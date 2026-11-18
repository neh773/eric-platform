package org.oip.sim.logix

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process ControlLogix / CompactLogix stand-in.
 *
 * Speaks EtherNet/IP encapsulation (TCP 44818) and the Logix tag services in the public
 * Rockwell manual 1756-PM020, "Logix 5000 Controllers Data Access":
 * https://literature.rockwellautomation.com/idc/groups/literature/documents/pm/1756-pm020_-en-p.pdf
 *
 * RegisterSession, SendRRData, SendUnitData, Class 3 Forward Open / Large Forward Open,
 * Read/Write Tag and the fragmented forms, Multiple Service Packet, Symbol object
 * Get_Instance_Attribute_List (class 0x6B, service 0x55), and Template object
 * Get_Attribute_List plus Read Template (class 0x6C). No third-party protocol stack.
 *
 * Write Tag Fragmented (0x53) copies the fragment at the UDINT byte offset and keeps the element
 * count of the whole value. Symbol responses include three UDINT dimensions (attribute 8) so a
 * BOOL array can be browsed as flags[word].bit. Alias tags are symbols that resolve to another
 * tag on Read/Write Tag. Add-On Instruction instances are templates with EnableIn and EnableOut.
 * Symbol class attribute 4 is a catalog generation that increments when a tag is added or a
 * template definition changes, which is what Automatic Rebrowse watches. 1756-PM020 does not
 * name that attribute; it is the simulator's signal for the the reference product setting.
 *
 * A template member can itself be a structure ([MemberSpec.structure]), so an Add-On Instruction
 * can hold another AOI or a UDT. Read/Write Tag also accepts symbol instance addressing from
 * 1756-PM020: the path starts with Symbol class 0x6B and the tag's instance id (a program tag is
 * the program's instance, then the tag's), followed by the usual member and element segments.
 *
 * BOOL arrays are packed little-endian in 32-bit words, which is how the public the reference platform
 * Logix driver addresses them (`flags[0].0` .. `flags[0].31`, then `flags[1].0`).
 *
 * [route] makes the simulator stand for a controller behind bridge modules: Forward Open and
 * Unconnected Send (0x52 to the Connection Manager) are accepted only when their route path is
 * exactly that port/link list, the way a chain of modules would forward them. A wrong hop is
 * Connection Manager status 0x01 with extended status 0x0312 (link address not valid).
 * [refuseConnections] answers every Forward Open with 0x01 / 0x0113 (out of connections), so a
 * client has to use unconnected messages. doc source: FEATURES DEV-009
 *
 * [firmware] picks the services the stand-in answers. [SimFirmware.V20] has no Large Forward
 * Open, no symbol instance addressing and no catalog generation attribute (doc source: FEATURES
 * DEV-021, DEV-023). [SimFirmware.MICRO800] has those limits too, global variables only, no
 * Template object (structures are reachable by member path but not described), CIP
 * SHORT_STRING strings ([shortString]) and the unsigned and bit-string types the Micro800 page
 * lists (doc source: FEATURES DEV-017). These are the simulator's model of the public pages,
 * not a capture of a real controller.
 */
