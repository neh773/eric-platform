package org.oip.testinfra

import org.junit.jupiter.api.AfterAll
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
import org.oip.opcua.OpcUaConnection
import org.oip.opcua.OpcUaConnectionConfig
import org.oip.opcua.QualityCode as OpcQuality
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.BitSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PRF-009 and PRF-010 (ADR 0010) on a Docker bridge (`docker-bridge`). Loopback does not count.
 *
 * CI defaults are a 4 s Modbus window and a 2 s OPC UA hold, both at the full floors
 * (10_000 values/s, 50_000 monitored items). A longer soak is
 * `-Pprf009Seconds=60 -Pprf010HoldSeconds=30`. See test-infra/driver-floors/README.md.
 *
 * Accepted baselines (higher is better), also `result.baseline` in test-infra/perf/perf-requirements.json:
 * - PRF-009 valuesPerSecond 499979
 * - PRF-010 goodMonitoredItems 50000
 * PRF-013 fails a later same-hardware result only when it is more than 15% worse.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(value = 12, unit = TimeUnit.MINUTES)
class DriverFloorTest {
    private lateinit var sim: BenchSim

    @BeforeAll fun up() {
        val image = System.getProperty("prf.simImage") ?: "oip-bench-points:ci"
        sim = BenchSim.start(image)
    }

    @AfterAll fun down() {
        if (::sim.isInitialized) sim.close()
    }

    @Test @Order(1)
    fun modbusConnectionHoldsTenThousandValuesPerSecond() {
        assertContainerPath(sim)
        val seconds = (System.getProperty("prf009.seconds") ?: "4").toInt().coerceIn(2, 180)
        val points = 10_000
        val periodMs = 20L
        val concurrent = 8
        Socket().use { probe ->
            probe.connect(InetSocketAddress(sim.ip, MODBUS_PORT), 5_000)
            assertFalse(probe.localAddress.isLoopbackAddress, "local ${probe.localAddress.hostAddress}")
            assertFalse(probe.inetAddress.isLoopbackAddress, "peer ${probe.inetAddress.hostAddress}")
        }
        val device = ModbusDevice(ModbusDeviceConfig(
            name = "prf-009",
            host = sim.ip,
            port = MODBUS_PORT,
            mode = ModbusMode.TCP,
            unitId = 1,
            timeoutMs = 2_000,
            concurrentRequests = concurrent,
            defaultScanPeriodMs = periodMs,
            maxRetryCount = 0,
            spanGaps = true,
            maxHoldingRegistersPerRead = 125,
        ))
        device.use {
            it.start()
            await("modbus connected ${it.connectionStatus()}") { it.connectionStatus().connected }
            val armed = AtomicBoolean(false)
            val good = AtomicLong()
            val bad = AtomicInteger()
            val handles = ArrayList<AutoCloseable>(points)
            for (i in 1..points) {
                handles += it.attachRaw(periodMs, "HR$i") { _, quality, _ ->
                    if (!armed.get()) return@attachRaw
                    if (quality.code == ModbusQuality.Good) good.incrementAndGet() else bad.incrementAndGet()
                }
            }
            await("first coalesced scan") { it.readRequestCount >= 80 }
            val spot = it.read(listOf("HR1", "HR2", "HR1000", "HR1001", "HR10000"))
            assertTrue(spot.all { sample -> sample.quality.code == ModbusQuality.Good }, "spot $spot\n${sim.tail()}")
            assertEquals(0, spot[0].value)
            assertEquals(1, spot[1].value)
            assertEquals(999, spot[2].value)
            assertEquals(0, spot[3].value)
            assertEquals(999, spot[4].value)
            armed.set(true)
            val pdu0 = it.readRequestCount
            val start = System.nanoTime()
            Thread.sleep(seconds * 1_000L)
            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            val samples = good.get()
            val badSamples = bad.get()
            val pdus = it.readRequestCount - pdu0
            val rate = samples / elapsed
            val perPdu = if (pdus == 0L) 0.0 else samples.toDouble() / pdus
            println(
                "PRF-009 valuesPerSecond=${"%.0f".format(rate)} samples=$samples bad=$badSamples " +
                    "pdus=$pdus valuesPerPdu=${"%.1f".format(perPdu)} peakInFlight=${it.peakInFlight} " +
                    "concurrentRequests=$concurrent points=$points periodMs=$periodMs seconds=${"%.2f".format(elapsed)} " +
                    "peer=${sim.ip} network=${sim.driver}",
            )
            assertEquals(0, badSamples, "bad quality during the steady window\n${sim.tail()}")
            assertTrue(pdus > 0, "no read PDUs")
            assertTrue(perPdu >= 100.0, "PDU coalescing values/pdu $perPdu")
            assertTrue(it.peakInFlight >= concurrent, "pipelined in-flight ${it.peakInFlight} < $concurrent")
            assertTrue(rate >= 10_000.0, "PRF-009 floor $rate values/s")
            assertFalse(
                regressionFails(MODBUS_BASELINE_VALUES_PER_SECOND.toDouble(), rate, lowerIsBetter = false),
                "PRF-013 values/s $rate vs baseline $MODBUS_BASELINE_VALUES_PER_SECOND",
            )
            handles.forEach { handle -> handle.close() }
        }
    }

