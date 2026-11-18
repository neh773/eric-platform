package org.oip.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Every password-policy rule from
 */
class PasswordRulesTest {
    private val hasher = Argon2idHasher.fast()
    private val strict = PasswordPolicy()

    private fun rules(password: String, username: String = "alice", policy: PasswordPolicy = strict, current: String? = null, history: List<String> = emptyList()) =
        PasswordRules.check(password.toCharArray(), username, policy, hasher, current, history).map { it.rule }.toSet()

    @Test fun defaultsMatchThePublicPage() {
        val p = PasswordPolicy()
        assertEquals(0, p.maxAgeDays)
        assertEquals(8, p.minLength)
        assertEquals(3, p.complexity)
        assertEquals(0, p.history)
        assertTrue(p.prohibitPassword)
        assertTrue(p.prohibitUsername)
        assertEquals(3, p.maxConsecutiveRepeated)
    }

    @Test fun minimumLength() {
        assertTrue(PasswordRule.MIN_LENGTH in rules("Ab1!xyz"))          // 7
        assertFalse(PasswordRule.MIN_LENGTH in rules("Ab1!xyza"))         // 8
        assertTrue(PasswordRule.MIN_LENGTH in rules("😀😀😀😀😀😀😀", policy = PasswordPolicy(minLength = 8, complexity = 0, prohibitPassword = false, prohibitUsername = false, maxConsecutiveRepeated = 0)))
    }

    @Test fun maximumLength() {
        val long = "A".repeat(PasswordRules.MAX_CODE_POINTS + 1)
        assertTrue(PasswordRule.MAX_LENGTH in rules(long, policy = PasswordPolicy(minLength = 1, complexity = 0, prohibitPassword = false, prohibitUsername = false, maxConsecutiveRepeated = 0)))
        val ok = "A".repeat(PasswordRules.MAX_CODE_POINTS)
        assertFalse(PasswordRule.MAX_LENGTH in rules(ok, policy = PasswordPolicy(minLength = 1, complexity = 0, prohibitPassword = false, prohibitUsername = false, maxConsecutiveRepeated = 0)))
    }

    @Test fun complexityCountsCharacterClasses() {
        val off = strict.copy(complexity = 0, prohibitPassword = false, prohibitUsername = false, maxConsecutiveRepeated = 0, minLength = 1)
        assertTrue(rules("abcdefgh", policy = strict.copy(prohibitPassword = false, prohibitUsername = false)).contains(PasswordRule.COMPLEXITY)) // lower only? wait length ok but 1 class
        assertTrue(PasswordRule.COMPLEXITY in rules("Abcdefgh", policy = strict.copy(prohibitPassword = false, prohibitUsername = false))) // 2 classes
        assertFalse(PasswordRule.COMPLEXITY in rules("Abcdefg1", policy = strict.copy(prohibitPassword = false, prohibitUsername = false))) // 3
        assertFalse(PasswordRule.COMPLEXITY in rules("Abcdefg!", policy = strict.copy(prohibitPassword = false, prohibitUsername = false))) // upper lower special
        assertTrue(PasswordRule.COMPLEXITY in rules("Abcdefg1", policy = strict.copy(complexity = 4, prohibitPassword = false, prohibitUsername = false)))
        assertFalse(PasswordRule.COMPLEXITY in rules("Ab1!", policy = strict.copy(complexity = 4, minLength = 4, prohibitPassword = false, prohibitUsername = false)))
        assertTrue(rules("a", policy = off).isEmpty())
    }

    @Test fun consecutiveRepeatedCharacters() {
        val base = strict.copy(prohibitPassword = false, prohibitUsername = false, complexity = 0, minLength = 1)
        assertFalse(PasswordRule.MAX_CONSECUTIVE in rules("aaa", policy = base))
        assertTrue(PasswordRule.MAX_CONSECUTIVE in rules("aaaa", policy = base))
        assertFalse(PasswordRule.MAX_CONSECUTIVE in rules("aaaa", policy = base.copy(maxConsecutiveRepeated = 0)))
        assertTrue(PasswordRule.MAX_CONSECUTIVE in rules("xx1111", policy = base.copy(maxConsecutiveRepeated = 3)))
    }

