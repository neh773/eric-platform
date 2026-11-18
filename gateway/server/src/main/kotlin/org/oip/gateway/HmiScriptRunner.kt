package org.oip.gateway

import org.oip.config.FileConfigStore
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.scripting.HmiScripts
import org.oip.scripting.ScriptLimits
import org.oip.tags.TagCaller
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs a project's HMI script hooks for [SessionHost] (D-5a).
 *
 * Where a hook's source comes from, first match wins:
 *  1. the saved view the call names (a `designer/project/<project>/View` resource whose `spec.path` is the view path): a script
 *     transform or script action in its document with that `hook` and its own `source`;
 *  2. the project's `designer/project/<project>/ScriptHooks/script-hooks` resource, `spec.hooks.<hook>.source`.
 * Sources are only ever read from the gateway's own project resources; a browser cannot send code to run.
 *
 * Scripts run on a small pool of platform threads (the script memory cap reads the thread allocation counter, which a
 * virtual thread does not have) with the gateway [limits]. The pool is the gateway-wide cap on HMI script runs; the
 * per-session cap is [SessionHost]'s. A call waits at most [maxWait] (below the browser's own 30 s call timeout): one
 * that has not started by then is answered busy, one still running is cancelled. Nothing here runs under the
 * tag-engine lock (P2-14).
 *
 * The script entries of each saved view are cached per project and view path, and the cache is cleared when any
 * designer resource or project setting changes.
 *
 * With [projects], views and the hook set resolve through project inheritance (PRO-002): a child project runs a
 * hook its parent defines unless it stores its own copy of that resource.
 */
