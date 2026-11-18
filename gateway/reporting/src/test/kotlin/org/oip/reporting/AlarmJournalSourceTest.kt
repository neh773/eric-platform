package org.oip.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FEATURES REP-032: the `alarmJournal` report data source. */
class AlarmJournalSourceTest {
    private val clock = MutableClock(1_000_000L)

    private val hot = "prov:default:/tag:Line1/Temp:/alm:High"
    private val secret = "prov:default:/tag:Secret/Recipe:/alm:High"
    private val row = { t: Long, src: String -> AlarmJournalRow(t, src, "Tank", "High", "active", null, "ev-$t") }

    private class FakeJournal(val rows: List<AlarmJournalRow>, val total: Long? = null) : AlarmJournalAccess {
        val requests = ArrayList<AlarmJournalRequest>()
        override fun tagPath(source: String): String? =
            Regex("^prov:([^:]+):/tag:(.+):/alm:").find(source)?.let { "[${it.groupValues[1]}]${it.groupValues[2]}" }

        override fun query(request: AlarmJournalRequest, sourceAllowed: (String) -> Boolean): AlarmJournalResult {
            requests += request
            val kept = rows.filter { sourceAllowed(it.source) && it.eventTime >= request.from && it.eventTime < request.to }
            return AlarmJournalResult(kept.take(request.limit), total ?: kept.size.toLong())
        }
    }

    private fun def(source: DataSourceDef) = ReportDefinition(
        "r1", 1, "Alarms", "", true,
        listOf(ParameterDef("range", ParameterType.TIME_RANGE, null, mapOf("last" to "15m"), true, false)),
        listOf(source), null, null,
    )

    private fun source(limit: Int = 1000, filter: AlarmJournalFilter = AlarmJournalFilter(null, null, null, null, null), ascending: Boolean = false) =
        DataSourceDef.AlarmJournal("alarms", Binding.Param("range", null), filter, ascending, limit)

    private val readable = TagReadCheck { _, path -> !path.startsWith("[default]Secret/") }

    @Test
    fun rowsBecomeADatasetWithTypedColumns() {
        val journal = FakeJournal(listOf(row(200_000, hot), AlarmJournalRow(300_000, hot, "Tank", "High", "ack", "ann", "ev-2")))
        val result = ReportEngine(null, null, readable, clock, journal).run(def(source()), emptyMap(), USER, RunTrigger.MANUAL)
        val ds = result.datasets.single()
        assertEquals(SourceType.ALARM_JOURNAL, ds.sourceType)
        assertEquals(listOf("eventTime", "source", "displayPath", "priority", "eventType", "actor", "eventId"), ds.columns.map { it.name })
        assertEquals(listOf(ColumnType.TIMESTAMP, ColumnType.STRING, ColumnType.STRING, ColumnType.STRING, ColumnType.STRING, ColumnType.STRING, ColumnType.STRING), ds.columns.map { it.type })
        assertEquals(listOf(200_000L, hot, "Tank", "High", "active", null, "ev-200000"), ds.rows[0])
        assertEquals("ann", ds.rows[1][5])
        assertFalse(ds.truncated)
        assertEquals(RunStatus.SUCCESS, result.run.status)
        // The range parameter (last 15 minutes before the clock) reaches the journal.
        assertEquals(100_000L, journal.requests.single().from)
        assertEquals(1_000_000L, journal.requests.single().to)
    }

    @Test
    fun filtersOrderAndLimitReachTheJournal() {
        val journal = FakeJournal(emptyList())
        val filter = AlarmJournalFilter("medium", "critical", listOf("active", "ack"), "prov:default:*", "Tank*")
        ReportEngine(null, null, readable, clock, journal).run(def(source(limit = 50, filter = filter, ascending = true)), emptyMap(), USER, RunTrigger.MANUAL)
        val request = journal.requests.single()
        assertEquals(filter, request.filter)
        assertTrue(request.ascending)
        assertEquals(50, request.limit)
    }

    @Test
    fun aCallerOnlySeesAlarmsOfTagsItMayRead() {
        val journal = FakeJournal(listOf(row(200_000, hot), row(300_000, secret)))
        val engine = ReportEngine(null, null, readable, clock, journal)
        val user = engine.run(def(source()), emptyMap(), USER, RunTrigger.MANUAL).datasets.single()
        assertEquals(listOf(hot), user.rows.map { it[1] })
        // A scheduled run is not filtered, like tag history.
        val system = engine.run(def(source()), emptyMap(), ReportCaller.SYSTEM, RunTrigger.SCHEDULE).datasets.single()
        assertEquals(listOf(hot, secret), system.rows.map { it[1] })
    }

