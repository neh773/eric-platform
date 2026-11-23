package org.oip.opcua

import org.oip.config.SchemaRegistry
import java.nio.file.Path

const val CONNECTION_KIND = "Connection"

/**
 * Config kind `Connection` for module `org.oip.driver-opcua-client`.
 * Field names match [OpcUaConnectionConfig]. The load bench puts this shape through the config API.
 */
fun opcUaConnectionSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("endpointUrl"),
    "properties" to linkedMapOf(
        "name" to mapOf("type" to "string", "minLength" to 1),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "endpointUrl" to mapOf("type" to "string", "minLength" to 1, "pattern" to "^opc\\.tcp://.+"),
        "securityPolicy" to mapOf(
            "type" to "string",
            "enum" to listOf("NONE", "BASIC256SHA256", "AES128_SHA256_RSAOAEP", "AES256_SHA256_RSAPSS"),
            "default" to "NONE",
        ),
        "securityMode" to mapOf(
            "type" to "string",
            "enum" to listOf("NONE", "SIGN", "SIGN_AND_ENCRYPT"),
            "default" to "NONE",
        ),
        "requestTimeoutMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 5000),
        "sessionTimeoutMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 60000),
        "keepAliveIntervalMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 5000),
        "keepAliveTimeoutMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 5000),
        "keepAliveFailuresAllowed" to mapOf("type" to "integer", "minimum" to 1, "default" to 1),
        "applicationName" to mapOf("type" to "string", "minLength" to 1, "default" to "ERIC OPC UA Client"),
        "applicationUri" to mapOf("type" to "string", "minLength" to 1, "default" to "urn:oip:driver-opcua-client"),
        "trustAllServerCertificates" to mapOf("type" to "boolean", "default" to false),
        // Username sign-in. The password is a secret:// reference to the gateway secret store.
        "username" to mapOf("type" to "string"),
        "password" to mapOf("type" to "string"),
        "pkiDir" to mapOf("type" to "string", "minLength" to 1),
        "connectHost" to mapOf("type" to "string"),
        "connectPort" to mapOf("type" to "integer", "minimum" to -1, "maximum" to 65535),
        "commsLossQuality" to mapOf(
            "type" to "string",
            "enum" to listOf("Bad_NotConnected", "Uncertain_LastKnownValue", "Bad_NoCommunication"),
            "default" to "Bad_NotConnected",
        ),
    ),
)

fun opcUaConnectionConfig(resourceName: String, spec: Map<String, Any?>): OpcUaConnectionConfig {
    val policy = enumValueOf<OpcUaSecurityPolicy>(spec.str("securityPolicy") ?: "NONE")
    val mode = enumValueOf<OpcUaSecurityMode>(spec.str("securityMode") ?: "NONE")
    val pki = spec.str("pkiDir")?.let { Path.of(it) }
    return OpcUaConnectionConfig(
        name = resourceName,
        endpointUrl = spec.str("endpointUrl") ?: throw IllegalArgumentException("endpointUrl is required"),
        securityPolicy = policy,
        securityMode = mode,
        applicationName = spec.str("applicationName") ?: "ERIC OPC UA Client",
        applicationUri = spec.str("applicationUri") ?: "urn:oip:driver-opcua-client",
        requestTimeoutMs = spec.long("requestTimeoutMs") ?: 5_000,
        sessionTimeoutMs = spec.long("sessionTimeoutMs") ?: 60_000,
        keepAliveIntervalMs = spec.long("keepAliveIntervalMs") ?: 5_000,
        keepAliveTimeoutMs = spec.long("keepAliveTimeoutMs") ?: 5_000,
        keepAliveFailuresAllowed = spec.int("keepAliveFailuresAllowed") ?: 1,
        connectHost = spec.str("connectHost")?.takeIf { it.isNotBlank() },
        connectPort = spec.int("connectPort") ?: -1,
        pkiDir = pki,
        trustAllServerCertificates = spec.bool("trustAllServerCertificates") ?: false,
        auth = spec.str("username")?.takeIf { it.isNotBlank() }
            ?.let { OpcUaAuth.Username(it, spec.str("password") ?: "") }
            ?: OpcUaAuth.Anonymous,
        commsLossQuality = when (spec.str("commsLossQuality") ?: "Bad_NotConnected") {
            "Bad_NotConnected" -> QualityCode.Bad_NotConnected
            "Uncertain_LastKnownValue" -> QualityCode.Uncertain_LastKnownValue
            "Bad_NoCommunication" -> QualityCode.Bad_NoCommunication
            else -> throw IllegalArgumentException("unknown commsLossQuality")
        },
    )
}

private fun Map<String, Any?>.str(key: String): String? = this[key] as? String
private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
internal fun Map<String, Any?>.enabled(): Boolean = this["enabled"] as? Boolean ?: true
