package org.oip.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase envelope for a gateway backup zip.
 *
 * The plaintext archive contains `secrets.key` and the secret store. This wrapper does not remove
 * them. It encrypts the whole archive with AES-256-GCM. The gateway seals every backup it creates
 * or stores this way ([BackupService.createEncrypted], review-1 F3); `oipctl` writes the same format. The 32-byte key is Argon2id over the
 * passphrase and a random salt, and that key is not stored in the file. A wrong passphrase fails
 * the GCM tag check.
 *
 * Layout, big-endian: magic `OIPENC1\0`, version 1, kdf id 1 (Argon2id), memory KiB, iterations,
 * parallelism, 16-byte salt, 12-byte nonce, then the ciphertext and the 16-byte tag. The header,
 * including the salt and nonce, is additional authenticated data.
 */
object BackupCipher {
    private val MAGIC = "OIPENC1\u0000".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val KDF_ARGON2ID: Byte = 1
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /** OWASP Argon2id floor: 19 MiB, 2 iterations, 1 lane. */
    val PRODUCTION = Kdf(19 * 1024, 2, 1)

    /** Shortest passphrase the gateway accepts for a new backup. Restore accepts any non-empty one. */
    const val MIN_NEW_PASSPHRASE = 12

    data class Kdf(val memoryKiB: Int, val iterations: Int, val parallelism: Int)

    /** Reject a passphrase that is too short to seal a new backup. Counts code points, not UTF-16 units. */
    fun requireNewPassphrase(passphrase: CharArray) {
        if (String(passphrase).codePointCount(0, passphrase.size) < MIN_NEW_PASSPHRASE) {
            throw IllegalArgumentException("backup passphrase must be at least $MIN_NEW_PASSPHRASE characters")
        }
    }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plaintext: ByteArray, passphrase: CharArray, kdf: Kdf = PRODUCTION): ByteArray {
        if (passphrase.isEmpty()) throw IllegalArgumentException("passphrase must not be empty")
        checkParams(kdf)
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val header = header(kdf, salt, nonce)
        val key = derive(passphrase, salt, kdf)
        try {
            return header + aes(Cipher.ENCRYPT_MODE, key, nonce, header, plaintext)
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("could not encrypt backup: ${e.message}")
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(envelope: ByteArray, passphrase: CharArray): ByteArray {
        if (passphrase.isEmpty()) throw IllegalArgumentException("passphrase must not be empty")
        if (!isEncrypted(envelope)) throw IllegalArgumentException("not an encrypted backup")
        val parsed = parse(envelope)
        val key = derive(passphrase, parsed.salt, parsed.kdf)
        try {
            return aes(Cipher.DECRYPT_MODE, key, parsed.nonce, parsed.header, parsed.body)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("wrong passphrase")
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("could not decrypt backup: ${e.message}")
        } finally {
            key.fill(0)
        }
    }

    private class Parsed(
        val kdf: Kdf,
        val salt: ByteArray,
        val nonce: ByteArray,
        val header: ByteArray,
        val body: ByteArray,
    )

    private fun parse(envelope: ByteArray): Parsed {
        val min = MAGIC.size + 1 + 1 + 4 + 4 + 4 + SALT_LEN + NONCE_LEN + TAG_BITS / 8
        if (envelope.size < min) throw IllegalArgumentException("encrypted backup is truncated")
        var offset = 0
        fun take(n: Int): ByteArray {
            if (offset + n > envelope.size) throw IllegalArgumentException("encrypted backup is truncated")
            val out = envelope.copyOfRange(offset, offset + n)
            offset += n
            return out
        }
        val magic = take(MAGIC.size)
        if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException("not an encrypted backup")
        if (take(1)[0] != VERSION) throw IllegalArgumentException("unsupported encrypted backup version")
        if (take(1)[0] != KDF_ARGON2ID) throw IllegalArgumentException("unsupported backup key derivation")
        val kdf = Kdf(int(take(4)), int(take(4)), int(take(4)))
        checkParams(kdf)
        val salt = take(SALT_LEN)
        val nonce = take(NONCE_LEN)
        val header = envelope.copyOfRange(0, offset)
        val body = envelope.copyOfRange(offset, envelope.size)
        if (body.size < TAG_BITS / 8) throw IllegalArgumentException("encrypted backup is truncated")
        return Parsed(kdf, salt, nonce, header, body)
    }

    private fun header(kdf: Kdf, salt: ByteArray, nonce: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(MAGIC.size + 1 + 1 + 4 + 4 + 4 + salt.size + nonce.size)
        buf.put(MAGIC)
        buf.put(VERSION)
        buf.put(KDF_ARGON2ID)
        buf.putInt(kdf.memoryKiB)
        buf.putInt(kdf.iterations)
        buf.putInt(kdf.parallelism)
        buf.put(salt)
        buf.put(nonce)
        return buf.array()
    }

    private fun int(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).int

    private fun checkParams(kdf: Kdf) {
        if (kdf.parallelism !in 1..4) throw IllegalArgumentException("encrypted backup has unsupported Argon2 parallelism")
        if (kdf.iterations !in 1..8) throw IllegalArgumentException("encrypted backup has unsupported Argon2 iterations")
        if (kdf.memoryKiB !in 32..(256 * 1024) || kdf.memoryKiB < 8 * kdf.parallelism) {
            throw IllegalArgumentException("encrypted backup has unsupported Argon2 memory")
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, kdf: Kdf): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(kdf.iterations)
            .withMemoryAsKB(kdf.memoryKiB)
            .withParallelism(kdf.parallelism)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val utf8 = String(passphrase).encodeToByteArray()
        val key = ByteArray(32)
        try {
            gen.generateBytes(utf8, key)
        } finally {
            utf8.fill(0)
        }
        return key
    }

    private fun aes(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }
}
