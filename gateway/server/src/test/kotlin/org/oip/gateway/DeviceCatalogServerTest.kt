package org.oip.gateway

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.NodeIds
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.opcua.server.DeviceCatalog
import org.oip.opcua.server.DeviceEndpoint
import org.oip.opcua.server.DeviceItem
import org.oip.opcua.server.DeviceReading
import org.oip.opcua.server.OpcUaServerSettings
import org.oip.opcua.server.OpcUaTagServer
import org.oip.opcua.server.OpcUaUserAuthenticator
import org.oip.tags.TagCaller
import org.oip.tags.TagEngine
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device catalog address space without a simulator: browse, read, write, a session without
 * `tags.write`, and a device that appears and disappears while the server stays up.
 *
 * The compose-simulator pass is [DeviceCatalogSimulatorTest].
 */
class DeviceCatalogServerTest {
    @TempDir lateinit var tmp: Path

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun browseReadWritePermissionAndRuntimeMembership() {
        val catalog = MutableCatalog()
        val line = FakeDevice("line")
        line.values["Speed"] = 3
        catalog.endpoints["line"] = line
        val server = OpcUaTagServer(
            OpcUaServerSettings(port = 0, hostname = "127.0.0.1", bindAddress = "127.0.0.1"),
            tmp.resolve("opcua"),
            TagEngine(),
            OpcUaUserAuthenticator { user, _, _, _ ->
                when (user) {
                    "ada" -> TagCaller.User("ada", setOf("operator"), "Default", tagWrite = true)
                    "vic" -> TagCaller.User("vic", setOf("viewer"), "Default", tagWrite = false)
                    else -> null
                }
            },
            "test",
            devices = catalog,
        )
        server.use {
            it.start()
            val endpoints = DiscoveryClient.getEndpoints(it.endpointUrl).get(15, TimeUnit.SECONDS)
            val ada = connect(it.boundPort, endpoints, "ada")
            val vic = connect(it.boundPort, endpoints, "vic")
            try {
                val roots = children(ada, NodeIds.ObjectsFolder)
                assertTrue("Devices" in roots, "roots $roots")
                assertTrue("Tags" in roots, "roots $roots")
                assertTrue("line" in children(ada, walk(ada, "Devices")))

                val speed = walk(ada, "Devices", "line", "Speed")
                val read = ada.readValues(0.0, TimestampsToReturn.Both, listOf(speed)).single()
                assertTrue(read.statusCode.isGood, "read ${read.statusCode}")
                assertEquals(3, (read.value.value as Number).toInt())

                assertEquals(0x02, userAccess(ada, speed) and 0x02)
                assertEquals(0, userAccess(vic, speed) and 0x02)

                val written = ada.writeValues(listOf(speed), listOf(DataValue(Variant(9)))).single()
                assertTrue(written.isGood, "write $written")
                assertEquals(9, line.values["Speed"])
                assertEquals(1, line.writes.get())

                val refused = vic.writeValues(listOf(speed), listOf(DataValue(Variant(4)))).single()
                assertEquals(StatusCodes.Bad_UserAccessDenied, refused.value)
                assertEquals(9, line.values["Speed"])
                assertEquals(1, line.writes.get(), "a refused write must not reach the device")
                val still = vic.readValues(0.0, TimestampsToReturn.Both, listOf(speed)).single()
                assertEquals(9, (still.value.value as Number).toInt())

                catalog.endpoints["extra"] = FakeDevice("extra").also { device -> device.values["X"] = 1 }
                assertTrue("extra" in children(ada, walk(ada, "Devices")))
                val extra = walk(ada, "Devices", "extra", "X")
                assertEquals(1, (ada.readValues(0.0, TimestampsToReturn.Neither, listOf(extra)).single().value.value as Number).toInt())

                catalog.endpoints.remove("extra")
                val remaining = children(ada, walk(ada, "Devices"))
                assertFalse("extra" in remaining, "removed device still listed in $remaining")
                assertTrue("line" in remaining, "browse listed $remaining")
                val gone = ada.readValues(0.0, TimestampsToReturn.Neither, listOf(extra)).single()
                assertEquals(StatusCodes.Bad_NodeIdUnknown, gone.statusCode.value)
            } finally {
                runCatching { ada.disconnect() }
                runCatching { vic.disconnect() }
            }
        }
    }

