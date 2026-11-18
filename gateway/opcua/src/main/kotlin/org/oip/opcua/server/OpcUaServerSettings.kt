package org.oip.opcua.server

/**
 * OPC UA server listener settings from `gateway.yaml` key `opcua`.
 *
 * the reference platform's server settings page documents bind port 62541 and bind address localhost
 * (-).
 * This platform's port default is 4840 (SPEC §10). The bind address stays on loopback until an
 * operator sets a reachable address, matching that page's localhost default.
 */
data class OpcUaServerSettings(
    val enabled: Boolean = true,
    val bindAddress: String = "127.0.0.1",
    val port: Int = 4840,
    val hostname: String = "localhost",
) {
    companion object {
        val KEYS = setOf("enabled", "bindAddress", "port", "hostname")

        fun parse(raw: Map<String, Any?>, env: Map<String, String> = emptyMap()): OpcUaServerSettings {
            val unknown = raw.keys - KEYS
            require(unknown.isEmpty()) { "unknown gateway.yaml opcua keys: $unknown" }
            var enabled = bool(raw["enabled"], "opcua.enabled") ?: true
            var bind = string(raw["bindAddress"], "opcua.bindAddress") ?: "127.0.0.1"
            var port = number(raw["port"], "opcua.port") ?: 4840
            var hostname = string(raw["hostname"], "opcua.hostname") ?: "localhost"
            envBool(env, "OIP_OPCUA_ENABLED")?.let { enabled = it }
            env["OIP_OPCUA_BIND"]?.let { bind = it }
            env["OIP_OPCUA_PORT"]?.let {
                port = it.toIntOrNull() ?: throw IllegalArgumentException("OIP_OPCUA_PORT must be a number")
            }
            env["OIP_OPCUA_HOSTNAME"]?.let { hostname = it }
            require(bind.isNotBlank()) { "opcua.bindAddress is blank" }
            require(hostname.isNotBlank()) { "opcua.hostname is blank" }
            require(port in 0..65535) { "opcua.port out of range: $port" }
            return OpcUaServerSettings(enabled, bind, port, hostname)
        }

        private fun bool(v: Any?, what: String): Boolean? = when (v) {
            null -> null
            is Boolean -> v
            else -> throw IllegalArgumentException("$what must be true or false")
        }

        private fun string(v: Any?, what: String): String? = when (v) {
            null -> null
            is String -> v
            else -> throw IllegalArgumentException("$what must be a string")
        }

        private fun number(v: Any?, what: String): Int? = when (v) {
            null -> null
            is Number -> v.toInt()
            else -> throw IllegalArgumentException("$what must be a number")
        }

        private fun envBool(env: Map<String, String>, key: String): Boolean? {
            val raw = env[key] ?: return null
            return when (raw) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("$key must be true or false")
            }
        }
    }
}
