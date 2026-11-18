package org.oip.reporting

import org.oip.db.QueryFailed
import org.oip.db.QueryForbidden
import org.oip.db.QueryNotFound
import org.oip.db.QueryRejected
import org.oip.db.QueryResult
import org.oip.db.QueryUnavailable
import org.oip.historian.HistoryAggregate
import org.oip.historian.HistoryAggregator
import org.oip.historian.HistoryQuery
import org.oip.historian.HistoryReadException
import org.oip.historian.HistoryReader
import org.oip.historian.HistoryWriteException
import org.oip.historian.normalizeTagPath
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Who runs a report. [system] is a scheduled run: it skips named-query role checks and per-tag read checks,
 * like `TagCaller.System` on the tag-permissions branch. [permissions] are the caller's effective gateway
 * permissions; [roles] are checked against named-query role lists. [zone] is the security zone the request
 * matched, for per-tag read permissions that name zones (null: the default zone).
 */
data class ReportCaller(
    val name: String,
    val roles: Set<String>,
    val permissions: Set<String>,
    val system: Boolean = false,
    val zone: String? = null,
) {
    companion object {
        val SYSTEM = ReportCaller("system", emptySet(), emptySet(), system = true)
    }
}

/** Per-tag read check, applied to every tag of a tagHistory source before any history is read. */
fun interface TagReadCheck {
    /** [tagPath] is provider-qualified (`[default]Line1/Temp`). */
    fun canRead(caller: ReportCaller, tagPath: String): Boolean
}

/** Named queries as the report engine needs them (adapter: [DatabaseQueries]). */
interface NamedQueries {
    /** `query`, `scalar`, or `update`; throws [QueryNotFound]. */
    fun queryType(name: String): String
    fun execute(name: String, parameters: Map<String, Any?>, caller: ReportCaller): QueryResult

    /** One read-only prepared statement on a named connection. Writes are refused. */
    fun readSql(connection: String, sql: String, parameters: Map<String, Any?>): QueryResult
}

/** History for one tag (adapter: [RouterHistoryAccess]). Throws [HistoryReadException] when it cannot be read. */
fun interface HistoryAccess {
    fun reader(tagPath: String): HistoryReader
}

/** One alarm journal row as a report shows it (FEATURES REP-032). Times are epoch milliseconds. */
data class AlarmJournalRow(
    val eventTime: Long,
    val source: String,
    val displayPath: String,
    val priority: String,
    val eventType: String,
    val actor: String?,
    val eventId: String,
)

class AlarmJournalRequest(
    val from: Long,
    val to: Long,
    val filter: AlarmJournalFilter,
    val ascending: Boolean,
    val limit: Int,
)

/** [rows] hold at most the request's limit; [total] counts every admitted row in the range. */
class AlarmJournalResult(val rows: List<AlarmJournalRow>, val total: Long)

/** The alarm journal as the report engine needs it (adapter: the gateway's alarm module). */
interface AlarmJournalAccess {
    /** The provider-qualified tag path behind an alarm source, or null when the source is not tied to a tag. */
    fun tagPath(source: String): String?

    /** Rows in the half-open range, filtered by [AlarmJournalRequest.filter]; only sources [sourceAllowed] admits. */
    fun query(request: AlarmJournalRequest, sourceAllowed: (String) -> Boolean): AlarmJournalResult
}