class LogixSimulator(
    val slot: Int = 0,
    val route: String? = null,
    val firmware: SimFirmware = SimFirmware.V21,
) : AutoCloseable {
    private val memory = Memory()
    private val clients = ArrayList<Socket>()
    private var server: ServerSocket? = null
    private var accept: Thread? = null
    @Volatile private var running = false
    @Volatile var holdReads: Boolean = false

    val port: Int
    val cipPackets = AtomicLong()
    val mspPackets = AtomicLong()
    val maxEmbedded = AtomicInteger()
    val fragmentedReads = AtomicLong()
    val fragmentedWrites = AtomicLong()
    val sessionsOpened = AtomicInteger()

    /** Read/Write Tag requests whose root was named by symbol instance id. */
    val instanceAddressed = AtomicLong()

    /** Read/Write Tag requests whose root was an ANSI extended symbolic segment. */
    val symbolAddressed = AtomicLong()

    /** 1-based Write Tag Fragmented call to reject. 0 rejects none. The rejected fragment is not applied. */
    @Volatile var failWriteFragment: Int = 0

    /** Answer every Forward Open with 0x01 / 0x0113 (no connections free). */
    @Volatile var refuseConnections: Boolean = false

    val forwardOpens = AtomicLong()
    val largeForwardOpens = AtomicLong()
    val unconnectedSends = AtomicLong()
    val generationRequests = AtomicLong()
    val templateRequests = AtomicLong()

    /** Route path bytes of the last Forward Open and the last Unconnected Send. */
    @Volatile var lastForwardOpenRoute: ByteArray = ByteArray(0)
        private set
    @Volatile var lastUnconnectedRoute: ByteArray = ByteArray(0)
        private set

    private val expectedRoute: List<Seg.Port>? = route?.let { parseRouteText(it) }

    init {
        require(slot in 0..31) { "slot $slot" }
        memory.productName = when (firmware) {
            SimFirmware.V21 -> "OIP Logix Simulator"
            SimFirmware.V20 -> "OIP Logix Simulator v20"
            SimFirmware.MICRO800 -> "OIP Micro800 Simulator"
        }
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress("127.0.0.1", 0))
        port = bound.localPort
        server = bound
        start()
    }

    @Synchronized
    fun start() {
        if (running) return
        val existing = server
        val listen = if (existing != null && !existing.isClosed) existing else {
            ServerSocket().also {
                it.reuseAddress = true
                it.bind(InetSocketAddress("127.0.0.1", port))
                server = it
            }
        }
        running = true
        accept = Thread({
            while (running && !listen.isClosed) {
                val client = try {
                    listen.accept()
                } catch (_: Exception) {
                    break
                }
                client.tcpNoDelay = true
                synchronized(clients) { clients += client }
                Thread({ serve(client) }, "logix-sim").apply { isDaemon = true }.start()
            }
        }, "logix-sim-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        val open = synchronized(clients) { clients.toList().also { clients.clear() } }
        open.forEach { runCatching { it.close() } }
        runCatching { server?.close() }
        accept?.join(1_000)
        accept = null
        server = null
    }

    override fun close() = stop()

    fun resetStats() {
        forwardOpens.set(0)
        largeForwardOpens.set(0)
        unconnectedSends.set(0)
        generationRequests.set(0)
        templateRequests.set(0)
        cipPackets.set(0)
        mspPackets.set(0)
        maxEmbedded.set(0)
        fragmentedReads.set(0)
        fragmentedWrites.set(0)
        instanceAddressed.set(0)
        symbolAddressed.set(0)
    }

    private fun scope(program: String?): String? {
        require(program == null || firmware != SimFirmware.MICRO800) { "a Micro800 has global variables only" }
        return program
    }

    fun bool(name: String, value: Boolean, program: String? = null) {
        memory.putAtomic(scope(program), name, Ab.BOOL, if (value) 1 else 0)
    }

    /** A Micro800 STRING: CIP SHORT_STRING, one length byte and up to 80 characters. */
    fun shortString(name: String, value: String) {
        memory.putShortString(name, value)
    }

    fun atomic(name: String, type: Int, value: Number, program: String? = null) {
        memory.putAtomic(scope(program), name, type, value)
    }

    fun array(name: String, type: Int, values: List<Number>, program: String? = null) {
        memory.putArray(scope(program), name, type, intArrayOf(values.size), values)
    }

    fun array2(name: String, type: Int, rows: Int, cols: Int, values: List<Number>, program: String? = null) {
        require(values.size == rows * cols)
        memory.putArray(scope(program), name, type, intArrayOf(rows, cols), values)
    }

    /** [bits] BOOL elements, packed into DWORDs. [set] lists the BOOL indexes that are true. */
    fun boolArray(name: String, bits: Int, set: Set<Int> = emptySet(), program: String? = null) {
        memory.putBoolArray(scope(program), name, bits, set)
    }

    fun string(name: String, value: String, capacity: Int = 82, program: String? = null) {
        memory.putString(scope(program), name, value, capacity)
    }

    fun defineUdt(name: String, members: List<MemberSpec>): Int = memory.defineUdt(name, members)

    /** An Add-On Instruction template. Logix AOI instances expose EnableIn and EnableOut plus the parameters. */
    fun defineAoi(name: String, parameters: List<MemberSpec>): Int =
        memory.defineUdt(name, listOf(MemberSpec("EnableIn", Ab.BOOL), MemberSpec("EnableOut", Ab.BOOL)) + parameters)

    /** Replace a template's members and bump the catalog generation. The template instance id stays. */
    fun reviseUdt(name: String, members: List<MemberSpec>) = memory.reviseUdt(name, members)

    /**
     * An alias symbol. [target] is a tag in the same scope, or `Tag.Member`.
     * Read Tag and Write Tag of the alias name reach the target.
     */
    fun alias(name: String, target: String, program: String? = null) = memory.alias(scope(program), name, target)

    fun structure(tag: String, template: String, values: Map<String, Any?>, program: String? = null) {
        memory.putStructure(scope(program), tag, template, values)
    }

    fun readOnly(name: String, program: String? = null) {
        memory.tag(program, name).writable = false
    }

    fun set(name: String, value: Any, program: String? = null) {
        memory.set(program, name, value)
    }

    fun get(name: String, program: String? = null): Any? = memory.get(program, name)

    private fun serve(socket: Socket) {
        val conn = Conn()
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            while (running && !socket.isClosed) {
                val header = readFully(input, 24) ?: return
                val command = u16(header, 0)
                val length = u16(header, 2)
                val session = u32(header, 4)
                val context = header.copyOfRange(12, 20)
                if (length > 65535) return
                val body = if (length == 0) ByteArray(0) else readFully(input, length) ?: return
                val reply = handle(conn, command, session, body)
                maybeHold(socket, reply.holdService)
                if (socket.isClosed) return
                output.write(encap(reply.command, reply.status, conn.session, context, reply.body))
                output.flush()
            }
        } catch (_: Exception) {
            // Client closed or the process is stopping.
        } finally {
            runCatching { socket.close() }
            synchronized(clients) { clients.remove(socket) }
        }
    }

    private fun maybeHold(socket: Socket, service: Int) {
        if (service != Ab.READ && service != Ab.READ_FRAG && service != Ab.MSP) return
        while (holdReads && running && !socket.isClosed) {
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun handle(conn: Conn, command: Int, session: Long, body: ByteArray): EncapReply {
        if (command != Ab.REGISTER && conn.session == 0L) {
            return EncapReply(command, 0x0064, ByteArray(0), 0)
        }
        return when (command) {
            Ab.REGISTER -> {
                if (body.size < 4 || u16(body, 0) != 1) return EncapReply(command, 0x0069, body, 0)
                conn.session = memory.nextSession()
                sessionsOpened.incrementAndGet()
                EncapReply(command, 0, byteArrayOf(1, 0, 0, 0), 0)
            }
            Ab.UNREGISTER -> {
                conn.session = 0
                conn.o2t = 0
                EncapReply(command, 0, ByteArray(0), 0)
            }
            Ab.SEND_RR -> cipEncap(conn, body, connected = false)
            Ab.SEND_UNIT -> cipEncap(conn, body, connected = true)
            else -> EncapReply(command, 0x0001, ByteArray(0), 0)
        }
    }

    private fun cipEncap(conn: Conn, body: ByteArray, connected: Boolean): EncapReply {
        if (body.size < 6) return EncapReply(if (connected) Ab.SEND_UNIT else Ab.SEND_RR, 0x0065, ByteArray(0), 0)
        val items = parseItems(body, 6)
        val cip = if (connected) {
            val addr = items.firstOrNull { it.type == 0x00A1 } ?: return failEncap(connected)
            if (addr.data.size < 4 || u32(addr.data, 0) != conn.o2t) return failEncap(connected)
            val data = items.firstOrNull { it.type == 0x00B1 } ?: return failEncap(connected)
            if (data.data.size < 2) return failEncap(connected)
            conn.replySequence = u16(data.data, 0)
            data.data.copyOfRange(2, data.data.size)
        } else {
            val data = items.firstOrNull { it.type == 0x00B2 } ?: return failEncap(connected)
            data.data
        }
        cipPackets.incrementAndGet()
        val budget = if (connected) (conn.cipSize - 2).coerceAtLeast(64) else UNCONNECTED_CIP
        val reply = cip(conn, cip, budget)
        val payload = if (connected) {
            val data = ByteArray(2 + reply.bytes.size)
            put16(data, 0, conn.replySequence)
            reply.bytes.copyInto(data, 2)
            cpfConnected(conn.t2o, data)
        } else {
            cpfUnconnected(reply.bytes)
        }
        val command = if (connected) Ab.SEND_UNIT else Ab.SEND_RR
        return EncapReply(command, 0, payload, reply.holdService)
    }

    private fun failEncap(connected: Boolean) =
        EncapReply(if (connected) Ab.SEND_UNIT else Ab.SEND_RR, 0x0065, ByteArray(0), 0)

    private fun cip(conn: Conn, message: ByteArray, budget: Int): CipOut {
        if (message.size < 2) return CipOut(cipReply(0, 0x13, byteArrayOf()), 0)
        val service = message[0].toInt() and 0xFF
        val words = message[1].toInt() and 0xFF
        val pathBytes = words * 2
        if (message.size < 2 + pathBytes) return CipOut(cipReply(service, 0x13, byteArrayOf()), service)
        val path = message.copyOfRange(2, 2 + pathBytes)
        val data = message.copyOfRange(2 + pathBytes, message.size)
        val segs = try {
            parsePath(path)
        } catch (_: PathException) {
            return CipOut(cipReply(service, 0x04, ext(0)), service)
        }
        val stripped = try {
            stripRoute(segs)
        } catch (_: CipFail) {
            return CipOut(cipReply(service, 0x05, ext(0)), service)
        }
        val bytes = try {
            dispatch(conn, service, stripped, data, budget)
        } catch (f: CipFail) {
            return CipOut(cipReply(service, f.status, if (f.ext == 0) byteArrayOf() else ext(f.ext)), service)
        }
        return CipOut(bytes, service)
    }

    private fun dispatch(conn: Conn, service: Int, path: List<Seg>, data: ByteArray, budget: Int): ByteArray {
        if (service == Ab.UNCONNECTED_SEND && classInst(path, Ab.CONNECTION_MANAGER, 1)) {
            return unconnectedSend(conn, data)
        }
        if (pathClass(path) == Ab.TEMPLATE) {
            templateRequests.incrementAndGet()
            if (firmware == SimFirmware.MICRO800) throw CipFail(0x08)
        }
        if (service == Ab.MSP && classInst(path, Ab.MESSAGE_ROUTER, 1)) {
            return multiple(conn, data, budget)
        }
        if ((service == Ab.FORWARD_OPEN || service == Ab.FORWARD_LARGE) && classInst(path, Ab.CONNECTION_MANAGER, 1)) {
            return forwardOpen(conn, service, data)
        }
        if (service == Ab.FORWARD_CLOSE && classInst(path, Ab.CONNECTION_MANAGER, 1)) {
            conn.o2t = 0
            return cipReply(service, 0, byteArrayOf())
        }
        if (service == Ab.GET_ALL && classInst(path, Ab.IDENTITY, 1)) {
            return cipReply(service, 0, memory.identity())
        }
        if (service == Ab.GET_LIST && pathClass(path) == Ab.TEMPLATE) {
            val instance = pathInstance(path)
            return cipReply(service, 0, memory.templateAttributes(instance, data))
        }
        if (service == Ab.GET_INSTANCES && pathClass(path) == Ab.SYMBOL) {
            return symbols(path, data, budget)
        }
        if ((service == Ab.READ || service == Ab.READ_FRAG) && pathClass(path) == Ab.TEMPLATE) {
            val instance = pathInstance(path)
            if (service == Ab.READ_FRAG) fragmentedReads.incrementAndGet()
            val offset = if (data.size >= 4) u32(data, 0).toInt() else 0
            val count = if (data.size >= 6) u16(data, 4) else 0
            val (chunk, more) = memory.readTemplate(instance, offset, count, budget)
            return cipReply(service, if (more) 0x06 else 0, chunk)
        }
        if (service == Ab.GET_ATTR && pathClass(path) == Ab.SYMBOL && pathInstance(path) == 0) {
            generationRequests.incrementAndGet()
            if (firmware != SimFirmware.V21) throw CipFail(0x14)
            if (pathAttr(path) != 4) throw CipFail(0x14)
            val bytes = ByteArray(4)
            put32(bytes, 0, memory.generation().toLong())
            return cipReply(service, 0, bytes)
        }
        if (service == Ab.READ || service == Ab.WRITE || service == Ab.READ_FRAG || service == Ab.WRITE_FRAG) {
            if (service == Ab.READ_FRAG) fragmentedReads.incrementAndGet()
            return tag(service, path, data, budget)
        }
        throw CipFail(0x08)
    }

    /**
     * Unconnected Send: timing bytes, message size, the message (padded to even), route size in
     * words, a reserved byte, then the route. The embedded reply is returned as it is.
     */
    private fun unconnectedSend(conn: Conn, data: ByteArray): ByteArray {
        if (data.size < 4) throw CipFail(0x13)
        val size = u16(data, 2)
        if (4 + size > data.size) throw CipFail(0x13)
        val message = data.copyOfRange(4, 4 + size)
        var p = 4 + size + (size % 2)
        if (p + 2 > data.size) throw CipFail(0x13)
        val words = data[p].toInt() and 0xFF
        p += 2
        if (p + words * 2 > data.size) throw CipFail(0x13)
        val routeBytes = data.copyOfRange(p, p + words * 2)
        unconnectedSends.incrementAndGet()
        lastUnconnectedRoute = routeBytes
        val segs = try {
            parsePath(routeBytes)
        } catch (_: PathException) {
            throw CipFail(0x01, 0x0315)
        }
        if (segs.any { it !is Seg.Port }) throw CipFail(0x01, 0x0315)
        checkRoute(segs.filterIsInstance<Seg.Port>(), allowDirect = false)
        return cip(conn, message, UNCONNECTED_CIP).bytes
    }

    private fun symbols(path: List<Seg>, data: ByteArray, budget: Int): ByteArray {
        if (data.size < 2) throw CipFail(0x13)
        val classes = path.withIndex().filter { it.value is Seg.ClassId }
        val (tags, start) = if (classes.size >= 2) {
            val progInst = (path[classes[0].index + 1] as? Seg.Inst)?.id ?: throw CipFail(0x04)
            val start = (path[classes[1].index + 1] as? Seg.Inst)?.id ?: 0
            val prog = memory.programByInstance(progInst) ?: throw CipFail(0x05, 0)
            prog.tags.values to start
        } else {
            val start = pathInstance(path)
            memory.controllerSymbols() to start
        }
        val replyBudget = (budget - 4).coerceAtLeast(32)
        val (body, more) = memory.symbolPage(tags, start, replyBudget)
        return cipReply(Ab.GET_INSTANCES, if (more) 0x06 else 0, body)
    }

    private fun multiple(conn: Conn, data: ByteArray, budget: Int): ByteArray {
        if (data.size < 2) throw CipFail(0x13)
        val count = u16(data, 0)
        if (count <= 0 || data.size < 2 + 2 * count) throw CipFail(0x13)
        mspPackets.incrementAndGet()
        maxEmbedded.updateAndGet { maxOf(it, count) }
        val replies = ArrayList<ByteArray>(count)
        var embeddedFail = false
        for (n in 0 until count) {
            val off = u16(data, 2 + 2 * n)
            val end = if (n + 1 < count) u16(data, 2 + 2 * (n + 1)) else data.size
            if (off > end || end > data.size) throw CipFail(0x13)
            val inner = cip(conn, data.copyOfRange(off, end), budget)
            val reply = inner.bytes
            if (reply.size > 2 && (reply[2].toInt() and 0xFF) != 0) embeddedFail = true
            replies += reply
        }
        val table = 2 + 2 * replies.size
        var cursor = table
        val offsets = IntArray(replies.size)
        for (i in replies.indices) {
            offsets[i] = cursor
            cursor += replies[i].size
        }
        val body = ByteArray(cursor)
        put16(body, 0, replies.size)
        for (i in offsets.indices) put16(body, 2 + 2 * i, offsets[i])
        var at = table
        for (reply in replies) {
            reply.copyInto(body, at)
            at += reply.size
        }
        return cipReply(Ab.MSP, if (embeddedFail) 0x1E else 0, body)
    }

    private fun forwardOpen(conn: Conn, service: Int, data: ByteArray): ByteArray {
        val large = service == Ab.FORWARD_LARGE
        forwardOpens.incrementAndGet()
        if (large) {
            largeForwardOpens.incrementAndGet()
            if (firmware != SimFirmware.V21) throw CipFail(0x08)
        }
        if (data.size < if (large) 40 else 36) throw CipFail(0x13)
        val o2t = u32(data, 2)
        val t2o = u32(data, 6)
        val serial = u16(data, 10)
        val vendor = u16(data, 12)
        val origin = u32(data, 14)
        var p = 26
        val o2tParam = if (large) { val v = u32(data, p); p += 4; v } else { val v = u16(data, p).toLong(); p += 2; v }
        p += 4 // t2o rpi
        val t2oParam = if (large) { val v = u32(data, p); p += 4; v } else { val v = u16(data, p).toLong(); p += 2; v }
        if (p + 2 > data.size) throw CipFail(0x13)
        val pathSize = data[p + 1].toInt() and 0xFF
        val path = data.copyOfRange(p + 2, (p + 2 + pathSize * 2).coerceAtMost(data.size))
        val segs = try {
            parsePath(path)
        } catch (_: PathException) {
            throw CipFail(0x04, 0)
        }
        val hops = segs.takeWhile { it is Seg.Port }.map { it as Seg.Port }
        lastForwardOpenRoute = encodeRoute(hops)
        checkRoute(hops, allowDirect = route == null)
        val rest = segs.subList(hops.size, segs.size)
        if (!classInst(rest, Ab.MESSAGE_ROUTER, 1)) throw CipFail(0x05, 0)
        if (refuseConnections) throw CipFail(0x01, 0x0113)
        val asked = (if (large) o2tParam and 0xFFFF else o2tParam and 0x1FF).toInt()
        val size = asked.coerceIn(64, 4000)
        conn.o2t = o2t
        conn.t2o = t2o
        conn.cipSize = size
        val out = Le()
        out.u32(o2t)
        out.u32(t2o)
        out.u16(serial)
        out.u16(vendor)
        out.u32(origin)
        out.u32(u32(data, 22))
        out.u32(u32(data, 22))
        out.u8(0)
        out.u8(0)
        return cipReply(service, 0, out.toByteArray())
    }

    private fun tag(service: Int, path: List<Seg>, data: ByteArray, budget: Int): ByteArray {
        if (firmware != SimFirmware.V21 && path.firstOrNull() is Seg.ClassId) throw CipFail(0x04, 0)
        val symbolic = memory.symbolicRoot(path)
        if (symbolic !== path) instanceAddressed.incrementAndGet() else symbolAddressed.incrementAndGet()
        val view = memory.open(symbolic.filter { it !is Seg.ClassId && it !is Seg.Inst && it !is Seg.Attr })
        val replyRoom = (budget - 4).coerceAtLeast(8)
        if (view.template == null && view.bit < 0 && view.atomic == Ab.SHORT_STRING) return shortStringTag(service, view, data)
        when (service) {
            Ab.READ -> {
                if (data.size < 2) throw CipFail(0x13)
                val count = u16(data, 0)
                val payload = view.read(count)
                val typeLen = view.wireType.size
                val room = replyRoom - typeLen
                if (room < 1) throw CipFail(0x06)
                return if (payload.size <= room) {
                    cipReply(service, 0, view.wireType + payload)
                } else {
                    cipReply(service, 0x06, view.wireType + payload.copyOf(room))
                }
            }
            Ab.READ_FRAG -> {
                if (data.size < 6) throw CipFail(0x13)
                val count = u16(data, 0)
                val offset = u32(data, 2).toInt()
                val payload = view.read(count)
                if (offset > payload.size) throw CipFail(0xFF, 0x2105)
                val typeLen = view.wireType.size
                val room = (replyRoom - typeLen).coerceAtLeast(1)
                val slice = payload.copyOfRange(offset, minOf(payload.size, offset + room))
                val more = offset + slice.size < payload.size
                return cipReply(service, if (more) 0x06 else 0, view.wireType + slice)
            }
            Ab.WRITE, Ab.WRITE_FRAG -> {
                if (!view.writable) throw CipFail(0x10, 0)
                val frag = service == Ab.WRITE_FRAG
                if (frag) {
                    val n = fragmentedWrites.incrementAndGet().toInt()
                    if (failWriteFragment > 0 && n == failWriteFragment) throw CipFail(0x01)
                }
                val parsed = parseWrite(data, frag)
                if (!view.typeMatches(parsed.type)) throw CipFail(0xFF, 0x2107)
                if (parsed.offset > 0 && !frag) throw CipFail(0x13)
                view.write(parsed.elements, parsed.offset, parsed.data, frag)
                return cipReply(service, 0, byteArrayOf())
            }
        }
        throw CipFail(0x08)
    }

    private fun shortStringTag(service: Int, view: View, data: ByteArray): ByteArray {
        val bytes = view.bytes
        when (service) {
            Ab.READ -> {
                if (data.size < 2) throw CipFail(0x13)
                if (u16(data, 0) != 1) throw CipFail(0xFF, 0x2105)
                val len = (bytes[view.offset].toInt() and 0xFF).coerceAtMost(SHORT_STRING_MAX)
                return cipReply(service, 0, view.wireType + bytes.copyOfRange(view.offset, view.offset + 1 + len))
            }
            Ab.WRITE -> {
                if (!view.writable) throw CipFail(0x10, 0)
                val parsed = parseWrite(data, false)
                if (!view.typeMatches(parsed.type)) throw CipFail(0xFF, 0x2107)
                if (parsed.data.isEmpty()) throw CipFail(0x13)
                val len = parsed.data[0].toInt() and 0xFF
                if (len > SHORT_STRING_MAX) throw CipFail(0xFF, 0x2105)
                if (parsed.data.size < 1 + len) throw CipFail(0x13)
                java.util.Arrays.fill(bytes, view.offset, view.offset + 1 + SHORT_STRING_MAX, 0)
                parsed.data.copyInto(bytes, view.offset, 0, 1 + len)
                view.tag.bytes = bytes
                return cipReply(service, 0, byteArrayOf())
            }
        }
        throw CipFail(0x08)
    }

    /**
     * Leading port segments of a plain request. They must be a route this stand-in answers; the
     * rest of the path is returned.
     */
    private fun stripRoute(segs: List<Seg>): List<Seg> {
        val hops = segs.takeWhile { it is Seg.Port }.map { it as Seg.Port }
        checkRoute(hops, allowDirect = true)
        return segs.subList(hops.size, segs.size)
    }

    /**
     * [hops] must be [route] exactly when one is set; otherwise `1,<slot>` on a chassis
     * controller and nothing on a Micro800. [allowDirect] also accepts no hops.
     */
    private fun checkRoute(hops: List<Seg.Port>, allowDirect: Boolean) {
        if (allowDirect && hops.isEmpty()) return
        val expected = expectedRoute ?: if (firmware == SimFirmware.MICRO800) emptyList() else listOf(Seg.Port(1, byteArrayOf(slot.toByte())))
        if (hops.size != expected.size) throw CipFail(0x01, 0x0312)
        for (i in hops.indices) {
            if (hops[i].port != expected[i].port || !hops[i].link.contentEquals(expected[i].link)) throw CipFail(0x01, 0x0312)
        }
    }

    private class Conn {
        @Volatile var session: Long = 0
        @Volatile var o2t: Long = 0
        @Volatile var t2o: Long = 0
        @Volatile var cipSize: Int = 500
        var replySequence: Int = 0
    }

    private class Memory {
        @Volatile var productName: String = "OIP Logix Simulator"
        private var sessionSeq = 0x1000L
        private var nextInstance = 1
        private var nextTemplate = 0x100
        private val templates = HashMap<Int, Template>()
        private val templatesByName = HashMap<String, Template>()
        private val controller = LinkedHashMap<String, Tag>()
        private val programs = LinkedHashMap<String, Prog>()
        private val stringTemplates = HashMap<Int, Int>()
        private val generation = AtomicInteger(1)

        init {
            val id = defineString(82)
            stringTemplates[82] = id
        }

        @Synchronized
        fun generation(): Int = generation.get()

        private fun bump() {
            generation.incrementAndGet()
        }

        @Synchronized
        fun nextSession(): Long = ++sessionSeq

        @Synchronized
        fun identity(): ByteArray {
            val name = productName.toByteArray(Charsets.US_ASCII)
            val out = Le()
            out.u16(0x0001)
            out.u16(0x000E)
            out.u16(0x00C3)
            out.u8(33)
            out.u8(1)
            out.u16(0x0030)
            out.u32(0x4F495031)
            out.u8(name.size)
            out.bytes(name)
            return out.toByteArray()
        }

        @Synchronized
        fun putAtomic(program: String?, name: String, type: Int, value: Number) {
            val tag = Tag(name, type and 0x0FFF, IntArray(0), encodeNumber(type, value), true, nextInstance++, null)
            place(program, tag)
        }

        @Synchronized
        fun putArray(program: String?, name: String, type: Int, dims: IntArray, values: List<Number>) {
            val n = dims.fold(1) { a, b -> a * b }
            require(values.size == n)
            val es = atomicSize(type)
            val bytes = ByteArray(n * es)
            values.forEachIndexed { i, v -> encodeNumber(type, v).copyInto(bytes, i * es) }
            val symbol = (type and 0x0FFF) or (dims.size shl 13)
            place(program, Tag(name, symbol, dims, bytes, true, nextInstance++, null))
        }

        @Synchronized
        fun putBoolArray(program: String?, name: String, bits: Int, set: Set<Int>) {
            require(bits > 0)
            val words = (bits + 31) / 32
            val bytes = ByteArray(words * 4)
            for (bit in set) {
                require(bit in 0 until bits)
                val i = bit / 8
                bytes[i] = (bytes[i].toInt() or (1 shl (bit % 8))).toByte()
            }
            val symbol = Ab.BOOL or (1 shl 13)
            place(program, Tag(name, symbol, intArrayOf(bits), bytes, true, nextInstance++, null))
        }

        @Synchronized
        fun putShortString(name: String, value: String) {
            place(null, Tag(name, Ab.SHORT_STRING, IntArray(0), shortStringBytes(value), true, nextInstance++, null))
        }

        private fun shortStringBytes(value: String): ByteArray {
            val raw = value.toByteArray(Charsets.UTF_8)
            require(raw.size <= SHORT_STRING_MAX) { "string longer than $SHORT_STRING_MAX" }
            val bytes = ByteArray(1 + SHORT_STRING_MAX)
            bytes[0] = raw.size.toByte()
            raw.copyInto(bytes, 1)
            return bytes
        }

        @Synchronized
        fun putString(program: String?, name: String, value: String, capacity: Int) {
            val template = stringTemplates.getOrPut(capacity) { defineString(capacity) }
            val bytes = stringBytes(templates.getValue(template), value)
            val symbol = 0x8000 or template
            place(program, Tag(name, symbol, IntArray(0), bytes, true, nextInstance++, template))
        }

        @Synchronized
        fun defineUdt(name: String, members: List<MemberSpec>): Int {
            templatesByName[name]?.let { return it.id }
            val id = nextTemplate++
            val built = layout(name, id, members, stringLike = false)
            templates[id] = built
            templatesByName[name] = built
            bump()
            return id
        }

        @Synchronized
        fun reviseUdt(name: String, members: List<MemberSpec>) {
            val existing = templatesByName[name] ?: throw IllegalArgumentException("no template $name")
            val built = layout(name, existing.id, members, stringLike = false)
            templates[existing.id] = built
            templatesByName[name] = built
            bump()
        }

        @Synchronized
        fun alias(program: String?, name: String, target: String) {
            val (symbol, dims, templateId) = describeTarget(program, target)
            place(program, Tag(name, symbol, dims, ByteArray(0), true, nextInstance++, templateId, target))
        }

        private fun describeTarget(program: String?, target: String): Triple<Int, IntArray, Int?> {
            val parts = target.split('.')
            if (parts.isEmpty() || parts.any { it.isEmpty() }) throw IllegalArgumentException(target)
            val root = find(program, parts[0])
            if (root.aliasPath != null) throw IllegalArgumentException("alias target $target")
            if (parts.size == 1) return Triple(root.symbolType, root.dims.copyOf(), root.templateId)
            var template = templates[root.templateId] ?: throw IllegalArgumentException(target)
            var at = 1
            while (true) {
                val member = template.members.firstOrNull { it.name == parts[at] } ?: throw IllegalArgumentException(target)
                val last = at == parts.lastIndex
                if (last) {
                    return if ((member.type and 0x8000) != 0) {
                        val id = member.type and 0x0FFF
                        val dims = if (member.arrayLength > 0) intArrayOf(member.arrayLength) else IntArray(0)
                        Triple(member.type, dims, id)
                    } else {
                        val dims = if (member.arrayLength > 0) intArrayOf(member.arrayLength) else IntArray(0)
                        val symbol = member.elementType or (if (dims.isNotEmpty()) 1 shl 13 else 0)
                        Triple(symbol, dims, null)
                    }
                }
                if ((member.type and 0x8000) == 0) throw IllegalArgumentException(target)
                template = templates[member.type and 0x0FFF] ?: throw IllegalArgumentException(target)
                at++
            }
        }

        @Synchronized
        fun putStructure(program: String?, tag: String, templateName: String, values: Map<String, Any?>) {
            val template = templatesByName[templateName] ?: throw IllegalArgumentException("no template $templateName")
            val bytes = ByteArray(template.size)
            for ((member, value) in values) {
                val mem = template.members.first { it.name == member }
                writeMember(bytes, template, mem, value)
            }
            val symbol = 0x8000 or template.id
            place(program, Tag(tag, symbol, IntArray(0), bytes, true, nextInstance++, template.id))
        }

        @Synchronized
        fun tag(program: String?, name: String): Tag = find(program, name)

        @Synchronized
        fun set(program: String?, name: String, value: Any) {
            val tag = find(program, name)
            when (value) {
                is Boolean -> {
                    if ((tag.symbolType and 0x0FFF) == Ab.BOOL && tag.dims.isEmpty()) {
                        tag.bytes = byteArrayOf(if (value) 1 else 0)
                    } else {
                        throw IllegalArgumentException(name)
                    }
                }
                is Number -> tag.bytes = encodeNumber(tag.symbolType, value)
                is String -> {
                    if (tag.symbolType == Ab.SHORT_STRING) {
                        tag.bytes = shortStringBytes(value)
                    } else {
                        val template = templates[tag.templateId] ?: throw IllegalArgumentException(name)
                        tag.bytes = stringBytes(template, value)
                    }
                }
                else -> throw IllegalArgumentException(name)
            }
        }

        @Synchronized
        fun get(program: String?, name: String): Any? {
            val tag = find(program, name)
            if (tag.symbolType == Ab.SHORT_STRING) {
                val len = tag.bytes[0].toInt() and 0xFF
                return tag.bytes.copyOfRange(1, 1 + len).toString(Charsets.UTF_8)
            }
            val template = tag.templateId?.let { templates[it] }
            if (template != null && template.name.substringBefore(';') == "STRING") return decodeString(tag.bytes)
            if (tag.dims.isEmpty() && template == null) return decodeNumber(tag.symbolType, tag.bytes, 0)
            return tag.bytes.copyOf()
        }

        @Synchronized
        fun controllerSymbols(): List<Tag> {
            val list = ArrayList<Tag>(controller.size + programs.size)
            list.addAll(controller.values)
            for (prog in programs.values) {
                list += Tag("Program:${prog.name}", Ab.PROGRAM, IntArray(0), ByteArray(0), false, prog.instanceId, null)
            }
            return list
        }

        @Synchronized
        fun programByInstance(instance: Int): Prog? = programs.values.firstOrNull { it.instanceId == instance }

        @Synchronized
        fun symbolPage(tags: Collection<Tag>, start: Int, budget: Int): Pair<ByteArray, Boolean> {
            val sorted = tags.filter { it.instanceId >= start }.sortedBy { it.instanceId }
            val out = Le()
            var sent = 0
            for (tag in sorted) {
                val name = tag.name.toByteArray(Charsets.US_ASCII)
                val need = 4 + 2 + name.size + 2 + 12
                if (out.size + need > budget && sent > 0) break
                out.u32(tag.instanceId.toLong())
                out.u16(name.size)
                out.bytes(name)
                out.u16(tag.symbolType)
                out.u32(tag.dims.getOrElse(0) { 0 }.toLong())
                out.u32(tag.dims.getOrElse(1) { 0 }.toLong())
                out.u32(tag.dims.getOrElse(2) { 0 }.toLong())
                sent++
            }
            return out.toByteArray() to (sent < sorted.size)
        }

        @Synchronized
        fun templateAttributes(instance: Int, data: ByteArray): ByteArray {
            val template = templates[instance] ?: throw CipFail(0x05, 0)
            if (data.size < 2) throw CipFail(0x13)
            val count = u16(data, 0)
            if (data.size < 2 + 2 * count) throw CipFail(0x13)
            val out = Le()
            out.u16(count)
            for (n in 0 until count) {
                val attr = u16(data, 2 + 2 * n)
                out.u16(attr)
                out.u16(0)
                when (attr) {
                    1 -> out.u16(template.handle)
                    2 -> out.u16(template.members.size)
                    4 -> out.u32(template.definitionWords.toLong())
                    5 -> out.u32(template.size.toLong())
                    else -> throw CipFail(0x0A)
                }
            }
            return out.toByteArray()
        }

        @Synchronized
        fun readTemplate(instance: Int, offset: Int, count: Int, budget: Int): Pair<ByteArray, Boolean> {
            val template = templates[instance] ?: throw CipFail(0x05, 0)
            val def = template.definition
            if (offset > def.size) throw CipFail(0xFF, 0x2105)
            val room = (budget - 4).coerceAtLeast(8)
            val take = minOf(count, def.size - offset, room)
            val more = offset + take < def.size && take < count
            return def.copyOfRange(offset, offset + take) to more
        }

        /**
         * A path that starts with Symbol class 0x6B and an instance names its root tag by instance id
         * (1756-PM020 symbol instance addressing). Two class/instance pairs are a program instance and
         * a tag in that program. The root is rewritten to its symbolic name so [open] walks it the same
         * way. Any other path is returned as it is.
         */
        @Synchronized
        fun symbolicRoot(path: List<Seg>): List<Seg> {
            if (path.size < 2) return path
            val c0 = path[0] as? Seg.ClassId ?: return path
            val i0 = path[1] as? Seg.Inst ?: return path
            if (c0.id != Ab.SYMBOL) return path
            val c1 = path.getOrNull(2) as? Seg.ClassId
            val i1 = path.getOrNull(3) as? Seg.Inst
            val head = ArrayList<Seg>(2)
            val rest: List<Seg>
            if (c1 != null && i1 != null && c1.id == Ab.SYMBOL) {
                val prog = programs.values.firstOrNull { it.instanceId == i0.id } ?: throw CipFail(0x05, 0)
                val tag = prog.tags.values.firstOrNull { it.instanceId == i1.id } ?: throw CipFail(0x05, 0)
                head += Seg.Symbol("Program:${prog.name}")
                head += Seg.Symbol(tag.name)
                rest = path.subList(4, path.size)
            } else {
                val tag = controller.values.firstOrNull { it.instanceId == i0.id } ?: throw CipFail(0x05, 0)
                head += Seg.Symbol(tag.name)
                rest = path.subList(2, path.size)
            }
            if (rest.any { it is Seg.ClassId || it is Seg.Inst }) throw CipFail(0x04, 0)
            return head + rest
        }

        @Synchronized
        fun open(path: List<Seg>): View {
            val symbols = ArrayList<Seg.Symbol>()
            val indexes = ArrayList<List<Int>>()
            var pending = ArrayList<Int>()
            var started = false
            for (seg in path) {
                when (seg) {
                    is Seg.Symbol -> {
                        if (started) indexes += pending
                        pending = ArrayList()
                        symbols += seg
                        started = true
                    }
                    is Seg.Elem -> pending += seg.index
                    else -> throw CipFail(0x04, 0)
                }
            }
            if (started) indexes += pending
            if (symbols.isEmpty()) throw CipFail(0x04, 0)
            var scope: String? = null
            var symAt = 0
            var tag: Tag
            val rootName = symbols[0].name
            if (rootName.startsWith("Program:") && symbols.size >= 2) {
                scope = rootName.removePrefix("Program:")
                if (programs[scope] == null) throw CipFail(0x05, 0)
                if (indexes[0].isNotEmpty()) throw CipFail(0x04, 0)
                tag = programs[scope]!!.tags[symbols[1].name] ?: throw CipFail(0x05, 0)
                symAt = 2
            } else {
                tag = controller[rootName] ?: throw CipFail(0x05, 0)
                symAt = 1
            }
            val memberSymbols = ArrayList<Seg.Symbol>()
            val memberIndexes = ArrayList<List<Int>>()
            for (i in symAt until symbols.size) {
                memberSymbols += symbols[i]
                memberIndexes += indexes[i]
            }
            var tagIndexes = indexes[symAt - 1]
            var guard = 0
            while (tag.aliasPath != null) {
                if (guard++ > 8) throw CipFail(0x04, 0)
                val parts = tag.aliasPath!!.split('.')
                if (parts.isEmpty() || parts.any { it.isEmpty() }) throw CipFail(0x05, 0)
                val base = if (scope == null) {
                    controller[parts[0]] ?: throw CipFail(0x05, 0)
                } else {
                    programs[scope]?.tags?.get(parts[0]) ?: throw CipFail(0x05, 0)
                }
                val inserted = parts.drop(1).map { Seg.Symbol(it) }
                val insertedIdx = ArrayList<List<Int>>(inserted.size)
                repeat(inserted.size) { insertedIdx.add(emptyList()) }
                if (tagIndexes.isNotEmpty() && insertedIdx.isNotEmpty()) {
                    insertedIdx[insertedIdx.lastIndex] = tagIndexes
                    tagIndexes = emptyList()
                }
                tag = base
                val combinedNames = ArrayList<Seg.Symbol>(inserted.size + memberSymbols.size)
                combinedNames += inserted
                combinedNames += memberSymbols
                val combinedIdx = ArrayList<List<Int>>(insertedIdx.size + memberIndexes.size)
                combinedIdx += insertedIdx
                combinedIdx += memberIndexes
                memberSymbols.clear()
                memberSymbols.addAll(combinedNames)
                memberIndexes.clear()
                memberIndexes.addAll(combinedIdx)
            }
            val walkSymbols = ArrayList<Seg.Symbol>(1 + memberSymbols.size)
            walkSymbols += Seg.Symbol(tag.name)
            walkSymbols.addAll(memberSymbols)
            val walkIndexes = ArrayList<List<Int>>(1 + memberIndexes.size)
            walkIndexes += tagIndexes
            walkIndexes.addAll(memberIndexes)
            symAt = 1
            var template = tag.templateId?.let { templates[it] }
            var offset = 0
            var dims = tag.dims
            var atomic = if (template == null) tag.symbolType and 0x0FFF else 0
            var boolArray = template == null && atomic == Ab.BOOL && dims.isNotEmpty()
            var bit = -1
            val activeBytes = tag.bytes
            if (tagIndexes.isNotEmpty()) {
                val linear = linear(dims, tagIndexes)
                if (boolArray) {
                    bit = linear
                    dims = IntArray(0)
                    boolArray = false
                    atomic = Ab.BOOL
                } else {
                    val es = if (template != null) template.size else atomicSize(atomic)
                    offset += linear * es
                    dims = IntArray(0)
                }
            }
            while (symAt < walkSymbols.size) {
                val member = template?.members?.firstOrNull { it.name == walkSymbols[symAt].name } ?: throw CipFail(0x05, 0)
                val memberIndexes = walkIndexes[symAt]
                symAt++
                if (member.elementType == Ab.BOOL && member.arrayLength == 0 && (member.type and 0x8000) == 0) {
                    bit = member.info
                    offset += member.offset
                    atomic = Ab.BOOL
                    template = null
                    dims = IntArray(0)
                    if (memberIndexes.isNotEmpty()) throw CipFail(0x04, 0)
                    continue
                }
                offset += member.offset
                if ((member.type and 0x8000) != 0) {
                    template = templates[member.type and 0x0FFF] ?: throw CipFail(0x05, 0)
                    atomic = 0
                    dims = if (member.arrayLength > 0) intArrayOf(member.arrayLength) else IntArray(0)
                } else {
                    template = null
                    atomic = member.elementType
                    dims = if (member.arrayLength > 0) intArrayOf(member.arrayLength) else IntArray(0)
                    boolArray = atomic == Ab.BOOL && dims.isNotEmpty()
                }
                if (memberIndexes.isNotEmpty()) {
                    val linear = linear(dims, memberIndexes)
                    if (boolArray) {
                        bit = linear
                        boolArray = false
                        atomic = Ab.BOOL
                        dims = IntArray(0)
                    } else {
                        val es = if (template != null) template.size else atomicSize(atomic)
                        offset += linear * es
                        dims = IntArray(0)
                        template = if ((member.type and 0x8000) != 0) template else null
                    }
                }
            }
            val remainingElements = when {
                bit >= 0 -> 1
                boolArray -> dims[0]
                dims.isEmpty() -> 1
                else -> dims.fold(1) { a, b -> a * b }
            }
            val elementSize = when {
                bit >= 0 -> 1
                boolArray -> 1
                template != null -> template.size
                else -> atomicSize(atomic)
            }
            val wire = if (template != null && bit < 0 && !boolArray) {
                byteArrayOf(0xA0.toByte(), 0x02, (template.handle and 0xFF).toByte(), (template.handle ushr 8).toByte())
            } else {
                val t = if (bit >= 0 || boolArray) Ab.BOOL else atomic
                byteArrayOf((t and 0xFF).toByte(), ((t ushr 8) and 0xFF).toByte())
            }
            return View(tag, activeBytes, offset, template, atomic, dims, boolArray, bit, remainingElements, elementSize, wire, tag.writable)
        }

        private fun find(program: String?, name: String): Tag {
            if (program == null) return controller[name] ?: throw IllegalArgumentException(name)
            return programs[program]?.tags?.get(name) ?: throw IllegalArgumentException(name)
        }

        private fun place(program: String?, tag: Tag) {
            if (program == null) {
                controller[tag.name] = tag
            } else {
                val prog = programs.getOrPut(program) { Prog(program, nextInstance++) }
                prog.tags[tag.name] = tag
            }
            bump()
        }

        private fun defineString(capacity: Int): Int {
            val id = if (capacity == 82) Ab.STRING_TEMPLATE else nextTemplate++
            if (id >= nextTemplate) nextTemplate = id + 1
            val members = listOf(
                MemberSpec("LEN", Ab.DINT),
                MemberSpec("DATA", Ab.SINT, capacity),
            )
            val built = layout("STRING", id, members, stringLike = true)
            templates[id] = built
            templatesByName["STRING"] = built
            return id
        }

        private fun layout(name: String, id: Int, specs: List<MemberSpec>, stringLike: Boolean): Template {
            val members = ArrayList<MemMember>()
            var offset = 0
            var host: MemMember? = null
            var hostBits = 0
            fun closeHost() {
                if (host != null) {
                    offset += 1
                    host = null
                    hostBits = 0
                }
            }
            for (spec in specs) {
                if (spec.type == Ab.BOOL && spec.arrayLength == 0) {
                    if (host == null || hostBits == 8) {
                        closeHost()
                        val hostMember = MemMember(
                            "ZZZZZZZZZZ${name}$offset", Ab.SINT, 0, offset, true, 0, Ab.SINT,
                        )
                        members += hostMember
                        host = hostMember
                        hostBits = 0
                    }
                    members += MemMember(spec.name, Ab.BOOL, hostBits, host!!.offset, false, 0, Ab.BOOL)
                    hostBits++
                    continue
                }
                closeHost()
                if ((spec.type and 0x8000) != 0) {
                    // A nested structure (UDT or Add-On Instruction): 4-byte aligned, its own size.
                    val nested = templates[spec.type and 0x0FFF] ?: throw IllegalArgumentException("no template for ${spec.name}")
                    offset = (offset + 3) and 3.inv()
                    val typeWord = if (spec.arrayLength > 0) spec.type or (1 shl 13) else spec.type
                    members += MemMember(spec.name, typeWord, spec.arrayLength, offset, false, spec.arrayLength, spec.type and 0x0FFF)
                    val count = if (spec.arrayLength > 0) spec.arrayLength else 1
                    offset += nested.size * count
                    continue
                }
                val align = alignOf(spec.type, spec.arrayLength)
                offset = (offset + align - 1) / align * align
                val typeWord = if (spec.arrayLength > 0) spec.type or (1 shl 13) else spec.type
                members += MemMember(spec.name, typeWord, spec.arrayLength, offset, false, spec.arrayLength, spec.type)
                val es = atomicSize(spec.type)
                val count = if (spec.arrayLength > 0) spec.arrayLength else 1
                offset += es * count
            }
            closeHost()
            offset = (offset + 3) and 3.inv()
            val info = Le()
            for (member in members) {
                info.u16(member.info)
                info.u16(member.type)
                info.u32(member.offset.toLong())
            }
            val templateName = if (stringLike) "STRING" else "$name;n"
            info.bytes(templateName.toByteArray(Charsets.US_ASCII))
            info.u8(0)
            for (member in members) {
                info.bytes(member.name.toByteArray(Charsets.US_ASCII))
                info.u8(0)
            }
            val definition = info.toByteArray()
            val words = (definition.size + 23 + 3) / 4
            val handle = crc(name, members)
            return Template(id, if (stringLike) "STRING" else name, handle, members, offset, definition, words)
        }

        private fun writeMember(bytes: ByteArray, template: Template, member: MemMember, value: Any?, base: Int = 0) {
            if ((member.type and 0x8000) != 0) {
                val nested = templates[member.type and 0x0FFF] ?: throw IllegalArgumentException(member.name)
                val fields = value as? Map<*, *> ?: throw IllegalArgumentException(member.name)
                if (member.arrayLength > 0) throw IllegalArgumentException("${member.name} is an array")
                for ((name, inner) in fields) {
                    val child = nested.members.first { it.name == name }
                    writeMember(bytes, nested, child, inner, base + member.offset)
                }
                return
            }
            if (member.elementType == Ab.BOOL && member.arrayLength == 0) {
                val on = when (value) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    else -> throw IllegalArgumentException(member.name)
                }
                val i = base + member.offset
                val mask = 1 shl member.info
                val cur = bytes[i].toInt() and 0xFF
                bytes[i] = (if (on) cur or mask else cur and mask.inv()).toByte()
                return
            }
            val encoded = when (value) {
                is Number -> encodeNumber(member.elementType, value)
                is Boolean -> byteArrayOf(if (value) 1 else 0)
                else -> throw IllegalArgumentException(member.name)
            }
            encoded.copyInto(bytes, base + member.offset)
        }

        private fun stringBytes(template: Template, value: String): ByteArray {
            val data = template.members.first { it.name == "DATA" }
            val raw = value.toByteArray(Charsets.US_ASCII)
            if (raw.size > data.arrayLength) throw IllegalArgumentException("string longer than ${data.arrayLength}")
            val bytes = ByteArray(template.size)
            put32(bytes, 0, raw.size.toLong())
            raw.copyInto(bytes, data.offset)
            return bytes
        }

        private fun decodeString(bytes: ByteArray): String {
            val len = i32(bytes, 0).coerceIn(0, bytes.size - 4)
            return bytes.copyOfRange(4, 4 + len).toString(Charsets.US_ASCII)
        }
    }

    private class View(
        val tag: Tag,
        val bytes: ByteArray,
        val offset: Int,
        val template: Template?,
        val atomic: Int,
        val dims: IntArray,
        val boolArray: Boolean,
        val bit: Int,
        val available: Int,
        val elementSize: Int,
        val wireType: ByteArray,
        val writable: Boolean,
    ) {
        fun typeMatches(requested: ByteArray): Boolean {
            if (requested.size != wireType.size) return false
            return requested.contentEquals(wireType) ||
                (wireType.size == 2 && (requested[0].toInt() and 0xFF) == (wireType[0].toInt() and 0xFF) && requested[1].toInt() == 0)
        }

        fun read(count: Int): ByteArray {
            if (count <= 0) throw CipFail(0x13)
            if (count > available) throw CipFail(0xFF, 0x2105)
            if (bit >= 0) {
                val on = (bytes[offset + bit / 8].toInt() ushr (bit % 8)) and 1
                return byteArrayOf(on.toByte())
            }
            if (boolArray) {
                val out = ByteArray(count)
                for (n in 0 until count) {
                    val index = n
                    val i = index / 8
                    val on = (bytes[i].toInt() ushr (index % 8)) and 1
                    out[n] = on.toByte()
                }
                return out
            }
            val start = offset
            val len = count * elementSize
            if (start < 0 || start + len > bytes.size) throw CipFail(0xFF, 0x2105)
            return bytes.copyOfRange(start, start + len)
        }

        fun write(elements: Int, byteOffset: Int, data: ByteArray, fragmented: Boolean) {
            if (elements <= 0) throw CipFail(0x13)
            if (bit >= 0) {
                if (data.isEmpty()) throw CipFail(0x13)
                val on = (data[0].toInt() and 0xFF) != 0
                val i = offset + bit / 8
                val mask = 1 shl (bit % 8)
                val cur = bytes[i].toInt() and 0xFF
                bytes[i] = (if (on) cur or mask else cur and mask.inv()).toByte()
                tag.bytes = bytes
                return
            }
            if (boolArray) {
                if (data.size < elements) throw CipFail(0x13)
                for (n in 0 until elements) {
                    val index = n
                    if (index >= available) throw CipFail(0xFF, 0x2105)
                    val i = index / 8
                    val mask = 1 shl (index % 8)
                    val cur = bytes[i].toInt() and 0xFF
                    val on = (data[n].toInt() and 0xFF) != 0
                    bytes[i] = (if (on) cur or mask else cur and mask.inv()).toByte()
                }
                tag.bytes = bytes
                return
            }
            val total = elements * elementSize
            if (elements > available) throw CipFail(0xFF, 0x2105)
            if (!fragmented) {
                if (byteOffset != 0 || data.size < total) throw CipFail(0x13)
                if (offset < 0 || offset + total > bytes.size) throw CipFail(0xFF, 0x2105)
                data.copyInto(bytes, offset, 0, total)
                if (atomic == Ab.BOOL || (wireType.size == 2 && (wireType[0].toInt() and 0xFF) == Ab.BOOL)) {
                    for (n in 0 until total) if (bytes[offset + n].toInt() != 0) bytes[offset + n] = 1
                }
            } else {
                if (byteOffset < 0 || data.isEmpty() || byteOffset > total || byteOffset + data.size > total) {
                    throw CipFail(0xFF, 0x2105)
                }
                val start = offset + byteOffset
                if (start < 0 || start + data.size > bytes.size) throw CipFail(0xFF, 0x2105)
                data.copyInto(bytes, start, 0, data.size)
            }
            tag.bytes = bytes
        }
    }

    private class Prog(val name: String, val instanceId: Int) {
        val tags = LinkedHashMap<String, Tag>()
    }

    private class Tag(
        val name: String,
        val symbolType: Int,
        val dims: IntArray,
        var bytes: ByteArray,
        var writable: Boolean,
        val instanceId: Int,
        val templateId: Int?,
        val aliasPath: String? = null,
    )

    private class Template(
        val id: Int,
        val name: String,
        val handle: Int,
        val members: List<MemMember>,
        val size: Int,
        val definition: ByteArray,
        val definitionWords: Int,
    )

    private class MemMember(
        val name: String,
        val type: Int,
        val info: Int,
        val offset: Int,
        val hidden: Boolean,
        val arrayLength: Int,
        val elementType: Int,
    )

    private companion object {
        const val UNCONNECTED_CIP = 480
        const val SHORT_STRING_MAX = 80

        /** `1,3,2,192.168.0.56,1,0` as port segments; a dotted or non-numeric link is an extended link. */
        fun parseRouteText(text: String): List<Seg.Port> {
            val parts = text.split(',').map { it.trim() }
            require(parts.size >= 2 && parts.size % 2 == 0 && parts.none { it.isEmpty() }) { "route '$text'" }
            return parts.chunked(2).map { (port, link) ->
                val number = if (link.all { it.isDigit() }) link.toInt() else null
                Seg.Port(port.toInt(), if (number != null) byteArrayOf(number.toByte()) else link.toByteArray(Charsets.US_ASCII))
            }
        }

        fun encodeRoute(hops: List<Seg.Port>): ByteArray {
            val out = ByteArrayOutputStream()
            for (hop in hops) {
                if (hop.link.size == 1 && hop.port < 15 && !extendedLink(hop)) {
                    out.write(hop.port)
                    out.write(hop.link[0].toInt())
                } else {
                    out.write(0x10 or hop.port)
                    out.write(hop.link.size)
                    out.write(hop.link)
                    if (hop.link.size % 2 != 0) out.write(0)
                }
            }
            return out.toByteArray()
        }

        private fun extendedLink(hop: Seg.Port): Boolean = hop.extended

        fun linear(dims: IntArray, indexes: List<Int>): Int {
            if (dims.isEmpty()) {
                if (indexes.size == 1 && indexes[0] == 0) return 0
                throw CipFail(0xFF, 0x2105)
            }
            if (indexes.size != dims.size) throw CipFail(0x04, 0)
            var linear = 0
            var stride = 1
            for (d in dims.indices.reversed()) {
                if (indexes[d] !in 0 until dims[d]) throw CipFail(0xFF, 0x2105)
                linear += indexes[d] * stride
                stride *= dims[d]
            }
            return linear
        }

        fun alignOf(type: Int, arrayLength: Int): Int {
            val wide = type == Ab.LINT || type == Ab.LREAL || type == Ab.DT || type == Ab.LDT || type == Ab.TIME || type == Ab.LTIME
            if (arrayLength > 0 && type != Ab.SINT && type != Ab.BOOL) {
                return if (wide) 8 else 4
            }
            return when (type) {
                Ab.BOOL, Ab.SINT -> 1
                Ab.INT -> 2
                Ab.LINT, Ab.LREAL, Ab.DT, Ab.LDT, Ab.TIME, Ab.LTIME -> 8
                else -> 4
            }
        }

        fun crc(name: String, members: List<MemMember>): Int {
            var c = 0xFFFF
            fun feed(b: Int) {
                c = c xor (b and 0xFF)
                repeat(8) {
                    c = if ((c and 1) != 0) (c ushr 1) xor 0xA001 else c ushr 1
                }
            }
            name.toByteArray(Charsets.US_ASCII).forEach { feed(it.toInt()) }
            for (m in members) {
                feed(m.type)
                feed(m.type ushr 8)
                feed(m.info)
                feed(m.offset)
            }
            return c and 0xFFFF
        }
    }
}

