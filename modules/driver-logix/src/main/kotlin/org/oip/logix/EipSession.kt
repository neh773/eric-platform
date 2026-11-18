package org.oip.logix

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One EtherNet/IP TCP session (port 44818) and a Class 3 explicit connection.
 *
 * Encapsulation header is the 24-byte form used on EtherNet/IP: command, length, session,
 * status, sender context, options. A response timeout closes the socket. The next request
 * must not consume a late reply, same rule the S7 driver uses for a paired PDU.
 *
 * The public Logix page asks for a CIP connection size used in Forward Open. Sizes through
 * 511 use Forward Open (0x54, 16-bit parameters). Larger sizes use Large Forward Open (0x5B).
 *
 * Both Forward Open and Unconnected Send carry the configured route (backplane slot hops and
 * port/link pairs through a bridge module). When the controller refuses the Forward Open with a
 * CIP status (no free connections, for example) the session falls back to unconnected messaging:
 * each request is wrapped in Unconnected Send along the same route, and the session only counts
 * as up once an identity read has come back that way. doc source: FEATURES DEV-009
 */
internal class EipSession(private val config: LogixDeviceConfig) {
    private val lock = Any()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: java.io.OutputStream? = null
    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile var cipBudget: Int = 480
        private set
    @Volatile var productName: String = ""
        private set

    /** True while requests go as Unconnected Send because the Forward Open was refused. */
    @Volatile var unconnected: Boolean = false
        private set
    private val route: ByteArray = config.route()
    private var session: Long = 0
    private var o2t: Long = 0
    private var t2o: Long = 0
    private var sequence = 1
    private var context = 1
    private val serial = AtomicInteger(1)

    val requests = AtomicLong()
    val errors = AtomicLong()
    val timeouts = AtomicLong()
    private val responseNanos = AtomicLong()
    private val responseSamples = AtomicLong()

    fun isConnected(): Boolean = connected && socket != null

    fun averageMillis(): Double? {
        val n = responseSamples.get()
        if (n == 0L) return null
        return (responseNanos.get().toDouble() / n) / 1_000_000.0
    }

    fun connect() = synchronized(lock) {
        if (closed) throw IOException("session closed")
        val timeout = config.timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val opened = Socket()
        opened.tcpNoDelay = true
        opened.keepAlive = true
        opened.soTimeout = timeout
        val local = config.localAddress
        if (!local.isNullOrBlank()) opened.bind(InetSocketAddress(local, 0))
        opened.connect(InetSocketAddress(config.host, config.port), timeout)
        synchronized(lock) {
            if (closed) {
                opened.close()
                throw IOException("session closed")
            }
            socket = opened
            input = DataInputStream(opened.getInputStream())
            output = opened.getOutputStream()
        }
        try {
            val registered = exchange(Cip.REGISTER, byteArrayOf(1, 0, 0, 0), expectSession = false)
            session = registered.session
            if (session == 0L) throw IOException("register session returned no handle")
            val open = forwardOpen()
            if (open.status == 0 && open.data.size >= 8) {
                o2t = u32(open.data, 0)
                t2o = u32(open.data, 4)
                unconnected = false
                connected = true
                val identity = request(Cip.message(Cip.GET_ALL, byteArrayOf(0x20, 0x01, 0x24, 0x01)))
                if (identity.status == 0) productName = identityName(identity.data)
            } else {
                // Forward Open refused: try the same route with unconnected messages.
                val identity = sendUnconnected(Cip.message(Cip.GET_ALL, byteArrayOf(0x20, 0x01, 0x24, 0x01)))
                if (identity.status != 0) {
                    throw IOException(
                        "forward open status 0x${open.status.toString(16)} ext 0x${open.extendedStatus.toString(16)}; " +
                            "unconnected identity status 0x${identity.status.toString(16)} ext 0x${identity.extendedStatus.toString(16)}",
                    )
                }
                productName = identityName(identity.data)
                o2t = 0
                unconnected = true
                cipBudget = minOf(UNCONNECTED_LIMIT - UNCONNECTED_OVERHEAD - route.size, config.cipConnectionSize - 2).coerceAtLeast(64)
                connected = true
            }
        } catch (e: Exception) {
            drop()
            throw e
        }
    }

    fun request(cip: ByteArray): Reply = synchronized(lock) {
        val started = System.nanoTime()
        try {
            val reply = if (connected && o2t != 0L) sendUnit(cip) else sendUnconnected(cip)
            responseNanos.addAndGet(System.nanoTime() - started)
            responseSamples.incrementAndGet()
            return reply
        } catch (e: SocketTimeoutException) {
            timeouts.incrementAndGet()
            errors.incrementAndGet()
            drop()
            throw e
        } catch (e: IOException) {
            errors.incrementAndGet()
            drop()
            throw e
        }
    }

    fun close() {
        connected = false
        if (o2t != 0L && socket != null) {
            runCatching { sendRr(forwardClose()) }
        }
        drop()
    }

    fun shutdown() {
        closed = true
        close()
    }

