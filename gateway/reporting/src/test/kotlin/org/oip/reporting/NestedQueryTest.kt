package org.oip.reporting

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.db.QueryResult

/** FEATURES REP-035: a query that runs once per row of an earlier data source. */
class NestedQueryTest {
    private val clock = MutableClock(1_000_000L)

    private fun rows(columns: List<String>, vararg rows: List<Any?>, truncated: Boolean = false) =
        QueryResult.Rows(columns, rows.toList(), cached = false, truncated = truncated)

    private val orders = DataSourceDef.NamedQuery("orders", "orders", emptyMap(), emptyMap())

    private fun items(parent: ParentLink?, extra: Map<String, Binding> = emptyMap()) =
        DataSourceDef.NamedQuery("items", "items-of-order", extra, emptyMap(), parent)

    private fun def(vararg sources: DataSourceDef) =
        ReportDefinition("r", 1, "R", "", true, emptyList(), sources.toList(), null, null)

    private fun fake(): FakeQueries = FakeQueries(answer = { name, params ->
        when (name) {
            "orders" -> rows(listOf("id", "customer"), listOf(1, "Acme"), listOf(2, "Bolt"), listOf(3, "Cog"))
            else -> when (params["orderId"]) {
                1 -> rows(listOf("item", "qty"), listOf("nut", 10), listOf("bolt", 4))
                2 -> rows(listOf("item", "qty"), listOf("washer", 7))
                else -> rows(listOf("item", "qty"))
            }
        }
    })

    @Test
    fun runsOncePerParentRowAndPrefixesParentColumns() {
        val queries = fake()
        val link = ParentLink("orders", linkedMapOf("orderId" to "id"))
        val result = ReportEngine(queries, null, { _, _ -> true }, clock).run(def(orders, items(link, mapOf("limit" to Binding.Literal(5)))), emptyMap(), USER, RunTrigger.MANUAL)
        val ds = result.datasets[1]
        assertEquals(listOf("parent_id", "item", "qty"), ds.columns.map { it.name })
        assertEquals(listOf(ColumnType.NUMBER, ColumnType.STRING, ColumnType.NUMBER), ds.columns.map { it.type })
        assertEquals(listOf(listOf(1, "nut", 10), listOf(1, "bolt", 4), listOf(2, "washer", 7)), ds.rows)
        // The orders query once, the item query once per order, each with the parent value and the literal binding.
        assertEquals(listOf("orders", "items-of-order", "items-of-order", "items-of-order"), queries.calls.map { it.first })
        assertEquals(mapOf("limit" to 5, "orderId" to 1), queries.calls[1].second)
        assertEquals(mapOf("limit" to 5, "orderId" to 3), queries.calls[3].second)
        assertEquals(RunStatus.SUCCESS, result.run.status)
    }

    @Test
    fun severalBoundColumnsAndColumnOverrides() {
        val queries = FakeQueries(answer = { name, params ->
            if (name == "orders") rows(listOf("id", "customer"), listOf(1, "Acme"))
            else rows(listOf("echo"), listOf("${params["o"]}/${params["c"]}"))
        })
        val source = DataSourceDef.NamedQuery("items", "q", emptyMap(), mapOf("parent_id" to ColumnOverride(ColumnType.STRING, "no")),
            ParentLink("orders", linkedMapOf("o" to "id", "c" to "customer")))
        val ds = ReportEngine(queries, null, { _, _ -> true }, clock).run(def(orders, source), emptyMap(), USER, RunTrigger.MANUAL).datasets[1]
        assertEquals(listOf("parent_id", "parent_customer", "echo"), ds.columns.map { it.name })
        assertEquals(listOf(listOf("1", "Acme", "1/Acme")), ds.rows)
        assertEquals("no", ds.columns[0].unit)
    }

    @Test
    fun aSqlSourceCanBeNestedToo() {
        val queries = FakeQueries(answer = { _, _ -> rows(listOf("id"), listOf(7), listOf(8)) })
        queries.sqlAnswer = { _, _, params -> rows(listOf("v"), listOf((params["p"] as Int) * 10)) }
        val sql = DataSourceDef.Sql("child", "plant", "SELECT :p * 10 AS v", emptyMap(), emptyMap(), ParentLink("orders", mapOf("p" to "id")))
        val ds = ReportEngine(queries, null, { _, _ -> true }, clock).run(def(orders, sql), emptyMap(), USER, RunTrigger.MANUAL).datasets[1]
        assertEquals(listOf(listOf(7, 70), listOf(8, 80)), ds.rows)
        assertEquals(listOf(7, 8), queries.sqlCalls.map { it.third["p"] })
    }

    @Test
    fun noParentRowsGiveAnEmptyDatasetWithTheParentColumns() {
        val queries = FakeQueries(answer = { _, _ -> rows(listOf("id", "customer")) })
        val ds = ReportEngine(queries, null, { _, _ -> true }, clock).run(def(orders, items(ParentLink("orders", mapOf("orderId" to "id")))), emptyMap(), USER, RunTrigger.MANUAL).datasets[1]
        assertEquals(listOf("parent_id"), ds.columns.map { it.name })
        assertTrue(ds.rows.isEmpty())
        assertEquals(1, queries.calls.size)
    }

