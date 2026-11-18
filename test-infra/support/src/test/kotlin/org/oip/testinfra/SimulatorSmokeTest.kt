package org.oip.testinfra

import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Optional
import kotlin.math.ceil

/**
 * Brings the compose stack up (Testcontainers, unless OIP_SIM_STACK=external) and
 * checks each service speaks its protocol with the scripted waveform.
 */
@Tag("smoke")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(180)
class SimulatorSmokeTest {
    @Test @Order(1) fun everyControlPlaneIsHealthy() {
        assertTrue(stack.modbusControl().healthy())
        assertTrue(stack.opcUaControl().healthy())
        assertTrue(stack.sparkplugControl().healthy())
        assertTrue(stack.toxiproxy().version().isNotBlank())
        assertTrue(stack.mailpit(direct = true).live())
        println("ENDPOINTS ${stack.endpoints}")
    }

    @Test @Order(2) fun modbusRegistersMatchFrozenWaveforms() {
        val control = stack.modbusControl()
        stack.openModbus().use { client ->
            assertFrozen(control, client, Waveform.Mode.SINE, nowMs = 250, period = 1000, amplitude = 100.0, offset = 0.0)
            assertFrozen(control, client, Waveform.Mode.RAMP, nowMs = 250, period = 1000, amplitude = 100.0, offset = 0.0)
            assertFrozen(control, client, Waveform.Mode.STEP, nowMs = 499, period = 1000, amplitude = 80.0, offset = 5.0)
            assertFrozen(control, client, Waveform.Mode.STEP, nowMs = 500, period = 1000, amplitude = 80.0, offset = 5.0)
            assertFrozen(control, client, Waveform.Mode.CONSTANT, nowMs = 0, period = 1000, amplitude = 0.0, offset = 3.25)
        }
    }

    @Test @Order(3) fun opcUaExposesTheSameFrozenValue() {
        val control = stack.opcUaControl()
        val signal = control.set(
            mode = Waveform.Mode.SINE, amplitude = 100.0, offset = 0.0,
            periodMs = 1000, epochMs = 0, frozen = true, nowMs = 250,
        )
        assertEquals(100.0, signal.value, 1e-6)
        val client = opcClient()
        try {
            client.connect()
            val read = readNode(client, "Sim.Value")
            assertEquals(signal.value, (read.value as Number).toDouble(), 1e-6)
            val modeId = read.nodeId.withName("Sim.Mode")
            val written = client.writeValues(listOf(modeId), listOf(DataValue(Variant("ramp")))).single()
            assertTrue(written.isGood, "mode write status $written")
            val mode = readNode(client, "Sim.Mode")
            assertEquals("ramp", mode.value)
            assertEquals(Waveform.Mode.RAMP, control.get().mode)
        } finally {
            control.set(mode = Waveform.Mode.SINE)
            client.disconnect()
        }
    }

    @Test @Order(4) fun sparkplugBirthAndDataCarryTheFrozenValue() {
        val control = stack.sparkplugControl()
        stack.openMqtt().use { mqtt ->
            mqtt.connect("oip-smoke-sparkplug")
            mqtt.subscribe(SparkplugNames.FILTER)
            val signal = control.set(
                mode = Waveform.Mode.RAMP, amplitude = 100.0, offset = 0.0,
                periodMs = 1000, epochMs = 0, frozen = true, nowMs = 250,
            )
            assertEquals(25.0, signal.value, 0.0)
            val deadline = System.nanoTime() + 5_000_000_000L
            var sawBirth = false
            var sawData = false
            while (System.nanoTime() < deadline && !(sawBirth && sawData)) {
                val message = mqtt.receive(500) ?: continue
                val payload = Sparkplug.decode(message.payload)
                val value = payload.metrics.firstOrNull { it.name == "Value" }?.doubleValue
                when (message.topic) {
                    SparkplugNames.NBIRTH, SparkplugNames.DBIRTH -> sawBirth = true
                    SparkplugNames.DDATA -> if (value != null && kotlin.math.abs(value - 25.0) < 1e-6) sawData = true
                }
            }
            assertTrue(sawBirth, "no NBIRTH/DBIRTH")
            assertTrue(sawData, "no DDATA Value=25")
        }
    }

