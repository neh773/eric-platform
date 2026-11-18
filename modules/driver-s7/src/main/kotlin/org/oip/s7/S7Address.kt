package org.oip.s7

/**
 * Absolute S7 address syntax. Doc source: FEATURES DEV-025, DEV-036. Differences: DIFFERENCES 321-330.
 *
 * An address is an area, a data type, and a byte offset. Offsets are absolute: IW0 and IW1 share
 * a byte, and two consecutive words are IW0 and IW2. Bits use the X type and `.bit` (0..7) on a
 * byte offset. Strings are the S7 string layout (max byte, actual length, characters) with a
 * maximum of 210 characters (`STRING` or `STRING.LEN`). Timers and counters take no data type
 * (`T0`, `C0`). Timers are S5TIME values scaled to milliseconds. Counters are BCD exposed as an
 * unsigned count.
 *
 * S7-1200 and S7-1500 do not support the timer or counter areas. DATE_AND_TIME is not supported
 * on S7-1200. LInt is S7-1500 only. LReal is S7-1200 and S7-1500 only.
 *
 * Full absolute syntax (case-insensitive, spaces ignored):
 *
 * | Form | Meaning |
 * |---|---|
 * | `IX0.0`, `QX1.7`, `MX10.3` | bit (the X is required: `I0.0` is rejected) |
 * | `IB0`, `QC1`, `MW2`, `MI2`, `MD4`, `MDI4` | byte, signed char, word, int, dword, dint |
 * | `MREAL4`, `MLREAL8`, `MLI8`, `MDT8` | real, lreal, lint, DATE_AND_TIME |
 * | `MSTRING20`, `MSTRING20.10` | string at byte 20, max 210 or max 10 characters |
 * | `DB10,X4.2`, `DB10,B4`, `DB10,W4`, `DB10,REAL8`, `DB10,STRING12.4`, ... | the same types in DB10 |
 * | `DB10.DBX4.2`, `DB10.DBB4`, `DB10.DBW4`, `DB10.DBD4` | dotted forms of bit, byte, word, dword |
 * | `T0`, `C0` | timer, counter (S7-300 and S7-400) |
 *
 * Symbolic addresses (`"MotorData".Speed`, `DB10.Motor.Speed`) resolve through the device's
 * [S7SymbolTable] to one of the DB forms above. Optimized-access blocks (no fixed offsets, reached
 * only over S7comm-plus) are not supported: their members must be read from a block with
 * absolute access (DIFFERENCES 321).
 */
class AddressException(message: String) : IllegalArgumentException(message)

class TypeMismatchException(message: String) : IllegalArgumentException(message)

class OutOfRangeException(message: String) : IllegalArgumentException(message)

/** CPU family. Rack/slot defaults follow the public device page (rack 0, slot 2) unless overridden. */
enum class S7Model { S7_300, S7_400, S7_1200, S7_1500 }

enum class S7Area { I, Q, M, DB, T, C }

enum class S7Type {
    BIT, BYTE, CHAR, WORD, INT, DWORD, DINT, REAL, STRING, DT, LINT, LREAL, TIMER, COUNTER,
}

enum class S7Kind { MEMORY, TIMER, COUNTER }

/**
 * One parsed address. Listener fields are the scan hot path: one listener is the usual case,
 * and the list is replaced rather than mutated so a scan can dispatch without a lock.
 */
class ParsedAddress(
    val raw: String,
    val area: S7Area,
    val db: Int,
    val type: S7Type,
    val start: Int,
    /** Bytes occupied in a memory area, or 1 element for a timer or counter. */
    val span: Int,
    val bit: Int? = null,
    /** Declared S7 string maximum (1..210). Null when the address is not a string. */
    val stringMax: Int? = null,
) {
    val kind: S7Kind = when (area) {
        S7Area.T -> S7Kind.TIMER
        S7Area.C -> S7Kind.COUNTER
        else -> S7Kind.MEMORY
    }
    val endExclusive: Int get() = start + span
    val writable: Boolean = true

    @Volatile internal var rawOne: RawReg? = null
    @Volatile internal var rawMore: List<RawReg>? = null
    internal var lastGood: Any? = null
    internal var hasLast: Boolean = false

    @Synchronized
    internal fun addRaw(scanClass: String, sample: RawSample) {
        val reg = RawReg(scanClass, sample)
        val one = rawOne
        val more = rawMore
        if (one == null && more == null) {
            rawOne = reg
            return
        }
        val next = ArrayList<RawReg>((more?.size ?: 0) + 2)
        if (one != null) next.add(one)
        if (more != null) next.addAll(more)
        next.add(reg)
        rawMore = next
        rawOne = null
    }

    @Synchronized
    internal fun removeRaw(sample: RawSample) {
        if (rawOne?.sample === sample) {
            rawOne = null
            return
        }
        val more = rawMore ?: return
        rawMore = more.filter { it.sample !== sample }.ifEmpty { null }
    }

    internal fun hasRaw(scanClass: String): Boolean {
        val one = rawOne
        if (one != null) return one.scanClass == scanClass
        val more = rawMore ?: return false
        for (reg in more) if (reg.scanClass == scanClass) return true
        return false
    }

    internal fun dispatch(scanClass: String, value: Any?, quality: Quality, epochMillis: Long) {
        val one = rawOne
        if (one != null) {
            if (one.scanClass == scanClass) one.sample.onSample(value, quality, epochMillis)
            return
        }
        val more = rawMore ?: return
        for (reg in more) if (reg.scanClass == scanClass) reg.sample.onSample(value, quality, epochMillis)
    }

    override fun toString(): String = raw
}

