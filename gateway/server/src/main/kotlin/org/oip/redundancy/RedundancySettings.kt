package org.oip.redundancy

/**
 * Node-local redundancy settings from `gateway.yaml`. These are not copied to the peer.
 *
 */
data class RedundancySettings(
    val mode: NodeRole = NodeRole.INDEPENDENT,
    val standbyActivityLevel: StandbyLevel = StandbyLevel.WARM,
    val startupAllowanceMillis: Long = 30_000,
    val syncTimeoutSeconds: Long = 60,
    val recoveryMode: RecoveryMode = RecoveryMode.AUTOMATIC,
    val masterHost: String = "127.0.0.1",
    val masterPort: Int = 8060,
    val pingRateMillis: Long = 1_000,
    val pingTimeoutMillis: Long = 300,
    val pingMaxMissed: Int = 30,
    val historyMode: HistoryMode = HistoryMode.PARTIAL,
    val useActiveUptime: Boolean = false,
    val connectionName: String = "redundancy",
) {
    fun state(): RedundancyState {
        val activeNow = mode == NodeRole.INDEPENDENT || (mode == NodeRole.MASTER && startupAllowanceMillis == 0L)
        return RedundancyState(
            role = mode,
            activity = if (activeNow) ActivityLevel.ACTIVE else ActivityLevel.UNDECIDED,
            standby = standbyActivityLevel,
            recovery = recoveryMode,
            pingMaxMissed = pingMaxMissed,
            becameActiveWithoutPeer = mode == NodeRole.MASTER && startupAllowanceMillis == 0L,
        )
    }

    companion object {
        val KEYS = setOf(
            "mode",
            "standbyActivityLevel",
            "startupAllowanceMillis",
            "syncTimeoutSeconds",
            "recoveryMode",
            "masterHost",
            "masterPort",
            "pingRateMillis",
            "pingTimeoutMillis",
            "pingMaxMissed",
            "historyMode",
            "useActiveUptime",
            "connectionName",
        )
        private val NAME = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?""")
        private val HOST = Regex("""[A-Za-z0-9._-]{1,253}""")

        fun parse(raw: Map<String, Any?>): RedundancySettings {
            val unknown = raw.keys - KEYS
            require(unknown.isEmpty()) { "unknown redundancy keys: $unknown" }
            val mode = NodeRole.fromWire(text(raw["mode"], "redundancy.mode") ?: "independent")
            val standby = StandbyLevel.fromWire(text(raw["standbyActivityLevel"], "redundancy.standbyActivityLevel"))
            val recovery = RecoveryMode.fromWire(text(raw["recoveryMode"], "redundancy.recoveryMode"))
            val history = HistoryMode.fromWire(text(raw["historyMode"], "redundancy.historyMode"))
            val host = text(raw["masterHost"], "redundancy.masterHost") ?: "127.0.0.1"
            require(HOST.matches(host)) { "redundancy.masterHost must be a hostname or address" }
            val connection = text(raw["connectionName"], "redundancy.connectionName") ?: "redundancy"
            require(NAME.matches(connection)) { "redundancy.connectionName must be a lowercase token" }
            val port = number(raw, "masterPort", 8060, 1, 65535)
            return RedundancySettings(
                mode = mode,
                standbyActivityLevel = standby,
                startupAllowanceMillis = number(raw, "startupAllowanceMillis", 30_000, 0, 3_600_000).toLong(),
                syncTimeoutSeconds = number(raw, "syncTimeoutSeconds", 60, 1, 3_600).toLong(),
                recoveryMode = recovery,
                masterHost = host,
                masterPort = port,
                pingRateMillis = number(raw, "pingRateMillis", 1_000, 10, 60_000).toLong(),
                pingTimeoutMillis = number(raw, "pingTimeoutMillis", 300, 10, 60_000).toLong(),
                pingMaxMissed = number(raw, "pingMaxMissed", 30, 1, 1_000),
                historyMode = history,
                useActiveUptime = bool(raw["useActiveUptime"], "redundancy.useActiveUptime") ?: false,
                connectionName = connection,
            )
        }

        private fun text(value: Any?, what: String): String? = when (value) {
            null -> null
            is String -> value.trim().ifEmpty { null }
            else -> throw IllegalArgumentException("$what must be a string")
        }

        private fun bool(value: Any?, what: String): Boolean? = when (value) {
            null -> null
            is Boolean -> value
            else -> throw IllegalArgumentException("$what must be a boolean")
        }

        private fun number(raw: Map<String, Any?>, key: String, default: Int, min: Int, max: Int): Int {
            val value = raw[key] ?: return default
            val parsed = (value as? Number)?.toLong() ?: throw IllegalArgumentException("redundancy.$key must be a number")
            require(parsed in min.toLong()..max.toLong()) { "redundancy.$key out of range" }
            return parsed.toInt()
        }
    }
}
