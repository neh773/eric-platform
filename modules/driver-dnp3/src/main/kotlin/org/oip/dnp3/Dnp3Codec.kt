package org.oip.dnp3

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * DNP3 encoding, written from the public description of the protocol (IEEE 1815): the FT3 link frame with
 * its CRC-16 every 16 octets, the one-octet transport header, and the application layer's fragments,
 * object headers and the data objects a master reads and commands.
 * doc source: FEATURES DEV-013, DEV-014
 */
object Dnp3Crc {
    private val table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA6BC else c ushr 1 }
        c
    }

    /** CRC-16 with polynomial 0x3D65 (reflected 0xA6BC), initial 0, result complemented. */
    fun of(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var crc = 0
        for (i in from until to) crc = (crc ushr 8) xor table[(crc xor b[i].toInt()) and 0xFF]
        return crc.inv() and 0xFFFF
    }
}

class Dnp3DecodeException(message: String) : IOException(message)

/** One link layer frame. [control] holds DIR, PRM, FCB/DFC, FCV and the function code. */
class LinkFrame(val control: Int, val destination: Int, val source: Int, val data: ByteArray) {
    val dir: Boolean get() = control and 0x80 != 0
    val primary: Boolean get() = control and 0x40 != 0
    val function: Int get() = control and 0x0F
}

object Dnp3Link {
    const val START1 = 0x05
    const val START2 = 0x64
    const val MAX_USER_DATA = 250

    // Primary (PRM = 1) function codes.
    const val RESET_LINK_STATES = 0
    const val TEST_LINK_STATES = 2
    const val CONFIRMED_USER_DATA = 3
    const val UNCONFIRMED_USER_DATA = 4
    const val REQUEST_LINK_STATUS = 9

    // Secondary (PRM = 0) function codes.
    const val ACK = 0
    const val NACK = 1
    const val LINK_STATUS = 11
    const val NOT_SUPPORTED = 15

    fun control(dir: Boolean, primary: Boolean, function: Int, fcb: Boolean = false, fcv: Boolean = false): Int =
        (if (dir) 0x80 else 0) or (if (primary) 0x40 else 0) or (if (fcb) 0x20 else 0) or (if (fcv) 0x10 else 0) or (function and 0x0F)

    fun encode(control: Int, destination: Int, source: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= MAX_USER_DATA) { "link user data of ${data.size} octets exceeds $MAX_USER_DATA" }
        val out = ByteArrayOutputStream(10 + data.size + 2 * ((data.size + 15) / 16))
        val header = byteArrayOf(
            START1.toByte(), START2.toByte(), (5 + data.size).toByte(), control.toByte(),
            destination.toByte(), (destination ushr 8).toByte(), source.toByte(), (source ushr 8).toByte(),
        )
        out.write(header)
        crc(out, header, 0, 8)
        var i = 0
        while (i < data.size) {
            val n = minOf(16, data.size - i)
            out.write(data, i, n)
            crc(out, data, i, i + n)
            i += n
        }
        return out.toByteArray()
    }

    private fun crc(out: ByteArrayOutputStream, b: ByteArray, from: Int, to: Int) {
        val c = Dnp3Crc.of(b, from, to)
        out.write(c and 0xFF); out.write(c ushr 8)
    }

    /** Read one frame. Bytes before a start sequence are skipped; a CRC error throws. */
    fun read(input: InputStream): LinkFrame {
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("connection closed")
            if (prev == START1 && b == START2) break
            prev = b
        }
        val header = ByteArray(10)
        header[0] = START1.toByte(); header[1] = START2.toByte()
        readFully(input, header, 2, 8)
        if (Dnp3Crc.of(header, 0, 8) != u16(header, 8)) throw Dnp3DecodeException("link header CRC error")
        val length = header[2].toInt() and 0xFF
        if (length < 5) throw Dnp3DecodeException("link length $length is below 5")
        val userLength = length - 5
        val data = ByteArray(userLength)
        var i = 0
        val block = ByteArray(18)
        while (i < userLength) {
            val n = minOf(16, userLength - i)
            readFully(input, block, 0, n + 2)
            if (Dnp3Crc.of(block, 0, n) != u16(block, n)) throw Dnp3DecodeException("link data CRC error")
            System.arraycopy(block, 0, data, i, n)
            i += n
        }
        return LinkFrame(header[3].toInt() and 0xFF, u16(header, 4), u16(header, 6), data)
    }

    /** Decode one whole frame held in [b]. */
    fun decode(b: ByteArray): LinkFrame = read(b.inputStream())

    private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int) {
        var n = 0
        while (n < len) {
            val r = input.read(b, off + n, len - n)
            if (r < 0) throw EOFException("connection closed mid-frame")
            n += r
        }
    }

    internal fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}

