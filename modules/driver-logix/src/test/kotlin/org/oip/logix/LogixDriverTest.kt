package org.oip.logix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.ValidationException
import org.oip.host.ModuleHost
import org.oip.host.ModuleState
import org.oip.sim.logix.Ab
import org.oip.sim.logix.LogixSimulator
import org.oip.sim.logix.MemberSpec
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * The peer is the in-repo EtherNet/IP simulator (test-infra/sim/logix), not a PLC and not a
 * GPL stack. Doc source: FEATURES DEV-008, DEV-010.
 *
 * https://literature.rockwellautomation.com/idc/groups/literature/documents/pm/1756-pm020_-en-p.pdf
 *
 * The throughput figure is an in-process loopback number, not a measurement of a network PLC.
 */
@Timeout(60)
class LogixDriverTest {
    @Test fun connectsAndReportsIdentity() {
        LogixSimulator().use { sim ->
            sim.atomic("Speed", Ab.DINT, 1)
            val device = device(sim)
            device.start()
            awaitConnected(device)
            assertEquals(ConnectionState.CONNECTED, device.connectionStatus().state)
            assertTrue(device.connectionStatus().connected)
            assertTrue(device.identity.contains("Logix"), device.identity)
            assertTrue(device.diagnostics().connected)
            assertTrue(device.diagnostics().requestCount > 0)
            device.close()
        }
    }

    @Test fun wrongSlotDoesNotConnect() {
        LogixSimulator(slot = 0).use { sim ->
            val device = device(sim, connectionPath = "1,4", timeoutMs = 400, backoffInitialMs = 40, backoffMaxMs = 80)
            device.start()
            await("reconnect") { device.reconnectDelaysMs.isNotEmpty() }
            assertFalse(device.connectionStatus().connected)
            assertEquals(ConnectionState.RECONNECTING, device.connectionStatus().state)
            device.close()
        }
    }

    @Test fun browsesControllerAndProgramTags() {
        LogixSimulator().use { sim ->
            sim.atomic("Motor_1", Ab.DINT, 7)
            sim.defineUdt("Motor", listOf(MemberSpec("running", Ab.BOOL), MemberSpec("speed", Ab.DINT), MemberSpec("rate", Ab.REAL)))
            sim.structure("Line", "Motor", mapOf("running" to true, "speed" to 3, "rate" to 1.0f))
            sim.atomic("Step", Ab.INT, 4, program = "MainProgram")
            sim.string("Note", "hi", program = "MainProgram")
            val device = device(sim)
            device.start(); awaitConnected(device)
            val roots = device.browse("").map { it.nodeId }
            assertTrue("Controller:Global" in roots, roots.toString())
            assertTrue("Program:MainProgram" in roots, roots.toString())
            val global = device.browse("Controller:Global").map { it.displayName }
            assertTrue("Motor_1" in global, global.toString())
            assertTrue("Line" in global, global.toString())
            assertFalse(global.any { it.startsWith("Program:") })
            val members = device.browse("Line").map { it.nodeId }
            assertTrue("Line.running" in members, members.toString())
            assertTrue("Line.speed" in members, members.toString())
            assertTrue("Line.rate" in members, members.toString())
            assertTrue(members.none { it.contains("ZZZZZZZZZZ") })
            val program = device.browse("Program:MainProgram").map { it.nodeId }
            assertTrue("Program:MainProgram.Step" in program, program.toString())
            assertTrue("Program:MainProgram.Note" in program, program.toString())
            device.close()
        }
    }

