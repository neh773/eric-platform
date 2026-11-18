package org.oip.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

/**
 * C-14: journal query, retention, shelved alarm listing, and asynchronous journal writes (P2-14).
 */
class AlarmJournalTest {
    @TempDir lateinit var tmp: Path

    private val t0: Instant = Instant.parse("2026-03-01T08:00:00Z")
    private val level = "prov:default:/tag:tank/level:/alm:High"
    private val pressure = "prov:default:/tag:line/pressure:/alm:Low"

    @Test fun activateAckClearShelveWritesOneRowPerEventInOrderAndFiltersNarrow() = engine { clock, tags, alarms ->
        tags.memoryTag("tank/level", alarmSpec("High", "High"))
        tags.memoryTag("line/pressure", alarmSpec("Low", "Low"))

        tags.write("[default]tank/level", 150)
        clock.now = t0.plusSeconds(1)
        val id = alarms.query().single().eventId
        alarms.acknowledge(listOf(id), "op", "seen")
        clock.now = t0.plusSeconds(2)
        tags.write("[default]tank/level", 0)
        clock.now = t0.plusSeconds(3)
        alarms.shelve(listOf(level), Duration.ofMinutes(10), user = "sup")
        clock.now = t0.plusSeconds(4)
        tags.write("[default]line/pressure", 150)

        val rows = alarms.journal.query(JournalQuery(newestFirst = false)).records
        assertEquals(
            listOf(JournalEventTypes.ACTIVE, JournalEventTypes.ACKNOWLEDGED, JournalEventTypes.CLEAR, JournalEventTypes.SHELVED, JournalEventTypes.ACTIVE),
            rows.map { it.eventType },
        )
        val tank = rows.take(4)
        assertTrue(tank.all { it.source == level && it.eventId == id }, "one event id across its lifecycle")
        assertEquals(listOf(0L, 1L, 2L, 3L), tank.map { (it.eventTimeMillis - t0.toEpochMilli()) / 1000 })
        assertEquals(listOf(null, "op", null, "sup"), tank.map { it.actor })
        assertEquals("seen", tank[1].data["ackNotes"])
        assertEquals(t0.plusSeconds(3).plus(Duration.ofMinutes(10)).toEpochMilli(), tank[3].data["shelveExpiry"])
        assertEquals("tank/level/High", tank[3].displayPath)

        // Newest first is the default order.
        assertEquals(rows.reversed().map { it.id }, alarms.journal.query().records.map { it.id })

        fun count(q: JournalQuery) = alarms.journal.query(q.copy(newestFirst = false))
        assertEquals(listOf(JournalEventTypes.ACKNOWLEDGED), count(JournalQuery(eventTypes = setOf(JournalEventTypes.ACKNOWLEDGED))).records.map { it.eventType })
        assertEquals(2, count(JournalQuery(eventTypes = setOf(JournalEventTypes.ACTIVE))).total)
        assertEquals(listOf(JournalEventTypes.ACKNOWLEDGED, JournalEventTypes.CLEAR),
            count(JournalQuery(start = t0.plusSeconds(1), end = t0.plusSeconds(3))).records.map { it.eventType })
        assertEquals(4, count(JournalQuery(source = "*tank/level*")).total)
        assertEquals(1, count(JournalQuery(source = "*pressure*")).total)
        assertEquals(5, count(JournalQuery(source = "*pressure*, *tank*")).total)
        assertEquals(0, count(JournalQuery(source = "*TANK*")).total, "patterns are case-sensitive")
        assertEquals(1, count(JournalQuery(displayPath = "line/*")).total)
        assertEquals(4, count(JournalQuery(minPriority = AlarmPriority.HIGH)).total)
        assertEquals(1, count(JournalQuery(maxPriority = AlarmPriority.MEDIUM)).total)
        assertEquals(0, count(JournalQuery(minPriority = AlarmPriority.MEDIUM, maxPriority = AlarmPriority.MEDIUM)).total)
        assertEquals(0, count(JournalQuery(eventTypes = emptySet())).total)

        val page = count(JournalQuery(offset = 1, limit = 2))
        assertEquals(5, page.total)
        assertEquals(rows.subList(1, 3).map { it.id }, page.records.map { it.id })

        clock.now = t0.plusSeconds(5)
        alarms.unshelve(listOf(level), user = "sup2")
        val last = alarms.journal.query(JournalQuery(limit = 1)).records.single()
        assertEquals(JournalEventTypes.UNSHELVED, last.eventType)
        assertEquals("sup2", last.actor)
        assertEquals(level, last.source)
    }

