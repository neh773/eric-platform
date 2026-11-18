package org.oip.simulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Preloaded Generic, Dairy, and SLC programs load, browse, and keep the legacy `_Meta:` item path.
 *
 * Contents are written from the public pages, not from an the reference platform program file. See SimulatorPrograms.kt.
 */
class SimulatorLibraryTest {
    @Test fun genericProgramLoadsAtLegacyPathsAndBrowses() {
        val config = simulatorDeviceConfig("generic", mapOf("program" to "generic"))
        assertTrue(config.legacyItemPaths)
        assertEquals("sine(-100,100,60,true)", source(config, "Sine/Sine0"))
        assertEquals("sine(-20,30,15,true)", source(config, "Sine/Sine7"))
        assertEquals("ramp(0,1000,75,true)", source(config, "Ramp/Ramp0"))
        assertEquals("ramp(0,600,13,true)", source(config, "Ramp/Ramp6"))
        assertEquals("ramp(-5,695,18,true)", source(config, "Ramp/Ramp7"))
        assertEquals("realistic()", source(config, "Realistic/Realistic3"))
        assertEquals("readonly(\"ABCDEFG\")", source(config, "ReadOnly/ReadOnlyString1"))
        assertFalse(config.instructions.any { it.browsePath.startsWith("_Meta:") })

        SimulatorDevice(config).use { device ->
            assertEquals(-100.0, device.read("_Meta:Sine/Sine0").value)
            assertEquals(-20.0, device.read("Sine/Sine7").value)
            assertEquals(false, device.read("_Meta:ReadOnly/ReadOnlyBoolean1").value)
            assertEquals(true, device.read("_Meta:ReadOnly/ReadOnlyBoolean2").value)
            assertEquals(1.1f, device.read("_Meta:ReadOnly/ReadOnlyFloat1").value)
            assertEquals("ABCDEFG", device.read("_Meta:ReadOnly/ReadOnlyString1").value)
            assertEquals("ZYXWVUT", device.read("_Meta:ReadOnly/ReadOnlyString2").value)
            assertEquals(0x803B0000, device.write("_Meta:ReadOnly/ReadOnlyInteger1", 9).quality.statusCode)
            assertEquals(1, device.read("_Meta:ReadOnly/ReadOnlyInteger1").value)
            assertEquals(0L, device.write("_Meta:Writeable/WriteableInteger1", 42).quality.statusCode)
            assertEquals(42, device.read("Writeable/WriteableInteger1").value)
            assertTrue("Writeable/WriteableInteger1" in config.persistWrites)
            assertFalse(config.persistWrites.any { it.startsWith("Sine/") })

            val expectedRandom = java.util.Random(mixSeed(1, "Random/RandomInteger1")).nextInt(101)
            assertEquals(expectedRandom, device.read("_Meta:Random/RandomInteger1").value)

            val root = device.browse("")
            val controls = root.first { it.browseName == "[Controls]" }
            assertEquals("Object", controls.nodeClass)
            assertEquals("[Controls]", controls.nodeId)
            assertTrue(root.map { it.browseName }.containsAll(listOf("ReadOnly", "Writeable", "Random", "Sine", "Ramp", "Realistic")))
            val sine = device.browse("_Meta:Sine").first { it.browseName == "Sine0" }
            assertEquals("_Meta:Sine/Sine0", sine.nodeId)
            assertEquals("Variable", sine.nodeClass)
            assertEquals("Double", sine.dataType)
            assertFalse(device.browse("ReadOnly").first { it.browseName == "ReadOnlyBoolean1" }.writable)
            assertTrue(device.browse("Writeable").first { it.browseName == "WriteableInteger1" }.writable)
            assertEquals("_Meta:Realistic/Realistic3", device.describe("Realistic/Realistic3")!!.nodeId)

            val csv = device.exportProgram()
            assertFalse(csv.contains("_Meta:"))
            assertEquals(config.instructions, ProgramCsv.import(csv))
        }
    }

