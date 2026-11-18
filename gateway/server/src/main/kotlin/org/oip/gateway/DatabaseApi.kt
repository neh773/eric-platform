package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.db.BrowseResult
import org.oip.db.PasswordUpdate
import org.oip.db.QueryFailed
import org.oip.db.QueryForbidden
import org.oip.db.QueryNotFound
import org.oip.db.QueryRejected
import org.oip.db.QueryResult
import org.oip.db.QueryUnavailable
import org.oip.security.AuditActions
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus

/**
 * Database connections and named queries.
 *
 * Connections are created once and shared:
 * Named queries run on the gateway. Callers pass parameters; they do not send SQL.
 * Role rows are the per-query security table:
 *
 * A password in a request is stored as a secret reference and is not written back in the response.
 * Listing and editing connections requires gateway.read / gateway.write. Running a query requires
 * authentication, then the query's own roles.
 *
 * Drivers (DAT-016): `GET /api/v1/db/drivers` lists uploaded JDBC driver JARs; `POST /api/v1/db/drivers?id=&driverClass=`
 * takes the JAR as the request body; `DELETE /api/v1/db/drivers/{id}` removes one that no connection uses. Upload and
 * removal need modules.admin and are audited. `driver-settings` (DAT-027) and `translators` read and write the
 * `DriverSettings` and `SqlTranslator` config resources of the same names.
 */
class DatabaseApi(private val gw: Gateway) {
    private val db get() = gw.databases

    fun register(app: Javalin) {
        app.get("/api/v1/db/connections") { ctx -> json(ctx, 200, mapOf("items" to db.connections())) }
        app.post("/api/v1/db/connections") { ctx -> createConnection(ctx) }
        app.get("/api/v1/db/connections/{name}") { ctx -> json(ctx, 200, db.connection(ctx.pathParam("name"))) }
        app.put("/api/v1/db/connections/{name}") { ctx -> updateConnection(ctx) }
        app.delete("/api/v1/db/connections/{name}") { ctx ->
            guard { db.deleteConnection(ctx.pathParam("name"), ifMatch(ctx)) }
            ctx.status(204)
        }
        app.post("/api/v1/db/connections/{name}/refresh") { ctx ->
            guard { json(ctx, 200, db.refreshConnection(ctx.pathParam("name"))) }
        }
        app.post("/api/v1/db/connections/{name}/browse") { ctx -> browse(ctx) }

        app.get("/api/v1/db/drivers") { ctx ->
            json(ctx, 200, mapOf("items" to db.driverViews(), "maxBytes" to org.oip.db.JdbcDriverStore.MAX_BYTES))
        }
        app.post("/api/v1/db/drivers") { ctx -> uploadDriver(ctx) }
        app.delete("/api/v1/db/drivers/{id}") { ctx ->
            val id = ctx.pathParam("id")
            guard { db.removeDriver(id) }
            audit(AuditActions.JDBC_DRIVER_REMOVED, id, "")
            ctx.status(204)
        }
        app.get("/api/v1/db/driver-settings") { ctx -> json(ctx, 200, mapOf("items" to db.driverSettings())) }
        app.put("/api/v1/db/driver-settings/{name}") { ctx ->
            val name = ctx.pathParam("name")
            val stored = guardResult { db.saveDriverSettings(name, jsonObject(ctx), ifMatch(ctx)) }
            ctx.header("ETag", "\"${stored.metadata.version}\"")
            json(ctx, 200, db.driverSettings().first { it["name"] == name })
        }
        app.delete("/api/v1/db/driver-settings/{name}") { ctx ->
            guard { db.deleteDriverSettings(ctx.pathParam("name"), ifMatch(ctx)) }
            ctx.status(204)
        }
        app.get("/api/v1/db/translators") { ctx -> json(ctx, 200, mapOf("items" to db.translators())) }
        app.put("/api/v1/db/translators/{name}") { ctx ->
            val name = ctx.pathParam("name")
            val stored = guardResult { db.saveTranslator(name, jsonObject(ctx), ifMatch(ctx)) }
            ctx.header("ETag", "\"${stored.metadata.version}\"")
            json(ctx, 200, db.translators().first { it["name"] == name })
        }
        app.delete("/api/v1/db/translators/{name}") { ctx ->
            guard { db.deleteTranslator(ctx.pathParam("name"), ifMatch(ctx)) }
            ctx.status(204)
        }

        app.get("/api/v1/db/queries") { ctx -> json(ctx, 200, mapOf("items" to db.queries())) }
        app.post("/api/v1/db/queries") { ctx -> createQuery(ctx) }
        app.post("/api/v1/db/queries/convert") { ctx -> convertQuery(ctx) }
        app.get("/api/v1/db/queries/{name}") { ctx -> json(ctx, 200, db.query(ctx.pathParam("name"))) }
        app.put("/api/v1/db/queries/{name}") { ctx -> updateQuery(ctx) }
        app.delete("/api/v1/db/queries/{name}") { ctx ->
            guard { db.deleteQuery(ctx.pathParam("name"), ifMatch(ctx)) }
            ctx.status(204)
        }
        app.post("/api/v1/db/queries/{name}/execute") { ctx -> execute(ctx) }
        app.post("/api/v1/db/queries/{name}/cache/clear") { ctx ->
            guard { db.clearCache(ctx.pathParam("name")) }
            ctx.status(204)
        }
    }

