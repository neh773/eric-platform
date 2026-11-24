package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * 0.1.1 first run: a gateway with no users offers the "Create the administrator" form. The first POST from this
 * machine creates the administrator and signs in; after that the route is closed. A request from another site's
 * page (a foreign Origin) or through a proxy header is refused, and so is a non-JSON body.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FirstRunSetupTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun firstAdministratorIsCreatedOnceFromThisMachine() {
        val dir = tmp.resolve("gw").createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  port: 0\n")
        Gateway(dir, port = 0).use { gw ->
            gw.start()
            val http = Http(gw.port())
            val status = http.send("GET", "/api/v1/setup")
            assertEquals(200, status.status, status.body)
            assertEquals(true, status.json["needsAdmin"])
            assertEquals(true, status.json["fromThisMachine"])

            val body = """{"username":"admin","password":"First-run-Pass-42"}""".toByteArray()
            val foreign = http.send("POST", "/api/v1/setup/admin", body, headers = mapOf("Origin" to "http://evil.example"))
            assertEquals(403, foreign.status, foreign.body)
            val proxied = http.send("POST", "/api/v1/setup/admin", body, headers = mapOf("X-Forwarded-For" to "203.0.113.9"))
            assertEquals(403, proxied.status, proxied.body)
            val plain = http.send("POST", "/api/v1/setup/admin", body, type = "text/plain")
            assertEquals(400, plain.status, plain.body)
            val weak = http.send("POST", "/api/v1/setup/admin", """{"username":"admin","password":"x"}""".toByteArray())
            assertEquals(422, weak.status, weak.body)
            assertTrue(gw.security.users.list().isEmpty())

            val created = http.send("POST", "/api/v1/setup/admin", body)
            assertEquals(201, created.status, created.body)
            assertEquals("admin", created.json["username"])
            assertTrue((created.json["csrfToken"] as String).isNotEmpty())
            val session = http.send("GET", "/api/v1/auth/session")
            assertEquals(200, session.status, session.body)

            val again = Http(gw.port()).send("POST", "/api/v1/setup/admin", """{"username":"other","password":"Another-Pass-77"}""".toByteArray())
            assertEquals(409, again.status, again.body)
            assertEquals(false, Http(gw.port()).send("GET", "/api/v1/setup").json["needsAdmin"])
            assertEquals(200, Http(gw.port()).login("admin", "First-run-Pass-42").status)
        }
    }
}
