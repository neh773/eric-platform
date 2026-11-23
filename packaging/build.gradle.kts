import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

buildscript {
    repositories { mavenCentral() }
    // Pure-Java xz reader for the pinned LLVM release archive (the Windows PLC linker comes out of it).
    dependencies { classpath("org.tukaani:xz:1.10") }
}

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

// LLVM's linker (Apache-2.0 WITH LLVM-exception) links PLC programs on Windows, so deploy needs no Visual Studio.
// Only bin/lld-link.exe is taken from the release archive; it uses no runtime beyond Windows system DLLs.
val llvmVersion = "23.1.6"
val llvmArchive = "clang+llvm-$llvmVersion-x86_64-pc-windows-msvc"
val llvmUrl = "https://github.com/llvm/llvm-project/releases/download/llvmorg-$llvmVersion/clang%2Bllvm-$llvmVersion-x86_64-pc-windows-msvc.tar.xz"
val llvmSha256 = "16af05919cf36958dfb4ddaa4fc4d9d4ff577f5daeb76bc112ea49cd8ced82b2"
val lldLinkSha256 = "89f2d0a22ee690ded87a59c396ab38139dde7efe56a749e6b3ea21490af692ce"
val lldLinkCache = layout.buildDirectory.file("llvm/lld-link-$llvmVersion.exe")

/** Copies one regular file out of a `.tar.xz` without unpacking the rest. Stops reading once it is found. */
fun extractFromTarXz(archive: File, entry: String, dest: File) {
    org.tukaani.xz.XZInputStream(archive.inputStream().buffered(1 shl 20)).use { input ->
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (input.readNBytes(header, 0, 512) < 512) break
            if (header.all { it.toInt() == 0 }) break
            fun field(from: Int, len: Int) = String(header, from, len, Charsets.UTF_8).substringBefore('\u0000')
            val sizeField = field(124, 12).trim()
            val size = if (sizeField.isEmpty()) 0L else sizeField.toLong(8)
            val type = header[156].toInt().toChar()
            val prefix = field(345, 155)
            val name = longName ?: if (prefix.isNotEmpty()) "$prefix/${field(0, 100)}" else field(0, 100)
            longName = null
            val padded = (size + 511) / 512 * 512
            when {
                type == 'L' -> {
                    longName = String(input.readNBytes(padded.toInt()), Charsets.UTF_8).substringBefore('\u0000')
                    continue
                }
                type == 'x' -> {
                    val pax = String(input.readNBytes(padded.toInt()), Charsets.UTF_8)
                    longName = Regex("""\d+ path=([^\n]*)\n""").find(pax)?.groupValues?.get(1)
                    continue
                }
                (type == '0' || type == '\u0000') && name == entry -> {
                    dest.parentFile.mkdirs()
                    dest.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var left = size
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw GradleException("truncated $entry in $archive")
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                    return
                }
                else -> input.skipNBytes(padded)
            }
        }
    }
    throw GradleException("$entry is not in $archive")
}

/** The pinned lld-link.exe, downloaded and cut out of the LLVM release once per build directory. */
fun pinnedLldLink(): File {
    val exe = lldLinkCache.get().asFile
    if (exe.isFile && sha256(exe) == lldLinkSha256) return exe
    val archive = layout.buildDirectory.file("llvm/$llvmArchive.tar.xz").get().asFile
    downloadPinned(llvmUrl, archive, llvmSha256)
    extractFromTarXz(archive, "$llvmArchive/bin/lld-link.exe", exe)
    archive.delete()
    if (sha256(exe) != lldLinkSha256) throw GradleException("checksum mismatch for lld-link.exe from $llvmUrl")
    return exe
}

// The Linux bundle takes bin/lld from the same LLVM release and ships it as plc/llvm/ld.lld, so Linux PLC
// deploy needs no gcc. It needs only libc, libm, libstdc++, libgcc_s and zlib, which every desktop Linux has.
val llvmLinuxArchive = "LLVM-$llvmVersion-Linux-X64"
val llvmLinuxUrl = "https://github.com/llvm/llvm-project/releases/download/llvmorg-$llvmVersion/$llvmLinuxArchive.tar.xz"
val llvmLinuxSha256 = "bd4d1c434ff69ef06d19e7934c04708da677e5f2eb51f5bcf052ab91818f0094"
val lldLinuxSha256 = "5c275e61d9b5e53475b1b82f8aa99490dc4345cd788f08b9cdab6be819094216"

