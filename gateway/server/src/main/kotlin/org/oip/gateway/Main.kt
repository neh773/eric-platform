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
        if (!launch.printOnly) openInBrowser(launch.adminUrl)
        return
    }
    runGateway(parseArgs(launch.gatewayArgs, LegacyEnv.current))
}

private fun runGateway(a: Args) {
    val g = Gateway(a.dataDir, a.port).start()
    Runtime.getRuntime().addShutdownHook(Thread { g.close() })
    println("ERIC gateway (${g.settings.profile}) listening on ${g.clientUrl()}")
    g.httpsClientUrl()?.let { println("ERIC gateway HTTPS on $it") }
    if (AdminUi.locate() != null) println("ERIC admin UI at ${g.clientUrl()}/admin")
    if (g.security.users.list().isEmpty()) {
        println("ERIC gateway has no users yet. Open ${g.clientUrl()}/admin in a browser on this machine to create the administrator, or set ERIC_ADMIN_PASSWORD before the first start.")
    }
    g.insecureCookieWarning?.let { println("ERIC gateway warning: $it") }
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
