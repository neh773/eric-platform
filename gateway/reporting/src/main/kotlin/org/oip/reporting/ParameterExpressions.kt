package org.oip.reporting

import java.time.DayOfWeek
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Expressions for report parameter values (doc source: FEATURES REP-034). A parameter with `expression` takes this
 * value when a run supplies none, so a schedule can ask for "the last full day" without anyone typing dates.
 *
 * Grammar: `+ - * /`, parentheses, numbers, durations (`15m`, `24h`, `7d`, `2w`, in milliseconds), quoted strings,
 * `true`/`false`, `now`, the name of a parameter declared earlier (`range.from` for the end of a timeRange), and
 * these functions: `startOfHour(t)`, `startOfDay(t[, zone])`, `startOfWeek(t[, zone])` (Monday),
 * `startOfMonth(t[, zone])`, `range(from, to)`, `min`, `max`, `round`, `floor`, `ceil`, `abs`, `concat`, `upper`,
 * `lower`. Zones are IANA ids and default to UTC. The result must have the parameter's type.
 */
object ParameterExpressions {
    sealed interface Node {
        data class Num(val value: Double) : Node
        data class Str(val value: String) : Node
        data class Bool(val value: Boolean) : Node
        data class Ref(val name: String, val field: String?) : Node
        data class Neg(val operand: Node) : Node
        data class Bin(val op: Char, val left: Node, val right: Node) : Node
        data class Call(val name: String, val args: List<Node>) : Node
    }

    private val FUNCTIONS = mapOf(
        "startOfHour" to 1..2, "startOfDay" to 1..2, "startOfWeek" to 1..2, "startOfMonth" to 1..2,
        "range" to 2..2, "min" to 2..8, "max" to 2..8, "round" to 1..1, "floor" to 1..1, "ceil" to 1..1,
        "abs" to 1..1, "concat" to 1..8, "upper" to 1..1, "lower" to 1..1,
    )

    /** Parse [text]; throws [IllegalArgumentException] with a message naming the offending position. */
    fun parse(text: String): Node {
        require(text.isNotBlank()) { "expression must not be empty" }
        require(text.length <= 500) { "expression is too long" }
        val parser = Parser(text)
        val node = parser.expression()
        parser.end()
        return node
    }

    /** Parameter names an expression reads. */
    fun references(node: Node): Set<String> = when (node) {
        is Node.Ref -> setOf(node.name)
        is Node.Neg -> references(node.operand)
        is Node.Bin -> references(node.left) + references(node.right)
        is Node.Call -> node.args.flatMap { references(it) }.toSet()
        else -> emptySet()
    }

    /**
     * Evaluate [node]. [lookup] returns the value of a parameter already resolved (a number, string, boolean,
     * epoch-millis timestamp, or timeRange map), or throws [IllegalArgumentException] for an unknown name.
     * A numeric result that is whole comes back as a Long.
     */
    fun evaluate(node: Node, nowMillis: Long, lookup: (String) -> Any?): Any? = normalize(eval(node, nowMillis, lookup))

    private fun normalize(value: Any?): Any? = when {
        value is Double && value.isFinite() && value % 1.0 == 0.0 && kotlin.math.abs(value) < 9e15 -> value.toLong()
        value is Double && !value.isFinite() -> throw IllegalArgumentException("expression result is not a finite number")
        else -> value
    }

    private fun eval(node: Node, now: Long, lookup: (String) -> Any?): Any? = when (node) {
        is Node.Num -> node.value
        is Node.Str -> node.value
        is Node.Bool -> node.value
        is Node.Ref -> reference(node, now, lookup)
        is Node.Neg -> -number(eval(node.operand, now, lookup), "-")
        is Node.Bin -> {
            val l = eval(node.left, now, lookup)
            val r = eval(node.right, now, lookup)
            if (node.op == '+' && (l is String || r is String)) "${text(l)}${text(r)}" else {
                val a = number(l, node.op.toString())
                val b = number(r, node.op.toString())
                when (node.op) {
                    '+' -> a + b
                    '-' -> a - b
                    '*' -> a * b
                    else -> {
                        require(b != 0.0) { "division by zero" }
                        a / b
                    }
                }
            }
        }
        is Node.Call -> call(node, now, lookup)
    }

    private fun reference(node: Node.Ref, now: Long, lookup: (String) -> Any?): Any? {
        if (node.name == "now") {
            require(node.field == null) { "now has no field ${node.field}" }
            return now.toDouble()
        }
        val value = lookup(node.name) ?: throw IllegalArgumentException("parameter ${node.name} has no value")
        if (node.field == null) {
            require(value !is Map<*, *>) { "${node.name} is a time range; use ${node.name}.from or ${node.name}.to" }
            return (value as? Number)?.toDouble() ?: value
        }
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("${node.name} is not a time range")
        require(node.field == "from" || node.field == "to") { "a time range has fields from and to, not ${node.field}" }
        return (map[node.field] as Number).toDouble()
    }

