package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.modbus.ModbusDriverModule
import org.oip.opcua.OpcUaClientModule
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The load bench creates Modbus devices and OPC UA connections through the config API,
 * then reads tag values with POST /api/v1/tags/read. This test uses that request shape.
 */
class DriverConfigApiTest {
    @TempDir lateinit var tmp: Path

    @Test fun modbusDeviceAndTagsComeUpAndReadInRequestOrder() {
        TinyModbusSlave().use { slave ->
            slave.holding[0] = 42
            slave.holding[1] = 7
            Gateway(tmp, port = 0).use { g ->
                g.start()
                val http = Http(g.port())
                g.loginAdmin(http)
                val device = http.put(
                    "/api/v1/config/org.oip.driver-modbus/gateway/Device/bench-mb-0",
                    deviceBody(slave.port),
                )
                assertEquals(201, device.status, device.body)
                val module = g.host.instance(ModbusDriverModule.MODULE_ID) as ModbusDriverModule
                assertNotNull(module.device("bench-mb-0"))

                val tags = (0 until 8).map { index ->
                    val name = "p%07d".format(index)
                    val put = http.put(
                        "/api/v1/config/tags/gateway/Tag/$name",
                        tagBody(name, "bench-mb-0", "1.HR${index + 1}", if (index == 7) 1000 else 200),
                    )
                    assertEquals(201, put.status, put.body)
                    "[default]bench/$name"
                }
                val fast = tags[0]
                val second = tags[1]
                await("good values") {
                    val row = read(http, listOf(fast, second, "[default]bench/missing"))
                    row[0]["quality"] == "Good" && (row[0]["value"] as Number).toInt() == 42 &&
                        row[1]["quality"] == "Good" && (row[1]["value"] as Number).toInt() == 7 &&
                        row[2]["quality"] == "Bad_NotFound" &&
                        (row[0]["timestamp"] as String).contains("T")
                }
                val before = module.device("bench-mb-0")!!.readRequestCount
                Thread.sleep(700)
                val delta = module.device("bench-mb-0")!!.readRequestCount - before
                assertTrue(delta in 1..12, "coalesced polls in 700ms: $delta (total $before)")

                assertEquals(422, http.put(
                    "/api/v1/config/org.oip.driver-modbus/gateway/Device/bench-mb-bad",
                    deviceBody(slave.port).replace("\"spanGaps\":true", "\"spanGaps\":true,\"notAField\":1")
                        .replace("bench-mb-0", "bench-mb-bad"),
                ).status)

                assertEquals(204, http.delete("/api/v1/config/org.oip.driver-modbus/gateway/Device/bench-mb-0").status)
                assertNull(module.device("bench-mb-0"))
                await("bad after delete") {
                    read(http, listOf(fast)).single()["quality"] != "Good"
                }
            }
        }
    }

