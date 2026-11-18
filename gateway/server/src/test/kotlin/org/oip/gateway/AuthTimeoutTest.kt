package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.AuditStatus
import org.oip.security.DefaultRoles
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Lockout and session expiry. The clock is injected, so the test does not sleep.
 * Lockout attempts and the 15 minute window:
 * Lockout audit action (recorded once per transition into lockout):
 * Idle timeout is gateway security inactivityTimeoutMinutes (<= 0 disables it):
 */
class AuthTimeoutTest {
    @TempDir lateinit var tmp: Path

    @Test fun repeatedFailuresLockTheAccountWithBackoff() {
        val clock = MutableClock()
        val gw = Gateway(tmp.resolve("lock"), port = 0, clock = clock).start()
        try {
            gw.createUser("ada", setOf(DefaultRoles.ADMINISTRATOR))
            val http = Http(gw.port())
            repeat(4) { i ->
                val r = http.login("ada", "Wrong-pass-1")
                assertEquals(401, r.status, "attempt ${i + 1}: ${r.body}")
            }
            assertEquals(0, gw.auditCount(AuditActions.LOCKOUT, "ada"))
            val locked = http.login("ada", "Wrong-pass-1")
            assertProblem(locked, 429, "account-locked")
            assertEquals(900L, (locked.json["retryAfter"] as Number).toLong())
            assertEquals("900", locked.header("Retry-After"))
            assertEquals(5, gw.auditCount(AuditActions.LOGIN_ATTEMPT, "ada", AuditStatus.FAILURE))
            assertEquals(1, gw.auditCount(AuditActions.LOCKOUT, "ada", AuditStatus.FAILURE))

            val still = http.login("ada", TEST_PASSWORD)
            assertProblem(still, 429, "account-locked")
            assertNull(http.sessionCookie())
            assertEquals(5, gw.auditCount(AuditActions.LOGIN_ATTEMPT, "ada", AuditStatus.FAILURE))
            assertEquals(1, gw.auditCount(AuditActions.LOCKOUT, "ada"))

            clock.advance(Duration.ofSeconds(899))
            assertProblem(http.login("ada", TEST_PASSWORD), 429, "account-locked")
            clock.advance(Duration.ofSeconds(1))
            assertEquals(200, http.login("ada", TEST_PASSWORD).status, "lock should have expired")
            assertEquals(1, gw.auditCount(AuditActions.LOGIN, "ada", AuditStatus.SUCCESS))

            repeat(5) { http.login("ada", "Wrong-pass-1") }
            val again = http.login("ada", "nope")
            // The fifth new failure locks again. The loop already sent five, so this is an attempt during the lock.
            assertProblem(again, 429, "account-locked")
            assertEquals(900L, (http.login("ada", "Wrong-pass-1").json["retryAfter"] as Number).toLong())
            clock.advance(Duration.ofSeconds(900))
            val longer = http.login("ada", "Wrong-pass-1")
            assertProblem(longer, 429, "account-locked")
            assertEquals(1800L, (longer.json["retryAfter"] as Number).toLong())
            assertEquals(3, gw.auditCount(AuditActions.LOCKOUT, "ada"))
        } finally { gw.close() }
    }

    @Test fun failuresOutsideTheWindowDoNotAccumulate() {
        val clock = MutableClock()
        val gw = Gateway(tmp.resolve("window"), port = 0, clock = clock).start()
        try {
            gw.createUser("bea", setOf(DefaultRoles.VIEWER))
            val http = Http(gw.port())
            repeat(4) { assertEquals(401, http.login("bea", "Wrong-pass-1").status) }
            clock.advance(Duration.ofMinutes(15))
            assertEquals(401, http.login("bea", "Wrong-pass-1").status)
            assertEquals(0, gw.auditCount(AuditActions.LOCKOUT, "bea"))
            assertEquals(200, http.login("bea", TEST_PASSWORD).status)
        } finally { gw.close() }
    }

