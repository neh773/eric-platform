package org.oip.db

/**
 * The SQL a database needs spelled its own way (DAT-016, DAT-023, DAT-017, DAT-024, DAT-027): row paging, identifier
 * quoting, the current time, column types, table creation, and the validation query. A translator is a small set of
 * templates, so a database this gateway has no built-in support for is described with a `SqlTranslator` config
 * resource instead of code.
 *
 * Templates use `{name}` placeholders only. Values are inserted in one pass and never searched again, so a table or
 * column name cannot add a placeholder. Identifiers are checked against [IDENTIFIER] before they are quoted.
 *
 * Built-in translators have upper-case names; resource names are lower case, so the two never collide.
 * The Oracle, DB2 and Snowflake templates follow those databases' public SQL references: row limiting with
 * `OFFSET … FETCH` (Oracle 12c and later, DB2 11.1 and later), `LIMIT … OFFSET` (Snowflake), `FROM DUAL`, and
 * `SYSIBM.SYSDUMMY1` for a FROM-less select.
 */
data class SqlTranslator(
    val name: String,
    val quoteOpen: String = "\"",
    val quoteClose: String = "\"",
    /** `{sql}` is a statement with its own ORDER BY where the database needs one; `{limit}` and `{offset}` are whole numbers. */
    val pageTemplate: String,
    val limitTemplate: String,
    val currentTimestamp: String,
    val validationQuery: String,
    val createTableTemplate: String = "CREATE TABLE {table} ({columns})",
    /** Keys: string, text, integer, long, double, boolean, datetime. `{length}` is replaced for string. */
    val columnTypes: Map<String, String>,
    val trueLiteral: String = "TRUE",
    val falseLiteral: String = "FALSE",
    val builtIn: Boolean = false,
) {
    data class Column(val name: String, val type: String, val length: Int = 255, val notNull: Boolean = false, val primaryKey: Boolean = false)

    fun quote(identifier: String): String {
        if (!IDENTIFIER.matches(identifier)) throw QueryRejected("'$identifier' is not a plain identifier")
        return quoteOpen + identifier + quoteClose
    }

    fun quoteTable(table: String): String = table.split('.').joinToString(".") { quote(it) }

    /** `{sql}` with the rows after the first [offset] and at most [limit] of them. */
    fun page(sql: String, limit: Int, offset: Int): String {
        if (limit < 1 || offset < 0) throw QueryRejected("limit must be at least 1 and offset at least 0")
        return fill(pageTemplate, mapOf("sql" to sql.trim().trimEnd(';'), "limit" to limit.toString(), "offset" to offset.toString()))
    }

    fun limit(sql: String, limit: Int): String {
        if (limit < 1) throw QueryRejected("limit must be at least 1")
        return fill(limitTemplate, mapOf("sql" to sql.trim().trimEnd(';'), "limit" to limit.toString()))
    }

    fun columnType(type: String, length: Int = 255): String {
        val template = columnTypes[type] ?: throw QueryRejected("translator '$name' has no column type '$type'")
        if (length !in 1..4000) throw QueryRejected("length must be 1 to 4000")
        return fill(template, mapOf("length" to length.toString()))
    }

    fun booleanLiteral(value: Boolean): String = if (value) trueLiteral else falseLiteral

    fun createTable(table: String, columns: List<Column>): String {
        if (columns.isEmpty()) throw QueryRejected("a table needs at least one column")
        val defs = columns.joinToString(", ") { c ->
            buildString {
                append(quote(c.name)).append(' ').append(columnType(c.type, c.length))
                if (c.notNull) append(" NOT NULL")
            }
        }
        val keys = columns.filter { it.primaryKey }
        val body = if (keys.isEmpty()) defs else "$defs, PRIMARY KEY (${keys.joinToString(", ") { quote(it.name) }})"
        return fill(createTableTemplate, mapOf("table" to quoteTable(table), "columns" to body))
    }

    fun view(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "builtIn" to builtIn,
        "quoteOpen" to quoteOpen,
        "quoteClose" to quoteClose,
        "pageTemplate" to pageTemplate,
        "limitTemplate" to limitTemplate,
        "currentTimestamp" to currentTimestamp,
        "validationQuery" to validationQuery,
        "createTableTemplate" to createTableTemplate,
        "columnTypes" to columnTypes,
        "trueLiteral" to trueLiteral,
        "falseLiteral" to falseLiteral,
    )

    companion object {
        val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_$#]{0,127}$")
        private val PLACEHOLDER = Regex("\\{([A-Za-z]+)}")
        val COLUMN_TYPES = listOf("string", "text", "integer", "long", "double", "boolean", "datetime")
        private val FIELDS = setOf(
            "quoteOpen", "quoteClose", "pageTemplate", "limitTemplate", "currentTimestamp", "validationQuery",
            "createTableTemplate", "columnTypes", "trueLiteral", "falseLiteral",
        )

        /** One pass, so a value is never scanned for placeholders. */
        private fun fill(template: String, values: Map<String, String>): String =
            PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }

        private fun postgresLike(name: String, open: String = "\"", close: String = "\"") = SqlTranslator(
            name = name, quoteOpen = open, quoteClose = close,
            pageTemplate = "{sql} LIMIT {limit} OFFSET {offset}", limitTemplate = "{sql} LIMIT {limit}",
            currentTimestamp = "CURRENT_TIMESTAMP", validationQuery = "SELECT 1",
            columnTypes = mapOf(
                "string" to "VARCHAR({length})", "text" to "TEXT", "integer" to "INTEGER", "long" to "BIGINT",
                "double" to "DOUBLE PRECISION", "boolean" to "BOOLEAN", "datetime" to "TIMESTAMP",
            ),
            builtIn = true,
        )

        val POSTGRES = postgresLike("POSTGRES")
        val SQLITE = postgresLike("SQLITE").copy(
            columnTypes = mapOf(
                "string" to "TEXT", "text" to "TEXT", "integer" to "INTEGER", "long" to "INTEGER",
                "double" to "REAL", "boolean" to "INTEGER", "datetime" to "TEXT",
            ),
            trueLiteral = "1", falseLiteral = "0",
        )
        val MYSQL = postgresLike("MYSQL", "`", "`").copy(
            columnTypes = mapOf(
                "string" to "VARCHAR({length})", "text" to "TEXT", "integer" to "INT", "long" to "BIGINT",
                "double" to "DOUBLE", "boolean" to "BOOLEAN", "datetime" to "DATETIME",
            ),
        )
        val MSSQL = SqlTranslator(
            name = "MSSQL", quoteOpen = "[", quoteClose = "]",
            pageTemplate = "{sql} OFFSET {offset} ROWS FETCH NEXT {limit} ROWS ONLY",
            limitTemplate = "{sql} OFFSET 0 ROWS FETCH NEXT {limit} ROWS ONLY",
            currentTimestamp = "SYSDATETIME()", validationQuery = "SELECT 1",
            columnTypes = mapOf(
                "string" to "NVARCHAR({length})", "text" to "NVARCHAR(MAX)", "integer" to "INT", "long" to "BIGINT",
                "double" to "FLOAT", "boolean" to "BIT", "datetime" to "DATETIME2",
            ),
            trueLiteral = "1", falseLiteral = "0", builtIn = true,
        )
        val ORACLE = SqlTranslator(
            name = "ORACLE",
            pageTemplate = "{sql} OFFSET {offset} ROWS FETCH NEXT {limit} ROWS ONLY",
            limitTemplate = "{sql} FETCH FIRST {limit} ROWS ONLY",
            currentTimestamp = "SYSTIMESTAMP", validationQuery = "SELECT 1 FROM DUAL",
            columnTypes = mapOf(
                "string" to "VARCHAR2({length})", "text" to "CLOB", "integer" to "NUMBER(10)", "long" to "NUMBER(19)",
                "double" to "BINARY_DOUBLE", "boolean" to "NUMBER(1)", "datetime" to "TIMESTAMP",
            ),
            trueLiteral = "1", falseLiteral = "0", builtIn = true,
        )
        val DB2 = SqlTranslator(
            name = "DB2",
            pageTemplate = "{sql} OFFSET {offset} ROWS FETCH FIRST {limit} ROWS ONLY",
            limitTemplate = "{sql} FETCH FIRST {limit} ROWS ONLY",
            currentTimestamp = "CURRENT TIMESTAMP", validationQuery = "SELECT 1 FROM SYSIBM.SYSDUMMY1",
            columnTypes = mapOf(
                "string" to "VARCHAR({length})", "text" to "CLOB", "integer" to "INTEGER", "long" to "BIGINT",
                "double" to "DOUBLE", "boolean" to "SMALLINT", "datetime" to "TIMESTAMP",
            ),
            trueLiteral = "1", falseLiteral = "0", builtIn = true,
        )
        val SNOWFLAKE = SqlTranslator(
            name = "SNOWFLAKE",
            pageTemplate = "{sql} LIMIT {limit} OFFSET {offset}", limitTemplate = "{sql} LIMIT {limit}",
            currentTimestamp = "CURRENT_TIMESTAMP()", validationQuery = "SELECT 1",
            columnTypes = mapOf(
                "string" to "VARCHAR({length})", "text" to "VARCHAR", "integer" to "NUMBER(10,0)", "long" to "NUMBER(19,0)",
                "double" to "FLOAT", "boolean" to "BOOLEAN", "datetime" to "TIMESTAMP_NTZ",
            ),
            builtIn = true,
        )

        val BUILT_IN: Map<String, SqlTranslator> =
            listOf(POSTGRES, SQLITE, MSSQL, MYSQL, ORACLE, DB2, SNOWFLAKE).associateBy { it.name }

        /** A translator from the fields of a `SqlTranslator` resource. Every template is checked here. */
        fun fromFields(name: String, fields: Map<String, Any?>): SqlTranslator {
            val unknown = fields.keys - FIELDS
            if (unknown.isNotEmpty()) throw QueryRejected("unknown fields: ${unknown.sorted().joinToString(",")}")
            fun text(key: String, default: String? = null, max: Int = 500): String {
                val raw = fields[key] ?: return default ?: throw QueryRejected("$key is required")
                val s = raw as? String ?: throw QueryRejected("$key must be a string")
                if (s.length > max || s.any { it == '\u0000' }) throw QueryRejected("$key is invalid")
                return s
            }
            val open = text("quoteOpen", "\"", 4)
            val close = text("quoteClose", "\"", 4)
            if (open.isEmpty() || close.isEmpty() || open.any { it.isLetterOrDigit() || it == '\'' } || close.any { it.isLetterOrDigit() || it == '\'' }) {
                throw QueryRejected("identifier quotes must be punctuation other than a single quote")
            }
            val page = text("pageTemplate")
            val limit = text("limitTemplate")
            val create = text("createTableTemplate", "CREATE TABLE {table} ({columns})")
            val validation = text("validationQuery", "SELECT 1").trim()
            if (!validation.startsWith("SELECT", ignoreCase = true) || validation.contains(';')) {
                throw QueryRejected("validation query must be one SELECT")
            }
            requirePlaceholders("pageTemplate", page, setOf("sql", "limit", "offset"), setOf("sql", "limit", "offset"))
            requirePlaceholders("limitTemplate", limit, setOf("sql", "limit"), setOf("sql", "limit"))
            requirePlaceholders("createTableTemplate", create, setOf("table", "columns"), setOf("table", "columns"))
            val typesRaw = fields["columnTypes"] as? Map<*, *> ?: throw QueryRejected("columnTypes is required")
            val types = linkedMapOf<String, String>()
            for ((k, v) in typesRaw) {
                val key = k as? String ?: throw QueryRejected("column type names must be strings")
                if (key !in COLUMN_TYPES) throw QueryRejected("column type '$key' is not one of ${COLUMN_TYPES.joinToString(", ")}")
                val value = v as? String ?: throw QueryRejected("column type '$key' must be a string")
                if (value.isBlank() || value.length > 100 || value.contains(';')) throw QueryRejected("column type '$key' is invalid")
                requirePlaceholders("column type '$key'", value, emptySet(), setOf("length"))
                types[key] = value
            }
            val missing = COLUMN_TYPES - types.keys
            if (missing.isNotEmpty()) throw QueryRejected("columnTypes is missing ${missing.joinToString(", ")}")
            return SqlTranslator(
                name = name, quoteOpen = open, quoteClose = close, pageTemplate = page, limitTemplate = limit,
                currentTimestamp = text("currentTimestamp", max = 100).also {
                    if (it.isBlank() || it.contains(';')) throw QueryRejected("currentTimestamp is invalid")
                },
                validationQuery = validation, createTableTemplate = create, columnTypes = types,
                trueLiteral = text("trueLiteral", "TRUE", 20), falseLiteral = text("falseLiteral", "FALSE", 20),
            )
        }

        private fun requirePlaceholders(what: String, template: String, required: Set<String>, allowed: Set<String>) {
            val used = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()
            val bad = used - allowed
            if (bad.isNotEmpty()) throw QueryRejected("$what uses unknown placeholder {${bad.first()}}")
            val absent = required - used
            if (absent.isNotEmpty()) throw QueryRejected("$what must contain {${absent.first()}}")
        }
    }
}
