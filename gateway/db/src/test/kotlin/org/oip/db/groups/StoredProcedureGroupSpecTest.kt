package org.oip.db.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.db.ParamMode
import org.oip.db.RoutineKind
import java.nio.file.Path

/**
 * The `storedProcedure` group type: parsing, validation, and the call it builds. Running one needs
 * PostgreSQL (StoredProcedurePostgresTest); on SQLite the run fails cleanly and no tag is written.
 */
class StoredProcedureGroupSpecTest {
    @TempDir lateinit var tmp: Path

    private fun item(name: String, direction: String = "in", extra: Map<String, Any?> = emptyMap()) =
        mapOf("name" to name, "source" to "tag", "tag" to "Line/$name", "parameter" to name, "direction" to direction, "dataType" to "integer") + extra

    private fun group(items: List<Map<String, Any?>>, procedure: Map<String, Any?> = mapOf("name" to "plant.adjust"), extra: Map<String, Any?> = emptyMap()) =
        mapOf("connection" to "plant", "groupType" to "storedProcedure", "procedure" to procedure, "items" to items) + extra

    @Test fun parsesIntoACall() {
        val spec = GroupSpecs.parse(group(listOf(item("a"), item("b", "inout"), item("c", "out"))))
        assertEquals(GroupType.PROCEDURE, spec.groupType)
        assertEquals("", spec.table)
        val call = GroupSpecs.toCall(spec)
        assertEquals("plant.adjust", call.routine)
        assertEquals(RoutineKind.PROCEDURE, call.kind)
        assertEquals(listOf(ParamMode.IN, ParamMode.INOUT, ParamMode.OUT), call.parameters.map { it.mode })
        val fn = GroupSpecs.parse(group(emptyList(), mapOf("name" to "f", "routine" to "function", "returnType" to "double", "returnTag" to "Line/R")))
        assertEquals("Line/R", fn.procedure!!.returnTag)
    }

    @Test fun rejectsInvalidProcedureGroups() {
        fun bad(fields: Map<String, Any?>, text: String) {
            val e = assertThrows<GroupRejected> { GroupSpecs.parse(fields) }
            assertTrue(e.message!!.contains(text), "${e.message} should mention $text")
        }
        bad(mapOf("connection" to "plant", "groupType" to "storedProcedure"), "procedure.name")
        bad(group(emptyList(), extra = mapOf("table" to "t")), "no table")
        bad(group(listOf(item("a", extra = mapOf("column" to "a")))), "not a column")
        bad(group(listOf(mapOf("name" to "a", "source" to "tag", "tag" to "T", "dataType" to "integer"))), "needs a parameter")
        bad(group(listOf(mapOf("name" to "a", "source" to "tag", "tag" to "T", "parameter" to "a"))), "needs a dataType")
        bad(group(listOf(mapOf("name" to "a", "source" to "static", "value" to 1, "parameter" to "a", "direction" to "out", "dataType" to "integer"))), "only a tag item")
        bad(group(emptyList(), mapOf("name" to "x; DROP TABLE y")), "identifier")
        bad(group(emptyList(), mapOf("name" to "p", "returnType" to "integer", "returnTag" to "T")), "no return value")
        bad(group(emptyList(), mapOf("name" to "f", "routine" to "function", "returnTag" to "T")), "needs procedure.returnType")
        bad(group(listOf(item("a"), item("A"))), "duplicate")
        bad(group(emptyList(), extra = mapOf("timestampColumn" to "ts")), "no table columns")
        // Procedure-only fields on other group types.
        bad(
            mapOf("connection" to "plant", "table" to "t", "groupType" to "historical", "procedure" to mapOf("name" to "p"),
                "items" to listOf(mapOf("name" to "a", "source" to "tag", "tag" to "T", "column" to "a"))),
            "storedProcedure",
        )
        bad(
            mapOf("connection" to "plant", "table" to "t", "groupType" to "historical",
                "items" to listOf(mapOf("name" to "a", "source" to "tag", "tag" to "T", "column" to "a", "parameter" to "a"))),
            "only for storedProcedure",
        )
        bad(mapOf("connection" to "plant", "groupType" to "historical"), "table is required")
    }

    @Test fun sqliteRunFailsWithoutWritingTags() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            g.tags["Line/a"] = 1
            g.put("proc", group(listOf(item("a"), item("b", "out"))) + mapOf("execution" to mapOf("rateMillis" to 1000)))
            assertEquals("Stopped", g.groups.status("proc")["state"], g.groups.status("proc").toString())
            g.run("proc")
            assertEquals("Error", g.groups.status("proc")["state"])
            assertEquals("SQLite has no stored procedures", g.groups.status("proc")["error"])
            assertTrue(g.tags.writes.isEmpty())
        }
    }
}
