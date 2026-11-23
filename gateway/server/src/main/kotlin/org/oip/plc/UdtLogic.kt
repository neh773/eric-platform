package org.oip.plc

import org.oip.tags.DataType
import org.oip.tags.UdtMemberShape

/** What the PLC side needs to know about gateway UDTs. The gateway backs it with the default tag provider. */
interface UdtCatalog {
    /** Every UDT type path. */
    fun types(): List<String>
    /** Flattened members of [typeId], or null when it is missing. */
    fun members(typeId: String): List<UdtMemberShape>?
    /** The UDT type of the instance at the full tag path [path] (`[default]Folder/Name`), or null. */
    fun instanceType(path: String): String?
    /** Top-level instances: full tag path to type path. */
    fun instances(): List<Pair<String, String>>

    companion object {
        val EMPTY = object : UdtCatalog {
            override fun types() = emptyList<String>()
            override fun members(typeId: String): List<UdtMemberShape>? = null
            override fun instanceType(path: String): String? = null
            override fun instances() = emptyList<Pair<String, String>>()
        }
    }
}

/** One UDT instance mapped onto a program structure variable. [members] overrides a member's direction. */
data class UdtBinding(val variable: String, val instance: String, val members: Map<String, String> = emptyMap())

data class BoundMember(
    /** ST field path below the variable, e.g. `[Level]`. */
    val fields: List<String>,
    /** UDT member path below the instance, e.g. `WetWellLevel`. */
    val member: String,
    val type: String,
    /** `in`, `out` or `none`. */
    val direction: String,
    /** What the program's writes suggest: `out` when the logic writes the member, else `in`. */
    val auto: String,
    val tag: String,
    val address: String? = null,
    val note: String? = null,
) {
    fun stPath(variable: String) = (listOf(variable) + fields).joinToString(".")
}

data class BindingPlan(
    val variable: String,
    val instance: String,
    val structType: String?,
    val udtType: String?,
    val members: List<BoundMember>,
    val problems: List<String>,
)

data class PreparedProgram(
    /** Source handed to the compiler: same lines as the user's, plus glue on existing lines and types at the end. */
    val source: String,
    val plans: List<BindingPlan>,
    /** Generated mapping rows (inputs and outputs) for bound members. */
    val inputs: List<Map<String, Any?>>,
    val outputs: List<Map<String, Any?>>,
    /** Gateway UDT types added to the source: ST name to UDT path. */
    val addedTypes: Map<String, String>,
)

/** A program-level structure variable the editor can bind, with the type it was declared with. */
data class StructVariable(val variable: String, val type: String, val udtType: String?, val fromSource: Boolean)

object UdtLogic {
    /** Types the scan engine can move between a tag and the program. */
    val ENGINE_TYPES = setOf("BOOL", "INT", "DINT", "REAL", "LREAL")

    private val RESERVED = setOf(
        "TON", "TOF", "TP", "CTU", "CTD", "CTUD", "R_TRIG", "F_TRIG", "SR", "RS", "BOOL", "INT", "DINT", "LINT", "REAL",
        "LREAL", "STRING", "WORD", "DWORD", "BYTE", "TIME", "DATE", "PROGRAM", "TYPE", "STRUCT", "VAR", "END",
    )

    fun stTypeOf(dataType: DataType): String? = when (dataType) {
        DataType.BOOLEAN -> "BOOL"
        DataType.INT32 -> "DINT"
        DataType.INT64 -> "LINT"
        DataType.FLOAT -> "REAL"
        DataType.DOUBLE -> "LREAL"
        DataType.STRING -> "STRING"
        else -> null
    }

    fun identifier(raw: String): String {
        var s = raw.replace(Regex("[^A-Za-z0-9_]"), "_").replace(Regex("_+"), "_").trim('_')
        if (s.isEmpty()) s = "X"
        if (s.first().isDigit()) s = "M_$s"
        return s
    }

    /** ST structure names for every gateway UDT: the last path segment, or the whole path when that collides. */
    fun typeNames(catalog: UdtCatalog): Map<String, String> {
        val types = catalog.types()
        val byLeaf = types.groupBy { identifier(it.substringAfterLast('/')).lowercase() }
        val out = LinkedHashMap<String, String>()
        for (path in types) {
            val leaf = identifier(path.substringAfterLast('/'))
            var name = if (byLeaf[leaf.lowercase()]!!.size == 1) leaf else identifier(path)
            if (name.uppercase() in RESERVED || name.uppercase().startsWith("ERIC_")) name += "_UDT"
            out[path] = name
        }
        return out
    }

