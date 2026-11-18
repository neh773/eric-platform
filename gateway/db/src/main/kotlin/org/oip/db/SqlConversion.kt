package org.oip.db

/**
 * DAT-035: turn an ad hoc script query into a named query.
 *
 * Input is the text of one `system.db.runPrepQuery`, `runPrepUpdate`, `runScalarPrepQuery`, `runQuery` or
 * `runUpdateQuery` call (or a script holding such a call), or bare SQL. The result is the named query's
 * fields (`?` placeholders become `:name` parameters) and the script with that call replaced by
 * `system.db.runNamedQuery("name", {...})`. Nothing is saved here; the caller stores the resource, in the
 * gateway or in a project.
 */
object SqlConversion {
    data class Converted(
        val name: String,
        /** Fields of the named query resource: connection, queryType, sql, parameters. */
        val fields: Map<String, Any?>,
        /** The script with the first ad hoc call replaced, or the replacement call alone when only SQL was given. */
        val script: String,
        /** The replacement call text. */
        val call: String,
    )

    private val CALLS = mapOf(
        "runPrepQuery" to "query",
        "runQuery" to "query",
        "runScalarPrepQuery" to "scalar",
        "runPrepUpdate" to "update",
        "runUpdateQuery" to "update",
    )
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val CALL_START = Regex("system\\.db\\.(" + CALLS.keys.joinToString("|") + ")\\s*\\(")

    /** Convert the first ad hoc call in [script]. */
    fun fromScript(name: String, script: String, connection: String?): Converted {
        requireName(name)
        val start = CALL_START.find(script) ?: throw QueryRejected("no system.db.runPrepQuery, runPrepUpdate, runScalarPrepQuery, runQuery or runUpdateQuery call found")
        val type = CALLS.getValue(start.groupValues[1])
        val prepared = start.groupValues[1].contains("Prep")
        val open = start.range.last
        val close = matchingParen(script, open)
        val args = splitArgs(script.substring(open + 1, close))
        val positional = args.filter { it.keyword == null }.map { it.text }
        val keyword = args.filter { it.keyword != null }.associate { it.keyword!! to it.text }
        if ("tx" in keyword || positional.getOrNull(if (prepared) 3 else 2)?.isNotBlank() == true) {
            throw QueryRejected("a call inside a transaction cannot be converted")
        }
        val query = positional.getOrNull(0) ?: keyword["query"] ?: throw QueryRejected("the call has no query text")
        val sql = literal(query) ?: throw QueryRejected("the query text must be one string literal")
        val params = if (prepared) positional.getOrNull(1) ?: keyword["params"] else null
        val database = positional.getOrNull(if (prepared) 2 else 1) ?: keyword["database"]
        val values = params?.let { listItems(it) } ?: emptyList()
        val dbName = database?.let { literal(it) }
        if (database != null && dbName == null) throw QueryRejected("the database argument must be a string literal")
        val converted = build(name, sql, values, dbName ?: connection, type)
        val replaced = script.substring(0, start.range.first) + converted.call + script.substring(close + 1)
        return converted.copy(script = replaced)
    }

