package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.ProjectService
import org.oip.config.ResourceCodec
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.Subject
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What an HMI session reads to run a project (review-4 F5 and F12, HMP-095, SEC-017).
 *
 * - `GET /api/v1/hmi/projects`: the names of the launchable projects (enabled and runnable, PRO-004), for `/hmi/`.
 * - `GET /api/v1/hmi/projects/{name}`: the project as a session runs it. Its views, page configuration and style
 *   classes, each read through project inheritance (PRO-002), and a `signature` that changes whenever any of them,
 *   or the project's settings, change. A project that is disabled or not runnable answers only
 *   `{name, launchable: false, enabled, runnable}`, without its resources. 404 when the gateway has no such project.
 *
 * Both need `tags.read`, the permission that opens the session socket, not `gateway.read`. A viewer can run the HMI,
 * and an HMI user cannot read the rest of the gateway configuration through this route. It returns nothing else:
 * no script hook source (scripts run on the gateway), no other module's resources and no other project's.
 *
 * Change notification (F12): `?after=<signature>&waitMs=<n>` holds the request until the project's signature differs
 * from `after`, or `waitMs` (at most [MAX_WAIT_MS]) has passed. It then answers 304 with no body. The request waits
 * on a change event from the config store, not on a timer, so an open session costs one parked request instead of
 * four config reads every 2 s. At most [MAX_WAITERS] requests wait at once (gateway-wide) and [MAX_WAITERS_PER_PRINCIPAL]
 * per caller; past that a request is answered at once, as if `waitMs` were 0.
 *
 * Session and view security (HMP-092, P4-04). A project's settings may name `sessionRoles` (the caller needs one of
 * them) and a `sessionIdentityProvider` (the caller must have logged in through it). A caller who fails either is
 * refused the whole project: 403 with code `session_refused`, and nothing of the project in the answer; such a
 * project is also left off the list. A view document may carry `security.roles`; a caller with none of those roles
 * gets the project without that view: its path is listed in `deniedViews` and nothing else of it is sent (no
 * document, no title, no bindings). Roles stand in for security levels here (DIFFERENCES 282). An API key has no
 * roles, so it opens only projects and views that name none. The `signature` is computed per caller, over what that
 * caller is sent.
 *
 * Security levels (SEC-017) gate the same places, and components too: a project's `sessionLevels`, a view document's
 * `security.levels`, and the `security` of a component or container inside a view, each with `requireAll`. A
 * component the caller may not see is removed from the view before it is sent ([HmiSecurity]).
 *
 * Doc source: -
 * Doc source: FEATURES HMP-092.
 */
