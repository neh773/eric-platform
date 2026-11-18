package org.oip.reporting

import org.oip.config.SchemaRegistry
import org.oip.historian.HistoryAggregate
import org.oip.historian.HistoryAggregator
import org.oip.historian.HistoryQuery
import org.oip.historian.normalizeTagPath
import java.time.DateTimeException
import java.time.ZoneId

const val REPORTING_MODULE = "reporting"
const val REPORT_KIND = "Report"

private const val PARAM_NAME = "^[A-Za-z_][A-Za-z0-9_-]{0,62}$"
private const val SOURCE_NAME = "^[A-Za-z_][A-Za-z0-9_-]{0,62}$"
private const val QUERY_NAME = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"
const val MAX_PARAMETERS = 32
const val MAX_SOURCES = 16
const val MAX_TAGS = 32
/** Most alarm journal rows one data source returns; the journal pages in blocks of this size. */
const val MAX_JOURNAL_ROWS = 1000
private val PROFILE_NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
private val SECRET = Regex("^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/**
 * `reporting/Report` config resources (contract section 1). The JSON schema checks shape; [semanticErrors]
 * (registered through [SchemaRegistry.registerSemantic]) checks what a schema cannot: parameter references,
 * types of defaults, tag paths, aggregates, cron and time zone. Both run on every save path.
 */
fun registerReportSchemas(registry: SchemaRegistry) {
    registry.register(REPORTING_MODULE, REPORT_KIND, reportSchema())
    registry.registerSemantic(REPORTING_MODULE, REPORT_KIND) { spec -> ReportDefinitions.semanticErrors(spec) }
}

object ReportDefinitions {
    /** Errors that make [parse] fail, as messages; empty when the spec is valid. */
    fun semanticErrors(spec: Map<String, Any?>): List<String> = try {
        parse("validation", 0, spec)
        emptyList()
    } catch (e: IllegalArgumentException) {
        listOf(e.message ?: "invalid report")
    } catch (e: ClassCastException) {
        listOf("invalid report: ${e.message}")
    }

    /** Parse and check a spec that already passed the JSON schema. Throws [IllegalArgumentException]. */
    fun parse(name: String, version: Long, spec: Map<String, Any?>): ReportDefinition {
        val params = (spec["parameters"] as? List<*>).orEmpty().map { parameter(it as Map<*, *>) }
        val names = HashSet<String>()
        params.forEach { require(names.add(it.name)) { "parameter ${it.name} is declared twice" } }
        // Defaults must have the declared type; resolve them once at an arbitrary time.
        for (p in params) if (p.default != null) {
            try {
                Parameters.convert(p.type, p.default, 0)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("parameter ${p.name} default: ${e.message}")
            }
        }
        checkExpressions(params)
        val byName = params.associateBy { it.name }
        val sources = (spec["dataSources"] as? List<*>).orEmpty().map { source(it as Map<*, *>, byName) }
        require(sources.isNotEmpty()) { "dataSources must not be empty" }
        val sourceNames = HashSet<String>()
        sources.forEach { source ->
            val parent = when (source) {
                is DataSourceDef.NamedQuery -> source.parent
                is DataSourceDef.Sql -> source.parent
                else -> null
            }
            if (parent != null) {
                require(parent.source != source.name) { "data source ${source.name}: parent must be an earlier data source" }
                require(parent.source in sourceNames) { "data source ${source.name}: parent ${parent.source} is not declared before it" }
            }
            require(sourceNames.add(source.name)) { "data source ${source.name} is declared twice" }
        }
        val schedule = (spec["schedule"] as? Map<*, *>)?.let { schedule(it, params) }
        @Suppress("UNCHECKED_CAST")
        return ReportDefinition(
            name = name,
            version = version,
            title = (spec["title"] as? String)?.takeIf { it.isNotBlank() } ?: name,
            description = spec["description"] as? String ?: "",
            enabled = spec["enabled"] as? Boolean ?: true,
            parameters = params,
            dataSources = sources,
            schedule = schedule,
            layout = spec["layout"] as? Map<String, Any?>,
        )
    }

    /** Expression parameters: valid syntax, no default beside it, reads only earlier parameters, right type when constant. */
    private fun checkExpressions(params: List<ParameterDef>) {
        val earlier = HashSet<String>()
        for (p in params) {
            require(p.name != "now") { "parameter name now is reserved" }
            val text = p.expression
            if (text != null) {
                require(!p.hasDefault) { "parameter ${p.name}: use either default or expression, not both" }
                val node = try {
                    ParameterExpressions.parse(text)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("parameter ${p.name}: ${e.message}")
                }
                val refs = ParameterExpressions.references(node) - "now"
                val bad = refs.filter { it !in earlier }
                require(bad.isEmpty()) { "parameter ${p.name}: expression reads ${bad.sorted().joinToString()}, which is not declared before it" }
                if (refs.isEmpty()) try {
                    Parameters.convert(p.type, ParameterExpressions.evaluate(node, 0) { null }!!, 0)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("parameter ${p.name} expression: ${e.message}")
                }
            }
            earlier += p.name
        }
    }

    private fun parameter(m: Map<*, *>): ParameterDef = ParameterDef(
        name = m["name"] as String,
        type = ParameterType.fromWire(m["type"] as String),
        label = m["label"] as? String,
        default = m["default"],
        hasDefault = m.containsKey("default"),
        required = m["required"] as? Boolean ?: false,
        expression = m["expression"] as? String,
    )

    private fun binding(raw: Any?, where: String, params: Map<String, ParameterDef>): Binding {
        val m = raw as? Map<*, *> ?: throw IllegalArgumentException("$where must be {param} or {value}")
        val hasParam = m.containsKey("param")
        val hasValue = m.containsKey("value")
        require(hasParam != hasValue) { "$where must have exactly one of param or value" }
        if (hasValue) {
            require(!m.containsKey("field")) { "$where: field is only allowed with param" }
            return Binding.Literal(m["value"])
        }
        val name = m["param"] as String
        val def = params[name] ?: throw IllegalArgumentException("$where refers to unknown parameter $name")
        val field = m["field"] as? String
        if (field != null) require(def.type == ParameterType.TIME_RANGE) { "$where: field is only allowed for a timeRange parameter" }
        return Binding.Param(name, field)
    }

    private fun source(m: Map<*, *>, params: Map<String, ParameterDef>): DataSourceDef {
        val name = m["name"] as String
        return when (m["type"] as String) {
            SourceType.NAMED_QUERY.wire -> {
                val query = m["query"] as? String ?: throw IllegalArgumentException("data source $name: query is required")
                for (k in listOf("sql", "connection", "tags", "mode", "range", "aggregate", "intervalMillis", "limit")) {
                    require(!m.containsKey(k)) { "data source $name: $k is not allowed for namedQuery" }
                }
                val bindings = linkedMapOf<String, Binding>()
                (m["parameters"] as? Map<*, *>).orEmpty().forEach { (k, v) ->
                    val where = "data source $name parameter $k"
                    val b = binding(v, where, params)
                    if (b is Binding.Param && b.field == null) {
                        require(params.getValue(b.name).type != ParameterType.TIME_RANGE) { "$where: a timeRange parameter needs field from or to" }
                    }
                    bindings[k.toString()] = b
                }
                val columns = linkedMapOf<String, ColumnOverride>()
                (m["columns"] as? Map<*, *>).orEmpty().forEach { (k, v) ->
                    val c = v as Map<*, *>
                    columns[k.toString()] = ColumnOverride((c["type"] as? String)?.let { ColumnType.fromWire(it) }, c["unit"] as? String)
                }
                DataSourceDef.NamedQuery(name, query, bindings, columns, parent(m, name, bindings.keys))
            }
            SourceType.SQL.wire -> {
                val connection = m["connection"] as? String ?: throw IllegalArgumentException("data source $name: connection is required")
                val sql = m["sql"] as? String ?: throw IllegalArgumentException("data source $name: sql is required")
                require(sql.isNotBlank()) { "data source $name: sql is required" }
                for (k in listOf("query", "tags", "mode", "range", "aggregate", "intervalMillis", "limit")) {
                    require(!m.containsKey(k)) { "data source $name: $k is not allowed for sql" }
                }
                val bindings = linkedMapOf<String, Binding>()
                (m["parameters"] as? Map<*, *>).orEmpty().forEach { (k, v) ->
                    bindings[k.toString()] = binding(v, "data source $name parameter $k", params)
                }
                val columns = linkedMapOf<String, ColumnOverride>()
                (m["columns"] as? Map<*, *>).orEmpty().forEach { (k, v) ->
                    val c = v as Map<*, *>
                    columns[k.toString()] = ColumnOverride((c["type"] as? String)?.let { ColumnType.fromWire(it) }, c["unit"] as? String)
                }
                DataSourceDef.Sql(name, connection, sql, bindings, columns, parent(m, name, bindings.keys))
            }
            SourceType.TAG_HISTORY.wire -> {
                for (k in listOf("query", "sql", "connection", "parameters", "columns", "parent")) {
                    require(!m.containsKey(k)) { "data source $name: $k is not allowed for tagHistory" }
                }
                val mode = when (m["mode"] as? String ?: HistoryMode.RAW.wire) {
                    HistoryMode.RAW.wire -> HistoryMode.RAW
                    HistoryMode.AGGREGATE.wire -> HistoryMode.AGGREGATE
                    else -> throw IllegalArgumentException("data source $name: mode must be raw or aggregate")
                }
                val tags = (m["tags"] as? List<*>).orEmpty().map { t ->
                    val tm = t as Map<*, *>
                    val path = tm["path"] as String
                    try {
                        normalizeTagPath(path)
                    } catch (e: RuntimeException) {
                        throw IllegalArgumentException("data source $name: invalid tag path '$path': ${e.message}")
                    }
                    HistoryTag(path, tm["label"] as? String, tm["unit"] as? String)
                }
                require(tags.isNotEmpty()) { "data source $name: tags must not be empty" }
                val range = binding(m["range"] ?: throw IllegalArgumentException("data source $name: range is required"), "data source $name range", params)
                when (range) {
                    is Binding.Param -> {
                        require(range.field == null) { "data source $name range: field is not allowed" }
                        require(params.getValue(range.name).type == ParameterType.TIME_RANGE) { "data source $name range must bind a timeRange parameter" }
                    }
                    is Binding.Literal -> try {
                        Parameters.timeRange(range.value ?: throw IllegalArgumentException("must not be null"), 0)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("data source $name range: ${e.message}")
                    }
                }
                val aggregate = m["aggregate"] as? String
                val interval = (m["intervalMillis"] as? Number)?.toLong()
                if (mode == HistoryMode.AGGREGATE) {
                    requireNotNull(aggregate) { "data source $name: aggregate is required for mode aggregate" }
                    HistoryAggregate.fromWire(aggregate)
                    requireNotNull(interval) { "data source $name: intervalMillis is required for mode aggregate" }
                    require(interval > 0) { "data source $name: intervalMillis must be > 0" }
                    require(!m.containsKey("limit")) { "data source $name: limit is only for mode raw" }
                } else {
                    require(aggregate == null && interval == null) { "data source $name: aggregate and intervalMillis are only for mode aggregate" }
                }
                val limit = (m["limit"] as? Number)?.toInt() ?: HistoryQuery.DEFAULT_LIMIT
                require(limit in 1..HistoryQuery.MAX_LIMIT) { "data source $name: limit must be 1..${HistoryQuery.MAX_LIMIT}" }
                DataSourceDef.TagHistory(name, mode, tags, range, aggregate, interval, limit)
            }
            SourceType.ALARM_JOURNAL.wire -> {
                for (k in listOf("query", "sql", "connection", "parameters", "columns", "parent", "tags", "mode", "aggregate", "intervalMillis")) {
                    require(!m.containsKey(k)) { "data source $name: $k is not allowed for alarmJournal" }
                }
                val range = binding(m["range"] ?: throw IllegalArgumentException("data source $name: range is required"), "data source $name range", params)
                when (range) {
                    is Binding.Param -> {
                        require(range.field == null) { "data source $name range: field is not allowed" }
                        require(params.getValue(range.name).type == ParameterType.TIME_RANGE) { "data source $name range must bind a timeRange parameter" }
                    }
                    is Binding.Literal -> try {
                        Parameters.timeRange(range.value ?: throw IllegalArgumentException("must not be null"), 0)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("data source $name range: ${e.message}")
                    }
                }
                val priorities = listOf("diagnostic", "low", "medium", "high", "critical")
                fun priority(key: String): String? = (m[key] as? String)?.lowercase()?.also {
                    require(it in priorities) { "data source $name: $key must be one of ${priorities.joinToString()}" }
                }
                val min = priority("minPriority")
                val max = priority("maxPriority")
                if (min != null && max != null) require(priorities.indexOf(min) <= priorities.indexOf(max)) { "data source $name: maxPriority must not be below minPriority" }
                val eventTypes = (m["eventTypes"] as? List<*>)?.map { (it as? String)?.lowercase() ?: throw IllegalArgumentException("data source $name: eventTypes must be names") }
                if (eventTypes != null) {
                    require(eventTypes.isNotEmpty()) { "data source $name: eventTypes must not be empty" }
                    val known = setOf("active", "clear", "ack", "acknowledged", "shelve", "shelved", "unshelve", "unshelved", "enabled", "disabled")
                    val bad = eventTypes.filter { it !in known }
                    require(bad.isEmpty()) { "data source $name: unknown eventTypes ${bad.joinToString()} (active, clear, ack, shelve, unshelve, enabled, disabled)" }
                }
                val order = m["order"] as? String ?: "desc"
                require(order == "asc" || order == "desc") { "data source $name: order must be asc or desc" }
                val limit = (m["limit"] as? Number)?.toInt() ?: MAX_JOURNAL_ROWS
                require(limit in 1..MAX_JOURNAL_ROWS) { "data source $name: limit must be 1..$MAX_JOURNAL_ROWS" }
                DataSourceDef.AlarmJournal(
                    name, range,
                    AlarmJournalFilter(min, max, eventTypes, (m["source"] as? String)?.takeIf { it.isNotBlank() }, (m["displayPath"] as? String)?.takeIf { it.isNotBlank() }),
                    order == "asc", limit,
                )
            }
            else -> throw IllegalArgumentException("data source $name: type must be namedQuery, sql, tagHistory, or alarmJournal")
        }
    }

    /** `parent: {source, bind: {parameter: column}}` of a query source (REP-035); null when absent. */
    private fun parent(m: Map<*, *>, name: String, bound: Set<String>): ParentLink? {
        val raw = m["parent"] ?: return null
        val p = raw as? Map<*, *> ?: throw IllegalArgumentException("data source $name: parent must be an object")
        val extra = p.keys.map { it.toString() }.filter { it != "source" && it != "bind" }
        require(extra.isEmpty()) { "data source $name: parent has unknown fields ${extra.sorted().joinToString()}" }
        val source = p["source"] as? String ?: throw IllegalArgumentException("data source $name: parent.source is required")
        val bind = linkedMapOf<String, String>()
        (p["bind"] as? Map<*, *> ?: throw IllegalArgumentException("data source $name: parent.bind is required")).forEach { (k, v) ->
            val column = v as? String ?: throw IllegalArgumentException("data source $name: parent.bind.$k must be a column name")
            require(column.isNotEmpty()) { "data source $name: parent.bind.$k must be a column name" }
            require(k.toString() !in bound) { "data source $name: parameter $k is bound by both parameters and parent.bind" }
            bind[k.toString()] = column
        }
        require(bind.isNotEmpty()) { "data source $name: parent.bind must bind at least one parameter" }
        return ParentLink(source, bind)
    }

    private fun schedule(m: Map<*, *>, params: List<ParameterDef>): ScheduleDef {
        val cron = m["cron"] as String
        try {
            CronExpression.parse(cron)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("schedule: ${e.message}")
        }
        val zone = m["timezone"] as? String ?: "UTC"
        try {
            ZoneId.of(zone)
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("schedule: unknown timezone '$zone'")
        }
        @Suppress("UNCHECKED_CAST")
        val values = (m["parameters"] as? Map<String, Any?>).orEmpty()
        try {
            Parameters.resolve(params, values, 0)
        } catch (e: ReportParameterException) {
            throw IllegalArgumentException("schedule: ${e.message}")
        }
        return ScheduleDef(m["enabled"] as? Boolean ?: true, cron, zone, values, actions(m["actions"]))
    }

    private fun actions(raw: Any?): List<ScheduleAction> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: throw IllegalArgumentException("schedule.actions must be a list")
        if (list.size > 8) throw IllegalArgumentException("schedule: at most 8 actions")
        return list.mapIndexed { index, item ->
            val map = item as? Map<*, *> ?: throw IllegalArgumentException("schedule.actions[$index] must be an object")
            val type = map["type"] as? String ?: throw IllegalArgumentException("schedule.actions[$index].type is required")
            val at = "schedule.actions[$index]"
            when (type) {
                "save" -> {
                    rejectExtra(map, at, setOf("type"))
                    ScheduleAction.Save
                }
                "email" -> {
                    rejectExtra(map, at, setOf("type", "to", "from", "subject", "host", "port", "username", "password", "smtpProfile"))
                    val profile = text(map, "smtpProfile", at, required = false)
                    if (profile.isNotEmpty()) {
                        if (!PROFILE_NAME.matches(profile)) throw IllegalArgumentException("$at.smtpProfile must be a profile name")
                        val clash = listOf("host", "port", "username", "password").filter { map.containsKey(it) }
                        if (clash.isNotEmpty()) {
                            throw IllegalArgumentException("$at: smtpProfile supplies the server and login, so ${clash.joinToString()} must be left out")
                        }
                        return@mapIndexed ScheduleAction.Email(
                            to = address(map, "to", at),
                            from = if (map.containsKey("from")) address(map, "from", at) else "",
                            subject = text(map, "subject", at, required = false).ifEmpty { "Report" },
                            host = "",
                            port = 0,
                            username = "",
                            passwordRef = "",
                            smtpProfile = profile,
                        )
                    }
                    val username = text(map, "username", at, required = false)
                    ScheduleAction.Email(
                        to = address(map, "to", at),
                        from = address(map, "from", at),
                        subject = text(map, "subject", at, required = false).ifEmpty { "Report" },
                        host = host(map, at),
                        port = port(map, at, 25),
                        username = username,
                        passwordRef = secret(map, at, required = username.isNotEmpty()),
                    )
                }
                "print" -> {
                    rejectExtra(map, at, setOf("type", "printer", "copies"))
                    val copies = (map["copies"] as? Number)?.toInt() ?: 1
                    if (copies !in 1..20) throw IllegalArgumentException("$at.copies must be 1..20")
                    ScheduleAction.Print(printer = text(map, "printer", at, required = true), copies = copies)
                }
                "ftp" -> {
                    rejectExtra(map, at, setOf("type", "host", "port", "username", "password", "path"))
                    ScheduleAction.Ftp(
                        host = host(map, at),
                        port = port(map, at, 21),
                        username = text(map, "username", at, required = true),
                        passwordRef = secret(map, at, required = false),
                        path = text(map, "path", at, required = true),
                    )
                }
                else -> throw IllegalArgumentException("$at.type must be save, email, print, or ftp")
            }
        }
    }

    private fun rejectExtra(map: Map<*, *>, at: String, allowed: Set<String>) {
        val extra = map.keys.map { it.toString() }.filter { it !in allowed }
        if (extra.isNotEmpty()) throw IllegalArgumentException("$at: unknown fields ${extra.sorted().joinToString()}")
    }

    private fun text(map: Map<*, *>, key: String, at: String, required: Boolean): String {
        val value = map[key] ?: return if (required) throw IllegalArgumentException("$at.$key is required") else ""
        val text = value as? String ?: throw IllegalArgumentException("$at.$key must be a string")
        if (text.any { it.code < 0x20 || it.code == 0x7F }) throw IllegalArgumentException("$at.$key contains a control character")
        if (text.length > 400) throw IllegalArgumentException("$at.$key is too long")
        if (required && text.isBlank()) throw IllegalArgumentException("$at.$key is required")
        return text
    }

    private fun address(map: Map<*, *>, key: String, at: String): String {
        val text = text(map, key, at, required = true)
        if (!text.contains('@') || text.length > 254) throw IllegalArgumentException("$at.$key must be an email address")
        return text
    }

    private fun host(map: Map<*, *>, at: String): String {
        val text = text(map, "host", at, required = true)
        if (text.any { it == '/' || it == ' ' }) throw IllegalArgumentException("$at.host is invalid")
        return text
    }

    private fun port(map: Map<*, *>, at: String, default: Int): Int {
        val raw = map["port"] ?: return default
        val port = (raw as? Number)?.toInt() ?: throw IllegalArgumentException("$at.port must be an integer")
        if (port !in 1..65535) throw IllegalArgumentException("$at.port is out of range")
        return port
    }

    private fun secret(map: Map<*, *>, at: String, required: Boolean): String {
        val text = text(map, "password", at, required = required)
        if (text.isEmpty()) return ""
        if (!SECRET.matches(text)) throw IllegalArgumentException("$at.password must be a secret:// reference")
        return text
    }

    /** Check an aggregate source's window count for a resolved range (run time; ranges are not known at save). */
    fun checkWindows(source: DataSourceDef.TagHistory, from: Long, to: Long) {
        HistoryAggregator.windowCount(from, to, source.intervalMillis!!)
    }
}

