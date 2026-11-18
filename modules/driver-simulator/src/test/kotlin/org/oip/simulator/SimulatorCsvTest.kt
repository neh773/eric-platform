package org.oip.simulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CSV import and export. Four columns, in order.
 *
 */
class SimulatorCsvTest {
    @Test fun roundTripKeepsCommasQuotesAndOrder() {
        val rows = listOf(
            SimInstruction(0, "TagA", "list(1, 2, 3, true)", "Int32"),
            SimInstruction(2, "a/b", "sine(0, 100, 10, true)", "Double"),
            SimInstruction(4, "say", "\"hello, world\"", "String"),
        )
        val csv = ProgramCsv.export(rows)
        assertTrue(csv.startsWith("Time Interval,Browse Path,Value Source,Data Type\n"))
        assertEquals(rows, ProgramCsv.import(csv))
        assertEquals(rows, ProgramCsv.import(csv.replace("\n", "\r\n")))
    }

    @Test fun exportStripsOneLegacyPrefixAndImportKeepsATypedPrefix() {
        val rows = listOf(SimInstruction(0, "_Meta:Writeable/Agitator1Mode", "0", "Int32"))
        val csv = ProgramCsv.export(rows)
        assertFalse(csv.contains("_Meta:"))
        assertTrue(csv.contains("Writeable/Agitator1Mode"))
        assertEquals("Writeable/Agitator1Mode", ProgramCsv.import(csv).single().browsePath)
        val typed = csv.replace("Writeable/Agitator1Mode", "_Meta:Writeable/Agitator1Mode")
        assertEquals("_Meta:Writeable/Agitator1Mode", ProgramCsv.import(typed).single().browsePath)
    }

    @Test fun importAcceptsAHeaderAndRejectsAShortRow() {
        val csv = "Time Interval,Browse Path,Value Source,Data Type\n0,A,1,Int32\n"
        assertEquals(SimInstruction(0, "A", "1", "Int32"), ProgramCsv.import(csv).single())
        assertEquals(emptyList<SimInstruction>(), ProgramCsv.import("Time Interval,Browse Path,Value Source,Data Type\n"))
        assertThrows(IllegalArgumentException::class.java) {
            ProgramCsv.import("0,A,1\n")
        }
    }

    @Test fun importedRampRunsOnTheManualClock() {
        val csv = ProgramCsv.export(listOf(SimInstruction(0, "A", "ramp(0,100,5,true)", "Int32")))
        val rows = ProgramCsv.import(csv)
        val clock = ManualSimulatorClock(0)
        SimulatorDevice(SimulatorDeviceConfig(name = "sim", instructions = rows), clock).use { device ->
            val values = (0 until 6).map { k ->
                clock.millis = k * 1_000L
                device.read("A").value
            }
            assertEquals(listOf(0, 25, 50, 75, 100, 0), values)
        }
    }
}
