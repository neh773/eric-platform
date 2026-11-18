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
 * DAT-003, DAT-019, DAT-022: MariaDB and MySQL connections use the bundled BSD-licensed client. The
 * scanner and driver checks need no server; the live tests run against a MariaDB container (or a server
 * on OIP_SIM_MARIADB_PORT) and are skipped where neither exists.
 */
@Timeout(120)
class MariaDbDriverTest {
    @TempDir lateinit var tmp: Path

    @Test fun theBundledClientIsBsdLicensedAndAcceptsTheThinUrl() {
        val cls = Class.forName("org.drizzle.jdbc.DrizzleDriver")
        val driver = cls.getDeclaredConstructor().newInstance() as Driver
        assertTrue(driver.acceptsURL("jdbc:mysql:thin://db.example:3306/plant"))
        assertFalse(driver.acceptsURL("jdbc:postgresql://db.example/plant"))
        val pom = JarFile(Path.of(cls.protectionDomain.codeSource.location.toURI()).toFile()).use { archive ->
            archive.getInputStream(archive.getJarEntry("META-INF/maven/org.drizzle.jdbc/drizzle-jdbc/pom.xml")).bufferedReader().readText()
        }
        assertTrue(pom.contains("<name>Simplified BSD</name>"), pom.substringAfter("<licenses>").take(240))
    }

    @Test fun mysqlQuotingFollowsTheServerLexer() {
        val d = SqlDialect.MYSQL
        assertEquals(SqlDialect.MYSQL, SqlDialect.of("mariadb"))
        assertEquals("SELECT ?", SqlText.prepare("SELECT :n", setOf("n"), d).sql)
        // A backslash escapes the quote, so the semicolon is inside the string.
        assertEquals("SELECT 'a\\';b'", SqlText.single("SELECT 'a\\';b'", d))
        assertEquals("SELECT `a;b`", SqlText.single("SELECT `a;b`", d))
        assertThrows<QueryRejected> { SqlText.single("SELECT 1; DROP TABLE t", d) }
        // # and "-- " comment out the rest of the line; "--1" does not.
        assertEquals("SELECT 1 # ; x", SqlText.single("SELECT 1 # ; x", d))
        assertEquals("SELECT 1 -- ; x", SqlText.single("SELECT 1 -- ; x", d))
        assertThrows<QueryRejected> { SqlText.single("SELECT 1--1; DROP TABLE t", d) }
        // The server runs version comments, so they are refused.
        assertThrows<QueryRejected> { SqlText.single("SELECT 1 /*! ; DROP TABLE t */", d) }
        assertThrows<QueryRejected> { SqlText.single("SELECT 1 /*M! ; DROP TABLE t */", d) }
    }

    @Test fun connectionFieldsAreCheckedForBothNames() {
        DbHarness(tmp).use { h ->
            h.connection("maria", driver = "mariadb", url = "jdbc:mariadb://127.0.0.1:1/oip", fields = mapOf("username" to "oip"))
            h.connection("my", driver = "mysql", url = "jdbc:mysql://127.0.0.1:1/oip")
            assertThrows<QueryRejected> { h.connection("a", driver = "mysql", url = "jdbc:postgresql://x/y") }
            assertThrows<QueryRejected> { h.connection("b", driver = "mysql", url = "jdbc:mysql://u@127.0.0.1/oip") }
            assertThrows<QueryRejected> { h.connection("c", driver = "mysql", url = "jdbc:mysql://127.0.0.1/oip?user=root") }
            assertThrows<QueryRejected> { h.connection("d", driver = "mysql", url = "jdbc:mysql://127.0.0.1/oip", fields = mapOf("translator" to "POSTGRES")) }
        }
    }

    @Test fun aMariaDbConnectionReadsAndRefusesWrites() {
        val hostPort = MariaDbServer.require()
        DbHarness(tmp).use { h ->
            h.secrets["maria-pw"] = MariaDbServer.PASSWORD
            h.connection(
                "plant",
                driver = "mariadb",
                url = "jdbc:mariadb://$hostPort/oip",
                password = PasswordUpdate.Reference("secret://maria-pw"),
                fields = mapOf("username" to MariaDbServer.USER, "maxActive" to 2),
            )
            h.db.saveQuery("one", mapOf("connection" to "plant", "queryType" to "query", "sql" to "SELECT :n + 1 AS next, 'it''s' AS s", "parameters" to listOf(mapOf("name" to "n", "dataType" to "long"))), 0)
            val rows = h.db.execute("one", mapOf("n" to 4), emptySet()) as QueryResult.Rows
            assertEquals(5L, (rows.rows[0][0] as Number).toLong())
            assertEquals("it's", rows.rows[0][1])
            val browse = h.db.browse("plant", "SELECT 7 AS x, 'a\\'b' AS y", 10) as BrowseResult.Rows
            assertEquals(listOf("x", "y"), browse.columns)
            assertEquals("a'b", browse.rows[0][1])
            assertThrows<QueryForbidden> { h.db.browse("plant", "CREATE TABLE t (a INT)", 10) }
            assertEquals("Valid", h.db.connection("plant")["status"])
        }
    }

    @Test fun aMySqlConnectionReadsThroughTheSameClient() {
        val hostPort = MySqlServer.require()
        DbHarness(tmp).use { h ->
            h.secrets["my-pw"] = MySqlServer.PASSWORD
            h.connection(
                "plant",
                driver = "mysql",
                url = "jdbc:mysql://$hostPort/oip",
                password = PasswordUpdate.Reference("secret://my-pw"),
                fields = mapOf("username" to MySqlServer.USER, "maxActive" to 2),
            )
            val browse = h.db.browse("plant", "SELECT 7 AS x, 'a\\'b' AS y", 10) as BrowseResult.Rows
            assertEquals(listOf("x", "y"), browse.columns)
            assertEquals("a'b", browse.rows[0][1])
            assertThrows<QueryForbidden> { h.db.browse("plant", "DROP TABLE nothing", 10) }
            assertEquals("Valid", h.db.connection("plant")["status"])
        }
    }
}