    private fun forwardOpen(): Reply {
        val size = config.cipConnectionSize
        val large = size > 511 && config.profile.largeForwardOpen
        val service = if (large) Cip.FORWARD_LARGE else Cip.FORWARD_OPEN
        val path = route + byteArrayOf(0x20, 0x02, 0x24, 0x01)
        val rpi = (config.cipConnectionTimeoutMs.coerceAtMost(60_000) * 250L).coerceAtLeast(1_000L)
        val body = Le()
        body.u8(0x05)
        body.u8(249)
        val id1 = 0x1000L + (serial.get() and 0xFFFF)
        val id2 = id1 + 1
        body.u32(id1)
        body.u32(id2)
        body.u16(serial.getAndIncrement() and 0xFFFF)
        body.u16(0xF00D)
        body.u32(0x4F495000)
        body.u8(1)
        body.u8(0); body.u8(0); body.u8(0)
        body.u32(rpi)
        if (large) body.u32((size.toLong() and 0xFFFF) or 0x42000000L) else body.u16((size and 0x1FF) or 0x4200)
        body.u32(rpi)
        if (large) body.u32((size.toLong() and 0xFFFF) or 0x42000000L) else body.u16((size and 0x1FF) or 0x4200)
        body.u8(0xA3)
        body.u8(path.size / 2)
        body.bytes(path)
        val reply = sendRr(Cip.message(service, byteArrayOf(0x20, 0x06, 0x24, 0x01), body.toByteArray()))
        val accepted = size.coerceAtMost(4000)
        cipBudget = (accepted - 2).coerceAtLeast(64)
        return reply
    }

    private fun forwardClose(): ByteArray {
        val path = route + byteArrayOf(0x20, 0x02, 0x24, 0x01)
        val body = Le()
        body.u8(0x05)
        body.u8(249)
        body.u16((serial.get() - 1) and 0xFFFF)
        body.u16(0xF00D)
        body.u32(0x4F495000)
        body.u8(path.size / 2)
        body.u8(0)
        body.bytes(path)
        return Cip.message(Cip.FORWARD_CLOSE, byteArrayOf(0x20, 0x06, 0x24, 0x01), body.toByteArray())
    }

    /**
     * An unconnected request. With a route it is wrapped in Unconnected Send to the local
     * Connection Manager, which forwards it hop by hop; with no route (a Micro800 addressed
     * directly) it goes to the module's own Message Router.
     */
    private fun sendUnconnected(cip: ByteArray): Reply {
        if (route.isEmpty()) return sendRr(cip)
        return sendRr(Cip.unconnectedSend(cip, route, config.timeoutMs))
    }

    private fun sendRr(cip: ByteArray): Reply {
        val payload = Le()
        payload.u32(0)
        payload.u16(1)
        payload.u16(2)
        payload.u16(0x0000)
        payload.u16(0)
        payload.u16(0x00B2)
        payload.u16(cip.size)
        payload.bytes(cip)
        val frame = exchange(Cip.SEND_RR, payload.toByteArray(), expectSession = true)
        return unwrap(frame.body, connected = false)
    }

    private fun sendUnit(cip: ByteArray): Reply {
        val seq = sequence
        sequence = (sequence + 1) and 0xFFFF
        val data = ByteArray(2 + cip.size)
        put16(data, 0, seq)
        cip.copyInto(data, 2)
        val payload = Le()
        payload.u32(0)
        payload.u16(1)
        payload.u16(2)
        payload.u16(0x00A1)
        payload.u16(4)
        payload.u32(o2t)
        payload.u16(0x00B1)
        payload.u16(data.size)
        payload.bytes(data)
        val frame = exchange(Cip.SEND_UNIT, payload.toByteArray(), expectSession = true)
        return unwrap(frame.body, connected = true)
    }

    private fun unwrap(body: ByteArray, connected: Boolean): Reply {
        if (body.size < 6) throw IOException("short encapsulation data")
        var p = 6
        val count = u16(body, p)
        p += 2
        var cip: ByteArray? = null
        repeat(count) {
            if (p + 4 > body.size) throw IOException("short item")
            val type = u16(body, p)
            val len = u16(body, p + 2)
            p += 4
            if (p + len > body.size) throw IOException("short item")
            val data = body.copyOfRange(p, p + len)
            p += len
            if (!connected && type == 0x00B2) cip = data
            if (connected && type == 0x00B1 && data.size >= 2) cip = data.copyOfRange(2, data.size)
        }
        return Cip.parseReply(cip ?: throw IOException("no CIP item"))
    }

    private fun exchange(command: Int, body: ByteArray, expectSession: Boolean): Frame {
        val ctx = context++
        val header = ByteArray(24)
        put16(header, 0, command)
        put16(header, 2, body.size)
        put32(header, 4, if (expectSession) session else 0)
        put32(header, 12, ctx.toLong())
        val inp: DataInputStream
        val out: java.io.OutputStream
        synchronized(lock) {
            inp = input ?: throw NotConnectedException()
            out = output ?: throw NotConnectedException()
            requests.incrementAndGet()
            out.write(header)
            out.write(body)
            out.flush()
        }
        val resp = ByteArray(24)
        inp.readFully(resp)
        val status = u32(resp, 8)
        val length = u16(resp, 2)
        val got = ByteArray(length)
        if (length > 0) inp.readFully(got)
        if (u32(resp, 12) != ctx.toLong()) throw IOException("encapsulation context mismatch")
        if (status != 0L) throw IOException("encapsulation status 0x${status.toString(16)}")
        return Frame(u32(resp, 4), got)
    }

    private fun drop() {
        connected = false
        o2t = 0
        synchronized(lock) {
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
        }
    }

    private class Frame(val session: Long, val body: ByteArray)

    private companion object {
        /** Largest unconnected CIP message an EtherNet/IP module forwards. */
        const val UNCONNECTED_LIMIT = 504
        /** Unconnected Send service, path, timing, size, pad and route size bytes around the message. */
        const val UNCONNECTED_OVERHEAD = 12
    }
}

private fun identityName(data: ByteArray): String {
    if (data.size < 15) return ""
    val len = data[14].toInt() and 0xFF
    if (data.size < 15 + len) return ""
    return data.copyOfRange(15, 15 + len).toString(Charsets.US_ASCII)
}
