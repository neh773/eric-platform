package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.security.AuditContext
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Active Designer sessions (GAT-028). A Designer that opens a project on the gateway registers a session
 * (`POST /api/v1/designer/sessions`), keeps it alive with a heartbeat (`PUT .../{id}`) and ends it on close
 * (`DELETE .../{id}`). A session whose heartbeat stops for [TTL_SECONDS] drops out of the list, so a closed tab or a
 * crashed browser does not linger. The registry is in memory: it describes who is connected now, not history.
 *
 * `GET /api/v1/designer/sessions` (gateway.read) lists the sessions with the Designer launch link and the launcher
 * download. `GET /api/v1/designer/launcher` (gateway.write) downloads an internet shortcut to the Designer.
 * Opening, heartbeat and close need gateway.write and designer.login, and only the user who opened a session may
 * keep it alive or end it. Closing a session releases the resource locks it held (SEC-020, [DesignerLocks]); a session
 * that expires releases them too, because a lock counts only while its session is live.
 * Doc source: FEATURES GAT-028.
 */
class DesignerSessions(private val clock: Clock) {
    data class Entry(
        val id: String,
        val user: String,
        val project: String?,
        val address: String,
        val openedAt: Instant,
        @Volatile var lastSeen: Instant,
    )

    private val sessions = ConcurrentHashMap<String, Entry>()

    fun open(user: String, project: String?, address: String): Entry {
        val now = clock.instant()
        prune(now)
        val mine = sessions.values.filter { it.user == user }.sortedBy { it.openedAt }
        // One user cannot fill the list: past the cap the oldest of their sessions is replaced.
        if (mine.size >= MAX_PER_USER) sessions.remove(mine.first().id)
        if (sessions.size >= MAX_TOTAL) sessions.values.minByOrNull { it.lastSeen }?.let { sessions.remove(it.id) }
        val entry = Entry(UUID.randomUUID().toString(), user, project, address, now, now)
        sessions[entry.id] = entry
        return entry
    }

    /** True when [id] is a live session of [user]. */
    fun heartbeat(id: String, user: String): Boolean {
        val now = clock.instant()
        val entry = sessions[id]?.takeIf { it.user == user && !expired(it, now) } ?: return false
        entry.lastSeen = now
        return true
    }

    /** The session [id] while its heartbeat is current, or null once it is closed or has expired. */
    fun live(id: String): Entry? = sessions[id]?.takeIf { !expired(it, clock.instant()) }

    fun close(id: String, user: String): Boolean {
        val entry = sessions[id]?.takeIf { it.user == user } ?: return false
        return sessions.remove(entry.id, entry)
    }

    fun active(): List<Entry> {
        val now = clock.instant()
        prune(now)
        return sessions.values.sortedBy { it.openedAt }
    }

    private fun expired(entry: Entry, now: Instant) = entry.lastSeen.plusSeconds(TTL_SECONDS).isBefore(now)

    private fun prune(now: Instant) {
        sessions.values.removeIf { expired(it, now) }
    }

    companion object {
        const val TTL_SECONDS = 45L
        const val HEARTBEAT_SECONDS = 15
        const val MAX_PER_USER = 20
        const val MAX_TOTAL = 500
    }
}

class DesignerSessionApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get(PATH) { ctx -> ProjectsApi.json(ctx, 200, document()) }
        app.post(PATH) { ctx -> open(ctx) }
        app.put("$PATH/{id}") { ctx -> heartbeat(ctx) }
        app.delete("$PATH/{id}") { ctx -> close(ctx) }
        app.get(LAUNCHER) { ctx -> launcher(ctx) }
    }

    private fun document(): Map<String, Any?> = linkedMapOf(
        "sessions" to gw.designerSessions.active().map(::view),
        "ttlSeconds" to DesignerSessions.TTL_SECONDS,
        "launch" to linkedMapOf("label" to "Launch Control Canvas", "href" to STUDIO_PATH),
        "download" to linkedMapOf("label" to "Download Control Canvas shortcut", "href" to LAUNCHER, "filename" to LAUNCHER_FILE),
    )

    private fun view(entry: DesignerSessions.Entry) = linkedMapOf<String, Any?>(
        "id" to entry.id,
        "user" to entry.user,
        "project" to entry.project,
        "address" to entry.address,
        "openedAt" to entry.openedAt.toString(),
        "lastSeen" to entry.lastSeen.toString(),
    )

    private fun open(ctx: Context) {
        val body = ProjectsApi.body(ctx)
        val unknown = body.keys - setOf("project")
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown fields: ${unknown.sorted().joinToString()}")
        val project = when (val raw = body["project"]) {
            null -> null
            is String -> raw.takeIf { it.isNotEmpty() }?.let { ProjectsApi.projectName(it) }
            else -> throw Problems.unprocessable("project must be a string")
        }
        // SEC-020: opening a project in the Designer needs its view roles.
        if (project != null && gw.projectService.exists(project)) gw.designerGuard.require(ctx, project, org.oip.config.DesignerAction.VIEW)
        val (subject, _) = GatewayAuth.subject(ctx)
        val entry = gw.designerSessions.open(subject.principal, project, AuditContext.current()?.host.orEmpty())
        ProjectsApi.json(ctx, 201, view(entry) + mapOf("heartbeatSeconds" to DesignerSessions.HEARTBEAT_SECONDS))
    }

    private fun heartbeat(ctx: Context) {
        val (subject, _) = GatewayAuth.subject(ctx)
        if (!gw.designerSessions.heartbeat(ctx.pathParam("id"), subject.principal)) {
            throw Problems.notFound("no such Designer session")
        }
        ctx.status(204)
    }

    private fun close(ctx: Context) {
        val (subject, _) = GatewayAuth.subject(ctx)
        val id = ctx.pathParam("id")
        if (!gw.designerSessions.close(id, subject.principal)) {
            throw Problems.notFound("no such Designer session")
        }
        // SEC-020: the resources this Designer was editing are free again.
        gw.designerLocks.releaseSession(id)
        ctx.status(204)
    }

    /** An internet shortcut (`.url`) to the Designer at the address the caller used to reach this gateway. */
    private fun launcher(ctx: Context) {
        val host = ctx.viaHost(gw)?.takeIf { it.matches(HOST) } ?: throw Problems.badRequest("no usable Host header")
        val scheme = if (ctx.viaScheme(gw) == "https") "https" else "http"
        val prefix = ctx.viaPrefix(gw)
        ctx.contentType("text/plain; charset=utf-8")
            .header("Content-Disposition", "attachment; filename=\"$LAUNCHER_FILE\"")
            .result("[InternetShortcut]\r\nURL=$scheme://$host$prefix$STUDIO_PATH\r\n")
    }

    companion object {
        const val PATH = "/api/v1/designer/sessions"
        const val LAUNCHER = "/api/v1/designer/launcher"
        const val STUDIO_PATH = "/studio"
        const val LAUNCHER_FILE = "control-canvas.url"
        private val HOST = Regex("^[A-Za-z0-9.\\-\\[\\]:]{1,255}$")
    }
}
