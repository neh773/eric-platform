package org.oip.s7

import java.time.Instant

/**
 * Adaptation contract v1 for the tag engine.
 *
 * This interface lives in the driver module on purpose. The tag engine (built separately, under
 * gateway/tags) adapts it; drivers do not depend on that engine and the engine is not required to
 * compile a driver. [org.oip.modbus.TagSource] and [org.oip.opcua.TagSource] are the same contract —
 * keep the members aligned.
 *
 * Every sample is a [QualifiedValue]: a value, a quality, a gateway receive [QualifiedValue.timestamp]
 * (UTC), and an optional device [QualifiedValue.sourceTimestamp]. Quality is a level plus a subcode.
 * [Quality.statusCode] is the OPC UA StatusCode (IEC 62541-4 / Part 6) the subcode maps to, masked
 * to the upper 16 bits.
 *
 * OPC UA codes: https://reference.opcfoundation.org/Core/Part6/v105/docs/A.2
 */
interface TagSource {
    /** Stable id of this source (device or connection name). */
    val sourceId: String

    /** Latest connection snapshot. Comms loss is visible here and on subscribed values. */
    fun connectionStatus(): ConnectionStatus

    /** Emit the current status immediately, then every change, until the returned handle is closed. */
    fun subscribeStatus(listener: (ConnectionStatus) -> Unit): AutoCloseable

    /**
     * Children of [nodeId]. An empty id is the root.
     * The basic S7 driver has no PLC browse; this returns the configured and subscribed addresses.
     */
    fun browse(nodeId: String = ""): List<SourceNode>

    /** One-shot read. Order matches [nodeIds]. Failures are bad-quality values, not thrown. */
    fun read(nodeIds: List<String>): List<QualifiedValue>

    /** Write one node. The returned quality is the device result (Good, or a Bad_* subcode). */
    fun write(nodeId: String, value: Any?): WriteResult

    /**
     * Subscribe to value updates.
     *
     * [samplingIntervalMs] is the scan period. The listener is invoked with the current cached sample
     * (or Bad_WaitingForInitialData) before this method returns, then with later samples.
     */
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

/**
 * One sample. [timestamp] is when the gateway received or synthesized the sample (UTC).
 * S7comm has no source timestamp; [sourceTimestamp] stays null.
 * On comms loss the last [value] is retained and [quality] becomes Bad_NotConnected
 * (or Uncertain_LastKnownValue when the source is configured that way).
 */
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

/**
 * Platform subcodes and the OPC UA StatusCode each one maps to (upper 16 bits).
 *
 * [Bad_Stale] has no OPC UA name; it maps to Bad_OutOfService (0x808D0000).
 * [Good_Overridden] maps to Good_LocalOverride (0x00960000).
 * [Uncertain_LastKnownValue] maps to Uncertain_LastUsableValue (0x40900000).
 * [Bad_NotFound] maps to Bad_NodeIdUnknown (0x80340000).
 * [Error] maps to Bad_UnexpectedError (0x80010000).
 */
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
