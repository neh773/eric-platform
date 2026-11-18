package org.oip.redundancy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmJournal
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.network.GatewayNetwork
import org.oip.network.LinkSettings
import org.oip.tags.TagEngine
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Listener delivery: registration order, one failure does not skip the rest,
 * and a listener may call back into the runtime without deadlocking.
 */
class RedundancyListenerTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun listenersRunInOrderOnActivityChanges() {
        val opened = open(NodeRole.MASTER)
        val runtime = opened.runtime
        try {
            assertEquals(ActivityLevel.UNDECIDED, runtime.activity())
            assertFalse(runtime.isActive())
            assertEquals(RuntimeGate.COLD, runtime.gate())
            val seen = Collections.synchronizedList(mutableListOf<String>())
            runtime.addListener { previous, current ->
                seen.add("a:${previous.activity.wire()}->${current.activity.wire()}")
            }
            runtime.addListener { previous, current ->
                seen.add("b:${previous.activity.wire()}->${current.activity.wire()}")
            }
            runtime.requestFailover()
            runtime.requestFailover()
            await { seen.size >= 4 }
            assertEquals(
                listOf(
                    "a:undecided->active",
                    "b:undecided->active",
                    "a:active->warm",
                    "b:active->warm",
                ),
                seen.toList(),
            )
            assertEquals(ActivityLevel.WARM, runtime.activity())
            assertEquals(NodeRole.MASTER, runtime.role())
            assertFalse(runtime.snapshot().active)
        } finally {
            opened.close()
        }
    }

    @Test
    fun oneListenerThrowingDoesNotSkipTheNext() {
        val opened = open(NodeRole.MASTER)
        val runtime = opened.runtime
        try {
            val seen = Collections.synchronizedList(mutableListOf<String>())
            runtime.addListener { _, _ -> seen.add("a") }
            runtime.addListener { _, _ -> throw IllegalStateException("listener failed") }
            runtime.addListener { _, _ -> seen.add("c") }
            runtime.requestFailover()
            await { seen.size >= 2 }
            assertEquals(listOf("a", "c"), seen.toList())
            assertTrue(runtime.isActive())
        } finally {
            opened.close()
        }
    }

    @Test
    fun listenerCanCallBackWithoutDeadlock() {
        val opened = open(NodeRole.MASTER)
        val runtime = opened.runtime
        try {
            val once = AtomicBoolean(false)
            val thread = AtomicReference<String>()
            val done = CountDownLatch(1)
            val error = AtomicReference<Throwable>()
            runtime.addListener { _, current ->
                if (!once.compareAndSet(false, true)) return@addListener
                try {
                    thread.set(Thread.currentThread().name)
                    assertEquals(ActivityLevel.ACTIVE, current.activity)
                    runtime.snapshot()
                    runtime.role()
                    runtime.activity()
                    runtime.gate()
                    runtime.isActive()
                    runtime.statusDocument()
                    val added = runtime.addListener { _, _ -> }
                    added.close()
                    val worker = Thread {
                        runtime.snapshot()
                        runtime.requestFailover()
                    }
                    worker.start()
                    worker.join(3_000)
                    assertFalse(worker.isAlive, "callback into the runtime did not return")
                } catch (thrown: Throwable) {
                    error.set(thrown)
                } finally {
                    done.countDown()
                }
            }
            runtime.requestFailover()
            assertTrue(done.await(5, TimeUnit.SECONDS), "listener was not called")
            error.get()?.let { throw it }
            assertEquals("redundancy-listener", thread.get())
            assertFalse(thread.get() == "redundancy")
        } finally {
            opened.close()
        }
    }

    @Test
    fun removedListenerDoesNotRunAndIndependentStaysActive() {
        val opened = open(NodeRole.MASTER)
        val runtime = opened.runtime
        try {
            var calls = 0
            val listener = RedundancyListener { _, _ -> calls += 1 }
            runtime.addListener(listener)
            runtime.removeListener(listener)
            runtime.requestFailover()
            Thread.sleep(150)
            assertEquals(0, calls)
        } finally {
            opened.close()
        }
        val aloneOpened = open(NodeRole.INDEPENDENT)
        val alone = aloneOpened.runtime
        try {
            var calls = 0
            alone.addListener { _, _ -> calls += 1 }
            alone.start()
            alone.requestFailover()
            Thread.sleep(100)
            assertEquals(0, calls)
            assertTrue(alone.isActive())
            assertEquals(NodeRole.INDEPENDENT, alone.role())
            assertEquals(ActivityLevel.ACTIVE, alone.activity())
            assertEquals(RuntimeGate.ACTIVE, alone.gate())
            assertTrue(alone.snapshot().active)
        } finally {
            aloneOpened.close()
        }
    }

    @Test
    fun concurrentFlushersDeliverChangesInOrder() {
        val opened = open(NodeRole.MASTER)
        val runtime = opened.runtime
        try {
            val delivered = Collections.synchronizedList(mutableListOf<Pair<RedundancySnapshot, RedundancySnapshot>>())
            runtime.addListener { previous, current -> delivered.add(previous to current) }
            val threads = 4
            val rounds = 2_000
            val ready = CountDownLatch(threads)
            val go = CountDownLatch(1)
            val workers = (0 until threads).map { index ->
                Thread {
                    ready.countDown()
                    go.await()
                    repeat(rounds) { round ->
                        if ((index + round) % 2 == 0) runtime.requestFailover() else runtime.assumeControl()
                    }
                }.apply { start() }
            }
            ready.await()
            go.countDown()
            workers.forEach { it.join(30_000) }
            assertTrue(workers.none { it.isAlive }, "workers did not finish")
            // Every change is queued before the call that made it returns, so a no-op round trip drains the queue.
            val drained = CountDownLatch(1)
            runtime.addListener(replay = true) { _, _ -> drained.countDown() }
            assertTrue(drained.await(10, TimeUnit.SECONDS), "listener queue did not drain")
            val events = delivered.toList()
            assertTrue(events.size > 10, "only ${events.size} changes delivered")
            for (index in 1 until events.size) {
                assertEquals(events[index - 1].second, events[index].first, "event $index out of order")
            }
            for ((previous, current) in events) assertTrue(previous != current)
            assertEquals(runtime.snapshot(), events.last().second)
        } finally {
            opened.close()
        }
    }

    @Test
    fun replayDeliversCurrentStateFirstAndThenChangesInOrder() {
        val opened = open(NodeRole.MASTER)
        val runtime = opened.runtime
        try {
            runtime.requestFailover()
            assertTrue(runtime.isActive())
            val seen = Collections.synchronizedList(mutableListOf<Pair<RedundancySnapshot, RedundancySnapshot>>())
            val thread = AtomicReference<String>()
            runtime.addListener(replay = true) { previous, current ->
                thread.compareAndSet(null, Thread.currentThread().name)
                seen.add(previous to current)
            }
            runtime.requestFailover()
            await { seen.size >= 2 }
            val (replayPrevious, replayCurrent) = seen[0]
            assertEquals(replayPrevious, replayCurrent)
            assertTrue(replayCurrent.active)
            assertEquals(ActivityLevel.ACTIVE, replayCurrent.activity)
            assertEquals(replayCurrent, seen[1].first)
            assertFalse(seen[1].second.active)
            assertEquals("redundancy-listener", thread.get())

            // A plain listener hears nothing until the next change.
            val plain = Collections.synchronizedList(mutableListOf<RedundancySnapshot>())
            runtime.addListener { _, current -> plain.add(current) }
            Thread.sleep(100)
            assertTrue(plain.isEmpty())

            val aloneOpened = open(NodeRole.INDEPENDENT)
            try {
                val got = AtomicReference<RedundancySnapshot>()
                aloneOpened.runtime.addListener(replay = true) { _, current -> got.set(current) }
                await { got.get() != null }
                assertTrue(got.get().active)
                assertEquals(NodeRole.INDEPENDENT, got.get().role)
            } finally {
                aloneOpened.close()
            }
        } finally {
            opened.close()
        }
    }

    @Test
    fun listenerErrorDoesNotStopLaterListenersOrLaterEvents() {
        val opened = open(NodeRole.MASTER)
        val runtime = opened.runtime
        try {
            val seen = Collections.synchronizedList(mutableListOf<String>())
            runtime.addListener { _, _ -> throw AssertionError("listener error") }
            runtime.addListener { _, current -> seen.add(current.activity.wire()) }
            runtime.requestFailover()
            runtime.requestFailover()
            await { seen.size >= 2 }
            assertEquals(listOf("active", "warm"), seen.toList())
        } finally {
            opened.close()
        }
    }

    @Test
    fun callsAfterCloseDoNotThrow() {
        val opened = open(NodeRole.MASTER)
        val runtime = opened.runtime
        runtime.addListener { _, _ -> }
        opened.close()
        runtime.requestFailover()
        runtime.assumeControl()
        runtime.addListener(replay = true) { _, _ -> }
        runtime.isActive()
    }

    private fun open(role: NodeRole): Opened {
        val root = tmp.resolve(role.name.lowercase() + "-" + System.nanoTime())
        val tags = TagEngine()
        val store = FileConfigStore(root.resolve("config"), SchemaRegistry())
        val alarms = AlarmEngine(java.time.Clock.systemUTC(), AlarmJournal(root.resolve("db/alarms.sqlite")))
        val network = GatewayNetwork(
            root.resolve("network"),
            tags,
            LinkSettings(enabled = false, gatewayName = "node"),
        )
        val settings = RedundancySettings(
            mode = role,
            startupAllowanceMillis = if (role == NodeRole.INDEPENDENT) 0 else 30_000,
        )
        val runtime = RedundancyRuntime(settings, network, store, tags, alarms)
        return Opened(runtime, alarms, store, network)
    }

    private class Opened(
        val runtime: RedundancyRuntime,
        private val alarms: AlarmEngine,
        private val store: FileConfigStore,
        private val network: GatewayNetwork,
    ) : AutoCloseable {
        override fun close() {
            runtime.close()
            alarms.close()
            store.close()
            network.close()
        }
    }

    private fun await(timeoutMs: Long = 5_000, probe: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (probe()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met")
    }
}
