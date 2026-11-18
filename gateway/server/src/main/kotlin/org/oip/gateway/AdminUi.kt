package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Serves the built admin bundle at `/admin`.
 *
 * The directory comes from the `adminDist` argument, or from `OIP_ADMIN_DIST` / `oip.admin.dist`.
 * HTML and assets are public so the browser can load the shell. `/api/v1` stays authenticated
 * in [GatewayAuth]: a path that does not start with `/api/` is public.
 *
 * A request whose last segment has no file extension, and that is not a real file, returns
 * `index.html` so client routes (`/admin/login`, `/admin/modules`, …) work.
 * A missing asset (a path with an extension) is 404, not the HTML shell.
 *
 * GAT-016: -
 */
class AdminUi(private val root: Path?) {
    fun register(app: Javalin) {
        // The gateway's root is its home page (GAT-023), a page of the admin bundle.
        app.get("/") { ctx ->
            ctx.header("Cache-Control", "no-store")
            ctx.redirect("/admin/home", io.javalin.http.HttpStatus.FOUND)
        }
        app.get("/admin") { ctx -> serve(ctx, "") }
        app.get("/admin/") { ctx -> ctx.redirect("/admin") }
        app.get("/admin/<path>") { ctx -> serve(ctx, ctx.pathParam("path")) }
    }

    private fun serve(ctx: Context, relative: String) {
        val base = root?.takeIf { it.isDirectory() }
        if (base == null) notFound("admin UI bundle is not installed")
        val rel = relative.trim().trim('/')
        if (rel.isEmpty()) {
            sendFile(ctx, base.resolve("index.html"))
            return
        }
        val file = resolve(base, rel)
        if (file != null && file.isRegularFile()) {
            sendFile(ctx, file)
            return
        }
        if (file != null && isClientRoute(rel)) {
            sendFile(ctx, base.resolve("index.html"))
            return
        }
        notFound("not found")
    }

    private fun sendFile(ctx: Context, file: Path) {
        if (!file.isRegularFile()) notFound("admin UI bundle is not installed")
        ctx.status(200).contentType(contentType(file)).result(file.readBytes())
    }

    private fun notFound(detail: String): Nothing {
        throw Problems.notFound(detail)
    }

    companion object {
        fun locate(
            env: Map<String, String> = System.getenv(),
            property: String? = System.getProperty("oip.admin.dist"),
        ): Path? {
            val raw = property?.takeIf { it.isNotBlank() } ?: env["OIP_ADMIN_DIST"]?.takeIf { it.isNotBlank() } ?: return null
            val path = Path.of(raw).toAbsolutePath().normalize()
            return if (path.isDirectory()) path else null
        }

        /** A file inside [root], or null when [relative] escapes the bundle. */
        fun resolve(root: Path, relative: String): Path? {
            if (relative.isEmpty() || relative.contains('\\') || relative.contains('\u0000')) return null
            val parts = relative.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            val base = root.toAbsolutePath().normalize()
            val candidate = base.resolve(relative).normalize()
            if (!candidate.startsWith(base)) return null
            return candidate
        }

        private fun isClientRoute(relative: String): Boolean {
            val name = relative.substringAfterLast('/')
            return !name.contains('.')
        }

        internal fun contentType(file: Path): String = when (file.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}
