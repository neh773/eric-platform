package org.oip.security

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

enum class AuditStatus { SUCCESS, FAILURE }

/**
 * One row of the append-only audit log. [hash] is HMAC-SHA256 over a canonical encoding of every other field,
 * including [prevHash], keyed by an HKDF subkey of the gateway secrets key (`oip-audit`). The first row's
 * [prevHash] is [AuditChain.GENESIS]. Recomputing the chain without that key does not verify.
 *
 * Action strings used for gateway config and logins follow the public action list where it names them
 * (`gateway config added`, `gateway config updated`, `login`, `login attempt`, `token login attempt`):
 */
data class AuditRecord(
    val seq: Long,
    val timestampMillis: Long,
    val actor: String,
    val actorHost: String,
    val action: String,
    val target: String,
    val detail: String,
    val status: AuditStatus,
    val origin: String,
    val context: String,
    val prevHash: String,
    val hash: String,
) {
    val timestamp: Instant get() = Instant.ofEpochMilli(timestampMillis)
}

data class AuditDraft(
    val timestampMillis: Long,
    val actor: String,
    val action: String,
    val status: AuditStatus,
    val actorHost: String = "",
    val target: String = "",
    val detail: String = "",
    val origin: String = "local",
    val context: String = "",
)

data class AuditQuery(
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val actor: String? = null,
    val action: String? = null,
    val targetContains: String? = null,
    val status: AuditStatus? = null,
    val limit: Int = 100,
    val offset: Int = 0,
)

data class AuditPage(val records: List<AuditRecord>, val total: Long, val limit: Int, val offset: Int)

data class AuditVerification(
    val ok: Boolean,
    val records: Int,
    val failedSeq: Long?,
    val reason: String?,
    /**
     * True when the anchor was exactly one committed row behind a tip whose HMAC verified, and the caller
     * should rewrite the anchor to that tip. This is not tampering: [ok] stays true.
     */
    val anchorRepaired: Boolean = false,
)

/**
 * Where the stored chain starts after retention removed older rows (SEC-001). [seq] and [hash] are the sequence
 * number and HMAC of the last removed row, so the oldest kept row has seq [seq] + 1 and prevHash [hash]. [mac] is
 * an HMAC under the chain key over a domain-separated encoding of [seq], [hash] and [prunedAtMillis]
 * ([AuditChain.baseMac]), so only a holder of the secrets key can move the start of the chain. Removing rows without
 * moving the base, or a base edited without the key, fails [AuditLog.verify].
 */
data class AuditBase(val seq: Long, val hash: String, val prunedAtMillis: Long, val mac: String)

/** What [AuditLog.prune] removed. [base] is null when nothing was removed. */
data class AuditPrune(val removed: Long, val base: AuditBase?)

/**
 * Hash-chained log. Rows are never updated. The only removal is [prune], which drops the oldest rows up to a
 * cutoff and records a keyed [AuditBase] for where the kept chain starts, in the same transaction.
 * [verify] checks the base, the HMAC chain from it, and a head anchor stored outside the audit database, so a
 * truncated tail, a prefix removed without the base, or a chain rebuilt without the secrets key fails. An anchor
 * exactly one row behind a tip that still passes the keyed HMAC is reported as [AuditChain.ANCHOR_LAGGING] and
 * repaired; that is a crash between the row commit and the checkpoint write, not tampering.
 */
interface AuditLog {
    fun append(draft: AuditDraft): AuditRecord
    fun query(query: AuditQuery = AuditQuery()): AuditPage
    fun verify(): AuditVerification
    fun latest(): AuditRecord?
    fun countOlderThan(millis: Long): Long
    /**
     * Remove rows with a timestamp before [beforeMillis], oldest first, stopping at the first row that is not older.
     * The newest row is always kept so the head anchor keeps naming a stored row. [nowMillis] is stamped on the base.
     */
    fun prune(beforeMillis: Long, nowMillis: Long): AuditPrune
    /** Where the stored chain starts, or null when no row was ever removed. */
    fun base(): AuditBase?
    /** Rows with seq greater than [afterSeq], oldest first. */
    fun recordsAfter(afterSeq: Long, limit: Int): List<AuditRecord>
}

/**
 * Pluggable persistence for [ChainedAuditLog]. Implementations never update rows. They delete rows only in
 * [prune], which stores the new base and removes the rows at or below it atomically.
 */
