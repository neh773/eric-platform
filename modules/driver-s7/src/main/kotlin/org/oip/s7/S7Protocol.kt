package org.oip.s7

import java.io.DataInputStream
import java.io.IOException

/**
 * ISO-on-TCP (RFC 1006 TPKT), COTP (ISO/IEC 8073 class 0), and the S7 job header used by
 * S7-300/400/1200/1500 on TCP port 102.
 *
 * TPKT is a 4-byte header (version 3, reserved 0, length including the header). COTP connection
 * request carries the calling and called TSAPs. A data transfer (DT, 0xF0) with the last-unit
 * flag carries one S7 message. The negotiated PDU size is the maximum S7 message (it does not
 * include TPKT or COTP).
 *
 * Job header (10 bytes): protocol 0x32, ROSCTR 0x01, redundancy id, PDU reference, parameter
 * length, data length. Ack-data (ROSCTR 0x03) adds a 2-byte error class and code. Setup is
 * function 0xF0. Read is 0x04 and write is 0x05. Each any-type item is 12 bytes: spec 0x12,
 * length 0x0A, syntax 0x10, transport size, element count, DB number, area, 24-bit bit address.
 */
internal class S7ProtocolException(message: String) : IOException(message)

internal class NotConnectedException(message: String) : IOException(message)

internal object S7Wire {
    const val JOB = 0x01
    const val ACK_DATA = 0x03
    const val SETUP = 0xF0
    const val READ = 0x04
    const val WRITE = 0x05
    const val TS_BIT = 0x01
    const val TS_BYTE = 0x02
    const val TS_TIMER = 0x1D
    const val TS_COUNTER = 0x1C
    const val DATA_BIT = 0x03
    const val DATA_BYTE = 0x04
    const val RET_OK = 0xFF

    const val AREA_I = 0x81
    const val AREA_Q = 0x82
    const val AREA_M = 0x83
    const val AREA_DB = 0x84
    const val AREA_C = 0x1C
    const val AREA_T = 0x1D

    const val HEADER_JOB = 10
    const val HEADER_ACK = 12
    const val ITEM_SPEC = 12

    fun areaCode(area: S7Area): Int = when (area) {
        S7Area.I -> AREA_I
        S7Area.Q -> AREA_Q
        S7Area.M -> AREA_M
        S7Area.DB -> AREA_DB
        S7Area.T -> AREA_T
        S7Area.C -> AREA_C
    }

    fun transport(kind: S7Kind): Int = when (kind) {
        S7Kind.MEMORY -> TS_BYTE
        S7Kind.TIMER -> TS_TIMER
        S7Kind.COUNTER -> TS_COUNTER
    }

    /** Bytes of one S7 read/write request for [items] any-type items and [data] bytes of write data. */
    fun requestBytes(items: Int, data: Int = 0): Int = HEADER_JOB + 2 + ITEM_SPEC * items + data

    /** Bytes of one ack-data carrying items whose payloads are [dataBytes] each (fill byte included). */
    fun responseBytes(dataBytes: List<Int>): Int =
        HEADER_ACK + 2 + dataBytes.sumOf { itemWireBytes(it) }

    fun itemWireBytes(dataBytes: Int): Int = 4 + dataBytes + (dataBytes and 1)

    /** Largest even-or-odd payload whose single-item ack-data still fits in [pdu]. */
    fun maxDataBytes(pdu: Int): Int {
        var n = (pdu - (HEADER_ACK + 2 + 4)).coerceAtLeast(0)
        while (n > 0 && responseBytes(listOf(n)) > pdu) n--
        return n
    }

    fun tpkt(payload: ByteArray): ByteArray {
        val n = payload.size + 4
        val out = ByteArray(n)
        out[0] = 3
        out[1] = 0
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        payload.copyInto(out, 4)
        return out
    }

    fun readTpkt(input: DataInputStream): ByteArray {
        val header = ByteArray(4)
        input.readFully(header)
        if ((header[0].toInt() and 0xFF) != 3) throw S7ProtocolException("TPKT version ${header[0].toInt() and 0xFF}")
        val len = u16(header, 2)
        if (len < 4 || len > 8192) throw S7ProtocolException("TPKT length $len")
        if (len == 4) return ByteArray(0)
        val rest = ByteArray(len - 4)
        input.readFully(rest)
        return rest
    }

