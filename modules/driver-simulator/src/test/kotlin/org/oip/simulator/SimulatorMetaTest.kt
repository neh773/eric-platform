package org.oip.simulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * `[Controls]` meta tags over a manual clock.
 *
 */
class SimulatorMetaTest {
    @Test fun pauseFreezesTheIntervalAndResumeContinuesTheSamePhase() {
        val clock = ManualSimulatorClock(0)
        val device = device(clock)
        device.use {
            clock.millis = 2_000
            assertEquals(sine(2), it.read("A").value as Double, 0.0)
            assertEquals(2, it.read("[Controls]/Program Counter").value)
            assertEquals(0L, it.write("[Controls]/Pause", true).quality.statusCode)
            assertEquals(true, it.read("[Controls]/Pause").value)
            clock.millis = 9_000
            assertEquals(2, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(2), it.read("A").value as Double, 0.0)
            assertEquals(0L, it.write("[Controls]/Pause", false).quality.statusCode)
            clock.millis = 10_000
            assertEquals(3, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(3), it.read("A").value as Double, 0.0)
        }
    }

    @Test fun pauseKeepsThePartialInterval() {
        val clock = ManualSimulatorClock(0)
        val device = device(clock)
        device.use {
            clock.millis = 400
            assertEquals(0, it.read("[Controls]/Program Counter").value)
            it.write("[Controls]/Pause", true)
            clock.millis = 5_000
            it.write("[Controls]/Pause", false)
            clock.millis = 5_600
            assertEquals(1, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(1), it.read("A").value as Double, 0.0)
        }
    }

    @Test fun baseRateChangeAppliesToTimeAlreadySpentAndToLaterIntervals() {
        val clock = ManualSimulatorClock(0)
        val device = device(clock)
        device.use {
            assertEquals(1_000, it.read("[Controls]/Base Rate").value)
            assertEquals(1_000L, it.config.baseRateMs)
            clock.millis = 400
            assertEquals(0L, it.write("[Controls]/Base Rate", 2_000).quality.statusCode)
            assertEquals(2_000, it.read("[Controls]/Base Rate").value)
            assertEquals(1_000L, it.config.baseRateMs)
            clock.millis = 1_400
            assertEquals(0, it.read("[Controls]/Program Counter").value)
            clock.millis = 2_400
            assertEquals(1, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(1), it.read("A").value as Double, 0.0)

            val again = ManualSimulatorClock(0)
            device(again).use { other ->
                again.millis = 800
                assertEquals(0, other.read("[Controls]/Program Counter").value)
                assertEquals(0L, other.write("[Controls]/Base Rate", 200).quality.statusCode)
                assertEquals(4, other.read("[Controls]/Program Counter").value)
                assertEquals(sine(4), other.read("A").value as Double, 0.0)
            }
        }
    }

    @Test fun rejectedBaseRateDoesNotChangeTheRunningRate() {
        val device = device(ManualSimulatorClock(0))
        device.use {
            val rejected = it.write("[Controls]/Base Rate", 0)
            assertEquals(0x80740000, rejected.quality.statusCode)
            assertEquals(1_000, it.read("[Controls]/Base Rate").value)
            assertEquals(0x80740000, it.write("[Controls]/Base Rate", "slow").quality.statusCode)
        }
    }

    @Test fun resetReturnsToIntervalZeroAndClearsItself() {
        val clock = ManualSimulatorClock(0)
        val device = device(clock)
        device.use {
            clock.millis = 3_000
            assertEquals(3, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(3), it.read("A").value as Double, 0.0)
            assertEquals(0L, it.write("[Controls]/Reset", true).quality.statusCode)
            assertEquals(false, it.read("[Controls]/Reset").value)
            assertEquals(0, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(0), it.read("A").value as Double, 0.0)
            clock.millis = 4_000
            assertEquals(1, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(1), it.read("A").value as Double, 0.0)
        }
    }

    @Test fun resetWhilePausedStaysPausedAtIntervalZero() {
        val clock = ManualSimulatorClock(0)
        val device = device(clock)
        device.use {
            clock.millis = 2_000
            it.write("[Controls]/Pause", true)
            it.write("[Controls]/Reset", true)
            assertEquals(true, it.read("[Controls]/Pause").value)
            assertEquals(0, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(0), it.read("A").value as Double, 0.0)
            clock.millis = 8_000
            assertEquals(0, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(0), it.read("A").value as Double, 0.0)
        }
    }

    @Test fun repeatMetaTagStopsAndRestartsWrapping() {
        val clock = ManualSimulatorClock(0)
        val device = device(clock, repeat = true)
        device.use {
            clock.millis = 4_000
            assertEquals(4, it.read("[Controls]/Program Counter").value)
            assertEquals(0L, it.write("[Controls]/Repeat", false).quality.statusCode)
            assertEquals(false, it.read("[Controls]/Repeat").value)
            clock.millis = 7_000
            assertEquals(4, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(4), it.read("A").value as Double, 0.0)
            it.write("[Controls]/Repeat", true)
            clock.millis = 8_000
            assertEquals(0, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(5), it.read("A").value as Double, 0.0)
        }
    }

    @Test fun programThatDoesNotRepeatStopsTheCounterOnTheLastInterval() {
        val clock = ManualSimulatorClock(0)
        val device = device(clock, repeat = false)
        device.use {
            assertEquals(false, it.read("[Controls]/Repeat").value)
            clock.millis = 9_000
            assertEquals(4, it.read("[Controls]/Program Counter").value)
            assertEquals(sine(4), it.read("A").value as Double, 0.0)
        }
    }

    @Test fun programCounterRejectsWrites() {
        val device = device(ManualSimulatorClock(0))
        device.use {
            val rejected = it.write("[Controls]/Program Counter", 3)
            assertEquals(0x803B0000, rejected.quality.statusCode)
            assertEquals(0, it.read("[Controls]/Program Counter").value)
        }
    }

    @Test fun controlsAreBrowsedAsVariables() {
        val device = device(ManualSimulatorClock(0))
        device.use {
            val root = it.browse("")
            val folder = root.first { node -> node.browseName == "[Controls]" }
            assertEquals("Object", folder.nodeClass)
            assertFalse(folder.writable)
            val controls = it.browse("[Controls]")
            assertEquals(
                listOf("Base Rate", "Pause", "Program Counter", "Repeat", "Reset"),
                controls.map { node -> node.browseName },
            )
            val counter = controls.first { node -> node.browseName == "Program Counter" }
            assertEquals("Variable", counter.nodeClass)
            assertEquals("Int32", counter.dataType)
            assertFalse(counter.writable)
            assertEquals(true, controls.first { node -> node.browseName == "Pause" }.writable)
            assertEquals("[Controls]/Base Rate", it.describe("[Controls]/Base Rate")!!.nodeId)
        }
    }

    private fun sine(k: Int): Double = sin(PI * Math.floorMod(k, 10).toDouble() / 10) * 100.0

    private fun device(clock: ManualSimulatorClock, repeat: Boolean = true) = SimulatorDevice(
        SimulatorDeviceConfig(
            name = "sim",
            repeatProgram = repeat,
            baseRateMs = 1_000,
            instructions = listOf(
                SimInstruction(0, "A", "sine()", "Double"),
                SimInstruction(4, "Hold", "1", "Int32"),
            ),
        ),
        clock,
    )
}
