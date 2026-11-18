package org.oip.sfc

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.tags.Level
import org.oip.tags.Quality
import org.oip.tags.QualityCode
import org.oip.tags.TagEngine
import org.oip.tags.expr.EvalContext
import org.oip.tags.expr.Expressions
import org.oip.tags.expr.Value
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level as LogLevel
import java.util.logging.Logger
import kotlin.concurrent.withLock

/**
 * Gateway chart executor.
 *
 * Charts are config resources. Instances run in the gateway, one scope each:
 *
 * Work runs on the `sfc-engine` thread. A call made while the tag engine write lock is held, or on a
 * thread whose name starts with `tag-`, is queued and does not run the chart on that thread (P2-14).
 * [inline] runs the same steps on the caller for tests. Transition time uses [clock].
 *
 * `chart.startTime` in an expression is epoch milliseconds. The monitor view reports the same instant
 * as ISO-8601. There is no Python date in the expression language.
 */
class SfcEngine(
    private val clock: Clock,
    private val tags: TagEngine?,
    private val store: FileConfigStore,
    private val stateStore: ChartStateStore = MemoryChartStateStore(),
    private val inline: Boolean = false,
    var scripts: ChartScriptHost = ChartScriptHost.NOOP,
    var deferWhen: () -> Boolean = { Thread.currentThread().name.startsWith("tag-") },
    /** When false, charts do not advance. An independent gateway leaves this true. */
    var actsWhen: () -> Boolean = { true },
) : AutoCloseable {
    private val log = Logger.getLogger("oip.sfc")
    private val lock = ReentrantLock()
    private val charts = LinkedHashMap<String, CompiledChart>()
    private val faults = LinkedHashMap<String, List<String>>()
    private val instances = LinkedHashMap<String, Instance>()
    private val pending = ArrayDeque<() -> Unit>()
    private var subscription: AutoCloseable? = null
    private var executor: ScheduledExecutorService? = null
    private var scheduled = false
    @Volatile private var worker: Thread? = null
    @Volatile private var closed = false

    fun attach() {
        registerSfcSchemas(store.registry)
        store.revalidate(SFC_MODULE)
        subscription = store.subscribe { event -> onConfig(event) }
        for (stored in store.list(SFC_MODULE, Scope.GATEWAY, CHART_KIND)) accept(stored.resource)
    }

    /** Load redundancy snapshots and start run-always charts. Starts the scheduler unless [inline]. */
    fun start() {
        onEngine {
            loadSnapshots()
            linkParents()
            for (chart in charts.values) {
                if (chart.mode == "runAlways") ensureRunAlways(chart)
            }
        }
        ensureScheduler()
    }

    fun charts(): List<Map<String, Any?>> = lock.withLock {
        val names = (charts.keys + faults.keys).sorted()
        names.map { name ->
            val chart = charts[name]
            linkedMapOf<String, Any?>(
                "name" to name,
                "executionMode" to (chart?.mode ?: "invalid"),
                "persistState" to (chart?.persistState ?: false),
                "redundancySync" to (chart?.redundancySync ?: false),
                "valid" to (chart != null),
                "elements" to (chart?.graph?.nodes?.keys?.sorted() ?: emptyList<String>()),
                "errors" to (faults[name] ?: emptyList<String>()),
            )
        }
    }

    fun instances(): List<Map<String, Any?>> = lock.withLock { instances.values.map { view(it) } }

    fun instance(id: String): Map<String, Any?> = lock.withLock {
        view(instances[id] ?: throw ChartNotFound("chart instance '$id' not found"))
    }

    fun startChart(name: String, parameters: Map<String, Any?> = emptyMap()): String {
        val id = UUID.randomUUID().toString()
        if (deferWhen()) {
            lock.withLock { pending.add { startWithId(id, name, parameters, allowRunAlways = false) } }
            return id
        }
        onEngine { startWithId(id, name, parameters, allowRunAlways = false) }
        return id
    }

    fun pause(id: String) = onEngine {
        val inst = instances[id] ?: throw ChartNotFound("chart instance '$id' not found")
        if (inst.state != ChartState.RUNNING && inst.state != ChartState.STARTING && inst.state != ChartState.RESUMING) {
            throw IllegalStateException("cannot pause a chart that is ${inst.state.label}")
        }
        inst.resumeState = ChartState.RUNNING
        inst.pausedAt = clock.millis()
        inst.state = ChartState.PAUSED
        snapshot(inst)
    }

    fun resume(id: String) = onEngine {
        val inst = instances[id] ?: throw ChartNotFound("chart instance '$id' not found")
        if (inst.state != ChartState.PAUSED) throw IllegalStateException("cannot resume a chart that is ${inst.state.label}")
        val delta = clock.millis() - inst.pausedAt
        inst.pauseAccumulated += delta
        shift(inst.cursor, delta)
        inst.state = ChartState.RESUMING
        drive(inst, Budget(MAX_STEPS), clock.millis())
        snapshot(inst)
    }

    fun cancel(id: String) = onEngine {
        val inst = instances[id] ?: throw ChartNotFound("chart instance '$id' not found")
        if (inst.state.terminal) throw IllegalStateException("cannot cancel a chart that is ${inst.state.label}")
        cancelNow(inst, "canceled")
    }

    /**
     * Set a declared chart variable or parameter on a live instance (doc source: FEATURES SFC-004). The name
     * must already exist on the instance and the instance must not have finished.
     */
    fun setVariable(id: String, name: String, value: Any?) = onEngine {
        val inst = instances[id] ?: throw ChartNotFound("chart instance '$id' not found")
        if (inst.state.terminal) throw IllegalStateException("cannot set a variable on a chart that is ${inst.state.label}")
        if (name in RESERVED || name !in inst.vars) throw ChartRejected("chart '${inst.chart.name}' has no variable '$name'")
        inst.vars[name] = value
        snapshot(inst)
    }

    /** Stop executing redundancy-synced charts and keep a snapshot a peer can load. */
    fun park() = onEngine {
        val now = clock.millis()
        for (inst in instances.values) {
            if (!inst.chart.redundancySync || inst.state.terminal || inst.state == ChartState.REDUNDANT_INACTIVE) continue
            // Not run yet (created on a standby): nothing to resume, and the gate already holds it until active.
            if (inst.state == ChartState.STARTING) continue
            inst.resumeState = if (inst.state == ChartState.PAUSED) ChartState.PAUSED else ChartState.RUNNING
            if (inst.state != ChartState.PAUSED) inst.pausedAt = now
            inst.state = ChartState.REDUNDANT_INACTIVE
            snapshot(inst)
        }
    }

    /**
     * Continue parked charts. The on-redundant-failover actions can cancel, restart, or jump.
     */
    fun activate() = onEngine {
        val now = clock.millis()
        for (inst in instances.values.toList()) {
            if (inst.state != ChartState.REDUNDANT_INACTIVE) continue
            val action = RestartAction()
            when (inst.chart.failover) {
                "cancel" -> action.cancel = true
                "restart" -> action.restart = true
                "goto" -> action.gotoStep = inst.chart.failoverStep
            }
            try {
                runActions(inst, inst.chart.events.onRedundantFailover, action)
            } catch (e: ActionFailed) {
                abortNow(inst, e.message ?: "redundant failover failed")
                continue
            }
            when {
                action.cancel -> cancelNow(inst, "redundant failover canceled the chart")
                action.restart -> restart(inst, now)
                !action.gotoStep.isNullOrBlank() -> jumpTo(inst, action.gotoStep!!, now)
                inst.resumeState == ChartState.PAUSED -> inst.state = ChartState.PAUSED
                else -> {
                    val delta = now - inst.pausedAt
                    if (delta > 0) {
                        inst.pauseAccumulated += delta
                        shift(inst.cursor, delta)
                    }
                    inst.state = ChartState.RESUMING
                    drive(inst, Budget(MAX_STEPS), now)
                }
            }
            snapshot(inst)
        }
    }

    /** Run charts that were queued off a tag thread. Tests use this with [inline]. */
    fun drain() {
        check(inline) { "drain is for the inline engine" }
        while (true) {
            val job = lock.withLock { if (pending.isEmpty()) null else pending.removeFirst() } ?: break
            lock.withLock { job() }
        }
    }

    /** One scheduling pass. Tests advance [clock] and then call this. */
    fun tick() = onEngine { driveAll() }

    override fun close() {
        if (closed) return
        closed = true
        lock.withLock {
            for (inst in instances.values) if (!inst.state.terminal) snapshot(inst)
        }
        subscription?.close()
        subscription = null
        executor?.shutdownNow()
        executor = null
    }

    private fun onConfig(event: ConfigEvent) {
        val id = event.id ?: return
        if (id.module != SFC_MODULE || id.kind != CHART_KIND || id.scope != Scope.GATEWAY) return
        onEngine { applyConfig(event) }
    }

    private fun applyConfig(event: ConfigEvent) {
        val name = event.id?.name ?: return
        when (event.type) {
            ChangeType.DELETED -> {
                charts.remove(name)
                faults.remove(name)
                for (inst in instances.values.filter { it.chart.name == name && !it.state.terminal }) {
                    cancelNow(inst, "chart removed")
                }
            }
            ChangeType.INVALID -> {
                charts.remove(name)
                faults[name] = listOf(event.error ?: "invalid chart")
            }
            else -> event.resource?.let { accept(it) }
        }
    }

    private fun accept(resource: Resource) {
        val name = resource.metadata.name
        try {
            charts[name] = compileChart(name, resource.spec)
            faults.remove(name)
        } catch (e: ChartCompileException) {
            charts.remove(name)
            faults[name] = e.errors
        }
    }

    private fun loadSnapshots() {
        for (doc in stateStore.load()) {
            runCatching { importOne(doc) }.onFailure { log.warning("skipped a chart snapshot: ${it.message}") }
        }
    }

    private fun ensureRunAlways(chart: CompiledChart) {
        val live = instances.values.any { it.chart.name == chart.name && !it.state.terminal }
        if (!live) startWithId(UUID.randomUUID().toString(), chart.name, emptyMap(), allowRunAlways = true)
    }

    private fun startWithId(id: String, name: String, parameters: Map<String, Any?>, allowRunAlways: Boolean) {
        faults[name]?.let { throw ChartRejected("chart '$name' is invalid: ${it.joinToString("; ")}") }
        val chart = charts[name] ?: throw ChartNotFound("chart '$name' not found")
        if (chart.mode == "disabled") throw ChartRejected("chart '$name' is disabled")
        if (chart.mode == "runAlways" && !allowRunAlways) {
            throw ChartRejected("chart '$name' is runAlways and starts with the gateway")
        }
        if (chart.mode == "runAlways" && instances.values.any { it.chart.name == name && !it.state.terminal }) return
        for (key in parameters.keys) {
            if (key in RESERVED) throw ChartRejected("parameter '$key' is reserved")
            if (!NAME.matches(key)) throw ChartRejected("parameter '$key' is not a valid name")
        }
        val inst = Instance(
            id = id,
            chart = chart,
            state = ChartState.STARTING,
            vars = initialVars(chart, parameters),
            parent = null,
            key = chart.parameters.firstOrNull { it.key }?.let { (parameters[it.name] ?: it.default)?.toString() },
            startedAtMillis = clock.millis(),
            startParams = parameters.toMap(),
        )
        instances[id] = inst
        drive(inst, Budget(MAX_STEPS), clock.millis())
        snapshot(inst)
        trim()
    }

    private fun initialVars(chart: CompiledChart, parameters: Map<String, Any?>): MutableMap<String, Any?> {
        val vars = LinkedHashMap<String, Any?>()
        for (param in chart.parameters) vars[param.name] = if (param.name in parameters) parameters[param.name] else param.default
        for (variable in chart.variables) vars.putIfAbsent(variable.name, variable.initial)
        for ((key, value) in parameters) vars.putIfAbsent(key, value)
        return vars
    }

    private fun driveAll() {
        while (pending.isNotEmpty()) pending.removeFirst().invoke()
        if (!actsWhen()) return
        val now = clock.millis()
        for (inst in instances.values.toList()) {
            if (inst.state.terminal || inst.state == ChartState.PAUSED || inst.state == ChartState.REDUNDANT_INACTIVE) continue
            if (inst.parent != null) continue
            drive(inst, Budget(MAX_STEPS), now)
            snapshot(inst)
        }
    }

    private fun drive(inst: Instance, budget: Budget, now: Long) {
        if (!actsWhen()) return
        if (inst.state == ChartState.STARTING) {
            inst.state = ChartState.RUNNING
            try {
                runActions(inst, inst.chart.events.onStart, null)
            } catch (e: ActionFailed) {
                abortNow(inst, e.message ?: "onStart failed")
                return
            }
            if (inst.state != ChartState.RUNNING) return
            inst.cursor = Cursor(inst.chart.graph.entry, enteredAtMillis = now)
            budget.left--
        }
        if (inst.state == ChartState.RESUMING) inst.state = ChartState.RUNNING
        while (budget.left > 0 && inst.state == ChartState.RUNNING) {
            val cursor = inst.cursor ?: break
            when (step(inst, cursor, inst.chart.graph, inBranch = false, now, budget)) {
                StepResult.BLOCKED -> break
                StepResult.PROGRESSED -> budget.left--
                StepResult.BRANCH_DONE -> {
                    abortNow(inst, "flow from '${cursor.nodeId}' has nowhere to go")
                    break
                }
                StepResult.REACHED_END -> {
                    stopNow(inst)
                    break
                }
            }
        }
    }

    private fun step(inst: Instance, cursor: Cursor, graph: Graph, inBranch: Boolean, now: Long, budget: Budget): StepResult {
        if (cursor.done) return StepResult.BRANCH_DONE
        val node = graph.nodes[cursor.nodeId] ?: run {
            abortNow(inst, "missing element '${cursor.nodeId}'")
            return StepResult.BLOCKED
        }
        return when (node) {
            is Node.Begin, is Node.Anchor -> moveTo(cursor, single(graph, node.id), now)
            is Node.End -> if (inBranch) {
                abortNow(inst, "end step inside a parallel section")
                StepResult.BLOCKED
            } else StepResult.REACHED_END
            is Node.Jump -> {
                val anchor = graph.nodes[node.anchor]
                if (anchor !is Node.Anchor) {
                    abortNow(inst, "jump '${node.id}' has no anchor")
                    StepResult.BLOCKED
                } else moveTo(cursor, single(graph, anchor.id), now)
            }
            is Node.Note -> StepResult.BLOCKED
            is Node.Action -> stepAction(inst, cursor, graph, node, inBranch, now)
            is Node.Transition -> stepTransition(inst, cursor, graph, node, now)
            is Node.Assertion -> stepAssertion(inst, cursor, graph, node, inBranch, now)
            is Node.Parallel -> stepParallel(inst, cursor, graph, node, inBranch, now, budget)
            is Node.Enclosing -> stepEnclosing(inst, cursor, graph, node, inBranch, now, budget)
        }
    }

    private fun stepAction(inst: Instance, cursor: Cursor, graph: Graph, node: Node.Action, inBranch: Boolean, now: Long): StepResult {
        if (!cursor.onStartDone) {
            if (!runGuarded(inst, node.onStart, node.onError)) return StepResult.BLOCKED
            cursor.onStartDone = true
            cursor.enteredAtMillis = now
            return StepResult.PROGRESSED
        }
        val period = node.timerPeriodMillis
        if (period != null && period > 0 && node.timer.isNotEmpty() && now >= cursor.enteredAtMillis + (cursor.timerRuns + 1) * period) {
            if (!runGuarded(inst, node.timer, node.onError)) return StepResult.BLOCKED
            cursor.timerRuns++
            return StepResult.PROGRESSED
        }
        return leave(inst, cursor, graph, node.id, inBranch, now) {
            if (!runGuarded(inst, node.onStop, node.onError)) StepResult.BLOCKED else null
        }
    }

    private fun stepTransition(inst: Instance, cursor: Cursor, graph: Graph, node: Node.Transition, now: Long): StepResult {
        armTimeout(inst, cursor, node, now)
        if (!isTrue(eval(inst, node.expression))) return StepResult.BLOCKED
        return moveTo(cursor, single(graph, node.id), now)
    }

    private fun stepAssertion(inst: Instance, cursor: Cursor, graph: Graph, node: Node.Assertion, inBranch: Boolean, now: Long): StepResult {
        val failed = node.checks.any { check ->
            val actual = inst.vars[check.parameter]
            !compare(actual, check.operator, check.value)
        }
        if (failed) {
            if (node.onFail == "abort") {
                abortNow(inst, "assertion '${node.id}' failed")
                return StepResult.BLOCKED
            }
            if (node.flag != null) inst.vars[node.flag] = true
        }
        return leave(inst, cursor, graph, node.id, inBranch, now) { null }
    }

    private fun stepParallel(
        inst: Instance,
        cursor: Cursor,
        graph: Graph,
        node: Node.Parallel,
        inBranch: Boolean,
        now: Long,
        budget: Budget,
    ): StepResult {
        if (cursor.branches == null) {
            cursor.branches = node.branches.map { branch -> Cursor(branch.entry, enteredAtMillis = now) }.toMutableList()
            return StepResult.PROGRESSED
        }
        val branches = cursor.branches!!
        var moved = false
        for (index in branches.indices) {
            val branchCursor = branches[index]
            if (branchCursor.done) continue
            if (budget.left <= 0) return if (moved) StepResult.PROGRESSED else StepResult.BLOCKED
            when (step(inst, branchCursor, node.branches[index], true, now, budget)) {
                StepResult.BLOCKED -> {}
                StepResult.PROGRESSED -> { budget.left--; moved = true }
                StepResult.BRANCH_DONE -> { branchCursor.done = true; moved = true }
                StepResult.REACHED_END -> {
                    abortNow(inst, "end step inside a parallel section")
                    return StepResult.BLOCKED
                }
            }
            if (inst.state != ChartState.RUNNING) return StepResult.BLOCKED
        }
        if (branches.all { it.done }) return leave(inst, cursor, graph, node.id, inBranch, now) { null }
        return if (moved) StepResult.PROGRESSED else StepResult.BLOCKED
    }

    private fun stepEnclosing(
        inst: Instance,
        cursor: Cursor,
        graph: Graph,
        node: Node.Enclosing,
        inBranch: Boolean,
        now: Long,
        budget: Budget,
    ): StepResult {
        var child = cursor.childId?.let { instances[it] }
        if (child == null) {
            child = spawn(inst, node, now) ?: return StepResult.BLOCKED
            cursor.childId = child.id
            drive(child, budget, now)
        } else if (!child.state.terminal) {
            drive(child, budget, now)
        }
        if (inst.state != ChartState.RUNNING) return StepResult.BLOCKED
        if (node.execution == "cancel" && !child.state.terminal) {
            val opened = opened(inst, cursor, graph, node.id, now)
            if (opened != null) {
                cancelNow(child, "enclosing step canceled the chart")
                return moveTo(cursor, opened, now)
            }
            return StepResult.BLOCKED
        }
        if (!child.state.terminal) return StepResult.BLOCKED
        if (child.state != ChartState.STOPPED) {
            abortNow(inst, "enclosed chart '${node.chart}' ended ${child.state.label}")
            return StepResult.BLOCKED
        }
        for (back in node.returns) inst.vars[back.to] = child.vars[back.from]
        return leave(inst, cursor, graph, node.id, inBranch, now) { null }
    }

    private fun spawn(parent: Instance, node: Node.Enclosing, now: Long): Instance? {
        faults[node.chart]?.let {
            abortNow(parent, "enclosed chart '${node.chart}' is invalid")
            return null
        }
        val chart = charts[node.chart]
        if (chart == null) {
            abortNow(parent, "enclosed chart '${node.chart}' was not found")
            return null
        }
        if (chart.mode != "callable") {
            abortNow(parent, "enclosed chart '${node.chart}' is not callable")
            return null
        }
        val params = LinkedHashMap<String, Any?>()
        for (arg in node.parameters) {
            val value = eval(parent, arg.expression)
            if (value.quality.level == Level.ERROR) {
                abortNow(parent, "enclosing parameter '${arg.name}' failed: ${value.quality}")
                return null
            }
            params[arg.name] = value.value
        }
        val child = Instance(
            id = UUID.randomUUID().toString(),
            chart = chart,
            state = ChartState.STARTING,
            vars = initialVars(chart, params),
            parent = parent,
            key = chart.parameters.firstOrNull { it.key }?.let { (params[it.name] ?: it.default)?.toString() },
            startedAtMillis = now,
            startParams = params,
        )
        instances[child.id] = child
        return child
    }

    private fun leave(
        inst: Instance,
        cursor: Cursor,
        graph: Graph,
        nodeId: String,
        inBranch: Boolean,
        now: Long,
        beforeMove: () -> StepResult?,
    ): StepResult {
        val outs = graph.outgoing[nodeId].orEmpty()
        if (outs.isEmpty()) {
            if (!inBranch) {
                abortNow(inst, "flow from '$nodeId' has nowhere to go")
                return StepResult.BLOCKED
            }
            val failed = beforeMove()
            if (failed != null) return failed
            cursor.done = true
            return StepResult.BRANCH_DONE
        }
        val next = opened(inst, cursor, graph, nodeId, now) ?: return StepResult.BLOCKED
        val failed = beforeMove()
        if (failed != null) return failed
        return moveTo(cursor, next, now)
    }

    /** Left-most transition that is true, or the single direct link. */
    private fun opened(inst: Instance, cursor: Cursor, graph: Graph, nodeId: String, now: Long): String? {
        val outs = graph.outgoing[nodeId].orEmpty()
        for (id in outs) {
            val next = graph.nodes[id]
            if (next is Node.Transition) armTimeout(inst, cursor, next, now)
        }
        for (id in outs) {
            val next = graph.nodes[id] ?: continue
            if (next is Node.Transition) {
                if (isTrue(eval(inst, next.expression))) return single(graph, next.id)
            } else return id
        }
        return null
    }

    private fun armTimeout(inst: Instance, cursor: Cursor, node: Node.Transition, now: Long) {
        val flag = node.timeoutFlag ?: return
        val limit = node.timeoutMillis ?: return
        if (inst.vars[flag] == true) return
        if (now - cursor.enteredAtMillis >= limit) inst.vars[flag] = true
    }

    private fun moveTo(cursor: Cursor, nodeId: String, now: Long): StepResult {
        cursor.nodeId = nodeId
        cursor.onStartDone = false
        cursor.enteredAtMillis = now
        cursor.timerRuns = 0
        cursor.childId = null
        cursor.branches = null
        cursor.done = false
        return StepResult.PROGRESSED
    }

    private fun single(graph: Graph, id: String): String =
        graph.outgoing[id]?.singleOrNull() ?: id

    private fun runGuarded(inst: Instance, actions: List<StepAction>, onError: List<StepAction>): Boolean {
        try {
            runActions(inst, actions, null)
        } catch (e: ActionFailed) {
            if (onError.isEmpty()) {
                abortNow(inst, e.message ?: "action failed")
                return false
            }
            try {
                runActions(inst, onError, null)
            } catch (e2: ActionFailed) {
                abortNow(inst, e2.message ?: "error handler failed")
                return false
            }
        }
        return inst.state == ChartState.RUNNING
    }

    private fun runActions(inst: Instance, actions: List<StepAction>, restart: RestartAction?) {
        for (action in actions) {
            when (action.kind) {
                "expression" -> {
                    val value = eval(inst, action.expression!!)
                    if (value.quality.level == Level.ERROR) throw ActionFailed(value.quality.toString())
                    if (action.assign != null) inst.vars[action.assign] = value.value
                }
                "script" -> {
                    val scope = scriptScope(inst, restart ?: RestartAction())
                    try {
                        scripts.execute(action.source ?: "", scope)
                    } catch (e: RuntimeException) {
                        throw ActionFailed(e.message ?: "script failed")
                    }
                }
            }
        }
    }

    private fun scriptScope(inst: Instance, restart: RestartAction) = ChartScriptScope(
        chartPath = inst.chart.name,
        instanceId = inst.id,
        variables = inst.vars,
        step = inst.stepVars,
        abortCause = inst.abortCause,
        activeSteps = activeSteps(inst),
        restart = restart,
        parent = inst.parent?.vars,
    )

    private fun eval(inst: Instance, expr: org.oip.tags.expr.Expr): Value {
        val locals = HashMap<String, Value>(inst.vars.size + 8)
        for ((key, value) in inst.vars) locals[key] = Value(value)
        locals["instanceId"] = Value(inst.id)
        locals["startTime"] = Value(inst.startedAtMillis)
        locals["runningTime"] = Value(runningSeconds(inst, clock.millis()))
        locals["running"] = Value(inst.state == ChartState.RUNNING)
        locals["state"] = Value(inst.state.code.toLong())
        locals["chartPath"] = Value(inst.chart.name)
        if (inst.abortCause != null) locals["abortCause"] = Value(inst.abortCause)
        return Expressions.eval(expr, EvalContext(ref = { token -> resolve(inst, token) }, locals = locals))
    }

    private fun resolve(inst: Instance, token: String): Value {
        if (token.startsWith("parent.")) {
            val parent = inst.parent ?: return Value(null, Quality(QualityCode.BAD_NOT_FOUND, "no parent chart"))
            val found = lookup(parent.vars, token.removePrefix("parent."))
            return if (found === MISSING_LOOKUP) Value(null, Quality(QualityCode.BAD_NOT_FOUND, token)) else Value(found)
        }
        if (token.startsWith("[") || token.contains("/")) {
            val engine = tags ?: return Value(null, Quality.BAD_NOT_FOUND)
            val read = engine.read(token)
            return Value(read.value, read.quality)
        }
        return Value(null, Quality(QualityCode.BAD_NOT_FOUND, token))
    }

    private fun cancelNow(inst: Instance, reason: String) {
        if (inst.state.terminal) return
        inst.state = ChartState.CANCELING
        for (child in children(inst)) cancelNow(child, reason)
        inst.abortCause = null
        runCatching { runActions(inst, inst.chart.events.onCancel, null) }
            .onFailure { log.log(LogLevel.WARNING, "onCancel failed for ${inst.id}", it) }
        inst.state = ChartState.CANCELED
        inst.cursor = null
        stateStore.delete(inst.id)
    }

    private fun abortNow(inst: Instance, cause: String) {
        if (inst.state.terminal) return
        inst.state = ChartState.ABORTING
        inst.abortCause = cause
        for (child in children(inst)) cancelNow(child, cause)
        try {
            runActions(inst, inst.chart.events.onAbort, null)
        } catch (e: RuntimeException) {
            log.warning("onAbort failed for ${inst.chart.name}: ${e.message}")
        }
        inst.state = ChartState.ABORTED
        inst.cursor = null
        stateStore.delete(inst.id)
    }

    private fun stopNow(inst: Instance) {
        if (inst.state.terminal) return
        inst.state = ChartState.STOPPING
        runCatching { runActions(inst, inst.chart.events.onStop, null) }
            .onFailure { e -> abortNow(inst, e.message ?: "onStop failed"); return }
        if (inst.state.terminal) return
        inst.state = ChartState.STOPPED
        inst.cursor = null
        stateStore.delete(inst.id)
    }

    private fun restart(inst: Instance, now: Long) {
        inst.vars = initialVars(inst.chart, inst.startParams)
        inst.stepVars = LinkedHashMap()
        inst.abortCause = null
        inst.pauseAccumulated = 0
        inst.startedAtMillis = now
        inst.cursor = null
        inst.state = ChartState.STARTING
        drive(inst, Budget(MAX_STEPS), now)
    }

    private fun jumpTo(inst: Instance, step: String, now: Long) {
        if (inst.chart.graph.nodes[step] == null) {
            abortNow(inst, "failover step '$step' was not found")
            return
        }
        inst.state = ChartState.RUNNING
        inst.cursor = Cursor(step, enteredAtMillis = now)
        drive(inst, Budget(MAX_STEPS), now)
    }

    private fun children(inst: Instance): List<Instance> = instances.values.filter { it.parent === inst && !it.state.terminal }

    private fun snapshot(inst: Instance) {
        if (inst.parent != null) return
        if (inst.state.terminal || !(inst.chart.persistState || inst.chart.redundancySync)) {
            stateStore.delete(inst.id)
            for (child in instances.values.filter { it.parent === inst }) stateStore.delete(child.id)
            return
        }
        stateStore.save(inst.id, writeSnapshot(inst))
        for (child in instances.values.filter { it.parent === inst }) stateStore.save(child.id, writeSnapshot(child))
    }

    private fun writeSnapshot(inst: Instance): String {
        val root = linkedMapOf<String, Any?>(
            "id" to inst.id,
            "chart" to inst.chart.name,
            "state" to inst.state.name,
            "resumeState" to inst.resumeState.name,
            "variables" to inst.vars,
            "startedAt" to inst.startedAtMillis,
            "pauseAccumulated" to inst.pauseAccumulated,
            "pausedAt" to inst.pausedAt,
            "parentId" to inst.parent?.id,
            "key" to inst.key,
            "abortCause" to inst.abortCause,
            "startParams" to inst.startParams,
            "cursor" to inst.cursor?.let { cursorMap(it) },
        )
        return MAPPER.writeValueAsString(root)
    }

    private fun cursorMap(cursor: Cursor): Map<String, Any?> = linkedMapOf(
        "nodeId" to cursor.nodeId,
        "onStartDone" to cursor.onStartDone,
        "enteredAt" to cursor.enteredAtMillis,
        "timerRuns" to cursor.timerRuns,
        "childId" to cursor.childId,
        "done" to cursor.done,
        "branches" to cursor.branches?.map { cursorMap(it) },
    )

    private fun importOne(doc: String) {
        val raw = asMap(MAPPER.readValue(doc, Any::class.java)) ?: return
        val id = raw["id"] as? String ?: return
        val name = raw["chart"] as? String ?: return
        val chart = charts[name] ?: return
        if (instances.containsKey(id)) return
        val parentId = raw["parentId"] as? String
        val inst = Instance(
            id = id,
            chart = chart,
            state = enumOf(raw["state"] as? String) ?: ChartState.RUNNING,
            vars = mutableMap(raw["variables"]),
            parent = null,
            key = raw["key"] as? String,
            startedAtMillis = (raw["startedAt"] as? Number)?.toLong() ?: clock.millis(),
            startParams = mutableMap(raw["startParams"]),
        )
        inst.resumeState = enumOf(raw["resumeState"] as? String) ?: ChartState.RUNNING
        inst.pauseAccumulated = (raw["pauseAccumulated"] as? Number)?.toLong() ?: 0
        inst.pausedAt = (raw["pausedAt"] as? Number)?.toLong() ?: inst.startedAtMillis
        inst.abortCause = raw["abortCause"] as? String
        inst.cursor = readCursor(raw["cursor"])
        inst.pendingParentId = parentId
        instances[id] = inst
    }

    private fun linkParents() {
        for (inst in instances.values) {
            val parentId = inst.pendingParentId ?: continue
            inst.parent = instances[parentId]
            inst.pendingParentId = null
        }
    }

    private fun readCursor(raw: Any?): Cursor? {
        val map = asMap(raw) ?: return null
        val node = map["nodeId"] as? String ?: return null
        val cursor = Cursor(node, map["onStartDone"] as? Boolean ?: false, (map["enteredAt"] as? Number)?.toLong() ?: 0)
        cursor.timerRuns = (map["timerRuns"] as? Number)?.toInt() ?: 0
        cursor.childId = map["childId"] as? String
        cursor.done = map["done"] as? Boolean ?: false
        val branches = asList(map["branches"])
        if (branches.isNotEmpty()) cursor.branches = branches.mapNotNull { readCursor(it) }.toMutableList()
        return cursor
    }

    private fun mutableMap(raw: Any?): MutableMap<String, Any?> {
        val map = asMap(raw) ?: return LinkedHashMap()
        return LinkedHashMap(map)
    }

    private fun enumOf(name: String?): ChartState? = name?.let { runCatching { ChartState.valueOf(it) }.getOrNull() }

    private fun view(inst: Instance): Map<String, Any?> = linkedMapOf(
        "id" to inst.id,
        "chart" to inst.chart.name,
        "state" to inst.state.label,
        "stateCode" to inst.state.code,
        "activeSteps" to activeSteps(inst),
        "variables" to LinkedHashMap(inst.vars),
        "key" to inst.key,
        "startedAt" to Instant.ofEpochMilli(inst.startedAtMillis).toString(),
        "runningSeconds" to runningSeconds(inst, clock.millis()),
        "parentId" to inst.parent?.id,
        "abortCause" to inst.abortCause,
    )

    private fun activeSteps(inst: Instance): List<String> {
        val cursor = inst.cursor ?: return emptyList()
        return active(cursor, inst.chart.graph)
    }

    private fun active(cursor: Cursor, graph: Graph): List<String> {
        if (cursor.done) return emptyList()
        val node = graph.nodes[cursor.nodeId]
        if (node is Node.Parallel && cursor.branches != null) {
            return cursor.branches!!.flatMapIndexed { index, branch ->
                if (index >= node.branches.size) emptyList() else active(branch, node.branches[index])
            }
        }
        return listOf(cursor.nodeId)
    }

    private fun runningSeconds(inst: Instance, now: Long): Long {
        val end = if (inst.state == ChartState.PAUSED || inst.state == ChartState.REDUNDANT_INACTIVE) inst.pausedAt else now
        return ((end - inst.startedAtMillis - inst.pauseAccumulated) / 1000L).coerceAtLeast(0)
    }

    private fun shift(cursor: Cursor?, delta: Long) {
        if (cursor == null || delta == 0L) return
        cursor.enteredAtMillis += delta
        cursor.branches?.forEach { shift(it, delta) }
    }

    private fun trim() {
        val done = instances.values.filter { it.state.terminal && it.parent == null }
        if (done.size <= KEEP_TERMINAL) return
        for (inst in done.sortedBy { it.startedAtMillis }.take(done.size - KEEP_TERMINAL)) {
            val drop = instances.values.filter { it === inst || it.parent === inst }.map { it.id }
            drop.forEach { instances.remove(it) }
        }
    }

    private fun ensureScheduler() {
        if (inline || scheduled || closed) return
        val exec = executor ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "sfc-engine").apply { isDaemon = true; worker = this }
        }.also { executor = it }
        scheduled = true
        exec.scheduleWithFixedDelay(
            { runCatching { if (!closed) onEngine { driveAll() } } },
            TICK_MILLIS,
            TICK_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun onEngine(block: () -> Unit) {
        if (closed) return
        if (deferWhen()) {
            lock.withLock { pending.add(block) }
            return
        }
        if (inline || Thread.currentThread() === worker) {
            lock.withLock { block() }
            return
        }
        val exec = executor ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "sfc-engine").apply { isDaemon = true; worker = this }
        }.also { executor = it }
        val future = CompletableFuture<Unit>()
        exec.execute {
            try {
                lock.withLock { block() }
                future.complete(null)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        try {
            future.get(30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is RuntimeException) throw cause
            throw IllegalStateException(cause?.message ?: "chart execution failed", cause)
        }
    }

    private class Budget(var left: Int)
    private enum class StepResult { BLOCKED, PROGRESSED, BRANCH_DONE, REACHED_END }

    private class Cursor(var nodeId: String, var onStartDone: Boolean = false, var enteredAtMillis: Long = 0) {
        var timerRuns: Int = 0
        var childId: String? = null
        var branches: MutableList<Cursor>? = null
        var done: Boolean = false
    }

    private class Instance(
        val id: String,
        val chart: CompiledChart,
        var state: ChartState,
        var vars: MutableMap<String, Any?>,
        var parent: Instance?,
        val key: String?,
        var startedAtMillis: Long,
        val startParams: Map<String, Any?>,
    ) {
        var cursor: Cursor? = null
        var stepVars: MutableMap<String, Any?> = LinkedHashMap()
        var abortCause: String? = null
        var pauseAccumulated: Long = 0
        var pausedAt: Long = startedAtMillis
        var resumeState: ChartState = ChartState.RUNNING
        var pendingParentId: String? = null
    }

    companion object {
        private const val TICK_MILLIS = 50L
        private const val MAX_STEPS = 256
        private const val KEEP_TERMINAL = 100
        private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")
        private val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
    }
}

