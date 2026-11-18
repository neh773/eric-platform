package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Per-IP login throttle, (username, IP) lockout plus a global cap, malformed-username responses,
 * Secure cookie defaults, and a bounded failure table.
 *
 * Lockout window and attempt count:
 * The doubling duration and the per-address split are this gateway's choices; see docs/ops/http-auth.md.
 */
class AuthHardeningTest {
    @TempDir lateinit var tmp: Path

    @Test fun passwordSprayAcrossUsernamesIsThrottledPerIp() {
        val gw = gateway(tmp.resolve("spray"), """
            trustedProxies: ["127.0.0.1"]
            loginRatePerMinute: 4
        """)
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val attacker = Http(gw.port()).also { it.forwardedFor = "203.0.113.8" }
            val bodies = mutableListOf<Map<String, Any?>>()
            for (name in listOf("Admin", "Bob", "not a user", "x")) {
                val r = attacker.login(name, "Wrong-pass-1")
                assertProblem(r, 401, "unauthorized")
                assertEquals("invalid credentials", r.json["detail"])
                assertFalse(r.body.contains("lowercase"), r.body)
                assertFalse(r.body.contains(name), r.body)
                bodies += r.json
            }
            assertEquals(bodies[0]["type"], bodies[1]["type"])
            assertEquals(bodies[0]["title"], bodies[3]["title"])
            assertEquals(bodies[0]["detail"], bodies[2]["detail"])
            val blocked = attacker.login("erin", "Wrong-pass-1")
            assertProblem(blocked, 429, "rate-limited")
            assertTrue((blocked.json["retryAfter"] as Number).toLong() >= 1L)
            assertEquals(blocked.json["retryAfter"].toString(), blocked.header("Retry-After"))
            val owner = Http(gw.port()).also { it.forwardedFor = "203.0.113.9" }
            assertEquals(200, owner.login("admin", TEST_PASSWORD).status)
        } finally { gw.close() }
    }

    @Test fun malformedUsernameMatchesAnUnknownUser() {
        val gw = gateway(tmp.resolve("same"), """
            trustedProxies: ["127.0.0.1"]
            loginRatePerMinute: 20
        """)
        try {
            val http = Http(gw.port()).also { it.forwardedFor = "203.0.113.4" }
            val bad = http.login("Admin", "Wrong-pass-1")
            val missing = http.login("nosuchuser", "Wrong-pass-1")
            assertProblem(bad, 401, "unauthorized")
            assertProblem(missing, 401, "unauthorized")
            assertEquals(missing.json["title"], bad.json["title"])
            assertEquals(missing.json["detail"], bad.json["detail"])
            assertEquals("Bearer realm=\"oip\"", bad.header("WWW-Authenticate"))
            assertFalse(bad.body.contains("invalid resource"))
        } finally { gw.close() }
    }

    @Test fun throttleBackoffGrowsWhileTheBucketIsEmpty() {
        val clock = MutableClock()
        val gw = gateway(tmp.resolve("bucket"), """
            trustedProxies: ["127.0.0.1"]
            loginRatePerMinute: 2
        """, clock)
        try {
            val http = Http(gw.port()).also { it.forwardedFor = "198.51.100.7" }
            assertEquals(401, http.login("Admin", "Wrong-pass-1").status)
            assertEquals(401, http.login("Bob", "Wrong-pass-1").status)
            val first = http.login("Cara", "Wrong-pass-1")
            assertProblem(first, 429, "rate-limited")
            assertEquals(30L, (first.json["retryAfter"] as Number).toLong())
            val second = http.login("Dee", "Wrong-pass-1")
            assertProblem(second, 429, "rate-limited")
            assertEquals(60L, (second.json["retryAfter"] as Number).toLong())
            clock.advance(Duration.ofSeconds(59))
            val still = http.login("Eve", "Wrong-pass-1")
            assertProblem(still, 429, "rate-limited")
            assertTrue((still.json["retryAfter"] as Number).toLong() >= 60L, still.body)
            clock.advance(Duration.ofSeconds((still.json["retryAfter"] as Number).toLong()))
            assertEquals(401, http.login("Fay", "Wrong-pass-1").status)
        } finally { gw.close() }
    }

    @Test fun oneAddressCannotLockTheAccountForEveryone() {
        val gw = gateway(tmp.resolve("pair"), """
            trustedProxies: ["127.0.0.1"]
            loginRatePerMinute: 100
        """)
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val attacker = Http(gw.port()).also { it.forwardedFor = "203.0.113.20" }
            repeat(4) { assertEquals(401, attacker.login("admin", "Wrong-pass-1").status) }
            assertProblem(attacker.login("admin", "Wrong-pass-1"), 429, "account-locked")
            assertProblem(attacker.login("admin", TEST_PASSWORD), 429, "account-locked")
            val owner = Http(gw.port()).also { it.forwardedFor = "203.0.113.21" }
            assertEquals(401, owner.login("admin", "Wrong-pass-1").status, "a second address is not pair-locked")
            assertEquals(6, gw.httpAuth.globalFailures("admin"))
            assertEquals(200, owner.login("admin", TEST_PASSWORD).status)
            assertEquals(0, gw.httpAuth.globalFailures("admin"))
        } finally { gw.close() }
    }

    @Test fun forwardedForIsIgnoredUnlessThePeerIsTrusted() {
        val gw = gateway(tmp.resolve("untrusted"), "loginRatePerMinute: 100\n")
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val a = Http(gw.port()).also { it.forwardedFor = "203.0.113.30" }
            val b = Http(gw.port()).also { it.forwardedFor = "203.0.113.31" }
            repeat(4) { assertEquals(401, a.login("admin", "Wrong-pass-1").status) }
            assertProblem(a.login("admin", "Wrong-pass-1"), 429, "account-locked")
            assertProblem(b.login("admin", TEST_PASSWORD), 429, "account-locked")
        } finally { gw.close() }
    }

    @Test fun distinctAddressesCanTripTheGlobalCap() {
        val gw = gateway(tmp.resolve("global"), """
            trustedProxies: ["127.0.0.1"]
            loginRatePerMinute: 100
            lockoutDistinctIps: 2
        """)
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val a = Http(gw.port()).also { it.forwardedFor = "203.0.113.40" }
            val b = Http(gw.port()).also { it.forwardedFor = "203.0.113.41" }
            val c = Http(gw.port()).also { it.forwardedFor = "203.0.113.42" }
            repeat(5) { a.login("admin", "Wrong-pass-1") }
            repeat(4) { assertEquals(401, b.login("admin", "Wrong-pass-1").status) }
            val tipped = b.login("admin", "Wrong-pass-1")
            assertProblem(tipped, 429, "account-locked")
            assertEquals(900L, (tipped.json["retryAfter"] as Number).toLong())
            assertProblem(c.login("admin", TEST_PASSWORD), 429, "account-locked")
            assertTrue(gw.httpAuth.globalFailures("admin") >= 10)
        } finally { gw.close() }
    }

    @Test fun oneAddressCannotFillTheGlobalCounterByItself() {
        val clock = MutableClock()
        val dir = tmp.resolve("global-one")
        val store = HttpAuthStore(dir.resolve("http-auth.sqlite"), clock, AuthLimits(permitsPerMinute = 0, distinctIpCap = 5))
        try {
            repeat(5) { assertFalse(store.recordFailure("admin", "203.0.113.1").let { it.justLocked && it.failures > 5 }) }
            assertEquals(5, store.globalFailures("admin"))
            assertTrue(store.recordFailure("admin", "203.0.113.1").locked)
            clock.advance(Duration.ofMinutes(15))
            val again = store.recordFailure("admin", "203.0.113.1")
            assertTrue(again.locked)
            assertEquals(1800L, again.retryAfterSeconds)
            assertEquals(5, store.globalFailures("admin"))
        } finally { store.close() }
    }

    @Test fun failureTableDropsExpiredRowsAndKeepsTheCap() {
        val clock = MutableClock()
        val dir = tmp.resolve("cap")
        dir.createDirectories()
        val store = HttpAuthStore(dir.resolve("http-auth.sqlite"), clock, AuthLimits(maxFailureRows = 3, permitsPerMinute = 0))
        try {
            listOf("u1", "u2", "u3", "u4", "u5").forEach { store.recordFailure(it, "203.0.113.50") }
            assertEquals(3, store.pairRowCount())
            assertEquals(3, store.globalRowCount())
            assertFalse(store.hasPair("u1", "203.0.113.50"))
            assertFalse(store.hasPair("u2", "203.0.113.50"))
            assertTrue(store.hasPair("u5", "203.0.113.50"))
            clock.advance(Duration.ofMinutes(15))
            store.pruneExpired()
            assertEquals(0, store.pairRowCount())
            assertEquals(0, store.globalRowCount())
        } finally { store.close() }
    }

    @Test fun capKeepsAnActiveLockAndDropsStaleLocksAfterTheWindow() {
        val clock = MutableClock()
        val dir = tmp.resolve("keep-lock")
        dir.createDirectories()
        val store = HttpAuthStore(dir.resolve("http-auth.sqlite"), clock, AuthLimits(maxFailureRows = 2, permitsPerMinute = 0))
        try {
            repeat(5) { store.recordFailure("ada", "203.0.113.60") }
            listOf("u1", "u2", "u3").forEach { store.recordFailure(it, "203.0.113.60") }
            assertEquals(2, store.pairRowCount())
            assertTrue(store.hasPair("ada", "203.0.113.60"))
            assertTrue(store.lockRemaining("ada", "203.0.113.60")!! > 0L)
            clock.advance(Duration.ofMinutes(15))
            store.pruneExpired()
            assertTrue(store.hasPair("ada", "203.0.113.60"), "backoff state stays until a window after the lock ends")
            clock.advance(Duration.ofMinutes(15))
            store.pruneExpired()
            assertFalse(store.hasPair("ada", "203.0.113.60"))
            assertFalse(store.hasGlobal("ada"))
        } finally { store.close() }
    }

    @Test fun secureCookieFollowsHttpsTrustedProxyAndPlainHttp() {
        val plain = gateway(tmp.resolve("plain"), "")
        try {
            plain.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val cookie = Http(plain.port()).login("admin", TEST_PASSWORD).header("Set-Cookie")!!
            assertFalse(Regex("(?i)(^|;)\\s*Secure\\s*(;|$)").containsMatchIn(cookie), cookie)
            assertTrue(plain.insecureCookieWarning!!.contains("plain HTTP"))
        } finally { plain.close() }

        val https = gateway(tmp.resolve("https"), "https: true\nhttpsPort: 0\n")
        try {
            https.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val cookie = Http(https.port()).login("admin", TEST_PASSWORD).header("Set-Cookie")!!
            assertTrue(Regex("(?i)(^|;)\\s*Secure\\s*(;|$)").containsMatchIn(cookie), cookie)
            assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"))
            assertNull(https.insecureCookieWarning)
        } finally { https.close() }

        val proxy = gateway(tmp.resolve("proxy"), """trustedProxies: ["10.0.0.0/8"]""")
        try {
            proxy.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val cookie = Http(proxy.port()).login("admin", TEST_PASSWORD).header("Set-Cookie")!!
            assertTrue(Regex("(?i)(^|;)\\s*Secure\\s*(;|$)").containsMatchIn(cookie), cookie)
            assertNull(proxy.insecureCookieWarning)
        } finally { proxy.close() }
    }

    private fun gateway(dir: Path, httpYaml: String, clock: MutableClock = MutableClock()): Gateway {
        dir.createDirectories()
        if (httpYaml.isNotBlank()) {
            val body = httpYaml.trimIndent().trim().lines().joinToString("\n") { "  $it" }
            dir.resolve("gateway.yaml").writeText("profile: standard\nhttp:\n$body\n")
        }
        return Gateway(dir, port = 0, clock = clock).start()
    }

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
        assertEquals("urn:oip:problem:$slug", r.json["type"])
        assertEquals(status, (r.json["status"] as Number).toInt())
    }
}
