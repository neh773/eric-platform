package org.oip.gateway

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription
import org.eclipse.milo.opcua.stack.core.NodeIds
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.simulator.SimulatorDriverModule
import org.oip.tags.QualityCode
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * Programmable simulator through the gateway OPC UA `Devices` folder.
 *
 * Browse lists each simulator, including `[Controls]` and the Generic program's `_Meta:` item
 * paths. A read and a write reach the device. A session without `tags.write` is refused. A
 * monitored item is sampled on the device scheduler and delivers a change. Deleting the device
 * removes it. A name shared with another driver is qualified the same way as Logix.
 *
 * doc source: FEATURES DEV-004
 * doc source: FEATURES DEV-028
 * doc source: FEATURES DEV-028
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SimulatorCatalogGatewayTest {
    @TempDir lateinit var tmp: Path

    @Test fun genericProgramIsBrowsableReadableWritableAndSampled() {
        tmp.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: true
              bindAddress: 127.0.0.1
              port: 0
              hostname: 127.0.0.1
            """.trimIndent() + "\n",
        )
        Gateway(tmp, port = 0).use { g ->
            g.start()
            g.createUser("ada", setOf(DefaultRoles.OPERATOR))
            g.createUser("vic", setOf(DefaultRoles.VIEWER))
            val http = Http(g.port())
            g.loginAdmin(http)
            val created = http.put(
                "/api/v1/config/org.oip.driver-simulator/gateway/Device/gensim",
                genericBody("gensim"),
            )
            assertEquals(201, created.status, created.body)
            val extra = http.put(
                "/api/v1/config/org.oip.driver-simulator/gateway/Device/extra",
                constantBody("extra"),
            )
            assertEquals(201, extra.status, extra.body)

            val server = g.opcUa ?: error("opc ua server")
            val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
            val ada = connect(server.boundPort, endpoints, "ada")
            val vic = connect(server.boundPort, endpoints, "vic")
            try {
                await("both simulators") {
                    val names = children(ada, walk(ada, "Devices"))
                    if ("gensim" in names && "extra" in names) names else null
                }
                val root = children(ada, walk(ada, "Devices", "gensim"))
                assertTrue("[Controls]" in root, "root $root")
                assertTrue(root.containsAll(listOf("Writeable", "ReadOnly", "Sine", "Ramp", "Random", "Realistic")), "root $root")
                val controls = children(ada, walk(ada, "Devices", "gensim", "[Controls]"))
                assertTrue(
                    controls.containsAll(listOf("Base Rate", "Pause", "Program Counter", "Repeat", "Reset")),
                    "controls $controls",
                )

                val writable = walk(ada, "Devices", "gensim", "Writeable", "WriteableInteger1")
                val identifier = writable.identifier as String
                assertTrue(
                    identifier.endsWith("/_Meta:Writeable/WriteableInteger1"),
                    "legacy item path missing from $identifier",
                )
                val readOnly = walk(ada, "Devices", "gensim", "ReadOnly", "ReadOnlyInteger1")
                assertTrue((readOnly.identifier as String).endsWith("/_Meta:ReadOnly/ReadOnlyInteger1"))

                val initial = ada.readValues(0.0, TimestampsToReturn.Both, listOf(writable, readOnly)).toList()
                assertTrue(initial.all { it.statusCode.isGood }, "read $initial")
                assertEquals(0, (initial[0].value.value as Number).toInt())
                assertEquals(1, (initial[1].value.value as Number).toInt())
                // UserAccessLevel omits CurrentWrite on a read-only node, so the stack refuses the write
                // before it reaches the driver. The value stays the program's constant.
                val refusedReadOnly = ada.writeValues(listOf(readOnly), listOf(DataValue(Variant(9)))).single()
                assertEquals(StatusCodes.Bad_UserAccessDenied, refusedReadOnly.value)
                assertEquals(1, (ada.readValues(0.0, TimestampsToReturn.Neither, listOf(readOnly)).single().value.value as Number).toInt())

                val written = ada.writeValues(listOf(writable), listOf(DataValue(Variant(42)))).single()
                assertTrue(written.isGood, "write $written")
                val device = (g.host.instance(SimulatorDriverModule.MODULE_ID) as SimulatorDriverModule).device("gensim")
                assertEquals(42, device!!.read("_Meta:Writeable/WriteableInteger1").value)

                val sine = walk(ada, "Devices", "gensim", "Sine", "Sine1")
                val sineAtWrite = (ada.readValues(0.0, TimestampsToReturn.Both, listOf(sine)).single().value.value as Number).toDouble()
                await("sine advanced past the write") {
                    val sample = ada.readValues(0.0, TimestampsToReturn.Both, listOf(sine)).single()
                    if (sample.statusCode.isGood && (sample.value.value as Number).toDouble() != sineAtWrite) true else null
                }
                val held = ada.readValues(0.0, TimestampsToReturn.Both, listOf(writable)).single()
                assertEquals(42, (held.value.value as Number).toInt(), "Generic writable value was republished")
                assertEquals(42, device.read("Writeable/WriteableInteger1").value)

                val refused = vic.writeValues(listOf(writable), listOf(DataValue(Variant(7)))).single()
                assertEquals(StatusCodes.Bad_UserAccessDenied, refused.value)
                assertEquals(42, device.read("Writeable/WriteableInteger1").value)

                await("simulator diagnostics") {
                    val status = g.tags.read("[system]Gateway/Device/gensim/Status")
                    val connected = g.tags.read("[system]Gateway/Device/gensim/Diagnostics/Connected")
                    val sampling = g.tags.read("[system]Gateway/Device/gensim/Diagnostics/SamplingInterval")
                    if (status.value == "Connected" && connected.value == true &&
                        (sampling.value as? Number)?.toDouble() == 200.0
                    ) true else null
                }

                val seen = CopyOnWriteArrayList<Double>()
                val sampling = 200.0
                val sub = OpcUaSubscription(ada, 50.0)
                sub.create()
                val item = OpcUaMonitoredItem.newDataItem(sine)
                item.samplingInterval = sampling
                item.setDataValueListener { _, value ->
                    val number = value.value.value as? Number ?: return@setDataValueListener
                    if (value.statusCode.isGood) seen += number.toDouble()
                }
                sub.addMonitoredItem(item)
                sub.synchronizeMonitoredItems()
                try {
                    await("monitored sine changes") { if (seen.toSet().size >= 2) true else null }
                    val revised = item.revisedSamplingInterval.orElse(Double.NaN)
                    assertEquals(sampling, revised, 1.0, "revised sampling $revised")
                } finally {
                    runCatching { sub.delete() }
                }

                val level = walk(ada, "Devices", "extra", "Level")
                assertEquals(3, (ada.readValues(0.0, TimestampsToReturn.Neither, listOf(level)).single().value.value as Number).toInt())
                assertEquals(204, http.delete("/api/v1/config/org.oip.driver-simulator/gateway/Device/extra").status)
                await("extra removed") {
                    val names = children(ada, walk(ada, "Devices"))
                    if ("extra" !in names && "gensim" in names) names else null
                }
                val gone = ada.readValues(0.0, TimestampsToReturn.Neither, listOf(level)).single()
                assertEquals(StatusCodes.Bad_NodeIdUnknown, gone.statusCode.value)

                assertEquals(204, http.delete("/api/v1/config/org.oip.driver-simulator/gateway/Device/gensim").status)
                await("gensim removed") {
                    if ("gensim" !in children(ada, walk(ada, "Devices"))) true else null
                }
                await("diagnostics removed") {
                    val sample = g.tags.read("[system]Gateway/Device/gensim/Status")
                    if (sample.quality.code == QualityCode.BAD_NOT_FOUND) true else null
                }
            } finally {
                runCatching { ada.disconnect() }
                runCatching { vic.disconnect() }
            }
        }
    }

    @Test fun nameCollisionIsQualifiedLikeLogix() {
        tmp.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: true
              bindAddress: 127.0.0.1
              port: 0
              hostname: 127.0.0.1
            """.trimIndent() + "\n",
        )
        Gateway(tmp, port = 0).use { g ->
            g.start()
            g.createUser("ada", setOf(DefaultRoles.OPERATOR))
            val http = Http(g.port())
            g.loginAdmin(http)
            assertEquals(201, http.put(
                "/api/v1/config/org.oip.driver-simulator/gateway/Device/plant",
                constantBody("plant"),
            ).status)
            // A second driver with the same resource name. It does not need a CPU; the folder is the name.
            assertEquals(201, http.put(
                "/api/v1/config/org.oip.driver-s7/gateway/Device/plant",
                """
                {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"plant","module":"org.oip.driver-s7","version":0},
                 "spec":{"name":"plant","host":"127.0.0.1","port":1,"timeoutMs":200,"backoffInitialMs":40,"backoffMaxMs":200,"defaultScanPeriodMs":1000}}
                """.trimIndent(),
            ).status)
            val server = g.opcUa ?: error("opc ua server")
            val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
            val ada = connect(server.boundPort, endpoints, "ada")
            try {
                val names = await("qualified names") {
                    val listed = children(ada, walk(ada, "Devices"))
                    if ("plant (simulator)" in listed && "plant (s7)" in listed) listed else null
                }
                assertFalse("plant" in names, "unqualified name still listed in $names")
                val level = walk(ada, "Devices", "plant (simulator)", "Level")
                val identifier = level.identifier as String
                assertTrue(identifier.contains("simulator\u001fplant/"), identifier)
                assertEquals(3, (ada.readValues(0.0, TimestampsToReturn.Neither, listOf(level)).single().value.value as Number).toInt())
                val written = ada.writeValues(listOf(level), listOf(DataValue(Variant(8)))).single()
                assertTrue(written.isGood, "qualified write $written")
                val device = (g.host.instance(SimulatorDriverModule.MODULE_ID) as SimulatorDriverModule).device("plant")
                assertEquals(8, device!!.read("Level").value)
            } finally {
                runCatching { ada.disconnect() }
            }
        }
    }

    private fun genericBody(name: String) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"$name","module":"org.oip.driver-simulator","version":0},
         "spec":{"name":"$name","enabled":true,"program":"generic","baseRateMs":200,"randomSeed":1}}
    """.trimIndent()

    private fun constantBody(name: String) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"$name","module":"org.oip.driver-simulator","version":0},
         "spec":{"name":"$name","enabled":true,"repeatProgram":false,"baseRateMs":60000,
           "instructions":[{"timeInterval":0,"browsePath":"Level","valueSource":"3","dataType":"Int32"}]}}
    """.trimIndent()

    private fun <T> await(message: String, probe: () -> T?): T {
        val end = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < end) {
            val value = runCatching { probe() }.getOrNull()
            if (value != null) return value
            Thread.sleep(40)
        }
        throw AssertionError(message)
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
            .setApplicationName(LocalizedText.english("oip-simulator-catalog"))
            .setApplicationUri("urn:oip:test:simulator-catalog")
            .setProductUri("urn:oip:test")
            .setRequestTimeout(uint(15_000))
            .setSessionTimeout(uint(60_000))
            .setIdentityProvider(UsernameProvider(username, TEST_PASSWORD))
            .build()
        val client = OpcUaClient(config, OpcTcpClientTransport(OpcTcpClientTransportConfig.newBuilder().build()))
        client.connect()
        return client
    }
}
