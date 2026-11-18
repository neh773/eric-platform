package org.oip.config

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ResourceFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal object Names {
    val NAME = Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$""")      // DNS-label style, 1..63 chars
    val KIND = Regex("""^[A-Z][A-Za-z0-9]{0,62}$""")                     // PascalCase
    val MODULE = Regex("""^[a-z][a-z0-9]*([.-][a-z0-9]+)*$""")           // same as manifest id (ADR 0006)
}

/** Resource scope: `gateway`, `project/<name>`, or `mode/<deploymentMode>` (overlay). SPEC §4. */
data class Scope private constructor(val type: String, val qualifier: String?) {
    val path: String get() = if (qualifier == null) type else "$type/$qualifier"
    val isOverlay: Boolean get() = type == "mode"
    override fun toString() = path
    companion object {
        val GATEWAY = Scope("gateway", null)
        fun project(name: String) = Scope("project", checkName(name, "project"))
        fun mode(name: String) = Scope("mode", checkName(name, "mode"))
        fun parse(s: String): Scope {
            val p = s.split('/')
            return when {
                p.size == 1 && p[0] == "gateway" -> GATEWAY
                p.size == 2 && p[0] == "project" -> project(p[1])
                p.size == 2 && p[0] == "mode" -> mode(p[1])
                else -> throw IllegalArgumentException("invalid scope '$s'")
            }
        }
        private fun checkName(n: String, what: String) = n.also { require(Names.NAME.matches(it)) { "invalid $what name '$n'" } }
    }
}

/** Identity of a resource: module / scope / kind / name. Path: `<moduleId>/<scope>/<kind>/<name>.yaml` (ADR 0005). */
data class ResourceId(val module: String, val scope: Scope, val kind: String, val name: String) {
    init {
        require(Names.MODULE.matches(module)) { "invalid module id '$module'" }
        require(Names.KIND.matches(kind)) { "invalid kind '$kind' (PascalCase, alphanumeric)" }
        require(Names.NAME.matches(name)) { "invalid resource name '$name' (lowercase alphanumeric and '-', 1-63 chars)" }
    }
    val relativePath: String get() = "$module/${scope.path}/$kind/$name.yaml"
    override fun toString() = "$module/${scope.path}/$kind/$name"
    companion object {
        fun fromRelativePath(p: String): ResourceId {
            val s = p.replace('\\', '/').split('/')
            require(s.size in 4..5 && s.last().endsWith(".yaml")) { "not a resource path: $p" }
            return ResourceId(s[0], Scope.parse(s.subList(1, s.size - 2).joinToString("/")), s[s.size - 2], s.last().removeSuffix(".yaml"))
        }
    }
}

data class Metadata(
    val name: String,
    val module: String,
    val labels: Map<String, String> = emptyMap(),
    /** Optimistic-concurrency version; 0 = not yet stored. */
    val version: Long = 0,
    val modified: Instant? = null,
    val modifiedBy: String? = null,
)

/** Config resource envelope `{apiVersion, kind, metadata, spec}` (SPEC §4). Scope comes from the file location, not the document. */
data class Resource(
    val kind: String,
    val metadata: Metadata,
    val spec: Map<String, Any?> = emptyMap(),
    val apiVersion: String = API_VERSION,
) {
    fun id(scope: Scope = Scope.GATEWAY) = ResourceId(metadata.module, scope, kind, metadata.name)
    companion object { const val API_VERSION = "oip/v1" }
}

enum class Format { YAML, JSON }

/** One YAML document from a multi-document shard. [resource] is null when [error] is set. */
data class DocumentDecode(val resource: Resource?, val body: String, val error: String?)

/**
 * One shared instance for a short token that shows up on every tag: provider, type name, device,
 * folder segment, user, module, kind. Paths containing `/`, long text, and tokens with a run of
 * digits (tag names, item addresses) are left alone. A pool entry for each of those costs a map
 * node and does not save a copy when the caller already shares one reference.
 */
object Canon {
    private val pool = ConcurrentHashMap<String, String>(256)

    fun share(text: String): String {
        if (text.length > 48 || text.indexOf('/') >= 0 || digitRun(text)) return text
        return pool.computeIfAbsent(text) { text }
    }

    private fun digitRun(text: String): Boolean {
        var run = 0
        for (i in text.indices) {
            val c = text[i]
            if (c in '0'..'9') {
                if (++run == 3) return true
            } else {
                run = 0
            }
        }
        return false
    }
}

object ResourceCodec {
    private val TOP = setOf("apiVersion", "kind", "metadata", "spec")
    private val META = setOf("name", "module", "labels", "version", "modified", "modifiedBy")
    private val json = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val yaml = ObjectMapper(YAMLFactory.builder()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
        .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
        .stringQuotingChecker(StableYamlQuoting())
        .build())
    private fun mapper(f: Format) = if (f == Format.JSON) json else yaml

    fun toMap(r: Resource): Map<String, Any?> = linkedMapOf(
        "apiVersion" to r.apiVersion, "kind" to r.kind,
        "metadata" to linkedMapOf<String, Any?>("name" to r.metadata.name, "module" to r.metadata.module).apply {
            if (r.metadata.labels.isNotEmpty()) put("labels", r.metadata.labels)
            put("version", r.metadata.version)
            r.metadata.modified?.let { put("modified", it.toString()) }
            r.metadata.modifiedBy?.let { put("modifiedBy", it) }
        },
        "spec" to r.spec)

    /** Parse a JSON or YAML value (YAML is a superset of JSON) into canonical Kotlin maps/lists. */
    fun parseValue(text: String): Any? = normalize(yaml.readValue(text, Any::class.java))

    fun encode(r: Resource, f: Format = Format.YAML): String =
        if (f == Format.JSON) json.writeValueAsString(toMap(r)) else encodeYaml(r)

    /** YAML bytes for a bulk write. Same document [encode] returns for [Format.YAML]. */
    fun encodeYamlBytes(r: Resource): ByteArray = encodeYaml(r).toByteArray(Charsets.UTF_8)

    fun decode(text: String, f: Format = Format.YAML): Resource {
        val root = try { mapper(f).readValue(text, Any::class.java) } catch (e: JacksonException) {
            throw ResourceFormatException("malformed ${f.name}: ${e.originalMessage}", e) }
        return fromMap(root)
    }

    /**
     * Every YAML document in [text]. A line that is exactly `---` starts a new document.
     * One bad document does not stop the others from being decoded; its error is returned beside a null resource.
     */
    fun decodeDocuments(text: String): List<DocumentDecode> {
        if (text.isBlank()) return emptyList()
        val parts = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty() && current.any { !it.isWhitespace() }) parts += current.toString()
            current.clear()
        }
        for (line in text.split('\n')) {
            if (line == "---") flush() else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        flush()
        val out = ArrayList<DocumentDecode>(parts.size)
        for (part in parts) {
            val body = stripLeadingComments(part)
            if (body.isBlank()) continue
            try {
                out += DocumentDecode(decode(body, Format.YAML), body, null)
            } catch (e: RuntimeException) {
                out += DocumentDecode(null, body, e.message ?: e.toString())
            }
        }
        return out
    }

    /** Drop the shard header (and any other leading comments) so a document body stands alone. */
    private fun stripLeadingComments(part: String): String {
        val lines = part.split('\n')
        var start = 0
        while (start < lines.size && (lines[start].isBlank() || lines[start].trimStart().startsWith("#"))) start++
        if (start >= lines.size) return ""
        val sb = StringBuilder()
        for (i in start until lines.size) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(lines[i])
        }
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun fromMap(root: Any?): Resource {
        if (root !is Map<*, *>) throw ResourceFormatException("resource must be an object")
        val unknown = root.keys.map { it.toString() } - TOP
        if (unknown.isNotEmpty()) throw ResourceFormatException("unknown top-level keys: $unknown")
        val api = root["apiVersion"] as? String ?: throw ResourceFormatException("missing apiVersion")
        if (api != Resource.API_VERSION) throw ResourceFormatException("unsupported apiVersion '$api' (expected ${Resource.API_VERSION})")
        val kind = (root["kind"] as? String)?.let { Canon.share(it) } ?: throw ResourceFormatException("missing kind")
        val md = root["metadata"] as? Map<*, *> ?: throw ResourceFormatException("missing metadata")
        val unknownMd = md.keys.map { it.toString() } - META
        if (unknownMd.isNotEmpty()) throw ResourceFormatException("unknown metadata keys: $unknownMd")
        val name = (md["name"] as? String)?.let { Canon.share(it) } ?: throw ResourceFormatException("missing metadata.name")
        val module = (md["module"] as? String)?.let { Canon.share(it) } ?: throw ResourceFormatException("missing metadata.module")
        val labels = (md["labels"] ?: emptyMap<String, String>()) as? Map<*, *> ?: throw ResourceFormatException("metadata.labels must be an object")
        val version = when (val v = md["version"]) { null -> 0L; is Number -> v.toLong(); else -> throw ResourceFormatException("metadata.version must be an integer") }
        val modified = (md["modified"] as? String)?.let { runCatching { Instant.parse(it) }.getOrElse { throw ResourceFormatException("metadata.modified must be an ISO-8601 instant") } }
        val spec = when (val s = root["spec"]) { null -> emptyMap(); is Map<*, *> -> normalize(s) as Map<String, Any?>; else -> throw ResourceFormatException("spec must be an object") }
        try { ResourceId(module, Scope.GATEWAY, kind, name) } catch (e: IllegalArgumentException) { throw ResourceFormatException(e.message!!, e) }
        val labelMap = if (labels.isEmpty()) emptyMap() else labels.entries.associate {
            Canon.share(it.key.toString()) to Canon.share(it.value.toString())
        }
        val actor = (md["modifiedBy"] as? String)?.let { Canon.share(it) }
        return Resource(kind, Metadata(name, module, labelMap, version, modified, actor), spec, Canon.share(api))
    }

    /** Canonical value form so YAML and JSON decode identically (ints as Int when they fit, else Long). */
    internal fun normalize(v: Any?): Any? = when (v) {
        is Map<*, *> -> SmallMaps.of(v.entries.map { it.key.toString() to it.value })
        is List<*> -> v.map { normalize(it) }
        else -> normalizeScalar(v)
    }

    internal fun normalizeScalar(v: Any?): Any? = when (v) {
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
        is java.math.BigInteger -> if (v.bitLength() < 32) v.toInt() else v.toLong()
        is Float -> v.toDouble()
        is Map<*, *> -> normalize(v)
        is List<*> -> normalize(v)
        else -> v
    }

    /**
     * Block YAML for the resource envelope. Jackson's mapper spends most of a bulk import reflecting
     * over the same six fields. Decode still goes through Jackson, and [encode] of YAML and JSON
     * must produce equal resources.
     */
    private fun encodeYaml(r: Resource): String {
        val sb = StringBuilder(192)
        sb.append("apiVersion: ").append(yamlScalar(r.apiVersion)).append('\n')
        sb.append("kind: ").append(yamlScalar(r.kind)).append('\n')
        sb.append("metadata:\n")
        sb.append("  name: ").append(yamlScalar(r.metadata.name)).append('\n')
        sb.append("  module: ").append(yamlScalar(r.metadata.module)).append('\n')
        if (r.metadata.labels.isNotEmpty()) {
            sb.append("  labels:\n")
            for ((k, v) in r.metadata.labels) {
                sb.append("    ").append(yamlKey(k)).append(": ").append(yamlScalar(v)).append('\n')
            }
        }
        sb.append("  version: ").append(r.metadata.version).append('\n')
        r.metadata.modified?.let { sb.append("  modified: ").append(yamlScalar(it.toString())).append('\n') }
        r.metadata.modifiedBy?.let { sb.append("  modifiedBy: ").append(yamlScalar(it)).append('\n') }
        if (r.spec.isEmpty()) {
            sb.append("spec: {}\n")
        } else {
            sb.append("spec:\n")
            writeMap(sb, r.spec, 1)
        }
        return sb.toString()
    }

    private fun writeMap(sb: StringBuilder, map: Map<*, *>, indent: Int) {
        val pad = "  ".repeat(indent)
        for ((k, v) in map) writeField(sb, pad, k.toString(), v, indent)
    }

    private fun writeField(sb: StringBuilder, pad: String, key: String, value: Any?, indent: Int) {
        sb.append(pad).append(yamlKey(key)).append(':')
        when (value) {
            is Map<*, *> -> if (value.isEmpty()) sb.append(" {}\n") else {
                sb.append('\n')
                writeMap(sb, value, indent + 1)
            }
            is List<*> -> if (value.isEmpty()) sb.append(" []\n") else {
                sb.append('\n')
                val itemPad = "  ".repeat(indent + 1)
                for (item in value) {
                    when (item) {
                        is Map<*, *> -> {
                            sb.append(itemPad).append("-")
                            if (item.isEmpty()) sb.append(" {}\n") else {
                                sb.append('\n')
                                writeMap(sb, item, indent + 2)
                            }
                        }
                        else -> sb.append(itemPad).append("- ").append(yamlAny(item)).append('\n')
                    }
                }
            }
            else -> sb.append(' ').append(yamlAny(value)).append('\n')
        }
    }

    private fun yamlAny(v: Any?): String = when (v) {
        null -> "null"
        is String -> yamlScalar(v)
        is Boolean -> if (v) "true" else "false"
        is Int, is Long -> v.toString()
        is Double -> if (v.isFinite()) java.lang.Double.toString(v) else yamlScalar(v.toString())
        is Float -> if (v.isFinite()) java.lang.Float.toString(v) else yamlScalar(v.toString())
        is Number -> v.toString()
        else -> yamlScalar(v.toString())
    }

    private fun yamlKey(key: String): String = if (needsQuote(key) || key.isEmpty()) yamlScalar(key) else key

    private fun yamlScalar(value: String): String {
        if (!needsQuote(value)) return value
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun needsQuote(value: String): Boolean {
        if (value.isEmpty()) return true
        if (value in YAML_RESERVED) return true
        if (YAML_NUMBER.matches(value)) return true
        if (value[0].isWhitespace() || value.last().isWhitespace()) return true
        for (c in value) {
            if (c == ':' || c == '#' || c == '"' || c == '\'' || c == '{' || c == '}' || c == '[' || c == ']' ||
                c == ',' || c == '&' || c == '*' || c == '!' || c == '|' || c == '>' || c == '%' || c == '@' ||
                c == '`' || c == '\n' || c == '\r' || c == '\t' || c.code < 32
            ) return true
        }
        return false
    }

    private val YAML_RESERVED = setOf(
        "true", "false", "null", "True", "False", "Null", "TRUE", "FALSE", "NULL",
        "yes", "no", "Yes", "No", "YES", "NO", "on", "off", "~",
    )
    private val YAML_NUMBER = Regex(
        """[-+]?(?:[0-9]+|0x[0-9A-Fa-f]+)|[-+]?(?:[0-9]+\.[0-9]*|\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[-+]?[0-9]+[eE][-+]?[0-9]+|[-+]?\.(?:inf|Inf|INF|nan|NaN|NAN)""",
    )
}

/**
 * Spec map that does not allocate a [java.util.LinkedHashMap.Entry] per key.
 * A tag spec is a handful of fields; the entry objects were a large part of the per-tag heap.
 * The map is immutable. Iteration order follows insertion.
 */
internal class SmallMap internal constructor(
    private val keyArray: Array<String>,
    private val valueArray: Array<Any?>,
) : Map<String, Any?> {
    override val size: Int get() = keyArray.size
    override fun isEmpty(): Boolean = keyArray.isEmpty()

    override fun get(key: String): Any? {
        for (i in keyArray.indices) if (keyArray[i] == key) return valueArray[i]
        return null
    }

    override fun containsKey(key: String): Boolean {
        for (i in keyArray.indices) if (keyArray[i] == key) return true
        return false
    }

    override fun containsValue(value: Any?): Boolean {
        for (i in valueArray.indices) if (valueArray[i] == value) return true
        return false
    }

    override val keys: Set<String> = object : java.util.AbstractSet<String>() {
        override val size: Int get() = keyArray.size
        override fun iterator(): MutableIterator<String> = object : MutableIterator<String> {
            private var i = 0
            override fun hasNext() = i < keyArray.size
            override fun next(): String {
                if (i >= keyArray.size) throw NoSuchElementException()
                return keyArray[i++]
            }
            override fun remove() = throw UnsupportedOperationException()
        }
    }

    override val values: Collection<Any?> = object : java.util.AbstractCollection<Any?>() {
        override val size: Int get() = valueArray.size
        override fun iterator(): MutableIterator<Any?> = object : MutableIterator<Any?> {
            private var i = 0
            override fun hasNext() = i < valueArray.size
            override fun next(): Any? {
                if (i >= valueArray.size) throw NoSuchElementException()
                return valueArray[i++]
            }
            override fun remove() = throw UnsupportedOperationException()
        }
    }

    override val entries: Set<Map.Entry<String, Any?>> = object : java.util.AbstractSet<Map.Entry<String, Any?>>() {
        override val size: Int get() = keyArray.size
        override fun iterator(): MutableIterator<Map.Entry<String, Any?>> = object : MutableIterator<Map.Entry<String, Any?>> {
            private var i = 0
            override fun hasNext() = i < keyArray.size
            override fun next(): Map.Entry<String, Any?> {
                if (i >= keyArray.size) throw NoSuchElementException()
                return java.util.AbstractMap.SimpleImmutableEntry(keyArray[i], valueArray[i++])
            }
            override fun remove() = throw UnsupportedOperationException()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Map<*, *>) return false
        if (other.size != size) return false
        for (i in keyArray.indices) if (other[keyArray[i]] != valueArray[i]) return false
        return true
    }

    override fun hashCode(): Int {
        var h = 0
        for (i in keyArray.indices) h += keyArray[i].hashCode() xor (valueArray[i]?.hashCode() ?: 0)
        return h
    }
}

internal object SmallMaps {
    private val keys = listOf(
        "apiVersion", "kind", "metadata", "spec", "name", "module", "labels", "version", "modified", "modifiedBy",
        "provider", "path", "type", "dataType", "value", "expression", "sourcePath", "readExpression", "writeExpression",
        "typeId", "parameters", "overrides", "opcServer", "opcItemPath", "deadband", "scaling", "mode",
        "rawLow", "rawHigh", "scaledLow", "scaledHigh", "clamp", "factor", "engLow", "engHigh", "engUnit",
        "commsLoss", "scanPeriodMillis", "commsTimeoutMillis", "writable", "documentation", "parent", "members",
        "default", "host", "port", "unitId",
    ).associateWith { it }

    private val values = listOf(
        "default", "memory", "expression", "derived", "reference", "opc", "udt", "folder",
        "Double", "Int32", "Int64", "Float", "Boolean", "String", "DateTime", "Document", "Dataset",
        "off", "absolute", "percent", "none", "linear", "squareRoot", "exponentialFilter",
        "badNotConnected", "uncertainLastKnown",
    ).associateWith { it }

    /** Spec values that are unique per tag. Pooling each one adds a map node and saves nothing. */
    private val uniqueValues = setOf(
        "path", "opcItemPath", "expression", "sourcePath", "readExpression", "writeExpression", "documentation",
    )

    fun share(text: String): String = keys[text] ?: values[text] ?: if (text.length > 48 || '/' in text) text else Canon.share(text)

    fun shareKey(text: String): String = keys[text] ?: Canon.share(text)

    fun shareValue(key: String, text: String): String {
        values[text]?.let { return it }
        if (key in uniqueValues || text.length > 48 || '/' in text) return text
        return Canon.share(text)
    }

    fun of(pairs: List<Pair<String, Any?>>): Map<String, Any?> {
        if (pairs.isEmpty()) return emptyMap()
        val ks = Array(pairs.size) { shareKey(pairs[it].first) }
        val vs = arrayOfNulls<Any>(pairs.size)
        for (i in pairs.indices) vs[i] = canonStored(ks[i], pairs[i].second)
        return SmallMap(ks, vs)
    }

    fun compact(spec: Map<String, Any?>): Map<String, Any?> {
        if (spec is SmallMap || spec.isEmpty()) return spec
        return of(spec.entries.map { it.key to it.value })
    }

    private fun canonStored(key: String, v: Any?): Any? = when (val n = ResourceCodec.normalizeScalar(v)) {
        is String -> shareValue(key, n)
        is SmallMap -> n
        is Map<*, *> -> of(n.entries.map { it.key.toString() to it.value })
        is List<*> -> n.map { item -> canonStored(key, item) }
        else -> n
    }
}

/**
 * Quote plain scalars YAML would load as a number, or refuse to parse.
 * Jackson's minimised quoting leaves `1234567890123456` and `1e10` bare, so a hex API-key id or hash
 * sometimes comes back as a Long, BigInteger, or Double and fails the string schema on restart.
 * Addresses, item paths, and timestamps do not match these forms and stay unquoted.
 */
private class StableYamlQuoting : StringQuotingChecker() {
    override fun needToQuoteName(name: String): Boolean = StringQuotingChecker.Default.instance().needToQuoteName(name)

    override fun needToQuoteValue(value: String): Boolean {
        val defaults = StringQuotingChecker.Default.instance()
        return defaults.needToQuoteValue(value) || YAML_NON_STRING.matches(value)
    }

    private companion object {
        val YAML_NON_STRING = Regex(
            """[-+]?(?:[0-9]+|0x[0-9A-Fa-f]+)|[-+]?(?:[0-9]+\.[0-9]*|\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[-+]?[0-9]+[eE][-+]?[0-9]+|[-+]?\.(?:inf|Inf|INF|nan|NaN|NAN)""",
        )
    }
}
