package org.oip.mqtt

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** Edge-node scenarios on the in-process [RoutingBroker], plus checks that need the broker's log. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
internal class SparkplugEdgeLocalTest : SparkplugEdgeScenarios() {
    private val broker = RoutingBroker()

    override fun brokerPort(): Int = broker.port

    @AfterAll fun stopBroker() = broker.close()

    @Test fun willCarriesTheBirthBdSeqAndAReconnectIncrementsIt() {
        val tags = feed()
        val edge = SparkplugEdgeNode(edgeConfig("WG", "WE", null), { tags })
        edge.start()
        try {
            awaitTrue("born") { edge.online() }
            val firstBirth = SparkplugCodec.decode(broker.published("spBv1.0/WG/NBIRTH/WE").last().payload)
            val firstBd = firstBirth.metrics.first { it.name == "bdSeq" }.value
            assertEquals(0, firstBirth.seq)
            broker.dropClients()
            awaitTrue("will delivered") { broker.published("spBv1.0/WG/NDEATH/WE").any { it.fromWill } }
            val will = broker.published("spBv1.0/WG/NDEATH/WE").first { it.fromWill }
            assertEquals(1, will.qos)
            assertTrue(!will.retain)
            assertEquals(firstBd, SparkplugCodec.decode(will.payload).metrics.single().value, "NDEATH bdSeq matches NBIRTH")
            // birthCount() moves when the node has written the NBIRTH, which can be before the broker has read it.
            awaitTrue("reborn") { edge.birthCount() == 2 && edge.online() && broker.published("spBv1.0/WG/NBIRTH/WE").size == 2 }
            val second = SparkplugCodec.decode(broker.published("spBv1.0/WG/NBIRTH/WE").last().payload)
            val secondBd = second.metrics.first { it.name == "bdSeq" }.value as Long
            assertTrue(secondBd != firstBd && ((secondBd - (firstBd as Long)) and 0xFF) in 1L..127L, "bdSeq increased modulo 256")

            tags.set("[default]Line/Speed", 20.0)
            awaitTrue("ndata 20") { speeds("WG", "WE").contains(20.0) }
            tags.set("[default]Line/Speed", 20.0)
            tags.set("[default]Line/Speed", 21.0)
            awaitTrue("ndata 21") { speeds("WG", "WE").contains(21.0) }
            assertEquals(listOf(20.0, 21.0), speeds("WG", "WE"), "an unchanged value is not published again")
            assertEquals(2, SparkplugCodec.decode(broker.published("spBv1.0/WG/NDATA/WE").first().payload).seq, "first NDATA after NBIRTH (0) and one DBIRTH (1)")
            assertTrue(broker.published("spBv1.0/WG/DBIRTH/WE/Filler").all { SparkplugCodec.decode(it.payload).seq == 1 })

            val deaths = broker.published("spBv1.0/WG/NDEATH/WE").size
            edge.close()
            awaitTrue("a clean stop publishes NDEATH before DISCONNECT") { broker.published("spBv1.0/WG/NDEATH/WE").size > deaths }
            val clean = broker.published("spBv1.0/WG/NDEATH/WE").last()
            assertTrue(!clean.fromWill, "published, not the will")
            assertEquals(second.metrics.first { it.name == "bdSeq" }.value, SparkplugCodec.decode(clean.payload).metrics.single().value)
        } finally {
            edge.close()
        }
    }

    private fun speeds(group: String, edge: String) = broker.published("spBv1.0/$group/NDATA/$edge")
        .flatMap { SparkplugCodec.decode(it.payload).metrics }.filter { it.name == "Speed" }.map { it.value }

    @Test fun aBurstIsCoalescedToTheNewestValue() {
        val tags = feed()
        val edge = SparkplugEdgeNode(edgeConfig("BG", "BE", null).copy(publishPeriodMs = 400), { tags })
        edge.start()
        try {
            awaitTrue("born") { edge.online() }
            for (v in 1..5) tags.set("[default]Line/Speed", v.toDouble())
            awaitTrue("newest value published") { speeds("BG", "BE").lastOrNull() == 5.0 }
            assertTrue(speeds("BG", "BE").size < 5, "intermediate values within one publish period are coalesced")
            for (v in 6..2_000) tags.set("[default]Line/Speed", v.toDouble())
            awaitTrue("newest after a large burst") { speeds("BG", "BE").lastOrNull() == 2_000.0 }
        } finally {
            edge.close()
        }
    }

