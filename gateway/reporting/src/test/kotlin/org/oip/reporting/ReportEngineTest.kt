package org.oip.reporting

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.db.QueryForbidden
import org.oip.db.QueryResult
import org.oip.historian.HistoryReader
import org.oip.historian.HistorySample
import org.oip.historian.SqliteHistoryStore
import org.oip.tags.Quality
import java.nio.file.Path
import java.time.Instant

class ReportEngineTest {
    @TempDir lateinit var tmp: Path
    private val clock = MutableClock(1_000_000L)
    private var store: SqliteHistoryStore? = null

    private val temp = "[default]Line1/Temp"
    private val press = "[default]Line1/Press"
    private val secret = "[default]Secret/Recipe"

    @AfterEach
    fun close() { store?.close() }

    private fun history(): HistoryAccess {
        val s = SqliteHistoryStore(tmp.resolve("h.sqlite")).also { store = it }
        s.appendAll(listOf(
            HistorySample(temp, 100_000, 20.0, Quality.GOOD),
            HistorySample(temp, 400_000, 22.0, Quality.GOOD),
            HistorySample(temp, 700_000, 24.0, Quality.BAD_NOT_CONNECTED),
            HistorySample(press, 200_000, 1.5, Quality.GOOD),
            HistorySample(secret, 300_000, 42.0, Quality.GOOD),
        ))
        return HistoryAccess { _ -> s as HistoryReader }
    }

    private val denySecret = TagReadCheck { _, path -> !path.startsWith("[default]Secret/") }

    private fun def(vararg sources: DataSourceDef, params: List<ParameterDef> = listOf(
        ParameterDef("range", ParameterType.TIME_RANGE, null, mapOf("last" to "15m"), true, false),
        ParameterDef("line", ParameterType.STRING, null, "L1", true, false),
        ParameterDef("at", ParameterType.TIMESTAMP, null, null, false, false),
    )) = ReportDefinition("r1", 3, "Report one", "", true, params, sources.toList(), null, mapOf("k" to "v"))

    private fun tags(mode: HistoryMode, vararg paths: String, aggregate: String? = null, interval: Long? = null, limit: Int = 10_000) =
        DataSourceDef.TagHistory("h", mode, paths.map { HistoryTag(it, it.substringAfterLast('/'), "u") }, Binding.Param("range", null), aggregate, interval, limit)

    @Test
    fun namedQuerySourceBindsParameters() {
        val queries = FakeQueries(answer = { _, _ ->
            QueryResult.Rows(listOf("line", "count", "at", "ok", "dec"), listOf(
                listOf("L1", 120, "2026-10-27T06:00:00Z", true, "12.50"),
                listOf("L1", null, "2026-10-27T07:00:00Z", false, "3"),
            ), cached = false, truncated = false)
        })
        val source = DataSourceDef.NamedQuery("production", "line-production", linkedMapOf(
            "line" to Binding.Param("line", null),
            "start" to Binding.Param("range", "from"),
            "end" to Binding.Param("range", "to"),
            "at" to Binding.Param("at", null),
            "limit" to Binding.Literal(100),
        ), mapOf("dec" to ColumnOverride(ColumnType.NUMBER, "kg")))
        val engine = ReportEngine(queries, null, denySecret, clock)
        val result = engine.run(def(source), mapOf("line" to "L2", "at" to "2026-10-27T00:00:00Z"), USER, RunTrigger.MANUAL)

        val (name, params, caller) = queries.calls.single()
        assertEquals("line-production", name)
        assertEquals(mapOf("line" to "L2", "start" to 100_000L, "end" to 1_000_000L, "at" to Instant.parse("2026-10-27T00:00:00Z").toEpochMilli(), "limit" to 100), params)
        assertEquals(USER, caller)

        val ds = result.datasets.single()
        assertEquals(listOf(
            Column("line", ColumnType.STRING), Column("count", ColumnType.NUMBER), Column("at", ColumnType.TIMESTAMP),
            Column("ok", ColumnType.BOOLEAN), Column("dec", ColumnType.NUMBER, "kg"),
        ), ds.columns)
        assertEquals(listOf("L1", 120, Instant.parse("2026-10-27T06:00:00Z").toEpochMilli(), true, 12.5), ds.rows[0])
        assertEquals(listOf("L1", null, Instant.parse("2026-10-27T07:00:00Z").toEpochMilli(), false, 3L), ds.rows[1])
        assertEquals(RunStatus.SUCCESS, result.run.status)
        assertEquals(CONTRACT_VERSION, result.toMap()["contractVersion"])
        assertEquals(mapOf("k" to "v"), result.toMap()["layout"])
        assertEquals(listOf("contractVersion", "report", "parameters", "generatedAt", "datasets", "run", "layout"), result.toMap().keys.toList())
        assertEquals(listOf("range", "line", "at"), result.parameters.map { it.name })
    }

