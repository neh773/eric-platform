package org.oip.opcua.server

import org.eclipse.milo.opcua.sdk.server.items.DataItem
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference

/**
 * Push driver samples to OPC UA monitored items on device nodes.
 *
 * Each item is one listener on the driver's existing poll or subscription ([DeviceEndpoint.watch]).
 * Two items on the same address therefore share that poll: the driver coalesces them, and a tag
 * that is already polling the address is the poll that gets joined. The sampling interval reported
 * to the client is the rate that poll actually runs.
 *
 * The driver callback only publishes an atomic sample. [setValue] runs on a stripe, not on the
 * driver's scan thread, so a scan is not blocked behind the OPC UA stack and a stack callback
 * cannot re-enter the socket the scan is using.
 *
 */
internal class DeviceMonitors(
    private val namespaceIndex: UShort,
    private val catalog: DeviceCatalog,
    private val readValue: (DataItem) -> DataValue,
) : AutoCloseable {
    private val stripes: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    private val workers = Array(stripes) { index ->
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "opcua-device-$index").apply { isDaemon = true }
        }
    }
    private val queues = Array(stripes) { ConcurrentLinkedQueue<Slot>() }
    private val running = AtomicIntegerArray(stripes)
    private val slots = ConcurrentHashMap<DataItem, Slot>()
    private val closed = AtomicBoolean(false)

    /** Revised sampling interval for [nodeId], or the request when this is not a sampled variable. */
    fun revised(nodeId: NodeId, requestedMs: Double): Double {
        val item = variable(nodeId) ?: return requestedMs
        return item.endpoint.revisedSamplingIntervalMs(item.itemId, requestedMs)
    }

    fun created(items: List<DataItem>) {
        if (closed.get()) return
        items.forEach { track(it) }
    }

    fun modified(items: List<DataItem>) {
        if (closed.get()) return
        for (item in items) {
            if (!item.isSamplingEnabled) remove(item) else track(item)
        }
    }

    fun deleted(items: List<DataItem>) {
        items.forEach { remove(it) }
    }

    fun modeChanged(items: List<MonitoredItem>) {
        for (item in items) {
            if (item is DataItem) {
                if (item.isSamplingEnabled) track(item) else remove(item)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val open = slots.values.toList()
        slots.clear()
        open.forEach { runCatching { it.handle?.close() } }
        workers.forEach { it.shutdownNow() }
    }

    private fun track(item: DataItem) {
        if (closed.get()) return
        if (!AttributeId.Value.uid().equals(item.readValueId.attributeId)) {
            item.setValue(readValue(item))
            return
        }
        val variable = variable(item.readValueId.nodeId)
        if (variable == null) {
            item.setValue(readValue(item))
            return
        }
        slots.remove(item)?.let { runCatching { it.handle?.close() } }
        val slot = Slot(item, stripeOf(item))
        slots[item] = slot
        val handle = try {
            variable.endpoint.watch(variable.itemId, item.samplingInterval) { reading -> publish(slot, reading) }
        } catch (_: Exception) {
            slots.remove(item, slot)
            item.setValue(readValue(item))
            return
        }
        if (slots[item] !== slot) {
            // Removed or replaced before watch returned.
            runCatching { handle.close() }
            return
        }
        slot.handle = handle
    }

    private fun remove(item: DataItem) {
        val slot = slots.remove(item) ?: return
        runCatching { slot.handle?.close() }
    }

    private fun publish(slot: Slot, reading: DeviceReading) {
        if (closed.get() || slots[slot.item] !== slot) return
        slot.latest.set(reading)
        val stripe = slot.stripe
        if (slot.queued.compareAndSet(false, true)) queues[stripe].add(slot)
        wake(stripe)
    }

    private fun wake(stripe: Int) {
        if (running.compareAndSet(stripe, 0, 1)) workers[stripe].execute { drain(stripe) }
    }

    private fun drain(stripe: Int) {
        try {
            while (true) {
                val slot = queues[stripe].poll() ?: break
                slot.queued.set(false)
                val reading = slot.latest.get() ?: continue
                if (slots[slot.item] === slot && slot.item.isSamplingEnabled) {
                    try {
                        deliver(slot, reading)
                    } catch (_: Throwable) {
                        // A rejected sample must not stop the stripe that serves the other items.
                    }
                }
                if (slot.latest.get() !== reading && slot.queued.compareAndSet(false, true)) {
                    queues[stripe].add(slot)
                }
            }
        } finally {
            running.set(stripe, 0)
            if (queues[stripe].isNotEmpty() && running.compareAndSet(stripe, 0, 1)) {
                workers[stripe].execute { drain(stripe) }
            }
        }
    }

    private fun deliver(slot: Slot, reading: DeviceReading) {
        val dv = dataValue(reading)
        val stamped = runCatching { DataValue.derivedValue(dv, slot.item.timestampsToReturn) }.getOrDefault(dv)
        slot.item.setValue(stamped)
    }

    private fun dataValue(reading: DeviceReading): DataValue {
        val source = reading.sourceTimestamp ?: reading.serverTimestamp
        return DataValue(
            TagValues.variant(reading.value),
            StatusCode(reading.statusCode and 0xffff_ffffL),
            TagValues.dateTime(source),
            TagValues.dateTime(reading.serverTimestamp),
        )
    }

    private fun variable(nodeId: NodeId): Sampled? {
        if (nodeId.namespaceIndex != namespaceIndex) return null
        val id = nodeId.identifier as? String ?: return null
        val loc = DeviceNodeIds.parse(id) as? DeviceLoc.Item ?: return null
        val endpoint = catalog.device(loc.device) ?: return null
        val described = endpoint.describe(loc.itemId) ?: return null
        if (!described.variable || !described.readable) return null
        return Sampled(endpoint, loc.itemId)
    }

    private fun stripeOf(item: DataItem): Int {
        val id = item.id?.toLong() ?: System.identityHashCode(item).toLong()
        return (id and Long.MAX_VALUE).mod(stripes)
    }

    private class Slot(val item: DataItem, val stripe: Int) {
        val latest = AtomicReference<DeviceReading?>(null)
        val queued = AtomicBoolean(false)
        @Volatile var handle: AutoCloseable? = null
    }

    private data class Sampled(val endpoint: DeviceEndpoint, val itemId: String)
}
