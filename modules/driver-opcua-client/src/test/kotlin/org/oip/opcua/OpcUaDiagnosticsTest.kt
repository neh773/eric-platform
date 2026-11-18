package org.oip.opcua

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * OPC UA connection diagnostics move with subscription traffic and a dropped channel.
 * A node the server refuses is not a monitored item.
 * Doc source: FEATURES DEV-007, DEV-016.
 */
class OpcUaDiagnosticsTest {
    @TempDir lateinit var tmp: Path

    @Test fun diagnosticSnapshotFollowsTrafficAndCommsLoss() {
        SimServer(tmp.resolve("pki-diag")).use { server ->
            TcpProxy(server.port).use { proxy ->
                val conn = connection(server, proxy)
                val seen = CopyOnWriteArrayList<Int>()
                conn.watch(server.nodeId("Counter"), 200.0) { value ->
                    if (value.quality.code == QualityCode.Good && value.value is Number) {
                        seen += (value.value as Number).toInt()
                    }
                }
                try {
                    conn.connect()
                    conn.flushWatches()
                    await("subscribed") { conn.diagnostics().connected && conn.monitoredItemCount == 1 }
                    val before = conn.diagnostics().requestCount
                    server.setCounter(5)
                    await("value delivered") { seen.any { it == 5 } }
                    val live = conn.diagnostics()
                    assertTrue(live.requestCount > before, "requests ${live.requestCount} before $before")
                    val average = live.averageResponseTimeMs
                    assertTrue(average != null && average > 0.0, "average $average")
                    assertTrue((live.samplingIntervalMs ?: 0.0) in 50.0..1_000.0, "sampling ${live.samplingIntervalMs}")
                    assertTrue((live.publishIntervalMs ?: 0.0) > 0.0, "publish ${live.publishIntervalMs}")
                    assertEquals(1, live.subscriptionCount)
                    assertEquals(1, live.monitoredItemCount)
                    conn.refreshServerState()
                    assertEquals("Running", conn.diagnostics().serverState)
                    assertTrue(!conn.diagnostics().sessionId.isNullOrBlank(), "session ${conn.diagnostics().sessionId}")
                    proxy.drop()
                    await("disconnected") { !conn.diagnostics().connected }
                    proxy.resume()
                    await("recovered") { conn.diagnostics().connected && conn.monitoredItemCount == 1 }
                } finally {
                    conn.close()
                }
            }
        }
    }

    @Test fun aMonitoredItemTheServerRejectsIsNotCounted() {
        SimServer(tmp.resolve("pki-refused")).use { server ->
            val conn = connection(server, null)
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            conn.watch(server.nodeId("Counter"), 100.0) { }
            conn.watch(server.nodeId("DoesNotExist"), 100.0) { seen += it }
            try {
                conn.connect()
                conn.flushWatches()
                await("refusal reported") { seen.any { it.quality.statusCode == 0x80340000L } }
                await("one item on the server") { conn.monitoredItemCount == 1 }
                Thread.sleep(400)
                val diag = conn.diagnostics()
                assertEquals(1, diag.monitoredItemCount)
                assertEquals(1, diag.subscriptions.sumOf { it.monitoredItemCount })
                assertEquals(1, conn.monitoredItemCount)
            } finally {
                conn.close()
            }
        }
    }

    @Test fun recordingANotificationIsAnAtomicAdd() {
        val stats = ResponseStats()
        val n = 200_000
        val start = System.nanoTime()
        repeat(n) { stats.noteNotification() }
        val per = (System.nanoTime() - start) / n
        println("BENCH opcua diagnostic noteNotification ns/op=$per")
        // A subscription callback is already a network delivery. A few microseconds cannot add lag.
        assertTrue(per < 10_000, "ns/op $per")
        assertEquals(n.toLong(), stats.trafficCount())
    }

    private fun connection(server: SimServer, proxy: TcpProxy?) = OpcUaConnection(OpcUaConnectionConfig(
        name = "sim",
        endpointUrl = server.endpointUrl,
        connectHost = if (proxy != null) "127.0.0.1" else null,
        connectPort = proxy?.port ?: -1,
        requestTimeoutMs = 5_000,
        keepAliveIntervalMs = 1_000,
        keepAliveTimeoutMs = 1_000,
        keepAliveFailuresAllowed = 1,
    ))

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
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
