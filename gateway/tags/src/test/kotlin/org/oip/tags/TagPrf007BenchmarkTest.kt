package org.oip.tags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * PRF-007 (ADR 0010): 100_000 in-memory tags at 1 Hz, 100% changing, subscription fan-out
 * included, in under 25% of one core. CPU time under 250 ms for one 1 s scan is a
 * cpuFractionOfOneCore under 0.25. The legacy 10_000 scan in [TagBenchmarkTest] is not this test.
 *
 * A window repeats the scan until it has run at least [MIN_SCANS] times and [MIN_WINDOW_NS]
 * of wall time, so one noisy sample cannot decide the result. Seven windows; the ceiling is
 * applied to the best, the same shape as SecurityBenchmarkTest's best of 7 batches.
 * The ceiling stays 0.25. Accepted baseline (cpuFractionOfOneCore, lower is better): 0.0103.
 * That number is PRF-007 result.baseline in test-infra/perf/perf-requirements.json.
 * PRF-013 fails a later same-hardware run only when it is more than 15% worse. The test asserts
 * that against the baseline for its hardware class ([Prf013]). 0.0103 was measured in an agent
 * container (class ci); other classes can be added to `result.runnerBaselines`.
 */
class TagPrf007BenchmarkTest {
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    fun hundredThousandMemoryTagsAtOneHertzStayUnderAQuarterCore() {
        val os = ManagementFactory.getOperatingSystemMXBean()
        assertTrue(os is com.sun.management.OperatingSystemMXBean, "process CPU time needs a HotSpot OperatingSystemMXBean")
        val process = os as com.sun.management.OperatingSystemMXBean
        process.processCpuTime

        val engine = TagEngine()
        val zero = 0.0
        repeat(TAGS) { i ->
            engine.configure(TagConfig(
                path = "m$i", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = zero, valueSet = true,
            ))
        }
        val samples = Array(TAGS) { engine.openSample("m$it") }
        val counts = LongArray(SUBSCRIBERS)
        repeat(SUBSCRIBERS) { s ->
            repeat(TAGS) { i ->
                engine.subscribe("m$i") { counts[s]++ }
            }
        }

        var seq = 1.0
        fun scan(value: Double) {
            val stamp = Instant.ofEpochMilli(System.currentTimeMillis())
            var i = 0
            while (i < TAGS) {
                samples[i].set(value, stamp)
                i++
            }
        }
        repeat(WARMUP_SCANS) {
            scan(seq)
            seq += 1.0
        }
        for (s in counts.indices) counts[s] = 0
        System.gc()

        val windows = Array(WINDOWS) { window(process, ::scan, seq + it * 10_000.0) }
        val best = windows.minBy { it.fraction }
        val ordered = windows.sortedBy { it.fraction }
        val median = ordered[ordered.size / 2].fraction
        val shown = windows.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it.fraction) }
        println(
            "PRF-007 cpuFractionOfOneCore=${"%.4f".format(best.fraction)} " +
                "bestCpuMs=${"%.2f".format(best.cpuNs / 1_000_000.0 / best.scans)} " +
                "medianCpuFraction=${"%.4f".format(median)} " +
                "windows=$shown scansPerWindow=${windows.joinToString(",") { it.scans.toString() }} " +
                "wallMsPerScan=${"%.2f".format(best.wallNs / 1_000_000.0 / best.scans)} " +
                "gcInBest=${best.gcDelta} tags=$TAGS subscribers=$SUBSCRIBERS",
        )
        val notified = counts[0]
        assertEquals(notified, counts[SUBSCRIBERS - 1], "subscription fan-out delivered a different count to the last listener")
        assertEquals(windows.sumOf { it.scans }.toLong() * TAGS, notified)
        assertEquals(seq + (WINDOWS - 1) * 10_000.0 + windows[WINDOWS - 1].scans - 1.0, (samples[TAGS - 1].read().value as Number).toDouble())
        assertTrue(best.fraction < CEILING, "best window ${"%.4f".format(best.fraction)} of $shown is not under $CEILING of one core")
        assertTrue(best.cpuNs / best.scans > 1_000_000L, "process CPU clock did not move")
        assertNoRegression(best.fraction)
    }

    /** PRF-013 against the accepted baseline measured on this run's hardware class (review-2 F11). */
    private fun assertNoRegression(measured: Double) {
        val hardware = Prf013.hardwareClass()
        val baseline = Prf013.applicable("PRF-007", hardware)
        if (baseline == null) {
            println("PRF-013 PRF-007 not applied: no accepted baseline for hardware class ${hardware ?: "(unset)"}")
            return
        }
        val worse = Prf013.worsePercent(baseline.value, measured, baseline.lowerIsBetter)
        println("PRF-013 PRF-007 hardware=${baseline.hardware} baseline=${baseline.value} measured=${"%.4f".format(measured)} worsePercent=${"%.1f".format(worse)}")
        assertFalse(
            Prf013.regressionFails(baseline.value, measured, baseline.lowerIsBetter),
            "PRF-013: PRF-007 ${"%.4f".format(measured)} is ${"%.1f".format(worse)}% worse than the ${baseline.hardware} baseline ${baseline.value}",
        )
    }

    private fun window(
        process: com.sun.management.OperatingSystemMXBean,
        scan: (Double) -> Unit,
        first: Double,
    ): Window {
        var scans = 0
        var value = first
        val gc0 = gcCount()
        val cpu0 = process.processCpuTime
        val wall0 = System.nanoTime()
        while (scans < MIN_SCANS || System.nanoTime() - wall0 < MIN_WINDOW_NS) {
            scan(value)
            value += 1.0
            scans++
            if (scans >= MAX_SCANS) break
        }
        return Window(
            cpuNs = process.processCpuTime - cpu0,
            wallNs = System.nanoTime() - wall0,
            scans = scans,
            gcDelta = gcCount() - gc0,
        )
    }

    private fun gcCount(): Long =
        ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) }

    private class Window(val cpuNs: Long, val wallNs: Long, val scans: Int, val gcDelta: Long) {
        val fraction: Double = (cpuNs.toDouble() / scans) / 1_000_000_000.0
    }

    private companion object {
        const val TAGS = 100_000
        const val SUBSCRIBERS = 4
        const val WARMUP_SCANS = 3
        const val WINDOWS = 7
        const val MIN_SCANS = 8
        const val MAX_SCANS = 400
        const val MIN_WINDOW_NS = 1_500_000_000L
        /** PRF-007: under 25% of one core. CPU time under 250 ms for one 1 s scan. */
        const val CEILING = 0.25
    }
}
