package org.oip.logix

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.sim.logix.Ab
import org.oip.sim.logix.LogixSimulator
import org.oip.sim.logix.MemberSpec
import org.oip.sim.logix.SimFirmware
import java.time.Instant

/**
 * One Logix driver, four controller profiles: Micro800, legacy ControlLogix and legacy
 * CompactLogix (firmware 20 and earlier) next to the default.
 *
 * Every check runs against the in-repo simulator in its Micro800 and firmware-20 modes, which
 * model the public pages; no Micro800 or legacy controller was used.
 * doc source: FEATURES DEV-017, DEV-021, DEV-023
 */
@Timeout(60)
class LogixProfileTest {
    @Test fun micro800ReadsAndWritesItsAtomicTypes() {
        LogixSimulator(firmware = SimFirmware.MICRO800).use { sim ->
            sim.bool("Run", true)
            sim.atomic("Small", Ab.SINT, -4)
            sim.atomic("Count", Ab.INT, -300)
            sim.atomic("Total", Ab.DINT, 70_000)
            sim.atomic("Big", Ab.LINT, -5_000_000_000L)
            sim.atomic("U8", Ab.USINT, 200)
            sim.atomic("U16", Ab.UINT, 60_000)
            sim.atomic("U32", Ab.UDINT, 4_000_000_000L)
            sim.atomic("U64", Ab.ULINT, 7L)
            sim.atomic("B", Ab.BYTE, 0xF0)
            sim.atomic("W", Ab.WORD, 0xBEEF)
            sim.atomic("DW", Ab.DWORD, 5)
            sim.atomic("LW", Ab.LWORD, 0x0102030405060708L)
            sim.atomic("Rate", Ab.REAL, 2.5f)
            sim.atomic("Precise", Ab.LREAL, 0.125)
            sim.atomic("Day", Ab.DATE, 1_700_000_000L)
            sim.atomic("Dwell", Ab.MTIME, 1_500)
            sim.shortString("Label", "scale 1")
            val device = device(sim, LogixProfile.MICRO800)
            device.start()
            awaitConnected(device)
            assertTrue(device.identity.contains("Micro800"), device.identity)

            val names = listOf("Run", "Small", "Count", "Total", "Big", "U8", "U16", "U32", "U64", "B", "W", "DW", "LW", "Rate", "Precise", "Day", "Dwell", "Label")
            val values = device.read(names).associate { it.address to it.value }
            assertEquals(true, values["Run"])
            assertEquals(-4, values["Small"])
            assertEquals(-300, values["Count"])
            assertEquals(70_000, values["Total"])
            assertEquals(-5_000_000_000L, values["Big"])
            assertEquals(200, values["U8"])
            assertEquals(60_000, values["U16"])
            assertEquals(4_000_000_000L, values["U32"])
            assertEquals(7L, values["U64"])
            assertEquals(0xF0, values["B"])
            assertEquals(0xBEEF, values["W"])
            assertEquals(5, values["DW"])
            assertEquals(0x0102030405060708L, values["LW"])
            assertEquals(2.5f, values["Rate"])
            assertEquals(0.125, values["Precise"])
            assertEquals(1_700_000_000_000L, values["Day"], "DATE is milliseconds since the epoch")
            assertEquals(1_500, values["Dwell"], "TIME is a millisecond duration")
            assertEquals("scale 1", values["Label"])

            assertEquals(QualityCode.Good, device.write("U32", 4_294_967_295L).quality.code)
            assertEquals(4_294_967_295L, sim.get("U32"))
            assertEquals(QualityCode.Bad_OutOfRange, device.write("U8", 256).quality.code)
            assertEquals(QualityCode.Bad_OutOfRange, device.write("U16", -1).quality.code)
            assertEquals(QualityCode.Good, device.write("W", 0x1234).quality.code)
            assertEquals(0x1234, sim.get("W"))
            assertEquals(QualityCode.Good, device.write("Day", Instant.ofEpochSecond(1_800_000_000L)).quality.code)
            assertEquals(1_800_000_000L, sim.get("Day"))
            assertEquals(QualityCode.Good, device.write("Dwell", 250).quality.code)
            assertEquals(250, sim.get("Dwell"))

            assertEquals(QualityCode.Good, device.write("Label", "batch 42").quality.code)
            assertEquals("batch 42", sim.get("Label"))
            assertEquals("batch 42", device.read(listOf("Label")).single().value)
            assertEquals(QualityCode.Bad_OutOfRange, device.write("Label", "x".repeat(81)).quality.code)
            assertEquals("batch 42", sim.get("Label"))

            // Bit addressing on numeric types: `MyUint.1` style, including the top bit of a UDINT.
            assertEquals(QualityCode.Good, device.write("U16.0", true).quality.code)
            assertEquals(60_001, sim.get("U16"))
            assertEquals(true, device.read(listOf("U32.31")).single().value)
            assertEquals(QualityCode.Good, device.write("U32.31", false).quality.code)
            assertEquals(2_147_483_647L, sim.get("U32"))
            device.close()
        }
    }

