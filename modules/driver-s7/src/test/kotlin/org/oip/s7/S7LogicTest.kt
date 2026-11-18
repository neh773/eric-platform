package org.oip.s7

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Address syntax and PDU batching. Doc source: FEATURES DEV-036.
 *
 * Offsets are absolute bytes (IW0 and IW1 share a byte). Bits are `X` plus `.bit` in 0..7.
 * Strings are at most 210 characters. Timers and counters have no data type. DATE_AND_TIME is
 * not available on S7-1200, LInt is S7-1500 only, LReal is S7-1200 and S7-1500, and the timer
 * and counter areas are not available on S7-1200 or S7-1500.
 */
class S7LogicTest {
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("valid")
    fun parsesPerModel(model: S7Model, text: String, area: S7Area, type: S7Type, start: Int, bit: Int?, span: Int) {
        val parsed = S7Address.parse(text, model)
        assertEquals(area, parsed.area)
        assertEquals(type, parsed.type)
        assertEquals(start, parsed.start)
        assertEquals(bit, parsed.bit)
        assertEquals(span, parsed.span)
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalid")
    fun rejectsPerModel(model: S7Model, text: String) {
        assertThrows(AddressException::class.java) { S7Address.parse(text, model) }
    }

    @Test fun offsetsAreAbsoluteBytesAndBitsUseTheXForm() {
        val word = S7Address.parse("IW0", S7Model.S7_300)
        val overlap = S7Address.parse("IW1", S7Model.S7_300)
        assertEquals(0, word.start)
        assertEquals(2, word.span)
        assertEquals(1, overlap.start)
        assertEquals(2, overlap.span)
        val bit = S7Address.parse("IX20.3", S7Model.S7_1500)
        assertEquals(S7Type.BIT, bit.type)
        assertEquals(20, bit.start)
        assertEquals(3, bit.bit)
    }

    @Test fun tsapComesFromRackAndSlotOrAnExplicitPair() {
        val cpu = S7DeviceConfig(name = "cpu", host = "10.0.0.1", rack = 0, slot = 2)
        assertEquals(0x0100, cpu.resolvedLocalTsap())
        assertEquals(0x0102, cpu.resolvedRemoteTsap())
        val s400 = cpu.copy(model = S7Model.S7_400, slot = 3)
        assertEquals(0x0103, s400.resolvedRemoteTsap())
        val op = cpu.copy(connectionType = S7ConnectionType.OP)
        assertEquals(0x0200, op.resolvedLocalTsap())
        assertEquals(0x0202, op.resolvedRemoteTsap())
        val explicit = cpu.copy(localTsap = 0x1002, remoteTsap = 0x0301, rack = 0, slot = 2)
        assertEquals(0x1002, explicit.resolvedLocalTsap())
        assertEquals(0x0301, explicit.resolvedRemoteTsap())
    }

    @Test fun s5TimeAndCountersRoundTrip() {
        assertEquals(0x0150, S7Codec.encodeS5(1_500))
        assertEquals(1_500, S7Codec.decodeS5(0x0150))
        assertEquals(0x1100, S7Codec.encodeS5(10_000))
        assertEquals(10_000, S7Codec.decodeS5(0x1100))
        assertEquals(0x0999, S7Codec.encodeS5(9_990))
        assertEquals(0x0042, S7Codec.encodeCounter(42))
        assertEquals(42, S7Codec.decodeCounter(0x0042))
        assertEquals(999, S7Codec.decodeCounter(0x0999))
        assertThrows(OutOfRangeException::class.java) { S7Codec.encodeCounter(1000) }
        assertThrows(OutOfRangeException::class.java) { S7Codec.encodeS5(10_000_000) }
    }

    @Test fun readJobMatchesThePublicS7Layout() {
        val flags = ReadSlice(S7Area.M, 0, S7Kind.MEMORY, 0, 1, emptyList())
        val read = S7Wire.job(1, S7Wire.readParam(listOf(flags)))
        assertArrayEquals(
            byteArrayOf(
                0x32, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x0E, 0x00, 0x00,
                0x04, 0x01,
                0x12, 0x0A, 0x10, 0x02, 0x00, 0x01, 0x00, 0x00, 0x83.toByte(), 0x00, 0x00, 0x00,
            ),
            read,
        )
        val setup = S7Wire.job(1, S7Wire.setupParam(240))
        assertArrayEquals(
            byteArrayOf(
                0x32, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x08, 0x00, 0x00,
                0xF0.toByte(), 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0xF0.toByte(),
            ),
            setup,
        )
        val one = S7Wire.parseDataItems(byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x08, 0x2A, 0x00), 1)
        assertEquals(0x2A, one.single().bytes.single().toInt() and 0xFF)
        val mixed = S7Wire.parseDataItems(
            byteArrayOf(0x0A, 0x00, 0x00, 0x00, 0xFF.toByte(), 0x04, 0x00, 0x08, 0x22, 0x00),
            2,
        )
        assertEquals(0x0A, mixed[0].returnCode)
        assertEquals(0x22, mixed[1].bytes.single().toInt() and 0xFF)
    }

