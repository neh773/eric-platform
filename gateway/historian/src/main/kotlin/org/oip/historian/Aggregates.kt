package org.oip.historian

import org.oip.tags.Level

/**
 * How each fixed window of a history query is reduced to one value. The set of modes and their wire names
 * follow the Aggregation Mode list on the reference platform 8.3's tag history binding page; the definitions below are our own
 * (see [HistoryAggregator] for the exact rules):
 *
 *  - [AVERAGE]: mean over time, each value counted for as long as it was in effect inside the window.
 *  - [SIMPLE_AVERAGE]: arithmetic mean of the window's rows, ignoring how long each lasted.
 *  - [SUM]: total of the window's rows.
 *  - [MINIMUM] / [MAXIMUM]: smallest / largest value in the window.
 *  - [RANGE]: [MAXIMUM] minus [MINIMUM].
 *  - [LAST_VALUE]: the newest row in the window.
 *  - [COUNT]: how many rows the window holds.
 *
 * Not in this slice: MinMax (two rows per window), DurationOn/Off, CountOn/Off, Variance, StdDev, PctGood/Bad.
 */
enum class HistoryAggregate(val wire: String) {
    AVERAGE("Average"),
    SIMPLE_AVERAGE("SimpleAverage"),
    SUM("Sum"),
    MINIMUM("Minimum"),
    MAXIMUM("Maximum"),
    RANGE("Range"),
    LAST_VALUE("LastValue"),
    COUNT("Count");

    companion object {
        fun fromWire(text: String): HistoryAggregate = entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("aggregate must be one of ${entries.joinToString { it.wire }}")
    }
}

/**
 * How values between stored rows are estimated by [HistoryAggregator] when [HistoryInterpolation] is not [NONE].
 *  - [NONE]: no estimate; only rows inside a window count (Average still holds each row until the next one).
 *  - [STEP]: a value stays in effect until the next row.
 *  - [LINEAR]: a straight line between two Good numeric rows; after the last row, or next to a non-Good or
 *    non-numeric row, the value is held as in [STEP].
 */
enum class HistoryInterpolation(val wire: String) {
    NONE("None"),
    STEP("Step"),
    LINEAR("Linear");

    companion object {
        fun fromWire(text: String): HistoryInterpolation = entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("interpolation must be one of ${entries.joinToString { it.wire }}")
    }
}

/**
 * One fixed interval `[startMillis, endMillis)` of an aggregate query, with the raw row count inside it.
 * [startValue] and [endValue] are the interpolated values at the window's start and end; they are set only
 * when the query asked for interpolation and a Good numeric value is in effect there.
 */
data class AggregateWindow(
    val startMillis: Long,
    val endMillis: Long,
    val value: Any?,
    val rows: Int,
    val startValue: Double? = null,
    val endValue: Double? = null,
)

/**
 * Computes [HistoryAggregate]s over fixed intervals from any [HistoryReader], so every provider answers the
 * same way. Rules:
 *
 *  - The inclusive range `[from, to]` is cut into windows of [intervalMillis] starting at `from`:
 *    `[from, from+i)`, `[from+i, from+2i)`, ...; the last window ends at `to + 1` (it may be shorter).
 *    At most [MAX_WINDOWS] windows.
 *  - Numeric modes (Average, SimpleAverage, Sum, Minimum, Maximum, Range) use rows whose quality level is Good
 *    and whose value is numeric (booleans count as 1/0). Every numeric mode skips non-Good rows, so a Bad row's
 *    retained value never skews a result.
 *  - Average is time-weighted. Each row's value is in effect from its timestamp until the next row; the last
 *    row stays in effect until `to + 1`, and the newest row before `from` covers the start of the first window.
 *    Time during which a non-Good row is in effect carries no weight. With [HistoryInterpolation.LINEAR] the
 *    value between two Good numeric rows follows a straight line, so each segment contributes its trapezoid
 *    area instead of a rectangle.
 *  - LastValue is the last row inside the window (any quality); Count is every row inside the window.
 *  - A window with nothing to aggregate returns null (Count returns 0).
 *
 * Interpolation ([HistoryInterpolation], default [HistoryInterpolation.NONE], which gives the rules above
 * unchanged):
 *  - Each window gets [AggregateWindow.startValue] and [AggregateWindow.endValue], the estimated value at its
 *    two edges (null where no Good numeric value is in effect). For [HistoryInterpolation.LINEAR] at the end of
 *    the range, the first row after `to` is read so the last edge lies on the line toward it.
 *  - Minimum, Maximum and Range also consider the start value (and, for Linear, the end value), so a window
 *    between two rows, or the part of a ramp cut by a window edge, still has a result. With Step the end value
 *    can belong to a row of the next window, so it is not used there.
 *  - LastValue of a window with no rows is the value in effect at its end: the end value for Linear, the start
 *    value for Step (nothing changes inside an empty window).
 *  - Sum, SimpleAverage and Count describe recorded rows only and are not affected.
 *
 * A reader that is a [NativeAggregation] is asked first and its answer is returned as is; the rules above are
 * the contract it must keep.
 */
