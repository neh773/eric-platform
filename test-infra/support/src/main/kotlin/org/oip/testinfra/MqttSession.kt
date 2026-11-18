package org.oip.testinfra

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class MqttPublish(val topic: String, val payload: ByteArray, val retain: Boolean)

/**
 * Small MQTT 3.1.1 client (clean session, QoS 0 publishes) for smoke tests.
 * https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html
 */
class MqttSession(private val host: String, private val port: Int, private val timeoutMs: Int = 5_000) : AutoCloseable {
    private val socket = Socket()
    private val incoming = ArrayBlockingQueue<MqttPublish>(512)
    private val packetIds = AtomicInteger(1)
    private val closed = AtomicBoolean(false)
    private val connAck = CompletableFuture<Int>()
    private val subAcks = ConcurrentHashMap<Int, CompletableFuture<Int>>()
    @Volatile private var failure: Throwable? = null
    private var reader: Thread? = null
    private var output: java.io.OutputStream? = null

    fun connect(clientId: String = "oip-smoke") {
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = 0
            val input = socket.getInputStream()
            output = socket.getOutputStream()
            reader = Thread({ readLoop(input) }, "mqtt-read").also { it.isDaemon = true; it.start() }
            write(MqttPackets.connect(clientId, 60))
            val code = connAck.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (code != 0) error("MQTT CONNACK return code $code")
        } catch (ex: Exception) {
            close()
            throw ex
        }
    }

    fun subscribe(filter: String) {
        failure?.let { throw it }
        val id = packetIds.getAndUpdate { current -> if (current == 0xFFFF) 1 else current + 1 }
        val future = CompletableFuture<Int>()
        subAcks[id] = future
        write(MqttPackets.subscribe(id, filter))
        val code = future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (code == 0x80) error("MQTT SUBACK rejected $filter")
    }

    fun receive(waitMs: Long): MqttPublish? {
        failure?.let { throw it }
        return incoming.poll(waitMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            write(byteArrayOf(0xE0.toByte(), 0x00))
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
        reader?.join(1_000)
    }

    private fun write(bytes: ByteArray) {
        val out = output ?: return
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
    }

    private fun readLoop(input: InputStream) {
        try {
            while (!closed.get()) {
                val (type, body) = MqttPackets.read(input)
                when (type and 0xF0) {
                    0x20 -> if (body.size >= 2) connAck.complete(body[1].toInt() and 0xFF)
                    0x90 -> if (body.size >= 3) {
                        val id = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                        subAcks.remove(id)?.complete(body[2].toInt() and 0xFF)
                    }
                    0x30 -> incoming.put(MqttPackets.decodePublish(type, body))
                }
            }
        } catch (ex: Exception) {
            if (!closed.get()) {
                failure = ex
                connAck.completeExceptionally(ex)
                subAcks.values.forEach { it.completeExceptionally(ex) }
            }
        }
    }
}

internal object MqttPackets {
    fun connect(clientId: String, keepAliveSeconds: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeMqttString("MQTT")
        body.write(4)
        body.write(0x02)
        body.write((keepAliveSeconds shr 8) and 0xFF)
        body.write(keepAliveSeconds and 0xFF)
        body.writeMqttString(clientId)
        return frame(0x10, body.toByteArray())
    }

    fun subscribe(packetId: Int, topic: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId shr 8) and 0xFF)
        body.write(packetId and 0xFF)
        body.writeMqttString(topic)
        body.write(0)
        return frame(0x82, body.toByteArray())
    }

    fun decodePublish(typeAndFlags: Int, body: ByteArray): MqttPublish {
        val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val topic = String(body, 2, topicLength, StandardCharsets.UTF_8)
        var offset = 2 + topicLength
        val qos = (typeAndFlags shr 1) and 0x03
        if (qos > 0) offset += 2
        return MqttPublish(topic, body.copyOfRange(offset, body.size), typeAndFlags and 0x01 == 1)
    }

    fun read(input: InputStream): Pair<Int, ByteArray> {
        val first = input.read()
        if (first < 0) throw EOFException("mqtt closed")
        var multiplier = 1
        var value = 0
        while (true) {
            val encoded = input.read()
            if (encoded < 0) throw EOFException("mqtt closed")
            value += (encoded and 127) * multiplier
            if (multiplier > 128 * 128 * 128) throw java.io.IOException("malformed remaining length")
            multiplier *= 128
            if (encoded and 128 == 0) break
        }
        return first to readFully(input, value)
    }

    private fun frame(type: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type)
        var remaining = body.size
        do {
            var encoded = remaining % 128
            remaining /= 128
            if (remaining > 0) encoded = encoded or 128
            out.write(encoded)
        } while (remaining > 0)
        out.write(body)
        return out.toByteArray()
    }
}

private fun ByteArrayOutputStream.writeMqttString(text: String) {
    val raw = text.toByteArray(StandardCharsets.UTF_8)
    write((raw.size shr 8) and 0xFF)
    write(raw.size and 0xFF)
    write(raw)
}
