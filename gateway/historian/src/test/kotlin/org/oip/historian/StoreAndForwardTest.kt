package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.Quality
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Store-and-forward and quarantine rules ([HistoryRouter] KDoc), against an in-process provider with injected
 * faults. Time is a fake clock and [HistoryRouter.forward] is called explicitly; no threads or sleeps.
 * Docs: - (Store and Forward,
 * disk store local cache and quarantine; a rejected batch is retried record by record before anything is quarantined).
 */
class StoreAndForwardTest {
    @TempDir lateinit var tmp: Path

    private val t0 = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = FakeClock(t0)
    private val tag = "[default]Plant/Flow"

    private fun sample(i: Int, path: String = tag) = HistorySample(path, t0.toEpochMilli() + i * 1000L, i.toDouble(), Quality.GOOD)

    private fun router(local: HistorySink = MemoryHistorySink(), retryMillis: Long = 5_000, batch: Int = 4) =
        HistoryRouter(local, ForwardBuffer(tmp.resolve("db/history-forward.sqlite")), clock, retryMillis, batch)

    private fun values(p: FakeRemoteProvider) = p.rows().map { (it.value as Double).toInt() }

    @Test
    fun localTagsAreWrittenDirectlyAndRemoteTagsAreBufferedUntilForwarded() {
        val local = MemoryHistorySink()
        router(local).use { r ->
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            r.route(tag, "pg")
            r.appendAll(listOf(sample(1), sample(2), sample(3, "[default]Plant/Local")))
            assertEquals(listOf(3.0), local.query(HistoryQuery("[default]Plant/Local", 0, Long.MAX_VALUE)).map { it.value })
            assertEquals(0, local.query(HistoryQuery(tag, 0, Long.MAX_VALUE)).size)
            assertEquals(mapOf("pg" to 2L), r.buffer.pendingCounts())
            assertEquals(0, pg.rows().size, "nothing reaches the provider until forward runs")
            assertEquals(2, r.forward())
            assertEquals(listOf(1, 2), values(pg))
            assertEquals(emptyMap<String, Long>(), r.buffer.pendingCounts())
        }
    }

    @Test
    fun anUnreachableProviderAccumulatesRecordsAndGetsThemInOrderAfterReconnect() {
        router(retryMillis = 5_000).use { r ->
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            r.route(tag, "pg")
            r.appendAll((1..3).map { sample(it) })
            r.forward()
            pg.down = true
            r.appendAll((4..10).map { sample(it) })
            assertEquals(0, r.forward())
            val st = r.status().single { it["name"] == "pg" }
            assertEquals(false, st["connected"])
            assertEquals(7L, st["pending"])
            // Reconnected, but the retry delay has not passed: nothing is attempted.
            pg.down = false
            val calls = pg.writeCalls
            clock.advance(Duration.ofSeconds(4))
            assertEquals(0, r.forward())
            assertEquals(calls, pg.writeCalls)
            r.appendAll((11..12).map { sample(it) })
            clock.advance(Duration.ofSeconds(1))
            assertEquals(9, r.forward())
            assertEquals((1..12).toList(), values(pg))
            assertEquals(true, r.status().single { it["name"] == "pg" }["connected"])
        }
    }

    @Test
    fun aLostAcknowledgementResendsTheBatchButStoresEachRecordOnce() {
        router().use { r ->
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            r.route(tag, "pg")
            r.appendAll((1..6).map { sample(it) })
            pg.commitThenFail = 1 // the first batch (1..4) commits, then the connection drops
            assertEquals(0, r.forward())
            assertEquals(6L, r.buffer.pendingCounts()["pg"], "an unacknowledged batch stays buffered")
            clock.advance(Duration.ofSeconds(5))
            assertEquals(6, r.forward())
            assertEquals((1..6).toList(), values(pg))
        }
    }

