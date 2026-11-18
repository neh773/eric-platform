package org.oip.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.UdtDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.createDirectories

/**
 * Alarm state machine, delays, deadband, shelving, UDT members, and bound attributes.
 */
class AlarmEngineTest {
    @Test fun activeUnackedThenAckedThenClearAckedDropsOff() = run { clock, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val active = alarms.query().single()
        assertEquals("Active and Unacknowledged", active.state)
        assertEquals(2, active.stateCode)
        assertEquals("High", active.priority)
        assertEquals(3, active.priorityCode)
        assertEquals("prov:default:/tag:tank/level:/alm:High", active.source)
        assertEquals("tank/level/High", active.displayPath)
        assertEquals(150, (active.value as Number).toInt())
        alarms.acknowledge(listOf(active.eventId), "op", null)
        val acked = alarms.query().single()
        assertEquals("Active and Acknowledged", acked.state)
        assertEquals(3, acked.stateCode)
        assertEquals("op", acked.ackUser)
        tags.write("[default]tank/level", 0)
        assertTrue(alarms.query().isEmpty())
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.ACKNOWLEDGED, AlarmEventType.CLEAR), types(alarms))
    }

    @Test fun clearUnackedStaysUntilAck() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        tags.write("[default]tank/level", 0)
        val cleared = alarms.query().single()
        assertEquals("Cleared and Unacknowledged", cleared.state)
        assertEquals(0, cleared.stateCode)
        assertFalse(cleared.active)
        assertFalse(cleared.acknowledged)
        alarms.acknowledge(listOf(cleared.eventId), "op", null)
        assertTrue(alarms.query().isEmpty())
        val ack = alarms.journal.records().last()
        assertEquals(AlarmEventType.ACKNOWLEDGED, ack.eventType)
        assertTrue(ack.eventFlags and AlarmEventFlags.CLEARED != 0)
    }

    @Test fun clearedEventDoesNotBecomeActive() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val first = alarms.query().single().eventId
        tags.write("[default]tank/level", 0)
        tags.write("[default]tank/level", 160)
        val live = alarms.query()
        assertEquals(2, live.size)
        val again = live.single { it.active }
        val prior = live.single { !it.active }
        assertEquals(first, prior.eventId)
        assertNotEquals(first, again.eventId)
        assertEquals("Active and Unacknowledged", again.state)
        assertEquals("Cleared and Unacknowledged", prior.state)
    }

    @Test fun autoAckClearsOutOfTheLiveList() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("ackMode" to "Auto")))
        tags.write("[default]tank/level", 150)
        assertEquals("Active and Unacknowledged", alarms.query().single().state)
        tags.write("[default]tank/level", 0)
        assertTrue(alarms.query().isEmpty())
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR, AlarmEventType.ACKNOWLEDGED), types(alarms))
    }

    @Test fun unusedAckModeIsBornAcknowledged() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("ackMode" to "Unused")))
        tags.write("[default]tank/level", 150)
        val active = alarms.query().single()
        assertEquals("Active and Acknowledged", active.state)
        assertTrue(active.acknowledged)
        assertNull(active.ackUser)
        tags.write("[default]tank/level", 0)
        assertTrue(alarms.query().isEmpty())
        val born = alarms.journal.records().first()
        assertEquals(AlarmEventType.ACTIVE, born.eventType)
        assertTrue(born.eventFlags and AlarmEventFlags.ACKNOWLEDGED != 0)
    }

    @Test fun onDelayAndCancellation() = run { clock, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("timeOnDelaySeconds" to 10)))
        tags.write("[default]tank/level", 150)
        assertTrue(alarms.query().isEmpty())
        clock.advance(Duration.ofSeconds(9))
        alarms.poll()
        assertTrue(alarms.query().isEmpty())
        tags.write("[default]tank/level", 0)
        clock.advance(Duration.ofSeconds(30))
        alarms.poll()
        assertTrue(alarms.query().isEmpty())
        tags.write("[default]tank/level", 150)
        clock.advance(Duration.ofSeconds(10))
        alarms.poll()
        assertEquals("Active and Unacknowledged", alarms.query().single().state)
    }

    @Test fun offDelayHoldsTheSameEventWhenTheConditionReturns() = run { clock, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("timeOffDelaySeconds" to 10)))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.write("[default]tank/level", 0)
        clock.advance(Duration.ofSeconds(5))
        alarms.poll()
        assertEquals(id, alarms.query().single().eventId)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 150)
        clock.advance(Duration.ofSeconds(30))
        alarms.poll()
        val still = alarms.query().single()
        assertEquals(id, still.eventId)
        assertTrue(still.active)
        assertEquals(listOf(AlarmEventType.ACTIVE), types(alarms))
        tags.write("[default]tank/level", 0)
        clock.advance(Duration.ofSeconds(10))
        alarms.poll()
        val cleared = alarms.query().single()
        assertEquals(id, cleared.eventId)
        assertEquals("Cleared and Unacknowledged", cleared.state)
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR), types(alarms))
    }

    @Test fun absoluteDeadbandMustClearTheSetpoint() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High", setpoint = 100, deadband = 5))
        tags.write("[default]tank/level", 101)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 96)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 95)
        assertEquals("Cleared and Unacknowledged", alarms.query().single().state)
    }

    @Test fun percentDeadbandUsesEngineeringSpan() = run { _, tags, alarms ->
        tags.memory(
            "tank/level", 0.0,
            above("High", setpoint = 100, deadband = 10, extra = mapOf("deadbandMode" to "Percent")),
            engLow = 0.0, engHigh = 200.0,
        )
        tags.write("[default]tank/level", 101)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 81)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 80)
        assertFalse(alarms.query().single().active)
    }

    @Test fun deadbandOffIgnoresTheAmount() = run { _, tags, alarms ->
        tags.memory(
            "tank/level", 0.0,
            above("High", setpoint = 100, deadband = 50, extra = mapOf("deadbandMode" to "Off")),
        )
        tags.write("[default]tank/level", 100.1)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 100)
        assertFalse(alarms.query().single().active)
    }

    @Test fun inclusiveAboveAndBelow() = run { _, tags, alarms ->
        tags.memory("tank/hi", 0.0, above("High", setpoint = 100, inclusive = true))
        tags.write("[default]tank/hi", 100)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/hi", 99.9)
        assertFalse(alarms.query().single().active)

        tags.memory("tank/lo", 100.0, below("Low", setpoint = 30, inclusive = true, deadband = 2))
        tags.write("[default]tank/lo", 30)
        assertEquals("Low", alarms.query().single { it.name == "Low" }.name)
        tags.write("[default]tank/lo", 32)
        assertTrue(alarms.query().single { it.name == "Low" }.active)
        tags.write("[default]tank/lo", 33)
        assertFalse(alarms.query().single { it.name == "Low" }.active)
    }

    @Test fun equalityUsesDeadbandOnlyAfterItLatches() = run { _, tags, alarms ->
        tags.memory("tank/code", 0.0, listOf(alarm("Equal", "Equality", setpoint = 10, deadband = 5)))
        tags.write("[default]tank/code", 12)
        assertTrue(alarms.query().isEmpty())
        tags.write("[default]tank/code", 10)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/code", 14)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/code", 16)
        assertFalse(alarms.query().single().active)
    }

    @Test fun bitAndBitOnZero() = run { _, tags, alarms ->
        tags.memory(
            "plc/word", 0.0,
            listOf(
                alarm("Bit7", "Bit", extra = mapOf("bitPosition" to 7, "priority" to "Low")),
                alarm("Bit0", "Bit", extra = mapOf("bitPosition" to 0, "priority" to "Low")),
                alarm("Bit0Low", "Bit", extra = mapOf("bitPosition" to 0, "bitOnZero" to true, "priority" to "Medium")),
            ),
            dataType = "Int32",
        )
        tags.write("[default]plc/word", 142)
        val names = alarms.query().map { it.name }.toSet()
        assertEquals(setOf("Bit7", "Bit0Low"), names)
        assertFalse(names.contains("Bit0"))
    }

    @Test fun outOfRangeUsesEngineeringLimitsAndDeadband() = run { _, tags, alarms ->
        tags.memory(
            "tank/level", 50.0,
            listOf(alarm("Range", "OutOfEngRange", deadband = 1, extra = mapOf("priority" to "High"))),
            engLow = 0.0, engHigh = 100.0,
        )
        assertTrue(alarms.query().isEmpty())
        tags.write("[default]tank/level", -1)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 0.5)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 1)
        assertFalse(alarms.query().single().active)
        tags.write("[default]tank/level", 101)
        assertTrue(alarms.query().single { it.active }.active)
    }

    @Test fun outsideSetpoints() = run { _, tags, alarms ->
        tags.memory(
            "tank/level", 50.0,
            listOf(alarm(
                "Outside", "OutsideValues",
                extra = mapOf(
                    "setpointA" to 20, "setpointB" to 80,
                    "inclusiveA" to true, "inclusiveB" to true,
                    "deadband" to 3, "priority" to "High",
                ),
            )),
        )
        tags.write("[default]tank/level", 80)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 77)
        assertTrue(alarms.query().single().active)
        tags.write("[default]tank/level", 76)
        assertFalse(alarms.query().single().active)
        tags.write("[default]tank/level", 20)
        val live = alarms.query()
        assertEquals(2, live.size)
        assertTrue(live.single { it.active }.active)
        assertFalse(live.single { !it.active }.active)
    }

    @Test fun shelveHidesAndExpiryRestoresTheSameEvent() = run { clock, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        val source = alarms.query().single().source
        alarms.shelve(listOf(source), Duration.ofSeconds(60))
        assertTrue(alarms.query().isEmpty())
        assertTrue(alarms.isShelved(source))
        clock.advance(Duration.ofSeconds(59))
        alarms.poll()
        assertTrue(alarms.query().isEmpty())
        clock.advance(Duration.ofSeconds(1))
        alarms.poll()
        val back = alarms.query().single()
        assertEquals(id, back.eventId)
        assertTrue(back.active)
        // The shelve itself is an operator row (C-14); no event row is written while shelved.
        assertEquals(listOf(AlarmEventType.ACTIVE, JournalEventTypes.SHELVED), types(alarms))
    }

    @Test fun shelfExpiryOpensAnEventThatStartedWhileShelved() = run { clock, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        val source = "prov:default:/tag:tank/level:/alm:High"
        alarms.shelve(listOf(source), Duration.ofSeconds(60))
        tags.write("[default]tank/level", 150)
        assertTrue(alarms.query().isEmpty())
        assertEquals(listOf(JournalEventTypes.SHELVED), types(alarms))
        clock.advance(Duration.ofSeconds(60))
        alarms.poll()
        assertEquals("Active and Unacknowledged", alarms.query().single().state)
        assertEquals(listOf(JournalEventTypes.SHELVED, AlarmEventType.ACTIVE), types(alarms))
        assertEquals(0, alarms.journal.records().last().eventFlags and AlarmEventFlags.SHELVED)
    }

    /**
     * Review-3 F7: an event journaled before the shelf and cleared during it is closed in the journal (a clear row
     * flagged Shelved) and, still unacknowledged, comes back to the operator when the shelf ends.
     */
    @Test fun clearDuringShelfClosesTheJournaledEventAndKeepsItForAck() = run { clock, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val event = alarms.query().single()
        alarms.shelve(listOf(event.source), Duration.ofSeconds(60))
        tags.write("[default]tank/level", 0)
        assertTrue(alarms.query().isEmpty(), "hidden while shelved")
        clock.advance(Duration.ofSeconds(60))
        alarms.poll()
        val back = alarms.query().single()
        assertEquals(event.eventId, back.eventId)
        assertEquals("Cleared and Unacknowledged", back.state)
        assertEquals(listOf(AlarmEventType.ACTIVE, JournalEventTypes.SHELVED, AlarmEventType.CLEAR), types(alarms))
    }

    @Test fun unshelveReevaluatesImmediately() = run { clock, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        val source = "prov:default:/tag:tank/level:/alm:High"
        alarms.shelve(listOf(source), Duration.ofHours(1))
        tags.write("[default]tank/level", 150)
        assertTrue(alarms.query().isEmpty())
        alarms.unshelve(listOf(source))
        assertTrue(alarms.query().single().active)
        assertFalse(alarms.isShelved(source, clock.now))
    }

    @Test fun shelvingCanBeDisallowed() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("shelvingAllowed" to false)))
        tags.write("[default]tank/level", 150)
        val source = alarms.query().single().source
        assertThrows(IllegalArgumentException::class.java) { alarms.shelve(listOf(source), Duration.ofSeconds(10)) }
        assertEquals(1, alarms.query().size)
    }

    @Test fun ackNotesAreRequiredWhenConfigured() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("ackNotesReqd" to true)))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        assertThrows(IllegalArgumentException::class.java) { alarms.acknowledge(listOf(id), "op", "  ") }
        assertFalse(alarms.query().single().acknowledged)
        alarms.acknowledge(listOf(id), "op", "checked the tank")
        assertEquals("checked the tank", alarms.query().single().ackNotes)
    }

    @Test fun liveEventLimitSystemAcksTheOldest() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        repeat(6) {
            tags.write("[default]tank/level", 150)
            tags.write("[default]tank/level", 0)
        }
        val live = alarms.query()
        assertEquals(5, live.size)
        assertTrue(live.all { !it.active && !it.acknowledged })
        val systemAcks = alarms.journal.records().count { it.eventFlags and AlarmEventFlags.SYSTEM_ACK != 0 }
        assertEquals(1, systemAcks)
    }

    @Test fun disabledBindingDoesNotEvaluate() = run { _, tags, alarms ->
        tags.memory("plant/running", 0.0, emptyList(), dataType = "Boolean")
        tags.write("[default]plant/running", false)
        tags.memory(
            "tank/level", 0.0,
            above("High", extra = mapOf("enabled" to mapOf("tag" to "[default]plant/running"))),
        )
        tags.write("[default]tank/level", 150)
        assertTrue(alarms.query().isEmpty())
        tags.write("[default]plant/running", true)
        assertTrue(alarms.query().single().active)
        // A-1: an alarm that becomes disabled clears its live event (a system clear), the same as a
        // config save that disables it. It is not evaluated again while disabled.
        tags.write("[default]plant/running", false)
        assertFalse(alarms.query().single().active)
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR), types(alarms))
        tags.write("[default]tank/level", 160)
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR), types(alarms))
    }

    @Test fun setpointTagBinding() = run { _, tags, alarms ->
        tags.memory("limits/high", 100.0, emptyList())
        tags.memory(
            "tank/level", 90.0,
            above("High", extra = mapOf("setpointA" to mapOf("tag" to "[default]limits/high"))),
        )
        assertTrue(alarms.query().isEmpty())
        tags.write("[default]limits/high", 80)
        val alarm = alarms.query().single()
        assertTrue(alarm.active)
        assertEquals(80, (alarm.setpointA as Number).toInt())
    }

    @Test fun expressionEnableBinding() = run { _, tags, alarms ->
        tags.memory("plant/running", 0.0, emptyList(), dataType = "Boolean")
        tags.write("[default]plant/running", false)
        tags.memory(
            "tank/level", 150.0,
            above(
                "High",
                extra = mapOf("enabled" to mapOf("expression" to "{[default]plant/running}")),
            ),
        )
        assertTrue(alarms.query().isEmpty())
        tags.write("[default]plant/running", true)
        assertTrue(alarms.query().single().active)
    }

    @Test fun udtMemberAlarmParameterBindingAndOverride() = run { _, tags, alarms ->
        tags.configureUdt(UdtDefinition.fromSpec(mapOf(
            "path" to "Types/Motor",
            "parameters" to listOf(
                mapOf("name" to "LowAmps", "dataType" to "Double", "default" to 25),
                mapOf("name" to "MotorNumber", "dataType" to "String", "default" to "?"),
            ),
            "members" to listOf(mapOf(
                "name" to "Amps",
                "type" to "memory",
                "dataType" to "Double",
                "value" to 40,
                "alarms" to listOf(mapOf(
                    "name" to "Low Amps",
                    "mode" to "BelowValue",
                    "priority" to "High",
                    "setpointA" to mapOf("parameter" to "LowAmps"),
                    "inclusiveA" to true,
                    "displayPath" to "Motor {MotorNumber}",
                )),
            )),
        )))
        tags.configure(TagConfig.fromSpec(mapOf(
            "path" to "Line/Motor1",
            "type" to "udt",
            "typeId" to "Types/Motor",
            "parameters" to mapOf("LowAmps" to 30, "MotorNumber" to "7"),
        )))
        tags.configure(TagConfig.fromSpec(mapOf(
            "path" to "Line/Motor2",
            "type" to "udt",
            "typeId" to "Types/Motor",
            "parameters" to mapOf("LowAmps" to 10, "MotorNumber" to "8"),
            "overrides" to mapOf(
                "Amps" to mapOf(
                    "value" to 80,
                    "alarms" to listOf(mapOf(
                        "name" to "Low Amps",
                        "mode" to "BelowValue",
                        "priority" to "Critical",
                        "setpointA" to 50,
                        "inclusiveA" to true,
                    )),
                ),
            ),
        )))
        assertTrue(alarms.query().isEmpty())
        tags.write("[default]Line/Motor1/Amps", 30)
        val motor1 = alarms.query().single()
        assertEquals("prov:default:/tag:Line/Motor1/Amps:/alm:Low Amps", motor1.source)
        assertEquals("Motor 7", motor1.displayPath)
        assertEquals(30, (motor1.setpointA as Number).toInt())
        tags.write("[default]Line/Motor1/Amps", 40)
        val cleared = alarms.query().single()
        assertFalse(cleared.active)
        assertFalse(cleared.acknowledged)
        tags.write("[default]Line/Motor2/Amps", 40)
        val motor2 = alarms.query().single { it.active }
        assertEquals("Critical", motor2.priority)
        assertEquals(50, (motor2.setpointA as Number).toInt())
        assertTrue(motor2.source.contains("Line/Motor2/Amps"))
    }

    @Test fun priorityFilterAndDiagnosticIsNotJournaled() = run { _, tags, alarms ->
        tags.memory(
            "tank/level", 0.0,
            listOf(
                alarm("Diag", "AboveValue", setpoint = 100, extra = mapOf("priority" to "Diagnostic")),
                alarm("Crit", "AboveValue", setpoint = 200, extra = mapOf("priority" to "Critical")),
            ),
        )
        tags.write("[default]tank/level", 150)
        assertEquals(setOf("Diag"), alarms.query().map { it.name }.toSet())
        assertTrue(alarms.journal.records().none { it.source.endsWith("Diag") })
        tags.write("[default]tank/level", 250)
        assertEquals(setOf("Crit"), alarms.query(AlarmFilter(minPriority = AlarmPriority.CRITICAL)).map { it.name }.toSet())
        assertEquals(1, alarms.journal.records().count { it.eventType == AlarmEventType.ACTIVE })
    }

    @Test fun customDisplayPathAndSourceFilter() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("displayPath" to "Tank High")))
        tags.write("[default]tank/level", 150)
        assertEquals("Tank High", alarms.query().single().displayPath)
        assertEquals(1, alarms.query(AlarmFilter(source = "*tank/level:/alm:High")).size)
        assertEquals(1, alarms.query(AlarmFilter(displayPath = "Tank*")).size)
        assertTrue(alarms.query(AlarmFilter(source = "prov:other:*")).isEmpty())
    }

    @Test fun sourcePathWildcardsMatchThePublishedExamples() {
        val exact = "prov:tagProvider:/tag:Inputs/PS_1:/alm:MyAlarm"
        assertTrue(wildcard("*PS_1:/alm:MyAlarm", exact))
        assertTrue(wildcard("*PS_1:/alm:MyAlarm", "prov:tagProvider:/tag:anotherFolder/different_Path/PS_1:/alm:MyAlarm"))
        assertTrue(wildcard("prov:tagProvider:/tag:PS_*", "prov:tagProvider:/tag:PS_1:/alm:MyAlarm1"))
        assertTrue(wildcard("*MyAlarm*", exact))
        assertFalse(wildcard("prov:tagProvider:/tag:PS_*", "prov:other:/tag:PS_1:/alm:MyAlarm1"))
        assertTrue(wildcardMatch("*PS_1:/alm:MyAlarm,*missing", exact))
        assertTrue(wildcardMatch("A*,*Z", "Apple"))
        assertTrue(wildcardMatch("A*,*Z", "QUIZ"))
        assertFalse(wildcardMatch("A*,*Z", "Zoo"))
    }

    @Test fun journalSurvivesRestart(@TempDir dir: Path) {
        val clock = ManualClock(Instant.parse("2026-01-01T00:00:00Z"))
        val file = dir.resolve("db").createDirectories().resolve("alarm-journal.sqlite")
        val tags = TagEngine(clock)
        val first = AlarmEngine(clock, AlarmJournal(file))
        first.attach(tags)
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = first.query().single().eventId
        first.close()
        val second = AlarmJournal(file)
        try {
            val rows = second.records()
            assertEquals(1, rows.size)
            assertEquals(id, rows.single().eventId)
            assertEquals(AlarmEventType.ACTIVE, rows.single().eventType)
            assertEquals("prov:default:/tag:tank/level:/alm:High", rows.single().source)
            assertEquals(150.0, rows.single().data["eventValue"])
        } finally {
            second.close()
        }
    }

    // A-1: saving tag config again. An alarm keeps its state when its source (provider, tag path and
    // alarm name) is unchanged. Removing it, or a change that makes it false, clears the live event.

    @Test fun resaveWithTheSameAlarmKeepsTheActiveEvent() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.memory("tank/level", 0.0, above("High"))
        tags.memory("tank/level", 0.0, above("High"), engHigh = 500.0)
        val live = alarms.query().single()
        assertEquals(id, live.eventId)
        assertEquals("Active and Unacknowledged", live.state)
        assertEquals(listOf(AlarmEventType.ACTIVE), types(alarms))
        // The kept event still clears normally afterwards.
        tags.write("[default]tank/level", 0)
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR), types(alarms))
        assertEquals(listOf(id, id), alarms.journal.records().map { it.eventId })
    }

    @Test fun resaveKeepsAcknowledgementAndShelf() = run { _, tags, alarms ->
        val source = "prov:default:/tag:tank/level:/alm:High"
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        alarms.acknowledge(listOf(id), "op", "seen")
        alarms.shelve(listOf(source), Duration.ofMinutes(10), user = "sup")
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("notes" to "check the pump")))
        assertTrue(alarms.isShelved(source))
        val shelved = alarms.shelved().single()
        assertEquals("sup", shelved.shelvedBy)
        assertEquals(id, shelved.event!!.eventId)
        assertEquals("Active and Acknowledged", shelved.event!!.state)
        assertEquals("op", shelved.event!!.ackUser)
        assertEquals("check the pump", shelved.event!!.notes)
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.ACKNOWLEDGED, JournalEventTypes.SHELVED), types(alarms))
    }

    @Test fun resaveThatAddsAnotherAlarmLeavesTheFirstAlone() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.memory("tank/level", 0.0, above("High") + above("HighHigh", setpoint = 200))
        assertEquals(listOf(id), alarms.query().map { it.eventId })
        assertEquals(listOf(AlarmEventType.ACTIVE), types(alarms))
        tags.write("[default]tank/level", 250)
        assertEquals(2, alarms.query().size)
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.ACTIVE), types(alarms))
    }

    @Test fun resaveWithASetpointThatStillHoldsKeepsTheEvent() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.memory("tank/level", 0.0, above("High", setpoint = 120))
        assertEquals(id, alarms.query().single().eventId)
        assertEquals(listOf(AlarmEventType.ACTIVE), types(alarms))
    }

    @Test fun resaveWithASetpointThatNoLongerHoldsClearsTheSameEvent() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.memory("tank/level", 0.0, above("High", setpoint = 200))
        val cleared = alarms.query().single()
        assertEquals(id, cleared.eventId)
        assertEquals("Cleared and Unacknowledged", cleared.state)
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR), types(alarms))
        val clear = alarms.journal.records().last()
        assertEquals(id, clear.eventId)
        assertEquals(0, clear.eventFlags and AlarmEventFlags.SYSTEM, "a false condition is an ordinary clear")
        assertEquals(200L, clear.data["setpointA"])
        alarms.acknowledge(listOf(id), "op", null)
        assertTrue(alarms.query().isEmpty())
    }

    @Test fun resaveThatDisablesTheAlarmClearsIt() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("enabled" to false)))
        val cleared = alarms.query().single()
        assertEquals(id, cleared.eventId)
        assertEquals("Cleared and Unacknowledged", cleared.state)
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR), types(alarms))
        val clear = alarms.journal.records().last()
        assertTrue(clear.eventFlags and AlarmEventFlags.SYSTEM != 0, "disabling is a system clear")
        alarms.acknowledge(listOf(id), "op", null)
        assertTrue(alarms.query().isEmpty())
        // Enabled again with the condition still true: a new event.
        tags.memory("tank/level", 0.0, above("High"))
        val again = alarms.query().single()
        assertTrue(again.active)
        assertNotEquals(id, again.eventId)
    }

    @Test fun resaveThatRemovesTheAlarmClearsAndAcknowledgesIt() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.memory("tank/level", 0.0, emptyList())
        assertTrue(alarms.query().isEmpty())
        assertRetired(alarms, id)
    }

    @Test fun removingTheTagClearsAndAcknowledgesItsAlarm() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.remove("[default]tank/level")
        assertTrue(alarms.query().isEmpty())
        assertRetired(alarms, id)
    }

    @Test fun removingAnAcknowledgedAlarmWritesOnlyAClear() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        alarms.acknowledge(listOf(id), "op", null)
        tags.memory("tank/level", 0.0, emptyList())
        assertTrue(alarms.query().isEmpty())
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.ACKNOWLEDGED, AlarmEventType.CLEAR), types(alarms))
        val clear = alarms.journal.records().last()
        assertEquals(id, clear.eventId)
        assertEquals(AlarmEventFlags.SYSTEM or AlarmEventFlags.ACKNOWLEDGED, clear.eventFlags)
    }

    @Test fun removingAnAlarmWithAClearedUnackedEventAcknowledgesIt() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        tags.write("[default]tank/level", 0)
        val id = alarms.query().single().eventId
        tags.memory("tank/level", 0.0, emptyList())
        assertTrue(alarms.query().isEmpty())
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR, AlarmEventType.ACKNOWLEDGED), types(alarms))
        val ack = alarms.journal.records().last()
        assertEquals(id, ack.eventId)
        assertEquals(AlarmEventFlags.SYSTEM_ACK or AlarmEventFlags.CLEARED, ack.eventFlags)
    }

    @Test fun renamingAnAlarmRetiresTheOldOneAndStartsTheNewOne() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.memory("tank/level", 0.0, above("Level High"))
        val live = alarms.query().single()
        assertEquals("prov:default:/tag:tank/level:/alm:Level High", live.source)
        assertNotEquals(id, live.eventId)
        val rows = alarms.journal.records()
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR, AlarmEventType.ACKNOWLEDGED, AlarmEventType.ACTIVE), rows.map { it.eventType })
        assertEquals(listOf(id, id, id, live.eventId), rows.map { it.eventId })
    }

    @Test fun udtInstanceResaveKeepsMemberAlarmState() = run { _, tags, alarms ->
        tags.configureUdt(UdtDefinition.fromSpec(mapOf(
            "path" to "Types/Motor",
            "parameters" to listOf(mapOf("name" to "LowAmps", "dataType" to "Double", "default" to 25)),
            "members" to listOf(mapOf(
                "name" to "Amps",
                "type" to "memory",
                "dataType" to "Double",
                "value" to 10,
                "alarms" to listOf(mapOf(
                    "name" to "Low Amps",
                    "mode" to "BelowValue",
                    "priority" to "High",
                    "setpointA" to mapOf("parameter" to "LowAmps"),
                )),
            )),
        )))
        val instance = TagConfig.fromSpec(mapOf(
            "path" to "Line/Motor1",
            "type" to "udt",
            "typeId" to "Types/Motor",
            "parameters" to mapOf("LowAmps" to 30),
        ))
        tags.configure(instance)
        val id = alarms.query().single().eventId
        tags.configure(instance)
        assertEquals(id, alarms.query().single().eventId)
        assertEquals(listOf(AlarmEventType.ACTIVE), types(alarms))
    }

    @Test fun resaveThatOnlyChangesCaseKeepsTheAlarm() = run { _, tags, alarms ->
        val source = "prov:default:/tag:tank/level:/alm:High"
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.memory("Tank/Level", 0.0, above("High"))
        val live = alarms.query().single()
        assertEquals(id, live.eventId)
        // The live alarm keeps the source it was opened under, so shelve and journal rows stay on one source.
        assertEquals(source, live.source)
        assertEquals(listOf(AlarmEventType.ACTIVE), types(alarms))
        alarms.shelve(listOf(source), Duration.ofMinutes(1))
        assertTrue(alarms.isShelved(source))
        alarms.unshelve(listOf(source))
        // No orphan is left behind: removing the tag retires the one alarm once.
        tags.remove("[default]Tank/Level")
        assertTrue(alarms.query().isEmpty())
        val rows = alarms.journal.records().filter { it.eventType != JournalEventTypes.SHELVED && it.eventType != JournalEventTypes.UNSHELVED }
        assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR, AlarmEventType.ACKNOWLEDGED), rows.map { it.eventType })
        assertTrue(rows.all { it.eventId == id && it.source == source })
    }

    @Test fun resaveWhoseBindingCannotSubscribeRetiresTheKeptAlarm() = run { _, tags, alarms ->
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        tags.memory("tank/level", 0.0, above("High", extra = mapOf("enabled" to mapOf("tag" to "[nope]en"))))
        assertTrue(alarms.query().isEmpty())
        assertRetired(alarms, id)
        // The half-made subscriptions were closed: later value changes write nothing.
        tags.write("[default]tank/level", 0)
        tags.write("[default]tank/level", 150)
        assertEquals(3, alarms.journal.records().size)
    }

    @Test fun retiringAShelvedAlarmClosesItsJournaledEvent() = run { _, tags, alarms ->
        val source = "prov:default:/tag:tank/level:/alm:High"
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        alarms.shelve(listOf(source), Duration.ofMinutes(10), user = "sup")
        tags.memory("tank/level", 0.0, emptyList())
        val rows = alarms.journal.records()
        assertEquals(
            listOf(AlarmEventType.ACTIVE, JournalEventTypes.SHELVED, AlarmEventType.CLEAR, AlarmEventType.ACKNOWLEDGED),
            rows.map { it.eventType },
        )
        assertTrue(rows.all { it.eventId == id })
        assertEquals(AlarmEventFlags.SYSTEM or AlarmEventFlags.SHELVED, rows[2].eventFlags)
        assertEquals(AlarmEventFlags.SYSTEM_ACK or AlarmEventFlags.CLEARED or AlarmEventFlags.SHELVED, rows[3].eventFlags)
    }

    /**
     * Review-3 F7: an event whose active row was journaled before the shelf is closed in the journal when it clears
     * while shelved, as retiring does, even with Store Shelved Events off. The clear row is flagged Shelved. A
     * condition that starts and clears inside the shelf still writes nothing.
     */
    @Test fun shelvedClearOfAJournaledEventStillClosesIt() = run { _, tags, alarms ->
        val source = "prov:default:/tag:tank/level:/alm:High"
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId
        alarms.shelve(listOf(source), Duration.ofMinutes(10))
        tags.write("[default]tank/level", 0)
        assertEquals(listOf(AlarmEventType.ACTIVE, JournalEventTypes.SHELVED, AlarmEventType.CLEAR), types(alarms))
        val clear = alarms.journal.records().last()
        assertEquals(id, clear.eventId)
        assertTrue(clear.eventFlags and AlarmEventFlags.SHELVED != 0)
        alarms.acknowledge(listOf(id), "op", null)
        assertEquals(AlarmEventType.ACKNOWLEDGED, alarms.journal.records().last().eventType, "the ack closes it too")
        tags.write("[default]tank/level", 150)
        tags.write("[default]tank/level", 0)
        assertEquals(4, alarms.journal.records().size, "a condition inside the shelf writes nothing")
    }

    @Test fun aShelfLongerThanTheMaximumIsRefused() = run { _, tags, alarms ->
        val source = "prov:default:/tag:tank/level:/alm:High"
        tags.memory("tank/level", 0.0, above("High"))
        assertThrows(IllegalArgumentException::class.java) { alarms.shelve(listOf(source), Duration.ofDays(365 * 100)) }
        assertFalse(alarms.isShelved(source))
        alarms.shelve(listOf(source), alarms.settings.maxShelveDuration)
        assertTrue(alarms.isShelved(source))
    }

    @Test fun reconcileRunsAfterTheTagLockIsReleased() = run { _, tags, alarms ->
        val held = ArrayList<Pair<String, Boolean>>()
        alarms.onNotice = { held += it.trigger to tags.holdingWriteLock() }
        tags.memory("tank/level", 0.0, above("High"))
        tags.write("[default]tank/level", 150)
        tags.memory("tank/level", 0.0, emptyList())
        assertEquals(listOf("active" to false, "clear" to false, "ack" to false), held)
    }
}

