package org.oip.plc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.tags.DataType
import org.oip.tags.UdtMemberShape

/** 0.1.1: Structured Text structures bound to gateway UDT instances (two FB calls, eight stations). */
class UdtLogicTest {
    private val source = javaClass.getResource("/plc/FB_CentralInhibit.st")!!.readText()

    private val stationMembers = listOf(
        "Level" to DataType.DOUBLE, "CommOk" to DataType.BOOLEAN, "PumpFault" to DataType.BOOLEAN,
        "InhibitCmd" to DataType.BOOLEAN, "OverrideActive" to DataType.BOOLEAN, "AlmHH" to DataType.BOOLEAN,
        "AlmCommLoss" to DataType.BOOLEAN, "AlmPumpFault" to DataType.BOOLEAN, "AlmCannotRelieve" to DataType.BOOLEAN,
        "AlmCannotEvacuate" to DataType.BOOLEAN, "AlmPumpIntoOverflow" to DataType.BOOLEAN, "Name" to DataType.STRING,
    ).map { UdtMemberShape(it.first, it.second, null) }

    private val stations = listOf("CLS-1", "OS-1A", "OS-1B", "OS-1C", "CLS-2", "OS-2A", "OS-2B", "OS-2C")

    private val catalog = object : UdtCatalog {
        override fun types() = listOf("Types/LiftStation_Interlock", "Types/Pump", "Types/Station2")
        override fun members(typeId: String) = when (typeId.lowercase()) {
            "types/liftstation_interlock" -> stationMembers
            "types/pump" -> listOf(UdtMemberShape("Running", DataType.BOOLEAN, null), UdtMemberShape("Speed Pct", DataType.FLOAT, null))
            "types/station2" -> listOf(UdtMemberShape("Level", DataType.DOUBLE, null), UdtMemberShape("P1", DataType.STRING, "Types/Pump"))
            else -> null
        }
        override fun instanceType(path: String) = when {
            stations.any { path.endsWith("/$it") } -> "Types/LiftStation_Interlock"
            path.endsWith("/S2") -> "Types/Station2"
            else -> null
        }
        override fun instances() = stations.map { "[default]MiamiDade/$it" to "Types/LiftStation_Interlock" }
    }

    private val bindings = listOf("Cls1", "Os1A", "Os1B", "Os1C", "Cls2", "Os2A", "Os2B", "Os2C").zip(stations)
        .map { (v, s) -> UdtBinding(v, "[default]MiamiDade/$s") }

    @Test fun parsesStructsPousAndInOutParameters() {
        val parsed = StSource.parse(source)
        assertEquals(listOf("ST_LiftStation"), parsed.structs.map { it.name })
        assertEquals(11, parsed.struct("st_liftstation")!!.fields.size)
        val fb = parsed.pou("FB_CentralInhibit")!!
        assertEquals("FUNCTION_BLOCK", fb.kind)
        assertEquals(listOf("Central", "OS1", "OS2", "OS3"), fb.vars.filter { it.section == "VAR_IN_OUT" }.map { it.name })
        val program = parsed.pou("CollectionInterlock")!!
        assertEquals("ST_LiftStation", program.variable("Os2C")!!.type)
        assertEquals("FB_CentralInhibit", program.variable("Grp2")!!.type)
    }

    @Test fun writesFollowFunctionBlockInOutParameters() {
        val parsed = StSource.parse(source)
        val program = parsed.pou("CollectionInterlock")!!
        val central = StSource.writes(parsed, program, "Cls1")
        assertTrue("almhh" in central && "inhibitcmd" in central && "almpumpintooverflow" in central, central.toString())
        assertFalse("level" in central || "commok" in central || "pumpfault" in central, central.toString())
        val outstation = StSource.writes(parsed, program, "Os2B")
        assertTrue("inhibitcmd" in outstation && "overrideactive" in outstation, outstation.toString())
        assertFalse("level" in outstation)
    }

