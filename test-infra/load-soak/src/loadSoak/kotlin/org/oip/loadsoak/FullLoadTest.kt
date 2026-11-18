package org.oip.loadsoak

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.db.PasswordUpdate
import org.oip.gateway.Gateway
import org.oip.security.Permissions
import org.oip.tags.Level
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.io.path.writeText

/**
 * PRF-003 (ADR 0010): at least 50,000 value changes/s for 1 h on S8 with drivers, historian, alarms, and
 * clients active. Notification pipelines stay out.
 *
 * Topology, on one user-defined Docker bridge: the bench point simulator (`oip-bench-points`, every point
 * changing once per 1000 ms period) and TimescaleDB on its own container. The gateway runs in this JVM and dials
 * the simulator's bridge address (never loopback): half the points through one Modbus TCP `Device`, half through
 * one OPC UA `Connection`, all as `type: opc` tags scanned faster than the simulator period so no change is
 * aliased away. Every tag has on-change history to a SQL provider on the TimescaleDB container and one bit-mode
 * alarm on a middle bit, so the journal sees a steady trickle of transitions. [sessions] HMI sessions, one API
 * key each (one principal holds at most 8 sockets), subscribe [tagsPerSession] tags each on `/api/v1/ws`.
 *
 * Measured over the steady window (after warmup), by event timestamp:
 *  - value changes/s, counted by an independent tag engine subscriber on every tag;
 *  - history rows per tag in the window equal that subscriber's count and value sum per tag;
 *  - every change in the window has Good quality; alarm journal `lostRows` 0; collector dropped 0;
 *  - after the simulator is frozen, each session's last value per path equals the tag's value.
 * Cutoffs (CPU 50, memory 50, drift +/-10, 10 s samples; window mean and every 60 s mean) gate the window.
 *
 * CPU is this JVM, which also hosts the session clients, so it overstates gateway CPU.
 * Sizes are in test-infra/perf/prf-003.json. CI runs `ci`; `-PloadSoakSize=full` runs the floor size.
 */
class FullLoadTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun fullLoadHoldsTheRateWithDriversHistorianAlarmsAndSessions() {
        val req = requirements()
        val scenario = req["scenarios"].first { it["id"].asText() == "PRF-003" }
        val floor = scenario["threshold"].asInt()
        val size = sizes("PRF-003")
        val points = size["points"].asInt()
        val modbusPoints = size["modbusPoints"].asInt()
        val seconds = size["seconds"].asInt()
        val warmup = size["warmupSeconds"].asInt()
        val sessions = size["sessions"].asInt()
        val perSession = size["tagsPerSession"].asInt()
        val bit = size["alarmBit"].asInt()
        val scanMs = size["scanMillis"].asInt()
        val commsTimeoutMs = size["commsTimeoutMillis"].asInt()
        val sampleSeconds = req["cutoffs"]["sampleSeconds"].asInt()
        require(modbusPoints in 0..points && modbusPoints <= 10_000 * 247)
        if (sizeName() == "full") {
            assertTrue(points >= floor, "full size must offer the $floor changes/s floor, not $points")
            assertEquals(scenario["durationMinutes"].asInt() * 60, seconds, "full size must hold the floor for the ADR window")
            assertEquals(scenario["clients"].asInt(), sessions, "full size runs the ADR client count")
        }
        println(
            "PRF-003 size=${sizeName()} points=$points (modbus $modbusPoints, opc ${points - modbusPoints}) " +
                "window=${seconds}s sessions=$sessions x $perSession alarmBit=$bit scan=${scanMs}ms",
        )

