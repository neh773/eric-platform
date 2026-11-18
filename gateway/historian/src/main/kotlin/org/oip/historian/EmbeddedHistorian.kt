package org.oip.historian

import org.oip.tags.Level
import org.oip.tags.Quality
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

/** How long one partition of the [EmbeddedHistorian] spans, in UTC calendar units. */
enum class PartitionInterval(val wire: String) {
    DAY("day"), WEEK("week"), MONTH("month"), YEAR("year");

    /** `[start, end)` of the partition holding [millis]. Weeks start on Monday (ISO 8601). */
    fun bounds(millis: Long): LongArray {
        val day = Math.floorDiv(millis, DAY_MILLIS)
        return try {
            val (startDay, endDay) = when (this) {
                DAY -> day to day + 1
                WEEK -> (day - Math.floorMod(day + 3, 7L)).let { it to it + 7 }
                MONTH -> LocalDate.ofEpochDay(day).withDayOfMonth(1).let { it.toEpochDay() to it.plusMonths(1).toEpochDay() }
                YEAR -> LocalDate.ofEpochDay(day).withDayOfYear(1).let { it.toEpochDay() to it.plusYears(1).toEpochDay() }
            }
            longArrayOf(dayStart(startDay), dayStart(endDay))
        } catch (e: DateTimeException) {
            longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE)
        }
    }

    /** [millis] moved back by [count] of this unit (UTC calendar arithmetic). */
    fun minus(millis: Long, count: Int): Long {
        val t = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC)
        val back = when (this) {
            DAY -> t.minusDays(count.toLong())
            WEEK -> t.minusWeeks(count.toLong())
            MONTH -> t.minusMonths(count.toLong())
            YEAR -> t.minusYears(count.toLong())
        }
        return back.toInstant().toEpochMilli()
    }

    companion object {
        const val DAY_MILLIS = 86_400_000L

        fun fromWire(text: String): PartitionInterval = entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("partition interval must be one of ${entries.joinToString { it.wire }}")

        private fun dayStart(day: Long): Long = try {
            Math.multiplyExact(day, DAY_MILLIS)
        } catch (e: ArithmeticException) {
            if (day < 0) Long.MIN_VALUE else Long.MAX_VALUE
        }
    }
}

/** What [EmbeddedHistorian.maintain] does with partitions older than the maintenance age. */
enum class MaintenanceMode(val wire: String) {
    /** Keep every partition. */
    NONE("none"),
    /** Delete old partitions. */
    PRUNE("prune"),
    /** Copy old partitions to their own SQLite files in the archive folder, then delete them here. */
    ARCHIVE("archive");

    companion object {
        fun fromWire(text: String): MaintenanceMode = entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("maintenance mode must be one of ${entries.joinToString { it.wire }}")
    }
}

/**
 * Settings of the built-in `local` provider (the `historian/HistoryProvider/local` resource with `type: embedded`).
 * The age is a whole number of [maintenanceAgeUnits], which must be a whole multiple of [partitionInterval]
 * (a week of day partitions, a year of month partitions), so maintenance only ever removes whole partitions.
 * (doc source: FEATURES HIS-007)
 */
data class EmbeddedHistorianSettings(
    val partitionInterval: PartitionInterval = PartitionInterval.WEEK,
    val deduplication: Boolean = true,
    val maintenanceMode: MaintenanceMode = MaintenanceMode.NONE,
    val maintenanceAge: Int = 0,
    val maintenanceAgeUnits: PartitionInterval = partitionInterval,
    /** Folder for [MaintenanceMode.ARCHIVE] files; required in that mode. */
    val archiveFolder: Path? = null,
) {
    init {
        if (maintenanceMode != MaintenanceMode.NONE) {
            require(maintenanceAge > 0) { "maintenance age must be > 0 when maintenance mode is ${maintenanceMode.wire}" }
            require(multipleOf(maintenanceAgeUnits, partitionInterval)) {
                "maintenance age units (${maintenanceAgeUnits.wire}) must be a whole multiple of the partition interval (${partitionInterval.wire})"
            }
        }
        if (maintenanceMode == MaintenanceMode.ARCHIVE) require(archiveFolder != null) { "archive mode needs an archive folder" }
    }

    private companion object {
        fun multipleOf(unit: PartitionInterval, partition: PartitionInterval) = unit == partition ||
            partition == PartitionInterval.DAY ||
            (partition == PartitionInterval.MONTH && unit == PartitionInterval.YEAR)
    }
}

/**
 * A reader that can compute some aggregates inside the store instead of streaming rows through
 * [HistoryAggregator]. Returns null for a request it does not compute natively; the caller then scans.
 * A native answer must equal [HistoryAggregator]'s scan for the same rows (floating-point sums may differ
 * in the last bits, because the store sums with compensation).
 */
interface NativeAggregation {
    fun aggregateNative(
        tagPath: String,
        fromMillis: Long,
        toMillis: Long,
        intervalMillis: Long,
        mode: HistoryAggregate,
        interpolation: HistoryInterpolation,
    ): List<AggregateWindow>?
}

/**
 * The default embedded time-series historian: the built-in `local` provider at `<dataDir>/db/history.sqlite`.
 * (doc source: FEATURES HIS-007)
 *
 * Layout (one SQLite file, WAL, `synchronous=FULL`):
 *  - `eh_tag`: one id per tag path (paths compare case-insensitively);
 *  - `eh_partition`: one row per time partition, `[start_ms, end_ms)`, with the name of its table;
 *  - one `eh_p_<start>` table per partition, a `WITHOUT ROWID` table keyed by (tag id, timestamp, seq), so
 *    one tag's rows in a time range are contiguous on disk and a range read touches no other tag's pages;
 *  - `eh_hot`: rows written but not yet merged into their partition, keyed by seq (see Write path below);
 *  - `eh_latest`: the newest row per tag (newest timestamp; a tie goes to the later write), brought up to date by
 *    each merge and read once at startup;
 *  - `eh_meta`: the next seq.
 *
 * Write path (PRF-006). Each [appendAll] batch is still one transaction, committed with `synchronous=FULL` before it
 * returns, so a row the collector counts as written is on disk. The rows of that transaction go to `eh_hot`, whose key
 * is the seq, so a batch is appended at the end of one table instead of being inserted into the middle of every tag's
 * run in the partition (one random leaf page per tag per batch, which capped the old path at about 75,000 rows/s
 * at 88,500 tags). A merge later moves the waiting rows into their partitions, sorted by (tag, timestamp, seq), so
 * each tag's leaf is touched once per merge instead of once per row. A merge runs as steps, one per append, each
 * one transaction over a range of tags of about [MERGE_CHUNK_ROWS] rows, so appends are never held for a whole
 * merge; the last step also deletes the merged rows from `eh_hot`. It starts after an
 * append once [mergeRows] rows, or [mergeRowsPerTag] per tag that has waiting rows, wait (whichever is more), or
 * the oldest has waited [mergeAgeMillis]; also before maintenance and when the
 * file is opened. Until then readers see the waiting rows from memory ([HotTag]): each row's tag, timestamp, seq,
 * value and quality is kept in memory next to `eh_hot`, so a read never scans `eh_hot`.
 *
 * Values keep their type: `k` holds the kind (null, bool, int, long, double, string) and `v` holds the value in
 * SQLite's own integer, real or text storage, so aggregates run on numbers in SQL. `g` is 1 for Good quality.
 *
 * Partitioning: a row goes to the partition that holds its timestamp. A missing partition is created inside
 * the same transaction as the rows that need it, so a rollover is atomic with the data and a crash leaves either
 * both or neither. A new partition spans [EmbeddedHistorianSettings.partitionInterval] around its first row,
 * trimmed so it never overlaps a partition made under an earlier interval setting.
 *
 * Every row gets the next seq, so rows of one tag in the same millisecond keep their write order.
 *
 * Deduplication (on by default): a row identical to a stored row of the same tag and timestamp (same value, kind
 * and quality) is not stored again, so a batch written twice, for example after a store-and-forward retry or a
 * quarantine retry of a batch that had in fact committed, is stored once. A different value in the same
 * millisecond is a real change and is kept as its own row after the earlier one; it never overwrites it (see
 * DIFFERENCES 702). With deduplication off, every row is stored.
 *
 * Native aggregation ([NativeAggregation]): SimpleAverage, Sum, Minimum, Maximum, Range, Count and LastValue
 * without interpolation are computed with `GROUP BY` window inside SQLite, one statement per partition the
 * range crosses. Time-weighted Average and interpolated queries fall back to [HistoryAggregator]'s scan.
 *
 * Maintenance ([maintain]): partitions whose end is at or before now minus the maintenance age are pruned or
 * archived. An archive is written to a temporary file, moved into place, and only then is the partition dropped;
 * a crash between the move and the drop archives the partition again on the next pass (the file is replaced).
 *
 * Threading: one connection writes (appends, merges, partition creation, maintenance) under [writeLock]; a second
 * connection reads, so a long query never delays a write (WAL readers do not block the writer). Partition and
 * tag state in memory is published only after the transaction that created it committed. Readers hold a shared
 * lock while a statement runs; dropping a partition takes it exclusively, so no read runs against a table that
 * is being dropped. [HistoryReader.scan] releases the locks between pages. Reads that combine waiting rows with
 * partition rows hold [hotLock] shared; a merge commits under it exclusively, so such a read sees a row either in
 * memory or in its partition, never both or neither. A scan, which releases it between pages, drops a partition row
 * whose (timestamp, seq) it already has from memory.
 *
 * A file written by the earlier flat store (`history_sample`) is moved into partitions in one transaction the
 * first time it is opened.
 */
