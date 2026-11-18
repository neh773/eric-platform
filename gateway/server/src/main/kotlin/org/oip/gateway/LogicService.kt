package org.oip.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Programs, the tag mapping, and the running logic image.
 * A deploy writes safe output values before the new program is marked running.
 * A force is recorded here and, when a scan engine is connected, sent to it.
 * While the engine is stopped, faulted, or swapping, the value that is published
 * stays the safe value. The force does not bypass that.
 */
class LogicService(
    private val root: Path,
    private val compiler: PlcCompiler = PlcCompiler.fromEnvironment(),
    private val engine: PlcControl = PlcControl.disconnected(),
) {
    private val programs = root.resolve("programs")
    private val mappingFile = root.resolve("mapping.json")
    private val values = ConcurrentHashMap<String, LogicValue>()
    private val forces = ConcurrentHashMap<String, LogicForce>()
    @Volatile var state: String = "stopped"
        private set
    @Volatile var program: String? = null
        private set
    @Volatile var message: String = ""
        private set

    init {
        programs.createDirectories()
    }

    fun list(): List<String> =
        Files.list(programs).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".st") }
                .map { it.fileName.toString().removeSuffix(".st") }
                .sorted()
                .toList()
        }

    fun read(name: String): String = file(name).readText()

    fun create(name: String) {
        validateName(name)
        val path = file(name)
        if (path.exists()) throw IllegalArgumentException("program '$name' already exists")
        path.writeText("PROGRAM $name\nVAR\nEND_VAR\nEND_PROGRAM\n")
    }

    fun save(name: String, source: String) {
        validateName(name)
        if (source.length > 200_000) throw IllegalArgumentException("program is too large")
        file(name).writeText(source)
    }

    fun delete(name: String) {
        Files.deleteIfExists(file(name))
        if (program == name) {
            state = "stopped"
            program = null
        }
    }

    fun rename(name: String, next: String) {
        validateName(next)
        val from = file(name)
        if (!from.exists()) throw IllegalArgumentException("program '$name' was not found")
        val to = file(next)
        if (to.exists()) throw IllegalArgumentException("program '$next' already exists")
        Files.move(from, to)
        if (program == name) program = next
    }

    fun mapping(): Map<String, Any?> {
        if (!mappingFile.exists()) return mapOf("inputs" to emptyList<Any>(), "outputs" to emptyList<Any>())
        val parsed = Json.mapper.readValue(mappingFile.readText(), Any::class.java)
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?> ?: mapOf("inputs" to emptyList<Any>(), "outputs" to emptyList<Any>())
    }

    fun saveMapping(body: Map<String, Any?>) {
        val inputs = mappingRows(body["inputs"], "in")
        val outputs = mappingRows(body["outputs"], "out")
        mappingFile.writeText(Json.write(mapOf("inputs" to inputs, "outputs" to outputs)))
    }

    fun check(name: String): List<PlcDiagnostic> = compiler.check(file(name))

    fun deploy(name: String): Map<String, Any?> {
        val diagnostics = check(name)
        if (diagnostics.isNotEmpty()) {
            return mapOf("ok" to false, "state" to state, "diagnostics" to diagnostics.map { it.toMap() })
        }
        val previous = state
        state = "swapping"
        applySafe()
        if (engine.connected()) {
            val library = try {
                compiler.link(file(name))
            } catch (ex: IllegalArgumentException) {
                state = previous
                message = ex.message ?: "compile failed"
                return mapOf("ok" to false, "state" to state, "message" to message, "diagnostics" to emptyList<Any>())
            }
            if (library == null) {
                state = previous
                message = "the scan engine needs a compiled library"
                return mapOf("ok" to false, "state" to state, "message" to message, "diagnostics" to emptyList<Any>())
            }
            val hwmap = library.parent.resolve(libraryStem(library.fileName.toString()) + "-hwmap.json")
            val error = engine.deploy(library.toString(), name, "${name}_instance", if (hwmap.exists()) hwmap.toString() else null)
            if (error != null) {
                state = "faulted"
                applySafe()
                message = error
                return mapOf("ok" to false, "state" to state, "message" to message, "diagnostics" to emptyList<Any>())
            }
        }
        program = name
        pullEngine()
        if (!engine.connected()) state = "running"
        if (state != "running") applySafe()
        message = ""
        return status() + mapOf("ok" to (state == "running"))
    }

    fun start() {
        if (program == null) throw IllegalArgumentException("deploy a program before starting")
        val error = engine.start()
        if (error != null) throw IllegalArgumentException(error)
        pullEngine()
        if (state == "faulted") throw IllegalArgumentException("the engine is faulted; deploy again")
        if (!engine.connected()) state = "running"
    }

    fun stop() {
        val error = engine.stop()
        if (error != null) throw IllegalArgumentException(error)
        pullEngine()
        if (!engine.connected()) state = "stopped"
        applySafe()
    }

    fun fault() {
        state = "faulted"
        applySafe()
    }

    fun force(name: String, value: Any?, output: Boolean) {
        val point = allPoints().firstOrNull { it.name == name || it.tag == name }
        val sent = engine.force(point?.tag ?: name, engineBits(value, point?.type ?: "BOOL"), point?.output ?: output)
        if (sent != null) throw IllegalArgumentException(sent)
        forces[name] = LogicForce(name, value, output)
        if (!engine.connected() && state == "running") values[name] = LogicValue(value, forced = true, output = output)
    }

    fun unforce(name: String) {
        val point = allPoints().firstOrNull { it.name == name || it.tag == name }
        val sent = engine.unforce(point?.tag ?: name)
        if (sent != null) throw IllegalArgumentException(sent)
        forces.remove(name)
        if (point != null && point.tag != name) forces.remove(point.tag)
    }

    fun watch(): Map<String, Any?> {
        if (engine.connected()) {
            val remote = engine.watch()
            if (remote != null) return maskedWatch(remote)
        }
        return localWatch()
    }

    fun status(): Map<String, Any?> {
        val body = localStatus().toMutableMap()
        val remote = if (engine.connected()) engine.status() else null
        if (remote != null) {
            for (key in listOf("state", "forcesActive", "periodMs", "jitterP99Ns", "execP99Ns", "overruns")) {
                if (remote.containsKey(key)) body[key] = remote[key]
            }
            (remote["state"] as? String)?.let { state = it }
        }
        return body
    }

    private fun pullEngine() {
        val remote = engine.status() ?: return
        (remote["state"] as? String)?.let { state = it }
    }

    private fun localStatus(): Map<String, Any?> = mapOf(
        "state" to state,
        "program" to program,
        "message" to message,
        "forcesActive" to (state == "running" && forces.isNotEmpty()),
        "periodMs" to 10,
        "jitterP99Ns" to 0,
        "execP99Ns" to 0,
        "overruns" to 0,
    )

    private fun localWatch(): Map<String, Any?> {
        val rows = mutableListOf<Map<String, Any?>>()
        for (point in allPoints()) {
            val forced = forces[point.name]
            val published = when (state) {
                "running" -> if (forced != null) forced.value else values[point.name]?.value ?: point.safe
                else -> point.safe
            }
            rows += pointRow(point, published, forced != null && state == "running")
        }
        return mapOf("state" to state, "forcesActive" to (state == "running" && forces.isNotEmpty()), "variables" to rows)
    }

    /** Engine values are shown only while it reports running. Otherwise the safe value is published. */
    private fun maskedWatch(remote: Map<String, Any?>): Map<String, Any?> {
        val remoteState = remote["state"] as? String ?: state
        val live = remoteState == "running"
        state = remoteState
        val points = allPoints()
        val rows = mutableListOf<Map<String, Any?>>()
        if (!live) {
            for (point in points) rows += pointRow(point, point.safe, false)
            return mapOf("state" to remoteState, "forcesActive" to false, "variables" to rows)
        }
        val remoteRows = remote["variables"] as? List<*> ?: emptyList<Any>()
        for (point in points) {
            val match = remoteRows.filterIsInstance<Map<*, *>>().firstOrNull { row ->
                row["name"] == point.tag || row["name"] == point.name || row["address"] == point.address
            }
            val forced = match?.get("forced") == true
            val value = if (match == null) point.safe else present(match["bits"] ?: match["value"], point.type)
            rows += pointRow(point, value, forced)
        }
        return mapOf("state" to remoteState, "forcesActive" to (remote["forcesActive"] == true), "variables" to rows)
    }

    private fun pointRow(point: LogicPoint, value: Any?, forced: Boolean): Map<String, Any?> = mapOf(
        "name" to point.name,
        "address" to point.address,
        "tag" to point.tag,
        "value" to value,
        "forced" to forced,
        "output" to point.output,
        "safe" to point.safe,
    )

    private fun applySafe() {
        for (point in allPoints()) {
            if (point.output) values[point.name] = LogicValue(point.safe, forced = false, output = true)
        }
    }

    private fun allPoints(): List<LogicPoint> {
        val map = mapping()
        return logicPoints(map["inputs"], output = false) + logicPoints(map["outputs"], output = true)
    }

    private fun file(name: String): Path {
        validateName(name)
        return programs.resolve("$name.st")
    }

    private fun validateName(name: String) {
        if (!NAME.matches(name)) throw IllegalArgumentException("program name must be letters, digits, and underscores")
    }

    companion object {
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    }
}