/** Which controller family the simulator stands in for. */
enum class SimFirmware { V21, V20, MICRO800 }

data class MemberSpec(val name: String, val type: Int, val arrayLength: Int = 0) {
    companion object {
        /** A member whose type is the template [templateId] returned by defineUdt or defineAoi. */
        fun structure(name: String, templateId: Int, arrayLength: Int = 0) =
            MemberSpec(name, 0x8000 or (templateId and 0x0FFF), arrayLength)
    }
}

/**
 * Tag type codes from 1756-PM020, extended to 16 bits the way that manual describes.
 * DT, LDT, TIME32, TIME, and LTIME are the CIP elementary codes those Logix names use
 * (UTIME 0xC0, STIME 0xCC, FTIME 0xD6, LTIME 0xD7, NTIME 0xDF). The cited manual's
 * atomic table does not list them. Storage is microseconds for DT, TIME, and TIME32,
 * and nanoseconds for LDT and LTIME.
 */
object Ab {
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
    const val PROGRAM = 0x1068
    const val STRING_TEMPLATE = 0x0FCE

    const val REGISTER = 0x0065
    const val UNREGISTER = 0x0066
    const val SEND_RR = 0x006F
    const val SEND_UNIT = 0x0070

    const val GET_ALL = 0x01
    const val GET_LIST = 0x03
    const val GET_ATTR = 0x0E
    const val MSP = 0x0A
    const val FORWARD_CLOSE = 0x4E
    const val FORWARD_OPEN = 0x54
    const val FORWARD_LARGE = 0x5B
    const val READ = 0x4C
    const val WRITE = 0x4D
    const val READ_FRAG = 0x52
    const val UNCONNECTED_SEND = 0x52
    const val WRITE_FRAG = 0x53
    const val GET_INSTANCES = 0x55

