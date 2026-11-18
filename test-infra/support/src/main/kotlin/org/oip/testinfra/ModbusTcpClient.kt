package org.oip.testinfra

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Modbus TCP client for the simulator's holding registers.
 * Function codes 03, 06 and 16. Framing follows the Modbus Messaging
 * Implementation Guide plus Application Protocol V1.1b3:
 * https://modbus.org/docs/Modbus_Application_Protocol_V1_1b3.pdf
 */
class ModbusTcpClient(
    host: String,
    port: Int,
    private val unitId: Int = 1,
    timeoutMs: Int = 2_000,
) : AutoCloseable {
    private val socket = Socket()
    private val lock = Any()
    private var transaction = 1

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(host, port), timeoutMs)
    }

    fun readHolding(address: Int, count: Int): IntArray = synchronized(lock) {
        require(count in 1..125)
        val pdu = byteArrayOf(
            3,
            (address shr 8).toByte(),
            address.toByte(),
            (count shr 8).toByte(),
            count.toByte(),
        )
        val response = transact(pdu)
        val byteCount = response[1].toInt() and 0xFF
        check(byteCount == count * 2) { "expected ${count * 2} data bytes, got $byteCount" }
        IntArray(count) { i ->
            val hi = response[2 + 2 * i].toInt() and 0xFF
            val lo = response[3 + 2 * i].toInt() and 0xFF
            (hi shl 8) or lo
        }
    }

    fun writeSingle(address: Int, value: Int) {
        synchronized(lock) {
            val word = value and 0xFFFF
            val pdu = byteArrayOf(
                6,
                (address shr 8).toByte(),
                address.toByte(),
                (word shr 8).toByte(),
                word.toByte(),
            )
            transact(pdu)
        }
    }

    fun writeMultiple(address: Int, values: IntArray) {
        synchronized(lock) {
            require(values.size in 1..123)
            val body = ByteArray(6 + values.size * 2)
            body[0] = 16
            body[1] = (address shr 8).toByte()
            body[2] = address.toByte()
            body[3] = (values.size shr 8).toByte()
            body[4] = values.size.toByte()
            body[5] = (values.size * 2).toByte()
            values.forEachIndexed { i, value ->
                val word = value and 0xFFFF
                body[6 + 2 * i] = (word shr 8).toByte()
                body[7 + 2 * i] = word.toByte()
            }
            transact(body)
        }
    }

    internal fun transact(pdu: ByteArray): ByteArray {
        val tid = transaction
        transaction = (transaction + 1) and 0xFFFF
        val length = 1 + pdu.size
        val frame = ByteArray(7 + pdu.size)
        frame[0] = (tid shr 8).toByte()
        frame[1] = tid.toByte()
        frame[4] = (length shr 8).toByte()
        frame[5] = length.toByte()
        frame[6] = unitId.toByte()
        pdu.copyInto(frame, destinationOffset = 7)
        socket.getOutputStream().write(frame)
        socket.getOutputStream().flush()
        // MBAP length counts the unit id and the PDU, which follow the 6-byte header.
        val header = readFully(socket.getInputStream(), 6)
        val responseTid = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        check(responseTid == tid) { "transaction id $responseTid != $tid" }
        val remaining = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        check(remaining >= 2)
        val rest = readFully(socket.getInputStream(), remaining)
        check(rest[0].toInt() and 0xFF == unitId and 0xFF)
        val response = rest.copyOfRange(1, rest.size)
        if (response.isNotEmpty() && response[0].toInt() and 0x80 != 0) {
            val code = if (response.size > 1) response[1].toInt() and 0xFF else 0
            throw ModbusException(code, "function ${pdu[0].toInt() and 0xFF}")
        }
        return response
    }

    override fun close() {
        socket.close()
    }
}

class ModbusException(val code: Int, operation: String) : RuntimeException("$operation exception code $code")

internal fun readFully(input: InputStream, count: Int): ByteArray {
    val buf = ByteArray(count)
    var off = 0
    while (off < count) {
        val n = input.read(buf, off, count - off)
        if (n < 0) throw java.io.EOFException("expected $count bytes, got $off")
        off += n
    }
    return buf
}
