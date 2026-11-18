package org.oip.simulator

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * Value programs for the programmable device simulator.
 *
 * The instruction table matches the public page: a time interval, a browse path, a value source,
 * and a data type. Value sources are `sine`, `cosine`, `square`, `triangle`, `ramp`, `realistic`,
 * `random`, `list`, `qv`, `readonly`, and a constant (a literal, or `constant(...)`).
 *
 *
 * Interpretations (the page describes the shape of each function and does not give a closed form):
 *
 * - Time is an interval index `n = elapsedMillis / baseRateMs`. Interval 0 is applied at n = 0.
 *   The program length is one past the highest time interval. `repeatProgram` wraps to interval 0;
 *   otherwise every address holds the value it had on the last interval.
 * - An address exists from the start. Until its first instruction it reads the zero of its data type
 *   (the page's TagC example).
 * - `sine(min, max, period, repeat)` at step `k` since that instruction became the active source:
 *   `min + (max - min) * sin(π * (k mod period) / period)`.
 *   It starts at min, reaches max halfway through the period, and returns to min. Defaults are
 *   min 0, max 100, period 10, repeat true, so one cycle is `period` base-rate intervals
 *   (10 s when the base rate is 1000 ms). The page's "((10 * Base Rate) * 1000 ms" reads as that
 *   10 s example, not an extra factor of 1000 on top of a base rate already in milliseconds.
 *   The 7.9 simulator page describes the same shape: low, then high, then back to low over one period.
 *   -
 * - `cosine(min, max, period, repeat)` stays inside `[min, max]`, starts at max, reaches min halfway
 *   through the period, and returns to max:
 *   `mid + amp * cos(2π * (k mod period) / period)` with `mid = (min + max) / 2` and `amp = (max - min) / 2`.
 *   A full turn is used so the sample at `k = period` matches `k = 0`. The page names the function and
 *   does not give this expression. Defaults match sine.
 * - `square(min, max, period, repeat)` is max for the first half of the period and min for the rest.
 *   Step `k` is max when `(k mod period) * 2 < period`, otherwise min. The extra sample of an odd
 *   period is on the max side (three highs and two lows when the period is 5). Period 1 is max.
 *   Defaults match sine.
 * - `triangle(min, max, period, repeat)` rises linearly from min to max over the first half of the
 *   period and falls linearly back to min over the second half. Period 1 is min. Defaults match sine.
 * - `ramp(min, max, period, repeat)` at step `k`:
 *   `min + (max - min) * (k mod period) / (period - 1)` for period > 1, and min when period is 1.
 *   The last step of the period is max. The next step is min (the reset). Defaults match sine.
 * - `realistic(setPoint, proportion, integral, derivative, repeat)` is a discrete PID. The page names
 *   the gains and the defaults (set point 100, proportion 1.2, integral 0.06, derivative 0.25, repeat
 *   true) and does not publish the plant. Step 0 is one control update from process value 0:
 *   `error = setPoint - pv`, `integral += error` (dt = 1 interval), derivative is 0 on the first step
 *   and `error - previousError` after that, `output = Kp*error + Ki*integral + Kd*derivative`,
 *   `pv += output * 0.05`. The plant gain 0.05 keeps the default gains from running away. Each later
 *   step does that update again. Gains must be finite; they may be zero or negative.
 * - `random(min, max, repeat)` draws one value per interval from `java.util.Random` seeded with
 *   [mixSeed]. The page does not publish the reference platform's generator. Integer addresses draw inclusive
 *   `[min, max]`; floating addresses draw `[min, max)`. Boolean addresses ignore min and max and
 *   draw `nextBoolean()`, as the page says those parameters are unused. Each address has its own
 *   stream. The same instruction keeps the stream; a different instruction starts it over.
 * - `list(v1, v2, ..., repeat)` publishes one entry per interval, wrapping while repeat is true
 *   (the default). A trailing `true` or `false` is that flag when the call has two or more
 *   arguments. `repeat` false publishes the first entry once.
 * - `qv(value, qualityCode)` publishes one value with quality Good, Bad, or Uncertain. The quality
 *   word defaults to Good. It is not a per-interval function: the value and quality hold until a
 *   later instruction publishes that address. A successful write replaces both with the written
 *   value and Good quality until the program publishes again.
 * - `readonly(value)` publishes one static value and rejects writes (Bad_NotWritable) until a later
 *   instruction that is not `readonly` publishes that address.
 * - A repeating function keeps its phase while that same instruction stays the active source, so
 *   a program whose only row is interval 0 still traces a full wave (the page's internal clock).
 *   A later instruction replaces the source and starts the new function at its first step.
 *   `repeat` false, and a numeric or string literal, publish only on the intervals where that
 *   instruction fires.
 * - A write replaces the current value and stays until the program next publishes that address.
 */
internal enum class SimType {
    BOOLEAN, INT16, UINT16, INT32, UINT32, INT64, UINT64, FLOAT, DOUBLE, STRING, DATETIME,
}

internal fun parseSimType(token: String): SimType = when (token.lowercase()) {
    "boolean" -> SimType.BOOLEAN
    "int16" -> SimType.INT16
    "uint16" -> SimType.UINT16
    "int32" -> SimType.INT32
    "uint32" -> SimType.UINT32
    "int64" -> SimType.INT64
    "uint64" -> SimType.UINT64
    "float" -> SimType.FLOAT
    "double" -> SimType.DOUBLE
    "string" -> SimType.STRING
    "datetime" -> SimType.DATETIME
    else -> throw IllegalArgumentException("unknown data type '$token'")
}

internal fun zeroOf(type: SimType): Any = when (type) {
    SimType.BOOLEAN -> false
    SimType.INT16, SimType.UINT16, SimType.INT32 -> 0
    SimType.UINT32, SimType.INT64, SimType.UINT64, SimType.DATETIME -> 0L
    SimType.FLOAT -> 0.0f
    SimType.DOUBLE -> 0.0
    SimType.STRING -> ""
}

/**
 * Seed for one address. `mixed` starts at [deviceSeed]; each UTF-16 code unit updates it with
 * `mixed = 31 * mixed + code` (Long overflow wraps). `java.util.Random` is then constructed with
 * that seed, which applies its own documented scramble.
 */
internal fun mixSeed(deviceSeed: Long, browsePath: String): Long {
    var mixed = deviceSeed
    for (ch in browsePath) mixed = 31L * mixed + ch.code
    return mixed
}

internal sealed interface Generator {
    val repeats: Boolean
}

internal data class ConstantGen(val value: Any, val persistWrite: Boolean = false) : Generator {
    override val repeats: Boolean = false
}

internal enum class WaveKind { SINE, COSINE, SQUARE, TRIANGLE, RAMP }

/**
 * Plant gain for [RealisticGen]. One control update moves the process value by `output *` this.
 * Chosen so the documented default gains (1.2, 0.06, 0.25) approach the set point from 0
 * without diverging. The public page does not publish a plant model.
 */
internal const val REALISTIC_PLANT_GAIN = 0.05

internal data class WaveGen(
    val kind: WaveKind,
    val min: Double,
    val max: Double,
    val period: Int,
    override val repeats: Boolean,
) : Generator {
    fun wave(k: Long): Double {
        val km = Math.floorMod(k, period.toLong())
        val span = max - min
        return when (kind) {
            WaveKind.SINE -> min + span * sin(PI * km.toDouble() / period)
            WaveKind.COSINE -> {
                val mid = (min + max) / 2.0
                val amp = span / 2.0
                mid + amp * cos(2.0 * PI * km.toDouble() / period)
            }
            WaveKind.SQUARE -> if (km * 2 < period) max else min
            WaveKind.TRIANGLE -> {
                if (period <= 1) min
                else {
                    val half = period / 2.0
                    val pos = km.toDouble()
                    val frac = if (pos <= half) pos / half else (period - pos) / half
                    min + span * frac
                }
            }
            WaveKind.RAMP -> if (period <= 1) min else min + span * (km.toDouble() / (period - 1))
        }
    }
}

internal data class RealisticGen(
    val setPoint: Double,
    val proportion: Double,
    val integral: Double,
    val derivative: Double,
    override val repeats: Boolean,
) : Generator

internal data class QvGen(val value: Any, val quality: Quality) : Generator {
    override val repeats: Boolean = false
}

internal data class ReadOnlyGen(val value: Any) : Generator {
    override val repeats: Boolean = false
}

internal data class RandomGen(
    val min: Double,
    val max: Double,
    override val repeats: Boolean,
    val seed: Long,
    val type: SimType,
) : Generator

internal data class ListGen(val values: List<Any>, override val repeats: Boolean) : Generator {
    fun at(k: Long): Any = if (!repeats) values[0] else values[Math.floorMod(k, values.size.toLong()).toInt()]
}

internal class CompiledPoint(
    val path: String,
    val type: SimType,
    val dataTypeName: String,
    val fires: IntArray,
    val generators: Array<Generator>,
    val initial: Any,
)

internal class CompiledProgram(
    val span: Int,
    val points: List<CompiledPoint>,
)

internal fun compileProgram(config: SimulatorDeviceConfig): CompiledProgram {
    require(config.baseRateMs >= 1) { "baseRateMs must be >= 1" }
    if (config.instructions.isEmpty()) return CompiledProgram(span = 1, points = emptyList())
    var maxInterval = 0
    val grouped = linkedMapOf<String, MutableList<SimInstruction>>()
    for (row in config.instructions) {
        if (row.timeInterval < 0) throw IllegalArgumentException("${row.browsePath}: time interval must be >= 0")
        if (row.browsePath.isBlank()) throw IllegalArgumentException("browse path is blank")
        if (row.timeInterval > maxInterval) maxInterval = row.timeInterval
        grouped.getOrPut(row.browsePath) { ArrayList() }.add(row)
    }
    val points = ArrayList<CompiledPoint>(grouped.size)
    for ((path, rows) in grouped) {
        val type = parseSimType(rows.first().dataType)
        if (rows.any { parseSimType(it.dataType) != type }) {
            throw IllegalArgumentException("$path has more than one data type")
        }
        val byTime = linkedMapOf<Int, Generator>()
        for (row in rows) {
            try {
                val parsed = parseValueSource(row.valueSource, type, config.randomSeed, path)
                // Only a constant can hold a write across a later firing of the same instruction.
                // Repeating functions and user-program literals still publish when they fire.
                byTime[row.timeInterval] = if (path in config.persistWrites && parsed is ConstantGen) {
                    parsed.copy(persistWrite = true)
                } else {
                    parsed
                }
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$path: ${e.message}", e)
            }
        }
        val times = byTime.keys.sorted()
        points += CompiledPoint(
            path = path,
            type = type,
            dataTypeName = rows.first().dataType,
            fires = IntArray(times.size) { times[it] },
            generators = Array(times.size) { byTime.getValue(times[it]) },
            initial = zeroOf(type),
        )
    }
    return CompiledProgram(span = maxInterval + 1, points = points)
}

internal fun parseValueSource(text: String, type: SimType, deviceSeed: Long, path: String): Generator {
    val src = Src(text.trim())
    if (src.done()) throw IllegalArgumentException("value source is blank")
    val gen = if (src.peek()?.isLetter() == true) {
        val name = src.ident()
        src.skip()
        if (!src.consume('(')) {
            if (name.equals("true", true)) return ConstantGen(coerceExact(type, true))
            if (name.equals("false", true)) return ConstantGen(coerceExact(type, false))
            throw IllegalArgumentException("unknown value source '$text'")
        }
        val args = src.args()
        src.skip()
        if (!src.done()) throw IllegalArgumentException("trailing text in '$text'")
        when (name.lowercase()) {
            "sine" -> wave(WaveKind.SINE, args, type)
            "cosine" -> wave(WaveKind.COSINE, args, type)
            "square" -> wave(WaveKind.SQUARE, args, type)
            "triangle" -> wave(WaveKind.TRIANGLE, args, type)
            "ramp" -> wave(WaveKind.RAMP, args, type)
            "realistic" -> realistic(args, type)
            "random" -> random(args, type, deviceSeed, path)
            "list" -> listGen(args, type)
            "qv" -> qv(args, type)
            "readonly" -> readOnly(args, type)
            "constant" -> {
                if (args.size != 1) throw IllegalArgumentException("constant takes one argument")
                ConstantGen(coerceExact(type, args[0]))
            }
            else -> throw IllegalArgumentException("unknown function '$name'")
        }
    } else {
        val literal = src.literal()
        src.skip()
        if (!src.done()) throw IllegalArgumentException("trailing text in '$text'")
        ConstantGen(coerceExact(type, literal))
    }
    return gen
}

private fun wave(kind: WaveKind, args: List<Any>, type: SimType): WaveGen {
    if (type == SimType.STRING) throw IllegalArgumentException("${kind.name.lowercase()} is numeric")
    if (args.size > 4) throw IllegalArgumentException("${kind.name.lowercase()} takes at most 4 arguments")
    val min = if (args.isEmpty()) 0.0 else numberArg(args[0], "min")
    val max = if (args.size < 2) 100.0 else numberArg(args[1], "max")
    val period = if (args.size < 3) 10 else intArg(args[2], "period")
    val repeat = if (args.size < 4) true else boolArg(args[3], "repeat")
    if (period < 1) throw IllegalArgumentException("period must be >= 1")
    if (!min.isFinite() || !max.isFinite()) throw IllegalArgumentException("min and max must be finite")
    return WaveGen(kind, min, max, period, repeat)
}

private fun realistic(args: List<Any>, type: SimType): RealisticGen {
    if (type == SimType.STRING) throw IllegalArgumentException("realistic is numeric")
    if (args.size > 5) throw IllegalArgumentException("realistic takes at most 5 arguments")
    val setPoint = if (args.isEmpty()) 100.0 else numberArg(args[0], "setPoint")
    val proportion = if (args.size < 2) 1.2 else numberArg(args[1], "proportion")
    val integral = if (args.size < 3) 0.06 else numberArg(args[2], "integral")
    val derivative = if (args.size < 4) 0.25 else numberArg(args[3], "derivative")
    val repeat = if (args.size < 5) true else boolArg(args[4], "repeat")
    if (!setPoint.isFinite() || !proportion.isFinite() || !integral.isFinite() || !derivative.isFinite()) {
        throw IllegalArgumentException("realistic gains must be finite")
    }
    return RealisticGen(setPoint, proportion, integral, derivative, repeat)
}

private fun qv(args: List<Any>, type: SimType): QvGen {
    if (args.isEmpty() || args.size > 2) throw IllegalArgumentException("qv takes a value and an optional qualityCode")
    val quality = if (args.size == 1) Quality.GOOD else qualityArg(args[1])
    return QvGen(coerceExact(type, args[0]), quality)
}

private fun readOnly(args: List<Any>, type: SimType): ReadOnlyGen {
    if (args.size != 1) throw IllegalArgumentException("readonly takes one argument")
    return ReadOnlyGen(coerceExact(type, args[0]))
}

private fun qualityArg(value: Any): Quality {
    val name = when (value) {
        is Word -> value.text
        is String -> value
        else -> throw IllegalArgumentException("qualityCode must be Good, Bad, or Uncertain")
    }
    return when (name.lowercase()) {
        "good" -> Quality.GOOD
        "bad" -> Quality.BAD
        "uncertain" -> Quality.UNCERTAIN
        else -> throw IllegalArgumentException("qualityCode must be Good, Bad, or Uncertain")
    }
}

private fun random(args: List<Any>, type: SimType, deviceSeed: Long, path: String): RandomGen {
    if (type == SimType.STRING) throw IllegalArgumentException("random is numeric or boolean")
    if (args.size > 3) throw IllegalArgumentException("random takes at most 3 arguments")
    val min = if (args.isEmpty()) 0.0 else numberArg(args[0], "min")
    val max = if (args.size < 2) 100.0 else numberArg(args[1], "max")
    val repeat = if (args.size < 3) true else boolArg(args[2], "repeat")
    if (type != SimType.BOOLEAN) {
        if (!min.isFinite() || !max.isFinite() || min > max) throw IllegalArgumentException("random range is empty")
        if (type != SimType.FLOAT && type != SimType.DOUBLE) {
            val lo = exactLong(min) ?: throw IllegalArgumentException("random min is not an integer")
            val hi = exactLong(max) ?: throw IllegalArgumentException("random max is not an integer")
            val span = hi - lo + 1
            if (span <= 0 || span > Int.MAX_VALUE) throw IllegalArgumentException("random range does not fit an Int span")
            checkRange(type, lo)
            checkRange(type, hi)
        }
    }
    return RandomGen(min, max, repeat, mixSeed(deviceSeed, path), type)
}

private fun listGen(args: List<Any>, type: SimType): ListGen {
    if (args.isEmpty()) throw IllegalArgumentException("list needs at least one value")
    val repeat: Boolean
    val raw: List<Any>
    if (args.size >= 2 && args.last() is Boolean) {
        repeat = args.last() as Boolean
        raw = args.subList(0, args.size - 1)
    } else {
        repeat = true
        raw = args
    }
    if (raw.isEmpty()) throw IllegalArgumentException("list needs at least one value")
    return ListGen(raw.map { coerceExact(type, it) }, repeat)
}

private fun numberArg(value: Any, name: String): Double = when (value) {
    is Number -> value.toDouble()
    else -> throw IllegalArgumentException("$name must be a number")
}

private fun intArg(value: Any, name: String): Int {
    val n = exactLong(value as? Number ?: throw IllegalArgumentException("$name must be an integer"))
        ?: throw IllegalArgumentException("$name must be an integer")
    if (n !in 1..1_000_000) throw IllegalArgumentException("$name is out of range")
    return n.toInt()
}

private fun boolArg(value: Any, name: String): Boolean =
    value as? Boolean ?: throw IllegalArgumentException("$name must be true or false")

/** User writes, literals, and list entries: integers must be integral, not rounded. */
internal fun coerceExact(type: SimType, value: Any?): Any {
    if (value == null) throw IllegalArgumentException("value is null")
    return when (type) {
        SimType.BOOLEAN -> when (value) {
            is Boolean -> value
            is Number -> when (val n = exactLong(value)) {
                0L -> false
                1L -> true
                else -> throw IllegalArgumentException("boolean value '$value'")
            }
            is String -> when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("boolean value '$value'")
            }
            else -> throw IllegalArgumentException("boolean value '$value'")
        }
        SimType.STRING -> value as? String ?: throw IllegalArgumentException("string value required")
        SimType.DATETIME -> when (value) {
            is Number -> {
                val n = exactLong(value) ?: throw IllegalArgumentException("datetime is not an epoch millis integer")
                n
            }
            is String -> try {
                java.time.Instant.parse(value).toEpochMilli()
            } catch (_: java.time.DateTimeException) {
                throw IllegalArgumentException("datetime '$value' is not an ISO-8601 instant")
            }
            else -> throw IllegalArgumentException("datetime value required")
        }
        SimType.FLOAT -> {
            val n = value as? Number ?: throw IllegalArgumentException("number required")
            val d = n.toDouble()
            if (!d.isFinite()) throw IllegalArgumentException("number is not finite")
            d.toFloat()
        }
        SimType.DOUBLE -> {
            val n = value as? Number ?: throw IllegalArgumentException("number required")
            val d = n.toDouble()
            if (!d.isFinite()) throw IllegalArgumentException("number is not finite")
            d
        }
        else -> {
            val n = value as? Number ?: throw IllegalArgumentException("integer required")
            val l = exactLong(n) ?: throw IllegalArgumentException("value is not an integer")
            narrow(type, l)
        }
    }
}

/** Computed wave samples. Integer addresses are rounded; out-of-range results clamp. */
internal fun boxWave(type: SimType, value: Double): Any {
    return when (type) {
        SimType.DOUBLE -> value
        SimType.FLOAT -> value.toFloat()
        SimType.BOOLEAN -> value != 0.0 && !value.isNaN()
        SimType.STRING -> value.toString()
        SimType.DATETIME -> round(value).toLong()
        else -> {
            if (!value.isFinite()) zeroOf(type)
            else narrow(type, round(value).toLong(), clamp = true)
        }
    }
}

internal fun drawRandom(rng: java.util.Random, gen: RandomGen): Any = when (gen.type) {
    SimType.BOOLEAN -> rng.nextBoolean()
    SimType.DOUBLE -> gen.min + rng.nextDouble() * (gen.max - gen.min)
    SimType.FLOAT -> (gen.min + rng.nextDouble() * (gen.max - gen.min)).toFloat()
    SimType.STRING -> (gen.min + rng.nextDouble() * (gen.max - gen.min)).toString()
    SimType.DATETIME -> {
        val lo = gen.min.toLong()
        val span = (gen.max.toLong() - lo + 1).toInt()
        lo + rng.nextInt(span)
    }
    else -> {
        val lo = gen.min.toLong()
        val span = (gen.max.toLong() - lo + 1).toInt()
        narrow(gen.type, lo + rng.nextInt(span))
    }
}

private fun exactLong(n: Number): Long? = when (n) {
    is Double -> if (n.isFinite() && n.toLong().toDouble() == n) n.toLong() else null
    is Float -> if (n.isFinite() && n.toLong().toFloat() == n) n.toLong() else null
    else -> n.toLong()
}

private fun narrow(type: SimType, value: Long, clamp: Boolean = false): Any {
    val limited = if (clamp) clamp(type, value) else checkRange(type, value)
    return when (type) {
        SimType.INT16, SimType.UINT16, SimType.INT32 -> limited.toInt()
        else -> limited
    }
}

private fun checkRange(type: SimType, value: Long): Long {
    val (lo, hi) = limits(type)
    if (value !in lo..hi) throw IllegalArgumentException("value $value is outside $type")
    return value
}

private fun clamp(type: SimType, value: Long): Long {
    val (lo, hi) = limits(type)
    return value.coerceIn(lo, hi)
}

private fun limits(type: SimType): Pair<Long, Long> = when (type) {
    SimType.INT16 -> Short.MIN_VALUE.toLong() to Short.MAX_VALUE.toLong()
    SimType.UINT16 -> 0L to 65_535L
    SimType.INT32 -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
    SimType.UINT32 -> 0L to 4_294_967_295L
    SimType.INT64 -> Long.MIN_VALUE to Long.MAX_VALUE
    SimType.UINT64 -> 0L to Long.MAX_VALUE
    else -> Long.MIN_VALUE to Long.MAX_VALUE
}

/** An unquoted quality word (`Good`, `Bad`, `Uncertain`) inside a value-source argument list. */
internal data class Word(val text: String)

private class Src(private val text: String) {
    private var i = 0

    fun done(): Boolean {
        skip()
        return i >= text.length
    }

    fun peek(): Char? = if (i >= text.length) null else text[i]

    fun skip() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    fun consume(ch: Char): Boolean {
        skip()
        if (i < text.length && text[i] == ch) {
            i++
            return true
        }
        return false
    }

    fun ident(): String {
        skip()
        val start = i
        while (i < text.length && text[i].isLetter()) i++
        if (start == i) throw IllegalArgumentException("expected a name in '$text'")
        return text.substring(start, i)
    }

    fun args(): List<Any> {
        skip()
        if (consume(')')) return emptyList()
        val out = ArrayList<Any>()
        while (true) {
            out += literal()
            skip()
            when {
                consume(',') -> continue
                consume(')') -> return out
                else -> throw IllegalArgumentException("expected ',' or ')' in '$text'")
            }
        }
    }

    fun literal(): Any {
        skip()
        if (i >= text.length) throw IllegalArgumentException("expected a value in '$text'")
        if (text[i] == '"') return string()
        if (text[i].isLetter()) {
            val word = ident()
            return when {
                word.equals("true", true) -> true
                word.equals("false", true) -> false
                word.equals("good", true) -> Word("Good")
                word.equals("bad", true) -> Word("Bad")
                word.equals("uncertain", true) -> Word("Uncertain")
                else -> throw IllegalArgumentException("unknown token '$word'")
            }
        }
        return number()
    }

    private fun string(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (i < text.length) {
            val ch = text[i++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= text.length) throw IllegalArgumentException("bad escape in '$text'")
                    sb.append(when (val esc = text[i++]) {
                        '\\', '"' -> esc
                        'n' -> '\n'
                        else -> throw IllegalArgumentException("bad escape in '$text'")
                    })
                }
                else -> sb.append(ch)
            }
        }
        throw IllegalArgumentException("unterminated string in '$text'")
    }

    private fun number(): Number {
        val start = i
        if (text[i] == '+' || text[i] == '-') i++
        val digits = i
        while (i < text.length && text[i].isDigit()) i++
        var dot = false
        if (i < text.length && text[i] == '.') {
            dot = true
            i++
            while (i < text.length && text[i].isDigit()) i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            dot = true
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            val exp = i
            while (i < text.length && text[i].isDigit()) i++
            if (exp == i) throw IllegalArgumentException("bad number in '$text'")
        }
        val token = text.substring(start, i)
        if (token.isEmpty() || token == "+" || token == "-" || token == "." || token == "+." || token == "-." ||
            token.endsWith("e") || token.endsWith("E") || token.endsWith("+") || token.endsWith("-")
        ) {
            throw IllegalArgumentException("bad number in '$text'")
        }
        return try {
            if (!dot) token.toLong() else token.toDouble()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("bad number '$token'")
        }
    }
}
