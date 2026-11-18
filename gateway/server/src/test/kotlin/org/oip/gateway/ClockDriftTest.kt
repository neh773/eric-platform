package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** ADR 0010: drift is (actualWake - expectedWake) / interval for a 1 s sleep. */
class ClockDriftTest {
    @Test fun lateWakeIsTheIntervalRatio() {
        var now = 0L
        val detector = ClockDriftDetector(
            intervalMs = 1_000,
            clock = { now },
            sleeper = { ms -> now += ms * 1_000_000L + 100_000_000L },
        )
        detector.measureOnce()
        val snap = detector.snapshot()
        assertEquals(0.10, snap["drift"] as Double, 1e-12)
        assertEquals(100.0, snap["lateMillis"] as Double, 1e-9)
        assertEquals(1L, snap["samples"])
        assertEquals(1_000L, snap["intervalMs"])
        assertFalse(ClockDriftDetector.outsideCutoff(0.10))
        assertFalse(ClockDriftDetector.outsideCutoff(-0.10))
        assertTrue(ClockDriftDetector.outsideCutoff(0.1000001))
        assertTrue(ClockDriftDetector.outsideCutoff(-0.11))
    }

    @Test fun earlyWakeIsNegative() {
        var now = 5_000_000_000L
        val detector = ClockDriftDetector(
            intervalMs = 1_000,
            clock = { now },
            sleeper = { ms -> now += ms * 1_000_000L - 50_000_000L },
        )
        detector.measureOnce()
        assertEquals(-0.05, detector.drift(), 1e-12)
        assertEquals(-50.0, detector.snapshot()["lateMillis"] as Double, 1e-9)
    }

    @Test fun detectorThreadComparesSleepWakeups() {
        val detector = ClockDriftDetector(intervalMs = 30)
        detector.start()
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            while ((detector.snapshot()["samples"] as Long) == 0L && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue((detector.snapshot()["samples"] as Long) >= 1)
            assertTrue(detector.drift().isFinite())
        } finally {
            detector.stop()
        }
    }
}

class GatewayControlTest {
    @TempDir lateinit var dir: Path

    @Test fun lockExcludesASecondAcquireUntilRelease() {
        val first = GatewayControl(dir)
        val second = GatewayControl(dir)
        assertTrue(first.tryAcquire())
        assertFalse(second.tryAcquire())
        first.publish("http://127.0.0.1:9")
        assertEquals(ProcessHandle.current().pid(), GatewayControl.readPid(dir))
        assertEquals("http://127.0.0.1:9", GatewayControl.readUrl(dir))
        first.release()
        assertEquals(null, GatewayControl.readPid(dir))
        assertEquals(null, GatewayControl.readUrl(dir))
        assertTrue(second.tryAcquire())
        second.release()
    }
}