    private fun call(node: Node.Call, now: Long, lookup: (String) -> Any?): Any? {
        val args = node.args.map { eval(it, now, lookup) }
        return when (node.name) {
            "startOfHour" -> truncate(args, ChronoUnit.HOURS, node.name)
            "startOfDay" -> truncate(args, ChronoUnit.DAYS, node.name)
            "startOfWeek" -> start(args, node.name) { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS) }
            "startOfMonth" -> start(args, node.name) { it.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS) }
            "range" -> {
                val from = number(args[0], "range").toLong()
                val to = number(args[1], "range").toLong()
                require(from <= to) { "range: from must not be after to" }
                linkedMapOf("from" to from, "to" to to)
            }
            "min" -> args.map { number(it, "min") }.min()
            "max" -> args.map { number(it, "max") }.max()
            "round" -> Math.floor(number(args[0], "round") + 0.5)
            "floor" -> Math.floor(number(args[0], "floor"))
            "ceil" -> Math.ceil(number(args[0], "ceil"))
            "abs" -> kotlin.math.abs(number(args[0], "abs"))
            "concat" -> args.joinToString("") { text(it) }
            "upper" -> text(args[0]).uppercase()
            "lower" -> text(args[0]).lowercase()
            else -> throw IllegalArgumentException("unknown function ${node.name}")
        }
    }

    private fun zone(args: List<Any?>, fn: String): ZoneId {
        if (args.size < 2) return ZoneOffset.UTC
        val id = args[1] as? String ?: throw IllegalArgumentException("$fn: the zone must be a string")
        return try {
            ZoneId.of(id)
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("$fn: unknown time zone '$id'")
        }
    }

    private fun truncate(args: List<Any?>, unit: ChronoUnit, fn: String): Double =
        start(args, fn) { it.truncatedTo(unit) }

    private fun start(args: List<Any?>, fn: String, adjust: (java.time.ZonedDateTime) -> java.time.ZonedDateTime): Double {
        val millis = number(args[0], fn).toLong()
        val local = Instant.ofEpochMilli(millis).atZone(zone(args, fn))
        return adjust(local).toInstant().toEpochMilli().toDouble()
    }

    private fun number(value: Any?, op: String): Double = when (value) {
        is Number -> value.toDouble()
        else -> throw IllegalArgumentException("$op needs a number")
    }

    private fun text(value: Any?): String = when (value) {
        is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 9e15) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun end() {
            ws()
            if (i < s.length) fail("unexpected '${s[i]}'")
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("expression: $message at ${i + 1}")

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char? {
            ws()
            return s.getOrNull(i)
        }

        fun expression(): Node {
            var left = term()
            while (true) {
                val c = peek()
                if (c != '+' && c != '-') return left
                i++
                left = Node.Bin(c, left, term())
            }
        }

        private fun term(): Node {
            var left = unary()
            while (true) {
                val c = peek()
                if (c != '*' && c != '/') return left
                i++
                left = Node.Bin(c, left, unary())
            }
        }

        private fun unary(): Node {
            if (peek() == '-') {
                i++
                return Node.Neg(unary())
            }
            return primary()
        }

        private fun primary(): Node {
            val c = peek() ?: fail("unexpected end")
            return when {
                c == '(' -> {
                    i++
                    val inner = expression()
                    if (peek() != ')') fail("expected )")
                    i++
                    inner
                }
                c == '"' || c == '\'' -> string(c)
                c.isDigit() -> number()
                c.isLetter() || c == '_' -> identifier()
                else -> fail("unexpected '$c'")
            }
        }

        private fun string(quote: Char): Node {
            i++
            val out = StringBuilder()
            while (i < s.length && s[i] != quote) out.append(s[i++])
            if (i >= s.length) fail("unterminated string")
            i++
            return Node.Str(out.toString())
        }

        private fun number(): Node {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            val digits = s.substring(start, i)
            val value = digits.toDoubleOrNull() ?: fail("bad number '$digits'")
            val unit = s.getOrNull(i)
            val next = s.getOrNull(i + 1)
            if (unit != null && unit in "smhdw" && (next == null || !(next.isLetterOrDigit() || next == '_'))) {
                i++
                val millis = when (unit) {
                    's' -> 1_000.0
                    'm' -> 60_000.0
                    'h' -> 3_600_000.0
                    'd' -> 86_400_000.0
                    else -> 604_800_000.0
                }
                return Node.Num(value * millis)
            }
            return Node.Num(value)
        }

        private fun name(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            return s.substring(start, i)
        }

        private fun identifier(): Node {
            val id = name()
            when (id) {
                "true" -> return Node.Bool(true)
                "false" -> return Node.Bool(false)
            }
            if (peek() == '(') {
                i++
                val range = FUNCTIONS[id] ?: fail("unknown function $id")
                val args = ArrayList<Node>()
                if (peek() != ')') {
                    while (true) {
                        args += expression()
                        if (peek() == ',') { i++; continue }
                        break
                    }
                }
                if (peek() != ')') fail("expected )")
                i++
                if (args.size !in range) fail("$id takes ${if (range.first == range.last) "${range.first}" else "${range.first} to ${range.last}"} arguments")
                return Node.Call(id, args)
            }
            if (s.getOrNull(i) == '.') {
                i++
                val field = name()
                if (field.isEmpty()) fail("expected a field name")
                return Node.Ref(id, field)
            }
            return Node.Ref(id, null)
        }
    }
}