    /** S-1: [JournalQuery.sourceAllowed] filters before paging, so total and offset count only admitted rows. */
    @Test fun sourceAllowedFiltersBeforePaging() = engine { clock, tags, alarms ->
        tags.memoryTag("tank/level", alarmSpec("High", "High"))
        tags.memoryTag("line/pressure", alarmSpec("Low", "Low"))
        for (i in 1..3) {
            clock.now = t0.plusSeconds(i * 10L)
            tags.write("[default]tank/level", 150)
            tags.write("[default]line/pressure", 150)
            clock.now = t0.plusSeconds(i * 10L + 1)
            tags.write("[default]tank/level", 0)
            tags.write("[default]line/pressure", 0)
        }
        assertEquals(12L, alarms.journal.query(JournalQuery()).total)
        val asked = ArrayList<String>()
        val onlyLevel = JournalQuery(limit = 2, offset = 1, newestFirst = false, sourceAllowed = { asked += it; it == level })
        val page = alarms.journal.query(onlyLevel)
        assertEquals(6L, page.total)
        assertEquals(2, page.records.size)
        assertTrue(page.records.all { it.source == level })
        assertEquals(listOf(JournalEventTypes.CLEAR, JournalEventTypes.ACTIVE), page.records.map { it.eventType }, "offset 1 of level's own rows")
        assertEquals(setOf(level, pressure), asked.toSet())
        assertEquals(2, asked.size, "asked once per distinct source")
        assertEquals(0L, alarms.journal.query(JournalQuery(sourceAllowed = { false })).total)
        assertEquals(6L, alarms.journal.query(JournalQuery(source = "*pressure*", sourceAllowed = { true })).total)
    }

    @Test fun sourceTagPathNamesTheAlarmsTag() {
        assertEquals("[default]tank/level", alarmSourceTagPath(level))
        assertEquals("[edge]a/b:c", alarmSourceTagPath("prov:edge:/tag:a/b:c:/alm:Hi"))
        assertEquals("[default]q\"x", alarmSourceTagPath("prov:default:/tag:q\"x:/alm:Hi"))
        assertEquals("[default]tank/level", alarmSourceTagPath("prov:default:/tag:tank/level:/alm:hi:/alm:x"),
            "an alarm name containing :/alm: does not move the tag path")
        assertNull(alarmSourceTagPath("evt:something"))
        assertNull(alarmSourceTagPath("prov:default:/alm:Hi"))
    }

    @Test fun retentionDeletesOnlyRowsOlderThanTheSetting() {
        val clock = JournalClock(t0)
        val file = tmp.resolve("db").createDirectories().resolve("alarm-journal.sqlite")
        AlarmJournal(file, clock = clock).use { journal ->
            assertNull(journal.maxAge, "pruning is off by default")
            for (days in listOf(40L, 31L, 30L, 29L, 1L)) {
                journal.append(row("e$days", t0.minus(Duration.ofDays(days)), mapOf("eventValue" to days)))
            }
            assertEquals(0, journal.applyRetention())
            assertEquals(5, journal.records().size)

            journal.setMaxAge(Duration.ofDays(30))
            assertEquals(2, journal.applyRetention())
            val left = journal.records()
            assertEquals(listOf("e30", "e29", "e1"), left.map { it.eventId }, "a row exactly at the cutoff stays")
            assertEquals(listOf(30L, 29L, 1L), left.map { it.data["eventValue"] })

            assertEquals(2, journal.purgeOlderThan(t0.minus(Duration.ofDays(2))))
            assertEquals(listOf("e1"), journal.records().map { it.eventId })
        }
        AlarmJournal(file, clock = clock).use { reopened ->
            assertEquals(Duration.ofDays(30), reopened.maxAge, "retention is persisted")
            reopened.setMaxAge(null)
            assertNull(reopened.maxAge)
        }
        AlarmJournal(file, clock = clock).use { assertNull(it.maxAge) }
    }

