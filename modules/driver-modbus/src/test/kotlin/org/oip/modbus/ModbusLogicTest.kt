package org.oip.modbus

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Address syntax and register layout follow the public the reference platform Modbus pages:
 *
 * Quality codes are the OPC UA StatusCodes named in SPEC §5
 * (https://reference.opcfoundation.org/Core/Part6/v105/docs/A.2).
 */
class ModbusLogicTest {
    private val oneBased = ModbusDeviceConfig(name = "d", host = "127.0.0.1", zeroBasedAddressing = false)
    private val zeroBased = oneBased.copy(zeroBasedAddressing = true)

    @Test fun designatorsMatchThePublicAddressSyntax() {
        fun p(raw: String, cfg: ModbusDeviceConfig = oneBased) = ModbusAddress.parse(raw, cfg)
        assertEquals(ModbusArea.HOLDING_REGISTER, p("HR1024").area)
        assertEquals(ModbusType.INT16, p("HR1024").type)
        assertEquals(1023, p("HR1024").protocolOffset) // HR1 == 40001 == offset 0
        assertEquals(0, p("HR1").protocolOffset)
        assertEquals(511, p("IR512").protocolOffset)
        assertEquals(ModbusArea.COIL, p("C3072").area)
        assertEquals(3071, p("C3072").protocolOffset)
        assertEquals(ModbusArea.DISCRETE_INPUT, p("DI1").area)
        assertEquals(ModbusType.BCD16, p("HRBCD1024").type)
        assertEquals(ModbusType.BCD32, p("HRBCD_321").type)
        assertEquals(0, p("HRBCD_321").protocolOffset)
        assertEquals(ModbusType.UINT16, p("HRUS1").type)
        assertEquals(ModbusType.INT32, p("HRI1").type)
        assertEquals(2, p("HRI1").span)
        assertEquals(ModbusType.UINT32, p("HRUI1").type)
        assertEquals(ModbusType.INT64, p("HRI_641").type)
        assertEquals(4, p("HRI_641").span)
        assertEquals(ModbusType.UINT64, p("HRUI_641").type)
        assertEquals(ModbusType.FLOAT32, p("HRF1").type)
        assertEquals(ModbusType.FLOAT64, p("HRD1").type)
        assertEquals(ModbusType.FLOAT32, p("IRF10").type)
        val str = p("HRS1024:20")
        assertEquals(ModbusType.STRING, str.type)
        assertEquals(20, str.stringLength)
        assertEquals(10, str.span)
        val unit = p("3.HR1024")
        assertEquals(3, unit.unitId)
        assertEquals(1023, unit.protocolOffset)
        val bit = p("HR1024.0")
        assertEquals(ModbusType.BIT, bit.type)
        assertEquals(0, bit.bit)
        assertEquals(10, p("HR1024.10").bit)
        assertEquals(0, p("IR0", zeroBased).protocolOffset)
        assertEquals(1, p("HR1", zeroBased).protocolOffset)
    }

    @Test fun invalidAddressesAreRejected() {
        assertThrows(AddressException::class.java) { ModbusAddress.parse("HR0", oneBased) }
        assertThrows(AddressException::class.java) { ModbusAddress.parse("HR1.16", oneBased) }
        assertThrows(AddressException::class.java) { ModbusAddress.parse("HRF1.0", oneBased) }
        assertThrows(AddressException::class.java) { ModbusAddress.parse("HRS1", oneBased) }
        assertThrows(AddressException::class.java) { ModbusAddress.parse("HRS1:3", oneBased) }
        assertThrows(AddressException::class.java) { ModbusAddress.parse("nope", oneBased) }
        assertThrows(AddressException::class.java) { ModbusAddress.parse("C1.0", oneBased) }
        assertFalse(ModbusAddress.parse("IR1", oneBased).writable)
        assertFalse(ModbusAddress.parse("DI1", oneBased).writable)
        assertTrue(ModbusAddress.parse("HR1", oneBased).writable)
        assertTrue(ModbusAddress.parse("C1", oneBased).writable)
    }

    @Test fun contiguousReadsCoalesceAndGapsHonorMaxGap() {
        val tight = oneBased.copy(spanGaps = false, maxHoldingRegistersPerRead = 125)
        val three = (1..3).map { ModbusAddress.parse("HR$it", tight) }
        val merged = RequestOptimizer.plan(three, tight)
        assertEquals(1, merged.blocks.size)
        assertEquals(0, merged.blocks[0].start)
        assertEquals(3, merged.blocks[0].quantity)

        val apart = listOf(ModbusAddress.parse("HR1", tight), ModbusAddress.parse("HR10", tight))
        assertEquals(2, RequestOptimizer.plan(apart, tight).blocks.size)
        val spanned = RequestOptimizer.plan(apart, tight.copy(spanGaps = true, maxGap = 20))
        assertEquals(1, spanned.blocks.size)
        assertEquals(10, spanned.blocks[0].quantity)

        val wide = (1..200).map { ModbusAddress.parse("HR$it", tight) }
        val split = RequestOptimizer.plan(wide, tight)
        assertEquals(2, split.blocks.size)
        assertEquals(125, split.blocks[0].quantity)
        assertEquals(75, split.blocks[1].quantity)

        val singles = RequestOptimizer.plan(three, tight.copy(allowReadMultipleRegisters = false))
        assertEquals(3, singles.blocks.size)

        val coils = listOf(ModbusAddress.parse("C1", tight), ModbusAddress.parse("C2", tight), ModbusAddress.parse("C8", tight))
        assertEquals(2, RequestOptimizer.plan(coils, tight.copy(spanGaps = false)).blocks.size)
        assertEquals(1, RequestOptimizer.plan(coils, tight.copy(spanGaps = true, maxGap = 10)).blocks.size)
    }

    @Test fun wordAndByteOrderRoundTrip() {
        // 1.0f is 0x3F800000. ABCD, CDAB (the reference platform reverse word order), BADC, DCBA.
        assertArrayEquals(byteArrayOf(0x3F, 0x80.toByte(), 0x00, 0x00), encodeFloat(1f, ByteOrder.BIG_ENDIAN, WordOrder.HIGH_WORD_FIRST))
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x3F, 0x80.toByte()), encodeFloat(1f, ByteOrder.BIG_ENDIAN, WordOrder.LOW_WORD_FIRST))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x3F, 0x00, 0x00), encodeFloat(1f, ByteOrder.LITTLE_ENDIAN, WordOrder.HIGH_WORD_FIRST))
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F), encodeFloat(1f, ByteOrder.LITTLE_ENDIAN, WordOrder.LOW_WORD_FIRST))
        for (order in ByteOrder.entries) for (words in WordOrder.entries) {
            val cfg = oneBased.copy(byteOrder = order, wordOrder = words)
            val wire = ModbusCodec.encode(ModbusAddress.parse("HRF1", cfg), 1f, cfg)
            assertEquals(1f, ModbusCodec.decode(ModbusAddress.parse("HRF1", cfg), 0, wire, cfg))
            val i64 = ModbusCodec.encode(ModbusAddress.parse("HRI_641", cfg), -2L, cfg)
            assertEquals(-2L, ModbusCodec.decode(ModbusAddress.parse("HRI_641", cfg), 0, i64, cfg))
        }
        val cfg = oneBased
        assertEquals(-2, ModbusCodec.decode(ModbusAddress.parse("HR1", cfg), 0, ModbusCodec.encode(ModbusAddress.parse("HR1", cfg), -2, cfg), cfg))
        assertEquals(65534, ModbusCodec.decode(ModbusAddress.parse("HRUS1", cfg), 0, ModbusCodec.encode(ModbusAddress.parse("HRUS1", cfg), 65534, cfg), cfg))
        assertEquals(1234L, ModbusCodec.decode(ModbusAddress.parse("HRBCD1", cfg), 0, ModbusCodec.encode(ModbusAddress.parse("HRBCD1", cfg), 1234, cfg), cfg))
        assertEquals(12345678L, ModbusCodec.decode(ModbusAddress.parse("HRBCD_321", cfg), 0, ModbusCodec.encode(ModbusAddress.parse("HRBCD_321", cfg), 12345678, cfg), cfg))
        val text = ModbusAddress.parse("HRS1:4", cfg)
        val encoded = ModbusCodec.encode(text, "AB", cfg)
        assertArrayEquals(byteArrayOf('A'.code.toByte(), 'B'.code.toByte(), ' '.code.toByte(), ' '.code.toByte()), encoded)
        assertEquals("AB", ModbusCodec.decode(text, 0, encoded, cfg))
        val reversed = cfg.copy(reverseStringByteOrder = true)
        val rev = ModbusCodec.encode(text, "AB", reversed)
        assertArrayEquals(byteArrayOf('B'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(), ' '.code.toByte()), rev)
        assertEquals("AB", ModbusCodec.decode(text, 0, rev, reversed))
        val right = cfg.copy(rightJustifyStrings = true)
        val justified = ModbusCodec.encode(text, "AB", right)
        assertArrayEquals(byteArrayOf(' '.code.toByte(), ' '.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte()), justified)
        assertEquals("AB", ModbusCodec.decode(text, 0, justified, right))
    }

    @Test fun crc16MatchesTheModbusPolynomial() {
        // CRC-16/Modbus check value for ASCII "123456789" is 0x4B37.
        val digits = "123456789".encodeToByteArray()
        assertEquals(0x4B37, ModbusCodec.crc16(digits))
    }

    @Test fun opcUaStatusCodesAreThePlatformQualityMapping() {
        assertEquals(0x00000000L, QualityCode.Good.statusCode)
        assertEquals(0x808A0000L, QualityCode.Bad_NotConnected.statusCode)
        assertEquals(0x40900000L, QualityCode.Uncertain_LastKnownValue.statusCode)
        assertEquals(0x00960000L, QualityCode.Good_Overridden.statusCode)
        assertEquals(0x808D0000L, QualityCode.Bad_Stale.statusCode)
        val bad = Quality.fromOpcUa(0x808A0001L) // low 16 bits are status flags
        assertEquals(QualityCode.Bad_NotConnected, bad.code)
        assertEquals(QualityLevel.BAD, bad.level)
        assertEquals(0x808A0000L, bad.statusCode)
        assertEquals(QualityLevel.UNCERTAIN, Quality.fromOpcUa(0x40AB0000L).level)
    }

    @Test fun reconnectBackoffJitterStaysInsideTwentyPercentAndTheCap() {
        assertEquals(32L, reconnectDelayMs(40, 160, 2.0, 0, -0.2))
        assertEquals(48L, reconnectDelayMs(40, 160, 2.0, 0, 0.2))
        assertEquals(64L, reconnectDelayMs(40, 160, 2.0, 1, -0.2))
        assertEquals(96L, reconnectDelayMs(40, 160, 2.0, 1, 0.2))
        assertEquals(128L, reconnectDelayMs(40, 160, 2.0, 2, -0.2))
        assertEquals(160L, reconnectDelayMs(40, 160, 2.0, 2, 0.2))
        assertEquals(40L, reconnectDelayMs(40, 160, 2.0, 0, 0.0))
    }

    private fun encodeFloat(v: Float, byteOrder: ByteOrder, wordOrder: WordOrder): ByteArray {
        val cfg = oneBased.copy(byteOrder = byteOrder, wordOrder = wordOrder)
        return ModbusCodec.encode(ModbusAddress.parse("HRF1", cfg), v, cfg)
    }
}
