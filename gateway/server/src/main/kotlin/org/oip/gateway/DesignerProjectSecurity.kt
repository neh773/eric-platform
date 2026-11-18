package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.ConfigBatches
import org.oip.config.DesignerAction
import org.oip.config.ProjectService
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.AuditActions
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import org.oip.security.Authorizer
import org.oip.security.Permissions
import org.oip.security.Subject
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Resource locks (SEC-020). A Designer that starts editing a project resource takes its lock; while the lock is held,
 * every other user (and the same user's other Designer sessions) is refused a save or delete of that resource with
 * 423. A lock belongs to one Designer session ([DesignerSessions]) and counts only while that session is live, so it
 * ends when the holder saves the resource, releases it, closes the Designer, or stops sending heartbeats. A user with
 * `users.admin` may break any lock. Locks are in memory: they describe who is editing now, like the session list.
 * Doc source: FEATURES SEC-020.
 */
class DesignerLocks(private val clock: Clock, private val sessions: DesignerSessions) {
    data class Key(val project: String, val module: String, val kind: String, val name: String) {
        val path: String get() = "$module/$kind/$name"

        companion object {
            fun of(id: ResourceId): Key? =
                if (id.scope.type == "project") Key(id.scope.qualifier!!, id.module, id.kind, id.name) else null
        }
    }

    data class Lock(val key: Key, val user: String, val session: String, val acquiredAt: Instant)

    sealed interface Acquired {
        val lock: Lock
        data class Taken(override val lock: Lock, val renewed: Boolean) : Acquired
        data class Held(override val lock: Lock) : Acquired
    }

    private val locks = ConcurrentHashMap<Key, Lock>()

    /** The lock on [key] while its session is live; a lock whose session ended is dropped here. */
    fun live(key: Key): Lock? {
        val lock = locks[key] ?: return null
        if (sessions.live(lock.session) != null) return lock
        locks.remove(key, lock)
        return null
    }

    /** Take [key] for [user]'s Designer [session], or report who holds it. The session must be live and the user's. */
    @Synchronized
    fun acquire(key: Key, user: String, session: String): Acquired {
        val entry = sessions.live(session)
        require(entry != null && entry.user == user) { "no live Designer session $session of this user" }
        live(key)?.let { held ->
            if (held.user != user || held.session != session) return Acquired.Held(held)
            return Acquired.Taken(held, renewed = true)
        }
        if (locks.size >= MAX_TOTAL) prune()
        require(locks.size < MAX_TOTAL) { "too many resource locks are held on this gateway" }
        require(locks.values.count { it.session == session } < MAX_PER_SESSION) { "this Designer session holds $MAX_PER_SESSION locks already" }
        val lock = Lock(key, user, session, clock.instant())
        locks[key] = lock
        return Acquired.Taken(lock, renewed = false)
    }

    /** Drop [lock] if it is still the lock on its key. */
    fun release(lock: Lock): Boolean = locks.remove(lock.key, lock)

    fun releaseSession(session: String) {
        locks.values.removeIf { it.session == session }
    }

    fun list(project: String): List<Lock> {
        prune()
        return locks.values.filter { it.key.project == project }.sortedBy { it.key.path }
    }

    /** Whether a write by [user] from Designer session [session] (null when the request names none) would overwrite [lock]. */
    fun blocks(lock: Lock, user: String, session: String?): Boolean =
        lock.user != user || (session != null && session != lock.session)

    private fun prune() {
        for ((key, lock) in locks) if (sessions.live(lock.session) == null) locks.remove(key, lock)
    }

    companion object {
        const val MAX_PER_SESSION = 1_000
        const val MAX_TOTAL = 50_000
        /** The Designer sends its session id in this header with every save, so a lock it holds is its own. */
        const val SESSION_HEADER = "X-OIP-Designer-Session"
    }
}

/**
 * Project permissions in the Designer (SEC-020). A project's settings name required Designer roles for four actions
 * (`designerViewRoles`, `designerSaveRoles`, `designerDeleteRoles`, `designerProtectRoles`). An empty list lets every
 * user who may use the Designer take that action; otherwise the caller needs one of the listed roles. An API key has
 * no roles, so it passes only an empty list. These checks come on top of the route permissions (`gateway.write`,
 * `designer.login`), never instead of them, and the gateway applies them on every route that reaches a project's
 * resources:
 *
 * - View: a read of the project's Designer resources through the config API, opening a Designer session on it.
 * - Save: any create, change or delete of a resource in the project scope (config API item writes, the config batch,
 *   project import). Changing the project settings is a save too; a caller with `users.admin` may change them without
 *   the save roles, so an administrator can always recover a project nobody can save.
 * - Delete: deleting or renaming the project.
 * - Protect resources: marking a resource protected or not, and any write of a protected resource (or a change of
 *   the settings' `protectedResources`, again also open to `users.admin`).
 *
 * A write also fails with 423 while another Designer holds the resource's lock ([DesignerLocks]); a write by the
 * holder releases it.
 * Doc source: FEATURES SEC-020.
 */
class DesignerProjectGuard(
    private val projects: ProjectService,
    private val authorizer: Authorizer,
    private val locks: DesignerLocks,
) {
    fun settings(project: String): ProjectService.Settings = projects.settings(project)

    /** The caller's roles; an API key holds none. */
    fun roles(ctx: Context): Set<String> = when (val subject = GatewayAuth.subject(ctx).first) {
        is Subject.User -> subject.roles
        else -> emptySet()
    }

    fun allowed(ctx: Context, settings: ProjectService.Settings, action: DesignerAction): Boolean {
        val required = settings.designerRoles(action)
        return required.isEmpty() || roles(ctx).any { it in required }
    }

    fun isAdmin(ctx: Context): Boolean {
        val (subject, connection) = GatewayAuth.subject(ctx)
        return authorizer.authorize(subject, Permissions.USERS_ADMIN, connection).allowed
    }

    fun require(ctx: Context, project: String, action: DesignerAction, settings: ProjectService.Settings = settings(project)) {
        if (allowed(ctx, settings, action)) return
        throw Problems.forbidden(
            "${action.label} in project '$project' needs one of the Designer roles ${settings.designerRoles(action).joinToString(", ")}",
            SLUG,
        )
    }

    /** Refuse a read of [module] resources in [scope] when the caller may not view that project in the Designer. */
    fun checkRead(ctx: Context, module: String, scope: Scope) {
        if (scope.type != "project" || module != DESIGNER_MODULE) return
        val project = scope.qualifier!!
        if (projects.exists(project)) require(ctx, project, DesignerAction.VIEW)
    }

    /** Whether a listing may show [id] to the caller: Designer resources of a project it may not view are left out. */
    fun mayList(ctx: Context, id: ResourceId): Boolean {
        if (id.scope.type != "project" || id.module != DESIGNER_MODULE) return true
        return allowed(ctx, settings(id.scope.qualifier!!), DesignerAction.VIEW)
    }

    /**
     * Refuse writing [id] (from [before] to [after]; [after] null deletes it) when the project's permissions, its
     * protected resources, or another Designer's lock forbid it. Gateway-scope resources are not checked here.
     */
    fun checkWrite(ctx: Context, id: ResourceId, before: Map<String, Any?>?, after: Map<String, Any?>?) {
        val key = DesignerLocks.Key.of(id) ?: return
        val settings = settings(key.project)
        if (ProjectService.isSettings(id.module, id.kind)) {
            val old = ProjectService.Settings.fromSpec(before)
            val new = if (after == null) ProjectService.Settings() else ProjectService.Settings.fromSpec(after)
            checkSettingsChange(ctx, key.project, settings, changed = old != new, protectionChanged = old.protectedResources != new.protectedResources)
        } else {
            require(ctx, key.project, DesignerAction.SAVE, settings)
            if (key.path in settings.protectedResources) requireProtect(ctx, key, settings)
        }
        checkLock(ctx, key)
    }

    /** A resource in a project archive about to be imported over [id]: the archive's content is not known yet. */
    fun checkImport(ctx: Context, id: ResourceId) {
        val key = DesignerLocks.Key.of(id) ?: return
        val settings = settings(key.project)
        if (ProjectService.isSettings(id.module, id.kind)) {
            checkSettingsChange(ctx, key.project, settings, changed = true, protectionChanged = true)
        } else {
            require(ctx, key.project, DesignerAction.SAVE, settings)
            if (key.path in settings.protectedResources) requireProtect(ctx, key, settings)
        }
        checkLock(ctx, key)
    }

    /** `PATCH /api/v1/projects/{name}` with [keys]: a project save, open to `users.admin` as well. */
    fun checkSettingsPatch(ctx: Context, project: String, keys: Set<String>) {
        if (!projects.exists(project)) return
        checkSettingsChange(ctx, project, settings(project), keys.isNotEmpty(), "protectedResources" in keys)
    }

    private fun checkSettingsChange(ctx: Context, project: String, settings: ProjectService.Settings, changed: Boolean, protectionChanged: Boolean) {
        if (!changed || isAdmin(ctx)) return
        require(ctx, project, DesignerAction.SAVE, settings)
        if (protectionChanged) require(ctx, project, DesignerAction.PROTECT, settings)
    }

    private fun requireProtect(ctx: Context, key: DesignerLocks.Key, settings: ProjectService.Settings) {
        if (allowed(ctx, settings, DesignerAction.PROTECT)) return
        throw Problems.forbidden(
            "${key.path} is protected in project '${key.project}': changing it needs one of the Designer roles " +
                settings.designerProtectRoles.joinToString(", "),
            PROTECTED_SLUG,
        )
    }

    /** Refuse the write when another Designer holds the lock on [key]. */
    fun checkLock(ctx: Context, key: DesignerLocks.Key) {
        val lock = locks.live(key) ?: return
        if (locks.blocks(lock, GatewayAuth.subject(ctx).first.principal, session(ctx))) throw locked(lock)
    }

    /** After a successful write of [id]: the writer's own lock on it is released (SEC-020, release on save). */
    fun written(ctx: Context, id: ResourceId) {
        val key = DesignerLocks.Key.of(id) ?: return
        val lock = locks.live(key) ?: return
        if (!locks.blocks(lock, GatewayAuth.subject(ctx).first.principal, session(ctx))) locks.release(lock)
    }

    private fun session(ctx: Context): String? = ctx.header(DesignerLocks.SESSION_HEADER)?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val SLUG = "designer-permission"
        const val PROTECTED_SLUG = "resource-protected"
        const val LOCKED_SLUG = "resource-locked"

        fun lockView(lock: DesignerLocks.Lock, mine: Boolean): Map<String, Any?> = linkedMapOf<String, Any?>(
            "module" to lock.key.module,
            "kind" to lock.key.kind,
            "name" to lock.key.name,
            "path" to lock.key.path,
            "user" to lock.user,
            "acquiredAt" to lock.acquiredAt.toString(),
            "mine" to mine,
        ).also { if (mine) it["session"] = lock.session }

        fun locked(lock: DesignerLocks.Lock) = ProblemException(
            423, LOCKED_SLUG, "Locked",
            "${lock.key.path} in project '${lock.key.project}' is being edited by ${lock.user}",
            extensions = mapOf("lock" to lockView(lock, mine = false)),
        )
    }
}

