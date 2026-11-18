package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.Quality
import java.nio.file.Path

/**
 * Aggregation modes and interpolation over fixed intervals ([HistoryAggregator] KDoc), worked by hand.
 * Docs: -
 */
class AggregatesTest {
    @TempDir lateinit var tmp: Path

    private val tag = "[default]Tank/Level"

    /**
     * Rows (time ms -> value): before the range 5@-10; then 10@0, 20@40, 30@60 (Bad), 40@100, 50@150, true@190.
     * Windows of 100 over [0, 199]: W0 = [0,100), W1 = [100,200).
     */
    private var files = 0

    private fun reader(): HistorySink = SqliteHistoryStore(tmp.resolve("h${files++}.sqlite")).also {
        it.appendAll(listOf(
            HistorySample(tag, -10, 5.0, Quality.GOOD),
            HistorySample(tag, 0, 10.0, Quality.GOOD),
            HistorySample(tag, 40, 20, Quality.GOOD),
            HistorySample(tag, 60, 30.0, Quality.BAD_NOT_CONNECTED),
            HistorySample(tag, 100, 40L, Quality.GOOD),
            HistorySample(tag, 150, 50.0, Quality.GOOD),
            HistorySample(tag, 190, true, Quality.GOOD),
            HistorySample("[default]Other", 50, 999.0, Quality.GOOD),
        ))
    }

    private fun agg(
        mode: HistoryAggregate,
        from: Long = 0,
        to: Long = 199,
        interval: Long = 100,
        interpolation: HistoryInterpolation = HistoryInterpolation.NONE,
    ) = reader().use { HistoryAggregator.aggregate(it, tag, from, to, interval, mode, interpolation) }

    @Test
    fun windowsAreFixedIntervalsFromTheStartAndTheLastEndsAfterTo() {
        val w = agg(HistoryAggregate.COUNT, from = 0, to = 249, interval = 100)
        assertEquals(listOf(0L to 100L, 100L to 200L, 200L to 250L), w.map { it.startMillis to it.endMillis })
        assertEquals(listOf(3, 3, 0), w.map { it.value })
        assertEquals(listOf(3, 3, 0), w.map { it.rows })
    }

    @Test
    fun countMinMaxSumRangeAndSimpleAverageUseGoodNumericRowsInTheWindow() {
        assertEquals(listOf(3, 3), agg(HistoryAggregate.COUNT).map { it.value }) // Bad rows are still recorded values
        assertEquals(listOf(10.0, 1.0), agg(HistoryAggregate.MINIMUM).map { it.value }) // true counts as 1
        assertEquals(listOf(20.0, 50.0), agg(HistoryAggregate.MAXIMUM).map { it.value }) // 30 is Bad
        assertEquals(listOf(30.0, 91.0), agg(HistoryAggregate.SUM).map { it.value })
        assertEquals(listOf(10.0, 49.0), agg(HistoryAggregate.RANGE).map { it.value })
        assertEquals(listOf(15.0, 91.0 / 3), agg(HistoryAggregate.SIMPLE_AVERAGE).map { it.value })
    }

    @Test
    fun lastValueIsTheRowClosestToTheEndOfEachWindowWhateverItsQuality() {
        assertEquals(listOf(30.0, true), agg(HistoryAggregate.LAST_VALUE).map { it.value })
    }

    @Test
    fun averageIsTimeWeightedWithTheValueHeldUntilTheNextRow() {
        // W0: 10 holds 0..40 (40 ms), 20 holds 40..60 (20 ms), Bad 30 holds 60..100 (no weight).
        //     (10*40 + 20*20) / 60 = 13.333...
        // W1: 40 holds 100..150 (50), 50 holds 150..190 (40), true=1 holds 190..200 (10).
        //     (40*50 + 50*40 + 1*10) / 100 = 40.1
        val w = agg(HistoryAggregate.AVERAGE)
        assertEquals(800.0 / 60, w[0].value as Double, 1e-9)
        assertEquals(40.1, w[1].value as Double, 1e-9)
    }

