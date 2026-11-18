package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.Quality
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.sin

/** CSV, simulator and splitter history providers (FEATURES HIS-006, HIS-009). */
class HistoryProvidersTest {
    @TempDir lateinit var tmp: Path

    private val clock = FakeClock(Instant.parse("2026-11-09T12:00:00Z"))
    private val t0 = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()

    private fun sample(path: String, t: Long, v: Any?) = HistorySample(path, t, v, Quality.GOOD)

    // ---------------------------------------------------------------- CSV

    @Test
    fun csvProviderReadsOneSeriesPerColumnWithTypesAndGaps() {
        val file = tmp.resolve("line1.csv")
        file.writeText(
            "time,Line1/Speed,[plant]Line1/State,Line1/Note\r\n" +
                "2026-11-02T00:00:02Z,2.5,true,\"said \"\"hi\"\"\"\r\n" +
                "2026-11-02 00:00:00,1,false,\n" +
                "1793577601000,,true,plain\n" +
                "\n"
        )
        val p = CsvHistoryProvider("csv", file)
        assertEquals(listOf("[default]Line1/Speed", "[plant]Line1/State", "[default]Line1/Note"), p.tagPaths())
        val speed = p.query(HistoryQuery("line1/speed", 0, Long.MAX_VALUE))
        assertEquals(listOf(t0, t0 + 2000), speed.map { it.timestampMillis }, "sorted by time; an empty cell is no sample")
        assertEquals(listOf(1, 2.5), speed.map { it.value })
        assertEquals("[default]Line1/Speed", speed.first().tagPath)
        assertTrue(speed.all { it.quality == Quality.GOOD })
        assertEquals(listOf(false, true, true), p.query(HistoryQuery("[plant]Line1/State", 0, Long.MAX_VALUE)).map { it.value })
        assertEquals(listOf("plain", "said \"hi\""), p.query(HistoryQuery("[default]Line1/Note", 0, Long.MAX_VALUE)).map { it.value })
        assertEquals(listOf(true), p.query(HistoryQuery("[plant]Line1/State", t0 + 1, t0 + 1999)).map { it.value })
        assertEquals(1, p.latestBefore("[default]Line1/Speed", t0 + 2000)?.value)
        assertNull(p.latestBefore("[default]Line1/Speed", t0))
        assertEquals(listOf(1), p.query(HistoryQuery("[default]Line1/Speed", 0, Long.MAX_VALUE, limit = 1)).map { it.value })
        val max = HistoryAggregator.aggregate(p, "[default]Line1/Speed", t0, t0 + 2999, 3000, HistoryAggregate.MAXIMUM)
        assertEquals(2.5, max.single().value)
        assertEquals(0, p.query(HistoryQuery("[default]Missing", 0, Long.MAX_VALUE)).size)
    }

    @Test
    fun relativeTimestampsCountMillisecondsFromTheFirstRow() {
        val file = tmp.resolve("rel.csv")
        file.writeText("t,A\n2026-11-02T00:00:00Z,1\n500,2\n1500,3\n")
        val p = CsvHistoryProvider("csv", file, relativeTimestamps = true)
        assertEquals(listOf(t0, t0 + 500, t0 + 1500), p.query(HistoryQuery("A", 0, Long.MAX_VALUE)).map { it.timestampMillis })
    }

