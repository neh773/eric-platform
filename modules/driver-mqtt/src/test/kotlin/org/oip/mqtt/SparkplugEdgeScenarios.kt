package org.oip.mqtt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Edge node against this driver's own Sparkplug host ([MqttConnection]) through a real broker.
 * [SparkplugEdgeLocalTest] runs these on an in-process broker; [SparkplugEdgeBrokerTest] on Mosquitto.
 */
internal abstract class SparkplugEdgeScenarios {
    protected abstract fun brokerPort(): Int

    private val unique = AtomicInteger()

    private fun names(): Triple<String, String, String> {
        val n = "${System.nanoTime() % 100_000}-${unique.incrementAndGet()}"
        return Triple("Plant$n", "Line$n", "scada$n")
    }

    protected fun feed() = FakeFeed().apply {
        set("[default]Line/Speed", 12.5, dataType = "Double")
        set("[default]Line/Setpoint", 40, dataType = "Int32")
        set("[default]Line/Filler/Count", 7, dataType = "Int32")
    }

    protected fun edgeConfig(group: String, edge: String, primary: String?) = SparkplugEdgeConfig(
        name = edge,
        host = "127.0.0.1",
        port = brokerPort(),
        clientId = "oip-edge-$edge",
        groupId = group,
        edgeNodeId = edge,
        primaryHostId = primary,
        nodeMetrics = listOf(
            EdgeMetricConfig("Speed", "[default]Line/Speed"),
            EdgeMetricConfig("Setpoint", "[default]Line/Setpoint", writable = true),
        ),
        devices = listOf(EdgeDeviceConfig("Filler", listOf(EdgeMetricConfig("Count", "[default]Line/Filler/Count", writable = true)))),
        publishPeriodMs = 10,
        reconnectInitialMs = 50,
        reconnectMaxMs = 200,
        connectTimeoutMs = 2_000,
    )

    protected fun host(group: String, hostId: String) = MqttConnection(
        MqttConnectionConfig(
            name = "host-$group",
            host = "127.0.0.1",
            port = brokerPort(),
            clientId = "oip-host-$group",
            sparkplugHostId = hostId,
            sparkplugGroup = group,
            autoCreateTags = false,
            reconnectInitialMs = 50,
            reconnectMaxMs = 200,
            connectTimeoutMs = 2_000,
        ),
        secrets = { null },
    )

    /**
     * Sends an NCMD write until the edge node reports a rejected command. While the node is not born,
     * that proves it is subscribed and processing messages, without a fixed sleep.
     */
    protected fun probe(edge: SparkplugEdgeNode, group: String, edgeId: String) {
        val before = edge.rejectedCommands()
        MqttClient("127.0.0.1", brokerPort(), "oip-probe-$edgeId").use { cmd ->
            cmd.connect()
            awaitTrue("edge processed a command") {
                cmd.publish(
                    "spBv1.0/$group/NCMD/$edgeId",
                    SparkplugSession.commandPayload("Setpoint", SparkplugCodec.INT32, 1, System.currentTimeMillis()),
                )
                Thread.sleep(50)
                edge.rejectedCommands() > before
            }
        }
    }

