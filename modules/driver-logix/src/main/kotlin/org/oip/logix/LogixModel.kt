package org.oip.logix

import java.io.ByteArrayOutputStream

/**
 * Symbol and template cache built with the services in 1756-PM020 chapter 2.
 * Get_Instance_Attribute_List (0x55) on class 0x6B returns instance, name, and symbol type.
 * A program symbol (type 0x1068, name `Program:<name>`) has its own symbol list, addressed by
 * nesting that instance in front of class 0x6B. Template class 0x6C attributes 1, 2, 4 and 5
 * plus Read Template (0x4C) describe a UDT, including the hidden BOOL host the manual shows.
 */
internal class LogixModel(
    val symbols: Map<String, SymbolRec>,
    val templates: Map<Int, TemplateRec>,
    val byHandle: Map<Int, TemplateRec>,
) {
    fun controllerTags(): List<SymbolRec> =
        symbols.values.filter { it.programName == null && !it.program && !it.system && !it.name.startsWith("__") }
            .sortedBy { it.name }

    fun programs(): List<String> =
        symbols.values.filter { it.program }.map { it.name.removePrefix("Program:") }.sorted()

    fun programTags(program: String): List<SymbolRec> =
        symbols.values.filter { it.programName == program && !it.name.startsWith("__") }.sortedBy { it.name }

    fun symbol(fullName: String): SymbolRec? = symbols[fullName]

    fun templateOf(address: ParsedAddress): TemplateRec? {
        val root = tagName(address.steps) ?: return null
        val sym = symbols[root] ?: return null
        if (!sym.structure) return null
        var template = templates[sym.typeId] ?: return null
        val names = memberNames(address.steps)
        for (name in names) {
            val member = template.members.firstOrNull { it.name == name } ?: return null
            if (!member.structure) return null
            template = templates[member.elementType] ?: return null
        }
        return template
    }

    /**
     * Template of the structure [address] names: the root tag, or a member that is itself a
     * structure (a nested AOI or UDT), at any depth. Null for an atomic, a bit, or an array of
     * structures named without an element index.
     */
    fun structureAt(address: ParsedAddress): TemplateRec? {
        if (address.bit != null) return null
        val root = tagName(address.steps) ?: return null
        val sym = symbols[root] ?: return null
        if (!sym.structure) return null
        var template = templates[sym.typeId] ?: return null
        val names = memberNames(address.steps)
        var arrayLength = 0
        for (name in names) {
            val member = template.members.firstOrNull { it.name == name && !it.hidden } ?: return null
            if (!member.structure) return null
            template = templates[member.elementType] ?: return null
            arrayLength = member.info
        }
        if (arrayLength > 0 && address.steps.lastOrNull() !is Step.At) return null
        return template
    }

    /** Atomic type a write of [address] must use, or null when the cache does not know. */
    fun atomicType(address: ParsedAddress): Int? {
        if (address.bit != null && boolArrayIndex(address) != null) return Cip.BOOL
        val root = tagName(address.steps.filter { it !is Step.Bit }) ?: return null
        val sym = symbols[root] ?: return null
        val names = memberNames(address.steps)
        if (names.isEmpty()) {
            if (sym.structure) return null
            if (sym.boolArray) return Cip.BOOL
            return sym.typeId
        }
        if (!sym.structure) return null
        var template = templates[sym.typeId] ?: return null
        for ((n, name) in names.withIndex()) {
            val member = template.members.firstOrNull { it.name == name } ?: return null
            val last = n == names.lastIndex
            if (last) {
                if (member.structure) return null
                return if (member.elementType == Cip.BOOL) Cip.BOOL else member.elementType
            }
            if (!member.structure) return null
            template = templates[member.elementType] ?: return null
        }
        return null
    }

    /**
     * the reference platform addresses a BOOL array as DWORDs: `flags[0].31` is BOOL 31 and `flags[1].0` is BOOL 32.
     */
    fun boolArrayIndex(address: ParsedAddress): Int? {
        val bit = address.bit ?: return null
        val steps = address.steps.dropLast(1)
        val root = tagName(steps) ?: return null
        val sym = symbols[root] ?: return null
        if (!sym.boolArray) return null
        val at = steps.lastOrNull() as? Step.At ?: return bit
        if (at.indexes.size != 1) return null
        return at.indexes[0] * 32 + bit
    }

    companion object {
        val EMPTY = LogixModel(emptyMap(), emptyMap(), emptyMap())

        /**
         * Symbols and templates. A profile without program scope (Micro800) lists global
         * variables only, and one without templates reads no template: a structure member is still
         * reachable by its path, it is just not browsed. doc source: FEATURES DEV-017
         */
        fun load(session: EipSession, profile: LogixProfile = LogixProfile.LOGIX): LogixModel {
            val symbols = LinkedHashMap<String, SymbolRec>()
            val listed = readSymbols(session, null)
            val controller = if (profile.programScope) listed else listed.filter { !it.program }
            val programs = controller.filter { it.program }
            for (sym in controller) if (!sym.program) symbols[sym.name] = sym
            for (prog in programs) {
                symbols[prog.name] = prog
                val program = prog.name.removePrefix("Program:")
                for (child in readSymbols(session, prog.instanceId)) {
                    symbols["${prog.name}.${child.name}"] = child.copy(programName = program, fullName = "${prog.name}.${child.name}")
                }
            }
            val templates = HashMap<Int, TemplateRec>()
            if (!profile.templates) return LogixModel(symbols, emptyMap(), emptyMap())
            for (sym in symbols.values) {
                if (!sym.structure || sym.typeId in templates) continue
                runCatching { readTemplate(session, sym.typeId) }.getOrNull()?.let { templates[it.id] = it }
            }
            val pending = ArrayDeque(templates.values.toList())
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                for (member in current.members) {
                    if (!member.structure || member.elementType in templates) continue
                    val nested = runCatching { readTemplate(session, member.elementType) }.getOrNull() ?: continue
                    templates[nested.id] = nested
                    pending += nested
                }
            }
            val byHandle = HashMap<Int, TemplateRec>()
            for (template in templates.values) byHandle.putIfAbsent(template.handle, template)
            return LogixModel(symbols, templates, byHandle)
        }

        private fun readSymbols(session: EipSession, programInstance: Int?): List<SymbolRec> {
            val out = ArrayList<SymbolRec>()
            var start = 0
            var guard = 0
            while (guard++ < 64) {
                val path = if (programInstance == null) {
                    Cip.classInstance(Cip.SYMBOL, start)
                } else {
                    Cip.classInstance(Cip.SYMBOL, programInstance) + Cip.classInstance(Cip.SYMBOL, start)
                }
                // Attributes 1 (name), 2 (type), and 8 (three UDINT dimensions). A BOOL[n] reports n in the first dimension so browse can list flags[word].bit.
                val data = byteArrayOf(3, 0, 1, 0, 2, 0, 8, 0)
                val reply = session.request(Cip.message(Cip.GET_INSTANCES, path, data))
                if (reply.status != 0 && reply.status != 0x06) break
                var last = start
                var p = 0
                val body = reply.data
                while (p + 6 <= body.size) {
                    val instance = i32(body, p)
                    val nameLen = u16(body, p + 4)
                    val tail = 6 + nameLen + 2 + 12
                    if (p + tail > body.size) break
                    val name = body.copyOfRange(p + 6, p + 6 + nameLen).toString(Charsets.US_ASCII)
                    val typeAt = p + 6 + nameLen
                    val type = u16(body, typeAt)
                    val dimAt = typeAt + 2
                    out += SymbolRec(name, name, type, instance, null, i32(body, dimAt), i32(body, dimAt + 4), i32(body, dimAt + 8))
                    last = instance
                    p += tail
                }
                if (reply.status == 0) break
                val next = last + 1
                if (next <= start) break
                start = next
            }
            return out
        }

        private fun readTemplate(session: EipSession, instance: Int): TemplateRec {
            val ask = Le()
            ask.u16(4)
            ask.u16(1); ask.u16(2); ask.u16(4); ask.u16(5)
            val header = session.request(Cip.message(Cip.GET_LIST, Cip.classInstance(Cip.TEMPLATE, instance), ask.toByteArray()))
            if (header.status != 0) throw IllegalStateException("template $instance status ${header.status}")
            var handle = 0
            var members = 0
            var words = 0
            var size = 0
            val body = header.data
            if (body.size < 2) throw IllegalStateException("template header")
            val count = u16(body, 0)
            var p = 2
            repeat(count) {
                if (p + 4 > body.size) return@repeat
                val attr = u16(body, p)
                val status = u16(body, p + 2)
                p += 4
                if (status != 0) return@repeat
                when (attr) {
                    1 -> { handle = u16(body, p); p += 2 }
                    2 -> { members = u16(body, p); p += 2 }
                    4 -> { words = i32(body, p); p += 4 }
                    5 -> { size = i32(body, p); p += 4 }
                }
            }
            val want = (words * 4 - 23).coerceAtLeast(0)
            val def = ByteArrayOutputStream()
            var offset = 0
            var guard = 0
            while (def.size() < want && guard++ < 32) {
                val data = ByteArray(6)
                put32(data, 0, offset.toLong())
                put16(data, 4, (want - def.size()).coerceAtMost(400))
                val reply = session.request(Cip.message(Cip.READ, Cip.classInstance(Cip.TEMPLATE, instance), data))
                if (reply.data.isEmpty()) break
                def.write(reply.data)
                offset += reply.data.size
                if (reply.status == 0) break
                if (reply.status != 0x06) break
            }
            val raw = def.toByteArray()
            val recs = ArrayList<MemberRec>()
            var q = 0
            repeat(members) {
                if (q + 8 > raw.size) return@repeat
                recs += MemberRec(u16(raw, q), u16(raw, q + 2), i32(raw, q + 4), "")
                q += 8
            }
            val templateName = cString(raw, q).also { q += it.length + 1 }
            for (i in recs.indices) {
                if (q >= raw.size) break
                val memberName = cString(raw, q)
                q += memberName.length + 1
                recs[i] = recs[i].copy(name = memberName)
            }
            val short = templateName.substringBefore(';')
            return TemplateRec(instance, short, handle, size, recs)
        }

        private fun cString(bytes: ByteArray, at: Int): String {
            var end = at
            while (end < bytes.size && bytes[end].toInt() != 0) end++
            if (at >= bytes.size) return ""
            return bytes.copyOfRange(at, end).toString(Charsets.US_ASCII)
        }

        private fun tagName(steps: List<Step>): String? {
            val names = steps.filterIsInstance<Step.Sym>().map { it.name }
            if (names.isEmpty()) return null
            return if (names[0].startsWith("Program:")) {
                if (names.size == 1) names[0] else "${names[0]}.${names[1]}"
            } else {
                names[0]
            }
        }

        private fun memberNames(steps: List<Step>): List<String> {
            val names = steps.filterIsInstance<Step.Sym>().map { it.name }
            if (names.isEmpty()) return emptyList()
            return if (names[0].startsWith("Program:")) names.drop(2) else names.drop(1)
        }
    }
}

