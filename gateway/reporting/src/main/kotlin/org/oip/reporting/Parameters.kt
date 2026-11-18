package org.oip.reporting

import java.time.Instant
import java.time.format.DateTimeParseException

/** A run parameter value the definition rejects (wrong type, missing, unknown). The REST layer returns 400. */
class ReportParameterException(message: String) : RuntimeException(message)

/**
 * Typed report parameters (contract section 1). Values arrive as JSON (numbers, strings, booleans, objects) and
 * resolve, at run time, to: string, Double/Long number, Boolean, epoch-millis Long timestamp, or a timeRange
 * `{from, to}` map of epoch millis. Relative ranges (`{"last": "24h"}`) and `"now"` resolve against [nowMillis].
 */
object Parameters {
    private val DURATION = Regex("^(\\d{1,9})([smhdw])$")

    /**
     * Resolve [defs] against [supplied]: supplied wins, then the expression (parameters resolve in declaration order,
     * so an expression may read an earlier one), then the default, then null. Unknown supplied names,
     * a missing required value, or a value of the wrong type throw [ReportParameterException].
     */
    fun resolve(defs: List<ParameterDef>, supplied: Map<String, Any?>, nowMillis: Long): List<ResolvedParameter> {
        val known = defs.map { it.name }.toSet()
        val unknown = supplied.keys - known
        if (unknown.isNotEmpty()) throw ReportParameterException("unknown parameter ${unknown.sorted().joinToString(", ")}")
        val values = HashMap<String, Any?>()
        return defs.map { def ->
            val value = try {
                val raw = when {
                    supplied.containsKey(def.name) -> supplied[def.name]
                    def.expression != null -> ParameterExpressions.evaluate(ParameterExpressions.parse(def.expression), nowMillis) { name ->
                        if (name !in values) throw IllegalArgumentException("unknown parameter $name")
                        values[name]
                    }
                    def.hasDefault -> def.default
                    else -> null
                }
                if (raw == null && def.required) throw ReportParameterException("parameter ${def.name} is required")
                raw?.let { convert(def.type, it, nowMillis) }
            } catch (e: IllegalArgumentException) {
                throw ReportParameterException("parameter ${def.name}: ${e.message}")
            }
            values[def.name] = value
            ResolvedParameter(def.name, def.type, value)
        }
    }

    /** Convert one non-null value to [type], or throw [IllegalArgumentException]. */
    fun convert(type: ParameterType, raw: Any, nowMillis: Long): Any = when (type) {
        ParameterType.STRING -> raw as? String ?: throw IllegalArgumentException("expects a string")
        ParameterType.NUMBER -> when (raw) {
            is Int, is Long, is Short, is Byte -> (raw as Number).toLong()
            is Number -> raw.toDouble().also { require(it.isFinite()) { "expects a finite number" } }
            else -> throw IllegalArgumentException("expects a number")
        }
        ParameterType.BOOLEAN -> raw as? Boolean ?: throw IllegalArgumentException("expects true or false")
        ParameterType.TIMESTAMP -> timestamp(raw, nowMillis)
        ParameterType.TIME_RANGE -> timeRange(raw, nowMillis)
    }

    fun timestamp(raw: Any, nowMillis: Long): Long = when (raw) {
        is Int, is Long, is Short, is Byte -> (raw as Number).toLong()
        is Number -> raw.toDouble().let { d ->
            require(d.isFinite() && d % 1.0 == 0.0) { "timestamp must be whole epoch milliseconds" }
            d.toLong()
        }
        is String -> if (raw.trim().equals("now", ignoreCase = true)) nowMillis else try {
            Instant.parse(raw.trim()).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("timestamp must be epoch milliseconds, an ISO-8601 instant, or \"now\"")
        }
        else -> throw IllegalArgumentException("timestamp must be epoch milliseconds, an ISO-8601 instant, or \"now\"")
    }

    /** `{last: "24h"}` or `{from, to}`; returns `{from, to}` (from <= to). */
    fun timeRange(raw: Any, nowMillis: Long): Map<String, Long> {
        val m = raw as? Map<*, *> ?: throw IllegalArgumentException("timeRange must be an object {last} or {from, to}")
        val keys = m.keys.map { it.toString() }.toSet()
        return when (keys) {
            setOf("last") -> {
                val millis = durationMillis(m["last"] as? String ?: throw IllegalArgumentException("last must be a string like \"24h\""))
                linkedMapOf("from" to nowMillis - millis, "to" to nowMillis)
            }
            setOf("from", "to") -> {
                val from = timestamp(m["from"] ?: throw IllegalArgumentException("from is required"), nowMillis)
                val to = timestamp(m["to"] ?: throw IllegalArgumentException("to is required"), nowMillis)
                require(from <= to) { "from must not be after to" }
                linkedMapOf("from" to from, "to" to to)
            }
            else -> throw IllegalArgumentException("timeRange must be {last} or {from, to}")
        }
    }

    /** `<n><unit>` with unit s, m, h, d, w; n > 0. */
    fun durationMillis(text: String): Long {
        val match = DURATION.matchEntire(text.trim()) ?: throw IllegalArgumentException("duration must look like 15m, 24h, 7d (units s, m, h, d, w)")
        val n = match.groupValues[1].toLong()
        require(n > 0) { "duration must be greater than zero" }
        val unit = when (match.groupValues[2]) {
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            "d" -> 86_400_000L
            else -> 604_800_000L
        }
        return n * unit
    }
}
