package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.sim.bacnet.BacnetSimulator
import org.oip.tags.QualityCode
import org.oip.tags.TagCaller
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * BACnet/IP through the gateway: a local device and a remote device saved with the config API, OPC tags on
 * browsed objects and their status flags, the device catalog, the device's system tags and `system.bacnet`.
 * The peer is the in-repo BACnet/IP simulator on loopback.
 * doc source: FEATURES DEV-011, DEV-012, DEV-038, SCR-007
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class BacnetGatewayTest {
    @TempDir lateinit var tmp: Path

    @Test fun remoteDeviceFeedsTagsTheCatalogSystemTagsAndScripts() {
        BacnetSimulator(260001, deviceName = "AHU-1").use { sim ->
            sim.add(BacnetSimulator.ANALOG_INPUT, 0, "Supply Temp", 72.5f)
            sim.add(BacnetSimulator.ANALOG_OUTPUT, 1, "Damper", 0.0f)
            Gateway(tmp, port = 0).use { g ->
                g.start()
                val http = Http(g.port())
                g.loginAdmin(http)
                val badLocal = http.put(url("LocalDevice", "gw"), body("LocalDevice", "gw", """"deviceInstance":4194303,"deviceName":"Gateway""""))
                assertEquals(422, badLocal.status, badLocal.body)
                val local = http.put(url("LocalDevice", "gw"), body("LocalDevice", "gw",
                    """"deviceInstance":1001,"deviceName":"Gateway","bindAddress":"127.0.0.1","port":0,"broadcastAddress":"127.0.0.1","apduTimeoutMs":300,"apduRetries":1"""))
                assertEquals(201, local.status, local.body)
                val remote = http.put(url("RemoteDevice", "ahu"), body("RemoteDevice", "ahu",
                    """"localDevice":"gw","deviceInstance":260001,"host":"127.0.0.1","port":${sim.port},"reconnectMs":100"""))
                assertEquals(201, remote.status, remote.body)

                val catalog = GatewayDeviceCatalog(g)
                await("object list browsed") { catalog.device("ahu")?.browse("")?.any { it.nodeId == "analog-input:0" } == true }
                val endpoint = catalog.device("ahu")!!
                assertEquals("bacnet", endpoint.driver)
                assertTrue(endpoint.browse("analog-input:0").any { it.nodeId == "analog-input:0/StatusFlags" })
                assertEquals(4, endpoint.browse("analog-input:0/StatusFlags").size)

                assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/temp", tagBody("temp", "ahu/temp", "Double", "analog-input:0/PresentValue")).status)
                assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/fault", tagBody("fault", "ahu/fault", "Boolean", "analog-input:0/StatusFlags/Fault")).status)
                assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/damper", tagBody("damper", "ahu/damper", "Double", "analog-output:1/PresentValue;priority=10")).status)
                await("tags") {
                    val temp = g.tags.read("ahu/temp")
                    (temp.value as? Number)?.toDouble() == 72.5 && temp.quality.code == QualityCode.GOOD && g.tags.read("ahu/fault").value == false
                }
                sim.obj(BacnetSimulator.ANALOG_INPUT, 0).fault = true
                await("status flag") { g.tags.read("ahu/fault").value == true }
                val written = g.tags.write("ahu/damper", 25.0)
                assertEquals(QualityCode.GOOD, written.quality.code, written.quality.diagnostic)
                await("written at priority 10: ${sim.writes}") { sim.obj(BacnetSimulator.ANALOG_OUTPUT, 1).priority[9] == 25f }

                await("system tags") {
                    g.tags.read("[system]Gateway/Device/ahu/Status").value == "Connected" &&
                        g.tags.read("[system]Gateway/Device/ahu/Diagnostics/Connected").value == true
                }
                assertTrue(((g.tags.read("[system]Gateway/Device/ahu/Diagnostics/RequestCount").value as Number).toLong()) > 0)

                val script = g.scripts.run(
                    """
                    temp = system.bacnet.readRaw("ahu", "analog-input", 0, 85)
                    count = system.bacnet.readRaw("ahu", 8, 260001, 76, 0)
                    w = system.bacnet.writeWithPriority("ahu", "analog-output", 1, 85, 40, 8)
                    bad = system.bacnet.writeRaw("ahu", "analog-output", 1, 85, 1, 17)
                    sent = system.bacnet.synchronizeTimeUtc("ahu")
                    _result = {"temp": temp, "count": count, "good": w["good"], "bad": bad["quality"], "sent": sent}
                    """.trimIndent(),
                )
                assertTrue(script.ok, script.error)
                @Suppress("UNCHECKED_CAST")
                val result = script.value as Map<String, Any?>
                assertEquals(72.5, (result["temp"] as Number).toDouble())
                assertEquals(3, (result["count"] as Number).toInt())
                assertEquals(true, result["good"])
                assertEquals("Bad_OutOfRange", result["bad"])
                assertEquals(true, result["sent"])
                assertEquals(40f, sim.obj(BacnetSimulator.ANALOG_OUTPUT, 1).priority[7])
                await("time sync received") { sim.timeSyncs.any { it.utc } }
                val missing = g.scripts.run("system.bacnet.readRaw('nope', 'analog-input', 0, 85)\n")
                assertFalse(missing.ok)
                assertTrue(missing.error!!.contains("not configured"), missing.error)
                val session = g.scripts.runForUser("system.bacnet.readRaw('ahu', 0, 0, 85)\n", "{}", TagCaller.User("ada", setOf("operator"), "default"))
                assertFalse(session.ok)
                assertTrue(session.error!!.contains("gateway script only"), session.error)

                assertEquals(204, http.delete(url("RemoteDevice", "ahu")).status)
                await("device removed") { catalog.device("ahu") == null }
                assertNull(catalog.device("ahu"))
                assertNotNull(g.tags.read("ahu/temp"))
            }
        }
    }

    private fun url(kind: String, name: String) = "/api/v1/config/org.oip.driver-bacnet/gateway/$kind/$name"

    private fun body(kind: String, name: String, spec: String) = """
        {"apiVersion":"oip/v1","kind":"$kind","metadata":{"name":"$name","module":"org.oip.driver-bacnet","version":0},
         "spec":{$spec}}
    """.trimIndent()

    private fun tagBody(name: String, path: String, type: String, item: String) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"$path","type":"opc","dataType":"$type","opcServer":"ahu",
           "opcItemPath":"$item","scanPeriodMillis":100}}
    """.trimIndent()

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError(message)
    }
}