/**
 * Designer project security routes (SEC-020), under `/api/v1/designer/projects/{project}`:
 *
 * - `GET .../access` (gateway.read): what the caller may do in the project (view, save, delete, protect, break
 *   locks), the required roles of each action, the protected resources, and the live locks.
 * - `GET .../locks` (gateway.read): the live locks.
 * - `POST .../locks` `{module, kind, name, session}` (gateway.write, designer.login): take a resource's lock for one
 *   of the caller's live Designer sessions. 201 when taken, 200 when the session already holds it, 423 with the
 *   holder when another Designer does. Taking a lock needs the save roles (and the protect roles for a protected
 *   resource), since only someone who may save it can hold others off.
 * - `DELETE .../locks/{module}/{kind}/{name}` (gateway.write): the holder releases its lock (designer.login), or
 *   with `?break=true` a caller with `users.admin` breaks another user's lock (audited as `designer lock broken`).
 * - `PUT` and `DELETE .../protected/{module}/{kind}/{name}` (gateway.write, designer.login): protect or unprotect a
 *   resource; needs the protect roles. One `project resource protected` or `unprotected` audit record each.
 * Doc source: FEATURES SEC-020.
 */
class DesignerProjectApi(private val gw: Gateway) {
    private val guard get() = gw.designerGuard
    private val locks get() = gw.designerLocks

