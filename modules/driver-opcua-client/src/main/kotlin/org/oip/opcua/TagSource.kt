package org.oip.opcua

import java.time.Instant

/**
 * Adaptation contract v1 for the tag engine.
 *
 * This interface lives in the driver module on purpose. The tag engine (built separately, under
 * gateway/tags) adapts it; drivers do not depend on that engine and the engine is not required to
 * compile a driver. [org.oip.modbus.TagSource] is the same contract — keep the members aligned.
 *
 * Every sample is a [QualifiedValue]: a value, a quality, a gateway receive [QualifiedValue.timestamp]
 * (UTC), and an optional device [QualifiedValue.sourceTimestamp]. Quality is a level plus a subcode.
 * [Quality.statusCode] is the OPC UA StatusCode (IEC 62541-4 / Part 6) the subcode maps to, masked
 * to the upper 16 bits. Where the platform subcode has no OPC UA equivalent, the nearest standard
 * code is used and named in [QualityCode].
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
     * Children of [nodeId]. An empty id browses the OPC UA Objects folder.
     * Modbus has no device browse; that driver returns configured and subscribed addresses instead.
     */
    fun browse(nodeId: String = ""): List<SourceNode>

    /** One-shot read. Order matches [nodeIds]. Failures are bad-quality values, not thrown. */
    fun read(nodeIds: List<String>): List<QualifiedValue>

    /** Write one node. The returned quality is the server StatusCode mapped onto [Quality]. */
    fun write(nodeId: String, value: Any?): WriteResult

    /**
     * Subscribe to value updates.
     *
     * [samplingIntervalMs] is the OPC UA sampling interval (or the Modbus scan period).
     * [publishingIntervalMs] is the OPC UA publishing interval. The listener receives the current
     * sample (or Bad_WaitingForInitialData) before this method returns. Close the subscription to stop.
     *
     * After a session drops, the client reconnects and asks the server to transfer the subscription
     * (OPC UA TransferSubscriptions). If the server no longer has it, the subscription is recreated.
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
    /** Revised or requested sampling interval, in milliseconds. */
    val samplingIntervalMs: Double
    /** Revised or requested publishing interval, in milliseconds. */
    val publishingIntervalMs: Double
}

/**
 * One sample. [timestamp] is when the gateway received or synthesized the sample (UTC).
 * [sourceTimestamp] is the device/source timestamp when the protocol provides one.
 * On comms loss the last [value] is retained and [quality] becomes Bad_NotConnected
 * (or Uncertain_LastKnownValue when the source is configured that way).
 */
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
    /** Hint: variable nodes can be read. The server still enforces access on the operation. */
    val readable: Boolean,
    /** Hint: variable nodes can be written. A rejected write comes back as a bad [WriteResult]. */
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
 * [Error] maps to Bad_UnexpectedError (0x80010000); its level stays ERROR so callers can tell a
 * local failure from a device Bad.
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
    /** Session is not allowed to write. OPC UA Bad_UserAccessDenied (0x801F0000). */
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
    /** OPC UA StatusCode, upper 16 bits. Authoritative for interoperability. */
    val statusCode: Long,
    val diagnostic: String? = null,
) {
    companion object {
        fun of(code: QualityCode, diagnostic: String? = null) =
            Quality(code.level, code, code.statusCode, diagnostic)

        /**
         * Map an OPC UA StatusCode (flags in the low 16 bits are ignored) to a platform quality.
         * Unknown codes keep their status word and take the severity from bits 30-31.
         */
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