    data class Cotp(val type: Int, val srcRef: Int, val dstRef: Int, val params: ByteArray, val userData: ByteArray)

    fun parseCotp(packet: ByteArray): Cotp {
        if (packet.isEmpty()) throw S7ProtocolException("empty COTP")
        val li = packet[0].toInt() and 0xFF
        if (packet.size < 1 + li) throw S7ProtocolException("short COTP header")
        val type = packet[1].toInt() and 0xFF
        val dst = if (li >= 3) u16(packet, 2) else 0
        val src = if (li >= 5) u16(packet, 4) else 0
        val paramStart = if (type == 0xF0 || type == 0x80) 1 + li else minOf(7, 1 + li)
        val headerEnd = 1 + li
        val params = if (paramStart < headerEnd) packet.copyOfRange(paramStart, headerEnd) else ByteArray(0)
        val user = if (headerEnd < packet.size) packet.copyOfRange(headerEnd, packet.size) else ByteArray(0)
        return Cotp(type, src, dst, params, user)
    }

    fun connectionRequest(srcRef: Int, localTsap: Int, remoteTsap: Int): ByteArray {
        val params = byteArrayOf(
            0xC0.toByte(), 0x01, 0x0A,
            0xC1.toByte(), 0x02, (localTsap ushr 8).toByte(), localTsap.toByte(),
            0xC2.toByte(), 0x02, (remoteTsap ushr 8).toByte(), remoteTsap.toByte(),
        )
        val li = 6 + params.size
        val cotp = ByteArray(1 + li)
        cotp[0] = li.toByte()
        cotp[1] = 0xE0.toByte()
        put16(cotp, 4, srcRef)
        params.copyInto(cotp, 7)
        return tpkt(cotp)
    }

    fun dataTransfer(s7: ByteArray): ByteArray {
        val cotp = ByteArray(3 + s7.size)
        cotp[0] = 2
        cotp[1] = 0xF0.toByte()
        cotp[2] = 0x80.toByte()
        s7.copyInto(cotp, 3)
        return tpkt(cotp)
    }

    fun tsap(params: ByteArray, code: Int): Int? {
        var i = 0
        while (i + 2 <= params.size) {
            val c = params[i].toInt() and 0xFF
            val n = params[i + 1].toInt() and 0xFF
            if (i + 2 + n > params.size) return null
            if (c == code && n >= 2) return u16(params, i + 2)
            i += 2 + n
        }
        return null
    }

    fun job(ref: Int, param: ByteArray, data: ByteArray = ByteArray(0)): ByteArray {
        val msg = ByteArray(HEADER_JOB + param.size + data.size)
        msg[0] = 0x32
        msg[1] = JOB.toByte()
        put16(msg, 4, ref)
        put16(msg, 6, param.size)
        put16(msg, 8, data.size)
        param.copyInto(msg, HEADER_JOB)
        if (data.isNotEmpty()) data.copyInto(msg, HEADER_JOB + param.size)
        return msg
    }

    fun setupParam(pdu: Int): ByteArray {
        val p = ByteArray(8)
        p[0] = SETUP.toByte()
        put16(p, 2, 1)
        put16(p, 4, 1)
        put16(p, 6, pdu)
        return p
    }

    data class Ack(val ref: Int, val errorClass: Int, val errorCode: Int, val param: ByteArray, val data: ByteArray)

    fun parseAck(msg: ByteArray): Ack {
        if (msg.size < HEADER_ACK || (msg[0].toInt() and 0xFF) != 0x32) {
            throw S7ProtocolException("not an S7 message")
        }
        val rosctr = msg[1].toInt() and 0xFF
        if (rosctr != ACK_DATA) throw S7ProtocolException("S7 ROSCTR $rosctr")
        val ref = u16(msg, 4)
        val plen = u16(msg, 6)
        val dlen = u16(msg, 8)
        if (msg.size < HEADER_ACK + plen + dlen) throw S7ProtocolException("short S7 ack")
        return Ack(
            ref,
            msg[10].toInt() and 0xFF,
            msg[11].toInt() and 0xFF,
            msg.copyOfRange(HEADER_ACK, HEADER_ACK + plen),
            msg.copyOfRange(HEADER_ACK + plen, HEADER_ACK + plen + dlen),
        )
    }

