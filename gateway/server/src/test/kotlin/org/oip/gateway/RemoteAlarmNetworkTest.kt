package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmEventView
import org.oip.alarm.AlarmFilter
import org.oip.alarm.JournalEventTypes
import org.oip.network.ServicePolicy
import org.oip.security.AccessDeniedException
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import org.oip.tags.TagConfig
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Two gateways on loopback. Alarm query, subscribe, and acknowledge follow the service policy.
 * A dropped session subscribes again and receives the current live set.
 *
 */
class RemoteAlarmNetworkTest {
    @TempDir lateinit var tmp: Path

    @Test fun deniedPeerGetsNoAlarms() {
        open("deny").use { (from, to) ->
            raise(to)
            connect(from, to)
            from.network.addRemoteProvider("site", "peer", "default")
            waitUntil({ from.network.remoteAlarmPhase("site") }) { from.network.remoteAlarmPhase("site") == "denied" }
            assertTrue(from.network.pullRemoteAlarms("site").isEmpty())
            val http = Http(from.port())
            from.loginAdmin(http)
            val listed = http.get("/api/v1/alarms")
            assertEquals(200, listed.status, listed.body)
            assertTrue((listed.json["alarms"] as List<*>).isEmpty())
            assertFalse(listed.body.contains("tank/level"))
            to.network.putPolicy(ServicePolicy(from.network.fingerprint(), "alarms", "deny"))
            assertTrue(from.network.pullRemoteAlarms("site").isEmpty())
            assertEquals(1, to.alarms.query().size)
        }
    }

    @Test fun readOnlyCanQueryAndSubscribeButNotAcknowledge() {
        open("read").use { (from, to) ->
            raise(to)
            connect(from, to)
            to.network.putPolicy(ServicePolicy(from.network.fingerprint(), "alarms", "read"))
            from.network.addRemoteProvider("site", "peer", "default")
            val seen = CopyOnWriteArrayList<List<AlarmEventView>>()
            from.network.watchRemoteAlarms("site") { seen.add(it) }
            waitUntil({ seen.map { it.map { event -> event.state } }.toString() }) {
                seen.any { it.size == 1 && it.single().active && !it.single().acknowledged }
            }
            val pulled = from.network.pullRemoteAlarms("site")
            assertEquals("prov:site:/tag:tank/level:/alm:High", pulled.single().source)
            assertTrue(pulled.single().eventId.startsWith("site:"))
            assertThrows(AccessDeniedException::class.java) {
                from.network.acknowledge(listOf(pulled.single().eventId), "ada", null)
            }
            assertThrows(AccessDeniedException::class.java) {
                from.network.shelve(listOf(pulled.single().source), Duration.ofSeconds(60))
            }
            val owner = to.alarms.query().single()
            assertFalse(owner.acknowledged)
            assertFalse(to.alarms.isShelved(owner.source))
            assertEquals(0, to.auditCount(AuditActions.ALARM_ACKNOWLEDGED, status = AuditStatus.SUCCESS))
        }
    }

