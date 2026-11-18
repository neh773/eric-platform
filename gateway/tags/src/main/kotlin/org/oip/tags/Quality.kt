package org.oip.tags

import java.time.Clock
import java.time.Instant

/**
 * Quality levels from SPEC §5.
 * Names follow the public the reference platform quality table where they describe the same condition
 * (-).
 * Numeric values are OPC UA StatusCodes (OPC 10000-6 Annex A, published in
 * https://github.com/OPCFoundation/UA-Nodeset/blob/latest/Schema/StatusCode.csv)
 * when a 1:1 equivalent exists. Platform-only codes leave [QualityCode.opcUa] null and set the
 * vendor-specific subcode bit (bit 29) so they cannot collide with a standard code.
 */
enum class Level { GOOD, UNCERTAIN, BAD, ERROR }

enum class QualityCode(
    val level: Level,
    /** Exact OPC UA status, or null when no 1:1 equivalent exists (SPEC §5). */
    val opcUa: Int?,
    val label: String,
    vararg aliases: String,
) {
    GOOD(Level.GOOD, u32(0x00000000), "Good", "Good_Unspecified"),
    GOOD_OVERRIDDEN(Level.GOOD, u32(0x00960000), "Good_Overridden", "Good_LocalOverride"),
    GOOD_OVERLOAD(Level.GOOD, u32(0x002F0000), "Good_Overload"),
    GOOD_INITIAL(Level.GOOD, null, "Good_Initial"),
    GOOD_WRITE_PENDING(Level.GOOD, null, "Good_WritePending"),

    UNCERTAIN(Level.UNCERTAIN, u32(0x40000000), "Uncertain"),
    UNCERTAIN_LAST_KNOWN_VALUE(
        Level.UNCERTAIN, u32(0x40900000), "Uncertain_LastKnownValue",
        "Uncertain_LastUsableValue",
    ),
    UNCERTAIN_NO_COMM_LAST_USABLE(
        Level.UNCERTAIN, u32(0x408F0000), "Uncertain_NoCommunicationLastUsableValue",
    ),
    UNCERTAIN_INITIAL_VALUE(Level.UNCERTAIN, u32(0x40920000), "Uncertain_InitialValue"),
    UNCERTAIN_SUBSTITUTE(Level.UNCERTAIN, u32(0x40910000), "Uncertain_SubstituteValue"),
    UNCERTAIN_SENSOR_NOT_ACCURATE(Level.UNCERTAIN, u32(0x40930000), "Uncertain_SensorNotAccurate"),
    UNCERTAIN_ENGINEERING_UNITS_EXCEEDED(
        Level.UNCERTAIN, u32(0x40940000), "Uncertain_EngineeringUnitsExceeded",
    ),
    UNCERTAIN_SUB_NORMAL(Level.UNCERTAIN, u32(0x40950000), "Uncertain_SubNormal", "Uncertain_DataSubNormal"),

    BAD(Level.BAD, u32(0x80000000), "Bad"),
    BAD_UNEXPECTED_ERROR(Level.BAD, u32(0x80010000), "Bad_UnexpectedError"),
    BAD_INTERNAL_ERROR(Level.BAD, u32(0x80020000), "Bad_InternalError"),
    BAD_TIMEOUT(Level.BAD, u32(0x800A0000), "Bad_Timeout"),
    BAD_USER_ACCESS_DENIED(Level.BAD, u32(0x801F0000), "Bad_AccessDenied", "Bad_Unauthorized", "Bad_UserAccessDenied"),
    BAD_NO_COMMUNICATION(Level.BAD, u32(0x80310000), "Bad_NoCommunication"),
    BAD_WAITING_FOR_INITIAL_DATA(Level.BAD, u32(0x80320000), "Bad_WaitingForInitialData"),
    BAD_NOT_READABLE(Level.BAD, u32(0x803A0000), "Bad_NotReadable"),
    BAD_NOT_WRITABLE(Level.BAD, u32(0x803B0000), "Bad_NotWritable", "Bad_ReadOnly"),
    BAD_OUT_OF_RANGE(Level.BAD, u32(0x803C0000), "Bad_OutOfRange", "Bad_OutofRange"),
    BAD_NOT_SUPPORTED(Level.BAD, u32(0x803D0000), "Bad_Unsupported", "Bad_NotSupported"),
    BAD_NOT_FOUND(Level.BAD, u32(0x803E0000), "Bad_NotFound"),
    BAD_CONFIGURATION_ERROR(Level.BAD, u32(0x80890000), "Bad_ConfigurationError"),
    BAD_NOT_CONNECTED(Level.BAD, u32(0x808A0000), "Bad_NotConnected"),
    BAD_DEVICE_FAILURE(Level.BAD, u32(0x808B0000), "Bad_DeviceFailure"),
    BAD_SENSOR_FAILURE(Level.BAD, u32(0x808C0000), "Bad_SensorFailure"),
    BAD_OUT_OF_SERVICE(Level.BAD, u32(0x808D0000), "Bad_OutOfService"),
    BAD_STALE(Level.BAD, platformCode(2, 1), "Bad_Stale"),
    BAD_DISABLED(Level.BAD, platformCode(2, 2), "Bad_Disabled"),
    BAD_REFERENCE_NOT_FOUND(Level.BAD, platformCode(2, 3), "Bad_ReferenceNotFound"),

    ERROR(Level.ERROR, platformCode(3, 1), "Error"),
    ERROR_CONFIGURATION(Level.ERROR, platformCode(3, 2), "Error_Configuration"),
    ERROR_EXPRESSION_EVAL(Level.ERROR, platformCode(3, 3), "Error_ExpressionEval"),
    ERROR_TYPE_CONVERSION(Level.ERROR, platformCode(3, 4), "Error_TypeConversion"),
    ERROR_CYCLE_DETECTED(Level.ERROR, platformCode(3, 5), "Error_CycleDetected"),
    ERROR_INVALID_PATH(Level.ERROR, platformCode(3, 6), "Error_InvalidPathSyntax");

    internal val names: Set<String> = buildSet {
        add(normalize(label))
        add(normalize(name))
        aliases.forEach { add(normalize(it)) }
    }

    internal fun matches(token: String) = normalize(token) in names

    companion object {
        /** Index is the high 16 bits of the status (the code). The low 16 are info bits and are ignored. */
        private val byOpc = arrayOfNulls<QualityCode>(65536).also { table ->
            for (c in QualityCode.entries) {
                val code = c.opcUa ?: continue
                if (isPlatform(code)) continue
                table[(code ushr 16) and 0xFFFF] = c
            }
        }
        private val byName: Map<String, QualityCode> = buildMap {
            for (c in QualityCode.entries) c.names.forEach { put(it, c) }
        }

        private val plain = QualityCode.entries.associateWith { Quality(it) }

        fun shared(code: QualityCode): Quality = plain.getValue(code)

        fun fromName(token: String): QualityCode? = byName[normalize(token)]

        /**
         * Map an OPC UA StatusCode. Info bits (the low 16) are ignored. Unknown codes keep the severity.
         * Status 0 (Good) returns the shared instance without touching the table. This runs once per sample.
         */
        fun fromOpcUa(status: Int): Quality {
            val idx = status ushr 16
            if (idx == 0) return shared(GOOD)
            byOpc[idx]?.let { return shared(it) }
            val level = when ((status ushr 30) and 0x3) {
                0 -> Level.GOOD
                1 -> Level.UNCERTAIN
                2 -> Level.BAD
                else -> Level.ERROR
            }
            val generic = when (level) {
                Level.GOOD -> GOOD
                Level.UNCERTAIN -> UNCERTAIN
                Level.BAD -> BAD
                Level.ERROR -> ERROR
            }
            return Quality(generic, "unmapped OPC UA status 0x${status.toUInt().toString(16)}")
        }
    }
}

