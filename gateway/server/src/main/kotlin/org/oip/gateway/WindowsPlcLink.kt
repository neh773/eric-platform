package org.oip.gateway

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** File name of a compiled scan library without its `lib` prefix or suffix. */
internal fun libraryStem(fileName: String): String =
    fileName.removePrefix("lib").removeSuffix(".so").removeSuffix(".dll")

internal const val NO_WINDOWS_LINKER =
    "The PLC linker (plc\\llvm\\lld-link.exe) is missing from this ERIC install. Repair or reinstall ERIC, then deploy again."

/** Names of the two marker symbols that bracket the program's constructor table (`.CRT$XCA` .. `.CRT$XCZ`). */
internal const val CTORS_BEGIN = "__eric_ctors_begin"
internal const val CTORS_END = "__eric_ctors_end"

/**
 * Definitions the Microsoft C runtime would otherwise supply. The scan library links with no C runtime,
 * so `_fltused` and the standard library's constants (declared `{external}` in its include files, and
 * exported from iec61131std.dll only as data) live in the program itself.
 */
internal val WINDOWS_SUPPORT_ST = """
    VAR_GLOBAL
        _fltused : DINT;
        PI_REAL : REAL := 3.14159265358979323846;
        PI_LREAL : LREAL := 3.14159265358979323846;
        FRAC_PI_2_REAL : REAL := 1.57079632679489661923;
        FRAC_PI_2_LREAL : LREAL := 1.57079632679489661923;
        FRAC_PI_4_REAL : REAL := 0.785398163397448309616;
        FRAC_PI_4_LREAL : LREAL := 0.785398163397448309616;
        E_REAL : REAL := 2.71828182845904523536;
        E_LREAL : LREAL := 2.71828182845904523536;
        INF_REAL : REAL := 1.0E38 * 1.0E38;
        INF_LREAL : LREAL := 1.0E308 * 1.0E308;
        NAN_REAL : REAL := (1.0E308 * 1.0E308) - (1.0E308 * 1.0E308);
        NAN_LREAL : LREAL := (1.0E308 * 1.0E308) - (1.0E308 * 1.0E308);
    END_VAR
""".trimIndent() + "\n"

/** ntdll.dll is mapped into every Windows process and exports the few C helpers LLVM code calls. */
internal val NTDLL_EXPORTS = listOf("__chkstk", "memcmp", "memcpy", "memmove", "memset", "strlen")

/**
 * Compiles [source] with the bundled RuSTy `plc.exe` and links a DLL the Windows scan engine can load.
 *
 * Linking uses the LLVM linker shipped in the install (`plc/llvm/lld-link.exe`), so a Windows PC needs no
 * Visual Studio or other C toolchain. The program links against `iec61131std.dll` through an import library
 * made from that DLL's export table; the standard library's Structured Text units are compiled into the
 * program; constructors run from a table the scan engine walks after it loads the DLL.
 *
 * [target] is passed to RuSTy as `--target` (tests cross-compile from Linux); null uses the host.
 */
