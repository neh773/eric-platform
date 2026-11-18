package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.DataType
import org.oip.tags.Quality
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Store-and-forward buffer sizes and flush rates, per provider and gateway-wide (FEATURES HIS-024). */
class StoreAndForwardSettingsTest {
    @TempDir lateinit var tmp: Path

    private val clock = FakeClock(Instant.parse("2026-06-01T00:00:00Z"))

    private fun sample(path: String, t: Long, v: Any?) = HistorySample(path, t, v, Quality.GOOD)

    private fun router(buffer: ForwardBuffer): Pair<HistoryRouter, FakeRemoteProvider> {
        val r = HistoryRouter(MemoryHistorySink(), buffer, clock)
        val p = FakeRemoteProvider("pg")
        r.register(p)
        r.route("[default]T", "pg")
        return r to p
    }

    @Test
    fun aFullDiskCacheRefusesNewSamplesByDefaultAndCountsThem() {
        ForwardBuffer(tmp.resolve("fwd.sqlite")).use { buffer ->
            val (r, p) = router(buffer)
            p.down = true
            r.setPolicy("pg", ForwardPolicy(maxPending = 5))
            r.appendAll((1..3).map { sample("[default]T", it.toLong(), it) })
            r.appendAll((4..8).map { sample("[default]T", it.toLong(), it) })
            assertEquals(5L, buffer.pendingFor("pg"))
            assertEquals(mapOf("pg" to 5L), buffer.pendingCounts())
            val st = r.status().single { it["name"] == "pg" }
            assertEquals(3L, st["rejected"])
            assertEquals(5L, (st["policy"] as Map<*, *>)["maxPending"])
            assertTrue((st["lastError"] as String).contains("full"))
            p.down = false
            r.forward()
            assertEquals((1..5).toList(), p.rows().map { it.value }, "the oldest five were kept")
            r.appendAll(listOf(sample("[default]T", 9, 9)))
            r.forward()
            assertEquals(9, p.rows().last().value, "room again after forwarding")
        }
    }

    @Test
    fun evictOldestDataKeepsTheNewestSamples() {
        ForwardBuffer(tmp.resolve("fwd.sqlite")).use { buffer ->
            val (r, p) = router(buffer)
            p.down = true
            r.setPolicy("pg", ForwardPolicy(maxPending = 4, whenFull = WhenFull.EVICT_OLDEST_DATA))
            r.appendAll((1..3).map { sample("[default]T", it.toLong(), it) })
            r.appendAll((4..6).map { sample("[default]T", it.toLong(), it) })
            r.appendAll((7..12).map { sample("[default]T", it.toLong(), it) })
            assertEquals(4L, buffer.pendingFor("pg"))
            assertEquals(8L, r.status().single { it["name"] == "pg" }["evicted"])
            p.down = false
            r.forward()
            assertEquals((9..12).toList(), p.rows().map { it.value })
        }
    }

    @Test
    fun batchSizeAndRetryDelayArePerProvider() {
        ForwardBuffer(tmp.resolve("fwd.sqlite")).use { buffer ->
            val (r, p) = router(buffer)
            r.setPolicy("pg", ForwardPolicy(batchSize = 3, retryMillis = 60_000))
            r.appendAll((1..10).map { sample("[default]T", it.toLong(), it) })
            r.forward()
            assertEquals(4, p.writeCalls, "10 records in batches of 3")
            assertEquals((1..10).toList(), p.rows().map { it.value })

            p.down = true
            r.appendAll(listOf(sample("[default]T", 11, 11)))
            r.forward()
            p.down = false
            clock.advance(Duration.ofSeconds(30))
            r.forward()
            assertEquals(1L, buffer.pendingFor("pg"), "still waiting out the 60 s retry delay")
            clock.advance(Duration.ofSeconds(31))
            r.forward()
            assertEquals(0L, buffer.pendingFor("pg"))
            assertEquals(3, r.policyFor("pg").batchSize)
            assertEquals(HistoryRouter.DEFAULT_BATCH, r.policyFor("other").batchSize, "providers without settings use the defaults")
        }
    }

