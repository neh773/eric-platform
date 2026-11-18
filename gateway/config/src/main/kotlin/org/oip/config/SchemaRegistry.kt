package org.oip.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.util.concurrent.ConcurrentHashMap

class UnknownKindException(module: String, kind: String) : RuntimeException("no schema registered for kind '$kind' of module '$module'")
class ValidationException(val id: String, val errors: List<String>) : RuntimeException("invalid resource $id: ${errors.joinToString("; ")}")

/**
 * JSON Schema (draft 2020-12) registry keyed by (module, kind). Validation uses networknt json-schema-validator.
 * Objects that declare `properties` but neither `additionalProperties` nor `unevaluatedProperties` get
 * `additionalProperties: false` injected, so unknown spec fields are rejected (SPEC §4).
 */
class SchemaRegistry {
    private data class Entry(val raw: Map<String, Any?>, val compiled: JsonSchema)
    private val schemas = ConcurrentHashMap<Pair<String, String>, Entry>()
    private val semantics = ConcurrentHashMap<Pair<String, String>, (Map<String, Any?>) -> List<String>>()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val mapper = ObjectMapper()

    fun register(module: String, kind: String, schema: Map<String, Any?>) {
        ResourceId(module, Scope.GATEWAY, kind, "x") // validates module/kind syntax
        (schema["\$schema"] as? String)?.let { require(it.trimEnd('#') == DRAFT) { "only JSON Schema draft 2020-12 is supported, got '$it'" } }
        @Suppress("UNCHECKED_CAST") val strict = strict(schema) as Map<String, Any?>
        val meta = factory.getSchema(java.net.URI.create(DRAFT))
        val metaErrors = meta.validate(mapper.valueToTree(schema))
        require(metaErrors.isEmpty()) { "invalid schema for $module/$kind: ${metaErrors.joinToString { it.message }}" }
        schemas[module to kind] = Entry(strict, factory.getSchema(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(strict)))
    }

    /** A module manifest's `configSchema` becomes the schema of kind [MODULE_CONFIG_KIND] in that module. */
    fun registerModuleConfig(module: String, configSchema: Map<String, Any?>) {
        if (configSchema.isNotEmpty()) register(module, MODULE_CONFIG_KIND, configSchema)
    }

    /**
     * Extra checks for a kind that JSON Schema cannot express (cross-references, parseable expressions). Run by
     * [validate] only after the schema passes, so every write path (API, batch, file edit, revalidate) applies them.
     * [check] returns error messages (empty = valid).
     */
    fun registerSemantic(module: String, kind: String, check: (Map<String, Any?>) -> List<String>) {
        semantics[module to kind] = check
    }

    fun unregisterModule(module: String) {
        schemas.keys.removeIf { it.first == module }
        semantics.keys.removeIf { it.first == module }
    }
    fun isRegistered(module: String, kind: String) = schemas.containsKey(module to kind)

    /** The strict schema registered for this kind. Callers must not mutate the map. */
    fun schema(module: String, kind: String): Map<String, Any?> =
        schemas[module to kind]?.raw ?: throw UnknownKindException(module, kind)

    /** Validation errors (empty = valid). Throws [UnknownKindException] if the kind is not registered. */
    fun validate(r: Resource): List<String> {
        val e = schemas[r.metadata.module to r.kind] ?: throw UnknownKindException(r.metadata.module, r.kind)
        val errors = e.compiled.validate(mapper.valueToTree(r.spec)).map { it.message }.sorted()
        if (errors.isNotEmpty()) return errors
        val semantic = semantics[r.metadata.module to r.kind] ?: return errors
        return semantic(r.spec)
    }

    fun check(r: Resource, scope: Scope = Scope.GATEWAY) {
        val errs = validate(r); if (errs.isNotEmpty()) throw ValidationException(r.id(scope).toString(), errs)
    }

    fun defaults(module: String, kind: String): Map<String, Any?> = applyDefaults(module, kind, emptyMap())

    /** Fill missing properties from `default` keywords (recursing into object properties). Existing values win. */
    fun applyDefaults(module: String, kind: String, spec: Map<String, Any?>): Map<String, Any?> {
        val e = schemas[module to kind] ?: throw UnknownKindException(module, kind)
        @Suppress("UNCHECKED_CAST") return fill(e.raw, spec) as Map<String, Any?>
    }

    private fun fill(schema: Map<*, *>, value: Any?): Any? {
        val props = schema["properties"] as? Map<*, *> ?: return value
        val obj = (value as? Map<*, *>) ?: return value
        val out = linkedMapOf<String, Any?>(); obj.forEach { (k, v) -> out[k.toString()] = v }
        props.forEach { (k, s) ->
            val ps = s as? Map<*, *> ?: return@forEach
            val key = k.toString()
            when {
                key in out -> out[key] = fill(ps, out[key])
                ps.containsKey("default") -> out[key] = ResourceCodec.normalize(ps["default"])
                ps["properties"] != null && ps["type"] == "object" -> fill(ps, emptyMap<String, Any?>()).let { if ((it as Map<*, *>).isNotEmpty()) out[key] = it }
            }
        }
        return out
    }

    private fun strict(s: Any?): Any? = when (s) {
        is Map<*, *> -> {
            val m = s.entries.associateTo(linkedMapOf<String, Any?>()) { (k, v) ->
                k.toString() to if (k == "default" || k == "const" || k == "enum") v else strict(v) }
            if (m.containsKey("properties") && !m.containsKey("additionalProperties") && !m.containsKey("unevaluatedProperties"))
                m["additionalProperties"] = false
            m
        }
        is List<*> -> s.map { strict(it) }
        else -> s
    }

    companion object {
        const val DRAFT = "https://json-schema.org/draft/2020-12/schema"
        const val MODULE_CONFIG_KIND = "ModuleConfig"
    }
}
