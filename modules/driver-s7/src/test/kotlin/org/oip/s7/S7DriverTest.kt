package org.oip.s7

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.ValidationException
import org.oip.host.ModuleHost
import org.oip.host.ModuleState
import org.oip.sim.s7.S7Simulator
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * The peer is the in-repo S7comm simulator (test-infra/sim/s7), not a GPL stack.
 * Doc source: FEATURES DEV-025, DEV-036.
 *
 * The throughput figure is an in-process loopback number, not a measurement of a network PLC.
 */
@Timeout(60)
class S7DriverTest {
    @Test fun readsAndWritesEachTypeAndArea() {
        S7Simulator().use { sim ->
            sim.ensureDb(1, 8192)
            sim.ensureDb(500, 64)
            sim.setBytes('I', 0, byteArrayOf(0x01, 0x02, 0xFF.toByte(), 0xFF.toByte(), 0x12, 0x34, 0x56, 0x78))
            sim.setBytes('Q', 0, byteArrayOf(0x10))
            sim.setBytes('M', 0, byteArrayOf(0x01))
            sim.setBytes('D', 8, byteArrayOf(0x00, 0x00, 0x00, 0x2A), db = 500)
            val device = device(sim, S7Model.S7_300)
            device.start(); awaitConnected(device)

            assertEquals(2, device.read(listOf("IB1")).single().value)
            assertEquals(0x1234, device.read(listOf("IW4")).single().value)
            assertEquals(true, device.read(listOf("IX0.0")).single().value)
            assertEquals(false, device.read(listOf("IX0.1")).single().value)
            assertEquals(QualityCode.Good, device.write("QB0", 0x7F).quality.code)
            assertEquals(0x7F, sim.getBytes('Q', 0, 1)[0].toInt() and 0xFF)
            assertEquals(0x7F, device.read(listOf("QB0")).single().value)
            assertEquals(QualityCode.Good, device.write("MX0.3", true).quality.code)
            assertEquals(0x09, sim.getBytes('M', 0, 1)[0].toInt() and 0xFF)
            assertEquals(true, device.read(listOf("MX0.0")).single().value)
            assertEquals(true, device.read(listOf("MX0.3")).single().value)

            assertEquals(42, device.read(listOf("DB500,DI8")).single().value)
            assertEquals(255, device.read(listOf("IB2")).single().value)
            assertEquals(-1, device.read(listOf("IC2")).single().value)
            assertEquals(65535, device.read(listOf("IW2")).single().value)
            assertEquals(-1, device.read(listOf("II2")).single().value)
            assertEquals(0x12345678L, device.read(listOf("ID4")).single().value)
            assertEquals(0x12345678, device.read(listOf("IDI4")).single().value)

            assertEquals(QualityCode.Good, device.write("DB1,I0", -2).quality.code)
            assertEquals(-2, device.read(listOf("DB1,I0")).single().value)
            assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), sim.getBytes('D', 0, 2, 1))
            assertEquals(QualityCode.Good, device.write("DB1,DI4", 0x12345678).quality.code)
            assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), sim.getBytes('D', 4, 4, 1))
            assertEquals(QualityCode.Good, device.write("DB1,REAL8", 1.0f).quality.code)
            assertArrayEquals(byteArrayOf(0x3F, 0x80.toByte(), 0x00, 0x00), sim.getBytes('D', 8, 4, 1))
            assertEquals(1.0f, device.read(listOf("DB1,REAL8")).single().value)
            assertEquals(QualityCode.Good, device.write("DB1,STRING12.4", "AB").quality.code)
            assertArrayEquals(byteArrayOf(4, 2, 'A'.code.toByte(), 'B'.code.toByte(), 0, 0), sim.getBytes('D', 12, 6, 1))
            assertEquals("AB", device.read(listOf("DB1,STRING12.4")).single().value)
            assertEquals(QualityCode.Good, device.write("DB1,DT20", "2024-03-05T06:07:08.090").quality.code)
            assertEquals("2024-03-05T06:07:08.090", device.read(listOf("DB1,DT20")).single().value)
            assertEquals(0x24, sim.getBytes('D', 20, 1, 1)[0].toInt() and 0xFF)

            assertEquals(QualityCode.Good, device.write("T0", 1500).quality.code)
            assertEquals(0x0150, sim.timerWord(0))
            assertEquals(1500, device.read(listOf("T0")).single().value)
            assertEquals(QualityCode.Good, device.write("T1", 10_000).quality.code)
            assertEquals(0x1100, sim.timerWord(1))
            assertEquals(10_000, device.read(listOf("T1")).single().value)
            assertEquals(QualityCode.Good, device.write("C0", 42).quality.code)
            assertEquals(0x0042, sim.counterWord(0))
            assertEquals(42, device.read(listOf("C0")).single().value)
            assertEquals(QualityCode.Bad_OutOfRange, device.write("C1", 1000).quality.code)
            assertEquals(QualityCode.Bad_TypeMismatch, device.write("IB0", "nope").quality.code)
            assertEquals(QualityCode.Bad_TypeMismatch, device.write("IX0.0", 1).quality.code)
            assertEquals(QualityCode.Bad_ConfigurationError, device.read(listOf("nope")).single().quality.code)
            val rejected = device.read(listOf("T", "ILI0"))
            assertEquals(QualityCode.Bad_ConfigurationError, rejected[0].quality.code)
            assertEquals(QualityCode.Bad_ConfigurationError, rejected[1].quality.code)
            assertEquals(QualityCode.Bad_NotFound, device.read(listOf("DB99,B0")).single().quality.code)
            assertEquals(QualityCode.Bad_OutOfRange, device.read(listOf("DB1,B9000")).single().quality.code)

            val shared = device.read(listOf("IW0", "IW1"))
            // Bytes at I0.. were 01 02 FF FF, so IW0 = 0x0102 and IW1 shares the second byte.
            assertEquals(0x0102, shared[0].value)
            assertEquals(0x02FF, shared[1].value)
            device.close()
        }
    }

    @Test fun modelLimitsAndTsapsFollowTheCpu() {
        S7Simulator().use { sim ->
            sim.ensureDb(1, 256)
            connect(sim, S7Model.S7_300, rack = 0, slot = 2).use { cpu ->
                assertEquals(0x0100, sim.localTsap)
                assertEquals(0x0102, sim.remoteTsap)
                assertEquals(QualityCode.Good, cpu.write("MW0", 7).quality.code)
                assertEquals(7, cpu.read(listOf("MW0")).single().value)
            }
            connect(sim, S7Model.S7_400, rack = 0, slot = 3).use { cpu ->
                assertEquals(0x0103, sim.remoteTsap)
                assertEquals(QualityCode.Good, cpu.write("DB1,DT0", "1999-12-31T23:59:58.010").quality.code)
                assertEquals("1999-12-31T23:59:58.010", cpu.read(listOf("DB1,DT0")).single().value)
                assertEquals(QualityCode.Bad_ConfigurationError, cpu.read(listOf("ILREAL0")).single().quality.code)
            }
            connect(sim, S7Model.S7_1200, rack = 0, slot = 1).use { cpu ->
                assertEquals(0x0101, sim.remoteTsap)
                assertEquals(QualityCode.Good, cpu.write("DB1,LREAL0", 1.5).quality.code)
                assertEquals(1.5, cpu.read(listOf("DB1,LREAL0")).single().value)
                assertEquals(QualityCode.Bad_ConfigurationError, cpu.read(listOf("T0")).single().quality.code)
                assertEquals(QualityCode.Bad_ConfigurationError, cpu.read(listOf("C0")).single().quality.code)
                assertEquals(QualityCode.Bad_ConfigurationError, cpu.write("ILI0", 1).quality.code)
                assertEquals(QualityCode.Bad_ConfigurationError, cpu.read(listOf("IDT0")).single().quality.code)
            }
            connect(sim, S7Model.S7_1500, rack = 0, slot = 1, localTsap = 0x1002, remoteTsap = 0x0301).use { cpu ->
                assertEquals(0x1002, sim.localTsap)
                assertEquals(0x0301, sim.remoteTsap)
                assertEquals(QualityCode.Good, cpu.write("DB1,LI0", -2L).quality.code)
                assertEquals(-2L, cpu.read(listOf("DB1,LI0")).single().value)
                assertEquals(QualityCode.Bad_ConfigurationError, cpu.read(listOf("T0")).single().quality.code)
                assertEquals(QualityCode.Bad_ConfigurationError, cpu.read(listOf("C3")).single().quality.code)
                assertEquals(QualityCode.Good, cpu.read(listOf("DB1,LREAL0")).single().quality.code)
            }
        }
    }

    @Test fun batchingRespectsTheNegotiatedPdu() {
        S7Simulator(maxPdu = 240).use { sim ->
            sim.ensureDb(1, 8192)
            val raw = ByteArray(400)
            for (i in raw.indices) raw[i] = (i and 0xFF).toByte()
            sim.setBytes('D', 0, raw, 1)
            val narrow = device(sim, S7Model.S7_1500, pduSize = 960)
            narrow.start(); awaitConnected(narrow)
            assertEquals(240, narrow.negotiatedPduSize)
            sim.resetStats()
            val words = (0 until 200).map { "DB1,W${it * 2}" }
            val values = narrow.read(words)
            assertEquals(200, values.size)
            assertTrue(values.all { it.quality.code == QualityCode.Good })
            assertEquals(0x0001, values.first().value)
            assertEquals(((398 and 0xFF) shl 8) or (399 and 0xFF), values.last().value)
            assertTrue(narrow.readRequestCount >= 2, "jobs ${narrow.readRequestCount}")
            assertTrue(sim.maxItemElements() <= 222, "item ${sim.maxItemElements()}")
            narrow.close()
        }
        S7Simulator(maxPdu = 960).use { sim ->
            sim.ensureDb(1, 8192)
            val wide = device(sim, S7Model.S7_1500, pduSize = 960)
            wide.start(); awaitConnected(wide)
            assertEquals(960, wide.negotiatedPduSize)
            sim.resetStats()
            val bytes = (0 until 40).map { "DB1,B$it" }
            val values = wide.read(bytes)
            assertTrue(values.all { it.quality.code == QualityCode.Good })
            assertEquals(1, wide.readRequestCount)
            assertEquals(40, sim.maxItemElements())
            sim.resetStats()
            val before = wide.readRequestCount
            wide.read(listOf("DB1,B0", "DB1,B10"))
            assertEquals(1, wide.readRequestCount - before)
            assertEquals(1, sim.maxItemElements())
            wide.close()
        }
    }

    @Test fun attachRawCoalescesAScan() {
        S7Simulator().use { sim ->
            sim.ensureDb(1, 256)
            sim.setBytes('D', 0, byteArrayOf(7), 1)
            val device = device(sim, S7Model.S7_300, pduSize = 240, scanMs = 80)
            device.start(); awaitConnected(device)
            val seen = AtomicInteger()
            val handles = (0 until 40).map { i ->
                device.attachRaw(80, "DB1,B$i") { _, quality, _ ->
                    if (i == 0 && quality.code == QualityCode.Good) seen.incrementAndGet()
                }
            }
            await("samples") { seen.get() >= 2 }
            val polls = device.readRequestCount
            handles.forEach { it.close() }
            assertTrue(polls in 1..6, "polls $polls for 40 addresses")
            device.close()
        }
    }

    /**
     * SPEC §5: comms loss sets bad quality within one scan period plus the request timeout,
     * and the last value is kept. A stalled peer (the response is held) is the case that
     * spends the timeout. Closing the socket is the case that fails inside the scan period.
     * The slack past scan+timeout is scheduling room for the test, not a second timeout.
     */
    @Test fun commsLossSetsBadQualityWithinScanPlusTimeoutThenRecovers() {
        val scan = 100L
        val timeout = 200L
        val budget = scan + timeout + 1_500L
        S7Simulator().use { sim ->
            sim.ensureDb(1, 64)
            sim.setBytes('D', 0, byteArrayOf(42), 1)
            val device = device(
                sim, S7Model.S7_300, timeoutMs = timeout, scanMs = scan,
                backoffInitialMs = 40, backoffMaxMs = 200,
            )
            device.start(); awaitConnected(device)
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            val sub = device.subscribe(listOf("DB1,B0"), scan.toDouble()) { seen += it }
            await("initial good") { seen.any { it.quality.code == QualityCode.Good && it.value == 42 } }

            val stalled = System.nanoTime()
            sim.holdReads = true
            await("bad after stall") {
                seen.any { it.value == 42 && it.quality.level == QualityLevel.BAD }
            }
            val stallMs = (System.nanoTime() - stalled) / 1_000_000
            val during = seen.last { it.value == 42 && it.quality.level == QualityLevel.BAD }
            assertEquals(42, during.value)
            assertTrue(
                during.quality.code == QualityCode.Bad_NotConnected || during.quality.code == QualityCode.Bad_Timeout,
                "quality ${during.quality.code}",
            )
            assertTrue(stallMs <= budget, "stall took ${stallMs}ms, budget ${budget}ms")

            sim.holdReads = false
            sim.stop()
            sim.start()
            val after = seen.size
            await("recovered from stall") {
                device.connectionStatus().connected &&
                    seen.drop(after).any { it.quality.code == QualityCode.Good && it.value == 42 }
            }

            val closed = System.nanoTime()
            sim.stop()
            await("bad after close") {
                seen.any { it.quality.code == QualityCode.Bad_NotConnected && it.value == 42 }
            }
            val closeMs = (System.nanoTime() - closed) / 1_000_000
            assertTrue(closeMs <= budget, "close took ${closeMs}ms, budget ${budget}ms")
            assertEquals(42, seen.last { it.quality.code == QualityCode.Bad_NotConnected }.value)
            sim.start()
            val afterClose = seen.size
            await("recovered from close") {
                device.connectionStatus().connected &&
                    seen.drop(afterClose).any { it.quality.code == QualityCode.Good && it.value == 42 }
            }
            assertEquals(ConnectionState.CONNECTED, device.connectionStatus().state)
            sub.close()
            device.close()
        }
    }

    /**
     * Figures for `Gateway/Device/<name>/Diagnostics`, the same set the Modbus and Logix drivers
     * report: requests and response time follow traffic, a stalled CPU counts a timeout and an
     * error, and sampling follows the fastest subscription.
     * doc source: FEATURES DEV-004
     */
    @Test fun diagnosticsFollowTrafficTimeoutsAndTheFastestScan() {
        S7Simulator().use { sim ->
            sim.ensureDb(1, 64)
            sim.setBytes('D', 0, byteArrayOf(5), 1)
            val device = device(sim, S7Model.S7_300, timeoutMs = 200, scanMs = 1_000, backoffInitialMs = 40, backoffMaxMs = 200)
            val idle = device.diagnostics()
            assertFalse(idle.connected)
            assertEquals(0L, idle.requestCount)
            assertEquals(null, idle.averageResponseTimeMs)
            assertEquals(1_000.0, idle.samplingIntervalMs)
            device.start(); awaitConnected(device)
            repeat(3) { assertEquals(5, device.read(listOf("DB1,B0")).single().value) }
            val busy = device.diagnostics()
            assertTrue(busy.connected)
            assertTrue(busy.requestCount >= 4, "requests ${busy.requestCount}")
            assertEquals(0L, busy.timeoutCount)
            assertTrue((busy.averageResponseTimeMs ?: -1.0) > 0.0, "average ${busy.averageResponseTimeMs}")

            val sub = device.subscribe(listOf("DB1,B0"), 100.0, 100.0) { }
            assertEquals(100.0, device.diagnostics().samplingIntervalMs)
            assertEquals(100.0, device.diagnostics().publishIntervalMs)
            sim.holdReads = true
            await("timeout counted") {
                val d = device.diagnostics()
                d.timeoutCount >= 1 && d.errorCount >= 1 && !d.connected
            }
            sim.holdReads = false
            sim.stop()
            sim.start()
            val before = device.diagnostics().requestCount
            await("recovered") { device.diagnostics().connected && device.diagnostics().requestCount > before }
            sub.close()
            assertEquals(1_000.0, device.diagnostics().samplingIntervalMs)
            device.close()
        }
    }

    @Test fun reconnectBackoffGrowsUntilTheCap() {
        val device = S7Device(S7DeviceConfig(
            name = "down", host = "127.0.0.1", port = 1, timeoutMs = 200,
            backoffInitialMs = 40, backoffMaxMs = 160, backoffMultiplier = 2.0,
        ))
        device.start()
        await("three attempts") { device.reconnectDelaysMs.size >= 3 }
        val delays = device.reconnectDelaysMs
        assertTrue(delays[0] in 32L..48L, "delay0=${delays[0]}")
        assertTrue(delays[1] in 64L..96L, "delay1=${delays[1]}")
        assertTrue(delays[2] in 128L..160L, "delay2=${delays[2]}")
        assertTrue(delays[0] < delays[1] && delays[1] < delays[2])
        assertEquals(ConnectionState.RECONNECTING, device.connectionStatus().state)
        assertFalse(device.connectionStatus().connected)
        device.close()
    }

    @Test fun throughputAgainstTheSimulator() {
        S7Simulator(maxPdu = 960).use { sim ->
            sim.ensureDb(1, 8192)
            val raw = ByteArray(4000)
            for (i in 0 until 2000) {
                raw[i * 2] = (i ushr 8).toByte()
                raw[i * 2 + 1] = (i and 0xFF).toByte()
            }
            sim.setBytes('D', 0, raw, 1)
            val device = device(sim, S7Model.S7_1500, pduSize = 960, timeoutMs = 2_000)
            device.start(); awaitConnected(device)
            val addresses = (0 until 2000).map { "DB1,W${it * 2}" }
            val warm = device.read(addresses)
            assertEquals(0, warm.first().value)
            assertEquals(1999, warm.last().value)
            assertEquals(QualityCode.Good, warm.last().quality.code)
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
            println("BENCH s7 in-process values/s=${"%.0f".format(rate)} calls=$calls seconds=${"%.2f".format(seconds)} readJobs=${device.readRequestCount} pdu=${device.negotiatedPduSize}")
            assertTrue(rate >= 3_000.0, "throughput $rate values/s")
            device.close()
        }
    }

    @TempDir lateinit var tmp: Path

    /** DEV-026: a symbol-addressed tag reads and writes through the device's imported symbol table. */
    @Test fun symbolAddressedTagsReadAndWriteAndAnUnknownSymbolIsBadNotFatal() {
        S7Simulator().use { sim ->
            sim.ensureDb(10, 64)
            sim.setBytes('D', 0, byteArrayOf(0x42, 0x48, 0x00, 0x00), db = 10) // 50.0f
            sim.setBytes('D', 4, byteArrayOf(0x01), db = 10)
            val spec = mapOf(
                "host" to "127.0.0.1", "port" to sim.port, "model" to "S7_1500", "slot" to 1,
                "timeoutMs" to 1000, "backoffInitialMs" to 40, "backoffMaxMs" to 400, "defaultScanPeriodMs" to 50,
                "symbolTable" to "block,number,member,type,offset\nMotorData,10,Speed,REAL,0\nMotorData,10,Motor.Running,BOOL,4.0\nMotorData,10,Setpoint,INT,6\n",
            )
            val device = S7Device(s7DeviceConfig("sim", spec))
            device.start(); awaitConnected(device)

            val (speed, running, unknown, absolute) = device.read(
                listOf("\"MotorData\".Speed", "DB10.Motor.Running", "\"MotorData\".Torque", "DB10.DBD0"),
            )
            assertEquals(50.0f, speed.value)
            assertEquals(QualityCode.Good, speed.quality.code)
            assertEquals("\"MotorData\".Speed", speed.address)
            assertEquals(true, running.value)
            assertEquals(QualityCode.Bad_ConfigurationError, unknown.quality.code)
            assertEquals("unknown symbol '\"MotorData\".Torque': no member 'Torque' in DB10", unknown.quality.diagnostic)
            assertEquals(0x42480000L, absolute.value)

            assertEquals(QualityCode.Good, device.write("\"MotorData\".Setpoint", -300).quality.code)
            assertArrayEquals(byteArrayOf(0xFE.toByte(), 0xD4.toByte()), sim.getBytes('D', 6, 2, 10))
            assertEquals(QualityCode.Bad_ConfigurationError, device.write("\"MotorData\".Torque", 1).quality.code)

            // A subscription that mixes a good symbol and an unknown one keeps the good one live.
            val good = CopyOnWriteArrayList<QualifiedValue>()
            val bad = CopyOnWriteArrayList<QualifiedValue>()
            device.subscribe(listOf("\"MotorData\".Speed", "\"Pump\".Speed"), "Default") { v ->
                if (v.address.startsWith("\"Pump")) bad += v else good += v
            }.use {
                sim.setBytes('D', 0, byteArrayOf(0x42, 0xC8.toByte(), 0x00, 0x00), db = 10) // 100.0f
                await("symbol update") { good.any { it.value == 100.0f && it.quality.code == QualityCode.Good } }
                await("unknown symbol quality") { bad.any { it.quality.code == QualityCode.Bad_ConfigurationError } }
                assertTrue(bad.none { it.quality.code == QualityCode.Good })
                assertTrue(device.connectionStatus().connected)
            }
            device.close()
        }
    }

    @Test fun deviceSpecAndModuleRoundTrip() {
        val registry = SchemaRegistry()
        registry.register(S7DriverModule.MODULE_ID, DEVICE_KIND, s7DeviceSchema())
        val spec = mapOf(
            "host" to "10.0.0.8",
            "model" to "S7_1500",
            "port" to 102,
            "pduSize" to 480,
            "rack" to 0,
            "slot" to 1,
            "timeoutMs" to 2000,
        )
        val applied = registry.applyDefaults(S7DriverModule.MODULE_ID, DEVICE_KIND, spec)
        val config = s7DeviceConfig("line", applied)
        assertEquals("line", config.name)
        assertEquals(S7Model.S7_1500, config.model)
        assertEquals(480, config.pduSize)
        assertEquals(1, config.slot)
        assertEquals(480, applied["pduSize"])
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(DEVICE_KIND, Metadata("line", S7DriverModule.MODULE_ID), spec + ("pduSize" to 100)))
        }
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(DEVICE_KIND, Metadata("line", S7DriverModule.MODULE_ID), spec + ("nope" to true)))
        }
        val file = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n").trim()
        assertEquals(file, S7DriverModule.MANIFEST_YAML.trim())

        val id = S7DriverModule.MODULE_ID
        val modules = tmp.resolve("modules")
        modules.resolve("driver-s7").createDirectories().resolve("module.yaml").writeText(javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n"))
        val configDir = tmp.resolve("config")
        val store = FileConfigStore(configDir, SchemaRegistry())
        val host = ModuleHost(modules, configDir, store = store)
        host.installAll()
        assertEquals(ModuleState.INSTALLED, host.state(id))
        assertEquals(2000, store.list(module = id).single().resource.spec["defaultTimeoutMs"])
        host.start(id)
        val module = host.instance(id) as S7DriverModule
        assertEquals(ModuleState.RUNNING, host.state(id))
        host.stop(id)
        host.uninstall(id)
        assertTrue(host.installed().isEmpty())
        assertEquals(0, store.list(module = id).size)
        assertFalse(configDir.resolve(id).exists())
        assertTrue(module.device("none") == null)
    }

    private fun connect(
        sim: S7Simulator,
        model: S7Model,
        rack: Int = 0,
        slot: Int = 2,
        localTsap: Int? = null,
        remoteTsap: Int? = null,
    ): S7Device {
        val device = device(sim, model, rack = rack, slot = slot, localTsap = localTsap, remoteTsap = remoteTsap)
        device.start()
        awaitConnected(device)
        return device
    }

    private fun device(
        sim: S7Simulator,
        model: S7Model,
        pduSize: Int = 240,
        timeoutMs: Long = 1_000,
        scanMs: Long = 1_000,
        rack: Int = 0,
        slot: Int = 2,
        localTsap: Int? = null,
        remoteTsap: Int? = null,
        backoffInitialMs: Long = 40,
        backoffMaxMs: Long = 400,
    ) = S7Device(S7DeviceConfig(
        name = "sim", host = "127.0.0.1", port = sim.port, model = model,
        timeoutMs = timeoutMs, pduSize = pduSize, rack = rack, slot = slot,
        localTsap = localTsap, remoteTsap = remoteTsap,
        backoffInitialMs = backoffInitialMs, backoffMaxMs = backoffMaxMs,
        defaultScanPeriodMs = scanMs, spanGaps = true, maxGap = 0,
    ))

    private fun awaitConnected(device: S7Device) {
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
