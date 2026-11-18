package org.oip.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import org.bouncycastle.crypto.params.Argon2Parameters
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashes stored in an external user table (SEC-010). The configured scheme verifies the hash; a password is
 * never compared in plain text, and there is no plain-text, unsalted or single-round scheme.
 *
 * - `argon2id`: a PHC string, `$argon2id$v=19$m=<KiB>,t=<passes>,p=<lanes>$<salt>$<hash>` (unpadded base64).
 * - `bcrypt`: a `$2a$`, `$2b$` or `$2y$` string with its cost.
 * - `pbkdf2-sha256` and `pbkdf2-sha512`: `$pbkdf2-sha256$<iterations>$<salt>$<hash>` (base64 or the `.`-for-`+`
 *   variant), or `pbkdf2_sha256$<iterations>$<salt>$<hash>` where the salt is the literal text and the hash is base64.
 *
 * Cost parameters read from a row are bounded, so a row cannot make one login cost minutes of CPU or gigabytes of
 * memory. A malformed or out-of-range hash verifies as false.
 *
 * Doc source: FEATURES SEC-010.
 */
object ExternalPasswordHashes {
    const val ARGON2ID = "argon2id"
    const val BCRYPT = "bcrypt"
    const val PBKDF2_SHA256 = "pbkdf2-sha256"
    const val PBKDF2_SHA512 = "pbkdf2-sha512"
    val SCHEMES = listOf(ARGON2ID, BCRYPT, PBKDF2_SHA256, PBKDF2_SHA512)

    private const val MAX_ARGON2_KIB = 262_144
    private const val MAX_ARGON2_PASSES = 10
    private const val MAX_ARGON2_LANES = 16
    private const val MAX_PBKDF2_ITERATIONS = 2_000_000
    private const val MIN_PBKDF2_ITERATIONS = 1_000

    private val ARGON2 = Regex("^\\\$argon2id\\\$v=19\\\$m=(\\d{1,7}),t=(\\d{1,3}),p=(\\d{1,3})\\\$([A-Za-z0-9+/]+)\\\$([A-Za-z0-9+/]+)$")
    private val PBKDF2_PHC = Regex("^\\\$pbkdf2-(sha256|sha512)\\\$(\\d{1,8})\\\$([A-Za-z0-9+/.=_-]+)\\\$([A-Za-z0-9+/.=_-]+)$")
    private val PBKDF2_TEXT = Regex("^pbkdf2_(sha256|sha512)\\\$(\\d{1,8})\\\$([^\$]{1,128})\\\$([A-Za-z0-9+/=]+)$")

    /**
     * Dummy hashes of each scheme at its usual cost, verified for a missing user so a login for an unknown name costs
     * about what a wrong password costs.
     */
    private val DUMMY by lazy {
        mapOf(
        BCRYPT to OpenBSDBCrypt.generate("2b", CharArray(12) { 'x' }, ByteArray(16) { 7 }, 10),
        PBKDF2_SHA256 to "\$pbkdf2-sha256\$100000\$c2FsdHNhbHRzYWx0c2FsdA\$5mT2Bvcqf2y0r5c7r7pmn2Rk4cZqCq0wQ0dVq8bYxEo",
        PBKDF2_SHA512 to "\$pbkdf2-sha512\$100000\$c2FsdHNhbHRzYWx0c2FsdA\$5mT2Bvcqf2y0r5c7r7pmn2Rk4cZqCq0wQ0dVq8bYxEo",
        ARGON2ID to "\$argon2id\$v=19\$m=19456,t=2,p=1\$c2FsdHNhbHRzYWx0c2FsdA\$5mT2Bvcqf2y0r5c7r7pmn2Rk4cZqCq0wQ0dVq8bYxEo",
        )
    }

    /** Whether [password] matches [stored] under [scheme]. False for a hash that does not parse under that scheme. */
    fun verify(scheme: String, stored: String, password: CharArray): Boolean = runCatching {
        when (scheme) {
            ARGON2ID -> argon2(stored.trim(), password)
            BCRYPT -> bcrypt(stored.trim(), password)
            PBKDF2_SHA256, PBKDF2_SHA512 -> pbkdf2(scheme, stored.trim(), password)
            else -> false
        }
    }.getOrDefault(false)

    /** Spend about one verification's time for a user that does not exist. Always false. */
    fun verifyMissing(scheme: String, password: CharArray): Boolean {
        DUMMY[scheme]?.let { verify(scheme, it, password) }
        return false
    }

    private fun argon2(stored: String, password: CharArray): Boolean {
        val m = ARGON2.matchEntire(stored) ?: return false
        val memory = m.groupValues[1].toInt()
        val passes = m.groupValues[2].toInt()
        val lanes = m.groupValues[3].toInt()
        if (lanes !in 1..MAX_ARGON2_LANES || passes !in 1..MAX_ARGON2_PASSES || memory !in 8 * lanes..MAX_ARGON2_KIB) return false
        val salt = b64(m.groupValues[4])
        val expected = b64(m.groupValues[5])
        if (salt.size < 8 || expected.size < 16) return false
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memory).withIterations(passes).withParallelism(lanes).withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator().apply { init(params) }
        val out = ByteArray(expected.size)
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            gen.generateBytes(bytes, out)
            return ConstantTime.equal(expected, out)
        } finally {
            bytes.fill(0)
        }
    }

    private fun bcrypt(stored: String, password: CharArray): Boolean {
        if (!stored.matches(Regex("^\\\$2[aby]\\\$(0[4-9]|1[0-6])\\\$[./A-Za-z0-9]{53}$"))) return false
        return OpenBSDBCrypt.checkPassword(stored, password)
    }

    private fun pbkdf2(scheme: String, stored: String, password: CharArray): Boolean {
        val (algo, iterations, salt, expected) = PBKDF2_PHC.matchEntire(stored)?.let {
            Quad(it.groupValues[1], it.groupValues[2].toInt(), ab64(it.groupValues[3]), ab64(it.groupValues[4]))
        } ?: PBKDF2_TEXT.matchEntire(stored)?.let {
            Quad(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toByteArray(Charsets.UTF_8), Base64.getDecoder().decode(it.groupValues[4]))
        } ?: return false
        if ("pbkdf2-$algo" != scheme) return false
        if (iterations !in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS || salt.size < 4 || expected.size < 16 || expected.size > 128) return false
        val factory = SecretKeyFactory.getInstance(if (algo == "sha256") "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(password, salt, iterations, expected.size * 8)
        try {
            return ConstantTime.equal(expected, factory.generateSecret(spec).encoded)
        } finally {
            spec.clearPassword()
        }
    }

    private data class Quad(val algo: String, val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s.padEnd((s.length + 3) / 4 * 4, '='))

    /** Standard, URL-safe, or the `.`-for-`+` base64 used by PHC-style PBKDF2 strings; padding optional. */
    private fun ab64(s: String): ByteArray {
        val std = s.replace('.', '+').replace('-', '+').replace('_', '/').trimEnd('=')
        return b64(std)
    }
}
