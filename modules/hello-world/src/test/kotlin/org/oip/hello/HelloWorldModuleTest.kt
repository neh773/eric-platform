package org.oip.hello

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.ResourceCodec
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.config.SchemaRegistry
import org.oip.config.ValidationException
import org.oip.host.ModuleHost
import org.oip.host.ModuleState
import org.oip.sdk.ManifestParser
import java.nio.file.Path
import kotlin.io.path.*

class HelloWorldModuleTest {
    @TempDir lateinit var tmp: Path
    private val id = "org.oip.hello-world"
    private val manifestText = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n")
    private val schemaDefault = ((ManifestParser.parse(manifestText).configSchema["properties"] as Map<*, *>)["greeting"] as Map<*, *>)["default"]
    private val config get() = tmp.resolve("config")
    private val resId = ResourceId(id, Scope.GATEWAY, "ModuleConfig", "default")
    private lateinit var store: FileConfigStore
    private lateinit var host: ModuleHost

    @BeforeEach fun setUp() {
        val modules = tmp.resolve("modules")
        modules.resolve("hello-world").createDirectories().resolve("module.yaml").writeText(manifestText)
        store = FileConfigStore(config, SchemaRegistry())
        host = ModuleHost(modules, config, store = store)
    }

    @Test fun installStartStopUninstallRoundTrip() {
        host.installAll()
        assertEquals(ModuleState.INSTALLED, host.state(id))
        val file = config.resolve("$id/gateway/ModuleConfig/default.yaml")
        assertTrue(file.exists())
        val doc = ResourceCodec.decode(file.readText())
        assertEquals("oip/v1", doc.apiVersion); assertEquals("ModuleConfig", doc.kind); assertEquals(id, doc.metadata.module)

        host.start(id)
        assertEquals(ModuleState.RUNNING, host.state(id))
        assertEquals("Hello, world!", (host.instance(id) as HelloWorldModule).lastGreeting)
        host.stop(id)
        assertEquals(ModuleState.STOPPED, host.state(id))

        host.uninstall(id)
        assertFalse(config.resolve(id).exists())
        assertTrue(host.installed().isEmpty())
        assertTrue(store.list(module = id).isEmpty())
    }

    @Test fun defaultGreetingComesFromConfigSchema() {
        assertEquals("Hello, world!", schemaDefault)
        host.installAll()
        assertEquals(schemaDefault, store.get(resId)!!.spec["greeting"])
        host.start(id)
        assertEquals(schemaDefault, (host.instance(id) as HelloWorldModule).lastGreeting)
    }

    @Test fun installConfigureUninstallLeavesZeroResources() {
        host.installAll()
        val v = store.get(resId)!!.metadata.version
        host.context(id)!!.config.put("ModuleConfig", "default", mapOf("greeting" to "Hi there"), version = v)
        host.start(id)
        assertEquals("Hi there", (host.instance(id) as HelloWorldModule).lastGreeting)
        host.uninstall(id)
        assertEquals(0, store.list(module = id).size)
        assertEquals(0, FileConfigStore(config, SchemaRegistry()).list().count { it.id.module == id })
        assertFalse(config.resolve(id).exists())
    }

    @Test fun invalidGreetingRejected() {
        host.installAll()
        val mc = host.context(id)!!.config
        val v = store.get(resId)!!.metadata.version
        for (bad in listOf<Any?>("", 42, "x".repeat(201), null))
            assertThrows(ValidationException::class.java, { mc.put("ModuleConfig", "default", mapOf("greeting" to bad), version = v) }, "greeting=$bad")
        assertThrows(ValidationException::class.java) { mc.put("ModuleConfig", "default", mapOf("greeting" to "ok", "extra" to 1), version = v) }
        assertEquals(schemaDefault, store.get(resId)!!.spec["greeting"])
    }

    @Test fun restartKeepsConfiguredGreeting() {
        host.installAll()
        host.context(id)!!.config.put("ModuleConfig", "default", mapOf("greeting" to "Persisted"), version = 1)
        val store2 = FileConfigStore(config, SchemaRegistry())
        val host2 = ModuleHost(tmp.resolve("modules"), config, store = store2)
        host2.installAll(); host2.start(id)
        assertEquals("Persisted", (host2.instance(id) as HelloWorldModule).lastGreeting)
    }
}
