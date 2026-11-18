package org.oip.backup

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.security.SecretKeyLoader
import org.oip.security.SecretKeySource
import org.oip.security.SecretsKeyFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * GAT-014 / GAT-032: `https/` and `opcua/` certificate material in the gateway backup. Private keys are
 * sealed under the secrets key and never appear in the archive in the clear.
 */
class CertificateBackupTest {
    @TempDir lateinit var tmp: Path
    private val opened = mutableListOf<AutoCloseable>()
    private val webKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
    private val uaKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
    private val pendingKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private
    private val storePass = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8"

    @AfterEach fun close() {
        opened.asReversed().forEach { runCatching { it.close() } }
    }

    private fun service(key: SecretKey?): BackupService {
        // The previous store still has the config journal open. Windows cannot rename that directory until it is closed.
        opened.asReversed().forEach { runCatching { it.close() } }
        opened.clear()
        val store = FileConfigStore(tmp.resolve("config"), SchemaRegistry()).also { opened += it }
        val db = RuntimeDatabase(tmp.resolve("db/runtime.db")).also { opened += it }
        return BackupService(tmp, store, db, "0.1.0-test", key?.let { k -> SecretKeySource { k } })
    }

    private fun randomKey(): SecretKey = SecretKeyLoader.fromBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun pem(type: String, der: ByteArray): String =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    /** A layout like the gateway writes: PEM web key, raw DER OPC UA key, a key store, and its password. */
    private fun seed(): Map<String, ByteArray> {
        val files = linkedMapOf(
            "https/server.crt" to pem("CERTIFICATE", byteArrayOf(0x30, 0x03, 1, 2, 3)).toByteArray(),
            "https/server.key" to pem("PRIVATE KEY", webKey.encoded).toByteArray(),
            "https/server.source" to "uploaded".toByteArray(),
            "https/tls.json" to """{"protocols":["TLSv1.3"]}""".toByteArray(),
            "https/pending.key" to pem("PRIVATE KEY", pendingKey.encoded).toByteArray(),
            "https/pending.csr" to pem("CERTIFICATE REQUEST", byteArrayOf(0x30, 0x01, 9)).toByteArray(),
            "opcua/server.crt" to byteArrayOf(0x30, 0x05, 7, 7, 7, 7, 7),
            "opcua/server.key" to uaKey.encoded,
            "opcua/server.pfx" to byteArrayOf(0x30, 0x10) + uaKey.encoded.copyOfRange(0, 64),
            "opcua/store.pass" to storePass.toByteArray(),
            "opcua/pki/trusted/certs/client.der" to byteArrayOf(0x30, 0x02, 4, 4),
        )
        for ((rel, bytes) in files) tmp.resolve(rel).also { it.parent.createDirectories() }.writeBytes(bytes)
        tmp.resolve("https/incoming").createDirectories().resolve("private-key.pem")
            .writeText(pem("PRIVATE KEY", ByteArray(48) { 5 }))
        return files
    }

    @Test fun archiveHoldsNoPlaintextPrivateKey() {
        seed()
        val archive = service(randomKey()).create()
        val entries = ZipIo.read(archive)
        val secrets = listOf(
            webKey.encoded, uaKey.encoded, pendingKey.encoded,
            uaKey.encoded.copyOfRange(0, 64), storePass.toByteArray(),
            Base64.getEncoder().encodeToString(webKey.encoded).take(60).toByteArray(),
        )
        val blobs = listOf(archive) + entries.values
        for (blob in blobs) {
            assertFalse(contains(blob, "PRIVATE KEY".toByteArray()), "PEM private-key marker in archive")
            for (s in secrets) assertFalse(contains(blob, s), "raw key bytes in archive")
        }
        assertTrue(entries.keys.none { it.startsWith("certs/") && (it.endsWith(".key") || it.endsWith(".pfx") || it.endsWith(".pass")) })
        assertTrue(entries.containsKey("sealed/https/server.key"))
        assertTrue(entries.containsKey("sealed/opcua/server.key"))
        assertTrue(entries.containsKey("sealed/opcua/server.pfx"))
        assertTrue(entries.containsKey("sealed/opcua/store.pass"))
        assertTrue(entries.containsKey("sealed/https/pending.key"))
        assertTrue(entries.containsKey("certs/https/server.crt"))
        assertTrue(entries.containsKey("certs/opcua/pki/trusted/certs/client.der"))
        assertTrue(entries.keys.none { it.contains("incoming") }, "transient drop area is not archived")
    }

