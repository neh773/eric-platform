package org.oip.logix

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * CIP explicit messages for Logix tags, from the public manual 1756-PM020
 * "Logix 5000 Controllers Data Access":
 * https://literature.rockwellautomation.com/idc/groups/literature/documents/pm/1756-pm020_-en-p.pdf
 *
 * Symbolic segments are the ANSI extended symbol (0x91). An odd-length name is padded so the
 * next segment starts on a 16-bit boundary. Read Tag is service 0x4C, Write Tag is 0x4D, the
 * fragmented forms are 0x52 and 0x53, and Multiple Service Packet is 0x0A aimed at the Message
 * Router (class 0x02, instance 1). Offsets in that packet are from the start of the request data.
 *
 * Atomic tag-type values are the manual's table, extended to 16 bits the way the manual describes
 * CIP appendix C. LREAL is 0x00CB by that same extension (the table in the edition cited lists
 * through LINT and REAL). A structure's tag type on the wire is `A0 02` plus the template
 * structure handle.
 *
 * Logix date and time types use that same 16-bit CIP extension. Rockwell stores DT and TIME as
 * microseconds, LDT and LTIME as nanoseconds, and TIME32 as microseconds
 * (doc source: FEATURES DEV-010).
 * The codes are the CIP elementary codes those Logix names are carried as: UTIME 0xC0 (DT),
 * STIME 0xCC (LDT), FTIME 0xD6 (TIME32), LTIME 0xD7 (TIME), NTIME 0xDF (LTIME). The cited
 * 1756-PM020 edition does not list these five codes; the assignment is that CIP extension.
 *
 * OPC values follow the parentheticals on the the reference product Logix driver page
 * (doc source: FEATURES DEV-010):
 * DT is a Date/Time, LDT is a Date/Time with nanoseconds, LTIME is a nanosecond count,
 * TIME is a decimal number of seconds, and TIME32 is a microsecond count.
 */
internal object Cip {
    const val DT = 0x00C0
    const val BOOL = 0x00C1
    const val SINT = 0x00C2
    const val INT = 0x00C3
    const val DINT = 0x00C4
    const val LINT = 0x00C5
    const val REAL = 0x00CA
    const val LREAL = 0x00CB
    const val LDT = 0x00CC
    const val DWORD = 0x00D3
    const val TIME32 = 0x00D6
    const val TIME = 0x00D7
    const val LTIME = 0x00DF

    // Micro800 elementary types (doc source: FEATURES DEV-017). These are the CIP elementary
    // codes for the names the Micro800 page lists. The page gives the OPC value forms (DATE in
    // milliseconds since the Unix epoch, TIME as a millisecond duration) but not the wire widths;
    // DATE as 32-bit seconds and TIME as 32-bit milliseconds is this driver's reading, checked
    // against the in-repo simulator only (DIFFERENCES 751).
    const val USINT = 0x00C6
    const val UINT = 0x00C7
    const val UDINT = 0x00C8
    const val ULINT = 0x00C9
    const val DATE = 0x00CD
    const val BYTE = 0x00D1
    const val WORD = 0x00D2
    const val LWORD = 0x00D4
    const val SHORT_STRING = 0x00DA
    const val MTIME = 0x00DB

    /** Longest Micro800 STRING, from the Micro800 page (doc source: FEATURES DEV-017). */
    const val SHORT_STRING_MAX = 80

    const val READ = 0x4C
    const val WRITE = 0x4D
    const val READ_FRAG = 0x52
    const val WRITE_FRAG = 0x53
    const val MSP = 0x0A
    const val GET_ALL = 0x01
    const val GET_LIST = 0x03
    const val GET_ATTR = 0x0E
    const val GET_INSTANCES = 0x55
    const val FORWARD_OPEN = 0x54
    const val FORWARD_LARGE = 0x5B
    const val FORWARD_CLOSE = 0x4E
    const val UNCONNECTED_SEND = 0x52

