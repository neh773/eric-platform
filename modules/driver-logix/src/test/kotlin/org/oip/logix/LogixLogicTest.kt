package org.oip.logix

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Wire layout from the public Rockwell manual 1756-PM020, and the address forms on the
 * public the reference platform Logix driver page.
 *
 * https://literature.rockwellautomation.com/idc/groups/literature/documents/pm/1756-pm020_-en-p.pdf
 */
class LogixLogicTest {
    @Test fun multipleServicePacketMatchesTheRockwellExample() {
        // 1756-PM020 "Example - Multiple Service": Read Tag "parts" then "ControlWord".
        val parts = Cip.readTag(Cip.symbol("parts"), 1)
        val control = Cip.readTag(Cip.symbol("ControlWord"), 1)
        val packet = Cip.multiple(listOf(parts, control))
        val expected = byteArrayOf(
            0x0A, 0x02, 0x20, 0x02, 0x24, 0x01,
            0x02, 0x00,
            0x06, 0x00,
            0x12, 0x00,
            0x4C, 0x04, 0x91.toByte(), 0x05, 0x70, 0x61, 0x72, 0x74, 0x73, 0x00, 0x01, 0x00,
            0x4C, 0x07, 0x91.toByte(), 0x0B, 0x43, 0x6F, 0x6E, 0x74, 0x72, 0x6F, 0x6C, 0x57, 0x6F, 0x72, 0x64, 0x00, 0x01, 0x00,
        )
        assertArrayEquals(expected, packet)
    }

    @Test fun symbolicReadMatchesTheTotalCountExample() {
        // 1756-PM020 Read Tag of "TotalCount", one element. The name is 10 characters, so no pad.
        val packet = Cip.readTag(Cip.symbol("TotalCount"), 1)
        assertArrayEquals(
            byteArrayOf(
                0x4C, 0x06,
                0x91.toByte(), 0x0A,
                'T'.code.toByte(), 'o'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
                'C'.code.toByte(), 'o'.code.toByte(), 'u'.code.toByte(), 'n'.code.toByte(), 't'.code.toByte(),
                0x01, 0x00,
            ),
            packet,
        )
    }

    @Test fun packSplitsWhenTheBudgetRunsOut() {
        val one = Cip.readTag(Cip.symbol("A"), 1)
        val services = List(10) { one }
        val packed = Cip.pack(services, Cip.multipleSize(services.take(3)))
        assertTrue(packed.size > 1)
        assertTrue(packed.all { (it[0].toInt() and 0xFF) == Cip.MSP || (it[0].toInt() and 0xFF) == Cip.READ })
        val replies = packed.sumOf { if ((it[0].toInt() and 0xFF) == Cip.MSP) u16(it, 6) else 1 }
        assertEquals(10, replies)
    }

    @Test fun encodesAndDecodesEachAtomic() {
        assertEquals(true, Cip.decode(Cip.BOOL, Cip.encode(Cip.BOOL, true), 0))
        assertEquals(false, Cip.decode(Cip.BOOL, Cip.encode(Cip.BOOL, 0), 0))
        assertEquals(-5, Cip.decode(Cip.SINT, Cip.encode(Cip.SINT, -5), 0))
        assertEquals(-300, Cip.decode(Cip.INT, Cip.encode(Cip.INT, -300), 0))
        assertEquals(534, Cip.decode(Cip.DINT, Cip.encode(Cip.DINT, 534), 0))
        assertEquals(0x1122334455667788L, Cip.decode(Cip.LINT, Cip.encode(Cip.LINT, 0x1122334455667788L), 0))
        assertEquals(1.5f, Cip.decode(Cip.REAL, Cip.encode(Cip.REAL, 1.5f), 0))
        assertEquals(-2.25, Cip.decode(Cip.LREAL, Cip.encode(Cip.LREAL, -2.25), 0))
        assertEquals("Hello", Cip.decodeString(Cip.encodeString("Hello", 82, 88)))
        assertThrows(OutOfRangeException::class.java) { Cip.encode(Cip.SINT, 200) }
        assertThrows(TypeMismatchException::class.java) { Cip.encode(Cip.DINT, "nope") }
    }

