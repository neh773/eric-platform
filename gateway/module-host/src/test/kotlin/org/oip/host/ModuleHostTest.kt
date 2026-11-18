package org.oip.host

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Test module. Events are appended to `events.log` next to the config root (i.e. in the test's own
 * @TempDir), so there is no shared/global state between tests.
 */
class RecordingModule : Module {
    private lateinit var id: String
    private lateinit var log: Path
    private fun bind(context: ModuleContext) { id = context.manifest.id; log = context.configDir.parent.resolveSibling("events.log") }
    private fun record(e: String) { Files.writeString(log, "$e\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) }
    override fun install(context: ModuleContext) { bind(context); record("install:$id")
        context.configDir.resolve("settings.yaml").writeText("x: 1\n") }
    override fun restore(context: ModuleContext) { bind(context); record("restore:$id") }
    override fun start() = record("start:$id")
    override fun stop() = record("stop:$id")
    override fun uninstall() { record("uninstall:$id"); if (id.startsWith("fail")) throw IllegalStateException("boom") }
}

class ModuleHostTest {
    @TempDir lateinit var tmp: Path
    private val modules get() = tmp.resolve("modules")
    private val config get() = tmp.resolve("config")
    private val events: List<String> get() = tmp.resolve("events.log").let { if (it.exists()) it.readLines() else emptyList() }

    private fun module(id: String, version: String = "1.0.0", deps: String = "") {
        val d = modules.resolve(id).createDirectories()
        d.resolve("module.yaml").writeText("id: $id\nversion: $version\nentrypoint: ${RecordingModule::class.qualifiedName}\n$deps")
    }

    @Test fun loadsInstallsInDependencyOrder() {
        module("app", deps = "dependencies:\n  - id: base\n    version: 1.0.0\n")
        module("base")
        val host = ModuleHost(modules, config)
        host.installAll(); host.startAll()
        assertEquals(listOf("install:base", "install:app", "start:base", "start:app"), events)
        assertEquals(ModuleState.RUNNING, host.state("app"))
        host.stopAll()
        assertEquals(listOf("stop:app", "stop:base"), events.takeLast(2))
    }

    @Test fun refusesUninstallOfDependedOnModule() {
        module("base"); module("app", deps = "dependencies:\n  - id: base\n    version: 1.0.0\n")
        val host = ModuleHost(modules, config)
        host.installAll(); host.startAll()
        val ex = assertThrows(DependencyException::class.java) { host.uninstall("base") }
        assertTrue(ex.message!!.contains("app"))
        assertEquals(ModuleState.RUNNING, host.state("base"))
        assertTrue(config.resolve("base").exists())
        host.uninstall("app"); host.uninstall("base")
        assertFalse(config.resolve("base").exists()); assertFalse(config.resolve("app").exists())
        assertTrue(host.installed().isEmpty())
    }

    @Test fun uninstallRemovesConfigDir() {
        module("solo")
        val host = ModuleHost(modules, config)
        host.installAll()
        assertTrue(config.resolve("solo/settings.yaml").exists())
        host.uninstall("solo")
        assertFalse(config.resolve("solo").exists())
        assertNull(host.state("solo"))
    }

    @Test fun missingDependencyFailsInstall() {
        module("app", deps = "dependencies:\n  - id: ghost\n    version: 1.0.0\n")
        assertThrows(DependencyException::class.java) { ModuleHost(modules, config).installAll() }
    }

    @Test fun tooOldDependencyFailsInstall() {
        module("base", version = "0.9.0"); module("app", deps = "dependencies:\n  - id: base\n    version: 1.0.0\n")
        assertThrows(DependencyException::class.java) { ModuleHost(modules, config).installAll() }
    }

    @Test fun cycleFailsInstall() {
        module("a", deps = "dependencies:\n  - id: b\n    version: 1.0.0\n")
        module("b", deps = "dependencies:\n  - id: a\n    version: 1.0.0\n")
        assertThrows(DependencyException::class.java) { ModuleHost(modules, config).installAll() }
    }

    @Test fun badManifestInDirectoryIsRejected() {
        modules.resolve("bad").createDirectories().resolve("module.yaml").writeText("id: BAD!\n")
        assertThrows(org.oip.sdk.ManifestException::class.java) { ModuleHost(modules, config).installAll() }
    }

    @Test fun duplicateIdRejected() {
        module("x"); val d = modules.resolve("x2").createDirectories()
        Files.copy(modules.resolve("x/module.yaml"), d.resolve("module.yaml"))
        assertThrows(IllegalStateException::class.java) { ModuleHost(modules, config).installAll() }
    }

    /** Review-1 F5: a module uninstalled after a restart and found again is installed afresh, not restored. */
    @Test fun uninstalledAfterRestartIsInstalledAgainNotRestored() {
        module("a")
        ModuleHost(modules, config).installAll()
        val restarted = ModuleHost(modules, config)
        restarted.installAll()
        restarted.uninstall("a")
        assertNull(restarted.state("a"))
        restarted.discover()
        assertNull(restarted.state("a"), "a module uninstalled in this run must not come back from the registry read at start")
        restarted.install("a")
        assertEquals(ModuleState.INSTALLED, restarted.state("a"))
        assertEquals(2, events.count { it == "install:a" })
    }

    @Test fun registryPersistsAcrossRestartAndDoesNotReinstall() {
        module("base"); module("app", deps = "dependencies:\n  - id: base\n    version: 1.0.0\n")
        val host = ModuleHost(modules, config)
        host.installAll(); host.startAll()
        config.resolve("app/settings.yaml").writeText("x: 42\n") // user edit
        host.stopAll()

        val restarted = ModuleHost(modules, config)
        restarted.installAll()
        assertEquals(ModuleState.INSTALLED, restarted.state("base"))
        assertEquals(ModuleState.INSTALLED, restarted.state("app"))
        assertEquals(2, events.count { it.startsWith("install:") }, "restart must not call install() again")
        assertTrue(events.containsAll(listOf("restore:base", "restore:app")))
        assertEquals("x: 42\n", config.resolve("app/settings.yaml").readText(), "existing config must not be overwritten")
        restarted.startAll()
        assertEquals(ModuleState.RUNNING, restarted.state("app"))
        restarted.uninstall("app")

        val third = ModuleHost(modules, config); third.discover()
        assertNull(third.state("app")); assertEquals(ModuleState.INSTALLED, third.state("base"))
        assertEquals(listOf("base"), third.installed().map { it.id })
    }

    @Test fun failedUninstallKeepsModuleInstalledWithConfig() {
        module("failing")
        val host = ModuleHost(modules, config)
        host.installAll(); host.start("failing")
        val ex = assertThrows(IllegalStateException::class.java) { host.uninstall("failing") }
        assertEquals("boom", ex.message)
        assertEquals(ModuleState.UNINSTALL_FAILED, host.state("failing"))
        assertTrue(host.lastError("failing")!!.contains("boom"))
        assertEquals("x: 1\n", config.resolve("failing/settings.yaml").readText(), "no partial config deletion")
        assertEquals(listOf("failing"), host.installed().map { it.id })

        val restarted = ModuleHost(modules, config); restarted.discover()
        assertEquals(ModuleState.UNINSTALL_FAILED, restarted.state("failing"), "failed state is persisted")
        assertTrue(restarted.lastError("failing")!!.contains("boom"))
    }

    @Test fun installConfigureUninstallLeavesZeroResources() {
        val d = modules.resolve("cfg").createDirectories()
        d.resolve("module.yaml").writeText("id: cfg\nversion: 1.0.0\nentrypoint: ${RecordingModule::class.qualifiedName}\n" +
            "configSchema:\n  type: object\n  properties:\n    n: {type: integer, default: 3}\n")
        val store = org.oip.config.FileConfigStore(config, org.oip.config.SchemaRegistry())
        val host = ModuleHost(modules, config, store = store)
        host.installAll()
        assertTrue(store.registry.isRegistered("cfg", "ModuleConfig"))
        val mc = host.context("cfg")!!.config
        val r = mc.put("ModuleConfig", "default", mapOf("n" to 7)); assertEquals(1L, r.metadata.version)
        mc.put("ModuleConfig", "other", mc.defaults(), scope = org.oip.config.Scope.project("p1"))
        mc.put("ModuleConfig", "default", mapOf("n" to 9), scope = org.oip.config.Scope.mode("dev"))
        assertEquals(3, store.list(module = "cfg").size)
        assertThrows(org.oip.config.ValidationException::class.java) { mc.put("ModuleConfig", "bad", mapOf("n" to "x")) }
        assertThrows(IllegalArgumentException::class.java) { mc.put("ModuleConfig", "x", emptyMap(), module = "other") }
        host.start("cfg"); host.uninstall("cfg")
        assertEquals(0, store.list(module = "cfg").size)
        assertEquals(0, org.oip.config.FileConfigStore(config, org.oip.config.SchemaRegistry()).list(module = "cfg").size)
        assertFalse(config.resolve("cfg").exists())
        assertFalse(store.registry.isRegistered("cfg", "ModuleConfig"))
    }

    @Test fun invalidConfigSchemaRejectedAtDiscover() {
        modules.resolve("bad").createDirectories().resolve("module.yaml").writeText(
            "id: bad\nversion: 1.0.0\nentrypoint: ${RecordingModule::class.qualifiedName}\nconfigSchema:\n  type: object\n  properties:\n    n: {type: nope}\n")
        assertThrows(org.oip.sdk.ManifestException::class.java) { ModuleHost(modules, config).installAll() }
    }
}
