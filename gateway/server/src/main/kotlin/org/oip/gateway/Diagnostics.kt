package org.oip.gateway

import org.oip.config.FileConfigStore
import org.oip.host.ModuleHost
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.ceil
import kotlin.math.max

/**
 * Diagnostics snapshots for the admin API.
 *
 * Threads: -
 * Scheduled tasks: -
 * Performance (CPU, heap, disk, clock drift): -
 * Clock drift is the lateness of a 1 s sleep, `(actualWake - expectedWake) / 1 s` (ADR 0010).
 */
class TaskRegistry {
    private val tasks = linkedMapOf<String, TrackedTask>()
    fun register(name: String, rateMs: Long): TrackedTask = synchronized(tasks) {
        check(name !in tasks) { "task '$name' already registered" }
        TrackedTask(name, rateMs).also { tasks[name] = it }
    }
    fun snapshot(): List<Map<String, Any?>> = synchronized(tasks) { tasks.values.map { it.snapshot() } }
}

class TrackedTask(val name: String, val rateMs: Long) {
    @Volatile var state: String = "IDLE"
        private set
    @Volatile var lastDurationNanos: Long = 0
        private set
    @Volatile var runs: Long = 0
        private set
    @Volatile var lastError: String? = null
        private set

    fun measure(block: () -> Unit) {
        val t0 = System.nanoTime()
        state = "RUNNING"
        try {
            block()
            state = "IDLE"
            lastError = null
        } catch (t: Throwable) {
            state = "FAILED"
            lastError = t.message ?: t.javaClass.simpleName
            throw t
        } finally {
            lastDurationNanos = System.nanoTime() - t0
            runs += 1
        }
    }

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "rateMs" to rateMs,
        "state" to state,
        "lastDurationMillis" to lastDurationNanos / 1_000_000.0,
        "runs" to runs,
        "lastError" to lastError,
    )
}

/** Fixed-bucket latency histogram. Bucket i covers values up to 2^i microseconds. */
class LatencyRecorder {
    private class Histo {
        val buckets = Array(63) { LongAdder() }
        val count = LongAdder()
        val max = AtomicLong()
        fun record(micros: Long) {
            val us = micros.coerceAtLeast(0)
            val idx = if (us <= 1) 0 else (63 - java.lang.Long.numberOfLeadingZeros(us - 1)).coerceIn(0, 62)
            buckets[idx].increment()
            count.increment()
            max.accumulateAndGet(us) { a, b -> max(a, b) }
        }
        fun percentile(p: Double): Long {
            val n = count.sum()
            if (n == 0L) return 0
            val rank = ceil(p / 100.0 * n).toLong().coerceIn(1, n)
            var acc = 0L
            for (i in buckets.indices) {
                acc += buckets[i].sum()
                if (acc >= rank) return (1L shl i).coerceAtMost(max.get().coerceAtLeast(0))
            }
            return max.get()
        }
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "count" to count.sum(),
            "p50Micros" to percentile(50.0),
            "p99Micros" to percentile(99.0),
            "maxMicros" to max.get(),
        )
    }

    private val total = Histo()
    private val routes = ConcurrentHashMap<String, Histo>()

    fun record(method: String, path: String, nanos: Long) {
        val micros = nanos / 1_000
        total.record(micros)
        val key = "$method $path"
        val existing = routes[key]
        if (existing != null) { existing.record(micros); return }
        if (routes.size >= 128) return
        val created = Histo()
        val prev = routes.putIfAbsent(key, created)
        (prev ?: created).record(micros)
    }

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "total" to total.toMap(),
        "routes" to routes.entries.sortedBy { it.key }.map { (key, histo) ->
            val sp = key.split(' ', limit = 2)
            linkedMapOf(
                "method" to sp[0],
                "path" to sp.getOrElse(1) { "" },
                "count" to histo.toMap().getValue("count"),
                "p50Micros" to histo.toMap().getValue("p50Micros"),
                "p99Micros" to histo.toMap().getValue("p99Micros"),
                "maxMicros" to histo.toMap().getValue("maxMicros"),
            )
        },
    )
}

/**
 * Named numeric metrics with a short rolling history, for user-built dashboards (GAT-024).
 *
 * A reader is called once per [sample], never per request, so a dashboard that polls costs nothing extra. A reader
 * that returns null (a value the platform cannot read) leaves a gap. Values are in display units: percent, MiB,
 * GiB, records, per second.
 */
