package org.oip.simulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.ValidationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Exact program sequences over a manual clock.
 *
 * Formulas are the interpretations in SimulatorProgram.kt of
 */
class SimulatorDeviceTest {
    @Test fun sineDefaultsTraceAFullWaveAndDoNotResetEveryInterval() {
        val values = doubles("sine()", 12)
        val explicit = doubles("sine(0,100,10,true)", 12)
        assertEquals(explicit, values)
        for (k in values.indices) {
            assertEquals(sine(k), values[k], 0.0, "k=$k")
        }
        assertEquals(0.0, values[0], 0.0)
        assertEquals(100.0 * sin(PI * 0.5), values[5], 0.0)
        assertEquals(0.0, values[10], 0.0)
        assertEquals(values[1], values[11], 0.0)
        assertNotEquals(values[0], values[1])
    }

    @Test fun rampReachesMaxThenResetsToMin() {
        // period 5: steps 0, 25, 50, 75, 100, then min again.
        assertEquals(listOf(0, 25, 50, 75, 100, 0, 25), ints("ramp(0,100,5,true)", 7))
    }

    @Test fun defaultRampUsesZeroToOneHundredOverTenIntervals() {
        val values = doubles("ramp()", 11)
        for (k in values.indices) assertEquals(ramp(k, 0.0, 100.0, 10), values[k], 0.0, "k=$k")
        assertEquals(0.0, values[0], 0.0)
        assertEquals(100.0, values[9], 0.0)
        assertEquals(0.0, values[10], 0.0)
    }

    @Test fun listWalksAndWraps() {
        assertEquals(listOf(2, 4, 6, 2, 4), ints("list(2, 4, 6, true)", 5))
        assertEquals(listOf(2, 4, 6, 2), ints("list(2,4,6)", 4))
    }

    @Test fun listRepeatFalsePublishesTheFirstValueOnce() {
        val clock = ManualSimulatorClock(0)
        val device = device(
            clock,
            repeat = true,
            rows = listOf(
                row(0, "A", "list(10, 20, 30, false)", "Int32"),
                row(3, "Other", "1", "Int32"),
            ),
        )
        device.use {
            assertEquals(10, it.read("A").value)
            assertEquals(0L, it.write("A", 99).quality.statusCode)
            clock.millis = 1_000
            assertEquals(99, it.read("A").value)
            clock.millis = 2_000
            assertEquals(99, it.read("A").value)
            clock.millis = 4_000 // interval 0 of the next cycle fires the instruction again
            assertEquals(10, it.read("A").value)
        }
    }

    @Test fun randomIntegersMatchJavaUtilRandomForTheAddressSeed() {
        val expected = java.util.Random(mix(1L, "Rand")).let { rng ->
            List(6) { rng.nextInt(11) }
        }
        assertEquals(expected, ints("random(0,10,true)", 6, path = "Rand"))
    }

    @Test fun randomDoublesMatchNextDoubleAndBooleansIgnoreMinAndMax() {
        val seed = mix(7L, "D")
        val rng = java.util.Random(seed)
        val expected = List(4) { 5.0 + rng.nextDouble() * 5.0 }
        val values = doubles("random(5, 10, true)", 4, path = "D", seed = 7)
        for (k in expected.indices) assertEquals(expected[k], values[k], 0.0, "k=$k")

        val flags = java.util.Random(mix(3L, "B")).let { bits -> List(5) { bits.nextBoolean() } }
        assertEquals(flags, bools("random(0,1,true)", 5, path = "B", seed = 3))
    }

    @Test fun constantHoldsUntilItsNextInstructionAndAWriteSticksBetweenThem() {
        val clock = ManualSimulatorClock(0)
        val device = device(
            clock,
            repeat = true,
            rows = listOf(
                row(0, "Const", "5", "Int32"),
                row(2, "Const", "8", "Int32"),
            ),
        )
        device.use {
            assertEquals(5, it.read("Const").value)
            assertEquals(0L, it.write("Const", 42).quality.statusCode)
            assertEquals(42, it.read("Const").value)
            clock.millis = 1_000
            assertEquals(42, it.read("Const").value)
            clock.millis = 2_000
            assertEquals(8, it.read("Const").value)
            clock.millis = 3_000 // wrap to interval 0
            assertEquals(5, it.read("Const").value)
        }
    }

    @Test fun repeatingSpanOfOneRepublishesAConstantAndReplacesAWrite() {
        // 8.3: a write lasts until the program next publishes that address. A user program whose
        // only row is interval 0 has span 1, so each base rate publishes the constant again.
        // doc source: FEATURES DEV-028
        val clock = ManualSimulatorClock(0)
        val device = device(clock, repeat = true, rows = listOf(row(0, "A", "5", "Int32")))
        device.use {
            assertEquals(5, it.read("A").value)
            assertEquals(0L, it.write("A", 9).quality.statusCode)
            clock.millis = 500 // still inside interval 0
            assertEquals(9, it.read("A").value)
            clock.millis = 1_000
            assertEquals(5, it.read("A").value)
        }
    }

