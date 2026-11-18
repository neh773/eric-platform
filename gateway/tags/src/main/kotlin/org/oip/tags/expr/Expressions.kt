package org.oip.tags.expr

import org.oip.tags.Level
import org.oip.tags.Quality
import org.oip.tags.QualityCode
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Expression language used by expression and derived tags.
 * Operators, literals, `{tag}` references, `if` and `switch` follow the public expression docs:
 *
 * Referenced tag qualities propagate the worst level
 * (-).
 */
sealed interface Expr {
    data class Lit(val value: Any?) : Expr
    data class Ref(val raw: String) : Expr
    data class Unary(val op: String, val expr: Expr) : Expr
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
    data class Call(val name: String, val args: List<Expr>) : Expr
    data class Index(val target: Expr, val indexes: List<Expr>) : Expr
}

data class Value(val value: Any?, val quality: Quality = Quality.GOOD) {
    fun withWorst(other: Quality): Value = if (other.level.ordinal > quality.level.ordinal) copy(quality = other) else this
}

class ExpressionException(message: String, val quality: QualityCode = QualityCode.ERROR_EXPRESSION_EVAL) : RuntimeException(message)

/**
 * [ref] resolves a `{...}` body or a dynamic `tag()` path. Implementations record dependencies.
 * [params] are UDT parameters. [locals] holds derived-tag `{source}` and `{value}`.
 */
class EvalContext(
    val ref: (String) -> Value,
    val params: Map<String, Any?> = emptyMap(),
    val locals: Map<String, Value> = emptyMap(),
)

object Expressions {
    fun parse(text: String): Expr = Parser(text).parse()

    fun eval(text: String, ctx: EvalContext): Value = eval(parse(text), ctx)

    fun eval(expr: Expr, ctx: EvalContext): Value = try {
        evaluate(expr, ctx)
    } catch (e: ExpressionException) {
        Value(null, Quality(e.quality, e.message))
    } catch (e: RuntimeException) {
        // Arity and null-index bugs are handled below. This keeps any other runtime failure
        // inside the value instead of escaping configure() or a subscriber callback.
        Value(null, Quality(QualityCode.ERROR_EXPRESSION_EVAL, e.message ?: e.javaClass.simpleName))
    }

    /** Static `{ref}` bodies, in left-to-right order. Dynamic `tag()` calls are not included. */
    fun staticRefs(expr: Expr): List<String> {
        val out = ArrayList<String>()
        fun walk(e: Expr) {
            when (e) {
                is Expr.Lit -> {}
                is Expr.Ref -> out += e.raw
                is Expr.Unary -> walk(e.expr)
                is Expr.Binary -> { walk(e.left); walk(e.right) }
                is Expr.Call -> e.args.forEach { walk(it) }
                is Expr.Index -> { walk(e.target); e.indexes.forEach { walk(it) } }
            }
        }
        walk(expr)
        return out
    }

    private fun evaluate(expr: Expr, ctx: EvalContext): Value = when (expr) {
        is Expr.Lit -> Value(expr.value)
        is Expr.Ref -> resolveRef(expr.raw, ctx)
        is Expr.Unary -> evalUnary(expr, ctx)
        is Expr.Binary -> evalBinary(expr, ctx)
        is Expr.Call -> evalCall(expr, ctx)
        is Expr.Index -> evalIndex(expr, ctx)
    }

    private fun resolveRef(raw: String, ctx: EvalContext): Value {
        val token = raw.trim()
        ctx.locals[token]?.let { return it }
        if (token in ctx.params && token.none { it == '/' || it == '[' || it == '.' || it == ']' }) {
            return Value(ctx.params[token], Quality.GOOD)
        }
        return ctx.ref(token)
    }