class HmiScriptRunner(
    private val store: FileConfigStore,
    private val scripts: HmiScripts,
    /** Limits for each run; read at the run, so a settings change applies to the next one. */
    @Volatile var limits: () -> ScriptLimits,
    threads: Int = DEFAULT_THREADS,
    queue: Int = DEFAULT_QUEUE,
    private val maxWait: Duration = DEFAULT_MAX_WAIT,
    private val projects: org.oip.config.ProjectService? = null,
) : SessionHost.SessionScripts, AutoCloseable {
    private val counter = AtomicInteger()
    /** Hook name to source and guarding requirements of each saved view's script entries, by (project, view path). */
    private val viewHooks = ConcurrentHashMap<Pair<String, String>, Map<String, Hook>>()
    private val generation = AtomicLong()
    private val watch = store.subscribe { event ->
        val id = event.id
        if (id == null || id.module == DESIGNER_MODULE || org.oip.config.ProjectService.isSettings(id.module, id.kind)) {
            generation.incrementAndGet()
            viewHooks.clear()
        }
    }
    private val pool = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, ArrayBlockingQueue(queue)) { runnable ->
        Thread(runnable, "oip-hmi-script-${counter.incrementAndGet()}").apply { isDaemon = true }
    }.also { it.allowCoreThreadTimeOut(true) }

    override fun run(caller: TagCaller.User, call: SessionHost.ScriptCall): SessionHost.ScriptReply {
        // Review-4 F6 (PRO-004): a disabled or inheritance-only project does not run, so neither do its scripts. The
        // client names the project in the frame, so this is checked here, at every call.
        val launchable = try {
            projects?.let { it.info(call.project)?.settings?.launchable } ?: true.also { Scope.project(call.project) }
        } catch (e: IllegalArgumentException) {
            return SessionHost.ScriptReply.failed(e.message ?: "invalid script call")
        }
        if (!launchable) return SessionHost.ScriptReply.failed("project '${call.project}' is not running (disabled or not runnable)", PROJECT_NOT_RUNNING)
        // HMP-092: the session and view security the project route applies hold for script frames too, since the
        // client names the project and view in the frame.
        securityRefusal(caller, call)?.let { return SessionHost.ScriptReply.failed(it, HmiProjectApi.SESSION_REFUSED) }
        val found = try {
            hook(call.project, call.view, call.hook)
        } catch (e: IllegalArgumentException) {
            return SessionHost.ScriptReply.failed(e.message ?: "invalid script call")
        }
        // SEC-017: a hook inside a component the caller may not see, or a project hook that declares security
        // levels the caller does not hold, is refused here, whatever the browser sends.
        if (found != null) {
            val viewer = HmiProjectApi.Viewer(caller.roles, call.provider, call.levels)
            found.requirements.firstOrNull { !it.allows(viewer) }?.let {
                return SessionHost.ScriptReply.failed("script hook '${call.hook}' needs ${it.describe()}", HmiProjectApi.SESSION_REFUSED)
            }
        }
        val source = found?.source ?: return SessionHost.ScriptReply.failed(
            "unknown script hook '${call.hook}' in project '${call.project}'",
            SessionHost.ScriptReply.UNKNOWN_HOOK,
        )
        val started = AtomicBoolean(false)
        val abandoned = AtomicBoolean(false)
        val guard = HmiScripts.Guard(mayWrite = call.mayWrite, cancelled = { abandoned.get() || call.cancelled() })
        val future = try {
            pool.submit<HmiScripts.Outcome> {
                started.set(true)
                if (guard.cancelled()) HmiScripts.Outcome(false, null, emptyList(), "script cancelled", null)
                else execute(source, caller, call, guard)
            }
        } catch (_: RejectedExecutionException) {
            return SessionHost.ScriptReply.failed("busy: the gateway is running too many HMI scripts", SessionHost.ScriptReply.BUSY)
        }
        val outcome = try {
            future.get(maxWait.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            abandoned.set(true)
            future.cancel(false)
            return if (started.get()) SessionHost.ScriptReply.failed("script timed out")
            else SessionHost.ScriptReply.failed("busy: the gateway is running too many HMI scripts", SessionHost.ScriptReply.BUSY)
        } catch (e: ExecutionException) {
            return SessionHost.ScriptReply.failed(e.cause?.message ?: "script failed")
        } catch (_: InterruptedException) {
            abandoned.set(true)
            future.cancel(false)
            Thread.currentThread().interrupt()
            return SessionHost.ScriptReply.failed("script cancelled")
        }
        return if (outcome.ok) SessionHost.ScriptReply(true, outcome.value, outcome.navigations, null)
        else SessionHost.ScriptReply.failed(outcome.error ?: "script failed")
    }

    private fun execute(source: String, caller: TagCaller.User, call: SessionHost.ScriptCall, guard: HmiScripts.Guard): HmiScripts.Outcome {
        val name = "hmi.${call.project}.${call.hook}"
        return when (call.kind) {
            SessionHost.ScriptKind.TRANSFORM -> scripts.transform(source, caller, call.value, call.context, name, limits(), guard, call.project)
            SessionHost.ScriptKind.EVENT -> scripts.event(source, caller, call.event, call.context, name, limits(), guard, call.project)
        }
    }

    /** Why [caller] may not run scripts of [call]'s project or view (HMP-092), or null when it may. */
    private fun securityRefusal(caller: TagCaller.User, call: SessionHost.ScriptCall): String? {
        val projects = projects ?: return null
        val viewer = HmiProjectApi.Viewer(caller.roles, call.provider, call.levels)
        val settings = projects.info(call.project)?.settings ?: return null
        HmiProjectApi.sessionRefusal(settings, viewer)?.let { return "project '${call.project}': $it" }
        val view = call.view
        if (!view.isNullOrEmpty()) {
            val saved = projects.resolveList(call.project, DESIGNER_MODULE, VIEW_KIND).firstOrNull { it.resource.spec["path"] == view }
            if (saved != null && !HmiProjectApi.viewAllowed(saved.resource.spec, viewer)) {
                return "view '$view' of project '${call.project}' needs ${HmiProjectApi.describeView(saved.resource.spec)}"
            }
        }
        return null
    }

    /** A hook's source and the security requirements that guard it (SEC-017), outermost first. */
    internal data class Hook(
        val source: String,
        val requirements: List<HmiSecurity.Requirement>,
        /** The hook's `upload` settings (HMP-047): `maxBytes` and `contentTypes`. Null: the hook does not take uploaded files. */
        val upload: Map<*, *>? = null,
    )

    /** The hook's source, or null when neither the view nor the project's hook set defines it. */
    internal fun hookSource(project: String, view: String?, hook: String): String? = hook(project, view, hook)?.source

    /** The hook, from the view first and then the project's hook set, or null when neither defines it. */
    internal fun hook(project: String, view: String?, hook: String): Hook? {
        val scope = Scope.project(project)
        if (!view.isNullOrEmpty()) viewHooks(project, scope, view)[hook]?.let { return it }
        val hookSet = ResourceId(DESIGNER_MODULE, scope, SCRIPT_HOOKS_KIND, SCRIPT_HOOKS_RESOURCE)
        val stored = if (projects != null) projects.resolve(hookSet)?.resource else store.get(hookSet)
        val hooks = stored?.spec?.get("hooks") as? Map<*, *>
        val entry = hooks?.get(hook) as? Map<*, *> ?: return null
        val source = entry["source"] as? String ?: return null
        return Hook(source, listOfNotNull(HmiSecurity.requirement(entry["security"])), entry["upload"] as? Map<*, *>)
    }

    /** The saved view's script entries, from the cache or read once (only stored when no designer change came meanwhile). */
    private fun viewHooks(project: String, scope: Scope, view: String): Map<String, Hook> {
        val key = project to view
        viewHooks[key]?.let { return it }
        val seen = generation.get()
        val views = projects?.resolveList(project, DESIGNER_MODULE, VIEW_KIND)?.map { it.resource }
            ?: store.list(DESIGNER_MODULE, scope, VIEW_KIND).map { it.resource }
        val saved = views.firstOrNull { it.spec["path"] == view }
        val sources = LinkedHashMap<String, String>()
        val document = saved?.spec?.get("document")
        document?.let { collect(it, sources) }
        val guards = HmiSecurity.hookRequirements(document)
        val hooks = sources.mapValues { (name, source) -> Hook(source, guards[name].orEmpty()) }
        if (generation.get() == seen && viewHooks.size < MAX_CACHED_VIEWS) viewHooks[key] = hooks
        return hooks
    }

    /** Every `{kind: script, hook, source}` entry in a view document; the first entry of a hook wins. */
    private fun collect(node: Any?, into: MutableMap<String, String>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (node) {
            is Map<*, *> -> {
                val hook = node["hook"]
                val source = node["source"]
                if (node["kind"] == "script" && hook is String && source is String) into.putIfAbsent(hook, source)
                for (value in node.values) collect(value, into, depth + 1)
            }
            is List<*> -> for (value in node) collect(value, into, depth + 1)
        }
    }

    /** Scripts running now (tests and diagnostics). */
    fun running(): Int = pool.activeCount

    override fun close() {
        runCatching { watch.close() }
        pool.shutdownNow()
    }

    companion object {
        /** Script call refused because its project is disabled or not runnable (review-4 F6). */
        const val PROJECT_NOT_RUNNING = "project_not_running"
        const val DEFAULT_THREADS = 8
        const val DEFAULT_QUEUE = 64
        /** Below the browser's 30 s call timeout, so the gateway always answers first. */
        val DEFAULT_MAX_WAIT: Duration = Duration.ofSeconds(25)
        private const val MAX_CACHED_VIEWS = 4096
        private const val MAX_DEPTH = 64
    }
}
