package org.oip.edgesync

import io.grpc.BindableService
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import org.oip.alarm.AlarmJournal
import org.oip.config.FileConfigStore
import org.oip.gateway.Json
import org.oip.network.GatewayNetwork
import org.oip.network.SERVICE_ALARM_JOURNAL
import org.oip.network.SERVICE_AUDIT
import org.oip.network.SERVICE_HISTORY
import org.oip.network.v1.EdgeAlarmBatch
import org.oip.network.v1.EdgeAlarmRecord
import org.oip.network.v1.EdgeAuditBatch
import org.oip.network.v1.EdgeAuditRecord
import org.oip.network.v1.EdgeHistoryBatch
import org.oip.network.v1.EdgeHistoryPoint
import org.oip.network.v1.EdgeSyncAck
import org.oip.network.v1.EdgeSyncGrpc
import org.oip.security.AuditLog
import org.oip.security.AuditRecord
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TagChange
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards an edge gateway's local history, audit records, and alarm journal to a hub
 * over the mutual-TLS peer network. Disk and network work run on this service's thread.
 * A tag listener only queues the sample.
 *
 */
class EdgeSyncRuntime(
    val settings: EdgeSyncSettings,
    private val network: GatewayNetwork,
    private val tags: TagEngine,
    private val config: FileConfigStore,
    private val audit: AuditLog,
    private val journal: AlarmJournal,
    file: java.nio.file.Path,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(EdgeSyncRuntime::class.java)
    private val db = EdgeSyncDatabase(
        file,
        settings.history.retentionDays,
        settings.history.maxPoints,
    )
    private val queue = ConcurrentLinkedQueue<PendingSample>()
    private val subscriptions = LinkedHashMap<String, AutoCloseable>()
    private val refresh = AtomicBoolean(settings.history.enabled)
    private val pumpLock = Any()
    private val wake = Object()
    private var thread: Thread? = null
    private var configSub: AutoCloseable? = null
    @Volatile private var started = false
    @Volatile private var stop = false
    @Volatile private var closed = false
    @Volatile private var historyError: String? = null
    @Volatile private var auditError: String? = null
    @Volatile private var alarmError: String? = null
    @Volatile private var historyForwarded = 0L
    @Volatile private var auditForwarded = 0L
    @Volatile private var alarmForwarded = 0L

    /** Test seam. Runs on the sync thread, before the sample is written. Must not call back into sync. */
    @Volatile internal var beforePersist: (() -> Unit)? = null

    /** When false, only [syncOnce] sends. The background loop still persists queued samples. */
    @Volatile internal var automatic: Boolean = true

    val service: BindableService = Service()

    fun start() {
        if (started) return
        started = true
        if (settings.history.captureAll) {
            configSub = config.subscribe { event ->
                val id = event.id ?: return@subscribe
                if (id.module == TAGS_MODULE && id.kind == TAG_KIND) {
                    refresh.set(true)
                    poke()
                }
            }
        }
        if (!forwarding()) return
        val worker = Thread({
            runCatching { loop() }.onFailure { log.warn("edge sync loop stopped", it) }
        }, "edge-sync")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    fun onLink(name: String, up: Boolean) {
        if (name != settings.remoteGateway || !up) return
        synchronized(pumpLock) {
            historyDue = 0
            auditDue = 0
            alarmDue = 0
        }
        poke()
    }

    /** One pass: persist queued samples, then send at most one batch per enabled stream. */
    fun syncOnce(): Map<String, Any?> {
        pump(force = true)
        return statusDocument()
    }

    fun statusDocument(): Map<String, Any?> = synchronized(pumpLock) {
        val now = clock.millis()
        linkedMapOf(
            "remoteGateway" to settings.remoteGateway,
            "linkUp" to linkUp(),
            "history" to streamStatus(
                settings.history.enabled,
                settings.history.remoteProvider,
                settings.history.open(now),
                db.pendingHistory(),
                historyForwarded,
                historyError,
            ),
            "audit" to streamStatus(
                settings.audit.enabled,
                settings.audit.remoteName,
                settings.audit.open(now),
                pendingAfter(audit.latest()?.seq ?: 0L, db.cursor(CURSOR_AUDIT)),
                auditForwarded,
                auditError,
            ),
            "alarmJournal" to streamStatus(
                settings.alarmJournal.enabled,
                settings.alarmJournal.remoteName,
                settings.alarmJournal.open(now),
                // A journal on a faulted connection has no readable id; its fault is the stream's last error.
                pendingAfter(runCatching { journal.maxId() }.getOrDefault(0L), db.cursor(CURSOR_ALARM)),
                alarmForwarded,
                alarmError,
            ),
            "subscriptions" to subscriptions.size,
        )
    }

    fun historyDocument(provider: String?, source: String?, limit: Int): Map<String, Any?> {
        val cap = limit.coerceIn(1, 1000)
        return linkedMapOf(
            "local" to db.localHistory(provider, cap).map { row ->
                linkedMapOf(
                    "id" to row.id,
                    "provider" to row.provider,
                    "path" to row.path,
                    "value" to decode(row.valueJson),
                    "quality" to row.quality,
                    "timestamp" to instantOfMicros(row.timestampMicros).toString(),
                    "forwarded" to row.forwarded,
                )
            },
            "remote" to db.remoteHistory(provider, source, cap).map { row ->
                linkedMapOf(
                    "sourceGateway" to row.sourceGateway,
                    "provider" to row.remoteProvider,
                    "localId" to row.localId,
                    "path" to row.path,
                    "value" to decode(row.valueJson),
                    "quality" to row.quality,
                    "timestamp" to instantOfMicros(row.timestampMicros).toString(),
                )
            },
        )
    }

    fun auditDocument(profile: String?, source: String?, limit: Int): Map<String, Any?> {
        val cap = limit.coerceIn(1, 1000)
        return linkedMapOf(
            "remote" to db.remoteAudit(profile, source, cap).map { row ->
                linkedMapOf(
                    "sourceGateway" to row.sourceGateway,
                    "profile" to row.remoteProfile,
                    "seq" to row.seq,
                    "timestamp" to instantMillis(row.timestampMillis),
                    "actor" to row.actor,
                    "action" to row.action,
                    "target" to row.target,
                    "detail" to row.detail,
                    "status" to row.status,
                    "origin" to row.origin,
                )
            },
        )
    }

    fun alarmDocument(journalName: String?, source: String?, limit: Int): Map<String, Any?> {
        val cap = limit.coerceIn(1, 1000)
        return linkedMapOf(
            "remote" to db.remoteAlarms(journalName, source, cap).map { row ->
                linkedMapOf(
                    "sourceGateway" to row.sourceGateway,
                    "journal" to row.remoteJournal,
                    "localId" to row.localId,
                    "eventId" to row.eventId,
                    "source" to row.source,
                    "displayPath" to row.displayPath,
                    "priority" to row.priority,
                    "eventType" to row.eventType,
                    "eventTime" to instantMillis(row.eventTimeMillis),
                    "data" to decode(row.dataJson),
                )
            },
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        stop = true
        poke()
        thread?.join(3_000)
        thread = null
        runCatching { configSub?.close() }
        configSub = null
        synchronized(pumpLock) { closeSubs() }
        db.close()
    }

    private fun forwarding(): Boolean =
        settings.history.enabled || settings.audit.enabled || settings.alarmJournal.enabled

    private fun loop() {
        while (!stop) {
            val wait = runCatching { pump(force = false) }.getOrElse {
                log.warn("edge sync pass failed", it)
                1_000L
            }
            if (stop) return
            synchronized(wake) {
                if (!stop) wake.wait(wait)
            }
        }
    }

    private fun pump(force: Boolean): Long = synchronized(pumpLock) {
        if (stop && !force) return 1_000
        if (refresh.getAndSet(false)) refreshSubscriptions()
        drain()
        db.pruneHistory(timestampMicros(clock.instant()))
        val now = clock.millis()
        if (settings.history.enabled && (force || (automatic && due(historyDue, now)))) {
            historyDue = now + settings.history.frequencyMillis()
            sendHistory(now)
        }
        if (settings.audit.enabled && (force || (automatic && due(auditDue, now)))) {
            auditDue = now + settings.audit.frequencyMillis()
            sendAudit(now)
        }
        if (settings.alarmJournal.enabled && (force || (automatic && due(alarmDue, now)))) {
            alarmDue = now + settings.alarmJournal.frequencyMillis()
            sendAlarms(now)
        }
        nextWait(now)
    }

    private var historyDue = 0L
    private var auditDue = 0L
    private var alarmDue = 0L

    private fun due(at: Long, now: Long): Boolean = now >= at

    private fun nextWait(now: Long): Long {
        var soonest = Long.MAX_VALUE
        if (settings.history.enabled) soonest = minOf(soonest, historyDue)
        if (settings.audit.enabled) soonest = minOf(soonest, auditDue)
        if (settings.alarmJournal.enabled) soonest = minOf(soonest, alarmDue)
        if (soonest == Long.MAX_VALUE) return 1_000
        return (soonest - now).coerceIn(20, 1_000)
    }

    private fun sendHistory(now: Long) {
        val spec = settings.history
        if (!spec.open(now)) return
        if (settings.remoteGateway.isBlank() || spec.remoteProvider.isBlank()) {
            historyError = "remote history target is not set"
            return
        }
        val rows = db.nextHistory(spec.maxBatchSize)
        if (rows.isEmpty()) return
        val channel = network.outgoingChannel(settings.remoteGateway)
        if (channel == null) {
            historyError = "link down"
            return
        }
        try {
            val batch = EdgeHistoryBatch.newBuilder().setRemoteProvider(spec.remoteProvider)
            for (row in rows) {
                batch.addPoints(
                    EdgeHistoryPoint.newBuilder()
                        .setLocalId(row.id)
                        .setProvider(row.provider)
                        .setPath(row.path)
                        .setValueJson(row.valueJson)
                        .setQuality(row.quality)
                        .setTimestampUnixMicros(row.timestampMicros)
                        .build(),
                )
            }
            val ack = EdgeSyncGrpc.newBlockingStub(channel)
                .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                .pushHistory(batch.build())
            if (ack.accepted == rows.size && ack.throughId == rows.last().id) {
                db.markForwarded(rows.map { it.id })
                historyForwarded += rows.size
                historyError = null
            } else {
                historyError = "hub accepted ${ack.accepted} of ${rows.size}"
            }
        } catch (error: StatusRuntimeException) {
            historyError = error.status.code.name
            log.debug("edge sync history push failed", error)
        }
    }

    private fun sendAudit(now: Long) {
        val spec = settings.audit
        if (!spec.open(now)) return
        if (settings.remoteGateway.isBlank() || spec.remoteName.isBlank()) {
            auditError = "remote audit target is not set"
            return
        }
        val latest = audit.latest()?.seq ?: 0L
        var cursor = db.cursor(CURSOR_AUDIT)
        if (latest < cursor) {
            db.setCursor(CURSOR_AUDIT, 0)
            cursor = 0
        }
        val rows = audit.recordsAfter(cursor, RECORD_BATCH)
        if (rows.isEmpty()) return
        val cutoff = now - spec.retentionDays.toLong() * 86_400_000L
        var index = 0
        while (index < rows.size && rows[index].timestampMillis < cutoff) index++
        if (index > 0) db.setCursor(CURSOR_AUDIT, rows[index - 1].seq)
        if (index >= rows.size) return
        val send = rows.subList(index, rows.size)
        val channel = network.outgoingChannel(settings.remoteGateway)
        if (channel == null) {
            auditError = "link down"
            return
        }
        try {
            val batch = EdgeAuditBatch.newBuilder().setRemoteProfile(spec.remoteName)
            for (row in send) batch.addRecords(auditProto(row))
            val ack = EdgeSyncGrpc.newBlockingStub(channel)
                .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                .pushAudit(batch.build())
            if (ack.accepted == send.size && ack.throughId == send.last().seq) {
                db.setCursor(CURSOR_AUDIT, send.last().seq)
                auditForwarded += send.size
                auditError = null
            } else {
                auditError = "hub accepted ${ack.accepted} of ${send.size}"
            }
        } catch (error: StatusRuntimeException) {
            auditError = error.status.code.name
            log.debug("edge sync audit push failed", error)
        }
    }

    private fun sendAlarms(now: Long) {
        val spec = settings.alarmJournal
        if (!spec.open(now)) return
        if (settings.remoteGateway.isBlank() || spec.remoteName.isBlank()) {
            alarmError = "remote alarm journal target is not set"
            return
        }
        val rows = try {
            val latest = journal.maxId()
            var cursor = db.cursor(CURSOR_ALARM)
            if (latest < cursor) {
                db.setCursor(CURSOR_ALARM, 0)
                cursor = 0
            }
            journal.recordsAfter(cursor, RECORD_BATCH)
        } catch (error: org.oip.alarm.JournalUnavailable) {
            // The journal's named connection is missing or broken (DAT-015): nothing is read, and the cursor stays.
            alarmError = error.message
            return
        }
        if (rows.isEmpty()) return
        val cutoff = now - spec.retentionDays.toLong() * 86_400_000L
        var index = 0
        while (index < rows.size && rows[index].eventTimeMillis < cutoff) index++
        if (index > 0) db.setCursor(CURSOR_ALARM, rows[index - 1].id)
        if (index >= rows.size) return
        val send = rows.subList(index, rows.size)
        val channel = network.outgoingChannel(settings.remoteGateway)
        if (channel == null) {
            alarmError = "link down"
            return
        }
        try {
            val batch = EdgeAlarmBatch.newBuilder().setRemoteJournal(spec.remoteName)
            for (row in send) {
                batch.addRecords(
                    EdgeAlarmRecord.newBuilder()
                        .setLocalId(row.id)
                        .setEventId(row.eventId)
                        .setSource(row.source)
                        .setDisplayPath(row.displayPath)
                        .setPriority(row.priority)
                        .setEventType(row.eventType)
                        .setEventFlags(row.eventFlags)
                        .setEventTimeUnixMillis(row.eventTimeMillis)
                        .setDataJson(Json.write(row.data))
                        .build(),
                )
            }
            val ack = EdgeSyncGrpc.newBlockingStub(channel)
                .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                .pushAlarms(batch.build())
            if (ack.accepted == send.size && ack.throughId == send.last().id) {
                db.setCursor(CURSOR_ALARM, send.last().id)
                alarmForwarded += send.size
                alarmError = null
            } else {
                alarmError = "hub accepted ${ack.accepted} of ${send.size}"
            }
        } catch (error: StatusRuntimeException) {
            alarmError = error.status.code.name
            log.debug("edge sync alarm push failed", error)
        }
    }

    private fun drain() {
        val batch = ArrayList<PendingSample>(64)
        while (batch.size < 1_000) {
            val next = queue.poll() ?: break
            batch += next
        }
        if (batch.isEmpty()) return
        val encoded = ArrayList<NewHistory>(batch.size)
        for (sample in batch) {
            val json = try {
                Json.write(sample.value)
            } catch (error: RuntimeException) {
                log.warn("edge sync dropped a sample that could not be encoded")
                continue
            }
            encoded += NewHistory(sample.provider, sample.path, json, sample.quality, sample.timestampMicros)
        }
        if (encoded.isEmpty()) return
        beforePersist?.invoke()
        db.insertHistory(encoded)
    }

    private fun refreshSubscriptions() {
        if (!settings.history.enabled) {
            closeSubs()
            return
        }
        val wanted = LinkedHashSet<String>()
        wanted.addAll(settings.history.tagPaths)
        if (settings.history.captureAll) {
            for ((provider, config) in tags.copyTagConfigs()) {
                if (provider.equals("system", true) || config.provider.equals("system", true)) continue
                if (config.type == TagType.FOLDER || config.type == TagType.UDT) continue
                if (config.path.isEmpty()) continue
                wanted += "[${config.provider}]${config.path}"
            }
        }
        val stale = subscriptions.keys.filter { it !in wanted }
        for (path in stale) subscriptions.remove(path)?.close()
        for (path in wanted) {
            if (path in subscriptions) continue
            val sub = try {
                tags.subscribe(path) { change -> enqueue(change) }
            } catch (error: RuntimeException) {
                log.debug("edge sync did not subscribe to {}", path)
                continue
            }
            subscriptions[path] = sub
        }
    }

    private fun enqueue(change: TagChange) {
        val path = change.path
        val provider = path.provider ?: "default"
        if (provider.equals("system", true)) return
        val joined = path.segments.joinToString("/")
        if (joined.isEmpty()) return
        val value = change.value
        queue.offer(
            PendingSample(
                provider,
                joined,
                value.value,
                value.quality.code.label,
                timestampMicros(value.timestamp),
            ),
        )
        poke()
    }

    private fun closeSubs() {
        val open = subscriptions.values.toList()
        subscriptions.clear()
        open.forEach { runCatching { it.close() } }
    }

    private fun linkUp(): Boolean {
        if (settings.remoteGateway.isEmpty()) return false
        return network.outgoingChannel(settings.remoteGateway) != null
    }

    private fun poke() {
        synchronized(wake) { wake.notifyAll() }
    }

    private inner class Service : EdgeSyncGrpc.EdgeSyncImplBase() {
        override fun pushHistory(request: EdgeHistoryBatch, observer: StreamObserver<EdgeSyncAck>) {
            val source = authorize(observer, SERVICE_HISTORY) ?: return
            if (!validRemoteName(request.remoteProvider)) {
                observer.onError(Status.INVALID_ARGUMENT.withDescription("remote provider is invalid").asRuntimeException())
                return
            }
            try {
                val points = request.pointsList.map { point ->
                    RemoteHistory(
                        sourceGateway = source,
                        remoteProvider = request.remoteProvider,
                        localId = point.localId,
                        provider = point.provider,
                        path = point.path,
                        valueJson = point.valueJson,
                        quality = point.quality,
                        timestampMicros = point.timestampUnixMicros,
                    )
                }
                val through = db.acceptHistory(source, request.remoteProvider, points)
                ack(observer, points.size, through)
            } catch (error: RuntimeException) {
                log.warn("edge sync history store failed", error)
                observer.onError(Status.INTERNAL.withDescription("history store failed").asRuntimeException())
            }
        }

        override fun pushAudit(request: EdgeAuditBatch, observer: StreamObserver<EdgeSyncAck>) {
            val source = authorize(observer, SERVICE_AUDIT) ?: return
            if (!validRemoteName(request.remoteProfile)) {
                observer.onError(Status.INVALID_ARGUMENT.withDescription("remote profile is invalid").asRuntimeException())
                return
            }
            try {
                val rows = request.recordsList.map { record ->
                    RemoteAudit(
                        source, request.remoteProfile, record.seq, record.timestampUnixMillis, record.actor,
                        record.actorHost, record.action, record.target, record.detail, record.status,
                        record.origin, record.context,
                    )
                }
                val through = db.acceptAudit(rows)
                ack(observer, rows.size, through)
            } catch (error: RuntimeException) {
                log.warn("edge sync audit store failed", error)
                observer.onError(Status.INTERNAL.withDescription("audit store failed").asRuntimeException())
            }
        }

        override fun pushAlarms(request: EdgeAlarmBatch, observer: StreamObserver<EdgeSyncAck>) {
            val source = authorize(observer, SERVICE_ALARM_JOURNAL) ?: return
            if (!validRemoteName(request.remoteJournal)) {
                observer.onError(Status.INVALID_ARGUMENT.withDescription("remote journal is invalid").asRuntimeException())
                return
            }
            try {
                val rows = request.recordsList.map { record ->
                    RemoteAlarm(
                        source, request.remoteJournal, record.localId, record.eventId, record.source,
                        record.displayPath, record.priority, record.eventType, record.eventFlags,
                        record.eventTimeUnixMillis, record.dataJson,
                    )
                }
                val through = db.acceptAlarms(rows)
                ack(observer, rows.size, through)
            } catch (error: RuntimeException) {
                log.warn("edge sync alarm store failed", error)
                observer.onError(Status.INTERNAL.withDescription("alarm store failed").asRuntimeException())
            }
        }

        private fun authorize(observer: StreamObserver<*>, service: String): String? = try {
            network.requireStorage(service)
        } catch (error: Exception) {
            observer.onError(error)
            null
        }
    }

    private data class PendingSample(
        val provider: String,
        val path: String,
        val value: Any?,
        val quality: String,
        val timestampMicros: Long,
    )
}

private const val CURSOR_AUDIT = "audit"
private const val CURSOR_ALARM = "alarm"
private const val RPC_DEADLINE_SECONDS = 15L

private fun ack(observer: StreamObserver<EdgeSyncAck>, accepted: Int, through: Long) {
    observer.onNext(EdgeSyncAck.newBuilder().setAccepted(accepted).setThroughId(through).build())
    observer.onCompleted()
}

private fun auditProto(row: AuditRecord): EdgeAuditRecord = EdgeAuditRecord.newBuilder()
    .setSeq(row.seq)
    .setTimestampUnixMillis(row.timestampMillis)
    .setActor(row.actor)
    .setActorHost(row.actorHost)
    .setAction(row.action)
    .setTarget(row.target)
    .setDetail(row.detail)
    .setStatus(row.status.name)
    .setOrigin(row.origin)
    .setContext(row.context)
    .build()

private fun streamStatus(
    enabled: Boolean,
    remoteName: String,
    scheduleOpen: Boolean,
    pending: Int,
    forwarded: Long,
    error: String?,
): Map<String, Any?> = linkedMapOf(
    "enabled" to enabled,
    "remoteName" to remoteName,
    "scheduleOpen" to scheduleOpen,
    "pending" to pending,
    "forwarded" to forwarded,
    "lastError" to error,
)

private fun pendingAfter(latest: Long, cursor: Long): Int =
    (latest - cursor).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun decode(json: String): Any? = runCatching { Json.mapper.readValue(json, Any::class.java) }.getOrDefault(json)

private fun instantMillis(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()