    @Test fun allowedAckIsAuditedOnTheOwner() {
        open("allow").use { (from, to) ->
            raise(to)
            connect(from, to)
            to.network.putPolicy(ServicePolicy(from.network.fingerprint(), "alarms", "allow"))
            from.network.addRemoteProvider("site", "peer", "default")
            val http = Http(from.port())
            from.loginAdmin(http)
            waitUntil({ http.get("/api/v1/alarms").body }) {
                val body = http.get("/api/v1/alarms")
                body.status == 200 && (body.json["alarms"] as List<*>).size == 1
            }
            val row = (http.get("/api/v1/alarms").json["alarms"] as List<*>).single() as Map<*, *>
            assertEquals("prov:site:/tag:tank/level:/alm:High", row["source"])
            val id = row["eventId"] as String
            val acked = http.post("/api/v1/alarms/ack", """{"eventIds":["$id"],"notes":"from peer"}""")
            assertEquals(200, acked.status, acked.body)
            val owner = to.alarms.query().single()
            assertTrue(owner.acknowledged)
            assertEquals("admin", owner.ackUser)
            val audit = to.security.audit.query(
                AuditQuery(action = AuditActions.ALARM_ACKNOWLEDGED, status = AuditStatus.SUCCESS),
            )
            assertEquals(1, audit.total)
            val record = audit.records.single()
            assertEquals("gateway-a", record.actor)
            assertEquals(from.network.fingerprint(), record.actorHost)
            assertEquals("gateway-network", record.origin)
            assertTrue(record.detail.contains("user=admin"))
            val shelved = http.post(
                "/api/v1/alarms/shelve",
                """{"sources":["${row["source"]}"],"durationSeconds":120}""",
            )
            assertEquals(200, shelved.status, shelved.body)
            assertTrue(to.alarms.isShelved("prov:default:/tag:tank/level:/alm:High"))
            assertTrue(to.alarms.query().isEmpty())
        }
    }

    @Test fun shelveForwardsTheActorAndQueryForwardsTheCaller() {
        open("caller").use { (from, to) ->
            to.tags.configure(TagConfig.fromSpec(linkedMapOf(
                "path" to "tank/level",
                "type" to "memory",
                "dataType" to "Double",
                "value" to 0.0,
                "readPermissions" to linkedMapOf("roles" to listOf("driver")),
                "writePermissions" to linkedMapOf("roles" to listOf("driver")),
                "alarms" to listOf(linkedMapOf(
                    "name" to "High",
                    "mode" to "AboveValue",
                    "priority" to "High",
                    "setpointA" to 100,
                )),
            )))
            to.tags.write("[default]tank/level", 150.0)
            check(to.alarms.query().size == 1)
            connect(from, to)
            to.network.putPolicy(ServicePolicy(from.network.fingerprint(), "alarms", "allow"))
            from.network.addRemoteProvider("site", "peer", "default")
            from.security.roles.create(
                "driver",
                setOf(Permissions.GATEWAY_ACCESS, Permissions.TAGS_READ, Permissions.TAGS_WRITE),
                actor = "test",
            )
            from.createUser("ada", setOf("driver"))
            from.createUser("op", setOf(DefaultRoles.OPERATOR))
            val ada = Http(from.port())
            ada.login("ada", TEST_PASSWORD)
            val op = Http(from.port())
            op.login("op", TEST_PASSWORD)
            waitUntil({ ada.get("/api/v1/alarms").body }) {
                (ada.get("/api/v1/alarms").json["alarms"] as List<*>).size == 1
            }
            val hidden = op.get("/api/v1/alarms")
            assertEquals(200, hidden.status, hidden.body)
            assertTrue((hidden.json["alarms"] as List<*>).isEmpty(), hidden.body)
            val row = (ada.get("/api/v1/alarms").json["alarms"] as List<*>).single() as Map<*, *>
            val source = row["source"] as String
            val refused = op.post("/api/v1/alarms/shelve", """{"sources":["$source"],"durationSeconds":60}""")
            assertEquals(403, refused.status, refused.body)
            assertFalse(to.alarms.isShelved("prov:default:/tag:tank/level:/alm:High"))
            val shelved = ada.post("/api/v1/alarms/shelve", """{"sources":["$source"],"durationSeconds":120}""")
            assertEquals(200, shelved.status, shelved.body)
            val shelf = to.alarms.shelved(AlarmFilter()).single()
            assertEquals("ada", shelf.shelvedBy)
            val journal = to.alarms.journal.records().single { it.eventType == JournalEventTypes.SHELVED }
            assertEquals("ada", journal.actor)
            val adaListed = ada.get("/api/v1/alarms?state=shelved")
            assertEquals(200, adaListed.status, adaListed.body)
            val adaShelf = (adaListed.json["alarms"] as List<*>).single() as Map<*, *>
            assertEquals(source, adaShelf["source"])
            assertEquals("ada", adaShelf["shelvedBy"])
            assertTrue((adaShelf["shelvedUntil"] as String).isNotBlank())
            val opListed = op.get("/api/v1/alarms?state=shelved")
            assertEquals(200, opListed.status, opListed.body)
            assertTrue((opListed.json["alarms"] as List<*>).isEmpty(), opListed.body)
            val unshelved = ada.post("/api/v1/alarms/unshelve", """{"sources":["$source"]}""")
            assertEquals(200, unshelved.status, unshelved.body)
            val opened = to.alarms.journal.records().single { it.eventType == JournalEventTypes.UNSHELVED }
            assertEquals("ada", opened.actor)
        }
    }

