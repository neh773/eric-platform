import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

val productName = providers.gradleProperty("oip.productName").orElse("Open Industrial Platform")
val gatewayUpgradeUuid = providers.gradleProperty("oip.gatewayUpgradeUuid")
val designerUpgradeUuid = providers.gradleProperty("oip.designerUpgradeUuid")

val nssmUrl = "https://nssm.cc/release/nssm-2.24.zip"
val nssmSha256 = "727d1e42275c605e0f04aba98095c38a8e1e46def453cdffce42869428aa6743"

val serverLib = project(":gateway:server").layout.buildDirectory.dir("install/server/lib")
val designerLib = project(":tools:designer-launcher").layout.buildDirectory.dir("install/designer-launcher/lib")
val imageDest = layout.buildDirectory.dir("image")
val exeDest = layout.buildDirectory.dir("dist")
val resourceDir = layout.buildDirectory.dir("windows-resources")
val nssmZip = layout.buildDirectory.file("nssm/nssm-2.24.zip")
val layoutFile = layout.buildDirectory.file("layout.properties")
val gatewayLauncherPath = layout.buildDirectory.file("gateway-launcher.path")
val designerLauncherPath = layout.buildDirectory.file("designer-launcher.path")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun windows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

tasks.named<Test>("test") {
    systemProperty("oip.gradleProperties", rootProject.file("gradle.properties").absolutePath)
}

tasks.register("fetchNssm") {
    group = "distribution"
    description = "Download NSSM 2.24 (public domain) as service-installer.exe for jpackage."
    outputs.file(resourceDir.map { it.file("service-installer.exe") })
    doLast {
        if (!windows()) throw GradleException("NSSM is only fetched when building Windows installers.")
        val zip = nssmZip.get().asFile
        zip.parentFile.mkdirs()
        if (!zip.isFile || sha256(zip) != nssmSha256) {
            downloadUrl(nssmUrl, zip)
        }
        if (sha256(zip) != nssmSha256) throw GradleException("NSSM checksum mismatch for $nssmUrl")
        val dest = resourceDir.get().file("service-installer.exe").asFile
        dest.parentFile.mkdirs()
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry("nssm-2.24/win64/nssm.exe")
                ?: throw GradleException("nssm-2.24/win64/nssm.exe not in the zip")
            archive.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
    }
}

fun JavaExec.plan(vararg extra: String) {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.oip.packaging.PlanKt")
    standardOutput = System.out
    errorOutput = System.err
    args(extra.toList())
}

val linuxStage = layout.buildDirectory.dir("gateway-stage-linux")