internal fun linkWindows(
    compiler: File,
    source: Path,
    linker: File? = windowsLinker(compiler),
    target: String? = null,
): Path {
    if (!compiler.isFile) throw IllegalArgumentException("Structured Text compiler was not found")
    if (linker == null) throw IllegalArgumentException(NO_WINDOWS_LINKER)
    val out = source.parent.resolve("build")
    Files.createDirectories(out)
    val base = source.fileName.toString().removeSuffix(".st")
    // The running engine keeps the previous DLL mapped, so each deploy writes a new file.
    val stem = "$base-${System.currentTimeMillis()}"
    val work = out.resolve("$stem-link")
    Files.createDirectories(work)
    val targetArgs = target?.let { listOf("--target", it) }.orEmpty()
    val hwmap = out.resolve("$stem-hwmap.json").toFile()

    val exported = mutableListOf<File>()
    exported += compileUnit(compiler, source.toFile(), work.resolve("$base.obj").toFile(), hwmap, includeProcessLibrary = true, targetArgs)
    processLibraryUnits(compiler).forEach { unit ->
        exported += compileUnit(compiler, unit, work.resolve(unit.name.removeSuffix(".st") + ".obj").toFile(), null, includeProcessLibrary = false, targetArgs)
    }
    val objects = exported.toMutableList()
    val include = compiler.parentFile?.resolve("stdlib/include")
    if (include != null && include.isDirectory) {
        val stdObj = work.resolve("iec61131std-st.obj").toFile()
        runCaptured(
            listOf(compiler.absolutePath, include.absolutePath + File.separator + "*.st", "--generate-external-constructors", "-c", "--error-format", "none") +
                targetArgs + listOf("-o", stdObj.absolutePath),
        )
        if (!stdObj.isFile) throw IllegalArgumentException("RuSTy did not compile the standard library")
        objects += stdObj
    }
    val support = work.resolve("eric-support.st")
    support.writeText(WINDOWS_SUPPORT_ST)
    objects += compileUnit(compiler, support.toFile(), work.resolve("eric-support.obj").toFile(), null, includeProcessLibrary = false, targetArgs, includes = false)
    val markers = work.resolve("eric-ctors.obj").toFile()
    markers.writeBytes(Coff.ctorMarkers())
    objects += markers

    val libs = mutableListOf<File>()
    val stdDll = compiler.parentFile?.resolve("iec61131std.dll")
    if (stdDll != null && stdDll.isFile) {
        libs += importLibrary(linker, work, "iec61131std.dll", PeExports.read(stdDll.readBytes()))
    }
    libs += importLibrary(linker, work, "ntdll.dll", NTDLL_EXPORTS.map { PeExports.Export(it, code = true) })

    val exports = (exported.flatMap { Coff.definedExternals(it.readBytes()) } + listOf(CTORS_BEGIN, CTORS_END)).distinct()
    if (exports.size <= 2) throw IllegalArgumentException("the object file has no exported symbols")
    val dll = out.resolve("$stem.dll").toFile()
    val lines = mutableListOf("/nologo", "/dll", "/noentry", "/nodefaultlib", "/machine:x64")
    exports.forEach { lines += "/export:$it" }
    lines += "/out:${quoted(dll)}"
    objects.forEach { lines += quoted(it) }
    libs.forEach { lines += quoted(it) }
    val rsp = work.resolve("link.rsp").toFile()
    rsp.writeText(lines.joinToString("\r\n"))
    val text = try {
        runCaptured(listOf(linker.absolutePath, "@${rsp.absolutePath}"))
    } catch (ex: IllegalArgumentException) {
        throw IllegalArgumentException(linkErrors(ex.message.orEmpty()))
    }
    if (!dll.isFile) throw IllegalArgumentException(linkErrors(text).ifBlank { "the linker did not produce ${dll.name}" })
    if (stdDll != null && stdDll.isFile) {
        val dest = out.resolve("iec61131std.dll").toFile()
        if (!dest.isFile || dest.length() != stdDll.length()) stdDll.copyTo(dest, overwrite = true)
    }
    return dll.toPath()
}

/** `plc/llvm/lld-link.exe` beside the bundled compiler, else `lld-link` on PATH. */
internal fun windowsLinker(compiler: File, path: String? = System.getenv("PATH")): File? {
    compiler.absoluteFile.parentFile?.parentFile?.resolve("llvm")?.resolve("lld-link.exe")?.takeIf { it.isFile }?.let { return it }
    val dirs = path.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
    for (dir in dirs) {
        for (name in listOf("lld-link.exe", "lld-link")) {
            val file = File(dir, name)
            if (file.isFile && file.canExecute()) return file
        }
    }
    return null
}

/** Only the error lines of linker output, with the program's undefined symbols named plainly. */
internal fun linkErrors(text: String): String {
    val lines = text.lineSequence().map { it.trim() }.filter { it.contains("error", ignoreCase = true) }.take(20).toList()
    return lines.joinToString("\n") { line ->
        val undefined = Regex("""undefined symbol:\s*(.+)$""").find(line)?.groupValues?.get(1)
        if (undefined != null) "the program calls $undefined, which this ERIC install's standard library does not provide" else line
    }
}

private fun quoted(file: File) = "\"${file.absolutePath}\""

