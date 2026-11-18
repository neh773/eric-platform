package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Times structured log emit. The ceiling is a loose anti-regression gate; the printed line is the measurement. */
class LoggingBenchmarkTest {
    @TempDir lateinit var tmp: Path

    @Test fun emitStaysUnderCeiling() {
        val logs = StructuredLog(tmp)
        repeat(1_000) { logs.log(LogLevel.INFO, "bench", "warmup") }
        val n = 20_000
        val samples = LongArray(n)
        val t0 = System.nanoTime()
        repeat(n) { i ->
            val s = System.nanoTime()
            logs.log(LogLevel.INFO, "bench", "m$i")
            samples[i] = System.nanoTime() - s
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        samples.sort()
        val p99us = samples[(n * 0.99).toInt().coerceAtMost(n - 1)] / 1_000
        println("BENCH log.emit n=$n elapsedMs=$elapsedMs p99Micros=$p99us")
        assertTrue(elapsedMs < 5_000, "log emit took ${elapsedMs}ms")
        assertTrue(p99us < 20_000, "log emit p99 was ${p99us}us")
        logs.close()
    }
}
