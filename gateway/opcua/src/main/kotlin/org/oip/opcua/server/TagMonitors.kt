package org.oip.opcua.server

import org.eclipse.milo.opcua.sdk.server.items.DataItem
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.oip.tags.QualifiedValue
import org.oip.tags.TagCaller
import org.oip.tags.TagEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Push tag changes to OPC UA monitored items.
 *
 * Each tag has one engine subscription. Delivery is split across [stripes] single-thread
 * executors. A change publishes an atomic value and wakes the stripes that own items for that
 * tag. It does not take a lock shared by every monitored item, and the engine listener does not
 * call into Milo: the tag engine runs listeners on the writer thread.
 *
 * Sampling intervals above zero deliver the latest value at most once per interval. Zero means
 * every change.
 *
 * The engine subscription is shared by every item on the tag. Each item belongs to a session user ([callerOf]);
 * before a value goes to an item, [mayRead] checks that user against the tag's current read permissions, and a
 * denied item gets `Bad_UserAccessDenied` with no value instead (TAG-013). The check reads the tag's
 * configuration without the engine lock.
 */
internal class TagMonitors(
    private val engine: TagEngine,
    private val readValue: (DataItem) -> DataValue,
    private val callerOf: (DataItem) -> TagCaller = { TagCaller.System },
    private val mayRead: (Loc.Tag, TagCaller) -> Boolean = { _, _ -> true },
) : AutoCloseable {
    val stripes: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, 16)
    private val workers = Array(stripes) { index ->
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "opcua-tag-$index").apply { isDaemon = true }
        }
    }
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(stripes.coerceAtMost(4)) { runnable ->
        Thread(runnable, "opcua-tag-sample").apply { isDaemon = true }
    }
    private val feeds = ConcurrentHashMap<String, Feed>()
    private val closed = AtomicBoolean(false)

    fun created(items: List<DataItem>) {
        if (closed.get()) return
        for (item in items) track(item)
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
        feeds.values.forEach { it.close() }
        feeds.clear()
        workers.forEach { it.shutdownNow() }
        scheduler.shutdownNow()
    }

    private fun track(item: DataItem) {
        if (!AttributeId.Value.uid().equals(item.readValueId.attributeId)) {
            item.setValue(readValue(item))
            return
        }
        val id = item.readValueId.nodeId.identifier as? String ?: return
        val loc = TagNodeIds.parse(id) as? Loc.Tag ?: run {
            item.setValue(readValue(item))
            return
        }
        val key = loc.address()
        val feed = feeds.computeIfAbsent(key) { Feed(loc) }
        feed.add(item)
    }

    private fun remove(item: DataItem) {
        val id = item.readValueId.nodeId.identifier as? String ?: return
        val loc = TagNodeIds.parse(id) as? Loc.Tag ?: return
        val key = loc.address()
        val feed = feeds[key] ?: return
        if (feed.remove(item) && feeds.remove(key, feed)) feed.close()
    }

    private inner class Feed(val loc: Loc.Tag) {
        private val slots = CopyOnWriteArrayList<Slot>()
        private val latest = AtomicReference<DataValue?>(null)
        private val epoch = AtomicLong(0)
        private val running = AtomicIntegerArray(stripes)
        private val lock = Any()
        private var subscription: AutoCloseable? = null
        private var open = true

        fun add(item: DataItem) {
            val stripe = stripeOf(item)
            val slot = Slot(item, stripe, loc, callerOf(item)) { wake(stripe) }
            synchronized(lock) {
                if (!open) return
                slots.removeAll { it.item === item }
                slots.add(slot)
                if (subscription == null) {
                    subscription = engine.subscribe(loc.address()) { change ->
                        publish(TagValues.dataValue(change.value))
                    }
                    latest.compareAndSet(null, TagValues.dataValue(engine.read(loc.address())))
                }
            }
            wake(stripe)
        }

        fun remove(item: DataItem): Boolean {
            synchronized(lock) {
                slots.removeAll { it.item === item }
                if (slots.isNotEmpty()) return false
                open = false
                subscription?.close()
                subscription = null
                return true
            }
        }

        fun close() {
            synchronized(lock) {
                open = false
                subscription?.close()
                subscription = null
                slots.clear()
            }
        }

        private fun publish(dv: DataValue) {
            latest.set(dv)
            epoch.incrementAndGet()
            val present = BooleanArray(stripes)
            for (slot in slots) present[slot.stripe] = true
            for (stripe in 0 until stripes) if (present[stripe]) wake(stripe)
        }

        private fun wake(stripe: Int) {
            if (running.compareAndSet(stripe, 0, 1)) workers[stripe].execute { drain(stripe) }
        }

        private fun drain(stripe: Int) {
            var seen = epoch.get()
            try {
                do {
                    seen = epoch.get()
                    val dv = latest.get() ?: continue
                    for (slot in slots) if (slot.stripe == stripe) slot.submit(dv)
                } while (epoch.get() != seen)
            } finally {
                running.set(stripe, 0)
                if (epoch.get() != seen && running.compareAndSet(stripe, 0, 1)) {
                    workers[stripe].execute { drain(stripe) }
                }
            }
        }
    }

    private inner class Slot(
        val item: DataItem,
        val stripe: Int,
        private val loc: Loc.Tag,
        private val caller: TagCaller,
        private val resample: () -> Unit,
    ) {
        private val nextDue = AtomicLong(0)
        private val armed = AtomicBoolean(false)

        /** Called only on this slot's stripe, so two deliveries cannot pass each other. */
        fun submit(dv: DataValue) {
            if (!item.isSamplingEnabled) return
            val intervalMs = item.samplingInterval
            if (intervalMs.isNaN() || intervalMs <= 0.0) {
                deliver(dv)
                return
            }
            val intervalNs = (intervalMs * 1_000_000.0).toLong().coerceAtLeast(1L)
            val now = System.nanoTime()
            val due = nextDue.get()
            if (now >= due) {
                nextDue.set(now + intervalNs)
                deliver(dv)
            } else if (armed.compareAndSet(false, true)) {
                val waitNs = (due - now).coerceAtLeast(1_000_000L)
                scheduler.schedule({
                    armed.set(false)
                    resample()
                }, waitNs, TimeUnit.NANOSECONDS)
            }
        }

        private fun deliver(value: DataValue) {
            val dv = if (caller is TagCaller.User && !mayRead(loc, caller)) denied(value) else value
            val stamped = runCatching { DataValue.derivedValue(dv, item.timestampsToReturn) }.getOrDefault(dv)
            item.setValue(stamped)
        }
    }

    /** The change's timestamps with no value: the item's user may not read this tag. */
    private fun denied(dv: DataValue): DataValue =
        DataValue(Variant.NULL_VALUE, StatusCode(StatusCodes.Bad_UserAccessDenied), dv.sourceTime, dv.serverTime)

    private fun stripeOf(item: DataItem): Int {
        val id = item.id?.toLong() ?: System.identityHashCode(item).toLong()
        return (id and Long.MAX_VALUE).mod(stripes)
    }
}
