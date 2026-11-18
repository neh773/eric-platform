package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.AuditQuery
import org.oip.security.DefaultRoles
import java.nio.file.Path

/**
 * Review-1 F11 (GAT-033): the active deployment mode is read and set through `/api/v1/deployment-mode`.
 * Reading needs gateway.read, setting needs gateway.write, and a change writes one audit record.
 */
class DeploymentModeApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http
    private val widget = ResourceId("org.oip.test", Scope.GATEWAY, "Widget", "w")

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.allowWidgets()
        gw.store.put(Scope.GATEWAY, Resource("Widget", Metadata("w", "org.oip.test"), mapOf("size" to 1)))
        gw.store.put(Scope.mode("prod"), Resource("Widget", Metadata("w", "org.oip.test"), mapOf("size" to 5)))
        gw.store.put(Scope.mode("dev"), Resource("Widget", Metadata("w", "org.oip.test"), mapOf("size" to 2)))
        http = Http(gw.port())
        gw.loginAdmin(http)
    }
    @AfterEach fun tearDown() = gw.close()

    private fun size() = gw.store.resolve(widget)!!.spec["size"]
    private fun auditTotal() = gw.security.audit.query(AuditQuery(limit = 1)).total

    @Test fun readSwitchAndClearTheActiveMode() {
        val initial = http.get("/api/v1/deployment-mode")
        assertEquals(200, initial.status, initial.body)
        assertTrue(initial.json.containsKey("mode"))
        assertNull(initial.json["mode"])
        assertEquals(listOf("dev", "prod"), initial.json["modes"])
        assertEquals(1, size())

        val before = auditTotal()
        val set = http.put("/api/v1/deployment-mode", """{"mode":"prod"}""")
        assertEquals(200, set.status, set.body)
        assertEquals("prod", set.json["mode"])
        assertEquals("prod", gw.store.activeMode())
        // The switch applies to the next effective-config read, without a restart (DIFFERENCES 210).
        assertEquals(5, size())
        assertEquals(before + 1, auditTotal(), "one audit record per switch")
        val row = gw.security.audit.query(AuditQuery(limit = 1)).records.single()
        assertEquals("admin", row.actor)
        assertTrue(row.target.contains("DeploymentMode/active"), row.target)
        assertEquals("prod", http.get("/api/v1/deployment-mode").json["mode"])

        val cleared = http.put("/api/v1/deployment-mode", """{"mode":null}""")
        assertEquals(200, cleared.status, cleared.body)
        assertNull(cleared.json["mode"])
        assertNull(gw.store.activeMode())
        assertEquals(1, size())
    }

    @Test fun invalidBodiesAreRejectedAndChangeNothing() {
        http.put("/api/v1/deployment-mode", """{"mode":"dev"}""")
        val before = auditTotal()
        assertEquals(422, http.put("/api/v1/deployment-mode", """{"mode":"Bad Mode"}""").status)
        assertEquals(422, http.put("/api/v1/deployment-mode", """{"other":1}""").status)
        assertEquals(422, http.put("/api/v1/deployment-mode", """{"mode":7}""").status)
        assertEquals(400, http.put("/api/v1/deployment-mode", "not json").status)
        assertEquals("dev", gw.store.activeMode())
        assertEquals(before, auditTotal())
    }

    @Test fun activeModeSurvivesRestart() {
        assertEquals(200, http.put("/api/v1/deployment-mode", """{"mode":"dev"}""").status)
        gw.close()
        gw = Gateway(tmp, port = 0).start(); http = Http(gw.port()); gw.loginAdmin(http)
        assertEquals("dev", http.get("/api/v1/deployment-mode").json["mode"])
    }

    @Test fun operatorReadsButCannotSetAndViewerCannotRead() {
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        val op = Http(gw.port()).also { assertEquals(200, it.login("op", TEST_PASSWORD).status) }
        assertEquals(200, op.get("/api/v1/deployment-mode").status)
        assertEquals(403, op.put("/api/v1/deployment-mode", """{"mode":"prod"}""").status)
        assertNull(gw.store.activeMode())
        val viewer = Http(gw.port()).also { assertEquals(200, it.login("view", TEST_PASSWORD).status) }
        assertEquals(403, viewer.get("/api/v1/deployment-mode").status)
        assertEquals(401, Http(gw.port()).get("/api/v1/deployment-mode").status)
    }
}
