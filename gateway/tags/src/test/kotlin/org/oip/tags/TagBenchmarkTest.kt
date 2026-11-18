package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * Legacy 10_000-tag scan. ADR 0010 withdrew this as a gate. The replacement is PRF-007:
 * at least 100_000 tags at 1 Hz, 100% changing, under 25% of one core. This test is not PRF-007.
 * hundredThousandTagFootprint measures retained heap, not the scan-rate gate.
 * The 100_000-tag scan is TagPrf007BenchmarkTest. Leave the 10_000 assertions in place.
 */
class TagBenchmarkTest {
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun tenThousandTagsAtOneHertzStayCheap() {
        val tags = 10_000
        val subscribers = 4
        val engine = TagEngine()
        repeat(tags) { i ->
            engine.configure(TagConfig(path = "s$i", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0.0, valueSet = true))
        }
        val counts = LongArray(subscribers)
        repeat(subscribers) { s ->
            repeat(tags) { i ->
                engine.subscribe("s$i") { counts[s]++ }
            }
        }
        val cpu = ManagementFactory.getThreadMXBean()
        assertTrue(cpu.isCurrentThreadCpuTimeSupported)
        fun pass(value: Double) {
            for (i in 0 until tags) engine.write("s$i", value)
        }
        // Warm up, then each measured pass writes a new value so deadband does not swallow the scan.
        repeat(2) { pass(1.0) }
        for (i in counts.indices) counts[i] = 0
        var bestCpu = Long.MAX_VALUE
        var bestWall = Long.MAX_VALUE
        val passes = 3
        repeat(passes) { n ->
            val cpu0 = cpu.currentThreadCpuTime
            val wall0 = System.nanoTime()
            pass(2.0 + n)
            val cpuMs = (cpu.currentThreadCpuTime - cpu0) / 1_000_000
            val wallMs = (System.nanoTime() - wall0) / 1_000_000
            if (cpuMs < bestCpu) bestCpu = cpuMs
            if (wallMs < bestWall) bestWall = wallMs
        }
        val percentOfOneCore = bestCpu / 10.0
        println("BENCH 10k tags x $subscribers subscribers, one 1s scan: cpu=${bestCpu}ms wall=${bestWall}ms (~$percentOfOneCore% of one core at 1 Hz)")
        assertEquals(tags.toLong() * passes, counts[0])
        assertEquals(tags.toLong() * passes, counts[subscribers - 1])
        assertTrue(bestCpu < 250, "CPU ${bestCpu}ms for 10k updates is too high for a 1s scan")
        assertTrue(bestWall < 1000, "wall ${bestWall}ms cannot keep up with a 1s scan")
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun hundredThousandTagFootprint() {
        val tags = 100_000
        forceGc()
        val before = usedHeap()
        val engine = TagEngine()
        repeat(tags) { i ->
            engine.configure(TagConfig(path = "m$i", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = i, valueSet = true))
        }
        assertEquals(tags - 1.0, (engine.read("m${tags - 1}").value as Number).toDouble())
        forceGc()
        val after = usedHeap()
        val delta = (after - before).coerceAtLeast(0)
        val perTag = delta / tags.toDouble()
        println("BENCH 100k tags heap delta=${delta} bytes (${"%.1f".format(perTag)} bytes/tag), used=$after")
        assertTrue(perTag < 3072, "footprint ${perTag} bytes/tag is above 3KB")
        assertEquals(tags, engine.browse("[default]").size)
    }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun forceGc() {
        repeat(4) {
            System.gc()
            Thread.sleep(30)
        }
    }
}
