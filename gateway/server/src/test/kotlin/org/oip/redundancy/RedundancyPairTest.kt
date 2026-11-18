package org.oip.redundancy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmFilter
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.gateway.ClockDriftDetector
import org.oip.gateway.Gateway
import org.oip.gateway.Http
import org.oip.gateway.loginAdmin
import org.oip.tags.QualityCode
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Two gateways in one process. The backup dials the master's gateway-network port.
 * A network partition is the gateway-network fault seam, which drops accepted calls.
 */
class RedundancyPairTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun failoverKeepsEveryTagInsideTheCutoffsAndFailbackReturnsControl() {
        val (master, backup) = pair("failover", masterAllowance = 0, backupAllowance = 120_000)
        try {
            approve(backup, master)
            waitUntil({ status(master, backup) }) {
                master.redundancy.statusDocument()["activityLevel"] == "active" &&
                    backup.redundancy.statusDocument()["activityLevel"] == "warm"
            }
            val count = 128
            putTags(master, count)
            // Config apply and the following runtime pull share the backup with the rest of a
            // 2-core `gradlew build`. Run 40599107191 got through this and failed later on CPU.
            // Run 40600030372 expired this 12s wait while config revision had already caught
            // up and the backup was still warm, which is standby, not a missed failover.
            waitUntil({ status(master, backup) }, timeoutMs = 30_000) {
                (0 until count).all { backup.tags.read("[default]line/t$it").value == it.toDouble() }
            }
            master.network.faultSessions(true)
            waitUntil({ status(master, backup) }, timeoutMs = 30_000) {
                backup.redundancy.statusDocument()["activityLevel"] == "active"
            }
            for (i in 0 until count) {
                val value = backup.tags.read("[default]line/t$i")
                assertEquals(i.toDouble(), value.value, "tag t$i")
                assertEquals(QualityCode.GOOD, value.quality.code, "tag t$i")
            }
            assertWithinCutoffs(backup)
            master.network.faultSessions(false)
            waitUntil({ status(master, backup) }) {
                master.redundancy.statusDocument()["activityLevel"] == "active" &&
                    backup.redundancy.statusDocument()["activityLevel"] == "warm" &&
                    backup.redundancy.statusDocument()["splitBrain"] == false
            }
            assertEquals(0.0, backup.tags.read("[default]line/t0").value)
            assertEquals(127.0, backup.tags.read("[default]line/t127").value)
        } finally {
            backup.close()
            master.close()
        }
    }

    @Test
    fun timerScriptRunsOnTheActiveNodeAndMovesWithFailover() {
        val (master, backup) = pair("scripts", masterAllowance = 0, backupAllowance = 120_000)
        try {
            approve(backup, master)
            waitUntil({ status(master, backup) }) {
                master.redundancy.isActive() &&
                    !backup.redundancy.isActive() &&
                    backup.redundancy.activity() == ActivityLevel.WARM
            }
            master.scripts.saveEvent("pulse", "timer", "_result = 1\n", delayMillis = 40)
            val id = ResourceId("scripting", Scope.GATEWAY, "GatewayEvent", "pulse")
            waitUntil({ status(master, backup) }, timeoutMs = 20_000) {
                master.scripts.lastResult("pulse")?.ok == true && backup.store.get(id) != null
            }
            Thread.sleep(250)
            assertEquals(null, backup.scripts.lastResult("pulse"))
            master.network.faultSessions(true)
            waitUntil({ status(master, backup) }, timeoutMs = 20_000) {
                backup.redundancy.isActive() && backup.scripts.lastResult("pulse")?.ok == true
            }
            master.network.faultSessions(false)
            waitUntil({ status(master, backup) }) {
                master.redundancy.isActive() &&
                    backup.redundancy.activity() == ActivityLevel.WARM &&
                    !backup.redundancy.isActive()
            }
            // A timer that already entered the script when the node went warm still records
            // when it returns. That return follows the script runtime, which on a loaded
            // runner is longer than the 40 ms delay, so a fixed drain sleep races it.
            waitUntil({ status(master, backup) }, timeoutMs = 15_000) {
                !backup.scripts.executing("pulse") && backup.scripts.lastResult("pulse")?.ok == true
            }
            val frozen = backup.scripts.lastResult("pulse")
            val quietDeadline = System.nanoTime() + 1_000_000_000L
            while (System.nanoTime() < quietDeadline) {
                if (backup.scripts.executing("pulse")) {
                    waitUntil({ status(master, backup) }, timeoutMs = 15_000) {
                        !backup.scripts.executing("pulse")
                    }
                }
                assertSame(frozen, backup.scripts.lastResult("pulse"))
                Thread.sleep(20)
            }
            assertTrue(master.scripts.lastResult("pulse")?.ok == true)
        } finally {
            backup.close()
            master.close()
        }
    }

    @Test
    fun configSyncCopiesTagsLeavesLocalResourcesAndCarriesAlarmAck() {
        val (master, backup) = pair("config", masterAllowance = 0, backupAllowance = 120_000)
        try {
            approve(backup, master)
            waitUntil({ status(master, backup) }) { backup.redundancy.statusDocument()["connected"] == true }
            master.store.put(Scope.GATEWAY, tag("alpha", "line/alpha", 1))
            waitUntil({ status(master, backup) }) { backup.tags.read("[default]line/alpha").value == 1.0 || backup.tags.read("[default]line/alpha").value == 1 }
            master.store.put(Scope.GATEWAY, tag("beta", "line/beta", 2))
            waitUntil({ status(master, backup) }) { number(backup.tags.read("[default]line/beta").value) == 2.0 }
            master.store.delete(ResourceId("tags", Scope.GATEWAY, "Tag", "alpha"))
            waitUntil({ status(master, backup) }) {
                backup.tags.read("[default]line/alpha").quality.code == QualityCode.BAD_NOT_FOUND
            }
            assertEquals(2.0, number(backup.tags.read("[default]line/beta").value))
            backup.store.put(
                Scope.GATEWAY,
                tag("localonly", "line/localonly", 7).let { resource ->
                    resource.copy(metadata = resource.metadata.copy(labels = mapOf("oip-local" to "true")))
                },
            )
            master.store.put(Scope.GATEWAY, tag("gamma", "line/gamma", 4))
            backup.redundancy.forceResync()
            waitUntil({ status(master, backup) }) {
                number(backup.tags.read("[default]line/gamma").value) == 4.0 &&
                    number(backup.tags.read("[default]line/localonly").value) == 7.0 &&
                    backup.tags.read("[default]line/alpha").quality.code == QualityCode.BAD_NOT_FOUND
            }
            master.store.put(Scope.GATEWAY, tag("level", "plant/level", 0, alarms = true))
            master.tags.write("[default]plant/level", 150.0)
            waitUntil({ status(master, backup) }) { number(backup.tags.read("[default]plant/level").value) == 150.0 }
            val event = master.alarms.query(AlarmFilter()).first { it.active && it.name == "High" }
            master.alarms.acknowledge(listOf(event.eventId), "admin", null)
            waitUntil({ status(master, backup) }) {
                backup.alarms.query(AlarmFilter()).any { it.name == "High" && it.acknowledged }
            }
        } finally {
            backup.close()
            master.close()
        }
    }

    /**
     * A tag whose resource is labeled `oip-local` on the master stays there, value and all. Before the fix the
     * master still offered its value in every runtime pull. The backup has no such tag, counted the row as a
     * tag it had not configured yet, and pulled config again for each change (17 pulls for 20 changes).
     */
    @Test
    fun aMasterLocalTagChangingDoesNotMakeTheBackupPullConfig() {
        val (master, backup) = pair("localpull", masterAllowance = 0, backupAllowance = 120_000)
        try {
            approve(backup, master)
            master.store.put(Scope.GATEWAY, local(tag("mine", "line/mine", 0)))
            master.store.put(Scope.GATEWAY, tag("shared", "line/shared", 0))
            waitUntil({ status(master, backup) }) {
                backup.redundancy.statusDocument()["activityLevel"] == "warm" &&
                    number(backup.tags.read("[default]line/shared").value) == 0.0
            }
            // Let the connect-time pulls finish before counting.
            Thread.sleep(500)
            val before = backup.redundancy.configPulls
            for (i in 1..20) {
                master.tags.write("[default]line/mine", i.toDouble())
                // Spread the changes over several heartbeats (50 ms) so each can start a sync.
                Thread.sleep(60)
            }
            Thread.sleep(500)
            val pulls = backup.redundancy.configPulls - before
            assertEquals(0L, pulls, "config pulls while a master-local tag changed 20 times: $pulls; ${status(master, backup)}")
            assertEquals(QualityCode.BAD_NOT_FOUND, backup.tags.read("[default]line/mine").quality.code)

            // Genuine changes still arrive: a new shared tag's config and a shared value.
            master.store.put(Scope.GATEWAY, tag("later", "line/later", 3))
            master.tags.write("[default]line/shared", 9.0)
            waitUntil({ status(master, backup) }) {
                number(backup.tags.read("[default]line/later").value) == 3.0 &&
                    number(backup.tags.read("[default]line/shared").value) == 9.0
            }
        } finally {
            backup.close()
            master.close()
        }
    }

    /**
     * A tag created on the master and written right away can reach the backup's runtime pull
     * before its config does. Before the fix the backup dropped that value, kept the runtime
     * revision, and stayed on the config default (about 9 of 60 tags on an idle 8-core box).
     */
    @Test
    fun valueOfATagCreatedBetweenSyncsReachesTheBackup() {
        val (master, backup) = pair("created", masterAllowance = 0, backupAllowance = 120_000)
        try {
            approve(backup, master)
            waitUntil({ status(master, backup) }) { backup.redundancy.statusDocument()["activityLevel"] == "warm" }
            val count = 60
            for (i in 0 until count) {
                master.store.put(Scope.GATEWAY, tag("fresh$i", "fresh/t$i", 0))
                master.tags.write("[default]fresh/t$i", 100.0 + i)
                // Spread the pairs over several heartbeats so some land between two syncs.
                Thread.sleep((i % 7) * 9L)
            }
            waitUntil({ status(master, backup) + " " + (0 until count).map { number(backup.tags.read("[default]fresh/t$it").value) } }) {
                (0 until count).all { number(backup.tags.read("[default]fresh/t$it").value) == 100.0 + it }
            }
        } finally {
            backup.close()
            master.close()
        }
    }

    /**
     * A restarted master numbers its runtime changes from 0 again. The backup reconnects holding a
     * higher revision from before, so a delta pull since that number is empty. The connect push only
     * answers for tags the backup already has, so a tag the master created and set while the backup
     * was away, then held, must reach the backup through a full pull.
     */
    @Test
    fun backupCatchesUpWithAValueSetWhileTheMasterRestarted() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val root = tmp.resolve("restart")
        var master = start(root.resolve("master"), "master", "master", 0, 0, "automatic", "warm", networkPort = port)
        val backup = try {
            start(root.resolve("backup"), "backup", "backup", port, 120_000, "automatic", "warm")
        } catch (error: Throwable) {
            master.close()
            throw error
        }
        try {
            approve(backup, master)
            waitUntil({ status(master, backup) }) { backup.redundancy.statusDocument()["activityLevel"] == "warm" }
            master.store.put(Scope.GATEWAY, tag("kept", "line/kept", 0))
            // Take the master's runtime revision well past what it reaches after the restart.
            for (i in 1..40) {
                master.tags.write("[default]line/kept", i.toDouble())
                waitUntil({ status(master, backup) }) { number(backup.tags.read("[default]line/kept").value) == i.toDouble() }
            }
            master.close()
            master = start(root.resolve("master"), "master", "master", 0, 0, "automatic", "warm", networkPort = port)
            // Created and set before the backup reconnects (its backoff is at least 200 ms), then held.
            master.store.put(Scope.GATEWAY, tag("late", "line/late", 0))
            master.tags.write("[default]line/late", 8.0)
            master.tags.write("[default]line/kept", 7.0)
            waitUntil({ status(master, backup) + " backup late=" + backup.tags.read("[default]line/late").value }) {
                number(backup.tags.read("[default]line/late").value) == 8.0 &&
                    number(backup.tags.read("[default]line/kept").value) == 7.0
            }
        } finally {
            backup.close()
            master.close()
        }
    }

    @Test
    fun manualFailbackWaitsForAssumeControl() {
        val (master, backup) = pair(
            "manual",
            masterAllowance = 120_000,
            backupAllowance = 0,
            recovery = "manual",
            standby = "cold",
        )
        try {
            approve(backup, master)
            waitUntil({ status(master, backup) }) {
                backup.redundancy.statusDocument()["activityLevel"] == "active" &&
                    master.redundancy.statusDocument()["activityLevel"] == "cold" &&
                    master.redundancy.statusDocument()["isActive"] == false
            }
            val http = Http(master.port())
            master.loginAdmin(http)
            val assumed = http.post("/api/v1/redundancy/assume", "{}")
            assertEquals(200, assumed.status)
            waitUntil({ status(master, backup) }) {
                master.redundancy.statusDocument()["activityLevel"] == "active" &&
                    master.redundancy.statusDocument()["isMaster"] == true &&
                    backup.redundancy.statusDocument()["activityLevel"] == "cold"
            }
            assertEquals("Master", master.tags.read("[system]Gateway/Redundancy/Role").value)
            assertEquals("active", master.tags.read("[system]Gateway/Redundancy/ActivityLevel").value)
            assertEquals(true, master.tags.read("[system]Gateway/Redundancy/IsActive").value)
            assertEquals(true, master.tags.read("[system]Gateway/Redundancy/IsMaster").value)
            assertEquals(true, master.tags.read("[system]Gateway/Redundancy/Connection/IsConnected").value)
            assertEquals("backup", master.tags.read("[system]Gateway/Redundancy/Connection/PeerId").value)
            val body = http.get("/api/v1/redundancy")
            assertEquals(200, body.status)
            assertEquals("Master", body.json["role"])
            assertEquals("active", body.json["activityLevel"])
            assertEquals(true, body.json["isActive"])
            assertEquals(true, body.json["isMaster"])
            assertEquals("backup", body.json["peerId"])
            assertEquals(false, body.json["splitBrain"])
        } finally {
            backup.close()
            master.close()
        }
    }

    @Test
    fun partitionHealsToTheMasterValueAndDropsHistoryTakenWhileTheMasterStayedActive() {
        val (master, backup) = pair("partition", masterAllowance = 0, backupAllowance = 120_000)
        try {
            approve(backup, master)
            waitUntil({ status(master, backup) }) { backup.redundancy.statusDocument()["activityLevel"] == "warm" }
            master.store.put(Scope.GATEWAY, tag("held", "line/held", 5))
            waitUntil({ status(master, backup) }) { number(backup.tags.read("[default]line/held").value) == 5.0 }
            master.tags.write("[default]line/held", 5.0)
            waitUntil({ status(master, backup) }) { number(backup.tags.read("[default]line/held").value) == 5.0 }
            master.network.faultSessions(true)
            waitUntil({ status(master, backup) }) {
                backup.redundancy.statusDocument()["activityLevel"] == "active" &&
                    backup.redundancy.statusDocument()["splitBrain"] == true &&
                    master.redundancy.statusDocument()["activityLevel"] == "active"
            }
            backup.tags.write("[default]line/held", 9.0)
            val during = System.currentTimeMillis()
            backup.redundancy.noteHistory("default", "line/held", 1.0, "Good", 1L)
            backup.redundancy.noteHistory("default", "line/held", 9.0, "Good", during)
            assertTrue(backup.redundancy.pendingHistory() >= 2)
            master.network.faultSessions(false)
            waitUntil({ status(master, backup) }) {
                backup.redundancy.statusDocument()["activityLevel"] == "warm" &&
                    number(backup.tags.read("[default]line/held").value) == 5.0 &&
                    backup.redundancy.pendingHistory() == 0
            }
            assertEquals(5.0, number(master.tags.read("[default]line/held").value))
            val logged = master.redundancy.loggedHistory()
            val windows = master.redundancy.statusDocument()["activeWindows"]
            assertTrue(logged.any { it.timestampMillis == 1L }, "logged=$logged windows=$windows during=$during")
            assertTrue(logged.none { it.timestampMillis == during }, "logged=$logged windows=$windows during=$during")
        } finally {
            backup.close()
            master.close()
        }
    }

    private fun pair(
        name: String,
        masterAllowance: Long,
        backupAllowance: Long,
        recovery: String = "automatic",
        standby: String = "warm",
    ): TwoGateways {
        val root = tmp.resolve(name)
        val master = start(root.resolve("master"), "master", "master", 0, masterAllowance, recovery, standby)
        val backup = try {
            start(root.resolve("backup"), "backup", "backup", master.network.port(), backupAllowance, recovery, standby)
        } catch (error: Throwable) {
            master.close()
            throw error
        }
        return TwoGateways(master, backup)
    }

    private fun start(
        dir: Path,
        role: String,
        gatewayName: String,
        masterPort: Int,
        allowance: Long,
        recovery: String,
        standby: String,
        networkPort: Int = 0,
    ): Gateway {
        dir.createDirectories()
        dir.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: false
            gatewayNetwork:
              name: $gatewayName
              port: $networkPort
              pingRateMillis: 50
              pingTimeoutMillis: 500
              missedPings: 3
              backoffInitialMillis: 200
              backoffCapMillis: 1000
            redundancy:
              mode: $role
              standbyActivityLevel: $standby
              startupAllowanceMillis: $allowance
              recoveryMode: $recovery
              masterHost: 127.0.0.1
              masterPort: ${if (masterPort == 0) 8060 else masterPort}
              pingRateMillis: 50
              pingTimeoutMillis: 400
              pingMaxMissed: 4
              historyMode: partial
              connectionName: redundancy
            """.trimIndent(),
        )
        return Gateway(dir, port = 0).also { it.start() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun approve(backup: Gateway, master: Gateway) {
        waitUntil({ backup.network.statusDocument().toString() }) {
            val outgoing = backup.network.statusDocument()["outgoing"] as List<Map<String, Any?>>
            outgoing.isNotEmpty() && (outgoing.first()["certificateState"] == "pending" || outgoing.first()["status"] == "pending")
        }
        backup.network.approveOutgoing("redundancy")
        waitUntil({ master.network.statusDocument().toString() }) {
            (master.network.statusDocument()["incoming"] as List<*>).isNotEmpty()
        }
        val fingerprint = (master.network.statusDocument()["incoming"] as List<Map<String, Any?>>).first()["fingerprint"] as String
        master.network.approveIncoming(fingerprint)
    }

    private fun putTags(gateway: Gateway, count: Int) {
        val resources = (0 until count).map { index -> tag("t$index", "line/t$index", 0) }
        gateway.store.putAll(Scope.GATEWAY, resources, actor = "test", syncEach = false)
        for (index in 0 until count) gateway.tags.write("[default]line/t$index", index.toDouble())
    }

    private fun tag(name: String, path: String, value: Int, alarms: Boolean = false): Resource {
        val spec = linkedMapOf<String, Any?>(
            "path" to path,
            "type" to "memory",
            "dataType" to "Double",
            "value" to value,
        )
        if (alarms) {
            spec["alarms"] = listOf(
                mapOf(
                    "name" to "High",
                    "mode" to "AboveValue",
                    "priority" to "High",
                    "setpointA" to 100,
                    "ackMode" to "Manual",
                ),
            )
        }
        return Resource(kind = "Tag", metadata = Metadata(name = name, module = "tags"), spec = spec)
    }

    private fun local(resource: Resource): Resource =
        resource.copy(metadata = resource.metadata.copy(labels = mapOf("oip-local" to "true")))

    private fun assertWithinCutoffs(gateway: Gateway) {
        val driftDeadline = System.nanoTime() + 5_000_000_000
        var drift = 0.0
        var samples = 0L
        while (System.nanoTime() < driftDeadline) {
            val snap = gateway.diagnostics.clockDrift.snapshot()
            samples = (snap["samples"] as Number).toLong()
            drift = (snap["drift"] as Number).toDouble()
            if (samples >= 1) break
            Thread.sleep(100)
        }
        assertTrue(samples >= 1, "clock drift was not sampled")
        assertFalse(ClockDriftDetector.outsideCutoff(drift), "clock drift $drift")
        // ADR 0010 samples every 10s and fails on the mean of that steady window.
        // On main 670a474 the failover had already kept every tag; the next 2s on a
        // 2-core runner still included the catch-up and read 53.7% CPU. Wait for
        // that burst to finish, then take the 10s mean. Both gateways share this process.
        Thread.sleep(5_000)
        val bean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean::class.java)
        val cores = bean.availableProcessors.coerceAtLeast(1)
        val cpuStart = bean.processCpuTime
        val wallStart = System.nanoTime()
        Thread.sleep(10_000)
        val wallNs = (System.nanoTime() - wallStart).coerceAtLeast(1)
        val cpu = (bean.processCpuTime - cpuStart).toDouble() / (wallNs.toDouble() * cores)
        val rss = rssFraction()
        assertTrue(cpu <= 0.50, "process cpu $cpu over ${wallNs / 1_000_000} ms on $cores cores, rss $rss, drift $drift")
        assertTrue(rss <= 0.50, "rss fraction $rss")
    }

    private fun rssFraction(): Double {
        if (!java.io.File("/proc/meminfo").exists()) return 0.0
        val total = procValue("/proc/meminfo", "MemTotal:")
        val rss = procValue("/proc/self/status", "VmRSS:")
        return rss / total
    }

    private fun procValue(file: String, prefix: String): Double =
        java.io.File(file).readLines().first { it.startsWith(prefix) }.trim().split(Regex("\\s+"))[1].toDouble()

    private fun number(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        else -> Double.NaN
    }

    private fun status(master: Gateway, backup: Gateway): String =
        "master=${master.redundancy.statusDocument()} backup=${backup.redundancy.statusDocument()}"

    private fun waitUntil(detail: () -> String, timeoutMs: Long = 12_000, body: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var error = ""
        while (System.nanoTime() < deadline) {
            try {
                if (body()) return
            } catch (thrown: Throwable) {
                error = thrown.message ?: thrown.toString()
            }
            Thread.sleep(30)
        }
        throw AssertionError(detail() + if (error.isEmpty()) "" else " ($error)")
    }

    private class TwoGateways(val master: Gateway, val backup: Gateway) {
        operator fun component1() = master
        operator fun component2() = backup
    }
}
