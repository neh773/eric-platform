package org.oip.simulator

import org.oip.config.SchemaRegistry

const val DEVICE_KIND = "Device"

/**
 * Config kind `Device` for module `org.oip.driver-simulator`.
 * One resource is one simulator. `instructions` are the program rows from the public page
 * (time interval, browse path, value source, data type). `program` loads a preloaded Generic,
 * Dairy, or SLC program when `instructions` is empty. `legacyMode` publishes those addresses
 * with the `_Meta:` item prefix. `randomSeed` is not on that page; it makes `random()` repeatable.
 * See [compileProgram].
 *
 */
fun simulatorDeviceSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "properties" to linkedMapOf(
        "name" to mapOf("type" to "string", "minLength" to 1),
        "description" to mapOf("type" to "string", "default" to ""),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "repeatProgram" to mapOf("type" to "boolean", "default" to true),
        "baseRateMs" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 86_400_000, "default" to 1000),
        "randomSeed" to mapOf("type" to "integer", "default" to 1),
        "program" to mapOf("type" to "string", "enum" to PROGRAMS, "default" to PROGRAM_NONE),
        "legacyMode" to mapOf("type" to "boolean", "default" to false),
        "instructions" to mapOf(
            "type" to "array",
            "default" to emptyList<Any>(),
            "maxItems" to 100_000,
            "items" to mapOf(
                "type" to "object",
                "required" to listOf("timeInterval", "browsePath", "valueSource", "dataType"),
                "properties" to mapOf(
                    "timeInterval" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 100_000),
                    "browsePath" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 512),
                    "valueSource" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 4_000),
                    "dataType" to mapOf("type" to "string", "enum" to DATA_TYPES),
                ),
            ),
        ),
    ),
)

/** Item-path prefix the preloaded programs publish. Hidden in CSV browse paths. */
const val LEGACY_ITEM_PREFIX = "_Meta:"

const val PROGRAM_NONE = "none"
const val PROGRAM_GENERIC = "generic"
const val PROGRAM_DAIRY = "dairy"
const val PROGRAM_SLC = "slc"

private val PROGRAMS = listOf(PROGRAM_NONE, PROGRAM_GENERIC, PROGRAM_DAIRY, PROGRAM_SLC)

private val DATA_TYPES = listOf(
    "Boolean", "boolean",
    "Int16", "int16",
    "UInt16", "uint16",
    "Int32", "int32",
    "UInt32", "uint32",
    "Int64", "int64",
    "UInt64", "uint64",
    "Float", "float",
    "Double", "double",
    "String", "string",
    "DateTime", "datetime",
)

/**
 * [specified] is the set of keys the caller actually set. Schema defaults fill the rest.
 * A preloaded [program] is used only when the caller did not supply `instructions`. Legacy item
 * paths turn on for that load unless the caller set `legacyMode`.
 */
fun simulatorDeviceConfig(
    resourceName: String,
    spec: Map<String, Any?>,
    specified: Set<String> = spec.keys,
): SimulatorDeviceConfig {
    val explicit = instructionRows(spec["instructions"])
    val programName = (spec.str("program") ?: PROGRAM_NONE).trim().lowercase()
    val preloaded = when (programName) {
        "", PROGRAM_NONE -> null
        PROGRAM_GENERIC, PROGRAM_DAIRY, PROGRAM_SLC -> SimulatorPrograms.instructions(programName)
        else -> throw IllegalArgumentException("unknown simulator program '$programName'")
    }
    val usePreloaded = explicit.isEmpty() && preloaded != null
    val legacy = if ("legacyMode" in specified) spec.bool("legacyMode") ?: false else usePreloaded
    return SimulatorDeviceConfig(
        name = resourceName,
        description = spec.str("description") ?: "",
        enabled = spec.bool("enabled") ?: true,
        repeatProgram = spec.bool("repeatProgram") ?: true,
        baseRateMs = spec.long("baseRateMs") ?: 1_000,
        randomSeed = spec.long("randomSeed") ?: 1L,
        legacyItemPaths = legacy,
        instructions = if (usePreloaded) preloaded else explicit,
        // Writable tags of the preloaded Generic, Dairy, and SLC programs keep a written value
        // until the next write. The flag is not a CSV column and is not applied to a user program,
        // including a re-import of a preloaded program's CSV.
        // doc source: FEATURES DEV-028
        persistWrites = if (usePreloaded) preloadedPersistWrites(programName, preloaded) else emptySet(),
    )
}

/**
 * Generic: the `Writeable/` folder. Dairy and SLC: every address whose value source is a plain
 * constant, which is every writable address those programs have (Dairy's moving values are
 * functions and still republish each base rate).
 */
private fun preloadedPersistWrites(programName: String, rows: List<SimInstruction>): Set<String> = when (programName) {
    PROGRAM_GENERIC -> rows.filter { it.browsePath.startsWith("Writeable/") }.map { it.browsePath }.toSet()
    else -> rows.filter { row ->
        runCatching { parseValueSource(row.valueSource, parseSimType(row.dataType), 1L, row.browsePath) }
            .getOrNull() is ConstantGen
    }.map { it.browsePath }.toSet()
}

private fun instructionRows(raw: Any?): List<SimInstruction> {
    val rows = raw as? List<*> ?: return emptyList()
    return rows.map { item ->
        val row = item as? Map<*, *> ?: throw IllegalArgumentException("instruction must be an object")
        SimInstruction(
            timeInterval = (row["timeInterval"] as? Number)?.toInt()
                ?: throw IllegalArgumentException("timeInterval is required"),
            browsePath = row["browsePath"] as? String ?: throw IllegalArgumentException("browsePath is required"),
            valueSource = row["valueSource"] as? String ?: throw IllegalArgumentException("valueSource is required"),
            dataType = row["dataType"] as? String ?: throw IllegalArgumentException("dataType is required"),
        )
    }
}

data class SimulatorDeviceConfig(
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val repeatProgram: Boolean = true,
    val baseRateMs: Long = 1_000,
    val randomSeed: Long = 1,
    val legacyItemPaths: Boolean = false,
    val instructions: List<SimInstruction> = emptyList(),
    /**
     * Browse paths whose constant is published once. A later firing of that same constant does
     * not replace a write. Only the preloaded Generic, Dairy, and SLC programs set this. User
     * programs stay empty, so a write lasts until the program next publishes that address.
     */
    val persistWrites: Set<String> = emptySet(),
)

data class SimInstruction(
    val timeInterval: Int,
    val browsePath: String,
    val valueSource: String,
    val dataType: String,
)

private fun Map<String, Any?>.str(key: String): String? = this[key] as? String
private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