    @Test fun constantFunctionMatchesALiteral() {
        assertEquals(listOf(7, 7, 7), ints("constant(7)", 3))
        assertEquals(listOf("pump", "pump"), strings("\"pump\"", 2))
    }

    @Test fun writeToARepeatingSineLastsUntilTheNextInterval() {
        val clock = ManualSimulatorClock(0)
        val device = device(clock, rows = listOf(row(0, "A", "sine()", "Double")))
        device.use {
            assertEquals(sine(0), it.read("A").value as Double, 0.0)
            assertEquals(0L, it.write("A", 123.0).quality.statusCode)
            assertEquals(123.0, it.read("A").value as Double, 0.0)
            clock.millis = 500 // still interval 0
            assertEquals(123.0, it.read("A").value as Double, 0.0)
            clock.millis = 1_000
            assertEquals(sine(1), it.read("A").value as Double, 0.0)
        }
    }

    @Test fun badWriteDoesNotChangeTheValueAndUnknownAddressesAreNotFound() {
        val device = device(rows = listOf(row(0, "Const", "5", "Int32")))
        device.use {
            val rejected = it.write("Const", "nope")
            assertEquals(0x80740000, rejected.quality.statusCode)
            assertEquals(5, it.read("Const").value)
            assertEquals(0L, it.read("Const").quality.statusCode)
            val missing = it.read("Other")
            assertEquals(0x803E0000, missing.quality.statusCode)
            assertEquals(null, missing.value)
            assertEquals(0x803E0000, it.write("Other", 1).quality.statusCode)
        }
    }

    @Test fun addressExistsAtZeroUntilItsFirstInstruction() {
        val clock = ManualSimulatorClock(0)
        val device = device(
            clock,
            repeat = true,
            rows = listOf(row(4, "TagC", "100", "Int32")),
        )
        device.use {
            assertEquals(listOf(0, 0, 0, 0, 100, 100), readInts(it, clock, "TagC", 6))
        }
    }

    @Test fun switchingFromSineToRampStartsTheRampAndRepeatRestartsSine() {
        // Shortened from the page's advanced program. TagA is sine until interval 5, then ramp.
        // The next cycle's interval 0 selects sine again, which starts over.
        val clock = ManualSimulatorClock(0)
        val device = device(
            clock,
            repeat = true,
            rows = listOf(
                row(0, "TagA", "sine()", "Double"),
                row(0, "TagB", "0", "Int32"),
                row(3, "TagB", "4", "Int32"),
                row(4, "TagC", "100", "Int32"),
                row(5, "TagA", "ramp(0,100,5,true)", "Double"),
            ),
        )
        device.use {
            val tagA = readDoubles(it, clock, "TagA", 8)
            for (k in 0..4) assertEquals(sine(k), tagA[k], 0.0, "sine k=$k")
            assertEquals(0.0, tagA[5], 0.0) // ramp starts at min
            assertEquals(sine(0), tagA[6], 0.0) // interval 0 again, sine replaces ramp
            assertEquals(sine(1), tagA[7], 0.0)

            val later = ManualSimulatorClock(0)
            val fresh = device(later, repeat = true, rows = listOf(
                row(0, "TagB", "0", "Int32"),
                row(3, "TagB", "4", "Int32"),
                row(4, "TagC", "100", "Int32"),
                row(5, "Hold", "1", "Int32"),
            ))
            fresh.use { sim ->
                assertEquals(0, sim.read("TagC").value)
                later.millis = 4_000
                assertEquals(100, sim.read("TagC").value)
                assertEquals(4, sim.read("TagB").value)
                assertEquals(0L, sim.write("TagB", 99).quality.statusCode)
                later.millis = 5_000
                assertEquals(99, sim.read("TagB").value)
                later.millis = 6_000 // interval 0 sets TagB back to 0
                assertEquals(0, sim.read("TagB").value)
                assertEquals(100, sim.read("TagC").value)
            }
        }
    }

    @Test fun programThatDoesNotRepeatHoldsTheLastValue() {
        val clock = ManualSimulatorClock(0)
        val device = device(
            clock,
            repeat = false,
            rows = listOf(
                row(0, "Const", "1", "Int32"),
                row(2, "Const", "3", "Int32"),
            ),
        )
        device.use {
            assertEquals(listOf(1, 1, 3, 3, 3), readInts(it, clock, "Const", 5))
            assertEquals(0L, it.write("Const", 9).quality.statusCode)
            clock.millis = 9_000
            assertEquals(9, it.read("Const").value)
        }
    }

    @Test fun browsePathIsCaseSensitive() {
        val device = device(rows = listOf(row(0, "Tank/Level", "42", "Int32")))
        device.use {
            assertEquals(42, it.read("Tank/Level").value)
            assertEquals(0x803E0000, it.read("tank/level").quality.statusCode)
        }
    }