/** Removed with an unacked active event: a system clear, then a system acknowledgement, on the same event. */
private fun assertRetired(alarms: AlarmEngine, id: String) {
    val rows = alarms.journal.records()
    assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR, AlarmEventType.ACKNOWLEDGED), rows.map { it.eventType })
    assertEquals(listOf(id, id, id), rows.map { it.eventId })
    val clear = rows[1]
    assertEquals(AlarmEventFlags.SYSTEM, clear.eventFlags)
    assertEquals(150.0, clear.data["eventValue"], "the clear row carries the last value the alarm saw")
    val ack = rows[2]
    assertEquals(AlarmEventFlags.SYSTEM_ACK or AlarmEventFlags.CLEARED, ack.eventFlags)
    assertNull(ack.actor)
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
    val journal = AlarmJournal(Files.createTempFile("alarm-journal", ".sqlite"))
    val alarms = AlarmEngine(clock, journal)
    alarms.attach(tags)
    try {
        body(clock, tags, alarms)
    } finally {
        alarms.close()
    }
}

private fun types(alarms: AlarmEngine) = alarms.journal.records().map { it.eventType }

private fun TagEngine.memory(
    path: String,
    value: Double,
    alarms: List<Map<String, Any?>>,
    engLow: Double? = null,
    engHigh: Double? = null,
    dataType: String = "Double",
) {
    val spec = linkedMapOf<String, Any?>(
        "path" to path,
        "type" to "memory",
        "dataType" to dataType,
        "value" to value,
    )
    if (alarms.isNotEmpty()) spec["alarms"] = alarms
    if (engLow != null) spec["engLow"] = engLow
    if (engHigh != null) spec["engHigh"] = engHigh
    configure(TagConfig.fromSpec(spec))
}

