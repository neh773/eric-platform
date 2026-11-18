package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * DAT-023 (Oracle), DAT-017 (DB2), DAT-024 (Snowflake), DAT-016 (translators): the SQL each translator writes is run
 * on H2 in its Oracle and DB2 compatibility modes, reached through an uploaded JAR and the query browser. There is
 * no Oracle, DB2 or Snowflake server here; H2 has no Snowflake mode, so that translator runs on H2's default mode
 * with the column types that H2 knows (see DIFFERENCES 774).
 */
@Timeout(120)
class SqlTranslatorTest {
    @TempDir lateinit var tmp: Path

    private fun rowsOf(h: DbHarness, connection: String, sql: String): List<List<Any?>> =
        (h.db.browse(connection, sql, 100) as BrowseResult.Rows).rows

    private fun exec(h: DbHarness, connection: String, sql: String) {
        h.db.browse(connection, sql, 1)
    }

    private fun open(h: DbHarness, name: String, url: String, translator: String) {
        h.connection(
            name, driver = "custom", url = url,
            fields = mapOf("driverId" to "h2", "translator" to translator, "maxActive" to 1, "allowBrowserWrites" to true),
        )
        assertEquals("Valid", h.db.connection(name)["status"], h.db.connection(name)["error"]?.toString())
    }

    /** Create, fill, page and limit through the translator's own SQL. */
    private fun exercise(h: DbHarness, connection: String, t: SqlTranslator, types: List<String>) {
        val columns = listOf(SqlTranslator.Column("ID", "integer", primaryKey = true, notNull = true)) +
            types.mapIndexed { i, type -> SqlTranslator.Column("C$i", type, length = 40) }
        exec(h, connection, t.createTable("READINGS", columns))
        for (id in 1..10) exec(h, connection, "INSERT INTO ${t.quote("READINGS")} (${t.quote("ID")}) VALUES ($id)")
        val select = "SELECT ${t.quote("ID")} FROM ${t.quote("READINGS")} ORDER BY ${t.quote("ID")}"
        assertEquals(listOf(3, 4, 5), rowsOf(h, connection, t.page(select, 3, 2)).map { it[0].toString().toDouble().toInt() })
        assertEquals(listOf(1, 2, 3, 4), rowsOf(h, connection, t.limit(select, 4)).map { it[0].toString().toDouble().toInt() })
        assertEquals(10, rowsOf(h, connection, t.page(select, 100, 0)).size)
    }