    @Test fun readsAndWritesEachTypeArrayAndUdtMember() {
        LogixSimulator().use { sim ->
            sim.bool("Running", true)
            sim.atomic("Small", Ab.SINT, -5)
            sim.atomic("Count", Ab.INT, -300)
            sim.atomic("Total", Ab.DINT, 534)
            sim.atomic("Big", Ab.LINT, 0x1122334455667788L)
            sim.atomic("Rate", Ab.REAL, 1.5f)
            sim.atomic("Precise", Ab.LREAL, -2.25)
            sim.string("Message", "Hello")
            sim.array("Counts", Ab.DINT, listOf(10, 20, 30, 40))
            sim.array2("Grid", Ab.INT, 2, 3, listOf(1, 2, 3, 4, 5, 6))
            sim.boolArray("flags", 64, setOf(0, 31, 32, 63))
            sim.defineUdt("Motor", listOf(MemberSpec("running", Ab.BOOL), MemberSpec("speed", Ab.DINT), MemberSpec("rate", Ab.REAL)))
            sim.structure("Line", "Motor", mapOf("running" to false, "speed" to 1, "rate" to 0.5f))
            sim.atomic("Step", Ab.DINT, 9, program = "Main")
            sim.readOnly("Total")
            val device = device(sim)
            device.start(); awaitConnected(device)

            assertEquals(true, device.read(listOf("Running")).single().value)
            assertEquals(-5, device.read(listOf("Small")).single().value)
            assertEquals(-300, device.read(listOf("Count")).single().value)
            assertEquals(534, device.read(listOf("Total")).single().value)
            assertEquals(0x1122334455667788L, device.read(listOf("Big")).single().value)
            assertEquals(1.5f, device.read(listOf("Rate")).single().value)
            assertEquals(-2.25, device.read(listOf("Precise")).single().value)
            assertEquals("Hello", device.read(listOf("Message")).single().value)
            assertEquals(20, device.read(listOf("Counts[1]")).single().value)
            assertEquals(6, device.read(listOf("Grid[1,2]")).single().value)
            assertEquals(9, device.read(listOf("Program:Main.Step")).single().value)

            assertEquals(true, device.read(listOf("flags[0].0")).single().value)
            assertEquals(true, device.read(listOf("flags[0].31")).single().value)
            assertEquals(false, device.read(listOf("flags[0].1")).single().value)
            assertEquals(true, device.read(listOf("flags[1].0")).single().value)
            assertEquals(true, device.read(listOf("flags[1].31")).single().value)
            assertEquals(QualityCode.Good, device.write("flags[0].1", true).quality.code)
            assertEquals(true, device.read(listOf("flags[0].1")).single().value)
            assertEquals(true, sim.get("flags") is ByteArray)

            assertEquals(false, device.read(listOf("Line.running")).single().value)
            assertEquals(1, device.read(listOf("Line.speed")).single().value)
            assertEquals(0.5f, device.read(listOf("Line.rate")).single().value)
            assertEquals(QualityCode.Good, device.write("Line.running", true).quality.code)
            assertEquals(QualityCode.Good, device.write("Line.speed", 42).quality.code)
            assertEquals(QualityCode.Good, device.write("Line.rate", 3.5f).quality.code)
            assertEquals(true, device.read(listOf("Line.running")).single().value)
            assertEquals(42, device.read(listOf("Line.speed")).single().value)
            assertEquals(3.5f, device.read(listOf("Line.rate")).single().value)

            assertEquals(QualityCode.Good, device.write("Running", false).quality.code)
            assertEquals(false, device.read(listOf("Running")).single().value)
            assertEquals(false, sim.get("Running"))
            assertEquals(QualityCode.Good, device.write("Small", 12).quality.code)
            assertEquals(12, device.read(listOf("Small")).single().value)
            assertEquals(QualityCode.Good, device.write("Count", 8).quality.code)
            assertEquals(8, sim.get("Count"))
            assertEquals(QualityCode.Good, device.write("Big", -2L).quality.code)
            assertEquals(-2L, device.read(listOf("Big")).single().value)
            assertEquals(QualityCode.Good, device.write("Rate", 2.0f).quality.code)
            assertEquals(2.0f, device.read(listOf("Rate")).single().value)
            assertEquals(QualityCode.Good, device.write("Precise", 4.5).quality.code)
            assertEquals(4.5, device.read(listOf("Precise")).single().value)
            assertEquals(QualityCode.Good, device.write("Message", "World").quality.code)
            assertEquals("World", device.read(listOf("Message")).single().value)
            assertEquals("World", sim.get("Message"))
            assertEquals(QualityCode.Good, device.write("Counts[2]", 99).quality.code)
            assertEquals(99, device.read(listOf("Counts[2]")).single().value)
            assertEquals(QualityCode.Good, device.write("Program:Main.Step", 15).quality.code)
            assertEquals(15, sim.get("Step", "Main"))
            assertEquals(QualityCode.Bad_NotFound, device.write("Missing", 1).quality.code)
            assertEquals(QualityCode.Bad_NotFound, device.read(listOf("Missing")).single().quality.code)
            assertEquals(QualityCode.Bad_NotWritable, device.write("Total", 1).quality.code)
            assertEquals(534, device.read(listOf("Total")).single().value)
            assertEquals(QualityCode.Bad_OutOfRange, device.write("Small", 200).quality.code)
            assertEquals(QualityCode.Bad_TypeMismatch, device.write("Count", "nope").quality.code)
            assertEquals(QualityCode.Bad_ConfigurationError, device.read(listOf("Bad Name")).single().quality.code)
            device.close()
        }
    }

    @Test fun batchesReadsIntoMultipleServicePackets() {
        LogixSimulator().use { sim ->
            repeat(40) { sim.atomic("T%02d".format(it), Ab.DINT, it) }
            val device = device(sim)
            device.start(); awaitConnected(device)
            sim.resetStats()
            val before = device.readRequestCount
            val values = device.read((0 until 40).map { "T%02d".format(it) })
            assertEquals(40, values.size)
            assertTrue(values.all { it.quality.code == QualityCode.Good }, values.map { it.quality.code }.toString())
            assertEquals(0, values.first().value)
            assertEquals(39, values.last().value)
            val frames = device.readRequestCount - before
            assertTrue(frames < 40, "frames $frames")
            assertTrue(sim.mspPackets.get() >= 1, "msp ${sim.mspPackets.get()}")
            assertTrue(sim.maxEmbedded.get() > 1, "embedded ${sim.maxEmbedded.get()}")
            device.close()
        }
    }

