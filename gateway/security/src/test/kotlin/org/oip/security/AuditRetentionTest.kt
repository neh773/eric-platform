package org.oip.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * SEC-001: audit retention is enforced. Rows older than the audit profile's retention are removed, the kept chain
 * still verifies from a keyed base, and removing rows any other way still fails verification.
 * Doc source: FEATURES SEC-001.
 */
class AuditRetentionTest {
    @TempDir lateinit var tmp: Path

    private val secret = SecretKeyLoader.fromBytes(ByteArray(32) { 7 })
    private val chainKey = AuditKeys.chainKey(secret)
    private val day = 86_400_000L
    private val t0 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

    private class FakeClock(var now: Instant) : Clock() {
        fun advance(d: Duration) { now = now.plus(d) }
        override fun instant(): Instant = now
        override fun millis(): Long = now.toEpochMilli()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun draft(at: Long, n: Int) = AuditDraft(at, "alice", "login", AuditStatus.SUCCESS, target = "user/alice", detail = "n=$n")

    private fun memory(sink: MemoryAuditSink = MemoryAuditSink()) =
        sink to ChainedAuditLog(sink, chainKey, MacAuditAnchor(MemoryAuditAnchorStore(), AuditKeys.anchorKey(secret)))

    private fun sqlite(dir: Path): Triple<Path, SqliteAuditSink, ChainedAuditLog> {
        val db = dir.resolve("audit.sqlite")
        val sink = SqliteAuditSink(db)
        val anchor = MacAuditAnchor(FileAuditAnchorStore(dir.resolve("audit.anchor")), AuditKeys.anchorKey(secret))
        return Triple(db, sink, ChainedAuditLog(sink, chainKey, anchor))
    }

    private fun seqs(log: AuditLog) = log.query(AuditQuery(limit = ChainedAuditLog.MAX_LIMIT)).records.map { it.seq }.sorted()

    @Test fun pruneRemovesExpiredRowsAndTheKeptChainVerifiesAndContinues() {
        val (_, log) = memory()
        for (i in 1..10) log.append(draft(t0 + i * day, i))
        val result = log.prune(beforeMillis = t0 + 6 * day + 1, nowMillis = t0 + 40 * day)
        assertEquals(6, result.removed)
        assertEquals(6L, result.base!!.seq)
        assertEquals((7L..10L).toList(), seqs(log))
        assertTrue(log.verify().ok, log.verify().reason)
        val next = log.append(draft(t0 + 41 * day, 11))
        assertEquals(11L, next.seq)
        assertTrue(log.verify().ok)
        assertEquals(result.base, log.base())
    }

    @Test fun theNewestRowIsNeverRemoved() {
        val (_, log) = memory()
        for (i in 1..3) log.append(draft(t0 + i, i))
        val result = log.prune(beforeMillis = t0 + 10 * day, nowMillis = t0 + 10 * day)
        assertEquals(2, result.removed)
        assertEquals(listOf(3L), seqs(log))
        assertTrue(log.verify().ok)
        // Nothing left to remove but the tip.
        assertEquals(0, log.prune(t0 + 10 * day, t0 + 10 * day).removed)
    }

    @Test fun pruneStopsAtTheFirstRowThatIsNotExpired() {
        val (_, log) = memory()
        log.append(draft(t0, 1))
        log.append(draft(t0 + 5 * day, 2))
        // A clock step back: row 3 is older than row 2 but sits after it in the chain.
        log.append(draft(t0 + day, 3))
        log.append(draft(t0 + 9 * day, 4))
        assertEquals(1, log.prune(t0 + 2 * day, t0 + 10 * day).removed)
        assertEquals(listOf(2L, 3L, 4L), seqs(log))
        assertTrue(log.verify().ok)
    }

    @Test fun rowsDroppedWithoutMovingTheBaseFailVerification() {
        val (sink, log) = memory()
        for (i in 1..5) log.append(draft(t0 + i, i))
        sink.dropThrough(2)
        val v = log.verify()
        assertFalse(v.ok)
        assertEquals("sequence gap or reorder", v.reason)
    }

    @Test fun aBaseForgedWithoutTheKeyFailsVerification() {
        val (sink, log) = memory()
        for (i in 1..5) log.append(draft(t0 + i, i))
        val victim = log.query(AuditQuery(limit = 10)).records.single { it.seq == 2L }
        sink.dropThrough(2)
        // The attacker knows seq and hash of the dropped row but not the chain key.
        val wrongKey = ByteArray(32) { 1 }
        sink.forceBase(AuditBase(2, victim.hash, t0, AuditChain.baseMac(2, victim.hash, t0, wrongKey)))
        val v = log.verify()
        assertFalse(v.ok)
        assertEquals("retention base mismatch", v.reason)
        // A genuine base (holder of the key) verifies.
        sink.forceBase(AuditBase(2, victim.hash, t0, AuditChain.baseMac(2, victim.hash, t0, chainKey)))
        assertTrue(log.verify().ok)
        // Moving the base past rows that are still present breaks the link.
        val third = log.query(AuditQuery(limit = 10)).records.single { it.seq == 3L }
        sink.forceBase(AuditBase(3, third.hash, t0, AuditChain.baseMac(3, third.hash, t0, chainKey)))
        assertFalse(log.verify().ok)
    }

