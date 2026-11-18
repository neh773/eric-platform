package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.*
import org.oip.security.AuditContext

/**
 * Config resource CRUD over the ConfigStore (SPEC §5): `/api/v1/config/{module}/{scope}/{kind}/{name}` where
 * scope is `gateway` or `project/{project}` (or `mode/{mode}` for overlays). Collections omit `{name}`.
 * Security-module resources follow [SecurityResourceGuard]: credentials are never written here and never returned
 * with their hashes.
 *
 * A GET in a project scope with `?inherited=true` reads through project inheritance (PRO-002, [ProjectService]): the
 * collection lists the project's own resources plus every ancestor resource no nearer project overrides, and an item
 * is the nearest stored copy. Such a result carries `inheritedFrom` (the ancestor that stores it) when it is not the
 * project's own, and `scope` is where it is stored. Writes always go to the path's own scope, so a PUT of an
 * inherited resource into the child creates the child's override.
 */
class ConfigApi(
    private val store: FileConfigStore,
    private val guard: SecurityResourceGuard,
    private val projects: ProjectService? = null,
    private val limits: LimitGuard? = null,
) {
    private sealed interface Target
    private data class Item(val id: ResourceId) : Target
    private data class Collection(val module: String, val scope: Scope, val kind: String) : Target

    fun register(app: Javalin) {
        app.post("/api/v1/config/batch") { ctx -> batch(ctx) }
        app.get("/api/v1/config") { ctx -> list(ctx, ctx.queryParam("module"), ctx.queryParam("scope")?.let { scope(it) }, ctx.queryParam("kind")) }
        app.get("/api/v1/config/<path>") { ctx ->
            when (val t = target(ctx)) {
                is Collection -> {
                    guard.checkRead(ctx, t.module, t.kind)
                    guard.checkProjectRead(ctx, t.module, t.scope)
                    if (inherited(ctx, t.scope)) listInherited(ctx, t.module, t.scope, t.kind)
                    else list(ctx, t.module, t.scope, t.kind)
                }
                is Item -> {
                    guard.checkRead(ctx, t.id.module, t.id.kind)
                    guard.checkProjectRead(ctx, t.id.module, t.id.scope)
                    if (inherited(ctx, t.id.scope)) {
                        val found = projects!!.resolve(t.id) ?: throw Problems.notFound("${t.id} not found")
                        send(ctx, 200, found.resource, inheritedFrom(t.id.scope, found))
                    } else {
                        send(ctx, 200, store.get(t.id) ?: throw Problems.notFound("${t.id} not found"))
                    }
                }
            }
        }
        app.post("/api/v1/config/<path>") { ctx ->
            val t = target(ctx) as? Collection ?: throw ProblemException(405, "method-not-allowed", "Method Not Allowed", "POST to a collection path")
            guard.checkWrite(ctx, t.module, t.kind)
            val r = body(ctx)
            if (r.metadata.module != t.module || r.kind != t.kind) throw Problems.unprocessable("body module/kind must match path")
            val id = r.id(t.scope)
            if (store.get(id) != null) throw Problems.conflict("$id already exists")
            guard.checkProjectWrite(ctx, id, null, r.spec)
            limits?.checkCreate(t.module, t.kind)
            val stored = store.put(t.scope, r.copy(metadata = r.metadata.copy(version = 0)), actor())
            guard.projectWritten(ctx, id)
            ctx.header("Location", "${ctx.path().trimEnd('/')}/${r.metadata.name}")
            send(ctx, 201, stored)
        }
        app.put("/api/v1/config/<path>") { ctx ->
            val t = item(ctx)
            guard.checkWrite(ctx, t.id.module, t.id.kind)
            val r = body(ctx)
            if (r.id(t.id.scope) != t.id) throw Problems.unprocessable("body identity ${r.id(t.id.scope)} does not match path ${t.id}")
            val current = store.get(t.id)
            val existed = current != null
            guard.checkProjectWrite(ctx, t.id, current?.spec, r.spec)
            if (!existed) limits?.checkCreate(t.id.module, t.id.kind)
            val stored = store.put(t.id.scope, r, actor())
            guard.projectWritten(ctx, t.id)
            send(ctx, if (existed) 200 else 201, stored)
        }
        app.patch("/api/v1/config/<path>") { ctx ->
            val t = item(ctx)
            guard.checkWrite(ctx, t.id.module, t.id.kind)
            val cur = store.get(t.id) ?: throw Problems.notFound("${t.id} not found")
            val expected = ifMatch(ctx) ?: cur.metadata.version
            val patch = parse(ctx) as? Map<*, *> ?: throw Problems.unprocessable("merge patch must be an object")
            @Suppress("UNCHECKED_CAST") val spec = JsonMergePatch.apply(cur.spec, patch) as Map<String, Any?>
            guard.checkProjectWrite(ctx, t.id, cur.spec, spec)
            val stored = store.put(t.id.scope, cur.copy(metadata = cur.metadata.copy(version = expected), spec = spec), actor())
            guard.projectWritten(ctx, t.id)
            send(ctx, 200, stored)
        }
        app.delete("/api/v1/config/<path>") { ctx ->
            val t = item(ctx)
            guard.checkWrite(ctx, t.id.module, t.id.kind)
            guard.checkProjectWrite(ctx, t.id, store.get(t.id)?.spec, null)
            store.delete(t.id, ifMatch(ctx))
            guard.projectWritten(ctx, t.id)
            ctx.status(204)
        }
    }

    /**
     * Create or update many resources in one request. Body: `{ "scope"?: "gateway", "items": [ resource, ... ] }`.
     * Used for tag imports of 100k and up. Files are committed as a batch without a per-file fsync.
     */
    private fun batch(ctx: Context) {
        val body = parse(ctx) as? Map<*, *> ?: throw Problems.unprocessable("batch body must be an object")
        val scope = (body["scope"] as? String)?.let { scope(it) } ?: Scope.GATEWAY
        val items = body["items"] as? List<*> ?: throw Problems.unprocessable("items must be an array of resources")
        if (items.isEmpty()) throw Problems.unprocessable("items is empty")
        if (items.size > MAX_BATCH) throw Problems.unprocessable("items exceeds $MAX_BATCH")
        val resources = items.map { item ->
            if (item !is Map<*, *>) throw Problems.unprocessable("each item must be a resource object")
            @Suppress("UNCHECKED_CAST")
            ResourceCodec.fromMap(item as Map<String, Any?>)
        }
        for (key in resources.map { it.metadata.module to it.kind }.distinct()) guard.checkWrite(ctx, key.first, key.second)
        // SEC-020: a batch into a project scope is a project save, resource by resource.
        if (scope.type == "project") for (r in resources) guard.checkProjectWrite(ctx, r.id(scope), store.get(r.id(scope))?.spec, r.spec)
        for ((key, group) in resources.groupBy { it.metadata.module to it.kind }) {
            limits?.checkCreate(key.first, key.second, group.count { store.get(it.id(scope)) == null })
        }
        val result = store.putAll(scope, resources, actor(), syncEach = false)
        if (scope.type == "project") for (r in resources) guard.projectWritten(ctx, r.id(scope))
        ctx.status(200).contentType("application/json").result(Json.write(mapOf(
            "created" to result.created,
            "updated" to result.updated,
            "count" to result.stored.size,
        )))
    }

    private fun list(ctx: Context, module: String?, scope: Scope?, kind: String?) {
        val items = store.list(module, scope, kind)
            .filter { guard.mayListResource(ctx, it.id) }
            .sortedBy { it.id.toString() }
            .map { withScope(it.id, it.resource) }
        ctx.contentType("application/json").result(Json.write(mapOf("items" to items)))
    }
    private fun withScope(id: ResourceId, r: Resource) = SecurityResourceGuard.redact(id.module, ResourceCodec.toMap(r)) + ("scope" to id.scope.path)

    private fun inherited(ctx: Context, scope: Scope): Boolean {
        val raw = ctx.queryParam("inherited") ?: return false
        val on = when (raw.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw Problems.badRequest("inherited must be true or false")
        }
        if (on && scope.type != "project") throw Problems.unprocessable("inherited=true applies to a project scope")
        return on && projects != null
    }

    private fun inheritedFrom(scope: Scope, found: ProjectService.Resolved): String? =
        found.project.takeIf { it != scope.qualifier }

    private fun listInherited(ctx: Context, module: String, scope: Scope, kind: String) {
        val items = projects!!.resolveList(scope.qualifier!!, module, kind)
            .filter { guard.mayListResource(ctx, it.id) }
            .map { found ->
                val base = withScope(found.id, found.resource)
                inheritedFrom(scope, found)?.let { base + ("inheritedFrom" to it) } ?: base
            }
        ctx.contentType("application/json").result(Json.write(mapOf("items" to items)))
    }

    private fun send(ctx: Context, status: Int, r: Resource, inheritedFrom: String? = null) {
        val body = SecurityResourceGuard.redact(r.metadata.module, ResourceCodec.toMap(r))
        ctx.status(status).header("ETag", "\"${r.metadata.version}\"").contentType("application/json")
            .result(Json.write(if (inheritedFrom == null) body else body + ("inheritedFrom" to inheritedFrom)))
    }

    private fun ifMatch(ctx: Context): Long? = ctx.header("If-Match")?.trim()?.removePrefix("W/")?.trim('"')?.let {
        it.toLongOrNull() ?: throw Problems.badRequest("If-Match must be a resource version") }

    private fun parse(ctx: Context): Any? = try { Json.mapper.readValue(ctx.body(), Any::class.java) }
        catch (e: com.fasterxml.jackson.core.JacksonException) { throw Problems.badRequest("malformed JSON: ${e.originalMessage}") }
    private fun body(ctx: Context): Resource = ResourceCodec.fromMap(parse(ctx))

    private fun item(ctx: Context) = target(ctx) as? Item ?: throw ProblemException(405, "method-not-allowed", "Method Not Allowed", "${ctx.method()} requires a resource path")

    private fun scope(s: String) = try { Scope.parse(s) } catch (e: IllegalArgumentException) { throw Problems.notFound(e.message!!) }

    private fun target(ctx: Context): Target {
        val seg = ctx.pathParam("path").trim('/').split('/')
        try {
            if (seg.size < 3) throw Problems.notFound("not a config path")
            val scopeLen = if (seg[1] == "gateway") 1 else 2
            val scope = scope(seg.subList(1, minOf(seg.size, 1 + scopeLen)).joinToString("/"))
            val rest = seg.drop(1 + scopeLen)
            return when (rest.size) {
                1 -> Collection(seg[0], scope, rest[0]).also { ResourceId(it.module, scope, it.kind, "x") }
                2 -> Item(ResourceId(seg[0], scope, rest[0], rest[1]))
                else -> throw Problems.notFound("not a config path")
            }
        } catch (e: IllegalArgumentException) { throw Problems.notFound(e.message ?: "bad path") }
    }

    private fun actor(): String = AuditContext.current()?.actor ?: ACTOR

    companion object {
        const val ACTOR = "api"
        const val MAX_BATCH = 200_000
    }
}
