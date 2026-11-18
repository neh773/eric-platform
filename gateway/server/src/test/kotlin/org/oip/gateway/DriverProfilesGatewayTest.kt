package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.sim.logix.Ab
import org.oip.sim.logix.LogixSimulator
import org.oip.sim.logix.SimFirmware
import org.oip.tags.QualityCode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Logix profiles and route paths, and the UDP/TCP listener, through the gateway config API, OPC
 * tags and the device catalog. Peers are the in-repo Logix simulator and a loopback test client.
 * doc source: FEATURES DEV-009, DEV-017, DEV-021, DEV-023, DEV-037
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DriverProfilesGatewayTest {
    @TempDir lateinit var tmp: Path

    @Test fun logixRouteAndProfileAreCheckedOnSaveAndAMicro800ServesTags() {
        LogixSimulator(firmware = SimFirmware.MICRO800).use { sim ->
            sim.shortString("Label", "lot 7")
            sim.atomic("Weight", Ab.UDINT, 3_000_000_000L)
            Gateway(tmp, port = 0).use { g ->
                g.start()
                val http = Http(g.port())
                g.loginAdmin(http)
                val badRoute = http.put(logixUrl("bridge"), logixBody("bridge", sim.port, """"connectionPath":"1,3,2,192.168.0.999,1,0""""))
                assertEquals(422, badRoute.status, badRoute.body)
                assertTrue(badRoute.body.contains("IPv4"), badRoute.body)
                val tooLarge = http.put(logixUrl("m8"), logixBody("m8", sim.port, """"profile":"micro800","cipConnectionSize":1000"""))
                assertEquals(422, tooLarge.status, tooLarge.body)
                assertTrue(tooLarge.body.contains("511"), tooLarge.body)
                val legacyInstance = http.put(logixUrl("old"), logixBody("old", sim.port, """"profile":"legacy-controllogix","symbolInstanceAddressing":true"""))
                assertEquals(422, legacyInstance.status, legacyInstance.body)

                val ok = http.put(logixUrl("m8"), logixBody("m8", sim.port, """"profile":"micro800""""))
                assertEquals(201, ok.status, ok.body)
                assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/label", tagBody("label", "plant/label", "String", "m8", "Label")).status)
                assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/weight", tagBody("weight", "plant/weight", "Int64", "m8", "Weight")).status)
                await("micro800 tags") {
                    val label = g.tags.read("plant/label")
                    val weight = g.tags.read("plant/weight")
                    label.value == "lot 7" && label.quality.code == QualityCode.GOOD &&
                        (weight.value as? Number)?.toLong() == 3_000_000_000L
                }
                val written = g.tags.write("plant/label", "lot 8")
                assertEquals(QualityCode.GOOD, written.quality.code, written.quality.diagnostic)
                await("written") { sim.get("Label") == "lot 8" }
                val endpoint = GatewayDeviceCatalog(g).device("m8")
                assertNotNull(endpoint)
                assertEquals(listOf("Controller:Global"), endpoint!!.browse("").map { it.nodeId })
            }
        }
    }

    @Test fun udpTcpListenerFeedsTagsAndTheCatalog() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            val bad = http.put(udpUrl("scanner"), udpBody("scanner", """"protocol":"udp","mode":"connect","address":"10.0.0.5","ports":[$port]"""))
            assertEquals(422, bad.status, bad.body)
            val put = http.put(
                udpUrl("scale"),
                udpBody("scale", """"address":"127.0.0.1","ports":[$port],"messageDelimiter":"\\r\\n","fieldCount":3"""),
            )
            assertEquals(201, put.status, put.body)
            assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/net", tagBody("net", "scale/net", "String", "scale", "$port/Field2")).status)
            assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/count", tagBody("count", "scale/count", "Int64", "scale", "$port/MessageCount")).status)
            val catalog = GatewayDeviceCatalog(g)
            await("listening") { catalog.device("scale")?.browse("")?.map { it.nodeId } == listOf(port.toString()) }
            Socket("127.0.0.1", port).use { client ->
                client.getOutputStream().apply { write("S1,12.50,kg\r\nS1,12.75,kg\r\n".toByteArray()); flush() }
                await("tags") {
                    val net = g.tags.read("scale/net")
                    net.value == "12.75" && net.quality.code == QualityCode.GOOD &&
                        (g.tags.read("scale/count").value as? Number)?.toLong() == 2L
                }
            }
            val endpoint = catalog.device("scale")!!
            assertTrue(endpoint.browse(port.toString()).any { it.nodeId == "$port/Field3" })
            assertEquals("kg", endpoint.read(listOf("$port/Field3")).single().value)
            assertTrue(endpoint.write("$port/Message", "x") != 0L, "the listener is receive-only")

            assertEquals(204, http.delete(udpUrl("scale")).status)
            await("port released") {
                runCatching { ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)) } }.isSuccess
            }
            assertEquals(null, catalog.device("scale"))
        }
    }

    private fun logixUrl(name: String) = "/api/v1/config/org.oip.driver-logix/gateway/Device/$name"

    private fun logixBody(name: String, port: Int, extra: String) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"$name","module":"org.oip.driver-logix","version":0},
         "spec":{"name":"$name","host":"127.0.0.1","port":$port,"timeoutMs":500,"identityRequestFrequencyMs":0,
           "backoffInitialMs":40,"backoffMaxMs":200,"defaultScanPeriodMs":200,$extra}}
    """.trimIndent()

    private fun udpUrl(name: String) = "/api/v1/config/org.oip.driver-udp-tcp/gateway/Device/$name"

    private fun udpBody(name: String, spec: String) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"$name","module":"org.oip.driver-udp-tcp","version":0},
         "spec":{$spec}}
    """.trimIndent()

    private fun tagBody(name: String, path: String, type: String, server: String, item: String) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"$path","type":"opc","dataType":"$type","opcServer":"$server",
           "opcItemPath":"$item","scanPeriodMillis":100}}
    """.trimIndent()

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError(message)
    }
}