    @Test fun oracleSqlRunsOnH2InOracleMode() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("h2", h2JarBytes(), null, "admin")
            open(h, "ora", "jdbc:h2:mem:oracle;MODE=Oracle;DB_CLOSE_DELAY=-1", "ORACLE")
            val t = SqlTranslator.ORACLE
            // The connection's validation query defaulted from the translator and ran on the server (FROM DUAL).
            assertEquals("SELECT 1 FROM DUAL", h.db.connection("ora")["validationQuery"])
            exercise(h, "ora", t, listOf("string", "text", "long", "double", "boolean", "datetime"))
            exec(h, "ora", "UPDATE ${t.quote("READINGS")} SET ${t.quote("C4")} = ${t.booleanLiteral(true)}, ${t.quote("C5")} = ${t.currentTimestamp} WHERE ${t.quote("ID")} = 1")
            val row = rowsOf(h, "ora", "SELECT ${t.quote("C4")}, ${t.quote("C5")} FROM ${t.quote("READINGS")} WHERE ${t.quote("ID")} = 1").single()
            assertEquals(1, row[0].toString().toDouble().toInt())
            assertTrue(row[1] != null)
            assertEquals("CREATE TABLE \"T\" (\"ID\" NUMBER(10) NOT NULL, PRIMARY KEY (\"ID\"))", t.createTable("T", listOf(SqlTranslator.Column("ID", "integer", notNull = true, primaryKey = true))))
            assertEquals(h.db.translator("ORACLE"), h.db.connectionTranslator("ora"))
        }
    }

    @Test fun db2SqlRunsOnH2InDb2Mode() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("h2", h2JarBytes(), null, "admin")
            open(h, "db2", "jdbc:h2:mem:db2;MODE=DB2;DB_CLOSE_DELAY=-1", "DB2")
            val t = SqlTranslator.DB2
            assertEquals("SELECT 1 FROM SYSIBM.SYSDUMMY1", h.db.connection("db2")["validationQuery"])
            exercise(h, "db2", t, listOf("string", "text", "long", "double", "boolean", "datetime"))
            exec(h, "db2", "UPDATE ${t.quote("READINGS")} SET ${t.quote("C4")} = ${t.booleanLiteral(false)}, ${t.quote("C5")} = ${t.currentTimestamp} WHERE ${t.quote("ID")} = 2")
            val row = rowsOf(h, "db2", "SELECT ${t.quote("C4")}, ${t.quote("C5")} FROM ${t.quote("READINGS")} WHERE ${t.quote("ID")} = 2").single()
            assertEquals(0, row[0].toString().toDouble().toInt())
            assertTrue(row[1] != null)
        }
    }

    @Test fun snowflakeSqlRunsOnH2ForEverythingH2Knows() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("h2", h2JarBytes(), null, "admin")
            open(h, "snow", "jdbc:h2:mem:snow;DB_CLOSE_DELAY=-1", "SNOWFLAKE")
            val t = SqlTranslator.SNOWFLAKE
            // TIMESTAMP_NTZ is Snowflake's own type; H2 has no such type, so the datetime column is left out here.
            exercise(h, "snow", t, listOf("string", "text", "long", "double", "boolean"))
            assertEquals("TIMESTAMP_NTZ", t.columnType("datetime"))
            exec(h, "snow", "UPDATE ${t.quote("READINGS")} SET ${t.quote("C4")} = ${t.booleanLiteral(true)} WHERE ${t.quote("ID")} = 1")
            assertEquals(true, rowsOf(h, "snow", "SELECT ${t.quote("C4")} FROM ${t.quote("READINGS")} WHERE ${t.quote("ID")} = 1").single()[0])
            assertEquals(1, rowsOf(h, "snow", "SELECT ${t.currentTimestamp}").size)
        }
    }

    @Test fun theTranslatorsDifferWhereTheDatabasesDo() {
        val select = "SELECT a FROM t ORDER BY a"
        assertEquals("$select OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY", SqlTranslator.ORACLE.page(select, 10, 5))
        assertEquals("$select OFFSET 5 ROWS FETCH FIRST 10 ROWS ONLY", SqlTranslator.DB2.page(select, 10, 5))
        assertEquals("$select LIMIT 10 OFFSET 5", SqlTranslator.SNOWFLAKE.page(select, 10, 5))
        assertEquals("$select FETCH FIRST 3 ROWS ONLY", SqlTranslator.ORACLE.limit("$select;", 3))
        assertEquals("VARCHAR2(30)", SqlTranslator.ORACLE.columnType("string", 30))
        assertEquals("CLOB", SqlTranslator.DB2.columnType("text"))
        assertEquals("CURRENT TIMESTAMP", SqlTranslator.DB2.currentTimestamp)
        assertEquals("SELECT 1 FROM DUAL", SqlTranslator.ORACLE.validationQuery)
        assertEquals(listOf("MSSQL", "MYSQL", "ORACLE", "POSTGRES", "SQLITE", "DB2", "SNOWFLAKE").sorted(), SqlTranslator.BUILT_IN.keys.sorted())
    }

    @Test fun identifiersAndNumbersCannotCarryPlaceholdersOrSql() {
        val t = SqlTranslator.ORACLE
        for (bad in listOf("a\"b", "a b", "1a", "a;drop", "{sql}", "", "a-b")) assertThrows<QueryRejected>(bad) { t.quote(bad) }
        assertEquals("\"A\".\"B\"", t.quoteTable("A.B"))
        assertThrows<QueryRejected> { t.page("SELECT 1 FROM DUAL", 0, 0) }
        assertThrows<QueryRejected> { t.page("SELECT 1 FROM DUAL", 1, -1) }
        assertThrows<QueryRejected> { t.columnType("nope") }
        // A table named like a placeholder is refused, and a value is never scanned for placeholders.
        assertThrows<QueryRejected> { t.createTable("{columns}", listOf(SqlTranslator.Column("A", "integer"))) }
        assertEquals("SELECT '{limit}' FROM DUAL FETCH FIRST 2 ROWS ONLY", t.limit("SELECT '{limit}' FROM DUAL", 2))
    }

    @Test fun aCustomTranslatorIsAResourceThatConnectionsCanNameAndItsTemplatesAreChecked() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("h2", h2JarBytes(), null, "admin")
            val fields = SqlTranslator.ORACLE.view().filterKeys { it !in setOf("name", "builtIn") } + mapOf("validationQuery" to "SELECT 2 FROM DUAL")
            h.db.saveTranslator("mydb", fields, 0)
            assertTrue(h.db.translators().any { it["name"] == "mydb" && it["builtIn"] == false })
            h.connection("mine", driver = "custom", url = "jdbc:h2:mem:mine;MODE=Oracle;DB_CLOSE_DELAY=-1", fields = mapOf("driverId" to "h2", "translator" to "mydb", "maxActive" to 1))
            assertEquals("SELECT 2 FROM DUAL", h.db.connection("mine")["validationQuery"])
            assertEquals("Valid", h.db.connection("mine")["status"])
            assertEquals("mydb", h.db.connectionTranslator("mine").name)
            // In use: refused.
            assertThrows<QueryRejected> { h.db.deleteTranslator("mydb", null) }
            // Unknown translator, a built-in kind with another translator, and a custom kind with none.
            assertThrows<QueryRejected> { h.connection("x1", driver = "custom", url = "jdbc:h2:mem:x1", fields = mapOf("driverId" to "h2", "translator" to "nope")) }
            assertThrows<QueryRejected> { h.connection("x2", driver = "custom", url = "jdbc:h2:mem:x2", fields = mapOf("driverId" to "h2")) }
            assertThrows<QueryRejected> { h.connection("x3", driver = "sqlite", url = "jdbc:sqlite::memory:", fields = mapOf("translator" to "mydb")) }
            // Template checks.
            val base = fields.toMutableMap()
            assertThrows<QueryRejected> { h.db.saveTranslator("t1", base + ("pageTemplate" to "{sql} LIMIT {limit}"), 0) }
            assertThrows<QueryRejected> { h.db.saveTranslator("t2", base + ("pageTemplate" to "{sql} {limit} {offset} {other}"), 0) }
            assertThrows<QueryRejected> { h.db.saveTranslator("t3", base - "columnTypes", 0) }
            assertThrows<QueryRejected> { h.db.saveTranslator("t4", base + ("columnTypes" to mapOf("string" to "X")), 0) }
            assertThrows<QueryRejected> { h.db.saveTranslator("t5", base + ("validationQuery" to "DROP TABLE x"), 0) }
            assertThrows<QueryRejected> { h.db.saveTranslator("t6", base + ("quoteOpen" to "'"), 0) }
            assertThrows<QueryRejected> { h.db.saveTranslator("ORACLE", base, 0) }
            h.db.deleteConnection("mine", null)
            h.db.deleteTranslator("mydb", null)
            assertFalse(h.db.translators().any { it["name"] == "mydb" })
        }
    }
}
