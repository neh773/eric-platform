package org.oip.historian

import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import java.io.ByteArrayInputStream
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

/**
 * A history provider reached through store-and-forward ([HistoryRouter.forward]): rows are written from the
 * forward buffer, never from the collector, and reads go straight to it.
 */
interface RemoteHistoryProvider : HistoryReader, AutoCloseable {
    val name: String

    /**
     * Store [records] in order, atomically. A record already stored under the same (sourceId, seq) is skipped,
     * so a batch resent after a lost acknowledgement is stored once.
     * Throws [HistoryWriteException]: `permanent` when the provider rejects the data itself (it would fail
     * again unchanged), otherwise the provider is unreachable and the batch should be retried later.
     */
    fun write(records: List<ForwardRecord>)

    /** Provider-specific status for diagnostics (no secrets). */
    fun status(): Map<String, Any?> = emptyMap()

    override fun close() {}
}

class HistoryWriteException(message: String, val permanent: Boolean, cause: Throwable? = null) : Exception(message, cause)

/** A read against a provider failed (for example the database is unreachable). */
class HistoryReadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Where a [SqlHistoryProvider] gets JDBC connections. In the gateway this is a pooled database connection
 * (`db/Connection` resource, the reference platform's "Data Source"); [release] returns it. [broken] says the connection
 * failed at the transport level and should not be reused.
 */
interface JdbcConnectionSource : AutoCloseable {
    fun borrow(): Connection
    fun release(connection: Connection, broken: Boolean)

    /**
     * True when two overlapping [borrow] calls return different connections. The SQL writer then copies
     * different tags of one batch at the same time. A source that hands out one connection stays single-threaded.
     */
    fun supportsConcurrentBorrow(): Boolean = false

    override fun close() {}

    companion object {
        /** A single cached [DriverManager] connection, reopened after a transport failure. */
        fun driverManager(url: String, user: String? = null, password: String? = null, properties: Map<String, String> = emptyMap()) =
            object : JdbcConnectionSource {
                private var cached: Connection? = null

                @Synchronized
                override fun borrow(): Connection {
                    cached?.let { if (!it.isClosed) return it }
                    val props = Properties()
                    user?.let { props["user"] = it }
                    password?.let { props["password"] = it }
                    properties.forEach { (k, v) -> props[k] = v }
                    return DriverManager.getConnection(url, props).also { cached = it }
                }

                @Synchronized
                override fun release(connection: Connection, broken: Boolean) {
                    if (broken) {
                        runCatching { connection.close() }
                        if (cached === connection) cached = null
                    }
                }

                @Synchronized
                override fun close() {
                    cached?.let { runCatching { it.close() } }
                    cached = null
                }
            }
    }
}

/** Use a TimescaleDB hypertable: when the extension is installed ([AUTO]), never ([OFF]), or fail without it ([REQUIRED]). */
enum class TimescaleMode(val wire: String) {
    AUTO("auto"), OFF("off"), REQUIRED("required");

    companion object {
        fun fromWire(text: String) = entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("timescale must be one of ${entries.joinToString { it.wire }}")
    }
}

/**
 * SQL history provider over JDBC, our counterpart of the reference platform 8.3's SQL
 * Historian: tag history kept in an external database, chosen by a "Data Source" setting that names the
 * database connection, and always written through store-and-forward
 * (-
 * -). The table layout is ours
 * (the reference platform's `sqlth_*` tables are not reproduced; DIFFERENCES: no wire/format compatibility).
 *
 * One table [table]: `ts` (`timestamptz` on PostgreSQL, epoch milliseconds as `bigint` on SQLite and other
 * dialects), tag path, typed value (`value_kind` + `value_text`, plus `value_num`
 * for numbers and booleans so SQL users can aggregate), quality, and (`source_id`, `seq`) with a unique index
 * so inserts are idempotent (`ON CONFLICT DO NOTHING`). On PostgreSQL, when the `timescaledb` extension is
 * installed and [timescale] is not OFF, the table becomes a hypertable on `ts` ([hypertable] tells which).
 * The extension check and `create_hypertable` run only on PostgreSQL; they are skipped on SQLite and other dialects.
 *
 * A PostgreSQL connection writes with COPY (https://www.postgresql.org/docs/current/sql-copy.html). COPY
 * rejects a batch that is already stored, and that batch is written again with `INSERT … ON CONFLICT DO NOTHING`,
 * so a resend after a lost acknowledgement lands once. Any other connection uses the batched INSERT. A COPY
 * stays uncommitted until it finishes, so a connection that dies mid-COPY leaves those rows unstored.
 *
 * When the connection source can lend two connections at once, a batch of at least [PARALLEL_AT] rows is split
 * by tag across two COPY transactions. Each tag stays on one connection, in buffer order. [write] returns only
 * after both have committed, or throws without the caller treating the batch as stored.
 *
 * Errors: SQLSTATE classes 22 (data exception), 23 (integrity constraint), and 42 (syntax or access rule)
 * reject the data itself and are permanent, so the forwarder quarantines the record. A unique violation on
 * COPY is the resent-batch case above, not a quarantine. Everything else
 * (connection failures 08, shutdown 57, resources 53, a missing table 42P01 which is recreated on the next
 * write, insufficient privilege 42501 which a grant fixes without touching the data (review-3 F8), or no
 * SQLSTATE at all) is transient: the record stays queued.
 */
class SqlHistoryProvider(
    override val name: String,
    private val connections: JdbcConnectionSource,
    val table: String = DEFAULT_TABLE,
    val timescale: TimescaleMode = TimescaleMode.AUTO,
) : RemoteHistoryProvider {
    init {
        require(TABLE_NAME.matches(table)) { "table must match ${TABLE_NAME.pattern}" }
    }

    @Volatile private var schemaReady = false
    /** True after [ensureSchema] when the connection is PostgreSQL (Timescale, COPY, timestamptz). */
    @Volatile private var postgres = false
    @Volatile var hypertable: Boolean = false
        private set

    override fun write(records: List<ForwardRecord>) {
        if (records.isEmpty()) return
        val parts = if (records.size >= PARALLEL_AT && connections.supportsConcurrentBorrow()) splitByTag(records) else listOf(records)
        if (parts.size == 1) {
            writeOne(records)
            return
        }
        if (!schemaReady) withConnection(write = true) { }
        val errors = ArrayList<Throwable>(2)
        val threads = parts.map { part ->
            kotlin.concurrent.thread(isDaemon = true, name = "history-copy") {
                try {
                    writeOne(part)
                } catch (t: Throwable) {
                    synchronized(errors) { errors += t }
                }
            }
        }
        threads.forEach { it.join() }
        if (errors.isEmpty()) return
        val primary = errors.firstOrNull { it is HistoryWriteException && it.permanent } ?: errors[0]
        errors.filter { it !== primary }.forEach { primary.addSuppressed(it) }
        throw primary
    }

    /** One connection, one transaction. A tag's rows stay in the order they are given. */
    private fun writeOne(records: List<ForwardRecord>) {
        if (records.isEmpty()) return
        withConnection(write = true) { c ->
            c.autoCommit = false
            try {
                if (!copy(c, records)) insertBatched(c, records)
                c.commit()
            } catch (e: Exception) {
                runCatching { c.rollback() }
                throw e
            } finally {
                runCatching { c.autoCommit = true }
            }
        }
    }

    /** Two lanes, preserving input order inside each. One lane when every row is the same tag. */
    private fun splitByTag(records: List<ForwardRecord>): List<List<ForwardRecord>> {
        val even = ArrayList<ForwardRecord>(records.size / 2 + 1)
        val odd = ArrayList<ForwardRecord>(records.size / 2 + 1)
        for (r in records) {
            if ((r.sample.tagPath.lowercase().hashCode() and 1) == 0) even += r else odd += r
        }
        if (even.isEmpty() || odd.isEmpty()) return listOf(records)
        return listOf(even, odd)
    }

    /**
     * COPY [records] into [table]. Returns false when this connection cannot COPY, or when the rows are already
     * stored (unique violation): the transaction is rolled back so the caller can insert them idempotently.
     * Any other failure is thrown and the caller rolls the batch back, so a reset mid-COPY stores nothing.
     */
    private fun copy(c: Connection, records: List<ForwardRecord>): Boolean {
        val mgr = copyManager(c) ?: return false
        return try {
            val copied = mgr.copyIn(
                "COPY $table ($INSERT_COLUMNS) FROM STDIN WITH (FORMAT text)",
                ByteArrayInputStream(copyPayload(records)),
            )
            if (copied != records.size.toLong()) {
                throw SQLException("COPY stored $copied of ${records.size}", "08000")
            }
            true
        } catch (e: SQLException) {
            val state = sqlState(e)
            if (state != null && state.startsWith("23")) {
                runCatching { c.rollback() }.onFailure { e.addSuppressed(it) }
                false
            } else {
                throw e
            }
        } catch (e: IOException) {
            throw SQLException("copy failed: ${e.message}", "08006", e)
        }
    }

    /** Batched INSERT used when COPY is unavailable, and for a batch COPY refused because it was already stored. */
    private fun insertBatched(c: Connection, records: List<ForwardRecord>) {
        c.prepareStatement(
            "INSERT INTO $table ($INSERT_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"
        ).use { ps ->
            for (r in records) {
                val s = r.sample
                bindTimestamp(ps, 1, s.timestampMillis)
                ps.setString(2, s.tagPath)
                val (kind, text) = ValueCodec.encode(s.value)
                ps.setString(3, kind)
                if (text == null) ps.setNull(4, Types.VARCHAR) else ps.setString(4, text)
                val num = ValueCodec.numeric(s.value)
                if (num == null) ps.setNull(5, Types.DOUBLE) else ps.setDouble(5, num)
                ps.setString(6, s.quality.code.label)
                val diag = s.quality.diagnostic
                if (diag == null) ps.setNull(7, Types.VARCHAR) else ps.setString(7, diag)
                ps.setString(8, r.sourceId)
                ps.setLong(9, r.seq)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    override fun query(query: HistoryQuery): List<HistorySample> = read { c ->
        c.prepareStatement(
            "SELECT $COLUMNS FROM $table WHERE lower(tag_path) = lower(?) AND ts >= ? AND ts <= ? ORDER BY ts, source_id, seq LIMIT ?"
        ).use { ps ->
            ps.setString(1, query.tagPath)
            bindTimestamp(ps, 2, query.fromMillis)
            bindTimestamp(ps, 3, query.toMillis)
            ps.setInt(4, query.limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<HistorySample>()
                while (rs.next()) out += readRow(rs)
                out
            }
        }
    }

    override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) = read { c ->
        // A cursor (fetch size inside a transaction) streams the range instead of loading it whole.
        c.autoCommit = false
        try {
            c.prepareStatement(
                "SELECT $COLUMNS FROM $table WHERE lower(tag_path) = lower(?) AND ts >= ? AND ts <= ? ORDER BY ts, source_id, seq"
            ).use { ps ->
                ps.fetchSize = 5_000
                ps.setString(1, tagPath)
                bindTimestamp(ps, 2, fromMillis)
                bindTimestamp(ps, 3, toMillis)
                ps.executeQuery().use { rs -> while (rs.next()) each(readRow(rs)) }
            }
            c.commit()
        } catch (e: Exception) {
            runCatching { c.rollback() }
            throw e
        } finally {
            runCatching { c.autoCommit = true }
        }
    }

    override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? = read { c ->
        c.prepareStatement(
            "SELECT $COLUMNS FROM $table WHERE lower(tag_path) = lower(?) AND ts < ? ORDER BY ts DESC, source_id DESC, seq DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, tagPath)
            bindTimestamp(ps, 2, beforeMillis)
            ps.executeQuery().use { rs -> if (rs.next()) readRow(rs) else null }
        }
    }

    override fun status(): Map<String, Any?> = linkedMapOf(
        "type" to "sql", "table" to table, "timescale" to timescale.wire, "hypertable" to hypertable, "schemaReady" to schemaReady,
    )

    override fun close() = connections.close()

    private fun <T> read(body: (Connection) -> T): T = try {
        withConnection(write = false, body)
    } catch (e: HistoryWriteException) {
        throw HistoryReadException("history provider '$name': ${e.message}", e)
    }

    /** Borrow a connection, make sure the schema exists, run [body], and classify failures. */
    private fun <T> withConnection(write: Boolean, body: (Connection) -> T): T {
        val c = try {
            connections.borrow()
        } catch (e: Exception) {
            throw HistoryWriteException("cannot connect: ${e.message}", permanent = false, e)
        }
        var broken = false
        try {
            if (!schemaReady) {
                // A schema failure is about the database, not the records: never quarantine for it.
                try {
                    ensureSchema(c)
                } catch (e: SQLException) {
                    broken = !(sqlState(e) ?: "").startsWith("42")
                    throw HistoryWriteException("cannot prepare table $table: ${sqlState(e) ?: "no SQLSTATE"}: ${e.message}", permanent = false, e)
                }
            }
            return body(c)
        } catch (e: SQLException) {
            val state = sqlState(e)
            val permanent = write && isPermanent(state)
            if (state == UNDEFINED_TABLE || state == INVALID_SCHEMA) schemaReady = false
            broken = !isPermanent(state) && state != UNDEFINED_TABLE
            throw HistoryWriteException("${state ?: "no SQLSTATE"}: ${e.message}", permanent, e)
        } catch (e: DateTimeException) {
            throw HistoryWriteException("timestamp out of range: ${e.message}", permanent = write, e)
        } catch (e: HistoryWriteException) {
            throw e
        } catch (e: RuntimeException) {
            broken = true
            throw HistoryWriteException("${e.javaClass.simpleName}: ${e.message}", permanent = false, e)
        } catch (e: AssertionError) {
            // With -ea, pgjdbc asserts that a connection reset mid-batch cannot happen; it is a transport failure.
            broken = true
            throw HistoryWriteException("connection failed: ${e.message}", permanent = false, e)
        } finally {
            connections.release(c, broken)
        }
    }

    private fun ensureSchema(c: Connection) {
        postgres = isPostgreSQL(c)
        c.createStatement().use { st ->
            val tsType = if (postgres) "TIMESTAMPTZ" else "BIGINT"
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS $table (
                  ts $tsType NOT NULL,
                  tag_path TEXT NOT NULL,
                  value_kind TEXT NOT NULL,
                  value_text TEXT,
                  value_num DOUBLE PRECISION,
                  quality TEXT NOT NULL,
                  quality_diagnostic TEXT,
                  source_id TEXT NOT NULL,
                  seq BIGINT NOT NULL
                )
                """.trimIndent()
            )
            // pg_extension and create_hypertable exist only on PostgreSQL.
            val hasExtension = if (postgres) {
                st.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'timescaledb'").use { it.next() }
            } else {
                false
            }
            if (timescale == TimescaleMode.REQUIRED && !hasExtension) {
                throw HistoryWriteException("timescale is required but the timescaledb extension is not installed", permanent = false)
            }
            hypertable = if (hasExtension && timescale != TimescaleMode.OFF) {
                // The unique key below includes ts, as a hypertable requires.
                c.prepareStatement("SELECT create_hypertable(?::regclass, 'ts', if_not_exists => TRUE, migrate_data => TRUE)").use { ps ->
                    ps.setString(1, table)
                    ps.executeQuery().close()
                }
                true
            } else {
                false
            }
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ${table}_source_seq ON $table (source_id, seq, ts)")
            st.execute("CREATE INDEX IF NOT EXISTS ${table}_tag_ts ON $table (lower(tag_path), ts)")
        }
        schemaReady = true
    }

    private fun bindTimestamp(ps: java.sql.PreparedStatement, index: Int, millis: Long) {
        if (postgres) ps.setObject(index, clampTimestamp(millis)) else ps.setLong(index, millis)
    }

    private fun readRow(rs: java.sql.ResultSet) = HistorySample(
        tagPath = rs.getString("tag_path"),
        timestampMillis = if (postgres) {
            rs.getObject("ts", OffsetDateTime::class.java).toInstant().toEpochMilli()
        } else {
            rs.getLong("ts")
        },
        value = ValueCodec.decode(rs.getString("value_kind"), rs.getString("value_text")),
        quality = ValueCodec.quality(rs.getString("quality"), rs.getString("quality_diagnostic")),
    )

    companion object {
        /** Batches at least this large and a pool that can lend two connections are copied on two lanes. */
        const val PARALLEL_AT = 2_000
        const val DEFAULT_TABLE = "oip_history"
        val TABLE_NAME = Regex("^[a-z_][a-z0-9_]{0,47}$")
        private const val INSERT_COLUMNS =
            "ts, tag_path, value_kind, value_text, value_num, quality, quality_diagnostic, source_id, seq"
        private const val COLUMNS = "tag_path, ts, value_kind, value_text, quality, quality_diagnostic"
        private val COPY_TS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSXXX").withZone(ZoneOffset.UTC)

        private fun copyManager(c: Connection): CopyManager? = runCatching {
            if (c.isWrapperFor(BaseConnection::class.java)) CopyManager(c.unwrap(BaseConnection::class.java)) else null
        }.getOrNull()

        private fun isPostgreSQL(c: Connection): Boolean =
            runCatching { c.metaData.databaseProductName }.getOrNull()
                ?.contains("postgres", ignoreCase = true) == true

        /** PostgreSQL text COPY. Null is `\N`; backslash, tab, and newlines are escaped. */
        private fun copyPayload(records: List<ForwardRecord>): ByteArray {
            val sb = StringBuilder(records.size * 96)
            for (r in records) {
                val s = r.sample
                sb.append(COPY_TS.format(Instant.ofEpochMilli(s.timestampMillis))).append('\t')
                appendCopyText(sb, s.tagPath).append('\t')
                val (kind, text) = ValueCodec.encode(s.value)
                appendCopyText(sb, kind).append('\t')
                appendCopyText(sb, text).append('\t')
                val num = ValueCodec.numeric(s.value)
                if (num == null) sb.append("\\N") else sb.append(num)
                sb.append('\t')
                appendCopyText(sb, s.quality.code.label).append('\t')
                appendCopyText(sb, s.quality.diagnostic).append('\t')
                appendCopyText(sb, r.sourceId).append('\t')
                sb.append(r.seq).append('\n')
            }
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        private fun appendCopyText(sb: StringBuilder, text: String?): StringBuilder {
            if (text == null) return sb.append("\\N")
            for (ch in text) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '\t' -> sb.append("\\t")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    else -> sb.append(ch)
                }
            }
            return sb
        }
        private const val UNDEFINED_TABLE = "42P01"
        private const val INSUFFICIENT_PRIVILEGE = "42501"
        private const val INVALID_SCHEMA = "3F000"

        /** SQLSTATE classes that reject the data itself; see the class KDoc. */
        fun isPermanent(state: String?): Boolean {
            if (state == null || state.length < 2 || state == UNDEFINED_TABLE || state == INSUFFICIENT_PRIVILEGE) return false
            return state.substring(0, 2) in setOf("22", "23", "42")
        }

        /** First SQLSTATE in the exception chain (a batch failure carries it on the next exception). */
        fun sqlState(e: SQLException): String? {
            var cur: Throwable? = e
            val seen = HashSet<Throwable>()
            while (cur != null && seen.add(cur)) {
                if (cur is SQLException) {
                    cur.sqlState?.let { return it }
                    val next = cur.nextException
                    if (next != null) {
                        cur = next
                        continue
                    }
                }
                cur = cur.cause
            }
            return null
        }

        // PostgreSQL timestamptz spans 4713 BC .. 294276 AD; values outside are rejected (SQLSTATE 22008).
        private fun toTimestamp(millis: Long): OffsetDateTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

        private val MIN_QUERY = Instant.parse("-4712-01-01T00:00:00Z").toEpochMilli()
        private val MAX_QUERY = Instant.parse("+294000-01-01T00:00:00Z").toEpochMilli()

        /** Query bounds are clamped into the database range, so `from=0&to=Long.MAX_VALUE` works. */
        private fun clampTimestamp(millis: Long) = toTimestamp(millis.coerceIn(MIN_QUERY, MAX_QUERY))

    }
}
