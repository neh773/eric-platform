package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The designer script console runs on the gateway and needs designer.login as well as gateway.write. */
class DesignerConsoleTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private lateinit var writer: Http

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.security.roles.create("config-editor", setOf(Permissions.GATEWAY_WRITE, Permissions.TAGS_READ), "", "test", "127.0.0.1")
        gw.createUser("cfg", setOf("config-editor"))
        admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
        writer = Http(gw.port()).also { assertEquals(200, it.login("cfg", TEST_PASSWORD).status) }
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun aDesignerRunsAScriptAndAWriterWithoutTheRoleCannot() {
        assertEquals("gateway.write+designer.login", GatewayAuth.explicitRule("POST", "/api/v1/designer/console"))
        assertProblem(writer.post("/api/v1/designer/console", """{"source":"_result = 1\n"}"""), 403, "forbidden")
        val ran = admin.post("/api/v1/designer/console", """{"source":"print('console-ok')\n_result = 6 + 1\n"}""")
        assertEquals(200, ran.status, ran.body)
        assertEquals(true, ran.json["ok"])
        assertEquals(7, (ran.json["value"] as Number).toInt())
        assertTrue((ran.json["output"] as String).contains("console-ok"), ran.json["output"].toString())
        assertEquals("designer", ran.json["scope"])
        val missing = admin.post("/api/v1/designer/console", """{"source":"system.screen.navigate(page='/x')\n"}""")
        assertEquals(200, missing.status, missing.body)
        assertEquals(false, missing.json["ok"])
        assertTrue((missing.json["error"] as String).contains("screen"), missing.json["error"].toString())
        assertProblem(admin.post("/api/v1/designer/console", """{"source":"  "}"""), 400, "bad-request")
    }

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertEquals("urn:oip:problem:$slug", r.json["type"])
    }
}
