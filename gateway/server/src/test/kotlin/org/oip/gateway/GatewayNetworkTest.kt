package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.network.ServicePolicy
import org.oip.security.AccessDeniedException
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.tags.DataType
import org.oip.tags.QualifiedValue
import org.oip.tags.TAG_WRITE_ACTION
import org.oip.tags.TagCaller
import org.oip.tags.TagConfig
import org.oip.tags.TagType
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Two gateways on loopback, mutual TLS, approval, reconnect, remote tag subscribe, and a denied service.
 *
 */
class GatewayNetworkTest {
    @TempDir lateinit var tmp: Path

    @Test fun twoGatewaysConnectWithMutualTls() {
        open("connect").use { (from, to) ->
            connect(from, to)
            assertEquals("running", from.network.outgoingStatus("peer"))
            assertTrue(from.network.outgoingConnects("peer") >= 1)
            val incoming = peers(to, "incoming").first()
            assertEquals(from.network.fingerprint(), incoming["fingerprint"])
            assertEquals("running", incoming["status"])
        }
    }

    @Test fun incomingConnectionIsRejectedUntilApproved() {
        open("reject").use { (from, to) ->
            from.network.addOutgoing("peer", "127.0.0.1", to.network.port())
            waitUntil({ from.network.statusDocument().toString() }) {
                val row = peers(from, "outgoing").first()
                row["certificateState"] == "pending" || row["status"] == "pending"
            }
            assertNotEquals("running", from.network.outgoingStatus("peer"))
            from.network.approveOutgoing("peer")
            waitUntil({ to.network.statusDocument().toString() }) { peers(to, "incoming").isNotEmpty() }
            Thread.sleep(200)
            assertNotEquals("running", from.network.outgoingStatus("peer"))
            assertEquals("pending", peers(to, "incoming").first()["state"])
        }
    }

    @Test fun revokedCertificateCannotReconnect() {
        open("revoke").use { (from, to) ->
            connect(from, to)
            to.network.denyIncoming(from.network.fingerprint())
            waitUntil({ from.network.outgoingStatus("peer") }) { from.network.outgoingStatus("peer") != "running" }
            Thread.sleep(250)
            assertNotEquals("running", from.network.outgoingStatus("peer"))
        }
    }

    @Test fun reconnectsAfterTheSessionDrops() {
        open("reconnect").use { (from, to) ->
            connect(from, to)
            val before = from.network.outgoingConnects("peer")
            to.network.faultSessions(true)
            waitUntil({ from.network.outgoingStatus("peer") }) { from.network.outgoingStatus("peer") != "running" }
            to.network.faultSessions(false)
            waitUntil({ "status=${from.network.outgoingStatus("peer")} connects=${from.network.outgoingConnects("peer")}" }) {
                from.network.outgoingStatus("peer") == "running" && from.network.outgoingConnects("peer") > before
            }
        }
    }

    @Test fun remoteTagSubscribeDeliversChanges() {
        open("tags").use { (from, to) ->
            to.tags.configure(TagConfig(
                path = "tank/level",
                type = TagType.MEMORY,
                dataType = DataType.DOUBLE,
                value = 1.0,
                valueSet = true,
            ))
            connect(from, to)
            from.network.addRemoteProvider("site", "peer", "default")
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            from.tags.subscribe("[site]tank/level") { seen.add(it.value) }
            waitUntil({ seen.map { it.value }.toString() }) { seen.isNotEmpty() }
            assertEquals(1.0, number(seen.last().value))
            to.tags.write("[default]tank/level", 4.0)
            waitUntil({ seen.map { it.value }.toString() }) { seen.any { number(it.value) == 4.0 } }
        }
    }

