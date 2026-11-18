package org.oip.mqtt

import org.oip.config.SchemaRegistry

const val EDGE_NODE_KIND = "EdgeNode"

/**
 * One Sparkplug B edge node that publishes gateway tags (`EdgeNode` config resource).
 *
 * The edge node opens its own MQTT session. It cannot share a `Connection` session because the
 * NDEATH will belongs to the session (Sparkplug B 3.0, edge node session establishment).
 * https://sparkplug.eclipse.org/specification/version/3.0/documents/sparkplug-specification-3.0.0.pdf
 */
data class SparkplugEdgeConfig(
    val name: String,
    val host: String,
    val port: Int = 1883,
    val protocol: MqttProtocol = MqttProtocol.MQTT_3_1_1,
    val clientId: String,
    val keepAliveSeconds: Int = 30,
    val usernameRef: String? = null,
    val passwordRef: String? = null,
    val tls: Boolean = false,
    val insecureTrustAllCertificates: Boolean = false,
    val tlsTrustStore: String? = null,
    val tlsTrustStorePasswordRef: String? = null,
    val groupId: String,
    val edgeNodeId: String,
    val primaryHostId: String? = null,
    val nodeMetrics: List<EdgeMetricConfig> = emptyList(),
    val devices: List<EdgeDeviceConfig> = emptyList(),
    val publishPeriodMs: Long = 20,
    val reconnectInitialMs: Long = 500,
    val reconnectMaxMs: Long = 30_000,
    val connectTimeoutMs: Int = 5_000,
) {
    /** Omits credentials and secret references. */
    override fun toString(): String =
        "SparkplugEdgeConfig(name=$name, host=$host, port=$port, groupId=$groupId, edgeNodeId=$edgeNodeId)"
}

/** [dataType] is a Sparkplug datatype name. Null takes the tag's data type, then the value's type. */
data class EdgeMetricConfig(val name: String, val tag: String, val dataType: String? = null, val writable: Boolean = false)

data class EdgeDeviceConfig(val id: String, val metrics: List<EdgeMetricConfig>)

/** Sparkplug B 3.0 datatype names accepted in `dataType`. */
internal val EDGE_DATATYPES = linkedMapOf(
    "Int8" to SparkplugCodec.INT8,
    "Int16" to SparkplugCodec.INT16,
    "Int32" to SparkplugCodec.INT32,
    "Int64" to SparkplugCodec.INT64,
    "UInt8" to SparkplugCodec.UINT8,
    "UInt16" to SparkplugCodec.UINT16,
    "UInt32" to SparkplugCodec.UINT32,
    "UInt64" to SparkplugCodec.UINT64,
    "Float" to SparkplugCodec.FLOAT,
    "Double" to SparkplugCodec.DOUBLE,
    "Boolean" to SparkplugCodec.BOOLEAN,
    "String" to SparkplugCodec.STRING,
    "DateTime" to SparkplugCodec.DATETIME,
    "Text" to SparkplugCodec.TEXT,
)

private const val SECRET_PATTERN = "^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"

/** Group, edge node, and device ids are topic levels, so `/`, `+` and `#` are not allowed. */
private const val ID_PATTERN = "^[^/+#]+$"

/** A metric name may contain `/` (a folder path) but not the MQTT wildcards. */
private const val METRIC_PATTERN = "^[^+#]+$"

private fun metricSchema(): Map<String, Any?> = linkedMapOf(
    "type" to "object",
    "required" to listOf("name", "tag"),
    "additionalProperties" to false,
    "properties" to linkedMapOf(
        "name" to mapOf("type" to "string", "minLength" to 1, "pattern" to METRIC_PATTERN),
        "tag" to mapOf("type" to "string", "minLength" to 1, "description" to "Gateway tag path, for example [default]Line/Speed."),
        "dataType" to mapOf("type" to "string", "enum" to EDGE_DATATYPES.keys.toList()),
        "writable" to mapOf(
            "type" to "boolean",
            "default" to false,
            "description" to "Accept NCMD or DCMD writes to this metric.",
        ),
    ),
)

fun sparkplugEdgeSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("host", "groupId", "edgeNodeId"),
    "properties" to linkedMapOf(
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "host" to mapOf("type" to "string", "minLength" to 1),
        "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535, "default" to 1883),
        "protocol" to mapOf("type" to "string", "enum" to listOf("MQTT_3_1_1", "MQTT_5"), "default" to "MQTT_3_1_1"),
        "clientId" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 128),
        "keepAliveSeconds" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 65535, "default" to 30),
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
        "groupId" to mapOf("type" to "string", "minLength" to 1, "pattern" to ID_PATTERN),
        "edgeNodeId" to mapOf("type" to "string", "minLength" to 1, "pattern" to ID_PATTERN),
        "primaryHostId" to mapOf(
            "type" to "string",
            "pattern" to ID_PATTERN,
            "description" to "Publish births only while spBv1.0/STATE/<id> reports online.",
        ),
        "nodeMetrics" to mapOf("type" to "array", "items" to metricSchema(), "default" to emptyList<Any>()),
        "devices" to mapOf(
            "type" to "array",
            "default" to emptyList<Any>(),
            "items" to linkedMapOf(
                "type" to "object",
                "required" to listOf("id"),
                "additionalProperties" to false,
                "properties" to linkedMapOf(
                    "id" to mapOf("type" to "string", "minLength" to 1, "pattern" to ID_PATTERN),
                    "metrics" to mapOf("type" to "array", "items" to metricSchema(), "default" to emptyList<Any>()),
                ),
            ),
        ),
        "publishPeriodMs" to mapOf(
            "type" to "integer",
            "minimum" to 1,
            "maximum" to 60000,
            "default" to 20,
            "description" to "How often queued tag changes are sent as NDATA and DDATA.",
        ),
        "reconnectInitialMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 500),
        "reconnectMaxMs" to mapOf("type" to "integer", "minimum" to 1, "default" to 30000),
        "connectTimeoutMs" to mapOf("type" to "integer", "minimum" to 100, "maximum" to 120000, "default" to 5000),
    ),
)

