package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.network.ServicePolicy
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Edge sync over the mutual-TLS peer network.
 * History, audit, and alarm journal rows buffer while the link is down and forward when it is up.
 *
 */
class EdgeSyncGatewayTest {
    @TempDir lateinit var tmp: Path

    @Test fun buffersUntilTheHubAllowsStorageThenKeepsTheLocalCopy() {
        val root = tmp.resolve("buffer")
        val hub = start(root.resolve("hub"), hubYaml())
        val spoke = try {
            start(root.resolve("spoke"), spokeYaml("site-a", history = true, audit = true, alarms = true))
        } catch (error: Throwable) {
            hub.close()
            throw error
        }
        listOf(hub, spoke).useAll {
            spoke.edgeSync.automatic = false
            putTag(spoke, "level", "line/level", alarm = true)
            assertTrue((spoke.edgeSync.syncOnce()["subscriptions"] as Number).toInt() >= 1)
            spoke.tags.write("[default]line/level", 150.0)
            spoke.security.audit.append(AuditDraft(System.currentTimeMillis(), "op", "edge-sync-test", AuditStatus.SUCCESS, target = "line/level"))
            assertFalse(spoke.alarms.journal.records().isEmpty())
            val buffered = spoke.edgeSync.syncOnce()
            assertEquals(false, buffered["linkUp"])
            assertTrue(pending(buffered, "history") >= 1)
            assertTrue(pending(buffered, "audit") >= 1)
            assertTrue(pending(buffered, "alarmJournal") >= 1)
            assertTrue(remoteHistory(hub).isEmpty())

            connect(spoke, hub)
            allow(hub, "read")
            val denied = spoke.edgeSync.syncOnce()
            assertEquals("PERMISSION_DENIED", (denied["history"] as Map<*, *>)["lastError"])
            assertTrue(remoteHistory(hub).isEmpty())
            assertTrue(pending(spoke.edgeSync.statusDocument(), "history") >= 1)

            allow(hub, "allow")
            spoke.edgeSync.syncOnce()
            val history = remoteHistory(hub)
            assertEquals(1, history.size)
            assertEquals("site-a", history.single()["sourceGateway"])
            assertEquals("enterprise", history.single()["provider"])
            assertEquals("line/level", history.single()["path"])
            assertEquals(150, (history.single()["value"] as Number).toInt())
            val local = localHistory(spoke).single()
            assertEquals(true, local["forwarded"])
            assertEquals("line/level", local["path"])

            val audits = remoteRows(hub.edgeSync.auditDocument(null, "site-a", 20))
            val copied = audits.single { it["action"] == "edge-sync-test" }
            assertEquals("op", copied["actor"])
            assertEquals(0, hub.auditCount("edge-sync-test"))
            assertTrue(spoke.auditCount("edge-sync-test") >= 1)

            val alarms = remoteRows(hub.edgeSync.alarmDocument(null, "site-a", 20))
            assertTrue(alarms.any { (it["displayPath"] as String).contains("line/level") })
            assertEquals(0, hub.alarms.journal.records().size)

            hub.network.faultSessions(true)
            spoke.tags.write("[default]line/level", 160.0)
            spoke.edgeSync.syncOnce()
            assertEquals(1, remoteHistory(hub).size)
            assertTrue(pending(spoke.edgeSync.statusDocument(), "history") >= 1)
            hub.network.faultSessions(false)
            waitForCleanLink(spoke, System.currentTimeMillis())
            spoke.edgeSync.syncOnce()
            assertEquals(2, remoteHistory(hub).size)
            assertTrue(remoteHistory(hub).any { (it["value"] as Number).toInt() == 160 })
        }
    }