private fun reportSchema(): Map<String, Any?> {
    val anyValue = emptyMap<String, Any?>()
    val binding = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "param" to mapOf("type" to "string", "pattern" to PARAM_NAME),
            "field" to mapOf("type" to "string", "enum" to listOf("from", "to")),
            "value" to anyValue,
        ),
    )
    val columnTypes = ColumnType.entries.map { it.wire }
    return linkedMapOf(
        "\$schema" to SchemaRegistry.DRAFT,
        "type" to "object",
        "required" to listOf("dataSources"),
        "properties" to linkedMapOf(
            "title" to mapOf("type" to "string", "maxLength" to 200),
            "description" to mapOf("type" to "string", "maxLength" to 2000, "default" to ""),
            "enabled" to mapOf("type" to "boolean", "default" to true),
            "parameters" to mapOf(
                "type" to "array",
                "maxItems" to MAX_PARAMETERS,
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("name", "type"),
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "pattern" to PARAM_NAME),
                        "type" to mapOf("type" to "string", "enum" to ParameterType.entries.map { it.wire }),
                        "label" to mapOf("type" to "string", "maxLength" to 200),
                        "description" to mapOf("type" to "string", "maxLength" to 2000),
                        "default" to anyValue,
                        "expression" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 500),
                        "required" to mapOf("type" to "boolean"),
                    ),
                ),
            ),
            "dataSources" to mapOf(
                "type" to "array",
                "minItems" to 1,
                "maxItems" to MAX_SOURCES,
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("name", "type"),
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "pattern" to SOURCE_NAME),
                        "type" to mapOf("type" to "string", "enum" to SourceType.entries.map { it.wire }),
                        "query" to mapOf("type" to "string", "pattern" to QUERY_NAME),
                        "connection" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 63),
                        "sql" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 100_000),
                        "parameters" to mapOf("type" to "object", "maxProperties" to 64, "additionalProperties" to binding),
                        "columns" to mapOf(
                            "type" to "object",
                            "maxProperties" to 256,
                            "additionalProperties" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "type" to mapOf("type" to "string", "enum" to columnTypes),
                                    "unit" to mapOf("type" to "string", "maxLength" to 32),
                                ),
                            ),
                        ),
                        "parent" to mapOf(
                            "type" to "object",
                            "required" to listOf("source", "bind"),
                            "properties" to mapOf(
                                "source" to mapOf("type" to "string", "pattern" to SOURCE_NAME),
                                "bind" to mapOf("type" to "object", "minProperties" to 1, "maxProperties" to 16, "additionalProperties" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 128)),
                            ),
                        ),
                        "mode" to mapOf("type" to "string", "enum" to HistoryMode.entries.map { it.wire }),
                        "tags" to mapOf(
                            "type" to "array",
                            "maxItems" to MAX_TAGS,
                            "items" to mapOf(
                                "type" to "object",
                                "required" to listOf("path"),
                                "properties" to mapOf(
                                    "path" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 1000),
                                    "label" to mapOf("type" to "string", "maxLength" to 200),
                                    "unit" to mapOf("type" to "string", "maxLength" to 32),
                                ),
                            ),
                        ),
                        "range" to binding,
                        "aggregate" to mapOf("type" to "string", "enum" to HistoryAggregate.entries.map { it.wire }),
                        "intervalMillis" to mapOf("type" to "integer", "minimum" to 1),
                        "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to HistoryQuery.MAX_LIMIT),
                        "minPriority" to mapOf("type" to "string", "enum" to listOf("diagnostic", "low", "medium", "high", "critical")),
                        "maxPriority" to mapOf("type" to "string", "enum" to listOf("diagnostic", "low", "medium", "high", "critical")),
                        "eventTypes" to mapOf("type" to "array", "minItems" to 1, "maxItems" to 16, "items" to mapOf("type" to "string", "maxLength" to 16)),
                        "source" to mapOf("type" to "string", "maxLength" to 1000),
                        "displayPath" to mapOf("type" to "string", "maxLength" to 1000),
                        "order" to mapOf("type" to "string", "enum" to listOf("asc", "desc")),
                    ),
                ),
            ),
            "schedule" to mapOf(
                "type" to "object",
                "required" to listOf("cron"),
                "properties" to mapOf(
                    "enabled" to mapOf("type" to "boolean", "default" to true),
                    "cron" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 200),
                    "timezone" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 64, "default" to "UTC"),
                    "parameters" to mapOf("type" to "object", "maxProperties" to MAX_PARAMETERS),
                    "actions" to mapOf(
                        "type" to "array",
                        "maxItems" to 8,
                        "items" to mapOf(
                            "type" to "object",
                            "required" to listOf("type"),
                            "properties" to mapOf(
                                "type" to mapOf("type" to "string", "enum" to listOf("save", "email", "print", "ftp")),
                                "to" to mapOf("type" to "string", "maxLength" to 254),
                                "from" to mapOf("type" to "string", "maxLength" to 254),
                                "subject" to mapOf("type" to "string", "maxLength" to 200),
                                "host" to mapOf("type" to "string", "maxLength" to 253),
                                "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535),
                                "username" to mapOf("type" to "string", "maxLength" to 128),
                                "password" to mapOf("type" to "string", "pattern" to SECRET.pattern),
                                "smtpProfile" to mapOf("type" to "string", "pattern" to PROFILE_NAME.pattern, "description" to "Shared SMTP email profile; replaces host, port, username and password."),
                                "path" to mapOf("type" to "string", "maxLength" to 400),
                                "printer" to mapOf("type" to "string", "maxLength" to 128),
                                "copies" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 20),
                            ),
                        ),
                    ),
                ),
            ),
            "layout" to mapOf(
                "type" to "object",
                "description" to "Opaque to the backend: stored and returned verbatim for the renderer.",
            ),
        ),
    )
}
