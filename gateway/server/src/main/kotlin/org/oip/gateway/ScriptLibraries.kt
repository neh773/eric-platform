package org.oip.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.historian.HistoryAggregate
import org.oip.historian.HistoryAggregator
import org.oip.historian.HistoryQuery
import org.oip.historian.HistorySample
import org.oip.historian.normalizeTagPath
import org.oip.modbus.DEVICE_KIND
import org.oip.modbus.ModbusDriverModule
import org.oip.opcua.CONNECTION_KIND
import org.oip.opcua.OpcUaClientModule
import org.oip.scripting.ScriptHost
import org.oip.tags.Quality
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Gateway implementations of the system libraries that need live services: history, devices, OPC, and users.
 * Config itself is answered by the script service from the config store.
 */
internal object ScriptLibraries {
    private val json = ObjectMapper()

    fun attach(host: ScriptHost, gw: Gateway) {
        val notes = HistoryNotes(gw.dataDir.resolve("history-notes.json"))
        host.historyQueryFn = { path, from, to, aggregate, interval -> query(gw, path, from, to, aggregate, interval) }
        host.historyStoreFn = { path, value, timestamp ->
            val sample = HistorySample(normalizeTagPath(path), timestamp, parseValue(value), Quality.GOOD)
            gw.historyRouter.appendAll(listOf(sample))
        }
        host.historyAnnotateFn = { path, timestamp, note -> notes.add(normalizeTagPath(path), timestamp, note) }
        host.historyNotesFn = { path -> json.writeValueAsString(notes.list(normalizeTagPath(path))) }
        host.historyBrowseFn = { json.writeValueAsString(gw.historyRouter.latestAll().keys.sorted()) }
        host.deviceListFn = { json.writeValueAsString(devices(gw)) }
        host.deviceEnableFn = { name, enabled -> setEnabled(gw, name, enabled) }
        host.opcBrowseFn = { device, node -> browse(gw, device, node) }
        host.opcReadFn = { device, node -> read(gw, device, node) }
        host.opcWriteFn = { device, node, value -> write(gw, device, node, value) }
        host.opcuaListFn = { json.writeValueAsString(devices(gw).filter { it["driver"] == "opcua" }) }
        host.opcuaCallFn = { connection, objectId, method ->
            json.writeValueAsString(ScriptLibraryCalls.call(gw, "opcua.callMethod", listOf(connection, objectId, method, emptyList<Any?>()), "system"))
        }
        host.libraryFn = { function, args, actor -> ScriptLibraryCalls.call(gw, function, args, actor) }
        host.userListFn = {
            json.writeValueAsString(gw.security.users.list().map { user ->
                mapOf("username" to user.username, "displayName" to user.displayName, "enabled" to user.enabled, "roles" to user.roles.sorted())
            })
        }
        host.roleListFn = {
            json.writeValueAsString(gw.security.roles.list().map { role ->
                mapOf("name" to role.name, "description" to role.description, "permissions" to role.permissions.sorted())
            })
        }
    }

    private fun query(gw: Gateway, path: String, from: Long, to: Long, aggregate: String, interval: Long): String {
        val tag = normalizeTagPath(path)
        if (aggregate.isBlank()) {
            val rows = gw.historyRouter.query(HistoryQuery(tag, from, to))
            return json.writeValueAsString(rows.map { row ->
                mapOf("timestamp" to row.timestampMillis, "value" to row.value, "quality" to row.quality.code.name)
            })
        }
        val mode = HistoryAggregate.fromWire(aggregate)
        val windows = HistoryAggregator.aggregate(gw.historyRouter, tag, from, to, interval, mode)
        return json.writeValueAsString(windows.map { window ->
            mapOf("start" to window.startMillis, "end" to window.endMillis, "value" to window.value, "rows" to window.rows)
        })
    }

