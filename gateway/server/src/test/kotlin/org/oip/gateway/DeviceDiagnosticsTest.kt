package org.oip.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
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
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.opcua.OpcUaCertificates
import org.oip.security.DefaultRoles
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.KeyPair
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Diagnostic tags and the OPC UA connection status route.
 * Tags follow simulator traffic, go disconnected when the peer is gone, and come back.
 * A monitored item the server rejects is not counted.
 * Doc source: FEATURES DEV-007, DEV-016.
 */
class DeviceDiagnosticsTest {
    @TempDir lateinit var tmp: Path
    private val mapper = ObjectMapper()
    private val schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun modbusDiagnosticTagsFollowTrafficAndCommsLoss() {
        val port = ServerSocket(0).use { it.localPort }
        RestartableSlave(port).use { slave ->
            slave.start()
            tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
            Gateway(tmp, port = 0).use { g ->
                g.start()
                val http = Http(g.port())
                assertEquals(401, http.get("/api/v1/diagnostics/opcua/connections").status)
                g.loginAdmin(http)
                put(http, "/api/v1/config/org.oip.driver-modbus/gateway/Device/plc", """
                    {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"plc","module":"org.oip.driver-modbus","version":0},
                     "spec":{"name":"plc","host":"127.0.0.1","port":$port,"mode":"TCP","unitId":1,
                       "timeoutMs":400,"reconnectAfterConsecutiveTimeouts":1,"maxRetryCount":0,
                       "backoffInitialMs":30,"backoffMaxMs":80,"backoffMultiplier":2,"defaultScanPeriodMs":200}}
                """.trimIndent())
                put(http, "/api/v1/config/tags/gateway/Tag/speed", """
                    {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"speed","module":"tags","version":0},
                     "spec":{"provider":"default","path":"plant/speed","type":"opc","dataType":"Int32","opcServer":"plc",
                       "opcItemPath":"HR1","scanPeriodMillis":200}}
                """.trimIndent())
                await("scanning") {
                    val row = read(g, MODBUS_TAGS)
                    bool(row[0]) && long(row[1]) >= 2L && double(row[4]) > 0.0 &&
                        double(row[5]) == 200.0 && double(row[6]) == 200.0
                }
                val before = read(g, MODBUS_TAGS)
                val requests = long(before[1])
                val errors = long(before[2])
                slave.stop()
                await("disconnected") {
                    val row = read(g, MODBUS_TAGS)
                    !bool(row[0]) && long(row[2]) > errors
                }
                slave.start()
                await("recovered") {
                    val row = read(g, MODBUS_TAGS)
                    bool(row[0]) && long(row[1]) > requests
                }
                val viaRest = readRest(http, MODBUS_TAGS.map { "[system]$it" })
                assertEquals(true, viaRest[0]["value"])
                assertEquals("Good", viaRest[0]["quality"])
            }
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun opcUaStatusCountsOnlyItemsTheServerCreated() {
        DiagOpcServer(tmp.resolve("opc")).use { server ->
            TcpProxy(server.port).use { proxy ->
                val dir = tmp.resolve("gw")
                dir.createDirectories()
                dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
                Gateway(dir, port = 0).use { g ->
                    g.start()
                    val http = Http(g.port())
                    g.loginAdmin(http)
                    val good = server.nodeId("Counter")
                    val missing = server.nodeId("DoesNotExist")
                    put(http, "/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/line", """
                        {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"line","module":"org.oip.driver-opcua-client","version":0},
                         "spec":{"name":"line","endpointUrl":"${server.endpointUrl}","connectHost":"127.0.0.1","connectPort":${proxy.port},
                           "securityPolicy":"NONE","securityMode":"NONE","trustAllServerCertificates":false,
                           "requestTimeoutMs":3000,"keepAliveIntervalMs":1000,"keepAliveTimeoutMs":1000,"keepAliveFailuresAllowed":1}}
                    """.trimIndent())
                    put(http, "/api/v1/config/tags/gateway/Tag/counter", """
                        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"counter","module":"tags","version":0},
                         "spec":{"provider":"default","path":"plant/counter","type":"opc","dataType":"Int32","opcServer":"line",
                           "opcItemPath":"$good","scanPeriodMillis":250}}
                    """.trimIndent())
                    put(http, "/api/v1/config/tags/gateway/Tag/missing", """
                        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"missing","module":"tags","version":0},
                         "spec":{"provider":"default","path":"plant/missing","type":"opc","dataType":"Int32","opcServer":"line",
                           "opcItemPath":"$missing","scanPeriodMillis":250}}
                    """.trimIndent())
                    await("one monitored item") {
                        val row = read(g, OPC_TAGS)
                        bool(row[0]) && int(row[7]) == 1 && double(row[5]) > 0.0 && double(row[6]) > 0.0
                    }
                    val before = long(read(g, OPC_TAGS)[1])
                    server.setCounter(9)
                    await("traffic counted") { long(read(g, OPC_TAGS)[1]) > before }
                    val status = http.get("/api/v1/diagnostics/opcua/connections")
                    assertEquals(200, status.status, status.body)
                    valid(status.body)
                    @Suppress("UNCHECKED_CAST")
                    val connections = status.json["connections"] as List<Map<String, Any?>>
                    val line = connections.single { it["name"] == "line" }
                    assertEquals(true, line["connected"])
                    assertEquals("Running", line["serverState"])
                    assertEquals("Connected", line["state"])
                    assertTrue((line["sessionId"] as String).isNotBlank())
                    assertEquals(1, (line["subscriptionCount"] as Number).toInt())
                    assertEquals(1, (line["monitoredItemCount"] as Number).toInt(), "a refused item must not be counted")
                    @Suppress("UNCHECKED_CAST")
                    val subs = line["subscriptions"] as List<Map<String, Any?>>
                    assertEquals(1, subs.size)
                    assertEquals(1, (subs.single()["monitoredItemCount"] as Number).toInt())
                    assertTrue((line["requestCount"] as Number).toLong() > before)
                    proxy.drop()
                    await("opc disconnected") { !bool(read(g, OPC_TAGS)[0]) }
                    val dropped = http.get("/api/v1/diagnostics/opcua/connections")
                    valid(dropped.body)
                    @Suppress("UNCHECKED_CAST")
                    val down = (dropped.json["connections"] as List<Map<String, Any?>>).single { it["name"] == "line" }
                    assertEquals(false, down["connected"])
                    proxy.resume()
                    await("opc recovered") {
                        bool(read(g, OPC_TAGS)[0]) && int(read(g, OPC_TAGS)[7]) == 1
                    }
                }
            }
        }
    }

    @Test fun opcConnectionStatusRequiresGatewayRead() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val anon = Http(g.port())
            assertEquals(401, anon.get("/api/v1/diagnostics/opcua/connections").status)
            g.createUser("view", setOf(DefaultRoles.VIEWER))
            val view = Http(g.port())
            assertEquals(200, view.login("view", TEST_PASSWORD).status)
            assertEquals(403, view.get("/api/v1/diagnostics/opcua/connections").status)
            val http = Http(g.port())
            g.loginAdmin(http)
            val body = http.get("/api/v1/diagnostics/opcua/connections")
            assertEquals(200, body.status, body.body)
            valid(body.body)
            assertEquals(emptyList<Any?>(), body.json["connections"])
        }
    }

