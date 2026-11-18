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
 * DAT-027 (per-driver defaults), and the Oracle, DB2 and Snowflake connection builders (DAT-023, DAT-017, DAT-024).
 * A connection that leaves out its url, translator, validation query, driver or extra properties gets them from
 * the driver's settings, which are config resources and can be overridden.
 */
@Timeout(120)
class DriverSettingsTest {
    @TempDir lateinit var tmp: Path

    private fun save(h: DbHarness, name: String, vararg fields: Pair<String, Any?>) =
        h.db.saveConnection(name, mapOf(*fields), 0, PasswordUpdate.Unchanged)

    private fun setting(h: DbHarness, name: String, key: String): Any? =
        h.db.driverSettings().single { it["name"] == name }[key]

    @Test fun theBuiltInDefaultsBuildOracleDb2AndSnowflakeConnections() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("vendor", h2JarBytes(), null, "admin")
            save(h, "ora", "driver" to "oracle", "driverId" to "vendor", "host" to "db.example", "database" to "ORCLPDB1", "enabled" to false)
            save(h, "blue", "driver" to "db2", "driverId" to "vendor", "host" to "10.0.0.5", "port" to 50001, "database" to "SAMPLE", "enabled" to false)
            save(h, "snow", "driver" to "snowflake", "driverId" to "vendor", "host" to "acme-xy123.snowflakecomputing.com", "database" to "PLANT", "enabled" to false)
            val ora = h.db.connection("ora")
            assertEquals("jdbc:oracle:thin:@//db.example:1521/ORCLPDB1", ora["url"])
            assertEquals("ORACLE", ora["translator"])
            assertEquals("SELECT 1 FROM DUAL", ora["validationQuery"])
            assertEquals("vendor", ora["driverId"])
            val db2 = h.db.connection("blue")
            assertEquals("jdbc:db2://10.0.0.5:50001/SAMPLE", db2["url"])
            assertEquals("DB2", db2["translator"])
            assertEquals("SELECT 1 FROM SYSIBM.SYSDUMMY1", db2["validationQuery"])
            val snow = h.db.connection("snow")
            assertEquals("jdbc:snowflake://acme-xy123.snowflakecomputing.com/?db=PLANT", snow["url"])
            assertEquals("SNOWFLAKE", snow["translator"])
            assertEquals("Disabled", snow["status"])
        }
    }

    @Test fun anOracleConnectionWaitsForItsDriverAndThenOpensThroughIt() {
        DbHarness(tmp).use { h ->
            // The resource is written as a hand-edited file would be: save refuses a driver that is not uploaded.
            assertThrows<QueryRejected> {
                save(h, "ora", "driver" to "oracle", "driverId" to "vendor", "host" to "127.0.0.1", "database" to "X")
            }
            h.store.put(
                org.oip.config.Scope.GATEWAY,
                org.oip.config.Resource(
                    CONNECTION_KIND, org.oip.config.Metadata("ora", DB_MODULE, version = 0),
                    mapOf(
                        "driver" to "oracle", "driverId" to "vendor", "url" to "jdbc:oracle:thin:@//127.0.0.1:1/X", "translator" to "ORACLE",
                        "maxActive" to 1, "maxWaitMillis" to 1000, "validationTimeoutMillis" to 250,
                    ),
                ),
                "test",
            )
            assertEquals("Faulted", h.db.connection("ora")["status"])
            assertTrue(h.db.connection("ora")["error"].toString().contains("not uploaded"), h.db.connection("ora")["error"].toString())
            h.db.installDriver("vendor", h2JarBytes(), null, "admin")
            // The upload re-opened the connection through the driver, which (H2 here) does not take a jdbc:oracle: url.
            val after = h.db.connection("ora")
            assertEquals("Faulted", after["status"])
            assertFalse(after["error"].toString().contains("not uploaded"), after["error"].toString())
            assertTrue(after["error"].toString().contains("Connection is not available"), after["error"].toString())
        }
    }

    @Test fun credentialsAndForeignUrlsAreRefusedForTheNewKinds() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("vendor", h2JarBytes(), null, "admin")
            fun url(kind: String, url: String) = save(h, "x", "driver" to kind, "driverId" to "vendor", "url" to url, "enabled" to false)
            assertThrows<QueryRejected> { url("oracle", "jdbc:postgresql://h/x") }
            assertThrows<QueryRejected> { url("oracle", "jdbc:oracle:thin:scott/tiger@db:1521/x") }
            assertThrows<QueryRejected> { url("oracle", "jdbc:oracle:thin:@//db:1521/x?user=scott") }
            assertThrows<QueryRejected> { url("db2", "jdbc:db2://h:50000/x:user=bob;password=pw;") }
            assertThrows<QueryRejected> { url("snowflake", "jdbc:snowflake://a.snowflakecomputing.com/?user=bob") }
            assertThrows<QueryRejected> { url("snowflake", "jdbc:oracle:thin:@//h:1/x") }
            assertThrows<QueryRejected> { url("custom", "http://h/x") }
            assertThrows<QueryRejected> { url("mongo", "jdbc:mongo://h/x") }
            url("oracle", "jdbc:oracle:thin:@//db:1521/x")
        }
    }

    @Test fun hostPortAndDatabaseCannotAddUrlParts() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("vendor", h2JarBytes(), null, "admin")
            fun build(host: Any?, port: Any? = null, database: Any? = "X") =
                save(h, "x", "driver" to "oracle", "driverId" to "vendor", "host" to host, "port" to port, "database" to database, "enabled" to false)
            for (host in listOf("a;b", "a?b=c", "a/b", "a b", "a@b", "", "-x")) assertThrows<QueryRejected>("host '$host'") { build(host) }
            for (database in listOf("x;y", "x?z=1", "x y", "x/y", "")) assertThrows<QueryRejected>("database '$database'") { build("h", database = database) }
            assertThrows<QueryRejected> { build("h", port = 0) }
            assertThrows<QueryRejected> { build("h", port = 70000) }
            assertThrows<QueryRejected> { build("h", port = "1521") }
            assertThrows<QueryRejected> { build(null) }
            // A url and a host together are ambiguous.
            assertThrows<QueryRejected> {
                save(h, "x", "driver" to "oracle", "driverId" to "vendor", "url" to "jdbc:oracle:thin:@//a:1/b", "host" to "h", "enabled" to false)
            }
            build("[::1]")
        }
    }

    @Test fun settingsOverrideTheBuiltInsAndAreAppliedWhenAConnectionOmitsAField() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("vendor", h2JarBytes(), null, "admin")
            assertEquals(1521, setting(h, "oracle", "defaultPort"))
            assertFalse(h.db.driverSettings().single { it["name"] == "oracle" }["overridden"] as Boolean)
            h.db.saveDriverSettings(
                "oracle",
                mapOf("defaultPort" to 1522, "driverId" to "vendor", "validationQuery" to "SELECT 5 FROM DUAL", "extraProperties" to mapOf("oracle.net.CONNECT_TIMEOUT" to "3000")),
                0,
            )
            assertEquals(1522, setting(h, "oracle", "defaultPort"))
            assertEquals("jdbc:oracle:thin:@//{host}:{port}/{database}", setting(h, "oracle", "urlTemplate"))
            assertTrue(h.db.driverSettings().single { it["name"] == "oracle" }["overridden"] as Boolean)
            // driverId, port, validation query and extra properties all come from the settings.
            save(h, "ora", "driver" to "oracle", "host" to "db.example", "database" to "SVC", "enabled" to false)
            val ora = h.db.connection("ora")
            assertEquals("jdbc:oracle:thin:@//db.example:1522/SVC", ora["url"])
            assertEquals("vendor", ora["driverId"])
            assertEquals("SELECT 5 FROM DUAL", ora["validationQuery"])
            assertEquals(mapOf("oracle.net.CONNECT_TIMEOUT" to "3000"), h.store.get(org.oip.config.ResourceId(DB_MODULE, org.oip.config.Scope.GATEWAY, CONNECTION_KIND, "ora"))!!.spec["extraProperties"])
            // What the connection says itself wins: port, validation query and one extra property.
            save(
                h, "ora2", "driver" to "oracle", "host" to "db.example", "port" to 1530, "database" to "SVC", "enabled" to false,
                "validationQuery" to "SELECT 6 FROM DUAL", "extraProperties" to mapOf("oracle.net.CONNECT_TIMEOUT" to "9"),
            )
            assertEquals("jdbc:oracle:thin:@//db.example:1530/SVC", h.db.connection("ora2")["url"])
            assertEquals("SELECT 6 FROM DUAL", h.db.connection("ora2")["validationQuery"])
            // Deleting the override returns the built-in value, and a connection with no driver then has none to use.
            h.db.deleteDriverSettings("oracle", null)
            assertEquals(1521, setting(h, "oracle", "defaultPort"))
            assertThrows<QueryRejected> { save(h, "ora3", "driver" to "oracle", "host" to "db.example", "database" to "SVC", "enabled" to false) }
        }
    }

    @Test fun settingsForAnUploadedDriverApplyToCustomConnectionsAndReachTheDriver() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("h2", h2JarBytes(), null, "admin")
            h.db.saveDriverSettings(
                "h2",
                mapOf(
                    "urlTemplate" to "jdbc:h2:mem:{database};MODE=Oracle;DB_CLOSE_DELAY=-1", "translator" to "ORACLE",
                    "extraProperties" to mapOf("IGNORECASE" to "TRUE"),
                ),
                0,
            )
            assertTrue(h.db.driverSettings().any { it["name"] == "h2" && it["builtIn"] == false })
            save(h, "plant", "driver" to "custom", "driverId" to "h2", "database" to "settingsdb", "maxActive" to 1, "allowBrowserWrites" to true)
            val view = h.db.connection("plant")
            assertEquals("jdbc:h2:mem:settingsdb;MODE=Oracle;DB_CLOSE_DELAY=-1", view["url"])
            assertEquals("ORACLE", view["translator"])
            assertEquals("Valid", view["status"], view["error"]?.toString())
            // IGNORECASE reached the driver as a connect property: the comparison ignores case.
            h.db.browse("plant", "CREATE TABLE T (NAME VARCHAR2(20))", 1)
            h.db.browse("plant", "INSERT INTO T VALUES ('abc')", 1)
            val rows = (h.db.browse("plant", "SELECT COUNT(*) FROM T WHERE NAME = 'ABC'", 5) as BrowseResult.Rows).rows
            assertEquals(1, rows.single()[0].toString().toDouble().toInt())
        }
    }

    @Test fun settingsAreCheckedWhenSaved() {
        DbHarness(tmp).use { h ->
            fun bad(name: String, vararg fields: Pair<String, Any?>) = assertThrows<QueryRejected>("$name ${fields.toList()}") { h.db.saveDriverSettings(name, mapOf(*fields), 0) }
            bad("oracle", "urlTemplate" to "jdbc:oracle:thin:@//{host}:{port}/{db}")
            bad("oracle", "urlTemplate" to "http://{host}")
            bad("oracle", "urlTemplate" to "jdbc:oracle:thin:@//{host}/{database}?user=sys")
            bad("oracle", "urlTemplate" to "jdbc:oracle:thin:@//{host} {database}")
            bad("oracle", "defaultPort" to 0)
            bad("oracle", "defaultPort" to "1521")
            bad("oracle", "translator" to "nope")
            bad("oracle", "driverId" to "not-uploaded")
            bad("oracle", "validationQuery" to "DELETE FROM x")
            bad("oracle", "extraProperties" to mapOf("password" to "x"))
            bad("oracle", "unknownField" to 1)
            bad("nothing-like-a-driver", "defaultPort" to 1)
            bad("postgres", "translator" to "ORACLE")
            bad("postgres", "driverId" to "x")
            h.db.saveDriverSettings("postgres", mapOf("defaultPort" to 6432), 0)
            assertEquals(6432, setting(h, "postgres", "defaultPort"))
            save(h, "pg", "driver" to "postgres", "host" to "db.example", "database" to "plant", "enabled" to false)
            assertEquals("jdbc:postgresql://db.example:6432/plant", h.db.connection("pg")["url"])
        }
    }

    @Test fun aSettingsResourceWrittenThroughTheConfigStoreIsPickedUpAndABadOneIsIgnored() {
        DbHarness(tmp).use { h ->
            fun put(name: String, spec: Map<String, Any?>) =
                h.store.put(org.oip.config.Scope.GATEWAY, org.oip.config.Resource(DRIVER_SETTINGS_KIND, org.oip.config.Metadata(name, DB_MODULE, version = 0), spec), "test")
            put("mariadb", mapOf("defaultPort" to 3307))
            assertEquals(3307, setting(h, "mariadb", "defaultPort"))
            // The schema refuses an out-of-range port; a template that passes the schema but is not a jdbc: url is dropped.
            assertThrows<org.oip.config.ValidationException> { put("mysql", mapOf("defaultPort" to 99999)) }
            put("mysql", mapOf("urlTemplate" to "http://{host}"))
            assertEquals("jdbc:mysql://{host}:{port}/{database}", setting(h, "mysql", "urlTemplate"))
        }
    }
}