enum class ChartState(val code: Int, val label: String) {
    ABORTED(0, "Aborted"),
    ABORTING(1, "Aborting"),
    CANCELED(2, "Canceled"),
    CANCELING(3, "Canceling"),
    INITIAL(4, "Initial"),
    INIT_PAUSED(5, "InitPaused"),
    PAUSED(6, "Paused"),
    PAUSING(7, "Pausing"),
    RESUMING(8, "Resuming"),
    RUNNING(9, "Running"),
    STARTING(10, "Starting"),
    STOPPED(11, "Stopped"),
    STOPPING(12, "Stopping"),
    SUSPENDED(13, "Suspended"),
    REDUNDANT_INACTIVE(14, "RedundantInactive"),
    ;

    val terminal: Boolean get() = this == ABORTED || this == CANCELED || this == STOPPED
}

class ChartNotFound(message: String) : NoSuchElementException(message)
class ChartRejected(message: String) : IllegalArgumentException(message)
private class ActionFailed(message: String) : RuntimeException(message)

interface ChartStateStore {
    fun save(id: String, document: String)
    fun delete(id: String)
    fun load(): List<String>
}

class MemoryChartStateStore : ChartStateStore {
    private val docs = LinkedHashMap<String, String>()
    override fun save(id: String, document: String) { synchronized(docs) { docs[id] = document } }
    override fun delete(id: String) { synchronized(docs) { docs.remove(id) } }
    override fun load(): List<String> = synchronized(docs) { docs.values.toList() }
}

