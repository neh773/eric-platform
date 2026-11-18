package org.oip.gateway

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.eclipse.milo.opcua.sdk.core.AccessLevel
import org.eclipse.milo.opcua.sdk.core.Reference
import org.eclipse.milo.opcua.sdk.server.AddressSpace
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
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.opcua.OpcUaCertificates
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.tags.Level
import org.oip.tags.TAG_WRITE_ACTION
import org.oip.tags.TagEngine
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * Review-2 F5: a tag write reaches the device, and the writer and the audit log get the device's answer (TAG-033,
 * TAG-013, SEC-003). Writes go through REST to a Modbus slave and an OPC UA server, in process, and assert:
 * - the device holds an accepted value and the REST row is Good, audited as a success;
 * - a write the device refuses returns a bad row, is audited as rejected, and the tag keeps the device's value
 *   rather than the attempted one;
 * - two writes queued back to back leave the second value on the device, even when the first is slow to land.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DeviceWriteTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun modbusWritesReturnTheDeviceResultInOrder() {
        WritableModbus().use { slave ->
            slave.holding[0] = 42
            slave.holding[1] = 7
            slave.refused += 1 // HR2 answers every write with Modbus exception 4 (server device failure).
            slave.start()
            gateway().use { g ->
                val http = Http(g.port())
                g.loginAdmin(http)
                put(http, "/api/v1/config/org.oip.driver-modbus/gateway/Device/line", modbusDevice(slave.port))
                put(http, "/api/v1/config/tags/gateway/Tag/flow", tag("flow", "line", "1.HR1"))
                put(http, "/api/v1/config/tags/gateway/Tag/locked", tag("locked", "line", "1.HR2"))
                awaitValue(g.tags, "plant/flow", 42)
                awaitValue(g.tags, "plant/locked", 7)

                // Accepted: the device holds the value, the row is Good and the audit row is a success.
                val ok = write(http, "plant/flow", 55)
                assertEquals(true, ok["ok"], "$ok")
                assertEquals(55, slave.holding[0])
                assertEquals(AuditStatus.SUCCESS, lastAudit(g, "plant/flow").status)

                // Refused by the device: a bad row, a rejected audit row, and the tag keeps the device's value.
                val refused = write(http, "plant/locked", 99)
                assertEquals(false, refused["ok"], "$refused")
                assertEquals(7, slave.holding[1])
                assertEquals(7, (g.tags.read("plant/locked").value as Number).toInt())
                val audit = lastAudit(g, "plant/locked")
                assertEquals(AuditStatus.FAILURE, audit.status)
                assertTrue(audit.detail.startsWith("rejected"), audit.detail)

                // Two writes queued at once: the slow first one does not land after the second.
                slave.slowValue = 111
                g.tags.write("plant/flow", 111)
                g.tags.write("plant/flow", 222)
                awaitTrue("device ends on the second write") { slave.holding[0] == 222 && slave.writes.get() >= 3 }
                Thread.sleep(SLOW_MS + 200)
                assertEquals(222, slave.holding[0])
                awaitValue(g.tags, "plant/flow", 222)
            }
        }
    }

    @Test
    fun opcUaWritesReturnTheDeviceResultInOrder() {
        WritableOpcSim(tmp.resolve("opc-sim")).use { sim ->
            gateway().use { g ->
                val http = Http(g.port())
                g.loginAdmin(http)
                put(http, "/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/line", opcConnection(sim.port))
                put(http, "/api/v1/config/tags/gateway/Tag/setpoint", tag("setpoint", "line", sim.nodeId("Setpoint")))
                put(http, "/api/v1/config/tags/gateway/Tag/locked", tag("locked", "line", sim.nodeId("Locked")))
                awaitValue(g.tags, "plant/setpoint", 5)
                awaitValue(g.tags, "plant/locked", 11)

                val ok = write(http, "plant/setpoint", 55)
                assertEquals(true, ok["ok"], "$ok")
                assertEquals(55, sim.value("Setpoint"))
                assertEquals(AuditStatus.SUCCESS, lastAudit(g, "plant/setpoint").status)

                // The server refuses (read-only node): bad row, rejected audit row, the tag keeps the device value.
                val refused = write(http, "plant/locked", 99)
                assertEquals(false, refused["ok"], "$refused")
                assertEquals(11, sim.value("Locked"))
                assertEquals(11, (g.tags.read("plant/locked").value as Number).toInt())
                val audit = lastAudit(g, "plant/locked")
                assertEquals(AuditStatus.FAILURE, audit.status)
                assertTrue(audit.detail.startsWith("rejected"), audit.detail)
                // A subscription reports only changes; the re-read brings the unchanged value back to Good.
                awaitTrue("locked tag back to Good") { g.tags.read("plant/locked").quality.level == Level.GOOD }

                // The server holds the first write for SLOW_MS; with the writes in flight together it would land last.
                sim.slowValue = 111
                g.tags.write("plant/setpoint", 111)
                g.tags.write("plant/setpoint", 222)
                awaitTrue("device ends on the second write") { sim.value("Setpoint") == 222 && sim.writes.get() >= 3 }
                Thread.sleep(SLOW_MS + 300)
                assertEquals(222, sim.value("Setpoint"))
                awaitValue(g.tags, "plant/setpoint", 222)
            }
        }
    }

    private fun gateway(): Gateway {
        Files.writeString(tmp.resolve("gateway.yaml"), "profile: standard\nopcua:\n  enabled: false\n")
        return Gateway(tmp, port = 0).also { it.start() }
    }

    private fun put(http: Http, path: String, body: String) {
        val res = http.put(path, body)
        assertEquals(201, res.status, res.body)
    }

    private fun write(http: Http, path: String, value: Int): Map<*, *> {
        val res = http.post("/api/v1/tags/write", Json.write(mapOf("writes" to listOf(mapOf("path" to path, "value" to value)))))
        assertEquals(200, res.status, res.body)
        return (res.json["results"] as List<*>).single() as Map<*, *>
    }

    private fun lastAudit(g: Gateway, path: String) =
        g.security.audit.query(AuditQuery(action = TAG_WRITE_ACTION, targetContains = path)).records.first()

    private fun awaitValue(engine: TagEngine, path: String, expected: Int) =
        awaitTrue("$path == $expected") {
            val qv = engine.read(path)
            qv.quality.level == Level.GOOD && (qv.value as? Number)?.toInt() == expected
        }

    private fun awaitTrue(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!check()) {
            assertFalse(System.nanoTime() > deadline, "timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun modbusDevice(port: Int) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"line","module":"org.oip.driver-modbus","version":0},
         "spec":{"name":"line","host":"127.0.0.1","port":$port,"mode":"TCP","unitId":1,"zeroBasedAddressing":false,
           "timeoutMs":3000,"maxRetryCount":0,"defaultScanPeriodMs":100}}
    """.trimIndent()

    private fun opcConnection(port: Int) = """
        {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"line","module":"org.oip.driver-opcua-client","version":0},
         "spec":{"name":"line","endpointUrl":"opc.tcp://127.0.0.1:$port/oip","connectHost":"127.0.0.1","connectPort":$port,
           "securityPolicy":"NONE","securityMode":"NONE","trustAllServerCertificates":false,"requestTimeoutMs":5000}}
    """.trimIndent()

    private fun tag(name: String, server: String, item: String) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"plant/$name","type":"opc","dataType":"Int32","opcServer":"$server",
           "opcItemPath":"$item","scanPeriodMillis":100}}
    """.trimIndent()

    companion object {
        /** How long a simulator holds a write of its slow value before it applies it. */
        const val SLOW_MS = 600L
    }
}

