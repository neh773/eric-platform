package org.oip.tags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Allocation and concurrency on the in-process sample handle.
 * The retained-heap budget for the real gateway path (config batch, file store, Modbus, OPC UA)
 * lives in gateway/server TagScaleTest.
 */
class TagScaleTest {
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun sampleHandleUpdatesAllocateUnderBudgetAndDoNotNeedTheConfigLock() {
        val engine = TagEngine()
        val n = 2_000
        repeat(n) { i ->
            engine.configure(TagConfig(
                path = "p$i", type = TagType.OPC, dataType = DataType.INT32,
                opcServer = "dev", opcItemPath = "HR$i",
            ))
        }
        val samples = List(n) { engine.openSample("p$it") }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        repeat(2) { pass -> samples.forEach { it.update(pass, Quality.GOOD, 1_000L) } }
        val passes = 8
        val stringBefore = bean.currentThreadAllocatedBytes
        repeat(passes) { pass ->
            for (i in 0 until n) engine.updateRaw("p$i", pass + 3, Quality.GOOD, null)
        }
        val stringAllocated = bean.currentThreadAllocatedBytes - stringBefore
        val stringPer = stringAllocated.toDouble() / (n * passes)
        val before = bean.currentThreadAllocatedBytes
        repeat(passes) { pass ->
            samples.forEach { it.update(pass + 3 + passes, Quality.GOOD, 1_000L) }
        }
        val allocated = bean.currentThreadAllocatedBytes - before
        val per = allocated.toDouble() / (n * passes)
        println("SCALE alloc string-path ${"%.1f".format(stringPer)} bytes/update; handle ${"%.1f".format(per)} bytes/update over ${n * passes} samples")
        assertTrue(per < 512, "allocation ${"%.1f".format(per)} bytes/update is above 512")
        assertEquals(3 + passes + passes - 1, samples[n - 1].read().value)

        val updates = AtomicInteger()
        val threads = List(4) { stripe ->
            Thread {
                val mine = samples.filterIndexed { index, _ -> index % 4 == stripe }
                repeat(200) { pass ->
                    for (sample in mine) sample.update(pass, Quality.GOOD, 2_000L)
                    updates.incrementAndGet()
                }
            }
        }
        val config = Thread {
            repeat(50) {
                engine.configure(TagConfig(path = "extra$it", type = TagType.MEMORY, dataType = DataType.INT32, value = it, valueSet = true))
            }
        }
        threads.forEach { it.start() }
        config.start()
        threads.forEach { it.join(15_000) }
        config.join(15_000)
        assertTrue(threads.all { !it.isAlive })
        assertTrue(!config.isAlive)
        assertEquals(4 * 200, updates.get())
        assertEquals(199, samples[0].read().value)
    }
}
