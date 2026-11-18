package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * C-16 admin tag browser: providers, device list and device browse, and the config resource name on browse rows.
 */
@Suppress("UNCHECKED_CAST")
class TagDeviceApiTest {
    @TempDir lateinit var tmp: Path

    private fun gateway(): Gateway {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        return Gateway(tmp, port = 0)
    }

    private fun deviceBody(port: Int) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"sim-1","module":"org.oip.driver-modbus","version":0},
         "spec":{"name":"sim-1","host":"127.0.0.1","port":$port,"mode":"TCP","unitId":1,"zeroBasedAddressing":false,"defaultScanPeriodMs":200}}
    """.trimIndent()

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun browsesAModbusDeviceAndListsProvidersAndDevices() {
        TinyModbusSlave().use { slave ->
            slave.holding[0] = 11
            slave.holding[2] = 33
            gateway().use { g ->
                g.start()
                val http = Http(g.port())
                g.loginAdmin(http)
                assertEquals(201, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/sim-1", deviceBody(slave.port)).status)

                val providers = http.get(TagDeviceApi.PROVIDERS)
                assertEquals(200, providers.status, providers.body)
                assertEquals("default", (providers.json["providers"] as List<Map<String, Any?>>).first()["name"])

                val devices = http.get(TagDeviceApi.DEVICES).json["devices"] as List<Map<String, Any?>>
                assertEquals(listOf("sim-1"), devices.map { it["name"] })
                assertEquals("modbus", devices.single()["driver"])

                val root = http.post(TagDeviceApi.BROWSE, """{"device":"sim-1"}""")
                assertEquals(200, root.status, root.body)
                val areas = root.json["nodes"] as List<Map<String, Any?>>
                assertEquals(listOf("area:HR", "area:IR", "area:C", "area:DI"), areas.map { it["nodeId"] })
                assertTrue(areas.all { it["hasChildren"] == true })

                var registers: List<Map<String, Any?>> = emptyList()
                val end = System.nanoTime() + 10_000_000_000L
                while (System.nanoTime() < end) {
                    val page = http.post(TagDeviceApi.BROWSE, """{"device":"sim-1","nodeId":"area:HR"}""")
                    assertEquals(200, page.status, page.body)
                    assertEquals(true, page.json["more"])
                    registers = page.json["nodes"] as List<Map<String, Any?>>
                    if (registers.first()["quality"] == "Good") break
                    Thread.sleep(100)
                }
                assertEquals((1..TagDeviceApi.MODBUS_PAGE).map { "HR$it" }, registers.map { it["nodeId"] })
                assertEquals(11, (registers[0]["value"] as Number).toInt())
                assertEquals(33, (registers[2]["value"] as Number).toInt())

                val second = http.post(TagDeviceApi.BROWSE, """{"device":"sim-1","nodeId":"area:HR","offset":10}""")
                assertEquals("HR11", (second.json["nodes"] as List<Map<String, Any?>>).first()["nodeId"])

                assertEquals(404, http.post(TagDeviceApi.BROWSE, """{"device":"nope"}""").status)
                assertEquals(404, http.post(TagDeviceApi.BROWSE, """{"device":"sim-1","nodeId":"HR1"}""").status)
                assertEquals(422, http.post(TagDeviceApi.BROWSE, """{"nodeId":""}""").status)
                assertEquals(422, http.post(TagDeviceApi.BROWSE, """{"device":"sim-1","offset":-1}""").status)
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun browseRowsNameTheirTagResourceAndDeviceRoutesNeedGatewayPermissions() {
        gateway().use { g ->
            g.start()
            g.store.put(Scope.GATEWAY, Resource("Tag", Metadata("line-speed", "tags"), mapOf(
                "path" to "Line/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0,
            )))
            g.createUser("ada", setOf(DefaultRoles.OPERATOR))
            g.createUser("vic", setOf(DefaultRoles.VIEWER))
            val admin = Http(g.port())
            g.loginAdmin(admin)
            val rows = admin.post("/api/v1/tags/browse", """{"path":"[default]Line"}""").json["nodes"] as List<Map<String, Any?>>
            assertEquals("line-speed", rows.single()["resource"])
            val folders = admin.post("/api/v1/tags/browse", """{"path":"[default]"}""").json["nodes"] as List<Map<String, Any?>>
            assertNull(folders.single { it["name"] == "Line" }["resource"])

            val operator = Http(g.port()).also { assertEquals(200, it.login("ada", TEST_PASSWORD).status) }
            assertEquals(200, operator.get(TagDeviceApi.DEVICES).status)
            assertEquals(403, operator.post(TagDeviceApi.BROWSE, """{"device":"x"}""").status)
            val viewer = Http(g.port()).also { assertEquals(200, it.login("vic", TEST_PASSWORD).status) }
            assertEquals(200, viewer.get(TagDeviceApi.PROVIDERS).status)
            assertEquals(403, viewer.get(TagDeviceApi.DEVICES).status)
            assertTrue(OpenApi.document.toString().contains(TagDeviceApi.BROWSE))
        }
    }
}
