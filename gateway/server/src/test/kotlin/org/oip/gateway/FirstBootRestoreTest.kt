package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.io.path.writeBytes

/**
 * GAT-009: a container started with OIP_RESTORE_FROM restores that backup into its empty data volume once, before
 * modules start, and never again over live data.
 * Doc source: FEATURES GAT-009.
 */
class FirstBootRestoreTest {
    @TempDir lateinit var tmp: Path
    private val id = ResourceId("m", Scope.GATEWAY, "Thing", "a")

    private fun open(dir: Path, env: Map<String, String> = emptyMap()): Gateway {
        Files.createDirectories(dir)
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  enabled: false\n")
        val gw = Gateway(dir, port = 0, env = System.getenv() + env)
        gw.store.registry.register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
        return gw
    }

    private fun makeBackup(): Path {
        val source = open(tmp.resolve("source"))
        source.start().use {
            it.store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 42)))
            val file = tmp.resolve("seed.zip")
            file.writeBytes(it.backups.create())
            return file
        }
    }

    @Test fun anEmptyVolumeIsRestoredOnceAndNotAgain() {
        val backup = makeBackup()
        val env = mapOf("OIP_RESTORE_FROM" to backup.toString())
        open(tmp.resolve("volume"), env).start().use {
            assertEquals(42, it.store.get(id)!!.spec["x"])
        }
        assertTrue(tmp.resolve("volume/${FirstBootRestore.MARKER}").exists())
        // Operator removes the resource; a restart with the variable still set must not bring it back.
        open(tmp.resolve("volume"), env).use { gw ->
            gw.store.delete(id)
        }
        open(tmp.resolve("volume"), env).start().use { assertNull(it.store.get(id)) }
    }

    @Test fun theRestoredConfigurationIsLiveInTheRunningServicesOnTheFirstBoot() {
        // The container recipe smoke reads the restored database connection over the API right after the first boot.
        val source = open(tmp.resolve("source"))
        val backup = source.start().use {
            it.store.put(
                Scope.GATEWAY,
                Resource("DatabaseConnection", Metadata("recipe-marker", "db"), mapOf("driver" to "sqlite", "url" to "jdbc:sqlite::memory:")),
            )
            tmp.resolve("seed.zip").also { file -> file.writeBytes(it.backups.create()) }
        }
        open(tmp.resolve("volume"), mapOf("OIP_RESTORE_FROM" to backup.toString())).start().use {
            assertEquals(listOf("recipe-marker"), it.databases.connections().map { c -> c["name"] })
        }
    }

    @Test fun aDataDirectoryWithConfigurationIsLeftAlone() {
        val backup = makeBackup()
        val dir = tmp.resolve("live")
        open(dir).start().use {
            it.store.put(Scope.GATEWAY, Resource("Thing", Metadata("b", "m"), mapOf("x" to 7)))
        }
        open(dir, mapOf("OIP_RESTORE_FROM" to backup.toString())).start().use {
            assertNull(it.store.get(id))
            assertNotNull(it.store.get(ResourceId("m", Scope.GATEWAY, "Thing", "b")))
        }
        assertTrue(!dir.resolve(FirstBootRestore.MARKER).exists())
    }

    @Test fun aMissingBackupFileStopsTheStart() {
        val e = assertThrows<IllegalStateException> {
            open(tmp.resolve("volume"), mapOf("OIP_RESTORE_FROM" to tmp.resolve("absent.zip").toString())).start()
        }
        assertTrue(e.message!!.contains("OIP_RESTORE_FROM"), e.message)
    }

    @Test fun anEncryptedBackupNeedsItsPassphrase() {
        val source = open(tmp.resolve("source"))
        val sealed = source.start().use {
            it.store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 5)))
            it.backups.createEncrypted("correct horse battery".toCharArray())
        }
        val file = tmp.resolve("seed.oipbk").also { it.writeBytes(sealed) }
        val e = assertThrows<IllegalStateException> {
            open(tmp.resolve("v1"), mapOf("OIP_RESTORE_FROM" to file.toString())).start()
        }
        assertTrue(e.message!!.contains("OIP_RESTORE_PASSPHRASE"), e.message)
        val phrase = tmp.resolve("phrase.txt").also { it.writeText("correct horse battery\n") }
        open(tmp.resolve("v2"), mapOf("OIP_RESTORE_FROM" to file.toString(), "OIP_RESTORE_PASSPHRASE_FILE" to phrase.toString())).start().use {
            assertEquals(5, it.store.get(id)!!.spec["x"])
        }
    }
}
