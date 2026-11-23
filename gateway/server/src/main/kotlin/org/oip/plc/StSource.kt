package org.oip.plc

/**
 * A small reader for the parts of a Structured Text source that UDT support needs: `TYPE ... STRUCT` declarations,
 * POUs with their variable sections, and which structure members a program or function block writes.
 * It is not a compiler; RuSTy still checks the program. Offsets point into the original text, so glue code can be
 * inserted without moving any line (diagnostics keep their line numbers).
 */
object StSource {
    data class Field(val name: String, val type: String)
    data class StructType(val name: String, val fields: List<Field>)
    data class VarDecl(val name: String, val type: String, val section: String, val address: String?)
    data class Pou(
        val kind: String,
        val name: String,
        val vars: List<VarDecl>,
        /** Offset just after the POU header (`PROGRAM Name`). */
        val headerEnd: Int,
        /** Offset just after the last `END_VAR` of the header, or [headerEnd] when there is none. */
        val bodyStart: Int,
        /** Offset of the `END_PROGRAM` / `END_FUNCTION_BLOCK` keyword. */
        val endKeyword: Int,
    ) {
        fun variable(name: String): VarDecl? = vars.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
    data class Parsed(val text: String, val masked: String, val structs: List<StructType>, val pous: List<Pou>) {
        fun struct(name: String): StructType? = structs.firstOrNull { it.name.equals(name, ignoreCase = true) }
        fun pou(name: String): Pou? = pous.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val programs: List<Pou> get() = pous.filter { it.kind == "PROGRAM" }
        fun body(pou: Pou): String = masked.substring(pou.bodyStart, pou.endKeyword)
    }

    private val POU = Regex("""\b(PROGRAM|FUNCTION_BLOCK|FUNCTION)\s+([A-Za-z_]\w*)""", RegexOption.IGNORE_CASE)
    private val VAR_SECTION = Regex(
        """\b(VAR_INPUT|VAR_OUTPUT|VAR_IN_OUT|VAR_TEMP|VAR_GLOBAL|VAR_EXTERNAL|VAR)\b((?:\s+(?:CONSTANT|RETAIN|NON_RETAIN|PERSISTENT))*)""",
        RegexOption.IGNORE_CASE,
    )
    private val END_VAR = Regex("""\bEND_VAR\b""", RegexOption.IGNORE_CASE)
    private val TYPE_BLOCK = Regex("""\bTYPE\b(.*?)\bEND_TYPE\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val STRUCT = Regex("""([A-Za-z_]\w*)\s*:\s*STRUCT\b(.*?)\bEND_STRUCT\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DECL = Regex("""^\s*([A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*)\s*(?:AT\s+(%[A-Za-z]+[\d.]+))?\s*:\s*(.+?)\s*(?::=.*)?$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun parse(text: String): Parsed {
        val masked = mask(text)
        val structs = ArrayList<StructType>()
        for (block in TYPE_BLOCK.findAll(masked)) {
            for (s in STRUCT.findAll(block.groupValues[1])) {
                val fields = declarations(s.groupValues[2]).map { Field(it.first, it.third) }
                structs += StructType(s.groupValues[1], fields)
            }
        }
        val pous = ArrayList<Pou>()
        var from = 0
        while (true) {
            val head = POU.find(masked, from) ?: break
            // END_FUNCTION_BLOCK etc. never match POU because `_` is a word character, so \b fails before FUNCTION.
            val kind = head.groupValues[1].uppercase()
            val end = Regex("""\bEND_$kind\b""", RegexOption.IGNORE_CASE).find(masked, head.range.last + 1)
            val endAt = end?.range?.first ?: masked.length
            val vars = ArrayList<VarDecl>()
            var cursor = head.range.last + 1
            var bodyStart = cursor
            while (true) {
                val section = VAR_SECTION.find(masked, cursor) ?: break
                if (section.range.first >= endAt) break
                // VAR sections come before the first statement; anything else in between ends the header.
                val gap = masked.substring(cursor, section.range.first)
                if (!(gap.isBlank() || (cursor == head.range.last + 1 && onlyFunctionReturn(gap)))) break
                val close = END_VAR.find(masked, section.range.last + 1) ?: break
                if (close.range.first > endAt) break
                val name = section.groupValues[1].uppercase()
                for ((n, address, type) in declarations(masked.substring(section.range.last + 1, close.range.first))) {
                    vars += VarDecl(n, type, name, address)
                }
                cursor = close.range.last + 1
                bodyStart = cursor
            }
            pous += Pou(kind, head.groupValues[2], vars, head.range.last + 1, bodyStart, endAt)
            from = if (end != null) end.range.last + 1 else masked.length
        }
        return Parsed(text, masked, structs, pous)
    }

    /** `FUNCTION F : INT` leaves `: INT` between the header and the first VAR. */
    private fun onlyFunctionReturn(gap: String): Boolean = gap.isBlank() || Regex("""^\s*:\s*[A-Za-z_]\w*\s*$""").matches(gap)

    /** (name, address, type) for each declared name in a VAR or STRUCT body. */
    private fun declarations(body: String): List<Triple<String, String?, String>> {
        val out = ArrayList<Triple<String, String?, String>>()
        for (raw in splitTopLevel(body, ';')) {
            if (raw.isBlank()) continue
            val m = DECL.find(raw) ?: continue
            val type = m.groupValues[3].trim().replace(Regex("""\s+"""), " ")
            val address = m.groupValues[2].ifBlank { null }
            for (name in m.groupValues[1].split(',')) out += Triple(name.trim(), address, type)
        }
        return out
    }

    /** Splits on [sep] outside brackets and parentheses. */
    internal fun splitTopLevel(text: String, sep: Char): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in text) {
            when (c) {
                '(', '[' -> depth++
                ')', ']' -> depth--
            }
            if (c == sep && depth == 0) {
                parts += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts
    }

    /** Comments and string literals become spaces; newlines stay, so offsets and line numbers match [text]. */
    fun mask(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                depth > 0 && c == '*' && next == ')' -> { depth--; out.append("  "); i += 2 }
                c == '(' && next == '*' -> { depth++; out.append("  "); i += 2 }
                depth > 0 -> { out.append(if (c == '\n' || c == '\r') c else ' '); i++ }
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') { out.append(' '); i++ }
                }
                c == '\'' || c == '"' -> {
                    out.append(' ')
                    i++
                    while (i < text.length && text[i] != c) { out.append(if (text[i] == '\n') '\n' else ' '); i++ }
                    if (i < text.length) { out.append(' '); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private val MEMBER_WRITE = Regex("""\b([A-Za-z_]\w*)((?:\s*\.\s*[A-Za-z_]\w*)+)\s*:=""")
    private val WHOLE_WRITE = Regex("""\b([A-Za-z_]\w*)\s*:=""")

    /**
     * Member paths (lower case, `/`-joined) that [pou] writes on its variable [variable], directly or through a
     * function block's VAR_IN_OUT parameter. `*` means the whole structure is assigned.
     */
    fun writes(parsed: Parsed, pou: Pou, variable: String): Set<String> = Writes(parsed).of(pou, variable.lowercase(), emptySet())

    private class Writes(val parsed: Parsed) {
        private val memo = HashMap<Pair<String, String>, Set<String>>()

        fun of(pou: Pou, variable: String, stack: Set<String>): Set<String> {
            val key = pou.name.lowercase() to variable
            memo[key]?.let { return it }
            if (pou.name.lowercase() in stack) return emptySet()
            val body = parsed.body(pou)
            val out = LinkedHashSet<String>()
            for (m in MEMBER_WRITE.findAll(body)) {
                if (!m.groupValues[1].equals(variable, ignoreCase = true)) continue
                out += m.groupValues[2].split('.').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.joinToString("/")
            }
            for (m in WHOLE_WRITE.findAll(body)) {
                if (!m.groupValues[1].equals(variable, ignoreCase = true)) continue
                val before = body.substring(0, m.range.first).trimEnd().lastOrNull()
                if (before == '(' || before == ',' || before == '.') continue
                out += "*"
            }
            // Calls of function block instances that pass this variable to a VAR_IN_OUT parameter.
            for (instance in pou.vars) {
                val fb = parsed.pou(instance.type)?.takeIf { it.kind == "FUNCTION_BLOCK" } ?: continue
                val call = Regex("""\b${Regex.escape(instance.name)}\s*\(""", RegexOption.IGNORE_CASE)
                for (c in call.findAll(body)) {
                    val args = argumentsAt(body, c.range.last + 1) ?: continue
                    for (arg in splitTopLevel(args, ',')) {
                        val named = Regex("""^\s*([A-Za-z_]\w*)\s*:=\s*([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)\s*$""").find(arg) ?: continue
                        val path = named.groupValues[2].split('.').map { it.trim().lowercase() }
                        if (path.first() != variable) continue
                        val param = fb.variable(named.groupValues[1]) ?: continue
                        if (param.section != "VAR_IN_OUT") continue
                        val prefix = path.drop(1).joinToString("/")
                        for (w in of(fb, param.name.lowercase(), stack + pou.name.lowercase())) {
                            out += when {
                                prefix.isEmpty() -> w
                                w == "*" -> prefix
                                else -> "$prefix/$w"
                            }
                        }
                    }
                }
            }
            memo[key] = out
            return out
        }

        /** The text between `(` (already consumed at [start]) and its matching `)`. */
        private fun argumentsAt(body: String, start: Int): String? {
            var depth = 1
            var i = start
            while (i < body.length) {
                when (body[i]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return body.substring(start, i) }
                }
                i++
            }
            return null
        }
    }
}
