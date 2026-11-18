package org.oip.edgesync

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.createDirectories

data class LocalHistory(
    val id: Long,
    val provider: String,
    val path: String,
    val valueJson: String,
    val quality: String,
    val timestampMicros: Long,
    val forwarded: Boolean,
)

data class RemoteHistory(
    val sourceGateway: String,
    val remoteProvider: String,
    val localId: Long,
    val provider: String,
    val path: String,
    val valueJson: String,
    val quality: String,
    val timestampMicros: Long,
)

data class RemoteAudit(
    val sourceGateway: String,
    val remoteProfile: String,
    val seq: Long,
    val timestampMillis: Long,
    val actor: String,
    val actorHost: String,
    val action: String,
    val target: String,
    val detail: String,
    val status: String,
    val origin: String,
    val context: String,
)

data class RemoteAlarm(
    val sourceGateway: String,
    val remoteJournal: String,
    val localId: Long,
    val eventId: String,
    val source: String,
    val displayPath: String,
    val priority: Int,
    val eventType: Int,
    val eventFlags: Int,
    val eventTimeMillis: Long,
    val dataJson: String,
)

data class NewHistory(
    val provider: String,
    val path: String,
    val valueJson: String,
    val quality: String,
    val timestampMicros: Long,
)

/**
 * Durable edge-sync buffer and the hub inbox. One SQLite file under `db/`.
 * History kept after a successful forward is the spoke's local copy.
 *
 */
