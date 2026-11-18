package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * DAT-015 on a running gateway: one set of named database connections, with their pools and status, used by the
 * alarm journal, history providers, named queries and report SQL sources. A missing or broken connection shows as a
 * fault on the journal's diagnostics and on a report run, never as a silent loss.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NamedConnectionUseTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        // The peer network listener is off so this test does not need its fixed port.
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
    }

    @AfterEach fun tearDown() = gw.close()

    private fun sqlite(name: String) {
        gw.databases.saveConnection(name, mapOf("driver" to "sqlite", "url" to "jdbc:sqlite:$name.db"), 0, PasswordUpdate.Unchanged)
        until("connection $name to be valid") { gw.databases.connection(name)["status"] == "Valid" }
    }

    private fun alarmTag() {
        gw.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("level", TAGS_MODULE), mapOf(
            "path" to "tank/level",
            "type" to "memory",
            "dataType" to "Double",
            "value" to 0,
            "alarms" to listOf(mapOf("name" to "High", "mode" to "AboveValue", "priority" to "High", "setpointA" to 100)),
        )), actor = "test")
    }

    private fun journalRows(connection: String): Long = gw.databases.withConnection(connection) { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM alarm_events").use { rs -> rs.next(); rs.getLong(1) } }
    }

    private fun builtInRows(): Long =
        DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("db/alarm-journal.sqlite").toAbsolutePath()}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM alarm_events").use { rs -> rs.next(); rs.getLong(1) } }
        }

    private fun diagnostics(): Map<String, Any?> = admin.get(JOURNAL_DIAGNOSTICS).also { assertEquals(200, it.status, it.body) }.json

    @Suppress("UNCHECKED_CAST")
    private fun usedBy(connection: String): List<Map<String, Any?>> =
        admin.get("/api/v1/db/connections/$connection").also { assertEquals(200, it.status, it.body) }.json["usedBy"] as List<Map<String, Any?>>

    private fun until(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!check()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    @Test fun theAlarmJournalWritesThroughANamedConnectionsPool() {
        sqlite("plant")
        assertEquals(emptyList<Any>(), usedBy("plant"))
        val set = admin.put("/api/v1/alarms/journal/settings", """{"connection":"plant"}""")
        assertEquals(200, set.status, set.body)
        assertEquals("plant", set.json["connection"])
        assertEquals("plant", admin.get("/api/v1/alarms/journal/settings").json["connection"])
        // The setting is the alarm/AlarmJournal/journal config resource, so backups and the config API carry it.
        assertEquals("plant", gw.store.get(org.oip.config.ResourceId("alarm", Scope.GATEWAY, "AlarmJournal", "journal"))!!.spec["connection"])
        until("the journal connection to be checked") { diagnostics()["connectionStatus"] == "Valid" }
        assertEquals("connection", diagnostics()["storage"])
        assertEquals("plant", diagnostics()["connection"])
        assertEquals("sqlite", diagnostics()["database"])
        assertEquals(listOf(mapOf("kind" to "alarmJournal", "name" to "journal")), usedBy("plant"))

        alarmTag()
        gw.tags.write("[default]tank/level", 150)
        until("the active row on the named connection") { journalRows("plant") == 1L }
        assertEquals(0L, builtInRows())
        val page = admin.get("/api/v1/alarms/journal")
        assertEquals(200, page.status, page.body)
        @Suppress("UNCHECKED_CAST") val events = page.json["events"] as List<Map<String, Any?>>
        assertEquals(listOf("prov:default:/tag:tank/level:/alm:High"), events.map { it["source"] })

        // Back to the built-in file.
        assertEquals(200, admin.put("/api/v1/alarms/journal/settings", """{"connection":null}""").status)
        assertEquals("built-in", diagnostics()["storage"])
        assertEquals(emptyList<Any>(), usedBy("plant"))
        assertProblem(admin.put("/api/v1/alarms/journal/settings", """{"connection":"Not A Name"}"""), 400, "bad-request")
    }

    @Test fun aMissingConnectionIsAJournalFaultAndTheRowsWaitForIt() {
        assertEquals(200, admin.put("/api/v1/alarms/journal/settings", """{"connection":"late"}""").status)
        // Reported before any alarm happens.
        until("the missing connection to be reported") { diagnostics()["connectionStatus"] == "Faulted" }
        assertTrue((diagnostics()["connectionError"] as String).contains("'late' not found"), diagnostics().toString())

        alarmTag()
        gw.tags.write("[default]tank/level", 150)
        until("the row to be held for retry") { diagnostics()["retrying"] == true }
        assertTrue((diagnostics()["lastWriteError"] as String).contains("not found"), diagnostics().toString())
        assertEquals(0, (diagnostics()["lostRows"] as Number).toInt())
        assertProblem(admin.get("/api/v1/alarms/journal"), 503, "journal-unavailable")

        sqlite("late")
        until("the held row to reach the connection") { diagnostics()["retrying"] == false && journalRows("late") == 1L }
        assertEquals("Valid", diagnostics()["connectionStatus"])
        assertEquals(0, (diagnostics()["lostRows"] as Number).toInt())
        assertEquals(200, admin.get("/api/v1/alarms/journal").status)

        // A connection disabled under the journal is a fault again, named on the diagnostics.
        val current = gw.databases.connection("late")
        gw.databases.saveConnection("late", mapOf("driver" to "sqlite", "url" to "jdbc:sqlite:late.db", "enabled" to false),
            (current["version"] as Number).toLong(), PasswordUpdate.Unchanged)
        gw.tags.write("[default]tank/level", 0)
        until("the disabled connection to be reported") { diagnostics()["connectionStatus"] == "Faulted" }
        assertTrue((diagnostics()["connectionError"] as String).contains("disabled"), diagnostics().toString())
    }

    @Test fun reportsHistoryAndQueriesShareTheConnectionAndAMissingOneFailsTheRun() {
        sqlite("plant")
        gw.databases.withConnection("plant") { c -> c.createStatement().use { it.execute("CREATE TABLE output (line TEXT, n INTEGER)") } }
        gw.databases.withConnection("plant") { c -> c.createStatement().use { it.execute("INSERT INTO output VALUES ('L1', 7)") } }
        fun report(name: String, connection: String) = admin.put("/api/v1/config/reporting/gateway/Report/$name", Json.write(mapOf(
            "apiVersion" to "oip/v1", "kind" to "Report",
            "metadata" to mapOf("name" to name, "module" to "reporting"),
            "spec" to mapOf(
                "title" to name,
                "dataSources" to listOf(mapOf("name" to "rows", "type" to "sql", "connection" to connection, "sql" to "SELECT line, n FROM output")),
                "layout" to mapOf("blocks" to listOf(mapOf("type" to "table", "dataset" to "rows"))),
            ),
        ))).also { assertEquals(201, it.status, it.body) }
        report("shift", "plant")
        report("orphan", "gone")
        gw.store.put(Scope.GATEWAY, Resource("HistoryProvider", Metadata("plant-history", "historian"), mapOf("connection" to "plant")))
        assertEquals(201, admin.post("/api/v1/db/queries", """{"name":"lines","connection":"plant","queryType":"query","sql":"SELECT line FROM output"}""").status)

        val ran = admin.post("/api/v1/reports/shift/run", "{}")
        assertEquals(200, ran.status, ran.body)
        @Suppress("UNCHECKED_CAST") val rows = ((ran.json["datasets"] as List<Map<String, Any?>>).single()["rows"]) as List<List<Any?>>
        assertEquals(listOf(listOf("L1", 7)), rows.map { row -> row.map { if (it is Number) it.toInt() else it } })
        assertEquals(
            setOf("historyProvider" to "plant-history", "report" to "shift", "namedQuery" to "lines"),
            usedBy("plant").map { it["kind"] to it["name"] }.toSet(),
        )

        // A report on a connection that does not exist fails with the connection named, and the run history says so.
        val failed = admin.post("/api/v1/reports/orphan/run", "{}")
        assertEquals(422, failed.status, failed.body)
        assertTrue(failed.body.contains("'gone' not found"), failed.body)
        @Suppress("UNCHECKED_CAST") val runs = admin.get("/api/v1/reports/orphan/runs").json["items"] as List<Map<String, Any?>>
        assertEquals("failure", runs.first()["status"])
    }

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertEquals("urn:oip:problem:$slug", r.json["type"])
    }
}
