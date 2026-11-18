package org.oip.bacnet

import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A remote BACnet/IP device reached through a [BacnetLocalDevice] (DEV-011, DEV-038).
 *
 * One scan thread per device (`bacnet-scan-<name>`) finds the device (Who-Is / I-Am), reads its object
 * list and object names (the browse tree), and polls subscribed items. Network work goes through the
 * local device, whose own threads send and receive; this thread waits on a reply future with a bound of
 * the local device's APDU timeout times its tries. Values keep the last good reading with
 * Bad_NotConnected after communication is lost, and the device is found again after
 * [RemoteDeviceConfig.reconnectMs].
 */
class BacnetRemoteDevice(
    val config: RemoteDeviceConfig,
    private val localLookup: (String) -> BacnetLocalDevice?,
) : TagSource, AutoCloseable {
    private val log = Logger.getLogger("org.oip.bacnet.${config.name}")
    private val deviceObject = ObjectId(BacnetObjectType.DEVICE, config.deviceInstance)
    private val subs = CopyOnWriteArrayList<LiveSub>()
    private val periods = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private val statusListeners = CopyOnWriteArrayList<(ConnectionStatus) -> Unit>()
    private val cache = ConcurrentHashMap<String, QualifiedValue>()
    private val parsed = ConcurrentHashMap<String, BacnetItemAddress>()
    private val lifecycle = Any()

    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var running = false
    @Volatile private var bound: BacnetAddress? = null
    @Volatile private var nextAttempt = 0L
    @Volatile private var rpmSupported = true
    @Volatile private var catalog: Map<ObjectId, String> = emptyMap()
    @Volatile private var status = ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now())

    val requestCount = AtomicLong()
    val failureCount = AtomicLong()
    val timeoutCount = AtomicLong()
    val discoveryCount = AtomicLong()

    /** The address the device answered from, once found. */
    val address: BacnetAddress? get() = bound

    /** Objects found by the last browse, with their names. */
    fun objects(): Map<ObjectId, String> = catalog

    /** False once the device rejected ReadPropertyMultiple; reads then use ReadProperty. */
    val usesReadPropertyMultiple: Boolean get() = rpmSupported

    override val sourceId: String get() = config.name

    fun start() = synchronized(lifecycle) {
        if (running) return
        running = true
        val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bacnet-scan-${config.name}").apply { isDaemon = true } }
        scheduler = exec
        nextAttempt = 0L
        setStatus(ConnectionStatus(ConnectionState.CONNECTING, connected = false, since = Instant.now()))
        exec.scheduleWithFixedDelay({ supervise() }, 0, 100, TimeUnit.MILLISECONDS)
        for (sub in subs) schedule(sub.period)
    }

    fun stop() = synchronized(lifecycle) {
        if (!running) return
        running = false
        periods.values.forEach { it.cancel(false) }
        periods.clear()
        scheduler?.let { it.shutdownNow(); it.awaitTermination(5, TimeUnit.SECONDS) }
        scheduler = null
        bound = null
        setStatus(ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now()))
    }

    override fun close() = stop()

    override fun connectionStatus(): ConnectionStatus = status

    override fun subscribeStatus(listener: (ConnectionStatus) -> Unit): AutoCloseable {
        statusListeners += listener
        listener(status)
        return AutoCloseable { statusListeners -= listener }
    }

    // ---- browse ------------------------------------------------------------------------------

    override fun browse(nodeId: String): List<SourceNode> {
        browseStatusFlags(nodeId)?.let { return it }
        if (nodeId.isEmpty()) {
            val known = catalog
            val objects = if (known.isEmpty() && status.connected) mapOf(deviceObject to config.name) else known
            return objects.keys.sortedWith(compareBy({ it.type }, { it.instance })).map { id ->
                SourceNode(id.toString(), objects[id]?.takeIf { it.isNotBlank() } ?: id.toString(), "Object", readable = false, writable = false)
            }
        }
        val address = try { BacnetItemAddress.parse(nodeId) } catch (_: AddressException) { return emptyList() }
        if (address.isObject) {
            val type = address.obj.type
            val items = BacnetItem.itemsFor(type).map { item -> itemNode(address.obj, item) }
            val folder = if (BacnetItem.hasStatusFlags(type)) {
                listOf(SourceNode("${address.obj}/${BacnetItem.STATUS_FLAGS_FOLDER}", BacnetItem.STATUS_FLAGS_FOLDER, "Object", readable = false, writable = false))
            } else emptyList()
            return items + folder
        }
        return emptyList()
    }

    /** Browse of `<object>/StatusFlags`: the four flag items. */
    fun browseStatusFlags(nodeId: String): List<SourceNode>? {
        if (!nodeId.endsWith("/${BacnetItem.STATUS_FLAGS_FOLDER}")) return null
        val obj = try { BacnetItemAddress.parse(nodeId.removeSuffix("/${BacnetItem.STATUS_FLAGS_FOLDER}")).obj } catch (_: AddressException) { return null }
        return BacnetItem.STATUS_BITS.map { itemNode(obj, it, it.path.substringAfter('/')) }
    }

    private fun itemNode(obj: ObjectId, item: BacnetItem, display: String = item.path) =
        SourceNode("$obj/${item.path}", display, "Variable", readable = true, writable = BacnetItem.writable(obj.type, item))

    /** The node [nodeId] names, including an address browse does not list. Null when it is not an address. */
    fun describe(nodeId: String): SourceNode? {
        if (nodeId.endsWith("/${BacnetItem.STATUS_FLAGS_FOLDER}")) {
            val obj = runCatching { BacnetItemAddress.parse(nodeId.removeSuffix("/${BacnetItem.STATUS_FLAGS_FOLDER}")).obj }.getOrNull() ?: return null
            return SourceNode(nodeId, BacnetItem.STATUS_FLAGS_FOLDER, "Object", readable = false, writable = false).takeIf { BacnetItem.hasStatusFlags(obj.type) }
        }
        val address = try { BacnetItemAddress.parse(nodeId) } catch (_: AddressException) { return null }
        if (address.isObject) return SourceNode(nodeId, catalog[address.obj]?.takeIf { it.isNotBlank() } ?: nodeId, "Object", readable = false, writable = false)
        val item = address.item!!
        return SourceNode(nodeId, item.path, "Variable", readable = true, writable = BacnetItem.writable(address.obj.type, item))
    }

    /** Read the object list and names again. Runs on the caller's thread; bounded by the request timeouts. */
    fun rebrowse(): Map<ObjectId, String> {
        val dest = bound ?: throw NotConnectedException("device ${config.name} has not been found")
        catalog = browseObjects(dest)
        return catalog
    }

    // ---- reads and writes --------------------------------------------------------------------

    override fun read(nodeIds: List<String>): List<QualifiedValue> {
        val now = Instant.now()
        val dest = bound
        val addresses = nodeIds.map { id -> runCatching { address(id) }.getOrNull() }
        if (dest == null || !status.connected) {
            return nodeIds.mapIndexed { i, id ->
                if (addresses[i] == null) bad(id, QualityCode.Bad_NotFound, "not a BACnet item address", now)
                else bad(id, QualityCode.Bad_NotConnected, status.lastError ?: "device ${config.name} is not connected", now)
            }
        }
        val refs = addresses.filterNotNull().filter { !it.isObject }.map { it.obj to PropertyRef(it.property) }.distinct()
        val results = readProperties(dest, refs)
        return nodeIds.mapIndexed { i, id ->
            val a = addresses[i] ?: return@mapIndexed bad(id, QualityCode.Bad_NotFound, "not a BACnet item address", now)
            if (a.isObject) return@mapIndexed bad(id, QualityCode.Bad_NotReadable, "an object is not a value", now)
            toValue(id, a, results[a.obj to a.property], now)
        }
    }

    override fun write(nodeId: String, value: Any?): WriteResult = try {
        writeAsync(nodeId, value).get(waitBoundMs(), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        WriteResult(nodeId, Quality.of(QualityCode.Bad_Timeout, "write timed out"), Instant.now())
    }

    /**
     * Write [value] to [nodeId]. A commandable present value is written at the address's priority, else
     * [RemoteDeviceConfig.writePriority]; null relinquishes the command at that priority.
     */
    fun writeAsync(nodeId: String, value: Any?): CompletableFuture<WriteResult> {
        val now = Instant.now()
        fun done(code: QualityCode, diagnostic: String?) = CompletableFuture.completedFuture(WriteResult(nodeId, Quality.of(code, diagnostic), now))
        val a = try { address(nodeId) } catch (e: AddressException) { return done(QualityCode.Bad_NotFound, e.message) }
        val item = a.item ?: return done(QualityCode.Bad_NotWritable, "an object is not a value")
        if (!BacnetItem.writable(a.obj.type, item)) {
            return done(QualityCode.Bad_NotWritable, "${item.path} is read-only")
        }
        val encoded = try { BacnetValues.forWrite(a.obj.type, item, value) } catch (e: IllegalArgumentException) {
            return done(if (e is OutOfRangeException) QualityCode.Bad_OutOfRange else QualityCode.Bad_TypeMismatch, e.message)
        }
        val priority = if (item.property == BacnetProperty.PRESENT_VALUE && BacnetObjectType.isCommandable(a.obj.type)) a.priority ?: config.writePriority else null
        val dest = bound ?: return done(QualityCode.Bad_NotConnected, status.lastError ?: "device ${config.name} is not connected")
        val local = localLookup(config.localDevice) ?: return done(QualityCode.Bad_NotConnected, "local device ${config.localDevice} is not configured")
        requestCount.incrementAndGet()
        return local.request(dest, BacnetService.WRITE_PROPERTY, BacnetFrames.writeProperty(a.obj, item.property, null, encoded, priority))
            .handle { reply, error ->
                val q = when {
                    error != null -> { failureCount.incrementAndGet(); failure(error) }
                    reply is Apdu.SimpleAck -> Quality.of(QualityCode.Good)
                    reply is Apdu.Error -> errorQuality(reply.errorClass, reply.errorCode)
                    reply is Apdu.Reject -> Quality.of(QualityCode.Bad_DeviceFailure, "write rejected (reason ${reply.reason})")
                    reply is Apdu.Abort -> Quality.of(QualityCode.Bad_DeviceFailure, "write aborted (reason ${reply.reason})")
                    else -> Quality.of(QualityCode.Bad_DeviceFailure, "unexpected reply")
                }
                WriteResult(nodeId, q, Instant.now())
            }
    }

    // ---- raw access for scripts (SCR-007) ---------------------------------------------------

    /**
     * ReadProperty of any property of any object, on the caller's thread. Returns the plain value (a list for an
     * array or list). Throws [NotConnectedException] before the device is found, [java.util.concurrent.TimeoutException]
     * when it does not answer, and [IllegalStateException] with the BACnet error when it refuses.
     */
    fun readRaw(obj: ObjectId, property: Int, index: Int? = null): Any? {
        val dest = bound ?: throw NotConnectedException(status.lastError ?: "device ${config.name} has not been found")
        val r = readOne(dest, obj, property, index)
        if (!r.ok) throw IllegalStateException(errorQuality(r.errorClass ?: -1, r.errorCode ?: -1).diagnostic ?: "read refused")
        return BacnetValues.plain(r.value)
    }

    /**
     * WriteProperty of any property, at [priority] when given (1..16). A value for a property the driver knows is
     * encoded as that property's type; any other value with its natural tag (a whole number as Unsigned or Signed,
     * any other number as REAL).
     * Null writes a BACnet NULL, which relinquishes a commanded value.
     */
    fun writeRaw(obj: ObjectId, property: Int, value: Any?, priority: Int? = null, index: Int? = null): Quality {
        if (priority != null && priority !in 1..16) return Quality.of(QualityCode.Bad_OutOfRange, "priority must be 1 to 16")
        val known = BacnetItem.itemsFor(obj.type).firstOrNull { it.property == property && it.bit == null }
        val encoded = try {
            when {
                value == null -> BacnetNull
                known != null && index == null -> BacnetValues.forWrite(obj.type, known, value)
                value is Double -> value.toFloat()
                else -> value
            }
        } catch (e: IllegalArgumentException) {
            return Quality.of(if (e is OutOfRangeException) QualityCode.Bad_OutOfRange else QualityCode.Bad_TypeMismatch, e.message)
        }
        val dest = bound ?: return Quality.of(QualityCode.Bad_NotConnected, status.lastError ?: "device ${config.name} is not connected")
        val data = try { BacnetFrames.writeProperty(obj, property, index, encoded, priority) } catch (e: IllegalArgumentException) {
            return Quality.of(QualityCode.Bad_TypeMismatch, e.message)
        }
        val reply = try { rawRequest(dest, BacnetService.WRITE_PROPERTY, data) } catch (e: Exception) { return failure(e) }
        return when (reply) {
            is Apdu.SimpleAck -> Quality.of(QualityCode.Good)
            is Apdu.Error -> errorQuality(reply.errorClass, reply.errorCode)
            is Apdu.Reject -> Quality.of(QualityCode.Bad_DeviceFailure, "write rejected (reason ${reply.reason})")
            is Apdu.Abort -> Quality.of(QualityCode.Bad_DeviceFailure, "write aborted (reason ${reply.reason})")
            else -> Quality.of(QualityCode.Bad_DeviceFailure, "unexpected reply")
        }
    }

    /**
     * Send the device the gateway's clock: TimeSynchronization with local time in [zone], or UTCTimeSynchronization
     * when [utc]. Unconfirmed, so a sent request is all this reports.
     */
    fun synchronizeTime(utc: Boolean, at: Instant = Instant.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean {
        val dest = bound ?: throw NotConnectedException(status.lastError ?: "device ${config.name} has not been found")
        val local = local()
        val time = java.time.LocalDateTime.ofInstant(at, if (utc) java.time.ZoneOffset.UTC else zone)
        val service = if (utc) BacnetService.UTC_TIME_SYNCHRONIZATION else BacnetService.TIME_SYNCHRONIZATION
        requestCount.incrementAndGet()
        return local.sendUnconfirmed(dest, service, BacnetFrames.timeSynchronization(time))
    }

    override fun subscribe(
        nodeIds: List<String>,
        samplingIntervalMs: Double,
        publishingIntervalMs: Double,
        listener: (QualifiedValue) -> Unit,
    ): TagSubscription {
        val period = samplingIntervalMs.toLong().coerceAtLeast(10)
        val sub = LiveSub(period, nodeIds, listener, null)
        subs += sub
        if (running) schedule(period)
        return sub
    }

    fun attachRaw(periodMs: Long, address: String, listener: RawSample): AutoCloseable {
        val period = periodMs.coerceAtLeast(10)
        val sub = LiveSub(period, listOf(address), null, listener)
        subs += sub
        cache[address]?.let { runCatching { listener.onSample(it.value, it.quality, (it.sourceTimestamp ?: it.timestamp).toEpochMilli()) } }
        if (running) schedule(period)
        return sub
    }

    fun activeScanPeriodMs(address: String): Long? = subs.filter { address in it.addresses }.minOfOrNull { it.period }

    // ---- scan thread -------------------------------------------------------------------------

    private fun schedule(period: Long) {
        val exec = scheduler ?: return
        periods.computeIfAbsent(period) {
            exec.scheduleWithFixedDelay({ poll(period) }, 0, period, TimeUnit.MILLISECONDS)
        }
    }

    private fun supervise() {
        if (!running || bound != null && status.connected) return
        if (System.nanoTime() < nextAttempt) return
        try {
            connect()
        } catch (e: Exception) {
            if (!running) return
            val msg = e.message ?: e.javaClass.simpleName
            log.log(Level.FINE, "device ${config.name}: $msg", e)
            bound = null
            nextAttempt = System.nanoTime() + config.reconnectMs * 1_000_000L
            setStatus(ConnectionStatus(ConnectionState.RECONNECTING, connected = false, lastError = msg, since = Instant.now(),
                reconnectAttempt = status.reconnectAttempt + 1, requestCount = requestCount.get(), failureCount = failureCount.get()))
            markLost(msg)
        }
    }

    private fun local(): BacnetLocalDevice {
        val local = localLookup(config.localDevice) ?: throw NotConnectedException("local device '${config.localDevice}' is not configured")
        if (!local.isBound) throw NotConnectedException("local device '${config.localDevice}' is not running${local.lastError?.let { ": $it" } ?: ""}")
        return local
    }

    private fun connect() {
        val local = local()
        discoveryCount.incrementAndGet()
        val target = config.host?.let { InetSocketAddress(ipv4(it), config.port) } ?: InetSocketAddress(ipv4(local.config.broadcastAddress), config.port)
        val found = CompletableFuture<BacnetAddress>()
        val listener = local.addIAmListener { iAm, from -> if (iAm.device == config.deviceInstance) found.complete(from) }
        val dest = try {
            var answer: BacnetAddress? = null
            for (attempt in 0..local.config.apduRetries) {
                if (!running) throw NotConnectedException("stopped")
                local.whoIs(config.deviceInstance, config.deviceInstance, target)
                answer = try { found.get(local.config.apduTimeoutMs, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { null }
                if (answer != null) break
            }
            answer ?: config.host?.let { BacnetAddress(target) }
                ?: throw NotConnectedException("device ${config.deviceInstance} did not answer Who-Is at $target")
        } finally {
            listener.close()
        }
        // The device object must answer before the device counts as connected.
        val name = readOne(dest, deviceObject, BacnetProperty.OBJECT_NAME)
        if (!name.ok) throw NotConnectedException("device ${config.deviceInstance} did not return its name (error class ${name.errorClass}, code ${name.errorCode})")
        bound = dest
        if (config.browseOnConnect) catalog = browseObjects(dest)
        else catalog = mapOf(deviceObject to (name.value as? String ?: ""))
        setStatus(ConnectionStatus(ConnectionState.CONNECTED, connected = true, since = Instant.now(),
            requestCount = requestCount.get(), failureCount = failureCount.get()))
        log.fine("device ${config.name} found at $dest with ${catalog.size} objects")
    }

    private fun browseObjects(dest: BacnetAddress): Map<ObjectId, String> {
        val ids = objectList(dest)
        val names = readProperties(dest, ids.map { it to PropertyRef(BacnetProperty.OBJECT_NAME) })
        val out = LinkedHashMap<ObjectId, String>()
        for (id in ids) {
            val r = names[id to BacnetProperty.OBJECT_NAME]
            if (r?.error is TimeoutException) throw r.error
            out[id] = (r?.result?.value as? String) ?: ""
        }
        return out
    }

    /** The device's object list: whole, or element by element when the whole list does not fit one reply. */
    private fun objectList(dest: BacnetAddress): List<ObjectId> {
        val whole = rawRequest(dest, BacnetService.READ_PROPERTY, BacnetFrames.readProperty(deviceObject, BacnetProperty.OBJECT_LIST))
        if (whole is Apdu.ComplexAck) return BacnetAcks.readProperty(whole.data).values.orEmpty().filterIsInstance<ObjectId>()
        val count = readOne(dest, deviceObject, BacnetProperty.OBJECT_LIST, 0)
        val n = ((count.value as? BacnetUnsigned)?.value ?: throw NotConnectedException("device ${config.deviceInstance} did not return its object list")).toInt()
        val out = ArrayList<ObjectId>(n)
        val indexes = (1..n).toList()
        if (rpmSupported) {
            for (chunk in indexes.chunked(config.maxPropertiesPerRequest)) {
                val reply = rawRequest(dest, BacnetService.READ_PROPERTY_MULTIPLE,
                    BacnetFrames.readPropertyMultiple(listOf(deviceObject to chunk.map { PropertyRef(BacnetProperty.OBJECT_LIST, it) })))
                if (reply is Apdu.ComplexAck) {
                    out += BacnetAcks.readPropertyMultiple(reply.data).mapNotNull { it.value as? ObjectId }
                    continue
                }
                if (reply is Apdu.Reject && reply.reason == RejectReason.UNRECOGNIZED_SERVICE) rpmSupported = false
                for (i in chunk) (readOne(dest, deviceObject, BacnetProperty.OBJECT_LIST, i).value as? ObjectId)?.let { out += it }
            }
        } else {
            for (i in indexes) (readOne(dest, deviceObject, BacnetProperty.OBJECT_LIST, i).value as? ObjectId)?.let { out += it }
        }
        return out
    }

    private fun readOne(dest: BacnetAddress, obj: ObjectId, property: Int, index: Int? = null): PropertyResult {
        return when (val reply = rawRequest(dest, BacnetService.READ_PROPERTY, BacnetFrames.readProperty(obj, property, index))) {
            is Apdu.ComplexAck -> BacnetAcks.readProperty(reply.data)
            is Apdu.Error -> PropertyResult(obj, property, index, null, reply.errorClass, reply.errorCode)
            is Apdu.Reject -> PropertyResult(obj, property, index, null, -1, reply.reason.toLong())
            is Apdu.Abort -> PropertyResult(obj, property, index, null, -2, reply.reason.toLong())
            else -> PropertyResult(obj, property, index, null, -1, -1)
        }
    }

    /** One confirmed request; throws TimeoutException or NotConnectedException when it gets no reply. */
    private fun rawRequest(dest: BacnetAddress, service: Int, data: ByteArray): Apdu {
        val local = local()
        requestCount.incrementAndGet()
        return try {
            local.request(dest, service, data).get(waitBoundMs(), TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            failureCount.incrementAndGet()
            if (e.cause is TimeoutException) timeoutCount.incrementAndGet()
            throw (e.cause as? Exception) ?: e
        } catch (e: TimeoutException) {
            failureCount.incrementAndGet()
            timeoutCount.incrementAndGet()
            throw e
        }
    }

    private class Outcome(val result: PropertyResult?, val error: Exception?)

    /** Read properties, many per ReadPropertyMultiple, falling back to ReadProperty. Never throws for one bad property. */
    private fun readProperties(dest: BacnetAddress, refs: List<Pair<ObjectId, PropertyRef>>): Map<Pair<ObjectId, Int>, Outcome> {
        val out = HashMap<Pair<ObjectId, Int>, Outcome>()
        for (chunk in refs.chunked(config.maxPropertiesPerRequest)) readChunk(dest, chunk, out)
        return out
    }

    private fun readChunk(dest: BacnetAddress, chunk: List<Pair<ObjectId, PropertyRef>>, out: MutableMap<Pair<ObjectId, Int>, Outcome>) {
        if (rpmSupported && chunk.size > 1) {
            val grouped = chunk.groupBy({ it.first }, { it.second }).toList()
            val reply = try {
                rawRequest(dest, BacnetService.READ_PROPERTY_MULTIPLE, BacnetFrames.readPropertyMultiple(grouped))
            } catch (e: Exception) {
                for ((obj, ref) in chunk) out[obj to ref.property] = Outcome(null, e)
                return
            }
            when {
                reply is Apdu.ComplexAck -> {
                    for (r in BacnetAcks.readPropertyMultiple(reply.data)) out[r.obj to r.property] = Outcome(r, null)
                    return
                }
                reply is Apdu.Abort && reply.reason == AbortReason.SEGMENTATION_NOT_SUPPORTED -> {
                    // The answer did not fit one reply: split the request.
                    val half = chunk.size / 2
                    readChunk(dest, chunk.subList(0, half), out)
                    readChunk(dest, chunk.subList(half, chunk.size), out)
                    return
                }
                reply is Apdu.Reject && reply.reason == RejectReason.UNRECOGNIZED_SERVICE -> rpmSupported = false
                reply is Apdu.Error && reply.errorClass == BacnetError.CLASS_SERVICES.toLong() -> rpmSupported = false
                else -> Unit
            }
        }
        for ((obj, ref) in chunk) {
            out[obj to ref.property] = try { Outcome(readOne(dest, obj, ref.property, ref.index), null) } catch (e: Exception) { Outcome(null, e) }
        }
    }

    private fun poll(period: Long) {
        try {
            val addresses = LinkedHashSet<String>()
            for (sub in subs) if (sub.period == period) addresses += sub.addresses
            if (addresses.isEmpty()) return
            val now = Instant.now()
            val dest = bound
            if (dest == null || !status.connected) {
                publish(period, addresses.associateWith { id -> lostValue(id, status.lastError ?: "device ${config.name} is not connected", now) })
                return
            }
            val valid = addresses.mapNotNull { id -> runCatching { id to address(id) }.getOrNull() }
            val refs = valid.filter { !it.second.isObject }.map { it.second.obj to PropertyRef(it.second.property) }.distinct()
            val results = readProperties(dest, refs)
            if (refs.isNotEmpty() && results.values.all { it.error is TimeoutException || it.error is NotConnectedException }) {
                val msg = results.values.first().error?.message ?: "no reply"
                lost(msg)
                publish(period, addresses.associateWith { id -> lostValue(id, msg, now) })
                return
            }
            val values = LinkedHashMap<String, QualifiedValue>()
            for (id in addresses) {
                val a = valid.firstOrNull { it.first == id }?.second
                values[id] = when {
                    a == null -> bad(id, QualityCode.Bad_NotFound, "not a BACnet item address", now)
                    a.isObject -> bad(id, QualityCode.Bad_NotReadable, "an object is not a value", now)
                    else -> toValue(id, a, results[a.obj to a.property], now)
                }
            }
            publish(period, values)
        } catch (t: Throwable) {
            log.log(Level.FINE, "poll of ${config.name} failed", t)
        }
    }

    private fun lost(message: String) {
        if (!status.connected) return
        bound = null
        nextAttempt = System.nanoTime() + config.reconnectMs * 1_000_000L
        setStatus(ConnectionStatus(ConnectionState.RECONNECTING, connected = false, lastError = message, since = Instant.now(),
            requestCount = requestCount.get(), failureCount = failureCount.get()))
        markLost(message)
    }

    private fun markLost(message: String) {
        val now = Instant.now()
        for ((id, v) in cache) cache[id] = v.copy(quality = Quality.of(QualityCode.Bad_NotConnected, message), timestamp = now)
    }

    private fun lostValue(id: String, message: String, now: Instant): QualifiedValue {
        val last = cache[id]
        return QualifiedValue(id, last?.value, Quality.of(QualityCode.Bad_NotConnected, message), now, last?.sourceTimestamp)
    }

    private fun publish(period: Long, values: Map<String, QualifiedValue>) {
        for ((id, v) in values) cache[id] = v
        for (sub in subs) {
            if (sub.period != period) continue
            for (address in sub.addresses) {
                val sample = values[address] ?: continue
                try {
                    sub.listener?.invoke(sample)
                    sub.raw?.onSample(sample.value, sample.quality, (sample.sourceTimestamp ?: sample.timestamp).toEpochMilli())
                } catch (t: Throwable) {
                    log.log(Level.FINE, "listener failed", t)
                }
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun address(id: String): BacnetItemAddress = parsed[id] ?: BacnetItemAddress.parse(id).also { if (parsed.size < 100_000) parsed[id] = it }

    private fun toValue(id: String, a: BacnetItemAddress, outcome: Outcome?, now: Instant): QualifiedValue {
        if (outcome == null) return bad(id, QualityCode.Bad_NotFound, "no answer for this property", now)
        outcome.error?.let { return QualifiedValue(id, cache[id]?.value, failure(it), now, null) }
        val r = outcome.result!!
        if (!r.ok) return QualifiedValue(id, null, errorQuality(r.errorClass ?: -1, r.errorCode ?: -1), now, null)
        return try {
            QualifiedValue(id, BacnetValues.forItem(a.obj.type, a.item!!, r.value), Quality.of(QualityCode.Good), now, now)
        } catch (e: IllegalArgumentException) {
            QualifiedValue(id, null, Quality.of(QualityCode.Bad_TypeMismatch, e.message), now, null)
        }
    }

    private fun bad(id: String, code: QualityCode, diagnostic: String, now: Instant) = QualifiedValue(id, null, Quality.of(code, diagnostic), now, null)

    private fun failure(error: Throwable): Quality {
        val cause = if (error is java.util.concurrent.CompletionException || error is ExecutionException) error.cause ?: error else error
        return when (cause) {
            is TimeoutException -> Quality.of(QualityCode.Bad_Timeout, cause.message)
            is NotConnectedException -> Quality.of(QualityCode.Bad_NotConnected, cause.message)
            is IllegalStateException -> Quality.of(QualityCode.Bad_DeviceFailure, cause.message)
            else -> Quality.of(QualityCode.Bad_DeviceFailure, cause.message)
        }
    }

    private fun errorQuality(cls: Long, code: Long): Quality {
        val text = "BACnet error class $cls code $code"
        return when (code) {
            BacnetError.CODE_UNKNOWN_OBJECT.toLong(), BacnetError.CODE_UNKNOWN_PROPERTY.toLong() -> Quality.of(QualityCode.Bad_NotFound, text)
            BacnetError.CODE_WRITE_ACCESS_DENIED.toLong() -> Quality.of(QualityCode.Bad_NotWritable, text)
            BacnetError.CODE_VALUE_OUT_OF_RANGE.toLong() -> Quality.of(QualityCode.Bad_OutOfRange, text)
            BacnetError.CODE_INVALID_DATA_TYPE.toLong() -> Quality.of(QualityCode.Bad_TypeMismatch, text)
            else -> Quality.of(QualityCode.Bad_DeviceFailure, text)
        }
    }

    private fun waitBoundMs(): Long {
        val local = localLookup(config.localDevice) ?: return 5_000
        return local.config.apduTimeoutMs * (local.config.apduRetries + 1) + 1_000
    }

    private fun setStatus(next: ConnectionStatus) {
        status = next
        for (cb in statusListeners) runCatching { cb(next) }
    }

    private inner class LiveSub(
        val period: Long,
        val addresses: List<String>,
        val listener: ((QualifiedValue) -> Unit)?,
        val raw: RawSample?,
    ) : TagSubscription {
        override val id: String = UUID.randomUUID().toString()
        override val samplingIntervalMs: Double get() = period.toDouble()
        override val publishingIntervalMs: Double get() = period.toDouble()
        override fun close() {
            subs.remove(this)
            if (subs.none { it.period == period }) periods.remove(period)?.cancel(false)
        }
    }
}
