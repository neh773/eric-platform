package org.oip.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.math.min

/**
 * Argon2id password hashing (SPEC: hashed passwords use Argon2id).
 * Production parameters follow the OWASP Password Storage Cheat Sheet (Argon2id, 19 MiB, t=2, p=1):
 * https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
 *
 * The encoded string is PHC (`$argon2id$v=19$m=…,t=…,p=…$salt$hash`). Verification uses the parameters
 * stored in the string, so raising the defaults later does not invalidate existing passwords.
 * A malformed string still runs a full Argon2id pass against a dummy salt so the cost is not skipped.
 */
interface PasswordHasher {
    val memoryKiB: Int
    val iterations: Int
    val parallelism: Int
    fun hash(password: CharArray): String
    fun verify(encoded: String, password: CharArray): Boolean
}

class Argon2idHasher internal constructor(
    override val memoryKiB: Int,
    override val iterations: Int,
    override val parallelism: Int,
    private val random: SecureRandom = SecureRandom(),
) : PasswordHasher {
    init {
        require(memoryKiB >= 8 * parallelism) { "Argon2 memory must be at least ${8 * parallelism} KiB" }
        require(iterations >= 1 && parallelism >= 1)
    }

    private val dummySalt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
    private val dummyHash = argon2(CharArray(16) { 'x' }, dummySalt, memoryKiB, iterations, parallelism)

    override fun hash(password: CharArray): String {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        return encode(salt, argon2(password, salt, memoryKiB, iterations, parallelism), memoryKiB, iterations, parallelism)
    }

    internal fun hashWithSalt(password: CharArray, salt: ByteArray): String =
        encode(salt, argon2(password, salt, memoryKiB, iterations, parallelism), memoryKiB, iterations, parallelism)

    override fun verify(encoded: String, password: CharArray): Boolean {
        val parsed = parse(encoded)
        val memory = parsed?.memoryKiB ?: memoryKiB
        val time = parsed?.iterations ?: iterations
        val lanes = parsed?.parallelism ?: parallelism
        val salt = parsed?.salt ?: dummySalt
        val expected = parsed?.hash ?: dummyHash
        val actual = argon2(password, salt, memory, time, lanes)
        val same = ConstantTime.equal(expected, actual)
        return parsed != null && same
    }

    private data class Parsed(
        val memoryKiB: Int, val iterations: Int, val parallelism: Int,
        val salt: ByteArray, val hash: ByteArray,
    )

    private fun parse(encoded: String): Parsed? {
        val m = PHC.matchEntire(encoded) ?: return null
        val memory = m.groupValues[1].toIntOrNull() ?: return null
        val time = m.groupValues[2].toIntOrNull() ?: return null
        val lanes = m.groupValues[3].toIntOrNull() ?: return null
        if (memory < 8 * lanes || time < 1 || lanes < 1) return null
        val salt = b64decode(m.groupValues[4])
        val hash = b64decode(m.groupValues[5])
        if (salt.size < 8 || hash.size < 16) return null
        return Parsed(memory, time, lanes, salt, hash)
    }

    companion object {
        const val SALT_LEN = 16
        const val HASH_LEN = 32
        // Kotlin string escapes: `\\\$` is the two characters `\$`, which is a literal '$' in the regex.
        private val PHC = Regex("^\\\$argon2id\\\$v=19\\\$m=(\\d+),t=(\\d+),p=(\\d+)\\\$([A-Za-z0-9+/]+)\\\$([A-Za-z0-9+/]+)\$")

        /** OWASP Argon2id floor: 19 MiB, 2 iterations, 1 lane. */
        const val PRODUCTION_MEMORY_KIB = 19 * 1024
        const val PRODUCTION_ITERATIONS = 2
        const val PRODUCTION_PARALLELISM = 1

        fun production(): Argon2idHasher = Argon2idHasher(PRODUCTION_MEMORY_KIB, PRODUCTION_ITERATIONS, PRODUCTION_PARALLELISM)

        /** Real Argon2id with a small memory cost for tests. Not used on the shipped default path. */
        internal fun fast(): Argon2idHasher = Argon2idHasher(memoryKiB = 32, iterations = 1, parallelism = 1)

        private fun encode(salt: ByteArray, hash: ByteArray, memory: Int, time: Int, lanes: Int): String =
            "\$argon2id\$v=19\$m=$memory,t=$time,p=$lanes\$${b64(salt)}\$${b64(hash)}"

        private fun b64(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)

        private fun b64decode(text: String): ByteArray {
            val pad = when (text.length % 4) { 2 -> "=="; 3 -> "="; else -> "" }
            return Base64.getDecoder().decode(text + pad)
        }

        private fun argon2(password: CharArray, salt: ByteArray, memoryKiB: Int, iterations: Int, parallelism: Int): ByteArray {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKiB)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()
            val gen = Argon2BytesGenerator()
            gen.init(params)
            val utf8 = String(password).encodeToByteArray()
            val out = ByteArray(HASH_LEN)
            try {
                gen.generateBytes(utf8, out)
            } finally {
                utf8.fill(0)
            }
            return out
        }
    }
}