    @Test fun eightStationsBindWithGeneratedGlueOnExistingLines() {
        val prepared = UdtLogic.prepare(source, "CollectionInterlock", bindings, listOf("%IX0.0", "%QX1.0"), catalog)
        assertEquals(source.lines().size, prepared.source.lines().size, "glue must not add lines")
        assertEquals(8, prepared.plans.size)
        prepared.plans.forEach { assertTrue(it.problems.isEmpty(), it.problems.toString()) }
        // 3 inputs (Level, CommOk, PumpFault) and 8 written members per station.
        assertEquals(8 * 3, prepared.inputs.size)
        assertEquals(8 * 8, prepared.outputs.size)
        val level = prepared.inputs.first { it["name"] == "Cls1.Level" }
        assertEquals("[default]MiamiDade/CLS-1/Level", level["tag"])
        assertEquals("LREAL", level["type"])
        assertEquals("Cls1", level["udt"])
        val inhibit = prepared.outputs.first { it["name"] == "Os1A.InhibitCmd" }
        assertTrue((inhibit["address"] as String).startsWith("%QX"))
        val majors = (prepared.inputs + prepared.outputs).map { UdtLogic.major(it["address"] as String)!! }
        assertEquals(majors.size, majors.toSet().size, "every generated address is unique")
        assertTrue(majors.min() >= 1000)
        assertTrue("Cls1.Level := ERICUDT_Cls1_Level;" in prepared.source)
        assertTrue("ERICUDT_Os2C_InhibitCmd := Os2C.InhibitCmd;" in prepared.source)
        // Members the scan engine cannot move are reported, not bound.
        assertTrue(prepared.plans.first().members.none { it.member == "Name" })
    }

    @Test fun directionOverrideAndUnknownVariable() {
        val prepared = UdtLogic.prepare(
            source, null,
            listOf(UdtBinding("Cls1", "[default]MiamiDade/CLS-1", mapOf("AlmHH" to "none", "Level" to "out")), UdtBinding("Nope", "[default]MiamiDade/CLS-2")),
            emptyList(), catalog,
        )
        val cls1 = prepared.plans.first { it.variable == "Cls1" }
        assertEquals("none", cls1.members.first { it.member == "AlmHH" }.direction)
        assertNull(cls1.members.first { it.member == "AlmHH" }.address)
        assertEquals("out", cls1.members.first { it.member == "Level" }.direction)
        assertEquals("in", cls1.members.first { it.member == "Level" }.auto)
        assertTrue(prepared.plans.first { it.variable == "Nope" }.problems.single().contains("not declared"))
    }

    @Test fun gatewayUdtTypesAreAddedWhenTheProgramUsesThem() {
        val program = """
            PROGRAM Lift
            VAR
                S : Station2;
                L : LiftStation_Interlock;
            END_VAR
                S.P1.Running := S.Level > 50.0;
            END_PROGRAM
        """.trimIndent()
        val prepared = UdtLogic.prepare(program, "Lift", listOf(UdtBinding("S", "[default]Area/S2")), emptyList(), catalog)
        assertTrue(prepared.source.contains("TYPE Pump : STRUCT Running : BOOL; Speed_Pct : REAL; END_STRUCT; END_TYPE"), prepared.source)
        assertTrue(prepared.source.indexOf("TYPE Pump") < prepared.source.indexOf("TYPE Station2"), "nested type first")
        assertTrue(prepared.source.contains("TYPE LiftStation_Interlock"))
        assertEquals(setOf("Station2", "LiftStation_Interlock"), prepared.addedTypes.keys)
        val s = prepared.plans.single()
        val running = s.members.first { it.member == "P1/Running" }
        assertEquals("out", running.direction)
        assertEquals("[default]Area/S2/P1/Running", running.tag)
        assertEquals("in", s.members.first { it.member == "Level" }.direction)
        val vars = UdtLogic.structVariables(program, "Lift", catalog)
        assertEquals(listOf("S", "L"), vars.map { it.variable })
        assertNotNull(vars.first().udtType)
    }

    @Test fun commentsAndStringsDoNotConfuseTheReader() {
        val text = "(* PROGRAM Fake VAR x : BOOL; END_VAR *)\nPROGRAM Real // VAR y : INT; END_VAR\nVAR\n  a : BOOL; (* b : INT; *)\nEND_VAR\na := TRUE;\nEND_PROGRAM\n"
        val parsed = StSource.parse(text)
        assertEquals(listOf("Real"), parsed.pous.map { it.name })
        assertEquals(listOf("a"), parsed.pous.single().vars.map { it.name })
        assertEquals(text.length, StSource.mask(text).length)
    }
}