    @Test fun parsesAddressesAndTheConnectionPath() {
        assertEquals(listOf("Motor"), names(parseAddress("Motor")))
        assertEquals(listOf(3), (parseAddress("Counts[3]").steps[1] as Step.At).indexes)
        assertEquals(listOf(1, 2), (parseAddress("Grid[1,2]").steps[1] as Step.At).indexes)
        assertEquals(listOf("Machine", "rate"), names(parseAddress("Machine.rate")))
        assertEquals(listOf("Program:Main", "Step"), names(parseAddress("Program:Main.Step")))
        assertEquals(5, parseAddress("Status.5").bit)
        assertEquals(31, parseAddress("flags[0].31").bit)
        assertEquals(0, parseAddress("flags[1].0").bit)
        assertEquals(listOf("1" to "0"), parseConnectionPath("1,0"))
        assertEquals(listOf("1" to "2", "2" to "192.168.1.10", "1" to "0"), parseConnectionPath("1,2,2,192.168.1.10,1,0"))
        val route = Cip.route("", 0)
        assertArrayEquals(byteArrayOf(0x01, 0x00), route)
        assertThrows(AddressException::class.java) { parseAddress("") }
        assertThrows(AddressException::class.java) { parseAddress("Tag.") }
        assertThrows(AddressException::class.java) { parseConnectionPath("1") }
        assertThrows(AddressException::class.java) { parseConnectionPath("1,0,2") }
    }

    /**
     * OPC values are the parentheticals on the Logix driver page: DT and LDT are Date/Time
     * (LDT keeps nanoseconds), LTIME is a nanosecond count, TIME is a decimal number of seconds,
     * and TIME32 is a microsecond count.
     * doc source: FEATURES DEV-010
     *
     * Wire units are the Studio 5000 elementary types: DT and TIME are microseconds, LDT and LTIME
     * are nanoseconds, TIME32 is microseconds, from 1970-01-01 through 2250-12-31 for the absolute types.
     * doc source: FEATURES DEV-010
     */
    @Test fun encodesDateAndTimeTypesAsTheReferencePageDescribes() {
        val dt = Instant.parse("2024-06-15T12:30:00.000123Z")
        assertEquals(dt, Cip.decode(Cip.DT, Cip.encode(Cip.DT, dt), 0))
        val ldt = Instant.parse("2024-06-15T12:30:00.000123456Z")
        assertEquals(ldt, Cip.decode(Cip.LDT, Cip.encode(Cip.LDT, ldt), 0))
        assertEquals(1_500_000_000L, Cip.decode(Cip.LTIME, Cip.encode(Cip.LTIME, 1_500_000_000L), 0))
        assertEquals(1_500_000, Cip.decode(Cip.TIME32, Cip.encode(Cip.TIME32, 1_500_000), 0))
        val time = BigDecimal("1.500001")
        val decoded = Cip.decode(Cip.TIME, Cip.encode(Cip.TIME, time), 0) as BigDecimal
        assertEquals(0, time.compareTo(decoded))
        assertEquals(6, decoded.scale())
        assertThrows(OutOfRangeException::class.java) { Cip.encode(Cip.DT, Instant.parse("1969-12-31T23:00:00Z")) }
        assertThrows(OutOfRangeException::class.java) { Cip.encode(Cip.TIME32, 3_000_000_000L) }
        assertThrows(TypeMismatchException::class.java) { Cip.encode(Cip.LDT, "nope") }
    }

    @Test fun writeTagFragmentCarriesAByteOffset() {
        // 1756-PM020 Write Tag Fragmented is service 0x53. The offset is a UDINT byte offset after the element count.
        val packet = Cip.writeTag(Cip.symbol("Bulk"), Cip.atomicType(Cip.DINT), 4, byteArrayOf(9, 0, 0, 0), byteOffset = 8)
        assertEquals(Cip.WRITE_FRAG, packet[0].toInt() and 0xFF)
        assertEquals(8, i32(packet, 12))
        assertEquals(9, packet[16].toInt() and 0xFF)
        val plain = Cip.writeTag(Cip.symbol("Bulk"), Cip.atomicType(Cip.DINT), 1, byteArrayOf(1, 0, 0, 0))
        assertEquals(Cip.WRITE, plain[0].toInt() and 0xFF)
    }

    @Test fun dwordBoolIndexFollowsThereferenceMapping() {
        // The public driver page: boolTag[0].0, boolTag[0].31, boolTag[1].0, boolTag[1].31.
        val model = boolArrayModel()
        assertEquals(0, model.boolArrayIndex(parseAddress("flags[0].0")))
        assertEquals(31, model.boolArrayIndex(parseAddress("flags[0].31")))
        assertEquals(32, model.boolArrayIndex(parseAddress("flags[1].0")))
        assertEquals(63, model.boolArrayIndex(parseAddress("flags[1].31")))
        assertEquals(null, model.boolArrayIndex(parseAddress("flags[4]")))
    }

    private fun names(parsed: ParsedAddress) = parsed.steps.filterIsInstance<Step.Sym>().map { it.name }

    private fun boolArrayModel(): LogixModel {
        val sym = SymbolRec("flags", "flags", Cip.BOOL or (1 shl 13), 1, null)
        return LogixModel(mapOf("flags" to sym), emptyMap(), emptyMap())
    }
}
