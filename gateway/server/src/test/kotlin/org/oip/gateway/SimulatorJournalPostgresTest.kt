package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.db.PasswordUpdate
import org.oip.security.DefaultRoles
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TAG_KIND
import org.oip.testinfra.SimPorts
import org.oip.testinfra.SimulatorStack
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * DAT-015 against the simulator stack's PostgreSQL: a gateway whose alarm journal names a PostgreSQL connection
 * writes the journal rows there (through the connection's pool) and reads them back through the journal API with
 * its source filter. Runs in the simulators CI job.
 */
@Tag("simulator")
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class SimulatorJournalPostgresTest {
    @TempDir lateinit var tmp: Path

    @Test fun aPostgresConnectionReceivesTheJournalRows() {
        SimulatorStack.fromEnvironment().use { stack ->
            val url = stack.endpoints.postgresJdbcUrl
            // The journal tables are shared by every run against this database; rows are told apart by source.
            val tag = "journal-" + System.nanoTime().toString().takeLast(8)
            tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  enabled: false\n")
            Gateway(tmp, port = 0).use { gw ->
                gw.start()
                gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
                gw.databases.saveConnection(
                    "sim-pg",
                    mapOf("driver" to "postgres", "url" to url, "username" to SimPorts.DB_USER),
                    0,
                    PasswordUpdate.Set(SimPorts.DB_PASSWORD),
                )
                val admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
                assertEquals(200, admin.put("/api/v1/alarms/journal/settings", """{"connection":"sim-pg"}""").status)
                until { admin.get(JOURNAL_DIAGNOSTICS).json["connectionStatus"] == "Valid" }
                assertEquals("postgresql", admin.get(JOURNAL_DIAGNOSTICS).json["database"])

                gw.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata(tag, TAGS_MODULE), mapOf(
                    "path" to "sim/$tag",
                    "type" to "memory",
                    "dataType" to "Double",
                    "value" to 0,
                    "alarms" to listOf(mapOf("name" to "High", "mode" to "AboveValue", "priority" to "High", "setpointA" to 100)),
                )), actor = "test")
                gw.tags.write("[default]sim/$tag", 150)
                gw.tags.write("[default]sim/$tag", 0)
                val source = "prov:default:/tag:sim/$tag:/alm:High"
                until {
                    DriverManager.getConnection(url, SimPorts.DB_USER, SimPorts.DB_PASSWORD).use { c ->
                        c.prepareStatement("SELECT COUNT(*) FROM alarm_events WHERE source = ?").use { ps ->
                            ps.setString(1, source)
                            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) == 2L }
                        }
                    }
                }
                val page = admin.get("/api/v1/alarms/journal?source=*$tag*&order=asc")
                assertEquals(200, page.status, page.body)
                @Suppress("UNCHECKED_CAST") val events = page.json["events"] as List<Map<String, Any?>>
                assertEquals(listOf(source, source), events.map { it["source"] })
                assertEquals(listOf("active", "clear"), events.map { it["eventType"] })
            }
        }
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!runCatching(check).getOrDefault(false)) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out")
            Thread.sleep(100)
        }
    }
}
