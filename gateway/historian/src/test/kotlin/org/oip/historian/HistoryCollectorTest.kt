package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.DataType
import org.oip.tags.Deadband
import org.oip.tags.Quality
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * M3.1 first slice: sample modes, deadband, and the non-blocking listener path. Everything is driven by a
 * fake clock and explicit [HistoryCollector.tick] / [HistoryCollector.flush] calls; no threads, no sleeps.
 * Docs: -
 * and -
 */
class HistoryCollectorTest {
    @TempDir lateinit var tmp: Path

    private val t0: Instant = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = FakeClock(t0)
    private val engine = TagEngine(clock)

    private fun memoryTag(path: String, value: Any?, type: DataType = DataType.DOUBLE) =
        engine.configure(TagConfig(path = path, type = TagType.MEMORY, dataType = type, value = value, valueSet = true))

    private fun rows(sink: HistorySink, path: String) = sink.query(HistoryQuery(path, Long.MIN_VALUE, Long.MAX_VALUE))

    private fun write(path: String, value: Any?, after: Duration = Duration.ofSeconds(1)) {
        clock.advance(after)
        engine.write(path, value)
    }

    @Test
    fun onChangeWithDeadbandOffStoresEveryChangeAtItsValueTimestamp() {
        memoryTag("Plant/Speed", 1.0)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("[default]Plant/Speed"))
            write("[default]Plant/Speed", 2.0)
            write("[default]Plant/Speed", 2.0) // identical value: the engine publishes no change
            write("[default]Plant/Speed", 3.0)
            c.flush()
            val r = rows(sink, "[default]Plant/Speed")
            assertEquals(listOf(1.0, 2.0, 3.0), r.map { it.value })
            assertEquals(listOf(1L, 3L), r.drop(1).map { (it.timestampMillis - t0.toEpochMilli()) / 1000 })
            assertTrue(r.all { it.quality == Quality.GOOD })
        }
    }

    @Test
    fun onChangeAbsoluteDeadbandComparesAgainstTheLastStoredValue() {
        memoryTag("Plant/Level", 10.0)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Level", deadband = Deadband.absolute(0.5)))
            write("[default]Plant/Level", 10.4) // 0.4 from 10.0: suppressed
            write("[default]Plant/Level", 10.6) // 0.6 from 10.0 (last stored): stored
            write("[default]Plant/Level", 10.9) // 0.3 from 10.6: suppressed
            write("[default]Plant/Level", 10.0) // 0.6 from 10.6: stored
            c.flush()
            assertEquals(listOf(10.0, 10.6, 10.0), rows(sink, "[default]Plant/Level").map { it.value })
        }
    }

    @Test
    fun onChangePercentDeadband() {
        memoryTag("Plant/Flow", 100.0)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Flow", deadband = Deadband.percent(5.0)))
            write("[default]Plant/Flow", 104.0) // 4% of 100: suppressed
            write("[default]Plant/Flow", 106.0) // 6%: stored
            c.flush()
            assertEquals(listOf(100.0, 106.0), rows(sink, "[default]Plant/Flow").map { it.value })
        }
    }

    @Test
    fun aQualityChangeIsStoredEvenInsideTheDeadband() {
        engine.configure(TagConfig(path = "Plant/Remote", type = TagType.OPC, dataType = DataType.DOUBLE))
        engine.updateRaw("[default]Plant/Remote", 5.0, Quality.GOOD)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Remote", deadband = Deadband.absolute(10.0)))
            clock.advance(Duration.ofSeconds(1))
            engine.updateRaw("[default]Plant/Remote", 5.0, Quality.BAD_NOT_CONNECTED)
            clock.advance(Duration.ofSeconds(1))
            engine.updateRaw("[default]Plant/Remote", 6.0, Quality.BAD_NOT_CONNECTED) // same quality, inside deadband
            c.flush()
            val r = rows(sink, "[default]Plant/Remote")
            assertEquals(listOf(Quality.GOOD.code, Quality.BAD_NOT_CONNECTED.code), r.map { it.quality.code })
        }
    }

    @Test
    fun periodicWithDeadbandOffStoresOneRowPerPeriodStampedAtTheSampleTime() {
        memoryTag("Plant/Temp", 20.0)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Temp", HistorySampleMode.PERIODIC, sampleRateMillis = 1000))
            c.tick(t0.plusMillis(100)) // first tick samples
            c.tick(t0.plusMillis(600)) // not due
            c.tick(t0.plusMillis(1100)) // due
            c.tick(t0.plusMillis(1500)) // not due
            c.tick(t0.plusMillis(2100)) // due
            c.flush()
            val r = rows(sink, "[default]Plant/Temp")
            assertEquals(listOf(100L, 1100L, 2100L), r.map { it.timestampMillis - t0.toEpochMilli() })
            assertEquals(listOf(20.0, 20.0, 20.0), r.map { it.value }) // unchanged value is still stored
        }
    }

    @Test
    fun periodicDoesNotStoreBetweenTicksAndSkipsMissedPeriodsAfterAStall() {
        memoryTag("Plant/Temp", 20.0)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Temp", HistorySampleMode.PERIODIC, sampleRateMillis = 1000))
            write("[default]Plant/Temp", 21.0) // a change alone stores nothing in periodic mode
            c.flush()
            assertTrue(rows(sink, "[default]Plant/Temp").isEmpty())
            c.tick(t0)
            c.tick(t0.plusMillis(10_000)) // 9 missed periods: one row, not a burst
            c.tick(t0.plusMillis(10_500))
            c.tick(t0.plusMillis(11_000))
            c.flush()
            assertEquals(listOf(0L, 10_000L, 11_000L), rows(sink, "[default]Plant/Temp").map { it.timestampMillis - t0.toEpochMilli() })
        }
    }

    @Test
    fun periodicWithDeadbandStoresOnlyWhenTheValueMovedPastIt() {
        memoryTag("Plant/Pressure", 50.0)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Pressure", HistorySampleMode.PERIODIC, Deadband.absolute(5.0), sampleRateMillis = 1000))
            c.tick(t0) // first sample: stored
            c.tick(t0.plusMillis(1000)) // unchanged: not stored
            engine.write("[default]Plant/Pressure", 53.0)
            c.tick(t0.plusMillis(2000)) // 3 < 5: not stored
            engine.write("[default]Plant/Pressure", 56.0)
            c.tick(t0.plusMillis(3000)) // 6 >= 5: stored, stamped at the sample time
            c.flush()
            val r = rows(sink, "[default]Plant/Pressure")
            assertEquals(listOf(50.0, 56.0), r.map { it.value })
            assertEquals(listOf(0L, 3000L), r.map { it.timestampMillis - t0.toEpochMilli() })
        }
    }

    @Test
    fun maxTimeBetweenSamplesForcesARowInsideTheDeadband() {
        memoryTag("Plant/Pressure", 50.0)
        memoryTag("Plant/Level", 10.0)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Pressure", HistorySampleMode.PERIODIC, Deadband.absolute(5.0),
                sampleRateMillis = 1000, maxTimeBetweenSamplesMillis = 3000))
            c.configure(HistorySettings("Plant/Level", HistorySampleMode.ON_CHANGE, Deadband.absolute(5.0),
                maxTimeBetweenSamplesMillis = 3000))
            for (s in 0..6) c.tick(t0.plusMillis(s * 1000L))
            c.flush()
            assertEquals(listOf(0L, 3000L, 6000L), rows(sink, "[default]Plant/Pressure").map { it.timestampMillis - t0.toEpochMilli() })
            // On-change seed is stamped with the value's timestamp (t0); the timer forces rows at 3 s and 6 s.
            assertEquals(listOf(0L, 3000L, 6000L), rows(sink, "[default]Plant/Level").map { it.timestampMillis - t0.toEpochMilli() })
        }
    }

    @Test
    fun theTagListenerOnlyEnqueuesAndTheSinkIsWrittenInBatchesByFlush() {
        memoryTag("Plant/Speed", 0.0)
        val sink = CountingSink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Speed"))
            for (i in 1..50) write("[default]Plant/Speed", i.toDouble(), Duration.ofMillis(10))
            assertEquals(0, sink.appendCalls, "no sink I/O on the tag engine thread")
            assertEquals(1, sink.latestAllCalls, "deadband state is in memory, read once at startup")
            assertEquals(51, c.pending)
            assertEquals(51, c.flush())
            assertEquals(1, sink.appendCalls, "one batch")
            assertEquals(51, rows(sink, "[default]Plant/Speed").size)
        }
    }

    @Test
    fun aFullQueueDropsAndCountsInsteadOfBlocking() {
        memoryTag("Plant/Speed", 0.0)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock, queueCapacity = 3).use { c ->
            c.configure(HistorySettings("Plant/Speed"))
            for (i in 1..5) write("[default]Plant/Speed", i.toDouble())
            assertEquals(3, c.pending)
            assertEquals(3L, c.dropped)
        }
    }

    @Test
    fun aSinkFailureHoldsTheBatchAndTheNextFlushWritesItFirstInOrder() {
        memoryTag("Plant/Speed", 1.0)
        val sink = FailOnceSink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Speed"))
            write("[default]Plant/Speed", 2.0)
            write("[default]Plant/Speed", 3.0)
            assertThrows(java.sql.SQLException::class.java) { c.flush() } // e.g. SQLITE_BUSY
            assertEquals(3, c.pending, "the failed batch is held, not dropped")
            assertEquals(0L, c.dropped)
            write("[default]Plant/Speed", 4.0)
            assertEquals(4, c.flush())
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), rows(sink.inner, "[default]Plant/Speed").map { it.value })
            assertEquals(0, c.pending)
            assertEquals(4L, c.written)
        }
    }

    @Test
    fun aDroppedSampleDoesNotMoveTheDeadbandBaseline() {
        memoryTag("Plant/Level", 10.0)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock, queueCapacity = 1).use { c ->
            c.configure(HistorySettings("Plant/Level", deadband = Deadband.absolute(1.0)))
            write("[default]Plant/Level", 12.0) // past the deadband, but the queue is full: dropped
            assertEquals(1L, c.dropped)
            c.flush()
            // 11.9 is 1.9 from the last *queued* 10.0, so it is stored. Had the dropped 12.0 become the
            // baseline, 11.9 would sit inside the deadband and this change would be lost too.
            write("[default]Plant/Level", 11.9)
            c.flush()
            assertEquals(listOf(10.0, 11.9), rows(sink, "[default]Plant/Level").map { it.value })
        }
    }

    @Test
    fun stopEndsCollection() {
        memoryTag("Plant/Count", 0, DataType.INT32)
        val sink = MemoryHistorySink()
        HistoryCollector(engine, sink, clock).use { c ->
            c.configure(HistorySettings("Plant/Count"))
            write("[default]Plant/Count", 1)
            c.stop("Plant/Count")
            assertFalse(c.isCollecting("[default]Plant/Count"))
            write("[default]Plant/Count", 2)
            c.flush()
            assertEquals(listOf(0, 1), rows(sink, "[default]Plant/Count").map { it.value })
        }
    }

    @Test
    fun historyAndDeadbandStateSurviveARestartOnSqlite() {
        val db = tmp.resolve("db/history.sqlite")
        memoryTag("Plant/Level", 10.0)
        SqliteHistoryStore(db).use { store ->
            HistoryCollector(engine, store, clock).use { c ->
                c.configure(HistorySettings("Plant/Level", deadband = Deadband.absolute(1.0)))
                write("[default]Plant/Level", 12.0)
            } // close() flushes
        }
        // New process: new engine whose tag starts at 12.4, new store over the same file.
        val engine2 = TagEngine(clock)
        engine2.configure(TagConfig(path = "Plant/Level", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 12.4, valueSet = true))
        SqliteHistoryStore(db).use { store ->
            HistoryCollector(engine2, store, clock).use { c ->
                assertEquals(listOf(10.0, 12.0), rows(store, "[default]Plant/Level").map { it.value })
                c.configure(HistorySettings("Plant/Level", deadband = Deadband.absolute(1.0)))
                c.flush()
                // 12.4 is within 1.0 of the last stored 12.0 from before the restart: nothing new.
                assertEquals(2, rows(store, "[default]Plant/Level").size)
                clock.advance(Duration.ofSeconds(1))
                engine2.write("[default]Plant/Level", 13.5)
                c.flush()
                assertEquals(listOf(10.0, 12.0, 13.5), rows(store, "[default]Plant/Level").map { it.value })
            }
        }
    }

    /** Fails the first append (like SQLITE_BUSY), then writes normally. */
    private class FailOnceSink : HistorySink {
        val inner = MemoryHistorySink()
        private var failed = false
        override fun appendAll(samples: List<HistorySample>) {
            if (!failed) { failed = true; throw java.sql.SQLException("database is locked", "SQLITE_BUSY") }
            inner.appendAll(samples)
        }
        override fun query(query: HistoryQuery) = inner.query(query)
        override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) = inner.scan(tagPath, fromMillis, toMillis, each)
        override fun latestBefore(tagPath: String, beforeMillis: Long) = inner.latestBefore(tagPath, beforeMillis)
        override fun latestAll(): Map<String, HistorySample> = inner.latestAll()
    }

    /** Memory sink that counts calls, to show where I/O happens. */
    private class CountingSink : HistorySink {
        val inner = MemoryHistorySink()
        var appendCalls = 0
        var latestAllCalls = 0
        override fun appendAll(samples: List<HistorySample>) { appendCalls++; inner.appendAll(samples) }
        override fun query(query: HistoryQuery) = inner.query(query)
        override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) = inner.scan(tagPath, fromMillis, toMillis, each)
        override fun latestBefore(tagPath: String, beforeMillis: Long) = inner.latestBefore(tagPath, beforeMillis)
        override fun latestAll(): Map<String, HistorySample> { latestAllCalls++; return inner.latestAll() }
    }
}

/** In-memory [HistorySink] for tests. */
class MemoryHistorySink : HistorySink {
    private val rows = ArrayList<HistorySample>()

    @Synchronized override fun appendAll(samples: List<HistorySample>) { rows += samples }

    @Synchronized override fun query(query: HistoryQuery): List<HistorySample> = rows
        .filter { it.tagPath.equals(query.tagPath, true) && it.timestampMillis in query.fromMillis..query.toMillis }
        .sortedBy { it.timestampMillis }
        .take(query.limit)

    @Synchronized override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) =
        rows.filter { it.tagPath.equals(tagPath, true) && it.timestampMillis in fromMillis..toMillis }
            .sortedBy { it.timestampMillis }.forEach(each)

    @Synchronized override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? =
        rows.filter { it.tagPath.equals(tagPath, true) && it.timestampMillis < beforeMillis }.maxByOrNull { it.timestampMillis }

    @Synchronized override fun latestAll(): Map<String, HistorySample> = rows.associateBy { it.tagPath }
}

class FakeClock(private var now: Instant) : Clock() {
    @Synchronized fun advance(d: Duration) { now = now.plus(d) }
    @Synchronized override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
