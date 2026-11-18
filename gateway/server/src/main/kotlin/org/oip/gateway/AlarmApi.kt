package org.oip.gateway

import io.javalin.Javalin
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmFilter
import org.oip.alarm.AlarmPriority
import org.oip.alarm.AlarmRemote
import org.oip.alarm.RemoteCaller
import org.oip.alarm.JournalEventTypes
import org.oip.alarm.JournalQuery
import org.oip.alarm.JournalRecord
import org.oip.alarm.ShelvedAlarmView
import org.oip.alarm.alarmSourceTagPath
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import org.oip.security.SecurityService
import org.oip.tags.TagCaller
import org.oip.tags.TagEngine
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Live alarm status, acknowledgement, and shelving.
 *
 * GET requires `tags.read` (viewer, operator, administrator). Ack and shelve require `tags.write`
 * (operator, administrator), and since review-3 F2 also read and write access to each alarm's source tag.
 *
 * C-14: `GET /api/v1/alarms?state=shelved` lists shelved alarms with expiry and actor. The journal is
 * `GET /api/v1/alarms/journal` (tags.read). Retention settings and purge need `gateway.write` and are audited.
 *
 * S-1 (TAG-013): the live, shelved and journal queries leave out alarms whose source tag the caller may not read
 * ([TagEngine.mayRead] with the caller's roles and zone, the check `POST /api/v1/tags/read` uses). The journal
 * applies it before paging, so `total` counts only rows the caller may see. Journal rows of a tag that is no longer
 * configured are hidden unless the caller holds `gateway.write` (the permission that edits tag configuration).
 * Alarms of a remote tag provider are left to that provider, as remote tag reads are.
 */
class AlarmApi(
    private val alarms: AlarmEngine,
    private val remote: AlarmRemote? = null,
    private val security: SecurityService? = null,
    private val clock: Clock = Clock.systemUTC(),
    /** Per-tag read checks (TAG-013). Null (no tag engine, or no [security] for the zones): none. */
    private val tags: TagEngine? = null,
    /** Where the journal rows are stored (DAT-015). Null: the journal's own file, not configurable here. */
    private val storage: org.oip.alarm.JournalStorageBinding? = null,
) {
    fun register(app: Javalin) {
        app.get("/api/v1/alarms") { ctx ->
            val state = ctx.queryParam("state")?.trim()?.lowercase()
            if (state != null && state != "live" && state != "shelved") throw Problems.badRequest("state must be live or shelved")
            val active = when (ctx.queryParam("active")) {
                null -> null
                "true" -> true
                "false" -> false
                else -> throw Problems.badRequest("active must be true or false")
            }
            val min = ctx.queryParam("minPriority")?.let { raw ->
                AlarmPriority.fromName(raw) ?: throw Problems.badRequest("unknown minPriority '$raw'")
            }
            val filter = AlarmFilter(
                active = active,
                minPriority = min,
                source = ctx.queryParam("source"),
                displayPath = ctx.queryParam("displayPath"),
            )
            if (state == "shelved") {
                val readable = readableSources(ctx)
                val caller = remoteCaller(ctx)
                val local = alarms.shelved(filter).filter { readable(it.source) }
                val remoteShelves = remote?.shelved(filter, caller)?.filter { readable(it.source) } ?: emptyList()
                val merged = (local + remoteShelves).distinctBy { it.source }.sortedBy { it.source }
                json(ctx, mapOf("alarms" to merged.map { it.toJson() }))
                return@get
            }
            val readable = readableSources(ctx)
            val caller = remoteCaller(ctx)
            val events = (alarms.query(filter) + (remote?.query(filter, caller) ?: emptyList())).filter { readable(it.source) }
            json(ctx, mapOf("alarms" to events.sortedWith(compareBy({ it.source }, { it.eventId })).map { it.toJson() }))
        }
        app.post("/api/v1/alarms/ack") { ctx ->
            val body = objectBody(ctx)
            val ids = stringList(body, "eventIds")
            val notes = body["notes"] as? String
            val user = AuditContext.current()?.actor ?: throw Problems.unauthorized("authentication required")
            val remoteIds = ids.filter { remote?.ownsEvent(it) == true }
            val localIds = ids.filter { it !in remoteIds }
            // Every local id is checked (exists, the caller may act on its tag, notes) before anything is applied,
            // so a refused request changes nothing, here or on a peer (review-3 F2, F7).
            val mayAct = actionAllowed(ctx)
            for (id in localIds) {
                val source = alarms.sourceOfEvent(id) ?: throw Problems.notFound("alarm event $id not found")
                if (!mayAct(source)) throw Problems.forbidden("not permitted to acknowledge alarm event $id")
            }
            if (localIds.isNotEmpty()) alarms.checkAcknowledge(localIds, notes)
            if (remoteIds.isNotEmpty()) remote?.acknowledge(remoteIds, user, notes, remoteCaller(ctx).copy(user = user))
            if (localIds.isNotEmpty()) alarms.acknowledge(localIds, user, notes)
            json(ctx, mapOf("acknowledged" to ids))
        }
        app.post("/api/v1/alarms/shelve") { ctx ->
            val body = objectBody(ctx)
            val sources = stringList(body, "sources")
            val seconds = number(body["durationSeconds"]) ?: throw Problems.badRequest("durationSeconds is required")
            val max = alarms.settings.maxShelveDuration.toSeconds()
            if (seconds.isNaN() || seconds <= 0.0 || seconds > max) {
                throw Problems.badRequest("durationSeconds must be greater than 0 and at most $max")
            }
            val duration = Duration.ofMillis((seconds * 1000.0).toLong())
            val remoteSources = sources.filter { remote?.ownsSource(it) == true }
            val localSources = sources.filter { it !in remoteSources }
            checkLocalSources(ctx, localSources, "shelve")
            if (localSources.isNotEmpty()) alarms.checkShelve(localSources, duration)
            if (remoteSources.isNotEmpty()) remote?.shelve(remoteSources, duration, remoteCaller(ctx))
            if (localSources.isNotEmpty()) alarms.shelve(localSources, duration, AuditContext.current()?.actor)
            json(ctx, mapOf("shelved" to sources))
        }
        app.post("/api/v1/alarms/unshelve") { ctx ->
            val body = objectBody(ctx)
            val sources = stringList(body, "sources")
            val remoteSources = sources.filter { remote?.ownsSource(it) == true }
            val localSources = sources.filter { it !in remoteSources }
            checkLocalSources(ctx, localSources, "unshelve")
            if (remoteSources.isNotEmpty()) remote?.unshelve(remoteSources, remoteCaller(ctx))
            if (localSources.isNotEmpty()) alarms.unshelve(localSources, AuditContext.current()?.actor)
            json(ctx, mapOf("unshelved" to sources))
        }
        app.get("/api/v1/alarms/journal") { ctx ->
            val page = journalRead { alarms.journal.query(journalQuery(ctx).copy(sourceAllowed = readableSources(ctx))) }
            json(ctx, linkedMapOf(
                "events" to page.records.map { it.toJson() },
                "total" to page.total,
                "offset" to page.offset,
                "limit" to page.limit,
            ))
        }
        app.get("/api/v1/alarms/journal/settings") { ctx -> json(ctx, journalSettings()) }
        // Review-3 F5: journal write health. Rows waiting (including a batch being retried), failed attempts, rows lost.
        app.get(JOURNAL_DIAGNOSTICS) { ctx -> json(ctx, alarms.journal.stats()) }
        app.put("/api/v1/alarms/journal/settings") { ctx ->
            val body = objectBody(ctx)
            val unknown = body.keys - setOf("maxAgeDays", "connection")
            if (unknown.isNotEmpty()) throw Problems.badRequest("unknown fields: ${unknown.sorted().joinToString()}")
            if (!body.containsKey("maxAgeDays") && !body.containsKey("connection")) {
                throw Problems.badRequest("maxAgeDays is required (a positive number, or null to keep rows forever)")
            }
            // DAT-015: the database connection for the journal rows; null or empty is the journal's own file.
            if (body.containsKey("connection")) {
                val store = storage ?: throw Problems.badRequest("journal storage cannot be changed on this gateway")
                val connection = when (val c = body["connection"]) {
                    null -> null
                    is String -> c.trim().ifEmpty { null }
                    else -> throw Problems.badRequest("connection must be a database connection name or null")
                }
                if (connection != null && !CONNECTION_NAME.matches(connection)) throw Problems.badRequest("connection '$connection' is not a valid connection name")
                val before = store.connection()
                if (before != connection) {
                    store.save(connection, AuditContext.current()?.actor ?: "system")
                    audit(STORAGE_ACTION, "connection=${before ?: "built-in"}->${connection ?: "built-in"}")
                }
                if (!body.containsKey("maxAgeDays")) return@put json(ctx, journalSettings())
            }
            val raw = body["maxAgeDays"]
            val age = if (raw == null) null else {
                val days = number(raw) ?: throw Problems.badRequest("maxAgeDays must be a number or null")
                if (days < 0 || days.isNaN() || days > MAX_AGE_DAYS) throw Problems.badRequest("maxAgeDays must be between 0 and $MAX_AGE_DAYS")
                Duration.ofMillis((days * DAY_MILLIS).toLong())
            }
            val before = alarms.journal.maxAge
            alarms.journal.setMaxAge(age)
            audit(RETENTION_ACTION, "maxAgeDays=${days(before)}->${days(alarms.journal.maxAge)}")
            json(ctx, journalSettings())
        }
        app.post("/api/v1/alarms/journal/purge") { ctx ->
            val body = if (ctx.body().isBlank()) emptyMap() else objectBody(ctx)
            val cutoff = when (val raw = body["olderThan"]) {
                null -> {
                    val age = alarms.journal.maxAge ?: throw Problems.badRequest("olderThan is required when retention is off")
                    clock.instant().minus(age)
                }
                is String -> instant(raw, "olderThan")
                else -> throw Problems.badRequest("olderThan must be an ISO-8601 instant")
            }
            val deleted = journalRead { alarms.journal.purgeOlderThan(cutoff) }
            audit(PURGE_ACTION, "olderThan=$cutoff;deleted=$deleted")
            json(ctx, linkedMapOf("deleted" to deleted, "olderThan" to cutoff.toString()))
        }
    }

    /**
     * Whether the caller may see an alarm with a given source: it may read the source's tag. One answer per tag
     * within the request. A source not in the `prov:…:/tag:…:/alm:…` form names no tag, so nothing restricts it.
     */
    private fun remoteCaller(ctx: io.javalin.http.Context): RemoteCaller {
        val zones = security?.zones ?: return RemoteCaller(user = AuditContext.current()?.actor ?: "")
        val caller = GatewayAuth.tagCaller(ctx, zones, security.authorization)
        val user = caller as? TagCaller.User ?: return RemoteCaller(user = AuditContext.current()?.actor ?: "")
        return RemoteCaller(user = user.actor, roles = user.roles, zone = user.zone)
    }

    private fun readableSources(ctx: io.javalin.http.Context): (String) -> Boolean {
        val engine = tags ?: return { true }
        val zones = security?.zones ?: return { true }
        val caller: TagCaller = GatewayAuth.tagCaller(ctx, zones, security.authorization)
        val seen = HashMap<String, Boolean>()
        return { source ->
            val path = alarmSourceTagPath(source)
            path == null || seen.getOrPut(path) { engine.mayRead(path, caller) }
        }
    }

    /**
     * Review-3 F2 (TAG-013): acknowledging, shelving or unshelving an alarm needs both read and write access to its
     * source tag ([TagEngine.mayRead] and [TagEngine.mayWrite] with the caller's roles and zone), on top of the
     * route's `tags.write`. A source not in the `prov:…:/tag:…:/alm:…` form names no tag, so nothing restricts it.
     */
    private fun actionAllowed(ctx: io.javalin.http.Context): (String) -> Boolean {
        val engine = tags ?: return { true }
        val zones = security?.zones ?: return { true }
        val caller: TagCaller = GatewayAuth.tagCaller(ctx, zones, security.authorization)
        val seen = HashMap<String, Boolean>()
        return { source ->
            val path = alarmSourceTagPath(source)
            path == null || seen.getOrPut(path) { engine.mayRead(path, caller) && engine.mayWrite(path, caller) }
        }
    }

    /**
     * Permission first, then existence: a source on a tag the caller may not use is refused with 403 whether or not
     * the alarm exists (an unconfigured tag fails closed in [TagEngine.mayRead]), so the refusal reveals nothing.
     */
    private fun checkLocalSources(ctx: io.javalin.http.Context, sources: List<String>, verb: String) {
        val mayAct = actionAllowed(ctx)
        for (source in sources) {
            if (!mayAct(source)) throw Problems.forbidden("not permitted to $verb alarm $source")
            if (!alarms.hasSource(source)) throw Problems.notFound("alarm $source not found")
        }
    }

    private fun journalQuery(ctx: io.javalin.http.Context): JournalQuery {
        val types = ctx.queryParams("eventType").flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val codes = if (types.isEmpty()) null else types.map { name ->
            JournalEventTypes.fromName(name) ?: throw Problems.badRequest("unknown eventType '$name' (active, clear, ack, shelve, unshelve)")
        }.toSet()
        val order = ctx.queryParam("order")?.trim()?.lowercase()
        if (order != null && order != "asc" && order != "desc") throw Problems.badRequest("order must be asc or desc")
        val start = ctx.queryParam("start")?.let { instant(it, "start") }
        val end = ctx.queryParam("end")?.let { instant(it, "end") }
        if (start != null && end != null && end.isBefore(start)) throw Problems.badRequest("end must not be before start")
        val min = priority(ctx.queryParam("minPriority"), "minPriority")
        val max = priority(ctx.queryParam("maxPriority"), "maxPriority")
        if (min != null && max != null && max < min) throw Problems.badRequest("maxPriority must not be below minPriority")
        val offset = ctx.queryParam("offset")?.let { it.toIntOrNull() ?: throw Problems.badRequest("offset must be an integer") } ?: 0
        if (offset < 0) throw Problems.badRequest("offset must not be negative")
        val limit = ctx.queryParam("limit")?.let { it.toIntOrNull() ?: throw Problems.badRequest("limit must be an integer") } ?: 100
        if (limit !in 1..org.oip.alarm.AlarmJournal.MAX_PAGE) throw Problems.badRequest("limit must be 1..${org.oip.alarm.AlarmJournal.MAX_PAGE}")
        return JournalQuery(
            start = start,
            end = end,
            source = ctx.queryParam("source"),
            displayPath = ctx.queryParam("displayPath"),
            minPriority = min,
            maxPriority = max,
            eventTypes = codes,
            offset = offset,
            limit = limit,
            newestFirst = order != "asc",
        )
    }

    private fun priority(raw: String?, name: String): AlarmPriority? {
        if (raw == null) return null
        raw.trim().toIntOrNull()?.let { code -> return AlarmPriority.entries.getOrNull(code) ?: throw Problems.badRequest("unknown $name '$raw'") }
        return AlarmPriority.fromName(raw) ?: throw Problems.badRequest("unknown $name '$raw'")
    }

    private fun instant(raw: String, name: String): Instant = try {
        Instant.parse(raw.trim())
    } catch (_: DateTimeParseException) {
        throw Problems.badRequest("$name must be an ISO-8601 instant such as 2026-01-01T00:00:00Z")
    }

    private fun journalSettings(): Map<String, Any?> = linkedMapOf(
        "maxAgeDays" to days(alarms.journal.maxAge),
        "connection" to (storage?.connection() ?: alarms.journal.connection),
    )

    /** A journal read on a missing or faulted connection (DAT-015) is a 503 that names the connection. */
    private fun <T> journalRead(block: () -> T): T = try {
        block()
    } catch (e: org.oip.alarm.JournalUnavailable) {
        throw ProblemException(503, "journal-unavailable", "Service Unavailable", e.message ?: "alarm journal is unavailable")
    }

    private fun days(age: Duration?): Double? = age?.let { it.toMillis() / DAY_MILLIS }

    /** One success row per retention change or purge. Audit errors do not undo the change. */
    private fun audit(action: String, detail: String) {
        val sec = security ?: return
        try {
            if (!sec.auditProfile.get().enabled) return
            val origin = AuditContext.current()
            sec.audit.append(
                AuditDraft(
                    timestampMillis = clock.millis(),
                    actor = origin?.actor ?: "system",
                    action = action,
                    status = AuditStatus.SUCCESS,
                    actorHost = origin?.host ?: "",
                    target = "alarms/journal",
                    detail = detail,
                    context = origin?.context ?: "",
                )
            )
        } catch (e: Exception) {
            System.getLogger(AlarmApi::class.java.name).log(System.Logger.Level.WARNING, "audit record for $action was not written: ${e.message}")
        }
    }

    private fun json(ctx: io.javalin.http.Context, value: Any?) {
        ctx.contentType("application/json").result(Json.write(value))
    }

    private fun objectBody(ctx: io.javalin.http.Context): Map<String, Any?> {
        val parsed = try {
            Json.parse(ctx.body())
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        return parsed
    }

    private fun stringList(body: Map<String, Any?>, name: String): List<String> {
        val raw = body[name] as? List<*> ?: throw Problems.badRequest("$name is required")
        if (raw.isEmpty()) throw Problems.badRequest("$name is required")
        return raw.map { it as? String ?: throw Problems.badRequest("$name must be strings") }
    }

    private fun number(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}

/** Alarm journal write health (review-3 F5). gateway.read, like the other diagnostics. */
const val JOURNAL_DIAGNOSTICS = "/api/v1/diagnostics/alarms/journal"
private const val DAY_MILLIS = 86_400_000.0
private const val MAX_AGE_DAYS = 36_500.0
private const val PURGE_ACTION = "alarm journal purged"
private const val RETENTION_ACTION = "alarm journal retention updated"
private const val STORAGE_ACTION = "alarm journal storage updated"
private val CONNECTION_NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/**
 * One shelved alarm. With a live event it carries every live-alarm field plus the shelf fields; without
 * one, the event fields (`eventId`, `state`, times) are null and `conditionActive` says whether the
 * condition is currently true.
 */
private fun ShelvedAlarmView.toJson(): Map<String, Any?> {
    val base: MutableMap<String, Any?> = event?.toJson()?.toMutableMap() ?: linkedMapOf(
        "eventId" to null,
        "source" to source,
        "name" to name,
        "displayPath" to displayPath,
        "tagPath" to tagPath,
        "priority" to priority.label(),
        "priorityCode" to priority.code,
        "state" to null,
        "stateCode" to null,
        "active" to null,
        "acknowledged" to null,
    )
    base["shelved"] = true
    base["shelvedAt"] = shelvedAt?.toString()
    base["shelvedUntil"] = shelvedUntil.toString()
    base["shelvedBy"] = shelvedBy
    base["conditionActive"] = conditionActive
    return base
}

private fun JournalRecord.toJson(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "eventId" to eventId,
    "source" to source,
    "displayPath" to displayPath,
    "priority" to (AlarmPriority.entries.getOrNull(priority)?.label() ?: priority.toString()),
    "priorityCode" to priority,
    "eventType" to JournalEventTypes.name(eventType),
    "eventTypeCode" to eventType,
    "eventFlags" to eventFlags,
    "eventTime" to Instant.ofEpochMilli(eventTimeMillis).toString(),
    "actor" to actor,
    "data" to data,
)

private fun org.oip.alarm.AlarmEventView.toJson(): Map<String, Any?> = linkedMapOf(
    "eventId" to eventId,
    "source" to source,
    "name" to name,
    "displayPath" to displayPath,
    "tagPath" to tagPath,
    "priority" to priority,
    "priorityCode" to priorityCode,
    "state" to state,
    "stateCode" to stateCode,
    "active" to active,
    "acknowledged" to acknowledged,
    "value" to value,
    "activeTime" to activeTime.toString(),
    "clearTime" to clearTime?.toString(),
    "ackTime" to ackTime?.toString(),
    "ackUser" to ackUser,
    "ackNotes" to ackNotes,
    "setpointA" to setpointA,
    "setpointB" to setpointB,
    "notes" to notes,
    "associatedData" to associatedData,
)
