package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Review-4 F8: the gateway's pages and API carry browser security headers. */
class SecurityHeadersTest {
    @TempDir lateinit var tmp: Path

    @Test fun pagesAssetsAndApiCarryTheirHeaders() {
        val web = tmp.resolve("web").createDirectories()
        web.resolve("assets").createDirectories().resolve("index-abc.js").writeText("console.log('oip')")
        web.resolve("index.html").writeText("<!doctype html><div id=\"root\"></div>")
        val admin = tmp.resolve("admin").createDirectories()
        admin.resolve("index.html").writeText("<!doctype html><div id=\"admin\"></div>")
        val dir = tmp.resolve("gw").createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(dir, port = 0, adminDist = admin, webDist = web).use { gw ->
            gw.start()
            val http = Http(gw.port())
            for (path in listOf("/hmi/", "/hmi/plant/pumps", "/studio", "/admin", "/admin/", "/assets/index-abc.js")) {
                val r = http.get(path)
                assertEquals(200, r.status, "$path ${r.body}")
                val csp = r.header("Content-Security-Policy") ?: fail("no CSP on $path")
                assertTrue(csp.contains("script-src 'self'"), "$path: $csp")
                assertFalse(csp.contains("unsafe-eval"), csp)
                assertFalse(Regex("script-src[^;]*unsafe-inline").containsMatchIn(csp), csp)
                assertTrue(csp.contains("frame-ancestors 'self'"), csp)
                assertTrue(csp.contains("connect-src 'self'"), csp)
                assertEquals("nosniff", r.header("X-Content-Type-Options"), path)
                assertEquals("same-origin", r.header("Referrer-Policy"), path)
                assertEquals("SAMEORIGIN", r.header("X-Frame-Options"), path)
            }
            for (path in listOf("/api/v1/health", "/api/v1/config")) {
                val r = http.get(path)
                assertEquals(SecurityHeaders.API_POLICY, r.header("Content-Security-Policy"), path)
                assertEquals("nosniff", r.header("X-Content-Type-Options"), path)
                assertEquals("DENY", r.header("X-Frame-Options"), path)
            }
        }
    }

    /** HMP-033: the PDF viewer frames a document from this origin or an http(s) URL, and nothing else. */
    @Test fun pagesFrameOnlyThisOriginAndHttpUrls() {
        val directives = SecurityHeaders.APP_POLICY.split(";").map { it.trim() }.associate { it.substringBefore(' ') to it.substringAfter(' ', "") }
        assertEquals("'self' http: https:", directives["frame-src"])
        assertEquals("'none'", directives["object-src"])
        assertEquals("'self'", directives["script-src"])
        assertFalse(directives.getValue("frame-src").contains("data:"))
        assertFalse(directives.getValue("frame-src").contains("blob:"))
        assertFalse(directives.containsKey("child-src"))
    }
}
