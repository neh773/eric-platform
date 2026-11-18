package org.oip.bacnet

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * BACnet/IP encoding written from the public protocol description (ASHRAE 135): BVLC framing from
 * Annex J, the network layer header (NPDU, clause 6), APDU headers (clause 20.1) and the tagged
 * encoding of service parameters (clause 20.2). Only what the driver needs is here.
 */
object BacnetObjectType {
    const val ANALOG_INPUT = 0
    const val ANALOG_OUTPUT = 1
    const val ANALOG_VALUE = 2
    const val BINARY_INPUT = 3
    const val BINARY_OUTPUT = 4
    const val BINARY_VALUE = 5
    const val DEVICE = 8
    const val MULTI_STATE_INPUT = 13
    const val MULTI_STATE_OUTPUT = 14
    const val MULTI_STATE_VALUE = 19

    private val names = mapOf(
        0 to "analog-input", 1 to "analog-output", 2 to "analog-value", 3 to "binary-input",
        4 to "binary-output", 5 to "binary-value", 6 to "calendar", 7 to "command", 8 to "device",
        9 to "event-enrollment", 10 to "file", 11 to "group", 12 to "loop", 13 to "multi-state-input",
        14 to "multi-state-output", 15 to "notification-class", 16 to "program", 17 to "schedule",
        18 to "averaging", 19 to "multi-state-value", 20 to "trend-log", 21 to "life-safety-point",
        22 to "life-safety-zone", 23 to "accumulator", 24 to "pulse-converter", 25 to "event-log",
        26 to "global-group", 27 to "trend-log-multiple", 28 to "load-control", 29 to "structured-view",
        30 to "access-door", 39 to "bitstring-value", 40 to "characterstring-value",
        45 to "integer-value", 46 to "large-analog-value", 48 to "positive-integer-value",
    )
    private val byName = names.entries.associate { it.value to it.key }

    fun name(type: Int): String = names[type] ?: "object-type-$type"

    fun parse(text: String): Int? {
        byName[text.lowercase()]?.let { return it }
        val raw = text.removePrefix("object-type-").toIntOrNull() ?: return null
        return raw.takeIf { it in 0..1023 }
    }

    fun isAnalog(type: Int) = type == ANALOG_INPUT || type == ANALOG_OUTPUT || type == ANALOG_VALUE
    fun isBinary(type: Int) = type == BINARY_INPUT || type == BINARY_OUTPUT || type == BINARY_VALUE
    fun isMultiState(type: Int) = type == MULTI_STATE_INPUT || type == MULTI_STATE_OUTPUT || type == MULTI_STATE_VALUE

    /** Outputs are always commanded through a priority array; values may be. Inputs never are. */
    fun isCommandable(type: Int) = type == ANALOG_OUTPUT || type == BINARY_OUTPUT || type == MULTI_STATE_OUTPUT ||
        type == ANALOG_VALUE || type == BINARY_VALUE || type == MULTI_STATE_VALUE
}

object BacnetProperty {
    const val APDU_TIMEOUT = 11
    const val DESCRIPTION = 28
    const val FIRMWARE_REVISION = 44
    const val MAX_APDU_LENGTH_ACCEPTED = 62
    const val MODEL_NAME = 70
    const val NUMBER_OF_APDU_RETRIES = 73
    const val OBJECT_IDENTIFIER = 75
    const val OBJECT_LIST = 76
    const val OBJECT_NAME = 77
    const val OBJECT_TYPE = 79
    const val OUT_OF_SERVICE = 81
    const val PRESENT_VALUE = 85
    const val PRIORITY_ARRAY = 87
    const val PROTOCOL_VERSION = 98
    const val RELIABILITY = 103
    const val RELINQUISH_DEFAULT = 104
    const val SEGMENTATION_SUPPORTED = 107
    const val STATUS_FLAGS = 111
    const val SYSTEM_STATUS = 112
    const val UNITS = 117
    const val VENDOR_IDENTIFIER = 120
    const val VENDOR_NAME = 121
    const val PROTOCOL_REVISION = 139
}

object BacnetService {
    // Confirmed
    const val READ_PROPERTY = 12
    const val READ_PROPERTY_MULTIPLE = 14
    const val WRITE_PROPERTY = 15
    // Unconfirmed
    const val I_AM = 0
    const val TIME_SYNCHRONIZATION = 6
    const val WHO_IS = 8
    const val UTC_TIME_SYNCHRONIZATION = 9
}

