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
    val launch = classifyLaunch(argv, System.getenv())
    if (launch.openAdmin) {
        println(launch.adminUrl)
        if (!launch.printOnly) openInBrowser(launch.adminUrl)
        return
    }
    val a = parseArgs(launch.gatewayArgs)
    val g = Gateway(a.dataDir, a.port).start()
    Runtime.getRuntime().addShutdownHook(Thread { g.close() })
    println("ERIC gateway (${g.settings.profile}) listening on ${g.clientUrl()}")
    g.httpsClientUrl()?.let { println("ERIC gateway HTTPS on $it") }
    if (AdminUi.locate() != null) println("ERIC admin UI at ${g.clientUrl()}/admin")
    if (g.security.users.list().isEmpty()) {
        println("ERIC gateway has no users. Set ERIC_ADMIN_PASSWORD to seed an administrator on first start.")
    }
    g.insecureCookieWarning?.let { println("ERIC gateway warning: $it") }
}
