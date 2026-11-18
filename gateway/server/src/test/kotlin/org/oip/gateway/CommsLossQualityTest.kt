package org.oip.gateway

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.eclipse.milo.opcua.sdk.core.AccessLevel
import org.eclipse.milo.opcua.sdk.core.Reference
import org.eclipse.milo.opcua.sdk.server.EndpointConfig
import org.eclipse.milo.opcua.sdk.server.Lifecycle
import org.eclipse.milo.opcua.sdk.server.ManagedNamespaceWithLifecycle
import org.eclipse.milo.opcua.sdk.server.OpcUaServer
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator
import org.eclipse.milo.opcua.sdk.server.items.DataItem
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel
import org.eclipse.milo.opcua.stack.core.NodeIds
import org.eclipse.milo.opcua.stack.core.security.DefaultApplicationGroup
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager
import org.eclipse.milo.opcua.stack.core.security.DefaultServerCertificateValidator
import org.eclipse.milo.opcua.stack.core.security.FileBasedCertificateQuarantine
import org.eclipse.milo.opcua.stack.core.security.KeyStoreCertificateStore
import org.eclipse.milo.opcua.stack.core.security.MemoryTrustListManager
import org.eclipse.milo.opcua.stack.core.security.RsaSha256CertificateFactory
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.modbus.ModbusDriverModule
import org.oip.mqtt.MqttClient
import org.oip.mqtt.MqttDriverModule
import org.oip.opcua.OpcUaCertificates
import org.oip.opcua.OpcUaClientModule
import org.oip.tags.Level
import org.oip.tags.QualifiedValue
import org.oip.tags.QualityCode
import org.oip.tags.TagEngine
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level as LogLevel
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Comms loss through a live Device or Connection (P2-14) and the tag engine (SPEC §5).
 *
 * The Gradle `build` job does not start the compose stack, so this stands in for Toxiproxy:
 * the same four faults (refused, reset, stall, upstream stop) on an in-process proxy in front
 * of a simulator. [SimulatorCommsLossTest] repeats kill, cut, and black-hole against the
 * compose stack. The engine clock is frozen and then advanced by one scan period plus the
 * tag's comms timeout, so the deadline is that sum and not a wall-clock sleep. The device
 * timeout is longer than the drain, so a pass is not "we waited for the socket".
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class CommsLossQualityTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun mqttTagsGoBadOnEachFaultAndRecoverWithoutRestart() {
        val password = "s3cret-mqtt-gw-7k"
        val records = CopyOnWriteArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record.message.orEmpty() + record.thrown?.message.orEmpty()
            }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger("org.oip.mqtt")
        logger.addHandler(handler)
        logger.level = LogLevel.ALL
        val clock = MutableClock()
        val hostPort = ServerSocket(0).use { it.localPort }
        val broker = mqttBroker(hostPort, password)
        try {
            FaultProxy(hostPort).use { proxy ->
                gateway(clock).use { g ->
                    g.security.secrets.putText("mqtt-user", "operator")
                    g.security.secrets.putText("mqtt-pass", password)
                    val http = Http(g.port())
                    g.loginAdmin(http)
                    put(http, "/api/v1/config/org.oip.driver-mqtt/gateway/Connection/line", mqttConnectionBody(proxy.port))
                    put(http, "/api/v1/config/tags/gateway/Tag/flow", tagBody("flow", "line", "number:plant/flow", "badNotConnected"))
                    put(http, "/api/v1/config/tags/gateway/Tag/hold", tagBody("hold", "line", "number:plant/hold", "uncertainLastKnown"))
                    val stored = Files.readString(tmp.resolve("config/org.oip.driver-mqtt/gateway/Connection/line.yaml"))
                    val fetched = http.get("/api/v1/config/org.oip.driver-mqtt/gateway/Connection/line")
                    assertTrue(stored.contains("secret://mqtt-pass"))
                    assertFalse(stored.contains(password))
                    assertTrue(fetched.body.contains("secret://mqtt-pass"))
                    assertFalse(fetched.body.contains(password))
                    val module = g.host.instance(MqttDriverModule.MODULE_ID) as MqttDriverModule
                    val connection = module.connection("line") ?: error("missing mqtt connection")
                    val tags = listOf(
                        Expect("plant/flow", QualityCode.BAD_NOT_CONNECTED, 42),
                        Expect("plant/hold", QualityCode.UNCERTAIN_LAST_KNOWN_VALUE, 7),
                    )
                    MqttRetainedPublisher(hostPort, "operator", password).use { publisher ->
                        publisher.start()
                        awaitGood(g.tags, tags, 8_000)
                        assertStaysGood(g.tags, tags, 300)
                        exercise(
                            g.tags, clock, tags, proxy,
                            stopUpstream = { broker.stop() },
                            startUpstream = { broker.start() },
                            sessionGeneration = { connection.connectCount() },
                        )
                    }
                    assertFalse(records.any { it.contains(password) })
                    assertSame(connection, module.connection("line"))
                }
            }
        } finally {
            logger.removeHandler(handler)
            broker.stop()
        }
    }

    @Test
    fun modbusTagsGoBadOnEachFaultAndRecoverWithoutRestart() {
        val clock = MutableClock()
        RestartableModbus().use { slave ->
            slave.holding[0] = 42
            slave.holding[1] = 7
            slave.start()
            FaultProxy(slave.port).use { proxy ->
                gateway(clock).use { g ->
                    val http = Http(g.port())
                    g.loginAdmin(http)
                    put(http, "/api/v1/config/org.oip.driver-modbus/gateway/Device/line", deviceBody(proxy.port))
                    put(http, "/api/v1/config/tags/gateway/Tag/flow", tagBody("flow", "line", "1.HR1", "badNotConnected"))
                    put(http, "/api/v1/config/tags/gateway/Tag/hold", tagBody("hold", "line", "1.HR2", "uncertainLastKnown"))
                    val module = g.host.instance(ModbusDriverModule.MODULE_ID) as ModbusDriverModule
                    val device = module.device("line")
                    assertNotNull(device)
                    val tags = listOf(
                        Expect("plant/flow", QualityCode.BAD_NOT_CONNECTED, 42),
                        Expect("plant/hold", QualityCode.UNCERTAIN_LAST_KNOWN_VALUE, 7),
                    )
                    awaitGood(g.tags, tags, 8_000)
                    assertStaysGood(g.tags, tags, 300)
                    exercise(g.tags, clock, tags, proxy, stopUpstream = { slave.stop() }, startUpstream = { slave.start() })
                    assertSame(device, module.device("line"))
                }
            }
        }
    }

    @Test
    fun opcUaTagsGoBadOnEachFaultAndRecoverWithoutRestart() {
        val clock = MutableClock()
        OpcSim(tmp.resolve("opc-sim")).use { sim ->
            FaultProxy(sim.port).use { proxy ->
                gateway(clock).use { g ->
                    val http = Http(g.port())
                    g.loginAdmin(http)
                    put(
                        http,
                        "/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/line",
                        connectionBody(proxy.port),
                    )
                    put(http, "/api/v1/config/tags/gateway/Tag/count", tagBody("count", "line", sim.nodeId("Counter"), "badNotConnected"))
                    put(http, "/api/v1/config/tags/gateway/Tag/fixed", tagBody("fixed", "line", sim.nodeId("Static"), "uncertainLastKnown"))
                    val module = g.host.instance(OpcUaClientModule.MODULE_ID) as OpcUaClientModule
                    val connection = module.connection("line")
                    assertNotNull(connection)
                    val counterId = sim.nodeId("Counter")
                    val tags = listOf(
                        Expect("plant/count", QualityCode.BAD_NOT_CONNECTED, 5),
                        Expect("plant/fixed", QualityCode.UNCERTAIN_LAST_KNOWN_VALUE, 11),
                    )
                    awaitGood(g.tags, tags, 15_000)
                    assertStaysGood(g.tags, tags, 300)
                    exercise(g.tags, clock, tags, proxy, stopUpstream = { sim.stop() }, startUpstream = { sim.start() })
                    assertSame(connection, module.connection("line"))
                    assertEquals(counterId, sim.nodeId("Counter"))
                }
            }
        }
    }

    private fun exercise(
        engine: TagEngine,
        clock: MutableClock,
        tags: List<Expect>,
        proxy: FaultProxy,
        stopUpstream: () -> Unit,
        startUpstream: () -> Unit,
        sessionGeneration: (() -> Int)? = null,
    ) {
        val faults = listOf<Pair<String, () -> Unit>>(
            "stall" to { proxy.stall() },
            "reset" to { proxy.reset() },
            "refused" to { proxy.refuse() },
            "server stop" to { stopUpstream() },
        )
        for ((name, inject) in faults) {
            awaitGood(engine, tags, 15_000, "before $name")
            val sessions = sessionGeneration
            val generation = if (name == "stall") sessions?.invoke() else null
            val t0 = clock.millis()
            inject()
            if (generation != null && sessions != null) {
                assertEquals(generation, sessions(), "stall kept the session")
            }
            // Let a response that was already on the wire land at t0. The clock does not move,
            // so this is not part of the deadline. 80 ms was too short for a loaded 2-core runner
            // to finish handling that response; a late one moved the silence deadline past t0.
            Thread.sleep(500)
            for (spec in tags) {
                val qv = engine.read(spec.path)
                if (qv.quality.code == QualityCode.GOOD) spec.noteGood(qv)
            }
            val deadline = t0 + SCAN_MS + COMMS_TIMEOUT_MS
            // A black hole must not flip quality before the deadline, and must not wait out
            // the device socket timeout (that timeout is 2500 ms; this advance is not a sleep).
            if (name == "stall") {
                clock.advance(Duration.ofMillis(deadline - clock.millis() - 1))
                engine.checkComms()
                for (spec in tags) {
                    assertEquals(QualityCode.GOOD, engine.read(spec.path).quality.code, "$name before the deadline")
                }
            }
            val remain = deadline - clock.millis()
            if (remain > 0) clock.advance(Duration.ofMillis(remain))
            engine.checkComms()
            val deadlineAt = Instant.ofEpochMilli(deadline)
            for (spec in tags) spec.assertLost(engine.read(spec.path), deadlineAt, name)
            when (name) {
                "server stop" -> startUpstream()
                // A stall is a black hole, not a reset. The same TCP session has to
                // start moving bytes again; tearing it down would hide that case.
                "stall" -> proxy.resume()
                else -> proxy.open()
            }
            awaitGood(engine, tags, 15_000, "after $name")
            if (generation != null && sessions != null) {
                assertEquals(generation, sessions(), "stall recovery did not reconnect")
            }
        }
    }

    private fun gateway(clock: MutableClock): Gateway {
        Files.writeString(tmp.resolve("gateway.yaml"), "profile: standard\nopcua:\n  enabled: false\n")
        return Gateway(tmp, port = 0, clock = clock).also { it.start() }
    }

    private fun put(http: Http, path: String, body: String) {
        val res = http.put(path, body)
        assertEquals(201, res.status, res.body)
    }

    private fun deviceBody(port: Int) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"line","module":"org.oip.driver-modbus","version":0},
         "spec":{"name":"line","host":"127.0.0.1","port":$port,"mode":"TCP","unitId":1,"zeroBasedAddressing":false,
           "timeoutMs":2500,"reconnectAfterConsecutiveTimeouts":1,"maxRetryCount":0,
           "backoffInitialMs":20,"backoffMaxMs":80,"backoffMultiplier":2,"defaultScanPeriodMs":$SCAN_MS,"spanGaps":true}}
    """.trimIndent()

    private fun connectionBody(port: Int) = """
        {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"line","module":"org.oip.driver-opcua-client","version":0},
         "spec":{"name":"line","endpointUrl":"opc.tcp://127.0.0.1:$port/oip","connectHost":"127.0.0.1","connectPort":$port,
           "securityPolicy":"NONE","securityMode":"NONE","trustAllServerCertificates":false,
           "requestTimeoutMs":2000,"keepAliveIntervalMs":2000,"keepAliveTimeoutMs":2000,"keepAliveFailuresAllowed":1}}
    """.trimIndent()

    private fun mqttConnectionBody(port: Int) = """
        {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"line","module":"org.oip.driver-mqtt","version":0},
         "spec":{"host":"127.0.0.1","port":$port,"clientId":"oip-gw","keepAliveSeconds":10,"cleanSession":true,
           "username":"secret://mqtt-user","password":"secret://mqtt-pass",
           "reconnectInitialMs":20,"reconnectMaxMs":80,"defaultScanPeriodMs":$SCAN_MS,"connectTimeoutMs":2500,
           "autoCreateTags":false}}
    """.trimIndent()

    private fun mqttBroker(hostPort: Int, password: String): GenericContainer<*> {
        val dir = Files.createDirectories(tmp.resolve("broker"))
        val image = "eclipse-mosquitto:2.0.22@sha256:09b62ff32c2e7b64d4b431b9a89236073289b68db273de2b089edeb9b0d92b5c"
        val proc = ProcessBuilder(
            "docker", "run", "--rm", "--entrypoint", "sh", image,
            "-c", "mosquitto_passwd -b -c /tmp/pw operator $password && cat /tmp/pw",
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        check(proc.waitFor() == 0) { "mosquitto_passwd failed" }
        val line = text.lineSequence().firstOrNull { it.startsWith("operator:") } ?: error("password file was empty")
        val passwordFile = dir.resolve("password_file")
        Files.writeString(passwordFile, line + "\n")
        val conf = dir.resolve("mosquitto.conf")
        Files.writeString(
            conf,
            """
            listener 1883 0.0.0.0
            allow_anonymous false
            password_file /mosquitto/config/password_file
            persistence false
            """.trimIndent() + "\n",
        )
        val container = GenericContainer(DockerImageName.parse(image))
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forHostPath(conf), "/mosquitto/config/mosquitto.conf")
            .withCopyFileToContainer(MountableFile.forHostPath(passwordFile), "/mosquitto/config/password_file")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofSeconds(60)))
        container.portBindings = listOf("$hostPort:1883")
        container.start()
        return container
    }

    private fun tagBody(name: String, server: String, item: String, loss: String) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"plant/$name","type":"opc","dataType":"Int32","opcServer":"$server",
           "opcItemPath":"$item","scanPeriodMillis":$SCAN_MS,"commsTimeoutMillis":$COMMS_TIMEOUT_MS,"commsLoss":"$loss"}}
    """.trimIndent()

    private class Expect(val path: String, val lossCode: QualityCode, val value: Int) {
        var source: Instant? = null

        fun noteGood(qv: QualifiedValue) {
            assertEquals(value, (qv.value as Number).toInt(), path)
            assertNotNull(qv.sourceTimestamp, "$path has no source timestamp")
            source = qv.sourceTimestamp
        }

        fun assertLost(qv: QualifiedValue, deadline: Instant, fault: String) {
            assertEquals(lossCode, qv.quality.code, "$fault $path")
            val level = if (lossCode.level == org.oip.tags.Level.BAD) Level.BAD else Level.UNCERTAIN
            assertEquals(level, qv.quality.level, "$fault $path")
            assertEquals(value, (qv.value as Number).toInt(), "$fault $path")
            assertEquals(source, qv.sourceTimestamp, "$fault $path")
            assertFalse(
                qv.timestamp.isAfter(deadline),
                "$fault $path detected at ${qv.timestamp}, deadline $deadline",
            )
        }
    }

    private class MqttRetainedPublisher(val port: Int, val user: String, val password: String) : AutoCloseable {
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
        private const val SCAN_MS = 80L
        private const val COMMS_TIMEOUT_MS = 120L

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

        private fun assertStaysGood(engine: TagEngine, tags: List<Expect>, windowMs: Long) {
            val end = System.nanoTime() + windowMs * 1_000_000L
            while (System.nanoTime() < end) {
                for (spec in tags) {
                    val qv = engine.read(spec.path)
                    assertEquals(QualityCode.GOOD, qv.quality.code, "${spec.path} while the link was up")
                    spec.noteGood(qv)
                }
                Thread.sleep(15)
            }
        }

    }
}

