package org.oip.tags

import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import java.security.MessageDigest
import java.util.HexFormat

/** [StandardTagProvider.creationConflict] text for a path that is an existing folder. */
const val FOLDER_EXISTS = "a folder already exists at this path"

/** UDT parameters after `parent` inheritance. [error] is an inheritance error, such as a cycle. */
data class UdtTypeView(val path: String, val parameters: List<UdtParameter>, val error: String?)

/**
 * What to do when a requested instance path already exists, or repeats an earlier row of the same request.
 * - [FAIL] (default): the row is an error, so the whole request creates nothing.
 * - [SKIP]: the row is left out. Existing tags are not changed.
 * - [RENAME]: the leaf gets the first free suffix `_2`, `_3`, ... (up to `_1000`), checked against existing
 *   tags, config resources, and every other row of the request.
 */
enum class CollisionPolicy {
    FAIL, SKIP, RENAME;

    val label: String get() = name.lowercase()

    companion object {
        fun parse(raw: String?): CollisionPolicy = when (raw?.trim()?.lowercase()) {
            null, "", "fail" -> FAIL
            "skip" -> SKIP
            "rename" -> RENAME
            else -> throw IllegalArgumentException("onCollision must be fail, skip, or rename")
        }
    }
}

/**
 * Instance names from a template. Every `{n}` in [template] becomes `start + i * step`, left-padded with
 * zeros to [pad] digits. `Pump_{n}` with start 1, count 3, pad 2 gives Pump_01, Pump_02, Pump_03.
 */
data class NamePattern(val template: String, val start: Int = 1, val count: Int, val step: Int = 1, val pad: Int = 0) {
    fun problems(): List<String> {
        val out = ArrayList<String>()
        if (TOKEN !in template) out += "pattern template must contain {n}"
        if (count !in 1..UdtInstancePlanner.MAX_ROWS) out += "pattern count must be 1..${UdtInstancePlanner.MAX_ROWS}"
        if (start < 0) out += "pattern start must be >= 0"
        if (step < 1) out += "pattern step must be >= 1"
        if (pad !in 0..9) out += "pattern pad must be 0..9"
        if (out.isEmpty() && start.toLong() + (count - 1).toLong() * step > Int.MAX_VALUE) out += "pattern numbers overflow"
        return out
    }

    fun expand(): List<String> {
        require(problems().isEmpty()) { problems().joinToString("; ") }
        return (0 until count).map { i -> template.replace(TOKEN, (start + i * step).toString().padStart(pad, '0')) }
    }

    companion object {
        const val TOKEN = "{n}"
    }
}

/** One requested instance. A null [name] takes the name from the request pattern. */
data class UdtInstanceRow(val name: String?, val parameters: Map<String, Any?> = emptyMap())

data class UdtInstanceRequest(
    val typeId: String,
    val rows: List<UdtInstanceRow>,
    val provider: String = DEFAULT_PROVIDER,
    /** Parent folder of every instance, without the provider. Empty is the provider root. */
    val folder: String = "",
    val pattern: NamePattern? = null,
    val onCollision: CollisionPolicy = CollisionPolicy.FAIL,
)

enum class RowStatus {
    CREATE, RENAME, SKIP, ERROR;

    val label: String get() = name.lowercase()
}

data class PlannedRow(
    val index: Int,
    val requestedName: String?,
    /** Final leaf name, after a rename. */
    val name: String?,
    /** Full path with provider, e.g. `[default]Line/Pump_01`. */
    val path: String?,
    /** Config resource that will hold the instance (module tags, kind Tag, gateway scope). */
    val resourceName: String?,
    val status: RowStatus,
    /** Why the row is an error. Empty unless [status] is [RowStatus.ERROR]. */
    val errors: List<String>,
    /** Why a row was skipped or renamed. */
    val note: String?,
    /** Parameter values after defaults: what the instance resolves with. */
    val effectiveParameters: Map<String, Any?>,
    internal val resource: Resource?,
)

