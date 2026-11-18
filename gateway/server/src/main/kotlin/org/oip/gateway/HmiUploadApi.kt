package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.ProjectService
import org.oip.config.Scope
import org.oip.security.Permissions
import org.oip.security.Subject
import java.util.Base64

/**
 * File upload from an HMI session to a gateway script handler (HMP-047).
 *
 * `POST /api/v1/hmi/projects/{project}/upload/{hook}?view=` takes the file as the raw request body, with the file's media
 * type as `Content-Type` and its name in `X-File-Name`. The handler is a hook in the project's script hook set whose entry
 * has an `upload` block: `contentTypes` (the allowed media types, exact, or a type prefix such as image with a trailing slash-star; required, so a hook that lists none
 * takes no file) and `maxBytes` (default 1 MiB, never above [CEILING]). A hook without that block is not an upload handler.
 *
 * The caller must be a signed-in user (an API key is refused) holding `tags.read`, and passes the same project, view
 * and hook security checks as any script call, which [HmiScriptRunner.run] applies. The size is checked against
 * `Content-Length` before any byte is read and again while reading, so an oversize or unlabelled stream stops at the limit.
 * The gateway keeps the bytes in memory and passes them to the handler as `event["payload"]` (`filename`, `contentType`,
 * `size`, base64 `data`); it writes nothing to disk. Only what the handler does with them reaches storage, and a
 * session script can write no file at all.
 */
class HmiUploadApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.post("/api/v1/hmi/projects/{project}/upload/{hook}") { ctx -> upload(ctx) }
    }

    private fun upload(ctx: Context) {
        val (subject, connection) = GatewayAuth.subject(ctx)
        if (subject !is Subject.User) throw Problems.forbidden("file upload needs a signed-in user")
        val project = ctx.pathParam("project")
        val hookName = ctx.pathParam("hook")
        try {
            Scope.project(project)
            gw.projectService.info(project)
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw Problems.notFound("project '$project' not found")
        val view = ctx.queryParam("view")?.takeIf { it.isNotEmpty() }
        val hook = try {
            gw.hmiScripts.hook(project, view, hookName)
        } catch (e: IllegalArgumentException) {
            null
        }
        val settings = hook?.upload ?: throw Problems.notFound("'$hookName' is not an upload handler of project '$project'")
        val allowed = (settings["contentTypes"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.lowercase() }.filter { it.isNotEmpty() }
        if (allowed.isEmpty()) throw Problems.notFound("'$hookName' is not an upload handler of project '$project'")
        val limit = ((settings["maxBytes"] as? Number)?.toLong() ?: DEFAULT_MAX).coerceIn(1L, CEILING)

        val type = (ctx.header("Content-Type") ?: "").substringBefore(';').trim().lowercase()
        if (type.isEmpty() || !allowed.any { matches(it, type) }) {
            throw ProblemException(415, "unsupported-media-type", "Unsupported Media Type",
                "content type '${type.take(80)}' is not allowed; allowed: ${allowed.joinToString(", ")}")
        }
        val name = fileName(ctx.header("X-File-Name"))
        val declared = ctx.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > limit) throw tooLarge(limit)
        val bytes = ctx.req().inputStream.readNBytes((limit + 1).toInt())
        if (bytes.size > limit) throw tooLarge(limit)
        if (bytes.isEmpty()) throw Problems.badRequest("the file is empty")

        val caller = GatewayAuth.tagCaller(ctx, gw.security.zones)
        val mayWrite = gw.security.authorization.authorize(subject, Permissions.TAGS_WRITE, connection).allowed
        val payload = linkedMapOf<String, Any?>(
            "filename" to name, "contentType" to type, "size" to bytes.size,
            "data" to Base64.getEncoder().encodeToString(bytes),
        )
        val call = SessionHost.ScriptCall(
            SessionHost.ScriptKind.EVENT, project, view, hookName,
            context = linkedMapOf("nodeId" to (ctx.queryParam("node") ?: ""), "view" to view, "params" to emptyMap<String, Any?>(),
                "custom" to emptyMap<String, Any?>(), "user" to caller.actor),
            event = linkedMapOf("name" to "upload", "payload" to payload),
            mayWrite = { mayWrite },
            provider = GatewayAuth.provider(ctx),
            levels = GatewayAuth.levels(ctx),
        )
        // The upload is not a configuration write; its audit scope closes before the handler runs.
        GatewayAuth.releaseAudit(ctx)
        val reply = gw.hmiScripts.run(caller, call)
        if (!reply.ok) {
            val message = reply.error ?: "upload handler failed"
            throw when (reply.code) {
                HmiProjectApi.SESSION_REFUSED -> Problems.forbidden(message)
                SessionHost.ScriptReply.UNKNOWN_HOOK -> Problems.notFound(message)
                SessionHost.ScriptReply.BUSY -> ProblemException(503, "busy", "Service Unavailable", message)
                else -> Problems.unprocessable(message)
            }
        }
        ctx.status(200).contentType("application/json").result(Json.write(mapOf("filename" to name, "size" to bytes.size, "value" to reply.value, "actions" to reply.actions)))
    }

    private fun tooLarge(limit: Long) = ProblemException(413, "too-large", "Content Too Large", "the file is larger than the $limit byte limit")

    private fun matches(pattern: String, type: String): Boolean =
        if (pattern.endsWith("/*")) type.startsWith(pattern.dropLast(1)) && type.length > pattern.length - 1 else pattern == type

    /** The file name the browser reports, reduced to a plain name: no path, no control characters, 1 to 200 characters. */
    private fun fileName(raw: String?): String {
        val decoded = raw?.let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(it) }.orEmpty()
        val plain = decoded.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 0x20 && it.code != 0x7f }.trim().take(200)
        if (plain.isEmpty() || plain == "." || plain == "..") throw Problems.badRequest("X-File-Name must name the file")
        return plain
    }

    companion object {
        const val DEFAULT_MAX = 1L shl 20
        /** The most one upload may be, whatever a hook allows: the bytes travel to the script as base64 in memory. */
        const val CEILING = 5L shl 20
    }
}
