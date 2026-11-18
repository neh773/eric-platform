package org.oip.host

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.sdk.*
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.*

/** Service used only by these tests; requires permission `clock.read`. */
interface TestClock { fun now(): Long }

/** Test module that tries to obtain [ModuleLog] and [TestClock] at start. */
class GreedyModule : Module {
    lateinit var ctx: ModuleContext
    var got = mutableListOf<String>()
    override fun install(context: ModuleContext) { ctx = context }
    override fun restore(context: ModuleContext) { ctx = context }
    override fun start() {
        ctx.services.get(ModuleLog::class.java).info("hi"); got += "log"
        ctx.services.get(TestClock::class.java).now(); got += "clock"
    }
    override fun stop() {}
    override fun uninstall() {}
}

class RecordingLog(val lines: MutableList<String>) {
    fun registry() = ServiceRegistry().apply {
        register(ModuleLog::class.java, "log.write") { m -> object : ModuleLog {
            override fun info(message: String) { lines += "${m.id} INFO $message" }
            override fun warn(message: String) { lines += "${m.id} WARN $message" }
        } }
        register(TestClock::class.java, "clock.read") { object : TestClock { override fun now() = 42L } }
    }
}

class PermissionTest {
    @TempDir lateinit var tmp: Path
    private val lines = mutableListOf<String>()
    private val modules get() = tmp.resolve("modules")
    private val config get() = tmp.resolve("config")
    private fun host() = ModuleHost(modules, config, store = FileConfigStore(config, SchemaRegistry()), services = RecordingLog(lines).registry())

    private fun <T> withHost(body: (ModuleHost) -> T): T {
        val opened = host()
        try {
            return body(opened)
        } finally {
            opened.close()
            opened.store.close()
        }
    }
    private fun greedy(id: String, perms: List<String>) = modules.resolve(id).createDirectories().resolve("module.yaml")
        .writeText("id: $id\nversion: 1.0.0\nentrypoint: ${GreedyModule::class.qualifiedName}\npermissions: ${perms.joinToString(",", "[", "]")}\n")

    @Test fun declaredServicesGrantedAndBoundToModule() {
        greedy("all", listOf("log.write", "clock.read"))
        withHost { h ->
            h.installAll(); h.start("all")
            assertEquals(listOf("log", "clock"), (h.instance("all") as GreedyModule).got)
            assertEquals(listOf("all INFO hi"), lines)
        }
    }

    @Test fun undeclaredServiceAccessThrows() {
        greedy("logonly", listOf("log.write"))
        withHost { h ->
        h.installAll()
        val e = assertThrows<PermissionDeniedException> { h.start("logonly") }
        assertTrue(e.message!!.contains("clock.read")); assertTrue(e.message!!.contains("logonly"))
        assertEquals(listOf("log"), (h.instance("logonly") as GreedyModule).got)
        assertNotEquals(ModuleState.RUNNING, h.state("logonly"))
        }
    }

    @Test fun noPermissionsMeansNoServices() {
        greedy("none", emptyList())
        withHost { h ->
        h.installAll()
        assertThrows<PermissionDeniedException> { h.start("none") }
        assertTrue(lines.isEmpty())
        }
    }

    @Test fun unregisteredServiceIsNotFound() {
        val svc = ModuleServices(ManifestParser.parse("id: a\nversion: 1.0.0\nentrypoint: a.B\npermissions: [x]\n"), ServiceRegistry())
        assertThrows<NoSuchElementException> { svc.get(TestClock::class.java) }
    }

    @Test fun grantedReflectsManifest() {
        val m = ManifestParser.parse("id: a\nversion: 1.0.0\nentrypoint: a.B\npermissions: [log.write]\n")
        val s = ModuleServices(m, RecordingLog(lines).registry())
        assertTrue(s.isGranted(ModuleLog::class.java)); assertFalse(s.isGranted(TestClock::class.java))
    }

    // hello-world loaded from its packaged jar (not on the classpath)
    private val jar = Path.of(System.getProperty("oip.helloJar"))
    private val manifest = ZipFile(jar.toFile()).use { z -> z.getInputStream(z.getEntry("module.yaml")).readBytes().decodeToString() }
    private fun helloPkg(man: String) = ModulePackageTest.zip("module.yaml" to man.toByteArray(), "hello-world.jar" to jar.readBytes())

    @Test fun helloWorldWithLogWriteLogsGreeting() {
        withHost { h ->
        h.installPackage(helloPkg(manifest)); h.start("org.oip.hello-world")
        assertEquals(listOf("org.oip.hello-world INFO Hello, world!"), lines)
        }
    }

    @Test fun helloWorldWithoutLogWriteCannotLog() {
        val stripped = manifest.replace(Regex("(?ms)^permissions:\\r?\\n(  - .*?\\r?\\n)+"), "permissions: []\n")
        assertFalse(stripped.contains("log.write"))
        withHost { h ->
        h.installPackage(helloPkg(stripped))
        val e = assertThrows<PermissionDeniedException> { h.start("org.oip.hello-world") }
        assertTrue(e.message!!.contains("log.write"))
        assertTrue(lines.isEmpty())
        }
    }
}