    /**
     * DT (Date/Time), LDT (Date/Time [ns]), LTIME (LTime [ns]), TIME (Decimal), TIME32 (Time32 [µs]),
     * and the same types as arrays.
     * doc source: FEATURES DEV-010
     */
    @Test fun readsAndWritesDateTimeTypesAndTheirArrays() {
        val dt = Instant.parse("2024-06-15T12:30:00.000123Z")
        val dt2 = Instant.parse("2024-06-16T01:00:00Z")
        val ldt = Instant.parse("2024-06-15T12:30:00.000123456Z")
        val ldt2 = Instant.parse("2025-01-01T00:00:00.000000001Z")
        LogixSimulator().use { sim ->
            sim.atomic("Stamp", Ab.DT, micros(dt))
            sim.atomic("StampNs", Ab.LDT, nanos(ldt))
            sim.atomic("Span", Ab.LTIME, 1_500_000_000L)
            sim.atomic("Tick", Ab.TIME32, 1_500_000)
            sim.atomic("Duration", Ab.TIME, 1_500_001L)
            sim.array("Stamps", Ab.DT, listOf(micros(dt), micros(dt2)))
            sim.array("StampsNs", Ab.LDT, listOf(nanos(ldt), nanos(ldt2)))
            sim.array("Spans", Ab.LTIME, listOf(10L, 20L))
            sim.array("Ticks", Ab.TIME32, listOf(5, 6))
            sim.array("Durations", Ab.TIME, listOf(1_000_000L, 2_500_000L))
            val device = device(sim)
            device.start(); awaitConnected(device)
            sim.resetStats()

            assertEquals(dt, device.read(listOf("Stamp")).single().value)
            assertEquals(ldt, device.read(listOf("StampNs")).single().value)
            assertEquals(1_500_000_000L, device.read(listOf("Span")).single().value)
            assertEquals(1_500_000, device.read(listOf("Tick")).single().value)
            assertDecimal("1.500001", device.read(listOf("Duration")).single().value)
            assertEquals(dt2, device.read(listOf("Stamps[1]")).single().value)
            assertEquals(ldt2, device.read(listOf("StampsNs[1]")).single().value)
            assertEquals(20L, device.read(listOf("Spans[1]")).single().value)
            assertEquals(6, device.read(listOf("Ticks[1]")).single().value)
            assertDecimal("2.5", device.read(listOf("Durations[1]")).single().value)

            assertEquals(QualityCode.Good, device.write("Stamp", dt2).quality.code)
            assertEquals(dt2, device.read(listOf("Stamp")).single().value)
            assertEquals(micros(dt2), sim.get("Stamp"))
            assertEquals(0, sim.fragmentedWrites.get())
            assertEquals(QualityCode.Good, device.write("StampNs", ldt2).quality.code)
            assertEquals(ldt2, device.read(listOf("StampNs")).single().value)
            assertEquals(nanos(ldt2), sim.get("StampNs"))
            assertEquals(QualityCode.Good, device.write("Span", 42L).quality.code)
            assertEquals(42L, device.read(listOf("Span")).single().value)
            assertEquals(QualityCode.Good, device.write("Tick", 99).quality.code)
            assertEquals(99, device.read(listOf("Tick")).single().value)
            assertEquals(QualityCode.Good, device.write("Duration", BigDecimal("2.25")).quality.code)
            assertDecimal("2.25", device.read(listOf("Duration")).single().value)
            assertEquals(2_250_000L, sim.get("Duration"))
            assertEquals(QualityCode.Good, device.write("Stamps[0]", dt).quality.code)
            assertEquals(dt, device.read(listOf("Stamps[0]")).single().value)
            assertEquals(QualityCode.Good, device.write("Durations[1]", BigDecimal("3.5")).quality.code)
            assertDecimal("3.5", device.read(listOf("Durations[1]")).single().value)
            assertEquals(QualityCode.Good, device.write("Spans[0]", 7L).quality.code)
            assertEquals(7L, device.read(listOf("Spans[0]")).single().value)
            assertEquals(QualityCode.Good, device.write("Ticks[0]", 8).quality.code)
            assertEquals(8, device.read(listOf("Ticks[0]")).single().value)
            assertEquals(QualityCode.Good, device.write("StampsNs[0]", ldt).quality.code)
            assertEquals(ldt, device.read(listOf("StampsNs[0]")).single().value)

            assertEquals(QualityCode.Bad_OutOfRange, device.write("Stamp", Instant.parse("1960-01-01T00:00:00Z")).quality.code)
            assertEquals(dt2, device.read(listOf("Stamp")).single().value)
            assertEquals(QualityCode.Bad_OutOfRange, device.write("Tick", 3_000_000_000L).quality.code)
            assertEquals(99, device.read(listOf("Tick")).single().value)
            assertEquals(QualityCode.Bad_TypeMismatch, device.write("StampNs", "nope").quality.code)
            assertEquals(ldt2, device.read(listOf("StampNs")).single().value)
            device.close()
        }
    }

    @Test fun fragmentedStringReadSurvivesASmallConnection() {
        LogixSimulator().use { sim ->
            sim.string("Message", "abcdefghijklmnopqrstuvwxyz")
            val device = device(sim, cipConnectionSize = 80)
            device.start(); awaitConnected(device)
            sim.resetStats()
            assertEquals("abcdefghijklmnopqrstuvwxyz", device.read(listOf("Message")).single().value)
            assertTrue(sim.fragmentedReads.get() >= 1, "fragments ${sim.fragmentedReads.get()}")
            device.close()
        }
    }

