package org.oip.perfsim

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.oip.modbus.ModbusDevice
import org.oip.modbus.ModbusDeviceConfig
import org.oip.modbus.ModbusMode
import org.oip.modbus.QualityCode as ModbusQuality
import org.oip.mqtt.DiscoveredMetric
import org.oip.mqtt.MqttConnection
import org.oip.mqtt.MqttConnectionConfig
import org.oip.opcua.OpcUaConnection
import org.oip.opcua.OpcUaConnectionConfig
import org.oip.opcua.QualityCode as OpcQuality
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * CI-sized PRF-012 check. The image is the high-point simulator, not the
 * functional compose stack. Drivers dial the container's bridge address.
 *
 * Full size (150,000 points, and the 10% of 100,000 / 6% of 150,000 scans)
 * is `--pace` plus the run in test-infra/perf-sim/README.md. This test counts
 * every point the container was started with.
 *
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(value = 12, unit = TimeUnit.MINUTES)
class PerfSimHarnessTest {
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeAll fun image() {
        val root = root()
        docker(
            root, true, "build", "-t", IMAGE, "-f",
            "test-infra/perf-sim/Dockerfile", "test-infra/perf-sim",
        )
    }

    @Test @Order(1)
    fun driverRefusesLoopbackPeers() {
        PerfSimPeer.assertRefusesLoopback()
    }

    @Test @Order(2)
    fun changeRatesUsedByTheTagFloorsFinishInsideThePeriod() {
        val self = docker(root(), true, "run", "--rm", IMAGE, "python", "-u", "/app/server.py", "--self-test")
        assertTrue(self.contains("SELFTEST OK"), self)
        val pace = docker(root(), true, "run", "--rm", IMAGE, "python", "-u", "/app/server.py", "--pace")
        assertTrue(pace.contains("PACE OK"), pace)
        val lines = pace.lines().filter { it.startsWith("PACE prf") }
        assertEquals(2, lines.size, pace)
        for (line in lines) {
            assertTrue(line.contains("behind=0"), line)
            assertTrue(line.contains("cpuPercent="), line)
            val ms = Regex("""maxScanMs=([0-9.]+)""").find(line)?.groupValues?.get(1)?.toDouble()
                ?: error("no maxScanMs in $line")
            assertTrue(ms < 1_000.0, line)
        }
        println(pace.trim())
    }

