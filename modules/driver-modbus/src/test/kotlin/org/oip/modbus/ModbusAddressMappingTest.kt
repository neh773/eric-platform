package org.oip.modbus

import com.digitalpetri.modbus.server.ProcessImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Address mapping per the public page:
 */
class ModbusAddressMappingTest {
    private val device = ModbusDeviceConfig(name = "plc", host = "127.0.0.1", unitId = 1)

    @Test fun decimalRangeNamesAndAddresses() {
        // The page's simple example: Temp1..Temp10 from holding register 10.
        val items = ModbusAddressMapping.expand(ModbusAddressRange("Temp", "1", "10", "HR", 10), device)
        assertEquals(10, items.size)
        assertEquals("Temp1", items.first().name)
        assertEquals("1.HR10", items.first().address)
        assertEquals("Temp10", items.last().name)
        assertEquals("1.HR19", items.last().address)
        assertEquals(ModbusType.INT16, items.first().type)
    }

    @Test fun octalRangeCountsInTheRadix() {
        // The page's DL240 example: V2000..V3777 octal from register 1024.
        val items = ModbusAddressMapping.expand(ModbusAddressRange("V", "2000", "3777", "HR", 1024, radix = 8), device)
        assertEquals(1024, items.size)
        assertEquals("V2000", items[0].name)
        assertEquals("1.HR1024", items[0].address)
        assertEquals("V2007", items[7].name)
        assertEquals("V2010", items[8].name)
        assertEquals("1.HR1032", items[8].address)
        assertEquals("V3777", items.last().name)
        assertEquals("1.HR2047", items.last().address)
    }

    @Test fun stepAdvancesByTheTypeWidthAndUnitIdOverridesTheDevice() {
        val floats = ModbusAddressMapping.expand(ModbusAddressRange("R", "1024", "1030", "hrf", 1024, step = true, unitId = 3), device)
        assertEquals(listOf("3.HRF1024", "3.HRF1026", "3.HRF1028", "3.HRF1030"), floats.take(4).map { it.address })
        assertEquals(7, floats.size)
        assertEquals(ModbusType.FLOAT32, floats.first().type)
        val doubles = ModbusAddressMapping.expand(ModbusAddressRange("D", "0", "2", "HRD", 1, step = true), device)
        assertEquals(listOf("1.HRD1", "1.HRD5", "1.HRD9"), doubles.map { it.address })
        val hex = ModbusAddressMapping.expand(ModbusAddressRange("X", "e", "11", "C", 1, radix = 16), device)
        assertEquals(listOf("XE", "XF", "X10", "X11"), hex.map { it.name })
        assertEquals(ModbusArea.COIL, hex.first().area)
    }

    @Test fun invalidRangesAreRejected() {
        fun bad(range: ModbusAddressRange) = assertThrows<AddressException> { ModbusAddressMapping.expand(range, device) }
        bad(ModbusAddressRange("HR", "1", "2", "HR", 1)) // reserved prefix
        bad(ModbusAddressRange("di", "1", "2", "HR", 1))
        bad(ModbusAddressRange("A-B", "1", "2", "HR", 1))
        bad(ModbusAddressRange("S", "1", "2", "HRS", 1)) // strings cannot be mapped
        bad(ModbusAddressRange("F", "1", "2", "HRF", 1)) // 32-bit without step would overlap
        bad(ModbusAddressRange("A", "5", "4", "HR", 1))
        bad(ModbusAddressRange("A", "8", "9", "HR", 1, radix = 8)) // 8 and 9 are not octal digits
        bad(ModbusAddressRange("A", "1", "2", "HR1", 1)) // a designator that ends in digits would shift addresses
        bad(ModbusAddressRange("A", "1", "2", "XX", 1))
        bad(ModbusAddressRange("A", "0", "65535", "HR", 2)) // runs past register 65535
        bad(ModbusAddressRange("A", "0", "1", "HR", 1, unitId = 300))
        bad(ModbusAddressRange("A", "0", "70000", "HR", 1))
    }

    @Test fun generatedAddressesReadTheSlave() {
        TestSlave(ModbusMode.TCP, intArrayOf(1, 2)).use { slave ->
            val one = slave.images.getValue(1)
            for (i in 0 until 4) one.putHolding(9 + i, 0, 100 + i) // HR10..HR13, one-based
            // 1.5f = 0x3FC00000 and 2.5f = 0x40200000, high word first, at HRF20 and HRF22 on unit 2.
            val two = slave.images.getValue(2)
            two.putHolding(19, 0x3F, 0xC0)
            two.putHolding(20, 0, 0)
            two.putHolding(21, 0x40, 0x20)
            two.putHolding(22, 0, 0)
            val config = device.copy(port = slave.port)
            ModbusDevice(config).use { dev ->
                dev.start()
                val end = System.nanoTime() + 8_000_000_000L
                while (!dev.connectionStatus().connected && System.nanoTime() < end) Thread.sleep(20)
                val ints = ModbusAddressMapping.expand(ModbusAddressRange("T", "1", "4", "HR", 10), config)
                assertEquals(listOf(100, 101, 102, 103), dev.read(ints.map { it.address }).map { it.value })
                val floats = ModbusAddressMapping.expand(ModbusAddressRange("F", "0", "1", "HRF", 20, step = true, unitId = 2), config)
                assertEquals(listOf(1.5f, 2.5f), dev.read(floats.map { it.address }).map { it.value })
            }
        }
    }

    private fun ProcessImage.putHolding(address: Int, hi: Int, lo: Int) {
        val bytes = byteArrayOf(hi.toByte(), lo.toByte())
        with { tx -> tx.writeHoldingRegisters { map -> map[address] = bytes } }
    }
}
