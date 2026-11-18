package org.oip.cli

import org.oip.gateway.AdminPassword
import org.oip.gateway.SecretKeys
import org.oip.gateway.Gateway
import org.oip.gateway.GatewayControl
import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Process control and offline admin recovery.
 *
 * the reference platform starts and stops the gateway service from the install directory, and `gwcmd -r`
 * restarts it. `gwcmd -p` is the documented password recovery
 * (-).
 * `oipctl start` launches the gateway JVM, `stop` signals the pid in `gateway.pid`, and
 * `reset-admin` changes the internal user password only while that process is stopped.
 */
internal class GatewayCommands(
    private val out: PrintStream,
    private val err: PrintStream,
    private val env: Map<String, String>,
    private val stdin: BufferedReader?,
    private val readSecret: (Map<String, String>, String, String) -> CharArray,
) {
    fun start(args: List<String>): Int {
        val (pos, flags) = parseArgs(args, setOf("data-dir", "port"), setOf("foreground"))
        if (pos.isNotEmpty()) throw IllegalArgumentException("start does not take positional arguments")
        val dir = dataDir(flags)
        val port = flags["port"]
        if (flags.containsKey("foreground")) return foreground(dir, port)
        return spawn(dir, port)
    }

    fun stop(args: List<String>): Int {
        val (pos, flags) = parseArgs(args, setOf("data-dir"))
        if (pos.isNotEmpty()) throw IllegalArgumentException("stop does not take positional arguments")
        return stopDir(dataDir(flags))
    }

    fun restart(args: List<String>): Int {
        val (pos, flags) = parseArgs(args, setOf("data-dir", "port"))
        if (pos.isNotEmpty()) throw IllegalArgumentException("restart does not take positional arguments")
        val dir = dataDir(flags)
        val port = flags["port"] ?: portOf(GatewayControl.readUrl(dir))
        val pid = GatewayControl.readPid(dir)
        val alive = pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        if (alive) {
            val code = stopDir(dir)
            if (code != 0) return code
        }
        return spawn(dir, port)
    }

    fun resetAdmin(args: List<String>): Int {
        val (pos, flags) = parseArgs(args, setOf("data-dir", "user", "password-file"))
        if (pos.isNotEmpty()) throw IllegalArgumentException("reset-admin does not take a password on the command line")
        val dir = dataDir(flags)
        val user = flags["user"] ?: "admin"
        val password = readSecret(flags, "password-file", "New password for $user: ")
        val message = AdminPassword.reset(dir, user, password)
        out.println(message)
        return 0
    }

    /** `rotate-secret-keys [--data-dir DIR] [--only root|kek]`: re-wrap stored secrets under new keys (SEC-031). */
    fun rotateSecretKeys(args: List<String>): Int {
        val (pos, flags) = parseArgs(args, setOf("data-dir", "only"))
        if (pos.isNotEmpty()) throw IllegalArgumentException("rotate-secret-keys takes no arguments besides --data-dir and --only")
        val only = flags["only"]
        if (only != null && only != "root" && only != "kek") throw IllegalArgumentException("--only must be root or kek")
        val result = SecretKeys.rotate(dataDir(flags), rotateRoot = only != "kek", rotateKek = only != "root")
        out.println("rotated secret keys: ${result.secrets} secrets re-wrapped (root key version ${result.rootVersion}, key-encryption key version ${result.kekVersion})")
        if (result.migratedFromLegacy > 0) out.println("${result.migratedFromLegacy} secrets moved from the gateway key to per-secret data keys")
        return 0
    }

    private fun foreground(dir: Path, port: String?): Int {
        val gateway = Gateway(dir, port?.toInt())
        val hook = Thread { runCatching { gateway.close() } }
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            gateway.start()
            Thread.currentThread().join()
            return 0
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            gateway.close()
        }
    }

    private fun spawn(dir: Path, port: String?): Int {
        val pid = GatewayControl.readPid(dir)
        val existing = pid?.let { ProcessHandle.of(it).orElse(null) }
        if (existing != null && existing.isAlive && isGatewayProcess(existing)) {
            err.println("gateway is already running")
            GatewayControl.readUrl(dir)?.let { err.println(it) }
            return 1
        }
        val probe = GatewayControl(dir)
        var acquired = probe.tryAcquire()
        if (!acquired) {
            // A process that just exited can still hold the lock for a moment, especially after a forced stop.
            val recorded = GatewayControl.readPid(dir)?.let { ProcessHandle.of(it).orElse(null) }
            if (recorded == null || !recorded.isAlive) {
                repeat(20) {
                    Thread.sleep(100)
                    if (probe.tryAcquire()) {
                        acquired = true
                        return@repeat
                    }
                }
            }
        }
        if (!acquired) {
            err.println("gateway is already running")
            return 1
        }
        probe.release()
        Files.createDirectories(dir.resolve("logs"))
        Files.deleteIfExists(dir.resolve(GatewayControl.URL_FILE))
        val log = dir.resolve("logs").resolve("oipctl-gateway.log")
        val argFile = Files.createTempFile("oipctl-", ".args")
        argFile.toFile().deleteOnExit()
        val argv = mutableListOf(
            "-cp",
            System.getProperty("java.class.path"),
            "org.oip.cli.MainKt",
            "start",
            "--foreground",
            "--data-dir",
            dir.toString(),
        )
        if (port != null) {
            argv += "--port"
            argv += port
        }
        Files.writeString(argFile, argv.joinToString("\n") { quoteArg(it) })
        val javaBin = Path.of(System.getProperty("java.home"), "bin")
        val java = listOf(javaBin.resolve("java.exe"), javaBin.resolve("java")).first { Files.exists(it) }.toString()
        // The marker stays on the real argv. The classpath is inside the argfile, so `ps` does not show MainKt.
        val proc = ProcessBuilder(java, "-Doip.gateway.control=true", "@$argFile")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .start()
        val httpFile = dir.resolve(GatewayControl.URL_FILE)
        val deadline = System.nanoTime() + 90_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive) {
                err.println("gateway exited before listening")
                err.println(tail(log))
                return 1
            }
            if (httpFile.exists()) {
                val url = httpFile.readText().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    out.println(url)
                    return 0
                }
            }
            Thread.sleep(50)
        }
        proc.destroy()
        err.println("timed out waiting for the gateway to listen")
        err.println(tail(log))
        return 1
    }

    private fun stopDir(dir: Path): Int {
        val pid = GatewayControl.readPid(dir)
        if (pid == null) {
            err.println("gateway is not running")
            return 1
        }
        val handle = ProcessHandle.of(pid).orElse(null)
        if (handle == null || !handle.isAlive) {
            GatewayControl.clearProcessFiles(dir, pid)
            err.println("gateway is not running")
            return 1
        }
        if (!isGatewayProcess(handle)) {
            err.println("pid $pid is not an OIP gateway process; refusing to stop it")
            return 1
        }
        if (!handle.destroy()) {
            err.println("could not signal gateway pid $pid")
            return 1
        }
        if (!waitUntilDead(handle, 20_000)) handle.destroyForcibly()
        if (!waitUntilDead(handle, 5_000)) {
            err.println("gateway pid $pid did not exit")
            return 1
        }
        GatewayControl.clearProcessFiles(dir, pid)
        out.println("stopped")
        return 0
    }

    private fun waitUntilDead(handle: ProcessHandle, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (handle.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
        return !handle.isAlive
    }

    private fun dataDir(flags: Map<String, String>): Path {
        val raw = flags["data-dir"] ?: env["OIP_DATA_DIR"] ?: "data"
        return Path.of(raw).toAbsolutePath().normalize()
    }

    private fun portOf(url: String?): String? {
        if (url == null) return null
        val port = url.substringAfterLast(':', "")
        return port.toIntOrNull()?.toString()
    }

    private fun tail(log: Path): String {
        if (!log.exists()) return ""
        return log.readText().lines().takeLast(40).joinToString("\n")
    }

    private fun isGatewayProcess(handle: ProcessHandle): Boolean {
        val args = handle.info().arguments().orElse(null)
        val cmd = handle.info().command().orElse("")
        // Windows often cannot read another process's arguments. The pid file already names this process.
        if (args == null || args.isEmpty()) {
            val name = cmd.replace('\\', '/').substringAfterLast('/')
            return name.isEmpty() || name.equals("java", ignoreCase = true) || name.equals("java.exe", ignoreCase = true) ||
                name.contains("server")
        }
        val text = (listOf(cmd) + args).joinToString(" ")
        return text.contains("oip.gateway.control=true") ||
            text.contains("org.oip.cli.MainKt") ||
            text.contains("org.oip.gateway.MainKt") ||
            text.contains("/bin/server") ||
            text.contains("\\bin\\server")
    }

    private fun quoteArg(s: String): String {
        if (s.none { it.isWhitespace() || it == '"' || it == '\\' }) return s
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun parseArgs(args: List<String>, valued: Set<String>, switches: Set<String> = emptySet()): Pair<List<String>, Map<String, String>> {
        val pos = mutableListOf<String>()
        val flags = linkedMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--") {
                pos += args.subList(i + 1, args.size)
                break
            }
            if (a.startsWith("-")) {
                val raw = if (a.startsWith("--")) a.removePrefix("--") else a.removePrefix("-")
                val name = when (raw) {
                    "o" -> "output"
                    "f" -> "file"
                    else -> raw
                }
                if (name in switches) flags[name] = "true"
                else if (name !in valued) throw IllegalArgumentException("unknown flag $a")
                else flags[name] = args.getOrNull(++i) ?: throw IllegalArgumentException("$a needs a value")
            } else pos += a
            i++
        }
        return pos to flags
    }
}
