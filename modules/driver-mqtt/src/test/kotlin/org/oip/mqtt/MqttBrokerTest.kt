package org.oip.mqtt

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Eclipse Mosquitto 2.0.22, the same image and digest as test-infra.
 * Covers Sparkplug birth, data, and death, a rebirth after a sequence gap, a write, and comms loss.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class MqttBrokerTest {
    private lateinit var mosquitto: GenericContainer<*>

    @BeforeAll fun startBroker() {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(), "Docker is not available; this test needs a container")
        mosquitto = startMosquitto()
    }

    private fun startMosquitto(hostPort: Int? = null): GenericContainer<*> {
        val image = DockerImageName.parse(
            "eclipse-mosquitto:2.0.22@sha256:09b62ff32c2e7b64d4b431b9a89236073289b68db273de2b089edeb9b0d92b5c",
        )
        val container = GenericContainer(image)
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto.conf"), "/mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)))
        if (hostPort != null) container.portBindings = listOf("$hostPort:1883")
        container.start()
        return container
    }

    @AfterAll fun stopBroker() {
        if (this::mosquitto.isInitialized) mosquitto.stop()
    }

    @Test fun birthDataAndDeath() {
        val host = host(reorderMs = 2_000)
        host.start()
        try {
            await("connected") { host.connected() }
            edge().use { edge ->
                edge.connect()
                edge.publish("spBv1.0/G/NBIRTH/E", payload(0, metric("V", 7, alias = 1), bdSeq(4), rebirth(false)), retain = true)
                edge.publish("spBv1.0/G/DBIRTH/E/Pump", payload(1, metric("Flow", 11)), retain = true)
                edge.publish("spBv1.0/G/DDATA/E/Pump", payload(2, metric("Flow", 12)))
                await("flow 12") { host.read(address("Pump", "Flow")).value == 12 }
                edge.publish("spBv1.0/G/DDEATH/E/Pump", payload(3))
                await("device stale") { host.read(address("Pump", "Flow")).quality.statusCode == SparkplugCodec.STALE }
                edge.publish("spBv1.0/G/DBIRTH/E/Pump", payload(4, metric("Flow", 13)), retain = true)
                await("flow 13") {
                    val sample = host.read(address("Pump", "Flow"))
                    sample.value == 13 && sample.quality.statusCode == 0L
                }
                edge.publish("spBv1.0/G/NDEATH/E", payload(0, bdSeq(4)), retain = true)
                await("node stale") { host.read(address("", "V")).quality.statusCode == SparkplugCodec.STALE }
                val ignored = host.read(address("Pump", "Flow")).value
                edge.publish("spBv1.0/G/NDEATH/E", payload(0, bdSeq(1)), retain = true)
                Thread.sleep(200)
                assertEquals(ignored, host.read(address("Pump", "Flow")).value)
                assertEquals(SparkplugCodec.STALE, host.read(address("Pump", "Flow")).quality.statusCode)
            }
        } finally {
            host.close()
        }
    }

    @Test fun sequenceGapRequestsRebirthOnce() {
        val host = host(reorderMs = 400)
        host.start()
        val commands = mutableListOf<ByteArray>()
        val watcher = MqttClient("127.0.0.1", mosquitto.getMappedPort(1883), "oip-watch")
        try {
            await("connected") { host.connected() }
            watcher.connect()
            watcher.subscribe("spBv1.0/G/NCMD/E")
            edge().use { edge ->
                edge.connect()
                edge.publish("spBv1.0/G/NBIRTH/E", payload(0, metric("V", 1, alias = 3), bdSeq(1)), retain = true)
                edge.publish("spBv1.0/G/NDATA/E", payload(1, metric("V", 2)))
                await("value 2") { host.read(address("", "V")).value == 2 }
                edge.publish("spBv1.0/G/NDATA/E", payload(3, metric("V", 9)))
                val deadline = System.nanoTime() + 3_000_000_000L
                while (System.nanoTime() < deadline && commands.isEmpty()) {
                    val polled = watcher.poll(100)
                    if (polled is MqttPoll.Published) commands += polled.message.payload
                }
            }
            assertEquals(1, commands.size, "expected one rebirth")
            val decoded = SparkplugCodec.decode(commands.single())
            val rebirth = decoded.metrics.single()
            assertEquals("Node Control/Rebirth", rebirth.name)
            assertEquals(true, rebirth.value)
            assertEquals(2, host.read(address("", "V")).value)
            Thread.sleep(500)
            assertEquals(1, commands.size)
        } finally {
            watcher.drop()
            host.close()
        }
    }

    @Test fun plainAndSparkplugWritesPublish() {
        val host = host(reorderMs = 2_000)
        host.start()
        val seen = mutableListOf<Pair<String, ByteArray>>()
        val watcher = MqttClient("127.0.0.1", mosquitto.getMappedPort(1883), "oip-sub")
        try {
            await("connected") { host.connected() }
            watcher.connect()
            watcher.subscribe("cmd/plain")
            watcher.subscribe("spBv1.0/G/DCMD/E/Pump")
            // SUBSCRIBE is not applied until SUBACK. A write before that is not delivered.
            var subacks = 0
            val subscribed = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < subscribed && subacks < 2) {
                when (val polled = watcher.poll(100)) {
                    is MqttPoll.Activity -> subacks++
                    is MqttPoll.Published -> seen += polled.message.topic to polled.message.payload
                    null -> {}
                }
            }
            assertEquals(2, subacks)
            assertEquals(0L, host.write("number:cmd/plain", 99).quality.statusCode)
            assertEquals(0L, host.write("spb:G/E/Pump/Flow", 5).quality.statusCode)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < deadline && seen.size < 2) {
                val polled = watcher.poll(100)
                if (polled is MqttPoll.Published) seen += polled.message.topic to polled.message.payload
            }
            val plain = seen.find { it.first == "cmd/plain" }?.second?.toString(Charsets.UTF_8)
            val sparkplug = seen.find { it.first == "spBv1.0/G/DCMD/E/Pump" }?.second
            assertEquals("99", plain)
            val command = SparkplugCodec.decode(checkNotNull(sparkplug) { "sparkplug command was published" })
            assertEquals("Flow", command.metrics.single().name)
            assertEquals(5, command.metrics.single().value)
        } finally {
            watcher.drop()
            host.close()
        }
    }

    @Test fun mqtt5PlainPublishRoundTrips() {
        val publisher = MqttClient("127.0.0.1", mosquitto.getMappedPort(1883), "oip-v5-pub", protocol = MqttProtocol.MQTT_5)
        val subscriber = MqttClient("127.0.0.1", mosquitto.getMappedPort(1883), "oip-v5-sub", protocol = MqttProtocol.MQTT_5)
        try {
            publisher.connect()
            subscriber.connect()
            subscriber.subscribe("v5/line")
            val subscribed = System.nanoTime() + 3_000_000_000L
            var suback = false
            while (System.nanoTime() < subscribed && !suback) {
                if (subscriber.poll(100) != null) suback = true
            }
            assertTrue(suback, "SUBACK")
            publisher.publish("v5/line", "mqtt5".encodeToByteArray())
            val deadline = System.nanoTime() + 3_000_000_000L
            var body: String? = null
            while (System.nanoTime() < deadline && body == null) {
                val polled = subscriber.poll(100)
                if (polled is MqttPoll.Published && polled.message.topic == "v5/line") {
                    body = polled.message.payload.toString(Charsets.UTF_8)
                }
            }
            assertEquals("mqtt5", body)
        } finally {
            publisher.drop()
            subscriber.drop()
        }
    }

    @Test fun brokerStopSetsTheSessionDownAndRestartRecovers() {
        val hostPort = java.net.ServerSocket(0).use { it.localPort }
        val broker = startMosquitto(hostPort)
        val host = MqttConnection(
            MqttConnectionConfig(
                name = "loss",
                host = "127.0.0.1",
                port = hostPort,
                clientId = "oip-loss",
                keepAliveSeconds = 2,
                sparkplugHostId = null,
                autoCreateTags = false,
                reconnectInitialMs = 40,
                reconnectMaxMs = 200,
                defaultScanPeriodMs = 80,
                connectTimeoutMs = 2_000,
            ),
            secrets = { null },
        )
        host.attach("number:plant/flow", 80, { _, _, _ -> }, { _, _, _ -> })
        host.start()
        val publisher = MqttClient("127.0.0.1", hostPort, "oip-pub")
        try {
            await("connected") { host.connected() }
            publisher.connect()
            publisher.publish("plant/flow", "42".encodeToByteArray(), retain = true)
            await("value") { host.read("number:plant/flow").value == 42 }
            val connects = host.connectCount()
            broker.stop()
            await("down") { !host.linkFresh() && !host.connected() }
            assertTrue(host.connectCount() >= connects)
            broker.start()
            val again = MqttClient("127.0.0.1", hostPort, "oip-pub-2")
            again.connect()
            again.publish("plant/flow", "42".encodeToByteArray(), retain = true)
            await("recovered") {
                val sample = host.read("number:plant/flow")
                host.connected() && sample.value == 42 && sample.quality.statusCode == 0L
            }
            again.disconnect()
        } finally {
            publisher.drop()
            host.close()
            broker.stop()
        }
    }

    private fun host(reorderMs: Long, scanMs: Long = 1_000, keepAlive: Int = 30) = MqttConnection(
        MqttConnectionConfig(
            name = "line",
            host = "127.0.0.1",
            port = mosquitto.getMappedPort(1883),
            clientId = "oip-host-${System.nanoTime()}",
            keepAliveSeconds = keepAlive,
            sparkplugHostId = "primary",
            reorderTimeoutMs = reorderMs,
            autoCreateTags = false,
            reconnectInitialMs = 40,
            reconnectMaxMs = 200,
            defaultScanPeriodMs = scanMs,
            connectTimeoutMs = 2_000,
        ),
        secrets = { null },
    )

    private fun edge() = MqttClient("127.0.0.1", mosquitto.getMappedPort(1883), "oip-edge-${System.nanoTime()}")

    private fun address(device: String, metric: String) = "spb:G/E/$device/$metric"

    private fun metric(name: String, value: Int, alias: Long? = null) =
        SparkplugCodec.Metric(name, alias, SparkplugCodec.INT32, null, false, value)

    private fun bdSeq(value: Long) = SparkplugCodec.Metric("bdSeq", null, SparkplugCodec.UINT64, null, false, value)

    private fun rebirth(value: Boolean) =
        SparkplugCodec.Metric("Node Control/Rebirth", null, SparkplugCodec.BOOLEAN, null, false, value)

    private fun payload(seq: Int, vararg metrics: SparkplugCodec.Metric) =
        SparkplugCodec.encode(SparkplugCodec.Payload(System.currentTimeMillis(), seq, metrics.toList()))

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }
}
