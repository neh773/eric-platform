package org.oip.tags

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.ConflictException
import org.oip.config.FileConfigStore
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry

/**
 * Tags and UDT definitions are typed config resources (SPEC §4) owned by module [TAGS_MODULE].
 * UDT definitions are `<configRoot>/tags/<scope>/Udt/<name>.yaml`. Tag resources share a fixed
 * shard set, `<configRoot>/tags/<scope>/Tag/_shards/sXX.yaml` (ADR 0011), not one file per tag.
 * Runtime values are not written back to the config files.
 */
fun registerTagSchemas(registry: SchemaRegistry) {
    registry.register(TAGS_MODULE, TAG_KIND, tagResourceSchema())
    registry.register(TAGS_MODULE, UDT_KIND, udtResourceSchema())
    registry.register(TAGS_MODULE, GROUP_KIND, tagGroupResourceSchema())
}

class TagConfigBinding(
    val engine: TagEngine,
    val store: FileConfigStore,
    val registry: SchemaRegistry,
) : AutoCloseable {
    private val problems = ArrayList<String>()
    private var subscription: AutoCloseable? = null
    private var guard: AutoCloseable? = null

    /**
     * Tag resource name by the location it claims (lowercase provider and path). The store refuses a
     * create or update that would claim a location another Tag resource holds, so a taken path is a 409
     * for the caller instead of a tag that silently drops out of the engine. Kept apart from the engine
     * and its lock; guarded by itself. Keyed provider, then path, so a claim costs no key object of its own
     * (TagScaleTest footprint).
     */
    private val claims = HashMap<String, HashMap<String, String>>()
    /** Claimed path by resource name; the provider is in [claimProvider] when it is not the default. */
    private val claimOf = HashMap<String, String>()
    private val claimProvider = HashMap<String, String>()

    fun problems(): List<String> = problems.toList()

    fun attach() {
        registerTagSchemas(registry)
        store.revalidate(TAGS_MODULE)
        reload()
        subscription = store.subscribe { event -> onEvent(event) }
        guard = store.guard { writes -> checkClaims(writes) }
    }

    fun reload() {
        val all = store.list(TAGS_MODULE)
        synchronized(claims) {
            claims.clear()
            claimOf.clear()
            claimProvider.clear()
            all.filter { it.resource.kind == TAG_KIND && !it.id.scope.isOverlay }.forEach { claim(it.id, it.resource) }
        }
        all.filter { it.resource.kind == GROUP_KIND }.forEach { apply(it.id, it.resource) }
        all.filter { it.resource.kind == UDT_KIND }.forEach { apply(it.id, it.resource) }
        all.filter { it.resource.kind == TAG_KIND }.forEach { apply(it.id, it.resource) }
    }

    override fun close() {
        guard?.close()
        guard = null
        subscription?.close()
        subscription = null
    }

    /** Caller holds the [claims] monitor. The first resource to claim a location keeps it. */
    private fun claim(id: ResourceId, resource: Resource) {
        unclaim(id)
        val (provider, path) = location(resource)
        if (claims.getOrPut(provider) { HashMap() }.putIfAbsent(path, id.name) == null) {
            claimOf[id.name] = path
            if (provider != DEFAULT_CLAIM) claimProvider[id.name] = provider
        }
    }

    private fun unclaim(id: ResourceId) {
        val path = claimOf.remove(id.name) ?: return
        val provider = claimProvider.remove(id.name) ?: DEFAULT_CLAIM
        val held = claims[provider] ?: return
        if (held[path] == id.name) held.remove(path)
    }

    /** Caller holds the [claims] monitor. */
    private fun ownerOf(provider: String, path: String): String? = claims[provider]?.get(path)

    /** Caller holds the [claims] monitor. Whether [name] already holds this location. */
    private fun holds(name: String, provider: String, path: String): Boolean =
        claimOf[name] == path && (claimProvider[name] ?: DEFAULT_CLAIM) == provider

    private fun checkClaims(writes: List<Pair<ResourceId, Resource>>) {
        var batch: HashMap<Pair<String, String>, String>? = null
        for ((id, resource) in writes) {
            if (id.module != TAGS_MODULE || id.kind != TAG_KIND || id.scope.isOverlay) continue
            val at = location(resource)
            if (writes.size > 1) {
                val seen = (batch ?: HashMap<Pair<String, String>, String>().also { batch = it }).put(at, id.name)
                if (seen != null && seen != id.name)
                    throw ConflictException("Tag path ${describe(resource)} is given to both '$seen' and '${id.name}'")
            }
            val owner = synchronized(claims) { if (holds(id.name, at.first, at.second)) null else ownerOf(at.first, at.second) }
            if (owner != null && owner != id.name)
                throw ConflictException("Tag path ${describe(resource)} is already used by tag '$owner'")
        }
    }

    private fun describe(resource: Resource): String =
        "[${resource.spec["provider"] as? String ?: DEFAULT_PROVIDER}]${resource.spec["path"] as? String ?: ""}"

    private fun onEvent(event: ConfigEvent) {
        val id = event.id ?: return
        if (id.module != TAGS_MODULE) return
        if (id.kind == TAG_KIND && !id.scope.isOverlay) synchronized(claims) {
            val resource = event.resource
            if (event.type == ChangeType.DELETED || resource == null) unclaim(id) else claim(id, resource)
        }
        when (event.type) {
            ChangeType.DELETED -> remove(id, event.previous)
            ChangeType.CREATED -> event.resource?.let { apply(id, it) }
            ChangeType.UPDATED -> event.resource?.let { resource ->
                val previous = event.previous
                if (id.kind == TAG_KIND && previous != null && location(previous) != location(resource)) move(id, previous, resource)
                else apply(id, resource)
            }
            ChangeType.INVALID -> problems += "$id: ${event.error}"
        }
    }

    private fun apply(id: ResourceId, resource: Resource) {
        try {
            // Defaults stay in the typed fields. Applying them here built a second ~30-key map per tag
            // and kept it on [TagConfig.raw] beside the store's own spec.
            when (id.kind) {
                UDT_KIND -> engine.configureUdt(UdtDefinition.fromSpec(resource.spec), origin = id.name, originKind = id.kind)
                TAG_KIND -> if (isSystem(resource)) applySystem(resource, clear = false)
                    else engine.configure(TagConfig.fromSpec(resource.spec), origin = id.name, originKind = id.kind)
                GROUP_KIND -> engine.configureGroup(TagGroupDef.fromSpec(id.name, resource.spec))
                else -> return
            }
        } catch (e: RuntimeException) {
            problems += "$id: ${e.message}"
        }
    }

    private companion object {
        /** [DEFAULT_PROVIDER] as [location] spells it. */
        val DEFAULT_CLAIM = DEFAULT_PROVIDER.lowercase()
    }

    private fun location(resource: Resource): Pair<String, String> =
        (resource.spec["provider"] as? String ?: DEFAULT_PROVIDER).lowercase() to (resource.spec["path"] as? String ?: "").trim('/').lowercase()

    /**
     * The resource's `path` (or provider) changed: drop the tag at the old path, create it at the new
     * one. A plain memory tag carries its runtime value over when its data type and seed did not
     * change (the same rule a UDT re-expansion uses), so a renamed setpoint keeps its setpoint.
     */
    private fun move(id: ResourceId, previous: Resource, resource: Resource) {
        val before = runCatching { TagConfig.fromSpec(previous.spec) }.getOrNull()
        val carried = before?.takeIf { it.type == TagType.MEMORY }?.let { engine.read("[${it.provider}]${it.path}") }
        remove(id, previous)
        apply(id, resource)
        val after = runCatching { TagConfig.fromSpec(resource.spec) }.getOrNull() ?: return
        if (carried == null || carried.quality.level != Level.GOOD || after.type != TagType.MEMORY) return
        if (after.dataType != before.dataType || after.valueSet != before.valueSet || after.value != before.value) return
        engine.openSample("[${after.provider}]${after.path}").set(carried.value, carried.timestamp)
    }

    /** A Tag resource in the read-only `[system]` provider holds that tag's alarm configuration only (ALA-040). */
    private fun isSystem(resource: Resource): Boolean =
        (resource.spec["provider"] as? String)?.equals(SYSTEM_PROVIDER, ignoreCase = true) == true

    private fun applySystem(resource: Resource, clear: Boolean) {
        val path = resource.spec["path"] as? String ?: return
        val alarms = (resource.spec["alarms"] as? List<*>)?.mapNotNull { item ->
            (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }
        engine.setSystemAlarms(path, if (clear) null else alarms)
    }

    private fun remove(id: ResourceId, previous: Resource? = null) {
        if (previous != null && id.kind == TAG_KIND && isSystem(previous)) {
            applySystem(previous, clear = true)
            return
        }
        if (id.kind == GROUP_KIND) {
            engine.removeGroup(id.name)
            return
        }
        engine.providers().filterIsInstance<StandardTagProvider>().forEach { it.removeOrigin(id.kind, id.name) }
    }
}

private fun tagFields(requirePath: Boolean): Map<String, Any?> {
    val props = linkedMapOf<String, Any?>(
        "provider" to mapOf("type" to "string", "minLength" to 1, "default" to DEFAULT_PROVIDER),
        "path" to mapOf("type" to "string", "minLength" to 1),
        "name" to mapOf("type" to "string", "minLength" to 1),
        "type" to mapOf("type" to "string", "enum" to listOf("memory", "expression", "derived", "reference", "opc", "udt", "folder", "query")),
        "dataType" to mapOf("type" to "string", "enum" to listOf("Int32", "Int64", "Float", "Double", "Boolean", "String", "DateTime", "Document", "Dataset"), "default" to "Double"),
        "value" to emptyMap<String, Any?>(),
        "expression" to mapOf("type" to "string"),
        "sourcePath" to mapOf("type" to "string"),
        "readExpression" to mapOf("type" to "string"),
        "writeExpression" to mapOf("type" to "string"),
        "typeId" to mapOf("type" to "string"),
        "parameters" to mapOf("type" to "object", "additionalProperties" to true),
        "opcServer" to mapOf("type" to "string"),
        "opcItemPath" to mapOf("type" to "string"),
        "deadband" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "mode" to mapOf("type" to "string", "enum" to listOf("off", "absolute", "percent"), "default" to "off"),
                "value" to mapOf("type" to "number", "default" to 0),
            ),
        ),
        "scaling" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "mode" to mapOf("type" to "string", "enum" to listOf("none", "linear", "squareRoot", "exponentialFilter"), "default" to "none"),
                "rawLow" to mapOf("type" to "number", "default" to 0),
                "rawHigh" to mapOf("type" to "number", "default" to 100),
                "scaledLow" to mapOf("type" to "number", "default" to 0),
                "scaledHigh" to mapOf("type" to "number", "default" to 100),
                "clamp" to mapOf("type" to "boolean", "default" to true),
                "factor" to mapOf("type" to "number", "minimum" to 0, "maximum" to 1, "default" to 0.5),
            ),
        ),
        "engLow" to mapOf("type" to "number"),
        "engHigh" to mapOf("type" to "number"),
        "engUnit" to mapOf("type" to "string"),
        "commsLoss" to mapOf("type" to "string", "enum" to listOf("badNotConnected", "uncertainLastKnown"), "default" to "badNotConnected"),
        "scanPeriodMillis" to mapOf("type" to "integer", "minimum" to 0, "default" to 1000),
        // Unset means max(3 x scan period, 5000 ms); 0 means no margin past one scan period (review-3 F6).
        "commsTimeoutMillis" to mapOf("type" to "integer", "minimum" to 0),
        "writable" to mapOf("type" to "boolean", "default" to true),
        "documentation" to mapOf("type" to "string"),
        "tagGroup" to mapOf("type" to "string", "minLength" to 1, "default" to "default"),
        "executionMode" to mapOf("type" to "string", "enum" to listOf("eventDriven", "fixedRate", "tagGroup"), "default" to "eventDriven"),
        "executionRateMillis" to mapOf("type" to "integer", "minimum" to 1),
        // Query tag (TAG-030). -
        // datasource is a gateway DatabaseConnection name. queryDataset returns the whole result as a Document.
        "query" to mapOf("type" to "string", "maxLength" to 100_000),
        "datasource" to mapOf(
            "type" to "string",
            "minLength" to 1,
            "maxLength" to 63,
            "pattern" to "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$",
        ),
        "queryDataset" to mapOf("type" to "boolean", "default" to false),
        // Alarm objects use the public JSON names. Bindings are tag, expression, or UDT parameter.
        "alarms" to mapOf("type" to "array", "items" to alarmSchema()),
        // TAG-010/013. readOnly rejects every write; it is the documented name for writable: false.
        "readOnly" to mapOf("type" to "boolean", "default" to false),
        // A plain list (or comma-separated string) of role names is the role-only shorthand the
        // peer network's remote reads use. Present but naming nobody (null, [], "", {}) denies
        // every user; only an absent key is unrestricted (TagAccess.NOBODY).
        "readPermissions" to permissionSchema(),
        "writePermissions" to permissionSchema(),
    )
    val required = if (requirePath) listOf("path", "type") else listOf("name", "type")
    return linkedMapOf(
        "type" to "object",
        "required" to required,
        "properties" to props,
    )
}

