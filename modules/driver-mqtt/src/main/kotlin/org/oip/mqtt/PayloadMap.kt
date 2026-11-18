package org.oip.mqtt

/**
 * Plain MQTT payload mapping.
 *
 * `number:<topic>` parses a JSON number or a decimal token.
 * `string:<topic>` is the raw UTF-8 payload (a JSON string, quotes included, is not unwrapped).
 * `json:<topic>` parses one JSON value. `json:<topic>#/a/b` applies an RFC 6901 pointer.
 * The topic itself cannot contain `#` or `+`.
 *
 * A write publishes the encoded payload. A JSON pointer write merges into the last document
 * received on that topic.
 */
internal enum class PayloadEncoding { NUMBER, STRING, JSON }

internal class PlainAddress(val encoding: PayloadEncoding, val topic: String, val pointer: String?)

internal class SparkplugAddress(val group: String, val edge: String, val device: String, val metric: String) {
    val commandTopic: String
        get() = if (device.isEmpty()) "spBv1.0/$group/NCMD/$edge" else "spBv1.0/$group/DCMD/$edge/$device"

    fun address(): String = "spb:$group/$edge/$device/$metric"
}

internal object Addresses {
    fun parse(text: String): Any? {
        val plain = parsePlain(text)
        if (plain != null) return plain
        return parseSparkplug(text)
    }

    fun parsePlain(text: String): PlainAddress? {
        val encoding = when {
            text.startsWith("number:") -> PayloadEncoding.NUMBER
            text.startsWith("string:") -> PayloadEncoding.STRING
            text.startsWith("json:") -> PayloadEncoding.JSON
            else -> return null
        }
        val rest = text.substring(text.indexOf(':') + 1)
        val hash = rest.indexOf('#')
        val topic = if (hash < 0) rest else rest.substring(0, hash)
        val pointer = if (hash < 0) null else rest.substring(hash + 1)
        if (topic.isEmpty() || topic.contains('#') || topic.contains('+')) {
            throw IllegalArgumentException("mqtt topic is empty or contains a wildcard")
        }
        if (pointer != null && encoding != PayloadEncoding.JSON) {
            throw IllegalArgumentException("only a json: address takes a JSON pointer")
        }
        if (pointer != null && pointer.isNotEmpty() && !pointer.startsWith("/")) {
            throw IllegalArgumentException("json pointer must be empty or start with /")
        }
        return PlainAddress(encoding, topic, pointer)
    }

    fun parseSparkplug(text: String): SparkplugAddress? {
        if (!text.startsWith("spb:")) return null
        val parts = text.removePrefix("spb:").split('/')
        if (parts.size < 4) throw IllegalArgumentException("sparkplug address needs group, edge, device, and metric")
        val group = parts[0]
        val edge = parts[1]
        val device = parts[2]
        val metric = parts.drop(3).joinToString("/")
        if (group.isEmpty() || edge.isEmpty() || metric.isEmpty()) {
            throw IllegalArgumentException("sparkplug address is missing a group, edge, or metric")
        }
        return SparkplugAddress(group, edge, device, metric)
    }

    fun sparkplug(group: String, edge: String, device: String, metric: String) =
        SparkplugAddress(group, edge, device, metric).address()
}

internal object PayloadMap {
    fun decode(address: PlainAddress, payload: ByteArray, lastDocument: Any?): Decoded {
        return try {
            when (address.encoding) {
                PayloadEncoding.NUMBER -> Decoded(parseNumber(payload), null)
                PayloadEncoding.STRING -> Decoded(String(payload, Charsets.UTF_8), null)
                PayloadEncoding.JSON -> {
                    val root = JsonCodec.parse(String(payload, Charsets.UTF_8))
                    val value = if (address.pointer.isNullOrEmpty()) root else JsonPointer.get(root, address.pointer)
                    Decoded(value, root)
                }
            }
        } catch (e: IllegalArgumentException) {
            Decoded(null, lastDocument, e.message ?: "payload")
        }
    }

