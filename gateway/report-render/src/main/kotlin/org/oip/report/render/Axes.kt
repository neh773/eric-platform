package org.oip.report.render

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Axis tick computation, mirrored by web/components/src/report/axes.ts. */
object Axes {
    data class NumericAxis(val min: Double, val max: Double, val step: Double, val ticks: List<Double>, val decimals: Int)

    data class TimeAxis(val ticks: List<Long>, val pattern: String)

    /** "Nice" linear ticks (Heckbert) covering [lo, hi], about [target] intervals. */
    fun numeric(lo: Double, hi: Double, fixedMin: Double? = null, fixedMax: Double? = null, target: Int = 5): NumericAxis {
        var a = fixedMin ?: lo
        var b = fixedMax ?: hi
        if (!a.isFinite() || !b.isFinite()) { a = 0.0; b = 1.0 }
        if (a > b) { val t = a; a = b; b = t }
        if (a == b) {
            val pad = if (a == 0.0) 1.0 else abs(a) * 0.1
            if (fixedMin == null) a -= pad
            if (fixedMax == null) b += pad
            if (a == b) b = a + 1.0
        }
        val step = niceNum(niceNum(b - a, false) / max(1, target), true)
        val min = fixedMin ?: (floor(a / step) * step)
        val max = fixedMax ?: (ceil(b / step) * step)
        val ticks = ArrayList<Double>()
        var t = ceil(min / step - 1e-9) * step
        while (t <= max + step * 1e-9 && ticks.size < 50) {
            ticks += if (abs(t) < step * 1e-9) 0.0 else t
            t += step
        }
        val decimals = max(0, -floor(log10(step) + 1e-9).toInt())
        return NumericAxis(min, max, step, ticks, decimals)
    }

    private fun niceNum(range: Double, round: Boolean): Double {
        val exp = floor(log10(range))
        val f = range / 10.0.pow(exp)
        val nf = if (round) {
            when { f < 1.5 -> 1.0; f < 3.0 -> 2.0; f < 7.0 -> 5.0; else -> 10.0 }
        } else {
            when { f <= 1.0 -> 1.0; f <= 2.0 -> 2.0; f <= 5.0 -> 5.0; else -> 10.0 }
        }
        return nf * 10.0.pow(exp)
    }

    private const val S = 1000L
    private const val M = 60 * S
    private const val H = 60 * M
    private const val D = 24 * H
    private val TIME_STEPS = longArrayOf(
        S, 2 * S, 5 * S, 10 * S, 15 * S, 30 * S, M, 2 * M, 5 * M, 10 * M, 15 * M, 30 * M,
        H, 2 * H, 3 * H, 6 * H, 12 * H, D, 2 * D, 7 * D, 14 * D, 30 * D, 90 * D, 365 * D,
    )

    /**
     * Ticks at a round step aligned in [zone] (using the zone offset at [from]), at most [maxTicks] of them.
     * The label pattern follows the step and span unless [pattern] overrides it.
     */
    fun time(from: Long, to: Long, zone: ZoneId, maxTicks: Int, pattern: String? = null): TimeAxis {
        val span = max(1L, to - from)
        val step = TIME_STEPS.firstOrNull { span / it <= max(1, maxTicks) } ?: TIME_STEPS.last()
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(from)).totalSeconds * 1000L
        var t = Math.floorDiv(from + offset, step) * step - offset
        if (t < from) t += step
        val ticks = ArrayList<Long>()
        while (t <= to && ticks.size <= maxTicks) { ticks += t; t += step }
        val chosen = pattern ?: when {
            step < M -> "HH:mm:ss"
            step >= D -> "MM-dd"
            span > D -> "MM-dd HH:mm"
            else -> "HH:mm"
        }
        return TimeAxis(ticks, chosen)
    }
}
