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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.testinfra.SimulatorStack
import org.oip.testinfra.Waveform
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * P2-19 / DEV-004 against the compose Modbus and OPC UA simulators.
 *
 * Browse lists each configured device. A read and a write through the gateway OPC UA server's
 * `Devices` folder reach the simulator. A session without `tags.write` is refused with
 * `Bad_UserAccessDenied` and the simulator value is unchanged. A device added or removed at
 * runtime appears and disappears without restarting the server. A warmed read of a Modbus
 * holding register through that folder stays under 20 ms at p95.
 *
 */
@Tag("simulator")
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class DeviceCatalogSimulatorTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun devicesAreBrowsableReadableAndWritableThroughTheGatewayServer() {
        SimulatorStack.fromEnvironment().use { stack ->
            // HR3 is the offset register when addressing is one-based (register 2). 42.0 encodes as 4200.
            stack.modbusControl().set(mode = Waveform.Mode.CONSTANT, amplitude = 0.0, offset = 42.0, periodMs = 10_000, frozen = true, nowMs = 0)
            stack.opcUaControl().set(mode = Waveform.Mode.CONSTANT, amplitude = 0.0, offset = 42.0, periodMs = 10_000, frozen = true, nowMs = 0)
            val gw = gateway()
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("vic", setOf(DefaultRoles.VIEWER))
            gw.use {
                it.start()
                val http = Http(it.port())
                it.loginAdmin(http)
                val host = stack.endpoints.host
                put(http, "/api/v1/config/org.oip.driver-modbus/gateway/Device/mb", deviceBody("mb", host, stack.endpoints.modbusProxy))
                put(
                    http,
                    "/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/opc",
                    connectionBody(host, stack.endpoints.opcUaProxy),
                )
                val server = it.opcUa!!
                val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
                val ada = connect(server.boundPort, endpoints, "ada")
                val vic = connect(server.boundPort, endpoints, "vic")
                try {
                    val devices = await("devices mb and opc") {
                        val names = children(ada, walk(ada, "Devices"))
                        if ("mb" in names && "opc" in names) names else null
                    }
                    assertTrue("mb" in devices && "opc" in devices, "browse listed $devices")

                    val offset = await("Sim.Offset") {
                        val node = runCatching { walk(ada, "Devices", "opc", "Sim", "Offset") }.getOrNull() ?: return@await null
                        val read = ada.readValues(0.0, TimestampsToReturn.Both, listOf(node)).single()
                        if (read.statusCode.isGood && (read.value.value as? Number)?.toDouble() == 42.0) node else null
                    }
                    val writtenOpc = ada.writeValues(listOf(offset), listOf(DataValue(Variant(7.5)))).single()
                    assertTrue(writtenOpc.isGood, "opc write $writtenOpc")
                    assertEquals(7.5, stack.opcUaControl().get().offset, 0.0)
                    val opcAfter = ada.readValues(0.0, TimestampsToReturn.Both, listOf(offset)).single()
                    assertEquals(7.5, (opcAfter.value.value as Number).toDouble(), 0.0)

                    val holding = await("HR3") {
                        val node = runCatching { walk(ada, "Devices", "mb", "Holding registers", "HR3") }.getOrNull()
                            ?: return@await null
                        val read = ada.readValues(0.0, TimestampsToReturn.Both, listOf(node)).single()
                        if (read.statusCode.isGood && (read.value.value as? Number)?.toInt() == 4200) node else null
                    }
                    val writtenMb = ada.writeValues(listOf(holding), listOf(DataValue(Variant(2500)))).single()
                    assertTrue(writtenMb.isGood, "modbus write $writtenMb")
                    assertEquals(25.0, stack.modbusControl().get().offset, 0.0)
                    val mbAfter = ada.readValues(0.0, TimestampsToReturn.Both, listOf(holding)).single()
                    assertEquals(2500, (mbAfter.value.value as Number).toInt())

                    val refusedOpc = vic.writeValues(listOf(offset), listOf(DataValue(Variant(1.0)))).single()
                    assertEquals(StatusCodes.Bad_UserAccessDenied, refusedOpc.value)
                    assertEquals(7.5, stack.opcUaControl().get().offset, 0.0)
                    val refusedMb = vic.writeValues(listOf(holding), listOf(DataValue(Variant(100)))).single()
                    assertEquals(StatusCodes.Bad_UserAccessDenied, refusedMb.value)
                    assertEquals(25.0, stack.modbusControl().get().offset, 0.0)
                    assertEquals(2500, (ada.readValues(0.0, TimestampsToReturn.Neither, listOf(holding)).single().value.value as Number).toInt())

                    put(http, "/api/v1/config/org.oip.driver-modbus/gateway/Device/extra", deviceBody("extra", host, stack.endpoints.modbusProxy))
                    val withExtra = children(ada, walk(ada, "Devices"))
                    assertTrue("extra" in withExtra, "added device missing from $withExtra")
                    val extra = walk(ada, "Devices", "extra")
                    assertEquals(204, http.delete("/api/v1/config/org.oip.driver-modbus/gateway/Device/extra").status)
                    val without = children(ada, walk(ada, "Devices"))
                    assertFalse("extra" in without, "removed device still listed in $without")
                    assertTrue("mb" in without && "opc" in without, "browse listed $without")
                    val gone = ada.readValues(0.0, TimestampsToReturn.Neither, listOf(extra)).single()
                    assertEquals(StatusCodes.Bad_NodeIdUnknown, gone.statusCode.value)

                    repeat(WARMUP) {
                        val sample = ada.readValues(0.0, TimestampsToReturn.Neither, listOf(holding)).single()
                        assertTrue(sample.statusCode.isGood, "warmup ${sample.statusCode}")
                    }
                    val nanos = LongArray(SAMPLES)
                    for (i in nanos.indices) {
                        val started = System.nanoTime()
                        val sample = ada.readValues(0.0, TimestampsToReturn.Neither, listOf(holding)).single()
                        nanos[i] = System.nanoTime() - started
                        assertTrue(sample.statusCode.isGood, "sample $i ${sample.statusCode}")
                        assertEquals(2500, (sample.value.value as Number).toInt())
                    }
                    val p95 = percentile(nanos, 0.95)
                    println("DEV-004 Devices folder read p95=${"%.2f".format(p95 / 1_000_000.0)} ms over $SAMPLES samples")
                    assertTrue(p95 < 20_000_000L, "p95 ${p95 / 1_000_000.0} ms is not under 20 ms")
                } finally {
                    runCatching { ada.disconnect() }
                    runCatching { vic.disconnect() }
                }
            }
        }
    }

    /**
     * A monitored item on the Modbus device is sampled by the driver. The value written on the
     * simulator shows up within one sampling interval plus slack, not on the device's slow default scan.
     */
    @Test
    fun subscriptionReceivesASimulatorChangeWithinOneSamplingInterval() {
        SimulatorStack.fromEnvironment().use { stack ->
            stack.modbusControl().set(mode = Waveform.Mode.CONSTANT, amplitude = 0.0, offset = 42.0, periodMs = 10_000, frozen = true, nowMs = 0)
            val gw = gateway()
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.use {
                it.start()
                val http = Http(it.port())
                it.loginAdmin(http)
                val host = stack.endpoints.host
                put(
                    http,
                    "/api/v1/config/org.oip.driver-modbus/gateway/Device/mb",
                    deviceBody("mb", host, stack.endpoints.modbusProxy).replace("\"defaultScanPeriodMs\":200", "\"defaultScanPeriodMs\":5000"),
                )
                val server = it.opcUa!!
                val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
                val ada = connect(server.boundPort, endpoints, "ada")
                try {
                    val holding = await("HR3") {
                        val node = runCatching { walk(ada, "Devices", "mb", "Holding registers", "HR3") }.getOrNull()
                            ?: return@await null
                        val read = ada.readValues(0.0, TimestampsToReturn.Both, listOf(node)).single()
                        if (read.statusCode.isGood && (read.value.value as? Number)?.toInt() == 4200) node else null
                    }
                    val seen = CopyOnWriteArrayList<org.eclipse.milo.opcua.stack.core.types.builtin.DataValue>()
                    val sampling = 200.0
                    val sub = OpcUaSubscription(ada, 50.0)
                    sub.create()
                    val item = OpcUaMonitoredItem.newDataItem(holding)
                    item.samplingInterval = sampling
                    item.setDataValueListener { _, value -> seen += value }
                    sub.addMonitoredItem(item)
                    sub.synchronizeMonitoredItems()
                    try {
                        await("subscribed 4200") {
                            if (seen.any { it.statusCode.isGood && (it.value.value as? Number)?.toInt() == 4200 }) true else null
                        }
                        val revised = item.revisedSamplingInterval.orElse(Double.NaN)
                        assertEquals(sampling, revised, 1.0, "revised sampling $revised")
                        val started = System.nanoTime()
                        stack.modbusControl().set(mode = Waveform.Mode.CONSTANT, amplitude = 0.0, offset = 9.0, periodMs = 10_000, frozen = true, nowMs = 0)
                        await("subscribed 900") {
                            if (seen.any { it.statusCode.isGood && (it.value.value as? Number)?.toInt() == 900 }) true else null
                        }
                        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                        println("DEV-004 simulator subscription lag=${"%.1f".format(elapsedMs)} ms sampling=$sampling slack=1500")
                        assertTrue(elapsedMs <= sampling + 1_500.0, "simulator change arrived in $elapsedMs ms")
                    } finally {
                        runCatching { sub.delete() }
                    }
                } finally {
                    runCatching { ada.disconnect() }
                }
            }
        }
    }

    private fun <T> await(label: String, probe: () -> T?): T {
        val end = System.nanoTime() + 30_000_000_000L
        var last = ""
        while (System.nanoTime() < end) {
            try {
                val value = probe()
                if (value != null) return value
            } catch (e: Exception) {
                last = e.message ?: e.toString()
            }
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $label $last")
    }

    private fun gateway(): Gateway {
        tmp.resolve("gateway.yaml").toFile().writeText(
            """
            profile: standard
            opcua:
              enabled: true
              bindAddress: 127.0.0.1
              port: 0
              hostname: 127.0.0.1
            """.trimIndent() + "\n",
        )
        return Gateway(tmp, port = 0)
    }

    private fun put(http: Http, path: String, body: String) {
        val res = http.put(path, body)
        assertEquals(201, res.status, "$path ${res.body}")
    }

    private fun deviceBody(name: String, host: String, port: Int) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"$name","module":"org.oip.driver-modbus","version":0},
         "spec":{"name":"$name","host":"$host","port":$port,"mode":"TCP","unitId":1,"zeroBasedAddressing":false,
           "timeoutMs":2000,"reconnectAfterConsecutiveTimeouts":1,"maxRetryCount":0,
           "backoffInitialMs":40,"backoffMaxMs":200,"backoffMultiplier":2,"defaultScanPeriodMs":200,"spanGaps":true}}
    """.trimIndent()

    private fun connectionBody(host: String, port: Int) = """
        {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"opc","module":"org.oip.driver-opcua-client","version":0},
         "spec":{"name":"opc","endpointUrl":"opc.tcp://$host:$port/oip/sim","connectHost":"$host","connectPort":$port,
           "securityPolicy":"NONE","securityMode":"NONE","trustAllServerCertificates":false,
           "requestTimeoutMs":2000,"sessionTimeoutMs":60000,
           "keepAliveIntervalMs":2000,"keepAliveTimeoutMs":2000,"keepAliveFailuresAllowed":1}}
    """.trimIndent()

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
            .setApplicationName(LocalizedText.english("oip-device-catalog-sim"))
            .setApplicationUri("urn:oip:test:device-catalog-sim")
            .setProductUri("urn:oip:test")
            .setRequestTimeout(uint(15_000))
            .setSessionTimeout(uint(60_000))
            .setIdentityProvider(UsernameProvider(username, TEST_PASSWORD))
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

    private fun percentile(samples: LongArray, p: Double): Long {
        val sorted = samples.sortedArray()
        val index = ceil(p * sorted.size).toInt() - 1
        return sorted[index.coerceIn(0, sorted.lastIndex)]
    }

    private companion object {
        const val WARMUP = 20
        const val SAMPLES = 200
    }
}
