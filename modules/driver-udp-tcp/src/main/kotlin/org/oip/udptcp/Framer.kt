package org.oip.udptcp

/**
 * Cuts received bytes into messages with a fixed-size buffer. Nothing here grows: a message longer
 * than [maxMessageBytes] is dropped (counted through [onDropped]) and the framer skips to the next
 * delimiter, so a device that never sends one cannot make the driver hold more than the limit.
 * doc source: FEATURES DEV-037
 *
 * Not thread safe; each connection or UDP port has its own framer on the I/O thread.
 */
internal class Framer(
    private val type: DelimiterType,
    private val delimiter: ByteArray,
    private val fixedLength: Int,
    private val maxMessageBytes: Int,
    private val onMessage: (ByteArray) -> Unit,
    private val onDropped: () -> Unit,
) {
    private val buffer = ByteArray(
        when (type) {
            DelimiterType.CHARACTER -> maxMessageBytes + delimiter.size
            DelimiterType.FIXED -> fixedLength
            DelimiterType.PACKET -> 0
        },
    )
    private var length = 0
    private var discarding = false
    /** While discarding, how many delimiter bytes have matched in a row. */
    private var matched = 0

    /** Largest number of bytes the framer ever held for one message, for tests and diagnostics. */
    var highWater = 0
        private set

    val capacity: Int get() = buffer.size

    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        when (type) {
            DelimiterType.PACKET -> packet(bytes, offset, count)
            DelimiterType.FIXED -> fixed(bytes, offset, count)
            DelimiterType.CHARACTER -> character(bytes, offset, count)
        }
    }

    /** A datagram or read larger than [maxMessageBytes] arrives as `count > maxMessageBytes`. */
    private fun packet(bytes: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        if (count > maxMessageBytes) {
            onDropped()
            return
        }
        highWater = maxOf(highWater, count)
        onMessage(bytes.copyOfRange(offset, offset + count))
    }

    private fun fixed(bytes: ByteArray, offset: Int, count: Int) {
        var i = offset
        val end = offset + count
        while (i < end) {
            val take = minOf(fixedLength - length, end - i)
            System.arraycopy(bytes, i, buffer, length, take)
            length += take
            i += take
            highWater = maxOf(highWater, length)
            if (length == fixedLength) {
                onMessage(buffer.copyOf(length))
                length = 0
            }
        }
    }

    private fun character(bytes: ByteArray, offset: Int, count: Int) {
        val d = delimiter
        for (n in offset until offset + count) {
            val b = bytes[n]
            if (discarding) {
                matched = if (b == d[matched]) matched + 1 else if (b == d[0]) 1 else 0
                if (matched == d.size) {
                    discarding = false
                    matched = 0
                }
                continue
            }
            buffer[length++] = b
            highWater = maxOf(highWater, length)
            if (length >= d.size && endsWithDelimiter()) {
                onMessage(buffer.copyOf(length - d.size))
                length = 0
                continue
            }
            if (length == buffer.size) {
                // Full and no delimiter yet: the message is too long. Keep the tail that may be the
                // start of a delimiter so a delimiter split across the boundary still ends the skip.
                onDropped()
                discarding = true
                matched = partialDelimiterAtEnd()
                length = 0
            }
        }
    }

    private fun endsWithDelimiter(): Boolean {
        val d = delimiter
        val start = length - d.size
        for (i in d.indices) if (buffer[start + i] != d[i]) return false
        return true
    }

    private fun partialDelimiterAtEnd(): Int {
        val d = delimiter
        for (k in minOf(d.size - 1, length) downTo 1) {
            var ok = true
            for (i in 0 until k) if (buffer[length - k + i] != d[i]) { ok = false; break }
            if (ok) return k
        }
        return 0
    }

    /** Bytes held toward the next message. */
    val pending: Int get() = length
}
