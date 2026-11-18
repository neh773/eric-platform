package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Reverse-proxy headers (HMP-088): `X-Forwarded-Proto`, `-Host` and `-Prefix` shape the links, cookies and origin check
 * only when the socket peer is a trusted proxy. See docs/ops/reverse-proxy.md.
 */
class ForwardedHeadersTest {
    @TempDir lateinit var tmp: Path
    private val trusted = listOf("127.0.0.1", "10.0.0.0/8")

    @Test fun schemeFollowsTheProxyOnlyWhenTrusted() {
        assertEquals("https", ForwardedHeaders.scheme("10.1.2.3", "http", "https", trusted))
        assertEquals("http", ForwardedHeaders.scheme("10.1.2.3", "https", "http", trusted))
        assertEquals("https", ForwardedHeaders.scheme("10.1.2.3", "https", "HTTPS, http", trusted))
        assertEquals("http", ForwardedHeaders.scheme("203.0.113.5", "http", "https", trusted))
        assertEquals("http", ForwardedHeaders.scheme("10.1.2.3", "http", "gopher", trusted))
        assertEquals("http", ForwardedHeaders.scheme("10.1.2.3", "http", null, trusted))
        assertEquals("http", ForwardedHeaders.scheme("10.1.2.3", "http", "https", emptyList()))
    }

    @Test fun hostIsValidatedAndTrustGated() {
        assertEquals("hmi.example.test", ForwardedHeaders.host("10.0.0.2", "gateway:8088", "hmi.example.test", trusted))
        assertEquals("hmi.example.test:8443", ForwardedHeaders.host("10.0.0.2", "gateway:8088", "hmi.example.test:8443, other", trusted))
        assertEquals("gateway:8088", ForwardedHeaders.host("203.0.113.5", "gateway:8088", "evil.test", trusted))
        assertEquals("gateway:8088", ForwardedHeaders.host("10.0.0.2", "gateway:8088", "evil.test/path", trusted))
        assertEquals("gateway:8088", ForwardedHeaders.host("10.0.0.2", "gateway:8088", "a@evil.test", trusted))
        assertEquals("gateway:8088", ForwardedHeaders.host("10.0.0.2", "gateway:8088", null, trusted))
    }

    @Test fun prefixIsNormalisedAndUnsafeValuesDropped() {
        assertEquals("/plant", ForwardedHeaders.prefix("10.0.0.2", "/plant/", trusted))
        assertEquals("/a/b", ForwardedHeaders.prefix("10.0.0.2", "a/b", trusted))
        assertEquals("", ForwardedHeaders.prefix("10.0.0.2", "/", trusted))
        assertEquals("", ForwardedHeaders.prefix("10.0.0.2", "/a/../b", trusted))
        assertEquals("", ForwardedHeaders.prefix("10.0.0.2", "//evil.test", trusted))
        assertEquals("", ForwardedHeaders.prefix("10.0.0.2", "/a?x=1", trusted))
        assertEquals("", ForwardedHeaders.prefix("203.0.113.5", "/plant", trusted))
    }

    @Test fun sessionSocketOriginCheckUsesTheForwardedHost() {
        val host = ForwardedHeaders.host("10.0.0.2", "gateway:8088", "hmi.example.test", trusted)
        assertTrue(SessionHost.sameOrigin("https://hmi.example.test", host))
        assertFalse(SessionHost.sameOrigin("https://hmi.example.test", "gateway:8088"))
    }

    @Test fun launcherShortcutAndCookieHonourTheHeadersFromATrustedProxy() {
        val gw = gateway(tmp.resolve("trusted"), """trustedProxies: ["127.0.0.1"]""")
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val http = Http(gw.port())
            val login = http.login("admin", TEST_PASSWORD)
            assertEquals(200, login.status)
            assertTrue(Regex("(?i)(^|;)\\s*Secure\\s*(;|$)").containsMatchIn(login.header("Set-Cookie")!!))
            val headers = mapOf("X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "hmi.example.test", "X-Forwarded-Prefix" to "/plant")
            val r = http.send("GET", "/api/v1/designer/launcher", headers = headers)
            assertEquals(200, r.status)
            assertTrue(r.body.contains("URL=https://hmi.example.test/plant/studio"), r.body)
        } finally { gw.close() }
    }

    @Test fun theSameHeadersAreIgnoredFromAnUntrustedPeer() {
        val gw = gateway(tmp.resolve("untrusted"), "")
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val http = Http(gw.port())
            assertEquals(200, http.login("admin", TEST_PASSWORD).status)
            val headers = mapOf("X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "evil.example.test", "X-Forwarded-Prefix" to "/plant")
            val r = http.send("GET", "/api/v1/designer/launcher", headers = headers)
            assertEquals(200, r.status)
            assertFalse(r.body.contains("evil.example.test"), r.body)
            assertFalse(r.body.contains("/plant"), r.body)
            assertTrue(r.body.contains("URL=http://"), r.body)
        } finally { gw.close() }
    }

    private fun gateway(dir: Path, httpYaml: String): Gateway {
        dir.createDirectories()
        if (httpYaml.isNotBlank()) dir.resolve("gateway.yaml").writeText("profile: standard\nhttp:\n  ${httpYaml.trim()}\n")
        return Gateway(dir, port = 0).start()
    }
}