    @Test fun backupWipeRestoreBringsTheSameFilesBack() {
        val files = seed()
        val key = randomKey()
        val svc = service(key)
        var reloads = 0
        svc.afterRestore = { reloads++ }
        val archive = svc.create()
        svc.wipe()
        assertFalse(tmp.resolve("https").exists())
        assertFalse(tmp.resolve("opcua").exists())
        svc.restore(archive)
        for ((rel, bytes) in files) assertArrayEquals(bytes, tmp.resolve(rel).readBytes(), rel)
        assertEquals(1, reloads)
        val posix = tmp.fileSystem.supportedFileAttributeViews().contains("posix")
        val owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (posix) {
            assertEquals(owner, Files.getPosixFilePermissions(tmp.resolve("https/server.key")))
            assertEquals(owner, Files.getPosixFilePermissions(tmp.resolve("opcua/server.key")))
        }
        val ownerDir = owner + PosixFilePermission.OWNER_EXECUTE
        for (drop in listOf("https/incoming", "https/rejected")) {
            val dir = tmp.resolve(drop)
            assertTrue(Files.isDirectory(dir), "$drop is recreated")
            Files.list(dir).use { assertEquals(0L, it.count(), "$drop is empty") }
            if (posix) assertEquals(ownerDir, Files.getPosixFilePermissions(dir), drop)
        }
        Files.list(tmp).use { s -> assertTrue(s.noneMatch { it.fileName.toString().startsWith(".restore-") }) }
    }

