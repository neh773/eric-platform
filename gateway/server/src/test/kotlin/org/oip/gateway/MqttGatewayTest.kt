package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.mqtt.MqttClient
import org.oip.mqtt.MqttConnection
import org.oip.mqtt.MqttDriverModule
import org.oip.mqtt.SparkplugCodec
import org.oip.tags.Level
import org.oip.tags.QualifiedValue
import org.oip.tags.QualityCode
import org.oip.tags.TagEngine
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level as LogLevel
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * MQTT through the gateway tag engine. Comms loss uses the same four faults and the same budget
 * as [CommsLossQualityTest]. Username and password are secret references; the plaintext is not
 * written to the config file, returned by the config API, or written to the driver log.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class MqttGatewayTest {
    @TempDir
    lateinit var tmp: Path

    @Test fun mqttTagsGoBadOnEachFaultAndRecoverWithoutRestart() {
        val password = "s3cret-mqtt-gw-7k"
        val records = CopyOnWriteArrayList<String>()
        val handler = capture(records)
        val logger = Logger.getLogger("org.oip.mqtt")
        logger.addHandler(handler)
        logger.level = LogLevel.ALL
        val hostPort = java.net.ServerSocket(0).use { it.localPort }
        val broker = mosquitto(hostPort, passwordFile(password))
        try {
            FaultProxy(hostPort).use { proxy ->
                gateway().use { g ->
                    g.security.secrets.putText("mqtt-user", "operator")
                    g.security.secrets.putText("mqtt-pass", password)
                    val http = Http(g.port())
                    g.loginAdmin(http)
                    val created = http.put(
                        "/api/v1/config/org.oip.driver-mqtt/gateway/Connection/line",
                        connectionBody(proxy.port),
                    )
                    assertEquals(201, created.status, created.body)
                    assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/flow", tagBody("flow", "number:plant/flow", "badNotConnected")).status)
                    assertEquals(201, http.put("/api/v1/config/tags/gateway/Tag/hold", tagBody("hold", "number:plant/hold", "uncertainLastKnown")).status)
                    val stored = Files.readString(tmp.resolve("config/org.oip.driver-mqtt/gateway/Connection/line.yaml"))
                    val fetched = http.get("/api/v1/config/org.oip.driver-mqtt/gateway/Connection/line")
                    assertTrue(stored.contains("secret://mqtt-pass"))
                    assertTrue(fetched.body.contains("secret://mqtt-pass"))
                    assertFalse(stored.contains(password))
                    assertFalse(fetched.body.contains(password))
                    val module = g.host.instance(MqttDriverModule.MODULE_ID) as MqttDriverModule
                    val connection = module.connection("line") ?: error("missing connection")
                    val tags = listOf(
                        Expect("plant/flow", QualityCode.BAD_NOT_CONNECTED, 42),
                        Expect("plant/hold", QualityCode.UNCERTAIN_LAST_KNOWN_VALUE, 7),
                    )
                    RetainedPublisher(hostPort, "operator", password).use { publisher ->
                        publisher.start()
                        awaitGood(g.tags, tags, 8_000)
                        exercise(g.tags, tags, proxy, connection, stopUpstream = { broker.stop() }, startUpstream = { broker.start() })
                    }
                    assertFalse(records.any { it.contains(password) })
                    assertSameConnection(connection, module.connection("line"))
                }
            }
        } finally {
            logger.removeHandler(handler)
            broker.stop()
        }
    }

    @Test fun sparkplugBirthCreatesAReadableTag() {
        val hostPort = java.net.ServerSocket(0).use { it.localPort }
        val broker = mosquitto(hostPort, null)
        try {
            gateway().use { g ->
                val http = Http(g.port())
                g.loginAdmin(http)
                val created = http.put(
                    "/api/v1/config/org.oip.driver-mqtt/gateway/Connection/line",
                    """
                    {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"line","module":"org.oip.driver-mqtt","version":0},
                     "spec":{"host":"127.0.0.1","port":$hostPort,"clientId":"oip-discover","keepAliveSeconds":10,
                       "sparkplugHostId":"primary","autoCreateTags":true,"reorderTimeoutMs":2000,"connectTimeoutMs":2000}}
                    """.trimIndent(),
                )
                assertEquals(201, created.status, created.body)
                val edge = MqttClient("127.0.0.1", hostPort, "oip-edge")
                edge.connect()
                try {
                    val now = System.currentTimeMillis()
                    edge.publish(
                        "spBv1.0/G/NBIRTH/E",
                        SparkplugCodec.encode(
                            SparkplugCodec.Payload(
                                now, 0,
                                listOf(
                                    SparkplugCodec.Metric("bdSeq", null, SparkplugCodec.UINT64, null, false, 1L),
                                    SparkplugCodec.Metric("V", 1L, SparkplugCodec.INT32, null, false, 7),
                                ),
                            ),
                        ),
                        retain = true,
                    )
                    edge.publish(
                        "spBv1.0/G/DBIRTH/E/Pump",
                        SparkplugCodec.encode(
                            SparkplugCodec.Payload(
                                now, 1,
                                listOf(SparkplugCodec.Metric("Flow", 2L, SparkplugCodec.INT32, null, false, 11)),
                            ),
                        ),
                        retain = true,
                    )
                    await("birth tag") {
                        val flow = g.tags.read("mqtt/line/G/E/Pump/Flow")
                        val node = g.tags.read("mqtt/line/G/E/V")
                        flow.quality.code == QualityCode.GOOD && flow.value == 11 &&
                            node.quality.code == QualityCode.GOOD && node.value == 7
                    }
                } finally {
                    edge.drop()
                }
            }
        } finally {
            broker.stop()
        }
    }

    private fun exercise(
        engine: TagEngine,
        tags: List<Expect>,
        proxy: FaultProxy,
        connection: MqttConnection,
        stopUpstream: () -> Unit,
        startUpstream: () -> Unit,
    ) {
        val faults = listOf<Pair<String, () -> Unit>>(
            "stall" to { proxy.stall() },
            "reset" to { proxy.reset() },
            "refused" to { proxy.refuse() },
            "server stop" to { stopUpstream() },
        )
        for ((name, inject) in faults) {
            awaitGood(engine, tags, 15_000, "before $name")
            val connects = connection.connectCount()
            val faultAt = Instant.now()
            inject()
            awaitLoss(engine, tags, faultAt, name)
            if (name == "stall") assertEquals(connects, connection.connectCount(), "stall kept the session")
            when (name) {
                "server stop" -> startUpstream()
                "stall" -> proxy.resume()
                else -> proxy.open()
            }
            awaitGood(engine, tags, 15_000, "after $name")
            if (name == "stall") assertEquals(connects, connection.connectCount(), "stall recovery did not reconnect")
        }
    }

    private fun gateway(): Gateway {
        Files.writeString(tmp.resolve("gateway.yaml"), "profile: standard\nopcua:\n  enabled: false\n")
        return Gateway(tmp, port = 0).also { it.start() }
    }

    private fun connectionBody(port: Int) = """
        {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"line","module":"org.oip.driver-mqtt","version":0},
         "spec":{"host":"127.0.0.1","port":$port,"clientId":"oip-gw","keepAliveSeconds":10,"cleanSession":true,
           "username":"secret://mqtt-user","password":"secret://mqtt-pass",
           "reconnectInitialMs":20,"reconnectMaxMs":80,"defaultScanPeriodMs":$SCAN_MS,"connectTimeoutMs":2500,
           "autoCreateTags":false}}
    """.trimIndent()

    private fun tagBody(name: String, item: String, loss: String) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"plant/$name","type":"opc","dataType":"Int32","opcServer":"line",
           "opcItemPath":"$item","scanPeriodMillis":$SCAN_MS,"commsTimeoutMillis":$COMMS_TIMEOUT_MS,"commsLoss":"$loss"}}
    """.trimIndent()

    private fun passwordFile(password: String): Path {
        val image = "$IMAGE@$DIGEST"
        val proc = ProcessBuilder(
            "docker", "run", "--rm", "--entrypoint", "sh", image,
            "-c", "mosquitto_passwd -b -c /tmp/pw operator $password && cat /tmp/pw",
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        check(proc.waitFor() == 0) { "mosquitto_passwd failed" }
        val line = text.lineSequence().firstOrNull { it.startsWith("operator:") } ?: error("password file was empty")
        val dir = Files.createDirectories(tmp.resolve("broker"))
        val file = dir.resolve("password_file")
        Files.writeString(file, line + "\n")
        val conf = dir.resolve("mosquitto-auth.conf")
        Files.writeString(
            conf,
            """
            listener 1883 0.0.0.0
            allow_anonymous false
            password_file /mosquitto/config/password_file
            persistence false
            """.trimIndent() + "\n",
        )
        return conf
    }

    private fun mosquitto(hostPort: Int, authConf: Path?): GenericContainer<*> {
        val image = DockerImageName.parse("$IMAGE@$DIGEST")
        val container = GenericContainer(image)
            .withExposedPorts(1883)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)))
        if (authConf == null) {
            val dir = Files.createDirectories(tmp.resolve("broker"))
            val conf = dir.resolve("mosquitto.conf")
            Files.writeString(
                conf,
                "listener 1883 0.0.0.0\nallow_anonymous true\npersistence false\n",
            )
            container.withCopyFileToContainer(MountableFile.forHostPath(conf), "/mosquitto/config/mosquitto.conf")
        } else {
            container.withCopyFileToContainer(MountableFile.forHostPath(authConf), "/mosquitto/config/mosquitto.conf")
            container.withCopyFileToContainer(
                MountableFile.forHostPath(authConf.parent.resolve("password_file")),
                "/mosquitto/config/password_file",
            )
        }
        container.portBindings = listOf("$hostPort:1883")
        container.start()
        return container
    }

    private fun capture(records: MutableList<String>) = object : Handler() {
        override fun publish(record: LogRecord) {
            records += record.message.orEmpty() + record.thrown?.message.orEmpty()
        }
        override fun flush() {}
        override fun close() {}
    }

    private fun assertSameConnection(before: MqttConnection, after: MqttConnection?) {
        assertTrue(before === after)
    }

    private class Expect(val path: String, val lossCode: QualityCode, val value: Int) {
        var source: Instant? = null

        fun noteGood(qv: QualifiedValue) {
            assertEquals(value, (qv.value as Number).toInt(), path)
            assertNotNull(qv.sourceTimestamp, "$path has no source timestamp")
            source = qv.sourceTimestamp
        }

        fun assertLost(qv: QualifiedValue, deadline: Instant) {
            assertEquals(lossCode, qv.quality.code, path)
            val level = if (lossCode.level == Level.BAD) Level.BAD else Level.UNCERTAIN
            assertEquals(level, qv.quality.level, path)
            assertEquals(value, (qv.value as Number).toInt(), path)
            assertEquals(source, qv.sourceTimestamp, path)
            assertFalse(qv.timestamp.isAfter(deadline), "$path detected at ${qv.timestamp}, deadline $deadline")
        }
    }

    private class RetainedPublisher(val port: Int, val user: String, val password: String) : AutoCloseable {
        @Volatile private var running = true
        private var thread: Thread? = null

        fun start() {
            thread = Thread({
                while (running) {
                    val client = MqttClient(
                        "127.0.0.1", port, "oip-pub",
                        username = user,
                        password = password.encodeToByteArray(),
                        keepAliveSeconds = 30,
                    )
                    try {
                        client.connect()
                        while (running && client.open) {
                            client.publish("plant/flow", "42".encodeToByteArray(), retain = true)
                            client.publish("plant/hold", "7".encodeToByteArray(), retain = true)
                            Thread.sleep(100)
                        }
                    } catch (_: Exception) {
                        Thread.sleep(40)
                    } finally {
                        client.drop()
                    }
                }
            }, "mqtt-retained").apply { isDaemon = true; start() }
        }

        override fun close() {
            running = false
            thread?.join(2_000)
        }
    }

    companion object {
        private const val IMAGE = "eclipse-mosquitto:2.0.22"
        private const val DIGEST = "sha256:09b62ff32c2e7b64d4b431b9a89236073289b68db273de2b089edeb9b0d92b5c"
        private const val SCAN_MS = 80L
        private const val COMMS_TIMEOUT_MS = 120L
        private val LOSS_BUDGET_MS =
            SCAN_MS + SCAN_MS + COMMS_TIMEOUT_MS + TagDriverBridge.COMMS_WATCH_MS + 250L

        private fun awaitGood(engine: TagEngine, tags: List<Expect>, budgetMs: Long, label: String = "") {
            val end = System.nanoTime() + budgetMs * 1_000_000L
            while (System.nanoTime() < end) {
                if (tags.all { spec ->
                        val qv = engine.read(spec.path)
                        qv.quality.code == QualityCode.GOOD && (qv.value as? Number)?.toInt() == spec.value
                    }
                ) {
                    for (spec in tags) spec.noteGood(engine.read(spec.path))
                    return
                }
                Thread.sleep(10)
            }
            val detail = tags.joinToString { spec ->
                val qv = engine.read(spec.path)
                "${spec.path}=${qv.quality.code} value=${qv.value}"
            }
            throw AssertionError("good ${tags.joinToString { it.path }} $label ($detail)")
        }

        private fun awaitLoss(engine: TagEngine, tags: List<Expect>, faultAt: Instant, fault: String) {
            val deadline = faultAt.plusMillis(LOSS_BUDGET_MS)
            val pending = tags.toMutableSet()
            val end = System.nanoTime() + (LOSS_BUDGET_MS + 400) * 1_000_000L
            while (pending.isNotEmpty() && System.nanoTime() < end) {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val spec = iterator.next()
                    val qv = engine.read(spec.path)
                    if (qv.quality.code == QualityCode.GOOD) {
                        spec.noteGood(qv)
                        continue
                    }
                    if (qv.quality.code == spec.lossCode) {
                        spec.assertLost(qv, deadline)
                        iterator.remove()
                    }
                }
                if (pending.isNotEmpty()) Thread.sleep(10)
            }
            if (pending.isNotEmpty()) {
                val detail = pending.joinToString { spec ->
                    val qv = engine.read(spec.path)
                    "${spec.path}=${qv.quality.code} value=${qv.value} at=${qv.timestamp}"
                }
                throw AssertionError("$fault: tags not at comms quality within ${LOSS_BUDGET_MS}ms ($detail)")
            }
        }

        private fun await(message: String, cond: () -> Boolean) {
            val end = System.nanoTime() + 8_000_000_000L
            while (System.nanoTime() < end) {
                if (cond()) return
                Thread.sleep(20)
            }
            throw AssertionError(message)
        }
    }
}
