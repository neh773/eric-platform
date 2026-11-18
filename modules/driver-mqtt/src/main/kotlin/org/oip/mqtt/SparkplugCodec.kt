package org.oip.mqtt

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Sparkplug B 3.0 payload codec (protobuf encoding of the public `Payload` message).
 *
 * https://sparkplug.eclipse.org/specification/version/3.0/documents/sparkplug-specification-3.0.0.pdf
 *
 * Field numbers follow the Eclipse Sparkplug B proto: timestamp=1, metrics=2, seq=3, uuid=4, body=5.
 * Metric: name=1, alias=2, timestamp=3, datatype=4, is_historical=5, is_transient=6, is_null=7,
 * int_value=10, long_value=11, float_value=12, double_value=13, boolean_value=14, string_value=15.
 * Alias 0 is treated as absent. Dataset, template, metadata and properties are skipped.
 * Unknown fields are skipped so a newer payload still yields the metrics this host understands.
 */
object SparkplugCodec {
    const val INT8 = 1
    const val INT16 = 2
    const val INT32 = 3
    const val INT64 = 4
    const val UINT8 = 5
    const val UINT16 = 6
    const val UINT32 = 7
    const val UINT64 = 8
    const val FLOAT = 9
    const val DOUBLE = 10
    const val BOOLEAN = 11
    const val STRING = 12
    const val DATETIME = 13
    const val TEXT = 14
    const val UUID = 15

    const val STALE = 0x808D0000L

    class Metric(
        val name: String,
        val alias: Long?,
        val datatype: Int,
        val timestamp: Long?,
        val isNull: Boolean,
        val value: Any?,
    )

    class Payload(val timestamp: Long, val seq: Int?, val metrics: List<Metric>)

    fun encode(payload: Payload): ByteArray {
        val out = ProtoOut()
        if (payload.timestamp != 0L) out.varintField(1, payload.timestamp)
        for (metric in payload.metrics) {
            val body = encodeMetric(metric)
            out.key(2, 2)
            out.varint(body.size.toLong())
            out.raw(body)
        }
        if (payload.seq != null) out.varintField(3, (payload.seq and 0xFF).toLong())
        return out.toByteArray()
    }

