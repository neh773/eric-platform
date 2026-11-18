package org.oip.redundancy

import io.grpc.BindableService
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmSyncState
import org.oip.config.FileConfigStore
import org.oip.gateway.Json
import org.oip.gateway.TagDriverBridge
import org.oip.network.GatewayNetwork
import org.oip.network.v1.ConfigDocument
import org.oip.network.v1.ConfigPullRequest
import org.oip.network.v1.ConfigPullResponse
import org.oip.network.v1.HistoryForwardRequest
import org.oip.network.v1.HistoryForwardResponse
import org.oip.network.v1.HistoryPoint
import org.oip.network.v1.RedundancyExchange
import org.oip.network.v1.RedundancyGrpc
import org.oip.network.v1.RuntimeAlarm
import org.oip.network.v1.RuntimePullRequest
import org.oip.network.v1.RuntimePullResponse
import org.oip.network.v1.RuntimePush
import org.oip.network.v1.RuntimeTag
import org.oip.tags.DataType
import org.oip.tags.TagEngine
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Master/backup redundancy over the mutual-TLS peer network.
 * The backup dials the master. An independent gateway publishes system tags and does not start a thread.
 *
 * [snapshot], [role], [activity], [gate], and [isActive] are the read API for other gateway services.
 * [addListener] fires when activity changes, on `redundancy-listener`, not on `redundancy`.
 *
 */