    @Test fun oneBatchPerPassAndAClosedScheduleHoldsTheBuffer() {
        val root = tmp.resolve("batch")
        val hub = start(root.resolve("hub"), hubYaml())
        val spoke = try {
            start(root.resolve("spoke"), spokeYaml("site-a", history = true, batch = 2))
        } catch (error: Throwable) {
            hub.close()
            throw error
        }
        val closedHour = (java.time.LocalTime.now(ZoneOffset.UTC).hour + 12) % 24
        val closed = try {
            start(root.resolve("closed"), spokeYaml("site-b", history = true, schedule = "%02d:00-%02d:01".format(closedHour, closedHour)))
        } catch (error: Throwable) {
            hub.close()
            spoke.close()
            throw error
        }
        listOf(hub, spoke, closed).useAll {
            spoke.edgeSync.automatic = false
            closed.edgeSync.automatic = false
            connect(spoke, hub)
            connect(closed, hub)
            allow(hub, "allow")
            putTag(spoke, "level", "line/level")
            putTag(closed, "level", "line/level")
            spoke.edgeSync.syncOnce()
            closed.edgeSync.syncOnce()
            repeat(5) { spoke.tags.write("[default]line/level", (it + 1).toDouble()) }
            closed.tags.write("[default]line/level", 9.0)
            spoke.edgeSync.syncOnce()
            assertEquals(2, remoteHistory(hub, "site-a").size)
            assertEquals(3, pending(spoke.edgeSync.statusDocument(), "history"))
            spoke.edgeSync.syncOnce()
            assertEquals(4, remoteHistory(hub, "site-a").size)
            spoke.edgeSync.syncOnce()
            assertEquals(5, remoteHistory(hub, "site-a").size)
            assertEquals(0, pending(spoke.edgeSync.statusDocument(), "history"))
            assertEquals(5, localHistory(spoke).size)

            closed.edgeSync.syncOnce()
            assertTrue(remoteHistory(hub, "site-b").isEmpty())
            assertTrue(pending(closed.edgeSync.statusDocument(), "history") >= 1)
            assertEquals(false, (closed.edgeSync.statusDocument()["history"] as Map<*, *>)["scheduleOpen"])
        }
    }

    @Test fun hubAndSpokeKeepsACopyAtEachSite() {
        val root = tmp.resolve("hub")
        val hub = start(root.resolve("hub"), hubYaml())
        val siteA = try {
            start(root.resolve("a"), spokeYaml("site-a", history = true))
        } catch (error: Throwable) {
            hub.close()
            throw error
        }
        val siteB = try {
            start(root.resolve("b"), spokeYaml("site-b", history = true))
        } catch (error: Throwable) {
            hub.close()
            siteA.close()
            throw error
        }
        listOf(hub, siteA, siteB).useAll {
            siteA.edgeSync.automatic = false
            siteB.edgeSync.automatic = false
            connect(siteA, hub)
            connect(siteB, hub)
            allow(hub, "allow")
            putTag(siteA, "tank", "site/tank")
            putTag(siteB, "pump", "site/pump")
            siteA.edgeSync.syncOnce()
            siteB.edgeSync.syncOnce()
            siteA.tags.write("[default]site/tank", 4.0)
            siteB.tags.write("[default]site/pump", 8.0)
            siteA.edgeSync.syncOnce()
            siteB.edgeSync.syncOnce()

            val remote = remoteHistory(hub)
            assertEquals(setOf("site-a", "site-b"), remote.map { it["sourceGateway"] }.toSet())
            assertEquals(4, (remote.single { it["sourceGateway"] == "site-a" }["value"] as Number).toInt())
            assertEquals(8, (remote.single { it["sourceGateway"] == "site-b" }["value"] as Number).toInt())
            assertEquals("site/tank", localHistory(siteA).single()["path"])
            assertEquals("site/pump", localHistory(siteB).single()["path"])
            assertTrue(localHistory(siteA).none { it["path"] == "site/pump" })

            val http = Http(hub.port())
            assertEquals(401, http.get("/api/v1/edge-sync").status)
            hub.loginAdmin(http)
            val body = http.get("/api/v1/edge-sync/history").json
            val listed = body["remote"] as List<*>
            assertEquals(2, listed.size)
            val status = http.get("/api/v1/edge-sync").json
            assertEquals("", status["remoteGateway"])
            assertTrue(status.containsKey("history"))
        }
    }