    /** `TYPE ... END_TYPE` for [paths] and the nested UDTs they use, nested types first. */
    fun declarations(catalog: UdtCatalog, names: Map<String, String>, paths: Collection<String>): String {
        val ordered = LinkedHashSet<String>()
        fun visit(path: String, stack: Set<String>) {
            val key = names.keys.firstOrNull { it.equals(path, ignoreCase = true) } ?: return
            if (key in ordered || key.lowercase() in stack) return
            catalog.members(key)?.forEach { m -> m.nestedType?.let { visit(it, stack + key.lowercase()) } }
            ordered += key
        }
        paths.forEach { visit(it, emptySet()) }
        val sb = StringBuilder()
        for (path in ordered) {
            val fields = catalog.members(path).orEmpty().mapNotNull { m ->
                val type = m.nestedType?.let { nested -> names.entries.firstOrNull { it.key.equals(nested, true) }?.value } ?: stTypeOf(m.dataType)
                type?.let { "${identifier(m.name)} : $it;" }
            }.distinctBy { it.substringBefore(' ').lowercase() }
            if (fields.isEmpty()) continue
            sb.append("TYPE ").append(names[path]).append(" : STRUCT ").append(fields.joinToString(" ")).append(" END_STRUCT; END_TYPE\n")
        }
        return sb.toString()
    }

    /** Structure variables declared in the program's own VAR sections (the ones a UDT instance can be bound to). */
    fun structVariables(source: String, program: String?, catalog: UdtCatalog): List<StructVariable> {
        val parsed = StSource.parse(source)
        val pou = programOf(parsed, program) ?: return emptyList()
        val names = typeNames(catalog)
        return pou.vars.filter { it.section == "VAR" && it.address == null }.mapNotNull { v ->
            val src = parsed.struct(v.type)
            val udt = names.entries.firstOrNull { it.value.equals(v.type, ignoreCase = true) }?.key
            when {
                src != null -> StructVariable(v.name, src.name, udt, fromSource = true)
                udt != null -> StructVariable(v.name, names[udt]!!, udt, fromSource = false)
                else -> null
            }
        }
    }

    fun programOf(parsed: StSource.Parsed, program: String?): StSource.Pou? =
        (program?.let { parsed.pou(it)?.takeIf { p -> p.kind == "PROGRAM" } }) ?: parsed.programs.firstOrNull()

    private data class Field(val name: String, val type: String)

