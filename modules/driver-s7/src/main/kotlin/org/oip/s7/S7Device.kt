package org.oip.s7

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * An S7-300, S7-400, S7-1200, or S7-1500 client. Implements [TagSource] so the tag engine can
 * adapt it without this module depending on gateway/tags.
 *
 * Addresses that share a scan period are one coalesced pass. [RequestOptimizer] merges adjacent
 * bytes and packs the merge into PDUs that fit the size negotiated at S7 setup.
 *
 * A response timeout closes the ISO session. S7 jobs are strictly paired by PDU reference, so a
 * late reply must not be applied to the next read. The supervisor reconnects with backoff.
 * Subscribed values keep the last good value and take [S7DeviceConfig.commsLossQuality].
 */
class S7Device(val config: S7DeviceConfig) : TagSource, AutoCloseable {
    private val log = Logger.getLogger("org.oip.s7.${config.name}")
    private val session = S7Session(config)
    private val cache = ConcurrentHashMap<String, QualifiedValue>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(QualifiedValue) -> Unit>>()
    private val rawListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<RawSample>>()
    private val parsedByRaw = ConcurrentHashMap<String, ParsedAddress>()
    private val parseFailed = ConcurrentHashMap<String, String>()
    private val plans = ConcurrentHashMap<String, ScanPlan>()
    private val planGeneration = AtomicLong()
    private val subs = CopyOnWriteArrayList<LiveSub>()
    private val directSubs = AtomicInteger()
    private val sharedSubs = ConcurrentHashMap<String, LiveSub>()
    private val periods = ConcurrentHashMap<String, Long>()
    private val tasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val scanning = ConcurrentHashMap<String, AtomicBoolean>()
    private val statusListeners = CopyOnWriteArrayList<(ConnectionStatus) -> Unit>()
    private val addressMap = CopyOnWriteArrayList<String>()
    private val delays = CopyOnWriteArrayList<Long>()

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "s7-scan-${config.name}").apply { isDaemon = true }
    }

    @Volatile private var started = false
    @Volatile private var everConnected = false
    @Volatile private var supervisor: Thread? = null
    @Volatile private var status = ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now())

    override val sourceId: String get() = config.name

    val reconnectDelaysMs: List<Long> get() = delays.toList()

    /** S7 read jobs sent after coalescing. One job may carry many addresses. */
    val readRequestCount: Long get() = session.readJobs.get()

    /** PDU size agreed with the CPU. The configured size until setup completes. */
    val negotiatedPduSize: Int get() = session.negotiatedPdu

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
        supervisor = Thread({ supervise() }, "s7-conn-${config.name}").apply { isDaemon = true; start() }
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
        session.shutdown()
        scheduler.shutdownNow()
    }

    override fun connectionStatus(): ConnectionStatus = status

    /** Status for `Gateway/Device/<name>/Diagnostics`. Reads counters only; no I/O. */
    fun diagnostics(): S7DeviceDiagnostics {
        var sampling: Double? = null
        for (sub in subs) {
            if (sub.addresses.isEmpty()) continue
            val period = sub.samplingIntervalMs
            val current = sampling
            if (current == null || period < current) sampling = period
        }
        val interval = sampling ?: config.defaultScanPeriodMs.toDouble()
        return S7DeviceDiagnostics(
            connected = status.connected && session.isConnected(),
            state = status.state.name,
            requestCount = session.requestCount.get(),
            errorCount = session.failureCount.get(),
            timeoutCount = session.timeoutCount.get(),
            averageResponseTimeMs = session.averageMillis(),
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
            val parsed = runCatching { S7Address.parse(addr, config.model, config.symbols) }.getOrNull()
            SourceNode(addr, addr, "Variable", readable = parsed != null, writable = parsed != null)
        }
    }

    override fun read(nodeIds: List<String>): List<QualifiedValue> {
        val now = Instant.now()
        val parsed = ArrayList<ParsedAddress>(nodeIds.size)
        val produced = HashMap<String, QualifiedValue>(nodeIds.size)
        for (raw in nodeIds) {
            if (raw in produced) continue
            val item = parseCached(raw)
            if (item == null) {
                produced[raw] = sample(raw, null, Quality.of(QualityCode.Bad_ConfigurationError, parseFailed[raw]), now, replace = true)
            } else {
                parsed += item
            }
        }
        if (parsed.isNotEmpty()) {
            if (!session.isConnected()) {
                for (item in parsed) produced[item.raw] = lost(item.raw, now, "not connected")
            } else {
                val plan = RequestOptimizer.plan(parsed, session.activePdu(), config)
                produced.putAll(execute(plan, now))
            }
        }
        return nodeIds.map { produced.getValue(it) }
    }

    override fun write(nodeId: String, value: Any?): WriteResult {
        val now = Instant.now()
        val item = try {
            parseCached(nodeId) ?: return WriteResult(nodeId, Quality.of(QualityCode.Bad_ConfigurationError, parseFailed[nodeId]), now)
        } catch (e: AddressException) {
            return WriteResult(nodeId, Quality.of(QualityCode.Bad_ConfigurationError, e.message), now)
        }
        if (!session.isConnected()) return WriteResult(nodeId, Quality.of(lossCode(nodeId), "not connected"), now)
        return try {
            val payload = S7Codec.encode(item, value)
            session.write(item, payload)
            // A bit payload is 0 or 1, not the memory byte the bit lives in, so the written
            // boolean is kept as the value. Every other type decodes from the bytes just sent.
            val decoded = if (item.type == S7Type.BIT) value else S7Codec.decode(item, payload)
            sample(nodeId, decoded, Quality.of(QualityCode.Good), now, replace = true)
            if (decoded != null) {
                item.lastGood = decoded
                item.hasLast = true
            }
            WriteResult(nodeId, Quality.of(QualityCode.Good), now)
        } catch (e: Exception) {
            WriteResult(nodeId, Quality.of(qualityFor(e), e.message), now)
        }
    }

    fun subscribe(addresses: List<String>, scanClass: String, listener: (QualifiedValue) -> Unit): TagSubscription {
        val period = periods[scanClass] ?: throw IllegalArgumentException("unknown scan class '$scanClass'")
        return addSub(addresses, scanClass, period.toDouble(), listener)
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
        return addSub(nodeIds, name, period.toDouble(), listener)
    }

    /**
     * One address on the scan class for [periodMs]. The gateway tag path uses this so a device
     * read stays off the tag engine lock. Same coalescing rules as [read].
     */
    /** Shortest period already polling [address], so a second subscriber joins that scan. */
    fun activeScanPeriodMs(address: String): Long? {
        var found: Long? = null
        for (sub in subs) {
            if (address !in sub.addresses) continue
            val period = sub.samplingIntervalMs.toLong()
            if (found == null || period < found) found = period
        }
        return found
    }

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

    private fun addSub(
        addresses: List<String>,
        scanClass: String,
        sampling: Double,
        listener: (QualifiedValue) -> Unit,
    ): TagSubscription {
        val sub = LiveSub(UUID.randomUUID().toString(), scanClass, addresses, sampling, listener, shared = false)
        subs += sub
        planGeneration.incrementAndGet()
        for (addr in addresses) {
            val initial = cache[addr] ?: sample(addr, null, Quality.of(QualityCode.Bad_WaitingForInitialData), Instant.now(), replace = false)
            listener(initial)
        }
        return sub
    }

    private fun sharedSub(name: String, period: Double): LiveSub = sharedSubs.computeIfAbsent(name) {
        val created = LiveSub(
            UUID.randomUUID().toString(), name, emptyList(), period,
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

    private fun schedule(name: String, periodMs: Long) {
        tasks.remove(name)?.cancel(false)
        val gate = scanning.computeIfAbsent(name) { AtomicBoolean(false) }
        tasks[name] = scheduler.scheduleAtFixedRate({
            if (!started) return@scheduleAtFixedRate
            if (!gate.compareAndSet(false, true)) return@scheduleAtFixedRate
            try {
                poll(name)
            } catch (t: Throwable) {
                log.log(Level.FINE, "scan $name failed", t)
            } finally {
                gate.set(false)
            }
        }, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    private fun poll(scanClass: String) {
        val compiled = compile(scanClass)
        if (compiled.items.isEmpty() && compiled.failed.isEmpty()) return
        val stamp = Instant.now()
        if (!session.isConnected()) {
            for ((raw, message) in compiled.failed) {
                deliverRaw(scanClass, raw, null, Quality.of(QualityCode.Bad_ConfigurationError, message), stamp)
            }
            for (item in compiled.items) deliver(scanClass, item, null, Quality.of(lossCode(item.raw), "not connected"), stamp)
            return
        }
        for ((raw, message) in compiled.failed) {
            deliverRaw(scanClass, raw, null, Quality.of(QualityCode.Bad_ConfigurationError, message), stamp)
        }
        val produced = execute(compiled.plan, stamp)
        for (item in compiled.items) {
            val sample = produced[item.raw] ?: continue
            deliver(scanClass, item, sample.value, sample.quality, sample.timestamp)
        }
    }

    private fun compile(scanClass: String): ScanPlan {
        val generation = planGeneration.get()
        val pdu = session.activePdu()
        val cached = plans[scanClass]
        if (cached != null && cached.generation == generation && cached.pdu == pdu) return cached
        val wanted = LinkedHashSet<String>()
        for (sub in subs) if (sub.scanClass == scanClass) wanted.addAll(sub.addresses)
        val items = ArrayList<ParsedAddress>(wanted.size)
        val failed = ArrayList<Pair<String, String>>()
        for (raw in wanted) {
            val item = parseCached(raw)
            if (item == null) failed += raw to (parseFailed[raw] ?: "bad address") else items += item
        }
        val built = ScanPlan(generation, pdu, items, RequestOptimizer.plan(items, pdu, config), failed)
        plans[scanClass] = built
        return built
    }

    /**
     * One [ParsedAddress] per raw string. A scan and an attach that parse the same new address
     * at the same time must share the object the listener is registered on.
     */
    private fun parseCached(raw: String): ParsedAddress? {
        parsedByRaw[raw]?.let { return it }
        parseFailed[raw]?.let { return null }
        val parsed = try {
            S7Address.parse(raw, config.model, config.symbols)
        } catch (e: AddressException) {
            parseFailed.putIfAbsent(raw, e.message ?: "bad address")
            return null
        }
        return parsedByRaw.putIfAbsent(raw, parsed) ?: parsed
    }

    private fun execute(plan: ReadPlan, stamp: Instant): Map<String, QualifiedValue> {
        val out = HashMap<String, QualifiedValue>()
        for (item in plan.rejected) {
            out[item.raw] = sample(item.raw, null, Quality.of(QualityCode.Bad_ConfigurationError, "exceeds negotiated PDU"), stamp, replace = true)
        }
        for (pdu in plan.pdus) {
            try {
                val items = session.read(pdu.slices)
                for ((slice, result) in pdu.slices.zip(items)) {
                    if (result.returnCode != S7Wire.RET_OK) {
                        val q = Quality.of(S7Wire.qualityForReturn(result.returnCode), "S7 status 0x${result.returnCode.toString(16)}")
                        for (addr in slice.addresses) out[addr.raw] = sample(addr.raw, retained(addr), q, stamp, replace = true)
                        continue
                    }
                    for (addr in slice.addresses) {
                        out[addr.raw] = try {
                            val bytes = extract(addr, slice, result.bytes)
                            val value = S7Codec.decode(addr, bytes)
                            sample(addr.raw, value, Quality.of(QualityCode.Good), stamp, replace = true)
                        } catch (e: TypeMismatchException) {
                            sample(addr.raw, retained(addr), Quality.of(QualityCode.Bad_TypeMismatch, e.message), stamp, replace = true)
                        } catch (e: OutOfRangeException) {
                            sample(addr.raw, retained(addr), Quality.of(QualityCode.Bad_OutOfRange, e.message), stamp, replace = true)
                        }
                    }
                }
            } catch (e: Exception) {
                val q = Quality.of(qualityFor(e), e.message)
                for (slice in pdu.slices) for (addr in slice.addresses) {
                    out[addr.raw] = sample(addr.raw, retained(addr), q, stamp, replace = true)
                }
            }
        }
        return out
    }

    private fun extract(address: ParsedAddress, slice: ReadSlice, data: ByteArray): ByteArray {
        val rel = if (address.kind == S7Kind.MEMORY) address.start - slice.start else (address.start - slice.start) * 2
        val len = S7Codec.valueBytes(address)
        if (rel < 0 || rel + len > data.size) throw S7ProtocolException("short data for ${address.raw}")
        return data.copyOfRange(rel, rel + len)
    }

    private fun deliver(scanClass: String, item: ParsedAddress, value: Any?, quality: Quality, stamp: Instant) {
        val shown = if (quality.code == QualityCode.Good) {
            if (value != null) {
                item.lastGood = value
                item.hasLast = true
            }
            value
        } else if (item.hasLast) item.lastGood else value
        val epoch = stamp.toEpochMilli()
        cache[item.raw] = QualifiedValue(item.raw, shown, quality, stamp, null)
        try {
            item.dispatch(scanClass, shown, quality, epoch)
        } catch (t: Throwable) {
            log.log(Level.FINE, "listener failed", t)
        }
        deliverSubscribers(scanClass, item.raw, shown, quality, stamp)
    }

    private fun deliverRaw(scanClass: String, address: String, value: Any?, quality: Quality, stamp: Instant) {
        val parsed = parsedByRaw[address]
        if (parsed != null) {
            deliver(scanClass, parsed, value, quality, stamp)
            return
        }
        val raw = rawListeners[rawKey(scanClass, address)]
        if (raw != null) {
            val epoch = stamp.toEpochMilli()
            for (cb in raw) {
                try { cb.onSample(value, quality, epoch) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
            }
        }
        deliverSubscribers(scanClass, address, value, quality, stamp)
    }

    private fun deliverSubscribers(scanClass: String, address: String, shown: Any?, quality: Quality, stamp: Instant) {
        if (listeners.isEmpty() && directSubs.get() == 0) return
        var qv: QualifiedValue? = null
        for (sub in subs) {
            if (sub.scanClass != scanClass || address !in sub.addresses) continue
            val sample = qv ?: sample(address, shown, quality, stamp, replace = true).also { qv = it }
            if (!sub.shared) {
                try { sub.listener(sample) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
            }
        }
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
                planGeneration.incrementAndGet()
                setStatus(status.copy(
                    state = ConnectionState.CONNECTED, connected = true, lastError = null,
                    reconnectAttempt = 0, since = Instant.now(),
                ))
                log.fine("connected ${config.model} ${config.host}:${config.port} pdu=${session.negotiatedPdu}")
            } catch (e: Exception) {
                if (!started || e is InterruptedException) break
                val delay = backoff(attempt)
                delays += delay
                attempt++
                setStatus(status.copy(
                    state = ConnectionState.RECONNECTING, connected = false, lastError = e.message,
                    reconnectAttempt = attempt, since = Instant.now(),
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
                deliverRaw(sub.scanClass, addr, null, Quality.of(lossCode(addr), diagnostic), now)
            }
        }
    }

    private fun retained(address: ParsedAddress): Any? = if (address.hasLast) address.lastGood else cache[address.raw]?.value

    private fun lost(address: String, now: Instant, diagnostic: String?): QualifiedValue {
        val prev = cache[address]
        return sample(address, prev?.value, Quality.of(lossCode(address), diagnostic), now, replace = true)
    }

    private fun lossCode(address: String): QualityCode {
        if (!everConnected && cache[address]?.value == null) return QualityCode.Bad_WaitingForInitialData
        return config.commsLossQuality
    }

    private fun qualityFor(e: Exception): QualityCode = when (e) {
        is SocketTimeoutException -> if (everConnected) config.commsLossQuality else QualityCode.Bad_Timeout
        is NotConnectedException -> lossCode("")
        is S7ItemException -> S7Wire.qualityForReturn(e.code)
        is OutOfRangeException -> QualityCode.Bad_OutOfRange
        is TypeMismatchException -> QualityCode.Bad_TypeMismatch
        is AddressException -> QualityCode.Bad_ConfigurationError
        is S7ProtocolException -> QualityCode.Bad_DeviceFailure
        is IOException -> if (session.isConnected()) QualityCode.Bad else lossCode("")
        else -> QualityCode.Error
    }

    private fun sample(address: String, value: Any?, quality: Quality, timestamp: Instant, replace: Boolean): QualifiedValue {
        val qv = QualifiedValue(address, value, quality, timestamp, null)
        if (replace) cache[address] = qv
        return qv
    }

    private fun backoff(attempt: Int): Long {
        val unit = ThreadLocalRandom.current().nextDouble(-0.2, 0.2)
        return reconnectDelayMs(config.backoffInitialMs, config.backoffMaxMs, config.backoffMultiplier, attempt, unit)
    }

    private fun sleep(ms: Long) {
        if (ms <= 0 || !started) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun setStatus(next: ConnectionStatus) {
        val withCounts = next.copy(requestCount = session.requestCount.get(), failureCount = session.failureCount.get())
        status = withCounts
        for (cb in statusListeners) {
            try { cb(withCounts) } catch (t: Throwable) { log.log(Level.FINE, "status listener failed", t) }
        }
    }

    private fun rawKey(scanClass: String, address: String) = "$scanClass\u0000$address"

    private inner class LiveSub(
        override val id: String,
        val scanClass: String,
        initial: Collection<String>,
        override val samplingIntervalMs: Double,
        val listener: (QualifiedValue) -> Unit,
        val shared: Boolean,
    ) : TagSubscription {
        val addresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val open = AtomicBoolean(true)
        override val publishingIntervalMs: Double get() = samplingIntervalMs
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

private class ScanPlan(
    val generation: Long,
    val pdu: Int,
    val items: List<ParsedAddress>,
    val plan: ReadPlan,
    val failed: List<Pair<String, String>>,
)

/** Exponential backoff capped at [maxMs], then scaled by `1 + unit` where [unit] is in [-0.2, 0.2]. */
internal fun reconnectDelayMs(initialMs: Long, maxMs: Long, multiplier: Double, attempt: Int, unit: Double): Long {
    var delay = initialMs.toDouble()
    repeat(attempt.coerceAtLeast(0)) { delay *= multiplier }
    val base = delay.coerceIn(initialMs.toDouble(), maxMs.toDouble())
    val factor = 1.0 + unit.coerceIn(-0.2, 0.2)
    return (base * factor).toLong().coerceIn(1L, maxMs)
}
