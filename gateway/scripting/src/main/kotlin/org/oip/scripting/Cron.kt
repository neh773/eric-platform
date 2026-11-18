package org.oip.scripting

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Cron match for a gateway scheduled script.
 *
 * Five fields are minute hour day-of-month month day-of-week. Six fields put seconds first.
 * Names and ranges follow the usual crontab form. When both day-of-month and day-of-week are
 * restricted, either one matching is enough (the usual cron OR rule).
 *
 */
class Cron(expression: String) {
    private val seconds: Set<Int>?
    private val minutes: Set<Int>
    private val hours: Set<Int>
    private val days: Set<Int>
    private val months: Set<Int>
    private val dows: Set<Int>
    val fields: Int

    init {
        val parts = expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(parts.size == 5 || parts.size == 6) { "cron must have 5 or 6 fields: '$expression'" }
        fields = parts.size
        var i = 0
        seconds = if (parts.size == 6) parseField(parts[i++], 0, 59) else null
        minutes = parseField(parts[i++], 0, 59)
        hours = parseField(parts[i++], 0, 23)
        days = parseField(parts[i++], 1, 31)
        months = parseField(parts[i++], 1, 12, MONTHS)
        dows = parseField(parts[i], 0, 7, DAYS).let { set ->
            if (7 in set) set + 0 else set
        }
    }

    fun matches(instant: Instant, zone: ZoneId): Boolean {
        val z = instant.atZone(zone)
        if (seconds != null && z.second !in seconds) return false
        if (z.minute !in minutes || z.hour !in hours || z.monthValue !in months) return false
        val domAll = days.size == 31
        val dowAll = dows.size >= 7
        val dom = z.dayOfMonth in days
        val dow = cronDow(z) in dows
        return when {
            domAll && dowAll -> true
            domAll -> dow
            dowAll -> dom
            else -> dom || dow
        }
    }

    /** Slot id so a schedule fires once per matching second (or minute for a 5-field cron). */
    fun fireKey(instant: Instant): Long =
        if (seconds == null) instant.epochSecond / 60 else instant.epochSecond

    companion object {
        private val MONTHS = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        )
        private val DAYS = mapOf(
            "sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6,
        )

        /** Java Monday=1 .. Sunday=7. Cron Sunday=0. */
        private fun cronDow(z: ZonedDateTime): Int = z.dayOfWeek.value % 7

        private fun parseField(token: String, min: Int, max: Int, names: Map<String, Int> = emptyMap()): Set<Int> {
            val out = sortedSetOf<Int>()
            for (part in token.split(",")) {
                require(part.isNotEmpty()) { "empty cron field" }
                val stepSplit = part.split("/")
                require(stepSplit.size <= 2) { "bad cron field '$part'" }
                val range = stepSplit[0]
                val step = if (stepSplit.size == 2) stepSplit[1].toIntOrNull()?.also { require(it > 0) { "bad step '$part'" } }
                    ?: throw IllegalArgumentException("bad cron field '$part'")
                else 1
                val (start, end) = when {
                    range == "*" -> min to max
                    "-" in range -> {
                        val bits = range.split("-")
                        require(bits.size == 2) { "bad cron range '$part'" }
                        number(bits[0], min, max, names) to number(bits[1], min, max, names)
                    }
                    else -> {
                        val n = number(range, min, max, names)
                        n to n
                    }
                }
                require(start <= end) { "bad cron range '$part'" }
                if (stepSplit.size == 1 && range != "*" && "-" !in range) out += start
                else {
                    var v = start
                    while (v <= end) {
                        out += v
                        v += step
                    }
                }
            }
            require(out.isNotEmpty()) { "cron field matched nothing: '$token'" }
            return out
        }

        private fun number(token: String, min: Int, max: Int, names: Map<String, Int>): Int {
            val named = names[token.lowercase()]
            val n = named ?: token.toIntOrNull() ?: throw IllegalArgumentException("bad cron value '$token'")
            require(n in min..max) { "cron value $n is outside $min..$max" }
            return n
        }
    }
}
