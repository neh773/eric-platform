package org.oip.modbus

import com.digitalpetri.modbus.server.ModbusRtuServer
import com.digitalpetri.modbus.server.ModbusServer
import com.digitalpetri.modbus.server.ModbusServerConfig
import com.digitalpetri.modbus.server.ModbusTcpServer
import com.digitalpetri.modbus.server.ProcessImage
import com.digitalpetri.modbus.server.ReadWriteModbusServices
import com.digitalpetri.modbus.tcp.server.NettyRtuServerTransport
import com.digitalpetri.modbus.tcp.server.NettyServerTransportConfig
import com.digitalpetri.modbus.tcp.server.NettyTcpServerTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.ValidationException
import org.oip.host.ModuleHost
import org.oip.host.ModuleState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * The slave is digitalpetri Modbus 2.1.6 (EPL-2.0), in process. It is not the driver under test.
 * The throughput figure is an in-process loopback number, not a measurement of a network device.
 * RTU over TCP uses [NettyRtuServerTransport]; Modbus TCP uses [NettyTcpServerTransport].
 */
class ModbusDriverTest {
    @Test fun readsAndWritesCoilsRegistersBitsStringsAndUnitIds() {
        TestSlave(ModbusMode.TCP, intArrayOf(1, 3)).use { slave ->
            slave.images.getValue(1).putHolding(0, 0x00, 0x2A) // HR1 == 42 when one-based
            slave.images.getValue(1).putInput(0, 0x00, 0x07)
            slave.images.getValue(1).putCoil(0, true)
            slave.images.getValue(1).putDiscrete(1, true)
            slave.images.getValue(3).putHolding(0, 0x00, 0x09)
            val device = device(slave, ModbusMode.TCP)
            device.start()
            awaitConnected(device)
            val values = device.read(listOf("HR1", "IR1", "C1", "DI2", "3.HR1", "HR1.1", "HR1.5"))
            assertEquals(42, values[0].value)
            assertEquals(QualityCode.Good, values[0].quality.code)
            assertEquals(7, values[1].value)
            assertEquals(true, values[2].value)
            assertEquals(true, values[3].value)
            assertEquals(9, values[4].value)
            assertEquals(true, values[5].value) // 42 = 0b101010, bit 1
            assertEquals(true, values[6].value) // bit 5

            val wrote = device.write("HR2", 1000)
            assertEquals(QualityCode.Good, wrote.quality.code)
            assertEquals(1000, device.read(listOf("HR2")).single().value)
            assertEquals(QualityCode.Good, device.write("C2", true).quality.code)
            assertEquals(true, device.read(listOf("C2")).single().value)
            assertEquals(QualityCode.Good, device.write("HR10.3", true).quality.code)
            assertEquals(8, device.read(listOf("HR10")).single().value)
            assertEquals(true, device.read(listOf("HR10.3")).single().value)

            assertEquals(QualityCode.Bad_NotWritable, device.write("IR1", 1).quality.code)
            assertEquals(QualityCode.Bad_NotWritable, device.write("DI1", true).quality.code)
            assertEquals(QualityCode.Bad_ConfigurationError, device.read(listOf("nope")).single().quality.code)

            val cfg = device.config.copy(wordOrder = WordOrder.LOW_WORD_FIRST)
            device.stop()
            ModbusDevice(cfg.copy(port = slave.port)).use { swapped ->
                swapped.start(); awaitConnected(swapped)
                assertEquals(QualityCode.Good, swapped.write("HRF1", 1.0f).quality.code)
                val raw0 = slave.images.getValue(1).holding(0)
                val raw1 = slave.images.getValue(1).holding(1)
                assertEquals(0x0000, raw0) // CDAB: low word first
                assertEquals(0x3F80, raw1)
                assertEquals(1.0f, swapped.read(listOf("HRF1")).single().value)
            }

            assertEquals(QualityCode.Good, device.also { it.start(); awaitConnected(it) }.write("HRS20:4", "AB").quality.code)
            assertEquals("AB", device.read(listOf("HRS20:4")).single().value)
            assertEquals(QualityCode.Good, device.write("HRBCD30", 1234).quality.code)
            assertEquals(0x1234, slave.images.getValue(1).holding(29))
            assertEquals(1234L, device.read(listOf("HRBCD30")).single().value)
            device.close()
        }
    }

