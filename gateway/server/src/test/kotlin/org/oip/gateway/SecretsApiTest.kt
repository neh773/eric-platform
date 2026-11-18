package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path

/** SEC-023: secrets can be saved and listed without the value leaving the gateway. */
class SecretsApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() {
        gw.close()
    }

    @Test fun `a saved secret is listed by reference and the value stays on the gateway`() {
        val saved = http.put("/api/v1/secrets/db-password", """{"value":"s3cret-value","description":"database"}""")
        assertEquals(200, saved.status, saved.body)
        assertFalse(saved.body.contains("s3cret-value"), saved.body)
        assertEquals("secret://db-password", saved.json["reference"])
        assertEquals("database", saved.json["description"])
        val listed = http.get("/api/v1/secrets")
        assertEquals(200, listed.status, listed.body)
        assertFalse(listed.body.contains("s3cret-value"), listed.body)
        assertEquals(true, listed.body.contains("secret://db-password"))
        val viewer = Http(gw.port())
        assertEquals(200, viewer.login("view", TEST_PASSWORD).status)
        assertEquals(403, viewer.put("/api/v1/secrets/other", """{"value":"nope"}""").status)
        assertEquals(204, http.delete("/api/v1/secrets/db-password").status)
        assertFalse(http.get("/api/v1/secrets").body.contains("db-password"))
    }
}
