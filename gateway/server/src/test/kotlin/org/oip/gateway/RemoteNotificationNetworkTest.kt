package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.notify.NOTIFY_MODULE
import org.oip.alarm.notify.NotifyKinds
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.network.ServicePolicy
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A notification profile on one gateway hands the event to a profile on a peer.
 */
class RemoteNotificationNetworkTest {
    @TempDir lateinit var tmp: Path

    @Test fun alarmNotificationIsDeliveredToThePeerProfile() {
        val root = tmp.resolve("forward")
        val from = start(root.resolve("from"), "gateway-a")
        val to = try {
            start(root.resolve("to"), "gateway-b")
        } catch (error: Throwable) {
            from.close()
            throw error
        }
        try {
            connect(from, to)
            put(to, NotifyKinds.EMAIL, "plant", mapOf("fromAddress" to "alarms@example.com", "hostname" to "127.0.0.1"))
            put(from, NotifyKinds.REMOTE, "to-plant", mapOf("connection" to "peer", "remoteProfile" to "plant"))
            put(from, NotifyKinds.PIPELINE, "active", mapOf(
                "blocks" to listOf(
                    mapOf("id" to "start", "type" to "start", "next" to "send"),
                    mapOf("id" to "send", "type" to "notification", "profile" to "to-plant", "next" to "end"),
                    mapOf("id" to "end", "type" to "end"),
                ),
            ))
            from.store.put(Scope.GATEWAY, tag(), actor = "test")
            to.network.putPolicy(ServicePolicy(from.network.fingerprint(), "notifications", "deny"))
            from.tags.write("[default]tank/level", 150.0)
            Thread.sleep(400)
            assertTrue(to.notifications.delivered().isEmpty())
            to.network.putPolicy(ServicePolicy(from.network.fingerprint(), "notifications", "allow"))
            from.tags.write("[default]tank/level", 10.0)
            from.tags.write("[default]tank/level", 160.0)
            waitUntil {
                to.notifications.delivered().any { it["profile"] == "plant" && it["sender"] == "gateway-a" }
            }
            val http = Http(to.port())
            to.loginAdmin(http)
            val listed = http.get("/api/v1/notifications/delivered")
            assertEquals(200, listed.status, listed.body)
            assertTrue(listed.body.contains("plant"))
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    private fun tag() = Resource(
        TAG_KIND,
        Metadata("level", TAGS_MODULE),
        mapOf(
            "path" to "tank/level",
            "type" to "memory",
            "dataType" to "Double",
            "value" to 0,
            "alarms" to listOf(mapOf(
                "name" to "High",
                "mode" to "AboveValue",
                "priority" to "High",
                "setpointA" to 100,
                "activePipeline" to "active",
                "displayPath" to "Tank",
            )),
        ),
    )

    private fun put(gateway: Gateway, kind: String, name: String, spec: Map<String, Any?>) {
        gateway.store.put(Scope.GATEWAY, Resource(kind, Metadata(name, NOTIFY_MODULE), spec), actor = "test")
    }

    private fun connect(from: Gateway, to: Gateway) {
        from.network.addOutgoing("peer", "127.0.0.1", to.network.port())
        waitUntil {
            val row = (from.network.statusDocument()["outgoing"] as List<*>).first() as Map<*, *>
            row["certificateState"] == "pending" || row["status"] == "pending"
        }
        from.network.approveOutgoing("peer")
        waitUntil { (to.network.statusDocument()["incoming"] as List<*>).isNotEmpty() }
        val fingerprint = ((to.network.statusDocument()["incoming"] as List<*>).first() as Map<*, *>)["fingerprint"] as String
        to.network.approveIncoming(fingerprint)
        waitUntil { from.network.outgoingStatus("peer") == "running" }
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

    private fun waitUntil(timeoutMs: Long = 8_000, body: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (body()) return
            Thread.sleep(25)
        }
        error("timed out")
    }
}
