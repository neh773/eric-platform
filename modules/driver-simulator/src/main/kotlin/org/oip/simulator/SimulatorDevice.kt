package org.oip.simulator

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.withLock

/**
 * One node in the device address tree. [nodeClass] is `Object` for a folder and `Variable` for a
 * value. This is the browse shape an OPC client walks; the gateway catalog is outside this module.
 *
 */
data class SimBrowseNode(
    val nodeId: String,
    val browseName: String,
    val nodeClass: String,
    val readable: Boolean,
    val writable: Boolean,
    val dataType: String? = null,
)

/**
 * One programmable simulator device. Addresses are the program's browse paths. [read] and [scan]
 * advance the program to [clock] and return the current value. A [write] keeps the written value
 * until the program next publishes that address.
 *
 * Meta tags live under `[Controls]` (Base Rate, Pause, Program Counter, Repeat, Reset). They
 * change the running program and are not part of the instruction list, so they do not survive a
 * restart. [browse] walks folders created by `/` in the browse path, plus `[Controls]`.
 *
 * Listeners run on the device lock. They must not call back into this device on another thread
 * that also needs the lock. The tag bridge publishes into the tag engine, which does not take
 * this lock while it holds the engine lock.
 *
 */
class SimulatorDevice(
    val config: SimulatorDeviceConfig,
    private val clock: SimulatorClock = SimulatorClock.SYSTEM,
) : AutoCloseable {
    private val log = Logger.getLogger("org.oip.simulator.${config.name}")
    private val program = compileProgram(config)
    private val origin = clock.millis()
    private val points: Array<RuntimePoint> = program.points.map { RuntimePoint(it) }.toTypedArray()
    private val byPath: HashMap<String, RuntimePoint> = HashMap(points.size * 2)
    private val children = HashMap<String, List<SimBrowseNode>>()
    private val described = HashMap<String, SimBrowseNode>()
    private val groups = HashMap<Long, CopyOnWriteArrayList<Binding>>()
    private val tasks = HashMap<Long, ScheduledFuture<*>>()
    private val lock = ReentrantLock()
    private var scheduler: ScheduledExecutorService? = null
    private var rateMs: Long = config.baseRateMs
    private var repeat: Boolean = config.repeatProgram
    private var paused: Boolean = false
    private var interval: Int = 0
    private var intoInterval: Long = 0
    private var absoluteTick: Long = 0
    private var bootstrapped: Boolean = false
    private var lastClock: Long = origin
    @Volatile private var running = false
    @Volatile private var closed = false

    init {
        for (point in points) {
            byPath.putIfAbsent(point.path, point)
            val item = itemPath(point.path)
            point.itemPath = item
            byPath.putIfAbsent(item, point)
        }
        buildTree()
    }

    fun start() {
        lock.withLock {
            if (closed || running) return
            running = true
            if (scheduler == null || scheduler!!.isShutdown) {
                scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "sim-${config.name}").apply { isDaemon = true }
                }
            }
            for (period in groups.keys.toList()) scheduleLocked(period)
        }
    }

    fun stop() {
        lock.withLock {
            running = false
            tasks.values.forEach { it.cancel(false) }
            tasks.clear()
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            running = false
            tasks.values.forEach { it.cancel(false) }
            tasks.clear()
            groups.clear()
            val pool = scheduler
            scheduler = null
            pool?.shutdownNow()
        }
    }

    fun read(address: String): Sample {
        lock.withLock {
            syncLocked()
            if (address in CONTROL_VALUES) return Sample(controlValue(address), Quality.GOOD, clock.millis())
            val point = byPath[address] ?: return Sample(null, Quality.NOT_FOUND, clock.millis())
            return Sample(point.displayed(), point.quality, clock.millis())
        }
    }

    fun write(address: String, value: Any?): WriteResult {
        lock.withLock {
            syncLocked()
            if (address in CONTROL_VALUES) return writeControl(address, value)
            val point = byPath[address] ?: return WriteResult(address, Quality.NOT_FOUND)
            if (point.readOnly) return WriteResult(address, Quality.NOT_WRITABLE)
            val coerced = try {
                coerceExact(point.type, value)
            } catch (e: IllegalArgumentException) {
                return WriteResult(address, Quality.typeMismatch(e.message ?: "type"))
            }
            point.overrideOn = true
            point.overrideValue = coerced
            // A successful write is Good until the program next publishes this address.
            point.quality = Quality.GOOD
            return WriteResult(address, Quality.GOOD)
        }
    }

    /** Instruction rows as CSV. A stored `_Meta:` prefix is removed from browse paths. */
    fun exportProgram(): String = ProgramCsv.export(config.instructions)

    /**
     * Children of [nodeId]. An empty id is the device root. Folders come from `/` in the item path.
     * With [SimulatorDeviceConfig.legacyItemPaths], node ids start with [LEGACY_ITEM_PREFIX] and the
     * same tree can be browsed without that prefix.
     */
    fun browse(nodeId: String = ""): List<SimBrowseNode> {
        lock.withLock {
            syncLocked()
            val key = resolveBrowseKey(nodeId.trim())
            val nodes = children[key] ?: return emptyList()
            return nodes.map { live(it) }
        }
    }

    /** The node [nodeId], or null when it is not a folder or variable of this device. */
    fun describe(nodeId: String): SimBrowseNode? {
        lock.withLock {
            syncLocked()
            val key = resolveBrowseKey(nodeId.trim())
            val node = described[key] ?: return null
            return live(node)
        }
    }

    /**
     * Publish every subscription at [periodMs]. The scheduler calls this; tests call it after
     * moving a [ManualSimulatorClock]. Safe to call when the device was not [start]ed.
     */
    fun scan(periodMs: Long) {
        if (closed) return
        lock.withLock {
            if (closed) return
            syncLocked()
            val group = groups[periodMs] ?: return
            val epoch = clock.millis()
            for (binding in group) {
                val point = binding.point
                val value = if (point != null) point.displayed() else controlValue(binding.control!!)
                val quality = point?.quality ?: Quality.GOOD
                for (listener in binding.listeners) {
                    try {
                        listener(value, quality, epoch)
                    } catch (e: Throwable) {
                        log.log(Level.FINE, point?.path ?: binding.control ?: "", e)
                    }
                }
            }
        }
    }

    /**
     * Shortest period already publishing [address], so a second subscriber joins that scan.
     * Null when nothing is attached. Safe to call off the device scheduler.
     */
    fun activeScanPeriodMs(address: String): Long? {
        lock.withLock {
            var found: Long? = null
            for ((period, bindings) in groups) {
                val hit = bindings.any { binding ->
                    val point = binding.point
                    binding.control == address || point?.path == address || point?.itemPath == address
                }
                if (!hit) continue
                if (found == null || period < found) found = period
            }
            return found
        }
    }

    /**
     * In-process status for `Gateway/Device/<name>/Diagnostics`. There is no socket, so request,
     * error, and timeout counts stay at zero. Sampling and publish are the current base rate.
     */
    fun diagnostics(): SimulatorDiagnostics {
        val rate = lock.withLock { rateMs.toDouble() }
        return SimulatorDiagnostics(
            connected = running && !closed,
            requestCount = 0,
            errorCount = 0,
            timeoutCount = 0,
            averageResponseTimeMs = null,
            samplingIntervalMs = rate,
            publishIntervalMs = rate,
        )
    }

    fun attach(periodMs: Long, address: String, listener: (Any?, Quality, Long) -> Unit): AutoCloseable {
        val period = periodMs.coerceAtLeast(1)
        var handle = AutoCloseable { }
        var value: Any? = null
        var quality = Quality.NOT_FOUND
        var epoch = 0L
        lock.withLock {
            if (closed) return handle
            syncLocked()
            epoch = clock.millis()
            val control = address in CONTROL_VALUES
            val point = if (control) null else byPath[address]
            if (!control && point == null) {
                value = null
                quality = Quality.NOT_FOUND
            } else {
                val group = groups.getOrPut(period) { CopyOnWriteArrayList() }
                var binding = group.firstOrNull { if (control) it.control == address else it.point === point }
                if (binding == null) {
                    binding = Binding(point, if (control) address else null)
                    group.add(binding)
                }
                binding.listeners.add(listener)
                if (running) scheduleLocked(period)
                value = if (control) controlValue(address) else point!!.displayed()
                quality = if (control) Quality.GOOD else point!!.quality
                val added = binding
                val listed = group
                handle = AutoCloseable {
                    lock.withLock {
                        if (!added.listeners.remove(listener)) return@withLock
                        if (added.listeners.isEmpty()) {
                            listed.remove(added)
                            if (listed.isEmpty() && groups[period] === listed) {
                                groups.remove(period)
                                tasks.remove(period)?.cancel(false)
                            }
                        }
                    }
                }
            }
        }
        try {
            listener(value, quality, epoch)
        } catch (e: Throwable) {
            log.log(Level.FINE, address, e)
        }
        return handle
    }

    private fun scheduleLocked(period: Long) {
        if (period in tasks) return
        val pool = scheduler ?: return
        val task = pool.scheduleAtFixedRate({
            try {
                scan(period)
            } catch (e: Throwable) {
                log.log(Level.FINE, "scan $period", e)
            }
        }, period, period, TimeUnit.MILLISECONDS)
        tasks[period] = task
    }

    private fun syncLocked() {
        val now = clock.millis()
        if (!bootstrapped) {
            bootstrapped = true
            interval = 0
            absoluteTick = 0
            applyInterval(0, 0)
        }
        var delta = now - lastClock
        if (delta < 0) delta = 0
        lastClock = now
        if (paused) return
        if (delta == 0L && intoInterval < rateMs) return
        intoInterval += delta
        catchUp()
    }

    /**
     * Move one interval each time [intoInterval] covers the current base rate.
     * A smaller base rate consumes time already spent in this interval. Pause does not call this.
     * When the program does not repeat and the last interval is already showing, leftover time is
     * dropped so the counter stays on that interval.
     */
    private fun catchUp() {
        var guard = 0
        while (intoInterval >= rateMs) {
            if (!step()) {
                intoInterval = 0
                return
            }
            intoInterval -= rateMs
            if (++guard > 1_000_000) return
        }
    }

    private fun step(): Boolean {
        if (!repeat && interval >= program.span - 1) return false
        absoluteTick++
        interval = if (interval + 1 >= program.span) 0 else interval + 1
        applyInterval(absoluteTick, interval)
        return true
    }

    private fun applyInterval(tick: Long, at: Int) {
        for (point in points) point.onTick(tick, at)
    }

    private fun restartLocked() {
        absoluteTick = 0
        interval = 0
        intoInterval = 0
        for (point in points) point.reset()
        applyInterval(0, 0)
    }

    private fun controlValue(address: String): Any = when (address) {
        BASE_RATE -> rateMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        PAUSE -> paused
        PROGRAM_COUNTER -> interval
        REPEAT -> repeat
        RESET -> false
        else -> error(address)
    }

    private fun writeControl(address: String, value: Any?): WriteResult {
        when (address) {
            BASE_RATE -> {
                val rate = try {
                    coerceExact(SimType.INT32, value) as Int
                } catch (e: IllegalArgumentException) {
                    return WriteResult(address, Quality.typeMismatch(e.message ?: "type"))
                }
                if (rate < 1 || rate > MAX_BASE_RATE_MS) {
                    return WriteResult(address, Quality.typeMismatch("base rate must be 1..$MAX_BASE_RATE_MS ms"))
                }
                rateMs = rate.toLong()
                if (!paused) catchUp()
            }
            PAUSE -> {
                paused = try {
                    coerceExact(SimType.BOOLEAN, value) as Boolean
                } catch (e: IllegalArgumentException) {
                    return WriteResult(address, Quality.typeMismatch(e.message ?: "type"))
                }
            }
            REPEAT -> {
                repeat = try {
                    coerceExact(SimType.BOOLEAN, value) as Boolean
                } catch (e: IllegalArgumentException) {
                    return WriteResult(address, Quality.typeMismatch(e.message ?: "type"))
                }
            }
            RESET -> {
                val flag = try {
                    coerceExact(SimType.BOOLEAN, value) as Boolean
                } catch (e: IllegalArgumentException) {
                    return WriteResult(address, Quality.typeMismatch(e.message ?: "type"))
                }
                if (flag) restartLocked()
            }
            PROGRAM_COUNTER -> return WriteResult(address, Quality.NOT_WRITABLE)
        }
        return WriteResult(address, Quality.GOOD)
    }

    private fun itemPath(browsePath: String): String {
        if (!config.legacyItemPaths || browsePath.startsWith(LEGACY_ITEM_PREFIX)) return browsePath
        return LEGACY_ITEM_PREFIX + browsePath
    }

    private fun resolveBrowseKey(nodeId: String): String {
        if (nodeId.isEmpty() || nodeId in children || nodeId in described) return nodeId
        if (config.legacyItemPaths && !nodeId.startsWith(LEGACY_ITEM_PREFIX) && !nodeId.startsWith(CONTROLS)) {
            val prefixed = LEGACY_ITEM_PREFIX + nodeId
            if (prefixed in children || prefixed in described) return prefixed
        }
        return nodeId
    }

    private fun live(node: SimBrowseNode): SimBrowseNode {
        if (node.nodeClass != "Variable" || node.nodeId.startsWith(CONTROLS)) return node
        val point = byPath[node.nodeId] ?: return node
        return if (point.readOnly == !node.writable) node else node.copy(writable = !point.readOnly)
    }

    private fun buildTree() {
        val root = Folder("", "")
        val controls = Folder(CONTROLS, CONTROLS)
        controls.variables[BASE_RATE] = leaf(BASE_RATE, "Base Rate", "Int32", writable = true)
        controls.variables[PAUSE] = leaf(PAUSE, "Pause", "Boolean", writable = true)
        controls.variables[PROGRAM_COUNTER] = leaf(PROGRAM_COUNTER, "Program Counter", "Int32", writable = false)
        controls.variables[REPEAT] = leaf(REPEAT, "Repeat", "Boolean", writable = true)
        controls.variables[RESET] = leaf(RESET, "Reset", "Boolean", writable = true)
        root.folders[CONTROLS] = controls
        for (point in points) {
            val item = point.itemPath
            val parts = item.split('/')
            var folder = root
            var acc = ""
            for (index in 0 until parts.lastIndex) {
                val segment = parts[index]
                acc = if (acc.isEmpty()) segment else "$acc/$segment"
                val name = segment.removePrefix(LEGACY_ITEM_PREFIX)
                folder = folder.folders.getOrPut(segment) { Folder(acc, name) }
            }
            val leafName = parts.last()
            folder.variables[item] = leaf(item, leafName, point.dataTypeName, writable = true)
        }
        freeze(root)
    }

    private fun leaf(nodeId: String, browseName: String, dataType: String, writable: Boolean) =
        SimBrowseNode(nodeId, browseName, "Variable", readable = true, writable = writable, dataType = dataType)

    private fun freeze(folder: Folder) {
        val nodes = ArrayList<SimBrowseNode>(folder.folders.size + folder.variables.size)
        for (child in folder.folders.values) {
            val node = SimBrowseNode(child.nodeId, child.browseName, "Object", readable = false, writable = false, dataType = null)
            described[child.nodeId] = node
            nodes += node
            freeze(child)
        }
        for (variable in folder.variables.values) {
            described[variable.nodeId] = variable
            nodes += variable
        }
        children[folder.nodeId] = nodes.sortedBy { it.browseName }
    }

    private class Folder(val nodeId: String, val browseName: String) {
        val folders = LinkedHashMap<String, Folder>()
        val variables = LinkedHashMap<String, SimBrowseNode>()
    }

    private class Binding(val point: RuntimePoint?, val control: String?) {
        val listeners = CopyOnWriteArrayList<(Any?, Quality, Long) -> Unit>()
    }

    private class RuntimePoint(compiled: CompiledPoint) {
        val path: String = compiled.path
        val type: SimType = compiled.type
        val dataTypeName: String = compiled.dataTypeName
        var itemPath: String = compiled.path
        private val initial: Any = compiled.initial
        private val fires: IntArray = compiled.fires
        private val generators: Array<Generator> = compiled.generators
        var programValue: Any = compiled.initial
        var overrideOn: Boolean = false
        var overrideValue: Any? = null
        var quality: Quality = Quality.GOOD
        var readOnly: Boolean = false
        private var generator: Generator? = null
        private var startedAt: Long = 0
        private var publishesEveryTick: Boolean = false
        private var rng: java.util.Random? = null
        private var drawn: Long = -1
        private var drawnValue: Any? = null
        private var pidSteps: Long = -1
        private var pidPv: Double = 0.0
        private var pidIntegral: Double = 0.0
        private var pidLastError: Double = 0.0
        private var pidHaveError: Boolean = false

        fun displayed(): Any? = if (overrideOn) overrideValue else programValue

        fun reset() {
            generator = null
            publishesEveryTick = false
            overrideOn = false
            overrideValue = null
            programValue = initial
            quality = Quality.GOOD
            readOnly = false
            rng = null
            drawn = -1L
            drawnValue = null
            pidSteps = -1L
            pidPv = 0.0
            pidIntegral = 0.0
            pidLastError = 0.0
            pidHaveError = false
        }

        fun onTick(tick: Long, interval: Int) {
            val fired = firing(interval)
            if (fired != null) {
                if (generator === fired) {
                    if (fired.repeats && publishesEveryTick) {
                        continueAt(tick)
                        return
                    }
                    // The same preloaded writable constant is already published. Re-entering
                    // interval 0 is not a new publish, so a write survives later base-rate ticks.
                    // A user program leaves persistWrite false and still replaces the write.
                    // doc source: FEATURES DEV-028
                    // doc source: FEATURES DEV-028
                    if (fired is ConstantGen && fired.persistWrite) return
                }
                activate(fired, tick)
                return
            }
            if (publishesEveryTick) continueAt(tick)
        }

        private fun firing(interval: Int): Generator? {
            for (i in fires.indices) if (fires[i] == interval) return generators[i]
            return null
        }

        private fun activate(fired: Generator, tick: Long) {
            generator = fired
            startedAt = tick
            publishesEveryTick = fired.repeats
            if (fired is RandomGen) {
                rng = java.util.Random(fired.seed)
                drawn = -1L
                drawnValue = null
            }
            if (fired is RealisticGen) {
                pidSteps = -1L
                pidPv = 0.0
                pidIntegral = 0.0
                pidLastError = 0.0
                pidHaveError = false
            }
            programValue = eval(fired, 0L)
            overrideOn = false
            note(fired)
        }

        private fun continueAt(tick: Long) {
            val gen = generator ?: return
            programValue = eval(gen, tick - startedAt)
            overrideOn = false
            note(gen)
        }

        private fun note(gen: Generator) {
            quality = if (gen is QvGen) gen.quality else Quality.GOOD
            readOnly = gen is ReadOnlyGen
        }

        private fun eval(gen: Generator, k: Long): Any = when (gen) {
            is ConstantGen -> gen.value
            is WaveGen -> boxWave(type, gen.wave(k))
            is ListGen -> gen.at(k)
            is RandomGen -> randomAt(gen, k)
            is RealisticGen -> boxWave(type, realisticAt(gen, k))
            is QvGen -> gen.value
            is ReadOnlyGen -> gen.value
        }

        private fun randomAt(gen: RandomGen, k: Long): Any {
            val random = rng ?: java.util.Random(gen.seed).also { rng = it }
            while (drawn < k) {
                drawn++
                drawnValue = drawRandom(random, gen)
            }
            return drawnValue ?: zeroOf(type)
        }

        private fun realisticAt(gen: RealisticGen, k: Long): Double {
            while (pidSteps < k) {
                pidSteps++
                val error = gen.setPoint - pidPv
                pidIntegral += error
                val deriv = if (!pidHaveError) 0.0 else error - pidLastError
                pidHaveError = true
                val output = gen.proportion * error + gen.integral * pidIntegral + gen.derivative * deriv
                pidPv += output * REALISTIC_PLANT_GAIN
                pidLastError = error
            }
            return pidPv
        }
    }

    private companion object {
        const val CONTROLS = "[Controls]"
        const val BASE_RATE = "[Controls]/Base Rate"
        const val PAUSE = "[Controls]/Pause"
        const val PROGRAM_COUNTER = "[Controls]/Program Counter"
        const val REPEAT = "[Controls]/Repeat"
        const val RESET = "[Controls]/Reset"
        const val MAX_BASE_RATE_MS = 86_400_000
        val CONTROL_VALUES = setOf(BASE_RATE, PAUSE, PROGRAM_COUNTER, REPEAT, RESET)
    }
}

/**
 * Figures for `Gateway/Device/<name>/Diagnostics` on a programmable simulator.
 * Request, error, and timeout counts stay at zero: the device has no wire protocol.
 */
data class SimulatorDiagnostics(
    val connected: Boolean,
    val requestCount: Long,
    val errorCount: Long,
    val timeoutCount: Long,
    val averageResponseTimeMs: Double?,
    val samplingIntervalMs: Double,
    val publishIntervalMs: Double,
)