    @Test
    fun sqlSourceBindsParametersAndRefusesAWrite() {
        val queries = FakeQueries()
        queries.sqlAnswer = { _, _, _ ->
            QueryResult.Rows(listOf("name", "n"), listOf(listOf("ada", 2), listOf("bea", 5)), false, false)
        }
        val source = DataSourceDef.Sql(
            "people", "plant", "SELECT name, n FROM item WHERE line = :line",
            mapOf("line" to Binding.Param("line", null)), emptyMap(),
        )
        val engine = ReportEngine(queries, null, denySecret, clock)
        val result = engine.run(def(source), mapOf("line" to "L2"), USER, RunTrigger.MANUAL)
        val (connection, sql, params) = queries.sqlCalls.single()
        assertEquals("plant", connection)
        assertEquals("SELECT name, n FROM item WHERE line = :line", sql)
        assertEquals("L2", params["line"])
        assertEquals(SourceType.SQL, result.datasets.single().sourceType)
        assertEquals(2, result.datasets.single().rows.size)
        queries.sqlAnswer = { _, _, _ -> throw QueryForbidden("a report SQL source can only read") }
        val refused = assertThrows(ReportRunException::class.java) {
            engine.run(def(source), emptyMap(), USER, RunTrigger.MANUAL)
        }
        assertEquals(422, refused.status)
    }

    @Test
    fun scalarAndUpdateQueries() {
        val queries = FakeQueries(types = mapOf("del" to "update"), answer = { _, _ -> QueryResult.Scalar(7, false, false) })
        val engine = ReportEngine(queries, null, denySecret, clock)
        val scalar = engine.run(def(DataSourceDef.NamedQuery("s", "count", emptyMap(), emptyMap())), emptyMap(), USER, RunTrigger.MANUAL)
        assertEquals(listOf(listOf<Any?>(7)), scalar.datasets.single().rows)
        assertEquals("value", scalar.datasets.single().columns.single().name)
        // An update query is refused before it is executed.
        val e = assertThrows(ReportRunException::class.java) {
            engine.run(def(DataSourceDef.NamedQuery("d", "del", emptyMap(), emptyMap())), emptyMap(), USER, RunTrigger.MANUAL)
        }
        assertEquals(422, e.status)
        assertEquals(1, queries.calls.size)
        queries.answer = { _, _ -> throw QueryForbidden("named query 'count' is not permitted for this role") }
        assertEquals(403, assertThrows(ReportRunException::class.java) {
            engine.run(def(DataSourceDef.NamedQuery("s", "count", emptyMap(), emptyMap())), emptyMap(), USER, RunTrigger.MANUAL)
        }.status)
    }