    fun register(app: Javalin) {
        app.get("$BASE/{project}/access") { ctx -> ProjectsApi.json(ctx, 200, access(ctx, project(ctx))) }
        app.get("$BASE/{project}/locks") { ctx ->
            val project = project(ctx)
            ProjectsApi.json(ctx, 200, mapOf("locks" to lockViews(ctx, project)))
        }
        app.post("$BASE/{project}/locks") { ctx -> acquire(ctx) }
        app.delete("$BASE/{project}/locks/{module}/{kind}/{name}") { ctx -> release(ctx) }
        app.put("$BASE/{project}/protected/{module}/{kind}/{name}") { ctx -> protect(ctx, true) }
        app.delete("$BASE/{project}/protected/{module}/{kind}/{name}") { ctx -> protect(ctx, false) }
    }

    private fun project(ctx: Context): String {
        val name = ProjectsApi.projectName(ctx.pathParam("project"))
        if (!gw.projectService.exists(name)) throw Problems.notFound("project '$name' not found")
        return name
    }

    private fun access(ctx: Context, project: String): Map<String, Any?> {
        val settings = guard.settings(project)
        return linkedMapOf(
            "project" to project,
            "user" to GatewayAuth.subject(ctx).first.principal,
            "allowed" to DesignerAction.entries.associate { it.name.lowercase() to guard.allowed(ctx, settings, it) },
            "requiredRoles" to DesignerAction.entries.associate { it.name.lowercase() to settings.designerRoles(it) },
            "breakLocks" to guard.isAdmin(ctx),
            "protected" to settings.protectedResources,
            "locks" to lockViews(ctx, project),
        )
    }

