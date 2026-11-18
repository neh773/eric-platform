package org.oip.mqtt

import org.oip.config.SchemaRegistry

const val CONNECTION_KIND = "Connection"

/**
 * MQTT client connection.
 *
 * Username and password are `secret://` references (SEC-023). The schema rejects any other string,
 * and the driver resolves the reference at each CONNECT. Resolved text is never logged.
 *
 */
data class MqttConnectionConfig(
    val name: String,
    val host: String,
    val port: Int = 1883,
    val protocol: MqttProtocol = MqttProtocol.MQTT_3_1_1,
    val clientId: String,
    val keepAliveSeconds: Int = 30,
    val cleanSession: Boolean = true,
    val usernameRef: String? = null,
    val passwordRef: String? = null,
    val tls: Boolean = false,
    val insecureTrustAllCertificates: Boolean = false,
    val tlsTrustStore: String? = null,
    val tlsTrustStorePasswordRef: String? = null,
    val willTopic: String? = null,
    val willPayload: String = "",
    val willQos: Int = 0,
    val willRetain: Boolean = false,
    val sparkplugHostId: String? = null,
    val sparkplugGroup: String? = null,
    val reorderTimeoutMs: Long = 2_000,
    val autoCreateTags: Boolean = true,
    val reconnectInitialMs: Long = 500,
    val reconnectMaxMs: Long = 30_000,
    val defaultScanPeriodMs: Long = 1_000,
    val connectTimeoutMs: Int = 5_000,
) {
    /** Omits credentials, the will, and secret references. */
    override fun toString(): String =
        "MqttConnectionConfig(name=$name, host=$host, port=$port, protocol=$protocol, clientId=$clientId)"
}

private const val SECRET_PATTERN = "^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"

fun mqttConnectionSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("host"),
    "properties" to linkedMapOf(
        "name" to mapOf("type" to "string", "minLength" to 1),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "host" to mapOf("type" to "string", "minLength" to 1),
        "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535, "default" to 1883),
        "protocol" to mapOf("type" to "string", "enum" to listOf("MQTT_3_1_1", "MQTT_5"), "default" to "MQTT_3_1_1"),
        "clientId" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 128),
        "keepAliveSeconds" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 65535, "default" to 30),
        "cleanSession" to mapOf(
            "type" to "boolean",
            "default" to true,
            "description" to "Forced on when sparkplugHostId is set. MQTT 5 uses Clean Start and Session Expiry 0.",
        ),
        "username" to mapOf("type" to "string", "pattern" to SECRET_PATTERN),
        "password" to mapOf("type" to "string", "pattern" to SECRET_PATTERN),
        "tls" to mapOf("type" to "boolean", "default" to false),
        "insecureTrustAllCertificates" to mapOf(
            "type" to "boolean",
            "default" to false,
            "description" to "Skip TLS certificate and hostname checks. Test only.",
        ),
        "tlsTrustStore" to mapOf("type" to "string"),
        "tlsTrustStorePassword" to mapOf("type" to "string", "pattern" to SECRET_PATTERN),
        "willTopic" to mapOf("type" to "string"),
        "willPayload" to mapOf("type" to "string", "default" to ""),
        "willQos" to mapOf("type" to "integer", "enum" to listOf(0, 1), "default" to 0),
        "willRetain" to mapOf("type" to "boolean", "default" to false),
        "sparkplugHostId" to mapOf(
            "type" to "string",
            "description" to "Primary host id. The session publishes spBv1.0/STATE/<id> and subscribes to Sparkplug B.",
        ),
        "sparkplugGroup" to mapOf("type" to "string"),
        "reorderTimeoutMs" to mapOf("type" to "integer", "minimum" to 0, "default" to 2000),
        "autoCreateTags" to mapOf("type" to "boolean", "default" to true),
        "reconnectInitialMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 500),
        "reconnectMaxMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 30000),
        "defaultScanPeriodMs" to mapOf("type" to "integer", "minimum" to 10, "default" to 1000),
        "connectTimeoutMs" to mapOf("type" to "integer", "minimum" to 100, "maximum" to 120000, "default" to 5000),
    ),
)

fun mqttConnectionConfig(resourceName: String, spec: Map<String, Any?>): MqttConnectionConfig {
    val protocol = when (spec.str("protocol") ?: "MQTT_3_1_1") {
        "MQTT_3_1_1" -> MqttProtocol.MQTT_3_1_1
        "MQTT_5" -> MqttProtocol.MQTT_5
        else -> throw IllegalArgumentException("unknown protocol '${spec["protocol"]}'")
    }
    val hostId = spec.str("sparkplugHostId")?.takeIf { it.isNotBlank() }
    val username = spec.str("username")?.takeIf { it.isNotBlank() }
    val password = spec.str("password")?.takeIf { it.isNotBlank() }
    if (password != null && username == null) throw IllegalArgumentException("mqtt password requires a username")
    checkSecret(username)
    checkSecret(password)
    checkSecret(spec.str("tlsTrustStorePassword")?.takeIf { it.isNotBlank() })
    val clientId = spec.str("clientId")?.takeIf { it.isNotBlank() } ?: "oip-$resourceName"
    if (clientId.length !in 1..128) throw IllegalArgumentException("clientId length")
    return MqttConnectionConfig(
        name = resourceName,
        host = spec.str("host") ?: throw IllegalArgumentException("host is required"),
        port = spec.int("port") ?: 1883,
        protocol = protocol,
        clientId = clientId,
        keepAliveSeconds = spec.int("keepAliveSeconds") ?: 30,
        cleanSession = if (hostId != null) true else spec.bool("cleanSession") ?: true,
        usernameRef = username,
        passwordRef = password,
        tls = spec.bool("tls") ?: false,
        insecureTrustAllCertificates = spec.bool("insecureTrustAllCertificates") ?: false,
        tlsTrustStore = spec.str("tlsTrustStore")?.takeIf { it.isNotBlank() },
        tlsTrustStorePasswordRef = spec.str("tlsTrustStorePassword")?.takeIf { it.isNotBlank() },
        willTopic = spec.str("willTopic")?.takeIf { it.isNotBlank() },
        willPayload = spec.str("willPayload") ?: "",
        willQos = spec.int("willQos") ?: 0,
        willRetain = spec.bool("willRetain") ?: false,
        sparkplugHostId = hostId,
        sparkplugGroup = spec.str("sparkplugGroup")?.takeIf { it.isNotBlank() },
        reorderTimeoutMs = spec.long("reorderTimeoutMs") ?: 2_000,
        autoCreateTags = spec.bool("autoCreateTags") ?: true,
        reconnectInitialMs = spec.long("reconnectInitialMs") ?: 500,
        reconnectMaxMs = spec.long("reconnectMaxMs") ?: 30_000,
        defaultScanPeriodMs = spec.long("defaultScanPeriodMs") ?: 1_000,
        connectTimeoutMs = spec.int("connectTimeoutMs") ?: 5_000,
    )
}

private fun checkSecret(reference: String?) {
    if (reference == null) return
    if (!Regex(SECRET_PATTERN).matches(reference)) throw IllegalArgumentException("mqtt secret field must be a secret:// reference")
}

private fun Map<String, Any?>.str(key: String): String? = this[key] as? String
private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
internal fun Map<String, Any?>.enabled(): Boolean = this["enabled"] as? Boolean ?: true