    const val SYMBOL = 0x6B
    const val TEMPLATE = 0x6C
    const val PROGRAM = 0x1068
    const val STRUCT_MARK = 0x02A0

    const val REGISTER = 0x0065
    const val UNREGISTER = 0x0066
    const val SEND_RR = 0x006F
    const val SEND_UNIT = 0x0070

    fun atomicSize(type: Int): Int = when (type and 0x00FF) {
        0xC1, 0xC2, 0xC6, 0xD1 -> 1
        0xC3, 0xC7, 0xD2 -> 2
        0xC4, 0xC8, 0xCA, 0xCD, 0xD3, 0xD6, 0xDB -> 4
        0xC0, 0xC5, 0xC9, 0xCB, 0xCC, 0xD4, 0xD7, 0xDF -> 8
        else -> -1
    }

    /** Integer types whose bits a `Tag.n` address can name. */
    fun integerType(type: Int): Boolean = when (type and 0xFF) {
        0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xD1, 0xD2, 0xD3, 0xD4 -> true
        else -> false
    }

    fun symbol(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        require(bytes.size in 1..255) { "symbol length ${bytes.size}" }
        val pad = if (bytes.size % 2 == 0) 0 else 1
        val out = ByteArray(2 + bytes.size + pad)
        out[0] = 0x91.toByte()
        out[1] = bytes.size.toByte()
        bytes.copyInto(out, 2)
        return out
    }

    fun element(index: Int): ByteArray = when {
        index in 0..255 -> byteArrayOf(0x28, index.toByte())
        index in 0..65535 -> byteArrayOf(0x29, 0x00, (index and 0xFF).toByte(), ((index ushr 8) and 0xFF).toByte())
        else -> byteArrayOf(
            0x2A, 0x00,
            (index and 0xFF).toByte(),
            ((index ushr 8) and 0xFF).toByte(),
            ((index ushr 16) and 0xFF).toByte(),
            ((index ushr 24) and 0xFF).toByte(),
        )
    }

    /** Class and 16-bit instance, the form the symbol and template examples use. */
    fun classInstance(classId: Int, instance: Int): ByteArray {
        val out = ByteArray(6)
        out[0] = 0x20
        out[1] = classId.toByte()
        out[2] = 0x25
        out[3] = 0
        out[4] = (instance and 0xFF).toByte()
        out[5] = ((instance ushr 8) and 0xFF).toByte()
        return out
    }

    fun message(service: Int, path: ByteArray, data: ByteArray = ByteArray(0)): ByteArray {
        require(path.size % 2 == 0) { "path ${path.size} is not word aligned" }
        val out = ByteArray(2 + path.size + data.size)
        out[0] = service.toByte()
        out[1] = (path.size / 2).toByte()
        path.copyInto(out, 2)
        data.copyInto(out, 2 + path.size)
        return out
    }

    fun readTag(path: ByteArray, elements: Int): ByteArray = message(READ, path, u16(elements))

    fun readFragment(path: ByteArray, elements: Int, byteOffset: Int): ByteArray {
        val data = ByteArray(6)
        put16(data, 0, elements)
        put32(data, 2, byteOffset.toLong())
        return message(READ_FRAG, path, data)
    }

    fun writeTag(path: ByteArray, type: ByteArray, elements: Int, payload: ByteArray, byteOffset: Int? = null): ByteArray {
        val data = ByteArray(type.size + 2 + (if (byteOffset != null) 4 else 0) + payload.size)
        type.copyInto(data, 0)
        put16(data, type.size, elements)
        var p = type.size + 2
        if (byteOffset != null) {
            put32(data, p, byteOffset.toLong())
            p += 4
        }
        payload.copyInto(data, p)
        return message(if (byteOffset != null) WRITE_FRAG else WRITE, path, data)
    }