internal class RawReg(val scanClass: String, val sample: RawSample)

object S7Address {
    /** Largest byte offset that still fits in the 3-byte S7 bit address. */
    const val MAX_BYTE = (0xFFFFFF / 8)

    const val MAX_STRING = 210

    private val TYPE_KEYS = listOf("LREAL", "STRING", "REAL", "DT", "DI", "LI", "X", "B", "C", "W", "I", "D")

    private val TYPE_BY_KEY = mapOf(
        "X" to S7Type.BIT,
        "B" to S7Type.BYTE,
        "C" to S7Type.CHAR,
        "W" to S7Type.WORD,
        "I" to S7Type.INT,
        "D" to S7Type.DWORD,
        "DI" to S7Type.DINT,
        "REAL" to S7Type.REAL,
        "STRING" to S7Type.STRING,
        "DT" to S7Type.DT,
        "LI" to S7Type.LINT,
        "LREAL" to S7Type.LREAL,
    )

    /**
     * Parse an absolute address, or a symbolic one through [symbols] (see [S7SymbolTable]).
     * A symbolic address keeps [raw] as its [ParsedAddress.raw]; area, block and offset come from
     * the table. The model limits apply to the resolved type either way.
     */
    fun parse(raw: String, model: S7Model, symbols: S7SymbolTable): ParsedAddress {
        val absolute = symbols.resolve(raw) ?: return parse(raw, model)
        val p = try {
            parse(absolute, model)
        } catch (e: AddressException) {
            throw AddressException("symbol '${raw.trim()}' ($absolute): ${e.message}")
        }
        return ParsedAddress(raw, p.area, p.db, p.type, p.start, p.span, p.bit, p.stringMax)
    }

    fun parse(raw: String, model: S7Model): ParsedAddress {
        val text = raw.trim()
        if (text.isEmpty()) throw AddressException("invalid address '$raw'")
        if (text.startsWith("\"")) throw AddressException("symbolic address '$text' needs a symbol table on the device")
        val upper = text.uppercase().replace(" ", "")
        val parsed = when {
            upper.startsWith("DB") -> parseDb(text, upper)
            isIndex(upper, 'T') -> indexAddress(text, S7Area.T, S7Type.TIMER, upper.substring(1))
            isIndex(upper, 'C') -> indexAddress(text, S7Area.C, S7Type.COUNTER, upper.substring(1))
            upper[0] == 'I' || upper[0] == 'Q' || upper[0] == 'M' -> parseMemory(text, upper)
            else -> throw AddressException("invalid address '$raw'")
        }
        if (!supported(model, parsed.type)) {
            throw AddressException("${parsed.type} is not supported on $model (${text})")
        }
        return parsed
    }

    /**
     * Model limits from the public driver page.
     * Timer and counter areas: S7-300 and S7-400 only (1200/1500 absolute driver does not support them).
     * DATE_AND_TIME: S7-300, S7-400, S7-1500. LInt: S7-1500. LReal: S7-1200 and S7-1500.
     */
    fun supported(model: S7Model, type: S7Type): Boolean = when (type) {
        S7Type.TIMER, S7Type.COUNTER -> model == S7Model.S7_300 || model == S7Model.S7_400
        S7Type.DT -> model != S7Model.S7_1200
        S7Type.LINT -> model == S7Model.S7_1500
        S7Type.LREAL -> model == S7Model.S7_1200 || model == S7Model.S7_1500
        else -> true
    }

    private fun parseDb(original: String, upper: String): ParsedAddress {
        var i = 2
        val start = i
        while (i < upper.length && upper[i].isDigit()) i++
        if (i == start || i >= upper.length) throw AddressException("invalid address '$original'")
        val db = parseInt(upper.substring(start, i), original)
        if (db !in 1..65535) throw AddressException("data block $db is outside 1..65535")
        if (upper[i] == '.') return parseDottedDb(original, upper.substring(i + 1), db)
        if (upper[i] != ',') throw AddressException("invalid address '$original'")
        return parseTyped(original, upper.substring(i + 1), S7Area.DB, db)
    }