    @Test fun idleTimeoutExpiresTheSessionAndARequestSlidesIt() {
        val clock = MutableClock()
        val gw = Gateway(tmp.resolve("idle"), port = 0, clock = clock).start()
        try {
            val settings = gw.security.gatewaySecurity.get()
            gw.security.gatewaySecurity.update(settings.copy(inactivityTimeoutMinutes = 1), actor = "test")
            gw.createUser("cy", setOf(DefaultRoles.ADMINISTRATOR))
            val http = Http(gw.port())
            val login = http.login("cy", TEST_PASSWORD)
            assertEquals(200, login.status, login.body)
            assertEquals(60, (login.json["idleTimeoutSeconds"] as Number).toInt())
            clock.advance(Duration.ofSeconds(50))
            assertEquals(200, http.get("/api/v1/auth/session").status)
            clock.advance(Duration.ofSeconds(50))
            assertEquals(200, http.get("/api/v1/auth/session").status)
            clock.advance(Duration.ofSeconds(61))
            val expired = http.get("/api/v1/auth/session")
            assertProblem(expired, 401, "session-expired")
            assertTrue(expired.header("Set-Cookie")!!.contains("Max-Age=0"))
            assertNull(http.sessionCookie())
        } finally { gw.close() }
    }

    /** H-1: a running HMI session re-reads its project in the background; that must not keep the login alive. */
    @Test fun backgroundReadsDoNotSlideTheIdleTimeoutButStillExpire() {
        val clock = MutableClock()
        val gw = Gateway(tmp.resolve("background"), port = 0, clock = clock).start()
        try {
            val settings = gw.security.gatewaySecurity.get()
            gw.security.gatewaySecurity.update(settings.copy(inactivityTimeoutMinutes = 1), actor = "test")
            gw.createUser("fay", setOf(DefaultRoles.ADMINISTRATOR))
            val http = Http(gw.port())
            assertEquals(200, http.login("fay", TEST_PASSWORD).status)
            val background = mapOf(HttpAuth.BACKGROUND_HEADER to "1")
            val views = "/api/v1/config/designer/project/demo/View"
            // Background reads inside the timeout are answered, but do not count as activity.
            for (step in 1..5) {
                clock.advance(Duration.ofSeconds(10))
                assertEquals(200, http.send("GET", views, headers = background).status, "step $step")
            }
            clock.advance(Duration.ofSeconds(11))
            val expired = http.send("GET", views, headers = background)
            assertProblem(expired, 401, "session-expired")
            assertNull(http.sessionCookie())
            assertEquals(401, http.get("/api/v1/auth/session").status)

            // Any other header value is an ordinary request, and an ordinary request still slides the timeout.
            assertEquals(200, http.login("fay", TEST_PASSWORD).status)
            clock.advance(Duration.ofSeconds(50))
            assertEquals(200, http.send("GET", views, headers = mapOf(HttpAuth.BACKGROUND_HEADER to "0")).status)
            clock.advance(Duration.ofSeconds(50))
            assertEquals(200, http.get(views).status)
            clock.advance(Duration.ofSeconds(50))
            assertEquals(200, http.send("GET", views, headers = background).status)
        } finally { gw.close() }
    }

    @Test fun disabledAbsoluteTimeoutIsASessionCookie() {
        val dir = tmp.resolve("session-cookie")
        dir.createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nhttp:\n  absoluteSessionMinutes: 0\n")
        val gw = Gateway(dir, port = 0).start()
        try {
            gw.createUser("eve", setOf(DefaultRoles.ADMINISTRATOR))
            val http = Http(gw.port())
            val login = http.login("eve", TEST_PASSWORD)
            assertEquals(200, login.status, login.body)
            val cookie = login.header("Set-Cookie")!!
            assertFalse(cookie.contains("Max-Age"), cookie)
            assertTrue(cookie.contains("HttpOnly"), cookie)
            assertEquals(200, http.get("/api/v1/auth/session").status)
        } finally { gw.close() }
    }

    @Test fun absoluteTimeoutExpiresAnActiveSession() {
        val clock = MutableClock()
        val gw = Gateway(tmp.resolve("absolute"), port = 0, clock = clock, sessionPolicyOverride = SessionPolicy(Duration.ofMinutes(2))).start()
        try {
            gw.createUser("dee", setOf(DefaultRoles.ADMINISTRATOR))
            val http = Http(gw.port())
            assertEquals(200, http.login("dee", TEST_PASSWORD).status)
            val cookie = http.sessionCookie()
            clock.advance(Duration.ofSeconds(90))
            val mid = http.get("/api/v1/auth/session")
            assertEquals(200, mid.status, mid.body)
            assertEquals(cookie, http.sessionCookie())
            clock.advance(Duration.ofSeconds(31))
            val expired = http.get("/api/v1/auth/session")
            assertProblem(expired, 401, "session-expired")
            assertTrue(expired.header("Set-Cookie")!!.contains("Max-Age=0"))
        } finally { gw.close() }
    }

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"))
        assertEquals("urn:oip:problem:$slug", r.json["type"])
        assertEquals(status, (r.json["status"] as Number).toInt())
    }
}