    @Test fun sqlitePruneIsDurableAndTheTriggerStillRefusesOtherDeletes(@TempDir dir: Path) {
        val (db, sink, log) = sqlite(dir)
        for (i in 1..8) log.append(draft(t0 + i * day, i))
        assertEquals(4, log.prune(t0 + 4 * day + 1, t0 + 30 * day).removed)
        assertTrue(log.verify().ok)
        sink.close()

        // A delete above the base is still refused by the append-only trigger.
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                assertThrows(SQLException::class.java) { st.execute("DELETE FROM audit_event WHERE seq = 6") }
                assertThrows(SQLException::class.java) { st.execute("UPDATE audit_base SET seq = 1") }
            }
        }

        val (_, sink2, log2) = sqlite(dir)
        try {
            assertEquals((5L..8L).toList(), seqs(log2))
            assertEquals(4L, log2.base()!!.seq)
            assertTrue(log2.verify().ok, log2.verify().reason)
            assertEquals(9L, log2.append(draft(t0 + 31 * day, 9)).seq)
            assertTrue(log2.verify().ok)
        } finally {
            sink2.close()
        }
    }

    @Test fun sqliteRowsRemovedBehindTheTriggerFailVerification(@TempDir dir: Path) {
        val (db, sink, log) = sqlite(dir)
        for (i in 1..5) log.append(draft(t0 + i, i))
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TRIGGER audit_event_no_delete")
                st.execute("DELETE FROM audit_event WHERE seq <= 2")
            }
        }
        assertFalse(log.verify().ok)
        sink.close()
    }

    @Test fun serviceEnforcesProfileRetentionWithAFakeClock() {
        val clock = FakeClock(Instant.parse("2026-03-01T00:00:00Z"))
        val (store, svc) = openSecurity(tmp.resolve("svc"), clock)
        try {
            svc.auditProfile.update(AuditProfile("Internal audit profile", 30, true), actor = "test")
            for (i in 1..5) {
                svc.audit.append(AuditDraft(clock.millis(), "bob", "login", AuditStatus.SUCCESS))
                clock.advance(Duration.ofDays(10))
            }
            val before = svc.audit.query(AuditQuery(limit = 1000)).records
            // Now = start + 50 days. Cutoff = start + 20 days: the seed rows and the first two logins are expired.
            val cutoff = clock.instant().minus(Duration.ofDays(30)).toEpochMilli()
            val expired = before.count { it.timestampMillis < cutoff }.toLong()
            assertTrue(expired > 0)
            assertEquals(expired, svc.auditProfile.expiredCount(clock.instant()))

            val removed = svc.auditProfile.enforceRetention(clock.instant())
            assertEquals(expired, removed)
            assertEquals(0, svc.auditProfile.expiredCount(clock.instant()))
            val after = svc.audit.query(AuditQuery(limit = 1000)).records
            assertTrue(after.all { it.timestampMillis >= cutoff })
            val pruned = after.first()
            assertEquals(AuditActions.AUDIT_PRUNED, pruned.action)
            assertTrue(pruned.detail.startsWith("removed=$expired,"), pruned.detail)
            assertTrue(svc.audit.verify().ok, svc.audit.verify().reason)

            // Retention 0 keeps every row, however old.
            svc.auditProfile.update(AuditProfile("Internal audit profile", 0, true), actor = "test")
            clock.advance(Duration.ofDays(3650))
            assertEquals(0, svc.auditProfile.enforceRetention(clock.instant()))
            assertNotNull(svc.audit.latest())
        } finally {
            svc.close(); store.close()
        }

        // A restart reads the same base and the chain still verifies.
        val (store2, svc2) = openSecurity(tmp.resolve("svc"), clock, registry = org.oip.config.SchemaRegistry())
        try {
            assertTrue(svc2.audit.verify().ok)
            assertNotNull(svc2.audit.base())
        } finally {
            svc2.close(); store2.close()
        }
    }

    @Test fun noBaseUntilSomethingIsRemoved() {
        val (_, log) = memory()
        log.append(draft(t0, 1))
        assertNull(log.base())
        assertEquals(0, log.prune(t0 - 1, t0).removed)
        assertNull(log.base())
    }
}
