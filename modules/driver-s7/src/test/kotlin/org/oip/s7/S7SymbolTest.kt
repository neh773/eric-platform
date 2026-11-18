package org.oip.s7

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Symbol table parser, symbolic address resolver and the full absolute syntax.
 * Doc source: FEATURES DEV-026.
 */
class S7SymbolTest {
    private val csv = """
        # exported from the project, absolute-access blocks only
        block,number,member,type,offset
        MotorData,10,Speed,REAL,0
        MotorData,10,Motor.Running,BOOL,4.0
        MotorData,10,Motor.Count,DINT,6
        MotorData,10,Name,STRING[20],10
        MotorData,10,Total,LREAL,40
        "Recipe",11,Batch,UINT,2
    """.trimIndent()

    @Test fun symbolTableParsesRowsHeaderCommentsAndNestedMembers() {
        val table = S7SymbolTable.parseCsv(csv)
        assertEquals(6, table.size)
        assertEquals("DB10,REAL0", table.resolve("\"MotorData\".Speed"))
        assertEquals("DB10,X4.0", table.resolve("\"MotorData\".Motor.Running"))
        assertEquals("DB10,DI6", table.resolve("DB10.Motor.Count"))
        assertEquals("DB10,STRING10.20", table.resolve("\"motordata\".\"name\""))
        assertEquals("DB11,W2", table.resolve("\"Recipe\".Batch"))
        assertEquals(listOf("Speed", "Motor.Running", "Motor.Count", "Name", "Total", "Batch"), table.symbols().map { it.member })
        assertTrue(S7SymbolTable.parseCsv("  \n").isEmpty)
    }

    @Test fun symbolTableRejectsBadRowsWithTheLineNumber() {
        fun error(text: String) = assertThrows(IllegalArgumentException::class.java) { S7SymbolTable.parseCsv(text) }.message!!
        assertTrue(error("A,1,X,REAL").contains("line 1"))
        assertTrue(error("A,1,X,REAL,0\nA,1,Y,FLOAT,4").contains("line 2: unsupported type 'FLOAT'"))
        assertTrue(error("A,1,X,BOOL,4").contains("needs .bit"))
        assertTrue(error("A,1,X,INT,4.1").contains("only BOOL"))
        assertTrue(error("A,1,X,BOOL,4.8").contains("bit address"))
        assertTrue(error("A,1,X,INT,0\nA,1,x,INT,2").contains("appears twice"))
        assertTrue(error("A,1,X,INT,0\nA,2,Y,INT,0").contains("DB1 on an earlier line"))
        assertTrue(error("A,1,X,INT,0\nB,1,Y,INT,0").contains("DB1 is \"A\""))
        assertTrue(error("A,0,X,INT,0").contains("outside 1..65535"))
        assertTrue(error("A,1,X..Y,INT,0").contains("dotted name"))
        assertThrows(IllegalArgumentException::class.java) { s7DeviceConfig("bad", mapOf("host" to "h", "symbolTable" to "A,1,X,REAL")) }
    }

    @Test fun resolverMapsSymbolsToAbsoluteAddressesAndNamesTheUnknownOne() {
        val table = S7SymbolTable.parseCsv(csv)
        val speed = S7Address.parse("\"MotorData\".Speed", S7Model.S7_1500, table)
        assertEquals("\"MotorData\".Speed", speed.raw)
        assertEquals(S7Area.DB, speed.area)
        assertEquals(10, speed.db)
        assertEquals(S7Type.REAL, speed.type)
        assertEquals(0, speed.start)
        val running = S7Address.parse("DB10.Motor.Running", S7Model.S7_300, table)
        assertEquals(S7Type.BIT, running.type)
        assertEquals(4, running.start)
        assertEquals(0, running.bit)
        assertEquals(20, S7Address.parse("\"MotorData\".Name", S7Model.S7_300, table).stringMax)

        val unknownMember = assertThrows(AddressException::class.java) { S7Address.parse("\"MotorData\".Torque", S7Model.S7_1500, table) }
        assertEquals("unknown symbol '\"MotorData\".Torque': no member 'Torque' in DB10", unknownMember.message)
        val unknownBlock = assertThrows(AddressException::class.java) { S7Address.parse("\"Pump\".Speed", S7Model.S7_1500, table) }
        assertTrue(unknownBlock.message!!.contains("no block \"Pump\""))
        val unknownDb = assertThrows(AddressException::class.java) { S7Address.parse("DB99.Speed", S7Model.S7_1500, table) }
        assertTrue(unknownDb.message!!.contains("no DB99"))
        val noTable = assertThrows(AddressException::class.java) { S7Address.parse("\"MotorData\".Speed", S7Model.S7_1500, S7SymbolTable.EMPTY) }
        assertTrue(noTable.message!!.contains("no block"))
        assertThrows(AddressException::class.java) { S7Address.parse("\"MotorData\".Speed", S7Model.S7_1500) }
        assertThrows(AddressException::class.java) { S7Address.parse("DB10.Speed", S7Model.S7_1500) }
        // LReal is S7-1200/1500 only; the model limit applies to the resolved type.
        val model = assertThrows(AddressException::class.java) { S7Address.parse("\"MotorData\".Total", S7Model.S7_300, table) }
        assertTrue(model.message!!.contains("LREAL is not supported on S7_300"))
        assertThrows(AddressException::class.java) { S7Address.parse("\"MotorData\"Speed", S7Model.S7_1500, table) }
        assertThrows(AddressException::class.java) { S7Address.parse("\"MotorData.Speed", S7Model.S7_1500, table) }
    }

    @Test fun fullAbsoluteSyntaxIncludingDottedDbForms() {
        val table = S7SymbolTable.parseCsv(csv)
        fun p(text: String) = S7Address.parse(text, S7Model.S7_300, table)
        // Dotted DB forms are absolute, never looked up as members.
        assertNull(table.resolve("DB10.DBX4.2"))
        p("DB10.DBX4.2").let { assertEquals(listOf(S7Area.DB, 10, S7Type.BIT, 4, 2), listOf(it.area, it.db, it.type, it.start, it.bit)) }
        p("db10.dbb5").let { assertEquals(listOf(S7Type.BYTE, 5, 1), listOf(it.type, it.start, it.span)) }
        p("DB10.DBW6").let { assertEquals(listOf(S7Type.WORD, 6, 2), listOf(it.type, it.start, it.span)) }
        p("DB10.DBD8").let { assertEquals(listOf(S7Type.DWORD, 8, 4), listOf(it.type, it.start, it.span)) }
        assertThrows(AddressException::class.java) { p("DB10.DBX4") }
        assertThrows(AddressException::class.java) { p("DB10.DBX4.8") }
        assertThrows(AddressException::class.java) { p("DB10.4") }
        // Bits always carry the X (the bare `I0.0` stays rejected, S7LogicTest).
        p("QX1.7").let { assertEquals(listOf(S7Area.Q, S7Type.BIT, 1, 7), listOf(it.area, it.type, it.start, it.bit)) }
        assertThrows(AddressException::class.java) { p("M10.3") }
        assertThrows(AddressException::class.java) { p("DB10,4.0") }
        // Existing forms keep their meaning.
        assertEquals(S7Type.REAL, p("DB10,REAL8").type)
        assertEquals(S7Type.STRING, p("MSTRING20.10").type)
        assertEquals(S7Type.TIMER, p("T3").type)
        assertEquals(S7Type.COUNTER, p("C4").type)
        assertEquals(S7Type.INT, p("MI2").type)
    }
}