    /**
     * SPEC §5: comms loss sets bad quality within one scan period plus the request timeout,
     * and the last value is kept. A stalled peer spends the timeout. Closing the socket fails
     * inside the scan period. The slack past scan+timeout is scheduling room for the test.
     */
    @Test fun commsLossSetsBadQualityWithinScanPlusTimeoutThenRecovers() {
        val scan = 100L
        val timeout = 200L
        val budget = scan + timeout + 1_500L
        LogixSimulator().use { sim ->
            sim.atomic("Speed", Ab.DINT, 42)
            val device = device(
                sim, timeoutMs = timeout, scanMs = scan,
                backoffInitialMs = 40, backoffMaxMs = 200,
                identityMs = 0,
            )
            device.start(); awaitConnected(device)
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            val sub = device.subscribe(listOf("Speed"), scan.toDouble()) { seen += it }
            await("initial good") { seen.any { it.quality.code == QualityCode.Good && it.value == 42 } }

            val stalled = System.nanoTime()
            sim.holdReads = true
            await("bad after stall") { seen.any { it.value == 42 && it.quality.level == QualityLevel.BAD } }
            val stallMs = (System.nanoTime() - stalled) / 1_000_000
            val during = seen.last { it.value == 42 && it.quality.level == QualityLevel.BAD }
            assertEquals(42, during.value)
            assertTrue(
                during.quality.code == QualityCode.Bad_NotConnected || during.quality.code == QualityCode.Bad_Timeout,
                "quality ${during.quality.code}",
            )
            assertTrue(stallMs <= budget, "stall took ${stallMs}ms, budget ${budget}ms")

            sim.holdReads = false
            val after = seen.size
            await("recovered from stall") {
                device.connectionStatus().connected &&
                    seen.drop(after).any { it.quality.code == QualityCode.Good && it.value == 42 }
            }

            val closed = System.nanoTime()
            sim.stop()
            await("bad after close") { seen.any { it.quality.code == QualityCode.Bad_NotConnected && it.value == 42 } }
            val closeMs = (System.nanoTime() - closed) / 1_000_000
            assertTrue(closeMs <= budget, "close took ${closeMs}ms, budget ${budget}ms")
            assertEquals(42, seen.last { it.quality.code == QualityCode.Bad_NotConnected }.value)
            sim.start()
            val afterClose = seen.size
            await("recovered from close") {
                device.connectionStatus().connected &&
                    seen.drop(afterClose).any { it.quality.code == QualityCode.Good && it.value == 42 }
            }
            assertEquals(ConnectionState.CONNECTED, device.connectionStatus().state)
            sub.close()
            device.close()
        }
    }

    /**
     * BOOL[64] is browsed as flags[0].0 .. flags[0].31, flags[1].0 .. flags[1].31.
     * A shorter array does not invent bits past its length.
     * doc source: FEATURES DEV-010
     */
    @Test fun browsesBoolArrayBitsUnderTheArray() {
        LogixSimulator().use { sim ->
            sim.boolArray("flags", 64, setOf(0, 31, 32))
            sim.boolArray("few", 40, setOf(39), program = "Main")
            val device = device(sim)
            device.start(); awaitConnected(device)
            val parent = device.browse("Controller:Global").first { it.nodeId == "flags" }
            assertEquals("Object", parent.nodeClass)
            val bits = device.browse("flags")
            assertEquals(64, bits.size)
            assertEquals("flags[0].0", bits.first().nodeId)
            assertTrue(bits.any { it.nodeId == "flags[0].31" && it.nodeClass == "Variable" && it.readable && it.writable })
            assertTrue(bits.any { it.nodeId == "flags[1].0" })
            assertTrue(bits.any { it.nodeId == "flags[1].31" })
            assertEquals(true, device.read(listOf("flags[1].0")).single().value)
            assertEquals(false, device.read(listOf("flags[1].1")).single().value)
            val few = device.browse("Program:Main.few").map { it.nodeId }
            assertEquals(40, few.size)
            assertTrue("Program:Main.few[0].0" in few)
            assertTrue("Program:Main.few[1].7" in few)
            assertFalse(few.any { it.endsWith("[1].8") })
            assertFalse(few.any { it.contains("[2]") })
            assertEquals(true, device.read(listOf("Program:Main.few[1].7")).single().value)
            device.close()
        }
    }