    @Test fun writerAppliesRetentionOnStart() {
        val clock = JournalClock(t0)
        val file = tmp.resolve("start.sqlite")
        AlarmJournal(file, clock = clock).use { journal ->
            journal.append(row("old", t0.minus(Duration.ofDays(10))))
            journal.append(row("new", t0))
            journal.flush()
            journal.setMaxAge(Duration.ofDays(5))
        }
        AlarmJournal(file, clock = clock).use { journal ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (journal.records().size > 1 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(listOf("new"), journal.records().map { it.eventId })
        }
    }

    @Test fun shelvedAlarmsComeBackWithExpiryAndActorAndUnshelveRemovesThem() = engine { clock, tags, alarms ->
        tags.memoryTag("tank/level", alarmSpec("High", "High"))
        tags.memoryTag("line/pressure", alarmSpec("Low", "Low"))
        tags.write("[default]tank/level", 150)
        val id = alarms.query().single().eventId

        alarms.shelve(listOf(level), Duration.ofSeconds(120), user = "sup")
        clock.now = t0.plusSeconds(10)
        alarms.shelve(listOf(pressure), Duration.ofSeconds(30), user = "op")
        assertTrue(alarms.query().isEmpty(), "the live list still hides shelved alarms")

        val shelved = alarms.shelved()
        assertEquals(listOf(pressure, level), shelved.map { it.source })
        val tank = shelved.single { it.source == level }
        assertEquals(t0.plusSeconds(120), tank.shelvedUntil)
        assertEquals(t0, tank.shelvedAt)
        assertEquals("sup", tank.shelvedBy)
        assertTrue(tank.conditionActive)
        assertEquals(id, tank.event?.eventId)
        assertEquals("Active and Unacknowledged", tank.event?.state)
        val line = shelved.single { it.source == pressure }
        assertNull(line.event, "an alarm with no live event is still listed")
        assertFalse(line.conditionActive)
        assertEquals("line/pressure/Low", line.displayPath)
        assertEquals(AlarmPriority.LOW, line.priority)

        assertEquals(listOf(level), alarms.shelved(AlarmFilter(minPriority = AlarmPriority.HIGH)).map { it.source })
        assertEquals(listOf(pressure), alarms.shelved(AlarmFilter(displayPath = "line/*")).map { it.source })
        assertEquals(listOf(level), alarms.shelved(AlarmFilter(active = true)).map { it.source })

        // A condition that starts under the shelf is reported without a live event.
        tags.write("[default]line/pressure", 150)
        assertTrue(alarms.shelved(AlarmFilter(source = pressure)).single().conditionActive)
        assertNull(alarms.shelved(AlarmFilter(source = pressure)).single().event)

        // Expiry removes it; unshelve removes the other.
        clock.now = t0.plusSeconds(40)
        alarms.poll()
        assertEquals(listOf(level), alarms.shelved().map { it.source })
        alarms.unshelve(listOf(level), user = "sup")
        assertTrue(alarms.shelved().isEmpty())
        assertEquals(setOf(level, pressure), alarms.query().map { it.source }.toSet())

        val ops = alarms.journal.records().filter { it.eventType == JournalEventTypes.SHELVED || it.eventType == JournalEventTypes.UNSHELVED }
        assertEquals(listOf(JournalEventTypes.SHELVED, JournalEventTypes.SHELVED, JournalEventTypes.UNSHELVED), ops.map { it.eventType })
        assertEquals(listOf("sup", "op", "sup"), ops.map { it.actor })
        // Unshelving an alarm that is not shelved writes nothing.
        alarms.unshelve(listOf(level), user = "sup")
        assertEquals(3, alarms.journal.records().count { it.eventType == JournalEventTypes.SHELVED || it.eventType == JournalEventTypes.UNSHELVED })
    }

    /**
     * P2-14: the journal insert runs on its own thread. With the writer stuck (a slow disk), device
     * samples that open and clear alarms still return, and the rows land in order once it resumes.
     */
    @Test fun updateRawIsNotBlockedWhileJournalWritesAreSlow() {
        val clock = JournalClock(t0)
        val tags = TagEngine(clock)
        val journal = AlarmJournal(tmp.resolve("slow.sqlite"), clock = clock)
        val alarms = AlarmEngine(clock, journal)
        alarms.attach(tags)
        val stuck = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val writerThreads = Collections.synchronizedSet(HashSet<String>())
        val writerHeldTagLock = Collections.synchronizedList(ArrayList<Boolean>())
        journal.beforeWrite = {
            writerThreads += Thread.currentThread().name
            writerHeldTagLock += tags.holdingWriteLock()
            entered.countDown()
            stuck.await(30, TimeUnit.SECONDS)
        }
        val device = Executors.newSingleThreadExecutor { r -> Thread(r, "device-poll") }
        try {
            tags.configure(TagConfig.fromSpec(mapOf(
                "path" to "pump/flow", "type" to "opc", "dataType" to "Double", "opcItemPath" to "ns=1;s=Flow",
                "alarms" to alarmSpec("High", "High"),
            )))
            val samples = 40
            val run = device.submit {
                for (i in 0 until samples) {
                    tags.updateRaw("[default]pump/flow", if (i % 2 == 0) 150.0 else 0.0)
                    if (i == 0) assertTrue(entered.await(10, TimeUnit.SECONDS), "writer picked up the first row")
                }
            }
            // Fails here (instead of hanging) if updateRaw waits for the journal.
            run.get(10, TimeUnit.SECONDS)
            assertEquals(0.0, (tags.read("[default]pump/flow").value as Number).toDouble())
            assertEquals(1L, stuck.count, "the writer was still stuck while every sample returned")
            stuck.countDown()
            assertTrue(journal.flush(Duration.ofSeconds(10)))
            val types = journal.records().map { it.eventType }
            // Each trip writes active then clear (unacked clears stay live; older ones get system-acked).
            assertEquals(samples / 2, types.count { it == JournalEventTypes.ACTIVE })
            assertEquals(samples / 2, types.count { it == JournalEventTypes.CLEAR })
            assertEquals(JournalEventTypes.ACTIVE, types.first())
            assertEquals(setOf("alarm-journal-writer"), writerThreads.toSet())
            assertFalse(writerHeldTagLock.any { it })
            assertEquals(0L, journal.lostRows)
        } finally {
            stuck.countDown()
            device.shutdownNow()
            alarms.close()
        }
    }

    /**
     * A-1: saving the same tag config again writes nothing; saving it without the alarm journals one
     * system clear and one system acknowledgement for the live event, at the time of the save.
     */
    @Test fun resaveJournalsNoDuplicateActiveAndARemovedAlarmIsCleared() = engine { clock, tags, alarms ->
        tags.memoryTag("tank/level", alarmSpec("High", "High"))
        tags.memoryTag("line/pressure", alarmSpec("Low", "Low"))
        tags.write("[default]tank/level", 150)
        tags.write("[default]line/pressure", 150)
        val ids = alarms.query().associate { it.source to it.eventId }

        clock.now = t0.plusSeconds(5)
        tags.memoryTag("tank/level", alarmSpec("High", "High"))
        tags.memoryTag("line/pressure", alarmSpec("Low", "Low"))
        assertEquals(2L, alarms.journal.query().total, "a re-save writes no rows")
        assertEquals(ids.values.toSet(), alarms.query().map { it.eventId }.toSet())

        clock.now = t0.plusSeconds(9)
        tags.memoryTag("tank/level", emptyList())
        val rows = alarms.journal.query(JournalQuery(source = level, newestFirst = false)).records
        assertEquals(listOf(JournalEventTypes.ACTIVE, JournalEventTypes.CLEAR, JournalEventTypes.ACKNOWLEDGED), rows.map { it.eventType })
        assertTrue(rows.all { it.eventId == ids.getValue(level) })
        assertEquals(listOf(0L, 9L, 9L), rows.map { (it.eventTimeMillis - t0.toEpochMilli()) / 1000 })
        assertEquals("tank/level/High", rows[1].displayPath)
        assertEquals(150.0, rows[1].data["eventValue"])
        assertEquals(AlarmEventFlags.SYSTEM, rows[1].eventFlags)
        assertEquals(AlarmEventFlags.SYSTEM_ACK or AlarmEventFlags.CLEARED, rows[2].eventFlags)
        assertEquals(null, rows[2].actor)
        // The other tag's alarm is untouched.
        assertEquals(listOf(ids.getValue(pressure)), alarms.query().map { it.eventId })
        assertEquals(1L, alarms.journal.query(JournalQuery(source = pressure)).total)
    }

    /** Edge sync reads committed rows by id cursor; the async writer must not change that contract. */
    @Test fun recordsAfterPagesCommittedRowsByIdWithData() {
        val clock = JournalClock(t0)
        AlarmJournal(tmp.resolve("cursor.sqlite"), clock = clock).use { journal ->
            assertEquals(0L, journal.maxId())
            assertTrue(journal.recordsAfter(0, 10).isEmpty())
            for (i in 0 until 5) assertTrue(journal.append(row("e$i", t0.plusSeconds(i.toLong()), mapOf("n" to i.toLong()))))
            assertTrue(journal.flush(Duration.ofSeconds(10)))
            val max = journal.maxId()
            val first = journal.recordsAfter(0, 2)
            assertEquals(listOf("e0", "e1"), first.map { it.eventId })
            assertEquals(1L, first[1].data["n"])
            val rest = journal.recordsAfter(first.last().id, 10)
            assertEquals(listOf("e2", "e3", "e4"), rest.map { it.eventId })
            assertEquals(max, rest.last().id)
            assertTrue(journal.recordsAfter(max, 10).isEmpty())
        }
    }

    /**
     * Review-3 F5: a failed journal write is held and retried before newer rows, so a transient failure (a busy
     * file, a full disk that recovers) loses nothing. Rows queued during the outage are stored after the held batch,
     * every row exactly once and in order, and [AlarmJournal.stats] reports the failed attempts.
     */
    @Test fun aFailedWriteIsRetriedAndNothingIsLostOrReordered() {
        val journal = AlarmJournal(tmp.resolve("retry.sqlite"))
        try {
            val failures = java.util.concurrent.atomic.AtomicInteger(3)
            val firstFailure = CountDownLatch(1)
            journal.beforeWrite = {
                if (failures.getAndDecrement() > 0) {
                    firstFailure.countDown()
                    throw java.sql.SQLException("database is locked", "SQLITE_BUSY")
                }
            }
            assertTrue(journal.append(row("e0", t0)))
            assertTrue(firstFailure.await(10, TimeUnit.SECONDS))
            for (i in 1 until 20) assertTrue(journal.append(row("e$i", t0.plusSeconds(i.toLong()))))
            assertTrue(journal.flush(Duration.ofSeconds(20)), "the held batch was retried until it committed")
            assertEquals((0 until 20).map { "e$it" }, journal.records().map { it.eventId })
            assertEquals(0L, journal.lostRows)
            val stats = journal.stats()
            assertEquals(3L, stats["failedWriteAttempts"])
            assertEquals(0L, stats["lostRows"])
            assertEquals(0, stats["queued"])
            assertEquals(false, stats["retrying"])
            assertTrue((stats["lastWriteError"] as String).contains("database is locked"), "$stats")
        } finally {
            journal.close()
        }
    }

    /** Review-3 F5: while the store keeps failing, [AlarmJournal.stats] shows the held rows as queued and retrying. */
    @Test fun aStoreThatKeepsFailingIsReportedNotDropped() {
        val journal = AlarmJournal(tmp.resolve("down.sqlite"))
        val down = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            journal.beforeWrite = { if (down.get()) throw java.sql.SQLException("disk I/O error") }
            for (i in 0 until 5) assertTrue(journal.append(row("d$i", t0.plusSeconds(i.toLong()))))
            assertFalse(journal.flush(Duration.ofMillis(500)), "nothing commits while the store is down")
            val stats = journal.stats()
            assertEquals(5, stats["queued"], "$stats")
            assertEquals(true, stats["retrying"], "$stats")
            assertEquals(0L, journal.lostRows)
            down.set(false)
            assertTrue(journal.flush(Duration.ofSeconds(20)))
            assertEquals((0 until 5).map { "d$it" }, journal.records().map { it.eventId })
            assertEquals(0L, journal.lostRows)
        } finally {
            down.set(false)
            journal.close()
        }
    }