/** A `.def` file for [dll] and the import library lld-link makes from it. Data exports get no code thunk. */
private fun importLibrary(linker: File, dir: Path, dll: String, exports: List<PeExports.Export>): File {
    val stem = dll.removeSuffix(".dll")
    val def = dir.resolve("$stem.def").toFile()
    def.writeText(
        buildString {
            append("LIBRARY ").append(dll).append("\r\nEXPORTS\r\n")
            exports.forEach { append("  ").append(it.name).append(if (it.code) "" else " DATA").append("\r\n") }
        },
    )
    val lib = dir.resolve("$stem.lib").toFile()
    runCaptured(listOf(linker.absolutePath, "/lib", "/nologo", "/machine:x64", "/def:${def.absolutePath}", "/out:${lib.absolutePath}"))
    if (!lib.isFile) throw IllegalArgumentException("the linker did not make an import library for $dll")
    return lib
}

private fun compileUnit(
    plc: File,
    source: File,
    obj: File,
    hwmap: File?,
    includeProcessLibrary: Boolean,
    targetArgs: List<String>,
    includes: Boolean = true,
): File {
    val args = mutableListOf(plc.absolutePath, source.absolutePath)
    if (includes) {
        args += compilerIncludes(plc).chunked(2).filter { pair ->
            includeProcessLibrary || pair.getOrNull(1)?.contains("${File.separator}st${File.separator}") != true
        }.flatten()
    }
    args += "-c"
    args += targetArgs
    if (hwmap != null) args += "--hwmap-file=${hwmap.absolutePath}"
    args += listOf("-o", obj.absolutePath)
    val text = runCaptured(args)
    if (!obj.isFile) throw IllegalArgumentException(text.trim().ifEmpty { "RuSTy did not produce ${obj.name}" })
    return obj
}

private fun runCaptured(args: List<String>): String {
    val process = ProcessBuilder(args).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0) throw IllegalArgumentException(text.trim().ifEmpty { "command failed ($code)" })
    return text
}

/** The parts of the COFF object format the Windows link step reads and writes. */
internal object Coff {
    private const val MACHINE_AMD64 = 0x8664
    private const val STORAGE_EXTERNAL = 2

    /** Defined external symbols of an x64 COFF object (regular or `/bigobj` header). */
    fun definedExternals(bytes: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val big = bytes.size >= 56 && buf.getShort(0).toInt() == 0 && (buf.getShort(2).toInt() and 0xffff) == 0xffff
        val symbolTable: Int
        val count: Int
        val entry: Int
        if (big) {
            symbolTable = buf.getInt(48)
            count = buf.getInt(52)
            entry = 20
        } else {
            symbolTable = buf.getInt(8)
            count = buf.getInt(12)
            entry = 18
        }
        val strings = symbolTable + count * entry
        val names = linkedSetOf<String>()
        var i = 0
        while (i < count) {
            val at = symbolTable + i * entry
            val section = if (big) buf.getInt(at + 12) else buf.getShort(at + 12).toInt()
            val storage = bytes[at + entry - 2].toInt() and 0xff
            val aux = bytes[at + entry - 1].toInt() and 0xff
            if (storage == STORAGE_EXTERNAL && section > 0) names += symbolName(bytes, buf, at, strings)
            i += 1 + aux
        }
        return names.toList()
    }

    private fun symbolName(bytes: ByteArray, buf: ByteBuffer, at: Int, strings: Int): String {
        if (buf.getInt(at) == 0) {
            var p = strings + buf.getInt(at + 4)
            val start = p
            while (p < bytes.size && bytes[p].toInt() != 0) p++
            return String(bytes, start, p - start, Charsets.UTF_8)
        }
        var len = 0
        while (len < 8 && bytes[at + len].toInt() != 0) len++
        return String(bytes, at, len, Charsets.UTF_8)
    }

