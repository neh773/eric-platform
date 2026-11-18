package org.oip.s7

import java.time.LocalDate
import java.time.DayOfWeek

/**
 * Value encoding for the absolute S7 types. Doc source: FEATURES DEV-036.
 *
 * Memory values are big-endian. Strings use the S7 layout (max length, actual length, characters).
 * Timers are S5TIME scaled to milliseconds (a DWord-sized count). Counters are 3-digit BCD.
 * DATE_AND_TIME is the 8-byte BCD structure, exposed as `yyyy-MM-ddTHH:mm:ss.SSS` (DIFFERENCES 325).
 */
object S7Codec {
    private val DT = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{3})$""")

    fun decode(address: ParsedAddress, bytes: ByteArray): Any {
        if (bytes.size < valueBytes(address)) throw TypeMismatchException("short ${address.type} value")
        return when (address.type) {
            S7Type.BIT -> ((bytes[0].toInt() ushr (address.bit ?: 0)) and 1) == 1
            S7Type.BYTE -> bytes[0].toInt() and 0xFF
            S7Type.CHAR -> bytes[0].toInt()
            S7Type.WORD -> u16(bytes, 0)
            S7Type.INT -> u16(bytes, 0).toShort().toInt()
            S7Type.DWORD -> u32(bytes, 0)
            S7Type.DINT -> u32(bytes, 0).toInt()
            S7Type.REAL -> Float.fromBits(u32(bytes, 0).toInt())
            S7Type.LINT -> u64(bytes, 0)
            S7Type.LREAL -> Double.fromBits(u64(bytes, 0))
            S7Type.STRING -> decodeString(bytes, address.stringMax ?: S7Address.MAX_STRING)
            S7Type.DT -> decodeDt(bytes)
            S7Type.TIMER -> decodeS5(u16(bytes, 0))
            S7Type.COUNTER -> decodeCounter(u16(bytes, 0))
        }
    }

    /** Raw memory bytes for a non-bit write. Bit writes are a single 0/1 and use a bit transfer. */
    fun encode(address: ParsedAddress, value: Any?): ByteArray = when (address.type) {
        S7Type.BIT -> {
            if (value !is Boolean) throw TypeMismatchException("bit write expects boolean")
            byteArrayOf(if (value) 1 else 0)
        }
        S7Type.BYTE -> byteArrayOf(integral(value, 0, 255).toInt().toByte())
        S7Type.CHAR -> byteArrayOf(integral(value, -128, 127).toInt().toByte())
        S7Type.WORD -> put16(integral(value, 0, 65535).toInt())
        S7Type.INT -> put16(integral(value, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt() and 0xFFFF)
        S7Type.DWORD -> put32(integral(value, 0, 0xFFFF_FFFFL))
        S7Type.DINT -> put32(integral(value, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) and 0xFFFF_FFFFL)
        S7Type.REAL -> put32(realBits(value).toLong() and 0xFFFF_FFFFL)
        S7Type.LINT -> put64(integral(value, Long.MIN_VALUE, Long.MAX_VALUE))
        S7Type.LREAL -> put64(lrealBits(value))
        S7Type.STRING -> encodeString(value, address.stringMax ?: S7Address.MAX_STRING)
        S7Type.DT -> encodeDt(value)
        S7Type.TIMER -> put16(encodeS5(integral(value, 0, 9_990_000).toInt()))
        S7Type.COUNTER -> put16(encodeCounter(integral(value, 0, 999).toInt()))
    }

    fun valueBytes(address: ParsedAddress): Int = when (address.type) {
        S7Type.TIMER, S7Type.COUNTER -> 2
        else -> address.span
    }

    /** S5TIME word for [millis]. The smallest time base that can hold the value is used. */
    fun encodeS5(millis: Int): Int {
        if (millis < 0 || millis > 9_990_000) throw OutOfRangeException("timer $millis ms is outside S5TIME")
        val bases = intArrayOf(10, 100, 1_000, 10_000)
        for (base in 0..3) {
            val mult = bases[base]
            if (millis > 999 * mult) continue
            val ticks = (millis + mult / 2) / mult
            if (ticks > 999) continue
            val hundreds = ticks / 100
            val tens = (ticks / 10) % 10
            val ones = ticks % 10
            return (base shl 12) or (hundreds shl 8) or (tens shl 4) or ones
        }
        throw OutOfRangeException("timer $millis ms is outside S5TIME")
    }

    fun decodeS5(word: Int): Int {
        val base = (word ushr 12) and 0x3
        val hundreds = (word ushr 8) and 0xF
        val tens = (word ushr 4) and 0xF
        val ones = word and 0xF
        if (hundreds > 9 || tens > 9 || ones > 9) throw TypeMismatchException("S5TIME is not BCD")
        val ticks = hundreds * 100 + tens * 10 + ones
        val mult = intArrayOf(10, 100, 1_000, 10_000)[base]
        return ticks * mult
    }

    fun encodeCounter(value: Int): Int {
        if (value !in 0..999) throw OutOfRangeException("counter $value is outside 0..999")
        return ((value / 100) shl 8) or (((value / 10) % 10) shl 4) or (value % 10)
    }

    fun decodeCounter(word: Int): Int {
        val hundreds = (word ushr 8) and 0xF
        val tens = (word ushr 4) and 0xF
        val ones = word and 0xF
        if (hundreds > 9 || tens > 9 || ones > 9) throw TypeMismatchException("counter is not BCD")
        return hundreds * 100 + tens * 10 + ones
    }

    private fun decodeString(bytes: ByteArray, max: Int): String {
        val actual = bytes[1].toInt() and 0xFF
        val n = actual.coerceIn(0, minOf(max, bytes.size - 2))
        return String(bytes, 2, n, Charsets.ISO_8859_1)
    }

    private fun encodeString(value: Any?, max: Int): ByteArray {
        if (value !is String) throw TypeMismatchException("string write expects a string")
        if (value.length > max) throw OutOfRangeException("string length ${value.length} exceeds $max")
        for (ch in value) if (ch.code > 0xFF) throw TypeMismatchException("string has a character outside ISO-8859-1")
        val out = ByteArray(2 + max)
        out[0] = max.toByte()
        out[1] = value.length.toByte()
        value.toByteArray(Charsets.ISO_8859_1).copyInto(out, 2)
        return out
    }

    private fun encodeDt(value: Any?): ByteArray {
        if (value !is String) throw TypeMismatchException("DATE_AND_TIME write expects yyyy-MM-ddTHH:mm:ss.SSS")
        val m = DT.matchEntire(value) ?: throw TypeMismatchException("DATE_AND_TIME write expects yyyy-MM-ddTHH:mm:ss.SSS")
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        val second = m.groupValues[6].toInt()
        val millis = m.groupValues[7].toInt()
        if (year !in 1990..2089) throw OutOfRangeException("DATE_AND_TIME year $year is outside 1990..2089")
        if (month !in 1..12 || hour !in 0..23 || minute !in 0..59 || second !in 0..59 || millis !in 0..999) {
            throw OutOfRangeException("DATE_AND_TIME fields are out of range")
        }
        val date = try {
            LocalDate.of(year, month, day)
        } catch (_: RuntimeException) {
            throw OutOfRangeException("DATE_AND_TIME day is out of range")
        }
        val yy = if (year >= 2000) year - 2000 else year - 1900
        val dow = when (date.dayOfWeek) {
            DayOfWeek.SUNDAY -> 1
            DayOfWeek.MONDAY -> 2
            DayOfWeek.TUESDAY -> 3
            DayOfWeek.WEDNESDAY -> 4
            DayOfWeek.THURSDAY -> 5
            DayOfWeek.FRIDAY -> 6
            DayOfWeek.SATURDAY -> 7
        }
        return byteArrayOf(
            bcd2(yy), bcd2(month), bcd2(day), bcd2(hour), bcd2(minute), bcd2(second),
            bcd2(millis / 10), ((millis % 10) shl 4 or dow).toByte(),
        )
    }

    private fun decodeDt(bytes: ByteArray): String {
        val yy = fromBcd2(bytes[0])
        val month = fromBcd2(bytes[1])
        val day = fromBcd2(bytes[2])
        val hour = fromBcd2(bytes[3])
        val minute = fromBcd2(bytes[4])
        val second = fromBcd2(bytes[5])
        val msHigh = fromBcd2(bytes[6])
        val msLow = (bytes[7].toInt() ushr 4) and 0xF
        if (msLow > 9) throw TypeMismatchException("DATE_AND_TIME milliseconds are not BCD")
        val year = if (yy >= 90) 1900 + yy else 2000 + yy
        val millis = msHigh * 10 + msLow
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03d".format(year, month, day, hour, minute, second, millis)
    }

    private fun bcd2(n: Int): Byte {
        require(n in 0..99)
        return (((n / 10) shl 4) or (n % 10)).toByte()
    }

    private fun fromBcd2(b: Byte): Int {
        val v = b.toInt() and 0xFF
        val tens = v ushr 4
        val ones = v and 0xF
        if (tens > 9 || ones > 9) throw TypeMismatchException("DATE_AND_TIME field is not BCD")
        return tens * 10 + ones
    }

    private fun realBits(value: Any?): Int {
        val f = when (value) {
            is Float -> value
            is Double -> value.toFloat()
            is Number -> value.toFloat()
            else -> throw TypeMismatchException("real write expects a number")
        }
        if (!f.isFinite()) throw OutOfRangeException("real is not finite")
        return f.toRawBits()
    }

    private fun lrealBits(value: Any?): Long {
        val d = when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Number -> value.toDouble()
            else -> throw TypeMismatchException("lreal write expects a number")
        }
        if (!d.isFinite()) throw OutOfRangeException("lreal is not finite")
        return d.toRawBits()
    }

    private fun integral(value: Any?, min: Long, max: Long): Long {
        val n = when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is Float -> {
                if (!value.isFinite() || value.toLong().toFloat() != value) throw TypeMismatchException("expected an integer")
                value.toLong()
            }
            is Double -> {
                if (!value.isFinite() || value.toLong().toDouble() != value) throw TypeMismatchException("expected an integer")
                value.toLong()
            }
            else -> throw TypeMismatchException("expected an integer")
        }
        if (n < min || n > max) throw OutOfRangeException("value $n is outside $min..$max")
        return n
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun put16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun put32(v: Long): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun put64(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr (56 - 8 * i)).toByte()
        return out
    }
}
