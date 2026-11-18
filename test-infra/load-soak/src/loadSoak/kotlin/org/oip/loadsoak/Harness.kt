package org.oip.loadsoak

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.round

internal val mapper: ObjectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

internal fun repoRoot(): Path = Path.of(System.getProperty("loadSoak.root") ?: "../..").toAbsolutePath().normalize()

/** `ci` (the Load bench job) or `full` (the ADR 0010 floor size). */
internal fun sizeName(): String = System.getProperty("loadSoak.size") ?: "ci"

/** The size block for [sizeName] from test-infra/perf/<scenario>.json. */
internal fun sizes(scenario: String): JsonNode {
    val file = repoRoot().resolve("test-infra/perf/${scenario.lowercase(Locale.US)}.json")
    val tree = mapper.readTree(file.toFile())
    return tree["sizes"]?.get(sizeName()) ?: error("$file has no size '${sizeName()}'")
}

internal fun requirements(): JsonNode = mapper.readTree(repoRoot().resolve("test-infra/perf/perf-requirements.json").toFile())

internal fun round4(v: Double): Double = round(v * 10_000.0) / 10_000.0

/** Report JSON and Markdown under build/reports/load-soak, and also under -PloadSoakReport=<dir> when set. */
internal fun writeReport(scenario: String, report: Map<String, Any?>, markdown: String) {
    val slug = scenario.lowercase(Locale.US)
    val dirs = listOfNotNull(Path.of("build/reports/load-soak"), System.getProperty("loadSoak.report")?.let { Path.of(it) })
    for (dir in dirs) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$slug.json"), mapper.writeValueAsString(report) + "\n")
        Files.writeString(dir.resolve("$slug.md"), markdown)
    }
}

/** Throughput falling is worse. More than 15% worse fails; exactly 15% does not (PRF-013). */
internal fun regressionFails(baseline: Double, measured: Double, lowerIsBetter: Boolean): Boolean {
    val change = (measured - baseline) / abs(baseline) * 100.0
    val worse = if (lowerIsBetter) change else -change
    return worse > 15.0
}

internal fun awaitTrue(what: String, budgetMs: Long, state: () -> String = { "" }, check: () -> Boolean) {
    val deadline = System.nanoTime() + budgetMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (check()) return
        Thread.sleep(100)
    }
    throw AssertionError("timed out after $budgetMs ms waiting: $what; ${state()}")
}

internal fun docker(check: Boolean, vararg args: String): String {
    val proc = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
    val text = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    if (check && code != 0) error("docker ${args.joinToString(" ")} exited $code\n$text")
    return text
}

/**
 * A user-defined Docker bridge with containers on it. No host port is published: the test JVM dials each
 * container's bridge address, the way a gateway reaches a database or a device on its own host.
 */
internal class Bridge(prefix: String) : AutoCloseable {
    private val suffix = "${ProcessHandle.current().pid()}-${System.nanoTime().toString().takeLast(5)}"
    val network = "$prefix-$suffix"
    private val containers = ArrayList<String>()

    init {
        docker(true, "network", "create", network)
    }

    val driver: String get() = docker(true, "network", "inspect", "-f", "{{.Driver}}", network).trim()

    /** Start [image] as [alias] on this network and return the container name. */
    fun run(alias: String, image: String, env: Map<String, String> = emptyMap(), vararg command: String): String {
        val name = "$alias-$suffix"
        val args = arrayListOf("run", "-d", "--name", name, "--network", network, "--network-alias", alias)
        env.forEach { (k, v) -> args += listOf("-e", "$k=$v") }
        args += image
        args += command
        containers += name
        docker(true, *args.toTypedArray())
        return name
    }

    fun ip(container: String): String {
        val ip = docker(
            true, "inspect", "-f", "{{(index .NetworkSettings.Networks \"$network\").IPAddress}}", container,
        ).trim()
        val addr = InetAddress.getByName(ip)
        check(!addr.isLoopbackAddress && !addr.isAnyLocalAddress && !ip.startsWith("127.")) { "loopback address $ip" }
        return ip
    }