    @Test fun birthDataWritesRebirthAndDeathReachTheHost() {
        val (group, edgeId, hostId) = names()
        val tags = feed()
        val edge = SparkplugEdgeNode(edgeConfig(group, edgeId, hostId), { tags })
        val host = host(group, hostId)
        edge.start()
        try {
            awaitTrue("edge connected") { edge.connected() }
            probe(edge, group, edgeId)
            assertEquals(0, edge.birthCount(), "no birth before the primary host is online")
            assertTrue(tags.writes.isEmpty(), "a command before NBIRTH is not written")
            host.start()
            awaitTrue("births reach the host") {
                host.read(Addresses.sparkplug(group, edgeId, "", "Speed")).value == 12.5 &&
                    host.read(Addresses.sparkplug(group, edgeId, "Filler", "Count")).value == 7
            }
            assertEquals(1, edge.birthCount())
            assertEquals(40, host.read(Addresses.sparkplug(group, edgeId, "", "Setpoint")).value)

            tags.set("[default]Line/Speed", 13.0)
            awaitTrue("NDATA") { host.read(Addresses.sparkplug(group, edgeId, "", "Speed")).value == 13.0 }
            tags.set("[default]Line/Filler/Count", 8)
            awaitTrue("DDATA") { host.read(Addresses.sparkplug(group, edgeId, "Filler", "Count")).value == 8 }
            tags.set("[default]Line/Speed", 13.0, good = false)
            awaitTrue("quality loss as null") { host.read(Addresses.sparkplug(group, edgeId, "", "Speed")).value == null }
            tags.set("[default]Line/Speed", 14.0)
            awaitTrue("value back") { host.read(Addresses.sparkplug(group, edgeId, "", "Speed")).value == 14.0 }

            assertEquals(0L, host.write(Addresses.sparkplug(group, edgeId, "", "Setpoint"), 55).quality.statusCode)
            awaitTrue("NCMD write") { tags.writes.contains("[default]Line/Setpoint" to 55) }
            awaitTrue("written value published") { host.read(Addresses.sparkplug(group, edgeId, "", "Setpoint")).value == 55 }
            assertEquals(0L, host.write(Addresses.sparkplug(group, edgeId, "Filler", "Count"), 99).quality.statusCode)
            awaitTrue("DCMD write") { tags.writes.any { it.first == "[default]Line/Filler/Count" && (it.second as Number).toLong() == 99L } }
            awaitTrue("device write published") { host.read(Addresses.sparkplug(group, edgeId, "Filler", "Count")).value == 99 }
            host.write(Addresses.sparkplug(group, edgeId, "", "Speed"), 1.0)
            host.write(Addresses.sparkplug(group, edgeId, "", "Setpoint"), 56)
            // Commands are handled in order on one thread, so once 56 is written the Speed command was seen.
            awaitTrue("later write") { tags.writes.contains("[default]Line/Setpoint" to 56) }
            assertTrue(tags.writes.none { it.first == "[default]Line/Speed" }, "a metric that is not writable ignores commands")

            MqttClient("127.0.0.1", brokerPort(), "oip-cmd-$edgeId").use { cmd ->
                cmd.connect()
                cmd.publish("spBv1.0/$group/NCMD/$edgeId", SparkplugSession.rebirthPayload(System.currentTimeMillis()))
            }
            awaitTrue("rebirth") { edge.birthCount() == 2 }
            assertTrue(tags.listenerThreads.none { it == "sparkplug-edge-$edgeId" }, "the socket thread never runs tag listeners")

            edge.close()
            awaitTrue("NDEATH marks node metrics stale") {
                host.read(Addresses.sparkplug(group, edgeId, "", "Speed")).quality.statusCode == SparkplugCodec.STALE
            }
            assertEquals(0, tags.listenerCount(), "close removes tag subscriptions")
        } finally {
            edge.close()
            host.close()
        }
    }

    @Test fun primaryHostOfflineEndsTheSessionAndOnlineBirthsAgain() {
        val (group, edgeId, hostId) = names()
        val tags = feed()
        val counter = AtomicLong(0)
        val edge = SparkplugEdgeNode(edgeConfig(group, edgeId, hostId), { tags }, bdSeqCounter = counter)
        var host = host(group, hostId)
        edge.start()
        host.start()
        try {
            awaitTrue("born") { edge.online() }
            val connects = edge.connectCount()
            host.close()
            awaitTrue("edge reconnects after the host goes offline") { edge.connectCount() > connects }
            probe(edge, group, edgeId)
            assertEquals(1, edge.birthCount(), "no birth while the host is offline")
            host = host(group, hostId)
            host.start()
            awaitTrue("born again") { edge.birthCount() == 2 && edge.online() }
            awaitTrue("host has values") { host.read(Addresses.sparkplug(group, edgeId, "", "Speed")).value == 12.5 }
            assertTrue(counter.get() >= 2, "each connect takes a new bdSeq")
        } finally {
            edge.close()
            host.close()
        }
    }
}
