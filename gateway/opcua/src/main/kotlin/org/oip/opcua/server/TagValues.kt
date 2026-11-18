package org.oip.opcua.server

import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte
import org.oip.tags.DataType
import org.oip.tags.QualifiedValue
import org.oip.tags.Quality
import java.time.Instant

internal object TagValues {
    /** OPC UA AccessLevel: bit 0 CurrentRead, bit 1 CurrentWrite (OPC 10000-3). */
    fun accessLevel(writable: Boolean): UByte = ubyte(if (writable) 0x03 else 0x01)

    fun status(code: Int): StatusCode = StatusCode(code.toLong() and 0xffff_ffffL)

    fun status(quality: Quality): StatusCode = status(quality.toOpcUa())

    /**
     * Source time is the tag's source timestamp when the tag has one, otherwise the gateway
     * timestamp. Server time is always the gateway timestamp (SPEC §5).
     */
    fun dataValue(qv: QualifiedValue): DataValue {
        val source = qv.sourceTimestamp ?: qv.timestamp
        return DataValue(variant(qv.value), status(qv.quality), dateTime(source), dateTime(qv.timestamp))
    }

    fun variant(value: Any?): Variant = Variant(opcValue(value))

    fun opcValue(value: Any?): Any? = when (value) {
        null -> null
        is Boolean, is Int, is Long, is Float, is Double, is String -> value
        is Instant -> dateTime(value)
        is org.oip.tags.Dataset -> value.toJson()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "$k=$v" }
        is List<*> -> value.toString()
        else -> value.toString()
    }

    /** Values a client write sends, turned into the types the tag engine coerces. */
    fun fromOpc(value: Any?): Any? = when (value) {
        null -> null
        is DateTime -> if (value.isNull) null else value.javaInstant
        is UByte -> value.toInt()
        is UShort -> value.toInt()
        is UInteger -> value.toLong()
        is ULong -> value.toLong()
        else -> value
    }

    fun dateTime(instant: Instant): DateTime = DateTime(instant)

    fun inferredType(value: Any?): DataType? = when (value) {
        is Boolean -> DataType.BOOLEAN
        is Int -> DataType.INT32
        is Long -> DataType.INT64
        is Float -> DataType.FLOAT
        is Double -> DataType.DOUBLE
        is String -> DataType.STRING
        is Instant -> DataType.DATETIME
        else -> null
    }
}
