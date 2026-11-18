package org.oip.alarm

import org.oip.tags.Level
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.expr.EvalContext
import org.oip.tags.expr.Expressions
import org.oip.tags.expr.Value
import java.time.Duration
import kotlin.math.abs

/**
 * One alarm configured on a tag. JSON names follow the tag alarm property reference.
 *
 * Setpoint, enabled, and other attributes may be a literal or a tag, expression, or UDT parameter
 * binding.
 */
class AlarmDefinition(
    val name: String,
    val mode: AlarmMode,
    val priority: AlarmPriority,
    val ackMode: AckMode,
    val enabled: Bound,
    val setpointA: Bound,
    val setpointB: Bound,
    val inclusiveA: Bound,
    val inclusiveB: Bound,
    val deadband: Bound,
    val deadbandMode: DeadbandMode,
    val bitPosition: Bound,
    val bitOnZero: Bound,
    val displayPath: Bound,
    val onDelay: Duration,
    val offDelay: Duration,
    val notes: String?,
    val ackNotesRequired: Boolean,
    val shelvingAllowed: Boolean,
    val timestampSource: TimestampSource,
    val label: String?,
    /** Pipeline entered when the alarm becomes active. Empty means none. */
    val activePipeline: String?,
    /** Pipeline entered when the alarm clears. Empty means none. */
    val clearPipeline: String?,
    /** Pipeline entered when the alarm is acknowledged. Empty means none. */
    val ackPipeline: String?,
    /** Overrides the notification block subject for this alarm. */
    val emailSubject: String?,
    /** Overrides the notification block body for this alarm. */
    val emailMessage: String?,
    /** Associated data copied onto the pipeline event. Values are strings. */
    val associatedData: Map<String, String>,
    /**
     * Associated data entries bound to a tag, expression or parameter instead of fixed text. They are read when the event
     * becomes active and carried on the event like [associatedData] (ALA-039). Doc source: FEATURES ALA-039.
     */
    val associatedBound: Map<String, Bound> = emptyMap(),
) {
    fun dependencyPaths(provider: String, tagPath: String): List<String> {
        val out = LinkedHashSet<String>()
        for (bound in listOf(enabled, setpointA, setpointB, inclusiveA, inclusiveB, deadband, bitPosition, bitOnZero, displayPath)) {
            when (bound) {
                is Bound.TagRef -> out += qualify(bound.path, provider, tagPath)
                is Bound.Expr -> {
                    val refs = try {
                        Expressions.staticRefs(Expressions.parse(bound.text))
                    } catch (_: RuntimeException) {
                        emptyList()
                    }
                    for (ref in refs) {
                        if (ref.startsWith("[") || "/" in ref || ref.startsWith(".")) out += qualify(ref, provider, tagPath)
                    }
                }
                else -> {}
            }
        }
        return out.toList()
    }

    /** Fixed associated data, then each bound entry as it reads now. A bound entry that reads no value is left out. */
    private fun resolveAssociated(tags: TagEngine, provider: String, tagPath: String, params: Map<String, Any?>): Map<String, Any?> {
        if (associatedData.isEmpty() && associatedBound.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>(associatedData)
        for ((key, bound) in associatedBound) {
            val read = resolveQualified(bound, tags, provider, tagPath, params)
            if (read.value != null) out[key] = read.value
        }
        return out
    }

    fun resolve(tags: TagEngine, provider: String, tagPath: String, params: Map<String, Any?>): ResolvedAlarm {
        fun value(bound: Bound): Any? = resolveBound(bound, tags, provider, tagPath, params)
        val a = resolveQualified(setpointA, tags, provider, tagPath, params)
        val b = resolveQualified(setpointB, tags, provider, tagPath, params)
        // Review-3 F3: a setpoint bound to a tag or expression that is not Good, or that reads no value, holds the
        // alarm state. A literal setpoint left unset is not a reading; the condition is simply false.
        fun trusted(bound: Bound, read: Value) = when (bound) {
            is Bound.TagRef, is Bound.Expr -> read.quality.level == Level.GOOD && read.value != null
            else -> true
        }
        val good = when (mode) {
            AlarmMode.ABOVE, AlarmMode.BELOW, AlarmMode.EQUALITY -> trusted(setpointA, a)
            AlarmMode.OUTSIDE -> trusted(setpointA, a) && trusted(setpointB, b)
            AlarmMode.BIT, AlarmMode.OUT_OF_RANGE -> true
        }
        val shown = value(displayPath)?.toString()?.takeIf { it.isNotBlank() } ?: "$tagPath/$name"
        return ResolvedAlarm(
            enabled = truth(value(enabled) ?: true),
            setpointA = number(a.value),
            setpointB = number(b.value),
            inclusiveA = truth(value(inclusiveA) ?: false),
            inclusiveB = truth(value(inclusiveB) ?: false),
            deadband = (number(value(deadband)) ?: 0.0).coerceAtLeast(0.0),
            bitPosition = number(value(bitPosition))?.toInt() ?: 0,
            bitOnZero = truth(value(bitOnZero) ?: false),
            displayPath = shown,
            setpointARaw = a.value,
            setpointBRaw = b.value,
            setpointsGood = good,
            associated = resolveAssociated(tags, provider, tagPath, params),
        )
    }

    /**
     * Activation uses the setpoints. Once latched, a positive deadband keeps the alarm active
     * until the value clears the setpoint by that amount.
     */
    fun inCondition(value: Any?, resolved: ResolvedAlarm, engLow: Double?, engHigh: Double?, latched: Boolean): Boolean {
        if (!resolved.enabled) return false
        val db = effectiveDeadband(resolved, engLow, engHigh)
        return when (mode) {
            AlarmMode.ABOVE -> above(number(value), resolved.setpointA, resolved.inclusiveA, db, latched)
            AlarmMode.BELOW -> below(number(value), resolved.setpointA, resolved.inclusiveA, db, latched)
            AlarmMode.EQUALITY -> equality(value, resolved.setpointARaw, db, latched)
            AlarmMode.BIT -> bit(value, resolved.bitPosition, resolved.bitOnZero)
            AlarmMode.OUT_OF_RANGE -> outside(
                number(value), engLow, engHigh, resolved.inclusiveA, resolved.inclusiveB, db, latched,
            )
            AlarmMode.OUTSIDE -> outside(
                number(value), resolved.setpointA, resolved.setpointB, resolved.inclusiveA, resolved.inclusiveB, db, latched,
            )
        }
    }

    private fun effectiveDeadband(resolved: ResolvedAlarm, engLow: Double?, engHigh: Double?): Double {
        if (deadbandMode == DeadbandMode.OFF || resolved.deadband == 0.0) return 0.0
        if (deadbandMode == DeadbandMode.ABSOLUTE) return resolved.deadband
        if (engLow == null || engHigh == null) return 0.0
        val span = abs(engHigh - engLow)
        if (span == 0.0) return 0.0
        return span * (resolved.deadband / 100.0)
    }

    companion object {
        fun parseAll(config: TagConfig): List<AlarmDefinition> {
            val raw = config.raw["alarms"] as? List<*> ?: return emptyList()
            val names = HashSet<String>()
            return raw.map { item ->
                val map = item as? Map<*, *> ?: throw IllegalArgumentException("alarm must be an object")
                val spec = map.entries.associate { it.key.toString() to it.value }
                parse(spec).also { alarm ->
                    if (!names.add(alarm.name)) throw IllegalArgumentException("duplicate alarm name '${alarm.name}'")
                }
            }
        }

        fun parse(spec: Map<String, Any?>): AlarmDefinition {
            val name = spec["name"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("alarm name is required")
            val mode = AlarmMode.fromName(spec["mode"]?.toString())
                ?: throw IllegalArgumentException("unknown alarm mode '${spec["mode"]}'")
            val priority = AlarmPriority.fromName(spec["priority"]?.toString()) ?: AlarmPriority.MEDIUM
            val ack = AckMode.fromName(spec["ackMode"]?.toString()) ?: AckMode.MANUAL
            val deadbandMode = DeadbandMode.fromName(spec["deadbandMode"]?.toString()) ?: DeadbandMode.ABSOLUTE
            val timestamp = when (spec["timestampSource"]?.toString()?.trim()?.lowercase()) {
                "value" -> TimestampSource.VALUE
                else -> TimestampSource.SYSTEM
            }
            return AlarmDefinition(
                name = name,
                mode = mode,
                priority = priority,
                ackMode = ack,
                enabled = bound(spec["enabled"], default = true),
                setpointA = bound(spec["setpointA"], default = null),
                setpointB = bound(spec["setpointB"], default = null),
                inclusiveA = bound(spec["inclusiveA"], default = false),
                inclusiveB = bound(spec["inclusiveB"], default = false),
                deadband = bound(spec["deadband"], default = 0.0),
                deadbandMode = deadbandMode,
                bitPosition = bound(spec["bitPosition"], default = 0),
                bitOnZero = bound(spec["bitOnZero"], default = false),
                displayPath = bound(spec["displayPath"], default = null),
                onDelay = seconds(spec["timeOnDelaySeconds"]),
                offDelay = seconds(spec["timeOffDelaySeconds"]),
                notes = spec["notes"]?.toString(),
                ackNotesRequired = spec["ackNotesReqd"] as? Boolean ?: false,
                shelvingAllowed = spec["shelvingAllowed"] as? Boolean ?: true,
                timestampSource = timestamp,
                label = spec["label"]?.toString(),
                activePipeline = optionalText(spec["activePipeline"]),
                clearPipeline = optionalText(spec["clearPipeline"]),
                ackPipeline = optionalText(spec["ackPipeline"]),
                emailSubject = optionalText(spec["emailSubject"]),
                emailMessage = optionalText(spec["emailMessage"]),
                associatedData = associated(spec["associatedData"]),
                associatedBound = associatedBindings(spec["associatedData"]),
            )
        }
    }
}

data class ResolvedAlarm(
    val enabled: Boolean,
    val setpointA: Double?,
    val setpointB: Double?,
    val inclusiveA: Boolean,
    val inclusiveB: Boolean,
    val deadband: Double,
    val bitPosition: Int,
    val bitOnZero: Boolean,
    val displayPath: String,
    val setpointARaw: Any?,
    val setpointBRaw: Any?,
    /** False while a setpoint this mode uses is bound to a tag or expression that is not Good or reads null (F3). */
    val setpointsGood: Boolean = true,
    /** Associated data as read now: fixed text and bound values, by name (ALA-039). */
    val associated: Map<String, Any?> = emptyMap(),
)

sealed interface Bound {
    data class Lit(val value: Any?) : Bound
    data class TagRef(val path: String) : Bound
    data class Expr(val text: String) : Bound
    data class Parameter(val name: String) : Bound
}

private fun bound(raw: Any?, default: Any?): Bound {
    if (raw == null && default != null) return Bound.Lit(default)
    if (raw !is Map<*, *>) return Bound.Lit(raw ?: default)
    val keys = listOf("tag", "expression", "parameter").filter { raw.containsKey(it) }
    if (keys.size != 1) throw IllegalArgumentException("alarm binding needs one of tag, expression, or parameter")
    val text = raw[keys[0]]?.toString()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("alarm binding '${keys[0]}' is empty")
    return when (keys[0]) {
        "tag" -> Bound.TagRef(text)
        "expression" -> Bound.Expr(text)
        else -> Bound.Parameter(text)
    }
}

private fun resolveBound(bound: Bound, tags: TagEngine, provider: String, tagPath: String, params: Map<String, Any?>): Any? =
    resolveQualified(bound, tags, provider, tagPath, params).value

/** The bound value with its quality: a literal or parameter is Good, a tag read or expression carries its own. */
private fun resolveQualified(bound: Bound, tags: TagEngine, provider: String, tagPath: String, params: Map<String, Any?>): Value =
    when (bound) {
        is Bound.Lit -> Value(bound.value)
        is Bound.Parameter -> Value(params[bound.name])
        is Bound.TagRef -> tags.read(qualify(bound.path, provider, tagPath)).let { Value(it.value, it.quality) }
        is Bound.Expr -> try {
            Expressions.eval(bound.text, EvalContext(
                ref = { body ->
                    val read = tags.read(qualify(body, provider, tagPath))
                    Value(read.value, read.quality)
                },
                params = params,
            ))
        } catch (_: RuntimeException) {
            Value(null, org.oip.tags.Quality.ERROR_EXPRESSION_EVAL)
        }
    }

private fun qualify(raw: String, provider: String, tagPath: String): String {
    val text = raw.trim()
    if (text.startsWith("[")) return text
    val folder = tagPath.substringBeforeLast('/', "")
    val joined = if (folder.isEmpty() || text.startsWith("/")) text.trimStart('/') else "$folder/$text"
    return "[$provider]$joined"
}

private fun optionalText(raw: Any?): String? = raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }

private fun associated(raw: Any?): Map<String, String> {
    val map = raw as? Map<*, *> ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    for ((key, value) in map) {
        val name = key?.toString()?.trim().orEmpty()
        if (name.isEmpty() || value == null || value is Map<*, *>) continue
        out[name] = value.toString()
    }
    return out
}

/** Entries written as `{tag: ...}`, `{expression: ...}` or `{parameter: ...}` read a live value (ALA-039). */
private fun associatedBindings(raw: Any?): Map<String, Bound> {
    val map = raw as? Map<*, *> ?: return emptyMap()
    val out = linkedMapOf<String, Bound>()
    for ((key, value) in map) {
        val name = key?.toString()?.trim().orEmpty()
        if (name.isEmpty() || value !is Map<*, *>) continue
        out[name] = bound(value, default = null)
    }
    return out
}

private fun seconds(raw: Any?): Duration {
    val n = number(raw) ?: return Duration.ZERO
    if (n < 0.0) throw IllegalArgumentException("alarm delay cannot be negative")
    return Duration.ofMillis((n * 1000.0).toLong())
}

internal fun number(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

private fun truth(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Number -> v.toDouble() != 0.0 && !v.toDouble().isNaN()
    is String -> v.isNotEmpty() && !v.equals("false", true) && v != "0"
    else -> true
}

private fun above(value: Double?, setpoint: Double?, inclusive: Boolean, deadband: Double, latched: Boolean): Boolean {
    if (value == null || setpoint == null || value.isNaN()) return false
    val threshold = if (latched) setpoint - deadband else setpoint
    return if (inclusive) value >= threshold else value > threshold
}

private fun below(value: Double?, setpoint: Double?, inclusive: Boolean, deadband: Double, latched: Boolean): Boolean {
    if (value == null || setpoint == null || value.isNaN()) return false
    val threshold = if (latched) setpoint + deadband else setpoint
    return if (inclusive) value <= threshold else value < threshold
}

private fun outside(
    value: Double?,
    low: Double?,
    high: Double?,
    inclusiveLow: Boolean,
    inclusiveHigh: Boolean,
    deadband: Double,
    latched: Boolean,
): Boolean {
    if (value == null || low == null || high == null) return false
    return below(value, low, inclusiveLow, deadband, latched) || above(value, high, inclusiveHigh, deadband, latched)
}

private fun equality(value: Any?, setpoint: Any?, deadband: Double, latched: Boolean): Boolean {
    val left = number(value)
    val right = number(setpoint)
    if (left != null && right != null) {
        if (left.isNaN() || right.isNaN()) return false
        return if (latched) abs(left - right) <= deadband else left == right
    }
    // Review-3 F3: "no value" never equals "no setpoint"; a null operand is not in condition.
    if (value == null || setpoint == null) return false
    return value.toString() == setpoint.toString()
}

private fun bit(value: Any?, position: Int, onZero: Boolean): Boolean {
    if (position < 0 || position > 62) return false
    val n = number(value)?.toLong() ?: return false
    val high = (n ushr position) and 1L == 1L
    return if (onZero) !high else high
}
