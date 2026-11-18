package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.Quality
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

/**
 * The embedded historian's write path (PRF-006): rows wait in `eh_hot` and are merged into their partitions later.
 * Every read must give the same answer whether a row is still waiting, already merged, or merged while the read runs,
 * and deduplication must hold across a merge.
 */
class EmbeddedHistorianMergeTest {
    @TempDir lateinit var tmp: Path

    private val day = PartitionInterval.DAY_MILLIS
    private val t0 = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
    private val settings = EmbeddedHistorianSettings(partitionInterval = PartitionInterval.DAY)

    private fun open(name: String, mergeRows: Int) =
        EmbeddedHistorian(tmp.resolve(name), settings, mergeRows = mergeRows, mergeRowsPerTag = 0, mergeAgeMillis = Long.MAX_VALUE)

    private fun open(name: String, mergeRows: Int, mergeChunkRows: Int) =
        EmbeddedHistorian(tmp.resolve(name), settings, mergeRows = mergeRows, mergeRowsPerTag = 0, mergeAgeMillis = Long.MAX_VALUE, mergeChunkRows = mergeChunkRows)

    private val tagPaths = listOf("[default]A", "[default]B", "[default]C")

    /** Out-of-order, same-millisecond, duplicate and cross-partition rows of every value kind. */
    private fun workload(seed: Int): List<List<HistorySample>> {
        val r = Random(seed)
        val values = listOf<Any?>(1.0, 2.5, -0.0, 0.0, Double.NaN, 7, 7L, true, false, "x", null, 3.0, 4.0)
        val qualities = listOf(Quality.GOOD, Quality.GOOD, Quality.GOOD, Quality.BAD, Quality.UNCERTAIN)
        val written = ArrayList<HistorySample>()
        return List(60) {
            List(r.nextInt(1, 12)) {
                if (written.isNotEmpty() && r.nextInt(6) == 0) {
                    written[r.nextInt(written.size)] // an exact duplicate of an earlier row
                } else {
                    val t = t0 + r.nextLong(0, 3 * day / 1000) * 1000 + if (r.nextBoolean()) 0 else r.nextLong(0, 3)
                    HistorySample(tagPaths[r.nextInt(tagPaths.size)], t, values[r.nextInt(values.size)], qualities[r.nextInt(qualities.size)])
                }.also { written += it }
            }
        }
    }

    private fun readAll(h: EmbeddedHistorian): List<Any?> {
        val out = ArrayList<Any?>()
        val probes = listOf(t0, t0 + 1, t0 + day / 2, t0 + day, t0 + day + 1000, t0 + 2 * day + 7, t0 + 3 * day, Long.MAX_VALUE)
        for (path in tagPaths) {
            for (limit in listOf(1, 3, 10, 10_000)) out.add(h.query(HistoryQuery(path, Long.MIN_VALUE, Long.MAX_VALUE, limit)))
            out.add(h.query(HistoryQuery(path, t0 + day / 3, t0 + 2 * day)))
            val scanned = ArrayList<HistorySample>()
            h.scan(path, t0 + 1000, t0 + 3 * day) { scanned += it }
            out.add(scanned)
            for (p in probes) out.add(h.latestBefore(path, p))
            for (mode in HistoryAggregate.entries) for (interval in listOf(3_600_000L, day)) {
                out.add(h.aggregateNative(path, t0, t0 + 3 * day - 1, interval, mode, HistoryInterpolation.NONE))
            }
        }
        out.add(h.latestAll().toSortedMap())
        return out
    }

