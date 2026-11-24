package org.oip.gateway

import java.nio.file.Path

data class Args(val dataDir: Path, val port: Int?)

fun parseArgs(argv: Array<String>, env: Map<String, String> = System.getenv()): Args {
    var dir: Path = Path.of(env["OIP_DATA_DIR"] ?: "data"); var port: Int? = null
    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--data-dir" -> dir = Path.of(argv.getOrNull(++i) ?: throw IllegalArgumentException("--data-dir needs a value"))
            "--port" -> port = argv.getOrNull(++i)?.toIntOrNull() ?: throw IllegalArgumentException("--port needs a number")
            else -> throw IllegalArgumentException("unknown argument '${argv[i]}' (usage: --data-dir DIR [--port N])")
        }
        i++
    }
    return Args(dir, port)
}

fun main(argv: Array<String>) {
    val launch = classifyLaunch(argv, LegacyEnv.current)
    if (launch.openAdmin) {
        // 0.1.1: the Linux app image has no service to start the gateway, so its launcher starts one when none answers.
        if (!launch.printOnly && !isWindows() && !gatewayAnswers(launch.adminUrl)) {
            val dir = desktopDataDir(LegacyEnv.current)
            println("Starting the ERIC gateway. Data directory: ${dir.toAbsolutePath()}")
            runGateway(Args(dir, null))
            runCatching { openInBrowser(launch.adminUrl) }
            return
        }
        println(launch.adminUrl)
        if (!launch.printOnly && isWindows() && !waitForGateway(launch.adminUrl, WINDOWS_START_WAIT_MS)) {
            // The Windows service did not answer: say what to check instead of opening a browser on an error page.
            println(serviceNotAnswering(System.getProperty("jpackage.app-path"), launch.adminUrl))
            pauseForEnter()
            return
        }
        if (!launch.printOnly) openInBrowser(launch.adminUrl)
        return
    }
    runGateway(parseArgs(launch.gatewayArgs, LegacyEnv.current))
}

private const val WINDOWS_START_WAIT_MS = 90_000L

private fun waitForGateway(adminUrl: String, waitMillis: Long): Boolean {
    if (gatewayAnswers(adminUrl)) return true
    println("Waiting for the ERIC Gateway service to answer (first start can take a minute)...")
    val until = System.currentTimeMillis() + waitMillis
    while (System.currentTimeMillis() < until) {
        Thread.sleep(2_000)
        if (gatewayAnswers(adminUrl)) return true
    }
    return false
}

/** What a user should check when the Windows service does not answer. [launcherPath] is the installed launcher exe. */
internal fun serviceNotAnswering(launcherPath: String?, adminUrl: String): String {
    val installDir = launcherPath?.let { java.io.File(it).parentFile?.absolutePath } ?: "C:\\Program Files\\ERIC Gateway"
    return """
        |The ERIC Gateway service is not answering at $adminUrl.
        |
        |Check these, in order:
        |  1. Services (services.msc): "ERIC Gateway" should be Running. If it is Stopped, select it and press Start.
        |  2. Why startup stopped: C:\ProgramData\ERIC\gateway\logs\startup.log
        |     and the service log: $installDir\service-stdout.log
        |     The last lines say why it stopped, for example a port another program already uses.
        |  3. The gateway logs: C:\ProgramData\ERIC\gateway\logs
        |  4. Run the install check in PowerShell as administrator:
        |     powershell -ExecutionPolicy Bypass -File "$installDir\app\check-install.ps1"
        |
        |Then open this shortcut again. Troubleshooting: https://neh773.github.io/eric-platform/ (Install on Windows).
    """.trimMargin()
}

private fun pauseForEnter() {
    if (System.console() == null) return
    println()
    println("Press Enter to close this window.")
    runCatching { readlnOrNull() }
}