    @Test
    fun aPermanentRejectionQuarantinesOnlyTheBadRecordAndTheQueueKeepsMoving() {
        router(batch = 4).use { r ->
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            r.route(tag, "pg")
            pg.reject = { it.value == 3.0 }
            r.appendAll((1..9).map { sample(it) })
            assertEquals(8, r.forward())
            assertEquals(listOf(1, 2, 4, 5, 6, 7, 8, 9), values(pg))
            val q = r.buffer.quarantined()
            assertEquals(1, q.size)
            assertEquals("pg", q.single().provider)
            assertEquals(3.0, q.single().sample.value)
            assertTrue(q.single().error.contains("23514"), q.single().error)
            assertEquals(t0.toEpochMilli(), q.single().quarantinedMillis)
            assertEquals(1L, r.status().single { it["name"] == "pg" }["quarantined"])

            // Fixed on the database side: retry puts it back on the queue and it is forwarded.
            pg.reject = { false }
            assertEquals(1, r.buffer.retryQuarantined(listOf(q.single().id)))
            assertEquals(1, r.forward())
            assertEquals(listOf(1, 2, 4, 5, 6, 7, 8, 9, 3), values(pg))
            assertEquals(0, r.buffer.quarantined().size)
        }
    }

    @Test
    fun quarantinedRecordsCanBeListedPerProviderRetriedAgainAndDeleted() {
        router().use { r ->
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            val ts = FakeRemoteProvider("ts").also { r.register(it) }
            r.route(tag, "pg")
            r.route("[default]Plant/Other", "ts")
            pg.reject = { true }
            ts.reject = { true }
            r.appendAll(listOf(sample(1), sample(2), sample(3, "[default]Plant/Other")))
            r.forward()
            assertEquals(2, r.buffer.quarantined("pg").size)
            assertEquals(1, r.buffer.quarantined("ts").size)
            // Still rejected: a retry quarantines it again instead of blocking.
            assertEquals(1, r.buffer.retryQuarantined(null, provider = "ts"))
            r.forward()
            assertEquals(1, r.buffer.quarantined("ts").size)
            assertEquals(2, r.buffer.deleteQuarantined(r.buffer.quarantined("pg").map { it.id }))
            assertEquals(0, r.buffer.quarantined("pg").size)
            assertEquals(1, r.buffer.deleteQuarantined(null))
            assertEquals(0, r.buffer.quarantined().size)
        }
    }

    @Test
    fun bufferedRecordsSurviveARestartAndAreForwardedOnce() {
        val sourceId: String
        router().use { r ->
            FakeRemoteProvider("pg").also { it.down = true; r.register(it) }
            r.route(tag, "pg")
            r.appendAll((1..5).map { sample(it) })
            r.forward()
            sourceId = r.buffer.sourceId
        }
        router().use { r ->
            assertEquals(sourceId, r.buffer.sourceId, "the buffer keeps its source id across restarts")
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            assertEquals(5, r.forward())
            assertEquals((1..5).toList(), values(pg))
            // The deadband baseline after restart comes from the buffer, not the (maybe unreachable) provider.
            assertEquals(5.0, r.latestAll().getValue(tag).value)
        }
    }

    @Test
    fun recordsForAnUnconfiguredProviderStayQueuedUntilItExists() {
        router().use { r ->
            r.route(tag, "later")
            r.appendAll((1..3).map { sample(it) })
            assertEquals(0, r.forward())
            val st = r.status().single { it["name"] == "later" }
            assertEquals(false, st["configured"])
            assertEquals(3L, st["pending"])
            assertTrue((st["lastError"] as String).contains("not configured"))
            val later = FakeRemoteProvider("later").also { r.register(it) }
            assertEquals(3, r.forward())
            assertEquals(listOf(1, 2, 3), values(later))
        }
    }

    @Test
    fun aRetriedMixedBatchIsNotBufferedTwice() {
        val local = object : HistorySink by MemoryHistorySink() {
            var fail = true
            val inner = MemoryHistorySink()
            override fun appendAll(samples: List<HistorySample>) {
                if (fail) { fail = false; throw java.sql.SQLException("database is locked") }
                inner.appendAll(samples)
            }
        }
        router(local).use { r ->
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            r.route(tag, "pg")
            val batch = listOf(sample(1), sample(2, "[default]Plant/Local"))
            assertThrows(java.sql.SQLException::class.java) { r.appendAll(batch) }
            r.appendAll(batch) // the collector retries the same held batch
            r.forward()
            assertEquals(listOf(1), values(pg))
            assertEquals(1, local.inner.query(HistoryQuery("[default]Plant/Local", 0, Long.MAX_VALUE)).size)
        }
    }