    @Test @Order(5) fun timescaleHypertableAcceptsASample() {
        DriverManager.getConnection(stack.endpoints.postgresJdbcUrl, SimPorts.DB_USER, SimPorts.DB_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'").use { rs ->
                    assertTrue(rs.next(), "timescaledb extension missing")
                    assertTrue(rs.getString(1).isNotBlank())
                    println("timescaledb ${rs.getString(1)}")
                }
                statement.executeQuery(
                    "SELECT hypertable_name FROM timescaledb_information.hypertables WHERE hypertable_name = 'sim_samples'",
                ).use { rs -> assertTrue(rs.next(), "sim_samples hypertable missing") }
                statement.executeUpdate("DELETE FROM sim_samples WHERE signal = 'smoke'")
                statement.executeUpdate("INSERT INTO sim_samples(ts, signal, value) VALUES (now(), 'smoke', 1.25)")
                statement.executeQuery(
                    "SELECT value FROM sim_samples WHERE signal = 'smoke' ORDER BY ts DESC LIMIT 1",
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1.25, rs.getDouble(1), 0.0)
                }
            }
        }
    }

    @Test @Order(6) fun mailpitCapturesSmtpThroughTheProxy() {
        val mail = stack.mailpit()
        mail.deleteAll()
        mail.send("sim@oip.test", "alarm@oip.test", "oip-smoke", "hello from the simulator stack")
        val message = mail.await("oip-smoke")
        assertEquals(listOf("alarm@oip.test"), message.to)
        assertTrue(message.snippet.contains("hello"))
    }

    @Test @Order(7) fun commsCutDropsTheProxyAndLeavesTheDirectPortUp() {
        val tox = stack.toxiproxy()
        try {
            stack.openModbus().use { it.readHolding(0, 1) }
            tox.cut(SimService.MODBUS.proxyName)
            assertThrows<Exception> {
                stack.openModbus(timeoutMs = 500).use { it.readHolding(0, 1) }
            }
            stack.openModbus(direct = true).use { client ->
                assertEquals(1, client.readHolding(0, 1).size)
            }
        } finally {
            tox.restore(SimService.MODBUS.proxyName)
        }
        stack.openModbus().use { it.readHolding(0, 1) }

        // Socat (Testcontainers) accepts the TCP handshake even after Toxiproxy closes
        // the upstream listener, so the cut has to fail an OPC UA Hello/Acknowledge.
        opcUaHello(stack.endpoints.host, stack.endpoints.opcUaProxy, 2_000)
        assertCut("opcua") { opcUaHello(stack.endpoints.host, stack.endpoints.opcUaProxy, 800) }
        opcUaHello(stack.endpoints.host, stack.endpoints.opcUaDirect, 2_000)
        assertCut("mqtt") { stack.openMqtt(timeoutMs = 500).use { it.connect("oip-cut") } }
        stack.openMqtt(direct = true, timeoutMs = 2_000).use { it.connect("oip-direct") }
        assertCut("postgres") {
            DriverManager.setLoginTimeout(1)
            DriverManager.getConnection(
                stack.endpoints.postgresProxyJdbcUrl() + "&connectTimeout=1&socketTimeout=1",
                SimPorts.DB_USER,
                SimPorts.DB_PASSWORD,
            ).use { }
        }
        DriverManager.getConnection(stack.endpoints.postgresJdbcUrl, SimPorts.DB_USER, SimPorts.DB_PASSWORD).use { }
        assertCut("smtp") {
            stack.mailpit().send(
                "sim@oip.test", "alarm@oip.test", "during-cut", "nope",
                connectTimeoutMs = 800, readTimeoutMs = 800,
            )
        }
        stack.mailpit(direct = true).send("sim@oip.test", "alarm@oip.test", "after-direct", "yes")
    }

    @Test @Order(8) fun readLatencyStaysInteractive() {
        stack.modbusControl().set(mode = Waveform.Mode.RAMP, frozen = true, nowMs = 100, periodMs = 1000, amplitude = 100.0, offset = 0.0)
        val modbus = LongArray(200)
        stack.openModbus().use { client ->
            repeat(20) { client.readHolding(ModbusSimMap.VALUE_X100, 1) }
            for (i in modbus.indices) {
                val start = System.nanoTime()
                client.readHolding(ModbusSimMap.VALUE_X100, 1)
                modbus[i] = System.nanoTime() - start
            }
        }
        val modbusP50 = percentile(modbus, 50.0)
        val modbusP99 = percentile(modbus, 99.0)
        println("BENCH modbus-read n=${modbus.size} p50=${modbusP50}ms p99=${modbusP99}ms max=${modbus.max() / 1_000_000.0}ms")
        assertTrue(modbusP99 < 250.0, "modbus p99 ${modbusP99}ms")

        val client = opcClient()
        try {
            client.connect()
            val opc = LongArray(50)
            repeat(5) { readNode(client, "Sim.Value") }
            for (i in opc.indices) {
                val start = System.nanoTime()
                readNode(client, "Sim.Value")
                opc[i] = System.nanoTime() - start
            }
            val p50 = percentile(opc, 50.0)
            val p99 = percentile(opc, 99.0)
            println("BENCH opcua-read n=${opc.size} p50=${p50}ms p99=${p99}ms max=${opc.max() / 1_000_000.0}ms")
            assertTrue(p99 < 500.0, "opcua p99 ${p99}ms")
        } finally {
            client.disconnect()
        }
    }

    @Test @Order(9) fun postgresNamedQueryBindsParameters(@TempDir dir: Path) {
        PostgresNamedQuery.check(dir, stack.endpoints.postgresJdbcUrl)
    }

    private fun assertFrozen(
        control: SimControl,
        client: ModbusTcpClient,
        mode: Waveform.Mode,
        nowMs: Long,
        period: Long,
        amplitude: Double,
        offset: Double,
    ) {
        val signal = control.set(
            mode = mode, amplitude = amplitude, offset = offset,
            periodMs = period, epochMs = 0, frozen = true, nowMs = nowMs,
        )
        val expected = Waveform.value(mode, nowMs, 0, period, amplitude, offset)
        assertEquals(expected, signal.value, if (mode == Waveform.Mode.SINE) 1e-6 else 0.0)
        val register = client.readHolding(ModbusSimMap.VALUE_X100, 1)[0]
        assertEquals(Waveform.toRegister(signal.value), register)
    }

    private fun assertCut(name: String, block: () -> Unit) {
        val tox = stack.toxiproxy()
        try {
            tox.cut(name)
            val start = System.nanoTime()
            assertThrows<Exception>("proxy $name stayed open", block)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(elapsedMs < 4_000, "$name cut took ${elapsedMs}ms")
        } finally {
            tox.restore(name)
        }
    }

    /**
     * OPC UA TCP Hello (Part 6). A live server answers Acknowledge; a cut proxy
     * resets or times out the read.
     */
    private fun opcUaHello(host: String, port: Int, timeoutMs: Int) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val url = "opc.tcp://$host:$port/oip/sim".toByteArray(Charsets.UTF_8)
            val size = 8 + 20 + 4 + url.size
            val hello = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            hello.put("HELF".toByteArray(Charsets.US_ASCII))
            hello.putInt(size)
            hello.putInt(0)
            hello.putInt(65_535)
            hello.putInt(65_535)
            hello.putInt(0)
            hello.putInt(0)
            hello.putInt(url.size)
            hello.put(url)
            socket.getOutputStream().write(hello.array())
            socket.getOutputStream().flush()
            val header = ByteArray(3)
            readFully(socket.getInputStream(), header)
            val type = String(header, Charsets.US_ASCII)
            if (type != "ACK") error("OPC UA hello response was $type")
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw EOFException("short OPC UA response")
            offset += n
        }
    }

    private fun opcClient(): OpcUaClient {
        val host = stack.endpoints.host
        val port = stack.endpoints.opcUaProxy
        return OpcUaClient.create(
            stack.endpoints.opcUaEndpoint,
            { endpoints ->
                val chosen = endpoints.firstOrNull { it.securityPolicyUri == SecurityPolicy.None.uri }
                    ?: error("no SecurityPolicy.None endpoint in $endpoints")
                Optional.of(EndpointUtil.updateUrl(chosen, host, port))
            },
            { },
            { },
        )
    }

    private data class NodeRead(val nodeId: NodeId, val value: Any?)

    private fun readNode(client: OpcUaClient, name: String): NodeRead {
        val namespaces = client.readValue(0.0, TimestampsToReturn.Neither, NodeId.parse("ns=0;i=2255"))
        check(namespaces.statusCode.isGood) { "NamespaceArray ${namespaces.statusCode}" }
        val values = namespaces.value.value as Array<*>
        var last = namespaces.statusCode.toString()
        for (index in values.indices) {
            val id = NodeId(index, name)
            val data = client.readValue(0.0, TimestampsToReturn.Both, id)
            if (data.statusCode.isGood) return NodeRead(id, data.value.value)
            last = data.statusCode.toString()
        }
        error("$name not readable ($last), namespaces=${values.toList()}")
    }

    private fun NodeId.withName(name: String) = NodeId(getNamespaceIndex(), name)

    private fun percentile(samples: LongArray, p: Double): Double {
        val sorted = samples.sorted()
        val index = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index] / 1_000_000.0
    }

    companion object {
        private lateinit var stack: SimulatorStack

        @JvmStatic
        @BeforeAll
        fun up() {
            stack = SimulatorStack.fromEnvironment()
        }

        @JvmStatic
        @AfterAll
        fun down() {
            if (this::stack.isInitialized) stack.close()
        }
    }
}
