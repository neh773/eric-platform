package org.oip.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.db.QueryFailed
import org.oip.db.QueryResult
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReportSchedulerTest {
    @TempDir lateinit var tmp: Path

    private val t0 = Instant.parse("2026-10-27T05:58:30Z").toEpochMilli()
    private val sixAm = Instant.parse("2026-10-27T06:00:00Z").toEpochMilli()

    private fun spec(cron: String = "0 6 * * *", enabled: Boolean = true) = mapOf(
        "parameters" to listOf(mapOf("name" to "line", "type" to "string", "default" to "L1")),
        "dataSources" to listOf(mapOf("name" to "production", "type" to "namedQuery", "query" to "line-production",
            "parameters" to mapOf("line" to mapOf("param" to "line")))),
        "schedule" to mapOf("cron" to cron, "enabled" to enabled, "parameters" to mapOf("line" to "L9")),
    )

    private class Harness(val store: FileConfigStore, val service: ReportService, val queries: FakeQueries) : AutoCloseable {
        override fun close() { service.close(); store.close() }
    }

    private fun harness(clock: MutableClock, keep: Int = RunHistory.DEFAULT_KEEP): Harness {
        val store = FileConfigStore(tmp.resolve("config"), SchemaRegistry(), clock)
        val queries = FakeQueries(answer = { _, p -> QueryResult.Rows(listOf("line"), listOf(listOf(p["line"])), false, false) })
        val service = ReportService(store, ReportEngine(queries, null, { _, _ -> true }, clock),
            RunHistory(tmp.resolve("db/report-runs.sqlite"), keep), clock).attach()
        return Harness(store, service, queries)
    }

    @Test
    fun schedulerRecordsSuccessAndFailure() {
        val clock = MutableClock(t0)
        harness(clock).use { h ->
            h.store.putReport("daily", spec())
            val delivered = ArrayList<ReportResult>()
            val scheduler = ReportScheduler(h.service, clock, { _, r -> delivered += r }, runner = { it.run() })
            scheduler.tick()
            assertEquals(mapOf("daily" to sixAm), scheduler.nextFires())
            assertTrue(h.service.runs("daily", 10).isEmpty())

            clock.millis = sixAm + 500
            scheduler.tick()
            val run = h.service.runs("daily", 10).single()
            assertEquals(RunTrigger.SCHEDULE, run.trigger)
            assertEquals("system", run.user)
            assertEquals(RunStatus.SUCCESS, run.status)
            assertEquals(1, run.rows)
            // Schedule parameters were used, and the same result shape as a manual run was delivered.
            assertEquals(listOf(listOf<Any?>("L9")), delivered.single().datasets.single().rows)
            assertEquals(RunTrigger.SCHEDULE, delivered.single().run.trigger)
            assertEquals(Instant.parse("2026-10-28T06:00:00Z").toEpochMilli(), scheduler.nextFires()["daily"])
            // Not due again within the same minute.
            scheduler.tick(sixAm + 30_000)
            assertEquals(1, h.service.runs("daily", 10).size)

            // The database fails for the next two fires.
            h.queries.answer = { _, _ -> throw QueryFailed("connection refused") }
            for (day in 1..2) {
                clock.millis = sixAm + day * 86_400_000L
                scheduler.tick()
            }
            val status = h.service.status("daily")
            assertEquals(RunStatus.FAILURE, status.lastStatus)
            assertEquals(2, status.consecutiveFailures)
            assertTrue(status.lastError!!.contains("connection refused"))
            assertEquals(true, status.scheduled)
            assertEquals(Instant.parse("2026-10-30T06:00:00Z").toEpochMilli(), status.nextFireAt)
            assertEquals(listOf(RunStatus.FAILURE, RunStatus.FAILURE, RunStatus.SUCCESS), h.service.runs("daily", 10).map { it.status })

            // Recovery resets the failure count.
            h.queries.answer = { _, _ -> QueryResult.Rows(listOf("line"), emptyList(), false, false) }
            clock.millis = sixAm + 3 * 86_400_000L
            scheduler.tick()
            assertEquals(0, h.service.status("daily").consecutiveFailures)
            assertNull(h.service.status("daily").lastError)
        }
        // History survives a restart.
        harness(MutableClock(t0)).use { h ->
            assertEquals(4, h.service.runs("daily", 10).size)
            assertEquals(RunStatus.SUCCESS, h.service.status("daily").lastStatus)
        }
    }

    @Test
    fun editedOrDisabledSchedules() {
        val clock = MutableClock(t0)
        harness(clock).use { h ->
            h.store.putReport("daily", spec())
            val scheduler = ReportScheduler(h.service, clock, runner = { it.run() })
            scheduler.tick()
            // Editing the cron recomputes the next fire.
            h.store.putReport("daily", spec(cron = "*/5 * * * *"))
            scheduler.tick()
            assertEquals(Instant.parse("2026-10-27T06:00:00Z").toEpochMilli(), scheduler.nextFires()["daily"])
            h.store.putReport("daily", spec(cron = "30 * * * *"))
            scheduler.tick()
            assertEquals(Instant.parse("2026-10-27T06:30:00Z").toEpochMilli(), scheduler.nextFires()["daily"])
            // Disabled: dropped from the schedule and never run.
            h.store.putReport("daily", spec(enabled = false))
            scheduler.tick(sixAm + 3_600_000)
            assertTrue(scheduler.nextFires().isEmpty())
            assertTrue(h.service.runs("daily", 10).isEmpty())
            assertEquals(false, h.service.status("daily").scheduled)
        }
    }

    @Test
    fun manualRunsAndRefusals() {
        val clock = MutableClock(t0)
        harness(clock).use { h ->
            h.store.putReport("daily", spec())
            val result = h.service.run("daily", mapOf("line" to "L3"), USER, RunTrigger.MANUAL)
            assertEquals(listOf(listOf<Any?>("L3")), result.datasets.single().rows)
            assertEquals("ann", h.service.runs("daily", 10).single().user)
            assertThrows(ReportParameterException::class.java) { h.service.run("daily", mapOf("line" to 3), USER, RunTrigger.MANUAL) }
            assertThrows(ReportNotFound::class.java) { h.service.run("nope", emptyMap(), USER, RunTrigger.MANUAL) }
            // A history range bound to a parameter with no value is a parameter error, not a failed run.
            h.store.putReport("ranged", mapOf(
                "parameters" to listOf(mapOf("name" to "range", "type" to "timeRange")),
                "dataSources" to listOf(mapOf("name" to "h", "type" to "tagHistory", "tags" to listOf(mapOf("path" to "A/B")), "range" to mapOf("param" to "range"))),
            ))
            assertThrows(ReportParameterException::class.java) { h.service.run("ranged", emptyMap(), USER, RunTrigger.MANUAL) }
            assertTrue(h.service.runs("ranged", 10).isEmpty())
            h.store.putReport("daily", spec() + ("enabled" to false))
            assertThrows(ReportDisabled::class.java) { h.service.run("daily", emptyMap(), USER, RunTrigger.MANUAL) }
            // Refusals are not runs.
            assertEquals(1, h.service.runs("daily", 10).size)
        }
    }

    @Test
    fun runHistoryIsBounded() {
        val clock = MutableClock(t0)
        harness(clock, keep = 3).use { h ->
            h.store.putReport("daily", spec())
            repeat(5) { clock.millis += 1000; h.service.run("daily", emptyMap(), USER, RunTrigger.MANUAL) }
            val runs = h.service.runs("daily", 10)
            assertEquals(3, runs.size)
            assertEquals(listOf(t0 + 5000, t0 + 4000, t0 + 3000), runs.map { it.startedAt })
        }
    }

    @Test
    fun startAndStopRunTheTicker() {
        val clock = MutableClock(t0)
        harness(clock).use { h ->
            h.store.putReport("every-minute", spec(cron = "* * * * *"))
            val ran = CountDownLatch(1)
            val scheduler = ReportScheduler(h.service, clock, { _, _ -> ran.countDown() }, tickMillis = 20).start()
            try {
                Thread.sleep(100)
                clock.millis = t0 + 60_000
                assertTrue(ran.await(5, TimeUnit.SECONDS))
            } finally {
                scheduler.stop()
            }
            val count = h.service.runs("every-minute", 10).size
            clock.millis = t0 + 600_000
            Thread.sleep(100)
            assertEquals(count, h.service.runs("every-minute", 10).size)
        }
    }
}
