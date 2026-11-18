package org.oip.alarm

import java.time.Instant

/**
 * Alarm conditions, states, and priorities from the public alarming docs.
 *
 * States are the cross of active/clear and acked/unacked. A cleared event never becomes active
 * again; the next trip is a new event.
 *
 * Priorities and mode names:
 */
enum class AlarmPriority {
    DIAGNOSTIC, LOW, MEDIUM, HIGH, CRITICAL;

    val code: Int get() = ordinal

    fun label(): String = when (this) {
        DIAGNOSTIC -> "Diagnostic"
        LOW -> "Low"
        MEDIUM -> "Medium"
        HIGH -> "High"
        CRITICAL -> "Critical"
    }

    companion object {
        fun fromName(raw: String?): AlarmPriority? = when (raw?.trim()?.lowercase()) {
            "diagnostic" -> DIAGNOSTIC
            "low" -> LOW
            "medium" -> MEDIUM
            "high" -> HIGH
            "critical" -> CRITICAL
            null, "" -> null
            else -> null
        }
    }
}

enum class AckMode { UNUSED, AUTO, MANUAL;

    companion object {
        fun fromName(raw: String?): AckMode? = when (raw?.trim()?.lowercase()) {
            "unused" -> UNUSED
            "auto" -> AUTO
            "manual" -> MANUAL
            null, "" -> null
            else -> null
        }
    }
}

enum class AlarmMode { ABOVE, BELOW, EQUALITY, BIT, OUT_OF_RANGE, OUTSIDE;

    companion object {
        fun fromName(raw: String?): AlarmMode? = when (raw?.trim()?.lowercase()) {
            "abovevalue", "abovesetpoint", "above" -> ABOVE
            "belowvalue", "belowsetpoint", "below" -> BELOW
            "equality", "equal" -> EQUALITY
            "bit", "bitstate" -> BIT
            "outofengrange", "outofrange" -> OUT_OF_RANGE
            "outsidevalues", "outsidesetpoints", "outside" -> OUTSIDE
            else -> null
        }
    }
}

enum class DeadbandMode { ABSOLUTE, PERCENT, OFF;

    companion object {
        fun fromName(raw: String?): DeadbandMode? = when (raw?.trim()?.lowercase()) {
            "absolute" -> ABSOLUTE
            "percent" -> PERCENT
            "off" -> OFF
            null, "" -> null
            else -> null
        }
    }
}

enum class TimestampSource { SYSTEM, VALUE }

/**
 * Live-event state. Numeric codes match the runtime State property:
 * ClearUnacked 0, ClearAcked 1, ActiveUnacked 2, ActiveAcked 3.
 */
enum class AlarmRuntimeState(val code: Int, val label: String) {
    CLEAR_UNACKED(0, "Cleared and Unacknowledged"),
    CLEAR_ACKED(1, "Cleared and Acknowledged"),
    ACTIVE_UNACKED(2, "Active and Unacknowledged"),
    ACTIVE_ACKED(3, "Active and Acknowledged"),
}

fun runtimeState(active: Boolean, acked: Boolean): AlarmRuntimeState = when {
    active && acked -> AlarmRuntimeState.ACTIVE_ACKED
    active && !acked -> AlarmRuntimeState.ACTIVE_UNACKED
    !active && acked -> AlarmRuntimeState.CLEAR_ACKED
    else -> AlarmRuntimeState.CLEAR_UNACKED
}

/** Journal eventtype values. - */
object AlarmEventType {
    const val ACTIVE = 0
    const val CLEAR = 1
    const val ACKNOWLEDGED = 2
}

/**
 * eventflags bits, least significant first, as published for alarm_events.eventflags.
 */
object AlarmEventFlags {
    const val SYSTEM = 1
    const val SHELVED = 2
    const val SYSTEM_ACK = 4
    const val ACKNOWLEDGED = 8
    const val CLEARED = 16
    const val ENABLED = 32
}

data class AlarmEventView(
    val eventId: String,
    val source: String,
    val name: String,
    val displayPath: String,
    val tagPath: String,
    val priority: String,
    val priorityCode: Int,
    val state: String,
    val stateCode: Int,
    val active: Boolean,
    val acknowledged: Boolean,
    val value: Any?,
    val activeTime: Instant,
    val clearTime: Instant?,
    val ackTime: Instant?,
    val ackUser: String?,
    val ackNotes: String?,
    val setpointA: Any?,
    val setpointB: Any?,
    val notes: String?,
    /** Associated data read when the event became active (ALA-039). */
    val associatedData: Map<String, Any?> = emptyMap(),
)

/**
 * The tag an alarm source belongs to: `prov:<provider>:/tag:<path>:/alm:<name>` gives `[<provider>]<path>`.
 * Null when [source] is not in that form. Used to apply the tag's read permissions to its alarms (TAG-013).
 */