    /**
     * Dotted data block forms: `DB10.DBX4.2` (bit), `DB10.DBB4` (byte), `DB10.DBW4` (word) and
     * `DB10.DBD4` (double word). They are the same as `DB10,X4.2`, `DB10,B4`, `DB10,W4`, `DB10,D4`.
     * Any other `DB10.name` is a symbol, which needs a symbol table.
     */
    private fun parseDottedDb(original: String, spec: String, db: Int): ParsedAddress {
        val key = if (spec.length >= 3 && spec.startsWith("DB")) spec[2] else null
        val type = when (key) {
            'X' -> "X"
            'B' -> "B"
            'W' -> "W"
            'D' -> "D"
            else -> null
        }
        if (type == null || spec.length < 4 || !spec[3].isDigit()) {
            throw AddressException("symbolic address '$original' needs a symbol table on the device")
        }
        return parseTyped(original, type + spec.substring(3), S7Area.DB, db)
    }

    private fun parseMemory(original: String, upper: String): ParsedAddress {
        val area = when (upper[0]) {
            'I' -> S7Area.I
            'Q' -> S7Area.Q
            'M' -> S7Area.M
            else -> throw AddressException("invalid address '$original'")
        }
        return parseTyped(original, upper.substring(1), area, 0)
    }

    private fun indexAddress(original: String, area: S7Area, type: S7Type, digits: String): ParsedAddress {
        val index = parseInt(digits, original)
        if (index !in 0..65535) throw AddressException("address $index is out of range")
        return ParsedAddress(original, area, 0, type, index, 1)
    }

    private fun parseTyped(original: String, spec: String, area: S7Area, db: Int): ParsedAddress {
        if (spec.isEmpty()) throw AddressException("invalid address '$original'")
        val key = TYPE_KEYS.firstOrNull { spec.startsWith(it) }
            ?: throw AddressException("unknown data type in '$original'")
        val type = TYPE_BY_KEY.getValue(key)
        val (offset, extra) = splitOffset(spec.substring(key.length), original)
        val bit: Int?
        val stringMax: Int?
        val span: Int
        when (type) {
            S7Type.BIT -> {
                if (extra == null || extra !in 0..7) throw AddressException("bit address requires .0 through .7 in '$original'")
                bit = extra
                stringMax = null
                span = 1
            }
            S7Type.STRING -> {
                if (extra != null && extra !in 1..MAX_STRING) {
                    throw AddressException("string length must be 1..$MAX_STRING")
                }
                bit = null
                stringMax = extra ?: MAX_STRING
                span = 2 + stringMax
            }
            else -> {
                if (extra != null) throw AddressException("invalid address '$original'")
                bit = null
                stringMax = null
                span = width(type)
            }
        }
        if (offset < 0 || offset.toLong() + span - 1 > MAX_BYTE) {
            throw AddressException("address $offset is out of range")
        }
        return ParsedAddress(original, area, db, type, offset, span, bit, stringMax)
    }

    private fun width(type: S7Type): Int = when (type) {
        S7Type.BIT, S7Type.BYTE, S7Type.CHAR -> 1
        S7Type.WORD, S7Type.INT -> 2
        S7Type.DWORD, S7Type.DINT, S7Type.REAL -> 4
        S7Type.DT, S7Type.LINT, S7Type.LREAL -> 8
        S7Type.STRING, S7Type.TIMER, S7Type.COUNTER -> 1
    }

    private fun splitOffset(rest: String, original: String): Pair<Int, Int?> {
        if (rest.isEmpty() || !rest[0].isDigit()) throw AddressException("invalid address '$original'")
        var i = 0
        while (i < rest.length && rest[i].isDigit()) i++
        val offset = parseInt(rest.substring(0, i), original)
        if (i == rest.length) return offset to null
        if (rest[i] != '.') throw AddressException("invalid address '$original'")
        val dot = i + 1
        if (dot >= rest.length || !rest[dot].isDigit()) throw AddressException("invalid address '$original'")
        var j = dot
        while (j < rest.length && rest[j].isDigit()) j++
        if (j != rest.length) throw AddressException("invalid address '$original'")
        return offset to parseInt(rest.substring(dot, j), original)
    }

    private fun parseInt(digits: String, original: String): Int {
        if (digits.length > 8) throw AddressException("address in '$original' is out of range")
        return try {
            digits.toInt()
        } catch (_: NumberFormatException) {
            throw AddressException("address in '$original' is out of range")
        }
    }

    private fun isIndex(upper: String, letter: Char): Boolean {
        if (upper.length < 2 || upper[0] != letter) return false
        for (i in 1 until upper.length) if (!upper[i].isDigit()) return false
        return true
    }
}