/** One alarm object. Property names are the public JSON names, not a private file format. */
private fun alarmSchema(): Map<String, Any?> {
    val bound = mapOf(
        "anyOf" to listOf(
            mapOf("type" to "number"),
            mapOf("type" to "boolean"),
            mapOf("type" to "string"),
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "tag" to mapOf("type" to "string", "minLength" to 1),
                    "expression" to mapOf("type" to "string", "minLength" to 1),
                    "parameter" to mapOf("type" to "string", "minLength" to 1),
                ),
            ),
        ),
    )
    return mapOf(
        "type" to "object",
        "required" to listOf("name", "mode"),
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "minLength" to 1),
            "enabled" to bound,
            "priority" to mapOf("type" to "string", "enum" to listOf("Diagnostic", "Low", "Medium", "High", "Critical")),
            "mode" to mapOf(
                "type" to "string",
                "enum" to listOf("AboveValue", "BelowValue", "Equality", "Bit", "OutOfEngRange", "OutsideValues"),
            ),
            "setpointA" to bound,
            "setpointB" to bound,
            "inclusiveA" to bound,
            "inclusiveB" to bound,
            "bitPosition" to bound,
            "bitOnZero" to bound,
            "deadband" to bound,
            "deadbandMode" to mapOf("type" to "string", "enum" to listOf("Absolute", "Percent", "Off")),
            "timeOnDelaySeconds" to mapOf("type" to "number", "minimum" to 0),
            "timeOffDelaySeconds" to mapOf("type" to "number", "minimum" to 0),
            "displayPath" to bound,
            "notes" to mapOf("type" to "string"),
            "ackMode" to mapOf("type" to "string", "enum" to listOf("Unused", "Auto", "Manual")),
            "ackNotesReqd" to mapOf("type" to "boolean"),
            "shelvingAllowed" to mapOf("type" to "boolean"),
            "label" to mapOf("type" to "string"),
            "timestampSource" to mapOf("type" to "string", "enum" to listOf("System", "Value")),
            // Pipelines selected per event, plus the alarm's own email text.
            "activePipeline" to mapOf("type" to "string", "maxLength" to 63),
            "clearPipeline" to mapOf("type" to "string", "maxLength" to 63),
            "ackPipeline" to mapOf("type" to "string", "maxLength" to 63),
            "emailSubject" to mapOf("type" to "string", "maxLength" to 500),
            "emailMessage" to mapOf("type" to "string", "maxLength" to 8000),
            // Each value is fixed text, or a binding ({tag}, {expression} or {parameter}) read when the event goes active (ALA-039).
            "associatedData" to mapOf("type" to "object", "additionalProperties" to mapOf("anyOf" to listOf(
                mapOf("type" to "string", "maxLength" to 500),
                mapOf("type" to "number"),
                mapOf("type" to "boolean"),
                (bound["anyOf"] as List<*>).last(),
            ))),
        ),
    )
}
/** `{roles: [...], zones: [...]}`; one empty list does not restrict that dimension, both empty name nobody, see [TagAccess]. */
private fun permissionSchema(): Map<String, Any?> = mapOf("anyOf" to listOf(
    accessSchema(),
    mapOf("type" to "array", "items" to mapOf("type" to "string")),
    mapOf("type" to "string"),
    mapOf("type" to "null"),
))