    fun atomicType(type: Int): ByteArray = byteArrayOf((type and 0xFF).toByte(), ((type ushr 8) and 0xFF).toByte())

    fun structType(handle: Int): ByteArray =
        byteArrayOf(0xA0.toByte(), 0x02, (handle and 0xFF).toByte(), ((handle ushr 8) and 0xFF).toByte())

    /**
     * Multiple Service Packet. Offsets are from the first byte of the request data, matching the
     * 1756-PM020 example (two reads, offsets 6 and 18).
     */
    fun multiple(services: List<ByteArray>): ByteArray {
        require(services.isNotEmpty())
        val count = services.size
        val table = 2 + 2 * count
        var cursor = table
        val offsets = IntArray(count)
        for (i in services.indices) {
            offsets[i] = cursor
            cursor += services[i].size
        }
        val data = ByteArray(cursor)
        put16(data, 0, count)
        for (i in offsets.indices) put16(data, 2 + 2 * i, offsets[i])
        var at = table
        for (svc in services) {
            svc.copyInto(data, at)
            at += svc.size
        }
        return message(MSP, byteArrayOf(0x20, 0x02, 0x24, 0x01), data)
    }

    fun multipleSize(services: List<ByteArray>): Int {
        val bodies = services.sumOf { it.size }
        return 2 + 4 + 2 + 2 * services.size + bodies
    }

    /** Pack CIP requests into as few Multiple Service Packets as [budget] allows. */
    fun pack(services: List<ByteArray>, budget: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val batch = ArrayList<ByteArray>()
        fun flush() {
            if (batch.isEmpty()) return
            out += if (batch.size == 1) batch[0] else multiple(batch)
            batch.clear()
        }
        for (svc in services) {
            if (batch.isEmpty()) {
                if (svc.size > budget) out += svc else batch += svc
                continue
            }
            val with = ArrayList(batch)
            with += svc
            if (multipleSize(with) > budget) {
                flush()
                if (svc.size > budget) out += svc else batch += svc
            } else {
                batch += svc
            }
        }
        flush()
        return out
    }

    fun parseReply(bytes: ByteArray): Reply {
        if (bytes.size < 4) throw IllegalArgumentException("short CIP reply")
        val extWords = bytes[3].toInt() and 0xFF
        val ext = IntArray(extWords) { u16(bytes, 4 + 2 * it) }
        val dataAt = 4 + 2 * extWords
        return Reply(
            service = bytes[0].toInt() and 0xFF,
            status = bytes[2].toInt() and 0xFF,
            extended = ext,
            data = if (dataAt > bytes.size) ByteArray(0) else bytes.copyOfRange(dataAt, bytes.size),
        )
    }

    fun parseMultiple(data: ByteArray): List<Reply> {
        if (data.size < 2) return emptyList()
        val count = u16(data, 0)
        if (data.size < 2 + 2 * count) throw IllegalArgumentException("short multiple reply")
        val offsets = IntArray(count) { u16(data, 2 + 2 * it) }
        return offsets.mapIndexed { i, off ->
            val end = if (i + 1 < count) offsets[i + 1] else data.size
            parseReply(data.copyOfRange(off, end))
        }
    }

    fun pathOf(address: ParsedAddress): ByteArray {
        val out = ByteArrayOutputStream()
        for (step in address.steps) {
            when (step) {
                is Step.Sym -> out.write(symbol(step.name))
                is Step.At -> step.indexes.forEach { out.write(element(it)) }
                is Step.Bit -> Unit
            }
        }
        return out.toByteArray()
    }

    /**
     * Connection path. A blank path is `1,<slot>` (out the backplane, into that slot): paths are
     * port/link pairs and port 1 is the backplane. A link is a number (a slot, a DH+ station or a
     * ControlNet node) or, after an Ethernet port, an IPv4 address carried as an extended link.
     * doc source: FEATURES DEV-009
     */
    fun route(connectionPath: String, slot: Int): ByteArray {
        val pairs = if (connectionPath.isBlank()) listOf("1" to slot.toString()) else parseConnectionPath(connectionPath)
        return routeOf(pairs)
    }

