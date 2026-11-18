package org.oip.historian

import org.oip.tags.Quality
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.Calendar
import java.util.TimeZone

/** Where the samples of an existing table sit. */
sealed class TableLayout {
    /**
     * One row per sample: [tagColumn] holds the tag path (or a tag id, see [tagIds]), [valueColumn] the value.
     * [qualityColumn] is optional; without it every sample is Good.
     */
    data class Narrow(
        val tagColumn: String,
        val valueColumn: String,
        val qualityColumn: String? = null,
        /** Tag path (any provider spelling) to the id stored in [tagColumn]. Empty: the column holds tag paths. */
        val tagIds: Map<String, Any> = emptyMap(),
    ) : TableLayout()

    /** One row per time, one column per tag: [tagColumns] maps a tag path to its column. */
    data class Wide(val tagColumns: Map<String, String>) : TableLayout()
}

/** How the timestamp column is stored. */
enum class TimestampUnit(val wire: String) {
    TIMESTAMP("timestamp"), EPOCH_MILLIS("epochMillis"), EPOCH_SECONDS("epochSeconds");

    companion object {
        fun fromWire(text: String) = entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("timestampUnit must be one of ${entries.joinToString { it.wire }}")
    }
}

/**
 * HIS-008: an existing PostgreSQL table read as a history provider. Read only: nothing is created, written, or
 * partitioned, and every query runs on a read-only connection. A write routed here is refused as permanent.
 *
 * Names ([table], columns) are plain or `schema.table` identifiers; they are always quoted, so the case given
 * is the case matched. `timestamp` and `timestamptz` columns are read as UTC instants (a `timestamp` without
 * zone is taken to hold UTC); an epoch column needs [timestampUnit]. A tag path matches the narrow tag column
 * exactly, both as the full `[provider]path` form and as the bare path, case sensitive.
 *
 * Values keep their type: booleans, whole numbers (Long), other numbers (Double), anything else as text.
 * A quality column holding text is read by quality name (`Good`, `Bad_NotConnected`); a number from 0 to 255
 * follows the OPC DA convention (192 and over good, 64 and over uncertain, below that bad); a larger number is
 * an OPC UA status code. An empty quality is Good.
 */