object HistoryAggregator {
    const val MAX_WINDOWS = 10_000

    /** Number of windows for the range, or an [IllegalArgumentException] when the request is invalid. */
    fun windowCount(fromMillis: Long, toMillis: Long, intervalMillis: Long): Int {
        require(intervalMillis > 0) { "interval must be > 0" }
        require(fromMillis <= toMillis) { "from must not be after to" }
        val span = try {
            Math.addExact(Math.subtractExact(toMillis, fromMillis), 1)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("range is too large")
        }
        val n = (span - 1) / intervalMillis + 1
        require(n <= MAX_WINDOWS) { "range / interval gives $n windows; at most $MAX_WINDOWS are allowed" }
        return n.toInt()
    }

    fun aggregate(
        reader: HistoryReader,
        tagPath: String,
        fromMillis: Long,
        toMillis: Long,
        intervalMillis: Long,
        mode: HistoryAggregate,
        interpolation: HistoryInterpolation = HistoryInterpolation.NONE,
    ): List<AggregateWindow> {
        val n = windowCount(fromMillis, toMillis, intervalMillis)
        // A store that aggregates natively answers without streaming rows here (doc source: FEATURES HIS-007).
        if (reader is NativeAggregation) {
            reader.aggregateNative(tagPath, fromMillis, toMillis, intervalMillis, mode, interpolation)?.let { return it }
        }
        val end = toMillis + 1
        val acc = Array(n) { Acc() }
        fun windowStart(i: Int) = fromMillis + i * intervalMillis
        fun windowEnd(i: Int) = if (i == n - 1) end else fromMillis + (i + 1) * intervalMillis
        fun indexOf(t: Long) = ((t - fromMillis) / intervalMillis).toInt()
        val interpolating = interpolation != HistoryInterpolation.NONE
        val linear = interpolation == HistoryInterpolation.LINEAR
        val weighting = mode == HistoryAggregate.AVERAGE

        // Edge k is the start of window k; edge n is the end of the range.
        val edges = arrayOfNulls<Double>(n + 1)
        var nextEdge = 0
        fun edgeTime(k: Int) = if (k == n) end else windowStart(k)

        /** Value at [t] while [prev] is in effect and [next] is the following row (or null). */
        fun valueAt(prev: HistorySample?, next: HistorySample?, t: Long): Double? {
            if (prev == null || prev.quality.level != Level.GOOD) return null
            val pv = ValueCodec.numeric(prev.value) ?: return null
            if (!linear || next == null || next.quality.level != Level.GOOD || next.timestampMillis <= prev.timestampMillis) return pv
            val nv = ValueCodec.numeric(next.value) ?: return pv
            val f = (t - prev.timestampMillis).toDouble() / (next.timestampMillis - prev.timestampMillis)
            return pv + (nv - pv) * f
        }

        fun fillEdges(prev: HistorySample?, next: HistorySample?, before: Long) {
            while (nextEdge <= n && edgeTime(nextEdge) < before) {
                edges[nextEdge] = valueAt(prev, next, edgeTime(nextEdge))
                nextEdge++
            }
        }

        /** Adds the area under [prev]'s segment, from max(its start, from) to [b], to the windows it crosses. */
        fun weigh(prev: HistorySample?, next: HistorySample?, b: Long) {
            if (prev == null) return
            val a = maxOf(prev.timestampMillis, fromMillis)
            if (b <= a || valueAt(prev, next, a) == null) return
            var i = indexOf(a)
            while (i < n && windowStart(i) < b) {
                val x = maxOf(a, windowStart(i))
                val y = minOf(b, windowEnd(i))
                val dt = y - x
                if (dt > 0) {
                    val vx = valueAt(prev, next, x)!!
                    val vy = valueAt(prev, next, y)!!
                    // Exact for a constant (vx == vy) and for a straight line.
                    acc[i].weighted += if (vx == vy) vx * dt else (vx + vy) / 2 * dt
                    acc[i].weight += dt
                }
                i++
            }
        }

        var prev: HistorySample? = if (weighting || interpolating) reader.latestBefore(tagPath, fromMillis) else null

        reader.scan(tagPath, fromMillis, toMillis) { s ->
            val i = indexOf(s.timestampMillis)
            if (i !in 0 until n) return@scan
            val a = acc[i]
            a.rows++
            a.last = s.value
            if (s.quality.level == Level.GOOD) {
                ValueCodec.numeric(s.value)?.let { v ->
                    a.good++
                    a.sum += v
                    if (v < a.min) a.min = v
                    if (v > a.max) a.max = v
                }
            }
            if (weighting) weigh(prev, s, s.timestampMillis)
            if (interpolating) fillEdges(prev, s, s.timestampMillis)
            if (weighting || interpolating) prev = s
        }
        // Linear needs the row after the range to place the last edges; Step and None hold the last value.
        val after = if (linear && prev != null && toMillis < Long.MAX_VALUE) {
            reader.query(HistoryQuery(tagPath, toMillis + 1, Long.MAX_VALUE, 1)).firstOrNull()
        } else {
            null
        }
        if (weighting) weigh(prev, if (linear) after else null, end)
        if (interpolating) fillEdges(prev, after, Long.MAX_VALUE)

        return List(n) { i ->
            val a = acc[i]
            val startValue = edges[i]
            val endValue = edges[i + 1]
            // The end edge is the window's open end. On a line it is the limit from inside the window; with
            // Step it can be a row of the next window, and the value held up to it is already counted (a row
            // inside the window, or the start value).
            val inside = if (!interpolating) emptyList() else if (linear) listOfNotNull(startValue, endValue) else listOfNotNull(startValue)
            for (v in inside) {
                if (v < a.min) a.min = v
                if (v > a.max) a.max = v
            }
            val extremes = a.good > 0 || inside.isNotEmpty()
            val value: Any? = when (mode) {
                HistoryAggregate.AVERAGE -> if (a.weight > 0) a.weighted / a.weight else null
                HistoryAggregate.SIMPLE_AVERAGE -> if (a.good > 0) a.sum / a.good else null
                HistoryAggregate.SUM -> if (a.good > 0) a.sum else null
                HistoryAggregate.MINIMUM -> if (extremes) a.min else null
                HistoryAggregate.MAXIMUM -> if (extremes) a.max else null
                HistoryAggregate.RANGE -> if (extremes) a.max - a.min else null
                HistoryAggregate.LAST_VALUE -> when {
                    a.rows > 0 -> a.last
                    linear -> endValue
                    interpolating -> startValue
                    else -> null
                }
                HistoryAggregate.COUNT -> a.rows
            }
            AggregateWindow(windowStart(i), windowEnd(i), value, a.rows, startValue, endValue)
        }
    }

    private class Acc {
        var rows = 0
        var good = 0
        var sum = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        var last: Any? = null
        var weighted = 0.0
        var weight = 0L
    }
}
