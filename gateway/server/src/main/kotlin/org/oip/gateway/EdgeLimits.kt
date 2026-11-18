package org.oip.gateway

import org.oip.config.FileConfigStore
import org.oip.config.Scope
import org.oip.logix.DEVICE_KIND as LOGIX_DEVICE
import org.oip.logix.LogixDriverModule
import org.oip.modbus.DEVICE_KIND as MODBUS_DEVICE
import org.oip.modbus.ModbusDriverModule
import org.oip.mqtt.CONNECTION_KIND as MQTT_CONNECTION
import org.oip.mqtt.MqttDriverModule
import org.oip.opcua.CONNECTION_KIND as OPC_CONNECTION
import org.oip.opcua.OpcUaClientModule
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local resource caps for a reduced gateway (the edge profile). Doc source: FEATURES GAT-004.
 *
 * `limits` in gateway.yaml (the edge profile sets defaults; 0 means no cap):
 *  - `maxDevices`: device and OPC UA connections together (Modbus, Logix, MQTT, OPC UA client).
 *  - `maxStorageMb`: size of the data directory. Creating a new configuration resource is refused with 507 once the
 *    directory is at or over the cap, so a small field device cannot be filled by configuration. Existing resources
 *    stay editable and deletes always work, so an operator can free space.
 */
data class EdgeLimits(val maxDevices: Int = 0, val maxStorageMb: Int = 0) {
    val any: Boolean get() = maxDevices > 0 || maxStorageMb > 0

    companion object {
        private val KEYS = setOf("maxDevices", "maxStorageMb")

        fun parse(map: Map<String, Any?>, what: String = "limits"): EdgeLimits {
            val unknown = map.keys - KEYS
            require(unknown.isEmpty()) { "unknown $what keys: $unknown" }
            fun cap(key: String): Int = when (val v = map[key]) {
                null -> 0
                is Int -> v.also { require(it in 0..10_000_000) { "$what.$key out of range: $it" } }
                else -> throw IllegalArgumentException("$what.$key must be a whole number")
            }
            return EdgeLimits(cap("maxDevices"), cap("maxStorageMb"))
        }

        val DEVICE_KINDS: Map<String, String> = mapOf(
            ModbusDriverModule.MODULE_ID to MODBUS_DEVICE,
            LogixDriverModule.MODULE_ID to LOGIX_DEVICE,
            MqttDriverModule.MODULE_ID to MQTT_CONNECTION,
            OpcUaClientModule.MODULE_ID to OPC_CONNECTION,
        )
    }
}

/** Enforces [EdgeLimits] when configuration resources are created. */
class LimitGuard(private val limits: EdgeLimits, private val store: FileConfigStore, private val dataDir: Path) {
    private val sizeCache = java.util.concurrent.atomic.AtomicReference<Pair<Long, Long>?>(null)

    fun deviceCount(): Int = EdgeLimits.DEVICE_KINDS.entries.sumOf { (module, kind) -> store.list(module, Scope.GATEWAY, kind).size }

    fun storageBytes(): Long {
        val now = System.currentTimeMillis()
        sizeCache.get()?.let { (at, bytes) -> if (now - at < 2_000) return bytes }
        var total = 0L
        Files.walk(dataDir).use { walk -> walk.filter { Files.isRegularFile(it) }.forEach { total += runCatching { Files.size(it) }.getOrDefault(0L) } }
        sizeCache.set(now to total)
        return total
    }

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "maxDevices" to limits.maxDevices,
        "devices" to deviceCount(),
        "maxStorageMb" to limits.maxStorageMb,
        "storageMb" to storageBytes() / (1024 * 1024),
    )

    /** Throws when creating [count] new resources of this kind would break a cap. */
    fun checkCreate(module: String, kind: String, count: Int = 1) {
        if (!limits.any || count <= 0) return
        if (limits.maxDevices > 0 && EdgeLimits.DEVICE_KINDS[module] == kind) {
            val have = deviceCount()
            if (have + count > limits.maxDevices) {
                throw ProblemException(
                    409, "limit-reached", "Limit Reached",
                    "This gateway profile allows ${limits.maxDevices} device connections and $have exist (limits.maxDevices).",
                )
            }
        }
        if (limits.maxStorageMb > 0 && storageBytes() >= limits.maxStorageMb.toLong() * 1024 * 1024) {
            throw ProblemException(
                507, "storage-limit", "Insufficient Storage",
                "Local storage is at the ${limits.maxStorageMb} MB cap (limits.maxStorageMb). Delete data or raise the cap.",
            )
        }
    }
}
