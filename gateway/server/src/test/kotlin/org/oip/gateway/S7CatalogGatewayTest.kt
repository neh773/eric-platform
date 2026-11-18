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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.sim.s7.S7Simulator
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * S7 device through the gateway OPC UA `Devices` folder.
 *
 * Browse lists each device and the addresses the driver is polling. A read and a write reach the
 * CPU image. A session without `tags.write` is refused. A monitored item is sampled on the driver
 * scan and delivers a change. Deleting the device removes it. A name shared with the simulator is
 * qualified the same way as Logix.
 *
 * doc source: FEATURES DEV-004
 * doc source: FEATURES DEV-004
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class S7CatalogGatewayTest {
    @TempDir lateinit var tmp: Path

    @Test fun addressTreeIsBrowsableReadableWritableAndSampled() {
        S7Simulator().use { cpu ->
            S7Simulator().use { other ->
                cpu.setBytes('D', 0, byteArrayOf(0x00, 0x2A), db = 1)
                cpu.setBytes('M', 0, byteArrayOf(0x00, 0x07))
                other.setBytes('D', 0, byteArrayOf(0x00, 0x01), db = 1)
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
                    assertEquals(201, http.put(
                        "/api/v1/config/org.oip.driver-s7/gateway/Device/cpu",
                        deviceBody("cpu", cpu.port),
                    ).status, "cpu create")
                    assertEquals(201, http.put(
                        "/api/v1/config/org.oip.driver-s7/gateway/Device/aux",
                        deviceBody("aux", other.port),
                    ).status, "aux create")
                    val dbTag = http.put(
                        "/api/v1/config/tags/gateway/Tag/db",
                        tagBody("db", "cpu", "DB1,I0", "line/db"),
                    )
                    assertEquals(201, dbTag.status, dbTag.body)
                    val flagTag = http.put(
                        "/api/v1/config/tags/gateway/Tag/flag",
                        tagBody("flag", "cpu", "MW0", "line/flag"),
                    )
                    assertEquals(201, flagTag.status, flagTag.body)
                    val auxTag = http.put(
                        "/api/v1/config/tags/gateway/Tag/auxdb",
                        tagBody("auxdb", "aux", "DB1,I0", "line/aux"),
                    )
                    assertEquals(201, auxTag.status, auxTag.body)

                    val server = g.opcUa ?: error("opc ua server")
                    val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
                    val ada = connect(server.boundPort, endpoints, "ada")
                    val vic = connect(server.boundPort, endpoints, "vic")
                    try {
                        await("both cpus") {
                            val names = children(ada, walk(ada, "Devices"))
                            if ("cpu" in names && "aux" in names) names else null
                        }
                        val word = await("DB1,I0") {
                            val node = runCatching { walk(ada, "Devices", "cpu", "DB1,I0") }.getOrNull() ?: return@await null
                            val read = ada.readValues(0.0, TimestampsToReturn.Both, listOf(node)).single()
                            if (read.statusCode.isGood && (read.value.value as? Number)?.toInt() == 42) node else null
                        }
                        val addresses = children(ada, walk(ada, "Devices", "cpu"))
                        assertTrue(addresses.containsAll(listOf("DB1,I0", "MW0")), "address tree $addresses")
                        val flag = walk(ada, "Devices", "cpu", "MW0")
                        assertEquals(7, (ada.readValues(0.0, TimestampsToReturn.Both, listOf(flag)).single().value.value as Number).toInt())
                        val aux = await("aux DB1,I0") {
                            val node = runCatching { walk(ada, "Devices", "aux", "DB1,I0") }.getOrNull() ?: return@await null
                            val read = ada.readValues(0.0, TimestampsToReturn.Neither, listOf(node)).single()
                            if (read.statusCode.isGood && (read.value.value as? Number)?.toInt() == 1) node else null
                        }

                        val written = ada.writeValues(listOf(word), listOf(DataValue(Variant(21)))).single()
                        assertTrue(written.isGood, "write $written")
                        assertArrayEquals(byteArrayOf(0x00, 21), cpu.getBytes('D', 0, 2, db = 1))
                        assertEquals(21, (ada.readValues(0.0, TimestampsToReturn.Both, listOf(word)).single().value.value as Number).toInt())

                        val refused = vic.writeValues(listOf(word), listOf(DataValue(Variant(4)))).single()
                        assertEquals(StatusCodes.Bad_UserAccessDenied, refused.value)
                        assertArrayEquals(byteArrayOf(0x00, 21), cpu.getBytes('D', 0, 2, db = 1))

                        val seen = CopyOnWriteArrayList<Int>()
                        val sampling = 200.0
                        val sub = OpcUaSubscription(ada, 50.0)
                        sub.create()
                        val item = OpcUaMonitoredItem.newDataItem(word)
                        item.samplingInterval = sampling
                        item.setDataValueListener { _, value ->
                            val number = value.value.value as? Number ?: return@setDataValueListener
                            if (value.statusCode.isGood) seen += number.toInt()
                        }
                        sub.addMonitoredItem(item)
                        sub.synchronizeMonitoredItems()
                        try {
                            await("subscribed 21") { if (seen.any { it == 21 }) true else null }
                            val revised = item.revisedSamplingInterval.orElse(Double.NaN)
                            assertEquals(sampling, revised, 1.0, "revised sampling $revised")
                            val started = System.nanoTime()
                            cpu.setBytes('D', 0, byteArrayOf(0x00, 99), db = 1)
                            await("subscribed 99") { if (seen.any { it == 99 }) true else null }
                            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                            println("S7 device subscription lag=${"%.1f".format(elapsedMs)} ms sampling=$sampling")
                            assertTrue(elapsedMs <= sampling + 2_000.0, "change arrived in $elapsedMs ms")
                        } finally {
                            runCatching { sub.delete() }
                        }

                        assertEquals(204, http.delete("/api/v1/config/org.oip.driver-s7/gateway/Device/aux").status)
                        await("aux removed") {
                            val names = children(ada, walk(ada, "Devices"))
                            if ("aux" !in names && "cpu" in names) names else null
                        }
                        val gone = ada.readValues(0.0, TimestampsToReturn.Neither, listOf(aux)).single()
                        assertEquals(StatusCodes.Bad_NodeIdUnknown, gone.statusCode.value)
                        assertFalse("aux" in children(ada, walk(ada, "Devices")))
                    } finally {
                        runCatching { ada.disconnect() }
                        runCatching { vic.disconnect() }
                    }
                }
            }
        }
    }

    /**
     * An S7 device has the same `Gateway/Device/<name>` system tags and `Diagnostics` folder as
     * the Modbus, Logix, and simulator devices: they follow traffic and comms loss, a disabled
     * device reads Disabled, and deleting the device removes its folder.
     * doc source: FEATURES DEV-004
     */
    @Test fun systemAndDiagnosticTagsFollowTheCpu() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  enabled: false\n")
        S7Simulator().use { cpu ->
            cpu.setBytes('D', 0, byteArrayOf(0x00, 0x2A), db = 1)
            Gateway(tmp, port = 0).use { g ->
                g.start()
                val http = Http(g.port())
                g.loginAdmin(http)
                assertEquals(201, http.put(
                    "/api/v1/config/org.oip.driver-s7/gateway/Device/press",
                    deviceBody("press", cpu.port, description = "Press line"),
                ).status)
                val tag = http.put("/api/v1/config/tags/gateway/Tag/stroke", tagBody("stroke", "press", "DB1,I0", "line/stroke"))
                assertEquals(201, tag.status, tag.body)
                val folder = "[system]Gateway/Device/press"
                val first = await("s7 system tags") {
                    val row = listOf(
                        "$folder/Name", "$folder/Description", "$folder/Enabled", "$folder/Status",
                        "$folder/Diagnostics/Connected", "$folder/Diagnostics/RequestCount",
                        "$folder/Diagnostics/AverageResponseTime", "$folder/Diagnostics/SamplingInterval",
                        "$folder/Diagnostics/PublishInterval", "$folder/Diagnostics/TimeoutCount",
                    ).map { g.tags.read(it) }
                    val ok = row[0].value == "press" && row[1].value == "Press line" && row[2].value == true &&
                        row[3].value == "Connected" && row[4].value == true &&
                        ((row[5].value as? Number)?.toLong() ?: 0L) >= 2L &&
                        ((row[6].value as? Number)?.toDouble() ?: 0.0) > 0.0 &&
                        (row[7].value as? Number)?.toDouble() == 200.0 &&
                        (row[8].value as? Number)?.toDouble() == 200.0 &&
                        (row[9].value as? Number)?.toLong() == 0L
                    if (ok) row else null
                }
                val requests = (first[5].value as Number).toLong()

                cpu.stop()
                await("s7 diagnostics down") {
                    val connected = g.tags.read("$folder/Diagnostics/Connected").value
                    val errors = (g.tags.read("$folder/Diagnostics/ErrorCount").value as? Number)?.toLong() ?: 0L
                    if (connected == false && errors >= 1L && g.tags.read("$folder/Status").value != "Connected") true else null
                }
                cpu.start()
                await("s7 diagnostics back") {
                    val connected = g.tags.read("$folder/Diagnostics/Connected").value
                    val count = (g.tags.read("$folder/Diagnostics/RequestCount").value as? Number)?.toLong() ?: 0L
                    if (connected == true && count > requests) true else null
                }

                assertEquals(200, http.put(
                    "/api/v1/config/org.oip.driver-s7/gateway/Device/press",
                    deviceBody("press", cpu.port, description = "Press line", enabled = false, version = 1),
                ).status)
                await("s7 disabled") {
                    val status = g.tags.read("$folder/Status").value
                    val enabled = g.tags.read("$folder/Enabled").value
                    val connected = g.tags.read("$folder/Diagnostics/Connected").value
                    if (status == "Disabled" && enabled == false && connected == false) true else null
                }

                assertEquals(204, http.delete("/api/v1/config/org.oip.driver-s7/gateway/Device/press").status)
                await("s7 folder removed") {
                    if (g.tags.read("$folder/Status").quality.code == org.oip.tags.QualityCode.BAD_NOT_FOUND) true else null
                }
            }
        }
    }

    @Test fun nameCollisionWithTheSimulatorIsQualified() {
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
        S7Simulator().use { cpu ->
            cpu.setBytes('D', 0, byteArrayOf(0x00, 0x05), db = 1)
            Gateway(tmp, port = 0).use { g ->
                g.start()
                g.createUser("ada", setOf(DefaultRoles.OPERATOR))
                val http = Http(g.port())
                g.loginAdmin(http)
                assertEquals(201, http.put(
                    "/api/v1/config/org.oip.driver-simulator/gateway/Device/plant",
                    """
                    {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"plant","module":"org.oip.driver-simulator","version":0},
                     "spec":{"name":"plant","repeatProgram":false,"baseRateMs":60000,
                       "instructions":[{"timeInterval":0,"browsePath":"Level","valueSource":"3","dataType":"Int32"}]}}
                    """.trimIndent(),
                ).status)
                assertEquals(201, http.put(
                    "/api/v1/config/org.oip.driver-s7/gateway/Device/plant",
                    deviceBody("plant", cpu.port),
                ).status)
                val wordTag = http.put(
                    "/api/v1/config/tags/gateway/Tag/word",
                    tagBody("word", "plant", "DB1,I0", "line/word"),
                )
                assertEquals(201, wordTag.status, wordTag.body)
                val server = g.opcUa ?: error("opc ua server")
                val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
                val ada = connect(server.boundPort, endpoints, "ada")
                try {
                    val word = await("qualified S7 address") {
                        val names = children(ada, walk(ada, "Devices"))
                        if ("plant (s7)" !in names || "plant (simulator)" !in names) return@await null
                        val node = runCatching { walk(ada, "Devices", "plant (s7)", "DB1,I0") }.getOrNull() ?: return@await null
                        val read = ada.readValues(0.0, TimestampsToReturn.Neither, listOf(node)).single()
                        if (read.statusCode.isGood && (read.value.value as? Number)?.toInt() == 5) node else null
                    }
                    val identifier = word.identifier as String
                    assertTrue(identifier.contains("s7\u001fplant/"), identifier)
                    val written = ada.writeValues(listOf(word), listOf(DataValue(Variant(9)))).single()
                    assertTrue(written.isGood, "qualified write $written")
                    assertArrayEquals(byteArrayOf(0x00, 9), cpu.getBytes('D', 0, 2, db = 1))
                } finally {
                    runCatching { ada.disconnect() }
                }
            }
        }
    }

    private fun deviceBody(
        name: String,
        port: Int,
        description: String = "",
        enabled: Boolean = true,
        version: Int = 0,
    ) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"$name","module":"org.oip.driver-s7","version":$version},
         "spec":{"name":"$name","description":"$description","enabled":$enabled,"host":"127.0.0.1","port":$port,
           "model":"S7_300","rack":0,"slot":2,"timeoutMs":500,
           "backoffInitialMs":40,"backoffMaxMs":200,"defaultScanPeriodMs":200}}
    """.trimIndent()

    private fun tagBody(name: String, server: String, item: String, path: String) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"$path","type":"opc","dataType":"Int32","opcServer":"$server",
           "opcItemPath":"$item","scanPeriodMillis":200}}
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
            .setApplicationName(LocalizedText.english("oip-s7-catalog"))
            .setApplicationUri("urn:oip:test:s7-catalog")
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
