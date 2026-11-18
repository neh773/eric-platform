package org.oip.edgesync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Local history stays after a forward, and the cap drops the oldest rows. */
class EdgeSyncDatabaseTest {
    @TempDir lateinit var tmp: Path

    @Test fun pointCapDropsForwardedRowsBeforeUnsentOnes() {
        val db = EdgeSyncDatabase(tmp.resolve("edge-sync.sqlite"), historyRetentionDays = 35, historyMaxPoints = 3)
        db.use {
            it.insertHistory(listOf(sample("a", NOW + 1), sample("b", NOW + 2)))
            val first = it.nextHistory(10)
            it.markForwarded(first.map { row -> row.id })
            it.insertHistory(listOf(sample("c", NOW + 3), sample("d", NOW + 4)))
            it.pruneHistory(NOW + 10)
            val left = it.localHistory(null, 10).sortedBy { row -> row.id }
            assertEquals(3, left.size)
            assertEquals(listOf("b", "c", "d"), left.map { row -> row.path })
            assertTrue(left.first().forwarded)
            assertFalse(left.last().forwarded)
            assertEquals(2, it.pendingHistory())
        }
    }

    @Test fun rowsOlderThanRetentionAreRemoved() {
        val db = EdgeSyncDatabase(tmp.resolve("old.sqlite"), historyRetentionDays = 1, historyMaxPoints = 100)
        db.use {
            it.insertHistory(listOf(NewHistory("default", "old", "1", "Good", 1)))
            it.pruneHistory(3L * 86_400L * 1_000_000L)
            assertTrue(it.localHistory(null, 10).isEmpty())
        }
    }

    @Test fun remoteInsertIsIdempotent() {
        val db = EdgeSyncDatabase(tmp.resolve("inbox.sqlite"))
        db.use {
            val row = RemoteHistory("site-a", "enterprise", 7, "default", "line/level", "1.0", "Good", 50)
            assertEquals(7, it.acceptHistory("site-a", "enterprise", listOf(row)))
            assertEquals(7, it.acceptHistory("site-a", "enterprise", listOf(row)))
            assertEquals(1, it.remoteHistory("enterprise", "site-a", 10).size)
            it.acceptAudit(listOf(RemoteAudit("site-a", "Gateway", 3, 10, "op", "", "login", "gate", "", "SUCCESS", "local", "")))
            assertEquals("login", it.remoteAudit("Gateway", "site-a", 10).single().action)
            it.acceptAlarms(listOf(RemoteAlarm("site-a", "Journal", 4, "evt", "src", "line/level/High", 3, 0, 0, 10, "{}")))
            assertEquals("evt", it.remoteAlarms("Journal", "site-a", 10).single().eventId)
        }
    }

    private fun sample(path: String, micros: Long) = NewHistory("default", path, "1", "Good", micros)

    private companion object {
        const val NOW = 1_700_000_000_000_000L
    }
}
