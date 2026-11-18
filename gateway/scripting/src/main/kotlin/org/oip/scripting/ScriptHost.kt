package org.oip.scripting

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.graalvm.polyglot.HostAccess
import org.oip.db.DatabaseService
import org.oip.db.QueryResult
import org.oip.tags.Dataset
import org.oip.tags.NodeKind
import org.oip.tags.QualityCode
import org.oip.tags.TagCaller
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone

/**
 * The only host object a guest script can call. Methods are annotated for
 * [HostAccess.CONSTRAINED]; everything else on the JVM stays unreachable.
 *
 * Tag reads and writes take the engine lock only for the duration of [TagEngine.read]
 * and [TagEngine.write]. They do not run guest code.
 *
 * Named queries go through [DatabaseService.execute]. This object does not open JDBC.
 *
 * [caller] is who the script acts for. Gateway scripts run as [TagCaller.System]; an HMI session's
 * script hooks run as the signed-in user, so each tag's read and write permissions apply.
 */
class ScriptHost(
    private val tags: TagEngine,
    private val databases: DatabaseService?,
    private val clock: java.time.Clock,
    private val zone: ZoneId,
    private val levels: LogLevels,
    private val sink: ScriptLogger,
    private val queryRoles: Set<String>,
    var deliver: (project: String, handler: String, payloadJson: String, wait: Boolean) -> String =
        { _, _, _, _ -> throw IllegalStateException("message handlers are not armed") },
    private val caller: TagCaller = TagCaller.System,
    /**
     * Writes a tag resource when the config store knows the tag schema. Returns true when the store
     * (and its listener) applied the tag, so the engine must not be configured a second time.
     */
    var saveTag: (TagConfig) -> Boolean = { false },
    /** Deletes the tag resource when one exists. Returns true when the store removed it. */
    var removeTag: (String) -> Boolean = { false },
    /** Rewrites the tag resource onto [toPath]. Returns true when the store moved it. */
    var moveTag: (TagConfig, String) -> Boolean = { _, _ -> false },
    var scheduleScript: (Long, String) -> Unit = { _, _ -> throw IllegalStateException("timers are not armed") },
    var gatewayStatus: () -> Map<String, Any?> = { mapOf("state" to "running", "name" to "", "uptimeMillis" to 0L) },
    /** `system.eam.*` (SCR-012). Unset outside a gateway: every call fails. */
    var eam: (String, String) -> String = { _, _ -> throw IllegalStateException("enterprise administration is not available") },
    /**
     * Asked before each write: false (for an HMI session, its login logged out, its key revoked, or `tags.write`
     * gone) answers that write `Bad_AccessDenied`.
     */
    private val mayWrite: () -> Boolean = { true },
    /** Tag values one run may write; past it a write throws, which ends the script unless it catches it. */
    private val maxWrites: Int = Int.MAX_VALUE,
) {
    private val json = ObjectMapper()
    private val writes = java.util.concurrent.atomic.AtomicInteger()

    @HostAccess.Export
    fun tagRead(pathsJson: String): String {
        val paths = stringList(pathsJson, "tag paths")
        val values = paths.map { path ->
            val qv = tags.read(path, caller)
            mapOf(
                "value" to jsonSafe(qv.value),
                "quality" to qv.quality.code.label,
                "timestamp" to qv.timestamp.toEpochMilli(),
            )
        }
        return json.writeValueAsString(values)
    }

    @HostAccess.Export
    fun tagWrite(pathsJson: String, valuesJson: String): String {
        val paths = stringList(pathsJson, "tag paths")
        val values = valueList(valuesJson)
        if (paths.size != values.size) throw IllegalArgumentException("tag paths and values differ in length")
        val qualities = paths.zip(values).map { (path, value) ->
            if (writes.incrementAndGet() > maxWrites) throw IllegalStateException("tag write limit reached ($maxWrites per run)")
            if (runCatching(mayWrite).getOrDefault(false)) tags.write(path, value, caller).quality.code.label
            else QualityCode.BAD_USER_ACCESS_DENIED.label
        }
        return json.writeValueAsString(qualities)
    }

    @HostAccess.Export
    fun tagBrowse(path: String): String {
        val at = if (path.isBlank()) "[default]" else path
        // As the caller: a tag it may not read is listed by name and kind only (S-1), the same as the browse route.
        val results = tags.browse(at, 0, Int.MAX_VALUE, caller).map { node ->
            mapOf(
                "name" to node.name,
                "fullPath" to node.path.toString(),
                "tagType" to when (node.kind) {
                    NodeKind.FOLDER -> "Folder"
                    NodeKind.TAG -> "AtomicTag"
                    NodeKind.UDT_INSTANCE -> "UdtInstance"
                    NodeKind.UDT_DEFINITION -> "UdtType"
                },
                "dataType" to node.dataType?.label(),
                "hasChildren" to (node.kind != NodeKind.TAG),
            )
        }
        return json.writeValueAsString(mapOf("results" to results))
    }

    @HostAccess.Export
    fun log(level: String, name: String, message: String) {
        if (!levels.allows(name, level)) return
        val text = if (message.length > 8_000) message.substring(0, 8_000) else message
        sink.log(level, name, text)
    }

    @HostAccess.Export
    fun logEnabled(name: String, level: String): Boolean = levels.emits(name, level)

    @HostAccess.Export
    fun formatMessage(fmt: String, argsJson: String): String {
        val args = valueList(argsJson).toTypedArray()
        return java.util.Formatter(Locale.US).use { it.format(fmt, *args).toString() }
    }

    @HostAccess.Export
    fun nowMillis(): Long = clock.millis()

    @HostAccess.Export
    fun formatDate(millis: Long, pattern: String): String = formatter(pattern).format(java.util.Date(millis))

    @HostAccess.Export
    fun parseDate(text: String, pattern: String): Long = formatter(pattern).parse(text).time

    /** [month] is zero-based, matching system.date.getDate. */
    @HostAccess.Export
    fun makeDate(year: Long, month: Long, day: Long): Long =
        LocalDate.of(year.toInt(), month.toInt() + 1, day.toInt()).atStartOfDay(zone).toInstant().toEpochMilli()

    @HostAccess.Export
    fun addDate(millis: Long, unit: String, amount: Long): Long {
        val zoned = Instant.ofEpochMilli(millis).atZone(zone)
        val next = when (unit) {
            "millis" -> zoned.plus(amount, ChronoUnit.MILLIS)
            "seconds" -> zoned.plusSeconds(amount)
            "minutes" -> zoned.plusMinutes(amount)
            "hours" -> zoned.plusHours(amount)
            "days" -> zoned.plusDays(amount)
            "weeks" -> zoned.plusWeeks(amount)
            "months" -> zoned.plusMonths(amount)
            "years" -> zoned.plusYears(amount)
            else -> throw IllegalArgumentException("unknown date unit '$unit'")
        }
        return next.toInstant().toEpochMilli()
    }

    @HostAccess.Export
    fun dateField(millis: Long, field: String): Long {
        val zoned = Instant.ofEpochMilli(millis).atZone(zone)
        return when (field) {
            "millis" -> (zoned.nano / 1_000_000).toLong()
            "second" -> zoned.second.toLong()
            "minute" -> zoned.minute.toLong()
            "hour24" -> zoned.hour.toLong()
            "hour12" -> {
                val hour = zoned.hour % 12
                (if (hour == 0) 12 else hour).toLong()
            }
            // java.time Monday=1 .. Sunday=7. Calendar / system.date Sunday=1 .. Saturday=7.
            "dayOfWeek" -> (if (zoned.dayOfWeek.value == 7) 1 else zoned.dayOfWeek.value + 1).toLong()
            "dayOfMonth" -> zoned.dayOfMonth.toLong()
            "dayOfYear" -> zoned.dayOfYear.toLong()
            "month" -> (zoned.monthValue - 1).toLong()
            "year" -> zoned.year.toLong()
            "quarter" -> ((zoned.monthValue - 1) / 3 + 1).toLong()
            else -> throw IllegalArgumentException("unknown date field '$field'")
        }
    }

    /** Positive when [end] is after [start]. */
    @HostAccess.Export
    fun between(start: Long, end: Long, unit: String): Long {
        val a = Instant.ofEpochMilli(start).atZone(zone)
        val b = Instant.ofEpochMilli(end).atZone(zone)
        return when (unit) {
            "millis" -> end - start
            "seconds" -> (end - start) / 1_000
            "minutes" -> (end - start) / 60_000
            "hours" -> (end - start) / 3_600_000
            "days" -> (end - start) / 86_400_000
            "weeks" -> (end - start) / (7 * 86_400_000)
            "months" -> ChronoUnit.MONTHS.between(a, b)
            "years" -> ChronoUnit.YEARS.between(a, b)
            else -> throw IllegalArgumentException("unknown date unit '$unit'")
        }
    }

    @HostAccess.Export
    fun midnight(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    @HostAccess.Export
    fun zoneId(): String = if (zone.id == "Z") "UTC" else zone.id

    @HostAccess.Export
    fun zoneOffset(millis: Long): Long =
        zone.rules.getOffset(Instant.ofEpochMilli(millis)).totalSeconds.toLong() * 1_000

    @HostAccess.Export
    fun zoneRawOffset(): Long =
        zone.rules.getStandardOffset(Instant.ofEpochMilli(clock.millis())).totalSeconds.toLong() * 1_000

    /** The project whose named queries `execQuery` prefers (PRO-001); null for a gateway script. */
    var project: String? = null

    @HostAccess.Export
    fun execQuery(path: String, paramsJson: String): String = execQueryTx(path, paramsJson, "")

    @HostAccess.Export
    fun execQueryTx(path: String, paramsJson: String, tx: String): String {
        val db = databases ?: throw IllegalStateException("no database service")
        val params = readMap(paramsJson)
        val body = when (val result = db.execute(path, params, queryRoles, tx = tx.ifBlank { null }, project = project)) {
            is QueryResult.Rows -> mapOf(
                "kind" to "rows",
                "columns" to result.columns,
                "rows" to result.rows.map { row -> row.map { jsonSafe(it) } },
                "truncated" to result.truncated,
            )
            is QueryResult.Scalar -> mapOf("kind" to "scalar", "value" to jsonSafe(result.value))
            is QueryResult.Update -> mapOf("kind" to "update", "count" to result.rowsAffected)
            is QueryResult.Procedure -> result.table().let { (columns, rows) ->
                mapOf(
                    "kind" to "rows",
                    "columns" to columns,
                    "rows" to rows.map { row -> row.map { jsonSafe(it) } },
                    "truncated" to result.truncated,
                )
            }
        }
        return json.writeValueAsString(body)
    }

    @HostAccess.Export
    fun scriptQuery(connection: String, sql: String, paramsJson: String, prepared: String, mode: String, tx: String): String {
        gatewaySql()
        val db = databases ?: throw IllegalStateException("no database service")
        val params = if (paramsJson.isBlank()) emptyMap() else readMap(paramsJson)
        val result = db.scriptQuery(connection, sql, params, prepared == "true", mode, tx.ifBlank { null })
        return json.writeValueAsString(resultBody(result))
    }

    @HostAccess.Export
    fun beginTransaction(connection: String): String {
        gatewaySql()
        val db = databases ?: throw IllegalStateException("no database service")
        return db.beginScriptTransaction(connection)
    }

    @HostAccess.Export
    fun finishTransaction(id: String, commit: String) {
        gatewaySql()
        val db = databases ?: throw IllegalStateException("no database service")
        db.finishScriptTransaction(id, commit == "true")
    }

    @HostAccess.Export
    fun tagConfigure(specJson: String): String {
        if (!mayConfigureTags()) throw IllegalStateException("tag configuration is not permitted")
        val config = TagConfig.fromSpec(readMap(specJson))
        if (!saveTag(config)) tags.configure(config)
        return config.path
    }

    @HostAccess.Export
    fun tagRemove(path: String): String {
        if (!mayConfigureTags()) throw IllegalStateException("tag configuration is not permitted")
        if (!removeTag(path)) tags.remove(path)
        return path
    }

    @HostAccess.Export
    fun tagCopy(fromPath: String, toPath: String): String {
        if (!mayConfigureTags()) throw IllegalStateException("tag configuration is not permitted")
        val source = findTag(fromPath)
        val copy = retarget(source, toPath)
        if (!saveTag(copy)) tags.configure(copy)
        return copy.path
    }

    @HostAccess.Export
    fun tagMove(fromPath: String, toPath: String): String {
        if (!mayConfigureTags()) throw IllegalStateException("tag configuration is not permitted")
        val source = findTag(fromPath)
        val moved = retarget(source, toPath)
        if (!moveTag(source, moved.path)) {
            val value = tags.read(fromPath, TagCaller.System)
            tags.remove(fromPath)
            tags.configure(moved)
            if (value.quality.code == QualityCode.GOOD) tags.write("[${moved.provider}]${moved.path}", value.value, TagCaller.System)
        }
        return moved.path
    }

    @HostAccess.Export
    fun schedule(delayMillis: Long, source: String) {
        if (caller !is TagCaller.System) throw IllegalStateException("timers are gateway scripts only")
        if (delayMillis !in 0..600_000) throw IllegalArgumentException("timer delay must be 0..600000 ms")
        if (source.length > 20_000) throw IllegalArgumentException("timer script is too long")
        scheduleScript(delayMillis, source)
    }

    /** `system.eam.<function>(...)`: gateway scripts only, like ad hoc SQL. */
    @HostAccess.Export
    fun eamCall(function: String, argsJson: String): String {
        if (caller !is TagCaller.System) throw IllegalStateException("system.eam is available to gateway scripts only")
        return eam(function, argsJson)
    }

    @HostAccess.Export
    fun statusJson(): String = json.writeValueAsString(gatewayStatus().mapValues { jsonSafe(it.value) })

    var alarmQuery: (String) -> String = { throw IllegalStateException("alarms are not armed") }
    var alarmAcknowledge: (List<String>, String, String?) -> Unit = { _, _, _ -> throw IllegalStateException("alarms are not armed") }
    var alarmShelve: (String, Long, String) -> Unit = { _, _, _ -> throw IllegalStateException("alarms are not armed") }
    /** Sends one mail through a shared SMTP profile: profile, to, from, subject, body, html (GAT-030). */
    var emailSend: (String, String, String, String, String, Boolean) -> Unit = { _, _, _, _, _, _ -> throw IllegalStateException("email profiles are not armed") }
    var alarmJournal: (String) -> String = { throw IllegalStateException("alarms are not armed") }
    var fileRoot: java.nio.file.Path? = null
    var configListFn: (String, String, String) -> String = { _, _, _ -> throw IllegalStateException("config is not armed") }
    var configGetFn: (String, String, String, String) -> String = { _, _, _, _ -> throw IllegalStateException("config is not armed") }
    var configPutFn: (String, String, String, String, String) -> String = { _, _, _, _, _ -> throw IllegalStateException("config is not armed") }
    var configDeleteFn: (String, String, String, String) -> Unit = { _, _, _, _ -> throw IllegalStateException("config is not armed") }
    var historyQueryFn: (String, Long, Long, String, Long) -> String = { _, _, _, _, _ -> throw IllegalStateException("history is not armed") }
    var historyStoreFn: (String, String, Long) -> Unit = { _, _, _ -> throw IllegalStateException("history is not armed") }
    var historyAnnotateFn: (String, Long, String) -> Unit = { _, _, _ -> throw IllegalStateException("history is not armed") }
    var historyNotesFn: (String) -> String = { throw IllegalStateException("history is not armed") }
    var historyBrowseFn: () -> String = { throw IllegalStateException("history is not armed") }
    var deviceListFn: () -> String = { throw IllegalStateException("devices are not armed") }
    var deviceEnableFn: (String, Boolean) -> Unit = { _, _ -> throw IllegalStateException("devices are not armed") }
    var opcBrowseFn: (String, String) -> String = { _, _ -> throw IllegalStateException("opc is not armed") }
    var opcReadFn: (String, String) -> String = { _, _ -> throw IllegalStateException("opc is not armed") }
    var opcWriteFn: (String, String, String) -> String = { _, _, _ -> throw IllegalStateException("opc is not armed") }
    var opcuaListFn: () -> String = { throw IllegalStateException("opcua is not armed") }
    var opcuaCallFn: (String, String, String) -> String = { _, _, _ -> throw IllegalStateException("opcua is not armed") }
    var userListFn: () -> String = { throw IllegalStateException("users are not armed") }
    var roleListFn: () -> String = { throw IllegalStateException("users are not armed") }

    /**
     * The rest of `system.opcua`, `system.user` and `system.security` (SCR-021, SCR-023, SCR-027): function
     * name (as in [LIBRARY_ACCESS]), JSON-decoded arguments, and the acting user (`system` for a gateway
     * script). The host has already checked who may call the function; the answer is JSON-encoded here.
     */
    var libraryFn: (String, List<Any?>, String) -> Any? = { _, _, _ -> throw IllegalStateException("library is not armed") }

    internal fun adoptLibrary(from: ScriptHost) {
        configListFn = from.configListFn
        configGetFn = from.configGetFn
        configPutFn = from.configPutFn
        configDeleteFn = from.configDeleteFn
        historyQueryFn = from.historyQueryFn
        historyStoreFn = from.historyStoreFn
        historyAnnotateFn = from.historyAnnotateFn
        historyNotesFn = from.historyNotesFn
        historyBrowseFn = from.historyBrowseFn
        deviceListFn = from.deviceListFn
        deviceEnableFn = from.deviceEnableFn
        opcBrowseFn = from.opcBrowseFn
        opcReadFn = from.opcReadFn
        opcWriteFn = from.opcWriteFn
        opcuaListFn = from.opcuaListFn
        opcuaCallFn = from.opcuaCallFn
        userListFn = from.userListFn
        roleListFn = from.roleListFn
        libraryFn = from.libraryFn
        alarmQuery = from.alarmQuery
        alarmAcknowledge = from.alarmAcknowledge
        alarmShelve = from.alarmShelve
        alarmJournal = from.alarmJournal
        emailSend = from.emailSend
        fileRoot = from.fileRoot
    }

    @HostAccess.Export
    fun securityUser(): String {
        val who = caller
        val body = if (who is TagCaller.User) {
            mapOf("username" to who.actor, "roles" to who.roles.sorted(), "zone" to who.zone)
        } else {
            mapOf("username" to "system", "roles" to emptyList<String>(), "zone" to "")
        }
        return json.writeValueAsString(body)
    }

    @HostAccess.Export
    fun alarmQueryJson(source: String): String = alarmQuery(source.take(500))

    @HostAccess.Export
    fun alarmAck(idsJson: String, notes: String) {
        val ids = stringList(idsJson, "event ids")
        if (ids.size > 100) throw IllegalArgumentException("too many event ids")
        alarmAcknowledge(ids, actor(), notes.take(500).ifBlank { null })
    }

    /**
     * `system.net.sendEmail`: gateway scripts only. The message goes out through the named shared SMTP profile, which
     * holds the server and login, so a script never sees a password or token.
     * Doc source: FEATURES GAT-030.
     */
    @HostAccess.Export
    fun emailSendJson(profile: String, to: String, from: String, subject: String, body: String, html: Boolean) {
        gatewayOnly("email")
        if (!Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$").matches(profile)) throw IllegalArgumentException("email profile name is invalid")
        for ((field, value) in listOf("to" to to, "from" to from)) {
            if (value.length > 254 || value.any { it.code < 0x20 || it.code == 0x7F }) throw IllegalArgumentException("$field address is invalid")
        }
        if (!to.contains('@')) throw IllegalArgumentException("to must be an email address")
        if (from.isNotEmpty() && !from.contains('@')) throw IllegalArgumentException("from must be an email address")
        if (subject.length > 300 || body.length > 200_000) throw IllegalArgumentException("subject or body is too long")
        emailSend(profile, to, from, subject, body, html)
    }

    @HostAccess.Export
    fun alarmJournalJson(source: String): String = alarmJournal(source.take(500))

    @HostAccess.Export
    fun alarmShelveJson(source: String, minutes: Long) {
        if (source.isBlank() || source.length > 500) throw IllegalArgumentException("alarm source is invalid")
        if (minutes !in 1..43_200) throw IllegalArgumentException("shelve minutes must be 1..43200")
        alarmShelve(source, minutes, actor())
    }

    @HostAccess.Export
    fun fileRead(relative: String): String {
        gatewayOnly("files")
        val path = scriptFile(relative)
        if (!java.nio.file.Files.isRegularFile(path)) throw IllegalArgumentException("file was not found")
        if (java.nio.file.Files.size(path) > 1_000_000) throw IllegalArgumentException("file is too large")
        return java.nio.file.Files.readString(path)
    }

    @HostAccess.Export
    fun fileWrite(relative: String, text: String) {
        gatewayOnly("files")
        if (text.length > 1_000_000) throw IllegalArgumentException("file is too large")
        val path = scriptFile(relative)
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.writeString(path, text)
    }

    @HostAccess.Export
    fun configList(module: String, scope: String, kind: String): String {
        gatewayOnly("config")
        return configListFn(bounded(module, "module"), bounded(scope, "scope"), bounded(kind, "kind"))
    }

    @HostAccess.Export
    fun configGet(module: String, scope: String, kind: String, name: String): String {
        gatewayOnly("config")
        return configGetFn(bounded(module, "module"), bounded(scope, "scope"), bounded(kind, "kind"), bounded(name, "name"))
    }

    @HostAccess.Export
    fun configPut(module: String, scope: String, kind: String, name: String, specJson: String): String {
        gatewayOnly("config")
        if (specJson.length > 100_000) throw IllegalArgumentException("spec is too large")
        return configPutFn(bounded(module, "module"), bounded(scope, "scope"), bounded(kind, "kind"), bounded(name, "name"), specJson)
    }

    @HostAccess.Export
    fun configDelete(module: String, scope: String, kind: String, name: String) {
        gatewayOnly("config")
        configDeleteFn(bounded(module, "module"), bounded(scope, "scope"), bounded(kind, "kind"), bounded(name, "name"))
    }

    @HostAccess.Export
    fun historyQuery(path: String, fromMillis: Long, toMillis: Long, aggregate: String, intervalMillis: Long): String {
        gatewayOnly("history")
        return historyQueryFn(bounded(path, "path", 500), fromMillis, toMillis, aggregate.take(32), intervalMillis)
    }

    @HostAccess.Export
    fun historyStore(path: String, value: String, timestamp: Long) {
        gatewayOnly("history")
        if (value.length > 10_000) throw IllegalArgumentException("value is too large")
        historyStoreFn(bounded(path, "path", 500), value, timestamp)
    }

    @HostAccess.Export
    fun historyAnnotate(path: String, timestamp: Long, note: String) {
        gatewayOnly("history")
        if (note.length > 2_000) throw IllegalArgumentException("note is too large")
        historyAnnotateFn(bounded(path, "path", 500), timestamp, note)
    }

    @HostAccess.Export
    fun historyNotes(path: String): String {
        gatewayOnly("history")
        return historyNotesFn(bounded(path, "path", 500))
    }

    @HostAccess.Export
    fun historyBrowse(): String {
        gatewayOnly("history")
        return historyBrowseFn()
    }

    @HostAccess.Export
    fun deviceList(): String {
        gatewayOnly("devices")
        return deviceListFn()
    }

    @HostAccess.Export
    fun deviceEnable(name: String, enabled: Boolean) {
        gatewayOnly("devices")
        deviceEnableFn(bounded(name, "device"), enabled)
    }

    @HostAccess.Export
    fun opcBrowse(device: String, nodeId: String): String {
        gatewayOnly("opc")
        return opcBrowseFn(bounded(device, "device"), nodeId.take(1_000))
    }

    @HostAccess.Export
    fun opcRead(device: String, nodeId: String): String {
        gatewayOnly("opc")
        return opcReadFn(bounded(device, "device"), nodeId.take(1_000))
    }

    @HostAccess.Export
    fun opcWrite(device: String, nodeId: String, value: String): String {
        gatewayOnly("opc")
        if (value.length > 10_000) throw IllegalArgumentException("value is too large")
        return opcWriteFn(bounded(device, "device"), nodeId.take(1_000), value)
    }

    @HostAccess.Export
    fun opcuaList(): String {
        gatewayOnly("opcua")
        return opcuaListFn()
    }

    @HostAccess.Export
    fun opcuaCall(connection: String, objectId: String, method: String): String {
        gatewayOnly("opcua")
        return opcuaCallFn(bounded(connection, "connection"), objectId.take(1_000), bounded(method, "method"))
    }

    @HostAccess.Export
    fun libraryCall(function: String, argsJson: String): String {
        val access = LIBRARY_ACCESS[function] ?: throw IllegalArgumentException("unknown function")
        if (argsJson.length > 100_000) throw IllegalArgumentException("arguments are too large")
        val args = (json.readValue(argsJson, List::class.java) as List<*>).toList()
        val who = caller
        when (access) {
            LibraryAccess.ANY -> Unit
            LibraryAccess.GATEWAY -> gatewayOnly(function.substringBefore('.'))
            LibraryAccess.ANY -> Unit
            LibraryAccess.SELF -> if (who is TagCaller.User) {
                val name = args.firstOrNull() as? String
                if (name == null || !name.equals(who.actor, ignoreCase = true)) {
                    throw SecurityException("$function is only allowed for the signed-in user")
                }
            }
        }
        return json.writeValueAsString(libraryFn(function, args, actor()))
    }

    @HostAccess.Export
    fun userList(): String {
        gatewayOnly("users")
        return userListFn()
    }

    @HostAccess.Export
    fun roleList(): String {
        gatewayOnly("users")
        return roleListFn()
    }

    private fun bounded(value: String, what: String, max: Int = 200): String {
        if (value.isBlank() || value.length > max || value.any { it.code < 0x20 }) throw IllegalArgumentException("$what is invalid")
        return value
    }

    @HostAccess.Export
    fun httpCall(method: String, url: String, body: String, headersJson: String): String {
        gatewayOnly("http")
        val verb = method.uppercase()
        if (verb != "GET" && verb != "POST") throw IllegalArgumentException("method must be GET or POST")
        val parsed = try {
            java.net.URI(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("url is invalid")
        }
        if (parsed.scheme != "http" && parsed.scheme != "https") throw IllegalArgumentException("url must be http or https")
        if (parsed.userInfo != null) throw IllegalArgumentException("url must not include a user")
        if (body.length > 100_000) throw IllegalArgumentException("body is too large")
        val headers = headerMap(headersJson)
        val client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            // SEC-022: the gateway's outbound trust store.
            .sslContext(org.oip.config.OutboundTls.context("script-http"))
            .build()
        val builder = java.net.http.HttpRequest.newBuilder(parsed).timeout(java.time.Duration.ofSeconds(5))
        for ((name, value) in headers) builder.header(name, value)
        if (verb == "GET") builder.GET() else builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        val response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        val text = response.body() ?: ""
        if (text.length > 256_000) throw IllegalArgumentException("response is too large")
        return json.writeValueAsString(mapOf("status" to response.statusCode(), "body" to text))
    }

    private fun actor(): String = when (val who = caller) {
        is TagCaller.User -> who.actor
        else -> "system"
    }

    private fun gatewayOnly(what: String) {
        if (caller !is TagCaller.System) throw IllegalStateException("$what is a gateway script only")
    }

    private fun scriptFile(relative: String): java.nio.file.Path {
        val root = fileRoot?.normalize() ?: throw IllegalStateException("files are not armed")
        if (relative.isBlank() || relative.length > 200 || relative.contains("..") || relative.startsWith("/") || relative.startsWith("\\") || relative.contains('\\') || relative.any { it.code < 0x20 }) {
            throw IllegalArgumentException("file path is not allowed")
        }
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(root)) throw IllegalArgumentException("file path is not allowed")
        return path
    }

    private fun headerMap(headersJson: String): Map<String, String> {
        if (headersJson.isBlank()) return emptyMap()
        val node = json.readTree(headersJson)
        if (!node.isObject) throw IllegalArgumentException("headers must be an object")
        if (node.size() > 16) throw IllegalArgumentException("too many headers")
        val out = linkedMapOf<String, String>()
        for (field in node.fields()) {
            val name = field.key
            if (name.length > 64 || name.any { it.code < 0x20 || it == ':' }) throw IllegalArgumentException("header name is invalid")
            val value = field.value?.asText() ?: throw IllegalArgumentException("header value must be text")
            if (value.length > 500 || value.any { it.code < 0x20 }) throw IllegalArgumentException("header value is invalid")
            out[name] = value
        }
        return out
    }

    @HostAccess.Export
    fun sendMessage(project: String, handler: String, payloadJson: String): String {
        deliver(project, handler, payloadJson, false)
        return ""
    }

    @HostAccess.Export
    fun sendRequest(project: String, handler: String, payloadJson: String): String =
        deliver(project, handler, payloadJson, true)

    private fun gatewaySql() {
        if (caller !is TagCaller.System) throw IllegalStateException("ad hoc queries and transactions are gateway scripts only")
    }

    private fun mayConfigureTags(): Boolean = when (val who = caller) {
        TagCaller.System -> true
        is TagCaller.User -> who.configAdmin
    }

    private fun resultBody(result: QueryResult): Map<String, Any?> = when (result) {
        is QueryResult.Rows -> mapOf(
            "kind" to "rows",
            "columns" to result.columns,
            "rows" to result.rows.map { row -> row.map { jsonSafe(it) } },
            "truncated" to result.truncated,
        )
        is QueryResult.Scalar -> mapOf("kind" to "scalar", "value" to jsonSafe(result.value))
        is QueryResult.Update -> mapOf("kind" to "update", "count" to result.rowsAffected)
        is QueryResult.Procedure -> result.table().let { (columns, rows) ->
            mapOf(
                "kind" to "rows",
                "columns" to columns,
                "rows" to rows.map { row -> row.map { jsonSafe(it) } },
                "truncated" to result.truncated,
            )
        }
    }

    private fun findTag(path: String): TagConfig {
        val wanted = path.trim()
        return tags.copyTagConfigs().map { it.second }.firstOrNull { config ->
            val full = "[${config.provider}]${config.path}"
            full.equals(wanted, ignoreCase = true) || config.path.equals(wanted, ignoreCase = true)
        } ?: throw IllegalArgumentException("tag '$path' is not configured")
    }

    private fun retarget(source: TagConfig, toPath: String): TagConfig {
        val text = toPath.trim()
        val (provider, path) = if (text.startsWith("[") && "]" in text) {
            val end = text.indexOf(']')
            text.substring(1, end) to text.substring(end + 1)
        } else {
            source.provider to text
        }
        return source.copy(provider = provider.ifBlank { source.provider }, path = path.trim('/'))
    }

    private fun formatter(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).also { it.timeZone = TimeZone.getTimeZone(zone) }

    private fun stringList(raw: String, what: String): List<String> {
        val node = json.readTree(raw)
        if (!node.isArray) throw IllegalArgumentException("$what must be a list")
        return node.map { it.asText() }
    }

    private fun valueList(raw: String): List<Any?> {
        val node = json.readTree(raw)
        if (!node.isArray) throw IllegalArgumentException("expected a list")
        return node.map { fromNode(it) }
    }

    private fun readMap(raw: String): Map<String, Any?> {
        val node = json.readTree(if (raw.isBlank()) "{}" else raw)
        if (!node.isObject) throw IllegalArgumentException("parameters must be an object")
        return node.fields().asSequence().associate { it.key to fromNode(it.value) }
    }

    private fun fromNode(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> if (node.canConvertToInt()) node.intValue() else node.longValue()
        node.isNumber -> node.doubleValue()
        node.isTextual -> node.textValue()
        node.isArray -> node.map { fromNode(it) }
        node.isObject -> node.fields().asSequence().associate { it.key to fromNode(it.value) }
        else -> node.asText()
    }

    private fun jsonSafe(value: Any?): Any? = when (value) {
        null -> null
        is Boolean, is String -> value
        is Byte, is Short, is Int, is Long, is Float, is Double -> value
        is Instant -> value.toEpochMilli()
        is java.util.Date -> value.time
        is Dataset -> value.toWire()
        is List<*> -> value.map { jsonSafe(it) }
        is Map<*, *> -> value.entries.associate { it.key.toString() to jsonSafe(it.value) }
        else -> value.toString()
    }
}

/**
 * Who may call each [ScriptHost.libraryFn] function. GATEWAY: gateway scripts only. SELF: a gateway script for
 * any user, a session or designer script only for its own user name (the first argument), so a caller cannot
 * use it to probe other accounts. ANY: read-only functions every script may call.
 */
internal enum class LibraryAccess { GATEWAY, SELF, ANY }

internal val LIBRARY_ACCESS: Map<String, LibraryAccess> = mapOf(
    "opcua.getConnectionInfo" to LibraryAccess.GATEWAY,
    "opcua.addConnection" to LibraryAccess.GATEWAY,
    "opcua.removeConnection" to LibraryAccess.GATEWAY,
    "opcua.setConnectionEnabled" to LibraryAccess.GATEWAY,
    "opcua.callMethod" to LibraryAccess.GATEWAY,
    "user.getUser" to LibraryAccess.GATEWAY,
    "user.addUser" to LibraryAccess.GATEWAY,
    "user.editUser" to LibraryAccess.GATEWAY,
    "user.removeUser" to LibraryAccess.GATEWAY,
    "user.getSchedules" to LibraryAccess.GATEWAY,
    "user.getSchedule" to LibraryAccess.GATEWAY,
    "user.isScheduleActive" to LibraryAccess.GATEWAY,
    "user.getHolidays" to LibraryAccess.GATEWAY,
    "security.validateUser" to LibraryAccess.SELF,
    "security.getUserRoles" to LibraryAccess.SELF,
    // system.sfc (SFC-004): starting and steering charts is gateway-only; listing running charts is read-only.
    // system.util.translate (HMI-005): a term lookup, read-only.
    "util.translate" to LibraryAccess.ANY,
    "sfc.startChart" to LibraryAccess.GATEWAY,
    "sfc.cancelChart" to LibraryAccess.GATEWAY,
    "sfc.pauseChart" to LibraryAccess.GATEWAY,
    "sfc.resumeChart" to LibraryAccess.GATEWAY,
    "sfc.setVariable" to LibraryAccess.GATEWAY,
    "sfc.getRunningCharts" to LibraryAccess.ANY,
    // SCR-035, SCR-018, SCR-022, SCR-016, SCR-011, SCR-010: all gateway-only except the name of the running project.
    "project.getProjectNames" to LibraryAccess.GATEWAY,
    "project.getProjectName" to LibraryAccess.ANY,
    "project.requestScan" to LibraryAccess.GATEWAY,
    "net.getHostName" to LibraryAccess.GATEWAY,
    "net.getIpAddress" to LibraryAccess.GATEWAY,
    "net.getIpAddresses" to LibraryAccess.GATEWAY,
    "net.getRemoteServers" to LibraryAccess.GATEWAY,
    "secrets.listSecrets" to LibraryAccess.GATEWAY,
    "secrets.secretExists" to LibraryAccess.GATEWAY,
    "secrets.writeSecret" to LibraryAccess.GATEWAY,
    "secrets.deleteSecret" to LibraryAccess.GATEWAY,
    "roster.getRosterNames" to LibraryAccess.GATEWAY,
    "roster.getRoster" to LibraryAccess.GATEWAY,
    "roster.createRoster" to LibraryAccess.GATEWAY,
    "roster.setRosterUsers" to LibraryAccess.GATEWAY,
    "roster.deleteRoster" to LibraryAccess.GATEWAY,
    "eventstream.publish" to LibraryAccess.GATEWAY,
    "eventstream.getStreams" to LibraryAccess.GATEWAY,
    "eventstream.diagnose" to LibraryAccess.GATEWAY,
    "groups.getGroups" to LibraryAccess.GATEWAY,
    "groups.getGroupStatus" to LibraryAccess.GATEWAY,
    "groups.setGroupEnabled" to LibraryAccess.GATEWAY,
    "groups.executeGroup" to LibraryAccess.GATEWAY,
    // system.bacnet (SCR-007): raw device reads, writes and time sync talk to field devices: gateway scripts only.
    "bacnet.readRaw" to LibraryAccess.GATEWAY,
    "bacnet.writeRaw" to LibraryAccess.GATEWAY,
    "bacnet.synchronizeTime" to LibraryAccess.GATEWAY,
    "bacnet.synchronizeTimeUtc" to LibraryAccess.GATEWAY,
    // system.dnp (SCR-003): commands operate field equipment: gateway scripts only.
    "dnp.selectOperateBinary" to LibraryAccess.GATEWAY,
    "dnp.directOperateBinary" to LibraryAccess.GATEWAY,
    "dnp.selectOperateAnalog" to LibraryAccess.GATEWAY,
    "dnp.directOperateAnalog" to LibraryAccess.GATEWAY,
    // SCR-024: running a report reads every source it names, so only gateway scripts (which run as the system caller) may.
    "report.getReportNames" to LibraryAccess.GATEWAY,
    "report.executeReport" to LibraryAccess.GATEWAY,
)
