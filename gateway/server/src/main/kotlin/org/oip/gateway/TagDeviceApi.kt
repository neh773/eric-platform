package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.Scope
import org.oip.modbus.DEVICE_KIND
import org.oip.modbus.ModbusDevice
import org.oip.modbus.ModbusDriverModule
import org.oip.opcua.CONNECTION_KIND
import org.oip.opcua.OpcUaClientModule
import org.oip.opcua.OpcUaConnection

/**
 * Admin tag browser support (C-16).
 *
 * `GET /api/v1/tags/providers` lists the tag providers the tree shows as roots (tags.read).
 *
 * `GET /api/v1/tags/devices` lists the configured Modbus devices and OPC UA client connections a tag can be
 * bound to, with their connection state (gateway.read).
 *
 * `POST /api/v1/tags/devices/browse` with `{ "device", "nodeId"?: "", "offset"?: 0 }` lists one page of a
 * device's nodes so the browser can create OPC tags from them (gateway.write: it talks to the device and
 * returns raw device values that no tag permission covers).
 * - An OPC UA connection is browsed on the server (Objects folder at the root). Variable nodes come back
 *   with their current value and quality.
 * - Modbus has no browse service, so the root lists the four address areas and each area pages through
 *   addresses (`HR1`, `HR2`, ...) with one probe read per page. Addresses the device already polls are
 *   listed at the root as well.
 * Each node's `nodeId` is the `opcItemPath` for a tag with `opcServer` = the device name.
 */
class TagDeviceApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get(PROVIDERS) { ctx ->
            val providers = gw.tags.providers().sortedWith(compareBy({ it.name != "default" }, { it.name })).map { p ->
                linkedMapOf("name" to p.name, "readOnly" to p.readOnly)
            }
            json(ctx, mapOf("providers" to providers))
        }
        app.get(DEVICES) { ctx -> json(ctx, mapOf("devices" to devices())) }
        app.post(BROWSE) { ctx -> browse(ctx) }
    }

    private fun modbus(): ModbusDriverModule? = gw.host.instance(ModbusDriverModule.MODULE_ID) as? ModbusDriverModule
    private fun opc(): OpcUaClientModule? = gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule

    private fun devices(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val modbus = modbus()
        for (stored in gw.store.list(ModbusDriverModule.MODULE_ID, Scope.GATEWAY, DEVICE_KIND)) {
            val name = stored.id.name
            val state = modbus?.device(name)?.connectionStatus()?.state?.name ?: "NOT_RUNNING"
            out += linkedMapOf("name" to name, "driver" to "modbus", "state" to state)
        }
        val opc = opc()
        for (stored in gw.store.list(OpcUaClientModule.MODULE_ID, Scope.GATEWAY, CONNECTION_KIND)) {
            val name = stored.id.name
            val state = opc?.connection(name)?.connectionStatus()?.state?.name ?: "NOT_RUNNING"
            out += linkedMapOf("name" to name, "driver" to "opcua", "state" to state)
        }
        return out.sortedBy { it["name"] as String }
    }

    private fun browse(ctx: Context) {
        val body = try {
            Json.mapper.readValue(ctx.body(), Any::class.java) as? Map<*, *>
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            throw Problems.badRequest("malformed JSON: ${e.originalMessage}")
        } ?: throw Problems.unprocessable("body must be an object")
        val device = (body["device"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw Problems.unprocessable("device is required")
        val nodeId = when (val raw = body["nodeId"]) {
            null -> ""
            is String -> raw.trim()
            else -> throw Problems.unprocessable("nodeId must be a string")
        }
        if (nodeId.length > MAX_NODE_ID) throw Problems.unprocessable("nodeId exceeds $MAX_NODE_ID characters")
        val offset = when (val raw = body["offset"]) {
            null -> 0
            is Int -> raw
            is Long -> if (raw in 0..Int.MAX_VALUE) raw.toInt() else throw Problems.unprocessable("offset is out of range")
            else -> throw Problems.unprocessable("offset must be an integer")
        }
        if (offset < 0) throw Problems.unprocessable("offset must not be negative")

        val modbusDevice = modbus()?.device(device)
        val page = when {
            modbusDevice != null -> modbusPage(modbusDevice, nodeId, offset)
            else -> {
                val connection = opc()?.connection(device) ?: throw Problems.notFound("device or connection '$device' is not configured")
                opcPage(connection, nodeId, offset)
            }
        }
        json(ctx, linkedMapOf("device" to device, "nodeId" to nodeId, "offset" to offset, "nodes" to page.first, "more" to page.second))
    }

    private fun modbusPage(device: ModbusDevice, nodeId: String, offset: Int): Pair<List<Map<String, Any?>>, Boolean> {
        if (nodeId.isEmpty()) {
            val areas = MODBUS_AREAS.map { (id, label) ->
                node("$AREA_PREFIX$id", label, "Folder", readable = false, writable = false, hasChildren = true)
            }
            val polled = device.browse("").map { n ->
                node(n.nodeId, n.displayName, n.nodeClass, n.readable, n.writable, hasChildren = false)
            }
            val all = areas + polled
            return all.drop(offset).take(PAGE) to (all.size > offset + PAGE)
        }
        val area = nodeId.removePrefix(AREA_PREFIX)
        if (!nodeId.startsWith(AREA_PREFIX) || MODBUS_AREAS.none { it.first == area }) {
            throw Problems.notFound("Modbus node '$nodeId' has no children")
        }
        val first = if (device.config.zeroBasedAddressing) 0 else 1
        val last = if (device.config.zeroBasedAddressing) 65_535 else 65_536
        val start = first.toLong() + offset
        if (start > last) return emptyList<Map<String, Any?>>() to false
        val end = minOf(last.toLong(), start + MODBUS_PAGE - 1)
        val ids = (start..end).map { "$area$it" }
        val values = runCatching { device.probe(ids) }.getOrNull()
        val writable = area == "HR" || area == "C"
        val rows = ids.mapIndexed { i, id ->
            val sample = values?.getOrNull(i)
            node(id, id, "Variable", readable = true, writable = writable, hasChildren = false).also { row ->
                if (sample != null) {
                    row["value"] = sample.value
                    row["quality"] = sample.quality.code.name
                }
            }
        }
        return rows to (end < last)
    }

    private fun opcPage(connection: OpcUaConnection, nodeId: String, offset: Int): Pair<List<Map<String, Any?>>, Boolean> {
        val all = try {
            connection.browse(nodeId)
        } catch (e: RuntimeException) {
            throw Problems.badRequest("browse failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val slice = all.drop(offset).take(PAGE)
        val variables = slice.filter { it.readable }.map { it.nodeId }
        val samples = if (variables.isEmpty()) emptyMap() else runCatching {
            variables.zip(connection.read(variables)).toMap()
        }.getOrDefault(emptyMap())
        val rows = slice.map { n ->
            node(n.nodeId, n.displayName, n.nodeClass, n.readable, n.writable, hasChildren = n.nodeClass != "Variable").also { row ->
                samples[n.nodeId]?.let { sample ->
                    row["value"] = sample.value
                    row["quality"] = sample.quality.code.name
                }
            }
        }
        return rows to (all.size > offset + PAGE)
    }

    private fun node(nodeId: String, name: String, nodeClass: String, readable: Boolean, writable: Boolean, hasChildren: Boolean) =
        linkedMapOf<String, Any?>(
            "nodeId" to nodeId,
            "name" to name,
            "nodeClass" to nodeClass,
            "readable" to readable,
            "writable" to writable,
            "hasChildren" to hasChildren,
        )

    private fun json(ctx: Context, body: Any) {
        ctx.contentType("application/json").result(Json.write(body))
    }

    companion object {
        const val PROVIDERS = "/api/v1/tags/providers"
        const val DEVICES = "/api/v1/tags/devices"
        const val BROWSE = "/api/v1/tags/devices/browse"
        const val PAGE = 50
        // One probe read per Modbus page. Small, so one out-of-range address does not fail a long block.
        const val MODBUS_PAGE = 10
        const val MAX_NODE_ID = 4_096
        private const val AREA_PREFIX = "area:"
        private val MODBUS_AREAS = listOf(
            "HR" to "Holding registers",
            "IR" to "Input registers",
            "C" to "Coils",
            "DI" to "Discrete inputs",
        )
    }
}
