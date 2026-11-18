package org.oip.testinfra

import kotlin.math.floor
import kotlin.math.sin

/**
 * Deterministic engineering value shared with the Python and Node simulators.
 *
 * phase = floorMod(nowMs - epochMs, periodMs) / periodMs, in [0, 1)
 * constant: offset
 * ramp:     offset + amplitude * phase
 * sine:     offset + amplitude * sin(2π * phase)
 * step:     offset, or offset + amplitude once phase >= 0.5
 */
object Waveform {
    const val MAX_PERIOD_MS = 65_535L

    enum class Mode {
        CONSTANT, RAMP, SINE, STEP;

        val json: String get() = name.lowercase()

        companion object {
            fun fromJson(text: String): Mode = when (text.lowercase()) {
                "constant" -> CONSTANT
                "ramp" -> RAMP
                "sine" -> SINE
                "step" -> STEP
                else -> throw IllegalArgumentException("invalid mode: $text")
            }
        }
    }

    fun value(mode: Mode, nowMs: Long, epochMs: Long, periodMs: Long, amplitude: Double, offset: Double): Double {
        require(periodMs > 0) { "periodMs must be > 0" }
        val phase = Math.floorMod(nowMs - epochMs, periodMs).toDouble() / periodMs.toDouble()
        return when (mode) {
            Mode.CONSTANT -> offset
            Mode.RAMP -> offset + amplitude * phase
            Mode.SINE -> offset + amplitude * sin(2.0 * Math.PI * phase)
            Mode.STEP -> offset + if (phase >= 0.5) amplitude else 0.0
        }
    }

    /** int16 engineering×100 as a uint16 bit pattern. Rounding matches Java Math.round (floor(x+0.5)). */
    fun toRegister(engineering: Double): Int {
        var scaled = floor(engineering * 100.0 + 0.5).toLong()
        if (scaled > 32_767) scaled = 32_767
        if (scaled < -32_768) scaled = -32_768
        return scaled.toInt() and 0xFFFF
    }

    fun fromRegister(word: Int): Double = toInt16(word) / 100.0

    fun toInt16(word: Int): Int {
        val bits = word and 0xFFFF
        return if (bits and 0x8000 != 0) bits - 0x10000 else bits
    }
}
