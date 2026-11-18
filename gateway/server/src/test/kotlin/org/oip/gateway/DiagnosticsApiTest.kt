package org.oip.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.host.ModuleState
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Diagnostics REST returns schema-valid JSON.
 */
class DiagnosticsApiTest {
    @TempDir lateinit var tmp: Path
    private val mapper = ObjectMapper()
    private val schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    @Test fun endpointsMatchSchemasAndReportLiveGateway() {
        tmp.resolve("modules/sample").createDirectories().resolve("module.yaml").writeText(
            "id: sample\nversion: 1.2.3\nname: Sample\nentrypoint: org.oip.gateway.SampleModule\n")
        Gateway(tmp, port = 0).use { g ->
            g.store.registry.register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
            g.store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 1)))
            g.start()
            assertEquals(ModuleState.RUNNING, g.host.state("sample"))
            val http = Http(g.port())
            repeat(3) { assertEquals(200, http.get("/api/v1/health").status) }
            g.loginAdmin(http)

            val threads = http.get("/api/v1/diagnostics/threads")
            assertEquals(200, threads.status)
            valid("threads", threads.body)
            @Suppress("UNCHECKED_CAST")
            val threadList = threads.json["threads"] as List<Map<String, Any?>>
            assertTrue(threadList.any { it["name"] == "clock-drift" })
            val name = threadList.first()["name"] as String
            val dump = http.get("/api/v1/diagnostics/threads/dump")
            assertTrue(dump.header("Content-Type")!!.contains("text/plain"))
            assertTrue(dump.body.contains(name))
            assertTrue(dump.body.contains("\tat "))

            val memory = http.get("/api/v1/diagnostics/memory")
            valid("memory", memory.body)
            @Suppress("UNCHECKED_CAST")
            val heap = memory.json["heap"] as Map<String, Any?>
            assertTrue((heap["usedBytes"] as Number).toLong() > 0)

            val modules = http.get("/api/v1/diagnostics/modules")
            valid("modules", modules.body)
            assertTrue(modules.body.contains("\"id\":\"sample\""))
            assertTrue(modules.body.contains("\"state\":\"RUNNING\""))
            assertTrue(modules.body.contains("\"version\":\"1.2.3\""))

            val config = http.get("/api/v1/diagnostics/config")
            valid("config", config.body)
            assertEquals(g.store.list().size, (config.json["resources"] as Number).toInt())
            @Suppress("UNCHECKED_CAST")
            val byModule = config.json["byModule"] as Map<String, Any?>
            assertEquals(1, (byModule["m"] as Number).toInt())
            assertTrue((byModule["org.oip.security"] as Number).toInt() >= 1)
            assertTrue((byModule["org.oip.driver-modbus"] as Number).toInt() >= 1)
            assertTrue((byModule["org.oip.driver-opcua-client"] as Number).toInt() >= 1)
            assertTrue((byModule["org.oip.driver-mqtt"] as Number).toInt() >= 1)
            assertTrue((byModule["org.oip.driver-simulator"] as Number).toInt() >= 1)
            assertEquals(0, (config.json["loadErrors"] as Number).toInt())

            val latency = http.get("/api/v1/diagnostics/latency")
            valid("latency", latency.body)
            @Suppress("UNCHECKED_CAST")
            val total = latency.json["total"] as Map<String, Any?>
            assertTrue((total["count"] as Number).toLong() >= 3)
            val p50 = (total["p50Micros"] as Number).toLong()
            val p99 = (total["p99Micros"] as Number).toLong()
            val max = (total["maxMicros"] as Number).toLong()
            assertTrue(p99 >= p50 && max >= p99)
            @Suppress("UNCHECKED_CAST")
            val routes = latency.json["routes"] as List<Map<String, Any?>>
            assertTrue(routes.any { it["path"] == "/api/v1/health" && (it["count"] as Number).toInt() >= 3 })

            val perf = http.get("/api/v1/diagnostics/performance")
            valid("performance", perf.body)
            @Suppress("UNCHECKED_CAST")
            val disk = perf.json["disk"] as Map<String, Any?>
            assertTrue((disk["totalBytes"] as Number).toLong() > 0)
            @Suppress("UNCHECKED_CAST")
            val drift = perf.json["clockDrift"] as Map<String, Any?>
            assertEquals(1000, (drift["intervalMs"] as Number).toInt())
            assertTrue((drift["drift"] as Number).toDouble().isFinite())
            assertTrue((drift["lateMillis"] as Number).toDouble().isFinite())
            assertTrue((drift["samples"] as Number).toLong() >= 0)
            @Suppress("UNCHECKED_CAST")
            val history = perf.json["samples"] as List<Map<String, Any?>>
            assertTrue((history.first()["clockDrift"] as Number).toDouble().isFinite())

            val tasks = http.get("/api/v1/diagnostics/tasks")
            valid("tasks", tasks.body)
            assertTrue(tasks.body.contains("\"name\":\"diagnostics-sampler\""))
            assertTrue(tasks.body.contains("\"rateMs\":5000"))
            @Suppress("UNCHECKED_CAST")
            val sampler = (tasks.json["tasks"] as List<Map<String, Any?>>).first { it["name"] == "diagnostics-sampler" }
            assertTrue((sampler["runs"] as Number).toLong() >= 1)
        }
    }

    @Test fun scriptsEndpointCountsRunsTimingAndLogLines() {
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            assertTrue(http.get("/api/v1/diagnostics/scripts").json["scripts"] is List<*>)
            repeat(2) { assertTrue(g.scripts.run("print('hello from diag')", "diag-demo").ok) }
            assertFalse(g.scripts.run("raise ValueError('boom')", "diag-demo").ok)
            val res = http.get("/api/v1/diagnostics/scripts")
            assertEquals(200, res.status)
            valid("scripts", res.body)
            @Suppress("UNCHECKED_CAST")
            val row = (res.json["scripts"] as List<Map<String, Any?>>).first { it["name"] == "diag-demo" }
            assertEquals(3, (row["executions"] as Number).toInt())
            assertEquals(1, (row["failures"] as Number).toInt())
            assertEquals(0, (row["running"] as Number).toInt())
            assertTrue((row["maxMillis"] as Number).toLong() >= (row["lastMillis"] as Number).toLong())
            assertTrue((row["recentLog"] as List<*>).any { it.toString().contains("hello from diag") })
            assertNotNull(row["lastError"])
        }
    }

    @Test fun metricsEndpointServesNamedMetricsWithHistoryAndDashboardsAreConfigResources() {
        Gateway(tmp, port = 0).use { g ->
            g.start()
            g.diagnostics.recordSample()
            val http = Http(g.port())
            g.loginAdmin(http)
            val res = http.get("/api/v1/diagnostics/metrics")
            assertEquals(200, res.status)
            valid("metrics", res.body)
            @Suppress("UNCHECKED_CAST")
            val byId = (res.json["metrics"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
            for (id in listOf("cpu.system", "heap.used", "disk.free", "tags.changesPerSecond", "sessions.web", "sessions.hmi", "history.queueDepth")) {
                assertTrue(byId.containsKey(id)) { "missing metric $id in ${byId.keys}" }
            }
            assertTrue((byId.getValue("heap.used")["value"] as Number).toDouble() > 0)
            assertTrue((byId.getValue("heap.used")["values"] as List<*>).size >= 2) { "start and the extra sample both landed in history" }
            assertEquals((res.json["times"] as List<*>).size, (byId.getValue("heap.used")["values"] as List<*>).size)

            // A dashboard is the MetricsDashboard config resource.
            fun dash(tiles: String) =
                """{"apiVersion":"oip/v1","kind":"MetricsDashboard","metadata":{"name":"plant","module":"org.oip.gateway"},"spec":{"title":"Plant","tiles":$tiles}}"""
            val path = "/api/v1/config/org.oip.gateway/gateway/MetricsDashboard/plant"
            val ok = http.put(path, dash("""[{"id":"cpu","type":"gauge","metric":"cpu.system","title":"CPU"},{"id":"heap","type":"series","metric":"heap.used"}]"""))
            assertEquals(201, ok.status, ok.body)
            assertEquals(2, ((http.get(path).json["spec"] as Map<*, *>)["tiles"] as List<*>).size)
            val badType = http.put(path, dash("""[{"id":"x","type":"pie","metric":"cpu.system"}]""").replace("\"name\":\"plant\"", "\"name\":\"plant\",\"version\":0"))
            assertEquals(422, badType.status, badType.body)
            val dupe = http.put(path, dash("""[{"id":"a","type":"gauge","metric":"cpu.system"},{"id":"a","type":"series","metric":"heap.used"}]""").replace("\"name\":\"plant\"", "\"name\":\"plant\",\"version\":1"))
            assertEquals(422, dupe.status, dupe.body)
        }
    }

    @Test fun counterRateAndGapsInMetricsRegistry() {
        val m = MetricsRegistry(capacity = 3)
        var counter = 0L
        var now = 0L
        m.register("c", "Counter", "/s", read = m.rate({ counter }, { now }))
        m.register("n", "Nothing", "x") { null }
        m.sample(1); counter = 50; now = 2_000_000_000L; m.sample(2)
        counter = 50; now = 3_000_000_000L; m.sample(3); m.sample(4)
        @Suppress("UNCHECKED_CAST")
        val c = (m.snapshot(5000)["metrics"] as List<Map<String, Any?>>).first { it["id"] == "c" }
        assertEquals(3, (c["values"] as List<*>).size, "history is bounded by capacity")
        assertEquals(listOf(25.0, 0.0), (c["values"] as List<*>).take(2).map { (it as Double) })
        @Suppress("UNCHECKED_CAST")
        val n = (m.snapshot(5000)["metrics"] as List<Map<String, Any?>>).first { it["id"] == "n" }
        assertNull(n["value"])
    }

    private fun valid(name: String, body: String) {
        val schema = schemas.getSchema(mapper.readTree(javaClass.getResource("/diagnostics/$name.json")!!.readText()))
        val errors = schema.validate(mapper.readTree(body))
        assertTrue(errors.isEmpty()) { "$name: " + errors.joinToString { it.message } }
    }
}