/** The pinned Linux lld, cut out of the LLVM release once per build directory, then stripped when `strip` exists. */
fun pinnedLinuxLld(): File {
    val raw = layout.buildDirectory.file("llvm/lld-linux-$llvmVersion").get().asFile
    if (!raw.isFile || sha256(raw) != lldLinuxSha256) {
        val archive = layout.buildDirectory.file("llvm/$llvmLinuxArchive.tar.xz").get().asFile
        downloadPinned(llvmLinuxUrl, archive, llvmLinuxSha256)
        extractFromTarXz(archive, "$llvmLinuxArchive/bin/lld", raw)
        archive.delete()
        if (sha256(raw) != lldLinuxSha256) throw GradleException("checksum mismatch for lld from $llvmLinuxUrl")
    }
    val stripped = layout.buildDirectory.file("llvm/ld.lld-$llvmVersion").get().asFile
    if (!stripped.isFile) {
        val strip = try {
            ProcessBuilder("strip", "-o", stripped.absolutePath, raw.absolutePath).inheritIO().start().waitFor()
        } catch (ex: java.io.IOException) { -1 }
        if (strip != 0) raw.copyTo(stripped, true)
    }
    stripped.setExecutable(true, false)
    return stripped
}

tasks.register("stageWindowsLinker") {
    group = "distribution"
    description = "Downloads the pinned LLVM release and keeps only lld-link.exe (runs on any OS)."
    doLast { println("lld-link.exe: ${pinnedLldLink()}") }
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
        val cargoBuild = ProcessBuilder(
            "cargo", "build", "--release", "--locked",
            "--manifest-path", rootProject.file("plc/eric-plc/Cargo.toml").absolutePath,
        ).directory(rootProject.projectDir).inheritIO()
        // A clean Windows machine has no Visual C++ runtime: link it into eric-plc.exe.
        cargoBuild.environment()["RUSTFLAGS"] = listOfNotNull(System.getenv("RUSTFLAGS"), "-C target-feature=+crt-static").joinToString(" ")
        val cargo = cargoBuild.start()
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
        // Programs link against iec61131std.dll through an import library made at deploy time, so the
        // 13 MB static iec61131std.lib is not shipped.
        val tar = rusty.resolve("stdlib.tar.gz")
        downloadPinned("$base/stdlib-1.0.8.tar.gz", tar, "f0bf72ed4df3ef5cab0d5887f8e06c5b0fd5f43ca1a10cd6ade249d0decb0c4e")
        val unpack = ProcessBuilder("tar", "-xzf", tar.absolutePath, "-C", rusty.absolutePath).inheritIO().start()
        if (unpack.waitFor() != 0) throw GradleException("could not unpack the RuSTy standard library")
        // The prebuilt RuSTy compiler and its standard library DLL load the Visual C++ runtime, which a clean
        // Windows machine does not have. Ship the runtime DLLs next to them (app-local), from the build JDK.
        val jdkBin = File(System.getProperty("java.home"), "bin")
        val vcRuntime = listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")
        val found = vcRuntime.filter { jdkBin.resolve(it).isFile }
        if ("vcruntime140.dll" !in found) throw GradleException("vcruntime140.dll not found in $jdkBin; build with a JDK that bundles it")
        for (dll in found) {
            jdkBin.resolve(dll).copyTo(rusty.resolve(dll), true)
            jdkBin.resolve(dll).copyTo(bundle.resolve(dll), true)
        }
        pinnedLldLink().copyTo(bundle.resolve("llvm/lld-link.exe"), true)
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
        pinnedLinuxLld().copyTo(bundle.resolve("llvm/ld.lld"), true).setExecutable(true, false)
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
        listOf("plc/eric-plc", "plc/rusty/plc", "plc/llvm/ld.lld").forEach { dest.resolve(it).setExecutable(true, false) }
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
    if (windowsExtras) {
        rootProject.file("packaging/windows/uninstall.ps1").copyTo(dest.resolve("uninstall.ps1"), true)
        rootProject.file("packaging/windows/check-install.ps1").copyTo(dest.resolve("check-install.ps1"), true)
    }
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