    @Test fun protocolOneQueryDoesNotRequireACaller() {
        open("legacy").use { (from, to) ->
            from.network.advertisedProtocol = 1
            to.tags.configure(TagConfig.fromSpec(linkedMapOf(
                "path" to "tank/level",
                "type" to "memory",
                "dataType" to "Double",
                "value" to 150.0,
                "readPermissions" to linkedMapOf("roles" to listOf("driver")),
                "alarms" to listOf(linkedMapOf(
                    "name" to "High",
                    "mode" to "AboveValue",
                    "priority" to "High",
                    "setpointA" to 100,
                )),
            )))
            check(to.alarms.query().size == 1)
            connect(from, to)
            to.network.putPolicy(ServicePolicy(from.network.fingerprint(), "alarms", "allow"))
            from.network.addRemoteProvider("site", "peer", "default")
            waitUntil({ from.network.pullRemoteAlarms("site").size.toString() }) {
                from.network.pullRemoteAlarms("site").size == 1
            }
        }
    }

    @Test fun requesterShelvedListIncludesThePeerShelf() {
        open("shelf-list").use { (from, to) ->
            raise(to)
            connect(from, to)
            to.network.putPolicy(ServicePolicy(from.network.fingerprint(), "alarms", "allow"))
            from.network.addRemoteProvider("site", "peer", "default")
            val http = Http(from.port())
            from.loginAdmin(http)
            waitUntil({ http.get("/api/v1/alarms").body }) {
                (http.get("/api/v1/alarms").json["alarms"] as List<*>).size == 1
            }
            val row = (http.get("/api/v1/alarms").json["alarms"] as List<*>).single() as Map<*, *>
            val source = row["source"] as String
            val shelved = http.post("/api/v1/alarms/shelve", """{"sources":["$source"],"durationSeconds":120}""")
            assertEquals(200, shelved.status, shelved.body)
            val listed = http.get("/api/v1/alarms?state=shelved")
            assertEquals(200, listed.status, listed.body)
            val shelf = (listed.json["alarms"] as List<*>).single() as Map<*, *>
            assertEquals(source, shelf["source"])
            assertEquals("admin", shelf["shelvedBy"])
            assertEquals("prov:site:/tag:tank/level:/alm:High", shelf["source"])
            assertTrue((shelf["shelvedUntil"] as String).isNotBlank())
            val owner = Http(to.port())
            to.loginAdmin(owner)
            val ownerListed = owner.get("/api/v1/alarms?state=shelved")
            val ownerShelf = (ownerListed.json["alarms"] as List<*>).single() as Map<*, *>
            assertEquals("prov:default:/tag:tank/level:/alm:High", ownerShelf["source"])
            assertEquals("admin", ownerShelf["shelvedBy"])
            val cleared = http.post("/api/v1/alarms/unshelve", """{"sources":["$source"]}""")
            assertEquals(200, cleared.status, cleared.body)
            assertTrue((http.get("/api/v1/alarms?state=shelved").json["alarms"] as List<*>).isEmpty())
        }
    }

