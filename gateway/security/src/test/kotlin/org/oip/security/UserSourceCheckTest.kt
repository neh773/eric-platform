package org.oip.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * P1-34 (SEC-027): verify a user on the internal user source. The check reports existence, roles, and a password match;
 * it hashes a password for a missing user as well, so right, wrong, and missing take the same time class; it writes one
 * audit row that never holds the password, and it is not a login.
 * Doc source: FEATURES SEC-027.
 */
class UserSourceCheckTest {
    @TempDir lateinit var tmp: Path
    private var service: SecurityService? = null

    @AfterEach fun tearDown() {
        service?.close()
    }

    private fun open(hasher: PasswordHasher = Argon2idHasher.fast()): SecurityService =
        openSecurity(tmp, hasher = hasher).second.also { service = it }

    @Test fun existingAndMissingUsersRolesAndPassword() {
        val s = open()
        s.users.create(CreateUser("dana", "Correct-horse-1".toCharArray(), setOf(DefaultRoles.OPERATOR)), actor = "test")

        val exists = s.users.check("dana", null, actor = "admin")
        assertTrue(exists.exists)
        assertEquals(listOf(DefaultRoles.OPERATOR), exists.roles)
        assertEquals(true, exists.enabled)
        assertFalse(exists.passwordChecked)
        assertNull(exists.passwordValid)

        assertEquals(true, s.users.check("dana", "Correct-horse-1".toCharArray(), actor = "admin").passwordValid)
        assertEquals(false, s.users.check("dana", "Wrong-horse-2".toCharArray(), actor = "admin").passwordValid)
        val missing = s.users.check("nobody", "Correct-horse-1".toCharArray(), actor = "admin")
        assertFalse(missing.exists)
        assertEquals(emptyList<String>(), missing.roles)
        assertEquals(false, missing.passwordValid)
        // A name the directory could never hold is just a missing user.
        assertFalse(s.users.check("Not A Name", null, actor = "admin").exists)
    }

    @Test fun theCheckIsAuditedWithoutThePasswordAndIsNotALogin() {
        val s = open()
        s.users.create(CreateUser("dana", "Correct-horse-1".toCharArray(), setOf(DefaultRoles.VIEWER)), actor = "test")
        val pw = "Wrong-horse-2".toCharArray()
        s.users.check("dana", pw, actor = "admin", host = "10.0.0.5")
        assertTrue(pw.all { it == '\u0000' }, "the password buffer is wiped")
        val rows = s.audit.query(AuditQuery(action = AuditActions.USER_SOURCE_CHECK, limit = 10)).records
        assertEquals(1, rows.size)
        assertEquals("admin", rows[0].actor)
        assertEquals("10.0.0.5", rows[0].actorHost)
        assertEquals("dana", rows[0].target)
        assertEquals("source=internal;exists=true;password=invalid", rows[0].detail)
        val all = s.audit.query(AuditQuery(limit = 500)).records
        assertTrue(all.none { it.detail.contains("Wrong-horse-2") || it.target.contains("Wrong-horse-2") })
        assertEquals(0, s.audit.query(AuditQuery(action = AuditActions.LOGIN_ATTEMPT, limit = 10)).total)
        assertEquals(0, s.audit.query(AuditQuery(action = AuditActions.LOGIN, limit = 10)).total)
    }

    /**
     * Right password, wrong password, and a missing user with a password take the same time class with the production
     * hasher: every case runs one Argon2id verification, so the medians stay within a factor of 2 of each other. A check
     * that skipped the hash for a missing user would answer in microseconds against tens of milliseconds.
     */
    @Test fun rightWrongAndMissingTakeTheSameTimeClass() {
        val s = open(Argon2idHasher.production())
        s.users.create(CreateUser("erin", "Correct-horse-1".toCharArray(), setOf(DefaultRoles.VIEWER)), actor = "test")
        val cases = linkedMapOf(
            "right" to ("erin" to "Correct-horse-1"),
            "wrong" to ("erin" to "Wrong-horse-2"),
            "missing" to ("nobody" to "Correct-horse-1"),
        )
        // Warm-up, then interleaved samples so drift on a busy runner hits every case alike.
        cases.values.forEach { (u, p) -> s.users.check(u, p.toCharArray(), actor = "admin") }
        val samples = cases.keys.associateWith { mutableListOf<Long>() }
        repeat(7) {
            for ((name, up) in cases) {
                val t0 = System.nanoTime()
                s.users.check(up.first, up.second.toCharArray(), actor = "admin")
                samples.getValue(name) += System.nanoTime() - t0
            }
        }
        val medians = samples.mapValues { (_, v) -> v.sorted()[v.size / 2] }
        val max = medians.values.max().toDouble()
        val min = medians.values.min().toDouble()
        assertTrue(max / min < 2.0, "medians (ns) differ by more than a factor of 2: $medians")
    }
}
