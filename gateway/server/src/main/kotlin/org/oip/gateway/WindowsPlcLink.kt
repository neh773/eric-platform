package org.oip.gateway

import java.io.File
import java.nio.file.Files
import kotlin.io.path.writeText

/** File name of a compiled scan library without its `lib` prefix or suffix. */
internal fun libraryStem(fileName: String): String =
    fileName.removePrefix("lib").removeSuffix(".so").removeSuffix(".dll")

/** Defined external symbols from `dumpbin /SYMBOLS` output. */
internal fun exportedSymbols(dumpbin: String): List<String> {
    val names = linkedSetOf<String>()
    for (line in dumpbin.lineSequence()) {
        if (line.contains("UNDEF")) continue
        val match = Regex("""External\s+\|\s+(\S+)\s*$""").find(line) ?: continue
        names += match.groupValues[1]
    }
    return names.toList()
}

/**
 * Compiles [source] with the bundled RuSTy `plc.exe` and links a DLL the Windows scan engine can load.
 * Visual Studio supplies the linker. Linux linking is unchanged.
 */
internal fun linkWindows(compiler: File, source: java.nio.file.Path): java.nio.file.Path {
    if (!compiler.isFile) throw IllegalArgumentException("Structured Text compiler was not found")
    val out = source.parent.resolve("build")
    Files.createDirectories(out)
    val base = source.fileName.toString().removeSuffix(".st")
    // The running engine keeps the previous DLL mapped, so each deploy writes a new file.
    val stem = "$base-${System.currentTimeMillis()}"
    val hwmap = out.resolve("$stem-hwmap.json").toFile()
    val objects = mutableListOf<File>()
    objects += compileUnit(compiler, source.toFile(), out.resolve("$stem.obj").toFile(), hwmap, includeProcessLibrary = true)
    val library = compiler.parentFile?.parentFile?.resolve("st")
    if (library != null && library.isDirectory) {
        library.listFiles { file -> file.isFile && file.name.endsWith(".st") }
            ?.sortedBy { it.name }
            ?.forEach { unit ->
                objects += compileUnit(
                    compiler,
                    unit,
                    out.resolve(unit.name.removeSuffix(".st") + ".obj").toFile(),
                    null,
                    includeProcessLibrary = false,
                )
            }
    }
    val dll = out.resolve("$stem.dll").toFile()
    linkDll(compiler, objects, dll)
    val stdlib = compiler.parentFile?.resolve("iec61131std.dll")
    val stdlibDest = out.resolve("iec61131std.dll").toFile()
    if (stdlib != null && stdlib.isFile && !stdlibDest.isFile) stdlib.copyTo(stdlibDest)
    return dll.toPath()
}

private fun compileUnit(plc: File, source: File, obj: File, hwmap: File?, includeProcessLibrary: Boolean): File {
    val args = mutableListOf(plc.absolutePath, source.absolutePath)
    args += compilerIncludes(plc).chunked(2).filter { pair ->
        includeProcessLibrary || pair.getOrNull(1)?.contains("${File.separator}st${File.separator}") != true
    }.flatten()
    args += "-c"
    if (hwmap != null) args += "--hwmap-file=${hwmap.absolutePath}"
    args += listOf("-o", obj.absolutePath)
    val text = runCaptured(args)
    if (!obj.isFile) throw IllegalArgumentException(text.trim().ifEmpty { "RuSTy did not produce ${obj.name}" })
    return obj
}

private fun linkDll(compiler: File, objects: List<File>, dll: File) {
    val vcvars = visualCppVars()
    val exports = objects.flatMap { exportedSymbols(runVc(vcvars, "dumpbin /SYMBOLS \"${it.absolutePath}\"")) }.distinct()
    if (exports.isEmpty()) throw IllegalArgumentException("the object file has no exported symbols")
    val rsp = dll.parentFile.resolve("${dll.nameWithoutExtension}.link.rsp")
    val lines = mutableListOf("/nologo", "/DLL")
    exports.forEach { lines += "/EXPORT:$it" }
    lines += "/OUT:\"${dll.absolutePath}\""
    objects.forEach { lines += "\"${it.absolutePath}\"" }
    compiler.parentFile?.resolve("iec61131std.lib")?.takeIf { it.isFile }?.let { lines += "\"${it.absolutePath}\"" }
    lines += listOf(
        "kernel32.lib", "advapi32.lib", "userenv.lib", "ws2_32.lib",
        "ntdll.lib", "bcrypt.lib", "msvcrt.lib", "vcruntime.lib", "ucrt.lib",
    )
    rsp.writeText(lines.joinToString("\r\n"))
    val text = runVc(vcvars, "link @\"${rsp.absolutePath}\"")
    if (!dll.isFile || text.contains("fatal error", ignoreCase = true)) {
        val errors = text.lineSequence().filter { it.contains("error", ignoreCase = true) }.take(20).joinToString("\n")
        throw IllegalArgumentException(errors.ifBlank { "the Microsoft linker did not produce ${dll.name}" })
    }
}

internal const val NO_VC_TOOLS = "Deploying a PLC program on Windows needs Visual Studio Build Tools with \"Desktop development with C++\" (free). Install them, then deploy again. Everything else in ERIC works without them."

private fun visualCppVars(): File {
    val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
    val vswhere = File(pf86, "Microsoft Visual Studio\\Installer\\vswhere.exe")
    if (!vswhere.isFile) throw IllegalArgumentException(NO_VC_TOOLS)
    val install = runCaptured(
        listOf(
            vswhere.absolutePath, "-latest", "-products", "*", "-requires",
            "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath",
        ),
    ).trim()
    val vcvars = File(install, "VC\\Auxiliary\\Build\\vcvars64.bat")
    if (install.isBlank() || !vcvars.isFile) throw IllegalArgumentException(NO_VC_TOOLS)
    return vcvars
}

private fun runVc(vcvars: File, command: String): String {
    val bat = Files.createTempFile("eric-plc-link", ".bat")
    bat.writeText("@echo off\r\ncall \"${vcvars.absolutePath}\" >nul\r\n$command\r\necho ERIC_LINK_EXIT %ERRORLEVEL%\r\n")
    return try {
        val process = ProcessBuilder("cmd.exe", "/c", bat.toAbsolutePath().toString()).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val exit = Regex("""ERIC_LINK_EXIT\s+(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (exit != null && exit != 0) {
            val errors = text.lineSequence().filter { it.contains("error", ignoreCase = true) }.take(30).joinToString("\n")
            throw IllegalArgumentException(errors.ifBlank { text.takeLast(2000) })
        }
        text
    } finally {
        Files.deleteIfExists(bat)
    }
}

private fun runCaptured(args: List<String>): String {
    val process = ProcessBuilder(args).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0) throw IllegalArgumentException(text.trim().ifEmpty { "command failed ($code)" })
    return text
}