class FileChartStateStore(private val dir: Path) : ChartStateStore {
    override fun save(id: String, document: String) {
        Files.createDirectories(dir)
        val name = safe(id)
        val tmp = dir.resolve("$name.json.tmp")
        val dest = dir.resolve("$name.json")
        Files.writeString(tmp, document)
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete(id: String) {
        Files.deleteIfExists(dir.resolve("${safe(id)}.json"))
        Unit
    }

    override fun load(): List<String> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") && !it.fileName.toString().endsWith(".tmp") }
                .map { Files.readString(it) }
                .toList()
        }
    }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }
}

private fun lookup(root: Map<String, Any?>, path: String): Any? {
    var current: Any? = root
    for (part in path.split('.')) {
        current = when (val node = current) {
            is Map<*, *> -> if (part in node) node[part] else return MISSING_LOOKUP
            else -> return MISSING_LOOKUP
        }
    }
    return current
}

private val MISSING_LOOKUP = Any()

private fun isTrue(value: Value): Boolean {
    if (value.quality.level.ordinal > Level.UNCERTAIN.ordinal) return false
    return when (val raw = value.value) {
        null -> false
        is Boolean -> raw
        is Number -> raw.toDouble() != 0.0 && !raw.toDouble().isNaN()
        is String -> raw.isNotEmpty() && !raw.equals("false", true) && raw != "0"
        else -> true
    }
}

private fun compare(left: Any?, op: String, right: Any?): Boolean {
    if (left is Number && right is Number) {
        val cmp = left.toDouble().compareTo(right.toDouble())
        return when (op) {
            "=" -> cmp == 0
            "!=" -> cmp != 0
            "<" -> cmp < 0
            ">" -> cmp > 0
            "<=" -> cmp <= 0
            ">=" -> cmp >= 0
            else -> false
        }
    }
    return when (op) {
        "=" -> left == right
        "!=" -> left != right
        else -> false
    }
}