class EdgeSyncDatabase(
    val file: Path,
    private val historyRetentionDays: Int = HISTORY_RETENTION_DAYS,
    private val historyMaxPoints: Long = HISTORY_MAX_POINTS,
) : AutoCloseable {
    private val lock = Any()
    private var conn: Connection = open()

    init {
        file.parent?.createDirectories()
    }

    fun insertHistory(rows: List<NewHistory>) {
        if (rows.isEmpty()) return
        synchronized(lock) {
            transaction {
                val ps = conn.prepareStatement(
                    "INSERT INTO history_local(provider, path, value_json, quality, timestamp_micros, forwarded) VALUES (?, ?, ?, ?, ?, 0)",
                )
                ps.use {
                    for (row in rows) {
                        it.setString(1, row.provider)
                        it.setString(2, row.path)
                        it.setString(3, row.valueJson)
                        it.setString(4, row.quality)
                        it.setLong(5, row.timestampMicros)
                        it.addBatch()
                    }
                    it.executeBatch()
                }
            }
        }
    }

    fun nextHistory(limit: Int): List<LocalHistory> = synchronized(lock) {
        val out = ArrayList<LocalHistory>()
        conn.prepareStatement(
            "SELECT id, provider, path, value_json, quality, timestamp_micros, forwarded FROM history_local " +
                "WHERE forwarded = 0 ORDER BY id ASC LIMIT ?",
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) out += readLocal(rs)
            }
        }
        out
    }

    fun markForwarded(ids: List<Long>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            transaction {
                conn.prepareStatement("UPDATE history_local SET forwarded = 1 WHERE id = ?").use { ps ->
                    for (id in ids) {
                        ps.setLong(1, id)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
    }

    fun pruneHistory(nowMicros: Long) {
        synchronized(lock) {
            val span = historyRetentionDays.toLong() * 86_400L * 1_000_000L
            if (historyRetentionDays > 0 && nowMicros > span) {
                conn.prepareStatement("DELETE FROM history_local WHERE timestamp_micros < ?").use { ps ->
                    ps.setLong(1, nowMicros - span)
                    ps.executeUpdate()
                }
            }
            var extra = countHistory() - historyMaxPoints
            if (extra > 0) extra -= deleteOldest(forwarded = 1, extra)
            if (extra > 0) deleteOldest(forwarded = 0, extra)
        }
    }

    fun pendingHistory(): Int = synchronized(lock) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM history_local WHERE forwarded = 0").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
    }

    fun localHistory(provider: String?, limit: Int): List<LocalHistory> = synchronized(lock) {
        val sql = if (provider == null) {
            "SELECT id, provider, path, value_json, quality, timestamp_micros, forwarded FROM history_local ORDER BY id DESC LIMIT ?"
        } else {
            "SELECT id, provider, path, value_json, quality, timestamp_micros, forwarded FROM history_local WHERE provider = ? ORDER BY id DESC LIMIT ?"
        }
        val out = ArrayList<LocalHistory>()
        conn.prepareStatement(sql).use { ps ->
            if (provider == null) ps.setInt(1, limit) else {
                ps.setString(1, provider)
                ps.setInt(2, limit)
            }
            ps.executeQuery().use { rs -> while (rs.next()) out += readLocal(rs) }
        }
        out
    }

    fun acceptHistory(source: String, remoteProvider: String, points: List<RemoteHistory>): Long {
        if (points.isEmpty()) return 0
        synchronized(lock) {
            transaction {
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO history_remote(source_gateway, remote_provider, local_id, provider, path, value_json, quality, timestamp_micros) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    for (point in points) {
                        ps.setString(1, source)
                        ps.setString(2, remoteProvider)
                        ps.setLong(3, point.localId)
                        ps.setString(4, point.provider)
                        ps.setString(5, point.path)
                        ps.setString(6, point.valueJson)
                        ps.setString(7, point.quality)
                        ps.setLong(8, point.timestampMicros)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
        return points.last().localId
    }

    fun remoteHistory(provider: String?, source: String?, limit: Int): List<RemoteHistory> = synchronized(lock) {
        val clauses = ArrayList<String>()
        val args = ArrayList<Any>()
        if (provider != null) {
            clauses += "remote_provider = ?"
            args += provider
        }
        if (source != null) {
            clauses += "source_gateway = ?"
            args += source
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        val out = ArrayList<RemoteHistory>()
        conn.prepareStatement(
            "SELECT source_gateway, remote_provider, local_id, provider, path, value_json, quality, timestamp_micros " +
                "FROM history_remote $where ORDER BY source_gateway, local_id DESC LIMIT ?",
        ).use { ps ->
            args.forEachIndexed { index, value -> bind(ps, index + 1, value) }
            ps.setInt(args.size + 1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += RemoteHistory(
                        rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8),
                    )
                }
            }
        }
        out
    }

    fun acceptAudit(rows: List<RemoteAudit>): Long {
        if (rows.isEmpty()) return 0
        synchronized(lock) {
            transaction {
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO audit_remote(source_gateway, remote_profile, seq, timestamp_millis, actor, actor_host, action, target, detail, status, origin, context) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    for (row in rows) {
                        ps.setString(1, row.sourceGateway)
                        ps.setString(2, row.remoteProfile)
                        ps.setLong(3, row.seq)
                        ps.setLong(4, row.timestampMillis)
                        ps.setString(5, row.actor)
                        ps.setString(6, row.actorHost)
                        ps.setString(7, row.action)
                        ps.setString(8, row.target)
                        ps.setString(9, row.detail)
                        ps.setString(10, row.status)
                        ps.setString(11, row.origin)
                        ps.setString(12, row.context)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
        return rows.last().seq
    }

    fun remoteAudit(profile: String?, source: String?, limit: Int): List<RemoteAudit> = synchronized(lock) {
        queryRemote(profile, source, "remote_profile", "audit_remote", limit) { rs ->
            RemoteAudit(
                rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12),
            )
        }
    }

    fun acceptAlarms(rows: List<RemoteAlarm>): Long {
        if (rows.isEmpty()) return 0
        synchronized(lock) {
            transaction {
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO alarm_remote(source_gateway, remote_journal, local_id, event_id, source, display_path, priority, event_type, event_flags, event_time_millis, data_json) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    for (row in rows) {
                        ps.setString(1, row.sourceGateway)
                        ps.setString(2, row.remoteJournal)
                        ps.setLong(3, row.localId)
                        ps.setString(4, row.eventId)
                        ps.setString(5, row.source)
                        ps.setString(6, row.displayPath)
                        ps.setInt(7, row.priority)
                        ps.setInt(8, row.eventType)
                        ps.setInt(9, row.eventFlags)
                        ps.setLong(10, row.eventTimeMillis)
                        ps.setString(11, row.dataJson)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
        return rows.last().localId
    }

    fun remoteAlarms(journal: String?, source: String?, limit: Int): List<RemoteAlarm> = synchronized(lock) {
        queryRemote(journal, source, "remote_journal", "alarm_remote", limit) { rs ->
            RemoteAlarm(
                rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getLong(10), rs.getString(11),
            )
        }
    }

    fun cursor(stream: String): Long = synchronized(lock) {
        conn.prepareStatement("SELECT cursor_id FROM sync_cursor WHERE stream = ?").use { ps ->
            ps.setString(1, stream)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    fun setCursor(stream: String, cursor: Long) {
        synchronized(lock) {
            transaction {
                conn.prepareStatement(
                    "INSERT INTO sync_cursor(stream, cursor_id) VALUES (?, ?) ON CONFLICT(stream) DO UPDATE SET cursor_id = excluded.cursor_id",
                ).use { ps ->
                    ps.setString(1, stream)
                    ps.setLong(2, cursor)
                    ps.executeUpdate()
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) { runCatching { conn.close() } }
    }

    private fun countHistory(): Long {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM history_local").use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    private fun deleteOldest(forwarded: Int, limit: Long): Long {
        conn.prepareStatement(
            "DELETE FROM history_local WHERE id IN (SELECT id FROM history_local WHERE forwarded = ? ORDER BY id ASC LIMIT ?)",
        ).use { ps ->
            ps.setInt(1, forwarded)
            ps.setLong(2, limit)
            return ps.executeUpdate().toLong()
        }
    }

    private fun <T> queryRemote(
        name: String?,
        source: String?,
        nameColumn: String,
        table: String,
        limit: Int,
        read: (java.sql.ResultSet) -> T,
    ): List<T> {
        val clauses = ArrayList<String>()
        val args = ArrayList<Any>()
        if (name != null) {
            clauses += "$nameColumn = ?"
            args += name
        }
        if (source != null) {
            clauses += "source_gateway = ?"
            args += source
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        val columns = if (table == "audit_remote") {
            "source_gateway, remote_profile, seq, timestamp_millis, actor, actor_host, action, target, detail, status, origin, context"
        } else {
            "source_gateway, remote_journal, local_id, event_id, source, display_path, priority, event_type, event_flags, event_time_millis, data_json"
        }
        val out = ArrayList<T>()
        conn.prepareStatement("SELECT $columns FROM $table $where ORDER BY source_gateway, 3 DESC LIMIT ?").use { ps ->
            args.forEachIndexed { index, value -> bind(ps, index + 1, value) }
            ps.setInt(args.size + 1, limit)
            ps.executeQuery().use { rs -> while (rs.next()) out += read(rs) }
        }
        return out
    }

    private fun readLocal(rs: java.sql.ResultSet) = LocalHistory(
        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
        rs.getString(5), rs.getLong(6), rs.getInt(7) != 0,
    )

    private fun bind(ps: java.sql.PreparedStatement, index: Int, value: Any) {
        when (value) {
            is String -> ps.setString(index, value)
            is Long -> ps.setLong(index, value)
            is Int -> ps.setInt(index, value)
            else -> ps.setString(index, value.toString())
        }
    }

    private fun transaction(body: () -> Unit) {
        conn.autoCommit = false
        try {
            body()
            conn.commit()
        } catch (error: Throwable) {
            runCatching { conn.rollback() }
            throw error
        } finally {
            conn.autoCommit = true
        }
    }

    private fun open(): Connection {
        file.parent?.createDirectories()
        Class.forName("org.sqlite.JDBC")
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        connection.createStatement().use { st ->
            for (sql in listOf(
                "PRAGMA journal_mode=WAL",
                "PRAGMA synchronous=NORMAL",
                "PRAGMA busy_timeout=5000",
                "CREATE TABLE IF NOT EXISTS history_local (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL, path TEXT NOT NULL, " +
                    "value_json TEXT NOT NULL, quality TEXT NOT NULL, timestamp_micros INTEGER NOT NULL, forwarded INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS history_local_pending ON history_local(forwarded, id)",
                "CREATE TABLE IF NOT EXISTS history_remote (" +
                    "source_gateway TEXT NOT NULL, remote_provider TEXT NOT NULL, local_id INTEGER NOT NULL, " +
                    "provider TEXT NOT NULL, path TEXT NOT NULL, value_json TEXT NOT NULL, quality TEXT NOT NULL, " +
                    "timestamp_micros INTEGER NOT NULL, PRIMARY KEY (source_gateway, remote_provider, local_id))",
                "CREATE TABLE IF NOT EXISTS audit_remote (" +
                    "source_gateway TEXT NOT NULL, remote_profile TEXT NOT NULL, seq INTEGER NOT NULL, " +
                    "timestamp_millis INTEGER NOT NULL, actor TEXT NOT NULL, actor_host TEXT NOT NULL, action TEXT NOT NULL, " +
                    "target TEXT NOT NULL, detail TEXT NOT NULL, status TEXT NOT NULL, origin TEXT NOT NULL, context TEXT NOT NULL, " +
                    "PRIMARY KEY (source_gateway, remote_profile, seq))",
                "CREATE TABLE IF NOT EXISTS alarm_remote (" +
                    "source_gateway TEXT NOT NULL, remote_journal TEXT NOT NULL, local_id INTEGER NOT NULL, " +
                    "event_id TEXT NOT NULL, source TEXT NOT NULL, display_path TEXT NOT NULL, priority INTEGER NOT NULL, " +
                    "event_type INTEGER NOT NULL, event_flags INTEGER NOT NULL, event_time_millis INTEGER NOT NULL, " +
                    "data_json TEXT NOT NULL, PRIMARY KEY (source_gateway, remote_journal, local_id))",
                "CREATE TABLE IF NOT EXISTS sync_cursor (stream TEXT PRIMARY KEY, cursor_id INTEGER NOT NULL)",
            )) {
                if (st.execute(sql)) st.resultSet?.close()
            }
        }
        return connection
    }
}

internal fun timestampMicros(instant: Instant): Long {
    val micros = instant.nano / 1_000
    return Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000L), micros.toLong())
}

internal fun instantOfMicros(micros: Long): Instant {
    val seconds = Math.floorDiv(micros, 1_000_000L)
    val rest = Math.floorMod(micros, 1_000_000L)
    return Instant.ofEpochSecond(seconds, rest * 1_000L)
}
