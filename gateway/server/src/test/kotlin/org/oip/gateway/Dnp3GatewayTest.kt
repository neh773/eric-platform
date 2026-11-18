package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.sim.dnp3.Dnp3OutstationSimulator
import org.oip.tags.QualityCode
import org.oip.tags.TagCaller
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * DNP3 through the gateway: an outstation saved with the config API, OPC tags on its points, a tag write that
 * becomes a command, the device catalog, the device's system tags and `system.dnp`. The peer is the in-repo DNP3
 * outstation simulator on loopback TCP.
 * doc source: FEATURES DEV-013, DEV-014, SCR-003
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Dnp3GatewayTest {
    @TempDir lateinit var tmp: Path

    @Test fun outstationFeedsTagsTheCatalogSystemTagsAndScripts() {
        Dnp3OutstationSimulator().use { sim ->
            sim.binaryInput(0, true, event = false)
            sim.binaryOutput(0, false)
            sim.analogInput(0, 12.5, event = false)
            sim.analogOutput(0, 0L)
            Gateway(tmp, port = 0).use { g ->
                g.start()
                val http = Http(g.port())
                g.loginAdmin(http)
                val bad = http.put(url("rtu"), body("rtu", """"host":"127.0.0.1","port":${sim.port},"timeSync":"sometimes""""))
                assertEquals(422, bad.status, bad.body)
                val put = http.put(url("rtu"), body("rtu",
                    """"host":"127.0.0.1","port":${sim.port},"reconnectMs":100,"responseTimeoutMs":1000,"eventPeriodMs":100,"integrityPeriodMs":0"""))
                assertEquals(201, put.status, put.body)

                assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/temp", tagBody("temp", "rtu/temp", "Double", "ai:0")).status)
                assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/online", tagBody("online", "rtu/online", "Boolean", "ai:0/Online")).status)
                assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/run", tagBody("run", "rtu/run", "Boolean", "bo:0;mode=direct")).status)
                await("tags") {
                    val temp = g.tags.read("rtu/temp")
                    (temp.value as? Number)?.toDouble() == 12.5 && temp.quality.code == QualityCode.GOOD && g.tags.read("rtu/online").value == true
                }
                sim.analogInput(0, 20.0)
                await("event polled") { (g.tags.read("rtu/temp").value as? Number)?.toDouble() == 20.0 }
                val written = g.tags.write("rtu/run", true)
                assertEquals(QualityCode.GOOD, written.quality.code, written.quality.diagnostic)
                await("latched") { sim.binaryOutputs[0]?.value == true }
                assertEquals(0, sim.requestCount(3), "the tag's mode=direct skips select")

                val endpoint = GatewayDeviceCatalog(g).device("rtu")!!
                assertEquals("dnp3", endpoint.driver)
                assertTrue(endpoint.browse("").any { it.nodeId == "ai" })
                assertTrue(endpoint.browse("bo").single { it.nodeId == "bo:0" }.writable)

                await("system tags") {
                    g.tags.read("[system]Gateway/Device/rtu/Status").value == "Connected" &&
                        g.tags.read("[system]Gateway/Device/rtu/Diagnostics/Connected").value == true
                }

                val script = g.scripts.run(
                    """
                    a = system.dnp.selectOperateBinary("rtu", 0, "NUL", "LATCH_OFF")
                    b = system.dnp.directOperateAnalog("rtu", 0, 42)
                    c = system.dnp.directOperateBinary("rtu", 7, 0, 3)
                    _result = {"a": a["ok"], "b": b["statusName"], "c": c["statusName"], "cok": c["ok"]}
                    """.trimIndent(),
                )
                assertTrue(script.ok, script.error)
                @Suppress("UNCHECKED_CAST")
                val result = script.value as Map<String, Any?>
                assertEquals(true, result["a"])
                assertEquals("SUCCESS", result["b"])
                assertEquals("NOT_SUPPORTED", result["c"])
                assertEquals(false, result["cok"])
                assertEquals(false, sim.binaryOutputs[0]?.value)
                assertEquals(42L, sim.analogOutputs[0]?.value)
                assertEquals(1, sim.requestCount(3), "select before operate")
                val badOp = g.scripts.run("system.dnp.directOperateBinary('rtu', 0, 'NUL', 'TOGGLE')\n")
                assertFalse(badOp.ok)
                assertTrue(badOp.error!!.contains("opType"), badOp.error)
                val session = g.scripts.runForUser("system.dnp.directOperateAnalog('rtu', 0, 1)\n", "{}", TagCaller.User("ada", setOf("operator"), "default"))
                assertFalse(session.ok)
                assertTrue(session.error!!.contains("gateway script only"), session.error)

                assertEquals(204, http.delete(url("rtu")).status)
                await("outstation removed") { GatewayDeviceCatalog(g).device("rtu") == null && !sim.hasClient }
            }
        }
    }

    private fun url(name: String) = "/api/v1/config/org.oip.driver-dnp3/gateway/Outstation/$name"

    private fun body(name: String, spec: String) = """
        {"apiVersion":"oip/v1","kind":"Outstation","metadata":{"name":"$name","module":"org.oip.driver-dnp3","version":0},
         "spec":{$spec}}
    """.trimIndent()

    private fun tagBody(name: String, path: String, type: String, item: String) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"$path","type":"opc","dataType":"$type","opcServer":"rtu",
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
