package org.oip.logix

import org.oip.config.SchemaRegistry

const val DEVICE_KIND = "Device"

/**
 * Config kind `Device` for module `org.oip.driver-logix`.
 * Doc source: FEATURES DEV-008, DEV-010.
 * `profile` picks the controller family (doc source: FEATURES DEV-017, DEV-021, DEV-023).
 */
fun logixDeviceSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("host"),
    "properties" to linkedMapOf(
        "name" to mapOf("type" to "string", "minLength" to 1),
        "description" to mapOf("type" to "string", "default" to ""),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "host" to mapOf("type" to "string", "minLength" to 1),
        "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535, "default" to 44818),
        "localAddress" to mapOf("type" to "string"),
        "timeoutMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 2000),
        "connectionPath" to mapOf("type" to "string", "default" to ""),
        "slot" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 31, "default" to 0),
        "cipConnectionSize" to mapOf("type" to "integer", "minimum" to 64, "maximum" to 4000, "default" to 500),
        "cipConnectionTimeoutMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 8000),
        "identityRequestFrequencyMs" to mapOf("type" to "integer", "minimum" to 0, "default" to 5000),
        "maxConcurrentRequests" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 8, "default" to 1),
        "automaticRebrowse" to mapOf("type" to "boolean", "default" to true),
        "symbolInstanceAddressing" to mapOf("type" to "boolean", "default" to false),
        "backoffInitialMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 500),
        "backoffMaxMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 30000),
        "backoffMultiplier" to mapOf("type" to "number", "minimum" to 1, "default" to 2),
        "defaultScanPeriodMs" to mapOf("type" to "integer", "minimum" to 10, "default" to 1000),
        "profile" to mapOf(
            "type" to "string",
            "enum" to LogixProfile.entries.map { it.id },
            "default" to LogixProfile.LOGIX.id,
        ),
        "commsLossQuality" to mapOf(
            "type" to "string",
            "enum" to listOf("Bad_NotConnected", "Uncertain_LastKnownValue", "Bad_NoCommunication"),
            "default" to "Bad_NotConnected",
        ),
    ),
)

fun logixDeviceConfig(resourceName: String, spec: Map<String, Any?>): LogixDeviceConfig = LogixDeviceConfig(
    name = resourceName,
    host = spec.str("host") ?: throw IllegalArgumentException("host is required"),
    port = spec.int("port") ?: 44818,
    description = spec.str("description") ?: "",
    localAddress = spec.str("localAddress")?.takeIf { it.isNotBlank() },
    timeoutMs = spec.long("timeoutMs") ?: 2_000,
    connectionPath = spec.str("connectionPath") ?: "",
    slot = spec.int("slot") ?: 0,
    cipConnectionSize = spec.int("cipConnectionSize") ?: 500,
    cipConnectionTimeoutMs = spec.long("cipConnectionTimeoutMs") ?: 8_000,
    identityRequestFrequencyMs = spec.long("identityRequestFrequencyMs") ?: 5_000,
    maxConcurrentRequests = spec.int("maxConcurrentRequests") ?: 1,
    automaticRebrowse = spec.bool("automaticRebrowse") ?: true,
    symbolInstanceAddressing = spec.bool("symbolInstanceAddressing") ?: false,
    backoffInitialMs = spec.long("backoffInitialMs") ?: 500,
    backoffMaxMs = spec.long("backoffMaxMs") ?: 30_000,
    backoffMultiplier = spec.dbl("backoffMultiplier") ?: 2.0,
    defaultScanPeriodMs = spec.long("defaultScanPeriodMs") ?: 1_000,
    commsLossQuality = lossQuality(spec.str("commsLossQuality") ?: "Bad_NotConnected"),
    profile = LogixProfile.of(spec.str("profile") ?: LogixProfile.LOGIX.id),
)

/**
 * Checks the schema cannot express, run on every save: the connection path parses into port and
 * link pairs that fit a Forward Open, and the profile allows the connection size, slot and
 * addressing mode asked for. doc source: FEATURES DEV-009, DEV-017, DEV-021, DEV-023
 */
fun logixDeviceErrors(spec: Map<String, Any?>): List<String> = try {
    logixDeviceConfig("check", spec)
    emptyList()
} catch (e: IllegalArgumentException) {
    listOf(e.message ?: "invalid Logix device")
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