    @Test
    fun historyRawSource() {
        val engine = ReportEngine(null, history(), denySecret, clock)
        val result = engine.run(def(tags(HistoryMode.RAW, "Line1/Temp", "[default]Line1/Press")), emptyMap(), USER, RunTrigger.MANUAL)
        val ds = result.datasets.single()
        assertEquals(listOf("tagPath", "timestamp", "value", "quality"), ds.columns.map { it.name })
        assertEquals(listOf(ColumnType.STRING, ColumnType.TIMESTAMP, ColumnType.NUMBER, ColumnType.STRING), ds.columns.map { it.type })
        assertEquals("u", ds.columns[2].unit)
        assertEquals(listOf(
            listOf(temp, 100_000L, 20.0, "Good"),
            listOf(temp, 400_000L, 22.0, "Good"),
            listOf(temp, 700_000L, 24.0, "Bad_NotConnected"),
            listOf(press, 200_000L, 1.5, "Good"),
        ), ds.rows)
        assertEquals(listOf(temp, press), ds.series.map { it.tagPath })
        assertEquals("Temp", ds.series[0].label)
        assertEquals(listOf(listOf(100_000L, 20.0), listOf(400_000L, 22.0), listOf(700_000L, 24.0)), ds.series[0].points)
        assertFalse(ds.truncated)

        // Limit per tag truncates and makes the run partial.
        val limited = engine.run(def(tags(HistoryMode.RAW, temp, limit = 2)), emptyMap(), USER, RunTrigger.MANUAL)
        assertTrue(limited.datasets.single().truncated)
        assertEquals(2, limited.datasets.single().rows.size)
        assertEquals(RunStatus.PARTIAL, limited.run.status)

        // A narrower explicit range.
        val ranged = engine.run(def(tags(HistoryMode.RAW, temp)), mapOf("range" to mapOf("from" to 300_000, "to" to 500_000)), USER, RunTrigger.MANUAL)
        assertEquals(listOf(400_000L), ranged.datasets.single().rows.map { it[1] })
    }

    @Test
    fun historyAggregateSource() {
        val engine = ReportEngine(null, history(), denySecret, clock)
        val result = engine.run(def(tags(HistoryMode.AGGREGATE, temp, aggregate = "Maximum", interval = 300_000)),
            mapOf("range" to mapOf("from" to 100_000, "to" to 699_999)), USER, RunTrigger.MANUAL)
        val ds = result.datasets.single()
        assertEquals(listOf("tagPath", "start", "end", "value", "rows"), ds.columns.map { it.name })
        assertEquals(listOf(
            listOf(temp, 100_000L, 400_000L, 20.0, 1),
            listOf(temp, 400_000L, 700_000L, 22.0, 1),
        ), ds.rows)
        assertEquals(listOf(listOf(100_000L, 20.0), listOf(400_000L, 22.0)), ds.series.single().points)
        // Too many windows for the range is a 422, not a crash.
        val e = assertThrows(ReportRunException::class.java) {
            engine.run(def(tags(HistoryMode.AGGREGATE, temp, aggregate = "Average", interval = 1)), emptyMap(), USER, RunTrigger.MANUAL)
        }
        assertEquals(422, e.status)
    }

    @Test
    fun unreadableTagsAreExcludedAndNeverQueried() {
        val queried = ArrayList<String>()
        val base = history()
        val spy = HistoryAccess { path -> queried += path; base.reader(path) }
        val engine = ReportEngine(null, spy, denySecret, clock)
        val result = engine.run(def(tags(HistoryMode.RAW, temp, "Secret/Recipe")), emptyMap(), USER, RunTrigger.MANUAL)
        val ds = result.datasets.single()
        assertEquals(listOf(secret), ds.excludedTags)
        assertFalse(ds.rows.any { it[0] == secret })
        assertFalse(ds.series.any { it.tagPath == secret })
        assertEquals(listOf(temp), queried)
        assertEquals(RunStatus.PARTIAL, result.run.status)
        assertTrue(result.run.warnings.single().contains("excluded"))

        // Only unreadable tags: the run is denied.
        val e = assertThrows(ReportRunException::class.java) {
            engine.run(def(tags(HistoryMode.AGGREGATE, "Secret/Recipe", aggregate = "Average", interval = 60_000)), emptyMap(), USER, RunTrigger.MANUAL)
        }
        assertEquals(403, e.status)

        // A scheduled (system) run is not filtered.
        val system = engine.run(def(tags(HistoryMode.RAW, "Secret/Recipe")), emptyMap(), ReportCaller.SYSTEM, RunTrigger.SCHEDULE)
        assertEquals(listOf(listOf(secret, 300_000L, 42.0, "Good")), system.datasets.single().rows)
    }
}
