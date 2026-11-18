package org.oip.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.crypto.spec.SecretKeySpec

/**
 * SEC-031: rotating the root key and the key-encryption key re-wraps every stored secret without losing one, moves a
 * legacy store onto per-secret data keys, and never exposes a key or value.
 * Doc source: FEATURES SEC-031.
 */
class SecretKeyRingTest {
    private val gatewayKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val blobs = MemorySecretBlobStore()
    private val store = EncryptedSecretStore(blobs, gatewayKey)

    private fun versionOf(name: String): Int = java.nio.ByteBuffer.wrap(blobs.read(name)!!.ciphertext, 1, 4).int

    @Test fun rotationKeepsEverySecretAndMovesLegacyStoresOntoDataKeys() {
        store.putText("db-password", "p4ss-one", "database")
        store.putText("api-token", "tok-two")
        val legacyBlob = blobs.read("db-password")!!.ciphertext.copyOf()
        assertEquals(1, legacyBlob[0].toInt())
        assertNull(blobs.read(SecretKeyRing.ROW))

        val first = store.rotateKeys()
        assertEquals(2, first.secrets)
        assertEquals(2, first.migratedFromLegacy)
        assertEquals(1, first.rootVersion)
        assertEquals(1, first.kekVersion)
        assertEquals("p4ss-one", store.getText("db-password"))
        assertEquals("tok-two", store.getText("api-token"))
        assertEquals(SecretKeyRing.VERSION_WRAPPED, blobs.read("db-password")!!.ciphertext[0])
        assertEquals("database", store.list().first { it.name == "db-password" }.description)
        // The key row is not a secret: not listed, not readable, not deletable by name.
        assertEquals(setOf("db-password", "api-token"), store.list().map { it.name }.toSet())
        assertThrows(NotFoundException::class.java) { store.get(SecretKeyRing.ROW) }
        assertThrows(NotFoundException::class.java) { store.delete(SecretKeyRing.ROW) }

        // A second rotation moves every secret to a new KEK and a new root, and keeps the values.
        val before = blobs.read("api-token")!!.ciphertext.copyOf()
        val rootBefore = blobs.read(SecretKeyRing.ROW)!!.ciphertext.copyOf()
        val second = store.rotateKeys()
        assertEquals(0, second.migratedFromLegacy)
        assertEquals(2, second.rootVersion)
        assertEquals(2, second.kekVersion)
        assertEquals(2, versionOf("api-token"))
        assertFalse(before.contentEquals(blobs.read("api-token")!!.ciphertext))
        assertFalse(rootBefore.contentEquals(blobs.read(SecretKeyRing.ROW)!!.ciphertext))
        assertEquals("tok-two", store.getText("api-token"))

        // A secret written after rotation uses the current KEK and reads back.
        store.putText("later", "after")
        assertEquals(2, versionOf("later"))
        assertEquals("after", store.getText("later"))
        // Plaintext never appears in the stored bytes.
        for (row in blobs.list()) assertFalse(String(row.ciphertext, Charsets.ISO_8859_1).contains("p4ss-one"))
    }

    @Test fun rotatingOnlyTheRootKeepsTheKekAndOnlyTheKekKeepsTheRoot() {
        store.putText("a", "alpha")
        store.rotateKeys()
        val rootOnly = store.rotateKeys(rotateRoot = true, rotateKek = false)
        assertEquals(2, rootOnly.rootVersion)
        assertEquals(1, rootOnly.kekVersion)
        assertEquals(1, versionOf("a"))
        val kekOnly = store.rotateKeys(rotateRoot = false, rotateKek = true)
        assertEquals(2, kekOnly.rootVersion)
        assertEquals(2, kekOnly.kekVersion)
        assertEquals(2, versionOf("a"))
        assertEquals("alpha", store.getText("a"))
    }

    @Test fun anInterruptedRotationLosesNothingAndRerunFinishesIt() {
        store.putText("a", "alpha")
        store.putText("b", "bravo")
        store.rotateKeys()
        // Fail on the second blob rewrite during the next rotation.
        var writes = 0
        val flaky = object : SecretBlobStore by blobs {
            override fun upsert(name: String, description: String, ciphertext: ByteArray, nowMillis: Long) {
                if (name != SecretKeyRing.ROW && ++writes == 2) throw IllegalStateException("disk full")
                blobs.upsert(name, description, ciphertext, nowMillis)
            }
        }
        assertThrows(IllegalStateException::class.java) { EncryptedSecretStore(flaky, gatewayKey).rotateKeys() }
        // One secret is on the new KEK, one still on the old one; both still open.
        assertEquals("alpha", store.getText("a"))
        assertEquals("bravo", store.getText("b"))
        val done = store.rotateKeys(rotateRoot = false, rotateKek = false)
        assertEquals("alpha", store.getText("a"))
        assertEquals("bravo", store.getText("b"))
        assertEquals(done.kekVersion, versionOf("a"))
        assertEquals(done.kekVersion, versionOf("b"))
    }

    @Test fun aWrongGatewayKeyCannotOpenTheHierarchyOrTheSecrets() {
        store.putText("a", "alpha")
        store.rotateKeys()
        val other = EncryptedSecretStore(blobs, SecretKeySpec(ByteArray(32) { 9 }, "AES"))
        assertThrows(SecretException::class.java) { other.getText("a") }
        assertThrows(SecretException::class.java) { other.rotateKeys() }
        assertEquals("alpha", store.getText("a"))
    }
}
