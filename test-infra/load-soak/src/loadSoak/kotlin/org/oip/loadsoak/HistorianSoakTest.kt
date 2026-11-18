package org.oip.loadsoak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.db.PasswordUpdate
import org.oip.gateway.Gateway
import org.oip.tags.QualityCode
import org.oip.tags.TagSample
import org.oip.testinfra.ToxiproxyClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.writeText

/**
 * PRF-008 (ADR 0010): the historian holds at least 10,000 changes/s with no sample loss, including across a
 * database network cut. The 30,000 changes/s stretch is recorded and does not gate.
 *
 * Topology, all on one user-defined Docker bridge: TimescaleDB (the image pinned in test-infra/compose.yaml),
 * and the fault proxy (also pinned there) in front of it. The gateway runs in this JVM and reaches the
 * database only through the proxy's bridge address, never loopback. History goes the production way: on-change
 * `historian/HistorySettings` per tag, a SQL `historian/HistoryProvider` on a `db/Connection`, the
 * store-and-forward buffer, and the forwarder.
 *
 * Load: memory tags `soak/pNNNNNNN`. One scan per second, at an absolute 1 s deadline, writes `scan + 1`
 * stamped `t0 + scan * 1000` to every tag where `(index + scan) % 100 < percent`. The steady phase runs at
 * the floor rate and cuts the database link for `cutSeconds` in the middle (live connections reset, new ones
 * refused). The stretch phase follows at the stretch rate.
 *
 * Verdict, from the database after the drain:
 *  - row count equals the writes plus one first sample (value 0) per tag;
 *  - distinct (tag, value) equals the row count, so no duplicate;
 *  - no row breaks the change rule for its scan, no row has the wrong timestamp, every row is Good;
 *  - collector dropped 0, diverted 0, quarantine empty.
 * Cutoffs (CPU 50, memory 50, drift +/-10 on VM counters, sampled every 10 s; steady mean and every 60 s
 * mean) gate the steady phase. The stretch phase's numbers are recorded only: ingest, the forward rate, and
 * backlog growth per minute (review-3 F12).
 *
 * Sizes are in test-infra/perf/prf-008.json. CI runs `ci`; `-PloadSoakSize=full` runs the floor size.
 */
class HistorianSoakTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun historianHoldsTheFloorRateThroughADatabaseCutWithZeroLoss() {
        val req = requirements()
        val scenario = req["scenarios"].first { it["id"].asText() == "PRF-008" }
        val floor = scenario["threshold"].asInt()
        val stretchRate = scenario["stretch"].asInt()
        val size = sizes("PRF-008")
        val tags = size["tags"].asInt()
        val percent = size["percent"].asInt()
        val steadySeconds = size["steadySeconds"].asInt()
        val cutAt = size["cutAtSeconds"].asInt()
        val cutSeconds = size["cutSeconds"].asInt()
        val stretchPercent = size["stretchPercent"].asInt()
        val stretchSeconds = size["stretchSeconds"].asInt()
        val sampleSeconds = req["cutoffs"]["sampleSeconds"].asInt()
        val drainBudgetMs = size["drainBudgetSeconds"].asLong() * 1_000
        require(tags % 100 == 0) { "tags must be a multiple of 100 so every scan changes the same count" }
        require(cutAt + cutSeconds < steadySeconds) { "the cut must end inside the steady phase" }
        val steadyRate = tags * percent / 100
        val stretchActual = tags * stretchPercent / 100
        if (sizeName() == "full") {
            assertTrue(steadyRate >= floor, "full size must run at the $floor floor, not $steadyRate")
            assertEquals(scenario["durationMinutes"].asInt() * 60, steadySeconds, "full size must hold the floor for the ADR window")
            assertTrue(stretchActual >= stretchRate, "full size must record the $stretchRate stretch")
        }
        println(
            "PRF-008 size=${sizeName()} tags=$tags steady=$steadyRate/s for ${steadySeconds}s " +
                "cut ${cutSeconds}s at ${cutAt}s stretch=$stretchActual/s for ${stretchSeconds}s",
        )

