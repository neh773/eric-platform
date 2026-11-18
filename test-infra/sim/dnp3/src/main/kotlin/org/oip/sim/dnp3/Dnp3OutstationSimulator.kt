package org.oip.sim.dnp3

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A DNP3 outstation on loopback TCP for the driver tests, written from the public protocol description.
 * Its frame, transport and object encoding is its own (a bitwise CRC, its own object writer), so it checks
 * the driver's codec rather than sharing it.
 *
 * It keeps static values for binary inputs and outputs, counters and analog inputs and outputs, buffers
 * events by class until a confirm, answers class reads (splitting responses into fragments of at most
 * [maxFragment] octets), select / operate / direct operate on control relay output blocks and analog
 * outputs, enable / disable unsolicited, delay measurement, record current time and time writes, and
 * reports restart, need-time and class IIN bits. Unsolicited responses go out on their own thread.
 */
class Dnp3OutstationSimulator(
    val address: Int = 1024,
    val masterAddress: Int = 1,
    @Volatile var maxFragment: Int = 2048,
) : AutoCloseable {
    class Pt(@Volatile var value: Any, @Volatile var flags: Int)
    class Event(val group: Int, val variation: Int, val index: Int, val data: ByteArray, val dnpClass: Int)
    data class Command(val function: Int, val group: Int, val index: Int, val code: Int, val value: Any?, val onMs: Long, val offMs: Long)

    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val lock = Object()
    private val events = ArrayList<Event>()
    private val unsolicitedExec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dnp3-sim-unsolicited").apply { isDaemon = true } }
    @Volatile private var client: Conn? = null
    @Volatile private var closed = false

    val port: Int get() = server.localPort

    /** True while a master is connected. */
    val hasClient: Boolean get() = client != null

    val binaryInputs = ConcurrentHashMap<Int, Pt>()
    val binaryOutputs = ConcurrentHashMap<Int, Pt>()
    val counters = ConcurrentHashMap<Int, Pt>()
    val analogInputs = ConcurrentHashMap<Int, Pt>()
    val analogOutputs = ConcurrentHashMap<Int, Pt>()

    /** Drop every request without an answer. */
    @Volatile var silent = false
    @Volatile var restart = true
    @Volatile var needTime = false
    @Volatile var supportsUnsolicited = true
    /** Send outstation data as confirmed user data, which the master must acknowledge at the link layer. */
    @Volatile var confirmedUserData = false
    @Volatile var nullUnsolicitedOnConnect = true
    @Volatile var selectTimeoutMs = 2_000L
    @Volatile var unsolicitedRetryMs = 500L
    /** Status echoed for commands on an index: absent means SUCCESS. */
    val commandStatus = ConcurrentHashMap<Int, Int>()
    @Volatile var processingDelayMs = 3

    val requests = ConcurrentHashMap<Int, AtomicInteger>()
    val confirms = AtomicInteger()
    val unsolicitedSent = AtomicInteger()
    val unsolicitedConfirmed = AtomicInteger()
    val linkAcks = AtomicInteger()
    val linkStatusReplies = AtomicInteger()
    val connections = AtomicInteger()
    val commands = CopyOnWriteArrayList<Command>()
    /** Clock values the master wrote (epoch ms), as the outstation set them. */
    val timeWrites = CopyOnWriteArrayList<Long>()
    val enabledClasses: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    private var selected: ByteArray? = null
    private var selectedSeq = -1
    private var selectedAt = 0L
    private var recordedAt = 0L

    init {
        Thread({ acceptLoop() }, "dnp3-sim-accept").apply { isDaemon = true }.start()
        unsolicitedExec.scheduleWithFixedDelay({ runCatching { client?.unsolicitedTick() } }, 20, 20, TimeUnit.MILLISECONDS)
    }

    fun requestCount(function: Int): Int = requests[function]?.get() ?: 0

    fun pendingEvents(): Int = synchronized(lock) { events.size }

    // ---- points -----------------------------------------------------------------------------

    fun binaryInput(index: Int, value: Boolean, flags: Int = ONLINE, dnpClass: Int = 1, event: Boolean = true, timeMs: Long = System.currentTimeMillis()) {
        binaryInputs[index] = Pt(value, flags)
        if (event) addEvent(Event(2, 2, index, byteArrayOf(bin(value, flags).toByte()) + t48(timeMs), dnpClass))
    }

    fun binaryOutput(index: Int, value: Boolean, flags: Int = ONLINE, dnpClass: Int = 1, event: Boolean = false, timeMs: Long = System.currentTimeMillis()) {
        binaryOutputs[index] = Pt(value, flags)
        if (event) addEvent(Event(11, 2, index, byteArrayOf(bin(value, flags).toByte()) + t48(timeMs), dnpClass))
    }

    fun counter(index: Int, value: Long, flags: Int = ONLINE, dnpClass: Int = 3, event: Boolean = true) {
        counters[index] = Pt(value, flags)
        if (event) addEvent(Event(22, 1, index, byteArrayOf(flags.toByte()) + le(value, 4), dnpClass))
    }

    /** A Long is a 32-bit analog, a Double a single-precision float. */
    fun analogInput(index: Int, value: Number, flags: Int = ONLINE, dnpClass: Int = 2, event: Boolean = true, timeMs: Long = System.currentTimeMillis()) {
        val v: Any = if (value is Double || value is Float) value.toDouble() else value.toLong()
        analogInputs[index] = Pt(v, flags)
        if (event) {
            val e = if (v is Double) Event(32, 7, index, byteArrayOf(flags.toByte()) + le(java.lang.Float.floatToIntBits(v.toFloat()).toLong(), 4) + t48(timeMs), dnpClass)
            else Event(32, 3, index, byteArrayOf(flags.toByte()) + le(v as Long, 4) + t48(timeMs), dnpClass)
            addEvent(e)
        }
    }

    fun analogOutput(index: Int, value: Number, flags: Int = ONLINE) {
        analogOutputs[index] = Pt(if (value is Double || value is Float) value.toDouble() else value.toLong(), flags)
    }

    private fun addEvent(e: Event) = synchronized(lock) { events += e }

    fun dropConnection() { client?.close() }

    /** Ask the master for its link status; [linkStatusReplies] counts the answers. */
    fun requestLinkStatus() { client?.sendLink(0x40 or 9) }

    override fun close() {
        closed = true
        runCatching { server.close() }
        client?.close()
        unsolicitedExec.shutdownNow()
        unsolicitedExec.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun acceptLoop() {
        while (!closed) {
            val s = try { server.accept() } catch (_: IOException) { return }
            client?.close()
            val c = Conn(s)
            client = c
            connections.incrementAndGet()
            Thread({ c.run() }, "dnp3-sim-conn").apply { isDaemon = true }.start()
        }
    }

    // ---- one master connection ----------------------------------------------------------------

    private inner class Conn(val socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        private var rxBuffer: ByteArrayOutputStream? = null
        private var txSeq = 0
        private var unsSeq = 0
        // Solicited fragments still to send, each after the master confirms the one before.
        private val queued = ArrayDeque<ByteArray>()
        private var awaitingSolicited = -1
        private var solicitedEvents: List<Event> = emptyList()
        private var unsInFlight: List<Event>? = null
        private var unsSentAt = 0L
        private var nullUnsolicitedDue = nullUnsolicitedOnConnect

        fun close() { runCatching { socket.close() } }

        fun run() {
            try {
                val input = socket.getInputStream()
                while (!closed) {
                    val frame = readFrame(input) ?: continue
                    if (silent) continue
                    onFrame(frame)
                }
            } catch (_: IOException) {
            } finally {
                close()
                if (client === this) client = null
            }
        }

        private fun onFrame(f: Frame) {
            if (f.dest != address || f.src != masterAddress) return
            val primary = f.control and 0x40 != 0
            val function = f.control and 0x0F
            if (!primary) {
                if (function == 11) linkStatusReplies.incrementAndGet()
                if (function == 0) linkAcks.incrementAndGet()
                return
            }
            when (function) {
                9 -> { sendLink(11); return }
                0, 2 -> { sendLink(0); return }
                3 -> sendLink(0)
                4 -> Unit
                else -> { sendLink(15); return }
            }
            if (f.data.isEmpty()) return
            val h = f.data[0].toInt() and 0xFF
            val fir = h and 0x40 != 0
            val fin = h and 0x80 != 0
            if (fir) rxBuffer = ByteArrayOutputStream()
            val buf = rxBuffer ?: return
            buf.write(f.data, 1, f.data.size - 1)
            if (!fin) return
            rxBuffer = null
            synchronized(lock) { onRequest(buf.toByteArray()) }
        }

        private fun onRequest(apdu: ByteArray) {
            if (apdu.size < 2) return
            val control = apdu[0].toInt() and 0xFF
            val seq = control and 0x0F
            val function = apdu[1].toInt() and 0xFF
            val objects = apdu.copyOfRange(2, apdu.size)
            requests.computeIfAbsent(function) { AtomicInteger() }.incrementAndGet()
            if (function == 0) { confirmed(seq, control and 0x10 != 0); return }
            queued.clear(); awaitingSolicited = -1; solicitedEvents = emptyList()
            when (function) {
                1 -> read(seq, objects)
                2 -> respond(seq, write(objects))
                3, 4, 5, 6 -> command(seq, function, objects)
                20, 21 -> respond(seq, unsolicitedControl(function == 20, objects))
                23 -> respond(seq, 0, byteArrayOf(52, 2, 0x07, 1) + le(processingDelayMs.toLong(), 2))
                24 -> { recordedAt = System.currentTimeMillis(); respond(seq, 0) }
                else -> respond(seq, NO_FUNC)
            }
        }

        private fun confirmed(seq: Int, uns: Boolean) {
            confirms.incrementAndGet()
            if (uns) {
                if (seq != unsSeq) return
                if (nullUnsolicitedDue) nullUnsolicitedDue = false
                unsInFlight?.let { events.removeAll(it.toSet()) }
                unsInFlight = null
                unsolicitedConfirmed.incrementAndGet()
                unsSeq = (unsSeq + 1) and 0x0F
                return
            }
            if (seq != awaitingSolicited) return
            awaitingSolicited = -1
            if (queued.isEmpty()) {
                events.removeAll(solicitedEvents.toSet())
                solicitedEvents = emptyList()
                return
            }
            sendQueued()
        }

        // ---- READ ----------------------------------------------------------------------------

        private fun read(seq: Int, objects: ByteArray) {
            val classes = ArrayList<Int>()
            var p = 0
            var bad = 0
            while (p + 3 <= objects.size) {
                val g = objects[p].toInt() and 0xFF; val v = objects[p + 1].toInt() and 0xFF; val q = objects[p + 2].toInt() and 0xFF
                p += 3
                if (g == 60 && v in 1..4 && q == 0x06) classes += v - 1 else { bad = OBJECT_UNKNOWN; break }
            }
            val blocks = ArrayList<Block>()
            val included = ArrayList<Event>()
            for (c in classes) {
                if (c == 0) blocks += staticBlocks()
                else {
                    val evs = events.filter { it.dnpClass == c && it !in included }
                    included += evs
                    blocks += eventBlocks(evs)
                }
            }
            solicitedEvents = included
            val fragments = pack(blocks)
            for ((i, body) in fragments.withIndex()) {
                val last = i == fragments.size - 1
                val con = !last || included.isNotEmpty()
                val s = (seq + i) and 0x0F
                val iin = iin(excluding = included) or bad
                queued.addLast(apdu(fir = i == 0, fin = last, con = con, uns = false, seq = s, function = 129, iin = iin, body = body))
            }
            sendQueued()
        }

        private fun sendQueued() {
            val next = queued.removeFirstOrNull() ?: return
            val con = next[0].toInt() and 0x20 != 0
            awaitingSolicited = if (con) next[0].toInt() and 0x0F else -1
            sendFragment(next)
            if (!con) sendQueued()
        }

        // ---- WRITE, unsolicited control, commands --------------------------------------------

        private fun write(objects: ByteArray): Int {
            if (objects.size == 6 && objects[0].toInt() == 80 && objects[1].toInt() == 1 && objects[2].toInt() == 0 && objects[3].toInt() == 7 && objects[4].toInt() == 7) {
                if (objects[5].toInt() and 1 == 0) restart = false
                return 0
            }
            if (objects.size == 10 && objects[0].toInt() == 50 && (objects[1].toInt() == 1 || objects[1].toInt() == 3) && objects[2].toInt() == 0x07 && objects[3].toInt() == 1) {
                val t = u(objects, 4, 6)
                timeWrites += if (objects[1].toInt() == 3) t + (System.currentTimeMillis() - recordedAt) else t
                needTime = false
                return 0
            }
            return PARAMETER_ERROR
        }

        private fun unsolicitedControl(enable: Boolean, objects: ByteArray): Int {
            if (!supportsUnsolicited) return NO_FUNC
            var p = 0
            while (p + 3 <= objects.size) {
                val g = objects[p].toInt(); val v = objects[p + 1].toInt(); val q = objects[p + 2].toInt()
                if (g != 60 || v !in 2..4 || q != 0x06) return PARAMETER_ERROR
                if (enable) enabledClasses += v - 1 else enabledClasses -= v - 1
                p += 3
            }
            return 0
        }

        private fun command(seq: Int, function: Int, objects: ByteArray) {
            val now = System.currentTimeMillis()
            val operateWithoutSelect = function == 4 && (!objects.contentEquals(selected ?: ByteArray(0)) ||
                seq != ((selectedSeq + 1) and 0x0F) || now - selectedAt > selectTimeoutMs)
            val echo = objects.copyOf()
            var p = 0
            var iinBits = 0
            while (p + 3 <= objects.size && iinBits == 0) {
                val g = objects[p].toInt() and 0xFF; val v = objects[p + 1].toInt() and 0xFF; val q = objects[p + 2].toInt() and 0xFF
                val size = when { g == 12 && v == 1 -> 11; g == 41 && v == 1 -> 5; g == 41 && v == 2 -> 3; g == 41 && v == 3 -> 5; g == 41 && v == 4 -> 9; else -> -1 }
                val idxBytes = when (q) { 0x17 -> 1; 0x28 -> 2; else -> -1 }
                if (size < 0 || idxBytes < 0) { iinBits = PARAMETER_ERROR; break }
                val count = u(objects, p + 3, idxBytes).toInt()
                p += 3 + idxBytes
                repeat(count) {
                    val index = u(objects, p, idxBytes).toInt()
                    val at = p + idxBytes
                    val statusAt = at + size - 1
                    val status = when {
                        operateWithoutSelect -> 2
                        g == 12 && binaryOutputs[index] == null -> 4
                        g == 41 && analogOutputs[index] == null -> 4
                        else -> commandStatus[index] ?: 0
                    }
                    echo[statusAt] = status.toByte()
                    if (status == 0 && function != 3) apply(function, g, v, index, objects, at)
                    p = at + size
                }
            }
            if (function == 3) { selected = objects; selectedSeq = seq; selectedAt = now } else selected = null
            if (function == 6) return
            respond(seq, iinBits, if (iinBits == 0) echo else ByteArray(0))
        }

        private fun apply(function: Int, g: Int, v: Int, index: Int, b: ByteArray, at: Int) {
            if (g == 12) {
                val code = b[at].toInt() and 0xFF
                val op = code and 0x0F
                val tcc = code ushr 6
                val on = u(b, at + 2, 4); val off = u(b, at + 6, 4)
                commands += Command(function, g, index, code, null, on, off)
                val value = when {
                    op == 3 -> true
                    op == 4 -> false
                    op == 1 && tcc == 1 -> true
                    op == 1 && tcc == 2 -> false
                    else -> null
                }
                if (value != null) binaryOutput(index, value, binaryOutputs[index]?.flags ?: ONLINE, event = true)
                return
            }
            val value: Any = when (v) {
                1 -> u(b, at, 4).toInt().toLong()
                2 -> u(b, at, 2).toShort().toLong()
                3 -> java.lang.Float.intBitsToFloat(u(b, at, 4).toInt()).toDouble()
                else -> java.lang.Double.longBitsToDouble(u(b, at, 8))
            }
            commands += Command(function, g, index, 0, value, 0, 0)
            analogOutput(index, value as Number)
        }

        // ---- unsolicited ---------------------------------------------------------------------

        fun unsolicitedTick() {
            synchronized(lock) { tick() }
        }

        private fun tick() {
            if (silent) return
            val now = System.currentTimeMillis()
            unsInFlight?.let { inFlight ->
                if (now - unsSentAt < unsolicitedRetryMs) return
                sendUnsolicited(inFlight, now)
                return
            }
            if (nullUnsolicitedDue) {
                unsInFlight = emptyList()
                sendUnsolicited(emptyList(), now)
                return
            }
            if (!supportsUnsolicited || enabledClasses.isEmpty()) return
            val evs = events.filter { it.dnpClass in enabledClasses }
            if (evs.isEmpty()) return
            unsInFlight = evs
            sendUnsolicited(evs, now)
        }

        private fun sendUnsolicited(evs: List<Event>, now: Long) {
            unsSentAt = now
            val body = pack(eventBlocks(evs)).first()
            unsolicitedSent.incrementAndGet()
            sendFragment(apdu(fir = true, fin = true, con = true, uns = true, seq = unsSeq, function = 130, iin = iin(excluding = evs), body = body))
        }

        // ---- encoding ------------------------------------------------------------------------

        private fun respond(seq: Int, iinBits: Int, body: ByteArray = ByteArray(0)) =
            sendFragment(apdu(fir = true, fin = true, con = false, uns = false, seq = seq, function = 129, iin = iin(emptyList()) or iinBits, body = body))

        private fun iin(excluding: List<Event>): Int {
            var v = 0
            if (restart) v = v or 0x80
            if (needTime) v = v or 0x10
            for (e in events) if (e !in excluding) v = v or (1 shl e.dnpClass)
            return v
        }

        private fun apdu(fir: Boolean, fin: Boolean, con: Boolean, uns: Boolean, seq: Int, function: Int, iin: Int, body: ByteArray): ByteArray {
            val c = (if (fir) 0x80 else 0) or (if (fin) 0x40 else 0) or (if (con) 0x20 else 0) or (if (uns) 0x10 else 0) or (seq and 0x0F)
            return byteArrayOf(c.toByte(), function.toByte(), iin.toByte(), (iin ushr 8).toByte()) + body
        }

        private fun sendFragment(apdu: ByteArray) {
            var i = 0
            do {
                val n = minOf(249, apdu.size - i)
                val th = (if (i + n >= apdu.size) 0x80 else 0) or (if (i == 0) 0x40 else 0) or txSeq
                txSeq = (txSeq + 1) and 0x3F
                val seg = byteArrayOf(th.toByte()) + apdu.copyOfRange(i, i + n)
                sendFrame((if (confirmedUserData) 3 else 4) or 0x40, seg)
                i += n
            } while (i < apdu.size)
        }

        fun sendLink(control: Int) = sendFrame(control, ByteArray(0))

        private fun sendFrame(control: Int, data: ByteArray) {
            val f = ByteArrayOutputStream()
            val header = byteArrayOf(0x05, 0x64, (5 + data.size).toByte(), control.toByte(),
                masterAddress.toByte(), (masterAddress ushr 8).toByte(), address.toByte(), (address ushr 8).toByte())
            f.write(header); f.write(crcBytes(header, 0, 8))
            var i = 0
            while (i < data.size) {
                val n = minOf(16, data.size - i)
                f.write(data, i, n); f.write(crcBytes(data, i, i + n))
                i += n
            }
            try {
                synchronized(out) { out.write(f.toByteArray()); out.flush() }
            } catch (_: SocketException) {
            } catch (_: IOException) {
            }
        }
    }

    // ---- blocks, packing ----------------------------------------------------------------------

    private class Block(val group: Int, val variation: Int, val items: List<Pair<Int, ByteArray>>, val startStop: Boolean)

    private fun staticBlocks(): List<Block> {
        val out = ArrayList<Block>()
        fun add(g: Int, v: Int, map: Map<Int, Pt>, filter: (Pt) -> Boolean, enc: (Pt) -> ByteArray) {
            val items = map.entries.filter { filter(it.value) }.sortedBy { it.key }.map { it.key to enc(it.value) }
            if (items.isEmpty()) return
            val contiguous = items.last().first - items.first().first == items.size - 1
            out += Block(g, v, items, startStop = contiguous)
        }
        add(1, 2, binaryInputs, { true }) { byteArrayOf(bin(it.value as Boolean, it.flags).toByte()) }
        add(10, 2, binaryOutputs, { true }) { byteArrayOf(bin(it.value as Boolean, it.flags).toByte()) }
        add(20, 1, counters, { true }) { byteArrayOf(it.flags.toByte()) + le(it.value as Long, 4) }
        add(30, 1, analogInputs, { it.value is Long }) { byteArrayOf(it.flags.toByte()) + le(it.value as Long, 4) }
        add(30, 5, analogInputs, { it.value is Double }) { byteArrayOf(it.flags.toByte()) + le(java.lang.Float.floatToIntBits((it.value as Double).toFloat()).toLong(), 4) }
        add(40, 1, analogOutputs, { it.value is Long }) { byteArrayOf(it.flags.toByte()) + le(it.value as Long, 4) }
        add(40, 3, analogOutputs, { it.value is Double }) { byteArrayOf(it.flags.toByte()) + le(java.lang.Float.floatToIntBits((it.value as Double).toFloat()).toLong(), 4) }
        return out
    }

    private fun eventBlocks(evs: List<Event>): List<Block> =
        evs.groupBy { it.group to it.variation }.map { (gv, list) -> Block(gv.first, gv.second, list.map { it.index to it.data }, startStop = false) }

    /** Object bytes of each response fragment, filling each up to [maxFragment] octets. */
    private fun pack(blocks: List<Block>): List<ByteArray> {
        val room = maxFragment - 4
        val fragments = ArrayList<ByteArray>()
        var cur = ByteArrayOutputStream()
        for (b in blocks) {
            val wide = b.items.any { it.first > 255 }
            var i = 0
            while (i < b.items.size) {
                val headerSize = if (b.startStop) 7 else if (wide) 5 else 4
                val perItem = { k: Int -> b.items[k].second.size + if (b.startStop) 0 else if (wide) 2 else 1 }
                if (cur.size() + headerSize + perItem(i) > room && cur.size() > 0) { fragments += cur.toByteArray(); cur = ByteArrayOutputStream() }
                var n = 0
                var used = cur.size() + headerSize
                while (i + n < b.items.size && (n == 0 || used + perItem(i + n) <= room)) { used += perItem(i + n); n++ }
                val chunk = b.items.subList(i, i + n)
                cur.write(b.group); cur.write(b.variation)
                when {
                    b.startStop -> { cur.write(0x01); cur.write(le(chunk.first().first.toLong(), 2)); cur.write(le(chunk.last().first.toLong(), 2)) }
                    wide -> { cur.write(0x28); cur.write(le(n.toLong(), 2)) }
                    else -> { cur.write(0x17); cur.write(n) }
                }
                for ((index, data) in chunk) {
                    if (!b.startStop) cur.write(le(index.toLong(), if (wide) 2 else 1))
                    cur.write(data)
                }
                i += n
            }
        }
        fragments += cur.toByteArray()
        return fragments
    }

    // ---- frames -------------------------------------------------------------------------------

    private class Frame(val control: Int, val dest: Int, val src: Int, val data: ByteArray)

    private fun readFrame(input: InputStream): Frame? {
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            if (prev == 0x05 && b == 0x64) break
            prev = b
        }
        val h = ByteArray(10)
        h[0] = 0x05; h[1] = 0x64
        full(input, h, 2, 8)
        if (!crcBytes(h, 0, 8).contentEquals(h.copyOfRange(8, 10))) return null
        val len = (h[2].toInt() and 0xFF) - 5
        if (len < 0) return null
        val data = ByteArrayOutputStream()
        var left = len
        while (left > 0) {
            val n = minOf(16, left)
            val blk = ByteArray(n + 2)
            full(input, blk, 0, n + 2)
            if (!crcBytes(blk, 0, n).contentEquals(blk.copyOfRange(n, n + 2))) return null
            data.write(blk, 0, n)
            left -= n
        }
        return Frame(h[3].toInt() and 0xFF, u(h, 4, 2).toInt(), u(h, 6, 2).toInt(), data.toByteArray())
    }

    private fun full(input: InputStream, b: ByteArray, off: Int, len: Int) {
        var n = 0
        while (n < len) {
            val r = input.read(b, off + n, len - n)
            if (r < 0) throw IOException("closed")
            n += r
        }
    }

    companion object {
        const val ONLINE = 0x01
        const val RESTART = 0x02
        const val COMM_LOST = 0x04
        const val LOCAL_FORCED = 0x10
        private const val NO_FUNC = 0x0100
        private const val OBJECT_UNKNOWN = 0x0200
        private const val PARAMETER_ERROR = 0x0400

        /** CRC-16 of the frame blocks, computed bit by bit: polynomial 0x3D65 in reversed bit order, then complemented. */
        fun crc(b: ByteArray, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) {
                var x = b[i].toInt() and 0xFF
                repeat(8) {
                    val mix = (crc xor x) and 1
                    crc = crc ushr 1
                    if (mix != 0) crc = crc xor 0xA6BC
                    x = x ushr 1
                }
            }
            return crc.inv() and 0xFFFF
        }

        private fun crcBytes(b: ByteArray, from: Int, to: Int): ByteArray = crc(b, from, to).let { byteArrayOf(it.toByte(), (it ushr 8).toByte()) }
        private fun bin(v: Boolean, flags: Int) = (flags and 0x7F) or (if (v) 0x80 else 0)
        private fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }
        private fun t48(ms: Long) = le(ms, 6)
        private fun u(b: ByteArray, at: Int, n: Int): Long { var v = 0L; for (k in n - 1 downTo 0) v = (v shl 8) or (b[at + k].toLong() and 0xFF); return v }
    }
}
