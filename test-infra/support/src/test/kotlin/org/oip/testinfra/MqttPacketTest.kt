package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class MqttPacketTest {
    @Test fun connectAndSubscribeMatchMqtt311AndPublishIsDelivered() {
        val server = ServerSocket(0)
        val received = CompletableFuture<ByteArray>()
        val thread = Thread {
            val socket = server.accept()
            socket.soTimeout = 3_000
            val input = socket.getInputStream()
            val first = readAvailable(input, 17)
            received.complete(first)
            socket.getOutputStream().write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
            val subscribe = readAvailable(input, 256)
            val packetId = subscribe.copyOfRange(2, 4)
            socket.getOutputStream().write(byteArrayOf(0x90.toByte(), 0x03, packetId[0], packetId[1], 0x00))
            val topic = "spBv1.0/OIP/DDATA/SimEdge/SimDevice".toByteArray()
            val payload = byteArrayOf(0x01, 0x02)
            val body = ByteArray(2 + topic.size + payload.size)
            body[0] = (topic.size shr 8).toByte()
            body[1] = topic.size.toByte()
            topic.copyInto(body, 2)
            payload.copyInto(body, 2 + topic.size)
            val packet = ByteArray(2 + body.size)
            packet[0] = 0x31
            packet[1] = body.size.toByte()
            body.copyInto(packet, 2)
            socket.getOutputStream().write(packet)
            socket.getOutputStream().flush()
            Thread.sleep(500)
            socket.close()
        }
        thread.isDaemon = true
        thread.start()
        try {
            MqttSession("127.0.0.1", server.localPort, 2_000).use { session ->
                session.connect("oip")
                val connect = received.get(2, TimeUnit.SECONDS)
                assertEquals(
                    "100f00044d5154540402003c00036f6970",
                    connect.toHex(),
                )
                session.subscribe("spBv1.0/OIP/#")
                val publish = session.receive(2_000) ?: error("no publish")
                assertEquals("spBv1.0/OIP/DDATA/SimEdge/SimDevice", publish.topic)
                assertEquals("0102", publish.payload.toHex())
                assertEquals(true, publish.retain)
            }
        } finally {
            server.close()
            thread.join(2_000)
        }
    }

    private fun readAvailable(input: java.io.InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var off = 0
        val deadline = System.nanoTime() + 2_000_000_000L
        while (off < 2 && System.nanoTime() < deadline) {
            val n = input.read(buf, off, max - off)
            if (n < 0) break
            off += n
            if (off >= 2 && buf[0] == 0x10.toByte() && off >= 2 + (buf[1].toInt() and 0xFF)) break
            if (off >= 2 && buf[0] == 0x82.toByte()) {
                val remaining = buf[1].toInt() and 0xFF
                if (off >= 2 + remaining) break
            }
        }
        return buf.copyOf(off)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
