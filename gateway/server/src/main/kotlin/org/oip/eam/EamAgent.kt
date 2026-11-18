package org.oip.eam

import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import org.oip.gateway.Json
import org.oip.network.v1.EamCheckIn
import org.oip.network.v1.EamCheckInReply
import org.oip.network.v1.EamChunk
import org.oip.network.v1.EamDownloadRequest
import org.oip.network.v1.EamTaskOrder
import org.oip.network.v1.EamTaskReport
import org.oip.network.v1.EamUploadAck
import org.oip.network.v1.EnterpriseAdminGrpc
import org.oip.tags.DataType
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The agent half of enterprise administration. It checks in to the controller over the outgoing gateway-network
 * connection named in [EamSettings.controller], and to [EamSettings.backupController] when the first does not answer
 * (a redundant controller, EAM-012). Each check-in carries this gateway's inventory and health. The reply carries
 * task runs; each one runs on this thread, and its result is reported to the controller that sent it.
 * Doc source: FEATURES EAM rows (EAM-001 to EAM-017).
 */
class EamAgentClient(private val host: EamHost) {
    @Volatile var state: String = "disconnected"
        private set
    @Volatile var activeConnection: String = ""
        private set
    @Volatile var controllerName: String = ""
        private set
    @Volatile var lastCheckInMillis: Long = 0
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var nextCheckInMillis: Long = EamSettings.DEFAULT_CHECK_IN_MILLIS
        private set
    private val recent = ArrayDeque<Map<String, Any?>>()

    fun statusDocument(): Map<String, Any?> = linkedMapOf(
        "state" to state,
        "connection" to activeConnection.ifEmpty { null },
        "controllerName" to controllerName.ifEmpty { null },
        "lastCheckInMillis" to lastCheckInMillis.takeIf { it > 0 },
        "lastError" to lastError.ifEmpty { null },
        "recentRuns" to synchronized(recent) { recent.toList().reversed() },
    )

    /** One check-in and the runs it hands out. Returns the controller's state, or null when no controller answered. */
    fun checkInOnce(): String? {
        val settings = host.settings()
        if (settings.role != EamRole.AGENT) return null
        val candidates = listOf(settings.controller, settings.backupController).filter { it.isNotBlank() }
        var errors = ArrayList<String>()
        for (connection in candidates) {
            val channel = host.network.outgoingChannel(connection)
            if (channel == null) {
                errors += "$connection: not connected"
                continue
            }
            val reply = try {
                EnterpriseAdminGrpc.newBlockingStub(channel).withDeadlineAfter(CALL_SECONDS, TimeUnit.SECONDS).checkIn(checkInRequest(settings))
            } catch (e: StatusRuntimeException) {
                errors += "$connection: ${e.status.code}${e.status.description?.let { " $it" } ?: ""}"
                continue
            }
            activeConnection = connection
            controllerName = reply.controllerName
            lastCheckInMillis = host.clock.millis()
            state = reply.state
            lastError = ""
            nextCheckInMillis = reply.checkInMillis.takeIf { it > 0 } ?: settings.checkInMillis
            publishTags(true)
            for (order in reply.tasksList) execute(channel, order)
            return reply.state
        }
        state = "disconnected"
        activeConnection = ""
        lastError = errors.joinToString("; ").take(500)
        publishTags(false)
        return null
    }

    private fun publishTags(connected: Boolean) {
        host.publish("Gateway/EAM/Controller/Connected", connected, DataType.BOOLEAN)
        host.publish("Gateway/EAM/Controller/State", state, DataType.STRING)
        host.publish("Gateway/EAM/Controller/Connection", activeConnection, DataType.STRING)
    }

    private fun checkInRequest(settings: EamSettings): EamCheckIn {
        val inventory = host.inventory().toMutableMap()
        if (settings.group.isNotEmpty()) inventory["requestedGroup"] = settings.group
        return EamCheckIn.newBuilder()
            .setGatewayName(host.gatewayName())
            .setVersion(host.version())
            .setInventoryJson(Json.write(inventory))
            .build()
    }

    private fun execute(channel: ManagedChannel, order: EamTaskOrder) {
        val started = host.clock.millis()
        val outcome = runCatching { run(channel, order) }
        val report = EamTaskReport.newBuilder().setRunId(order.runId)
        outcome.onSuccess { (result, upload) ->
            report.setSuccess(true).setMessage("ok").setResultJson(Json.write(result))
            if (upload != null) report.setUploadId(upload)
        }.onFailure {
            report.setSuccess(false).setMessage((it.message ?: it.javaClass.simpleName).take(1000))
        }
        synchronized(recent) {
            recent.addLast(linkedMapOf(
                "run" to order.runId,
                "kind" to order.kind,
                "success" to outcome.isSuccess,
                "message" to (outcome.exceptionOrNull()?.message ?: "ok"),
                "startedMillis" to started,
                "millis" to host.clock.millis() - started,
            ))
            while (recent.size > 50) recent.removeFirst()
        }
        runCatching {
            EnterpriseAdminGrpc.newBlockingStub(channel).withDeadlineAfter(CALL_SECONDS, TimeUnit.SECONDS).report(report.build())
        }.onFailure { lastError = "report ${order.runId}: ${it.message}".take(500) }
    }