data class LogicPoint(val name: String, val address: String, val tag: String, val type: String, val direction: String, val safe: Any?, val output: Boolean)

data class LogicForce(val name: String, val value: Any?, val output: Boolean)

data class LogicValue(val value: Any?, val forced: Boolean, val output: Boolean)

fun PlcDiagnostic.toMap(): Map<String, Any?> = mapOf("line" to line, "column" to column, "message" to message, "code" to code)

private fun mappingRows(raw: Any?, direction: String): List<Map<String, Any?>> {
    val rows = raw as? List<*> ?: return emptyList()
    return rows.map { row ->
        val map = row as? Map<*, *> ?: throw IllegalArgumentException("mapping entries must be objects")
        val address = map["address"] as? String ?: throw IllegalArgumentException("address is required")
        val tag = map["tag"] as? String ?: throw IllegalArgumentException("tag is required")
        val type = map["type"] as? String ?: "BOOL"
        val name = (map["name"] as? String)?.ifBlank { null } ?: tag.substringAfterLast('/')
        mapOf("name" to name, "address" to address, "tag" to tag, "type" to type, "direction" to direction, "safe" to map["safe"])
    }
}

private fun present(raw: Any?, type: String): Any? = when (type) {
    "BOOL" -> when (raw) {
        is Boolean -> raw
        is Number -> raw.toLong() != 0L
        else -> false
    }
    "REAL" -> when (raw) {
        is Number -> java.lang.Float.intBitsToFloat(raw.toInt()).toDouble()
        else -> raw
    }
    "LREAL" -> when (raw) {
        is Number -> java.lang.Double.longBitsToDouble(raw.toLong())
        else -> raw
    }
    else -> raw
}