/**
 * Vendor-specific status: severity in bits 30-31, vendor flag in bit 29, subcode in bits 16-28.
 * These are not OPC UA standard codes; [Quality.toOpcUa] falls back to the severity's generic code.
 */
private fun u32(value: Long): Int = value.toInt()

private fun platformCode(severity: Int, sub: Int): Int =
    (severity shl 30) or (1 shl 29) or (sub shl 16)

private const val OPC_CODE_MASK = 0xFFFF0000.toInt()

private fun normalize(s: String) = s.lowercase().replace("_", "")

class Quality(val code: QualityCode, val diagnostic: String? = null) {
    val level: Level get() = code.level

    /** 1:1 OPC UA StatusCode, or null when this subcode has no standard equivalent. */
    val opcUaStatus: Int?
        get() {
            val raw = code.opcUa ?: return null
            return if (isPlatform(raw)) null else raw
        }

    /**
     * StatusCode for an OPC UA peer. Exact when [opcUaStatus] is non-null; otherwise the generic
     * code for the level (Error falls back to Bad_UnexpectedError because OPC UA reserves severity 3).
     */
    fun toOpcUa(): Int {
        val exact = opcUaStatus
        if (exact != null) return exact
        return when (code.level) {
            Level.GOOD -> u32(0x00000000)
            Level.UNCERTAIN -> u32(0x40000000)
            Level.ERROR -> u32(0x80010000)
            Level.BAD -> u32(0x80000000)
        }
    }

    fun withDiagnostic(text: String?): Quality = if (text == diagnostic) this else Quality(code, text)

    fun worse(other: Quality): Quality = if (other.level.ordinal > level.ordinal) other else this

    override fun equals(other: Any?) = other is Quality && code == other.code && diagnostic == other.diagnostic
    override fun hashCode() = 31 * code.hashCode() + (diagnostic?.hashCode() ?: 0)
    override fun toString() = if (diagnostic.isNullOrEmpty()) code.label else "${code.label}: $diagnostic"