    @Test
    fun readsAreTheSameWhetherRowsWaitOrAreMerged() {
        for (seed in 1..8) {
            val batches = workload(seed)
            val merged = open("merged-$seed.sqlite", mergeRows = 1)
            val waiting = open("waiting-$seed.sqlite", mergeRows = Int.MAX_VALUE)
            val mixed = open("mixed-$seed.sqlite", mergeRows = 7)
            // Each merge runs as one step per tag, between appends.
            val chunked = open("chunked-$seed.sqlite", mergeRows = 7, mergeChunkRows = 1)
            for (b in batches) {
                merged.appendAll(b)
                waiting.appendAll(b)
                mixed.appendAll(b)
                chunked.appendAll(b)
            }
            assertEquals(0, merged.status()["waitingRows"], "seed $seed")
            assertTrue((waiting.status()["waitingRows"] as Int) > 0, "seed $seed")
            assertTrue((mixed.status()["merges"] as Long) > 0, "seed $seed")
            val expected = readAll(merged)
            assertEquals(expected, readAll(waiting), "seed $seed: rows still waiting")
            assertEquals(expected, readAll(mixed), "seed $seed: some rows merged")
            assertTrue((chunked.status()["merges"] as Long) > 0, "seed $seed")
            assertEquals(expected, readAll(chunked), "seed $seed: merged a tag at a time")
            assertEquals(merged.partitionBounds(), waiting.partitionBounds(), "seed $seed: partitions are made with the rows")
            merged.close()
            mixed.close()
            waiting.close()
            // Closed in the middle of a merge: reopening finishes it.
            chunked.close()
            open("chunked-$seed.sqlite", mergeRows = Int.MAX_VALUE).use { h ->
                assertEquals(0, h.status()["waitingRows"])
                assertEquals(expected, readAll(h), "seed $seed: a merge left half done")
            }
            // Reopening merges what was waiting.
            open("waiting-$seed.sqlite", mergeRows = Int.MAX_VALUE).use { h ->
                assertEquals(0, h.status()["waitingRows"])
                assertEquals(expected, readAll(h), "seed $seed: merged on open")
            }
        }
    }

    @Test
    fun anIdenticalRowIsStoredOnceAcrossAMerge() {
        open("dedup.sqlite", mergeRows = 2).use { h ->
            val a = HistorySample("[default]A", t0 + 5, 1.5, Quality.GOOD)
            h.appendAll(listOf(a, HistorySample("[default]B", t0 + 5, 2, Quality.GOOD)))
            assertEquals(0, h.status()["waitingRows"], "two rows reach the merge threshold")
            // Written again after it was merged, and again while waiting.
            h.appendAll(listOf(a))
            h.appendAll(listOf(HistorySample("[default]A", t0 + 5, 9.0, Quality.GOOD)))
            h.appendAll(listOf(a, HistorySample("[default]A", t0 + 5, 9.0, Quality.GOOD)))
            assertEquals(listOf(1.5, 9.0), h.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).map { it.value })
            assertEquals(listOf(2), h.query(HistoryQuery("[default]B", 0, Long.MAX_VALUE)).map { it.value })
        }
    }

    @Test
    fun aScanThatRunsWhileRowsAreMergedSeesEachRowOnce() {
        open("scan.sqlite", mergeRows = Int.MAX_VALUE).use { h ->
            val rows = (0 until 12_000).map { HistorySample("[default]A", t0 + it * 1000L, it, Quality.GOOD) }
            h.appendAll(rows.take(6_000))
            h.maintainMergeForTest()
            h.appendAll(rows.drop(6_000))
            val seen = ArrayList<Int>()
            h.scan("[default]A", Long.MIN_VALUE, Long.MAX_VALUE) {
                // After the first page the waiting half moves to its partition.
                if (seen.size == 1) h.maintainMergeForTest()
                seen += it.value as Int
            }
            assertEquals((0 until 12_000).toList(), seen)
        }
    }

    /** A merge now, through maintenance in prune mode with an age nothing reaches. */
    private fun EmbeddedHistorian.maintainMergeForTest() {
        configure(EmbeddedHistorianSettings(PartitionInterval.DAY, maintenanceMode = MaintenanceMode.PRUNE, maintenanceAge = 10_000))
        maintain(Instant.ofEpochMilli(t0))
        configure(settings)
        assertEquals(0, status()["waitingRows"])
    }
}
