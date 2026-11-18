package org.oip.simulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * One evaluation of 10_000 addresses is the work of a 1 s base rate.
 * The ceiling is 5% of one core: 50 ms of CPU for that evaluation.
 *
 */
class SimulatorBenchmarkTest {
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun tenThousandAddressesAtOneHertzStayUnderFivePercentOfOneCore() {
        val count = 10_000
        val clock = ManualSimulatorClock(0)
        val instructions = ArrayList<SimInstruction>(count)
        for (i in 0 until count) {
            val (source, type) = when (i % 5) {
                0 -> "ramp(0,100,5,true)" to "Int32"
                1 -> "sine(0,100,10,true)" to "Double"
                2 -> "random(0,1000,true)" to "Int32"
                3 -> "list(1,2,3,4,5,true)" to "Int32"
                else -> (i % 47).toString() to "Int32"
            }
            instructions += SimInstruction(0, "p$i", source, type)
        }
        val device = SimulatorDevice(
            SimulatorDeviceConfig(
                name = "bench",
                repeatProgram = true,
                baseRateMs = 1_000,
                randomSeed = 1,
                instructions = instructions,
            ),
            clock,
        )
        device.use {
            val seen = IntArray(count)
            var checksum = 0L
            for (i in 0 until count) {
                val index = i
                it.attach(1_000, "p$index") { value, _, _ ->
                    seen[index]++
                    if (value is Number) checksum += value.toLong()
                }
            }
            val cpu = ManagementFactory.getThreadMXBean()
            assertTrue(cpu.isCurrentThreadCpuTimeSupported)
            fun step() {
                clock.millis += 1_000
                it.scan(1_000)
            }
            val warmup = 3
            repeat(warmup) { step() }
            var best = Long.MAX_VALUE
            val measured = 5
            repeat(measured) {
                val start = cpu.currentThreadCpuTime
                step()
                val elapsed = cpu.currentThreadCpuTime - start
                if (elapsed < best) best = elapsed
            }
            val millis = best / 1_000_000.0
            val percent = millis / 10.0
            println("BENCH 10000 simulator addresses, one 1s tick: cpu=${"%.3f".format(millis)}ms (${"%.3f".format(percent)}% of one core)")
            assertTrue(best < 50_000_000L, "CPU ${"%.3f".format(millis)}ms is ${"%.3f".format(percent)}% of one core, not under 5%")
            assertTrue(seen.all { it >= 1 + warmup + measured })
            // Tick 0 was the initial publish. warmup + measured steps later the ramp (period 5) is at k = 8 -> 75.
            assertEquals(75, it.read("p0").value)
            assertTrue(checksum != 0L)
        }
    }
}
