package org.oip.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.util.encoders.Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasswordHasherTest {
    private val hasher = Argon2idHasher.fast()

    @Test fun hashVerifiesAndDiffersBySalt() {
        val password = "Abcdefg1!".toCharArray()
        val a = hasher.hash(password)
        val b = hasher.hash(password)
        assertTrue(a.startsWith("\$argon2id\$v=19\$"))
        assertNotEquals(a, b)
        assertTrue(hasher.verify(a, password))
        assertTrue(hasher.verify(b, "Abcdefg1!".toCharArray()))
        assertFalse(hasher.verify(a, "Abcdefg2!".toCharArray()))
        password.fill('\u0000')
    }

    @Test fun sameSaltIsDeterministic() {
        val salt = ByteArray(16) { it.toByte() }
        val a = hasher.hashWithSalt("password".toCharArray(), salt)
        val b = hasher.hashWithSalt("password".toCharArray(), salt)
        assertEquals(a, b)
        assertNotEquals(a, hasher.hashWithSalt("password".toCharArray(), ByteArray(16) { (it + 1).toByte() }))
    }

    @Test fun tamperedOrForeignEncodingFails() {
        val encoded = hasher.hash("Abcdefg1!".toCharArray())
        val chars = encoded.toCharArray()
        val i = encoded.lastIndexOf('$') + 2
        chars[i] = if (chars[i] == 'A') 'B' else 'A'
        assertFalse(hasher.verify(String(chars), "Abcdefg1!".toCharArray()))
        assertFalse(hasher.verify("\$argon2i\$v=19\$m=32,t=1,p=1\$c2FsdA\$aaaaaaaaaaaaaaaaaaaaaa", "Abcdefg1!".toCharArray()))
        assertFalse(hasher.verify("not-a-hash", "Abcdefg1!".toCharArray()))
    }

    @Test fun verificationUsesParametersEmbeddedInTheString() {
        val lighter = Argon2idHasher(memoryKiB = 32, iterations = 1, parallelism = 1)
        val heavier = Argon2idHasher(memoryKiB = 64, iterations = 2, parallelism = 1)
        val encoded = lighter.hash("Abcdefg1!".toCharArray())
        assertTrue(encoded.contains("m=32,t=1,p=1"))
        assertTrue(heavier.verify(encoded, "Abcdefg1!".toCharArray()))
        assertFalse(heavier.verify(encoded, "nope".toCharArray()))
    }

    @Test fun productionParametersMeetOwaspFloor() {
        assertTrue(Argon2idHasher.PRODUCTION_MEMORY_KIB >= 19 * 1024)
        assertTrue(Argon2idHasher.PRODUCTION_ITERATIONS >= 2)
        assertTrue(Argon2idHasher.PRODUCTION_PARALLELISM >= 1)
        val hasher = Argon2idHasher.production()
        assertEquals(Argon2idHasher.PRODUCTION_MEMORY_KIB, hasher.memoryKiB)
        assertEquals(2, hasher.iterations)
        assertEquals(1, hasher.parallelism)
    }

    /**
     * Argon2id test vector from draft-irtf-cfrg-argon2-03 / the Bouncy Castle suite:
     * password 32×0x01, salt 16×0x02, secret 8×0x03, ad 12×0x04, t=3, m=32 KiB, p=4, v=0x13.
     */
    @Test fun argon2idDraftVector() {
        val password = ByteArray(32) { 0x01 }
        val salt = ByteArray(16) { 0x02 }
        val secret = ByteArray(8) { 0x03 }
        val ad = ByteArray(12) { 0x04 }
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3)
            .withMemoryAsKB(32)
            .withParallelism(4)
            .withSalt(salt)
            .withSecret(secret)
            .withAdditional(ad)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(32)
        gen.generateBytes(password, out)
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", Hex.toHexString(out))
    }
}