class MetricsRegistry(private val capacity: Int = 120) {
    private class Def(val id: String, val label: String, val unit: String, val max: () -> Double?, val read: () -> Double?) {
        val values = ArrayDeque<Double?>()
        @Volatile var latestMax: Double? = null
    }

    private val defs = linkedMapOf<String, Def>()
    private val times = ArrayDeque<Long>()

    fun register(id: String, label: String, unit: String, max: () -> Double? = { null }, read: () -> Double?) = synchronized(defs) {
        check(id !in defs) { "metric '$id' already registered" }
        defs[id] = Def(id, label, unit, max, read)
    }

    /** A reader that turns an ever-growing counter into a per-second rate between two samples. */
    fun rate(counter: () -> Long, clock: () -> Long = System::nanoTime): () -> Double? {
        var lastValue = -1L
        var lastTime = 0L
        return {
            val now = clock()
            val value = counter()
            val out = if (lastValue < 0 || now <= lastTime) null else (value - lastValue) * 1_000_000_000.0 / (now - lastTime)
            lastValue = value
            lastTime = now
            out?.coerceAtLeast(0.0)
        }
    }

    fun sample(nowMillis: Long = System.currentTimeMillis()) = synchronized(defs) {
        times.addLast(nowMillis)
        while (times.size > capacity) times.removeFirst()
        for (d in defs.values) {
            val v = runCatching { d.read() }.getOrNull()?.takeIf { it.isFinite() }
            d.values.addLast(v)
            while (d.values.size > capacity) d.values.removeFirst()
            d.latestMax = runCatching { d.max() }.getOrNull()?.takeIf { it.isFinite() && it > 0 }
        }
    }

    fun ids(): List<String> = synchronized(defs) { defs.keys.toList() }

    fun snapshot(intervalMs: Long): Map<String, Any?> = synchronized(defs) {
        linkedMapOf(
            "intervalMs" to intervalMs,
            "times" to times.toList(),
            "metrics" to defs.values.map { d ->
                linkedMapOf(
                    "id" to d.id,
                    "label" to d.label,
                    "unit" to d.unit,
                    "max" to d.latestMax,
                    "value" to d.values.lastOrNull(),
                    "values" to d.values.toList(),
                )
            },
        )
    }
}

