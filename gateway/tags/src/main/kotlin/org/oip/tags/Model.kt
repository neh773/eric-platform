package org.oip.tags

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tag data types. Integer/Float/Boolean/String/DateTime/Document/Dataset match the public type list
 * (-).
 */
enum class DataType {
    INT32, INT64, FLOAT, DOUBLE, BOOLEAN, STRING, DATETIME, DOCUMENT, DATASET;

    companion object {
        fun fromName(raw: String): DataType? = when (raw.trim().lowercase()) {
            "int32", "int", "integer" -> INT32
            "int64", "long" -> INT64
            "float", "float4" -> FLOAT
            "double", "float8" -> DOUBLE
            "boolean", "bool" -> BOOLEAN
            "string" -> STRING
            "datetime", "date" -> DATETIME
            "document" -> DOCUMENT
            "dataset" -> DATASET
            else -> null
        }
    }

    fun label(): String = when (this) {
        INT32 -> "Int32"
        INT64 -> "Int64"
        FLOAT -> "Float"
        DOUBLE -> "Double"
        BOOLEAN -> "Boolean"
        STRING -> "String"
        DATETIME -> "DateTime"
        DOCUMENT -> "Document"
        DATASET -> "Dataset"
    }
}

enum class TagType {
    MEMORY, EXPRESSION, DERIVED, REFERENCE, OPC, UDT, FOLDER,
    /**
     * SQL query polled at the tag's group rate (TAG-030). The engine stores the value and enforces
     * read permissions; [org.oip.tags] does not open a database. The runner lives in `gateway/tags-query`.
     */
    QUERY;

    companion object {
        fun fromName(raw: String): TagType? = when (raw.trim().lowercase()) {
            "memory" -> MEMORY
            "expression" -> EXPRESSION
            "derived" -> DERIVED
            "reference" -> REFERENCE
            "opc" -> OPC
            "udt", "udtinstance" -> UDT
            "folder" -> FOLDER
            "query" -> QUERY
            else -> null
        }
    }
}

/** Comms-loss quality while the last value is retained (SPEC §5). */
enum class CommsLossMode {
    BAD_NOT_CONNECTED, UNCERTAIN_LAST_KNOWN;

    companion object {
        fun fromName(raw: String): CommsLossMode? = when (raw.trim().lowercase()) {
            "badnotconnected", "bad_not_connected", "bad" -> BAD_NOT_CONNECTED
            "uncertainlastknown", "uncertain_last_known", "uncertainlastknownvalue", "uncertain" -> UNCERTAIN_LAST_KNOWN
            else -> null
        }
    }
}

/**
 * When an expression tag re-evaluates (TAG-029), per the Execution Mode property in
 * [EVENT_DRIVEN] when a dependency changes (the default), [FIXED_RATE] also every
 * [TagConfig.executionRateMillis], [TAG_GROUP] also at its tag group's rate. The timed modes run from
 * [TagEngine.tickExpressionGroups].
 */
enum class ExecutionMode {
    EVENT_DRIVEN, FIXED_RATE, TAG_GROUP;

    fun label(): String = when (this) {
        EVENT_DRIVEN -> "eventDriven"
        FIXED_RATE -> "fixedRate"
        TAG_GROUP -> "tagGroup"
    }

    companion object {
        fun fromName(raw: String): ExecutionMode? = when (raw.trim().lowercase()) {
            "eventdriven", "event_driven", "event" -> EVENT_DRIVEN
            "fixedrate", "fixed_rate", "fixed" -> FIXED_RATE
            "taggroup", "tag_group", "group" -> TAG_GROUP
            else -> null
        }
    }
}

enum class DeadbandMode { OFF, ABSOLUTE, PERCENT }

/**
 * Per-tag deadband. Absolute is a difference in engineering units. Percent is a percentage of the
 * engineering span (engHigh - engLow) when both limits are set; otherwise a percentage of the
 * previous value. See -
 */
data class Deadband(val mode: DeadbandMode = DeadbandMode.OFF, val value: Double = 0.0) {
    fun suppresses(previous: Any?, next: Any?, engLow: Double?, engHigh: Double?): Boolean {
        if (mode == DeadbandMode.OFF) return previous == next
        val a = asDouble(previous) ?: return previous == next
        val b = asDouble(next) ?: return previous == next
        val delta = abs(b - a)
        return when (mode) {
            DeadbandMode.OFF -> previous == next
            DeadbandMode.ABSOLUTE -> delta < value
            DeadbandMode.PERCENT -> {
                val span = if (engLow != null && engHigh != null && engHigh != engLow) abs(engHigh - engLow)
                else abs(a)
                if (span == 0.0) a == b else delta < (value / 100.0) * span
            }
        }
    }

