package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The admin query browser on SQLite: reads run, writes are refused unless the connection allows them, and
 * a statement that slips past the keyword check still cannot change data.
 */
class QueryBrowserTest {
    @TempDir lateinit var tmp: Path

    private fun DbHarness.setup(allowWrites: Boolean = false) {
        connection("plant", url = "jdbc:sqlite:plant.db", fields = mapOf("allowBrowserWrites" to allowWrites))
        db.withConnection("plant") { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS line (id INTEGER PRIMARY KEY, name TEXT)")
                it.execute("DELETE FROM line")
                it.execute("INSERT INTO line(name) VALUES ('a'), ('b'), ('c')")
            }
        }
    }

    private fun DbHarness.count(): Int = (db.browse("plant", "SELECT COUNT(*) AS n FROM line", 10) as BrowseResult.Rows).rows[0][0] as Int

    @Test fun readsReturnRowsAndTruncate() {
        DbHarness(tmp).use { h ->
            h.setup()
            val all = h.db.browse("plant", "  -- latest first\n SELECT id, name FROM line ORDER BY id DESC;", 10) as BrowseResult.Rows
            assertEquals(listOf("id", "name"), all.columns)
            assertEquals(listOf(listOf(3, "c"), listOf(2, "b"), listOf(1, "a")), all.rows)
            assertFalse(all.truncated)
            val two = h.db.browse("plant", "WITH x AS (SELECT name FROM line) SELECT * FROM x", 2) as BrowseResult.Rows
            assertEquals(2, two.rows.size)
            assertTrue(two.truncated)
            assertEquals(listOf(listOf(1)), (h.db.browse("plant", "VALUES (1)", 5) as BrowseResult.Rows).rows)
        }
    }

    @Test fun writesAreRefusedUnlessTheConnectionAllowsThem() {
        DbHarness(tmp).use { h ->
            h.setup()
            for (sql in listOf("DELETE FROM line", "UPDATE line SET name = 'x'", "DROP TABLE line", "PRAGMA query_only = 0", "INSERT INTO line(name) VALUES ('d')")) {
                val e = assertThrows<QueryForbidden>(sql) { h.db.browse("plant", sql, 10) }
                assertTrue(e.message!!.contains("does not allow write statements"), e.message)
            }
            // Starts like a read but writes: the read-only guard stops it, and nothing changes.
            assertThrows<QueryFailed> { h.db.browse("plant", "WITH gone AS (SELECT 1) DELETE FROM line", 10) }
            assertEquals(3, h.count())
            // The connection is usable afterwards and no longer read-only for other callers.
            h.db.withConnection("plant") { c -> c.createStatement().use { it.execute("INSERT INTO line(name) VALUES ('d')") } }
            assertEquals(4, h.count())
        }
    }

    @Test fun allowedWritesReportRowsAffected() {
        DbHarness(tmp).use { h ->
            h.setup(allowWrites = true)
            assertEquals(true, h.db.connection("plant")["allowBrowserWrites"])
            val result = h.db.browse("plant", "UPDATE line SET name = 'z' WHERE id < 3", 10)
            assertEquals(BrowseResult.Update(2), result)
            assertEquals(listOf(listOf("z")), (h.db.browse("plant", "SELECT name FROM line WHERE id = 1", 10) as BrowseResult.Rows).rows)
        }
    }

    @Test fun oneStatementOnlyAndErrorsAreRedacted() {
        DbHarness(tmp).use { h ->
            h.setup(allowWrites = true)
            assertThrows<QueryRejected> { h.db.browse("plant", "SELECT 1; DELETE FROM line", 10) }
            assertThrows<QueryRejected> { h.db.browse("plant", "SELECT 1", 0) }
            assertThrows<QueryRejected> { h.db.browse("plant", "SELECT 1", DatabaseService.MAX_BROWSE_ROWS + 1) }
            assertThrows<QueryNotFound> { h.db.browse("nope", "SELECT 1", 10) }
            val bad = assertThrows<QueryFailed> { h.db.browse("plant", "SELECT missing_column FROM line", 10) }
            assertTrue(bad.message!!.contains("missing_column"), bad.message)
            assertEquals("Valid", h.db.connection("plant")["status"], "a bad statement does not fault the connection")
            assertEquals(3, h.count())
        }
    }

    @Test fun readKeywordClassification() {
        for (sql in listOf("select 1", " (SELECT 1)", "/* c */ WITH a AS (SELECT 1) SELECT * FROM a", "EXPLAIN SELECT 1", "values (1)", "TABLE line", "SHOW search_path")) {
            assertTrue(SqlText.isRead(sql, SqlDialect.POSTGRES) && SqlText.isRead(sql, SqlDialect.SQLITE), sql)
        }
        for (sql in listOf("DELETE FROM x", "insert into x values (1)", "PRAGMA query_only=0", "CALL p()", "-- select\nDROP TABLE x", "selectx")) {
            assertFalse(SqlText.isRead(sql, SqlDialect.POSTGRES) || SqlText.isRead(sql, SqlDialect.SQLITE), sql)
        }
        assertEquals("SELECT '?' , x::text, :name", SqlText.single("SELECT '?' , x::text, :name ;", SqlDialect.POSTGRES))
    }

    // Each of these is one statement to a scanner that only knows '…', "…" and backticks, but several to
    // PostgreSQL: the COMMIT would end the read-only transaction and the DELETE would stick.
    private val pgSplitPayloads = listOf(
        "SELECT \$\$'\$\$; COMMIT; DELETE FROM line; --'",
        "SELECT \$tag\$'\$tag\$; COMMIT; DELETE FROM line; --'",
        "SELECT E'\\''; COMMIT; DELETE FROM line; --'",
        "SELECT e'\\\\\\''; COMMIT; DELETE FROM line; --'",
        "SELECT 1 /* /* */ ' */ ; COMMIT; DELETE FROM line; -- '",
        "SELECT 1 `; COMMIT; DELETE FROM line; `",
    )

    @Test fun postgresQuotingIsUnderstood() {
        val pg = SqlDialect.POSTGRES
        for (sql in pgSplitPayloads) {
            val e = assertThrows<QueryRejected>(sql) { SqlText.single(sql, pg) }
            assertTrue(e.message!!.contains("only one SQL statement"), "$sql: ${e.message}")
            assertThrows<QueryRejected>(sql) { SqlText.prepare(sql, emptySet(), pg) }
            // The driver's own parser agrees that this is more than one statement.
            assertThrows<QueryRejected>(sql) { PgStatements.requireSingle(sql, standardConformingStrings = true) }
        }
        // With standard_conforming_strings off, every string takes backslash escapes.
        val off = SqlDialect(postgres = true, standardConformingStrings = false)
        val legacy = "SELECT '\\''; COMMIT; DELETE FROM line; --'"
        assertEquals(legacy, SqlText.single(legacy, pg), "one string under the default setting")
        assertThrows<QueryRejected> { SqlText.single(legacy, off) }
        assertThrows<QueryRejected> { PgStatements.requireSingle(legacy, standardConformingStrings = false) }

        // Statements that really are one stay accepted, and quoted text is not scanned for parameters.
        for (sql in listOf(
            "SELECT \$\$a;b\$\$", "SELECT \$t\$ it's \$\$ ; \$t\$", "SELECT E'it\\'s; fine'", "SELECT 'a\\'",
            "SELECT 1 /* outer /* inner; */ still; */", "SELECT a\$\$b FROM line", "SELECT \$1",
        )) {
            assertEquals(sql, SqlText.single(sql, pg), sql)
            PgStatements.requireSingle(sql, standardConformingStrings = true)
        }
        val prepared = SqlText.prepare("SELECT \$\$:x;\$\$, E':y\\'', :n", setOf("n"), pg)
        assertEquals("SELECT \$\$:x;\$\$, E':y\\'', ?", prepared.sql)
        assertEquals(listOf("n"), prepared.names)
        assertThrows<QueryRejected> { SqlText.single("SELECT \$\$ open", pg) }
        assertThrows<QueryRejected> { SqlText.single("SELECT 1 /* /* */", pg) }

        // A nested comment hides what follows it from the read check too.
        assertFalse(SqlText.isRead("/* /* */ SELECT */ DELETE FROM line", pg))
        assertTrue(SqlText.isRead("/* /* */ DELETE */ SELECT 1", pg))
    }

    @Test fun sqliteQuotingIsUnderstood() {
        val lite = SqlDialect.SQLITE
        assertEquals("SELECT [a;b], `c;d`", SqlText.single("SELECT [a;b], `c;d`;", lite))
        assertThrows<QueryRejected> { SqlText.single("SELECT [a'] ; DELETE FROM line; SELECT [']", lite) }
        assertThrows<QueryRejected> { SqlText.single("SELECT 'a\\'; DELETE FROM line; --'", lite) }
    }

    @Test fun transactionStatementsAreRefused() {
        for (sql in listOf(
            "BEGIN", "start transaction", "COMMIT", "end", "ROLLBACK", "abort", "SAVEPOINT a", "release a",
            "PREPARE TRANSACTION 'x'", "set transaction read write", "SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE",
            "/* c */ commit",
        )) {
            assertTrue(SqlText.isTransactionControl(sql, SqlDialect.POSTGRES), sql)
            assertThrows<QueryRejected>(sql) { PgStatements.requireSingle(sql, standardConformingStrings = true) }
        }
        for (sql in listOf("SELECT 1", "SET search_path TO public", "PREPARE q AS SELECT 1", "beginning", "ended")) {
            assertFalse(SqlText.isTransactionControl(sql, SqlDialect.POSTGRES), sql)
        }
        DbHarness(tmp).use { h ->
            h.setup(allowWrites = true)
            for (sql in listOf("BEGIN", "COMMIT", "SAVEPOINT a")) {
                val e = assertThrows<QueryRejected>(sql) { h.db.browse("plant", sql, 10) }
                assertTrue(e.message!!.contains("transaction statements"), e.message)
            }
            assertEquals(BrowseResult.Update(1), h.db.browse("plant", "DELETE FROM line WHERE id = 1", 10))
            assertEquals(2, h.count())
        }
    }

    @Test fun writeFlagIsTheOneOfTheConnectionUsed() {
        DbHarness(tmp).use { h ->
            h.connection("spare", url = "jdbc:sqlite:\${data}/spare.db")
            h.db.withConnection("spare") { c ->
                c.createStatement().use {
                    it.execute("CREATE TABLE line (id INTEGER PRIMARY KEY, name TEXT)")
                    it.execute("INSERT INTO line(name) VALUES ('a'), ('b')")
                }
            }
            // The primary allows writes but is down; the failover that actually runs the statement does not.
            h.connection(
                "down", driver = "postgres", url = "jdbc:postgresql://127.0.0.1:1/none",
                fields = mapOf(
                    "maxWaitMillis" to 250, "validationTimeoutMillis" to 250, "allowBrowserWrites" to true,
                    "failoverConnection" to "spare", "failoverMode" to "standard",
                ),
            )
            val e = assertThrows<QueryForbidden> { h.db.browse("down", "DELETE FROM line", 10) }
            assertTrue(e.message!!.contains("failover 'spare'"), e.message)
            // The single SQLite connection went back to the pool: the next statement gets it at once.
            val rows = h.db.browse("down", "SELECT COUNT(*) FROM line", 10) as BrowseResult.Rows
            assertEquals(listOf(listOf(2)), rows.rows)
            // And the other way round: a primary without the flag, a failover with it.
            h.connection("spare", url = "jdbc:sqlite:\${data}/spare.db", fields = mapOf("allowBrowserWrites" to true), version = 1)
            h.connection(
                "down2", driver = "postgres", url = "jdbc:postgresql://127.0.0.1:1/none",
                fields = mapOf("maxWaitMillis" to 250, "validationTimeoutMillis" to 250, "failoverConnection" to "spare", "failoverMode" to "standard"),
            )
            assertEquals(BrowseResult.Update(1), h.db.browse("down2", "DELETE FROM line WHERE id = 1", 10))
        }
    }

    @Test fun passwordIsNeverInTheConnectionView() {
        DbHarness(tmp).use { h ->
            h.connection("plant", url = "jdbc:sqlite:plant.db", password = PasswordUpdate.Set("Sup3r-Secret-Value"))
            val view = h.db.connection("plant").toString() + h.db.connections().toString()
            assertFalse(view.contains("Sup3r-Secret-Value"))
            assertFalse(view.contains("secret://"))
            assertEquals(true, h.db.connection("plant")["passwordSet"])
        }
    }
}