    fun routeOf(pairs: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((portText, link) in pairs) {
            val port = portText.toIntOrNull() ?: throw AddressException("port '$portText' is not a number")
            if (port !in 1..14) throw AddressException("port $port is outside 1..14")
            val asNumber = if (link.all { it.isDigit() }) link.toIntOrNull() else null
            if (asNumber != null) {
                if (asNumber !in 0..255) throw AddressException("link $link after port $port is outside 0..255")
                out.write(port)
                out.write(asNumber)
            } else {
                if (port == 1) throw AddressException("backplane link '$link' must be a slot number")
                if (!ipv4(link)) throw AddressException("link '$link' after port $port is neither a number nor an IPv4 address")
                val bytes = link.toByteArray(Charsets.US_ASCII)
                out.write(0x10 or port)
                out.write(bytes.size)
                out.write(bytes)
                if (bytes.size % 2 != 0) out.write(0)
            }
        }
        val route = out.toByteArray()
        // The Forward Open path size is one byte of 16-bit words and the route is followed by
        // the four-byte Message Router segment.
        if ((route.size + 4) / 2 > 255) throw AddressException("connection path is longer than a Forward Open can carry")
        return route
    }

    private fun ipv4(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255 }
    }

    /**
     * Unconnected Send (0x52) to the Connection Manager, carrying [message] along [route].
     * Message request, a pad byte when the request is odd, route path size in words, a reserved
     * byte, then the route path. doc source: FEATURES DEV-009
     */
    fun unconnectedSend(message: ByteArray, route: ByteArray, timeoutMs: Long): ByteArray {
        val body = Le()
        val tick = 5 // 32 ms per tick
        body.u8(tick)
        body.u8((timeoutMs / 32L).coerceIn(1L, 255L).toInt())
        body.u16(message.size)
        body.bytes(message)
        if (message.size % 2 != 0) body.u8(0)
        body.u8(route.size / 2)
        body.u8(0)
        body.bytes(route)
        return message(UNCONNECTED_SEND, byteArrayOf(0x20, 0x06, 0x24, 0x01), body.toByteArray())
    }

    fun encode(type: Int, value: Any?): ByteArray {
        val size = atomicSize(type)
        if (size < 0) throw TypeMismatchException("cannot encode type ${type.toString(16)}")
        val out = ByteArray(size)
        when (type and 0xFF) {
            0xC1 -> {
                val on = when (value) {
                    is Boolean -> value
                    is Number -> value.toLong() != 0L
                    else -> throw TypeMismatchException("BOOL value")
                }
                out[0] = if (on) 1 else 0
            }
            0xC2 -> {
                val n = number(value, "SINT")
                if (n !in -128..127) throw OutOfRangeException("SINT $n")
                out[0] = n.toByte()
            }
            0xC3 -> {
                val n = number(value, "INT")
                if (n !in -32768..32767) throw OutOfRangeException("INT $n")
                put16(out, 0, n)
            }
            0xC4, 0xD3 -> {
                val n = number(value, "DINT").toLong()
                if (type and 0xFF == 0xC4 && (value is Long) && (value < Int.MIN_VALUE || value > Int.MAX_VALUE)) {
                    throw OutOfRangeException("DINT")
                }
                put32(out, 0, n)
            }
            0xCA -> {
                val f = when (value) {
                    is Number -> value.toFloat()
                    else -> throw TypeMismatchException("REAL value")
                }
                put32(out, 0, java.lang.Float.floatToRawIntBits(f).toLong() and 0xFFFFFFFFL)
            }
            0xC5 -> {
                val n = when (value) {
                    is Number -> value.toLong()
                    else -> throw TypeMismatchException("LINT value")
                }
                put64(out, 0, n)
            }
            0xCB -> {
                val n = when (value) {
                    is Number -> value.toDouble()
                    else -> throw TypeMismatchException("LREAL value")
                }
                put64(out, 0, java.lang.Double.doubleToRawLongBits(n))
            }
            0xC6, 0xD1 -> {
                val n = wide(value, type)
                if (n !in 0L..0xFFL) throw OutOfRangeException("${typeName(type)} $n")
                out[0] = n.toByte()
            }
            0xC7, 0xD2 -> {
                val n = wide(value, type)
                if (n !in 0L..0xFFFFL) throw OutOfRangeException("${typeName(type)} $n")
                put16(out, 0, n.toInt())
            }
            0xC8 -> {
                val n = wide(value, type)
                if (n !in 0L..0xFFFFFFFFL) throw OutOfRangeException("UDINT $n")
                put32(out, 0, n)
            }
            0xC9 -> {
                val n = when (value) {
                    is java.math.BigInteger -> {
                        if (value.signum() < 0 || value.bitLength() > 64) throw OutOfRangeException("ULINT $value")
                        value.toLong()
                    }
                    else -> wide(value, type).also { if (it < 0) throw OutOfRangeException("ULINT $it") }
                }
                put64(out, 0, n)
            }
            0xD4 -> put64(out, 0, when (value) {
                is java.math.BigInteger -> {
                    if (value.signum() < 0 || value.bitLength() > 64) throw OutOfRangeException("LWORD $value")
                    value.toLong()
                }
                else -> wide(value, type)
            })
            0xCD -> {
                val millis = when (value) {
                    is Instant -> value.toEpochMilli()
                    is java.util.Date -> value.time
                    else -> wide(value, type)
                }
                val seconds = Math.floorDiv(millis, 1000L)
                if (seconds !in 0L..0xFFFFFFFFL) throw OutOfRangeException("DATE")
                put32(out, 0, seconds)
            }
            0xDB -> {
                val n = wide(value, type)
                if (n !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw OutOfRangeException("TIME")
                put32(out, 0, n)
            }
            0xC0 -> put64(out, 0, dateMicros(value))
            0xCC -> put64(out, 0, dateNanos(value))
            0xD6 -> {
                val n = number(value, "TIME32").toLong()
                if (n !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw OutOfRangeException("TIME32")
                put32(out, 0, n)
            }
            0xD7 -> put64(out, 0, decimalMicros(value))
            0xDF -> {
                val n = when (value) {
                    is Number -> value.toLong()
                    else -> throw TypeMismatchException("LTIME value")
                }
                put64(out, 0, n)
            }
        }
        return out
    }

    fun decode(type: Int, bytes: ByteArray, offset: Int): Any {
        if (offset < 0 || offset + atomicSize(type) > bytes.size) throw IllegalArgumentException("short value")
        return when (type and 0xFF) {
            0xC1 -> (bytes[offset].toInt() and 0xFF) != 0
            0xC2 -> bytes[offset].toInt()
            0xC3 -> {
                val v = u16(bytes, offset)
                if (v and 0x8000 != 0) v - 0x10000 else v
            }
            0xC4, 0xD3 -> i32(bytes, offset)
            0xC6, 0xD1 -> bytes[offset].toInt() and 0xFF
            0xC7, 0xD2 -> u16(bytes, offset)
            0xC8 -> u32(bytes, offset)
            0xC9, 0xD4 -> i64(bytes, offset)
            0xCD -> u32(bytes, offset) * 1000L
            0xDB -> i32(bytes, offset)
            0xCA -> java.lang.Float.intBitsToFloat(i32(bytes, offset))
            0xC5 -> i64(bytes, offset)
            0xCB -> java.lang.Double.longBitsToDouble(i64(bytes, offset))
            0xC0 -> fromMicros(i64(bytes, offset))
            0xCC -> fromNanos(i64(bytes, offset))
            0xD6 -> i32(bytes, offset)
            0xD7 -> BigDecimal.valueOf(i64(bytes, offset), 6)
            0xDF -> i64(bytes, offset)
            else -> bytes.copyOfRange(offset, offset + atomicSize(type))
        }
    }

    /**
     * DT is microseconds since 1970-01-01, returned as a Date/Time.
     * LDT is nanoseconds since the same epoch, returned as a Date/Time that keeps the nanoseconds.
     * The published ranges run from that epoch through the end of 2250-12-31 UTC.
     */
    private val DT_MAX: Instant = Instant.parse("2250-12-31T23:59:59.999999Z")
    private val LDT_MAX: Instant = Instant.parse("2250-12-31T23:59:59.999999999Z")

    private fun dateMicros(value: Any?): Long {
        val instant = instant(value, "DT")
        if (instant.isBefore(Instant.EPOCH) || instant.isAfter(DT_MAX)) throw OutOfRangeException("DT")
        return try {
            microsOf(instant)
        } catch (_: ArithmeticException) {
            throw OutOfRangeException("DT")
        }
    }

    private fun dateNanos(value: Any?): Long {
        val instant = instant(value, "LDT")
        if (instant.isBefore(Instant.EPOCH) || instant.isAfter(LDT_MAX)) throw OutOfRangeException("LDT")
        return try {
            Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000_000L), instant.nano.toLong())
        } catch (_: ArithmeticException) {
            throw OutOfRangeException("LDT")
        }
    }

    private fun instant(value: Any?, type: String): Instant = when (value) {
        is Instant -> value
        is java.util.Date -> value.toInstant()
        else -> throw TypeMismatchException("$type value")
    }

    private fun microsOf(instant: Instant): Long {
        val whole = Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000L), instant.nano / 1000L)
        return if (instant.nano % 1000 >= 500) Math.addExact(whole, 1) else whole
    }

    private fun fromMicros(micros: Long): Instant {
        val secs = Math.floorDiv(micros, 1_000_000L)
        val us = Math.floorMod(micros, 1_000_000L)
        return Instant.ofEpochSecond(secs, us * 1_000L)
    }

    private fun fromNanos(nanos: Long): Instant {
        val secs = Math.floorDiv(nanos, 1_000_000_000L)
        val ns = Math.floorMod(nanos, 1_000_000_000L)
        return Instant.ofEpochSecond(secs, ns)
    }

    /** TIME (Decimal) is seconds. The wire value is the Rockwell microsecond count. */
    private fun decimalMicros(value: Any?): Long {
        val seconds = when (value) {
            is BigDecimal -> value
            is Double -> BigDecimal.valueOf(value)
            is Float -> BigDecimal.valueOf(value.toDouble())
            else -> throw TypeMismatchException("TIME value")
        }
        return try {
            seconds.multiply(BigDecimal.valueOf(1_000_000L)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: ArithmeticException) {
            throw OutOfRangeException("TIME")
        }
    }

    fun decodeString(bytes: ByteArray): String {
        if (bytes.size < 4) return ""
        val len = i32(bytes, 0).coerceIn(0, (bytes.size - 4).coerceAtLeast(0))
        return bytes.copyOfRange(4, 4 + len).toString(Charsets.US_ASCII)
    }

    fun encodeString(text: String, capacity: Int, size: Int): ByteArray {
        val raw = text.toByteArray(Charsets.US_ASCII)
        if (raw.size > capacity) throw OutOfRangeException("string length ${raw.size} exceeds $capacity")
        val out = ByteArray(size)
        put32(out, 0, raw.size.toLong())
        raw.copyInto(out, 4)
        return out
    }

    /**
     * Micro800 STRING: a one-byte length and up to [SHORT_STRING_MAX] characters (the CIP
     * SHORT_STRING form). doc source: FEATURES DEV-017
     */
    fun decodeShortString(bytes: ByteArray, offset: Int): String {
        if (offset >= bytes.size) return ""
        val len = (bytes[offset].toInt() and 0xFF).coerceAtMost(bytes.size - offset - 1)
        return bytes.copyOfRange(offset + 1, offset + 1 + len).toString(Charsets.UTF_8)
    }

    fun encodeShortString(text: String): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size > SHORT_STRING_MAX) throw OutOfRangeException("string length ${raw.size} exceeds $SHORT_STRING_MAX")
        val out = ByteArray(1 + raw.size)
        out[0] = raw.size.toByte()
        raw.copyInto(out, 1)
        return out
    }

    private fun typeName(type: Int): String = when (type and 0xFF) {
        0xC6 -> "USINT"
        0xC7 -> "UINT"
        0xD1 -> "BYTE"
        0xD2 -> "WORD"
        else -> "0x${type.toString(16)}"
    }

    private fun wide(value: Any?, type: Int): Long = when (value) {
        is java.math.BigInteger -> {
            if (value.bitLength() > 63) throw OutOfRangeException(typeName(type))
            value.toLong()
        }
        is Double, is Float -> {
            val d = (value as Number).toDouble()
            if (d != Math.floor(d) || d.isInfinite()) throw TypeMismatchException("${typeName(type)} value $d is not whole")
            d.toLong()
        }
        is Number -> value.toLong()
        else -> throw TypeMismatchException("${typeName(type)} value")
    }

    private fun number(value: Any?, type: String): Int = when (value) {
        is Number -> {
            val asLong = value.toLong()
            if (asLong > Int.MAX_VALUE || asLong < Int.MIN_VALUE) throw OutOfRangeException(type)
            asLong.toInt()
        }
        else -> throw TypeMismatchException("$type value")
    }
}

