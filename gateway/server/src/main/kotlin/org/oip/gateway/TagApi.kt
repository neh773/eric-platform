package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.FileConfigStore
import org.oip.security.AuditContext
import org.oip.security.ZoneDirectory
import org.oip.tags.CollisionPolicy
import org.oip.tags.DeadbandDiagnostics
import org.oip.tags.NamePattern
import org.oip.tags.NodeKind
import org.oip.tags.PathException
import org.oip.tags.PlannedRow
import org.oip.tags.StandardTagProvider
import org.oip.tags.TagDiagnostics
import org.oip.tags.TagEngine
import org.oip.tags.TagGroupDiagnostics
import org.oip.tags.TagLink
import org.oip.tags.TagPath
import org.oip.tags.TagPathPattern
import org.oip.tags.UdtInstancePlanner
import org.oip.tags.UdtInstanceRequest
import org.oip.tags.UdtInstanceResult
import org.oip.tags.UdtInstanceRow

/**
 * Tag value reads, writes and tree browsing, as the authenticated caller (TAG-013).
 *
 * `POST /api/v1/tags/read` with `{ "paths": ["[default]folder/tag", ...] }` returns one row per path, in order,
 * with value, quality, and timestamp. A tag whose read permissions do not admit the caller's roles and zone is
 * returned with a null value and quality `Bad_AccessDenied`, as over OPC UA. History queries ([HistoryApi]),
 * alarm status and journal queries ([AlarmApi]), report history sources and OPC UA attribute reads apply the
 * same check ([TagEngine.mayRead]).
 *
 * `POST /api/v1/tags/write` with `{ "writes": [{ "path": "...", "value": ... }, ...] }` writes each value in order
 * and returns one row per write with the resulting quality. A tag whose write permissions do not admit the caller
 * is left unchanged and reported as `Bad_AccessDenied`; a read-only tag is `Bad_NotWritable` for every caller.
 * Each write, accepted or rejected, appends one audit record (actor, host, tag, old and new value, or the
 * rejection) while the audit profile is enabled.
 *
 * `POST /api/v1/tags/browse` with `{ "path"?: "[default]folder", "offset"?: 0, "limit"?: 500 }` lists one page of
 * the immediate children of a folder or UDT path (the Designer tag browser panel; see [TagEngine.browse]).
 * Every caller sees the structure (names, paths, kinds). A row carries the tag's data type, config resource, current
 * value, quality and timestamp only when the tag's read permissions admit the caller; otherwise it has
 * `readable: false` and none of those.
 *
 * The route rules (GatewayAuth) ask for `tags.read` (read, browse) and `tags.write`; the per-tag check comes on top.
 * - (Security)
 * Tag browser behaviour: -
 *
 * TAG-024 UDT wizard: `GET /api/v1/tags/udts` lists UDT definitions with inherited parameters
 * (tags.read). `POST /api/v1/tags/udt-instances` creates many instances of one UDT in one config batch
 * (tags.write, enforced by GatewayAuth.accessFor). With `dryRun` it returns the plan and writes nothing.
 * A request with any row error is 422 and creates nothing.
 *
 * `GET /api/v1/tags/diagnostics?path=[default]folder/tag` returns one tag's diagnostics (TAG-003): value,
 * quality, timestamp, subscriber count, references in and out, tag group execution counters and deadband
 * counters. Requires tags.read. A tag the caller may not read comes back with a null value and quality
 * `Bad_AccessDenied`.
 */
