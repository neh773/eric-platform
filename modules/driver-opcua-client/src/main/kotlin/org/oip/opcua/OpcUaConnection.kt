package org.oip.opcua

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClient as MiloClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider
import org.eclipse.milo.opcua.sdk.client.identity.X509IdentityProvider
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription
import org.eclipse.milo.opcua.stack.core.NodeIds
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.UaException
import org.eclipse.milo.opcua.stack.core.security.CertificateValidator
import org.eclipse.milo.opcua.stack.core.security.DefaultClientCertificateValidator
import org.eclipse.milo.opcua.stack.core.security.FileBasedCertificateQuarantine
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager
import org.eclipse.milo.opcua.stack.core.types.UaRequestMessageType
import org.eclipse.milo.opcua.stack.core.types.UaResponseMessageType
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UNumber
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ulong
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort
import org.eclipse.milo.opcua.sdk.client.nodes.UaMethodNode
import org.eclipse.milo.opcua.stack.core.types.structured.CallMethodRequest
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription
import org.eclipse.milo.opcua.stack.core.types.structured.TransferSubscriptionsRequest
import org.eclipse.milo.opcua.stack.core.types.structured.TransferSubscriptionsResponse
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * An OPC UA client session. Implements [TagSource] so the tag engine can adapt it without this
 * module depending on gateway/tags.
 *
 * Subscriptions use the requested sampling and publishing intervals. Eclipse Milo transfers them
 * when a new session is created (OPC UA TransferSubscriptions, Part 4 §5.14.7). A channel drop
 * while the same session is still alive is a reactivation, not a transfer. A successful transfer
 * is counted from the Good TransferSubscriptions result (the same result Milo records in
 * PublishingManager.notifySubscriptionTransferred) and from
 * [onStatusChanged](org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription.SubscriptionListener)
 * when the status is Good_SubscriptionTransferred. It is not inferred by comparing subscription
 * ids. When the server refuses the transfer,
 * [onTransferFailed](org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription.SubscriptionListener)
 * runs and this class creates the subscription again on a private executor — not on the session
 * state-machine thread, which must not block in [OpcUaSubscription.create].
 *
 * Server certificates are checked with Milo's DefaultClientCertificateValidator against [OpcUaConnectionConfig.pkiDir].
 * Milo 1.1.8 has no DefaultTrustListManager; the file implementation is FileBasedTrustListManager
 * (`trusted/` and `issuer/`) plus a FileBasedCertificateQuarantine (`rejected/certs`).
 * [OpcUaConnectionConfig.trustAllServerCertificates] is the only way to skip that check.
 *
 * A session drop publishes [OpcUaConnectionConfig.commsLossQuality] and keeps the last value.
 *
 * Lost notifications (Part 4 §5.14.1.1, §6.7): when Milo cannot republish a missing
 * NotificationMessage it calls onNotificationDataLost, and when no PublishResponse arrives for
 * longer than the keep-alive deadline it calls onWatchdogTimerElapsed. Either way the absence of a
 * notification no longer means the value is unchanged. Every member of that subscription is read
 * again and delivered, and keep-alives stop vouching for the cached value until that read succeeds.
 *
 * Monitored items whose CreateMonitoredItems failed are retried until the subscription reports
 * them SYNCHRONIZED. An item the server refuses for a configuration reason (unknown node, bad
 * attribute) is not retried; its watcher gets that status as the tag quality.
 * Status codes: https://reference.opcfoundation.org/Core/Part6/v105/docs/A.2
 */
