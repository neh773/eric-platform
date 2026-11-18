package org.oip.edgesync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.gateway.GatewaySettings
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.writeText

/**
 * Schedule ranges and edge-sync settings.
 */
class EdgeSyncScheduleTest {
    @TempDir lateinit var tmp: Path

    @Test fun examplePatternOpensInsideEachRange() {
        val schedule = SyncSchedule.parse("9:00-15:00, 6pm-1am, 06.30-07.30")
        assertTrue(schedule.allows(at(10, 0)))
        assertTrue(schedule.allows(at(9, 0)))
        assertFalse(schedule.allows(at(15, 0)))
        assertFalse(schedule.allows(at(8, 59)))
        assertTrue(schedule.allows(at(18, 0)))
        assertTrue(schedule.allows(at(0, 30)))
        assertFalse(schedule.allows(at(2, 0)))
        assertTrue(schedule.allows(at(6, 45)))
        assertFalse(schedule.allows(at(7, 30)))
        assertFalse(schedule.allows(at(6, 29)))
    }

    @Test fun hoursWithoutMinutesAndTwelveHourClock() {
        val military = SyncSchedule.parse("9-15")
        assertTrue(military.allows(at(9, 0)))
        assertFalse(military.allows(at(15, 0)))
        val midnight = SyncSchedule.parse("12am-1am")
        assertTrue(midnight.allows(at(0, 30)))
        assertFalse(midnight.allows(at(1, 0)))
        val noon = SyncSchedule.parse("12pm-1pm")
        assertTrue(noon.allows(at(12, 30)))
        assertFalse(noon.allows(at(13, 0)))
        assertTrue(SyncSchedule.parse("0:00-24:00").allows(at(23, 59)))
    }

    @Test fun emptyOrEqualRangeNeverOpens() {
        assertFalse(SyncSchedule.parse("").allows(at(12, 0)))
        assertFalse(SyncSchedule.parse("9:00-9:00").allows(at(9, 0)))
    }

    @Test fun rejectsATimeThatIsNotAClockValue() {
        assertThrows(IllegalArgumentException::class.java) { SyncSchedule.parse("25:00-26:00") }
        assertThrows(IllegalArgumentException::class.java) { SyncUnit.parse("fortnights") }
    }

    @Test fun frequencyUnitsUseFixedMonthAndYearLengths() {
        assertEquals(10_000L, SyncUnit.SECONDS.toMillis(10))
        assertEquals(30L * 86_400_000L, SyncUnit.MONTHS.toMillis(1))
        assertEquals(365L * 86_400_000L, SyncUnit.YEARS.toMillis(1))
    }

    @Test fun yamlDefaultsAreOffAndAScheduleIsParsed() {
        val file = tmp.resolve("gateway.yaml")
        file.writeText("profile: standard\n")
        val defaults = GatewaySettings.load(file)
        assertFalse(defaults.edgeSync.history.enabled)
        assertFalse(defaults.edgeSync.audit.enabled)
        assertFalse(defaults.edgeSync.alarmJournal.enabled)
        assertEquals(35, defaults.edgeSync.history.retentionDays)
        assertEquals(10_000_000L, defaults.edgeSync.history.maxPoints)
        assertEquals(7, defaults.edgeSync.audit.retentionDays)

        file.writeText(
            """
            profile: edge
            edgeSync:
              remoteGateway: hub
              history:
                enabled: true
                remoteProvider: enterprise
                maxBatchSize: 25
                syncFrequency: 5
                syncFrequencyUnits: seconds
                scheduleEnabled: true
                schedulePattern: "9:00-15:00, 6pm-1am"
                captureAll: true
                maxPoints: 1000
              audit:
                enabled: true
                remoteProfile: Gateway
              alarmJournal:
                enabled: false
                remoteJournal: Journal
            """.trimIndent(),
        )
        val loaded = GatewaySettings.load(file)
        assertEquals("hub", loaded.edgeSync.remoteGateway)
        assertEquals("edge", loaded.profile)
        assertTrue(loaded.edgeSync.history.enabled)
        assertEquals(25, loaded.edgeSync.history.maxBatchSize)
        assertEquals(SyncUnit.SECONDS, loaded.edgeSync.history.syncFrequencyUnits)
        assertTrue(loaded.edgeSync.history.schedule.allows(at(10, 0)))
        assertFalse(loaded.edgeSync.history.schedule.allows(at(16, 0)))
        assertTrue(loaded.edgeSync.audit.enabled)
        assertEquals("Gateway", loaded.edgeSync.audit.remoteName)
        assertFalse(loaded.edgeSync.alarmJournal.enabled)
    }

    @Test fun unknownKeyIsRejected() {
        val file = tmp.resolve("gateway.yaml")
        file.writeText("profile: standard\nedgeSync:\n  nope: true\n")
        assertThrows(IllegalArgumentException::class.java) { GatewaySettings.load(file) }
    }

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 10, 27, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
}
