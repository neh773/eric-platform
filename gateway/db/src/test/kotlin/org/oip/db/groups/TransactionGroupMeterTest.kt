package org.oip.db.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.ValidationException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Hour and event meter items (doc source: FEATURES DAT-007): on-time and rising-edge counts, kept across restarts.
 */
class TransactionGroupMeterTest {
    @TempDir lateinit var tmp: Path

    private class StepClock(var now: Long = 1_000_000L) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private fun spec(table: String, unit: String? = null) = mapOf(
        "connection" to "plant", "table" to table, "groupType" to "historical",
        "execution" to mapOf("rateMillis" to 60_000),
        "items" to listOf(
            mapOf("name" to "hours", "source" to "tag", "tag" to "Pump/Running", "column" to "run_time", "meter" to "hour") +
                (if (unit == null) emptyMap() else mapOf("meterUnit" to unit)),
            mapOf("name" to "starts", "source" to "tag", "tag" to "Pump/Running", "column" to "starts", "meter" to "event"),
        ),
    )

    private fun lastRow(g: GroupHarness, table: String) = g.rows("SELECT run_time, starts FROM \"$table\" ORDER BY rowid DESC LIMIT 1")[0]

    private fun groupsFor(g: GroupHarness, clock: Clock, dir: Path = tmp.resolve("meters")) =
        TransactionGroups(g.h.store, g.tags, g.database, clock, meters = FileMeterStore(dir)).also { it.attach() }

    @Test fun hourMeterAddsOnTimeAndEventMeterCountsRisingEdges() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            g.groups.close()
            g.sql("CREATE TABLE usage (run_time DOUBLE PRECISION, starts INTEGER)")
            g.put("usage", spec("usage"))
            val clock = StepClock()
            val groups = groupsFor(g, clock)
            g.tags["Pump/Running"] = false
            groups.runOnce("usage")
            clock.now += 10_000; g.tags["Pump/Running"] = true
            groups.runOnce("usage")            // rising edge; on-time starts counting from the next sample
            clock.now += 30_000
            groups.runOnce("usage")            // 30 s on
            clock.now += 20_000; g.tags["Pump/Running"] = false
            groups.runOnce("usage")            // still on for the 20 s before this sample
            clock.now += 60_000
            groups.runOnce("usage")            // off: nothing added
            clock.now += 5_000; g.tags["Pump/Running"] = true
            groups.runOnce("usage")            // second rising edge
            assertEquals(50.0, num(lastRow(g, "usage")[0]), 0.001)
            assertEquals(2, (lastRow(g, "usage")[1] as Number).toInt())
            groups.close()
        }
    }

    @Test fun totalsSurviveARestartWithoutCountingTheDowntimeOrARunningPumpTwice() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            g.groups.close()
            g.sql("CREATE TABLE usage (run_time DOUBLE PRECISION, starts INTEGER)")
            g.put("usage", spec("usage"))
            val clock = StepClock()
            var groups = groupsFor(g, clock)
            g.tags["Pump/Running"] = true
            groups.runOnce("usage")
            clock.now += 3_600_000
            groups.runOnce("usage")
            groups.close()                      // saves the totals

            clock.now += 7_200_000              // two hours with the gateway down
            groups = groupsFor(g, clock)
            groups.runOnce("usage")             // still running: no new start, no downtime counted
            clock.now += 1_800_000
            groups.runOnce("usage")
            assertEquals(5_400.0, num(lastRow(g, "usage")[0]), 0.001)
            assertEquals(1, (lastRow(g, "usage")[1] as Number).toInt())
            groups.close()
        }
    }

    @Test fun hourMeterCanReportInHoursAndBadQualityChangesNothing() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            g.groups.close()
            g.sql("CREATE TABLE usage (run_time DOUBLE PRECISION, starts INTEGER)")
            g.put("usage", spec("usage", unit = "hours"))
            val clock = StepClock()
            val groups = groupsFor(g, clock)
            g.tags["Pump/Running"] = true
            groups.runOnce("usage")
            clock.now += 1_800_000
            groups.runOnce("usage")
            g.tags.bad += "Pump/Running"
            clock.now += 1_800_000
            groups.runOnce("usage")
            g.tags.bad -= "Pump/Running"
            assertEquals(0.5, num(lastRow(g, "usage")[0]), 0.001)
            assertEquals(1, (lastRow(g, "usage")[1] as Number).toInt())
            groups.close()
        }
    }

    @Test fun meterItemsMustWatchATagInATagToDbGroup() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            val bad = spec("usage") + mapOf(
                "items" to listOf(mapOf("name" to "hours", "source" to "static", "value" to 1, "column" to "run_time", "meter" to "hour")),
            )
            g.put("usage", bad)
            assertEquals("Invalid", g.groups.status("usage")["state"])
            assertEquals("item hours: a meter watches a tag", g.groups.status("usage")["error"])
            val unit = spec("usage") + mapOf(
                "items" to listOf(mapOf("name" to "x", "source" to "tag", "tag" to "A", "column" to "run_time", "meterUnit" to "hours")),
            )
            g.put("usage", unit)
            assertEquals("item x: meterUnit needs a meter", g.groups.status("usage")["error"])
        }
    }
}
