package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.backup.BackupCipher
import org.oip.backup.ContentHash
import org.oip.backup.ZipIo
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.AuditQuery
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Review-1 F3 (SPEC §4, SEC-023): a backup created through REST or the admin UI, whether stored under
 * `<dataDir>/backups/` or downloaded, never carries `secrets.key` in clear. Every one is sealed with the
 * passphrase envelope ([BackupCipher]); restore with the right passphrase round-trips, and a wrong one is
 * refused without changing anything.
 */
class BackupEncryptionGatewayTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private val id = ResourceId("m", Scope.GATEWAY, "Thing", "a")

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0)
        gw.store.registry.register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
        gw.start()
        gw.security.secrets.putText("plc-password", "s3cret-value")
        gw.store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 1)))
        admin = Http(gw.port())
        gw.loginAdmin(admin)
    }

    @AfterEach fun tearDown() = gw.close()

    private val keyFile: Path get() = tmp.resolve("secrets/gateway.key")

    @Test fun restBackupHoldsNoPlaintextSecretsKeyInTheResponseOrOnDisk() {
        val keyBytes = keyFile.readBytes()
        assertTrue(keyBytes.isNotEmpty())
        val created = admin.createBackup()
        assertEquals(200, created.status, created.body)
        val name = created.header("X-Backup-Name")!!
        assertTrue(name.endsWith(".oipbk"), name)
        assertTrue(created.header("Content-Type")!!.startsWith("application/octet-stream"))

        val stored = tmp.resolve("backups").listDirectoryEntries()
        assertEquals(listOf(name), stored.map { it.name })
        val onDisk = stored.single().readBytes()
        assertArrayEquals(created.raw, onDisk)
        val downloaded = admin.get("/api/v1/backups/$name")
        assertEquals(200, downloaded.status)
        assertArrayEquals(onDisk, downloaded.raw)

        for (bytes in listOf(created.raw, onDisk, downloaded.raw)) {
            assertTrue(BackupCipher.isEncrypted(bytes))
            assertFalse(contains(bytes, keyBytes), "archive holds the secrets key bytes")
            assertFalse(contains(bytes, "secrets.key".toByteArray()), "archive shows its zip entry names")
            assertFalse(contains(bytes, "PK\u0003\u0004".toByteArray()), "archive holds a plaintext zip")
            assertFalse(contains(bytes, "s3cret-value".toByteArray()))
        }
        // The right passphrase opens it, and the archive inside does carry the key for a restore.
        assertArrayEquals(keyBytes, ZipIo.read(openBackup(onDisk)).getValue("secrets.key"))

        val listed = admin.get("/api/v1/backups").json["backups"] as List<*>
        val item = listed.single() as Map<*, *>
        assertEquals(name, item["name"])
        assertEquals(onDisk.size.toLong(), (item["bytes"] as Number).toLong())
        assertEquals(true, item["encrypted"])
    }

    @Test fun createWithoutAUsablePassphraseStoresNothing() {
        assertProblem(admin.send("POST", "/api/v1/backups"), 400)
        assertProblem(admin.post("/api/v1/backups", "{}"), 422)
        assertProblem(admin.post("/api/v1/backups", """{"passphrase":""}"""), 422)
        val short = admin.createBackup(passphrase = "elevenchars")
        assertProblem(short, 422)
        assertTrue(short.body.contains("at least 12"), short.body)
        assertFalse(tmp.resolve("backups").exists() && tmp.resolve("backups").listDirectoryEntries().isNotEmpty())
        assertEquals(0, gw.security.audit.query(AuditQuery(action = "gateway backup created", limit = 10)).records.size)
    }

    @Test fun storedAndUploadedRestoreRoundTripWithTheRightPassphrase() {
        val name = admin.createBackup().header("X-Backup-Name")!!
        val sealed = admin.get("/api/v1/backups/$name").raw
        val configHash = ContentHash.config(gw.store.root)
        val keyBytes = keyFile.readBytes()

        gw.store.put(Scope.GATEWAY, gw.store.get(id)!!.let { Resource("Thing", it.metadata, mapOf("x" to 2)) })
        val restored = admin.post("/api/v1/backups/$name/restore", Json.write(mapOf("passphrase" to TEST_BACKUP_PASSPHRASE)))
        assertEquals(200, restored.status, restored.body)
        assertEquals(configHash, ContentHash.config(gw.store.root))
        assertEquals(1, gw.store.get(id)!!.spec["x"])

        gw.backups.wipe()
        assertFalse(keyFile.exists())
        // Wipe removes users and roles with the config tree. Seed the administrator role again so the upload can authenticate.
        if (gw.security.roles.get(DefaultRoles.ADMINISTRATOR) == null) {
            gw.security.roles.create(DefaultRoles.ADMINISTRATOR, Permissions.ALL, actor = "test")
        }
        gw.loginAdmin(Http(gw.port()).also { admin = it })
        val uploaded = admin.restoreBackup(sealed)
        assertEquals(200, uploaded.status, uploaded.body)
        assertEquals(configHash, ContentHash.config(gw.store.root))
        assertArrayEquals(keyBytes, keyFile.readBytes())
    }

    @Test fun wrongOrMissingPassphraseIsRefusedAndChangesNothing() {
        val name = admin.createBackup().header("X-Backup-Name")!!
        val sealed = admin.get("/api/v1/backups/$name").raw
        gw.store.put(Scope.GATEWAY, gw.store.get(id)!!.let { Resource("Thing", it.metadata, mapOf("x" to 2)) })
        val configBefore = snapshot(gw.store.root)
        val keyBefore = keyFile.readBytes()
        val restoredRows = { gw.security.audit.query(AuditQuery(action = "gateway backup restored", limit = 10)).records.size }

        val wrongStored = admin.post("/api/v1/backups/$name/restore", Json.write(mapOf("passphrase" to "not the passphrase")))
        assertProblem(wrongStored, 422)
        assertTrue(wrongStored.body.contains("wrong passphrase"), wrongStored.body)
        assertProblem(admin.restoreBackup(sealed, passphrase = "not the passphrase"), 422)
        assertProblem(admin.send("POST", "/api/v1/backups/$name/restore"), 422)
        assertProblem(admin.restoreBackup(sealed, passphrase = null), 422)
        val raw = admin.postRaw("/api/v1/backups/restore", sealed, "application/octet-stream")
        assertProblem(raw, 422)
        assertTrue(raw.body.contains("passphrase is required"), raw.body)

        assertFiles(configBefore, snapshot(gw.store.root))
        assertEquals(2, gw.store.get(id)!!.spec["x"])
        assertArrayEquals(keyBefore, keyFile.readBytes())
        assertEquals(0, restoredRows())
        assertTrue(Files.list(tmp).use { s -> s.noneMatch { it.fileName.toString().startsWith(".restore-") } })
    }

    /** A plaintext `.zip` stored by an older version is listed and restorable, but never served for download. */
    @Test fun legacyPlaintextBackupIsNeverDownloadable() {
        val plain = gw.backups.create()
        val legacy = tmp.resolve("backups").also { Files.createDirectories(it) }.resolve("20260101-000000-000.zip")
        Files.write(legacy, plain)
        val listed = admin.get("/api/v1/backups").json["backups"] as List<*>
        val item = listed.single() as Map<*, *>
        assertEquals(legacy.name, item["name"])
        assertEquals(false, item["encrypted"])
        val download = admin.get("/api/v1/backups/${legacy.name}")
        assertProblem(download, 409)
        assertFalse(contains(download.raw, "PK\u0003\u0004".toByteArray()))
        assertEquals(200, admin.send("POST", "/api/v1/backups/${legacy.name}/restore").status)
    }

    private fun assertProblem(r: Http.Res, status: Int) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun snapshot(root: Path): Map<String, ByteArray> =
        ContentHash.files(root).associate { root.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }

    private fun assertFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        for ((k, v) in expected) assertArrayEquals(v, actual.getValue(k), k)
    }
}
