package org.oip.udptcp

import java.time.Instant

/**
 * Adaptation contract v1 for the tag engine.
 *
 * This interface lives in the driver module on purpose. The tag engine adapts it; drivers do not
 * depend on that engine. [org.oip.logix.TagSource] and [org.oip.s7.TagSource] are the same contract.
 *
 * OPC UA codes: https://reference.opcfoundation.org/Core/Part6/v105/docs/A.2
 */
interface TagSource {
    val sourceId: String
    fun connectionStatus(): ConnectionStatus
    fun subscribeStatus(listener: (ConnectionStatus) -> Unit): AutoCloseable
    fun browse(nodeId: String = ""): List<SourceNode>
    fun read(nodeIds: List<String>): List<QualifiedValue>
    fun write(nodeId: String, value: Any?): WriteResult
    fun subscribe(
        nodeIds: List<String>,
        samplingIntervalMs: Double,
        publishingIntervalMs: Double = samplingIntervalMs,
        listener: (QualifiedValue) -> Unit,
    ): TagSubscription
}

interface TagSubscription : AutoCloseable {
    val id: String
    val samplingIntervalMs: Double
    val publishingIntervalMs: Double
}

fun interface RawSample {
    fun onSample(value: Any?, quality: Quality, epochMillis: Long)
}

data class QualifiedValue(
    val address: String,
    val value: Any?,
    val quality: Quality,
    val timestamp: Instant,
    val sourceTimestamp: Instant? = null,
)

data class WriteResult(val address: String, val quality: Quality, val timestamp: Instant)

data class SourceNode(
    val nodeId: String,
    val displayName: String,
    val nodeClass: String,
    val readable: Boolean,
    val writable: Boolean,
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

data class ConnectionStatus(
    val state: ConnectionState,
    val connected: Boolean,
    val lastError: String? = null,
    val reconnectAttempt: Int = 0,
    val since: Instant = Instant.EPOCH,
    val requestCount: Long = 0,
    val failureCount: Long = 0,
)

enum class QualityLevel { GOOD, UNCERTAIN, BAD, ERROR }

enum class QualityCode(val level: QualityLevel, val statusCode: Long) {
    Good(QualityLevel.GOOD, 0x00000000),
    Good_Overridden(QualityLevel.GOOD, 0x00960000),
    Uncertain(QualityLevel.UNCERTAIN, 0x40000000),
    Uncertain_LastKnownValue(QualityLevel.UNCERTAIN, 0x40900000),
    Bad(QualityLevel.BAD, 0x80000000),
    Bad_Timeout(QualityLevel.BAD, 0x800A0000),
    Bad_NoCommunication(QualityLevel.BAD, 0x80310000),
    Bad_WaitingForInitialData(QualityLevel.BAD, 0x80320000),
    Bad_NotFound(QualityLevel.BAD, 0x80340000),
    Bad_NotReadable(QualityLevel.BAD, 0x803A0000),
    Bad_NotWritable(QualityLevel.BAD, 0x803B0000),
    Bad_UserAccessDenied(QualityLevel.BAD, 0x801F0000),
    Bad_OutOfRange(QualityLevel.BAD, 0x803C0000),
    Bad_TypeMismatch(QualityLevel.BAD, 0x80740000),
    Bad_ConfigurationError(QualityLevel.BAD, 0x80890000),
    Bad_NotConnected(QualityLevel.BAD, 0x808A0000),
    Bad_DeviceFailure(QualityLevel.BAD, 0x808B0000),
    Bad_Stale(QualityLevel.BAD, 0x808D0000),
    Error(QualityLevel.ERROR, 0x80010000),
}

data class Quality(
    val level: QualityLevel,
    val code: QualityCode,
    val statusCode: Long,
    val diagnostic: String? = null,
) {
    companion object {
        private val plain = QualityCode.entries.associateWith { Quality(it.level, it, it.statusCode, null) }

        fun of(code: QualityCode, diagnostic: String? = null): Quality =
            if (diagnostic == null) plain.getValue(code) else Quality(code.level, code, code.statusCode, diagnostic)

        fun fromOpcUa(statusCode: Long, diagnostic: String? = null): Quality {
            val masked = statusCode and 0xFFFF0000
            val known = QualityCode.entries.find { it.statusCode == masked }
            if (known != null) return Quality(known.level, known, masked, diagnostic)
            val level = when ((masked ushr 30) and 0x3) {
                0L -> QualityLevel.GOOD
                1L -> QualityLevel.UNCERTAIN
                2L -> QualityLevel.BAD
                else -> QualityLevel.ERROR
            }
            val code = when (level) {
                QualityLevel.GOOD -> QualityCode.Good
                QualityLevel.UNCERTAIN -> QualityCode.Uncertain
                QualityLevel.BAD -> QualityCode.Bad
                QualityLevel.ERROR -> QualityCode.Error
            }
            return Quality(level, code, masked, diagnostic)
        }
    }
}

class AddressException(message: String) : IllegalArgumentException(message)
class TypeMismatchException(message: String) : IllegalArgumentException(message)
class OutOfRangeException(message: String) : IllegalArgumentException(message)
class NotConnectedException(message: String = "not connected") : java.io.IOException(message)
