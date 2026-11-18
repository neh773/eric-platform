package org.oip.testinfra

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Sparkplug B payload codec for the metrics the edge-node simulator publishes.
 * https://sparkplug.eclipse.org/specification/version/3.0.0/
 *
 * Encoded fields: timestamp, seq, and metrics with name, datatype and one of
 * int32/uint32 (field 10), int64/uint64 (field 11), float, double, boolean, string.
 * Other protobuf fields are skipped on decode.
 */
object Sparkplug {
    const val TYPE_INT32 = 3
    const val TYPE_INT64 = 4
    const val TYPE_UINT64 = 8
    const val TYPE_FLOAT = 9
    const val TYPE_DOUBLE = 10
    const val TYPE_BOOLEAN = 11
    const val TYPE_STRING = 12

    data class Metric(
        val name: String,
        val datatype: Int,
        val intValue: Long? = null,
        val longValue: Long? = null,
        val floatValue: Float? = null,
        val doubleValue: Double? = null,
        val boolValue: Boolean? = null,
        val stringValue: String? = null,
    )

    data class Payload(val timestamp: Long, val seq: Int, val metrics: List<Metric>)

    fun encode(payload: Payload): ByteArray {
        val out = ByteArrayOutput()
        out.varintField(1, payload.timestamp)
        for (metric in payload.metrics) {
            val body = encodeMetric(metric)
            out.key(2, 2)
            out.varint(body.size.toLong())
            out.bytes(body)
        }
        out.varintField(3, (payload.seq and 0xFF).toLong())
        return out.toByteArray()
    }

    fun decode(raw: ByteArray): Payload {
        var timestamp = 0L
        var seq = 0
        val metrics = mutableListOf<Metric>()
        var index = 0
        while (index < raw.size) {
            val (key, next) = readVarint(raw, index)
            index = next
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            when {
                field == 1 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    timestamp = value
                    index = n
                }
                field == 3 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    seq = value.toInt()
                    index = n
                }
                field == 2 && wire == 2 -> {
                    val (length, n) = readVarint(raw, index)
                    index = n
                    val size = length.toInt()
                    metrics += decodeMetric(raw.copyOfRange(index, index + size))
                    index += size
                }
                else -> index = skip(raw, index, wire)
            }
        }
        return Payload(timestamp, seq, metrics)
    }

    private fun encodeMetric(metric: Metric): ByteArray {
        val out = ByteArrayOutput()
        out.stringField(1, metric.name)
        out.varintField(4, metric.datatype.toLong())
        when {
            metric.intValue != null -> out.varintField(10, metric.intValue)
            metric.longValue != null -> out.varintField(11, metric.longValue)
            metric.floatValue != null -> {
                out.key(12, 5)
                val bits = java.lang.Float.floatToIntBits(metric.floatValue)
                out.bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits).array())
            }
            metric.doubleValue != null -> {
                out.key(13, 1)
                val bits = java.lang.Double.doubleToLongBits(metric.doubleValue)
                out.bytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bits).array())
            }
            metric.boolValue != null -> out.varintField(14, if (metric.boolValue) 1 else 0)
            metric.stringValue != null -> out.stringField(15, metric.stringValue)
            else -> error("metric ${metric.name} has no value")
        }
        return out.toByteArray()
    }

    private fun decodeMetric(raw: ByteArray): Metric {
        var name = ""
        var datatype = 0
        var intValue: Long? = null
        var longValue: Long? = null
        var floatValue: Float? = null
        var doubleValue: Double? = null
        var boolValue: Boolean? = null
        var stringValue: String? = null
        var index = 0
        while (index < raw.size) {
            val (key, next) = readVarint(raw, index)
            index = next
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            when {
                field == 1 && wire == 2 -> {
                    val (length, n) = readVarint(raw, index)
                    index = n
                    val size = length.toInt()
                    name = String(raw, index, size, StandardCharsets.UTF_8)
                    index += size
                }
                field == 4 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    datatype = value.toInt()
                    index = n
                }
                field == 10 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    intValue = value
                    index = n
                }
                field == 11 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    longValue = value
                    index = n
                }
                field == 12 && wire == 5 -> {
                    floatValue = ByteBuffer.wrap(raw, index, 4).order(ByteOrder.LITTLE_ENDIAN).float
                    index += 4
                }
                field == 13 && wire == 1 -> {
                    doubleValue = ByteBuffer.wrap(raw, index, 8).order(ByteOrder.LITTLE_ENDIAN).double
                    index += 8
                }
                field == 14 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    boolValue = value != 0L
                    index = n
                }
                field == 15 && wire == 2 -> {
                    val (length, n) = readVarint(raw, index)
                    index = n
                    val size = length.toInt()
                    stringValue = String(raw, index, size, StandardCharsets.UTF_8)
                    index += size
                }
                else -> index = skip(raw, index, wire)
            }
        }
        return Metric(name, datatype, intValue, longValue, floatValue, doubleValue, boolValue, stringValue)
    }

    private fun skip(raw: ByteArray, index: Int, wire: Int): Int = when (wire) {
        0 -> readVarint(raw, index).second
        1 -> index + 8
        5 -> index + 4
        2 -> {
            val (length, next) = readVarint(raw, index)
            next + length.toInt()
        }
        else -> error("unsupported wire type $wire")
    }

    private fun readVarint(raw: ByteArray, start: Int): Pair<Long, Int> {
        var index = start
        var shift = 0
        var value = 0L
        while (true) {
            if (index >= raw.size || shift > 63) error("truncated varint")
            val byte = raw[index].toLong() and 0xFF
            index += 1
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80L == 0L) return value to index
            shift += 7
        }
    }

    private class ByteArrayOutput {
        private val out = java.io.ByteArrayOutputStream()
        fun bytes(value: ByteArray) {
            out.write(value)
        }
        fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun varint(value: Long) {
            require(value >= 0)
            var rest = value
            while (true) {
                var byte = (rest and 0x7F).toInt()
                rest = rest ushr 7
                if (rest != 0L) byte = byte or 0x80
                out.write(byte)
                if (rest == 0L) return
            }
        }
        fun varintField(field: Int, value: Long) {
            key(field, 0)
            varint(value)
        }
        fun stringField(field: Int, text: String) {
            val raw = text.toByteArray(StandardCharsets.UTF_8)
            key(field, 2)
            varint(raw.size.toLong())
            bytes(raw)
        }
        fun toByteArray() = out.toByteArray()
    }
}