class OpcUaConnection(
    val config: OpcUaConnectionConfig,
    /** Turns a `secret://` password into its text at connect time. Plain text passes through. */
    private val secrets: (String) -> String = { it },
) : TagSource, AutoCloseable {
    private val log = Logger.getLogger("org.oip.opcua.${config.name}")
    private val cache = ConcurrentHashMap<String, QualifiedValue>()
    /** Last good sample per node. A comms-loss cache entry must not hide it from keep-alives. */
    private val lastGood = ConcurrentHashMap<String, QualifiedValue>()
    private val subs = CopyOnWriteArrayList<LiveSub>()
    private val watches = CopyOnWriteArrayList<PendingWatch>()
    private val groups = ConcurrentHashMap<Long, LiveSub>()
    private val inbox = ConcurrentLinkedQueue<PendingWatch>()
    private val watchLock = Any()
    private val flushArmed = AtomicBoolean(false)
    private val statusListeners = CopyOnWriteArrayList<(ConnectionStatus) -> Unit>()
    /** Service calls, errors, timeouts, notifications, and response time. Callbacks only add to these. */
    private val stats = ResponseStats()
    private val requests: AtomicLong get() = stats.requests
    private val failures: AtomicLong get() = stats.errors
    private val timeouts: AtomicLong get() = stats.timeouts
    private val transfers = AtomicInteger(0)
    private val transferFailures = AtomicInteger(0)
    private val recreates = AtomicInteger(0)
    private val countedTransferIds = ConcurrentHashMap.newKeySet<Long>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "opcua-${config.name}").apply { isDaemon = true }
    }
    /** Delays only; the work itself runs on [executor]. */
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "opcua-${config.name}-retry").apply { isDaemon = true }
    }
    private val resyncs = AtomicInteger(0)

    @Volatile private var milo: MiloClient? = null
    @Volatile private var trustList: FileBasedTrustListManager? = null
    @Volatile private var everActive = false
    @Volatile private var closed = false
    @Volatile private var status = ConnectionStatus(ConnectionState.DISCONNECTED, connected = false, since = Instant.now())
    @Volatile private var sessionIdText: String? = null
    @Volatile private var sessionNameText: String? = null
    @Volatile private var serverStateName: String? = null
    @Volatile private var serverStateAtNanos: Long = 0

    override val sourceId: String get() = config.name

    /** Subscriptions accepted by TransferSubscriptions (Good result), counted once per id. */
    val subscriptionTransfers: Int get() = transfers.get()

    /** Subscriptions the server refused to transfer. Each one is recreated. */
    val subscriptionTransferFailures: Int get() = transferFailures.get()

    /** Subscriptions created again after a failed transfer. */
    val subscriptionRecreates: Int get() = recreates.get()

    /**
     * Monitored items the server created: only items whose sync state is SYNCHRONIZED.
     * An item still waiting on CreateMonitoredItems, or one the server refused, is not counted
     * (DIFFERENCES 315).
     */
    val monitoredItemCount: Int get() = subs.sumOf { synchronizedCount(it) }

    /** Re-reads of a subscription's members after lost notifications or a watchdog timeout. */
    val notificationResyncs: Int get() = resyncs.get()

    /** CreateMonitoredItems batches sent for watched items that share a sampling interval. */
    val monitoredItemSyncs: Int get() = itemSyncs.get()
    private val itemSyncs = AtomicInteger(0)

    fun discover(): List<OpcUaEndpoint> = discover(config.endpointUrl, config.requestTimeoutMs)

    /**
     * Open the session. Throws the OPC UA failure (for example Bad_UserAccessDenied) when the
     * server rejects the session. A later socket drop is reported through [connectionStatus] and
     * subscribed values; Milo reconnects and transfers subscriptions.
     */
    @Synchronized
    fun connect() {
        check(!closed) { "connection is closed" }
        if (status.connected && milo != null) return
        val reconnecting = everActive
        setStatus(status.copy(
            state = if (reconnecting) ConnectionState.RECONNECTING else ConnectionState.CONNECTING,
            connected = false, since = Instant.now(),
        ))
        var client: MiloClient? = null
        try {
            client = buildClient()
            client.addSessionActivityListener(Activity())
            client.connect()
            milo = client
            everActive = true
            setStatus(status.copy(
                state = ConnectionState.CONNECTED, connected = true, lastError = null,
                reconnectAttempt = 0, since = Instant.now(),
            ))
            log.info("connected ${config.securityPolicy} ${config.endpointUrl}")
        } catch (e: Exception) {
            failures.incrementAndGet()
            val cause = unwrap(e)
            setStatus(status.copy(
                state = ConnectionState.DISCONNECTED, connected = false, lastError = cause.message,
                since = Instant.now(), failureCount = failures.get(),
            ))
            val live = milo ?: client
            milo = null
            if (live != null) runCatching { live.disconnect() }
            closePki()
            throw cause
        }
    }

    @Synchronized
    fun disconnect() {
        synchronized(watchLock) {
            subs.forEach { runCatching { it.opc.delete() } }
            subs.clear()
            groups.clear()
            for (watch in watches) {
                watch.item = null
                watch.group = null
                if (!watch.cancelled.get()) inbox.add(watch)
            }
        }
        val client = milo
        milo = null
        if (client != null) runCatching { client.disconnect() }
        closePki()
        setStatus(status.copy(state = ConnectionState.DISCONNECTED, connected = false, since = Instant.now()))
    }

    override fun close() {
        closed = true
        disconnect()
        timer.shutdownNow()
        executor.shutdownNow()
    }

    override fun connectionStatus(): ConnectionStatus = status

    /**
     * Connection diagnostic snapshot. Does not open a socket and does not read ServerStatus.
     * [refreshServerState] fills [OpcUaConnectionDiagnostics.serverState] for the status page.
     *
     * The snapshot lists server state, the session, and each subscription's rate and item count.
     * The item count is items the server created (DIFFERENCES 315).
     *
     * [OpcUaConnectionDiagnostics.requestCount] is service calls plus data notifications since
     * connect, so it moves when subscribed values change. Sampling and publish intervals are the
     * fastest open subscription (revised sampling, revised publishing).
     */
    fun diagnostics(): OpcUaConnectionDiagnostics {
        val groups = ArrayList<OpcUaSubscriptionDiagnostics>()
        var sampling: Double? = null
        var publishing: Double? = null
        var items = 0
        for (sub in subs) {
            if (!sub.open.get()) continue
            val count = synchronizedCount(sub)
            items += count
            groups += OpcUaSubscriptionDiagnostics(
                name = "sampling-${sub.requestedSamplingMs.toLong()}ms",
                samplingIntervalMs = sub.samplingIntervalMs,
                requestedPublishingIntervalMs = sub.requestedPublishingMs,
                revisedPublishingIntervalMs = sub.publishingIntervalMs,
                monitoredItemCount = count,
            )
            val current = sampling
            if (current == null || sub.samplingIntervalMs < current) {
                sampling = sub.samplingIntervalMs
                publishing = sub.publishingIntervalMs
            }
        }
        return OpcUaConnectionDiagnostics(
            connected = status.connected,
            state = status.state.name,
            requestCount = stats.trafficCount(),
            errorCount = stats.errorCount(),
            timeoutCount = stats.timeoutCount(),
            averageResponseTimeMs = stats.averageMillis(),
            samplingIntervalMs = sampling,
            publishIntervalMs = publishing,
            serverState = if (status.connected) serverStateName else null,
            sessionId = if (status.connected) sessionIdText else null,
            sessionName = if (status.connected) sessionNameText else null,
            subscriptionCount = groups.size,
            monitoredItemCount = items,
            subscriptions = groups,
            lastError = status.lastError,
            since = status.since,
        )
    }

    /**
     * Read ServerStatus.State at most once every two seconds. The status page calls this.
     * The subscription callback does not.
     * Node: Server_ServerStatus_State (i=2259). Part 5, ServerState.
     */
    fun refreshServerState() {
        val client = milo
        if (client == null || !status.connected) {
            serverStateName = null
            return
        }
        val now = System.nanoTime()
        if (serverStateName != null && now - serverStateAtNanos < 2_000_000_000L) return
        val started = System.nanoTime()
        try {
            requests.incrementAndGet()
            val value = client.readValues(
                0.0, TimestampsToReturn.Neither, listOf(NodeIds.Server_ServerStatus_State),
            ).single()
            stats.noteSample(System.nanoTime() - started)
            serverStateName = serverStateLabel(value.value?.value)
            serverStateAtNanos = now
        } catch (e: Exception) {
            failures.incrementAndGet()
            if (isTimeout(e)) timeouts.incrementAndGet()
        }
    }

    override fun subscribeStatus(listener: (ConnectionStatus) -> Unit): AutoCloseable {
        statusListeners += listener
        listener(status)
        return AutoCloseable { statusListeners -= listener }
    }

    override fun browse(nodeId: String): List<SourceNode> {
        val client = milo
        if (client == null || !status.connected) return emptyList()
        val id = if (nodeId.isBlank()) NodeIds.ObjectsFolder else NodeId.parse(nodeId)
        val started = System.nanoTime()
        requests.incrementAndGet()
        val refs = try {
            val found = client.addressSpace.browse(id)
            stats.noteSample(System.nanoTime() - started)
            found
        } catch (e: Exception) {
            failures.incrementAndGet()
            if (isTimeout(e)) timeouts.incrementAndGet()
            throw e
        }
        return refs.map { ref ->
            val target = ref.nodeId.toParseableString()
            val variable = ref.nodeClass == NodeClass.Variable
            SourceNode(
                nodeId = target,
                displayName = ref.displayName.text ?: ref.browseName.name ?: target,
                nodeClass = ref.nodeClass.name,
                readable = variable,
                writable = variable,
            )
        }
    }

    override fun read(nodeIds: List<String>): List<QualifiedValue> {
        val now = Instant.now()
        val client = milo
        if (client == null || !status.connected) {
            return nodeIds.map { lost(it, now, "not connected") }
        }
        val parsed = ArrayList<NodeId?>(nodeIds.size)
        val early = HashMap<Int, QualifiedValue>()
        nodeIds.forEachIndexed { i, raw ->
            try {
                parsed += NodeId.parse(raw)
            } catch (e: Exception) {
                parsed += null
                early[i] = sample(raw, null, Quality.of(QualityCode.Bad_ConfigurationError, e.message), now, null, true)
            }
        }
        val goodIndexes = parsed.indices.filter { parsed[it] != null && it !in early }
        val produced = HashMap<Int, QualifiedValue>(early)
        if (goodIndexes.isNotEmpty()) {
            val started = System.nanoTime()
            try {
                requests.incrementAndGet()
                val values = client.readValues(0.0, TimestampsToReturn.Both, goodIndexes.map { parsed[it]!! })
                stats.noteSample(System.nanoTime() - started)
                goodIndexes.forEachIndexed { n, i ->
                    produced[i] = fromData(nodeIds[i], values[n])
                }
            } catch (e: Exception) {
                failures.incrementAndGet()
                if (isTimeout(e)) timeouts.incrementAndGet()
                val stamp = Instant.now()
                for (i in goodIndexes) produced[i] = lost(nodeIds[i], stamp, e.message)
            }
        }
        return nodeIds.indices.map { produced.getValue(it) }
    }

    /**
     * Call [methodId] on [objectId] (SCR-021). Each input is converted to the data type the server declares
     * for that argument (a JSON number becomes an Int32 for an Int32 argument, and so on). A method the
     * server does not declare arguments for gets its inputs as given. Throws [IllegalStateException] when
     * not connected and [UaException] when the server refuses the call; a method that ran and answered a
     * bad status is returned with [MethodCallResult.good] false.
     */
    fun callMethod(objectId: String, methodId: String, inputs: List<Any?>): MethodCallResult {
        val client = milo
        if (client == null || !status.connected) throw IllegalStateException("not connected")
        val object_ = NodeId.parse(objectId)
        val method = NodeId.parse(methodId)
        val declared = try {
            (client.addressSpace.getNode(method) as? UaMethodNode)?.readInputArgumentsAsync()?.get(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }
        if (declared != null && declared.size != inputs.size) {
            throw IllegalArgumentException("method takes ${declared.size} inputs, got ${inputs.size}")
        }
        val variants = inputs.mapIndexed { i, value -> Variant(coerceArgument(value, declared?.get(i)?.dataType)) }.toTypedArray()
        val started = System.nanoTime()
        requests.incrementAndGet()
        val result = try {
            val response = client.call(listOf(CallMethodRequest(object_, method, variants)))
            stats.noteSample(System.nanoTime() - started)
            response.results.single()
        } catch (e: Exception) {
            failures.incrementAndGet()
            if (isTimeout(e)) timeouts.incrementAndGet()
            throw e
        }
        return MethodCallResult(
            statusCode = result.statusCode.value,
            good = result.statusCode.isGood,
            outputs = (result.outputArguments ?: emptyArray()).map { plain(it.value) },
            inputResults = (result.inputArgumentResults ?: emptyArray()).map { it.value },
        )
    }

    /** An output argument as a plain value a script can hold: unsigned types become integers, arrays lists, the rest text. */
    private fun plain(value: Any?): Any? = when {
        value == null || value is Boolean || value is String -> value
        value is UNumber -> value.toBigInteger()
        value is Number -> value
        value.javaClass.isArray -> (0 until java.lang.reflect.Array.getLength(value)).map { plain(java.lang.reflect.Array.get(value, it)) }
        else -> value.toString()
    }

    private fun coerceArgument(value: Any?, type: NodeId?): Any? {
        if (value == null) return null
        // No declared type (the server does not publish InputArguments): a whole number that fits is an Int32.
        if (type == null) return if (value is Long && value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
        val number = value as? Number
        return when (type) {
            NodeIds.Boolean -> value as? Boolean ?: throw IllegalArgumentException("expected a boolean")
            NodeIds.SByte -> number?.toByte()
            NodeIds.Byte -> number?.let { ubyte(it.toInt()) }
            NodeIds.Int16 -> number?.toShort()
            NodeIds.UInt16 -> number?.let { ushort(it.toInt()) }
            NodeIds.Int32 -> number?.toInt()
            NodeIds.UInt32 -> number?.let { uint(it.toLong()) }
            NodeIds.Int64 -> number?.toLong()
            NodeIds.UInt64 -> number?.let { ulong(it.toLong()) }
            NodeIds.Float -> number?.toFloat()
            NodeIds.Double -> number?.toDouble()
            NodeIds.String -> value.toString()
            else -> value
        } ?: throw IllegalArgumentException("expected a number")
    }

    override fun write(nodeId: String, value: Any?): WriteResult {
        val now = Instant.now()
        val client = milo
        if (client == null || !status.connected) {
            return WriteResult(nodeId, Quality.of(config.commsLossQuality, "not connected"), now)
        }
        val id = try {
            NodeId.parse(nodeId)
        } catch (e: Exception) {
            return WriteResult(nodeId, Quality.of(QualityCode.Bad_ConfigurationError, e.message), now)
        }
        val started = System.nanoTime()
        return try {
            requests.incrementAndGet()
            val statusCode = client.writeValues(listOf(id), listOf(DataValue(Variant(value)))).single()
            stats.noteSample(System.nanoTime() - started)
            val quality = Quality.fromOpcUa(statusCode.value)
            val stamp = Instant.now()
            if (quality.level == QualityLevel.GOOD) {
                sample(nodeId, value, quality, stamp, null, true)
            }
            WriteResult(nodeId, quality, stamp)
        } catch (e: Exception) {
            failures.incrementAndGet()
            if (isTimeout(e)) timeouts.incrementAndGet()
            WriteResult(nodeId, Quality.of(QualityCode.Error, e.message), Instant.now())
        }
    }

    override fun subscribe(
        nodeIds: List<String>,
        samplingIntervalMs: Double,
        publishingIntervalMs: Double,
        listener: (QualifiedValue) -> Unit,
    ): TagSubscription {
        val client = milo ?: throw IllegalStateException("not connected")
        val publishing = publishingIntervalMs.coerceAtLeast(10.0)
        val sampling = samplingIntervalMs.coerceAtLeast(10.0)
        val opc = OpcUaSubscription(client, publishing)
        val live = LiveSub(UUID.randomUUID().toString(), opc, nodeIds, sampling, publishing, listener)
        opc.setSubscriptionListener(object : OpcUaSubscription.SubscriptionListener {
            override fun onStatusChanged(subscription: OpcUaSubscription, status: StatusCode) {
                // Good_SubscriptionTransferred is published on the session that used to own the
                // subscription (Part 4 §5.14.7). Count it when this client actually receives it.
                if (status.value == StatusCodes.Good_SubscriptionTransferred) {
                    subscription.subscriptionId.map { it.toLong() }.ifPresent { noteTransferred(it) }
                } else if (status.value == StatusCodes.Bad_Timeout) {
                    noteSubscriptionTimeout()
                    log.fine("subscription timed out ${config.name}")
                    executor.execute { recreate(live) }
                }
            }

            override fun onTransferFailed(subscription: OpcUaSubscription, status: StatusCode) {
                transferFailures.incrementAndGet()
                log.fine("transfer failed ${config.name} status=${status.value.toString(16)}")
                executor.execute { recreate(live) }
            }

            override fun onNotificationDataLost(subscription: OpcUaSubscription) {
                scheduleResync(live, "notification data lost")
            }

            override fun onWatchdogTimerElapsed(subscription: OpcUaSubscription) {
                scheduleResync(live, "subscription watchdog elapsed")
            }
        })
        opc.create()
        for (raw in nodeIds) {
            val item = OpcUaMonitoredItem.newDataItem(NodeId.parse(raw))
            item.samplingInterval = sampling
            item.setDataValueListener { _, value -> deliver(raw, value, listener) }
            opc.addMonitoredItem(item)
            live.items += item
        }
        val syncStarted = System.nanoTime()
        try {
            requests.incrementAndGet()
            opc.synchronizeMonitoredItems()
            stats.noteSample(System.nanoTime() - syncStarted)
        } catch (e: Exception) {
            failures.incrementAndGet()
            if (isTimeout(e)) timeouts.incrementAndGet()
            log.log(Level.FINE, "monitored item sync failed", e)
            scheduleSyncRetry(live)
        }
        live.publishingIntervalMs = opc.revisedPublishingInterval.orElse(publishing)
        live.items.firstNotNullOfOrNull { it.revisedSamplingInterval.orElse(null) }?.let {
            live.samplingIntervalMs = it
        }
        subs += live
        val current = read(nodeIds)
        for (value in current) listener(value)
        return live
    }

    /**
     * Sampling interval already in use for [nodeId], or null when nothing is watching it.
     * A second watch joins that item instead of creating another one on the remote server.
     * The value is the remote item's revised interval once the server has reported it.
     */
    fun activeSamplingMs(nodeId: String): Double? {
        val watch = watches.firstOrNull { !it.cancelled.get() && it.nodeId == nodeId } ?: return null
        val revised = watch.item?.revisedSamplingInterval?.orElse(null)
        return if (revised != null && revised > 0.0) revised else watch.samplingMs
    }

    /**
     * Add one monitored item to the subscription for this sampling interval.
     * Items that arrive together are created in one CreateMonitoredItems call.
     * A second watch of a node that is already watched joins that item: one remote
     * monitored item, and every listener hears the same samples. Safe to call before
     * the session exists; the items are sent after connect.
     */
    fun watch(
        nodeId: String,
        samplingIntervalMs: Double,
        onAlive: (QualifiedValue) -> Unit = {},
        listener: (QualifiedValue) -> Unit,
    ): AutoCloseable {
        val sampling = samplingIntervalMs.coerceAtLeast(10.0)
        val slot = WatchSlot(listener, onAlive)
        val pending: PendingWatch
        val joined: Boolean
        synchronized(watchLock) {
            val existing = watches.firstOrNull { !it.cancelled.get() && it.nodeId == nodeId }
            if (existing != null) {
                existing.slots.add(slot)
                pending = existing
                joined = true
            } else {
                pending = PendingWatch(nodeId, sampling, slot)
                watches += pending
                inbox.add(pending)
                joined = false
            }
        }
        if (joined) {
            val initial = cache[nodeId] ?: sample(
                nodeId, null, Quality.of(QualityCode.Bad_WaitingForInitialData), Instant.now(), null, false,
            )
            try { listener(initial) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
        } else {
            scheduleFlush()
        }
        return AutoCloseable { detach(pending, slot) }
    }

    private fun detach(pending: PendingWatch, slot: WatchSlot) {
        // Same lock as watch(), so a new listener cannot join a watch that is being removed.
        val drop = synchronized(watchLock) {
            pending.slots.remove(slot)
            if (!pending.slots.isEmpty()) false
            else {
                pending.cancelled.set(true)
                true
            }
        }
        if (drop) {
            inbox.add(pending)
            scheduleFlush()
        }
    }

    private fun emit(pending: PendingWatch, sample: QualifiedValue) {
        for (slot in pending.slots) {
            try { slot.onSample(sample) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
        }
    }

    /** Create monitored items that were queued before the session was up. */
    fun flushWatches() {
        synchronized(watchLock) { flushLocked() }
        while (inbox.isNotEmpty() && milo != null && status.connected && !closed) {
            synchronized(watchLock) { flushLocked() }
        }
    }

    private fun scheduleFlush() {
        if (!flushArmed.compareAndSet(false, true)) return
        flushSeen.set(inbox.size)
        // The wait stays on the timer. The connection executor is one thread and also runs
        // lost-notification re-reads; sleeping here blocked that re-read until the batch wait ended.
        timer.schedule({ flushTick() }, 20, TimeUnit.MILLISECONDS)
    }

    /** Queue size observed at the previous debounce tick. */
    private val flushSeen = AtomicInteger(0)

    private fun flushTick() {
        if (closed) {
            flushArmed.set(false)
            return
        }
        val connected = milo != null && status.connected
        val queued = inbox.size
        val previous = flushSeen.getAndSet(queued)
        // Keep gathering while watches are still arriving. A full batch of 500 is sent at once.
        // A shorter queue is sent only after 20 ms with no new watch, so a bulk subscribe is not
        // cut into one sync per timer tick.
        if (connected && queued in 1..499 && queued != previous) {
            timer.schedule({ flushTick() }, 20, TimeUnit.MILLISECONDS)
            return
        }
        val full = connected && queued >= 500
        flushArmed.set(false)
        val submitted = runCatching {
            executor.execute {
                if (full) synchronized(watchLock) { flushLocked() } else flushWatches()
                if (inbox.isNotEmpty() && !closed && milo != null && status.connected) scheduleFlush()
            }
        }.isSuccess
        if (!submitted) flushArmed.set(false)
    }

    private fun flushLocked() {
        val batch = ArrayList<PendingWatch>(64)
        while (batch.size < 500) {
            batch += inbox.poll() ?: break
        }
        val client = milo
        if (client == null || !status.connected) {
            for (pending in batch) if (!pending.cancelled.get()) inbox.add(pending)
            return
        }
        val seen = HashSet<PendingWatch>()
        val fresh = ArrayList<PendingWatch>()
        for (pending in batch) {
            if (!seen.add(pending)) continue
            if (pending.cancelled.get()) {
                removeWatchItem(pending)
                watches.remove(pending)
                continue
            }
            if (pending.item == null) fresh += pending
        }
        if (fresh.isEmpty()) return
        val created = ArrayList<PendingWatch>()
        for ((period, items) in fresh.groupBy { it.samplingMs.toLong() }) {
            val group = try {
                ensureGroup(client, period)
            } catch (e: Exception) {
                log.log(Level.FINE, "subscription create failed", e)
                items.forEach { inbox.add(it) }
                continue
            }
            for (pending in items) {
                try {
                    val item = OpcUaMonitoredItem.newDataItem(NodeId.parse(pending.nodeId))
                    item.samplingInterval = pending.samplingMs
                    val address = pending.nodeId
                    item.setDataValueListener { _, value ->
                        // One atomic add on the notification callback. No allocation and no lock.
                        stats.noteNotification()
                        emit(pending, fromData(address, value))
                    }
                    group.opc.addMonitoredItem(item)
                    group.items += item
                    pending.item = item
                    pending.group = group
                    group.members += pending
                    created += pending
                } catch (e: Exception) {
                    emit(pending, sample(pending.nodeId, null, Quality.of(QualityCode.Bad_ConfigurationError, e.message), Instant.now(), null, false))
                }
            }
            val added = created.any { it.group === group }
            requests.incrementAndGet()
            val syncStarted = System.nanoTime()
            runCatching { group.opc.synchronizeMonitoredItems() }
                .onSuccess {
                    stats.noteSample(System.nanoTime() - syncStarted)
                    if (added) itemSyncs.incrementAndGet()
                }
                .onFailure { error ->
                    failures.incrementAndGet()
                    if (error is Exception && isTimeout(error)) timeouts.incrementAndGet()
                    // Items the server did not create stay on the subscription; a retry creates
                    // them. Their initial values come from the read below until then.
                    log.log(Level.FINE, "monitored item sync failed", error)
                    reportRefused(group)
                    scheduleSyncRetry(group)
                }
        }
        if (created.isNotEmpty()) {
            val values = runCatching { read(created.map { it.nodeId }) }.getOrNull()
            if (values != null) {
                for (i in created.indices) emit(created[i], values[i])
            }
        }
    }

    private fun ensureGroup(client: MiloClient, periodMs: Long): LiveSub {
        val existing = groups[periodMs]
        if (existing != null && existing.open.get()) return existing
        val sampling = periodMs.toDouble()
        val opc = OpcUaSubscription(client, sampling)
        // Milo's default keep-alive target is 10s, and it derives the lifetime as five
        // times that count. A static item would then go silent for longer than a tag's
        // scan period plus comms timeout. Ask for a keep-alive about once per publishing
        // interval, and keep the subscription lifetime long so a short stall does not
        // delete it before the session can recover.
        opc.setLifetimeAndKeepAliveCalculated(false)
        opc.setMaxKeepAliveCount(uint(1))
        opc.setLifetimeCount(uint(1_000))
        val live = LiveSub(UUID.randomUUID().toString(), opc, emptyList(), sampling, sampling) { }
        opc.setSubscriptionListener(object : OpcUaSubscription.SubscriptionListener {
            override fun onStatusChanged(subscription: OpcUaSubscription, status: StatusCode) {
                if (status.value == StatusCodes.Good_SubscriptionTransferred) {
                    subscription.subscriptionId.map { it.toLong() }.ifPresent { noteTransferred(it) }
                } else if (status.value == StatusCodes.Bad_Timeout) {
                    noteSubscriptionTimeout()
                    executor.execute { recreate(live) }
                }
            }

            override fun onTransferFailed(subscription: OpcUaSubscription, status: StatusCode) {
                transferFailures.incrementAndGet()
                executor.execute { recreate(live) }
            }

            override fun onNotificationDataLost(subscription: OpcUaSubscription) {
                scheduleResync(live, "notification data lost")
            }

            override fun onWatchdogTimerElapsed(subscription: OpcUaSubscription) {
                scheduleResync(live, "subscription watchdog elapsed")
            }

            override fun onKeepAliveReceived(subscription: OpcUaSubscription) {
                // A publish keep-alive means the session is still delivering. Refresh tag
                // liveness off this thread so a quiet value is not treated as comms loss.
                // Milo's own subscription watchdog is shorter than a tag's scan-plus-timeout
                // and is not used here; the tag engine owns that deadline.
                if (!live.aliveRefresh.compareAndSet(false, true)) return
                executor.execute {
                    try {
                        refreshAlive(live)
                    } finally {
                        live.aliveRefresh.set(false)
                    }
                }
            }
        })
        opc.create()
        live.publishingIntervalMs = opc.revisedPublishingInterval.orElse(live.publishingIntervalMs)
        subs += live
        groups[periodMs] = live
        return live
    }

    private fun removeWatchItem(pending: PendingWatch) {
        val item = pending.item ?: return
        val group = pending.group
        pending.item = null
        pending.group = null
        if (group != null) {
            group.members.remove(pending)
            group.items.remove(item)
            runCatching { group.opc.removeMonitoredItem(item) }
            runCatching { group.opc.synchronizeMonitoredItems() }
        }
    }

    private fun refreshAlive(group: LiveSub) {
        if (closed || !status.connected) return
        // After lost notifications the cached value is not known to be current. Only the
        // re-read in [resync] may vouch for it again.
        if (group.suspect.get()) return
        for (watch in group.members) {
            if (watch.cancelled.get()) continue
            val cached = cache[watch.nodeId] ?: continue
            val deliver = when {
                cached.quality.level == QualityLevel.GOOD -> cached
                // The subscription is alive again. The retained good sample is current until
                // the next data change; a comms-loss cache entry is not a device quality.
                isCommsLoss(cached.quality.code) -> lastGood[watch.nodeId]
                else -> null
            } ?: continue
            for (slot in watch.slots) {
                try { slot.onAlive(deliver) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
            }
        }
    }

    private fun deliver(address: String, value: DataValue, listener: (QualifiedValue) -> Unit) {
        // One atomic add. The callback does not allocate a diagnostic record or take a lock.
        stats.noteNotification()
        val sample = fromData(address, value)
        try {
            listener(sample)
        } catch (t: Throwable) {
            log.log(Level.FINE, "listener failed", t)
        }
    }

    private fun recreate(live: LiveSub) {
        if (!live.open.get() || closed) return
        if (!live.recreating.compareAndSet(false, true)) return
        try {
            var last: Exception? = null
            var attempt = 0
            while (live.open.get() && !closed && attempt < 40) {
                if (live.opc.subscriptionId.isPresent) return
                try {
                    live.opc.create()
                    try {
                        live.opc.synchronizeMonitoredItems()
                    } catch (e: Exception) {
                        // The subscription exists; the items that failed are retried until synchronized.
                        log.log(Level.FINE, "monitored item sync failed after recreate", e)
                        scheduleSyncRetry(live)
                    }
                    recreates.incrementAndGet()
                    live.publishingIntervalMs = live.opc.revisedPublishingInterval.orElse(live.publishingIntervalMs)
                    live.items.firstNotNullOfOrNull { it.revisedSamplingInterval.orElse(null) }?.let {
                        live.samplingIntervalMs = it
                    }
                    log.info("recreated subscription ${live.id}")
                    return
                } catch (e: Exception) {
                    last = e
                    if (live.opc.subscriptionId.isPresent) {
                        recreates.incrementAndGet()
                        scheduleSyncRetry(live)
                        return
                    }
                    attempt++
                    Thread.sleep(250)
                }
            }
            if (live.open.get() && !closed) log.log(Level.WARNING, "subscription recreate failed", last)
        } finally {
            live.recreating.set(false)
        }
    }

    /**
     * Retry CreateMonitoredItems for [live] until every item is SYNCHRONIZED or refused for a
     * configuration reason. Backs off from 250 ms to 5 s. Stops while the session is down;
     * [Activity.onSessionActive] starts it again.
     */
    private fun scheduleSyncRetry(live: LiveSub) {
        if (closed || !live.open.get()) return
        if (!live.syncRetryArmed.compareAndSet(false, true)) return
        val delay = live.syncBackoffMs
        live.syncBackoffMs = (delay * 2).coerceAtMost(5_000)
        runCatching {
            timer.schedule(Runnable {
                runCatching { executor.execute { retrySync(live) } }
                    .onFailure { live.syncRetryArmed.set(false) }
            }, delay, TimeUnit.MILLISECONDS)
        }.onFailure { live.syncRetryArmed.set(false) }
    }

    private fun retrySync(live: LiveSub) {
        live.syncRetryArmed.set(false)
        if (closed || !live.open.get() || milo == null || !status.connected) return
        if (live.opc.subscriptionId.isEmpty) return // recreate() owns a subscription that is gone
        val retried = synchronized(watchLock) {
            val before = live.members.filter { it.item?.syncState != OpcUaMonitoredItem.SyncState.SYNCHRONIZED }
            val ok = runCatching { live.opc.synchronizeMonitoredItems() }
                .onFailure { log.log(Level.FINE, "monitored item sync retry failed", it) }
                .isSuccess
            reportRefused(live)
            if (ok || !needsSync(live)) {
                live.syncBackoffMs = 250
                itemSyncs.incrementAndGet()
            } else {
                scheduleSyncRetry(live)
            }
            before.filter { it.item?.syncState == OpcUaMonitoredItem.SyncState.SYNCHRONIZED }
        }
        // The server sends initial values for new items; read them too, as flushLocked does.
        if (retried.isNotEmpty()) deliverRead(retried.map { watch -> watch.nodeId to { sample -> emit(watch, sample) } })
    }

    /** True while an item that can still be created is not SYNCHRONIZED. */
    private fun needsSync(live: LiveSub): Boolean =
        live.opc.monitoredItems.any { item ->
            item.syncState != OpcUaMonitoredItem.SyncState.SYNCHRONIZED && !refused(item)
        }

    private fun refused(item: OpcUaMonitoredItem): Boolean {
        val result = item.createResult.orElse(null) ?: return false
        return !result.isGood && result.value in CONFIGURATION_REFUSALS
    }

    /** Remove items the server refused for a configuration reason and give their watchers that status. */
    private fun reportRefused(live: LiveSub) {
        for (watch in live.members) {
            val item = watch.item ?: continue
            if (!refused(item)) continue
            val code = item.createResult.get()
            watch.item = null
            watch.group = null
            live.members.remove(watch)
            live.items.remove(item)
            runCatching { live.opc.removeMonitoredItem(item) }
            val now = Instant.now()
            val qv = sample(watch.nodeId, null, Quality.fromOpcUa(code.value), now, null, true)
            emit(watch, qv)
        }
    }

    /**
     * Read every member of [live] again and deliver the values (onNotificationDataLost,
     * onWatchdogTimerElapsed). Keep-alives do not vouch for the members until a read succeeds.
     * A failed read is retried after a second while the subscription is open.
     */
    private fun scheduleResync(live: LiveSub, reason: String) {
        if (closed || !live.open.get()) return
        live.suspect.set(true)
        live.resyncAgain.set(true)
        if (!live.resyncing.compareAndSet(false, true)) return
        log.info("re-reading subscription ${live.id} on ${config.name}: $reason")
        runCatching { executor.execute { runResync(live, reason) } }.onFailure { live.resyncing.set(false) }
    }

    private fun runResync(live: LiveSub, reason: String) {
        var ok = true
        try {
            while (ok && live.resyncAgain.getAndSet(false)) {
                ok = resyncOnce(live)
            }
        } finally {
            live.resyncing.set(false)
        }
        if (!live.open.get() || closed) return
        if (ok && !live.resyncAgain.get()) {
            live.suspect.set(false)
        } else if (ok) {
            scheduleResync(live, reason)
        } else {
            runCatching { timer.schedule(Runnable { scheduleResync(live, reason) }, 1, TimeUnit.SECONDS) }
        }
    }

    private fun resyncOnce(live: LiveSub): Boolean {
        val client = milo
        if (client == null || !status.connected) return false
        val targets = ArrayList<Pair<String, (QualifiedValue) -> Unit>>()
        for (address in live.addresses) targets += address to live.listener
        for (watch in live.members) if (!watch.cancelled.get() && watch.item != null) {
            targets += watch.nodeId to { sample -> emit(watch, sample) }
        }
        val pending = ArrayList<Pair<List<Pair<String, (QualifiedValue) -> Unit>>, List<QualifiedValue>>>()
        for (chunk in targets.chunked(500)) {
            val values = try {
                readOrThrow(client, chunk.map { it.first })
            } catch (e: Exception) {
                // readOrThrow already counted the error and, when it was one, the timeout.
                log.log(Level.FINE, "re-read after lost notifications failed", e)
                return false
            }
            pending += chunk to values
        }
        // Count the re-read before listeners run. A second lost-notification can queue another
        // resync while this one delivers, and the caller must already see this one.
        resyncs.incrementAndGet()
        for ((chunk, values) in pending) {
            for (i in chunk.indices) {
                try { chunk[i].second(values[i]) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
            }
        }
        return true
    }

    private fun deliverRead(targets: List<Pair<String, (QualifiedValue) -> Unit>>) {
        for (chunk in targets.chunked(500)) {
            val values = runCatching { read(chunk.map { it.first }) }.getOrNull() ?: return
            for (i in chunk.indices) {
                try { chunk[i].second(values[i]) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
            }
        }
    }

    /** One Read of [nodeIds]; throws when the service call fails instead of reporting comms loss. */
    private fun readOrThrow(client: MiloClient, nodeIds: List<String>): List<QualifiedValue> {
        val started = System.nanoTime()
        requests.incrementAndGet()
        val ids = nodeIds.map { NodeId.parse(it) }
        val values = try {
            val read = client.readValues(0.0, TimestampsToReturn.Both, ids)
            stats.noteSample(System.nanoTime() - started)
            read
        } catch (e: Exception) {
            failures.incrementAndGet()
            if (isTimeout(e)) timeouts.incrementAndGet()
            throw e
        }
        return nodeIds.indices.map { fromData(nodeIds[it], values[it]) }
    }

    private fun synchronizedCount(live: LiveSub): Int {
        if (!live.open.get()) return 0
        return runCatching {
            live.opc.monitoredItems.count { it.syncState == OpcUaMonitoredItem.SyncState.SYNCHRONIZED }
        }.getOrDefault(0)
    }

    private fun noteSubscriptionTimeout() {
        failures.incrementAndGet()
        timeouts.incrementAndGet()
    }

    private fun isTimeout(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is UaException && current.statusCode.value == StatusCodes.Bad_Timeout) return true
            if (current is java.util.concurrent.TimeoutException) return true
            current = current.cause
        }
        return false
    }

    private fun noteTransferred(subscriptionId: Long) {
        if (countedTransferIds.add(subscriptionId)) transfers.incrementAndGet()
    }

    private fun buildClient(): MiloClient {
        val discovered = DiscoveryClient.getEndpoints(config.endpointUrl).get(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
        val selected = selectEndpoint(discovered, config)
        val rewritten = EndpointUtil.updateUrl(
            selected,
            config.connectHost,
            config.connectPort,
        )
        val needsCert = config.securityPolicy != OpcUaSecurityPolicy.NONE
        val material = when {
            config.certificate != null && config.keyPair != null -> config.keyPair to config.certificate
            needsCert -> {
                val generated = OpcUaCertificates.generate(config.applicationUri, config.applicationName)
                generated.keyPair to generated.certificate
            }
            else -> null
        }
        val identity = when (val auth = config.auth) {
            OpcUaAuth.Anonymous -> AnonymousProvider()
            is OpcUaAuth.Username -> UsernameProvider(auth.username, secrets(auth.password))
            is OpcUaAuth.Certificate -> X509IdentityProvider(auth.certificate, auth.privateKey)
        }
        val validator = certificateValidator()
        val clientConfig = OpcUaClientConfig.builder()
            .setEndpoint(rewritten)
            .setDiscoveryEndpoints(discovered)
            .setApplicationName(LocalizedText.english(config.applicationName))
            .setApplicationUri(config.applicationUri)
            .setProductUri("urn:oip:driver-opcua-client")
            .setSessionTimeout(uint(config.sessionTimeoutMs))
            .setRequestTimeout(uint(config.requestTimeoutMs))
            .setKeepAliveInterval(uint(config.keepAliveIntervalMs))
            .setKeepAliveTimeout(uint(config.keepAliveTimeoutMs))
            .setKeepAliveFailuresAllowed(uint(config.keepAliveFailuresAllowed.toLong()))
            .setIdentityProvider(identity)
            .apply {
                if (validator != null) setCertificateValidator(validator)
                if (material != null) {
                    setKeyPair(material.first)
                    setCertificate(material.second)
                    setCertificateChain(arrayOf(material.second))
                }
            }
            .build()
        val transport = TransferObservingTransport(OpcTcpClientTransportConfig.newBuilder().build(), ::noteTransferred)
        return MiloClient(clientConfig, transport)
    }

    /**
     * Secure channels authenticate the server certificate against [OpcUaConnectionConfig.pkiDir].
     * SecurityPolicy.None does not present a certificate. [OpcUaConnectionConfig.trustAllServerCertificates]
     * installs Milo's insecure validator explicitly and logs a warning.
     */
    private fun certificateValidator(): CertificateValidator? {
        if (config.trustAllServerCertificates) {
            log.warning(
                "trustAllServerCertificates is set on ${config.name}; server certificates are not authenticated",
            )
            closePki()
            return CertificateValidator.InsecureCertificateValidator()
        }
        val dir = config.pkiDir ?: return null
        closePki()
        val manager = FileBasedTrustListManager.createAndInitialize(dir)
        trustList = manager
        val quarantine = FileBasedCertificateQuarantine.create(dir.resolve("rejected").resolve("certs"))
        return DefaultClientCertificateValidator(manager, quarantine)
    }

    private fun closePki() {
        val manager = trustList
        trustList = null
        if (manager != null) runCatching { manager.close() }
    }

    private fun fromData(address: String, value: DataValue): QualifiedValue {
        val quality = Quality.fromOpcUa(value.statusCode?.value ?: StatusCode.BAD.value)
        val source = value.sourceTime?.takeIf { it.isNotNull }?.javaInstant
        return sample(address, value.value?.value, quality, Instant.now(), source, replace = true)
    }

    private fun publishLoss(diagnostic: String) {
        val now = Instant.now()
        val addresses = subs.flatMap { it.addresses }.distinct()
        for (addr in addresses) {
            val qv = lost(addr, now, diagnostic)
            subs.filter { addr in it.addresses }.forEach { sub ->
                try { sub.listener(qv) } catch (t: Throwable) { log.log(Level.FINE, "listener failed", t) }
            }
        }
        for (watch in watches) {
            if (watch.cancelled.get() || watch.nodeId in addresses) continue
            emit(watch, lost(watch.nodeId, now, diagnostic))
        }
    }

    private fun lost(address: String, now: Instant, diagnostic: String?): QualifiedValue {
        val prev = cache[address]
        val code = if (!everActive && prev?.value == null) QualityCode.Bad_WaitingForInitialData else config.commsLossQuality
        return sample(address, prev?.value, Quality.of(code, diagnostic), now, prev?.sourceTimestamp, true)
    }

    private fun sample(
        address: String, value: Any?, quality: Quality, timestamp: Instant, source: Instant?, replace: Boolean,
    ): QualifiedValue {
        val qv = QualifiedValue(address, value, quality, timestamp, source)
        if (replace) {
            cache[address] = qv
            if (quality.level == QualityLevel.GOOD) lastGood[address] = qv
        }
        return qv
    }

    private fun isCommsLoss(code: QualityCode) = when (code) {
        QualityCode.Bad_NotConnected,
        QualityCode.Bad_NoCommunication,
        QualityCode.Bad_Timeout,
        QualityCode.Uncertain_LastKnownValue,
        -> true
        else -> false
    }

    private fun setStatus(next: ConnectionStatus) {
        val withCounts = next.copy(requestCount = requests.get(), failureCount = failures.get())
        status = withCounts
        statusListeners.forEach { cb ->
            try { cb(withCounts) } catch (t: Throwable) { log.log(Level.FINE, "status listener failed", t) }
        }
    }

    private fun unwrap(error: Exception): Exception {
        var current: Throwable = error
        while (current.cause != null && current !is UaException) current = current.cause!!
        return (current as? Exception) ?: error
    }

    private inner class Activity : SessionActivityListener {
        override fun onSessionInactive(session: org.eclipse.milo.opcua.sdk.client.UaSession) {
            sessionIdText = null
            sessionNameText = null
            serverStateName = null
            if (!everActive || closed) return
            log.info("session inactive ${config.name}")
            setStatus(status.copy(
                state = ConnectionState.RECONNECTING, connected = false, since = Instant.now(),
                lastError = "session inactive",
            ))
            publishLoss("session inactive")
        }

        override fun onSessionActive(session: org.eclipse.milo.opcua.sdk.client.UaSession) {
            everActive = true
            sessionIdText = session.sessionId.toParseableString()
            sessionNameText = session.sessionName
            log.info("session active ${config.name}")
            // A failed transfer resets the subscription before the session is active. Create it
            // again here if that attempt has not already succeeded. A transferred subscription
            // still has its id, so this does not run, and the id is not compared to a previous one.
            for (sub in subs) {
                if (sub.open.get() && sub.opc.subscriptionId.isEmpty) executor.execute { recreate(sub) }
                else if (sub.open.get() && needsSync(sub)) {
                    sub.syncBackoffMs = 250
                    scheduleSyncRetry(sub)
                }
            }
            executor.execute { flushWatches() }
            setStatus(status.copy(
                state = ConnectionState.CONNECTED, connected = true, lastError = null, since = Instant.now(),
            ))
        }
    }

    private inner class LiveSub(
        override val id: String,
        val opc: OpcUaSubscription,
        val addresses: List<String>,
        @Volatile override var samplingIntervalMs: Double,
        @Volatile override var publishingIntervalMs: Double,
        val listener: (QualifiedValue) -> Unit,
    ) : TagSubscription {
        /** Sampling interval asked for when the subscription was created. [samplingIntervalMs] may be revised. */
        val requestedSamplingMs: Double = samplingIntervalMs
        /** Publishing interval asked for. [publishingIntervalMs] becomes the server's revised value. */
        val requestedPublishingMs: Double = publishingIntervalMs
        val items = ArrayList<OpcUaMonitoredItem>()
        val members = CopyOnWriteArrayList<PendingWatch>()
        val open = AtomicBoolean(true)
        val recreating = AtomicBoolean(false)
        val aliveRefresh = AtomicBoolean(false)
        /** Notifications were lost; the cached values wait for a re-read. */
        val suspect = AtomicBoolean(false)
        val resyncing = AtomicBoolean(false)
        val resyncAgain = AtomicBoolean(false)
        val syncRetryArmed = AtomicBoolean(false)
        @Volatile var syncBackoffMs: Long = 250

        override fun close() {
            if (!open.compareAndSet(true, false)) return
            subs.remove(this)
            runCatching { opc.delete() }
        }
    }

    private class PendingWatch(
        val nodeId: String,
        val samplingMs: Double,
        first: WatchSlot,
    ) {
        val slots = CopyOnWriteArrayList<WatchSlot>().apply { add(first) }
        val cancelled = AtomicBoolean(false)
        @Volatile var item: OpcUaMonitoredItem? = null
        @Volatile var group: LiveSub? = null
    }

    /** OPC UA ServerState enumeration, Part 5, in numeric order. */
    private fun serverStateLabel(raw: Any?): String? = when (raw) {
        null -> null
        is org.eclipse.milo.opcua.stack.core.types.enumerated.ServerState -> raw.name
        is Number -> SERVER_STATES.getOrNull(raw.toInt())
        else -> raw.toString().substringAfterLast('.').substringBefore('(')
    }

    private class WatchSlot(
        val onSample: (QualifiedValue) -> Unit,
        val onAlive: (QualifiedValue) -> Unit,
    )

    companion object {
        private val SERVER_STATES = listOf(
            "Running", "Failed", "NoConfiguration", "Suspended", "Shutdown", "Test", "CommunicationFault", "Unknown",
        )

        /** CreateMonitoredItems results that a retry cannot fix (Part 4 §5.12.2.4). */
        private val CONFIGURATION_REFUSALS = setOf(
            StatusCodes.Bad_NodeIdInvalid,
            StatusCodes.Bad_NodeIdUnknown,
            StatusCodes.Bad_AttributeIdInvalid,
            StatusCodes.Bad_IndexRangeInvalid,
            StatusCodes.Bad_DataEncodingInvalid,
            StatusCodes.Bad_DataEncodingUnsupported,
            StatusCodes.Bad_MonitoredItemFilterInvalid,
            StatusCodes.Bad_MonitoredItemFilterUnsupported,
            StatusCodes.Bad_FilterNotAllowed,
            StatusCodes.Bad_NotReadable,
            StatusCodes.Bad_UserAccessDenied,
        )

        fun discover(endpointUrl: String, timeoutMs: Long = 5_000): List<OpcUaEndpoint> {
            val found = DiscoveryClient.getEndpoints(endpointUrl).get(timeoutMs, TimeUnit.MILLISECONDS)
            return found.map { it.toEndpoint() }
        }

        internal fun selectEndpoint(endpoints: List<EndpointDescription>, config: OpcUaConnectionConfig): EndpointDescription {
            val mode = config.securityMode.toMilo()
            val token = config.auth.tokenType()
            return endpoints.firstOrNull { ep ->
                ep.securityPolicyUri == config.securityPolicy.uri &&
                    ep.securityMode == mode &&
                    (ep.userIdentityTokens ?: emptyArray()).any { it.tokenType == token }
            } ?: throw UaException(
                org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_ConfigurationError,
                "no endpoint for ${config.securityPolicy} ${config.securityMode} $token",
            )
        }

        private fun EndpointDescription.toEndpoint() = OpcUaEndpoint(
            endpointUrl = endpointUrl ?: "",
            securityPolicyUri = securityPolicyUri ?: "",
            securityMode = securityMode?.name ?: "",
            userTokenTypes = (userIdentityTokens ?: emptyArray()).map { it.tokenType.name },
        )
    }
}

/**
 * One OPC UA client connection's diagnostics.
 * Doc source: FEATURES DEV-016.
 */
data class OpcUaConnectionDiagnostics(
    val connected: Boolean,
    val state: String,
    val requestCount: Long,
    val errorCount: Long,
    val timeoutCount: Long,
    val averageResponseTimeMs: Double?,
    val samplingIntervalMs: Double?,
    val publishIntervalMs: Double?,
    val serverState: String?,
    val sessionId: String?,
    val sessionName: String?,
    val subscriptionCount: Int,
    /** Items whose sync state is SYNCHRONIZED. A refused or not-yet-created item is not included. */
    val monitoredItemCount: Int,
    val subscriptions: List<OpcUaSubscriptionDiagnostics>,
    val lastError: String?,
    val since: Instant,
)

/** One subscription on an OPC UA client connection. Item count is server-created items only. */
data class OpcUaSubscriptionDiagnostics(
    val name: String,
    val samplingIntervalMs: Double,
    val requestedPublishingIntervalMs: Double,
    val revisedPublishingIntervalMs: Double,
    val monitoredItemCount: Int,
)

/**
 * Counts Good TransferSubscriptions results. Milo's session FSM sends that request on the transport
 * and, for each Good result, calls PublishingManager.notifySubscriptionTransferred. There is no
 * onTransferSucceeded listener. Good_SubscriptionTransferred is a status notification for the
 * previous session, which often cannot be delivered after the channel is gone, so the service
 * result is the success signal. Subscription ids are recorded only to count each id once.
 */
private class TransferObservingTransport(
    config: OpcTcpClientTransportConfig,
    private val onTransferred: (Long) -> Unit,
) : OpcTcpClientTransport(config) {
    override fun sendRequestMessage(request: UaRequestMessageType): CompletableFuture<UaResponseMessageType> {
        val future = super.sendRequestMessage(request)
        if (request is TransferSubscriptionsRequest) {
            val ids = request.subscriptionIds
            future.whenComplete { response, _ ->
                if (response !is TransferSubscriptionsResponse || ids == null) return@whenComplete
                val results = response.results ?: return@whenComplete
                for (i in results.indices) {
                    if (i >= ids.size) break
                    if (results[i].statusCode?.isGood == true) onTransferred(ids[i].toLong())
                }
            }
        }
        return future
    }
}

/** The answer of [OpcUaConnection.callMethod]: the status code, and the output arguments in order. */
data class MethodCallResult(val statusCode: Long, val good: Boolean, val outputs: List<Any?>, val inputResults: List<Long>)
