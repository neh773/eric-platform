package org.oip.redundancy

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.gateway.Gateway
import org.oip.gateway.Http
import org.oip.gateway.TEST_PASSWORD
import org.oip.gateway.createUser
import org.oip.security.DefaultRoles
import java.nio.file.Path
import kotlin.io.path.readText

/** RED-002 and RED-003: the pair form writes gateway.yaml through PUT /api/v1/redundancy. */
class RedundancySettingsApiTest {
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

    @Test fun `an administrator saves the master address and a viewer cannot`() {
        val saved = http.put(
            "/api/v1/redundancy",
            """{"mode":"backup","masterHost":"10.0.0.8","masterPort":8060,"pingRateMillis":2000,"pingMaxMissed":4}""",
        )
        assertEquals(200, saved.status, saved.body)
        assertEquals("backup", saved.json["mode"])
        assertEquals("10.0.0.8", saved.json["masterHost"])
        val file = tmp.resolve("gateway.yaml").readText()
        assertTrue(file.contains("10.0.0.8"), file)
        val viewer = Http(gw.port())
        assertEquals(200, viewer.login("view", TEST_PASSWORD).status)
        assertEquals(403, viewer.put("/api/v1/redundancy", """{"mode":"master","masterHost":"10.0.0.9"}""").status)
        val rejected = http.put("/api/v1/redundancy", """{"mode":"master","masterHost":"bad host"}""")
        assertEquals(422, rejected.status, rejected.body)
    }
}
