package org.oip.sim.bacnet

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A BACnet/IP device on a loopback UDP port, written from the public protocol description (ASHRAE 135
 * Annex J and clauses 6, 20 and 21). It answers Who-Is with I-Am, ReadProperty, ReadPropertyMultiple and
 * WriteProperty with a 16-level priority array on commandable objects. Its encoder and decoder are its own,
 * not the driver's.
 *
 * Test knobs: [maxApdu] (a reply that does not fit is aborted with segmentation-not-supported),
 * [supportsReadPropertyMultiple], [silent] (drop every request), [answerWhoIs].
 */
class BacnetSimulator(
    val deviceInstance: Int,
    val deviceName: String = "sim-device",
    val vendorId: Int = 999,
    port: Int = 0,
) : AutoCloseable {
    class Obj(val type: Int, val instance: Int, var name: String) {
        @Volatile var presentValue: Any? = null
        @Volatile var description: String = ""
        @Volatile var units: Int = 95
        @Volatile var outOfService = false
        @Volatile var inAlarm = false
        @Volatile var fault = false
        @Volatile var overridden = false
        @Volatile var reliability = 0
        val priority = arrayOfNulls<Any?>(16)
        @Volatile var relinquishDefault: Any? = null
        val commandable: Boolean get() = type in COMMANDABLE

        fun effective(): Any? {
            if (!commandable) return presentValue
            return priority.firstOrNull { it != null } ?: relinquishDefault
        }
    }

    class TimeSync(val utc: Boolean, val data: ByteArray)
    data class Write(val type: Int, val instance: Int, val property: Int, val value: Any?, val priority: Int?)

    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
    private val objects = ConcurrentHashMap<Long, Obj>()
    private val order = CopyOnWriteArrayList<Long>()
    private val thread = Thread({ loop() }, "bacnet-sim-$deviceInstance").apply { isDaemon = true }

    @Volatile var maxApdu = 1476
    @Volatile var supportsReadPropertyMultiple = true
    @Volatile var silent = false
    @Volatile var answerWhoIs = true
    val whoIsCount = AtomicInteger()
    val requestCount = AtomicInteger()
    /** Datagrams dropped while [silent]. */
    val droppedCount = AtomicInteger()
    val readPropertyCount = AtomicInteger()
    val readPropertyMultipleCount = AtomicInteger()
    val writes = CopyOnWriteArrayList<Write>()
    /** TimeSynchronization (utc false) and UTCTimeSynchronization requests received, with their raw service data. */
    val timeSyncs = CopyOnWriteArrayList<TimeSync>()

    val port: Int get() = socket.localPort

    init {
        add(DEVICE, deviceInstance, deviceName)
        thread.start()
    }

    fun add(type: Int, instance: Int, name: String, value: Any? = null): Obj {
        val o = Obj(type, instance, name)
        if (o.commandable) o.relinquishDefault = value else o.presentValue = value
        val key = key(type, instance)
        if (objects.put(key, o) == null) order += key
        return o
    }

    fun obj(type: Int, instance: Int): Obj = objects.getValue(key(type, instance))

    override fun close() {
        socket.close()
        thread.join(2_000)
    }

    private fun key(type: Int, instance: Int) = (type.toLong() shl 22) or instance.toLong()

    private fun loop() {
        val buf = ByteArray(2048)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                return
            }
            if (silent) { droppedCount.incrementAndGet(); continue }
            try {
                handle(buf.copyOf(packet.length), InetSocketAddress(packet.address, packet.port))
            } catch (_: RuntimeException) {
                // A malformed request is dropped, like a device would.
            }
        }
    }

    private fun send(apdu: ByteArray, to: InetSocketAddress) {
        val npdu = byteArrayOf(0x01, 0x00) + apdu
        val len = npdu.size + 4
        val frame = byteArrayOf(0x81.toByte(), 0x0A, (len shr 8).toByte(), len.toByte()) + npdu
        socket.send(DatagramPacket(frame, frame.size, to))
    }

    private fun handle(b: ByteArray, from: InetSocketAddress) {
        if (b.size < 6 || b[0] != 0x81.toByte()) return
        if (((b[2].toInt() and 0xFF) shl 8 or (b[3].toInt() and 0xFF)) != b.size) return
        var p = 4
        if (b[p].toInt() != 1) return
        val control = b[p + 1].toInt() and 0xFF
        p += 2
        if (control and 0x20 != 0) p += 3 + (b[p + 2].toInt() and 0xFF)
        if (control and 0x08 != 0) p += 3 + (b[p + 2].toInt() and 0xFF)
        if (control and 0x20 != 0) p += 1
        if (control and 0x80 != 0) return
        val type = (b[p].toInt() and 0xFF) shr 4
        if (type == 1) {
            val unconfirmed = b[p + 1].toInt() and 0xFF
            if (unconfirmed == 8) whoIs(b.copyOfRange(p + 2, b.size), from)
            if (unconfirmed == 6 || unconfirmed == 9) timeSyncs += TimeSync(unconfirmed == 9, b.copyOfRange(p + 2, b.size))
            return
        }
        if (type != 0) return
        requestCount.incrementAndGet()
        val clientMax = MAX_APDU.getOrElse(b[p + 1].toInt() and 0x0F) { 1476 }
        val invoke = b[p + 2].toInt() and 0xFF
        val service = b[p + 3].toInt() and 0xFF
        val data = b.copyOfRange(p + 4, b.size)
        val reply = when (service) {
            12 -> { readPropertyCount.incrementAndGet(); readProperty(invoke, data) }
            14 -> {
                readPropertyMultipleCount.incrementAndGet()
                if (supportsReadPropertyMultiple) readPropertyMultiple(invoke, data) else byteArrayOf(0x60, invoke.toByte(), 9)
            }
            15 -> writeProperty(invoke, data)
            else -> byteArrayOf(0x60, invoke.toByte(), 9)
        }
        val fits = reply.size <= minOf(clientMax, maxApdu)
        send(if (fits) reply else byteArrayOf(0x71, invoke.toByte(), 4), from)
    }

    private fun whoIs(data: ByteArray, from: InetSocketAddress) {
        whoIsCount.incrementAndGet()
        if (!answerWhoIs) return
        if (data.isNotEmpty()) {
            val r = Rd(data)
            val low = r.ctx(0)
            val high = r.ctx(1)
            if (deviceInstance < low || deviceInstance > high) return
        }
        val w = Wr()
        w.appObjectId(DEVICE, deviceInstance); w.appUnsigned(maxApdu.toLong()); w.appEnum(3); w.appUnsigned(vendorId.toLong())
        send(byteArrayOf(0x10, 0x00) + w.bytes(), from)
    }

    private fun error(invoke: Int, service: Int, cls: Int, code: Int) =
        byteArrayOf(0x50, invoke.toByte(), service.toByte(), 0x91.toByte(), cls.toByte(), 0x91.toByte(), code.toByte())

    /** Encode one property or return (class, code). */
    private fun property(o: Obj, prop: Int, index: Long?, w: Wr): Pair<Int, Int>? {
        if (index != null && prop != OBJECT_LIST && prop != PRIORITY_ARRAY) return 2 to 50
        when (prop) {
            75 -> w.appObjectId(o.type, o.instance)
            77 -> w.appString(o.name)
            79 -> w.appEnum(o.type.toLong())
            28 -> w.appString(o.description)
            OBJECT_LIST -> {
                if (o.type != DEVICE) return 2 to 32
                val all = order.map { objects.getValue(it) }
                when (index) {
                    null -> all.forEach { w.appObjectId(it.type, it.instance) }
                    0L -> w.appUnsigned(all.size.toLong())
                    else -> { val x = all.getOrNull(index.toInt() - 1) ?: return 2 to 42; w.appObjectId(x.type, x.instance) }
                }
            }
            120 -> if (o.type == DEVICE) w.appUnsigned(vendorId.toLong()) else return 2 to 32
            121 -> if (o.type == DEVICE) w.appString("Simulated Vendor") else return 2 to 32
            70 -> if (o.type == DEVICE) w.appString("Sim") else return 2 to 32
            44 -> if (o.type == DEVICE) w.appString("1.0") else return 2 to 32
            112 -> if (o.type == DEVICE) w.appEnum(0) else return 2 to 32
            62 -> if (o.type == DEVICE) w.appUnsigned(maxApdu.toLong()) else return 2 to 32
            107 -> if (o.type == DEVICE) w.appEnum(3) else return 2 to 32
            PRESENT_VALUE -> if (o.type == DEVICE) return 2 to 32 else value(o.type, o.effective(), w)
            STATUS_FLAGS -> if (o.type == DEVICE) return 2 to 32 else w.appBits(listOf(o.inAlarm, o.fault, o.overridden, o.outOfService))
            OUT_OF_SERVICE -> if (o.type == DEVICE) return 2 to 32 else w.appBool(o.outOfService)
            103 -> if (o.type == DEVICE) return 2 to 32 else w.appEnum(o.reliability.toLong())
            117 -> if (o.type !in ANALOG) return 2 to 32 else w.appEnum(o.units.toLong())
            104 -> if (!o.commandable) return 2 to 32 else value(o.type, o.relinquishDefault, w)
            PRIORITY_ARRAY -> {
                if (!o.commandable) return 2 to 32
                when (index) {
                    null -> o.priority.forEach { value(o.type, it, w) }
                    0L -> w.appUnsigned(16)
                    in 1L..16L -> value(o.type, o.priority[index.toInt() - 1], w)
                    else -> return 2 to 42
                }
            }
            else -> return 2 to 32
        }
        return null
    }

    private fun value(type: Int, v: Any?, w: Wr) {
        when {
            v == null -> w.raw(0x00)
            type in ANALOG -> w.appReal((v as Number).toFloat())
            type in BINARY -> w.appEnum((v as Number).toLong())
            else -> w.appUnsigned((v as Number).toLong())
        }
    }

    private fun readProperty(invoke: Int, data: ByteArray): ByteArray {
        val r = Rd(data)
        val oid = r.ctx(0)
        val prop = r.ctx(1).toInt()
        val index = if (!r.end() && r.peekCtx(2)) r.ctx(2) else null
        val o = objects[wildcard(oid)] ?: return error(invoke, 12, 1, 31)
        val w = Wr()
        w.ctx(0, oid, 4); w.ctx(1, prop.toLong())
        if (index != null) w.ctx(2, index)
        w.raw(0x3E)
        property(o, prop, index, w)?.let { (c, code) -> return error(invoke, 12, c, code) }
        w.raw(0x3F)
        return byteArrayOf(0x30, invoke.toByte(), 12) + w.bytes()
    }

    private fun wildcard(oid: Long): Long = if (oid == key(DEVICE, 4194303)) key(DEVICE, deviceInstance) else oid

    private fun readPropertyMultiple(invoke: Int, data: ByteArray): ByteArray {
        val r = Rd(data)
        val w = Wr()
        while (!r.end()) {
            val oid = r.ctx(0)
            r.expect(0x1E)
            w.ctx(0, oid, 4)
            w.raw(0x1E)
            val o = objects[wildcard(oid)]
            while (!r.at(0x1F)) {
                val prop = r.ctx(0).toInt()
                val index = if (r.peekCtx(1)) r.ctx(1) else null
                w.ctx(2, prop.toLong())
                if (index != null) w.ctx(3, index)
                val inner = Wr()
                val err = if (o == null) 1 to 31 else property(o, prop, index, inner)
                if (err == null) { w.raw(0x4E); w.raw(*inner.bytes()); w.raw(0x4F) }
                else { w.raw(0x5E); w.appEnum(err.first.toLong()); w.appEnum(err.second.toLong()); w.raw(0x5F) }
            }
            r.expect(0x1F)
            w.raw(0x1F)
        }
        return byteArrayOf(0x30, invoke.toByte(), 14) + w.bytes()
    }

    private fun writeProperty(invoke: Int, data: ByteArray): ByteArray {
        val r = Rd(data)
        val oid = r.ctx(0)
        val prop = r.ctx(1).toInt()
        if (r.peekCtx(2)) r.ctx(2)
        r.expect(0x3E)
        val value = r.app()
        r.expect(0x3F)
        val priority = if (!r.end() && r.peekCtx(4)) r.ctx(4).toInt() else null
        val o = objects[wildcard(oid)] ?: return error(invoke, 15, 1, 31)
        writes += Write(o.type, o.instance, prop, value, priority)
        when (prop) {
            OUT_OF_SERVICE -> o.outOfService = value as? Boolean ?: return error(invoke, 15, 2, 9)
            28 -> o.description = value as? String ?: return error(invoke, 15, 2, 9)
            104 -> if (o.commandable) o.relinquishDefault = value else return error(invoke, 15, 2, 40)
            PRESENT_VALUE -> {
                if (o.type == DEVICE) return error(invoke, 15, 2, 40)
                val typed = when {
                    value == null -> null
                    o.type in ANALOG -> value as? Float ?: return error(invoke, 15, 2, 9)
                    o.type in BINARY -> (value as? Enumerated)?.v?.also { if (it > 1) return error(invoke, 15, 2, 37) } ?: return error(invoke, 15, 2, 9)
                    else -> (value as? Unsigned)?.v ?: return error(invoke, 15, 2, 9)
                }
                if (o.commandable) {
                    val slot = (priority ?: 16)
                    if (slot !in 1..16 || slot == 6) return error(invoke, 15, 2, 37)
                    o.priority[slot - 1] = typed
                } else {
                    if (!o.outOfService) return error(invoke, 15, 2, 40)
                    if (typed == null) return error(invoke, 15, 2, 9)
                    o.presentValue = typed
                }
            }
            else -> return error(invoke, 15, 2, 40)
        }
        return byteArrayOf(0x20, invoke.toByte(), 15)
    }

    data class Enumerated(val v: Long)
    data class Unsigned(val v: Long)

    private class Wr {
        private val o = ByteArrayOutputStream()
        fun bytes(): ByteArray = o.toByteArray()
        fun raw(vararg b: Int) = b.forEach { o.write(it) }
        fun raw(vararg b: Byte) = o.write(b)
        private fun minimal(v: Long): ByteArray {
            val n = when { v < 0x100 -> 1; v < 0x10000 -> 2; v < 0x1000000 -> 3; else -> 4 }
            return ByteArray(n) { (v shr (8 * (n - 1 - it))).toByte() }
        }
        private fun tag(number: Int, ctx: Boolean, content: ByteArray) {
            val len = content.size
            o.write((number shl 4) or (if (ctx) 8 else 0) or (if (len < 5) len else 5))
            if (len >= 5) o.write(len)
            o.write(content)
        }
        fun ctx(n: Int, v: Long, width: Int = 0) = tag(n, true, if (width == 4) ByteArray(4) { (v shr (24 - 8 * it)).toByte() } else minimal(v))
        fun appUnsigned(v: Long) = tag(2, false, minimal(v))
        fun appEnum(v: Long) = tag(9, false, minimal(v))
        fun appBool(v: Boolean) = o.write(0x10 or if (v) 1 else 0)
        fun appReal(v: Float) = tag(4, false, java.nio.ByteBuffer.allocate(4).putFloat(v).array())
        fun appString(s: String) = tag(7, false, byteArrayOf(0) + s.toByteArray(Charsets.UTF_8))
        fun appObjectId(type: Int, inst: Int) = tag(12, false, ByteArray(4) { (((type.toLong() shl 22) or inst.toLong()) shr (24 - 8 * it)).toByte() })
        fun appBits(bits: List<Boolean>) {
            var b = 0
            bits.forEachIndexed { i, x -> if (x) b = b or (0x80 shr i) }
            tag(8, false, byteArrayOf((8 - bits.size).toByte(), b.toByte()))
        }
    }

    private class Rd(val b: ByteArray) {
        var p = 0
        fun end() = p >= b.size
        fun at(v: Int) = !end() && (b[p].toInt() and 0xFF) == v
        fun peekCtx(n: Int) = !end() && ((b[p].toInt() and 0xFF) shr 4) == n && (b[p].toInt() and 0x08) != 0 && (b[p].toInt() and 7) < 6
        fun expect(v: Int) { if (!at(v)) throw IllegalStateException("expected $v"); p++ }
        private fun len(first: Int): Int { val l = first and 7; return if (l == 5) (b[p++].toInt() and 0xFF) else l }
        fun ctx(n: Int): Long {
            val first = b[p++].toInt() and 0xFF
            if (first shr 4 != n || first and 8 == 0) throw IllegalStateException("expected context $n")
            val l = len(first)
            var v = 0L
            repeat(l) { v = (v shl 8) or (b[p++].toLong() and 0xFF) }
            return v
        }
        fun app(): Any? {
            val first = b[p++].toInt() and 0xFF
            val tag = first shr 4
            if (tag == 1) return (first and 7) == 1
            val l = len(first)
            val c = b.copyOfRange(p, p + l)
            p += l
            fun u(): Long { var v = 0L; c.forEach { v = (v shl 8) or (it.toLong() and 0xFF) }; return v }
            return when (tag) {
                0 -> null
                2 -> Unsigned(u())
                3 -> { var v = c[0].toLong(); for (i in 1 until c.size) v = (v shl 8) or (c[i].toLong() and 0xFF); v }
                4 -> java.nio.ByteBuffer.wrap(c).float
                7 -> String(c, 1, c.size - 1, Charsets.UTF_8)
                9 -> Enumerated(u())
                else -> c
            }
        }
    }

    companion object {
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
        const val OBJECT_LIST = 76
        const val OUT_OF_SERVICE = 81
        const val PRESENT_VALUE = 85
        const val PRIORITY_ARRAY = 87
        const val STATUS_FLAGS = 111
        private val ANALOG = setOf(0, 1, 2)
        private val BINARY = setOf(3, 4, 5)
        private val COMMANDABLE = setOf(1, 2, 4, 5, 14, 19)
        private val MAX_APDU = intArrayOf(50, 128, 206, 480, 1024, 1476)
    }
}