    private fun engine(body: (JournalClock, TagEngine, AlarmEngine) -> Unit) {
        val clock = JournalClock(t0)
        val tags = TagEngine(clock)
        val alarms = AlarmEngine(clock, AlarmJournal(tmp.resolve("journal.sqlite"), clock = clock))
        alarms.attach(tags)
        try {
            body(clock, tags, alarms)
        } finally {
            alarms.close()
        }
    }

    private fun row(eventId: String, at: Instant, data: Map<String, Any?> = emptyMap()) = JournalEntry(
        eventId = eventId,
        source = "prov:default:/tag:t:/alm:A",
        displayPath = "t/A",
        priority = AlarmPriority.HIGH,
        eventType = JournalEventTypes.ACTIVE,
        eventFlags = 0,
        eventTimeMillis = at.toEpochMilli(),
        shelved = false,
        data = data,
    )
}

private class JournalClock(@Volatile var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    override fun millis(): Long = now.toEpochMilli()
}

private fun TagEngine.memoryTag(path: String, alarms: List<Map<String, Any?>>) {
    configure(TagConfig.fromSpec(mapOf("path" to path, "type" to "memory", "dataType" to "Double", "value" to 0.0, "alarms" to alarms)))
}

private fun alarmSpec(name: String, priority: String): List<Map<String, Any?>> = listOf(mapOf(
    "name" to name,
    "mode" to "AboveValue",
    "priority" to priority,
    "setpointA" to 100,
))
