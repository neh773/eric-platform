package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.ConflictException
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.modbus.AddressException
import org.oip.modbus.DEVICE_KIND
import org.oip.modbus.MappedAddress
import org.oip.modbus.ModbusAddressMapping
import org.oip.modbus.ModbusAddressRange
import org.oip.modbus.ModbusDriverModule
import org.oip.modbus.ModbusType
import org.oip.modbus.modbusDeviceConfig
import org.oip.security.AuditContext
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE

/**
 * Modbus address mapping. A range row (prefix, start, end, radix, step, unit id, Modbus type, first
 * address) expands into named driver addresses, and then into ordinary OPC tag resources bound to the
 * device. The tags go through the config store in one batch, the same path as `POST /api/v1/config/batch`,
 * so nothing new is stored: the mapping is a generator, not a second address table.
 *
 * `POST .../preview` returns the items and needs `gateway.read`. `POST .../tags` writes them and needs
 * `gateway.write` (see [GatewayAuth]). Body:
 * `{ "device", "folder"?, "provider"?, "scanPeriodMillis"?, "overwrite"?, "ranges": [ { "prefix", "start",
 * "end", "radix"?, "step"?, "unitId"?, "modbusType", "modbusAddress" } ] }`.
 *
 * `overwrite: true` replaces only Tag resources labelled `modbus-address-map: <device>` (that is, tags
 * this mapping generated for the same device). Any other existing Tag with a generated name is a 409.
 */
class ModbusMappingApi(private val store: FileConfigStore) {
    fun register(app: Javalin) {
        app.post(PREVIEW) { ctx ->
            val request = parse(ctx)
            val items = generate(request)
            json(ctx, 200, mapOf("count" to items.size, "items" to items.map { it.view() }))
        }
        app.post(TAGS) { ctx ->
            val request = parse(ctx)
            val items = generate(request)
            val resources = items.map { item -> resource(request, item) }
            val result = try {
                store.putAll(Scope.GATEWAY, resources, AuditContext.current()?.actor ?: ConfigApi.ACTOR, syncEach = false)
            } catch (e: ConflictException) {
                throw Problems.conflict("${e.message}; send \"overwrite\": true to replace generated tags")
            }
            json(ctx, 200, mapOf(
                "created" to result.created,
                "updated" to result.updated,
                "count" to result.stored.size,
                "items" to items.map { it.view() },
            ))
        }
    }

    private data class Request(
        val device: String,
        val folder: String,
        val provider: String,
        val scanPeriodMillis: Int?,
        val overwrite: Boolean,
        val ranges: List<ModbusAddressRange>,
    )

    private data class Item(val resourceName: String, val path: String, val mapped: MappedAddress) {
        fun dataType(): String = dataTypeOf(mapped.type)
        fun view(): Map<String, Any?> = linkedMapOf(
            "name" to mapped.name,
            "resourceName" to resourceName,
            "path" to path,
            "address" to mapped.address,
            "dataType" to dataType(),
            "writable" to mapped.writable(),
        )
    }

    private fun generate(request: Request): List<Item> {
        val deviceResource = store.get(ResourceId(ModbusDriverModule.MODULE_ID, Scope.GATEWAY, DEVICE_KIND, request.device))
            ?: throw Problems.notFound("no Modbus device ${request.device}")
        val device = try {
            modbusDeviceConfig(request.device, deviceResource.spec)
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable("device ${request.device} is not usable: ${e.message}")
        }
        val out = ArrayList<Item>()
        val names = HashSet<String>()
        request.ranges.forEachIndexed { index, range ->
            val mapped = try {
                ModbusAddressMapping.expand(range, device)
            } catch (e: AddressException) {
                throw Problems.unprocessable("ranges[$index]: ${e.message}")
            }
            for (item in mapped) {
                val resourceName = resourceName(request.device, item.name)
                if (!names.add(resourceName)) throw Problems.unprocessable("ranges[$index]: ${item.name} is generated twice")
                out += Item(resourceName, "${request.folder}/${item.name}", item)
                if (out.size > ModbusAddressMapping.MAX_ITEMS) {
                    throw Problems.unprocessable("ranges generate more than ${ModbusAddressMapping.MAX_ITEMS} items")
                }
            }
        }
        return out
    }

