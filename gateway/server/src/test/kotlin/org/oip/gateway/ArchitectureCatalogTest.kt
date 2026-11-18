package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.mqtt.MqttConnectionConfig
import org.oip.mqtt.MqttDriverModule
import org.oip.network.ServicePolicy
import org.oip.security.DefaultRoles
import org.oip.security.SecurityZone
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * GAN-006, GAN-007, GAN-008, GAN-011, GAN-012, GAN-013, RED-001, RED-004.
 * The catalog is computed from the running gateway, not from a static page.
 */
class ArchitectureCatalogTest {
    @TempDir lateinit var tmp: Path

    @Test fun standardGatewayMatchesSingleServerAndReportsSubsystemStorage() {
        val gateway = start(tmp.resolve("standard"), "profile: standard\nopcua:\n  enabled: false\n")
        gateway.use {
            assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/architectures"))
            assertEquals(null, GatewayAuth.explicitRule("POST", "/api/v1/architectures"))
            val http = Http(it.port())
            assertEquals(401, http.get("/api/v1/architectures").status)
            it.loginAdmin(http)
            val body = catalog(http)
            assertTrue(fits(body, "single-server"))
            assertFalse(fits(body, "enterprise"))
            assertFalse(fits(body, "edge-panel"))
            assertFalse(fits(body, "edge-iiot"))
            assertFalse(fits(body, "edge-sync"))
            assertFalse(fits(body, "cloud"))
            assertFalse(fits(body, "scale-out"))
            assertFalse(fits(body, "segmentation"))
            assertTrue(fits(body, "independent"))
            assertFalse(fits(body, "warm-pair"))
            assertFalse(fits(body, "cold-pair"))
            assertEquals("copied", subsystem(body, "config")["redundancy"])
            assertEquals("file-store", subsystem(body, "config")["database"])
            assertEquals("copied", subsystem(body, "live-alarms")["redundancy"])
            assertEquals("active-node", subsystem(body, "alarm-journal")["redundancy"])
            assertEquals("local-file", subsystem(body, "alarm-journal")["database"])
            assertEquals("buffer", subsystem(body, "history")["redundancy"])
            assertEquals("none", subsystem(body, "history")["database"])
            assertTrue((subsystem(body, "history")["detail"] as String).contains("partial"))
            assertEquals("node-local", subsystem(body, "certificates")["redundancy"])
            assertEquals("node-local", subsystem(body, "gateway-network-identity")["redundancy"])
            assertEquals("none", subsystem(body, "certificates")["database"])
            it.createUser("view", setOf(DefaultRoles.VIEWER))
            val viewer = Http(it.port())
            viewer.login("view", TEST_PASSWORD)
            assertEquals(403, viewer.get("/api/v1/architectures").status)
        }
    }

    @Test fun publicAddressMatchesCloudHosting() {
        val gateway = start(
            tmp.resolve("cloud"),
            """
            profile: standard
            http:
              publicAddress: gateway.example.test
            opcua:
              enabled: false
            """.trimIndent(),
        )
        gateway.use {
            val http = Http(it.port())
            it.loginAdmin(http)
            val body = catalog(http)
            assertTrue(fits(body, "cloud"))
            assertFalse(fits(body, "edge-panel"))
            val reachable = check(body, "cloud", "reachable")
            assertEquals(true, reachable["met"])
        }
    }

    @Test fun edgeProfileMatchesPanelUntilABrokerIsConfigured() {
        val gateway = start(tmp.resolve("edge"), "profile: edge\nopcua:\n  enabled: false\n")
        gateway.use {
            val http = Http(it.port())
            it.loginAdmin(http)
            val before = catalog(http)
            assertTrue(fits(before, "edge-panel"))
            assertTrue(fits(before, "single-server"))
            assertFalse(fits(before, "edge-iiot"))
            assertFalse(fits(before, "cloud"))
            assertFalse(fits(before, "edge-sync"))
            val mqtt = it.host.instance(MqttDriverModule.MODULE_ID) as MqttDriverModule
            mqtt.connect(MqttConnectionConfig(name = "plant", host = "127.0.0.1", port = 1, clientId = "arch-test"))
            try {
                val after = catalog(http)
                assertTrue(fits(after, "edge-iiot"))
                assertEquals(true, check(after, "edge-iiot", "broker-or-node")["met"])
                assertTrue(fits(after, "edge-panel"))
            } finally {
                mqtt.remove("plant")
            }
        }
    }