/** Abort reasons (clause 21, BACnetAbortReason). */
object AbortReason {
    const val OTHER = 0
    const val BUFFER_OVERFLOW = 1
    const val SEGMENTATION_NOT_SUPPORTED = 4
}

/** Reject reasons (clause 21, BACnetRejectReason). */
object RejectReason {
    const val OTHER = 0
    const val UNRECOGNIZED_SERVICE = 9
}

/** Error classes and the error codes the driver reacts to (clause 21, BACnetErrorClass/Code). */
object BacnetError {
    const val CLASS_DEVICE = 0
    const val CLASS_OBJECT = 1
    const val CLASS_PROPERTY = 2
    const val CLASS_SERVICES = 5
    const val CODE_OTHER = 0
    const val CODE_SERVICE_REQUEST_DENIED = 29
    const val CODE_UNKNOWN_OBJECT = 31
    const val CODE_UNKNOWN_PROPERTY = 32
    const val CODE_VALUE_OUT_OF_RANGE = 37
    const val CODE_WRITE_ACCESS_DENIED = 40
    const val CODE_INVALID_DATA_TYPE = 9
    const val CODE_INVALID_ARRAY_INDEX = 42
    const val CODE_PROPERTY_IS_NOT_AN_ARRAY = 50
}

data class ObjectId(val type: Int, val instance: Int) {
    init {
        require(type in 0..1023) { "object type $type is outside 0..1023" }
        require(instance in 0..MAX_INSTANCE) { "object instance $instance is outside 0..$MAX_INSTANCE" }
    }

    fun encoded(): Long = (type.toLong() shl 22) or instance.toLong()
    override fun toString(): String = "${BacnetObjectType.name(type)}:$instance"

    companion object {
        /** 4194303 is reserved as "no instance", so a configured device may use 0..4194302. */
        const val MAX_INSTANCE = 4_194_303
        fun decode(raw: Long): ObjectId = ObjectId(((raw ushr 22) and 0x3FF).toInt(), (raw and 0x3FFFFF).toInt())
    }
}

/** Application-tagged values the driver decodes. Unsigned and enumerated stay distinct so a write can echo them. */
data class BacnetEnumerated(val value: Long)
data class BacnetBitString(val bits: List<Boolean>) {
    operator fun get(i: Int): Boolean = bits.getOrElse(i) { false }
}
data class BacnetUnsigned(val value: Long)
object BacnetNull { override fun toString() = "null" }

/** Context-tagged data inside a constructed value that the driver does not interpret. */
data class ContextRaw(val tag: Int, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is ContextRaw && other.tag == tag && other.bytes.contentEquals(bytes)
    override fun hashCode() = tag * 31 + bytes.contentHashCode()
}

class BacnetDecodeException(message: String) : RuntimeException(message)

