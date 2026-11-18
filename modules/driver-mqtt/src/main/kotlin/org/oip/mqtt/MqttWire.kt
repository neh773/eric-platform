package org.oip.mqtt

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * MQTT 3.1.1 and MQTT 5.0 packet codec.
 *
 * OASIS MQTT 3.1.1: https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html
 * OASIS MQTT 5.0: https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html
 *
 * MQTT 5 properties are a variable-byte length plus that many bytes. This client sends an empty
 * property block except Session Expiry Interval 0 on CONNECT, and skips property blocks it receives.
 */
enum class MqttProtocol(val level: Int) {
    MQTT_3_1_1(4),
    MQTT_5(5),
}

class MqttWill(
    val topic: String,
    val payload: ByteArray,
    val qos: Int,
    val retain: Boolean,
)

internal class MqttConnectSpec(
    val protocol: MqttProtocol,
    val clientId: String,
    val keepAliveSeconds: Int,
    val cleanSession: Boolean,
    val username: String?,
    val password: ByteArray?,
    val will: MqttWill?,
)

internal class MqttIncoming(
    val topic: String,
    val payload: ByteArray,
    val retain: Boolean,
)

internal object MqttWire {
    const val MAX_PACKET = 8 * 1024 * 1024

    fun encodeConnect(spec: MqttConnectSpec): ByteArray {
        val body = ByteArrayOutputStream()
        writeMqttString(body, "MQTT")
        body.write(spec.protocol.level)
        var flags = 0
        if (spec.cleanSession) flags = flags or 0x02
        val will = spec.will
        if (will != null) {
            flags = flags or 0x04
            flags = flags or ((will.qos and 0x03) shl 3)
            if (will.retain) flags = flags or 0x20
        }
        if (spec.password != null) flags = flags or 0x40
        if (spec.username != null) flags = flags or 0x80
        body.write(flags)
        writeShort(body, spec.keepAliveSeconds)
        if (spec.protocol == MqttProtocol.MQTT_5) {
            // Session Expiry Interval (identifier 17) = 0. Clean Start + expiry 0 is a clean session.
            writeVarint(body, 5)
            body.write(0x11)
            body.write(0)
            body.write(0)
            body.write(0)
            body.write(0)
        }
        writeMqttString(body, spec.clientId)
        if (will != null) {
            if (spec.protocol == MqttProtocol.MQTT_5) writeVarint(body, 0)
            writeMqttString(body, will.topic)
            writePrefixed(body, will.payload)
        }
        if (spec.username != null) writeMqttString(body, spec.username)
        if (spec.password != null) writePrefixed(body, spec.password)
        return packet(0x10, body.toByteArray())
    }

    fun encodePublish(protocol: MqttProtocol, topic: String, payload: ByteArray, qos: Int, retain: Boolean, packetId: Int): ByteArray {
        val body = ByteArrayOutputStream()
        writeMqttString(body, topic)
        if (qos > 0) writeShort(body, packetId)
        if (protocol == MqttProtocol.MQTT_5) writeVarint(body, 0)
        body.write(payload)
        var header = 0x30
        if (retain) header = header or 0x01
        header = header or ((qos and 0x03) shl 1)
        return packet(header, body.toByteArray())
    }

    fun encodeSubscribe(protocol: MqttProtocol, packetId: Int, filter: String, qos: Int): ByteArray {
        val body = ByteArrayOutputStream()
        writeShort(body, packetId)
        if (protocol == MqttProtocol.MQTT_5) writeVarint(body, 0)
        writeMqttString(body, filter)
        body.write(qos and 0x03)
        return packet(0x82, body.toByteArray())
    }

    fun encodePubAck(packetId: Int): ByteArray = packet(0x40, byteArrayOf((packetId shr 8).toByte(), packetId.toByte()))

    fun encodePing(): ByteArray = byteArrayOf(0xC0.toByte(), 0x00)

    fun encodeDisconnect(): ByteArray = byteArrayOf(0xE0.toByte(), 0x00)