    /**
     * Alias tags and one-level Add-On Instruction instances, including program scope.
     * Nested AOI members are covered by browsesReadsAndWritesNestedAoiMembers.
     * doc source: FEATURES DEV-008
     */
    @Test fun browsesAndWritesAliasAndAoiMembers() {
        LogixSimulator().use { sim ->
            sim.defineAoi("Valve", listOf(MemberSpec("position", Ab.DINT), MemberSpec("rate", Ab.REAL)))
            sim.structure("Inlet", "Valve", mapOf("EnableIn" to true, "position" to 3, "rate" to 1.5f))
            sim.structure("Outlet", "Valve", mapOf("position" to 9, "rate" to 0.25f), program = "Main")
            sim.alias("InletAlias", "Inlet")
            sim.alias("PosAlias", "Inlet.position")
            sim.alias("OutletAlias", "Outlet", program = "Main")
            val device = device(sim)
            device.start(); awaitConnected(device)
            val global = device.browse("Controller:Global").map { it.nodeId }
            assertTrue("Inlet" in global, global.toString())
            assertTrue("InletAlias" in global, global.toString())
            assertTrue("PosAlias" in global, global.toString())
            val inlet = device.browse("Inlet").map { it.nodeId }
            assertTrue("Inlet.EnableIn" in inlet, inlet.toString())
            assertTrue("Inlet.EnableOut" in inlet, inlet.toString())
            assertTrue("Inlet.position" in inlet, inlet.toString())
            assertTrue("Inlet.rate" in inlet, inlet.toString())
            assertTrue(inlet.none { it.contains("ZZZZZZZZZZ") })
            val aliasMembers = device.browse("InletAlias").map { it.nodeId }
            assertTrue("InletAlias.position" in aliasMembers, aliasMembers.toString())
            assertTrue("InletAlias.EnableIn" in aliasMembers, aliasMembers.toString())
            val program = device.browse("Program:Main").map { it.nodeId }
            assertTrue("Program:Main.Outlet" in program, program.toString())
            assertTrue("Program:Main.OutletAlias" in program, program.toString())
            val outletAlias = device.browse("Program:Main.OutletAlias").map { it.nodeId }
            assertTrue("Program:Main.OutletAlias.position" in outletAlias, outletAlias.toString())
            assertTrue("Program:Main.OutletAlias.rate" in outletAlias, outletAlias.toString())

            assertEquals(true, device.read(listOf("Inlet.EnableIn")).single().value)
            assertEquals(false, device.read(listOf("Inlet.EnableOut")).single().value)
            assertEquals(3, device.read(listOf("InletAlias.position")).single().value)
            assertEquals(1.5f, device.read(listOf("InletAlias.rate")).single().value)
            assertEquals(3, device.read(listOf("PosAlias")).single().value)
            assertEquals(QualityCode.Good, device.write("InletAlias.position", 11).quality.code)
            assertEquals(11, device.read(listOf("Inlet.position")).single().value)
            assertEquals(11, device.read(listOf("PosAlias")).single().value)
            assertEquals(9, device.read(listOf("Program:Main.Outlet.position")).single().value)
            assertEquals(QualityCode.Good, device.write("Program:Main.OutletAlias.rate", 2.5f).quality.code)
            assertEquals(2.5f, device.read(listOf("Program:Main.Outlet.rate")).single().value)
            assertEquals(2.5f, device.read(listOf("Program:Main.OutletAlias.rate")).single().value)
            device.close()
        }
    }

    /**
     * An Add-On Instruction that holds another AOI, which holds a UDT, browses at every level, in
     * controller and program scope and through an alias. Leaves read and write, and a whole-instance
     * read returns the nested members as nested maps.
     * doc source: FEATURES DEV-008
     */
    @Test fun browsesReadsAndWritesNestedAoiMembers() {
        LogixSimulator().use { sim ->
            val seal = sim.defineUdt("Seal", listOf(MemberSpec("wear", Ab.DINT), MemberSpec("note", Ab.SINT, 4)))
            val pump = sim.defineAoi("Pump", listOf(MemberSpec("speed", Ab.DINT), MemberSpec.structure("seal", seal)))
            sim.defineAoi("Station", listOf(MemberSpec("mode", Ab.INT), MemberSpec.structure("pump", pump), MemberSpec("rate", Ab.REAL)))
            sim.structure(
                "Station1", "Station",
                mapOf("mode" to 2, "rate" to 1.5f, "pump" to mapOf("EnableIn" to true, "speed" to 40, "seal" to mapOf("wear" to 7))),
            )
            sim.structure("Station2", "Station", mapOf("pump" to mapOf("speed" to 5)), program = "Main")
            sim.alias("PumpAlias", "Station1.pump")
            val device = device(sim)
            device.start(); awaitConnected(device)

            val top = device.browse("Station1").associateBy { it.nodeId }
            assertEquals("Object", top.getValue("Station1.pump").nodeClass)
            assertEquals("Variable", top.getValue("Station1.mode").nodeClass)
            val inner = device.browse("Station1.pump").associateBy { it.nodeId }
            assertEquals(setOf("Station1.pump.EnableIn", "Station1.pump.EnableOut", "Station1.pump.speed", "Station1.pump.seal"), inner.keys)
            assertEquals("Object", inner.getValue("Station1.pump.seal").nodeClass)
            assertTrue(inner.getValue("Station1.pump.speed").writable)
            val deepest = device.browse("Station1.pump.seal").map { it.nodeId }
            assertEquals(listOf("Station1.pump.seal.wear", "Station1.pump.seal.note"), deepest)
            assertEquals("Object", device.describe("Station1.pump")!!.nodeClass)
            assertEquals("Object", device.describe("Station1.pump.seal")!!.nodeClass)
            assertEquals("Variable", device.describe("Station1.pump.seal.wear")!!.nodeClass)
            val program = device.browse("Program:Main.Station2.pump").map { it.nodeId }
            assertTrue("Program:Main.Station2.pump.speed" in program, program.toString())
            assertTrue("Program:Main.Station2.pump.seal.wear" in device.browse("Program:Main.Station2.pump.seal").map { it.nodeId })
            val alias = device.browse("PumpAlias").map { it.nodeId }
            assertTrue("PumpAlias.seal" in alias, alias.toString())
            assertEquals(listOf("PumpAlias.seal.wear", "PumpAlias.seal.note"), device.browse("PumpAlias.seal").map { it.nodeId })

            assertEquals(40, device.read(listOf("Station1.pump.speed")).single().value)
            assertEquals(7, device.read(listOf("Station1.pump.seal.wear")).single().value)
            assertEquals(true, device.read(listOf("Station1.pump.EnableIn")).single().value)
            assertEquals(7, device.read(listOf("PumpAlias.seal.wear")).single().value)
            val whole = device.read(listOf("Station1")).single().value as Map<*, *>
            assertEquals(2, whole["mode"])
            assertEquals(1.5f, whole["rate"])
            val nested = whole["pump"] as Map<*, *>
            assertEquals(40, nested["speed"])
            assertEquals(true, nested["EnableIn"])
            assertEquals(7, (nested["seal"] as Map<*, *>)["wear"])

            assertEquals(QualityCode.Good, device.write("Station1.pump.seal.wear", 9).quality.code)
            assertEquals(9, device.read(listOf("Station1.pump.seal.wear")).single().value)
            assertEquals(QualityCode.Good, device.write("Program:Main.Station2.pump.seal.wear", 4).quality.code)
            assertEquals(4, device.read(listOf("Program:Main.Station2.pump.seal.wear")).single().value)
            assertEquals(5, device.read(listOf("Program:Main.Station2.pump.speed")).single().value)
            val replaced = device.write("Station1.pump", mapOf("speed" to 41, "seal" to mapOf("wear" to 3)))
            assertEquals(QualityCode.Good, replaced.quality.code, replaced.quality.diagnostic)
            assertEquals(41, device.read(listOf("Station1.pump.speed")).single().value)
            assertEquals(3, device.read(listOf("PumpAlias.seal.wear")).single().value)
            assertEquals(2, device.read(listOf("Station1.mode")).single().value)
            device.close()
        }
    }

