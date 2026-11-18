package org.oip.db

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.db.groups.FakeTags
import org.oip.db.groups.GROUP_KIND
import org.oip.db.groups.GROUP_MODULE
import org.oip.db.groups.PooledConnections
import org.oip.db.groups.TransactionGroups
import java.nio.file.Path

/**
 * Stored procedures and functions on a real PostgreSQL: in, out, and in/out parameters, a return value,
 * a result set, errors, named queries of type `procedure`, and a stored-procedure transaction group that
 * writes its outputs to tags. Skipped when no server is available (see [PostgresServer]).
 */
class StoredProcedurePostgresTest {
    @TempDir lateinit var tmp: Path
    private lateinit var h: DbHarness
    private val sfx = "_" + java.lang.Long.toHexString(System.nanoTime() and 0xffffff)
    private val adjust = "c17_adjust$sfx"
    private val twice = "c17_twice$sfx"
    private val stats = "c17_stats$sfx"
    private val series = "c17_series$sfx"

    @BeforeEach fun setUp() {
        val url = PostgresServer.require()
        h = DbHarness(tmp)
        h.connection(
            "plant", driver = "postgres", url = url,
            password = PasswordUpdate.Set(PostgresServer.PASSWORD), fields = mapOf("username" to PostgresServer.USER),
        )
        sql(
            """CREATE PROCEDURE $adjust(IN line text, INOUT total integer, OUT status text) LANGUAGE plpgsql AS ${'$'}${'$'}
               BEGIN total := total + length(line); status := 'ok:' || line; END ${'$'}${'$'}""",
        )
        sql("CREATE FUNCTION $twice(n bigint) RETURNS bigint LANGUAGE sql AS 'SELECT n * 2'")
        sql(
            """CREATE FUNCTION $stats(line text, OUT lo double precision, INOUT n integer) LANGUAGE plpgsql AS ${'$'}${'$'}
               BEGIN lo := length(line) / 2.0; n := n * 10; END ${'$'}${'$'}""",
        )
        sql("CREATE FUNCTION $series(n integer) RETURNS TABLE(i integer, sq integer) LANGUAGE sql AS 'SELECT g, g * g FROM generate_series(1, n) g'")
    }

    @AfterEach fun tearDown() {
        if (!::h.isInitialized) return
        runCatching { sql("DROP PROCEDURE IF EXISTS $adjust(text, integer, text)") }
        for (f in listOf(twice, stats, series)) runCatching { sql("DROP FUNCTION IF EXISTS $f") }
        h.close()
    }

    private fun sql(statement: String) {
        h.db.withConnection("plant") { c -> c.createStatement().use { it.execute(statement) } }
    }

    private fun p(name: String, mode: ParamMode, type: String) = ProcedureParam(name, mode, type)

    @Test fun procedureInOutAndInOutParameters() {
        val call = ProcedureCall(adjust, RoutineKind.PROCEDURE, listOf(p("line", ParamMode.IN, "string"), p("total", ParamMode.INOUT, "integer"), p("status", ParamMode.OUT, "string")))
        val out = h.db.callProcedure("plant", call, mapOf("line" to "L12", "total" to 10))
        assertEquals(mapOf("total" to 13, "status" to "ok:L12"), out.outputs)
        assertNull(out.returnValue)
    }

    @Test fun functionReturnValueOutputsAndResultSet() {
        val ret = h.db.callProcedure("plant", ProcedureCall(twice, RoutineKind.FUNCTION, listOf(p("n", ParamMode.IN, "long")), returnType = "long"), mapOf("n" to 21))
        assertEquals(42L, ret.returnValue)

        val outs = h.db.callProcedure(
            "plant",
            ProcedureCall(stats, RoutineKind.FUNCTION, listOf(p("line", ParamMode.IN, "string"), p("lo", ParamMode.OUT, "double"), p("n", ParamMode.INOUT, "integer"))),
            mapOf("line" to "abc", "n" to 4),
        )
        // lo is declared double precision, so the numeric expression is cast on assignment.
        assertEquals(mapOf("lo" to 1.5, "n" to 40), outs.outputs)

        val rows = h.db.callProcedure("plant", ProcedureCall(series, RoutineKind.FUNCTION, listOf(p("n", ParamMode.IN, "integer")), maxRows = 3), mapOf("n" to 4))
        assertEquals(listOf("i", "sq"), rows.columns)
        assertEquals(listOf(listOf(1, 1), listOf(2, 4), listOf(3, 9)), rows.rows)
        assertTrue(rows.truncated)
    }

    @Test fun namedProcedureQuery() {
        h.db.saveQuery(
            "adjust",
            mapOf(
                "connection" to "plant", "queryType" to "procedure", "procedure" to adjust, "roles" to listOf("operator"),
                "parameters" to listOf(
                    mapOf("name" to "line", "dataType" to "string"),
                    mapOf("name" to "total", "dataType" to "integer", "mode" to "inout"),
                    mapOf("name" to "status", "dataType" to "string", "mode" to "out"),
                ),
            ),
            0,
        )
        val result = h.db.execute("adjust", mapOf("line" to "AB", "total" to "5"), setOf("operator")) as QueryResult.Procedure
        assertEquals(mapOf("total" to 7, "status" to "ok:AB"), result.outputs)
        assertThrows<QueryForbidden> { h.db.execute("adjust", mapOf("line" to "AB", "total" to 5), setOf("viewer")) }

        h.db.saveQuery(
            "twice",
            mapOf(
                "connection" to "plant", "queryType" to "procedure", "procedure" to twice, "routine" to "function", "returnType" to "long",
                "parameters" to listOf(mapOf("name" to "n", "dataType" to "long")),
            ),
            0,
        )
        assertEquals(10L, (h.db.execute("twice", mapOf("n" to 5), emptySet()) as QueryResult.Procedure).returnValue)
    }