    @Test fun micro800BrowsesGlobalsOnlyAndReadsStructureMembersByPath() {
        LogixSimulator(firmware = SimFirmware.MICRO800).use { sim ->
            sim.atomic("Speed", Ab.DINT, 3)
            sim.array("Bytes", Ab.USINT, listOf(1, 2, 0x80))
            sim.boolArray("Flags", 32, setOf(1))
            sim.defineUdt("Motor", listOf(MemberSpec("running", Ab.BOOL), MemberSpec("speed", Ab.DINT)))
            sim.structure("Line", "Motor", mapOf("running" to true, "speed" to 41))
            assertThrows(IllegalArgumentException::class.java) { sim.atomic("Step", Ab.DINT, 1, program = "Main") }
            val device = device(sim, LogixProfile.MICRO800)
            device.start()
            awaitConnected(device)

            assertEquals(listOf("Controller:Global"), device.browse("").map { it.nodeId })
            val globals = device.browse("Controller:Global").map { it.nodeId }
            assertEquals(listOf("Bytes", "Speed"), globals.sorted())
            assertTrue(device.browse("Line").isEmpty())
            assertEquals(0L, sim.templateRequests.get(), "no Template object requests on a Micro800")

            assertEquals(41, device.read(listOf("Line.speed")).single().value)
            assertEquals(true, device.read(listOf("Line.running")).single().value)
            assertEquals(true, device.read(listOf("Bytes[2].7")).single().value)
            assertEquals(QualityCode.Good, device.write("Line.speed", 42).quality.code)
            assertEquals(42, device.read(listOf("Line.speed")).single().value)
            val whole = device.read(listOf("Line")).single()
            assertEquals(QualityCode.Bad_TypeMismatch, whole.quality.code, whole.quality.diagnostic)

            val packets = sim.cipPackets.get()
            val scoped = device.read(listOf("Program:Main.Step")).single()
            assertEquals(QualityCode.Bad_ConfigurationError, scoped.quality.code)
            assertEquals(QualityCode.Bad_ConfigurationError, device.write("Program:Main.Step", 1).quality.code)
            assertEquals(packets, sim.cipPackets.get(), "a program-scoped address is not sent")
            assertEquals(null, device.describe("Program:Main"))
            device.close()
        }
    }

    @Test fun micro800UsesAStandardForwardOpenWithNoRoute() {
        LogixSimulator(firmware = SimFirmware.MICRO800).use { sim ->
            sim.atomic("Speed", Ab.DINT, 3)
            val device = device(sim, LogixProfile.MICRO800)
            device.start()
            awaitConnected(device)
            assertEquals("connected", device.messaging)
            assertEquals(0L, sim.largeForwardOpens.get())
            assertArrayEquals(ByteArray(0), sim.lastForwardOpenRoute)
            device.close()

            // The default profile asks for a large connection through the backplane; a Micro800 has neither.
            val wrong = device(sim, LogixProfile.LOGIX, cipConnectionSize = 1000)
            wrong.start()
            await("rejected") { wrong.reconnectDelaysMs.isNotEmpty() }
            assertFalse(wrong.connectionStatus().connected)
            wrong.close()
        }
        assertThrows(IllegalArgumentException::class.java) { config(LogixProfile.MICRO800, cipConnectionSize = 1000) }
        assertThrows(IllegalArgumentException::class.java) { config(LogixProfile.MICRO800, symbolInstanceAddressing = true) }
        assertThrows(IllegalArgumentException::class.java) { config(LogixProfile.MICRO800, slot = 2) }
    }

