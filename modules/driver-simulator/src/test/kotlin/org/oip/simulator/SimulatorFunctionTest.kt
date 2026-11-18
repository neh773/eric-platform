package org.oip.simulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * Sequences for the value sources added after ramp, sine, random, list, and constant.
 *
 * Formulas are the interpretations in SimulatorProgram.kt of
 * The sine shape (low, high, low over one period) is also the 7.9 generic simulator description:
 */
class SimulatorFunctionTest {
    @Test fun cosineDefaultsStartAtMaxReachMinAndReturnToMax() {
        val values = doubles("cosine()", 12)
        val explicit = doubles("cosine(0, 100, 10, true)", 12)
        assertEquals(explicit, values)
        for (k in values.indices) assertEquals(cosine(k), values[k], 0.0, "k=$k")
        assertEquals(100.0, values[0], 0.0)
        assertEquals(0.0, values[5], 0.0)
        assertEquals(100.0, values[10], 0.0)
        assertEquals(values[1], values[11], 0.0)
        assertNotEquals(values[0], values[1])
    }

    @Test fun squareIsHighForTheFirstHalfThenLow() {
        assertEquals(listOf(100, 100, 100, 100, 100, 0, 0, 0, 0, 0, 100, 100), ints("square()", 12))
        assertEquals(listOf(100, 100, 100, 100, 100, 0, 0, 0, 0, 0), ints("square(0,100,10,true)", 10))
        // Odd period: the extra sample is on the high side (km * 2 < period).
        assertEquals(listOf(8, 8, 8, 1, 1, 8), ints("square(1, 8, 5, true)", 6))
        assertEquals(listOf(100), ints("square()", 1))
    }

    @Test fun triangleRisesToMaxAtMidpointThenFallsToMin() {
        assertEquals(listOf(0, 20, 40, 60, 80, 100, 80, 60, 40, 20, 0, 20), ints("triangle()", 12))
        val values = doubles("triangle(0,100,10,true)", 11)
        for (k in values.indices) assertEquals(triangle(k), values[k], 0.0, "k=$k")
        assertEquals(0.0, doubles("triangle(0,100,1,true)", 1).single(), 0.0)
    }

    @Test fun realisticDefaultPidStepsFromZeroTowardTheSetPoint() {
        val values = doubles("realistic()", 6)
        val explicit = doubles("realistic(100, 1.2, 0.06, 0.25, true)", 6)
        assertEquals(explicit, values)
        val expected = pid(6)
        for (k in expected.indices) assertEquals(expected[k], values[k], 1e-9, "k=$k")
        // First step: error 100, integral 100, derivative 0, output 126, pv 126 * 0.05.
        assertEquals(6.3, values[0], 1e-9)
        assertNotEquals(values[0], values[1])
    }

    @Test fun realisticGainsAreIndependent() {
        val proportionOnly = doubles("realistic(100, 1.2, 0, 0, true)", 4)
        for (k in proportionOnly.indices) {
            assertEquals(pid(4, proportion = 1.2, integral = 0.0, derivative = 0.0)[k], proportionOnly[k], 1e-9)
        }
        // Zero gains leave the process value at 0. Integral moves the default off the proportion-only path.
        assertEquals(listOf(0.0, 0.0, 0.0), doubles("realistic(100, 0, 0, 0, true)", 3))
        assertNotEquals(proportionOnly[0], doubles("realistic()", 1).single())
        assertEquals(6.0, proportionOnly[0], 0.0)
    }

    @Test fun realisticRepeatFalseHoldsTheFirstStepUntilTheInstructionFiresAgain() {
        val clock = ManualSimulatorClock(0)
        val device = device(
            clock,
            rows = listOf(
                row(0, "A", "realistic(100, 1.2, 0.06, 0.25, false)", "Double"),
                row(3, "Other", "1", "Int32"),
            ),
        )
        device.use {
            val first = pid(1).single()
            assertEquals(first, it.read("A").value as Double, 0.0)
            clock.millis = 2_000
            assertEquals(first, it.read("A").value as Double, 0.0)
            clock.millis = 4_000
            assertEquals(first, it.read("A").value as Double, 0.0)
        }
    }