    @Test fun errorsAreReportedAndDoNotFaultTheConnection() {
        val missing = ProcedureCall("c17_missing$sfx", RoutineKind.PROCEDURE, emptyList())
        val e = assertThrows<QueryFailed> { h.db.callProcedure("plant", missing, emptyMap()) }
        assertTrue(e.message!!.contains("c17_missing"), e.message)
        // Wrong declared type: integer where the procedure takes text does not resolve.
        val wrong = ProcedureCall(adjust, RoutineKind.PROCEDURE, listOf(p("line", ParamMode.IN, "integer"), p("total", ParamMode.INOUT, "integer"), p("status", ParamMode.OUT, "string")))
        assertThrows<QueryFailed> { h.db.callProcedure("plant", wrong, mapOf("line" to 1, "total" to 1)) }
        assertEquals("Valid", h.db.connection("plant")["status"])
    }

    /** The query browser's read-only guard on PostgreSQL: a data-modifying CTE behind SELECT is stopped. */
    @Test fun queryBrowserReadsAreReadOnly() {
        val table = "c17_browse$sfx"
        sql("CREATE TABLE $table (id integer)")
        try {
            sql("INSERT INTO $table VALUES (1), (2)")
            val rows = h.db.browse("plant", "SELECT id FROM $table ORDER BY id", 10) as BrowseResult.Rows
            assertEquals(listOf(listOf(1), listOf(2)), rows.rows)
            assertThrows<QueryForbidden> { h.db.browse("plant", "DELETE FROM $table", 10) }
            val blocked = assertThrows<QueryFailed> { h.db.browse("plant", "WITH gone AS (DELETE FROM $table RETURNING id) SELECT * FROM gone", 10) }
            assertTrue(blocked.message!!.contains("read-only", ignoreCase = true), blocked.message)
            assertEquals(2L, (h.db.browse("plant", "SELECT count(*) FROM $table", 10) as BrowseResult.Rows).rows[0][0].toString().toLong())
            // The pooled connection is writable again for other callers.
            sql("INSERT INTO $table VALUES (3)")
            assertEquals("Valid", h.db.connection("plant")["status"])
        } finally {
            sql("DROP TABLE IF EXISTS $table")
        }
    }

    @Test fun procedureGroupMapsOutputsOntoTags() {
        val tags = FakeTags()
        TransactionGroups(h.store, tags, PooledConnections(h.db)).use { groups ->
            groups.attach()
            tags["Line/Name"] = "L7"
            tags["Line/Total"] = 100
            h.store.put(
                Scope.GATEWAY,
                Resource(GROUP_KIND, Metadata("adjust", GROUP_MODULE), mapOf(
                    "connection" to "plant", "groupType" to "storedProcedure",
                    "procedure" to mapOf("name" to adjust),
                    "execution" to mapOf("rateMillis" to 1000),
                    "items" to listOf(
                        mapOf("name" to "line", "source" to "tag", "tag" to "Line/Name", "parameter" to "line", "dataType" to "string"),
                        mapOf("name" to "total", "source" to "tag", "tag" to "Line/Total", "parameter" to "total", "direction" to "inout", "dataType" to "integer"),
                        mapOf("name" to "status", "source" to "tag", "tag" to "Line/Status", "parameter" to "status", "direction" to "out", "dataType" to "string"),
                    ),
                )),
                "test",
            )
            val outcome = groups.runOnce("adjust")
            assertNull(groups.status("adjust")["error"], groups.status("adjust").toString())
            assertEquals(2, outcome.tagWrites)
            assertEquals(102, tags["Line/Total"])
            assertEquals("ok:L7", tags["Line/Status"])
            groups.runOnce("adjust")
            assertEquals(104, tags["Line/Total"])

            h.store.put(
                Scope.GATEWAY,
                Resource(GROUP_KIND, Metadata("twice", GROUP_MODULE), mapOf(
                    "connection" to "plant", "groupType" to "storedProcedure",
                    "procedure" to mapOf("name" to twice, "routine" to "function", "returnType" to "long", "returnTag" to "Line/Twice"),
                    "items" to listOf(mapOf("name" to "n", "source" to "static", "value" to 8, "parameter" to "n", "dataType" to "long")),
                )),
                "test",
            )
            groups.runOnce("twice")
            assertEquals(16L, tags["Line/Twice"])

            h.store.put(
                Scope.GATEWAY,
                Resource(GROUP_KIND, Metadata("broken", GROUP_MODULE), mapOf(
                    "connection" to "plant", "groupType" to "storedProcedure", "procedure" to mapOf("name" to "c17_missing$sfx"),
                )),
                "test",
            )
            groups.runOnce("broken")
            assertEquals("Error", groups.status("broken")["state"])
            assertTrue((groups.status("broken")["error"] as String).contains("c17_missing"))
        }
    }
}