    @Test fun legacyControlLogixUsesStandardForwardOpenAndNamesEveryTag() {
        LogixSimulator(slot = 2, firmware = SimFirmware.V20).use { sim ->
            sim.atomic("Speed", Ab.DINT, 3)
            sim.defineUdt("Motor", listOf(MemberSpec("running", Ab.BOOL), MemberSpec("speed", Ab.DINT)))
            sim.structure("Line", "Motor", mapOf("running" to false, "speed" to 9))
            sim.atomic("Step", Ab.INT, 4, program = "Main")
            sim.string("Note", "legacy")
            val device = device(sim, LogixProfile.LEGACY_CONTROLLOGIX, slot = 2)
            device.start()
            awaitConnected(device)
            assertEquals("connected", device.messaging)
            assertArrayEquals(byteArrayOf(0x01, 0x02), sim.lastForwardOpenRoute)
            sim.resetStats()
            assertEquals(3, device.read(listOf("Speed")).single().value)
            assertEquals(9, device.read(listOf("Line.speed")).single().value)
            assertEquals(4, device.read(listOf("Program:Main.Step")).single().value)
            assertEquals("legacy", device.read(listOf("Note")).single().value)
            assertEquals(QualityCode.Good, device.write("Line.running", true).quality.code)
            assertEquals(mapOf("running" to true, "speed" to 9), device.read(listOf("Line")).single().value)
            assertEquals(0L, sim.instanceAddressed.get())
            assertTrue(sim.symbolAddressed.get() > 0)
            assertTrue("Program:Main" in device.browse("").map { it.nodeId })
            assertEquals(0L, sim.largeForwardOpens.get())
            device.close()

            // A firmware-20 controller refuses symbol instance addressing, which the legacy profile never sends.
            val modern = device(sim, LogixProfile.LOGIX, slot = 2, symbolInstanceAddressing = true)
            modern.start()
            awaitConnected(modern)
            await("catalog") { modern.browse("Controller:Global").isNotEmpty() }
            assertEquals(QualityCode.Bad_ConfigurationError, modern.read(listOf("Speed")).single().quality.code)
            modern.close()
        }
        assertThrows(IllegalArgumentException::class.java) { config(LogixProfile.LEGACY_CONTROLLOGIX, symbolInstanceAddressing = true) }
        assertThrows(IllegalArgumentException::class.java) { config(LogixProfile.LEGACY_CONTROLLOGIX, cipConnectionSize = 1000) }
        config(LogixProfile.LEGACY_CONTROLLOGIX, cipConnectionSize = 511, slot = 4)
    }

    @Test fun legacyProfilesBrowseOnConnectAndDoNotWatchTheCatalog() {
        LogixSimulator(firmware = SimFirmware.V20).use { sim ->
            sim.atomic("Speed", Ab.DINT, 3)
            val device = device(sim, LogixProfile.LEGACY_CONTROLLOGIX)
            device.start()
            awaitConnected(device)
            await("browse") { device.browse("Controller:Global").map { it.nodeId } == listOf("Speed") }
            sim.atomic("Added", Ab.DINT, 1)
            Thread.sleep(600)
            assertEquals(listOf("Speed"), device.browse("Controller:Global").map { it.nodeId })
            assertEquals(0L, sim.generationRequests.get(), "no catalog change polling on a legacy controller")
            assertEquals(1, device.read(listOf("Added")).single().value, "an unbrowsed tag is still read by name")

            sim.stop()
            await("down") { device.read(listOf("Speed")); !device.connectionStatus().connected }
            sim.start()
            awaitConnected(device)
            await("rebrowsed") { "Added" in device.browse("Controller:Global").map { it.nodeId } }
            assertEquals(0L, sim.generationRequests.get())
            device.close()
        }
    }