    /**
     * Symbol instance addressing (1756-PM020): once the catalog is browsed, Read/Write Tag name the
     * root tag by its Symbol object instance instead of its name. Members, elements, BOOL-array bits,
     * program tags, aliases and nested AOI members still resolve. Off, every request is symbolic.
     * doc source: FEATURES DEV-010
     */
    @Test fun symbolInstanceAddressingReachesTagsByInstance() {
        LogixSimulator().use { sim ->
            sim.atomic("Speed", Ab.DINT, 7)
            sim.array("Bulk", Ab.DINT, listOf(1, 2, 3))
            sim.atomic("Count", Ab.DINT, 12, program = "Main")
            sim.boolArray("Flags", 64, setOf(3))
            sim.defineUdt("Motor", listOf(MemberSpec("running", Ab.BOOL), MemberSpec("speed", Ab.DINT)))
            sim.structure("Line", "Motor", mapOf("running" to true, "speed" to 5))
            val pump = sim.defineAoi("Pump", listOf(MemberSpec("speed", Ab.DINT)))
            sim.defineAoi("Station", listOf(MemberSpec.structure("pump", pump)))
            sim.structure("Station1", "Station", mapOf("pump" to mapOf("speed" to 40)))
            sim.alias("SpeedAlias", "Speed")

            val device = device(sim, symbolInstanceAddressing = true)
            device.start(); awaitConnected(device)
            sim.resetStats()
            val addresses = listOf(
                "Speed", "Bulk[1]", "Program:Main.Count", "Flags[0].3", "Line.running", "Line.speed",
                "Station1.pump.speed", "SpeedAlias",
            )
            assertEquals(listOf<Any?>(7, 2, 12, true, true, 5, 40, 7), device.read(addresses).map { it.value })
            assertEquals(QualityCode.Good, device.write("Speed", 8).quality.code)
            assertEquals(QualityCode.Good, device.write("Program:Main.Count", 13).quality.code)
            assertEquals(QualityCode.Good, device.write("Line.speed", 6).quality.code)
            assertEquals(QualityCode.Good, device.write("Flags[1].0", true).quality.code)
            assertEquals(QualityCode.Good, device.write("Station1.pump.speed", 41).quality.code)
            assertEquals(8, sim.get("Speed"))
            assertEquals(13, sim.get("Count", program = "Main"))
            assertEquals(listOf<Any?>(8, 6, true, 41, 8), device.read(listOf("Speed", "Line.speed", "Flags[1].0", "Station1.pump.speed", "SpeedAlias")).map { it.value })
            assertTrue(sim.instanceAddressed.get() >= 10, "instance-addressed ${sim.instanceAddressed.get()}")
            assertEquals(0L, sim.symbolAddressed.get(), "symbolic requests")
            device.close()

            val plain = device(sim)
            plain.start(); awaitConnected(plain)
            sim.resetStats()
            assertEquals(listOf<Any?>(8, 13, 41), plain.read(listOf("Speed", "Program:Main.Count", "Station1.pump.speed")).map { it.value })
            assertEquals(0L, sim.instanceAddressed.get())
            assertTrue(sim.symbolAddressed.get() >= 3)
            plain.close()
        }
    }

    /**
     * Automatic Rebrowse detects new tags and UDT changes while the session stays up.
     * doc source: FEATURES DEV-010
     * Disabled, tags are browsed when connecting and reconnecting:
     * doc source: FEATURES DEV-008
     */
    @Test fun automaticRebrowsePicksUpNewTagsAndUdtEdits() {
        LogixSimulator().use { sim ->
            sim.atomic("Speed", Ab.DINT, 1)
            sim.defineUdt("Motor", listOf(MemberSpec("speed", Ab.DINT)))
            sim.structure("Line", "Motor", mapOf("speed" to 5))
            val device = device(sim)
            device.start(); awaitConnected(device)
            assertTrue(device.browse("Controller:Global").any { it.nodeId == "Speed" })
            assertTrue(device.browse("Line").any { it.nodeId == "Line.speed" })
            assertFalse(device.browse("Controller:Global").any { it.nodeId == "Added" })
            assertFalse(device.browse("Line").any { it.nodeId == "Line.hours" })
            sim.atomic("Added", Ab.DINT, 8)
            sim.reviseUdt("Motor", listOf(MemberSpec("speed", Ab.DINT), MemberSpec("hours", Ab.DINT)))
            await("rebrowse") {
                device.browse("Controller:Global").any { it.nodeId == "Added" } &&
                    device.browse("Line").any { it.nodeId == "Line.hours" }
            }
            assertEquals(8, device.read(listOf("Added")).single().value)
            assertEquals(5, device.read(listOf("Line.speed")).single().value)
            device.close()
        }
    }

