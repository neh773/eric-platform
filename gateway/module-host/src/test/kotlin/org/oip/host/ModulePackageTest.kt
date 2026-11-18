package org.oip.host

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.sdk.ManifestException
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

/** Packages and upgrades, using the real hello-world jar loaded only through the module class loader. */
class ModulePackageTest {
    @TempDir lateinit var tmp: Path
    private val id = "org.oip.hello-world"
    private val entry = "org.oip.hello.HelloWorldModule"
    private val jar = Path.of(System.getProperty("oip.helloJar") ?: error("oip.helloJar not set"))
    private val manifest = ZipFile(jar.toFile()).use { z -> z.getInputStream(z.getEntry("module.yaml")).readBytes().decodeToString() }
    private val modules get() = tmp.resolve("modules")
    private val config get() = tmp.resolve("config")
    private val res = ResourceId(id, Scope.GATEWAY, "ModuleConfig", "default")
    private lateinit var host: ModuleHost

    companion object {
        fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } } }.toByteArray()
    }
    private fun pkg(version: String = "1.0.0", man: String = manifest.replace(Regex("(?m)^version: .*$"), "version: $version")) =
        zip("module.yaml" to man.toByteArray(), "hello-world.jar" to jar.readBytes())
    private fun newHost() = ModuleHost(modules, config, store = FileConfigStore(config, SchemaRegistry()))
    private fun greeting() = host.instance(id)!!.javaClass.getMethod("getLastGreeting").invoke(host.instance(id)) as String?

    @BeforeEach fun setUp() { host = newHost() }

    @AfterEach fun tearDown() {
        if (::host.isInitialized) {
            host.close()
            host.store.close()
        }
    }

    @Test fun entrypointIsNotOnTestClasspath() {
        assertThrows<ClassNotFoundException> { Class.forName(entry) }
        assertTrue(jar.exists())
    }

    @Test fun jarLoadedHelloWorldRoundTrip() {
        val m = host.installPackage(pkg())
        assertEquals(id, m.id)
        assertEquals(ModuleState.INSTALLED, host.state(id))
        val inst = host.instance(id)!!
        assertEquals(entry, inst.javaClass.name)
        assertTrue(inst.javaClass.classLoader is ModuleClassLoader)
        assertEquals("Hello, world!", host.store.get(res)!!.spec["greeting"])
        host.start(id)
        assertEquals("Hello, world!", greeting())
        host.stop(id)
        host.uninstall(id, removeFiles = true)
        assertNull(host.state(id))
        assertFalse(modules.resolve(id).exists()); assertFalse(config.resolve(id).exists())
        assertTrue(host.store.list(id).isEmpty())
        assertEquals("", tmp.resolve("installed-modules.tsv").readText())
    }

    @Test fun moduleClassLoaderIsIsolatedFromHostInternals() {
        host.installPackage(pkg())
        val cl = host.instance(id)!!.javaClass.classLoader
        assertThrows<ClassNotFoundException> { cl.loadClass(ModuleHost::class.java.name) }
        assertSame(org.oip.sdk.Module::class.java, cl.loadClass("org.oip.sdk.Module"))
    }

    @Test fun upgradeKeepsConfigAndRestartsIfRunning() {
        host.installPackage(pkg("1.0.0"))
        host.context(id)!!.config.put("ModuleConfig", "default", mapOf("greeting" to "Hi"), version = host.store.get(res)!!.metadata.version)
        host.start(id)
        val m = host.upgradePackage(pkg("1.1.0"))
        assertEquals("1.1.0", m.version.toString())
        assertEquals("1.1.0", host.manifest(id)!!.version.toString())
        assertEquals(ModuleState.RUNNING, host.state(id))
        assertEquals("Hi", host.store.get(res)!!.spec["greeting"])
        assertEquals("Hi", greeting())
        // survives restart as 1.1.0 with config kept
        host.stopAll()
        val again = newHost()
        try {
            again.discover()
            assertEquals(ModuleState.INSTALLED, again.state(id))
            assertEquals("1.1.0", again.manifest(id)!!.version.toString())
            assertEquals("Hi", again.store.get(res)!!.spec["greeting"])
        } finally {
            again.close()
            again.store.close()
        }
    }

    @Test fun downgradeAndSameVersionRefused() {
        host.installPackage(pkg("1.1.0"))
        assertThrows<UpgradeException> { host.upgradePackage(pkg("1.0.0")) }
        assertThrows<UpgradeException> { host.upgradePackage(pkg("1.1.0")) }
        assertEquals("1.1.0", host.manifest(id)!!.version.toString())
        assertEquals(ModuleState.INSTALLED, host.state(id))
    }

    @Test fun upgradeOfUnknownModuleRefused() {
        assertThrows<NoSuchElementException> { host.upgradePackage(pkg()) }
    }

    @Test fun installingTwiceRefused() {
        host.installPackage(pkg())
        assertThrows<IllegalStateException> { host.installPackage(pkg("1.2.0")) }
    }

    @Test fun invalidPackagesRejectedWithoutResidue() {
        assertThrows<ManifestException> { host.installPackage(zip("hello-world.jar" to jar.readBytes())) }
        assertThrows<ManifestException> { host.installPackage(zip("module.yaml" to manifest.toByteArray(), "../evil.jar" to byteArrayOf(1))) }
        assertThrows<ManifestException> { host.installPackage(zip("module.yaml" to manifest.toByteArray(), "notes.txt" to byteArrayOf(1))) }
        assertThrows<ManifestException> { host.installPackage("not a zip".toByteArray()) }
        assertThrows<ManifestException> { host.installPackage(zip("module.yaml" to "id: x\n".toByteArray())) }
        assertTrue(!modules.exists() || modules.listDirectoryEntries().isEmpty())
    }

    @Test fun failedInstallLeavesNoFiles() {
        val bad = manifest.replace(entry, "org.oip.hello.Missing")
        assertThrows<ClassNotFoundException> { host.installPackage(zip("module.yaml" to bad.toByteArray(), "hello-world.jar" to jar.readBytes())) }
        assertFalse(modules.resolve(id).exists()); assertFalse(config.resolve(id).exists()); assertNull(host.state(id))
    }
}