    @Test fun legacyCompactLogixRoutesToTheFirstSlotWithoutASlotSetting() {
        LogixSimulator(slot = 0, firmware = SimFirmware.V20).use { sim ->
            sim.atomic("Speed", Ab.DINT, 5)
            val device = device(sim, LogixProfile.LEGACY_COMPACTLOGIX)
            device.start()
            awaitConnected(device)
            assertArrayEquals(byteArrayOf(0x01, 0x00), sim.lastForwardOpenRoute)
            assertEquals(5, device.read(listOf("Speed")).single().value)
            assertEquals(0L, sim.largeForwardOpens.get())
            device.close()
        }
        assertThrows(IllegalArgumentException::class.java) { config(LogixProfile.LEGACY_COMPACTLOGIX, slot = 3) }
        assertThrows(IllegalArgumentException::class.java) { config(LogixProfile.LEGACY_COMPACTLOGIX, cipConnectionSize = 600) }
        config(LogixProfile.LEGACY_COMPACTLOGIX, connectionPath = "1,0")
    }

    @Test fun profileIsASchemaChoiceCheckedAtSave() {
        val registry = SchemaRegistry()
        registry.register(LogixDriverModule.MODULE_ID, DEVICE_KIND, logixDeviceSchema())
        registry.registerSemantic(LogixDriverModule.MODULE_ID, DEVICE_KIND, ::logixDeviceErrors)
        fun errors(spec: Map<String, Any?>) =
            registry.validate(Resource(DEVICE_KIND, Metadata("plc", LogixDriverModule.MODULE_ID), spec))
        assertTrue(errors(mapOf("host" to "h", "profile" to "micro800")).isEmpty())
        assertTrue(errors(mapOf("host" to "h", "profile" to "legacy-controllogix", "slot" to 3)).isEmpty())
        assertTrue(errors(mapOf("host" to "h", "profile" to "plc5")).isNotEmpty())
        assertTrue(errors(mapOf("host" to "h", "profile" to "micro800", "cipConnectionSize" to 1000)).single().contains("511"))
        assertTrue(errors(mapOf("host" to "h", "profile" to "legacy-compactlogix", "symbolInstanceAddressing" to true)).single().contains("symbol instance"))
        val applied = registry.applyDefaults(LogixDriverModule.MODULE_ID, DEVICE_KIND, mapOf("host" to "h"))
        assertEquals("logix", applied["profile"])
        assertEquals(LogixProfile.MICRO800, logixDeviceConfig("plc", mapOf("host" to "h", "profile" to "micro800")).profile)
    }

    private fun config(
        profile: LogixProfile,
        cipConnectionSize: Int = 500,
        slot: Int = 0,
        connectionPath: String = "",
        symbolInstanceAddressing: Boolean = false,
        port: Int = 44818,
    ) = LogixDeviceConfig(
        name = "plc",
        host = "127.0.0.1",
        port = port,
        timeoutMs = 1_000,
        slot = slot,
        connectionPath = connectionPath,
        cipConnectionSize = cipConnectionSize,
        identityRequestFrequencyMs = 0,
        symbolInstanceAddressing = symbolInstanceAddressing,
        backoffInitialMs = 40,
        backoffMaxMs = 200,
        profile = profile,
    )

    private fun device(
        sim: LogixSimulator,
        profile: LogixProfile,
        cipConnectionSize: Int = 500,
        slot: Int = 0,
        symbolInstanceAddressing: Boolean = false,
    ) = LogixDevice(config(profile, cipConnectionSize, slot, "", symbolInstanceAddressing, sim.port))

    private fun awaitConnected(device: LogixDevice) {
        await("connected ${device.connectionStatus()}") { device.connectionStatus().connected }
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }
}
