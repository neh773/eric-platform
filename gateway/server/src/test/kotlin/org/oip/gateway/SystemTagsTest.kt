package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * System tags published from the gateway, read through the tag engine and `POST /api/v1/tags/read`.
 */
class SystemTagsTest {
    @TempDir lateinit var tmp: Path

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun gatewayHealthTagsReadThroughEngineAndRest() {
        TinyModbusSlave().use { slave ->
            tmp.resolve("gateway.yaml").writeText(
                "profile: standard\nopcua:\n  enabled: true\n  bindAddress: 127.0.0.1\n  port: 0\n  hostname: 127.0.0.1\n",
            )
            Gateway(tmp, port = 0).use { g ->
                g.start()
                val http = Http(g.port())
                g.loginAdmin(http)

                // Fast group: published at start, then every second.
                val first = g.tags.read("[system]Gateway/CurrentDateTime")
                assertEquals("Good", first.quality.code.label)
                val firstTime = first.value
                await("clock advances") { g.tags.read("[system]Gateway/CurrentDateTime").value != firstTime }
                await("uptime counts") { ((g.tags.read("[system]Gateway/UptimeSeconds").value as? Number)?.toLong() ?: -1) >= 1 }

                val identity = read(http, listOf(
                    "[system]Gateway/SystemName", "[system]Gateway/Timezone", "[system]Gateway/Version",
                    "[system]Gateway/Name", "[system]Gateway/DeploymentMode",
                ))
                assertTrue((identity[0]["value"] as String).isNotBlank())
                assertTrue((identity[1]["value"] as String).isNotBlank())
                assertEquals(Gateway.VERSION, identity[2]["value"])
                assertEquals("open-industrial-platform", identity[3]["value"])
                assertEquals("Good", identity[4]["quality"])
                assertEquals(null, identity[4]["value"])

                val perf = read(http, listOf(
                    "[system]Gateway/Performance/Max Memory", "[system]Gateway/Performance/Memory Usage",
                    "[system]Gateway/Performance/Memory Utilization", "[system]Gateway/Performance/Available Disk Space MB",
                    "[system]Gateway/Performance/Disk Utilization", "[system]Gateway/Performance/CPU Usage",
                ))
                assertTrue(perf.all { it["quality"] == "Good" }, perf.toString())
                val max = (perf[0]["value"] as Number).toLong()
                val used = (perf[1]["value"] as Number).toLong()
                assertTrue(max > 0 && used in 1..max, "used $used max $max")
                assertTrue((perf[2]["value"] as Number).toDouble() in 0.0..1.0)
                assertTrue((perf[3]["value"] as Number).toLong() >= 0)
                assertTrue((perf[4]["value"] as Number).toDouble() in 0.0..1.0)

                val server = read(http, listOf(
                    "[system]Gateway/OPC UA Server/State", "[system]Gateway/OPC UA Server/Running",
                    "[system]Gateway/OPC UA Server/TrustedCertificates", "[system]Gateway/Alarming/Active and Unacked",
                ))
                assertEquals("Running", server[0]["value"])
                assertEquals(true, server[1]["value"])
                assertEquals(0, (server[2]["value"] as Number).toInt())
                assertEquals(0, (server[3]["value"] as Number).toInt())

                // Slow group (every 5 s): connections that appear after start, and the session count.
                assertEquals(201, http.post("/api/v1/db/connections",
                    """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite::memory:","username":"","description":"plant"}""").status)
                assertEquals(201, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/plc", """
                    {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"plc","module":"org.oip.driver-modbus","version":0},
                     "spec":{"name":"plc","host":"127.0.0.1","port":${slave.port},"mode":"TCP","unitId":1}}
                """.trimIndent()).status)
                assertEquals(201, http.put("/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/remote", """
                    {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"remote","module":"org.oip.driver-opcua-client","version":0},
                     "spec":{"name":"remote","endpointUrl":"opc.tcp://127.0.0.1:1/none","securityPolicy":"NONE","securityMode":"NONE","trustAllServerCertificates":false}}
                """.trimIndent()).status)
                val dynamic = listOf(
                    "[system]Gateway/Database/plant/Available", "[system]Gateway/Database/plant/ActiveConnections",
                    "[system]Gateway/Device/plc/Status", "[system]Gateway/Device/plc/Description",
                    "[system]Gateway/Device/plc/Enabled", "[system]Gateway/OPC/remote/Connected",
                    "[system]Gateway/OPC/remote/Enabled", "[system]Gateway/Sessions/SessionCount",
                )
                await("connection tags") {
                    val v = read(http, dynamic)
                    v[0]["value"] == true && v[2]["value"] == "Connected" && v[3]["value"] == "" &&
                        v[4]["value"] == true && v[5]["value"] == false && v[6]["value"] == true &&
                        ((v[7]["value"] as? Number)?.toInt() ?: 0) >= 1
                }

                // Read-only provider.
                assertNotEquals("Good", g.tags.write("[system]Gateway/UptimeSeconds", 5).quality.code.label)

                // A removed device takes its folder with it.
                assertEquals(204, http.delete("/api/v1/config/org.oip.driver-modbus/gateway/Device/plc").status)
                await("device folder removed") { read(http, listOf("[system]Gateway/Device/plc/Status")).single()["quality"] == "Bad_NotFound" }
            }
        }
    }

    @Test fun disabledServerReportsDisabledAndLeavesNoTrustStore() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(tmp, port = 0).use { g ->
            g.start()
            assertEquals("Disabled", g.tags.read("[system]Gateway/OPC UA Server/State").value)
            assertEquals(false, g.tags.read("[system]Gateway/OPC UA Server/Running").value)
            assertTrue(!tmp.resolve("opcua").resolve("pki").toFile().exists())
        }
    }

    private fun read(http: Http, paths: List<String>): List<Map<*, *>> {
        val res = http.post("/api/v1/tags/read", Json.write(mapOf("paths" to paths)))
        assertEquals(200, res.status, res.body)
        @Suppress("UNCHECKED_CAST")
        return res.json["values"] as List<Map<*, *>>
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        var last: Throwable? = null
        while (System.nanoTime() < end) {
            try {
                if (cond()) return
            } catch (e: Throwable) {
                last = e
            }
            Thread.sleep(100)
        }
        throw AssertionError(message, last)
    }
}