    @Test @Order(3)
    fun ciPointCountIsReadableOnEveryProtocol() {
        val root = root()
        val spec = mapper.readTree(File(root, "test-infra/perf/prf-012.json"))
        val points = spec.get("ciPoints").intValue()
        assertTrue(points in 1..1024, "ciPoints=$points")
        assertTrue(points < spec.get("fullPoints").intValue())
        assertTrue(points > 10, "need a changing point and a stable point")
        val suffix = ProcessHandle.current().pid()
        val network = "oip-perf-sim-$suffix"
        val container = "oip-perf-run-$suffix"
        docker(root, true, "network", "create", network)
        try {
            docker(
                root, true, "run", "-d", "--name", container, "--network", network,
                "-e", "POINTS=$points",
                "-e", "PERCENT_CHANGING=10",
                "-e", "PERIOD_MS=1000",
                "-e", "PATTERN=scan",
                "-e", "FROZEN=true",
                "-e", "NOW_MS=5000",
                "-e", "REGISTERS_PER_UNIT=10000",
                IMAGE,
            )
            val logs = awaitReady(root, container)
            val ip = docker(
                root, true, "inspect", "-f",
                """{{(index .NetworkSettings.Networks "$network").IPAddress}}""",
                container,
            ).trim()
            val driver = docker(root, true, "network", "inspect", "-f", "{{.Driver}}", network).trim()
            assertEquals("bridge", driver, logs)
            assertFalse(ip.startsWith("127."), ip)
            assertFalse(InetAddress.getByName(ip).isLoopbackAddress, ip)
            assertEquals(ip, PerfSimPeer.require(ip))
            Socket().use { probe ->
                probe.connect(InetSocketAddress(ip, MODBUS_PORT), 5_000)
                assertFalse(probe.inetAddress.isLoopbackAddress, probe.inetAddress.hostAddress)
                assertFalse(probe.localAddress.isLoopbackAddress, probe.localAddress.hostAddress)
            }
            val health = httpJson(ip, "/health")
            assertEquals(points, health.get("points").intValue())
            assertEquals("scan", health.get("pattern").asText())
            assertEquals(10.0, health.get("percentChanging").asDouble(), 0.001)
            assertEquals(1000, health.get("periodMs").intValue())
            assertTrue(health.has("cpuPercent") && health.get("cpuPercent").isNumber)
            assertEquals(listOf("modbus", "opcua", "mqtt"), health.get("protocols").map { it.asText() })

            val addresses = (0 until points).map { index ->
                val unit = 1 + index / 10_000
                val hr = index % 10_000 + 1
                "$unit.HR$hr"
            }
            ModbusDevice(ModbusDeviceConfig(
                name = "perf-sim",
                host = ip,
                port = MODBUS_PORT,
                mode = ModbusMode.TCP,
                unitId = 1,
                timeoutMs = 2_000,
                maxRetryCount = 0,
            )).use { device ->
                device.setAddressMap(addresses)
                device.start()
                await("modbus connected ${device.connectionStatus()}") { device.connectionStatus().connected }
                val browsed = device.browse("")
                assertEquals(points, browsed.size, "modbus browse ${browsed.size}")
                assertTrue(browsed.all { it.readable })
                val read = device.read(addresses)
                assertEquals(points, read.size)
                read.forEachIndexed { index, sample ->
                    assertEquals(ModbusQuality.Good, sample.quality.code, "modbus $index ${sample.quality}")
                    assertEquals(frozen(index), (sample.value as Number).toInt(), "modbus value $index")
                }
            }

            OpcUaConnection(OpcUaConnectionConfig(
                name = "perf-sim",
                endpointUrl = "opc.tcp://$ip:$OPC_PORT/oip/perf",
                requestTimeoutMs = 30_000,
                sessionTimeoutMs = 120_000,
            )).use { opc ->
                opc.connect()
                val rootNodes = opc.browse("")
                val folder = rootNodes.firstOrNull { it.displayName == "Points" }
                    ?: error("no Points folder in $rootNodes\n${docker(root, false, "logs", "--tail", "80", container)}")
                val children = opc.browse(folder.nodeId)
                val variables = children.filter { it.displayName.matches(Regex("P\\d{7}")) }
                assertEquals(points, variables.size, "opc browse ${children.size} from ${folder.nodeId}")
                val ids = (0 until points).map { "ns=1;i=${OPC_BASE + it}" }
                val read = opc.read(ids)
                assertEquals(points, read.size)
                read.forEachIndexed { index, sample ->
                    assertEquals(OpcQuality.Good, sample.quality.code, "opc $index ${sample.quality}")
                    assertEquals(frozen(index), (sample.value as Number).toInt(), "opc value $index")
                }
            }

            val discovered = ConcurrentHashMap.newKeySet<String>()
            val plain = ConcurrentHashMap<Int, Int>()
            val mqtt = MqttConnection(
                MqttConnectionConfig(
                    name = "perf-sim",
                    host = ip,
                    port = MQTT_PORT,
                    clientId = "perf-sim-$suffix",
                    sparkplugHostId = "perf-sim",
                    sparkplugGroup = "OIPPERF",
                    connectTimeoutMs = 10_000,
                ),
                { null },
                { metric: DiscoveredMetric ->
                    val name = metric.address.substringAfterLast('/')
                    if (name.matches(Regex("P\\d{7}"))) discovered.add(metric.address)
                },
            )
            val subscriptions = ArrayList<AutoCloseable>(points)
            try {
                for (index in 0 until points) {
                    val topic = "oip/perf/p%07d".format(index)
                    subscriptions += mqtt.attach("number:$topic", 1_000, onSample = { value, quality, _ ->
                        if (quality.statusCode == 0L && value is Number) plain[index] = value.toInt()
                    })
                }
                mqtt.start()
                await("mqtt connected=${mqtt.connected()} sparkplug=${discovered.size} plain=${plain.size}") {
                    mqtt.connected() && discovered.size == points && plain.size == points
                }
                assertEquals(points, discovered.size)
                for (index in 0 until points) {
                    val deviceIndex = index / 500
                    val address = "spb:OIPPERF/PerfSim/D$deviceIndex/P%07d".format(index)
                    val sample = mqtt.read(address)
                    assertEquals(0L, sample.quality.statusCode, "sparkplug $address ${sample.quality.diagnostic}")
                    assertEquals(frozen(index), (sample.value as Number).toInt(), "sparkplug $index")
                    assertEquals(frozen(index), plain[index], "plain $index")
                }

                ModbusDevice(ModbusDeviceConfig(
                    name = "perf-sim-rate",
                    host = ip,
                    port = MODBUS_PORT,
                    mode = ModbusMode.TCP,
                    unitId = 1,
                    timeoutMs = 2_000,
                    maxRetryCount = 0,
                )).use { rateModbus ->
                rateModbus.start()
                await("modbus rate") { rateModbus.connectionStatus().connected }
                OpcUaConnection(OpcUaConnectionConfig(
                    name = "perf-sim-rate",
                    endpointUrl = "opc.tcp://$ip:$OPC_PORT/oip/perf",
                    requestTimeoutMs = 15_000,
                )).use { rateOpc ->
                rateOpc.connect()
                val released = httpJsonPost(ip, "/control", """{"frozen":false}""")
                assertFalse(released.get("frozen").asBoolean())
                val first = awaitAgreement(ip, mqtt, plain, rateModbus, rateOpc)
                val second = awaitAgreement(ip, mqtt, plain, rateModbus, rateOpc, minTick = first.tick + 2)
                val span = second.tick - first.tick
                assertTrue(span in 2..4, "ticks ${first.tick} -> ${second.tick}")
                assertEquals(Math.floorMod(first.changing.toLong() + span, 30_000L).toInt(), second.changing)
                assertEquals(first.stable, second.stable)
                val after = httpJson(ip, "/health")
                assertEquals(0, after.get("behindScans").intValue(), after.toString())
                assertTrue(after.get("scans").intValue() >= 1, after.toString())
                assertTrue(after.get("lastScanMs").asDouble() < 1_000.0, after.toString())
                assertTrue(after.get("cpuPercent").asDouble() >= 0.0, after.toString())
                println(
                    "PRF012 points=$points host=$ip network=$driver modbus=$points opcua=$points " +
                        "mqttPlain=$points mqttSparkplug=${discovered.size} " +
                        "cpuPercent=${after.get("cpuPercent").asDouble()} " +
                        "lastScanMs=${after.get("lastScanMs").asDouble()} behindScans=0",
                )
                } }
            } finally {
                subscriptions.forEach { runCatching { it.close() } }
                mqtt.close()
            }
        } finally {
            docker(root, false, "rm", "-f", container)
            docker(root, false, "network", "rm", network)
        }
    }

