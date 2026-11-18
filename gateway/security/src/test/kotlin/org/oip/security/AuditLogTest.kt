package org.oip.security

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import javax.crypto.SecretKey

/** Checkpoint write that fails a fixed number of times, then delegates. Simulates a crash or a full disk. */
private class FailNextStore(private val delegate: AuditAnchor) : AuditAnchor {
    var failures: Int = 0

    override fun load(): AnchorLoad = delegate.load()

    override fun store(seq: Long, hash: String) {
        if (failures > 0) {
            failures -= 1
            throw IOException("disk full")
        }
        delegate.store(seq, hash)
    }
}

class AuditLogTest {
    @TempDir lateinit var tmp: Path

    private val secret: SecretKey = SecretKeyLoader.fromBytes(ByteArray(32) { 9 })
    private val chainKey: ByteArray = AuditKeys.chainKey(secret)

    private fun draft(i: Int, actor: String = "alice", action: String = "login", at: Long = 1_700_000_000_000L + i) = AuditDraft(
        timestampMillis = at, actor = actor, action = action, status = if (i % 2 == 0) AuditStatus.SUCCESS else AuditStatus.FAILURE,
        actorHost = "10.0.0.$i", target = "user/$actor", detail = "n=$i",
    )

    private fun memoryLog(sink: AuditSink = MemoryAuditSink()) =
        ChainedAuditLog(sink, chainKey, MacAuditAnchor(MemoryAuditAnchorStore(), AuditKeys.anchorKey(secret)))

    private class SqliteFixture(
        val db: Path,
        val anchorFile: Path,
        val anchor: MacAuditAnchor,
        val sink: SqliteAuditSink,
        val log: ChainedAuditLog,
    )

    private fun sqliteLog(dir: Path): SqliteFixture {
        val db = dir.resolve("audit.sqlite")
        val anchorFile = dir.resolve("audit.anchor")
        val anchor = MacAuditAnchor(FileAuditAnchorStore(anchorFile), AuditKeys.anchorKey(secret))
        val sink = SqliteAuditSink(db)
        val log = ChainedAuditLog(sink, chainKey, anchor)
        return SqliteFixture(db, anchorFile, anchor, sink, log)
    }

    private fun rows(log: AuditLog): List<AuditRecord> =
        log.query(AuditQuery(limit = ChainedAuditLog.MAX_LIMIT)).records.sortedBy { it.seq }

