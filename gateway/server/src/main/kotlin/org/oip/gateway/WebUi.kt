package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Serves the built `web/designer` bundle (M4.1): HMI sessions at `/hmi` and the Studio at `/studio` and `/designer`.
 *
 * The directory comes from the `webDist` argument, or from `OIP_WEB_DIST` / `oip.web.dist`. The bundle is built with
 * its default base, so its scripts and styles are under `/assets/`; those are served from the bundle as well. Pages
 * and assets are public so the browser can load the shell. Tag values reach a session through `/api/v1/ws`
 * ([SessionHost]), which is authenticated like the rest of `/api/v1`.
 *
 * A path under `/hmi` or `/studio` whose last segment has no file extension returns `index.html`, so session pages
 * (`/hmi/plant/pumps`) and query parameters work on reload. Below a project (`/hmi/<project>/...`) every path is a
 * page URL and returns `index.html` even with a dot in it (`/hmi/plant/line/3.5`). A missing asset is 404, not the
 * HTML shell.
 *
 * A project that is disabled or not runnable (PRO-004) is not served: `/hmi/<project>/...` is 404 for it. A name that
 * is not a stored project still gets the shell, which says the project was not found.
 *
 */
class WebUi(
    private val root: Path?,
    private val defaultProject: () -> String? = { null },
    /** False for a project `/hmi` must not serve. */
    private val launchable: (String) -> Boolean = { true },
    /** `http.trustedProxies`: only these peers may publish the gateway under a path prefix (HMP-088). */
    private val trustedProxies: () -> List<String> = { emptyList() },
    /** Called with the project name before a project shell is sent. A project with public HMI admits an anonymous session. */
    private val admit: (Context, String) -> Unit = { _, _ -> },
) {
    fun register(app: Javalin) {
        for (mount in MOUNTS) {
            app.get(mount) { ctx -> launch(ctx, mount) }
            app.get("$mount/") { ctx -> launch(ctx, mount) }
            app.get("$mount/<path>") { ctx -> page(ctx, mount, ctx.pathParam("path")) }
        }
        app.get("/assets/<path>") { ctx -> asset(ctx, "assets/" + ctx.pathParam("path")) }
    }

    private fun base(): Path = root?.takeIf { it.isDirectory() } ?: throw Problems.notFound("HMI bundle is not installed")

    /**
     * `/hmi` with no project opens the configured launch default (GAT-035, `launch.defaultProject`) with a 302,
     * keeping the query string. Without one, or under `/studio` or `/designer`, it is the shell as before.
     */
    private fun launch(ctx: Context, mount: String) {
        val project = if (mount == HMI_MOUNT) defaultProject() else null
        if (project.isNullOrBlank()) return shell(ctx)
        val query = ctx.queryString()?.let { "?$it" } ?: ""
        ctx.header("Cache-Control", "no-store")
        val prefix = ForwardedHeaders.prefix(ctx.ip(), ctx.header("X-Forwarded-Prefix"), trustedProxies())
        ctx.redirect("$prefix$HMI_MOUNT/$project/$query", io.javalin.http.HttpStatus.FOUND)
    }

    private fun shell(ctx: Context) {
        send(ctx, base().resolve("index.html"))
    }

    private fun page(ctx: Context, mount: String, relative: String) {
        val rel = relative.trim().trim('/')
        val name = rel.substringAfterLast('/')
        // Under /hmi, a path below the project is a session page URL, and page URLs may contain dots (`/line/3.5`);
        // the bundle's own files are all under /assets, so only the first segment is held to the file rule.
        val pageUrl = mount == HMI_MOUNT && rel.contains('/')
        if (rel.isNotEmpty() && !pageUrl && name.contains('.')) throw Problems.notFound("not found")
        if (mount == HMI_MOUNT) {
            val project = rel.substringBefore('/')
            // A built-in sample such as `_sample` is not a stored project name. The shell still loads.
            if (project.isNotEmpty() && runCatching { org.oip.config.Scope.project(project) }.isSuccess) {
                if (!launchable(project)) {
                    ctx.header("Cache-Control", "no-store")
                    throw Problems.notFound("project '$project' is disabled or not runnable")
                }
                admit(ctx, project)
            }
        }
        shell(ctx)
    }

    private fun asset(ctx: Context, relative: String) {
        val file = AdminUi.resolve(base(), relative)
        if (file == null || !file.isRegularFile()) throw Problems.notFound("not found")
        send(ctx, file)
    }

    private fun send(ctx: Context, file: Path) {
        if (!file.isRegularFile()) throw Problems.notFound("HMI bundle is not installed")
        // The shell names hashed assets; it must not be cached past a redeploy.
        if (file.fileName.toString() == "index.html") ctx.header("Cache-Control", "no-cache")
        ctx.status(200).contentType(AdminUi.contentType(file)).result(file.readBytes())
    }

    companion object {
        const val HMI_MOUNT = "/hmi"
        val MOUNTS = listOf(HMI_MOUNT, "/studio", "/designer")

        fun locate(
            env: Map<String, String> = LegacyEnv.current,
            property: String? = System.getProperty("oip.web.dist"),
            bundled: Path? = Bundled.dir("web"),
        ): Path? {
            val raw = property?.takeIf { it.isNotBlank() } ?: env["OIP_WEB_DIST"]?.takeIf { it.isNotBlank() } ?: return bundled
            val path = Path.of(raw).toAbsolutePath().normalize()
            return if (path.isDirectory()) path else bundled
        }
    }
}
