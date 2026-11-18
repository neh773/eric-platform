package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.config.ValidationException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp

/**
 * SQLite coverage for value-parameter binding, caching, roles, and type coercion.
 */
class NamedQuerySqliteTest {
    @TempDir lateinit var tmp: Path

    @Test fun valueParametersStayBoundWhenTheTextLooksLikeSql() {
        DbHarness(tmp).use { h ->
            h.connection("mem")
            h.db.saveQuery("create", update("CREATE TABLE users (name TEXT PRIMARY KEY, secret TEXT NOT NULL)"), 0)
            h.db.execute("create", emptyMap(), setOf("operator"))
            h.db.saveQuery(
                "add",
                update("INSERT INTO users(name, secret) VALUES (:name, :secret)", params("name" to "string", "secret" to "string")),
                0,
            )
            h.db.execute("add", mapOf("name" to "ada", "secret" to "ok"), setOf("operator"))
            h.db.saveQuery(
                "find",
                query("SELECT secret FROM users WHERE name = :name", params("name" to "string"), roles = listOf("operator")),
                0,
            )

            val prepared = SqlText.prepare("SELECT secret FROM users WHERE name = :name", setOf("name"), SqlDialect.SQLITE)
            assertEquals("SELECT secret FROM users WHERE name = ?", prepared.sql)
            assertEquals(listOf("name"), prepared.names)
            assertFalse(prepared.sql.contains("DROP"))

            assertEquals("ok", scalar(h, "find", mapOf("name" to "ada")))
            assertNull(scalar(h, "find", mapOf("name" to "ada' OR '1'='1")))
            assertNull(scalar(h, "find", mapOf("name" to "'; DROP TABLE users; --")))
            h.db.saveQuery("count", scalarQuery("SELECT COUNT(*) FROM users"), 0)
            assertEquals(1, number(scalar(h, "count", emptyMap())))
            h.db.saveQuery("tables", scalarQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'users'"), 0)
            assertEquals("users", scalar(h, "tables", emptyMap()))

            h.db.saveQuery(
                "rename",
                update("UPDATE users SET secret = 'pwned' WHERE name = :name", params("name" to "string")),
                0,
            )
            val changed = h.db.execute("rename", mapOf("name" to "ada'; DROP TABLE users; --"), setOf("operator")) as QueryResult.Update
            assertEquals(0, changed.rowsAffected)
            assertEquals("ok", scalar(h, "find", mapOf("name" to "ada")))

            h.db.saveQuery(
                "literal",
                query("SELECT ':name' AS shown, secret FROM users WHERE name = :name", params("name" to "string")),
                0,
            )
            val row = (h.db.execute("literal", mapOf("name" to "ada"), setOf("operator")) as QueryResult.Rows).rows.single()
            assertEquals(":name", row[0])
            assertEquals("ok", row[1])
        }
    }

    @Test fun commentsStringsAndCastsAreNotParameters() {
        val known = setOf("name", "n")
        assertEquals(
            "SELECT '?' AS q, secret FROM t WHERE name = ?",
            SqlText.prepare("SELECT '?' AS q, secret FROM t WHERE name = :name", known, SqlDialect.SQLITE).sql,
        )
        assertEquals(
            "SELECT 1 -- :name\nFROM t WHERE id = ?",
            SqlText.prepare("SELECT 1 -- :name\nFROM t WHERE id = :n", known, SqlDialect.SQLITE).sql,
        )
        assertEquals("SELECT ?::int", SqlText.prepare("SELECT :n::int", known, SqlDialect.SQLITE).sql)
        assertThrows<QueryRejected> { SqlText.prepare("SELECT 1; DROP TABLE t", known, SqlDialect.SQLITE) }
        assertThrows<QueryRejected> { SqlText.prepare("SELECT :missing", known, SqlDialect.SQLITE) }
    }

    @Test fun typesAreCoercedBeforeBinding() {
        DbHarness(tmp).use { h ->
            h.connection("mem")
            h.db.saveQuery("create", update("CREATE TABLE box (id INTEGER, label TEXT, flag INTEGER, amount REAL, at_time INTEGER)"), 0)
            h.db.execute("create", emptyMap(), emptySet())
            h.db.saveQuery(
                "add",
                update(
                    "INSERT INTO box(id, label, flag, amount, at_time) VALUES (:id, :label, :flag, :amount, :at)",
                    params(
                        "id" to "integer",
                        "label" to "string",
                        "flag" to "boolean",
                        "amount" to "double",
                        "at" to "datetime",
                    ),
                ),
                0,
            )
            h.db.execute(
                "add",
                mapOf("id" to "4", "label" to "bolt", "flag" to "TRUE", "amount" to 1, "at" to "2017-06-25T15:58:47Z"),
                emptySet(),
            )
            h.db.execute(
                "add",
                mapOf("id" to 4.0, "label" to "nut", "flag" to false, "amount" to "1.5", "at" to 1_500_000_000_000L),
                emptySet(),
            )
            h.db.saveQuery("by-id", query("SELECT label FROM box WHERE id = :id ORDER BY label", params("id" to "integer")), 0)
            val labels = (h.db.execute("by-id", mapOf("id" to 4), emptySet()) as QueryResult.Rows).rows.map { it[0] }
            assertEquals(listOf("bolt", "nut"), labels)
            assertThrows<QueryRejected> { h.db.execute("by-id", mapOf("id" to "4; DROP TABLE box"), emptySet()) }
            assertThrows<QueryRejected> { h.db.execute("by-id", mapOf("id" to 4.5), emptySet()) }
            assertThrows<QueryRejected> { h.db.execute("add", mapOf("id" to 1, "label" to "x", "flag" to "yes", "amount" to 1, "at" to "nope"), emptySet()) }

            h.db.saveQuery("when", scalarQuery("SELECT at_time FROM box WHERE label = :label", params("label" to "string")), 0)
            val stored = scalar(h, "when", mapOf("label" to "bolt")).toString()
            val millis = Timestamp.from(java.time.Instant.parse("2017-06-25T15:58:47Z")).time.toString()
            assertTrue(stored == millis || stored.contains("2017-06-25"), stored)
        }
    }

    @Test fun cacheExpiresAndUpdateQueriesDoNotCache() {
        val clock = SettableClock()
        DbHarness(tmp, clock).use { h ->
            h.connection("mem", url = "jdbc:sqlite:\${data}/cache.db")
            assertTrue(Files.exists(tmp.resolve("data/cache.db")))
            h.db.saveQuery("create", update("CREATE TABLE item (n INTEGER)"), 0)
            h.db.execute("create", emptyMap(), emptySet())
            h.db.saveQuery("put", update("INSERT INTO item(n) VALUES (1)"), 0)
            h.db.execute("put", emptyMap(), emptySet())
            h.db.saveQuery(
                "read",
                mapOf(
                    "connection" to "mem",
                    "queryType" to "scalar",
                    "sql" to "SELECT n FROM item",
                    "cacheEnabled" to true,
                    "cacheTtlMillis" to 1000,
                ),
                0,
            )
            assertEquals(1, number(scalar(h, "read", emptyMap())))
            h.db.saveQuery("set", update("UPDATE item SET n = 2"), 0)
            h.db.execute("set", emptyMap(), emptySet())
            val cached = h.db.execute("read", emptyMap(), emptySet()) as QueryResult.Scalar
            assertEquals(1, number(cached.value))
            assertTrue(cached.cached)
            clock.millis += 1000
            val fresh = h.db.execute("read", emptyMap(), emptySet()) as QueryResult.Scalar
            assertEquals(2, number(fresh.value))
            assertFalse(fresh.cached)

            h.db.saveQuery("bump", update("UPDATE item SET n = 3"), 0)
            h.db.execute("bump", emptyMap(), emptySet())
            val stillCached = h.db.execute("read", emptyMap(), emptySet()) as QueryResult.Scalar
            assertEquals(2, number(stillCached.value))
            assertTrue(stillCached.cached)
            h.db.saveQuery(
                "read",
                mapOf(
                    "connection" to "mem",
                    "queryType" to "scalar",
                    "sql" to "SELECT n FROM item",
                    "cacheEnabled" to true,
                    "cacheTtlMillis" to 1000,
                ),
                1,
            )
            val afterSave = h.db.execute("read", emptyMap(), emptySet()) as QueryResult.Scalar
            assertEquals(3, number(afterSave.value))
            assertFalse(afterSave.cached)
            assertThrows<QueryRejected> {
                h.db.saveQuery("bad", mapOf("connection" to "mem", "queryType" to "update", "sql" to "UPDATE item SET n = 3", "cacheEnabled" to true, "cacheTtlMillis" to 1000), 0)
            }
        }
    }

    @Test fun rolesRejectBeforeExecutionAndDoNotReturnScalarFallback() {
        DbHarness(tmp).use { h ->
            h.connection(
                "down",
                driver = "postgres",
                url = "jdbc:postgresql://127.0.0.1:1/none",
                password = PasswordUpdate.Set("Unique-db-pass-9f3a"),
                fields = mapOf("maxWaitMillis" to 250, "validationTimeoutMillis" to 250),
            )
            val status = h.db.connection("down")
            assertEquals("Faulted", status["status"])
            assertEquals(true, status["passwordSet"])
            assertFalse(status.containsKey("password"))
            assertFalse(status.toString().contains("Unique-db-pass-9f3a"))
            assertFalse((status["error"] as String?).orEmpty().contains("Unique-db-pass-9f3a"))
            assertEquals("Unique-db-pass-9f3a", h.secrets["db-down"])
            assertEquals(10_000L, RETEST_INTERVAL_MILLIS)

            h.db.saveQuery(
                "fb",
                mapOf(
                    "connection" to "down",
                    "queryType" to "scalar",
                    "sql" to "SELECT 1",
                    "roles" to listOf("operator"),
                    "fallback" to 7,
                ),
                0,
            )
            assertThrows<QueryForbidden> { h.db.execute("fb", emptyMap(), setOf("viewer")) }
            assertThrows<QueryForbidden> { h.db.execute("fb", emptyMap(), emptySet()) }
            val fallback = h.db.execute("fb", emptyMap(), setOf("operator")) as QueryResult.Scalar
            assertEquals(7, fallback.value)
            assertTrue(fallback.fallback)

            h.connection("mem")
            h.db.saveQuery("open", query("SELECT 1", roles = emptyList()), 0)
            assertTrue(h.db.execute("open", emptyMap(), setOf("viewer")) is QueryResult.Rows)
            h.db.saveQuery("shut", mapOf("connection" to "mem", "queryType" to "query", "sql" to "SELECT 1", "enabled" to false), 0)
            assertThrows<QueryForbidden> { h.db.execute("shut", emptyMap(), setOf("operator")) }
        }
    }

    @Test fun sqlitePoolIsOneConnectionAndFailoverFollowsTheMode() {
        DbHarness(tmp).use { h ->
            h.connection("mem", fields = mapOf("maxActive" to 8, "defaultConnection" to true))
            assertEquals(1, h.db.connection("mem")["maxConnections"])
            h.connection("spare", url = "jdbc:sqlite:\${data}/spare.db")
            h.db.saveQuery("make", mapOf("connection" to "spare", "queryType" to "update", "sql" to "CREATE TABLE marker (v TEXT)"), 0)
            h.db.execute("make", emptyMap(), emptySet())
            h.db.saveQuery("seed", mapOf("connection" to "spare", "queryType" to "update", "sql" to "INSERT INTO marker(v) VALUES ('from-failover')"), 0)
            h.db.execute("seed", emptyMap(), emptySet())

            h.connection(
                "down",
                driver = "postgres",
                url = "jdbc:postgresql://127.0.0.1:1/none",
                fields = mapOf(
                    "maxWaitMillis" to 250,
                    "validationTimeoutMillis" to 250,
                    "failoverConnection" to "spare",
                    "failoverMode" to "standard",
                ),
            )
            h.db.saveQuery("read", mapOf("connection" to "down", "queryType" to "scalar", "sql" to "SELECT v FROM marker"), 0)
            assertEquals("from-failover", scalar(h, "read", emptyMap()))

            val primary = tmp.resolve("data/primary.db")
            java.sql.DriverManager.getConnection("jdbc:sqlite:$primary").use { jdbc ->
                jdbc.createStatement().use { it.execute("CREATE TABLE marker (v TEXT)") }
                jdbc.createStatement().use { it.execute("INSERT INTO marker(v) VALUES ('from-primary')") }
            }
            h.connection(
                "down",
                url = "jdbc:sqlite:\${data}/primary.db",
                fields = mapOf("failoverConnection" to "spare", "failoverMode" to "standard"),
                version = 1,
            )
            assertEquals("from-primary", scalar(h, "read", emptyMap()))

            h.connection(
                "sticky",
                driver = "postgres",
                url = "jdbc:postgresql://127.0.0.1:1/none",
                fields = mapOf(
                    "maxWaitMillis" to 250,
                    "validationTimeoutMillis" to 250,
                    "failoverConnection" to "spare",
                    "failoverMode" to "sticky",
                ),
            )
            h.db.saveQuery("sticky-read", mapOf("connection" to "sticky", "queryType" to "scalar", "sql" to "SELECT v FROM marker"), 0)
            assertEquals("from-failover", scalar(h, "sticky-read", emptyMap()))
            h.connection(
                "sticky",
                url = "jdbc:sqlite:\${data}/primary.db",
                fields = mapOf("failoverConnection" to "spare", "failoverMode" to "sticky"),
                version = 1,
            )
            assertEquals("from-failover", scalar(h, "sticky-read", emptyMap()))
        }
    }

    @Test fun plaintextPasswordIsRejectedByTheSchema() {
        DbHarness(tmp).use { h ->
            assertThrows<ValidationException> {
                h.store.put(
                    Scope.GATEWAY,
                    Resource(
                        "DatabaseConnection",
                        Metadata("plant", "db"),
                        mapOf("driver" to "sqlite", "url" to "jdbc:sqlite::memory:", "password" to "not-a-reference"),
                    ),
                )
            }
        }
    }

    @Test fun redactStripsThePasswordFromADriverMessage() {
        assertEquals("login failed for [redacted]", redact("login failed for Unique-db-pass-9f3a", listOf("Unique-db-pass-9f3a")))
    }

    private fun scalar(h: DbHarness, name: String, parameters: Map<String, Any?>): Any? =
        when (val result = h.db.execute(name, parameters, setOf("operator"))) {
            is QueryResult.Scalar -> result.value
            is QueryResult.Rows -> when (result.rows.size) {
                0 -> null
                1 -> result.rows.single().single()
                else -> error("expected one row, got ${result.rows.size}")
            }
            is QueryResult.Update, is QueryResult.Procedure -> error("not a read")
        }

    private fun number(value: Any?): Int = (value as Number).toInt()

    private fun update(sql: String, parameters: List<Map<String, String>> = emptyList()) = mapOf(
        "connection" to "mem",
        "queryType" to "update",
        "sql" to sql,
        "parameters" to parameters,
    )

    private fun query(sql: String, parameters: List<Map<String, String>> = emptyList(), roles: List<String> = emptyList()) = mapOf(
        "connection" to "mem",
        "queryType" to "query",
        "sql" to sql,
        "parameters" to parameters,
        "roles" to roles,
    )

    private fun scalarQuery(sql: String, parameters: List<Map<String, String>> = emptyList()) = mapOf(
        "connection" to "mem",
        "queryType" to "scalar",
        "sql" to sql,
        "parameters" to parameters,
    )

    private fun params(vararg pairs: Pair<String, String>) = pairs.map { mapOf("name" to it.first, "dataType" to it.second) }
}