    private fun put(http: Http, path: String, body: String) {
        val res = http.put(path, body)
        assertEquals(201, res.status, res.body)
    }

    private fun read(g: Gateway, paths: List<String>): List<org.oip.tags.QualifiedValue> =
        paths.map { g.tags.read("[system]$it") }

    private fun readRest(http: Http, paths: List<String>): List<Map<*, *>> {
        val res = http.post("/api/v1/tags/read", Json.write(mapOf("paths" to paths)))
        assertEquals(200, res.status, res.body)
        @Suppress("UNCHECKED_CAST")
        return res.json["values"] as List<Map<*, *>>
    }

    private fun bool(value: org.oip.tags.QualifiedValue) = value.value == true && value.quality.code.name == "GOOD"
    private fun long(value: org.oip.tags.QualifiedValue) = (value.value as? Number)?.toLong() ?: -1L
    private fun int(value: org.oip.tags.QualifiedValue) = (value.value as? Number)?.toInt() ?: -1
    private fun double(value: org.oip.tags.QualifiedValue) = (value.value as? Number)?.toDouble() ?: Double.NaN

    private fun valid(body: String) {
        val schema = schemas.getSchema(mapper.readTree(javaClass.getResource("/diagnostics/opcua-connections.json")!!.readText()))
        val errors = schema.validate(mapper.readTree(body))
        assertTrue(errors.isEmpty()) { errors.joinToString { it.message } }
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 25_000_000_000L
        var last: Throwable? = null
        while (System.nanoTime() < end) {
            try {
                if (cond()) return
            } catch (e: Throwable) {
                last = e
            }
            Thread.sleep(50)
        }
        throw AssertionError(message, last)
    }