    @Test
    fun theTimerForwardsEachProviderAtItsOwnRate() {
        ForwardBuffer(tmp.resolve("fwd.sqlite")).use { buffer ->
            val r = HistoryRouter(MemoryHistorySink(), buffer, clock)
            val fast = FakeRemoteProvider("fast").also { r.register(it) }
            val slow = FakeRemoteProvider("slow").also { r.register(it) }
            r.route("[default]F", "fast")
            r.route("[default]S", "slow")
            r.setPolicy("fast", ForwardPolicy(forwardRateMillis = 100))
            r.setPolicy("slow", ForwardPolicy(forwardRateMillis = 10_000))
            fun add(i: Int) = r.appendAll(listOf(sample("[default]F", i.toLong(), i), sample("[default]S", i.toLong(), i)))
            add(1)
            r.forwardDue()
            assertEquals(1, fast.rows().size)
            assertEquals(1, slow.rows().size, "the first pass forwards every provider")
            for (i in 2..6) {
                add(i)
                clock.advance(Duration.ofMillis(200))
                r.forwardDue()
            }
            assertEquals(6, fast.rows().size)
            assertEquals(1, slow.rows().size, "the slow provider is not due yet")
            clock.advance(Duration.ofSeconds(10))
            r.forwardDue()
            assertEquals(6, slow.rows().size)
            add(7)
            r.forward()
            assertEquals(7, slow.rows().size, "a direct forward ignores the rate")
        }
    }

    @Test
    fun pendingCountsStayRightThroughEveryBufferChange() {
        ForwardBuffer(tmp.resolve("fwd.sqlite")).use { buffer ->
            val (r, p) = router(buffer)
            fun check() = assertEquals(buffer.pendingCounts()["pg"] ?: 0L, buffer.pendingFor("pg"))
            r.appendAll((1..6).map { sample("[default]T", it.toLong(), it) })
            check()
            p.reject = { it.value == 3 }
            r.forward()
            check()
            assertEquals(1L, buffer.quarantineCounts()["pg"])
            p.reject = { false }
            p.down = true
            buffer.retryQuarantined(null, "pg")
            check()
            buffer.enqueueIdentified(listOf(DiskCacheRecord("pg", sample("[default]T", 50, 50), "other-source", 999_999)))
            check()
            buffer.deletePendingThrough("pg", 999_999)
            check()
            assertEquals(0L, buffer.pendingFor("pg"))
        }
    }

    @Test
    fun theCollectorBufferCanBeResizedAtRuntime() {
        val engine = TagEngine(clock)
        engine.configure(TagConfig(path = "A", type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
        val sink = MemoryHistorySink()
        val collector = HistoryCollector(engine, sink, clock, queueCapacity = 100)
        collector.configure(HistorySettings("A"))
        collector.resize(queueCapacity = 3, batchSize = 2)
        for (v in 1..5) {
            clock.advance(Duration.ofMillis(1))
            engine.write("[default]A", v)
        }
        assertEquals(3, collector.pending, "the first sample plus two changes fit")
        assertEquals(3L, collector.dropped)
        assertEquals(3, collector.flush())
        assertEquals(3, collector.stats()["queueCapacity"])
        assertEquals(2, collector.stats()["batchSize"])
        assertThrows(IllegalArgumentException::class.java) { collector.resize(0) }
        collector.close()
    }

    @Test
    fun theWriterFlushesOnTheTimeOrDataThresholdAndRunsMaintenance() {
        val engine = TagEngine(clock)
        engine.configure(TagConfig(path = "A", type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
        val sink = MemoryHistorySink()
        val collector = HistoryCollector(engine, sink, clock)
        var nanos = 0L
        var settings = StoreAndForwardSettings(timeThresholdMillis = 1_000, dataThreshold = 3)
        var maintained = 0
        val runner = HistoryRunner(collector, settings = { settings }, maintenance = { maintained++ }, maintenanceMillis = 5_000, nanoTime = { nanos })
        collector.configure(HistorySettings("A"))
        runner.writerPass()
        assertEquals(0, runner.flushCount, "one sample waiting, neither threshold reached")
        nanos += TimeUnit.MILLISECONDS.toNanos(999)
        runner.writerPass()
        assertEquals(1, collector.pending)
        nanos += TimeUnit.MILLISECONDS.toNanos(1)
        runner.writerPass()
        assertEquals(0, collector.pending, "time threshold")
        for (v in 1..3) {
            clock.advance(Duration.ofMillis(1))
            engine.write("[default]A", v)
        }
        nanos += TimeUnit.MILLISECONDS.toNanos(10)
        runner.writerPass()
        assertEquals(0, collector.pending, "data threshold before the time threshold")
        assertEquals(2, runner.flushCount)
        assertEquals(0, maintained)
        nanos += TimeUnit.SECONDS.toNanos(5)
        runner.writerPass()
        assertEquals(1, maintained)
        settings = settings.copy(memoryBufferSize = 7, batchSize = 4)
        runner.writerPass()
        assertEquals(7, collector.queueCapacity, "engine settings apply on the next check")
        assertEquals(4, collector.batchSize)
        assertEquals(4, sink.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).size)
        assertThrows(IllegalArgumentException::class.java) { StoreAndForwardSettings(timeThresholdMillis = 1) }
        assertThrows(IllegalArgumentException::class.java) { ForwardPolicy(batchSize = 0) }
        runner.close()
        collector.close()
    }
}
