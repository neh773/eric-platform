package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagType
import org.oip.security.Permissions
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * M5.3 reporting over a live [Gateway]: a `reporting/Report` config resource with a named-query source (SQLite
 * connection from #51) and tag-history sources (raw and aggregate), run through `POST /api/v1/reports/{name}/run`,
 * then run history and status. OPC UA is off so the test does not need port 4840.
 */
class ReportApiTest {
    @TempDir lateinit var tmp: Path

    private val clock = MutableClock(Instant.parse("2026-06-01T00:00:00Z"))

    private fun reportDoc(name: String, spec: String, version: Long = 0) =
        """{"apiVersion":"oip/v1","kind":"Report","metadata":{"name":"$name","module":"reporting","version":$version},"spec":$spec}"""

    private val spec = """
        {
          "title": "Line 1",
          "parameters": [
            {"name": "range", "type": "timeRange", "default": {"last": "1h"}},
            {"name": "line", "type": "string", "default": "L1"},
            {"name": "min", "type": "number", "required": true}
          ],
          "dataSources": [
            {"name": "production", "type": "namedQuery", "query": "by-line",
             "parameters": {"line": {"param": "line"}, "min": {"param": "min"}, "start": {"param": "range", "field": "from"}},
             "columns": {"count": {"unit": "pcs"}}},
            {"name": "speed", "type": "tagHistory", "mode": "raw",
             "tags": [{"path": "Plant/Speed", "label": "Speed", "unit": "rpm"}, {"path": "Secret/Recipe"}],
             "range": {"param": "range"}},
            {"name": "speedMax", "type": "tagHistory", "mode": "aggregate", "aggregate": "Maximum", "intervalMillis": 3600000,
             "tags": [{"path": "Plant/Speed"}], "range": {"param": "range"}}
          ],
          "layout": {"blocks": [{"type": "table", "dataset": "production"}]}
        }
    """.trimIndent()

    private fun Gateway.memoryTag(name: String, path: String, value: Double, readPermissions: Set<String>? = null) {
        if (readPermissions == null) {
            store.put(Scope.GATEWAY, Resource("Tag", Metadata(name, "tags"),
                mapOf("path" to path, "type" to "memory", "dataType" to "Double", "value" to value)))
        } else {
            // The Tag resource schema does not take readPermissions yet; configure the engine directly, as GatewayNetworkTest does.
            tags.configure(TagConfig(path = path, type = TagType.MEMORY, dataType = DataType.DOUBLE, value = value, valueSet = true,
                readPermissions = readPermissions))
        }
        store.put(Scope.GATEWAY, Resource("HistorySettings", Metadata(name, "historian"), mapOf("tagPath" to path)))
    }

    private fun ok(r: Http.Res, status: Int = 200): Http.Res = r.also { assertEquals(status, it.status, it.body) }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun reportRunsNamedQueryAndHistorySourcesWithPermissions() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(tmp, port = 0, clock = clock).use { gw ->
            gw.memoryTag("speed", "Plant/Speed", 1.0)
            gw.memoryTag("recipe", "Secret/Recipe", 7.0, readPermissions = setOf("supervisor"))
            gw.start()
            val http = Http(gw.port())
            gw.loginAdmin(http)

            // Named query from gateway/db.
            ok(http.post("/api/v1/db/connections", """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite::memory:","username":"","description":"plant"}"""), 201)
            fun query(body: String) = ok(http.post("/api/v1/db/queries", body), 201)
            query("""{"name":"setup","connection":"plant","queryType":"update","sql":"CREATE TABLE production (line TEXT, count INTEGER, ts INTEGER)"}""")
            ok(http.post("/api/v1/db/queries/setup/execute", """{"parameters":{}}"""))
            query("""{"name":"add","connection":"plant","queryType":"update","sql":"INSERT INTO production VALUES (:line, :count, :ts)","parameters":[{"name":"line","dataType":"string"},{"name":"count","dataType":"integer"},{"name":"ts","dataType":"long"}]}""")
            val now = clock.millis()
            for ((line, count, ts) in listOf(Triple("L1", 120, now - 1000), Triple("L1", 5, now - 2000), Triple("L2", 300, now - 1000), Triple("L1", 99, now - 7_200_000))) {
                ok(http.post("/api/v1/db/queries/add/execute", """{"parameters":{"line":"$line","count":$count,"ts":$ts}}"""))
            }
            query("""{"name":"by-line","connection":"plant","queryType":"query","sql":"SELECT line, count FROM production WHERE line = :line AND count >= :min AND ts >= :start ORDER BY count DESC","parameters":[{"name":"line","dataType":"string"},{"name":"min","dataType":"integer"},{"name":"start","dataType":"long"}]}""")

            // History rows.
            for (v in listOf(2.0, 3.0)) {
                clock.advance(Duration.ofSeconds(10))
                gw.tags.write("[default]Plant/Speed", v)
            }
            clock.advance(Duration.ofSeconds(10))

            // Definition through the config resource API; validated on save.
            ok(http.put("/api/v1/config/reporting/gateway/Report/line1", reportDoc("line1", spec)), 201)
            val bad = http.put("/api/v1/config/reporting/gateway/Report/bad",
                reportDoc("bad", spec.replace("\"field\": \"from\"", "\"field\": \"middle\"")))
            assertEquals(422, bad.status, bad.body)
            val badRef = http.put("/api/v1/config/reporting/gateway/Report/bad",
                reportDoc("bad", spec.replace("{\"param\": \"line\"}", "{\"param\": \"nope\"}")))
            assertEquals(422, badRef.status, badRef.body)
            assertTrue(badRef.body.contains("unknown parameter nope"), badRef.body)

            // Per-tag read check: Secret/Recipe lists readPermissions [supervisor]; admin (role administrator) may not read it.

            assertEquals(400, http.post("/api/v1/reports/line1/run", "{}").status) // min is required
            assertEquals(400, http.post("/api/v1/reports/line1/run", """{"parameters":{"min":"x"}}""").status)
            assertEquals(404, http.post("/api/v1/reports/nope/run", "{}").status)

            val run = ok(http.post("/api/v1/reports/line1/run", """{"parameters":{"min":10}}"""))
            val body = run.json
            assertEquals(1, body["contractVersion"])
            assertEquals(listOf("contractVersion", "report", "parameters", "generatedAt", "datasets", "run", "layout"), body.keys.toList())
            assertEquals("line1", (body["report"] as Map<*, *>)["name"])
            assertEquals(mapOf("blocks" to listOf(mapOf("type" to "table", "dataset" to "production"))), body["layout"])
            val datasets = body["datasets"] as List<Map<String, Any?>>
            assertEquals(listOf("production", "speed", "speedMax"), datasets.map { it["name"] })

            val production = datasets[0]
            assertEquals(listOf(mapOf("name" to "line", "type" to "string"), mapOf("name" to "count", "type" to "number", "unit" to "pcs")), production["columns"])
            assertEquals(listOf(listOf("L1", 120)), production["rows"]) // L2, count < 10, and older than 1h are filtered by the bound parameters

            val speed = datasets[1]
            assertEquals(listOf("tagPath", "timestamp", "value", "quality"), (speed["columns"] as List<Map<*, *>>).map { it["name"] })
            val rows = speed["rows"] as List<List<Any?>>
            assertEquals(listOf(1.0, 2.0, 3.0), rows.map { (it[2] as Number).toDouble() })
            assertTrue(rows.all { it[0] == "[default]Plant/Speed" })
            assertEquals(listOf("[default]Secret/Recipe"), speed["excludedTags"])
            assertFalse(run.body.contains(",7.0]"), "an unreadable tag's value leaked")
            val series = (speed["series"] as List<Map<String, Any?>>).single()
            assertEquals("Speed", series["label"])
            assertEquals("rpm", series["unit"])

            val speedMax = datasets[2]
            assertEquals(listOf("tagPath", "start", "end", "value", "rows"), (speedMax["columns"] as List<Map<*, *>>).map { it["name"] })
            val windows = speedMax["rows"] as List<List<Any?>>
            assertEquals(2, windows.size) // [now-1h, now) and the closing 1 ms window [now, now]
            assertEquals(3.0, (windows[0][3] as Number).toDouble())

            val runInfo = body["run"] as Map<String, Any?>
            assertEquals("partial", runInfo["status"])
            assertEquals("admin", runInfo["user"])
            assertEquals("manual", runInfo["trigger"])

            val runs = ok(http.get("/api/v1/reports/line1/runs")).json["items"] as List<Map<String, Any?>>
            assertEquals("partial", runs.single()["status"])
            // Permissions: viewer (tags.read) can run; a role without tags.read cannot; anonymous is 401.
            gw.createUser("view", setOf(DefaultRoles.VIEWER))
            gw.security.roles.create("nothing", setOf(Permissions.GATEWAY_ACCESS), actor = "test")
            gw.createUser("nobody", setOf("nothing"))
            val view = Http(gw.port()).also { ok(it.login("view", TEST_PASSWORD)) }
            val viewed = ok(view.post("/api/v1/reports/line1/run", """{"parameters":{"min":10}}"""))
            assertEquals("view", (viewed.json["run"] as Map<*, *>)["user"])
            val nobody = Http(gw.port()).also { ok(it.login("nobody", TEST_PASSWORD)) }
            assertEquals(403, nobody.post("/api/v1/reports/line1/run", """{"parameters":{"min":10}}""").status)
            assertEquals(403, nobody.get("/api/v1/reports/line1/runs").status)
            assertEquals(401, Http(gw.port()).post("/api/v1/reports/line1/run", "{}").status)

            // A supervisor (tags.read and the listed role) gets the restricted tag.
            gw.security.roles.create("supervisor", setOf(Permissions.GATEWAY_ACCESS, Permissions.TAGS_READ), actor = "test")
            gw.createUser("sup", setOf("supervisor"))
            val sup = Http(gw.port()).also { ok(it.login("sup", TEST_PASSWORD)) }
            val supRun = (ok(sup.post("/api/v1/reports/line1/run", """{"parameters":{"min":10}}""")).json["datasets"] as List<Map<String, Any?>>)[1]
            assertEquals(emptyList<String>(), supRun["excludedTags"])
            assertTrue((supRun["rows"] as List<List<Any?>>).any { it[0] == "[default]Secret/Recipe" && (it[2] as Number).toDouble() == 7.0 })

            // Only unreadable tags: 403.
            ok(http.put("/api/v1/config/reporting/gateway/Report/secret", reportDoc("secret",
                """{"dataSources":[{"name":"s","type":"tagHistory","tags":[{"path":"Secret/Recipe"}],"range":{"value":{"last":"1h"}}}]}""")), 201)
            assertEquals(403, http.post("/api/v1/reports/secret/run", "").status)

            // A data source failure is a 502 and is recorded.
            query("""{"name":"broken","connection":"plant","queryType":"query","sql":"SELECT * FROM missing_table"}""")
            ok(http.put("/api/v1/config/reporting/gateway/Report/broken", reportDoc("broken",
                """{"dataSources":[{"name":"x","type":"namedQuery","query":"broken"}]}""")), 201)
            assertEquals(502, http.post("/api/v1/reports/broken/run", "").status)

            // (Last: an SQL error marks the in-memory connection faulted.)
            val status = ok(http.get("/api/v1/reports/broken/status")).json
            assertEquals("failure", status["lastStatus"])
            assertEquals(1, status["consecutiveFailures"])
            assertTrue((status["lastError"] as String).contains("missing_table"), status["lastError"].toString())
            val all = ok(http.get("/api/v1/reports/status")).json["items"] as List<Map<String, Any?>>
            assertEquals(listOf("broken", "line1", "secret"), all.map { it["report"] })

            val doc = ok(http.get("/api/docs")).json["paths"] as Map<String, Any?>
            for (p in listOf("/api/v1/reports/{name}/run", "/api/v1/reports/{name}/runs", "/api/v1/reports/{name}/status", "/api/v1/reports/status",
                    "/api/v1/reports/{name}/pdf", "/api/v1/reports/{name}/files", "/api/v1/reports/{name}/files/{file}")) {
                assertTrue(doc.containsKey(p), p)
            }
        }
        assertTrue(tmp.resolve("db/report-runs.sqlite").exists())
    }

    /** S-1: the report history check is the engine's per-tag read check, zones included. */
    @Test
    fun reportHistoryCheckAppliesTheTagsZones() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(tmp, port = 0, clock = clock).use { gw ->
            gw.tags.configure(TagConfig(path = "Line/Zoned", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true,
                readAccess = org.oip.tags.TagAccess(listOf("supervisor"), listOf("Floor"))))
            fun caller(zone: String?, roles: Set<String> = setOf("Supervisor")) =
                org.oip.reporting.ReportCaller("sue", roles, setOf(Permissions.TAGS_READ), zone = zone)
            assertTrue(gw.reportTagCheck.canRead(caller("floor"), "[default]Line/Zoned"))
            assertFalse(gw.reportTagCheck.canRead(caller("remote"), "[default]Line/Zoned"), "wrong zone")
            assertFalse(gw.reportTagCheck.canRead(caller(null), "[default]Line/Zoned"), "no zone is the default zone")
            assertFalse(gw.reportTagCheck.canRead(caller("floor", setOf(DefaultRoles.ADMINISTRATOR)), "[default]Line/Zoned"))
            assertTrue(gw.reportTagCheck.canRead(org.oip.reporting.ReportCaller.SYSTEM, "[default]Line/Zoned"))
        }
    }

    /**
     * C-9b over the live gateway: `POST /api/v1/reports/{name}/pdf` renders the caller's run, and a scheduled run
     * reaches the default sink, which archives the PDF for `GET /api/v1/reports/{name}/files[/{file}]`.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun reportRendersPdfAndArchivesScheduledRuns() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(tmp, port = 0, clock = clock).use { gw ->
            gw.memoryTag("speed", "Plant/Speed", 1.0)
            gw.start()
            val http = Http(gw.port())
            gw.loginAdmin(http)
            for (v in listOf(2.0, 3.0)) {
                clock.advance(Duration.ofSeconds(10))
                gw.tags.write("[default]Plant/Speed", v)
            }
            val pdfSpec = """
                {"title": "Speed", "parameters": [{"name": "range", "type": "timeRange", "default": {"last": "1h"}}],
                 "dataSources": [{"name": "speed", "type": "tagHistory", "mode": "raw", "tags": [{"path": "Plant/Speed", "label": "Speed"}], "range": {"param": "range"}}],
                 "schedule": {"cron": "* * * * *"},
                 "layout": {"layoutVersion": 1, "components": [{"type": "table", "dataset": "speed"}, {"type": "timeSeries", "dataset": "speed", "title": "Speed trend"}]}}
            """.trimIndent()
            ok(http.put("/api/v1/config/reporting/gateway/Report/speed", reportDoc("speed", pdfSpec)), 201)

            val pdf = ok(http.post("/api/v1/reports/speed/pdf", "{}"))
            assertEquals("application/pdf", pdf.headers.entries.first { it.key.equals("Content-Type", true) }.value.first().substringBefore(';'))
            assertTrue(String(pdf.raw.copyOf(5)) == "%PDF-", "not a PDF")
            assertEquals("manual", (ok(http.get("/api/v1/reports/speed/runs")).json["items"] as List<Map<String, Any?>>).first()["trigger"])
            assertEquals(404, http.post("/api/v1/reports/nope/pdf", "{}").status)

            // A layout the renderer refuses is a 422, and nothing runs.
            ok(http.put("/api/v1/config/reporting/gateway/Report/badlayout", reportDoc("badlayout",
                pdfSpec.replace("\"layoutVersion\": 1", "\"layoutVersion\": 9").replace("\"* * * * *\"", "\"0 0 1 1 *\""))), 201)
            assertEquals(422, http.post("/api/v1/reports/badlayout/pdf", "{}").status)
            assertEquals(emptyList<Any>(), ok(http.get("/api/v1/reports/badlayout/runs")).json["items"])

            // The scheduler (ticking on the gateway clock) fires the every-minute schedule; the default sink renders it.
            assertEquals(emptyList<Any>(), ok(http.get("/api/v1/reports/speed/files")).json["items"])
            // Keep moving the clock: the first tick that sees the definition sets its next fire from "now".
            val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            var files: List<Map<String, Any?>> = emptyList()
            while (files.isEmpty() && System.nanoTime() < deadline) {
                clock.advance(Duration.ofSeconds(30))
                Thread.sleep(250)
                files = ok(http.get("/api/v1/reports/speed/files")).json["items"] as List<Map<String, Any?>>
            }
            val archived = files.firstOrNull() ?: error("no scheduled PDF was archived; runs: " + http.get("/api/v1/reports/speed/runs").body)
            assertEquals("speed", archived["report"])
            val file = archived["file"] as String
            val download = ok(http.get("/api/v1/reports/speed/files/$file"))
            assertEquals((archived["size"] as Number).toLong(), download.raw.size.toLong())
            assertTrue(String(download.raw.copyOf(5)) == "%PDF-", "not a PDF")
            assertTrue(tmp.resolve("reports/speed/$file").exists())
            assertEquals(404, http.get("/api/v1/reports/speed/files/0-missing.pdf").status)
            assertEquals(404, http.get("/api/v1/reports/speed/files/..%2F..%2Fgateway.yaml").status)
            assertEquals(404, http.get("/api/v1/reports/nope/files").status)

            // Archived PDFs were run as system: gateway.read, not just tags.read. Running to PDF needs tags.read.
            gw.createUser("view", setOf(DefaultRoles.VIEWER)) // tags.read, no gateway.read
            val view = Http(gw.port()).also { ok(it.login("view", TEST_PASSWORD)) }
            ok(view.post("/api/v1/reports/speed/pdf", "{}"))
            assertEquals(403, view.get("/api/v1/reports/speed/files").status)
            assertEquals(403, view.get("/api/v1/reports/speed/files/$file").status)
            gw.createUser("op", setOf(DefaultRoles.OPERATOR)) // gateway.read
            val op = Http(gw.port()).also { ok(it.login("op", TEST_PASSWORD)) }
            ok(op.get("/api/v1/reports/speed/files/$file"))
            assertEquals(401, Http(gw.port()).post("/api/v1/reports/speed/pdf", "{}").status)
        }
    }
}
