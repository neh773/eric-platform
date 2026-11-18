package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException

/**
 * Call mapping without a database: the SQL each routine shape produces, which parameters are bound and in
 * what order, how outputs come back (by name, then by position), result sets, and errors.
 * A fake JDBC connection reports itself as PostgreSQL and records the statement.
 */
class StoredProcedureMappingTest {
    @TempDir lateinit var tmp: Path

    /** One canned reply: column labels and rows, or no result set at all. */
    class Reply(val columns: List<String>?, val rows: List<List<Any?>> = emptyList(), val error: SQLException? = null)

    class FakeDb(private val reply: Reply, private val product: String = "PostgreSQL") {
        var sql: String? = null
        val binds = sortedMapOf<Int, Any?>()
        var timeout = 0
        var closed = false

        fun connection(): Connection = proxy(Connection::class.java) { name, args ->
            when (name) {
                "getMetaData" -> proxy(DatabaseMetaData::class.java) { m, _ -> if (m == "getDatabaseProductName") product else null }
                "prepareStatement" -> {
                    sql = args!![0] as String
                    statement()
                }
                "close" -> null
                else -> error("unexpected Connection.$name")
            }
        }

        private fun statement(): PreparedStatement = proxy(PreparedStatement::class.java) { name, args ->
            when {
                name.startsWith("set") && name != "setQueryTimeout" -> {
                    binds[args!![0] as Int] = args[1]
                    null
                }
                name == "setQueryTimeout" -> {
                    timeout = args!![0] as Int
                    null
                }
                name == "execute" -> {
                    reply.error?.let { throw it }
                    reply.columns != null
                }
                name == "getResultSet" -> resultSet()
                name == "close" -> {
                    closed = true
                    null
                }
                else -> error("unexpected PreparedStatement.$name")
            }
        }

        private fun resultSet(): ResultSet {
            val columns = reply.columns!!
            var row = -1
            val meta = proxy(ResultSetMetaData::class.java) { name, args ->
                when (name) {
                    "getColumnCount" -> columns.size
                    "getColumnLabel" -> columns[(args!![0] as Int) - 1]
                    else -> error("unexpected ResultSetMetaData.$name")
                }
            }
            return proxy(ResultSet::class.java) { name, args ->
                when (name) {
                    "next" -> ++row < reply.rows.size
                    "getMetaData" -> meta
                    "getObject" -> reply.rows[row][(args!![0] as Int) - 1]
                    "close" -> null
                    else -> error("unexpected ResultSet.$name")
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, handler: (String, Array<Any?>?) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "toString" -> "fake ${type.simpleName}"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    "unwrap", "isWrapperFor" -> error("not a wrapper")
                    else -> handler(method.name, args)
                }
            } as T
    }

    private fun p(name: String, mode: ParamMode, type: String = "integer") = ProcedureParam(name, mode, type)

    @Test fun procedureBindsInAndInOutAndPassesNullForOut() {
        val call = ProcedureCall(
            "plant.adjust", RoutineKind.PROCEDURE,
            listOf(p("Line", ParamMode.IN, "string"), p("total", ParamMode.INOUT), p("status", ParamMode.OUT, "string")),
        )
        assertEquals("CALL plant.adjust(?, ?, NULL)", StoredProcedures.plan(call).sql)
        val db = FakeDb(Reply(listOf("total", "status"), listOf(listOf(12, "ok"))))
        val values = StoredProcedures.coerce(call, mapOf("line" to "L1", "TOTAL" to "10"))
        val out = StoredProcedures.execute(db.connection(), call, values, timeoutSeconds = 7)
        assertEquals("CALL plant.adjust(?, ?, NULL)", db.sql)
        assertEquals(mapOf(1 to "L1", 2 to 10), db.binds.toMap())
        assertEquals(7, db.timeout)
        assertTrue(db.closed)
        assertEquals(mapOf("total" to 12, "status" to "ok"), out.outputs)
        assertNull(out.returnValue)
        assertTrue(out.columns.isEmpty())
    }

    @Test fun outputsMatchByNameIgnoringCaseThenByPosition() {
        val call = ProcedureCall("swap", RoutineKind.PROCEDURE, listOf(p("a", ParamMode.OUT), p("b", ParamMode.OUT)))
        val named = StoredProcedures.execute(FakeDb(Reply(listOf("B", "A"), listOf(listOf(2, 1)))).connection(), call, emptyMap())
        assertEquals(mapOf("a" to 1, "b" to 2), named.outputs)
        val positional = StoredProcedures.execute(FakeDb(Reply(listOf("x", "y"), listOf(listOf(5, 6)))).connection(), call, emptyMap())
        assertEquals(mapOf("a" to 5, "b" to 6), positional.outputs)
        val short = assertThrows<QueryFailed> {
            StoredProcedures.execute(FakeDb(Reply(listOf("x"), listOf(listOf(5)))).connection(), call, emptyMap())
        }
        assertTrue(short.message!!.contains("did not return output b"), short.message)
        // One output named and one not: positions could give `a` the column of `b`, so the call fails.
        val mixed = assertThrows<QueryFailed> {
            StoredProcedures.execute(FakeDb(Reply(listOf("b", "x"), listOf(listOf(2, 9)))).connection(), call, emptyMap())
        }
        assertTrue(mixed.message!!.contains("but not a"), mixed.message)
    }