/** Transport function: splits a fragment into segments and puts segments back together. */
object Dnp3Transport {
    const val MAX_SEGMENT_DATA = Dnp3Link.MAX_USER_DATA - 1

    fun header(fin: Boolean, fir: Boolean, seq: Int): Int = (if (fin) 0x80 else 0) or (if (fir) 0x40 else 0) or (seq and 0x3F)

    /** Segments of [fragment], numbered from [firstSeq]. */
    fun segment(fragment: ByteArray, firstSeq: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var seq = firstSeq
        do {
            val n = minOf(MAX_SEGMENT_DATA, fragment.size - i)
            val seg = ByteArray(n + 1)
            seg[0] = header(fin = i + n >= fragment.size, fir = i == 0, seq = seq).toByte()
            System.arraycopy(fragment, i, seg, 1, n)
            out += seg
            i += n
            seq = (seq + 1) and 0x3F
        } while (i < fragment.size)
        return out
    }

    /** Reassembly of one direction's segments. */
    class Receiver {
        private var buffer: ByteArrayOutputStream? = null
        private var expect = 0

        /** Feed one segment; returns the fragment when this segment ends one. Out-of-order segments drop the partial fragment. */
        fun accept(segment: ByteArray): ByteArray? {
            if (segment.isEmpty()) return null
            val h = segment[0].toInt() and 0xFF
            val fin = h and 0x80 != 0
            val fir = h and 0x40 != 0
            val seq = h and 0x3F
            if (fir) {
                buffer = ByteArrayOutputStream()
            } else if (buffer == null || seq != expect) {
                buffer = null
                return null
            }
            buffer!!.write(segment, 1, segment.size - 1)
            expect = (seq + 1) and 0x3F
            if (!fin) return null
            val done = buffer!!.toByteArray()
            buffer = null
            return done
        }
    }
}

/** Application layer function codes, IIN bits and object helpers. */
object Dnp3App {
    const val CONFIRM = 0
    const val READ = 1
    const val WRITE = 2
    const val SELECT = 3
    const val OPERATE = 4
    const val DIRECT_OPERATE = 5
    const val DIRECT_OPERATE_NR = 6
    const val COLD_RESTART = 13
    const val ENABLE_UNSOLICITED = 20
    const val DISABLE_UNSOLICITED = 21
    const val DELAY_MEASURE = 23
    const val RECORD_CURRENT_TIME = 24
    const val RESPONSE = 129
    const val UNSOLICITED_RESPONSE = 130

    // IIN1 (low byte of [Iin.value]) and IIN2 (high byte).
    const val IIN_BROADCAST = 0x0001
    const val IIN_CLASS1 = 0x0002
    const val IIN_CLASS2 = 0x0004
    const val IIN_CLASS3 = 0x0008
    const val IIN_NEED_TIME = 0x0010
    const val IIN_LOCAL_CONTROL = 0x0020
    const val IIN_DEVICE_TROUBLE = 0x0040
    const val IIN_DEVICE_RESTART = 0x0080
    const val IIN_NO_FUNC_SUPPORT = 0x0100
    const val IIN_OBJECT_UNKNOWN = 0x0200
    const val IIN_PARAMETER_ERROR = 0x0400
    const val IIN_EVENT_OVERFLOW = 0x0800
    const val IIN_ALREADY_EXECUTING = 0x1000
    const val IIN_CONFIG_CORRUPT = 0x2000

    fun control(fir: Boolean, fin: Boolean, con: Boolean, uns: Boolean, seq: Int): Int =
        (if (fir) 0x80 else 0) or (if (fin) 0x40 else 0) or (if (con) 0x20 else 0) or (if (uns) 0x10 else 0) or (seq and 0x0F)

    fun request(seq: Int, function: Int, objects: ByteArray = ByteArray(0), uns: Boolean = false): ByteArray =
        byteArrayOf(control(fir = true, fin = true, con = false, uns = uns, seq = seq).toByte(), function.toByte()) + objects