    @Test fun rtuOverTcpReadsAndWrites() {
        TestSlave(ModbusMode.RTU_OVER_TCP, intArrayOf(1)).use { slave ->
            slave.images.getValue(1).putHolding(0, 0x12, 0x34)
            val device = device(slave, ModbusMode.RTU_OVER_TCP)
            device.start(); awaitConnected(device)
            assertEquals(0x1234, device.read(listOf("HRUS1")).single().value)
            assertEquals(QualityCode.Good, device.write("HR1", -2).quality.code)
            assertEquals(-2, device.read(listOf("HR1")).single().value)
            device.close()
        }
    }

    @Test fun scanClassesPollAtTheirOwnPeriods() {
        TestSlave(ModbusMode.TCP).use { slave ->
            slave.images.getValue(1).putHolding(0, 0x00, 0x01)
            val device = device(slave, ModbusMode.TCP, defaultScanPeriodMs = 1_000)
            device.defineScanClass("fast", 80)
            device.defineScanClass("slow", 400)
            device.start(); awaitConnected(device)
            val fast = AtomicInteger()
            val slow = AtomicInteger()
            val fastSub = device.subscribe(listOf("HR1"), "fast") { fast.incrementAndGet() }
            val slowSub = device.subscribe(listOf("HR1"), "slow") { slow.incrementAndGet() }
            Thread.sleep(1_000)
            fastSub.close(); slowSub.close()
            assertTrue(fast.get() > slow.get(), "fast ${fast.get()} slow ${slow.get()}")
            assertTrue(fast.get() >= 6, "fast ${fast.get()}")
            assertTrue(slow.get() in 1..5, "slow ${slow.get()}")
            device.close()
        }
    }

    @Test fun attachCoalescesAScanClassIntoOnePdu() {
        TestSlave(ModbusMode.TCP).use { slave ->
            slave.images.getValue(1).putHolding(0, 0x00, 0x07)
            val device = device(slave, ModbusMode.TCP, defaultScanPeriodMs = 200)
            device.start(); awaitConnected(device)
            val seen = AtomicInteger()
            val handles = (1..40).map { i ->
                device.attach(200, "HR$i") { sample ->
                    if (sample.address == "HR1" && sample.quality.code == QualityCode.Good) seen.incrementAndGet()
                }
            }
            await("samples") { seen.get() >= 2 }
            val polls = device.readRequestCount
            handles.forEach { it.close() }
            assertTrue(polls in 1..8, "polls $polls for 40 addresses")
            assertEquals(7, device.read(listOf("HR1")).single().value)
            device.close()
        }
    }