    @Test @Order(2)
    fun opcUaGatewayHoldsFiftyThousandMonitoredItems() {
        assertContainerPath(sim)
        val items = 50_000
        val holdSeconds = (System.getProperty("prf010.holdSeconds") ?: "2").toInt().coerceIn(1, 180)
        val conn = OpcUaConnection(OpcUaConnectionConfig(
            name = "prf-010",
            endpointUrl = "opc.tcp://${sim.ip}:$OPC_PORT/oip/bench",
            requestTimeoutMs = 60_000,
            sessionTimeoutMs = 120_000,
        ))
        conn.use {
            val t0 = System.nanoTime()
            it.connect()
            val good = AtomicInteger()
            val bad = AtomicInteger()
            val seen = BitSet(items)
            val spot = IntArray(items) { -1 }
            val watch = intArrayOf(0, 1, 999, 49_999)
            val handles = ArrayList<AutoCloseable>(items)
            for (i in 0 until items) {
                val node = "ns=1;i=${OPC_NODE_BASE + i}"
                handles += it.watch(node, 1_000.0) { sample ->
                    if (sample.quality.code != OpcQuality.Good) {
                        bad.incrementAndGet()
                        return@watch
                    }
                    val number = (sample.value as? Number)?.toInt() ?: return@watch
                    if (i == watch[0] || i == watch[1] || i == watch[2] || i == watch[3]) spot[i] = number
                    synchronized(seen) {
                        if (!seen.get(i)) {
                            seen.set(i)
                            good.incrementAndGet()
                        }
                    }
                }
            }
            it.flushWatches()
            await("50_000 good monitored items (good=${good.get()} bad=${bad.get()} count=${it.monitoredItemCount})") {
                good.get() >= items && it.monitoredItemCount >= items
            }
            val createMs = (System.nanoTime() - t0) / 1_000_000
            Thread.sleep(holdSeconds * 1_000L)
            val goodItems = good.get()
            val badItems = bad.get()
            val count = it.monitoredItemCount
            val syncs = it.monitoredItemSyncs
            println(
                "PRF-010 goodMonitoredItems=$goodItems monitoredItemCount=$count syncs=$syncs bad=$badItems " +
                    "createMs=$createMs holdSeconds=$holdSeconds peer=${sim.ip} network=${sim.driver}",
            )
            assertEquals(0, badItems, "bad quality on a monitored item\n${sim.tail()}")
            assertTrue(goodItems >= 50_000, "good items $goodItems")
            assertTrue(count >= 50_000, "monitoredItemCount $count")
            assertTrue(syncs >= 1 && syncs * 100 <= items, "subscription batches syncs=$syncs items=$items")
            assertEquals(0, spot[0])
            assertEquals(1, spot[1])
            assertEquals(999, spot[999])
            assertEquals(999, spot[49_999])
            val again = it.read(listOf("ns=1;i=${OPC_NODE_BASE}", "ns=1;i=${OPC_NODE_BASE + 49_999}"))
            assertEquals(OpcQuality.Good, again[0].quality.code)
            assertEquals(OpcQuality.Good, again[1].quality.code)
            assertEquals(0, (again[0].value as Number).toInt())
            assertEquals(999, (again[1].value as Number).toInt())
            assertFalse(
                regressionFails(OPC_BASELINE_MONITORED_ITEMS.toDouble(), goodItems.toDouble(), lowerIsBetter = false),
                "PRF-013 monitored items $goodItems vs baseline $OPC_BASELINE_MONITORED_ITEMS",
            )
            handles.forEach { handle -> handle.close() }
        }
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 120_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("$message\n${sim.tail()}")
    }

