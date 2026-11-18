package org.oip.s7

/**
 * Symbolic addressing through a per-device symbol table. Doc source: FEATURES DEV-026.
 * Differences: DIFFERENCES 321.
 *
 * A tag addresses a data block member by name instead of by byte offset:
 *
 * - `"MotorData".Speed` names the block in double quotes, then the member path.
 * - `DB10.Motor.Speed` names the block by number, then the member path.
 *
 * Member paths are dot separated; a nested struct member is written as one path
 * (`Motor.Speed`). Segments may be quoted (`"MotorData"."Speed"`). Names compare case-insensitively.
 * The dotted absolute forms `DB10.DBX4.2`, `DB10.DBB4`, `DB10.DBW4` and `DB10.DBD4` stay absolute
 * and are never looked up as members.
 *
 * The table is CSV, one member per line, five columns:
 *
 * ```
 * block,number,member,type,offset
 * MotorData,10,Speed,REAL,0
 * MotorData,10,Motor.Running,BOOL,4.0
 * MotorData,10,Name,STRING[20],6
 * ```
 *
 * The header line is optional. Blank lines and lines starting with `#` are skipped. `type` is an
 * S7 elementary type: BOOL, BYTE, USINT, CHAR, SINT, WORD, UINT, INT, DWORD, UDINT, DINT, REAL,
 * LREAL, LINT, DATE_AND_TIME (or DT), STRING or STRING[n]. `offset` is the byte offset in the
 * block, with `.bit` for BOOL. These are the offsets of a block with absolute (non-optimized)
 * access, as the engineering tool shows them in the block's offset column.
 *
 * The table is parsed when the device config loads; a malformed row rejects the config with its
 * line number. A tag whose symbol is not in the table is a [AddressException]: the device keeps
 * running and that tag reads Bad_ConfigurationError with the unknown name as its diagnostic.
 */