    @Test fun servicePolicyDeniesRemoteTags() {
        open("policy").use { (from, to) ->
            to.tags.configure(TagConfig(
                path = "tank/level",
                type = TagType.MEMORY,
                dataType = DataType.DOUBLE,
                value = 3.0,
                valueSet = true,
            ))
            to.tags.configure(TagConfig(
                path = "secret",
                type = TagType.MEMORY,
                dataType = DataType.DOUBLE,
                value = 7.0,
                valueSet = true,
                readPermissions = setOf("driver"),
            ))
            connect(from, to)
            from.network.addRemoteProvider("site", "peer", "default")
            val peer = from.network.fingerprint()
            to.network.putPolicy(ServicePolicy(peer, "tags", "allow", listOf("viewer")))
            val hidden = from.tags.read("[site]secret")
            assertEquals("Bad_AccessDenied", hidden.quality.code.label)
            to.network.putPolicy(ServicePolicy(peer, "tags", "allow", listOf("driver")))
            assertEquals(7.0, number(from.tags.read("[site]secret").value))
            to.network.putPolicy(ServicePolicy(peer, "tags", "deny"))
            val denied = from.tags.read("[site]tank/level")
            assertEquals("Bad_AccessDenied", denied.quality.code.label)
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            from.tags.subscribe("[site]tank/level") { seen.add(it.value) }
            to.tags.write("[default]tank/level", 9.0)
            waitUntil({ seen.map { it.quality.code.label }.toString() }) {
                seen.isNotEmpty() && seen.all { it.quality.code.label == "Bad_AccessDenied" }
            }
            assertTrue(seen.none { number(it.value) == 9.0 })
        }
    }

    /**
     * SEC-003: a write through a remote tag provider runs on the owning gateway only under an `allow` tags policy, with
     * the policy's roles against the tag's write permissions, and every attempt (written or refused) is audited on both
     * gateways: here for the local user, there for the peer and that user, in context `gateway-network`.
     */
    @Test fun remoteTagWritesAreGatedByPolicyAndAuditedOnBothGateways() {
        open("writes").use { (from, to) ->
            to.tags.configure(TagConfig(path = "tank/setpoint", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true))
            to.tags.configure(TagConfig(path = "tank/locked", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true,
                writeAccess = org.oip.tags.TagAccess(listOf("engineer"), emptyList())))
            connect(from, to)
            from.network.addRemoteProvider("site", "peer", "default")
            val peer = from.network.fingerprint()
            val ada = TagCaller.User(actor = "ada", roles = setOf("operator"), zone = "Default", host = "10.0.0.5", tagWrite = true)
            fun remoteAudits(status: AuditStatus) = to.security.audit.query(AuditQuery(action = TAG_WRITE_ACTION, status = status, limit = 50)).records

            // No policy (read-only by default), a read policy, or a deny policy: refused, and the value is unchanged.
            assertEquals("Bad_AccessDenied", from.tags.write("[site]tank/setpoint", 5.0, ada).quality.code.label)
            to.network.putPolicy(ServicePolicy(peer, "tags", "read"))
            assertEquals("Bad_AccessDenied", from.tags.write("[site]tank/setpoint", 5.0, ada).quality.code.label)
            to.network.putPolicy(ServicePolicy(peer, "tags", "deny"))
            assertEquals("Bad", from.tags.write("[site]tank/setpoint", 5.0, ada).quality.code.label.take(3))
            assertEquals(1.0, number(to.tags.read("[default]tank/setpoint").value))
            val refusedThere = remoteAudits(AuditStatus.FAILURE)
            assertEquals(3, refusedThere.size, refusedThere.toString())
            assertTrue(refusedThere.all { it.actor.endsWith("/ada") && it.context == "gateway-network" && it.target == "[default]tank/setpoint" }, refusedThere.toString())

            // Allow: written, audited on both sides with the old and new values.
            to.network.putPolicy(ServicePolicy(peer, "tags", "allow", listOf("operator")))
            assertEquals("Good", from.tags.write("[site]tank/setpoint", 5.0, ada).quality.code.label)
            assertEquals(5.0, number(to.tags.read("[default]tank/setpoint").value))
            val written = remoteAudits(AuditStatus.SUCCESS).single()
            assertTrue(written.actor.endsWith("/ada") && written.detail.contains("old=1.0") && written.detail.contains("new=5.0"), written.toString())
            val local = from.security.audit.query(AuditQuery(action = TAG_WRITE_ACTION, actor = "ada", limit = 50)).records
            assertEquals(4, local.size, local.toString())
            assertEquals(3, local.count { it.status == AuditStatus.FAILURE })
            assertEquals(listOf("[site]tank/setpoint"), local.map { it.target }.distinct())

            // The tag's own write permissions still apply to the policy's roles.
            assertEquals("Bad_AccessDenied", from.tags.write("[site]tank/locked", 9.0, ada).quality.code.label)
            assertEquals(1.0, number(to.tags.read("[default]tank/locked").value))
        }
    }

