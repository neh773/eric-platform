package org.oip.modbus

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class NotConnectedException(message: String = "not connected") : IOException(message)

internal class ModbusDeviceException(val code: Int) : IOException("modbus exception $code")

/**
 * One TCP socket. Modbus TCP uses the MBAP header and correlates responses by transaction id,
 * so [ModbusDeviceConfig.concurrentRequests] may be greater than one.
 * RTU over TCP is a unit id, a PDU, and a CRC-16 with no transaction id, so only one request
 * is in flight. Framing: Modbus Messaging Implementation Guide (MBAP) and the RTU frame in the
 * Modbus over Serial Line spec, carried on TCP the way the reference platform's "Modbus RTU over TCP" driver does.
 */
internal class ModbusSession(private val config: ModbusDeviceConfig) {
    private val writeLock = Any()
    private val rtuLock = Any()
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val tx = AtomicInteger(0)
    private val permits = Semaphore(if (config.mode == ModbusMode.TCP) config.concurrentRequests.coerceAtMost(32) else 1)
    private val consecutiveTimeouts = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    /** Completed PDUs, errors, timeouts, and response time. The poll path only touches these atomics. */
    val stats = ResponseStats()
    val requestCount: AtomicLong get() = stats.requests
    val failureCount: AtomicLong get() = stats.errors
    val timeoutCount: AtomicLong get() = stats.timeouts
    /** Highest number of Modbus TCP requests in flight on this socket. RTU stays at 1. */
    val peakInFlight = AtomicInteger(0)

    @Volatile private var socket: Socket? = null
    @Volatile private var reader: Thread? = null

    fun isConnected(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed
    }