    private fun userAccess(client: OpcUaClient, node: NodeId): Int {
        val dv = client.read(
            0.0,
            TimestampsToReturn.Neither,
            listOf(ReadValueId(node, AttributeId.UserAccessLevel.uid(), null, QualifiedName.NULL_VALUE)),
        ).results!!.single()
        assertTrue(dv.statusCode.isGood, "UserAccessLevel ${dv.statusCode}")
        return (dv.value.value as Number).toInt()
    }

    private fun children(client: OpcUaClient, node: NodeId): List<String> =
        client.addressSpace.browse(node).mapNotNull { it.browseName.name }

    private fun walk(client: OpcUaClient, vararg names: String): NodeId {
        var node = NodeIds.ObjectsFolder
        for (name in names) node = child(client, node, name)
        return node
    }

    private fun child(client: OpcUaClient, parent: NodeId, name: String): NodeId {
        val refs = client.addressSpace.browse(parent)
        val match = refs.firstOrNull { it.browseName.name.equals(name, ignoreCase = true) }
            ?: error("no '$name' under $parent; saw ${refs.map { it.browseName.name }}")
        return match.nodeId.toNodeId(client.namespaceTable).orElseThrow()
    }

    private fun connect(port: Int, endpoints: List<org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription>, username: String): OpcUaClient {
        val selected = endpoints.first { ep ->
            ep.securityPolicyUri == SecurityPolicy.None.uri &&
                ep.endpointUrl.contains("/oip") &&
                !ep.endpointUrl.contains("discovery")
        }
        val config = OpcUaClientConfig.builder()
            .setEndpoint(EndpointUtil.updateUrl(selected, "127.0.0.1", port))
            .setApplicationName(LocalizedText.english("oip-device-catalog"))
            .setApplicationUri("urn:oip:test:device-catalog")
            .setProductUri("urn:oip:test")
            .setRequestTimeout(uint(15_000))
            .setSessionTimeout(uint(60_000))
            .setIdentityProvider(UsernameProvider(username, "password"))
            .build()
        val client = OpcUaClient(config, OpcTcpClientTransport(OpcTcpClientTransportConfig.newBuilder().build()))
        try {
            client.connect()
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
        return client
    }

    private class MutableCatalog : DeviceCatalog {
        val endpoints = ConcurrentHashMap<String, DeviceEndpoint>()
        override fun devices(): List<DeviceEndpoint> = endpoints.values.sortedBy { it.name }
        override fun device(name: String): DeviceEndpoint? = endpoints[name]
    }

    private class FakeDevice(override val name: String) : DeviceEndpoint {
        override val driver = "fake"
        val values = ConcurrentHashMap<String, Any?>()
        val writes = AtomicInteger()

        override fun browse(nodeId: String): List<DeviceItem> {
            if (nodeId.isNotEmpty()) return emptyList()
            return values.keys.sorted().map { id -> DeviceItem(id, id, variable = true, readable = true, writable = true) }
        }

        override fun describe(nodeId: String): DeviceItem? =
            if (values.containsKey(nodeId)) DeviceItem(nodeId, nodeId, variable = true, readable = true, writable = true) else null

        override fun read(nodeIds: List<String>): List<DeviceReading> =
            nodeIds.map { id -> DeviceReading(values[id], 0L, null, Instant.EPOCH) }

        override fun write(nodeId: String, value: Any?): Long {
            writes.incrementAndGet()
            values[nodeId] = value
            return 0L
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double =
            org.oip.opcua.server.revisedDriverInterval(requestedMs)

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            listener(read(listOf(nodeId)).single())
            return AutoCloseable {}
        }
    }
}