/**
 * TCP fault injector with the same knobs the compose Toxiproxy exposes: refuse new connections,
 * reset live ones, or stall bytes until the client times out. [open] lets new connections through
 * and aborts sockets left over from the fault so the driver reconnects without a restart.
 */
internal class FaultProxy(private val upstreamPort: Int) : AutoCloseable {
    private enum class Mode { OPEN, STALL, RESET, REFUSED }

    private val live = CopyOnWriteArrayList<Socket>()
    @Volatile private var running = true
    @Volatile private var mode = Mode.OPEN
    @Volatile private var listen: ServerSocket? = null
    val port: Int

    private val acceptThread: Thread

    init {
        val first = ServerSocket()
        first.reuseAddress = true
        first.bind(InetSocketAddress("127.0.0.1", 0))
        port = first.localPort
        listen = first
        acceptThread = Thread({ acceptLoop() }, "fault-proxy").apply { isDaemon = true; start() }
    }

    fun stall() {
        mode = Mode.STALL
    }

    /** Let a stalled session pass bytes again without resetting it. */
    fun resume() {
        mode = Mode.OPEN
    }

    fun reset() {
        mode = Mode.RESET
        abortLive()
    }

    fun refuse() {
        // Leave the listening socket bound. Closing it hands the port back to the kernel,
        // and the next open() then races every other bind(0) in the process plus the accept
        // thread still inside accept(). New handshakes are reset below while this mode holds,
        // so the driver sees a failed connect and the address stays ours.
        mode = Mode.REFUSED
        abortLive()
    }