class Diagnostics(
    private val dataDir: Path,
    private val store: FileConfigStore,
    private val host: ModuleHost,
    val tasks: TaskRegistry = TaskRegistry(),
    val latency: LatencyRecorder = LatencyRecorder(),
    val clockDrift: ClockDriftDetector = ClockDriftDetector(),
) {
    private val samples = ArrayDeque<Map<String, Any?>>()

    /** Metrics a dashboard can plot (GAT-024). The gateway adds the ones that need its services when it starts. */
    val metrics = MetricsRegistry().also { m ->
        fun pct(key: String): () -> Double? = { (current()[key] as? Double)?.takeIf { it >= 0 }?.times(100) }
        m.register("cpu.system", "System CPU", "%", { 100.0 }, pct("cpuLoad"))
        m.register("cpu.process", "Gateway CPU", "%", { 100.0 }, pct("processCpuLoad"))
        m.register("heap.used", "Heap used", "MiB",
            { ManagementFactory.getMemoryMXBean().heapMemoryUsage.max.takeIf { it > 0 }?.let { it / MIB } },
            { ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / MIB })
        m.register("disk.free", "Data disk free", "GiB",
            { Files.getFileStore(dataDir).totalSpace / GIB },
            { Files.getFileStore(dataDir).usableSpace / GIB })
    }

    fun recordSample() {
        val now = current()
        @Suppress("UNCHECKED_CAST")
        val disk = now["disk"] as Map<String, Any?>
        val sample = linkedMapOf<String, Any?>(
            "cpuLoad" to now["cpuLoad"],
            "heapUsedBytes" to now["heapUsedBytes"],
            "diskUsableBytes" to disk["usableBytes"],
            "clockDrift" to now["clockDrift"],
        )
        synchronized(samples) {
            samples.addLast(sample)
            while (samples.size > 60) samples.removeFirst()
        }
        metrics.sample()
    }

    fun metricsSnapshot(): Map<String, Any?> = metrics.snapshot(SAMPLE_INTERVAL_MS)

    fun memory(): Map<String, Any?> {
        val mx = ManagementFactory.getMemoryMXBean()
        val heap = mx.heapMemoryUsage
        val non = mx.nonHeapMemoryUsage
        return linkedMapOf(
            "heap" to linkedMapOf("usedBytes" to heap.used, "committedBytes" to heap.committed, "maxBytes" to heap.max),
            "nonHeap" to linkedMapOf("usedBytes" to non.used, "committedBytes" to non.committed),
            "processors" to Runtime.getRuntime().availableProcessors(),
        )
    }

    fun threads(): Map<String, Any?> {
        val bean = ManagementFactory.getThreadMXBean()
        if (bean.isThreadCpuTimeSupported && !bean.isThreadCpuTimeEnabled) runCatching { bean.setThreadCpuTimeEnabled(true) }
        val daemons = Thread.getAllStackTraces().keys.associate { it.threadId() to it.isDaemon }
        val infos = bean.dumpAllThreads(false, false).filterNotNull()
        return linkedMapOf("threads" to infos.map { info ->
            linkedMapOf(
                "id" to info.threadId,
                "name" to info.threadName,
                "state" to info.threadState.name,
                "daemon" to (daemons[info.threadId] ?: false),
                "cpuNanos" to if (bean.isThreadCpuTimeSupported) bean.getThreadCpuTime(info.threadId) else -1L,
                "blockedCount" to info.blockedCount,
                "waitedCount" to info.waitedCount,
            )
        })
    }

    /** Plain-text dump, including stack frames, for download. */
    fun threadDump(): String {
        val bean = ManagementFactory.getThreadMXBean()
        return bean.dumpAllThreads(true, true).filterNotNull().joinToString("\n\n") { info ->
            buildString {
                append('"').append(info.threadName).append("\" #").append(info.threadId)
                append(' ').append(info.threadState)
                for (frame in info.stackTrace) append("\n\tat ").append(frame)
            }
        } + "\n"
    }

    fun modules(): Map<String, Any?> = linkedMapOf("modules" to host.installed().map { m ->
        linkedMapOf(
            "id" to m.id,
            "version" to m.version.toString(),
            "name" to m.name,
            "state" to host.state(m.id)?.name,
            "error" to host.lastError(m.id),
        )
    })

    fun config(): Map<String, Any?> {
        val list = store.list()
        return linkedMapOf(
            "resources" to list.size,
            "loadErrors" to store.loadErrors.size,
            "byModule" to list.groupingBy { it.id.module }.eachCount().toSortedMap(),
        )
    }

    fun performance(): Map<String, Any?> {
        val now = current()
        val history = synchronized(samples) { samples.toList() }
        return linkedMapOf(
            "cpuLoad" to now["cpuLoad"],
            "processCpuLoad" to now["processCpuLoad"],
            "heapUsedBytes" to now["heapUsedBytes"],
            "heapMaxBytes" to now["heapMaxBytes"],
            "disk" to now["disk"],
            "clockDrift" to clockDrift.snapshot(),
            "samples" to history,
        )
    }

    private fun current(): LinkedHashMap<String, Any?> {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val os = ManagementFactory.getOperatingSystemMXBean()
        val sun = os as? com.sun.management.OperatingSystemMXBean
        val disk = Files.getFileStore(dataDir)
        return linkedMapOf(
            "cpuLoad" to finite(sun?.cpuLoad ?: -1.0),
            "processCpuLoad" to finite(sun?.processCpuLoad ?: -1.0),
            "heapUsedBytes" to heap.used,
            "heapMaxBytes" to heap.max,
            "clockDrift" to finite(clockDrift.drift()),
            "disk" to linkedMapOf(
                "path" to dataDir.toAbsolutePath().toString(),
                "usableBytes" to disk.usableSpace,
                "totalBytes" to disk.totalSpace,
            ),
        )
    }

    private fun finite(d: Double) = if (d.isFinite()) d else -1.0

    companion object {
        const val SAMPLE_INTERVAL_MS = 5_000L
        private const val MIB = 1024.0 * 1024.0
        private const val GIB = 1024.0 * 1024.0 * 1024.0
    }
}