class HmiProjectApi(
    private val store: FileConfigStore,
    private val projects: ProjectService,
    /** Plain text of a `secret://` reference, for an HTTP source's credential header. */
    private val secret: (String) -> String = { throw IllegalStateException("no secret store") },
) : AutoCloseable {
    private val waiters = CopyOnWriteArrayList<CompletableFuture<Unit>>()
    private val waiting = AtomicInteger()
    private val perPrincipal = ConcurrentHashMap<String, Int>()
    private val responders: ExecutorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("hmi-project-", 0).factory())
    private val subscription: AutoCloseable = store.subscribe { event -> if (relevant(event)) wake() }

    fun register(app: Javalin) {
        app.get(LIST) { ctx ->
            val viewer = viewer(ctx)
            val names = projects.list().filter { it.settings.launchable && sessionRefusal(it.settings, viewer) == null }.map { it.name }
            ctx.contentType("application/json").result(Json.write(mapOf("projects" to names)))
        }
        app.get("$LIST/{name}") { ctx -> project(ctx) }
        app.post("$LIST/{name}/http/{source}") { ctx -> httpFetch(ctx) }
    }

    /**
     * HTTP binding fetch (HMP-114): the project's own `HttpSource`, run as a GET by the gateway. The caller must be
     * able to run the project; the answer carries the parsed JSON or a short error, never a header or the upstream address.
     */
    private fun httpFetch(ctx: Context) {
        val name = ctx.pathParam("name")
        if (runCatching { Scope.project(name) }.isFailure) throw Problems.notFound("project '$name' not found")
        val settings = projects.info(name)?.settings ?: throw Problems.notFound("project '$name' not found")
        if (!settings.launchable || sessionRefusal(settings, viewer(ctx)) != null) throw Problems.notFound("project '$name' not found")
        val sourceName = ctx.pathParam("source")
        val source = runCatching { ResourceId(DESIGNER_MODULE, Scope.project(name), HTTP_SOURCE_KIND, sourceName) }.getOrNull()
            ?.let { projects.resolve(it) }?.resource?.spec ?: throw Problems.notFound("HTTP source '$sourceName' not found")
        val body = runCatching { Json.parse(ctx.body()) }.getOrNull() ?: throw Problems.badRequest("body must be a JSON object")
        val request = try {
            HmiHttpFetch.checkedPath(body["path"]) to HmiHttpFetch.checkedQuery(body["query"])
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable(e.message ?: "invalid request")
        }
        ctx.contentType("application/json").result(Json.write(HmiHttpFetch.fetch(source, request.first, request.second, secret)))
    }

    private fun project(ctx: Context) {
        val name = ctx.pathParam("name")
        if (runCatching { Scope.project(name) }.isFailure) throw Problems.notFound("project '$name' not found")
        val after = ctx.queryParam("after")
        val waitMs = (ctx.queryParam("waitMs")?.let { it.toLongOrNull() ?: throw Problems.badRequest("waitMs must be an integer") } ?: 0L)
            .coerceIn(0L, MAX_WAIT_MS)
        val viewer = viewer(ctx)
        val now = bundle(name, viewer)
        if (after == null || waitMs == 0L || now?.signature != after) return respond(ctx, name, now, after)
        val principal = GatewayAuth.subject(ctx).first.principal
        if (!admit(principal)) return respond(ctx, name, now, after)
        // Nothing in this request writes configuration; its audit scope is closed here, on the request thread.
        GatewayAuth.releaseAudit(ctx)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        ctx.future {
            awaitChange(name, viewer, after, deadline)
                .whenComplete { _, _ -> release(principal) }
                .thenAccept { changed -> respond(ctx, name, changed, after) }
        }
    }

    /** Completes with the project once its signature differs from [after], or with the unchanged one at [deadline]. */
    private fun awaitChange(name: String, viewer: Viewer, after: String, deadline: Long): CompletableFuture<Bundle?> {
        val signal = CompletableFuture<Unit>()
        waiters += signal
        // A change between the caller's read and this registration is caught by reading again after it.
        val current = bundle(name, viewer)
        if (current?.signature != after) {
            waiters -= signal
            return CompletableFuture.completedFuture(current)
        }
        val left = deadline - System.nanoTime()
        if (left <= 0) {
            waiters -= signal
            return CompletableFuture.completedFuture(current)
        }
        return signal.completeOnTimeout(Unit, left, TimeUnit.NANOSECONDS)
            .thenComposeAsync({ _ ->
                waiters -= signal
                val next = bundle(name, viewer)
                if (next?.signature != after || System.nanoTime() >= deadline) CompletableFuture.completedFuture(next)
                else awaitChange(name, viewer, after, deadline)
            }, responders)
    }

    private fun respond(ctx: Context, name: String, bundle: Bundle?, after: String?) {
        if (bundle == null) throw Problems.notFound("project '$name' not found")
        ctx.header("Cache-Control", "no-store")
        bundle.refusal?.let { throw Problems.forbidden(it, SESSION_REFUSED) }
        if (after != null && bundle.signature == after) {
            ctx.status(304)
            return
        }
        ctx.status(200).contentType("application/json").result(bundle.json)
    }

    private class Bundle(val signature: String, val json: String, val refusal: String? = null)

    /**
     * Who is asking: the roles, identity provider and security levels (with ancestors) the session, view and component
     * security checks use.
     */
    data class Viewer(
        val roles: Set<String>,
        val provider: String?,
        val levels: Set<String> = setOf(org.oip.security.SecurityLevels.PUBLIC, org.oip.security.SecurityLevels.AUTHENTICATED),
    )

    private fun viewer(ctx: Context): Viewer {
        val roles = when (val subject = GatewayAuth.subject(ctx).first) {
            is Subject.User -> subject.roles
            is Subject.ApiKey -> emptySet()
        }
        return Viewer(roles, GatewayAuth.provider(ctx), GatewayAuth.levels(ctx))
    }

    private fun bundle(name: String, viewer: Viewer): Bundle? {
        val info = projects.info(name)
        val scope = Scope.project(name)
        val views = projects.resolveList(name, DESIGNER_MODULE, VIEW_KIND)
        val pageConfig = projects.resolve(ResourceId(DESIGNER_MODULE, scope, PAGE_CONFIG_KIND, PAGE_CONFIG_RESOURCE))
        val styles = projects.resolve(ResourceId(DESIGNER_MODULE, scope, STYLE_CLASSES_KIND, STYLE_CLASSES_RESOURCE))
        val translation = Translations.spec(projects, name)
        if (info == null && views.isEmpty() && pageConfig == null) return null
        val settings = info?.settings ?: ProjectService.Settings()
        if (settings.launchable) {
            sessionRefusal(settings, viewer)?.let { reason ->
                // The refusal changes with the project's settings, so a waiting read still sees a change.
                val signature = digest(Json.write(mapOf("refused" to reason)))
                return Bundle(signature, "", "project '$name': $reason")
            }
        }
        val (allowed, denied) = views.partition { viewAllowed(it.resource.spec, viewer) }
        val body: Map<String, Any?> = if (!settings.launchable) {
            linkedMapOf("name" to name, "launchable" to false, "enabled" to settings.enabled, "runnable" to settings.runnable)
        } else {
            linkedMapOf(
                "name" to name,
                "launchable" to true,
                "enabled" to settings.enabled,
                "runnable" to settings.runnable,
                "views" to allowed.map { resource(name, it, viewer) },
                "deniedViews" to denied.mapNotNull { it.resource.spec["path"] as? String }.sorted(),
                "pageConfig" to pageConfig?.let { resource(name, it) },
                "styleClasses" to styles?.let { resource(name, it) },
                "defaultTagProvider" to settings.defaultTagProvider,
                "defaultDatabase" to settings.defaultDatabase,
                "sessionTheme" to settings.sessionTheme,
                "sessionLocale" to settings.sessionLocale,
                "touchscreenMode" to settings.touchscreenMode,
                // Terms for the session locale, and the locales that have any (HMI-005).
                "translations" to linkedMapOf(
                    "locale" to settings.sessionLocale.ifEmpty { "en-US" },
                    "messages" to Translations.messages(translation, settings.sessionLocale.ifEmpty { "en-US" }),
                    "locales" to Translations.locales(translation),
                    // Every locale's terms, so a session that switches language (HMP-125) needs no second request.
                    "catalog" to Translations.locales(translation).associateWith { Translations.messages(translation, it) },
                ),
                // The gateway's default time zone; a session starts in it unless its own zone is set (HMP-101).
                "gatewayTimeZone" to java.time.ZoneId.systemDefault().id,
            )
        }
        val signature = digest(Json.write(body))
        return Bundle(signature, Json.write(body + ("signature" to signature)))
    }

    private fun digest(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest, 0, 16)
    }

    private fun resource(project: String, found: ProjectService.Resolved, viewer: Viewer? = null): Map<String, Any?> {
        var map = ResourceCodec.toMap(found.resource)
        if (viewer != null) {
            // SEC-017: components the viewer may not see leave the document here, before anything is sent.
            @Suppress("UNCHECKED_CAST")
            val spec = map["spec"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val document = spec?.get("document") as? Map<String, Any?>
            if (spec != null && document != null) map = map + ("spec" to (spec + ("document" to HmiSecurity.strip(document, viewer))))
        }
        val base = map + ("scope" to found.id.scope.path)
        return if (found.project == project) base else base + ("inheritedFrom" to found.project)
    }

    private fun relevant(event: ConfigEvent): Boolean {
        val id = event.id ?: return true
        if (id.scope.type != "project") return false
        return id.module == DESIGNER_MODULE || ProjectService.isSettings(id.module, id.kind)
    }

    /** Every waiting request reads its project again; those whose signature did not change wait on. */
    private fun wake() {
        for (signal in waiters) signal.complete(Unit)
    }

    private fun admit(principal: String): Boolean {
        if (waiting.incrementAndGet() > MAX_WAITERS) {
            waiting.decrementAndGet()
            return false
        }
        var taken = false
        perPrincipal.compute(principal) { _, held ->
            val now = held ?: 0
            if (now >= MAX_WAITERS_PER_PRINCIPAL) now else (now + 1).also { taken = true }
        }
        if (!taken) waiting.decrementAndGet()
        return taken
    }

    private fun release(principal: String) {
        waiting.decrementAndGet()
        perPrincipal.computeIfPresent(principal) { _, held -> if (held <= 1) null else held - 1 }
    }

    override fun close() {
        runCatching { subscription.close() }
        wake()
        responders.shutdownNow()
    }

    companion object {
        const val LIST = "/api/v1/hmi/projects"
        const val SESSION_REFUSED = "session_refused"

        /** Why [viewer] may not open a session of a project with [settings], or null when it may (HMP-092). */
        fun sessionRefusal(settings: ProjectService.Settings, viewer: Viewer): String? {
            if (settings.sessionRoles.isNotEmpty() && settings.sessionRoles.none { it in viewer.roles }) {
                return "a session needs one of the roles ${settings.sessionRoles.joinToString(", ")}"
            }
            val provider = settings.sessionIdentityProvider
            if (provider.isNotEmpty() && viewer.provider != provider) {
                return "a session needs a login through identity provider '$provider'"
            }
            val levels = HmiSecurity.Requirement(emptyList(), settings.sessionLevels, settings.sessionRequireAll)
            if (!levels.allows(viewer)) return "a session needs ${levels.describe()}"
            return null
        }

        /** The roles a view document's `security.roles` names; empty when it names none (anyone may open it). */
        fun viewRoles(spec: Map<String, Any?>): List<String> {
            val document = spec["document"] as? Map<*, *> ?: return emptyList()
            val security = document["security"] as? Map<*, *> ?: return emptyList()
            return (security["roles"] as? List<*>)?.filterIsInstance<String>()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }

        fun viewAllowed(spec: Map<String, Any?>, viewer: Viewer): Boolean = viewRequirements(spec).all { it.allows(viewer) }

        /**
         * The view document's `security` (roles and security levels) and its root container's own `security`, which
         * guards the whole view as well (SEC-017). Empty when neither names any.
         */
        fun viewRequirements(spec: Map<String, Any?>): List<HmiSecurity.Requirement> {
            val document = spec["document"] as? Map<*, *> ?: return emptyList()
            val root = document["root"] as? Map<*, *>
            return listOfNotNull(HmiSecurity.requirement(document["security"]), root?.let { HmiSecurity.requirement(it["security"]) })
        }

        /** What a refused caller lacks for the view of [spec], for the refusal message. */
        fun describeView(spec: Map<String, Any?>): String = viewRequirements(spec).joinToString(" and ") { it.describe() }
        const val MAX_WAIT_MS = 30_000L
        const val MAX_WAITERS = 2_000
        const val MAX_WAITERS_PER_PRINCIPAL = 16
    }
}
