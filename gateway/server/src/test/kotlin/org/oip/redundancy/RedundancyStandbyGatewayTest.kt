package org.oip.redundancy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmEventType
import org.oip.alarm.AlarmFilter
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.gateway.Gateway
import org.oip.gateway.Http
import org.oip.gateway.loginAdmin
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * One backup gateway whose master never answers. With a long startup allowance it stays
 * undecided, so it is a standby for the whole test. Checks the wiring in Gateway, not the pair protocol.
 */
class RedundancyStandbyGatewayTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun standbyKeepsLiveAlarmsWithoutJournalAndRejectsHttpEvents() {
        val gw = backup(tmp.resolve("standby"))
        try {
            assertFalse(gw.redundancy.isActive())
            val http = Http(gw.port())
            gw.loginAdmin(http)
            val stream = http.post("/api/v1/config/events/gateway/EventStream", STREAM)
            assertEquals(201, stream.status, stream.body)

            gw.store.put(Scope.GATEWAY, alarmTag(), actor = "test")
            gw.tags.write("[default]tank/level", 150.0)
            waitUntil { gw.alarms.query(AlarmFilter()).any { it.active && it.name == "High" } }
            assertEquals(0, gw.alarms.journal.records().size, "standby appended to the alarm journal")

            val rejected = Http(gw.port()).post("/system/eventstream/ingest", """{"n":1}""")
            assertEquals(409, rejected.status, rejected.body)

            // Taking control turns both back on.
            gw.redundancy.requestFailover()
            waitUntil { gw.redundancy.isActive() }
            gw.tags.write("[default]tank/level", 0.0)
            waitUntil { gw.alarms.journal.records().any { it.eventType == AlarmEventType.CLEAR } }
            val accepted = Http(gw.port()).post("/system/eventstream/ingest", """{"n":2}""")
            assertEquals(202, accepted.status, accepted.body)
        } finally {
            gw.close()
        }
    }

    @Test
    fun backupParksSyncedChartsAtStartup() {
        val dir = tmp.resolve("charts")
        val first = backup(dir)
        try {
            val http = Http(first.port())
            first.loginAdmin(http)
            val created = http.post("/api/v1/config/sfc/gateway/Chart", CHART)
            assertEquals(201, created.status, created.body)
        } finally {
            first.close()
        }
        // A run-always chart created on a standby has not run, so it is gated, not parked.
        val second = backup(dir)
        try {
            waitUntil { chartState(second) == "Starting" }
            Thread.sleep(200)
            assertEquals("Starting", chartState(second))
            second.redundancy.requestFailover()
            waitUntil { chartState(second) == "Running" }
        } finally {
            second.close()
        }
        // Restarting as a standby restores the running chart and parks it until this node takes over.
        val third = backup(dir)
        try {
            assertFalse(third.redundancy.isActive())
            waitUntil { chartState(third) == "RedundantInactive" }
            third.redundancy.requestFailover()
            waitUntil { chartState(third) == "Running" }
        } finally {
            third.close()
        }
    }

    private fun chartState(gw: Gateway): Any? =
        gw.charts.instances().firstOrNull { it["chart"] == "synced" }?.get("state")

    private fun backup(dir: Path): Gateway {
        dir.createDirectories()
        dir.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: false
            gatewayNetwork:
              name: backup
              port: 0
            redundancy:
              mode: backup
              standbyActivityLevel: warm
              startupAllowanceMillis: 600000
              masterHost: 127.0.0.1
              masterPort: 1
              connectionName: redundancy
            """.trimIndent(),
        )
        return Gateway(dir, port = 0).also { it.start() }
    }

    private fun alarmTag() = Resource(
        kind = "Tag",
        metadata = Metadata(name = "level", module = "tags"),
        spec = mapOf(
            "path" to "tank/level",
            "type" to "memory",
            "dataType" to "Double",
            "value" to 0,
            "alarms" to listOf(mapOf("name" to "High", "mode" to "AboveValue", "priority" to "High", "setpointA" to 100)),
        ),
    )

    private fun waitUntil(timeoutMs: Long = 10_000, body: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (body()) return
            Thread.sleep(30)
        }
        throw AssertionError("condition not met")
    }

    private companion object {
        val STREAM = """
            {"apiVersion":"oip/v1","kind":"EventStream","metadata":{"name":"ingest","module":"events"},"spec":{
              "source":{"type":"http"},
              "buffer":{"debounceMillis":0,"maxWaitMillis":0},
              "handlers":[{"type":"logger","message":"{event.data}"}]
            }}
        """.trimIndent()

        val CHART = """
            {"apiVersion":"oip/v1","kind":"Chart","metadata":{"name":"synced","module":"sfc"},"spec":{
              "executionMode":"runAlways",
              "redundancySync":true,
              "elements":[
                {"id":"begin","type":"begin"},
                {"id":"step","type":"action"},
                {"id":"go","type":"transition","expression":"false"},
                {"id":"end","type":"end"}
              ],
              "links":[
                {"from":"begin","to":"step"},
                {"from":"step","to":"go"},
                {"from":"go","to":"end"}
              ]
            }}
        """.trimIndent()
    }
}