    @Test fun prohibitedPasswordForms() {
        val base = strict.copy(minLength = 1, complexity = 0, prohibitUsername = false, maxConsecutiveRepeated = 0)
        for (form in PasswordRules.PROHIBITED_PASSWORD_FORMS) {
            assertTrue(PasswordRule.PROHIBIT_PASSWORD in rules("xx${form}yy", policy = base), form)
            assertTrue(PasswordRule.PROHIBIT_PASSWORD in rules("xx${form.uppercase()}yy", policy = base), form)
        }
        assertFalse(PasswordRule.PROHIBIT_PASSWORD in rules("correct horse", policy = base))
        assertFalse(PasswordRule.PROHIBIT_PASSWORD in rules("xxpasswordyy", policy = base.copy(prohibitPassword = false)))
    }

    @Test fun prohibitedUsernameForms() {
        val base = strict.copy(minLength = 1, complexity = 0, prohibitPassword = false, maxConsecutiveRepeated = 0)
        assertTrue(PasswordRule.PROHIBIT_USERNAME in rules("xxAliceyy", username = "alice", policy = base))
        assertTrue(PasswordRule.PROHIBIT_USERNAME in rules("a.l.i.c.e.9", username = "alice", policy = base))
        assertFalse(PasswordRule.PROHIBIT_USERNAME in rules("completely-different", username = "alice", policy = base))
        assertFalse(PasswordRule.PROHIBIT_USERNAME in rules("xxaliceyy", username = "alice", policy = base.copy(prohibitUsername = false)))
    }

    @Test fun historyChecksEveryStoredHash() {
        val counting = CountingHasher(hasher)
        val current = hasher.hash("Current1!".toCharArray())
        val older = hasher.hash("Older12!".toCharArray())
        val ancient = hasher.hash("Ancient1!".toCharArray())
        val policy = strict.copy(history = 2, minLength = 8)
        counting.verifies = 0
        val hit = PasswordRules.check("Older12!".toCharArray(), "alice", policy, counting, current, listOf(older, ancient))
        assertTrue(PasswordRule.HISTORY in hit.map { it.rule })
        assertEquals(3, counting.verifies) // current + two history entries, even though the second matches
        counting.verifies = 0
        val miss = PasswordRules.check("BrandNew1!".toCharArray(), "alice", policy, counting, current, listOf(older, ancient))
        assertFalse(PasswordRule.HISTORY in miss.map { it.rule })
        assertEquals(3, counting.verifies)
        assertTrue(PasswordRules.check("Older12!".toCharArray(), "alice", policy.copy(history = 0), counting, current, listOf(older)).none { it.rule == PasswordRule.HISTORY })
    }

    @Test fun severalRulesFailTogether() {
        val failed = rules("pwd", username = "pwd", policy = strict.copy(history = 0))
        assertTrue(PasswordRule.MIN_LENGTH in failed)
        assertTrue(PasswordRule.COMPLEXITY in failed)
        assertTrue(PasswordRule.PROHIBIT_PASSWORD in failed)
        assertTrue(PasswordRule.PROHIBIT_USERNAME in failed)
    }

    @Test fun maxAgeBoundary() {
        val changed = Instant.parse("2026-01-01T00:00:00Z")
        val policy = PasswordPolicy(maxAgeDays = 10)
        assertFalse(policy.expired(changed, changed.plus(10, ChronoUnit.DAYS).minusMillis(1)))
        assertTrue(policy.expired(changed, changed.plus(10, ChronoUnit.DAYS)))
        assertFalse(PasswordPolicy(maxAgeDays = 0).expired(changed, changed.plus(3650, ChronoUnit.DAYS)))
    }
}

class CountingHasher(private val delegate: PasswordHasher) : PasswordHasher {
    override val memoryKiB = delegate.memoryKiB
    override val iterations = delegate.iterations
    override val parallelism = delegate.parallelism
    var verifies = 0
    var hashes = 0
    override fun hash(password: CharArray): String {
        hashes++
        return delegate.hash(password)
    }
    override fun verify(encoded: String, password: CharArray): Boolean {
        verifies++
        return delegate.verify(encoded, password)
    }
}