    @Test fun functionWithOutputsSendsOnlyInputs() {
        val call = ProcedureCall(
            "stats", RoutineKind.FUNCTION,
            listOf(p("lo", ParamMode.OUT, "double"), p("line", ParamMode.IN, "string"), p("n", ParamMode.INOUT)),
        )
        val db = FakeDb(Reply(listOf("lo", "n"), listOf(listOf(1.5, 4))))
        val out = StoredProcedures.execute(db.connection(), call, StoredProcedures.coerce(call, mapOf("line" to "L2", "n" to 3)))
        assertEquals("SELECT * FROM stats(?, ?)", db.sql)
        assertEquals(mapOf(1 to "L2", 2 to 3), db.binds.toMap())
        assertEquals(mapOf("lo" to 1.5, "n" to 4), out.outputs)
    }

    @Test fun functionReturnValue() {
        val call = ProcedureCall("next_batch", RoutineKind.FUNCTION, listOf(p("line", ParamMode.IN, "string")), returnType = "long")
        val db = FakeDb(Reply(listOf("return_value"), listOf(listOf(41L))))
        val out = StoredProcedures.execute(db.connection(), call, StoredProcedures.coerce(call, mapOf("line" to "L1")))
        assertEquals("SELECT next_batch(?) AS return_value", db.sql)
        assertEquals(41L, out.returnValue)
        assertTrue(out.outputs.isEmpty())
    }

    @Test fun setReturningFunctionGivesRowsAndTruncates() {
        val call = ProcedureCall("recent", RoutineKind.FUNCTION, listOf(p("limit_n", ParamMode.IN)), maxRows = 2)
        val db = FakeDb(Reply(listOf("id", "name"), listOf(listOf(1, "a"), listOf(2, "b"), listOf(3, "c"))))
        val out = StoredProcedures.execute(db.connection(), call, StoredProcedures.coerce(call, mapOf("limit_n" to 3)))
        assertEquals("SELECT * FROM recent(?)", db.sql)
        assertEquals(listOf("id", "name"), out.columns)
        assertEquals(listOf(listOf(1, "a"), listOf(2, "b")), out.rows)
        assertTrue(out.truncated)
        val table = QueryResult.Procedure(out.returnValue, out.outputs, out.columns, out.rows, out.truncated).table()
        assertEquals(listOf("id", "name"), table.first)
    }

    @Test fun procedureWithoutOutputsReturnsNothing() {
        val call = ProcedureCall("reset_counts", RoutineKind.PROCEDURE, emptyList())
        val db = FakeDb(Reply(null))
        val out = StoredProcedures.execute(db.connection(), call, emptyMap())
        assertEquals("CALL reset_counts()", db.sql)
        assertTrue(out.outputs.isEmpty() && out.rows.isEmpty())
        assertEquals(listOf("return_value") to listOf(listOf<Any?>(null)), QueryResult.Procedure(null, emptyMap(), emptyList(), emptyList(), false).table())
    }

    @Test fun missingOutputRowIsAnError() {
        val call = ProcedureCall("p", RoutineKind.PROCEDURE, listOf(p("x", ParamMode.OUT)))
        assertThrows<QueryFailed> { StoredProcedures.execute(FakeDb(Reply(null)).connection(), call, emptyMap()) }
        assertThrows<QueryFailed> { StoredProcedures.execute(FakeDb(Reply(listOf("x"))).connection(), call, emptyMap()) }
    }

    @Test fun databaseErrorsReachTheCaller() {
        val call = ProcedureCall("missing", RoutineKind.PROCEDURE, emptyList())
        val db = FakeDb(Reply(null, error = SQLException("procedure missing() does not exist", "42883")))
        val e = assertThrows<SQLException> { StoredProcedures.execute(db.connection(), call, emptyMap()) }
        assertEquals("42883", e.sqlState)
        assertTrue(db.closed)
    }

    @Test fun onlyPostgresCanCallRoutines() {
        val call = ProcedureCall("p", RoutineKind.PROCEDURE, emptyList())
        val sqlite = assertThrows<QueryRejected> { StoredProcedures.execute(FakeDb(Reply(null), "SQLite").connection(), call, emptyMap()) }
        assertEquals("SQLite has no stored procedures", sqlite.message)
        assertThrows<QueryRejected> { StoredProcedures.execute(FakeDb(Reply(null), "Other").connection(), call, emptyMap()) }
    }

