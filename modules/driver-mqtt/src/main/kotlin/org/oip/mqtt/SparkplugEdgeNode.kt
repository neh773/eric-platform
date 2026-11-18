package org.oip.mqtt

import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Gateway tags as seen by an edge node. The gateway supplies it (the module does not depend on the
 * tag engine). [subscribe] listeners may run on a tag-engine writer thread, possibly while that thread
 * holds the engine lock, so the edge node only stores the value there (P2-14). [write] is called on the
 * edge node's write thread, never on the MQTT socket thread.
 */
interface EdgeTagFeed {
    fun read(path: String): EdgeTagValue
    fun subscribe(path: String, listener: (EdgeTagValue) -> Unit): AutoCloseable
    fun write(path: String, value: Any?): Boolean

    /**
     * A command write from edge node [edge]. The gateway's feed writes as a fixed user named after the edge node,
     * so tag write permissions apply and the write is audited (review-2 F14). Defaults to [write].
     */
    fun write(path: String, value: Any?, edge: String): Boolean = write(path, value)
}

/** [dataType] is the tag's data type name when known (`Int32`, `Double`, ...). */
class EdgeTagValue(val value: Any?, val good: Boolean, val epochMillis: Long, val dataType: String? = null) {
    companion object {
        val MISSING = EdgeTagValue(null, false, 0L)
    }
}

/**
 * Runs one Sparkplug B edge node: MQTT session with the NDEATH will, births, report-by-exception data,
 * commands, and primary-host STATE. Protocol rules are in [SparkplugEdgeSession].
 * https://sparkplug.eclipse.org/specification/version/3.0/documents/sparkplug-specification-3.0.0.pdf
 *
 * Threads: `sparkplug-edge-<name>` owns the socket and every publish, so `seq` is assigned in one place.
 * A tag listener stores the latest value in that metric's slot (one atomic set, no IO, no lock), so a
 * burst between two flushes is coalesced to its newest value and memory is bounded by the metric count.
 * NCMD and DCMD writes run on `sparkplug-edge-write-<name>`; the resulting tag change comes back through
 * the listener and is published as NDATA or DDATA.
 *
 * Redundancy: [setActive] only stores a flag. While inactive the IO thread publishes NDEATH, disconnects,
 * and does not connect again; when active again it opens a new session (next bdSeq) and births.
 */
