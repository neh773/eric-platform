package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText

/**
 * The report workspace's gateway side (FEATURES REP-013, REP-028, REP-034): the component catalog, and the preview of a
 * draft definition that is not saved yet, through the same engine and renderer a saved report uses.
 */
class ReportWorkspaceApiTest {
    @TempDir lateinit var tmp: Path

    private val clock = MutableClock(Instant.parse("2026-06-10T09:30:00Z"))

    private fun ok(r: Http.Res, status: Int = 200): Http.Res = r.also { assertEquals(status, it.status, it.body) }

    private val draft = """
        {"spec": {
          "title": "Draft",
          "parameters": [
            {"name": "day", "type": "timestamp", "expression": "startOfDay(now) - 1d"},
            {"name": "line", "type": "string", "default": "L1"}
          ],
          "dataSources": [{"name": "rows", "type": "sql", "connection": "plant", "sql": "SELECT :line AS line, :day AS day",
                           "parameters": {"line": {"param": "line"}, "day": {"param": "day"}}}],
          "layout": {"layoutVersion": 1, "page": {"size": "Legal", "margins": 60},
                     "components": [{"type": "text", "text": "Line @line@ since @day@"}, {"type": "table", "dataset": "rows"}]}
        }}
    """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    @Test
    fun catalogAndDraftPreviewRunThroughTheRealEngine() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(tmp, port = 0, clock = clock).use { gw ->
            gw.start()
            gw.createUser("view", setOf(DefaultRoles.VIEWER))
            val http = Http(gw.port())
            gw.loginAdmin(http)
            ok(http.post("/api/v1/db/connections", """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite::memory:","username":"","description":"plant"}"""), 201)

            val catalog = ok(http.get("/api/v1/reports/components")).json
            assertEquals(listOf("text", "table", "bar", "timeSeries", "pie", "xy", "image"), (catalog["components"] as List<Map<String, Any?>>).map { it["type"] })
            assertTrue((catalog["report"] as List<Map<String, Any?>>).any { it["name"] == "page.margins" })

            // The draft is not saved, yet it runs: the day parameter comes from its expression on the gateway clock.
            val result = ok(http.post("/api/v1/reports/preview", draft)).json
            val params = result["parameters"] as List<Map<String, Any?>>
            assertEquals(Instant.parse("2026-06-09T00:00:00Z").toEpochMilli(), (params.first { it["name"] == "day" }["value"] as Number).toLong())
            val rows = (result["datasets"] as List<Map<String, Any?>>).single()["rows"] as List<List<Any?>>
            assertEquals("L1", rows.single()[0])
            assertEquals(404, http.get("/api/v1/reports/preview/runs").status) // nothing was stored or recorded
            assertEquals(emptyList<Any>(), ok(http.get("/api/v1/config/reporting/gateway/Report")).json["items"])

            // A supplied value wins over the expression and the default.
            val supplied = ok(http.post("/api/v1/reports/preview", draft.replace("{\"spec\"", "{\"parameters\": {\"line\": \"L9\"}, \"spec\"")))
            assertTrue(supplied.body.contains("L9"), supplied.body)

            // The PDF is rendered by the gateway's renderer from the same draft (page setup is checked in PageSetupTest).
            val pdf = ok(http.post("/api/v1/reports/preview/pdf", draft))
            assertEquals("%PDF-", String(pdf.raw.copyOf(5)))

            // Drafts that would not save, or would not render, are 422 and run nothing.
            assertEquals(422, http.post("/api/v1/reports/preview", draft.replace("startOfDay(now) - 1d", "later + 1")).status)
            assertEquals(422, http.post("/api/v1/reports/preview/pdf", draft.replace("\"margins\": 60", "\"margins\": 3")).status)
            assertEquals(400, http.post("/api/v1/reports/preview", "{\"spec\": 3}").status)
            assertEquals(400, http.post("/api/v1/reports/preview", "{\"spec\": {}, \"extra\": 1}").status)

            // A viewer can read the catalog but may not run a draft (a draft can name any SQL).
            val viewer = Http(gw.port())
            assertEquals(200, viewer.login("view", TEST_PASSWORD).status)
            assertEquals(200, viewer.get("/api/v1/reports/components").status)
            assertEquals(403, viewer.post("/api/v1/reports/preview", draft).status)
            assertEquals(403, viewer.post("/api/v1/reports/preview/pdf", draft).status)

            val doc = ok(http.get("/api/docs")).json["paths"] as Map<String, Any?>
            for (p in listOf("/api/v1/reports/components", "/api/v1/reports/preview", "/api/v1/reports/preview/pdf")) assertTrue(doc.containsKey(p), p)
            assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/reports/preview"))
            assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/reports/preview/pdf"))
            assertEquals("tags.read", GatewayAuth.explicitRule("GET", "/api/v1/reports/components"))
        }
    }
}