class EmbeddedHistorian(
    val path: Path,
    settings: EmbeddedHistorianSettings = EmbeddedHistorianSettings(),
    private val clock: Clock = Clock.systemUTC(),
    /** Waiting rows that start a merge after an append; see the write path above. */
    private val mergeRows: Int = MERGE_ROWS,
    /** Waiting rows per tag that start a merge when that is more than [mergeRows], so a merge stays efficient at more tags. */
    private val mergeRowsPerTag: Int = MERGE_ROWS_PER_TAG,
    /** Longest a row waits in `eh_hot` before an append merges it. */
    private val mergeAgeMillis: Long = MERGE_AGE_MILLIS,
    /** Rows, about, that one merge step moves; a merge of more runs as several steps between appends. */
    private val mergeChunkRows: Int = MERGE_CHUNK_ROWS,
) : HistorySink, NativeAggregation {
    private class Partition(val start: Long, val end: Long, val table: String)
    private class TagEntry(val id: Long, val path: String) {
        /** This tag's rows in `eh_hot`. */
        val hot = HotTag()
    }

    /**
     * One tag's rows in `eh_hot`, in seq order, as a reader would decode them (values through [normalize]; the
     * quality as written). Mutated only by the writer under [hotLock] exclusively; read under [hotLock] shared.
     */
    private class HotTag {
        var n = 0
        var ts: LongArray = EMPTY_LONGS
        var seq: LongArray = EMPTY_LONGS
        var values: Array<Any?> = EMPTY_VALUES
        var quals: Array<Quality?> = EMPTY_QUALITIES
        /** Newest timestamp among the rows above; [Long.MIN_VALUE] when there are none. */
        var maxTs = Long.MIN_VALUE
        /** At least the newest timestamp this tag has in partition tables; [Long.MIN_VALUE] when it has none. */
        var mergedMaxTs = Long.MIN_VALUE
        /** Writer only: the transaction [txLast] and [txMaxTs] belong to. */
        var txId = 0L
        /** Writer only: this tag's last row pending in transaction [txId], or -1. */
        var txLast = -1
        /** Writer only: the newest timestamp pending in transaction [txId]. */
        var txMaxTs = Long.MIN_VALUE

        fun add(t: Long, s: Long, v: Any?, q: Quality) {
            if (n == ts.size) {
                val c = maxOf(4, n * 2)
                ts = ts.copyOf(c); seq = seq.copyOf(c); values = values.copyOf(c); quals = quals.copyOf(c)
            }
            ts[n] = t; seq[n] = s; values[n] = v; quals[n] = q
            n++
            if (t > maxTs) maxTs = t
        }

        /** After a merge step committed: rows with seq up to [upTo] (a prefix) are in partitions now. Returns how many. */
        fun dropThrough(upTo: Long): Int {
            var k = 0
            while (k < n && seq[k] <= upTo) {
                if (ts[k] > mergedMaxTs) mergedMaxTs = ts[k]
                k++
            }
            if (k == 0) return 0
            val left = n - k
            if (left == 0 && ts.size > KEEP_HOT_CAPACITY) {
                ts = EMPTY_LONGS; seq = EMPTY_LONGS; values = EMPTY_VALUES; quals = EMPTY_QUALITIES
            } else {
                System.arraycopy(ts, k, ts, 0, left)
                System.arraycopy(seq, k, seq, 0, left)
                System.arraycopy(values, k, values, 0, left)
                System.arraycopy(quals, k, quals, 0, left)
                java.util.Arrays.fill(values, left, n, null)
                java.util.Arrays.fill(quals, left, n, null)
            }
            n = left
            maxTs = Long.MIN_VALUE
            for (i in 0 until n) if (ts[i] > maxTs) maxTs = ts[i]
            return k
        }

        /** Rows with a timestamp in `[from, to]`, sorted by (timestamp, seq). */
        fun slice(from: Long, to: Long): HotSlice? {
            var count = 0
            for (i in 0 until n) if (ts[i] in from..to) count++
            if (count == 0) return null
            val idx = IntArray(count)
            var j = 0
            var sorted = true
            for (i in 0 until n) if (ts[i] in from..to) {
                if (j > 0 && ts[i] < ts[idx[j - 1]]) sorted = false
                idx[j++] = i
            }
            val order = if (sorted) idx.toList() else idx.sortedWith(compareBy<Int>({ ts[it] }, { seq[it] }))
            return HotSlice(
                LongArray(count) { ts[order[it]] }, LongArray(count) { seq[order[it]] },
                Array(count) { values[order[it]] }, Array(count) { quals[order[it]]!! },
            )
        }
    }

    /** A copy of some of one tag's waiting rows, sorted by (timestamp, seq). */
    private class HotSlice(val ts: LongArray, val seq: LongArray, val values: Array<Any?>, val quals: Array<Quality>) {
        val size: Int get() = ts.size
        fun sample(path: String, i: Int) = HistorySample(path, ts[i], values[i], ValueCodec.quality(quals[i].code.label, quals[i].diagnostic))
        /** Negative when row [i] sorts before (t, s). */
        fun compare(i: Int, t: Long, s: Long): Int = if (ts[i] != t) ts[i].compareTo(t) else seq[i].compareTo(s)
    }

    /** Rows one transaction put in `eh_hot`, published to [hot] after it commits. */
    private class PendingRows {
        var n = 0
        val tag = ArrayList<TagEntry>()
        var ts = LongArray(1024)
        var seq = LongArray(1024)
        /** Index of the previous pending row of the same tag, or -1. */
        var prev = IntArray(1024)
        val values = ArrayList<Any?>()
        val quals = ArrayList<Quality>()

        fun add(entry: TagEntry, t: Long, s: Long, v: Any?, q: Quality, previous: Int): Int {
            if (n == ts.size) {
                val c = n * 2
                ts = ts.copyOf(c); seq = seq.copyOf(c); prev = prev.copyOf(c)
            }
            ts[n] = t; seq[n] = s; prev[n] = previous
            tag += entry
            values += v
            quals += q
            return n++
        }
    }


    /** State made by one write transaction, published only after it commits. */
    private inner class TxState {
        val newTags = LinkedHashMap<String, TagEntry>()
        val newPartitions = ArrayList<Partition>()
        /** Deduplication for this whole transaction, read once so a concurrent [configure] cannot split a batch. */
        val dedup = currentSettings.deduplication
        var seq = nextSeq
        var seqUsed = false
        val pending = PendingRows()
        val id = ++txCount

        fun tag(path: String): TagEntry = tagsExact[path] ?: tags[path.lowercase()]?.also { tagsExact[path] = it }
            ?: newTags.getOrPut(path.lowercase()) {
                insertTag.setString(1, path)
                insertTag.executeUpdate()
                val id = lastRowId.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                TagEntry(id, path)
            }

        fun partition(ts: Long, map: TreeMap<Long, Partition>): Partition {
            map.floorEntry(ts)?.value?.let { if (ts < it.end) return it }
            newPartitions.firstOrNull { ts >= it.start && ts < it.end }?.let { return it }
            val b = currentSettings.partitionInterval.bounds(ts)
            var start = b[0]
            var end = b[1]
            val all = TreeMap(map).also { m -> newPartitions.forEach { m[it.start] = it } }
            all.floorEntry(ts)?.value?.let { if (it.end > start) start = it.end }
            all.higherEntry(ts)?.value?.let { if (it.start < end) end = it.start }
            val table = "eh_p_" + start.toString().replace('-', 'n')
            writer.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE "$table" (
                      tag_id INTEGER NOT NULL, ts INTEGER NOT NULL, seq INTEGER NOT NULL,
                      k INTEGER NOT NULL, v, g INTEGER NOT NULL, q TEXT NOT NULL, qd TEXT,
                      PRIMARY KEY (tag_id, ts, seq)
                    ) WITHOUT ROWID
                    """.trimIndent()
                )
            }
            writer.prepareStatement("INSERT INTO eh_partition(start_ms, end_ms, table_name) VALUES (?, ?, ?)").use { ps ->
                ps.setLong(1, start)
                ps.setLong(2, end)
                ps.setString(3, table)
                ps.executeUpdate()
            }
            return Partition(start, end, table).also { newPartitions += it }
        }
    }

    private val writer: Connection
    private val reader: Connection
    /** Fair, so a steady stream of appends can't starve [maintain] of the lock and let old partitions pile up. */
    private val writeLock = ReentrantLock(true)
    private val readLock = ReentrantLock()
    /** Shared by statements reading partition tables; exclusive while a partition table is dropped. */
    private val schemaLock = ReentrantReadWriteLock()
    @Volatile private var partitions: TreeMap<Long, Partition> = TreeMap()
    private val tags = ConcurrentHashMap<String, TagEntry>()
    /** [tags] by the path exactly as a caller spelled it, so the per-row lookup does not lower-case the path. */
    private val tagsExact = ConcurrentHashMap<String, TagEntry>()
    private var txCount = 0L
    /** Shared by reads that combine [hot] with partition rows; exclusive while [hot] changes. */
    private val hotLock = ReentrantReadWriteLock(true)
    @Volatile private var hotRows = 0
    /** Tags with at least one waiting row. Writer only. */
    private var hotTags = 0
    /** [clock] millis when the oldest row now in `eh_hot` was written; [Long.MIN_VALUE] when it is empty. Writer only. */
    private var hotSinceMillis = Long.MIN_VALUE
    @Volatile private var merges = 0L
    @Volatile private var mergedRows = 0L
    @Volatile private var lastMergeMillis: Long? = null
    @Volatile private var lastMergeError: String? = null
    @Volatile private var lastMergeStepMillis: Long? = null
    /** Dedup probes against partition rows, prepared once per table. Guarded by [writeLock]. */
    private val probeStatements = HashMap<String, PreparedStatement>()
    private val tagById = ConcurrentHashMap<Long, TagEntry>()
    private val insertTag: PreparedStatement
    private val lastRowId: PreparedStatement
    private lateinit var insertHot: PreparedStatement
    private lateinit var updateSeq: PreparedStatement
    private var nextSeq = 1L
    @Volatile private var currentSettings = settings
    @Volatile private var pruned = 0L
    @Volatile private var archived = 0L
    @Volatile private var lastMaintenanceMillis: Long? = null
    @Volatile private var lastMaintenanceError: String? = null

    init {
        path.parent?.createDirectories()
        writer = open()
        writer.createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS eh_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS eh_tag (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE COLLATE NOCASE)")
            st.execute("CREATE TABLE IF NOT EXISTS eh_partition (start_ms INTEGER PRIMARY KEY, end_ms INTEGER NOT NULL, table_name TEXT NOT NULL UNIQUE)")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS eh_hot (
                  seq INTEGER PRIMARY KEY, tag_id INTEGER NOT NULL, ts INTEGER NOT NULL,
                  k INTEGER NOT NULL, v, g INTEGER NOT NULL, q TEXT NOT NULL, qd TEXT
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS eh_latest (
                  tag_id INTEGER PRIMARY KEY, ts INTEGER NOT NULL, seq INTEGER NOT NULL,
                  k INTEGER NOT NULL, v, q TEXT NOT NULL, qd TEXT
                )
                """.trimIndent()
            )
            st.executeQuery("SELECT id, path FROM eh_tag").use { rs ->
                while (rs.next()) TagEntry(rs.getLong(1), rs.getString(2)).let { tags[it.path.lowercase()] = it; tagById[it.id] = it }
            }
            val map = TreeMap<Long, Partition>()
            st.executeQuery("SELECT start_ms, end_ms, table_name FROM eh_partition").use { rs ->
                while (rs.next()) map[rs.getLong(1)] = Partition(rs.getLong(1), rs.getLong(2), rs.getString(3))
            }
            partitions = map
            st.executeQuery("SELECT value FROM eh_meta WHERE key = 'seq'").use { rs -> if (rs.next()) nextSeq = rs.getString(1).toLong() }
        }
        insertTag = writer.prepareStatement("INSERT INTO eh_tag(path) VALUES (?)")
        lastRowId = writer.prepareStatement("SELECT last_insert_rowid()")
        insertHot = writer.prepareStatement("INSERT INTO eh_hot(tag_id, ts, seq, k, v, g, q, qd) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        updateSeq = writer.prepareStatement("INSERT INTO eh_meta(key, value) VALUES ('seq', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
        migrateFlatStore()
        // Rows left in eh_hot by the last run go to their partitions before anything reads or writes.
        writeLock.withLock { mergeHot() }
        // Which tags may already have partition rows, and up to which timestamp (the dedup fast path in [write]).
        writer.createStatement().use { st ->
            for (t in tags.values) t.hot.mergedMaxTs = Long.MAX_VALUE
            st.executeQuery("SELECT tag_id, ts FROM eh_latest").use { rs ->
                while (rs.next()) tagById[rs.getLong(1)]?.hot?.mergedMaxTs = rs.getLong(2)
            }
        }
        reader = open()
    }

    private fun open(): Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also { c ->
        c.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=FULL")
            st.execute("PRAGMA busy_timeout=5000")
            // A merge step's transaction can fill tens of MB of WAL; give the space back once it is checkpointed.
            st.execute("PRAGMA journal_size_limit=$WAL_LIMIT_BYTES")
            // A batch across many tags dirties one leaf page per tag; keep those pages in memory.
            st.execute("PRAGMA cache_size=-$CACHE_KIB")
        }
    }

    val settings: EmbeddedHistorianSettings get() = currentSettings

    /** Apply new settings. Existing partitions keep their bounds; the next new partition uses the new interval. */
    fun configure(settings: EmbeddedHistorianSettings) {
        currentSettings = settings
    }

    override fun appendAll(samples: List<HistorySample>) {
        if (samples.isEmpty()) return
        writeLock.withLock {
            val mergeAt = minOf(maxOf(mergeRows.toLong(), mergeRowsPerTag.toLong() * hotTags), MAX_MERGE_ROWS)
            if (hotRows >= maxOf(HOT_ROWS_LIMIT, 4L * mergeAt)) {
                // Merges keep failing: stop taking rows rather than holding ever more of them in memory.
                mergeHot()
            }
            tx { state -> write(state, samples) }
            if (cycle == null && (hotRows >= mergeAt || (hotRows > 0 && clock.millis() - hotSinceMillis >= mergeAgeMillis))) startCycle()
            if (cycle != null) {
                // One step per append, so appends keep flowing while a merge is under way. The batch is committed;
                // a failed step must not make the caller write it again: the rows stay waiting and the step is retried.
                try {
                    mergeStep()
                } catch (e: Exception) {
                    lastMergeError = "${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    /** Rows of one batch into `eh_hot`. Runs inside [tx]; `eh_latest` follows at the next merge. */
    private fun write(state: TxState, samples: List<HistorySample>) {
        val map = partitions
        var lastPartition: Partition? = null
        var batched = 0
        // A batch that failed half way left its rows bound on these statements.
        insertHot.clearBatch()
        for ((index, s) in samples.withIndex()) {
            val tag = state.tag(s.tagPath)
            val ts = s.timestampMillis
            // The partition is made with the rows that need it, as before; the rows wait in eh_hot until a merge.
            val partition = if (lastPartition != null && ts >= lastPartition.start && ts < lastPartition.end) lastPartition
            else state.partition(ts, map).also { lastPartition = it }
            val seq = state.seq++
            state.seqUsed = true
            val value = normalize(s.value)
            val h = tag.hot
            if (h.txId != state.id) {
                h.txId = state.id
                h.txLast = -1
                h.txMaxTs = Long.MIN_VALUE
            }
            if (!state.dedup || !duplicate(state, tag, ts, value, s.quality, partition)) {
                bindRow(insertHot, s, tag.id, seq)
                insertHot.addBatch()
                batched++
                h.txLast = state.pending.add(tag, ts, seq, value, s.quality, h.txLast)
                if (ts > h.txMaxTs) h.txMaxTs = ts
            }
        }
        if (batched > 0) insertHot.executeBatch()
        if (state.seqUsed) {
            updateSeq.setString(1, state.seq.toString())
            updateSeq.executeUpdate()
        }
    }

    /**
     * Deduplication: is (value, quality) already stored for this tag at [ts]? That is a waiting row (in memory), a
     * row earlier in this transaction, or a partition row. A tag whose rows so far are all older than [ts], the usual
     * case, needs none of those lookups; the partition is probed only when it may hold a row at [ts].
     */
    private fun duplicate(state: TxState, tag: TagEntry, ts: Long, value: Any?, quality: Quality, partition: Partition): Boolean {
        val h = tag.hot
        val mergedMax = h.mergedMaxTs
        if (ts > h.maxTs && ts > mergedMax && ts > h.txMaxTs) return false
        for (i in 0 until h.n) if (h.ts[i] == ts && sameRow(h.values[i], h.quals[i]!!, value, quality)) return true
        var i = h.txLast
        val pending = state.pending
        while (i >= 0) {
            if (pending.ts[i] == ts && sameRow(pending.values[i], pending.quals[i], value, quality)) return true
            i = pending.prev[i]
        }
        if (ts > mergedMax || partitions[partition.start]?.table != partition.table) return false
        val probe = probeStatements.getOrPut(partition.table) {
            writer.prepareStatement(
                "SELECT 1 FROM \"${partition.table}\" WHERE tag_id = ?1 AND ts = ?2 AND k = ?3 AND v IS ?4 AND q = ?5 AND qd IS ?6 LIMIT 1"
            )
        }
        probe.setLong(1, tag.id)
        probe.setLong(2, ts)
        bindValue(probe, 3, value)
        probe.setString(5, quality.code.label)
        setNullable(probe, 6, quality.diagnostic)
        return probe.executeQuery().use { it.next() }
    }

    /** A merge under way: rows with seq up to [upTo] move to their partitions, a range of tags at a time. */
    private class MergeCycle(val upTo: Long, val chunks: ArrayDeque<List<TagEntry>>, val startedMillis: Long)

    /** Writer only. */
    private var cycle: MergeCycle? = null

    /** Start a merge of every row waiting now, in tag-id ranges of about [MERGE_CHUNK_ROWS] rows. */
    private fun startCycle() {
        val chunks = ArrayDeque<List<TagEntry>>()
        var chunk = ArrayList<TagEntry>()
        var rows = 0
        for (t in tagById.values.filter { it.hot.n > 0 }.sortedBy { it.id }) {
            chunk += t
            rows += t.hot.n
            if (rows >= mergeChunkRows) {
                chunks += chunk
                chunk = ArrayList()
                rows = 0
            }
        }
        if (chunk.isNotEmpty()) chunks += chunk
        cycle = MergeCycle(nextSeq - 1, chunks, clock.millis())
    }

    /**
     * Move every `eh_hot` row into its partition and drop it from memory. Returns the rows moved. Runs under
     * [writeLock]; used when the file is opened, before maintenance, and when merges fell far behind.
     */
    private fun mergeHot(): Int {
        check(writeLock.isHeldByCurrentThread)
        val count = writer.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM eh_hot").use { rs -> rs.next(); rs.getInt(1) } }
        if (count == 0) {
            hotSinceMillis = Long.MIN_VALUE
            return 0
        }
        if (count != hotRows + (cycle?.let { mergedNotDeleted } ?: 0)) {
            // Rows left by an earlier run (opening the file): only eh_hot has them.
            mergeTable()
            return count
        }
        if (cycle == null) startCycle()
        while (cycle != null) mergeStep()
        return count
    }

    /** Rows in finished chunks of the current cycle, still in `eh_hot` until the cycle's last step deletes them. */
    private var mergedNotDeleted = 0

    /**
     * One step of the current cycle: a chunk of tags moves to the partitions in one transaction, sorted by
     * (tag, timestamp, seq), and leaves memory when it commits; the last chunk's transaction also deletes the cycle's rows from `eh_hot`.
     * A step that fails changes nothing and is retried. A partition row is never written twice: the insert skips a
     * (tag, timestamp, seq) the partition already has, so a step repeated after a crash lands once.
     */
    private fun mergeStep() {
        check(writeLock.isHeldByCurrentThread)
        val c = cycle ?: return
        val started = System.nanoTime()
        try {
            val chunk = c.chunks.firstOrNull()
            if (chunk == null) {
                finishCycle(c)
                return
            }
            val lastChunk = c.chunks.size == 1
            val lo = chunk.first().id
            val hi = chunk.last().id
            var moved = 0
            tx(commitUnder = hotLock.writeLock(), afterCommit = {
                for (t in chunk) {
                    val h = t.hot
                    val k = h.dropThrough(c.upTo)
                    moved += k
                    if (k > 0 && h.n == 0) hotTags--
                }
                hotRows -= moved
            }) { state ->
                // Partitions of these rows were made when they were appended; this only finds them.
                val parts = LinkedHashMap<Long, Partition>()
                var last: Partition? = null
                for (t in chunk) {
                    val h = t.hot
                    for (i in 0 until h.n) {
                        if (h.seq[i] > c.upTo) break
                        val ts = h.ts[i]
                        if (last == null || ts < last.start || ts >= last.end) last = state.partition(ts, partitions).also { parts[it.start] = it }
                    }
                }
                for (p in parts.values) {
                    writer.prepareStatement(
                        """
                        INSERT OR IGNORE INTO "${p.table}"(tag_id, ts, seq, k, v, g, q, qd)
                        SELECT tag_id, ts, seq, k, v, g, q, qd FROM eh_hot
                        WHERE seq <= ? AND tag_id >= ? AND tag_id <= ? AND ts >= ? AND ts < ? ORDER BY tag_id, ts, seq
                        """.trimIndent()
                    ).use { ps ->
                        ps.setLong(1, c.upTo)
                        ps.setLong(2, lo)
                        ps.setLong(3, hi)
                        ps.setLong(4, p.start)
                        ps.setLong(5, p.end)
                        ps.executeUpdate()
                    }
                }
                // The newest of these rows per tag, from memory.
                writer.prepareStatement("INSERT INTO eh_latest(tag_id, ts, seq, k, v, q, qd) VALUES (?, ?, ?, ?, ?, ?, ?) $LATEST_UPSERT").use { ps ->
                    for (t in chunk) {
                        val h = t.hot
                        var best = -1
                        for (i in 0 until h.n) {
                            if (h.seq[i] > c.upTo) break
                            if (best < 0 || h.ts[i] >= h.ts[best]) best = i
                        }
                        if (best < 0) continue
                        val q = h.quals[best]!!
                        ps.setLong(1, t.id)
                        ps.setLong(2, h.ts[best])
                        ps.setLong(3, h.seq[best])
                        bindValue(ps, 4, h.values[best])
                        ps.setString(6, q.code.label)
                        setNullable(ps, 7, q.diagnostic)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                if (lastChunk) writer.prepareStatement("DELETE FROM eh_hot WHERE seq <= ?").use { ps -> ps.setLong(1, c.upTo); ps.executeUpdate() }
            }
            c.chunks.removeFirst()
            mergedNotDeleted += moved
            mergedRows += moved
            if (lastChunk) finishCycle(c)
        } catch (e: Exception) {
            lastMergeError = "${e.javaClass.simpleName}: ${e.message}"
            throw e
        } finally {
            lastMergeMillis = clock.millis()
            lastMergeStepMillis = (System.nanoTime() - started) / 1_000_000
        }
    }

    /** The cycle's rows are in their partitions and gone from `eh_hot`. */
    private fun finishCycle(c: MergeCycle) {
        cycle = null
        mergedNotDeleted = 0
        merges++
        lastMergeError = null
        // Rows still waiting arrived after the cycle started.
        hotSinceMillis = if (hotRows == 0) Long.MIN_VALUE else c.startedMillis
    }

    /** Every `eh_hot` row into its partition in one transaction, from the table alone (memory may not have them). */
    private fun mergeTable() {
        tx(commitUnder = hotLock.writeLock(), afterCommit = {
            for (t in tagById.values) if (t.hot.n > 0 || t.hot.maxTs != Long.MIN_VALUE) t.hot.dropThrough(Long.MAX_VALUE)
            hotRows = 0
            hotTags = 0
        }) { state ->
            writer.createStatement().use { st ->
                var from = st.executeQuery("SELECT MIN(ts) FROM eh_hot").use { rs -> rs.next(); rs.getLong(1) }
                while (true) {
                    val p = state.partition(from, partitions)
                    writer.prepareStatement(
                        """
                        INSERT OR IGNORE INTO "${p.table}"(tag_id, ts, seq, k, v, g, q, qd)
                        SELECT tag_id, ts, seq, k, v, g, q, qd FROM eh_hot WHERE ts >= ? AND ts < ? ORDER BY tag_id, ts, seq
                        """.trimIndent()
                    ).use { ps ->
                        ps.setLong(1, p.start)
                        ps.setLong(2, p.end)
                        ps.executeUpdate()
                    }
                    if (p.end == Long.MAX_VALUE) break
                    val next = writer.prepareStatement("SELECT MIN(ts) FROM eh_hot WHERE ts >= ?").use { ps ->
                        ps.setLong(1, p.end)
                        ps.executeQuery().use { rs -> rs.next(); rs.getLong(1).takeUnless { rs.wasNull() } }
                    } ?: break
                    from = next
                }
                st.execute(
                    """
                    INSERT INTO eh_latest(tag_id, ts, seq, k, v, q, qd)
                    SELECT tag_id, ts, seq, k, v, q, qd FROM (
                      SELECT tag_id, ts, seq, k, v, q, qd, ROW_NUMBER() OVER (PARTITION BY tag_id ORDER BY ts DESC, seq DESC) AS rn
                      FROM eh_hot
                    ) WHERE rn = 1
                    """.trimIndent() + "\n" + LATEST_UPSERT
                )
                st.execute("DELETE FROM eh_hot")
            }
        }
        cycle = null
        mergedNotDeleted = 0
        merges++
        lastMergeError = null
        hotSinceMillis = Long.MIN_VALUE
        lastMergeMillis = clock.millis()
    }

    /** Run [body] in one write transaction; publish its partitions, tags and seq only after the commit. */
    private fun tx(commitUnder: java.util.concurrent.locks.Lock? = null, afterCommit: () -> Unit = {}, body: (TxState) -> Unit) {
        check(writeLock.isHeldByCurrentThread)
        val state = TxState()
        writer.autoCommit = false
        try {
            body(state)
            if (commitUnder == null) {
                writer.commit()
                afterCommit()
            } else commitUnder.withLock {
                writer.commit()
                afterCommit()
            }
        } catch (e: Exception) {
            runCatching { writer.rollback() }
            throw e
        } finally {
            writer.autoCommit = true
        }
        for ((k, t) in state.newTags) {
            tags[k] = t
            tagById[t.id] = t
        }
        if (state.newPartitions.isNotEmpty()) {
            partitions = TreeMap(partitions).also { m -> state.newPartitions.forEach { m[it.start] = it } }
        }
        if (state.seqUsed) nextSeq = state.seq
        val pending = state.pending
        if (pending.n > 0) {
            hotLock.write {
                for (i in 0 until pending.n) {
                    val h = pending.tag[i].hot
                    if (h.n == 0) hotTags++
                    h.add(pending.ts[i], pending.seq[i], pending.values[i], pending.quals[i])
                }
                hotRows += pending.n
            }
            if (hotSinceMillis == Long.MIN_VALUE) hotSinceMillis = clock.millis()
        }
    }

    /** [tag]'s waiting rows with a timestamp in `[from, to]`; the caller holds [hotLock] shared. */
    private fun hotSlice(tag: TagEntry, from: Long, to: Long): HotSlice? = tag.hot.takeIf { it.n > 0 }?.slice(from, to)

    override fun query(query: HistoryQuery): List<HistorySample> {
        val tag = tags[query.tagPath.lowercase()] ?: return emptyList()
        if (query.limit <= 0) return emptyList()
        return hotLock.read {
            val hs = hotSlice(tag, query.fromMillis, query.toMillis)
            val ts = ArrayList<Long>()
            val seqs = ArrayList<Long>()
            val rows = ArrayList<HistorySample>()
            for (p in overlapping(query.fromMillis, query.toMillis)) {
                if (rows.size >= query.limit) break
                readTable(p) { c ->
                    c.prepareStatement(
                        "SELECT ts, seq, k, v, q, qd FROM \"${p.table}\" WHERE tag_id = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC, seq ASC LIMIT ?"
                    ).use { ps ->
                        ps.setLong(1, tag.id)
                        ps.setLong(2, query.fromMillis)
                        ps.setLong(3, query.toMillis)
                        ps.setInt(4, query.limit - rows.size)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                ts += rs.getLong(1)
                                seqs += rs.getLong(2)
                                rows += readRow(tag.path, rs, withSeq = true)
                            }
                        }
                    }
                }
            }
            if (hs == null) return@read rows
            // Both lists are in (ts, seq) order; the first [limit] of their union is in these two.
            val out = ArrayList<HistorySample>(minOf(query.limit, rows.size + hs.size))
            var i = 0
            var j = 0
            while (out.size < query.limit && (i < rows.size || j < hs.size)) {
                val c = when {
                    i >= rows.size -> 1
                    j >= hs.size -> -1
                    else -> -hs.compare(j, ts[i], seqs[i])
                }
                when {
                    c < 0 -> out += rows[i++]
                    c > 0 -> out += hs.sample(tag.path, j++)
                    else -> { out += rows[i++]; j++ }
                }
            }
            out
        }
    }

    override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) {
        val tag = tags[tagPath.lowercase()] ?: return
        // Waiting rows are copied first, so a merge during the scan only moves rows this copy already has.
        val hs = hotLock.read { hotSlice(tag, fromMillis, toMillis) }
        var j = 0
        // Page by (ts, seq) inside one partition at a time; locks are not held while [each] runs.
        var cursorStart = fromMillis
        partitions@ while (true) {
            val p = overlapping(cursorStart, toMillis).firstOrNull() ?: break
            var afterTs = Long.MIN_VALUE
            var afterSeq = Long.MIN_VALUE
            var first = true
            while (true) {
                val page = ArrayList<Pair<Long, HistorySample>>()
                val present = readTable(p) { c ->
                    c.prepareStatement(
                        """
                        SELECT ts, seq, k, v, q, qd FROM "${p.table}"
                        WHERE tag_id = ? AND ts >= ? AND ts <= ? AND (? OR ts > ? OR (ts = ? AND seq > ?))
                        ORDER BY ts ASC, seq ASC LIMIT $SCAN_PAGE
                        """.trimIndent()
                    ).use { ps ->
                        ps.setLong(1, tag.id)
                        ps.setLong(2, cursorStart)
                        ps.setLong(3, toMillis)
                        ps.setBoolean(4, first)
                        ps.setLong(5, afterTs)
                        ps.setLong(6, afterTs)
                        ps.setLong(7, afterSeq)
                        ps.executeQuery().use { rs -> while (rs.next()) page += rs.getLong(2) to readRow(tag.path, rs, withSeq = true) }
                    }
                }
                for ((seq, s) in page) {
                    if (hs != null) {
                        while (j < hs.size && hs.compare(j, s.timestampMillis, seq) < 0) each(hs.sample(tag.path, j++))
                        // Merged since the copy was taken: the copy already has it.
                        if (j < hs.size && hs.compare(j, s.timestampMillis, seq) == 0) j++
                    }
                    each(s)
                }
                if (!present || page.size < SCAN_PAGE) break
                first = false
                afterTs = page.last().second.timestampMillis
                afterSeq = page.last().first
            }
            if (p.end == Long.MAX_VALUE || p.end > toMillis) break
            cursorStart = p.end
        }
        if (hs != null) while (j < hs.size) each(hs.sample(tag.path, j++))
    }

    override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? {
        val tag = tags[tagPath.lowercase()] ?: return null
        if (beforeMillis == Long.MIN_VALUE) return null
        return hotLock.read {
            val hs = hotSlice(tag, Long.MIN_VALUE, beforeMillis - 1)
            val best = hs?.let { it.size - 1 }
            for (p in overlapping(Long.MIN_VALUE, beforeMillis - 1).asReversed()) {
                if (hs != null && best != null && p.end <= hs.ts[best]) break
                var hit: HistorySample? = null
                var hitSeq = 0L
                readTable(p) { c ->
                    c.prepareStatement("SELECT ts, seq, k, v, q, qd FROM \"${p.table}\" WHERE tag_id = ? AND ts < ? ORDER BY ts DESC, seq DESC LIMIT 1").use { ps ->
                        ps.setLong(1, tag.id)
                        ps.setLong(2, beforeMillis)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                hitSeq = rs.getLong(2)
                                hit = readRow(tag.path, rs, withSeq = true)
                            }
                        }
                    }
                }
                val h = hit ?: continue
                return@read if (hs != null && best != null && hs.compare(best, h.timestampMillis, hitSeq) > 0) hs.sample(tag.path, best) else h
            }
            if (hs != null && best != null) hs.sample(tag.path, best) else null
        }
    }

    override fun latestAll(): Map<String, HistorySample> = hotLock.read {
        val out = HashMap<String, HistorySample>()
        val stored = HashMap<Long, Long>()
        readLock.withLock {
            reader.prepareStatement("SELECT tag_id, ts, k, v, q, qd FROM eh_latest").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val tag = tagById[rs.getLong(1)] ?: continue
                        out[tag.path] = HistorySample(tag.path, rs.getLong(2), decode(rs, 3), ValueCodec.quality(rs.getString(5), rs.getString(6)))
                        stored[tag.id] = rs.getLong(2)
                    }
                }
            }
        }
        // A waiting row is a later write than every row in eh_latest, so it wins a tie.
        for (tag in tagById.values) {
            val h = tag.hot
            if (h.n == 0) continue
            var best = 0
            for (i in 1 until h.n) if (h.ts[i] >= h.ts[best]) best = i
            val storedTs = stored[tag.id]
            if (storedTs == null || h.ts[best] >= storedTs) {
                val q = h.quals[best]!!
                out[tag.path] = HistorySample(tag.path, h.ts[best], h.values[best], ValueCodec.quality(q.code.label, q.diagnostic))
            }
        }
        out
    }

    override fun aggregateNative(
        tagPath: String,
        fromMillis: Long,
        toMillis: Long,
        intervalMillis: Long,
        mode: HistoryAggregate,
        interpolation: HistoryInterpolation,
    ): List<AggregateWindow>? {
        if (interpolation != HistoryInterpolation.NONE || mode == HistoryAggregate.AVERAGE) return null
        val n = HistoryAggregator.windowCount(fromMillis, toMillis, intervalMillis)
        val end = toMillis + 1
        fun windowStart(i: Int) = fromMillis + i * intervalMillis
        fun windowEnd(i: Int) = if (i == n - 1) end else fromMillis + (i + 1) * intervalMillis
        val rows = IntArray(n)
        val good = LongArray(n)
        val nan = LongArray(n)
        val sum = DoubleArray(n)
        val min = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val max = DoubleArray(n) { Double.NEGATIVE_INFINITY }
        val last = arrayOfNulls<Any>(n)
        val lastTs = LongArray(n) { Long.MIN_VALUE }
        val lastSeq = LongArray(n) { Long.MIN_VALUE }
        // MIN and MAX in SQL keep the first of equal values in (timestamp, seq) order; only 0.0 and -0.0 are equal
        // and still differ, so the earliest good zero per window decides which one a zero minimum or maximum is.
        val zeroTs = LongArray(n) { Long.MAX_VALUE }
        val zeroSeq = LongArray(n) { Long.MAX_VALUE }
        val zero = DoubleArray(n)
        val zeros = mode == HistoryAggregate.MINIMUM || mode == HistoryAggregate.MAXIMUM || mode == HistoryAggregate.RANGE
        var hotZero = false
        val tag = tags[tagPath.lowercase()]
        // Shared for the whole read, so a merge cannot move a waiting row into a partition between the two halves.
        if (tag != null) hotLock.read {
        val hs = hotSlice(tag, fromMillis, toMillis)
        hotZero = zeros && hs != null && (0 until hs.size).any { r -> hs.quals[r].level == Level.GOOD && numeric(hs.values[r]) == 0.0 }
        for (p in overlapping(fromMillis, toMillis)) {
            readTable(p) { c ->
                if (mode == HistoryAggregate.LAST_VALUE) {
                    c.prepareStatement(
                        """
                        SELECT w, n, k, v, ts, seq FROM (
                          SELECT (ts - ?1) / ?2 AS w, k, v, ts, seq,
                            ROW_NUMBER() OVER (PARTITION BY (ts - ?1) / ?2 ORDER BY ts DESC, seq DESC) AS rn,
                            COUNT(*) OVER (PARTITION BY (ts - ?1) / ?2) AS n
                          FROM "${p.table}" WHERE tag_id = ?3 AND ts >= ?4 AND ts <= ?5
                        ) WHERE rn = 1
                        """.trimIndent()
                    ).use { ps ->
                        bindWindow(ps, fromMillis, intervalMillis, tag.id, toMillis)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                val i = rs.getLong(1).toInt()
                                rows[i] += rs.getInt(2)
                                last[i] = decode(rs, 3)
                                lastTs[i] = rs.getLong(5)
                                lastSeq[i] = rs.getLong(6)
                            }
                        }
                    }
                } else {
                    c.prepareStatement(
                        """
                        SELECT (ts - ?1) / ?2 AS w, COUNT(*),
                          SUM(CASE WHEN g = 1 AND k BETWEEN 1 AND 4 THEN 1 ELSE 0 END),
                          TOTAL(CASE WHEN g = 1 AND k BETWEEN 1 AND 4 THEN v END),
                          MIN(CASE WHEN g = 1 AND k BETWEEN 1 AND 4 THEN v END),
                          MAX(CASE WHEN g = 1 AND k BETWEEN 1 AND 4 THEN v END),
                          SUM(CASE WHEN g = 1 AND k = 4 AND v IS NULL THEN 1 ELSE 0 END)
                        FROM "${p.table}" WHERE tag_id = ?3 AND ts >= ?4 AND ts <= ?5 GROUP BY w
                        """.trimIndent()
                    ).use { ps ->
                        bindWindow(ps, fromMillis, intervalMillis, tag.id, toMillis)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                val i = rs.getLong(1).toInt()
                                rows[i] += rs.getInt(2)
                                good[i] += rs.getLong(3)
                                val s = rs.getDouble(4)
                                sum[i] += if (rs.wasNull()) Double.NaN else s
                                val mn = rs.getDouble(5)
                                if (!rs.wasNull() && mn < min[i]) min[i] = mn
                                val mx = rs.getDouble(6)
                                if (!rs.wasNull() && mx > max[i]) max[i] = mx
                                nan[i] += rs.getLong(7)
                            }
                        }
                    }
                    // Only needed when waiting rows hold a zero too; otherwise the SQL answer stands.
                    if (hotZero) c.prepareStatement(
                        """
                        SELECT w, v, ts, seq FROM (
                          SELECT (ts - ?1) / ?2 AS w, v, ts, seq,
                            ROW_NUMBER() OVER (PARTITION BY (ts - ?1) / ?2 ORDER BY ts, seq) AS rn
                          FROM "${p.table}" WHERE tag_id = ?3 AND ts >= ?4 AND ts <= ?5 AND g = 1 AND k BETWEEN 1 AND 4 AND v = 0
                        ) WHERE rn = 1
                        """.trimIndent()
                    ).use { ps ->
                        bindWindow(ps, fromMillis, intervalMillis, tag.id, toMillis)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                val i = rs.getLong(1).toInt()
                                val ts = rs.getLong(3)
                                val sq = rs.getLong(4)
                                if (ts < zeroTs[i] || (ts == zeroTs[i] && sq < zeroSeq[i])) {
                                    zeroTs[i] = ts
                                    zeroSeq[i] = sq
                                    zero[i] = rs.getDouble(2)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Waiting rows, with the same rules as the SQL above.
        if (hs != null) for (r in 0 until hs.size) {
            val i = ((hs.ts[r] - fromMillis) / intervalMillis).toInt()
            rows[i]++
            if (mode == HistoryAggregate.LAST_VALUE) {
                if (hs.compare(r, lastTs[i], lastSeq[i]) > 0) {
                    last[i] = hs.values[r]
                    lastTs[i] = hs.ts[r]
                    lastSeq[i] = hs.seq[r]
                }
                continue
            }
            if (hs.quals[r].level != Level.GOOD) continue
            val v: Double = numeric(hs.values[r]) ?: continue
            good[i]++
            if (hotZero && v == 0.0 && hs.compare(r, zeroTs[i], zeroSeq[i]) < 0) {
                zeroTs[i] = hs.ts[r]
                zeroSeq[i] = hs.seq[r]
                zero[i] = v
            }
            if (v.isNaN()) {
                nan[i]++
                continue
            }
            sum[i] += v
            if (v < min[i]) min[i] = v
            if (v > max[i]) max[i] = v
        }
        }
        if (hotZero) for (i in 0 until n) if (zeroTs[i] != Long.MAX_VALUE) {
            if (min[i] == 0.0) min[i] = zero[i]
            if (max[i] == 0.0) max[i] = zero[i]
        }
        return List(n) { i ->
            val g = good[i]
            val s = if (nan[i] > 0) Double.NaN else sum[i]
            val value: Any? = when (mode) {
                HistoryAggregate.SIMPLE_AVERAGE -> if (g > 0) s / g else null
                HistoryAggregate.SUM -> if (g > 0) s else null
                HistoryAggregate.MINIMUM -> if (g > 0) min[i] else null
                HistoryAggregate.MAXIMUM -> if (g > 0) max[i] else null
                HistoryAggregate.RANGE -> if (g > 0) max[i] - min[i] else null
                HistoryAggregate.LAST_VALUE -> if (rows[i] > 0) last[i] else null
                HistoryAggregate.COUNT -> rows[i]
                HistoryAggregate.AVERAGE -> error("not native")
            }
            AggregateWindow(windowStart(i), windowEnd(i), value, rows[i])
        }
    }

    /**
     * Prune or archive partitions that ended at or before [now] minus the maintenance age. Returns how many
     * partitions were removed. Does nothing in [MaintenanceMode.NONE].
     */
    fun maintain(now: Instant = clock.instant()): Int {
        val s = currentSettings
        if (s.maintenanceMode == MaintenanceMode.NONE) return 0
        val cutoff = s.maintenanceAgeUnits.minus(now.toEpochMilli(), s.maintenanceAge)
        var removed = 0
        writeLock.withLock {
            try {
                // Waiting rows of an old partition must be in it before it is archived or dropped.
                mergeHot()
                for (p in partitions.values.toList()) {
                    if (p.end > cutoff) break
                    if (s.maintenanceMode == MaintenanceMode.ARCHIVE) archive(p, s.archiveFolder!!)
                    drop(p)
                    removed++
                    if (s.maintenanceMode == MaintenanceMode.ARCHIVE) archived++ else pruned++
                }
                lastMaintenanceError = null
            } catch (e: Exception) {
                lastMaintenanceError = "${e.javaClass.simpleName}: ${e.message}"
                throw e
            } finally {
                lastMaintenanceMillis = now.toEpochMilli()
            }
        }
        return removed
    }

    /** Archive file for the partition starting at [start] inside [folder]. */
    fun archiveFile(folder: Path, start: Long): Path = folder.resolve("history-" + start.toString().replace('-', 'n') + ".sqlite")

    private fun archive(p: Partition, folder: Path) {
        folder.createDirectories()
        val target = archiveFile(folder, p.start)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        tmp.deleteIfExists()
        writer.prepareStatement("ATTACH DATABASE ? AS arc").use { ps ->
            ps.setString(1, tmp.toAbsolutePath().toString())
            ps.execute()
        }
        try {
            writer.createStatement().use { st ->
                st.execute("CREATE TABLE arc.partition_info (start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL)")
                st.execute("INSERT INTO arc.partition_info VALUES (${p.start}, ${p.end})")
                st.execute(
                    """
                    CREATE TABLE arc.samples AS
                    SELECT t.path AS tag_path, s.ts, s.seq, s.k, s.v, s.g, s.q, s.qd
                    FROM main."${p.table}" s JOIN main.eh_tag t ON t.id = s.tag_id
                    ORDER BY t.path, s.ts, s.seq
                    """.trimIndent()
                )
            }
        } finally {
            writer.createStatement().use { it.execute("DETACH DATABASE arc") }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun drop(p: Partition) = schemaLock.write {
        probeStatements.remove(p.table)?.let { runCatching { it.close() } }
        writer.autoCommit = false
        try {
            writer.createStatement().use { st -> st.execute("DROP TABLE IF EXISTS \"${p.table}\"") }
            writer.prepareStatement("DELETE FROM eh_partition WHERE start_ms = ?").use { ps ->
                ps.setLong(1, p.start)
                ps.executeUpdate()
            }
            writer.commit()
        } catch (e: Exception) {
            runCatching { writer.rollback() }
            throw e
        } finally {
            writer.autoCommit = true
        }
        partitions = TreeMap(partitions).also { it.remove(p.start) }
    }

    /** Partitions, settings and maintenance counters, for diagnostics. */
    fun status(): Map<String, Any?> {
        val s = currentSettings
        return linkedMapOf(
            "type" to "embedded",
            "partitionInterval" to s.partitionInterval.wire,
            "deduplication" to s.deduplication,
            "maintenanceMode" to s.maintenanceMode.wire,
            "maintenanceAge" to s.maintenanceAge,
            "maintenanceAgeUnits" to s.maintenanceAgeUnits.wire,
            "partitions" to partitions.values.map {
                linkedMapOf("start" to instantText(it.start), "end" to instantText(it.end))
            },
            "tags" to tags.size,
            "pruned" to pruned,
            "archived" to archived,
            "lastMaintenance" to lastMaintenanceMillis?.let { instantText(it) },
            "lastMaintenanceError" to lastMaintenanceError,
            "waitingRows" to hotRows,
            "merges" to merges,
            "mergedRows" to mergedRows,
            "lastMerge" to lastMergeMillis?.let { instantText(it) },
            "lastMergeError" to lastMergeError,
            "lastMergeStepMillis" to lastMergeStepMillis,
        )
    }

    /** `[start, end)` of each partition, oldest first. */
    fun partitionBounds(): List<Pair<Long, Long>> = partitions.values.map { it.start to it.end }

    override fun close() {
        writeLock.withLock {
            readLock.withLock {
                probeStatements.values.forEach { runCatching { it.close() } }
                probeStatements.clear()
                listOf(insertHot, updateSeq).forEach { runCatching { it.close() } }
                runCatching { insertTag.close() }
                runCatching { lastRowId.close() }
                runCatching { reader.close() }
                writer.close()
            }
        }
    }

    /** Partitions overlapping `[from, to]`, oldest first. */
    private fun overlapping(from: Long, to: Long): List<Partition> {
        val map = partitions
        val out = ArrayList<Partition>()
        map.floorEntry(from)?.value?.let { if (it.end > from) out += it }
        for (p in map.subMap(from, false, to, true).values) out += p
        return out
    }

    /**
     * Run [body] on the read connection while [p]'s table is guaranteed to exist. Returns false (and skips
     * [body]) when maintenance dropped the partition since [overlapping] listed it.
     */
    private fun readTable(p: Partition, body: (Connection) -> Unit): Boolean = schemaLock.read {
        if (partitions[p.start]?.table != p.table) return false
        readLock.withLock {
            try {
                body(reader)
            } catch (e: SQLException) {
                throw HistoryReadException("local history read failed: ${e.message}", e)
            }
        }
        true
    }

    private fun migrateFlatStore() {
        val hasFlat = writer.createStatement().use { st ->
            st.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'history_sample'").use { it.next() }
        }
        if (!hasFlat) return
        writeLock.withLock {
            tx { state ->
                var afterId = 0L
                while (true) {
                    val page = ArrayList<HistorySample>()
                    writer.prepareStatement(
                        "SELECT id, tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic FROM history_sample WHERE id > ? ORDER BY id LIMIT $SCAN_PAGE"
                    ).use { ps ->
                        ps.setLong(1, afterId)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                afterId = rs.getLong(1)
                                page += HistorySample(
                                    rs.getString(2), rs.getLong(3), ValueCodec.decode(rs.getString(4), rs.getString(5)),
                                    ValueCodec.quality(rs.getString(6), rs.getString(7)),
                                )
                            }
                        }
                    }
                    if (page.isEmpty()) break
                    write(state, page)
                }
                writer.createStatement().use { st ->
                    st.execute("DROP TABLE history_sample")
                    st.execute("DROP TABLE IF EXISTS history_latest")
                }
            }
        }
    }

    private companion object {
        const val SCAN_PAGE = 5_000
        const val WAL_LIMIT_BYTES = 64L * 1024 * 1024
        /** Waiting rows that start a merge after an append (about 11 rows per tag at 88,500 tags). */
        const val MERGE_ROWS = 1_000_000
        /** Longest a row waits in `eh_hot` before an append merges it (rows are on disk all along). */
        const val MERGE_AGE_MILLIS = 60_000L
        /** About 12 rows per tag per merge: each tag's last leaf page is rewritten once per 12 rows, not once per row. */
        const val MERGE_ROWS_PER_TAG = 12
        /** Rows one merge step moves, about: a step holds the writer for well under a second. */
        const val MERGE_CHUNK_ROWS = 250_000
        const val LATEST_UPSERT = """
            ON CONFLICT(tag_id) DO UPDATE SET ts = excluded.ts, seq = excluded.seq, k = excluded.k, v = excluded.v,
              q = excluded.q, qd = excluded.qd
            WHERE excluded.ts >= eh_latest.ts
        """
        /** The per-tag rule never waits for more rows than this before a merge. */
        const val MAX_MERGE_ROWS = 4_000_000L
        /** Waiting rows (at least, or 4 x the merge point) above which an append first merges, and fails if the merge does. */
        const val HOT_ROWS_LIMIT = 4L * MERGE_ROWS
        /** A tag's in-memory arrays larger than this are released after a merge. */
        const val KEEP_HOT_CAPACITY = 1_024
        val EMPTY_LONGS = LongArray(0)
        val EMPTY_VALUES = arrayOfNulls<Any>(0)
        val EMPTY_QUALITIES = arrayOfNulls<Quality>(0)

        /** A value as the SQL aggregates read it: numbers and booleans, else null. */
        fun numeric(x: Any?): Double? = when (x) {
            is Boolean -> if (x) 1.0 else 0.0
            is Int -> x.toDouble()
            is Long -> x.toDouble()
            is Double -> x
            else -> null
        }

        /** [value] as a read decodes it from the row [bindValue] writes. */
        fun normalize(value: Any?): Any? = when (value) {
            null, is Boolean, is Long, is String -> value
            is Int -> value
            is Short, is Byte -> (value as Number).toInt()
            is Float -> value.toDouble()
            is Double -> value
            else -> value.toString()
        }

        /** The dedup test: same kind and value (SQL equality, so NaN equals NaN and 0.0 equals -0.0), quality label and diagnostic. */
        fun sameRow(a: Any?, qa: Quality, b: Any?, qb: Quality): Boolean {
            if (qa.code.label != qb.code.label || qa.diagnostic != qb.diagnostic) return false
            return when (a) {
                null -> b == null
                is Double -> b is Double && (a == b || (a.isNaN() && b.isNaN()))
                is Boolean -> b is Boolean && a == b
                is Int -> b is Int && a == b
                is Long -> b is Long && a == b
                else -> b is String && a == b
            }
        }
        /** Page cache per connection, in KiB (32 MiB). */
        const val CACHE_KIB = 32_768
        const val K_NULL = 0
        const val K_BOOL = 1
        const val K_INT = 2
        const val K_LONG = 3
        const val K_DOUBLE = 4
        const val K_STRING = 5

        /** One row's 8 parameters. */
        fun bindRow(ps: PreparedStatement, s: HistorySample, tagId: Long, seq: Long) {
            ps.setLong(1, tagId)
            ps.setLong(2, s.timestampMillis)
            ps.setLong(3, seq)
            bindValue(ps, 4, s.value)
            ps.setInt(6, if (s.quality.level == Level.GOOD) 1 else 0)
            ps.setString(7, s.quality.code.label)
            setNullable(ps, 8, s.quality.diagnostic)
        }

        fun bindWindow(ps: PreparedStatement, from: Long, interval: Long, tagId: Long, to: Long) {
            ps.setLong(1, from)
            ps.setLong(2, interval)
            ps.setLong(3, tagId)
            ps.setLong(4, from)
            ps.setLong(5, to)
        }

        /** Binds `k` at [at] and `v` at [at] + 1. */
        fun bindValue(ps: PreparedStatement, at: Int, value: Any?) {
            when (value) {
                null -> { ps.setInt(at, K_NULL); ps.setNull(at + 1, Types.NULL) }
                is Boolean -> { ps.setInt(at, K_BOOL); ps.setLong(at + 1, if (value) 1 else 0) }
                is Int, is Short, is Byte -> { ps.setInt(at, K_INT); ps.setLong(at + 1, (value as Number).toLong()) }
                is Long -> { ps.setInt(at, K_LONG); ps.setLong(at + 1, value) }
                is Float, is Double -> {
                    ps.setInt(at, K_DOUBLE)
                    val d = (value as Number).toDouble()
                    // SQLite stores NaN as NULL; the kind keeps it a double.
                    if (d.isNaN()) ps.setNull(at + 1, Types.DOUBLE) else ps.setDouble(at + 1, d)
                }
                else -> { ps.setInt(at, K_STRING); ps.setString(at + 1, value.toString()) }
            }
        }

        /** The value from `k` at column [at] and `v` at [at] + 1. */
        fun decode(rs: ResultSet, at: Int): Any? = when (rs.getInt(at)) {
            K_NULL -> null
            K_BOOL -> rs.getLong(at + 1) != 0L
            K_INT -> rs.getLong(at + 1).toInt()
            K_LONG -> rs.getLong(at + 1)
            K_DOUBLE -> rs.getDouble(at + 1).let { if (rs.wasNull()) Double.NaN else it }
            else -> rs.getString(at + 1)
        }

        /** A row selected as `ts, [seq,] k, v, q, qd`. */
        fun readRow(path: String, rs: ResultSet, withSeq: Boolean): HistorySample {
            val k = if (withSeq) 3 else 2
            return HistorySample(path, rs.getLong(1), decode(rs, k), ValueCodec.quality(rs.getString(k + 2), rs.getString(k + 3)))
        }

        fun setNullable(ps: PreparedStatement, at: Int, text: String?) {
            if (text == null) ps.setNull(at, Types.VARCHAR) else ps.setString(at, text)
        }

        fun instantText(millis: Long): String = try {
            Instant.ofEpochMilli(millis).toString()
        } catch (e: DateTimeException) {
            millis.toString()
        }
    }
}