    data class DataItem(val returnCode: Int, val bytes: ByteArray)

    /**
     * Walk ack-data items. A success item is a 4-byte header plus the payload, and an odd payload
     * is followed by a fill byte. A failed item is the 4-byte header only.
     */
    fun parseDataItems(data: ByteArray, count: Int): List<DataItem> {
        val out = ArrayList<DataItem>(count)
        var i = 0
        repeat(count) {
            if (i + 4 > data.size) throw S7ProtocolException("short S7 data item")
            val rc = data[i].toInt() and 0xFF
            val bits = u16(data, i + 2)
            if (rc != RET_OK) {
                out += DataItem(rc, ByteArray(0))
                i += 4
            } else {
                val nbytes = (bits + 7) / 8
                val start = i + 4
                if (start + nbytes > data.size) throw S7ProtocolException("short S7 data item")
                out += DataItem(rc, data.copyOfRange(start, start + nbytes))
                i = start + nbytes + (nbytes and 1)
            }
        }
        return out
    }

    /** Write ack is one return code per item, or the same 4-byte items as a read. */
    fun parseWriteCodes(data: ByteArray, count: Int): List<Int> {
        if (data.size == count) return List(count) { data[it].toInt() and 0xFF }
        return parseDataItems(data, count).map { it.returnCode }
    }

    fun readParam(slices: List<ReadSlice>): ByteArray {
        val p = ByteArray(2 + ITEM_SPEC * slices.size)
        p[0] = READ.toByte()
        p[1] = slices.size.toByte()
        var o = 2
        for (slice in slices) {
            writeItem(p, o, slice)
            o += ITEM_SPEC
        }
        return p
    }

    fun writeParam(area: S7Area, db: Int, transport: Int, elements: Int, bitAddress: Int): ByteArray {
        val p = ByteArray(2 + ITEM_SPEC)
        p[0] = WRITE.toByte()
        p[1] = 1
        writeItem(p, 2, areaCode(area), db, transport, elements, bitAddress)
        return p
    }

    fun writePayload(dataTransport: Int, bitLength: Int, payload: ByteArray): ByteArray {
        val pad = payload.size and 1
        val out = ByteArray(4 + payload.size + pad)
        out[1] = dataTransport.toByte()
        put16(out, 2, bitLength)
        payload.copyInto(out, 4)
        return out
    }

    private fun writeItem(p: ByteArray, o: Int, slice: ReadSlice) {
        val transport = transport(slice.kind)
        val elements = if (slice.kind == S7Kind.MEMORY) slice.length else slice.length
        val bitAddress = if (slice.kind == S7Kind.MEMORY) slice.start * 8 else slice.start * 8
        writeItem(p, o, areaCode(slice.area), slice.db, transport, elements, bitAddress)
    }

    private fun writeItem(p: ByteArray, o: Int, area: Int, db: Int, transport: Int, elements: Int, bitAddress: Int) {
        p[o] = 0x12
        p[o + 1] = 0x0A
        p[o + 2] = 0x10
        p[o + 3] = transport.toByte()
        put16(p, o + 4, elements)
        put16(p, o + 6, db)
        p[o + 8] = area.toByte()
        p[o + 9] = (bitAddress ushr 16).toByte()
        p[o + 10] = (bitAddress ushr 8).toByte()
        p[o + 11] = bitAddress.toByte()
    }

    fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    fun qualityForReturn(code: Int): QualityCode = when (code) {
        RET_OK -> QualityCode.Good
        0x01 -> QualityCode.Bad_DeviceFailure
        0x03 -> QualityCode.Bad_NotWritable
        0x05 -> QualityCode.Bad_OutOfRange
        0x06, 0x07 -> QualityCode.Bad_TypeMismatch
        0x0A -> QualityCode.Bad_NotFound
        else -> QualityCode.Bad
    }
}
