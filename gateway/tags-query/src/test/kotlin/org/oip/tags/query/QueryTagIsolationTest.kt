package org.oip.tags.query

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.db.DatabaseService
import org.oip.db.PasswordUpdate
import org.oip.tags.DataType
import org.oip.tags.QualityCode
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagGroupDef
import org.oip.tags.TagType
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * A slow named connection must not stall query tags on another connection, and a tag whose poll is
 * still running is not queued again.
 *
 * (the value is polled at the tag group rate; a shared timer is not required by that page).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class QueryTagIsolationTest {
    @TempDir
    lateinit var tmp: Path

    private val suffix = java.lang.Long.toHexString(System.nanoTime() and 0xffffff)
    private lateinit var db: Connections
    private lateinit var engine: TagEngine
    private lateinit var runtime: QueryTagRuntime

    @BeforeEach
    fun setUp() {
        val url = PostgresServer.require()
        db = Connections(tmp)
        db.connect("blocked", url)
        db.connect("other", url)
        engine = TagEngine()
        runtime = QueryTagRuntime(engine, db.db).also { it.attach() }
    }

    @AfterEach
    fun tearDown() {
        if (::runtime.isInitialized) runtime.close()
        if (::db.isInitialized) db.close()
    }

    @Test
    fun aBlockingConnectionDoesNotDelayAQueryTagOnAnotherConnection() {
        val rate = 500L
        val marker = "oip-iso-slow-$suffix"
        engine.configureGroup(TagGroupDef("fast", rateMillis = rate))
        engine.configure(query("Line/Slow", "/* $marker */ SELECT pg_sleep(30)", "blocked", DataType.STRING))
        engine.configure(query("Line/Fast", "SELECT gen_random_uuid()::text", "other", DataType.STRING, "fast"))

        val polls = ConcurrentLinkedQueue<Long>()
        engine.subscribe("Line/Fast") { polls.add(System.nanoTime()) }
        runtime.start()

        assertTrue(
            waitUntil(8_000) { active(marker) >= 1 },
            "the blocking poll never reached PostgreSQL",
        )
        val windowMs = 3_500L
        val deadline = System.nanoTime() + windowMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            assertEquals(
                QualityCode.UNCERTAIN_INITIAL_VALUE,
                engine.read("Line/Slow").quality.code,
                "the slow poll finished during the window, so it was not blocking",
            )
            Thread.sleep(40)
        }
        val times = polls.toList()
        val gaps = gapsMillis(times)
        println("QUERY-TAG isolation fastPolls=${times.size} gapsMs=$gaps while pg_sleep was active")
        assertTrue(times.size >= 4, "fast connection published ${times.size} times in ${windowMs}ms; a shared scheduler would stall it behind pg_sleep")
        assertTrue(gaps.all { it >= rate / 2 }, "fast polls ran ahead of the ${rate}ms group rate: $gaps")
        assertTrue(
            gaps.all { it <= rate + 1_000 },
            "a poll on '$marker' stretched the other connection past its ${rate}ms group rate: $gaps",
        )
    }

    @Test
    fun overlappingPollsOfTheSameTagDoNotQueue() {
        val rate = 200L
        val table = "q_gate_$suffix"
        val marker = "oip-iso-gate-$suffix"
        db.sql("blocked", "CREATE TABLE $table (block boolean NOT NULL)")
        db.sql("blocked", "INSERT INTO $table (block) VALUES (true)")
        val sql = """
            /* $marker */
            SELECT gen_random_uuid()::text
            FROM (SELECT pg_sleep(CASE WHEN (SELECT block FROM $table) THEN 8 ELSE 0 END)) held
        """.trimIndent()
        engine.configureGroup(TagGroupDef("fast", rateMillis = rate))
        engine.configure(query("Line/Gate", sql, "blocked", DataType.STRING, "fast"))

        val polls = ConcurrentLinkedQueue<Long>()
        engine.subscribe("Line/Gate") { polls.add(System.nanoTime()) }
        runtime.start()

        assertTrue(waitUntil(8_000) { active(marker) >= 1 }, "the blocking poll never reached PostgreSQL")
        val sampleEnd = System.nanoTime() + 2_500_000_000L
        var maxActive = 1
        while (System.nanoTime() < sampleEnd) {
            maxActive = maxOf(maxActive, active(marker))
            assertEquals(0, polls.size, "a second poll completed while the first was still in pg_sleep")
            Thread.sleep(40)
        }
        assertTrue(maxActive <= 1, "overlapping polls ran at the same time (max active $maxActive)")

        db.sql("blocked", "UPDATE $table SET block = false")
        assertTrue(waitUntil(12_000) { polls.isNotEmpty() }, "the in-flight poll did not finish")
        Thread.sleep(1_200)
        val times = polls.toList()
        val gaps = gapsMillis(times)
        val short = gaps.count { it < 80 }
        println("QUERY-TAG overlap polls=${times.size} shortGaps=$short gapsMs=$gaps")
        assertTrue(times.size >= 3, "polling did not resume after the blocked query, saw ${times.size}")
        assertTrue(short <= 1, "polls piled up after the blocked one finished: $short gaps under 80ms ($gaps)")
        db.sql("blocked", "DROP TABLE IF EXISTS $table")
    }

    @Test
    fun closeStopsEveryLane() {
        val markerA = "oip-iso-close-a-$suffix"
        val markerB = "oip-iso-close-b-$suffix"
        engine.configureGroup(TagGroupDef("fast", rateMillis = 200))
        engine.configure(query("Line/A", "/* $markerA */ SELECT pg_sleep(60)", "blocked", DataType.STRING, "fast"))
        engine.configure(query("Line/B", "/* $markerB */ SELECT pg_sleep(60)", "other", DataType.STRING, "fast"))
        runtime.start()

        assertTrue(
            waitUntil(8_000) { active(markerA) >= 1 && active(markerB) >= 1 },
            "both connections did not have a poll in flight (lanes=${laneCount()})",
        )
        val busy = laneCount()
        assertTrue(busy >= 2, "expected one live lane thread per busy connection, saw $busy (${laneNames()})")
        runtime.close()
        assertTrue(waitUntil(4_000) { laneCount() == 0 }, "close left lane threads: ${laneNames()}")
        assertEquals(0, tickerCount(), "close left the ticker running")
    }

    private fun query(
        path: String,
        sql: String,
        datasource: String,
        dataType: DataType,
        tagGroup: String = "fast",
    ) = TagConfig(
        path = path,
        type = TagType.QUERY,
        dataType = dataType,
        query = sql,
        datasource = datasource,
        tagGroup = tagGroup,
    )

    private fun active(marker: String): Int = db.jdbc("blocked") { connection ->
        connection.prepareStatement(
            """
            SELECT count(*) FROM pg_stat_activity
            WHERE datname = current_database()
              AND state = 'active'
              AND query LIKE ?
              AND pid <> pg_backend_pid()
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "%$marker%")
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun gapsMillis(times: List<Long>): List<Long> =
        times.zipWithNext { a, b -> (b - a) / 1_000_000L }

    private fun waitUntil(timeoutMs: Long, pred: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (pred()) return true
            Thread.sleep(20)
        }
        return pred()
    }

    private fun laneCount() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == QueryTagRuntime.LANE }

    private fun laneNames() = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name == QueryTagRuntime.LANE }
        .joinToString { it.name }

    private fun tickerCount() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == QueryTagRuntime.THREAD }
}

private class Connections(dir: Path) : AutoCloseable {
    private val registry = SchemaRegistry()
    private val store = FileConfigStore(dir.resolve("config"), registry)
    private val secrets = linkedMapOf<String, String>()
    val db: DatabaseService = DatabaseService(
        store,
        dir.resolve("data"),
        Clock.systemUTC(),
        readSecret = { reference -> secrets[reference.removePrefix("secret://")] ?: error("missing secret") },
        writeSecret = { name, plaintext, _ ->
            secrets[name] = plaintext
            "secret://$name"
        },
    ).also { it.attach() }

    fun connect(name: String, jdbcUrl: String) {
        db.saveConnection(
            name,
            mapOf(
                "driver" to "postgres",
                "url" to jdbcUrl,
                "username" to PostgresServer.USER,
                "enabled" to true,
            ),
            0,
            PasswordUpdate.Set(PostgresServer.PASSWORD),
        )
    }

    fun sql(name: String, statement: String) {
        jdbc(name) { connection -> connection.createStatement().use { it.execute(statement) } }
    }

    fun <T> jdbc(name: String, block: (Connection) -> T): T = db.withConnection(name) { block(it) }

    override fun close() {
        db.close()
        store.close()
    }
}
