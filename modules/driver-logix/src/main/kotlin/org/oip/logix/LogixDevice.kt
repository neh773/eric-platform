package org.oip.logix

import java.io.ByteArrayOutputStream
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
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ControlLogix / CompactLogix client. Symbolic reads and writes are batched with the Multiple
 * Service Packet service (0x0A) so one EtherNet/IP frame carries many tags.
 *
 * Comms loss keeps the last value and applies [LogixDeviceConfig.commsLossQuality]. A stalled
 * response spends the request timeout; a dropped socket fails on the next scan, inside one scan
 * period plus that timeout (SPEC §5).
 *
 * https://literature.rockwellautomation.com/idc/groups/literature/documents/pm/1756-pm020_-en-p.pdf
 */
class LogixDevice(val config: LogixDeviceConfig) : TagSource, AutoCloseable {
    private val log = Logger.getLogger("org.oip.logix.${config.name}")
    private val pool = EipPool(config)
    private val cache = ConcurrentHashMap<String, QualifiedValue>()
    private val lastGood = ConcurrentHashMap<String, Any?>()
    private val subs = CopyOnWriteArrayList<LiveSub>()
    private val periods = ConcurrentHashMap<String, Long>()
    private val tasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val scanning = ConcurrentHashMap<String, AtomicBoolean>()
    private val statusListeners = CopyOnWriteArrayList<(ConnectionStatus) -> Unit>()
    private val delays = CopyOnWriteArrayList<Long>()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "logix-scan-${config.name}").apply { isDaemon = true }
    }

    @Volatile private var started = false
    @Volatile private var everConnected = false
    @Volatile private var supervisor: Thread? = null
    @Volatile private var model: LogixModel = LogixModel.EMPTY
    @Volatile private var seenGeneration = -1
    private var generationCheckedAt = 0L
    @Volatile private var status = ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now())
    @Volatile var identity: String = ""
        private set

    override val sourceId: String get() = config.name
    val reconnectDelaysMs: List<Long> get() = delays.toList()

    /** EtherNet/IP frames sent after the session came up. One frame may carry many tag services. */
    val readRequestCount: Long get() = pool.requests

    /**
     * `connected` for a Class 3 connection, `unconnected` when the Forward Open was refused and
     * requests go as Unconnected Send along the route. doc source: FEATURES DEV-009
     */
    val messaging: String get() = if (pool.session().unconnected) "unconnected" else "connected"

    init {
        periods["Default"] = config.defaultScanPeriodMs
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        everConnected = false
        supervisor = Thread({ supervise() }, "logix-conn-${config.name}").apply { isDaemon = true; start() }
        periods.forEach { (name, period) -> schedule(name, period) }
    }

    @Synchronized
    fun stop() {
        if (!started && supervisor == null) return
        started = false
        supervisor?.interrupt()
        tasks.values.forEach { it.cancel(false) }
        tasks.clear()
        pool.close()
        supervisor?.join(1_000)
        supervisor = null
        setStatus(status.copy(state = ConnectionState.DISCONNECTED, connected = false, since = Instant.now()))
    }

    override fun close() {
        stop()
        pool.shutdown()
        scheduler.shutdownNow()
    }

    override fun connectionStatus(): ConnectionStatus = status

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
            requestCount = pool.requests,
            errorCount = pool.errors,
            timeoutCount = pool.timeouts,
            averageResponseTimeMs = pool.averageMillis(),
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
        val known = model
        if (known.symbols.isEmpty() && pool.connected) {
            runCatching { model = LogixModel.load(pool.session(), config.profile) }.onFailure { log.log(Level.FINE, "browse", it) }
        }
        val tree = model
        if (nodeId.isEmpty()) {
            val roots = ArrayList<SourceNode>()
            roots += SourceNode(CONTROLLER, "Controller:Global", "Object", readable = false, writable = false)
            for (program in tree.programs()) {
                roots += SourceNode("Program:$program", "Program:$program", "Object", readable = false, writable = false)
            }
            return roots
        }
        if (nodeId == CONTROLLER) {
            return tree.controllerTags().filter { browsable(it) }.map { nodeFor(it, it.name) }
        }
        if (!config.profile.programScope && nodeId.startsWith("Program:")) return emptyList()
        if (!config.profile.templates) {
            // Micro800: structures and BOOL arrays are not browsed; their members are read by path.
            // doc source: FEATURES DEV-017
            return emptyList()
        }
        if (nodeId.startsWith("Program:") && !nodeId.contains(".")) {
            val program = nodeId.removePrefix("Program:")
            return tree.programTags(program).map { nodeFor(it, "$nodeId.${it.name}") }
        }
        val sym = tree.symbol(nodeId)
        if (sym == null) {
            // A structure member of a tag: a nested Add-On Instruction or UDT, at any depth.
            // doc source: FEATURES DEV-008
            val parsed = try {
                parseAddress(nodeId)
            } catch (_: AddressException) {
                return emptyList()
            }
            val nested = tree.structureAt(parsed) ?: return emptyList()
            if (nested.string) return emptyList()
            return memberNodes(tree, nodeId, nested)
        }
        if (sym.boolArray && sym.dim0 > 0 && sym.dim1 == 0) return boolBitNodes(nodeId, sym.dim0)
        val template = if (sym.structure) tree.templates[sym.typeId] else null
        if (template == null || template.string) return emptyList()
        return memberNodes(tree, nodeId, template)
    }

    /**
     * Members of [template] under [nodeId]. A member that is itself a structure (not a STRING)
     * is a folder, so a nested AOI or UDT can be browsed one level further.
     */
    private fun memberNodes(tree: LogixModel, nodeId: String, template: TemplateRec): List<SourceNode> =
        template.members.filter { !it.hidden && it.name.isNotEmpty() }.map { member ->
            val child = "$nodeId.${member.name}"
            val folder = member.structure && tree.templates[member.elementType]?.string != true
            SourceNode(child, member.name, if (folder) "Object" else "Variable", readable = !folder, writable = !folder)
        }

    /**
     * Micro800 browse lists atomic globals and arrays of them. UDTs, system structures and BOOL
     * arrays are left out, as the Micro800 page describes. doc source: FEATURES DEV-017
     */
    private fun browsable(sym: SymbolRec): Boolean =
        config.profile.templates || (!sym.structure && !sym.boolArray)

    /** A folder or a tag address the OPC UA device catalog can expose without browsing first. */
    fun describe(nodeId: String): SourceNode? {
        if (nodeId.isEmpty()) return null
        if (!config.profile.programScope && nodeId.startsWith("Program:")) return null
        if (nodeId == CONTROLLER || (nodeId.startsWith("Program:") && '.' !in nodeId)) {
            return SourceNode(nodeId, nodeId, "Object", readable = false, writable = false)
        }
        val sym = model.symbol(nodeId)
        if (sym != null && !sym.program) return nodeFor(sym, nodeId)
        val parsed = try {
            parseAddress(nodeId)
        } catch (_: AddressException) {
            return null
        }
        val nested = model.structureAt(parsed)
        if (nested != null && !nested.string) {
            return SourceNode(nodeId, nodeId.substringAfterLast('.'), "Object", readable = false, writable = false)
        }
        return SourceNode(nodeId, nodeId.substringAfterLast('.'), "Variable", readable = true, writable = true)
    }

    fun activeScanPeriodMs(address: String): Long? {
        var found: Long? = null
        for (sub in subs) {
            if (address !in sub.addresses) continue
            val period = sub.samplingIntervalMs.toLong()
            if (found == null || period < found) found = period
        }
        return found
    }

    override fun read(nodeIds: List<String>): List<QualifiedValue> {
        val now = Instant.now()
        if (!pool.connected) return nodeIds.map { lost(it, now, "not connected") }
        return try {
            doRead(nodeIds, now)
        } catch (e: Exception) {
            nodeIds.map { lost(it, now, e.message) }
        }
    }

    override fun write(nodeId: String, value: Any?): WriteResult {
        val now = Instant.now()
        if (!pool.connected) return WriteResult(nodeId, Quality.of(lossCode(nodeId), "not connected"), now)
        return try {
            doWrite(nodeId, value, now)
        } catch (e: TypeMismatchException) {
            WriteResult(nodeId, Quality.of(QualityCode.Bad_TypeMismatch, e.message), now)
        } catch (e: OutOfRangeException) {
            WriteResult(nodeId, Quality.of(QualityCode.Bad_OutOfRange, e.message), now)
        } catch (e: AddressException) {
            WriteResult(nodeId, Quality.of(QualityCode.Bad_ConfigurationError, e.message), now)
        } catch (e: CipStatusException) {
            WriteResult(nodeId, Quality.of(qualityForCip(e.reply.status, e.reply.extendedStatus), e.message), now)
        } catch (e: Exception) {
            WriteResult(nodeId, Quality.of(qualityFor(e), e.message), now)
        }
    }

    override fun subscribe(
        nodeIds: List<String>,
        samplingIntervalMs: Double,
        publishingIntervalMs: Double,
        listener: (QualifiedValue) -> Unit,
    ): TagSubscription {
        val period = samplingIntervalMs.toLong().coerceAtLeast(10)
        val existing = periods.entries.firstOrNull { it.value == period }?.key
        val name = existing ?: "ms-$period".also { periods[it] = period; if (started) schedule(it, period) }
        val sub = LiveSub(UUID.randomUUID().toString(), name, nodeIds, period.toDouble(), listener)
        subs += sub
        for (addr in nodeIds) listener(cache[addr] ?: waiting(addr))
        return sub
    }

    fun attachRaw(periodMs: Long, address: String, listener: RawSample): AutoCloseable {
        val period = periodMs.coerceAtLeast(10)
        val existing = periods.entries.firstOrNull { it.value == period }?.key
        val name = existing ?: "ms-$period".also { periods[it] = period; if (started) schedule(it, period) }
        val sub = shared(name, period.toDouble())
        sub.addresses.add(address)
        val list = sub.raw.computeIfAbsent(address) { CopyOnWriteArrayList() }
        list += listener
        val initial = cache[address]
        val quality = initial?.quality ?: Quality.of(QualityCode.Bad_WaitingForInitialData)
        try {
            listener.onSample(initial?.value, quality, System.currentTimeMillis())
        } catch (t: Throwable) {
            log.log(Level.FINE, "listener failed", t)
        }
        return AutoCloseable {
            list.remove(listener)
            if (list.isEmpty()) {
                sub.raw.remove(address, list)
                if (sub.listener == null) sub.addresses.remove(address)
            }
        }
    }

    private fun doRead(nodeIds: List<String>, now: Instant): List<QualifiedValue> {
        val planned = ArrayList<Planned>(nodeIds.size)
        val produced = HashMap<String, QualifiedValue>(nodeIds.size)
        for (raw in nodeIds) {
            if (raw in produced) continue
            val plan = try {
                plan(raw)
            } catch (e: AddressException) {
                produced[raw] = sample(raw, null, Quality.of(QualityCode.Bad_ConfigurationError, e.message), now)
                continue
            }
            planned += plan
        }
        val batched = ArrayList<Planned>()
        for (plan in planned) {
            if (plan.alone) {
                produced[plan.address] = finish(plan, readComplete(plan), now)
            } else {
                batched += plan
            }
        }
        if (batched.isNotEmpty()) {
            val packets = Cip.pack(batched.map { it.request }, pool.budget())
            var cursor = 0
            for (packet in packets) {
                val reply = pool.request(packet)
                val msp = (packet[0].toInt() and 0xFF) == Cip.MSP
                val parts = if (msp && reply.data.size >= 2) Cip.parseMultiple(reply.data) else listOf(reply)
                if (parts.isEmpty()) {
                    val q = Quality.of(qualityForCip(reply.status, reply.extendedStatus), cipText(reply))
                    if (cursor < batched.size) {
                        val plan = batched[cursor++]
                        produced[plan.address] = sample(plan.address, retained(plan.address), q, now)
                    }
                    continue
                }
                for (part in parts) {
                    if (cursor >= batched.size) break
                    val plan = batched[cursor++]
                    produced[plan.address] = finish(plan, part, now)
                }
            }
        }
        return nodeIds.map { produced[it] ?: lost(it, now, "no reply") }
    }

    private fun doWrite(nodeId: String, value: Any?, now: Instant): WriteResult {
        val parsed = scoped(parseAddress(nodeId))
        val index = model.boolArrayIndex(parsed)
        val path = cipPath(parsed, index)
        if (parsed.bit != null && index == null) return writeBit(parsed, parsed.bit!!, value, now)
        if (value is String) {
            val template = model.templateOf(parsed)
            if (template == null) {
                // A Micro800 STRING is a CIP SHORT_STRING, not a template. doc source: FEATURES DEV-017
                if (probeType(path) != Cip.SHORT_STRING) throw TypeMismatchException("'$nodeId' is not a STRING")
                val bytes = Cip.encodeShortString(value)
                return writePayload(nodeId, path, Cip.atomicType(Cip.SHORT_STRING), 1, bytes, now, value)
            }
            if (!template.string) throw TypeMismatchException("'$nodeId' is not a STRING")
            val bytes = Cip.encodeString(value, template.dataCapacity, template.size.coerceAtLeast(4 + template.dataCapacity))
            return writePayload(nodeId, path, Cip.structType(template.handle), 1, bytes, now, value)
        }
        if (value is Map<*, *>) {
            val template = model.templateOf(parsed) ?: throw TypeMismatchException("'$nodeId' is not a structure")
            if (template.string) throw TypeMismatchException("'$nodeId' is a STRING")
            val bytes = encodeTemplate(template, value)
            return writePayload(nodeId, path, Cip.structType(template.handle), 1, bytes, now, value)
        }
        if (value is List<*>) {
            val sym = rootSymbol(parsed) ?: throw TypeMismatchException("'$nodeId' is not an array")
            if (sym.structure || parsed.steps.any { it is Step.At } || sym.dim0 <= 0 || sym.dim1 != 0) {
                throw TypeMismatchException("'$nodeId' is not a one-dimensional array")
            }
            if (value.size != sym.dim0) throw OutOfRangeException("array length ${value.size}")
            val type = sym.typeId
            val es = Cip.atomicSize(type)
            if (es < 0) throw TypeMismatchException("array type")
            val payload = ByteArray(value.size * es)
            value.forEachIndexed { i, item -> Cip.encode(type, item).copyInto(payload, i * es) }
            return writePayload(nodeId, path, Cip.atomicType(type), value.size, payload, now, value)
        }
        val type = model.atomicType(parsed) ?: probeType(path)
        val payload = Cip.encode(type, value)
        val decoded = Cip.decode(type, payload, 0)
        return writePayload(nodeId, path, Cip.atomicType(type), 1, payload, now, decoded)
    }

    /**
     * Write Tag (0x4D) when the service fits in the CIP connection. A larger value uses Write Tag
     * Fragmented (0x53): each request carries the same element count and a UDINT byte offset, and
     * the next offset advances by the bytes accepted. A non-zero status on any fragment is the
     * write result; the call does not report success after a later fragment fails.
     * 1756-PM020 Write Tag Fragmented.
     */
    private fun writePayload(
        nodeId: String,
        path: ByteArray,
        type: ByteArray,
        elements: Int,
        payload: ByteArray,
        now: Instant,
        stored: Any?,
    ): WriteResult {
        val reply = writeFitting(path, type, elements, payload)
        if (reply.status != 0) {
            return WriteResult(nodeId, Quality.of(qualityForCip(reply.status, reply.extendedStatus), cipText(reply)), now)
        }
        lastGood[nodeId] = stored
        sample(nodeId, stored, Quality.of(QualityCode.Good), now)
        return WriteResult(nodeId, Quality.of(QualityCode.Good), now)
    }

    private fun writeFitting(path: ByteArray, type: ByteArray, elements: Int, payload: ByteArray): Reply {
        val whole = Cip.writeTag(path, type, elements, payload, null)
        if (whole.size <= pool.budget()) return pool.request(whole)
        val overhead = Cip.writeTag(path, type, elements, ByteArray(0), 0).size
        val room = pool.budget() - overhead
        if (room < 1) return pool.request(whole)
        var offset = 0
        var last = Reply(Cip.WRITE_FRAG, 0x01, IntArray(0), ByteArray(0))
        while (offset < payload.size) {
            val n = minOf(room, payload.size - offset)
            val chunk = payload.copyOfRange(offset, offset + n)
            last = pool.request(Cip.writeTag(path, type, elements, chunk, offset))
            if (last.status != 0) return last
            offset += n
        }
        return last
    }

    private fun encodeTemplate(template: TemplateRec, values: Map<*, *>): ByteArray {
        val bytes = ByteArray(template.size)
        for (member in template.members) {
            if (member.hidden || member.name.isEmpty()) continue
            if (!values.containsKey(member.name)) continue
            val raw = values[member.name]
            val bit = member.elementType == Cip.BOOL && !member.structure && (member.type and 0x6000) == 0
            if (bit) {
                val on = when (raw) {
                    is Boolean -> raw
                    is Number -> raw.toLong() != 0L
                    else -> throw TypeMismatchException(member.name)
                }
                val i = member.offset
                if (i !in bytes.indices || member.info !in 0..7) throw OutOfRangeException(member.name)
                val mask = 1 shl member.info
                val cur = bytes[i].toInt() and 0xFF
                bytes[i] = (if (on) cur or mask else cur and mask.inv()).toByte()
                continue
            }
            if (member.structure && member.info == 0) {
                // A nested AOI or UDT member takes a map; a nested STRING takes text.
                val nested = model.templates[member.elementType] ?: throw TypeMismatchException(member.name)
                val encoded = if (nested.string) {
                    val text = raw as? String ?: throw TypeMismatchException(member.name)
                    Cip.encodeString(text, nested.dataCapacity, nested.size.coerceAtLeast(4 + nested.dataCapacity))
                } else {
                    encodeTemplate(nested, raw as? Map<*, *> ?: throw TypeMismatchException(member.name))
                }
                if (member.offset < 0 || member.offset + encoded.size > bytes.size) throw OutOfRangeException(member.name)
                encoded.copyInto(bytes, member.offset)
                continue
            }
            if (member.structure) throw TypeMismatchException(member.name)
            if (member.info > 0) {
                val list = raw as? List<*> ?: throw TypeMismatchException(member.name)
                if (list.size != member.info) throw OutOfRangeException(member.name)
                val es = Cip.atomicSize(member.elementType)
                if (es < 0) throw TypeMismatchException(member.name)
                var at = member.offset
                for (item in list) {
                    if (at + es > bytes.size) throw OutOfRangeException(member.name)
                    Cip.encode(member.elementType, item).copyInto(bytes, at)
                    at += es
                }
                continue
            }
            val encoded = Cip.encode(member.elementType, raw)
            if (member.offset < 0 || member.offset + encoded.size > bytes.size) throw OutOfRangeException(member.name)
            encoded.copyInto(bytes, member.offset)
        }
        return bytes
    }

    private fun rootSymbol(parsed: ParsedAddress): SymbolRec? {
        val names = parsed.steps.filterIsInstance<Step.Sym>().map { it.name }
        if (names.isEmpty()) return null
        val key = if (names[0].startsWith("Program:")) {
            if (names.size == 1) names[0] else "${names[0]}.${names[1]}"
        } else {
            names[0]
        }
        return model.symbol(key)
    }

    private fun writeBit(parsed: ParsedAddress, bit: Int, value: Any?, now: Instant): WriteResult {
        val on = when (value) {
            is Boolean -> value
            is Number -> value.toLong() != 0L
            else -> throw TypeMismatchException("bit value")
        }
        val parent = parsed.withoutBit()
        val path = encodePath(parent)
        val read = readComplete(Planned(parent.raw, path, 1, -1, false, Cip.readTag(path, 1)))
        if (read.status != 0) {
            return WriteResult(parsed.raw, Quality.of(qualityForCip(read.status, read.extendedStatus), cipText(read)), now)
        }
        if (read.data.size < 4) throw TypeMismatchException("bit parent")
        val type = u16(read.data, 0)
        val width = Cip.atomicSize(type)
        if (width !in 1..8 || bit >= width * 8) throw OutOfRangeException("bit $bit")
        val current = Cip.decode(type, read.data, 2) as? Number ?: throw TypeMismatchException("bit parent")
        var bits = if (Cip.integerType(type)) i64Of(read.data, 2, width) else current.toLong()
        bits = if (on) bits or (1L shl bit) else bits and (1L shl bit).inv()
        val payload = if (Cip.integerType(type)) {
            // Raw little-endian bits, so an unsigned type with its top bit set round-trips.
            ByteArray(width) { ((bits ushr (8 * it)) and 0xFF).toByte() }
        } else when (width) {
            1 -> Cip.encode(type, bits.toInt())
            2 -> Cip.encode(type, bits.toInt())
            4 -> Cip.encode(type, bits.toInt())
            else -> Cip.encode(Cip.LINT, bits)
        }
        val reply = pool.request(Cip.writeTag(path, Cip.atomicType(type), 1, payload))
        if (reply.status != 0) {
            return WriteResult(parsed.raw, Quality.of(qualityForCip(reply.status, reply.extendedStatus), cipText(reply)), now)
        }
        lastGood[parsed.raw] = on
        sample(parsed.raw, on, Quality.of(QualityCode.Good), now)
        return WriteResult(parsed.raw, Quality.of(QualityCode.Good), now)
    }

    /**
     * Micro800 has no program scope: a `Program:` address is a configuration error and is not
     * sent. doc source: FEATURES DEV-017
     */
    private fun scoped(parsed: ParsedAddress): ParsedAddress {
        val first = parsed.steps.firstOrNull() as? Step.Sym
        if (!config.profile.programScope && first != null && first.name.startsWith("Program:")) {
            throw AddressException("the ${config.profile.id} profile has global variables only: '${parsed.raw}'")
        }
        return parsed
    }

    private fun plan(raw: String): Planned {
        val parsed = scoped(parseAddress(raw))
        val index = model.boolArrayIndex(parsed)
        val path = cipPath(parsed, index)
        val bit = if (index != null) -1 else parsed.bit ?: -1
        val request = Cip.readTag(path, 1)
        val size = model.templateOf(parsed)?.size ?: 0
        val alone = size > 0 && size + request.size > pool.budget() / 2
        return Planned(raw, path, 1, bit, alone, request)
    }

    private fun cipPath(parsed: ParsedAddress, boolIndex: Int?): ByteArray {
        if (boolIndex == null) return encodePath(parsed.withoutBit())
        val names = parsed.steps.filterIsInstance<Step.Sym>()
        val steps = ArrayList<Step>(names.size + 1)
        steps += names
        steps += Step.At(listOf(boolIndex))
        return encodePath(ParsedAddress(parsed.raw, steps))
    }

    /**
     * Request path for [address]. With [LogixDeviceConfig.symbolInstanceAddressing] and a root tag
     * the catalog knows, the root is Symbol class 0x6B with the tag's instance id; a program tag is
     * the program's instance, then the tag's (1756-PM020 symbol instance addressing, the same
     * nesting the symbol browse uses). Member names and element indexes follow as usual. Anything
     * else is the ANSI extended symbolic path.
     * doc source: FEATURES DEV-010
     */
    private fun encodePath(address: ParsedAddress): ByteArray {
        if (!config.symbolInstanceAddressing || !config.profile.instanceAddressing) return Cip.pathOf(address)
        val steps = address.steps
        val first = steps.firstOrNull() as? Step.Sym ?: return Cip.pathOf(address)
        val tree = model
        val prefix: ByteArray
        val consumed: Int
        if (first.name.startsWith("Program:")) {
            val second = steps.getOrNull(1) as? Step.Sym ?: return Cip.pathOf(address)
            val program = tree.symbol(first.name)?.takeIf { it.program } ?: return Cip.pathOf(address)
            val tag = tree.symbol("${first.name}.${second.name}") ?: return Cip.pathOf(address)
            if (!instanceFits(program.instanceId) || !instanceFits(tag.instanceId)) return Cip.pathOf(address)
            prefix = Cip.classInstance(Cip.SYMBOL, program.instanceId) + Cip.classInstance(Cip.SYMBOL, tag.instanceId)
            consumed = 2
        } else {
            val tag = tree.symbol(first.name)?.takeIf { it.programName == null && !it.program } ?: return Cip.pathOf(address)
            if (!instanceFits(tag.instanceId)) return Cip.pathOf(address)
            prefix = Cip.classInstance(Cip.SYMBOL, tag.instanceId)
            consumed = 1
        }
        return prefix + Cip.pathOf(ParsedAddress(address.raw, steps.drop(consumed)))
    }

    private fun instanceFits(instance: Int): Boolean = instance in 1..0xFFFF

    private fun readComplete(plan: Planned): Reply {
        var reply = pool.request(plan.request)
        if (reply.status != 0x06) return reply
        val buf = ByteArrayOutputStream()
        val typeLen = typeLen(reply.data)
        if (reply.data.size < typeLen) return reply
        buf.write(reply.data, 0, typeLen)
        var got = reply.data.size - typeLen
        buf.write(reply.data, typeLen, got)
        var guard = 0
        while (reply.status == 0x06 && guard++ < 64) {
            reply = pool.request(Cip.readFragment(plan.path, plan.elements, got))
            if (reply.status != 0 && reply.status != 0x06) return reply
            val nType = typeLen(reply.data)
            if (reply.data.size <= nType) break
            buf.write(reply.data, nType, reply.data.size - nType)
            got += reply.data.size - nType
        }
        return Reply(reply.service, reply.status, reply.extended, buf.toByteArray())
    }

    private fun finish(plan: Planned, reply: Reply, now: Instant): QualifiedValue {
        if (reply.status != 0 && reply.status != 0x06) {
            return sample(plan.address, retained(plan.address), Quality.of(qualityForCip(reply.status, reply.extendedStatus), cipText(reply)), now)
        }
        if (!config.profile.templates && reply.data.size >= 2 && u16(reply.data, 0) == Cip.STRUCT_MARK) {
            // Micro800 paths must reach an atomic value or an array of one. doc source: FEATURES DEV-017
            return sample(plan.address, retained(plan.address), Quality.of(QualityCode.Bad_TypeMismatch, "address a member of the structure"), now)
        }
        return try {
            val value = decode(reply.data, plan.bit)
            if (reply.status == 0x06 && value == null) {
                sample(plan.address, retained(plan.address), Quality.of(QualityCode.Bad, "partial read"), now)
            } else {
                if (value != null) lastGood[plan.address] = value
                sample(plan.address, value, Quality.of(QualityCode.Good), now)
            }
        } catch (e: Exception) {
            sample(plan.address, retained(plan.address), Quality.of(QualityCode.Bad_TypeMismatch, e.message), now)
        }
    }

    private fun decode(data: ByteArray, bit: Int): Any? {
        if (data.size < 2) return null
        val mark = u16(data, 0)
        if (mark == Cip.STRUCT_MARK) {
            if (data.size < 4) return null
            val handle = u16(data, 2)
            val body = data.copyOfRange(4, data.size)
            val template = model.byHandle[handle]
            if (template?.string == true) return Cip.decodeString(body)
            if (template != null) return structMap(template, body)
            return body
        }
        val type = mark
        if (type == Cip.SHORT_STRING) return Cip.decodeShortString(data, 2)
        if (Cip.atomicSize(type) < 0 || data.size < 2 + Cip.atomicSize(type)) return null
        val value = Cip.decode(type, data, 2)
        if (bit < 0) return value
        val n = (value as? Number)?.toLong() ?: return value
        return ((n ushr bit) and 1L) != 0L
    }

    private fun structMap(template: TemplateRec, body: ByteArray): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (member in template.members) {
            if (member.hidden || member.name.isEmpty()) continue
            if (member.offset < 0 || member.offset >= body.size) continue
            when {
                member.elementType == Cip.BOOL && !member.structure && member.info < 8 && (member.type and 0x6000) == 0 -> {
                    out[member.name] = ((body[member.offset].toInt() ushr member.info) and 1) != 0
                }
                member.structure && member.info == 0 -> {
                    val nested = model.templates[member.elementType]
                    if (nested != null && member.offset + nested.size <= body.size) {
                        val slice = body.copyOfRange(member.offset, member.offset + nested.size)
                        out[member.name] = if (nested.string) Cip.decodeString(slice) else structMap(nested, slice)
                    }
                }
                !member.structure && member.info == 0 -> out[member.name] = Cip.decode(member.elementType, body, member.offset)
                !member.structure && member.info > 0 && member.elementType != Cip.BOOL -> {
                    val es = Cip.atomicSize(member.elementType)
                    if (es > 0) {
                        val n = member.info.coerceAtMost((body.size - member.offset) / es)
                        out[member.name] = (0 until n).map { Cip.decode(member.elementType, body, member.offset + it * es) }
                    }
                }
            }
        }
        return out
    }

    private fun probeType(path: ByteArray): Int {
        val reply = readComplete(Planned("", path, 1, -1, true, Cip.readTag(path, 1)))
        if (reply.status != 0 || reply.data.size < 2) {
            if (reply.status != 0) throw CipStatusException(reply)
            throw TypeMismatchException("type probe failed")
        }
        val mark = u16(reply.data, 0)
        if (mark == Cip.STRUCT_MARK) throw TypeMismatchException("structure write needs a STRING or a member")
        return mark
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
        val wanted = LinkedHashSet<String>()
        for (sub in subs) if (sub.scanClass == scanClass) wanted.addAll(sub.addresses)
        if (wanted.isEmpty()) return
        val now = Instant.now()
        val values = if (!pool.connected) {
            wanted.associateWith { lost(it, now, "not connected") }
        } else {
            try {
                doRead(wanted.toList(), now).associateBy { it.address }
            } catch (e: Exception) {
                wanted.associateWith { lost(it, now, e.message) }
            }
        }
        for (sub in subs) {
            if (sub.scanClass != scanClass) continue
            for (addr in sub.addresses.toList()) {
                val sample = values[addr] ?: continue
                sub.listener?.let { cb ->
                    try { cb(sample) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
                }
                val raw = sub.raw[addr] ?: continue
                for (cb in raw) {
                    try { cb.onSample(sample.value, sample.quality, sample.timestamp.toEpochMilli()) } catch (t: Throwable) {
                        log.log(Level.FINE, "listener failed", t)
                    }
                }
            }
        }
    }

    private fun supervise() {
        var attempt = 0
        var identityAt = 0L
        while (started && !Thread.currentThread().isInterrupted) {
            if (pool.connected) {
                if (!everConnected) everConnected = true
                val period = config.identityRequestFrequencyMs
                val now = System.nanoTime()
                if (period > 0 && now - identityAt > period * 1_000_000L) {
                    identityAt = now
                    try {
                        val reply = pool.session().request(Cip.message(Cip.GET_ALL, byteArrayOf(0x20, 0x01, 0x24, 0x01)))
                        if (reply.status != 0) throw IOException("identity status 0x${reply.status.toString(16)}")
                    } catch (e: Exception) {
                        if (!started) break
                        pool.close()
                        publishLoss(e.message ?: "identity failed")
                        continue
                    }
                }
                if (config.automaticRebrowse && config.profile.watchesCatalog && now - generationCheckedAt > 200_000_000L) {
                    generationCheckedAt = now
                    try {
                        val gen = catalogGeneration()
                        if (gen >= 0 && gen != seenGeneration) replaceModel()
                    } catch (e: Exception) {
                        log.log(Level.FINE, "rebrowse", e)
                    }
                }
                sleep(100)
                continue
            }
            val state = if (everConnected || attempt > 0) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
            setStatus(status.copy(state = state, connected = false, reconnectAttempt = attempt, since = Instant.now()))
            if (everConnected) publishLoss("connection lost")
            try {
                pool.connect()
                identity = pool.product()
                if (config.automaticRebrowse) {
                    runCatching { replaceModel() }.onFailure { log.log(Level.FINE, "browse", it) }
                }
                everConnected = true
                attempt = 0
                identityAt = System.nanoTime()
                setStatus(status.copy(state = ConnectionState.CONNECTED, connected = true, lastError = null, reconnectAttempt = 0, since = Instant.now()))
            } catch (e: Exception) {
                if (!started || e is InterruptedException) break
                val delay = backoff(attempt)
                delays += delay
                attempt++
                setStatus(status.copy(state = ConnectionState.RECONNECTING, connected = false, lastError = e.message, reconnectAttempt = attempt, since = Instant.now()))
                sleep(delay)
            }
        }
    }

    private fun publishLoss(diagnostic: String) {
        val now = Instant.now()
        val seen = HashSet<String>()
        for (sub in subs) {
            for (addr in sub.addresses) {
                if (!seen.add(addr)) continue
                val sample = lost(addr, now, diagnostic)
                sub.listener?.let { cb ->
                    try { cb(sample) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
                }
                for (cb in sub.raw[addr].orEmpty()) {
                    try { cb.onSample(sample.value, sample.quality, now.toEpochMilli()) } catch (t: Throwable) {
                        log.log(Level.FINE, "listener failed", t)
                    }
                }
            }
        }
    }

    private fun shared(name: String, period: Double): LiveSub = synchronized(subs) {
        subs.firstOrNull { it.shared && it.scanClass == name }?.let { return it }
        val created = LiveSub(UUID.randomUUID().toString(), name, emptyList(), period, null, shared = true)
        subs += created
        created
    }

    private fun nodeFor(sym: SymbolRec, id: String): SourceNode {
        if (sym.boolArray && sym.dim0 > 0 && sym.dim1 == 0) {
            return SourceNode(id, sym.name, "Object", readable = false, writable = false)
        }
        val template = if (sym.structure) model.templates[sym.typeId] else null
        val folder = template != null && !template.string
        return SourceNode(id, sym.name, if (folder) "Object" else "Variable", readable = !folder, writable = !folder)
    }

    /**
     * the reference product lists a BOOL array as DWORD words: `flags[0].0` through `flags[0].31`, then `flags[1].0`.
     * doc source: FEATURES DEV-010
     */
    private fun boolBitNodes(id: String, bits: Int): List<SourceNode> {
        val out = ArrayList<SourceNode>(bits)
        for (i in 0 until bits) {
            val word = i ushr 5
            val bit = i and 31
            out += SourceNode("$id[$word].$bit", "[$word].$bit", "Variable", readable = true, writable = true)
        }
        return out
    }

    /**
     * Automatic Rebrowse reloads symbols and templates when the controller catalog changes.
     * The the reference product setting detects new tags and UDT changes while the session stays up
     * (doc source: FEATURES DEV-010).
     * 1756-PM020 does not name the attribute a controller uses for that, so the simulator publishes
     * a generation as Symbol class attribute 4 and this driver reloads when it changes.
     */
    private fun catalogGeneration(): Int {
        val path = Cip.classInstance(Cip.SYMBOL, 0) + byteArrayOf(0x30, 0x04)
        val reply = pool.request(Cip.message(Cip.GET_ATTR, path, ByteArray(0)))
        if (reply.status != 0 || reply.data.size < 4) return -1
        return i32(reply.data, 0)
    }

    /**
     * A legacy or Micro800 profile has no catalog change signal to watch: the tag tree is read
     * when the connection comes up and kept until the next connection. doc source: FEATURES DEV-021, DEV-023
     */
    private fun replaceModel() {
        if (!config.profile.watchesCatalog) {
            model = LogixModel.load(pool.session(), config.profile)
            return
        }
        var guard = 0
        while (guard++ < 4) {
            val before = catalogGeneration()
            val loaded = LogixModel.load(pool.session(), config.profile)
            val after = catalogGeneration()
            model = loaded
            seenGeneration = after
            if (before == after) return
        }
    }

    private fun lost(address: String, now: Instant, diagnostic: String?): QualifiedValue =
        sample(address, retained(address), Quality.of(lossCode(address), diagnostic), now)

    private fun retained(address: String): Any? = lastGood[address]

    private fun lossCode(address: String): QualityCode {
        if (!everConnected && !lastGood.containsKey(address)) return QualityCode.Bad_WaitingForInitialData
        return config.commsLossQuality
    }

    private fun qualityFor(e: Exception): QualityCode = when (e) {
        is SocketTimeoutException -> if (everConnected) config.commsLossQuality else QualityCode.Bad_Timeout
        is NotConnectedException -> lossCode("")
        is OutOfRangeException -> QualityCode.Bad_OutOfRange
        is TypeMismatchException -> QualityCode.Bad_TypeMismatch
        is AddressException -> QualityCode.Bad_ConfigurationError
        is IOException -> if (pool.connected) QualityCode.Bad else lossCode("")
        else -> QualityCode.Error
    }

    private fun waiting(address: String) =
        QualifiedValue(address, null, Quality.of(QualityCode.Bad_WaitingForInitialData), Instant.now(), null)

    private fun sample(address: String, value: Any?, quality: Quality, timestamp: Instant): QualifiedValue {
        val qv = QualifiedValue(address, value, quality, timestamp, null)
        cache[address] = qv
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
        val withCounts = next.copy(requestCount = pool.requests, failureCount = pool.errors)
        status = withCounts
        for (cb in statusListeners) {
            try { cb(withCounts) } catch (t: Throwable) { log.log(Level.FINE, "status listener failed", t) }
        }
    }

    private class Planned(
        val address: String,
        val path: ByteArray,
        val elements: Int,
        val bit: Int,
        val alone: Boolean,
        val request: ByteArray,
    )

    private inner class LiveSub(
        override val id: String,
        val scanClass: String,
        initial: Collection<String>,
        override val samplingIntervalMs: Double,
        val listener: ((QualifiedValue) -> Unit)?,
        val shared: Boolean = false,
    ) : TagSubscription {
        val addresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val raw = ConcurrentHashMap<String, CopyOnWriteArrayList<RawSample>>()
        private val open = AtomicBoolean(true)
        override val publishingIntervalMs: Double get() = samplingIntervalMs
        init { addresses.addAll(initial) }
        override fun close() {
            if (!open.compareAndSet(true, false)) return
            subs.remove(this)
        }
    }

    private companion object {
        const val CONTROLLER = "Controller:Global"
    }
}

internal class EipPool(private val config: LogixDeviceConfig) {
    private val sessions = Array(config.maxConcurrentRequests.coerceIn(1, 8)) { EipSession(config) }
    private val turn = AtomicInteger()

    val connected: Boolean get() = sessions.any { it.isConnected() }
    val requests: Long get() = sessions.sumOf { it.requests.get() }
    val errors: Long get() = sessions.sumOf { it.errors.get() }
    val timeouts: Long get() = sessions.sumOf { it.timeouts.get() }

    fun connect() {
        sessions[0].connect()
        for (i in 1 until sessions.size) {
            try {
                sessions[i].connect()
            } catch (_: Exception) {
                sessions[i].close()
            }
        }
    }

    fun session(): EipSession = sessions.firstOrNull { it.isConnected() } ?: sessions[0]

    fun budget(): Int = session().cipBudget

    fun product(): String = sessions.firstOrNull { it.productName.isNotEmpty() }?.productName ?: ""

    fun averageMillis(): Double? = sessions.firstOrNull { it.isConnected() }?.averageMillis()

    fun request(cip: ByteArray): Reply {
        val start = turn.getAndIncrement()
        var last: IOException? = null
        for (n in sessions.indices) {
            val session = sessions[(start + n).mod(sessions.size)]
            if (!session.isConnected()) continue
            try {
                return session.request(cip)
            } catch (e: IOException) {
                last = e
                session.close()
            }
        }
        throw last ?: NotConnectedException()
    }

    fun close() {
        sessions.forEach { it.close() }
    }

    fun shutdown() {
        sessions.forEach { it.shutdown() }
    }
}

/** Exponential backoff capped at [maxMs], then scaled by `1 + unit` where [unit] is in [-0.2, 0.2]. */
internal fun reconnectDelayMs(initialMs: Long, maxMs: Long, multiplier: Double, attempt: Int, unit: Double): Long {
    var delay = initialMs.toDouble()
    repeat(attempt.coerceAtLeast(0)) { delay *= multiplier }
    val base = delay.coerceIn(initialMs.toDouble(), maxMs.toDouble())
    val factor = 1.0 + unit.coerceIn(-0.2, 0.2)
    return (base * factor).toLong().coerceIn(1L, maxMs)
}

private fun i64Of(data: ByteArray, offset: Int, width: Int): Long {
    var v = 0L
    for (i in 0 until width) v = v or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
    return v
}

private fun typeLen(data: ByteArray): Int =
    if (data.size >= 2 && u16(data, 0) == Cip.STRUCT_MARK) 4 else 2

private fun cipText(reply: Reply): String =
    "CIP 0x${reply.status.toString(16)} ext 0x${reply.extendedStatus.toString(16)}"