    @Test fun genericWritableValuePersistsAcrossLaterIntervals() {
        // The legacy Generic page: writable tags keep a written value while the device is running.
        // Interval 0 is the only row, so a repeating program would otherwise republish every base rate.
        // Reset starts the program over and publishes that constant again.
        // doc source: FEATURES DEV-028
        // doc source: FEATURES DEV-028
        val clock = ManualSimulatorClock(0)
        val config = simulatorDeviceConfig(
            "generic",
            mapOf("program" to "generic", "baseRateMs" to 1_000),
            setOf("program", "baseRateMs"),
        )
        SimulatorDevice(config, clock).use { device ->
            assertEquals(0, device.read("_Meta:Writeable/WriteableInteger1").value)
            assertEquals(false, device.read("Writeable/WriteableBoolean1").value)
            assertEquals("", device.read("_Meta:Writeable/WriteableString1").value)
            assertEquals(0L, device.write("_Meta:Writeable/WriteableInteger1", 42).quality.statusCode)
            assertEquals(0L, device.write("Writeable/WriteableBoolean1", true).quality.statusCode)
            assertEquals(0L, device.write("Writeable/WriteableString1", "held").quality.statusCode)
            val sineAtStart = device.read("_Meta:Sine/Sine0").value as Double
            clock.millis = 5_000
            assertEquals(42, device.read("Writeable/WriteableInteger1").value)
            assertEquals(true, device.read("_Meta:Writeable/WriteableBoolean1").value)
            assertEquals("held", device.read("Writeable/WriteableString1").value)
            val sineLater = device.read("Sine/Sine0").value as Double
            assertTrue(sineLater != sineAtStart, "sine should advance while the write is held")
            assertEquals(0L, device.write("[Controls]/Reset", true).quality.statusCode)
            assertEquals(0, device.read("_Meta:Writeable/WriteableInteger1").value)
            assertEquals(false, device.read("Writeable/WriteableBoolean1").value)
            assertEquals("", device.read("_Meta:Writeable/WriteableString1").value)
        }
    }

    @Test fun dairyWritableValuesPersistAcrossLaterIntervals() {
        // Dairy writable addresses are constants at interval 0, so a repeating one-row program
        // would otherwise republish them every base rate. A write is held until the next write,
        // and Reset publishes interval 0 again. Moving addresses keep moving.
        // doc source: FEATURES DEV-028
        val clock = ManualSimulatorClock(0)
        val config = simulatorDeviceConfig(
            "dairy",
            mapOf("program" to "dairy", "baseRateMs" to 1_000),
            setOf("program", "baseRateMs"),
        )
        assertTrue("Writeable/Agitator1Mode" in config.persistWrites)
        assertTrue("Overview/Refrigeration/HOA" in config.persistWrites)
        assertFalse("Overview/Refrigeration/Tank/Level" in config.persistWrites)
        SimulatorDevice(config, clock).use { device ->
            assertEquals(0L, device.write("_Meta:Writeable/Agitator1Mode", 2).quality.statusCode)
            assertEquals(0L, device.write("Writeable/Agitator1Speed", 37.5).quality.statusCode)
            assertEquals(0L, device.write("_Meta:Overview/Refrigeration/HOA", 0).quality.statusCode)
            assertEquals(0L, device.write("Overview/Motor/Running", false).quality.statusCode)
            val levelAtStart = device.read("_Meta:Overview/Refrigeration/Tank/Level").value as Double
            clock.millis = 5_000
            assertEquals(2, device.read("Writeable/Agitator1Mode").value)
            assertEquals(37.5, device.read("_Meta:Writeable/Agitator1Speed").value)
            assertEquals(0, device.read("Overview/Refrigeration/HOA").value)
            assertEquals(false, device.read("_Meta:Overview/Motor/Running").value)
            assertTrue(device.read("Overview/Refrigeration/Tank/Level").value != levelAtStart, "level should move")
            assertEquals(0L, device.write("Writeable/Agitator1Mode", 3).quality.statusCode)
            clock.millis = 9_000
            assertEquals(3, device.read("_Meta:Writeable/Agitator1Mode").value)
            assertEquals(0L, device.write("[Controls]/Reset", true).quality.statusCode)
            assertEquals(0, device.read("Writeable/Agitator1Mode").value)
            assertEquals(1, device.read("Overview/Refrigeration/HOA").value)
            assertEquals(true, device.read("Overview/Motor/Running").value)
        }
    }

    @Test fun slcWritableValuesPersistAcrossLaterIntervals() {
        // Every SLC address is a writable 0. A write is held until the next write.
        // doc source: FEATURES DEV-028
        val clock = ManualSimulatorClock(0)
        val config = simulatorDeviceConfig(
            "slc",
            mapOf("program" to "slc", "baseRateMs" to 1_000),
            setOf("program", "baseRateMs"),
        )
        assertEquals(config.instructions.map { it.browsePath }.toSet(), config.persistWrites)
        SimulatorDevice(config, clock).use { device ->
            assertEquals(0L, device.write("_Meta:N7/N7:3", 12).quality.statusCode)
            assertEquals(0L, device.write("F8/F8:1", 2.5).quality.statusCode)
            assertEquals(0L, device.write("_Meta:T4/T4:3/T4:3.DN", true).quality.statusCode)
            assertEquals(0L, device.write("C5/C5:2/C5:2.ACC", 7).quality.statusCode)
            clock.millis = 4_000
            assertEquals(12, device.read("N7/N7:3").value)
            assertEquals(2.5, device.read("_Meta:F8/F8:1").value)
            assertEquals(true, device.read("T4/T4:3/T4:3.DN").value)
            assertEquals(7, device.read("_Meta:C5/C5:2/C5:2.ACC").value)
            assertEquals(0, device.read("_Meta:N7/N7:4").value)
            assertEquals(0L, device.write("[Controls]/Reset", true).quality.statusCode)
            assertEquals(0, device.read("N7/N7:3").value)
        }
    }