    @Synchronized
    fun connect() {
        closeSocket()
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        if (!config.localAddress.isNullOrBlank()) s.bind(InetSocketAddress(config.localAddress, 0))
        s.connect(InetSocketAddress(config.host, config.port), config.timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        s.soTimeout = 0
        socket = s
        if (config.mode == ModbusMode.TCP) startReader(s)
    }

    fun transact(unitId: Int, pdu: ByteArray): ByteArray {
        var last: Exception? = null
        val attempts = if (isDeviceRetryable(pdu)) config.maxRetryCount + 1 else 1
        repeat(attempts) { n ->
            try {
                return transactOnce(unitId, pdu)
            } catch (e: ModbusDeviceException) {
                last = e
                failureCount.incrementAndGet()
                if (n == attempts - 1) throw e
            }
        }
        throw last ?: NotConnectedException()
    }

    private fun isDeviceRetryable(@Suppress("UNUSED_PARAMETER") pdu: ByteArray) = config.maxRetryCount > 0

    private fun transactOnce(unitId: Int, pdu: ByteArray): ByteArray {
        val s = socket ?: throw NotConnectedException()
        if (s.isClosed) throw NotConnectedException()
        permits.acquire()
        val now = inFlight.incrementAndGet()
        peakInFlight.updateAndGet { prev -> if (now > prev) now else prev }
        val started = System.nanoTime()
        try {
            requestCount.incrementAndGet()
            val response = if (config.mode == ModbusMode.TCP) transactTcp(s, unitId, pdu) else transactRtu(s, unitId, pdu)
            stats.noteSample(System.nanoTime() - started)
            return response
        } catch (e: ModbusDeviceException) {
            // An exception response still arrived. A timeout does not, so it is left out of the average.
            stats.noteSample(System.nanoTime() - started)
            throw e
        } finally {
            inFlight.decrementAndGet()
            permits.release()
        }
    }

    private fun transactTcp(socket: Socket, unitId: Int, pdu: ByteArray): ByteArray {
        val id = tx.incrementAndGet() and 0xFFFF
        val future = CompletableFuture<ByteArray>()
        pending[id] = Pending(unitId, future)
        try {
            val frame = mbap(id, unitId, pdu)
            synchronized(writeLock) {
                val out = DataOutputStream(socket.getOutputStream())
                out.write(frame)
                out.flush()
            }
            val response = try {
                future.get(config.timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                pending.remove(id)
                noteTimeout()
                throw SocketTimeoutException("modbus timeout")
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: "modbus read failed", e.cause)
            }
            consecutiveTimeouts.set(0)
            return checkException(response)
        } catch (e: IOException) {
            if (e !is SocketTimeoutException && e !is ModbusDeviceException) {
                // A dropped socket is an error. Timeouts and exception responses are counted where they are thrown.
                failureCount.incrementAndGet()
                closeSocket()
            }
            throw e
        }
    }

    private fun transactRtu(socket: Socket, unitId: Int, pdu: ByteArray): ByteArray {
        synchronized(rtuLock) {
            try {
                socket.soTimeout = config.timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                out.write(rtuFrame(unitId, pdu))
                out.flush()
                val response = readRtuPdu(input, unitId)
                consecutiveTimeouts.set(0)
                return checkException(response)
            } catch (e: SocketTimeoutException) {
                noteTimeout()
                throw e
            } catch (e: IOException) {
                if (e !is ModbusDeviceException) {
                    failureCount.incrementAndGet()
                    closeSocket()
                }
                throw e
            }
        }
    }

    private fun noteTimeout() {
        failureCount.incrementAndGet()
        timeoutCount.incrementAndGet()
        if (consecutiveTimeouts.incrementAndGet() >= config.reconnectAfterConsecutiveTimeouts) closeSocket()
    }

    private fun startReader(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        reader = Thread({
            try {
                while (!socket.isClosed) {
                    val header = ByteArray(7)
                    input.readFully(header)
                    val id = u16(header, 0)
                    val protocolId = u16(header, 2)
                    val length = u16(header, 4)
                    val unit = header[6].toInt() and 0xFF
                    if (length < 1 || length > 300) throw IOException("bad MBAP length $length")
                    val pdu = ByteArray(length - 1)
                    if (pdu.isNotEmpty()) input.readFully(pdu)
                    val slot = pending.remove(id)
                    // Modbus Messaging Implementation Guide: protocol id is 0. A non-zero value is
                    // not a Modbus reply, so the socket is dropped rather than resynchronized.
                    if (protocolId != 0) {
                        val error = IOException("MBAP protocol id $protocolId")
                        slot?.future?.completeExceptionally(error)
                        throw error
                    }
                    if (slot == null) continue
                    if (unit != slot.unitId) {
                        slot.future.completeExceptionally(IOException("MBAP unit id $unit != ${slot.unitId}"))
                    } else {
                        slot.future.complete(pdu)
                    }
                }
            } catch (_: EOFException) {
                failPending(IOException("connection closed"))
            } catch (e: IOException) {
                failPending(e)
            } finally {
                closeIfCurrent(socket)
            }
        }, "modbus-rx-${config.name}").apply { isDaemon = true; start() }
    }

    private fun failPending(error: IOException) {
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.future.completeExceptionally(error) }
    }

    @Synchronized
    fun close() = closeSocket()

    /** Close [s] only if it is still the active socket, so a reader exiting an old connection cannot drop a new one. */
    @Synchronized
    private fun closeIfCurrent(s: Socket) {
        if (socket === s) closeSocket()
    }

    @Synchronized
    private fun closeSocket() {
        val s = socket ?: return
        socket = null
        try { s.close() } catch (_: IOException) {}
        failPending(NotConnectedException("socket closed"))
    }

    private fun checkException(pdu: ByteArray): ByteArray {
        if (pdu.isEmpty()) throw IOException("empty pdu")
        val fc = pdu[0].toInt() and 0xFF
        if (fc and 0x80 != 0) {
            val code = if (pdu.size > 1) pdu[1].toInt() and 0xFF else 0
            throw ModbusDeviceException(code)
        }
        return pdu
    }

