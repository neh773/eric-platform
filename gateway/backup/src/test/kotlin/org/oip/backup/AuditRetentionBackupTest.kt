package org.oip.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import org.oip.security.ChainedAuditLog
import org.oip.security.FileAuditAnchorStore
import org.oip.security.MacAuditAnchor
import org.oip.security.SqliteAuditSink
import java.nio.file.Files
import java.nio.file.Path

/** SEC-001: a backup of a log that retention pruned carries the chain base, so the restored log still verifies. */
class AuditRetentionBackupTest {
    @TempDir lateinit var tmp: Path
    // Fixed test keys; the gateway derives these from its secrets key.
    private val chainKey = ByteArray(32) { 3 }
    private val anchorKey = ByteArray(32) { 4 }

    private fun open(dir: Path): Pair<SqliteAuditSink, ChainedAuditLog> {
        val sink = SqliteAuditSink(dir.resolve("audit.sqlite"))
        val anchor = MacAuditAnchor(FileAuditAnchorStore(dir.resolve("audit.anchor")), anchorKey)
        return sink to ChainedAuditLog(sink, chainKey, anchor)
    }

    @Test fun prunedLogRoundTripsThroughTheDumpAndVerifies() {
        val src = tmp.resolve("src").also { Files.createDirectories(it) }
        val (sink, log) = open(src)
        for (i in 1..6) log.append(AuditDraft(1_000L * i, "alice", "login", AuditStatus.SUCCESS))
        assertEquals(3, log.prune(3_500L, 10_000L).removed)
        sink.close()

        val dump = SecuritySnapshot.readAudit(src.resolve("audit.sqlite"))!!
        assertTrue(dump.startsWith(SecuritySnapshot.AUDIT_HEADER_V2 + "\n"), dump)
        val dst = tmp.resolve("dst").also { Files.createDirectories(it) }
        SecuritySnapshot.writeAudit(dst.resolve("audit.sqlite"), dump)
        Files.copy(src.resolve("audit.anchor"), dst.resolve("audit.anchor"))
        assertEquals(dump, SecuritySnapshot.readAudit(dst.resolve("audit.sqlite")))

        val (sink2, restored) = open(dst)
        try {
            assertEquals(3L, restored.base()!!.seq)
            assertTrue(restored.verify().ok, restored.verify().reason)
            assertEquals(7L, restored.append(AuditDraft(11_000L, "bob", "login", AuditStatus.SUCCESS)).seq)
            assertTrue(restored.verify().ok)
        } finally {
            sink2.close()
        }
    }

    @Test fun aNeverPrunedLogKeepsTheV1Dump() {
        val dir = tmp.resolve("v1").also { Files.createDirectories(it) }
        val (sink, log) = open(dir)
        log.append(AuditDraft(1L, "alice", "login", AuditStatus.SUCCESS))
        sink.close()
        val dump = SecuritySnapshot.readAudit(dir.resolve("audit.sqlite"))!!
        assertTrue(dump.startsWith(SecuritySnapshot.AUDIT_HEADER + "\n"))
        assertNull(SecuritySnapshot.parseAudit(dump).base)
    }
}
