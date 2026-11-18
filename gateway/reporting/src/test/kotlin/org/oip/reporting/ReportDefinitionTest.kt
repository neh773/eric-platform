package org.oip.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.config.ValidationException
import java.nio.file.Path

class ReportDefinitionTest {
    @TempDir lateinit var tmp: Path

    private val valid: Map<String, Any?> = mapOf(
        "title" to "Line 1 daily",
        "parameters" to listOf(
            mapOf("name" to "range", "type" to "timeRange", "default" to mapOf("last" to "24h")),
            mapOf("name" to "line", "type" to "string", "default" to "L1", "required" to true),
        ),
        "dataSources" to listOf(
            mapOf("name" to "production", "type" to "namedQuery", "query" to "line-production",
                "parameters" to mapOf(
                    "line" to mapOf("param" to "line"),
                    "start" to mapOf("param" to "range", "field" to "from"),
                    "limit" to mapOf("value" to 100),
                ),
                "columns" to mapOf("count" to mapOf("type" to "number", "unit" to "pcs"))),
            mapOf("name" to "temps", "type" to "tagHistory", "mode" to "raw",
                "tags" to listOf(mapOf("path" to "[default]Line1/Temp", "unit" to "degC")), "range" to mapOf("param" to "range")),
            mapOf("name" to "hourly", "type" to "tagHistory", "mode" to "aggregate", "aggregate" to "Average",
                "intervalMillis" to 3_600_000, "tags" to listOf(mapOf("path" to "Line1/Temp")), "range" to mapOf("value" to mapOf("last" to "8h"))),
        ),
        "schedule" to mapOf("cron" to "0 6 * * *", "timezone" to "Europe/London", "parameters" to mapOf("line" to "L2")),
        "layout" to mapOf("pages" to listOf(mapOf("blocks" to listOf(mapOf("table" to "production")))), "anything" to mapOf("x" to 1)),
    )

    private fun with(change: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> = LinkedHashMap(valid).also(change)

    @Suppress("UNCHECKED_CAST")
    private fun source(i: Int, change: (MutableMap<String, Any?>) -> Unit) = with { spec ->
        val list = (spec["dataSources"] as List<Map<String, Any?>>).toMutableList()
        list[i] = LinkedHashMap(list[i]).also(change)
        spec["dataSources"] = list
    }

    @Test
    fun validDefinitionSavesAndParses() {
        FileConfigStore(tmp.resolve("config"), SchemaRegistry()).use { store ->
            registerReportSchemas(store.registry)
            val stored = store.putReport("line1-daily", valid)
            val def = ReportDefinitions.parse("line1-daily", stored.metadata.version, stored.spec)
            assertEquals("Line 1 daily", def.title)
            assertEquals(listOf("production", "temps", "hourly"), def.dataSources.map { it.name })
            val nq = def.dataSources[0] as DataSourceDef.NamedQuery
            assertEquals(Binding.Param("range", "from"), nq.parameters["start"])
            assertEquals(Binding.Literal(100), nq.parameters["limit"])
            assertEquals(ColumnOverride(ColumnType.NUMBER, "pcs"), nq.columns["count"])
            assertEquals("Europe/London", def.schedule!!.timezone)
            assertTrue(def.schedule!!.actions.isEmpty())
            val withActions = with {
                it["schedule"] = mapOf(
                    "cron" to "0 6 * * *",
                    "actions" to listOf(
                        mapOf("type" to "save"),
                        mapOf("type" to "email", "to" to "ops@plant.example", "from" to "reports@plant.example", "host" to "mail.plant.example", "password" to "secret://mail"),
                        mapOf("type" to "print", "printer" to "line", "copies" to 2),
                        mapOf("type" to "ftp", "host" to "files.plant.example", "username" to "reports", "path" to "out/daily.pdf"),
                    ),
                )
            }
            store.putReport("acted", withActions)
            val acted = ReportDefinitions.parse("acted", 0, withActions).schedule!!
            assertTrue(acted.actions[0] is ScheduleAction.Save)
            assertEquals("ops@plant.example", (acted.actions[1] as ScheduleAction.Email).to)
            assertEquals(2, (acted.actions[2] as ScheduleAction.Print).copies)
            assertEquals("out/daily.pdf", (acted.actions[3] as ScheduleAction.Ftp).path)
            // Layout is opaque and comes back verbatim.
            assertEquals(valid["layout"], def.layout)
            assertNull(ReportDefinitions.parse("x", 0, mapOf("parameters" to valid["parameters"], "dataSources" to listOf((valid["dataSources"] as List<*>)[1]))).schedule)
        }
    }

    @Test
    fun invalidDefinitionsAreRejectedOnSave() {
        val cases = mapOf(
            "unknown parameter" to source(0) { it["parameters"] = mapOf("line" to mapOf("param" to "nope")) },
            "timeRange without field" to source(0) { it["parameters"] = mapOf("start" to mapOf("param" to "range")) },
            "field on a string" to source(0) { it["parameters"] = mapOf("x" to mapOf("param" to "line", "field" to "from")) },
            "param and value" to source(0) { it["parameters"] = mapOf("x" to mapOf("param" to "line", "value" to 1)) },
            "range binds a string" to source(1) { it["range"] = mapOf("param" to "line") },
            "bad literal range" to source(1) { it["range"] = mapOf("value" to mapOf("last" to "forever")) },
            "bad tag path" to source(1) { it["tags"] = listOf(mapOf("path" to "[default]")) },
            "no tags" to source(1) { it["tags"] = emptyList<Any>() },
            "aggregate without interval" to source(2) { it.remove("intervalMillis") },
            "unknown aggregate" to source(2) { it["aggregate"] = "Median" },
            "raw with aggregate" to source(1) { it["aggregate"] = "Average" },
            "query on tagHistory" to source(1) { it["query"] = "q" },
            "duplicate source" to source(2) { it["name"] = "temps" },
            "unknown field" to source(0) { it["sql"] = "SELECT 1" },
            "no sources" to with { it["dataSources"] = emptyList<Any>() },
            "duplicate parameter" to with { it["parameters"] = listOf(mapOf("name" to "a", "type" to "string"), mapOf("name" to "a", "type" to "number")) },
            "default of wrong type" to with { it["parameters"] = listOf(mapOf("name" to "range", "type" to "timeRange", "default" to "24h"), mapOf("name" to "line", "type" to "string")) },
            "unknown type" to with { it["parameters"] = listOf(mapOf("name" to "a", "type" to "date")) },
            "bad cron" to with { it["schedule"] = mapOf("cron" to "0 25 * * *") },
            "bad timezone" to with { it["schedule"] = mapOf("cron" to "0 6 * * *", "timezone" to "Mars/Olympus") },
            "schedule parameter unknown" to with { it["schedule"] = mapOf("cron" to "0 6 * * *", "parameters" to mapOf("nope" to 1)) },
            "schedule parameter wrong type" to with { it["schedule"] = mapOf("cron" to "0 6 * * *", "parameters" to mapOf("line" to 5)) },
            "layout not an object" to with { it["layout"] = listOf(1) },
        )
        FileConfigStore(tmp.resolve("config"), SchemaRegistry()).use { store ->
            registerReportSchemas(store.registry)
            for ((what, spec) in cases) {
                try {
                    store.putReport("bad", spec)
                    fail<Unit>("accepted: $what")
                } catch (e: ValidationException) {
                    assertTrue(e.errors.isNotEmpty(), what)
                }
            }
            assertNull(store.get(report("bad", valid).id()))
        }
    }
}
