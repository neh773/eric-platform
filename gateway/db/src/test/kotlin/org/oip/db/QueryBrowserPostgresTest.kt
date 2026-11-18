package org.oip.db

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * The admin query browser on a real PostgreSQL: text that one naive scanner would read as a single statement
 * but the server splits (dollar quotes, escape strings, nested comments) is refused, so a COMMIT cannot end
 * the read-only transaction and a DELETE never runs. Skipped when no server is available (see [PostgresServer]).
 */
class QueryBrowserPostgresTest {
    @TempDir lateinit var tmp: Path
    private lateinit var h: DbHarness
    private lateinit var url: String
    private val table = "c17_guard_" + java.lang.Long.toHexString(System.nanoTime() and 0xffffff)

    @BeforeEach fun setUp() {
        url = PostgresServer.require()
        h = DbHarness(tmp)
        connect(allowWrites = false, version = 0)
        h.db.withConnection("plant") { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE $table (id integer)")
                it.execute("INSERT INTO $table VALUES (1), (2), (3)")
            }
        }
    }

    @AfterEach fun tearDown() {
        if (!::h.isInitialized) return
        runCatching { h.db.withConnection("plant") { c -> c.createStatement().use { it.execute("DROP TABLE IF EXISTS $table") } } }
        h.close()
    }

    private fun connect(allowWrites: Boolean, version: Long) = h.connection(
        "plant", driver = "postgres", url = url, version = version,
        password = if (version == 0L) PasswordUpdate.Set(PostgresServer.PASSWORD) else PasswordUpdate.Unchanged,
        fields = mapOf("username" to PostgresServer.USER, "allowBrowserWrites" to allowWrites),
    )

    private fun count(): Long = DriverManager.getConnection(url, PostgresServer.USER, PostgresServer.PASSWORD).use { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM $table").use { it.next(); it.getLong(1) } }
    }

    @Test fun splitPayloadsNeverReachTheServer() {
        val payloads = listOf(
            "SELECT \$\$'\$\$; COMMIT; DELETE FROM $table; --'",
            "SELECT \$q\$'\$q\$; COMMIT; DELETE FROM $table; --'",
            "SELECT E'\\''; COMMIT; DELETE FROM $table; --'",
            "SELECT 1 /* /* */ ' */ ; COMMIT; DELETE FROM $table; -- '",
        )
        for (sql in payloads) {
            val e = assertThrows<QueryRejected>(sql) { h.db.browse("plant", sql, 10) }
            assertTrue(e.message!!.contains("only one SQL statement"), e.message)
            assertEquals(3L, count(), sql)
        }
        // Even where writes are allowed, the browser owns the transaction.
        connect(allowWrites = true, version = 1)
        for (sql in payloads + listOf("COMMIT", "BEGIN", "SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE")) {
            assertThrows<QueryRejected>(sql) { h.db.browse("plant", sql, 10) }
        }
        assertEquals(3L, count())
    }

    @Test fun quotedTextStillRunsAndReadsCannotWrite() {
        val rows = h.db.browse("plant", "SELECT \$\$a;b\$\$ AS v, E'it\\'s' AS w", 10) as BrowseResult.Rows
        assertEquals(listOf(listOf("a;b", "it's")), rows.rows)
        assertThrows<QueryFailed> { h.db.browse("plant", "WITH gone AS (DELETE FROM $table RETURNING 1) SELECT * FROM gone", 10) }
        assertEquals(3L, count())
    }

    @Test fun sessionAdvisoryLocksDoNotOutliveTheStatement() {
        h.db.browse("plant", "SELECT pg_advisory_lock(917017)", 10)
        DriverManager.getConnection(url, PostgresServer.USER, PostgresServer.PASSWORD).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT pg_try_advisory_lock(917017)").use { rs ->
                    rs.next()
                    assertTrue(rs.getBoolean(1), "the browser's session lock was released")
                }
                st.execute("SELECT pg_advisory_unlock_all()")
            }
        }
    }
}