class SparkplugEdgeNode(
    val config: SparkplugEdgeConfig,
    private val feed: () -> EdgeTagFeed?,
    private val secrets: () -> SecretSource? = { null },
    private val bdSeqCounter: AtomicLong = AtomicLong(0),
) : AutoCloseable {
    private val log = Logger.getLogger("org.oip.mqtt")
    private val session = SparkplugEdgeSession(config, bdSeqCounter.get())
    private val metrics = session.metrics()
    private val pending = AtomicReferenceArray<EdgeTagValue?>(metrics.size)
    private val discardedWrites = AtomicLong()
    private val writes = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(WRITE_QUEUE),
        { r -> Thread(r, "sparkplug-edge-write-${config.name}").apply { isDaemon = true } },
    ) { _, executor ->
        if (executor.isShutdown) return@ThreadPoolExecutor
        val n = discardedWrites.incrementAndGet()
        if (n and (n - 1) == 0L) log.warning("sparkplug edge ${config.name} write queue is full; $n command writes discarded so far")
    }
    private val subscriptions = CopyOnWriteArrayList<AutoCloseable>()
    private val births = AtomicInteger()
    private val connects = AtomicInteger()
    private val rejected = AtomicInteger()
    @Volatile private var running = false
    /** False on a backup (inactive) redundant node: no session, nothing published. */
    @Volatile private var active = true
    @Volatile private var sessionUp = false
    /** False between sessions: births read fresh values, so nothing needs to be kept then. */
    @Volatile private var accepting = false
    @Volatile private var thread: Thread? = null
    @Volatile private var current: MqttClient? = null

    /** True while the MQTT session is up and NBIRTH has been published. */
    fun online(): Boolean = sessionUp && session.birthed

    fun connected(): Boolean = sessionUp

    fun birthCount(): Int = births.get()

    fun connectCount(): Int = connects.get()

    /** NCMD and DCMD metrics that were not written (see [EdgeAction.Rejected]). */
    fun rejectedCommands(): Int = rejected.get()

    fun discardedWrites(): Long = discardedWrites.get()

    fun active(): Boolean = active

    /**
     * Follows the redundancy state. Called from the redundancy listener thread, so it only stores the
     * flag (P2-14); the IO thread ends or opens the session.
     */
    fun setActive(value: Boolean) {
        active = value
    }

    fun start() {
        if (running) return
        running = true
        val worker = Thread(this::loop, "sparkplug-edge-${config.name}")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    /** Signals the IO thread to end the session (NDEATH, then DISCONNECT). Returns at once. */
    fun requestStop() {
        running = false
    }

    /**
     * Waits for the IO thread. After a grace period the socket (or a connect in progress) is closed and
     * the thread interrupted, so a stopped node cannot connect later with the same client id.
     */
    fun awaitStop() {
        val worker = thread ?: return
        worker.join(STOP_GRACE_MS)
        if (worker.isAlive) {
            current?.drop()
            worker.interrupt()
            worker.join(config.connectTimeoutMs + STOP_GRACE_MS)
        }
        if (worker.isAlive) log.warning("sparkplug edge ${config.name} did not stop in time")
        thread = null
    }

    fun stop() {
        requestStop()
        awaitStop()
    }

    override fun close() {
        stop()
        writes.shutdownNow()
        unsubscribe()
    }

    private fun loop() {
        var attempt = 0
        while (running) {
            val tags = feed()
            if (tags == null) {
                pause(100)
                continue
            }
            if (!active) {
                attempt = 0
                pause(POLL_MS.toLong())
                continue
            }
            subscribe(tags)
            var client: MqttClient? = null
            try {
                client = open()
                attempt = 0
                pump(client, tags)
                if (client.open) cleanClose(client)
            } catch (e: Exception) {
                if (running) log.fine("sparkplug edge ${config.name} session ended (${e.javaClass.simpleName})")
            } finally {
                if (client != null && client.open) client.drop()
                current = null
                sessionUp = false
                accepting = false
                session.disconnected()
            }
            if (running && active) pause(backoff(attempt++))
        }
        unsubscribe()
    }

    private fun open(): MqttClient {
        val will = session.beginConnect(System.currentTimeMillis())
        bdSeqCounter.set((session.bdSeq + 1) and 0xFF)
        val client = MqttClient(
            host = config.host,
            port = config.port,
            clientId = config.clientId,
            protocol = config.protocol,
            keepAliveSeconds = config.keepAliveSeconds,
            // Sparkplug B 3.0: an edge node connects with a clean session (MQTT 5: Clean Start, expiry 0).
            cleanSession = true,
            username = resolve(config.usernameRef),
            password = resolve(config.passwordRef)?.encodeToByteArray(),
            will = will,
            tls = MqttTls(
                enabled = config.tls,
                trustAll = config.insecureTrustAllCertificates,
                trustStore = config.tlsTrustStore?.let { Path.of(it) },
                trustStorePassword = resolve(config.tlsTrustStorePasswordRef)?.toCharArray(),
            ),
            connectTimeoutMs = config.connectTimeoutMs,
        )
        current = client
        client.connect()
        if (!running || !active) {
            // Stopped or made inactive while connecting: leave without the will so no NDEATH is announced for this session.
            client.disconnect()
            throw IllegalStateException("stopped")
        }
        session.subscriptions().forEach { client.subscribe(it, qos = 1) }
        sessionUp = true
        connects.incrementAndGet()
        return client
    }

    private fun pump(client: MqttClient, tags: EdgeTagFeed) {
        var lastPing = System.currentTimeMillis()
        var lastInbound = lastPing
        var lastFlush = 0L
        val pingEvery = if (config.keepAliveSeconds > 0) config.keepAliveSeconds * 500L else Long.MAX_VALUE
        while (running && active && client.open) {
            if (session.readyToBirth()) birth(client, tags)
            val polled = client.poll(POLL_MS)
            val now = System.currentTimeMillis()
            if (polled != null) lastInbound = now
            if (polled is MqttPoll.Published) {
                for (action in session.onMessage(polled.message.topic, polled.message.payload)) {
                    if (!act(client, tags, action)) return
                }
            }
            if (now - lastFlush >= config.publishPeriodMs) {
                flush(client, tags, now)
                lastFlush = now
            }
            if (now - lastPing >= pingEvery) {
                client.ping()
                lastPing = now
            }
            if (config.keepAliveSeconds > 0 && now - lastInbound > config.keepAliveSeconds * 1_500L) {
                throw IllegalStateException("keepalive")
            }
        }
    }

    /** @return false when the session must end. */
    private fun act(client: MqttClient, tags: EdgeTagFeed, action: EdgeAction): Boolean {
        when (action) {
            EdgeAction.Birth -> birth(client, tags)
            EdgeAction.Rebirth -> if (session.birthed) {
                log.info("sparkplug edge ${config.name} rebirth requested")
                birth(client, tags)
            }
            EdgeAction.HostOffline -> {
                log.info("sparkplug edge ${config.name} primary host is offline; ending the session")
                cleanClose(client)
                return false
            }
            is EdgeAction.Rejected -> {
                rejected.incrementAndGet()
                log.info("sparkplug edge ${config.name} command for ${action.target} ignored: ${action.reason}")
            }
            is EdgeAction.Write -> write(tags, action)
        }
        return true
    }

    /**
     * Commands carry no identity of their own; broker ACLs on the command topics are the access control. The feed
     * writes as the edge node ([EdgeTagFeed.write] with the edge name), so the gateway applies the tag's write
     * permissions to that fixed identity and audits the write; each write is also logged here.
     */
    private fun write(tags: EdgeTagFeed, action: EdgeAction.Write) {
        val path = action.metric.config.tag
        try {
            writes.execute {
                val ok = try {
                    tags.write(path, action.value, config.name)
                } catch (e: RuntimeException) {
                    log.log(Level.FINE, "sparkplug edge ${config.name} write ${action.metric}", e)
                    false
                }
                log.info("sparkplug edge ${config.name} command write to ${action.metric} (tag $path) ${if (ok) "accepted" else "rejected by the tag"}")
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /**
     * Births read every tag fresh. Pending values are cleared first because the births already carry
     * them; a change after that is kept and compared with the birth value.
     */
    private fun birth(client: MqttClient, tags: EdgeTagFeed) {
        accepting = true
        drain()
        val now = System.currentTimeMillis()
        for (publish in session.births(now) { metric -> read(tags, metric) }) {
            client.publish(publish.topic, publish.payload, publish.qos, publish.retain)
        }
        births.incrementAndGet()
    }

    private fun flush(client: MqttClient, tags: EdgeTagFeed, now: Long) {
        if (!session.birthed) return
        val changes = drain()
        if (changes.isEmpty()) return
        for (publish in session.data(now, changes)) client.publish(publish.topic, publish.payload, publish.qos, publish.retain)
        if (session.rebirthNeeded) birth(client, tags)
    }

    private fun drain(): List<Pair<EdgeMetric, EdgeTagValue>> {
        val out = ArrayList<Pair<EdgeMetric, EdgeTagValue>>()
        for (i in metrics.indices) {
            val value = pending.getAndSet(i, null) ?: continue
            out += metrics[i] to value
        }
        return out
    }

    private fun read(tags: EdgeTagFeed, metric: EdgeMetric): EdgeTagValue = try {
        tags.read(metric.config.tag)
    } catch (e: RuntimeException) {
        log.log(Level.FINE, "sparkplug edge ${config.name} read ${metric.config.tag}", e)
        EdgeTagValue.MISSING
    }

    private fun subscribe(tags: EdgeTagFeed) {
        if (subscriptions.isNotEmpty()) return
        metrics.forEachIndexed { index, metric ->
            try {
                subscriptions += tags.subscribe(metric.config.tag) { value -> offer(index, value) }
            } catch (e: RuntimeException) {
                log.log(Level.FINE, "sparkplug edge ${config.name} cannot watch ${metric.config.tag}", e)
            }
        }
    }

    /** Called on tag-engine threads: one atomic store, no IO, no lock. A newer value replaces an unsent one. */
    private fun offer(index: Int, value: EdgeTagValue) {
        if (accepting) pending.set(index, value)
    }

    private fun unsubscribe() {
        val handles = subscriptions.toList()
        subscriptions.removeAll(handles.toSet())
        handles.forEach { runCatching { it.close() } }
    }

    private fun cleanClose(client: MqttClient) {
        // A DISCONNECT suppresses the will, so publish NDEATH first (Sparkplug B 3.0, edge node termination).
        // This runs on the IO thread, the client's only reader, so it polls for the PUBACK itself (bounded):
        // the broker has taken the NDEATH before DISCONNECT. On a timeout it disconnects anyway.
        runCatching { client.publishPollingAck(session.topic("NDEATH"), session.deathPayload(System.currentTimeMillis()), timeoutMs = NDEATH_ACK_MS) }
        client.disconnect()
    }

    private fun resolve(reference: String?): String? {
        if (reference.isNullOrBlank()) return null
        val name = secretName(reference) ?: throw IllegalArgumentException("sparkplug edge ${config.name} secret reference rejected")
        val source = secrets() ?: throw IllegalStateException("sparkplug edge ${config.name} secret $name is not available")
        return try {
            source.resolve(reference)
        } catch (_: Exception) {
            log.warning("sparkplug edge ${config.name} secret $name is not available")
            throw IllegalStateException("sparkplug edge ${config.name} secret $name is not available")
        }
    }

    private fun backoff(attempt: Int): Long {
        var delay = config.reconnectInitialMs
        repeat(attempt.coerceAtMost(16)) { delay = (delay * 2).coerceAtMost(config.reconnectMaxMs) }
        return delay.coerceAtMost(config.reconnectMaxMs)
    }

    private fun pause(ms: Long) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        val wasActive = active
        while (running && active == wasActive && System.nanoTime() < end) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private companion object {
        const val POLL_MS = 10
        const val WRITE_QUEUE = 256
        const val STOP_GRACE_MS = 2_000L
        /** NDEATH ack wait plus [MqttClient.DRAIN_MS] stays well inside [STOP_GRACE_MS]. */
        const val NDEATH_ACK_MS = 500L
    }
}