data class UdtInstancePlan(
    val request: UdtInstanceRequest,
    /** Request-level errors (unknown UDT, bad folder, bad pattern). */
    val errors: List<String>,
    val rows: List<PlannedRow>,
) {
    val ok: Boolean get() = errors.isEmpty() && rows.none { it.status == RowStatus.ERROR }
    val toCreate: List<PlannedRow> get() = rows.filter { it.status == RowStatus.CREATE || it.status == RowStatus.RENAME }
    val skipped: Int get() = rows.count { it.status == RowStatus.SKIP }

    /** Request and row errors as one flat list, for a problem+json `errors` member. */
    fun messages(): List<String> = errors + rows.flatMap { row ->
        row.errors.map { "row ${row.index + 1}${row.requestedName?.let { n -> " ($n)" } ?: ""}: $it" }
    }
}

data class UdtInstanceResult(val plan: UdtInstancePlan, val applied: Boolean, val created: Int)

/**
 * TAG-024: create many instances of one UDT in one call.
 *
 * [plan] validates every row first and does not write anything: names and paths (TagPath rules),
 * the UDT and its inherited parameters (unknown, missing, or unconvertible values), collisions with
 * existing tags, config resources, and other rows, and finally each built resource against the Tag
 * JSON Schema and [TagConfig.fromSpec]. [apply] writes only a plan with no errors, as one
 * [FileConfigStore.putAll] call: every resource is created at version 0 and the store checks all of them
 * before it replaces any file, so a row that became a conflict in the meantime fails the whole batch.
 * Plan and apply run under one monitor so two wizard requests cannot interleave.
 *
 * Authorization is the caller's job. The HTTP route requires `tags.write` (see GatewayAuth.accessFor).
 */