    companion object {
        private val MODBUS_TAGS = listOf(
            "Gateway/Device/plc/Diagnostics/Connected",
            "Gateway/Device/plc/Diagnostics/RequestCount",
            "Gateway/Device/plc/Diagnostics/ErrorCount",
            "Gateway/Device/plc/Diagnostics/TimeoutCount",
            "Gateway/Device/plc/Diagnostics/AverageResponseTime",
            "Gateway/Device/plc/Diagnostics/SamplingInterval",
            "Gateway/Device/plc/Diagnostics/PublishInterval",
        )
        private val OPC_TAGS = listOf(
            "Gateway/OPC/line/Diagnostics/Connected",
            "Gateway/OPC/line/Diagnostics/RequestCount",
            "Gateway/OPC/line/Diagnostics/ErrorCount",
            "Gateway/OPC/line/Diagnostics/TimeoutCount",
            "Gateway/OPC/line/Diagnostics/AverageResponseTime",
            "Gateway/OPC/line/Diagnostics/SamplingInterval",
            "Gateway/OPC/line/Diagnostics/PublishInterval",
            "Gateway/OPC/line/Diagnostics/MonitoredItemCount",
        )
    }
}

/** Function-3 slave that can stop and bind the same port again. */
private class RestartableSlave(private val port: Int) : AutoCloseable {
    private var server: ServerSocket? = null
    private var accept: Thread? = null
    @Volatile private var running = false

    @Synchronized fun start() {
        check(!running)
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", port))
        server = socket
        running = true
        accept = Thread({
            while (running && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ serve(client) }, "diag-modbus").apply { isDaemon = true }.start()
            }
        }, "diag-modbus-accept").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        running = false
        runCatching { server?.close() }
        accept?.join(1_000)
        server = null
        accept = null
    }

    override fun close() = stop()

    private fun serve(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val buf = ByteArray(260)
            while (running) {
                if (!readFully(input, buf, 6)) return
                val length = ((buf[4].toInt() and 0xff) shl 8) or (buf[5].toInt() and 0xff)
                if (length < 2 || length > 250 || !readFully(input, buf, length, 6)) return
                if ((buf[7].toInt() and 0xff) != 3) continue
                val count = ((buf[10].toInt() and 0xff) shl 8) or (buf[11].toInt() and 0xff)
                val pdu = ByteArray(2 + count * 2)
                pdu[0] = 3
                pdu[1] = (count * 2).toByte()
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
}

/** Bridges TCP until [drop]. Discovery still talks to the server; the session uses this port. */
private class TcpProxy(private val upstreamPort: Int) : AutoCloseable {
    private val listen = ServerSocket(0).apply { reuseAddress = true }
    val port: Int = listen.localPort
    @Volatile private var running = true
    @Volatile private var dropping = false
    private val live = CopyOnWriteArrayList<Socket>()
    private val acceptThread = Thread({
        while (running) {
            val down = try {
                listen.accept()
            } catch (_: Exception) {
                if (!running) break else continue
            }
            if (dropping) {
                runCatching { down.close() }
                continue
            }
            try {
                bridge(down)
            } catch (_: Exception) {
                runCatching { down.close() }
            }
        }
    }, "diag-opc-proxy").apply { isDaemon = true; start() }

    private fun bridge(down: Socket) {
        val up = Socket()
        up.tcpNoDelay = true
        down.tcpNoDelay = true
        up.connect(InetSocketAddress("127.0.0.1", upstreamPort), 3_000)
        live += down
        live += up
        pump(down, up)
        pump(up, down)
    }

    private fun pump(from: Socket, to: Socket) {
        Thread({
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buf = ByteArray(8_192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }, "diag-opc-proxy-io").apply { isDaemon = true; start() }
    }

    fun drop() {
        dropping = true
        live.forEach { runCatching { it.close() } }
        live.clear()
    }

    fun resume() {
        dropping = false
    }

    override fun close() {
        running = false
        dropping = true
        live.forEach { runCatching { it.close() } }
        runCatching { listen.close() }
        acceptThread.join(1_000)
    }
}

/** Anonymous OPC UA server with one Int32 variable. Unknown nodes are refused by Milo. */
private class DiagOpcServer(private val securityDir: Path) : AutoCloseable {
    val port: Int = ServerSocket(0).use { it.localPort }
    val endpointUrl: String = "opc.tcp://127.0.0.1:$port/oip"
    private lateinit var counter: UaVariableNode
    private lateinit var namespace: DiagNamespace
    private lateinit var server: OpcUaServer
    private var index: Int = 0

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        securityDir.createDirectories()
        val keys = OpcUaCertificates.generate("urn:oip:diag:server", "OIP Diag Server")
        val store = KeyStoreCertificateStore.createAndInitialize(KeyStoreCertificateStore.Settings(
            securityDir.resolve("server.pfx"),
            { "password".toCharArray() },
            { "password".toCharArray() },
        ))
        val quarantine = FileBasedCertificateQuarantine.create(securityDir.resolve("rejected"))
        val trust = MemoryTrustListManager()
        val factory = object : RsaSha256CertificateFactory() {
            override fun createRsaSha256KeyPair(): KeyPair = keys.keyPair
            override fun createRsaSha256CertificateChain(keyPair: KeyPair): Array<X509Certificate> = arrayOf(keys.certificate)
        }
        val group = DefaultApplicationGroup.createAndInitialize(
            trust, store, factory, DefaultServerCertificateValidator(trust, quarantine),
        )
        val endpoints = setOf(
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
                .build(),
        )
        val config = OpcUaServerConfig.builder()
            .setApplicationUri("urn:oip:diag:server")
            .setApplicationName(LocalizedText.english("OIP Diag"))
            .setProductUri("urn:oip:diag")
            .setEndpoints(endpoints)
            .setBuildInfo(BuildInfo("urn:oip:diag", "oip", "diag", OpcUaServer.SDK_VERSION, "", DateTime.now()))
            .setCertificateManager(DefaultCertificateManager(quarantine, group))
            .setIdentityValidator(AnonymousIdentityValidator.INSTANCE)
            .build()
        server = OpcUaServer(config) { OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build()) }
        namespace = DiagNamespace(server)
        namespace.startup()
        counter = namespace.counter
        index = namespace.namespaceIndex.toInt()
        server.startup().get(20, TimeUnit.SECONDS)
    }

    fun nodeId(name: String): String = "ns=$index;s=Sim/$name"

    fun setCounter(value: Int) {
        counter.setValue(DataValue(Variant(value), StatusCode.GOOD, DateTime.now()))
    }

    override fun close() {
        if (::namespace.isInitialized) runCatching { namespace.shutdown() }
        if (::server.isInitialized) runCatching { server.shutdown().get(15, TimeUnit.SECONDS) }
    }
}