    fun open() {
        if (listen == null || listen?.isClosed == true) listen = bindListen()
        mode = Mode.OPEN
        abortLive()
    }

    private fun bindListen(): ServerSocket {
        var last: java.net.BindException? = null
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", port))
                return socket
            } catch (error: java.net.BindException) {
                last = error
                runCatching { socket.close() }
                Thread.sleep(20)
            }
        }
        throw last ?: java.net.BindException("fault proxy port $port is in use")
    }

    private fun acceptLoop() {
        while (running) {
            val socket = listen
            if (socket == null || socket.isClosed) {
                Thread.sleep(5)
                continue
            }
            val down = try {
                socket.accept()
            } catch (_: Exception) {
                if (!running) break
                continue
            }
            if (mode == Mode.RESET || mode == Mode.REFUSED) {
                rst(down)
            } else {
                Thread({ bridge(down) }, "fault-proxy-bridge").apply { isDaemon = true }.start()
            }
        }
    }

    private fun bridge(down: Socket) {
        if (mode == Mode.RESET || mode == Mode.REFUSED) {
            rst(down)
            return
        }
        val up = Socket()
        up.tcpNoDelay = true
        down.tcpNoDelay = true
        try {
            up.connect(InetSocketAddress("127.0.0.1", upstreamPort), 500)
        } catch (_: Exception) {
            rst(down)
            runCatching { up.close() }
            return
        }
        if (mode == Mode.RESET || mode == Mode.REFUSED) {
            rst(down)
            rst(up)
            return
        }
        live += down
        live += up
        pump(down, up)
        pump(up, down)
    }

    private fun pump(from: Socket, to: Socket) {
        Thread({
            val buf = ByteArray(8_192)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (running && mode != Mode.RESET && mode != Mode.REFUSED) {
                    if (mode == Mode.STALL) {
                        Thread.sleep(10)
                        continue
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    // A read that was already blocked when stall() ran returns with the next message,
                    // possibly long after the stall began. Hold it, or the stall leaks one message
                    // (an OPC UA keep-alive, say) that moves the tag's silence deadline.
                    while (running && mode == Mode.STALL) Thread.sleep(10)
                    if (mode == Mode.RESET || mode == Mode.REFUSED) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                rst(from)
                rst(to)
            }
        }, "fault-proxy-io").apply { isDaemon = true }.start()
    }

    private fun abortLive() {
        val copy = live.toList()
        live.clear()
        copy.forEach { rst(it) }
    }

    private fun rst(socket: Socket) {
        runCatching {
            socket.setSoLinger(true, 0)
            socket.close()
        }
        live.remove(socket)
    }

    override fun close() {
        running = false
        abortLive()
        val socket = listen
        listen = null
        runCatching { socket?.close() }
        acceptThread.join(1_000)
    }
}

