package org.oip.udptcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import org.oip.host.ModuleHost
import org.oip.host.ModuleState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** The module registers its kind, refuses bad devices at save time and follows config changes. doc source: FEATURES DEV-037 */
@Timeout(60)
class UdpTcpModuleTest {
    @TempDir lateinit var tmp: Path

    @Test fun schemaDefaultsAndSaveTimeChecks() {
        val registry = SchemaRegistry()
        registry.register(UdpTcpDriverModule.MODULE_ID, DEVICE_KIND, listenerDeviceSchema())
        registry.registerSemantic(UdpTcpDriverModule.MODULE_ID, DEVICE_KIND, ::listenerDeviceErrors)
        fun errors(spec: Map<String, Any?>) = registry.validate(Resource(DEVICE_KIND, Metadata("scale", UdpTcpDriverModule.MODULE_ID), spec))
        assertTrue(errors(mapOf("ports" to listOf(9100), "fieldCount" to 3)).isEmpty())
        assertTrue(errors(mapOf("ports" to listOf(9100), "protocol" to "udp", "mode" to "connect", "address" to "10.0.0.5")).single().contains("UDP"))
        assertTrue(errors(mapOf("ports" to listOf(9100), "delimiterType" to "fixed", "fixedLength" to 0)).single().contains("fixedLength"))
        assertTrue(errors(mapOf("ports" to listOf(9100), "mode" to "connect")).single().contains("device address"))
        assertTrue(errors(mapOf("ports" to listOf(9100, 9100))).isNotEmpty())
        assertTrue(errors(mapOf("ports" to listOf(70000))).isNotEmpty())
        assertTrue(errors(mapOf("ports" to listOf(9100), "maxMessageBytes" to 1_000_000)).isNotEmpty())
        val applied = registry.applyDefaults(UdpTcpDriverModule.MODULE_ID, DEVICE_KIND, mapOf("ports" to listOf(9100)))
        val config = listenerDeviceConfig("scale", applied)
        assertEquals(Protocol.TCP, config.protocol)
        assertEquals(Mode.LISTEN, config.mode)
        assertEquals(DelimiterType.CHARACTER, config.delimiterType)
        assertEquals(4096, config.maxMessageBytes)
        val file = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n").trim()
        assertEquals(file, UdpTcpDriverModule.MANIFEST_YAML.trim())
    }

    @Test fun deviceFollowsConfigAndUninstallClosesIt() {
        val id = UdpTcpDriverModule.MODULE_ID
        val modules = tmp.resolve("modules")
        modules.resolve("driver-udp-tcp").createDirectories().resolve("module.yaml").writeText(javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n"))
        val configDir = tmp.resolve("config")
        val store = FileConfigStore(configDir, SchemaRegistry())
        val host = ModuleHost(modules, configDir, store = store)
        host.installAll()
        host.start(id)
        assertEquals(ModuleState.RUNNING, host.state(id))
        val module = host.instance(id) as UdpTcpDriverModule
        assertThrows(ValidationException::class.java) {
            store.put(Scope.GATEWAY, device(mapOf("ports" to listOf(9100), "protocol" to "udp", "mode" to "connect", "address" to "10.0.0.5")), "test")
        }

        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        store.put(Scope.GATEWAY, device(mapOf("address" to "127.0.0.1", "ports" to listOf(port), "messageDelimiter" to "\\n", "fieldCount" to 2)), "test")
        await("device") { module.device("scale")?.connectionStatus()?.connected == true }
        val live = module.device("scale")
        assertNotNull(live)
        assertEquals(4096, live!!.config.maxMessageBytes, "the module default applies")
        Socket("127.0.0.1", port).use { client ->
            client.getOutputStream().apply { write("12,kg\n".toByteArray()); flush() }
            await("message") { live.read(listOf("$port/Field1")).single().value == "12" }
        }
        host.stop(id)
        host.uninstall(id)
        assertEquals(0, live.openChannelCount)
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)) }
        assertFalse(configDir.resolve(id).exists())
    }

    private fun device(spec: Map<String, Any?>) = Resource(DEVICE_KIND, Metadata("scale", UdpTcpDriverModule.MODULE_ID), spec)

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }
}
