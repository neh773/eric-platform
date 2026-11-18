package org.oip.tags

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.oip.config.FileConfigStore
import org.oip.config.JsonMergePatch
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.config.ValidationException
import org.oip.tags.expr.ExpressionException
import org.oip.tags.expr.Expressions

/**
 * Tag/UDT config export and import (TAG-005):
 *
 * One JSON document per provider (optionally a folder subtree): `{version, provider, udts, tags}`,
 * each of `udts`/`tags` a list of `{name, spec}` sorted by resource name so two exports of the same
 * state are byte-identical. `name` is the config resource's name (not a file name -- `kind: Tag`
 * resources share a fixed shard set per ADR 0011; `kind: Udt` stays one file per resource, ADR 0005).
 * `spec` is the same map [TagConfigBinding] loads from the store.
 *
 * A folder-subtree export includes every UDT definition its tags need: a UDT instance's `typeId`,
 * that UDT's `parent` chain, and any nested member's `typeId`, followed transitively, so the result
 * can be imported into an empty provider on its own, not just UDTs whose own path happens to be under
 * the folder.
 *
 * Import applies one collision policy per existing resource -- overwrite (replace), merge (RFC 7386
 * patch: only keys present in the import change), or ignore (existing resource untouched; new
 * resources are still added) -- and is all-or-nothing: every resource is validated (schema, UDT
 * typeId/parent references, expression syntax) before anything is written, so one bad entry leaves
 * the store unchanged and fires no change event. Udt resources are written before Tag resources,
 * matching [TagConfigBinding.reload].
 *
 * [import] takes an optional [CollisionPolicy] companion parameter, `provider`: when given, it must
 * match the document's own `provider` field or the import is rejected outright (nothing written) --
 * this library does not silently remap a document exported from one provider into another. Each entry
 * is also checked against the provider it would actually land in (its own `provider`, after any merge,
 * and that of an existing resource with the same name), and UDT `typeId`/`parent` references only
 * resolve against UDT definitions of the document's provider.
 *
 * Memory-tag *runtime* values are not part of this document -- only the configured seed `value` that
 * already lives in the tag's spec, same as any other field.
 */
object TagExchange {
    const val FORMAT_VERSION = 1

    /**
     * Keys a `[system]` provider Tag resource may hold: the path it names, its type and data type as exported, and the
     * alarm list. System tags themselves are gateway-owned, so their alarm configuration is what moves between
     * gateways. Doc source: FEATURES ALA-040.
     */
    private val SYSTEM_KEYS = setOf("provider", "path", "name", "type", "dataType", "alarms", "documentation")

    enum class CollisionPolicy { OVERWRITE, MERGE, IGNORE }

    data class ImportIssue(val resource: String, val message: String)

    data class ImportReport(
        val created: List<String>,
        val updated: List<String>,
        val skipped: List<String>,
        val failed: List<ImportIssue>,
    )

    private data class Entry(val name: String, val spec: Map<String, Any?>)

    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    /**
     * Export every Tag/Udt resource of [provider] from [store], optionally limited to the subtree
     * rooted at [folderPath] (a tag path; null exports the whole provider). The UDT set is not just
     * UDTs whose own path is under [folderPath]: it is that set, unioned with every UDT any included
     * tag needs transitively (typeId, parent, nested member typeId) -- see the class doc.
     */
    fun export(store: FileConfigStore, provider: String = DEFAULT_PROVIDER, folderPath: String? = null, scope: Scope = Scope.GATEWAY): String {
        val tagEntries = entries(store, TAG_KIND, scope, provider, folderPath)
        val allUdts = store.list(TAGS_MODULE, scope, UDT_KIND)
            .filter { (it.resource.spec["provider"] as? String ?: DEFAULT_PROVIDER) == provider }
            .map { Entry(it.id.name, it.resource.spec) }
        val udtByPath = allUdts.associateBy { it.spec["path"] as? String ?: "" }

        val roots = LinkedHashSet<String>()
        tagEntries.forEach { collectUdtTypeIds(it.spec, roots) }
        allUdts.filter { belongs(it.spec, provider, folderPath) }.forEach { roots += (it.spec["path"] as? String ?: "") }

        val udtEntries = closeOverUdts(roots, udtByPath).sortedBy { it.name }
        val doc = linkedMapOf<String, Any?>(
            "version" to FORMAT_VERSION,
            "provider" to provider,
            "udts" to udtEntries.map { entryMap(it) },
            "tags" to tagEntries.map { entryMap(it) },
        )
        return mapper.writeValueAsString(doc)
    }

