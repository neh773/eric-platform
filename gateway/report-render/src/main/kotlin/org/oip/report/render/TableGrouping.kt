package org.oip.report.render

import com.fasterxml.jackson.databind.JsonNode

/**
 * Table helpers shared by the PDF renderer and mirrored by web/components/src/report/tableGroups.ts: grouping rows
 * by a key column (FEATURES REP-050) and auto-sized column widths (FEATURES REP-016).
 */
internal object TableGrouping {
    class Group(val key: JsonNode?, val rows: List<List<JsonNode?>>)

    /** Rows grouped by the value in column [index], groups in order of first appearance, rows in their original order. */
    fun groups(rows: List<List<JsonNode?>>, index: Int): List<Group> {
        val order = LinkedHashMap<String, Pair<JsonNode?, MutableList<List<JsonNode?>>>>()
        for (row in rows) {
            val key = row.getOrNull(index)?.takeUnless { it.isNull }
            val id = if (key == null) "\u0000" else key.toString()
            order.getOrPut(id) { key to ArrayList() }.second += row
        }
        return order.values.map { (key, list) -> Group(key, list) }
    }

    /** Number of rows looked at when sizing columns, so a huge table costs a bounded amount. */
    const val AUTO_SAMPLE = 200
    private const val MIN_CHARS = 4
    private const val MAX_CHARS = 40

    /** Characters that decide a column's width: the longest of its header and sampled cells. */
    fun textLength(header: String, cells: List<String>): Int =
        maxOf(header.length, cells.maxOfOrNull { it.length } ?: 0)

    /** Relative column widths from text lengths, each clamped to 4..40 characters so no column vanishes or takes the page. */
    fun autoWeights(lengths: List<Int>): List<Float> = lengths.map { it.coerceIn(MIN_CHARS, MAX_CHARS).toFloat() }
}
