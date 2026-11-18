package org.oip.tags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The [TagChange] contract: a listener gets one tag's changes in the order the engine stored them, and each
 * change's `previous` is the value the engine held just before it, which is the value this listener was last
 * given. A change stored while that tag's listeners are still being called is queued behind the current one.
 */
class TagChangeOrderTest {
    private data class Seen(val value: Any?, val previous: Any?)

    private fun assertChained(seen: List<Seen>, first: Any?, last: Any?) {
        assertTrue(seen.isNotEmpty(), "no changes delivered")
        assertEquals(first, seen.first().previous, "first previous in $seen")
        for (i in 1 until seen.size) {
            assertEquals(seen[i - 1].value, seen[i].previous, "change $i of $seen does not follow the one before it")
        }
        assertEquals(last, seen.last().value, "last value in $seen")
    }

    /** A listener that writes the same tag queues a second change while the first is still being delivered. */
    @Test
    fun aListenerWritingTheSameTagDoesNotPairTheNewValueWithAnOldPrevious() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "M", type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
        engine.subscribe("M") { if (it.value.value == 1) engine.write("M", 2) }
        val seen = ArrayList<Seen>()
        engine.subscribe("M") { seen += Seen(it.value.value, it.previous?.value) }

        engine.write("M", 1)

        assertEquals(2, engine.read("M").value)
        assertEquals(listOf(Seen(1, 0), Seen(2, 1)), seen)
        assertChained(seen, first = 0, last = engine.read("M").value)
    }

    /** A second thread stores a value while the first thread is still calling listeners for the one before. */
    @Test
    fun aValueStoredOnAnotherThreadDuringDeliveryIsQueuedBehindIt() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "M", type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
        val sample = engine.openSample("M")
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.subscribe("M") {
            if (it.value.value == 1 && inside.count > 0) {
                inside.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val seen = Collections.synchronizedList(ArrayList<Seen>())
        engine.subscribe("M") { seen += Seen(it.value.value, it.previous?.value) }

        val scan = Thread { sample.set(1) }
        scan.start()
        assertTrue(inside.await(10, TimeUnit.SECONDS), "first listener was not called")
        // The engine has stored 1 and is calling the first listener for it on the scan thread.
        assertEquals(1, engine.read("M").value)
        engine.write("M", 2)
        release.countDown()
        scan.join(10_000)

        assertEquals(2, engine.read("M").value)
        assertEquals(listOf(Seen(1, 0), Seen(2, 1)), seen.toList())
    }

    /** Many writers on one tag: every listener still sees one unbroken chain ending at the stored value. */
    @Test
    fun concurrentWritersStillGiveEachListenerAnUnbrokenChain() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "M", type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
        val a = Collections.synchronizedList(ArrayList<Seen>())
        val b = Collections.synchronizedList(ArrayList<Seen>())
        engine.subscribe("M") { a += Seen(it.value.value, it.previous?.value) }
        engine.subscribe("M") { b += Seen(it.value.value, it.previous?.value) }
        val start = CountDownLatch(1)
        val threads = (0 until 4).map { t ->
            Thread {
                val sample = engine.openSample("M")
                start.await()
                for (i in 1..2_000) {
                    if (i % 2 == 0) sample.set(t * 10_000 + i) else engine.write("M", t * 10_000 + i)
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(30_000) }

        val last = engine.read("M").value
        assertChained(a.toList(), first = 0, last = last)
        assertChained(b.toList(), first = 0, last = last)
        assertEquals(8_000, a.size)
        assertEquals(a.toList(), b.toList())
    }
}
