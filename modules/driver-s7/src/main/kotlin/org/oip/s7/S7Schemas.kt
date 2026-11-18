package org.oip.s7

import org.oip.config.SchemaRegistry

const val DEVICE_KIND = "Device"

/**
 * Config kind `Device` for module `org.oip.driver-s7`.
 * Field names match [S7DeviceConfig]. Defaults match the public device page.
 * Doc source: FEATURES DEV-025.
 */
fun s7DeviceSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("host"),
    "properties" to linkedMapOf(
        "name" to mapOf("type" to "string", "minLength" to 1),
        "description" to mapOf("type" to "string", "default" to ""),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "host" to mapOf("type" to "string", "minLength" to 1),
        "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535, "default" to 102),
        "model" to mapOf(
            "type" to "string",
            "enum" to listOf("S7_300", "S7_400", "S7_1200", "S7_1500"),
            "default" to "S7_300",
        ),
        "timeoutMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 2000),
        "localAddress" to mapOf("type" to "string"),
        "pduSize" to mapOf("type" to "integer", "minimum" to 240, "maximum" to 960, "default" to 240),
        "rack" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 7, "default" to 0),
        "slot" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 31, "default" to 2),
        "connectionType" to mapOf("type" to "string", "enum" to listOf("PG", "OP", "BASIC"), "default" to "PG"),
        "localTsap" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 65535),
        "remoteTsap" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 65535),
        "spanGaps" to mapOf("type" to "boolean", "default" to true),
        "maxGap" to mapOf("type" to "integer", "minimum" to 0, "default" to 0),
        "reconnectAfterConsecutiveTimeouts" to mapOf("type" to "integer", "minimum" to 1, "default" to 3),
        "backoffInitialMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 500),
        "backoffMaxMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 30000),
        "backoffMultiplier" to mapOf("type" to "number", "minimum" to 1, "default" to 2),
        "defaultScanPeriodMs" to mapOf("type" to "integer", "minimum" to 10, "default" to 1000),
        "commsLossQuality" to mapOf(
            "type" to "string",
            "enum" to listOf("Bad_NotConnected", "Uncertain_LastKnownValue", "Bad_NoCommunication"),
            "default" to "Bad_NotConnected",
        ),
        // CSV `block,number,member,type,offset`, one data block member per line (S7SymbolTable).
        "symbolTable" to mapOf("type" to "string", "default" to ""),
    ),
)

fun s7DeviceConfig(resourceName: String, spec: Map<String, Any?>): S7DeviceConfig {
    val model = when (spec.str("model") ?: "S7_300") {
        "S7_300" -> S7Model.S7_300
        "S7_400" -> S7Model.S7_400
        "S7_1200" -> S7Model.S7_1200
        "S7_1500" -> S7Model.S7_1500
        else -> throw IllegalArgumentException("unknown model '${spec["model"]}'")
    }
    val connection = when (spec.str("connectionType") ?: "PG") {
        "PG" -> S7ConnectionType.PG
        "OP" -> S7ConnectionType.OP
        "BASIC" -> S7ConnectionType.BASIC
        else -> throw IllegalArgumentException("unknown connectionType '${spec["connectionType"]}'")
    }
    return S7DeviceConfig(
        name = resourceName,
        host = spec.str("host") ?: throw IllegalArgumentException("host is required"),
        port = spec.int("port") ?: 102,
        model = model,
        timeoutMs = spec.long("timeoutMs") ?: 2_000,
        localAddress = spec.str("localAddress")?.takeIf { it.isNotBlank() },
        pduSize = spec.int("pduSize") ?: 240,
        rack = spec.int("rack") ?: 0,
        slot = spec.int("slot") ?: 2,
        connectionType = connection,
        localTsap = spec.int("localTsap"),
        remoteTsap = spec.int("remoteTsap"),
        spanGaps = spec.bool("spanGaps") ?: true,
        maxGap = spec.int("maxGap") ?: 0,
        reconnectAfterConsecutiveTimeouts = spec.int("reconnectAfterConsecutiveTimeouts") ?: 3,
        backoffInitialMs = spec.long("backoffInitialMs") ?: 500,
        backoffMaxMs = spec.long("backoffMaxMs") ?: 30_000,
        backoffMultiplier = spec.dbl("backoffMultiplier") ?: 2.0,
        defaultScanPeriodMs = spec.long("defaultScanPeriodMs") ?: 1_000,
        commsLossQuality = lossQuality(spec.str("commsLossQuality") ?: "Bad_NotConnected"),
        description = spec.str("description") ?: "",
        symbols = S7SymbolTable.parseCsv(spec.str("symbolTable") ?: ""),
    )
}

private fun lossQuality(name: String): QualityCode = when (name) {
    "Bad_NotConnected" -> QualityCode.Bad_NotConnected
    "Uncertain_LastKnownValue" -> QualityCode.Uncertain_LastKnownValue
    "Bad_NoCommunication" -> QualityCode.Bad_NoCommunication
    else -> throw IllegalArgumentException("unknown commsLossQuality '$name'")
}

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
internal fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
internal fun Map<String, Any?>.dbl(key: String): Double? = (this[key] as? Number)?.toDouble()
internal fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
internal fun Map<String, Any?>.enabled(): Boolean = this["enabled"] as? Boolean ?: true
