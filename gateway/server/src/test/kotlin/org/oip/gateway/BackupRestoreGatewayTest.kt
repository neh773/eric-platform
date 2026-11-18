package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.backup.ContentHash
import org.oip.backup.ZipIo
import org.oip.config.Format
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceCodec
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.host.ModuleState
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * GAT-014 through the running gateway: one zip, wipe, restore, identical config bytes and runtime content hash,
 * then a restart restores the module instead of installing it again.
 */
class BackupRestoreGatewayTest {
    @TempDir lateinit var tmp: Path
    private val id = ResourceId("m", Scope.GATEWAY, "Thing", "a")

    @Test fun httpBackupWipeRestoreThenRestartMatchesContentHash() {
        tmp.resolve("modules/sample").createDirectories().resolve("module.yaml").writeText(
            "id: sample\nversion: 1.2.3\nname: Sample\nentrypoint: org.oip.gateway.SampleModule\n")
        var configHash = ""
        var runtimeHash = ""
        var configFiles = mapOf<String, ByteArray>()
        Gateway(tmp, port = 0).use { g ->
            g.store.registry.register("m", "Thing", mapOf("type" to "object",
                "properties" to mapOf("x" to mapOf("type" to "integer"), "note" to mapOf("type" to "string"))))
            g.start()
            assertEquals(ModuleState.RUNNING, g.host.state("sample"))
            g.store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 4, "note" to "héllo")))
            g.runtime.put("k", "snow\nflake")
            val http = Http(g.port())
            g.loginAdmin(http)
            configFiles = snapshot(g.store.root)
            configHash = ContentHash.config(g.store.root)
            runtimeHash = ContentHash.runtime(g.runtime.canonicalDump())
            assertNotEquals(ContentHash.runtime(org.oip.backup.RuntimeDatabase.formatDump(emptyList())), runtimeHash)

            val backup = http.createBackup()
            assertEquals(200, backup.status, backup.body)
            assertTrue(backup.header("Content-Type")!!.contains("application/octet-stream"))
            assertTrue(org.oip.backup.BackupCipher.isEncrypted(backup.raw))
            val entries = ZipIo.read(openBackup(backup.raw))
            assertTrue(entries.containsKey("oip-backup.json"))
            assertTrue(entries.containsKey("db/runtime.sql"))
            assertTrue(entries.containsKey("installed-modules.tsv"))
            assertTrue(entries.keys.any { it.startsWith("config/") && it.endsWith("a.yaml") })
            val name = backup.header("X-Backup-Name")!!
            assertTrue(http.get("/api/v1/backups").body.contains(name))

            g.backups.wipe()
            assertTrue(snapshot(g.store.root).isEmpty())
            assertTrue(g.runtime.entries().isEmpty())
            assertNull(g.store.get(id))
            assertTrue(tmp.resolve("modules/sample/module.yaml").exists(), "module packages stay on disk")

            // Wipe removes users with the config tree. Seed the administrator role again so the restore call can authenticate.
            if (g.security.roles.get(DefaultRoles.ADMINISTRATOR) == null) {
                g.security.roles.create(DefaultRoles.ADMINISTRATOR, Permissions.ALL, actor = "test")
            }
            g.loginAdmin(http)
            val restored = http.restoreBackup(backup.raw)
            assertEquals(200, restored.status, restored.body)
            assertFiles(configFiles, snapshot(g.store.root))
            assertEquals(configHash, ContentHash.config(g.store.root))
            assertEquals(runtimeHash, ContentHash.runtime(g.runtime.canonicalDump()))
            assertEquals("snow\nflake", g.runtime.get("k"))
            val live = ResourceCodec.decode(http.get("/api/v1/config/m/gateway/Thing/a").body, Format.JSON)
            assertEquals(4, live.spec["x"])
            assertEquals("héllo", live.spec["note"])
            assertEquals(1L, live.metadata.version)
        }

        Gateway(tmp, port = 0).use { g ->
            g.start()
            val events = tmp.resolve("module-events.log").readLines()
            assertEquals(1, events.count { it == "install:sample" })
            assertEquals(1, events.count { it == "restore:sample" })
            assertEquals(ModuleState.RUNNING, g.host.state("sample"))
            assertEquals(configHash, ContentHash.config(g.store.root))
            assertEquals(runtimeHash, ContentHash.runtime(g.runtime.canonicalDump()))
            assertEquals(4, g.store.get(id)!!.spec["x"])
            assertEquals(1L, g.store.get(id)!!.metadata.version)
            assertEquals("snow\nflake", g.runtime.get("k"))
        }
    }

    @Test fun manifestRestoreNamesMissingPackagesAndBundledRestoreComesBackRunning() {
        tmp.resolve("modules/sample").createDirectories().resolve("module.yaml").writeText(
            "id: sample\nversion: 1.2.3\nname: Sample\nentrypoint: org.oip.gateway.SampleModule\n")
        tmp.resolve("modules/sample/payload.txt").writeText("from-package")
        var configHash = ""
        var runtimeHash = ""
        var manifest = ByteArray(0)
        var bundled = ByteArray(0)
        Gateway(tmp, port = 0).use { g ->
            g.store.registry.register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
            g.start()
            assertEquals(ModuleState.RUNNING, g.host.state("sample"))
            g.store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 4)))
            g.runtime.put("k", "kept")
            val http = Http(g.port())
            g.loginAdmin(http)
            configHash = ContentHash.config(g.store.root)
            runtimeHash = ContentHash.runtime(g.runtime.canonicalDump())
            manifest = http.createBackup().raw
            val packed = http.createBackup("?bundleModules=true")
            assertEquals(200, packed.status, packed.body)
            bundled = packed.raw
            assertTrue(ZipIo.read(openBackup(bundled)).containsKey("modules/sample/payload.txt"))
            assertTrue(ZipIo.read(openBackup(manifest)).keys.none { it.startsWith("modules/") })
            g.backups.wipe()
        }
        deleteTree(tmp.resolve("modules"))
        assertFalse(tmp.resolve("modules").exists())

        Gateway(tmp, port = 0).use { g ->
            g.store.registry.register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
            g.start()
            assertNull(g.host.state("sample"))
            val http = Http(g.port())
            g.loginAdmin(http)
            val beforeRestore = snapshot(g.store.root)
            val refused = http.restoreBackup(manifest)
            assertEquals(422, refused.status, refused.body)
            assertTrue(refused.header("Content-Type")!!.contains("problem+json"))
            assertTrue(refused.body.contains("sample"))
            assertTrue(refused.body.contains("package not present"))
            assertTrue(g.runtime.entries().isEmpty())
            assertFiles(beforeRestore, snapshot(g.store.root))
            assertNull(g.store.get(id))
            assertFalse(tmp.resolve("modules/sample/module.yaml").exists())

            val restored = http.restoreBackup(bundled)
            assertEquals(200, restored.status, restored.body)
            assertEquals("from-package", tmp.resolve("modules/sample/payload.txt").readText())
            assertEquals(configHash, ContentHash.config(g.store.root))
            assertEquals(runtimeHash, ContentHash.runtime(g.runtime.canonicalDump()))
        }

        Gateway(tmp, port = 0).use { g ->
            g.start()
            assertEquals(ModuleState.RUNNING, g.host.state("sample"))
            val events = tmp.resolve("module-events.log").readLines()
            assertEquals(1, events.count { it == "install:sample" })
            assertEquals(1, events.count { it == "restore:sample" })
            assertEquals(configHash, ContentHash.config(g.store.root))
            assertEquals("kept", g.runtime.get("k"))
        }
    }

    private fun deleteTree(dir: Path) {
        if (!dir.exists()) return
        Files.walk(dir).use { stream ->
            stream.sorted(compareByDescending { it.nameCount }).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun snapshot(root: Path): Map<String, ByteArray> =
        ContentHash.files(root).associate { root.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }

    private fun assertFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        for ((k, v) in expected) assertArrayEquals(v, actual.getValue(k), k)
    }
}
