package org.oip.db

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Value parameters are coerced to the declared type before they are bound.
 * A value that does not fit is rejected and is never concatenated into the SQL.
 */
internal object Coercion {
    private val INT_TEXT = Regex("[-+]?\\d+")
    private val NUMBER_TEXT = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")

    fun coerce(dataType: String, value: Any?): Any? {
        if (value == null) return null
        return when (dataType) {
            "string" -> string(value)
            "integer" -> integer(value)
            "long" -> long(value)
            "float" -> double(value).also { finite(it) }.toFloat()
            "double" -> double(value).also { finite(it) }
            "boolean" -> bool(value)
            "datetime" -> timestamp(value)
            else -> throw QueryRejected("unknown parameter type $dataType")
        }
    }

    private fun string(value: Any): String = when (value) {
        is String -> value
        else -> throw QueryRejected("parameter expects a string")
    }

    private fun integer(value: Any): Int {
        val n = long(value)
        if (n !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw QueryRejected("integer is out of range")
        return n.toInt()
    }

    private fun long(value: Any): Long = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Double -> whole(value)
        is Float -> whole(value.toDouble())
        is String -> {
            val text = value.trim()
            if (!INT_TEXT.matches(text)) throw QueryRejected("parameter expects an integer")
            text.toLongOrNull() ?: throw QueryRejected("integer is out of range")
        }
        else -> throw QueryRejected("parameter expects an integer")
    }

    private fun whole(value: Double): Long {
        finite(value)
        if (value % 1.0 != 0.0) throw QueryRejected("parameter expects an integer")
        if (value !in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) throw QueryRejected("integer is out of range")
        return value.toLong()
    }

    private fun double(value: Any): Double = when (value) {
        is Byte, is Short, is Int, is Long, is Float, is Double -> (value as Number).toDouble()
        is String -> {
            val text = value.trim()
            if (!NUMBER_TEXT.matches(text)) throw QueryRejected("parameter expects a number")
            text.toDouble()
        }
        else -> throw QueryRejected("parameter expects a number")
    }

    private fun finite(value: Double) {
        if (!value.isFinite()) throw QueryRejected("parameter expects a finite number")
    }

    private fun bool(value: Any): Boolean = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw QueryRejected("parameter expects true or false")
        }
        else -> throw QueryRejected("parameter expects true or false")
    }

    private fun timestamp(value: Any): Timestamp = when (value) {
        is Number -> {
            val millis = value.toLong()
            if (value.toDouble() != millis.toDouble()) throw QueryRejected("parameter expects a datetime")
            Timestamp.from(Instant.ofEpochMilli(millis))
        }
        is String -> Timestamp.from(parseTime(value.trim()))
        else -> throw QueryRejected("parameter expects a datetime")
    }

    private fun parseTime(text: String): Instant {
        runCatching { return Instant.parse(text) }
        runCatching { return OffsetDateTime.parse(text).toInstant() }
        runCatching { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
        throw QueryRejected("parameter expects an ISO-8601 datetime")
    }
}

internal fun jsonValue(value: Any?): Any? = when (value) {
    null -> null
    is Boolean, is Int, is Long, is String -> value
    is Float, is Double -> (value as Number).toDouble()
    is java.math.BigDecimal -> value.toPlainString()
    is java.sql.Timestamp -> value.toInstant().toString()
    is java.sql.Date -> value.toLocalDate().toString()
    is java.sql.Time -> value.toLocalTime().toString()
    is ByteArray -> java.util.Base64.getEncoder().encodeToString(value)
    is Number -> value.toString()
    else -> value.toString()
}

/** Drop secret text from a message that might be returned or logged. Short tokens are left alone. */
internal fun redact(message: String, secrets: Collection<String>): String {
    var out = message
    for (secret in secrets) {
        if (secret.length >= 8) out = out.replace(secret, "[redacted]")
    }
    return out.take(300)
}