    @Test
    fun aChangedFileIsReadAgainAndABadFileFailsTheReadWithItsLine() {
        val file = tmp.resolve("f.csv")
        file.writeText("t,A\n1000,1\n")
        val p = CsvHistoryProvider("csv", file)
        assertEquals(listOf(1), p.query(HistoryQuery("A", 0, Long.MAX_VALUE)).map { it.value })
        file.writeText("t,A\n1000,1\n2000,2\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000))
        assertEquals(listOf(1, 2), p.query(HistoryQuery("A", 0, Long.MAX_VALUE)).map { it.value })

        file.writeText("t,A\n1000,1\nnot a time,2\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10_000))
        val bad = assertThrows(HistoryReadException::class.java) { p.query(HistoryQuery("A", 0, Long.MAX_VALUE)) }
        assertTrue(bad.message!!.contains("line 3"), bad.message)

        file.writeText("t,A,a\n1000,1,2\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 15_000))
        assertTrue(assertThrows(HistoryReadException::class.java) { p.query(HistoryQuery("A", 0, 1)) }.message!!.contains("more than one column"))

        val missing = CsvHistoryProvider("csv", tmp.resolve("nope.csv"))
        assertThrows(HistoryReadException::class.java) { missing.query(HistoryQuery("A", 0, 1)) }

        val big = tmp.resolve("big.csv").also { it.writeText("t,A\n" + "1000,1\n".repeat(200)) }
        assertThrows(HistoryReadException::class.java) { CsvHistoryProvider("csv", big, maxBytes = 100).query(HistoryQuery("A", 0, 1)) }
    }

    @Test
    fun readOnlyProvidersRefuseWritesPermanentlySoRecordsAreQuarantined() {
        val file = tmp.resolve("f.csv").also { it.writeText("t,A\n1000,1\n") }
        ForwardBuffer(tmp.resolve("fwd.sqlite")).use { buffer ->
            val router = HistoryRouter(MemoryHistorySink(), buffer, clock)
            router.register(CsvHistoryProvider("csv", file))
            router.register(SimulatorHistoryProvider("sim"))
            router.route("[default]X", "csv")
            router.route("[default]Y", "sim")
            router.appendAll(listOf(sample("[default]X", 1, 1.0), sample("[default]Y", 1, 2.0)))
            router.forward()
            assertEquals(emptyMap<String, Long>(), buffer.pendingCounts())
            assertEquals(mapOf("csv" to 1L, "sim" to 1L), buffer.quarantineCounts())
            assertTrue(buffer.quarantined("csv").single().error.contains("read-only"))
        }
    }

    // ---------------------------------------------------------- simulator

    @Test
    fun simulatorGeneratesRepeatablePointsAlignedToTheResolution() {
        val sim = SimulatorHistoryProvider("sim")
        val sine = sim.query(HistoryQuery("[default]Sim/sine_10s_20_500ms", 1_250, 4_000))
        assertEquals((3..8).map { it * 500L }, sine.map { it.timestampMillis }, "points on whole multiples of the resolution")
        for (s in sine) assertTrue(abs((s.value as Double) - 20 * sin(2 * Math.PI * (s.timestampMillis % 10_000) / 10_000.0)) < 1e-9)
        assertEquals(sine, sim.query(HistoryQuery("[default]Sim/sine_10s_20_500ms", 1_250, 4_000)), "the same request gives the same points")

        val ramp = sim.query(HistoryQuery("ramp_60s_100_1s", 0, 61_000)).map { it.value as Double }
        assertEquals(0.0, ramp[0])
        assertEquals(50.0, ramp[30], 1e-9)
        assertEquals(0.0, ramp[60], "the ramp starts again each period")
        val square = sim.query(HistoryQuery("square_1h_10_10s", 0, 3_600_000)).map { it.value as Double }
        assertEquals(0.0, square[0])
        assertEquals(10.0, square[180])
        assertEquals(1.0, sim.query(HistoryQuery("cos_1m_1_1000ms", 60_000, 60_000)).single().value as Double, 1e-12)

        val day = 86_400_000L
        val realistic = sim.query(HistoryQuery("realistic_1d", 0, day - 1, HistoryQuery.MAX_LIMIT))
        assertEquals(SimulatorHistoryProvider.REALISTIC_POINTS, realistic.size, "400 points per period")
        assertEquals(realistic.map { it.value }, sim.query(HistoryQuery("realistic_1d", 7 * day, 8 * day - 1, HistoryQuery.MAX_LIMIT)).map { it.value }, "it repeats every period")
        assertTrue(realistic.all { (it.value as Double) in 0.0..100.0 })

        assertEquals(0, sim.query(HistoryQuery("[default]Plant/Speed", 0, 10_000)).size, "a path outside the pattern has no history")
        assertEquals(0, sim.query(HistoryQuery("sine_0s_1_1s", 0, 10_000)).size)
        assertEquals(3_500L, sim.latestBefore("sine_10s_20_500ms", 4_000)?.timestampMillis)
        val avg = HistoryAggregator.aggregate(sim, "sine_10s_20_500ms", 0, 9_999, 10_000, HistoryAggregate.AVERAGE)
        assertEquals(0.0, avg.single().value as Double, 1e-9, "a whole sine period averages to zero")
    }

    @Test
    fun simulatorRefusesAReadThatWouldGenerateTooManyPoints() {
        val sim = SimulatorHistoryProvider("sim", maxPoints = 1_000)
        assertEquals(1_000, sim.query(HistoryQuery("sine_1s_1_1ms", 0, 10_000_000, limit = 1_000)).size, "a raw query stops at its limit")
        assertThrows(IllegalArgumentException::class.java) { sim.scan("sine_1s_1_1ms", 0, 10_000_000) {} }
    }

    // ----------------------------------------------------------- splitter

    private fun splitterRouter(buffer: ForwardBuffer, local: HistorySink = MemoryHistorySink()): Triple<HistoryRouter, FakeRemoteProvider, FakeRemoteProvider> {
        val router = HistoryRouter(local, buffer, clock)
        val a = FakeRemoteProvider("a")
        val b = FakeRemoteProvider("b")
        router.register(a)
        router.register(b)
        router.register(SplitterHistoryProvider("both", "a", "b", clock = clock) { router.reader(it) })
        router.route("[default]T", "both")
        return Triple(router, a, b)
    }

    @Test
    fun splitterStoresEverySampleInBothProvidersExactlyOnceEvenWhileOneIsDown() {
        ForwardBuffer(tmp.resolve("fwd.sqlite")).use { buffer ->
            val (router, a, b) = splitterRouter(buffer)
            b.down = true
            router.appendAll((1..5).map { sample("[default]T", it * 1000L, it) })
            router.forward()
            assertEquals((1..5).toList(), a.rows().map { it.value }, "the first provider is not held back by the second")
            assertEquals(0, b.rows().size)
            assertEquals(mapOf("b" to 5L), buffer.pendingCounts(), "the second keeps its own queue")

            router.appendAll((6..8).map { sample("[default]T", it * 1000L, it) })
            b.down = false
            a.commitThenFail = 1
            clock.advance(Duration.ofSeconds(10))
            router.forward()
            clock.advance(Duration.ofSeconds(10))
            router.forward()
            assertEquals((1..8).toList(), a.rows().map { it.value }, "a resend after a lost acknowledgement lands once")
            assertEquals((1..8).toList(), b.rows().map { it.value })
            assertEquals(emptyMap<String, Long>(), buffer.pendingCounts())
        }
    }

    @Test
    fun splitterReadsFromTheFirstFallsBackToTheSecondAndHonoursTheQueryLimit() {
        ForwardBuffer(tmp.resolve("fwd.sqlite")).use { buffer ->
            val (router, a, b) = splitterRouter(buffer)
            val now = clock.millis()
            router.appendAll((0 until 10).map { sample("[default]T", now - (10 - it) * 3_600_000L, it) })
            router.forward()
            val splitter = router.reader("both")!!
            assertEquals((0 until 10).toList(), splitter.query(HistoryQuery("[default]T", 0, now)).map { it.value })
            a.down = true
            assertEquals((0 until 10).toList(), splitter.query(HistoryQuery("[default]T", 0, now)).map { it.value }, "the second answers when the first cannot")
            assertEquals(9, splitter.latestBefore("[default]T", now)?.value)
            a.down = false

            // Only the last 4 hours from the first: rows the first lacks show the split.
            val limited = SplitterHistoryProvider("limited", "a", "b", 4 * 3_600_000L, clock) { router.reader(it) }
            b.down = true
            assertThrows(HistoryReadException::class.java) { limited.query(HistoryQuery("[default]T", 0, now)) }
            b.down = false
            val recentOnly = limited.query(HistoryQuery("[default]T", now - 3 * 3_600_000L, now))
            assertEquals(listOf(7, 8, 9), recentOnly.map { it.value })
            a.down = true
            assertEquals((0 until 10).toList(), limited.query(HistoryQuery("[default]T", 0, now)).map { it.value })
            assertEquals(5, limited.latestBefore("[default]T", now - 4 * 3_600_000L)?.value, "older than the limit reads the second")
            a.down = false
            assertEquals(9, limited.latestBefore("[default]T", now)?.value)
        }
    }

    @Test
    fun splitterCanWriteToTheLocalStoreAndRefusesASplitterTarget() {
        ForwardBuffer(tmp.resolve("fwd.sqlite")).use { buffer ->
            val local = MemoryHistorySink()
            val router = HistoryRouter(local, buffer, clock)
            val a = FakeRemoteProvider("a")
            router.register(a)
            router.register(SplitterHistoryProvider("mirror", "local", "a", clock = clock) { router.reader(it) })
            router.route("[default]T", "mirror")
            router.appendAll(listOf(sample("[default]T", 1000, 1.0)))
            router.forward()
            assertEquals(listOf(1.0), local.query(HistoryQuery("[default]T", 0, Long.MAX_VALUE)).map { it.value })
            assertEquals(listOf(1.0), a.rows().map { it.value })
            assertEquals(listOf(1.0), router.reader("mirror")!!.query(HistoryQuery("[default]T", 0, Long.MAX_VALUE)).map { it.value })

            router.register(SplitterHistoryProvider("outer", "mirror", "a", clock = clock) { router.reader(it) })
            router.route("[default]U", "outer")
            router.appendAll(listOf(sample("[default]U", 1000, 2.0)))
            router.forward()
            assertEquals(mapOf("outer" to 1L), buffer.pendingCounts(), "a splitter does not fan out into another splitter")
            assertTrue((router.status().single { it["name"] == "outer" }["lastError"] as String).contains("cannot write to splitter"))
        }
        assertThrows(IllegalArgumentException::class.java) { SplitterHistoryProvider("s", "a", "a") { null } }
        assertThrows(IllegalArgumentException::class.java) { SplitterHistoryProvider("s", "s", "a") { null } }
    }
}
