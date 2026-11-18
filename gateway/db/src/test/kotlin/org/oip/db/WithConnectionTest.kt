package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException

/** [DatabaseService.withConnection]: pooled borrow and return, clear errors, and no password in error text. */
class WithConnectionTest {
    @TempDir lateinit var tmp: Path

    private val password = "Unique-db-pass-7c1e"

    @Test fun borrowsFromThePoolAndReturnsTheConnection() {
        DbHarness(tmp).use { h ->
            // SQLite pools hold exactly one connection, so a connection that was not returned would make the
            // next borrow wait out maxWaitMillis and fail.
            h.connection("mem", fields = mapOf("defaultConnection" to true, "maxWaitMillis" to 250, "validationTimeoutMillis" to 250))
            h.db.withConnection("mem") { c -> c.createStatement().use { it.execute("CREATE TABLE t (n INTEGER)") } }
            for (i in 1..5) {
                h.db.withConnection("mem") { c -> c.createStatement().use { it.execute("INSERT INTO t VALUES ($i)") } }
            }
            val active = h.db.withConnection("mem") { _ -> h.db.connection("mem")["activeQueries"] }
            assertEquals(1, active, "a borrowed connection counts as active work while the block runs")
            assertEquals(0, h.db.connection("mem")["activeQueries"])
            assertEquals(0, h.db.connection("mem")["activeConnections"], "returned to the pool")

            // The same pool a named query uses: rows written through withConnection are visible to it.
            h.db.saveQuery("count", mapOf("connection" to "mem", "queryType" to "scalar", "sql" to "SELECT COUNT(*) FROM t"), 0)
            assertEquals(5, (h.db.execute("count", emptyMap(), emptySet()) as QueryResult.Scalar).value.let { (it as Number).toInt() })

            // The default connection resolves like a named query's <default>.
            val viaDefault = h.db.withConnection(CONNECTION_DEFAULT) { c ->
                c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM t").use { rs -> rs.next(); rs.getInt(1) } }
            }
            assertEquals(5, viaDefault)

            // A failing statement is reported but does not fault a connection that is still valid.
            val failed = assertThrows<QueryFailed> {
                h.db.withConnection("mem") { c -> c.createStatement().use { it.execute("SELECT nope FROM missing") } }
            }
            assertTrue(failed.message!!.contains("missing"), failed.message)
            assertEquals("Valid", h.db.connection("mem")["status"])
            h.db.withConnection("mem") { c -> c.createStatement().use { it.execute("SELECT 1") } }
        }
    }

    @Test fun unknownOrDisabledConnectionGivesAClearError() {
        DbHarness(tmp).use { h ->
            val missing = assertThrows<QueryNotFound> { h.db.withConnection("absent") { _ -> 1 } }
            assertEquals("database connection 'absent' not found", missing.message)
            val noDefault = assertThrows<QueryRejected> { h.db.withConnection(CONNECTION_DEFAULT) { _ -> 1 } }
            assertEquals("no default database connection", noDefault.message)
            h.connection("off", fields = mapOf("enabled" to false))
            val disabled = assertThrows<QueryUnavailable> { h.db.withConnection("off") { _ -> 1 } }
            assertEquals("database connection 'off' is disabled", disabled.message)
        }
    }

    @Test fun passwordNeverAppearsInErrorText() {
        DbHarness(tmp).use { h ->
            // Unreachable server: the borrow fails.
            h.connection(
                "down",
                driver = "postgres",
                url = "jdbc:postgresql://127.0.0.1:1/none",
                password = PasswordUpdate.Set(password),
                fields = mapOf("maxWaitMillis" to 250, "validationTimeoutMillis" to 250),
            )
            val unavailable = assertThrows<QueryUnavailable> { h.db.withConnection("down") { _ -> 1 } }
            assertFalse(unavailable.message.orEmpty().contains(password), unavailable.message)
            assertEquals(null, unavailable.cause)

            // A driver error that echoes the password is redacted, and the original exception is not attached.
            h.connection("mem", password = PasswordUpdate.Set(password))
            assertEquals("Valid", h.db.connection("mem")["status"])
            val failed = assertThrows<QueryFailed> {
                h.db.withConnection("mem") { _ -> throw SQLException("login failed for password $password") }
            }
            assertFalse(failed.message.orEmpty().contains(password), failed.message)
            assertTrue(failed.message.orEmpty().contains("[redacted]"), failed.message)
            assertEquals(null, failed.cause)
        }
    }
}