class S7SymbolTable private constructor(
    private val byNumber: Map<Int, Map<String, Symbol>>,
    private val numberByName: Map<String, Int>,
) {
    /** One member: its block, its path as written in the table, and the absolute address it maps to. */
    data class Symbol(val block: String, val db: Int, val member: String, val absolute: String)

    val isEmpty: Boolean get() = byNumber.isEmpty()

    val size: Int get() = byNumber.values.sumOf { it.size }

    /** Every member, in table order. */
    fun symbols(): List<Symbol> = byNumber.values.flatMap { it.values }

    /**
     * The absolute address for [text], or null when [text] is not symbolic syntax.
     * Throws [AddressException] when it is symbolic but not in the table.
     */
    fun resolve(text: String): String? {
        val ref = S7SymbolRef.parse(text) ?: return null
        val db = when {
            ref.db != null -> ref.db
            else -> numberByName[ref.block!!.lowercase()]
                ?: throw AddressException("unknown symbol '$text': no block \"${ref.block}\" in the symbol table")
        }
        val members = byNumber[db]
            ?: throw AddressException("unknown symbol '$text': no DB$db in the symbol table")
        val symbol = members[ref.member.lowercase()]
            ?: throw AddressException("unknown symbol '$text': no member '${ref.member}' in DB$db")
        return symbol.absolute
    }

    override fun equals(other: Any?): Boolean = other is S7SymbolTable && other.byNumber == byNumber

    override fun hashCode(): Int = byNumber.hashCode()

    override fun toString(): String = "S7SymbolTable($size symbols)"

    companion object {
        val EMPTY = S7SymbolTable(emptyMap(), emptyMap())

        private val HEADER = listOf("block", "number", "member", "type", "offset")

        fun parseCsv(text: String): S7SymbolTable {
            if (text.isBlank()) return EMPTY
            val byNumber = LinkedHashMap<Int, LinkedHashMap<String, Symbol>>()
            val numberByName = HashMap<String, Int>()
            val nameByNumber = HashMap<Int, String>()
            text.lines().forEachIndexed { index, line ->
                val lineNo = index + 1
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
                val cells = trimmed.split(',').map { it.trim().removeSurrounding("\"") }
                if (cells.map { it.lowercase() } == HEADER) return@forEachIndexed
                fun fail(why: String): Nothing = throw IllegalArgumentException("symbol table line $lineNo: $why")
                if (cells.size != 5) fail("expected 5 columns (block,number,member,type,offset), found ${cells.size}")
                val (block, numberText, member, typeText, offset) = cells
                if (block.isEmpty()) fail("block name is empty")
                if (block.contains('"')) fail("block name contains a quote")
                val db = numberText.toIntOrNull() ?: fail("block number '$numberText' is not a number")
                if (db !in 1..65535) fail("data block $db is outside 1..65535")
                val path = normalizeMember(member) ?: fail("member '$member' is not a dotted name")
                val known = numberByName.putIfAbsent(block.lowercase(), db)
                if (known != null && known != db) fail("block \"$block\" is DB$known on an earlier line")
                val named = nameByNumber.putIfAbsent(db, block)
                if (named != null && !named.equals(block, ignoreCase = true)) fail("DB$db is \"$named\" on an earlier line")
                val absolute = absoluteOf(db, typeText, offset) { fail(it) }
                try {
                    S7Address.parse(absolute, S7Model.S7_1500)
                } catch (e: AddressException) {
                    fail(e.message ?: "bad offset")
                }
                val members = byNumber.getOrPut(db) { LinkedHashMap() }
                if (members.containsKey(path.lowercase())) fail("member '$path' appears twice in DB$db")
                members[path.lowercase()] = Symbol(block, db, path, absolute)
            }
            return S7SymbolTable(byNumber, numberByName)
        }

        private fun absoluteOf(db: Int, typeText: String, offset: String, fail: (String) -> Nothing): String {
            val type = typeText.uppercase().replace(" ", "")
            val string = Regex("^STRING(?:\\[(\\d+)])?$").matchEntire(type)
            if (!offset.matches(Regex("^\\d+(\\.\\d+)?$"))) fail("offset '$offset' is not byte or byte.bit")
            val hasBit = offset.contains('.')
            if (type == "BOOL") {
                if (!hasBit) fail("BOOL offset needs .bit (for example 4.0)")
                return "DB$db,X$offset"
            }
            if (hasBit) fail("only BOOL takes a .bit offset")
            if (string != null) {
                val len = string.groupValues[1]
                return if (len.isEmpty()) "DB$db,STRING$offset" else "DB$db,STRING$offset.$len"
            }
            val key = when (type) {
                "BYTE", "USINT" -> "B"
                "CHAR", "SINT" -> "C"
                "WORD", "UINT" -> "W"
                "INT" -> "I"
                "DWORD", "UDINT" -> "D"
                "DINT" -> "DI"
                "REAL" -> "REAL"
                "LREAL" -> "LREAL"
                "LINT" -> "LI"
                "DATE_AND_TIME", "DT" -> "DT"
                else -> fail("unsupported type '$typeText'")
            }
            return "DB$db,$key$offset"
        }

        internal fun normalizeMember(text: String): String? {
            val segments = text.split('.').map { it.trim().removeSurrounding("\"") }
            if (segments.isEmpty() || segments.any { it.isEmpty() || it.contains('"') }) return null
            return segments.joinToString(".")
        }
    }
}

/** A symbolic tag address split into its block (by name or by number) and member path. */
internal data class S7SymbolRef(val block: String?, val db: Int?, val member: String) {
    companion object {
        private val DOTTED_ABSOLUTE = Regex("^DB\\d+\\.DB[XBWD]\\d+(\\.\\d+)?$", RegexOption.IGNORE_CASE)
        private val BY_NUMBER = Regex("^DB(\\d+)\\.(.+)$", RegexOption.IGNORE_CASE)

        /** Null when [raw] is not symbolic syntax (it is then parsed as an absolute address). */
        fun parse(raw: String): S7SymbolRef? {
            val text = raw.trim()
            if (text.startsWith("\"")) {
                val close = text.indexOf('"', 1)
                if (close <= 1) throw AddressException("invalid symbol '$raw': block name needs closing quote")
                val block = text.substring(1, close)
                val rest = text.substring(close + 1).trim()
                if (!rest.startsWith(".")) throw AddressException("invalid symbol '$raw': expected .member after the block name")
                val member = S7SymbolTable.normalizeMember(rest.substring(1))
                    ?: throw AddressException("invalid symbol '$raw': member is not a dotted name")
                return S7SymbolRef(block, null, member)
            }
            val compact = text.replace(" ", "")
            if (DOTTED_ABSOLUTE.matches(compact)) return null
            val match = BY_NUMBER.matchEntire(text) ?: return null
            val db = match.groupValues[1].toIntOrNull()
                ?: throw AddressException("invalid symbol '$raw': data block number is out of range")
            val member = S7SymbolTable.normalizeMember(match.groupValues[2])
                ?: throw AddressException("invalid symbol '$raw': member is not a dotted name")
            if (!member[0].isLetter() && member[0] != '_') return null
            return S7SymbolRef(null, db, member)
        }
    }
}
