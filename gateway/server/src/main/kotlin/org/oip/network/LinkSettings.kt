package org.oip.network

/**
 * Gateway network listener and ping settings.
 *
 * Outgoing connections use host, port, and an enabled flag. Ping rate, ping timeout, and missed
 * pings are the liveness settings from the public connections page. The listen port default is 8060.
 */
data class LinkSettings(
    val enabled: Boolean = true,
    val port: Int = DEFAULT_PORT,
    val pingRateMillis: Long = 1_000,
    val pingTimeoutMillis: Long = 300,
    val missedPings: Int = 30,
    val backoffInitialMillis: Long = 200,
    val backoffCapMillis: Long = 5_000,
    val gatewayName: String = "gateway",
) {
    init {
        require(port in 0..65535) { "gatewayNetwork.port out of range: $port" }
        require(pingRateMillis in 1..600_000) { "gatewayNetwork.pingRateMillis out of range" }
        require(pingTimeoutMillis in 1..600_000) { "gatewayNetwork.pingTimeoutMillis out of range" }
        require(missedPings in 1..10_000) { "gatewayNetwork.missedPings out of range" }
        require(backoffInitialMillis in 1..600_000) { "gatewayNetwork.backoffInitialMillis out of range" }
        require(backoffCapMillis in backoffInitialMillis..600_000) { "gatewayNetwork.backoffCapMillis out of range" }
        require(GATEWAY_NAME.matches(gatewayName)) { "gatewayNetwork.name must be a short token" }
    }

    companion object {
        const val DEFAULT_PORT = 8060
        val KEYS = setOf(
            "enabled", "port", "pingRateMillis", "pingTimeoutMillis", "missedPings",
            "backoffInitialMillis", "backoffCapMillis", "name",
        )
        private val GATEWAY_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,62}""")

        fun parse(raw: Map<String, Any?>): LinkSettings {
            val unknown = raw.keys - KEYS
            require(unknown.isEmpty()) { "unknown gateway.yaml gatewayNetwork keys: $unknown" }
            return LinkSettings(
                enabled = bool(raw["enabled"], "gatewayNetwork.enabled") ?: true,
                port = number(raw["port"], "gatewayNetwork.port") ?: DEFAULT_PORT,
                pingRateMillis = long(raw["pingRateMillis"], "gatewayNetwork.pingRateMillis") ?: 1_000,
                pingTimeoutMillis = long(raw["pingTimeoutMillis"], "gatewayNetwork.pingTimeoutMillis") ?: 300,
                missedPings = number(raw["missedPings"], "gatewayNetwork.missedPings") ?: 30,
                backoffInitialMillis = long(raw["backoffInitialMillis"], "gatewayNetwork.backoffInitialMillis") ?: 200,
                backoffCapMillis = long(raw["backoffCapMillis"], "gatewayNetwork.backoffCapMillis") ?: 5_000,
                gatewayName = string(raw["name"], "gatewayNetwork.name") ?: "gateway",
            )
        }

        private fun bool(value: Any?, what: String): Boolean? = when (value) {
            null -> null
            is Boolean -> value
            else -> throw IllegalArgumentException("$what must be true or false")
        }

        private fun number(value: Any?, what: String): Int? = when (value) {
            null -> null
            is Number -> value.toInt()
            else -> throw IllegalArgumentException("$what must be a number")
        }

        private fun long(value: Any?, what: String): Long? = when (value) {
            null -> null
            is Number -> value.toLong()
            else -> throw IllegalArgumentException("$what must be a number")
        }

        private fun string(value: Any?, what: String): String? = when (value) {
            null -> null
            is String -> value
            else -> throw IllegalArgumentException("$what must be a string")
        }
    }
}

/** One outgoing connection. Host and port identify the remote listener. */
data class OutgoingSpec(
    val name: String,
    val host: String,
    val port: Int,
    val enabled: Boolean = true,
    val description: String = "",
)

/** A local provider whose values are read from a remote gateway. */
data class RemoteProviderSpec(
    val name: String,
    val connection: String,
    val provider: String = "default",
    val enabled: Boolean = true,
)

/**
 * Whether one peer may use one remote service.
 * `peer` is a certificate fingerprint, a remote gateway name, or `*`.
 */
data class ServicePolicy(
    val peer: String,
    val service: String,
    val access: String,
    val roles: List<String> = emptyList(),
)

data class ServiceAccess(
    val allowed: Boolean,
    val roles: Set<String>,
    /**
     * True only for access `allow`.
     * Edge sync uses this as permission to store forwarded rows.
     * [canWrite] is the same bit for remote alarm acknowledge and shelve.
     */
    val storage: Boolean = false,
    val canWrite: Boolean = false,
)

/** Edge sync pushes. `allow` is query and storage. `read` is query only. */
const val SERVICE_HISTORY = "history"
const val SERVICE_AUDIT = "audit"
const val SERVICE_ALARM_JOURNAL = "alarm-journal"

internal const val DIR_INCOMING = "incoming"
internal const val DIR_OUTGOING = "outgoing"
internal const val STATE_PENDING = "pending"
internal const val STATE_APPROVED = "approved"
internal const val STATE_DENIED = "denied"
internal const val STATUS_RUNNING = "running"
internal const val STATUS_FAULTED = "faulted"
internal const val STATUS_CONNECTING = "connecting"
internal const val STATUS_DISABLED = "disabled"
internal const val SERVICE_TAGS = "tags"
internal const val SERVICE_ALARMS = "alarms"
internal const val SERVICE_TASKS = "tasks"
internal const val SERVICE_NOTIFICATIONS = "notifications"

/** Alarm calls carry [org.oip.alarm.RemoteCaller] from this protocol version on. */
internal const val PROTOCOL_CALLER = 2
/** The protocol this gateway advertises. */
internal const val PROTOCOL_VERSION = PROTOCOL_CALLER