    /**
     * A tag attached while another thread parses the same address for the first time (a scan
     * compile, or a read from the API) must still get the polled value. The parse cache used to
     * keep whichever parse finished last, so the listener could hang on a copy the scan never
     * dispatched to and stay "waiting for initial data" for good.
     */
    @Test fun attachRawWhileAnotherThreadParsesTheSameAddressStillDelivers() {
        TestSlave(ModbusMode.TCP).use { slave ->
            val device = device(slave, ModbusMode.TCP, defaultScanPeriodMs = 50)
            device.start(); awaitConnected(device)
            val count = 300
            val good = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val other = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                for (i in 1..count) {
                    val address = "HR$i"
                    val barrier = java.util.concurrent.CyclicBarrier(2)
                    val reader = other.submit { barrier.await(); device.read(listOf(address)) }
                    barrier.await()
                    device.attachRaw(50, address) { _, quality, _ -> if (quality.code == QualityCode.Good) good += address }
                    reader.get()
                }
                // 300 registers are three read blocks per scan. Six more PDUs means at least one
                // whole scan started after the last attach and has delivered.
                val polled = device.readRequestCount
                await("one full scan after the last attach") { device.readRequestCount >= polled + 6 }
                val missing = (1..count).map { "HR$it" } - good
                assertEquals(emptyList<String>(), missing, "listeners never given a value")
            } finally {
                other.shutdownNow()
                device.close()
            }
        }
    }

    @Test fun benchDeviceSpecValidates() {
        val registry = SchemaRegistry()
        registry.register(ModbusDriverModule.MODULE_ID, DEVICE_KIND, modbusDeviceSchema())
        val spec = mapOf(
            "name" to "bench-mb-0",
            "host" to "127.0.0.1",
            "port" to 1502,
            "mode" to "TCP",
            "unitId" to 1,
            "zeroBasedAddressing" to false,
            "concurrentRequests" to 2,
            "defaultScanPeriodMs" to 1000,
            "maxHoldingRegistersPerRead" to 125,
            "spanGaps" to true,
        )
        val applied = registry.applyDefaults(ModbusDriverModule.MODULE_ID, DEVICE_KIND, spec)
        val config = modbusDeviceConfig("bench-mb-0", applied)
        assertEquals("bench-mb-0", config.name)
        assertEquals("127.0.0.1", config.host)
        assertEquals(2, config.concurrentRequests)
        assertEquals(1000, config.defaultScanPeriodMs)
        assertTrue(config.spanGaps)
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(
                DEVICE_KIND,
                Metadata("bench-mb-0", ModbusDriverModule.MODULE_ID),
                spec + ("nope" to true),
            ))
        }
        val file = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n").trim()
        assertEquals(file, ModbusDriverModule.MANIFEST_YAML.trim())
    }

    @Test fun coalescedReadIsOnePdu() {
        TestSlave(ModbusMode.TCP).use { slave ->
            val device = device(slave, ModbusMode.TCP, spanGaps = false)
            device.start(); awaitConnected(device)
            val before = device.readRequestCount
            val values = device.read((1..10).map { "HR$it" })
            assertEquals(10, values.size)
            assertTrue(values.all { it.quality.code == QualityCode.Good })
            assertEquals(1, device.readRequestCount - before)
            val beforeGap = device.readRequestCount
            device.read(listOf("HR1", "HR50"))
            assertEquals(2, device.readRequestCount - beforeGap)
            device.close()
        }
    }

    @Test fun commsLossSetsBadQualityThenRecovers() {
        TestSlave(ModbusMode.TCP).use { slave ->
            slave.images.getValue(1).putHolding(0, 0x00, 0x2A)
            val device = device(
                slave, ModbusMode.TCP,
                timeoutMs = 300, backoffInitialMs = 40, backoffMaxMs = 200, backoffMultiplier = 2.0,
                defaultScanPeriodMs = 80, commsLossQuality = QualityCode.Bad_NotConnected,
            )
            device.defineScanClass("fast", 80)
            device.start(); awaitConnected(device)
            val seen = java.util.concurrent.CopyOnWriteArrayList<QualifiedValue>()
            val sub = device.subscribe(listOf("HR1"), "fast") { seen += it }
            await("initial good") { seen.any { it.quality.code == QualityCode.Good && it.value == 42 } }
            slave.stop()
            await("bad after loss") {
                seen.any { it.quality.code == QualityCode.Bad_NotConnected && it.value == 42 }
            }
            val during = seen.last { it.quality.code == QualityCode.Bad_NotConnected }
            assertEquals(42, during.value)
            assertEquals(QualityLevel.BAD, during.quality.level)
            assertEquals(0x808A0000L, during.quality.statusCode)
            val afterLoss = seen.size
            slave.start()
            await("recovered") {
                device.connectionStatus().state == ConnectionState.CONNECTED &&
                    seen.drop(afterLoss).any { it.quality.code == QualityCode.Good && it.value == 42 }
            }
            assertEquals(ConnectionState.CONNECTED, device.connectionStatus().state)
            sub.close()
            device.close()
        }
    }

    @Test fun uncertainLastKnownValueIsSelectable() {
        TestSlave(ModbusMode.TCP).use { slave ->
            slave.images.getValue(1).putHolding(0, 0x00, 0x01)
            val device = device(slave, ModbusMode.TCP, timeoutMs = 200, backoffInitialMs = 50, backoffMaxMs = 200, commsLossQuality = QualityCode.Uncertain_LastKnownValue)
            device.defineScanClass("fast", 50)
            device.start(); awaitConnected(device)
            val seen = java.util.concurrent.CopyOnWriteArrayList<QualifiedValue>()
            device.subscribe(listOf("HR1"), "fast") { seen += it }
            await("good") { seen.any { it.quality.code == QualityCode.Good } }
            slave.stop()
            await("uncertain") { seen.any { it.quality.code == QualityCode.Uncertain_LastKnownValue && it.value == 1 } }
            device.close()
        }
    }

    @Test fun reconnectBackoffGrowsUntilTheCap() {
        val device = ModbusDevice(ModbusDeviceConfig(
            name = "down", host = "127.0.0.1", port = 1, timeoutMs = 200,
            backoffInitialMs = 40, backoffMaxMs = 160, backoffMultiplier = 2.0,
        ))
        device.start()
        await("three attempts") { device.reconnectDelaysMs.size >= 3 }
        val d = device.reconnectDelaysMs
        assertTrue(d[0] in 32L..48L, "delay0=${d[0]}")
        assertTrue(d[1] in 64L..96L, "delay1=${d[1]}")
        assertTrue(d[2] in 128L..160L, "delay2=${d[2]}")
        assertTrue(d[0] < d[1] && d[1] < d[2])
        assertEquals(ConnectionState.RECONNECTING, device.connectionStatus().state)
        assertFalse(device.connectionStatus().connected)
        device.close()
    }

    @Test fun rejectsAReplyWhoseProtocolIdOrUnitIdDoesNotMatch() {
        fun reply(mutate: (ByteArray) -> Unit): IOException {
            ServerSocket(0).use { listen ->
                val accepted = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>()
                val thread = Thread {
                    val socket = listen.accept()
                    accepted.set(socket)
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val header = ByteArray(7)
                    input.readFully(header)
                    val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                    val body = ByteArray((length - 1).coerceAtLeast(0))
                    if (body.isNotEmpty()) input.readFully(body)
                    mutate(header)
                    output.write(header)
                    output.write(body)
                    output.flush()
                }
                thread.isDaemon = true
                thread.start()
                val session = ModbusSession(ModbusDeviceConfig(
                    name = "mbap", host = "127.0.0.1", port = listen.localPort, timeoutMs = 1_000, maxRetryCount = 0,
                ))
                try {
                    session.connect()
                    return assertThrows(IOException::class.java) {
                        session.transact(1, byteArrayOf(3, 0, 0, 0, 1))
                    }
                } finally {
                    session.close()
                    accepted.get()?.close()
                    thread.join(2_000)
                }
            }
        }
        val protocol = reply { header -> header[3] = 1 }
        assertTrue(protocol.message?.contains("protocol id") == true, protocol.message)
        val unit = reply { header -> header[6] = 9 }
        assertTrue(unit.message?.contains("unit id") == true, unit.message)
    }

    @Test fun throughputAgainstTheInProcessSlave() {
        TestSlave(ModbusMode.TCP).use { slave ->
            val image = slave.images.getValue(1)
            for (i in 0 until 2000) image.putHolding(i, (i ushr 8) and 0xFF, i and 0xFF)
            val device = device(slave, ModbusMode.TCP, timeoutMs = 2_000, concurrentRequests = 2)
            device.start(); awaitConnected(device)
            val addresses = (1..2000).map { "HR$it" }
            val warm = device.read(addresses)
            assertEquals(2000, warm.size)
            assertEquals(0, warm.first().value)
            assertEquals(1999, warm.last().value)
            val start = System.nanoTime()
            var samples = 0L
            var calls = 0
            val deadline = start + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val batch = device.read(addresses)
                assertEquals(2000, batch.size)
                assertEquals(QualityCode.Good, batch.first().quality.code)
                assertEquals(QualityCode.Good, batch.last().quality.code)
                samples += batch.size
                calls++
            }
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            val rate = samples / seconds
            println("BENCH modbus in-process values/s=${"%.0f".format(rate)} calls=$calls seconds=${"%.2f".format(seconds)} readPdus=${device.readRequestCount}")
            assertTrue(rate >= 3_000.0, "throughput $rate values/s")
            device.close()
        }
    }

    @TempDir lateinit var tmp: Path

    @Test fun moduleInstallStartStopUninstallLeavesNoResources() {
        val id = "org.oip.driver-modbus"
        val manifest = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n")
        val modules = tmp.resolve("modules")
        modules.resolve("driver-modbus").createDirectories().resolve("module.yaml").writeText(manifest)
        val config = tmp.resolve("config")
        val store = FileConfigStore(config, SchemaRegistry())
        val host = ModuleHost(modules, config, store = store)
        host.installAll()
        assertEquals(ModuleState.INSTALLED, host.state(id))
        assertEquals(1000, store.list(module = id).single().resource.spec["defaultTimeoutMs"])
        host.start(id)
        val module = host.instance(id) as ModbusDriverModule
        assertEquals(ModuleState.RUNNING, host.state(id))
        host.stop(id)
        host.uninstall(id)
        assertTrue(host.installed().isEmpty())
        assertEquals(0, store.list(module = id).size)
        assertFalse(config.resolve(id).exists())
        assertTrue(module.device("none") == null)
    }

    private fun device(
        slave: TestSlave,
        mode: ModbusMode,
        timeoutMs: Long = 1_000,
        backoffInitialMs: Long = 50,
        backoffMaxMs: Long = 500,
        backoffMultiplier: Double = 2.0,
        defaultScanPeriodMs: Long = 1_000,
        spanGaps: Boolean = true,
        concurrentRequests: Int = 1,
        commsLossQuality: QualityCode = QualityCode.Bad_NotConnected,
    ) = ModbusDevice(ModbusDeviceConfig(
        name = "sim", host = "127.0.0.1", port = slave.port, mode = mode, unitId = 1,
        timeoutMs = timeoutMs, backoffInitialMs = backoffInitialMs, backoffMaxMs = backoffMaxMs,
        backoffMultiplier = backoffMultiplier, defaultScanPeriodMs = defaultScanPeriodMs,
        spanGaps = spanGaps, maxGap = if (spanGaps) Int.MAX_VALUE else 0,
        concurrentRequests = concurrentRequests, commsLossQuality = commsLossQuality,
    ))

    private fun awaitConnected(device: ModbusDevice) {
        await("connected ${device.connectionStatus()}") { device.connectionStatus().connected }
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }
}