    const val IDENTITY = 0x01
    const val MESSAGE_ROUTER = 0x02
    const val CONNECTION_MANAGER = 0x06
    const val SYMBOL = 0x6B
    const val TEMPLATE = 0x6C
}

private class CipFail(val status: Int, val ext: Int = 0) : Exception()
private class PathException : Exception()

private class EncapReply(val command: Int, val status: Int, val body: ByteArray, val holdService: Int)
private class CipOut(val bytes: ByteArray, val holdService: Int)

private sealed interface Seg {
    class Port(val port: Int, val link: ByteArray, val extended: Boolean = link.size != 1) : Seg
    class ClassId(val id: Int) : Seg
    class Inst(val id: Int) : Seg
    class Attr(val id: Int) : Seg
    class Symbol(val name: String) : Seg
    class Elem(val index: Int) : Seg
}

private class Item(val type: Int, val data: ByteArray)

private fun parseItems(body: ByteArray, at: Int): List<Item> {
    if (at + 2 > body.size) return emptyList()
    val count = u16(body, at)
    var p = at + 2
    val items = ArrayList<Item>(count)
    repeat(count) {
        if (p + 4 > body.size) throw CipFail(0x13)
        val type = u16(body, p)
        val len = u16(body, p + 2)
        if (p + 4 + len > body.size) throw CipFail(0x13)
        items += Item(type, body.copyOfRange(p + 4, p + 4 + len))
        p += 4 + len
    }
    return items
}