internal class CipStatusException(val reply: Reply) : java.io.IOException(
    "CIP 0x${reply.status.toString(16)} ext 0x${reply.extendedStatus.toString(16)}",
)

internal data class Reply(val service: Int, val status: Int, val extended: IntArray, val data: ByteArray) {
    val extendedStatus: Int get() = extended.firstOrNull() ?: 0
}

internal sealed interface Step {
    data class Sym(val name: String) : Step
    data class At(val indexes: List<Int>) : Step
    data class Bit(val bit: Int) : Step
}

internal data class ParsedAddress(val raw: String, val steps: List<Step>) {
    val bit: Int? get() = (steps.lastOrNull() as? Step.Bit)?.bit
    fun withoutBit(): ParsedAddress =
        if (bit == null) this else ParsedAddress(raw, steps.dropLast(1))
}

/**
 * `Tag`, `Tag[1]`, `Tag[1,2]`, `UDT.Member`, `Program:Main.Tag`, `Dint.3`, `Flags[0].31`.
 * A dotted integer is a bit. Logix names do not start with a digit.
 */
internal fun parseAddress(raw: String): ParsedAddress {
    val s = raw.trim()
    if (s.isEmpty()) throw AddressException("empty address")
    val steps = ArrayList<Step>()
    var i = 0
    fun name(): String {
        val start = i
        if (s.startsWith("Program:", i)) {
            i += "Program:".length
            val n0 = i
            while (i < s.length && s[i] != '.' && s[i] != '[') {
                val c = s[i]
                if (!(c.isLetterOrDigit() || c == '_')) throw AddressException("bad program name in '$raw'")
                i++
            }
            if (i == n0) throw AddressException("missing program name in '$raw'")
            return s.substring(start, i)
        }
        if (i >= s.length || !(s[i].isLetter() || s[i] == '_')) throw AddressException("bad address '$raw'")
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        val text = s.substring(start, i)
        if (text.length > 40) throw AddressException("name longer than 40 in '$raw'")
        return text
    }
    steps += Step.Sym(name())
    while (i < s.length) {
        when (s[i]) {
            '[' -> {
                i++
                val idx = ArrayList<Int>()
                while (true) {
                    if (i >= s.length || !s[i].isDigit()) throw AddressException("bad index in '$raw'")
                    val n0 = i
                    while (i < s.length && s[i].isDigit()) i++
                    val n = s.substring(n0, i).toIntOrNull() ?: throw AddressException("bad index in '$raw'")
                    idx += n
                    when {
                        i < s.length && s[i] == ',' -> i++
                        i < s.length && s[i] == ']' -> {
                            i++
                            break
                        }
                        else -> throw AddressException("bad index in '$raw'")
                    }
                }
                if (idx.size !in 1..3) throw AddressException("bad index in '$raw'")
                steps += Step.At(idx)
            }
            '.' -> {
                i++
                if (i >= s.length) throw AddressException("trailing dot in '$raw'")
                if (s[i].isDigit()) {
                    val n0 = i
                    while (i < s.length && s[i].isDigit()) i++
                    if (i != s.length) throw AddressException("bad bit in '$raw'")
                    val bit = s.substring(n0, i).toIntOrNull() ?: throw AddressException("bad bit in '$raw'")
                    if (bit !in 0..63) throw AddressException("bit $bit in '$raw'")
                    steps += Step.Bit(bit)
                } else {
                    steps += Step.Sym(name())
                }
            }
            else -> throw AddressException("bad address '$raw'")
        }
    }
    return ParsedAddress(s, steps)
}

