package org.oip.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class CronExpressionTest {
    private val utc = ZoneId.of("UTC")

    private fun next(cron: String, after: String, zone: ZoneId = utc): String? =
        CronExpression.parse(cron).next(Instant.parse(after), zone)?.toString()

    @Test
    fun basicFields() {
        assertEquals("2026-10-27T10:16:00Z", next("* * * * *", "2026-10-27T10:15:30Z"))
        assertEquals("2026-10-27T10:30:00Z", next("*/15 * * * *", "2026-10-27T10:15:00Z"))
        assertEquals("2026-10-28T06:00:00Z", next("0 6 * * *", "2026-10-27T06:00:00Z"))
        assertEquals("2026-10-27T06:00:00Z", next("0 6 * * *", "2026-10-27T05:59:59Z"))
        assertEquals("2026-11-01T00:00:00Z", next("@monthly", "2026-10-27T00:00:00Z"))
        assertEquals("2026-10-27T11:00:00Z", next("@hourly", "2026-10-27T10:00:00Z"))
        assertEquals("2026-10-27T10:20:00Z", next("5-20/5,45 10 * * *", "2026-10-27T10:15:00Z"))
        assertEquals("2026-10-27T10:45:00Z", next("5-20/5,45 10 * * *", "2026-10-27T10:20:00Z"))
    }

    @Test
    fun namesAndDayOfWeek() {
        // 2026-10-30 is a Friday, 2026-10-31 Saturday, 2026-11-02 Monday.
        assertEquals("2026-11-02T09:00:00Z", next("0 9 * * MON-FRI", "2026-10-30T09:00:00Z"))
        assertEquals("2026-11-01T00:00:00Z", next("0 0 * * 7", "2026-10-30T00:00:00Z"))
        assertEquals("2026-11-01T00:00:00Z", next("0 0 * * sun", "2026-10-30T00:00:00Z"))
        assertEquals("2027-01-01T00:00:00Z", next("0 0 1 jan *", "2026-10-30T00:00:00Z"))
    }

    @Test
    fun dayOfMonthOrDayOfWeekWhenBothRestricted() {
        // POSIX: "0 0 15 * FRI" fires on the 15th and on every Friday.
        assertEquals("2026-10-30T00:00:00Z", next("0 0 15 * FRI", "2026-10-27T00:00:00Z"))
        assertEquals("2026-11-06T00:00:00Z", next("0 0 15 * FRI", "2026-10-30T00:00:00Z"))
        assertEquals("2026-11-15T00:00:00Z", next("0 0 15 * FRI", "2026-11-13T00:00:00Z"))
        // Day-of-week starred: only the 15th.
        assertEquals("2026-11-15T00:00:00Z", next("0 0 15 * *", "2026-10-27T00:00:00Z"))
    }

    @Test
    fun timeZonesAndDaylightSaving() {
        val ny = ZoneId.of("America/New_York")
        // 06:00 EDT = 10:00Z.
        assertEquals("2026-10-27T10:00:00Z", next("0 6 * * *", "2026-10-27T00:00:00Z", ny))
        // 2027-03-14 02:30 does not exist in New York: skipped to the next day (06:30Z = 02:30 EDT on 03-15).
        assertEquals("2027-03-15T06:30:00Z", next("30 2 * * *", "2027-03-14T00:00:00Z", ny))
        // 2026-11-01 01:30 happens twice; it fires once, at the first (EDT, 05:30Z).
        assertEquals("2026-11-01T05:30:00Z", next("30 1 * * *", "2026-11-01T00:00:00Z", ny))
        assertEquals("2026-11-02T06:30:00Z", next("30 1 * * *", "2026-11-01T05:30:00Z", ny))
    }

    @Test
    fun neverFiringAndInvalid() {
        assertNull(next("0 0 31 2 *", "2026-10-27T00:00:00Z"))
        for (bad in listOf("", "* * * *", "60 * * * *", "* 24 * * *", "* * 0 * *", "* * * 13 *", "* * * * 8",
                "*/0 * * * *", "5-1 * * * *", "a * * * *", "1,,2 * * * *", "* * * * * *")) {
            assertThrows(IllegalArgumentException::class.java, { CronExpression.parse(bad) }, bad)
        }
    }
}