    private fun evalUnary(expr: Expr.Unary, ctx: EvalContext): Value {
        val v = evaluate(expr.expr, ctx)
        return when (expr.op) {
            "!" -> Value(!truth(v.value), v.quality)
            "-" -> {
                val n = num(v.value) ?: return bad(QualityCode.ERROR_TYPE_CONVERSION, "unary minus needs a number")
                if (!isIntegral(v.value)) return Value(-n, v.quality)
                val negated = try {
                    Math.negateExact((v.value as Number).toLong())
                } catch (_: ArithmeticException) {
                    // Long.MIN_VALUE has no 64-bit negation. toDouble is the wider magnitude type.
                    -n
                }
                Value(negated, v.quality)
            }
            "~" -> {
                val n = num(v.value) ?: return bad(QualityCode.ERROR_TYPE_CONVERSION, "bitwise not needs a number")
                Value(n.toLong().inv(), v.quality)
            }
            else -> bad(QualityCode.ERROR_EXPRESSION_EVAL, "unknown operator ${expr.op}")
        }
    }

    private fun evalBinary(expr: Expr.Binary, ctx: EvalContext): Value {
        if (expr.op == "&&" || expr.op == "||") {
            val left = evaluate(expr.left, ctx)
            val takeLeft = if (expr.op == "&&") !truth(left.value) else truth(left.value)
            if (takeLeft) return Value(truth(left.value), left.quality)
            val right = evaluate(expr.right, ctx)
            return Value(truth(right.value), right.quality.let { left.quality.worse(it) }.let { if (it.level.ordinal < left.quality.level.ordinal) left.quality else it })
                .let { Value(it.value, worst(left.quality, right.quality)) }
        }
        val left = evaluate(expr.left, ctx)
        val right = evaluate(expr.right, ctx)
        val q = worst(left.quality, right.quality)
        // A missing or cyclic reference has no operand. Keep that quality instead of a secondary type error.
        if (expr.op !in COMPARE && expr.op != "like" && (left.value == null || right.value == null) && q.level.ordinal >= Level.BAD.ordinal) {
            return Value(null, q)
        }
        return try {
            Value(applyOp(expr.op, left.value, right.value), q)
        } catch (e: ExpressionException) {
            Value(null, Quality(e.quality, e.message).let { if (q.level.ordinal > it.level.ordinal) Quality(q.code, e.message) else it })
        }
    }

