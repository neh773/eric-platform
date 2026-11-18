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
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.modbus.ModbusDriverModule
import org.oip.opcua.server.DeviceNodeIds
import org.oip.security.DefaultRoles
import java.nio.file.Path
import kotlin.io.path.writeText
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Monitored items on a device node are sampled by the driver's poll, not by a second reader.
 *
 * A subscription reports a value the simulator changed within one sampling interval plus [SLACK_MS].
 * Two subscriptions on one address produce one device poll. Deleting the last item stops that poll.
 * An OPC tag already polling the address is the poll that gets joined. Comms loss is bad quality,
 * and the next good sample is recovery.
 *
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class DeviceSamplingTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun subscriptionReceivesASimulatorChangeWithinOneSamplingInterval() {
        TinyModbusSlave().use { slave ->
            slave.holding[0] = 11
            withDevice(tmp.resolve("change"), slave.port) { gw, device ->
                val client = connect(gw)
                try {
                    val node = deviceNode(client, "HR1")
                    val seen = CopyOnWriteArrayList<DataValue>()
                    val sub = subscribe(client, node, SAMPLING_MS, seen::add)
                    try {
                        await("initial good") { seen.any { good(it, 11) } }
                        val revised = sub.item.revisedSamplingInterval.orElse(Double.NaN)
                        assertEquals(SAMPLING_MS, revised, 1.0, "revised sampling $revised")
                        val started = System.nanoTime()
                        slave.holding[0] = 77
                        await("changed value") { seen.any { good(it, 77) } }
                        val elapsedMs = elapsedMs(started)
                        println("DEV-004 device subscription lag=$elapsedMs ms sampling=$SAMPLING_MS slack=$SLACK_MS")
                        assertTrue(elapsedMs <= SAMPLING_MS + SLACK_MS, "change arrived in $elapsedMs ms")
                    } finally {
                        sub.close()
                    }
                } finally {
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    @Test
    fun twoSubscriptionsOnTheSameAddressCauseOnePollAndDeletingStopsIt() {
        TinyModbusSlave().use { slave ->
            slave.holding[0] = 5
            withDevice(tmp.resolve("share"), slave.port) { gw, device ->
                val client = connect(gw)
                try {
                    val node = deviceNode(client, "HR1")
                    val first = CopyOnWriteArrayList<DataValue>()
                    val second = CopyOnWriteArrayList<DataValue>()
                    val subA = subscribe(client, node, SAMPLING_MS, first::add)
                    val subB = subscribe(client, node, SAMPLING_MS, second::add)
                    try {
                        await("both subscribed") { first.any { good(it, 5) } && second.any { good(it, 5) } }
                        assertEquals(SAMPLING_MS, subB.item.revisedSamplingInterval.orElse(Double.NaN), 1.0)
                        val before = device.readRequestCount
                        Thread.sleep(WINDOW_MS)
                        val delta = device.readRequestCount - before
                        // One 200 ms scan over 2 s is about 10 PDUs. Two polls would be about 20.
                        println("DEV-004 two subscriptions polls=$delta over ${WINDOW_MS}ms (one scan is about 10)")
                        assertTrue(delta in 6..15, "two subscriptions produced $delta polls in ${WINDOW_MS}ms")
                        subA.close()
                        Thread.sleep(300)
                        val mid = device.readRequestCount
                        Thread.sleep(1_000)
                        val remaining = device.readRequestCount - mid
                        assertTrue(remaining in 3..8, "the other subscription stopped the poll: $remaining")
                        subB.close()
                        Thread.sleep(400)
                        val stopped = device.readRequestCount
                        Thread.sleep(1_000)
                        val after = device.readRequestCount - stopped
                        assertTrue(after <= 1, "deleting the monitored item left the poll running: $after")
                    } finally {
                        subA.close()
                        subB.close()
                    }
                } finally {
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    @Test
    fun subscriptionJoinsTheTagAlreadyPollingTheAddress() {
        TinyModbusSlave().use { slave ->
            slave.holding[0] = 3
            withDevice(tmp.resolve("tag"), slave.port) { gw, device ->
                val http = Http(gw.port())
                gw.loginAdmin(http)
                val tag = http.put(
                    "/api/v1/config/tags/gateway/Tag/hr1",
                    tagBody("hr1", "mb", "1.HR1", TAG_PERIOD_MS),
                )
                assertEquals(201, tag.status, tag.body)
                await("tag good") {
                    val row = readTag(http, "[default]bench/hr1")
                    row["quality"] == "Good" && (row["value"] as Number).toInt() == 3
                }
                val client = connect(gw)
                try {
                    val node = deviceNode(client, "HR1")
                    val seen = CopyOnWriteArrayList<DataValue>()
                    // Faster than the tag. The revised interval is the tag's scan, and the PDU rate stays there.
                    val sub = subscribe(client, node, 50.0, seen::add)
                    try {
                        await("joined") { seen.any { good(it, 3) } }
                        val revised = sub.item.revisedSamplingInterval.orElse(Double.NaN)
                        assertEquals(TAG_PERIOD_MS.toDouble(), revised, 1.0, "revised $revised")
                        val before = device.readRequestCount
                        Thread.sleep(WINDOW_MS)
                        val delta = device.readRequestCount - before
                        // 400 ms over 2 s is about 5 PDUs. A second 50 ms poll would add about 40.
                        println("DEV-004 tag plus subscription polls=$delta over ${WINDOW_MS}ms (tag scan is ${TAG_PERIOD_MS}ms)")
                        assertTrue(delta in 3..9, "tag and subscription produced $delta polls")
                    } finally {
                        sub.close()
                    }
                } finally {
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    @Test
    fun commsLossReportsBadQualityThenRecovers() {
        TinyModbusSlave().use { slave ->
            slave.holding[0] = 42
            withDevice(tmp.resolve("loss"), slave.port) { gw, _ ->
                val client = connect(gw)
                try {
                    val node = deviceNode(client, "HR1")
                    val seen = CopyOnWriteArrayList<DataValue>()
                    val sub = subscribe(client, node, SAMPLING_MS, seen::add)
                    try {
                        await("initial") { seen.any { good(it, 42) } }
                        slave.cut()
                        await("bad quality") {
                            seen.any { it.statusCode.value == StatusCodes.Bad_NotConnected && number(it) == 42 }
                        }
                        slave.holding[0] = 99
                        slave.restore()
                        await("recovered") { seen.any { good(it, 99) } }
                    } finally {
                        sub.close()
                    }
                } finally {
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    @Test
    fun thousandMonitoredItemsShowNoLag() {
        val count = 1_000
        TinyModbusSlave(count).use { slave ->
            for (i in 0 until count) slave.holding[i] = 1
            withDevice(tmp.resolve("bench"), slave.port, concurrent = 4) { gw, _ ->
                val client = connect(gw, requestTimeout = 60_000)
                try {
                    val ns = deviceNode(client, "HR1").namespaceIndex
                    val sub = OpcUaSubscription(client, PUBLISHING_MS)
                    sub.create()
                    val initial = AtomicIntegerArray(count)
                    val changedAt = AtomicLongArray(count)
                    for (i in 0 until count) {
                        val item = OpcUaMonitoredItem.newDataItem(NodeId(ns, DeviceNodeIds.item("mb", "HR${i + 1}")))
                        item.samplingInterval = SAMPLING_MS
                        val index = i
                        item.setDataValueListener { _, value ->
                            val number = number(value) ?: return@setDataValueListener
                            if (number == 1) initial.set(index, 1)
                            if (number == 2 && value.statusCode.isGood && changedAt.compareAndSet(index, 0L, System.nanoTime())) {
                                // recorded
                            }
                        }
                        sub.addMonitoredItem(item)
                    }
                    sub.synchronizeMonitoredItems()
                    try {
                        await("initial values", 20_000) { (0 until count).all { initial.get(it) == 1 } }
                        val started = System.nanoTime()
                        for (i in 0 until count) slave.holding[i] = 2
                        await("all changed", (SAMPLING_MS + SLACK_MS).toLong() + 2_000) {
                            (0 until count).all { changedAt.get(it) != 0L }
                        }
                        var earliest = Long.MAX_VALUE
                        var latest = 0L
                        for (i in 0 until count) {
                            val at = changedAt.get(i)
                            if (at < earliest) earliest = at
                            if (at > latest) latest = at
                        }
                        val elapsedMs = elapsedMs(started)
                        val spreadMs = (latest - earliest) / 1_000_000.0
                        println(
                            "DEV-004 1000 monitored items elapsed=${"%.1f".format(elapsedMs)} ms " +
                                "spread=${"%.1f".format(spreadMs)} ms sampling=$SAMPLING_MS slack=$SLACK_MS",
                        )
                        assertTrue(elapsedMs <= SAMPLING_MS + SLACK_MS, "last item lagged $elapsedMs ms")
                        assertTrue(spreadMs <= SAMPLING_MS + 400.0, "delivery spread $spreadMs ms")
                    } finally {
                        runCatching { sub.delete() }
                    }
                } finally {
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    private fun withDevice(dir: Path, port: Int, concurrent: Int = 1, body: (Gateway, org.oip.modbus.ModbusDevice) -> Unit) {
        val gw = gateway(dir)
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.use {
            it.start()
            val http = Http(it.port())
            it.loginAdmin(http)
            val put = http.put(
                "/api/v1/config/org.oip.driver-modbus/gateway/Device/mb",
                deviceBody(port, concurrent),
            )
            assertEquals(201, put.status, put.body)
            val module = it.host.instance(ModbusDriverModule.MODULE_ID) as ModbusDriverModule
            val device = module.device("mb") ?: error("device was not started")
            await("connected") { device.connectionStatus().connected }
            body(it, device)
        }
    }

    private fun subscribe(
        client: OpcUaClient,
        node: NodeId,
        samplingMs: Double,
        onValue: (DataValue) -> Unit,
    ): Live {
        val sub = OpcUaSubscription(client, PUBLISHING_MS)
        sub.create()
        val item = OpcUaMonitoredItem.newDataItem(node)
        item.samplingInterval = samplingMs
        item.setDataValueListener { _, value -> onValue(value) }
        sub.addMonitoredItem(item)
        sub.synchronizeMonitoredItems()
        return Live(sub, item)
    }

    private class Live(val subscription: OpcUaSubscription, val item: OpcUaMonitoredItem) : AutoCloseable {
        override fun close() {
            runCatching { subscription.delete() }
        }
    }

    private fun good(value: DataValue, expected: Int): Boolean =
        value.statusCode.isGood && number(value) == expected

    private fun number(value: DataValue): Int? = (value.value?.value as? Number)?.toInt()

    private fun readTag(http: Http, path: String): Map<*, *> {
        val res = http.post("/api/v1/tags/read", """{"paths":["$path"]}""")
        assertEquals(200, res.status, res.body)
        @Suppress("UNCHECKED_CAST")
        return (res.json["values"] as List<Map<*, *>>).single()
    }

    private fun deviceNode(client: OpcUaClient, address: String): NodeId {
        val root = await("Devices/$address") {
            runCatching { walk(client, "Devices", "mb") }.getOrNull()
        }
        val index = root.namespaceIndex
        return NodeId(index, DeviceNodeIds.item("mb", address))
    }

    private fun <T> await(label: String, timeoutMs: Long = 8_000, probe: () -> T?): T {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        var last = ""
        while (System.nanoTime() < end) {
            try {
                val value = probe() ?: continue
                if (value is Boolean && !value) continue
                return value
            } catch (e: Exception) {
                last = e.message ?: e.toString()
            }
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $label $last")
    }

    private fun elapsedMs(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0

    private fun gateway(dir: Path): Gateway {
        dir.toFile().mkdirs()
        dir.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: true
              bindAddress: 127.0.0.1
              port: 0
              hostname: 127.0.0.1
            """.trimIndent() + "\n",
        )
        return Gateway(dir, port = 0)
    }

    private fun deviceBody(port: Int, concurrent: Int) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"mb","module":"org.oip.driver-modbus","version":0},
         "spec":{"name":"mb","host":"127.0.0.1","port":$port,"mode":"TCP","unitId":1,"zeroBasedAddressing":false,
           "timeoutMs":400,"reconnectAfterConsecutiveTimeouts":1,"maxRetryCount":0,
           "backoffInitialMs":40,"backoffMaxMs":200,"backoffMultiplier":2,"defaultScanPeriodMs":5000,
           "maxHoldingRegistersPerRead":125,"spanGaps":true,"concurrentRequests":$concurrent}}
    """.trimIndent()

    private fun tagBody(name: String, server: String, item: String, period: Int) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"bench/$name","type":"opc","dataType":"Int32","opcServer":"$server","opcItemPath":"$item","scanPeriodMillis":$period}}
    """.trimIndent()

    private fun connect(gw: Gateway, requestTimeout: Long = 15_000): OpcUaClient {
        val server = gw.opcUa ?: error("opc ua server is not running")
        val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
        val selected = endpoints.first { ep ->
            ep.securityPolicyUri == SecurityPolicy.None.uri &&
                ep.endpointUrl.contains("/oip") &&
                !ep.endpointUrl.contains("discovery")
        }
        val config = OpcUaClientConfig.builder()
            .setEndpoint(EndpointUtil.updateUrl(selected, "127.0.0.1", server.boundPort))
            .setApplicationName(LocalizedText.english("oip-device-sampling"))
            .setApplicationUri("urn:oip:test:device-sampling")
            .setProductUri("urn:oip:test")
            .setRequestTimeout(uint(requestTimeout))
            .setSessionTimeout(uint(60_000))
            .setIdentityProvider(UsernameProvider("ada", TEST_PASSWORD))
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

    private fun walk(client: OpcUaClient, vararg names: String): NodeId {
        var node = NodeIds.ObjectsFolder
        for (name in names) {
            val refs = client.addressSpace.browse(node)
            val match = refs.firstOrNull { it.browseName.name.equals(name, ignoreCase = true) }
                ?: error("no '$name' under $node; saw ${refs.map { it.browseName.name }}")
            node = match.nodeId.toNodeId(client.namespaceTable).orElseThrow()
        }
        return node
    }

    private companion object {
        const val SAMPLING_MS = 200.0
        const val PUBLISHING_MS = 50.0
        /** Room for one publishing interval and a scan that started just before the write. */
        const val SLACK_MS = 800.0
        const val WINDOW_MS = 2_000L
        const val TAG_PERIOD_MS = 400
    }
}
