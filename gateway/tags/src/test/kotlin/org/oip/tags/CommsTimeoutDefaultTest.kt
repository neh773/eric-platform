package org.oip.tags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Review-3 F6 (lead decision): an OPC tag with no `commsTimeoutMillis` reads as comms loss only after one scan
 * period plus max(3 × scan period, 5000 ms) of silence. An on-time sample that is a little late (scheduling jitter,
 * a slow poll) never shows as comms loss, so history gets no false Bad/Good row pair. An explicit value, 0 included,
 * is used as given (DIFFERENCES 270).
 */
class CommsTimeoutDefaultTest {
    @Test fun aLateSampleWithTheDefaultConfigStaysGood() {
        val clock = ManualClock()
        val engine = TagEngine(clock)
        engine.configure(TagConfig.fromSpec(mapOf("path" to "Dev", "type" to "opc", "dataType" to "Double", "scanPeriodMillis" to 1000)))
        val qualities = ArrayList<QualityCode>()
        engine.subscribe("Dev") { qualities += it.value.quality.code }
        for (i in 1..20) {
            engine.updateRaw("Dev", i.toDouble())
            // Each sample is 50 ms late, then one comes 1.5 s late.
            clock.advanceMillis(if (i == 10) 2_500 else 1_050)
            engine.checkComms()
        }
        assertEquals(QualityCode.GOOD, engine.read("Dev").quality.code)
        assertFalse(QualityCode.BAD_NOT_CONNECTED in qualities, "no false comms loss: $qualities")
    }

    @Test fun theDefaultIsThreeScanPeriodsWithAFiveSecondFloor() {
        assertEquals(5_000L, effectiveCommsTimeoutMillis(null, 1_000))
        assertEquals(5_000L, effectiveCommsTimeoutMillis(null, 100))
        assertEquals(30_000L, effectiveCommsTimeoutMillis(null, 10_000))
        assertEquals(0L, effectiveCommsTimeoutMillis(0, 1_000), "an explicit 0 is kept")
        assertEquals(500L, effectiveCommsTimeoutMillis(500, 1_000))

        val clock = ManualClock()
        val engine = TagEngine(clock)
        engine.configure(TagConfig.fromSpec(mapOf("path" to "Slow", "type" to "opc", "dataType" to "Double", "scanPeriodMillis" to 4000)))
        engine.updateRaw("Slow", 1.0)
        clock.advanceMillis(4_000 + 12_000 - 1)
        engine.checkComms()
        assertEquals(QualityCode.GOOD, engine.read("Slow").quality.code)
        clock.advanceMillis(1)
        engine.checkComms()
        assertEquals(QualityCode.BAD_NOT_CONNECTED, engine.read("Slow").quality.code, "real silence is still comms loss")
    }

    @Test fun anUnsetTimeoutIsNotWrittenBackAsZero() {
        val config = TagConfig.fromSpec(mapOf("path" to "Dev", "type" to "opc", "dataType" to "Double"))
        assertEquals(null, config.commsTimeoutMillis)
        assertFalse(config.toSpec().containsKey("commsTimeoutMillis"))
        val explicit = TagConfig.fromSpec(mapOf("path" to "Dev", "type" to "opc", "dataType" to "Double", "commsTimeoutMillis" to 0))
        assertEquals(0L, explicit.commsTimeoutMillis)
        assertEquals(0L, explicit.toSpec()["commsTimeoutMillis"])
    }
}