    @Test fun parameterValuesAreCoercedAndChecked() {
        val call = ProcedureCall(
            "p", RoutineKind.PROCEDURE,
            listOf(p("n", ParamMode.IN), p("flag", ParamMode.INOUT, "boolean"), p("out1", ParamMode.OUT, "string")),
        )
        assertEquals(mapOf("n" to 5, "flag" to true), StoredProcedures.coerce(call, mapOf("n" to 5.0, "flag" to "true")))
        assertThrows<QueryRejected> { StoredProcedures.coerce(call, mapOf("n" to 1)) } // flag missing
        assertThrows<QueryRejected> { StoredProcedures.coerce(call, mapOf("n" to 1, "flag" to true, "extra" to 1)) }
        assertThrows<QueryRejected> { StoredProcedures.coerce(call, mapOf("n" to 1, "flag" to true, "out1" to "x")) }
        assertThrows<QueryRejected> { StoredProcedures.coerce(call, mapOf("n" to "1; DROP TABLE x", "flag" to true)) }
        assertThrows<QueryRejected> { StoredProcedures.coerce(call, mapOf("n" to 1, "N" to 2, "flag" to true)) }
    }

    @Test fun callShapeIsValidated() {
        assertThrows<QueryRejected> { ProcedureCall("bad name", RoutineKind.PROCEDURE, emptyList()) }
        assertThrows<QueryRejected> { ProcedureCall("x; DROP TABLE y", RoutineKind.PROCEDURE, emptyList()) }
        assertThrows<QueryRejected> { ProcedureCall("a.b.c", RoutineKind.PROCEDURE, emptyList()) }
        assertThrows<QueryRejected> { ProcedureCall("p", RoutineKind.PROCEDURE, emptyList(), returnType = "integer") }
        assertThrows<QueryRejected> { ProcedureCall("f", RoutineKind.FUNCTION, listOf(p("o", ParamMode.OUT)), returnType = "integer") }
        assertThrows<QueryRejected> { ProcedureCall("f", RoutineKind.FUNCTION, listOf(p("a", ParamMode.IN), p("A", ParamMode.IN))) }
        assertThrows<QueryRejected> { ProcedureCall("f", RoutineKind.FUNCTION, listOf(p("a", ParamMode.IN, "blob"))) }
        assertThrows<QueryRejected> { ParamMode.of("both") }
        assertFalse(ParamMode.OUT.sends)
        assertTrue(ParamMode.INOUT.sends && ParamMode.INOUT.receives)
    }

    @Test fun sqliteNamedProcedureQueryIsRefusedCleanly() {
        DbHarness(tmp).use { h ->
            h.connection("mem")
            h.db.saveQuery(
                "adjust",
                mapOf(
                    "connection" to "mem", "queryType" to "procedure", "procedure" to "adjust",
                    "parameters" to listOf(mapOf("name" to "n", "dataType" to "integer", "mode" to "inout")),
                ),
                0,
            )
            val view = h.db.query("adjust")
            assertEquals("adjust", view["procedure"])
            assertEquals("procedure", view["routine"])
            assertFalse(view.containsKey("sql"))
            assertEquals(listOf(mapOf("name" to "n", "dataType" to "integer", "mode" to "inout")), view["parameters"])
            val e = assertThrows<QueryRejected> { h.db.execute("adjust", mapOf("n" to 1), emptySet()) }
            assertEquals("SQLite has no stored procedures", e.message)
            assertEquals("Valid", h.db.connection("mem")["status"], "a refused call does not fault the connection")
        }
    }

    @Test fun namedProcedureQueryFieldsAreValidated() {
        DbHarness(tmp).use { h ->
            h.connection("mem")
            fun save(fields: Map<String, Any?>) = h.db.saveQuery("q", mapOf("connection" to "mem") + fields, 0)
            assertThrows<QueryRejected> { save(mapOf("queryType" to "procedure")) } // no procedure name
            assertThrows<QueryRejected> { save(mapOf("queryType" to "procedure", "procedure" to "p", "sql" to "SELECT 1")) }
            assertThrows<QueryRejected> { save(mapOf("queryType" to "procedure", "procedure" to "p", "cacheEnabled" to true, "cacheTtlMillis" to 10)) }
            assertThrows<QueryRejected> { save(mapOf("queryType" to "procedure", "procedure" to "p", "returnType" to "integer")) }
            assertThrows<QueryRejected> { save(mapOf("queryType" to "query", "sql" to "SELECT 1", "procedure" to "p")) }
            assertThrows<QueryRejected> {
                save(mapOf("queryType" to "query", "sql" to "SELECT :a", "parameters" to listOf(mapOf("name" to "a", "dataType" to "integer", "mode" to "out"))))
            }
            assertThrows<QueryRejected> { save(mapOf("queryType" to "query")) } // sql still required for SQL queries
            save(mapOf("queryType" to "procedure", "procedure" to "f", "routine" to "function", "returnType" to "integer"))
            assertEquals("integer", h.db.query("q")["returnType"])
        }
    }
}