/** A port another program holds, in plain words, or null when [error] is something else. */
internal fun portInUseMessage(error: Throwable, dataDir: Path): String? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is java.net.BindException || cause.message?.contains("Address already in use", ignoreCase = true) == true) {
            return "ERIC gateway could not start: a port it needs is already used by another program (${cause.message}). " +
                "Stop the other program, or choose another port in ${dataDir.resolve("gateway.yaml")} (http: port:), then start the gateway again."
        }
        cause = cause.cause
    }
    return null
}

private const val STARTUP_DUMP_MS = 120_000L
private const val STARTUP_LIMIT_MS = 600_000L

private fun runGateway(a: Args) {
    StartupLog.open(a.dataDir)
    val started = java.util.concurrent.CountDownLatch(1)
    // A start that hangs must not leave a half-started process with no web port and no message.
    Thread({
        if (started.await(STARTUP_DUMP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) return@Thread
        StartupLog.threadDump("Startup is still at '${StartupLog.lastStage}' after ${STARTUP_DUMP_MS / 1000} s")
        if (started.await(STARTUP_LIMIT_MS - STARTUP_DUMP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) return@Thread
        val text = "ERIC gateway did not finish starting in ${STARTUP_LIMIT_MS / 60_000} minutes (stuck at '${StartupLog.lastStage}'). See ${StartupLog.path()}"
        StartupLog.threadDump(text)
        System.err.println(text)
        Runtime.getRuntime().halt(1)
    }, "startup-watchdog").apply { isDaemon = true }.start()
    val g = try {
        Gateway(a.dataDir, a.port).start()
    } catch (e: Throwable) {
        StartupLog.failure("startup at '${StartupLog.lastStage}'", e)
        val plain = portInUseMessage(e, a.dataDir)
            ?: "ERIC gateway could not start (${e.javaClass.simpleName}: ${e.message}) at '${StartupLog.lastStage}'. Details: ${StartupLog.path()}"
        System.err.println(plain)
        e.printStackTrace()
        // Exit even when threads that already started would keep the JVM alive: the service then shows as stopped.
        Runtime.getRuntime().halt(1)
        return
    }
    started.countDown()
    g.startupProblems.toList().forEach { println("ERIC gateway warning: $it (see ${StartupLog.path()})") }
    Runtime.getRuntime().addShutdownHook(Thread { g.close() })
    println("ERIC gateway (${g.settings.profile}) listening on ${g.clientUrl()}")
    g.httpsClientUrl()?.let { println("ERIC gateway HTTPS on $it") }
    if (AdminUi.locate() != null) println("ERIC admin UI at ${g.clientUrl()}/admin")
    if (g.security.users.list().isEmpty()) {
        println("ERIC gateway has no users yet. Open ${g.clientUrl()}/admin in a browser on this machine to create the administrator, or set ERIC_ADMIN_PASSWORD before the first start.")
    }
    if (g.settings.plainHttpOnly()) {
        println("ERIC gateway note: plain HTTP. That is fine on this computer; turn on HTTPS (admin > Network) before other computers sign in.")
    } else {
        g.insecureCookieWarning?.let { println("ERIC gateway warning: $it") }
    }
}

internal fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

/** `ERIC_DATA_DIR`, else `$XDG_DATA_HOME/eric/data`, else `$HOME/.local/share/eric/data`. */
internal fun desktopDataDir(env: Map<String, String>): Path {
    env["OIP_DATA_DIR"]?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
    // $HOME first: Java's user.home comes from the password database and ignores a changed HOME.
    val home = env["HOME"]?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
    val base = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: Path.of(home, ".local", "share")
    return base.resolve("eric").resolve("data")
}

private fun gatewayAnswers(adminUrl: String): Boolean = try {
    val uri = java.net.URI(adminUrl)
    val health = java.net.URI(uri.scheme, null, uri.host, uri.port, "/api/v1/health", null, null).toURL()
    val conn = health.openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 1_000
    conn.readTimeout = 2_000
    try {
        conn.responseCode in 200..499
    } finally {
        conn.disconnect()
    }
} catch (_: Exception) {
    false
}