class RedundancyRuntime(
    var settings: RedundancySettings,
    private val network: GatewayNetwork,
    store: FileConfigStore,
    private val tags: TagEngine,
    private val alarms: AlarmEngine,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(RedundancyRuntime::class.java)
    private val lock = Any()
    private val configMirror = ConfigMirror(store)
    private val localTags = LocalTags(store)
    private val runtimeMirror = RuntimeMirror(tags, alarms, localTags::isLocal)
    private val history = HistoryBuffer(settings.historyMode, settings.mode)
    private val windows = ActiveWindows()
    private val events = ArrayDeque<String>()
    /** Written only under [lock]. Every write republishes [published] for lock-free reads. */
    private var state: RedundancyState = settings.state()
        set(value) {
            field = value
            published = snapshotOf(value)
        }

    /** Latest snapshot. Hot paths (alarm journal, scripts, charts, event streams) read this without the lock. */
    @Volatile private var published: RedundancySnapshot = snapshotOf(state)

    /** Last snapshot handed to listeners. Read and written under [lock]. */
    private var emitted: RedundancySnapshot = published
    private var activityDirty = false
    private val listeners = CopyOnWriteArrayList<RedundancyListener>()
    private val notifier = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "redundancy-listener").apply { isDaemon = true }
    }
    private var thread: Thread? = null
    private var syncThread: Thread? = null
    private val syncGate = Object()
    private var syncNeeded = false
    private var pushPending = false
    private var drivers: TagDriverBridge? = null
    private var started = false
    @Volatile private var closed = false
    private var armed = false
    private var lastExchangeMillis = 0L
    private var lastTickMillis = System.currentTimeMillis()
    private var activeUptime = 0L
    private var reportedActivity: ActivityLevel? = null
    private var reportedConnected: Boolean? = null
    @Volatile private var appliedConfig = -1L
    @Volatile private var appliedRuntime = -1L

    val service: BindableService = Service()

    fun attachDrivers(bridge: TagDriverBridge) {
        drivers = bridge
        applyGate()
    }

    /**
     * Current role, activity, and gate. Lock-free, so it is safe on hot paths and from a [RedundancyListener].
     * An independent gateway is active, with [RuntimeGate.ACTIVE].
     * This can run ahead of listener delivery: a change is visible here before listeners hear about it.
     */
    fun snapshot(): RedundancySnapshot = published

    fun role(): NodeRole = snapshot().role

    fun activity(): ActivityLevel = snapshot().activity

    fun gate(): RuntimeGate = snapshot().gate

    /** True when this node should perform active-only work. An independent gateway is always active. */
    fun isActive(): Boolean = snapshot().active

    /**
     * Register [listener]. Delivery is serialized on `redundancy-listener` in registration order,
     * and in the order the changes happened: each call's `previous` is the `current` of the call before it.
     * The callback is not on the heartbeat thread and does not run while the redundancy lock
     * or the tag-engine lock is held. One listener's failure is logged and does not skip later listeners.
     * The returned handle removes the listener. A listener added during a callback does not see that callback.
     *
     * With [replay] true, the listener first receives the current state as one call with
     * `previous == current`, on `redundancy-listener`, ahead of any later change. This is the
     * startup pattern: no change can fall between reading the state and subscribing.
     * Without it, nothing is delivered until the next change.
     */
    fun addListener(replay: Boolean, listener: RedundancyListener): AutoCloseable {
        synchronized(lock) {
            // Same lock as flushActivity, so the replayed snapshot and later changes stay in order.
            listeners.add(listener)
            if (replay) {
                val now = emitted
                enqueueLocked { deliver(listener, now, now) }
            }
        }
        return AutoCloseable { removeListener(listener) }
    }

    /** Same as `addListener(replay = false, listener)`. Prefer `replay = true` for a service that needs the current state. */
    fun addListener(listener: RedundancyListener): AutoCloseable = addListener(replay = false, listener = listener)

    fun removeListener(listener: RedundancyListener) {
        listeners.remove(listener)
    }

    fun start() {
        if (started) return
        started = true
        release()
        if (settings.mode == NodeRole.INDEPENDENT) return
        configMirror.start()
        localTags.start()
        if (settings.mode == NodeRole.BACKUP && network.settings.enabled) {
            val present = runCatching { network.outgoingStatus(settings.connectionName) }.isSuccess
            if (!present) {
                network.addOutgoing(settings.connectionName, settings.masterHost, settings.masterPort, true, "redundancy")
            }
        }
        if (settings.mode == NodeRole.BACKUP) {
            // Config apply has to stay off this loop. A full pull of a busy gateway holds the
            // caller until the store finishes, and the backup cannot count missed heartbeats
            // or take over while that call is on the same thread.
            val syncer = Thread({ runCatching { syncLoop() }.onFailure { log.warn("redundancy sync stopped", it) } }, "redundancy-sync")
            syncer.isDaemon = true
            syncThread = syncer
            syncer.start()
        }
        val worker = Thread({ runCatching { loop() }.onFailure { log.warn("redundancy loop stopped", it) } }, "redundancy")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    fun onLink(name: String, up: Boolean) {
        if (up && name == settings.connectionName) thread?.interrupt()
    }

    fun requestFailover() = dispatch(RedundancyEvent.RequestFailover)

    fun assumeControl() = dispatch(RedundancyEvent.AssumeControl)

    fun forceResync() {
        appliedConfig = -1L
        thread?.interrupt()
    }

    fun retargetMaster(host: String, port: Int) {
        network.retargetOutgoing(settings.connectionName, host, port)
    }

    /** Replace the pair settings and point the backup link at the master. The activity state is left as it is. */
    fun replaceSettings(next: RedundancySettings) {
        settings = next
        if (next.mode == NodeRole.INDEPENDENT) return
        try {
            retargetMaster(next.masterHost, next.masterPort)
        } catch (_: NoSuchElementException) {
            // The outgoing connection is created when the pair link starts. The file is already saved.
        }
    }

    fun noteHistory(provider: String, path: String, value: Any?, quality: String, timestampMillis: Long) {
        val active = synchronized(lock) { state.activity == ActivityLevel.ACTIVE }
        history.note(HistorySample(provider, path, Json.write(value), quality, timestampMillis), active)
    }

    fun pendingHistory(): Int = history.pendingCount()

    fun loggedHistory(): List<HistorySample> = history.logged()

    fun statusDocument(): Map<String, Any?> {
        val snap = synchronized(lock) { state to activeUptime }
        val current = snap.first
        return linkedMapOf(
            "mode" to settings.mode.wire().lowercase(),
            "standbyActivityLevel" to settings.standbyActivityLevel.name.lowercase(),
            "startupAllowanceMillis" to settings.startupAllowanceMillis,
            "syncTimeoutSeconds" to settings.syncTimeoutSeconds,
            "recoveryMode" to settings.recoveryMode.wire(),
            "masterHost" to settings.masterHost,
            "masterPort" to settings.masterPort,
            "pingRateMillis" to settings.pingRateMillis,
            "pingTimeoutMillis" to settings.pingTimeoutMillis,
            "pingMaxMissed" to settings.pingMaxMissed,
            "historyMode" to settings.historyMode.name.lowercase(),
            "useActiveUptime" to settings.useActiveUptime,
            "connectionName" to settings.connectionName,
            "role" to current.role.wire(),
            "activityLevel" to current.activity.wire(),
            "standbyActivityLevel" to current.standby.name.lowercase(),
            "connected" to current.connected,
            "peerId" to (current.peer?.nodeId ?: ""),
            "isActive" to current.isActive,
            "isMaster" to current.isMaster,
            "recoveryMode" to current.recovery.wire(),
            "historyMode" to settings.historyMode.name.lowercase(),
            "useActiveUptime" to settings.useActiveUptime,
            "splitBrain" to current.splitBrain,
            "configRevision" to configMirror.revision(),
            "runtimeRevision" to runtimeMirror.revision(),
            "activeUptimeMillis" to snap.second,
            "missedHeartbeats" to current.missed,
            "historyPending" to history.pendingCount(),
            "historyLogged" to history.logged().size,
            "historyStore" to "redundancy-buffer",
            "providers" to tags.providers().map { linkedMapOf("name" to it.name) },
            "events" to synchronized(lock) { events.toList() },
            "activeWindows" to windows.snapshot().map {
                linkedMapOf("startMillis" to it.startMillis, "endMillis" to it.endMillis)
            },
        )
    }

    override fun close() {
        closed = true
        thread?.interrupt()
        syncThread?.interrupt()
        synchronized(syncGate) { syncGate.notifyAll() }
        runCatching { thread?.join(1_000) }
        runCatching { syncThread?.join(1_000) }
        configMirror.close()
        localTags.close()
        notifier.shutdown()
        if (Thread.currentThread().name != "redundancy-listener") {
            runCatching { notifier.awaitTermination(1, TimeUnit.SECONDS) }
        }
    }

    private fun dispatch(event: RedundancyEvent) {
        // During shutdown the listener thread is gone. A late HTTP request must not fail on it.
        if (closed) return
        synchronized(lock) {
            accrueLocked()
            state = reduce(state, event)
            noteActivityLocked()
        }
        release()
        thread?.interrupt()
    }

    private fun loop() {
        val startedAt = System.currentTimeMillis()
        var allowanceFired = settings.startupAllowanceMillis == 0L
        if (settings.mode == NodeRole.BACKUP && settings.startupAllowanceMillis == 0L) {
            synchronized(lock) {
                state = reduce(state, RedundancyEvent.AllowanceElapsed)
                noteActivityLocked()
            }
        }
        if (settings.startupAllowanceMillis == 0L) armed = true
        var wasConnected = false
        while (!closed) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                accrueLocked()
                if (!allowanceFired && settings.startupAllowanceMillis > 0 && now - startedAt >= settings.startupAllowanceMillis) {
                    allowanceFired = true
                    armed = true
                    state = reduce(state, RedundancyEvent.AllowanceElapsed)
                    noteActivityLocked()
                }
                if (settings.mode == NodeRole.MASTER) {
                    val limit = settings.pingRateMillis * settings.pingMaxMissed + settings.pingTimeoutMillis
                    if (state.connected && lastExchangeMillis > 0 && now - lastExchangeMillis > limit) {
                        state = reduce(state, RedundancyEvent.LinkLost)
                        noteActivityLocked()
                    }
                }
            }
            if (settings.mode == NodeRole.MASTER) runtimeMirror.capture()
            if (settings.mode == NodeRole.BACKUP) wasConnected = backupOnce(wasConnected)
            release()
            if (!sleep(settings.pingRateMillis)) break
        }
    }

    private fun backupOnce(wasConnected: Boolean): Boolean {
        val channel = network.outgoingChannel(settings.connectionName) ?: run {
            if (armed) miss()
            return false
        }
        return try {
            val stub = RedundancyGrpc.newBlockingStub(channel)
            val request = synchronized(lock) {
                accrueLocked()
                exchangeOf(state)
            }
            val response = stub.withDeadlineAfter(settings.pingTimeoutMillis, TimeUnit.MILLISECONDS).exchange(request)
            val peer = peerFrom(response)
            synchronized(lock) {
                val before = state.connected
                state = reduce(state, RedundancyEvent.HeartbeatOk(peer))
                lastExchangeMillis = System.currentTimeMillis()
                armed = true
                noteActivityLocked()
                if (!before || !wasConnected) pushPending = true
            }
            requestSync()
            true
        } catch (error: Exception) {
            if (armed) miss()
            false
        }
    }

    private fun requestSync() {
        synchronized(syncGate) {
            syncNeeded = true
            syncGate.notifyAll()
        }
    }

    private fun syncLoop() {
        while (!closed) {
            synchronized(syncGate) {
                while (!syncNeeded && !closed) {
                    try {
                        syncGate.wait(1_000)
                    } catch (_: InterruptedException) {
                        if (closed) return
                    }
                }
                if (closed) return
                syncNeeded = false
            }
            val channel = network.outgoingChannel(settings.connectionName) ?: continue
            val (peer, push) = synchronized(lock) {
                val pending = pushPending
                pushPending = false
                state.peer to pending
            }
            if (peer == null) {
                if (push) synchronized(lock) { pushPending = true }
                continue
            }
            try {
                sync(RedundancyGrpc.newBlockingStub(channel), peer, push)
            } catch (error: Exception) {
                if (push) synchronized(lock) { pushPending = true }
                log.warn("redundancy sync failed", error)
            }
        }
    }

    private fun miss() {
        synchronized(lock) {
            state = reduce(state, RedundancyEvent.HeartbeatMissed)
            noteActivityLocked()
        }
    }

    private fun sync(stub: RedundancyGrpc.RedundancyBlockingStub, peer: PeerSnapshot, justConnected: Boolean) {
        val deadline = settings.syncTimeoutSeconds.coerceAtLeast(1)
        if (justConnected) {
            // The peer may have restarted, and its config and runtime revisions restart at 0 with it.
            // A delta since an older, higher number would then be empty, so the first pulls after a
            // connect are full, as on the very first connect.
            appliedConfig = -1L
            appliedRuntime = -1L
        }
        // Heartbeats keep updating the peer while this apply runs. Use the newest revision
        // so a config pull does not consume the only chance to copy runtime values.
        var current = synchronized(lock) { state.peer } ?: peer
        if (current.configRevision != appliedConfig) pullConfig(stub, deadline)
        current = synchronized(lock) { state.peer } ?: current
        if (justConnected) {
            try {
                val push = runtimeMirror.snapshot()
                val uptime = synchronized(lock) { activeUptime }
                val request = RuntimePush.newBuilder().setActiveUptimeMillis(uptime)
                push.forEach { request.addTags(toProto(it)) }
                alarms.exportRuntime().forEach { request.addAlarms(toProto(it)) }
                val response = stub.withDeadlineAfter(deadline, TimeUnit.SECONDS).pushRuntime(request.build())
                runtimeMirror.apply(response.tagsList.map { fromProto(it) }, response.alarmsList.map { fromProto(it) })
                // The answer covers only the tags this node pushed, so it does not stand for the
                // master's runtime revision. Taking that revision here would skip the values of
                // tags the master added after the config pull above. The full pull below copies them.
            } catch (error: Exception) {
                synchronized(lock) { pushPending = true }
                log.warn("redundancy runtime push failed", error)
            }
            forwardHistory(stub, deadline)
        }
        try {
            if (current.runtimeRevision != appliedRuntime) {
                val pulled = stub.withDeadlineAfter(deadline, TimeUnit.SECONDS)
                    .pullRuntime(RuntimePullRequest.newBuilder().setSinceRevision(appliedRuntime).build())
                val alarmStates = pulled.alarmsList.map { fromProto(it) }
                val missed = runtimeMirror.apply(pulled.tagsList.map { fromProto(it) }, alarmStates)
                // A tag the master created after this node's last config pull is in the runtime
                // answer but not configured here yet, so its value had nowhere to go. Every tag in
                // that answer was configured on the master before it answered, so one more config
                // pull, made after the answer, creates them. Without it the revision below is taken
                // and the value is lost until the master changes it again.
                if (missed.isNotEmpty()) {
                    if (!pullConfig(stub, deadline)) return
                    runtimeMirror.apply(missed, alarmStates)
                }
                appliedRuntime = pulled.revision
            }
        } catch (error: Exception) {
            log.warn("redundancy runtime pull failed", error)
        }
    }

    /** Config pulls this backup has asked the master for. Read by tests. */
    @Volatile internal var configPulls = 0L
        private set

    /** Pull and apply config changes since [appliedConfig]. False when the pull or the apply failed. */
    private fun pullConfig(stub: RedundancyGrpc.RedundancyBlockingStub, deadline: Long): Boolean = try {
        configPulls += 1
        val pulled = stub.withDeadlineAfter(deadline, TimeUnit.SECONDS)
            .pullConfig(ConfigPullRequest.newBuilder().setSinceRevision(appliedConfig).build())
        configMirror.apply(fromConfig(pulled))
        appliedConfig = pulled.revision
        true
    } catch (error: Exception) {
        log.warn("redundancy config sync failed", error)
        false
    }

    private fun forwardHistory(stub: RedundancyGrpc.RedundancyBlockingStub, deadline: Long) {
        val pending = history.peekPending()
        if (pending.isEmpty()) return
        try {
            val request = HistoryForwardRequest.newBuilder()
            pending.forEach { sample ->
                request.addPoints(
                    HistoryPoint.newBuilder()
                        .setProvider(sample.provider)
                        .setPath(sample.path)
                        .setValueJson(sample.valueJson)
                        .setQuality(sample.quality)
                        .setTimestampUnixMillis(sample.timestampMillis)
                        .build(),
                )
            }
            stub.withDeadlineAfter(deadline, TimeUnit.SECONDS).forwardHistory(request.build())
            history.ackPending(pending.size)
        } catch (error: Exception) {
            log.warn("redundancy history forward failed", error)
        }
    }

    private fun accrueLocked() {
        val now = System.currentTimeMillis()
        if (state.activity == ActivityLevel.ACTIVE) activeUptime += (now - lastTickMillis).coerceAtLeast(0)
        lastTickMillis = now
    }

    private fun noteActivityLocked() {
        val now = System.currentTimeMillis()
        if (reportedActivity != state.activity) {
            windows.note(state.activity == ActivityLevel.ACTIVE, now)
            if (reportedActivity != null && reportedActivity != ActivityLevel.ACTIVE && state.activity == ActivityLevel.ACTIVE) {
                recordLocked("active")
            }
            if (reportedActivity == ActivityLevel.ACTIVE && state.activity != ActivityLevel.ACTIVE) recordLocked("standby")
            reportedActivity = state.activity
            activityDirty = true
        }
        if (reportedConnected != state.connected) {
            recordLocked(if (state.connected) "connected" else "disconnected")
            reportedConnected = state.connected
        }
        if (state.splitBrain) recordLocked("split-brain")
    }

    private fun recordLocked(name: String) {
        if (events.lastOrNull() == name) return
        events.addLast(name)
        while (events.size > 50) events.removeFirst()
    }

    private fun publish() {
        val snap = synchronized(lock) { state }
        tags.publishSystem("Gateway/Redundancy/Role", snap.role.wire(), DataType.STRING)
        tags.publishSystem("Gateway/Redundancy/ActivityLevel", snap.activity.wire(), DataType.STRING)
        tags.publishSystem("Gateway/Redundancy/IsActive", snap.isActive, DataType.BOOLEAN)
        tags.publishSystem("Gateway/Redundancy/IsMaster", snap.isMaster, DataType.BOOLEAN)
        tags.publishSystem("Gateway/Redundancy/Connection/IsConnected", snap.connected, DataType.BOOLEAN)
        tags.publishSystem("Gateway/Redundancy/Connection/PeerId", snap.peer?.nodeId ?: "", DataType.STRING)
    }

    private fun applyGate() {
        val bridge = drivers ?: return
        val level = if (settings.mode == NodeRole.INDEPENDENT) ActivityLevel.ACTIVE else synchronized(lock) { state.activity }
        bridge.setGate(RuntimeGate.forActivity(level))
    }

    /** Publish system tags, push the driver gate, then hand activity changes to listeners. */
    private fun release() {
        publish()
        applyGate()
        flushActivity()
    }

    private fun snapshotOf(current: RedundancyState): RedundancySnapshot {
        val level = if (current.role == NodeRole.INDEPENDENT) ActivityLevel.ACTIVE else current.activity
        return RedundancySnapshot(
            role = current.role,
            activity = level,
            gate = RuntimeGate.forActivity(level),
            active = current.isActive,
        )
    }

    /**
     * Hand the latest change to listeners. The previous/current pair is computed and queued under
     * [lock], so two threads flushing at once (the heartbeat loop and an HTTP failover request)
     * cannot queue their changes out of order. Queuing does not block; callbacks run later on
     * `redundancy-listener`, with no lock held.
     */
    private fun flushActivity() {
        synchronized(lock) {
            activityDirty = false
            val current = published
            val previous = emitted
            if (previous == current) return
            emitted = current
            val copy = listeners.toList()
            if (copy.isEmpty()) return
            enqueueLocked {
                for (listener in copy) deliver(listener, previous, current)
            }
        }
    }

    private fun enqueueLocked(task: () -> Unit) {
        if (closed) return
        try {
            notifier.execute(task)
        } catch (_: RejectedExecutionException) {
            // close() raced this call and stopped the listener thread.
        }
    }

    private fun deliver(listener: RedundancyListener, previous: RedundancySnapshot, current: RedundancySnapshot) {
        try {
            listener.onChange(previous, current)
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            log.warn("redundancy listener failed", error)
        }
    }

    private fun sleep(millis: Long): Boolean {
        if (closed) return false
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            // A link-up or a failover request wakes the loop early.
        }
        return !closed
    }

    private fun exchangeOf(current: RedundancyState): RedundancyExchange = RedundancyExchange.newBuilder()
        .setRole(current.role.wire())
        .setActivity(current.activity.wire())
        .setActiveUptimeMillis(activeUptime)
        .setSentUnixMillis(System.currentTimeMillis())
        .setConfigRevision(configMirror.revision())
        .setRuntimeRevision(runtimeMirror.revision())
        .setNodeId(network.settings.gatewayName)
        .setCommand(current.command)
        .setRecovery(current.recovery.wire())
        .setSplitBrain(current.splitBrain)
        .build()

    private fun peerFrom(message: RedundancyExchange) = PeerSnapshot(
        role = NodeRole.fromWire(message.role),
        activity = ActivityLevel.fromWire(message.activity),
        activeUptimeMillis = message.activeUptimeMillis,
        nodeId = message.nodeId,
        configRevision = message.configRevision,
        runtimeRevision = message.runtimeRevision,
        command = message.command,
        recovery = RecoveryMode.fromWire(message.recovery),
        splitBrain = message.splitBrain,
    )

    private fun requireMaster(observer: StreamObserver<*>): Boolean {
        if (settings.mode == NodeRole.MASTER) return true
        observer.onError(Status.FAILED_PRECONDITION.withDescription("redundancy is served by the master").asRuntimeException())
        return false
    }

    private inner class Service : RedundancyGrpc.RedundancyImplBase() {
        override fun exchange(request: RedundancyExchange, responseObserver: StreamObserver<RedundancyExchange>) {
            if (!requireMaster(responseObserver)) return
            val response = synchronized(lock) {
                accrueLocked()
                state = reduce(state, RedundancyEvent.HeartbeatOk(peerFrom(request)))
                lastExchangeMillis = System.currentTimeMillis()
                noteActivityLocked()
                exchangeOf(state)
            }
            responseObserver.onNext(response)
            responseObserver.onCompleted()
            // The loop publishes and notifies. Waking it avoids a second tag publish on this thread.
            val dirty = synchronized(lock) { activityDirty }
            if (dirty) thread?.interrupt()
        }

        override fun pullConfig(request: ConfigPullRequest, responseObserver: StreamObserver<ConfigPullResponse>) {
            if (!requireMaster(responseObserver)) return
            val pull = configMirror.pull(request.sinceRevision)
            val builder = ConfigPullResponse.newBuilder().setFull(pull.full).setRevision(pull.revision)
            for (doc in pull.documents) {
                builder.addDocuments(
                    ConfigDocument.newBuilder()
                        .setScope(doc.scope)
                        .setModule(doc.module)
                        .setKind(doc.kind)
                        .setName(doc.name)
                        .setSpecJson(Json.write(doc.spec))
                        .setLabelsJson(Json.write(doc.labels))
                        .setDeleted(doc.deleted)
                        .build(),
                )
            }
            responseObserver.onNext(builder.build())
            responseObserver.onCompleted()
        }

        override fun pullRuntime(request: RuntimePullRequest, responseObserver: StreamObserver<RuntimePullResponse>) {
            if (!requireMaster(responseObserver)) return
            val pull = runtimeMirror.pull(request.sinceRevision)
            responseObserver.onNext(runtimeResponse(pull, synchronized(lock) { activeUptime }))
            responseObserver.onCompleted()
        }

        override fun pushRuntime(request: RuntimePush, responseObserver: StreamObserver<RuntimePullResponse>) {
            if (!requireMaster(responseObserver)) return
            val uptime = synchronized(lock) { activeUptime }
            val resolved = runtimeMirror.resolvePush(
                request.tagsList.map { fromProto(it) },
                request.activeUptimeMillis,
                uptime,
                settings.useActiveUptime,
            )
            val alarmsNow = alarms.exportRuntime()
            val bundle = RuntimeBundle(full = true, revision = runtimeMirror.revision(), tags = resolved, alarms = alarmsNow)
            responseObserver.onNext(runtimeResponse(bundle, uptime))
            responseObserver.onCompleted()
        }

        override fun forwardHistory(request: HistoryForwardRequest, responseObserver: StreamObserver<HistoryForwardResponse>) {
            if (!requireMaster(responseObserver)) return
            var accepted = 0
            var dropped = 0
            val open = windows.snapshot()
            for (point in request.pointsList) {
                val keep = settings.historyMode == HistoryMode.FULL || historyWhileMasterDown(point.timestampUnixMillis, open)
                if (keep) {
                    history.log(
                        HistorySample(point.provider, point.path, point.valueJson, point.quality, point.timestampUnixMillis),
                    )
                    accepted += 1
                } else {
                    dropped += 1
                }
            }
            responseObserver.onNext(HistoryForwardResponse.newBuilder().setAccepted(accepted).setDropped(dropped).build())
            responseObserver.onCompleted()
        }
    }

    private fun runtimeResponse(bundle: RuntimeBundle, uptime: Long): RuntimePullResponse {
        val builder = RuntimePullResponse.newBuilder()
            .setFull(bundle.full)
            .setRevision(bundle.revision)
            .setActiveUptimeMillis(uptime)
        bundle.tags.forEach { builder.addTags(toProto(it)) }
        bundle.alarms.forEach { builder.addAlarms(toProto(it)) }
        return builder.build()
    }
}