    /**
     * Archive moves one provider's pending disk cache to a synced file and leaves quarantine and other
     * providers alone. Load puts the same (sourceId, seq) back, in order, and they forward once.
     * (doc source: FEATURES HIS-025)
     */
    @Test
    fun diskCacheArchiveMovesPendingRecordsAndLoadRestoresTheirOrder() {
        router().use { r ->
            val pg = FakeRemoteProvider("pg").also { it.down = true; r.register(it) }
            val other = FakeRemoteProvider("other").also { it.down = true; r.register(it) }
            val archives = DiskCacheArchives(tmp.resolve("archives"), r.buffer, clock)
            r.route(tag, "pg")
            r.route("[default]Plant/Other", "other")
            r.appendAll((1..5).map { sample(it) } + sample(1, "[default]Plant/Other") + sample(2, "[default]Plant/Other"))
            val archived = archives.archive("pg", pageSize = 2)
            assertEquals(5, archived.records)
            assertEquals("pg", archived.provider)
            assertEquals(null, r.buffer.pendingCounts()["pg"])
            assertEquals(2L, r.buffer.pendingCounts()["other"], "another provider's cache stays")
            val storedBefore = (r.status().single { it["name"] == "pg" }["stored"] as Number).toLong()
            assertEquals(5, archives.load(archived.name, pageSize = 2, providerExists = { true }, noteStored = r::noteStored))
            assertEquals(5L, r.buffer.pendingCounts()["pg"])
            assertEquals(storedBefore + 5, (r.status().single { it["name"] == "pg" }["stored"] as Number).toLong())
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0), r.buffer.head("pg", 10).map { it.sample.value })

            pg.down = false
            other.down = false
            clock.advance(Duration.ofMillis(HistoryRouter.STORE_RATE_WINDOW_MILLIS))
            assertEquals(7, r.forward())
            assertEquals(listOf(1, 2, 3, 4, 5), values(pg))
            assertEquals(listOf(1, 2), other.rows().map { (it.value as Double).toInt() })
            assertEquals(emptyMap<String, Long>(), r.buffer.pendingCounts())

