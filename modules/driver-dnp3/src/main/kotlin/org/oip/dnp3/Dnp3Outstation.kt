package org.oip.dnp3

import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.withLock

/** The outcome of a control: the status the outstation echoed, or why there was none. */
data class CommandResult(val status: Int, val iin: Iin, val diagnostic: String? = null) {
    val ok: Boolean get() = status == 0 && diagnostic == null
    val statusName: String get() = if (status < 0) "NO_RESPONSE" else CommandStatus.name(status)
}

/**
 * The gateway as DNP3 master of one outstation over TCP (DEV-013, DEV-014).
 *
 * Threads: `dnp3-master-<name>` connects, runs the startup sequence (disable unsolicited, integrity poll,
 * enable unsolicited), the periodic integrity and event polls, the restart and time-sync follow-ups, and
 * hands values to subscribers; `dnp3-read-<name>` reads link frames, answers link-layer requests,
 * reassembles transport segments, confirms and applies unsolicited responses and passes solicited
 * responses to the request in progress. Only one request is outstanding at a time: polls, commands and
 * scripts take turns. Values are the latest the outstation reported; after a loss they keep the last
 * value with Bad_NotConnected until the connection is made again after [OutstationConfig.reconnectMs].
 */
class Dnp3Outstation(val config: OutstationConfig) : TagSource, AutoCloseable {
    private val log = Logger.getLogger("org.oip.dnp3.${config.name}")
    private val lifecycle = Any()
    private val transaction = ReentrantLock()
    private val points = ConcurrentHashMap<String, Point>()
    private val subs = CopyOnWriteArrayList<LiveSub>()
    private val periods = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private val statusListeners = CopyOnWriteArrayList<(ConnectionStatus) -> Unit>()
    private val parsed = ConcurrentHashMap<String, Dnp3Address>()

    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var running = false
    @Volatile private var session: Session? = null
    @Volatile private var status = ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now())
    @Volatile private var nextAttempt = 0L
    @Volatile private var nextIntegrity = 0L
    @Volatile private var nextEvent = 0L
    @Volatile private var integrityNeeded = true
    @Volatile private var integrityDone = false
    @Volatile private var restartSeen = false
    @Volatile private var eventsPending = false
    @Volatile private var needTime = false
    private var appSeq = 0

    /** The internal indications of the last response. */
    @Volatile var lastIin: Iin = Iin(0)
        private set

    val requestCount = AtomicLong()
    val failureCount = AtomicLong()
    val timeoutCount = AtomicLong()
    val integrityPolls = AtomicLong()
    val eventPolls = AtomicLong()
    val unsolicitedCount = AtomicLong()
    val timeSyncs = AtomicLong()

    override val sourceId: String get() = config.name

    private class Point(val value: Any?, val flags: Int, val timeMs: Long?, val received: Instant)

    private inner class Session(val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        val responses = LinkedBlockingQueue<Fragment>()
        val receiver = Dnp3Transport.Receiver()
        var transportSeq = 0
        @Volatile var dead: String? = null
        @Volatile var lastUnsolicited = -1
        lateinit var reader: Thread

        fun send(fragment: ByteArray) = synchronized(this) {
            for (segment in Dnp3Transport.segment(fragment, transportSeq)) {
                out.write(Dnp3Link.encode(Dnp3Link.control(dir = true, primary = true, function = Dnp3Link.UNCONFIRMED_USER_DATA),
                    config.outstationAddress, config.masterAddress, segment))
                transportSeq = (transportSeq + 1) and 0x3F
            }
            out.flush()
        }

        fun sendLink(function: Int) = synchronized(this) {
            out.write(Dnp3Link.encode(Dnp3Link.control(dir = true, primary = false, function = function), config.outstationAddress, config.masterAddress))
            out.flush()
        }

        fun close() {
            dead = dead ?: "closed"
            runCatching { socket.close() }
            if (::reader.isInitialized && Thread.currentThread() !== reader) reader.join(2_000)
        }
    }

    private class Response(val iin: Iin, val objects: ParsedObjects)

    // ---- lifecycle ---------------------------------------------------------------------------

    fun start() = synchronized(lifecycle) {
        if (running) return
        running = true
        val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dnp3-master-${config.name}").apply { isDaemon = true } }
        scheduler = exec
        nextAttempt = 0L
        setStatus(ConnectionStatus(ConnectionState.CONNECTING, connected = false, since = Instant.now()))
        exec.scheduleWithFixedDelay({ supervise() }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        for (sub in subs) schedule(sub.period)
    }

    fun stop() = synchronized(lifecycle) {
        if (!running) return
        running = false
        periods.values.forEach { it.cancel(false) }
        periods.clear()
        session?.close()
        scheduler?.let { it.shutdownNow(); it.awaitTermination(5, TimeUnit.SECONDS) }
        scheduler = null
        session?.close()
        session = null
        setStatus(ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now(),
            requestCount = requestCount.get(), failureCount = failureCount.get()))
    }

    override fun close() = stop()

    override fun connectionStatus(): ConnectionStatus = status

    override fun subscribeStatus(listener: (ConnectionStatus) -> Unit): AutoCloseable {
        statusListeners += listener
        listener(status)
        return AutoCloseable { statusListeners -= listener }
    }

    private fun setStatus(next: ConnectionStatus) {
        status = next
        for (l in statusListeners) runCatching { l(next) }
    }

    // ---- connection and polling (master thread) -----------------------------------------------

    private fun supervise() {
        if (!running) return
        try {
            val s = session
            if (s == null) {
                if (System.nanoTime() >= nextAttempt) connect()
                return
            }
            s.dead?.let { lost(it); return }
            transaction.withLock { maintain() }
        } catch (e: Exception) {
            if (!running) return
            lost(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun connect() {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs.toInt())
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw IOException("connect to ${config.host}:${config.port} failed: ${e.message}", e)
        }
        val s = Session(socket)
        s.reader = Thread({ readLoop(s) }, "dnp3-read-${config.name}").apply { isDaemon = true }
        session = s
        s.reader.start()
        transaction.withLock {
            restartSeen = false
            // Startup: stop unsolicited reporting until the master holds a full picture, read everything, then enable it.
            request(Dnp3App.DISABLE_UNSOLICITED, Dnp3App.classRead(listOf(1, 2, 3)))
            if (restartSeen) clearRestart()
            integrity()
            if (config.unsolicited && config.eventClasses.isNotEmpty()) request(Dnp3App.ENABLE_UNSOLICITED, Dnp3App.classRead(config.eventClasses.sorted()))
        }
        setStatus(ConnectionStatus(ConnectionState.CONNECTED, connected = true, since = Instant.now(),
            reconnectAttempt = 0, requestCount = requestCount.get(), failureCount = failureCount.get()))
        nextEvent = System.nanoTime() + config.eventPeriodMs * 1_000_000L
    }

    /** Follow-ups the last responses asked for, then whichever poll is due. Runs under [transaction]. */
    private fun maintain() {
        val now = System.nanoTime()
        if (restartSeen) { clearRestart(); integrityNeeded = true }
        if (needTime && config.timeSync != TimeSync.NONE) synchronizeTime()
        val integrityDue = integrityNeeded || config.integrityPeriodMs > 0 && now >= nextIntegrity
        if (integrityDue) { integrity(); return }
        val eventDue = config.eventPeriodMs > 0 && now >= nextEvent
        if ((eventDue || eventsPending) && config.eventClasses.isNotEmpty()) eventPoll()
    }

    private fun integrity() {
        integrityNeeded = false
        request(Dnp3App.READ, Dnp3App.classRead(listOf(1, 2, 3, 0)))
        integrityPolls.incrementAndGet()
        integrityDone = true
        nextIntegrity = System.nanoTime() + config.integrityPeriodMs * 1_000_000L
    }

    private fun eventPoll() {
        eventsPending = false
        request(Dnp3App.READ, Dnp3App.classRead(config.eventClasses.sorted()))
        eventPolls.incrementAndGet()
        nextEvent = System.nanoTime() + config.eventPeriodMs * 1_000_000L
    }

    private fun clearRestart() {
        restartSeen = false
        request(Dnp3App.WRITE, Dnp3App.clearRestart())
    }

    /** Set the outstation's clock (LAN: record current time, then write it; non-LAN: measure the delay first). */
    private fun synchronizeTime() {
        needTime = false
        when (config.timeSync) {
            TimeSync.NONE -> return
            TimeSync.LAN -> {
                val sent = System.currentTimeMillis()
                request(Dnp3App.RECORD_CURRENT_TIME, ByteArray(0))
                request(Dnp3App.WRITE, Dnp3App.timeObject(3, sent))
            }
            TimeSync.NON_LAN -> {
                val t0 = System.nanoTime()
                val r = request(Dnp3App.DELAY_MEASURE, ByteArray(0))
                val roundTripMs = (System.nanoTime() - t0) / 1_000_000L
                val oneWay = ((roundTripMs - (r.objects.timeDelayMs ?: 0L)) / 2).coerceAtLeast(0)
                request(Dnp3App.WRITE, Dnp3App.timeObject(1, System.currentTimeMillis() + oneWay))
            }
        }
        timeSyncs.incrementAndGet()
    }

    private fun lost(message: String) {
        val s = session
        session = null
        s?.close()
        if (!running) return
        log.log(Level.FINE, "outstation ${config.name}: $message")
        integrityNeeded = true
        nextAttempt = System.nanoTime() + config.reconnectMs * 1_000_000L
        setStatus(ConnectionStatus(ConnectionState.RECONNECTING, connected = false, lastError = message, since = Instant.now(),
            reconnectAttempt = status.reconnectAttempt + 1, requestCount = requestCount.get(), failureCount = failureCount.get()))
    }

    // ---- requests ----------------------------------------------------------------------------

    /**
     * Send one request and gather its response fragments, confirming those that ask for it. Caller holds
     * [transaction]. A missing response closes the connection and throws [TimeoutException].
     */
    private fun request(function: Int, objects: ByteArray): Response {
        check(transaction.isHeldByCurrentThread)
        val s = session?.takeIf { it.dead == null } ?: throw NotConnectedException(status.lastError ?: "outstation ${config.name} is not connected")
        val seq = appSeq
        appSeq = (appSeq + 1) and 0x0F
        s.responses.clear()
        requestCount.incrementAndGet()
        try {
            s.send(Dnp3App.request(seq, function, objects))
        } catch (e: IOException) {
            failureCount.incrementAndGet()
            s.dead = "send failed: ${e.message}"
            throw NotConnectedException(s.dead!!)
        }
        val all = ParsedObjects()
        var iin = Iin(0)
        var expect = seq
        var first = true
        while (true) {
            val f = awaitFragment(s) ?: run {
                failureCount.incrementAndGet()
                timeoutCount.incrementAndGet()
                val msg = "no response to function $function within ${config.responseTimeoutMs} ms"
                s.dead = msg
                throw TimeoutException(msg)
            }
            if (f.seq != expect || (first && !f.fir)) continue
            first = false
            iin = f.iin
            val parsedObjects = try { Dnp3Objects.parse(f.objects) } catch (e: Dnp3DecodeException) {
                failureCount.incrementAndGet()
                log.log(Level.FINE, "outstation ${config.name}: bad response objects", e)
                ParsedObjects()
            }
            parsedObjects.unknown?.let { log.fine("outstation ${config.name}: stopped parsing at $it") }
            all.points += parsedObjects.points
            all.commands += parsedObjects.commands
            parsedObjects.timeDelayMs?.let { all.timeDelayMs = it }
            apply(parsedObjects)
            if (f.con) s.send(Dnp3App.confirm(f.seq, uns = false))
            if (f.fin) break
            expect = (f.seq + 1) and 0x0F
        }
        noteIin(iin)
        return Response(iin, all)
    }

    private fun awaitFragment(s: Session): Fragment? {
        val end = System.nanoTime() + config.responseTimeoutMs * 1_000_000L
        while (true) {
            s.dead?.let { throw NotConnectedException(it) }
            val left = end - System.nanoTime()
            if (left <= 0) return null
            s.responses.poll(minOf(left, 50_000_000L), TimeUnit.NANOSECONDS)?.let { return it }
        }
    }

    private fun noteIin(iin: Iin) {
        lastIin = iin
        if (iin.has(Dnp3App.IIN_DEVICE_RESTART)) restartSeen = true
        if (iin.has(Dnp3App.IIN_NEED_TIME)) needTime = true
        val classBits = config.eventClasses.any { c -> iin.has(1 shl c) }
        if (classBits) eventsPending = true
    }

    private fun apply(objects: ParsedObjects) {
        if (objects.points.isEmpty()) return
        val now = Instant.now()
        for (u in objects.points) {
            val key = "${u.type.prefix}:${u.index}"
            points[key] = Point(u.value, u.flags, u.timeMs, now)
        }
    }

    // ---- read thread -------------------------------------------------------------------------

    private fun readLoop(s: Session) {
        try {
            val input = BufferedInputStream(s.socket.getInputStream())
            while (s.dead == null) {
                val frame = try { Dnp3Link.read(input) } catch (e: Dnp3DecodeException) {
                    failureCount.incrementAndGet()
                    log.log(Level.FINE, "outstation ${config.name}: ${e.message}")
                    continue
                }
                if (frame.destination != config.masterAddress || frame.source != config.outstationAddress) continue
                if (!frame.primary) continue
                when (frame.function) {
                    Dnp3Link.REQUEST_LINK_STATUS -> { s.sendLink(Dnp3Link.LINK_STATUS); continue }
                    Dnp3Link.RESET_LINK_STATES, Dnp3Link.TEST_LINK_STATES -> { s.sendLink(Dnp3Link.ACK); continue }
                    Dnp3Link.CONFIRMED_USER_DATA -> s.sendLink(Dnp3Link.ACK)
                    Dnp3Link.UNCONFIRMED_USER_DATA -> Unit
                    else -> continue
                }
                val fragment = s.receiver.accept(frame.data) ?: continue
                val f = try { Fragment.parse(fragment) } catch (e: Dnp3DecodeException) { failureCount.incrementAndGet(); continue }
                when (f.function) {
                    Dnp3App.RESPONSE -> s.responses.offer(f)
                    Dnp3App.UNSOLICITED_RESPONSE -> unsolicited(s, f)
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            if (s.dead == null) s.dead = "connection lost: ${e.message}"
        } catch (e: RuntimeException) {
            if (s.dead == null) s.dead = "receive failed: ${e.message}"
            log.log(Level.FINE, "outstation ${config.name}: receive failed", e)
        }
    }

    /** Confirm, then apply unless it repeats the last one (a retry after a lost confirm). */
    private fun unsolicited(s: Session, f: Fragment) {
        if (f.con) runCatching { s.send(Dnp3App.confirm(f.seq, uns = true)) }
        if (f.seq == s.lastUnsolicited) return
        s.lastUnsolicited = f.seq
        unsolicitedCount.incrementAndGet()
        val objects = try { Dnp3Objects.parse(f.objects) } catch (e: Dnp3DecodeException) { failureCount.incrementAndGet(); return }
        apply(objects)
        noteIin(f.iin)
    }

    // ---- commands (DEV-013) ------------------------------------------------------------------

    /** A control relay output block on binary output [index]. */
    fun operateBinary(index: Int, crob: Crob, mode: CommandMode = config.commandMode): CommandResult {
        require(index in 0..65535) { "index $index is outside 0..65535" }
        require(crob.count in 0..255) { "count ${crob.count} is outside 0..255" }
        require(crob.onTimeMs in 0..0xFFFFFFFFL && crob.offTimeMs in 0..0xFFFFFFFFL) { "pulse times must fit 32 bits" }
        return command(Dnp3App.crob(index, crob), mode)
    }

    /** An analog output block on analog output [index]; [variation] 0 picks 32-bit for whole numbers, else float. */
    fun operateAnalog(index: Int, value: Number, variation: Int = config.analogOutputVariation, mode: CommandMode = config.commandMode): CommandResult {
        require(index in 0..65535) { "index $index is outside 0..65535" }
        val v = if (variation != 0) variation else {
            val d = value.toDouble()
            if (d == Math.floor(d) && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) 1 else 3
        }
        val d = value.toDouble()
        when (v) {
            1 -> require(d == Math.floor(d) && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) { "a 32-bit analog output takes a whole number" }
            2 -> require(d == Math.floor(d) && d in Short.MIN_VALUE.toDouble()..Short.MAX_VALUE.toDouble()) { "a 16-bit analog output takes a whole number from -32768 to 32767" }
            3, 4 -> Unit
            else -> throw IllegalArgumentException("analog output variation $v is not 1..4")
        }
        return command(Dnp3App.analogOutput(index, v, value), mode)
    }

    private fun command(objects: ByteArray, mode: CommandMode): CommandResult = transaction.withLock {
        val first = if (mode == CommandMode.SELECT_BEFORE_OPERATE) Dnp3App.SELECT else Dnp3App.DIRECT_OPERATE
        val selected = echo(request(first, objects))
        if (mode == CommandMode.DIRECT_OPERATE || !selected.ok) return@withLock selected.also { if (it.ok) eventsPending = true }
        // Operate must follow its select directly, with the same objects and the next sequence number.
        echo(request(Dnp3App.OPERATE, objects)).also { if (it.ok) eventsPending = true }
    }

    private fun echo(r: Response): CommandResult {
        val c = r.objects.commands.firstOrNull()
        val refused = listOf(
            Dnp3App.IIN_NO_FUNC_SUPPORT to "function not supported", Dnp3App.IIN_OBJECT_UNKNOWN to "object unknown",
            Dnp3App.IIN_PARAMETER_ERROR to "parameter error", Dnp3App.IIN_ALREADY_EXECUTING to "already executing",
        ).firstOrNull { r.iin.has(it.first) }?.second
        if (c == null) return CommandResult(-1, r.iin, refused ?: "the outstation did not echo the command")
        return CommandResult(c.status and 0x7F, r.iin, refused?.takeIf { c.status == 0 })
    }

    /** Run an integrity poll now, on the caller's thread. */
    fun integrityPoll() = transaction.withLock { integrity() }

    /** Run an event poll of the configured classes now, on the caller's thread. */
    fun eventPollNow() = transaction.withLock { eventPoll() }

    // ---- addresses, reads, browse -------------------------------------------------------------

    private fun address(id: String): Dnp3Address = parsed.computeIfAbsent(id) { Dnp3Address.parse(it) }

    private fun key(a: Dnp3Address) = "${a.type!!.prefix}:${a.index}"

    private fun valueOf(id: String, now: Instant): QualifiedValue {
        val a = try { address(id) } catch (e: AddressException) { return QualifiedValue(id, null, Quality.of(QualityCode.Bad_NotFound, e.message), now) }
        val connected = status.connected
        val lostQuality = Quality.of(QualityCode.Bad_NotConnected, status.lastError ?: "outstation ${config.name} is not connected")
        if (a.isIin) return QualifiedValue(id, lastIin.value.toLong(), if (connected) Quality.of(QualityCode.Good) else lostQuality, now)
        val p = points[key(a)]
            ?: return QualifiedValue(id, null, when {
                !connected -> lostQuality
                integrityDone -> Quality.of(QualityCode.Bad_NotFound, "the outstation has not reported ${key(a)}")
                else -> Quality.of(QualityCode.Bad_WaitingForInitialData)
            }, now)
        val source = p.timeMs?.let { Instant.ofEpochMilli(it) } ?: p.received
        val value: Any? = when (a.field) {
            "Flags" -> p.flags.toLong()
            "Online" -> p.flags and Dnp3Objects.FLAG_ONLINE != 0
            "Timestamp" -> p.timeMs
            else -> p.value
        }
        val q = when {
            !connected -> lostQuality
            a.field != Dnp3Address.VALUE -> Quality.of(QualityCode.Good)
            p.flags and Dnp3Objects.FLAG_COMM_LOST != 0 -> Quality.of(QualityCode.Bad_NoCommunication, "the outstation lost communication with this point")
            p.flags and Dnp3Objects.FLAG_ONLINE == 0 -> Quality.of(QualityCode.Bad, "point offline (flags 0x%02X)".format(p.flags))
            p.flags and (Dnp3Objects.FLAG_REMOTE_FORCED or Dnp3Objects.FLAG_LOCAL_FORCED) != 0 -> Quality.of(QualityCode.Good_Overridden)
            else -> Quality.of(QualityCode.Good)
        }
        return QualifiedValue(id, value, q, now, source)
    }

    override fun read(nodeIds: List<String>): List<QualifiedValue> {
        val now = Instant.now()
        return nodeIds.map { valueOf(it, now) }
    }

    override fun browse(nodeId: String): List<SourceNode> {
        val known = points.keys.mapNotNull { k -> runCatching { Dnp3Address.parse(k) }.getOrNull() }
        if (nodeId.isEmpty()) {
            val folders = PointType.entries.filter { t -> known.any { it.type == t } }.map { t ->
                SourceNode(t.prefix, t.display, "Object", readable = false, writable = false)
            }
            return folders + SourceNode(Dnp3Address.IIN, "IIN", "Variable", readable = true, writable = false)
        }
        val type = PointType.byPrefix(nodeId) ?: return emptyList()
        val writable = type == PointType.BINARY_OUTPUT || type == PointType.ANALOG_OUTPUT
        return known.filter { it.type == type }.map { it.index }.sorted().flatMap { i ->
            listOf(SourceNode("${type.prefix}:$i", "$i", "Variable", readable = true, writable = writable)) +
                Dnp3Address.FIELDS.drop(1).map { f -> SourceNode("${type.prefix}:$i/$f", "$i/$f", "Variable", readable = true, writable = false) }
        }
    }

    /** The node [nodeId] names, including a point browse does not list yet. Null when it is not an address. */
    fun describe(nodeId: String): SourceNode? {
        PointType.byPrefix(nodeId)?.let { return SourceNode(it.prefix, it.display, "Object", readable = false, writable = false) }
        val a = try { address(nodeId) } catch (_: AddressException) { return null }
        if (a.isIin) return SourceNode(nodeId, "IIN", "Variable", readable = true, writable = false)
        val writable = a.field == Dnp3Address.VALUE && (a.type == PointType.BINARY_OUTPUT || a.type == PointType.ANALOG_OUTPUT)
        return SourceNode(nodeId, nodeId, "Variable", readable = true, writable = writable)
    }

    // ---- writes ------------------------------------------------------------------------------

    /**
     * `bo:<n>` sends a control relay output block and `ao:<n>` an analog output block (see [Dnp3Address]).
     * The write is Good when the outstation echoes SUCCESS.
     */
    override fun write(nodeId: String, value: Any?): WriteResult {
        val now = Instant.now()
        fun done(code: QualityCode, diagnostic: String? = null) = WriteResult(nodeId, Quality.of(code, diagnostic), now)
        val a = try { address(nodeId) } catch (e: AddressException) { return done(QualityCode.Bad_NotFound, e.message) }
        if (a.isIin || a.field != Dnp3Address.VALUE || (a.type != PointType.BINARY_OUTPUT && a.type != PointType.ANALOG_OUTPUT)) {
            return done(QualityCode.Bad_NotWritable, "only bo and ao values take commands")
        }
        val result = try {
            val mode = a.options["mode"]?.let { CommandMode.parse(it) } ?: config.commandMode
            if (a.type == PointType.BINARY_OUTPUT) operateBinary(a.index, crobFor(a, value), mode)
            else {
                val n = number(value) ?: return done(QualityCode.Bad_TypeMismatch, "an analog output takes a number")
                val variation = a.options["variation"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..4 } ?: return done(QualityCode.Bad_ConfigurationError, "variation must be 1..4") }
                operateAnalog(a.index, n, variation ?: config.analogOutputVariation, mode)
            }
        } catch (e: IllegalArgumentException) {
            return done(QualityCode.Bad_TypeMismatch, e.message)
        } catch (e: TimeoutException) {
            return done(QualityCode.Bad_Timeout, e.message)
        } catch (e: NotConnectedException) {
            return done(QualityCode.Bad_NotConnected, e.message)
        } catch (e: IOException) {
            return done(QualityCode.Bad_NotConnected, e.message)
        }
        return if (result.ok) done(QualityCode.Good) else done(QualityCode.Bad_DeviceFailure, result.diagnostic ?: "command status ${result.statusName}")
    }

    private fun crobFor(a: Dnp3Address, value: Any?): Crob {
        val onMs = a.options["onms"]?.toLongOrNull() ?: 0L
        val offMs = a.options["offms"]?.toLongOrNull() ?: 0L
        if (value is String && value.toBooleanStrictOrNull() == null) return Crob(Crob.code(value), 1, onMs, offMs)
        val on = when (value) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.toBooleanStrict()
            else -> throw IllegalArgumentException("a binary output takes true, false or an operation name")
        }
        val code = when (a.options["op"]?.lowercase()) {
            null, "latch" -> if (on) Crob.LATCH_ON else Crob.LATCH_OFF
            "pulse" -> if (on) Crob.PULSE_ON else Crob.PULSE_OFF
            "tripclose" -> (if (on) Crob.CLOSE else Crob.TRIP) or Crob.PULSE_ON
            else -> throw IllegalArgumentException("op must be latch, pulse or tripclose")
        }
        return Crob(code, 1, onMs, offMs)
    }

    private fun number(v: Any?): Number? = when (v) {
        is Number -> v
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    // ---- subscriptions -----------------------------------------------------------------------

    override fun subscribe(
        nodeIds: List<String>,
        samplingIntervalMs: Double,
        publishingIntervalMs: Double,
        listener: (QualifiedValue) -> Unit,
    ): TagSubscription {
        val sub = LiveSub(samplingIntervalMs.toLong().coerceAtLeast(10), nodeIds, listener, null)
        subs += sub
        if (running) schedule(sub.period)
        return sub
    }

    /** Raw samples of [address] at [periodMs]: the current value now, then each change of value or quality. */
    fun attachRaw(periodMs: Long, address: String, listener: RawSample): AutoCloseable {
        val sub = LiveSub(periodMs.coerceAtLeast(10), listOf(address), null, listener)
        subs += sub
        sub.emit(force = true)
        if (running) schedule(sub.period)
        return sub
    }

    fun activeScanPeriodMs(address: String): Long? = subs.filter { address in it.addresses }.minOfOrNull { it.period }

    private fun schedule(period: Long) {
        val exec = scheduler ?: return
        periods.computeIfAbsent(period) {
            exec.scheduleWithFixedDelay({ for (s in subs) if (s.period == period) s.emit(force = false) }, 0, period, TimeUnit.MILLISECONDS)
        }
    }

    private inner class LiveSub(
        val period: Long,
        val addresses: List<String>,
        private val listener: ((QualifiedValue) -> Unit)?,
        private val raw: RawSample?,
    ) : TagSubscription {
        override val id: String = UUID.randomUUID().toString()
        override val samplingIntervalMs: Double get() = period.toDouble()
        override val publishingIntervalMs: Double get() = period.toDouble()
        private val last = HashMap<String, Pair<Any?, QualityCode>>()

        @Synchronized fun emit(force: Boolean) {
            val now = Instant.now()
            for (id in addresses) {
                val v = valueOf(id, now)
                val sig = v.value to v.quality.code
                if (!force && last[id] == sig) continue
                last[id] = sig
                try {
                    listener?.invoke(v)
                    raw?.onSample(v.value, v.quality, (v.sourceTimestamp ?: v.timestamp).toEpochMilli())
                } catch (e: RuntimeException) {
                    log.log(Level.FINE, "subscriber of ${config.name} failed", e)
                }
            }
        }

        override fun close() {
            subs -= this
            if (subs.none { it.period == period }) periods.remove(period)?.cancel(false)
        }
    }

    private companion object {
        const val TICK_MS = 20L
    }
}
