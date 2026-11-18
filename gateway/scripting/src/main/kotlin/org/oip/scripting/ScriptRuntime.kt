package org.oip.scripting

import com.sun.management.ThreadMXBean
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.oip.tags.TagEngine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One shared GraalPy engine and a fresh sandboxed context per run.
 *
 * Community GraalPy 24.2 accepts only [org.graalvm.polyglot.SandboxPolicy.TRUSTED]. The
 * context still closes the host: no IO, no native access, no process, no host class
 * loading, no environment, no other languages, and [HostAccess.CONSTRAINED] so guest
 * code reaches the gateway only through the exported methods on [ScriptHost].
 *
 * A watchdog cancels the context when the wall-clock timeout or the allocation cap is
 * crossed. The statement budget cancels a tight loop. Cancellation closes that context
 * only. The tag engine is not on this thread.
 *
 * A request handler (`system.util.sendRequest`) runs inline on the caller's thread with its
 * own context and its own limits. What it allocates, context setup included, is charged to the
 * handler and not to the caller's allocation cap: the caller's meter is paused while it runs.
 */
class ScriptRuntime(
    private val host: ScriptHost,
    private val tags: TagEngine,
    private val logger: ScriptLogger,
    private val stats: ScriptStats = ScriptStats(),
) : AutoCloseable {
    private val engine: Engine by lazy { sharedEngine() }
    private val prelude: String by lazy {
        ScriptRuntime::class.java.getResourceAsStream("/oip_system.py")!!.bufferedReader().use { it.readText() }
    }
    private val active = ConcurrentHashMap.newKeySet<Active>()
    /** The run executing on this thread, so a nested run can pause its caller's allocation meter. */
    private val current = ThreadLocal<Active?>()
    private val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val guard = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "oip-script-guard").apply { isDaemon = true }
    }

    init {
        if (bean.isThreadAllocatedMemorySupported) bean.isThreadAllocatedMemoryEnabled = true
        else throw IllegalStateException("thread allocation measurement is required for the script memory cap")
        guard.scheduleWithFixedDelay({ runCatching { watch() } }, 10, 10, TimeUnit.MILLISECONDS)
    }

    fun run(
        source: String,
        name: String,
        limits: ScriptLimits,
        libraries: List<Pair<String, String>> = emptyList(),
        eventJson: String? = null,
        payloadJson: String? = null,
        /** The caller's scope ([ScriptScope]). Every scope runs here with the same libraries (SCR-042). */
        @Suppress("UNUSED_PARAMETER") scope: ScriptScope = ScriptScope.GATEWAY,
        chart: MutableMap<String, Any?>? = null,
        streamJson: String? = null,
        /** Host for this run only, such as one acting for an HMI session user; null uses the runtime's own. */
        hostOverride: ScriptHost? = null,
        /** Polled by the watchdog (keep it a flag read); true cancels the run as [ScriptFailure.CANCELLED]. */
        cancelled: (() -> Boolean)? = null,
    ): ScriptResult {
        // SCR-042: every scope (gateway, HMI session, designer) runs here, with the same library snapshot.
        val started = System.nanoTime()
        val caller = current.get()
        caller?.pause(allocatedBytes())
        stats.begin(name)
        var finished = false
        try {
            val result = runOwn(source, name, limits, libraries, eventJson, payloadJson, chart, streamJson, hostOverride, cancelled, started)
            stats.end(name, result.millis, if (result.ok) null else result.error ?: result.failure?.name ?: "script failed")
            finished = true
            return result
        } finally {
            if (!finished) stats.end(name, (System.nanoTime() - started) / 1_000_000, "script aborted")
            current.set(caller)
            caller?.resume(allocatedBytes())
        }
    }

    private fun runOwn(
        source: String,
        name: String,
        limits: ScriptLimits,
        libraries: List<Pair<String, String>>,
        eventJson: String?,
        payloadJson: String?,
        chart: MutableMap<String, Any?>?,
        streamJson: String?,
        hostOverride: ScriptHost?,
        cancelled: (() -> Boolean)?,
        started: Long,
    ): ScriptResult {
        val out = LimitedStream(limits.maxOutputBytes)
        val err = LimitedStream(limits.maxOutputBytes)
        val context = try {
            newContext(limits, out, err)
        } catch (e: Throwable) {
            return failed(name, ScriptFailure.ERROR, e.message ?: e.javaClass.simpleName, (System.nanoTime() - started) / 1_000_000, false)
        }
        val run = Active(context, Thread.currentThread(), limits, cancelled)
        current.set(run)
        var held = false
        var thread = Thread.currentThread().name
        try {
            context.getBindings("python").putMember("_oip_host", hostOverride ?: host)
            context.eval(source("oip_system.py", prelude))
            held = tags.holdingWriteLock()
            thread = Thread.currentThread().name
            run.startedNanos = System.nanoTime()
            run.baseAlloc = allocatedBytes()
            active.add(run)
            installLibraries(context, libraries)
            if (eventJson != null) bindEvent(context, eventJson)
            if (payloadJson != null) bindPayload(context, payloadJson)
            if (chart != null) bindChart(context, chart)
            if (streamJson != null) context.getBindings("python").putMember("_oip_stream_json", streamJson)
            context.eval(source("user.py", source))
            if (out.exceeded || err.exceeded) {
                return finish(name, false, null, out, err, ScriptFailure.OUTPUT, "script output exceeded ${limits.maxOutputBytes} bytes", thread, held, started)
            }
            val value = fromGuest(context.getBindings("python").getMember("_result"))
            return finish(name, true, value, out, err, null, null, thread, held, started)
        } catch (e: PolyglotException) {
            val failure = classify(e, run, out, err)
            return finish(name, false, null, out, err, failure, guestMessage(e), thread, held, started)
        } catch (e: Throwable) {
            val failure = run.reason.get() ?: if (statementCancel(e)) ScriptFailure.STATEMENTS else ScriptFailure.ERROR
            return finish(name, false, null, out, err, failure, e.message ?: e.javaClass.simpleName, thread, held, started)
        } finally {
            active.remove(run)
            runCatching { context.close() }
        }
    }

    override fun close() {
        guard.shutdownNow()
        for (run in active.toList()) kill(run, ScriptFailure.TIMEOUT)
    }

    private fun newContext(limits: ScriptLimits, out: OutputStream, err: OutputStream): Context =
        Context.newBuilder("python")
            .engine(engine)
            .allowHostAccess(HostAccess.CONSTRAINED)
            .allowIO(IOAccess.NONE)
            .allowNativeAccess(false)
            .allowCreateProcess(false)
            .allowHostClassLoading(false)
            .allowHostClassLookup { false }
            .allowCreateThread(true)
            .allowEnvironmentAccess(EnvironmentAccess.NONE)
            .allowPolyglotAccess(PolyglotAccess.NONE)
            .`in`(InputStream.nullInputStream())
            .out(out)
            .err(err)
            .resourceLimits(
                ResourceLimits.newBuilder()
                    .statementLimit(limits.maxStatements) { src ->
                        val name = src?.name.orEmpty()
                        // Only the user script and project-library files count. The prelude and the
                        // Python runtime import more than a small budget on their own.
                        name == "user.py" || name.startsWith("lib_")
                    }
                    .build(),
            )
            .build()

    private fun installLibraries(context: Context, libraries: List<Pair<String, String>>) {
        if (libraries.isEmpty()) return
        val install = context.getBindings("python").getMember("_oip_install_module")
        for ((module, source) in libraries) install.executeVoid(module, source)
    }

    private fun bindEvent(context: Context, eventJson: String) {
        context.getBindings("python").putMember("_event_json", eventJson)
        context.eval(source("oip_bind.py", """
            import json
            _ev = json.loads(_event_json)
            initialChange = bool(_ev["initialChange"])
            executionCount = int(_ev["executionCount"])
            newValue = _qv(_ev["newValue"])
            previousValue = None if _ev["previousValue"] is None else _qv(_ev["previousValue"])
            event = _Event(_ev)
            del _event_json
        """.trimIndent()))
    }

    private fun bindChart(context: Context, variables: MutableMap<String, Any?>) {
        context.getBindings("python").putMember("_oip_chart", ChartVars(variables))
        context.eval(source("oip_chart.py", """
            class _Chart:
                def __getattr__(self, name):
                    if not _oip_chart.has(name):
                        raise AttributeError(name)
                    return json.loads(_oip_chart.get(name))
                def __setattr__(self, name, value):
                    _oip_chart.set(name, json.dumps(value))
            chart = _Chart()
        """.trimIndent()))
    }

    private fun bindPayload(context: Context, payloadJson: String) {
        context.getBindings("python").putMember("_payload_json", payloadJson)
        context.eval(source("oip_payload.py", """
            import json
            payload = json.loads(_payload_json)
            del _payload_json
        """.trimIndent()))
    }

    private fun watch() {
        val now = System.nanoTime()
        for (run in active) {
            if (run.reason.get() != null || run.startedNanos == 0L) continue
            if (run.cancelled?.let { runCatching(it).getOrDefault(false) } == true) {
                kill(run, ScriptFailure.CANCELLED)
                continue
            }
            val elapsed = now - run.startedNanos
            if (elapsed > run.limits.timeout.toNanos()) {
                kill(run, ScriptFailure.TIMEOUT)
                continue
            }
            val base = run.baseAlloc
            if (base < 0) continue
            // Read the pause mark before the excluded total; resume() writes them in the other order.
            val paused = run.pausedAt
            val excluded = run.excluded
            val used = if (paused >= 0) paused else bean.getThreadAllocatedBytes(run.thread.threadId())
            if (used >= 0 && used - base - excluded > run.limits.maxAllocatedBytes) kill(run, ScriptFailure.MEMORY)
        }
    }

    private fun allocatedBytes(): Long = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())

    private fun kill(run: Active, failure: ScriptFailure) {
        if (!run.reason.compareAndSet(null, failure)) return
        Thread({
            runCatching { run.context.close(true) }
        }, "oip-script-kill").apply { isDaemon = true }.start()
    }

    private fun classify(e: PolyglotException, run: Active, out: LimitedStream, err: LimitedStream): ScriptFailure {
        run.reason.get()?.let { return it }
        if (out.exceeded || err.exceeded) return ScriptFailure.OUTPUT
        val message = e.message ?: ""
        if ("SyntaxError" in message) return ScriptFailure.ERROR
        if (statementCancel(e)) return ScriptFailure.STATEMENTS
        if (sandbox(message)) return ScriptFailure.SANDBOX
        if (e.isResourceExhausted || e.isCancelled || e.isInterrupted) return ScriptFailure.STATEMENTS
        return ScriptFailure.ERROR
    }

    /** GraalPy can report a statement-limit cancel as a GIL assertion instead of a resource error. */
    private fun statementCancel(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message ?: ""
            if ("invalid hold count" in message || "Statement count limit" in message) return true
            current = current.cause
        }
        return false
    }

    private fun sandbox(message: String): Boolean {
        val text = message.lowercase()
        return listOf(
            "not permitted", "not allowed", "host access", "permission", "denied",
            "unsupported operation", "oserror", "permissionerror", "systemexit",
            "no such file", "operation not supported",
        ).any { it in text }
    }

    private fun guestMessage(e: PolyglotException): String {
        val text = e.message ?: e.javaClass.simpleName
        return if (text.length > 2_000) text.substring(0, 2_000) else text
    }

    private fun finish(
        name: String,
        ok: Boolean,
        value: Any?,
        out: LimitedStream,
        err: LimitedStream,
        failure: ScriptFailure?,
        error: String?,
        thread: String,
        held: Boolean,
        started: Long,
    ): ScriptResult {
        val output = buildString {
            append(out.text())
            val errors = err.text()
            if (errors.isNotEmpty()) {
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append(errors)
            }
        }
        output.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.forEach { line ->
            logger.log("INFO", name, line)
        }
        return ScriptResult(ok, value, output, error, failure, thread, held, (System.nanoTime() - started) / 1_000_000)
    }

    private fun failed(name: String, failure: ScriptFailure, error: String, millis: Long, held: Boolean) =
        ScriptResult(false, null, "", error, failure, Thread.currentThread().name, held, millis)

    private fun source(name: String, text: String): Source =
        Source.newBuilder("python", text, name).build()

    private class Active(
        val context: Context,
        val thread: Thread,
        val limits: ScriptLimits,
        val cancelled: (() -> Boolean)? = null,
    ) {
        val reason = AtomicReference<ScriptFailure?>(null)
        @Volatile var startedNanos: Long = 0
        @Volatile var baseAlloc: Long = -1
        /** Thread allocation when a nested run started, or -1 while this run's meter is running. */
        @Volatile var pausedAt: Long = -1
        /** Bytes allocated by nested runs. Written only by the run's own thread. */
        @Volatile var excluded: Long = 0

        fun pause(allocated: Long) {
            pausedAt = allocated
        }

        fun resume(allocated: Long) {
            val mark = pausedAt
            if (mark < 0) return
            if (allocated >= mark) excluded += allocated - mark
            pausedAt = -1
        }
    }

    private class LimitedStream(private val cap: Int) : OutputStream() {
        private val buf = ByteArrayOutputStream()
        @Volatile var exceeded = false
        override fun write(b: Int) {
            if (buf.size() >= cap) { exceeded = true; return }
            buf.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = cap - buf.size()
            if (room <= 0) { exceeded = true; return }
            val n = minOf(len, room)
            buf.write(b, off, n)
            if (n < len) exceeded = true
        }
        fun text(): String = buf.toString(StandardCharsets.UTF_8)
    }

    companion object {
        private val engineRef = lazy {
            Engine.newBuilder("python")
                .option("engine.WarnInterpreterOnly", "false")
                .build()
        }

        fun sharedEngine(): Engine = engineRef.value

        private val warmed = java.util.concurrent.CompletableFuture<Boolean>()
        private val warming = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Build the process-wide engine and initialise Python once in a throwaway sandboxed context,
         * so the first script does not pay GraalPy's cold start (seconds on a small host). Runs no
         * user code and creates no [ScriptRuntime] (no watchdog thread). Only the first call in the
         * process does work; every caller gets the same future (true when the engine started).
         */
        fun warmEngine(): java.util.concurrent.CompletableFuture<Boolean> {
            if (warming.compareAndSet(false, true)) {
                Thread({
                    val ok = runCatching {
                        Context.newBuilder("python")
                            .engine(sharedEngine())
                            .allowHostAccess(HostAccess.CONSTRAINED)
                            .allowIO(IOAccess.NONE)
                            .allowNativeAccess(false)
                            .allowCreateProcess(false)
                            .allowHostClassLoading(false)
                            .allowHostClassLookup { false }
                            .allowEnvironmentAccess(EnvironmentAccess.NONE)
                            .allowPolyglotAccess(PolyglotAccess.NONE)
                            .`in`(InputStream.nullInputStream())
                            .out(OutputStream.nullOutputStream())
                            .err(OutputStream.nullOutputStream())
                            .build()
                            .use { it.eval("python", "import json\njson.loads(json.dumps({'warm': [1]}))\n") }
                        true
                    }.getOrDefault(false)
                    warmed.complete(ok)
                }, "oip-script-warmup").apply { isDaemon = true }.start()
            }
            return warmed
        }
    }
}

internal fun fromGuest(value: Value?, depth: Int = 0): Any? {
    if (value == null || value.isNull) return null
    if (depth > 8) return value.toString()
    if (value.isBoolean) return value.asBoolean()
    if (value.isString) return value.asString()
    if (value.isNumber) return if (value.fitsInLong()) value.asLong() else value.asDouble()
    if (value.hasMembers() && value.hasMember("_millis") && !value.hasHashEntries()) {
        return value.getMember("_millis").asLong()
    }
    if (value.hasArrayElements() && !value.hasHashEntries()) {
        return (0 until value.arraySize).map { fromGuest(value.getArrayElement(it), depth + 1) }
    }
    if (value.hasHashEntries()) {
        val out = linkedMapOf<String, Any?>()
        val keys = value.hashKeysIterator
        while (keys.hasIteratorNextElement()) {
            val key = keys.getIteratorNextElement()
            out[fromGuest(key, depth + 1).toString()] = fromGuest(value.getHashValue(key), depth + 1)
        }
        return out
    }
    return value.toString()
}