            pg.reject = { it.value == 9.0 }
            r.appendAll(listOf(sample(9)))
            r.forward()
            assertEquals(1, r.buffer.quarantined("pg").size)
            val empty = archives.archive("pg", pageSize = 2)
            assertEquals(0, empty.records, "quarantine is not part of the disk cache")
            assertEquals(1, r.buffer.quarantined("pg").size)
        }
    }

    /**
     * Store rate is datapoints per second entering the disk cache over a trailing 10 second window.
     * Local samples bypass store-and-forward, so that provider's rate stays zero.
     * (doc source: FEATURES HIS-025)
     */
    @Test
    fun storeRateIsDatapointsEnteringTheDiskCachePerSecond() {
        router().use { r ->
            FakeRemoteProvider("pg").also { it.down = true; r.register(it) }
            r.route(tag, "pg")
            assertEquals(0.0, storeRate(r, "pg"), 1e-9)
            assertEquals(0L, stored(r, "pg"))
            r.appendAll((1..4).map { sample(it) })
            assertEquals(4L, stored(r, "pg"))
            assertEquals(4 * 1000.0 / HistoryRouter.STORE_RATE_WINDOW_MILLIS, storeRate(r, "pg"), 1e-9)
            r.appendAll(listOf(sample(9, "[default]Plant/Local")))
            assertEquals(0.0, storeRate(r, LOCAL_PROVIDER), 1e-9)
            assertEquals(0L, stored(r, LOCAL_PROVIDER))
            clock.advance(Duration.ofMillis(HistoryRouter.STORE_RATE_WINDOW_MILLIS))
            assertEquals(0.0, storeRate(r, "pg"), 1e-9)
            assertEquals(4L, stored(r, "pg"), "the cumulative count stays after the window slides")
        }
    }

    /**
     * Import places an exported quarantine sample back on the queue and forwards it.
     * A provider that still rejects the data quarantines it again instead of blocking the queue.
     * (doc source: FEATURES HIS-025)
     */
    @Test
    fun anImportedQuarantineRecordIsRetriedAndARejectedOneIsQuarantinedAgain() {
        router().use { r ->
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            r.route(tag, "pg")
            pg.reject = { (it.value as Double) == 3.0 }
            r.appendAll((1..3).map { sample(it) })
            r.forward()
            val held = r.buffer.quarantined().single()
            assertEquals(1, r.buffer.deleteQuarantined(listOf(held.id)))
            assertEquals(0, r.importQuarantine(emptyList()))
            pg.reject = { false }
            assertEquals(1, r.importQuarantine(listOf(DiskCacheRecord("pg", held.sample))))
            assertEquals(listOf(1, 2, 3), values(pg))
            assertEquals(0, r.buffer.quarantined().size)

            pg.reject = { true }
            assertEquals(1, r.importQuarantine(listOf(DiskCacheRecord("pg", held.sample))))
            assertEquals(1, r.buffer.quarantined().size)
            assertEquals(0L, r.buffer.pendingCounts()["pg"] ?: 0L)
            assertEquals(listOf(1, 2, 3), values(pg))
        }
    }

    private fun storeRate(r: HistoryRouter, name: String) = r.status().single { it["name"] == name }["storeRate"] as Double

    private fun stored(r: HistoryRouter, name: String) = (r.status().single { it["name"] == name }["stored"] as Number).toLong()

    @Test
    fun theLocalNameIsReservedAndReadersResolveByProvider() {
        router().use { r ->
            assertThrows(IllegalArgumentException::class.java) { r.register(FakeRemoteProvider(LOCAL_PROVIDER)) }
            assertEquals(r.local, r.reader(LOCAL_PROVIDER))
            assertNull(r.reader("pg"))
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            assertEquals(pg, r.reader("pg"))
            assertEquals(LOCAL_PROVIDER, r.providerFor("Plant/Flow"))
            r.route("Plant/Flow", "pg")
            assertEquals("pg", r.providerFor("[DEFAULT]plant/flow"))
            r.unregister("pg")
            assertNull(r.reader("pg"))
        }
    }
}

/**
 * A sink that keeps failing is visible and bounded (follow-up to the lead's #52 note): consecutive failures and
 * the last error are counted, and from the threshold on each failing batch goes to the durable quarantine instead
 * of being held in memory forever. Retrying the quarantine after the store recovers writes the samples in order.
 */
class SinkFailureDivertTest {
    @TempDir lateinit var tmp: Path

    private val clock = FakeClock(Instant.parse("2026-06-01T00:00:00Z"))