    companion object {
        val GOOD = Quality(QualityCode.GOOD)
        val GOOD_OVERRIDDEN = Quality(QualityCode.GOOD_OVERRIDDEN)
        val GOOD_INITIAL = Quality(QualityCode.GOOD_INITIAL)
        val GOOD_WRITE_PENDING = Quality(QualityCode.GOOD_WRITE_PENDING)
        val UNCERTAIN = Quality(QualityCode.UNCERTAIN)
        val UNCERTAIN_LAST_KNOWN_VALUE = Quality(QualityCode.UNCERTAIN_LAST_KNOWN_VALUE)
        val UNCERTAIN_INITIAL_VALUE = Quality(QualityCode.UNCERTAIN_INITIAL_VALUE)
        val UNCERTAIN_ENGINEERING_UNITS_EXCEEDED = Quality(QualityCode.UNCERTAIN_ENGINEERING_UNITS_EXCEEDED)
        val BAD = Quality(QualityCode.BAD)
        val BAD_NOT_CONNECTED = Quality(QualityCode.BAD_NOT_CONNECTED)
        val BAD_NOT_FOUND = Quality(QualityCode.BAD_NOT_FOUND)
        val BAD_NOT_WRITABLE = Quality(QualityCode.BAD_NOT_WRITABLE)
        val BAD_REFERENCE_NOT_FOUND = Quality(QualityCode.BAD_REFERENCE_NOT_FOUND)
        val BAD_WAITING_FOR_INITIAL_DATA = Quality(QualityCode.BAD_WAITING_FOR_INITIAL_DATA)
        val BAD_OUT_OF_RANGE = Quality(QualityCode.BAD_OUT_OF_RANGE)
        val BAD_STALE = Quality(QualityCode.BAD_STALE)
        val ERROR = Quality(QualityCode.ERROR)
        val ERROR_CONFIGURATION = Quality(QualityCode.ERROR_CONFIGURATION)
        val ERROR_EXPRESSION_EVAL = Quality(QualityCode.ERROR_EXPRESSION_EVAL)
        val ERROR_TYPE_CONVERSION = Quality(QualityCode.ERROR_TYPE_CONVERSION)
        val ERROR_CYCLE_DETECTED = Quality(QualityCode.ERROR_CYCLE_DETECTED)
        val ERROR_INVALID_PATH = Quality(QualityCode.ERROR_INVALID_PATH)

        fun fromOpcUa(status: Int) = QualityCode.fromOpcUa(status)
        fun fromName(token: String, diagnostic: String? = null): Quality? =
            QualityCode.fromName(token)?.let { code ->
                if (diagnostic == null) QualityCode.shared(code) else Quality(code, diagnostic)
            }
    }
}

private fun isPlatform(code: Int) = (code and (1 shl 29)) != 0

/** SPEC §5: value, quality, timestamp, optional source timestamp. Timestamps are UTC, microsecond resolution. */
data class QualifiedValue(
    val value: Any?,
    val quality: Quality,
    val timestamp: Instant,
    val sourceTimestamp: Instant? = null,
) {
    init {
        require(timestamp.nano % 1000 == 0) { "timestamp must be truncated to microseconds" }
        require(sourceTimestamp == null || sourceTimestamp.nano % 1000 == 0) { "sourceTimestamp must be truncated to microseconds" }
    }

    companion object {
        fun of(value: Any?, quality: Quality, clock: Clock, sourceTimestamp: Instant? = null): QualifiedValue {
            val now = clock.instant().micros()
            val src = sourceTimestamp?.micros()
            // Device/source timestamp is kept when provided; otherwise the gateway receive time (SPEC §5).
            return QualifiedValue(value, quality, src ?: now, src)
        }
    }
}

data class WriteResult(val quality: Quality) {
    val ok: Boolean get() = quality.level == Level.GOOD
}

/**
 * One stored change of the tag at [path], as a subscriber receives it.
 *
 * A listener gets one tag's changes in the order the engine stored them, each exactly once. [previous] is the
 * value the engine held just before [value], which is also the [value] of the change this listener was given
 * last for that path: a change stored while listeners are still being called waits behind the current one and
 * keeps the value it replaced, rather than being paired with whatever the tag holds when it is handed out.
 * The chain starts over when the listener subscribes (its first change's [previous] is the engine's previous
 * sample) and when the tag is reconfigured, which can replace the value without a change. [previous] is null
 * only where no value applies, such as a change the caller may not read.
 */
data class TagChange(val path: TagPath, val value: QualifiedValue, val previous: QualifiedValue?)

internal fun Instant.micros(): Instant {
    val nanos = nano
    val truncated = nanos / 1000 * 1000
    return if (truncated == nanos) this else Instant.ofEpochSecond(epochSecond, truncated.toLong())
}

internal fun Clock.nowMicros(): Instant = instant().micros()
