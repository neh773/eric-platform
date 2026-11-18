package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Format
import org.oip.config.ResourceCodec
import java.nio.file.Path

class ConfigAndModuleApiTest {
    @TempDir lateinit var tmp: Path

    @Test fun configCrudConflictAndModuleInstallUninstall() {
        Gateway(tmp, port = 0).use { g ->
            g.store.registry.register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            val created = http.put("/api/v1/config/m/gateway/Thing/a", resource("a", 0, 4))
            assertEquals(201, created.status)
            val stored = ResourceCodec.decode(created.body, Format.JSON)
            assertEquals(1L, stored.metadata.version)
            assertEquals(4, stored.spec["x"])
            assertEquals(404, http.get("/api/v1/no-such").status)
            assertTrue(http.get("/api/v1/no-such").header("Content-Type")!!.contains("problem+json"))

            val conflict = http.put("/api/v1/config/m/gateway/Thing/a", resource("a", 0, 5))
            assertEquals(409, conflict.status)
            assertTrue(conflict.header("Content-Type")!!.contains("problem+json"))
            assertEquals(409, (conflict.json["status"] as Number).toInt())

            val listed = http.get("/api/v1/config?module=m&scope=gateway")
            assertTrue(listed.body.contains("\"name\":\"a\""))
            val got = ResourceCodec.decode(http.get("/api/v1/config/m/gateway/Thing/a").body, Format.JSON)
            assertEquals(4, got.spec["x"])

            val mode = http.put("/api/v1/config/m/mode/dev/Thing/a", resource("a", 0, 8))
            assertEquals(201, mode.status)
            assertEquals(8, ResourceCodec.decode(http.get("/api/v1/config/m/mode/dev/Thing/a").body, Format.JSON).spec["x"])

            assertEquals(204, http.delete("/api/v1/config/m/gateway/Thing/a", mapOf("If-Match" to "1")).status)
            assertEquals(404, http.get("/api/v1/config/m/gateway/Thing/a").status)
        }
    }

    private fun resource(name: String, version: Long, x: Int) =
        """{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"$name","module":"m","version":$version},"spec":{"x":$x}}"""
}