class DbTableHistoryProvider(
    name: String,
    private val connections: JdbcConnectionSource,
    val table: String,
    val timestampColumn: String,
    val layout: TableLayout,
    val timestampUnit: TimestampUnit = TimestampUnit.TIMESTAMP,
) : ReadOnlyHistoryProvider(name, "database table") {
    private val wide: Map<String, String> = (layout as? TableLayout.Wide)?.tagColumns
        ?.mapKeys { pathKey(it.key) } ?: emptyMap()
    private val narrowIds: Map<String, Any> = (layout as? TableLayout.Narrow)?.tagIds?.mapKeys { pathKey(it.key) } ?: emptyMap()

    init {
        qualified(table)
        quoted(timestampColumn)
        when (layout) {
            is TableLayout.Narrow -> {
                quoted(layout.tagColumn); quoted(layout.valueColumn); layout.qualityColumn?.let { quoted(it) }
                require(layout.tagIds.values.all { it is Number || it is String }) { "tag ids must be numbers or text" }
            }
            is TableLayout.Wide -> {
                require(layout.tagColumns.isNotEmpty()) { "tagColumns must name at least one tag" }
                layout.tagColumns.forEach { (path, column) -> normalizeTagPath(path); quoted(column) }
                require(wide.size == layout.tagColumns.size) { "a tag path appears twice in tagColumns" }
            }
        }
    }

    override fun query(query: HistoryQuery): List<HistorySample> {
        val out = ArrayList<HistorySample>()
        run(query.tagPath, query.fromMillis, query.toMillis, query.limit, false) { out += it; true }
        return out
    }

    override fun scanUntil(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Boolean) {
        run(tagPath, fromMillis, toMillis, null, false, each)
    }

    override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? {
        var found: HistorySample? = null
        // Strictly before: the range ends one millisecond earlier.
        if (beforeMillis == Long.MIN_VALUE) return null
        run(tagPath, Long.MIN_VALUE, beforeMillis - 1, 1, true) { found = it; false }
        return found
    }

    /** Tag paths a wide layout or a tag-id map names. Empty when the table's tag column is open-ended. */
    fun mappedTags(): List<String> = when (layout) {
        is TableLayout.Wide -> layout.tagColumns.keys.map { normalizeTagPath(it) }
        is TableLayout.Narrow -> layout.tagIds.keys.map { normalizeTagPath(it) }
    }

    override fun status(): Map<String, Any?> = linkedMapOf(
        "type" to "table",
        "table" to table,
        "layout" to if (layout is TableLayout.Wide) "wide" else "narrow",
        "readOnly" to true,
    )

    override fun close() = connections.close()

    private fun run(tagPath: String, from: Long, to: Long, limit: Int?, newestFirst: Boolean, each: (HistorySample) -> Boolean) {
        val normalized = runCatching { normalizeTagPath(tagPath) }.getOrDefault(tagPath)
        val key = pathKey(tagPath)
        val tsCol = quoted(timestampColumn)
        val order = if (newestFirst) "DESC" else "ASC"
        val binds = ArrayList<Any>()
        val select: String
        val where = StringBuilder()
        when (layout) {
            is TableLayout.Narrow -> {
                select = "SELECT $tsCol, ${quoted(layout.valueColumn)}" + (layout.qualityColumn?.let { ", ${quoted(it)}" } ?: "")
                if (layout.tagIds.isEmpty()) {
                    val bare = normalized.substringAfter(']')
                    where.append("${quoted(layout.tagColumn)} IN (?, ?)")
                    binds += normalized; binds += bare
                } else {
                    val id = narrowIds[key] ?: return
                    where.append("${quoted(layout.tagColumn)} = ?")
                    binds += id
                }
            }
            is TableLayout.Wide -> {
                val column = wide[key] ?: return
                select = "SELECT $tsCol, ${quoted(column)}"
                where.append("${quoted(column)} IS NOT NULL")
            }
        }
        val sql = "$select FROM ${qualified(table)} WHERE $where AND $tsCol >= ? AND $tsCol <= ? ORDER BY $tsCol $order" +
            (limit?.let { " LIMIT $it" } ?: "")
        val c = try {
            connections.borrow()
        } catch (e: Exception) {
            throw HistoryReadException("history provider '$name': cannot connect: ${e.message}", e)
        }
        var broken = false
        val wasReadOnly = runCatching { c.isReadOnly }.getOrDefault(false)
        val wasAutoCommit = runCatching { c.autoCommit }.getOrDefault(true)
        try {
            // PostgreSQL enforces read-only only inside a transaction, so read in one and never commit it.
            c.autoCommit = false
            c.isReadOnly = true
            c.prepareStatement(sql).use { st ->
                var i = 1
                for (b in binds) { if (b is Number) st.setLong(i++, b.toLong()) else st.setString(i++, b.toString()) }
                bindTime(st, i++, maxOf(from, MIN_MILLIS))
                bindTime(st, i, minOf(to, MAX_MILLIS))
                st.fetchSize = FETCH
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        if (!each(sample(normalized, rs))) break
                    }
                }
            }
        } catch (e: SQLException) {
            val state = e.sqlState ?: ""
            broken = !state.startsWith("42") && !state.startsWith("22")
            throw HistoryReadException("history provider '$name': ${e.sqlState ?: "no SQLSTATE"}: ${e.message}", e)
        } finally {
            runCatching { c.rollback() }.onFailure { broken = true }
            runCatching { c.isReadOnly = wasReadOnly; c.autoCommit = wasAutoCommit }.onFailure { broken = true }
            connections.release(c, broken)
        }
    }

    private fun bindTime(st: PreparedStatement, index: Int, millis: Long) = when (timestampUnit) {
        TimestampUnit.TIMESTAMP -> st.setTimestamp(index, Timestamp(millis), Calendar.getInstance(UTC))
        TimestampUnit.EPOCH_MILLIS -> st.setLong(index, millis)
        TimestampUnit.EPOCH_SECONDS -> st.setLong(index, Math.floorDiv(millis, 1000L))
    }

    private fun sample(path: String, rs: ResultSet): HistorySample {
        val millis = when (timestampUnit) {
            TimestampUnit.TIMESTAMP -> (rs.getTimestamp(1, Calendar.getInstance(UTC)) ?: throw HistoryReadException("history provider '$name': a row has no timestamp")).time
            TimestampUnit.EPOCH_MILLIS -> rs.getLong(1)
            TimestampUnit.EPOCH_SECONDS -> Math.multiplyExact(rs.getLong(1), 1000L)
        }
        val quality = if (layout is TableLayout.Narrow && layout.qualityColumn != null) quality(rs.getObject(3)) else Quality.GOOD
        return HistorySample(path, millis, value(rs.getObject(2)), quality)
    }

    private fun value(raw: Any?): Any? = when (raw) {
        null -> null
        is Boolean -> raw
        is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
        is Float, is Double -> (raw as Number).toDouble()
        is BigDecimal -> if (raw.scale() <= 0 && raw.precision() <= 18) raw.toLong() else raw.toDouble()
        else -> raw.toString()
    }

    private fun quality(raw: Any?): Quality = when (raw) {
        null -> Quality.GOOD
        is Number -> {
            val code = raw.toLong()
            when {
                code in 0..255 -> if (code >= 192) Quality.GOOD else if (code >= 64) Quality.UNCERTAIN else Quality.BAD
                else -> Quality.fromOpcUa(code.toInt())
            }
        }
        else -> raw.toString().trim().let { text -> if (text.isEmpty()) Quality.GOOD else Quality.fromName(text) ?: Quality.BAD }
    }

    companion object {
        private val UTC = TimeZone.getTimeZone("UTC")
        private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_$]{0,62}$")
        private const val FETCH = 5000
        // PostgreSQL timestamps run from 4713 BC to 294276 AD; stay well inside so a wide query range binds.
        private const val MIN_MILLIS = -62_135_596_800_000L
        private const val MAX_MILLIS = 253_402_300_799_000L

        private fun pathKey(path: String): String = runCatching { normalizeTagPath(path) }.getOrDefault(path)

        /** A double-quoted identifier; anything but letters, digits, `_` and `$` is refused. */
        internal fun quoted(identifier: String): String {
            require(IDENT.matches(identifier)) { "'$identifier' is not a plain identifier (letters, digits, '_', '$')" }
            return "\"$identifier\""
        }

        /** `table` or `schema.table`, each part quoted. */
        internal fun qualified(name: String): String = name.split('.').also {
            require(it.size in 1..2) { "table must be a name or schema.name" }
        }.joinToString(".") { quoted(it) }
    }
}
