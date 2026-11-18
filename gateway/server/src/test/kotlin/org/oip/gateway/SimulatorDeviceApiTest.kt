package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.simulator.SimulatorDriverModule
import java.nio.file.Path

/**
 * REST create and delete of a programmable simulator, and an OPC tag bound to one of its
 * addresses. The program does not repeat and the base rate is long, so a write is not overwritten
 * during the test. Sequence timing is covered in the driver module with a manual clock.
 *
 */
class SimulatorDeviceApiTest {
    @TempDir lateinit var tmp: Path

    @Test fun createReadWriteAndDeleteASimulatorThroughTheDeviceApi() {
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            val created = http.put(
                "/api/v1/config/org.oip.driver-simulator/gateway/Device/gensim",
                deviceBody(),
            )
            assertEquals(201, created.status, created.body)
            val module = g.host.instance(SimulatorDriverModule.MODULE_ID) as SimulatorDriverModule
            val device = module.device("gensim")
            assertEquals(42, device!!.read("Tank/Level").value)

            val tag = http.put(
                "/api/v1/config/tags/gateway/Tag/level",
                """
                {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"level","module":"tags","version":0},
                 "spec":{"provider":"default","path":"sim/level","type":"opc","dataType":"Int32","opcServer":"gensim","opcItemPath":"Tank/Level","scanPeriodMillis":200}}
                """.trimIndent(),
            )
            assertEquals(201, tag.status, tag.body)
            await("Good value from the simulator") {
                val row = read(http, "[default]sim/level")
                row["quality"] == "Good" && (row["value"] as Number).toInt() == 42
            }

            val written = http.post(
                "/api/v1/tags/write",
                """{"writes":[{"path":"[default]sim/level","value":17}]}""",
            )
            assertEquals(200, written.status, written.body)
            @Suppress("UNCHECKED_CAST")
            val result = (written.json["results"] as List<Map<String, Any?>>).single()
            assertEquals(true, result["ok"])
            assertEquals("Good", result["quality"])
            assertEquals(17, device.read("Tank/Level").value)
            await("written value stays on the tag") {
                val row = read(http, "[default]sim/level")
                row["quality"] == "Good" && (row["value"] as Number).toInt() == 17
            }

            val rejected = http.put(
                "/api/v1/config/org.oip.driver-simulator/gateway/Device/gensim-bad",
                deviceBody().replace("\"repeatProgram\":false", "\"repeatProgram\":false,\"nope\":1")
                    .replace("gensim", "gensim-bad"),
            )
            assertEquals(422, rejected.status, rejected.body)

            assertEquals(204, http.delete("/api/v1/config/org.oip.driver-simulator/gateway/Device/gensim").status)
            assertNull(module.device("gensim"))
            await("tag leaves Good after the device is deleted") {
                read(http, "[default]sim/level")["quality"] != "Good"
            }
            assertNotEquals("Good", read(http, "[default]sim/level")["quality"])
        }
    }

    private fun read(http: Http, path: String): Map<*, *> {
        val res = http.post("/api/v1/tags/read", """{"paths":["$path"]}""")
        assertEquals(200, res.status, res.body)
        @Suppress("UNCHECKED_CAST")
        return (res.json["values"] as List<Map<*, *>>).single()
    }

    private fun deviceBody() = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"gensim","module":"org.oip.driver-simulator","version":0},
         "spec":{"name":"gensim","enabled":true,"repeatProgram":false,"baseRateMs":60000,"randomSeed":1,
           "instructions":[{"timeInterval":0,"browsePath":"Tank/Level","valueSource":"42","dataType":"Int32"}]}}
    """.trimIndent()

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        var last = "not run"
        while (System.nanoTime() < end) {
            try {
                if (cond()) return
            } catch (e: RuntimeException) {
                last = e.message ?: e.toString()
            }
            Thread.sleep(30)
        }
        throw AssertionError("$message ($last)")
    }
}
