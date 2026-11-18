package org.oip.report.render

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Locale-neutral value formatting shared by the PDF renderer. The web preview
 * (web/components/src/report/format.ts) implements the same rules so a table looks the same in both:
 * - numbers: `.` decimal separator, optional `,` thousands grouping, HALF_UP rounding to `decimals`;
 *   without `decimals`, at most 3 decimals with trailing zeros removed.
 * - timestamps: epoch millis rendered in the layout timezone with the pattern tokens
 *   `yyyy MM dd HH mm ss SSS`; every other character is copied literally.
 * - booleans: `true` / `false`. Null: the column's `nullText` (default empty).
 */
object Formatting {
    const val DEFAULT_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private const val AUTO_DECIMALS = 3

    fun number(value: Double, decimals: Int?, grouping: Boolean): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        var bd = BigDecimal.valueOf(value)
        bd = if (decimals != null) {
            bd.setScale(decimals.coerceIn(0, 10), RoundingMode.HALF_UP)
        } else {
            bd.setScale(AUTO_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        }
        if (bd.signum() == 0) bd = bd.abs() // no "-0.00"
        val plain = bd.toPlainString()
        if (!grouping) return plain
        val negative = plain.startsWith("-")
        val unsigned = plain.removePrefix("-")
        val intPart = unsigned.substringBefore('.')
        val frac = unsigned.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return (if (negative) "-" else "") + grouped + (if (frac.isEmpty()) "" else ".$frac")
    }

    fun timestamp(epochMillis: Long, pattern: String, zone: ZoneId): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val token = TOKENS.firstOrNull { pattern.startsWith(it, i) }
            if (token == null) { out.append(pattern[i]); i++; continue }
            out.append(
                when (token) {
                    "yyyy" -> t.year.toString().padStart(4, '0')
                    "MM" -> pad2(t.monthValue)
                    "dd" -> pad2(t.dayOfMonth)
                    "HH" -> pad2(t.hour)
                    "mm" -> pad2(t.minute)
                    "ss" -> pad2(t.second)
                    else -> (t.nano / 1_000_000).toString().padStart(3, '0') // SSS
                },
            )
            i += token.length
        }
        return out.toString()
    }

    private val TOKENS = listOf("yyyy", "SSS", "MM", "dd", "HH", "mm", "ss")
    private fun pad2(v: Int) = v.toString().padStart(2, '0')

    /** Formats one table cell according to the column format (or the contract column type when `auto`). */
    fun cell(value: JsonNode?, columnType: String, format: CellFormat, zone: ZoneId): String {
        if (value == null || value.isNull || value.isMissingNode) return format.nullText
        val kind = if (format.kind == FormatKind.AUTO) {
            when (columnType) {
                "number" -> FormatKind.NUMBER
                "timestamp" -> FormatKind.TIMESTAMP
                "boolean" -> FormatKind.BOOLEAN
                else -> FormatKind.TEXT
            }
        } else format.kind
        return when (kind) {
            FormatKind.NUMBER -> if (value.isNumber) number(value.asDouble(), format.decimals, format.grouping) else value.asText()
            FormatKind.TIMESTAMP -> if (value.isIntegralNumber) timestamp(value.asLong(), format.pattern ?: DEFAULT_TIMESTAMP_PATTERN, zone) else value.asText()
            FormatKind.BOOLEAN -> if (value.isBoolean) value.asBoolean().toString() else value.asText()
            FormatKind.TEXT, FormatKind.AUTO -> if (value.isValueNode) value.asText() else value.toString()
        }
    }
}
