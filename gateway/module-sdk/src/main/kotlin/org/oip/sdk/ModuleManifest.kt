package org.oip.sdk

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

class ManifestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        .takeIf { it != 0 } ?: when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    override fun toString() = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")
    companion object {
        private val RE = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?$""")
        fun parse(s: String): SemVer {
            val m = RE.matchEntire(s.trim()) ?: throw ManifestException("invalid semantic version '$s'")
            return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].ifEmpty { null })
        }
    }
}

/** A dependency on another module with a minimum version. */
data class Dependency(val id: String, val minVersion: SemVer)

data class ModuleManifest(
    val id: String,
    val version: SemVer,
    val name: String,
    val entrypoint: String,
    val dependencies: List<Dependency> = emptyList(),
    val permissions: List<String> = emptyList(),
    val configSchema: Map<String, Any?> = emptyMap(),
)

object ManifestParser {
    private val ID = Regex("""^[a-z][a-z0-9]*([.-][a-z0-9]+)*$""")
    private val CLASS = Regex("""^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*$""")
    private val PERM = Regex("""^[a-z][a-z0-9_.:-]*$""")
    private val KEYS = setOf("id", "version", "name", "entrypoint", "dependencies", "permissions", "configSchema")
    private val SCHEMA_TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")

    fun parse(yaml: String): ModuleManifest {
        val root = try { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml) }
                   catch (e: YAMLException) { throw ManifestException("invalid YAML: ${e.message}", e) }
        if (root !is Map<*, *>) throw ManifestException("manifest must be a YAML mapping")
        val unknown = root.keys.map { it.toString() } - KEYS
        if (unknown.isNotEmpty()) throw ManifestException("unknown manifest keys: $unknown")

        val id = str(root, "id") ?: throw ManifestException("missing 'id'")
        if (!ID.matches(id)) throw ManifestException("invalid id '$id'")
        val version = SemVer.parse(str(root, "version") ?: throw ManifestException("missing 'version'"))
        val entry = str(root, "entrypoint") ?: throw ManifestException("missing 'entrypoint'")
        if (!CLASS.matches(entry)) throw ManifestException("invalid entrypoint '$entry'")
        val name = str(root, "name") ?: id

        val deps = list(root, "dependencies").map { d ->
            if (d !is Map<*, *>) throw ManifestException("dependency must be a mapping")
            val did = str(d, "id") ?: throw ManifestException("dependency missing 'id'")
            if (!ID.matches(did)) throw ManifestException("invalid dependency id '$did'")
            if (did == id) throw ManifestException("module cannot depend on itself")
            Dependency(did, SemVer.parse(str(d, "version") ?: "0.0.0"))
        }
        if (deps.map { it.id }.toSet().size != deps.size) throw ManifestException("duplicate dependency")

        val perms = list(root, "permissions").map {
            if (it !is String || !PERM.matches(it)) throw ManifestException("invalid permission '$it'"); it
        }
        val schemaRaw = root["configSchema"] ?: emptyMap<String, Any?>()
        if (schemaRaw !is Map<*, *>) throw ManifestException("configSchema must be a mapping (JSON Schema object)")
        val schema = schemaRaw.entries.associate { it.key.toString() to it.value }
        schema["type"]?.let { if (it !is String || it !in SCHEMA_TYPES) throw ManifestException("configSchema.type invalid: $it") }

        return ModuleManifest(id, version, name, entry, deps, perms, schema)
    }

    private fun str(m: Map<*, *>, k: String): String? = when (val v = m[k]) {
        null -> null
        is String -> v.ifBlank { null }
        is Number, is Boolean -> throw ManifestException("'$k' must be a string")
        else -> throw ManifestException("'$k' must be a string")
    }
    private fun list(m: Map<*, *>, k: String): List<*> = when (val v = m[k]) {
        null -> emptyList<Any>()
        is List<*> -> v
        else -> throw ManifestException("'$k' must be a list")
    }
}