    @Test
    fun aSourceNotTiedToATagIsShown() {
        val journal = FakeJournal(listOf(row(200_000, "system:diagnostic")))
        val ds = ReportEngine(null, null, readable, clock, journal).run(def(source()), emptyMap(), USER, RunTrigger.MANUAL).datasets.single()
        assertEquals(1, ds.rows.size)
    }

    @Test
    fun moreRowsThanTheLimitTruncateAndMakeTheRunPartial() {
        val journal = FakeJournal((1..5).map { row(100_000L + it, hot) })
        val result = ReportEngine(null, null, readable, clock, journal).run(def(source(limit = 3)), emptyMap(), USER, RunTrigger.MANUAL)
        assertEquals(3, result.datasets.single().rows.size)
        assertTrue(result.datasets.single().truncated)
        assertEquals(RunStatus.PARTIAL, result.run.status)
        assertTrue(result.run.warnings.single().contains("truncated"))
    }

    @Test
    fun withoutAJournalTheRunFailsWith503() {
        val e = assertThrows(ReportRunException::class.java) {
            ReportEngine(null, null, readable, clock).run(def(source()), emptyMap(), USER, RunTrigger.MANUAL)
        }
        assertEquals(503, e.status)
    }

    @Test
    fun aJournalFailureIsA503() {
        val broken = object : AlarmJournalAccess {
            override fun tagPath(source: String): String? = null
            override fun query(request: AlarmJournalRequest, sourceAllowed: (String) -> Boolean): AlarmJournalResult = throw IllegalStateException("disk full")
        }
        val e = assertThrows(ReportRunException::class.java) {
            ReportEngine(null, null, readable, clock, broken).run(def(source()), emptyMap(), USER, RunTrigger.MANUAL)
        }
        assertEquals(503, e.status)
        assertTrue(e.message!!.contains("disk full"))
    }

    // ---- definition ----

    private fun parse(source: String) = ReportDefinitions.parse(
        "r", 1,
        mapOf(
            "parameters" to listOf(mapOf("name" to "range", "type" to "timeRange", "default" to mapOf("last" to "1h"))),
            "dataSources" to listOf(com.fasterxml.jackson.databind.ObjectMapper().readValue(source, Map::class.java)),
        ),
    )

    @Test
    fun definitionParsesFiltersAndDefaults() {
        val d = parse("""{"name":"a","type":"alarmJournal","range":{"param":"range"}}""").dataSources.single() as DataSourceDef.AlarmJournal
        assertEquals(MAX_JOURNAL_ROWS, d.limit)
        assertFalse(d.ascending)
        assertEquals(AlarmJournalFilter(null, null, null, null, null), d.filter)
        val full = parse("""{"name":"a","type":"alarmJournal","range":{"value":{"last":"1h"}},"minPriority":"Medium","maxPriority":"critical","eventTypes":["Active","ack"],"source":"prov:*","displayPath":"Tank*","order":"asc","limit":25}""")
            .dataSources.single() as DataSourceDef.AlarmJournal
        assertEquals(AlarmJournalFilter("medium", "critical", listOf("active", "ack"), "prov:*", "Tank*"), full.filter)
        assertTrue(full.ascending)
        assertEquals(25, full.limit)
    }

    @Test
    fun definitionRejectsBadSettings() {
        fun bad(extra: String, message: String) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                parse("""{"name":"a","type":"alarmJournal","range":{"param":"range"}$extra}""")
            }
            assertTrue(e.message!!.contains(message), "${e.message} should contain $message")
        }
        bad(""","minPriority":"urgent"""", "minPriority must be one of")
        bad(""","minPriority":"high","maxPriority":"low"""", "maxPriority must not be below")
        bad(""","eventTypes":[]""", "eventTypes must not be empty")
        bad(""","eventTypes":["boom"]""", "unknown eventTypes boom")
        bad(""","order":"sideways"""", "order must be asc or desc")
        bad(""","limit":1001""", "limit must be 1..1000")
        bad(""","limit":0""", "limit must be 1..1000")
        bad(""","tags":[{"path":"a"}]""", "tags is not allowed for alarmJournal")
        bad(""","sql":"select 1"""", "sql is not allowed for alarmJournal")
        val noRange = assertThrows(IllegalArgumentException::class.java) { parse("""{"name":"a","type":"alarmJournal"}""") }
        assertTrue(noRange.message!!.contains("range is required"))
        val wrongParam = assertThrows(IllegalArgumentException::class.java) {
            ReportDefinitions.parse("r", 1, mapOf(
                "parameters" to listOf(mapOf("name" to "n", "type" to "number")),
                "dataSources" to listOf(mapOf("name" to "a", "type" to "alarmJournal", "range" to mapOf("param" to "n"))),
            ))
        }
        assertTrue(wrongParam.message!!.contains("must bind a timeRange parameter"))
    }
}