    /** Convert bare SQL; `?` placeholders become `p1`, `p2`, and so on. */
    fun fromSql(name: String, sql: String, connection: String?, queryType: String?): Converted {
        requireName(name)
        val type = queryType ?: if (Regex("^\\s*(select|with|show|values)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)) "query" else "update"
        if (type !in setOf("query", "scalar", "update")) throw QueryRejected("queryType must be query, scalar or update")
        return build(name, sql, emptyList(), connection, type)
    }

    private fun build(name: String, sql: String, values: List<String>, connection: String?, type: String): Converted {
        val (rewritten, count) = numberPlaceholders(sql)
        if (values.isNotEmpty() && values.size != count) throw QueryRejected("the query has $count placeholder(s) but the call passes ${values.size} value(s)")
        val names = LinkedHashSet<String>()
        val parameters = ArrayList<Map<String, Any?>>()
        var text = rewritten
        val labels = (0 until count).map { index ->
            val arg = values.getOrNull(index)?.trim()
            var label = if (arg != null && IDENT.matches(arg) && arg !in setOf("None", "True", "False")) arg else "p${index + 1}"
            while (!names.add(label.lowercase())) label += "_"
            label
        }
        // Replace the numbered markers in order.
        for ((index, label) in labels.withIndex()) text = text.replace("\u0000${index + 1}\u0000", ":$label")
        for ((index, label) in labels.withIndex()) {
            parameters += mapOf("name" to label, "dataType" to dataType(values.getOrNull(index)))
        }
        val fields = linkedMapOf<String, Any?>(
            "connection" to (connection?.takeIf { it.isNotBlank() } ?: CONNECTION_DEFAULT),
            "queryType" to type,
            "sql" to text,
            "parameters" to parameters,
        )
        val call = if (count == 0) "system.db.runNamedQuery(\"$name\")"
        else "system.db.runNamedQuery(\"$name\", {" + labels.indices.joinToString(", ") { "\"${labels[it]}\": ${values.getOrNull(it)?.trim() ?: "None"}" } + "})"
        return Converted(name, fields, call, call)
    }

    private fun dataType(arg: String?): String {
        val text = arg?.trim() ?: return "string"
        return when {
            Regex("^-?\\d+$").matches(text) -> "long"
            Regex("^-?\\d*\\.\\d+([eE][-+]?\\d+)?$").matches(text) -> "double"
            text == "True" || text == "False" -> "boolean"
            else -> "string"
        }
    }

    /** Replace each `?` outside quotes and comments with a numbered marker. Returns the text and the count. */
    private fun numberPlaceholders(sql: String): Pair<String, Int> {
        val out = StringBuilder()
        var count = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' -> {
                    val end = skipQuoted(sql, i, c)
                    out.append(sql, i, end); i = end
                }
                c == '-' && sql.startsWith("--", i) -> {
                    val end = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                    out.append(sql, i, end); i = end
                }
                c == '/' && sql.startsWith("/*", i) -> {
                    val end = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                    out.append(sql, i, end); i = end
                }
                c == '?' -> { count++; out.append('\u0000').append(count).append('\u0000'); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString() to count
    }

    private fun skipQuoted(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < text.length) {
            if (text[i] == quote) {
                if (i + 1 < text.length && text[i + 1] == quote) i += 2 else return i + 1
            } else i++
        }
        return text.length
    }

    private class Arg(val keyword: String?, val text: String)

    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> i = pyStringEnd(text, i) - 1
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        throw QueryRejected("the call is not closed")
    }

    private fun pyStringEnd(text: String, start: Int): Int {
        val q = text[start]
        if (text.startsWith("$q$q$q", start)) {
            val end = text.indexOf("$q$q$q", start + 3)
            return if (end < 0) text.length else end + 3
        }
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                q -> return i + 1
                else -> i++
            }
        }
        return text.length
    }

    private fun splitTop(body: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var last = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '"' || c == '\'' -> { i = pyStringEnd(body, i); continue }
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> { parts += body.substring(last, i); last = i + 1 }
            }
            i++
        }
        if (body.substring(last).isNotBlank()) parts += body.substring(last)
        return parts
    }

    private fun splitArgs(body: String): List<Arg> = splitTop(body).map { raw ->
        val m = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(raw)
        if (m != null) Arg(m.groupValues[1], m.groupValues[2].trim()) else Arg(null, raw.trim())
    }

    private fun listItems(text: String): List<String> {
        val t = text.trim()
        if (!(t.startsWith("[") && t.endsWith("]")) && !(t.startsWith("(") && t.endsWith(")"))) {
            throw QueryRejected("the parameter list must be a list literal")
        }
        return splitTop(t.substring(1, t.length - 1)).map { it.trim() }
    }

    /** The value of a single Python string literal, or null when [text] is not exactly one. */
    private fun literal(text: String): String? {
        val t = text.trim()
        if (t.length < 2 || (t[0] != '"' && t[0] != '\'')) return null
        if (pyStringEnd(t, 0) != t.length) return null
        val q = t[0]
        val inner = if (t.startsWith("$q$q$q")) t.substring(3, t.length - 3) else t.substring(1, t.length - 1)
        return inner.replace("\\$q", "$q").replace("\\n", "\n").replace("\\\\", "\\")
    }

    private fun requireName(name: String) {
        if (!NAME.matches(name)) throw QueryRejected("name must be a lowercase resource name")
    }
}
