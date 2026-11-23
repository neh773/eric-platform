package org.oip.gateway

import org.oip.tags.TagEngine
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Runs the bundled `eric-plc` scan engine for the gateway (0.1.1). A deploy from Control Canvas writes the engine's
 * config under `logic/run/`, starts the engine with the compiled program, and opens the tag bridge with the same
 * mapping. Before this, a default install never started an engine: Logic showed "running" with nothing scanning.
 *
 * The last deployed program is remembered in `logic/run/deployed.json` and started again with the gateway.
 */
internal class ManagedPlc(
    private val runDir: Path,
    private val binary: File,
    private val tags: TagEngine,
    private var periodMs: Int = DEFAULT_PLC_PERIOD_MS,
    private var maxConsecutiveOverruns: Int = DEFAULT_PLC_MAX_CONSECUTIVE_OVERRUNS,
    private val log: (String) -> Unit = { System.err.println("eric-plc: $it") },
) : AutoCloseable {
    private var process: Process? = null
    private var lastExitError: String? = null
    private var bridge: PlcBridge? = null
    @Volatile var control: PlcControl = PlcControl.disconnected()
        private set

    data class Deployed(
        val library: String,
        val entry: String,
        val instance: String,
        val hwmap: String?,
        val running: Boolean,
        val periodMs: Int = DEFAULT_PLC_PERIOD_MS,
        val maxConsecutiveOverruns: Int = DEFAULT_PLC_MAX_CONSECUTIVE_OVERRUNS,
    )

    /** Stops any engine, then starts one with [library]. Returns an error message, or null when it is scanning. */
    @Synchronized
    fun start(
        library: Path,
        entry: String,
        instance: String,
        hwmap: Path?,
        mapping: Map<String, Any?>,
        running: Boolean = true,
        periodMs: Int = this.periodMs,
        maxConsecutiveOverruns: Int = this.maxConsecutiveOverruns,
    ): String? {
        val scanPeriodMs = periodMs.coerceIn(1, 60_000)
        val scanOverruns = maxConsecutiveOverruns.coerceAtLeast(0)
        this.periodMs = scanPeriodMs
        this.maxConsecutiveOverruns = scanOverruns
        stopEngine()
        Files.createDirectories(runDir)
        Files.deleteIfExists(runDir.resolve("status.json"))
        val inputs = rows(mapping["inputs"])
        val outputs = rows(mapping["outputs"])
        if (inputs.isEmpty() && outputs.isEmpty()) {
            return "map at least one program variable to a tag (Tag mapping, or a UDT instance) before deploying"
        }
        val mappingFile = runDir.resolve("mapping.json")
        mappingFile.writeText(Json.write(mapOf("inputs" to inputs, "outputs" to outputs)))
        val image = runDir.resolve("image.shm")
        Files.deleteIfExists(image)
        val socket = socketPath()
        if (!socket.startsWith("\\\\")) Files.deleteIfExists(Path.of(socket))
        val config = runDir.resolve("plc.toml")
        config.writeText(
            buildString {
                appendLine("period_ms = $scanPeriodMs")
                appendLine("fifo_priority = 0")
                appendLine("mlock = false")
                appendLine("max_consecutive_overruns = $scanOverruns")
                appendLine("bridge_timeout_ms = ${maxOf(500, scanPeriodMs * 20)}")
                appendLine("shm_path = ${toml(image.toString())}")
                appendLine("program = ${toml(library.toAbsolutePath().toString())}")
                appendLine("entry = ${toml(entry)}")
                appendLine("instance = ${toml(instance)}")
                if (hwmap != null && hwmap.exists()) appendLine("hwmap = ${toml(hwmap.toAbsolutePath().toString())}")
                appendLine("mapping = ${toml(mappingFile.toString())}")
                appendLine("status_path = ${toml(runDir.resolve("status.json").toString())}")
                appendLine("control_socket = ${toml(socket)}")
                appendLine("label = \"ERIC Gateway\"")
            },
        )
        val logFile = runDir.resolve("engine.log").toFile()
        val started = try {
            ProcessBuilder(binary.absolutePath, "run", "--config", config.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .start()
        } catch (e: Exception) {
            return "the scan engine could not start: ${e.message}"
        }
        process = started
        lastExitError = null
        val candidate = UnixPlcControl(socket)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!started.isAlive) break
            if (candidate.status() != null) break
            Thread.sleep(50)
        }
        if (!started.isAlive || candidate.status() == null) {
            stopEngine()
            val tail = runCatching { logFile.readLines().takeLast(5).joinToString(" ") }.getOrDefault("")
            return engineStartError(tail)
        }
        bridge = try {
            PlcBridge.open(
                tags,
                PlcSettings(enabled = true, image = image.toString(), mapping = mappingFile.toString(), publishMillis = 10, bridgeTimeoutMillis = maxOf(500L, scanPeriodMs * 20L)),
                log,
            ).also { it.start() }
        } catch (e: Exception) {
            stopEngine()
            return "the tag bridge could not open: ${e.message}"
        }
        control = candidate
        if (!running) candidate.stop()
        remember(Deployed(library.toAbsolutePath().toString(), entry, instance, hwmap?.toAbsolutePath()?.toString(), running, scanPeriodMs, scanOverruns))
        return null
    }

    /** Records whether the engine should be scanning after a gateway restart. */
    fun rememberRunning(running: Boolean) {
        val current = deployed() ?: return
        remember(current.copy(running = running))
    }

    fun deployed(): Deployed? {
        val file = runDir.resolve("deployed.json")
        if (!file.exists()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val m = Json.mapper.readValue(file.readText(), Map::class.java) as Map<String, Any?>
            Deployed(
                m["library"] as String,
                m["entry"] as String,
                m["instance"] as String,
                m["hwmap"] as String?,
                m["running"] != false,
                intSetting(m["periodMs"], DEFAULT_PLC_PERIOD_MS, 1, 60_000),
                intSetting(m["maxConsecutiveOverruns"], DEFAULT_PLC_MAX_CONSECUTIVE_OVERRUNS, 0, 1_000_000),
            )
        }.getOrNull()
    }

    /** True while the supervised engine process is still alive. */
    fun alive(): Boolean = process?.isAlive == true

    /** Last lines of the engine log after an unexpected exit, or null while it is running. */
    fun exitError(): String? {
        val running = process ?: return lastExitError
        if (running.isAlive) return null
        val tail = runCatching { runDir.resolve("engine.log").toFile().readLines().takeLast(8).joinToString(" ") }.getOrDefault("").trim()
        val code = runCatching { running.exitValue() }.getOrNull()
        lastExitError = engineStartError(tail + if (code != null && tail.isBlank()) "exit code $code" else "")
        return lastExitError
    }

    /** True when the engine has written a watchdog latch into `status.json`. */
    fun watchdogTripped(): Boolean {
        val file = runDir.resolve("status.json")
        if (!file.exists()) return false
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val m = Json.mapper.readValue(file.readText(), Map::class.java) as Map<String, Any?>
            m["watchdog_tripped"] == true
        }.getOrDefault(false)
    }

    /** Stops a dead or latched engine and starts the last deployed program again. */
    fun relaunch(periodMs: Int = this.periodMs, maxConsecutiveOverruns: Int = this.maxConsecutiveOverruns): String? {
        val last = deployed() ?: return "deploy a program before starting"
        val mappingFile = runDir.resolve("mapping.json")
        if (!mappingFile.exists()) return "deploy a program before starting"
        @Suppress("UNCHECKED_CAST")
        val mapping = Json.mapper.readValue(mappingFile.readText(), Map::class.java) as Map<String, Any?>
        return start(Path.of(last.library), last.entry, last.instance, last.hwmap?.let { Path.of(it) }, mapping, true, periodMs, maxConsecutiveOverruns)
    }

    /** Starts the last deployed program again, with the mapping it was deployed with. */
    fun resume(): String? {
        val last = deployed() ?: return null
        if (!File(last.library).isFile) return "the deployed program ${last.library} is missing; deploy it again"
        val mappingFile = runDir.resolve("mapping.json")
        if (!mappingFile.exists()) return null
        @Suppress("UNCHECKED_CAST")
        val mapping = Json.mapper.readValue(mappingFile.readText(), Map::class.java) as Map<String, Any?>
        return start(Path.of(last.library), last.entry, last.instance, last.hwmap?.let { Path.of(it) }, mapping, last.running, last.periodMs, last.maxConsecutiveOverruns)
    }

    private fun remember(state: Deployed) {
        runDir.resolve("deployed.json").writeText(
            Json.write(mapOf(
                "library" to state.library,
                "entry" to state.entry,
                "instance" to state.instance,
                "hwmap" to state.hwmap,
                "running" to state.running,
                "periodMs" to state.periodMs,
                "maxConsecutiveOverruns" to state.maxConsecutiveOverruns,
            )),
        )
    }

    @Synchronized
    private fun stopEngine() {
        control = PlcControl.disconnected()
        runCatching { bridge?.close() }
        bridge = null
        val running = process ?: return
        process = null
        running.destroy()
        if (!running.waitFor(3, TimeUnit.SECONDS)) {
            running.destroyForcibly()
            running.waitFor(3, TimeUnit.SECONDS)
        }
    }

    override fun close() = stopEngine()

    private fun socketPath(): String {
        val windows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
        val tag = Integer.toHexString(runDir.toAbsolutePath().toString().hashCode())
        if (windows) return "\\\\.\\pipe\\eric-plc-$tag"
        val local = runDir.resolve("control.sock").toAbsolutePath().toString()
        // A Unix socket path has to fit in 108 bytes.
        return if (local.toByteArray().size < 100) local else Path.of(System.getProperty("java.io.tmpdir"), "eric-plc-$tag.sock").toString()
    }

    private fun rows(raw: Any?): List<Map<String, Any?>> =
        (raw as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { row ->
            val type = (row["type"] as? String)?.ifBlank { null } ?: "BOOL"
            linkedMapOf("address" to row["address"], "tag" to row["tag"], "type" to type, "safe" to typedSafe(type, row["safe"]))
        }

    private fun toml(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** `ERIC_PLC_ENGINE`, or `eric-plc` in the install's `plc` folder. Null when neither exists. */
        fun findEngine(env: Map<String, String> = LegacyEnv.current): File? {
            env["OIP_PLC_ENGINE"]?.takeIf { it.isNotBlank() }?.let { return File(it).takeIf(File::isFile) }
            val name = if (System.getProperty("os.name").orEmpty().contains("win", true)) "eric-plc.exe" else "eric-plc"
            return Bundled.dir("plc")?.resolve(name)?.toFile()?.takeIf { it.isFile }
        }
    }
}

private val MISSING_ADDRESS = Regex("""hwmap has no (%[IQM][XBWDL][0-9.]+)""")

/**
 * The deploy error for an engine that did not start. A tag map row whose address the program never declares gets a
 * sentence that says what to change, instead of the engine's own wording.
 */
internal fun engineStartError(tail: String): String {
    val missing = MISSING_ADDRESS.find(tail)?.groupValues?.get(1)
    if (missing != null) {
        return "the Tag mapping uses $missing, but no variable in the program is at that address. " +
            "Declare one with AT, for example `count AT $missing : DINT;`, or change the address in Tag mapping"
    }
    return "the scan engine did not start${if (tail.isBlank()) "" else ": $tail"}"
}