    companion object {
        val OFF = Deadband()
        fun absolute(v: Double) = Deadband(DeadbandMode.ABSOLUTE, v)
        fun percent(v: Double) = Deadband(DeadbandMode.PERCENT, v)
    }
}

enum class ScaleMode { NONE, LINEAR, SQUARE_ROOT, EXPONENTIAL_FILTER }

/**
 * Scaling between a raw sample and the engineering value.
 * Equations: -
 * Memory tags do not scale (same public doc). Applied to OPC samples.
 */
data class Scaling(
    val mode: ScaleMode = ScaleMode.NONE,
    val rawLow: Double = 0.0,
    val rawHigh: Double = 100.0,
    val scaledLow: Double = 0.0,
    val scaledHigh: Double = 100.0,
    val clamp: Boolean = true,
    /** Exponential-filter weight f, 0..1. Smaller f smooths more. */
    val factor: Double = 0.5,
) {
    fun forward(raw: Double, previousFiltered: Double?): ScaleResult {
        if (mode == ScaleMode.NONE) return ScaleResult(raw, false)
        if (mode == ScaleMode.EXPONENTIAL_FILTER) {
            val f = factor.coerceIn(0.0, 1.0)
            val y = if (previousFiltered == null) raw else (1.0 - f) * previousFiltered + f * raw
            return ScaleResult(y, false, y)
        }
        val dr = rawHigh - rawLow
        if (dr == 0.0) return ScaleResult(scaledLow, false)
        val ratio = (raw - rawLow) / dr
        val outside = ratio < 0.0 || ratio > 1.0
        val used = if (mode == ScaleMode.SQUARE_ROOT) {
            val r = if (clamp) ratio.coerceIn(0.0, 1.0) else ratio
            if (r < 0.0) return ScaleResult(Double.NaN, outside)
            sqrt(r)
        } else {
            if (clamp) ratio.coerceIn(0.0, 1.0) else ratio
        }
        val scaled = (scaledHigh - scaledLow) * used + scaledLow
        return ScaleResult(scaled, outside && clamp)
    }

    fun inverse(scaled: Double): Double {
        if (mode == ScaleMode.NONE || mode == ScaleMode.EXPONENTIAL_FILTER) return scaled
        val ds = scaledHigh - scaledLow
        if (ds == 0.0) return rawLow
        val ratio = (scaled - scaledLow) / ds
        val used = when (mode) {
            ScaleMode.SQUARE_ROOT -> {
                val r = if (clamp) ratio.coerceIn(0.0, 1.0) else ratio
                r * r
            }
            else -> if (clamp) ratio.coerceIn(0.0, 1.0) else ratio
        }
        return (rawHigh - rawLow) * used + rawLow
    }

    companion object {
        val NONE = Scaling()
    }
}

data class ScaleResult(val value: Double, val clamped: Boolean, val filterState: Double? = null)

/**
 * Plain OPC tags keep the fields a scan touches and a null [ext]. Expression, UDT, scaling extras,
 * and the raw spec live in [ext] so a device tag is not a 25-field object.
 * [copy] keeps the named-argument call sites the data class used to provide.
 */
