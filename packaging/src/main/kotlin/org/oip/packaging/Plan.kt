package org.oip.packaging

import java.io.File

/**
 * Builds one jpackage image or installer. Arguments are a mode plus `--key value` pairs.
 * `jpackage` is invoked with a direct argument list so names with spaces stay one token.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "mode required" }
    val mode = args[0]
    val opt = HashMap<String, String>()
    var i = 1
    while (i < args.size) {
        val key = args[i]
        require(key.startsWith("--")) { "expected --key, got '$key'" }
        val value = args.getOrNull(++i) ?: error("$key needs a value")
        opt[key.removePrefix("--")] = value
        i++
    }
    fun need(name: String) = opt[name] ?: error("missing --$name")

    val layout = Layout(need("product"))
    val version = layout.appVersion(need("version"))
    val dest = File(need("dest"))
    dest.mkdirs()
    when (mode) {
        "gateway-image" -> jpackage(
            layout.gatewayImageArgs(version, need("input"), need("main-jar"), dest.absolutePath),
            dest, layout.gatewayName, File(need("launcher-path-file")),
        )
        "designer-image" -> jpackage(
            layout.designerImageArgs(version, need("input"), need("main-jar"), dest.absolutePath),
            dest, layout.designerName, File(need("launcher-path-file")),
        )
        "gateway-exe" -> {
            requireWindows()
            val resourceDir = File(need("resource-dir"))
            resourceDir.mkdirs()
            require(resourceDir.resolve("service-installer.exe").isFile) {
                "missing ${resourceDir.resolve("service-installer.exe")} (NSSM, public domain)"
            }
            resourceDir.resolve("${layout.gatewayName}-service-config.wxi").writeText(layout.serviceConfigWxi())
            File(need("layout-file")).apply { parentFile.mkdirs(); writeText(layout.layoutProperties(version)) }
            jpackage(
                layout.gatewayExeArgs(
                    version, need("input"), need("main-jar"), dest.absolutePath,
                    resourceDir.absolutePath, need("upgrade-uuid"),
                ),
                dest, layout.gatewayName, null,
            )
        }
        "designer-exe" -> {
            requireWindows()
            jpackage(
                layout.designerExeArgs(
                    version, need("input"), need("main-jar"), dest.absolutePath, need("upgrade-uuid"),
                    need("resource-dir"),
                ),
                dest, layout.designerName, null,
            )
        }
        else -> error("unknown mode '$mode'")
    }
}

private fun requireWindows() {
    require(System.getProperty("os.name").lowercase().contains("windows")) {
        "jpackage builds exe installers only on Windows"
    }
}

private fun jpackage(args: List<String>, dest: File, name: String, launcherPathFile: File?) {
    val image = dest.resolve(name)
    if (args.getOrNull(1) == "app-image" && image.exists()) image.deleteRecursively()
    if (args.getOrNull(1) == "exe") {
        dest.listFiles()?.filter { it.isFile && it.name.endsWith(".exe") && it.name.startsWith(name) }?.forEach { it.delete() }
    }
    val command = listOf("jpackage") + args
    val code = ProcessBuilder(command).inheritIO().start().waitFor()
    if (code != 0) error("jpackage exited $code")
    if (launcherPathFile != null) {
        val launcher = launcherFile(dest, name)
        require(launcher.isFile) { "launcher not found at $launcher" }
        launcherPathFile.parentFile.mkdirs()
        launcherPathFile.writeText(launcher.absolutePath)
    }
}
