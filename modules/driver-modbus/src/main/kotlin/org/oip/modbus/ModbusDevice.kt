package org.oip.modbus

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A Modbus TCP or RTU-over-TCP client. Implements [TagSource] so the tag engine can adapt it
 * without this module depending on gateway/tags.
 *
 * Scan classes are named poll periods on this device. [subscribe] with a sampling interval uses
 * the scan class of that period, creating one named `ms-<period>` when needed. Reads of items in
 * the same class are coalesced by [RequestOptimizer].
 *
 * A supervisor reconnects with exponential backoff. After the first successful connection, a drop
 * sets every subscribed value to [ModbusDeviceConfig.commsLossQuality] and keeps the last value.
 */
class ModbusDevice(val config: ModbusDeviceConfig) : TagSource, AutoCloseable {
    private val log = Logger.getLogger("org.oip.modbus.${config.name}")
    private val session = ModbusSession(config)
    private val cache = ConcurrentHashMap<String, QualifiedValue>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(QualifiedValue) -> Unit>>()
    /** Listeners whose address did not parse. Parsed addresses keep the listener on [ParsedAddress]. */
    private val rawListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<RawSample>>()
    private val parsedByRaw = ConcurrentHashMap<String, ParsedAddress>()
    private val parseFailed = ConcurrentHashMap<String, String>()
    private val plans = ConcurrentHashMap<String, ScanPlan>()
    private val planGeneration = AtomicLong()
    private val subs = CopyOnWriteArrayList<LiveSub>()
    /** Non-shared subscriptions. The raw tag path skips the subscriber walk when this is zero. */
    private val directSubs = java.util.concurrent.atomic.AtomicInteger()
    private val sharedSubs = ConcurrentHashMap<String, LiveSub>()
    private val periods = ConcurrentHashMap<String, Long>()
    private val tasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val scanning = ConcurrentHashMap<String, AtomicBoolean>()
    private val statusListeners = CopyOnWriteArrayList<(ConnectionStatus) -> Unit>()
    private val addressMap = CopyOnWriteArrayList<String>()
    private val delays = CopyOnWriteArrayList<Long>()
    private val readPdus = AtomicLong(0)

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "modbus-scan-${config.name}").apply { isDaemon = true }
    }
    /** Independent blocks on one TCP socket. RTU stays on the caller thread (one request in flight). */
    private val io: ExecutorService? =
        if (config.mode == ModbusMode.TCP && config.concurrentRequests > 1)
            Executors.newFixedThreadPool(config.concurrentRequests.coerceAtMost(32)) { r ->
                Thread(r, "modbus-io-${config.name}").apply { isDaemon = true }
            }
        else null

    @Volatile private var started = false
    @Volatile private var everConnected = false
    @Volatile private var supervisor: Thread? = null
    @Volatile private var status = ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now())

    override val sourceId: String get() = config.name

    /** Backoff delays chosen after failed connect attempts, in order. Exposed for tests. */
    val reconnectDelaysMs: List<Long> get() = delays.toList()

    /** Read PDUs sent (after coalescing). Exposed so tests can see request optimization. */
    val readRequestCount: Long get() = readPdus.get()

    /**
     * Period of a scan that already reads [address], or another spelling of the same point
     * (same unit, area, type, offset, span, and bit — `HR1` and `1.HR1` on unit 1).
     * The fastest such scan when several exist, so a new listener joins that poll instead of
     * opening a second request. Null when nothing is polling the point.
     */
    fun activeScanPeriodMs(address: String): Long? {
        val target = parseCached(address)
        var best: Long? = null
        for (sub in subs) {
            if (!covers(sub, address, target)) continue
            val period = periods[sub.scanClass] ?: continue
            if (best == null || period < best) best = period
        }
        return best
    }

    /** Highest number of TCP requests in flight. Stays at 1 for RTU over TCP. */
    val peakInFlight: Int get() = session.peakInFlight.get()

    init {
        periods["Default"] = config.defaultScanPeriodMs
    }

    fun defineScanClass(name: String, periodMs: Long) {
        require(name.isNotBlank())
        require(periodMs >= 10) { "scan class period must be >= 10 ms" }
        periods[name] = periodMs
        if (started) schedule(name, periodMs)
    }

    fun setAddressMap(addresses: List<String>) {
        addressMap.clear()
        addressMap.addAll(addresses)
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        everConnected = false
        supervisor = Thread({ supervise() }, "modbus-conn-${config.name}").apply { isDaemon = true; start() }
        periods.forEach { (name, period) -> schedule(name, period) }
    }

    @Synchronized
    fun stop() {
        if (!started && supervisor == null) return
        started = false
        supervisor?.interrupt()
        tasks.values.forEach { it.cancel(false) }
        tasks.clear()
        session.close()
        supervisor?.join(1_000)
        supervisor = null
        setStatus(status.copy(state = ConnectionState.DISCONNECTED, connected = false, since = Instant.now()))
    }

    override fun close() {
        stop()
        scheduler.shutdownNow()
        io?.shutdownNow()
    }

    override fun connectionStatus(): ConnectionStatus = status

    /**
     * Device diagnostic snapshot. Reads atomics and the subscription list; it does not touch the socket.
     *
     * Tag paths reserve `[]` for the provider and for array indexes, so the gateway publishes
     * these figures at `Gateway/Device/<name>/Diagnostics` (DIFFERENCES 311).
     *
     * [DeviceDiagnostics.requestCount] is PDUs issued since the device started. It moves with scan
     * traffic. Sampling and publish intervals are the fastest active scan class, in milliseconds.
     * Modbus delivers on each scan, so the publish interval is that scan period (DIFFERENCES 312, 314).
     */
    fun diagnostics(): DeviceDiagnostics {
        var sampling: Double? = null
        for (sub in subs) {
            if (sub.addresses.isEmpty()) continue
            val period = sub.samplingIntervalMs
            val current = sampling
            if (current == null || period < current) sampling = period
        }
        val interval = sampling ?: config.defaultScanPeriodMs.toDouble()
        return DeviceDiagnostics(
            connected = status.connected,
            state = status.state.name,
            requestCount = session.stats.requestCount(),
            errorCount = session.stats.errorCount(),
            timeoutCount = session.stats.timeoutCount(),
            averageResponseTimeMs = session.stats.averageMillis(),
            samplingIntervalMs = interval,
            publishIntervalMs = interval,
        )
    }

    override fun subscribeStatus(listener: (ConnectionStatus) -> Unit): AutoCloseable {
        statusListeners += listener
        listener(status)
        return AutoCloseable { statusListeners -= listener }
    }

    override fun browse(nodeId: String): List<SourceNode> {
        if (nodeId.isNotBlank()) return emptyList()
        val ids = LinkedHashSet<String>()
        ids.addAll(addressMap)
        subs.forEach { ids.addAll(it.addresses) }
        return ids.map { addr ->
            val parsed = runCatching { ModbusAddress.parse(addr, config) }.getOrNull()
            SourceNode(addr, addr, "Variable", readable = parsed != null, writable = parsed?.writable == true)
        }
    }

    override fun read(nodeIds: List<String>): List<QualifiedValue> {
        val now = Instant.now()
        val parsed = ArrayList<ParsedAddress>(nodeIds.size)
        val early = HashMap<String, QualifiedValue>()
        for (raw in nodeIds) {
            if (raw in early) continue
            val item = parseCached(raw)
            if (item == null) {
                early[raw] = sample(raw, null, Quality.of(QualityCode.Bad_ConfigurationError, parseFailed[raw]), now, replace = true)
            } else {
                parsed += item
            }
        }
        val produced = HashMap<String, QualifiedValue>(early)
        if (parsed.isNotEmpty()) {
            if (!session.isConnected()) {
                for (item in parsed) produced[item.raw] = lost(item.raw, now, "not connected")
            } else {
                val plan = RequestOptimizer.plan(parsed, config)
                for (item in plan.rejected) {
                    produced[item.raw] = sample(item.raw, null, Quality.of(QualityCode.Bad_ConfigurationError, "span exceeds max request"), now, replace = true)
                }
                for (part in executeBlocks(plan.blocks)) produced.putAll(part)
            }
        }
        return nodeIds.map { produced.getValue(it) }
    }

    /**
     * Read [nodeIds] once without remembering them: no parse-cache entries and no cached values, so
     * walking a device's address space from the tag browser does not grow this device's maps.
     */
    fun probe(nodeIds: List<String>): List<QualifiedValue> {
        val now = Instant.now()
        val out = HashMap<String, QualifiedValue>()
        val items = ArrayList<ParsedAddress>()
        for (raw in nodeIds.distinct()) {
            val item = parsedByRaw[raw] ?: try {
                ModbusAddress.parse(raw, config)
            } catch (e: AddressException) {
                out[raw] = QualifiedValue(raw, null, Quality.of(QualityCode.Bad_ConfigurationError, e.message), now, null)
                null
            }
            if (item != null) items += item
        }
        if (items.isNotEmpty()) {
            if (!session.isConnected()) {
                for (item in items) out[item.raw] = QualifiedValue(item.raw, null, Quality.of(config.commsLossQuality, "not connected"), now, null)
            } else {
                val plan = RequestOptimizer.plan(items, config)
                for (item in plan.rejected) {
                    out[item.raw] = QualifiedValue(item.raw, null, Quality.of(QualityCode.Bad_ConfigurationError, "span exceeds max request"), now, null)
                }
                for (block in plan.blocks) out.putAll(probeBlock(block))
            }
        }
        return nodeIds.map { out.getValue(it) }
    }

    private fun probeBlock(block: ReadBlock): Map<String, QualifiedValue> {
        val out = HashMap<String, QualifiedValue>(block.items.size)
        try {
            val pdu = session.transact(block.unitId, ModbusPdu.read(block.area, block.start, block.quantity))
            readPdus.incrementAndGet()
            val data = ModbusPdu.payload(pdu)
            val stamp = Instant.now()
            for (item in block.items) {
                out[item.raw] = try {
                    QualifiedValue(item.raw, ModbusCodec.decode(item, block.start, data, config), Quality.of(QualityCode.Good), stamp, null)
                } catch (e: TypeMismatchException) {
                    QualifiedValue(item.raw, null, Quality.of(QualityCode.Bad_TypeMismatch, e.message), stamp, null)
                }
            }
        } catch (e: Exception) {
            val q = Quality.of(qualityFor(e), e.message)
            val stamp = Instant.now()
            for (item in block.items) out[item.raw] = QualifiedValue(item.raw, null, q, stamp, null)
        }
        return out
    }

    override fun write(nodeId: String, value: Any?): WriteResult {
        val now = Instant.now()
        val item = parseCached(nodeId) ?: run {
            val message = parseFailed[nodeId]
            return WriteResult(nodeId, Quality.of(QualityCode.Bad_ConfigurationError, message), now)
        }
        if (!item.writable) return WriteResult(nodeId, Quality.of(QualityCode.Bad_NotWritable, "area is read-only"), now)
        if (!session.isConnected()) return WriteResult(nodeId, Quality.of(config.commsLossQuality, "not connected"), now)
        return try {
            when {
                item.type == ModbusType.BIT -> writeBit(item, value)
                item.area == ModbusArea.COIL -> writeCoil(item, value)
                else -> writeRegisters(item, value)
            }
            val stamp = Instant.now()
            val decoded = if (item.type == ModbusType.BIT || item.type == ModbusType.BOOLEAN) value as Boolean else value
            sample(item.raw, decoded, Quality.of(QualityCode.Good), stamp, replace = true)
            WriteResult(nodeId, Quality.of(QualityCode.Good), stamp)
        } catch (e: TypeMismatchException) {
            WriteResult(nodeId, Quality.of(QualityCode.Bad_TypeMismatch, e.message), Instant.now())
        } catch (e: Exception) {
            WriteResult(nodeId, Quality.of(qualityFor(e), e.message), Instant.now())
        }
    }

    /** Subscribe [addresses] on a named scan class. The class must already exist or be the Default class. */
    fun subscribe(addresses: List<String>, scanClass: String, listener: (QualifiedValue) -> Unit): TagSubscription {
        val period = periods[scanClass] ?: throw IllegalArgumentException("unknown scan class '$scanClass'")
        return addSub(addresses, scanClass, period.toDouble(), period.toDouble(), listener)
    }

    override fun subscribe(
        nodeIds: List<String>,
        samplingIntervalMs: Double,
        publishingIntervalMs: Double,
        listener: (QualifiedValue) -> Unit,
    ): TagSubscription {
        val period = samplingIntervalMs.toLong().coerceAtLeast(10)
        val existing = periods.entries.firstOrNull { it.value == period }?.key
        val name = existing ?: "ms-$period".also { defineScanClass(it, period) }
        return addSub(nodeIds, name, period.toDouble(), period.toDouble(), listener)
    }

    /**
     * Add one address to the scan class for [periodMs]. Every address on that class is read in one
     * coalesced pass. The listener is not invoked under a process-wide lock; the socket work stays
     * on this device's scan thread.
     */
    fun attach(periodMs: Long, address: String, listener: (QualifiedValue) -> Unit): AutoCloseable {
        val period = periodMs.coerceAtLeast(10)
        val existing = periods.entries.firstOrNull { it.value == period }?.key
        val name = existing ?: "ms-$period".also { defineScanClass(it, period) }
        val sub = sharedSub(name, period.toDouble())
        sub.addresses.add(address)
        planGeneration.incrementAndGet()
        val list = listeners.computeIfAbsent(address) { CopyOnWriteArrayList() }
        list.add(listener)
        val initial = cache[address] ?: sample(
            address, null, Quality.of(QualityCode.Bad_WaitingForInitialData), Instant.now(), replace = false,
        )
        try { listener(initial) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
        return AutoCloseable {
            list.remove(listener)
            if (list.isEmpty()) {
                listeners.remove(address, list)
                if (parsedByRaw[address]?.hasRaw(name) != true && rawListeners[rawKey(name, address)].isNullOrEmpty()) sub.addresses.remove(address)
                planGeneration.incrementAndGet()
            }
        }
    }

    /**
     * Same coalesced scan as [attach], but the listener receives the decoded value without a
     * [QualifiedValue] or a string-keyed map. This is the gateway tag hot path.
     */
    fun attachRaw(periodMs: Long, address: String, listener: RawSample): AutoCloseable {
        val period = periodMs.coerceAtLeast(10)
        val existing = periods.entries.firstOrNull { it.value == period }?.key
        val name = existing ?: "ms-$period".also { defineScanClass(it, period) }
        val sub = sharedSub(name, period.toDouble())
        sub.addresses.add(address)
        planGeneration.incrementAndGet()
        val parsed = parseCached(address)
        if (parsed == null) {
            val list = rawListeners.computeIfAbsent(rawKey(name, address)) { CopyOnWriteArrayList() }
            list.add(listener)
            val waiting = Quality.of(QualityCode.Bad_WaitingForInitialData)
            try { listener.onSample(null, waiting, System.currentTimeMillis()) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
            return AutoCloseable {
                list.remove(listener)
                if (list.isEmpty()) {
                    rawListeners.remove(rawKey(name, address), list)
                    if (listeners[address].isNullOrEmpty()) sub.addresses.remove(address)
                    planGeneration.incrementAndGet()
                }
            }
        }
        parsed.addRaw(name, listener)
        val waiting = Quality.of(QualityCode.Bad_WaitingForInitialData)
        try { listener.onSample(null, waiting, System.currentTimeMillis()) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
        return AutoCloseable {
            parsed.removeRaw(listener)
            if (!parsed.hasRaw(name) && listeners[address].isNullOrEmpty()) {
                sub.addresses.remove(address)
                planGeneration.incrementAndGet()
            }
        }
    }

    /**
     * Push one decoded sample through the raw listeners. Does not open a socket.
     * The scale test uses this so the allocation check covers the gateway sample path.
     */
    fun inject(address: String, value: Any?, quality: Quality = Quality.of(QualityCode.Good), epochMillis: Long = System.currentTimeMillis()) {
        val parsed = parseCached(address) ?: return
        val scan = parsed.rawOne?.scanClass ?: parsed.rawMore?.firstOrNull()?.scanClass ?: return
        deliver(scan, parsed, value, quality, stampFor(epochMillis), stampFor(epochMillis))
    }

    /** One [Instant] per distinct epoch on this device, matching a poll that stamps a whole block once. */
    private var stampEpoch = Long.MIN_VALUE
    private var stampInstant: Instant? = null

    private fun stampFor(epochMillis: Long): Instant {
        val cached = stampInstant
        if (stampEpoch == epochMillis && cached != null) return cached
        val created = Instant.ofEpochMilli(epochMillis)
        stampEpoch = epochMillis
        stampInstant = created
        return created
    }

    private fun addSub(
        addresses: List<String>,
        scanClass: String,
        sampling: Double,
        publishing: Double,
        listener: (QualifiedValue) -> Unit,
    ): TagSubscription {
        val sub = LiveSub(UUID.randomUUID().toString(), scanClass, addresses, sampling, publishing, listener, shared = false)
        subs += sub
        planGeneration.incrementAndGet()
        for (addr in addresses) {
            val initial = cache[addr] ?: sample(
                addr, null, Quality.of(QualityCode.Bad_WaitingForInitialData), Instant.now(), replace = false,
            )
            listener(initial)
        }
        return sub
    }

    private fun sharedSub(name: String, period: Double): LiveSub = sharedSubs.computeIfAbsent(name) {
        val created = LiveSub(
            UUID.randomUUID().toString(), name, emptyList(), period, period,
            { qv ->
                val list = listeners[qv.address] ?: return@LiveSub
                for (cb in list) {
                    try { cb(qv) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
                }
            },
            shared = true,
        )
        subs += created
        created
    }

    private fun rawKey(scanClass: String, address: String) = "$scanClass\u0000$address"

    private fun covers(sub: LiveSub, address: String, target: ParsedAddress?): Boolean {
        if (address in sub.addresses) return true
        if (target == null) return false
        for (raw in sub.addresses) {
            val parsed = parsedByRaw[raw] ?: parseCached(raw) ?: continue
            if (samePoint(parsed, target)) return true
        }
        return false
    }

    private fun samePoint(a: ParsedAddress, b: ParsedAddress): Boolean =
        a.unitId == b.unitId && a.area == b.area && a.type == b.type &&
            a.protocolOffset == b.protocolOffset && a.span == b.span && a.bit == b.bit

    private fun writeCoil(item: ParsedAddress, value: Any?) {
        if (value !is Boolean) throw TypeMismatchException("coil write expects boolean")
        session.transact(item.unitId, ModbusPdu.writeSingleCoil(item.protocolOffset, value))
    }

    /**
     * Bit-in-register writes use function 22 only.
     * Modbus Application Protocol V1.1b3 §6.16:
     * Result = (current AND andMask) OR (orMask AND NOT andMask).
     * The target bit is 0 in the AND mask so the OR mask can force it; every other AND bit stays 1.
     * the reference platform requires the same mask write and does not fall back to a read-modify-write:
     */
    private fun writeBit(item: ParsedAddress, value: Any?) {
        if (value !is Boolean) throw TypeMismatchException("bit write expects boolean")
        val bit = item.bit!!
        val andMask = 0xFFFF xor (1 shl bit)
        val orMask = if (value) 1 shl bit else 0
        session.transact(item.unitId, ModbusPdu.maskWrite(item.protocolOffset, andMask, orMask))
    }

    private fun writeRegisters(item: ParsedAddress, value: Any?) {
        val wire = ModbusCodec.encode(item, value, config)
        val words = wire.size / 2
        val maxWrite = config.maxHoldingRegistersPerWrite.coerceIn(1, 123)
        if (words > maxWrite) throw AddressException("write of $words registers exceeds max $maxWrite")
        val pdu = if (words == 1 && !config.forceMultipleRegisterWrites) {
            val v = ((wire[0].toInt() and 0xFF) shl 8) or (wire[1].toInt() and 0xFF)
            ModbusPdu.writeSingleRegister(item.protocolOffset, v)
        } else {
            if (!config.allowWriteMultipleRegisters) throw AddressException("multiple register writes are disabled")
            ModbusPdu.writeMultipleRegisters(item.protocolOffset, wire)
        }
        session.transact(item.unitId, pdu)
    }

    private fun schedule(name: String, periodMs: Long) {
        tasks.remove(name)?.cancel(false)
        val gate = scanning.computeIfAbsent(name) { AtomicBoolean(false) }
        tasks[name] = scheduler.scheduleAtFixedRate({
            if (!started) return@scheduleAtFixedRate
            if (!gate.compareAndSet(false, true)) return@scheduleAtFixedRate
            try { poll(name) } catch (t: Throwable) { log.log(Level.FINE, "scan $name failed", t) }
            finally { gate.set(false) }
        }, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    private fun poll(scanClass: String) {
        val compiled = compile(scanClass)
        if (compiled.items.isEmpty() && compiled.failed.isEmpty()) return
        val stamp = Instant.now()
        if (!session.isConnected()) {
            for ((raw, message) in compiled.failed) {
                deliver(scanClass, raw, null, Quality.of(QualityCode.Bad_ConfigurationError, message), stamp, null)
            }
            for (item in compiled.items) deliver(scanClass, item.raw, null, Quality.of(lossCode(item.raw), "not connected"), stamp, cache[item.raw]?.sourceTimestamp)
            return
        }
        for ((raw, message) in compiled.failed) {
            deliver(scanClass, raw, null, Quality.of(QualityCode.Bad_ConfigurationError, message), stamp, null)
        }
        for (item in compiled.plan.rejected) {
            deliver(scanClass, item.raw, null, Quality.of(QualityCode.Bad_ConfigurationError, "span exceeds max request"), stamp, null)
        }
        runBlocks(compiled.plan.blocks) { deliverBlock(scanClass, it, stamp) }
    }

    private fun compile(scanClass: String): ScanPlan {
        val generation = planGeneration.get()
        val cached = plans[scanClass]
        if (cached != null && cached.generation == generation) return cached
        val wanted = LinkedHashSet<String>()
        for (sub in subs) {
            if (sub.scanClass == scanClass) wanted.addAll(sub.addresses)
        }
        val items = ArrayList<ParsedAddress>(wanted.size)
        val failed = ArrayList<Pair<String, String>>()
        for (raw in wanted) {
            val item = parseCached(raw)
            if (item == null) failed += raw to (parseFailed[raw] ?: "bad address")
            else items += item
        }
        val built = ScanPlan(generation, items, RequestOptimizer.plan(items, config), failed)
        plans[scanClass] = built
        return built
    }

    /**
     * One [ParsedAddress] per raw address for the life of the device. Raw listeners hang on that
     * instance and the scan dispatches through it, so two threads parsing the same new address at
     * once (an attach and a scan compile, or an API read) must end up holding the same object.
     */
    private fun parseCached(raw: String): ParsedAddress? {
        parsedByRaw[raw]?.let { return it }
        parseFailed[raw]?.let { return null }
        val parsed = try {
            ModbusAddress.parse(raw, config)
        } catch (e: AddressException) {
            parseFailed.putIfAbsent(raw, e.message ?: "bad address")
            return null
        }
        return parsedByRaw.putIfAbsent(raw, parsed) ?: parsed
    }

    private fun runBlocks(blocks: List<ReadBlock>, body: (ReadBlock) -> Unit) {
        if (blocks.isEmpty()) return
        val pool = io
        if (pool == null || blocks.size == 1) {
            blocks.forEach(body)
            return
        }
        blocks.map { block -> CompletableFuture.runAsync({ body(block) }, pool) }.forEach { it.join() }
    }

    private fun deliverBlock(scanClass: String, block: ReadBlock, stamp: Instant) {
        try {
            val pdu = session.transact(block.unitId, ModbusPdu.read(block.area, block.start, block.quantity))
            readPdus.incrementAndGet()
            val data = ModbusPdu.payload(pdu)
            for (item in block.items) {
                try {
                    val value = ModbusCodec.decode(item, block.start, data, config)
                    deliver(scanClass, item, value, Quality.of(QualityCode.Good), stamp, stamp)
                } catch (e: TypeMismatchException) {
                    deliver(scanClass, item, if (item.hasLast) item.lastGood else null, Quality.of(QualityCode.Bad_TypeMismatch, e.message), stamp, null)
                }
            }
        } catch (e: Exception) {
            val q = qualityFor(e)
            for (item in block.items) {
                deliver(scanClass, item, if (item.hasLast) item.lastGood else null, Quality.of(q, e.message), stamp, cache[item.raw]?.sourceTimestamp)
            }
        }
    }

    private fun deliver(scanClass: String, address: String, value: Any?, quality: Quality, stamp: Instant, source: Instant?) {
        val parsed = parsedByRaw[address]
        if (parsed != null) {
            deliver(scanClass, parsed, value, quality, stamp, source)
            return
        }
        val raw = rawListeners[rawKey(scanClass, address)]
        if (raw != null) {
            val epoch = stamp.toEpochMilli()
            for (cb in raw) {
                try { cb.onSample(value, quality, epoch) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
            }
        }
        deliverSubscribers(scanClass, address, value, quality, stamp, source)
    }

    private fun deliver(scanClass: String, item: ParsedAddress, value: Any?, quality: Quality, stamp: Instant, source: Instant?) {
        val shown = if (quality.code == QualityCode.Good) {
            if (value != null) {
                item.lastGood = value
                item.hasLast = true
            }
            value
        } else if (item.hasLast) item.lastGood else value
        val epoch = stamp.toEpochMilli()
        try {
            item.dispatch(scanClass, shown, quality, epoch)
        } catch (t: Throwable) {
            log.log(Level.FINE, "listener failed", t)
        }
        deliverSubscribers(scanClass, item.raw, shown, quality, stamp, source)
    }

    private fun deliverSubscribers(scanClass: String, address: String, shown: Any?, quality: Quality, stamp: Instant, source: Instant?) {
        if (listeners.isEmpty() && directSubs.get() == 0) return
        var qv: QualifiedValue? = null
        for (sub in subs) {
            if (sub.scanClass != scanClass || address !in sub.addresses) continue
            if (sub.shared) {
                val list = listeners[address] ?: continue
                if (list.isEmpty()) continue
                val sample = qv ?: sample(address, shown, quality, stamp, source, replace = true).also { qv = it }
                for (cb in list) {
                    try { cb(sample) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
                }
            } else {
                val sample = qv ?: sample(address, shown, quality, stamp, source, replace = true).also { qv = it }
                try { sub.listener(sample) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
            }
        }
    }

    private fun executeBlocks(blocks: List<ReadBlock>): List<Map<String, QualifiedValue>> {
        if (blocks.isEmpty()) return emptyList()
        val pool = io
        if (pool == null || blocks.size == 1) return blocks.map { decodeBlock(it) }
        return blocks.map { block -> CompletableFuture.supplyAsync({ decodeBlock(block) }, pool) }.map { it.join() }
    }

    private fun decodeBlock(block: ReadBlock): Map<String, QualifiedValue> {
        val out = HashMap<String, QualifiedValue>(block.items.size)
        try {
            val pdu = session.transact(block.unitId, ModbusPdu.read(block.area, block.start, block.quantity))
            readPdus.incrementAndGet()
            val data = ModbusPdu.payload(pdu)
            val stamp = Instant.now()
            for (item in block.items) {
                out[item.raw] = try {
                    val value = ModbusCodec.decode(item, block.start, data, config)
                    sample(item.raw, value, Quality.of(QualityCode.Good), stamp, replace = true)
                } catch (e: TypeMismatchException) {
                    sample(item.raw, cache[item.raw]?.value, Quality.of(QualityCode.Bad_TypeMismatch, e.message), stamp, replace = true)
                }
            }
        } catch (e: Exception) {
            val q = qualityFor(e)
            val stamp = Instant.now()
            for (item in block.items) out[item.raw] = lost(item.raw, stamp, e.message, q)
        }
        return out
    }

    private fun supervise() {
        var attempt = 0
        while (started && !Thread.currentThread().isInterrupted) {
            if (session.isConnected()) {
                if (!everConnected) everConnected = true
                sleep(100)
                continue
            }
            val state = if (everConnected || attempt > 0) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
            setStatus(status.copy(state = state, connected = false, reconnectAttempt = attempt, since = Instant.now()))
            if (everConnected) publishLoss("connection lost")
            try {
                session.connect()
                everConnected = true
                attempt = 0
                setStatus(status.copy(
                    state = ConnectionState.CONNECTED, connected = true, lastError = null,
                    reconnectAttempt = 0, since = Instant.now(),
                    requestCount = session.requestCount.get(), failureCount = session.failureCount.get(),
                ))
                log.info("connected ${config.mode} ${config.host}:${config.port}")
            } catch (e: Exception) {
                if (!started || e is InterruptedException) break
                session.stats.noteError()
                val delay = backoff(attempt)
                delays += delay
                attempt++
                setStatus(status.copy(
                    state = ConnectionState.RECONNECTING, connected = false, lastError = e.message,
                    reconnectAttempt = attempt, since = Instant.now(),
                    failureCount = session.failureCount.get(),
                ))
                log.fine("connect failed (${e.message}); retry in ${delay}ms")
                sleep(delay)
            }
        }
    }

    private fun publishLoss(diagnostic: String) {
        val now = Instant.now()
        val seen = HashSet<String>()
        for (sub in subs) {
            for (addr in sub.addresses) {
                if (!seen.add(sub.scanClass + "\u0000" + addr)) continue
                deliver(sub.scanClass, addr, null, Quality.of(lossCode(addr), diagnostic), now, cache[addr]?.sourceTimestamp)
            }
        }
    }

    private fun lost(address: String, now: Instant, diagnostic: String?, code: QualityCode = lossCode(address)): QualifiedValue {
        val prev = cache[address]
        return sample(address, prev?.value, Quality.of(code, diagnostic), now, source = prev?.sourceTimestamp, replace = true)
    }

    private fun lossCode(address: String): QualityCode {
        if (!everConnected && cache[address]?.value == null) return QualityCode.Bad_WaitingForInitialData
        return config.commsLossQuality
    }

    private fun qualityFor(e: Exception): QualityCode = when (e) {
        is NotConnectedException -> lossCode("")
        is SocketTimeoutException -> QualityCode.Bad_Timeout
        is ModbusDeviceException -> when (e.code) {
            1 -> QualityCode.Bad_NotWritable
            2 -> QualityCode.Bad_OutOfRange
            3 -> QualityCode.Bad_TypeMismatch
            4 -> QualityCode.Bad_DeviceFailure
            5, 6 -> QualityCode.Uncertain
            10, 11 -> QualityCode.Bad_NoCommunication
            else -> QualityCode.Bad
        }
        is AddressException, is TypeMismatchException -> QualityCode.Bad_ConfigurationError
        is IOException -> if (session.isConnected()) QualityCode.Bad else lossCode("")
        else -> QualityCode.Error
    }

    private fun sample(
        address: String, value: Any?, quality: Quality, timestamp: Instant,
        source: Instant? = null, replace: Boolean,
    ): QualifiedValue {
        val qv = QualifiedValue(address, value, quality, timestamp, source)
        if (replace) cache[address] = qv
        return qv
    }

    private fun backoff(attempt: Int): Long {
        // ±20% so devices that drop together do not reconnect on the same millisecond.
        val unit = ThreadLocalRandom.current().nextDouble(-0.2, 0.2)
        return reconnectDelayMs(
            config.backoffInitialMs, config.backoffMaxMs, config.backoffMultiplier, attempt, unit,
        )
    }

    private fun sleep(ms: Long) {
        if (ms <= 0 || !started) return
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun setStatus(next: ConnectionStatus) {
        val withCounts = next.copy(requestCount = session.requestCount.get(), failureCount = session.failureCount.get())
        status = withCounts
        statusListeners.forEach { cb ->
            try { cb(withCounts) } catch (t: Throwable) { log.log(Level.FINE, "status listener failed", t) }
        }
    }

    private inner class LiveSub(
        override val id: String,
        val scanClass: String,
        initial: Collection<String>,
        override val samplingIntervalMs: Double,
        override val publishingIntervalMs: Double,
        val listener: (QualifiedValue) -> Unit,
        val shared: Boolean,
    ) : TagSubscription {
        val addresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val open = AtomicBoolean(true)
        init {
            addresses.addAll(initial)
            if (!shared) directSubs.incrementAndGet()
        }
        override fun close() {
            if (!open.compareAndSet(true, false)) return
            if (!shared) directSubs.decrementAndGet()
            subs.remove(this)
            sharedSubs.entries.removeIf { it.value === this }
            for (addr in addresses) listeners[addr]?.remove(listener)
            planGeneration.incrementAndGet()
        }
    }
}

/**
 * Figures for one device's Diagnostics folder.
 * Tag paths reserve `[]` for the provider and for array indexes, so the published folder
 * segment is `Diagnostics` (DIFFERENCES 311).
 */
data class DeviceDiagnostics(
    val connected: Boolean,
    val state: String,
    val requestCount: Long,
    val errorCount: Long,
    val timeoutCount: Long,
    /** Mean response time in milliseconds, or null before the first completed response. */
    val averageResponseTimeMs: Double?,
    val samplingIntervalMs: Double,
    /** Modbus publishes each scan, so this matches [samplingIntervalMs]. */
    val publishIntervalMs: Double,
)

private class ScanPlan(
    val generation: Long,
    val items: List<ParsedAddress>,
    val plan: ReadPlan,
    val failed: List<Pair<String, String>>,
)

/**
 * Exponential backoff capped at [maxMs], then scaled by `1 + unit` where [unit] is in [-0.2, 0.2].
 * The result is never above [maxMs], so the cap still bounds a storm. The first attempt is allowed
 * to land below [initialMs]; that is the downward half of the jitter.
 */
internal fun reconnectDelayMs(initialMs: Long, maxMs: Long, multiplier: Double, attempt: Int, unit: Double): Long {
    var delay = initialMs.toDouble()
    repeat(attempt.coerceAtLeast(0)) { delay *= multiplier }
    val base = delay.coerceIn(initialMs.toDouble(), maxMs.toDouble())
    val factor = 1.0 + unit.coerceIn(-0.2, 0.2)
    return (base * factor).toLong().coerceIn(1L, maxMs)
}