private fun toProto(snap: TagSnap): RuntimeTag = RuntimeTag.newBuilder()
    .setProvider(snap.provider)
    .setPath(snap.path)
    .setValueJson(snap.valueJson)
    .setQuality(snap.quality)
    .setTimestampUnixMillis(snap.timestampMillis)
    .setType(snap.type)
    .build()

private fun fromProto(tag: RuntimeTag) = TagSnap(
    provider = tag.provider,
    path = tag.path,
    valueJson = tag.valueJson,
    quality = tag.quality,
    timestampMillis = tag.timestampUnixMillis,
    type = tag.type,
)

private fun toProto(state: AlarmSyncState): RuntimeAlarm = RuntimeAlarm.newBuilder()
    .setSource(state.source)
    .setTagPath(state.tagPath)
    .setActive(state.active)
    .setAcknowledged(state.acknowledged)
    .setAckUser(state.ackUser ?: "")
    .setShelvedUntilMillis(state.shelvedUntilMillis ?: 0L)
    .setShelved(state.shelvedUntilMillis != null)
    .setEventId(state.eventId ?: "")
    .build()

private fun fromProto(alarm: RuntimeAlarm) = AlarmSyncState(
    source = alarm.source,
    tagPath = alarm.tagPath,
    active = alarm.active,
    acknowledged = alarm.acknowledged,
    ackUser = alarm.ackUser.ifEmpty { null },
    shelvedUntilMillis = if (alarm.shelved) alarm.shelvedUntilMillis else null,
    eventId = alarm.eventId.ifEmpty { null },
)

private fun fromConfig(response: ConfigPullResponse): ConfigPull {
    val docs = response.documentsList.map { doc ->
        ConfigDoc(
            scope = doc.scope,
            module = doc.module,
            kind = doc.kind,
            name = doc.name,
            spec = if (doc.specJson.isEmpty()) emptyMap() else Json.parse(doc.specJson),
            labels = if (doc.labelsJson.isEmpty()) emptyMap() else stringMap(Json.parse(doc.labelsJson)),
            deleted = doc.deleted,
        )
    }
    return ConfigPull(response.full, response.revision, docs)
}

private fun stringMap(raw: Map<String, Any?>): Map<String, String> =
    raw.mapNotNull { (key, value) -> value?.toString()?.let { key to it } }.toMap()