tasks.register<JavaExec>("gatewayAppImage") {
    group = "distribution"
    description = "jpackage app image of the gateway with a bundled jlink runtime, the admin UI, Control Canvas and the manual."
    dependsOn("stageLinuxGatewayPayload")
    val jar = serverLib.map { it.file("server-${project(":gateway:server").version}.jar") }
    plan(
        "gateway-image",
        "--product", productName.get(),
        "--version", version.toString(),
        "--input", linuxStage.get().asFile.absolutePath,
        "--main-jar", jar.get().asFile.name,
        "--dest", imageDest.get().asFile.absolutePath,
        "--launcher-path-file", gatewayLauncherPath.get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("designerAppImage") {
    group = "distribution"
    description = "jpackage app image of the designer launcher with a bundled jlink runtime."
    dependsOn(":tools:designer-launcher:installDist")
    val jar = designerLib.map { it.file("designer-launcher-${project(":tools:designer-launcher").version}.jar") }
    plan(
        "designer-image",
        "--product", productName.get(),
        "--version", version.toString(),
        "--input", designerLib.get().asFile.absolutePath,
        "--main-jar", jar.get().asFile.name,
        "--dest", imageDest.get().asFile.absolutePath,
        "--launcher-path-file", designerLauncherPath.get().asFile.absolutePath,
    )
}

fun downloadUrl(url: String, dest: File) {
    var last: Exception? = null
    repeat(4) { attempt ->
        try {
            dest.outputStream().use { out -> URI(url).toURL().openStream().use { input -> input.copyTo(out) } }
            return
        } catch (ex: Exception) {
            last = ex
            if (attempt < 3) Thread.sleep(5_000)
        }
    }
    throw GradleException("download failed for $url", last)
}

fun downloadPinned(url: String, dest: File, sha: String) {
    dest.parentFile.mkdirs()
    if (!dest.isFile || sha256(dest) != sha) {
        downloadUrl(url, dest)
    }
    if (sha256(dest) != sha) throw GradleException("checksum mismatch for $url")
}

val gatewayStage = layout.buildDirectory.dir("gateway-stage")
val plcBundle = layout.buildDirectory.dir("plc-bundle")

tasks.register("prepareWindowsResources") {
    group = "distribution"
    doLast {
        val dest = resourceDir.get().asFile
        dest.mkdirs()
        rootProject.file("packaging/windows/eric.ico").copyTo(dest.resolve("eric.ico"), true)
        val notice = rootProject.file("packaging/windows/LICENSE-INSTALL.txt").readText()
        val license = rootProject.file("LICENSE").readText()
        val lgpl = rootProject.file("packaging/windows/third-party/rusty-COPYING.LESSER").readText()
        dest.resolve("LICENSE-INSTALL.txt").writeText(notice + "\n\n" + license + "\n\n" + lgpl)
    }
}

tasks.register("stagePlcBundle") {
    group = "distribution"
    description = "eric-plc.exe, the ST library, and the separate RuSTy Windows compiler."
    doLast {
        if (!windows()) throw GradleException("the PLC bundle is built on Windows")
        val cargo = ProcessBuilder(
            "cargo", "build", "--release", "--locked",
            "--manifest-path", rootProject.file("plc/eric-plc/Cargo.toml").absolutePath,
        ).directory(rootProject.projectDir).inheritIO().start()
        if (cargo.waitFor() != 0) throw GradleException("cargo build of eric-plc failed")
        val built = rootProject.file("plc/eric-plc/target/release/eric-plc.exe")
        if (!built.isFile) throw GradleException("missing $built")
        val bundle = plcBundle.get().asFile
        bundle.deleteRecursively()
        bundle.mkdirs()
        built.copyTo(bundle.resolve("eric-plc.exe"), true)
        rootProject.file("plc/eric-plc/st").copyRecursively(bundle.resolve("st"), true)
        rootProject.file("plc/eric-plc/examples").copyRecursively(bundle.resolve("examples"), true)
        val rusty = bundle.resolve("rusty")
        rusty.mkdirs()
        val base = "https://github.com/PLC-lang/rusty/releases/download/v1.0.8"
        downloadPinned("$base/plc.exe", rusty.resolve("plc.exe"), "26b8a0f8e4f6eb4c145692e84fca478bc6357997d9a5a56e64b1309a505d32f3")
        downloadPinned("$base/iec61131std.dll", rusty.resolve("iec61131std.dll"), "bc0c2b2d0f10438590ebe28c9a588aadf3758d95bf3917209a718e9a807d8b0f")
        downloadPinned("$base/iec61131std.lib", rusty.resolve("iec61131std.lib"), "62bf3de6c98a4bc466e7204de61adf3bc6c257468835fc1f839908570978363b")
        val tar = rusty.resolve("stdlib.tar.gz")
        downloadPinned("$base/stdlib-1.0.8.tar.gz", tar, "f0bf72ed4df3ef5cab0d5887f8e06c5b0fd5f43ca1a10cd6ade249d0decb0c4e")
        val unpack = ProcessBuilder("tar", "-xzf", tar.absolutePath, "-C", rusty.absolutePath).inheritIO().start()
        if (unpack.waitFor() != 0) throw GradleException("could not unpack the RuSTy standard library")
        rootProject.file("packaging/windows/third-party").copyRecursively(bundle.resolve("licenses"), true)
        rootProject.file("packaging/windows/SOURCE-OFFER.txt").copyTo(bundle.resolve("licenses/SOURCE-OFFER.txt"), true)
    }
}

val linuxPlcBundle = layout.buildDirectory.dir("plc-bundle-linux")

tasks.register("stageLinuxPlcBundle") {
    group = "distribution"
    description = "eric-plc, the ST library, and the separate RuSTy Linux compiler with its standard library (x86_64)."
    doLast {
        if (windows()) throw GradleException("the Linux PLC bundle is built on Linux")
        val cargo = ProcessBuilder(
            "cargo", "build", "--release", "--locked",
            "--manifest-path", rootProject.file("plc/eric-plc/Cargo.toml").absolutePath,
        ).directory(rootProject.projectDir).inheritIO().start()
        if (cargo.waitFor() != 0) throw GradleException("cargo build of eric-plc failed")
        val built = rootProject.file("plc/eric-plc/target/release/eric-plc")
        if (!built.isFile) throw GradleException("missing $built")
        val bundle = linuxPlcBundle.get().asFile
        bundle.deleteRecursively()
        bundle.mkdirs()
        built.copyTo(bundle.resolve("eric-plc"), true).setExecutable(true, false)
        rootProject.file("plc/eric-plc/st").copyRecursively(bundle.resolve("st"), true)
        rootProject.file("plc/eric-plc/examples").copyRecursively(bundle.resolve("examples"), true)
        val rusty = bundle.resolve("rusty")
        rusty.mkdirs()
        val base = "https://github.com/PLC-lang/rusty/releases/download/v1.0.8"
        downloadPinned("$base/plc-linux-x86_64", rusty.resolve("plc"), "e4b086ae4eac8613b609a5862a161b9095f7f73d5b5db4967cf4571a8c080833")
        rusty.resolve("plc").setExecutable(true, false)
        val tar = rusty.resolve("stdlib.tar.gz")
        downloadPinned("$base/stdlib-1.0.8.tar.gz", tar, "f0bf72ed4df3ef5cab0d5887f8e06c5b0fd5f43ca1a10cd6ade249d0decb0c4e")
        val unpack = ProcessBuilder("tar", "-xzf", tar.absolutePath, "-C", rusty.absolutePath).inheritIO().start()
        if (unpack.waitFor() != 0) throw GradleException("could not unpack the RuSTy standard library")
        tar.delete()
        rootProject.file("packaging/windows/third-party").copyRecursively(bundle.resolve("licenses"), true)
        rootProject.file("packaging/windows/SOURCE-OFFER.txt").copyTo(bundle.resolve("licenses/SOURCE-OFFER.txt"), true)
    }
}

/** Jars, admin UI, Control Canvas and the manual into [dest]; the PLC bundle and uninstaller only on Windows. */
fun stagePayload(dest: File, windowsExtras: Boolean) {
    dest.deleteRecursively()
    dest.mkdirs()
    val lib = serverLib.get().asFile
    lib.listFiles()?.forEach { file -> file.copyRecursively(dest.resolve(file.name), true) }
    val admin = rootProject.file("web/admin/dist")
    val web = rootProject.file("web/designer/dist")
    if (!admin.isDirectory) throw GradleException("missing $admin — build the admin UI first")
    if (!web.isDirectory) throw GradleException("missing $web — build Control Canvas first")
    admin.copyRecursively(dest.resolve("admin"), true)
    web.copyRecursively(dest.resolve("web"), true)
    if (windowsExtras) plcBundle.get().asFile.copyRecursively(dest.resolve("plc"), true)
    else linuxPlcBundle.get().asFile.takeIf { it.isDirectory }?.let { plcDir ->
        plcDir.copyRecursively(dest.resolve("plc"), true)
        // copyRecursively drops the execute bit.
        listOf("plc/eric-plc", "plc/rusty/plc").forEach { dest.resolve(it).setExecutable(true, false) }
    }
    val docsOut = dest.resolve("docs")
    docsOut.mkdirs()
    listOf("manual", "install", "ops").forEach { name ->
        val src = rootProject.file("docs/$name")
        if (src.isDirectory) src.copyRecursively(docsOut.resolve(name), true)
    }
    val opsPage = rootProject.file("docs/ops.md")
    if (opsPage.isFile) opsPage.copyTo(docsOut.resolve("ops.md"), true)
    val guide = rootProject.file("docs-site/docs")
    if (guide.isDirectory) guide.copyRecursively(docsOut.resolve("guide"), true)
    if (windowsExtras) rootProject.file("packaging/windows/uninstall.ps1").copyTo(dest.resolve("uninstall.ps1"), true)
    rootProject.file("LICENSE").copyTo(dest.resolve("LICENSE"), true)
    rootProject.file("LICENSE-EXCEPTION.md").copyTo(dest.resolve("LICENSE-EXCEPTION.md"), true)
    rootProject.file("THIRD_PARTY.md").copyTo(dest.resolve("THIRD_PARTY.md"), true)
}

tasks.register("stageGatewayPayload") {
    group = "distribution"
    description = "Jars plus the admin UI, Control Canvas, and the PLC bundle."
    dependsOn(":gateway:server:installDist", "stagePlcBundle")
    doLast { stagePayload(gatewayStage.get().asFile, windowsExtras = true) }
}

tasks.register("stageLinuxGatewayPayload") {
    group = "distribution"
    description = "Jars plus the admin UI, Control Canvas, the manual and the PLC bundle, for the Linux app image."
    dependsOn(":gateway:server:installDist", "stageLinuxPlcBundle")
    doLast { stagePayload(linuxStage.get().asFile, windowsExtras = false) }
}

tasks.register<JavaExec>("gatewayExe") {
    group = "distribution"
    description = "Windows exe installer for the gateway. Requires WiX 3 on PATH."
    dependsOn("stageGatewayPayload", "fetchNssm", "prepareWindowsResources")
    val jar = serverLib.map { it.file("server-${project(":gateway:server").version}.jar") }
    plan(
        "gateway-exe",
        "--product", productName.get(),
        "--version", version.toString(),
        "--input", gatewayStage.get().asFile.absolutePath,
        "--main-jar", jar.get().asFile.name,
        "--dest", exeDest.get().asFile.absolutePath,
        "--resource-dir", resourceDir.get().asFile.absolutePath,
        "--upgrade-uuid", gatewayUpgradeUuid.get(),
        "--layout-file", layoutFile.get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("designerExe") {
    group = "distribution"
    description = "Windows exe installer for the designer launcher. Requires WiX 3 on PATH."
    dependsOn(":tools:designer-launcher:installDist", "prepareWindowsResources")
    val jar = designerLib.map { it.file("designer-launcher-${project(":tools:designer-launcher").version}.jar") }
    plan(
        "designer-exe",
        "--product", productName.get(),
        "--version", version.toString(),
        "--input", designerLib.get().asFile.absolutePath,
        "--main-jar", jar.get().asFile.name,
        "--dest", exeDest.get().asFile.absolutePath,
        "--upgrade-uuid", designerUpgradeUuid.get(),
        "--resource-dir", resourceDir.get().asFile.absolutePath,
    )
}

tasks.register("windowsInstallers") {
    group = "distribution"
    description = "Build both Windows exe installers. Windows only; not part of the regular build."
    dependsOn("gatewayExe", "designerExe")
}

tasks.register("smokeGatewayImage") {
    group = "verification"
    description = "Start the gateway app image and request /api/v1/health, then check the admin shortcut argument."
    dependsOn("gatewayAppImage")
    doLast {
        val bin = gatewayLauncherPath.get().asFile.readText().trim()
        val data = layout.buildDirectory.get().asFile.resolve("smoke-data")
        data.deleteRecursively()
        data.mkdirs()
        data.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: false
            gatewayNetwork:
              enabled: false
            """.trimIndent() + "\n",
        )
        val openAdminLog = layout.buildDirectory.get().asFile.resolve("smoke-open-admin.log")
        val opener = ProcessBuilder(bin)
        opener.environment()["OIP_ADMIN_PRINT_ONLY"] = "1"
        opener.redirectErrorStream(true).redirectOutput(openAdminLog)
        val opened = opener.start()
        if (!opened.waitFor(30, TimeUnit.SECONDS)) {
            opened.destroyForcibly()
            throw GradleException("open-admin did not exit\n" + openAdminLog.readText().takeLast(2000))
        }
        val openText = openAdminLog.readText()
        if (opened.exitValue() != 0 || !openText.contains("http://127.0.0.1:8090/admin")) {
            throw GradleException("open-admin failed (${opened.exitValue()}): $openText")
        }
        val log = layout.buildDirectory.get().asFile.resolve("smoke-gateway.log")
        val proc = ProcessBuilder(bin, "--data-dir", data.absolutePath, "--port", "18090")
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        try {
            val deadline = System.nanoTime() + 180_000_000_000L
            var ok = false
            var last = ""
            while (System.nanoTime() < deadline) {
                if (!proc.isAlive) {
                    throw GradleException("gateway exited ${proc.exitValue()}\n" + log.readText().takeLast(4000))
                }
                try {
                    val conn = URI("http://127.0.0.1:18090/api/v1/health").toURL()
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 1000
                    conn.readTimeout = 2000
                    last = conn.inputStream.bufferedReader().readText()
                    if (conn.responseCode == 200 && last.contains("UP")) {
                        ok = true
                        break
                    }
                } catch (_: Exception) {
                    Thread.sleep(1000)
                }
            }
            if (!ok) throw GradleException("health did not answer. last=$last\n" + log.readText().takeLast(4000))
        } finally {
            proc.destroy()
            if (!proc.waitFor(20, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }
}

tasks.register("smokeDesignerImage") {
    group = "verification"
    description = "Run the designer launcher with --print-only and check the URL."
    dependsOn("designerAppImage")
    doLast {
        val bin = designerLauncherPath.get().asFile.readText().trim()
        val log = layout.buildDirectory.get().asFile.resolve("smoke-designer.log")
        val proc = ProcessBuilder(bin, "--print-only").redirectErrorStream(true).redirectOutput(log).start()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw GradleException("designer launcher hung\n" + log.readText())
        }
        val text = log.readText()
        if (proc.exitValue() != 0 || !text.contains("http://127.0.0.1:8090/designer")) {
            throw GradleException("designer launcher failed (${proc.exitValue()}): $text")
        }
    }
}

tasks.register("smokeAppImages") {
    group = "verification"
    description = "Linux or macOS check of both jpackage app images. Does not build exe installers."
    dependsOn("smokeGatewayImage", "smokeDesignerImage")
}