/** digitalpetri in-process slave. The same [ProcessImage] survives a stop/start so values stay put. */
class TestSlave(private val mode: ModbusMode, units: IntArray = intArrayOf(1)) : AutoCloseable {
    val port: Int = ServerSocket(0).use { it.localPort }
    val images: Map<Int, ProcessImage> = units.associateWith { ProcessImage() }
    private var server: ModbusServer? = null

    init { start() }

    @Synchronized fun start() {
        check(server == null)
        val cfg = NettyServerTransportConfig.create {
            it.setBindAddress("127.0.0.1")
            it.setPort(port)
        }
        val services = ImageServices(images)
        server = if (mode == ModbusMode.TCP) {
            ModbusTcpServer(ModbusServerConfig.create { }, NettyTcpServerTransport(cfg), services)
        } else {
            ModbusRtuServer(ModbusServerConfig.create { }, NettyRtuServerTransport(cfg), services)
        }
        server!!.start()
    }

    @Synchronized fun stop() {
        server?.stop()
        server = null
    }

    override fun close() { stop() }
}

private class ImageServices(private val images: Map<Int, ProcessImage>) : ReadWriteModbusServices() {
    override fun getProcessImage(unitId: Int): Optional<ProcessImage> = Optional.ofNullable(images[unitId])
}

