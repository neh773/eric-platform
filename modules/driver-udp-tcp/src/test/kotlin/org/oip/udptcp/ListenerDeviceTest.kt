package org.oip.udptcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The UDP/TCP driver against real loopback sockets: a test client plays the scanner or scale.
 * doc source: FEATURES DEV-037
 */
@Timeout(60)
class ListenerDeviceTest {
    private val loopback = "127.0.0.1"

    @Test fun tcpListenSplitsFieldsAndCountsMessages() {
        val port = freeTcpPort()
        ListenerDevice(ListenerDeviceConfig(name = "scale", address = loopback, ports = listOf(port), fieldCount = 3)).use { device ->
            device.start()
            await("listening") { device.connectionStatus().connected }
            assertEquals(QualityCode.Bad_WaitingForInitialData, device.read(listOf("$port/Message")).single().quality.code)
            Socket(loopback, port).use { client ->
                val out = client.getOutputStream()
                out.write("A1,12.5,kg\r".toByteArray()); out.flush()
                out.write("\nB2,13.0,kg\r\n".toByteArray()); out.flush()
                await("two messages") { count(device, port) == 2L }
                val row = device.read(listOf("$port/Message", "$port/Field1", "$port/Field2", "$port/Field3", "$port/LastReceiveTime", "$port/MessageBytes"))
                assertEquals("B2,13.0,kg", row[0].value)
                assertEquals("B2", row[1].value)
                assertEquals("13.0", row[2].value)
                assertEquals("kg", row[3].value)
                assertTrue(row[4].value is Instant)
                assertEquals("B2,13.0,kg", String(row[5].value as ByteArray))
                assertTrue(row.all { it.quality.code == QualityCode.Good })

                out.write("bad,line\r\n".toByteArray()); out.flush()
                await("three") { count(device, port) == 3L }
                assertEquals("bad,line", device.read(listOf("$port/Message")).single().value)
                val field = device.read(listOf("$port/Field1")).single()
                assertEquals(QualityCode.Bad_ConfigurationError, field.quality.code, field.quality.diagnostic)
            }
            assertEquals(listOf(port.toString()), device.browse("").map { it.nodeId })
            val items = device.browse(port.toString()).map { it.displayName }
            assertEquals(listOf("Message", "MessageBytes", "Field1", "Field2", "Field3", "MessageCount", "LastReceiveTime", "DroppedCount", "RefusedCount"), items)
            assertNotNull(device.describe("$port/Field3"))
            assertEquals(null, device.describe("$port/Field4"))
            assertEquals(QualityCode.Bad_NotWritable, device.write("$port/Message", "x").quality.code)
        }
    }