    fun encode(address: PlainAddress, value: Any?, lastDocument: Any?): ByteArray {
        return when (address.encoding) {
            PayloadEncoding.NUMBER -> canonicalNumber(value).toByteArray(Charsets.UTF_8)
            PayloadEncoding.STRING -> stringify(value).toByteArray(Charsets.UTF_8)
            PayloadEncoding.JSON -> {
                val body = if (address.pointer.isNullOrEmpty()) {
                    value
                } else {
                    val base = lastDocument ?: emptyMap<String, Any?>()
                    JsonPointer.set(base, address.pointer, value)
                }
                JsonCodec.write(body).toByteArray(Charsets.UTF_8)
            }
        }
    }

    private fun parseNumber(payload: ByteArray): Any? {
        val text = String(payload, Charsets.UTF_8).trim()
        if (text.isEmpty()) throw IllegalArgumentException("payload is not a number")
        val parsed = JsonCodec.parse(text)
        if (parsed !is Number) throw IllegalArgumentException("payload is not a number")
        return narrow(parsed)
    }

    private fun canonicalNumber(value: Any?): String = when (value) {
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> if (value.isFinite() && value.toLong().toFloat() == value) value.toLong().toString() else value.toString()
        is Double -> if (value.isFinite() && value.toLong().toDouble() == value) value.toLong().toString() else value.toString()
        is Number -> value.toString()
        else -> throw IllegalArgumentException("value is not a number")
    }

    private fun stringify(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> value.toString()
    }

    private fun narrow(value: Number): Any {
        if (value is Double || value is Float) {
            val double = value.toDouble()
            if (double.isFinite() && double.toLong().toDouble() == double && double in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
                val long = double.toLong()
                if (long in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) return long.toInt()
                return long
            }
            return double
        }
        val long = value.toLong()
        if (long in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) return long.toInt()
        return long
    }
}

internal class Decoded(val value: Any?, val document: Any?, val error: String? = null)

/** RFC 6901 JSON pointer. https://www.rfc-editor.org/rfc/rfc6901 */
internal object JsonPointer {
    fun get(document: Any?, pointer: String): Any? {
        if (pointer.isEmpty()) return document
        var current = document
        for (token in tokens(pointer)) {
            current = when (current) {
                is Map<*, *> -> if (current.containsKey(token)) current[token] else throw IllegalArgumentException("json pointer misses '$token'")
                is List<*> -> {
                    val index = token.toIntOrNull() ?: throw IllegalArgumentException("json pointer index '$token'")
                    if (index !in current.indices) throw IllegalArgumentException("json pointer index $index")
                    current[index]
                }
                else -> throw IllegalArgumentException("json pointer cannot enter $current")
            }
        }
        return current
    }

    fun set(document: Any?, pointer: String, value: Any?): Any? {
        if (pointer.isEmpty()) return value
        val parts = tokens(pointer)
        return write(document, parts, 0, value)
    }

    private fun write(node: Any?, parts: List<String>, at: Int, value: Any?): Any? {
        val token = parts[at]
        val leaf = at == parts.lastIndex
        val index = token.toIntOrNull()
        if (node is List<*> || (node == null && index != null)) {
            val list = (node as? List<*>)?.toMutableList() ?: mutableListOf()
            val i = index ?: throw IllegalArgumentException("json pointer index '$token'")
            while (list.size <= i) list.add(null)
            list[i] = if (leaf) value else write(list[i], parts, at + 1, value)
            return list
        }
        val map = linkedMapOf<String, Any?>()
        if (node is Map<*, *>) node.forEach { (key, item) -> map[key.toString()] = item }
        map[token] = if (leaf) value else write(map[token], parts, at + 1, value)
        return map
    }

    private fun tokens(pointer: String): List<String> {
        if (!pointer.startsWith("/")) throw IllegalArgumentException("json pointer must start with /")
        return pointer.substring(1).split('/').map { unescape(it) }
    }

