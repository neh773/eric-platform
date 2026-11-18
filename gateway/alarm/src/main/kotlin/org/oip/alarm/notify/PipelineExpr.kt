package org.oip.alarm.notify

import org.oip.alarm.AlarmPriority
import org.oip.tags.Level
import org.oip.tags.expr.EvalContext
import org.oip.tags.expr.Expressions
import org.oip.tags.expr.Value
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pipeline expressions are the tag expression language, plus the alarm functions published for pipeline blocks.
 */
object PipelineExpr {
    data class Result(val value: Any?, val failed: Boolean)

    fun eval(text: String, props: Map<String, Any?>): Result {
        val rewritten = try {
            rewrite(text, props)
        } catch (_: RuntimeException) {
            return Result(null, true)
        }
        val parsed = try {
            Expressions.parse(rewritten)
        } catch (_: RuntimeException) {
            return Result(null, true)
        }
        val value = Expressions.eval(parsed, EvalContext(
            ref = { Value(null) },
            locals = props.mapValues { Value(it.value) },
        ))
        val failed = value.value == null && value.quality.level.ordinal >= Level.BAD.ordinal
        return Result(value.value, failed)
    }

    /** A failed expression is the roster name, which is how a bare roster name is accepted. */
    fun rosterName(text: String, props: Map<String, Any?>): String {
        val result = eval(text, props)
        if (result.failed) return text.trim()
        return result.value?.toString()?.trim().orEmpty()
    }

    fun truth(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0 && !value.toDouble().isNaN()
        is String -> value.isNotEmpty() && !value.equals("false", true) && value != "0"
        else -> true
    }

    private fun rewrite(text: String, props: Map<String, Any?>): String {
        var out = rewriteCalls(text, props)
        val code = priorityCode(props["priority"])
        if (code != null) {
            out = Regex("(?i)toInt\\(\\s*\\{priority\\}\\s*\\)").replace(out, code.toString())
        }
        out = Regex("(?i)\\{priority\\}\\s*=\\s*\"(Diagnostic|Low|Medium|High|Critical)\"").replace(out) { match ->
            "{priorityName}=\"${match.groupValues[1]}\""
        }
        out = Regex("(?i)\"(Diagnostic|Low|Medium|High|Critical)\"\\s*=\\s*\\{priority\\}").replace(out) { match ->
            "\"${match.groupValues[1]}\"={priorityName}"
        }
        return out
    }

    private fun priorityCode(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        else -> AlarmPriority.fromName(value?.toString())?.code
    }

    private fun rewriteCalls(text: String, props: Map<String, Any?>): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val call = callAt(text, i) 
            if (call == null) {
                out.append(text[i])
                i++
                continue
            }
            val rewrittenArgs = call.args.map { rewriteCalls(it, props) }
            val replacement = when (call.name.lowercase()) {
                "isactive" -> "{isActive}"
                "isclear" -> "{isClear}"
                "isacked" -> "{isAcked}"
                "isshelved" -> "{isShelved}"
                "ispropertydefined" -> if (props.containsKey(unquote(rewrittenArgs.singleOrNull()))) "true" else "false"
                "getproperty" -> "{${unquote(rewrittenArgs.singleOrNull().orEmpty())}}"
                // associatedData("Key") or associatedData("Key", default): an alarm's associated data entry (ALA-008).
                "associateddata" -> associatedValue(rewrittenArgs, props)
                "coalesce" -> coalesce(rewrittenArgs)
                else -> null
            }
            if (replacement == null) {
                out.append(text[i])
                i++
            } else {
                out.append(replacement)
                i = call.end
            }
        }
        return out.toString()
    }

    private fun associatedValue(args: List<String>, props: Map<String, Any?>): String? {
        if (args.isEmpty() || args.size > 2) return null
        val key = unquote(args[0])
        if (key.isEmpty()) return null
        return if (props.containsKey(key)) "{$key}" else args.getOrNull(1) ?: "null"
    }

    private fun coalesce(args: List<String>): String {
        if (args.isEmpty()) return "null"
        return args.drop(1).fold(args.first()) { acc, next -> "if(($acc)=null, $next, $acc)" }
    }

    private fun unquote(text: String?): String {
        val raw = text?.trim().orEmpty()
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') return raw.substring(1, raw.length - 1)
        return raw
    }

    private data class Call(val name: String, val args: List<String>, val end: Int)

    private fun callAt(text: String, index: Int): Call? {
        if (index > 0 && ident(text[index - 1])) return null
        var i = index
        if (i >= text.length || !text[i].isLetter()) return null
        val start = i
        while (i < text.length && ident(text[i])) i++
        val name = text.substring(start, i)
        if (i >= text.length || text[i] != '(') return null
        i++
        val args = ArrayList<String>()
        val arg = StringBuilder()
        var depth = 1
        while (i < text.length && depth > 0) {
            val c = text[i]
            when {
                c == '(' -> { depth++; arg.append(c) }
                c == ')' && depth == 1 -> depth = 0
                c == ')' -> { depth--; arg.append(c) }
                c == ',' && depth == 1 -> { args += arg.toString(); arg.clear() }
                else -> arg.append(c)
            }
            i++
        }
        if (depth != 0) return null
        if (arg.isNotEmpty() || args.isNotEmpty()) args += arg.toString()
        return Call(name, args.map { it.trim() }.filter { it.isNotEmpty() }, i)
    }

    private fun ident(c: Char) = c.isLetterOrDigit() || c == '_'
}

/**
 * `{property}` and `{property|pattern}` substitution for notification text.
 */
object AlarmText {
    private val token = Regex("\\{\\{([\\s\\S]*?)\\}\\}|\\{([A-Za-z][A-Za-z0-9_.]*)(?:\\|([^}]+))?\\}")

    fun render(template: String, props: Map<String, Any?>, zone: ZoneId): String =
        render(template, listOf(props), zone)

    fun render(template: String, events: List<Map<String, Any?>>, zone: ZoneId): String {
        val shared = events.firstOrNull().orEmpty()
        return token.replace(template) { match ->
            val repeated = match.groupValues[1]
            if (match.value.startsWith("{{")) {
                events.joinToString("") { event -> render(repeated, listOf(event), zone) }
            } else {
                val name = match.groupValues[2]
                val pattern = match.groupValues[3].ifBlank { null }
                format(lookup(shared, name), pattern, zone)
            }
        }
    }

    private fun lookup(props: Map<String, Any?>, name: String): Any? {
        props[name]?.let { return it }
        if (name.equals("Value", true)) return props["eventValue"]
        if (name.equals("priority", true)) return props["priorityName"] ?: props["priority"]
        if (name.equals("count", true)) return props["count"]
        return null
    }

    private fun format(value: Any?, pattern: String?, zone: ZoneId): String {
        if (value == null) return ""
        if (pattern != null && value is Instant) {
            return try {
                val fmt = when (pattern) {
                    "hh:mm:ss" -> DateTimeFormatter.ofPattern("hh:mm:ss")
                    "HH:mm:ss" -> DateTimeFormatter.ofPattern("HH:mm:ss")
                    else -> DateTimeFormatter.ofPattern(pattern)
                }
                fmt.format(value.atZone(zone))
            } catch (_: IllegalArgumentException) {
                value.toString()
            }
        }
        return value.toString()
    }
}