    /**
     * Build the compiler source and the generated mapping rows. [usedAddresses] are the hand-mapped rows' addresses,
     * so generated addresses never collide with them.
     */
    fun prepare(
        source: String,
        program: String?,
        bindings: List<UdtBinding>,
        usedAddresses: Collection<String>,
        catalog: UdtCatalog,
    ): PreparedProgram {
        val parsed = StSource.parse(source)
        val names = typeNames(catalog)
        val declaredHere = (parsed.structs.map { it.name } + parsed.pous.map { it.name }).map { it.lowercase() }.toSet()
        val words = Regex("""[A-Za-z_]\w*""").findAll(parsed.masked).map { it.value.lowercase() }.toSet()
        val wanted = names.filter { (_, st) -> st.lowercase() in words && st.lowercase() !in declaredHere }
        val pou = programOf(parsed, program)
        var next = (usedAddresses.mapNotNull { major(it) }.maxOrNull() ?: 0).let { maxOf(1000, it + 1) }
        val plans = ArrayList<BindingPlan>()
        val inputs = ArrayList<Map<String, Any?>>()
        val outputs = ArrayList<Map<String, Any?>>()
        val decls = StringBuilder()
        val copyIn = StringBuilder()
        val copyOut = StringBuilder()
        val usedIdents = HashSet<String>()
        for (binding in bindings) {
            val problems = ArrayList<String>()
            val decl = pou?.variable(binding.variable)
            if (pou == null || decl == null) {
                plans += BindingPlan(binding.variable, binding.instance, null, null, emptyList(), listOf("${binding.variable} is not declared in the program"))
                continue
            }
            val fieldsOf = { typeName: String -> fieldsOf(parsed, names, catalog, typeName) }
            val rootFields = fieldsOf(decl.type)
            if (rootFields == null) {
                plans += BindingPlan(binding.variable, binding.instance, decl.type, null, emptyList(), listOf("${binding.variable} is a ${decl.type}, not a structure or gateway UDT"))
                continue
            }
            val instanceType = catalog.instanceType(binding.instance)
            if (instanceType == null) problems += "${binding.instance} is not a UDT instance"
            val writes = StSource.writes(parsed, pou, decl.name)
            val members = ArrayList<BoundMember>()
            fun walk(fields: List<Field>, udtType: String?, stPrefix: List<String>, memberPrefix: List<String>) {
                val udtMembers = udtType?.let { catalog.members(it) }.orEmpty()
                for (f in fields) {
                    val m = udtMembers.firstOrNull { identifier(it.name).equals(f.name, ignoreCase = true) }
                    val stPath = stPrefix + f.name
                    if (m == null) {
                        members += BoundMember(stPath, (memberPrefix + f.name).joinToString("/"), f.type, "none", "none", "", note = "the UDT has no member named ${f.name}")
                        continue
                    }
                    val memberPath = memberPrefix + m.name
                    val nested = fieldsOf(f.type)
                    if (nested != null) {
                        walk(nested, m.nestedType, stPath, memberPath)
                        continue
                    }
                    val type = f.type.uppercase()
                    val lower = stPath.joinToString("/") { it.lowercase() }
                    val written = "*" in writes || writes.any { lower == it || lower.startsWith("$it/") }
                    val auto = if (written) "out" else "in"
                    val tag = binding.instance.trimEnd('/') + "/" + memberPath.joinToString("/")
                    if (type !in ENGINE_TYPES) {
                        members += BoundMember(stPath, memberPath.joinToString("/"), type, "none", auto, tag, note = "$type members are not moved by the scan engine")
                        continue
                    }
                    val override = binding.members.entries.firstOrNull { it.key.equals(memberPath.joinToString("/"), ignoreCase = true) }?.value
                    val direction = override?.takeIf { it in setOf("in", "out", "none") } ?: auto
                    members += BoundMember(stPath, memberPath.joinToString("/"), type, direction, auto, tag)
                }
            }
            walk(rootFields, instanceType, emptyList(), emptyList())
            val placed = members.map { member ->
                if (member.direction == "none" || instanceType == null) return@map member
                val n = next++
                val address = address(member.direction, member.type, n)
                var ident = identifier("ERICUDT_${binding.variable}_${member.fields.joinToString("_")}")
                while (!usedIdents.add(ident.lowercase())) ident += "_"
                decls.append(ident).append(" AT ").append(address).append(" : ").append(member.type).append("; ")
                val stPath = member.stPath(decl.name)
                val row = linkedMapOf<String, Any?>(
                    "name" to stPath, "address" to address, "tag" to member.tag, "type" to member.type,
                    "safe" to if (member.type == "BOOL") false else 0, "udt" to binding.variable, "member" to member.member,
                )
                if (member.direction == "in") {
                    copyIn.append(stPath).append(" := ").append(ident).append("; ")
                    inputs += row
                } else {
                    copyOut.append(ident).append(" := ").append(stPath).append("; ")
                    outputs += row
                }
                member.copy(address = address)
            }
            val udtForType = names.entries.firstOrNull { it.value.equals(decl.type, true) }?.key
            plans += BindingPlan(binding.variable, binding.instance, decl.type, instanceType ?: udtForType, placed, problems)
        }
        val out = StringBuilder(source)
        if (pou != null && (copyOut.isNotEmpty() || decls.isNotEmpty())) {
            // Back to front, so earlier offsets stay valid. Nothing inserted contains a newline.
            out.insert(pou.endKeyword, copyOut.toString())
            val at = pou.bodyStart
            out.insert(at, " VAR $decls END_VAR $copyIn")
        }
        if (wanted.isNotEmpty()) {
            if (!out.endsWith("\n")) out.append('\n')
            out.append(declarations(catalog, names, wanted.keys))
        }
        return PreparedProgram(out.toString(), plans, inputs, outputs, wanted.entries.associate { it.value to it.key })
    }

    private fun fieldsOf(parsed: StSource.Parsed, names: Map<String, String>, catalog: UdtCatalog, typeName: String): List<Field>? {
        parsed.struct(typeName)?.let { s -> return s.fields.map { Field(it.name, it.type) } }
        val udt = names.entries.firstOrNull { it.value.equals(typeName, ignoreCase = true) }?.key ?: return null
        return catalog.members(udt).orEmpty().mapNotNull { m ->
            val type = m.nestedType?.let { nested -> names.entries.firstOrNull { it.key.equals(nested, true) }?.value } ?: stTypeOf(m.dataType)
            type?.let { Field(identifier(m.name), it) }
        }
    }

    /** The first number of a `%` address, or null. */
    fun major(address: String): Int? = Regex("""^%[IQMG][XBWDL]?(\d+)""", RegexOption.IGNORE_CASE).find(address.trim())?.groupValues?.get(1)?.toIntOrNull()

    private fun address(direction: String, type: String, n: Int): String {
        val area = if (direction == "in") "I" else "Q"
        return when (type) {
            "BOOL" -> "%${area}X$n.0"
            "INT" -> "%${area}W$n"
            "LREAL" -> "%${area}L$n"
            else -> "%${area}D$n"
        }
    }
}