private class DiagNamespace(server: OpcUaServer) : ManagedNamespaceWithLifecycle(server, "urn:oip:diag") {
    lateinit var counter: UaVariableNode
        private set
    private val subscriptionModel = SubscriptionModel(server, this)

    init {
        lifecycleManager.addLifecycle(subscriptionModel)
        lifecycleManager.addLifecycle(object : Lifecycle {
            override fun startup() {
                val folder = UaFolderNode(nodeContext, newNodeId("Sim"), newQualifiedName("Sim"), LocalizedText.english("Sim"))
                nodeManager.addNode(folder)
                val objects = server.addressSpaceManager.getManagedNode(NodeIds.ObjectsFolder).orElseThrow()
                objects.addReference(Reference(objects.nodeId, NodeIds.Organizes, folder.nodeId.expanded(), true))
                counter = UaVariableNode.builder(nodeContext)
                    .setNodeId(newNodeId("Sim/Counter"))
                    .setBrowseName(newQualifiedName("Counter"))
                    .setDisplayName(LocalizedText.english("Counter"))
                    .setDataType(NodeIds.Int32)
                    .setAccessLevel(AccessLevel.CurrentRead, AccessLevel.CurrentWrite)
                    .setUserAccessLevel(AccessLevel.CurrentRead, AccessLevel.CurrentWrite)
                    .setValue(DataValue(Variant(0), StatusCode.GOOD, DateTime.now()))
                    .buildAndAdd()
                folder.addOrganizes(counter)
            }
            override fun shutdown() {}
        })
    }

    override fun onDataItemsCreated(dataItems: List<DataItem>) = subscriptionModel.onDataItemsCreated(dataItems)
    override fun onDataItemsModified(dataItems: List<DataItem>) = subscriptionModel.onDataItemsModified(dataItems)
    override fun onDataItemsDeleted(dataItems: List<DataItem>) = subscriptionModel.onDataItemsDeleted(dataItems)
    override fun onMonitoringModeChanged(monitoredItems: List<MonitoredItem>) = subscriptionModel.onMonitoringModeChanged(monitoredItems)
}