    @Test fun opcConnectionToADownServerStillCreatesAndDeletes() {
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            val body = """
                {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"bench-opc","module":"org.oip.driver-opcua-client","version":0},
                 "spec":{"name":"bench-opc","endpointUrl":"opc.tcp://127.0.0.1:1/oip/bench","securityPolicy":"NONE","securityMode":"NONE","trustAllServerCertificates":false}}
            """.trimIndent()
            val put = http.put("/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/bench-opc", body)
            assertEquals(201, put.status, put.body)
            val module = g.host.instance(OpcUaClientModule.MODULE_ID) as OpcUaClientModule
            assertNotNull(module.connection("bench-opc"))
            assertEquals(204, http.delete("/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/bench-opc").status)
            assertNull(module.connection("bench-opc"))
        }
    }

    @Test fun batchCreatesTagsAndReadRejectsABadBody() {
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            val items = (0 until 200).joinToString(",") { index ->
                val name = "b%07d".format(index)
                tagBody(name, "bench-opc", "ns=1;i=${1_000_000 + index}", 1000)
            }
            val batch = http.post("/api/v1/config/batch", """{"scope":"gateway","items":[$items]}""")
            assertEquals(200, batch.status, batch.body)
            assertEquals(200, (batch.json["created"] as Number).toInt())
            assertEquals(201, http.put(
                "/api/v1/config/tags/gateway/Tag/keep",
                tagBody("keep", "bench-opc", "ns=1;i=1", 1000),
            ).status)
            val conflict = http.post(
                "/api/v1/config/batch",
                """{"items":[${tagBody("keep", "bench-opc", "ns=1;i=1", 1000)},${tagBody("other", "bench-opc", "ns=1;i=2", 1000)}]}""",
            )
            assertEquals(409, conflict.status, conflict.body)
            assertEquals(404, http.get("/api/v1/config/tags/gateway/Tag/other").status)
            assertEquals(422, http.post("/api/v1/tags/read", """{"paths":"nope"}""").status)
            assertEquals(422, http.post("/api/v1/config/batch", """{"items":[]}""").status)
            val empty = http.post("/api/v1/tags/read", """{"paths":[]}""")
            assertEquals(200, empty.status, empty.body)
            assertEquals(emptyList<Any>(), empty.json["values"])
        }
    }

    private fun read(http: Http, paths: List<String>): List<Map<*, *>> {
        val body = paths.joinToString(prefix = "{\"paths\":[", postfix = "]}") { "\"$it\"" }
        val res = http.post("/api/v1/tags/read", body)
        assertEquals(200, res.status, res.body)
        @Suppress("UNCHECKED_CAST")
        return res.json["values"] as List<Map<*, *>>
    }

    private fun deviceBody(port: Int) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"bench-mb-0","module":"org.oip.driver-modbus","labels":{"bench":"load"},"version":0},
         "spec":{"name":"bench-mb-0","host":"127.0.0.1","port":$port,"mode":"TCP","unitId":1,"zeroBasedAddressing":false,"concurrentRequests":2,"defaultScanPeriodMs":200,"maxHoldingRegistersPerRead":125,"spanGaps":true}}
    """.trimIndent()

    private fun tagBody(name: String, server: String, item: String, period: Int) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"bench/$name","type":"opc","dataType":"Int32","opcServer":"$server","opcItemPath":"$item","scanPeriodMillis":$period}}
    """.trimIndent()

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        var last = "not run"
        while (System.nanoTime() < end) {
            try {
                if (cond()) return
            } catch (e: AssertionError) {
                last = e.message ?: e.toString()
            }
            Thread.sleep(50)
        }
        throw AssertionError("$message ($last)")
    }
}

/** Function-3 Modbus TCP slave. Holding register 0 is one-based HR1. */
internal class TinyModbusSlave(registers: Int = 32) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int = server.localPort
    val holding = IntArray(registers)
    val reads = AtomicInteger()
    private val clients = CopyOnWriteArrayList<Socket>()
    @Volatile var accepting = true

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ serve(socket) }, "tiny-modbus").apply { isDaemon = true }.start()
            }
        }, "tiny-modbus-accept").apply { isDaemon = true }.start()
    }

    /** Drop every client and refuse the next ones until [restore]. The device sees the socket close. */
    fun cut() {
        accepting = false
        clients.forEach { runCatching { it.close() } }
    }

    fun restore() {
        accepting = true
    }

    private fun serve(socket: Socket) {
        if (!accepting) {
            runCatching { socket.close() }
            return
        }
        clients += socket
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val buf = ByteArray(260)
            while (accepting) {
                if (!readFully(input, buf, 6)) return
                val length = ((buf[4].toInt() and 0xff) shl 8) or (buf[5].toInt() and 0xff)
                if (length < 2 || length > 250 || !readFully(input, buf, length, 6)) return
                val function = buf[7].toInt() and 0xff
                if (function != 3) continue
                reads.incrementAndGet()
                val address = ((buf[8].toInt() and 0xff) shl 8) or (buf[9].toInt() and 0xff)
                val count = ((buf[10].toInt() and 0xff) shl 8) or (buf[11].toInt() and 0xff)
                val pdu = ByteArray(2 + count * 2)
                pdu[0] = 3
                pdu[1] = (count * 2).toByte()
                for (i in 0 until count) {
                    val value = if (address + i < holding.size) holding[address + i] else 0
                    pdu[2 + i * 2] = (value shr 8).toByte()
                    pdu[3 + i * 2] = value.toByte()
                }
                val frame = ByteArray(6 + 1 + pdu.size)
                frame[0] = buf[0]
                frame[1] = buf[1]
                val follow = 1 + pdu.size
                frame[4] = (follow shr 8).toByte()
                frame[5] = follow.toByte()
                frame[6] = buf[6]
                pdu.copyInto(frame, 7)
                output.write(frame)
                output.flush()
            }
        }
        clients.remove(socket)
    }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int, offset: Int = 0): Boolean {
        var got = 0
        while (got < n) {
            val r = input.read(buf, offset + got, n - got)
            if (r < 0) return false
            got += r
        }
        return true
    }

    override fun close() {
        server.close()
    }
}
