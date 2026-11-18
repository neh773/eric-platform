package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Zero sample loss across a provider network cut (PLAN phase 3), through the whole pipeline: tag engine ->
 * collector (listener only enqueues) -> router -> SQLite forward buffer -> provider. The provider link is cut
 * mid-stream (every call fails, and the batch in flight when it drops is committed without an
 * acknowledgement), sampling continues through the outage and a gateway restart, the link comes back, and
 * every sample must arrive exactly once, in order.
 *
 * This runs in the `build` job with an in-process provider. The same scenario against TimescaleDB through
 * Toxiproxy runs in the simulators job (`SimulatorHistoryTest`), and against any Postgres with
 * `OIP_HISTORY_PG_URL` set (`PostgresHistoryProviderTest`).
 */
class NetworkCutZeroLossTest {
    @TempDir lateinit var tmp: Path

    private val t0 = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = FakeClock(t0)

    private fun engine() = TagEngine(clock).also {
        it.configure(TagConfig(path = "Line/Count", type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
        it.configure(TagConfig(path = "Line/Temp", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0.0, valueSet = true))
    }

    private fun router() = HistoryRouter(MemoryHistorySink(), ForwardBuffer(tmp.resolve("db/history-forward.sqlite")), clock, retryMillis = 2_000, batchSize = 7)

    @Test
    fun aProviderCutMidStreamLosesNoSampleAndDuplicatesNone() {
        val pg = FakeRemoteProvider("pg")
        var engine = engine()
        var value = 0
        fun step(c: HistoryCollector, r: HistoryRouter) {
            clock.advance(Duration.ofMillis(100))
            value++
            engine.write("[default]Line/Count", value)
            c.tick()
            c.flush()
            r.forward()
        }

        val count = HistorySettings("Line/Count", storageProvider = "pg")
        val temp = HistorySettings("Line/Temp", sampleMode = HistorySampleMode.PERIODIC, sampleRateMillis = 500, storageProvider = "pg")
        router().use { r ->
            r.register(pg)
            HistoryCollector(engine, r, clock).use { c ->
                for (s in listOf(count, temp)) { r.route(s.tagPath, "pg"); c.configure(s) }
                repeat(40) { step(c, r) }
                // Cut: the batch in flight commits but its acknowledgement is lost, then every call fails.
                pg.commitThenFail = 1
                repeat(5) { step(c, r) }
                pg.down = true
                repeat(60) { step(c, r) }
                assertEquals(true, r.buffer.pendingCounts().getValue("pg") > 60, "samples accumulate during the outage")
            }
        }
        // Gateway restart during the outage: the buffer is on disk.
        engine = engine().also { it.write("[default]Line/Count", value) }
        router().use { r ->
            r.register(pg)
            HistoryCollector(engine, r, clock).use { c ->
                for (s in listOf(count, temp)) { r.route(s.tagPath, "pg"); c.configure(s) }
                repeat(30) { step(c, r) }
                pg.down = false // restored
                repeat(30) { step(c, r) }
                clock.advance(Duration.ofSeconds(3))
                c.flush()
                r.forward()
                assertEquals(emptyMap<String, Long>(), r.buffer.pendingCounts())
                assertEquals(0, r.buffer.quarantined().size)
            }
        }

        val counts = pg.rows().filter { it.tagPath == "[default]Line/Count" }.map { it.value as Int }
        assertEquals((0..value).toList(), counts, "every on-change sample exactly once, in order")
        val temps = pg.rows().filter { it.tagPath == "[default]Line/Temp" }.map { it.timestampMillis }
        assertEquals(temps.distinct(), temps, "no periodic sample twice")
        assertEquals(temps.sorted(), temps, "periodic samples in order")
        // One periodic row per 500 ms over 16.5 s of steps, across the restart (the first tick after it is due at once).
        assertEquals(true, temps.size >= 32, "periodic rows: ${temps.size}")
    }
}