    @Test
    fun moreThanTheParentRowLimitTruncates() {
        val many = (1..150).map { listOf<Any?>(it) }
        val queries = FakeQueries(answer = { name, params ->
            if (name == "orders") QueryResult.Rows(listOf("id"), many, cached = false, truncated = false)
            else rows(listOf("n"), listOf(params["orderId"]))
        })
        val result = ReportEngine(queries, null, { _, _ -> true }, clock).run(def(orders, items(ParentLink("orders", mapOf("orderId" to "id")))), emptyMap(), USER, RunTrigger.MANUAL)
        assertEquals(ReportEngine.MAX_PARENT_ROWS, result.datasets[1].rows.size)
        assertTrue(result.datasets[1].truncated)
        assertEquals(RunStatus.PARTIAL, result.run.status)
        assertEquals(1 + ReportEngine.MAX_PARENT_ROWS, queries.calls.size)
    }

    @Test
    fun aTruncatedChildResultTruncatesTheDataset() {
        val queries = FakeQueries(answer = { name, _ ->
            if (name == "orders") rows(listOf("id"), listOf(1)) else rows(listOf("n"), listOf(1), truncated = true)
        })
        val ds = ReportEngine(queries, null, { _, _ -> true }, clock).run(def(orders, items(ParentLink("orders", mapOf("orderId" to "id")))), emptyMap(), USER, RunTrigger.MANUAL).datasets[1]
        assertTrue(ds.truncated)
    }

    @Test
    fun runProblemsAre422() {
        fun fail(source: DataSourceDef, queries: FakeQueries = fake()): ReportRunException = assertThrows(ReportRunException::class.java) {
            ReportEngine(queries, null, { _, _ -> true }, clock).run(def(orders, source), emptyMap(), USER, RunTrigger.MANUAL)
        }
        val missing = fail(items(ParentLink("orders", mapOf("orderId" to "nope"))))
        assertEquals(422, missing.status)
        assertTrue(missing.message!!.contains("has no column 'nope'"), missing.message)
        val clash = fail(items(ParentLink("orders", mapOf("orderId" to "id"))), FakeQueries(answer = { name, _ ->
            if (name == "orders") rows(listOf("id"), listOf(1)) else rows(listOf("parent_id"), listOf(1))
        }))
        assertEquals(422, clash.status)
        assertTrue(clash.message!!.contains("clashes with a parent column"))
        var call = 0
        val shifting = fail(items(ParentLink("orders", mapOf("orderId" to "id"))), FakeQueries(answer = { name, _ ->
            if (name == "orders") rows(listOf("id"), listOf(1), listOf(2)) else if (call++ == 0) rows(listOf("a")) else rows(listOf("b"))
        }))
        assertTrue(shifting.message!!.contains("different columns"))
    }

    // ---- definition ----

    private fun parse(vararg sources: String) = ReportDefinitions.parse(
        "r", 1, mapOf("dataSources" to sources.map { ObjectMapper().readValue(it, Map::class.java) }),
    )

    private val parentSource = """{"name":"orders","type":"namedQuery","query":"orders"}"""

    @Test
    fun definitionParsesAParentLink() {
        val d = parse(parentSource, """{"name":"items","type":"namedQuery","query":"items","parent":{"source":"orders","bind":{"orderId":"id"}}}""")
        assertEquals(ParentLink("orders", mapOf("orderId" to "id")), (d.dataSources[1] as DataSourceDef.NamedQuery).parent)
        val sql = parse(parentSource, """{"name":"items","type":"sql","connection":"c","sql":"select 1","parent":{"source":"orders","bind":{"p":"id"}}}""")
        assertEquals(ParentLink("orders", mapOf("p" to "id")), (sql.dataSources[1] as DataSourceDef.Sql).parent)
    }

    @Test
    fun definitionRejectsBadParentLinks() {
        fun bad(source: String, message: String, first: String = parentSource) {
            val e = assertThrows(IllegalArgumentException::class.java) { parse(first, source) }
            assertTrue(e.message!!.contains(message), "${e.message} should contain $message")
        }
        bad("""{"name":"i","type":"namedQuery","query":"i","parent":{"source":"later","bind":{"a":"id"}}}""", "parent later is not declared before it")
        bad("""{"name":"i","type":"namedQuery","query":"i","parent":{"source":"i","bind":{"a":"id"}}}""", "parent must be an earlier data source")
        bad("""{"name":"i","type":"namedQuery","query":"i","parent":{"source":"orders"}}""", "parent.bind is required")
        bad("""{"name":"i","type":"namedQuery","query":"i","parent":{"source":"orders","bind":{}}}""", "must bind at least one parameter")
        bad("""{"name":"i","type":"namedQuery","query":"i","parent":{"source":"orders","bind":{"a":3}}}""", "must be a column name")
        bad("""{"name":"i","type":"namedQuery","query":"i","parent":{"bind":{"a":"id"}}}""", "parent.source is required")
        bad("""{"name":"i","type":"namedQuery","query":"i","parent":{"source":"orders","bind":{"a":"id"},"x":1}}""", "unknown fields x")
        bad("""{"name":"i","type":"namedQuery","query":"i","parameters":{"a":{"value":1}},"parent":{"source":"orders","bind":{"a":"id"}}}""", "bound by both")
        bad("""{"name":"i","type":"tagHistory","tags":[{"path":"a"}],"range":{"value":{"last":"1h"}},"parent":{"source":"orders","bind":{"a":"id"}}}""", "parent is not allowed for tagHistory")
        // A parent declared after the child is the same as an unknown one.
        val e = assertThrows(IllegalArgumentException::class.java) {
            parse("""{"name":"i","type":"namedQuery","query":"i","parent":{"source":"orders","bind":{"a":"id"}}}""", parentSource)
        }
        assertTrue(e.message!!.contains("not declared before it"))
    }
}
