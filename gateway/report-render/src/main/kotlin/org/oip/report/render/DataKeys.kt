package org.oip.report.render

import com.fasterxml.jackson.databind.JsonNode
import org.oip.report.render.model.ReportResult
import java.time.ZoneId

/**
 * `@Name@` in report text. A known name is replaced; an unknown name is left as written.
 * Built-ins are `Date`, `Report`, and `Page`. A parameter is its name. A column from exactly one
 * dataset's first row is that column name, and `dataset.column` always names that cell.
 */
internal object DataKeys {
    private val KEY = Regex("@([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)@")

    fun apply(text: String, values: Map<String, String>): String =
        KEY.replace(text) { match -> values[match.groupValues[1]] ?: match.value }

    fun values(result: ReportResult, zone: ZoneId, page: Int): Map<String, String> {
        val out = linkedMapOf(
            "Date" to Formatting.timestamp(result.generatedAt, Formatting.DEFAULT_TIMESTAMP_PATTERN, zone),
            "Report" to result.report.name,
            "Page" to page.coerceAtLeast(1).toString(),
        )
        for (parameter in result.parameters) {
            val value = parameter.value ?: continue
            out[parameter.name] = if (value.isValueNode) value.asText() else value.toString()
        }
        val plain = linkedMapOf<String, String>()
        val duplicate = HashSet<String>()
        for (dataset in result.datasets) {
            val row = dataset.rows.firstOrNull() ?: continue
            dataset.columns.forEachIndexed { index, column ->
                val text = cell(row.getOrNull(index))
                out["${dataset.name}.${column.name}"] = text
                if (column.name in duplicate) return@forEachIndexed
                if (plain.containsKey(column.name)) {
                    duplicate += column.name
                    plain.remove(column.name)
                } else {
                    plain[column.name] = text
                }
            }
        }
        out.putAll(plain)
        return out
    }

    private fun cell(node: JsonNode?): String = when {
        node == null || node.isNull -> ""
        node.isValueNode -> node.asText()
        else -> node.toString()
    }
}
