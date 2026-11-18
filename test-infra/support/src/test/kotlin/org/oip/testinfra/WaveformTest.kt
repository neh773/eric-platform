package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.charset.StandardCharsets

class WaveformTest {
    @Test fun rampStepAndConstantAreExact() {
        assertEquals(0.0, Waveform.value(Waveform.Mode.RAMP, 0, 0, 1000, 100.0, 0.0))
        assertEquals(25.0, Waveform.value(Waveform.Mode.RAMP, 250, 0, 1000, 100.0, 0.0))
        assertEquals(10.0, Waveform.value(Waveform.Mode.CONSTANT, 250, 0, 1000, 100.0, 10.0))
        assertEquals(5.0, Waveform.value(Waveform.Mode.STEP, 499, 0, 1000, 80.0, 5.0))
        assertEquals(85.0, Waveform.value(Waveform.Mode.STEP, 500, 0, 1000, 80.0, 5.0))
    }

    @Test fun negativeElapsedUsesFloorMod() {
        // now=0, epoch=250, period=1000 -> elapsed -250 -> phase 0.75
        assertEquals(75.0, Waveform.value(Waveform.Mode.RAMP, 0, 250, 1000, 100.0, 0.0))
        assertEquals(-100.0, Waveform.value(Waveform.Mode.SINE, 0, 250, 1000, 100.0, 0.0), 1e-9)
    }

    @Test fun sineAtQuarterPeriodIsAmplitude() {
        val value = Waveform.value(Waveform.Mode.SINE, 250, 0, 1000, 100.0, 0.0)
        assertEquals(100.0, value, 1e-9)
        assertEquals(10_000, Waveform.toRegister(value))
    }

    @Test fun registerRoundingMatchesJavaMathRoundAndSaturates() {
        assertEquals(0, Waveform.toInt16(Waveform.toRegister(0.0)))
        assertEquals(-10000, Waveform.toInt16(Waveform.toRegister(-100.0)))
        assertEquals(1, Waveform.toInt16(Waveform.toRegister(0.005)))
        assertEquals(0, Waveform.toInt16(Waveform.toRegister(-0.005)))
        assertEquals(32767, Waveform.toInt16(Waveform.toRegister(10_000.0)))
        assertEquals(-32768, Waveform.toInt16(Waveform.toRegister(-10_000.0)))
    }

    @Test fun periodMustBePositive() {
        assertThrows<IllegalArgumentException> {
            Waveform.value(Waveform.Mode.RAMP, 0, 0, 0, 1.0, 0.0)
        }
    }

    @Test fun pythonWaveformMatches() {
        val cases = listOf(
            mapOf("mode" to "ramp", "now_ms" to 250, "epoch_ms" to 0, "period_ms" to 1000, "amplitude" to 100.0, "offset" to 0.0),
            mapOf("mode" to "step", "now_ms" to 500, "epoch_ms" to 0, "period_ms" to 1000, "amplitude" to 80.0, "offset" to 5.0),
            mapOf("mode" to "constant", "now_ms" to 10, "epoch_ms" to 0, "period_ms" to 1000, "amplitude" to 3.0, "offset" to 4.0),
            mapOf("mode" to "sine", "now_ms" to 250, "epoch_ms" to 0, "period_ms" to 1000, "amplitude" to 100.0, "offset" to 0.0),
            mapOf("mode" to "ramp", "now_ms" to 0, "epoch_ms" to 250, "period_ms" to 1000, "amplitude" to 100.0, "offset" to 0.0),
        )
        val script = File(repoRoot(), "test-infra/sim/common/waveform.py")
        val payload = cases.joinToString(prefix = "[", postfix = "]") { case ->
            case.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                val rendered = if (v is String) "\"$v\"" else v.toString()
                "\"$k\": $rendered"
            }
        }
        val process = ProcessBuilder(Python.command(), script.path)
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        val code = process.waitFor()
        assertEquals(0, code, output)
        val numbers = output.trim().removePrefix("[").removeSuffix("]").split(",").map { it.trim().toDouble() }
        cases.forEachIndexed { i, case ->
            val expected = Waveform.value(
                Waveform.Mode.fromJson(case["mode"] as String),
                (case["now_ms"] as Int).toLong(),
                (case["epoch_ms"] as Int).toLong(),
                (case["period_ms"] as Int).toLong(),
                case["amplitude"] as Double,
                case["offset"] as Double,
            )
            val delta = if (case["mode"] == "sine") 1e-9 else 0.0
            assertEquals(expected, numbers[i], delta, "case $case python=$output")
        }
    }
}