    @Test fun droppedConnectionResubscribesWithCurrentState() {
        open("resume").use { (from, to) ->
            raise(to)
            connect(from, to)
            to.network.putPolicy(ServicePolicy(from.network.fingerprint(), "alarms", "allow"))
            from.network.addRemoteProvider("site", "peer", "default")
            val seen = CopyOnWriteArrayList<List<AlarmEventView>>()
            from.network.watchRemoteAlarms("site") { seen.add(it) }
            waitUntil({ seen.map { it.map { event -> event.acknowledged } }.toString() }) {
                seen.any { it.any { event -> event.active && !event.acknowledged } }
            }
            val before = seen.size
            val connects = from.network.outgoingConnects("peer")
            to.network.faultSessions(true)
            waitUntil({ from.network.outgoingStatus("peer") }) { from.network.outgoingStatus("peer") != "running" }
            val ownerId = to.alarms.query().single().eventId
            to.alarms.acknowledge(listOf(ownerId), "local", null)
            to.network.faultSessions(false)
            waitUntil({
                "status=${from.network.outgoingStatus("peer")} connects=${from.network.outgoingConnects("peer")} " +
                    "seen=$before/${seen.size} last=${seen.lastOrNull()?.map { it.acknowledged }}"
            }) {
                from.network.outgoingStatus("peer") == "running" &&
                    from.network.outgoingConnects("peer") > connects &&
                    seen.size > before &&
                    seen.last().any { it.acknowledged }
            }
        }
    }

    private fun raise(gateway: Gateway) {
        gateway.tags.configure(TagConfig.fromSpec(linkedMapOf(
            "path" to "tank/level",
            "type" to "memory",
            "dataType" to "Double",
            "value" to 0.0,
            "alarms" to listOf(linkedMapOf(
                "name" to "High",
                "mode" to "AboveValue",
                "priority" to "High",
                "setpointA" to 100,
            )),
        )))
        gateway.tags.write("[default]tank/level", 150.0)
        check(gateway.alarms.query().size == 1) { "alarm did not become active" }
    }

    private fun connect(from: Gateway, to: Gateway) {
        from.network.addOutgoing("peer", "127.0.0.1", to.network.port())
        waitUntil({ from.network.statusDocument().toString() }) {
            val row = peers(from, "outgoing").first()
            row["certificateState"] == "pending" || row["status"] == "pending"
        }
        from.network.approveOutgoing("peer")
        waitUntil({ to.network.statusDocument().toString() }) { peers(to, "incoming").isNotEmpty() }
        val fingerprint = peers(to, "incoming").first()["fingerprint"] as String
        to.network.approveIncoming(fingerprint)
        waitUntil({ from.network.outgoingStatus("peer") }) { from.network.outgoingStatus("peer") == "running" }
    }

    private fun open(name: String): TwoGateways {
        val root = tmp.resolve(name)
        val from = start(root.resolve("from"), "gateway-a")
        val to = try {
            start(root.resolve("to"), "gateway-b")
        } catch (error: Throwable) {
            from.close()
            throw error
        }
        return TwoGateways(from, to)
    }

    private fun start(dir: Path, name: String): Gateway {
        dir.createDirectories()
        dir.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: false
            gatewayNetwork:
              name: $name
              port: 0
              pingRateMillis: 40
              pingTimeoutMillis: 400
              missedPings: 1
              backoffInitialMillis: 20
              backoffCapMillis: 80
            """.trimIndent(),
        )
        return Gateway(dir, port = 0).also { it.start() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun peers(gateway: Gateway, key: String): List<Map<String, Any?>> =
        gateway.network.statusDocument()[key] as List<Map<String, Any?>>

    private fun waitUntil(detail: () -> String, timeoutMs: Long = 8_000, body: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var error = ""
        while (System.nanoTime() < deadline) {
            try {
                if (body()) return
            } catch (thrown: Throwable) {
                error = thrown.message ?: thrown.toString()
            }
            Thread.sleep(25)
        }
        throw AssertionError(detail() + (if (error.isEmpty()) "" else " ($error)"))
    }

    private class TwoGateways(val from: Gateway, val to: Gateway) : AutoCloseable {
        operator fun component1() = from
        operator fun component2() = to
        override fun close() {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }
}
