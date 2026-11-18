package org.oip.dnp3

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.sim.dnp3.Dnp3OutstationSimulator

/**
 * Byte vectors written from the public description of DNP3 (IEEE 1815): link frames with a CRC after the
 * header and after every 16 data octets, the transport header, application headers and objects.
 * doc source: FEATURES DEV-013, DEV-014
 */
class Dnp3CodecTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun crcMatchesTheCatalogueCheckValueAndAnIndependentBitwiseCrc() {
        // CRC-16/DNP of "123456789" is 0xEA82 in the public CRC catalogues.
        val check = "123456789".toByteArray()
        assertEquals(0xEA82, Dnp3Crc.of(check))
        assertEquals(0xEA82, Dnp3OutstationSimulator.crc(check, 0, check.size))
        val any = ByteArray(300) { (it * 37 + 11).toByte() }
        for (n in listOf(1, 8, 16, 300)) assertEquals(Dnp3OutstationSimulator.crc(any, 0, n), Dnp3Crc.of(any, 0, n), "length $n")
    }

    @Test fun linkFramesCarryACrcPerBlockAndRejectCorruption() {
        // Reset link states, master 1 to outstation 1024: DIR and PRM set, function 0; addresses little-endian.
        val reset = Dnp3Link.encode(Dnp3Link.control(dir = true, primary = true, function = Dnp3Link.RESET_LINK_STATES), 1024, 1)
        assertArrayEquals(hex("05 64 05 C0 00 04 01 00"), reset.copyOf(8))
        val crc = Dnp3Crc.of(reset, 0, 8)
        assertArrayEquals(byteArrayOf(crc.toByte(), (crc ushr 8).toByte()), reset.copyOfRange(8, 10))

        // 20 octets of user data: a 16-octet block and a 4-octet block, each followed by its CRC.
        val data = ByteArray(20) { it.toByte() }
        val frame = Dnp3Link.encode(Dnp3Link.control(dir = false, primary = true, function = Dnp3Link.UNCONFIRMED_USER_DATA), 1, 1024, data)
        assertEquals(10 + 18 + 6, frame.size)
        assertEquals(5 + 20, frame[2].toInt())
        assertEquals(0x44, frame[3].toInt() and 0xFF)
        val decoded = Dnp3Link.decode(frame)
        assertArrayEquals(data, decoded.data)
        assertEquals(1, decoded.destination); assertEquals(1024, decoded.source)
        assertTrue(decoded.primary); assertTrue(!decoded.dir); assertEquals(Dnp3Link.UNCONFIRMED_USER_DATA, decoded.function)
        // Leading noise is skipped up to the start octets.
        assertArrayEquals(data, Dnp3Link.decode(hex("FF 05 00") + frame).data)

        val badHeader = frame.copyOf().also { it[5] = 9 }
        assertThrows(Dnp3DecodeException::class.java) { Dnp3Link.decode(badHeader) }
        val badData = frame.copyOf().also { it[12] = (it[12] + 1).toByte() }
        assertThrows(Dnp3DecodeException::class.java) { Dnp3Link.decode(badData) }
        assertThrows(IllegalArgumentException::class.java) { Dnp3Link.encode(0x44, 1, 2, ByteArray(251)) }
    }

    @Test fun transportSegmentsAndReassembles() {
        val fragment = ByteArray(600) { (it % 251).toByte() }
        val segments = Dnp3Transport.segment(fragment, 62)
        assertEquals(listOf(250, 250, 103), segments.map { it.size })
        // FIR on the first, FIN on the last, sequence 62, 63, 0.
        assertEquals(listOf(0x40 or 62, 63, 0x80), segments.map { it[0].toInt() and 0xFF })
        val rx = Dnp3Transport.Receiver()
        assertNull(rx.accept(segments[0])); assertNull(rx.accept(segments[1]))
        assertArrayEquals(fragment, rx.accept(segments[2]))
        // A missing segment drops the partial fragment.
        assertNull(rx.accept(segments[0])); assertNull(rx.accept(segments[2]))
        assertArrayEquals(hex("C5 01"), Dnp3Transport.segment(hex("01"), 5).single().let { byteArrayOf(it[0], it[1]) })
    }

    @Test fun applicationRequestsAndControlBlocks() {
        // Integrity poll: READ of class 1, 2, 3 then 0 (group 60 variations 2, 3, 4, 1, all objects).
        assertArrayEquals(hex("C3 01 3C 02 06 3C 03 06 3C 04 06 3C 01 06"), Dnp3App.request(3, Dnp3App.READ, Dnp3App.classRead(listOf(1, 2, 3, 0))))
        // Confirm of an unsolicited response: FIR, FIN, UNS, sequence 9.
        assertArrayEquals(hex("D9 00"), Dnp3App.confirm(9, uns = true))
        // Clear the restart indication: WRITE group 80 variation 1, index 7, value 0.
        assertArrayEquals(hex("C0 02 50 01 00 07 07 00"), Dnp3App.request(0, Dnp3App.WRITE, Dnp3App.clearRestart()))
        // CROB latch on, index 3: count 1, index 3 (2-octet each), code 3, count 1, on and off times 0, status 0.
        assertArrayEquals(hex("0C 01 28 01 00 03 00 03 01 00 00 00 00 00 00 00 00 00"), Dnp3App.crob(3, Crob(Crob.LATCH_ON)))
        assertArrayEquals(hex("0C 01 28 01 00 01 01 41 01 F4 01 00 00 00 00 00 00 00"), Dnp3App.crob(257, Crob(Crob.CLOSE or Crob.PULSE_ON, onTimeMs = 500)))
        // Analog output, single-precision float 12.5 (0x41480000) at index 2.
        assertArrayEquals(hex("29 03 28 01 00 02 00 00 00 48 41 00"), Dnp3App.analogOutput(2, 3, 12.5))
        assertArrayEquals(hex("29 02 28 01 00 00 00 9C FF 00"), Dnp3App.analogOutput(0, 2, -100))
        // Time and date: 48-bit milliseconds since 1970, little-endian.
        assertArrayEquals(hex("32 01 07 01 00 10 32 0A 9B 01"), Dnp3App.timeObject(1, 0x019B0A321000L))
        assertEquals(0x019B0A321000L, Dnp3App.u48(hex("00 10 32 0A 9B 01"), 0))

        assertEquals(Crob.TRIP or Crob.PULSE_ON, Crob.code("trip"))
        assertEquals(Crob.LATCH_OFF, Crob.code("latch-off"))
        assertThrows(IllegalArgumentException::class.java) { Crob.code("toggle") }
        assertEquals("NO_SELECT", CommandStatus.name(2))
    }

    @Test fun responseObjectsDecode() {
        val f = Fragment.parse(hex("E3 81 82 04") + hex(
            // Binary inputs 0..2 with flags (group 1 variation 2, 1-octet start-stop): on, off, on.
            "01 02 00 00 02 81 01 81" +
                // Packed binary inputs 10..19 (group 1 variation 1): bits 0 and 9 set.
                "01 01 00 0A 13 01 02" +
                // Analog input 5, float 2.5 with flags (group 30 variation 5, 2-octet start-stop).
                "1E 05 01 05 00 05 00 01 00 00 20 40" +
                // Analog input event, index 7, 32-bit with time (group 32 variation 3, 1-octet count and index).
                "20 03 17 01 07 01 F6 FF FF FF 00 10 32 0A 9B 01" +
                // Counter 4, 32-bit with flags (group 20 variation 1, 2-octet count and index).
                "14 01 28 01 00 04 00 01 40 E2 01 00" +
                // Binary input event with time, index 2, off with comm lost.
                "02 02 17 01 02 05 00 10 32 0A 9B 01" +
                // CROB echo, index 1, status 7 (LOCAL).
                "0C 01 17 01 01 03 01 00 00 00 00 00 00 00 00 07" +
                // Time delay fine, 25 ms.
                "34 02 07 01 19 00",
        ))
        assertTrue(f.fir && f.fin && f.con && !f.uns)
        assertEquals(3, f.seq)
        assertEquals(Dnp3App.RESPONSE, f.function)
        assertTrue(f.iin.has(Dnp3App.IIN_CLASS1) && f.iin.has(Dnp3App.IIN_DEVICE_RESTART) && f.iin.has(Dnp3App.IIN_PARAMETER_ERROR))
        val o = Dnp3Objects.parse(f.objects)
        fun p(type: PointType, i: Int) = o.points.first { it.type == type && it.index == i }
        assertEquals(listOf(true, false, true), (0..2).map { p(PointType.BINARY_INPUT, it).value })
        assertEquals(true, p(PointType.BINARY_INPUT, 10).value); assertEquals(false, p(PointType.BINARY_INPUT, 11).value); assertEquals(true, p(PointType.BINARY_INPUT, 19).value)
        assertEquals(2.5, p(PointType.ANALOG_INPUT, 5).value)
        val ev = p(PointType.ANALOG_INPUT, 7)
        assertEquals(-10L, ev.value); assertEquals(0x019B0A321000L, ev.timeMs); assertTrue(ev.event)
        assertEquals(123456L, p(PointType.COUNTER, 4).value)
        val biEvent = o.points.single { it.type == PointType.BINARY_INPUT && it.index == 2 && it.event }
        assertEquals(false, biEvent.value)
        assertTrue(biEvent.flags and Dnp3Objects.FLAG_COMM_LOST != 0, biEvent.toString())
        assertEquals(0x019B0A321000L, biEvent.timeMs)
        assertEquals(CommandEcho(12, 1, 1, 7), o.commands.single())
        assertEquals(25L, o.timeDelayMs)
        assertNull(o.unknown)

        // An object this parser cannot size ends parsing and is named.
        val partial = Dnp3Objects.parse(hex("01 02 00 00 00 81 6E 01 00 00 00"))
        assertEquals(1, partial.points.size)
        assertEquals("group 110 variation 1", partial.unknown)
        assertThrows(Dnp3DecodeException::class.java) { Dnp3Objects.parse(hex("1E 05 01 05 00 05 00 01 00")) }
        assertThrows(Dnp3DecodeException::class.java) { Fragment.parse(hex("C0 81 00")) }
    }

    @Test fun itemAddresses() {
        assertEquals(Dnp3Address(PointType.BINARY_INPUT, 3), Dnp3Address.parse("bi:3"))
        assertEquals(Dnp3Address(PointType.ANALOG_INPUT, 7, "Flags"), Dnp3Address.parse("AI:7/flags"))
        assertEquals(mapOf("mode" to "direct", "op" to "pulse"), Dnp3Address.parse("bo:1;mode=direct;op=pulse").options)
        assertTrue(Dnp3Address.parse("iin").isIin)
        for (bad in listOf("xx:1", "bi", "bi:70000", "bi:1/Nope", "bi:1;mode=sbo", "bo:1;colour=red", "iin;mode=sbo")) {
            assertThrows(AddressException::class.java, { Dnp3Address.parse(bad) }, bad)
        }
        assertEquals("ao:2/Timestamp", Dnp3Address.parse("ao:2/timestamp").toString())
    }
}