class UdtInstancePlanner(
    private val engine: TagEngine,
    private val store: FileConfigStore,
    private val registry: SchemaRegistry,
) {
    private val lock = Any()

    fun plan(request: UdtInstanceRequest): UdtInstancePlan = synchronized(lock) { planLocked(request) }

    /** Plan, and when [dryRun] is false and the plan has no errors, write it. */
    fun execute(request: UdtInstanceRequest, dryRun: Boolean, actor: String): UdtInstanceResult = synchronized(lock) {
        val plan = planLocked(request)
        if (dryRun || !plan.ok) return@synchronized UdtInstanceResult(plan, applied = false, created = 0)
        val resources = plan.toCreate.mapNotNull { it.resource }
        if (resources.isEmpty()) return@synchronized UdtInstanceResult(plan, applied = true, created = 0)
        val result = store.putAll(SCOPE, resources, actor, syncEach = true)
        UdtInstanceResult(plan, applied = true, created = result.created)
    }

    private fun planLocked(request: UdtInstanceRequest): UdtInstancePlan {
        val errors = ArrayList<String>()
        val providerName = request.provider.trim().ifEmpty { DEFAULT_PROVIDER }
        val provider = engine.provider(providerName)
        val standard = provider as? StandardTagProvider
        when {
            provider == null -> errors += "tag provider '$providerName' not found"
            standard == null || standard.readOnly -> errors += "tag provider '$providerName' is read-only"
        }
        val folder = parseFolder(request.folder, providerName, errors)
        val typeId = request.typeId.trim()
        var params: List<UdtParameter> = emptyList()
        if (typeId.isEmpty()) errors += "typeId is required"
        else if (standard != null) {
            val type = standard.udtType(typeId)
            when {
                type == null -> errors += "UDT '$typeId' not found in provider '$providerName'"
                type.error != null -> errors += "UDT '$typeId' is not usable: ${type.error}"
                else -> params = type.parameters
            }
        }
        if (standard != null && folder != null && folder.isNotEmpty()) {
            val conflict = standard.creationConflict(TagPath(providerName, folder))
            if (conflict != null && conflict != FOLDER_EXISTS) errors += "folder '${folder.joinToString("/")}' cannot hold tags: $conflict"
        }
        val names = names(request, errors)
        if (errors.isNotEmpty() || names == null || standard == null || folder == null) {
            return UdtInstancePlan(request, errors, emptyList())
        }

        val drafts = names.indices.map { i -> Draft(i, names[i], request.rows.getOrNull(i) ?: UdtInstanceRow(null)) }
        // Pass 1: names, paths, parameters, and collisions with what already exists.
        for (d in drafts) {
            val name = d.requested
            if (name.isNullOrBlank()) {
                d.errors += "name is required"
                continue
            }
            val path = leafPath(folder, name, providerName)
            if (path == null) {
                d.errors += "invalid tag name '$name' (letters, digits, '_', '-', and spaces)"
                continue
            }
            d.path = path
            d.effective = d.checkParametersInto(params, d.row.parameters, d.errors)
            d.existing = standard.creationConflict(path)
        }
        // Pass 2: collision policy. Names that are free and first in the request are reserved before any
        // rename, so a rename never takes a name another row asked for explicitly.
        val taken = HashSet<String>()
        val firstByKey = HashMap<String, Int>()
        for (d in drafts) {
            val key = d.path?.canonicalKey() ?: continue
            if (d.existing == null && key !in firstByKey) {
                firstByKey[key] = d.index
                taken += key
            }
        }
        for (d in drafts) {
            val path = d.path ?: continue
            val key = path.canonicalKey()
            val duplicateOf = firstByKey[key]?.takeIf { it != d.index }
            val reason = d.existing ?: duplicateOf?.let { "duplicate of row ${it + 1} in this request" } ?: continue
            when (request.onCollision) {
                CollisionPolicy.FAIL -> d.errors += "'${path.segments.last()}' collides: $reason"
                CollisionPolicy.SKIP -> d.skipReason = reason
                CollisionPolicy.RENAME -> {
                    val renamed = rename(standard, folder, path.segments.last(), providerName, taken)
                    if (renamed == null) d.errors += "'${path.segments.last()}' collides ($reason) and no free name _2.._$RENAME_LIMIT was found"
                    else {
                        d.path = renamed
                        d.renamed = true
                        taken += renamed.canonicalKey()
                    }
                }
            }
        }
        // Pass 3: resource names and the resource itself, validated by the same schema and parser the
        // config store and the tag binding use.
        val resourceNames = HashSet<String>()
        val rows = drafts.map { d ->
            val path = d.path
            if (path == null || d.errors.isNotEmpty() || d.skipReason != null) {
                return@map d.toRow(null, null)
            }
            val resourceName = resourceName(providerName, path, resourceNames)
            if (resourceName == null) {
                d.errors += "no free config resource name for '$path'"
                return@map d.toRow(null, null)
            }
            resourceNames += resourceName
            val spec = linkedMapOf<String, Any?>(
                "provider" to providerName,
                "path" to path.segments.joinToString("/"),
                "type" to "udt",
                "typeId" to typeId,
            )
            if (d.typedParameters.isNotEmpty()) spec["parameters"] = d.typedParameters
            val resource = Resource(TAG_KIND, Metadata(resourceName, TAGS_MODULE), spec)
            try {
                TagConfig.fromSpec(spec)
                d.errors += registry.validate(resource)
            } catch (e: RuntimeException) {
                d.errors += e.message ?: e.javaClass.simpleName
            }
            d.toRow(resourceName, resource.takeIf { d.errors.isEmpty() })
        }
        return UdtInstancePlan(request, emptyList(), rows)
    }

    private class Draft(val index: Int, val requested: String?, val row: UdtInstanceRow) {
        val errors = ArrayList<String>()
        var path: TagPath? = null
        var existing: String? = null
        var skipReason: String? = null
        var renamed = false
        var effective: Map<String, Any?> = emptyMap()
        val typedParameters = LinkedHashMap<String, Any?>()

        fun toRow(resourceName: String?, resource: Resource?): PlannedRow {
            val status = when {
                errors.isNotEmpty() -> RowStatus.ERROR
                skipReason != null -> RowStatus.SKIP
                renamed -> RowStatus.RENAME
                else -> RowStatus.CREATE
            }
            val note = when (status) {
                RowStatus.SKIP -> "skipped: $skipReason"
                RowStatus.RENAME -> "renamed from '$requested'"
                else -> null
            }
            return PlannedRow(
                index, requested, path?.segments?.lastOrNull(), path?.toString(), resourceName,
                status, errors.toList(), note, effective, resource,
            )
        }

        /** Validate supplied values against the inherited parameter list. Typed values go in [typedParameters]. */
        fun checkParametersInto(params: List<UdtParameter>, supplied: Map<String, Any?>, errs: MutableList<String>): Map<String, Any?> {
            val byName = params.associateBy { it.name }
            for (key in supplied.keys) if (key !in byName) errs += "unknown parameter '$key'"
            val effective = LinkedHashMap<String, Any?>()
            for (p in params) {
                val raw = supplied[p.name]
                if (raw == null || (raw is String && raw.isEmpty() && p.dataType != DataType.STRING)) {
                    if (p.defaultValue == null) errs += "parameter '${p.name}' is required (the UDT has no default)"
                    effective[p.name] = p.defaultValue
                    continue
                }
                val typed = typedParameter(raw, p.dataType)
                if (typed.isFailure) {
                    errs += "parameter '${p.name}': ${typed.exceptionOrNull()?.message}"
                    continue
                }
                typedParameters[p.name] = typed.getOrNull()
                effective[p.name] = typed.getOrNull()
            }
            return effective
        }
    }

    private fun parseFolder(raw: String, provider: String, errors: MutableList<String>): List<String>? {
        val text = raw.trim().trim('/')
        if (text.isEmpty()) return emptyList()
        val parsed = try {
            TagPath.parse(text, provider)
        } catch (e: PathException) {
            errors += "invalid folder '$raw': ${e.message}"
            return null
        }
        if (text.startsWith("[") || parsed.relative != null || parsed.property != null || parsed.indexes.isNotEmpty() ||
            parsed.segments.any { it == "." || it == ".." }
        ) {
            errors += "invalid folder '$raw' (use a plain path such as Line1/Pumps, without a provider)"
            return null
        }
        return parsed.segments
    }

    private fun names(request: UdtInstanceRequest, errors: MutableList<String>): List<String?>? {
        val pattern = request.pattern
        if (pattern == null) {
            if (request.rows.isEmpty()) errors += "rows is empty"
            if (request.rows.size > MAX_ROWS) errors += "rows exceeds $MAX_ROWS"
            return if (errors.isEmpty()) request.rows.map { it.name?.trim() } else null
        }
        val problems = pattern.problems()
        if (problems.isNotEmpty()) {
            errors += problems
            return null
        }
        if (request.rows.size > pattern.count) {
            errors += "rows has ${request.rows.size} entries but the pattern makes ${pattern.count} names"
            return null
        }
        val generated = pattern.expand()
        return generated.indices.map { i -> request.rows.getOrNull(i)?.name?.trim()?.takeIf { it.isNotEmpty() } ?: generated[i] }
    }

    private fun leafPath(folder: List<String>, name: String, provider: String): TagPath? {
        val leaf = name.trim()
        if (leaf.isEmpty() || '/' in leaf || '[' in leaf || leaf.startsWith(".")) return null
        val parsed = try {
            TagPath.parse(leaf, provider)
        } catch (_: PathException) {
            return null
        }
        if (parsed.segments.size != 1 || parsed.property != null || parsed.indexes.isNotEmpty() || parsed.segments[0] != leaf) return null
        return TagPath(provider, folder + parsed.segments[0])
    }

    private fun rename(provider: StandardTagProvider, folder: List<String>, leaf: String, providerName: String, taken: Set<String>): TagPath? {
        for (k in 2..RENAME_LIMIT) {
            val candidate = TagPath(providerName, folder + "${leaf}_$k")
            if (candidate.canonicalKey() in taken) continue
            if (provider.creationConflict(candidate) != null) continue
            return candidate
        }
        return null
    }

    /**
     * A DNS-label resource name from the path, e.g. `line-pump-01`. When that is too long, empty, or already
     * used (by a stored resource or another row), the name is a prefix plus 8 hex digits of the path hash.
     */
    private fun resourceName(provider: String, path: TagPath, used: Set<String>): String? {
        val source = (if (provider.equals(DEFAULT_PROVIDER, true)) "" else "$provider-") + path.segments.joinToString("-")
        val slug = source.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isNotEmpty() && slug.length <= 63 && free(slug, used)) return slug
        val digest = MessageDigest.getInstance("SHA-256").digest(path.canonicalKey().toByteArray(Charsets.UTF_8))
        val hash = HexFormat.of().formatHex(digest)
        for (offset in 0..(hash.length - 8) step 8) {
            val suffix = hash.substring(offset, offset + 8)
            val prefix = slug.take(54).trim('-')
            val candidate = if (prefix.isEmpty()) "tag-$suffix" else "$prefix-$suffix"
            if (free(candidate, used)) return candidate
        }
        return null
    }

    private fun free(name: String, used: Set<String>): Boolean {
        if (name in used) return false
        val id = runCatching { ResourceId(TAGS_MODULE, SCOPE, TAG_KIND, name) }.getOrNull() ?: return false
        return store.get(id) == null
    }

    companion object {
        const val MAX_ROWS = 1_000
        const val RENAME_LIMIT = 1_000
        val SCOPE: Scope = Scope.GATEWAY

        /** Strict conversion for a parameter value. Booleans accept true/false/1/0 only. */
        internal fun typedParameter(raw: Any?, type: DataType): Result<Any?> = runCatching {
            when (type) {
                DataType.BOOLEAN -> when (raw) {
                    is Boolean -> raw
                    is Number -> when (raw.toDouble()) {
                        1.0 -> true
                        0.0 -> false
                        else -> throw IllegalArgumentException("cannot convert '$raw' to Boolean")
                    }
                    is String -> when (raw.trim().lowercase()) {
                        "true", "1" -> true
                        "false", "0" -> false
                        else -> throw IllegalArgumentException("cannot convert '$raw' to Boolean")
                    }
                    else -> throw IllegalArgumentException("cannot convert '$raw' to Boolean")
                }
                DataType.INT32, DataType.INT64 -> {
                    val text = raw.toString().trim()
                    val n = asDouble(if (raw is String) text else raw) ?: throw IllegalArgumentException("cannot convert '$raw' to ${type.label()}")
                    if (n % 1.0 != 0.0) throw IllegalArgumentException("'$raw' is not a whole number")
                    when (val c = coerce(n, type)) {
                        is Int -> c.toLong()
                        else -> c
                    }
                }
                DataType.FLOAT, DataType.DOUBLE -> {
                    val n = asDouble(if (raw is String) raw.trim() else raw) ?: throw IllegalArgumentException("cannot convert '$raw' to ${type.label()}")
                    if (n.isNaN() || n.isInfinite()) throw IllegalArgumentException("'$raw' is not a finite number")
                    n
                }
                DataType.STRING -> when (raw) {
                    is Map<*, *>, is List<*> -> throw IllegalArgumentException("cannot convert an object or array to String")
                    else -> raw.toString()
                }
                // DateTime and Document keep the given JSON value once it converts.
                DataType.DATETIME, DataType.DOCUMENT -> raw.also { coerce(it, type) }
                DataType.DATASET -> coerce(raw, type)
            }
        }.recoverCatching { e -> throw IllegalArgumentException(e.message ?: "invalid value", e) }
    }
}
