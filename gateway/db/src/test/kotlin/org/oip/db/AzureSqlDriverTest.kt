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
 * Azure SQL (doc source: FEATURES DAT-020) is the SQL Server dialect with encryption and certificate checks on.
 * No live Azure server is used: the URL building is checked directly and a closed port faults the pool.
 */
@Timeout(60)
class AzureSqlDriverTest {
    @TempDir lateinit var tmp: Path

    @Test fun theResolvedUrlTurnsOnEncryptionAndCertificateChecks() {
        val url = AzureSqlUrl.resolve("jdbc:sqlserver://plant-sql.database.windows.net:1433;databaseName=plant")
        assertEquals(
            "jdbc:sqlserver://plant-sql.database.windows.net:1433;databaseName=plant;encrypt=true;trustServerCertificate=false;" +
                "hostNameInCertificate=*.database.windows.net;loginTimeout=30",
            url,
        )
        // Settings the admin already wrote are kept, not repeated.
        val own = AzureSqlUrl.resolve("jdbc:sqlserver://plant-sql.database.windows.net;database=plant;Encrypt=true;loginTimeout=5;")
        assertEquals(1, Regex("(?i)encrypt=").findAll(own).count(), own)
        assertTrue(own.contains("loginTimeout=5") && !own.contains("loginTimeout=30"), own)
        // A host outside Azure gets no certificate host name.
        assertFalse(AzureSqlUrl.resolve("jdbc:sqlserver://10.0.0.5;databaseName=plant").contains("hostNameInCertificate"))
    }

    @Test fun urlsThatWeakenTheConnectionAreRefused() {
        for (bad in listOf(
            "jdbc:sqlserver://x.database.windows.net;encrypt=false",
            "jdbc:sqlserver://x.database.windows.net;trustServerCertificate=true",
            "jdbc:postgresql://x.database.windows.net/db",
        )) assertThrows<QueryRejected>(bad) { AzureSqlUrl.check(bad) }
        assertEquals(SqlDialect.MSSQL, SqlDialect.of("azuresql"))
    }

    @Test fun anAzureConnectionUsesTheSqlServerDriverAndFaultsWhenNothingListens() {
        DbHarness(tmp).use { h ->
            h.connection(
                "cloud",
                driver = "azuresql",
                url = "jdbc:sqlserver://127.0.0.1:1;databaseName=plant;loginTimeout=1;connectRetryCount=0",
                fields = mapOf("maxWaitMillis" to 1000, "validationTimeoutMillis" to 250, "maxActive" to 1, "username" to "oip@plant-sql"),
            )
            assertThrows<QueryRejected> {
                h.connection("weak", driver = "azuresql", url = "jdbc:sqlserver://127.0.0.1:1;encrypt=false")
            }
            assertThrows<QueryRejected> {
                h.connection("bad-translator", driver = "azuresql", url = "jdbc:sqlserver://127.0.0.1:1", fields = mapOf("translator" to "POSTGRES"))
            }
            assertThrows<QueryRejected> {
                h.connection("user-in-url", driver = "azuresql", url = "jdbc:sqlserver://127.0.0.1:1;user=sa")
            }
            h.db.saveQuery("ping", mapOf("connection" to "cloud", "queryType" to "query", "sql" to "SELECT 1"), 0)
            val failed = assertThrows<QueryUnavailable> { h.db.execute("ping", emptyMap(), emptySet()) }
            assertFalse(failed.message!!.contains("driver must be"), failed.message)
            assertEquals("Faulted", h.db.connection("cloud")["status"], failed.message)
            assertEquals("azuresql", h.db.connection("cloud")["driver"])
        }
    }
}