private fun parsePath(path: ByteArray): List<Seg> {
    val out = ArrayList<Seg>()
    var i = 0
    while (i < path.size) {
        val b = path[i].toInt() and 0xFF
        when {
            b == 0x91 -> {
                if (i + 2 > path.size) throw PathException()
                val len = path[i + 1].toInt() and 0xFF
                if (i + 2 + len > path.size) throw PathException()
                val name = path.copyOfRange(i + 2, i + 2 + len).toString(Charsets.US_ASCII)
                var n = i + 2 + len
                if ((len % 2) != 0) n++
                if (n > path.size) throw PathException()
                out += Seg.Symbol(name)
                i = n
            }
            b and 0xE0 == 0x00 -> {
                val extended = (b and 0x10) != 0
                val port = b and 0x0F
                if (!extended) {
                    if (i + 2 > path.size) throw PathException()
                    out += Seg.Port(port, byteArrayOf(path[i + 1]))
                    i += 2
                } else {
                    if (i + 2 > path.size) throw PathException()
                    val len = path[i + 1].toInt() and 0xFF
                    val start = i + 2
                    val end = start + len
                    val padded = end + (if (len % 2 != 0) 1 else 0)
                    if (padded > path.size) throw PathException()
                    out += Seg.Port(port, path.copyOfRange(start, end), extended = true)
                    i = padded
                }
            }
            b and 0xE0 == 0x20 -> {
                val logical = (b ushr 2) and 0x7
                val format = b and 0x3
                val (value, next) = when (format) {
                    0 -> {
                        if (i + 2 > path.size) throw PathException()
                        (path[i + 1].toInt() and 0xFF) to (i + 2)
                    }
                    1 -> {
                        if (i + 4 > path.size) throw PathException()
                        u16(path, i + 2) to (i + 4)
                    }
                    2 -> {
                        if (i + 6 > path.size) throw PathException()
                        u32(path, i + 2).toInt() to (i + 6)
                    }
                    else -> throw PathException()
                }
                out += when (logical) {
                    0 -> Seg.ClassId(value)
                    1 -> Seg.Inst(value)
                    2 -> Seg.Elem(value)
                    4 -> Seg.Attr(value)
                    else -> throw PathException()
                }
                i = next
            }
            else -> throw PathException()
        }
    }
    return out
}