    @Test fun theBackgroundLoopForwardsWithoutAManualPass() {
        val root = tmp.resolve("loop")
        val hub = start(root.resolve("hub"), hubYaml())
        val spoke = try {
            start(root.resolve("spoke"), spokeYaml("site-a", history = true, frequency = 50, units = "milliseconds"))
        } catch (error: Throwable) {
            hub.close()
            throw error
        }
        listOf(hub, spoke).useAll {
            connect(spoke, hub)
            allow(hub, "allow")
            putTag(spoke, "level", "line/level")
            spoke.edgeSync.syncOnce()
            spoke.tags.write("[default]line/level", 3.0)
            waitUntil { remoteHistory(hub).isNotEmpty() }
            assertEquals(3, (remoteHistory(hub).single()["value"] as Number).toInt())
        }
    }

    @Test fun historyCapDropsTheOldestUnsentSample() {
        val root = tmp.resolve("cap")
        val hub = start(root.resolve("hub"), hubYaml())
        val spoke = try {
            start(root.resolve("spoke"), spokeYaml("site-a", history = true, maxPoints = 2))
        } catch (error: Throwable) {
            hub.close()
            throw error
        }
        listOf(hub, spoke).useAll {
            spoke.edgeSync.automatic = false
            connect(spoke, hub)
            allow(hub, "allow")
            putTag(spoke, "level", "line/level")
            spoke.edgeSync.syncOnce()
            spoke.tags.write("[default]line/level", 1.0)
            spoke.tags.write("[default]line/level", 2.0)
            spoke.tags.write("[default]line/level", 3.0)
            spoke.edgeSync.syncOnce()
            val values = remoteHistory(hub).map { (it["value"] as Number).toInt() }.toSet()
            assertFalse(1 in values)
            assertEquals(setOf(2, 3), values)
            assertEquals(2, localHistory(spoke).size)
        }
    }

