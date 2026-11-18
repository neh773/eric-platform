package org.oip.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Review-3 F4: live alarm state survives a restart. A new engine on the same journal file keeps the same event id
 * live, acknowledged or shelved as before, and writes no second active row. An event whose condition went away while
 * the gateway was down gets an ordinary clear. Events the journal left open that nothing can close any more get a
 * system clear and a system acknowledgement at start-up (DIFFERENCES 165, 271).
 * Doc source: FEATURES ALA-033, ALA-041.
 */
class AlarmRestartTest {
    @TempDir lateinit var tmp: Path

    private val clock = Manual(Instant.parse("2026-05-01T10:00:00Z"))
    private val level = "prov:default:/tag:tank/level:/alm:High"
    private val flow = "prov:default:/tag:pump/flow:/alm:High"

    @Test fun ackAndShelveAndEventIdsSurviveARestart() {
        val file = tmp.resolve("journal.sqlite")
        val (ackedId, shelvedId) = gateway(file, level = 150.0, flow = 150.0) { _, alarms ->
            val ids = alarms.query().associate { it.source to it.eventId }
            alarms.acknowledge(listOf(ids.getValue(level)), "op", "seen")
            alarms.shelve(listOf(flow), Duration.ofHours(2), user = "sup")
            ids.getValue(level) to ids.getValue(flow)
        }
        clock.now = clock.now.plusSeconds(60)
        gateway(file, level = 150.0, flow = 150.0) { _, alarms ->
            alarms.poll()
            val live = alarms.query().single()
            assertEquals(ackedId, live.eventId, "the same event is live after the restart")
            assertEquals("Active and Acknowledged", live.state)
            assertEquals("op", live.ackUser)
            assertEquals("seen", live.ackNotes)

            assertTrue(alarms.isShelved(flow), "the shelf is still in force")
            val shelf = alarms.shelved().single()
            assertEquals("sup", shelf.shelvedBy)
            assertEquals(Instant.parse("2026-05-01T12:00:00Z"), shelf.shelvedUntil)
            assertEquals(shelvedId, shelf.event?.eventId)

            val rows = alarms.journal.records()
            assertEquals(2, rows.count { it.eventType == AlarmEventType.ACTIVE }, "no second active row: $rows")
            assertTrue(rows.none { it.eventType == AlarmEventType.CLEAR }, "$rows")
        }
    }

    @Test fun anEventThatEndedWhileDownGetsAnOrdinaryClear() {
        val file = tmp.resolve("journal.sqlite")
        val id = gateway(file, level = 150.0, flow = 0.0) { _, alarms -> alarms.query().single().eventId }
        gateway(file, level = 0.0, flow = 0.0) { _, alarms ->
            alarms.poll()
            val cleared = alarms.query().single()
            assertEquals(id, cleared.eventId)
            assertEquals("Cleared and Unacknowledged", cleared.state)
            val clear = alarms.journal.records().last()
            assertEquals(AlarmEventType.CLEAR, clear.eventType)
            assertEquals(id, clear.eventId)
            assertEquals(0, clear.eventFlags and AlarmEventFlags.SYSTEM, "an ordinary clear, not a system one")
            alarms.acknowledge(listOf(id), "op", null)
            assertTrue(alarms.query().isEmpty())
        }
        gateway(file, level = 0.0, flow = 0.0) { _, alarms ->
            alarms.poll()
            assertTrue(alarms.query().isEmpty(), "nothing comes back once closed")
            assertEquals(3, alarms.journal.records().size)
        }
    }

    @Test fun openEventsNobodyCanCloseAreClosedAtStartUp() {
        val file = tmp.resolve("journal.sqlite")
        val (keptId, goneId) = gateway(file, level = 150.0, flow = 150.0) { _, alarms ->
            val ids = alarms.query().associate { it.source to it.eventId }
            ids.getValue(level) to ids.getValue(flow)
        }
        // As if the last run had crashed before saving the flow alarm's state, then its tag lost the alarm.
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.prepareStatement("DELETE FROM alarm_live_state WHERE source = ?").use { it.setString(1, flow); it.executeUpdate() }
        }
        gateway(file, level = 150.0, flow = null) { _, alarms ->
            alarms.poll()
            assertEquals(keptId, alarms.query().single().eventId)
            val gone = alarms.journal.records().filter { it.eventId == goneId }
            assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR, AlarmEventType.ACKNOWLEDGED), gone.map { it.eventType })
            assertEquals(AlarmEventFlags.SYSTEM, gone[1].eventFlags)
            assertEquals(AlarmEventFlags.SYSTEM_ACK or AlarmEventFlags.CLEARED, gone[2].eventFlags)
            assertTrue(alarms.journal.openEvents().none { it.eventId == goneId })
            assertTrue(alarms.journal.openEvents().any { it.eventId == keptId }, "a live event stays open")
            assertFalse(alarms.journal.loadStates().containsKey(flow))
        }
    }

    @Test fun aStatelessJournalFromAnOlderRunIsReconciled() {
        val file = tmp.resolve("journal.sqlite")
        val old = gateway(file, level = 150.0, flow = 0.0) { _, alarms -> alarms.query().single().eventId }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { it.execute("DELETE FROM alarm_live_state") } }
        gateway(file, level = 150.0, flow = 0.0) { _, alarms ->
            alarms.poll()
            val fresh = alarms.query().single()
            assertNotNull(fresh)
            assertTrue(fresh.eventId != old, "without saved state the condition opens a new event")
            val rows = alarms.journal.records().filter { it.eventId == old }.map { it.eventType }
            assertEquals(listOf(AlarmEventType.ACTIVE, AlarmEventType.CLEAR, AlarmEventType.ACKNOWLEDGED), rows, "the old one is closed")
        }
    }

    private fun <T> gateway(file: Path, level: Double, flow: Double?, body: (TagEngine, AlarmEngine) -> T): T {
        val tags = TagEngine(clock)
        tags.configure(memory("tank/level", level))
        if (flow != null) tags.configure(memory("pump/flow", flow))
        val alarms = AlarmEngine(clock, AlarmJournal(file, clock = clock))
        alarms.attach(tags)
        try {
            return body(tags, alarms)
        } finally {
            alarms.close()
        }
    }

    private fun memory(path: String, value: Double) = TagConfig.fromSpec(mapOf(
        "path" to path, "type" to "memory", "dataType" to "Double", "value" to value,
        "alarms" to listOf(mapOf("name" to "High", "mode" to "AboveValue", "priority" to "High", "setpointA" to 100)),
    ))

    private class Manual(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        override fun millis(): Long = now.toEpochMilli()
    }
}