private fun accessSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "properties" to mapOf(
        "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string", "minLength" to 1)),
        "zones" to mapOf("type" to "array", "items" to mapOf("type" to "string", "minLength" to 1)),
    ),
)

private fun tagResourceSchema(): Map<String, Any?> {
    val fields = tagFields(requirePath = true)
    @Suppress("UNCHECKED_CAST")
    val props = LinkedHashMap(fields["properties"] as Map<String, Any?>)
    props["overrides"] = mapOf(
        "type" to "object",
        "additionalProperties" to tagFields(requirePath = false).let { member ->
            val copy = LinkedHashMap(member)
            copy.remove("required")
            copy
        },
    )
    val schema = LinkedHashMap(fields)
    schema["properties"] = props
    schema["\$schema"] = SchemaRegistry.DRAFT
    return schema
}

private fun udtResourceSchema(): Map<String, Any?> {
    val member = tagFields(requirePath = false)
    return linkedMapOf(
        "\$schema" to SchemaRegistry.DRAFT,
        "type" to "object",
        "required" to listOf("path"),
        "properties" to linkedMapOf(
            "provider" to mapOf("type" to "string", "minLength" to 1, "default" to DEFAULT_PROVIDER),
            "path" to mapOf("type" to "string", "minLength" to 1),
            "parent" to mapOf("type" to "string"),
            "parameters" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("name"),
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "minLength" to 1),
                        "dataType" to mapOf("type" to "string", "default" to "String"),
                        "default" to emptyMap<String, Any?>(),
                    ),
                ),
            ),
            "members" to mapOf("type" to "array", "items" to member),
        ),
    )
}

/** Tag group resource (TAG-006): scan mode/rate for driver-backed tags and the rate a fixed-rate
 * expression tag re-evaluates on (TAG-029). See [TagGroupDef]. */
private fun tagGroupResourceSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("mode"),
    "properties" to linkedMapOf(
        "mode" to mapOf("type" to "string", "enum" to listOf("direct", "driven", "leased"), "default" to "direct"),
        "rateMillis" to mapOf("type" to "integer", "minimum" to 1, "default" to 1000),
        "slowRateMillis" to mapOf("type" to listOf("integer", "null"), "minimum" to 0),
        "drivingExpression" to mapOf("type" to "string"),
    ),
)