fun sparkplugEdgeConfig(resourceName: String, spec: Map<String, Any?>): SparkplugEdgeConfig {
    val protocol = when (spec["protocol"] as? String ?: "MQTT_3_1_1") {
        "MQTT_3_1_1" -> MqttProtocol.MQTT_3_1_1
        "MQTT_5" -> MqttProtocol.MQTT_5
        else -> throw IllegalArgumentException("unknown protocol '${spec["protocol"]}'")
    }
    val username = (spec["username"] as? String)?.takeIf { it.isNotBlank() }
    val password = (spec["password"] as? String)?.takeIf { it.isNotBlank() }
    if (password != null && username == null) throw IllegalArgumentException("mqtt password requires a username")
    val trustPassword = (spec["tlsTrustStorePassword"] as? String)?.takeIf { it.isNotBlank() }
    listOfNotNull(username, password, trustPassword).forEach {
        if (!Regex(SECRET_PATTERN).matches(it)) throw IllegalArgumentException("mqtt secret field must be a secret:// reference")
    }
    val groupId = id(spec, "groupId") ?: throw IllegalArgumentException("groupId is required")
    val edgeNodeId = id(spec, "edgeNodeId") ?: throw IllegalArgumentException("edgeNodeId is required")
    val primary = id(spec, "primaryHostId")
    val nodeMetrics = metrics(spec["nodeMetrics"])
    val reserved = nodeMetrics.firstOrNull { it.name == BD_SEQ || it.name.startsWith("Node Control/") }
    if (reserved != null) throw IllegalArgumentException("metric name '${reserved.name}' is reserved")
    unique(nodeMetrics, "node")
    val devices = (spec["devices"] as? List<*>).orEmpty().map { raw ->
        val device = raw as? Map<*, *> ?: throw IllegalArgumentException("device must be an object")
        val id = (device["id"] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("device id is required")
        if (!Regex(ID_PATTERN).matches(id)) throw IllegalArgumentException("device id '$id' is not a topic level")
        val metrics = metrics(device["metrics"])
        unique(metrics, "device $id")
        EdgeDeviceConfig(id, metrics)
    }
    if (devices.map { it.id }.toSet().size != devices.size) throw IllegalArgumentException("device ids must be unique")
    val clientId = (spec["clientId"] as? String)?.takeIf { it.isNotBlank() } ?: "oip-edge-$resourceName"
    if (clientId.length !in 1..128) throw IllegalArgumentException("clientId length")
    return SparkplugEdgeConfig(
        name = resourceName,
        host = (spec["host"] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("host is required"),
        port = (spec["port"] as? Number)?.toInt() ?: 1883,
        protocol = protocol,
        clientId = clientId,
        keepAliveSeconds = (spec["keepAliveSeconds"] as? Number)?.toInt() ?: 30,
        usernameRef = username,
        passwordRef = password,
        tls = spec["tls"] as? Boolean ?: false,
        insecureTrustAllCertificates = spec["insecureTrustAllCertificates"] as? Boolean ?: false,
        tlsTrustStore = (spec["tlsTrustStore"] as? String)?.takeIf { it.isNotBlank() },
        tlsTrustStorePasswordRef = trustPassword,
        groupId = groupId,
        edgeNodeId = edgeNodeId,
        primaryHostId = primary,
        nodeMetrics = nodeMetrics,
        devices = devices,
        publishPeriodMs = (spec["publishPeriodMs"] as? Number)?.toLong() ?: 20,
        reconnectInitialMs = (spec["reconnectInitialMs"] as? Number)?.toLong() ?: 500,
        reconnectMaxMs = (spec["reconnectMaxMs"] as? Number)?.toLong() ?: 30_000,
        connectTimeoutMs = (spec["connectTimeoutMs"] as? Number)?.toInt() ?: 5_000,
    )
}

private fun id(spec: Map<String, Any?>, key: String): String? {
    val value = (spec[key] as? String)?.takeIf { it.isNotBlank() } ?: return null
    if (!Regex(ID_PATTERN).matches(value)) throw IllegalArgumentException("$key '$value' is not a topic level")
    return value
}

private fun metrics(raw: Any?): List<EdgeMetricConfig> = (raw as? List<*>).orEmpty().map { item ->
    val metric = item as? Map<*, *> ?: throw IllegalArgumentException("metric must be an object")
    val name = (metric["name"] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("metric name is required")
    if (!Regex(METRIC_PATTERN).matches(name)) throw IllegalArgumentException("metric name '$name' contains a wildcard")
    val tag = (metric["tag"] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("metric $name needs a tag")
    val dataType = (metric["dataType"] as? String)?.takeIf { it.isNotBlank() }
    if (dataType != null && dataType !in EDGE_DATATYPES) throw IllegalArgumentException("metric $name dataType '$dataType'")
    EdgeMetricConfig(name, tag, dataType, metric["writable"] as? Boolean ?: false)
}

private fun unique(metrics: List<EdgeMetricConfig>, scope: String) {
    if (metrics.map { it.name }.toSet().size != metrics.size) throw IllegalArgumentException("$scope metric names must be unique")
}
