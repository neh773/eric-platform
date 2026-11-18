package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path

/**
 * Modbus address ranges expand into OPC tag resources through the config store, and those tags read
 * the device. -
 */
class ModbusMappingApiTest {
    @TempDir lateinit var tmp: Path

    @Test fun rangesBecomeTagsThatReadTheDevice() {
        TinyModbusSlave().use { slave ->
            for (i in 0 until 4) slave.holding[9 + i] = 200 + i // HR10..HR13, one-based
            // 1.5f = 0x3FC00000 at HRF20 (registers 19-20), 2.5f = 0x40200000 at HRF22.
            slave.holding[19] = 0x3FC0
            slave.holding[21] = 0x4020
            Gateway(tmp, port = 0).use { g ->
                g.start()
                val admin = Http(g.port()).also { g.loginAdmin(it) }
                g.createUser("olly", setOf(DefaultRoles.OPERATOR))
                g.createUser("vic", setOf(DefaultRoles.VIEWER))
                val operator = Http(g.port()).also { assertEquals(200, it.login("olly", TEST_PASSWORD).status) }
                val viewer = Http(g.port()).also { assertEquals(200, it.login("vic", TEST_PASSWORD).status) }
                assertEquals(201, admin.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/plc", device(slave.port)).status)

                val body = """
                    {"device":"plc","folder":"Plant/PLC","scanPeriodMillis":200,"ranges":[
                      {"prefix":"Temp","start":"1","end":"4","modbusType":"HR","modbusAddress":10},
                      {"prefix":"Flow_","start":"0","end":"1","modbusType":"HRF","modbusAddress":20,"step":true}
                    ]}
                """.trimIndent()
                val preview = operator.post("/api/v1/modbus/address-ranges/preview", body)
                assertEquals(200, preview.status, preview.body)
                assertEquals(6, (preview.json["count"] as Number).toInt())
                val items = preview.json["items"] as List<*>
                val first = items.first() as Map<*, *>
                assertEquals("Temp1", first["name"])
                assertEquals("plc-temp1", first["resourceName"])
                assertEquals("Plant/PLC/Temp1", first["path"])
                assertEquals("1.HR10", first["address"])
                assertEquals("Int32", first["dataType"])
                val flow = items.single { (it as Map<*, *>)["name"] == "Flow_1" } as Map<*, *>
                assertEquals("1.HRF22", flow["address"])
                assertEquals("Float", flow["dataType"])
                assertEquals("plc-flow-1", flow["resourceName"])
                assertEquals(true, first["writable"])
                assertEquals(404, admin.get("/api/v1/config/tags/gateway/Tag/plc-temp1").status, "preview stores nothing")
                assertEquals(403, viewer.post("/api/v1/modbus/address-ranges/preview", body).status)

                assertEquals(403, operator.post("/api/v1/modbus/address-ranges/tags", body).status)
                val created = admin.post("/api/v1/modbus/address-ranges/tags", body)
                assertEquals(200, created.status, created.body)
                assertEquals(6, (created.json["created"] as Number).toInt())
                val stored = admin.get("/api/v1/config/tags/gateway/Tag/plc-temp4")
                assertEquals(200, stored.status, stored.body)
                val spec = stored.json["spec"] as Map<*, *>
                assertEquals("opc", spec["type"])
                assertEquals("plc", spec["opcServer"])
                assertEquals("1.HR13", spec["opcItemPath"])
                assertEquals("Plant/PLC/Temp4", spec["path"])
                assertEquals("plc", ((stored.json["metadata"] as Map<*, *>)["labels"] as Map<*, *>)[ModbusMappingApi.MAPPING_LABEL])

                await {
                    val values = read(admin, listOf("[default]Plant/PLC/Temp1", "[default]Plant/PLC/Temp4", "[default]Plant/PLC/Flow_0", "[default]Plant/PLC/Flow_1"))
                    values.all { it["quality"] == "Good" } &&
                        (values[0]["value"] as Number).toInt() == 200 && (values[1]["value"] as Number).toInt() == 203 &&
                        (values[2]["value"] as Number).toDouble() == 1.5 && (values[3]["value"] as Number).toDouble() == 2.5
                }

                val again = admin.post("/api/v1/modbus/address-ranges/tags", body)
                assertEquals(409, again.status, again.body)
                val overwrite = admin.post("/api/v1/modbus/address-ranges/tags", body.replace("\"device\":\"plc\"", "\"device\":\"plc\",\"overwrite\":true"))
                assertEquals(200, overwrite.status, overwrite.body)
                assertEquals(6, (overwrite.json["updated"] as Number).toInt())
            }
        }
    }

    @Test fun badRequestsAreRejected() {
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val admin = Http(g.port()).also { g.loginAdmin(it) }
            assertEquals(201, admin.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/plc", device(1502)).status)
            fun range(r: String, device: String = "plc") = """{"device":"$device","ranges":[$r]}"""
            val ok = """{"prefix":"A","start":"1","end":"2","modbusType":"HR","modbusAddress":1}"""
            assertEquals(404, admin.post("/api/v1/modbus/address-ranges/preview", range(ok, "missing")).status)
            assertEquals(422, admin.post("/api/v1/modbus/address-ranges/preview", range(ok, "Bad Name")).status)
            assertEquals(422, admin.post("/api/v1/modbus/address-ranges/preview", """{"device":"plc","ranges":[]}""").status)
            assertEquals(422, admin.post("/api/v1/modbus/address-ranges/preview", range(ok.replace("\"HR\"", "\"HRS\""))).status)
            assertEquals(422, admin.post("/api/v1/modbus/address-ranges/preview", range(ok.replace("\"HR\"", "\"HRF\""))).status)
            assertEquals(422, admin.post("/api/v1/modbus/address-ranges/preview", range(ok.replace("\"A\"", "\"HR\""))).status)
            assertEquals(422, admin.post("/api/v1/modbus/address-ranges/preview", range("$ok,$ok")).status, "duplicate names")
            assertEquals(422, admin.post("/api/v1/modbus/address-ranges/preview", range(ok.replace("\"start\":\"1\",", ""))).status)
            assertEquals(400, admin.post("/api/v1/modbus/address-ranges/preview", "not json").status)
            assertEquals(200, admin.post("/api/v1/modbus/address-ranges/preview", range(ok)).status)
            val input = admin.post("/api/v1/modbus/address-ranges/preview", range(ok.replace("\"HR\"", "\"IR\"")))
            assertEquals(200, input.status, input.body)
            assertEquals(false, ((input.json["items"] as List<*>).first() as Map<*, *>)["writable"])
            val tags = admin.post("/api/v1/modbus/address-ranges/tags", range(ok.replace("\"HR\"", "\"IR\"")))
            assertEquals(200, tags.status, tags.body)
            assertEquals(false, (admin.get("/api/v1/config/tags/gateway/Tag/plc-a1").json["spec"] as Map<*, *>)["writable"])
        }
    }

    @Test fun overwriteOnlyReplacesTagsLabelledForTheDevice() {
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val admin = Http(g.port()).also { g.loginAdmin(it) }
            assertEquals(201, admin.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/plc", device(1502)).status)
            val body = { dev: String, prefix: String ->
                """{"device":"$dev","overwrite":true,"ranges":[{"prefix":"$prefix","start":"1","end":"2","modbusType":"HR","modbusAddress":1}]}"""
            }
            // A hand-made Tag that happens to have a generated name (plc-a1) is never replaced.
            val manual = """
                {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"plc-a1","module":"tags","version":0},
                 "spec":{"path":"Manual/A1","type":"memory","dataType":"Int32","value":7}}
            """.trimIndent()
            assertEquals(201, admin.put("/api/v1/config/tags/gateway/Tag/plc-a1", manual).status)
            val blocked = admin.post("/api/v1/modbus/address-ranges/tags", body("plc", "A"))
            assertEquals(409, blocked.status, blocked.body)
            assertEquals("Manual/A1", (admin.get("/api/v1/config/tags/gateway/Tag/plc-a1").json["spec"] as Map<*, *>)["path"])
            assertEquals(404, admin.get("/api/v1/config/tags/gateway/Tag/plc-a2").status, "a 409 stores nothing")

            // Generated names can collide across devices ("plc" + "Line_X1" and "plc-line" + "X1" both give
            // plc-line-x1). The second device's overwrite must not take the first device's tag.
            assertEquals(201, admin.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/plc-line", device(1503).replace("\"plc\"", "\"plc-line\"")).status)
            val mine = admin.post("/api/v1/modbus/address-ranges/tags", body("plc", "Line_X"))
            assertEquals(200, mine.status, mine.body)
            val theirs = admin.post("/api/v1/modbus/address-ranges/tags", body("plc-line", "X"))
            assertEquals(409, theirs.status, theirs.body)
            val kept = admin.get("/api/v1/config/tags/gateway/Tag/plc-line-x1").json
            assertEquals("plc", ((kept["metadata"] as Map<*, *>)["labels"] as Map<*, *>)[ModbusMappingApi.MAPPING_LABEL])

            // Tags this mapping generated for the same device are replaced.
            val first = admin.post("/api/v1/modbus/address-ranges/tags", body("plc", "B"))
            assertEquals(200, first.status, first.body)
            assertEquals(2, (first.json["created"] as Number).toInt())
            val again = admin.post("/api/v1/modbus/address-ranges/tags", body("plc", "B").replace("\"modbusAddress\":1", "\"modbusAddress\":5"))
            assertEquals(200, again.status, again.body)
            assertEquals(2, (again.json["updated"] as Number).toInt())
            assertEquals("1.HR5", (admin.get("/api/v1/config/tags/gateway/Tag/plc-b1").json["spec"] as Map<*, *>)["opcItemPath"])
        }
    }

    private fun read(http: Http, paths: List<String>): List<Map<*, *>> {
        val res = http.post("/api/v1/tags/read", Json.write(mapOf("paths" to paths)))
        assertEquals(200, res.status, res.body)
        @Suppress("UNCHECKED_CAST")
        return res.json["values"] as List<Map<*, *>>
    }

    private fun device(port: Int) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"plc","module":"org.oip.driver-modbus","version":0},
         "spec":{"name":"plc","host":"127.0.0.1","port":$port,"mode":"TCP","unitId":1,"defaultScanPeriodMs":200}}
    """.trimIndent()

    private fun await(cond: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        var last: Throwable? = null
        while (System.nanoTime() < end) {
            try {
                if (cond()) return
            } catch (e: Throwable) {
                last = e
            }
            Thread.sleep(50)
        }
        throw AssertionError("tag values did not arrive", last)
    }
}
