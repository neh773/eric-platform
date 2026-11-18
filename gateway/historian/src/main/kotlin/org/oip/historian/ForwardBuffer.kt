package org.oip.historian

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID
import kotlin.io.path.createDirectories

/** One buffered sample on its way to a provider. ([sourceId], [seq]) identifies it exactly once. */
data class ForwardRecord(val sourceId: String, val seq: Long, val sample: HistorySample)

/**
 * One sample in a disk-cache archive, in buffer order.
 * (doc source: FEATURES HIS-025)
 */
/**
 * One sample in a disk-cache archive, in buffer order, with the identity the provider uses to skip a
 * duplicate. [seq] 0 means the record has no buffer identity yet (a quarantine import assigns a new one).
 * (doc source: FEATURES HIS-025)
 */
data class DiskCacheRecord(
    val provider: String,
    val sample: HistorySample,
    val sourceId: String = "",
    val seq: Long = 0,
)

/** What [ForwardBuffer.enqueueCapped] did: rows stored, newest rows refused, and oldest rows evicted to make room. */
data class CappedEnqueue(val stored: Int, val rejected: Int, val evicted: Int)

/** A load or import named a history provider this gateway does not have. Nothing was written. */
class UnknownHistoryProvider(val names: List<String>) : IllegalArgumentException(
    "history provider ${names.joinToString(", ")} does not exist; nothing was written",
)

/** A record a provider rejected permanently, held until someone retries or deletes it. */
data class QuarantinedRecord(
    val id: Long,
    val provider: String,
    val seq: Long,
    val sample: HistorySample,
    val error: String,
    val quarantinedMillis: Long,
)

/**
 * The local, durable store-and-forward buffer (ADR 0008: SQLite, WAL, `<dataDir>/db/history-forward.sqlite`).
 *
 * It plays the role of the reference platform 8.3's store-and-forward disk store: samples bound for an external SQL historian
 * wait here, so an outage of that database loses nothing. Records waiting to be sent are [pending]; records the
 * provider keeps rejecting (bad data rather than a lost connection) move to a quarantine table and stay there
 * until someone retries or deletes them.
 *
 * Every record gets a `seq` from an AUTOINCREMENT key, so seqs only grow (never reused after a delete) and
 * per-provider order is seq order. [sourceId] is a random id made once per buffer file; a provider stores
 * (`sourceId`, `seq`) with a unique key, so a batch sent twice (the commit reached the database but the
 * acknowledgement was lost) is stored once.
 *
 * The buffer has no record cap: the reference platform 8.3's Primary Store Maintenance Value defaults to zero, so its local
 * cache is unlimited too (-).
 */
class ForwardBuffer(val path: Path) : AutoCloseable {
    private val connection: Connection
    private val lock = Any()
    val sourceId: String
    /**
     * Pending rows per provider, kept in memory so a capped enqueue does not count the queue on every flush.
     * Loaded on first use, adjusted after each commit, and dropped (reloaded later) after a bulk change. Guarded by [lock].
     */
    private var pendingCache: HashMap<String, Long>? = null