/** Tagged encoder (clause 20.2). */
class TagWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()
    val size: Int get() = out.size()

    fun raw(b: ByteArray) = out.write(b)
    fun byte(b: Int) = out.write(b and 0xFF)

    private fun header(number: Int, context: Boolean, length: Int) {
        val cls = if (context) 0x08 else 0
        val first = (if (number <= 14) number shl 4 else 0xF0) or cls
        val lvt = if (length <= 4) length else 5
        out.write(first or lvt)
        if (number > 14) out.write(number)
        if (length > 4) {
            when {
                length <= 253 -> out.write(length)
                length <= 65535 -> { out.write(254); out.write(length ushr 8); out.write(length) }
                else -> { out.write(255); for (s in intArrayOf(24, 16, 8, 0)) out.write(length ushr s) }
            }
        }
    }

    fun open(number: Int) { out.write((if (number <= 14) number shl 4 else 0xF0) or 0x0E); if (number > 14) out.write(number) }
    fun close(number: Int) { out.write((if (number <= 14) number shl 4 else 0xF0) or 0x0F); if (number > 14) out.write(number) }

    private fun unsignedBytes(v: Long): ByteArray {
        require(v >= 0 && v <= 0xFFFFFFFFL) { "unsigned value $v is outside 0..4294967295" }
        val n = when { v < 0x100 -> 1; v < 0x10000 -> 2; v < 0x1000000 -> 3; else -> 4 }
        return ByteArray(n) { i -> (v ushr (8 * (n - 1 - i))).toByte() }
    }

    private fun signedBytes(v: Long): ByteArray {
        require(v in Int.MIN_VALUE..Int.MAX_VALUE) { "signed value $v is outside the 32-bit range" }
        val n = when (v) { in -128..127 -> 1; in -32768..32767 -> 2; in -8388608..8388607 -> 3; else -> 4 }
        return ByteArray(n) { i -> (v shr (8 * (n - 1 - i))).toByte() }
    }

    fun appNull() = header(0, false, 0)
    fun appBoolean(v: Boolean) = out.write(0x10 or (if (v) 1 else 0))
    fun appUnsigned(v: Long) = unsignedBytes(v).let { header(2, false, it.size); out.write(it) }
    fun appSigned(v: Long) = signedBytes(v).let { header(3, false, it.size); out.write(it) }
    fun appReal(v: Float) { header(4, false, 4); writeInt(java.lang.Float.floatToIntBits(v)) }
    fun appDouble(v: Double) { header(5, false, 8); val b = java.lang.Double.doubleToLongBits(v); for (s in 56 downTo 0 step 8) out.write((b ushr s).toInt()) }
    fun appOctets(v: ByteArray) { header(6, false, v.size); out.write(v) }
    fun appString(v: String) { val b = v.toByteArray(Charsets.UTF_8); header(7, false, b.size + 1); out.write(0); out.write(b) }
    fun appBitString(bits: List<Boolean>) {
        val n = (bits.size + 7) / 8
        val content = ByteArray(n + 1)
        content[0] = ((n * 8 - bits.size) and 7).toByte()
        bits.forEachIndexed { i, b -> if (b) content[1 + i / 8] = (content[1 + i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
        header(8, false, content.size); out.write(content)
    }
    fun appEnumerated(v: Long) = unsignedBytes(v).let { header(9, false, it.size); out.write(it) }
    fun appObjectId(id: ObjectId) { header(12, false, 4); writeInt(id.encoded().toInt()) }

    /** Date: year - 1900, month, day, day of week (1 = Monday), clause 20.2.12. */
    fun appDate(d: java.time.LocalDate) { header(10, false, 4); out.write(d.year - 1900); out.write(d.monthValue); out.write(d.dayOfMonth); out.write(d.dayOfWeek.value) }
    /** Time: hour, minute, second, hundredths, clause 20.2.13. */
    fun appTime(t: java.time.LocalTime) { header(11, false, 4); out.write(t.hour); out.write(t.minute); out.write(t.second); out.write(t.nano / 10_000_000) }

    fun ctxUnsigned(number: Int, v: Long) = unsignedBytes(v).let { header(number, true, it.size); out.write(it) }
    fun ctxEnumerated(number: Int, v: Long) = ctxUnsigned(number, v)
    fun ctxObjectId(number: Int, id: ObjectId) { header(number, true, 4); writeInt(id.encoded().toInt()) }

    private fun writeInt(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }

    /** Encode a Kotlin value with its natural application tag. */
    fun appAny(value: Any?) {
        when (value) {
            null, BacnetNull -> appNull()
            is Boolean -> appBoolean(value)
            is BacnetUnsigned -> appUnsigned(value.value)
            is BacnetEnumerated -> appEnumerated(value.value)
            is BacnetBitString -> appBitString(value.bits)
            is ObjectId -> appObjectId(value)
            is Float -> appReal(value)
            is Double -> appDouble(value)
            is Int, is Long, is Short, is Byte -> {
                val v = (value as Number).toLong()
                if (v >= 0) appUnsigned(v) else appSigned(v)
            }
            is String -> appString(value)
            is ByteArray -> appOctets(value)
            else -> throw IllegalArgumentException("cannot encode ${value::class.simpleName}")
        }
    }
}

/** Tagged decoder over `buf[pos until end]`. */
class TagReader(private val buf: ByteArray, var pos: Int = 0, private val end: Int = buf.size) {
    class Header(val number: Int, val context: Boolean, val length: Int, val opening: Boolean, val closing: Boolean, val headerBytes: Int)

    val remaining: Int get() = end - pos
    fun atEnd(): Boolean = pos >= end

    fun peek(): Header? = if (pos >= end) null else header(pos)

    private fun need(at: Int, n: Int) {
        if (at + n > end) throw BacnetDecodeException("truncated tag at $at")
    }

    private fun header(at: Int): Header {
        need(at, 1)
        var p = at
        val b = buf[p++].toInt() and 0xFF
        var number = b ushr 4
        if (number == 15) { need(p, 1); number = buf[p++].toInt() and 0xFF }
        val context = (b and 0x08) != 0
        val lvt = b and 0x07
        if (context && lvt == 6) return Header(number, true, 0, opening = true, closing = false, headerBytes = p - at)
        if (context && lvt == 7) return Header(number, true, 0, opening = false, closing = true, headerBytes = p - at)
        if (!context && number == 1) return Header(1, false, lvt, opening = false, closing = false, headerBytes = p - at)
        var length = lvt
        if (lvt == 5) {
            need(p, 1)
            length = buf[p++].toInt() and 0xFF
            if (length == 254) { need(p, 2); length = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF); p += 2 }
            else if (length == 255) { need(p, 4); length = 0; repeat(4) { length = (length shl 8) or (buf[p++].toInt() and 0xFF) } }
        }
        return Header(number, context, length, opening = false, closing = false, headerBytes = p - at).also { need(at + it.headerBytes, length) }
    }

    fun next(): Header = header(pos).also { pos += it.headerBytes }

    private fun content(h: Header): ByteArray {
        if (h.number == 1 && !h.context) return ByteArray(0)
        need(pos, h.length)
        return buf.copyOfRange(pos, pos + h.length).also { pos += h.length }
    }

    fun expectOpen(number: Int) {
        val h = next()
        if (!h.opening || h.number != number) throw BacnetDecodeException("expected opening tag $number")
    }

    fun expectClose(number: Int) {
        val h = next()
        if (!h.closing || h.number != number) throw BacnetDecodeException("expected closing tag $number")
    }

    fun isOpen(number: Int): Boolean = peek()?.let { it.opening && it.number == number } == true
    fun isClose(number: Int): Boolean = peek()?.let { it.closing && it.number == number } == true
    fun isContext(number: Int): Boolean = peek()?.let { it.context && !it.opening && !it.closing && it.number == number } == true

    fun ctxUnsigned(number: Int): Long {
        val h = next()
        if (!h.context || h.number != number || h.opening || h.closing) throw BacnetDecodeException("expected context tag $number")
        return unsigned(content(h))
    }

    fun ctxObjectId(number: Int): ObjectId = ObjectId.decode(ctxUnsigned(number))

    fun optCtxUnsigned(number: Int): Long? = if (isContext(number)) ctxUnsigned(number) else null

    fun appValue(): Any? {
        val h = next()
        if (h.context) throw BacnetDecodeException("expected an application tag")
        return application(h, content(h))
    }

    fun appUnsigned(): Long = (appValue() as? BacnetUnsigned)?.value ?: throw BacnetDecodeException("expected unsigned")
    fun appEnumerated(): Long = (appValue() as? BacnetEnumerated)?.value ?: throw BacnetDecodeException("expected enumerated")
    fun appObjectId(): ObjectId = appValue() as? ObjectId ?: throw BacnetDecodeException("expected object identifier")

    /** Values up to the closing tag [number]; a nested constructed value becomes a list. */
    fun valuesUntilClose(number: Int): List<Any?> {
        val out = ArrayList<Any?>()
        while (true) {
            val h = peek() ?: throw BacnetDecodeException("missing closing tag $number")
            if (h.closing && h.number == number) { next(); return out }
            if (h.opening) { next(); out.add(valuesUntilClose(h.number)); continue }
            if (h.closing) throw BacnetDecodeException("unbalanced closing tag ${h.number}")
            next()
            val bytes = content(h)
            out.add(if (h.context) ContextRaw(h.number, bytes) else application(h, bytes))
        }
    }

    fun skipValue() {
        val h = next()
        if (h.opening) { valuesUntilClose(h.number); return }
        if (h.closing) throw BacnetDecodeException("unexpected closing tag")
        content(h)
    }

    companion object {
        fun unsigned(b: ByteArray): Long {
            if (b.size > 4 || b.isEmpty()) throw BacnetDecodeException("unsigned of ${b.size} bytes")
            var v = 0L
            for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
            return v
        }

        fun signed(b: ByteArray): Long {
            if (b.size > 4 || b.isEmpty()) throw BacnetDecodeException("signed of ${b.size} bytes")
            var v = b[0].toLong()
            for (i in 1 until b.size) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }

        fun application(h: Header, b: ByteArray): Any? = when (h.number) {
            0 -> BacnetNull
            1 -> h.length == 1
            2 -> BacnetUnsigned(unsigned(b))
            3 -> signed(b)
            4 -> { if (b.size != 4) throw BacnetDecodeException("real of ${b.size} bytes"); java.lang.Float.intBitsToFloat(unsigned(b).toInt()) }
            5 -> { if (b.size != 8) throw BacnetDecodeException("double of ${b.size} bytes"); var v = 0L; for (x in b) v = (v shl 8) or (x.toLong() and 0xFF); java.lang.Double.longBitsToDouble(v) }
            6 -> b
            7 -> characterString(b)
            8 -> {
                if (b.isEmpty()) throw BacnetDecodeException("empty bit string")
                val unused = b[0].toInt() and 7
                val count = (b.size - 1) * 8 - unused
                BacnetBitString(List(count.coerceAtLeast(0)) { i -> (b[1 + i / 8].toInt() and (0x80 ushr (i % 8))) != 0 })
            }
            9 -> BacnetEnumerated(unsigned(b))
            10 -> if (b.size == 4) date(b) else throw BacnetDecodeException("date of ${b.size} bytes")
            11 -> if (b.size == 4) time(b) else throw BacnetDecodeException("time of ${b.size} bytes")
            12 -> if (b.size == 4) ObjectId.decode(unsigned(b)) else throw BacnetDecodeException("object id of ${b.size} bytes")
            else -> ContextRaw(h.number, b)
        }

        private fun characterString(b: ByteArray): String {
            if (b.isEmpty()) return ""
            return when (b[0].toInt() and 0xFF) {
                0 -> String(b, 1, b.size - 1, Charsets.UTF_8)
                4 -> String(b, 1, b.size - 1, Charsets.UTF_16BE) // UCS-2
                5 -> String(b, 1, b.size - 1, Charsets.ISO_8859_1)
                else -> String(b, 1, b.size - 1, Charsets.ISO_8859_1)
            }
        }

        private fun date(b: ByteArray): String {
            val y = b[0].toInt() and 0xFF
            val m = b[1].toInt() and 0xFF
            val d = b[2].toInt() and 0xFF
            fun part(v: Int, w: Int, any: Int) = if (v == any) "*" else v.toString().padStart(w, '0')
            return "${if (y == 255) "*" else (1900 + y).toString()}-${part(m, 2, 255)}-${part(d, 2, 255)}"
        }

        private fun time(b: ByteArray): String {
            fun part(v: Int) = if (v == 255) "*" else v.toString().padStart(2, '0')
            return "${part(b[0].toInt() and 0xFF)}:${part(b[1].toInt() and 0xFF)}:${part(b[2].toInt() and 0xFF)}.${part(b[3].toInt() and 0xFF)}"
        }
    }
}

/**
 * A BACnet address: the UDP endpoint, plus the remote network number and MAC address when the
 * device sits behind a router (the source network fields of the frame it answered with).
 */
data class BacnetAddress(val socket: InetSocketAddress, val network: Int? = null, val mac: ByteArray? = null) {
    override fun equals(other: Any?) =
        other is BacnetAddress && other.socket == socket && other.network == network && (other.mac?.contentEquals(mac ?: ByteArray(0)) ?: (mac == null))
    override fun hashCode() = socket.hashCode() * 31 + (network ?: 0)
    override fun toString(): String = if (network == null) "${socket.hostString}:${socket.port}" else "${socket.hostString}:${socket.port} net $network mac ${mac?.joinToString("") { "%02x".format(it) }}"
}

sealed class Apdu {
    data class ConfirmedRequest(val invokeId: Int, val service: Int, val maxApdu: Int, val segmented: Boolean, val data: ByteArray) : Apdu()
    data class Unconfirmed(val service: Int, val data: ByteArray) : Apdu()
    data class SimpleAck(val invokeId: Int, val service: Int) : Apdu()
    data class ComplexAck(val invokeId: Int, val service: Int, val segmented: Boolean, val data: ByteArray) : Apdu()
    data class Error(val invokeId: Int, val service: Int, val errorClass: Long, val errorCode: Long) : Apdu()
    data class Reject(val invokeId: Int, val reason: Int) : Apdu()
    data class Abort(val invokeId: Int, val reason: Int, val fromServer: Boolean) : Apdu()
    data class Other(val type: Int, val invokeId: Int) : Apdu()
}

/** One received NPDU after BVLC and network header decoding. */
class Received(val source: BacnetAddress, val broadcast: Boolean, val apdu: Apdu)

object BacnetFrames {
    const val BVLC_TYPE = 0x81
    const val ORIGINAL_UNICAST = 0x0A
    const val ORIGINAL_BROADCAST = 0x0B
    const val FORWARDED_NPDU = 0x04
    const val DEFAULT_PORT = 47808

    private val maxApduSizes = intArrayOf(50, 128, 206, 480, 1024, 1476)

    fun maxApduCode(size: Int): Int = maxApduSizes.indexOfLast { it <= size }.coerceAtLeast(0)
    fun maxApduSize(code: Int): Int = maxApduSizes.getOrElse(code and 0x0F) { 1476 }

    /** BVLC + NPDU around [apdu]. A routed [dest] adds DNET/DADR and a hop count. */
    fun frame(apdu: ByteArray, dest: BacnetAddress?, broadcast: Boolean, expectingReply: Boolean, globalBroadcast: Boolean = false): ByteArray {
        val npdu = ByteArrayOutputStream()
        npdu.write(0x01)
        val routed = dest?.network != null
        var control = 0
        if (routed || globalBroadcast) control = control or 0x20
        if (expectingReply) control = control or 0x04
        npdu.write(control)
        if (globalBroadcast) {
            npdu.write(0xFF); npdu.write(0xFF); npdu.write(0)
        } else if (routed) {
            val net = dest!!.network!!
            val mac = dest.mac ?: ByteArray(0)
            npdu.write(net ushr 8); npdu.write(net); npdu.write(mac.size); npdu.write(mac)
        }
        if (routed || globalBroadcast) npdu.write(0xFF)
        npdu.write(apdu)
        val body = npdu.toByteArray()
        val total = body.size + 4
        val out = ByteArray(total)
        out[0] = BVLC_TYPE.toByte()
        out[1] = (if (broadcast) ORIGINAL_BROADCAST else ORIGINAL_UNICAST).toByte()
        out[2] = (total ushr 8).toByte()
        out[3] = total.toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }

    /** Decode a datagram from [from]. Null for frames that carry no APDU for us (BVLC results, network messages). */
    fun parse(data: ByteArray, length: Int, from: InetSocketAddress): Received? {
        if (length < 4 || (data[0].toInt() and 0xFF) != BVLC_TYPE) return null
        val function = data[1].toInt() and 0xFF
        val declared = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (declared != length) throw BacnetDecodeException("BVLC length $declared does not match datagram length $length")
        var p = 4
        var origin = from
        val broadcast: Boolean
        when (function) {
            ORIGINAL_UNICAST -> broadcast = false
            ORIGINAL_BROADCAST -> broadcast = true
            FORWARDED_NPDU -> {
                if (length < 10) throw BacnetDecodeException("short forwarded NPDU")
                val ip = InetAddress.getByAddress(data.copyOfRange(4, 8))
                val port = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
                origin = InetSocketAddress(ip, port)
                p = 10
                broadcast = true
            }
            else -> return null
        }
        if (p + 2 > length) throw BacnetDecodeException("short NPDU")
        if ((data[p].toInt() and 0xFF) != 0x01) throw BacnetDecodeException("NPDU version ${data[p]}")
        val control = data[p + 1].toInt() and 0xFF
        p += 2
        val hasDnet = control and 0x20 != 0
        val hasSnet = control and 0x08 != 0
        if (hasDnet) {
            if (p + 3 > length) throw BacnetDecodeException("short DNET")
            val dlen = data[p + 2].toInt() and 0xFF
            p += 3 + dlen
        }
        var snet: Int? = null
        var sadr: ByteArray? = null
        if (hasSnet) {
            if (p + 3 > length) throw BacnetDecodeException("short SNET")
            snet = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            val slen = data[p + 2].toInt() and 0xFF
            if (p + 3 + slen > length) throw BacnetDecodeException("short SADR")
            sadr = data.copyOfRange(p + 3, p + 3 + slen)
            p += 3 + slen
        }
        if (hasDnet) p += 1 // hop count
        if (control and 0x80 != 0) return null // network layer message
        if (p >= length) throw BacnetDecodeException("missing APDU")
        val apdu = parseApdu(data, p, length)
        return Received(BacnetAddress(origin, snet, sadr), broadcast, apdu)
    }

    fun parseApdu(b: ByteArray, start: Int, end: Int): Apdu {
        fun u(i: Int): Int {
            if (start + i >= end) throw BacnetDecodeException("short APDU")
            return b[start + i].toInt() and 0xFF
        }
        val type = u(0) ushr 4
        return when (type) {
            0 -> {
                val segmented = u(0) and 0x08 != 0
                val maxApdu = maxApduSize(u(1) and 0x0F)
                val invoke = u(2)
                val headerLen = if (segmented) 6 else 4
                Apdu.ConfirmedRequest(invoke, u(headerLen - 1), maxApdu, segmented, b.copyOfRange(start + headerLen, end))
            }
            1 -> Apdu.Unconfirmed(u(1), b.copyOfRange(start + 2, end))
            2 -> Apdu.SimpleAck(u(1), u(2))
            3 -> {
                val segmented = u(0) and 0x08 != 0
                val headerLen = if (segmented) 5 else 3
                Apdu.ComplexAck(u(1), u(headerLen - 1), segmented, b.copyOfRange(start + headerLen, end))
            }
            5 -> {
                val r = TagReader(b, start + 3, end)
                // Most services answer class and code; some wrap them in context tag 0.
                if (r.isOpen(0)) r.next()
                val cls = r.appEnumerated()
                val code = r.appEnumerated()
                Apdu.Error(u(1), u(2), cls, code)
            }
            6 -> Apdu.Reject(u(1), u(2))
            7 -> Apdu.Abort(u(1), u(2), u(0) and 0x01 != 0)
            else -> Apdu.Other(type, if (end - start > 1) u(1) else 0)
        }
    }

    // ---- APDU builders ----------------------------------------------------------------------

    fun confirmed(invokeId: Int, service: Int, data: ByteArray, maxApdu: Int = 1476): ByteArray =
        byteArrayOf(0x00, maxApduCode(maxApdu).toByte(), invokeId.toByte(), service.toByte()) + data

    fun unconfirmed(service: Int, data: ByteArray): ByteArray = byteArrayOf(0x10, service.toByte()) + data
    fun simpleAck(invokeId: Int, service: Int): ByteArray = byteArrayOf(0x20, invokeId.toByte(), service.toByte())
    fun complexAck(invokeId: Int, service: Int, data: ByteArray): ByteArray = byteArrayOf(0x30, invokeId.toByte(), service.toByte()) + data
    fun error(invokeId: Int, service: Int, cls: Int, code: Int): ByteArray =
        byteArrayOf(0x50, invokeId.toByte(), service.toByte()) + TagWriter().apply { appEnumerated(cls.toLong()); appEnumerated(code.toLong()) }.bytes()
    fun reject(invokeId: Int, reason: Int): ByteArray = byteArrayOf(0x60, invokeId.toByte(), reason.toByte())
    fun abort(invokeId: Int, reason: Int, fromServer: Boolean): ByteArray = byteArrayOf((0x70 or if (fromServer) 1 else 0).toByte(), invokeId.toByte(), reason.toByte())

    // ---- service parameters -----------------------------------------------------------------

    fun whoIs(low: Int?, high: Int?): ByteArray = TagWriter().apply {
        if (low != null && high != null) { ctxUnsigned(0, low.toLong()); ctxUnsigned(1, high.toLong()) }
    }.bytes()

    fun iAm(device: Int, maxApdu: Int, segmentation: Int, vendorId: Int): ByteArray = TagWriter().apply {
        appObjectId(ObjectId(BacnetObjectType.DEVICE, device)); appUnsigned(maxApdu.toLong()); appEnumerated(segmentation.toLong()); appUnsigned(vendorId.toLong())
    }.bytes()

    fun readProperty(obj: ObjectId, property: Int, index: Int? = null): ByteArray = TagWriter().apply {
        ctxObjectId(0, obj); ctxEnumerated(1, property.toLong()); if (index != null) ctxUnsigned(2, index.toLong())
    }.bytes()

    fun readPropertyMultiple(requests: List<Pair<ObjectId, List<PropertyRef>>>): ByteArray = TagWriter().apply {
        for ((obj, props) in requests) {
            ctxObjectId(0, obj)
            open(1)
            for (p in props) { ctxEnumerated(0, p.property.toLong()); if (p.index != null) ctxUnsigned(1, p.index.toLong()) }
            close(1)
        }
    }.bytes()

    /** TimeSynchronization and UTCTimeSynchronization service data: a date and a time (clause 16.7, 16.8). */
    fun timeSynchronization(at: java.time.LocalDateTime): ByteArray = TagWriter().apply { appDate(at.toLocalDate()); appTime(at.toLocalTime()) }.bytes()

    fun writeProperty(obj: ObjectId, property: Int, index: Int?, value: Any?, priority: Int?): ByteArray = TagWriter().apply {
        ctxObjectId(0, obj); ctxEnumerated(1, property.toLong()); if (index != null) ctxUnsigned(2, index.toLong())
        open(3); appAny(value); close(3)
        if (priority != null) ctxUnsigned(4, priority.toLong())
    }.bytes()
}

data class PropertyRef(val property: Int, val index: Int? = null)

data class IAm(val device: Int, val maxApdu: Int, val segmentation: Int, val vendorId: Int) {
    companion object {
        fun parse(data: ByteArray): IAm {
            val r = TagReader(data)
            val id = r.appObjectId()
            if (id.type != BacnetObjectType.DEVICE) throw BacnetDecodeException("I-Am names ${id}")
            return IAm(id.instance, r.appUnsigned().toInt(), r.appEnumerated().toInt(), r.appUnsigned().toInt())
        }
    }
}

data class WhoIs(val low: Int?, val high: Int?) {
    fun matches(instance: Int): Boolean = low == null || high == null || instance in low..high

    companion object {
        fun parse(data: ByteArray): WhoIs {
            if (data.isEmpty()) return WhoIs(null, null)
            val r = TagReader(data)
            return WhoIs(r.ctxUnsigned(0).toInt(), r.ctxUnsigned(1).toInt())
        }
    }
}

/** One property value or error from a ReadProperty or ReadPropertyMultiple acknowledgement. */
data class PropertyResult(val obj: ObjectId, val property: Int, val index: Int?, val values: List<Any?>?, val errorClass: Long? = null, val errorCode: Long? = null) {
    val ok: Boolean get() = values != null
    /** The single value, or the list when the property is a sequence or array. */
    val value: Any? get() = values?.let { if (it.size == 1) it[0] else it }
}

object BacnetAcks {
    fun readProperty(data: ByteArray): PropertyResult {
        val r = TagReader(data)
        val obj = r.ctxObjectId(0)
        val prop = r.ctxUnsigned(1).toInt()
        val index = r.optCtxUnsigned(2)?.toInt()
        r.expectOpen(3)
        val values = r.valuesUntilClose(3)
        return PropertyResult(obj, prop, index, values)
    }

    fun readPropertyMultiple(data: ByteArray): List<PropertyResult> {
        val r = TagReader(data)
        val out = ArrayList<PropertyResult>()
        while (!r.atEnd()) {
            val obj = r.ctxObjectId(0)
            r.expectOpen(1)
            while (!r.isClose(1)) {
                val prop = r.ctxUnsigned(2).toInt()
                val index = r.optCtxUnsigned(3)?.toInt()
                if (r.isOpen(4)) {
                    r.next()
                    out += PropertyResult(obj, prop, index, r.valuesUntilClose(4))
                } else if (r.isOpen(5)) {
                    r.next()
                    val cls = r.appEnumerated()
                    val code = r.appEnumerated()
                    r.expectClose(5)
                    out += PropertyResult(obj, prop, index, null, cls, code)
                } else {
                    throw BacnetDecodeException("property result without value or error")
                }
            }
            r.expectClose(1)
        }
        return out
    }
}
