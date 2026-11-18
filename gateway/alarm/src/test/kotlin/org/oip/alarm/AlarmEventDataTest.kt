package org.oip.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.oip.tags.TagEngine
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Associated data on alarm events (ALA-039) and the alarm event object handed to scripts (ALA-034).
 * Doc source: FEATURES ALA-039, ALA-034.
 */
class AlarmEventDataTest {
    private class Clk(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun harness(body: (TagEngine, AlarmEngine, MutableList<AlarmNotice>) -> Unit) {
        val clock = Clk(Instant.parse("2026-01-01T00:00:00Z"))
        val tags = TagEngine(clock)
        val alarms = AlarmEngine(clock, AlarmJournal(Files.createTempFile("alarm-data", ".sqlite")))
        alarms.attach(tags)
        val seen = ArrayList<AlarmNotice>()
        alarms.listen { seen += it }
        try {
            body(tags, alarms, seen)
        } finally {
            alarms.close()
        }
    }

    private fun setup(tags: TagEngine) {
        tags.configure(org.oip.tags.TagConfig.fromSpec(linkedMapOf(
            "path" to "plant/line", "type" to "memory", "dataType" to "String", "value" to "Line 4",
        )))
        tags.configure(org.oip.tags.TagConfig.fromSpec(linkedMapOf(
            "path" to "tank/level", "type" to "memory", "dataType" to "Double", "value" to 0.0,
            "alarms" to listOf(linkedMapOf(
                "name" to "High", "mode" to "AboveValue", "setpointA" to 100, "priority" to "High",
                "associatedData" to linkedMapOf(
                    "Area" to "Mixing",
                    "Line" to linkedMapOf("tag" to "[default]plant/line"),
                    "Margin" to linkedMapOf("expression" to "{[default]tank/level} - 100"),
                ),
            )),
        )))
    }

    @Test fun associatedDataCarriesFixedTagAndExpressionValuesOnTheEvent() = harness { tags, alarms, seen ->
        setup(tags)
        tags.write("[default]tank/level", 150)
        val event = alarms.query().single()
        assertEquals("Mixing", event.associatedData["Area"])
        assertEquals("Line 4", event.associatedData["Line"])
        assertEquals(50.0, (event.associatedData["Margin"] as Number).toDouble())
        // The values are read when the event goes active: a later change does not rewrite them.
        tags.write("[default]plant/line", "Line 5")
        assertEquals("Line 4", alarms.query().single().associatedData["Line"])
        val notice = seen.first { it.trigger == "active" }
        assertEquals("Line 4", notice.associatedData["Line"])
        val row = alarms.journal.records().first { it.eventType == AlarmEventType.ACTIVE }
        assertEquals("Mixing", row.data["Area"])
        assertEquals("Line 4", row.data["Line"])
        assertEquals(150.0, row.data["eventValue"])
    }

    @Test fun theAlarmEventObjectExposesStateValueTimesAndConfigToScripts() = harness { tags, alarms, seen ->
        setup(tags)
        tags.write("[default]tank/level", 150)
        val active = seen.first { it.trigger == "active" }.scriptEvent()
        assertEquals("High", active["name"])
        assertEquals(2, active["stateCode"])
        assertEquals("Active and Unacknowledged", active["state"])
        assertEquals(true, active["isActive"])
        assertEquals(false, active["isAcked"])
        assertEquals(150.0, active["value"])
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), active["activeTime"])
        assertNull(active["clearTime"])
        @Suppress("UNCHECKED_CAST")
        val config = active["config"] as Map<String, Any?>
        assertEquals("ABOVE", config["mode"])
        assertEquals(100, config["setpointA"])
        assertEquals("High", config["priority"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("Mixing", (active["associatedData"] as Map<String, Any?>)["Area"])

        alarms.acknowledge(listOf(alarms.query().single().eventId), "ada", null)
        val acked = seen.first { it.trigger == "ack" }.scriptEvent()
        assertEquals(3, acked["stateCode"])
        assertEquals("ada", acked["ackUser"])
        tags.write("[default]tank/level", 10)
        val clear = seen.first { it.trigger == "clear" }.scriptEvent()
        assertEquals(1, clear["stateCode"])
        assertEquals(true, clear["isClear"])
    }
}