        Bridge("oip-prf008").use { bridge ->
            assertEquals("bridge", bridge.driver, "container network driver")
            val db = bridge.run(
                "timescale", Bridge.composeImage("timescale"),
                mapOf("POSTGRES_USER" to USER, "POSTGRES_PASSWORD" to PASSWORD, "POSTGRES_DB" to DB, "TIMESCALEDB_TELEMETRY" to "off"),
                "postgres", "-c", "max_connections=200", "-c", "synchronous_commit=on",
            )
            val proxy = bridge.run("faultproxy", Bridge.composeImage("toxiproxy"), emptyMap(), "-host=0.0.0.0")
            val dbIp = bridge.ip(db)
            val proxyIp = bridge.ip(proxy)
            val directUrl = "jdbc:postgresql://$dbIp:5432/$DB"
            awaitTrue("timescale accepts connections", 180_000, { bridge.tail(db) }) {
                runCatching { DriverManager.getConnection(directUrl, USER, PASSWORD).use { it.isValid(2) } }.getOrDefault(false)
            }
            val toxi = ToxiproxyClient("http://$proxyIp:8474")
            awaitTrue("fault proxy API", 60_000, { bridge.tail(proxy) }) { runCatching { toxi.version() }.isSuccess }
            createProxy(proxyIp, "pg", "0.0.0.0:15432", "timescale:5432")
            val table = "prf008_" + System.nanoTime().toString().takeLast(6)

            tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
            Gateway(tmp, port = 0).use { gw ->
                gw.databases.saveConnection(
                    "soak-pg",
                    mapOf(
                        "driver" to "postgres",
                        "url" to "jdbc:postgresql://$proxyIp:15432/$DB",
                        "username" to USER,
                        "maxWaitMillis" to 2_000,
                        "validationTimeoutMillis" to 1_000,
                        "extraProperties" to mapOf("connectTimeout" to "2", "socketTimeout" to "15", "reWriteBatchedInserts" to "true"),
                    ),
                    0,
                    PasswordUpdate.Set(PASSWORD),
                )
                gw.store.put(Scope.GATEWAY, Resource("HistoryProvider", Metadata("soak-pg", "historian"),
                    mapOf("connection" to "soak-pg", "table" to table, "timescale" to "required")))
                val importStart = System.nanoTime()
                importTags(gw, tags)
                val importSeconds = (System.nanoTime() - importStart) / 1e9
                gw.start()

                fun pending() = gw.historyRouter.buffer.pendingCounts()["soak-pg"] ?: 0L
                fun state() = "collector=${gw.historyCollector.stats()} router=${gw.historyRouter.status()}"
                val samples = Array<TagSample>(tags) { i -> gw.tags.openSample(path(i)) }
                awaitTrue("first sample of every tag forwarded", 300_000, ::state) {
                    gw.historyCollector.pending == 0 && pending() == 0L && rowCount(directUrl, table) == tags.toLong()
                }

                val t0 = (System.currentTimeMillis() / 1_000 + 2) * 1_000
                val written = AtomicLong()
                var writeErrors = 0
                var lateScans = 0
                var maxScanMs = 0.0
                val maxBacklog = AtomicLong()
                var cutStartedAt = 0L
                val restoredAt = AtomicLong()
                val caughtUpAt = AtomicLong()
                val forwarded0 = forwarded(gw)
                fun scan(scan: Int, pct: Int) {
                    val start = System.nanoTime()
                    val stamp = Instant.ofEpochMilli(t0 + scan * 1_000L)
                    val next = scan + 1
                    var n = 0
                    for (i in 0 until tags) {
                        if (!changes(i, scan, pct)) continue
                        val r = samples[i].set(next, stamp)
                        if (r.quality.code != QualityCode.GOOD) writeErrors++
                        n++
                    }
                    written.addAndGet(n.toLong())
                    if (n != tags * pct / 100) writeErrors++
                    maxScanMs = maxOf(maxScanMs, (System.nanoTime() - start) / 1e6)
                }

                val sampler = VmSampler(sampleSeconds).start()
                val stretchBacklog = Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
                val backlogWatch = Thread({
                    while (!Thread.currentThread().isInterrupted) {
                        val p = runCatching { pending() + gw.historyCollector.pending }.getOrDefault(0L)
                        maxBacklog.accumulateAndGet(p, ::maxOf)
                        if (sampler.phase == "stretch") stretchBacklog.add(System.nanoTime() to p)
                        if (restoredAt.get() != 0L && caughtUpAt.get() == 0L && p <= steadyRate) caughtUpAt.set(System.nanoTime())
                        try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                    }
                }, "prf008-backlog").apply { isDaemon = true; start() }

                // Steady phase at the floor rate, with the cut in the middle.
                while (System.currentTimeMillis() < t0) Thread.sleep(5)
                sampler.phase = "steady"
                val steadyStart = System.nanoTime()
                runScans(steadySeconds, 0, { _, _ -> lateScans++ }) { s ->
                    if (s == cutAt) {
                        toxi.cut("pg")
                        cutStartedAt = System.nanoTime()
                    }
                    if (s == cutAt + cutSeconds) {
                        toxi.restore("pg")
                        restoredAt.set(System.nanoTime())
                    }
                    scan(s, percent)
                }
                val steadyWall = (System.nanoTime() - steadyStart) / 1e9
                val steadyWritten = written.get()
                val steadySamples = sampler.samples().filter { it.phase == "steady" }

                // Stretch phase: recorded, not gating.
                sampler.phase = "stretch"
                val stretchStart = System.nanoTime()
                val forwardedBeforeStretch = forwarded(gw)
                runScans(stretchSeconds, steadySeconds, { _, _ -> lateScans++ }) { s -> scan(s, stretchPercent) }
                val stretchWall = (System.nanoTime() - stretchStart) / 1e9
                val stretchWritten = written.get() - steadyWritten
                val stretchForwardedRate = (forwarded(gw) - forwardedBeforeStretch) / stretchWall
                val stretchBacklogSamples = synchronized(stretchBacklog) { stretchBacklog.toList() }
                val stretchBacklogGrowth = backlogGrowthPerMinute(stretchBacklogSamples)
                sampler.phase = "drain"

                val drainStart = System.nanoTime()
                awaitTrue("everything forwarded after the run", drainBudgetMs, ::state) {
                    gw.historyCollector.pending == 0 && pending() == 0L
                }
                val drainSeconds = (System.nanoTime() - drainStart) / 1e9
                backlogWatch.interrupt()
                sampler.close()
                val allSamples = sampler.samples()
                val stretchSamples = allSamples.filter { it.phase == "stretch" }

                val dropped = gw.historyCollector.dropped
                val diverted = gw.historyCollector.diverted
                val quarantined = gw.historyRouter.buffer.quarantineCounts().values.sum()
                val forwardedTotal = forwarded(gw) - forwarded0
                val expectedWrites = steadySeconds.toLong() * steadyRate + stretchSeconds.toLong() * stretchActual
                val check = DirectCheck(directUrl, table, tags, steadySeconds, percent, stretchPercent, t0)

                val steadyCut = Cutoffs(steadySamples, sampleSeconds, req)
                val stretchCut = Cutoffs(stretchSamples, sampleSeconds, req)
                val steadyChangesPerSecond = steadyWritten / steadyWall
                val stretchChangesPerSecond = stretchWritten / stretchWall
                val cutAtSec = if (cutStartedAt == 0L) null else round4((cutStartedAt - steadyStart) / 1e9)
                val catchUp = if (restoredAt.get() == 0L || caughtUpAt.get() == 0L) null else round4((caughtUpAt.get() - restoredAt.get()) / 1e9)
                val lossFree = check.rows == expectedWrites + tags && check.distinct == check.rows && check.ruleViolations == 0L &&
                    check.stampMismatches == 0L && check.notGood == 0L && check.firstSamples == tags.toLong() &&
                    dropped == 0L && diverted == 0L && quarantined == 0L && writeErrors == 0
                val report = linkedMapOf<String, Any?>(
                    "scenario" to "PRF-008",
                    "size" to sizeName(),
                    "label" to if (sizeName() == "full") "full-size" else "CI-size",
                    "floorChangesPerSecond" to floor,
                    "stretchChangesPerSecond" to stretchRate,
                    "tags" to tags,
                    "percent" to percent,
                    "steadySeconds" to steadySeconds,
                    "cutAtSeconds" to cutAt,
                    "cutSeconds" to cutSeconds,
                    "stretchPercent" to stretchPercent,
                    "stretchSeconds" to stretchSeconds,
                    "changeRule" to "(index + scan) % 100 < percent",
                    "network" to "docker-bridge",
                    "measuredProcessors" to sampler.vcpu,
                    "measuredRamBytes" to sampler.ramBytes,
                    "cpuAccounting" to "processCpuTime / wall / availableProcessors, 10 s samples",
                    "memoryAccounting" to "VmRSS / MemTotal",
                    "importSeconds" to round4(importSeconds),
                    "steady" to linkedMapOf(
                        "changesPerSecond" to round4(steadyChangesPerSecond),
                        "written" to steadyWritten,
                        "wallSeconds" to round4(steadyWall),
                    ) + steadyCut.toMap(),
                    "stretch" to linkedMapOf(
                        "gates" to false,
                        "changesPerSecond" to round4(stretchChangesPerSecond),
                        "forwardedPerSecond" to round4(stretchForwardedRate),
                        "backlogStart" to stretchBacklogSamples.firstOrNull()?.second,
                        "backlogEnd" to stretchBacklogSamples.lastOrNull()?.second,
                        "backlogSamples" to stretchBacklogSamples.size,
                        "backlogGrowthPerMinute" to stretchBacklogGrowth?.let { round4(it) },
                        "written" to stretchWritten,
                        "wallSeconds" to round4(stretchWall),
                    ) + stretchCut.toMap(),
                    "cut" to linkedMapOf(
                        "startedAtSeconds" to cutAtSec,
                        "seconds" to cutSeconds,
                        "catchUpSeconds" to catchUp,
                        "maxBacklog" to maxBacklog.get(),
                    ),
                    "drainSeconds" to round4(drainSeconds),
                    "maxScanMs" to round4(maxScanMs),
                    "lateScans" to lateScans,
                    "expectedRows" to expectedWrites + tags,
                    "rows" to check.rows,
                    "distinctTagValues" to check.distinct,
                    "firstSamples" to check.firstSamples,
                    "ruleViolations" to check.ruleViolations,
                    "stampMismatches" to check.stampMismatches,
                    "notGood" to check.notGood,
                    "forwarded" to forwardedTotal,
                    "dropped" to dropped,
                    "diverted" to diverted,
                    "quarantined" to quarantined,
                    "writeErrors" to writeErrors,
                    "hypertable" to check.hypertable,
                    "zeroLoss" to lossFree,
                    "insideCutoffs" to steadyCut.inside,
                )
                writeReport("PRF-008", report, markdown(report, steadyCut, stretchCut))
                println("PRF-008 report ${mapper.writeValueAsString(report)}")
                println(
                    "PRF-008 steady=${fmt(steadyChangesPerSecond)}/s stretch=${fmt(stretchChangesPerSecond)}/s " +
                        "forward=${fmt(stretchForwardedRate)}/s backlogGrowthPerMinute=${stretchBacklogGrowth?.let { fmt(it) }} " +
                        "rows=${check.rows} expected=${expectedWrites + tags} backlog=${maxBacklog.get()} catchUp=${catchUp}s " +
                        "drain=${round4(drainSeconds)}s steady[${steadyCut.describe()}] stretch[${stretchCut.describe()}]",
                )

                assertTrue(cutStartedAt != 0L && restoredAt.get() != 0L, "the database link was cut and restored")
                assertTrue(maxBacklog.get() > steadyRate, "the cut built a backlog in the forward buffer (${maxBacklog.get()})")
                assertEquals(0, writeErrors, "tag write errors")
                assertEquals(expectedWrites, written.get(), "every scheduled change was written")
                assertTrue(steadyChangesPerSecond >= steadyRate * 0.99, "steady rate ${fmt(steadyChangesPerSecond)}/s below $steadyRate")
                assertEquals(0L, dropped, "collector dropped samples")
                assertEquals(0L, diverted, "collector diverted samples")
                assertEquals(0L, quarantined, "quarantined records")
                assertEquals(tags.toLong(), check.firstSamples, "one first sample per tag")
                assertEquals(expectedWrites + tags, check.rows, "row count: a lost or extra sample")
                assertEquals(check.rows, check.distinct, "duplicate (tag, value) rows")
                assertEquals(0L, check.ruleViolations, "rows that break the change rule")
                assertEquals(0L, check.stampMismatches, "rows with the wrong timestamp")
                assertEquals(0L, check.notGood, "rows without Good quality")
                assertTrue(check.hypertable, "TimescaleDB hypertable")
                assertTrue(steadyCut.inside, "PRF-008 steady phase crossed a cutoff: ${steadyCut.describe()}")
                assertTrue(lossFree, "PRF-008 zero loss")
            }
        }
    }

    private fun importTags(gw: Gateway, tags: Int) {
        var offset = 0
        while (offset < tags) {
            val end = minOf(offset + BATCH, tags)
            val tagBatch = ArrayList<Resource>(end - offset)
            val historyBatch = ArrayList<Resource>(end - offset)
            for (i in offset until end) {
                val name = name(i)
                tagBatch += Resource("Tag", Metadata(name, "tags"),
                    mapOf("provider" to "default", "path" to "soak/$name", "type" to "memory", "dataType" to "Int32", "value" to 0))
                historyBatch += Resource("HistorySettings", Metadata(name, "historian"),
                    mapOf("tagPath" to path(i), "storageProvider" to "soak-pg"))
            }
            gw.store.putAll(Scope.GATEWAY, tagBatch, actor = "prf-008", syncEach = false)
            gw.store.putAll(Scope.GATEWAY, historyBatch, actor = "prf-008", syncEach = false)
            offset = end
        }
    }

    /** (last pending − first pending) / minutes across [samples] of (nanoTime, pending). */
    private fun backlogGrowthPerMinute(samples: List<Pair<Long, Long>>): Double? {
        if (samples.size < 2) return null
        val minutes = (samples.last().first - samples.first().first) / 60_000_000_000.0
        if (minutes <= 0.0) return null
        return (samples.last().second - samples.first().second) / minutes
    }

    private fun forwarded(gw: Gateway): Long =
        (gw.historyRouter.status().single { it["name"] == "soak-pg" }["forwarded"] as Number).toLong()

    private fun rowCount(url: String, table: String): Long = runCatching {
        DriverManager.getConnection(url, USER, PASSWORD).use { c -> c.long("SELECT count(*) FROM $table") }
    }.getOrDefault(-1L)

    private fun createProxy(ip: String, name: String, listen: String, upstream: String) {
        val body = """{"name":"$name","listen":"$listen","upstream":"$upstream","enabled":true}"""
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://$ip:8474/proxies"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "create proxy -> ${response.statusCode()}: ${response.body()}" }
    }

    /** The loss verdict, read straight from TimescaleDB (not through the proxy or the gateway). */
    private class DirectCheck(url: String, table: String, tags: Int, steadyScans: Int, pct: Int, stretchPct: Int, t0: Long) {
        val rows: Long
        val distinct: Long
        val firstSamples: Long
        val ruleViolations: Long
        val stampMismatches: Long
        val notGood: Long
        val hypertable: Boolean

        init {
            DriverManager.getConnection(url, USER, PASSWORD).use { c ->
                rows = c.long("SELECT count(*) FROM $table")
                distinct = c.long("SELECT count(*) FROM (SELECT DISTINCT tag_path, value_text FROM $table) d")
                firstSamples = c.long("SELECT count(DISTINCT tag_path) FROM $table WHERE value_text = '0'")
                val idx = "CAST(substring(tag_path from 'p([0-9]+)$') AS bigint)"
                val scan = "(CAST(value_text AS bigint) - 1)"
                ruleViolations = c.long(
                    "SELECT count(*) FROM $table WHERE value_text <> '0' AND " +
                        "NOT (($idx + $scan) % 100 < CASE WHEN $scan < $steadyScans THEN $pct ELSE $stretchPct END) " +
                        "OR $idx IS NULL OR $idx >= $tags",
                )
                stampMismatches = c.long(
                    "SELECT count(*) FROM $table WHERE value_text <> '0' AND " +
                        "CAST(round(extract(epoch from ts) * 1000) AS bigint) <> $t0 + $scan * 1000",
                )
                notGood = c.long("SELECT count(*) FROM $table WHERE quality NOT LIKE 'Good%'")
                hypertable = c.long("SELECT count(*) FROM timescaledb_information.hypertables WHERE hypertable_name = '$table'") == 1L
            }
        }
    }

    private fun markdown(r: Map<String, Any?>, steady: Cutoffs, stretch: Cutoffs): String {
        @Suppress("UNCHECKED_CAST") val st = r["steady"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val sx = r["stretch"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val cut = r["cut"] as Map<String, Any?>
        val label = r["label"]
        return """
            |# PRF-008 historian soak ($label)
            |
            |This is a $label run of `HistorianSoakTest` (`./gradlew :test-infra:load-soak:historianSoakTest`). It is not the 3 h floor run on reference hardware; PRF-008 stays `implemented: false` in test-infra/perf/perf-requirements.json until that run exists. Sizes are in test-infra/perf/prf-008.json.
            |
            |Topology: TimescaleDB and the fault proxy on one Docker bridge; the gateway in the test JVM reaches the database only through the proxy's bridge address. History is the production path: on-change HistorySettings per tag, SQL HistoryProvider on a db/Connection, store-and-forward buffer, forwarder.
            |
            |Load: ${r["tags"]} memory tags, one 1 s scan, rule `(index + scan) % 100 < percent`. Steady ${r["percent"]}% for ${r["steadySeconds"]} s with the database link cut for ${r["cutSeconds"]} s at ${cut["startedAtSeconds"]} s. Stretch ${r["stretchPercent"]}% for ${r["stretchSeconds"]} s. Machine: ${r["measuredProcessors"]} vCPU, ${(r["measuredRamBytes"] as Long) shr 20} MiB.
            |
            || Metric | Steady (gates) | Stretch (recorded) | Cutoff |
            ||---|---:|---:|---:|
            || Changes/s | ${st["changesPerSecond"]} | ${sx["changesPerSecond"]} | floor ${r["floorChangesPerSecond"]}, stretch ${r["stretchChangesPerSecond"]} |
            || Forward/s |  | ${sx["forwardedPerSecond"]} | beside the stretch ingest figure |
            || Backlog growth/min |  | ${sx["backlogGrowthPerMinute"]} | ${sx["backlogStart"]} at stretch start, ${sx["backlogEnd"]} at stretch end (${sx["backlogSamples"]} samples) |
            || CPU % mean | ${round4(steady.cpuMean)} | ${round4(stretch.cpuMean)} | 50 |
            || CPU % worst 60 s | ${round4(steady.cpuWorst60)} | ${round4(stretch.cpuWorst60)} | 50 |
            || Memory % mean | ${round4(steady.memMean)} | ${round4(stretch.memMean)} | 50 |
            || Memory % worst 60 s | ${round4(steady.memWorst60)} | ${round4(stretch.memWorst60)} | 50 |
            || Clock drift % mean | ${round4(steady.driftMean)} | ${round4(stretch.driftMean)} | +/-10 |
            || Clock drift % worst 60 s | ${round4(steady.driftWorst60)} | ${round4(stretch.driftWorst60)} | +/-10 |
            |
            |Cut: backlog peaked at ${cut["maxBacklog"]} samples; the buffer was back under one second of load ${cut["catchUpSeconds"]} s after the link came back. Stretch ingest ${sx["changesPerSecond"]}/s, forward ${sx["forwardedPerSecond"]}/s, backlog growth ${sx["backlogGrowthPerMinute"]} per minute. Drain after the last scan ${r["drainSeconds"]} s.
            |
            || Loss check | Value | Expected |
            ||---|---:|---:|
            || Rows | ${r["rows"]} | ${r["expectedRows"]} |
            || Distinct (tag, value) | ${r["distinctTagValues"]} | ${r["rows"]} |
            || First samples (value 0) | ${r["firstSamples"]} | ${r["tags"]} |
            || Rows breaking the change rule | ${r["ruleViolations"]} | 0 |
            || Rows with the wrong timestamp | ${r["stampMismatches"]} | 0 |
            || Rows not Good | ${r["notGood"]} | 0 |
            || Collector dropped / diverted | ${r["dropped"]} / ${r["diverted"]} | 0 / 0 |
            || Quarantined | ${r["quarantined"]} | 0 |
            |
            |Zero loss: ${r["zeroLoss"]}. Steady phase inside the cutoffs: ${r["insideCutoffs"]}.
            |
            |CPU is the test JVM, which hosts the gateway and the load generator, so it overstates gateway CPU. The database runs in its own container on the same machine; its CPU is not counted.
            |""".trimMargin()
    }

    private companion object {
        const val USER = "oip"
        const val PASSWORD = "oip"
        const val DB = "oip"
        const val BATCH = 5_000

        fun name(i: Int) = "p%07d".format(i)
        fun path(i: Int) = "[default]soak/${name(i)}"

        fun Connection.long(sql: String): Long = createStatement().use { st ->
            st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
        }
    }
}
