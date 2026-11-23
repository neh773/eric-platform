package org.oip.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.oip.plc.PreparedProgram
import org.oip.plc.StSource
import org.oip.plc.UdtBinding
import org.oip.plc.UdtCatalog
import org.oip.plc.UdtLogic

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
    engine: PlcControl = PlcControl.disconnected(),
    /** Gateway UDTs for Structured Text structures and UDT instance bindings (0.1.1). */
    private val udts: () -> UdtCatalog = { UdtCatalog.EMPTY },
) {
    private val external = engine
    /** The engine the gateway starts itself when no external engine socket is configured (0.1.1). */
    @Volatile internal var managed: ManagedPlc? = null
    private val engine: PlcControl get() = managed?.takeIf { it.alive() }?.control?.takeIf { it.connected() } ?: external
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

    /**
     * Saves the hand-mapped rows and the UDT bindings. Rows generated for UDT members (they carry `udt`) are kept
     * while their binding still exists; the next check or deploy regenerates them.
     */
    fun saveMapping(body: Map<String, Any?>) {
        val bindings = udtBindings(body["udts"])
        val bound = bindings.map { it.variable.lowercase() }.toSet()
        val previous = mapping()
        fun side(key: String, direction: String): List<Map<String, Any?>> {
            val hand = mappingRows((body[key] as? List<*>).orEmpty().filter { (it as? Map<*, *>)?.get("udt") == null }, direction)
            val kept = (previous[key] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>()
                .filter { (it["udt"] as? String)?.lowercase() in bound }
            return hand + kept
        }
        writeMapping(side("inputs", "in"), side("outputs", "out"), bindings, scanSettings(body, mapping()))
    }

    private fun writeMapping(inputs: List<Map<String, Any?>>, outputs: List<Map<String, Any?>>, bindings: List<UdtBinding>, settings: ScanSettings? = null) {
        val scan = settings ?: scanSettings()
        val doc = linkedMapOf<String, Any?>(
            "inputs" to inputs,
            "outputs" to outputs,
            "periodMs" to scan.periodMs,
            "maxConsecutiveOverruns" to scan.maxConsecutiveOverruns,
        )
        if (bindings.isNotEmpty()) {
            doc["udts"] = bindings.map { b -> linkedMapOf("variable" to b.variable, "instance" to b.instance, "members" to b.members) }
        }
        mappingFile.writeText(Json.write(doc))
    }

    fun bindings(): List<UdtBinding> = udtBindings(mapping()["udts"])

    private fun handRows(key: String): List<Map<String, Any?>> =
        (mapping()[key] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>().filter { it["udt"] == null }

    /** The user's source with UDT glue, as the compiler sees it, written to `logic/build/<name>.st`. */
    fun prepare(name: String): Pair<Path, PreparedProgram> {
        val source = read(name)
        val used = (handRows("inputs") + handRows("outputs")).mapNotNull { it["address"] as? String }
        val prepared = UdtLogic.prepare(source, name, bindings(), used, udts())
        val dir = root.resolve("build").createDirectories()
        val out = dir.resolve("$name.st")
        out.writeText(prepared.source)
        return out to prepared
    }

    /** Structure variables in [name] that a UDT instance can be bound to, with their current binding plan. */
    fun udtView(name: String): Map<String, Any?> {
        val catalog = udts()
        val source = read(name)
        val variables = UdtLogic.structVariables(source, name, catalog)
        val plans = runCatching { prepare(name).second.plans }.getOrDefault(emptyList())
        val names = UdtLogic.typeNames(catalog)
        return mapOf(
            "types" to names.map { (path, st) -> mapOf("udt" to path, "st" to st) },
            "instances" to catalog.instances().map { (path, type) -> mapOf("path" to path, "udt" to type) },
            "variables" to variables.map { v ->
                val plan = plans.firstOrNull { it.variable.equals(v.variable, true) }
                linkedMapOf(
                    "variable" to v.variable,
                    "type" to v.type,
                    "udt" to v.udtType,
                    "declaredInProgram" to v.fromSource,
                    "instance" to plan?.instance,
                    "problems" to plan?.problems.orEmpty(),
                    "members" to plan?.members.orEmpty().map { m ->
                        linkedMapOf(
                            "path" to m.stPath(v.variable), "member" to m.member, "type" to m.type,
                            "direction" to m.direction, "auto" to m.auto, "tag" to m.tag, "address" to m.address, "note" to m.note,
                        )
                    },
                )
            },
        )
    }

    fun check(name: String): List<PlcDiagnostic> {
        val (file, _) = prepare(name)
        return userLines(name, compiler.check(file))
    }

    /** A diagnostic past the user's last line is in the appended gateway UDT types. */
    private fun userLines(name: String, found: List<PlcDiagnostic>): List<PlcDiagnostic> {
        val lines = read(name).lines().size
        return found.map { d -> if (d.line > lines) d.copy(line = 1, column = 1, message = "In the gateway UDT types added to this program: ${d.message}") else d }
    }

    fun deploy(name: String): Map<String, Any?> {
        val (prepared, plan) = prepare(name)
        val diagnostics = userLines(name, compiler.check(prepared))
        if (diagnostics.isNotEmpty()) {
            return mapOf("ok" to false, "state" to state, "diagnostics" to diagnostics.map { it.toMap() })
        }
        // The generated UDT member rows join the hand-mapped ones, so the engine and the bridge move them too.
        writeMapping(handRows("inputs") + plan.inputs, handRows("outputs") + plan.outputs, bindings())
        val entry = UdtLogic.programOf(StSource.parse(read(name)), name)?.name ?: name
        // A new program starts without the old program's forces.
        releaseAll()
        val previous = state
        state = "swapping"
        applySafe()
        val supervisor = managed
        if (supervisor != null) {
            val library = try {
                compiler.link(prepared)
            } catch (ex: IllegalArgumentException) {
                state = previous
                message = ex.message ?: "compile failed"
                return mapOf("ok" to false, "state" to state, "message" to message, "diagnostics" to emptyList<Any>())
            }
            if (library == null) {
                state = previous
                message = "the Structured Text compiler could not build a program for the scan engine"
                return mapOf("ok" to false, "state" to state, "message" to message, "diagnostics" to emptyList<Any>())
            }
            val hwmap = library.parent.resolve(libraryStem(library.fileName.toString()) + "-hwmap.json")
            val scan = scanSettings()
            val error = supervisor.start(library, entry, "${entry}_instance", hwmap, mapping(), periodMs = scan.periodMs, maxConsecutiveOverruns = scan.maxConsecutiveOverruns)
            if (error != null) {
                state = "faulted"
                applySafe()
                message = error
                return mapOf("ok" to false, "state" to state, "message" to message, "diagnostics" to emptyList<Any>())
            }
        } else if (engine.connected()) {
            val library = try {
                compiler.link(prepared)
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
            val error = engine.deploy(library.toString(), entry, "${entry}_instance", if (hwmap.exists()) hwmap.toString() else null)
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
        val supervisor = managed
        val error = if (supervisor != null && (!supervisor.alive() || supervisor.watchdogTripped() || state == "faulted")) {
            val scan = scanSettings()
            supervisor.relaunch(scan.periodMs, scan.maxConsecutiveOverruns)
        } else {
            engine.start()
        }
        if (error != null) throw IllegalArgumentException(error)
        managed?.rememberRunning(true)
        pullEngine()
        if (state == "faulted" && managed == null) {
            throw IllegalArgumentException("the engine is faulted; deploy again")
        }
        if (!engine.connected() && state != "faulted") state = "running"
        if (state == "running" || state == "stopped") message = ""
    }

    fun stop() {
        releaseAll()
        val error = engine.stop()
        if (error != null) throw IllegalArgumentException(error)
        managed?.rememberRunning(false)
        pullEngine()
        if (!engine.connected()) state = "stopped"
        message = ""
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

    /** Writes a mapped point's tag once (no force), for online mode's Write once. */
    var writeTag: (String, Any?) -> Unit = { _, _ -> throw IllegalArgumentException("tag writes are not available") }

    fun writeOnce(name: String, value: Any?) {
        val point = allPoints().firstOrNull { it.name == name || it.tag == name }
            ?: throw IllegalArgumentException("$name is not in the tag map")
        writeTag(point.tag, value)
    }

    /** After a gateway restart: the program the engine resumed, so Start and Stop work without a redeploy. */
    internal fun resumed(deployed: ManagedPlc.Deployed) {
        val stem = libraryStem(java.io.File(deployed.library).name)
        val names = list()
        program = names.firstOrNull { it.equals(stem, ignoreCase = true) } ?: names.firstOrNull { it.equals(deployed.entry, ignoreCase = true) } ?: stem
        pullEngine()
    }

    fun forcedNames(): List<String> = forces.keys.sorted()

    /** Releases every force. Returns the names that were forced. */
    fun releaseAll(): List<String> {
        val names = forces.keys.toList()
        for (name in names) runCatching { unforce(name) }.onFailure { forces.remove(name) }
        return names
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
        applyEngineHealth(null)
        return localWatch()
    }

    private fun scanSettings(): ScanSettings = scanSettings(mapping())

    fun status(): Map<String, Any?> {
        val remote = if (engine.connected()) engine.status() else null
        applyEngineHealth(remote)
        val body = localStatus().toMutableMap()
        if (remote != null) {
            for (key in listOf("forcesActive", "periodMs", "jitterP99Ns", "execP99Ns", "overruns")) {
                if (remote.containsKey(key)) body[key] = remote[key]
            }
        }
        body["state"] = state
        body["message"] = message
        body["fault"] = currentFault()
        if (state != "running") body["forcesActive"] = false
        return body
    }

    private fun pullEngine() {
        applyEngineHealth(if (engine.connected()) engine.status() else null)
    }

    /** Aligns [state] and [message] with the engine process, its status socket, and status.json. */
    private fun applyEngineHealth(remote: Map<String, Any?>?) {
        val supervisor = managed
        val presented = presentLogicHealth(
            currentState = state,
            currentMessage = message,
            remote = remote,
            engineAlive = supervisor?.alive(),
            exitMessage = supervisor?.takeIf { !it.alive() }?.exitError(),
            statusWatchdog = supervisor?.watchdogTripped() == true,
        )
        state = presented.state
        if (presented.message != null) message = presented.message
    }

    private fun currentFault(): String? = when {
        state == "faulted" && (message == "watchdog" || managed?.watchdogTripped() == true) -> "watchdog"
        else -> null
    }

    private fun localStatus(): Map<String, Any?> = mapOf(
        "state" to state,
        "program" to program,
        "message" to message,
        "fault" to currentFault(),
        "forcesActive" to (state == "running" && forces.isNotEmpty()),
        "periodMs" to (managed?.let { scanSettings().periodMs } ?: scanSettings().periodMs),
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
        return mapOf("state" to state, "message" to message, "fault" to currentFault(), "forcesActive" to (state == "running" && forces.isNotEmpty()), "variables" to rows)
    }

    /** Engine values are shown only while it reports running. Otherwise the safe value is published. */
    private fun maskedWatch(remote: Map<String, Any?>): Map<String, Any?> {
        applyEngineHealth(remote)
        val remoteState = state
        val live = remoteState == "running"
        val points = allPoints()
        val rows = mutableListOf<Map<String, Any?>>()
        if (!live) {
            for (point in points) rows += pointRow(point, point.safe, false)
            return mapOf("state" to remoteState, "message" to message, "fault" to currentFault(), "forcesActive" to false, "variables" to rows)
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
        return mapOf("state" to remoteState, "message" to message, "fault" to currentFault(), "forcesActive" to (remote["forcesActive"] == true && remoteState == "running"), "variables" to rows)
    }

    private fun pointRow(point: LogicPoint, value: Any?, forced: Boolean): Map<String, Any?> = mapOf(
        "name" to point.name,
        "address" to point.address,
        "tag" to point.tag,
        "value" to value,
        "forced" to forced,
        "output" to point.output,
        "safe" to point.safe,
        "udt" to point.udt,
        "member" to point.member,
        "type" to point.type,
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

data class LogicPoint(
    val name: String,
    val address: String,
    val tag: String,
    val type: String,
    val direction: String,
    val safe: Any?,
    val output: Boolean,
    val udt: String? = null,
    val member: String? = null,
)

data class LogicForce(val name: String, val value: Any?, val output: Boolean)

data class LogicValue(val value: Any?, val forced: Boolean, val output: Boolean)

fun PlcDiagnostic.toMap(): Map<String, Any?> = mapOf("line" to line, "column" to column, "message" to message, "code" to code)

/** The ST type an address size letter implies: X bit, B/W word, D double word (REAL), L long word (LREAL). */
internal fun typeForAddress(address: String): String =
    when (Regex("^%[IQM]([XBWDL])", RegexOption.IGNORE_CASE).find(address.trim())?.groupValues?.get(1)?.uppercase()) {
        "B", "W" -> "INT"
        "D" -> "REAL"
        "L" -> "LREAL"
        else -> "BOOL"
    }

private fun mappingRows(raw: Any?, direction: String): List<Map<String, Any?>> {
    val rows = raw as? List<*> ?: return emptyList()
    return rows.map { row ->
        val map = row as? Map<*, *> ?: throw IllegalArgumentException("mapping entries must be objects")
        val address = map["address"] as? String ?: throw IllegalArgumentException("address is required")
        val tag = map["tag"] as? String ?: throw IllegalArgumentException("tag is required")
        val type = (map["type"] as? String)?.ifBlank { null } ?: typeForAddress(address)
        val name = (map["name"] as? String)?.ifBlank { null } ?: tag.substringAfterLast('/')
        val row = linkedMapOf<String, Any?>("name" to name, "address" to address, "tag" to tag, "type" to type, "direction" to direction, "safe" to typedSafe(type, map["safe"]))
        (map["udt"] as? String)?.let { row["udt"] = it }
        (map["member"] as? String)?.let { row["member"] = it }
        row
    }
}

private fun udtBindings(raw: Any?): List<UdtBinding> =
    (raw as? List<*>).orEmpty().map { item ->
        val m = item as? Map<*, *> ?: throw IllegalArgumentException("udts entries must be objects")
        val variable = (m["variable"] as? String)?.trim().orEmpty()
        val instance = (m["instance"] as? String)?.trim().orEmpty()
        if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(variable)) throw IllegalArgumentException("udts variable must be a Structured Text name")
        if (instance.isEmpty()) throw IllegalArgumentException("udts instance is required for $variable")
        val members = (m["members"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val dir = v as? String ?: return@mapNotNull null
            if (dir !in setOf("in", "out", "none", "auto")) throw IllegalArgumentException("member direction must be in, out, none or auto")
            if (dir == "auto") null else k.toString() to dir
        }.toMap()
        UdtBinding(variable, instance, members)
    }.distinctBy { it.variable.lowercase() }

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
            udt = it["udt"] as? String,
            member = it["member"] as? String,
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
        val objects = mutableListOf(obj.toString())
        // Process-library blocks (ERIC_PID ...) are declared to the program through -i; their bodies are their own units.
        processLibraryUnits(plc).forEach { unit ->
            val unitObj = out.resolve(unit.name.removeSuffix(".st") + ".o")
            run(listOf(plc.path, unit.absolutePath, "-i", "${include.path}/*.st", "-c", "--fpic", "-o", unitObj.toString()))
            objects += unitObj.toString()
        }
        val lld = bundledLinuxLinker(plc)
        if (lld != null) {
            // The bundled LLVM linker needs no C toolchain: the program's libc and libm calls resolve
            // against the scan engine process when it loads the library.
            run(listOf(lld.path, "-shared", "-o", so.toString()) + objects + listOf("-L", lib.path, "-liec61131std", "-rpath", "\$ORIGIN"))
        } else {
            val linker = linuxLinker()
                ?: throw IllegalArgumentException(
                    "The PLC linker (plc/llvm/ld.lld) is missing from this ERIC install. Reinstall ERIC, then deploy again.",
                )
            run(listOf(linker, "-shared", "-o", so.toString()) + objects + listOf("-L", lib.path, "-liec61131std", "-Wl,-rpath,\$ORIGIN"))
        }
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
        /** `ERIC_PLC_COMPILER`, or the RuSTy compiler bundled in the install's `plc/rusty` folder (Linux and Windows). */
        fun fromEnvironment(): PlcCompiler = PlcCompiler(LegacyEnv.current["OIP_PLC_COMPILER"]?.takeIf { it.isNotBlank() } ?: bundledCompiler())

        fun bundledCompiler(): String? {
            val name = if (System.getProperty("os.name").orEmpty().contains("win", true)) "plc.exe" else "plc"
            return Bundled.dir("plc")?.resolve("rusty")?.resolve(name)?.toFile()?.takeIf { it.isFile }?.absolutePath
        }
    }
}

/** Standard-library and process-library globs that sit beside a bundled `plc` or `plc.exe`. */
internal fun compilerIncludes(binary: java.io.File): List<String> {
    val parent = binary.parentFile ?: return emptyList()
    val dirs = listOfNotNull(parent.resolve("stdlib/include"), parent.parentFile?.resolve("st"))
    return dirs.filter { it.isDirectory }.flatMap { listOf("-i", it.absolutePath + java.io.File.separator + "*.st") }
}

/** The process library's own `.st` units, beside a bundled compiler (`plc/rusty/plc` -> `plc/st`). */
internal fun processLibraryUnits(binary: java.io.File): List<java.io.File> {
    val st = binary.parentFile?.parentFile?.resolve("st") ?: return emptyList()
    return st.listFiles { f -> f.isFile && f.name.endsWith(".st") }?.sortedBy { it.name }.orEmpty()
}

/** `plc/llvm/ld.lld` shipped beside the bundled compiler (`plc/rusty/plc`). */
internal fun bundledLinuxLinker(binary: java.io.File): java.io.File? =
    binary.absoluteFile.parentFile?.parentFile?.resolve("llvm")?.resolve("ld.lld")?.takeIf { it.isFile && it.canExecute() }

/** The first C compiler driver on PATH that can link a shared library (used only without the bundled linker). */
internal fun linuxLinker(path: String? = System.getenv("PATH")): String? {
    val dirs = path.orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }
    return listOf("cc", "gcc", "clang").firstOrNull { name -> dirs.any { java.io.File(it, name).canExecute() } }
}

/** Process-library include only. The Linux link step already names the standard library itself. */
internal fun libraryIncludes(binary: java.io.File): List<String> {
    val st = binary.parentFile?.parentFile?.resolve("st") ?: return emptyList()
    if (!st.isDirectory) return emptyList()
    return listOf("-i", st.absolutePath + java.io.File.separator + "*.st")
}

internal const val DEFAULT_PLC_PERIOD_MS: Int = 100
internal const val DEFAULT_PLC_MAX_CONSECUTIVE_OVERRUNS: Int = 10

internal data class ScanSettings(val periodMs: Int, val maxConsecutiveOverruns: Int)

internal data class PresentedLogicHealth(val state: String, val message: String?, val fault: String?)

/**
 * The state the Logic page should show. A watchdog latch or a dead engine process must not stay "running".
 */
internal fun presentLogicHealth(
    currentState: String,
    currentMessage: String,
    remote: Map<String, Any?>?,
    engineAlive: Boolean?,
    exitMessage: String?,
    statusWatchdog: Boolean,
): PresentedLogicHealth {
    val remoteState = remote?.get("state") as? String
    val remoteWatchdog = remote?.get("watchdog") == true || remote?.get("fault") == "watchdog"
    val watchdog = statusWatchdog || remoteWatchdog
    if (engineAlive == false) {
        return if (watchdog || currentState == "faulted" && currentMessage == "watchdog") {
            PresentedLogicHealth("faulted", "watchdog", "watchdog")
        } else {
            PresentedLogicHealth("stopped", exitMessage?.ifBlank { null } ?: "the scan engine stopped", null)
        }
    }
    if (watchdog) {
        return PresentedLogicHealth("faulted", "watchdog", "watchdog")
    }
    if (remoteState != null) {
        val message = when (remoteState) {
            "faulted" -> (remote?.get("fault") as? String)?.ifBlank { null } ?: currentMessage
            else -> if (remoteState == "running") "" else currentMessage
        }
        val fault = if (remoteState == "faulted") (remote?.get("fault") as? String) else null
        return PresentedLogicHealth(remoteState, message, fault)
    }
    return PresentedLogicHealth(currentState, currentMessage, if (currentState == "faulted") currentMessage.ifBlank { null } else null)
}

internal fun scanSettings(primary: Map<String, Any?>? = null, fallback: Map<String, Any?>? = null): ScanSettings {
    val period = intSetting(primary?.get("periodMs") ?: fallback?.get("periodMs"), DEFAULT_PLC_PERIOD_MS, 1, 60_000)
    val overruns = intSetting(
        primary?.get("maxConsecutiveOverruns") ?: fallback?.get("maxConsecutiveOverruns"),
        DEFAULT_PLC_MAX_CONSECUTIVE_OVERRUNS,
        0,
        1_000_000,
    )
    return ScanSettings(period, overruns)
}

internal fun intSetting(raw: Any?, default: Int, min: Int, max: Int): Int {
    val n = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull()
        else -> null
    } ?: return default
    return n.coerceIn(min, max)
}

/** Coerce a mapping safe value to a JSON boolean or number so the engine does not see the string "0". */
internal fun typedSafe(type: String, raw: Any?): Any? {
    if (raw == null) return if (type.equals("BOOL", ignoreCase = true)) false else 0
    return when (type.uppercase()) {
        "BOOL" -> when (raw) {
            is Boolean -> raw
            is Number -> raw.toLong() != 0L
            is String -> raw.trim().equals("true", ignoreCase = true) || raw.trim() == "1"
            else -> false
        }
        "INT", "DINT" -> when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull() ?: raw
            else -> raw
        }
        "REAL", "LREAL" -> when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: raw
            else -> raw
        }
        else -> raw
    }
}
