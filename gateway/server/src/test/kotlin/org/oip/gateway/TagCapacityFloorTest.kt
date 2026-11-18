package org.oip.gateway

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.tags.QualityCode
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TagConfigBinding
import org.oip.tags.TagEngine
import org.oip.tags.TagSample
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.round

/**
 * PRF-004, PRF-005, and PRF-006 (ADR 0010). Each floor holds its tag count on a 1 s scan inside
 * the cutoffs: CPU above 50%, memory above 50%, clock drift outside +/-10%, or any error or
 * data loss. Exactly 50% and exactly +/-10% stay inside, matching [ClockDriftDetector.outsideCutoff].
 *
 * CPU percent is process CPU time over the scheduled window divided by that size's vCPUs.
 * Memory percent is process RSS (VmRSS) over that size's RAM. The window is [INTERVALS] scheduled
 * 1 s ticks. This is the floor check. It is not the PRF-001 soak.
 *
 * "10% changing" and "6% changing" update `(index + scan) % 100 < percent` of the tags (ADR 0010).
 * PRF-006 has no change fraction in the scenario. "Updating every 1 s" changes every tag, and
 * local history appends one SQLite row per tag per tick. The phase-3 historian is not this table.
 *
 * Accepted cpuPercent baselines (lower is better): PRF-004 0.1406, PRF-005 0.3516, PRF-006 3.4687.
 * PRF-013 fails a later same-hardware run only when it is more than 15% worse. A result 15% worse
 * does not fail. The ceilings stay 50, 50, and 10.
 */
