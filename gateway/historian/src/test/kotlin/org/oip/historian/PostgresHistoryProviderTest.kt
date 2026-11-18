package org.oip.historian

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.Quality
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * [SqlHistoryProvider] against a real PostgreSQL/TimescaleDB, with an in-process TCP fault proxy in front for
 * the network cut. Uses `OIP_HISTORY_PG_URL` when it is set (for example
 * `jdbc:postgresql://localhost:25432/oip?user=oip&password=oip`); otherwise it starts the TimescaleDB image the
 * compose stack pins, through Testcontainers, so the `build` job runs it (review-3 F10). Without Docker it is
 * skipped on a developer machine and fails in CI (`CI=true`), so CI can never pass it by skipping.
 */
class PostgresHistoryProviderTest {
    companion object {
        /** Same image and digest as `test-infra/compose.yaml` service `timescale`. */
        private const val IMAGE = "timescale/timescaledb:2.31.0-pg16-oss@sha256:1bfcf994089b9c97b4ddfa07f2ff9eec430709715e419d9a6957d1a4b4614cd1"
        private var container: GenericContainer<*>? = null
        private var sharedUrl: String? = System.getenv("OIP_HISTORY_PG_URL")?.takeIf { it.isNotBlank() }

        @JvmStatic
        @BeforeAll
        fun startDatabase() {
            if (sharedUrl != null) return
            val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
            if (!docker) {
                check(System.getenv("CI") != "true") { "CI must run PostgresHistoryProviderTest: no Docker and no OIP_HISTORY_PG_URL" }
                return
            }
            val db = GenericContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("timescale/timescaledb"))
                .withEnv("POSTGRES_USER", "oip")
                .withEnv("POSTGRES_PASSWORD", "oip")
                .withEnv("POSTGRES_DB", "oip")
                .withEnv("TIMESCALEDB_TELEMETRY", "off")
                .withExposedPorts(5432)
                // The image's init runs a temporary server first, so wait for the second "ready" line.
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                    .withStartupTimeout(Duration.ofMinutes(3)))
            db.start()
            container = db
            sharedUrl = "jdbc:postgresql://${db.host}:${db.getMappedPort(5432)}/oip?user=oip&password=oip"
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() {
            container?.stop()
            container = null
        }
    }

    @TempDir lateinit var tmp: Path

    private val url: String? get() = sharedUrl
    private val t0 = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = FakeClock(t0)
    private lateinit var table: String
    private val tag = "[default]Line/Count"

    @BeforeEach
    fun setUp() {
        assumeTrue(!url.isNullOrBlank(), "no Docker and OIP_HISTORY_PG_URL is not set (CI fails instead, see startDatabase)")
        table = "oip_test_" + System.nanoTime().toString().takeLast(9)
    }

    @AfterEach
    fun dropTable() {
        if (url.isNullOrBlank()) return
        DriverManager.getConnection(url).use { c -> c.createStatement().use { it.execute("DROP TABLE IF EXISTS $table CASCADE") } }
    }

    private fun sample(i: Int, value: Any? = i, path: String = tag) = HistorySample(path, t0.toEpochMilli() + i * 1000L, value, Quality.GOOD)

    private fun provider(jdbcUrl: String = url!!, timescale: TimescaleMode = TimescaleMode.AUTO) =
        SqlHistoryProvider("pg", JdbcConnectionSource.driverManager(jdbcUrl, properties = mapOf("connectTimeout" to "2", "socketTimeout" to "10")), table, timescale)

    /** [size] distinct connections. [JdbcConnectionSource.supportsConcurrentBorrow] is what turns on two COPY lanes. */
    private fun pool(jdbcUrl: String, size: Int): JdbcConnectionSource = object : JdbcConnectionSource {
        private val idle = ArrayDeque<java.sql.Connection>()
        private val all = ArrayList<java.sql.Connection>()
        private val properties = java.util.Properties().apply {
            setProperty("connectTimeout", "5")
            setProperty("socketTimeout", "60")
        }

        @Synchronized
        override fun borrow(): java.sql.Connection {
            while (idle.isNotEmpty()) {
                val next = idle.removeFirst()
                if (!next.isClosed) return next
            }
            check(all.size < size) { "history test pool has no free connection" }
            // A proxy, the way a pool hands out connections: COPY must unwrap to the PostgreSQL connection.
            return wrap(DriverManager.getConnection(jdbcUrl, properties)).also { all += it }
        }

        @Synchronized
        override fun release(connection: java.sql.Connection, broken: Boolean) {
            if (broken || connection.isClosed) {
                all.remove(connection)
                runCatching { connection.close() }
            } else {
                idle.add(connection)
            }
        }

        @Synchronized
        override fun close() {
            all.forEach { runCatching { it.close() } }
            all.clear()
            idle.clear()
        }

        override fun supportsConcurrentBorrow() = size > 1
    }

    /** Delegates to [raw] and unwraps the way a pooled connection does, so COPY can see the driver connection. */
    private fun wrap(raw: java.sql.Connection): java.sql.Connection =
        Proxy.newProxyInstance(raw.javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
            try {
                when (method.name) {
                    "unwrap" -> {
                        val type = args[0] as Class<*>
                        if (type.isInstance(raw)) raw else raw.unwrap(type)
                    }
                    "isWrapperFor" -> {
                        val type = args[0] as Class<*>
                        type.isInstance(raw) || raw.isWrapperFor(type)
                    }
                    else -> if (args == null) method.invoke(raw) else method.invoke(raw, *args)
                }
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        } as java.sql.Connection

    private fun timescaleAvailable(): Boolean = DriverManager.getConnection(url).use { c ->
        c.createStatement().use { st ->
            val available = st.executeQuery("SELECT 1 FROM pg_available_extensions WHERE name = 'timescaledb'").use { it.next() }
            if (available) st.execute("CREATE EXTENSION IF NOT EXISTS timescaledb")
            available
        }
    }

    @Test
    fun writesAreIdempotentTypedAndQueryableAndTheTableIsAHypertableWhenTimescaleIsInstalled() {
        val hasTimescale = timescaleAvailable()
        provider().use { p ->
            val records = listOf(sample(1, 1), sample(2, 2.5), sample(3, true), sample(4, "text"), sample(5, null), sample(6, 9_000_000_000L))
                .mapIndexed { i, s -> ForwardRecord("src", i + 1L, s) }
            p.write(records)
            p.write(records.take(3)) // resent after a lost acknowledgement: stored once
            assertEquals(hasTimescale, p.hypertable)
            val rows = p.query(HistoryQuery("[DEFAULT]line/count", 0, Long.MAX_VALUE))
            assertEquals(listOf(1, 2.5, true, "text", null, 9_000_000_000L), rows.map { it.value })
            assertEquals(listOf(2.5, true), p.query(HistoryQuery(tag, sample(2).timestampMillis, sample(3).timestampMillis)).map { it.value })
            assertEquals(1, p.latestBefore(tag, sample(2).timestampMillis)!!.value)
            val scanned = ArrayList<Any?>()
            p.scan(tag, 0, Long.MAX_VALUE) { scanned += it.value }
            assertEquals(rows.map { it.value }, scanned)
            val agg = HistoryAggregator.aggregate(p, tag, sample(1).timestampMillis, sample(3).timestampMillis + 999, 1000, HistoryAggregate.MAXIMUM)
            assertEquals(listOf(1.0, 2.5, 1.0), agg.map { it.value })
        }
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT value_num FROM $table WHERE seq = 2").use { rs -> rs.next(); assertEquals(2.5, rs.getDouble(1)) }
                val isHyper = hasTimescale && st.executeQuery(
                    "SELECT 1 FROM timescaledb_information.hypertables WHERE hypertable_name = '$table'"
                ).use { it.next() }
                assertEquals(hasTimescale, isHyper)
            }
        }
    }

    @Test
    fun timescaleOffKeepsAPlainTable() {
        timescaleAvailable()
        provider(timescale = TimescaleMode.OFF).use { p ->
            p.write(listOf(ForwardRecord("src", 1, sample(1))))
            assertFalse(p.hypertable)
        }
    }

    @Test
    fun dataTheDatabaseRejectsIsPermanentAndGoesToQuarantineWithoutBlockingTheQueue() {
        provider().use { p ->
            // PostgreSQL text cannot hold NUL: SQLSTATE class 22, a permanent rejection of that record.
            val e = runCatching { p.write(listOf(ForwardRecord("src", 1, sample(1, "bad\u0000value")))) }.exceptionOrNull()
            assertTrue(e is HistoryWriteException && e.permanent, "$e")
            HistoryRouter(MemoryHistorySink(), ForwardBuffer(tmp.resolve("fwd.sqlite")), clock, batchSize = 3).use { r ->
                r.register(p)
                r.route(tag, "pg")
                r.appendAll(listOf(sample(1), sample(2, "bad\u0000value"), sample(3), sample(4)))
                assertEquals(3, r.forward())
                assertEquals(listOf<Any?>(1, 3, 4), p.query(HistoryQuery(tag, 0, Long.MAX_VALUE)).map { it.value })
                assertEquals(1, r.buffer.quarantined("pg").size)
            }
        }
    }

    @Test
    fun aNetworkCutMidStreamLosesNoSampleAndDuplicatesNone() {
        val base = url!!
        val target = URI(base.removePrefix("jdbc:"))
        FaultProxy(target.host, if (target.port > 0) target.port else 5432).use { proxy ->
            val proxied = base.replaceFirst("${target.host}:${target.port}", "127.0.0.1:${proxy.port}")
            HistoryRouter(MemoryHistorySink(), ForwardBuffer(tmp.resolve("fwd.sqlite")), clock, retryMillis = 1_000, batchSize = 50).use { r ->
                val p = provider(proxied)
                r.register(p)
                r.route(tag, "pg")
                var n = 0
                fun produce(k: Int) = repeat(k) { n++; r.appendAll(listOf(sample(n))) }
                produce(120)
                assertEquals(120, r.forward())
                proxy.cut() // live connections reset, new ones refused
                produce(200)
                r.forward()
                assertEquals(200L, r.buffer.pendingCounts()["pg"])
                clock.advance(Duration.ofSeconds(2))
                produce(80)
                r.forward() // still cut: retried and failed
                assertEquals(280L, r.buffer.pendingCounts()["pg"])
                proxy.restore()
                clock.advance(Duration.ofSeconds(2))
                produce(50)
                r.forward()
                assertEquals(emptyMap<String, Long>(), r.buffer.pendingCounts())
                val got = p.query(HistoryQuery(tag, 0, Long.MAX_VALUE, HistoryQuery.MAX_LIMIT)).map { it.value as Int }
                assertEquals((1..n).toList(), got, "every sample exactly once, in order")
            }
        }
    }

    /**
     * PRF-008 stretch is 30,000 changes/s. Build that backlog against this class's Timescale container (the SQL
     * target the build job uses), then keep ingesting at the same rate while [HistoryRouter.forward] runs at
     * the production batch size. The forward rate has to exceed ingest, and the backlog has to shrink.
     *
     * Enqueue chunks are 7,500 rows, the size a 250 ms collector flush holds at this rate
     * ([org.oip.historian.HistoryCollector] writes at most 10,000 per flush).
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    fun forwarderDrainsAThirtyThousandPerSecondBacklogFasterThanItGrows() {
        assertTrue(timescaleAvailable(), "CI Timescale image is missing the timescaledb extension")
        val tags = 3_000
        val pace = 30_000
        val paths = Array(tags) { "[default]bench/t%04d".format(it) }
        val t0 = this.t0.toEpochMilli()
        var produced = 0
        var rAppend: (List<HistorySample>) -> Unit = { error("router is not open") }
        fun chunk(n: Int): List<HistorySample> {
            val out = ArrayList<HistorySample>(n)
            repeat(n) {
                val i = produced
                val tagIndex = i % tags
                out += HistorySample(paths[tagIndex], t0 + i, i / tags, Quality.GOOD)
                produced++
            }
            return out
        }
        fun paceProduce(count: Int, onChunk: () -> Unit = {}) {
            val start = System.nanoTime()
            val origin = produced
            val target = origin + count
            while (produced < target) {
                val due = start + (produced - origin) * 1_000_000_000L / pace
                val waitNs = due - System.nanoTime()
                if (waitNs > 2_000_000L) Thread.sleep(waitNs / 1_000_000L)
                val n = minOf(7_500, target - produced)
                rAppend(chunk(n))
                onChunk()
            }
        }

        // Two connections, as the gateway pool does (db/Connection maxActive defaults to 8). One tag stays on one lane.
        SqlHistoryProvider("pg", pool(url!!, 2), table, TimescaleMode.REQUIRED).use { p ->
            HistoryRouter(MemoryHistorySink(), ForwardBuffer(tmp.resolve("fwd-bench.sqlite"))).use { r ->
                rAppend = { r.appendAll(it) }
                for (path in paths) r.route(path, "pg")
                r.register(p)
                fun pending() = r.buffer.pendingCounts()["pg"] ?: 0L
                fun forwarded() = (r.status().single { it["name"] == "pg" }["forwarded"] as Number).toLong()

                paceProduce(5_000)
                assertEquals(5_000, r.forward(), "warmup forward")
                assertEquals(0L, pending())
                assertTrue(p.hypertable, "benchmark must hit a hypertable, the production SQL target")

                val buildStart = System.nanoTime()
                paceProduce(60_000)
                val buildSeconds = (System.nanoTime() - buildStart) / 1e9
                val buildRate = 60_000 / buildSeconds
                assertTrue(buildRate >= 29_000, "enqueue could not build a 30000/s backlog (got ${"%.0f".format(buildRate)}/s)")
                val built = pending()
                assertTrue(built >= 55_000L, "backlog after the paced build was $built")

                val trend = ArrayList<Long>()
                val measureStart = System.nanoTime()
                val forwarded0 = forwarded()
                val pending0 = pending()
                trend += pending0
                var nextMark = measureStart + 1_000_000_000L
                val running = AtomicBoolean(true)
                val forwardErrors = Collections.synchronizedList(ArrayList<Throwable>())
                val forwarder = Thread({
                    while (running.get()) {
                        try {
                            r.forward()
                        } catch (e: Throwable) {
                            forwardErrors += e
                            break
                        }
                    }
                }, "sql-forward-bench").apply { isDaemon = true; start() }
                val pending1: Long
                val forwarded1: Long
                val measureSeconds: Double
                try {
                    paceProduce(180_000) {
                        val now = System.nanoTime()
                        while (now >= nextMark) {
                            trend += pending()
                            nextMark += 1_000_000_000L
                        }
                    }
                    measureSeconds = (System.nanoTime() - measureStart) / 1e9
                    pending1 = pending()
                    forwarded1 = forwarded()
                    trend += pending1
                } finally {
                    running.set(false)
                    forwarder.join()
                }
                val drainDeadline = System.nanoTime() + 90_000_000_000L
                while (pending() > 0L && System.nanoTime() < drainDeadline) r.forward()

                val ingestRate = 180_000 / measureSeconds
                val forwardRate = (forwarded1 - forwarded0) / measureSeconds
                val growthPerMinute = (pending1 - pending0) / (measureSeconds / 60.0)
                val cpu = runCatching { Files.readAllLines(Path.of("/proc/cpuinfo")).firstOrNull { it.startsWith("model name") } }.getOrNull() ?: "unknown"
                val mem = runCatching { Files.readAllLines(Path.of("/proc/meminfo")).firstOrNull { it.startsWith("MemTotal") } }.getOrNull() ?: "unknown"
                println(
                    "SQL forward benchmark ingest=${"%.0f".format(ingestRate)}/s forward=${"%.0f".format(forwardRate)}/s " +
                        "backlogStart=$pending0 backlogEnd=$pending1 growthPerMinute=${"%.0f".format(growthPerMinute)} " +
                        "trend=$trend batch=${HistoryRouter.DEFAULT_BATCH} buildRate=${"%.0f".format(buildRate)}/s " +
                        "processors=${Runtime.getRuntime().availableProcessors()} $cpu $mem",
                )

                assertTrue(forwardErrors.isEmpty(), "forward thread failed: $forwardErrors")
                assertTrue(ingestRate >= 29_000, "ingest during the drain window was ${"%.0f".format(ingestRate)}/s")
                assertTrue(forwardRate > pace, "forward ${"%.0f".format(forwardRate)}/s did not beat $pace/s ingest")
                assertTrue(pending1 < pending0, "backlog grew from $pending0 to $pending1 (${"%.0f".format(growthPerMinute)}/min)")
                assertEquals(0L, pending(), "rows left in the buffer after the drain")
                assertEquals(0, r.buffer.quarantined().size)
                val expected = produced.toLong()
                DriverManager.getConnection(url).use { c ->
                    fun long(sql: String) = c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
                    val rows = long("SELECT count(*) FROM $table")
                    val distinct = long("SELECT count(*) FROM (SELECT DISTINCT source_id, seq FROM $table) d")
                    assertEquals(expected, rows, "lost or extra sample")
                    assertEquals(rows, distinct, "duplicate (source_id, seq)")
                    for (tagIndex in intArrayOf(0, 1, tags / 2, tags - 1)) {
                        c.prepareStatement("SELECT value_text FROM $table WHERE tag_path = ? ORDER BY ts, source_id, seq").use { ps ->
                            ps.setString(1, paths[tagIndex])
                            ps.executeQuery().use { rs ->
                                var expect = 0
                                while (rs.next()) {
                                    assertEquals(expect.toString(), rs.getString(1), "tag ${paths[tagIndex]} out of order at $expect")
                                    expect++
                                }
                                assertTrue(expect > 1, "tag ${paths[tagIndex]} stored $expect rows")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Two COPY lanes (the gateway pool lends more than one connection) still store a resent batch once, and each
     * tag stays in seq order.
     */
    @Test
    fun aParallelResendStoresEachSampleOnceAndKeepsPerTagOrder() {
        assertTrue(timescaleAvailable())
        val other = "[default]Line/Other"
        val records = (0 until 4_000).map { i ->
            val path = if (i % 2 == 0) tag else other
            ForwardRecord("src", i + 1L, sample(i, path = path))
        }
        SqlHistoryProvider("pg", pool(url!!, 2), table, TimescaleMode.REQUIRED).use { p ->
            p.write(records)
            p.write(records)
            assertTrue(p.hypertable)
            assertEquals((0 until 4_000 step 2).toList(), p.query(HistoryQuery(tag, 0, Long.MAX_VALUE, HistoryQuery.MAX_LIMIT)).map { it.value })
            assertEquals((1 until 4_000 step 2).toList(), p.query(HistoryQuery(other, 0, Long.MAX_VALUE, HistoryQuery.MAX_LIMIT)).map { it.value })
        }
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                val rows = st.executeQuery("SELECT count(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) }
                val distinct = st.executeQuery("SELECT count(*) FROM (SELECT DISTINCT source_id, seq FROM $table) d").use { rs -> rs.next(); rs.getLong(1) }
                assertEquals(4_000L, rows)
                assertEquals(rows, distinct)
            }
        }
    }

    /**
     * A reset while a batch is on the wire must not drop or duplicate samples. The proxy forwards the start
     * of the COPY and then RSTs the sockets; the forwarder retries the same buffer records.
     * https://www.postgresql.org/docs/current/sql-copy.html (COPY is one transaction here)
     * - (store-and-forward: no loss)
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    fun killingTheConnectionMidBatchLosesNothingAndDuplicatesNothing() {
        val base = url!!
        val target = URI(base.removePrefix("jdbc:"))
        FaultProxy(target.host, if (target.port > 0) target.port else 5432).use { proxy ->
            val proxied = base.replaceFirst("${target.host}:${target.port}", "127.0.0.1:${proxy.port}")
            val batch = 4_000
            HistoryRouter(MemoryHistorySink(), ForwardBuffer(tmp.resolve("fwd-kill.sqlite")), clock, retryMillis = 1_000, batchSize = batch).use { r ->
                val p = provider(proxied)
                r.register(p)
                r.route(tag, "pg")
                r.appendAll(listOf(sample(0)))
                assertEquals(1, r.forward(), "schema and the first sample")
                proxy.armKillAfterMoreClientBytes(16 * 1024)
                r.appendAll((1..batch).map { sample(it) })
                val wrote = r.forward()
                assertTrue(proxy.killedMidBatch, "the connection was not killed")
                assertTrue(wrote < batch, "the batch committed before the kill (wrote $wrote of $batch)")
                clock.advance(Duration.ofSeconds(2))
                r.forward()
                assertEquals(0L, r.buffer.pendingCounts()["pg"] ?: 0L)
                assertEquals(0, r.buffer.quarantined().size)
                val got = p.query(HistoryQuery(tag, 0, Long.MAX_VALUE, HistoryQuery.MAX_LIMIT)).map { it.value as Int }
                assertEquals((0..batch).toList(), got, "every sample exactly once, in order")
                DriverManager.getConnection(url).use { c ->
                    c.createStatement().use { st ->
                        val rows = st.executeQuery("SELECT count(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) }
                        val distinct = st.executeQuery("SELECT count(*) FROM (SELECT DISTINCT source_id, seq FROM $table) d").use { rs -> rs.next(); rs.getLong(1) }
                        assertEquals(rows, distinct, "duplicate (source_id, seq)")
                        assertEquals((batch + 1).toLong(), rows)
                    }
                }
            }
        }
    }

    /** TCP proxy whose [cut] resets live connections and refuses new ones until [restore]. */
    private class FaultProxy(private val host: String, private val upstreamPort: Int) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        @Volatile private var open = true
        @Volatile private var killAfterClientBytes = Long.MAX_VALUE
        @Volatile var killedMidBatch: Boolean = false
            private set
        private val clientToServer = AtomicLong()
        private val live = Collections.synchronizedList(ArrayList<Socket>())

        init {
            thread(isDaemon = true, name = "fault-proxy") {
                while (!server.isClosed) {
                    val down = runCatching { server.accept() }.getOrNull() ?: break
                    if (!open) { abort(down); continue }
                    val up = runCatching { Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, upstreamPort), 2_000) } }.getOrNull()
                    if (up == null) { abort(down); continue }
                    down.tcpNoDelay = true
                    live += down; live += up
                    pump(down, up, fromClient = true); pump(up, down, fromClient = false)
                }
            }
        }

        /** RST the live connection once [more] further bytes have gone client to server. Later connections stay up. */
        fun armKillAfterMoreClientBytes(more: Long) {
            killedMidBatch = false
            killAfterClientBytes = clientToServer.get() + more
        }

        private fun pump(from: Socket, to: Socket, fromClient: Boolean) = thread(isDaemon = true) {
            val buf = ByteArray(16 * 1024)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val k = input.read(buf)
                    if (k < 0) break
                    if (fromClient) {
                        val seen = clientToServer.addAndGet(k.toLong())
                        output.write(buf, 0, k)
                        output.flush()
                        if (seen >= killAfterClientBytes) {
                            killAfterClientBytes = Long.MAX_VALUE
                            killedMidBatch = true
                            abort(from)
                            abort(to)
                            break
                        }
                    } else {
                        output.write(buf, 0, k)
                        output.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                abort(from); abort(to)
            }
        }

        private fun abort(s: Socket) = runCatching { s.setSoLinger(true, 0); s.close() }

        fun cut() {
            open = false
            synchronized(live) { live.forEach { abort(it) }; live.clear() }
        }

        fun restore() { open = true }

        override fun close() {
            runCatching { server.close() }
            cut()
        }
    }
}