    private fun lockViews(ctx: Context, project: String): List<Map<String, Any?>> {
        val me = GatewayAuth.subject(ctx).first.principal
        return locks.list(project).map { DesignerProjectGuard.lockView(it, it.user == me) }
    }

    private fun acquire(ctx: Context) {
        val project = project(ctx)
        val body = ProjectsApi.body(ctx)
        val unknown = body.keys - setOf("module", "kind", "name", "session")
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown fields: ${unknown.sorted().joinToString()}")
        fun text(key: String) = body[key] as? String ?: throw Problems.unprocessable("$key is required")
        val id = resourceId(project, text("module"), text("kind"), text("name"))
        val session = text("session")
        val key = DesignerLocks.Key.of(id)!!
        val settings = guard.settings(project)
        guard.require(ctx, project, DesignerAction.SAVE, settings)
        if (key.path in settings.protectedResources) guard.require(ctx, project, DesignerAction.PROTECT, settings)
        val user = GatewayAuth.subject(ctx).first.principal
        val result = try {
            locks.acquire(key, user, session)
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable(e.message!!)
        }
        when (result) {
            is DesignerLocks.Acquired.Held -> throw DesignerProjectGuard.locked(result.lock)
            is DesignerLocks.Acquired.Taken ->
                ProjectsApi.json(ctx, if (result.renewed) 200 else 201, DesignerProjectGuard.lockView(result.lock, mine = true))
        }
    }

    private fun release(ctx: Context) {
        val project = project(ctx)
        val id = resourceId(project, ctx.pathParam("module"), ctx.pathParam("kind"), ctx.pathParam("name"))
        val key = DesignerLocks.Key.of(id)!!
        val brk = when (ctx.queryParam("break")?.lowercase()) {
            null, "false", "0" -> false
            "true", "1" -> true
            else -> throw Problems.badRequest("break must be true or false")
        }
        val lock = locks.live(key) ?: throw Problems.notFound("${key.path} is not locked")
        val user = GatewayAuth.subject(ctx).first.principal
        if (brk) {
            GatewayAuth.requirePermission(ctx, gw.security.authorization, Permissions.USERS_ADMIN)
            locks.release(lock)
            audit(AuditActions.DESIGNER_LOCK_BROKEN, "project/$project/${key.path}", "holder=${lock.user}")
        } else {
            GatewayAuth.requirePermission(ctx, gw.security.authorization, Permissions.DESIGNER_LOGIN)
            if (lock.user != user) throw DesignerProjectGuard.locked(lock)
            locks.release(lock)
        }
        ctx.status(204)
    }

    private fun protect(ctx: Context, on: Boolean) {
        val project = project(ctx)
        val id = resourceId(project, ctx.pathParam("module"), ctx.pathParam("kind"), ctx.pathParam("name"))
        val key = DesignerLocks.Key.of(id)!!
        guard.require(ctx, project, DesignerAction.PROTECT)
        if (ProjectService.isSettings(id.module, id.kind)) throw Problems.unprocessable("the project settings resource cannot be protected")
        val current = guard.settings(project).protectedResources
        if (on && key.path !in current && gw.projectService.resolve(id) == null) throw Problems.notFound("${key.path} not found in project '$project'")
        val next = if (on) (current + key.path).distinct() else current - key.path
        if (next != current) {
            val action = if (on) AuditActions.PROJECT_RESOURCE_PROTECTED else AuditActions.PROJECT_RESOURCE_UNPROTECTED
            try {
                ConfigBatches.run("project/$project", ProjectsApi.actor(), action, "project/$project/${key.path}", null) {
                    gw.projectService.update(project, mapOf("protectedResources" to next), null, ProjectsApi.actor())
                }
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable(e.message!!)
            }
        }
        ProjectsApi.json(ctx, 200, mapOf("path" to key.path, "protected" to on, "protectedResources" to next))
    }

    private fun resourceId(project: String, module: String, kind: String, name: String): ResourceId = try {
        ResourceId(module, Scope.project(project), kind, name)
    } catch (e: IllegalArgumentException) {
        throw Problems.unprocessable(e.message ?: "not a resource path")
    }

    private fun audit(action: String, target: String, detail: String) {
        if (!gw.security.auditProfile.get().enabled) return
        val context = AuditContext.current()
        gw.security.audit.append(
            AuditDraft(
                timestampMillis = gw.clock.millis(),
                actor = context?.actor ?: "api",
                action = action,
                status = AuditStatus.SUCCESS,
                actorHost = context?.host ?: "",
                target = target,
                detail = detail.take(500),
                context = "gateway-http",
            ),
        )
    }

    companion object {
        const val BASE = "/api/v1/designer/projects"
    }
}