    @Test fun aTagMissingAtBirthIsBornOnceItResolves() {
        val tags = FakeFeed().apply {
            set("[default]Line/Setpoint", 40, dataType = "Int32")
            set("[default]Line/Filler/Count", 7, dataType = "Int32")
        }
        val edge = SparkplugEdgeNode(edgeConfig("RG", "RE", null), { tags })
        edge.start()
        try {
            awaitTrue("born") { edge.online() }
            val first = SparkplugCodec.decode(broker.published("spBv1.0/RG/NBIRTH/RE").last().payload)
            assertTrue(first.metrics.none { it.name == "Speed" }, "no datatype yet, so not in the birth")
            tags.set("[default]Line/Speed", 3.0, dataType = "Double")
            awaitTrue("rebirth with the resolved metric") { edge.birthCount() == 2 && broker.published("spBv1.0/RG/NBIRTH/RE").size == 2 }
            val second = SparkplugCodec.decode(broker.published("spBv1.0/RG/NBIRTH/RE").last().payload)
            assertEquals(SparkplugCodec.DOUBLE, second.metrics.first { it.name == "Speed" }.datatype)
            assertEquals(3.0, second.metrics.first { it.name == "Speed" }.value)
        } finally {
            edge.close()
        }
    }

    @Test fun commandWritesAreLoggedAndTheWriteQueueIsBounded() {
        val records = java.util.concurrent.CopyOnWriteArrayList<String>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                if (record.level.intValue() >= java.util.logging.Level.INFO.intValue()) records += record.message.orEmpty()
            }
            override fun flush() {}
            override fun close() {}
        }
        val logger = java.util.logging.Logger.getLogger("org.oip.mqtt")
        logger.addHandler(handler)
        val gate = java.util.concurrent.CountDownLatch(1)
        val tags = feed()
        val edge = SparkplugEdgeNode(edgeConfig("AG", "AE", null), { tags })
        edge.start()
        try {
            awaitTrue("born") { edge.online() }
            MqttClient("127.0.0.1", broker.port, "oip-cmd-AE").use { cmd ->
                cmd.connect()
                cmd.publish("spBv1.0/AG/NCMD/AE", SparkplugSession.commandPayload("Setpoint", SparkplugCodec.INT32, 41, 1))
                awaitTrue("accepted write logged") { records.any { it.contains("command write to Setpoint") && it.contains("accepted") } }
                cmd.publish("spBv1.0/AG/NCMD/AE", SparkplugSession.commandPayload("Setpoint", SparkplugCodec.STRING, "DROP TABLE", 1))
                awaitTrue("rejected command logged") { records.any { it.contains("command for Setpoint ignored") } }
                assertTrue(tags.writes.none { it.second == "DROP TABLE" })

                tags.writeGate = gate
                repeat(400) { cmd.publish("spBv1.0/AG/NCMD/AE", SparkplugSession.commandPayload("Setpoint", SparkplugCodec.INT32, 100 + it, 1)) }
                awaitTrue("full write queue discards") { edge.discardedWrites() > 0 }
            }
        } finally {
            gate.countDown()
            edge.close()
            logger.removeHandler(handler)
        }
    }

    @Test fun stopAbortsAConnectInProgressAndTheNodeNeverConnectsLater() {
        java.net.ServerSocket(0).use { silent ->
            // Accepts TCP and never answers CONNECT.
            val accepted = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
            Thread { runCatching { while (true) accepted += silent.accept() } }.apply { isDaemon = true; start() }
            val edge = SparkplugEdgeNode(
                edgeConfig("SG", "SE", null).copy(port = silent.localPort, connectTimeoutMs = 20_000),
                { feed() },
            )
            edge.start()
            awaitTrue("connect in progress") { accepted.isNotEmpty() }
            val started = System.nanoTime()
            edge.close()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs < 5_000, "stop returned in $tookMs ms")
            assertEquals(0, edge.connectCount())
            accepted.forEach { runCatching { it.close() } }
        }
    }

    @Test fun edgeWithoutTagFeedWaitsAndDoesNotConnect() {
        val edge = SparkplugEdgeNode(edgeConfig("NG", "NE", null), { null })
        edge.start()
        try {
            Thread.sleep(300)
            assertEquals(0, edge.connectCount())
        } finally {
            edge.close()
        }
    }

    private fun traffic(group: String) = broker.log.filter { it.topic.startsWith("spBv1.0/$group/") }

    private fun bdSeqOf(routed: RoutingBroker.Routed) =
        SparkplugCodec.decode(routed.payload).metrics.first { it.name == "bdSeq" }.value as Long

    @Test fun anInactiveNodeDoesNotConnectAndBirthsWhenItBecomesActive() {
        val tags = feed()
        val edge = SparkplugEdgeNode(edgeConfig("IG", "IE", null), { tags })
        edge.setActive(false)
        edge.start()
        try {
            // Absence check: give an unwanted connect several reconnect periods to show up.
            val until = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < until) {
                assertEquals(0, edge.connectCount(), "an inactive node does not connect")
                Thread.sleep(20)
            }
            assertTrue(traffic("IG").isEmpty(), "nothing is published while inactive")

            edge.setActive(true)
            awaitTrue("born after becoming active") { edge.online() }
            val birth = SparkplugCodec.decode(broker.published("spBv1.0/IG/NBIRTH/IE").single().payload)
            assertEquals(0, birth.seq)
            assertTrue(broker.published("spBv1.0/IG/DBIRTH/IE/Filler").isNotEmpty())
            tags.set("[default]Line/Speed", 33.0)
            awaitTrue("NDATA once active") { speeds("IG", "IE").contains(33.0) }
        } finally {
            edge.close()
        }
    }

    @Test fun becomingInactivePublishesNdeathDisconnectsAndActiveAgainBirthsFresh() {
        val tags = feed()
        val edge = SparkplugEdgeNode(edgeConfig("AG", "AE", null), { tags })
        edge.start()
        try {
            awaitTrue("born") { edge.online() }
            val firstBd = bdSeqOf(broker.published("spBv1.0/AG/NBIRTH/AE").single())

            edge.setActive(false)
            awaitTrue("NDEATH published") { broker.published("spBv1.0/AG/NDEATH/AE").isNotEmpty() }
            val death = broker.published("spBv1.0/AG/NDEATH/AE").single()
            assertTrue(!death.fromWill, "published by the node, not the broker will")
            assertEquals(firstBd, SparkplugCodec.decode(death.payload).metrics.single().value, "NDEATH bdSeq matches NBIRTH")
            awaitTrue("disconnected") { !edge.connected() }

            val seen = traffic("AG").size
            tags.set("[default]Line/Speed", 44.0)
            // Absence check: several publish periods pass with no session and no publish.
            val until = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < until) {
                assertTrue(!edge.connected(), "stays disconnected while inactive")
                Thread.sleep(20)
            }
            assertEquals(seen, traffic("AG").size, "nothing is published while inactive")
            assertEquals(1, edge.connectCount())

            edge.setActive(true)
            awaitTrue("fresh birth") { edge.online() && edge.birthCount() == 2 && broker.published("spBv1.0/AG/NBIRTH/AE").size == 2 }
            assertEquals(2, edge.connectCount(), "a new session")
            val second = broker.published("spBv1.0/AG/NBIRTH/AE").last()
            val decoded = SparkplugCodec.decode(second.payload)
            assertEquals(0, decoded.seq)
            assertEquals((firstBd + 1) and 0xFF, bdSeqOf(second), "the new session uses the next bdSeq")
            assertEquals(44.0, decoded.metrics.first { it.name == "Speed" }.value, "the birth carries the value set while inactive")
        } finally {
            edge.close()
        }
    }
}