    @Test fun tagWriteDoesNotWaitOnTheSyncDisk() {
        val spoke = start(tmp.resolve("lock"), spokeYaml("site-a", history = true, frequency = 50, units = "milliseconds"))
        spoke.use {
            it.edgeSync.automatic = false
            putTag(it, "level", "line/level")
            val subscribed = it.edgeSync.syncOnce()
            assertTrue((subscribed["subscriptions"] as Number).toInt() >= 1)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            it.edgeSync.beforePersist = {
                assertFalse(it.tags.holdingWriteLock())
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
            it.tags.write("[default]line/level", 1.0)
            assertTrue(entered.await(5, TimeUnit.SECONDS), "sync thread did not persist")
            val started = System.nanoTime()
            it.tags.write("[default]line/level", 2.0)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            release.countDown()
            assertTrue(elapsedMs < 500, "tag write waited ${elapsedMs}ms on sync disk")
        }
    }

    private fun start(dir: Path, yaml: String): Gateway {
        dir.createDirectories()
        dir.resolve("gateway.yaml").writeText(yaml)
        return Gateway(dir, port = 0).also { it.start() }
    }

    private fun hubYaml(): String = """
        profile: hub
        opcua:
          enabled: false
        gatewayNetwork:
          name: central
          port: 0
          pingRateMillis: 40
          pingTimeoutMillis: 400
          missedPings: 1
          backoffInitialMillis: 20
          backoffCapMillis: 80
    """.trimIndent()

    private fun spokeYaml(
        name: String,
        history: Boolean,
        audit: Boolean = false,
        alarms: Boolean = false,
        batch: Int = 1000,
        maxPoints: Long = 10_000_000,
        schedule: String? = null,
        frequency: Long = 3600,
        units: String = "seconds",
    ): String = buildString {
        appendLine("profile: edge")
        appendLine("opcua:")
        appendLine("  enabled: false")
        appendLine("gatewayNetwork:")
        appendLine("  name: $name")
        appendLine("  port: 0")
        appendLine("  pingRateMillis: 40")
        appendLine("  pingTimeoutMillis: 400")
        appendLine("  missedPings: 1")
        appendLine("  backoffInitialMillis: 20")
        appendLine("  backoffCapMillis: 80")
        appendLine("edgeSync:")
        appendLine("  remoteGateway: hub")
        appendLine("  history:")
        appendLine("    enabled: $history")
        appendLine("    remoteProvider: enterprise")
        appendLine("    maxBatchSize: $batch")
        appendLine("    syncFrequency: $frequency")
        appendLine("    syncFrequencyUnits: $units")
        appendLine("    captureAll: true")
        appendLine("    maxPoints: $maxPoints")
        if (schedule != null) {
            appendLine("    scheduleEnabled: true")
            appendLine("    schedulePattern: \"$schedule\"")
        }
        appendLine("  audit:")
        appendLine("    enabled: $audit")
        appendLine("    remoteProfile: Gateway")
        appendLine("    syncFrequency: $frequency")
        appendLine("    syncFrequencyUnits: $units")
        appendLine("  alarmJournal:")
        appendLine("    enabled: $alarms")
        appendLine("    remoteJournal: Journal")
        appendLine("    syncFrequency: $frequency")
        appendLine("    syncFrequencyUnits: $units")
    }

    private fun putTag(gateway: Gateway, name: String, path: String, alarm: Boolean = false) {
        val spec = linkedMapOf<String, Any?>(
            "path" to path,
            "type" to "memory",
            "dataType" to "Double",
            "value" to 0,
        )
        if (alarm) {
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
        gateway.store.putAll(
            Scope.GATEWAY,
            listOf(Resource(kind = "Tag", metadata = Metadata(name = name, module = "tags"), spec = spec)),
            actor = "test",
            syncEach = false,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun connect(spoke: Gateway, hub: Gateway) {
        val existing = runCatching { spoke.network.outgoingStatus("hub") }.isSuccess
        if (!existing) spoke.network.addOutgoing("hub", "127.0.0.1", hub.network.port(), true, "edge sync")
        waitUntil {
            val outgoing = spoke.network.statusDocument()["outgoing"] as List<Map<String, Any?>>
            outgoing.any { it["certificateState"] == "pending" || it["status"] == "pending" || it["status"] == "running" }
        }
        waitUntil(timeoutMs = 12_000) {
            val outgoing = (spoke.network.statusDocument()["outgoing"] as List<Map<String, Any?>>).first { it["name"] == "hub" }
            if (outgoing["certificateState"] == "pending" || outgoing["status"] == "pending") spoke.network.approveOutgoing("hub")
            val incoming = hub.network.statusDocument()["incoming"] as List<Map<String, Any?>>
            for (row in incoming) {
                if (row["state"] != "approved") hub.network.approveIncoming(row["fingerprint"] as String)
            }
            spoke.network.outgoingStatus("hub") == "running"
        }
    }

    /**
     * "running" alone is not enough after a fault: a heartbeat the hub dropped during the fault can
     * still be on its way back, and with `missedPings: 1` it then tears the channel down under the
     * next sync. A heartbeat or handshake that succeeded after [healedAt] means the link in place now
     * has talked to the hub since the fault ended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun waitForCleanLink(spoke: Gateway, healedAt: Long) {
        waitUntil {
            val outgoing = (spoke.network.statusDocument()["outgoing"] as List<Map<String, Any?>>).first { it["name"] == "hub" }
            outgoing["status"] == "running" && ((outgoing["lastCommMillis"] as? Number)?.toLong() ?: 0L) >= healedAt
        }
    }

    private fun allow(hub: Gateway, access: String) {
        for (service in listOf("history", "audit", "alarm-journal")) {
            hub.network.putPolicy(ServicePolicy("*", service, access))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun remoteHistory(hub: Gateway, source: String? = null): List<Map<String, Any?>> =
        hub.edgeSync.historyDocument(null, source, 20)["remote"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun localHistory(spoke: Gateway): List<Map<String, Any?>> =
        spoke.edgeSync.historyDocument(null, null, 20)["local"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun remoteRows(document: Map<String, Any?>): List<Map<String, Any?>> =
        document["remote"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun pending(status: Map<String, Any?>, stream: String): Int =
        ((status[stream] as Map<String, Any?>)["pending"] as Number).toInt()

    private fun waitUntil(timeoutMs: Long = 8_000, body: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var error = ""
        while (System.nanoTime() < deadline) {
            try {
                if (body()) return
            } catch (thrown: Throwable) {
                error = thrown.message ?: thrown.javaClass.simpleName
            }
            Thread.sleep(30)
        }
        throw AssertionError("condition not met: $error")
    }

    private fun List<Gateway>.useAll(body: () -> Unit) {
        try {
            body()
        } finally {
            forEach { runCatching { it.close() } }
        }
    }
}