private fun classInst(path: List<Seg>, classId: Int, instance: Int): Boolean {
    val c = path.filter { it !is Seg.Port }
    return c.size >= 2 && c[0] is Seg.ClassId && (c[0] as Seg.ClassId).id == classId &&
        c[1] is Seg.Inst && (c[1] as Seg.Inst).id == instance
}

private fun pathClass(path: List<Seg>): Int {
    val c = path.filter { it !is Seg.Port }
    return (c.firstOrNull { it is Seg.ClassId } as? Seg.ClassId)?.id ?: -1
}

private fun pathInstance(path: List<Seg>): Int {
    val c = path.filter { it !is Seg.Port }
    val classAt = c.indexOfFirst { it is Seg.ClassId }
    if (classAt < 0 || classAt + 1 >= c.size) return 0
    return (c[classAt + 1] as? Seg.Inst)?.id ?: 0
}

private fun pathAttr(path: List<Seg>): Int =
    path.filterIsInstance<Seg.Attr>().firstOrNull()?.id ?: -1

private class WriteParsed(val type: ByteArray, val elements: Int, val offset: Int, val data: ByteArray)

private fun parseWrite(data: ByteArray, fragmented: Boolean): WriteParsed {
    if (data.size < 4) throw CipFail(0x13)
    val struct = (data[0].toInt() and 0xFF) == 0xA0 && (data[1].toInt() and 0xFF) == 0x02
    val typeLen = if (struct) 4 else 2
    if (data.size < typeLen + 2) throw CipFail(0x13)
    val elements = u16(data, typeLen)
    var p = typeLen + 2
    val offset = if (fragmented) {
        if (data.size < p + 4) throw CipFail(0x13)
        val v = u32(data, p).toInt()
        p += 4
        v
    } else 0
    return WriteParsed(data.copyOfRange(0, typeLen), elements, offset, data.copyOfRange(p, data.size))
}