class TagConfig(
    provider: String = DEFAULT_PROVIDER,
    path: String,
    type: TagType,
    dataType: DataType = DataType.DOUBLE,
    value: Any? = null,
    valueSet: Boolean = false,
    expression: String? = null,
    sourcePath: String? = null,
    readExpression: String? = null,
    writeExpression: String? = null,
    typeId: String? = null,
    parameters: Map<String, Any?> = emptyMap(),
    overrides: Map<String, Map<String, Any?>> = emptyMap(),
    opcServer: String? = null,
    opcItemPath: String? = null,
    deadband: Deadband = Deadband.OFF,
    scaling: Scaling = Scaling.NONE,
    engLow: Double? = null,
    engHigh: Double? = null,
    engUnit: String? = null,
    commsLoss: CommsLossMode = CommsLossMode.BAD_NOT_CONNECTED,
    scanPeriodMillis: Long = 1000,
    /**
     * Extra silence past one scan period before an OPC tag reads as comms loss. Null (unset) is the default rule in
     * [effectiveCommsTimeoutMillis]; an explicit value, 0 included, is used as given (review-3 F6).
     */
    commsTimeoutMillis: Long? = null,
    writable: Boolean = true,
    documentation: String? = null,
    name: String? = null,
    /** Tag group (TAG-006): scan mode/rate for driver-backed tags, or the fixed-evaluation rate
     * for an expression tag (TAG-029). "default" always exists (see [TagGroupRegistry]). */
    tagGroup: String = DEFAULT_GROUP,
    executionMode: ExecutionMode = ExecutionMode.EVENT_DRIVEN,
    /** [ExecutionMode.FIXED_RATE] period; null means 1000 ms. */
    executionRateMillis: Long? = null,
    /** Who may read the value (TAG-013); null admits every caller. */
    readAccess: TagAccess? = null,
    /** Who may write (TAG-013); null admits every caller. [writable] = false (`readOnly`) overrides this. */
    writeAccess: TagAccess? = null,
    /** Original spec keys. Inheritance and instance overrides merge these maps, not the defaulted view. */
    raw: Map<String, Any?> = emptyMap(),
    /**
     * Shorthand for a role-only [readAccess] (the form the peer network's remote reads use).
     * Ignored when [readAccess] is given. Null is unrestricted; an empty set names nobody
     * ([TagAccess.NOBODY]), as main documented. -
     */
    readPermissions: Set<String>? = null,
    /**
     * SQL text for a [TagType.QUERY] tag. `{tag path}` references are bound parameters, not text
     * spliced into the statement. Kept on [raw] so a memory or OPC tag does not grow a field for it.
     */
    query: String? = null,
    /** Named gateway database connection ([org.oip.db] `DatabaseConnection` resource name). */
    datasource: String? = null,
    /**
     * When true, the query tag's value is the full result (a Document of columns and rows) rather than
     * the first cell. the reference platform selects this by a Dataset data type; that type is not in this library
     * (P2-16), so the flag is explicit and the value is a Document.
     */
    queryDataset: Boolean = false,
) {
    val provider: String = org.oip.config.Canon.share(provider)
    /**
     * Folder (shared when it is a single segment) and leaf. The joined path is derived so a tag
     * does not retain its own `folder/leaf` string.
     */
    private var folder: String = ""
    private var leaf: String = path
    val type: TagType = type
    val dataType: DataType = dataType
    val opcServer: String? = opcServer?.let { org.oip.config.Canon.share(it) }
    val opcItemPath: String? = opcItemPath
    val deadband: Deadband = deadband
    val scaling: Scaling = scaling
    val commsLoss: CommsLossMode = commsLoss
    val scanPeriodMillis: Long = scanPeriodMillis
    val commsTimeoutMillis: Long? = commsTimeoutMillis
    val writable: Boolean = writable
    val path: String
        get() = if (folder.isEmpty()) leaf else "$folder/$leaf"

    init {
        val slash = leaf.lastIndexOf('/')
        if (slash >= 0) {
            val parent = leaf.substring(0, slash)
            val child = leaf.substring(slash + 1)
            folder = if ('/' !in parent && parent.length <= 48) org.oip.config.Canon.share(parent) else parent
            leaf = child
        }
    }

    /** Point the leaf at a string the resource name already retains. */
    internal fun aliasLeaf(shared: String) {
        if (leaf == shared) leaf = shared
    }

    private val ext: Ext? = Ext.take(
        value, valueSet, expression, sourcePath, readExpression, writeExpression, typeId,
        parameters, overrides, engLow, engHigh, engUnit, documentation, name, tagGroup, executionMode, executionRateMillis,
        readAccess ?: TagAccess.ofRoles(readPermissions), writeAccess,
        queryFields(type, raw, query, datasource, queryDataset),
    )

    val value: Any? get() = ext?.value
    val valueSet: Boolean get() = ext?.valueSet ?: false
    val expression: String? get() = ext?.expression
    val sourcePath: String? get() = ext?.sourcePath
    val readExpression: String? get() = ext?.readExpression
    val writeExpression: String? get() = ext?.writeExpression
    val typeId: String? get() = ext?.typeId
    val parameters: Map<String, Any?> get() = ext?.parameters ?: emptyMap()
    val overrides: Map<String, Map<String, Any?>> get() = ext?.overrides ?: emptyMap()
    val engLow: Double? get() = ext?.engLow
    val engHigh: Double? get() = ext?.engHigh
    val engUnit: String? get() = ext?.engUnit
    val documentation: String? get() = ext?.documentation
    val name: String? get() = ext?.name
    val tagGroup: String get() = ext?.rare?.tagGroup ?: DEFAULT_GROUP
    val executionMode: ExecutionMode get() = ext?.rare?.executionMode ?: ExecutionMode.EVENT_DRIVEN
    val executionRateMillis: Long? get() = ext?.rare?.executionRateMillis
    val readAccess: TagAccess? get() = ext?.rare?.readAccess
    val writeAccess: TagAccess? get() = ext?.rare?.writeAccess
    val raw: Map<String, Any?> get() = ext?.raw ?: emptyMap()
    /**
     * Roles named by [readAccess], or null when the tag does not restrict readers. An empty set
     * means no remote reader passes: either the permission names nobody ([TagAccess.NOBODY], main's
     * empty list), or it restricts by zone only, and a remote reader has no zone (fail closed).
     */
    val readPermissions: Set<String>? get() = readAccess?.roles
    /** SQL for a [TagType.QUERY] tag, or null. See the constructor. */
    val query: String? get() = ext?.raw?.get("query") as? String
    /** Named database connection for a [TagType.QUERY] tag, or null. */
    val datasource: String? get() = ext?.raw?.get("datasource") as? String
    /** Full result set, rather than the first cell. See the constructor. */
    val queryDataset: Boolean get() = ext?.raw?.get("queryDataset") == true

    fun copy(
        provider: String = this.provider,
        path: String = this.path,
        type: TagType = this.type,
        dataType: DataType = this.dataType,
        value: Any? = this.value,
        valueSet: Boolean = this.valueSet,
        expression: String? = this.expression,
        sourcePath: String? = this.sourcePath,
        readExpression: String? = this.readExpression,
        writeExpression: String? = this.writeExpression,
        typeId: String? = this.typeId,
        parameters: Map<String, Any?> = this.parameters,
        overrides: Map<String, Map<String, Any?>> = this.overrides,
        opcServer: String? = this.opcServer,
        opcItemPath: String? = this.opcItemPath,
        deadband: Deadband = this.deadband,
        scaling: Scaling = this.scaling,
        engLow: Double? = this.engLow,
        engHigh: Double? = this.engHigh,
        engUnit: String? = this.engUnit,
        commsLoss: CommsLossMode = this.commsLoss,
        scanPeriodMillis: Long = this.scanPeriodMillis,
        commsTimeoutMillis: Long? = this.commsTimeoutMillis,
        writable: Boolean = this.writable,
        documentation: String? = this.documentation,
        name: String? = this.name,
        tagGroup: String = this.tagGroup,
        executionMode: ExecutionMode = this.executionMode,
        executionRateMillis: Long? = this.executionRateMillis,
        readAccess: TagAccess? = this.readAccess,
        writeAccess: TagAccess? = this.writeAccess,
        raw: Map<String, Any?> = this.raw,
        query: String? = this.query,
        datasource: String? = this.datasource,
        queryDataset: Boolean = this.queryDataset,
    ) = TagConfig(
        provider, path, type, dataType, value, valueSet, expression, sourcePath, readExpression, writeExpression,
        typeId, parameters, overrides, opcServer, opcItemPath, deadband, scaling, engLow, engHigh, engUnit,
        commsLoss, scanPeriodMillis, commsTimeoutMillis, writable, documentation, name, tagGroup,
        executionMode, executionRateMillis, readAccess, writeAccess, raw,
        query = query, datasource = datasource, queryDataset = queryDataset,
    )

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is TagConfig) return false
        return provider == other.provider && folder == other.folder && leaf == other.leaf && type == other.type && dataType == other.dataType &&
            opcServer == other.opcServer && opcItemPath == other.opcItemPath && deadband == other.deadband &&
            scaling == other.scaling && commsLoss == other.commsLoss && scanPeriodMillis == other.scanPeriodMillis &&
            commsTimeoutMillis == other.commsTimeoutMillis && writable == other.writable && tagGroup == other.tagGroup &&
            executionMode == other.executionMode && executionRateMillis == other.executionRateMillis &&
            readAccess == other.readAccess && writeAccess == other.writeAccess && value == other.value &&
            valueSet == other.valueSet && expression == other.expression && sourcePath == other.sourcePath &&
            readExpression == other.readExpression && writeExpression == other.writeExpression && typeId == other.typeId &&
            parameters == other.parameters && overrides == other.overrides && engLow == other.engLow &&
            engHigh == other.engHigh && engUnit == other.engUnit && documentation == other.documentation &&
            name == other.name && raw == other.raw
    }

    override fun hashCode(): Int {
        var h = provider.hashCode()
        h = 31 * h + folder.hashCode()
        h = 31 * h + leaf.hashCode()
        h = 31 * h + type.hashCode()
        h = 31 * h + dataType.hashCode()
        h = 31 * h + (opcServer?.hashCode() ?: 0)
        h = 31 * h + (opcItemPath?.hashCode() ?: 0)
        h = 31 * h + scanPeriodMillis.hashCode()
        h = 31 * h + (name?.hashCode() ?: 0)
        return h
    }

    override fun toString() = "TagConfig(provider=$provider, path=$path, type=$type, dataType=$dataType)"

    private open class Ext(
        val value: Any?,
        val valueSet: Boolean,
        val expression: String?,
        val sourcePath: String?,
        val readExpression: String?,
        val writeExpression: String?,
        val typeId: String?,
        val parameters: Map<String, Any?>,
        val overrides: Map<String, Map<String, Any?>>,
        val engLow: Double?,
        val engHigh: Double?,
        val engUnit: String?,
        val documentation: String?,
        val name: String?,
        val raw: Map<String, Any?>,
    ) {
        /** Only [RareExt] carries one, so a tag with default group, event-driven execution, and no
         * permissions (almost every tag) pays no field for it. */
        open val rare: Rare? get() = null

        companion object {
            fun take(
                value: Any?,
                valueSet: Boolean,
                expression: String?,
                sourcePath: String?,
                readExpression: String?,
                writeExpression: String?,
                typeId: String?,
                parameters: Map<String, Any?>,
                overrides: Map<String, Map<String, Any?>>,
                engLow: Double?,
                engHigh: Double?,
                engUnit: String?,
                documentation: String?,
                name: String?,
                tagGroup: String,
                executionMode: ExecutionMode,
                executionRateMillis: Long?,
                readAccess: TagAccess?,
                writeAccess: TagAccess?,
                raw: Map<String, Any?>,
            ): Ext? {
                val rare = if (tagGroup == DEFAULT_GROUP && executionMode == ExecutionMode.EVENT_DRIVEN &&
                    executionRateMillis == null && readAccess == null && writeAccess == null
                ) null else Rare(
                    if (tagGroup == DEFAULT_GROUP) null else org.oip.config.Canon.share(tagGroup),
                    if (executionMode == ExecutionMode.EVENT_DRIVEN) null else executionMode,
                    executionRateMillis,
                    readAccess,
                    writeAccess,
                )
                if (!valueSet && value == null && expression == null && sourcePath == null && readExpression == null &&
                    writeExpression == null && typeId == null && parameters.isEmpty() && overrides.isEmpty() &&
                    engLow == null && engHigh == null && engUnit == null && documentation == null && name == null &&
                    rare == null && raw.isEmpty()
                ) return null
                val unit = engUnit?.let { org.oip.config.Canon.share(it) }
                val shownName = name?.let { org.oip.config.Canon.share(it) }
                if (rare != null) return RareExt(
                    value, valueSet, expression, sourcePath, readExpression, writeExpression, typeId,
                    parameters, overrides, engLow, engHigh, unit, documentation, shownName, raw, rare,
                )
                return Ext(
                    value, valueSet, expression, sourcePath, readExpression, writeExpression, typeId,
                    parameters, overrides, engLow, engHigh, unit, documentation, shownName, raw,
                )
            }
        }
    }

    private class RareExt(
        value: Any?, valueSet: Boolean, expression: String?, sourcePath: String?, readExpression: String?,
        writeExpression: String?, typeId: String?, parameters: Map<String, Any?>, overrides: Map<String, Map<String, Any?>>,
        engLow: Double?, engHigh: Double?, engUnit: String?, documentation: String?, name: String?, raw: Map<String, Any?>,
        override val rare: Rare,
    ) : Ext(value, valueSet, expression, sourcePath, readExpression, writeExpression, typeId, parameters, overrides,
        engLow, engHigh, engUnit, documentation, name, raw)

    /** Settings few tags use: tag group and execution mode (TAG-006/029), permissions (TAG-013). */
    private class Rare(
        val tagGroup: String?,
        val executionMode: ExecutionMode?,
        val executionRateMillis: Long?,
        val readAccess: TagAccess?,
        val writeAccess: TagAccess?,
    )

    fun memberName(): String = name ?: leaf

    fun toSpec(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "provider" to provider,
            "path" to path,
            "type" to type.name.lowercase(),
            "dataType" to dataType.label(),
        )
        if (name != null) m["name"] = name
        if (valueSet) m["value"] = value
        expression?.let { m["expression"] = it }
        sourcePath?.let { m["sourcePath"] = it }
        readExpression?.let { m["readExpression"] = it }
        writeExpression?.let { m["writeExpression"] = it }
        typeId?.let { m["typeId"] = it }
        if (parameters.isNotEmpty()) m["parameters"] = parameters
        if (overrides.isNotEmpty()) m["overrides"] = overrides
        opcServer?.let { m["opcServer"] = it }
        opcItemPath?.let { m["opcItemPath"] = it }
        if (deadband.mode != DeadbandMode.OFF) m["deadband"] = mapOf("mode" to deadband.mode.name.lowercase(), "value" to deadband.value)
        if (scaling.mode != ScaleMode.NONE) m["scaling"] = linkedMapOf(
            "mode" to when (scaling.mode) {
                ScaleMode.LINEAR -> "linear"
                ScaleMode.SQUARE_ROOT -> "squareRoot"
                ScaleMode.EXPONENTIAL_FILTER -> "exponentialFilter"
                ScaleMode.NONE -> "none"
            },
            "rawLow" to scaling.rawLow,
            "rawHigh" to scaling.rawHigh,
            "scaledLow" to scaling.scaledLow,
            "scaledHigh" to scaling.scaledHigh,
            "clamp" to scaling.clamp,
            "factor" to scaling.factor,
        )
        engLow?.let { m["engLow"] = it }
        engHigh?.let { m["engHigh"] = it }
        engUnit?.let { m["engUnit"] = it }
        if (commsLoss != CommsLossMode.BAD_NOT_CONNECTED) m["commsLoss"] = if (commsLoss == CommsLossMode.UNCERTAIN_LAST_KNOWN) "uncertainLastKnown" else "badNotConnected"
        if (scanPeriodMillis != 1000L) m["scanPeriodMillis"] = scanPeriodMillis
        commsTimeoutMillis?.let { m["commsTimeoutMillis"] = it }
        if (!writable) m["writable"] = false
        documentation?.let { m["documentation"] = it }
        if (tagGroup != DEFAULT_GROUP) m["tagGroup"] = tagGroup
        if (executionMode != ExecutionMode.EVENT_DRIVEN) m["executionMode"] = executionMode.label()
        executionRateMillis?.let { m["executionRateMillis"] = it }
        readAccess?.let { m["readPermissions"] = it.toSpec() }
        writeAccess?.let { m["writePermissions"] = it.toSpec() }
        raw["alarms"]?.let { m["alarms"] = it }
        if (type == TagType.QUERY) {
            query?.let { m["query"] = it }
            datasource?.let { m["datasource"] = it }
            if (queryDataset) m["queryDataset"] = true
        }
        return m
    }

    fun overriddenBy(partial: Map<String, Any?>): TagConfig {
        val base = LinkedHashMap(raw.ifEmpty { toSpec() })
        partial.forEach { (k, v) -> if (v == null) base.remove(k) else base[k] = v }
        return fromSpec(base, member = true)
    }

    companion object {
        fun fromSpec(spec: Map<String, Any?>, member: Boolean = false): TagConfig {
            val type = TagType.fromName(spec.str("type") ?: throw IllegalArgumentException("tag type is required"))
                ?: throw IllegalArgumentException("unknown tag type '${spec["type"]}'")
            val path = spec.str("path") ?: spec.str("name") ?: if (member) "" else throw IllegalArgumentException("tag path is required")
            val dataType = spec.str("dataType")?.let { DataType.fromName(it) ?: throw IllegalArgumentException("unknown dataType '$it'") }
                ?: DataType.DOUBLE
            val db = spec.mapOrNull("deadband")
            val deadband = if (db == null) Deadband.OFF else {
                val mode = when (db.str("mode")?.lowercase() ?: "off") {
                    "absolute" -> DeadbandMode.ABSOLUTE
                    "percent" -> DeadbandMode.PERCENT
                    "off" -> DeadbandMode.OFF
                    else -> throw IllegalArgumentException("unknown deadband mode '${db["mode"]}'")
                }
                val amount = db.num("value") ?: 0.0
                if (mode == DeadbandMode.OFF && amount == 0.0) Deadband.OFF else Deadband(mode, amount)
            }
            val sc = spec.mapOrNull("scaling")
            val scaling = if (sc == null) Scaling.NONE else {
                val mode = when (sc.str("mode")?.lowercase() ?: "none") {
                    "linear" -> ScaleMode.LINEAR
                    "squareroot", "sqrt" -> ScaleMode.SQUARE_ROOT
                    "exponentialfilter", "exponential" -> ScaleMode.EXPONENTIAL_FILTER
                    "none" -> ScaleMode.NONE
                    else -> throw IllegalArgumentException("unknown scaling mode '${sc["mode"]}'")
                }
                val built = Scaling(
                    mode,
                    sc.num("rawLow") ?: 0.0,
                    sc.num("rawHigh") ?: 100.0,
                    sc.num("scaledLow") ?: 0.0,
                    sc.num("scaledHigh") ?: 100.0,
                    sc["clamp"] as? Boolean ?: true,
                    sc.num("factor") ?: 0.5,
                )
                if (built == Scaling.NONE) Scaling.NONE else built
            }
            @Suppress("UNCHECKED_CAST")
            val params = (spec["parameters"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val overrides = (spec["overrides"] as? Map<*, *>)?.entries?.associate { e ->
                e.key.toString() to ((e.value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap())
            } ?: emptyMap()
            val loss = spec.str("commsLoss")?.let { CommsLossMode.fromName(it) ?: throw IllegalArgumentException("unknown commsLoss '$it'") }
                ?: CommsLossMode.BAD_NOT_CONNECTED
            // The defaults-applied map used to be stored on every tag (~30 keys plus nested maps).
            // Typed fields already carry those defaults. Keep the original spec only where UDT
            // inheritance merges partial maps; plain tags share [emptyMap].
            val retainRaw = member || type == TagType.UDT || params.isNotEmpty() || overrides.isNotEmpty() ||
                spec["alarms"] is List<*>
            return TagConfig(
                provider = spec.str("provider") ?: DEFAULT_PROVIDER,
                path = path,
                type = type,
                dataType = dataType,
                value = spec["value"],
                valueSet = spec.containsKey("value"),
                expression = spec.str("expression"),
                sourcePath = spec.str("sourcePath"),
                readExpression = spec.str("readExpression"),
                writeExpression = spec.str("writeExpression"),
                typeId = spec.str("typeId"),
                parameters = params,
                overrides = overrides,
                opcServer = spec.str("opcServer"),
                opcItemPath = spec.str("opcItemPath"),
                deadband = deadband,
                scaling = scaling,
                engLow = spec.num("engLow"),
                engHigh = spec.num("engHigh"),
                engUnit = spec.str("engUnit"),
                commsLoss = loss,
                scanPeriodMillis = spec.long("scanPeriodMillis") ?: 1000L,
                commsTimeoutMillis = spec.long("commsTimeoutMillis"),
                // `readOnly` (TAG-010) is the documented name; `writable: false` predates it and means the same.
                writable = (spec["writable"] as? Boolean ?: true) && spec["readOnly"] != true,
                documentation = spec.str("documentation"),
                name = spec.str("name"),
                tagGroup = spec.str("tagGroup") ?: DEFAULT_GROUP,
                executionMode = spec.str("executionMode")?.let {
                    ExecutionMode.fromName(it) ?: throw IllegalArgumentException("unknown executionMode '$it'")
                } ?: ExecutionMode.EVENT_DRIVEN,
                executionRateMillis = spec.long("executionRateMillis"),
                readAccess = TagAccess.fromSpec(spec, "readPermissions"),
                writeAccess = TagAccess.fromSpec(spec, "writePermissions"),
                raw = if (retainRaw) spec else emptyMap(),
                query = spec.str("query"),
                datasource = spec.str("datasource"),
                queryDataset = spec["queryDataset"] == true,
            )
        }
    }
}

data class UdtParameter(val name: String, val dataType: DataType = DataType.STRING, val defaultValue: Any? = null)

data class UdtDefinition(
    val provider: String = DEFAULT_PROVIDER,
    val path: String,
    val parent: String? = null,
    val parameters: List<UdtParameter> = emptyList(),
    val members: List<TagConfig> = emptyList(),
) {
    fun toSpec(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "provider" to provider,
            "path" to path,
        )
        parent?.let { m["parent"] = it }
        if (parameters.isNotEmpty()) m["parameters"] = parameters.map {
            linkedMapOf<String, Any?>("name" to it.name, "dataType" to it.dataType.name, "default" to it.defaultValue)
        }
        if (members.isNotEmpty()) m["members"] = members.map { it.toSpec() }
        return m
    }

    companion object {
        fun fromSpec(spec: Map<String, Any?>): UdtDefinition {
            val path = spec.str("path") ?: throw IllegalArgumentException("UDT path is required")
            val params = (spec["parameters"] as? List<*>)?.map { raw ->
                val p = raw as? Map<*, *> ?: throw IllegalArgumentException("UDT parameter must be an object")
                val name = p["name"]?.toString() ?: throw IllegalArgumentException("UDT parameter name is required")
                val dt = p["dataType"]?.toString()?.let { DataType.fromName(it) } ?: DataType.STRING
                UdtParameter(name, dt, p["default"])
            } ?: emptyList()
            val members = (spec["members"] as? List<*>)?.map { raw ->
                val m = raw as? Map<*, *> ?: throw IllegalArgumentException("UDT member must be an object")
                @Suppress("UNCHECKED_CAST")
                TagConfig.fromSpec(m.entries.associate { it.key.toString() to it.value }, member = true)
            } ?: emptyList()
            return UdtDefinition(
                provider = spec.str("provider") ?: DEFAULT_PROVIDER,
                path = path,
                parent = spec.str("parent"),
                parameters = params,
                members = members,
            )
        }
    }
}

enum class NodeKind { FOLDER, TAG, UDT_INSTANCE, UDT_DEFINITION }

data class BrowseNode(
    val name: String,
    val path: TagPath,
    val kind: NodeKind,
    val dataType: DataType?,
    val value: QualifiedValue?,
    /** Name of the Tag config resource that defines this node, when one does (not for UDT members or folders). */
    val resource: String? = null,
    /**
     * False when the browsing caller may not read this tag (TAG-013). Such a node keeps its name, path and
     * kind, but [dataType] and [resource] are null and [value] is `Bad_AccessDenied` with no value.
     */
    val readable: Boolean = true,
)

class TagConfigException(message: String) : IllegalArgumentException(message)

internal const val DEFAULT_PROVIDER = "default"
/** [TagEngine.deviceWriteTimeoutMillis] default: longer than a driver's own request timeout and retries. */
const val DEFAULT_DEVICE_WRITE_TIMEOUT_MS = 10_000L
internal const val SYSTEM_PROVIDER = "system"
const val TAGS_MODULE = "tags"
const val TAG_KIND = "Tag"
const val UDT_KIND = "Udt"
const val GROUP_KIND = "TagGroup"

internal fun asDouble(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.toDoubleOrNull()
    else -> null
}

/**
 * Query-tag settings live in [raw] so the common memory and OPC tags do not carry the fields.
 * Non-query tags return [raw] unchanged. A false [queryDataset] drops the key.
 */
private fun queryFields(
    type: TagType,
    raw: Map<String, Any?>,
    query: String?,
    datasource: String?,
    queryDataset: Boolean,
): Map<String, Any?> {
    if (type != TagType.QUERY) return raw
    val merged = LinkedHashMap(raw)
    if (query != null) merged["query"] = query
    if (datasource != null) merged["datasource"] = datasource
    if (queryDataset) merged["queryDataset"] = true else merged.remove("queryDataset")
    return merged
}

private fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()?.takeIf { this[key] !is Map<*, *> && this[key] !is List<*> }
private fun Map<String, Any?>.num(key: String): Double? = asDouble(this[key])
private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}
@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.mapOrNull(key: String): Map<String, Any?>? =
    (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

/** Floor of the default comms timeout (review-3 F6). */
const val DEFAULT_COMMS_TIMEOUT_FLOOR_MILLIS = 5_000L

/**
 * The comms timeout in force for a tag scanned every [periodMillis] (review-3 F6, lead decision): the configured value
 * when one is set, 0 included (no margin: silence longer than one scan period is comms loss, as before); otherwise
 * `max(3 × scan period, 5000 ms)`, so an on-time sample that arrives a little late is never read as comms loss.
 */
fun effectiveCommsTimeoutMillis(configured: Long?, periodMillis: Long): Long =
    configured ?: maxOf(3 * periodMillis.coerceAtLeast(0), DEFAULT_COMMS_TIMEOUT_FLOOR_MILLIS)