    @Test fun pduBudgetSplitsAdjacentBytesAndPacksSeparateItems() {
        assertEquals(222, S7Wire.maxDataBytes(240))
        assertEquals(942, S7Wire.maxDataBytes(960))
        assertEquals(240, S7Wire.responseBytes(listOf(222)))
        val config = S7DeviceConfig(name = "s", host = "127.0.0.1", spanGaps = true, maxGap = 0)
        val adjacent = (0 until 40).map { S7Address.parse("MB$it", S7Model.S7_300) }
        val one = RequestOptimizer.plan(adjacent, 240, config)
        assertTrue(one.rejected.isEmpty())
        assertEquals(1, one.pdus.size)
        assertEquals(1, one.pdus[0].slices.size)
        assertEquals(40, one.pdus[0].slices[0].length)

        val words = (0 until 200).map { S7Address.parse("DB1,W${it * 2}", S7Model.S7_300) }
        val split = RequestOptimizer.plan(words, 240, config)
        assertTrue(split.pdus.size >= 2, "pdus ${split.pdus.size}")
        assertCovers(words, split)
        for (pdu in split.pdus) {
            assertTrue(S7Wire.requestBytes(pdu.slices.size) <= 240)
            assertTrue(S7Wire.responseBytes(pdu.slices.map { it.dataBytes }) <= 240)
            assertTrue(pdu.slices.all { it.dataBytes <= 222 })
        }
        val wide = RequestOptimizer.plan(words, 960, config)
        assertEquals(1, wide.pdus.size)
        assertEquals(1, wide.pdus[0].slices.size)
        assertEquals(400, wide.pdus[0].slices[0].dataBytes)

        val sparse = (0 until 30).map { S7Address.parse("MB${it * 2}", S7Model.S7_300) }
        val packed = RequestOptimizer.plan(sparse, 240, config)
        assertEquals(30, packed.pdus.sumOf { it.slices.size })
        assertEquals(2, packed.pdus.size)
        assertTrue(packed.pdus.all { S7Wire.requestBytes(it.slices.size) <= 240 })

        val overlap = listOf(S7Address.parse("IW0", S7Model.S7_300), S7Address.parse("IW1", S7Model.S7_300))
        val merged = RequestOptimizer.plan(overlap, 240, config)
        assertEquals(3, merged.pdus.single().slices.single().length)

        val gapped = listOf(S7Address.parse("MB0", S7Model.S7_300), S7Address.parse("MB10", S7Model.S7_300))
        val tight = RequestOptimizer.plan(gapped, 240, config.copy(maxGap = 0))
        assertEquals(2, tight.pdus.single().slices.size)
        val spanned = RequestOptimizer.plan(gapped, 240, config.copy(maxGap = 16))
        assertEquals(11, spanned.pdus.single().slices.single().length)

        val tooWide = listOf(S7Address.parse("DB1,STRING0.50", S7Model.S7_300))
        val rejected = RequestOptimizer.plan(tooWide, 64, config)
        assertEquals("DB1,STRING0.50", rejected.rejected.single().raw)
        assertTrue(rejected.pdus.isEmpty())
    }

    private fun assertCovers(items: List<ParsedAddress>, plan: ReadPlan) {
        val covered = plan.pdus.flatMap { pdu -> pdu.slices.flatMap { it.addresses.map { a -> a.raw } } }.toSet()
        assertEquals(items.map { it.raw }.toSet(), covered)
        assertTrue(plan.rejected.isEmpty())
    }

