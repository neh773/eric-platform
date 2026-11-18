package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** DAT-035: an ad hoc script query becomes a named query and the call is rewritten. */
class SqlConversionTest {
    @TempDir lateinit var tmp: Path

    @Test fun prepQueryBecomesANamedQueryWithTypedParameters() {
        val script = """
            rows = system.db.runPrepQuery("SELECT name FROM users WHERE age > ? AND city = ? AND note <> '?'", [minAge, 'Oslo'], "plant")
            print(rows)
        """.trimIndent()
        val c = SqlConversion.fromScript("old-users", script, null)
        assertEquals("plant", c.fields["connection"])
        assertEquals("query", c.fields["queryType"])
        assertEquals("SELECT name FROM users WHERE age > :minAge AND city = :p2 AND note <> '?'", c.fields["sql"])
        assertEquals(listOf(mapOf("name" to "minAge", "dataType" to "string"), mapOf("name" to "p2", "dataType" to "string")), c.fields["parameters"])
        assertEquals("rows = system.db.runNamedQuery(\"old-users\", {\"minAge\": minAge, \"p2\": 'Oslo'})\nprint(rows)", c.script)
    }

    @Test fun updateScalarAndLiteralTypes() {
        val u = SqlConversion.fromScript("bump", "system.db.runPrepUpdate('UPDATE t SET n = ? WHERE x = ?', [5, 2.5, ], database='d')", null)
        assertEquals("update", u.fields["queryType"])
        assertEquals(listOf("long", "double"), (u.fields["parameters"] as List<*>).map { (it as Map<*, *>)["dataType"] })
        val s = SqlConversion.fromScript("one", "v = system.db.runScalarPrepQuery(\"SELECT 1\")", "fallback")
        assertEquals("scalar", s.fields["queryType"])
        assertEquals("fallback", s.fields["connection"])
        assertEquals("v = system.db.runNamedQuery(\"one\")", s.script)
    }

    @Test fun refusesWhatItCannotRewrite() {
        assertThrows<QueryRejected> { SqlConversion.fromScript("x", "print(1)", null) }
        assertThrows<QueryRejected> { SqlConversion.fromScript("x", "system.db.runPrepQuery(sqlText, [1], 'd')", null) }
        assertThrows<QueryRejected> { SqlConversion.fromScript("x", "system.db.runPrepQuery('SELECT ?', [1, 2], 'd')", null) }
        assertThrows<QueryRejected> { SqlConversion.fromScript("x", "system.db.runPrepQuery('SELECT 1', [], 'd', tx)", null) }
        assertThrows<QueryRejected> { SqlConversion.fromScript("Bad Name", "system.db.runQuery('SELECT 1', 'd')", null) }
    }

    @Test fun bareSqlConvertsAndTheNamedQueryRuns() {
        DbHarness(tmp).use { h ->
            h.connection("mem")
            h.db.saveQuery("mk", mapOf("connection" to "mem", "queryType" to "update", "sql" to "CREATE TABLE t (n INTEGER, s TEXT)"), 0)
            h.db.execute("mk", emptyMap(), emptySet())
            val ins = SqlConversion.fromSql("ins", "INSERT INTO t(n, s) VALUES (?, ?)", "mem", null)
            assertEquals("update", ins.fields["queryType"])
            h.db.saveQuery("ins", ins.fields, 0)
            h.db.execute("ins", mapOf("p1" to "7", "p2" to "seven"), emptySet())
            val sel = SqlConversion.fromSql("sel", "SELECT s FROM t WHERE n = ?", "mem", "scalar")
            h.db.saveQuery("sel", sel.fields, 0)
            val r = h.db.execute("sel", mapOf("p1" to "7"), emptySet()) as QueryResult.Scalar
            assertEquals("seven", r.value)
            assertTrue(sel.call.startsWith("system.db.runNamedQuery(\"sel\""))
        }
    }
}
