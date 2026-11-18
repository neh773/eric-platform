package org.oip.mqtt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A clean close must reach the broker as a clean close. When the client closes its socket with broker bytes
 * still unread (a PUBACK, a PINGRESP), the kernel resets the connection and the broker can lose the
 * DISCONNECT, and the NDEATH before it, then publish the will instead (PR #114, SparkplugEdgeLocalTest flake).
 * The broker here leaves a packet unread on the client side and reads late, so the race is not left to timing.
 */
class MqttCleanCloseTest {
    /** A broker that answers CONNECT, then runs [script] on the connection. Records each packet type it reads. */
    private class SlowBroker(private val script: (java.net.Socket, java.io.InputStream) -> Unit) : AutoCloseable {
        val server = ServerSocket(0)
        val headers = CopyOnWriteArrayList<Int>()
        @Volatile var error: Throwable? = null
        val done = CountDownLatch(1)
        private val thread = Thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    MqttWire.readPacket(input)
                    socket.getOutputStream().apply { write(byteArrayOf(0x20, 0x02, 0x00, 0x00)); flush() }
                    script(socket, input)
                    while (true) {
                        val (header, _) = MqttWire.readPacket(input) ?: break
                        headers += header and 0xF0
                    }
                }
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    private fun client(port: Int) = MqttClient("127.0.0.1", port, "edge", connectTimeoutMs = 2_000)

    /**
     * The edge node's old clean close: NDEATH at QoS 1 without waiting, then DISCONNECT, with a broker packet
     * still unread on the client. The broker reads the NDEATH and acknowledges it before routing it, as the
     * test broker in EdgeTestSupport does. That write must not fail on a reset connection.
     */
    @Test fun disconnectWithAnUnreadPacketLetsTheBrokerAcknowledgeAndReadTheDisconnect() {
        val sent = CountDownLatch(1)
        SlowBroker { socket, input ->
            // A packet the client never polls for.
            socket.getOutputStream().apply { write(byteArrayOf(0xD0.toByte(), 0x00)); flush() }
            sent.countDown()
            Thread.sleep(300) // the client has published, disconnected and closed by now
            val (header, body) = MqttWire.readPacket(input)!!
            headersOf(header)
            socket.getOutputStream().apply { write(MqttWire.encodePubAck(MqttWire.publishPacketId(header, body)!!)); flush() }
        }.use { broker ->
            val client = client(broker.server.localPort)
            client.connect()
            assertTrue(sent.await(2, TimeUnit.SECONDS))
            Thread.sleep(50) // the PINGRESP is in the client's receive buffer
            client.publish("spBv1.0/G/NDEATH/E", byteArrayOf(1), qos = 1)
            client.disconnect()
            assertTrue(broker.done.await(3, TimeUnit.SECONDS))
            assertNull(broker.error, "the broker's PUBACK hit a reset connection: ${broker.error}")
            assertEquals(listOf(0xE0), broker.headers.toList(), "DISCONNECT reached the broker")
        }
    }

    @Test fun publishPollingAckReadsThePubAckOnTheCallingThread() {
        SlowBroker { socket, input ->
            val (header, body) = MqttWire.readPacket(input)!!
            headersOf(header)
            val id = MqttWire.publishPacketId(header, body)!!
            socket.getOutputStream().apply { write(MqttWire.encodePubAck(id)); flush() }
        }.use { broker ->
            val client = client(broker.server.localPort)
            client.connect()
            assertTrue(client.publishPollingAck("spBv1.0/G/NDEATH/E", byteArrayOf(1), timeoutMs = 2_000))
            client.disconnect()
            assertTrue(broker.done.await(3, TimeUnit.SECONDS))
            assertNull(broker.error)
            assertEquals(listOf(0xE0), broker.headers.toList())
        }
    }

    @Test fun publishPollingAckGivesUpAfterTheTimeout() {
        SlowBroker { _, input -> MqttWire.readPacket(input) }.use { broker ->
            val client = client(broker.server.localPort)
            client.connect()
            val started = System.nanoTime()
            assertFalse(client.publishPollingAck("spBv1.0/G/NDEATH/E", byteArrayOf(1), timeoutMs = 200))
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs in 150..1_500, "waited ${tookMs}ms for a 200 ms timeout")
            client.disconnect()
        }
    }

    private fun headersOf(header: Int) = assertEquals(0x30, header and 0xF0, "a PUBLISH")
}