private fun cipReply(service: Int, status: Int, data: ByteArray, extended: ByteArray = byteArrayOf()): ByteArray {
    val out = ByteArray(4 + extended.size + data.size)
    out[0] = (service or 0x80).toByte()
    out[1] = 0
    out[2] = status.toByte()
    out[3] = (extended.size / 2).toByte()
    extended.copyInto(out, 4)
    data.copyInto(out, 4 + extended.size)
    return out
}

private fun ext(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())

private fun cpfUnconnected(cip: ByteArray): ByteArray {
    val out = Le()
    out.u32(0)
    out.u16(1)
    out.u16(2)
    out.u16(0x0000)
    out.u16(0)
    out.u16(0x00B2)
    out.u16(cip.size)
    out.bytes(cip)
    return out.toByteArray()
}

private fun cpfConnected(connectionId: Long, data: ByteArray): ByteArray {
    val out = Le()
    out.u32(0)
    out.u16(1)
    out.u16(2)
    out.u16(0x00A1)
    out.u16(4)
    out.u32(connectionId)
    out.u16(0x00B1)
    out.u16(data.size)
    out.bytes(data)
    return out.toByteArray()
}

private fun encap(command: Int, status: Int, session: Long, context: ByteArray, body: ByteArray): ByteArray {
    val out = ByteArray(24 + body.size)
    put16(out, 0, command)
    put16(out, 2, body.size)
    put32(out, 4, session)
    put32(out, 8, status.toLong())
    context.copyInto(out, 12, 0, 8)
    body.copyInto(out, 24)
    return out
}