    @Test fun dairyProgramBrowsesOverviewRefrigerationAndTheAgitator() {
        val config = simulatorDeviceConfig("dairy", mapOf("program" to "dairy"))
        assertTrue(config.legacyItemPaths)
        SimulatorDevice(config).use { device ->
            assertEquals(1, device.read("_Meta:Overview/Refrigeration/HOA").value)
            assertEquals(0, device.read("_Meta:Writeable/Agitator1Mode").value)
            assertEquals(true, device.read("_Meta:Overview/Refrigeration/Tank/Agitator/Running").value)
            val root = device.browse("").map { it.browseName }
            assertTrue(root.containsAll(listOf("[Controls]", "Overview", "Writeable")))
            assertEquals(listOf("Motor", "Refrigeration"), device.browse("Overview").map { it.browseName })
            val section = device.browse("_Meta:Overview/Refrigeration").map { it.browseName }
            assertTrue(section.containsAll(listOf("Compressor", "HOA", "Tank")))
            val agitator = device.browse("_Meta:Overview/Refrigeration/Tank/Agitator")
            assertEquals("Running", agitator.single().browseName)
            assertEquals(
                "_Meta:Overview/Refrigeration/Tank/Agitator/Running",
                agitator.single().nodeId,
            )
            assertFalse(device.exportProgram().contains("_Meta:"))
            assertEquals(config.instructions, ProgramCsv.import(device.exportProgram()))
        }
    }

    @Test fun slcProgramBrowsesFilesAndStructuredMembers() {
        val config = simulatorDeviceConfig("slc", mapOf("program" to "slc"))
        assertTrue(config.legacyItemPaths)
        SimulatorDevice(config).use { device ->
            val root = device.browse("").map { it.browseName }
            assertTrue(root.containsAll(listOf("[Controls]", "O0", "I1", "S2", "B3", "T4", "C5", "R6", "N7", "F8")))
            assertEquals((0..9).map { "N7:$it" }, device.browse("_Meta:N7").map { it.browseName })
            assertEquals(0, device.read("_Meta:N7/N7:3").value)
            assertEquals(0L, device.write("N7/N7:3", 12).quality.statusCode)
            assertEquals(12, device.read("_Meta:N7/N7:3").value)
            assertEquals(0.0, device.read("_Meta:F8/F8:3").value)
            val members = device.browse("_Meta:T4/T4:3").map { it.browseName }.sorted()
            assertEquals(listOf("T4:3.ACC", "T4:3.DN", "T4:3.EN", "T4:3.PRE", "T4:3.TT"), members)
            assertEquals(false, device.read("_Meta:T4/T4:3/T4:3.DN").value)
            assertEquals(0, device.read("_Meta:C5/C5:3/C5:3.ACC").value)
            assertEquals(0, device.read("_Meta:R6/R6:0/R6:0.LEN").value)
            assertEquals(0L, device.write("_Meta:O0/O0:3", 4).quality.statusCode)
            assertFalse(device.exportProgram().contains("_Meta:"))
            assertEquals(config.instructions.size, ProgramCsv.import(device.exportProgram()).size)
        }
    }

    @Test fun explicitInstructionsWinAndAnUnknownProgramIsRejected() {
        val spec = mapOf(
            "program" to "generic",
            "legacyMode" to false,
            "instructions" to listOf(
                mapOf("timeInterval" to 0, "browsePath" to "A", "valueSource" to "3", "dataType" to "Int32"),
            ),
        )
        val config = simulatorDeviceConfig("custom", spec)
        assertFalse(config.legacyItemPaths)
        assertEquals(listOf("A"), config.instructions.map { it.browsePath })
        assertThrows(IllegalArgumentException::class.java) {
            simulatorDeviceConfig("nope", mapOf("program" to "boiler"))
        }
    }

    private fun source(config: SimulatorDeviceConfig, path: String): String =
        config.instructions.first { it.browsePath == path }.valueSource
}
