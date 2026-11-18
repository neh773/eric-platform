package org.oip.backup

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories

/**
 * Embedded runtime database (ADR 0008): one SQLite file, WAL, a single `kv` table.
 * Backup identity is [canonicalDump], a data format that is parsed and rebound — it is never executed as SQL.
 *
 * (the reference platform includes the internal DB in a gateway backup. Our dump is the runtime half of that backup.)
 */
class RuntimeDatabase(val file: Path) : AutoCloseable {
    private val lock = Any()
    private var conn: Connection = open()

    init {
        file.parent?.createDirectories()
    }

    fun put(key: String, value: String) = synchronized(lock) {
        conn.prepareStatement("INSERT INTO kv(k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").use { ps ->
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate()
        }
    }

    fun get(key: String): String? = synchronized(lock) {
        conn.prepareStatement("SELECT v FROM kv WHERE k = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun entries(): List<Pair<String, String>> = synchronized(lock) { readAll() }

    fun clear() = replaceAll(emptyList())

    /** Replace the table contents in one transaction. */
    fun replaceAll(rows: List<Pair<String, String>>) = synchronized(lock) {
        val seen = hashSetOf<String>()
        for ((k, _) in rows) require(seen.add(k)) { "duplicate runtime key" }
        conn.autoCommit = false
        try {
            conn.createStatement().use { it.execute("DELETE FROM kv") }
            conn.prepareStatement("INSERT INTO kv(k, v) VALUES (?, ?)").use { ps ->
                for ((k, v) in rows) { ps.setString(1, k); ps.setString(2, v); ps.addBatch() }
                ps.executeBatch()
            }
            conn.commit()
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun canonicalDump(): String = synchronized(lock) { formatDump(readAll()) }

    fun restoreDump(text: String) = replaceAll(parseDump(text))

    override fun close() {
        synchronized(lock) { runCatching { conn.close() } }
    }

    private fun open(): Connection {
        file.parent?.createDirectories()
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        c.createStatement().use { st ->
            for (sql in listOf("PRAGMA journal_mode=WAL", "PRAGMA synchronous=NORMAL", "PRAGMA busy_timeout=5000")) {
                if (st.execute(sql)) st.resultSet?.close()
            }
            st.execute("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        }
        return c
    }

    private fun readAll(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT k, v FROM kv ORDER BY k").use { rs ->
                while (rs.next()) out += rs.getString(1) to rs.getString(2)
            }
        }
        return out
    }

    companion object {
        const val HEADER = "OIP_RUNTIME_V1"

        fun formatDump(rows: List<Pair<String, String>>): String = buildString {
            append(HEADER).append('\n')
            append(rows.size).append('\n')
            for ((k, v) in rows) append(escape(k)).append('\t').append(escape(v)).append('\n')
        }

        fun parseDump(text: String): List<Pair<String, String>> {
            val lines = text.split('\n').let { if (it.lastOrNull().isNullOrEmpty() && it.size > 1) it.dropLast(1) else it }
            if (lines.size < 2 || lines[0] != HEADER) throw BackupException("runtime dump header missing")
            val n = lines[1].toIntOrNull() ?: throw BackupException("runtime dump count is not an integer")
            val rows = lines.drop(2)
            if (rows.size != n) throw BackupException("runtime dump has ${rows.size} rows, header says $n")
            val seen = hashSetOf<String>()
            return rows.map { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) throw BackupException("runtime dump row has no separator")
                val k = unescape(line.substring(0, tab))
                val v = unescape(line.substring(tab + 1))
                if (!seen.add(k)) throw BackupException("duplicate runtime key in dump")
                k to v
            }
        }

        private fun escape(s: String) = buildString(s.length) {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }

        private fun unescape(s: String): String = buildString(s.length) {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '\\' -> append('\\')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        else -> throw BackupException("bad escape in runtime dump")
                    }
                    i += 2
                } else {
                    append(c); i++
                }
            }
        }
    }
}
