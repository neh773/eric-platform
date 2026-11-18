package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.backup.BackupService
import org.oip.backup.RuntimeDatabase
import org.oip.backup.ZipIo
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.security.SecretKeyLoader
import org.oip.security.SecretsKeyFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * The key the gateway writes is the key a backup carries. A second gateway opened on a restored
 * data directory reads the stored secret, and the audit chain verifies.
 */
class SecretsKeyGatewayTest {
    @TempDir lateinit var tmp: Path

    @Test fun backupContainsTheKeyAndAFreshGatewayReadsTheSecret() {
        val source = tmp.resolve("source")
        val restored = tmp.resolve("restored")
        val plaintext = "stored-db-secret"
        lateinit var archive: ByteArray
        lateinit var keyBytes: ByteArray
        Gateway(source, port = 0, env = emptyMap()).use { g ->
            g.start()
            g.security.secrets.putText("db-password", plaintext)
            val before = g.security.audit.verify()
            assertTrue(before.ok, before.reason)
            val keyFile = SecretsKeyFile.path(g.dataDir)
            assertPosix(keyFile, SecretsKeyFile.FILE_MODE)
            assertPosix(keyFile.parent, SecretsKeyFile.DIRECTORY_MODE)
            keyBytes = keyFile.readBytes()
            archive = g.backups.create(bundleModules = true)
            val entries = ZipIo.read(archive)
            assertTrue(entries.containsKey("secrets.key"), "archive is missing the secrets key entry")
            assertFalse(entries.containsKey("secrets/gateway.key"))
            assertTrue(
                entries.getValue("secrets.key").contentEquals(keyBytes),
                "backup entry secrets.key does not match secrets/gateway.key",
            )
            val stray = source.resolve("secrets.key")
            stray.writeBytes(byteArrayOf(7))
            g.backups.wipe()
            assertFalse(keyFile.exists(), "wipe left secrets/gateway.key")
            assertFalse(stray.exists(), "wipe left a stray secrets.key")
        }

        // Files land before the second process opens, which is when a fresh gateway would create a key.
        restoreArchive(restored, archive)

        Gateway(restored, port = 0, env = emptyMap()).use { g ->
            g.start()
            assertEquals(plaintext, g.security.secrets.getText("db-password"))
            val verified = g.security.audit.verify()
            assertTrue(verified.ok, verified.reason)
            assertTrue(
                SecretsKeyFile.path(restored).readBytes().contentEquals(keyBytes),
                "restored gateway is not using the backed-up secrets key",
            )
            assertPosix(SecretsKeyFile.path(restored), SecretsKeyFile.FILE_MODE)
            assertPosix(restored.resolve("secrets"), SecretsKeyFile.DIRECTORY_MODE)
        }
    }

    @Test fun straySecretsKeyIsMigratedAndAnExistingKeyIsKept() {
        val dir = tmp.resolve("migrate")
        dir.createDirectories()
        val raw = ByteArray(32) { 9 }
        dir.resolve("secrets.key").writeBytes(raw)
        Gateway(dir, port = 0, env = emptyMap()).use { g ->
            g.security.secrets.putText("db-password", "from-stray")
        }
        assertFalse(dir.resolve("secrets.key").exists())
        val moved = SecretsKeyFile.path(dir)
        assertTrue(moved.exists())
        assertTrue(moved.readBytes().contentEquals(raw), "startup did not keep the stray secrets key")
        assertPosix(moved, SecretsKeyFile.FILE_MODE)
        assertPosix(moved.parent, SecretsKeyFile.DIRECTORY_MODE)
        Gateway(dir, port = 0, env = emptyMap()).use { g ->
            assertEquals("from-stray", g.security.secrets.getText("db-password"))
            val verified = g.security.audit.verify()
            assertTrue(verified.ok, verified.reason)
        }

        val kept = moved.readBytes()
        dir.resolve("secrets.key").writeBytes(ByteArray(32) { 4 })
        Gateway(dir, port = 0, env = emptyMap()).use { g ->
            assertEquals("from-stray", g.security.secrets.getText("db-password"))
        }
        assertTrue(dir.resolve("secrets.key").exists(), "a stray file must not replace secrets/gateway.key")
        assertTrue(moved.readBytes().contentEquals(kept), "startup replaced an existing secrets key")
    }

    @Test fun environmentKeyAndKeyFileAreHonoured() {
        val inline = ByteArray(32) { 8 }
        val inlineText = Base64.getEncoder().encodeToString(inline)
        val inlineDir = tmp.resolve("inline")
        Gateway(inlineDir, port = 0, env = mapOf(SecretKeyLoader.ENV_KEY to inlineText)).use { g ->
            g.security.secrets.putText("mqtt-pass", "from-env")
        }
        assertFalse(SecretsKeyFile.path(inlineDir).exists(), "OIP_SECRETS_KEY created secrets/gateway.key")
        Gateway(inlineDir, port = 0, env = mapOf(SecretKeyLoader.ENV_KEY to inlineText)).use { g ->
            assertEquals("from-env", g.security.secrets.getText("mqtt-pass"))
            val verified = g.security.audit.verify()
            assertTrue(verified.ok, verified.reason)
        }

        val fileKey = ByteArray(32) { 6 }
        val file = tmp.resolve("external.key")
        file.writeBytes(fileKey)
        val fileDir = tmp.resolve("from-file")
        Gateway(fileDir, port = 0, env = mapOf(SecretKeyLoader.ENV_FILE to file.toString())).use { g ->
            g.security.secrets.putText("smtp-pass", "from-file")
        }
        assertFalse(SecretsKeyFile.path(fileDir).exists(), "OIP_SECRETS_KEY_FILE also wrote secrets/gateway.key")
        assertTrue(file.readBytes().contentEquals(fileKey), "OIP_SECRETS_KEY_FILE replaced the supplied key file")
        Gateway(fileDir, port = 0, env = mapOf(SecretKeyLoader.ENV_FILE to file.toString())).use { g ->
            assertEquals("from-file", g.security.secrets.getText("smtp-pass"))
            val verified = g.security.audit.verify()
            assertTrue(verified.ok, verified.reason)
        }

        val bothDir = tmp.resolve("inline-wins")
        val other = tmp.resolve("other.key")
        val otherKey = ByteArray(32) { 3 }
        other.writeBytes(otherKey)
        Gateway(bothDir, port = 0, env = mapOf(
            SecretKeyLoader.ENV_KEY to inlineText,
            SecretKeyLoader.ENV_FILE to other.toString(),
        )).use { g ->
            g.security.secrets.putText("notify-token", "inline-wins")
        }
        assertTrue(other.readBytes().contentEquals(otherKey), "inline key overwrote OIP_SECRETS_KEY_FILE")
        Gateway(bothDir, port = 0, env = mapOf(SecretKeyLoader.ENV_KEY to inlineText)).use { g ->
            assertEquals("inline-wins", g.security.secrets.getText("notify-token"))
        }
    }

    /** Put the archive on disk before the second gateway constructs its key. */
    private fun restoreArchive(dir: Path, archive: ByteArray) {
        dir.createDirectories()
        val store = FileConfigStore(dir.resolve("config"), SchemaRegistry())
        val runtime = RuntimeDatabase(dir.resolve("db/runtime.db"))
        try {
            BackupService(dir, store, runtime, Gateway.VERSION).restore(archive)
        } finally {
            runtime.close()
            store.close()
        }
    }

    private fun assertPosix(path: Path, expected: Set<PosixFilePermission>) {
        if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
        assertEquals(expected, Files.getPosixFilePermissions(path))
    }
}