    @Test fun symbolicLinksAreNotFollowedIntoTheArchive() {
        seed()
        val outside = tmp.resolve("outside").createDirectories()
        val secretFile = outside.resolve("host.key").also { it.writeText(pem("PRIVATE KEY", ByteArray(40) { 3 })) }
        outside.resolve("host.crt").writeText(pem("CERTIFICATE", byteArrayOf(0x30, 0x01, 8)))
        Files.createSymbolicLink(tmp.resolve("https/linked.key"), secretFile)
        Files.createSymbolicLink(tmp.resolve("https/linked.crt"), outside.resolve("host.crt"))
        Files.createSymbolicLink(tmp.resolve("opcua/pki/linked-dir"), outside)
        val entries = ZipIo.read(service(randomKey()).create())
        assertTrue(entries.keys.none { it.contains("linked") }, entries.keys.toString())
        assertTrue(entries.containsKey("sealed/https/server.key"), "regular files are still archived")

        // A linked top-level directory is not walked either.
        val linkedRoot = tmp.resolve("linked-root").createDirectories()
        linkedRoot.resolve("server.crt").writeText(pem("CERTIFICATE", byteArrayOf(0x30, 0x01, 6)))
        deleteTree(tmp.resolve("opcua"))
        Files.createSymbolicLink(tmp.resolve("opcua"), linkedRoot)
        val second = ZipIo.read(service(randomKey()).create())
        assertTrue(second.keys.none { it.contains("opcua/") }, second.keys.toString())
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    @Test fun archiveSecretsKeyOpensTheSealedEntriesOnAFreshGateway() {
        val files = seed()
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val keyFile = SecretsKeyFile.path(tmp)
        keyFile.parent.createDirectories()
        keyFile.writeText(Base64.getEncoder().encodeToString(raw) + "\n")
        val archive = service(SecretKeyLoader.fromBytes(raw)).create()
        assertArrayEquals(keyFile.readBytes(), ZipIo.read(archive).getValue("secrets.key"))
        service(null).wipe()
        assertFalse(keyFile.exists())
        // The live gateway has no key source at all; the key carried in the archive opens the entries.
        service(null).restore(archive)
        assertArrayEquals(files.getValue("https/server.key"), tmp.resolve("https/server.key").readBytes())
        assertArrayEquals(Base64.getEncoder().encodeToString(raw).plus("\n").toByteArray(), keyFile.readBytes())
    }

    @Test fun withoutAKeySourceTheGatewayKeyFileSealsTheEntries() {
        val files = seed()
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        SecretsKeyFile.path(tmp).also { it.parent.createDirectories() }.writeBytes(raw)
        val entries = ZipIo.read(service(null).create())
        assertTrue(entries.containsKey("sealed/https/server.key"))
        assertFalse(tmp.resolve("secrets.key").exists(), "the unused dataDir/secrets.key path is not written")
        service(null).wipe()
        service(SecretKeyLoader.fromBytes(raw)).restore(ZipIo.write(entries.toList()))
        assertArrayEquals(files.getValue("opcua/server.key"), tmp.resolve("opcua/server.key").readBytes())
    }

    @Test fun certificateOnlyArchiveKeepsTheLiveKeyDirectories() {
        val files = seed()
        val archive = service(null).create()
        assertTrue(ZipIo.read(archive).keys.none { it.startsWith("sealed/") })
        // The live material changes after the backup; a certificate-only restore must not touch it.
        tmp.resolve("https/tls.json").writeText("""{"protocols":["TLSv1.2"]}""")
        service(null).restore(archive)
        assertArrayEquals(files.getValue("https/server.key"), tmp.resolve("https/server.key").readBytes())
        assertArrayEquals(files.getValue("https/server.crt"), tmp.resolve("https/server.crt").readBytes())
        assertEquals("""{"protocols":["TLSv1.2"]}""", Files.readString(tmp.resolve("https/tls.json")), "https/ was left as it was")
        assertArrayEquals(files.getValue("opcua/server.key"), tmp.resolve("opcua/server.key").readBytes())
        assertArrayEquals(files.getValue("opcua/store.pass"), tmp.resolve("opcua/store.pass").readBytes())

        // A complete https/ next to a certificate-only opcua/ restores https/ and keeps opcua/.
        val key = randomKey()
        val full = ZipIo.read(service(key).create())
        val mixed = full.filterKeys { !it.startsWith("sealed/opcua/") }
        tmp.resolve("https/tls.json").writeText("changed")
        tmp.resolve("opcua/store.pass").writeText("live-pass")
        service(key).restore(ZipIo.write(mixed.toList()))
        assertEquals("""{"protocols":["TLSv1.2"]}""", Files.readString(tmp.resolve("https/tls.json")), "https/ came from the archive")
        assertEquals("live-pass", Files.readString(tmp.resolve("opcua/store.pass")))
    }

    @Test fun wrongKeyRefusesBeforeAnythingChanges() {
        seed()
        val archive = service(randomKey()).create()
        tmp.resolve("https/server.key").writeText("live")
        val refused = assertThrows<BackupException> { service(randomKey()).restore(archive) }
        assertTrue(refused.message!!.contains("different secrets key"), refused.message)
        assertEquals("live", Files.readString(tmp.resolve("https/server.key")))
    }

    @Test fun withoutAKeyOnlyPublicCertificatesAreArchived() {
        seed()
        val entries = ZipIo.read(service(null).create())
        assertTrue(entries.keys.none { it.startsWith("sealed/") })
        assertTrue(entries.containsKey("certs/https/server.crt"))
        assertFalse(entries.keys.any { it.endsWith(".key") || it.endsWith("store.pass") })
        @Suppress("UNCHECKED_CAST")
        val manifest = ObjectMapper().readValue(entries.getValue("oip-backup.json"), Map::class.java) as Map<String, Any?>
        assertEquals(null, manifest["certificateKeyCheck"])
    }

    @Test fun tamperedOrPlaintextKeyEntriesAreRejected() {
        seed()
        val key = randomKey()
        val entries = ZipIo.read(service(key).create())
        val flipped = LinkedHashMap(entries)
        flipped["sealed/https/server.key"] = entries.getValue("sealed/https/server.key").copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows<BackupException> { service(key).restore(ZipIo.write(flipped.toList())) }

        val moved = LinkedHashMap(entries)
        moved.remove("sealed/https/server.key")
        moved["sealed/https/other.key"] = entries.getValue("sealed/https/server.key")
        assertThrows<BackupException> { service(key).restore(ZipIo.write(moved.toList())) }

        val clear = LinkedHashMap(entries)
        clear["certs/https/server.crt"] = pem("PRIVATE KEY", webKey.encoded).toByteArray()
        val plain = assertThrows<BackupException> { service(key).restore(ZipIo.write(clear.toList())) }
        assertTrue(plain.message!!.contains("in the clear"), plain.message)

        val collide = LinkedHashMap(entries)
        collide["certs/https/a.crt"] = byteArrayOf(0x30, 0x01, 1)
        collide["certs/https/a.crt/b.crt"] = byteArrayOf(0x30, 0x01, 2)
        val collision = assertThrows<BackupException> { service(key).restore(ZipIo.write(collide.toList())) }
        assertTrue(collision.message!!.contains("conflicting"), collision.message)

        val escape = LinkedHashMap(entries)
        escape["certs/config/x.crt"] = byteArrayOf(1)
        assertThrows<BackupException> { service(key).restore(ZipIo.write(escape.toList())) }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