    init {
        path.parent?.createDirectories()
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=FULL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("CREATE TABLE IF NOT EXISTS forward_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS forward_pending (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  provider TEXT NOT NULL,
                  source_id TEXT,
                  $SAMPLE_COLUMNS
                )
                """.trimIndent()
            )
            st.execute("CREATE INDEX IF NOT EXISTS forward_pending_provider ON forward_pending(provider, seq)")
            val pendingColumns = st.executeQuery("PRAGMA table_info(forward_pending)").use { rs ->
                val names = HashSet<String>()
                while (rs.next()) names += rs.getString("name")
                names
            }
            if ("source_id" !in pendingColumns) st.execute("ALTER TABLE forward_pending ADD COLUMN source_id TEXT")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS forward_quarantine (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  provider TEXT NOT NULL,
                  seq INTEGER NOT NULL,
                  $SAMPLE_COLUMNS,
                  error TEXT NOT NULL,
                  quarantined_millis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // Last sample routed to a provider per tag: seeds the deadband after a restart without asking the
            // (possibly unreachable) remote provider.
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS forward_latest (
                  tag_path TEXT PRIMARY KEY COLLATE NOCASE,
                  ts_millis INTEGER NOT NULL,
                  value_kind TEXT NOT NULL,
                  value_text TEXT,
                  quality TEXT NOT NULL,
                  quality_diagnostic TEXT
                )
                """.trimIndent()
            )
            st.execute("INSERT OR IGNORE INTO forward_meta(key, value) VALUES ('source_id', '${UUID.randomUUID()}')")
            sourceId = st.executeQuery("SELECT value FROM forward_meta WHERE key = 'source_id'").use { rs -> rs.next(); rs.getString(1) }
        }
    }

    /** Append [samples] for [provider], in order, in one transaction. */
    fun enqueue(provider: String, samples: List<HistorySample>) {
        if (samples.isEmpty()) return
        enqueueAll(listOf(provider to samples))
    }

    /**
     * Append every batch, in order, in one transaction. A failure stores none of them.
     * Later batches win the latest-sample row when they share a tag, the same as calling [enqueue] in order.
     */
    fun enqueueAll(batches: List<Pair<String, List<HistorySample>>>) {
        val present = batches.filter { it.second.isNotEmpty() }
        if (present.isEmpty()) return
        synchronized(lock) {
            insertPending(present)
            for ((provider, samples) in present) adjustPending(provider, samples.size.toLong())
        }
    }

    /**
     * Append [samples] for [provider] while keeping its pending rows at or below [maxPending] (0 = no cap), in one
     * transaction. [evictOldest] false keeps the queue and refuses the newest samples that do not fit (the reference
     * product's PREVENT_NEW_DATA); true stores every sample and deletes the provider's oldest pending rows to make
     * room (EVICT_OLDEST_DATA). (doc source: FEATURES HIS-024)
     */
    fun enqueueCapped(provider: String, samples: List<HistorySample>, maxPending: Long, evictOldest: Boolean): CappedEnqueue {
        if (samples.isEmpty()) return CappedEnqueue(0, 0, 0)
        if (maxPending <= 0) {
            enqueue(provider, samples)
            return CappedEnqueue(samples.size, 0, 0)
        }
        synchronized(lock) {
            val have = pendingFor(provider)
            if (!evictOldest) {
                val room = (maxPending - have).coerceIn(0, samples.size.toLong()).toInt()
                if (room > 0) {
                    insertPending(listOf(provider to samples.subList(0, room)))
                    adjustPending(provider, room.toLong())
                }
                return CappedEnqueue(room, samples.size - room, 0)
            }
            val over = have + samples.size - maxPending
            var evicted = 0
            tx {
                insertPendingRows(listOf(provider to samples))
                if (over > 0) {
                    evicted = connection.prepareStatement(
                        "DELETE FROM forward_pending WHERE seq IN (SELECT seq FROM forward_pending WHERE provider = ? ORDER BY seq ASC LIMIT ?)"
                    ).use { ps ->
                        ps.setString(1, provider)
                        ps.setLong(2, over)
                        ps.executeUpdate()
                    }
                }
            }
            adjustPending(provider, samples.size.toLong() - evicted)
            return CappedEnqueue(samples.size, 0, evicted)
        }
    }

    /**
     * Move [records] (a prefix of [head] for [provider]) onto the queue of every provider in [targets], in one
     * transaction: each target gets its own copy with a new seq, and the originals leave [provider]'s queue. A crash
     * leaves either the originals or all the copies, never both. Returns how many copies were made.
     * (doc source: FEATURES HIS-006)
     */
    fun fanOut(provider: String, records: List<ForwardRecord>, targets: List<String>): Int {
        if (records.isEmpty()) return 0
        synchronized(lock) {
            val removed = tx {
                connection.prepareStatement(
                    "INSERT INTO forward_pending(provider, source_id, tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    for (target in targets) for (r in records) {
                        ps.setString(1, target)
                        ps.setString(2, sourceId)
                        bindSample(ps, 3, r.sample)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                connection.prepareStatement("DELETE FROM forward_pending WHERE provider = ? AND seq BETWEEN ? AND ?").use { ps ->
                    ps.setString(1, provider)
                    ps.setLong(2, records.first().seq)
                    ps.setLong(3, records.last().seq)
                    ps.executeUpdate()
                }
            }
            adjustPending(provider, -removed.toLong())
            for (target in targets) adjustPending(target, records.size.toLong())
            return records.size * targets.size
        }
    }

    /** Pending rows for [provider], from the in-memory count. */
    fun pendingFor(provider: String): Long = synchronized(lock) { pendingMap()[provider] ?: 0L }

    private fun pendingMap(): HashMap<String, Long> = pendingCache ?: HashMap(counts("forward_pending")).also { pendingCache = it }

    private fun adjustPending(provider: String, delta: Long) {
        val map = pendingCache ?: return
        val next = (map[provider] ?: 0L) + delta
        if (next <= 0) map.remove(provider) else map[provider] = next
    }

    private fun invalidatePending() {
        pendingCache = null
    }

    private fun insertPending(present: List<Pair<String, List<HistorySample>>>) = tx { insertPendingRows(present) }

    private fun insertPendingRows(present: List<Pair<String, List<HistorySample>>>) {
        run {
            connection.prepareStatement(
                "INSERT INTO forward_pending(provider, source_id, tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                for ((provider, samples) in present) {
                    for (s in samples) {
                        ps.setString(1, provider)
                        ps.setString(2, sourceId)
                        bindSample(ps, 3, s)
                        ps.addBatch()
                    }
                }
                ps.executeBatch()
            }
            val last = LinkedHashMap<String, HistorySample>()
            for ((_, samples) in present) for (s in samples) last[s.tagPath.lowercase()] = s
            connection.prepareStatement(
                """
                INSERT INTO forward_latest(tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(tag_path) DO UPDATE SET tag_path = excluded.tag_path, ts_millis = excluded.ts_millis,
                  value_kind = excluded.value_kind, value_text = excluded.value_text,
                  quality = excluded.quality, quality_diagnostic = excluded.quality_diagnostic
                """.trimIndent()
            ).use { ps ->
                for (s in last.values) {
                    bindSample(ps, 1, s)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /**
     * Hold the buffer lock across an archive's file write and the delete that follows it.
     * The same thread may call the other locked methods from inside [body].
     */
    fun <T> withLock(body: () -> T): T = synchronized(lock) { body() }

    /** Pending rows for [provider] with seq greater than [afterSeq], oldest first. Caller may hold [withLock]. */
    fun pendingPage(provider: String, afterSeq: Long, limit: Int): List<DiskCacheRecord> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT seq, source_id, provider, $SELECT_SAMPLE FROM forward_pending WHERE provider = ? AND seq > ? ORDER BY seq ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, provider)
            ps.setLong(2, afterSeq)
            ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<DiskCacheRecord>()
                while (rs.next()) out += readCached(rs)
                out
            }
        }
    }

    fun pendingCount(provider: String): Int = synchronized(lock) {
        connection.prepareStatement("SELECT COUNT(*) FROM forward_pending WHERE provider = ?").use { ps ->
            ps.setString(1, provider)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** Drop [provider]'s pending rows with seq up to [lastSeq] (the rows an archive holds). Later rows and quarantine stay. */
    fun deletePendingThrough(provider: String, lastSeq: Long) = synchronized(lock) {
        val deleted = connection.prepareStatement("DELETE FROM forward_pending WHERE provider = ? AND seq <= ?").use { ps ->
            ps.setString(1, provider)
            ps.setLong(2, lastSeq)
            ps.executeUpdate()
        }
        adjustPending(provider, -deleted.toLong())
    }

    /**
     * Put [records] back with their original (sourceId, seq). A row whose seq is already pending is skipped,
     * so a load that overlaps a crash between the archive rename and this delete does not double the cache.
     * Returns how many rows were inserted. One transaction: a failure inserts none of this call.
     */
    fun enqueueIdentified(records: List<DiskCacheRecord>): Int {
        if (records.isEmpty()) return 0
        return synchronized(lock) { invalidatePending(); tx {
            var inserted = 0
            connection.prepareStatement(
                "INSERT OR IGNORE INTO forward_pending(seq, provider, source_id, tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                for (record in records) {
                    ps.setLong(1, record.seq)
                    ps.setString(2, record.provider)
                    ps.setString(3, record.sourceId)
                    bindSample(ps, 4, record.sample)
                    inserted += ps.executeUpdate()
                }
            }
            val last = LinkedHashMap<String, HistorySample>()
            for (record in records) last[record.sample.tagPath.lowercase()] = record.sample
            connection.prepareStatement(
                """
                INSERT INTO forward_latest(tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(tag_path) DO UPDATE SET tag_path = excluded.tag_path, ts_millis = excluded.ts_millis,
                  value_kind = excluded.value_kind, value_text = excluded.value_text,
                  quality = excluded.quality, quality_diagnostic = excluded.quality_diagnostic
                """.trimIndent()
            ).use { ps ->
                for (s in last.values) {
                    bindSample(ps, 1, s)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            inserted
        }.also { invalidatePending() } }
    }

    /** The oldest [limit] pending records for [provider], in seq order. */
    fun head(provider: String, limit: Int): List<ForwardRecord> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT seq, source_id, $SELECT_SAMPLE FROM forward_pending WHERE provider = ? ORDER BY seq ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, provider)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<ForwardRecord>()
                while (rs.next()) {
                    val id = rs.getString("source_id") ?: sourceId
                    out += ForwardRecord(id, rs.getLong("seq"), readSample(rs))
                }
                out
            }
        }
    }

    /**
     * Remove [records] (a prefix of [head] for [provider]) after the provider stored them. They are the
     * provider's lowest seqs, so the seq range covers exactly them.
     */
    fun remove(provider: String, records: List<ForwardRecord>) {
        if (records.isEmpty()) return
        synchronized(lock) {
            val deleted = connection.prepareStatement("DELETE FROM forward_pending WHERE provider = ? AND seq BETWEEN ? AND ?").use { ps ->
                ps.setString(1, provider)
                ps.setLong(2, records.first().seq)
                ps.setLong(3, records.last().seq)
                ps.executeUpdate()
            }
            adjustPending(provider, -deleted.toLong())
        }
    }

    /** Move one pending record to quarantine (one transaction), so it no longer blocks the queue. */
    fun quarantine(provider: String, record: ForwardRecord, error: String, nowMillis: Long) = synchronized(lock) {
        val deleted = quarantineTx(provider, record, error, nowMillis)
        adjustPending(provider, -deleted.toLong())
    }

    private fun quarantineTx(provider: String, record: ForwardRecord, error: String, nowMillis: Long): Int = tx {
        connection.prepareStatement(
            "INSERT INTO forward_quarantine(provider, seq, tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic, error, quarantined_millis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, provider)
            ps.setLong(2, record.seq)
            bindSample(ps, 3, record.sample)
            ps.setString(9, error.take(MAX_ERROR))
            ps.setLong(10, nowMillis)
            ps.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM forward_pending WHERE provider = ? AND seq = ?").use { ps ->
            ps.setString(1, provider)
            ps.setLong(2, record.seq)
            ps.executeUpdate()
        }
    }

    /** Quarantine [samples] for [provider] directly (they never reached the pending queue). One transaction. */
    fun quarantineSamples(provider: String, samples: List<HistorySample>, error: String, nowMillis: Long) {
        if (samples.isEmpty()) return
        tx {
            connection.prepareStatement(
                "INSERT INTO forward_quarantine(provider, seq, tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic, error, quarantined_millis) VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                for (s in samples) {
                    ps.setString(1, provider)
                    bindSample(ps, 2, s)
                    ps.setString(8, error.take(MAX_ERROR))
                    ps.setLong(9, nowMillis)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** Pending record count per provider. */
    fun pendingCounts(): Map<String, Long> = counts("forward_pending")

    /** Quarantined record count per provider. */
    fun quarantineCounts(): Map<String, Long> = counts("forward_quarantine")

    /** Quarantined records, oldest first, optionally for one [provider]. */
    fun quarantined(provider: String? = null, limit: Int = 1000): List<QuarantinedRecord> = synchronized(lock) {
        val where = if (provider == null) "" else "WHERE provider = ?"
        connection.prepareStatement(
            "SELECT id, provider, seq, $SELECT_SAMPLE, error, quarantined_millis FROM forward_quarantine $where ORDER BY id ASC LIMIT ?"
        ).use { ps ->
            var i = 1
            if (provider != null) ps.setString(i++, provider)
            ps.setInt(i, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<QuarantinedRecord>()
                while (rs.next()) out += QuarantinedRecord(
                    rs.getLong("id"), rs.getString("provider"), rs.getLong("seq"), readSample(rs),
                    rs.getString("error"), rs.getLong("quarantined_millis"),
                )
                out
            }
        }
    }

    /**
     * Put quarantined records back on their provider's queue (the retry action on the quarantine page), at the tail with
     * new seqs, in quarantine order. [ids] null means every record (of [provider] when given). Returns the count.
     */
    fun retryQuarantined(ids: List<Long>?, provider: String? = null): Int = synchronized(lock) { invalidatePending(); retryTx(ids, provider).also { invalidatePending() } }

    private fun retryTx(ids: List<Long>?, provider: String?): Int = tx {
        val filter = filter(ids, provider)
        val moved = connection.prepareStatement(
            "INSERT INTO forward_pending(provider, tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic) " +
                "SELECT provider, tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic FROM forward_quarantine ${filter.first} ORDER BY id ASC"
        ).use { ps -> filter.second(ps); ps.executeUpdate() }
        connection.prepareStatement("DELETE FROM forward_quarantine ${filter.first}").use { ps -> filter.second(ps); ps.executeUpdate() }
        moved
    }

    /** Delete quarantined records. [ids] null means every record (of [provider] when given). Returns the count. */
    fun deleteQuarantined(ids: List<Long>?, provider: String? = null): Int = tx {
        val filter = filter(ids, provider)
        connection.prepareStatement("DELETE FROM forward_quarantine ${filter.first}").use { ps -> filter.second(ps); ps.executeUpdate() }
    }

    /** Last sample enqueued per tag (any provider). */
    fun latestAll(): Map<String, HistorySample> = synchronized(lock) {
        connection.prepareStatement("SELECT $SELECT_SAMPLE FROM forward_latest").use { ps ->
            ps.executeQuery().use { rs ->
                val out = HashMap<String, HistorySample>()
                while (rs.next()) readSample(rs).let { out[it.tagPath] = it }
                out
            }
        }
    }

    override fun close() = synchronized(lock) { connection.close() }

    private fun readCached(rs: ResultSet) = DiskCacheRecord(
        provider = rs.getString("provider"),
        sample = readSample(rs),
        sourceId = rs.getString("source_id") ?: sourceId,
        seq = rs.getLong("seq"),
    )

    private fun filter(ids: List<Long>?, provider: String?): Pair<String, (PreparedStatement) -> Unit> {
        val clauses = ArrayList<String>()
        if (ids != null) clauses += if (ids.isEmpty()) "1 = 0" else "id IN (${ids.joinToString(",") { "?" }})"
        if (provider != null) clauses += "provider = ?"
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        return where to { ps ->
            var i = 1
            ids?.forEach { ps.setLong(i++, it) }
            if (provider != null) ps.setString(i, provider)
        }
    }

    private fun counts(table: String): Map<String, Long> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT provider, COUNT(*) FROM $table GROUP BY provider").use { rs ->
                val out = LinkedHashMap<String, Long>()
                while (rs.next()) out[rs.getString(1)] = rs.getLong(2)
                out
            }
        }
    }

    private fun <T> tx(body: () -> T): T = synchronized(lock) {
        connection.autoCommit = false
        try {
            val result = body()
            connection.commit()
            result
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private companion object {
        const val MAX_ERROR = 2000
        const val SAMPLE_COLUMNS = """tag_path TEXT NOT NULL COLLATE NOCASE,
                  ts_millis INTEGER NOT NULL,
                  value_kind TEXT NOT NULL,
                  value_text TEXT,
                  quality TEXT NOT NULL,
                  quality_diagnostic TEXT"""
        const val SELECT_SAMPLE = "tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic"

        fun bindSample(ps: PreparedStatement, first: Int, s: HistorySample) {
            ps.setString(first, s.tagPath)
            ps.setLong(first + 1, s.timestampMillis)
            val (kind, text) = ValueCodec.encode(s.value)
            ps.setString(first + 2, kind)
            if (text == null) ps.setNull(first + 3, Types.VARCHAR) else ps.setString(first + 3, text)
            ps.setString(first + 4, s.quality.code.label)
            val diag = s.quality.diagnostic
            if (diag == null) ps.setNull(first + 5, Types.VARCHAR) else ps.setString(first + 5, diag)
        }

        fun readSample(rs: ResultSet) = HistorySample(
            tagPath = rs.getString("tag_path"),
            timestampMillis = rs.getLong("ts_millis"),
            value = ValueCodec.decode(rs.getString("value_kind"), rs.getString("value_text")),
            quality = ValueCodec.quality(rs.getString("quality"), rs.getString("quality_diagnostic")),
        )
    }
}
