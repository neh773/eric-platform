package org.oip.opcua.server

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId

/**
 * Node ids for the device catalog. The namespace URI is [NAMESPACE_URI], separate from the tag namespace,
 * so a device item is never mistaken for a tag.
 *
 * `Devices` is the folder organized under Objects. `Devices/<name>` is one device or connection.
 * `Devices/<name>/<item>` is an item; `<item>` is the driver's own node id and may itself contain `/`.
 * A `/` or `%` in the device name is percent-encoded so the first segment stays the name.
 *
 * the reference platform does not publish the NodeId text of its device tree. This layout is ours; the browse names
 * are the device name and the driver's item name.
 */
object DeviceNodeIds {
    const val NAMESPACE_URI = "urn:oip:devices"
    const val ROOT = "Devices"

    fun device(name: String): String = "$ROOT/${encode(name)}"

    fun item(device: String, itemId: String): String = "${device(device)}/$itemId"

    fun parse(identifier: String): DeviceLoc? {
        if (identifier == ROOT) return DeviceLoc.Root
        if (!identifier.startsWith("$ROOT/")) return null
        val rest = identifier.removePrefix("$ROOT/")
        if (rest.isEmpty() || rest.endsWith("/")) return null
        val slash = rest.indexOf('/')
        val encoded = if (slash < 0) rest else rest.substring(0, slash)
        if (encoded.isEmpty()) return null
        val name = decode(encoded)
        if (name.isEmpty()) return null
        if (slash < 0) return DeviceLoc.Device(name)
        val item = rest.substring(slash + 1)
        if (item.isEmpty()) return null
        return DeviceLoc.Item(name, item)
    }

    /** Encode a device name so `/` cannot split it from the item id. */
    internal fun encode(name: String): String = buildString(name.length) {
        for (ch in name) {
            when (ch) {
                '%' -> append("%25")
                '/' -> append("%2F")
                else -> append(ch)
            }
        }
    }

    /** True when [text] is a parseable OPC UA node id (the form a remote server browse returns). */
    fun isRemoteNodeId(text: String): Boolean = try {
        NodeId.parse(text)
        true
    } catch (_: Exception) {
        false
    }

    internal fun decode(segment: String): String = buildString(segment.length) {
        var i = 0
        while (i < segment.length) {
            when {
                segment.startsWith("%2F", i) -> { append('/'); i += 3 }
                segment.startsWith("%25", i) -> { append('%'); i += 3 }
                else -> { append(segment[i]); i++ }
            }
        }
    }
}

sealed class DeviceLoc {
    data object Root : DeviceLoc()
    data class Device(val name: String) : DeviceLoc()
    data class Item(val device: String, val itemId: String) : DeviceLoc()
}
