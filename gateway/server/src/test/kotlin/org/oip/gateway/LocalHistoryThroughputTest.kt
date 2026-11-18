package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.historian.HistoryQuery
import org.oip.tags.TagSample
import java.nio.file.Path
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.writeText

/**
 * PRF-006 local history on a live [Gateway] with the edge profile: 88,500 device tags change every second and each
 * has a `historian/HistorySettings` (onChange, provider `local`), so 88,500 rows/s go through the gateway's own
 * [org.oip.historian.HistoryCollector], [org.oip.historian.HistoryRunner], [org.oip.historian.HistoryRouter] and the
 * embedded historian. Over a sustained window the collector must drop nothing and its queue must not grow: the
 * writer keeps up instead of falling behind (it wrote about 75,000 rows/s at container size before the PRF-006
 * write path. The edge report is not stored in this tree.)
 *
 * The tags are `opc` tags whose values this test sets through [TagSample.update], the call a driver makes, so no
 * device and no memory-tag persistence take CPU. Runs in its own JVM ([localHistoryThroughputTest] in
 * gateway/server/build.gradle.kts), with the edge heap (`-Xmx2g`).
 */
class LocalHistoryThroughputTest {
    @TempDir lateinit var tmp: Path

    private fun tagName(i: Int) = "t%06d".format(i)

    @Test
    fun localHistoryKeepsUpWithEveryTagChangingEverySecond() {
        tmp.resolve("gateway.yaml").writeText("profile: edge\nopcua:\n  enabled: false\n")
        Gateway(tmp, port = 0).use { gw ->
            gw.start()
            var offset = 0
            while (offset < TAGS) {
                val end = minOf(offset + BATCH, TAGS)
                gw.store.putAll(Scope.GATEWAY, (offset until end).map { i ->
                    Resource("Tag", Metadata(tagName(i), "tags"), mapOf(
                        "provider" to "default", "path" to "bench/${tagName(i)}", "type" to "opc", "dataType" to "Int32",
                        "opcServer" to "history-throughput", "opcItemPath" to "ns=1;i=$i", "scanPeriodMillis" to 1000,
                    ))
                }, actor = "test", syncEach = false)
                gw.store.putAll(Scope.GATEWAY, (offset until end).map { i ->
                    Resource("HistorySettings", Metadata("bench-${tagName(i)}", "historian"), mapOf(
                        "tagPath" to "[default]bench/${tagName(i)}", "sampleMode" to "onChange", "storageProvider" to "local",
                    ))
                }, actor = "test", syncEach = false)
                offset = end
            }
            val collector = gw.historyCollector
            assertEquals(TAGS, collector.settings().size)
            val samples: Array<TagSample> = Array(TAGS) { gw.tags.openSample("[default]bench/${tagName(it)}") }

            data class Point(val scans: Int, val written: Long, val dropped: Long, val pending: Int)
            val points = ArrayList<Point>()
            val start = System.nanoTime()
            var scan = 0
            val total = WARMUP_SCANS + WINDOW_SCANS
            while (scan < total) {
                val value = scan + 1
                for (s in samples) s.update(value)
                scan++
                if (scan >= WARMUP_SCANS) points += Point(scan, collector.written, collector.dropped, collector.pending)
                LockSupport.parkNanos(start + scan * 1_000_000_000L - System.nanoTime())
            }
            val window = points.drop(1)
            val first = points.first()
            val last = points.last()
            val seconds = (last.scans - first.scans).toDouble()
            val rate = (last.written - first.written) / seconds
            val halves = listOf(window.take(window.size / 2), window.drop(window.size / 2))
            println(
                "LocalHistoryThroughput tags=$TAGS window=${seconds}s written=${last.written - first.written} rows/s=${"%.0f".format(rate)} " +
                    "dropped=${last.dropped} pending min/max per half=${halves.map { h -> h.minOf { it.pending } to h.maxOf { it.pending } }} " +
                    "pendingEnd=${last.pending} historian=${gw.historyStore.status().filterKeys { it in setOf("waitingRows", "merges", "mergedRows", "lastMergeError") }}"
            )
            assertEquals(0L, last.dropped, "the collector dropped rows")
            assertEquals(0, collector.consecutiveFlushFailures, collector.lastFlushError)
            assertTrue(rate >= TAGS * 0.95, "wrote $rate rows/s against $TAGS changes/s")
            // The queue does not grow: in each half of the window it drains to at most one scan of changes (a writer
            // that falls behind never gets back there), and while a merge holds the writer it stays far from full.
            for ((n, h) in halves.withIndex()) assertTrue(h.minOf { it.pending } <= TAGS, "half ${n + 1}: the queue never drained, ${h.map { it.pending }}")
            assertTrue(window.maxOf { it.pending } <= collector.queueCapacity / 2, "queue peak ${window.maxOf { it.pending }} of ${collector.queueCapacity}")

            // Every change is stored once: the first and last tag each have one row per scan.
            val deadline = System.nanoTime() + 60_000_000_000L
            while (collector.pending > 0 && System.nanoTime() < deadline) Thread.sleep(100)
            assertEquals(0, collector.pending)
            for (i in listOf(0, TAGS - 1)) {
                val rows = gw.historyRouter.query(HistoryQuery("[default]bench/${tagName(i)}", Long.MIN_VALUE, Long.MAX_VALUE, limit = total + 10))
                val values = rows.filter { it.value != null }
                assertEquals((1..total).toList(), values.map { (it.value as Number).toInt() }, "tag $i")
                // Besides those, at most the tag's first value (no value yet) when collection started.
                assertTrue(rows.size - values.size <= 1, "tag $i: ${rows.take(3)}")
            }
        }
    }

    private companion object {
        const val TAGS = 88_500
        const val BATCH = 2_000
        const val WARMUP_SCANS = 15
        const val WINDOW_SCANS = 60
    }
}