internal data class SymbolRec(
    val name: String,
    val fullName: String,
    val symbolType: Int,
    val instanceId: Int,
    val programName: String?,
    val dim0: Int = 0,
    val dim1: Int = 0,
    val dim2: Int = 0,
) {
    val typeId: Int get() = symbolType and 0x0FFF
    val structure: Boolean get() = symbolType and 0x8000 != 0
    val dimensions: Int get() = (symbolType ushr 13) and 0x3
    val system: Boolean get() = symbolType and 0x1000 != 0
    val program: Boolean get() = typeId == 0x068 || name.startsWith("Program:")
    val boolArray: Boolean get() = !structure && typeId == Cip.BOOL && dimensions > 0

    fun copy(programName: String?, fullName: String) =
        SymbolRec(name, fullName, symbolType, instanceId, programName, dim0, dim1, dim2)
}

internal data class MemberRec(val info: Int, val type: Int, val offset: Int, val name: String) {
    val hidden: Boolean get() = name.startsWith("ZZZZZZZZZZ")
    val structure: Boolean get() = type and 0x8000 != 0
    val elementType: Int get() = type and 0x0FFF
    val arrayLength: Int get() = if (structure) info else info
    fun copy(name: String) = MemberRec(info, type, offset, name)
}

internal data class TemplateRec(
    val id: Int,
    val name: String,
    val handle: Int,
    val size: Int,
    val members: List<MemberRec>,
) {
    val string: Boolean get() = name == "STRING"
    val dataCapacity: Int get() = members.firstOrNull { it.name == "DATA" }?.info ?: 82
}