class TagApi(
    private val engine: TagEngine,
    private val zones: ZoneDirectory,
    private val planner: UdtInstancePlanner,
    private val store: FileConfigStore,
) {
    fun register(app: Javalin) {
        app.post("/api/v1/tags/read") { ctx -> read(ctx) }
        app.post("/api/v1/tags/write") { ctx -> write(ctx) }
        app.post("/api/v1/tags/browse") { ctx -> browse(ctx) }
        app.post("/api/v1/tags/search") { ctx -> search(ctx) }
        app.get(DIAGNOSTICS) { ctx -> diagnostics(ctx) }
        app.get("/api/v1/tags/udts") { ctx ->
            val name = ctx.queryParam("provider")?.trim()?.ifEmpty { null } ?: "default"
            val provider = engine.provider(name) as? StandardTagProvider ?: throw Problems.notFound("tag provider '$name' not found")
            val types = provider.udtTypes().map { t ->
                linkedMapOf<String, Any?>(
                    "path" to t.path,
                    "memberCount" to t.memberCount,
                    "parameters" to t.parameters.map { p ->
                        linkedMapOf("name" to p.name, "dataType" to p.dataType.label(), "default" to p.defaultValue)
                    },
                ).also { if (t.error != null) it["error"] = t.error }
            }
            ctx.contentType("application/json").result(Json.write(mapOf("provider" to provider.name, "types" to types)))
        }
        app.post("/api/v1/tags/udt-instances") { ctx ->
            val body = jsonObject(ctx)
            val dryRun = when (val v = body["dryRun"]) {
                null -> false
                is Boolean -> v
                else -> throw Problems.unprocessable("dryRun must be a boolean")
            }
            val request = parseRequest(body)
            val result = planner.execute(request, dryRun, AuditContext.current()?.actor ?: ConfigApi.ACTOR)
            val doc = document(result, dryRun)
            if (!result.plan.ok) {
                throw ProblemException(
                    422, "validation", "Unprocessable Content",
                    "nothing was created: ${result.plan.messages().size} problem(s) in the request",
                    result.plan.messages(), doc,
                )
            }
            ctx.status(if (result.applied && result.created > 0) 201 else 200)
                .contentType("application/json").result(Json.write(doc))
        }
    }

    private fun read(ctx: Context) {
        val map = parse(ctx)
        val raw = map["paths"] as? List<*> ?: throw Problems.unprocessable("paths must be an array of tag paths")
        if (raw.size > MAX_PATHS) throw Problems.unprocessable("paths exceeds $MAX_PATHS")
        val paths = raw.map { entry ->
            entry as? String ?: throw Problems.unprocessable("paths must be strings")
        }
        val caller = GatewayAuth.tagCaller(ctx, zones)
        val values = engine.readAll(paths, caller).mapIndexed { i, qv ->
            linkedMapOf<String, Any?>(
                "path" to paths[i],
                "value" to qv.value,
                "quality" to qv.quality.code.label,
                "timestamp" to qv.timestamp.toString(),
            ).also { row ->
                qv.sourceTimestamp?.let { row["sourceTimestamp"] = it.toString() }
            }
        }
        ctx.contentType("application/json").result(Json.write(mapOf("values" to values)))
    }

    private fun write(ctx: Context) {
        val map = parse(ctx)
        val raw = map["writes"] as? List<*> ?: throw Problems.unprocessable("writes must be an array of {path, value}")
        if (raw.size > MAX_WRITES) throw Problems.unprocessable("writes exceeds $MAX_WRITES")
        val writes = raw.map { entry ->
            val item = entry as? Map<*, *> ?: throw Problems.unprocessable("each write must be an object")
            val path = item["path"] as? String ?: throw Problems.unprocessable("each write needs a string path")
            if (!item.containsKey("value")) throw Problems.unprocessable("each write needs a value")
            path to item["value"]
        }
        val caller = GatewayAuth.tagCaller(ctx, zones)
        val results = writes.map { (path, value) ->
            val result = engine.write(path, value, caller)
            linkedMapOf<String, Any?>(
                "path" to path,
                "quality" to result.quality.code.label,
                "ok" to result.ok,
            ).also { row -> result.quality.diagnostic?.let { row["message"] = it } }
        }
        ctx.contentType("application/json").result(Json.write(mapOf("results" to results)))
    }

    /**
     * One page of a folder's immediate children. `more` is true when another page exists; the next
     * request passes `offset + nodes.size`. Paging walks the engine's child list without copying the
     * rest of the folder, so a 100,000-child folder is not serialized in one response.
     */
    private fun browse(ctx: Context) {
        val map = parse(ctx)
        val rawPath = map["path"]
        if (rawPath != null && rawPath !is String) throw Problems.unprocessable("path must be a string")
        val path = (rawPath as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: "[default]"
        if (path.length > MAX_PATH_LENGTH) throw Problems.unprocessable("path exceeds $MAX_PATH_LENGTH characters")
        val offset = intField(map, "offset", 0)
        val limit = intField(map, "limit", DEFAULT_BROWSE_LIMIT)
        if (offset < 0) throw Problems.unprocessable("offset must not be negative")
        if (limit !in 1..MAX_BROWSE_LIMIT) throw Problems.unprocessable("limit must be between 1 and $MAX_BROWSE_LIMIT")
        // One extra row tells the client whether another page exists. Providers other than the standard
        // one ignore offset/limit (TagEngine.browse), so the page is cut here as well.
        val caller = GatewayAuth.tagCaller(ctx, zones)
        val fetched = try {
            engine.browse(path, offset, limit + 1, caller)
        } catch (e: IllegalArgumentException) {
            throw Problems.badRequest(e.message ?: "invalid path")
        }
        val more = fetched.size > limit
        val rows = fetched.take(limit).map { node ->
            linkedMapOf<String, Any?>(
                "name" to node.name,
                "path" to node.path.toString(),
                "kind" to node.kind.name.lowercase(),
                "hasChildren" to (node.kind != NodeKind.TAG),
            ).also { row ->
                // A tag the caller may not read (TagEngine.mayRead) is listed by name and kind only: the engine has
                // already dropped its data type and resource, and its value, quality and timestamp are left out.
                if (!node.readable) {
                    row["readable"] = false
                    return@also
                }
                node.dataType?.let { row["dataType"] = it.name.lowercase() }
                // The Tag config resource behind the row, so the admin tag editor can open it (C-16).
                node.resource?.let { row["resource"] = it }
                node.value?.let { qv ->
                    row["value"] = qv.value
                    row["quality"] = qv.quality.code.label
                    row["timestamp"] = qv.timestamp.toString()
                }
            }
        }
        ctx.contentType("application/json").result(Json.write(mapOf(
            "path" to path,
            "offset" to offset,
            "nodes" to rows,
            "more" to more,
        )))
    }

    /**
     * Tag report (TAG-015, doc source: FEATURES TAG-015): tags whose path matches a glob and whose data type, quality
     * and name match the filters, one table row each. Every filter is optional and they combine with AND.
     * `path` is a glob (two stars cross folders, one star stays in a segment), `nameContains` is a case-insensitive
     * substring of the tag name, `dataType` a data type name, `quality` a quality label or its prefix
     * (`Good`, `Bad_NotConnected`). The walk is bounded by depth, the number of nodes visited and `limit`; `truncated`
     * says a bound cut the result. A tag the caller may not read is never listed, so a search does not reveal it.
     */
    private fun search(ctx: Context) {
        val map = parse(ctx)
        fun text(name: String): String? {
            val v = map[name] ?: return null
            if (v !is String) throw Problems.unprocessable("$name must be a string")
            if (v.length > MAX_PATH_LENGTH) throw Problems.unprocessable("$name exceeds $MAX_PATH_LENGTH characters")
            return v.trim().ifEmpty { null }
        }
        val pattern = text("path") ?: "[default]**"
        val nameContains = text("nameContains")?.lowercase()
        val dataType = text("dataType")?.lowercase()
        val quality = text("quality")?.lowercase()
        val limit = intField(map, "limit", DEFAULT_SEARCH_LIMIT)
        if (limit !in 1..MAX_SEARCH_LIMIT) throw Problems.unprocessable("limit must be between 1 and $MAX_SEARCH_LIMIT")
        val caller = GatewayAuth.tagCaller(ctx, zones)
        val provider = if (pattern.startsWith("[") && "]" in pattern) pattern.substring(1, pattern.indexOf(']')).ifBlank { "default" } else "default"
        val relative = if (pattern.startsWith("[") && "]" in pattern) pattern.substringAfter(']') else pattern
        val literal = relative.substringBefore('*').substringBefore('?')
        val prefix = literal.substringBeforeLast('/', "").trim('/')
        val start = if (prefix.isEmpty()) "[$provider]" else "[$provider]$prefix"
        val rows = ArrayList<Map<String, Any?>>()
        var visited = 0
        var truncated = false
        fun walk(path: String, depth: Int) {
            if (truncated) return
            if (depth > SEARCH_MAX_DEPTH) { truncated = true; return }
            val nodes = try {
                engine.browse(path, 0, SEARCH_PAGE, caller)
            } catch (e: IllegalArgumentException) {
                throw Problems.badRequest(e.message ?: "invalid path")
            }
            for (node in nodes) {
                if (truncated) return
                if (++visited > SEARCH_MAX_VISITS) { truncated = true; return }
                val full = node.path.toString()
                if (node.kind == NodeKind.TAG || node.kind == NodeKind.UDT_INSTANCE) {
                    if (node.readable && TagPathPattern.matches(pattern, full)) {
                        val q = node.value?.quality?.code?.label
                        val type = node.dataType?.name?.lowercase()
                        if ((nameContains == null || node.name.lowercase().contains(nameContains)) &&
                            (dataType == null || type == dataType) &&
                            (quality == null || q?.lowercase()?.startsWith(quality) == true)
                        ) {
                            if (rows.size >= limit) { truncated = true; return }
                            rows += linkedMapOf(
                                "name" to node.name, "path" to full, "kind" to node.kind.name.lowercase(),
                                "dataType" to type, "value" to node.value?.value, "quality" to q,
                                "timestamp" to node.value?.timestamp?.toString(),
                            )
                        }
                    }
                }
                if (node.kind == NodeKind.FOLDER || node.kind == NodeKind.UDT_INSTANCE) walk(full, depth + 1)
            }
        }
        walk(start, 0)
        ctx.contentType("application/json").result(Json.write(mapOf(
            "columns" to listOf("name", "path", "kind", "dataType", "value", "quality", "timestamp"),
            "rows" to rows, "truncated" to truncated,
        )))
    }

    /**
     * One tag. View bindings are read from designer View resources at request time and listed with
     * kind `binding`; expression, derived and reference tags come from the engine.
     */
    private fun diagnostics(ctx: Context) {
        val path = ctx.queryParam("path")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw Problems.unprocessable("path is required")
        if (path.length > MAX_PATH_LENGTH) throw Problems.unprocessable("path exceeds $MAX_PATH_LENGTH characters")
        val caller = GatewayAuth.tagCaller(ctx, zones)
        val parsed = try {
            TagPath.parse(path)
        } catch (e: PathException) {
            throw Problems.badRequest(e.message ?: "invalid path")
        }
        val diag = try {
            engine.diagnostics(path, caller)
        } catch (e: PathException) {
            throw Problems.badRequest(e.message ?: "invalid path")
        } catch (e: IllegalArgumentException) {
            throw Problems.badRequest(e.message ?: "invalid path")
        } ?: throw Problems.notFound("tag '$path' not found")
        val visible = engine.mayRead(path, caller)
        val withBindings = if (!visible) diag else {
            val bindings = TagBindingScan.referencing(store, parsed)
            if (bindings.isEmpty()) diag
            else diag.copy(referencesIn = (diag.referencesIn + bindings).sortedBy { it.path })
        }
        ctx.contentType("application/json").result(Json.write(document(withBindings)))
    }

    private fun document(d: TagDiagnostics): Map<String, Any?> = linkedMapOf(
        "path" to d.path,
        "type" to d.type,
        "value" to d.value,
        "quality" to d.quality,
        "timestamp" to d.timestamp.toString(),
        "sourceTimestamp" to d.sourceTimestamp?.toString(),
        "message" to d.message,
        "subscriberCount" to d.subscriberCount,
        "referencesIn" to d.referencesIn.map { linkJson(it) },
        "referencesOut" to d.referencesOut.map { linkJson(it) },
        "tagGroup" to groupJson(d.tagGroup),
        "deadband" to deadbandJson(d.deadband),
    )

    private fun linkJson(link: TagLink) = linkedMapOf("path" to link.path, "kind" to link.kind)

    private fun groupJson(group: TagGroupDiagnostics) = linkedMapOf(
        "name" to group.name,
        "mode" to group.mode,
        "rateMillis" to group.rateMillis,
        "slowRateMillis" to group.slowRateMillis,
        "drivingExpression" to group.drivingExpression,
        "valid" to group.valid,
        "lastExecution" to group.lastExecution?.toString(),
        "durationMillis" to group.durationMillis,
        "overruns" to group.overruns,
    )

    private fun deadbandJson(deadband: DeadbandDiagnostics) = linkedMapOf(
        "mode" to deadband.mode,
        "limit" to deadband.limit,
        "evaluated" to deadband.evaluated,
        "published" to deadband.published,
    )

    private fun intField(map: Map<*, *>, name: String, default: Int): Int {
        val raw = map[name] ?: return default
        val n = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is java.math.BigInteger -> if (raw.bitLength() < 63) raw.toLong() else Long.MAX_VALUE
            else -> throw Problems.unprocessable("$name must be an integer")
        }
        if (n < Int.MIN_VALUE || n > Int.MAX_VALUE) throw Problems.unprocessable("$name is out of range")
        return n.toInt()
    }

    private fun parse(ctx: Context): Map<*, *> {
        val body = try {
            Json.mapper.readValue(ctx.body(), Any::class.java)
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            throw Problems.badRequest("malformed JSON: ${e.originalMessage}")
        }
        return body as? Map<*, *> ?: throw Problems.unprocessable("body must be an object")
    }

    private fun parseRequest(body: Map<String, Any?>): UdtInstanceRequest {
        val typeId = body["typeId"] as? String ?: throw Problems.unprocessable("typeId is required")
        val provider = optionalText(body, "provider") ?: "default"
        val folder = optionalText(body, "folder") ?: ""
        val policy = try {
            CollisionPolicy.parse(optionalText(body, "onCollision"))
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable(e.message!!)
        }
        val pattern = when (val p = body["pattern"]) {
            null -> null
            is Map<*, *> -> NamePattern(
                template = p["template"] as? String ?: throw Problems.unprocessable("pattern.template is required"),
                start = intOf(p["start"], "pattern.start") ?: 1,
                count = intOf(p["count"], "pattern.count") ?: throw Problems.unprocessable("pattern.count is required"),
                step = intOf(p["step"], "pattern.step") ?: 1,
                pad = intOf(p["pad"], "pattern.pad") ?: 0,
            )
            else -> throw Problems.unprocessable("pattern must be an object")
        }
        val rawRows: List<*> = when (val r = body["rows"]) {
            null -> emptyList<Any?>()
            is List<*> -> r
            else -> throw Problems.unprocessable("rows must be an array")
        }
        if (rawRows.size > UdtInstancePlanner.MAX_ROWS) throw Problems.unprocessable("rows exceeds ${UdtInstancePlanner.MAX_ROWS}")
        val rows = rawRows.mapIndexed { i, raw ->
            val row = raw as? Map<*, *> ?: throw Problems.unprocessable("rows[$i] must be an object")
            val name = when (val n = row["name"]) {
                null -> null
                is String -> n
                else -> throw Problems.unprocessable("rows[$i].name must be a string")
            }
            val params = when (val p = row["parameters"]) {
                null -> emptyMap()
                is Map<*, *> -> p.entries.associate { it.key.toString() to it.value }
                else -> throw Problems.unprocessable("rows[$i].parameters must be an object")
            }
            UdtInstanceRow(name, params)
        }
        return UdtInstanceRequest(typeId, rows, provider, folder, pattern, policy)
    }

    private fun document(result: UdtInstanceResult, dryRun: Boolean): Map<String, Any?> {
        val plan = result.plan
        return linkedMapOf(
            "dryRun" to dryRun,
            "applied" to result.applied,
            "typeId" to plan.request.typeId,
            "provider" to plan.request.provider,
            "folder" to plan.request.folder,
            "onCollision" to plan.request.onCollision.label,
            "created" to if (result.applied) result.created else plan.toCreate.size,
            "skipped" to plan.skipped,
            "requestErrors" to plan.errors,
            "rows" to plan.rows.map { rowDocument(it) },
        )
    }

    private fun rowDocument(row: PlannedRow): Map<String, Any?> = linkedMapOf<String, Any?>(
        "index" to row.index,
        "requestedName" to row.requestedName,
        "name" to row.name,
        "path" to row.path,
        "resource" to row.resourceName,
        "status" to row.status.label,
        "errors" to row.errors,
        "parameters" to row.effectiveParameters,
    ).also { if (row.note != null) it["note"] = row.note }

    private fun optionalText(body: Map<String, Any?>, key: String): String? = when (val v = body[key]) {
        null -> null
        is String -> v
        else -> throw Problems.unprocessable("$key must be a string")
    }

    private fun intOf(v: Any?, key: String): Int? = when (v) {
        null -> null
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else throw Problems.unprocessable("$key is out of range")
        is Number -> if (v.toDouble() % 1.0 == 0.0) v.toInt() else throw Problems.unprocessable("$key must be an integer")
        else -> throw Problems.unprocessable("$key must be an integer")
    }

    private fun jsonObject(ctx: Context): Map<String, Any?> =
        parse(ctx).entries.associate { it.key.toString() to it.value }

    companion object {
        const val MAX_PATHS = 100_000
        const val MAX_WRITES = 10_000
        const val DEFAULT_BROWSE_LIMIT = 500
        const val MAX_BROWSE_LIMIT = 10_000
        const val DEFAULT_SEARCH_LIMIT = 500
        const val MAX_SEARCH_LIMIT = 5_000
        private const val SEARCH_MAX_DEPTH = 32
        private const val SEARCH_MAX_VISITS = 50_000
        private const val SEARCH_PAGE = 10_000
        const val MAX_PATH_LENGTH = 4_096
        const val DIAGNOSTICS = "/api/v1/tags/diagnostics"
    }
}
