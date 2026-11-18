package org.oip.backup

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.security.SecretKeyLoader
import org.oip.security.SecretKeySource
import org.oip.security.SecretsKeyFile
import org.oip.security.SecurityService
import org.oip.security.SqliteSecretBlobStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * GAT-014: backup, wipe, restore yields the same config bytes and the same runtime content hash.
 */
class BackupRestoreTest {
    @TempDir lateinit var tmp: Path
    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry().apply {
        register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"), "note" to mapOf("type" to "string"))))
    }
    private val id = ResourceId("m", Scope.GATEWAY, "Thing", "a")

    private fun layout(): Triple<FileConfigStore, RuntimeDatabase, BackupService> {
        val store = FileConfigStore(tmp.resolve("config"), registry)
        val db = RuntimeDatabase(tmp.resolve("db/runtime.db"))
        val svc = BackupService(tmp, store, db, "0.1.0-test")
        return Triple(store, db, svc)
    }

    @Test fun backupWipeRestoreIsByteIdenticalForConfigAndContentIdenticalForRuntime() {
        val (store, db, svc) = layout()
        tmp.resolve("gateway.yaml").writeText("profile: edge\n")
        tmp.resolve("modules/sample").createDirectories().resolve("module.yaml").writeText(
            "id: sample\nversion: 1.2.3\nname: Sample\nentrypoint: org.example.Sample\n")
        tmp.resolve("installed-modules.tsv").writeText("sample\tRUNNING\t\n")
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 7, "note" to "héllo 雪")))
        store.put(Scope.project("plant"), Resource("Thing", Metadata("b", "m"), mapOf("x" to 1)))
        db.put("alpha", "one")
        db.put("beta\tline", "two\nlines")
        db.put("k", "v")

        val configBefore = snapshot(store.root)
        val rowsBefore = db.entries()
        val configHash = ContentHash.config(store.root)
        val runtimeHash = ContentHash.runtime(db.canonicalDump())
        assertEquals(ContentHash.sha256(ContentHash.canonicalConfig(store.root)), configHash)
        assertNotEquals(ContentHash.config(tmp.resolve("empty-config")), configHash)
        assertNotEquals(ContentHash.runtime(RuntimeDatabase.formatDump(emptyList())), runtimeHash)

        val archive = svc.create()
        val zip = ZipIo.read(archive)
        assertEquals(setOf("oip-backup.json", "db/runtime.sql", "installed-modules.tsv", "gateway.yaml"),
            zip.keys.filter { !it.startsWith("config/") }.toSet())
        assertTrue(zip.keys.any { it.startsWith("config/") && it.endsWith(".yaml") })
        @Suppress("UNCHECKED_CAST")
        val manifest = mapper.readValue(zip.getValue("oip-backup.json"), Map::class.java) as Map<String, Any?>
        assertEquals("oip-gateway-backup", manifest["format"])
        assertEquals(configHash, manifest["configHash"])
        assertEquals(runtimeHash, manifest["runtimeHash"])
        @Suppress("UNCHECKED_CAST")
        val modules = manifest["modules"] as List<Map<String, Any?>>
        assertEquals("sample", modules.single()["id"])
        assertEquals("1.2.3", modules.single()["version"])
        assertEquals("RUNNING", modules.single()["state"])

        svc.wipe()
        assertTrue(snapshot(store.root).isEmpty())
        assertNotEquals(rowsBefore, db.entries())
        assertTrue(db.entries().isEmpty())
        assertNull(store.get(id))
        assertTrue(tmp.resolve("modules/sample/module.yaml").exists(), "wipe keeps module packages")
        assertFalse(tmp.resolve("installed-modules.tsv").exists())

        svc.restore(archive)
        assertSnapshotsEqual(configBefore, snapshot(store.root))
        assertEquals(rowsBefore, db.entries())
        assertEquals(configHash, ContentHash.config(store.root))
        assertEquals(runtimeHash, ContentHash.runtime(db.canonicalDump()))
        val restored = store.get(id)!!
        assertEquals(7, restored.spec["x"])
        assertEquals("héllo 雪", restored.spec["note"])
        assertEquals(1L, restored.metadata.version, "restore must not bump the stored version")
        assertEquals("two\nlines", db.get("beta\tline"))
        assertEquals("profile: edge\n", tmp.resolve("gateway.yaml").readText())
        assertEquals("sample\tRUNNING\t\n", tmp.resolve("installed-modules.tsv").readText())
        db.close(); store.close()
    }

    @Test fun rejectedArchiveDoesNotChangeData() {
        val (store, db, svc) = layout()
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 3)))
        db.put("k", "v")
        val files = snapshot(store.root)
        assertThrows<BackupException> { svc.restore(byteArrayOf(1, 2, 3, 4)) }
        val bad = ZipIo.write(listOf("oip-backup.json" to """{"format":"nope","formatVersion":1}""".toByteArray()))
        assertThrows<BackupException> { svc.restore(bad) }
        assertThrows<BackupException> { svc.restore(zipWith("../evil.txt")) }
        assertSnapshotsEqual(files, snapshot(store.root))
        assertEquals("v", db.get("k"))
        db.close(); store.close()
    }

    @Test fun missingOrMismatchedModulePackagesAreNamedAndChangeNothing() {
        val (store, db, svc) = layout()
        tmp.resolve("gateway.yaml").writeText("profile: edge\n")
        tmp.resolve("modules/sample").createDirectories().resolve("module.yaml").writeText(
            "id: sample\nversion: 1.2.3\nname: Sample\nentrypoint: org.example.Sample\n")
        tmp.resolve("modules/other").createDirectories().resolve("module.yaml").writeText(
            "id: other\nversion: 2.0.0\nname: Other\nentrypoint: org.example.Other\n")
        tmp.resolve("installed-modules.tsv").writeText("sample\tINSTALLED\t\nother\tINSTALLED\t\n")
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 3)))
        db.put("k", "v")
        val archive = svc.create()
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m", version = 1), mapOf("x" to 8)))
        db.put("k", "changed")
        val files = snapshot(store.root)
        deleteRecursively(tmp.resolve("modules"))
        val missing = assertThrows<BackupException> { svc.restore(archive) }
        assertTrue(missing.message!!.contains("sample"))
        assertTrue(missing.message!!.contains("other"))
        assertTrue(missing.message!!.contains("package not present"))
        assertSnapshotsEqual(files, snapshot(store.root))
        assertEquals("changed", db.get("k"))
        assertEquals(8, store.get(id)!!.spec["x"])

        tmp.resolve("modules/sample").createDirectories().resolve("module.yaml").writeText(
            "id: sample\nversion: 9.9.9\nname: Sample\nentrypoint: org.example.Sample\n")
        tmp.resolve("modules/other").createDirectories().resolve("module.yaml").writeText(
            "id: other\nversion: 2.0.0\nname: Other\nentrypoint: org.example.Other\n")
        val mismatch = assertThrows<BackupException> { svc.restore(archive) }
        assertTrue(mismatch.message!!.contains("sample"))
        assertTrue(mismatch.message!!.contains("expected 1.2.3"))
        assertTrue(mismatch.message!!.contains("found 9.9.9"))
        assertFalse(mismatch.message!!.contains("other ("))
        assertEquals("changed", db.get("k"))
        db.close(); store.close()
    }

    @Test fun bundledRestoreRebuildsWipedModulePackages() {
        val (store, db, svc) = layout()
        val pkg = tmp.resolve("modules/sample")
        pkg.createDirectories()
        pkg.resolve("module.yaml").writeText("id: sample\nversion: 1.2.3\nname: Sample\nentrypoint: org.example.Sample\n")
        pkg.resolve("payload.txt").writeText("module-bytes")
        tmp.resolve("installed-modules.tsv").writeText("sample\tINSTALLED\t\n")
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 3)))
        db.put("k", "v")
        val plain = ZipIo.read(svc.create())
        assertTrue(plain.keys.none { it.startsWith("modules/") })
        val bundled = ZipIo.read(svc.create(bundleModules = true))
        assertEquals("module-bytes", bundled.getValue("modules/sample/payload.txt").toString(Charsets.UTF_8))
        val archive = svc.create(bundleModules = true)
        svc.wipe()
        deleteRecursively(tmp.resolve("modules"))
        assertFalse(tmp.resolve("modules/sample/module.yaml").exists())
        svc.restore(archive)
        assertEquals("module-bytes", tmp.resolve("modules/sample/payload.txt").readText())
        assertEquals("id: sample\nversion: 1.2.3\nname: Sample\nentrypoint: org.example.Sample\n",
            tmp.resolve("modules/sample/module.yaml").readText())
        assertEquals("v", db.get("k"))
        assertEquals(3, store.get(id)!!.spec["x"])
        db.close(); store.close()
    }

    @Test fun failurePartwayThroughRestoreLeavesThePreviousState() {
        val (store, db, svc) = layout()
        tmp.resolve("gateway.yaml").writeText("profile: edge\n")
        tmp.resolve("modules/sample").createDirectories().resolve("module.yaml").writeText(
            "id: sample\nversion: 1.2.3\nname: Sample\nentrypoint: org.example.Sample\n")
        tmp.resolve("installed-modules.tsv").writeText("sample\tINSTALLED\t\n")
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 1)))
        db.put("k", "before")
        val archive = svc.create()
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m", version = 1), mapOf("x" to 9)))
        db.put("k", "after")
        tmp.resolve("gateway.yaml").writeText("profile: hub\n")
        val files = snapshot(store.root)
        val configHash = ContentHash.config(store.root)
        val runtimeHash = ContentHash.runtime(db.canonicalDump())
        svc.beforeRuntimeCommit = { throw BackupException("injected failure after the file swap") }
        try {
            val failed = assertThrows<BackupException> { svc.restore(archive) }
            assertTrue(failed.message!!.contains("injected failure"))
        } finally {
            svc.beforeRuntimeCommit = {}
        }
        assertSnapshotsEqual(files, snapshot(store.root))
        assertEquals(configHash, ContentHash.config(store.root))
        assertEquals(runtimeHash, ContentHash.runtime(db.canonicalDump()))
        assertEquals("after", db.get("k"))
        assertEquals(9, store.get(id)!!.spec["x"])
        assertEquals("profile: hub\n", tmp.resolve("gateway.yaml").readText())
        assertEquals("sample\tINSTALLED\t\n", tmp.resolve("installed-modules.tsv").readText())
        assertTrue(tmp.resolve("modules/sample/module.yaml").exists())
        assertTrue(java.nio.file.Files.list(tmp).use { s ->
            s.noneMatch { it.fileName.toString().startsWith(".restore-") }
        })
        db.close(); store.close()
    }

    @Test fun securityFilesRoundTripAndAFailedRestoreLeavesThem() {
        val (store, db, svc) = layout()
        val keyPath = SecuritySnapshot.keyFile(tmp)
        assertEquals("secrets/gateway.key", tmp.relativize(keyPath).toString().replace('\\', '/'))
        val anchorPath = tmp.resolve("db/audit.anchor")
        val auditPath = tmp.resolve("db/audit.sqlite")
        val secretsPath = tmp.resolve("db/secrets.sqlite")
        val key = SecretKeyLoader.loadFile(keyPath)
        val sec = SecurityService.open(store, tmp, SecretKeySource { key })
        sec.secrets.put("plant-token", "s3cret-value".toByteArray(Charsets.UTF_8), "line\n\tmore")
        val before = sec.audit.verify()
        assertTrue(before.ok, before.reason)
        sec.close()
        SqliteSecretBlobStore(secretsPath).use { blobs ->
            blobs.upsert("clock-skew", "d", byteArrayOf(1, 2, 3), 10L)
            blobs.upsert("clock-skew", "d", byteArrayOf(1, 2, 3), 20L)
        }

        val auditDump = SecuritySnapshot.readAudit(auditPath)
        val secretsDump = SecuritySnapshot.readSecrets(secretsPath)
        val anchor = anchorPath.readBytes()
        val keyBytes = keyPath.readBytes()
        assertNotNull(auditDump)
        assertNotNull(secretsDump)
        assertTrue(secretsDump!!.contains("line\\n\\tmore"))
        val securityHash = ContentHash.security(auditDump, anchor, secretsDump, keyBytes)
        db.put("k", "runtime")

        val archive = svc.create()
        val zip = ZipIo.read(archive)
        assertEquals(auditDump, zip.getValue("db/audit.sql").toString(Charsets.UTF_8))
        assertEquals(secretsDump, zip.getValue("db/secrets.sql").toString(Charsets.UTF_8))
        assertArrayEquals(anchor, zip.getValue("db/audit.anchor"))
        assertArrayEquals(keyBytes, zip.getValue("secrets.key"))
        @Suppress("UNCHECKED_CAST")
        val manifest = mapper.readValue(zip.getValue("oip-backup.json"), Map::class.java) as Map<String, Any?>
        assertEquals(securityHash, manifest["securityHash"])

        keyPath.writeBytes(ByteArray(32) { 1 })
        anchorPath.writeBytes("tampered".toByteArray())
        val stray = tmp.resolve("secrets.key")
        stray.writeBytes(byteArrayOf(9))
        svc.wipe()
        assertFalse(keyPath.exists())
        assertFalse(stray.exists())
        assertFalse(anchorPath.exists())
        assertFalse(auditPath.exists())
        assertFalse(secretsPath.exists())
        assertFalse(tmp.resolve("db/audit.sqlite-wal").exists())
        assertFalse(tmp.resolve("db/secrets.sqlite-shm").exists())
        assertTrue(db.entries().isEmpty())
        assertTrue(tmp.resolve("db/runtime.db").exists(), "wipe clears runtime rows and leaves the runtime file")

        svc.restore(archive)
        assertArrayEquals(keyBytes, keyPath.readBytes())
        assertArrayEquals(anchor, anchorPath.readBytes())
        assertEquals(auditDump, SecuritySnapshot.readAudit(auditPath))
        assertEquals(secretsDump, SecuritySnapshot.readSecrets(secretsPath))
        assertEquals(securityHash, ContentHash.security(
            SecuritySnapshot.readAudit(auditPath), anchorPath.readBytes(),
            SecuritySnapshot.readSecrets(secretsPath), keyPath.readBytes(),
        ))
        if (keyPath.fileSystem.supportedFileAttributeViews().contains("posix")) {
            val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            assertEquals(SecretsKeyFile.FILE_MODE, Files.getPosixFilePermissions(keyPath))
            assertEquals(SecretsKeyFile.DIRECTORY_MODE, Files.getPosixFilePermissions(keyPath.parent))
            assertEquals(ownerOnly, Files.getPosixFilePermissions(anchorPath))
        }
        assertFalse(tmp.resolve("secrets.key").exists())
        assertEquals("runtime", db.get("k"))
        SqliteSecretBlobStore(secretsPath).use { blobs ->
            val skewed = blobs.read("clock-skew")!!
            assertEquals(10L, skewed.createdMillis)
            assertEquals(20L, skewed.updatedMillis)
            assertArrayEquals(byteArrayOf(1, 2, 3), skewed.ciphertext)
        }

        val sec2 = SecurityService.open(store, tmp, SecretKeySource { SecretKeyLoader.loadFile(keyPath, createFileIfMissing = false) })
        assertEquals("s3cret-value", sec2.secrets.getText("plant-token"))
        assertEquals("line\n\tmore", sec2.secrets.list().single { it.name == "plant-token" }.description)
        val verified = sec2.audit.verify()
        assertTrue(verified.ok, verified.reason)
        sec2.secrets.putText("later", "after")
        sec2.close()

        val mutatedAudit = SecuritySnapshot.readAudit(auditPath)
        val mutatedSecrets = SecuritySnapshot.readSecrets(secretsPath)
        keyPath.writeBytes(ByteArray(32) { 3 })
        anchorPath.writeBytes(byteArrayOf(9, 9))
        val keyMut = keyPath.readBytes()
        val anchorMut = anchorPath.readBytes()
        svc.beforeRuntimeCommit = { throw BackupException("injected failure after the file swap") }
        try {
            val failed = assertThrows<BackupException> { svc.restore(archive) }
            assertTrue(failed.message!!.contains("injected failure"))
        } finally {
            svc.beforeRuntimeCommit = {}
        }
        assertArrayEquals(keyMut, keyPath.readBytes())
        assertArrayEquals(anchorMut, anchorPath.readBytes())
        assertEquals(mutatedAudit, SecuritySnapshot.readAudit(auditPath))
        assertEquals(mutatedSecrets, SecuritySnapshot.readSecrets(secretsPath))
        SecurityService.open(store, tmp, SecretKeySource { key }).use { opened ->
            assertEquals("after", opened.secrets.getText("later"))
        }
        assertEquals(mutatedAudit, SecuritySnapshot.readAudit(auditPath))
        assertEquals(mutatedSecrets, SecuritySnapshot.readSecrets(secretsPath))
        assertTrue(Files.list(tmp).use { stream -> stream.noneMatch { it.fileName.toString().startsWith(".restore-") } })

        val bad = ZipIo.write(zip.map { (name, bytes) ->
            name to if (name == "db/audit.sql") "not-a-dump".toByteArray() else bytes
        })
        val rejected = assertThrows<BackupException> { svc.restore(bad) }
        assertTrue(rejected.message!!.contains("audit dump"))
        assertEquals(mutatedAudit, SecuritySnapshot.readAudit(auditPath))
        assertArrayEquals(keyMut, keyPath.readBytes())
        db.close(); store.close()
    }

    /**
     * Review-1 F3: the sealed archive carries neither the key bytes nor any zip entry name, a wipe and a restore
     * with the right passphrase give the same config bytes, runtime hash and security files, and a wrong
     * passphrase fails before anything on disk changes.
     */
    @Test fun encryptedBackupHidesTheSecretsKeyAndRoundTripsOnlyWithItsPassphrase() {
        val (store, db, svc) = layout()
        val keyPath = SecuritySnapshot.keyFile(tmp)
        val key = SecretKeyLoader.loadFile(keyPath)
        SecurityService.open(store, tmp, SecretKeySource { key }).use { it.secrets.putText("plant-token", "s3cret-value") }
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 5, "note" to "sealed")))
        db.put("k", "v")
        val keyBytes = keyPath.readBytes()
        val configBefore = snapshot(store.root)
        val runtimeHash = ContentHash.runtime(db.canonicalDump())
        val secretsBefore = SecuritySnapshot.readSecrets(tmp.resolve("db/secrets.sqlite"))
        val pass = "correct horse battery"

        assertThrows<IllegalArgumentException> { svc.createEncrypted("short".toCharArray()) }
        val sealed = svc.createEncrypted(pass.toCharArray())
        assertTrue(BackupCipher.isEncrypted(sealed))
        assertFalse(contains(sealed, keyBytes), "sealed archive holds the secrets key bytes")
        assertFalse(contains(sealed, "secrets.key".toByteArray()), "sealed archive shows a zip entry name")
        assertFalse(contains(sealed, "PK\u0003\u0004".toByteArray()), "sealed archive holds a zip header")
        assertArrayEquals(keyBytes, ZipIo.read(BackupCipher.decrypt(sealed, pass.toCharArray())).getValue("secrets.key"))

        svc.wipe()
        assertFalse(keyPath.exists())
        val wiped = snapshot(store.root)
        val wrong = assertThrows<IllegalArgumentException> { svc.restoreEncrypted(sealed, "wrong horse battery".toCharArray()) }
        assertEquals("wrong passphrase", wrong.message)
        assertSnapshotsEqual(wiped, snapshot(store.root))
        assertFalse(keyPath.exists())
        assertTrue(db.entries().isEmpty())

        svc.restoreEncrypted(sealed, pass.toCharArray())
        assertSnapshotsEqual(configBefore, snapshot(store.root))
        assertEquals(runtimeHash, ContentHash.runtime(db.canonicalDump()))
        assertArrayEquals(keyBytes, keyPath.readBytes())
        assertEquals(secretsBefore, SecuritySnapshot.readSecrets(tmp.resolve("db/secrets.sqlite")))
        SecurityService.open(store, tmp, SecretKeySource { SecretKeyLoader.loadFile(keyPath, createFileIfMissing = false) }).use {
            assertEquals("s3cret-value", it.secrets.getText("plant-token"))
        }
        assertEquals(1L, store.get(id)!!.metadata.version)
        db.close(); store.close()
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test fun archiveWithoutSecurityEntriesLeavesLaterSecurityFiles() {
        val (store, db, svc) = layout()
        db.put("k", "v")
        val archive = svc.create()
        assertFalse(ZipIo.read(archive).keys.any {
            it == "secrets.key" || it == "db/audit.sql" || it == "db/audit.anchor" || it == "db/secrets.sql"
        })
        val keyPath = SecuritySnapshot.keyFile(tmp)
        val anchorPath = tmp.resolve("db/audit.anchor")
        keyPath.parent.createDirectories()
        keyPath.writeBytes(byteArrayOf(1, 2, 3))
        anchorPath.parent.createDirectories()
        anchorPath.writeBytes(byteArrayOf(4, 5))
        svc.restore(archive)
        assertArrayEquals(byteArrayOf(1, 2, 3), keyPath.readBytes())
        assertArrayEquals(byteArrayOf(4, 5), anchorPath.readBytes())
        assertEquals("v", db.get("k"))
        db.close(); store.close()
    }

    private fun deleteRecursively(dir: Path) {
        if (!dir.exists()) return
        java.nio.file.Files.walk(dir).use { stream ->
            stream.sorted(compareByDescending { it.nameCount }).forEach { java.nio.file.Files.deleteIfExists(it) }
        }
    }

    private fun snapshot(root: Path): Map<String, ByteArray> =
        ContentHash.files(root).associate { root.relativize(it).toString().replace('\\', '/') to it.readBytes() }

    private fun assertSnapshotsEqual(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        for (k in expected.keys) assertArrayEquals(expected.getValue(k), actual.getValue(k), k)
    }

    private fun zipWith(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        return out.toByteArray()
    }
}