/** A decimal force is the IEEE bit pattern the scan engine stores in a REAL or LREAL slot. */
internal fun engineBits(value: Any?, type: String): Any? = when (type) {
    "LREAL" -> (value as? Number)?.let { java.lang.Double.doubleToRawLongBits(it.toDouble()) } ?: value
    "REAL" -> (value as? Number)?.let { java.lang.Float.floatToRawIntBits(it.toFloat()).toLong() and 0xffff_ffffL } ?: value
    else -> value
}

private fun logicPoints(raw: Any?, output: Boolean): List<LogicPoint> =
    mappingRows(raw, if (output) "out" else "in").map {
        LogicPoint(
            name = it["name"] as String,
            address = it["address"] as String,
            tag = it["tag"] as String,
            type = it["type"] as String,
            direction = it["direction"] as String,
            safe = it["safe"],
            output = output,
        )
    }

/**
 * Runs the pinned RuSTy binary when [command] points at it. A missing compiler
 * is one diagnostic, not a crash. [ERIC_PLC_COMPILER] selects the binary.
 */
class PlcCompiler(
    private val command: String?,
    private val fixed: List<PlcDiagnostic>? = null,
    private val linkOverride: ((Path) -> Path)? = null,
) {
    /** A shared library for the scan engine, or null when this compiler only checks syntax. */
    fun link(source: Path): Path? {
        linkOverride?.let { return it(source) }
        val binary = command?.takeIf { it.isNotBlank() } ?: return null
        val plc = java.io.File(binary)
        if (!plc.isFile) return null
        if (System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
            return linkWindows(plc, source)
        }
        if (plc.name != "plc") return null
        val triple = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> "x86_64-unknown-linux-gnu"
            "aarch64" -> "aarch64-unknown-linux-gnu"
            else -> return null
        }
        val cache = plc.parentFile ?: return null
        val lib = cache.resolve("stdlib/$triple/lib")
        val include = cache.resolve("stdlib/include")
        val out = source.parent.resolve("build")
        java.nio.file.Files.createDirectories(out)
        val base = source.fileName.toString().removeSuffix(".st")
        val obj = out.resolve("$base.o")
        val so = out.resolve("lib$base.so")
        val hwmap = out.resolve("$base-hwmap.json")
        val compile = mutableListOf(plc.path, source.toString(), "-i", "${include.path}/*.st")
        compile += libraryIncludes(plc)
        compile += listOf("-c", "--fpic", "--hwmap-file=${hwmap}", "-o", obj.toString())
        run(compile)
        run(listOf("cc", "-shared", "-o", so.toString(), obj.toString(), "-L", lib.path, "-liec61131std", "-Wl,-rpath,\$ORIGIN"))
        val stdlib = lib.resolve("libiec61131std.so")
        if (stdlib.exists()) java.nio.file.Files.copy(stdlib.toPath(), out.resolve("libiec61131std.so"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return so
    }

    private fun run(args: List<String>) {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) throw IllegalArgumentException(text.trim().ifEmpty { "compile failed" })
    }

    fun check(source: Path): List<PlcDiagnostic> {
        if (fixed != null) return fixed
        val binary = command
        if (binary.isNullOrBlank()) {
            return listOf(PlcDiagnostic(1, 1, "Structured Text compiler is not available", "E000"))
        }
        val args = mutableListOf(binary, source.toString(), "--check")
        args += compilerIncludes(java.io.File(binary))
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code == 0) return emptyList()
        val parsed = PlcDiagnostics.parse(text)
        return parsed.ifEmpty { listOf(PlcDiagnostic(1, 1, text.trim().ifEmpty { "compile failed" }, "E000")) }
    }

    companion object {
        fun fromEnvironment(): PlcCompiler = PlcCompiler(System.getenv("ERIC_PLC_COMPILER"))
    }
}

/** Standard-library and process-library globs that sit beside a bundled `plc` or `plc.exe`. */
internal fun compilerIncludes(binary: java.io.File): List<String> {
    val parent = binary.parentFile ?: return emptyList()
    val dirs = listOfNotNull(parent.resolve("stdlib/include"), parent.parentFile?.resolve("st"))
    return dirs.filter { it.isDirectory }.flatMap { listOf("-i", it.absolutePath + java.io.File.separator + "*.st") }
}

/** Process-library include only. The Linux link step already names the standard library itself. */
internal fun libraryIncludes(binary: java.io.File): List<String> {
    val st = binary.parentFile?.parentFile?.resolve("st") ?: return emptyList()
    if (!st.isDirectory) return emptyList()
    return listOf("-i", st.absolutePath + java.io.File.separator + "*.st")
}