/** A run that failed. [status] is the HTTP status the REST layer returns; the run is still recorded. */
class ReportRunException(val status: Int, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Runs one [ReportDefinition] into a [ReportResult] (contract section 2). Data sources run in definition order.
 * Any data source failure fails the run ([ReportRunException]); excluded tags and truncation make it `partial`.
 */
class ReportEngine(
    private val queries: NamedQueries?,
    private val history: HistoryAccess?,
    private val tagCheck: TagReadCheck,
    private val clock: Clock = Clock.systemUTC(),
    private val alarms: AlarmJournalAccess? = null,
) {
    fun run(
        def: ReportDefinition,
        supplied: Map<String, Any?>,
        caller: ReportCaller,
        trigger: RunTrigger,
        runId: String = UUID.randomUUID().toString(),
    ): ReportResult {
        val started = clock.millis()
        val resolved = Parameters.resolve(def.parameters, supplied, started)
        val values = resolved.associate { it.name to it.value }
        val warnings = ArrayList<String>()
        var partial = false
        val datasets = ArrayList<Dataset>()
        for (source in def.dataSources) {
            val ds = when (source) {
                is DataSourceDef.NamedQuery -> namedQuery(source, values, caller, datasets)
                is DataSourceDef.Sql -> sqlQuery(source, values, datasets)
                is DataSourceDef.TagHistory -> tagHistory(source, values, caller, started)
                is DataSourceDef.AlarmJournal -> alarmJournal(source, values, caller, started)
            }
            if (ds.excludedTags.isNotEmpty()) {
                partial = true
                warnings += "${ds.name}: ${ds.excludedTags.size} tag(s) excluded (no read permission)"
            }
            if (ds.truncated) {
                partial = true
                warnings += "${ds.name}: truncated"
            }
            datasets += ds
        }
        val generated = clock.millis()
        return ReportResult(
            report = ReportRef("$REPORTING_MODULE/gateway/$REPORT_KIND/${def.name}", def.name, def.title, def.version),
            parameters = resolved,
            generatedAt = generated,
            datasets = datasets,
            run = RunInfo(runId, trigger, caller.name, started, generated - started, if (partial) RunStatus.PARTIAL else RunStatus.SUCCESS, warnings),
            layout = def.layout,
        )
    }

    private fun bound(b: Binding, values: Map<String, Any?>): Any? = when (b) {
        is Binding.Literal -> b.value
        is Binding.Param -> {
            val v = values[b.name]
            if (b.field == null) v else (v as? Map<*, *>)?.get(b.field)
        }
    }

    /** Rows as a query returned them, before column types are decided. */
    private class Raw(val names: List<String>, val rows: List<List<Any?>>, val truncated: Boolean)

    private fun namedRaw(source: DataSourceDef.NamedQuery, params: Map<String, Any?>, caller: ReportCaller): Raw {
        val q = queries ?: throw ReportRunException(503, "named queries are not available")
        val result = try {
            // An update query would change data; a report never runs one.
            if (q.queryType(source.query) == "update") {
                throw ReportRunException(422, "data source ${source.name}: named query '${source.query}' is an update query")
            }
            q.execute(source.query, params, caller)
        } catch (e: QueryNotFound) {
            throw ReportRunException(422, "data source ${source.name}: ${e.message}", e)
        } catch (e: QueryRejected) {
            throw ReportRunException(422, "data source ${source.name}: ${e.message}", e)
        } catch (e: QueryForbidden) {
            throw ReportRunException(403, "data source ${source.name}: ${e.message}", e)
        } catch (e: QueryUnavailable) {
            throw ReportRunException(503, "data source ${source.name}: ${e.message}", e)
        } catch (e: QueryFailed) {
            throw ReportRunException(502, "data source ${source.name}: ${e.message}", e)
        }
        return when (result) {
            is QueryResult.Rows -> Raw(result.columns, result.rows, result.truncated)
            is QueryResult.Scalar -> Raw(listOf("value"), listOf(listOf(result.value)), false)
            is QueryResult.Update -> throw ReportRunException(422, "data source ${source.name}: update queries are not allowed")
            is QueryResult.Procedure -> result.table().let { (columns, rows) -> Raw(columns, rows, result.truncated) }
        }
    }

    private fun sqlRaw(source: DataSourceDef.Sql, params: Map<String, Any?>): Raw {
        val q = queries ?: throw ReportRunException(503, "database connections are not available")
        val result = try {
            q.readSql(source.connection, source.sql, params)
        } catch (e: QueryNotFound) {
            throw ReportRunException(422, "data source ${source.name}: ${e.message}", e)
        } catch (e: QueryRejected) {
            throw ReportRunException(422, "data source ${source.name}: ${e.message}", e)
        } catch (e: QueryForbidden) {
            throw ReportRunException(422, "data source ${source.name}: ${e.message}", e)
        } catch (e: QueryUnavailable) {
            throw ReportRunException(503, "data source ${source.name}: ${e.message}", e)
        } catch (e: QueryFailed) {
            throw ReportRunException(502, "data source ${source.name}: ${e.message}", e)
        }
        return when (result) {
            is QueryResult.Rows -> Raw(result.columns, result.rows, result.truncated)
            is QueryResult.Scalar -> Raw(listOf("value"), listOf(listOf(result.value)), false)
            is QueryResult.Update -> throw ReportRunException(422, "data source ${source.name}: a SQL source cannot change data")
            is QueryResult.Procedure -> throw ReportRunException(422, "data source ${source.name}: a SQL source is not a procedure call")
        }
    }

    private fun baseParams(parameters: Map<String, Binding>, values: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val params = linkedMapOf<String, Any?>()
        parameters.forEach { (k, b) -> params[k] = bound(b, values) }
        return params
    }

    /**
     * Runs [fetch] once, or once per row of the parent data source (REP-035): the parent column named in each bind
     * entry becomes that query parameter, and every result row is prefixed with `parent_<column>` values. At most
     * [MAX_PARENT_ROWS] parent rows are used; more make the dataset truncated.
     */
    private fun fetched(source: DataSourceDef, parent: ParentLink?, base: Map<String, Any?>, earlier: List<Dataset>, fetch: (Map<String, Any?>) -> Raw): Raw {
        if (parent == null) return fetch(base)
        val parentSet = earlier.firstOrNull { it.name == parent.source }
            ?: throw ReportRunException(422, "data source ${source.name}: parent ${parent.source} did not run")
        val indexes = parent.bind.map { (param, column) ->
            val i = parentSet.columns.indexOfFirst { it.name == column }
            if (i < 0) throw ReportRunException(422, "data source ${source.name}: parent ${parent.source} has no column '$column'")
            param to i
        }
        val prefix = parent.bind.values.distinct().map { "parent_$it" }
        val parentIdx = parent.bind.values.distinct().map { column -> parentSet.columns.indexOfFirst { it.name == column } }
        val used = parentSet.rows.take(MAX_PARENT_ROWS)
        var names: List<String>? = null
        var truncated = parentSet.rows.size > used.size
        val rows = ArrayList<List<Any?>>()
        for (parentRow in used) {
            val params = LinkedHashMap(base)
            indexes.forEach { (param, i) -> params[param] = parentRow.getOrNull(i) }
            val raw = fetch(params)
            val clash = raw.names.filter { it in prefix }
            if (clash.isNotEmpty()) throw ReportRunException(422, "data source ${source.name}: column ${clash.first()} clashes with a parent column")
            if (names == null) names = raw.names
            else if (names != raw.names) throw ReportRunException(422, "data source ${source.name}: the query returned different columns for different parent rows")
            if (raw.truncated) truncated = true
            val head = parentIdx.map { parentRow.getOrNull(it) }
            raw.rows.forEach { rows += head + it }
        }
        return Raw(prefix + (names ?: emptyList()), rows, truncated)
    }

    private fun namedQuery(source: DataSourceDef.NamedQuery, values: Map<String, Any?>, caller: ReportCaller, earlier: List<Dataset>): Dataset {
        val raw = fetched(source, source.parent, baseParams(source.parameters, values), earlier) { namedRaw(source, it, caller) }
        return typed(source.name, SourceType.NAMED_QUERY, source.columns, raw.names, raw.rows, raw.truncated)
    }

    private fun sqlQuery(source: DataSourceDef.Sql, values: Map<String, Any?>, earlier: List<Dataset>): Dataset {
        val raw = fetched(source, source.parent, baseParams(source.parameters, values), earlier) { sqlRaw(source, it) }
        return typed(source.name, SourceType.SQL, source.columns, raw.names, raw.rows, raw.truncated)
    }

    private fun typed(
        name: String,
        sourceType: SourceType,
        overrides: Map<String, ColumnOverride>,
        names: List<String>,
        rows: List<List<Any?>>,
        truncated: Boolean,
    ): Dataset {
        val columns = names.mapIndexed { i, n ->
            val override = overrides[n]
            Column(n, override?.type ?: infer(rows.map { it.getOrNull(i) }), override?.unit)
        }
        val out = rows.map { row -> columns.mapIndexed { i, c -> convertCell(c.type, row.getOrNull(i)) } }
        return Dataset(name, sourceType, columns, out, truncated)
    }

    private fun tagHistory(source: DataSourceDef.TagHistory, values: Map<String, Any?>, caller: ReportCaller, now: Long): Dataset {
        val h = history ?: throw ReportRunException(503, "tag history is not available")
        val rawRange = bound(source.range, values) ?: throw ReportRunException(400, "data source ${source.name}: range is null")
        val range = try {
            if (source.range is Binding.Literal) Parameters.timeRange(rawRange, now) else rawRange as Map<*, *>
        } catch (e: IllegalArgumentException) {
            throw ReportRunException(422, "data source ${source.name} range: ${e.message}")
        }
        val from = (range["from"] as Number).toLong()
        val to = (range["to"] as Number).toLong()
        // Permission first: a tag the caller cannot read is never queried.
        val included = ArrayList<Pair<HistoryTag, String>>()
        val excluded = ArrayList<String>()
        for (tag in source.tags) {
            val path = normalizeTagPath(tag.path)
            if (caller.system || tagCheck.canRead(caller, path)) included += tag to path else excluded += path
        }
        if (included.isEmpty()) {
            throw ReportRunException(403, "data source ${source.name}: no read permission for any of its tags")
        }
        val rows = ArrayList<List<Any?>>()
        val series = ArrayList<Series>()
        var truncated = false
        val columns: List<Column>
        try {
            if (source.mode == HistoryMode.RAW) {
                val values = ArrayList<Any?>()
                for ((tag, path) in included) {
                    val fetched = h.reader(path).query(HistoryQuery(path, from, to, minOf(source.limit + 1, HistoryQuery.MAX_LIMIT)))
                    if (fetched.size > source.limit) truncated = true
                    val kept = fetched.take(source.limit)
                    kept.forEach { s ->
                        val v = cellValue(s.value)
                        values += v
                        rows += listOf(path, s.timestampMillis, v, s.quality.code.label)
                    }
                    series += Series(path, tag.label ?: path, tag.unit, kept.map { listOf(it.timestampMillis, cellValue(it.value)) })
                }
                columns = listOf(
                    Column("tagPath", ColumnType.STRING),
                    Column("timestamp", ColumnType.TIMESTAMP),
                    Column("value", rawValueType(values), commonUnit(included.map { it.first })),
                    Column("quality", ColumnType.STRING),
                )
            } else {
                val mode = HistoryAggregate.fromWire(source.aggregate!!)
                try {
                    ReportDefinitions.checkWindows(source, from, to)
                } catch (e: IllegalArgumentException) {
                    throw ReportRunException(422, "data source ${source.name}: ${e.message}")
                }
                for ((tag, path) in included) {
                    val windows = HistoryAggregator.aggregate(h.reader(path), path, from, to, source.intervalMillis!!, mode)
                    windows.forEach { w -> rows += listOf(path, w.startMillis, w.endMillis, cellValue(w.value), w.rows) }
                    series += Series(path, tag.label ?: path, tag.unit, windows.map { listOf(it.startMillis, cellValue(it.value)) })
                }
                columns = listOf(
                    Column("tagPath", ColumnType.STRING),
                    Column("start", ColumnType.TIMESTAMP),
                    Column("end", ColumnType.TIMESTAMP),
                    Column("value", ColumnType.NUMBER, commonUnit(included.map { it.first })),
                    Column("rows", ColumnType.NUMBER),
                )
            }
        } catch (e: HistoryReadException) {
            throw ReportRunException(503, "data source ${source.name}: history cannot be read: ${e.message}", e)
        } catch (e: HistoryWriteException) {
            throw ReportRunException(503, "data source ${source.name}: history cannot be read: ${e.message}", e)
        }
        return Dataset(source.name, SourceType.TAG_HISTORY, columns, rows, truncated, series, excluded)
    }

    /**
     * Alarm journal rows of the range, one row per journal event. A user's run leaves out alarms whose source tag
     * the caller may not read (the per-tag check the journal API applies); a scheduled run sees every row.
     */
    private fun alarmJournal(source: DataSourceDef.AlarmJournal, values: Map<String, Any?>, caller: ReportCaller, now: Long): Dataset {
        val journal = alarms ?: throw ReportRunException(503, "the alarm journal is not available")
        val rawRange = bound(source.range, values) ?: throw ReportRunException(400, "data source ${source.name}: range is null")
        val range = try {
            if (source.range is Binding.Literal) Parameters.timeRange(rawRange, now) else rawRange as Map<*, *>
        } catch (e: IllegalArgumentException) {
            throw ReportRunException(422, "data source ${source.name} range: ${e.message}")
        }
        val allowed: (String) -> Boolean = if (caller.system) { _ -> true } else { s ->
            val path = journal.tagPath(s)
            path == null || tagCheck.canRead(caller, path)
        }
        val result = try {
            journal.query(AlarmJournalRequest((range["from"] as Number).toLong(), (range["to"] as Number).toLong(), source.filter, source.ascending, source.limit), allowed)
        } catch (e: ReportRunException) {
            throw e
        } catch (e: RuntimeException) {
            throw ReportRunException(503, "data source ${source.name}: the alarm journal cannot be read: ${e.message}", e)
        }
        val rows = result.rows.map { listOf(it.eventTime, it.source, it.displayPath, it.priority, it.eventType, it.actor, it.eventId) }
        val columns = listOf(
            Column("eventTime", ColumnType.TIMESTAMP), Column("source", ColumnType.STRING), Column("displayPath", ColumnType.STRING),
            Column("priority", ColumnType.STRING), Column("eventType", ColumnType.STRING), Column("actor", ColumnType.STRING),
            Column("eventId", ColumnType.STRING),
        )
        return Dataset(source.name, SourceType.ALARM_JOURNAL, columns, rows, truncated = result.total > rows.size)
    }

    companion object {
        /** Most parent rows a nested query runs for (REP-035). */
        const val MAX_PARENT_ROWS = 100

        private fun commonUnit(tags: List<HistoryTag>): String? = tags.map { it.unit }.distinct().singleOrNull()

        private fun rawValueType(values: List<Any?>): ColumnType {
            val present = values.filterNotNull()
            return when {
                present.isEmpty() -> ColumnType.NUMBER
                present.all { it is Number } -> ColumnType.NUMBER
                present.all { it is Boolean } -> ColumnType.BOOLEAN
                else -> ColumnType.STRING
            }
        }

        /** History values: numbers and booleans as-is, anything else as text. */
        private fun cellValue(v: Any?): Any? = when (v) {
            null, is Boolean, is String -> v
            is Float -> v.toDouble()
            is Number -> v
            else -> v.toString()
        }

        internal fun infer(values: List<Any?>): ColumnType {
            val present = values.filterNotNull()
            return when {
                present.isEmpty() -> ColumnType.STRING
                present.all { it is Number } -> ColumnType.NUMBER
                present.all { it is Boolean } -> ColumnType.BOOLEAN
                present.all { it is String && instant(it) != null } -> ColumnType.TIMESTAMP
                else -> ColumnType.STRING
            }
        }

        private fun instant(s: String): Long? = try {
            Instant.parse(s).toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }

        /** Convert one cell to the column type; a value that does not convert is kept as text. */
        internal fun convertCell(type: ColumnType, v: Any?): Any? {
            if (v == null) return null
            return when (type) {
                ColumnType.STRING -> v as? String ?: v.toString()
                ColumnType.NUMBER -> when (v) {
                    is Float -> v.toDouble()
                    is Number -> v
                    is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull() ?: v
                    is Boolean -> if (v) 1 else 0
                    else -> v.toString()
                }
                ColumnType.BOOLEAN -> when (v) {
                    is Boolean -> v
                    is Number -> v.toDouble() != 0.0
                    is String -> v.trim().lowercase().toBooleanStrictOrNull() ?: v
                    else -> v.toString()
                }
                ColumnType.TIMESTAMP -> when (v) {
                    is Number -> v.toLong()
                    is String -> instant(v.trim()) ?: v
                    else -> v.toString()
                }
            }
        }
    }
}
