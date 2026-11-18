package org.oip.tags

import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * One column of a [Dataset]. [type] is a scalar tag type; a column is not itself a dataset or a document.
 */
data class DatasetColumn(val name: String, val type: DataType)

/**
 * A dataset tag value: named columns and rows of cells (TAG-014).
 *
 * The wire form, used by REST, scripts, persistence and the OPC UA server (as text), is
 * `{"columns":[{"name","type"}],"rows":[[...]]}`. Date-time cells are ISO-8601 text.
 * A column list of names, with no types, takes its type from the cells in that column.
 */
data class Dataset(
    val columns: List<DatasetColumn>,
    val rows: List<List<Any?>>,
) {
    @JsonValue
    fun toWire(): Map<String, Any?> = linkedMapOf(
        "columns" to columns.map { linkedMapOf("name" to it.name, "type" to it.type.label()) },
        "rows" to rows.map { row -> row.map { wireCell(it) } },
    )

    fun toJson(): String = MAPPER.writeValueAsString(this)

    companion object {
        /** Locked by DatasetTagTest. A wider write is refused. */
        const val MAX_COLUMNS = 256

        /** Locked by DatasetTagTest. A longer write is refused. */
        const val MAX_ROWS = 100_000

        private const val MAX_NAME = 128
        private val MAPPER = ObjectMapper()

        internal fun parse(value: Any?): Dataset = when (value) {
            is Dataset -> fromMap(value.toWire())
            is Map<*, *> -> fromMap(value)
            is String -> fromMap(jsonObject(value))
            else -> throw TypeFail("cannot convert '${preview(value)}' to Dataset")
        }

        private fun jsonObject(text: String): Map<*, *> {
            val parsed = try {
                MAPPER.readValue(text, Any::class.java)
            } catch (_: Exception) {
                throw TypeFail("cannot convert a string to Dataset")
            }
            return parsed as? Map<*, *> ?: throw TypeFail("cannot convert a string to Dataset")
        }

        private fun fromMap(raw: Map<*, *>): Dataset {
            val columnsRaw = raw["columns"] as? List<*> ?: throw TypeFail("a dataset needs a columns array")
            val rowsRaw = raw["rows"] as? List<*> ?: throw TypeFail("a dataset needs a rows array")
            if (columnsRaw.size > MAX_COLUMNS) throw TypeFail("a dataset has at most $MAX_COLUMNS columns")
            if (rowsRaw.size > MAX_ROWS) throw TypeFail("a dataset has at most $MAX_ROWS rows")
            val pending = columnsRaw.map { columnSpec(it) }
            val seen = HashSet<String>(pending.size)
            for (column in pending) {
                if (!seen.add(column.name)) throw TypeFail("dataset column '${column.name}' is duplicated")
            }
            val types = pending.mapIndexed { index, column ->
                column.type ?: infer(rowsRaw.map { cellAt(it, index, pending.size) })
            }
            val columns = pending.mapIndexed { index, column -> DatasetColumn(column.name, types[index]) }
            val rows = rowsRaw.map { rowOf(it, columns) }
            return Dataset(columns, rows)
        }

        private fun columnSpec(raw: Any?): PendingColumn = when (raw) {
            is String -> PendingColumn(checkName(raw), null)
            is Map<*, *> -> {
                val name = raw["name"] as? String ?: throw TypeFail("a dataset column needs a name")
                val type = when (val declared = raw["type"]) {
                    null -> null
                    is String -> columnType(declared)
                    else -> throw TypeFail("dataset column type must be a string")
                }
                PendingColumn(checkName(name), type)
            }
            else -> throw TypeFail("a dataset column must be a name or an object with name and type")
        }

        private fun checkName(raw: String): String {
            val name = raw.trim()
            if (name.isEmpty() || name.length > MAX_NAME) throw TypeFail("dataset column name is empty or too long")
            return name
        }

        private fun columnType(raw: String): DataType {
            val type = DataType.fromName(raw) ?: throw TypeFail("unknown dataset column type '$raw'")
            if (type == DataType.DATASET || type == DataType.DOCUMENT) {
                throw TypeFail("dataset column type '${type.label()}' is not a cell type")
            }
            return type
        }

        private fun cellAt(row: Any?, index: Int, width: Int): Any? {
            val cells = row as? List<*> ?: throw TypeFail("a dataset row must be an array")
            if (cells.size != width) throw TypeFail("dataset row length does not match the columns")
            return cells[index]
        }

        private fun infer(cells: List<Any?>): DataType {
            val present = cells.filterNotNull()
            if (present.isEmpty()) return DataType.STRING
            if (present.all { it is Boolean }) return DataType.BOOLEAN
            if (present.all { it is String }) return DataType.STRING
            if (present.all { it is Instant }) return DataType.DATETIME
            if (present.all { it is Number }) {
                val integral = present.all { n ->
                    val d = (n as Number).toDouble()
                    !d.isNaN() && !d.isInfinite() && d % 1.0 == 0.0
                }
                if (!integral) return if (present.all { it is Float }) DataType.FLOAT else DataType.DOUBLE
                val fitsInt = present.all { n ->
                    val d = (n as Number).toDouble()
                    d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble()
                }
                return if (fitsInt) DataType.INT32 else DataType.INT64
            }
            throw TypeFail("cannot infer a dataset column type")
        }

        private fun rowOf(raw: Any?, columns: List<DatasetColumn>): List<Any?> {
            val row = raw as? List<*> ?: throw TypeFail("a dataset row must be an array")
            if (row.size != columns.size) throw TypeFail("dataset row length does not match the columns")
            return List(columns.size) { index -> coerceCell(row[index], columns[index].type) }
        }

        private fun coerceCell(value: Any?, type: DataType): Any? {
            if (value == null) return null
            if (value is List<*> || value is Map<*, *>) throw TypeFail("a dataset cell must be a scalar")
            if (type == DataType.INT32 || type == DataType.INT64) {
                val n = asDouble(value)
                if (n != null && (n % 1.0 != 0.0 || n.isNaN() || n.isInfinite())) {
                    throw TypeFail("'$value' is not a whole number")
                }
            }
            return coerce(value, type)
        }

        private fun preview(value: Any?): String {
            val text = value.toString()
            return if (text.length > 80) text.take(80) + "..." else text
        }

        private data class PendingColumn(val name: String, val type: DataType?)
    }
}

private fun wireCell(value: Any?): Any? = when (value) {
    is Instant -> value.toString()
    else -> value
}
