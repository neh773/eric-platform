package org.oip.gateway

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider
import org.eclipse.milo.opcua.stack.core.NodeIds
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.sim.logix.Ab
import org.oip.sim.logix.LogixSimulator
import org.oip.tags.QualityCode
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * Logix device through the gateway: OPC tag subscription, diagnostic tags, and the OPC UA
 * `Devices` folder.
 *
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LogixGatewayTest {
    @TempDir lateinit var tmp: Path

    @Test fun tagDiagnosticsAndDeviceCatalogRoundTrip() {
        LogixSimulator().use { sim ->
            sim.atomic("Speed", Ab.DINT, 7)
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
                val put = http.put(
                    "/api/v1/config/org.oip.driver-logix/gateway/Device/line",
                    """
                    {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"line","module":"org.oip.driver-logix","version":0},
                     "spec":{"name":"line","host":"127.0.0.1","port":${sim.port},"slot":0,"timeoutMs":500,
                       "identityRequestFrequencyMs":0,"backoffInitialMs":40,"backoffMaxMs":200,"defaultScanPeriodMs":200}}
                    """.trimIndent(),
                )
                assertEquals(201, put.status, put.body)
                val tag = http.put(
                    "/api/v1/config/tags/gateway/Tag/speed",
                    """
                    {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"speed","module":"tags","version":0},
                     "spec":{"provider":"default","path":"plant/speed","type":"opc","dataType":"Int32","opcServer":"line",
                       "opcItemPath":"Speed","scanPeriodMillis":200}}
                    """.trimIndent(),
                )
                assertEquals(201, tag.status, tag.body)
                await("opc tag") {
                    val sample = g.tags.read("plant/speed")
                    sample.value == 7 && sample.quality.code == QualityCode.GOOD
                }
                await("diagnostics") {
                    val row = listOf(
                        "Gateway/Device/line/Diagnostics/Connected",
                        "Gateway/Device/line/Diagnostics/RequestCount",
                        "Gateway/Device/line/Status",
                    ).map { g.tags.read("[system]$it") }
                    row[0].value == true && ((row[1].value as? Number)?.toLong() ?: 0L) >= 1L && row[2].value == "Connected"
                }
                val written = g.tags.write("plant/speed", 11)
                assertEquals(QualityCode.GOOD, written.quality.code, written.quality.diagnostic)
                await("written") { g.tags.read("plant/speed").value == 11 && sim.get("Speed") == 11 }

                val server = g.opcUa ?: error("opc ua server")
                val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
                val ada = connect(server.boundPort, endpoints)
                try {
                    await("catalog") { "line" in children(ada, walk(ada, "Devices")) }
                    val speed = walk(ada, "Devices", "line", "Controller:Global", "Speed")
                    val read = ada.readValues(0.0, TimestampsToReturn.Both, listOf(speed)).single()
                    assertTrue(read.statusCode.isGood, "read ${read.statusCode}")
                    assertEquals(11, (read.value.value as Number).toInt())
                    val result = ada.writeValues(listOf(speed), listOf(DataValue(Variant(21)))).single()
                    assertTrue(result.isGood, "write $result")
                    assertEquals(21, sim.get("Speed"))
                } finally {
                    runCatching { ada.disconnect() }
                }

                sim.stop()
                await("bad tag") { g.tags.read("plant/speed").quality.code == QualityCode.BAD_NOT_CONNECTED }
                await("diag down") { g.tags.read("[system]Gateway/Device/line/Diagnostics/Connected").value == false }
                sim.start()
                await("recovered") {
                    g.tags.read("plant/speed").quality.code == QualityCode.GOOD &&
                        g.tags.read("[system]Gateway/Device/line/Diagnostics/Connected").value == true
                }
            }
        }
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < end) {
            if (runCatching { cond() }.getOrDefault(false)) return
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

    private fun connect(port: Int, endpoints: List<org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription>): OpcUaClient {
        val selected = endpoints.first { ep ->
            ep.securityPolicyUri == SecurityPolicy.None.uri &&
                ep.endpointUrl.contains("/oip") &&
                !ep.endpointUrl.contains("discovery")
        }
        val config = OpcUaClientConfig.builder()
            .setEndpoint(EndpointUtil.updateUrl(selected, "127.0.0.1", port))
            .setApplicationName(LocalizedText.english("oip-logix-catalog"))
            .setApplicationUri("urn:oip:test:logix-catalog")
            .setProductUri("urn:oip:test")
            .setRequestTimeout(uint(15_000))
            .setSessionTimeout(uint(60_000))
            .setIdentityProvider(UsernameProvider("ada", TEST_PASSWORD))
            .build()
        val client = OpcUaClient(config, OpcTcpClientTransport(OpcTcpClientTransportConfig.newBuilder().build()))
        client.connect()
        return client
    }
}
