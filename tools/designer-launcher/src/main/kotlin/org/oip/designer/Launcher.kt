package org.oip.designer

/**
 * Desktop entry point for the web designer (ADR 0004, ADR 0013).
 * The gateway does not serve the designer yet. This process opens the system
 * browser at `{gateway}/designer`. Default gateway: http://127.0.0.1:8090.
 */
const val DEFAULT_GATEWAY = "http://127.0.0.1:8090"

data class LauncherRequest(val url: String, val printOnly: Boolean)

fun designerUrl(gateway: String): String {
    val trimmed = gateway.trim().trimEnd('/')
    require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        "gateway URL must start with http:// or https://"
    }
    return if (trimmed.endsWith("/designer")) trimmed else "$trimmed/designer"
}

fun parseLauncherArgs(argv: Array<String>, env: Map<String, String> = System.getenv()): LauncherRequest {
    var gateway = env["OIP_GATEWAY_URL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_GATEWAY
    var printOnly = env["OIP_DESIGNER_PRINT_ONLY"] == "1"
    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--gateway" -> gateway = argv.getOrNull(++i) ?: throw IllegalArgumentException("--gateway needs a value")
            "--print-only" -> printOnly = true
            else -> throw IllegalArgumentException("unknown argument '${argv[i]}' (usage: --gateway URL [--print-only])")
        }
        i++
    }
    return LauncherRequest(designerUrl(gateway), printOnly)
}

internal fun openInBrowser(url: String) {
    val os = System.getProperty("os.name").lowercase()
    val command = when {
        os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        os.contains("mac") -> listOf("open", url)
        else -> listOf("xdg-open", url)
    }
    ProcessBuilder(command).inheritIO().start()
}

fun main(argv: Array<String>) {
    val request = parseLauncherArgs(argv)
    println(request.url)
    if (!request.printOnly) openInBrowser(request.url)
}
