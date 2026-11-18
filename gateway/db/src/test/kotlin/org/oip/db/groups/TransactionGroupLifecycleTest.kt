package org.oip.db.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.ValidationException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scheduling, enable/disable, validation, and restart. SQLite only.
 */
class TransactionGroupLifecycleTest {
    @TempDir lateinit var tmp: Path

    private fun hist(table: String, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "connection" to "plant", "table" to table, "groupType" to "historical",
        "execution" to mapOf("rateMillis" to 100),
        "items" to listOf(mapOf("name" to "speed", "source" to "tag", "tag" to "Line/Speed", "column" to "speed")),
    ) + extra

    private fun count(g: GroupHarness, table: String) = (g.rows("SELECT COUNT(*) FROM \"$table\"")[0][0] as Number).toLong()

    private fun waitFor(what: String, timeoutMillis: Long = 5_000, check: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun schedulerRunsEnabledGroupsAndDisableStopsThem() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            g.sql("CREATE TABLE log (speed DOUBLE PRECISION)")
            g.tags["Line/Speed"] = 3.0
            g.put("hist", hist("log"))
            g.groups.start()
            waitFor("three runs") { count(g, "log") >= 3 }
            assertEquals("Running", g.groups.status("hist")["state"])

            assertTrue(g.groups.setEnabled("hist", false))
            assertFalse(g.groups.setEnabled("hist", false), "already disabled: nothing to write")
            assertEquals("Disabled", g.groups.status("hist")["state"])
            Thread.sleep(150)
            val stopped = count(g, "log")
            Thread.sleep(400)
            assertEquals(stopped, count(g, "log"))

            assertTrue(g.groups.setEnabled("hist", true))
            waitFor("runs after enable") { count(g, "log") > stopped }
            assertEquals(true, g.h.store.list(GROUP_MODULE).single().resource.spec["enabled"])
        }
    }

    @Test fun aStandbyNodeRunsNoGroupsAndResumesWhenActive() {
        val active = AtomicBoolean(false)
        GroupHarness(tmp, SqliteBackend, GroupActivity { active.get() }).use { g ->
            g.sql("CREATE TABLE log (speed DOUBLE PRECISION)")
            g.tags["Line/Speed"] = 3.0
            g.put("hist", hist("log"))
            g.groups.start()
            waitFor("standby state") { g.groups.status("hist")["state"] == "Standby" }
            Thread.sleep(400)
            assertEquals(0L, count(g, "log"), "an inactive node writes nothing")
            assertEquals(0L, g.groups.status("hist")["executions"])
            assertFalse(g.groups.runOnce("hist").executed, "a manual run is skipped too")
            assertTrue(g.tags.writes.isEmpty())

            active.set(true)
            waitFor("runs after becoming active") { count(g, "log") >= 2 }
            assertEquals("Running", g.groups.status("hist")["state"])

            active.set(false)
            waitFor("standby again") { g.groups.status("hist")["state"] == "Standby" }
            Thread.sleep(150)
            val stopped = count(g, "log")
            Thread.sleep(400)
            assertEquals(stopped, count(g, "log"), "no runs after the node went back to standby")
        }
    }

    @Test fun setNodeActiveStopsSchedulersOnBackupAndRestartsThemOnActive() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            g.sql("CREATE TABLE log (speed DOUBLE PRECISION)")
            g.tags["Line/Speed"] = 3.0
            g.groups.setNodeActive(false)
            g.put("hist", hist("log"))
            g.groups.start()
            waitFor("standby before the first run") { g.groups.status("hist")["state"] == "Standby" }
            Thread.sleep(300)
            assertEquals(0L, count(g, "log"), "a node that starts as backup writes nothing")

            // Becoming active: the scheduler starts.
            g.groups.setNodeActive(true)
            waitFor("runs after becoming active") { count(g, "log") >= 2 }
            assertEquals("Running", g.groups.status("hist")["state"])
            assertTrue(Thread.getAllStackTraces().keys.any { it.name == "txgroup-hist" })

            // Becoming backup: the scheduler thread goes away, not just its runs.
            g.groups.setNodeActive(false)
            waitFor("standby again") { g.groups.status("hist")["state"] == "Standby" }
            waitFor("scheduler thread stopped") { Thread.getAllStackTraces().keys.none { it.name == "txgroup-hist" && it.isAlive } }
            val stopped = count(g, "log")
            Thread.sleep(400)
            assertEquals(stopped, count(g, "log"), "no runs on a backup")

            // A group configured while backup waits too, then both resume.
            g.sql("CREATE TABLE log2 (speed DOUBLE PRECISION)")
            g.put("hist2", hist("log2"))
            assertEquals("Standby", g.groups.status("hist2")["state"])
            g.groups.setNodeActive(true)
            waitFor("both resume") { count(g, "log") > stopped && count(g, "log2") >= 1 }
        }
    }

    @Test fun setNodeActiveReturnsWhileARunIsInTheDatabase() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            g.sql("CREATE TABLE log (speed DOUBLE PRECISION)")
            g.tags["Line/Speed"] = 3.0
            g.put("hist", hist("log"))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            g.groups.beforeDatabase("hist") {
                entered.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            g.groups.start()
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "a run reached the database phase")
                val started = System.nanoTime()
                g.groups.setNodeActive(false)
                val millis = (System.nanoTime() - started) / 1_000_000
                assertTrue(millis < 200, "setNodeActive waited ${millis}ms on a run in progress")
                waitFor("standby while the run is still parked") { g.groups.status("hist")["state"] == "Standby" }
            } finally {
                release.countDown()
            }
            g.groups.beforeDatabase("hist", null)
            Thread.sleep(200)
            assertEquals("Standby", g.groups.status("hist")["state"], "the finished run did not flip the state back")
            val after = count(g, "log")
            Thread.sleep(300)
            assertEquals(after, count(g, "log"))
        }
    }

    @Test fun groupsSurviveARestartOfTheStore() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            g.put("hist", hist("log"))
        }
        // A new store reads the files; the database service attaches first, as in the gateway.
        GroupHarness(tmp, SqliteBackend).use { g ->
            assertEquals("historical", g.groups.status("hist")["groupType"])
        }
    }

    @Test fun missingConnectionOrTableIsAnErrorNotAThrow() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            g.tags["Line/Speed"] = 1.0
            g.put("nowhere", hist("log") + mapOf("connection" to "absent"))
            g.groups.runOnce("nowhere")
            val status = g.groups.status("nowhere")
            assertEquals("Error", status["state"])
            assertTrue((status["error"] as String).contains("absent"))
        }
    }

    @Test fun badSpecsAreRejectedBySchemaOrMarkedInvalid() {
        GroupHarness(tmp, SqliteBackend).use { g ->
            assertThrows<ValidationException> { g.put("bad-table", hist("log; DROP TABLE x")) }
            assertThrows<ValidationException> {
                g.put("bad-col", hist("log", mapOf("items" to listOf(mapOf("name" to "x", "source" to "tag", "tag" to "a", "column" to "a b")))))
            }
            g.put("hist-read", hist("log", mapOf("updateMode" to "dbToTag")))
            assertEquals("Invalid", g.groups.status("hist-read")["state"])
            assertNotNull(g.groups.status("hist-read")["error"])
            assertThrows<GroupRejected> { g.groups.runOnce("hist-read") }
        }
    }

    @Test fun semanticChecks() {
        fun rejects(spec: Map<String, Any?>) = assertThrows<GroupRejected> { GroupSpecs.parse(spec) }
        val base = mapOf(
            "connection" to "plant", "table" to "t", "groupType" to "standard",
            "items" to listOf(mapOf("name" to "a", "source" to "tag", "tag" to "A", "column" to "a")),
        )
        GroupSpecs.parse(base)
        rejects(base + ("updateMode" to "dbToTag") + ("tableAction" to "insert"))
        rejects(base + ("tableAction" to "update") + ("row" to mapOf("mode" to "key")))
        rejects(base + ("tableAction" to "update") + ("row" to mapOf("mode" to "where", "where" to "1=1; DROP TABLE t")))
        rejects(base + ("items" to listOf(
            mapOf("name" to "a", "source" to "tag", "tag" to "A", "column" to "a"),
            mapOf("name" to "b", "source" to "tag", "tag" to "B", "column" to "a"),
        )))
        rejects(base + ("items" to listOf(mapOf("name" to "e", "source" to "expression", "expression" to "{e} + 1", "column" to "e"))))
        rejects(base + ("groupType" to "block") + ("blockItems" to listOf(
            mapOf("name" to "x", "column" to "x", "tags" to listOf("A", "B")),
            mapOf("name" to "y", "column" to "y", "tags" to listOf("C")),
        )))
        rejects(base + ("groupType" to "block") + ("updateMode" to "bidirectionalDbWins") +
            ("blockItems" to listOf(mapOf("name" to "x", "column" to "x", "tags" to listOf("A")))))
        rejects(base + ("groupType" to "historical") + ("tableAction" to "update"))
    }
}
