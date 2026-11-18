package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import java.nio.file.Files
import java.util.Base64

/**
 * Designer tools that the admin query browser does not cover: a project image library. The script console is
 * [DesignerConsole] (`POST /api/v1/designer/console`).
 * Ad hoc SQL stays on `POST /api/v1/db/connections/{name}/browse`.
 */
class StudioToolsApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get("/api/v1/projects/{project}/images") { ctx -> listImages(ctx) }
        app.post("/api/v1/projects/{project}/images") { ctx -> saveImage(ctx) }
        app.delete("/api/v1/projects/{project}/images/{name}") { ctx -> deleteImage(ctx) }
        app.get("/api/v1/hmi/projects/{project}/images/{name}") { ctx -> serveImage(ctx) }
    }

    private fun listImages(ctx: Context) {
        val project = project(ctx)
        val dir = dir(project)
        val items = if (!Files.isDirectory(dir)) emptyList() else Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.map { path ->
                val name = path.fileName.toString()
                mapOf("name" to name, "mediaType" to mediaType(name), "bytes" to Files.size(path))
            }.toList().sortedBy { it["name"] as String }
        }
        json(ctx, mapOf("items" to items))
    }

    private fun saveImage(ctx: Context) {
        val project = project(ctx)
        val body = jsonObject(ctx)
        val unknown = body.keys - setOf("name", "data")
        if (unknown.isNotEmpty()) throw Problems.badRequest("unknown fields: ${unknown.sorted().joinToString(",")}")
        val name = body["name"] as? String ?: throw Problems.badRequest("name is required")
        if (!NAME.matches(name)) throw Problems.unprocessable("name must be a file name of png, jpg, gif, or svg")
        val data = body["data"] as? String ?: throw Problems.badRequest("data is required")
        val bytes = try {
            Base64.getDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            throw Problems.badRequest("data must be base64")
        }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw Problems.unprocessable("image must be 1 to $MAX_BYTES bytes")
        if (name.endsWith(".svg") && svgHasScript(bytes)) throw Problems.unprocessable("svg must not contain a script")
        val folder = dir(project)
        Files.createDirectories(folder)
        Files.write(folder.resolve(name), bytes)
        audit(org.oip.security.AuditActions.PROJECT_IMAGE_SAVED, project, name, "bytes=${bytes.size}")
        ctx.status(201)
        json(ctx, mapOf("name" to name, "mediaType" to mediaType(name), "bytes" to bytes.size))
    }

    private fun deleteImage(ctx: Context) {
        val project = project(ctx)
        val name = imageName(ctx)
        val path = dir(project).resolve(name)
        if (!Files.isRegularFile(path)) throw Problems.notFound("no image $name")
        Files.delete(path)
        audit(org.oip.security.AuditActions.PROJECT_IMAGE_DELETED, project, name, "")
        ctx.status(204)
    }

    /** One audit record per image change (SEC-003): images are project content written outside the config store. */
    private fun audit(action: String, project: String, name: String, detail: String) {
        if (!gw.security.auditProfile.get().enabled) return
        val origin = org.oip.security.AuditContext.current()
        runCatching {
            gw.security.audit.append(
                org.oip.security.AuditDraft(
                    timestampMillis = gw.clock.millis(),
                    actor = origin?.actor ?: "api",
                    action = action,
                    status = org.oip.security.AuditStatus.SUCCESS,
                    actorHost = origin?.host ?: "",
                    target = "project/$project/images/$name",
                    detail = detail,
                    context = origin?.context ?: "gateway-http",
                ),
            )
        }
    }

    private fun serveImage(ctx: Context) {
        val project = project(ctx)
        val name = imageName(ctx)
        val path = dir(project).resolve(name)
        if (!Files.isRegularFile(path)) throw Problems.notFound("no image $name")
        ctx.contentType(mediaType(name))
        ctx.header("Cache-Control", "no-store")
        ctx.header("X-Content-Type-Options", "nosniff")
        ctx.result(Files.readAllBytes(path))
    }

    private fun project(ctx: Context): String {
        val name = ctx.pathParam("project")
        if (gw.projectService.info(name) == null) throw Problems.notFound("no project $name")
        return name
    }

    private fun imageName(ctx: Context): String {
        val name = ctx.pathParam("name")
        if (!NAME.matches(name)) throw Problems.badRequest("name must be a file name of png, jpg, gif, or svg")
        return name
    }

    private fun dir(project: String) = gw.dataDir.resolve("images").resolve(project)

    private fun jsonObject(ctx: Context): Map<String, Any?> {
        val raw = ctx.body()
        if (raw.isBlank()) throw Problems.badRequest("body must be a JSON object")
        val parsed = try {
            Json.parse(raw)
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        return parsed
    }

    private fun json(ctx: Context, body: Any?) {
        ctx.contentType("application/json").result(Json.write(body))
    }

    companion object {
        private const val MAX_BYTES = 512_000
        private val NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,80}\.(png|jpg|jpeg|gif|svg)$""")

        private fun mediaType(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }

        private fun svgHasScript(bytes: ByteArray): Boolean {
            val text = bytes.toString(Charsets.UTF_8).lowercase()
            return "<script" in text || "javascript:" in text || Regex("""\son[a-z]+\s*=""").containsMatchIn(text)
        }
    }
}
