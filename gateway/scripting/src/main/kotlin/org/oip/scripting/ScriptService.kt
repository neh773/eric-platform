package org.oip.scripting

import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.config.ChangeType
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.db.DatabaseService
import org.oip.tags.QualifiedValue
import org.oip.tags.TagCaller
import org.oip.tags.TagChange
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Project library, gateway event scripts, and the `system.*` host.
 *
 * Tag-change listeners only enqueue. Python runs on `oip-script-*` threads, never inside
 * the tag engine lock (P2-14). A library or event resource save replaces the source the
 * next run sees; tag-change scripts are subscribed again so `executionCount` starts over.
 *
 */
class ScriptService(
    private val tags: TagEngine,
    private val databases: DatabaseService?,
    private val clock: java.time.Clock,
    private val zone: ZoneId = clock.zone,
    logger: ScriptLogger = ScriptLogger.JUL,
    val queryRoles: Set<String> = setOf("gateway"),
    val limits: ScriptLimits = ScriptLimits(),
) : AutoCloseable {
    /** Execution counters behind the Diagnostics scripts table (GAT-029). */
    val stats = ScriptStats()
    private val logger: ScriptLogger = stats.wrap(logger)
    private val levels = LogLevels()
    private val json = ObjectMapper()
    private val libraries = ConcurrentHashMap<String, String>()
    private val libraryNames = ConcurrentHashMap<String, String>()
    /** Resolved library modules per project, dropped when any library or project setting changes. */
    private val projectLibraries = ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val events = ConcurrentHashMap<String, EventScript>()
    private val results = ConcurrentHashMap<String, ScriptResult>()
    private val lastFire = ConcurrentHashMap<String, Long>()
    private val busy = ConcurrentHashMap<String, AtomicBoolean>()
    private val epochs = ConcurrentHashMap<String, AtomicInteger>()
    private val tagArms = ConcurrentHashMap<String, TagArm>()
    private val timerFutures = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val dedicatedTimers = ConcurrentHashMap<String, ScheduledExecutorService>()
    private val messageThreads = ConcurrentHashMap<String, ExecutorService>()
    private val inflight = ThreadLocal.withInitial { HashSet<String>() }

    private var store: FileConfigStore? = null
    private var subscription: AutoCloseable? = null
    private var runtimeOrNull: ScriptRuntime? = null
    private var sharedTimer: ScheduledExecutorService? = null
    private var asyncPool: ExecutorService? = null
    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var running = false

    /**
     * When false, timer, scheduled, and tag-change scripts do not run.
     * Startup, shutdown, message handlers, and [run] still run: those belong to this process
     * or to the caller, not to which node is in control. An independent gateway leaves this true.
     */
    var actsWhen: () -> Boolean = { true }

    /** A project and the projects it inherits from, child first (PRO-001, PRO-002). The gateway sets this to its project service. */
    var projectChain: (String) -> List<String> = { listOf(it) }

    /** Copies gateway services (alarms, files) onto each host. Set before the first script runs. */
    var prepareHost: (ScriptHost) -> Unit = {}

    private val host: ScriptHost by lazy {
        ScriptHost(tags, databases, clock, zone, levels, logger, queryRoles).also { created ->
            created.deliver = { project, handler, payload, wait -> deliver(project, handler, payload, wait) }
            created.saveTag = { config -> persistTag(config, null) }
            created.removeTag = { path -> deleteTagResource(path) }
            created.moveTag = { config, toPath -> relocateTag(config, toPath) }
            created.scheduleScript = { delay, source -> scheduleLater(delay, source) }
            created.configListFn = { module, scope, kind -> listConfig(module, scope, kind) }
            created.configGetFn = { module, scope, kind, name -> getConfig(module, scope, kind, name) }
            created.configPutFn = { module, scope, kind, name, spec -> putConfig(module, scope, kind, name, spec) }
            created.configDeleteFn = { module, scope, kind, name -> deleteConfig(module, scope, kind, name) }
            prepareHost(created)
        }
    }

    fun attach(configStore: FileConfigStore) {
        check(store == null) { "script service is already attached" }
        store = configStore
        registerScriptSchemas(configStore.registry)
        configStore.revalidate(SCRIPT_MODULE)
        for (stored in configStore.list(SCRIPT_MODULE, Scope.GATEWAY)) apply(stored.resource, rearm = false)
        subscription = configStore.subscribe { event ->
            val id = event.id ?: return@subscribe
            // A project's library module, or a change to who a project inherits from, changes what its scripts import.
            if ((id.module == SCRIPT_MODULE && id.kind == LIBRARY_KIND) || id.kind == "Project") projectLibraries.clear()
            if (id.module != SCRIPT_MODULE || id.scope != Scope.GATEWAY) return@subscribe
            when (event.type) {
                ChangeType.DELETED -> remove(id.kind, id.name)
                ChangeType.CREATED, ChangeType.UPDATED -> event.resource?.let { apply(it, rearm = true) }
                else -> Unit
            }
        }
    }

    fun setLevel(loggerName: String, level: String) = levels.set(loggerName, level)

    /** `system.eam.*` (SCR-012): function name and JSON arguments in, JSON `{"value": ...}` out. */
    var eamCall: (String, String) -> String
        get() = host.eam
        set(value) { host.eam = value }

    /** What `system.util.getGatewayStatus` returns. The gateway sets this after startup fields exist. */
    var gatewayStatus: () -> Map<String, Any?>
        get() = host.gatewayStatus
        set(value) { host.gatewayStatus = value }

    fun defineModule(module: String, source: String) {
        require(MODULE_NAME.matches(module)) { "invalid module name '$module'" }
        libraries[module] = source
        if (running) rearmTags()
    }

    fun defineEvent(script: EventScript) {
        checked(script)
        events[script.name] = script
        if (running) rearm(script)
    }

    fun saveModule(name: String, source: String, module: String? = null, enabled: Boolean = true, version: Long? = null): Resource {
        val spec = linkedMapOf<String, Any?>(
            "module" to (module ?: ""),
            "source" to source,
            "enabled" to enabled,
        )
        return put(LIBRARY_KIND, name, spec, version)
    }

    fun saveEvent(
        name: String,
        type: String,
        source: String,
        enabled: Boolean = true,
        delayMillis: Long = 1_000,
        delayType: String = "fixedDelay",
        threading: String = "shared",
        tagPaths: List<String> = emptyList(),
        triggers: List<String> = listOf("value", "quality", "timestamp"),
        cron: String = "",
        timeoutMillis: Long = 10_000,
        maxStatements: Long = 1_000_000,
        maxAllocatedBytes: Long = 64L * 1024 * 1024,
        version: Long? = null,
    ): Resource {
        val spec = linkedMapOf<String, Any?>(
            "type" to type,
            "source" to source,
            "enabled" to enabled,
            "delayMillis" to delayMillis,
            "delayType" to delayType,
            "threading" to threading,
            "tagPaths" to tagPaths,
            "triggers" to triggers,
            "cron" to cron,
            "timeoutMillis" to timeoutMillis,
            "maxStatements" to maxStatements,
            "maxAllocatedBytes" to maxAllocatedBytes,
        )
        checked(parseEvent(name, spec))
        return put(EVENT_KIND, name, spec, version)
    }

    /**
     * An SFC step or chart script. [variables] is the chart scope: `chart.name` reads and writes it.
     * The result is not stored as a gateway-event result.
     *
     */
    fun runChart(source: String, variables: MutableMap<String, Any?>, name: String = "sfc"): ScriptResult =
        runtime().run(source, name, limits, librarySnapshot(), chart = variables)

    /**
     * An event-stream stage script. [streamJson] is bound as the string `_oip_stream_json`; the caller's
     * wrapper decodes it. The result is not stored as a gateway-event result.
     *
     */
    fun runStream(source: String, streamJson: String, name: String = "event-stream"): ScriptResult =
        runtime().run(source, name, limits, librarySnapshot(), streamJson = streamJson)

    /**
     * An HMI script hook ([HmiScripts]) acting for a session user. Tag reads and writes go through [caller], so each
     * tag's own permissions apply; named queries are checked against the user's roles. [argsJson] is bound as
     * `_oip_stream_json` for the wrapper. The result is not stored as a gateway-event result.
     */
    fun runForUser(
        source: String,
        argsJson: String,
        caller: org.oip.tags.TagCaller.User,
        name: String = "hmi-script",
        limits: ScriptLimits = this.limits,
        /** Asked before each tag write; see [ScriptHost]. */
        mayWrite: () -> Boolean = { true },
        maxWrites: Int = Int.MAX_VALUE,
        /** Polled while the script runs; true cancels it. */
        cancelled: (() -> Boolean)? = null,
        /** The project whose library modules the script imports: its own, then its parents' (PRO-001). Null: the gateway library only. */
        project: String? = null,
    ): ScriptResult {
        val userHost = ScriptHost(tags, databases, clock, zone, levels, logger, caller.roles, caller = caller, mayWrite = mayWrite, maxWrites = maxWrites).also {
            it.saveTag = host.saveTag
            it.removeTag = host.removeTag
            it.moveTag = host.moveTag
            it.adoptLibrary(host)
            prepareHost(it)
            it.project = project
        }
        return runtime().run(
            source, name, limits, librarySnapshot(project), streamJson = argsJson, scope = ScriptScope.SESSION,
            hostOverride = userHost, cancelled = cancelled,
        )
    }

    /**
     * Run [source] once with the current project library. [scope] is the caller's: [ScriptScope.DESIGNER] for the
     * designer script console, [ScriptScope.GATEWAY] otherwise. Both see the same library modules (SCR-042).
     */
    fun run(
        source: String,
        name: String = "script",
        limits: ScriptLimits = this.limits,
        scope: ScriptScope = ScriptScope.GATEWAY,
    ): ScriptResult {
        val result = runtime().run(source, name, limits, librarySnapshot(), scope = scope)
        record(name, result)
        return result
    }

    fun runAsync(source: String, name: String = "script", limits: ScriptLimits = this.limits): Future<ScriptResult> {
        ensureThreads()
        return asyncPool!!.submit(Callable {
            val result = runtime().run(source, name, limits, librarySnapshot())
            record(name, result)
            result
        })
    }

    private val tagEvents = TagEventScripts(tags) { source, name, eventJson ->
        val result = runtime().run(source, name, limits, librarySnapshot(), eventJson = eventJson)
        record(name, result)
        result
    }

    /** Alarm active, clear, and acknowledge transitions for tag event scripts. */
    fun onTagAlarm(source: String, alarm: String, trigger: String, value: Any?, alarmEvent: Map<String, Any?>? = null) {
        tagEvents.onAlarm(source, alarm, trigger, value, alarmEvent)
    }

    fun saveTagEvent(
        name: String,
        tagPath: String,
        event: String,
        source: String,
        alarmName: String = "",
        enabled: Boolean = true,
        version: Long? = null,
    ): org.oip.config.Resource {
        val spec = linkedMapOf<String, Any?>(
            "tagPath" to tagPath,
            "event" to event,
            "alarmName" to alarmName,
            "source" to source,
            "enabled" to enabled,
        )
        return put(TAG_EVENT_KIND, name, spec, version)
    }

    fun start() {
        if (running) return
        running = true
        ensureThreads()
        tagEvents.start()
        for (script in events.values.toList()) rearm(script)
        for (script in events.values.filter { it.enabled && it.type == EventType.STARTUP }) {
            runCatching { record(script.name, invoke(script)) }
                .onFailure { logger.log("ERROR", script.name, it.message ?: "startup script failed") }
        }
        scheduler!!.scheduleWithFixedDelay(
            { runCatching { poll(clock.instant()) } },
            50, 200, TimeUnit.MILLISECONDS,
        )
    }

    /** One pass of the schedule. The background poller calls this; tests call it with a fixed clock. */
    fun poll(now: Instant) {
        if (!actsWhen()) return
        for (script in events.values) {
            if (!script.enabled || script.type != EventType.SCHEDULED || script.cron.isBlank()) continue
            val cron = runCatching { Cron(script.cron) }.getOrNull() ?: continue
            if (!cron.matches(now, zone)) continue
            val key = cron.fireKey(now)
            val previous = lastFire.put(script.name, key)
            if (previous == key) continue
            ensureThreads()
            val token = epoch(script.name).get()
            asyncPool!!.execute { safeRun(script, token) }
        }
    }

    /** Running and finished script rows for Diagnostics: event scripts carry their event type, other runs say "run". */
    fun runningScripts(): List<Map<String, Any?>> = stats.snapshot { name -> events[name]?.type?.wire() }

    fun lastResult(name: String): ScriptResult? = results[name]

    /**
     * True while a timer or scheduled run of [name] has taken the run slot and not yet returned.
     * A tag-change script uses its own queue and does not set this.
     */
    fun executing(name: String): Boolean = busy[name]?.get() == true

    fun droppedEvents(name: String): Long = tagArms[name]?.dropped?.get() ?: 0

    /** Runs shutdown scripts, then stops script threads. Tag and database services are still usable. */
    fun shutdown() {
        if (!running) return
        running = false
        disarmAll()
        for (script in events.values.filter { it.enabled && it.type == EventType.SHUTDOWN }) {
            runCatching { record(script.name, invoke(script)) }
                .onFailure { logger.log("ERROR", script.name, it.message ?: "shutdown script failed") }
        }
        stopThreads()
    }

    override fun close() {
        shutdown()
        disarmAll()
        stopThreads()
        subscription?.close()
        subscription = null
        runtimeOrNull?.close()
        runtimeOrNull = null
    }

    /** Runs a message-handler gateway event and waits for it. Event-stream gateway handlers use this. */
    fun postMessage(handler: String, payloadJson: String) {
        deliver("", handler, payloadJson, true)
    }

    private fun deliver(project: String, handler: String, payloadJson: String, wait: Boolean): String {
        if (project.isNotEmpty() && project != PROJECT) throw IllegalArgumentException("unknown project '$project'")
        val script = events[handler] ?: throw IllegalArgumentException("no message handler '$handler'")
        if (script.type != EventType.MESSAGE || !script.enabled) {
            throw IllegalArgumentException("'$handler' is not a message handler")
        }
        if (!wait) {
            ensureThreads()
            messageThread(script).execute {
                runCatching { record(script.name, runHandler(script, payloadJson)) }
            }
            return ""
        }
        val result = runHandler(script, payloadJson)
        record(script.name, result)
        if (!result.ok) throw IllegalStateException(result.error ?: "message handler failed")
        return json.writeValueAsString(result.value)
    }

    private fun runHandler(script: EventScript, payloadJson: String): ScriptResult {
        val names = inflight.get()
        if (!names.add(script.name)) throw IllegalStateException("message handler '${script.name}' called itself")
        try {
            return runtime().run(script.source, script.name, script.limits, librarySnapshot(), payloadJson = payloadJson)
        } finally {
            names.remove(script.name)
        }
    }

    private fun invoke(script: EventScript, payloadJson: String? = null): ScriptResult =
        runtime().run(script.source, script.name, script.limits, librarySnapshot(), payloadJson = payloadJson)

    private fun safeRun(script: EventScript, token: Int) {
        if (!actsWhen()) return
        val flag = busy.computeIfAbsent(script.name) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return
        try {
            // The node can leave active between the check above and this slot.
            // A timer that has not entered the script must not start on a standby.
            if (!actsWhen()) return
            record(script.name, invoke(script), token)
        } finally {
            flag.set(false)
        }
    }

    private fun librarySnapshot(project: String? = null): List<Pair<String, String>> =
        if (project == null) gatewayLibrarySnapshot() else projectLibraries.computeIfAbsent(project) { resolveProjectLibraries(it) }

    private fun gatewayLibrarySnapshot(): List<Pair<String, String>> =
        libraries.entries.sortedBy { it.key }.map { it.key to it.value }

    /**
     * The modules a script of [project] can import (PRO-001): the gateway library first, then each project from the
     * root of its parent chain down to the project itself. A later layer replaces an earlier module of the same
     * Python name, so a child's copy wins, and a disabled module hides the one it overrides.
     */
    private fun resolveProjectLibraries(project: String): List<Pair<String, String>> {
        val config = store ?: return gatewayLibrarySnapshot()
        val modules = java.util.TreeMap<String, String>()
        for ((name, source) in gatewayLibrarySnapshot()) modules[name] = source
        val chain = runCatching { projectChain(project) }.getOrDefault(listOf(project))
        for (layer in chain.asReversed()) {
            val scope = runCatching { Scope.project(layer) }.getOrNull() ?: continue
            for (stored in config.list(SCRIPT_MODULE, scope, LIBRARY_KIND).sortedBy { it.resource.metadata.name }) {
                val resource = stored.resource
                val spec = config.registry.applyDefaults(SCRIPT_MODULE, LIBRARY_KIND, resource.spec)
                val module = pythonModuleName(resource.metadata.name, spec["module"] as? String)
                if (spec["enabled"] as? Boolean ?: true) modules[module] = spec["source"] as? String ?: "" else modules.remove(module)
            }
        }
        return modules.entries.map { it.key to it.value }
    }

    /**
     * Prepare the process-wide script engine in the background ([ScriptRuntime.warmEngine]), so the
     * first script does not pay GraalPy's cold start. The gateway calls it when a user signs in, not
     * at start, so a gateway nobody uses spends no CPU on it. Only the first call in the process works;
     * the future completes true when the engine started.
     */
    fun warmUp(): java.util.concurrent.CompletableFuture<Boolean> = ScriptRuntime.warmEngine()

    // Synchronized: HMI sessions call in from many threads at once; only one runtime is ever created.
    @Synchronized
    private fun runtime(): ScriptRuntime {
        val current = host
        return runtimeOrNull ?: ScriptRuntime(current, tags, logger, stats).also { runtimeOrNull = it }
    }

    private fun put(kind: String, name: String, spec: Map<String, Any?>, version: Long?): Resource {
        val config = store ?: throw IllegalStateException("script service has no config store")
        val id = ResourceId(SCRIPT_MODULE, Scope.GATEWAY, kind, name)
        val current = config.get(id)?.metadata?.version ?: 0
        val expected = version ?: current
        val filled = config.registry.applyDefaults(SCRIPT_MODULE, kind, spec)
        return config.put(Scope.GATEWAY, Resource(kind, Metadata(name, SCRIPT_MODULE, version = expected), filled), "script")
    }

    private fun apply(resource: Resource, rearm: Boolean) {
        val spec = store?.registry?.applyDefaults(SCRIPT_MODULE, resource.kind, resource.spec) ?: resource.spec
        when (resource.kind) {
            LIBRARY_KIND -> {
                val module = pythonModuleName(resource.metadata.name, spec["module"] as? String)
                libraryNames[resource.metadata.name] = module
                val enabled = spec["enabled"] as? Boolean ?: true
                val source = spec["source"] as? String ?: ""
                if (enabled) libraries[module] = source else libraries.remove(module)
                if (rearm && running) rearmTags()
            }
            EVENT_KIND -> {
                val script = parseEvent(resource.metadata.name, spec)
                events[script.name] = script
                if (rearm && running) rearm(script)
            }
            TAG_EVENT_KIND -> tagEvents.replace(parseTagEvent(resource.metadata.name, spec))
        }
    }

    private fun parseTagEvent(name: String, spec: Map<String, Any?>): TagEventScript {
        val event = spec["event"] as? String ?: throw IllegalArgumentException("tag event '$name' needs an event")
        require(event in setOf("value", "quality", "alarm")) { "tag event '$name' event must be value, quality, or alarm" }
        val path = spec["tagPath"] as? String ?: throw IllegalArgumentException("tag event '$name' needs a tag path")
        require(path.isNotBlank()) { "tag event '$name' needs a tag path" }
        return TagEventScript(
            name = name,
            tagPath = path,
            event = event,
            alarmName = spec["alarmName"] as? String ?: "",
            source = spec["source"] as? String ?: "",
            enabled = spec["enabled"] as? Boolean ?: true,
        )
    }

    private fun remove(kind: String, name: String) {
        when (kind) {
            LIBRARY_KIND -> {
                libraryNames.remove(name)?.let { libraries.remove(it) }
                if (running) rearmTags()
            }
            EVENT_KIND -> {
                events.remove(name)
                bump(name)
                disarm(name)
            }
            TAG_EVENT_KIND -> tagEvents.remove(name)
        }
    }

    private fun rearm(script: EventScript) {
        val token = bump(script.name)
        disarm(script.name)
        if (!running || !script.enabled) return
        when (script.type) {
            EventType.TIMER -> armTimer(script, token)
            EventType.TAG_CHANGE -> armTag(script, token)
            else -> Unit
        }
    }

    private fun rearmTags() {
        for (script in events.values) if (script.type == EventType.TAG_CHANGE) rearm(script)
    }

    private fun armTimer(script: EventScript, token: Int) {
        ensureThreads()
        val pool = if (script.threading == "dedicated") dedicatedTimer(script.name) else sharedTimer!!
        val delay = script.delayMillis
        val future = if (script.delayType == "fixedRate") {
            pool.scheduleAtFixedRate({ runCatching { safeRun(script, token) } }, delay, delay, TimeUnit.MILLISECONDS)
        } else {
            pool.scheduleWithFixedDelay({ runCatching { safeRun(script, token) } }, delay, delay, TimeUnit.MILLISECONDS)
        }
        timerFutures[script.name] = future
    }

    private fun armTag(script: EventScript, token: Int) {
        if (script.tagPaths.isEmpty()) return
        ensureThreads()
        val arm = TagArm(script.name, token)
        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "oip-script-tag-${script.name}").apply { isDaemon = true }
        }
        arm.worker = worker
        tagArms[script.name] = arm
        val subs = mutableListOf<AutoCloseable>()
        val concrete = ArrayList<String>()
        for (path in script.tagPaths) {
            if (org.oip.tags.TagPathPattern.isPattern(path)) {
                subs += org.oip.tags.PatternSubscription(tags, path) { change -> onTag(script, arm, change) }
                concrete += org.oip.tags.TagPathPattern.collect(tags, path)
            } else {
                val sub = runCatching {
                    tags.subscribe(path) { change -> onTag(script, arm, change) }
                }.getOrElse {
                    logger.log("ERROR", script.name, it.message ?: "tag subscribe failed")
                    null
                }
                if (sub != null) subs += sub
                concrete += path
            }
        }
        arm.subs = subs
        for (path in concrete) {
            val current = tags.read(path)
            arm.queue.offer(QueuedTag(path, current, null))
        }
        worker.execute { drain(script, arm) }
    }

    private fun onTag(script: EventScript, arm: TagArm, change: TagChange) {
        if (!interested(script, change)) return
        val queued = QueuedTag(change.path.toString(), change.value, change.previous)
        if (!arm.queue.offer(queued)) arm.dropped.incrementAndGet()
        else arm.worker?.execute { drain(script, arm) }
    }

    private fun drain(script: EventScript, arm: TagArm) {
        if (arm.token != epoch(script.name).get()) return
        if (!arm.busy.compareAndSet(false, true)) return
        try {
            while (arm.token == epoch(script.name).get()) {
                if (!actsWhen()) {
                    arm.queue.clear()
                    break
                }
                val job = arm.queue.poll() ?: break
                val count = arm.execution.getAndIncrement()
                val eventJson = eventJson(count == 0, count, job)
                val result = try {
                    runtime().run(
                        script.source, script.name, script.limits, librarySnapshot(), eventJson = eventJson,
                    )
                } catch (e: Throwable) {
                    logger.log("ERROR", script.name, e.message ?: "tag script failed")
                    break
                }
                record(script.name, result, arm.token)
            }
        } finally {
            arm.busy.set(false)
            if (arm.token == epoch(script.name).get() && arm.queue.isNotEmpty()) arm.worker?.execute { drain(script, arm) }
        }
    }

    private fun eventJson(initial: Boolean, count: Int, job: QueuedTag): String {
        val changes = ArrayList<String>(3)
        val previous = job.previous
        if (previous == null || job.value.value != previous.value) changes += "ValueChange"
        if (previous == null || job.value.quality != previous.quality) changes += "QualityChange"
        if (previous == null || job.value.timestamp != previous.timestamp) changes += "TimestampChange"
        return json.writeValueAsString(
            mapOf(
                "initialChange" to initial,
                "executionCount" to count,
                "newValue" to qvMap(job.value),
                "previousValue" to previous?.let { qvMap(it) },
                "tagPath" to job.path,
                "changes" to changes,
            ),
        )
    }

    private fun qvMap(qv: QualifiedValue): Map<String, Any?> = mapOf(
        "value" to when (val v = qv.value) {
            is Instant -> v.toEpochMilli()
            is java.util.Date -> v.time
            else -> v
        },
        "quality" to qv.quality.code.label,
        "timestamp" to qv.timestamp.toEpochMilli(),
    )

    private fun interested(script: EventScript, change: TagChange): Boolean {
        val previous = change.previous ?: return true
        val next = change.value
        if ("value" in script.triggers && next.value != previous.value) return true
        if ("quality" in script.triggers && next.quality != previous.quality) return true
        if ("timestamp" in script.triggers && next.timestamp != previous.timestamp) return true
        return false
    }

    private fun disarm(name: String) {
        timerFutures.remove(name)?.cancel(false)
        dedicatedTimers.remove(name)?.shutdownNow()
        tagArms.remove(name)?.close()
    }

    private fun disarmAll() {
        tagEvents.stop()
        timerFutures.keys.toList().forEach { disarm(it) }
        tagArms.keys.toList().forEach { disarm(it) }
        dedicatedTimers.values.forEach { it.shutdownNow() }
        dedicatedTimers.clear()
    }

    private fun tagSchema(): Boolean =
        store?.let { runCatching { it.registry.schema(TAGS_MODULE, TAG_KIND) }.isSuccess } == true

    private fun persistTag(config: TagConfig, replace: String?): Boolean {
        if (!tagSchema()) return false
        val configStore = store ?: return false
        val name = replace ?: resourceName(config.provider, config.path) ?: tagResourceName(config.provider, config.path)
        val id = ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, name)
        val version = configStore.get(id)?.metadata?.version ?: 0L
        configStore.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata(name, TAGS_MODULE, version = version), config.toSpec()), "script")
        return true
    }

    private fun deleteTagResource(path: String): Boolean {
        if (!tagSchema()) return false
        val (provider, relative) = splitUserPath(path)
        val name = resourceName(provider, relative) ?: return false
        store?.delete(ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, name), null)
        return true
    }

    private fun relocateTag(config: TagConfig, toPath: String): Boolean {
        if (!tagSchema()) return false
        val name = resourceName(config.provider, config.path) ?: return false
        return persistTag(config.copy(path = toPath), name)
    }

    private fun resourceName(provider: String, path: String): String? {
        val full = "[$provider]$path"
        val parent = if ('/' in path) "[$provider]${path.substringBeforeLast('/')}" else "[$provider]"
        return tags.browse(parent, 0, Int.MAX_VALUE, TagCaller.System)
            .firstOrNull { it.path.toString().equals(full, ignoreCase = true) }
            ?.resource
    }

    private fun splitUserPath(path: String): Pair<String, String> {
        val text = path.trim()
        if (text.startsWith("[") && "]" in text) {
            val end = text.indexOf(']')
            val provider = text.substring(1, end).ifBlank { "default" }
            return provider to text.substring(end + 1).trim('/')
        }
        return "default" to text.trim('/')
    }

    private fun tagResourceName(provider: String, path: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("${provider.lowercase()}/${path.lowercase()}".toByteArray())
        return "t" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun scheduleLater(delay: Long, source: String) {
        ensureThreads()
        sharedTimer!!.schedule({ runCatching { run(source, "timer") } }, delay, TimeUnit.MILLISECONDS)
    }

    private fun ensureThreads() {
        if (sharedTimer == null) {
            sharedTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "oip-script-shared").apply { isDaemon = true }
            }
        }
        if (asyncPool == null) {
            val seq = AtomicInteger()
            asyncPool = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "oip-script-${seq.incrementAndGet()}").apply { isDaemon = true }
            }
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "oip-script-schedule").apply { isDaemon = true }
            }
        }
    }

    private fun dedicatedTimer(name: String): ScheduledExecutorService =
        dedicatedTimers.computeIfAbsent(name) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "oip-script-timer-$name").apply { isDaemon = true }
            }
        }

    private fun messageThread(script: EventScript): ExecutorService =
        messageThreads.computeIfAbsent(script.name) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "oip-script-msg-${script.name}").apply { isDaemon = true }
            }
        }

    private fun stopThreads() {
        scheduler?.shutdownNow()
        scheduler = null
        sharedTimer?.shutdownNow()
        sharedTimer = null
        asyncPool?.shutdownNow()
        asyncPool = null
        messageThreads.values.forEach { it.shutdownNow() }
        messageThreads.clear()
        dedicatedTimers.values.forEach { it.shutdownNow() }
        dedicatedTimers.clear()
    }

    private fun record(name: String, result: ScriptResult, token: Int? = null) {
        if (token != null && token != epoch(name).get()) return
        results[name] = result
        if (!result.ok) logger.log("ERROR", name, result.error ?: result.failure?.name ?: "script failed")
    }

    private fun epoch(name: String): AtomicInteger = epochs.computeIfAbsent(name) { AtomicInteger() }

    private fun bump(name: String): Int = epoch(name).incrementAndGet()

    private fun checked(script: EventScript) {
        when (script.type) {
            EventType.TAG_CHANGE -> require(script.tagPaths.isNotEmpty()) { "tag change script '${script.name}' needs a tag path" }
            EventType.SCHEDULED -> {
                require(script.cron.isNotBlank()) { "scheduled script '${script.name}' needs a cron expression" }
                Cron(script.cron)
            }
            EventType.TIMER -> require(script.delayMillis >= 10) { "timer delay must be at least 10 ms" }
            else -> Unit
        }
    }

    private fun parseEvent(name: String, spec: Map<String, Any?>): EventScript {
        val type = EventType.fromName(spec["type"]?.toString() ?: "")
            ?: throw IllegalArgumentException("unknown gateway event type '${spec["type"]}'")
        val triggers = (spec["triggers"] as? List<*>)?.map { it.toString() }?.toSet()
            ?: setOf("value", "quality", "timestamp")
        return EventScript(
            name = name,
            type = type,
            source = spec["source"] as? String ?: "",
            enabled = spec["enabled"] as? Boolean ?: true,
            delayMillis = number(spec, "delayMillis", 1_000),
            delayType = spec["delayType"] as? String ?: "fixedDelay",
            threading = spec["threading"] as? String ?: "shared",
            tagPaths = (spec["tagPaths"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            triggers = triggers,
            cron = spec["cron"] as? String ?: "",
            limits = ScriptLimits(
                timeout = Duration.ofMillis(number(spec, "timeoutMillis", 10_000)),
                maxStatements = number(spec, "maxStatements", 1_000_000),
                maxAllocatedBytes = number(spec, "maxAllocatedBytes", 64L * 1024 * 1024),
            ),
        )
    }

    private fun number(spec: Map<String, Any?>, key: String, default: Long): Long = when (val value = spec[key]) {
        null -> default
        is Number -> value.toLong()
        else -> throw IllegalArgumentException("$key must be a number")
    }

    private class TagArm(val name: String, val token: Int) {
        val queue = ArrayBlockingQueue<QueuedTag>(64)
        val execution = AtomicInteger()
        val busy = AtomicBoolean(false)
        val dropped = AtomicLong()
        var subs: List<AutoCloseable> = emptyList()
        var worker: ExecutorService? = null

        fun close() {
            subs.forEach { runCatching { it.close() } }
            subs = emptyList()
            worker?.shutdownNow()
            worker = null
        }
    }

    private fun listConfig(module: String, scope: String, kind: String): String {
        refuseModule(module)
        val found = configStore().list(module, configScope(scope), kind)
        return CONFIG_JSON.writeValueAsString(found.map {
            mapOf("module" to it.id.module, "scope" to it.id.scope.path, "kind" to it.id.kind, "name" to it.id.name)
        })
    }

    private fun getConfig(module: String, scope: String, kind: String, name: String): String {
        refuseModule(module)
        val resource = configStore().get(configId(module, scope, kind, name))
            ?: throw IllegalArgumentException("resource not found")
        return CONFIG_JSON.writeValueAsString(
            mapOf("name" to name, "version" to resource.metadata.version, "spec" to redact(resource.spec)),
        )
    }

    private fun putConfig(module: String, scope: String, kind: String, name: String, specJson: String): String {
        val spec = CONFIG_JSON.readValue(specJson, Map::class.java)
        if (secretKey(spec)) throw IllegalArgumentException("config spec must not carry a credential")
        @Suppress("UNCHECKED_CAST")
        val body = spec as Map<String, Any?>
        val id = configId(module, scope, kind, name)
        val current = configStore().get(id)
        val stored = configStore().put(
            id.scope,
            Resource(kind, Metadata(name, module, version = current?.metadata?.version ?: 0), body),
            "script",
        )
        return CONFIG_JSON.writeValueAsString(mapOf("name" to name, "version" to stored.metadata.version))
    }

    private fun deleteConfig(module: String, scope: String, kind: String, name: String) {
        configStore().delete(configId(module, scope, kind, name))
    }

    private fun configStore(): FileConfigStore = store ?: throw IllegalStateException("config is not armed")

    private fun refuseModule(module: String) {
        if (module == "org.oip.security" || module.startsWith("org.oip.security.")) {
            throw IllegalArgumentException("security config is not available to scripts")
        }
    }

    private fun configScope(scope: String): Scope {
        return Scope.parse(scope)
    }

    private fun configId(module: String, scope: String, kind: String, name: String): ResourceId {
        return ResourceId(module, configScope(scope), kind, name)
    }

    private fun redact(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.filterKeys { key -> key.toString().lowercase() !in SECRET_KEYS }.mapValues { redact(it.value) }
        is List<*> -> value.map { redact(it) }
        else -> value
    }

    private fun secretKey(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value.keys.any { it.toString().lowercase() in SECRET_KEYS } || value.values.any { secretKey(it) }
        is List<*> -> value.any { secretKey(it) }
        else -> false
    }

    private data class QueuedTag(val path: String, val value: QualifiedValue, val previous: QualifiedValue?)

    companion object {
        const val PROJECT = "gateway"
        private val MODULE_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$""")

        private val CONFIG_JSON = ObjectMapper()
        private val SECRET_KEYS = setOf("password", "secret", "token", "hash")

        fun pythonModuleName(resourceName: String, module: String?): String {
            val raw = module?.takeIf { it.isNotBlank() } ?: resourceName.replace('-', '_')
            require(MODULE_NAME.matches(raw)) { "invalid module name '$raw'" }
            return raw
        }
    }
}