    private fun resource(request: Request, item: Item): Resource {
        val version = if (request.overwrite) {
            val existing = store.get(ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, item.resourceName))
            // Overwrite only replaces tags this mapping generated for the same device. A hand-made Tag
            // that happens to share the generated name is left alone.
            if (existing != null && existing.metadata.labels[MAPPING_LABEL] != request.device) {
                throw Problems.conflict(
                    "Tag ${item.resourceName} exists and is not labelled $MAPPING_LABEL: ${request.device}; overwrite only replaces generated tags",
                )
            }
            existing?.metadata?.version ?: 0L
        } else {
            0L
        }
        val spec = linkedMapOf<String, Any?>(
            "provider" to request.provider,
            "path" to item.path,
            "type" to "opc",
            "dataType" to item.dataType(),
            "opcServer" to request.device,
            "opcItemPath" to item.mapped.address,
        )
        if (!item.mapped.writable()) spec["writable"] = false
        request.scanPeriodMillis?.let { spec["scanPeriodMillis"] = it }
        return Resource(
            TAG_KIND,
            Metadata(item.resourceName, TAGS_MODULE, labels = mapOf(MAPPING_LABEL to request.device), version = version),
            spec,
        )
    }

    private fun parse(ctx: Context): Request {
        val body = try {
            Json.parse(ctx.body())
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        val device = body["device"] as? String ?: throw Problems.unprocessable("device is required")
        if (!RESOURCE_NAME.matches(device)) throw Problems.unprocessable("device '$device' is not a resource name")
        val folder = (body["folder"] as? String ?: device).trim().trim('/')
        if (folder.isEmpty() || folder.any { it == '[' || it == ']' || it.isISOControl() } || folder.split('/').any { it.isBlank() }) {
            throw Problems.unprocessable("folder must be a tag folder path such as 'Plant/Line1'")
        }
        val provider = body["provider"] as? String ?: "default"
        if (provider.isBlank() || provider.any { it == '[' || it == ']' || it == '/' }) throw Problems.unprocessable("provider is invalid")
        val scan = when (val raw = body["scanPeriodMillis"]) {
            null -> null
            is Number -> raw.toInt().also { if (it < 0) throw Problems.unprocessable("scanPeriodMillis must be >= 0") }
            else -> throw Problems.unprocessable("scanPeriodMillis must be a number")
        }
        val overwrite = when (val raw = body["overwrite"]) {
            null -> false
            is Boolean -> raw
            else -> throw Problems.unprocessable("overwrite must be true or false")
        }
        val rawRanges = body["ranges"] as? List<*> ?: throw Problems.unprocessable("ranges must be an array")
        if (rawRanges.isEmpty()) throw Problems.unprocessable("ranges is empty")
        if (rawRanges.size > MAX_RANGES) throw Problems.unprocessable("ranges exceeds $MAX_RANGES")
        val ranges = rawRanges.mapIndexed { index, raw ->
            val row = raw as? Map<*, *> ?: throw Problems.unprocessable("ranges[$index] must be an object")
            fun text(key: String): String = when (val v = row[key]) {
                is String -> v
                is Number -> v.toLong().toString()
                else -> throw Problems.unprocessable("ranges[$index].$key is required")
            }
            fun int(key: String): Int? = when (val v = row[key]) {
                null -> null
                is Number -> v.toInt()
                else -> throw Problems.unprocessable("ranges[$index].$key must be a number")
            }
            ModbusAddressRange(
                prefix = row["prefix"] as? String ?: throw Problems.unprocessable("ranges[$index].prefix is required"),
                start = text("start"),
                end = text("end"),
                modbusType = row["modbusType"] as? String ?: throw Problems.unprocessable("ranges[$index].modbusType is required"),
                modbusAddress = int("modbusAddress") ?: throw Problems.unprocessable("ranges[$index].modbusAddress is required"),
                radix = int("radix") ?: 10,
                step = when (val v = row["step"]) {
                    null -> false
                    is Boolean -> v
                    else -> throw Problems.unprocessable("ranges[$index].step must be true or false")
                },
                unitId = int("unitId"),
            )
        }
        return Request(device, folder, provider, scan, overwrite, ranges)
    }

    private fun json(ctx: Context, status: Int, value: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(value))
    }

    companion object {
        const val BASE = "/api/v1/modbus/address-ranges"
        const val PREVIEW = "$BASE/preview"
        const val TAGS = "$BASE/tags"
        const val MAX_RANGES = 256
        /** Label on generated Tag resources; the value is the device name. */
        const val MAPPING_LABEL = "modbus-address-map"
        private val RESOURCE_NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        fun dataTypeOf(type: ModbusType): String = when (type) {
            ModbusType.BOOLEAN, ModbusType.BIT -> "Boolean"
            ModbusType.INT16, ModbusType.UINT16, ModbusType.INT32, ModbusType.BCD16 -> "Int32"
            ModbusType.UINT32, ModbusType.INT64, ModbusType.UINT64, ModbusType.BCD32 -> "Int64"
            ModbusType.FLOAT32 -> "Float"
            ModbusType.FLOAT64 -> "Double"
            ModbusType.STRING -> "String"
        }

        /** Lowercase DNS-label resource name: `<device>-<name>`, underscores as dashes. */
        fun resourceName(device: String, name: String): String {
            val raw = "$device-${name.lowercase().replace('_', '-')}"
                .replace(Regex("-{2,}"), "-").trim('-')
            if (!RESOURCE_NAME.matches(raw)) {
                throw Problems.unprocessable("generated tag resource name '$raw' is not valid (lowercase letters, digits, '-', at most 63 characters)")
            }
            return raw
        }

        private fun MappedAddress.writable(): Boolean =
            area == org.oip.modbus.ModbusArea.COIL || area == org.oip.modbus.ModbusArea.HOLDING_REGISTER
    }
}