    fun awaitLog(container: String, marker: String, budgetMs: Long = 120_000) {
        var logs = ""
        awaitTrue("$container log '$marker'", budgetMs, { logs.takeLast(4_000) }) {
            logs = docker(false, "logs", container)
            logs.contains(marker)
        }
    }

    fun tail(container: String): String = docker(false, "logs", "--tail", "40", container)

    override fun close() {
        containers.asReversed().forEach { docker(false, "rm", "-f", it) }
        docker(false, "network", "rm", network)
    }

    companion object {
        /** Pinned in test-infra/compose.yaml; the same images the functional stack uses. */
        fun composeImage(service: String): String {
            val text = Files.readString(repoRoot().resolve("test-infra/compose.yaml"))
            val lines = text.lines()
            val start = lines.indexOfFirst { it.trimEnd() == "  $service:" }
            check(start >= 0) { "compose.yaml has no service $service" }
            val image = lines.drop(start + 1).takeWhile { it.startsWith("    ") || it.isBlank() }
                .firstOrNull { it.trim().startsWith("image:") }
                ?: error("compose.yaml service $service has no image")
            return image.substringAfter("image:").trim()
        }
    }
}

/** Process and VM counters, sampled every [sampleSeconds] (ADR 0010: CPU, OS memory, 1 s clock drift). */
internal class VmSampler(private val sampleSeconds: Int) : AutoCloseable {
    class Sample(
        val atSeconds: Double,
        val cpuPercent: Double,
        val memoryPercent: Double,
        val driftPercent: Double,
        val rssBytes: Long,
        val heapBytes: Long,
        val phase: String,
    )

    private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    val vcpu: Int = Runtime.getRuntime().availableProcessors()
    val ramBytes: Long = memTotalBytes()
    private val running = AtomicBoolean(true)
    private val samples = ArrayList<Sample>()
    private val drifts = ArrayList<Double>()
    @Volatile var phase: String = "warmup"
    private val t0 = System.nanoTime()

    /** The sizing guide's drift task: scheduled every 1 s; (actualIntervalMs - 1000) / 1000. */
    private val driftThread = Thread({
        var last = System.nanoTime()
        while (running.get()) {
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                break
            }
            val now = System.nanoTime()
            synchronized(drifts) { drifts += ((now - last) / 1_000_000.0 - 1_000.0) / 1_000.0 }
            last = now
        }
    }, "soak-drift").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }

    private val sampleThread = Thread({
        var cpu = os.processCpuTime
        var wall = System.nanoTime()
        while (running.get()) {
            try {
                Thread.sleep(sampleSeconds * 1_000L)
            } catch (_: InterruptedException) {
                break
            }
            val c = os.processCpuTime
            val w = System.nanoTime()
            val d = synchronized(drifts) { val m = if (drifts.isEmpty()) 0.0 else drifts.average(); drifts.clear(); m }
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
            val rss = rssBytes()
            val s = Sample(
                atSeconds = (w - t0) / 1e9,
                cpuPercent = (c - cpu).toDouble() / (w - wall).toDouble() / vcpu * 100.0,
                memoryPercent = rss.toDouble() / ramBytes * 100.0,
                driftPercent = d * 100.0,
                rssBytes = rss,
                heapBytes = heap,
                phase = phase,
            )
            synchronized(samples) { samples += s }
            println(
                "sample t=${"%.0f".format(s.atSeconds)}s phase=${s.phase} cpu=${"%.2f".format(s.cpuPercent)}% " +
                    "mem=${"%.2f".format(s.memoryPercent)}% drift=${"%.3f".format(s.driftPercent)}% heap=${heap shr 20}MiB",
            )
            cpu = c
            wall = w
        }
    }, "soak-sampler").apply { isDaemon = true }

    fun start(): VmSampler {
        driftThread.start()
        sampleThread.start()
        return this
    }

    fun samples(): List<Sample> = synchronized(samples) { samples.toList() }

    override fun close() {
        running.set(false)
        driftThread.interrupt()
        sampleThread.interrupt()
        driftThread.join(5_000)
        sampleThread.join(5_000)
    }

    companion object {
        fun rssBytes(): Long {
            val line = Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("VmRSS:") }
            return line.trim().split(Regex("\\s+"))[1].toLong() * 1024L
        }

        fun memTotalBytes(): Long {
            val line = Files.readAllLines(Path.of("/proc/meminfo")).first { it.startsWith("MemTotal:") }
            return line.trim().split(Regex("\\s+"))[1].toLong() * 1024L
        }
    }
}

