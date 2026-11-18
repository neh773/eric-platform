package org.oip.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class CronTest {
    private val zone = ZoneOffset.UTC

    @Test fun sixFieldsMatchTheSecondAndFiveFieldsMatchTheMinute() {
        val withSeconds = Cron("0 0 0 1 1 *")
        val minute = Cron("0 0 1 1 *")
        val newYear = Instant.parse("2020-01-01T00:00:00Z")
        val laterThatSecond = Instant.parse("2020-01-01T00:00:00.500Z")
        val nextSecond = Instant.parse("2020-01-01T00:00:01Z")
        val nextMinute = Instant.parse("2020-01-01T00:01:00Z")
        assertTrue(withSeconds.matches(newYear, zone))
        assertTrue(withSeconds.matches(laterThatSecond, zone))
        assertFalse(withSeconds.matches(nextSecond, zone))
        assertEquals(withSeconds.fireKey(newYear), withSeconds.fireKey(laterThatSecond))
        assertTrue(withSeconds.fireKey(nextSecond) != withSeconds.fireKey(newYear))
        assertTrue(minute.matches(newYear, zone))
        assertTrue(minute.matches(nextSecond, zone))
        assertFalse(minute.matches(nextMinute, zone))
        assertEquals(minute.fireKey(newYear), minute.fireKey(nextSecond))
    }

    @Test fun dayOfMonthOrDayOfWeekIsEnoughWhenBothAreRestricted() {
        val cron = Cron("0 0 2 1 3")
        val wednesday = Instant.parse("2020-01-01T00:00:00Z")
        val secondOfMonth = Instant.parse("2020-01-02T00:00:00Z")
        val neither = Instant.parse("2020-01-03T00:00:00Z")
        assertTrue(cron.matches(wednesday, zone))
        assertTrue(cron.matches(secondOfMonth, zone))
        assertFalse(cron.matches(neither, zone))
    }

    @Test fun namesAndSundaySeven() {
        val cron = Cron("0 0 * jan SUN")
        val sunday = Instant.parse("2020-01-05T00:00:00Z")
        val monday = Instant.parse("2020-01-06T00:00:00Z")
        assertTrue(cron.matches(sunday, zone))
        assertFalse(cron.matches(monday, zone))
        assertTrue(Cron("0 0 * * 7").matches(sunday, zone))
        assertTrue(Cron("* * * * *").matches(monday, zone))
    }

    @Test fun rejectsABadExpression() {
        assertThrows(IllegalArgumentException::class.java) { Cron("nope") }
        assertThrows(IllegalArgumentException::class.java) { Cron("60 * * * *") }
    }
}
