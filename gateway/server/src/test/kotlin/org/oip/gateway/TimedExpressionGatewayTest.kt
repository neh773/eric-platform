package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.DataType
import org.oip.tags.ExecutionMode
import org.oip.tags.TagConfig
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * TAG-029 in a running gateway: the gateway's own ticker re-runs a fixedRate expression tag with
 * no dependency change. Execution Mode:
 *
 * The engine clock is frozen and only moves when the test advances it, so the expected timestamp
 * is exact; the test only waits (bounded) for the ticker thread to observe the advance.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TimedExpressionGatewayTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun fixedRateExpressionTagIsRestampedByTheGatewayTicker() {
        val clock = MutableClock()
        Gateway(tmp, port = 0, clock = clock).use { gw ->
            gw.start()
            gw.tags.configure(TagConfig(
                path = "Timed", type = TagType.EXPRESSION, dataType = DataType.INT32, expression = "41 + 1",
                executionMode = ExecutionMode.FIXED_RATE, executionRateMillis = 100,
            ))
            gw.tags.configure(TagConfig(path = "Plain", type = TagType.EXPRESSION, dataType = DataType.INT32, expression = "1"))
            val plain = gw.tags.read("Plain").timestamp
            for (step in 1..2) {
                clock.advance(Duration.ofSeconds(1))
                val expected = clock.instant()
                awaitStamp(gw, "Timed", expected)
                assertEquals(42, (gw.tags.read("Timed").value as Number).toInt())
            }
            assertEquals(plain, gw.tags.read("Plain").timestamp, "an event-driven tag is not touched by the ticker")
        }
    }

    private fun awaitStamp(gw: Gateway, path: String, expected: Instant) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (gw.tags.read(path).timestamp != expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(gw.tags.read(path).timestamp == expected, "ticker re-stamped $path at $expected, was ${gw.tags.read(path).timestamp}")
    }
}