    /** Replace every row. Callers drop the append-only triggers first by using this helper. */
    private fun rewrite(db: Path, records: List<AuditRecord>) {
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TRIGGER IF EXISTS audit_event_no_update")
                st.execute("DROP TRIGGER IF EXISTS audit_event_no_delete")
                st.execute("DELETE FROM audit_event")
            }
            conn.prepareStatement(
                """
                INSERT INTO audit_event(seq, ts_millis, actor, actor_host, action, target, detail, status, origin, context, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (r in records) {
                    ps.setLong(1, r.seq)
                    ps.setLong(2, r.timestampMillis)
                    ps.setString(3, r.actor)
                    ps.setString(4, r.actorHost)
                    ps.setString(5, r.action)
                    ps.setString(6, r.target)
                    ps.setString(7, r.detail)
                    ps.setString(8, r.status.name)
                    ps.setString(9, r.origin)
                    ps.setString(10, r.context)
                    ps.setString(11, r.prevHash)
                    ps.setString(12, r.hash)
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun rechain(records: List<AuditRecord>, key: ByteArray): List<AuditRecord> {
        var prev = AuditChain.GENESIS
        return records.mapIndexed { index, row ->
            val bare = row.copy(seq = (index + 1).toLong(), prevHash = prev, hash = "")
            val hashed = bare.copy(hash = AuditChain.hash(bare, key))
            prev = hashed.hash
            hashed
        }
    }

    /** Unkeyed SHA-256 chain: what a rebuild looks like without the secrets key. */
    private fun sha256Chain(records: List<AuditRecord>): List<AuditRecord> {
        var prev = AuditChain.GENESIS
        return records.mapIndexed { index, row ->
            val bare = row.copy(seq = (index + 1).toLong(), prevHash = prev, hash = "")
            val hashed = bare.copy(hash = Hex.encode(sha256(AuditChain.payload(bare))))
            prev = hashed.hash
            hashed
        }
    }

    @Test fun chainLinksAndQueryFilters() {
        val log = memoryLog()
        val a = log.append(draft(1, actor = "Alice", action = "login"))
        val b = log.append(draft(2, actor = "bob", action = "gateway config added"))
        assertEquals(1L, a.seq)
        assertEquals(AuditChain.GENESIS, a.prevHash)
        assertEquals(a.hash, b.prevHash)
        assertEquals(2L, b.seq)
        assertTrue(log.verify().ok)
        val page = log.query(AuditQuery(actor = "alice", action = "LOGIN", fromMillis = a.timestampMillis, toMillis = a.timestampMillis))
        assertEquals(1, page.total)
        assertEquals(a.seq, page.records.single().seq)
        assertEquals(0, log.query(AuditQuery(targetContains = "missing")).total)
        assertEquals(1, log.query(AuditQuery(status = AuditStatus.FAILURE)).total)
        assertEquals(listOf(2L, 1L), log.query(AuditQuery(limit = 10)).records.map { it.seq })
        val paged = log.query(AuditQuery(limit = 1, offset = 1))
        assertEquals(2, paged.total)
        assertEquals(1L, paged.records.single().seq)
        assertThrows(IllegalArgumentException::class.java) { log.query(AuditQuery(limit = 0)) }
        assertThrows(IllegalArgumentException::class.java) { log.query(AuditQuery(offset = -1)) }
        assertEquals(1, log.countOlderThan(b.timestampMillis))
    }

    @Test fun memoryTamperIsDetected() {
        val sink = MemoryAuditSink()
        val log = memoryLog(sink)
        log.append(draft(1))
        log.append(draft(2))
        sink.rewrite(1) { it.copy(action = "tampered") }
        val result = log.verify()
        assertFalse(result.ok)
        assertEquals(1L, result.failedSeq)
        assertEquals("hash mismatch", result.reason)
        sink.rewrite(1) { stored ->
            val fixed = stored.copy(action = "login")
            fixed.copy(hash = AuditChain.hash(fixed, chainKey))
        }
        sink.rewrite(2) { it.copy(prevHash = "ffff") }
        val broken = log.verify()
        assertFalse(broken.ok)
        assertEquals(2L, broken.failedSeq)
        assertEquals("previous hash mismatch", broken.reason)
    }

    @Test fun sqliteIsAppendOnlyAndDetectsTampering(@TempDir dir: Path) {
        val fixture = sqliteLog(dir.resolve("db"))
        val path = fixture.db
        val log = fixture.log
        log.append(draft(1))
        log.append(draft(2))
        assertTrue(log.verify().ok)
        assertTrue(Files.exists(fixture.anchorFile))
        assertFalse(fixture.anchorFile.fileName.toString().endsWith(".sqlite"))
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
            val update = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE audit_event SET action = 'tampered' WHERE seq = 1")
            }
            assertTrue(update.message!!.contains("append-only"))
            val delete = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("DELETE FROM audit_event WHERE seq = 2")
            }
            assertTrue(delete.message!!.contains("append-only"))
            conn.createStatement().execute("DROP TRIGGER audit_event_no_update")
            conn.createStatement().executeUpdate("UPDATE audit_event SET detail = 'edited' WHERE seq = 1")
        }
        val tampered = log.verify()
        assertFalse(tampered.ok)
        assertEquals(1L, tampered.failedSeq)
        fixture.sink.close()
        val reopenedSink = SqliteAuditSink(path)
        val reopened = ChainedAuditLog(
            reopenedSink,
            chainKey,
            MacAuditAnchor(FileAuditAnchorStore(fixture.anchorFile), AuditKeys.anchorKey(secret)),
        )
        try {
            assertFalse(reopened.verify().ok)
            val third = reopened.append(draft(3))
            assertEquals(3L, third.seq)
        } finally {
            reopenedSink.close()
        }
    }

    @Test fun insertedMiddleRowWithRecomputedHashesFails() {
        val fixture = sqliteLog(tmp.resolve("insert"))
        repeat(4) { fixture.log.append(draft(it + 1)) }
        val original = rows(fixture.log)
        val anchorBefore = Files.readAllBytes(fixture.anchorFile)
        val inserted = original[1].copy(action = "inserted", detail = "forged middle")
        val forged = rechain(listOf(original[0], inserted) + original.drop(1), chainKey)
        rewrite(fixture.db, forged)
        assertArrayEquals(anchorBefore, Files.readAllBytes(fixture.anchorFile))
        val result = fixture.log.verify()
        assertFalse(result.ok)
        assertEquals("anchor mismatch", result.reason)
        assertEquals(5L, result.failedSeq)
        fixture.sink.close()
    }

    @Test fun deletedMiddleRowFails() {
        val fixture = sqliteLog(tmp.resolve("delete"))
        repeat(4) { fixture.log.append(draft(it + 1)) }
        val original = rows(fixture.log)
        val forged = rechain(original.filter { it.seq != 2L }, chainKey)
        rewrite(fixture.db, forged)
        val result = fixture.log.verify()
        assertFalse(result.ok)
        assertEquals("anchor mismatch", result.reason)
        assertEquals(3L, result.failedSeq)
        assertEquals(3, result.records)
        fixture.sink.close()
    }

    @Test fun truncatedTailFails() {
        val fixture = sqliteLog(tmp.resolve("truncate"))
        repeat(4) { fixture.log.append(draft(it + 1)) }
        val original = rows(fixture.log)
        val anchorBefore = Files.readAllBytes(fixture.anchorFile)
        rewrite(fixture.db, original.filter { it.seq <= 2L })
        assertArrayEquals(anchorBefore, Files.readAllBytes(fixture.anchorFile))
        val result = fixture.log.verify()
        assertFalse(result.ok)
        assertEquals("anchor mismatch", result.reason)
        assertEquals(2L, result.failedSeq)
        assertEquals(2, result.records)
        fixture.sink.close()
    }

    @Test fun fullyRecomputedChainFails() {
        val fixture = sqliteLog(tmp.resolve("rebuild"))
        repeat(4) { fixture.log.append(draft(it + 1)) }
        val forged = sha256Chain(rows(fixture.log))
        rewrite(fixture.db, forged)
        val attacker = SecretKeyLoader.fromBytes(ByteArray(32) { 1 })
        MacAuditAnchor(FileAuditAnchorStore(fixture.anchorFile), AuditKeys.anchorKey(attacker))
            .store(forged.last().seq, forged.last().hash)
        val withoutKey = fixture.log.verify()
        assertFalse(withoutKey.ok)
        assertEquals("anchor mac mismatch", withoutKey.reason)

        fixture.anchor.store(forged.last().seq, forged.last().hash)
        val keyedAnchor = fixture.log.verify()
        assertFalse(keyedAnchor.ok)
        assertEquals("hash mismatch", keyedAnchor.reason)
        assertEquals(1L, keyedAnchor.failedSeq)
        fixture.sink.close()
    }

    @Test fun failedAnchorWriteIsRecoveredWhenTheTipHmacHolds() {
        val sink = MemoryAuditSink()
        val backing = MacAuditAnchor(MemoryAuditAnchorStore(), AuditKeys.anchorKey(secret))
        val flaky = FailNextStore(backing)
        val log = ChainedAuditLog(sink, chainKey, flaky)
        log.append(draft(1))
        flaky.failures = 1
        val boom = assertThrows(IOException::class.java) { log.append(draft(2)) }
        assertEquals("disk full", boom.message)
        assertEquals(2L, sink.latest()!!.seq)
        assertEquals(1L, (backing.load() as AnchorLoad.Trusted).seq)

        val recovered = log.verify()
        assertTrue(recovered.ok)
        assertEquals("anchor lagging by one (recovered)", recovered.reason)
        assertTrue(recovered.anchorRepaired)
        assertNull(recovered.failedSeq)
        val head = backing.load() as AnchorLoad.Trusted
        assertEquals(2L, head.seq)
        assertEquals(sink.latest()!!.hash, head.hash)

        val again = log.verify()
        assertTrue(again.ok)
        assertNull(again.reason)
        assertFalse(again.anchorRepaired)
    }

    @Test fun anchorTwoBehindOrATamperedTipIsNotRecovery() {
        val sink = MemoryAuditSink()
        val anchor = MacAuditAnchor(MemoryAuditAnchorStore(), AuditKeys.anchorKey(secret))
        val log = ChainedAuditLog(sink, chainKey, anchor)
        val first = log.append(draft(1))
        val second = log.append(draft(2))
        anchor.store(second.seq + 1, second.hash)
        val ahead = log.verify()
        assertFalse(ahead.ok)
        assertEquals("anchor mismatch", ahead.reason)
        assertFalse(ahead.anchorRepaired)
        val bare = second.copy(seq = 3, prevHash = second.hash, action = "extra", detail = "direct", hash = "")
        sink.append(bare.copy(hash = AuditChain.hash(bare, chainKey)))
        anchor.store(first.seq, first.hash)
        val twoBehind = log.verify()
        assertFalse(twoBehind.ok)
        assertEquals("anchor mismatch", twoBehind.reason)
        assertFalse(twoBehind.anchorRepaired)
        assertEquals(1L, (anchor.load() as AnchorLoad.Trusted).seq)

        anchor.store(second.seq, second.hash)
        sink.rewrite(3) { it.copy(detail = "tampered") }
        val tampered = log.verify()
        assertFalse(tampered.ok)
        assertEquals("hash mismatch", tampered.reason)
        assertEquals(3L, tampered.failedSeq)
        assertFalse(tampered.anchorRepaired)
        assertEquals(2L, (anchor.load() as AnchorLoad.Trusted).seq)
    }

    @Test fun auditDatabaseIsFullySynchronousBeforeTheAnchorMoves(@TempDir dir: Path) {
        val sink = SqliteAuditSink(dir.resolve("audit.sqlite"))
        try {
            // SQLite: 0 OFF, 1 NORMAL, 2 FULL. FULL is what keeps a commit from vanishing after the anchor moves.
            assertEquals(2, sink.synchronousMode())
        } finally {
            sink.close()
        }
    }

    @Test fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val okm = hkdfSha256(ikm, info, length = 42, salt = salt)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.encode(okm),
        )
    }

    @Test fun detailRedactsArgon2AndTokens() {
        val log = memoryLog()
        val hash = Argon2idHasher.fast().hash("Abcdefg1!".toCharArray())
        val stored = log.append(AuditDraft(1, "alice", "login", AuditStatus.SUCCESS, detail = "pw hash $hash token oipk_0123456789abcdef_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(stored.detail.contains("argon2id"))
        assertFalse(stored.detail.contains("oipk_0123456789abcdef_"))
        assertTrue(stored.detail.contains("***"))
    }
}
