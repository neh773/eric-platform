package org.oip.gateway

import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.modbus.ModbusDriverModule
import org.oip.opcua.OpcUaClientModule
import org.oip.tags.Level
import org.oip.tags.QualifiedValue
import org.oip.tags.QualityCode
import org.oip.tags.TagEngine
import org.oip.testinfra.SimControl
import org.oip.testinfra.SimulatorStack
import org.oip.testinfra.Waveform
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Comms loss against the compose simulators (P2-08).
 *
 * Three faults, on Modbus TCP and on the OPC UA client: the simulator process is stopped,
 * Toxiproxy cuts the listener, and a timeout toxic black-holes the port. The other protocol
 * stays good. The engine clock is frozen while the fault is applied, then advanced by one
 * scan period plus [COMMS_TIMEOUT_MS]. That is the deadline (SPEC §5). The device and session
 * timeouts are longer than the wall time this spends after the fault, so a pass is not the
 * socket timeout. Recovery does not restart the gateway.
 *
 * MQTT comms loss is covered by CommsLossQualityTest. This compose run stays on Modbus and OPC UA.
 */
@Tag("simulator")
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class SimulatorCommsLossTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun eachFaultMarksEveryTagOnThatLinkAndRecoversWithoutRestart() {
        SimulatorStack.fromEnvironment().use { stack ->
            freeze(stack.modbusControl(), 42.0)
            freeze(stack.opcUaControl(), 42.0)
            val namespace = namespaceIndex(stack)
            val clock = MutableClock()
            gateway(clock).use { g ->
                val http = Http(g.port())
                g.loginAdmin(http)
                val host = stack.endpoints.host
                put(http, "/api/v1/config/org.oip.driver-modbus/gateway/Device/mb", deviceBody(host, stack.endpoints.modbusProxy))
                put(
                    http,
                    "/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/opc",
                    connectionBody(host, stack.endpoints.opcUaProxy),
                )
                put(http, "/api/v1/config/tags/gateway/Tag/mb-value", tagBody("mb-value", "mb", "HR5", "Int32", "badNotConnected"))
                put(http, "/api/v1/config/tags/gateway/Tag/mb-mode", tagBody("mb-mode", "mb", "HR1", "Int32", "uncertainLastKnown"))
                put(http, "/api/v1/config/tags/gateway/Tag/opc-value", tagBody("opc-value", "opc", "ns=$namespace;s=Sim.Value", "Double", "badNotConnected"))
                put(http, "/api/v1/config/tags/gateway/Tag/opc-offset", tagBody("opc-offset", "opc", "ns=$namespace;s=Sim.Offset", "Double", "uncertainLastKnown"))
                val modbusModule = g.host.instance(ModbusDriverModule.MODULE_ID) as ModbusDriverModule
                val opcModule = g.host.instance(OpcUaClientModule.MODULE_ID) as OpcUaClientModule
                val modbusDevice = modbusModule.device("mb")
                val opcConnection = opcModule.connection("opc")
                assertNotNull(modbusDevice)
                assertNotNull(opcConnection)
                val modbus = listOf(
                    Tag("plant/mb-value", QualityCode.BAD_NOT_CONNECTED),
                    Tag("plant/mb-mode", QualityCode.UNCERTAIN_LAST_KNOWN_VALUE),
                )
                val opc = listOf(
                    Tag("plant/opc-value", QualityCode.BAD_NOT_CONNECTED),
                    Tag("plant/opc-offset", QualityCode.UNCERTAIN_LAST_KNOWN_VALUE),
                )
                awaitGood(g.tags, modbus + opc, 30_000, "startup")
                val faults = listOf(
                    unitFault("port blocked", Link.MODBUS, strict = true, apply = { stack.toxiproxy().blackHole("modbus") }, clear = { stack.toxiproxy().clearBlackHole("modbus") }),
                    unitFault("network cut", Link.MODBUS, strict = false, apply = { stack.cut(org.oip.testinfra.SimService.MODBUS) }, clear = { stack.restore(org.oip.testinfra.SimService.MODBUS) }),
                    Fault(
                        "simulator stopped",
                        Link.MODBUS,
                        strict = false,
                        apply = { stack.stopComposeService("modbus", stack.endpoints.modbusControl) },
                        clear = { id ->
                            stack.startComposeService(id)
                            awaitHealthy(stack.modbusControl())
                            freeze(stack.modbusControl(), 42.0)
                        },
                    ),
                    unitFault("port blocked", Link.OPC, strict = true, apply = { stack.toxiproxy().blackHole("opcua") }, clear = { stack.toxiproxy().clearBlackHole("opcua") }),
                    unitFault("network cut", Link.OPC, strict = false, apply = { stack.cut(org.oip.testinfra.SimService.OPCUA) }, clear = { stack.restore(org.oip.testinfra.SimService.OPCUA) }),
                    Fault(
                        "simulator stopped",
                        Link.OPC,
                        strict = false,
                        apply = { stack.stopComposeService("opcua", stack.endpoints.opcUaControl) },
                        clear = { id ->
                            stack.startComposeService(id)
                            awaitHealthy(stack.opcUaControl())
                            freeze(stack.opcUaControl(), 42.0)
                        },
                    ),
                )
                for (fault in faults) {
                    val affected = if (fault.link == Link.MODBUS) modbus else opc
                    val other = if (fault.link == Link.MODBUS) opc else modbus
                    val otherControl = if (fault.link == Link.MODBUS) stack.opcUaControl() else stack.modbusControl()
                    runFault(g.tags, clock, fault, affected, other, otherControl)
                }
                assertSame(modbusDevice, modbusModule.device("mb"))
                assertSame(opcConnection, opcModule.connection("opc"))
            }
        }
    }

    private fun runFault(
        engine: TagEngine,
        clock: MutableClock,
        fault: Fault,
        affected: List<Tag>,
        other: List<Tag>,
        otherControl: SimControl,
    ) {
        val label = "${fault.link} ${fault.name}"
        awaitGood(engine, affected + other, 20_000, "before $label")
        val t0 = clock.millis()
        val token = fault.apply()
        try {
            // A response already on the wire may still arrive. The clock stays put, so this
            // wait is not the deadline. The device timeout is 8 s.
            Thread.sleep(150)
            for (tag in affected) {
                val qv = engine.read(tag.path)
                if (qv.quality.code == QualityCode.GOOD) tag.note(qv)
            }
            val deadline = t0 + SCAN_MS + COMMS_TIMEOUT_MS
            clock.advance(Duration.ofMillis(deadline - clock.millis() - 1))
            val poked = otherControl.get().offset + 7.0
            otherControl.set(offset = poked, frozen = true)
            awaitHealthySample(engine, other, 5_000, label)
            // One more publishing interval so a tag whose value did not change still gets a poll
            // or a subscription keep-alive at this clock, before the deadline.
            Thread.sleep(SCAN_MS + 400)
            engine.checkComms()
            if (fault.strict) {
                for (tag in affected) {
                    val qv = engine.read(tag.path)
                    assertEquals(QualityCode.GOOD, qv.quality.code, "$label was bad before the deadline (${qv.quality})")
                }
            }
            clock.advance(Duration.ofMillis(1))
            engine.checkComms()
            val deadlineAt = Instant.ofEpochMilli(deadline)
            for (tag in affected) tag.assertLost(engine.read(tag.path), deadlineAt, label)
            for (tag in other) {
                val qv = engine.read(tag.path)
                assertEquals(QualityCode.GOOD, qv.quality.code, "$label took ${tag.path} with it")
            }
        } finally {
            fault.clear(token)
        }
        awaitGood(engine, affected + other, 45_000, "after $label")
    }

    private fun gateway(clock: MutableClock): Gateway {
        Files.writeString(tmp.resolve("gateway.yaml"), "profile: standard\nopcua:\n  enabled: false\n")
        return Gateway(tmp, port = 0, clock = clock).also { it.start() }
    }

    private fun put(http: Http, path: String, body: String) {
        val res = http.put(path, body)
        assertEquals(201, res.status, "$path ${res.body}")
    }

    private fun deviceBody(host: String, port: Int) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"mb","module":"org.oip.driver-modbus","version":0},
         "spec":{"name":"mb","host":"$host","port":$port,"mode":"TCP","unitId":1,"zeroBasedAddressing":false,
           "timeoutMs":$SOCKET_TIMEOUT_MS,"reconnectAfterConsecutiveTimeouts":1,"maxRetryCount":0,
           "backoffInitialMs":40,"backoffMaxMs":200,"backoffMultiplier":2,"defaultScanPeriodMs":$SCAN_MS,"spanGaps":true}}
    """.trimIndent()

    private fun connectionBody(host: String, port: Int) = """
        {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"opc","module":"org.oip.driver-opcua-client","version":0},
         "spec":{"name":"opc","endpointUrl":"opc.tcp://$host:$port/oip/sim","connectHost":"$host","connectPort":$port,
           "securityPolicy":"NONE","securityMode":"NONE","trustAllServerCertificates":false,
           "requestTimeoutMs":$SOCKET_TIMEOUT_MS,"sessionTimeoutMs":60000,
           "keepAliveIntervalMs":$SOCKET_TIMEOUT_MS,"keepAliveTimeoutMs":$SOCKET_TIMEOUT_MS,"keepAliveFailuresAllowed":1}}
    """.trimIndent()

    private fun tagBody(name: String, server: String, item: String, dataType: String, loss: String) = """
        {"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},
         "spec":{"provider":"default","path":"plant/$name","type":"opc","dataType":"$dataType","opcServer":"$server",
           "opcItemPath":"$item","scanPeriodMillis":$SCAN_MS,"commsTimeoutMillis":$COMMS_TIMEOUT_MS,"commsLoss":"$loss"}}
    """.trimIndent()

    private enum class Link { MODBUS, OPC }

    /**
     * [strict] is the black-hole case: quality must still be good one millisecond of engine time
     * before the deadline, because the socket timeout has not fired.
     * [apply] returns the stopped container id, or an empty string when there is nothing to start.
     */
    private class Fault(
        val name: String,
        val link: Link,
        val strict: Boolean,
        val apply: () -> String,
        val clear: (String) -> Unit,
    )

    private class Tag(val path: String, val lossCode: QualityCode) {
        var value: Double = Double.NaN
        var source: Instant? = null

        fun note(qv: QualifiedValue) {
            value = (qv.value as Number).toDouble()
            assertNotNull(qv.sourceTimestamp, "$path has no source timestamp")
            source = qv.sourceTimestamp
        }

        fun assertLost(qv: QualifiedValue, deadline: Instant, label: String) {
            assertEquals(lossCode, qv.quality.code, "$label $path (${qv.quality})")
            val level = if (lossCode.level == Level.BAD) Level.BAD else Level.UNCERTAIN
            assertEquals(level, qv.quality.level, "$label $path")
            assertEquals(value, (qv.value as Number).toDouble(), 1e-6, "$label $path value")
            assertEquals(source, qv.sourceTimestamp, "$label $path source time")
            assertFalse(qv.timestamp.isAfter(deadline), "$label $path detected at ${qv.timestamp}, deadline $deadline")
        }
    }

    private fun unitFault(
        name: String,
        link: Link,
        strict: Boolean,
        apply: () -> Unit,
        clear: () -> Unit,
    ) = Fault(name, link, strict, apply = { apply(); "" }, clear = { clear() })

    companion object {
        private const val SCAN_MS = 200L
        private const val COMMS_TIMEOUT_MS = 200L
        private const val SOCKET_TIMEOUT_MS = 8_000L

        private fun freeze(control: SimControl, offset: Double) {
            control.set(
                mode = Waveform.Mode.CONSTANT,
                amplitude = 0.0,
                offset = offset,
                periodMs = 10_000,
                epochMs = 0,
                frozen = true,
                nowMs = 0,
            )
        }

        private fun awaitHealthy(control: SimControl) {
            val end = System.nanoTime() + 40_000_000_000L
            while (System.nanoTime() < end) {
                if (runCatching { control.healthy() }.getOrDefault(false)) return
                Thread.sleep(200)
            }
            throw AssertionError("simulator control did not become healthy")
        }

        private fun awaitGood(engine: TagEngine, tags: List<Tag>, budgetMs: Long, label: String) {
            val end = System.nanoTime() + budgetMs * 1_000_000L
            while (System.nanoTime() < end) {
                if (tags.all { tag ->
                        val qv = engine.read(tag.path)
                        qv.quality.code == QualityCode.GOOD && qv.value is Number
                    }
                ) {
                    for (tag in tags) tag.note(engine.read(tag.path))
                    return
                }
                Thread.sleep(20)
            }
            val detail = tags.joinToString { tag ->
                val qv = engine.read(tag.path)
                "${tag.path}=${qv.quality.code} value=${qv.value}"
            }
            throw AssertionError("good $label ($detail)")
        }

        /** A sample after the clock advance. Both tags on the healthy link have to move or be refreshed. */
        private fun awaitHealthySample(engine: TagEngine, tags: List<Tag>, budgetMs: Long, label: String) {
            val before = tags.associate { it.path to it.value }
            val end = System.nanoTime() + budgetMs * 1_000_000L
            val seen = HashSet<String>()
            while (System.nanoTime() < end && seen.size < tags.size) {
                for (tag in tags) {
                    val qv = engine.read(tag.path)
                    if (qv.quality.code != QualityCode.GOOD || qv.value !is Number) continue
                    val now = (qv.value as Number).toDouble()
                    // Mode (and any tag whose number did not change) is refreshed by the same poll
                    // once a sibling on that link has changed. A changed number is the proof.
                    if (abs(now - before.getValue(tag.path)) > 0.5) {
                        tag.note(qv)
                        seen += tag.path
                    }
                }
                if (seen.isNotEmpty() && seen.size < tags.size) {
                    // The sibling was in the same read. Give that publish a moment, then accept
                    // a good read of the unchanged tag as the refresh.
                    Thread.sleep(SCAN_MS)
                    for (tag in tags) {
                        if (tag.path in seen) continue
                        val qv = engine.read(tag.path)
                        if (qv.quality.code == QualityCode.GOOD && qv.value is Number) {
                            tag.note(qv)
                            seen += tag.path
                        }
                    }
                }
                if (seen.size < tags.size) Thread.sleep(20)
            }
            if (seen.size < tags.size) {
                throw AssertionError("$label healthy link did not deliver a new sample (${tags.map { it.path } - seen})")
            }
        }

        private fun namespaceIndex(stack: SimulatorStack): Int {
            val host = stack.endpoints.host
            val port = stack.endpoints.opcUaProxy
            val client = OpcUaClient.create(
                stack.endpoints.opcUaEndpoint,
                { endpoints ->
                    val chosen = endpoints.firstOrNull { it.securityPolicyUri == SecurityPolicy.None.uri }
                        ?: error("no SecurityPolicy.None endpoint in $endpoints")
                    Optional.of(EndpointUtil.updateUrl(chosen, host, port))
                },
                { },
                { },
            )
            try {
                client.connect()
                val namespaces = client.readValue(0.0, TimestampsToReturn.Neither, NodeId.parse("ns=0;i=2255"))
                check(namespaces.statusCode.isGood) { "NamespaceArray ${namespaces.statusCode}" }
                val values = namespaces.value.value as Array<*>
                for (index in values.indices) {
                    val data = client.readValue(0.0, TimestampsToReturn.Both, NodeId(index, "Sim.Value"))
                    if (data.statusCode.isGood) return index
                }
                error("Sim.Value not in ${values.toList()}")
            } finally {
                runCatching { client.disconnect() }
            }
        }
    }
}