    private fun applyOp(op: String, a: Any?, b: Any?): Any? {
        if (op == "+") {
            if (a is String || b is String) return stringify(a) + stringify(b)
        }
        if (op == "like") return like(stringify(a), stringify(b))
        if (op in COMPARE) {
            if (a == null || b == null) return when (op) {
                "=" -> a == b
                "!=" -> a != b
                else -> false
            }
            if (a is Number && b is Number) {
                val c = a.toDouble().compareTo(b.toDouble())
                return when (op) {
                    "=" -> c == 0
                    "!=" -> c != 0
                    "<" -> c < 0
                    ">" -> c > 0
                    "<=" -> c <= 0
                    ">=" -> c >= 0
                    else -> false
                }
            }
            if (a is String && b is String) {
                val c = a.compareTo(b)
                return when (op) {
                    "=" -> c == 0
                    "!=" -> c != 0
                    "<" -> c < 0
                    ">" -> c > 0
                    "<=" -> c <= 0
                    ">=" -> c >= 0
                    else -> false
                }
            }
            if (a is Boolean && b is Boolean) return when (op) {
                "=" -> a == b
                "!=" -> a != b
                else -> false
            }
            // `=` tests the operands as they are. `>`/`<` are defined between numbers.
            // Numeric strings are not coerced; toInt/toDouble do that explicitly.
            return when (op) {
                "=" -> a == b
                "!=" -> a != b
                else -> throw ExpressionException(
                    "cannot compare ${a.javaClass.simpleName} and ${b.javaClass.simpleName}",
                    QualityCode.ERROR_TYPE_CONVERSION,
                )
            }
        }
        if (op in BITWISE) {
            val la = num(a)?.toLong() ?: throw ExpressionException("bitwise operand is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
            val lb = num(b)?.toLong() ?: throw ExpressionException("bitwise operand is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
            return when (op) {
                "&" -> la and lb
                "|" -> la or lb
                "xor" -> la xor lb
                "<<" -> la shl (lb.toInt() and 63)
                ">>" -> la shr (lb.toInt() and 63)
                else -> throw ExpressionException("unknown operator $op")
            }
        }
        val na = num(a) ?: throw ExpressionException("operand is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
        val nb = num(b) ?: throw ExpressionException("operand is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
        if (op == "/" && nb == 0.0) throw ExpressionException("division by zero")
        if (op == "%" && nb == 0.0) throw ExpressionException("division by zero")
        val bothInt = isIntegral(a) && isIntegral(b)
        return when (op) {
            "+" -> if (bothInt) exactLong((a as Number).toLong(), (b as Number).toLong(), Math::addExact) { x, y -> x + y } else na + nb
            "-" -> if (bothInt) exactLong((a as Number).toLong(), (b as Number).toLong(), Math::subtractExact) { x, y -> x - y } else na - nb
            "*" -> if (bothInt) exactLong((a as Number).toLong(), (b as Number).toLong(), Math::multiplyExact) { x, y -> x * y } else na * nb
            "/" -> na / nb
            "%" -> if (bothInt) longMod((a as Number).toLong(), (b as Number).toLong()) else na % nb
            "^" -> na.pow(nb)
            else -> throw ExpressionException("unknown operator $op")
        }
    }

    /**
     * 64-bit integer math (toLong). Overflow promotes to double, the wider-magnitude numeric type
     * (toDouble), instead of wrapping.
     */
    private fun exactLong(a: Long, b: Long, op: (Long, Long) -> Long, wide: (Double, Double) -> Double): Any =
        try {
            op(a, b)
        } catch (_: ArithmeticException) {
            wide(a.toDouble(), b.toDouble())
        }

    private fun longMod(a: Long, b: Long): Long = try {
        a % b
    } catch (_: ArithmeticException) {
        0L
    }

    private fun evalCall(expr: Expr.Call, ctx: EvalContext): Value {
        val name = expr.name.lowercase()
        if (name == "if") {
            if (expr.args.size != 3) return bad(QualityCode.ERROR_EXPRESSION_EVAL, "if() takes 3 arguments")
            val cond = evaluate(expr.args[0], ctx)
            val branch = evaluate(if (truth(cond.value)) expr.args[1] else expr.args[2], ctx)
            return branch.withWorst(cond.quality)
        }
        if (name == "switch") return evalSwitch(expr.args, ctx)
        if (name == "tag") {
            if (expr.args.size != 1) return bad(QualityCode.ERROR_EXPRESSION_EVAL, "tag() takes 1 argument")
            val p = evaluate(expr.args[0], ctx)
            val path = p.value?.toString() ?: return Value(null, worst(p.quality, Quality.BAD_REFERENCE_NOT_FOUND))
            return resolveRef(path, ctx).withWorst(p.quality)
        }
        val args = expr.args.map { evaluate(it, ctx) }
        var q = Quality.GOOD
        args.forEach { q = worst(q, it.quality) }
        val fn = FUNCTIONS[name] ?: return bad(QualityCode.ERROR_EXPRESSION_EVAL, "unknown function '$name'")
        return try {
            fn(args.map { it.value }).withWorst(q)
        } catch (e: ExpressionException) {
            Value(null, Quality(e.quality, e.message))
        }
    }

    private fun evalSwitch(args: List<Expr>, ctx: EvalContext): Value {
        if (args.size < 4 || (args.size - 2) % 2 != 0) {
            return bad(QualityCode.ERROR_EXPRESSION_EVAL, "switch() needs a value, paired cases and returns, and a default")
        }
        val value = evaluate(args[0], ctx)
        val pairs = (args.size - 2) / 2
        var q = value.quality
        for (i in 0 until pairs) {
            val c = evaluate(args[1 + i], ctx)
            q = worst(q, c.quality)
            if (equals(value.value, c.value)) {
                val ret = evaluate(args[1 + pairs + i], ctx)
                return ret.withWorst(q)
            }
        }
        val def = evaluate(args.last(), ctx)
        return def.withWorst(q)
    }

    private fun evalIndex(expr: Expr.Index, ctx: EvalContext): Value {
        val target = evaluate(expr.target, ctx)
        val idxs = expr.indexes.map { evaluate(it, ctx) }
        var q = target.quality
        idxs.forEach { q = worst(q, it.quality) }
        fun fail(code: QualityCode, message: String) = Value(null, worst(q, Quality(code, message)))
        if (idxs.size != 1) {
            // Dataset form is [row, column]. A null target has no cell; do not call single() on two indexes.
            if (idxs.size == 2 && target.value == null) return fail(QualityCode.BAD_NOT_FOUND, "null value has no dataset cell")
            if (idxs.size == 2) return fail(QualityCode.BAD_NOT_SUPPORTED, "dataset access is not implemented")
            return fail(QualityCode.ERROR_EXPRESSION_EVAL, "expected one index")
        }
        val key = idxs[0].value
        val result = when (val v = target.value) {
            is List<*> -> {
                val i = num(key)?.toInt() ?: return fail(QualityCode.ERROR_TYPE_CONVERSION, "list index must be numeric")
                if (i !in v.indices) return fail(QualityCode.BAD_OUT_OF_RANGE, "list index $i")
                v[i]
            }
            is Map<*, *> -> {
                if (key !in v.keys && key?.toString() !in v.keys.map { it?.toString() }) {
                    val hit = v.entries.find { it.key?.toString() == key?.toString() }?.value
                    if (hit == null && v.entries.none { it.key?.toString() == key?.toString() })
                        return fail(QualityCode.BAD_NOT_FOUND, "key not found")
                    return Value(hit, q)
                }
                v[key]
            }
            null -> return fail(QualityCode.BAD_NOT_FOUND, "null value has no elements")
            else -> return fail(QualityCode.ERROR_TYPE_CONVERSION, "value is not a collection")
        }
        return Value(result, q)
    }

    private fun equals(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun worst(a: Quality, b: Quality) = a.worse(b)

    private fun bad(code: QualityCode, message: String) = Value(null, Quality(code, message))
}

private val COMPARE = setOf("=", "!=", "<", ">", "<=", ">=")
private val BITWISE = setOf("&", "|", "xor", "<<", ">>")

internal fun truth(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Number -> v.toDouble() != 0.0 && !v.toDouble().isNaN()
    is String -> v.isNotEmpty() && !v.equals("false", true) && v != "0"
    else -> true
}

private fun num(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.toDoubleOrNull()
    else -> null
}

private fun isIntegral(v: Any?) = v is Byte || v is Short || v is Int || v is Long

internal fun stringify(v: Any?): String = when (v) {
    null -> ""
    is Double -> if (v % 1.0 == 0.0 && !v.isInfinite() && !v.isNaN()) v.toLong().toString() else v.toString()
    is Float -> if (v % 1f == 0f && !v.isInfinite() && !v.isNaN()) v.toLong().toString() else v.toString()
    else -> v.toString()
}

private fun like(text: String, pattern: String): Boolean {
    val sb = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        when (val c = pattern[i]) {
            '%', '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> {
                if (c in REGEX_META) sb.append('\\')
                sb.append(c)
            }
        }
        i++
    }
    return Regex(sb.toString()).matches(text)
}

private const val REGEX_META = ".\\[]{}()+-^$|"

private val FUNCTIONS: Map<String, (List<Any?>) -> Value> = mapOf(
    "abs" to { a ->
        unaryNum(a) { x, integral ->
            if (!integral) abs(x)
            else {
                val l = (a[0] as Number).toLong()
                try {
                    if (l < 0) Math.negateExact(l) else l
                } catch (_: ArithmeticException) {
                    abs(x)
                }
            }
        }
    },
    "sqrt" to { a -> unaryDouble(a) { sqrt(it) } },
    "pow" to { a ->
        val (x, y) = twoDoubles(a)
        Value(x.pow(y))
    },
    "min" to { a -> extreme(a, false) },
    "max" to { a -> extreme(a, true) },
    "round" to { a ->
        val x = oneDouble(a)
        if (a.size == 1) Value(round(x).toLong())
        else {
            val d = num(a.getOrNull(1))?.toInt() ?: throw ExpressionException("round digits must be numeric", QualityCode.ERROR_TYPE_CONVERSION)
            val f = 10.0.pow(d)
            Value(round(x * f) / f)
        }
    },
    "floor" to { a -> Value(floor(oneDouble(a)).toLong()) },
    "ceil" to { a -> Value(ceil(oneDouble(a)).toLong()) },
    "ln" to { a -> unaryDouble(a) { ln(it) } },
    "log10" to { a -> unaryDouble(a) { log10(it) } },
    "log" to { a -> unaryDouble(a) { ln(it) } },
    "exp" to { a -> unaryDouble(a) { exp(it) } },
    "sin" to { a -> unaryDouble(a) { sin(it) } },
    "cos" to { a -> unaryDouble(a) { cos(it) } },
    "tan" to { a -> unaryDouble(a) { tan(it) } },
    "asin" to { a -> unaryDouble(a) { asin(it) } },
    "acos" to { a -> unaryDouble(a) { acos(it) } },
    "atan" to { a -> unaryDouble(a) { atan(it) } },
    "atan2" to { a -> val (y, x) = twoDoubles(a); Value(atan2(y, x)) },
    "toint" to { a -> Value(oneDouble(a).toLong()) },
    "tointeger" to { a -> Value(oneDouble(a).toLong()) },
    "tofloat" to { a -> Value(oneDouble(a)) },
    "todouble" to { a -> Value(oneDouble(a)) },
    "toboolean" to { a -> requireArgs(a, 1); Value(truth(a[0])) },
    "tostring" to { a -> requireArgs(a, 1); Value(stringify(a[0])) },
    "len" to { a -> requireArgs(a, 1); Value(stringify(a[0]).length) },
    "lower" to { a -> requireArgs(a, 1); Value(stringify(a[0]).lowercase()) },
    "upper" to { a -> requireArgs(a, 1); Value(stringify(a[0]).uppercase()) },
    "trim" to { a -> requireArgs(a, 1); Value(stringify(a[0]).trim()) },
    "left" to { a ->
        requireArgs(a, 2)
        val s = stringify(a[0]); val n = intArg(a, 1)
        if (n < 0) throw ExpressionException("left() count is negative")
        Value(s.take(n))
    },
    "right" to { a ->
        requireArgs(a, 2)
        val s = stringify(a[0]); val n = intArg(a, 1)
        if (n < 0) throw ExpressionException("right() count is negative")
        Value(s.takeLast(n))
    },
    "substring" to { a ->
        requireArgs(a, 3)
        val s = stringify(a[0])
        val start = intArg(a, 1)
        val end = intArg(a, 2)
        if (start < 0 || end < start || end > s.length) throw ExpressionException("substring range $start..$end is outside '${s.length}'")
        Value(s.substring(start, end))
    },
    "replace" to { a ->
        requireArgs(a, 3)
        Value(stringify(a[0]).replace(stringify(a[1]), stringify(a[2])))
    },
    "indexof" to { a ->
        requireArgs(a, 2)
        Value(stringify(a[0]).indexOf(stringify(a[1])))
    },
    "concat" to { a ->
        if (a.isEmpty()) throw ExpressionException("concat() needs at least one argument")
        Value(a.joinToString("") { stringify(it) })
    },
)

private fun requireArgs(a: List<Any?>, n: Int) {
    if (a.size < n) throw ExpressionException("expected $n arguments, got ${a.size}")
}

private fun oneDouble(a: List<Any?>): Double {
    requireArgs(a, 1)
    return num(a[0]) ?: throw ExpressionException("argument is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
}

private fun intArg(a: List<Any?>, i: Int): Int {
    requireArgs(a, i + 1)
    return num(a[i])?.toInt() ?: throw ExpressionException("argument is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
}

private fun twoDoubles(a: List<Any?>): Pair<Double, Double> {
    requireArgs(a, 2)
    val x = num(a[0]) ?: throw ExpressionException("argument is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
    val y = num(a[1]) ?: throw ExpressionException("argument is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
    return x to y
}

private fun unaryDouble(a: List<Any?>, f: (Double) -> Double): Value = Value(f(oneDouble(a)))

private fun unaryNum(a: List<Any?>, f: (Double, Boolean) -> Any?): Value {
    requireArgs(a, 1)
    val n = num(a[0]) ?: throw ExpressionException("argument is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
    return Value(f(n, isIntegral(a[0])))
}

private fun extreme(a: List<Any?>, maxNotMin: Boolean): Value {
    if (a.size < 2) throw ExpressionException("min/max need at least 2 arguments")
    var best = a[0]
    var bestN = num(best) ?: throw ExpressionException("argument is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
    for (i in 1 until a.size) {
        val n = num(a[i]) ?: throw ExpressionException("argument is not numeric", QualityCode.ERROR_TYPE_CONVERSION)
        if (if (maxNotMin) n > bestN else n < bestN) {
            best = a[i]; bestN = n
        }
    }
    return Value(best)
}

private class Parser(private val text: String) {
    private var i = 0
    private val n = text.length

    fun parse(): Expr {
        skip()
        if (i >= n) throw ExpressionException("empty expression")
        val e = parseOr()
        skip()
        if (i < n) throw ExpressionException("unexpected '${text[i]}' at $i")
        return e
    }

    private fun parseOr(): Expr {
        var e = parseAnd()
        while (match("||")) e = Expr.Binary("||", e, parseAnd())
        return e
    }

    private fun parseAnd(): Expr {
        var e = parseCmp()
        while (match("&&")) e = Expr.Binary("&&", e, parseCmp())
        return e
    }

    private fun parseCmp(): Expr {
        var e = parseBitOr()
        while (true) {
            val op = when {
                match(">=") -> ">="
                match("<=") -> "<="
                match("!=") -> "!="
                match("==") -> "="
                match("=") -> "="
                match(">") -> ">"
                match("<") -> "<"
                matchWord("like") -> "like"
                else -> null
            } ?: break
            e = Expr.Binary(op, e, parseBitOr())
        }
        return e
    }

    private fun parseBitOr(): Expr {
        var e = parseXor()
        while (match("|")) e = Expr.Binary("|", e, parseXor())
        return e
    }

    private fun parseXor(): Expr {
        var e = parseBitAnd()
        while (matchWord("xor")) e = Expr.Binary("xor", e, parseBitAnd())
        return e
    }

    private fun parseBitAnd(): Expr {
        var e = parseShift()
        while (match("&")) e = Expr.Binary("&", e, parseShift())
        return e
    }

    private fun parseShift(): Expr {
        var e = parseAdd()
        while (true) {
            val op = when {
                match("<<") -> "<<"
                match(">>") -> ">>"
                else -> null
            } ?: break
            e = Expr.Binary(op, e, parseAdd())
        }
        return e
    }

    private fun parseAdd(): Expr {
        var e = parseMul()
        while (true) {
            val op = when {
                match("+") -> "+"
                match("-") -> "-"
                else -> null
            } ?: break
            e = Expr.Binary(op, e, parseMul())
        }
        return e
    }

    private fun parseMul(): Expr {
        var e = parsePow()
        while (true) {
            val op = when {
                match("*") -> "*"
                match("/") -> "/"
                match("%") -> "%"
                else -> null
            } ?: break
            e = Expr.Binary(op, e, parsePow())
        }
        return e
    }

    private fun parsePow(): Expr {
        val e = parseUnary()
        return if (match("^")) Expr.Binary("^", e, parsePow()) else e
    }

    private fun parseUnary(): Expr {
        return when {
            match("!") -> Expr.Unary("!", parseUnary())
            match("~") -> Expr.Unary("~", parseUnary())
            match("-") -> Expr.Unary("-", parseUnary())
            match("+") -> parseUnary()
            else -> parsePostfix(parsePrimary())
        }
    }

    private fun parsePostfix(start: Expr): Expr {
        var e = start
        while (true) {
            skip()
            if (!match("[")) break
            val first = parseOr()
            val indexes = if (match(",")) listOf(first, parseOr()) else listOf(first)
            expect("]")
            e = Expr.Index(e, indexes)
        }
        return e
    }

    private fun parsePrimary(): Expr {
        skip()
        if (i >= n) throw ExpressionException("unexpected end of expression")
        return when {
            text[i] == '(' -> { i++; val e = parseOr(); expect(")"); e }
            text[i] == '{' -> parseRef()
            text[i] == '"' || text[i] == '\'' -> Expr.Lit(parseString())
            text[i].isDigit() || (text[i] == '.' && i + 1 < n && text[i + 1].isDigit()) -> parseNumber()
            text[i].isLetter() || text[i] == '_' -> parseIdentOrCall()
            else -> throw ExpressionException("unexpected '${text[i]}' at $i")
        }
    }

    private fun parseRef(): Expr {
        i++
        val start = i
        while (i < n && text[i] != '}') i++
        if (i >= n) throw ExpressionException("unclosed '{'")
        val body = text.substring(start, i)
        i++
        return Expr.Ref(body)
    }

    private fun parseIdentOrCall(): Expr {
        val ident = parseIdent()
        skip()
        if (i < n && text[i] == '(') {
            i++
            val args = ArrayList<Expr>()
            skip()
            if (i < n && text[i] != ')') {
                args += parseOr()
                while (match(",")) args += parseOr()
            }
            expect(")")
            return when (ident.lowercase()) {
                "true" -> Expr.Lit(true)
                "false" -> Expr.Lit(false)
                "null", "none" -> Expr.Lit(null)
                else -> Expr.Call(ident, args)
            }
        }
        return when (ident.lowercase()) {
            "true" -> Expr.Lit(true)
            "false" -> Expr.Lit(false)
            "null", "none" -> Expr.Lit(null)
            else -> throw ExpressionException("unknown identifier '$ident'")
        }
    }

    private fun parseIdent(): String {
        val start = i
        if (i >= n || !(text[i].isLetter() || text[i] == '_')) throw ExpressionException("expected identifier at $i")
        i++
        while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        return text.substring(start, i)
    }

    private fun parseNumber(): Expr.Lit {
        val start = i
        if (text.startsWith("0x", i, true)) {
            i += 2
            val h = i
            while (i < n && text[i].isHex()) i++
            if (h == i) throw ExpressionException("bad hex literal")
            val hex = text.substring(h, i)
            return try {
                Expr.Lit(hex.toLong(16))
            } catch (_: NumberFormatException) {
                throw ExpressionException("hex literal is outside a 64-bit integer")
            }
        }
        while (i < n && text[i].isDigit()) i++
        if (i < n && text[i] == '.' && i + 1 < n && text[i + 1].isDigit()) {
            i++
            while (i < n && text[i].isDigit()) i++
        }
        if (i < n && (text[i] == 'e' || text[i] == 'E')) {
            val e = i
            i++
            if (i < n && (text[i] == '+' || text[i] == '-')) i++
            val d = i
            while (i < n && text[i].isDigit()) i++
            if (d == i) i = e
        }
        val s = text.substring(start, i)
        if (s.any { it == '.' || it == 'e' || it == 'E' }) return Expr.Lit(s.toDouble())
        return try {
            Expr.Lit(s.toLong())
        } catch (_: NumberFormatException) {
            val wide = s.toDouble()
            if (!wide.isFinite()) throw ExpressionException("integer literal '$s' is out of range")
            Expr.Lit(wide)
        }
    }

    private fun parseString(): String {
        val quote = text[i++]
        val sb = StringBuilder()
        while (i < n) {
            val c = text[i++]
            when {
                c == quote -> return sb.toString()
                c == '\\' && i < n -> sb.append(when (val e = text[i++]) {
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    '\\' -> '\\'
                    '\'' -> '\''
                    '"' -> '"'
                    else -> e
                })
                else -> sb.append(c)
            }
        }
        throw ExpressionException("unclosed string")
    }

    private fun match(op: String): Boolean {
        skip()
        if (!text.startsWith(op, i)) return false
        val after = i + op.length
        if (after < n) {
            val next = text[after]
            if (op == "!" && next == '=') return false
            if (op == "<" && (next == '=' || next == '<')) return false
            if (op == ">" && (next == '=' || next == '>')) return false
            if (op == "|" && next == '|') return false
            if (op == "&" && next == '&') return false
            if (op == "=" && next == '=') return false
        }
        i = after
        return true
    }

    private fun matchWord(word: String): Boolean {
        skip()
        if (!text.startsWith(word, i, true)) return false
        val after = i + word.length
        if (after < n && (text[after].isLetterOrDigit() || text[after] == '_')) return false
        i = after
        return true
    }

    private fun expect(op: String) {
        if (!match(op)) throw ExpressionException("expected '$op' at $i")
    }

    private fun skip() {
        while (i < n) {
            when {
                text[i].isWhitespace() -> i++
                text.startsWith("//", i) -> while (i < n && text[i] != '\n') i++
                else -> return
            }
        }
    }
}

private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
