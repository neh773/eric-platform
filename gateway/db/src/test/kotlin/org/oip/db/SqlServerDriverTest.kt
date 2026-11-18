package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Driver
import java.util.jar.JarFile

/**
 * The SQL Server connection uses the bundled JDBC driver. A live server is not required: the driver class
 * accepts `jdbc:sqlserver:` URLs, its jar carries the MIT license text, and a closed port faults the pool
 * through that driver rather than a rejected driver name.
 */
@Timeout(60)
class SqlServerDriverTest {
    @TempDir lateinit var tmp: Path

    @Test fun quotingFollowsTheServerAndDoesNotTreatBackticksAsQuotes() {
        val dialect = SqlDialect.MSSQL
        assertEquals("SELECT ?", SqlText.prepare("SELECT :n", setOf("n"), dialect).sql)
        assertEquals("SELECT 'it''s'", SqlText.single("SELECT 'it''s'", dialect))
        assertEquals("SELECT [a;b]", SqlText.single("SELECT [a;b]", dialect))
        assertThrows<QueryRejected> { SqlText.single("SELECT 1; DROP TABLE t", dialect) }
        // A backtick is not a quote, so a semicolon inside one is a second statement and is refused.
        assertThrows<QueryRejected> { SqlText.single("SELECT `a; DROP TABLE t`", dialect) }
        assertFalse(SqlDialect.of("sqlserver").postgres)
        assertTrue(SqlDialect.of("sqlserver").mssql)
    }

    @Test fun theBundledDriverIsMitLicensedAndAcceptsItsUrl() {
        val cls = Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
        val driver = cls.getDeclaredConstructor().newInstance() as Driver
        assertTrue(driver.acceptsURL("jdbc:sqlserver://db.example:1433;databaseName=plant"))
        assertFalse(driver.acceptsURL("jdbc:postgresql://db.example/plant"))
        val jar = cls.protectionDomain.codeSource.location
        val pom = JarFile(Path.of(jar.toURI()).toFile()).use { archive ->
            val entry = archive.getJarEntry("META-INF/maven/com.microsoft.sqlserver/mssql-jdbc/pom.xml")
            archive.getInputStream(entry).bufferedReader().readText()
        }
        assertTrue(pom.contains("<name>MIT License</name>"), pom.substringAfter("<licenses>").take(240))
    }

    @Test fun aSqlServerConnectionUsesTheDriverAndFaultsWhenNothingListens() {
        DbHarness(tmp).use { h ->
            h.connection(
                "plant",
                driver = "sqlserver",
                url = "jdbc:sqlserver://127.0.0.1:1;databaseName=plant;loginTimeout=1;connectRetryCount=0",
                fields = mapOf("maxWaitMillis" to 1000, "validationTimeoutMillis" to 250, "maxActive" to 1, "username" to "sa"),
            )
            assertThrows<QueryRejected> {
                h.connection("bad-translator", driver = "sqlserver", url = "jdbc:sqlserver://127.0.0.1:1", fields = mapOf("translator" to "POSTGRES"))
            }
            assertThrows<QueryRejected> {
                h.connection("user-in-url", driver = "sqlserver", url = "jdbc:sqlserver://127.0.0.1:1;user=sa")
            }
            h.db.saveQuery(
                "ping",
                mapOf("connection" to "plant", "queryType" to "query", "sql" to "SELECT 1"),
                0,
            )
            val failed = assertThrows<QueryUnavailable> { h.db.execute("ping", emptyMap(), emptySet()) }
            assertFalse(failed.message!!.contains("driver must be"), failed.message)
            assertTrue(failed.message!!.contains("Connection is not available"), failed.message)
            val status = h.db.connection("plant")
            assertEquals("Faulted", status["status"], failed.message)
        }
    }
}