    /** Returns the result document and, for a backup, the upload id. */
    private fun run(channel: ManagedChannel, order: EamTaskOrder): Pair<Map<String, Any?>, String?> {
        val params = runCatching { Json.parse(order.paramsJson.ifBlank { "{}" }) }.getOrDefault(emptyMap())
        val kind = EamTaskKind.parse(order.kind)
        if (kind == EamTaskKind.BACKUP) {
            val passphrase = order.secret.toCharArray()
            val bytes = try {
                host.createBackup(passphrase, params["bundleModules"] == true)
            } finally {
                passphrase.fill('\u0000')
            }
            val ack = upload(channel, order.runId, bytes)
            return linkedMapOf<String, Any?>("bytes" to bytes.size, "sha256" to ack.sha256) to ack.uploadId
        }
        val payload = download(channel, order)
        val result: Map<String, Any?> = when (kind) {
            EamTaskKind.RESTORE -> {
                val passphrase = order.secret.toCharArray()
                try {
                    host.restoreBackup(payload, passphrase)
                } finally {
                    passphrase.fill('\u0000')
                }
                linkedMapOf("restored" to (params["backup"] ?: true))
            }
            EamTaskKind.MODULE_INSTALL -> host.installModule(payload)
            EamTaskKind.SEND_PROJECT -> host.importProject(
                params["target"] as? String ?: params["project"] as String,
                payload,
                params["policy"] as? String ?: "overwrite",
            )
            EamTaskKind.SEND_TAGS -> host.importTags(String(payload, Charsets.UTF_8), params["policy"] as? String ?: "overwrite")
            EamTaskKind.UPGRADE -> host.stageUpgrade(payload, order.payloadSha256, params)
            EamTaskKind.BACKUP -> error("unreachable")
        }
        return result to null
    }

    private fun download(channel: ManagedChannel, order: EamTaskOrder): ByteArray {
        if (order.payloadId.isEmpty()) throw IllegalStateException("this run has no payload")
        val out = ByteArrayOutputStream()
        val chunks = EnterpriseAdminGrpc.newBlockingStub(channel).withDeadlineAfter(TRANSFER_SECONDS, TimeUnit.SECONDS)
            .download(EamDownloadRequest.newBuilder().setRunId(order.runId).setPayloadId(order.payloadId).build())
        for (chunk in chunks) {
            if (chunk.offset != out.size().toLong()) throw IllegalStateException("payload chunk out of order")
            chunk.data.writeTo(out)
            if (out.size() > EamController.MAX_PAYLOAD) throw IllegalStateException("payload is too large")
        }
        val bytes = out.toByteArray()
        if (order.payloadSize > 0 && bytes.size.toLong() != order.payloadSize) throw IllegalStateException("payload size does not match")
        if (order.payloadSha256.isNotEmpty() && EamController.sha256(bytes) != order.payloadSha256) {
            throw IllegalStateException("payload checksum does not match")
        }
        return bytes
    }

    private fun upload(channel: ManagedChannel, runId: String, bytes: ByteArray): EamUploadAck {
        val done = CountDownLatch(1)
        val ack = AtomicReference<EamUploadAck?>()
        val error = AtomicReference<Throwable?>()
        val sink = EnterpriseAdminGrpc.newStub(channel).withDeadlineAfter(TRANSFER_SECONDS, TimeUnit.SECONDS)
            .upload(object : StreamObserver<EamUploadAck> {
                override fun onNext(value: EamUploadAck) = ack.set(value)
                override fun onError(t: Throwable) {
                    error.set(t)
                    done.countDown()
                }
                override fun onCompleted() = done.countDown()
            })
        var offset = 0
        while (offset < bytes.size || offset == 0) {
            val end = minOf(bytes.size, offset + EamController.CHUNK)
            sink.onNext(EamChunk.newBuilder().setRunId(runId).setOffset(offset.toLong())
                .setData(ByteString.copyFrom(bytes, offset, end - offset)).setLast(end == bytes.size).build())
            offset = end
            if (bytes.isEmpty()) break
        }
        sink.onCompleted()
        if (!done.await(TRANSFER_SECONDS, TimeUnit.SECONDS)) throw IllegalStateException("upload timed out")
        error.get()?.let { throw IllegalStateException("upload failed: ${it.message}") }
        val result = ack.get() ?: throw IllegalStateException("upload was not acknowledged")
        if (result.sha256 != EamController.sha256(bytes)) throw IllegalStateException("upload checksum does not match")
        return result
    }

    companion object {
        const val CALL_SECONDS = 15L
        const val TRANSFER_SECONDS = 300L
    }
}

/** What enterprise administration needs from the gateway it runs in. [EamRuntime] is the real one. */
interface EamHost {
    val store: org.oip.config.FileConfigStore
    val clock: java.time.Clock
    val network: org.oip.network.GatewayNetwork
    fun settings(): EamSettings
    fun gatewayName(): String
    fun version(): String
    /** False on a redundancy standby, so agents move to the active controller (EAM-012). */
    fun serving(): Boolean
    fun backupPassphrase(): String
    fun inventory(): Map<String, Any?>
    fun exportProject(name: String): ByteArray
    fun exportTags(provider: String, folder: String?): String
    fun createBackup(passphrase: CharArray, bundleModules: Boolean): ByteArray
    fun restoreBackup(archive: ByteArray, passphrase: CharArray)
    fun installModule(bytes: ByteArray): Map<String, Any?>
    fun importProject(name: String, archive: ByteArray, policy: String): Map<String, Any?>
    fun importTags(json: String, policy: String): Map<String, Any?>
    fun stageUpgrade(bytes: ByteArray, sha256: String, params: Map<String, Any?>): Map<String, Any?>
    fun publish(path: String, value: Any?, type: DataType)
    fun configureAlarmTag(config: org.oip.tags.TagConfig)
    fun log(agent: String, kind: String, detail: String)
}
