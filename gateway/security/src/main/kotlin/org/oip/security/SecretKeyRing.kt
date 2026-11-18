package org.oip.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key hierarchy for stored secrets (SEC-031). Doc source: FEATURES SEC-031.
 *
 * ```
 * gateway key (secrets/gateway.key)  wraps  root key  wraps  key-encryption keys (KEKs)  wrap  per-secret data keys
 * ```
 *
 * The hierarchy lives in one reserved row of the secret blob store ([ROW], not a valid secret name), so a backup of
 * the secret store carries it. The root key is wrapped by the gateway key, the KEK set by the root key, and each
 * secret's random data key by a KEK ([VERSION_WRAPPED] blobs). A store with no [ROW] is a legacy store: secrets are
 * encrypted directly under the gateway key ([EncryptedSecretStore] version 1) until the first rotation.
 *
 * [rotate] makes a new root key, a new KEK, or both, and re-wraps every secret under the new KEK. It writes the new
 * KEK next to the old ones first, re-wraps every secret, checks that every secret opens under the new KEK alone, and
 * only then drops the old KEKs, so an interruption never leaves a secret without a key; running it again finishes the
 * job. The gateway key itself is not rotated here: it also derives the audit chain and backup sealing keys.
 */
internal object SecretKeyRing {
    /** Not matched by [SecretReferences.validName], so it can never collide with a secret. */
    const val ROW = "_keys"
    const val VERSION_WRAPPED: Byte = 2
    private const val ROW_VERSION: Byte = 1
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_LEN = 32
    private const val WRAPPED_LEN = KEY_LEN + TAG_BITS / 8
    private val random = SecureRandom()

    class Keys(val rootVersion: Int, val root: SecretKey, val keks: List<Pair<Int, SecretKey>>) {
        val current: Pair<Int, SecretKey> get() = keks.first()
        fun kek(version: Int): SecretKey? = keks.firstOrNull { it.first == version }?.second
    }

    data class Rotation(val secrets: Int, val migratedFromLegacy: Int, val rootVersion: Int, val kekVersion: Int)

    fun load(blobs: SecretBlobStore, gatewayKey: SecretKey): Keys? {
        val row = blobs.read(ROW) ?: return null
        val b = row.ciphertext
        try {
            if (b.size < 3 || b[0] != ROW_VERSION) throw SecretException("secret key hierarchy has an unknown format")
            val aLen = ((b[1].toInt() and 0xff) shl 8) or (b[2].toInt() and 0xff)
            val a = open(gatewayKey, "oip-keys-root", b.copyOfRange(3, 3 + aLen))
            val rootVersion = ByteBuffer.wrap(a, 0, 4).int
            val root = SecretKeySpec(a.copyOfRange(4, 4 + KEY_LEN), "AES")
            val kekBytes = open(root, "oip-keys-kek", b.copyOfRange(3 + aLen, b.size))
            val n = kekBytes[0].toInt()
            val keks = (0 until n).map { i ->
                val off = 1 + i * (4 + KEY_LEN)
                ByteBuffer.wrap(kekBytes, off, 4).int to SecretKeySpec(kekBytes.copyOfRange(off + 4, off + 4 + KEY_LEN), "AES") as SecretKey
            }
            if (keks.isEmpty()) throw SecretException("secret key hierarchy holds no key-encryption key")
            return Keys(rootVersion, root, keks)
        } catch (e: SecretException) {
            throw e
        } catch (e: Exception) {
            throw SecretException("secret key hierarchy could not be opened with the gateway key", e)
        }
    }

    private fun store(blobs: SecretBlobStore, gatewayKey: SecretKey, keys: Keys, nowMillis: Long) {
        val a = ByteBuffer.allocate(4 + KEY_LEN).putInt(keys.rootVersion).put(keys.root.encoded).array()
        val sealedA = seal(gatewayKey, "oip-keys-root", a)
        val k = ByteBuffer.allocate(1 + keys.keks.size * (4 + KEY_LEN)).put(keys.keks.size.toByte())
        for ((v, key) in keys.keks) k.putInt(v).put(key.encoded)
        val sealedB = seal(keys.root, "oip-keys-kek", k.array())
        val out = byteArrayOf(ROW_VERSION, (sealedA.size shr 8).toByte(), sealedA.size.toByte()) + sealedA + sealedB
        blobs.upsert(ROW, "key hierarchy", out, nowMillis)
    }

    /** A version 2 secret blob: kek version, wrapped data key, then the payload under the data key. */
    fun encrypt(keys: Keys, name: String, plaintext: ByteArray): ByteArray {
        val dek = SecretKeySpec(ByteArray(KEY_LEN).also { random.nextBytes(it) }, "AES")
        return assemble(keys.current.first, seal(keys.current.second, "wrap:$name", dek.encoded), seal(dek, name, plaintext))
    }