    fun confirm(seq: Int, uns: Boolean): ByteArray = request(seq, CONFIRM, uns = uns)

    /** Header of class reads: group 60 variation 1..4, all objects (qualifier 0x06). */
    fun classHeader(dnpClass: Int): ByteArray {
        require(dnpClass in 0..3) { "class $dnpClass is outside 0..3" }
        return byteArrayOf(60, (dnpClass + 1).toByte(), 0x06)
    }

    /** READ of the classes in [classes], in that order. Class 0 alone is the static data. */
    fun classRead(classes: List<Int>): ByteArray = classes.fold(ByteArray(0)) { acc, c -> acc + classHeader(c) }

    /** WRITE of IIN1.7 (device restart) to 0: group 80 variation 1, start-stop 7..7, one packed bit. */
    fun clearRestart(): ByteArray = byteArrayOf(80, 1, 0x00, 7, 7, 0)

    /** Group 50 variation [variation] (1 absolute time, 3 last recorded time), count 1. */
    fun timeObject(variation: Int, epochMillis: Long): ByteArray = byteArrayOf(50, variation.toByte(), 0x07, 1) + time48(epochMillis)

    fun time48(epochMillis: Long): ByteArray = ByteArray(6) { i -> (epochMillis ushr (8 * i)).toByte() }

    fun u48(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 5 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /** One control relay output block (group 12 variation 1) at [index], qualifier 0x28 (2-octet count and index). */
    fun crob(index: Int, crob: Crob): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(12, 1, 0x28, 1, 0)); u16(out, index)
        out.write(crob.code); out.write(crob.count)
        u32(out, crob.onTimeMs); u32(out, crob.offTimeMs)
        out.write(crob.status)
        return out.toByteArray()
    }

    /** One analog output block (group 41, [variation] 1..4) at [index], qualifier 0x28. */
    fun analogOutput(index: Int, variation: Int, value: Number, status: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(41, variation.toByte(), 0x28, 1, 0)); u16(out, index)
        when (variation) {
            1 -> u32(out, value.toLong())
            2 -> u16(out, value.toInt())
            3 -> u32(out, java.lang.Float.floatToIntBits(value.toFloat()).toLong())
            4 -> { val bits = java.lang.Double.doubleToLongBits(value.toDouble()); for (i in 0 until 8) out.write((bits ushr (8 * i)).toInt()) }
            else -> throw IllegalArgumentException("analog output variation $variation is not 1..4")
        }
        out.write(status)
        return out.toByteArray()
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) { out.write(v); out.write(v ushr 8) }
    private fun u32(out: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) out.write((v ushr (8 * i)).toInt()) }
}

/** A control relay output block. [code] holds the trip/close bits, clear and queue flags, and the operation type. */
data class Crob(val code: Int, val count: Int = 1, val onTimeMs: Long = 0, val offTimeMs: Long = 0, val status: Int = 0) {
    companion object {
        const val NUL = 0x00
        const val PULSE_ON = 0x01
        const val PULSE_OFF = 0x02
        const val LATCH_ON = 0x03
        const val LATCH_OFF = 0x04
        const val CLOSE = 0x40
        const val TRIP = 0x80

        /** Operation names a script may pass: LATCH_ON, LATCH_OFF, PULSE_ON, PULSE_OFF, CLOSE (pulse close), TRIP (pulse trip). */
        fun code(name: String): Int = when (name.trim().uppercase().replace('-', '_')) {
            "NUL" -> NUL
            "PULSE_ON" -> PULSE_ON
            "PULSE_OFF" -> PULSE_OFF
            "LATCH_ON" -> LATCH_ON
            "LATCH_OFF" -> LATCH_OFF
            "CLOSE", "CLOSE_PULSE_ON" -> CLOSE or PULSE_ON
            "TRIP", "TRIP_PULSE_ON" -> TRIP or PULSE_ON
            else -> throw IllegalArgumentException("unknown control operation '$name'")
        }
    }
}

/** Control status codes a command response carries in each echoed object. */
object CommandStatus {
    private val names = mapOf(
        0 to "SUCCESS", 1 to "TIMEOUT", 2 to "NO_SELECT", 3 to "FORMAT_ERROR", 4 to "NOT_SUPPORTED",
        5 to "ALREADY_ACTIVE", 6 to "HARDWARE_ERROR", 7 to "LOCAL", 8 to "TOO_MANY_OBJS", 9 to "NOT_AUTHORIZED",
        10 to "AUTOMATION_INHIBIT", 11 to "PROCESSING_LIMITED", 12 to "OUT_OF_RANGE", 126 to "NON_PARTICIPATING", 127 to "UNDEFINED",
    )
    fun name(code: Int): String = names[code and 0x7F] ?: "STATUS_$code"
}