    private companion object {
        const val MODBUS_PORT = 5020
        const val OPC_PORT = 4840
        const val OPC_NODE_BASE = 1_000_000
        /** PRF-009 result.baseline. Higher is better. */
        const val MODBUS_BASELINE_VALUES_PER_SECOND = 499979
        /** PRF-010 result.baseline. Higher is better. */
        const val OPC_BASELINE_MONITORED_ITEMS = 50000
    }
}

/** Throughput falling is worse. More than 15% worse fails; exactly 15% does not (PRF-013). */
private fun regressionFails(baseline: Double, measured: Double, lowerIsBetter: Boolean): Boolean {
    val change = (measured - baseline) / kotlin.math.abs(baseline) * 100.0
    val worse = if (lowerIsBetter) change else -change
    return worse > 15.0
}

private fun assertContainerPath(sim: BenchSim) {
    val addr = InetAddress.getByName(sim.ip)
    assertFalse(addr.isLoopbackAddress, sim.ip)
    assertFalse(addr.isAnyLocalAddress, sim.ip)
    assertFalse(sim.ip.startsWith("127."), sim.ip)
    assertEquals("bridge", sim.driver, "container network driver")
}

/** Bench point simulator on a user-defined bridge. No host port is published. */
private class BenchSim(
    val ip: String,
    val driver: String,
    private val container: String,
    private val network: String,
) : AutoCloseable {
    fun tail(): String = docker(false, "logs", "--tail", "40", container)

    override fun close() {
        docker(false, "rm", "-f", container)
        docker(false, "network", "rm", network)
    }

    companion object {
        fun start(image: String): BenchSim {
            val suffix = ProcessHandle.current().pid()
            val network = "oip-prf-floors-$suffix"
            val container = "oip-prf-sim-$suffix"
            val listed = docker(true, "images", "-q", image).trim()
            check(listed.isNotEmpty()) {
                "missing image $image. Build it with: docker build -t $image -f bench/sim/Dockerfile bench"
            }
            docker(true, "network", "create", network)
            try {
                docker(
                    true, "run", "-d", "--name", container, "--network", network,
                    "-e", "POINTS=50000",
                    "-e", "PERCENT_CHANGING=0",
                    "-e", "REGISTERS_PER_UNIT=10000",
                    "-e", "MODBUS_EXTRA_PORTS=0",
                    image,
                    "python", "-u", "/app/server.py",
                    "--no-sparkplug", "--points", "50000", "--percent", "0",
                    "--registers-per-unit", "10000", "--modbus-extra-ports", "0",
                    "--modbus-port", "5020", "--opcua-port", "4840",
                )
                val deadline = System.nanoTime() + 60_000_000_000L
                var logs = ""
                while (System.nanoTime() < deadline) {
                    logs = docker(false, "logs", container)
                    if (logs.contains("READY")) break
                    Thread.sleep(200)
                }
                check(logs.contains("READY")) { "simulator did not become ready\n$logs" }
                val ip = docker(
                    true, "inspect", "-f",
                    "{{(index .NetworkSettings.Networks \"$network\").IPAddress}}",
                    container,
                ).trim()
                val driver = docker(true, "network", "inspect", "-f", "{{.Driver}}", network).trim()
                return BenchSim(ip, driver, container, network)
            } catch (e: Exception) {
                docker(false, "rm", "-f", container)
                docker(false, "network", "rm", network)
                throw e
            }
        }
    }
}

private fun docker(check: Boolean, vararg args: String): String {
    val proc = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
    val text = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    if (check && code != 0) error("docker ${args.joinToString(" ")} exited $code\n$text")
    return text
}