private fun atomicSize(type: Int): Int = when (type and 0xFF) {
    0xC1, 0xC2, 0xC6, 0xD1 -> 1
    0xC3, 0xC7, 0xD2 -> 2
    0xC4, 0xC8, 0xCA, 0xCD, 0xD3, 0xD6, 0xDB -> 4
    0xC0, 0xC5, 0xC9, 0xCB, 0xCC, 0xD4, 0xD7, 0xDF -> 8
    0xDA -> 81
    else -> throw IllegalArgumentException("type ${type.toString(16)}")
}

private fun encodeNumber(type: Int, value: Number): ByteArray {
    val out = ByteArray(atomicSize(type))
    when (type and 0xFF) {
        0xC1 -> out[0] = if (value.toInt() != 0) 1 else 0
        0xC2, 0xC6, 0xD1 -> out[0] = value.toInt().toByte()
        0xC3, 0xC7, 0xD2 -> put16(out, 0, value.toInt())
        0xC4, 0xD3, 0xC8, 0xCD, 0xDB -> put32(out, 0, value.toLong())
        0xC9, 0xD4 -> put64(out, 0, value.toLong())
        0xCA -> put32(out, 0, java.lang.Float.floatToRawIntBits(value.toFloat()).toLong() and 0xFFFFFFFFL)
        0xC5, 0xC0, 0xCC, 0xD7, 0xDF -> put64(out, 0, value.toLong())
        0xCB -> put64(out, 0, java.lang.Double.doubleToRawLongBits(value.toDouble()))
        0xD6 -> put32(out, 0, value.toLong())
    }
    return out
}

private fun decodeNumber(type: Int, bytes: ByteArray, offset: Int): Any = when (type and 0xFF) {
    0xC1 -> (bytes[offset].toInt() and 0xFF) != 0
    0xC2 -> bytes[offset].toInt()
    0xC3 -> u16(bytes, offset).let { if (it and 0x8000 != 0) it - 0x10000 else it }
    0xC4, 0xD3 -> i32(bytes, offset)
    0xC6, 0xD1 -> bytes[offset].toInt() and 0xFF
    0xC7, 0xD2 -> u16(bytes, offset)
    0xC8, 0xCD -> u32(bytes, offset)
    0xC9, 0xD4 -> i64(bytes, offset)
    0xDB -> i32(bytes, offset)
    0xCA -> java.lang.Float.intBitsToFloat(i32(bytes, offset))
    0xC5, 0xC0, 0xCC, 0xD7, 0xDF -> i64(bytes, offset)
    0xCB -> java.lang.Double.longBitsToDouble(i64(bytes, offset))
    0xD6 -> i32(bytes, offset)
    else -> bytes.copyOfRange(offset, offset + atomicSize(type))
}

private class Le {
    private val out = ByteArrayOutputStream()
    val size get() = out.size()
    fun u8(v: Int) { out.write(v and 0xFF) }
    fun u16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    fun u32(v: Long) {
        val x = v.toInt()
        out.write(x and 0xFF); out.write((x ushr 8) and 0xFF); out.write((x ushr 16) and 0xFF); out.write((x ushr 24) and 0xFF)
    }
    fun bytes(b: ByteArray) { out.write(b) }
    fun toByteArray() = out.toByteArray()
}

private fun readFully(input: InputStream, n: Int): ByteArray? {
    val buf = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = try {
            input.read(buf, got, n - got)
        } catch (_: Exception) {
            return null
        }
        if (r < 0) return null
        got += r
    }
    return buf
}

private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
private fun u32(b: ByteArray, o: Int): Long =
    (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
private fun i32(b: ByteArray, o: Int) = u32(b, o).toInt()
private fun i64(b: ByteArray, o: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
    return v
}

private fun put16(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xFF).toByte()
    b[o + 1] = ((v ushr 8) and 0xFF).toByte()
}
private fun put32(b: ByteArray, o: Int, v: Long) {
    val x = v.toInt()
    b[o] = (x and 0xFF).toByte()
    b[o + 1] = ((x ushr 8) and 0xFF).toByte()
    b[o + 2] = ((x ushr 16) and 0xFF).toByte()
    b[o + 3] = ((x ushr 24) and 0xFF).toByte()
}
private fun put64(b: ByteArray, o: Int, v: Long) {
    var x = v
    for (i in 0 until 8) {
        b[o + i] = (x and 0xFF).toByte()
        x = x ushr 8
    }
}