    private fun uploadDriver(ctx: Context) {
        val unknown = ctx.queryParamMap().keys - setOf("id", "driverClass")
        if (unknown.isNotEmpty()) throw Problems.badRequest("unknown parameters: ${unknown.sorted().joinToString(",")}")
        val id = ctx.queryParam("id") ?: throw Problems.badRequest("id is required")
        val driverClass = ctx.queryParam("driverClass")?.takeIf { it.isNotEmpty() }
        val declared = ctx.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > org.oip.db.JdbcDriverStore.MAX_BYTES) {
            throw ProblemException(413, "payload-too-large", "Payload Too Large", "the driver JAR is too large")
        }
        val bytes = ctx.bodyAsBytes()
        val actor = GatewayAuth.principal(ctx).name
        val info = guardResult { db.installDriver(id, bytes, driverClass, actor) }
        audit(AuditActions.JDBC_DRIVER_UPLOADED, id, "class=${info.driverClass};bytes=${info.size};sha256=${info.sha256}")
        ctx.header("Location", "/api/v1/db/drivers/$id")
        json(ctx, 201, info.view() + ("usedBy" to emptyList<String>()))
    }

    /** One success row per upload or removal. An audit error does not undo the change. */
    private fun audit(action: String, target: String, detail: String) {
        try {
            if (!gw.security.auditProfile.get().enabled) return
            val origin = AuditContext.current()
            gw.security.audit.append(
                AuditDraft(
                    timestampMillis = gw.clock.millis(),
                    actor = origin?.actor ?: "system",
                    action = action,
                    status = AuditStatus.SUCCESS,
                    actorHost = origin?.host ?: "",
                    target = target,
                    detail = detail,
                    context = origin?.context ?: "",
                )
            )
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(DatabaseApi::class.java).warn("audit record for {} {} was not written: {}", action, target, e.message)
        }
    }

    private fun createConnection(ctx: Context) {
        val (name, fields, password) = connectionBody(ctx, null)
        val stored = guardResult { db.saveConnection(name, fields, 0, password) }
        ctx.header("Location", "/api/v1/db/connections/$name")
        ctx.header("ETag", "\"${stored.metadata.version}\"")
        json(ctx, 201, db.connection(name))
    }

    private fun updateConnection(ctx: Context) {
        val name = ctx.pathParam("name")
        val (bodyName, fields, password) = connectionBody(ctx, name)
        if (bodyName != name) throw Problems.unprocessable("name does not match path")
        val version = ifMatch(ctx) ?: (guardResult { db.connection(name) }["version"] as Number).toLong()
        val stored = guardResult { db.saveConnection(name, fields, version, password) }
        ctx.header("ETag", "\"${stored.metadata.version}\"")
        json(ctx, 200, db.connection(name))
    }

    private fun createQuery(ctx: Context) {
        val (name, fields) = queryBody(ctx, null)
        val stored = guardResult { db.saveQuery(name, fields, 0) }
        ctx.header("Location", "/api/v1/db/queries/$name")
        ctx.header("ETag", "\"${stored.metadata.version}\"")
        json(ctx, 201, db.query(name))
    }

    /**
     * DAT-035: convert an ad hoc script call (`script`) or bare SQL (`sql`) into a named query. The answer holds the
     * resource fields and the script with the call rewritten to `system.db.runNamedQuery`. With `create: true` the
     * gateway-scope query is also saved; otherwise nothing is stored and the caller (the Studio) saves it where it wants.
     */
    private fun convertQuery(ctx: Context) {
        val body = jsonObject(ctx)
        rejectPassword(body)
        val unknown = body.keys - setOf("name", "script", "sql", "connection", "queryType", "create")
        if (unknown.isNotEmpty()) throw Problems.badRequest("unknown fields: ${unknown.sorted().joinToString(",")}")
        val name = requiredName(body)
        val script = body["script"] as? String
        val sql = body["sql"] as? String
        if ((script == null) == (sql == null)) throw Problems.badRequest("give either script or sql")
        if ((script ?: sql)!!.length > MAX_SQL) throw Problems.badRequest("the text is too long")
        val connection = body["connection"] as? String
        val converted = guardResult {
            if (script != null) org.oip.db.SqlConversion.fromScript(name, script, connection)
            else org.oip.db.SqlConversion.fromSql(name, sql!!, connection, body["queryType"] as? String)
        }
        val created = body["create"] == true
        if (created) guardResult { db.saveQuery(name, converted.fields, 0) }
        if (created) ctx.header("Location", "/api/v1/db/queries/$name")
        json(ctx, if (created) 201 else 200, mapOf("name" to name, "query" to converted.fields, "call" to converted.call, "script" to converted.script, "created" to created))
    }

    private fun updateQuery(ctx: Context) {
        val name = ctx.pathParam("name")
        val (bodyName, fields) = queryBody(ctx, name)
        if (bodyName != name) throw Problems.unprocessable("name does not match path")
        val version = ifMatch(ctx) ?: (guardResult { db.query(name) }["version"] as Number).toLong()
        val stored = guardResult { db.saveQuery(name, fields, version) }
        ctx.header("ETag", "\"${stored.metadata.version}\"")
        json(ctx, 200, db.query(name))
    }

    private fun execute(ctx: Context) {
        val name = ctx.pathParam("name")
        val body = jsonObject(ctx)
        rejectPassword(body)
        val parameters = when (val raw = body["parameters"]) {
            null -> emptyMap()
            is Map<*, *> -> {
                val out = linkedMapOf<String, Any?>()
                for ((key, value) in raw) {
                    val param = key as? String ?: throw Problems.badRequest("parameter names must be strings")
                    out[param] = value
                }
                out
            }
            else -> throw Problems.badRequest("parameters must be an object")
        }
        val connection = when (val raw = body["connection"]) {
            null -> null
            is String -> raw
            else -> throw Problems.badRequest("connection must be a string")
        }
        val project = when (val raw = body["project"]) {
            null -> null
            is String -> raw.also { runCatching { org.oip.config.Scope.project(it) }.getOrElse { throw Problems.badRequest("project must be a project name") } }
            else -> throw Problems.badRequest("project must be a string")
        }
        val roles = GatewayAuth.principal(ctx).roles
        val result = guardResult { db.execute(name, parameters, roles, connection, project = project) }
        json(ctx, 200, resultJson(result))
    }

    /**
     * Admin query browser: one ad hoc statement on a connection. The connections rule in [GatewayAuth]
     * requires gateway.write for every POST here. Reads run read-only; a write needs the connection's
     * allowBrowserWrites flag. -
     */
    private fun browse(ctx: Context) {
        val body = jsonObject(ctx)
        rejectPassword(body)
        val unknown = body.keys - setOf("sql", "maxRows")
        if (unknown.isNotEmpty()) throw Problems.badRequest("unknown fields: ${unknown.sorted().joinToString(",")}")
        val sql = body["sql"] as? String ?: throw Problems.badRequest("sql is required")
        if (sql.length > MAX_SQL) throw Problems.badRequest("sql is too long")
        val maxRows = when (val raw = body["maxRows"]) {
            null -> DEFAULT_BROWSE_ROWS
            is Int -> raw
            else -> throw Problems.badRequest("maxRows must be an integer")
        }
        val result = guardResult { db.browse(ctx.pathParam("name"), sql, maxRows) }
        json(ctx, 200, when (result) {
            is BrowseResult.Rows -> mapOf("type" to "query", "columns" to result.columns, "rows" to result.rows, "truncated" to result.truncated)
            is BrowseResult.Update -> mapOf("type" to "update", "rowsAffected" to result.rowsAffected)
        })
    }

    private fun connectionBody(ctx: Context, pathName: String?): Triple<String, Map<String, Any?>, PasswordUpdate> {
        val body = jsonObject(ctx)
        val name = pathName ?: requiredName(body)
        if (pathName != null && body["name"] != null && body["name"] != pathName) {
            throw Problems.unprocessable("name does not match path")
        }
        val password = passwordUpdate(body)
        val fields = linkedMapOf<String, Any?>()
        for ((key, value) in body) {
            if (key == "name" || key == "password") continue
            fields[key] = value
        }
        return Triple(name, fields, password)
    }

    private fun queryBody(ctx: Context, pathName: String?): Pair<String, Map<String, Any?>> {
        val body = jsonObject(ctx)
        rejectPassword(body)
        val name = pathName ?: requiredName(body)
        if (pathName != null && body["name"] != null && body["name"] != pathName) {
            throw Problems.unprocessable("name does not match path")
        }
        val fields = linkedMapOf<String, Any?>()
        for ((key, value) in body) {
            if (key == "name") continue
            fields[key] = value
        }
        return name to fields
    }

    private fun passwordUpdate(body: Map<String, Any?>): PasswordUpdate {
        if (!body.containsKey("password")) return PasswordUpdate.Unchanged
        return when (val raw = body["password"]) {
            null -> PasswordUpdate.Clear
            is String -> when {
                raw.isEmpty() -> PasswordUpdate.Clear
                raw.startsWith("secret://") -> PasswordUpdate.Reference(raw)
                raw.length > 1024 -> throw Problems.unprocessable("password is too long")
                else -> PasswordUpdate.Set(raw)
            }
            else -> throw Problems.unprocessable("password must be a string")
        }
    }

    private fun rejectPassword(body: Map<String, Any?>) {
        if (body.containsKey("password")) throw Problems.unprocessable("password is not accepted")
    }

    private fun requiredName(body: Map<String, Any?>): String {
        val name = body["name"] as? String ?: throw Problems.badRequest("name is required")
        if (!NAME.matches(name)) throw Problems.unprocessable("name must be a lowercase resource name")
        return name
    }

    private fun resultJson(result: QueryResult): Map<String, Any?> = when (result) {
        is QueryResult.Rows -> mapOf(
            "type" to "query",
            "columns" to result.columns,
            "rows" to result.rows,
            "cached" to result.cached,
            "truncated" to result.truncated,
        )
        is QueryResult.Scalar -> mapOf(
            "type" to "scalar",
            "value" to result.value,
            "cached" to result.cached,
            "fallback" to result.fallback,
        )
        is QueryResult.Update -> mapOf("type" to "update", "rowsAffected" to result.rowsAffected)
        is QueryResult.Procedure -> mapOf(
            "type" to "procedure",
            "returnValue" to result.returnValue,
            "outputs" to result.outputs,
            "columns" to result.columns,
            "rows" to result.rows,
            "truncated" to result.truncated,
        )
    }

    private fun <T> guardResult(block: () -> T): T = try {
        block()
    } catch (e: QueryNotFound) {
        throw Problems.notFound(e.message ?: "not found")
    } catch (e: QueryForbidden) {
        throw Problems.forbidden(e.message ?: "forbidden")
    } catch (e: QueryRejected) {
        throw Problems.unprocessable(e.message ?: "invalid")
    } catch (e: QueryUnavailable) {
        throw ProblemException(503, "connection-unavailable", "Connection Unavailable", e.message ?: "connection unavailable")
    } catch (e: QueryFailed) {
        throw Problems.unprocessable(e.message ?: "query failed")
    }

    private fun guard(block: () -> Unit) {
        guardResult(block)
    }

    private fun json(ctx: Context, status: Int, body: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(body))
    }

    private fun jsonObject(ctx: Context): Map<String, Any?> {
        if (ctx.body().length > MAX_BODY) throw Problems.badRequest("body is too large")
        val parsed = try {
            Json.mapper.readValue(ctx.body(), Any::class.java)
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?> ?: throw Problems.badRequest("body must be a JSON object")
    }

    private fun ifMatch(ctx: Context): Long? = ctx.header("If-Match")?.trim()?.removePrefix("W/")?.trim('"')?.let {
        it.toLongOrNull() ?: throw Problems.badRequest("If-Match must be a resource version")
    }

    companion object {
        private const val MAX_BODY = 1_000_000
        private const val MAX_SQL = 100_000
        private const val DEFAULT_BROWSE_ROWS = 500
        private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    }
}