data class Iin(val value: Int) {
    fun has(bit: Int): Boolean = value and bit != 0
    override fun toString(): String = "IIN %02X %02X".format(value and 0xFF, value ushr 8)
}

/** A parsed application fragment from the outstation. */
class Fragment(val control: Int, val function: Int, val iin: Iin, val objects: ByteArray) {
    val fir: Boolean get() = control and 0x80 != 0
    val fin: Boolean get() = control and 0x40 != 0
    val con: Boolean get() = control and 0x20 != 0
    val uns: Boolean get() = control and 0x10 != 0
    val seq: Int get() = control and 0x0F

    companion object {
        fun parse(b: ByteArray): Fragment {
            if (b.size < 2) throw Dnp3DecodeException("application fragment of ${b.size} octets")
            val function = b[1].toInt() and 0xFF
            if (function == Dnp3App.RESPONSE || function == Dnp3App.UNSOLICITED_RESPONSE) {
                if (b.size < 4) throw Dnp3DecodeException("response without IIN")
                return Fragment(b[0].toInt() and 0xFF, function, Iin(Dnp3Link.u16(b, 2)), b.copyOfRange(4, b.size))
            }
            return Fragment(b[0].toInt() and 0xFF, function, Iin(0), b.copyOfRange(2, b.size))
        }
    }
}

/** Point types a master reads; [static] and [event] are the DNP3 object groups. */
enum class PointType(val prefix: String, val static: Int, val event: Int, val display: String) {
    BINARY_INPUT("bi", 1, 2, "Binary Inputs"),
    BINARY_OUTPUT("bo", 10, 11, "Binary Output Status"),
    COUNTER("ci", 20, 22, "Counters"),
    ANALOG_INPUT("ai", 30, 32, "Analog Inputs"),
    ANALOG_OUTPUT("ao", 40, 42, "Analog Output Status");

    companion object {
        fun byPrefix(text: String): PointType? = entries.firstOrNull { it.prefix == text.lowercase() }
        fun byGroup(group: Int): PointType? = entries.firstOrNull { it.static == group || it.event == group }
    }
}

/** One value of one point from a response. [timeMs] is the event's own time, when the object carries one. */
data class PointUpdate(val type: PointType, val index: Int, val value: Any?, val flags: Int, val timeMs: Long?, val event: Boolean)

/** A control or analog output echo from a command response. */
data class CommandEcho(val group: Int, val variation: Int, val index: Int, val status: Int)

/** Everything a response's objects carried that the master uses. */
class ParsedObjects {
    val points = ArrayList<PointUpdate>()
    val commands = ArrayList<CommandEcho>()
    /** Group 52 time delay, in milliseconds. */
    var timeDelayMs: Long? = null
    /** Objects the parser could not size; parsing stops at the first. */
    var unknown: String? = null
}

/**
 * Object parser for responses. Qualifiers: start-stop (0x00, 0x01, 0x02), count (0x07, 0x08, 0x09) and
 * index-prefixed count (prefix codes 1..3). Parsing stops at an object whose size is not known.
 */
object Dnp3Objects {
    const val FLAG_ONLINE = 0x01
    const val FLAG_RESTART = 0x02
    const val FLAG_COMM_LOST = 0x04
    const val FLAG_REMOTE_FORCED = 0x08
    const val FLAG_LOCAL_FORCED = 0x10
    const val FLAG_OVER_RANGE = 0x20
    const val FLAG_REFERENCE_ERR = 0x40
    const val FLAG_STATE = 0x80