    @Test fun tcpConnectModeDialsOutAndRedialsAfterTheDeviceDrops() {
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(loopback, 0))
            val config = ListenerDeviceConfig(
                name = "scanner", mode = Mode.CONNECT, address = loopback, ports = listOf(server.localPort),
                messageDelimiter = "\\n", reconnectMs = 50,
            )
            ListenerDevice(config).use { device ->
                device.start()
                server.soTimeout = 5_000
                server.accept().use { link ->
                    link.getOutputStream().apply { write("0123456789\n".toByteArray()); flush() }
                    await("first scan") { count(device, server.localPort) == 1L }
                    await("connected") { device.connectionStatus().connected }
                }
                await("lost") { device.read(listOf("${server.localPort}/Message")).single().quality.code == QualityCode.Bad_NotConnected }
                assertEquals("0123456789", device.read(listOf("${server.localPort}/Message")).single().value, "the last message is kept")
                server.accept().use { link ->
                    link.getOutputStream().apply { write("9876543210\n".toByteArray()); flush() }
                    await("second scan") { count(device, server.localPort) == 2L }
                    val message = device.read(listOf("${server.localPort}/Message")).single()
                    assertEquals("9876543210", message.value)
                    assertEquals(QualityCode.Good, message.quality.code)
                }
            }
        }
    }

    @Test fun udpPacketBasedAndTcpFixedSize() {
        val udpPort = freeUdpPort()
        ListenerDevice(
            ListenerDeviceConfig(name = "udp", protocol = Protocol.UDP, address = loopback, ports = listOf(udpPort), delimiterType = DelimiterType.PACKET),
        ).use { device ->
            device.start()
            await("bound") { device.connectionStatus().connected }
            DatagramSocket().use { socket ->
                for (text in listOf("w=1.5", "w=2.5")) {
                    val bytes = text.toByteArray()
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(loopback), udpPort))
                }
            }
            await("two datagrams") { count(device, udpPort) == 2L }
            assertEquals("w=2.5", device.read(listOf("$udpPort/Message")).single().value)
        }
        val tcpPort = freeTcpPort()
        ListenerDevice(
            ListenerDeviceConfig(name = "fixed", address = loopback, ports = listOf(tcpPort), delimiterType = DelimiterType.FIXED, fixedLength = 4),
        ).use { device ->
            device.start()
            await("listening") { device.connectionStatus().connected }
            Socket(loopback, tcpPort).use { client ->
                client.getOutputStream().apply { write("ABCDEF".toByteArray()); flush(); write("GH".toByteArray()); flush() }
                await("two records") { count(device, tcpPort) == 2L }
                assertEquals("EFGH", device.read(listOf("$tcpPort/Message")).single().value)
            }
        }
    }

    @Test fun oversizeMessagesAreDroppedAndCountedWithoutGrowingTheBuffer() {
        val tcpPort = freeTcpPort()
        ListenerDevice(ListenerDeviceConfig(name = "big", address = loopback, ports = listOf(tcpPort), maxMessageBytes = 64)).use { device ->
            device.start()
            await("listening") { device.connectionStatus().connected }
            Socket(loopback, tcpPort).use { client ->
                val out = client.getOutputStream()
                out.write(ByteArray(200_000) { 'x'.code.toByte() })
                out.write("\r\nok\r\n".toByteArray())
                out.flush()
                await("ok after the flood") { device.read(listOf("$tcpPort/Message")).single().value == "ok" }
            }
            assertEquals(1L, device.read(listOf("$tcpPort/DroppedCount")).single().value)
            assertEquals(1L, count(device, tcpPort))
            assertEquals(66, device.bufferCapacity)
            assertTrue(device.bufferHighWater <= device.bufferCapacity, "held ${device.bufferHighWater}")
        }
        val udpPort = freeUdpPort()
        ListenerDevice(
            ListenerDeviceConfig(name = "bigudp", protocol = Protocol.UDP, address = loopback, ports = listOf(udpPort), delimiterType = DelimiterType.PACKET, maxMessageBytes = 32),
        ).use { device ->
            device.start()
            await("bound") { device.connectionStatus().connected }
            DatagramSocket().use { socket ->
                val big = ByteArray(1_000) { 'y'.code.toByte() }
                socket.send(DatagramPacket(big, big.size, InetAddress.getByName(loopback), udpPort))
                val small = "fits".toByteArray()
                socket.send(DatagramPacket(small, small.size, InetAddress.getByName(loopback), udpPort))
            }
            await("small datagram") { count(device, udpPort) == 1L }
            assertEquals(1L, device.read(listOf("$udpPort/DroppedCount")).single().value)
            assertEquals("fits", device.read(listOf("$udpPort/Message")).single().value)
        }
    }

    @Test fun clientsOverTheLimitAreRefusedAndCounted() {
        val port = freeTcpPort()
        ListenerDevice(ListenerDeviceConfig(name = "limit", address = loopback, ports = listOf(port), maxClients = 2, messageDelimiter = "\\n")).use { device ->
            device.start()
            await("listening") { device.connectionStatus().connected }
            Socket(loopback, port).use { a ->
                Socket(loopback, port).use { b ->
                    a.getOutputStream().apply { write("a\n".toByteArray()); flush() }
                    b.getOutputStream().apply { write("b\n".toByteArray()); flush() }
                    await("both accepted") { count(device, port) == 2L }
                    Socket(loopback, port).use { c ->
                        c.soTimeout = 5_000
                        assertEquals(-1, c.getInputStream().read(), "the third client is closed")
                    }
                    await("refused") { device.read(listOf("$port/RefusedCount")).single().value == 1L }
                    a.getOutputStream().apply { write("a2\n".toByteArray()); flush() }
                    await("still served") { count(device, port) == 3L }
                }
            }
            // A slot frees when a client leaves.
            await("slots free") {
                runCatching {
                    Socket(loopback, port).use { d ->
                        d.getOutputStream().apply { write("d\n".toByteArray()); flush() }
                        Thread.sleep(50)
                    }
                }
                count(device, port) >= 4L
            }
        }
    }

    /** A subscriber that blocks holds up only the scan thread; the socket thread keeps receiving. */
    @Test fun aBlockedSubscriberDoesNotStallReception() {
        val port = freeTcpPort()
        ListenerDevice(ListenerDeviceConfig(name = "slow", address = loopback, ports = listOf(port), messageDelimiter = "\\n")).use { device ->
            device.start()
            await("listening") { device.connectionStatus().connected }
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val thread = AtomicReference<String>()
            device.subscribe(listOf("$port/Message"), 20.0) {
                thread.set(Thread.currentThread().name)
                entered.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Socket(loopback, port).use { client ->
                val out = client.getOutputStream()
                repeat(500) { out.write("m$it\n".toByteArray()) }
                out.flush()
                await("all received while the subscriber is blocked") { count(device, port) == 500L }
            }
            assertEquals("m499", device.read(listOf("$port/Message")).single().value)
            assertEquals("udptcp-scan-slow", thread.get())
            release.countDown()
        }
    }

    @Test fun closeReleasesEverySocketAndThread() {
        val tcpPort = freeTcpPort()
        val udpPort = freeUdpPort()
        val listen = ListenerDevice(ListenerDeviceConfig(name = "shut", address = loopback, ports = listOf(tcpPort), messageDelimiter = "\\n"))
        val udp = ListenerDevice(ListenerDeviceConfig(name = "shutudp", protocol = Protocol.UDP, address = loopback, ports = listOf(udpPort)))
        // Dials a port nobody listens on, so it is mid-retry when closed.
        val dial = ListenerDevice(ListenerDeviceConfig(name = "shutdial", mode = Mode.CONNECT, address = loopback, ports = listOf(freeTcpPort()), reconnectMs = 20))
        listen.start(); udp.start(); dial.start()
        await("up") { listen.connectionStatus().connected && udp.connectionStatus().connected }
        listen.subscribe(listOf("$tcpPort/Message"), 20.0) { }
        Socket(loopback, tcpPort).use { client ->
            client.getOutputStream().apply { write("x\n".toByteArray()); flush() }
            await("received") { count(listen, tcpPort) == 1L }
            assertTrue(listen.openChannelCount >= 2)
            Thread.sleep(100)
            listen.close(); udp.close(); dial.close()
            client.soTimeout = 5_000
            val eof = try {
                client.getInputStream().read() == -1
            } catch (_: java.io.IOException) {
                true
            } catch (_: SocketTimeoutException) {
                false
            }
            assertTrue(eof, "the accepted connection is closed")
        }
        assertEquals(0, listen.openChannelCount)
        assertEquals(0, udp.openChannelCount)
        assertEquals(0, dial.openChannelCount)
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(loopback, tcpPort)) }
        DatagramSocket(InetSocketAddress(loopback, udpPort)).close()
        await("threads gone") {
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("udptcp-") && it.name.substringAfterLast('-').startsWith("shut") }
        }
        assertEquals(ConnectionState.DISCONNECTED, listen.connectionStatus().state)
    }

    private fun count(device: ListenerDevice, port: Int): Long =
        device.read(listOf("$port/MessageCount")).single().value as Long

    private fun freeTcpPort(): Int = ServerSocket(0, 1, InetAddress.getByName(loopback)).use { it.localPort }

    private fun freeUdpPort(): Int = DatagramSocket(0, InetAddress.getByName(loopback)).use { it.localPort }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }
}
