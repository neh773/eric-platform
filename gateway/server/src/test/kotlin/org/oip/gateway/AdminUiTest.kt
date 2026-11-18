package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class AdminUiTest {
    @TempDir lateinit var tmp: Path

    @Test fun resolveStaysInsideTheBundle() {
        val root = tmp.resolve("ui").createDirectories()
        root.resolve("index.html").writeText("<div id=\"root\"></div>")
        assertEquals(root.resolve("index.html").normalize(), AdminUi.resolve(root, "index.html")!!.normalize())
        assertNull(AdminUi.resolve(root, "../gateway.yaml"))
        assertNull(AdminUi.resolve(root, "assets/../../gateway.yaml"))
        assertNull(AdminUi.resolve(root, "assets//app.js"))
        assertNull(AdminUi.resolve(root, "/index.html"))
        assertNull(AdminUi.resolve(root, ""))
    }

    @Test fun bundleIsPublicAndTheApiStaysAuthenticated() {
        val root = tmp.resolve("ui").createDirectories()
        val assets = root.resolve("assets").createDirectories()
        root.resolve("index.html").writeText("<!doctype html><div id=\"root\" data-oip-admin=\"1\"></div>")
        assets.resolve("app.js").writeText("console.log('oip-admin')")
        Gateway(dataDir("with"), port = 0, adminDist = root).use { g ->
            g.start()
            val http = Http(g.port())
            val page = http.get("/admin")
            assertEquals(200, page.status, page.body)
            assertTrue(page.header("Content-Type")!!.contains("text/html"), page.header("Content-Type"))
            assertTrue(page.body.contains("data-oip-admin"))
            val script = http.get("/admin/assets/app.js")
            assertEquals(200, script.status, script.body)
            assertTrue(script.header("Content-Type")!!.contains("javascript"))
            assertTrue(script.body.contains("oip-admin"))
            val spa = http.get("/admin/overview")
            assertEquals(200, spa.status)
            assertTrue(spa.body.contains("data-oip-admin"))
            val login = http.get("/admin/login")
            assertEquals(200, login.status)
            assertTrue(login.body.contains("data-oip-admin"))
            assertEquals(404, http.get("/admin/assets/missing.js").status)
            val escaped = http.get("/admin/assets/..%2F..%2Fgateway.yaml")
            assertEquals(404, escaped.status, escaped.body)
            assertFalse(escaped.body.contains("profile:"))
            assertEquals(401, http.get("/api/v1/config").status)
            assertEquals(401, http.get("/api/v1/auth/session").status)
            val health = http.get("/api/v1/health")
            assertEquals(200, health.status, health.body)
            val warnings = health.json["warnings"] as List<*>
            assertTrue(warnings.any { it.toString().contains("plain HTTP") }, warnings.toString())
        }
    }

    @Test fun missingBundleIsNotFound() {
        Gateway(dataDir("none"), port = 0, adminDist = tmp.resolve("absent")).use { g ->
            g.start()
            val http = Http(g.port())
            val page = http.get("/admin")
            assertEquals(404, page.status, page.body)
            assertTrue(page.header("Content-Type")!!.contains("problem+json"), page.header("Content-Type"))
            assertTrue(page.body.contains("not installed"), page.body)
            assertEquals(401, http.get("/api/v1/config").status)
            assertEquals("UP", http.get("/api/v1/health").json["status"])
        }
    }

    private fun dataDir(name: String): Path {
        val dir = tmp.resolve(name).createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        return dir
    }
}