    fun parse(b: ByteArray): ParsedObjects {
        val out = ParsedObjects()
        val r = Reader(b)
        while (r.remaining > 0) {
            if (r.remaining < 3) throw Dnp3DecodeException("short object header")
            val group = r.u8()
            val variation = r.u8()
            val qualifier = r.u8()
            val prefixCode = (qualifier ushr 4) and 0x07
            val rangeCode = qualifier and 0x0F
            val indices: List<Int>?
            val count: Int
            when (rangeCode) {
                0, 1, 2 -> {
                    val start = r.uint(1 shl rangeCode).toInt()
                    val stop = r.uint(1 shl rangeCode).toInt()
                    if (stop < start) throw Dnp3DecodeException("range $start..$stop")
                    count = stop - start + 1
                    indices = (start..stop).toList()
                }
                7, 8, 9 -> { count = r.uint(1 shl (rangeCode - 7)).toInt(); indices = null }
                6 -> { count = 0; indices = emptyList() }
                else -> throw Dnp3DecodeException("qualifier 0x%02X is not supported".format(qualifier))
            }
            if (count > 65536) throw Dnp3DecodeException("object count $count")
            if (prefixCode > 3) throw Dnp3DecodeException("qualifier 0x%02X is not supported".format(qualifier))
            val prefixSize = if (prefixCode == 0) 0 else 1 shl (prefixCode - 1)
            // Packed bit objects: group 1 / 10 / 80 variation 1 carry one bit per point, no prefix.
            if (variation == 1 && (group == 1 || group == 10 || group == 80) && prefixSize == 0) {
                val bytes = r.bytes((count + 7) / 8)
                val type = PointType.byGroup(group)
                for (i in 0 until count) {
                    val bit = (bytes[i / 8].toInt() ushr (i % 8)) and 1 != 0
                    val index = indices?.get(i) ?: i
                    if (type != null) out.points += PointUpdate(type, index, bit, FLAG_ONLINE, null, event = false)
                }
                continue
            }
            val size = size(group, variation)
            if (size == null) {
                out.unknown = "group $group variation $variation"
                return out
            }
            for (i in 0 until count) {
                val index = if (prefixSize > 0) r.uint(prefixSize).toInt() else indices?.get(i) ?: i
                val o = r.bytes(size)
                decode(group, variation, index, o, out)
            }
        }
        return out
    }

    /** Octets of one object of [group] / [variation], or null when this parser does not know it. */
    fun size(group: Int, variation: Int): Int? = when (group) {
        1 -> if (variation == 2) 1 else null
        2 -> when (variation) { 1 -> 1; 2 -> 7; 3 -> 3; else -> null }
        10 -> if (variation == 2) 1 else null
        11 -> when (variation) { 1 -> 1; 2 -> 7; else -> null }
        12 -> if (variation == 1) 11 else null
        20 -> when (variation) { 1 -> 5; 2 -> 3; 5 -> 4; 6 -> 2; else -> null }
        22 -> when (variation) { 1 -> 5; 2 -> 3; 5 -> 11; 6 -> 9; else -> null }
        30 -> when (variation) { 1 -> 5; 2 -> 3; 3 -> 4; 4 -> 2; 5 -> 5; 6 -> 9; else -> null }
        32, 42 -> when (variation) { 1 -> 5; 2 -> 3; 3 -> 11; 4 -> 9; 5 -> 5; 6 -> 9; 7 -> 11; 8 -> 15; else -> null }
        40 -> when (variation) { 1 -> 5; 2 -> 3; 3 -> 5; 4 -> 9; else -> null }
        41 -> when (variation) { 1 -> 5; 2 -> 3; 3 -> 5; 4 -> 9; else -> null }
        50 -> when (variation) { 1, 2, 3 -> 6; 4 -> 11; else -> null }
        51 -> if (variation in 1..2) 6 else null
        52 -> if (variation in 1..2) 2 else null
        else -> null
    }