private fun ProcessImage.putHolding(address: Int, hi: Int, lo: Int) = putRegister(true, address, hi, lo)
private fun ProcessImage.putInput(address: Int, hi: Int, lo: Int) = putRegister(false, address, hi, lo)

private fun ProcessImage.putRegister(holding: Boolean, address: Int, hi: Int, lo: Int) {
    val bytes = byteArrayOf(hi.toByte(), lo.toByte())
    with { tx ->
        val write = { map: MutableMap<Int, ByteArray> -> map[address] = bytes }
        if (holding) tx.writeHoldingRegisters(write) else tx.writeInputRegisters(write)
    }
}

private fun ProcessImage.holding(address: Int): Int = withRegister(true, address)
private fun ProcessImage.withRegister(holding: Boolean, address: Int): Int = get { tx ->
    val read: (Map<Int, ByteArray>) -> Int = { map ->
        val b = map[address] ?: byteArrayOf(0, 0)
        ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }
    if (holding) tx.readHoldingRegisters(read) else tx.readInputRegisters(read)
}

private fun ProcessImage.putCoil(address: Int, on: Boolean) {
    with { tx ->
        tx.writeCoils { map ->
            val m = map as MutableMap<Int, Boolean>
            if (on) m[address] = true else m.remove(address)
        }
    }
}

private fun ProcessImage.putDiscrete(address: Int, on: Boolean) {
    with { tx ->
        tx.writeDiscreteInputs { map ->
            val m = map as MutableMap<Int, Boolean>
            if (on) m[address] = true else m.remove(address)
        }
    }
}