    @Test fun automaticRebrowseOffKeepsTheConnectedCatalog() {
        LogixSimulator().use { sim ->
            sim.atomic("Speed", Ab.DINT, 1)
            val device = device(sim, automaticRebrowse = false)
            device.start(); awaitConnected(device)
            assertTrue(device.browse("Controller:Global").any { it.nodeId == "Speed" })
            sim.atomic("Added", Ab.DINT, 2)
            Thread.sleep(700)
            assertFalse(device.browse("Controller:Global").any { it.nodeId == "Added" })
            device.close()
        }
    }

    /** A value that does not fit one CIP packet is Write Tag Fragmented and round-trips. 1756-PM020. */
    @Test fun fragmentedWriteRoundTripsLargeArrayStringAndUdt() {
        LogixSimulator().use { sim ->
            val n = 1000
            sim.array("Bulk", Ab.DINT, List(n) { 0 })
            sim.string("Note", "", capacity = 400)
            sim.defineUdt("Blob", listOf(MemberSpec("raw", Ab.SINT, 400), MemberSpec("tail", Ab.DINT)))
            sim.structure("BlobTag", "Blob", mapOf("tail" to 0))
            val device = device(sim, cipConnectionSize = 120)
            device.start(); awaitConnected(device)
            sim.resetStats()
            val values = List(n) { it }
            assertEquals(QualityCode.Good, device.write("Bulk", values).quality.code)
            assertTrue(sim.fragmentedWrites.get() >= 2, "fragments ${sim.fragmentedWrites.get()}")
            assertEquals(0, device.read(listOf("Bulk[0]")).single().value)
            assertEquals(n - 1, device.read(listOf("Bulk[${n - 1}]")).single().value)
            val text = "abc".repeat(100)
            val beforeString = sim.fragmentedWrites.get()
            assertEquals(QualityCode.Good, device.write("Note", text).quality.code)
            assertTrue(sim.fragmentedWrites.get() > beforeString, "string fragments ${sim.fragmentedWrites.get()}")
            assertEquals(text, device.read(listOf("Note")).single().value)
            assertEquals(text, sim.get("Note"))
            val raw = List(400) { (it % 50) - 25 }
            val beforeUdt = sim.fragmentedWrites.get()
            assertEquals(QualityCode.Good, device.write("BlobTag", mapOf("raw" to raw, "tail" to 77)).quality.code)
            assertTrue(sim.fragmentedWrites.get() > beforeUdt, "udt fragments ${sim.fragmentedWrites.get()}")
            assertEquals(77, device.read(listOf("BlobTag.tail")).single().value)
            assertEquals((399 % 50) - 25, device.read(listOf("BlobTag.raw[399]")).single().value)
            device.close()
        }
    }

    @Test fun fragmentedWriteFailureIsNotSuccess() {
        LogixSimulator().use { sim ->
            sim.array("Bulk", Ab.DINT, List(400) { 7 })
            val device = device(sim, cipConnectionSize = 80)
            device.start(); awaitConnected(device)
            sim.resetStats()
            sim.failWriteFragment = 2
            val result = device.write("Bulk", List(400) { 99 })
            assertNotEquals(QualityCode.Good, result.quality.code)
            assertEquals(QualityLevel.BAD, result.quality.level)
            assertTrue(sim.fragmentedWrites.get() >= 2, "fragments ${sim.fragmentedWrites.get()}")
            assertEquals(7, device.read(listOf("Bulk[399]")).single().value)
            device.close()
        }
    }

    @Test fun fragmentedWriteThroughput() {
        LogixSimulator().use { sim ->
            val n = 1024
            sim.array("Bulk", Ab.DINT, List(n) { 0 })
            val device = device(sim, cipConnectionSize = 500, timeoutMs = 2_000)
            device.start(); awaitConnected(device)
            val values = List(n) { it }
            sim.resetStats()
            val warm = device.write("Bulk", values)
            assertEquals(QualityCode.Good, warm.quality.code)
            assertTrue(sim.fragmentedWrites.get() >= 2, "fragments ${sim.fragmentedWrites.get()}")
            assertEquals(0, device.read(listOf("Bulk[0]")).single().value)
            assertEquals(n - 1, device.read(listOf("Bulk[${n - 1}]")).single().value)
            val start = System.nanoTime()
            var writes = 0
            val deadline = start + 1_000_000_000L
            while (System.nanoTime() < deadline) {
                val result = device.write("Bulk", values)
                assertEquals(QualityCode.Good, result.quality.code)
                writes++
            }
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            val rate = writes / seconds
            println(
                "BENCH logix fragmented-write arrays/s=${"%.1f".format(rate)} writes=$writes " +
                    "seconds=${"%.2f".format(seconds)} fragments=${sim.fragmentedWrites.get()}",
            )
            assertTrue(rate >= 200.0, "fragmented write throughput $rate arrays/s")
            device.close()
        }
    }

