package org.oip.udptcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Message cutting with a bounded buffer. doc source: FEATURES DEV-037 */
class FramerTest {
    private val messages = ArrayList<String>()
    private var dropped = 0

    private fun framer(type: DelimiterType, delimiter: String = "\r\n", fixed: Int = 0, max: Int = 16) =
        Framer(type, delimiter.toByteArray(), fixed, max, { messages += String(it) }, { dropped++ })

    @Test fun characterDelimiterSplitsAcrossAndWithinChunks() {
        val f = framer(DelimiterType.CHARACTER)
        f.feed("A1,12".toByteArray())
        f.feed(",kg\r".toByteArray())
        f.feed("\nB2,13,kg\r\nC".toByteArray())
        assertEquals(listOf("A1,12,kg", "B2,13,kg"), messages)
        assertEquals(1, f.pending)
        f.feed("\r\n".toByteArray())
        assertEquals("C", messages.last())
        f.feed("\r\n".toByteArray())
        assertEquals("", messages.last(), "an empty message is still a message")
    }

    @Test fun oversizeMessageIsDroppedAndTheNextOneIsKept() {
        val f = framer(DelimiterType.CHARACTER, max = 8)
        f.feed("exactly8\r\n".toByteArray())
        assertEquals(listOf("exactly8"), messages)
        f.feed(ByteArray(100_000) { 'x'.code.toByte() })
        assertEquals(1, dropped)
        assertTrue(f.highWater <= f.capacity, "held ${f.highWater} of ${f.capacity}")
        assertEquals(10, f.capacity)
        f.feed("still dropped\r\nok\r\n".toByteArray())
        assertEquals(listOf("exactly8", "ok"), messages)
        assertEquals(1, dropped)
        // Nine bytes then a delimiter split across the boundary: dropped, and the skip ends at that delimiter.
        f.feed("ninebytes\r".toByteArray())
        f.feed("\nnext\r\n".toByteArray())
        assertEquals(2, dropped)
        assertEquals("next", messages.last())
    }

    @Test fun fixedSizeAndPacketBased() {
        val fixed = framer(DelimiterType.FIXED, fixed = 4, max = 4)
        fixed.feed("ABCDEF".toByteArray())
        fixed.feed("GH".toByteArray())
        assertEquals(listOf("ABCD", "EFGH"), messages)
        messages.clear()
        val packet = framer(DelimiterType.PACKET, max = 5)
        packet.feed("w=1.5".toByteArray())
        packet.feed("w=12.5".toByteArray())
        assertEquals(listOf("w=1.5"), messages)
        assertEquals(1, dropped)
    }

    @Test fun escapesAndConfigChecks() {
        assertEquals("\r\n", unescape("\\r\\n"))
        assertEquals("\t|\b|\u000C|\\", unescape("\\t|\\b|\\f|\\\\"))
        assertEquals("\\q", unescape("\\q"))
        val ok = ListenerDeviceConfig(name = "s", ports = listOf(9000))
        assertEquals("\r\n", String(ok.delimiterBytes()))
        assertThrows(IllegalArgumentException::class.java) {
            ListenerDeviceConfig(name = "s", protocol = Protocol.UDP, mode = Mode.CONNECT, address = "10.0.0.2", ports = listOf(9000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ListenerDeviceConfig(name = "s", delimiterType = DelimiterType.FIXED, fixedLength = 100, maxMessageBytes = 50, ports = listOf(9000))
        }
        assertThrows(IllegalArgumentException::class.java) { ListenerDeviceConfig(name = "s", mode = Mode.CONNECT, ports = listOf(9000)) }
        assertThrows(IllegalArgumentException::class.java) { ListenerDeviceConfig(name = "s", ports = listOf(9000, 9000)) }
        assertThrows(IllegalArgumentException::class.java) { ListenerDeviceConfig(name = "s", messageDelimiter = "", ports = listOf(9000)) }
    }
}