    @Test fun edgeSyncSpokeMatchesTheSyncTopology() {
        val gateway = start(
            tmp.resolve("spoke"),
            """
            profile: edge
            opcua:
              enabled: false
            edgeSync:
              remoteGateway: hub
              history:
                enabled: true
                remoteProvider: default
            """.trimIndent(),
        )
        gateway.use {
            val http = Http(it.port())
            it.loginAdmin(http)
            val body = catalog(http)
            assertTrue(fits(body, "edge-sync"))
            assertFalse(fits(body, "edge-panel"))
            assertFalse(fits(body, "single-server"))
            assertEquals(true, (body["node"] as Map<*, *>)["edgeSpoke"])
        }
    }

    @Test fun hubProfileMatchesEdgeSyncWithoutASpokeStream() {
        val gateway = start(tmp.resolve("hub"), "profile: hub\nopcua:\n  enabled: false\n")
        gateway.use {
            val http = Http(it.port())
            it.loginAdmin(http)
            val body = catalog(http)
            assertTrue(fits(body, "edge-sync"))
            assertFalse(fits(body, "edge-panel"))
        }
    }

    @Test fun customZoneAndExplicitPolicyMatchSegmentationUntilAWildcardAllow() {
        val gateway = start(
            tmp.resolve("zones"),
            """
            profile: standard
            opcua:
              enabled: false
            gatewayNetwork:
              name: segmented
              port: 0
            """.trimIndent(),
        )
        gateway.use {
            it.security.zones.create(SecurityZone("plant", ipAddresses = listOf("192.0.2.10"), priority = 10), actor = "test")
            it.network.putPolicy(ServicePolicy(it.network.fingerprint(), "alarms", "read"))
            val http = Http(it.port())
            it.loginAdmin(http)
            val matched = catalog(http)
            assertTrue(fits(matched, "segmentation"))
            assertEquals(true, check(matched, "segmentation", "custom-zone")["met"])
            assertEquals(true, check(matched, "segmentation", "not-wide-open")["met"])
            it.network.putPolicy(ServicePolicy("*", "alarms", "allow"))
            val opened = catalog(http)
            assertFalse(fits(opened, "segmentation"))
            assertEquals(false, check(opened, "segmentation", "not-wide-open")["met"])
        }
    }

    @Test fun peersMatchEnterpriseAndScaleOut() {
        val root = tmp.resolve("pair")
        val from = start(
            root.resolve("from"),
            """
            profile: standard
            opcua:
              enabled: false
            gatewayNetwork:
              name: gateway-a
              port: 0
              pingRateMillis: 40
              pingTimeoutMillis: 400
              missedPings: 1
              backoffInitialMillis: 20
              backoffCapMillis: 80
            """.trimIndent(),
        )
        val to = try {
            start(
                root.resolve("to"),
                """
                profile: standard
                opcua:
                  enabled: false
                gatewayNetwork:
                  name: gateway-b
                  port: 0
                  pingRateMillis: 40
                  pingTimeoutMillis: 400
                  missedPings: 1
                  backoffInitialMillis: 20
                  backoffCapMillis: 80
                """.trimIndent(),
            )
        } catch (error: Throwable) {
            from.close()
            throw error
        }
        from.use {
            to.use {
                from.network.addOutgoing("peer", "127.0.0.1", to.network.port())
                waitUntil { peers(from, "outgoing").first()["certificateState"] == "pending" || peers(from, "outgoing").first()["status"] == "pending" }
                from.network.approveOutgoing("peer")
                waitUntil { peers(to, "incoming").isNotEmpty() }
                val fingerprint = peers(to, "incoming").first()["fingerprint"] as String
                to.network.approveIncoming(fingerprint)
                waitUntil { from.network.outgoingStatus("peer") == "running" }
                from.network.addRemoteProvider("site", "peer", "default")
                val front = Http(from.port())
                from.loginAdmin(front)
                val frontBody = catalog(front)
                assertTrue(fits(frontBody, "enterprise"))
                assertTrue(fits(frontBody, "scale-out"))
                assertEquals(true, check(frontBody, "scale-out", "front-end")["met"])
                assertFalse(fits(frontBody, "single-server"))
                val back = Http(to.port())
                to.loginAdmin(back)
                val backBody = catalog(back)
                assertTrue(fits(backBody, "enterprise"))
                assertTrue(fits(backBody, "scale-out"))
                assertEquals(true, check(backBody, "scale-out", "back-end")["met"])
                assertFalse(fits(backBody, "single-server"))
            }
        }
    }