    /**
     * A data-only object with an 8-byte zero slot in `.CRT$XCA` ([CTORS_BEGIN]) and one in `.CRT$XCZ` ([CTORS_END]).
     * The linker sorts `.CRT$XC*` sections by name, so LLVM's constructor pointers (`.CRT$XCU`) land between them.
     */
    fun ctorMarkers(): ByteArray {
        val sections = listOf(".CRT\$XCA", ".CRT\$XCZ")
        val names = listOf(CTORS_BEGIN, CTORS_END)
        val header = 20
        val sectionHeaders = 40 * sections.size
        val dataStart = header + sectionHeaders
        val symbolTable = dataStart + 8 * sections.size
        val stringTable = ByteArray(4 + names.sumOf { it.length + 1 })
        val buf = ByteBuffer.allocate(symbolTable + 18 * names.size + stringTable.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MACHINE_AMD64.toShort()).putShort(sections.size.toShort()).putInt(0)
            .putInt(symbolTable).putInt(names.size).putShort(0).putShort(0)
        sections.forEachIndexed { i, name ->
            buf.put(name.toByteArray(Charsets.US_ASCII).copyOf(8))
            buf.putInt(0).putInt(0).putInt(8).putInt(dataStart + 8 * i).putInt(0).putInt(0).putShort(0).putShort(0)
            // initialized data | align 8 | read
            buf.putInt(0x00000040 or 0x00400000 or 0x40000000)
        }
        buf.put(ByteArray(8 * sections.size))
        var offset = 4
        val strings = ByteBuffer.wrap(stringTable).order(ByteOrder.LITTLE_ENDIAN)
        strings.putInt(stringTable.size)
        names.forEachIndexed { i, name ->
            buf.putInt(0).putInt(offset).putInt(0).putShort((i + 1).toShort()).putShort(0)
            buf.put(STORAGE_EXTERNAL.toByte()).put(0)
            strings.put(name.toByteArray(Charsets.US_ASCII)).put(0)
            offset += name.length + 1
        }
        buf.put(stringTable)
        return buf.array()
    }
}

/** Export names of a PE32+ DLL, each marked as code or data by the section it points into. */
internal object PeExports {
    data class Export(val name: String, val code: Boolean)

    private const val SCN_EXECUTE = 0x20000000

    fun read(bytes: ByteArray): List<Export> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val pe = buf.getInt(0x3c)
        require(buf.getInt(pe) == 0x00004550) { "not a PE file" }
        val coff = pe + 4
        val sectionCount = buf.getShort(coff + 2).toInt() and 0xffff
        val optionalSize = buf.getShort(coff + 16).toInt() and 0xffff
        val optional = coff + 20
        require((buf.getShort(optional).toInt() and 0xffff) == 0x20b) { "not a 64-bit DLL" }
        val exportRva = buf.getInt(optional + 112)
        val exportSize = buf.getInt(optional + 116)
        if (exportRva == 0) return emptyList()
        data class Section(val va: Int, val size: Int, val raw: Int, val flags: Int)
        val sectionTable = optional + optionalSize
        val sections = (0 until sectionCount).map { i ->
            val at = sectionTable + 40 * i
            Section(buf.getInt(at + 12), maxOf(buf.getInt(at + 8), buf.getInt(at + 16)), buf.getInt(at + 20), buf.getInt(at + 36))
        }
        fun sectionOf(rva: Int) = sections.firstOrNull { rva >= it.va && rva < it.va + it.size }
        fun offset(rva: Int): Int {
            val s = sectionOf(rva) ?: throw IllegalArgumentException("RVA $rva is outside every section")
            return rva - s.va + s.raw
        }
        val dir = offset(exportRva)
        val nameCount = buf.getInt(dir + 24)
        val functions = offset(buf.getInt(dir + 28))
        val namePointers = offset(buf.getInt(dir + 32))
        val ordinals = offset(buf.getInt(dir + 36))
        val result = mutableListOf<Export>()
        for (i in 0 until nameCount) {
            var p = offset(buf.getInt(namePointers + 4 * i))
            val start = p
            while (bytes[p].toInt() != 0) p++
            val name = String(bytes, start, p - start, Charsets.US_ASCII)
            val ordinal = buf.getShort(ordinals + 2 * i).toInt() and 0xffff
            val rva = buf.getInt(functions + 4 * ordinal)
            if (rva >= exportRva && rva < exportRva + exportSize) continue // forwarder
            val code = (sectionOf(rva)?.flags ?: 0) and SCN_EXECUTE != 0
            result += Export(name, code)
        }
        return result
    }
}
