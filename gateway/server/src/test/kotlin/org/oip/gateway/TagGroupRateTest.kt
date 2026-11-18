package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.tags.GROUP_KIND
import org.oip.tags.Level
import org.oip.tags.QualityCode
import org.oip.tags.TAGS_MODULE
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review-2 F3: a tag group's rate is the rate the gateway reads the device (TAG-006, TAG-008, TAG-025).
 * A Modbus slave counts the reads of each register:
 * - a tag in a 250 ms direct group is read about 4 times a second, and about once a second after the group is
 *   edited to 1000 ms;
 * - a tag in a leased group with no slow rate is not read while nothing subscribes, is read at the fast rate while
 *   a subscriber holds it, and stops being read when the last subscriber closes. It does not go to comms loss while
 *   it is not read.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TagGroupRateTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun directAndLeasedGroupsSetTheDevicePollRate() {
        CountingModbus().use { slave ->
            slave.start()
            Files.writeString(tmp.resolve("gateway.yaml"), "profile: standard\nopcua:\n  enabled: false\n")
            Gateway(tmp, port = 0).also { it.start() }.use { g ->
                val http = Http(g.port())
                g.loginAdmin(http)
                group(g, "fast", mapOf("mode" to "direct", "rateMillis" to 250))
                group(g, "lease", mapOf("mode" to "leased", "rateMillis" to 250))
                put(http, "/api/v1/config/org.oip.driver-modbus/gateway/Device/line", """
                    {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"line","module":"org.oip.driver-modbus","version":0},
                     "spec":{"name":"line","host":"127.0.0.1","port":${slave.port},"mode":"TCP","unitId":1,"zeroBasedAddressing":false,
                       "timeoutMs":3000,"maxRetryCount":0,"defaultScanPeriodMs":1000}}
                """.trimIndent())
                // scanPeriodMillis is deliberately far from the group rate: the group decides.
                put(http, "/api/v1/config/tags/gateway/Tag/flow", tag("flow", "1.HR1", "fast"))
                put(http, "/api/v1/config/tags/gateway/Tag/leased", tag("leased", "1.HR5", "lease"))
                awaitTrue("flow is Good") { g.tags.read("plant/flow").quality.level == Level.GOOD }

                // Direct 250 ms: about 4 reads per second (2 s window: 8 expected).
                val fast = slave.readsDuring(0, 2_000)
                assertTrue(fast in 6..11, "250 ms direct group read $fast times in 2 s")

                // Editing the group changes the device rate without touching the tag.
                group(g, "fast", mapOf("mode" to "direct", "rateMillis" to 1000))
                Thread.sleep(1_200)
                val slow = slave.readsDuring(0, 3_000)
                assertTrue(slow in 2..4, "1000 ms direct group read $slow times in 3 s")

                // Leased, nobody subscribed: not read at all, and not comms loss.
                assertEquals(0, slave.readsDuring(4, 1_500), "leased item with no subscriber is not read")
                assertFalse(g.tags.read("plant/leased").quality.code == QualityCode.BAD_NOT_CONNECTED)

                // A subscriber leases it: read at the fast rate.
                val lease = g.tags.subscribe("plant/leased") { }
                awaitTrue("leased tag is Good") { g.tags.read("plant/leased").quality.level == Level.GOOD }
                val leased = slave.readsDuring(4, 2_000)
                assertTrue(leased in 6..11, "leased item with a subscriber read $leased times in 2 s")

                // The last subscriber closes: polling stops, and the tag keeps its value without comms loss.
                lease.close()
                Thread.sleep(500)
                assertEquals(0, slave.readsDuring(4, 1_500), "leased item read after its last subscriber closed")
                assertEquals(Level.GOOD, g.tags.read("plant/leased").quality.level)
            }
        }
    }

    private fun group(g: Gateway, name: String, spec: Map<String, Any?>) {
        val version = g.store.get(ResourceId(TAGS_MODULE, Scope.GATEWAY, GROUP_KIND, name))?.metadata?.version ?: 0L
        g.store.put(Scope.GATEWAY, Resource(GROUP_KIND, Metadata(name, TAGS_MODULE, version = version), spec))
    }

    private fun put(http: Http, path: String, body: String) {
        val res = http.put(path, body)
        assertTrue(res.status == 201 || res.status == 200, "${res.status} ${res.body}")
    }

    private fun tag(name: String, item: String, group: String) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"plant/$name","type":"opc","dataType":"Int32","opcServer":"line",
           "opcItemPath":"$item","scanPeriodMillis":5000,"tagGroup":"$group"}}
    """.trimIndent()

    private fun awaitTrue(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!check()) {
            assertFalse(System.nanoTime() > deadline, "timed out waiting for $what")
            Thread.sleep(20)
        }
    }
}

/** Modbus TCP slave that answers FC3 reads and counts the reads that cover each register index. */
private class CountingModbus : AutoCloseable {
    private val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 0)) }
    val port: Int = socket.localPort
    private val reads = ConcurrentHashMap<Int, AtomicInteger>()

    /** Reads covering register [index] during the next [millis]. */
    fun readsDuring(index: Int, millis: Long): Int {
        val before = reads[index]?.get() ?: 0
        Thread.sleep(millis)
        return (reads[index]?.get() ?: 0) - before
    }

    fun start() {
        Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                Thread({ serve(client) }, "modbus-count-sim").apply { isDaemon = true }.start()
            }
        }, "modbus-count-sim-accept").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val buf = ByteArray(260)
            while (true) {
                if (!readFully(input, buf, 6)) return
                val length = ((buf[4].toInt() and 0xff) shl 8) or (buf[5].toInt() and 0xff)
                if (length < 2 || length > 250 || !readFully(input, buf, length, 6)) return
                val function = buf[7].toInt() and 0xff
                val pdu = if (function == 3) {
                    val address = ((buf[8].toInt() and 0xff) shl 8) or (buf[9].toInt() and 0xff)
                    val count = ((buf[10].toInt() and 0xff) shl 8) or (buf[11].toInt() and 0xff)
                    for (i in address until address + count) reads.computeIfAbsent(i) { AtomicInteger() }.incrementAndGet()
                    ByteArray(2 + count * 2).also { it[0] = 3; it[1] = (count * 2).toByte(); for (i in 0 until count) it[3 + i * 2] = (address + i + 1).toByte() }
                } else {
                    byteArrayOf((function or 0x80).toByte(), 1)
                }
                val frame = ByteArray(7 + pdu.size)
                frame[0] = buf[0]
                frame[1] = buf[1]
                frame[4] = ((1 + pdu.size) shr 8).toByte()
                frame[5] = (1 + pdu.size).toByte()
                frame[6] = buf[6]
                pdu.copyInto(frame, 7)
                output.write(frame)
                output.flush()
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int, offset: Int = 0): Boolean {
        var got = 0
        while (got < n) {
            val r = try { input.read(buf, offset + got, n - got) } catch (_: Exception) { -1 }
            if (r < 0) return false
            got += r
        }
        return true
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
