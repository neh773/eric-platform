package org.oip.reporting

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.BitSet

/**
 * Minimal five-field cron expression: `minute hour day-of-month month day-of-week`, as in POSIX crontab
 * (https://pubs.opengroup.org/onlinepubs/9799919799/utilities/crontab.html) with the common Vixie extensions:
 * ranges `a-b`, lists `a,b`, steps `*&#47;n` and `a-b/n` and `a/n`, month names `JAN`-`DEC`, day names `SUN`-`SAT`
 * (case-insensitive), day-of-week 7 = Sunday, and the macros `@yearly`, `@annually`, `@monthly`, `@weekly`,
 * `@daily`, `@midnight`, `@hourly`.
 *
 * Day matching follows POSIX: when both day-of-month and day-of-week are restricted (neither starts with `*`),
 * a day matches if **either** matches; otherwise both must.
 *
 * Times are local to the schedule's zone. A local time that does not exist (the spring-forward gap) is skipped;
 * a local time that occurs twice (fall-back overlap) fires once, at its first occurrence.
 * No seconds field, no `L`/`W`/`#`/`?`. No cron utility existed in the repo, and this needs no dependency.
 */
class CronExpression private constructor(
    val text: String,
    private val minutes: BitSet,
    private val hours: BitSet,
    private val daysOfMonth: BitSet,
    private val months: BitSet,
    private val daysOfWeek: BitSet,
    private val domStar: Boolean,
    private val dowStar: Boolean,
) {
    /** The first fire time strictly after [after], or null if none within [SEARCH_YEARS] years (e.g. `0 0 31 2 *`). */
    fun next(after: Instant, zone: ZoneId): Instant? {
        var t = LocalDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(SEARCH_YEARS)
        while (t.isBefore(limit)) {
            if (!months[t.monthValue]) {
                t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay()
                continue
            }
            if (!dayMatches(t.toLocalDate())) {
                t = t.toLocalDate().plusDays(1).atStartOfDay()
                continue
            }
            if (!hours[t.hour]) {
                t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1)
                continue
            }
            if (!minutes[t.minute]) {
                t = t.plusMinutes(1)
                continue
            }
            // Gap: no valid offset, skip. Overlap: the first offset is the earlier instant.
            for (offset in zone.rules.getValidOffsets(t)) {
                val instant = t.toInstant(offset)
                if (instant.isAfter(after)) return instant
            }
            t = t.plusMinutes(1)
        }
        return null
    }

    private fun dayMatches(d: LocalDate): Boolean {
        val dom = daysOfMonth[d.dayOfMonth]
        val dow = daysOfWeek[d.dayOfWeek.value % 7]
        return if (!domStar && !dowStar) dom || dow else dom && dow
    }

    override fun toString() = text

    companion object {
        const val SEARCH_YEARS = 5L

        private val MACROS = mapOf(
            "@yearly" to "0 0 1 1 *", "@annually" to "0 0 1 1 *", "@monthly" to "0 0 1 * *",
            "@weekly" to "0 0 * * 0", "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *", "@hourly" to "0 * * * *",
        )
        private val MONTH_NAMES = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private val DAY_NAMES = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

        /** Parse, or throw [IllegalArgumentException] naming the bad field. */
        fun parse(text: String): CronExpression {
            val trimmed = text.trim()
            val expanded = MACROS[trimmed.lowercase()] ?: trimmed
            val f = expanded.split(Regex("\\s+"))
            require(f.size == 5) { "cron must have 5 fields (minute hour day-of-month month day-of-week), got ${f.size}" }
            val dow = field(f[4], 0, 7, "day-of-week", DAY_NAMES, 0)
            if (dow[7]) { dow.set(0); dow.clear(7) }
            return CronExpression(
                trimmed,
                field(f[0], 0, 59, "minute"),
                field(f[1], 0, 23, "hour"),
                field(f[2], 1, 31, "day-of-month"),
                field(f[3], 1, 12, "month", MONTH_NAMES, 1),
                dow,
                domStar = f[2].startsWith("*"),
                dowStar = f[4].startsWith("*"),
            )
        }

        private fun field(text: String, min: Int, max: Int, what: String, names: List<String> = emptyList(), nameBase: Int = 0): BitSet {
            val bits = BitSet(max + 1)
            fun value(s: String): Int {
                val idx = names.indexOf(s.uppercase())
                val v = if (idx >= 0) idx + nameBase else s.toIntOrNull() ?: throw IllegalArgumentException("cron $what: '$s' is not a number")
                require(v in min..max) { "cron $what: $v is outside $min-$max" }
                return v
            }
            for (part in text.split(',')) {
                require(part.isNotEmpty()) { "cron $what: empty list item" }
                val slash = part.split('/')
                require(slash.size <= 2) { "cron $what: bad step '$part'" }
                val step = if (slash.size == 2) slash[1].toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("cron $what: step must be a positive number in '$part'") else 1
                val range = slash[0]
                val (lo, hi) = when {
                    range == "*" -> min to max
                    range.contains('-') -> {
                        val ab = range.split('-')
                        require(ab.size == 2) { "cron $what: bad range '$range'" }
                        val a = value(ab[0]); val b = value(ab[1])
                        require(a <= b) { "cron $what: range $a-$b is backwards" }
                        a to b
                    }
                    else -> value(range).let { if (slash.size == 2) it to max else it to it }
                }
                var i = lo
                while (i <= hi) { bits.set(i); i += step }
            }
            return bits
        }
    }
}