        Bridge("oip-prf003").use { bridge ->
            assertEquals("bridge", bridge.driver, "container network driver")
            val image = System.getProperty("prf.simImage") ?: "oip-bench-points:ci"
            check(docker(true, "images", "-q", image).isNotBlank()) {
                "missing image $image. Build it with: docker build -t $image -f bench/sim/Dockerfile bench"
            }
            val sim = bridge.run(
                "benchsim", image, emptyMap(),
                "python", "-u", "/app/server.py", "--no-sparkplug", "--points", "$points", "--percent", "100",
                "--period-ms", "1000", "--registers-per-unit", "10000", "--modbus-extra-ports", "0",
                "--modbus-port", "5020", "--opcua-port", "4840", "--http-port", "8080",
            )
            val db = bridge.run(
                "timescale", Bridge.composeImage("timescale"),
                mapOf("POSTGRES_USER" to USER, "POSTGRES_PASSWORD" to PASSWORD, "POSTGRES_DB" to DB, "TIMESCALEDB_TELEMETRY" to "off"),
                "postgres", "-c", "max_connections=200",
            )
            bridge.awaitLog(sim, "READY", 180_000)
            val simIp = bridge.ip(sim)
            val dbIp = bridge.ip(db)
            val dbUrl = "jdbc:postgresql://$dbIp:5432/$DB"
            awaitTrue("timescale accepts connections", 180_000, { bridge.tail(db) }) {
                runCatching { DriverManager.getConnection(dbUrl, USER, PASSWORD).use { it.isValid(2) } }.getOrDefault(false)
            }
            val table = "prf003_" + System.nanoTime().toString().takeLast(6)

            tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
            Gateway(tmp, port = 0).use { gw ->
                gw.databases.saveConnection(
                    "load-pg",
                    mapOf("driver" to "postgres", "url" to dbUrl, "username" to USER, "maxWaitMillis" to 5_000,
                        "extraProperties" to mapOf("reWriteBatchedInserts" to "true")),
                    0,
                    PasswordUpdate.Set(PASSWORD),
                )
                gw.store.put(Scope.GATEWAY, Resource("HistoryProvider", Metadata("load-pg", "historian"),
                    mapOf("connection" to "load-pg", "table" to table, "timescale" to "required")))
                // Driver modules register the Device and Connection kinds when the gateway starts.
                gw.start()
                gw.store.put(Scope.GATEWAY, Resource("Device", Metadata("bench-mb", MODBUS_MODULE), mapOf(
                    "name" to "bench-mb", "host" to simIp, "port" to 5020, "mode" to "TCP", "unitId" to 1,
                    "zeroBasedAddressing" to false, "concurrentRequests" to 8, "defaultScanPeriodMs" to scanMs,
                    "maxHoldingRegistersPerRead" to 125, "spanGaps" to true, "timeoutMs" to 5_000,
                )))
                gw.store.put(Scope.GATEWAY, Resource("Connection", Metadata("bench-opc", OPC_MODULE), mapOf(
                    "name" to "bench-opc", "endpointUrl" to "opc.tcp://$simIp:4840/oip/bench",
                    "securityPolicy" to "NONE", "securityMode" to "NONE", "trustAllServerCertificates" to false,
                    "requestTimeoutMs" to 60_000, "sessionTimeoutMs" to 120_000,
                )))
                val importStart = System.nanoTime()
                importTags(gw, points, modbusPoints, bit, scanMs, commsTimeoutMs)
                val importSeconds = (System.nanoTime() - importStart) / 1e9

                // Independent change counter on every tag, keyed by the change's own timestamp.
                val counts = AtomicIntegerArray(points)
                val sums = AtomicLongArray(points)
                val notGood = AtomicLong()
                val lastChangeAt = AtomicLong(System.nanoTime())
                val recent = java.util.concurrent.ConcurrentLinkedDeque<String>()
                val window = AtomicLongArray(longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE))
                val counters = (0 until points).map { i ->
                    gw.tags.subscribe(path(i)) { change ->
                        val qv = change.value
                        lastChangeAt.set(System.nanoTime())
                        recent.addLast("${path(i)}=${qv.value}/${qv.quality.code}@${qv.timestamp}")
                        while (recent.size > 8) recent.pollFirst()
                        val ts = qv.timestamp.toEpochMilli()
                        if (ts < window.get(0) || ts >= window.get(1)) return@subscribe
                        if (qv.quality.code.level != Level.GOOD) {
                            notGood.incrementAndGet()
                            return@subscribe
                        }
                        counts.incrementAndGet(i)
                        sums.addAndGet(i, (qv.value as Number).toLong())
                    }
                }
                fun goodTags() = (0 until points).count { gw.tags.read(path(it)).quality.code.level == Level.GOOD }
                awaitTrue("every tag good", 300_000, { "good=${goodTags()}/$points ${bridge.tail(sim)}" }) { goodTags() == points }

                val http = HttpClient.newHttpClient()
                val clients = (0 until sessions).map { k ->
                    val key = gw.security.apiKeys.create(
                        "prf003-$k", setOf(Permissions.TAGS_READ), secureConnectionRequired = false, actor = "prf-003",
                    )
                    val paths = (0 until perSession).map { j -> path((k * perSession + j) % points) }
                    SessionClient(http, gw.port(), key.token, paths)
                }
                clients.forEach { it.open() }
                awaitTrue("every session has a first value on every path", 120_000, {
                    clients.joinToString { "${it.firstValues()}" } + " failures=" + clients.mapNotNull { it.sendFailure }
                }) { clients.all { it.firstValues() == perSession } }

                val sampler = VmSampler(sampleSeconds).start()
                Thread.sleep(warmup * 1_000L)
                val gwBacklog0 = gw.historyCollector.written
                val windowFrom = System.currentTimeMillis()
                window.set(0, windowFrom)
                sampler.phase = "steady"
                val updates0 = clients.sumOf { it.updates.get() }
                val journal0 = gw.alarms.journal.maxId()
                Thread.sleep(seconds * 1_000L)
                val windowTo = System.currentTimeMillis()
                window.set(1, windowTo)
                sampler.phase = "after"
                val updates1 = clients.sumOf { it.updates.get() }
                val journal1 = gw.alarms.journal.maxId()
                val windowSeconds = (windowTo - windowFrom) / 1_000.0
                sampler.close()
                val steady = sampler.samples().filter { it.phase == "steady" }
                val cut = Cutoffs(steady, sampleSeconds, req)

                // Freeze the simulator so the last values settle, then let history and sessions catch up.
                val frozenAt = freeze(simIp)
                // Staleness: how many simulator periods each tag's value is behind the simulator at the freeze.
                val modbusLag = staleness(gw, 0 until modbusPoints, frozenAt)
                val opcLag = staleness(gw, modbusPoints until points, frozenAt)
                println("PRF-003 staleness at freeze: modbus ${modbusLag.describe()} opc ${opcLag.describe()}")
                val settleStart = System.nanoTime()
                val settled = runCatching {
                    awaitTrue("changes stop after the freeze", 120_000, { "recent changes: ${recent.toList()}" }) {
                        System.nanoTime() - lastChangeAt.get() > 3_000_000_000L
                    }
                }
                val settleSeconds = (System.nanoTime() - settleStart) / 1e9
                println("PRF-003 settle after freeze ${round4(settleSeconds)} s ok=${settled.isSuccess} recent=${recent.toList()}")
                fun pending() = gw.historyRouter.buffer.pendingCounts()["load-pg"] ?: 0L
                val drainStart = System.nanoTime()
                awaitTrue("history forwarded", 300_000, { "collector=${gw.historyCollector.stats()} router=${gw.historyRouter.status()}" }) {
                    gw.historyCollector.pending == 0 && pending() == 0L
                }
                val drainSeconds = (System.nanoTime() - drainStart) / 1e9
                assertTrue(gw.alarms.journal.flush(java.time.Duration.ofSeconds(60)), "alarm journal flush")
                var staleSessions = 0
                awaitTrue("every session shows every tag's final value", 60_000, { "stale=$staleSessions" }) {
                    staleSessions = clients.sumOf { c -> c.stale { p -> gw.tags.read(p).value } }
                    staleSessions == 0
                }

                val changes = (0 until points).sumOf { counts.get(it).toLong() }
                val changesPerSecond = changes / windowSeconds
                val history = HistoryInWindow(dbUrl, table, windowFrom, windowTo)
                var countMismatches = 0
                var sumMismatches = 0
                for (i in 0 until points) {
                    val row = history.byTag[path(i).lowercase()]
                    if ((row?.first ?: 0L) != counts.get(i).toLong()) countMismatches++
                    if ((row?.second ?: 0L) != sums.get(i)) sumMismatches++
                }
                val lostJournal = gw.alarms.journal.lostRows
                val dropped = gw.historyCollector.dropped
                val quarantined = gw.historyRouter.buffer.quarantineCounts().values.sum()
                val sessionUpdatesPerSecond = (updates1 - updates0) / windowSeconds
                val journalRowsPerSecond = (journal1 - journal0) / windowSeconds
                val expectedRate = points.toDouble()
                val lossFree = countMismatches == 0 && sumMismatches == 0 && notGood.get() == 0L && lostJournal == 0L &&
                    dropped == 0L && quarantined == 0L && history.rows == changes && staleSessions == 0
                val report = linkedMapOf<String, Any?>(
                    "scenario" to "PRF-003",
                    "size" to sizeName(),
                    "label" to if (sizeName() == "full") "full-size" else "CI-size",
                    "floorChangesPerSecond" to floor,
                    "points" to points,
                    "modbusPoints" to modbusPoints,
                    "opcUaPoints" to points - modbusPoints,
                    "simulatorPeriodMs" to 1_000,
                    "scanMillis" to scanMs,
                    "commsTimeoutMillis" to commsTimeoutMs,
                    "sessions" to sessions,
                    "tagsPerSession" to perSession,
                    "alarmBit" to bit,
                    "warmupSeconds" to warmup,
                    "windowSeconds" to round4(windowSeconds),
                    "network" to "docker-bridge",
                    "measuredProcessors" to sampler.vcpu,
                    "measuredRamBytes" to sampler.ramBytes,
                    "cpuAccounting" to "processCpuTime / wall / availableProcessors, 10 s samples; the JVM also hosts the session clients",
                    "memoryAccounting" to "VmRSS / MemTotal",
                    "importSeconds" to round4(importSeconds),
                    "changesPerSecond" to round4(changesPerSecond),
                    "offeredChangesPerSecond" to expectedRate,
                    "changes" to changes,
                    "historyRows" to history.rows,
                    "historyWrittenInRun" to gw.historyCollector.written - gwBacklog0,
                    "historyCountMismatches" to countMismatches,
                    "historySumMismatches" to sumMismatches,
                    "historyDrainSeconds" to round4(drainSeconds),
                    "modbusChangesPerSecond" to round4((0 until modbusPoints).sumOf { counts.get(it).toLong() } / windowSeconds),
                    "opcUaChangesPerSecond" to round4((modbusPoints until points).sumOf { counts.get(it).toLong() } / windowSeconds),
                    "modbusStalenessPeriods" to modbusLag.toMap(),
                    "opcUaStalenessPeriods" to opcLag.toMap(),
                    "settleSeconds" to round4(settleSeconds),
                    "notGoodChanges" to notGood.get(),
                    "sessionUpdatesPerSecond" to round4(sessionUpdatesPerSecond),
                    "staleSessionValues" to staleSessions,
                    "journalRowsPerSecond" to round4(journalRowsPerSecond),
                    "journalLostRows" to lostJournal,
                    "collectorDropped" to dropped,
                    "quarantined" to quarantined,
                    "zeroLoss" to lossFree,
                ) + cut.toMap()
                writeReport("PRF-003", report, markdown(report, cut))
                println("PRF-003 report ${mapper.writeValueAsString(report)}")
                println(
                    "PRF-003 changes/s=${fmt(changesPerSecond)} offered=${fmt(expectedRate)} history=${history.rows} " +
                        "sessions=${fmt(sessionUpdatesPerSecond)}/s journal=${fmt(journalRowsPerSecond)}/s [${cut.describe()}]",
                )

                clients.forEach { it.close() }
                counters.forEach { it.close() }
                settled.getOrThrow()
                assertEquals(0L, notGood.get(), "changes without Good quality in the window")
                assertTrue(changesPerSecond >= expectedRate * 0.98, "changes/s ${fmt(changesPerSecond)} below the offered ${fmt(expectedRate)}")
                if (sizeName() == "full") assertTrue(changesPerSecond >= floor, "PRF-003 floor $floor, measured ${fmt(changesPerSecond)}")
                assertEquals(0L, dropped, "history collector dropped")
                assertEquals(0L, quarantined, "history quarantine")
                assertEquals(changes, history.rows, "history rows in the window vs counted changes")
                assertEquals(0, countMismatches, "tags whose history row count differs from their change count")
                assertEquals(0, sumMismatches, "tags whose history values differ from their changes")
                assertEquals(0L, lostJournal, "alarm journal lost rows")
                assertTrue(journalRowsPerSecond > 0.0, "the alarm journal saw transitions")
                assertTrue(sessionUpdatesPerSecond > 0.0, "sessions received updates")
                assertEquals(0, staleSessions, "session values behind the tag")
                assertTrue(cut.inside, "PRF-003 crossed a cutoff: ${cut.describe()}")
                assertTrue(lossFree, "PRF-003 zero loss")
            }
        }
    }

    private fun importTags(gw: Gateway, points: Int, modbusPoints: Int, bit: Int, scanMs: Int, commsTimeoutMs: Int) {
        val history = ArrayList<Resource>(points)
        var offset = 0
        while (offset < points) {
            val end = minOf(offset + BATCH, points)
            val batch = ArrayList<Resource>(end - offset)
            for (i in offset until end) {
                val (server, item) = if (i < modbusPoints) {
                    "bench-mb" to "${1 + i / 10_000}.HR${i % 10_000 + 1}"
                } else {
                    "bench-opc" to "ns=1;i=${1_000_000 + i}"
                }
                batch += Resource("Tag", Metadata(name(i), "tags"), mapOf(
                    "provider" to "default", "path" to "bench/${name(i)}", "type" to "opc", "dataType" to "Int32",
                    "opcServer" to server, "opcItemPath" to item, "scanPeriodMillis" to scanMs,
                    // Review-3 F6: the gateway default is now max(3 x scan, 5000 ms), which equals this size's 5000 ms
                    // at a 500 ms scan; the explicit value stays because the perf checker pins it.
                    "commsTimeoutMillis" to commsTimeoutMs,
                    "alarms" to listOf(mapOf("name" to "Bit", "mode" to "Bit", "bitPosition" to bit, "priority" to "Low")),
                ))
                history += Resource("HistorySettings", Metadata(name(i), "historian"),
                    mapOf("tagPath" to path(i), "storageProvider" to "load-pg"))
            }
            gw.store.putAll(Scope.GATEWAY, batch, actor = "prf-003", syncEach = false)
            offset = end
        }
        history.chunked(BATCH).forEach { gw.store.putAll(Scope.GATEWAY, it, actor = "prf-003", syncEach = false) }
    }

    private class Lag(val max: Long, val mean: Double, val behind: Int) {
        fun describe() = "max=$max mean=${round4(mean)} behind=$behind"
        fun toMap() = linkedMapOf("max" to max, "mean" to round4(mean), "tagsBehind" to behind)
    }

    /** Periods each tag trails the simulator, from the bench formula `(index % 1000 + tick) % 30000`. */
    private fun staleness(gw: Gateway, range: IntRange, atMs: Long): Lag {
        val tick = atMs / 1_000
        var max = 0L
        var sum = 0L
        var behind = 0
        for (i in range) {
            val v = (gw.tags.read(path(i)).value as? Number)?.toLong() ?: continue
            val expected = ((i % 1_000) + tick) % 30_000
            val lag = Math.floorMod(expected - v, 30_000L)
            if (lag > 1) behind++
            max = maxOf(max, lag)
            sum += lag
        }
        return Lag(max, if (range.isEmpty()) 0.0 else sum.toDouble() / (range.last - range.first + 1), behind)
    }

    /** Freeze the simulator's clock; returns the frozen instant in epoch millis. */
    private fun freeze(simIp: String): Long {
        val now = System.currentTimeMillis()
        val body = """{"frozen":true,"nowMs":$now}"""
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://$simIp:8080/control"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == 200) { "freeze -> ${response.statusCode()}: ${response.body()}" }
        return now
    }

    /** Rows per tag (count, value sum) with a timestamp in [from, to), read straight from TimescaleDB. */
    private class HistoryInWindow(url: String, table: String, from: Long, to: Long) {
        val byTag = HashMap<String, Pair<Long, Long>>()
        val rows: Long

        init {
            DriverManager.getConnection(url, USER, PASSWORD).use { c ->
                c.prepareStatement(
                    "SELECT lower(tag_path), count(*), sum(CAST(value_text AS bigint)) FROM $table " +
                        "WHERE ts >= to_timestamp(? / 1000.0) AND ts < to_timestamp(? / 1000.0) GROUP BY 1",
                ).use { ps ->
                    ps.setLong(1, from)
                    ps.setLong(2, to)
                    ps.executeQuery().use { rs -> while (rs.next()) byTag[rs.getString(1)] = rs.getLong(2) to rs.getLong(3) }
                }
            }
            rows = byTag.values.sumOf { it.first }
        }
    }

    /** One HMI session on `/api/v1/ws`, the frames the browser runtime sends (SPEC §6), with its own API key. */
    private class SessionClient(private val http: HttpClient, private val port: Int, private val token: String, private val paths: List<String>) {
        val updates = AtomicLong()
        private val last = ConcurrentHashMap<String, Any?>()
        private val seen = ConcurrentHashMap.newKeySet<String>()
        private var socket: WebSocket? = null
        /** java.net.http.WebSocket refuses a send while the previous one is pending, so sends are chained. */
        private var sends: CompletableFuture<WebSocket>? = null
        @Volatile var sendFailure: Throwable? = null

        @Synchronized
        private fun send(ws: WebSocket, frame: Map<String, Any?>) {
            val text = mapper.writeValueAsString(frame)
            val prev = sends ?: CompletableFuture.completedFuture(ws)
            sends = prev.thenCompose { it.sendText(text, true) }.whenComplete { _, e -> if (e != null) sendFailure = e }
        }

        fun firstValues(): Int = seen.size

        /** Paths whose last received value differs from [current]. */
        fun stale(current: (String) -> Any?): Int = paths.count { p -> numeric(last[p]) != numeric(current(p)) }

        fun open() {
            val nonce = UUID.randomUUID().toString()
            val opened = CountDownLatch(1)
            socket = http.newWebSocketBuilder()
                .header("Authorization", "Bearer $token")
                .buildAsync(URI.create("ws://127.0.0.1:$port/api/v1/ws"), object : WebSocket.Listener {
                    private val text = StringBuilder()

                    override fun onOpen(webSocket: WebSocket) {
                        send(webSocket, mapOf("type" to "hello", "nonce" to nonce))
                        opened.countDown()
                        webSocket.request(1)
                    }

                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        text.append(data)
                        if (last) {
                            handle(webSocket, mapper.readTree(text.toString()), nonce)
                            text.setLength(0)
                        }
                        webSocket.request(1)
                        return null
                    }

                    override fun onError(webSocket: WebSocket, error: Throwable) {
                        sendFailure = error
                        System.err.println("session error: $error")
                    }

                    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                        System.err.println("session closed: $statusCode $reason")
                        return null
                    }
                }).get(30, TimeUnit.SECONDS)
            check(opened.await(30, TimeUnit.SECONDS)) { "session socket did not open" }
        }

        private fun handle(ws: WebSocket, frame: JsonNode, nonce: String) {
            when (frame["type"]?.asText()) {
                "welcome" -> if (frame["nonce"]?.asText() == nonce) {
                    send(ws, mapOf("type" to "subscribe", "paths" to paths))
                }
                "update" -> for (u in frame["updates"]) {
                    val p = u["path"].asText()
                    val v = u["value"]
                    if (v == null || v.isNull) last.remove(p) else last[p] = v.asLong()
                    seen += p
                    updates.incrementAndGet()
                }
            }
        }

        fun close() {
            runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "done")?.get(5, TimeUnit.SECONDS) }
        }

        private fun numeric(v: Any?): Long? = (v as? Number)?.toLong()
    }

    private fun markdown(r: Map<String, Any?>, cut: Cutoffs): String {
        val label = r["label"]
        return """
            |# PRF-003 full load ($label)
            |
            |This is a $label run of `FullLoadTest` (`./gradlew :test-infra:load-soak:fullLoadTest`). It is not the 1 h, 50,000 changes/s floor run on S8; PRF-003 stays `implemented: false` in test-infra/perf/perf-requirements.json until that run exists. Sizes are in test-infra/perf/prf-003.json.
            |
            |Topology: the bench point simulator and TimescaleDB, each in its own container on one Docker bridge. The gateway in the test JVM dials the simulator's bridge address: ${r["modbusPoints"]} points through one Modbus TCP device and ${r["opcUaPoints"]} through one OPC UA connection, every point changing once per ${r["simulatorPeriodMs"]} ms, tags scanned every ${r["scanMillis"]} ms. Every tag has on-change history to TimescaleDB and one bit alarm (bit ${r["alarmBit"]}) with the journal on. ${r["sessions"]} sessions, one API key each, subscribe ${r["tagsPerSession"]} tags each on /api/v1/ws. Notification pipelines are off. Machine: ${r["measuredProcessors"]} vCPU, ${(r["measuredRamBytes"] as Long) shr 20} MiB. Window ${r["windowSeconds"]} s after ${r["warmupSeconds"]} s warmup.
            |
            || Metric | Measured | Cutoff or expected |
            ||---|---:|---:|
            || Value changes/s | ${r["changesPerSecond"]} | offered ${r["offeredChangesPerSecond"]}; floor ${r["floorChangesPerSecond"]} at full size |
            || CPU % mean | ${round4(cut.cpuMean)} | 50 |
            || CPU % worst 60 s | ${round4(cut.cpuWorst60)} | 50 |
            || Memory % mean | ${round4(cut.memMean)} | 50 |
            || Memory % worst 60 s | ${round4(cut.memWorst60)} | 50 |
            || Clock drift % mean | ${round4(cut.driftMean)} | +/-10 |
            || Clock drift % worst 60 s | ${round4(cut.driftWorst60)} | +/-10 |
            || Session updates/s | ${r["sessionUpdatesPerSecond"]} | |
            || Alarm journal rows/s | ${r["journalRowsPerSecond"]} | |
            |
            || Loss check | Value | Expected |
            ||---|---:|---:|
            || History rows in the window | ${r["historyRows"]} | ${r["changes"]} (counted changes) |
            || Tags with a row-count mismatch | ${r["historyCountMismatches"]} | 0 |
            || Tags with a value-sum mismatch | ${r["historySumMismatches"]} | 0 |
            || Changes without Good quality | ${r["notGoodChanges"]} | 0 |
            || Alarm journal lost rows | ${r["journalLostRows"]} | 0 |
            || History collector dropped / quarantined | ${r["collectorDropped"]} / ${r["quarantined"]} | 0 / 0 |
            || Session values behind the tag after the freeze | ${r["staleSessionValues"]} | 0 |
            |
            |Zero loss: ${r["zeroLoss"]}. Inside the cutoffs: ${r["insideCutoffs"]}. History drained ${r["historyDrainSeconds"]} s after the simulator froze.
            |
            |CPU is the test JVM, which hosts the gateway and the $label session clients, so it overstates gateway CPU. The simulator and the database run in their own containers on the same machine; their CPU is not counted.
            |""".trimMargin()
    }

    private companion object {
        const val USER = "oip"
        const val PASSWORD = "oip"
        const val DB = "oip"
        const val BATCH = 5_000
        const val MODBUS_MODULE = "org.oip.driver-modbus"
        const val OPC_MODULE = "org.oip.driver-opcua-client"

        fun name(i: Int) = "p%07d".format(i)
        fun path(i: Int) = "[default]bench/${name(i)}"
    }
}