/** Function-3 slave on a fixed port so a stop/start is a device power cycle, not a new address. */
private class RestartableModbus : AutoCloseable {
    private val reserved = ServerSocket(0)
    val port: Int = reserved.localPort
    val holding = IntArray(8)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    init {
        reserved.close()
    }

    fun start() {
        check(server == null)
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", port))
        server = socket
        running = true
        Thread({
            while (running && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ serve(client) }, "modbus-sim").apply { isDaemon = true }.start()
            }
        }, "modbus-sim-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        val socket = server
        server = null
        runCatching { socket?.close() }
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val buf = ByteArray(260)
            while (running) {
                if (!readFully(input, buf, 6)) return
                val length = ((buf[4].toInt() and 0xff) shl 8) or (buf[5].toInt() and 0xff)
                if (length < 2 || length > 250 || !readFully(input, buf, length, 6)) return
                if ((buf[7].toInt() and 0xff) != 3) continue
                val address = ((buf[8].toInt() and 0xff) shl 8) or (buf[9].toInt() and 0xff)
                val count = ((buf[10].toInt() and 0xff) shl 8) or (buf[11].toInt() and 0xff)
                val pdu = ByteArray(2 + count * 2)
                pdu[0] = 3
                pdu[1] = (count * 2).toByte()
                for (i in 0 until count) {
                    val value = if (address + i < holding.size) holding[address + i] else 0
                    pdu[2 + i * 2] = (value shr 8).toByte()
                    pdu[3 + i * 2] = value.toByte()
                }
                val frame = ByteArray(7 + pdu.size)
                frame[0] = buf[0]
                frame[1] = buf[1]
                val follow = 1 + pdu.size
                frame[4] = (follow shr 8).toByte()
                frame[5] = follow.toByte()
                frame[6] = buf[6]
                pdu.copyInto(frame, 7)
                output.write(frame)
                output.flush()
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int, offset: Int = 0): Boolean {
        var got = 0
        while (got < n) {
            val r = input.read(buf, offset + got, n - got)
            if (r < 0) return false
            got += r
        }
        return true
    }

