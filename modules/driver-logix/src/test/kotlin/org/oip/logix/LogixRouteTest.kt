package org.oip.logix

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.config.SchemaRegistry
import org.oip.config.ValidationException
import org.oip.host.ModuleHost
import org.oip.sim.logix.Ab
import org.oip.sim.logix.LogixSimulator
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * CIP route paths: backplane hops and port/link pairs through a bridge module, used in Forward
 * Open and in Unconnected Send, and checked when the device is saved.
 *
 * The peer is the in-repo simulator (test-infra/sim/logix) standing for a controller behind
 * bridge modules; no bridge hardware was used. doc source: FEATURES DEV-009
 */
@Timeout(60)
class LogixRouteTest {
    @TempDir lateinit var tmp: Path

    /** The Ethernet bridge example from the public connection path page: `1,3,2,192.168.0.56,1,0`. */
    @Test fun routedDeviceOpensItsConnectionAlongTheBridgePath() {
        val path = "1,3,2,192.168.0.56,1,0"
        LogixSimulator(route = path).use { sim ->
            sim.atomic("Speed", Ab.DINT, 12)
            val device = device(sim, path)
            device.start()
            awaitConnected(device)
            assertEquals("connected", device.messaging)
            assertEquals(12, device.read(listOf("Speed")).single().value)
            assertEquals(QualityCode.Good, device.write("Speed", 13).quality.code)
            assertEquals(13, sim.get("Speed"))
            val ip = "192.168.0.56".toByteArray(Charsets.US_ASCII)
            val expected = byteArrayOf(0x01, 0x03, 0x12, ip.size.toByte()) + ip + byteArrayOf(0x01, 0x00)
            assertArrayEquals(expected, sim.lastForwardOpenRoute)
            device.close()

            val wrong = device(sim, "1,3,2,192.168.0.57,1,0")
            wrong.start()
            await("rejected") { wrong.reconnectDelaysMs.isNotEmpty() }
            assertFalse(wrong.connectionStatus().connected)
            wrong.close()
        }
    }

    /** ControlNet and DH+ examples from the same page: numeric node and station links. */
    @Test fun controlNetAndDhPlusHopsAreNumericLinks() {
        LogixSimulator(route = "1,4,2,12,1,0").use { sim ->
            sim.atomic("Level", Ab.INT, 5)
            val device = device(sim, "1,4,2,12,1,0")
            device.start()
            awaitConnected(device)
            assertEquals(5, device.read(listOf("Level")).single().value)
            assertArrayEquals(byteArrayOf(0x01, 0x04, 0x02, 0x0C, 0x01, 0x00), sim.lastForwardOpenRoute)
            device.close()
        }
        LogixSimulator(route = "1,3,3,40").use { sim ->
            sim.atomic("Count", Ab.DINT, 9)
            val device = device(sim, "1,3,3,40")
            device.start()
            awaitConnected(device)
            assertEquals(9, device.read(listOf("Count")).single().value)
            assertArrayEquals(byteArrayOf(0x01, 0x03, 0x03, 0x28), sim.lastForwardOpenRoute)
            device.close()
        }
    }

    @Test fun refusedForwardOpenFallsBackToUnconnectedSendAlongTheRoute() {
        val path = "1,4,2,12,1,0"
        LogixSimulator(route = path).use { sim ->
            sim.refuseConnections = true
            sim.atomic("Speed", Ab.DINT, 3)
            sim.array("Counts", Ab.DINT, (0 until 200).toList())
            val device = device(sim, path)
            device.start()
            awaitConnected(device)
            assertEquals("unconnected", device.messaging)
            val before = sim.unconnectedSends.get()
            assertEquals(3, device.read(listOf("Speed")).single().value)
            assertEquals(199, device.read(listOf("Counts[199]")).single().value)
            assertEquals(QualityCode.Good, device.write("Speed", 4).quality.code)
            assertEquals(4, sim.get("Speed"))
            assertTrue(sim.unconnectedSends.get() >= before + 3, "unconnected sends ${sim.unconnectedSends.get()}")
            assertArrayEquals(byteArrayOf(0x01, 0x04, 0x02, 0x0C, 0x01, 0x00), sim.lastUnconnectedRoute)
            device.close()

            val wrong = device(sim, "1,4,2,13,1,0")
            wrong.start()
            await("rejected") { wrong.reconnectDelaysMs.isNotEmpty() }
            assertFalse(wrong.connectionStatus().connected)
            assertArrayEquals(byteArrayOf(0x01, 0x04, 0x02, 0x0D, 0x01, 0x00), sim.lastUnconnectedRoute)
            wrong.close()
        }
    }