    private fun decode(group: Int, variation: Int, index: Int, o: ByteArray, out: ParsedObjects) {
        fun flags() = o[0].toInt() and 0xFF
        fun i16(at: Int) = ((o[at].toInt() and 0xFF) or (o[at + 1].toInt() shl 8)).toLong()
        fun u16(at: Int) = ((o[at].toInt() and 0xFF) or ((o[at + 1].toInt() and 0xFF) shl 8)).toLong()
        fun u32(at: Int): Long { var v = 0L; for (k in 3 downTo 0) v = (v shl 8) or (o[at + k].toLong() and 0xFF); return v }
        fun i32(at: Int): Long = u32(at).toInt().toLong()
        fun f32(at: Int): Double = java.lang.Float.intBitsToFloat(u32(at).toInt()).toDouble()
        fun f64(at: Int): Double { var v = 0L; for (k in 7 downTo 0) v = (v shl 8) or (o[at + k].toLong() and 0xFF); return java.lang.Double.longBitsToDouble(v) }
        fun t48(at: Int): Long = Dnp3App.u48(o, at)
        val online = FLAG_ONLINE
        when (group) {
            1, 10 -> out.points += PointUpdate(PointType.byGroup(group)!!, index, flags() and FLAG_STATE != 0, flags(), null, event = false)
            2, 11 -> out.points += PointUpdate(PointType.byGroup(group)!!, index, flags() and FLAG_STATE != 0, flags(),
                if (variation == 2) t48(1) else null, event = true)
            12 -> out.commands += CommandEcho(group, variation, index, o[10].toInt() and 0xFF)
            20 -> out.points += when (variation) {
                1 -> PointUpdate(PointType.COUNTER, index, u32(1), flags(), null, false)
                2 -> PointUpdate(PointType.COUNTER, index, u16(1), flags(), null, false)
                5 -> PointUpdate(PointType.COUNTER, index, u32(0), online, null, false)
                else -> PointUpdate(PointType.COUNTER, index, u16(0), online, null, false)
            }
            22 -> out.points += when (variation) {
                1 -> PointUpdate(PointType.COUNTER, index, u32(1), flags(), null, true)
                2 -> PointUpdate(PointType.COUNTER, index, u16(1), flags(), null, true)
                5 -> PointUpdate(PointType.COUNTER, index, u32(1), flags(), t48(5), true)
                else -> PointUpdate(PointType.COUNTER, index, u16(1), flags(), t48(3), true)
            }
            30 -> out.points += when (variation) {
                1 -> PointUpdate(PointType.ANALOG_INPUT, index, i32(1), flags(), null, false)
                2 -> PointUpdate(PointType.ANALOG_INPUT, index, i16(1), flags(), null, false)
                3 -> PointUpdate(PointType.ANALOG_INPUT, index, i32(0), online, null, false)
                4 -> PointUpdate(PointType.ANALOG_INPUT, index, i16(0), online, null, false)
                5 -> PointUpdate(PointType.ANALOG_INPUT, index, f32(1), flags(), null, false)
                else -> PointUpdate(PointType.ANALOG_INPUT, index, f64(1), flags(), null, false)
            }
            32, 42 -> {
                val type = if (group == 32) PointType.ANALOG_INPUT else PointType.ANALOG_OUTPUT
                out.points += when (variation) {
                    1 -> PointUpdate(type, index, i32(1), flags(), null, true)
                    2 -> PointUpdate(type, index, i16(1), flags(), null, true)
                    3 -> PointUpdate(type, index, i32(1), flags(), t48(5), true)
                    4 -> PointUpdate(type, index, i16(1), flags(), t48(3), true)
                    5 -> PointUpdate(type, index, f32(1), flags(), null, true)
                    6 -> PointUpdate(type, index, f64(1), flags(), null, true)
                    7 -> PointUpdate(type, index, f32(1), flags(), t48(5), true)
                    else -> PointUpdate(type, index, f64(1), flags(), t48(9), true)
                }
            }
            40 -> out.points += when (variation) {
                1 -> PointUpdate(PointType.ANALOG_OUTPUT, index, i32(1), flags(), null, false)
                2 -> PointUpdate(PointType.ANALOG_OUTPUT, index, i16(1), flags(), null, false)
                3 -> PointUpdate(PointType.ANALOG_OUTPUT, index, f32(1), flags(), null, false)
                else -> PointUpdate(PointType.ANALOG_OUTPUT, index, f64(1), flags(), null, false)
            }
            41 -> out.commands += CommandEcho(group, variation, index, o[o.size - 1].toInt() and 0xFF)
            52 -> out.timeDelayMs = u16(0) * (if (variation == 1) 1000 else 1)
            else -> Unit // time objects in a response (group 50, 51) carry nothing the master keeps
        }
    }

    private class Reader(val b: ByteArray) {
        var p = 0
        val remaining: Int get() = b.size - p
        fun u8(): Int { need(1); return b[p++].toInt() and 0xFF }
        fun uint(n: Int): Long { need(n); var v = 0L; for (k in n - 1 downTo 0) v = (v shl 8) or (b[p + k].toLong() and 0xFF); p += n; return v }
        fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(p, p + n).also { p += n } }
        private fun need(n: Int) { if (p + n > b.size) throw Dnp3DecodeException("truncated object at $p") }
    }
}