    /** Every UDT in [udtByPath] reachable from [roots] via `typeId`/`parent`/nested member `typeId`. */
    private fun closeOverUdts(roots: Set<String>, udtByPath: Map<String, Entry>): List<Entry> {
        val closure = LinkedHashMap<String, Entry>()
        val queue = ArrayDeque(roots.filter { it.isNotEmpty() })
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (path in closure) continue
            val entry = udtByPath[path] ?: continue // dangling reference; import-time validation catches this, not export's job
            closure[path] = entry
            val spec = entry.spec
            (spec["parent"] as? String)?.let { queue.add(it) }
            @Suppress("UNCHECKED_CAST")
            (spec["members"] as? List<Map<String, Any?>>)?.forEach { member -> collectUdtTypeIds(member, queue) }
        }
        return closure.values.toList()
    }

    private fun collectUdtTypeIds(spec: Map<String, Any?>, into: MutableCollection<String>) {
        if (spec["type"] == "udt") (spec["typeId"] as? String)?.let { into += it }
    }

    private fun entryMap(e: Entry) = linkedMapOf<String, Any?>("name" to e.name, "spec" to e.spec)

    private fun entries(store: FileConfigStore, kind: String, scope: Scope, provider: String, folderPath: String?): List<Entry> =
        store.list(TAGS_MODULE, scope, kind)
            .filter { belongs(it.resource.spec, provider, folderPath) }
            .map { Entry(it.id.name, it.resource.spec) }
            .sortedBy { it.name }

    private fun providerOf(spec: Map<String, Any?>): String = spec["provider"] as? String ?: DEFAULT_PROVIDER

    private fun belongs(spec: Map<String, Any?>, provider: String, folderPath: String?): Boolean {
        if (providerOf(spec) != provider) return false
        if (folderPath.isNullOrEmpty()) return true
        val path = spec["path"] as? String ?: return false
        return path == folderPath || path.startsWith("$folderPath/")
    }

    /**
     * Import a document produced by [export] into [store]. Every resource is validated first; if any
     * one fails, [ImportReport.failed] names every bad resource (store path, e.g. `tags/gateway/Tag/x`)
     * and nothing is written and no change event fires. Otherwise the resources are written in one
     * [FileConfigStore.putAll], Udt resources first.
     *
     * [provider], if given, must equal the document's own `provider` field or the whole import is
     * rejected (nothing written) -- this never silently remaps a document from one provider to
     * another. Leave it null to accept whatever provider the document declares.
     */
    fun import(store: FileConfigStore, json: String, policy: CollisionPolicy, provider: String? = null, scope: Scope = Scope.GATEWAY, actor: String = "system"): ImportReport {
        registerTagSchemas(store.registry)
        val doc = decode(json)
        if (provider != null && provider != doc.provider) {
            return ImportReport(emptyList(), emptyList(), emptyList(), listOf(
                ImportIssue("$TAGS_MODULE/${scope.path}", "document provider '${doc.provider}' does not match target provider '$provider'"),
            ))
        }

        // UDT references resolve within the document's provider only: a definition with the same path
        // in another provider does not satisfy a typeId/parent here.
        val existingUdtPaths = store.list(TAGS_MODULE, scope, UDT_KIND)
            .filter { providerOf(it.resource.spec) == doc.provider }
            .map { it.resource.spec["path"] as? String }
        val importedUdtPaths = doc.udts.filter { providerOf(it.spec) == doc.provider }.map { it.spec["path"] as? String }
        val udtPathUniverse = (existingUdtPaths + importedUdtPaths).filterNotNull().toHashSet()

        val issues = ArrayList<ImportIssue>()
        val planned = ArrayList<Resource>()
        val created = ArrayList<String>()
        val updated = ArrayList<String>()
        val skipped = ArrayList<String>()

        fun plan(kind: String, entry: Entry) {
            val label = "$TAGS_MODULE/${scope.path}/$kind/${entry.name}"
            try {
                val id = ResourceId(TAGS_MODULE, scope, kind, entry.name)
                val existing = store.get(id)
                // Resource names are shared across providers, so check the provider each entry actually
                // lands in: the entry's own, and (any policy) that of the resource it would collide with.
                val entryProvider = entry.spec["provider"] as? String
                if (entryProvider != null && entryProvider != doc.provider) {
                    issues += ImportIssue(label, "entry provider '$entryProvider' does not match document provider '${doc.provider}'")
                    return
                }
                if (existing != null && providerOf(existing.spec) != doc.provider) {
                    issues += ImportIssue(label, "existing resource belongs to provider '${providerOf(existing.spec)}', not '${doc.provider}'")
                    return
                }
                if (existing != null && policy == CollisionPolicy.IGNORE) {
                    skipped += label
                    return
                }
                val finalSpec = if (existing != null && policy == CollisionPolicy.MERGE) {
                    @Suppress("UNCHECKED_CAST")
                    JsonMergePatch.apply(existing.spec, entry.spec) as Map<String, Any?>
                } else entry.spec
                if (providerOf(finalSpec) != doc.provider) {
                    issues += ImportIssue(label, "resource would land in provider '${providerOf(finalSpec)}', not '${doc.provider}'")
                    return
                }
                if (doc.provider.equals(SYSTEM_PROVIDER, ignoreCase = true)) {
                    if (kind != TAG_KIND) {
                        issues += ImportIssue(label, "the system provider holds tag alarm configuration only")
                        return
                    }
                    val extra = finalSpec.keys - SYSTEM_KEYS
                    if (extra.isNotEmpty()) {
                        issues += ImportIssue(label, "system tag configuration holds alarms only; unsupported keys: ${extra.sorted().joinToString()}")
                        return
                    }
                }
                validate(store, kind, label, finalSpec, udtPathUniverse, issues)
                planned += Resource(kind, Metadata(entry.name, TAGS_MODULE, version = existing?.metadata?.version ?: 0L), finalSpec)
                if (existing == null) created += label else updated += label
            } catch (e: IllegalArgumentException) {
                issues += ImportIssue(label, e.message ?: "invalid resource")
            }
        }

        doc.udts.forEach { plan(UDT_KIND, it) }
        doc.tags.forEach { plan(TAG_KIND, it) }

        if (issues.isNotEmpty()) return ImportReport(emptyList(), emptyList(), emptyList(), issues)
        if (planned.isNotEmpty()) store.putAll(scope, planned, actor)
        return ImportReport(created, updated, skipped, emptyList())
    }

    private fun validate(store: FileConfigStore, kind: String, label: String, spec: Map<String, Any?>, udtPathUniverse: Set<String>, issues: MutableList<ImportIssue>) {
        try {
            store.registry.check(Resource(kind, Metadata("x", TAGS_MODULE), spec))
        } catch (e: ValidationException) {
            issues += e.errors.map { ImportIssue(label, it) }
            return
        }
        if (kind == UDT_KIND) {
            val parent = spec["parent"] as? String
            if (parent != null && parent !in udtPathUniverse) issues += ImportIssue(label, "unknown parent UDT '$parent'")
            @Suppress("UNCHECKED_CAST")
            (spec["members"] as? List<Map<String, Any?>>)?.forEach { member -> checkTagFields(label, member, udtPathUniverse, issues) }
        } else {
            checkTagFields(label, spec, udtPathUniverse, issues)
        }
    }

    private fun checkTagFields(label: String, spec: Map<String, Any?>, udtPathUniverse: Set<String>, issues: MutableList<ImportIssue>) {
        when (spec["type"] as? String) {
            "expression" -> checkExpression(label, spec["expression"] as? String, issues)
            "derived" -> {
                checkExpression(label, spec["readExpression"] as? String, issues)
                checkExpression(label, spec["writeExpression"] as? String, issues)
            }
            "udt" -> {
                val typeId = spec["typeId"] as? String
                if (typeId.isNullOrBlank()) issues += ImportIssue(label, "UDT instance requires typeId")
                else if (typeId !in udtPathUniverse) issues += ImportIssue(label, "unknown UDT '$typeId'")
            }
            else -> {}
        }
    }

    private fun checkExpression(label: String, text: String?, issues: MutableList<ImportIssue>) {
        if (text.isNullOrBlank()) return
        try {
            Expressions.parse(text)
        } catch (e: ExpressionException) {
            issues += ImportIssue(label, "bad expression: ${e.message}")
        }
    }

    private data class Doc(val version: Int, val provider: String, val udts: List<Entry>, val tags: List<Entry>)

    @Suppress("UNCHECKED_CAST")
    private fun decode(json: String): Doc {
        val root = try {
            mapper.readValue(json, Any::class.java) as? Map<String, Any?> ?: throw IllegalArgumentException("export document must be a JSON object")
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            throw IllegalArgumentException("malformed JSON: ${e.originalMessage}")
        }
        val version = (root["version"] as? Number)?.toInt() ?: throw IllegalArgumentException("missing version")
        require(version == FORMAT_VERSION) { "unsupported export version $version (expected $FORMAT_VERSION)" }
        val provider = root["provider"] as? String ?: DEFAULT_PROVIDER
        val udts = (root["udts"] as? List<Map<String, Any?>>)?.map { toEntry(it) } ?: emptyList()
        val tags = (root["tags"] as? List<Map<String, Any?>>)?.map { toEntry(it) } ?: emptyList()
        return Doc(version, provider, udts, tags)
    }

    @Suppress("UNCHECKED_CAST")
    private fun toEntry(m: Map<String, Any?>): Entry {
        val name = m["name"] as? String ?: throw IllegalArgumentException("export entry missing name")
        val spec = m["spec"] as? Map<String, Any?> ?: throw IllegalArgumentException("export entry '$name' missing spec")
        return Entry(name, spec)
    }
}
