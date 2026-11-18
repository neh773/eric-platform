package org.oip.reporting

/**
 * Report result data contract, version [CONTRACT_VERSION]. The JSON shape is documented in
 * `/workspace/handoff/C9-REPORT-CONTRACT.md` (copied to the PR description); these classes are its Kotlin mirror.
 * Every class has a `toMap()` that writes the documented field order, so the JSON is deterministic.
 * Timestamps are epoch milliseconds (UTC).
 */
const val CONTRACT_VERSION = 1

enum class ColumnType(val wire: String) {
    STRING("string"), NUMBER("number"), BOOLEAN("boolean"), TIMESTAMP("timestamp");

    companion object {
        fun fromWire(text: String): ColumnType = entries.firstOrNull { it.wire == text }
            ?: throw IllegalArgumentException("column type must be one of ${entries.joinToString { it.wire }}")
    }
}

enum class SourceType(val wire: String) { NAMED_QUERY("namedQuery"), TAG_HISTORY("tagHistory"), SQL("sql"), ALARM_JOURNAL("alarmJournal") }

enum class RunTrigger(val wire: String) { MANUAL("manual"), SCHEDULE("schedule") }

enum class RunStatus(val wire: String) {
    SUCCESS("success"), PARTIAL("partial"), FAILURE("failure");

    companion object {
        fun fromWire(text: String): RunStatus = entries.first { it.wire == text }
    }
}

data class Column(val name: String, val type: ColumnType, val unit: String? = null) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("name" to name, "type" to type.wire).also { m ->
        unit?.let { m["unit"] = it }
    }
}

/** One chart series: `points` are `[timestamp, value]` pairs. */
data class Series(val tagPath: String, val label: String, val unit: String?, val points: List<List<Any?>>) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("tagPath" to tagPath, "label" to label).also { m ->
        unit?.let { m["unit"] = it }
        m["points"] = points
    }
}

data class Dataset(
    val name: String,
    val sourceType: SourceType,
    val columns: List<Column>,
    val rows: List<List<Any?>>,
    val truncated: Boolean = false,
    val series: List<Series> = emptyList(),
    val excludedTags: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "sourceType" to sourceType.wire,
        "columns" to columns.map { it.toMap() },
        "rows" to rows,
        "truncated" to truncated,
        "series" to series.map { it.toMap() },
        "excludedTags" to excludedTags,
    )
}

data class ReportRef(val id: String, val name: String, val title: String, val version: Long) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "name" to name, "title" to title, "version" to version)
}

/** A resolved parameter. A timeRange value is `{from, to}` in epoch millis. */
data class ResolvedParameter(val name: String, val type: ParameterType, val value: Any?) {
    fun toMap(): Map<String, Any?> = linkedMapOf("name" to name, "type" to type.wire, "value" to value)
}

data class RunInfo(
    val runId: String,
    val trigger: RunTrigger,
    val user: String,
    val startedAt: Long,
    val durationMillis: Long,
    val status: RunStatus,
    val warnings: List<String>,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "runId" to runId,
        "trigger" to trigger.wire,
        "user" to user,
        "startedAt" to startedAt,
        "durationMillis" to durationMillis,
        "status" to status.wire,
        "warnings" to warnings,
    )
}

data class ReportResult(
    val report: ReportRef,
    val parameters: List<ResolvedParameter>,
    val generatedAt: Long,
    val datasets: List<Dataset>,
    val run: RunInfo,
    /** The definition's `layout`, verbatim; null when the definition has none. */
    val layout: Map<String, Any?>?,
    val contractVersion: Int = CONTRACT_VERSION,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "contractVersion" to contractVersion,
        "report" to report.toMap(),
        "parameters" to parameters.map { it.toMap() },
        "generatedAt" to generatedAt,
        "datasets" to datasets.map { it.toMap() },
        "run" to run.toMap(),
    ).also { m -> layout?.let { m["layout"] = it } }
}

/** One persisted run (no result data). */
data class RunRecord(
    val runId: String,
    val report: String,
    val trigger: RunTrigger,
    val user: String,
    val startedAt: Long,
    val durationMillis: Long,
    val status: RunStatus,
    val error: String?,
    val datasets: Int,
    val rows: Int,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "runId" to runId,
        "report" to report,
        "trigger" to trigger.wire,
        "user" to user,
        "startedAt" to startedAt,
        "durationMillis" to durationMillis,
        "status" to status.wire,
        "error" to error,
        "datasets" to datasets,
        "rows" to rows,
    )
}