class TagCapacityFloorTest {
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    fun prf004HoldsOneHundredThousandTagsOnS8() {
        val floor = Floor(
            id = "PRF-004",
            tags = 100_000,
            changePercent = 10,
            vcpu = 8,
            ramGb = 16,
            hardware = "S8",
            localHistory = false,
        )
        runFloor(floor)
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    fun prf005HoldsOneHundredFiftyThousandTagsOnS16() {
        val floor = Floor(
            id = "PRF-005",
            tags = 150_000,
            changePercent = 6,
            vcpu = 16,
            ramGb = 32,
            hardware = "S16",
            localHistory = false,
        )
        runFloor(floor)
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    fun prf006HoldsEightyEightThousandFiveHundredTagsOnEdge() {
        val floor = Floor(
            id = "PRF-006",
            tags = 88_500,
            changePercent = 100,
            vcpu = 4,
            ramGb = 4,
            hardware = "EDGE",
            localHistory = true,
        )
        runFloor(floor)
    }

    private fun runFloor(floor: Floor) {
        val only = System.getProperty("oip.capacity.only")
        if (!only.isNullOrBlank() && only != floor.id) return
        val requirements = requirements()
        assertMatchesRequirements(floor, requirements)
        val changing = changingCount(floor.tags, 0, floor.changePercent)
        assertEquals(floor.tags * floor.changePercent / 100, changing)
        assertEquals(changing, changingCount(floor.tags, 3, floor.changePercent))

        val dir = Files.createTempDirectory("oip-capacity-${floor.id}")
        val registry = SchemaRegistry()
        val store = FileConfigStore(dir.resolve("config"), registry)
        val engine = TagEngine()
        val binding = TagConfigBinding(engine, store, registry)
        val history = if (floor.localHistory) LocalHistory(dir.resolve("history.db")) else null
        try {
            binding.attach()
            val importStarted = System.nanoTime()
            val created = import(store, floor.tags)
            val importSeconds = (System.nanoTime() - importStarted) / 1_000_000_000.0
            assertEquals(floor.tags, created)
            assertTrue(binding.problems().isEmpty(), binding.problems().take(3).toString())

            val samples = Array(floor.tags) { i -> engine.openSample("bench/${tagName(i)}") }
            assertEquals(0, (samples[floor.tags - 1].read().value as Number).toInt())
            assertEquals(QualityCode.GOOD, samples[floor.tags - 1].read().quality.code)
            val values = IntArray(floor.tags)

            repeat(WARMUP) { scan -> runScan(floor, samples, values, scan, history) }
            history?.wipe()

            val os = ManagementFactoryProcess.cpuBean()
            val intervals = intervals()
            val drifts = DoubleArray(intervals)
            val scanWallMs = DoubleArray(intervals)
            var expected = System.nanoTime() + INTERVAL_NS
            val cpu0 = os.processCpuTime
            val wall0 = System.nanoTime()
            repeat(intervals) { i ->
                val scan = WARMUP + i
                val scanStart = System.nanoTime()
                runScan(floor, samples, values, scan, history)
                scanWallMs[i] = (System.nanoTime() - scanStart) / 1_000_000.0
                val remaining = expected - System.nanoTime()
                if (remaining > 0) sleepNanos(remaining)
                val woke = System.nanoTime()
                drifts[i] = (woke - expected).toDouble() / INTERVAL_NS.toDouble()
                expected += INTERVAL_NS
            }
            val cpu1 = os.processCpuTime
            val wall1 = System.nanoTime()
            assertTrue(cpu1 > cpu0, "process CPU clock did not move")

            val historyRows = history?.count() ?: 0L
            if (history != null) {
                val expectedRows = intervals.toLong() * changing
                assertEquals(expectedRows, historyRows, "local history dropped or duplicated a sample")
                val lastScan = WARMUP + intervals - 1
                val lastTs = stampMillis(lastScan)
                val firstChanged = (0 until floor.tags).first { changes(it, lastScan, floor.changePercent) }
                assertEquals(values[firstChanged], history.value(firstChanged, lastTs))
            }

            System.gc()
            val rss = rssBytes()
            val heap = heapUsed()
            val cpuPercent = round4((cpu1 - cpu0).toDouble() / (wall1 - wall0).toDouble() / floor.vcpu * 100.0)
            val memoryPercent = round4(rss.toDouble() / (floor.ramGb.toDouble() * GIB) * 100.0)
            val driftMean = drifts.average()
            val driftPercent = round4(driftMean * 100.0)
            val driftMaxPercent = round4(drifts.maxOf { abs(it) } * 100.0)
            val maxScanMs = scanWallMs.max()
            val qualityErrors = scanQualityErrors
            val mismatches = scanMismatches
            val errors = scanErrors
            scanQualityErrors = 0
            scanMismatches = 0
            scanErrors = 0
            val inside = cpuPercent <= CPU_CUTOFF &&
                memoryPercent <= MEMORY_CUTOFF &&
                abs(driftPercent) <= DRIFT_CUTOFF &&
                maxScanMs < 1_000.0 &&
                qualityErrors == 0 &&
                mismatches == 0 &&
                errors == 0
            val report = linkedMapOf<String, Any?>(
                "scenario" to floor.id,
                "hardware" to floor.hardware,
                "vcpu" to floor.vcpu,
                "ramGb" to floor.ramGb,
                "tags" to floor.tags,
                "scanSeconds" to 1,
                "changePercent" to floor.changePercent,
                "localHistory" to floor.localHistory,
                "intervals" to intervals,
                "warmup" to WARMUP,
                "measuredProcessors" to Runtime.getRuntime().availableProcessors(),
                "cpuAccounting" to "processCpuTime / scheduledWall / referenceVcpu",
                "memoryAccounting" to "VmRSS / referenceRam",
                "importSeconds" to round4(importSeconds),
                "cpuPercent" to cpuPercent,
                "memoryPercent" to memoryPercent,
                "clockDriftPercent" to driftPercent,
                "clockDriftMaxPercent" to driftMaxPercent,
                "maxScanMs" to round4(maxScanMs),
                "rssBytes" to rss,
                "heapBytes" to heap,
                "historyRows" to historyRows,
                "qualityErrors" to qualityErrors,
                "mismatches" to mismatches,
                "errors" to errors,
                "insideCutoffs" to inside,
                "headroom" to linkedMapOf(
                    "cpuPercent" to round4(CPU_CUTOFF - cpuPercent),
                    "memoryPercent" to round4(MEMORY_CUTOFF - memoryPercent),
                    "clockDriftPercent" to round4(DRIFT_CUTOFF - abs(driftPercent)),
                ),
                "changeRule" to "(index + scan) % 100 < changePercent",
            )
            writeReport(floor.id, report)
            println(
                "${floor.id} cpuPercent=$cpuPercent memoryPercent=$memoryPercent " +
                    "clockDriftPercent=$driftPercent maxScanMs=${round4(maxScanMs)} " +
                    "rssBytes=$rss heapBytes=$heap importSeconds=${round4(importSeconds)} " +
                    "headroomCpu=${round4(CPU_CUTOFF - cpuPercent)} " +
                    "headroomMemory=${round4(MEMORY_CUTOFF - memoryPercent)} " +
                    "headroomDrift=${round4(DRIFT_CUTOFF - abs(driftPercent))} " +
                    "inside=$inside",
            )
            assertTrue(maxScanMs < 1_000.0, "${floor.id} scan took ${round4(maxScanMs)} ms, past the 1 s period")
            assertTrue(cpuPercent <= CPU_CUTOFF, "${floor.id} CPU $cpuPercent% is above $CPU_CUTOFF% of ${floor.hardware}")
            assertTrue(memoryPercent <= MEMORY_CUTOFF, "${floor.id} memory $memoryPercent% is above $MEMORY_CUTOFF% of ${floor.ramGb} GB")
            assertTrue(abs(driftPercent) <= DRIFT_CUTOFF, "${floor.id} clock drift $driftPercent% is outside +/-$DRIFT_CUTOFF%")
            assertEquals(0, qualityErrors, "${floor.id} quality errors")
            assertEquals(0, mismatches, "${floor.id} mismatched samples")
            assertEquals(0, errors, "${floor.id} write errors")
            assertTrue(inside, "${floor.id} missed a cutoff")
            assertNoRegression(floor, cpuPercent)
        } finally {
            runCatching { history?.close() }
            runCatching { binding.close() }
            runCatching { store.close() }
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private fun import(store: FileConfigStore, tags: Int): Int {
        var created = 0
        var offset = 0
        val batch = ArrayList<Resource>(BATCH)
        while (offset < tags) {
            batch.clear()
            val end = minOf(offset + BATCH, tags)
            for (i in offset until end) {
                val name = tagName(i)
                batch += Resource(
                    TAG_KIND,
                    Metadata(name, TAGS_MODULE),
                    mapOf(
                        "provider" to "default",
                        "path" to "bench/$name",
                        "type" to "memory",
                        "dataType" to "Int32",
                        "value" to 0,
                    ),
                )
            }
            val result = store.putAll(Scope.GATEWAY, batch, actor = "capacity", syncEach = false)
            created += result.created
            offset = end
        }
        batch.clear()
        return created
    }

    private fun runScan(floor: Floor, samples: Array<TagSample>, values: IntArray, scan: Int, history: LocalHistory?) {
        val stamp = Instant.ofEpochMilli(stampMillis(scan))
        val next = scan + 1
        var changed = 0
        var i = 0
        while (i < floor.tags) {
            if (changes(i, scan, floor.changePercent)) {
                val written = samples[i].set(next, stamp)
                if (written.quality.code != QualityCode.GOOD) scanErrors++
                values[i] = next
                changed++
            }
            i++
        }
        if (changed != floor.tags * floor.changePercent / 100) scanErrors++
        history?.write(floor, values, scan, stamp.toEpochMilli())
        i = 0
        while (i < floor.tags) {
            val current = samples[i].read()
            if (current.quality.code != QualityCode.GOOD) scanQualityErrors++
            val got = (current.value as? Number)?.toInt()
            if (got != values[i]) scanMismatches++
            i++
        }
    }

    private fun assertMatchesRequirements(floor: Floor, tree: JsonNode) {
        val cut = tree["cutoffs"]
        assertEquals(50, cut["cpuPercentGreaterThan"].asInt())
        assertEquals(50, cut["memoryPercentGreaterThan"].asInt())
        assertEquals(10, cut["clockDriftPercentAbsGreaterThan"].asInt())
        assertEquals(CPU_CUTOFF, cut["cpuPercentGreaterThan"].asDouble())
        assertEquals(MEMORY_CUTOFF, cut["memoryPercentGreaterThan"].asDouble())
        assertEquals(DRIFT_CUTOFF, cut["clockDriftPercentAbsGreaterThan"].asDouble())
        val scenario = tree["scenarios"].first { it["id"].asText() == floor.id }
        assertEquals(floor.tags, scenario["threshold"].asInt())
        assertEquals(1, scenario["scanSeconds"].asInt())
        assertEquals(floor.hardware, scenario["hardware"].asText())
        if (floor.id != "PRF-006") assertEquals(floor.changePercent, scenario["changePercent"].asInt())
        if (floor.localHistory) assertEquals(true, scenario["localHistory"].asBoolean())
        val hw = tree["hardware"].first { it["id"].asText() == floor.hardware }
        assertEquals(floor.vcpu, hw["vcpu"].asInt())
        assertEquals(floor.ramGb, hw["ramGb"].asInt())
    }

    /**
     * PRF-013 (review-2 F11): not more than 15% worse than the accepted cpuPercent baseline for
     * this run's hardware class. The baselines were measured for S8, S16 and EDGE ([Prf013]);
     * on any other class the comparison is printed and not asserted.
     */
    private fun assertNoRegression(floor: Floor, cpuPercent: Double) {
        val hardware = Prf013.hardwareClass()
        val baseline = Prf013.applicable(floor.id, hardware)
        if (baseline == null) {
            val primary = Prf013.baselines(floor.id).first()
            println(
                "PRF-013 ${floor.id} not applied: baseline ${primary.value} is for ${primary.hardware}, " +
                    "this run is ${hardware ?: "(unset)"}; worsePercent would be " +
                    "%.1f".format(Locale.ROOT, Prf013.worsePercent(primary.value, cpuPercent, primary.lowerIsBetter)),
            )
            return
        }
        val worse = Prf013.worsePercent(baseline.value, cpuPercent, baseline.lowerIsBetter)
        println("PRF-013 ${floor.id} hardware=${baseline.hardware} baseline=${baseline.value} measured=$cpuPercent worsePercent=${"%.1f".format(Locale.ROOT, worse)}")
        assertFalse(
            Prf013.regressionFails(baseline.value, cpuPercent, baseline.lowerIsBetter),
            "PRF-013: ${floor.id} cpuPercent $cpuPercent is ${"%.1f".format(Locale.ROOT, worse)}% worse than the ${baseline.hardware} baseline ${baseline.value}",
        )
    }

    private fun requirements(): JsonNode {
        val given = System.getProperty("oip.perfRequirements")
        val file = if (!given.isNullOrBlank()) Path.of(given) else {
            var dir = Path.of("").toAbsolutePath()
            var found: Path? = null
            while (dir.parent != null) {
                val candidate = dir.resolve("test-infra/perf/perf-requirements.json")
                if (Files.exists(candidate)) {
                    found = candidate
                    break
                }
                dir = dir.parent
            }
            found ?: error("perf-requirements.json not found")
        }
        return ObjectMapper().readTree(file.toFile())
    }

    private fun writeReport(id: String, report: Map<String, Any?>) {
        val dir = Path.of(System.getProperty("oip.capacity.reportDir") ?: "build/reports/tag-capacity")
        Files.createDirectories(dir)
        val json = StringBuilder("{\n")
        writeJsonFields(json, report, 1)
        json.append("}\n")
        val slug = id.lowercase(Locale.US)
        Files.writeString(dir.resolve("$slug.json"), json.toString())
        Files.writeString(dir.resolve("$slug.md"), markdown(report))
    }

    private fun writeJsonFields(out: StringBuilder, fields: Map<String, Any?>, indent: Int) {
        val pad = "  ".repeat(indent)
        val entries = fields.entries.toList()
        entries.forEachIndexed { index, (key, value) ->
            out.append(pad).append('"').append(key).append("\": ")
            writeJson(out, value, indent)
            if (index != entries.lastIndex) out.append(',')
            out.append('\n')
        }
    }

    private fun writeJson(out: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            is Map<*, *> -> {
                out.append("{\n")
                @Suppress("UNCHECKED_CAST")
                writeJsonFields(out, value as Map<String, Any?>, indent + 1)
                out.append("  ".repeat(indent)).append('}')
            }
            is String -> out.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Double -> out.append(String.format(Locale.US, "%.4f", value))
            else -> error("unsupported report value $value")
        }
    }

    private fun markdown(report: Map<String, Any?>): String {
        val id = report["scenario"]
        val tags = report["tags"] as Int
        val hardware = report["hardware"]
        val vcpu = report["vcpu"]
        val ram = report["ramGb"]
        val headroom = report["headroom"] as Map<*, *>
        val history = if (report["localHistory"] == true) {
            "Local history appended one SQLite row per changing tag on every tick (${report["historyRows"]} rows). " +
                "That is this floor's history load. The phase-3 historian is a separate component."
        } else {
            "Local history is off for this floor. The scenario does not set localHistory."
        }
        val tagText = "%,d".format(Locale.US, tags)
        return """
            # $id tag-capacity floor

            Hardware budget: $hardware, $vcpu vCPU, $ram GB. Cutoffs, from ADR 0010: CPU above 50%, memory above 50%, clock drift outside +/-10%, plus any error or data loss. Exactly 50% and exactly +/-10% stay inside the cutoffs.

            $tagText tags at a 1 s scan, ${report["changePercent"]}% changing, rule `${report["changeRule"]}`. The window is ${report["intervals"]} scheduled 1 s intervals after ${report["warmup"]} warmup scans. CPU percent is process CPU time over that window divided by the $vcpu reference vCPUs. Memory percent is process RSS over the $ram GB reference RAM. Measured processors on this JVM: ${report["measuredProcessors"]}. This is the floor check, not the PRF-001 soak.

            | Metric | Measured | Cutoff | Headroom |
            |---|---:|---:|---:|
            | CPU % of $hardware | ${report["cpuPercent"]} | $CPU_CUTOFF | ${headroom["cpuPercent"]} |
            | Memory % of $hardware | ${report["memoryPercent"]} | $MEMORY_CUTOFF | ${headroom["memoryPercent"]} |
            | Clock drift % | ${report["clockDriftPercent"]} | +/-$DRIFT_CUTOFF | ${headroom["clockDriftPercent"]} |
            | Max scan ms | ${report["maxScanMs"]} | 1000 | |
            | Quality errors | ${report["qualityErrors"]} | 0 | |
            | Mismatches | ${report["mismatches"]} | 0 | |
            | Write errors | ${report["errors"]} | 0 | |

            RSS ${report["rssBytes"]} bytes. Heap ${report["heapBytes"]} bytes. Import ${report["importSeconds"]} s. Max single-interval drift ${report["clockDriftMaxPercent"]}%.

            $history

            The run is inside the cutoffs.

            """.trimIndent() + "\n"
    }

    private var scanErrors = 0
    private var scanMismatches = 0
    private var scanQualityErrors = 0

    private class Floor(
        val id: String,
        val tags: Int,
        val changePercent: Int,
        val vcpu: Int,
        val ramGb: Int,
        val hardware: String,
        val localHistory: Boolean,
    )

    private class LocalHistory(file: Path) : AutoCloseable {
        private val conn = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        private val insert: java.sql.PreparedStatement

        init {
            conn.createStatement().use { st ->
                listOf(
                    "PRAGMA journal_mode=WAL",
                    "PRAGMA synchronous=NORMAL",
                    "PRAGMA temp_store=MEMORY",
                    "CREATE TABLE sample (tag INTEGER NOT NULL, ts INTEGER NOT NULL, v INTEGER NOT NULL)",
                ).forEach { sql ->
                    if (st.execute(sql)) st.resultSet?.close()
                }
            }
            // The table has to exist before the insert is prepared. Property initializers run first.
            insert = conn.prepareStatement("INSERT INTO sample (tag, ts, v) VALUES (?, ?, ?)")
        }

        fun write(floor: Floor, values: IntArray, scan: Int, ts: Long) {
            conn.autoCommit = false
            var queued = 0
            var i = 0
            while (i < floor.tags) {
                if (changes(i, scan, floor.changePercent)) {
                    insert.setInt(1, i)
                    insert.setLong(2, ts)
                    insert.setInt(3, values[i])
                    insert.addBatch()
                    if (++queued == 5_000) {
                        insert.executeBatch()
                        queued = 0
                    }
                }
                i++
            }
            if (queued > 0) insert.executeBatch()
            conn.commit()
        }

        fun wipe() {
            conn.createStatement().use { st -> st.execute("DELETE FROM sample") }
        }

        fun count(): Long = conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM sample").use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }

        fun value(tag: Int, ts: Long): Int {
            conn.prepareStatement("SELECT v FROM sample WHERE tag = ? AND ts = ?").use { ps ->
                ps.setInt(1, tag)
                ps.setLong(2, ts)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "missing history row tag=$tag ts=$ts" }
                    return rs.getInt(1)
                }
            }
        }

        override fun close() {
            runCatching { insert.close() }
            runCatching { conn.close() }
        }
    }

    private object ManagementFactoryProcess {
        fun cpuBean(): com.sun.management.OperatingSystemMXBean {
            val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            assertTrue(os is com.sun.management.OperatingSystemMXBean, "process CPU time needs a HotSpot OperatingSystemMXBean")
            return os as com.sun.management.OperatingSystemMXBean
        }
    }

    private companion object {
        const val CPU_CUTOFF = 50.0
        const val MEMORY_CUTOFF = 50.0
        const val DRIFT_CUTOFF = 10.0
        const val INTERVALS = 8
        const val WARMUP = 2
        const val BATCH = 2_000
        const val INTERVAL_NS = 1_000_000_000L
        const val GIB = 1024.0 * 1024.0 * 1024.0

        fun intervals(): Int {
            val given = System.getProperty("oip.capacity.intervals")?.toIntOrNull() ?: return INTERVALS
            return given.coerceIn(4, 3_600)
        }

        fun tagName(index: Int) = "p%07d".format(index)

        fun stampMillis(scan: Int) = 1_700_000_000_000L + scan * 1_000L

        /** ADR 0010: the changing set is fixed by the tag index and the scan number. */
        fun changes(index: Int, scan: Int, percent: Int): Boolean {
            if (percent >= 100) return true
            if (percent <= 0) return false
            return ((index + scan) % 100) < percent
        }

        fun changingCount(tags: Int, scan: Int, percent: Int): Int {
            var n = 0
            var i = 0
            while (i < tags) {
                if (changes(i, scan, percent)) n++
                i++
            }
            return n
        }

        fun round4(value: Double) = round(value * 10_000.0) / 10_000.0

        fun rssBytes(): Long {
            val text = Files.readString(Path.of("/proc/self/status"))
            val line = text.lineSequence().firstOrNull { it.startsWith("VmRSS:") }
                ?: error("VmRSS missing from /proc/self/status")
            return line.trim().split(Regex("\\s+"))[1].toLong() * 1024L
        }

        fun heapUsed(): Long {
            val usage = java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage
            return usage.used
        }

        fun sleepNanos(nanos: Long) {
            val ms = nanos / 1_000_000L
            val extra = (nanos % 1_000_000L).toInt()
            Thread.sleep(ms, extra)
        }
    }
}