fun alarmSourceTagPath(source: String): String? {
    if (!source.startsWith(SOURCE_PROVIDER)) return null
    val providerEnd = source.indexOf(SOURCE_TAG, SOURCE_PROVIDER.length)
    if (providerEnd <= SOURCE_PROVIDER.length) return null
    val pathStart = providerEnd + SOURCE_TAG.length
    // The first `:/alm:` after the tag path starts: an alarm name may itself contain `:/alm:`.
    val pathEnd = source.indexOf(SOURCE_ALARM, pathStart)
    if (pathEnd <= pathStart) return null
    return "[" + source.substring(SOURCE_PROVIDER.length, providerEnd) + "]" + source.substring(pathStart, pathEnd)
}

private const val SOURCE_PROVIDER = "prov:"
private const val SOURCE_TAG = ":/tag:"
private const val SOURCE_ALARM = ":/alm:"

data class AlarmFilter(
    val active: Boolean? = null,
    val minPriority: AlarmPriority? = null,
    val source: String? = null,
    val displayPath: String? = null,
)

data class AlarmSettings(
    /** Live events (active or unacknowledged) kept per alarm. Older ones are system-acknowledged. */
    val liveEventLimit: Int = 5,
    /** Journal default is Low, so Diagnostic is not stored unless the floor is lowered. */
    val minimumPriority: AlarmPriority = AlarmPriority.LOW,
    val storeShelvedEvents: Boolean = false,
    /** Longest shelf an operator may set (review-3 F7). Longer requests are refused. */
    val maxShelveDuration: java.time.Duration = java.time.Duration.ofDays(30),
)

/**
 * A transition the notification runtime can route into a pipeline.
 *
 * Active, clear, and acknowledge each name their own pipeline.
 */
data class AlarmNotice(
    val trigger: String,
    val eventId: String,
    val source: String,
    val name: String,
    val displayPath: String,
    val priority: AlarmPriority,
    val active: Boolean,
    val acknowledged: Boolean,
    val shelved: Boolean,
    val value: Any?,
    val activeTime: Instant,
    val clearTime: Instant?,
    val ackTime: Instant?,
    val ackUser: String?,
    val pipeline: String?,
    val emailSubject: String?,
    val emailMessage: String?,
    val associatedData: Map<String, Any?>,
    val notes: String?,
    val notesRequired: Boolean,
    val label: String?,
    val stateLabel: String,
    /** Alarm configuration as scripts see it on the event object (ALA-034). */
    val config: Map<String, Any?> = emptyMap(),
)

/**
 * The alarm event as a script sees it (`event.alarm`): identity, state, value, times and configuration (ALA-034).
 * Times are epoch milliseconds, or null when they have not happened. Doc source: FEATURES ALA-034.
 */
fun AlarmNotice.scriptEvent(): Map<String, Any?> {
    val state = runtimeState(active, acknowledged)
    return linkedMapOf(
        "eventId" to eventId,
        "source" to source,
        "name" to name,
        "displayPath" to displayPath,
        "trigger" to trigger,
        "priority" to priority.label(),
        "priorityCode" to priority.code,
        "state" to state.label,
        "stateCode" to state.code,
        "isActive" to active,
        "isClear" to !active,
        "isAcked" to acknowledged,
        "isShelved" to shelved,
        "value" to value?.let { if (it is Number || it is Boolean || it is String) it else it.toString() },
        "activeTime" to activeTime.toEpochMilli(),
        "clearTime" to clearTime?.toEpochMilli(),
        "ackTime" to ackTime?.toEpochMilli(),
        "ackUser" to ackUser,
        "notes" to notes,
        "label" to label,
        "associatedData" to associatedData.mapValues { (_, v) -> v?.let { if (it is Number || it is Boolean || it is String) it else it.toString() } },
        "config" to config,
    )
}

/** Point-in-time view used while an event is still inside a pipeline. Null from the engine means it has left the live set. */
data class AlarmLiveState(
    val active: Boolean,
    val acknowledged: Boolean,
    val shelved: Boolean,
    val ackUser: String?,
    val ackTime: Instant?,
    val clearTime: Instant?,
    val value: Any?,
    val displayPath: String,
    val priority: AlarmPriority,
)

/** `*` matches any run of characters. Comma-separated patterns match if any one matches. */
fun wildcardMatch(patterns: String?, text: String): Boolean {
    if (patterns.isNullOrBlank()) return true
    return patterns.split(',').any { wildcard(it.trim(), text) }
}

fun wildcard(pattern: String, text: String): Boolean {
    if ('*' !in pattern) return pattern == text
    val parts = pattern.split('*')
    var index = 0
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty()) continue
        val found = text.indexOf(part, index)
        if (found < 0) return false
        if (i == 0 && !pattern.startsWith("*") && found != 0) return false
        index = found + part.length
    }
    if (pattern.endsWith("*")) return true
    val last = parts.last()
    return last.isEmpty() || (text.endsWith(last) && index == text.length)
}

/**
 * Live alarm state copied from the master to the backup.
 * Tag values are applied first so a still-active condition has an event to acknowledge.
 */
data class AlarmSyncState(
    val source: String,
    val tagPath: String,
    val active: Boolean,
    val acknowledged: Boolean,
    val ackUser: String?,
    val shelvedUntilMillis: Long?,
    val eventId: String?,
)
