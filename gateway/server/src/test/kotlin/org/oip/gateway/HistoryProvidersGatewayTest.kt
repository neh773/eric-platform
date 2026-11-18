package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.historian.EmbeddedHistorianSettings
import org.oip.historian.HistoryQuery
import org.oip.historian.MaintenanceMode
import org.oip.historian.PartitionInterval
import org.oip.historian.StoreAndForwardSettings
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.writeText

/**
 * History provider types over a live [Gateway]: simulator and CSV providers read through the history API, a splitter
 * writing to the built-in embedded historian and to a SQL provider that cannot connect, and the embedded historian and
 * store-and-forward engine settings applied from config (doc source: FEATURES HIS-006, HIS-007, HIS-009, HIS-024).
 */
class HistoryProvidersGatewayTest {
    @TempDir lateinit var tmp: Path

    private val clock = MutableClock(Instant.parse("2026-06-01T00:00:00Z"))

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun prepare() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
    }

    private fun Gateway.put(kind: String, name: String, spec: Map<String, Any?>) {
        val id = Resource(kind, Metadata(name, "historian"), emptyMap()).id()
        val stored = store.get(id)
        store.put(Scope.GATEWAY, stored?.copy(spec = spec) ?: Resource(kind, Metadata(name, "historian"), spec))
    }

    @Suppress("UNCHECKED_CAST")
    private fun Http.Res.samples() = json["samples"] as List<Map<String, Any?>>

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun simulatorAndCsvProvidersAnswerTheHistoryApi() {
        prepare()
        Files.createDirectories(tmp.resolve("history-csv"))
        tmp.resolve("history-csv/line1.csv").writeText(
            "timestamp,[default]Line/Flow,[default]Line/State\n" +
                "2026-05-01T00:00:00Z,12.5,running\n" +
                "2026-05-01T00:01:00Z,13,stopped\n"
        )
        Gateway(tmp, port = 0, clock = clock).use { gw ->
            gw.put("HistoryProvider", "sim", mapOf("type" to "simulator"))
            gw.put("HistoryProvider", "line1", mapOf("type" to "csv", "file" to "line1.csv"))
            gw.start()
            val http = Http(gw.port())
            gw.loginAdmin(http)

            val from = Instant.parse("2026-05-01T00:00:00Z")
            val sim = http.get("/api/v1/history/data?provider=sim&tagPath=${enc("Test/sine_60s_10_1s")}&from=$from&to=${from.plusSeconds(59)}")
            assertEquals(200, sim.status, sim.body)
            assertEquals("sim", sim.json["provider"])
            val values = sim.samples()
            assertEquals(60, values.size, "one point per second")
            assertEquals(from.toString(), values.first()["timestamp"])
            assertEquals(0.0, (values.first()["value"] as Number).toDouble(), 1e-9)
            assertEquals(10.0, (values[15]["value"] as Number).toDouble(), 1e-9, "a quarter period in, the sine is at its amplitude")
            assertTrue(values.all { kotlin.math.abs((it["value"] as Number).toDouble()) <= 10.0 + 1e-9 })

            val bad = http.get("/api/v1/history/data?provider=sim&tagPath=${enc("Test/nothing")}&from=$from&to=${from.plusSeconds(1)}")
            assertEquals(200, bad.status, bad.body)
            assertEquals(0, bad.samples().size, "a path that does not name a signal has no history")

            val csv = http.get("/api/v1/history/data?provider=line1&tagPath=${enc("Line/Flow")}&from=$from&to=${from.plusSeconds(3600)}")
            assertEquals(200, csv.status, csv.body)
            assertEquals(listOf(12.5, 13.0), csv.samples().map { (it["value"] as Number).toDouble() })
            val state = http.get("/api/v1/history/data?provider=line1&tagPath=${enc("Line/State")}&from=$from&to=${from.plusSeconds(3600)}")
            assertEquals(listOf("running", "stopped"), state.samples().map { it["value"] })
        }
    }

    @Test
    fun aSplitterStoresLocallyWhileItsSqlTargetIsUnreachableAndConfigTunesTheHistorian() {
        prepare()
        Gateway(tmp, port = 0, clock = clock).use { gw ->
            gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("speed", "tags"),
                mapOf("path" to "Plant/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0)))
            gw.put("HistoryProvider", "plant-pg", mapOf("connection" to "missing-db", "storeAndForward" to mapOf("retryMillis" to 60_000)))
            // The forward rate is measured on [clock], which stops once the writes are done: with the default rate a
            // forward pass that lands before the collector's flush leaves the splitter's queue waiting for time that
            // never passes. A rate of 0 keeps the test about the splitter, not about forward pacing.
            gw.put("HistoryProvider", "both", mapOf("type" to "splitter", "first" to "local", "second" to "plant-pg",
                "storeAndForward" to mapOf("forwardRateMillis" to 0)))
            gw.put("HistorySettings", "speed", mapOf("tagPath" to "Plant/Speed", "storageProvider" to "both"))
            gw.put("StoreAndForward", "engine", mapOf("timeThresholdMillis" to 20, "dataThreshold" to 50, "memoryBufferSize" to 10_000))
            gw.put("HistoryProvider", "local", mapOf("type" to "embedded", "partitionInterval" to "month", "maintenance" to mapOf("mode" to "prune", "age" to 6)))
            assertEquals(StoreAndForwardSettings(memoryBufferSize = 10_000, timeThresholdMillis = 20, dataThreshold = 50), gw.storeAndForwardSettings)
            assertEquals(EmbeddedHistorianSettings(PartitionInterval.MONTH, true, MaintenanceMode.PRUNE, 6, PartitionInterval.MONTH), gw.historyStore.settings)
            gw.start()
            for (v in listOf(2.0, 3.0, 4.0)) {
                clock.advance(Duration.ofSeconds(1))
                gw.tags.write("[default]Plant/Speed", v)
            }
            val all = HistoryQuery("[default]Plant/Speed", 0, Long.MAX_VALUE)
            waitUntil("the local copy") { gw.historyStore.query(all).size == 4 }
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), gw.historyStore.query(all).map { it.value })
            waitUntil("the sql copy to wait in the disk cache") { (gw.historyRouter.buffer.pendingCounts()["plant-pg"] ?: 0L) == 4L }
            assertEquals(0L, gw.historyRouter.buffer.pendingCounts()["both"] ?: 0L, "nothing is left on the splitter itself")

            val http = Http(gw.port())
            gw.loginAdmin(http)
            val read = http.get("/api/v1/history/data?tagPath=${enc("Plant/Speed")}&from=0&to=${clock.millis()}")
            assertEquals(200, read.status, read.body)
            assertEquals("both", read.json["provider"])
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), read.samples().map { (it["value"] as Number).toDouble() }, "the splitter reads its first provider")

            gw.store.delete(Resource("HistoryProvider", Metadata("local", "historian"), emptyMap()).id())
            assertEquals(EmbeddedHistorianSettings(), gw.historyStore.settings)
        }
    }
}
