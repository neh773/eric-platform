package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.events.EVENTS_MODULE
import org.oip.events.STREAM_KIND
import org.oip.security.DefaultRoles
import org.oip.tags.TagCaller
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * system.project, system.net, system.secrets, system.roster, system.eventstream and system.groups run against the
 * gateway's own services, for gateway scripts only (doc source: FEATURES SCR-035, SCR-018, SCR-022, SCR-016,
 * SCR-011, SCR-010).
 */
class ScriptGatewayFunctionsTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
    }

    @AfterEach fun tearDown() = gw.close()

    private fun run(source: String): Map<String, Any?> {
        val result = gw.scripts.run(source.trimIndent() + "\n")
        assertTrue(result.ok, result.error)
        @Suppress("UNCHECKED_CAST")
        return result.value as Map<String, Any?>
    }

    @Test fun projectNamesCurrentProjectAndScan() {
        gw.projectService.create("plant-a", org.oip.config.ProjectService.Settings(), "test")
        val body = run(
            """
            names = system.project.getProjectNames()
            _result = {"names": names, "current": system.project.getProjectName()}
            """,
        )
        assertTrue((body["names"] as List<*>).contains("plant-a"), body.toString())
        assertEquals("gateway", body["current"])
        // A scan picks up a resource file edited outside the gateway.
        run("""system.roster.createRoster("scan-me", ["admin"])""" + "\n_result = {}")
        val file = tmp.toFile().walkTopDown().first { it.isFile && it.name.endsWith(".yaml") && it.name.contains("scan-me") }
        file.writeText(file.readText().replace("admin", "ada"))
        val scanned = run("system.project.requestScan()\n_result = {\"users\": system.roster.getRoster(\"scan-me\")[\"users\"]}")
        assertEquals(listOf("ada"), scanned["users"])
    }

    @Test fun netReportsHostAddressesRemoteGatewaysAndHttp() {
        gw.network.addOutgoing("peer-one", "127.0.0.1", 1, enabled = false, description = "unused")
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val reply = "got:$body".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val port = server.address.port
            val body = run(
                """
                remote = system.net.getRemoteServers()
                running = system.net.getRemoteServers(True)
                got = system.net.httpGet("http://127.0.0.1:$port/echo")
                posted = system.net.httpPost("http://127.0.0.1:$port/echo", "ping")
                _result = {"host": system.net.getHostName(), "ip": system.net.getIpAddress(), "ips": system.net.getIpAddresses(),
                           "remote": [r["name"] for r in remote], "running": len(running), "enabled": remote[0]["enabled"],
                           "get": got["body"], "post": posted["body"]}
                """,
            )
            assertEquals(java.net.InetAddress.getLocalHost().hostName.ifBlank { "localhost" }, body["host"])
            assertTrue((body["ips"] as List<*>).contains(body["ip"]))
            assertEquals(listOf("peer-one"), body["remote"])
            assertEquals(0, (body["running"] as Number).toInt())
            assertEquals(false, body["enabled"])
            assertEquals("got:", body["get"])
            assertEquals("got:ping", body["post"])
            val blocked = gw.scripts.run("system.net.httpGet('file:///etc/passwd')\n")
            assertFalse(blocked.ok)
        } finally {
            server.stop(0)
        }
    }

    @Test fun secretsAreWrittenListedAndDeletedButNeverReturned() {
        val plaintext = "Zq9-never-print-this-value"
        val body = run(
            """
            written = system.secrets.writeSecret("plant-db", "$plaintext", "database login")
            exists = system.secrets.secretExists("plant-db")
            listed = system.secrets.listSecrets()
            system.secrets.writeSecret("plant-db", "$plaintext-2", "rotated")
            _result = {"written": written, "exists": exists, "listed": listed}
            """,
        )
        assertFalse(body.toString().contains(plaintext), "a return value carries no secret")
        assertEquals("plant-db", (body["written"] as Map<*, *>)["name"])
        assertEquals(true, body["exists"])
        assertEquals(listOf("plant-db"), (body["listed"] as List<*>).map { (it as Map<*, *>)["name"] })
        assertEquals("$plaintext-2", gw.security.secrets.getText("plant-db"), "the store holds the rotated value")
        val bad = gw.scripts.run("system.secrets.writeSecret('Bad Name', '$plaintext')\n")
        assertFalse(bad.ok)
        assertFalse(bad.error!!.contains(plaintext), bad.error)
        val gone = run("""system.secrets.deleteSecret("plant-db")
_result = {"exists": system.secrets.secretExists("plant-db")}""")
        assertEquals(false, gone["exists"])
        val missing = gw.scripts.run("system.secrets.deleteSecret('plant-db')\n")
        assertFalse(missing.ok)
        assertTrue(missing.error!!.contains("not found"), missing.error)
    }

    @Test fun rostersAreManagedThroughTheNotificationConfig() {
        val body = run(
            """
            made = system.roster.createRoster("day-oncall", ["ada"], "weekday cover")
            system.roster.createRoster("empty")
            system.roster.setRosterUsers("empty", ["admin", "ada"])
            _result = {"made": made, "names": system.roster.getRosterNames(), "empty": system.roster.getRoster("empty")["users"]}
            """,
        )
        assertEquals(listOf("ada"), (body["made"] as Map<*, *>)["users"])
        assertEquals(listOf("day-oncall", "empty"), body["names"])
        assertEquals(listOf("admin", "ada"), body["empty"])
        val stored = gw.store.get(org.oip.config.ResourceId(org.oip.alarm.notify.NOTIFY_MODULE, Scope.GATEWAY, org.oip.alarm.notify.NotifyKinds.ROSTER, "day-oncall"))
        assertEquals(listOf("ada"), stored!!.spec["users"])
        for (bad in listOf("system.roster.createRoster('day-oncall')", "system.roster.setRosterUsers('empty', ['nobody'])", "system.roster.getRoster('nope')", "system.roster.createRoster('Bad Name')")) {
            assertFalse(gw.scripts.run("$bad\n").ok, bad)
        }
        run("""system.roster.deleteRoster("empty")
_result = {"names": system.roster.getRosterNames()}""").let { assertEquals(listOf("day-oncall"), it["names"]) }
    }

    @Test fun eventStreamsPublishAndDiagnose() {
        gw.store.put(Scope.GATEWAY, Resource(STREAM_KIND, Metadata("ear", EVENTS_MODULE), mapOf(
            "source" to mapOf("type" to "listener"),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "{event.data}")),
        )), "test")
        gw.store.put(Scope.GATEWAY, Resource(STREAM_KIND, Metadata("web", EVENTS_MODULE), mapOf(
            "source" to mapOf("type" to "http"),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "{event.data}")),
        )), "test")
        val body = run(
            """
            before = system.eventstream.diagnose("ear")["eventsReceived"]
            accepted = system.eventstream.publish("ear", {"n": 3}, {"line": "a"})
            after = system.eventstream.diagnose("ear")
            _result = {"streams": system.eventstream.getStreams(), "before": before, "accepted": accepted["accepted"],
                       "received": after["eventsReceived"], "source": after["source"]}
            """,
        )
        assertEquals(listOf("ear", "web"), body["streams"])
        assertEquals(0, (body["before"] as Number).toInt())
        assertEquals(true, body["accepted"])
        assertEquals(1, (body["received"] as Number).toInt())
        assertEquals("listener", body["source"])
        for (bad in listOf("system.eventstream.publish('web', 1)", "system.eventstream.publish('nope', 1)", "system.eventstream.diagnose('nope')")) {
            assertFalse(gw.scripts.run("$bad\n").ok, bad)
        }
    }

    @Test fun transactionGroupsAreListedToggledAndExecuted() {
        val http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        assertEquals(201, http.post("/api/v1/db/connections", """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite:groups.db"}""").status)
        assertEquals(201, http.post("/api/v1/db/queries", """{"name":"create","connection":"plant","queryType":"update","sql":"CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, speed DOUBLE PRECISION)"}""").status)
        assertEquals(200, http.post("/api/v1/db/queries/create/execute", """{"parameters":{}}""").status)
        gw.tags.configure(org.oip.tags.TagConfig(path = "Line/Speed", type = org.oip.tags.TagType.MEMORY, dataType = org.oip.tags.DataType.DOUBLE, value = 4.0, valueSet = true))
        val created = http.post("/api/v1/config/db-groups/gateway/TransactionGroup", """
            {"apiVersion":"oip/v1","kind":"TransactionGroup","metadata":{"name":"line-log","module":"db-groups","version":0},
             "spec":{"connection":"plant","table":"log","groupType":"historical","enabled":false,"execution":{"rateMillis":3600000},
                     "items":[{"name":"speed","source":"tag","tag":"Line/Speed","column":"speed"}]}}
        """.trimIndent())
        assertEquals(201, created.status, created.body)
        val body = run(
            """
            listed = [g["name"] for g in system.groups.getGroups()]
            off = system.groups.getGroupStatus("line-log")["enabled"]
            on = system.groups.setGroupEnabled("line-log", True)["enabled"]
            ran = system.groups.executeGroup("line-log")
            _result = {"listed": listed, "off": off, "on": on, "ran": ran}
            """,
        )
        assertEquals(listOf("line-log"), body["listed"])
        assertEquals(false, body["off"])
        assertEquals(true, body["on"])
        val ran = body["ran"] as Map<*, *>
        assertEquals(true, ran["executed"], ran.toString())
        assertFalse(gw.scripts.run("system.groups.getGroupStatus('nope')\n").ok)
        assertFalse(gw.scripts.run("system.groups.setGroupEnabled('line-log', 'yes')\n").ok)
    }

    @Test fun reportsAreRunAndDistributedThroughTheReportEngine() {
        gw.store.put(Scope.GATEWAY, Resource("Report", Metadata("shift", "reporting"),
            mapOf("title" to "Shift", "parameters" to listOf(mapOf("name" to "line", "type" to "string", "default" to "L1"),
                mapOf("name" to "range", "type" to "timeRange", "default" to mapOf("last" to "1h"))),
                "dataSources" to listOf(mapOf("name" to "speed", "type" to "tagHistory", "mode" to "raw",
                    "tags" to listOf(mapOf("path" to "Plant/Speed")), "range" to mapOf("param" to "range"))),
                "layout" to mapOf("blocks" to listOf(mapOf("type" to "table", "dataset" to "speed"))))))
        val delivered = mutableListOf<Pair<String, String>>()
        gw.reportSink = org.oip.reporting.ReportResultSink { def, result -> delivered += def.name to result.run.runId }
        val body = run(
            """
            names = system.report.getReportNames()
            kept = system.report.executeReport("shift", {"line": "L2"}, False)
            sent = system.report.executeReport("shift")
            _result = {"names": names, "kept": kept, "sent": sent}
            """,
        )
        assertEquals(listOf("shift"), body["names"])
        val kept = body["kept"] as Map<*, *>
        val sent = body["sent"] as Map<*, *>
        assertEquals(false, kept["delivered"])
        assertEquals(true, sent["delivered"])
        assertEquals(listOf("shift" to sent["runId"]), delivered, "only the distributed run reached the sink")
        assertEquals(2, gw.reports.history.runs("shift", 10).size, "both runs are in the run history")
        assertEquals("manual", gw.reports.history.runs("shift", 10).first().trigger.wire)
        gw.reportSink = org.oip.reporting.ReportResultSink { _, _ -> throw IllegalStateException("smtp down") }
        val failed = run("""_result = system.report.executeReport("shift")""")
        assertEquals(false, failed["delivered"])
        assertEquals("smtp down", failed["deliveryError"])
        val missing = gw.scripts.run("system.report.executeReport('nope')\n")
        assertFalse(missing.ok)
        assertTrue(missing.error!!.contains("not found"), missing.error)
    }

    @Test fun sessionScriptsAreRefusedTheGatewayOnlyFunctions() {
        val operator = TagCaller.User("ada", setOf("operator"), "default")
        val calls = listOf(
            "system.project.getProjectNames()", "system.project.requestScan()",
            "system.net.getHostName()", "system.net.getIpAddress()", "system.net.getIpAddresses()", "system.net.getRemoteServers()",
            "system.net.httpGet('http://127.0.0.1:1/')", "system.net.httpPost('http://127.0.0.1:1/', 'x')",
            "system.secrets.listSecrets()", "system.secrets.secretExists('a')", "system.secrets.writeSecret('a', 'hunter2-value')", "system.secrets.deleteSecret('a')",
            "system.roster.getRosterNames()", "system.roster.getRoster('a')", "system.roster.createRoster('a')", "system.roster.setRosterUsers('a', [])", "system.roster.deleteRoster('a')",
            "system.eventstream.publish('a', 1)", "system.eventstream.getStreams()", "system.eventstream.diagnose('a')",
            "system.groups.getGroups()", "system.groups.getGroupStatus('a')", "system.groups.setGroupEnabled('a', True)", "system.groups.executeGroup('a')",
            "system.report.getReportNames()", "system.report.executeReport('a')",
        )
        for (call in calls) {
            val refused = gw.scripts.runForUser("$call\n", "{}", operator)
            assertFalse(refused.ok, call)
            assertTrue(refused.error!!.contains("gateway script"), "$call: ${refused.error}")
            assertFalse(refused.error!!.contains("hunter2-value"), "$call leaked the value")
        }
        // The name of the running project says nothing about the gateway, so every scope may ask for it.
        val own = gw.scripts.runForUser("_result = system.project.getProjectName()\n", "{}", operator)
        assertTrue(own.ok, own.error)
        assertEquals("gateway", own.value)
        assertFalse(gw.store.list(org.oip.alarm.notify.NOTIFY_MODULE, Scope.GATEWAY, org.oip.alarm.notify.NotifyKinds.ROSTER).isNotEmpty())
        assertTrue(gw.security.secrets.list().isEmpty())
    }
}