    fun packet(header: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(header)
        writeRemaining(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    fun readPacket(input: InputStream): Pair<Int, ByteArray>? {
        val first = input.read()
        if (first < 0) return null
        val remaining = readRemaining(input)
        if (remaining > MAX_PACKET) throw IllegalStateException("mqtt packet is larger than $MAX_PACKET bytes")
        return first to readFully(input, remaining)
    }

    fun decodePublish(protocol: MqttProtocol, header: Int, body: ByteArray): MqttIncoming {
        var index = 0
        val topic = readMqttString(body, index).also { index = it.second }.first
        val qos = (header shr 1) and 0x03
        if (qos > 0) {
            if (index + 2 > body.size) throw IllegalStateException("mqtt publish is truncated")
            index += 2
        }
        if (protocol == MqttProtocol.MQTT_5) index = skipProperties(body, index)
        val payload = if (index >= body.size) ByteArray(0) else body.copyOfRange(index, body.size)
        return MqttIncoming(topic, payload, header and 0x01 == 1)
    }

    fun publishPacketId(header: Int, body: ByteArray): Int? {
        val qos = (header shr 1) and 0x03
        if (qos == 0) return null
        val topicLen = readShort(body, 0)
        val at = 2 + topicLen
        if (at + 2 > body.size) return null
        return readShort(body, at)
    }

    /** Skip a MQTT 5 property block. Returns the index of the first byte after it. */
    fun skipProperties(body: ByteArray, start: Int): Int {
        val (length, next) = readVarint(body, start)
        val end = next + length.toInt()
        if (end > body.size) throw IllegalStateException("mqtt properties are truncated")
        return end
    }

    fun readMqttString(body: ByteArray, start: Int): Pair<String, Int> {
        if (start + 2 > body.size) throw IllegalStateException("mqtt string is truncated")
        val length = readShort(body, start)
        val from = start + 2
        val to = from + length
        if (to > body.size) throw IllegalStateException("mqtt string is truncated")
        val text = String(body, from, length, StandardCharsets.UTF_8)
        if (text.indexOf('\u0000') >= 0) throw IllegalStateException("mqtt string contains U+0000")
        return text to to
    }

    fun readPrefixed(body: ByteArray, start: Int): Pair<ByteArray, Int> {
        if (start + 2 > body.size) throw IllegalStateException("mqtt field is truncated")
        val length = readShort(body, start)
        val from = start + 2
        val to = from + length
        if (to > body.size) throw IllegalStateException("mqtt field is truncated")
        return body.copyOfRange(from, to) to to
    }

    fun readShort(body: ByteArray, start: Int): Int =
        ((body[start].toInt() and 0xFF) shl 8) or (body[start + 1].toInt() and 0xFF)

    private fun writeMqttString(out: ByteArrayOutputStream, text: String) {
        if (text.indexOf('\u0000') >= 0) throw IllegalArgumentException("mqtt string contains U+0000")
        writePrefixed(out, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writePrefixed(out: ByteArrayOutputStream, raw: ByteArray) {
        if (raw.size > 65535) throw IllegalArgumentException("mqtt field is longer than 65535 bytes")
        writeShort(out, raw.size)
        out.write(raw)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeRemaining(out: ByteArrayOutputStream, length: Int) {
        var rest = length
        do {
            var encoded = rest and 0x7F
            rest = rest ushr 7
            if (rest > 0) encoded = encoded or 0x80
            out.write(encoded)
        } while (rest > 0)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        do {
            var encoded = rest and 0x7F
            rest = rest ushr 7
            if (rest > 0) encoded = encoded or 0x80
            out.write(encoded)
        } while (rest > 0)
    }

    private fun readRemaining(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        repeat(4) {
            val encoded = input.read()
            if (encoded < 0) throw EOFException("mqtt remaining length is truncated")
            value += (encoded and 0x7F) * multiplier
            if (encoded and 0x80 == 0) return value
            multiplier *= 128
        }
        throw IllegalStateException("mqtt remaining length is malformed")
    }

    private fun readVarint(body: ByteArray, start: Int): Pair<Long, Int> {
        var index = start
        var shift = 0
        var value = 0L
        while (true) {
            if (index >= body.size || shift > 63) throw IllegalStateException("mqtt varint is truncated")
            val byte = body[index].toLong() and 0xFF
            index += 1
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80L == 0L) return value to index
            shift += 7
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(buf, off, length - off)
            if (n < 0) throw EOFException("mqtt packet is truncated")
            off += n
        }
        return buf
    }
}
