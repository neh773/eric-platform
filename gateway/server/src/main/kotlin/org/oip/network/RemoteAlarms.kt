package org.oip.network

import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmEventView
import org.oip.alarm.AlarmFilter
import org.oip.alarm.AlarmPriority
import org.oip.alarm.RemoteCaller
import org.oip.alarm.ShelvedAlarmView
import org.oip.alarm.alarmSourceTagPath
import org.oip.alarm.wildcardMatch
import org.oip.tags.TagAccess
import org.oip.gateway.Json
import org.oip.security.AccessDeniedException
import org.oip.network.v1.AlarmCaller
import org.oip.network.v1.AlarmAckRequest
import org.oip.network.v1.AlarmAckResponse
import org.oip.network.v1.AlarmQueryRequest
import org.oip.network.v1.AlarmQueryResponse
import org.oip.network.v1.AlarmShelveRequest
import org.oip.network.v1.AlarmShelveResponse
import org.oip.network.v1.AlarmSnapshot
import org.oip.network.v1.AlarmSubscribeRequest
import org.oip.network.v1.AlarmUnshelveRequest
import org.oip.network.v1.AlarmUnshelveResponse
import org.oip.network.v1.AlarmShelvedList
import org.oip.network.v1.AlarmShelvedQuery
import org.oip.network.v1.AlarmView
import org.oip.network.v1.RemoteAlarmsGrpc
import org.oip.network.v1.ShelvedAlarm
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Edge sync pushes history under [SERVICE_HISTORY].
 *
 * A client-facing history query, the kind a remote tag provider has for tags, is not
 * defined next to [RemoteAlarmsGrpc]. Live alarm status is that service. The alarm-journal
 * edge-sync push and the redundancy history buffer are separate.
 */
object RemoteHistory {
    const val SERVICE: String = SERVICE_HISTORY
}

/** One acknowledgement attempt by a remote peer, written on the gateway that owns the alarm. */
internal data class RemoteAlarmAudit(
    val peerName: String,
    val fingerprint: String,
    val user: String,
    val eventIds: List<String>,
    val success: Boolean,
    val detail: String,
)

internal interface AlarmServerHost {
    fun dropping(): Boolean
    fun fingerprint(): String
    fun access(fingerprint: String): ServiceAccess
    fun peerName(fingerprint: String): String
    fun engine(): AlarmEngine?
    /** Queue [task] off the tag thread. The task snapshots alarm state, then does network I/O. */
    fun schedule(task: () -> Unit)
    fun audit(event: RemoteAlarmAudit)
    fun protocol(fingerprint: String): Int
    fun readAccess(path: String): TagAccess?
    fun writeAccess(path: String): TagAccess?
}

/**
 * Server side of [RemoteAlarmsGrpc]. Reads run on the gateway that owns the alarms.
 * The tag lock is not held while a snapshot is written to the stream.
 */