    private fun unescape(token: String): String {
        val out = StringBuilder(token.length)
        var i = 0
        while (i < token.length) {
            val ch = token[i]
            if (ch == '~') {
                val next = token.getOrNull(i + 1)
                when (next) {
                    '0' -> { out.append('~'); i += 2 }
                    '1' -> { out.append('/'); i += 2 }
                    else -> throw IllegalArgumentException("json pointer escape")
                }
            } else {
                out.append(ch)
                i += 1
            }
        }
        return out.toString()
    }
}

/** JSON values used by plain MQTT payloads: objects, arrays, strings, numbers, booleans, null. */
internal object JsonCodec {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skip()
        if (!parser.end()) throw IllegalArgumentException("trailing json")
        return value
    }

    fun write(value: Any?): String {
        val out = StringBuilder()
        writeInto(out, value)
        return out.toString()
    }

    private fun writeInto(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Float -> out.append(if (value.isFinite()) value.toString() else throw IllegalArgumentException("json number"))
            is Double -> out.append(if (value.isFinite()) value.toString() else throw IllegalArgumentException("json number"))
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                value.forEach { (key, item) ->
                    if (!first) out.append(',')
                    first = false
                    appendString(out, key.toString())
                    out.append(':')
                    writeInto(out, item)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeInto(out, item)
                }
                out.append(']')
            }
            else -> appendString(out, value.toString())
        }
    }

    private fun appendString(out: StringBuilder, text: String) {
        out.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }

    private class Parser(val text: String) {
        var index = 0

        fun end() = index >= text.length

        fun skip() {
            while (index < text.length && text[index].isWhitespace()) index += 1
        }

        fun value(): Any? {
            skip()
            if (end()) throw IllegalArgumentException("truncated json")
            return when (text[index]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skip()
            if (peek('}')) return map
            while (true) {
                skip()
                val key = string()
                skip()
                expect(':')
                map[key] = value()
                skip()
                when {
                    peek('}') -> return map
                    peek(',') -> {}
                    else -> throw IllegalArgumentException("json object")
                }
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skip()
            if (peek(']')) return list
            while (true) {
                list += value()
                skip()
                when {
                    peek(']') -> return list
                    peek(',') -> {}
                    else -> throw IllegalArgumentException("json array")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val ch = text[index]
                index += 1
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("json escape")
                        val esc = text[index]
                        index += 1
                        when (esc) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalArgumentException("json unicode")
                                val hex = text.substring(index, index + 4)
                                out.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("json escape")
                        }
                    }
                    else -> out.append(ch)
                }
            }
            throw IllegalArgumentException("unterminated json string")
        }

        private fun number(): Any {
            val start = index
            if (peek('-')) { /* sign consumed */ }
            if (index >= text.length) throw IllegalArgumentException("json number")
            if (text[index] == '0') index += 1
            else if (text[index].isDigit()) while (index < text.length && text[index].isDigit()) index += 1
            else throw IllegalArgumentException("json number")
            var frac = false
            if (index < text.length && text[index] == '.') {
                frac = true
                index += 1
                val mark = index
                while (index < text.length && text[index].isDigit()) index += 1
                if (index == mark) throw IllegalArgumentException("json number")
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                frac = true
                index += 1
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index += 1
                val mark = index
                while (index < text.length && text[index].isDigit()) index += 1
                if (index == mark) throw IllegalArgumentException("json number")
            }
            val token = text.substring(start, index)
            if (!frac) {
                val long = token.toLongOrNull() ?: throw IllegalArgumentException("json number")
                if (long in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) return long.toInt()
                return long
            }
            return token.toDoubleOrNull() ?: throw IllegalArgumentException("json number")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) throw IllegalArgumentException("json literal")
            index += word.length
            return value
        }

        private fun expect(ch: Char) {
            skip()
            if (index >= text.length || text[index] != ch) throw IllegalArgumentException("expected '$ch'")
            index += 1
        }

        private fun peek(ch: Char): Boolean {
            skip()
            if (index < text.length && text[index] == ch) {
                index += 1
                return true
            }
            return false
        }
    }
}