    @Test
    fun averageCarriesTheValueInEffectBeforeTheRangeIntoTheFirstWindow() {
        // Range [-5, 4]: 5 (from -10) holds -5..0, 10 holds 0..5 -> (5*5 + 10*5) / 10 = 7.5. A window with
        // no rows at all is still weighted by the held value.
        val w = agg(HistoryAggregate.AVERAGE, from = -5, to = 4, interval = 10)
        assertEquals(7.5, w.single().value as Double, 1e-9)
        val empty = agg(HistoryAggregate.AVERAGE, from = 160, to = 179, interval = 10)
        assertEquals(listOf(50.0, 50.0), empty.map { it.value })
        assertEquals(listOf(0, 0), empty.map { it.rows })
    }

    @Test
    fun aWindowWithNothingToAggregateIsNullAndThereIsNoInterpolation() {
        val w = reader().use { HistoryAggregator.aggregate(it, "[default]Nothing", 0, 199, 100, HistoryAggregate.MAXIMUM) }
        assertNull(w[0].value)
        assertNull(w[1].value)
        assertNull(agg(HistoryAggregate.LAST_VALUE, from = 200, to = 299)[0].value)
    }

    @Test
    fun noneIsTheDefaultAndLeavesResultsAndEdgeValuesUntouched() {
        for (mode in HistoryAggregate.entries) {
            val plain = reader().use { HistoryAggregator.aggregate(it, tag, 0, 199, 100, mode) }
            assertEquals(plain, agg(mode, interpolation = HistoryInterpolation.NONE))
            assertTrue(plain.all { it.startValue == null && it.endValue == null })
        }
    }

    @Test
    fun stepEdgesHoldTheValueInEffectAndFillEmptyWindows() {
        // Edge 0 is the row at 0; edge 1 (t=100) is the row at 100; edge 2 (t=200) is true@190 held = 1.
        val w = agg(HistoryAggregate.AVERAGE, interpolation = HistoryInterpolation.STEP)
        assertEquals(listOf(10.0 to 40.0, 40.0 to 1.0), w.map { it.startValue to it.endValue })
        // Step weighting is the same as the default time-weighted average.
        assertEquals(agg(HistoryAggregate.AVERAGE).map { it.value }, w.map { it.value })
        // [160, 180) has no rows: 50@150 is in effect throughout.
        assertEquals(listOf(50.0, 50.0), agg(HistoryAggregate.LAST_VALUE, 160, 179, 10, HistoryInterpolation.STEP).map { it.value })
        assertEquals(listOf(50.0, 50.0), agg(HistoryAggregate.MAXIMUM, 160, 179, 10, HistoryInterpolation.STEP).map { it.value })
        assertEquals(listOf(0.0, 0.0), agg(HistoryAggregate.RANGE, 160, 179, 10, HistoryInterpolation.STEP).map { it.value })
        // Without interpolation the same windows are empty.
        assertEquals(listOf(null, null), agg(HistoryAggregate.MAXIMUM, 160, 179, 10).map { it.value })
        // Sum, SimpleAverage and Count still describe stored rows only.
        assertEquals(listOf(null, null), agg(HistoryAggregate.SUM, 160, 179, 10, HistoryInterpolation.STEP).map { it.value })
        assertEquals(listOf(0, 0), agg(HistoryAggregate.COUNT, 160, 179, 10, HistoryInterpolation.STEP).map { it.value })
    }

    @Test
    fun stepDoesNotLetTheNextWindowsFirstRowIntoThisWindowsExtremes() {
        // W0 = [0,100) ends where the row 40@100 starts W1. W0's max stays 20 (30@60 is Bad).
        assertEquals(listOf(20.0, 50.0), agg(HistoryAggregate.MAXIMUM, interpolation = HistoryInterpolation.STEP).map { it.value })
    }

