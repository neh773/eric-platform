package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText

/**
 * GAT-028: active Designer sessions are registered, kept alive by heartbeat, dropped when the heartbeat stops, and
 * listed with the Designer launch link and the launcher download.
 * Doc source: FEATURES GAT-028.
 */
class DesignerSessionsTest {
    @TempDir lateinit var tmp: Path
    private val clock = MutableClock()
    private lateinit var gw: Gateway
    private lateinit var admin: Http

    @BeforeEach fun setUp() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0, clock = clock).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("admin2", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        admin = Http(gw.port())
        assertEquals(200, admin.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() = gw.close()

    @Suppress("UNCHECKED_CAST")
    private fun sessions(http: Http = admin): List<Map<String, Any?>> {
        val response = http.get("/api/v1/designer/sessions")
        assertEquals(200, response.status, response.body)
        return response.json["sessions"] as List<Map<String, Any?>>
    }

    @Test fun anOpenedSessionIsListedWithItsUserAndProject() {
        assertEquals(201, admin.post("/api/v1/projects", """{"name":"plant"}""").status)
        val opened = admin.post("/api/v1/designer/sessions", """{"project":"plant"}""")
        assertEquals(201, opened.status, opened.body)
        assertEquals(DesignerSessions.HEARTBEAT_SECONDS, (opened.json["heartbeatSeconds"] as Number).toInt())
        val listed = sessions().single()
        assertEquals(opened.json["id"], listed["id"])
        assertEquals("admin", listed["user"])
        assertEquals("plant", listed["project"])

        val doc = admin.get("/api/v1/designer/sessions").json
        @Suppress("UNCHECKED_CAST")
        assertEquals("/studio", (doc["launch"] as Map<String, Any?>)["href"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("/api/v1/designer/launcher", (doc["download"] as Map<String, Any?>)["href"])
    }

    @Test fun aSessionWithoutHeartbeatDropsOutAndAHeartbeatKeepsItAlive() {
        val id = admin.post("/api/v1/designer/sessions", "{}").json["id"] as String
        clock.advance(Duration.ofSeconds(30))
        assertEquals(204, admin.put("/api/v1/designer/sessions/$id", "{}").status)
        clock.advance(Duration.ofSeconds(30))
        assertEquals(1, sessions().size, "the heartbeat 30 s ago keeps it listed")
        clock.advance(Duration.ofSeconds(DesignerSessions.TTL_SECONDS + 1))
        assertEquals(0, sessions().size)
        assertEquals(404, admin.put("/api/v1/designer/sessions/$id", "{}").status)
    }

    @Test fun closeEndsOnlyTheCallersOwnSession() {
        val id = admin.post("/api/v1/designer/sessions", "{}").json["id"] as String
        val other = Http(gw.port())
        assertEquals(200, other.login("admin2", TEST_PASSWORD).status)
        assertEquals(404, other.put("/api/v1/designer/sessions/$id", "{}").status)
        assertEquals(404, other.delete("/api/v1/designer/sessions/$id").status)
        assertEquals(1, sessions(other).size, "another user can list but not end it")
        assertEquals(204, admin.delete("/api/v1/designer/sessions/$id").status)
        assertEquals(0, sessions().size)
    }

    @Test fun rolesAndInputAreChecked() {
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/designer/sessions"))
        assertEquals("gateway.write+designer.login", GatewayAuth.explicitRule("POST", "/api/v1/designer/sessions"))
        assertEquals("gateway.write+designer.login", GatewayAuth.explicitRule("DELETE", "/api/v1/designer/sessions/x"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("GET", "/api/v1/designer/launcher"))
        assertEquals(401, Http(gw.port()).get("/api/v1/designer/sessions").status)
        val op = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(200, op.get("/api/v1/designer/sessions").status)
        assertEquals(403, op.post("/api/v1/designer/sessions", "{}").status)
        assertEquals(403, op.get("/api/v1/designer/launcher").status)
        val view = Http(gw.port())
        assertEquals(200, view.login("view", TEST_PASSWORD).status)
        assertEquals(403, view.get("/api/v1/designer/sessions").status)
        assertEquals(422, admin.post("/api/v1/designer/sessions", """{"project":"Bad Name"}""").status)
        assertEquals(422, admin.post("/api/v1/designer/sessions", """{"extra":1}""").status)
        assertEquals(0, sessions().size)
    }

    @Test fun oneUserCannotFillTheList() {
        repeat(DesignerSessions.MAX_PER_USER + 5) { assertEquals(201, admin.post("/api/v1/designer/sessions", "{}").status) }
        assertEquals(DesignerSessions.MAX_PER_USER, sessions().size)
    }

    @Test fun launcherDownloadIsAShortcutToTheStudio() {
        val response = admin.get("/api/v1/designer/launcher")
        assertEquals(200, response.status)
        assertTrue(response.header("Content-Disposition")!!.contains("control-canvas.url"))
        assertTrue(response.body.startsWith("[InternetShortcut]"))
        assertTrue(response.body.contains("URL=http://127.0.0.1:${gw.port()}/studio") || response.body.contains("/studio"), response.body)
    }
}