internal fun parseConnectionPath(text: String): List<Pair<String, String>> {
    val parts = text.split(',').map { it.trim() }
    if (parts.any { it.isEmpty() }) throw AddressException("connection path '$text' has an empty entry")
    if (parts.size < 2 || parts.size % 2 != 0) {
        throw AddressException("connection path '$text' must be an even list of pairs")
    }
    return parts.chunked(2).map { it[0] to it[1] }
}

internal fun qualityForCip(status: Int, extended: Int): QualityCode = when (status) {
    0x00 -> QualityCode.Good
    0x04, 0x26 -> QualityCode.Bad_ConfigurationError
    0x05 -> QualityCode.Bad_NotFound
    0x10 -> QualityCode.Bad_NotWritable
    0x13 -> QualityCode.Bad
    0xFF -> when (extended) {
        0x2105 -> QualityCode.Bad_OutOfRange
        0x2107 -> QualityCode.Bad_TypeMismatch
        else -> QualityCode.Bad_DeviceFailure
    }
    else -> QualityCode.Bad_DeviceFailure
}

internal class Le {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) { out.write(v and 0xFF) }
    fun u16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    fun u32(v: Long) {
        val x = v.toInt()
        out.write(x and 0xFF); out.write((x ushr 8) and 0xFF); out.write((x ushr 16) and 0xFF); out.write((x ushr 24) and 0xFF)
    }
    fun bytes(b: ByteArray) { out.write(b) }
    fun toByteArray() = out.toByteArray()
}

internal fun u16(b: ByteArray, o: Int = 0) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
internal fun u32(b: ByteArray, o: Int): Long =
    (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
internal fun i32(b: ByteArray, o: Int) = u32(b, o).toInt()
internal fun i64(b: ByteArray, o: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
    return v
}
internal fun u16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
internal fun put16(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xFF).toByte()
    b[o + 1] = ((v ushr 8) and 0xFF).toByte()
}
internal fun put32(b: ByteArray, o: Int, v: Long) {
    val x = v.toInt()
    b[o] = (x and 0xFF).toByte()
    b[o + 1] = ((x ushr 8) and 0xFF).toByte()
    b[o + 2] = ((x ushr 16) and 0xFF).toByte()
    b[o + 3] = ((x ushr 24) and 0xFF).toByte()
}
internal fun put64(b: ByteArray, o: Int, v: Long) {
    var x = v
    for (i in 0 until 8) {
        b[o + i] = (x and 0xFF).toByte()
        x = x ushr 8
    }
}