    fun decrypt(keys: Keys, name: String, blob: ByteArray): ByteArray {
        val (kekVersion, wrapped, payload) = split(name, blob)
        val kek = keys.kek(kekVersion) ?: throw SecretException("secret '$name' uses an unknown key-encryption key")
        val dek = SecretKeySpec(open(kek, "wrap:$name", wrapped), "AES")
        return open(dek, name, payload)
    }

    /** Re-wrap a version 2 blob's data key under [target]; the payload bytes are unchanged. */
    private fun rewrap(keys: Keys, name: String, blob: ByteArray, target: Pair<Int, SecretKey>): ByteArray {
        val (kekVersion, wrapped, payload) = split(name, blob)
        val kek = keys.kek(kekVersion) ?: throw SecretException("secret '$name' uses an unknown key-encryption key")
        val dek = open(kek, "wrap:$name", wrapped)
        return assemble(target.first, seal(target.second, "wrap:$name", dek), payload)
    }

    private fun assemble(kekVersion: Int, wrapped: ByteArray, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(1 + 4 + wrapped.size + payload.size).put(VERSION_WRAPPED).putInt(kekVersion).put(wrapped).put(payload).array()

    private fun split(name: String, blob: ByteArray): Triple<Int, ByteArray, ByteArray> {
        val wrappedTotal = IV_LEN + WRAPPED_LEN
        if (blob.size < 1 + 4 + wrappedTotal + IV_LEN + TAG_BITS / 8 || blob[0] != VERSION_WRAPPED) {
            throw SecretException("secret '$name' has an unknown ciphertext format")
        }
        val kekVersion = ByteBuffer.wrap(blob, 1, 4).int
        return Triple(kekVersion, blob.copyOfRange(5, 5 + wrappedTotal), blob.copyOfRange(5 + wrappedTotal, blob.size))
    }

    fun rotate(
        blobs: SecretBlobStore,
        gatewayKey: SecretKey,
        legacy: (String, ByteArray) -> ByteArray,
        rotateRoot: Boolean,
        rotateKek: Boolean,
        nowMillis: Long,
    ): Rotation {
        val existing = load(blobs, gatewayKey)
        val firstTime = existing == null
        val root = if (firstTime || rotateRoot) newKey() else existing!!.root
        val rootVersion = if (firstTime) 1 else existing!!.rootVersion + if (rotateRoot) 1 else 0
        val oldKeks = existing?.keks.orEmpty()
        val keks = if (firstTime || rotateKek) listOf((oldKeks.maxOfOrNull { it.first } ?: 0) + 1 to newKey()) + oldKeks else oldKeks
        // Phase 1: the new keys are stored beside every old KEK, so nothing written below can be orphaned.
        val wide = Keys(rootVersion, root, keks)
        store(blobs, gatewayKey, wide, nowMillis)
        var migrated = 0
        var count = 0
        for (row in blobs.list()) {
            if (row.name == ROW) continue
            count++
            val blob = row.ciphertext
            val updated = when {
                blob.isNotEmpty() && blob[0] == VERSION_WRAPPED -> {
                    val have = split(row.name, blob).first
                    if (have == wide.current.first) null else rewrap(wide, row.name, blob, wide.current)
                }
                else -> {
                    migrated++
                    encrypt(wide, row.name, legacy(row.name, blob))
                }
            }
            if (updated != null) blobs.upsert(row.name, row.description, updated, nowMillis)
        }
        // Every secret must open under the new KEK alone before the old ones are dropped.
        val narrow = Keys(rootVersion, root, listOf(wide.current))
        for (row in blobs.list()) {
            if (row.name == ROW) continue
            decrypt(narrow, row.name, row.ciphertext).fill(0)
        }
        store(blobs, gatewayKey, narrow, nowMillis)
        return Rotation(count, migrated, rootVersion, wide.current.first)
    }

    private fun newKey(): SecretKey = SecretKeySpec(ByteArray(KEY_LEN).also { random.nextBytes(it) }, "AES")

    private fun seal(key: SecretKey, aad: String, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return iv + cipher.doFinal(plaintext)
    }

    private fun open(key: SecretKey, aad: String, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_LEN)))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
    }
}

/** Result of [EncryptedSecretStore.rotateKeys]. No key or secret value is included. */
data class SecretKeyRotation(val secrets: Int, val migratedFromLegacy: Int, val rootVersion: Int, val kekVersion: Int)
