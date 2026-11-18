package org.oip.historian

import org.oip.tags.Quality
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.io.path.createDirectories

/** Read side of a history provider: raw rows for one tag, oldest first. */
interface HistoryReader {
    fun query(query: HistoryQuery): List<HistorySample>

    /** Every row of [tagPath] in the inclusive range, oldest first, streamed to [each] (no row limit). */
    fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit)

    /** The newest row of [tagPath] strictly before [beforeMillis]: the value in effect when a range starts. */
    fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample?
}

/** Persistence for stored history samples. Called from the collector's writer, never from a tag listener. */
interface HistorySink : HistoryReader, AutoCloseable {
    /** Store [samples] atomically, in order. */
    fun appendAll(samples: List<HistorySample>)

    /** Last stored row per tag path (insertion order). Read once at startup to seed deadband comparison. */
    fun latestAll(): Map<String, HistorySample>

    override fun close() {}
}

/**
 * SQLite history store (ADR 0008): one file for this concern (`<dataDir>/db/history.sqlite`), WAL
 * journal mode. Each [appendAll] batch is one transaction. Values are stored as `kind` + text so
 * booleans, integers, doubles, and strings come back with their original type.
 *
 * Tag paths compare case-insensitively (column collation NOCASE), matching tag path lookup.
 */
class SqliteHistoryStore(val path: Path) : HistorySink {
    private val connection: Connection
    private val lock = Any()

