package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Scope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.withLock
import kotlin.io.path.absolute
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

/**
 * Small web-dev mount at `/system/webdev/{project}/{path}`.
 *
 * Python resources call [WebScriptHost]. A host that declines falls back to the static body.
 * Mounted folders stay under the gateway webdev directory.
 */
class WebDevService(
    private val store: FileConfigStore,
    private val publicRoot: Path,
    var scripts: WebScriptHost = WebScriptHost.NOOP,
) : AutoCloseable {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val resources = LinkedHashMap<String, WebResource>()
    private var subscription: AutoCloseable? = null

    fun attach() {
        store.registry.register(MODULE, KIND, schema())
        store.revalidate(MODULE)
        subscription = store.subscribe { event -> onConfig(event) }
        lock.withLock {
            for (stored in store.list(MODULE, Scope.GATEWAY, KIND)) accept(stored.resource.metadata.name, stored.resource.spec)
        }
    }

    fun requiresAuthentication(requestPath: String): Boolean = lock.withLock {
        match(requestPath)?.requireAuthentication == true
    }

    fun register(app: Javalin) {
        for (method in METHODS) {
            app.addHttpHandler(method, "/system/webdev/<path>") { ctx -> serve(ctx) }
        }
    }

    fun serve(ctx: Context) {
        val resource = lock.withLock { match(ctx.path()) } ?: throw Problems.notFound("web resource not found")
        if (resource.requireHttps && !ctx.scheme().equals("https", ignoreCase = true)) {
            throw Problems.forbidden("https required", "https-required")
        }
        when (resource.type) {
            "text" -> serveText(ctx, resource)
            "python" -> servePython(ctx, resource)
            "mounted" -> serveMounted(ctx, resource)
            else -> throw Problems.notFound("web resource not found")
        }
    }

    override fun close() {
        subscription?.close()
        subscription = null
    }

    private fun onConfig(event: ConfigEvent) {
        val id = event.id ?: return
        if (id.module != MODULE || id.kind != KIND || id.scope != Scope.GATEWAY) return
        lock.withLock {
            resources.entries.removeIf { it.value.configName == id.name }
            if (event.type != ChangeType.DELETED && event.type != ChangeType.INVALID) {
                event.resource?.let { accept(id.name, it.spec) }
            }
        }
    }

    private fun accept(name: String, spec: Map<String, Any?>) {
        val compiled = try {
            compile(name, spec)
        } catch (_: IllegalArgumentException) {
            return
        }
        resources[compiled.key] = compiled
    }

    private fun match(requestPath: String): WebResource? {
        val rest = requestPath.substringBefore('?').removePrefix("/system/webdev/").trim('/')
        if (rest.isEmpty() || rest.split('/').any { it == "." || it == ".." }) return null
        val parts = rest.split('/')
        val project = parts[0]
        val path = if (parts.size == 1) "index.html" else parts.drop(1).joinToString("/")
        resources["$project\u0000$path"]?.takeIf { it.enabled && it.type != "mounted" }?.let { return it }
        var best: WebResource? = null
        for (resource in resources.values) {
            if (!resource.enabled || resource.project != project || resource.type != "mounted") continue
            val mount = resource.path
            val matches = path == mount || path.startsWith("$mount/")
            if (matches && (best == null || mount.length > best.path.length)) best = resource
        }
        return best
    }

    private fun serveText(ctx: Context, resource: WebResource) {
        if (ctx.method().name != "GET" && ctx.method().name != "HEAD") {
            throw ProblemException(405, "method-not-allowed", "Method Not Allowed", "${ctx.method()} not allowed")
        }
        write(ctx, 200, resource.contentType, resource.body.orEmpty().toByteArray())
    }

    private fun servePython(ctx: Context, resource: WebResource) {
        val call = WebCall(
            method = ctx.method().name,
            project = resource.project,
            path = resource.path,
            query = ctx.queryParamMap(),
            body = ctx.bodyAsBytes().take(MAX_BODY).toByteArray(),
            principal = runCatching { GatewayAuth.principal(ctx).name }.getOrNull(),
        )
        val scripted = if (resource.source.isNotBlank()) scripts.handle(resource.source, call) else null
        if (scripted != null) {
            write(ctx, scripted.status, scripted.contentType, scripted.body)
            return
        }
        if (resource.body != null && (call.method == "GET" || call.method == "HEAD")) {
            write(ctx, 200, resource.contentType, resource.body.toByteArray())
            return
        }
        if (resource.source.isNotBlank()) {
            throw ProblemException(501, "not-implemented", "Not Implemented", "python resource has no script host")
        }
        throw Problems.notFound("web resource not found")
    }

    private fun serveMounted(ctx: Context, resource: WebResource) {
        if (ctx.method().name != "GET" && ctx.method().name != "HEAD") {
            throw ProblemException(405, "method-not-allowed", "Method Not Allowed", "${ctx.method()} not allowed")
        }
        val request = ctx.path().removePrefix("/system/webdev/${resource.project}/").trim('/')
        val relative = request.removePrefix(resource.path).trim('/')
        if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw Problems.notFound("web resource not found")
        }
        val root = publicRoot.resolve(resource.folder).normalize().absolute()
        val base = publicRoot.normalize().absolute()
        if (!root.startsWith(base)) throw Problems.notFound("web resource not found")
        val file = (if (relative.isEmpty()) root.resolve("index.html") else root.resolve(relative)).normalize().absolute()
        if (!file.startsWith(root) || file.notExists() || !file.isRegularFile()) throw Problems.notFound("web resource not found")
        val size = Files.size(file)
        if (size > MAX_BODY) throw ProblemException(413, "too-large", "Content Too Large", "file is larger than 1 MiB")
        write(ctx, 200, contentType(file), Files.readAllBytes(file))
    }

    private fun write(ctx: Context, status: Int, contentType: String, body: ByteArray) {
        ctx.status(status).contentType(contentType)
        if (ctx.method().name != "HEAD") ctx.result(body)
    }

    companion object {
        const val MODULE = "webdev"
        const val KIND = "WebResource"
        private const val MAX_BODY = 1024 * 1024
        private val METHODS = listOf(HandlerType.GET, HandlerType.HEAD, HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE)
        private val PROJECT = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        private val FOLDER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$")

        private fun schema(): Map<String, Any?> = mapOf(
            "type" to "object",
            "required" to listOf("path", "type"),
            "properties" to mapOf(
                "project" to mapOf("type" to "string", "pattern" to PROJECT.pattern, "default" to "gateway"),
                "path" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 240),
                "type" to mapOf("type" to "string", "enum" to listOf("text", "python", "mounted")),
                "contentType" to mapOf("type" to "string", "maxLength" to 200),
                "body" to mapOf("type" to "string", "maxLength" to 100_000),
                "source" to mapOf("type" to "string", "maxLength" to 100_000),
                "folder" to mapOf("type" to "string", "pattern" to FOLDER.pattern),
                "requireAuthentication" to mapOf("type" to "boolean", "default" to false),
                "requireHttps" to mapOf("type" to "boolean", "default" to false),
                "enabled" to mapOf("type" to "boolean", "default" to true),
            ),
        )

        private fun compile(name: String, spec: Map<String, Any?>): WebResource {
            val project = spec["project"] as? String ?: "gateway"
            if (!PROJECT.matches(project)) throw IllegalArgumentException("invalid project")
            val path = (spec["path"] as? String)?.trim('/') ?: throw IllegalArgumentException("path is required")
            if (path.isEmpty() || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                throw IllegalArgumentException("invalid path")
            }
            val type = spec["type"] as? String ?: throw IllegalArgumentException("type is required")
            val folder = spec["folder"] as? String ?: ""
            if (type == "mounted" && !FOLDER.matches(folder)) throw IllegalArgumentException("mounted folder is invalid")
            return WebResource(
                configName = name,
                project = project,
                path = path,
                type = type,
                contentType = spec["contentType"] as? String ?: "text/plain; charset=utf-8",
                body = spec["body"] as? String,
                source = spec["source"] as? String ?: "",
                folder = folder,
                requireAuthentication = spec["requireAuthentication"] as? Boolean ?: false,
                requireHttps = spec["requireHttps"] as? Boolean ?: false,
                enabled = spec["enabled"] as? Boolean ?: true,
            )
        }

        private fun contentType(file: Path): String = when (file.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "json" -> "application/json"
            "css" -> "text/css; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}

/**
 * Runs a web resource whose source defines `doGet`, `doPost`, `doPut`, `doPatch`, `doDelete`, or `doHead`.
 * A source that defines none of those is left to the static body. The function receives a request dict and
 * returns a string, or a dict with `status`, `contentType`, and `body`.
 */
class WebDevScripts(private val scripts: org.oip.scripting.ScriptService) : WebScriptHost {
    override fun handle(source: String, call: WebCall): WebResult? {
        if (!DEFINES.containsMatchIn(source)) return null
        val request = Json.write(mapOf(
            "method" to call.method,
            "project" to call.project,
            "path" to call.path,
            "query" to call.query.mapValues { it.value.firstOrNull() ?: "" },
            "body" to call.body.toString(Charsets.UTF_8),
            "principal" to call.principal,
        ))
        val program = buildString {
            appendLine("import json as _oip_json")
            appendLine("_oip_request = _oip_json.loads(${pythonString(request)})")
            appendLine(source.trim())
            appendLine("_oip_fns = {'GET': globals().get('doGet'), 'POST': globals().get('doPost'), 'PUT': globals().get('doPut'), 'PATCH': globals().get('doPatch'), 'DELETE': globals().get('doDelete'), 'HEAD': globals().get('doHead')}")
            appendLine("_oip_fn = _oip_fns.get(_oip_request['method'])")
            appendLine("if _oip_fn is None:")
            appendLine("    _result = {'status': 405, 'contentType': 'text/plain; charset=utf-8', 'body': 'method not allowed'}")
            appendLine("else:")
            appendLine("    _result = _oip_fn(_oip_request)")
        }
        val result = scripts.run(program, "webdev-${call.project}-${call.path}")
        if (!result.ok) throw IllegalStateException(result.error ?: "web script failed")
        return toResult(result.value, call.method)
    }

    private fun toResult(value: Any?, method: String): WebResult {
        val record = value as? Map<*, *>
        if (record == null) {
            val text = value?.toString() ?: ""
            return WebResult(200, "text/plain; charset=utf-8", if (method == "HEAD") ByteArray(0) else text.toByteArray())
        }
        val status = (record["status"] as? Number)?.toInt() ?: 200
        val type = record["contentType"] as? String ?: "text/plain; charset=utf-8"
        val body = record["body"]?.toString() ?: ""
        return WebResult(status, type, if (method == "HEAD") ByteArray(0) else body.toByteArray())
    }

    companion object {
        private val DEFINES = Regex("""(?m)^def\s+do(Get|Post|Put|Patch|Delete|Head)\s*\(""")

        private fun pythonString(value: String): String = "'''" + value.replace("\\", "\\\\").replace("'''", "\\'''") + "'''"
    }
}

/** Python page handler. A null result means the host did not run the script. */
fun interface WebScriptHost {
    fun handle(source: String, call: WebCall): WebResult?

    companion object {
        val NOOP: WebScriptHost = WebScriptHost { _, _ -> null }
    }
}

data class WebCall(
    val method: String,
    val project: String,
    val path: String,
    val query: Map<String, List<String>>,
    val body: ByteArray,
    val principal: String?,
)

data class WebResult(val status: Int, val contentType: String, val body: ByteArray)

private data class WebResource(
    val configName: String,
    val project: String,
    val path: String,
    val type: String,
    val contentType: String,
    val body: String?,
    val source: String,
    val folder: String,
    val requireAuthentication: Boolean,
    val requireHttps: Boolean,
    val enabled: Boolean,
) {
    val key: String get() = "$project\u0000$path"
}