/**
 * The ADR 0010 verdict over [samples] taken in the gating phases: the steady-window mean and every 60 s
 * mean (consecutive runs of `60 / sampleSeconds` samples) must stay at or under CPU 50, memory 50, and
 * |drift| 10. Exactly on a cutoff stays inside.
 */
internal class Cutoffs(samples: List<VmSampler.Sample>, sampleSeconds: Int, req: JsonNode) {
    private val cpuCut = req["cutoffs"]["cpuPercentGreaterThan"].asDouble()
    private val memCut = req["cutoffs"]["memoryPercentGreaterThan"].asDouble()
    private val driftCut = req["cutoffs"]["clockDriftPercentAbsGreaterThan"].asDouble()
    val count = samples.size
    val cpuMean = samples.map { it.cpuPercent }.averageOrZero()
    val memMean = samples.map { it.memoryPercent }.averageOrZero()
    val driftMean = samples.map { it.driftPercent }.averageOrZero()
    private val per = (60 / sampleSeconds).coerceAtLeast(1)
    private val windows = if (samples.size < per) listOf(samples) else (0..samples.size - per).map { samples.subList(it, it + per) }
    val cpuWorst60 = windows.maxOf { w -> w.map { it.cpuPercent }.averageOrZero() }
    val memWorst60 = windows.maxOf { w -> w.map { it.memoryPercent }.averageOrZero() }
    val driftWorst60 = windows.maxOf { w -> abs(w.map { it.driftPercent }.averageOrZero()) }
    val inside = count > 0 && cpuMean <= cpuCut && memMean <= memCut && abs(driftMean) <= driftCut &&
        cpuWorst60 <= cpuCut && memWorst60 <= memCut && driftWorst60 <= driftCut

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "samples" to count,
        "cpuPercentMean" to round4(cpuMean),
        "cpuPercentWorst60s" to round4(cpuWorst60),
        "memoryPercentMean" to round4(memMean),
        "memoryPercentWorst60s" to round4(memWorst60),
        "clockDriftPercentMean" to round4(driftMean),
        "clockDriftPercentWorst60s" to round4(driftWorst60),
        "insideCutoffs" to inside,
    )

    fun describe(): String =
        "cpu mean ${round4(cpuMean)} worst60 ${round4(cpuWorst60)}; mem mean ${round4(memMean)} worst60 ${round4(memWorst60)}; " +
            "drift mean ${round4(driftMean)} worst60 ${round4(driftWorst60)}"

    private companion object {
        fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()
    }
}

/** Every [scan] at its 1 s deadline (absolute, so one slow scan does not shift the rest). */
internal fun runScans(scans: Int, firstScan: Int, onLate: (Int, Double) -> Unit = { _, _ -> }, body: (Int) -> Unit) {
    var expected = System.nanoTime()
    for (k in 0 until scans) {
        val scan = firstScan + k
        val start = System.nanoTime()
        val late = (start - expected) / 1_000_000.0
        if (late > 1_000.0) onLate(scan, late)
        body(scan)
        expected += 1_000_000_000L
        val remaining = expected - System.nanoTime()
        if (remaining > 0) Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
    }
}

/** ADR 0010: "N% changing" updates `(index + scan) % 100 < percent` of the tags. */
internal fun changes(index: Int, scan: Int, percent: Int): Boolean = (index + scan) % 100 < percent

internal fun fmt(v: Double): String = "%,.0f".format(Locale.US, v)
