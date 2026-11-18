package org.oip.sim.s7

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process ISO-on-TCP / S7comm CPU for driver tests.
 *
 * Speaks TPKT (RFC 1006), COTP class 0 (ISO/IEC 8073) and the S7 setup, read, and write jobs
 * used by S7-300/400/1200/1500 on TCP port 102. The image is inputs, outputs, flags, data blocks,
 * and the classic timer and counter words. This class is not on the gateway classpath.
 *
 * A read or write can be held ([holdReads]) so the client blocks until its socket timeout, which
 * is how a stalled PLC is simulated. [stop] keeps the image and the bound port so a reconnect
 * sees the same values.
 */
class S7Simulator(
    val maxPdu: Int = 960,
    val memoryBytes: Int = 8192,
    val elements: Int = 4096,
) : AutoCloseable {
    private val inputs = ByteArray(memoryBytes)
    private val outputs = ByteArray(memoryBytes)
    private val flags = ByteArray(memoryBytes)
    private val timers = ByteArray(elements * 2)
    private val counters = ByteArray(elements * 2)
    private val dbs = ConcurrentHashMap<Int, ByteArray>()

    private val readJobs = AtomicLong()
    private val maxElements = AtomicInteger()

    @Volatile var holdReads: Boolean = false
    @Volatile var localTsap: Int = -1
    @Volatile var remoteTsap: Int = -1
    @Volatile var negotiatedPdu: Int = 0

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var thread: Thread? = null
    private var boundPort: Int = 0

    val port: Int get() = boundPort

    init {
        start()
    }

    fun readJobCount(): Long = readJobs.get()

    fun maxItemElements(): Int = maxElements.get()

    fun resetStats() {
        readJobs.set(0)
        maxElements.set(0)
    }

    fun ensureDb(number: Int, size: Int = memoryBytes): ByteArray =
        dbs.computeIfAbsent(number) { ByteArray(size) }

    fun setBytes(area: Char, offset: Int, data: ByteArray, db: Int = 0) {
        data.copyInto(image(area, db, true)!!, offset)
    }

    fun getBytes(area: Char, offset: Int, length: Int, db: Int = 0): ByteArray {
        val buf = image(area, db, false) ?: throw IllegalArgumentException("no $area$db")
        return buf.copyOfRange(offset, offset + length)
    }

    fun timerWord(index: Int): Int = u16(timers, index * 2)

    fun counterWord(index: Int): Int = u16(counters, index * 2)

    @Synchronized
    fun start() {
        check(!running) { "simulator already started" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", boundPort))
        if (boundPort == 0) boundPort = ss.localPort
        server = ss
        running = true
        thread = Thread(this::loop, "s7-sim-$boundPort").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!running && server == null) return
        running = false
        try { client?.close() } catch (_: IOException) {}
        try { server?.close() } catch (_: IOException) {}
        thread?.join(2_000)
        thread = null
        client = null
        server = null
    }

    override fun close() = stop()

    private fun loop() {
        while (running) {
            val ss = server ?: break
            val sock = try {
                ss.accept()
            } catch (_: IOException) {
                if (!running) break
                continue
            }
            client = sock
            try {
                sock.tcpNoDelay = true
                handle(sock)
            } catch (_: EOFException) {
            } catch (_: IOException) {
            } finally {
                try { sock.close() } catch (_: IOException) {}
                if (client === sock) client = null
            }
        }
    }

    private fun handle(sock: Socket) {
        val input = DataInputStream(sock.getInputStream())
        val output = DataOutputStream(sock.getOutputStream())
        val cr = readTpkt(input)
        if (cr.isEmpty() || (cr[1].toInt() and 0xFF) != 0xE0) throw IOException("expected COTP CR")
        rememberTsap(cr)
        val srcRef = u16(cr, 4)
        output.write(connectionConfirm(srcRef))
        output.flush()
        while (running && !sock.isClosed) {
            val packet = readTpkt(input)
            val li = packet[0].toInt() and 0xFF
            val type = packet[1].toInt() and 0xFF
            if ((type and 0xF0) != 0xF0) continue
            val s7 = packet.copyOfRange(1 + li, packet.size)
            if (s7.size < 10 || (s7[0].toInt() and 0xFF) != 0x32) continue
            val function = functionOf(s7)
            if (holdReads && (function == 0x04 || function == 0x05)) {
                while (holdReads && running) Thread.sleep(20)
                if (!running) return
            }
            val reply = answer(s7)
            output.write(tpkt(dataTransfer(reply)))
            output.flush()
        }
    }

    private fun functionOf(s7: ByteArray): Int {
        val plen = u16(s7, 6)
        if (s7.size < 10 + plen || plen < 1) return -1
        return s7[10].toInt() and 0xFF
    }

    private fun answer(s7: ByteArray): ByteArray {
        val ref = u16(s7, 4)
        val plen = u16(s7, 6)
        val dlen = u16(s7, 8)
        val param = s7.copyOfRange(10, 10 + plen)
        val data = s7.copyOfRange(10 + plen, minOf(s7.size, 10 + plen + dlen))
        if (param.isEmpty()) return ack(ref, byteArrayOf(0), byteArrayOf())
        return when (param[0].toInt() and 0xFF) {
            0xF0 -> setup(ref, param)
            0x04 -> read(ref, param)
            0x05 -> write(ref, param, data)
            else -> ack(ref, byteArrayOf(param[0], 0), byteArrayOf())
        }
    }

    private fun setup(ref: Int, param: ByteArray): ByteArray {
        val requested = if (param.size >= 8) u16(param, 6) else maxPdu
        val agreed = minOf(requested, maxPdu).coerceAtLeast(240.coerceAtMost(maxPdu))
        negotiatedPdu = agreed
        val body = byteArrayOf(
            0xF0.toByte(), 0, 0, 1, 0, 1,
            (agreed ushr 8).toByte(), agreed.toByte(),
        )
        return ack(ref, body, ByteArray(0))
    }

    private fun read(ref: Int, param: ByteArray): ByteArray {
        val items = items(param)
        readJobs.incrementAndGet()
        val payload = ArrayList<Byte>(64)
        for (item in items) {
            val got = readItem(item)
            if (got == null) {
                val code: Byte = if (item.area == 0x84 && !dbs.containsKey(item.db)) 0x0A else 0x05
                payload += code
                payload += 0.toByte()
                payload += 0.toByte()
                payload += 0.toByte()
            } else {
                noteElements(item.elements)
                val bits = if (item.transport == 0x01) item.elements else got.size * 8
                payload += 0xFF.toByte()
                payload += 0x04.toByte()
                payload += (bits ushr 8).toByte()
                payload += bits.toByte()
                for (b in got) payload += b
                if ((got.size and 1) != 0) payload += 0.toByte()
            }
        }
        return ack(ref, byteArrayOf(0x04, items.size.toByte()), payload.toByteArray())
    }

    private fun write(ref: Int, param: ByteArray, data: ByteArray): ByteArray {
        val items = items(param)
        val values = writePayloads(data, items.size)
        val codes = ByteArray(items.size)
        for (i in items.indices) {
            val item = items[i]
            val bytes = values.getOrNull(i)
            codes[i] = if (bytes == null) 0x05 else writeItem(item, bytes)
        }
        return ack(ref, byteArrayOf(0x05, items.size.toByte()), codes)
    }

    private fun readItem(item: Item): ByteArray? {
        val buf = memory(item) ?: return null
        if (item.transport == 0x01) {
            val offset = item.address ushr 3
            val bit = item.address and 7
            if (offset !in buf.indices || item.elements != 1) return null
            val on = ((buf[offset].toInt() ushr bit) and 1) == 1
            return byteArrayOf(if (on) 1 else 0)
        }
        val width = when (item.transport) {
            0x02 -> 1
            0x04 -> 2
            0x06 -> 4
            0x1C, 0x1D -> 2
            else -> return null
        }
        val offset = if (item.transport == 0x1C || item.transport == 0x1D) (item.address ushr 3) * 2 else item.address ushr 3
        val length = item.elements * width
        if (item.elements <= 0 || offset < 0 || offset + length > buf.size) return null
        return buf.copyOfRange(offset, offset + length)
    }

    private fun writeItem(item: Item, bytes: ByteArray): Byte {
        val buf = memory(item) ?: return 0x0A
        if (buf.isEmpty()) return 0x0A
        if (item.transport == 0x01) {
            val offset = item.address ushr 3
            val bit = item.address and 7
            if (offset !in buf.indices || bytes.isEmpty()) return 0x05
            val mask = 1 shl bit
            val cur = buf[offset].toInt() and 0xFF
            buf[offset] = (if (bytes[0].toInt() != 0) cur or mask else cur and mask.inv()).toByte()
            return 0xFF.toByte()
        }
        val offset = if (item.transport == 0x1C || item.transport == 0x1D) (item.address ushr 3) * 2 else item.address ushr 3
        if (offset < 0 || offset + bytes.size > buf.size) return 0x05
        bytes.copyInto(buf, offset)
        return 0xFF.toByte()
    }

    /** Null when the data block does not exist (distinct from a short image, which is an empty optional). */
    private fun memory(item: Item): ByteArray? = when (item.area) {
        0x81 -> inputs
        0x82 -> outputs
        0x83 -> flags
        0x84 -> dbs[item.db]
        0x1C -> counters
        0x1D -> timers
        else -> null
    }

    private fun image(area: Char, db: Int, create: Boolean): ByteArray? = when (area) {
        'I' -> inputs
        'Q' -> outputs
        'M' -> flags
        'T' -> timers
        'C' -> counters
        'D' -> if (create) ensureDb(db) else dbs[db]
        else -> null
    }

    private fun noteElements(elements: Int) {
        while (true) {
            val cur = maxElements.get()
            if (elements <= cur || maxElements.compareAndSet(cur, elements)) return
        }
    }

    private fun items(param: ByteArray): List<Item> {
        if (param.size < 2) return emptyList()
        val count = param[1].toInt() and 0xFF
        val out = ArrayList<Item>(count)
        var o = 2
        repeat(count) {
            if (o + 12 > param.size) return out
            out += Item(
                param[o + 3].toInt() and 0xFF,
                u16(param, o + 4),
                u16(param, o + 6),
                param[o + 8].toInt() and 0xFF,
                ((param[o + 9].toInt() and 0xFF) shl 16) or ((param[o + 10].toInt() and 0xFF) shl 8) or (param[o + 11].toInt() and 0xFF),
            )
            o += 12
        }
        return out
    }

    private fun writePayloads(data: ByteArray, count: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(count)
        var i = 0
        repeat(count) {
            if (i + 4 > data.size) return out
            val bits = u16(data, i + 2)
            val n = (bits + 7) / 8
            val start = i + 4
            if (start + n > data.size) return out
            out += data.copyOfRange(start, start + n)
            i = start + n + (n and 1)
        }
        return out
    }

    private fun rememberTsap(cr: ByteArray) {
        val li = cr[0].toInt() and 0xFF
        val end = minOf(cr.size, 1 + li)
        var i = 7
        while (i + 2 <= end) {
            val code = cr[i].toInt() and 0xFF
            val n = cr[i + 1].toInt() and 0xFF
            if (i + 2 + n > end) break
            if (n >= 2 && code == 0xC1) localTsap = u16(cr, i + 2)
            if (n >= 2 && code == 0xC2) remoteTsap = u16(cr, i + 2)
            i += 2 + n
        }
    }

    private data class Item(val transport: Int, val elements: Int, val db: Int, val area: Int, val address: Int)

    private fun connectionConfirm(dstRef: Int): ByteArray {
        val params = byteArrayOf(0xC0.toByte(), 0x01, 0x0A)
        val li = 6 + params.size
        val cotp = ByteArray(1 + li)
        cotp[0] = li.toByte()
        cotp[1] = 0xD0.toByte()
        put16(cotp, 2, dstRef)
        put16(cotp, 4, 1)
        params.copyInto(cotp, 7)
        return tpkt(cotp)
    }

    private fun dataTransfer(s7: ByteArray): ByteArray {
        val cotp = ByteArray(3 + s7.size)
        cotp[0] = 2
        cotp[1] = 0xF0.toByte()
        cotp[2] = 0x80.toByte()
        s7.copyInto(cotp, 3)
        return cotp
    }

    private fun ack(ref: Int, param: ByteArray, data: ByteArray): ByteArray {
        val msg = ByteArray(12 + param.size + data.size)
        msg[0] = 0x32
        msg[1] = 0x03
        put16(msg, 4, ref)
        put16(msg, 6, param.size)
        put16(msg, 8, data.size)
        param.copyInto(msg, 12)
        if (data.isNotEmpty()) data.copyInto(msg, 12 + param.size)
        return msg
    }

    private fun tpkt(payload: ByteArray): ByteArray {
        val n = payload.size + 4
        val out = ByteArray(n)
        out[0] = 3
        out[3] = n.toByte()
        out[2] = (n ushr 8).toByte()
        payload.copyInto(out, 4)
        return out
    }

    private fun readTpkt(input: DataInputStream): ByteArray {
        val header = ByteArray(4)
        input.readFully(header)
        val len = u16(header, 2)
        if (len < 4 || len > 8192) throw IOException("TPKT length $len")
        val rest = ByteArray(len - 4)
        if (rest.isNotEmpty()) input.readFully(rest)
        return rest
    }

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }
}