data class ReportStatus(
    val report: String,
    val scheduled: Boolean,
    val cron: String?,
    val timezone: String?,
    val nextFireAt: Long?,
    val lastRunAt: Long?,
    val lastStatus: RunStatus?,
    val lastError: String?,
    val consecutiveFailures: Int,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "report" to report,
        "scheduled" to scheduled,
        "cron" to cron,
        "timezone" to timezone,
        "nextFireAt" to nextFireAt,
        "lastRunAt" to lastRunAt,
        "lastStatus" to lastStatus?.wire,
        "lastError" to lastError,
        "consecutiveFailures" to consecutiveFailures,
    )
}

// ---- Report definition (spec of a `reporting/Report` config resource) ----

enum class ParameterType(val wire: String) {
    STRING("string"), NUMBER("number"), BOOLEAN("boolean"), TIMESTAMP("timestamp"), TIME_RANGE("timeRange");

    companion object {
        fun fromWire(text: String): ParameterType = entries.firstOrNull { it.wire == text }
            ?: throw IllegalArgumentException("parameter type must be one of ${entries.joinToString { it.wire }}")
    }
}

data class ParameterDef(
    val name: String,
    val type: ParameterType,
    val label: String?,
    val default: Any?,
    val hasDefault: Boolean,
    val required: Boolean,
    /** A [ParameterExpressions] expression used when a run supplies no value (FEATURES REP-034). */
    val expression: String? = null,
)

/** Where a value comes from: a report parameter (optionally the `from`/`to` field of a timeRange), or a literal. */
sealed class Binding {
    data class Param(val name: String, val field: String?) : Binding()
    data class Literal(val value: Any?) : Binding()
}

data class ColumnOverride(val type: ColumnType?, val unit: String?)

data class HistoryTag(val path: String, val label: String?, val unit: String?)

enum class HistoryMode(val wire: String) { RAW("raw"), AGGREGATE("aggregate") }

/**
 * Runs a query once per row of an earlier data source (FEATURES REP-035). [bind] maps a query parameter name to a
 * column of [source]; each result row is prefixed with `parent_<column>` columns holding the parent's values.
 */
data class ParentLink(val source: String, val bind: Map<String, String>)

sealed class DataSourceDef {
    abstract val name: String

    data class NamedQuery(
        override val name: String,
        val query: String,
        val parameters: Map<String, Binding>,
        val columns: Map<String, ColumnOverride>,
        val parent: ParentLink? = null,
    ) : DataSourceDef()

    data class Sql(
        override val name: String,
        val connection: String,
        val sql: String,
        val parameters: Map<String, Binding>,
        val columns: Map<String, ColumnOverride>,
        val parent: ParentLink? = null,
    ) : DataSourceDef()

    data class TagHistory(
        override val name: String,
        val mode: HistoryMode,
        val tags: List<HistoryTag>,
        val range: Binding,
        val aggregate: String?,
        val intervalMillis: Long?,
        val limit: Int,
    ) : DataSourceDef()

    /** Rows of the alarm journal inside [range] (FEATURES REP-032), newest first unless [ascending]. */
    data class AlarmJournal(
        override val name: String,
        val range: Binding,
        val filter: AlarmJournalFilter,
        val ascending: Boolean,
        val limit: Int,
    ) : DataSourceDef()
}

/** Alarm journal rows of a report range (FEATURES REP-032). Priorities and event types by name, as the journal API shows them. */
data class AlarmJournalFilter(
    val minPriority: String?,
    val maxPriority: String?,
    /** Event type names (`active`, `clear`, `ack`, `shelve`, `unshelve`, `enabled`, `disabled`); null: every type. */
    val eventTypes: List<String>?,
    /** Exact alarm source path filter; null: every source. */
    val source: String?,
    val displayPath: String?,
)

/** One scheduled delivery of the rendered PDF. An empty action list means save the file, which is what schedules did before actions existed. */
sealed class ScheduleAction {
    data object Save : ScheduleAction()

    data class Email(
        val to: String,
        val from: String,
        val subject: String,
        val host: String,
        val port: Int,
        val username: String,
        val passwordRef: String,
        /**
         * Name of a shared SMTP email profile (GAT-030). When set, the profile supplies the host, login and sender,
         * and [host], [port], [username] and [passwordRef] are empty.
         */
        val smtpProfile: String = "",
    ) : ScheduleAction()

    data class Print(val printer: String, val copies: Int) : ScheduleAction()

    data class Ftp(
        val host: String,
        val port: Int,
        val username: String,
        val passwordRef: String,
        val path: String,
    ) : ScheduleAction()
}

data class ScheduleDef(
    val enabled: Boolean,
    val cron: String,
    val timezone: String,
    val parameters: Map<String, Any?>,
    val actions: List<ScheduleAction> = emptyList(),
)

data class ReportDefinition(
    val name: String,
    val version: Long,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val parameters: List<ParameterDef>,
    val dataSources: List<DataSourceDef>,
    val schedule: ScheduleDef?,
    val layout: Map<String, Any?>?,
)