interface AuditSink : AutoCloseable {
    /** The stored chain base, or null when no row was ever removed. */
    fun base(): AuditBase?
    /** Store [base] and delete every row with seq at or below [AuditBase.seq], in one transaction. Returns rows removed. */
    fun prune(base: AuditBase): Long
    /** Oldest rows first, at most [limit], with timestamp before [beforeMillis]. */
    fun oldestBefore(beforeMillis: Long, limit: Int): List<AuditRecord>
    fun latest(): AuditRecord?
    fun append(record: AuditRecord)
    fun readAll(): List<AuditRecord>
    fun query(query: AuditQuery): AuditPage
    fun countOlderThan(millis: Long): Long
    /** Rows with seq greater than [afterSeq], oldest first. */
    fun recordsAfter(afterSeq: Long, limit: Int): List<AuditRecord>
    /** Drop the file handle so a restore can rename the database. No-op for in-memory sinks. */
    fun release() {}
    /** Open the backing file again, after a backup restore replaced it on disk. No-op for in-memory sinks. */
    fun reopen() {}
    override fun close() {}
}

object AuditChain {
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * Distinct from [anchor mismatch]. The row is already committed and its HMAC checked; the checkpoint
     * write did not finish. [ok] is true. [ChainedAuditLog.verify] rewrites the anchor to the tip.
     */
    const val ANCHOR_LAGGING = "anchor lagging by one (recovered)"

    /** Canonical record bytes. The stored [AuditRecord.hash] is not an input. */
    fun payload(record: AuditRecord): ByteArray = listOf(
        record.seq.toString(),
        record.timestampMillis.toString(),
        record.actor,
        record.actorHost,
        record.action,
        record.target,
        record.detail,
        record.status.name,
        record.origin,
        record.context,
        record.prevHash,
    ).joinToString("\u001e") { escape(it) }.toByteArray(Charsets.UTF_8)

    fun hash(record: AuditRecord, chainKey: ByteArray): String = Hex.encode(hmacSha256(chainKey, payload(record)))

    /** MAC of a chain base. The `oip-audit-base` prefix keeps it from colliding with a record payload. */
    fun baseMac(seq: Long, hash: String, prunedAtMillis: Long, chainKey: ByteArray): String =
        Hex.encode(hmacSha256(chainKey, listOf(BASE_DOMAIN, seq.toString(), hash, prunedAtMillis.toString())
            .joinToString("\u001e") { escape(it) }.toByteArray(Charsets.UTF_8)))

    private const val BASE_DOMAIN = "oip-audit-base"

    /**
     * Walk [records] from genesis, then compare the tip to the trusted anchor.
     * A missing anchor is acceptable only for an empty log. A prefix of a longer log verifies the
     * HMAC links and still fails the anchor comparison.
     *
     * An anchor whose seq is exactly the tip minus one, and whose hash is the previous record's hash
     * (or [GENESIS] when the tip is seq 1), is a crash or a failed checkpoint write after the row
     * commit. The tip row has already passed the keyed HMAC in the walk above, so only a holder of the
     * chain key could have appended it. That returns [ANCHOR_LAGGING] with [AuditVerification.ok] true
     * and [AuditVerification.anchorRepaired] true. A missing anchor, an anchor two or more behind, or an
     * anchor ahead of the tip stays a failure.
     */
    fun verify(records: List<AuditRecord>, chainKey: ByteArray, anchor: AuditAnchor, base: AuditBase? = null): AuditVerification {
        val loaded = anchor.load()
        if (loaded is AnchorLoad.Rejected) return AuditVerification(false, records.size, null, loaded.reason)
        if (base != null) {
            val expected = baseMac(base.seq, base.hash, base.prunedAtMillis, chainKey)
            if (base.seq < 1 || !ConstantTime.equal(base.mac, expected)) {
                return AuditVerification(false, records.size, base.seq, "retention base mismatch")
            }
        }
        var prev = base?.hash ?: GENESIS
        var expectedSeq = (base?.seq ?: 0L) + 1L
        for (r in records) {
            if (r.seq != expectedSeq) return AuditVerification(false, records.size, r.seq, "sequence gap or reorder")
            if (!ConstantTime.equal(r.prevHash, prev)) return AuditVerification(false, records.size, r.seq, "previous hash mismatch")
            val recomputed = hash(r, chainKey)
            if (!ConstantTime.equal(r.hash, recomputed)) return AuditVerification(false, records.size, r.seq, "hash mismatch")
            prev = r.hash
            expectedSeq++
        }
        val tipSeq = records.lastOrNull()?.seq ?: base?.seq ?: 0L
        val tipHash = records.lastOrNull()?.hash ?: base?.hash ?: GENESIS
        return when (loaded) {
            AnchorLoad.Absent ->
                if (records.isEmpty() && base == null) AuditVerification(true, 0, null, null)
                else AuditVerification(false, records.size, tipSeq, "missing audit anchor")
            is AnchorLoad.Trusted -> when {
                loaded.seq == tipSeq && ConstantTime.equal(loaded.hash, tipHash) ->
                    AuditVerification(true, records.size, null, null)
                records.isNotEmpty() && loaded.seq == tipSeq - 1 && ConstantTime.equal(loaded.hash, hashBeforeTip(records, base)) ->
                    AuditVerification(true, records.size, null, ANCHOR_LAGGING, anchorRepaired = true)
                else ->
                    AuditVerification(false, records.size, if (records.isEmpty()) loaded.seq else tipSeq, "anchor mismatch")
            }
            is AnchorLoad.Rejected -> AuditVerification(false, records.size, null, loaded.reason)
        }
    }