internal class RemoteAlarmService(
    private val host: AlarmServerHost,
) : RemoteAlarmsGrpc.RemoteAlarmsImplBase() {
    override fun query(request: AlarmQueryRequest, responseObserver: StreamObserver<AlarmQueryResponse>) {
        val engine = try {
            readableEngine()
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val provider = try {
            providerOrThrow(request.provider)
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val views = try {
            visible(engine, provider, request.caller)
        } catch (_: RuntimeException) {
            responseObserver.onError(Status.INTERNAL.withDescription("alarm query failed").asRuntimeException())
            return
        }
        responseObserver.onNext(AlarmQueryResponse.newBuilder().addAllAlarms(views.map { encodeAlarm(it) }).build())
        responseObserver.onCompleted()
    }

    override fun listShelved(request: AlarmShelvedQuery, responseObserver: StreamObserver<AlarmShelvedList>) {
        val engine = try {
            readableEngine()
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val provider = try {
            providerOrThrow(request.provider)
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val shelves = try {
            val rows = shelves(engine, provider)
            if (!callerEnforced()) rows
            else rows.filter { admits(alarmSourceTagPath(it.source), request.caller, write = false) }
        } catch (_: RuntimeException) {
            responseObserver.onError(Status.INTERNAL.withDescription("alarm shelf query failed").asRuntimeException())
            return
        }
        responseObserver.onNext(AlarmShelvedList.newBuilder().addAllShelves(shelves.map { encodeShelf(it) }).build())
        responseObserver.onCompleted()
    }

    override fun subscribe(request: AlarmSubscribeRequest, responseObserver: StreamObserver<AlarmSnapshot>) {
        val engine = try {
            readableEngine()
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val provider = try {
            providerOrThrow(request.provider)
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val fingerprint = host.fingerprint()
        val caller = request.caller
        val open = AtomicBoolean(true)
        val sendLock = Any()
        val epoch = AtomicLong()
        var watch: AutoCloseable? = null
        fun stop() {
            if (!open.compareAndSet(true, false)) return
            runCatching { watch?.close() }
        }
        fun fail(status: Status) {
            if (!open.compareAndSet(true, false)) return
            runCatching { watch?.close() }
            synchronized(sendLock) {
                runCatching { responseObserver.onError(status.asRuntimeException()) }
            }
        }
        fun send() {
            if (!open.get()) return
            if (host.dropping()) {
                fail(Status.UNAVAILABLE.withDescription("connection dropped"))
                return
            }
            if (!host.access(fingerprint).allowed) {
                fail(Status.PERMISSION_DENIED.withDescription("service denied"))
                return
            }
            val views = try {
                visible(engine, provider, caller)
            } catch (_: RuntimeException) {
                fail(Status.INTERNAL.withDescription("alarm query failed"))
                return
            }
            val message = AlarmSnapshot.newBuilder().addAllAlarms(views.map { encodeAlarm(it) }).build()
            synchronized(sendLock) {
                if (!open.get()) return
                try {
                    responseObserver.onNext(message)
                } catch (_: Exception) {
                    open.set(false)
                    runCatching { watch?.close() }
                }
            }
        }
        fun schedule() {
            val ticket = epoch.incrementAndGet()
            host.schedule {
                if (!open.get() || ticket != epoch.get()) return@schedule
                send()
            }
        }
        watch = engine.onLiveChange { schedule() }
        if (responseObserver is ServerCallStreamObserver<*>) {
            responseObserver.setOnCancelHandler { stop() }
        }
        schedule()
    }

    override fun acknowledge(request: AlarmAckRequest, responseObserver: StreamObserver<AlarmAckResponse>) {
        val ids = try {
            eventIdsOrThrow(request.eventIdsList)
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val user = cleanText(request.user, 200)
        try {
            ensureAck(user, ids)
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val engine = host.engine()
        if (engine == null) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("alarms are not available").asRuntimeException())
            return
        }
        val ackUser = request.caller.user.ifBlank { user }.ifBlank { peerLabel() }
        if (callerEnforced()) {
            val sources = ids.map { engine.sourceOfEvent(it) }
            if (sources.none { it == null } && !canAct(sources.filterNotNull(), request.caller)) {
                responseObserver.onError(Status.PERMISSION_DENIED.withDescription("alarm write denied").asRuntimeException())
                return
            }
        }
        val notes = cleanText(request.notes, 2_000).ifBlank { null }
        try {
            engine.acknowledge(ids, ackUser, notes)
        } catch (error: NoSuchElementException) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(safe(error)).asRuntimeException())
            return
        } catch (error: IllegalArgumentException) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(safe(error)).asRuntimeException())
            return
        } catch (_: RuntimeException) {
            responseObserver.onError(Status.INTERNAL.withDescription("alarm acknowledge failed").asRuntimeException())
            return
        }
        audit(ackUser, ids, true, "acknowledge")
        responseObserver.onNext(AlarmAckResponse.newBuilder().addAllEventIds(ids).build())
        responseObserver.onCompleted()
    }

    override fun shelve(request: AlarmShelveRequest, responseObserver: StreamObserver<AlarmShelveResponse>) {
        val sources = try {
            sourcesOrThrow(request.sourcesList)
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        if (request.durationMillis <= 0 || request.durationMillis > MAX_SHELF_MILLIS) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("shelve duration is invalid").asRuntimeException())
            return
        }
        try {
            writableEngine()
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val engine = host.engine() ?: run {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("alarms are not available").asRuntimeException())
            return
        }
        if (callerEnforced() && !canAct(sources, request.caller)) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription("alarm write denied").asRuntimeException())
            return
        }
        try {
            engine.shelve(sources, Duration.ofMillis(request.durationMillis), request.caller.user.ifBlank { null })
        } catch (error: NoSuchElementException) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(safe(error)).asRuntimeException())
            return
        } catch (error: IllegalArgumentException) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(safe(error)).asRuntimeException())
            return
        } catch (_: RuntimeException) {
            responseObserver.onError(Status.INTERNAL.withDescription("alarm shelve failed").asRuntimeException())
            return
        }
        responseObserver.onNext(AlarmShelveResponse.newBuilder().addAllSources(sources).build())
        responseObserver.onCompleted()
    }

    override fun unshelve(request: AlarmUnshelveRequest, responseObserver: StreamObserver<AlarmUnshelveResponse>) {
        val sources = try {
            sourcesOrThrow(request.sourcesList)
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        try {
            writableEngine()
        } catch (error: StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val engine = host.engine() ?: run {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("alarms are not available").asRuntimeException())
            return
        }
        if (callerEnforced() && !canAct(sources, request.caller)) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription("alarm write denied").asRuntimeException())
            return
        }
        try {
            engine.unshelve(sources, request.caller.user.ifBlank { null })
        } catch (error: NoSuchElementException) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(safe(error)).asRuntimeException())
            return
        } catch (error: IllegalArgumentException) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(safe(error)).asRuntimeException())
            return
        } catch (_: RuntimeException) {
            responseObserver.onError(Status.INTERNAL.withDescription("alarm unshelve failed").asRuntimeException())
            return
        }
        responseObserver.onNext(AlarmUnshelveResponse.newBuilder().addAllSources(sources).build())
        responseObserver.onCompleted()
    }

    private fun readableEngine(): AlarmEngine {
        if (host.dropping()) throw Status.UNAVAILABLE.withDescription("connection dropped").asRuntimeException()
        if (!host.access(host.fingerprint()).allowed) {
            throw Status.PERMISSION_DENIED.withDescription("service denied").asRuntimeException()
        }
        return host.engine() ?: throw Status.UNAVAILABLE.withDescription("alarms are not available").asRuntimeException()
    }

    private fun writableEngine(): AlarmEngine {
        val engine = readableEngine()
        if (!host.access(host.fingerprint()).canWrite) {
            throw Status.PERMISSION_DENIED.withDescription("alarm write denied").asRuntimeException()
        }
        return engine
    }

    private fun ensureAck(user: String, ids: List<String>) {
        if (host.dropping()) throw Status.UNAVAILABLE.withDescription("connection dropped").asRuntimeException()
        val access = host.access(host.fingerprint())
        if (!access.allowed) throw Status.PERMISSION_DENIED.withDescription("service denied").asRuntimeException()
        if (access.canWrite) return
        audit(user, ids, false, "read-only")
        throw Status.PERMISSION_DENIED.withDescription("alarm write denied").asRuntimeException()
    }

    private fun audit(user: String, ids: List<String>, success: Boolean, detail: String) {
        val fingerprint = host.fingerprint()
        val name = host.peerName(fingerprint).ifBlank { fingerprint }
        host.audit(RemoteAlarmAudit(name, fingerprint, user, ids, success, detail))
    }

    private fun peerLabel(): String {
        val fingerprint = host.fingerprint()
        return host.peerName(fingerprint).ifBlank { fingerprint }
    }

    private fun shelves(engine: AlarmEngine, provider: String): List<ShelvedAlarmView> {
        val all = engine.shelved()
        if (provider.isEmpty()) return all
        val prefix = "prov:$provider:"
        return all.filter { it.source.startsWith(prefix) }
    }

    private fun snapshot(engine: AlarmEngine, provider: String): List<AlarmEventView> {
        val all = engine.query()
        if (provider.isEmpty()) return all
        val prefix = "prov:$provider:"
        return all.filter { it.source.startsWith(prefix) }
    }

    private fun visible(engine: AlarmEngine, provider: String, caller: AlarmCaller): List<AlarmEventView> {
        val rows = snapshot(engine, provider)
        if (!callerEnforced()) return rows
        return rows.filter { admits(alarmSourceTagPath(it.source), caller, write = false) }
    }

    private fun callerEnforced(): Boolean = host.protocol(host.fingerprint()) >= PROTOCOL_CALLER

    private fun canAct(sources: List<String>, caller: AlarmCaller): Boolean =
        sources.all { admits(alarmSourceTagPath(it), caller, write = true) }

    private fun admits(path: String?, caller: AlarmCaller, write: Boolean): Boolean {
        if (path == null) return true
        if (!allows(host.readAccess(path), caller)) return false
        if (!write) return true
        return allows(host.writeAccess(path), caller)
    }

    private fun allows(access: TagAccess?, caller: AlarmCaller): Boolean {
        if (access == null) return true
        if (access.namesNobody) return false
        val roles = caller.rolesList.mapTo(HashSet()) { it.lowercase() }
        val roleOk = access.roles.isEmpty() || roles.any { it in access.roles }
        val zoneOk = access.zones.isEmpty() || caller.zone.lowercase() in access.zones
        return roleOk && zoneOk
    }

    private companion object {
        val PROVIDER = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?""")
        const val MAX_SHELF_MILLIS = 366L * 24 * 60 * 60 * 1000

        fun providerOrThrow(provider: String): String {
            if (provider.isEmpty()) return ""
            if (!PROVIDER.matches(provider)) {
                throw Status.INVALID_ARGUMENT.withDescription("alarm provider is invalid").asRuntimeException()
            }
            return provider
        }

        fun eventIdsOrThrow(ids: List<String>): List<String> {
            if (ids.isEmpty() || ids.size > 500) {
                throw Status.INVALID_ARGUMENT.withDescription("event ids are invalid").asRuntimeException()
            }
            val cleaned = ids.map { cleanText(it, 200) }
            if (cleaned.any { it.isEmpty() }) {
                throw Status.INVALID_ARGUMENT.withDescription("event ids are invalid").asRuntimeException()
            }
            return cleaned.distinct()
        }

        fun sourcesOrThrow(sources: List<String>): List<String> {
            if (sources.isEmpty() || sources.size > 500) {
                throw Status.INVALID_ARGUMENT.withDescription("alarm sources are invalid").asRuntimeException()
            }
            val cleaned = sources.map { cleanText(it, 1024) }
            if (cleaned.any { it.isEmpty() }) {
                throw Status.INVALID_ARGUMENT.withDescription("alarm source is invalid").asRuntimeException()
            }
            return cleaned.distinct()
        }

        fun cleanText(raw: String, limit: Int): String =
            raw.replace(Regex("[\\r\\n\\u0000]"), " ").trim().take(limit)

        fun safe(error: Exception): String =
            (error.message ?: "alarm request rejected").replace(Regex("[\\r\\n]"), " ").take(200)
    }
}

/**
 * One remote tag provider's alarm feed. The subscription is reopened when the link comes back,
 * and the first snapshot on that stream is the peer's current live set.
 */
internal class RemoteAlarmSession(
    val localName: String,
    private val remoteProvider: String,
    private val timeoutMillis: Long,
) {
    private val cache = AtomicReference<List<AlarmEventView>>(emptyList())
    private val listeners = CopyOnWriteArrayList<(List<AlarmEventView>) -> Unit>()
    private val generation = AtomicInteger()
    @Volatile var phase: String = "idle"
        private set
    @Volatile private var asyncStub: RemoteAlarmsGrpc.RemoteAlarmsStub? = null
    @Volatile private var blocking: RemoteAlarmsGrpc.RemoteAlarmsBlockingStub? = null
    @Volatile private var context: Context.CancellableContext? = null

    fun connected(): Boolean = blocking != null

    fun attach(asyncStub: RemoteAlarmsGrpc.RemoteAlarmsStub, blocking: RemoteAlarmsGrpc.RemoteAlarmsBlockingStub) {
        this.asyncStub = asyncStub
        this.blocking = blocking
        open()
    }

    fun detach() {
        asyncStub = null
        blocking = null
        cancel()
        if (phase != "denied") phase = "down"
    }

    fun snapshot(): List<AlarmEventView> = cache.get()

    fun watch(listener: (List<AlarmEventView>) -> Unit): AutoCloseable {
        listeners.add(listener)
        if (phase == "live") listener(cache.get())
        return AutoCloseable { listeners.remove(listener) }
    }

    fun pull(caller: RemoteCaller = RemoteCaller()): List<AlarmEventView> {
        val stub = blocking ?: return emptyList()
        return try {
            val response = stub.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS).query(
                AlarmQueryRequest.newBuilder().setProvider(remoteProvider).setCaller(caller.toProto()).build(),
            )
            val views = response.alarmsList.mapNotNull { decodeAlarm(it, localName, remoteProvider) }
            publish(views, "live")
            views
        } catch (error: StatusRuntimeException) {
            if (error.status.code == Status.Code.PERMISSION_DENIED) publish(emptyList(), "denied")
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Shelves on this peer. An older peer that has no shelved rpc contributes nothing. */
    fun pullShelved(caller: RemoteCaller = RemoteCaller()): List<ShelvedAlarmView> {
        val stub = blocking ?: return emptyList()
        return try {
            val response = stub.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS).listShelved(
                AlarmShelvedQuery.newBuilder().setProvider(remoteProvider).setCaller(caller.toProto()).build(),
            )
            response.shelvesList.mapNotNull { decodeShelf(it, localName, remoteProvider) }
        } catch (_: StatusRuntimeException) {
            // UNIMPLEMENTED is an older peer. PERMISSION_DENIED is a policy or caller refusal.
            // Either way this peer adds no shelves, and the requester still returns its own.
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun acknowledge(remoteIds: List<String>, user: String, notes: String?, caller: RemoteCaller = RemoteCaller(user = user)) {
        val stub = blocking ?: throw IllegalStateException("remote alarm link is down")
        val sent = if (caller.user.isBlank()) caller.copy(user = user) else caller
        val request = AlarmAckRequest.newBuilder()
            .addAllEventIds(remoteIds)
            .setUser(sent.user)
            .setNotes(notes ?: "")
            .setCaller(sent.toProto())
            .build()
        try {
            stub.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS).acknowledge(request)
        } catch (error: StatusRuntimeException) {
            fail(error)
        }
    }

    fun shelve(remoteSources: List<String>, duration: Duration, caller: RemoteCaller = RemoteCaller()) {
        val stub = blocking ?: throw IllegalStateException("remote alarm link is down")
        val request = AlarmShelveRequest.newBuilder()
            .addAllSources(remoteSources)
            .setDurationMillis(duration.toMillis())
            .setCaller(caller.toProto())
            .build()
        try {
            stub.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS).shelve(request)
        } catch (error: StatusRuntimeException) {
            fail(error)
        }
    }

    fun unshelve(remoteSources: List<String>, caller: RemoteCaller = RemoteCaller()) {
        val stub = blocking ?: throw IllegalStateException("remote alarm link is down")
        val request = AlarmUnshelveRequest.newBuilder().addAllSources(remoteSources).setCaller(caller.toProto()).build()
        try {
            stub.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS).unshelve(request)
        } catch (error: StatusRuntimeException) {
            fail(error)
        }
    }

    fun toRemoteSource(localSource: String): String {
        val prefix = "prov:$localName:"
        if (!localSource.startsWith(prefix)) return localSource
        return "prov:$remoteProvider:" + localSource.removePrefix(prefix)
    }

    private fun open() {
        val stub = asyncStub ?: return
        synchronized(this) {
            cancel()
            val token = generation.incrementAndGet()
            val call = Context.ROOT.withCancellation()
            context = call
            val request = AlarmSubscribeRequest.newBuilder().setProvider(remoteProvider).build()
            call.run {
                stub.subscribe(request, object : StreamObserver<AlarmSnapshot> {
                    override fun onNext(value: AlarmSnapshot) {
                        if (token != generation.get()) return
                        val views = value.alarmsList.mapNotNull { decodeAlarm(it, localName, remoteProvider) }
                        publish(views, "live")
                    }

                    override fun onError(t: Throwable) {
                        if (token != generation.get()) return
                        if (statusCode(t) == Status.Code.PERMISSION_DENIED) publish(emptyList(), "denied")
                    }

                    override fun onCompleted() = Unit
                })
            }
        }
    }

    private fun cancel() {
        generation.incrementAndGet()
        context?.cancel(null)
        context = null
    }

    private fun publish(views: List<AlarmEventView>, next: String) {
        cache.set(views)
        phase = next
        val copy = views.toList()
        for (listener in listeners) {
            try {
                listener(copy)
            } catch (_: Exception) {
                // A local listener must not stop the stream.
            }
        }
    }

    private fun fail(error: StatusRuntimeException): Nothing {
        when (error.status.code) {
            Status.Code.PERMISSION_DENIED -> throw AccessDeniedException(error.status.description ?: "alarm write denied")
            Status.Code.NOT_FOUND -> throw NoSuchElementException(error.status.description ?: "alarm event not found")
            Status.Code.INVALID_ARGUMENT -> throw IllegalArgumentException(error.status.description ?: "alarm request rejected")
            Status.Code.UNAVAILABLE, Status.Code.UNAUTHENTICATED -> throw IllegalStateException("remote alarm link is down")
            else -> throw IllegalStateException(error.status.description ?: "remote alarm request failed")
        }
    }
}

internal fun RemoteCaller.toProto(): AlarmCaller = AlarmCaller.newBuilder()
    .setUser(user)
    .addAllRoles(roles)
    .setZone(zone)
    .build()

internal fun ShelvedAlarmView.matchesShelf(filter: AlarmFilter): Boolean {
    if (filter.active != null && (event?.active ?: conditionActive) != filter.active) return false
    val minimum = filter.minPriority
    if (minimum != null && priority < minimum) return false
    if (!wildcardMatch(filter.source, source)) return false
    if (!wildcardMatch(filter.displayPath, displayPath)) return false
    return true
}

internal fun AlarmEventView.matches(filter: AlarmFilter): Boolean {
    if (filter.active != null && active != filter.active) return false
    val minimum = filter.minPriority
    if (minimum != null && priorityCode < minimum.code) return false
    if (!wildcardMatch(filter.source, source)) return false
    if (!wildcardMatch(filter.displayPath, displayPath)) return false
    return true
}

private fun encodeAlarm(event: AlarmEventView): AlarmView {
    val builder = AlarmView.newBuilder()
        .setEventId(event.eventId)
        .setSource(event.source)
        .setName(event.name)
        .setDisplayPath(event.displayPath)
        .setTagPath(event.tagPath)
        .setPriority(event.priority)
        .setPriorityCode(event.priorityCode)
        .setState(event.state)
        .setStateCode(event.stateCode)
        .setActive(event.active)
        .setAcknowledged(event.acknowledged)
        .setActiveTimeUnixMillis(event.activeTime.toEpochMilli())
        .setAckUser(event.ackUser ?: "")
        .setAckNotes(event.ackNotes ?: "")
        .setNotes(event.notes ?: "")
    event.clearTime?.let {
        builder.setClearTimeSet(true).setClearTimeUnixMillis(it.toEpochMilli())
    }
    event.ackTime?.let {
        builder.setAckTimeSet(true).setAckTimeUnixMillis(it.toEpochMilli())
    }
    writeJson(event.value)?.let { builder.setValuePresent(true).setValueJson(it) }
    writeJson(event.setpointA)?.let { builder.setSetpointAPresent(true).setSetpointAJson(it) }
    writeJson(event.setpointB)?.let { builder.setSetpointBPresent(true).setSetpointBJson(it) }
    return builder.build()
}

private fun encodeShelf(shelf: ShelvedAlarmView): ShelvedAlarm {
    val builder = ShelvedAlarm.newBuilder()
        .setSource(shelf.source)
        .setName(shelf.name)
        .setDisplayPath(shelf.displayPath)
        .setTagPath(shelf.tagPath)
        .setPriority(shelf.priority.label())
        .setPriorityCode(shelf.priority.code)
        .setShelvedUntilUnixMillis(shelf.shelvedUntil.toEpochMilli())
        .setShelvedBy(shelf.shelvedBy ?: "")
        .setConditionActive(shelf.conditionActive)
    shelf.shelvedAt?.let { builder.setShelvedAtSet(true).setShelvedAtUnixMillis(it.toEpochMilli()) }
    shelf.event?.let { builder.setEventSet(true).setEvent(encodeAlarm(it)) }
    return builder.build()
}

private fun decodeShelf(message: ShelvedAlarm, localName: String, remoteProvider: String): ShelvedAlarmView? {
    val prefix = "prov:$remoteProvider:"
    if (!message.source.startsWith(prefix)) return null
    val priority = AlarmPriority.entries.getOrNull(message.priorityCode)
        ?: AlarmPriority.fromName(message.priority)
        ?: return null
    val event = if (message.eventSet) decodeAlarm(message.event, localName, remoteProvider) else null
    return ShelvedAlarmView(
        source = "prov:$localName:" + message.source.removePrefix(prefix),
        name = message.name,
        displayPath = message.displayPath,
        tagPath = message.tagPath,
        priority = priority,
        shelvedAt = if (message.shelvedAtSet) Instant.ofEpochMilli(message.shelvedAtUnixMillis) else null,
        shelvedUntil = Instant.ofEpochMilli(message.shelvedUntilUnixMillis),
        shelvedBy = message.shelvedBy.ifBlank { null },
        conditionActive = message.conditionActive,
        event = event,
    )
}

private fun decodeAlarm(view: AlarmView, localName: String, remoteProvider: String): AlarmEventView? {
    val prefix = "prov:$remoteProvider:"
    if (!view.source.startsWith(prefix)) return null
    val source = "prov:$localName:" + view.source.removePrefix(prefix)
    return AlarmEventView(
        eventId = "$localName:${view.eventId}",
        source = source,
        name = view.name,
        displayPath = view.displayPath,
        tagPath = view.tagPath,
        priority = view.priority,
        priorityCode = view.priorityCode,
        state = view.state,
        stateCode = view.stateCode,
        active = view.active,
        acknowledged = view.acknowledged,
        value = if (view.valuePresent) readJson(view.valueJson) else null,
        activeTime = Instant.ofEpochMilli(view.activeTimeUnixMillis),
        clearTime = if (view.clearTimeSet) Instant.ofEpochMilli(view.clearTimeUnixMillis) else null,
        ackTime = if (view.ackTimeSet) Instant.ofEpochMilli(view.ackTimeUnixMillis) else null,
        ackUser = view.ackUser.ifBlank { null },
        ackNotes = view.ackNotes.ifBlank { null },
        setpointA = if (view.setpointAPresent) readJson(view.setpointAJson) else null,
        setpointB = if (view.setpointBPresent) readJson(view.setpointBJson) else null,
        notes = view.notes.ifBlank { null },
    )
}

private fun writeJson(value: Any?): String? {
    if (value == null) return null
    return try {
        Json.mapper.writeValueAsString(value)
    } catch (_: Exception) {
        null
    }
}

private fun readJson(text: String): Any? = try {
    if (text.isEmpty()) null else Json.mapper.readValue(text, Any::class.java)
} catch (_: Exception) {
    null
}

private fun statusCode(error: Throwable): Status.Code? {
    var current: Throwable? = error
    var guard = 0
    while (current != null && guard++ < 8) {
        when (current) {
            is StatusRuntimeException -> return current.status.code
            is StatusException -> return current.status.code
        }
        current = current.cause
    }
    return null
}
