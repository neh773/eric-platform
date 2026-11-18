package org.oip.s7

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * One ISO-on-TCP session. A single job is in flight: the S7 PDU reference has to match the
 * ack, so a timed-out read closes the socket instead of leaving a late reply for the next job.
 */
internal class S7ItemException(val code: Int) : IOException("S7 item status 0x${code.toString(16)}")

internal class S7Session(private val config: S7DeviceConfig) {
    private val ioLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile var negotiatedPdu: Int = config.pduSize
        private set

    val requestCount = AtomicLong()
    val failureCount = AtomicLong()
    val timeoutCount = AtomicLong()
    val readJobs = AtomicLong()
    private val responseNanos = AtomicLong()
    private val responseSamples = AtomicLong()
    private var pduRef = 1

    fun isConnected(): Boolean = connected && socket != null

    /** Mean time from sending a job to its acknowledgement, over this session's life. Null before the first. */
    fun averageMillis(): Double? {
        val n = responseSamples.get()
        if (n == 0L) return null
        return (responseNanos.get().toDouble() / n) / 1_000_000.0
    }

    fun activePdu(): Int = negotiatedPdu

    fun connect() {
        if (closed) throw IOException("session closed")
        val timeout = config.timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val opened = Socket()
        opened.tcpNoDelay = true
        opened.keepAlive = true
        opened.soTimeout = timeout
        val local = config.localAddress
        if (!local.isNullOrBlank()) opened.bind(InetSocketAddress(local, 0))
        opened.connect(InetSocketAddress(config.host, config.port), timeout)
        val inp = DataInputStream(opened.getInputStream())
        val out = DataOutputStream(opened.getOutputStream())
        synchronized(ioLock) {
            if (closed) {
                opened.close()
                throw IOException("session closed")
            }
            socket = opened
            input = inp
            output = out
        }
        try {
            out.write(S7Wire.connectionRequest(1, config.resolvedLocalTsap(), config.resolvedRemoteTsap()))
            out.flush()
            val cc = S7Wire.parseCotp(S7Wire.readTpkt(inp))
            if (cc.type != 0xD0) throw S7ProtocolException("expected COTP confirm, got 0x${cc.type.toString(16)}")
            val ack = exchange(S7Wire.setupParam(config.pduSize))
            if (ack.errorClass != 0 || ack.errorCode != 0 || ack.param.size < 8 ||
                (ack.param[0].toInt() and 0xFF) != S7Wire.SETUP
            ) {
                throw S7ProtocolException("S7 communication setup was rejected")
            }
            val offered = S7Wire.u16(ack.param, 6)
            if (offered < 20) throw S7ProtocolException("negotiated PDU $offered")
            negotiatedPdu = minOf(config.pduSize, offered)
            connected = true
        } catch (e: Exception) {
            drop()
            throw e
        }
    }

    fun close() {
        connected = false
        drop()
    }

    fun shutdown() {
        closed = true
        close()
    }

    fun read(slices: List<ReadSlice>): List<S7Wire.DataItem> {
        val ack = exchange(S7Wire.readParam(slices))
        if (ack.errorClass != 0 || ack.errorCode != 0) {
            throw S7ProtocolException("S7 read error ${ack.errorClass}/${ack.errorCode}")
        }
        readJobs.incrementAndGet()
        return S7Wire.parseDataItems(ack.data, slices.size)
    }

    fun write(address: ParsedAddress, payload: ByteArray) {
        val ack = when (address.type) {
            S7Type.BIT -> {
                val on = payload.isNotEmpty() && payload[0].toInt() != 0
                val data = S7Wire.writePayload(S7Wire.DATA_BIT, 1, byteArrayOf(if (on) 1 else 0))
                val bitAddress = address.start * 8 + (address.bit ?: 0)
                exchange(S7Wire.writeParam(address.area, address.db, S7Wire.TS_BIT, 1, bitAddress), data)
            }
            S7Type.TIMER, S7Type.COUNTER -> {
                val transport = if (address.type == S7Type.TIMER) S7Wire.TS_TIMER else S7Wire.TS_COUNTER
                val data = S7Wire.writePayload(S7Wire.DATA_BYTE, payload.size * 8, payload)
                exchange(S7Wire.writeParam(address.area, address.db, transport, 1, address.start * 8), data)
            }
            else -> {
                val data = S7Wire.writePayload(S7Wire.DATA_BYTE, payload.size * 8, payload)
                val message = S7Wire.HEADER_JOB + (2 + S7Wire.ITEM_SPEC) + data.size
                if (message > activePdu()) throw AddressException("write of ${payload.size} bytes exceeds negotiated PDU $negotiatedPdu")
                exchange(S7Wire.writeParam(address.area, address.db, S7Wire.TS_BYTE, payload.size, address.start * 8), data)
            }
        }
        if (ack.errorClass != 0 || ack.errorCode != 0) {
            throw S7ProtocolException("S7 write error ${ack.errorClass}/${ack.errorCode}")
        }
        val code = S7Wire.parseWriteCodes(ack.data, 1).single()
        if (code != S7Wire.RET_OK) throw S7ItemException(code)
    }

    private fun exchange(param: ByteArray, data: ByteArray = ByteArray(0)): S7Wire.Ack {
        synchronized(ioLock) {
            val out = output ?: throw NotConnectedException("not connected")
            val inp = input ?: throw NotConnectedException("not connected")
            if (socket == null) throw NotConnectedException("not connected")
            val ref = pduRef
            pduRef = if (pduRef == 0xFFFF) 1 else pduRef + 1
            val started = System.nanoTime()
            try {
                out.write(S7Wire.dataTransfer(S7Wire.job(ref, param, data)))
                out.flush()
                val cotp = S7Wire.parseCotp(S7Wire.readTpkt(inp))
                if ((cotp.type and 0xF0) != 0xF0) throw S7ProtocolException("expected COTP data")
                val ack = S7Wire.parseAck(cotp.userData)
                if (ack.ref != ref) throw S7ProtocolException("PDU reference ${ack.ref} != $ref")
                responseNanos.addAndGet(System.nanoTime() - started)
                responseSamples.incrementAndGet()
                requestCount.incrementAndGet()
                return ack
            } catch (e: Exception) {
                if (e is SocketTimeoutException) timeoutCount.incrementAndGet()
                failureCount.incrementAndGet()
                connected = false
                drop()
                throw e
            }
        }
    }

    private fun drop() {
        val s = socket
        socket = null
        input = null
        output = null
        connected = false
        try {
            s?.close()
        } catch (_: IOException) {
        }
    }
}
