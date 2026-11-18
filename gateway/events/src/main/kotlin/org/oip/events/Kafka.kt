package org.oip.events

/**
 * Kafka connections, and the narrow client interface the Kafka source and handler use.
 *
 * A connection is a config resource (module `kafka`, kind `KafkaConnection`). Sources and handlers
 * name it. The password is a `secret://` reference, resolved only when a client is created, and
 * never logged or returned.
 *
 */
const val KAFKA_MODULE = "kafka"
const val KAFKA_CONNECTION_KIND = "KafkaConnection"

data class KafkaConnection(
    val name: String,
    val enabled: Boolean,
    val bootstrapServers: String,
    val clientId: String,
    val securityProtocol: String,
    val saslMechanism: String?,
    val username: String?,
    /** `secret://name`, never the plaintext. */
    val passwordRef: String?,
    val properties: Map<String, String>,
)

/** One consumed record. [key] and [value] are the raw bytes; headers are decoded as UTF-8. */
data class KafkaRecord(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val timestamp: Long,
    val key: ByteArray?,
    val value: ByteArray?,
    val headers: Map<String, String>,
)

/** One record to produce. A null [partition] or [timestamp] leaves the choice to the client. */
data class KafkaOutgoing(
    val topic: String,
    val partition: Int?,
    val timestamp: Long?,
    val key: ByteArray?,
    val value: ByteArray,
    val headers: Map<String, ByteArray>,
)

interface KafkaConsumerClient : AutoCloseable {
    /** Consumer-group subscription: the group decides partitions, and offsets are committed for the group. */
    fun subscribe(topics: List<String>)

    /** Read one partition directly, starting at [offset] (null: the reset policy decides). */
    fun assign(topic: String, partition: Int, offset: Long?)

    fun poll(timeoutMillis: Long): List<KafkaRecord>

    /** Commit, for the consumer group, the next offset to read per (topic, partition). Blocks until done. */
    fun commit(offsets: Map<Pair<String, Int>, Long>)

    /** Interrupts a blocked [poll] from another thread. */
    fun wakeup()
}

interface KafkaProducerClient : AutoCloseable {
    /** Blocks until the broker acknowledges, or throws. */
    fun send(record: KafkaOutgoing, timeoutMillis: Long)
}

/** Creates clients. The gateway uses [ApacheKafkaClients]; tests substitute fakes. */
interface KafkaClients {
    fun consumer(connection: KafkaConnection, properties: Map<String, String>): KafkaConsumerClient
    fun producer(connection: KafkaConnection, properties: Map<String, String>): KafkaProducerClient

    companion object {
        val NONE: KafkaClients = object : KafkaClients {
            override fun consumer(connection: KafkaConnection, properties: Map<String, String>): KafkaConsumerClient =
                throw IllegalStateException("no kafka client")

            override fun producer(connection: KafkaConnection, properties: Map<String, String>): KafkaProducerClient =
                throw IllegalStateException("no kafka client")
        }
    }
}

internal val KAFKA_PROTOCOLS = listOf("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")
internal val KAFKA_MECHANISMS = listOf("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512")
private val SECRET_REF = Regex("^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/**
 * Client property keys whose values are credentials. They may only hold a `secret://` reference,
 * which the client factory resolves when it creates the client.
 */
internal val SENSITIVE_PROPERTY = Regex("password$|secret|^sasl\\.jaas\\.config$|^ssl\\.keystore\\.key$")

internal fun isSecretReference(value: String): Boolean = SECRET_REF.matches(value)

/** Free-form client properties: any string, but credential keys must be secret references. */
internal fun clientPropertiesSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "patternProperties" to mapOf(SENSITIVE_PROPERTY.pattern to mapOf("type" to "string", "pattern" to SECRET_REF.pattern)),
    "additionalProperties" to mapOf("type" to "string"),
)

/** Rejects plaintext credentials and the keys the stream controls itself. */
internal fun checkClientProperties(owner: String, properties: Map<String, String>, reserved: Set<String> = emptySet()) {
    for ((key, value) in properties) {
        if (key in reserved) throw IllegalArgumentException("$owner: property '$key' is set by the event stream and cannot be overridden")
        if (SENSITIVE_PROPERTY.containsMatchIn(key) && !isSecretReference(value)) {
            throw IllegalArgumentException("$owner: property '$key' must be a secret reference")
        }
    }
}

internal fun kafkaConnectionSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("bootstrapServers"),
    "properties" to mapOf(
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "bootstrapServers" to mapOf("type" to "string", "minLength" to 1, "description" to "Comma-separated host:port list"),
        "clientId" to mapOf("type" to "string"),
        "securityProtocol" to mapOf("type" to "string", "enum" to KAFKA_PROTOCOLS, "default" to "PLAINTEXT"),
        "saslMechanism" to mapOf("type" to "string", "enum" to KAFKA_MECHANISMS),
        "username" to mapOf("type" to "string"),
        "password" to mapOf(
            "type" to "string",
            "pattern" to SECRET_REF.pattern,
            "description" to "Reference to a gateway secret (secret://name)",
        ),
        "properties" to clientPropertiesSchema(),
    ),
)

internal fun compileKafkaConnection(name: String, spec: Map<String, Any?>): KafkaConnection {
    val servers = (spec["bootstrapServers"] as? String)?.trim().orEmpty()
    if (servers.isEmpty()) throw IllegalArgumentException("kafka connection '$name' needs bootstrapServers")
    val protocol = (spec["securityProtocol"] as? String ?: "PLAINTEXT").uppercase()
    if (protocol !in KAFKA_PROTOCOLS) throw IllegalArgumentException("kafka connection '$name' security protocol '$protocol' is not supported")
    val mechanism = (spec["saslMechanism"] as? String)?.uppercase()
    if (mechanism != null && mechanism !in KAFKA_MECHANISMS) {
        throw IllegalArgumentException("kafka connection '$name' SASL mechanism '$mechanism' is not supported")
    }
    if (protocol.startsWith("SASL") && mechanism == null) {
        throw IllegalArgumentException("kafka connection '$name' needs a SASL mechanism for $protocol")
    }
    val password = spec["password"] as? String
    if (password != null && !SECRET_REF.matches(password)) {
        throw IllegalArgumentException("kafka connection '$name' password must be a secret reference")
    }
    return KafkaConnection(
        name = name,
        enabled = spec["enabled"] as? Boolean ?: true,
        bootstrapServers = servers,
        clientId = spec["clientId"] as? String ?: "oip-$name",
        securityProtocol = protocol,
        saslMechanism = mechanism,
        username = spec["username"] as? String,
        passwordRef = password,
        properties = stringMap(spec["properties"]).also { checkClientProperties("kafka connection '$name'", it) },
    )
}

internal fun stringMap(raw: Any?): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (raw is Map<*, *>) for ((key, value) in raw) out[key.toString()] = value?.toString() ?: ""
    return out
}
