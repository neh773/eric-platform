package org.oip.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.tags.Quality
import org.oip.tags.QualityCode
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Review-3 F3: alarm evaluation follows the shared quality model (SPEC §5). While the tag value or a bound
 * setpoint is not Good the alarm holds its state: no activation, no clear, and pending delays restart once
 * the data is Good again. A null operand is never "in condition", in any mode (DIFFERENCES 269).
 * Doc source: FEATURES ALA-032, ALA-041.
 */
class AlarmQualityTest {
    @Test fun equalityWithNoSetpointOnAWaitingOpcTagStaysClear() = run { _, tags, alarms ->
        tags.configure(TagConfig.fromSpec(opc("dev/state", listOf(mapOf("name" to "Eq", "mode" to "Equality", "priority" to "High")))))
        assertEquals(QualityCode.BAD_WAITING_FOR_INITIAL_DATA, tags.read("[default]dev/state").quality.code)
        alarms.poll()
        assertTrue(alarms.query().isEmpty(), "no setpoint and no value must not trip")
        assertTrue(alarms.journal.records().isEmpty())
    }

    @Test fun aGoodNullValueIsNotEqualToAMissingSetpoint() = run { _, tags, alarms ->
        tags.configure(TagConfig.fromSpec(opc("dev/state", listOf(mapOf("name" to "Eq", "mode" to "Equality", "priority" to "High")))))
        tags.updateRaw("[default]dev/state", null)
        assertEquals(QualityCode.GOOD, tags.read("[default]dev/state").quality.code)
        assertTrue(alarms.query().isEmpty())
        assertTrue(alarms.journal.records().isEmpty())
    }

    @Test fun badValueQualityNeitherActivatesNorClears() = run { _, tags, alarms ->
        tags.configure(TagConfig.fromSpec(opc("dev/level", listOf(mapOf("name" to "High", "mode" to "AboveValue", "priority" to "High", "setpointA" to 100)))))
        tags.updateRaw("[default]dev/level", 150.0, Quality(QualityCode.BAD_SENSOR_FAILURE))
        assertTrue(alarms.query().isEmpty(), "a Bad value above the setpoint does not activate")

        tags.updateRaw("[default]dev/level", 150.0)
        val id = alarms.query().single().eventId
        // Comms loss keeps the last value with a Bad quality: the alarm holds, it does not clear.
        tags.reportCommsLoss("[default]dev/level", true)
        assertEquals(QualityCode.BAD_NOT_CONNECTED, tags.read("[default]dev/level").quality.code)
        assertTrue(alarms.query().single().active)
        tags.updateRaw("[default]dev/level", 10.0, Quality(QualityCode.BAD_SENSOR_FAILURE))
        val held = alarms.query().single()
        assertEquals(id, held.eventId)
        assertTrue(held.active, "a Bad value below the setpoint does not clear the alarm")
        assertEquals(listOf(AlarmEventType.ACTIVE), alarms.journal.records().map { it.eventType })

        tags.updateRaw("[default]dev/level", 10.0)
        assertTrue(!alarms.query().single().active, "Good data clears it as usual")
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR), alarms.journal.records().map { it.eventType })
    }

    @Test fun aBadSetpointTagDoesNotClearAnActiveAlarm() = run { _, tags, alarms ->
        tags.configure(TagConfig.fromSpec(opc("dev/limit", emptyList())))
        tags.updateRaw("[default]dev/limit", 100.0)
        tags.configure(TagConfig.fromSpec(opc("dev/level", listOf(mapOf(
            "name" to "High", "mode" to "AboveValue", "priority" to "High", "setpointA" to mapOf("tag" to "[default]dev/limit"),
        )))))
        tags.updateRaw("[default]dev/level", 150.0)
        val id = alarms.query().single { it.active }.eventId

        tags.reportCommsLoss("[default]dev/limit", true)
        tags.updateRaw("[default]dev/level", 151.0)
        assertTrue(alarms.query().single().active, "a non-Good setpoint holds the state")

        // Back with Good quality but no value: a bound setpoint that reads null is not a limit either.
        tags.updateRaw("[default]dev/limit", null)
        assertEquals(QualityCode.GOOD, tags.read("[default]dev/limit").quality.code)
        tags.updateRaw("[default]dev/level", 152.0)
        val held = alarms.query().single()
        assertEquals(id, held.eventId)
        assertTrue(held.active, "a null setpoint does not clear the alarm")
        assertEquals(listOf(AlarmEventType.ACTIVE), alarms.journal.records().map { it.eventType })
    }

    @Test fun anOnDelayRestartsAfterBadData() = run { clock, tags, alarms ->
        tags.configure(TagConfig.fromSpec(opc("dev/level", listOf(mapOf(
            "name" to "High", "mode" to "AboveValue", "priority" to "High", "setpointA" to 100, "timeOnDelaySeconds" to 10,
        )))))
        tags.updateRaw("[default]dev/level", 150.0)
        clock.advance(Duration.ofSeconds(6))
        tags.updateRaw("[default]dev/level", 150.0, Quality(QualityCode.BAD_NOT_CONNECTED))
        clock.advance(Duration.ofSeconds(6))
        alarms.poll()
        assertTrue(alarms.query().isEmpty(), "the delay does not run on Bad data")
        tags.updateRaw("[default]dev/level", 151.0)
        clock.advance(Duration.ofSeconds(9))
        alarms.poll()
        assertTrue(alarms.query().isEmpty(), "the delay restarts when the data is Good again")
        clock.advance(Duration.ofSeconds(1))
        alarms.poll()
        assertTrue(alarms.query().single().active)
    }

    private fun opc(path: String, alarms: List<Map<String, Any?>>): Map<String, Any?> {
        val spec = linkedMapOf<String, Any?>(
            "path" to path,
            "type" to "opc",
            "dataType" to "Double",
            "opcServer" to "sim",
            "opcItemPath" to path,
        )
        if (alarms.isNotEmpty()) spec["alarms"] = alarms
        return spec
    }

    private class ManualClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        override fun millis(): Long = now.toEpochMilli()
        fun advance(duration: Duration) { now = now.plus(duration) }
    }

    private fun run(body: (ManualClock, TagEngine, AlarmEngine) -> Unit) {
        val clock = ManualClock(Instant.parse("2026-01-01T00:00:00Z"))
        val tags = TagEngine(clock)
        val alarms = AlarmEngine(clock, AlarmJournal(Files.createTempFile("alarm-quality", ".sqlite")))
        alarms.attach(tags)
        try {
            body(clock, tags, alarms)
        } finally {
            alarms.close()
        }
    }
}