    override fun close() = stop()
}

/**
 * Small OPC UA server on a fixed port. [stop] then [start] is a server power cycle; node ids
 * stay on the same namespace uri so the client's item paths still resolve.
 */
private class OpcSim(private val securityDir: Path) : AutoCloseable {
    val port: Int
    private val keys = OpcUaCertificates.generate("urn:oip:comms-sim", "OIP Comms Sim")
    private var server: OpcUaServer? = null
    private var namespace: SimNamespace? = null
    private var store: KeyStoreCertificateStore? = null
    private var index: Int = 0

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val reserved = ServerSocket(0)
        port = reserved.localPort
        reserved.close()
        securityDir.toFile().mkdirs()
        start()
    }

    fun nodeId(name: String): String = "ns=$index;s=Sim/$name"

    fun start() {
        check(server == null)
        val trust = MemoryTrustListManager()
        val keyStore = KeyStoreCertificateStore.createAndInitialize(KeyStoreCertificateStore.Settings(
            securityDir.resolve("server.pfx"),
            { "password".toCharArray() },
            { "password".toCharArray() },
        ))
        store = keyStore
        val quarantine = FileBasedCertificateQuarantine.create(securityDir.resolve("rejected"))
        val factory = object : RsaSha256CertificateFactory() {
            override fun createRsaSha256KeyPair(): KeyPair = keys.keyPair
            override fun createRsaSha256CertificateChain(keyPair: KeyPair): Array<X509Certificate> =
                arrayOf(keys.certificate)
        }
        val group = DefaultApplicationGroup.createAndInitialize(
            trust, keyStore, factory, DefaultServerCertificateValidator(trust, quarantine),
        )
        val config = OpcUaServerConfig.builder()
            .setApplicationUri("urn:oip:comms-sim")
            .setApplicationName(LocalizedText.english("OIP Comms Sim"))
            .setProductUri("urn:oip:comms-sim")
            .setEndpoints(setOf(endpoint()))
            .setBuildInfo(BuildInfo("urn:oip:comms-sim", "oip", "comms sim", OpcUaServer.SDK_VERSION, "", DateTime.now()))
            .setCertificateManager(DefaultCertificateManager(quarantine, group))
            .setIdentityValidator(AnonymousIdentityValidator.INSTANCE)
            .build()
        val created = OpcUaServer(config) {
            OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build())
        }
        val ns = SimNamespace(created)
        ns.startup()
        index = ns.namespaceIndex.toInt()
        created.startup().get(15, TimeUnit.SECONDS)
        server = created
        namespace = ns
    }

    fun stop() {
        val ns = namespace
        namespace = null
        if (ns != null) runCatching { ns.shutdown() }
        val running = server
        server = null
        if (running != null) runCatching { running.shutdown().get(15, TimeUnit.SECONDS) }
        val keyStore = store
        store = null
        if (keyStore != null) runCatching { keyStore.close() }
    }

    private fun endpoint(): EndpointConfig =
        EndpointConfig.newBuilder()
            .setBindAddress("127.0.0.1")
            .setHostname("127.0.0.1")
            .setBindPort(port)
            .setPath("/oip")
            .setCertificate(keys.certificate)
            .setSecurityPolicy(SecurityPolicy.None)
            .setSecurityMode(MessageSecurityMode.None)
            .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
            .addTokenPolicies(OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS)
            .build()

    override fun close() = stop()

    private class SimNamespace(server: OpcUaServer) : ManagedNamespaceWithLifecycle(server, "urn:oip:comms-sim") {
        private val subscriptionModel = SubscriptionModel(server, this)

        init {
            lifecycleManager.addLifecycle(subscriptionModel)
            lifecycleManager.addLifecycle(object : Lifecycle {
                override fun startup() {
                    buildNodes()
                }
                override fun shutdown() {}
            })
        }

        override fun onDataItemsCreated(dataItems: List<DataItem>) = subscriptionModel.onDataItemsCreated(dataItems)
        override fun onDataItemsModified(dataItems: List<DataItem>) = subscriptionModel.onDataItemsModified(dataItems)
        override fun onDataItemsDeleted(dataItems: List<DataItem>) = subscriptionModel.onDataItemsDeleted(dataItems)
        override fun onMonitoringModeChanged(monitoredItems: List<MonitoredItem>) =
            subscriptionModel.onMonitoringModeChanged(monitoredItems)

        private fun buildNodes() {
            val folder = UaFolderNode(nodeContext, newNodeId("Sim"), newQualifiedName("Sim"), LocalizedText.english("Sim"))
            nodeManager.addNode(folder)
            val objects = server.addressSpaceManager.getManagedNode(NodeIds.ObjectsFolder).orElseThrow()
            objects.addReference(Reference(objects.nodeId, NodeIds.Organizes, folder.nodeId.expanded(), true))
            variable(folder, "Counter", 5)
            variable(folder, "Static", 11)
        }

        private fun variable(folder: UaFolderNode, name: String, initial: Int) {
            val node = UaVariableNode.builder(nodeContext)
                .setNodeId(newNodeId("Sim/$name"))
                .setBrowseName(newQualifiedName(name))
                .setDisplayName(LocalizedText.english(name))
                .setDataType(NodeIds.Int32)
                .setAccessLevel(AccessLevel.CurrentRead)
                .setUserAccessLevel(AccessLevel.CurrentRead)
                .setValue(DataValue(Variant(initial), StatusCode.GOOD, DateTime.now()))
                .buildAndAdd()
            folder.addOrganizes(node)
        }
    }
}
