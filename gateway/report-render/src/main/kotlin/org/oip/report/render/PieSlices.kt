package org.oip.report.render

import java.util.Base64

/**
 * Pie slices from (label, value) pairs (FEATURES REP-004), mirrored by web/components/src/report/pie.ts.
 * Rows without a positive finite value draw nothing. At most [MAX_SLICES] slices are drawn: the smallest beyond
 * that are summed into one "Other" slice, which is last. Fractions are of the positive total.
 */
internal object PieSlices {
    const val MAX_SLICES = 12

    class Slice(val label: String, val value: Double, val fraction: Double)

    fun of(rows: List<Pair<String, Double?>>): List<Slice> {
        val positive = rows.filter { (_, v) -> v != null && v.isFinite() && v > 0.0 }.map { it.first to it.second!! }
        val total = positive.sumOf { it.second }
        if (positive.isEmpty() || total <= 0.0) return emptyList()
        var kept = positive
        var other = 0.0
        if (positive.size > MAX_SLICES) {
            // Keep the largest MAX_SLICES - 1 in their original order.
            val keepIdx = positive.indices.sortedByDescending { positive[it].second }.take(MAX_SLICES - 1).toSet()
            kept = positive.filterIndexed { i, _ -> i in keepIdx }
            other = positive.filterIndexed { i, _ -> i !in keepIdx }.sumOf { it.second }
        }
        val out = kept.map { Slice(it.first, it.second, it.second / total) }.toMutableList()
        if (other > 0.0) out += Slice("Other", other, other / total)
        return out
    }

    /** "42.5%": one decimal place, half up. */
    fun percent(fraction: Double): String = Formatting.number(fraction * 100.0, 1, false) + "%"
}

/** Picture data of an image component: only base64 `data:image/png` and `data:image/jpeg` URIs, at most 4 MB decoded. */
internal object ImageData {
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun decode(src: String): ByteArray? {
        val m = Regex("^data:image/(png|jpeg|jpg);base64,([A-Za-z0-9+/=\\s]+)$").matchEntire(src) ?: return null
        return try {
            Base64.getMimeDecoder().decode(m.groupValues[2]).takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