    @Test fun masterWithWarmStandbyMatchesTheWarmPair() {
        val gateway = start(
            tmp.resolve("warm"),
            """
            profile: standard
            opcua:
              enabled: false
            gatewayNetwork:
              port: 0
            redundancy:
              mode: master
              startupAllowanceMillis: 0
              historyMode: full
            """.trimIndent(),
        )
        gateway.use {
            val http = Http(it.port())
            it.loginAdmin(http)
            val body = catalog(http)
            assertTrue(fits(body, "warm-pair"))
            assertFalse(fits(body, "cold-pair"))
            assertFalse(fits(body, "independent"))
            assertFalse(fits(body, "single-server"))
            assertTrue((subsystem(body, "history")["detail"] as String).contains("full"))
        }
    }

    @Test fun masterWithColdStandbyMatchesTheColdPair() {
        val gateway = start(
            tmp.resolve("cold"),
            """
            profile: standard
            opcua:
              enabled: false
            gatewayNetwork:
              port: 0
            redundancy:
              mode: master
              standbyActivityLevel: cold
              startupAllowanceMillis: 0
            """.trimIndent(),
        )
        gateway.use {
            val http = Http(it.port())
            it.loginAdmin(http)
            val body = catalog(http)
            assertTrue(fits(body, "cold-pair"))
            assertFalse(fits(body, "warm-pair"))
            assertFalse(fits(body, "independent"))
        }
    }

    private fun start(dir: Path, yaml: String): Gateway {
        dir.createDirectories()
        dir.resolve("gateway.yaml").writeText(yaml.trim() + "\n")
        return Gateway(dir, port = 0).also { it.start() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun catalog(http: Http): Map<String, Any?> {
        val response = http.get("/api/v1/architectures")
        assertEquals(200, response.status, response.body)
        return response.json
    }

    @Suppress("UNCHECKED_CAST")
    private fun fits(body: Map<String, Any?>, id: String): Boolean =
        (body["architectures"] as List<*>).map { it as Map<*, *> }.single { it["id"] == id }["fits"] == true

    @Suppress("UNCHECKED_CAST")
    private fun check(body: Map<String, Any?>, architecture: String, id: String): Map<*, *> {
        val row = (body["architectures"] as List<*>).map { it as Map<*, *> }.single { it["id"] == architecture }
        return (row["checks"] as List<*>).map { it as Map<*, *> }.single { it["id"] == id }
    }

    @Suppress("UNCHECKED_CAST")
    private fun subsystem(body: Map<String, Any?>, id: String): Map<*, *> =
        (body["subsystems"] as List<*>).map { it as Map<*, *> }.single { it["id"] == id }

    @Suppress("UNCHECKED_CAST")
    private fun peers(gateway: Gateway, key: String): List<Map<String, Any?>> =
        gateway.network.statusDocument()[key] as List<Map<String, Any?>>

    private fun waitUntil(timeoutMs: Long = 8_000, body: () -> Boolean) {
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
        throw AssertionError(if (error.isEmpty()) "condition was not met" else error)
    }
}