    /** Hash stored for the record before the tip: the base hash when the tip is the first kept row, else [GENESIS]. */
    private fun hashBeforeTip(records: List<AuditRecord>, base: AuditBase?): String =
        if (records.size >= 2) records[records.size - 2].hash else base?.hash ?: GENESIS

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\u001e", "\\x1e").replace("\n", "\\n")
}

/**
 * HMAC-SHA256 chain plus a head checkpoint kept outside the row store.
 * [chainKey] and the anchor MAC key are HKDF-SHA256 subkeys of the gateway secrets key
 * (info `oip-audit` and `oip-audit-anchor`), so editing the database without that key cannot
 * produce a chain [verify] accepts.
 *
 * [append] commits the row and then updates the anchor. If the process dies, or [AuditAnchor.store]
 * throws, between those two steps, the anchor is one row behind. [verify] accepts that, reports
 * [AuditChain.ANCHOR_LAGGING], and rewrites the anchor to the tip. The audit database uses
 * synchronous=FULL, so the row is durable before the anchor is moved and power loss cannot leave
 * the anchor ahead of a row the database dropped.
 */
class ChainedAuditLog(
    private val sink: AuditSink,
    chainKey: ByteArray,
    private var anchor: AuditAnchor,
) : AuditLog {
    private var chainKey = chainKey.copyOf()
    private val lock = Any()

    /**
     * Continue the chain that a backup restore put on disk: reopen the sink and take the keys derived from the
     * restored secrets key, so the next row extends the restored chain and verifies after a restart.
     */
    fun release() = synchronized(lock) { sink.release() }

    fun reopenSink() = synchronized(lock) { sink.reopen() }

    fun reload(chainKey: ByteArray, anchor: AuditAnchor) = synchronized(lock) {
        require(chainKey.size == 32) { "audit chain key must be 32 bytes" }
        sink.reopen()
        this.chainKey = chainKey.copyOf()
        this.anchor = anchor
    }

    init {
        require(this.chainKey.size == 32) { "audit chain key must be 32 bytes" }
    }

    override fun append(draft: AuditDraft): AuditRecord = synchronized(lock) {
        val last = sink.latest()
        val base = if (last == null) sink.base() else null
        val seq = (last?.seq ?: base?.seq ?: 0L) + 1L
        val prev = last?.hash ?: base?.hash ?: AuditChain.GENESIS
        val bare = AuditRecord(
            seq, draft.timestampMillis, draft.actor, draft.actorHost, draft.action, draft.target,
            sanitize(draft.detail), draft.status, draft.origin, draft.context, prev, hash = "",
        )
        val stored = bare.copy(hash = AuditChain.hash(bare, chainKey))
        sink.append(stored)
        // The row is durable before this returns when the sink is SQLite with synchronous=FULL.
        // A throw here leaves the anchor one row behind; verify() repairs that. The row is not rolled back.
        anchor.store(stored.seq, stored.hash)
        stored
    }

    override fun query(query: AuditQuery): AuditPage = synchronized(lock) {
        require(query.limit in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
        require(query.offset >= 0) { "offset must be >= 0" }
        sink.query(query)
    }

    override fun verify(): AuditVerification = synchronized(lock) {
        val records = sink.readAll()
        val result = AuditChain.verify(records, chainKey, anchor, sink.base())
        if (result.anchorRepaired) {
            val tip = records.last()
            anchor.store(tip.seq, tip.hash)
        }
        result
    }

    override fun latest(): AuditRecord? = synchronized(lock) { sink.latest() }

    override fun countOlderThan(millis: Long): Long = synchronized(lock) { sink.countOlderThan(millis) }

    override fun base(): AuditBase? = synchronized(lock) { sink.base() }

    /**
     * Remove expired rows in batches of [PRUNE_BATCH]. Each batch moves the base to its last removed row, so a crash
     * between batches leaves a chain that still verifies. The newest row is never removed.
     */
    override fun prune(beforeMillis: Long, nowMillis: Long): AuditPrune = synchronized(lock) {
        val tip = sink.latest() ?: return AuditPrune(0, null)
        var removed = 0L
        var base: AuditBase? = null
        while (true) {
            val batch = sink.oldestBefore(beforeMillis, PRUNE_BATCH).takeWhile { it.seq < tip.seq }
            if (batch.isEmpty()) break
            val last = batch.last()
            val next = AuditBase(last.seq, last.hash, nowMillis, AuditChain.baseMac(last.seq, last.hash, nowMillis, chainKey))
            removed += sink.prune(next)
            base = next
            if (batch.size < PRUNE_BATCH) break
        }
        AuditPrune(removed, base)
    }

    override fun recordsAfter(afterSeq: Long, limit: Int): List<AuditRecord> = synchronized(lock) {
        require(limit in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
        sink.recordsAfter(afterSeq, limit)
    }

    private fun sanitize(detail: String): String {
        var s = detail
        s = ARGON.replace(s, "***")
        s = TOKEN.replace(s, "oipk_***")
        return s
    }

    companion object {
        const val MAX_LIMIT = 1000
        const val PRUNE_BATCH = 5000
        private val ARGON = Regex("\\\$argon2id\\\$\\S*")
        private val TOKEN = Regex("oipk_[0-9a-f]{16}_[A-Za-z0-9_-]+")
    }
}

/** In-memory sink. [rewrite] exists so tests can simulate tampering; it is not on [AuditLog]. */
class MemoryAuditSink : AuditSink {
    private val rows = mutableListOf<AuditRecord>()
    private var base: AuditBase? = null

    override fun latest(): AuditRecord? = rows.lastOrNull()

    override fun append(record: AuditRecord) {
        val last = rows.lastOrNull()
        require(record.seq == (last?.seq ?: base?.seq ?: 0L) + 1L) { "append must extend the chain" }
        rows += record
    }

    override fun base(): AuditBase? = base

    override fun prune(base: AuditBase): Long {
        require(base.seq >= (this.base?.seq ?: 0L)) { "the chain base only moves forward" }
        val before = rows.size
        rows.removeAll { it.seq <= base.seq }
        this.base = base
        return (before - rows.size).toLong()
    }

    override fun oldestBefore(beforeMillis: Long, limit: Int): List<AuditRecord> =
        rows.sortedBy { it.seq }.takeWhile { it.timestampMillis < beforeMillis }.take(limit)

    /** Test hook: drop rows without moving the base, as an attacker with file access could. */
    internal fun dropThrough(seq: Long) {
        rows.removeAll { it.seq <= seq }
    }

    /** Test hook: replace the base without the chain key. */
    internal fun forceBase(base: AuditBase?) {
        this.base = base
    }

    override fun readAll(): List<AuditRecord> = rows.toList()

    override fun query(query: AuditQuery): AuditPage {
        val matched = rows.filter { matches(it, query) }.sortedByDescending { it.seq }
        val page = matched.drop(query.offset).take(query.limit)
        return AuditPage(page, matched.size.toLong(), query.limit, query.offset)
    }

    override fun countOlderThan(millis: Long) = rows.count { it.timestampMillis < millis }.toLong()

    override fun recordsAfter(afterSeq: Long, limit: Int): List<AuditRecord> =
        rows.filter { it.seq > afterSeq }.sortedBy { it.seq }.take(limit)

    internal fun rewrite(seq: Long, transform: (AuditRecord) -> AuditRecord) {
        val i = rows.indexOfFirst { it.seq == seq }
        require(i >= 0)
        rows[i] = transform(rows[i])
    }

    private fun matches(r: AuditRecord, q: AuditQuery): Boolean {
        if (q.fromMillis != null && r.timestampMillis < q.fromMillis) return false
        if (q.toMillis != null && r.timestampMillis > q.toMillis) return false
        if (q.actor != null && !r.actor.equals(q.actor, ignoreCase = true)) return false
        if (q.action != null && !r.action.equals(q.action, ignoreCase = true)) return false
        if (q.targetContains != null && !r.target.contains(q.targetContains, ignoreCase = true)) return false
        if (q.status != null && r.status != q.status) return false
        return true
    }
}

/**
 * SQLite sink (ADR 0008): one file, WAL, append-only triggers, synchronous=FULL.
 * FULL makes a commit durable before [append] returns. The anchor is updated only after that, so a
 * power loss cannot leave the checkpoint naming a row this file no longer has. A crash in the other
 * direction (row durable, anchor not yet moved) is the one-row lag [AuditLog.verify] repairs.
 * A holder of the file can drop the triggers. [AuditLog.verify] still rejects an edited row, a gap,
 * a truncated tail, and a chain recomputed without the audit HMAC key. The expected head is not stored
 * in this database; it lives in the anchor file next to it.
 */
class SqliteAuditSink(val path: Path) : AuditSink {
    @Volatile private var connection: Connection = connect()

    private fun connect(): Connection {
        Files.createDirectories(path.parent)
        Class.forName("org.sqlite.JDBC")
        val connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.autoCommit = true
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            // FULL, not NORMAL: the WAL frame is synced before commit returns, so the anchor cannot move
            // ahead of a row that power loss would drop. SQLite reports FULL as 2.
            st.execute("PRAGMA synchronous=FULL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS audit_event (
                  seq INTEGER PRIMARY KEY,
                  ts_millis INTEGER NOT NULL,
                  actor TEXT NOT NULL,
                  actor_host TEXT NOT NULL,
                  action TEXT NOT NULL,
                  target TEXT NOT NULL,
                  detail TEXT NOT NULL,
                  status TEXT NOT NULL,
                  origin TEXT NOT NULL,
                  context TEXT NOT NULL,
                  prev_hash TEXT NOT NULL,
                  hash TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.execute("CREATE INDEX IF NOT EXISTS audit_event_ts ON audit_event(ts_millis)")
            st.execute("CREATE INDEX IF NOT EXISTS audit_event_actor ON audit_event(actor)")
            st.execute("CREATE INDEX IF NOT EXISTS audit_event_action ON audit_event(action)")
            st.execute(
                """
                CREATE TRIGGER IF NOT EXISTS audit_event_no_update
                BEFORE UPDATE ON audit_event
                BEGIN
                  SELECT RAISE(ABORT, 'audit log is append-only');
                END
                """.trimIndent()
            )
            // One row: where the kept chain starts after retention removed older rows (AuditBase).
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS audit_base (
                  id INTEGER PRIMARY KEY CHECK (id = 1),
                  seq INTEGER NOT NULL,
                  hash TEXT NOT NULL,
                  pruned_at INTEGER NOT NULL,
                  mac TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TRIGGER IF NOT EXISTS audit_base_forward_only
                BEFORE UPDATE ON audit_base
                WHEN NEW.seq < OLD.seq
                BEGIN
                  SELECT RAISE(ABORT, 'audit base only moves forward');
                END
                """.trimIndent()
            )
            // Rows at or below the base may be deleted (retention). Every other delete is refused. Older files carry
            // the unconditional trigger, so it is replaced on open.
            st.execute("DROP TRIGGER IF EXISTS audit_event_no_delete")
            st.execute(
                """
                CREATE TRIGGER audit_event_no_delete
                BEFORE DELETE ON audit_event
                WHEN OLD.seq > COALESCE((SELECT seq FROM audit_base WHERE id = 1), 0)
                BEGIN
                  SELECT RAISE(ABORT, 'audit log is append-only');
                END
                """.trimIndent()
            )
        }
        return connection
    }

    override fun base(): AuditBase? {
        connection.prepareStatement("SELECT seq, hash, pruned_at, mac FROM audit_base WHERE id = 1").use { ps ->
            ps.executeQuery().use { rs ->
                return if (rs.next()) AuditBase(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)) else null
            }
        }
    }

    override fun prune(base: AuditBase): Long {
        val conn = connection
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                """
                INSERT INTO audit_base(id, seq, hash, pruned_at, mac) VALUES (1, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET seq = excluded.seq, hash = excluded.hash, pruned_at = excluded.pruned_at, mac = excluded.mac
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, base.seq)
                ps.setString(2, base.hash)
                ps.setLong(3, base.prunedAtMillis)
                ps.setString(4, base.mac)
                ps.executeUpdate()
            }
            val removed = conn.prepareStatement("DELETE FROM audit_event WHERE seq <= ?").use { ps ->
                ps.setLong(1, base.seq)
                ps.executeUpdate().toLong()
            }
            conn.commit()
            return removed
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    override fun oldestBefore(beforeMillis: Long, limit: Int): List<AuditRecord> {
        // Oldest first by seq, stopping at the first row that is not older (a clock step back keeps later rows).
        connection.prepareStatement("SELECT * FROM audit_event ORDER BY seq ASC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<AuditRecord>()
                while (rs.next()) {
                    val r = read(rs)
                    if (r.timestampMillis >= beforeMillis) break
                    out += r
                }
                return out
            }
        }
    }

    /** A restore renames a new database over [path]; the open connection still points at the old file. */
    override fun release() {
        if (!connection.isClosed) {
            runCatching { connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
            connection.close()
        }
    }

    override fun reopen() {
        release()
        connection = connect()
    }

    override fun latest(): AuditRecord? {
        connection.prepareStatement("SELECT * FROM audit_event ORDER BY seq DESC LIMIT 1").use { ps ->
            ps.executeQuery().use { rs -> return if (rs.next()) read(rs) else null }
        }
    }

    override fun append(record: AuditRecord) {
        connection.prepareStatement(
            """
            INSERT INTO audit_event(seq, ts_millis, actor, actor_host, action, target, detail, status, origin, context, prev_hash, hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, record.seq)
            ps.setLong(2, record.timestampMillis)
            ps.setString(3, record.actor)
            ps.setString(4, record.actorHost)
            ps.setString(5, record.action)
            ps.setString(6, record.target)
            ps.setString(7, record.detail)
            ps.setString(8, record.status.name)
            ps.setString(9, record.origin)
            ps.setString(10, record.context)
            ps.setString(11, record.prevHash)
            ps.setString(12, record.hash)
            ps.executeUpdate()
        }
    }

    override fun readAll(): List<AuditRecord> {
        connection.prepareStatement("SELECT * FROM audit_event ORDER BY seq ASC").use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<AuditRecord>()
                while (rs.next()) out += read(rs)
                return out
            }
        }
    }

    override fun query(query: AuditQuery): AuditPage {
        val (where, args) = where(query)
        val total = connection.prepareStatement("SELECT COUNT(*) FROM audit_event WHERE $where").use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        val records = connection.prepareStatement(
            "SELECT * FROM audit_event WHERE $where ORDER BY seq DESC LIMIT ? OFFSET ?"
        ).use { ps ->
            bind(ps, args)
            ps.setInt(args.size + 1, query.limit)
            ps.setInt(args.size + 2, query.offset)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<AuditRecord>()
                while (rs.next()) out += read(rs)
                out
            }
        }
        return AuditPage(records, total, query.limit, query.offset)
    }

    override fun countOlderThan(millis: Long): Long {
        connection.prepareStatement("SELECT COUNT(*) FROM audit_event WHERE ts_millis < ?").use { ps ->
            ps.setLong(1, millis)
            ps.executeQuery().use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    override fun recordsAfter(afterSeq: Long, limit: Int): List<AuditRecord> {
        connection.prepareStatement(
            "SELECT * FROM audit_event WHERE seq > ? ORDER BY seq ASC LIMIT ?",
        ).use { ps ->
            ps.setLong(1, afterSeq)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<AuditRecord>()
                while (rs.next()) out += read(rs)
                return out
            }
        }
    }

    override fun close() {
        connection.close()
    }

    /** `PRAGMA synchronous` on this connection. FULL is 2, NORMAL is 1. */
    internal fun synchronousMode(): Int {
        connection.createStatement().use { st ->
            st.executeQuery("PRAGMA synchronous").use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    private fun where(q: AuditQuery): Pair<String, List<Any>> {
        val clauses = mutableListOf("1=1")
        val args = mutableListOf<Any>()
        q.fromMillis?.let { clauses += "ts_millis >= ?"; args += it }
        q.toMillis?.let { clauses += "ts_millis <= ?"; args += it }
        q.actor?.let { clauses += "actor = ? COLLATE NOCASE"; args += it }
        q.action?.let { clauses += "action = ? COLLATE NOCASE"; args += it }
        q.targetContains?.let { clauses += "instr(lower(target), lower(?)) > 0"; args += it }
        q.status?.let { clauses += "status = ?"; args += it.name }
        return clauses.joinToString(" AND ") to args
    }

    private fun bind(ps: java.sql.PreparedStatement, args: List<Any>) {
        args.forEachIndexed { i, v ->
            when (v) {
                is Long -> ps.setLong(i + 1, v)
                is Int -> ps.setInt(i + 1, v)
                else -> ps.setString(i + 1, v.toString())
            }
        }
    }

    private fun read(rs: java.sql.ResultSet) = AuditRecord(
        seq = rs.getLong("seq"),
        timestampMillis = rs.getLong("ts_millis"),
        actor = rs.getString("actor"),
        actorHost = rs.getString("actor_host"),
        action = rs.getString("action"),
        target = rs.getString("target"),
        detail = rs.getString("detail"),
        status = AuditStatus.valueOf(rs.getString("status")),
        origin = rs.getString("origin"),
        context = rs.getString("context"),
        prevHash = rs.getString("prev_hash"),
        hash = rs.getString("hash"),
    )
}

/** Where the latest seq and hash live. This is not the audit row store. */
interface AuditAnchorStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

sealed interface AnchorLoad {
    data object Absent : AnchorLoad
    data class Trusted(val seq: Long, val hash: String) : AnchorLoad
    data class Rejected(val reason: String) : AnchorLoad
}

/** MAC'd head checkpoint. [store] replaces the checkpoint; [load] rejects a bad MAC. */
interface AuditAnchor {
    fun load(): AnchorLoad
    fun store(seq: Long, hash: String)
}

class MemoryAuditAnchorStore : AuditAnchorStore {
    private var bytes: ByteArray? = null
    override fun read(): ByteArray? = bytes?.copyOf()
    override fun write(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
    }
}

/** Checkpoint file kept beside the audit database, not inside it. */
class FileAuditAnchorStore(val path: Path) : AuditAnchorStore {
    override fun read(): ByteArray? = if (Files.exists(path)) Files.readAllBytes(path) else null

    override fun write(bytes: ByteArray) {
        SecureFiles.replace(path, bytes)
    }
}

/**
 * HMAC-SHA256 checkpoint. The MAC key is HKDF info [AuditKeys.ANCHOR_INFO], distinct from the chain key.
 * File body is `v1\n<seq>\n<hash>\n<mac>\n`. The MAC covers the first three lines, including their trailing newlines.
 */
class MacAuditAnchor(private val store: AuditAnchorStore, macKey: ByteArray) : AuditAnchor {
    private val macKey = macKey.copyOf()

    init {
        require(this.macKey.size == 32) { "audit anchor key must be 32 bytes" }
    }

    override fun load(): AnchorLoad {
        val raw = store.read() ?: return AnchorLoad.Absent
        val text = raw.toString(Charsets.UTF_8)
        val lines = text.split('\n')
        if (lines.size != 5 || lines[0] != VERSION || lines[4].isNotEmpty()) return AnchorLoad.Rejected("anchor malformed")
        val seq = lines[1].toLongOrNull() ?: return AnchorLoad.Rejected("anchor malformed")
        val hash = lines[2]
        if (!HASH.matches(hash)) return AnchorLoad.Rejected("anchor malformed")
        val body = "$VERSION\n$seq\n$hash\n"
        val expected = Hex.encode(hmacSha256(macKey, body.toByteArray(Charsets.UTF_8)))
        if (!ConstantTime.equal(lines[3], expected)) return AnchorLoad.Rejected("anchor mac mismatch")
        return AnchorLoad.Trusted(seq, hash)
    }

    override fun store(seq: Long, hash: String) {
        require(HASH.matches(hash)) { "audit hash must be 64 hex characters" }
        val body = "$VERSION\n$seq\n$hash\n"
        val mac = Hex.encode(hmacSha256(macKey, body.toByteArray(Charsets.UTF_8)))
        store.write((body + mac + "\n").toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val VERSION = "v1"
        private val HASH = Regex("[0-9a-f]{64}")
    }
}

/**
 * Subkeys of the gateway secrets key. Salt is the RFC 5869 all-zero HashLen salt.
 * Info `oip-audit` keys record HMACs. Info `oip-audit-anchor` keys the head checkpoint.
 */
internal object AuditKeys {
    const val CHAIN_INFO = "oip-audit"
    const val ANCHOR_INFO = "oip-audit-anchor"

    fun chainKey(secret: SecretKey): ByteArray = hkdfSha256(extract(secret), CHAIN_INFO.toByteArray(Charsets.UTF_8))

    fun anchorKey(secret: SecretKey): ByteArray = hkdfSha256(extract(secret), ANCHOR_INFO.toByteArray(Charsets.UTF_8))

    private fun extract(secret: SecretKey): ByteArray =
        secret.encoded ?: throw SecretKeyException("secrets key is not extractable")
}

/**
 * HKDF-SHA256 (RFC 5869). [salt] null means HashLen zero bytes. [length] is at most 255 blocks.
 * Used to derive the audit chain and anchor keys; the RFC test vector covers extract and expand.
 */
internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int = 32, salt: ByteArray? = null): ByteArray {
    require(length in 1..255 * 32) { "HKDF length out of range" }
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt ?: ByteArray(32), "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    val out = ByteArray(length)
    var filled = 0
    var previous = ByteArray(0)
    var counter = 1
    while (filled < length) {
        mac.reset()
        mac.update(previous)
        mac.update(info)
        mac.update(counter.toByte())
        previous = mac.doFinal()
        val n = minOf(previous.size, length - filled)
        previous.copyInto(out, destinationOffset = filled, startIndex = 0, endIndex = n)
        filled += n
        counter++
    }
    prk.fill(0)
    return out
}

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

object AuditActions {
    const val CONFIG_ADDED = "gateway config added"
    const val CONFIG_UPDATED = "gateway config updated"
    const val CONFIG_DELETED = "gateway config deleted"
    /** One record for POST /api/v1/config/batch, instead of one chained row per resource. */
    const val CONFIG_BATCH = "gateway config batch"
    const val LOGIN = "login"
    const val LOGIN_ATTEMPT = "login attempt"
    const val TOKEN_LOGIN = "token login attempt"
    const val LOGOUT = "logout"
    /** An administrator's test login or logout against an identity provider (SEC-018). No session is created. */
    const val IDP_TEST = "identity provider test"
    /** The root key or key-encryption key of the secret store was rotated by `oipctl rotate-secret-keys` (SEC-031). */
    const val SECRET_KEYS_ROTATED = "secret keys rotated"
    /**
     * An administrator verified a user on a user source (SEC-027): whether the user exists and, optionally, whether a
     * password matches. The detail says what was checked and the result, never the password.
     */
    const val USER_SOURCE_CHECK = "user source check"
    /**
     * One record when an account transitions into lockout, not one per later attempt while it stays locked.
     */
    const val LOCKOUT = "Authentication Lockout"
    const val SECRET_WRITTEN = "secret written"
    const val SECRET_DELETED = "secret deleted"
    /**
     * Module install, upgrade, and uninstall. The public action reference describes installing and
     * deleting a module and does not give the Action-column token; upgrade is this gateway's lifecycle.
     */
    const val MODULE_INSTALLED = "module installed"
    const val MODULE_UPGRADED = "module upgraded"
    const val MODULE_UNINSTALLED = "module uninstalled"

    /**
     * A JDBC driver JAR uploaded to or removed from the gateway (DAT-016). The target is the driver id; the detail holds
     * the driver class, size and SHA-256 of an upload. This gateway's own token: the public action reference lists none.
     */
    const val JDBC_DRIVER_UPLOADED = "jdbc driver uploaded"
    const val JDBC_DRIVER_REMOVED = "jdbc driver removed"

    /**
     * Gateway backup created, gateway restored from a backup (stored or uploaded), and a runtime log-level change.
     * One row each. A restore replaces the audit log with the archived one, so its row is written after the swap.
     */
    const val BACKUP_CREATED = "gateway backup created"
    const val BACKUP_RESTORED = "gateway backup restored"
    const val LOG_LEVEL_CHANGED = "log level changed"

    /**
     * A peer gateway acknowledged an alarm owned by this gateway.
     * The actor is the peer gateway name, or its certificate fingerprint when the name is unknown.
     * The actor host is the peer certificate fingerprint.
     */
    const val ALARM_ACKNOWLEDGED = "alarm acknowledged"

    /**
     * One row for each notification the gateway sent for an alarm event (ALA-036). Target is the alarm source, the actor is
     * the notification profile, and the detail names the recipient, event and outcome. Written only while the audit profile
     * is enabled.
     */
    const val ALARM_NOTIFICATION = "alarm notification"

    /**
     * Project create, settings change, rename, copy and delete (PRO-004). One record per request, however many
     * resources the verb moves, copies or removes.
     */
    const val PROJECT_CREATED = "project created"
    const val PROJECT_UPDATED = "project updated"
    const val PROJECT_RENAMED = "project renamed"
    const val PROJECT_COPIED = "project copied"
    const val PROJECT_DELETED = "project deleted"
    /** A project archive imported into a project (SEC-003): one record for the whole import. */
    const val PROJECT_IMPORTED = "project imported"
    /** A project image saved to or deleted from the project's image library (SEC-003). */
    const val PROJECT_IMAGE_SAVED = "project image saved"
    const val PROJECT_IMAGE_DELETED = "project image deleted"
    /** A project resource marked protected or no longer protected in the Designer (SEC-020). */
    const val PROJECT_RESOURCE_PROTECTED = "project resource protected"
    const val PROJECT_RESOURCE_UNPROTECTED = "project resource unprotected"
    /** An administrator broke another user's Designer resource lock (SEC-020). */
    const val DESIGNER_LOCK_BROKEN = "designer lock broken"
    /** An OAuth2 client registration saved, deleted, or given a refresh token by the authorization code flow (SEC-029). */
    const val OAUTH2_CLIENT_SAVED = "oauth2 client saved"
    const val OAUTH2_CLIENT_DELETED = "oauth2 client deleted"
    const val OAUTH2_CLIENT_AUTHORIZED = "oauth2 client authorized"

    /** Retention removed expired rows (SEC-001). Detail carries the count and the new chain base. */
    const val AUDIT_PRUNED = "audit records pruned"

    /** One row when a quarantine export is imported and its records are retried. */
    const val QUARANTINE_IMPORTED = "quarantine imported"

    /** One row when a provider's pending disk cache is archived on the gateway. */
    const val DISK_CACHE_ARCHIVED = "disk cache archived"

    /** One row when a disk-cache archive is loaded back onto the store-and-forward queues. */
    const val DISK_CACHE_LOADED = "disk cache loaded"

    /** One row when a gateway-side disk-cache archive file is deleted. */
    const val DISK_CACHE_ARCHIVE_DELETED = "disk cache archive deleted"
}
