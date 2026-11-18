package org.oip.gateway

import io.javalin.Javalin
import org.oip.config.Scope
import org.oip.logix.DEVICE_KIND as LOGIX_DEVICE
import org.oip.logix.LogixDriverModule
import org.oip.modbus.DEVICE_KIND
import org.oip.modbus.ModbusDriverModule
import org.oip.mqtt.CONNECTION_KIND as MQTT_CONNECTION
import org.oip.mqtt.MqttDriverModule
import org.oip.opcua.CONNECTION_KIND as OPC_CONNECTION
import org.oip.opcua.OpcUaClientModule
import java.lang.management.ManagementFactory
import java.time.ZoneId

/**
 * The Connections list: one document of every device, database, service and OPC connection this gateway holds,
 * each with its live status read from the source that already tracks it (the drivers, the database pools, the
 * peer network, edge sync and the OPC UA server). Nothing here opens a connection or probes a device.
 * Doc source: FEATURES GAT-022.
 *
 * `GET /api/v1/connections` returns `{items, summary}`. Each item has `id` (`kind:type:name`), `kind` (`device`,
 * `database`, `service` or `opc`), `type`, `name`, `status` (the source's own word), `health` (`ok`, `pending`,
 * `fault` or `disabled`), `detail`, and `section` (the admin section that edits it). A caller needs gateway.read.
 */
class ConnectionsApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get(PATH) { ctx -> ctx.contentType("application/json").result(Json.write(document())) }
    }

    fun document(): Map<String, Any?> {
        val items = ArrayList<Map<String, Any?>>()
        items += devices()
        items += opcConnections()
        items += databases()
        items += services()
        val summary = linkedMapOf<String, Any?>(
            "total" to items.size,
            "ok" to items.count { it["health"] == "ok" },
            "pending" to items.count { it["health"] == "pending" },
            "fault" to items.count { it["health"] == "fault" },
            "disabled" to items.count { it["health"] == "disabled" },
        )
        return linkedMapOf("items" to items, "summary" to summary)
    }

    private fun item(kind: String, type: String, name: String, status: String, health: String, detail: String, section: String) =
        linkedMapOf<String, Any?>(
            "id" to "$kind:$type:$name", "kind" to kind, "type" to type, "name" to name,
            "status" to status, "health" to health, "detail" to detail, "section" to section,
        )

    private fun title(enumName: String) =
        enumName.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun healthOf(connected: Boolean, state: String) = when {
        connected -> "ok"
        state.contains("CONNECTING") -> "pending"
        else -> "fault"
    }

    private fun devices(): List<Map<String, Any?>> {
        val rows = ArrayList<Map<String, Any?>>()
        (gw.host.instance(ModbusDriverModule.MODULE_ID) as? ModbusDriverModule).let { module ->
            for (stored in gw.store.list(ModbusDriverModule.MODULE_ID, Scope.GATEWAY, DEVICE_KIND)) {
                val name = stored.id.name
                val enabled = stored.resource.spec["enabled"] as? Boolean ?: true
                val status = if (enabled) module?.device(name)?.connectionStatus() else null
                rows += deviceRow("modbus", name, enabled, status?.state?.name, status?.connected == true, status?.lastError, stored.resource.spec)
            }
        }
        (gw.host.instance(LogixDriverModule.MODULE_ID) as? LogixDriverModule).let { module ->
            for (stored in gw.store.list(LogixDriverModule.MODULE_ID, Scope.GATEWAY, LOGIX_DEVICE)) {
                val name = stored.id.name
                val enabled = stored.resource.spec["enabled"] as? Boolean ?: true
                val status = if (enabled) module?.device(name)?.connectionStatus() else null
                rows += deviceRow("logix", name, enabled, status?.state?.name, status?.connected == true, status?.lastError, stored.resource.spec)
            }
        }
        (gw.host.instance(MqttDriverModule.MODULE_ID) as? MqttDriverModule).let { module ->
            for (stored in gw.store.list(MqttDriverModule.MODULE_ID, Scope.GATEWAY, MQTT_CONNECTION)) {
                val name = stored.id.name
                val enabled = stored.resource.spec["enabled"] as? Boolean ?: true
                val connection = if (enabled) module?.connection(name) else null
                val state = when {
                    !enabled -> null
                    connection == null -> null
                    connection.connected() -> "CONNECTED"
                    else -> "DISCONNECTED"
                }
                rows += deviceRow("mqtt", name, enabled, state, connection?.connected() == true, null, stored.resource.spec)
            }
        }
        return rows
    }

    private fun deviceRow(
        type: String, name: String, enabled: Boolean, state: String?, connected: Boolean, error: String?, spec: Map<String, Any?>,
    ): Map<String, Any?> {
        val description = spec["description"] as? String ?: ""
        val (status, health) = when {
            !enabled -> "Disabled" to "disabled"
            state == null -> "Not Started" to "pending"
            else -> title(state) to healthOf(connected, state)
        }
        val detail = error?.takeIf { it.isNotBlank() } ?: description
        return item("device", type, name, status, health, detail, "config")
    }

    private fun opcConnections(): List<Map<String, Any?>> {
        val module = gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule
        return gw.store.list(OpcUaClientModule.MODULE_ID, Scope.GATEWAY, OPC_CONNECTION).map { stored ->
            val name = stored.id.name
            val spec = stored.resource.spec
            val enabled = spec["enabled"] as? Boolean ?: true
            val status = if (enabled) module?.connection(name)?.connectionStatus() else null
            val (text, health) = when {
                !enabled -> "Disabled" to "disabled"
                status == null -> "Not Started" to "pending"
                else -> title(status.state.name) to healthOf(status.connected, status.state.name)
            }
            item("opc", "opcua-client", name, text, health, status?.lastError?.takeIf { it.isNotBlank() } ?: (spec["endpointUrl"] as? String ?: ""), "opc-connections")
        }
    }

    private fun databases(): List<Map<String, Any?>> = gw.databases.connections().map { view ->
        val name = view["name"] as String
        val enabled = view["enabled"] as? Boolean ?: false
        val status = view["status"] as? String ?: "Faulted"
        val health = when {
            !enabled -> "disabled"
            status == "Valid" -> "ok"
            else -> "fault"
        }
        val detail = (view["error"] as? String)?.takeIf { it.isNotBlank() } ?: (view["driver"] as? String ?: "")
        item("database", "sql", name, status, health, detail, "databases")
    }

    private fun services(): List<Map<String, Any?>> {
        val rows = ArrayList<Map<String, Any?>>()
        val network = gw.network.statusDocument()
        @Suppress("UNCHECKED_CAST")
        for (peer in (network["outgoing"] as? List<Map<String, Any?>>).orEmpty()) {
            val status = peer["status"] as? String ?: "unknown"
            val health = when (status) {
                "running" -> "ok"
                "connecting" -> "pending"
                "disabled" -> "disabled"
                else -> "fault"
            }
            rows += item("service", "gateway-network", peer["name"] as String, title(status), health,
                (peer["lastError"] as? String)?.takeIf { it.isNotBlank() } ?: "${peer["host"]}:${peer["port"]}", "network")
        }
        val opcEnabled = gw.settings.opcua.enabled
        rows += item(
            "service", "opcua-server", "OPC UA Server",
            when {
                !opcEnabled -> "Disabled"
                gw.opcUa?.running == true -> "Running"
                else -> "Stopped"
            },
            when {
                !opcEnabled -> "disabled"
                gw.opcUa?.running == true -> "ok"
                else -> "fault"
            },
            "", "opcua",
        )
        val sync = gw.settings.edgeSync
        if (sync.remoteGateway.isNotBlank()) {
            @Suppress("UNCHECKED_CAST")
            val up = gw.edgeSync.statusDocument()["linkUp"] == true
            rows += item("service", "edge-sync", sync.remoteGateway, if (up) "Link up" else "Link down", if (up) "ok" else "fault",
                "Upstream gateway", "architectures")
        }
        return rows
    }

    companion object {
        const val PATH = "/api/v1/connections"
    }
}

/**
 * Environment facts for the Diagnostics Environment page: runtime, operating system, profile and data directory.
 * Read-only; no secrets and no environment variable values. Doc source: FEATURES GAT-021.
 */
internal fun Gateway.environmentDocument(): Map<String, Any?> {
    val runtime = ManagementFactory.getRuntimeMXBean()
    val os = ManagementFactory.getOperatingSystemMXBean()
    return linkedMapOf(
        "gatewayVersion" to Gateway.VERSION,
        "profile" to settings.profile,
        "javaVersion" to System.getProperty("java.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "os" to "${os.name} ${os.version}",
        "architecture" to os.arch,
        "processors" to os.availableProcessors,
        "timeZone" to ZoneId.systemDefault().id,
        "dataDirectory" to dataDir.toAbsolutePath().toString(),
        "startedAt" to startedAt?.toString(),
        "uptimeMillis" to runtime.uptime,
        "deploymentMode" to store.activeMode(),
        "configResources" to diagnostics.config(),
        "limits" to limitGuard.snapshot(),
    )
}