    private fun devices(gw: Gateway): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val modbus = gw.host.instance(ModbusDriverModule.MODULE_ID) as? ModbusDriverModule
        for (stored in gw.store.list(ModbusDriverModule.MODULE_ID, Scope.GATEWAY, DEVICE_KIND)) {
            val name = stored.id.name
            out += mapOf(
                "name" to name,
                "driver" to "modbus",
                "enabled" to (stored.resource.spec["enabled"] as? Boolean ?: true),
                "state" to (modbus?.device(name)?.connectionStatus()?.state?.name ?: "NOT_RUNNING"),
            )
        }
        val opc = gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule
        for (stored in gw.store.list(OpcUaClientModule.MODULE_ID, Scope.GATEWAY, CONNECTION_KIND)) {
            val name = stored.id.name
            out += mapOf(
                "name" to name,
                "driver" to "opcua",
                "enabled" to (stored.resource.spec["enabled"] as? Boolean ?: true),
                "state" to (opc?.connection(name)?.connectionStatus()?.state?.name ?: "NOT_RUNNING"),
            )
        }
        return out.sortedBy { it["name"] as String }
    }

    private fun setEnabled(gw: Gateway, name: String, enabled: Boolean) {
        val stored = gw.store.list(ModbusDriverModule.MODULE_ID, Scope.GATEWAY, DEVICE_KIND).find { it.id.name == name }
            ?: gw.store.list(OpcUaClientModule.MODULE_ID, Scope.GATEWAY, CONNECTION_KIND).find { it.id.name == name }
            ?: throw IllegalArgumentException("device not found")
        val spec = stored.resource.spec.toMutableMap()
        spec["enabled"] = enabled
        gw.store.put(
            stored.id.scope,
            Resource(stored.resource.kind, stored.resource.metadata.copy(version = stored.resource.metadata.version), spec),
            "script",
        )
    }

    private fun browse(gw: Gateway, name: String, nodeId: String): String {
        val modbus = (gw.host.instance(ModbusDriverModule.MODULE_ID) as? ModbusDriverModule)?.device(name)
        if (modbus != null) {
            return json.writeValueAsString(modbus.browse(nodeId).map { node ->
                mapOf("nodeId" to node.nodeId, "displayName" to node.displayName, "nodeClass" to node.nodeClass, "readable" to node.readable, "writable" to node.writable)
            })
        }
        val connection = (gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule)?.connection(name)
            ?: throw IllegalArgumentException("device not found")
        return json.writeValueAsString(connection.browse(nodeId).map { node ->
            mapOf("nodeId" to node.nodeId, "displayName" to node.displayName, "nodeClass" to node.nodeClass, "readable" to node.readable, "writable" to node.writable)
        })
    }

    private fun read(gw: Gateway, name: String, nodeId: String): String {
        val modbus = (gw.host.instance(ModbusDriverModule.MODULE_ID) as? ModbusDriverModule)?.device(name)
        if (modbus != null) {
            val sample = modbus.read(listOf(nodeId)).firstOrNull() ?: throw IllegalArgumentException("read returned nothing")
            return json.writeValueAsString(mapOf("value" to sample.value, "quality" to sample.quality.code.name))
        }
        val connection = (gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule)?.connection(name)
            ?: throw IllegalArgumentException("device not found")
        val sample = connection.read(listOf(nodeId)).firstOrNull() ?: throw IllegalArgumentException("read returned nothing")
        return json.writeValueAsString(mapOf("value" to sample.value, "quality" to sample.quality.code.name))
    }

    private fun write(gw: Gateway, name: String, nodeId: String, value: String): String {
        val parsed = parseValue(value)
        val modbus = (gw.host.instance(ModbusDriverModule.MODULE_ID) as? ModbusDriverModule)?.device(name)
        if (modbus != null) {
            return json.writeValueAsString(mapOf("quality" to modbus.write(nodeId, parsed).quality.code.name))
        }
        val connection = (gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule)?.connection(name)
            ?: throw IllegalArgumentException("device not found")
        return json.writeValueAsString(mapOf("quality" to connection.write(nodeId, parsed).quality.code.name))
    }

    private fun parseValue(text: String): Any = when {
        text == "true" || text == "false" -> text.toBoolean()
        text.toLongOrNull() != null && '.' !in text -> text.toLong()
        text.toDoubleOrNull() != null -> text.toDouble()
        else -> text
    }

    private class HistoryNotes(private val file: Path) {
        fun add(path: String, timestamp: Long, note: String) = synchronized(this) {
            val all = read().toMutableList()
            all += mapOf("path" to path, "timestamp" to timestamp, "note" to note)
            file.writeText(json.writeValueAsString(all))
        }

        fun list(path: String): List<Map<String, Any?>> = synchronized(this) {
            read().filter { it["path"] == path }
        }

        @Suppress("UNCHECKED_CAST")
        private fun read(): List<Map<String, Any?>> {
            if (!file.exists()) return emptyList()
            return json.readValue(file.readText(), List::class.java) as List<Map<String, Any?>>
        }
    }
}