    companion object {
        @JvmStatic
        fun valid(): List<Array<Any?>> {
            val all = S7Model.entries
            val rows = ArrayList<Array<Any?>>()
            fun add(models: List<S7Model>, text: String, area: S7Area, type: S7Type, start: Int, bit: Int?, span: Int) {
                for (model in models) rows += arrayOf(model, text, area, type, start, bit, span)
            }
            // Examples from the public address table.
            add(all, "IB0", S7Area.I, S7Type.BYTE, 0, null, 1)
            add(all, "IW0", S7Area.I, S7Type.WORD, 0, null, 2)
            add(all, "DB500,DI8", S7Area.DB, S7Type.DINT, 8, null, 4)
            add(all, "ISTRING24.50", S7Area.I, S7Type.STRING, 24, null, 52)
            add(all, "IX20.3", S7Area.I, S7Type.BIT, 20, 3, 1)
            add(listOf(S7Model.S7_300, S7Model.S7_400), "T0", S7Area.T, S7Type.TIMER, 0, null, 1)
            add(listOf(S7Model.S7_300, S7Model.S7_400), "C0", S7Area.C, S7Type.COUNTER, 0, null, 1)
            add(all, "iw0", S7Area.I, S7Type.WORD, 0, null, 2)
            add(all, "db500, di8", S7Area.DB, S7Type.DINT, 8, null, 4)
            add(all, "QB0", S7Area.Q, S7Type.BYTE, 0, null, 1)
            add(all, "MB0", S7Area.M, S7Type.BYTE, 0, null, 1)
            add(all, "MX0.7", S7Area.M, S7Type.BIT, 0, 7, 1)
            add(all, "IC0", S7Area.I, S7Type.CHAR, 0, null, 1)
            add(all, "II0", S7Area.I, S7Type.INT, 0, null, 2)
            add(all, "ID0", S7Area.I, S7Type.DWORD, 0, null, 4)
            add(all, "IDI0", S7Area.I, S7Type.DINT, 0, null, 4)
            add(all, "IREAL0", S7Area.I, S7Type.REAL, 0, null, 4)
            add(all, "DB1,STRING0", S7Area.DB, S7Type.STRING, 0, null, 212)
            add(all, "DB1,B0", S7Area.DB, S7Type.BYTE, 0, null, 1)
            add(listOf(S7Model.S7_300, S7Model.S7_400, S7Model.S7_1500), "IDT0", S7Area.I, S7Type.DT, 0, null, 8)
            add(listOf(S7Model.S7_1200, S7Model.S7_1500), "ILREAL0", S7Area.I, S7Type.LREAL, 0, null, 8)
            add(listOf(S7Model.S7_1500), "ILI0", S7Area.I, S7Type.LINT, 0, null, 8)
            add(listOf(S7Model.S7_1500), "DB1,LI0", S7Area.DB, S7Type.LINT, 0, null, 8)
            return rows
        }

        @JvmStatic
        fun invalid(): List<Array<Any>> {
            val rows = ArrayList<Array<Any>>()
            val structural = listOf(
                "", " ", "IB", "IW", "IX0", "IX0.8", "IX0.3.1", "I0.0", "IW0.1",
                "DB", "DB0,B0", "DB1", "DB1,", "DB1,B", "DB1,X0.8", "DB65536,B0",
                "STRING0", "HR1", "T", "C", "TB0", "MW1.0", "IB0X", "ISTRING0.0", "ISTRING0.211",
                "T-1", "C-1",
            )
            for (model in S7Model.entries) for (text in structural) rows += arrayOf(model, text)
            for (text in listOf("T0", "C0", "T10", "C999")) {
                rows += arrayOf(S7Model.S7_1200, text)
                rows += arrayOf(S7Model.S7_1500, text)
            }
            for (model in listOf(S7Model.S7_300, S7Model.S7_400, S7Model.S7_1200)) {
                rows += arrayOf(model, "ILI0")
                rows += arrayOf(model, "DB1,LI4")
            }
            for (model in listOf(S7Model.S7_300, S7Model.S7_400)) {
                rows += arrayOf(model, "ILREAL0")
                rows += arrayOf(model, "DB2,LREAL8")
            }
            rows += arrayOf(S7Model.S7_1200, "IDT0")
            rows += arrayOf(S7Model.S7_1200, "DB1,DT0")
            return rows
        }
    }
}
