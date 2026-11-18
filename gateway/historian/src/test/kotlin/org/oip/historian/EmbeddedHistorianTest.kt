package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.Quality
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.random.Random

/** The default embedded historian: partitions, deduplication, native aggregation, maintenance (FEATURES HIS-007). */
class EmbeddedHistorianTest {
    @TempDir lateinit var tmp: Path

    private val day = PartitionInterval.DAY_MILLIS
    private val t0 = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli() // a Monday

    private fun sample(path: String, t: Long, v: Any?, q: Quality = Quality.GOOD) = HistorySample(path, t, v, q)

    private fun open(settings: EmbeddedHistorianSettings = EmbeddedHistorianSettings(partitionInterval = PartitionInterval.DAY), name: String = "db/history.sqlite") =
        EmbeddedHistorian(tmp.resolve(name), settings)

    private fun tables(file: Path): List<String> = DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) out += rs.getString(1)
                out
            }
        }
    }

    @Test
    fun rowsLandInOneTablePerPartitionAndReadBackAcrossPartitionsInOrder() {
        open().use { h ->
            h.appendAll((0 until 30).map { sample("[default]A", t0 + it * 6 * 3_600_000L, it.toDouble()) }) // 7.5 days
            h.appendAll(listOf(sample("[default]B", t0 + 1, 99.0)))
            assertEquals(8, h.partitionBounds().size)
            assertEquals((0 until 8).map { (t0 + it * day) to (t0 + (it + 1) * day) }, h.partitionBounds())
            val rows = h.query(HistoryQuery("[DEFAULT]a", t0 + day - 1, t0 + 3 * day))
            assertEquals((4..12).map { it.toDouble() }, rows.map { it.value }, "range crosses partitions, oldest first, case-insensitive path")
            assertEquals(listOf(4.0, 5.0), h.query(HistoryQuery("[default]A", t0 + day - 1, t0 + 3 * day, limit = 2)).map { it.value })
            val scanned = ArrayList<Double>()
            h.scan("[default]A", t0, t0 + 30 * day) { scanned += it.value as Double }
            assertEquals((0 until 30).map { it.toDouble() }, scanned)
            assertEquals(28.0, h.latestBefore("[default]A", t0 + 29 * 6 * 3_600_000L)?.value, "latest before searches back across partitions")
            assertNull(h.latestBefore("[default]A", t0))
            assertEquals(listOf(99.0), h.query(HistoryQuery("[default]B", 0, Long.MAX_VALUE)).map { it.value })
        }
        val names = tables(tmp.resolve("db/history.sqlite"))
        assertEquals(8, names.count { it.startsWith("eh_p_") }, names.toString())
    }

    @Test
    fun valuesKeepTheirTypeAndQualityRoundTrips() {
        open().use { h ->
            val values = listOf(true, 7, 9_000_000_000L, 1.5, "text", null, Double.NaN, Double.POSITIVE_INFINITY)
            h.appendAll(values.mapIndexed { i, v -> sample("[default]T", t0 + i, v) })
            h.appendAll(listOf(sample("[default]T", t0 + 99, 1.0, Quality.BAD_NOT_CONNECTED.withDiagnostic("link down"))))
            val rows = h.query(HistoryQuery("[default]T", t0, t0 + 100))
            assertEquals(values.map { if (it is Double && it.isNaN()) "NaN" else it }, rows.take(values.size).map { (it.value as? Double)?.takeIf { d -> d.isNaN() }?.let { "NaN" } ?: it.value })
            assertEquals(Quality.BAD_NOT_CONNECTED.withDiagnostic("link down"), rows.last().quality)
        }
    }

    @Test
    fun weekMonthAndYearPartitionsFollowTheUtcCalendar() {
        val wed = Instant.parse("2026-11-04T13:00:00Z").toEpochMilli()
        assertEquals(listOf(Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-09T00:00:00Z")), PartitionInterval.WEEK.bounds(wed).map { Instant.ofEpochMilli(it) })
        assertEquals(listOf(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z")), PartitionInterval.MONTH.bounds(wed).map { Instant.ofEpochMilli(it) })
        assertEquals(listOf(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")), PartitionInterval.YEAR.bounds(wed).map { Instant.ofEpochMilli(it) })
        assertEquals(listOf(Instant.parse("1969-12-29T00:00:00Z"), Instant.parse("1970-01-05T00:00:00Z")), PartitionInterval.WEEK.bounds(-1).map { Instant.ofEpochMilli(it) })
    }

    @Test
    fun aChangedIntervalNeverOverlapsAnEarlierPartition() {
        open(EmbeddedHistorianSettings(partitionInterval = PartitionInterval.DAY)).use { h ->
            h.appendAll(listOf(sample("[default]A", t0 + 3 * day + 5, 1.0)))
            h.configure(EmbeddedHistorianSettings(partitionInterval = PartitionInterval.WEEK))
            h.appendAll(listOf(sample("[default]A", t0 + 1, 0.0), sample("[default]A", t0 + 5 * day, 2.0)))
            assertEquals(listOf(t0 to t0 + 3 * day, t0 + 3 * day to t0 + 4 * day, t0 + 4 * day to t0 + 7 * day), h.partitionBounds())
            assertEquals(listOf(0.0, 1.0, 2.0), h.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).map { it.value })
        }
    }

    @Test
    fun aFailedBatchCreatesNoPartitionAndTheNextBatchStillWrites() {
        val file = tmp.resolve("db/history.sqlite")
        open().use { h ->
            h.appendAll(listOf(sample("[default]A", t0, 1.0)))
            val poison = object { override fun toString(): String = throw IllegalStateException("cannot encode") }
            assertThrows(IllegalStateException::class.java) {
                h.appendAll(listOf(sample("[default]A", t0 + 2 * day, 2.0), sample("[default]New", t0 + 2 * day, 3.0), sample("[default]A", t0 + 2 * day + 1, poison)))
            }
            assertEquals(listOf(t0 to t0 + day), h.partitionBounds(), "the rolled-back partition is not published")
            assertEquals(0, h.query(HistoryQuery("[default]A", t0 + day, Long.MAX_VALUE)).size)
            assertFalse(h.latestAll().containsKey("[default]New"))
            h.appendAll(listOf(sample("[default]A", t0 + 2 * day, 2.0), sample("[default]New", t0 + 2 * day, 3.0)))
            assertEquals(listOf(1.0, 2.0), h.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).map { it.value })
            assertEquals(3.0, h.latestAll()["[default]New"]?.value)
        }
        assertEquals(2, tables(file).count { it.startsWith("eh_p_") })
        // Reopen: the catalog, tag ids and latest rows come back from disk.
        open().use { h ->
            assertEquals(listOf(t0 to t0 + day, t0 + 2 * day to t0 + 3 * day), h.partitionBounds())
            assertEquals(listOf(1.0, 2.0), h.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).map { it.value })
            assertEquals(2.0, h.latestAll()["[default]A"]?.value)
        }
    }

    @Test
    fun deduplicationStoresAnIdenticalRowOnceAndKeepsRealChangesInTheSameMillisecond() {
        open().use { h ->
            assertTrue(h.settings.deduplication, "on by default")
            h.appendAll(listOf(sample("[default]A", t0, 1.0), sample("[default]A", t0, 1.0), sample("[default]A", t0 + 1, 3.0)))
            h.appendAll(listOf(sample("[default]A", t0 + 1, 3.0), sample("[default]A", t0, 1.0)))
            assertEquals(listOf(1.0, 3.0), h.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).map { it.value }, "identical rows are stored once")
            h.appendAll(listOf(sample("[default]A", t0 + 1, 4.0), sample("[default]A", t0 + 1, 3.0, Quality.BAD)))
            assertEquals(listOf(3.0, 4.0, 3.0), h.query(HistoryQuery("[default]A", t0 + 1, t0 + 1)).map { it.value }, "a different value or quality is a new row, in write order")
            h.appendAll(listOf(sample("[default]N", t0, null), sample("[default]N", t0, null), sample("[default]N", t0, Double.NaN), sample("[default]N", t0, Double.NaN)))
            assertEquals(2, h.query(HistoryQuery("[default]N", t0, t0)).size, "null and NaN compare equal to themselves")
            assertEquals(Quality.BAD, h.latestAll()["[default]A"]?.quality)

            h.configure(h.settings.copy(deduplication = false))
            h.appendAll(listOf(sample("[default]A", t0 + 2, 5.0), sample("[default]A", t0 + 2, 5.0)))
            assertEquals(listOf(5.0, 5.0), h.query(HistoryQuery("[default]A", t0 + 2, t0 + 2)).map { it.value }, "without dedup every row is kept")
        }
        // The seq survives a reopen, so later rows still sort after earlier ones in the same millisecond.
        open().use { h ->
            h.appendAll(listOf(sample("[default]A", t0 + 2, 6.0), sample("[default]A", t0 + 2, 5.0)))
            assertEquals(listOf(5.0, 5.0, 6.0), h.query(HistoryQuery("[default]A", t0 + 2, t0 + 2)).map { it.value })
        }
    }

    @Test
    fun aBatchReplayedAfterARetryIsStoredOnce() {
        open().use { h ->
            val batch = (0 until 500).map { sample("[default]T${it % 7}", t0 + it * 1000L, it) }
            h.appendAll(batch)
            h.appendAll(batch)
            var n = 0
            for (t in 0 until 7) h.scan("[default]T$t", 0, Long.MAX_VALUE) { n++ }
            assertEquals(500, n)
        }
    }

    @Test
    fun nativeAggregatesEqualTheScanForEveryNativeModeAcrossPartitions() {
        val rnd = Random(42)
        open().use { h ->
            val rows = ArrayList<HistorySample>()
            var t = t0 - day / 2
            repeat(3_000) {
                t += rnd.nextLong(1, 400_000)
                val v: Any? = when (rnd.nextInt(10)) {
                    0 -> rnd.nextBoolean()
                    1 -> rnd.nextInt(-50, 50)
                    2 -> "s$it"
                    3 -> null
                    4 -> rnd.nextLong(-1_000_000_000_000L, 1_000_000_000_000L)
                    else -> rnd.nextDouble(-100.0, 100.0)
                }
                val q = if (rnd.nextInt(8) == 0) Quality.BAD_NOT_CONNECTED else Quality.GOOD
                rows += sample("[default]R", t, v, q)
            }
            rows.chunked(700).forEach { h.appendAll(it) }
            assertTrue(h.partitionBounds().size >= 5, h.partitionBounds().size.toString())
            val scanOnly = object : HistoryReader by h {}
            val native = HistoryAggregate.entries - HistoryAggregate.AVERAGE
            for (interval in listOf(3_600_000L, 7 * 3_600_000L, 40 * day)) {
                for (mode in native) {
                    val from = t0 - day / 3
                    val to = t - 1234
                    val fast = h.aggregateNative("[default]R", from, to, interval, mode, HistoryInterpolation.NONE)!!
                    val slow = HistoryAggregator.aggregate(scanOnly, "[default]R", from, to, interval, mode)
                    assertEquals(slow.map { it.startMillis to it.endMillis }, fast.map { it.startMillis to it.endMillis })
                    assertEquals(slow.map { it.rows }, fast.map { it.rows }, "$mode rows")
                    for ((a, b) in slow.zip(fast)) {
                        if (a.value is Double && b.value is Double) {
                            val x = a.value as Double
                            val y = b.value as Double
                            assertTrue(x == y || Math.abs(x - y) <= 1e-9 * maxOf(1.0, Math.abs(x)), "$mode at ${a.startMillis}: $x vs $y")
                        } else {
                            assertEquals(a.value, b.value, "$mode at ${a.startMillis}")
                        }
                    }
                }
            }
            assertNull(h.aggregateNative("[default]R", t0, t, 3_600_000L, HistoryAggregate.AVERAGE, HistoryInterpolation.NONE), "time-weighted average scans")
            assertNull(h.aggregateNative("[default]R", t0, t, 3_600_000L, HistoryAggregate.MAXIMUM, HistoryInterpolation.LINEAR))
            val viaAggregator = HistoryAggregator.aggregate(h, "[default]R", t0, t, day, HistoryAggregate.COUNT)
            assertEquals(rows.count { it.timestampMillis in t0..t }, viaAggregator.sumOf { it.value as Int }, "HistoryAggregator uses the native path")
            val nothing = h.aggregateNative("[default]Missing", t0, t0 + day - 1, 3_600_000L, HistoryAggregate.COUNT, HistoryInterpolation.NONE)!!
            assertEquals(24, nothing.size)
            assertTrue(nothing.all { it.value == 0 })
        }
    }

    @Test
    fun pruneRemovesWholePartitionsOlderThanTheAge() {
        val settings = EmbeddedHistorianSettings(PartitionInterval.DAY, maintenanceMode = MaintenanceMode.PRUNE, maintenanceAge = 2, maintenanceAgeUnits = PartitionInterval.DAY)
        open(settings).use { h ->
            h.appendAll((0 until 5).map { sample("[default]A", t0 + it * day + 10, it.toDouble()) })
            assertEquals(0, h.maintain(Instant.ofEpochMilli(t0 + 3 * day - 1)), "a partition ends exactly at the cutoff only after a full age")
            assertEquals(2, h.maintain(Instant.ofEpochMilli(t0 + 4 * day)))
            assertEquals(listOf(2.0, 3.0, 4.0), h.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).map { it.value })
            assertEquals(2L, h.status()["pruned"])
            assertEquals(4.0, h.latestAll()["[default]A"]?.value)
        }
        assertEquals(3, tables(tmp.resolve("db/history.sqlite")).count { it.startsWith("eh_p_") })
    }

    @Test
    fun archiveMovesOldPartitionsToTheirOwnFileBeforeDroppingThem() {
        val folder = tmp.resolve("archive")
        val settings = EmbeddedHistorianSettings(PartitionInterval.DAY, maintenanceMode = MaintenanceMode.ARCHIVE, maintenanceAge = 1, archiveFolder = folder)
        open(settings).use { h ->
            h.appendAll(listOf(sample("[default]A", t0 + 5, 1.0), sample("[default]B", t0 + 6, "x"), sample("[default]A", t0 + day + 5, 2.0)))
            assertEquals(1, h.maintain(Instant.ofEpochMilli(t0 + 2 * day)))
            assertEquals(listOf(2.0), h.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).map { it.value })
            val file = h.archiveFile(folder, t0)
            assertTrue(file.exists())
            assertFalse(file.resolveSibling(file.fileName.toString() + ".tmp").exists())
            DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT tag_path, ts, v FROM samples ORDER BY tag_path").use { rs ->
                        assertTrue(rs.next()); assertEquals("[default]A", rs.getString(1)); assertEquals(t0 + 5, rs.getLong(2)); assertEquals(1.0, rs.getDouble(3))
                        assertTrue(rs.next()); assertEquals("[default]B", rs.getString(1)); assertEquals("x", rs.getString(3))
                        assertFalse(rs.next())
                    }
                    st.executeQuery("SELECT start_ms, end_ms FROM partition_info").use { rs -> rs.next(); assertEquals(t0, rs.getLong(1)); assertEquals(t0 + day, rs.getLong(2)) }
                }
            }
            assertEquals(1L, h.status()["archived"])
        }
    }

    @Test
    fun maintenanceSettingsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedHistorianSettings(PartitionInterval.WEEK, maintenanceMode = MaintenanceMode.PRUNE, maintenanceAge = 10, maintenanceAgeUnits = PartitionInterval.DAY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedHistorianSettings(PartitionInterval.WEEK, maintenanceMode = MaintenanceMode.PRUNE, maintenanceAge = 1, maintenanceAgeUnits = PartitionInterval.MONTH)
        }
        assertThrows(IllegalArgumentException::class.java) { EmbeddedHistorianSettings(maintenanceMode = MaintenanceMode.PRUNE, maintenanceAge = 0) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddedHistorianSettings(maintenanceMode = MaintenanceMode.ARCHIVE, maintenanceAge = 1) }
        EmbeddedHistorianSettings(PartitionInterval.MONTH, maintenanceMode = MaintenanceMode.PRUNE, maintenanceAge = 2, maintenanceAgeUnits = PartitionInterval.YEAR)
        EmbeddedHistorianSettings(PartitionInterval.DAY, maintenanceMode = MaintenanceMode.PRUNE, maintenanceAge = 2, maintenanceAgeUnits = PartitionInterval.WEEK)
    }

    @Test
    fun aFlatStoreFileIsMovedIntoPartitionsOnFirstOpen() {
        val file = tmp.resolve("db/history.sqlite")
        SqliteHistoryStore(file).use { s ->
            s.appendAll(listOf(sample("[default]A", t0 + 1, 1.0), sample("[default]A", t0 + day + 1, 2.0), sample("[default]B", t0 + 2, "on")))
        }
        open().use { h ->
            assertEquals(listOf(1.0, 2.0), h.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).map { it.value })
            assertEquals("on", h.latestAll()["[default]B"]?.value)
            assertEquals(2.0, h.latestAll()["[default]A"]?.value)
        }
        val names = tables(file)
        assertFalse("history_sample" in names, names.toString())
        assertFalse("history_latest" in names, names.toString())
        open().use { h -> assertEquals(2, h.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).size, "migrated once") }
    }

    @Test
    fun readsWritesAndPruningRunConcurrentlyWithoutErrors() {
        val settings = EmbeddedHistorianSettings(PartitionInterval.DAY, maintenanceMode = MaintenanceMode.PRUNE, maintenanceAge = 3, maintenanceAgeUnits = PartitionInterval.DAY)
        open(settings).use { h ->
            val stop = AtomicBoolean(false)
            val errors = Collections.synchronizedList(ArrayList<Throwable>())
            val started = CountDownLatch(3)
            val written = java.util.concurrent.atomic.AtomicLong(0)
            fun worker(body: () -> Unit) = Thread {
                started.countDown()
                try {
                    while (!stop.get()) body()
                } catch (e: Throwable) {
                    errors += e
                }
            }.apply { isDaemon = true; start() }
            var tick = 0L
            val writer = worker {
                val base = t0 + tick * 3_600_000L
                h.appendAll((0 until 50).map { sample("[default]T$it", base, tick.toDouble()) })
                written.set(tick)
                tick++
            }
            val reader = worker {
                val rows = h.query(HistoryQuery("[default]T1", 0, Long.MAX_VALUE))
                check(rows.zipWithNext().all { (a, b) -> a.timestampMillis < b.timestampMillis }) { "rows out of order" }
                HistoryAggregator.aggregate(h, "[default]T2", t0, t0 + 30 * day, day, HistoryAggregate.MAXIMUM)
                var last = Long.MIN_VALUE
                h.scan("[default]T3", 0, Long.MAX_VALUE) { check(it.timestampMillis > last); last = it.timestampMillis }
            }
            val pruner = worker { h.maintain(Instant.ofEpochMilli(t0 + written.get() * 3_600_000L)) }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < deadline && errors.isEmpty() && written.get() < 24 * 12) Thread.sleep(20)
            stop.set(true)
            listOf(writer, reader, pruner).forEach { it.join(10_000) }
            assertEquals(emptyList<Throwable>(), errors.toList())
            assertTrue(written.get() > 24 * 4, "writes kept going: ${written.get()}")
            assertTrue(h.partitionBounds().size <= 6, h.partitionBounds().toString())
        }
    }
}
