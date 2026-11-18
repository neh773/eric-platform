package org.oip.modbus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Device diagnostic counters move with traffic and with a lost socket, and recording one
 * sample is an atomic add.
 * Doc source: FEATURES DEV-007.
 */
class ModbusDiagnosticsTest {
    @Test fun diagnosticSnapshotFollowsTrafficAndCommsLoss() {
        TestSlave(ModbusMode.TCP).use { slave ->
            val device = device(slave)
            device.start()
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            val sub = device.subscribe(listOf("HR1"), 200.0, 200.0) { seen += it }
            try {
                await("connected and scanning") {
                    val diag = device.diagnostics()
                    diag.connected && diag.requestCount >= 2 && seen.any { it.quality.code == QualityCode.Good }
                }
                val live = device.diagnostics()
                assertEquals(200.0, live.samplingIntervalMs)
                assertEquals(200.0, live.publishIntervalMs)
                val average = live.averageResponseTimeMs
                assertTrue(average != null && average > 0.0 && average < 500.0, "average $average")
                val requests = live.requestCount
                val errors = live.errorCount
                slave.stop()
                await("disconnected after the slave stops") {
                    val diag = device.diagnostics()
                    !diag.connected && diag.errorCount > errors
                }
                slave.start()
                await("scanning again after the slave returns") {
                    val diag = device.diagnostics()
                    diag.connected && diag.requestCount > requests
                }
            } finally {
                sub.close()
                device.close()
            }
        }
    }

    @Test fun aHeldSocketCountsAsATimeout() {
        ServerSocket(0).use { hole ->
            hole.reuseAddress = true
            val held = CopyOnWriteArrayList<Socket>()
            val accept = Thread({
                while (!hole.isClosed) {
                    val socket = try {
                        hole.accept()
                    } catch (_: Exception) {
                        break
                    }
                    held += socket
                }
            }, "modbus-hold").apply { isDaemon = true; start() }
            val device = ModbusDevice(ModbusDeviceConfig(
                name = "hold", host = "127.0.0.1", port = hole.localPort,
                timeoutMs = 200, reconnectAfterConsecutiveTimeouts = 3,
                backoffInitialMs = 20, backoffMaxMs = 40, maxRetryCount = 0,
            ))
            device.start()
            val sub = device.subscribe(listOf("HR1"), 200.0, 200.0) { }
            try {
                await("timeout counted") { device.diagnostics().timeoutCount >= 1 }
                val diag = device.diagnostics()
                // The socket stays up until the consecutive-timeout limit, so connected may still be true.
                assertTrue(diag.errorCount >= diag.timeoutCount, "errors ${diag.errorCount} timeouts ${diag.timeoutCount}")
            } finally {
                sub.close()
                device.close()
                held.forEach { runCatching { it.close() } }
                hole.close()
                accept.join(1_000)
            }
        }
    }

    @Test fun recordingASampleIsAnAtomicAdd() {
        val stats = ResponseStats()
        val n = 200_000
        val start = System.nanoTime()
        repeat(n) {
            stats.noteRequest()
            stats.noteSample(1_500L)
        }
        val per = (System.nanoTime() - start) / n
        println("BENCH modbus diagnostic record ns/op=$per")
        // A scan is at least 10 ms. A few microseconds of atomics cannot add lag on that path.
        assertTrue(per < 10_000, "ns/op $per")
        assertEquals(n.toLong(), stats.requestCount())
        assertTrue(stats.averageMillis()!! in 0.001..0.003)
    }

    private fun device(slave: TestSlave) = ModbusDevice(ModbusDeviceConfig(
        name = "sim", host = "127.0.0.1", port = slave.port,
        timeoutMs = 500, reconnectAfterConsecutiveTimeouts = 1,
        backoffInitialMs = 30, backoffMaxMs = 80, backoffMultiplier = 2.0,
        defaultScanPeriodMs = 200, maxRetryCount = 0,
    ))

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        var last: Throwable? = null
        while (System.nanoTime() < end) {
            try {
                if (cond()) return
            } catch (e: Throwable) {
                last = e
            }
            Thread.sleep(20)
        }
        throw AssertionError(message, last)
    }
}