    @Test fun unknownFunctionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            device(rows = listOf(row(0, "A", "noSuch()", "Double")))
        }
    }

    @Test fun schemaRejectsAnUnknownFieldAndAppliesDefaults() {
        val registry = SchemaRegistry()
        registry.register(SimulatorDriverModule.MODULE_ID, DEVICE_KIND, simulatorDeviceSchema())
        val spec = mapOf(
            "repeatProgram" to false,
            "instructions" to listOf(
                mapOf("timeInterval" to 0, "browsePath" to "A", "valueSource" to "sine()", "dataType" to "Double"),
            ),
        )
        val applied = registry.applyDefaults(SimulatorDriverModule.MODULE_ID, DEVICE_KIND, spec)
        val config = simulatorDeviceConfig("gensim", applied)
        assertEquals(1_000L, config.baseRateMs)
        assertEquals(1L, config.randomSeed)
        assertEquals(false, config.repeatProgram)
        assertEquals("sine()", config.instructions.single().valueSource)
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(
                DEVICE_KIND,
                Metadata("gensim", SimulatorDriverModule.MODULE_ID),
                spec + ("nope" to true),
            ))
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun schedulerPublishesALaterRampSample() {
        val device = SimulatorDevice(SimulatorDeviceConfig(
            name = "live",
            repeatProgram = true,
            baseRateMs = 40,
            instructions = listOf(SimInstruction(0, "R", "ramp(0,100,5,true)", "Int32")),
        ))
        val seen = CopyOnWriteArrayList<Any?>()
        device.use {
            it.start()
            it.attach(20, "R") { value, quality, _ ->
                assertEquals(0L, quality.statusCode)
                seen.add(value)
            }
            val end = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < end && 25 !in seen) Thread.sleep(20)
            assertTrue(seen.contains(25), "scheduler samples: $seen")
        }
    }

    private fun sine(k: Int, min: Double = 0.0, max: Double = 100.0, period: Int = 10): Double {
        val km = Math.floorMod(k, period)
        return min + (max - min) * sin(PI * km.toDouble() / period)
    }

    private fun ramp(k: Int, min: Double, max: Double, period: Int): Double {
        if (period <= 1) return min
        val km = Math.floorMod(k, period)
        return min + (max - min) * (km.toDouble() / (period - 1))
    }

    /** Same mix as [mixSeed]. Duplicated so a seed change fails this test. */
    private fun mix(deviceSeed: Long, browsePath: String): Long {
        var mixed = deviceSeed
        for (ch in browsePath) mixed = 31L * mixed + ch.code
        return mixed
    }

    private fun ints(source: String, ticks: Int, path: String = "A", seed: Long = 1): List<Int> =
        readAll(source, ticks, path, "Int32", seed).map { it as Int }

    private fun doubles(source: String, ticks: Int, path: String = "A", seed: Long = 1): List<Double> =
        readAll(source, ticks, path, "Double", seed).map { it as Double }

    private fun strings(source: String, ticks: Int, path: String = "A"): List<String> =
        readAll(source, ticks, path, "String", 1).map { it as String }

    private fun bools(source: String, ticks: Int, path: String, seed: Long): List<Boolean> =
        readAll(source, ticks, path, "Boolean", seed).map { it as Boolean }

    private fun readAll(source: String, ticks: Int, path: String, type: String, seed: Long): List<Any> {
        val clock = ManualSimulatorClock(0)
        device(clock, seed = seed, rows = listOf(row(0, path, source, type))).use { device ->
            return readValues(device, clock, path, ticks)
        }
    }

    private fun readInts(device: SimulatorDevice, clock: ManualSimulatorClock, path: String, ticks: Int): List<Int> =
        readValues(device, clock, path, ticks).map { it as Int }

    private fun readDoubles(device: SimulatorDevice, clock: ManualSimulatorClock, path: String, ticks: Int): List<Double> =
        readValues(device, clock, path, ticks).map { it as Double }

    private fun readValues(device: SimulatorDevice, clock: ManualSimulatorClock, path: String, ticks: Int): List<Any> {
        val out = ArrayList<Any>(ticks)
        for (k in 0 until ticks) {
            clock.millis = k * 1_000L
            val sample = device.read(path)
            assertEquals(0L, sample.quality.statusCode, sample.quality.diagnostic)
            out += sample.value ?: error("null at $k")
        }
        return out
    }

    private fun device(
        clock: ManualSimulatorClock = ManualSimulatorClock(0),
        repeat: Boolean = true,
        seed: Long = 1,
        rows: List<SimInstruction>,
    ) = SimulatorDevice(
        SimulatorDeviceConfig(
            name = "sim",
            repeatProgram = repeat,
            baseRateMs = 1_000,
            randomSeed = seed,
            instructions = rows,
        ),
        clock,
    )

    private fun row(interval: Int, path: String, source: String, type: String) =
        SimInstruction(interval, path, source, type)
}
