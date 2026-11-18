package org.oip.gateway

import org.oip.alarm.AlarmJournal
import org.oip.alarm.AlarmPriority
import org.oip.alarm.JournalEventTypes
import org.oip.alarm.JournalQuery
import org.oip.alarm.alarmSourceTagPath
import org.oip.reporting.AlarmJournalAccess
import org.oip.reporting.AlarmJournalRequest
import org.oip.reporting.AlarmJournalResult
import org.oip.reporting.AlarmJournalRow
import java.time.Instant

/**
 * The alarm journal for report data sources (FEATURES REP-032): the same query `GET /api/v1/alarms/journal` runs,
 * with the report's range and filters, and the caller's per-tag read check passed as the source filter.
 */
class AlarmJournalReports(private val journal: AlarmJournal) : AlarmJournalAccess {
    override fun tagPath(source: String): String? = alarmSourceTagPath(source)

    override fun query(request: AlarmJournalRequest, sourceAllowed: (String) -> Boolean): AlarmJournalResult {
        val filter = request.filter
        val page = journal.query(
            JournalQuery(
                start = Instant.ofEpochMilli(request.from),
                end = Instant.ofEpochMilli(request.to),
                source = filter.source,
                displayPath = filter.displayPath,
                minPriority = filter.minPriority?.let { AlarmPriority.fromName(it) },
                maxPriority = filter.maxPriority?.let { AlarmPriority.fromName(it) },
                eventTypes = filter.eventTypes?.map { JournalEventTypes.fromName(it) ?: throw IllegalArgumentException("unknown event type '$it'") }?.toSet(),
                offset = 0,
                limit = request.limit,
                newestFirst = !request.ascending,
                sourceAllowed = sourceAllowed,
            ),
        )
        val rows = page.records.map {
            AlarmJournalRow(
                eventTime = it.eventTimeMillis,
                source = it.source,
                displayPath = it.displayPath,
                priority = AlarmPriority.entries.getOrNull(it.priority)?.label() ?: it.priority.toString(),
                eventType = JournalEventTypes.name(it.eventType),
                actor = it.actor,
                eventId = it.eventId,
            )
        }
        return AlarmJournalResult(rows, page.total)
    }
}