    fun decode(raw: ByteArray): Payload {
        var timestamp = 0L
        var seq: Int? = null
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
                    seq = value.toInt() and 0xFF
                    index = n
                }
                field == 2 && wire == 2 -> {
                    val (length, n) = readVarint(raw, index)
                    index = n
                    val size = length.toInt()
                    if (index + size > raw.size) throw IllegalArgumentException("sparkplug metric is truncated")
                    metrics += decodeMetric(raw.copyOfRange(index, index + size))
                    index += size
                }
                else -> index = skip(raw, index, wire)
            }
        }
        return Payload(timestamp, seq, metrics)
    }

    fun tagDataType(datatype: Int): String? = when (datatype) {
        INT8, INT16, INT32, UINT8, UINT16 -> "Int32"
        INT64, UINT32, UINT64, DATETIME -> "Int64"
        FLOAT -> "Float"
        DOUBLE -> "Double"
        BOOLEAN -> "Boolean"
        STRING, TEXT, UUID -> "String"
        else -> null
    }

    private fun encodeMetric(metric: Metric): ByteArray {
        val out = ProtoOut()
        if (metric.name.isNotEmpty()) out.stringField(1, metric.name)
        if (metric.alias != null && metric.alias != 0L) out.varintField(2, metric.alias)
        if (metric.timestamp != null) out.varintField(3, metric.timestamp)
        out.varintField(4, metric.datatype.toLong())
        if (metric.isNull) {
            out.varintField(7, 1)
            return out.toByteArray()
        }
        when (metric.datatype) {
            INT8, INT16, INT32, UINT8, UINT16, UINT32 -> out.varintField(10, unsigned32(metric))
            INT64, UINT64, DATETIME -> out.varintField(11, (metric.value as Number).toLong())
            FLOAT -> {
                out.key(12, 5)
                val bits = java.lang.Float.floatToRawIntBits((metric.value as Number).toFloat())
                out.raw(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits).array())
            }
            DOUBLE -> {
                out.key(13, 1)
                val bits = java.lang.Double.doubleToRawLongBits((metric.value as Number).toDouble())
                out.raw(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bits).array())
            }
            BOOLEAN -> out.varintField(14, if (metric.value == true) 1 else 0)
            STRING, TEXT, UUID -> out.stringField(15, metric.value?.toString() ?: "")
            else -> throw IllegalArgumentException("sparkplug datatype ${metric.datatype} is not encoded")
        }
        return out.toByteArray()
    }

    private fun unsigned32(metric: Metric): Long = when (metric.datatype) {
        INT8 -> ((metric.value as Number).toInt() and 0xFF).toLong()
        INT16 -> ((metric.value as Number).toInt() and 0xFFFF).toLong()
        INT32 -> (metric.value as Number).toInt().toLong() and 0xFFFFFFFFL
        UINT8 -> (metric.value as Number).toLong() and 0xFF
        UINT16 -> (metric.value as Number).toLong() and 0xFFFF
        else -> (metric.value as Number).toLong() and 0xFFFFFFFFL
    }

    private fun decodeMetric(raw: ByteArray): Metric {
        var name = ""
        var alias: Long? = null
        var datatype = 0
        var timestamp: Long? = null
        var isNull = false
        var intBits: Long? = null
        var longBits: Long? = null
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
                field == 2 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    if (value != 0L) alias = value
                    index = n
                }
                field == 3 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    timestamp = value
                    index = n
                }
                field == 4 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    datatype = value.toInt()
                    index = n
                }
                field == 7 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    isNull = value != 0L
                    index = n
                }
                field == 10 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    intBits = value
                    index = n
                }
                field == 11 && wire == 0 -> {
                    val (value, n) = readVarint(raw, index)
                    longBits = value
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
        val value = if (isNull) null else interpret(datatype, intBits, longBits, floatValue, doubleValue, boolValue, stringValue)
        return Metric(name, alias, datatype, timestamp, isNull, value)
    }

    private fun interpret(
        datatype: Int,
        intBits: Long?,
        longBits: Long?,
        floatValue: Float?,
        doubleValue: Double?,
        boolValue: Boolean?,
        stringValue: String?,
    ): Any? {
        val bits = (intBits ?: 0L).toInt()
        return when (datatype) {
            INT8 -> bits.toByte().toInt()
            INT16 -> bits.toShort().toInt()
            INT32 -> bits
            INT64, DATETIME -> longBits ?: 0L
            UINT8 -> bits and 0xFF
            UINT16 -> bits and 0xFFFF
            UINT32 -> (intBits ?: 0L) and 0xFFFFFFFFL
            UINT64 -> longBits ?: 0L
            FLOAT -> floatValue ?: 0f
            DOUBLE -> doubleValue ?: 0.0
            BOOLEAN -> boolValue ?: false
            STRING, TEXT, UUID -> stringValue ?: ""
            else -> null
        }
    }

    private fun skip(raw: ByteArray, index: Int, wire: Int): Int = when (wire) {
        0 -> readVarint(raw, index).second
        1 -> index + 8
        5 -> index + 4
        2 -> {
            val (length, next) = readVarint(raw, index)
            next + length.toInt()
        }
        else -> throw IllegalArgumentException("sparkplug wire type $wire")
    }

    private fun readVarint(raw: ByteArray, start: Int): Pair<Long, Int> {
        var index = start
        var shift = 0
        var value = 0L
        while (true) {
            if (index >= raw.size || shift > 63) throw IllegalArgumentException("truncated varint")
            val byte = raw[index].toLong() and 0xFF
            index += 1
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80L == 0L) return value to index
            shift += 7
        }
    }

    private class ProtoOut {
        private val out = ByteArrayOutputStream()

        fun raw(value: ByteArray) {
            out.write(value)
        }

        fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

        fun varint(value: Long) {
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
            raw(raw)
        }

        fun toByteArray() = out.toByteArray()
    }
}