    @Test fun throughputAgainstTheSimulator() {
        LogixSimulator().use { sim ->
            repeat(200) { sim.atomic("N$it", Ab.DINT, it) }
            val device = device(sim, cipConnectionSize = 2000, timeoutMs = 2_000, concurrent = 2)
            device.start(); awaitConnected(device)
            val addresses = (0 until 200).map { "N$it" }
            val warm = device.read(addresses)
            assertEquals(0, warm.first().value)
            assertEquals(199, warm.last().value)
            assertEquals(QualityCode.Good, warm.last().quality.code)
            val start = System.nanoTime()
            var samples = 0L
            var calls = 0
            val deadline = start + 1_000_000_000L
            while (System.nanoTime() < deadline) {
                val batch = device.read(addresses)
                assertEquals(200, batch.size)
                assertEquals(QualityCode.Good, batch.first().quality.code)
                assertEquals(QualityCode.Good, batch.last().quality.code)
                samples += batch.size
                calls++
            }
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            val rate = samples / seconds
            println("BENCH logix in-process values/s=${"%.0f".format(rate)} calls=$calls seconds=${"%.2f".format(seconds)} frames=${device.readRequestCount}")
            assertTrue(rate >= 2_500.0, "throughput $rate values/s")
            device.close()
        }
    }

    @TempDir lateinit var tmp: Path

    @Test fun deviceSpecAndModuleRoundTrip() {
        val registry = SchemaRegistry()
        registry.register(LogixDriverModule.MODULE_ID, DEVICE_KIND, logixDeviceSchema())
        val spec = mapOf(
            "host" to "10.0.0.8",
            "port" to 44818,
            "slot" to 2,
            "cipConnectionSize" to 1000,
            "timeoutMs" to 2000,
        )
        val applied = registry.applyDefaults(LogixDriverModule.MODULE_ID, DEVICE_KIND, spec)
        val config = logixDeviceConfig("line", applied)
        assertEquals("line", config.name)
        assertEquals(2, config.slot)
        assertEquals(1000, config.cipConnectionSize)
        assertEquals(44818, applied["port"])
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(DEVICE_KIND, Metadata("line", LogixDriverModule.MODULE_ID), spec + ("cipConnectionSize" to 10)))
        }
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(DEVICE_KIND, Metadata("line", LogixDriverModule.MODULE_ID), spec + ("nope" to true)))
        }
        val file = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n").trim()
        assertEquals(file, LogixDriverModule.MANIFEST_YAML.trim())

        val id = LogixDriverModule.MODULE_ID
        val modules = tmp.resolve("modules")
        modules.resolve("driver-logix").createDirectories().resolve("module.yaml").writeText(javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n"))
        val configDir = tmp.resolve("config")
        val store = FileConfigStore(configDir, SchemaRegistry())
        val host = ModuleHost(modules, configDir, store = store)
        host.installAll()
        assertEquals(ModuleState.INSTALLED, host.state(id))
        assertEquals(2000, store.list(module = id).single().resource.spec["defaultTimeoutMs"])
        host.start(id)
        val module = host.instance(id) as LogixDriverModule
        assertEquals(ModuleState.RUNNING, host.state(id))
        host.stop(id)
        host.uninstall(id)
        assertTrue(host.installed().isEmpty())
        assertEquals(0, store.list(module = id).size)
        assertFalse(configDir.resolve(id).exists())
        assertTrue(module.device("none") == null)
    }

    private fun device(
        sim: LogixSimulator,
        timeoutMs: Long = 1_000,
        scanMs: Long = 1_000,
        connectionPath: String = "",
        cipConnectionSize: Int = 500,
        backoffInitialMs: Long = 40,
        backoffMaxMs: Long = 400,
        identityMs: Long = 5_000,
        concurrent: Int = 1,
        automaticRebrowse: Boolean = true,
        symbolInstanceAddressing: Boolean = false,
    ) = LogixDevice(LogixDeviceConfig(
        name = "sim",
        host = "127.0.0.1",
        port = sim.port,
        timeoutMs = timeoutMs,
        connectionPath = connectionPath,
        slot = 0,
        cipConnectionSize = cipConnectionSize,
        identityRequestFrequencyMs = identityMs,
        maxConcurrentRequests = concurrent,
        automaticRebrowse = automaticRebrowse,
        symbolInstanceAddressing = symbolInstanceAddressing,
        backoffInitialMs = backoffInitialMs,
        backoffMaxMs = backoffMaxMs,
        defaultScanPeriodMs = scanMs,
    ))

    private fun awaitConnected(device: LogixDevice) {
        await("connected ${device.connectionStatus()}") { device.connectionStatus().connected }
    }

    private fun micros(instant: Instant) = instant.epochSecond * 1_000_000 + instant.nano / 1000

    private fun nanos(instant: Instant) = instant.epochSecond * 1_000_000_000 + instant.nano

    private fun assertDecimal(expected: String, value: Any?) {
        val actual = value as? BigDecimal ?: throw AssertionError("expected decimal, was $value")
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected was $actual")
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
