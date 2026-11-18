package org.oip.simulator

/**
 * CSV program import and export. Four columns, in order: Time Interval, Browse Path, Value Source,
 * Data Type. A leading `_Meta:` on a browse path is removed on export, because the programmable
 * simulator page shows that prefix on the OPC item path and not in the browse path. Import keeps
 * the browse path as written, so a caller who needs the legacy item path puts `_Meta:` back in
 * the Browse Path column.
 *
 */
object ProgramCsv {
    const val HEADER = "Time Interval,Browse Path,Value Source,Data Type"

    fun export(instructions: List<SimInstruction>): String {
        val out = StringBuilder(HEADER.length + instructions.size * 32)
        out.append(HEADER).append('\n')
        for (row in instructions) {
            out.append(field(row.timeInterval.toString())).append(',')
            out.append(field(stripLegacyPrefix(row.browsePath))).append(',')
            out.append(field(row.valueSource)).append(',')
            out.append(field(row.dataType)).append('\n')
        }
        return out.toString()
    }

    fun import(csv: String): List<SimInstruction> {
        val rows = parse(csv.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return emptyList()
        val first = rows.first()
        val header = first.isNotEmpty() && first[0].trim().toIntOrNull() == null
        val body = if (header) rows.drop(1) else rows
        return body.map { cells ->
            if (cells.size != 4) {
                throw IllegalArgumentException("instruction rows need 4 columns, found ${cells.size}")
            }
            val interval = cells[0].toIntOrNull()
                ?: throw IllegalArgumentException("time interval '${cells[0]}' is not an integer")
            if (interval < 0) throw IllegalArgumentException("time interval must be >= 0")
            val path = cells[1]
            val source = cells[2]
            val type = cells[3]
            if (path.isEmpty()) throw IllegalArgumentException("browse path is blank")
            if (source.isEmpty()) throw IllegalArgumentException("value source is blank")
            if (type.isEmpty()) throw IllegalArgumentException("data type is blank")
            parseSimType(type)
            SimInstruction(interval, path, source, type)
        }
    }

    /** One leading [LEGACY_ITEM_PREFIX]. A path that does not have it is unchanged. */
    fun stripLegacyPrefix(browsePath: String): String =
        if (browsePath.startsWith(LEGACY_ITEM_PREFIX)) browsePath.removePrefix(LEGACY_ITEM_PREFIX) else browsePath

    private fun field(value: String): String {
        val quote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            value.trim() != value
        if (!quote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val field = StringBuilder()
        var i = 0
        var quoted = false
        var fieldQuoted = false
        fun finishField() {
            row += if (fieldQuoted) field.toString() else field.toString().trim()
            field.setLength(0)
            fieldQuoted = false
        }
        fun finishRow() {
            if (row.isEmpty() && field.isEmpty() && !fieldQuoted) return
            finishField()
            if (row.size == 1 && row[0].isEmpty()) {
                row.clear()
                return
            }
            rows += row.toList()
            row.clear()
        }
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    quoted = false
                    i++
                    continue
                }
                field.append(ch)
                i++
                continue
            }
            when (ch) {
                '"' -> {
                    if (field.isEmpty()) {
                        quoted = true
                        fieldQuoted = true
                    } else {
                        field.append(ch)
                    }
                    i++
                }
                ',' -> {
                    finishField()
                    i++
                }
                '\n' -> {
                    finishRow()
                    i++
                }
                '\r' -> {
                    finishRow()
                    i++
                    if (i < text.length && text[i] == '\n') i++
                }
                else -> {
                    field.append(ch)
                    i++
                }
            }
        }
        if (quoted) throw IllegalArgumentException("unterminated quote in program csv")
        if (field.isNotEmpty() || fieldQuoted || row.isNotEmpty()) finishRow()
        return rows
    }
}