    @Test fun regeneratedCertificateMustBeApprovedAgain() {
        open("regen").use { (from, to) ->
            connect(from, to)
            val old = from.network.fingerprint()
            from.network.regenerateCertificate()
            assertNotEquals(old, from.network.fingerprint())
            waitUntil({ from.network.outgoingStatus("peer") }) { from.network.outgoingStatus("peer") != "running" }
            waitUntil({ peers(to, "incoming").map { it["fingerprint"] to it["state"] }.toString() }) {
                peers(to, "incoming").any { it["fingerprint"] == from.network.fingerprint() && it["state"] == "pending" }
            }
            to.network.approveIncoming(from.network.fingerprint())
            waitUntil({ from.network.outgoingStatus("peer") }) { from.network.outgoingStatus("peer") == "running" }
            val http = Http(from.port())
            from.loginAdmin(http)
            val again = http.post("/api/v1/gateway-network/certificate/regenerate", "{}")
            assertEquals(200, again.status, again.body)
            assertFalse(again.body.contains("PRIVATE KEY"))
            assertNotEquals(from.network.fingerprint(), old)
        }
    }

    @Test fun remoteTaskRunsAndCanBeCancelled() {
        open("tasks").use { (from, to) ->
            connect(from, to)
            to.tags.configure(org.oip.tags.TagConfig(
                path = "tank/level",
                type = org.oip.tags.TagType.MEMORY,
                dataType = org.oip.tags.DataType.DOUBLE,
                value = 1.0,
                valueSet = true,
            ))
            val peer = from.network.fingerprint()
            val denied = assertThrows(AccessDeniedException::class.java) {
                from.network.submitTask("peer", "gateway-status", "")
            }
            assertTrue(denied.message?.isNotBlank() == true)
            to.network.putPolicy(ServicePolicy(peer, "tasks", "allow"))
            val status = from.network.submitTask("peer", "gateway-status", "")
            assertEquals("succeeded", status["state"])
            assertEquals("gateway-b", status["detail"])
            val count = from.network.submitTask("peer", "tag-count", "default")
            assertEquals("succeeded", count["state"])
            assertEquals("1", count["detail"])
            val hold = from.network.submitTask("peer", "hold", "2000")
            assertEquals("running", hold["state"])
            val id = hold["id"] as String
            val cancelled = from.network.cancelTask("peer", id)
            assertEquals("cancelled", cancelled["state"])
            val listed = from.network.statusDocument()["tasks"] as List<*>
            assertTrue(listed.isNotEmpty())
            val http = Http(from.port())
            from.loginAdmin(http)
            val posted = http.post("/api/v1/gateway-network/connections/peer/tasks", """{"name":"gateway-status"}""")
            assertEquals(200, posted.status, posted.body)
            assertEquals("succeeded", posted.json["state"])
        }
    }

    @Test fun adminApprovesIncomingConnectionThroughRest() {
        open("rest").use { (from, to) ->
            val http = Http(to.port())
            to.loginAdmin(http)
            from.network.addOutgoing("peer", "127.0.0.1", to.network.port())
            waitUntil({ from.network.statusDocument().toString() }) {
                val row = peers(from, "outgoing").first()
                row["certificateState"] == "pending" || row["status"] == "pending"
            }
            from.network.approveOutgoing("peer")
            waitUntil({ http.get("/api/v1/gateway-network").body }) {
                val body = http.get("/api/v1/gateway-network")
                body.status == 200 && (body.json["incoming"] as List<*>).isNotEmpty()
            }
            val listed = http.get("/api/v1/gateway-network")
            val fingerprint = ((listed.json["incoming"] as List<*>).first() as Map<*, *>)["fingerprint"] as String
            assertNotEquals("running", from.network.outgoingStatus("peer"))
            val approved = http.post("/api/v1/gateway-network/incoming/$fingerprint/approve", "{}")
            assertEquals(200, approved.status)
            waitUntil({ from.network.outgoingStatus("peer") }) { from.network.outgoingStatus("peer") == "running" }
            assertTrue(approved.body.contains(fingerprint))
            assertTrue(!approved.body.contains("PRIVATE KEY"))
        }
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
        waitUntil({ from.network.statusDocument().toString() }) { from.network.outgoingStatus("peer") == "running" }
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

    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()

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