    @Test fun routeEncodingAndUnconnectedSendFraming() {
        assertArrayEquals(byteArrayOf(0x01, 0x04, 0x02, 0x0C, 0x01, 0x00), Cip.route("1,4,2,12,1,0", 0))
        val ip9 = "10.0.0.12".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(byteArrayOf(0x12, 9) + ip9 + byteArrayOf(0) + byteArrayOf(0x01, 0x00), Cip.route("2,10.0.0.12,1,0", 0))
        val read = Cip.readTag(Cip.symbol("A"), 1)
        val route = byteArrayOf(0x01, 0x02)
        val wrapped = Cip.unconnectedSend(read, route, 2_000)
        assertEquals(0x52, wrapped[0].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0x20, 0x06, 0x24, 0x01), wrapped.copyOfRange(2, 6))
        assertEquals(read.size, u16(wrapped, 8))
        assertArrayEquals(read, wrapped.copyOfRange(10, 10 + read.size))
        val pad = read.size % 2
        assertEquals(1, wrapped[10 + read.size + pad].toInt())
        assertArrayEquals(route, wrapped.copyOfRange(12 + read.size + pad, wrapped.size))
    }

    @Test fun connectionPathIsCheckedAtSaveTime() {
        val bad = listOf(
            "1,3,2" to "pairs",
            "1,300" to "0..255",
            "1,a.b" to "slot number",
            "16,1" to "1..14",
            "1,3,2,192.168.0.999,1,0" to "IPv4",
            "1,,2,3" to "empty entry",
            "x,1" to "not a number",
        )
        val registry = SchemaRegistry()
        registry.register(LogixDriverModule.MODULE_ID, DEVICE_KIND, logixDeviceSchema())
        registry.registerSemantic(LogixDriverModule.MODULE_ID, DEVICE_KIND, ::logixDeviceErrors)
        for ((path, words) in bad) {
            val errors = registry.validate(resource(mapOf("host" to "10.0.0.8", "connectionPath" to path)))
            assertTrue(errors.any { it.contains(words) }, "$path -> $errors")
        }
        assertTrue(registry.validate(resource(mapOf("host" to "10.0.0.8", "connectionPath" to "1,3,2,192.168.0.56,1,0"))).isEmpty())

        // Through the module's own registration, the store refuses the save.
        val modules = tmp.resolve("modules")
        modules.resolve("driver-logix").createDirectories().resolve("module.yaml").writeText(javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n"))
        val configDir = tmp.resolve("config")
        val store = FileConfigStore(configDir, SchemaRegistry())
        val host = ModuleHost(modules, configDir, store = store)
        host.installAll()
        host.start(LogixDriverModule.MODULE_ID)
        val refused = assertThrows(ValidationException::class.java) {
            store.put(Scope.GATEWAY, resource(mapOf("host" to "127.0.0.1", "connectionPath" to "1,3,2,192.168.0.999,1,0")), "test")
        }
        assertTrue(refused.errors.single().contains("IPv4"), refused.errors.toString())
        store.put(Scope.GATEWAY, resource(mapOf("host" to "127.0.0.1", "connectionPath" to "1,3,2,192.168.0.56,1,0", "enabled" to false)), "test")
        host.stop(LogixDriverModule.MODULE_ID)
        host.uninstall(LogixDriverModule.MODULE_ID)
    }

    private fun resource(spec: Map<String, Any?>) =
        Resource(DEVICE_KIND, Metadata("line", LogixDriverModule.MODULE_ID), spec)

    private fun device(sim: LogixSimulator, path: String) = LogixDevice(
        LogixDeviceConfig(
            name = "routed",
            host = "127.0.0.1",
            port = sim.port,
            timeoutMs = 1_000,
            connectionPath = path,
            identityRequestFrequencyMs = 0,
            backoffInitialMs = 40,
            backoffMaxMs = 200,
        ),
    )

    private fun awaitConnected(device: LogixDevice) {
        await("connected ${device.connectionStatus()}") { device.connectionStatus().connected }
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }
}