private fun above(
    name: String,
    setpoint: Number = 100,
    inclusive: Boolean = false,
    deadband: Number = 0,
    extra: Map<String, Any?> = emptyMap(),
): List<Map<String, Any?>> = listOf(alarm(name, "AboveValue", setpoint, inclusive, deadband, extra + mapOf("priority" to (extra["priority"] ?: "High"))))

private fun below(
    name: String,
    setpoint: Number = 30,
    inclusive: Boolean = false,
    deadband: Number = 0,
    extra: Map<String, Any?> = emptyMap(),
): List<Map<String, Any?>> = listOf(alarm(name, "BelowValue", setpoint, inclusive, deadband, extra + mapOf("priority" to (extra["priority"] ?: "High"))))

private fun alarm(
    name: String,
    mode: String,
    setpoint: Number? = null,
    inclusive: Boolean = false,
    deadband: Number = 0,
    extra: Map<String, Any?> = emptyMap(),
): Map<String, Any?> {
    val spec = linkedMapOf<String, Any?>(
        "name" to name,
        "mode" to mode,
        "priority" to "Medium",
        "inclusiveA" to inclusive,
        "deadband" to deadband,
    )
    if (setpoint != null) spec["setpointA"] = setpoint
    spec.putAll(extra)
    return spec
}