    private class BrokenLocal : HistorySink {
        val inner = MemoryHistorySink()
        @Volatile var broken = true
        override fun appendAll(samples: List<HistorySample>) {
            if (broken) throw java.sql.SQLException("disk I/O error", "SQLITE_IOERR")
            inner.appendAll(samples)
        }
        override fun query(query: HistoryQuery) = inner.query(query)
        override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) = inner.scan(tagPath, fromMillis, toMillis, each)
        override fun latestBefore(tagPath: String, beforeMillis: Long) = inner.latestBefore(tagPath, beforeMillis)
        override fun latestAll(): Map<String, HistorySample> = inner.latestAll()
    }

    @Test
    fun aPermanentlyFailingSinkIsCountedAndItsBatchesGoToQuarantineAfterTheThreshold() {
        val engine = org.oip.tags.TagEngine(clock).also {
            it.configure(org.oip.tags.TagConfig(path = "Plant/Speed", type = org.oip.tags.TagType.MEMORY,
                dataType = org.oip.tags.DataType.DOUBLE, value = 0.0, valueSet = true))
        }
        val local = BrokenLocal()
        HistoryRouter(local, ForwardBuffer(tmp.resolve("fwd.sqlite")), clock).use { r ->
            HistoryCollector(engine, r, clock, divert = r::divert, divertAfterFailures = 3).use { c ->
                c.configure(HistorySettings("Plant/Speed"))
                fun write(v: Double) { clock.advance(Duration.ofSeconds(1)); engine.write("[default]Plant/Speed", v) }
                write(1.0)
                repeat(2) { assertThrows(java.sql.SQLException::class.java) { c.flush() } }
                assertEquals(2, c.consecutiveFlushFailures)
                assertEquals(2, c.pending, "held in memory below the threshold")
                assertEquals(0, r.buffer.quarantined().size)
                assertTrue(c.lastFlushError!!.contains("disk I/O error"), c.lastFlushError)
                assertEquals(clock.instant(), c.lastFlushErrorAt)

                // Third failure: the batch is diverted to the durable quarantine; nothing is held.
                assertThrows(java.sql.SQLException::class.java) { c.flush() }
                assertEquals(3, c.consecutiveFlushFailures)
                assertEquals(0, c.pending)
                assertEquals(2L, c.diverted)
                assertEquals(listOf(0.0, 1.0), r.buffer.quarantined(LOCAL_PROVIDER).map { it.sample.value })
                assertTrue(r.buffer.quarantined().first().error.contains("disk I/O error"))

                // Still failing: each new batch is diverted at once, so memory stays bounded.
                write(2.0)
                assertThrows(java.sql.SQLException::class.java) { c.flush() }
                assertEquals(0, c.pending)
                assertEquals(3L, c.diverted)
                assertEquals(3L, c.stats()["diverted"])

                // The store recovers: the counter resets, and retried records are written in order.
                local.broken = false
                write(3.0)
                assertEquals(1, c.flush())
                assertEquals(0, c.consecutiveFlushFailures)
                assertEquals(3, r.buffer.retryQuarantined(null, LOCAL_PROVIDER))
                assertEquals(3, r.forward())
                assertEquals(listOf(0.0, 1.0, 2.0, 3.0), local.inner.query(HistoryQuery("[default]Plant/Speed", 0, Long.MAX_VALUE)).map { it.value })
                assertEquals(0, r.buffer.quarantined().size)
            }
        }
    }

    /**
     * Review-3 F8: a history query flushes the collector so it sees its own writes, but a failure on that reader's
     * flush does not count toward the divert threshold, so quick reads while the store is failing do not send the
     * held batch to quarantine early.
     */
    @Test
    fun readerFlushesDoNotPushTheCollectorIntoQuarantine() {
        val engine = org.oip.tags.TagEngine(clock).also {
            it.configure(org.oip.tags.TagConfig(path = "Plant/Speed", type = org.oip.tags.TagType.MEMORY,
                dataType = org.oip.tags.DataType.DOUBLE, value = 0.0, valueSet = true))
        }
        val local = BrokenLocal()
        HistoryRouter(local, ForwardBuffer(tmp.resolve("fwd-read.sqlite")), clock).use { r ->
            HistoryCollector(engine, r, clock, divert = r::divert, divertAfterFailures = 3).use { c ->
                c.configure(HistorySettings("Plant/Speed"))
                clock.advance(Duration.ofSeconds(1))
                engine.write("[default]Plant/Speed", 1.0)
                repeat(10) { assertThrows(java.sql.SQLException::class.java) { c.flushForRead() } }
                assertEquals(0, c.consecutiveFlushFailures, "reads do not count")
                assertEquals(0L, c.diverted)
                assertEquals(2, c.pending, "still held in memory")
                assertEquals(0, r.buffer.quarantined().size)
                assertThrows(java.sql.SQLException::class.java) { c.flush() }
                assertEquals(1, c.consecutiveFlushFailures, "the writer's own failures still count")
                local.broken = false
                assertEquals(2, c.flushForRead())
                assertEquals(0, c.consecutiveFlushFailures)
            }
        }
    }

    @Test
    fun insufficientPrivilegeIsTransientSoRecordsWaitForAGrant() {
        assertFalse(SqlHistoryProvider.isPermanent("42501"), "a missing grant is fixed without touching the data")
        assertFalse(SqlHistoryProvider.isPermanent("42P01"))
        assertTrue(SqlHistoryProvider.isPermanent("42601"), "a syntax error still rejects the data")
        assertTrue(SqlHistoryProvider.isPermanent("23505"))
        assertFalse(SqlHistoryProvider.isPermanent("08006"))
    }
}