    @Test
    fun linearAverageIntegratesTheLineBetweenGoodRows() {
        // W0: 10@0 -> 20@40 trapezoid (10+20)/2*40 = 600; 20@40 -> Bad@60 is held (next is Bad): 20*20 = 400;
        //     Bad 60..100 has no weight. 1000 / 60.
        // W1: 40@100 -> 50@150: 45*50 = 2250; 50@150 -> 1@190: 25.5*40 = 1020; 1 held 190..200: 10. 3280 / 100.
        val w = agg(HistoryAggregate.AVERAGE, interpolation = HistoryInterpolation.LINEAR)
        assertEquals(1000.0 / 60, w[0].value as Double, 1e-9)
        assertEquals(32.8, w[1].value as Double, 1e-9)
        assertEquals(listOf(10.0 to 40.0, 40.0 to 1.0), w.map { it.startValue to it.endValue })
    }

    @Test
    fun linearEdgesUseTheRowsOnBothSidesIncludingTheFirstRowAfterTheRange() {
        // [160, 180) in windows of 10 has no rows. The line runs from 50@150 to 1@190 (after the range):
        // 160 -> 37.75, 170 -> 25.5, 180 -> 13.25.
        val avg = agg(HistoryAggregate.AVERAGE, 160, 179, 10, HistoryInterpolation.LINEAR)
        assertEquals(listOf(37.75 to 25.5, 25.5 to 13.25), avg.map { it.startValue to it.endValue })
        assertEquals((37.75 + 25.5) / 2, avg[0].value as Double, 1e-9)
        assertEquals((25.5 + 13.25) / 2, avg[1].value as Double, 1e-9)
        assertEquals(listOf(25.5, 13.25), agg(HistoryAggregate.MINIMUM, 160, 179, 10, HistoryInterpolation.LINEAR).map { it.value })
        assertEquals(listOf(37.75, 25.5), agg(HistoryAggregate.MAXIMUM, 160, 179, 10, HistoryInterpolation.LINEAR).map { it.value })
        assertEquals(listOf(25.5, 13.25), agg(HistoryAggregate.LAST_VALUE, 160, 179, 10, HistoryInterpolation.LINEAR).map { it.value })
        assertEquals(listOf(0, 0), avg.map { it.rows })
    }

    @Test
    fun nothingIsInterpolatedWhileABadRowIsInEffect() {
        // [60, 100): Bad 30@60 is in effect until 40@100, so there is no edge value and no average.
        for (mode in listOf(HistoryInterpolation.STEP, HistoryInterpolation.LINEAR)) {
            val w = agg(HistoryAggregate.AVERAGE, 60, 99, 20, mode)
            assertEquals(listOf(null to null, null to null), w.map { it.startValue to it.endValue }, mode.wire)
            assertEquals(listOf(null, null), w.map { it.value }, mode.wire)
            assertEquals(listOf(null, null), agg(HistoryAggregate.MAXIMUM, 60, 99, 20, mode).map { it.value }, mode.wire)
        }
    }

    @Test
    fun invalidRequestsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { HistoryAggregator.windowCount(0, 10, 0) }
        assertThrows(IllegalArgumentException::class.java) { HistoryAggregator.windowCount(11, 10, 1) }
        assertThrows(IllegalArgumentException::class.java) { HistoryAggregator.windowCount(0, 10_000, 1) }
        assertThrows(IllegalArgumentException::class.java) { HistoryAggregator.windowCount(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE) }
        assertEquals(10_000, HistoryAggregator.windowCount(0, 9_999, 1))
        assertEquals(HistoryAggregate.LAST_VALUE, HistoryAggregate.fromWire("lastvalue"))
        assertThrows(IllegalArgumentException::class.java) { HistoryAggregate.fromWire("Median") }
        assertEquals(HistoryInterpolation.LINEAR, HistoryInterpolation.fromWire("linear"))
        assertThrows(IllegalArgumentException::class.java) { HistoryInterpolation.fromWire("Cubic") }
    }
}