/** Modbus TCP slave: reads (FC3), single and multiple register writes (FC6, FC16), refused registers, a slow value. */
private class WritableModbus : AutoCloseable {
    private val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 0)) }
    val port: Int = socket.localPort
    val holding = IntArray(8)
    /** Register indexes whose writes answer exception 4. */
    val refused: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile var slowValue: Int? = null
    val writes = java.util.concurrent.atomic.AtomicInteger()

    fun start() {
        Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                Thread({ serve(client) }, "modbus-write-sim").apply { isDaemon = true }.start()
            }
        }, "modbus-write-sim-accept").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val buf = ByteArray(260)
            while (true) {
                if (!readFully(input, buf, 6)) return
                val length = ((buf[4].toInt() and 0xff) shl 8) or (buf[5].toInt() and 0xff)
                if (length < 2 || length > 250 || !readFully(input, buf, length, 6)) return
                val function = buf[7].toInt() and 0xff
                val address = u16(buf, 8)
                val pdu: ByteArray = when (function) {
                    3 -> {
                        val count = u16(buf, 10)
                        ByteArray(2 + count * 2).also { pdu ->
                            pdu[0] = 3
                            pdu[1] = (count * 2).toByte()
                            for (i in 0 until count) {
                                val value = if (address + i < holding.size) holding[address + i] else 0
                                pdu[2 + i * 2] = (value shr 8).toByte()
                                pdu[3 + i * 2] = value.toByte()
                            }
                        }
                    }
                    6, 16 -> {
                        val value = u16(buf, if (function == 6) 10 else 13)
                        writes.incrementAndGet()
                        if (address in refused) {
                            byteArrayOf((function or 0x80).toByte(), 4)
                        } else {
                            if (value == slowValue) Thread.sleep(DeviceWriteTest.SLOW_MS)
                            holding[address] = value
                            if (function == 6) buf.copyOfRange(7, 12) else buf.copyOfRange(7, 12)
                        }
                    }
                    else -> byteArrayOf((function or 0x80).toByte(), 1)
                }
                val frame = ByteArray(7 + pdu.size)
                frame[0] = buf[0]
                frame[1] = buf[1]
                val follow = 1 + pdu.size
                frame[4] = (follow shr 8).toByte()
                frame[5] = follow.toByte()
                frame[6] = buf[6]
                pdu.copyInto(frame, 7)
                synchronized(output) {
                    output.write(frame)
                    output.flush()
                }
            }
        }
    }

    private fun u16(buf: ByteArray, at: Int) = ((buf[at].toInt() and 0xff) shl 8) or (buf[at + 1].toInt() and 0xff)

    private fun readFully(input: InputStream, buf: ByteArray, n: Int, offset: Int = 0): Boolean {
        var got = 0
        while (got < n) {
            val r = try { input.read(buf, offset + got, n - got) } catch (_: Exception) { -1 }
            if (r < 0) return false
            got += r
        }
        return true
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * OPC UA server with a writable Int32 `Setpoint` and a read-only `Locked`. A write of [slowValue] is held for
 * [DeviceWriteTest.SLOW_MS] before it applies, so two writes in flight together would land out of order.
 */
private class WritableOpcSim(private val securityDir: Path) : AutoCloseable {
    val port: Int
    private val keys = OpcUaCertificates.generate("urn:oip:write-sim", "OIP Write Sim")
    private val server: OpcUaServer
    private val namespace: SimNamespace
    private val store: KeyStoreCertificateStore
    @Volatile var slowValue: Int? = null
    val writes = java.util.concurrent.atomic.AtomicInteger()

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(BouncyCastleProvider())
        port = ServerSocket(0).use { it.localPort }
        securityDir.toFile().mkdirs()
        val trust = MemoryTrustListManager()
        store = KeyStoreCertificateStore.createAndInitialize(KeyStoreCertificateStore.Settings(
            securityDir.resolve("server.pfx"), { "password".toCharArray() }, { "password".toCharArray() },
        ))
        val quarantine = FileBasedCertificateQuarantine.create(securityDir.resolve("rejected"))
        val factory = object : RsaSha256CertificateFactory() {
            override fun createRsaSha256KeyPair(): KeyPair = keys.keyPair
            override fun createRsaSha256CertificateChain(keyPair: KeyPair): Array<X509Certificate> = arrayOf(keys.certificate)
        }
        val group = DefaultApplicationGroup.createAndInitialize(trust, store, factory, DefaultServerCertificateValidator(trust, quarantine))
        val endpoint = EndpointConfig.newBuilder()
            .setBindAddress("127.0.0.1").setHostname("127.0.0.1").setBindPort(port).setPath("/oip")
            .setCertificate(keys.certificate)
            .setSecurityPolicy(SecurityPolicy.None).setSecurityMode(MessageSecurityMode.None)
            .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
            .addTokenPolicies(OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS)
            .build()
        val config = OpcUaServerConfig.builder()
            .setApplicationUri("urn:oip:write-sim")
            .setApplicationName(LocalizedText.english("OIP Write Sim"))
            .setProductUri("urn:oip:write-sim")
            .setEndpoints(setOf(endpoint))
            .setBuildInfo(BuildInfo("urn:oip:write-sim", "oip", "write sim", OpcUaServer.SDK_VERSION, "", DateTime.now()))
            .setCertificateManager(DefaultCertificateManager(quarantine, group))
            .setIdentityValidator(AnonymousIdentityValidator.INSTANCE)
            .build()
        server = OpcUaServer(config) { OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build()) }
        namespace = SimNamespace(server, this)
        namespace.startup()
        server.startup().get(15, TimeUnit.SECONDS)
    }

    fun nodeId(name: String): String = "ns=${namespace.namespaceIndex};s=Sim/$name"

    fun value(name: String): Int = (namespace.nodes.getValue(name).value.value.value as Number).toInt()

    override fun close() {
        runCatching { namespace.shutdown() }
        runCatching { server.shutdown().get(15, TimeUnit.SECONDS) }
        runCatching { store.close() }
    }

    private class SimNamespace(server: OpcUaServer, private val sim: WritableOpcSim) :
        ManagedNamespaceWithLifecycle(server, "urn:oip:write-sim") {
        private val subscriptionModel = SubscriptionModel(server, this)
        val nodes = java.util.concurrent.ConcurrentHashMap<String, UaVariableNode>()

        init {
            lifecycleManager.addLifecycle(subscriptionModel)
            lifecycleManager.addLifecycle(object : Lifecycle {
                override fun startup() = buildNodes()
                override fun shutdown() {}
            })
        }

        override fun write(context: AddressSpace.WriteContext, writeValues: MutableList<WriteValue>): MutableList<StatusCode> {
            for (write in writeValues) {
                sim.writes.incrementAndGet()
                if ((write.value?.value?.value as? Number)?.toInt() == sim.slowValue) Thread.sleep(DeviceWriteTest.SLOW_MS)
            }
            return super.write(context, writeValues)
        }

        override fun onDataItemsCreated(dataItems: List<DataItem>) = subscriptionModel.onDataItemsCreated(dataItems)
        override fun onDataItemsModified(dataItems: List<DataItem>) = subscriptionModel.onDataItemsModified(dataItems)
        override fun onDataItemsDeleted(dataItems: List<DataItem>) = subscriptionModel.onDataItemsDeleted(dataItems)
        override fun onMonitoringModeChanged(monitoredItems: List<MonitoredItem>) = subscriptionModel.onMonitoringModeChanged(monitoredItems)

        private fun buildNodes() {
            val folder = UaFolderNode(nodeContext, newNodeId("Sim"), newQualifiedName("Sim"), LocalizedText.english("Sim"))
            nodeManager.addNode(folder)
            val objects = server.addressSpaceManager.getManagedNode(NodeIds.ObjectsFolder).orElseThrow()
            objects.addReference(Reference(objects.nodeId, NodeIds.Organizes, folder.nodeId.expanded(), true))
            variable(folder, "Setpoint", 5, AccessLevel.READ_WRITE)
            variable(folder, "Locked", 11, AccessLevel.READ_ONLY)
        }

        private fun variable(folder: UaFolderNode, name: String, initial: Int, access: Set<AccessLevel>) {
            val node = UaVariableNode.builder(nodeContext)
                .setNodeId(newNodeId("Sim/$name"))
                .setBrowseName(newQualifiedName(name))
                .setDisplayName(LocalizedText.english(name))
                .setDataType(NodeIds.Int32)
                .setAccessLevel(access)
                .setUserAccessLevel(access)
                .setValue(DataValue(Variant(initial), StatusCode.GOOD, DateTime.now()))
                .buildAndAdd()
            folder.addOrganizes(node)
            nodes[name] = node
        }
    }
}