    @Test fun qvPublishesTheValueAndTheNamedQualityUntilTheNextInstruction() {
        val clock = ManualSimulatorClock(0)
        val device = device(
            clock,
            rows = listOf(
                row(0, "A", "qv(5, Bad)", "Int32"),
                row(2, "A", "8", "Int32"),
                row(0, "U", "qv(1.5, Uncertain)", "Double"),
                row(0, "G", "qv(3)", "Int32"),
                row(0, "S", "qv(\"fault\", bad)", "String"),
            ),
        )
        device.use {
            val bad = it.read("A")
            assertEquals(5, bad.value)
            assertEquals(0x80000000, bad.quality.statusCode)
            assertEquals(0x40000000, it.read("U").quality.statusCode)
            assertEquals(1.5, it.read("U").value as Double, 0.0)
            val good = it.read("G")
            assertEquals(3, good.value)
            assertEquals(0L, good.quality.statusCode)
            assertEquals("fault", it.read("S").value)
            assertEquals(0x80000000, it.read("S").quality.statusCode)

            assertEquals(0L, it.write("A", 9).quality.statusCode)
            assertEquals(9, it.read("A").value)
            assertEquals(0L, it.read("A").quality.statusCode)

            clock.millis = 1_000
            assertEquals(9, it.read("A").value)
            assertEquals(0L, it.read("A").quality.statusCode)
            clock.millis = 2_000
            assertEquals(8, it.read("A").value)
            assertEquals(0L, it.read("A").quality.statusCode)
        }
    }

    @Test fun readonlyRejectsWritesUntilALaterInstructionReplacesIt() {
        val clock = ManualSimulatorClock(0)
        val device = device(
            clock,
            rows = listOf(
                row(0, "A", "readonly(5)", "Int32"),
                row(2, "A", "8", "Int32"),
                row(0, "S", "readonly(\"locked\")", "String"),
            ),
        )
        device.use {
            assertEquals(5, it.read("A").value)
            val rejected = it.write("A", 9)
            assertEquals(0x803B0000, rejected.quality.statusCode)
            assertEquals(5, it.read("A").value)
            assertEquals(0x803B0000, it.write("S", "nope").quality.statusCode)
            assertEquals("locked", it.read("S").value)
            clock.millis = 2_000
            assertEquals(8, it.read("A").value)
            assertEquals(0L, it.write("A", 9).quality.statusCode)
            assertEquals(9, it.read("A").value)
        }
    }

    @Test fun unknownQualityAndBlankReadonlyAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            device(rows = listOf(row(0, "A", "qv(1, Horrible)", "Int32")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            device(rows = listOf(row(0, "A", "readonly()", "Int32")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            device(rows = listOf(row(0, "A", "realistic(1, 2, 3, 4, true, 5)", "Double")))
        }
    }

    private fun cosine(k: Int, min: Double = 0.0, max: Double = 100.0, period: Int = 10): Double {
        val km = Math.floorMod(k, period)
        val mid = (min + max) / 2.0
        val amp = (max - min) / 2.0
        return mid + amp * cos(2.0 * PI * km.toDouble() / period)
    }

    private fun triangle(k: Int, min: Double = 0.0, max: Double = 100.0, period: Int = 10): Double {
        if (period <= 1) return min
        val km = Math.floorMod(k, period).toDouble()
        val half = period / 2.0
        val frac = if (km <= half) km / half else (period - km) / half
        return min + (max - min) * frac
    }

    /**
     * Same recurrence as [RealisticGen]: one step per sample, derivative is 0 on the first step,
     * plant gain 0.05. Duplicated so a gain or step change fails this test.
     */
    private fun pid(
        steps: Int,
        setPoint: Double = 100.0,
        proportion: Double = 1.2,
        integral: Double = 0.06,
        derivative: Double = 0.25,
    ): List<Double> {
        var pv = 0.0
        var sum = 0.0
        var lastError = 0.0
        var have = false
        return List(steps) {
            val error = setPoint - pv
            sum += error
            val deriv = if (!have) 0.0 else error - lastError
            have = true
            val output = proportion * error + integral * sum + derivative * deriv
            pv += output * 0.05
            lastError = error
            pv
        }
    }

    private fun ints(source: String, ticks: Int): List<Int> =
        readAll(source, ticks, "Int32").map { it as Int }

    private fun doubles(source: String, ticks: Int): List<Double> =
        readAll(source, ticks, "Double").map { it as Double }

    private fun readAll(source: String, ticks: Int, type: String): List<Any> {
        val clock = ManualSimulatorClock(0)
        device(clock, rows = listOf(row(0, "A", source, type))).use { device ->
            val out = ArrayList<Any>(ticks)
            for (k in 0 until ticks) {
                clock.millis = k * 1_000L
                val sample = device.read("A")
                assertEquals(0L, sample.quality.statusCode, sample.quality.diagnostic)
                out += sample.value ?: error("null at $k")
            }
            return out
        }
    }

    private fun device(
        clock: ManualSimulatorClock = ManualSimulatorClock(0),
        rows: List<SimInstruction>,
    ) = SimulatorDevice(
        SimulatorDeviceConfig(name = "sim", repeatProgram = true, baseRateMs = 1_000, instructions = rows),
        clock,
    )

    private fun row(interval: Int, path: String, source: String, type: String) =
        SimInstruction(interval, path, source, type)
}
