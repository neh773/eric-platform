package org.oip.udptcp

import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.Charset
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Generic UDP/TCP receiver for simple devices (barcode scanners, scales).
 *
 * All socket work is non-blocking on one selector thread that this device owns
 * (`udptcp-io-<name>`); nothing blocks a shared thread. Receiving a message only swaps an
 * immutable snapshot and bumps counters. Subscribers are called from a separate scan thread at
 * their sampling interval, so a slow subscriber cannot stall reception: messages keep being
 * counted, and the tag shows the latest one, as the public page describes for messages that
 * arrive faster than the scan. doc source: FEATURES DEV-037
 *
 * Per port folder `<port>` the device exposes `Message`, `MessageBytes`, `Field1`..`FieldN`,
 * `MessageCount`, `LastReceiveTime`, `DroppedCount` (messages over [ListenerDeviceConfig.maxMessageBytes])
 * and `RefusedCount` (TCP clients over [ListenerDeviceConfig.maxClients]). Field tags carry
 * Bad_ConfigurationError when a message does not split into the configured field count.
 *
 * [close] closes every channel and stops both threads before it returns.
 */
class ListenerDevice(val config: ListenerDeviceConfig) : TagSource, AutoCloseable {
    private val log = Logger.getLogger("org.oip.udptcp.${config.name}")
    private val charset: Charset = Charset.forName(config.charset)
    private val separator = config.fieldSeparator()
    private val delimiter = if (config.delimiterType == DelimiterType.CHARACTER) config.delimiterBytes() else ByteArray(0)
    private val ports = config.ports.associateWith { PortState(it) }
    private val subs = CopyOnWriteArrayList<LiveSub>()
    private val periods = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private val statusListeners = CopyOnWriteArrayList<(ConnectionStatus) -> Unit>()
    private val lifecycle = Any()
    private val running = AtomicBoolean(false)
    @Volatile private var io: Thread? = null
    @Volatile private var selector: Selector? = null
    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var status = ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now())
    private val openChannels = ConcurrentHashMap.newKeySet<SelectableChannel>()

    override val sourceId: String get() = config.name

    /** Channels open now (listening sockets, accepted and dialled connections, UDP sockets). */
    val openChannelCount: Int get() = openChannels.size

    /** Most bytes any framer held for one message; never above [bufferCapacity]. */
    val bufferHighWater: Int get() = ports.values.maxOf { it.highWater.get() }

    /** Fixed size of each framer buffer: the message limit plus the delimiter. */
    val bufferCapacity: Int get() = ports.values.maxOf { it.capacity }

    fun start() = synchronized(lifecycle) {
        if (running.get()) return
        running.set(true)
        val sel = Selector.open()
        selector = sel
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "udptcp-scan-${config.name}").apply { isDaemon = true }
        }
        io = Thread({ loop(sel) }, "udptcp-io-${config.name}").apply { isDaemon = true; start() }
        for (sub in subs) schedule(sub.period)
    }

    fun stop() = synchronized(lifecycle) {
        if (!running.getAndSet(false)) return
        selector?.wakeup()
        io?.join(5_000)
        io = null
        selector = null
        closeAll()
        periods.values.forEach { it.cancel(false) }
        periods.clear()
        scheduler?.let {
            it.shutdownNow()
            it.awaitTermination(5, TimeUnit.SECONDS)
        }
        scheduler = null
        setStatus(ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now()))
    }

    override fun close() = stop()

    override fun connectionStatus(): ConnectionStatus = status

    override fun subscribeStatus(listener: (ConnectionStatus) -> Unit): AutoCloseable {
        statusListeners += listener
        listener(status)
        return AutoCloseable { statusListeners -= listener }
    }

    override fun browse(nodeId: String): List<SourceNode> {
        if (nodeId.isEmpty()) return config.ports.map { SourceNode(it.toString(), it.toString(), "Object", readable = false, writable = false) }
        val port = nodeId.toIntOrNull() ?: return emptyList()
        if (port !in ports) return emptyList()
        return itemNames().map { SourceNode("$port/$it", it, "Variable", readable = true, writable = false) }
    }

    fun describe(nodeId: String): SourceNode? {
        if (nodeId.isEmpty()) return null
        val port = nodeId.toIntOrNull()
        if (port != null) return if (port in ports) SourceNode(nodeId, nodeId, "Object", readable = false, writable = false) else null
        val (p, item) = split(nodeId) ?: return null
        if (p !in ports || item !in itemNames()) return null
        return SourceNode(nodeId, item, "Variable", readable = true, writable = false)
    }

    private fun itemNames(): List<String> =
        listOf("Message", "MessageBytes") + (1..config.fieldCount).map { "Field$it" } +
            listOf("MessageCount", "LastReceiveTime", "DroppedCount", "RefusedCount")

    override fun read(nodeIds: List<String>): List<QualifiedValue> {
        val now = Instant.now()
        return nodeIds.map { value(it, now) }
    }

    /** No writeback: the device only receives. */
    override fun write(nodeId: String, value: Any?): WriteResult =
        WriteResult(nodeId, Quality.of(QualityCode.Bad_NotWritable, "the UDP/TCP driver receives only"), Instant.now())

    override fun subscribe(
        nodeIds: List<String>,
        samplingIntervalMs: Double,
        publishingIntervalMs: Double,
        listener: (QualifiedValue) -> Unit,
    ): TagSubscription {
        val period = samplingIntervalMs.toLong().coerceAtLeast(10)
        val sub = LiveSub(period, nodeIds, listener, null)
        subs += sub
        if (running.get()) schedule(period)
        return sub
    }

    fun attachRaw(periodMs: Long, address: String, listener: RawSample): AutoCloseable {
        val period = periodMs.coerceAtLeast(10)
        val sub = LiveSub(period, listOf(address), null, listener)
        subs += sub
        if (running.get()) schedule(period)
        val first = value(address, Instant.now())
        runCatching { listener.onSample(first.value, first.quality, first.timestamp.toEpochMilli()) }
        return sub
    }

    fun activeScanPeriodMs(address: String): Long? = subs.filter { address in it.addresses }.minOfOrNull { it.period }

    // ---- values -------------------------------------------------------------------------------

    private fun split(nodeId: String): Pair<Int, String>? {
        val slash = nodeId.indexOf('/')
        if (slash <= 0) return null
        val port = nodeId.substring(0, slash).toIntOrNull() ?: return null
        return port to nodeId.substring(slash + 1)
    }

    private fun value(nodeId: String, now: Instant): QualifiedValue {
        val (port, item) = split(nodeId) ?: return bad(nodeId, QualityCode.Bad_NotFound, now)
        val state = ports[port] ?: return bad(nodeId, QualityCode.Bad_NotFound, now)
        val snap = state.last.get()
        val received = snap?.at
        return when (item) {
            "MessageCount" -> good(nodeId, state.count.get(), now)
            "DroppedCount" -> good(nodeId, state.dropped.get(), now)
            "RefusedCount" -> good(nodeId, state.refused.get(), now)
            "LastReceiveTime" -> if (received == null) waiting(nodeId, now) else good(nodeId, received, now, received)
            "Message" -> if (snap == null) waiting(nodeId, now) else QualifiedValue(nodeId, snap.text, quality(state), now, received)
            "MessageBytes" -> if (snap == null) waiting(nodeId, now) else QualifiedValue(nodeId, snap.bytes.copyOf(), quality(state), now, received)
            else -> {
                val n = if (item.startsWith("Field")) item.removePrefix("Field").toIntOrNull() else null
                if (n == null || n !in 1..config.fieldCount) return bad(nodeId, QualityCode.Bad_NotFound, now)
                if (snap == null) return waiting(nodeId, now)
                val fields = snap.fields
                    ?: return QualifiedValue(
                        nodeId, null,
                        Quality.of(QualityCode.Bad_ConfigurationError, "message has ${snap.fieldsSeen} fields, expected ${config.fieldCount}"),
                        now, received,
                    )
                QualifiedValue(nodeId, fields[n - 1], quality(state), now, received)
            }
        }
    }

    /** A dialled TCP port that is down keeps its last message with Bad_NotConnected. */
    private fun quality(state: PortState): Quality =
        if (config.mode == Mode.CONNECT && !state.up.get()) Quality.of(QualityCode.Bad_NotConnected) else Quality.of(QualityCode.Good)

    private fun good(nodeId: String, value: Any?, now: Instant, source: Instant? = null) =
        QualifiedValue(nodeId, value, Quality.of(QualityCode.Good), now, source)

    private fun waiting(nodeId: String, now: Instant) =
        QualifiedValue(nodeId, null, Quality.of(QualityCode.Bad_WaitingForInitialData), now, null)

    private fun bad(nodeId: String, code: QualityCode, now: Instant) =
        QualifiedValue(nodeId, null, Quality.of(code, "unknown item '$nodeId'"), now, null)

    /** Runs on the I/O thread: build the snapshot and publish it with one reference swap. */
    private fun received(state: PortState, bytes: ByteArray) {
        val text = String(bytes, charset)
        var fields: List<String>? = null
        var seen = 0
        if (config.fieldCount > 0) {
            val parts = text.split(separator)
            seen = parts.size
            if (parts.size == config.fieldCount) fields = parts
        }
        state.last.set(Snapshot(text, bytes, fields, seen, Instant.now()))
        state.count.incrementAndGet()
    }

    // ---- publishing ---------------------------------------------------------------------------

    private fun schedule(period: Long) {
        val exec = scheduler ?: return
        periods.computeIfAbsent(period) {
            exec.scheduleAtFixedRate({ publish(period) }, period, period, TimeUnit.MILLISECONDS)
        }
    }

    private fun publish(period: Long) {
        val now = Instant.now()
        for (sub in subs) {
            if (sub.period != period) continue
            for (address in sub.addresses) {
                val sample = value(address, now)
                try {
                    sub.listener?.invoke(sample)
                    sub.raw?.onSample(sample.value, sample.quality, (sample.sourceTimestamp ?: now).toEpochMilli())
                } catch (t: Throwable) {
                    log.log(Level.FINE, "listener failed", t)
                }
            }
        }
    }

    // ---- I/O thread ---------------------------------------------------------------------------

    private fun loop(sel: Selector) {
        val buffer = ByteBuffer.allocate(config.maxMessageBytes + 1)
        val retryAt = HashMap<Int, Long>()
        for (port in config.ports) retryAt[port] = 0L
        try {
            while (running.get()) {
                val now = System.nanoTime()
                for ((port, at) in retryAt.entries.toList()) {
                    if (at != 0L && now < at) continue
                    retryAt.remove(port)
                    try {
                        open(sel, port)
                    } catch (e: IOException) {
                        log.log(Level.FINE, "port $port: ${e.message}", e)
                        retryAt[port] = now + config.reconnectMs * 1_000_000L
                        noteError("port $port: ${e.message}")
                    }
                }
                refreshStatus()
                sel.select(nextWait(retryAt))
                if (!running.get()) break
                val keys = sel.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    val att = key.attachment()
                    try {
                        when (att) {
                            is Acceptor -> accept(sel, key, att)
                            is TcpConn -> tcp(key, att, buffer, retryAt)
                            is UdpPort -> udp(key, att, buffer)
                        }
                    } catch (e: IOException) {
                        log.log(Level.FINE, "socket error", e)
                        if (att is TcpConn) drop(key, att, retryAt)
                    }
                }
                timeouts(sel, retryAt)
            }
        } catch (_: ClosedSelectorException) {
            // Closed by stop().
        } catch (t: Throwable) {
            log.log(Level.WARNING, "udp/tcp device ${config.name} stopped", t)
        } finally {
            closeAll()
            runCatching { sel.close() }
        }
    }

    private fun nextWait(retryAt: Map<Int, Long>): Long {
        var wait = 200L
        val now = System.nanoTime()
        for (at in retryAt.values) wait = minOf(wait, ((at - now) / 1_000_000L).coerceAtLeast(1))
        return wait.coerceAtLeast(1)
    }

    private fun open(sel: Selector, port: Int) {
        val state = ports.getValue(port)
        when {
            config.protocol == Protocol.UDP -> {
                val channel = DatagramChannel.open()
                track(channel)
                try {
                    channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                    channel.bind(InetSocketAddress(config.address, port))
                    channel.configureBlocking(false)
                    channel.register(sel, SelectionKey.OP_READ, UdpPort(state, framer(state)))
                } catch (e: IOException) {
                    untrack(channel)
                    throw e
                }
                state.up.set(true)
            }
            config.mode == Mode.LISTEN -> {
                val server = ServerSocketChannel.open()
                track(server)
                try {
                    server.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                    server.bind(InetSocketAddress(config.address, port), config.maxClients)
                    server.configureBlocking(false)
                    server.register(sel, SelectionKey.OP_ACCEPT, Acceptor(state))
                } catch (e: IOException) {
                    untrack(server)
                    throw e
                }
                state.up.set(true)
            }
            else -> {
                val channel = SocketChannel.open()
                track(channel)
                try {
                    channel.configureBlocking(false)
                    config.localAddress?.let { channel.bind(InetSocketAddress(it, 0)) }
                    channel.setOption(StandardSocketOptions.TCP_NODELAY, true)
                    val conn = TcpConn(state, framer(state), System.nanoTime())
                    if (channel.connect(InetSocketAddress(config.address, port))) {
                        conn.connected = true
                        state.up.set(true)
                        channel.register(sel, SelectionKey.OP_READ, conn)
                    } else {
                        channel.register(sel, SelectionKey.OP_CONNECT, conn)
                    }
                } catch (e: IOException) {
                    untrack(channel)
                    throw e
                }
            }
        }
    }

    private fun framer(state: PortState) = Framer(
        config.delimiterType, delimiter, config.fixedLength, config.maxMessageBytes,
        onMessage = { received(state, it) },
        onDropped = { state.dropped.incrementAndGet() },
    ).also { state.capacity = it.capacity }

    private fun accept(sel: Selector, key: SelectionKey, acceptor: Acceptor) {
        val server = key.channel() as ServerSocketChannel
        while (true) {
            val client = server.accept() ?: return
            if (acceptor.state.clients.get() >= config.maxClients) {
                acceptor.state.refused.incrementAndGet()
                runCatching { client.close() }
                continue
            }
            track(client)
            client.configureBlocking(false)
            client.setOption(StandardSocketOptions.TCP_NODELAY, true)
            acceptor.state.clients.incrementAndGet()
            client.register(sel, SelectionKey.OP_READ, TcpConn(acceptor.state, framer(acceptor.state), System.nanoTime(), accepted = true).also { it.connected = true })
        }
    }

    private fun tcp(key: SelectionKey, conn: TcpConn, buffer: ByteBuffer, retryAt: MutableMap<Int, Long>) {
        val channel = key.channel() as SocketChannel
        if (key.isConnectable) {
            channel.finishConnect()
            conn.connected = true
            conn.lastData = System.nanoTime()
            conn.state.up.set(true)
            key.interestOps(SelectionKey.OP_READ)
            return
        }
        if (!key.isReadable) return
        buffer.clear()
        val n = channel.read(buffer)
        if (n < 0) {
            drop(key, conn, retryAt)
            return
        }
        if (n == 0) return
        conn.lastData = System.nanoTime()
        conn.framer.feed(buffer.array(), 0, n)
        conn.state.highWater.accumulateAndGet(conn.framer.highWater) { a, b -> maxOf(a, b) }
    }

    private fun udp(key: SelectionKey, port: UdpPort, buffer: ByteBuffer) {
        val channel = key.channel() as DatagramChannel
        while (true) {
            buffer.clear()
            channel.receive(buffer) ?: return
            // The buffer is one byte larger than a message may be, so a full buffer is an oversize datagram.
            val n = buffer.position()
            if (n > config.maxMessageBytes) {
                port.state.dropped.incrementAndGet()
                continue
            }
            port.framer.feed(buffer.array(), 0, n)
            port.state.highWater.accumulateAndGet(port.framer.highWater) { a, b -> maxOf(a, b) }
        }
    }

    private fun drop(key: SelectionKey, conn: TcpConn, retryAt: MutableMap<Int, Long>) {
        val channel = key.channel()
        key.cancel()
        untrack(channel)
        if (conn.accepted) {
            conn.state.clients.decrementAndGet()
            return
        }
        conn.state.up.set(false)
        if (running.get()) retryAt[conn.state.port] = System.nanoTime() + config.reconnectMs * 1_000_000L
    }

    private fun timeouts(sel: Selector, retryAt: MutableMap<Int, Long>) {
        val now = System.nanoTime()
        for (key in sel.keys().toList()) {
            val conn = key.attachment() as? TcpConn ?: continue
            if (!key.isValid) continue
            val idle = (now - conn.lastData) / 1_000_000L
            val limit = if (!conn.connected) config.connectTimeoutMs else config.inactivityTimeoutMs
            if (limit > 0 && idle >= limit) {
                if (conn.connected) noteError("port ${conn.state.port}: no data for ${config.inactivityTimeoutMs} ms")
                else noteError("port ${conn.state.port}: connect timed out")
                drop(key, conn, retryAt)
            }
        }
    }

    private fun track(channel: SelectableChannel) {
        openChannels += channel
    }

    private fun untrack(channel: SelectableChannel) {
        openChannels -= channel
        runCatching { channel.close() }
    }

    private fun closeAll() {
        for (channel in openChannels.toList()) untrack(channel)
        for (state in ports.values) {
            state.up.set(false)
            state.clients.set(0)
        }
    }

    @Volatile private var lastError: String? = null

    private fun noteError(message: String) {
        lastError = message
    }

    private fun refreshStatus() {
        val up = ports.values.count { it.up.get() }
        val state = when {
            up == ports.size -> ConnectionState.CONNECTED
            status.state == ConnectionState.DISCONNECTED -> ConnectionState.CONNECTING
            else -> ConnectionState.RECONNECTING
        }
        val connected = up == ports.size
        if (state != status.state || connected != status.connected) {
            setStatus(ConnectionStatus(state, connected, if (connected) null else lastError, since = Instant.now()))
        }
    }

    private fun setStatus(next: ConnectionStatus) {
        status = next
        for (cb in statusListeners) {
            try { cb(next) } catch (t: Throwable) { log.log(Level.FINE, "status listener failed", t) }
        }
    }

    private class Snapshot(val text: String, val bytes: ByteArray, val fields: List<String>?, val fieldsSeen: Int, val at: Instant)

    private class PortState(val port: Int) {
        val last = AtomicReference<Snapshot?>(null)
        val count = AtomicLong()
        val dropped = AtomicLong()
        val refused = AtomicLong()
        val clients = AtomicInteger()
        val up = AtomicBoolean(false)
        val highWater = AtomicInteger()
        @Volatile var capacity = 0
    }

    private class Acceptor(val state: PortState)

    private class TcpConn(
        val state: PortState,
        val framer: Framer,
        @Volatile var lastData: Long,
        val accepted: Boolean = false,
    ) {
        @Volatile var connected = false
    }

    private class UdpPort(val state: PortState, val framer: Framer)

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
        }
    }
}
