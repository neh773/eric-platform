package org.oip.backup

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer

/** AES-256-GCM backup envelope. The fast KDF is only for these tests; the CLI uses [BackupCipher.PRODUCTION]. */
class BackupCipherTest {
    private val fast = BackupCipher.Kdf(memoryKiB = 32, iterations = 1, parallelism = 1)

    @Test fun roundTrip() {
        val plain = "zip-bytes-\u0000".toByteArray(Charsets.UTF_8)
        val enc = BackupCipher.encrypt(plain, "correct horse".toCharArray(), fast)
        assertTrue(BackupCipher.isEncrypted(enc))
        assertFalse(BackupCipher.isEncrypted(plain))
        assertFalse(enc[0] == 'P'.code.toByte())
        assertArrayEquals(plain, BackupCipher.decrypt(enc, "correct horse".toCharArray()))
    }

    @Test fun wrongPassphraseIsRejected() {
        val enc = BackupCipher.encrypt(byteArrayOf(1, 2, 3, 4), "right".toCharArray(), fast)
        val failed = assertThrows<IllegalArgumentException> {
            BackupCipher.decrypt(enc, "wrong".toCharArray())
        }
        assertEquals("wrong passphrase", failed.message)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), BackupCipher.decrypt(enc, "right".toCharArray()))
    }

    @Test fun tamperAndTruncationFail() {
        val enc = BackupCipher.encrypt(byteArrayOf(9), "pw".toCharArray(), fast)
        val tampered = enc.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        assertEquals("wrong passphrase", assertThrows<IllegalArgumentException> {
            BackupCipher.decrypt(tampered, "pw".toCharArray())
        }.message)
        val truncated = assertThrows<IllegalArgumentException> {
            BackupCipher.decrypt(enc.copyOf(8), "pw".toCharArray())
        }
        assertTrue(truncated.message!!.contains("truncated"))
    }

    @Test fun emptyPassphraseRejected() {
        assertThrows<IllegalArgumentException> { BackupCipher.encrypt(byteArrayOf(1), charArrayOf(), fast) }
        val enc = BackupCipher.encrypt(byteArrayOf(1), "x".toCharArray(), fast)
        assertThrows<IllegalArgumentException> { BackupCipher.decrypt(enc, charArrayOf()) }
    }

    @Test fun defaultKeyDerivationIsOwaspArgon2id() {
        val enc = BackupCipher.encrypt(byteArrayOf(1, 2), "prod-pass".toCharArray())
        val memory = ByteBuffer.wrap(enc, 10, 4).int
        val iterations = ByteBuffer.wrap(enc, 14, 4).int
        val parallelism = ByteBuffer.wrap(enc, 18, 4).int
        assertEquals(19 * 1024, memory)
        assertEquals(2, iterations)
        assertEquals(1, parallelism)
        assertArrayEquals(byteArrayOf(1, 2), BackupCipher.decrypt(enc, "prod-pass".toCharArray()))
    }
}