    private fun frozen(index: Int): Int = atTick(index, 5)

    private fun atTick(index: Int, tick: Long): Int {
        val base = index % 1000
        if (index % 100 >= 10) return base
        return Math.floorMod(base + tick, 30_000L).toInt()
    }

    private data class Sample(val tick: Long, val changing: Int, val stable: Int)

    /**
     * Modbus, OPC UA, plain MQTT and Sparkplug all showing one wall-clock tick.
     *
     * A read that crosses a period boundary is discarded and tried again. The
     * sample counts only when every source matches that tick and a second read
     * of all four, bracketed by the same tick, still matches. A source left on
     * another tick fails the timeout; this does not accept "any tick".
     */
    private fun awaitAgreement(
        ip: String,
        mqtt: MqttConnection,
        plain: Map<Int, Int>,
        modbus: ModbusDevice,
        opc: OpcUaConnection,
        minTick: Long = 0,
    ): Sample {
        val end = System.nanoTime() + 8_000_000_000L
        var last = ""
        while (System.nanoTime() < end) {
            val tick = wallTick(ip)
            if (tick < minTick) {
                Thread.sleep(20)
                continue
            }
            val changing = atTick(0, tick)
            val stable = atTick(10, tick)
            val first = shot(modbus, opc, mqtt, plain)
            if (wallTick(ip) != tick) continue
            last = first.describe(tick, changing, stable)
            if (!first.matches(changing, stable)) {
                Thread.sleep(20)
                continue
            }
            val second = shot(modbus, opc, mqtt, plain)
            if (wallTick(ip) == tick && second.matches(changing, stable)) {
                return Sample(tick, changing, stable)
            }
            last = second.describe(tick, changing, stable)
        }
        error("protocols did not meet on a tick ($last)")
    }

