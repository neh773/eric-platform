package org.oip.modbus

import org.oip.config.SchemaRegistry

const val DEVICE_KIND = "Device"

/**
 * Config kind `Device` for module `org.oip.driver-modbus`.
 * Field names match [ModbusDeviceConfig]. The load bench puts this shape through the config API.
 */
fun modbusDeviceSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("host"),
    "properties" to linkedMapOf(
        "name" to mapOf("type" to "string", "minLength" to 1),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "host" to mapOf("type" to "string", "minLength" to 1),
        "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535, "default" to 502),
        "mode" to mapOf("type" to "string", "enum" to listOf("TCP", "RTU_OVER_TCP"), "default" to "TCP"),
        "unitId" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 255, "default" to 1),
        "timeoutMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 1000),
        "localAddress" to mapOf("type" to "string"),
        "zeroBasedAddressing" to mapOf("type" to "boolean", "default" to false),
        "wordOrder" to mapOf("type" to "string", "enum" to listOf("HIGH_WORD_FIRST", "LOW_WORD_FIRST"), "default" to "HIGH_WORD_FIRST"),
        "byteOrder" to mapOf("type" to "string", "enum" to listOf("BIG_ENDIAN", "LITTLE_ENDIAN"), "default" to "BIG_ENDIAN"),
        "reverseStringByteOrder" to mapOf("type" to "boolean", "default" to false),
        "rightJustifyStrings" to mapOf("type" to "boolean", "default" to false),
        "readRawStrings" to mapOf("type" to "boolean", "default" to false),
        "spanGaps" to mapOf("type" to "boolean", "default" to true),
        "maxHoldingRegistersPerRead" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 125, "default" to 125),
        "maxHoldingRegistersPerWrite" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 123, "default" to 123),
        "maxInputRegistersPerRequest" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 125, "default" to 125),
        "maxCoilsPerRequest" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 2000, "default" to 2000),
        "maxDiscreteInputsPerRequest" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 2000, "default" to 2000),
        "concurrentRequests" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 32, "default" to 1),
        "allowWriteMultipleRegisters" to mapOf("type" to "boolean", "default" to true),
        "forceMultipleRegisterWrites" to mapOf("type" to "boolean", "default" to false),
        "allowWriteMultipleCoils" to mapOf("type" to "boolean", "default" to true),
        "allowReadMultipleRegisters" to mapOf("type" to "boolean", "default" to true),
        "allowReadMultipleCoils" to mapOf("type" to "boolean", "default" to true),
        "allowReadMultipleDiscreteInputs" to mapOf("type" to "boolean", "default" to true),
        "reconnectAfterConsecutiveTimeouts" to mapOf("type" to "integer", "minimum" to 1, "default" to 3),
        "maxRetryCount" to mapOf("type" to "integer", "minimum" to 0, "default" to 1),
        "backoffInitialMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 500),
        "backoffMaxMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 30000),
        "backoffMultiplier" to mapOf("type" to "number", "minimum" to 1, "default" to 2),
        "defaultScanPeriodMs" to mapOf("type" to "integer", "minimum" to 10, "default" to 1000),
        "commsLossQuality" to mapOf(
            "type" to "string",
            "enum" to listOf("Bad_NotConnected", "Uncertain_LastKnownValue", "Bad_NoCommunication"),
            "default" to "Bad_NotConnected",
        ),
    ),
)

fun modbusDeviceConfig(resourceName: String, spec: Map<String, Any?>): ModbusDeviceConfig {
    val mode = when (spec.str("mode") ?: "TCP") {
        "TCP" -> ModbusMode.TCP
        "RTU_OVER_TCP" -> ModbusMode.RTU_OVER_TCP
        else -> throw IllegalArgumentException("unknown mode '${spec["mode"]}'")
    }
    val word = when (spec.str("wordOrder") ?: "HIGH_WORD_FIRST") {
        "HIGH_WORD_FIRST" -> WordOrder.HIGH_WORD_FIRST
        "LOW_WORD_FIRST" -> WordOrder.LOW_WORD_FIRST
        else -> throw IllegalArgumentException("unknown wordOrder '${spec["wordOrder"]}'")
    }
    val bytes = when (spec.str("byteOrder") ?: "BIG_ENDIAN") {
        "BIG_ENDIAN" -> ByteOrder.BIG_ENDIAN
        "LITTLE_ENDIAN" -> ByteOrder.LITTLE_ENDIAN
        else -> throw IllegalArgumentException("unknown byteOrder '${spec["byteOrder"]}'")
    }
    return ModbusDeviceConfig(
        name = resourceName,
        host = spec.str("host") ?: throw IllegalArgumentException("host is required"),
        port = spec.int("port") ?: 502,
        mode = mode,
        unitId = spec.int("unitId") ?: 1,
        timeoutMs = spec.long("timeoutMs") ?: 1_000,
        localAddress = spec.str("localAddress")?.takeIf { it.isNotBlank() },
        zeroBasedAddressing = spec.bool("zeroBasedAddressing") ?: false,
        wordOrder = word,
        byteOrder = bytes,
        reverseStringByteOrder = spec.bool("reverseStringByteOrder") ?: false,
        rightJustifyStrings = spec.bool("rightJustifyStrings") ?: false,
        readRawStrings = spec.bool("readRawStrings") ?: false,
        spanGaps = spec.bool("spanGaps") ?: true,
        maxHoldingRegistersPerRead = spec.int("maxHoldingRegistersPerRead") ?: 125,
        maxHoldingRegistersPerWrite = spec.int("maxHoldingRegistersPerWrite") ?: 123,
        maxInputRegistersPerRequest = spec.int("maxInputRegistersPerRequest") ?: 125,
        maxCoilsPerRequest = spec.int("maxCoilsPerRequest") ?: 2000,
        maxDiscreteInputsPerRequest = spec.int("maxDiscreteInputsPerRequest") ?: 2000,
        concurrentRequests = spec.int("concurrentRequests") ?: 1,
        allowWriteMultipleRegisters = spec.bool("allowWriteMultipleRegisters") ?: true,
        forceMultipleRegisterWrites = spec.bool("forceMultipleRegisterWrites") ?: false,
        allowWriteMultipleCoils = spec.bool("allowWriteMultipleCoils") ?: true,
        allowReadMultipleRegisters = spec.bool("allowReadMultipleRegisters") ?: true,
        allowReadMultipleCoils = spec.bool("allowReadMultipleCoils") ?: true,
        allowReadMultipleDiscreteInputs = spec.bool("allowReadMultipleDiscreteInputs") ?: true,
        reconnectAfterConsecutiveTimeouts = spec.int("reconnectAfterConsecutiveTimeouts") ?: 3,
        maxRetryCount = spec.int("maxRetryCount") ?: 1,
        backoffInitialMs = spec.long("backoffInitialMs") ?: 500,
        backoffMaxMs = spec.long("backoffMaxMs") ?: 30_000,
        backoffMultiplier = spec.dbl("backoffMultiplier") ?: 2.0,
        defaultScanPeriodMs = spec.long("defaultScanPeriodMs") ?: 1_000,
        commsLossQuality = lossQuality(spec.str("commsLossQuality") ?: "Bad_NotConnected"),
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