    init {
        path.parent?.createDirectories()
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=FULL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS history_sample (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  tag_path TEXT NOT NULL COLLATE NOCASE,
                  ts_millis INTEGER NOT NULL,
                  value_kind TEXT NOT NULL,
                  value_text TEXT,
                  quality TEXT NOT NULL,
                  quality_diagnostic TEXT
                )
                """.trimIndent()
            )
            st.execute("CREATE INDEX IF NOT EXISTS history_sample_tag_ts ON history_sample(tag_path, ts_millis)")
            val hadLatest = st.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'history_latest'"
            ).use { it.next() }
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS history_latest (
                  tag_path TEXT PRIMARY KEY COLLATE NOCASE,
                  ts_millis INTEGER NOT NULL,
                  value_kind TEXT NOT NULL,
                  value_text TEXT,
                  quality TEXT NOT NULL,
                  quality_diagnostic TEXT
                )
                """.trimIndent()
            )
            // A database written before history_latest existed: build it once from the samples.
            if (!hadLatest) st.execute(
                """
                INSERT OR REPLACE INTO history_latest(tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic)
                SELECT h.tag_path, h.ts_millis, h.value_kind, h.value_text, h.quality, h.quality_diagnostic
                FROM history_sample h
                JOIN (SELECT MAX(id) AS id FROM history_sample GROUP BY tag_path) last ON h.id = last.id
                """.trimIndent()
            )
        }
    }

    override fun appendAll(samples: List<HistorySample>) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "INSERT INTO history_sample(tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic) VALUES (?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    for (sample in samples) {
                        bindRow(ps, sample)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                // Last row per tag in this batch, so startup reads one row per tag instead of scanning samples.
                val last = LinkedHashMap<String, HistorySample>()
                for (sample in samples) last[sample.tagPath.lowercase()] = sample
                connection.prepareStatement(
                    """
                    INSERT INTO history_latest(tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(tag_path) DO UPDATE SET tag_path = excluded.tag_path, ts_millis = excluded.ts_millis,
                      value_kind = excluded.value_kind, value_text = excluded.value_text,
                      quality = excluded.quality, quality_diagnostic = excluded.quality_diagnostic
                    """.trimIndent()
                ).use { ps ->
                    for (sample in last.values) {
                        bindRow(ps, sample)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                connection.commit()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun query(query: HistoryQuery): List<HistorySample> = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic
            FROM history_sample
            WHERE tag_path = ? AND ts_millis >= ? AND ts_millis <= ?
            ORDER BY ts_millis ASC, id ASC
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, query.tagPath)
            ps.setLong(2, query.fromMillis)
            ps.setLong(3, query.toMillis)
            ps.setInt(4, query.limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<HistorySample>()
                while (rs.next()) out += read(rs)
                out
            }
        }
    }

    override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) {
        // Page by (ts, id) so the connection lock is not held while [each] runs and memory stays bounded.
        var afterTs = Long.MIN_VALUE
        var afterId = Long.MIN_VALUE
        var first = true
        while (true) {
            val page = synchronized(lock) {
                connection.prepareStatement(
                    """
                    SELECT id, tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic
                    FROM history_sample
                    WHERE tag_path = ? AND ts_millis >= ? AND ts_millis <= ? AND (? OR ts_millis > ? OR (ts_millis = ? AND id > ?))
                    ORDER BY ts_millis ASC, id ASC
                    LIMIT $SCAN_PAGE
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, tagPath)
                    ps.setLong(2, fromMillis)
                    ps.setLong(3, toMillis)
                    ps.setBoolean(4, first)
                    ps.setLong(5, afterTs)
                    ps.setLong(6, afterTs)
                    ps.setLong(7, afterId)
                    ps.executeQuery().use { rs ->
                        val out = ArrayList<Pair<Long, HistorySample>>()
                        while (rs.next()) out += rs.getLong("id") to read(rs)
                        out
                    }
                }
            }
            for ((_, sample) in page) each(sample)
            if (page.size < SCAN_PAGE) return
            first = false
            afterTs = page.last().second.timestampMillis
            afterId = page.last().first
        }
    }

    override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT tag_path, ts_millis, value_kind, value_text, quality, quality_diagnostic
            FROM history_sample WHERE tag_path = ? AND ts_millis < ?
            ORDER BY ts_millis DESC, id DESC LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, tagPath)
            ps.setLong(2, beforeMillis)
            ps.executeQuery().use { rs -> if (rs.next()) read(rs) else null }
        }
    }

    /** Last row per tag from `history_latest` (one row per tag, kept in each append's transaction). */
    override fun latestAll(): Map<String, HistorySample> = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT h.tag_path, h.ts_millis, h.value_kind, h.value_text, h.quality, h.quality_diagnostic
            FROM history_latest h
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = HashMap<String, HistorySample>()
                while (rs.next()) read(rs).let { out[it.tagPath] = it }
                out
            }
        }
    }

    override fun close() = synchronized(lock) { connection.close() }

    private fun bindRow(ps: PreparedStatement, sample: HistorySample) {
        ps.setString(1, sample.tagPath)
        ps.setLong(2, sample.timestampMillis)
        val (kind, text) = ValueCodec.encode(sample.value)
        ps.setString(3, kind)
        if (text == null) ps.setNull(4, Types.VARCHAR) else ps.setString(4, text)
        ps.setString(5, sample.quality.code.label)
        val diag = sample.quality.diagnostic
        if (diag == null) ps.setNull(6, Types.VARCHAR) else ps.setString(6, diag)
    }

    private fun read(rs: ResultSet): HistorySample = HistorySample(
        tagPath = rs.getString("tag_path"),
        timestampMillis = rs.getLong("ts_millis"),
        value = ValueCodec.decode(rs.getString("value_kind"), rs.getString("value_text")),
        quality = ValueCodec.quality(rs.getString("quality"), rs.getString("quality_diagnostic")),
    )

    private companion object {
        const val SCAN_PAGE = 5_000
    }
}

/** Typed value <-> (`kind`, text) columns, shared by the local store, the forward buffer, and SQL providers. */
internal object ValueCodec {
        fun encode(value: Any?): Pair<String, String?> = when (value) {
            null -> "null" to null
            is Boolean -> "bool" to value.toString()
            is Int, is Short, is Byte -> "int" to value.toString()
            is Long -> "long" to value.toString()
            is Float -> "double" to value.toDouble().toString()
            is Double -> "double" to value.toString()
            else -> "string" to value.toString()
        }

        fun decode(kind: String, text: String?): Any? = when (kind) {
            "null" -> null
            "bool" -> text!!.toBoolean()
            "int" -> text!!.toInt()
            "long" -> text!!.toLong()
            "double" -> text!!.toDouble()
            else -> text
        }

        fun quality(label: String, diagnostic: String?): Quality = Quality.fromName(label, diagnostic) ?: Quality.BAD

        /** Numeric view used by aggregates and the SQL `value_num` column: numbers, and booleans as 1/0. */
        fun numeric(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is Boolean -> if (value) 1.0 else 0.0
            else -> null
        }
}
