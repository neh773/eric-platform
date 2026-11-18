package org.oip.gateway

/**
 * A packaged gateway launcher with no arguments is given `open-admin` by jpackage
 * (`--arguments`). That opens the admin UI. The Windows service always passes
 * `--data-dir`, which starts the gateway even if `open-admin` is also present.
 */
internal data class Launch(
    val openAdmin: Boolean,
    val printOnly: Boolean,
    val adminUrl: String,
    val gatewayArgs: Array<String>,
)

internal const val DEFAULT_ADMIN_URL = "http://127.0.0.1:8090/admin"

internal fun classifyLaunch(argv: Array<String>, env: Map<String, String>): Launch {
    val openFlag = argv.contains("open-admin")
    val gatewayArgs = if (openFlag) {
        argv.filter { it != "open-admin" && it != "--print-only" }.toTypedArray()
    } else {
        argv
    }
    val hasDataDir = gatewayArgs.contains("--data-dir")
    val url = env["OIP_ADMIN_URL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_ADMIN_URL
    require(url.startsWith("http://") || url.startsWith("https://")) { "admin URL must be http or https" }
    val printOnly = argv.contains("--print-only") || env["OIP_ADMIN_PRINT_ONLY"] == "1"
    return Launch(
        openAdmin = openFlag && !hasDataDir,
        printOnly = printOnly,
        adminUrl = url,
        gatewayArgs = gatewayArgs,
    )
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
