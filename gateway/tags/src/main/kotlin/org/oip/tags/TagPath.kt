package org.oip.tags

import org.oip.config.Canon

/**
 * Tag path syntax from SPEC §5 and the public path rules:
 *
 * `[provider]folder/sub/tag.property`, case-preserving and matched case-insensitively.
 * Relative forms inside UDTs and expressions: `.`, `..`, `[.]`, `[~]`, and `{param}` (resolved by the caller).
 * Array elements: `tag[3]`.
 */
enum class PathRelative { FOLDER, ROOT }

data class TagPath(
    val provider: String?,
    val segments: List<String>,
    val indexes: List<Int> = emptyList(),
    val property: String? = null,
    val relative: PathRelative? = null,
) {
    val folder: List<String> get() = segments.dropLast(1)

    fun canonicalKey(): String {
        val prov = (provider ?: "").lowercase()
        val body = segments.joinToString("\u0000") { it.lowercase() }
        return "$prov\u0000$body"
    }

    /** Subscription/read key including element indexes and property. */
    fun watchKey(): String {
        val idx = indexes.joinToString(",")
        val prop = property?.lowercase() ?: ""
        return canonicalKey() + "\u0001" + idx + "\u0001" + prop
    }

    fun child(name: String): TagPath = copy(segments = segments + name, indexes = emptyList(), property = null, relative = null)

    fun resolve(base: TagPath?): TagPath {
        val folder = base?.segments?.dropLast(1) ?: emptyList()
        val providerName = provider ?: base?.provider
        val raw = when (relative) {
            PathRelative.FOLDER -> folder + segments
            PathRelative.ROOT -> segments
            null -> when (segments.firstOrNull()) {
                "." -> folder + segments.drop(1)
                ".." -> folder + segments
                else -> segments
            }
        }
        val normalized = ArrayDeque<String>()
        for (seg in raw) {
            when (seg) {
                "." -> {}
                ".." -> if (normalized.isNotEmpty()) normalized.removeLast() else
                    throw PathException("path escapes the provider root: $this")
                else -> normalized.addLast(seg)
            }
        }
        if (normalized.isEmpty() && property == null && indexes.isEmpty()) {
            return TagPath(providerName, emptyList())
        }
        return TagPath(providerName, normalized.toList(), indexes, property, null)
    }

    override fun toString(): String {
        val head = when (relative) {
            PathRelative.FOLDER -> "[.]"
            PathRelative.ROOT -> "[~]"
            null -> if (provider != null) "[$provider]" else ""
        }
        val body = segments.joinToString("/")
        val idx = indexes.joinToString("") { "[$it]" }
        val prop = if (property != null) ".$property" else ""
        return head + body + idx + prop
    }

    companion object {
        fun parse(text: String, defaultProvider: String? = null): TagPath {
            val s = text.trim()
            if (s.isEmpty()) return TagPath(defaultProvider, emptyList())
            var i = 0
            var provider: String? = defaultProvider
            var relative: PathRelative? = null
            if (s[0] == '[') {
                val end = s.indexOf(']')
                if (end < 0) throw PathException("unclosed provider in '$text'")
                when (val inside = s.substring(1, end).trim()) {
                    "." -> relative = PathRelative.FOLDER
                    "~" -> relative = PathRelative.ROOT
                    "" -> provider = defaultProvider
                    else -> {
                        if (!inside.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' })
                            throw PathException("invalid provider '$inside'")
                        provider = Canon.share(inside)
                    }
                }
                i = end + 1
                if (i < s.length && s[i] == '/') i++
            }
            val rest = s.substring(i)
            if (rest.isEmpty()) return TagPath(provider, emptyList(), relative = relative)
            val parts = rest.split('/')
            if (parts.any { it.isEmpty() }) throw PathException("empty path segment in '$text'")
            var indexes = emptyList<Int>()
            var property: String? = null
            val segments = ArrayList<String>(parts.size)
            parts.forEachIndexed { idx, part ->
                val parsed = parseSegment(part, allowIndexAndProperty = idx == parts.lastIndex)
                segments += parsed.name
                if (idx == parts.lastIndex) {
                    indexes = parsed.indexes
                    property = parsed.property
                }
            }
            return TagPath(provider, segments, indexes, property, relative)
        }

        private data class Seg(val name: String, val indexes: List<Int>, val property: String?)

        private fun parseSegment(part: String, allowIndexAndProperty: Boolean): Seg {
            if (part == "." || part == "..") return Seg(part, emptyList(), null)
            var nameEnd = part.length
            var property: String? = null
            val dot = part.lastIndexOf('.')
            if (dot >= 0) {
                val prop = part.substring(dot + 1)
                val before = if (dot > 0) part[dot - 1] else 0.toChar()
                if (prop.isNotEmpty() && prop.all { it.isLetterOrDigit() || it == '_' } && prop[0].isLetter() && before != '.') {
                    if (!allowIndexAndProperty) throw PathException("property is only valid on the last segment: '$part'")
                    property = prop
                    nameEnd = dot
                }
            }
            val head = part.substring(0, nameEnd)
            val bracket = head.indexOf('[')
            val rawName = if (bracket < 0) head else head.substring(0, bracket)
            if (rawName.isEmpty() || rawName == "." || rawName == "..") throw PathException("invalid segment '$part'")
            if (!rawName.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' })
                throw PathException("invalid segment '$part'")
            val name = Canon.share(rawName)
            if (bracket < 0) return Seg(name, emptyList(), property)
            val indexes = ArrayList<Int>()
            if (bracket >= 0) {
                if (!allowIndexAndProperty) throw PathException("array index is only valid on the last segment: '$part'")
                var j = bracket
                val h = head
                while (j < h.length) {
                    if (h[j] != '[') throw PathException("invalid array index in '$part'")
                    val close = h.indexOf(']', j + 1)
                    if (close < 0) throw PathException("unclosed array index in '$part'")
                    val num = h.substring(j + 1, close)
                    val value = num.toIntOrNull() ?: throw PathException("invalid array index '$num'")
                    if (value < 0) throw PathException("negative array index in '$part'")
                    indexes += value
                    j = close + 1
                }
            }
            return Seg(name, indexes, property)
        }
    }
}

class PathException(message: String) : IllegalArgumentException(message)
