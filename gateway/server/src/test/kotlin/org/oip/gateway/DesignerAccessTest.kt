package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Review-4 F4 (SEC-020): project design resources are Designer edits. A write needs `designer.login` (granted by the
 * gateway security settings' designer roles, administrator by default) as well as `gateway.write`, on every path that
 * reaches them: the config API, the config batch, project import, and project copy, rename and delete.
 *
 * Doc source: -
 */
class DesignerAccessTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private lateinit var writer: Http
    private val views = "/api/v1/config/designer/project/demo/View"

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        // A configuration role: gateway.write, but not one of the gateway's designer roles.
        gw.security.roles.create("config-editor", setOf(Permissions.GATEWAY_WRITE, Permissions.TAGS_READ), "", "test", "127.0.0.1")
        gw.createUser("cfg", setOf("config-editor"))
        admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
        writer = Http(gw.port()).also { assertEquals(200, it.login("cfg", TEST_PASSWORD).status) }
    }

    @AfterEach fun tearDown() = gw.close()

    private fun view(name: String, version: Long = 0) =
        """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"$name","module":"designer","version":$version},
            "spec":{"path":"$name","document":{"params":{},"root":{"id":"root","kind":"container","layout":"coordinate","children":[]}}}}"""

    private val hooks =
        """{"apiVersion":"oip/v1","kind":"ScriptHooks","metadata":{"name":"script-hooks","module":"designer","version":0},
            "spec":{"hooks":{"go":{"source":"return 1"}}}}"""

    @Test fun aGatewayWriteHolderWithoutADesignerRoleCannotWriteDesignResources() {
        val session = writer.get("/api/v1/auth/session")
        assertFalse((session.json["permissions"] as List<*>).contains(Permissions.DESIGNER_LOGIN))

        assertProblem(writer.post(views, view("overview")), 403, "forbidden")
        assertProblem(writer.put("$views/overview", view("overview")), 403, "forbidden")
        assertProblem(writer.put("/api/v1/config/designer/project/demo/ScriptHooks/script-hooks", hooks), 403, "forbidden")
        val batch = """{"scope":"project/demo","items":[${view("batched")}]}"""
        assertProblem(writer.post("/api/v1/config/batch", batch), 403, "forbidden")
        assertTrue(writer.post(views, view("overview")).body.contains(Permissions.DESIGNER_LOGIN))

        // The administrator holds designer.login; the same writes go through.
        assertEquals(201, admin.post(views, view("overview")).status)
        assertEquals(200, admin.post("/api/v1/config/batch", batch).status)
        assertEquals(404, writer.get("$views/missing").status) // reads stay gateway.read
        assertEquals(200, writer.get("$views/overview").status)
        assertProblem(writer.send("DELETE", "$views/overview"), 403, "forbidden")
        assertProblem(writer.send("PATCH", "$views/overview", """{"spec":{"title":"x"}}""".toByteArray(), "application/merge-patch+json"), 403, "forbidden")

        // Project copy, delete and import reach the same resources.
        assertProblem(writer.post("/api/v1/projects/demo/copy", """{"name":"demo-copy"}"""), 403, "forbidden")
        assertProblem(writer.send("DELETE", "/api/v1/projects/demo"), 403, "forbidden")
        val archive = admin.send("GET", "/api/v1/projects/demo/export")
        assertEquals(200, archive.status)
        val import = writer.postRaw("/api/v1/projects/other/import?policy=overwrite", archive.raw, "application/zip")
        assertProblem(import, 403, "forbidden")
        assertEquals(404, admin.get("/api/v1/config/designer/project/other/View/overview").status)

        // A gateway.write holder still writes non-design configuration.
        gw.allowWidgets()
        assertEquals(201, writer.post("/api/v1/config/org.oip.test/gateway/Widget", widgetDoc("a")).status)
    }

    @Test fun addingTheRoleToTheDesignerRolesGrantsTheWrites() {
        val current = admin.get("/api/v1/config/org.oip.security/gateway/GatewaySecurity/gateway")
        assertEquals(200, current.status, current.body)
        @Suppress("UNCHECKED_CAST") val spec = (current.json["spec"] as Map<String, Any?>).toMutableMap()
        spec["designerRoles"] = listOf(DefaultRoles.ADMINISTRATOR, "config-editor")
        val metadata = current.json["metadata"] as Map<*, *>
        val body = Json.write(mapOf("apiVersion" to "oip/v1", "kind" to "GatewaySecurity", "metadata" to metadata, "spec" to spec))
        val saved = admin.put("/api/v1/config/org.oip.security/gateway/GatewaySecurity/gateway", body)
        assertEquals(200, saved.status, saved.body)
        assertEquals(201, writer.post(views, view("overview")).status)
    }

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertEquals("urn:oip:problem:$slug", r.json["type"])
    }
}
