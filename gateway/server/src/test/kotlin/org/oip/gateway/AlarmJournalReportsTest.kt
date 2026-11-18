package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmJournal
import org.oip.alarm.AlarmPriority
import org.oip.alarm.JournalEntry
import org.oip.alarm.JournalEventTypes
import org.oip.reporting.AlarmJournalFilter
import org.oip.reporting.AlarmJournalRequest
import org.oip.reporting.AlarmJournalResult
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** FEATURES REP-032: the report adapter over a real alarm journal file. */
class AlarmJournalReportsTest {
    @TempDir lateinit var tmp: Path

    private val open = "prov:default:/tag:Line1/Temp:/alm:High"
    private val secret = "prov:default:/tag:Secret/Recipe:/alm:High"

    private fun entry(source: String, type: Int, time: Long, priority: AlarmPriority = AlarmPriority.HIGH, data: Map<String, Any?> = emptyMap()) =
        JournalEntry("ev-$time", source, "Tank", priority, type, 0, time, false, data)

    private fun journal(): AlarmJournal {
        val j = AlarmJournal(tmp.resolve("journal.sqlite"))
        listOf(
            entry(open, JournalEventTypes.ACTIVE, 1_000),
            entry(open, JournalEventTypes.ACKNOWLEDGED, 2_000, data = mapOf("ackUser" to "ann")),
            entry(secret, JournalEventTypes.ACTIVE, 3_000, AlarmPriority.LOW),
            entry(open, JournalEventTypes.CLEAR, 9_000),
        ).forEach { j.append(it) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (j.query().total < 4) {
            check(System.nanoTime() < deadline) { "journal rows were not written" }
            Thread.sleep(20)
        }
        return j
    }

    private fun query(
        reports: AlarmJournalReports,
        filter: AlarmJournalFilter = AlarmJournalFilter(null, null, null, null, null),
        ascending: Boolean = false,
        limit: Int = 100,
        allowed: (String) -> Boolean = { true },
    ): AlarmJournalResult = reports.query(AlarmJournalRequest(0, 5_000, filter, ascending, limit), allowed)

    @Test
    fun readsRowsOfTheRangeWithNamesAndActor() {
        journal().use { j ->
            val result = query(AlarmJournalReports(j))
            // The clear at 9000 is outside the range; newest first.
            assertEquals(listOf(3_000L, 2_000L, 1_000L), result.rows.map { it.eventTime })
            assertEquals(3L, result.total)
            val ack = result.rows[1]
            assertEquals("ack", ack.eventType)
            assertEquals("ann", ack.actor)
            assertEquals("High", ack.priority)
            assertEquals("Low", result.rows[0].priority)
            assertEquals(null, result.rows[2].actor)
            assertEquals("ev-2000", ack.eventId)
        }
    }

    @Test
    fun appliesFiltersOrderAndLimit() {
        journal().use { j ->
            val reports = AlarmJournalReports(j)
            assertEquals(listOf(1_000L, 2_000L, 3_000L), query(reports, ascending = true).rows.map { it.eventTime })
            assertEquals(listOf(2_000L), query(reports, AlarmJournalFilter(null, null, listOf("ack"), null, null)).rows.map { it.eventTime })
            assertEquals(listOf(3_000L), query(reports, AlarmJournalFilter(null, "low", null, null, null)).rows.map { it.eventTime })
            assertEquals(listOf(2_000L, 1_000L), query(reports, AlarmJournalFilter("high", null, null, null, null)).rows.map { it.eventTime })
            val limited = query(reports, limit = 2)
            assertEquals(2, limited.rows.size)
            assertEquals(3L, limited.total)
        }
    }

    @Test
    fun theSourceCheckRemovesRowsBeforeCounting() {
        journal().use { j ->
            val reports = AlarmJournalReports(j)
            val result = query(reports, allowed = { it != secret })
            assertEquals(listOf(2_000L, 1_000L), result.rows.map { it.eventTime })
            assertEquals(2L, result.total)
            assertEquals("[default]Line1/Temp", reports.tagPath(open))
            assertEquals(null, reports.tagPath("system:diagnostic"))
        }
    }

    @Test
    fun anUnknownEventTypeIsRefused() {
        journal().use { j ->
            val failure = runCatching { query(AlarmJournalReports(j), AlarmJournalFilter(null, null, listOf("boom"), null, null)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException, failure.toString())
        }
    }
}