    private fun mbap(id: Int, unitId: Int, pdu: ByteArray): ByteArray {
        val length = pdu.size + 1
        val out = ByteArray(7 + pdu.size)
        out[0] = (id ushr 8).toByte(); out[1] = id.toByte()
        out[2] = 0; out[3] = 0
        out[4] = (length ushr 8).toByte(); out[5] = length.toByte()
        out[6] = unitId.toByte()
        pdu.copyInto(out, 7)
        return out
    }

    private fun rtuFrame(unitId: Int, pdu: ByteArray): ByteArray {
        val body = ByteArray(1 + pdu.size)
        body[0] = unitId.toByte()
        pdu.copyInto(body, 1)
        val crc = ModbusCodec.crc16(body)
        return body + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }

    private fun readRtuPdu(input: DataInputStream, expectedUnit: Int): ByteArray {
        val unit = input.readUnsignedByte()
        if (unit != expectedUnit) throw IOException("RTU unit id $unit != $expectedUnit")
        val fc = input.readUnsignedByte()
        val rest = when {
            fc and 0x80 != 0 -> byteArrayOf(input.readUnsignedByte().toByte())
            fc == 1 || fc == 2 || fc == 3 || fc == 4 -> {
                val count = input.readUnsignedByte()
                val data = ByteArray(count)
                if (count > 0) input.readFully(data)
                byteArrayOf(count.toByte()) + data
            }
            fc == 5 || fc == 6 || fc == 15 || fc == 16 -> {
                val echo = ByteArray(4); input.readFully(echo); echo
            }
            fc == 22 -> {
                val echo = ByteArray(6); input.readFully(echo); echo
            }
            else -> throw IOException("unsupported function $fc")
        }
        val crcLo = input.readUnsignedByte()
        val crcHi = input.readUnsignedByte()
        val frame = byteArrayOf(unit.toByte(), fc.toByte()) + rest
        val expect = ModbusCodec.crc16(frame)
        val got = crcLo or (crcHi shl 8)
        if (expect != got) throw IOException("RTU CRC mismatch")
        return byteArrayOf(fc.toByte()) + rest
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private class Pending(val unitId: Int, val future: CompletableFuture<ByteArray>)
}

internal object ModbusPdu {
    fun read(area: ModbusArea, start: Int, quantity: Int): ByteArray {
        val fc = when (area) {
            ModbusArea.COIL -> 1
            ModbusArea.DISCRETE_INPUT -> 2
            ModbusArea.HOLDING_REGISTER -> 3
            ModbusArea.INPUT_REGISTER -> 4
        }
        return byteArrayOf(fc.toByte(), hib(start), lob(start), hib(quantity), lob(quantity))
    }

    fun writeSingleCoil(address: Int, on: Boolean): ByteArray {
        val v = if (on) 0xFF00 else 0
        return byteArrayOf(5, hib(address), lob(address), hib(v), lob(v))
    }

    fun writeSingleRegister(address: Int, value: Int): ByteArray =
        byteArrayOf(6, hib(address), lob(address), hib(value), lob(value))

    fun writeMultipleRegisters(address: Int, wire: ByteArray): ByteArray {
        val qty = wire.size / 2
        val out = ByteArray(6 + wire.size)
        out[0] = 16
        out[1] = hib(address); out[2] = lob(address)
        out[3] = hib(qty); out[4] = lob(qty)
        out[5] = wire.size.toByte()
        wire.copyInto(out, 6)
        return out
    }

    fun maskWrite(address: Int, andMask: Int, orMask: Int): ByteArray =
        byteArrayOf(0x16, hib(address), lob(address), hib(andMask), lob(andMask), hib(orMask), lob(orMask))

    /** Register or coil payload inside a read response, without the function and byte-count. */
    fun payload(pdu: ByteArray): ByteArray {
        if (pdu.size < 2) throw IOException("short read response")
        val count = pdu[1].toInt() and 0xFF
        if (pdu.size < 2 + count) throw IOException("short read payload")
        return pdu.copyOfRange(2, 2 + count)
    }

    private fun hib(v: Int) = ((v ushr 8) and 0xFF).toByte()
    private fun lob(v: Int) = (v and 0xFF).toByte()
}