    private fun wallTick(ip: String): Long = httpJson(ip, "/expected?index=0").get("tick").asLong()

    private fun shot(
        modbus: ModbusDevice,
        opc: OpcUaConnection,
        mqtt: MqttConnection,
        plain: Map<Int, Int>,
    ): Shot {
        val holding = modbus.read(listOf("1.HR1", "1.HR11"))
        val nodes = opc.read(listOf("ns=1;i=$OPC_BASE", "ns=1;i=${OPC_BASE + 10}"))
        val spark = mqtt.read("spb:OIPPERF/PerfSim/D0/P0000000")
        val sparkStable = mqtt.read("spb:OIPPERF/PerfSim/D0/P0000010")
        return Shot(
            modbusChanging = (holding[0].value as? Number)?.toInt(),
            modbusStable = (holding[1].value as? Number)?.toInt(),
            opcChanging = (nodes[0].value as? Number)?.toInt(),
            opcStable = (nodes[1].value as? Number)?.toInt(),
            plainChanging = plain[0],
            plainStable = plain[10],
            sparkChanging = spark.value,
            sparkStable = sparkStable.value,
        )
    }

    private data class Shot(
        val modbusChanging: Int?,
        val modbusStable: Int?,
        val opcChanging: Int?,
        val opcStable: Int?,
        val plainChanging: Int?,
        val plainStable: Int?,
        val sparkChanging: Any?,
        val sparkStable: Any?,
    ) {
        fun matches(changing: Int, stable: Int): Boolean =
            modbusChanging == changing && modbusStable == stable &&
                opcChanging == changing && opcStable == stable &&
                plainChanging == changing && plainStable == stable &&
                (sparkChanging as? Number)?.toInt() == changing &&
                (sparkStable as? Number)?.toInt() == stable

        fun describe(tick: Long, changing: Int, stable: Int): String {
            val modbusOk = modbusChanging == changing && modbusStable == stable
            val opcOk = opcChanging == changing && opcStable == stable
            return "tick=$tick modbus=$modbusOk opc=$opcOk plain=$plainChanging/$plainStable spark=$sparkChanging/$sparkStable"
        }
    }

    private fun awaitReady(root: File, container: String): String {
        val end = System.nanoTime() + 60_000_000_000L
        var logs = ""
        while (System.nanoTime() < end) {
            logs = docker(root, false, "logs", container)
            if (logs.contains("READY")) return logs
            Thread.sleep(200)
        }
        error("simulator did not become ready\n$logs")
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(30)
        }
        throw AssertionError(message)
    }

    private fun httpJson(ip: String, path: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create("http://$ip:8080$path"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return mapper.readTree(response.body())
    }

    private fun httpJsonPost(ip: String, path: String, json: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create("http://$ip:8080$path"))
            .timeout(Duration.ofSeconds(10))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return mapper.readTree(response.body())
    }

    private fun root(): File = File(System.getProperty("perfSim.root") ?: error("perfSim.root"))

    private companion object {
        const val IMAGE = "oip-perf-sim:ci"
        const val MODBUS_PORT = 5020
        const val OPC_PORT = 4840
        const val MQTT_PORT = 1883
        const val OPC_BASE = 1_000_000
    }
}

private fun docker(root: File, check: Boolean, vararg args: String): String {
    val proc = ProcessBuilder(listOf("docker") + args).directory(root).redirectErrorStream(true).start()
    val text = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    if (check && code != 0) error("docker ${args.joinToString(" ")} exited $code\n$text")
    return text
}