/**
 * Internal user-source password policy.
 * Property meanings and defaults match the public Internal Authentication page:
 *
 * History is checked only while [history] is greater than zero, and passwords chosen while history is off are not stored.
 * [maxAgeDays] of 0 disables expiry. A password is expired when `now >= changedAt + maxAgeDays` (the age has passed).
 * [maxConsecutiveRepeated] of 0 disables that check; otherwise a run longer than the value is rejected (a run of exactly
 * that length is allowed).
 */
data class PasswordPolicy(
    val maxAgeDays: Int = 0,
    val minLength: Int = 8,
    val complexity: Int = 3,
    val history: Int = 0,
    val prohibitPassword: Boolean = true,
    val prohibitUsername: Boolean = true,
    val maxConsecutiveRepeated: Int = 3,
) {
    fun expired(changedAt: Instant, now: Instant): Boolean {
        if (maxAgeDays <= 0) return false
        val expiry = changedAt.plus(maxAgeDays.toLong(), ChronoUnit.DAYS)
        return !now.isBefore(expiry)
    }
}

object PasswordRules {
    const val MAX_CODE_POINTS = 1024

    /** Forms listed on the Internal Authentication page. Comparison is case-insensitive. */
    val PROHIBITED_PASSWORD_FORMS = listOf("password", "pass word", "pwd", "passwd", "pword")

    fun check(
        password: CharArray,
        username: String,
        policy: PasswordPolicy,
        hasher: PasswordHasher,
        currentHash: String? = null,
        history: List<String> = emptyList(),
    ): List<PasswordViolation> {
        val text = String(password)
        val out = mutableListOf<PasswordViolation>()
        val length = text.codePointCount(0, text.length)
        if (length < policy.minLength) out += PasswordViolation(PasswordRule.MIN_LENGTH, "password must be at least ${policy.minLength} characters")
        if (length > MAX_CODE_POINTS) out += PasswordViolation(PasswordRule.MAX_LENGTH, "password must be at most $MAX_CODE_POINTS characters")
        if (policy.complexity > 0 && characterClasses(text) < policy.complexity) {
            out += PasswordViolation(PasswordRule.COMPLEXITY, "password must include at least ${policy.complexity} of: lowercase, uppercase, digit, special")
        }
        if (policy.maxConsecutiveRepeated > 0 && longestRun(text) > policy.maxConsecutiveRepeated) {
            out += PasswordViolation(PasswordRule.MAX_CONSECUTIVE, "password has more than ${policy.maxConsecutiveRepeated} identical characters in a row")
        }
        if (policy.prohibitPassword && containsProhibitedWord(text)) {
            out += PasswordViolation(PasswordRule.PROHIBIT_PASSWORD, "password must not contain a form of the word password")
        }
        if (policy.prohibitUsername && containsUsername(text, username)) {
            out += PasswordViolation(PasswordRule.PROHIBIT_USERNAME, "password must not contain the username")
        }
        if (policy.history > 0 && reusesHistory(password, hasher, currentHash, history, policy.history)) {
            out += PasswordViolation(PasswordRule.HISTORY, "password was used recently")
        }
        return out
    }

    fun reject(
        password: CharArray,
        username: String,
        policy: PasswordPolicy,
        hasher: PasswordHasher,
        currentHash: String? = null,
        history: List<String> = emptyList(),
    ) {
        val violations = check(password, username, policy, hasher, currentHash, history)
        if (violations.isNotEmpty()) throw PasswordRejectedException(violations)
    }

    /** Always verifies every stored hash so a hit on the first entry is not faster than a miss on the last. */
    internal fun reusesHistory(
        password: CharArray,
        hasher: PasswordHasher,
        currentHash: String?,
        history: List<String>,
        count: Int,
    ): Boolean {
        val checks = ArrayList<String>(min(count, history.size) + 1)
        if (currentHash != null) checks += currentHash
        history.take(count).forEach { checks += it }
        var reused = false
        for (hash in checks) {
            if (hasher.verify(hash, password)) reused = true
        }
        return reused
    }

    internal fun characterClasses(text: String): Int {
        var lower = false
        var upper = false
        var digit = false
        var special = false
        text.codePoints().forEach { cp ->
            when {
                Character.isLowerCase(cp) -> lower = true
                Character.isUpperCase(cp) -> upper = true
                Character.isDigit(cp) -> digit = true
                else -> special = true
            }
        }
        return listOf(lower, upper, digit, special).count { it }
    }

    internal fun longestRun(text: String): Int {
        val cps = text.codePoints().toArray()
        if (cps.isEmpty()) return 0
        var best = 1
        var run = 1
        for (i in 1 until cps.size) {
            if (cps[i] == cps[i - 1]) {
                run++
                if (run > best) best = run
            } else run = 1
        }
        return best
    }

    internal fun containsProhibitedWord(text: String): Boolean {
        val p = text.lower()
        return PROHIBITED_PASSWORD_FORMS.any { p.contains(it) }
    }

    /** Case-insensitive substring, also after stripping non-alphanumerics (`a.l.i.c.e` contains `alice`). */
    internal fun containsUsername(text: String, username: String): Boolean {
        if (username.isEmpty()) return false
        val p = text.lower()
        val u = username.lower()
        if (p.contains(u)) return true
        val ps = p.filter { it.isLetterOrDigit() }
        val us = u.filter { it.isLetterOrDigit() }
        return us.isNotEmpty() && ps.contains(us)
    }
}
