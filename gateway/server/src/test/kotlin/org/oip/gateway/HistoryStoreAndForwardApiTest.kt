package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.historian.ForwardRecord
import org.oip.historian.HistoryQuery
import org.oip.historian.HistoryReadException
import org.oip.historian.HistorySample
import org.oip.historian.HistoryWriteException
import org.oip.historian.RemoteHistoryProvider
import org.oip.security.Permissions
import java.net.URLEncoder
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * M3.1 rest of history over a live [Gateway]: aggregates REST route, per-tag storage provider, store-and-forward
 * status, quarantine list/retry/delete, and quarantine export. A provider that needs Postgres is replaced by an in-process one
 * registered on the router; the SQL provider itself is covered by PostgresHistoryProviderTest and
 * SimulatorHistoryTest. Docs: -
 */
class HistoryStoreAndForwardApiTest {
    @TempDir lateinit var tmp: Path

    private val clock = MutableClock(Instant.parse("2026-06-01T00:00:00Z"))
    private val t0 = clock.millis()

    private fun gateway() = Gateway(tmp, port = 0, clock = clock)
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun prepare() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
    }

    private fun Gateway.memoryTag(name: String, path: String, value: Any?) {
        store.put(Scope.GATEWAY, Resource("Tag", Metadata(name, "tags"),
            mapOf("path" to path, "type" to "memory", "dataType" to "Double", "value" to value)))
    }

    private fun Gateway.history(name: String, spec: Map<String, Any?>) {
        store.put(Scope.GATEWAY, Resource("HistorySettings", Metadata(name, "historian"), spec))
    }

    private fun write(gw: Gateway, path: String, value: Any?) {
        clock.advance(Duration.ofSeconds(1))
        gw.tags.write(path, value)
    }

    private fun assertProblem(r: Http.Res, status: Int) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun Http.Res.list(key: String) = json[key] as List<Map<String, Any?>>

    /** Stand-in for a SQL provider: rejects values over 100 permanently, and can be cut. */
    private class Remote(
        override val name: String,
        private val rejectMessage: String = "22003: value out of range",
    ) : RemoteHistoryProvider {
        val rows = ArrayList<HistorySample>()
        private val keys = HashSet<Pair<String, Long>>()
        @Volatile var down = false
        /** Values strictly above this are a permanent rejection. Raised in the import test once the data is fixed. */
        @Volatile var rejectAbove = 100.0
        @Synchronized override fun write(records: List<ForwardRecord>) {
            if (down) throw HistoryWriteException("connection refused", permanent = false)
            records.firstOrNull { ((it.sample.value as? Number)?.toDouble() ?: Double.NEGATIVE_INFINITY) > rejectAbove }
                ?.let { throw HistoryWriteException(rejectMessage, permanent = true) }
            for (r in records) if (keys.add(r.sourceId to r.seq)) rows += r.sample
        }
        @Synchronized override fun query(query: HistoryQuery): List<HistorySample> {
            if (down) throw HistoryReadException("connection refused")
            return rows.filter { it.tagPath.equals(query.tagPath, true) && it.timestampMillis in query.fromMillis..query.toMillis }.take(query.limit)
        }
        override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) =
            query(HistoryQuery(tagPath, fromMillis, toMillis, HistoryQuery.MAX_LIMIT)).forEach(each)
        @Synchronized override fun latestBefore(tagPath: String, beforeMillis: Long) =
            rows.filter { it.tagPath.equals(tagPath, true) && it.timestampMillis < beforeMillis }.maxByOrNull { it.timestampMillis }
    }

    @Test
    fun aggregateRouteReturnsOneValuePerWindowAndValidatesItsParameters() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("speed", "Plant/Speed", 10.0)
            gw.history("speed", mapOf("tagPath" to "Plant/Speed"))
            gw.start()
            for (v in listOf(20.0, 30.0, 40.0)) write(gw, "[default]Plant/Speed", v) // t0+1s, +2s, +3s
            val http = Http(gw.port())
            assertProblem(http.get("/api/v1/history/aggregate?tagPath=Plant/Speed&from=0&to=1&interval=1"), 401)
            gw.loginAdmin(http)
            fun agg(q: String) = http.get("/api/v1/history/aggregate?tagPath=${enc("Plant/Speed")}&$q")
            val max = agg("from=$t0&to=${t0 + 3999}&interval=PT2S&aggregate=maximum")
            assertEquals(200, max.status, max.body)
            assertEquals("Maximum", max.json["aggregate"])
            assertEquals("local", max.json["provider"])
            assertEquals(2000, (max.json["intervalMillis"] as Number).toInt())
            assertEquals(listOf(20.0, 40.0), max.list("windows").map { (it["value"] as Number).toDouble() })
            assertEquals(listOf("2026-06-01T00:00:00Z", "2026-06-01T00:00:02Z"), max.list("windows").map { it["start"] })
            // Time-weighted average over [t0, t0+4s): 10,20,30,40 for 1 s each.
            val avg = agg("from=$t0&to=${t0 + 3999}&interval=4000")
            assertEquals(25.0, (avg.list("windows").single()["value"] as Number).toDouble(), 1e-9)
            assertEquals(4, (agg("from=$t0&to=${t0 + 3999}&interval=4000&aggregate=Count").list("windows").single()["value"] as Number).toInt())
            // Default is no interpolation: no edge values in the response.
            assertEquals("None", avg.json["interpolation"])
            assertTrue("startValue" !in avg.list("windows").single())
            // Linear: trapezoids 15, 25, 35 for 1 s each, then 40 held for 1 s -> 115 / 4.
            val linear = agg("from=$t0&to=${t0 + 3999}&interval=4000&interpolation=linear")
            assertEquals(200, linear.status, linear.body)
            assertEquals("Linear", linear.json["interpolation"])
            val lw = linear.list("windows").single()
            assertEquals(28.75, (lw["value"] as Number).toDouble(), 1e-9)
            assertEquals(10.0, (lw["startValue"] as Number).toDouble())
            assertEquals(40.0, (lw["endValue"] as Number).toDouble())
            assertProblem(agg("from=0&to=10&interval=1&interpolation=cubic"), 422)

            assertProblem(agg("from=0&to=10"), 400) // interval missing
            assertProblem(agg("from=0&to=10&interval=soon"), 400)
            assertProblem(agg("from=0&to=10&interval=0"), 422)
            assertProblem(agg("from=0&to=10&interval=1&aggregate=Median"), 422)
            assertProblem(agg("from=0&to=100000&interval=1"), 422) // more than 10000 windows
            assertProblem(agg("from=10&to=0&interval=1"), 422)
            assertProblem(agg("from=0&to=10&interval=1&provider=nope"), 422)
            assertProblem(agg("from=0&to=10&interval=1&provider=Bad_Name"), 422)
        }
    }

    @Test
    fun aRemoteProviderGetsSamplesThroughStoreAndForwardAndRejectedOnesAreQuarantined() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("flow", "Plant/Flow", 1.0)
            gw.start()
            val remote = Remote("plant-pg")
            gw.historyRouter.register(remote)
            val http = Http(gw.port())
            gw.loginAdmin(http)
            assertEquals(201, http.put("/api/v1/config/historian/gateway/HistorySettings/flow",
                """{"apiVersion":"oip/v1","kind":"HistorySettings","metadata":{"name":"flow","module":"historian","version":0},"spec":{"tagPath":"Plant/Flow","storageProvider":"plant-pg"}}""").status)
            write(gw, "[default]Plant/Flow", 2.0)
            write(gw, "[default]Plant/Flow", 500.0) // the provider rejects this one
            write(gw, "[default]Plant/Flow", 3.0)

            // The query flushes and forwards; the tag's provider is the default.
            val data = http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}")
            assertEquals(200, data.status, data.body)
            assertEquals("plant-pg", data.json["provider"])
            assertEquals(listOf(1.0, 2.0, 3.0), data.list("samples").map { (it["value"] as Number).toDouble() })
            assertEquals(0, http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}&provider=local").list("samples").size)

            val status = http.get("/api/v1/diagnostics/history/providers")
            assertEquals(200, status.status, status.body)
            val pg = status.list("providers").single { it["name"] == "plant-pg" }
            assertEquals(1, (pg["quarantined"] as Number).toInt())
            assertEquals(0, (pg["pending"] as Number).toInt())
            assertEquals(true, pg["connected"])
            val collector = status.json["collector"] as Map<*, *>
            assertEquals(0, (collector["consecutiveFlushFailures"] as Number).toInt())
            assertEquals(0, (collector["pending"] as Number).toInt())

            val q = http.get("/api/v1/diagnostics/history/quarantine?provider=plant-pg")
            assertEquals(200, q.status, q.body)
            val rec = q.list("records").single()
            assertEquals(500.0, (rec["value"] as Number).toDouble())
            assertEquals("[default]Plant/Flow", rec["tagPath"])
            assertTrue((rec["error"] as String).startsWith("22003"), rec["error"] as String)
            val id = (rec["id"] as Number).toLong()

            assertProblem(http.post("/api/v1/diagnostics/history/quarantine/retry", "{}"), 422)
            assertProblem(http.post("/api/v1/diagnostics/history/quarantine/retry", """{"ids":["x"]}"""), 422)
            assertProblem(http.post("/api/v1/diagnostics/history/quarantine/retry", """{"ids":[1],"extra":1}"""), 422)
            // Still rejected after a retry: back in quarantine, and the queue is not blocked.
            assertEquals(1, (http.post("/api/v1/diagnostics/history/quarantine/retry", """{"ids":[$id]}""").json["retried"] as Number).toInt())
            write(gw, "[default]Plant/Flow", 4.0)
            http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}")
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), remote.rows.map { it.value })
            val again = http.get("/api/v1/diagnostics/history/quarantine").list("records")
            assertEquals(1, again.size)
            assertEquals(1, (http.post("/api/v1/diagnostics/history/quarantine/delete", """{"provider":"plant-pg"}""").json["deleted"] as Number).toInt())
            assertEquals(0, http.get("/api/v1/diagnostics/history/quarantine").list("records").size)

            // Unreachable: the read is a 503, samples stay buffered and arrive after reconnect.
            remote.down = true
            write(gw, "[default]Plant/Flow", 5.0)
            assertProblem(http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}"), 503)
            assertEquals(1, (http.get("/api/v1/diagnostics/history/providers").list("providers").single { it["name"] == "plant-pg" }["pending"] as Number).toInt())
            remote.down = false
            clock.advance(Duration.ofSeconds(10))
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0),
                http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}").list("samples").map { (it["value"] as Number).toDouble() })
            assertTrue(gw.dataDir.resolve("db/history-forward.sqlite").exists())
        }
    }

    @Test
    fun aProviderResourceOnAMissingConnectionKeepsRecordsQueuedAndStatusSaysWhy() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("level", "Plant/Level", 1.0)
            gw.store.put(Scope.GATEWAY, Resource("HistoryProvider", Metadata("plant-pg", "historian"), mapOf("connection" to "missing-db")))
            gw.history("level", mapOf("tagPath" to "Plant/Level", "storageProvider" to "plant-pg"))
            gw.start()
            write(gw, "[default]Plant/Level", 2.0)
            gw.historyCollector.flush()
            gw.historyRouter.forward()
            val http = Http(gw.port())
            gw.loginAdmin(http)
            val pg = http.get("/api/v1/diagnostics/history/providers").list("providers").single { it["name"] == "plant-pg" }
            assertEquals(true, pg["configured"])
            assertEquals(false, pg["connected"])
            assertEquals(2, (pg["pending"] as Number).toInt())
            assertTrue((pg["lastError"] as String).contains("missing-db"), pg["lastError"] as String)
            assertProblem(http.get("/api/v1/history/data?tagPath=${enc("Plant/Level")}&from=0&to=10"), 503)
            assertEquals(0, (http.post("/api/v1/diagnostics/history/quarantine/delete", """{"provider":"plant-pg"}""").json["deleted"] as Number).toInt())
            assertProblem(Http(gw.port()).get("/api/v1/diagnostics/history/providers"), 401)
        }
    }

    @Test
    fun quarantinedValuesNeedTagsReadAsWellAsGatewayRead() {
        prepare()
        gateway().use { gw ->
            gw.start()
            val diagOnly = gw.security.apiKeys.create(
                "diag-only", setOf(Permissions.GATEWAY_READ), secureConnectionRequired = false, actor = "test",
            )
            val diagAndTags = gw.security.apiKeys.create(
                "diag-tags", setOf(Permissions.GATEWAY_READ, Permissions.TAGS_READ), secureConnectionRequired = false, actor = "test",
            )
            val tagsOnly = gw.security.apiKeys.create(
                "tags-only", setOf(Permissions.TAGS_READ), secureConnectionRequired = false, actor = "test",
            )
            fun client(token: String) = Http(gw.port()).also { it.authorizeBearer(token) }
            // gateway.read alone still sees the status page, but not the quarantined values.
            assertEquals(200, client(diagOnly.token).get("/api/v1/diagnostics/history/providers").status)
            val denied = client(diagOnly.token).get("/api/v1/diagnostics/history/quarantine")
            assertProblem(denied, 403)
            assertTrue(denied.body.contains("tags.read"), denied.body)
            assertProblem(client(tagsOnly.token).get("/api/v1/diagnostics/history/quarantine"), 403)
            val allowed = client(diagAndTags.token).get("/api/v1/diagnostics/history/quarantine")
            assertEquals(200, allowed.status, allowed.body)
            assertEquals(0, allowed.list("records").size)
        }
    }

    /**
     * HIS-026: export downloads the quarantine list as JSON (default) or CSV, and needs gateway.write.
     * Viewing the list stays gateway.read plus tags.read.
     */
    @Test
    fun quarantineExportDownloadsTheListedRecordsAndNeedsGatewayWrite() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("flow", "Plant/Flow", 1.0)
            gw.start()
            // A comma and a leading '=' exercise CSV quoting and the spreadsheet-formula prefix.
            val remote = Remote("plant-pg", "=22003: out of range, rejected")
            gw.historyRouter.register(remote)
            val http = Http(gw.port())
            gw.loginAdmin(http)
            assertEquals(201, http.put("/api/v1/config/historian/gateway/HistorySettings/flow",
                """{"apiVersion":"oip/v1","kind":"HistorySettings","metadata":{"name":"flow","module":"historian","version":0},"spec":{"tagPath":"Plant/Flow","storageProvider":"plant-pg"}}""").status)
            write(gw, "[default]Plant/Flow", 500.0)
            http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}")

            val listed = http.get("/api/v1/diagnostics/history/quarantine?provider=plant-pg")
            assertEquals(200, listed.status, listed.body)
            val rec = listed.list("records").single()
            assertEquals(500.0, (rec["value"] as Number).toDouble())
            val id = (rec["id"] as Number).toLong()

            val exported = http.get("$QUARANTINE_EXPORT_PATH?provider=plant-pg")
            assertEquals(200, exported.status, exported.body)
            assertTrue(exported.header("Content-Type")!!.startsWith("application/json"), exported.header("Content-Type"))
            assertTrue(exported.header("Content-Disposition")!!.contains("attachment"), exported.header("Content-Disposition"))
            assertTrue(exported.header("Content-Disposition")!!.contains("filename=\"quarantine-plant-pg.json\""))
            assertEquals("no-store", exported.header("Cache-Control"))
            assertEquals(listed.body, exported.body)
            // format is case-insensitive, and ids narrows the same list.
            assertEquals(listed.body, http.get("$QUARANTINE_EXPORT_PATH?format=JSON&ids=$id").body)
            assertEquals(0, http.get("$QUARANTINE_EXPORT_PATH?format=json&ids=0").list("records").size)
            assertEquals(0, http.get("$QUARANTINE_EXPORT_PATH?format=json&ids=999999").list("records").size)

            val csv = http.get("$QUARANTINE_EXPORT_PATH?format=csv&provider=plant-pg")
            assertEquals(200, csv.status, csv.body)
            assertTrue(csv.header("Content-Type")!!.startsWith("text/csv"), csv.header("Content-Type"))
            assertTrue(csv.header("Content-Disposition")!!.contains("filename=\"quarantine-plant-pg.csv\""))
            val lines = csv.body.trimEnd().split("\r\n")
            assertEquals(listOf("id,provider,tagPath,timestamp,value,quality,error,quarantinedAt"), lines.take(1))
            assertEquals(2, lines.size)
            assertTrue(lines[1].contains("plant-pg"), lines[1])
            assertTrue(lines[1].contains("[default]Plant/Flow"), lines[1])
            assertTrue(lines[1].contains("500"), lines[1])
            assertTrue(lines[1].contains("\"'=22003: out of range, rejected\""), lines[1])

            assertProblem(http.get("$QUARANTINE_EXPORT_PATH?format=xml"), 422)
            assertProblem(http.get("$QUARANTINE_EXPORT_PATH?ids=nope"), 422)
            assertProblem(http.get("$QUARANTINE_EXPORT_PATH?limit=0"), 422)
            assertProblem(http.get("$QUARANTINE_EXPORT_PATH?provider=Bad_Name"), 422)
            assertProblem(Http(gw.port()).get(QUARANTINE_EXPORT_PATH), 401)

            val reader = gw.security.apiKeys.create(
                "export-read", setOf(Permissions.GATEWAY_READ, Permissions.TAGS_READ), secureConnectionRequired = false, actor = "test",
            )
            val writer = gw.security.apiKeys.create(
                "export-write", setOf(Permissions.GATEWAY_WRITE), secureConnectionRequired = false, actor = "test",
            )
            val both = gw.security.apiKeys.create(
                "export-both", setOf(Permissions.GATEWAY_WRITE, Permissions.TAGS_READ), secureConnectionRequired = false, actor = "test",
            )
            fun client(token: String) = Http(gw.port()).also { it.authorizeBearer(token) }
            val deniedRead = client(reader.token).get("$QUARANTINE_EXPORT_PATH?provider=plant-pg")
            assertProblem(deniedRead, 403)
            assertTrue(deniedRead.body.contains("gateway.write"), deniedRead.body)
            assertFalse(deniedRead.body.contains("Plant/Flow"), deniedRead.body)
            val deniedTags = client(writer.token).get("$QUARANTINE_EXPORT_PATH?provider=plant-pg")
            assertProblem(deniedTags, 403)
            assertTrue(deniedTags.body.contains("tags.read"), deniedTags.body)
            assertFalse(deniedTags.body.contains("500"), deniedTags.body)
            val allowed = client(both.token).get("$QUARANTINE_EXPORT_PATH?format=json&provider=plant-pg")
            assertEquals(200, allowed.status, allowed.body)
            assertEquals(listed.body, allowed.body)

            assertEquals("gateway.write", GatewayAuth.explicitRule("GET", QUARANTINE_EXPORT_PATH))
            assertEquals("gateway.write", GatewayAuth.explicitRule("HEAD", QUARANTINE_EXPORT_PATH))
            assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/diagnostics/history/quarantine"))
            assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/diagnostics/history/quarantine/retry"))
            assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/diagnostics/history/quarantine/delete"))
            assertTrue(OpenApi.document.toString().contains(QUARANTINE_EXPORT_PATH))
        }
    }

    /**
     * Import reads a quarantine export and retries those records. A malformed or oversized file is a 4xx
     * and does not change the quarantine or the disk cache. gateway.write, the same rule as retry and delete.
     * (doc source: FEATURES HIS-025)
     */
    @Test
    fun quarantineImportRetriesAnExportAndRejectsABadFileWithoutWriting() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("flow", "Plant/Flow", 1.0)
            gw.start()
            val remote = Remote("plant-pg")
            gw.historyRouter.register(remote)
            val http = Http(gw.port())
            gw.loginAdmin(http)
            assertEquals(201, http.put("/api/v1/config/historian/gateway/HistorySettings/flow",
                """{"apiVersion":"oip/v1","kind":"HistorySettings","metadata":{"name":"flow","module":"historian","version":0},"spec":{"tagPath":"Plant/Flow","storageProvider":"plant-pg"}}""").status)
            write(gw, "[default]Plant/Flow", 500.0)
            http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}")

            val exported = http.get("$QUARANTINE_EXPORT_PATH?format=json&provider=plant-pg")
            assertEquals(200, exported.status, exported.body)
            val csv = http.get("$QUARANTINE_EXPORT_PATH?format=csv&provider=plant-pg")
            assertEquals(1, (http.post("/api/v1/diagnostics/history/quarantine/delete", """{"provider":"plant-pg"}""").json["deleted"] as Number).toInt())
            assertEquals(0, http.get("/api/v1/diagnostics/history/quarantine").list("records").size)

            val pendingBefore = pending(http, "plant-pg")
            val quarantinedBefore = quarantined(http, "plant-pg")
            val bad = http.postRaw(QUARANTINE_IMPORT_PATH, """{"records":[{"provider":"plant-pg","tagPath":"[default]Plant/Flow","timestamp":"2026-06-01T00:00:00Z","value":1,"quality":"Good"},{"provider":"NOT A NAME"}]}""".toByteArray(), "application/json")
            assertProblem(bad, 422)
            assertTrue(bad.body.contains("nothing was imported"), bad.body)
            assertTrue(bad.body.contains("record 2"), bad.body)
            assertEquals(pendingBefore, pending(http, "plant-pg"))
            assertEquals(quarantinedBefore, quarantined(http, "plant-pg"))
            assertProblem(http.postRaw(QUARANTINE_IMPORT_PATH, "{".toByteArray(), "application/json"), 400)
            assertProblem(http.postRaw(QUARANTINE_IMPORT_PATH, ByteArray(0), "application/json"), 400)
            val oversized = http.postRaw(QUARANTINE_IMPORT_PATH, ByteArray(StoreForwardFiles.MAX_BYTES + 1) { 'x'.code.toByte() }, "application/json")
            assertProblem(oversized, 413)
            assertTrue(oversized.body.contains("nothing was imported"), oversized.body)
            assertEquals(0, http.get("/api/v1/diagnostics/history/quarantine").list("records").size)

            val tooMany = assertThrows(ProblemException::class.java) {
                StoreForwardFiles.parseQuarantine(
                    """{"records":[{"provider":"plant-pg","tagPath":"[default]Plant/Flow","timestamp":"2026-06-01T00:00:00Z","value":1,"quality":"Good"},{"provider":"plant-pg","tagPath":"[default]Plant/Flow","timestamp":"2026-06-01T00:00:01Z","value":2,"quality":"Good"}]}""".toByteArray(),
                    "application/json",
                    maxRecords = 1,
                )
            }
            assertEquals(413, tooMany.status)
            assertTrue(tooMany.message!!.contains("nothing was imported"), tooMany.message)

            // Still rejected: the imported row is retried and quarantined again, and the good rows stay stored.
            val imported = http.postRaw(QUARANTINE_IMPORT_PATH, exported.raw, "application/json")
            assertEquals(200, imported.status, imported.body)
            assertEquals(1, (imported.json["retried"] as Number).toInt())
            assertEquals(1, gw.auditCount(org.oip.security.AuditActions.QUARANTINE_IMPORTED, actor = "admin"))
            val again = http.get("/api/v1/diagnostics/history/quarantine?provider=plant-pg").list("records").single()
            assertEquals(500.0, (again["value"] as Number).toDouble())
            assertEquals("[default]Plant/Flow", again["tagPath"])
            assertEquals(listOf(1.0), remote.rows.map { it.value })

            assertEquals(1, (http.post("/api/v1/diagnostics/history/quarantine/delete", """{"provider":"plant-pg"}""").json["deleted"] as Number).toInt())
            val fromCsv = http.postRaw(QUARANTINE_IMPORT_PATH, csv.raw, "text/csv")
            assertEquals(200, fromCsv.status, fromCsv.body)
            assertEquals(1, (fromCsv.json["retried"] as Number).toInt())
            assertEquals(500.0, (http.get("/api/v1/diagnostics/history/quarantine").list("records").single()["value"] as Number).toDouble())

            // The provider accepts the value, so the next import is stored instead of quarantined.
            remote.rejectAbove = 1000.0
            assertEquals(1, (http.post("/api/v1/diagnostics/history/quarantine/delete", """{"provider":"plant-pg"}""").json["deleted"] as Number).toInt())
            val fixed = http.postRaw(QUARANTINE_IMPORT_PATH, exported.raw, "application/json")
            assertEquals(1, (fixed.json["retried"] as Number).toInt())
            assertEquals(0, http.get("/api/v1/diagnostics/history/quarantine").list("records").size)
            assertEquals(listOf(1.0, 500.0), remote.rows.map { it.value })

            val reader = gw.security.apiKeys.create(
                "import-read", setOf(Permissions.GATEWAY_READ, Permissions.TAGS_READ), secureConnectionRequired = false, actor = "test",
            )
            val writer = gw.security.apiKeys.create(
                "import-write", setOf(Permissions.GATEWAY_WRITE), secureConnectionRequired = false, actor = "test",
            )
            fun client(token: String) = Http(gw.port()).also { it.authorizeBearer(token) }
            val denied = client(reader.token).postRaw(QUARANTINE_IMPORT_PATH, exported.raw, "application/json")
            assertProblem(denied, 403)
            assertTrue(denied.body.contains("gateway.write"), denied.body)
            assertEquals(listOf(1.0, 500.0), remote.rows.map { it.value }, "a refused import writes nothing")
            val allowed = client(writer.token).postRaw(QUARANTINE_IMPORT_PATH, """{"records":[]}""".toByteArray(), "application/json")
            assertEquals(200, allowed.status, allowed.body)
            assertEquals(0, (allowed.json["retried"] as Number).toInt())

            assertEquals("gateway.write", GatewayAuth.explicitRule("POST", QUARANTINE_IMPORT_PATH))
            assertTrue(OpenApi.document.toString().contains(QUARANTINE_IMPORT_PATH))
        }
    }

    /**
     * Archive writes a gateway-side file and then removes the pending rows. The list, download, load-by-name,
     * and upload of that download put the same samples back. A bad upload does not change the cache.
     */
    @Test
    fun diskCacheArchiveAndLoadRoundTrip() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("flow", "Plant/Flow", 1.0)
            gw.start()
            val remote = Remote("plant-pg")
            remote.down = true
            gw.historyRouter.register(remote)
            val http = Http(gw.port())
            gw.loginAdmin(http)
            assertEquals(201, http.put("/api/v1/config/historian/gateway/HistorySettings/flow",
                """{"apiVersion":"oip/v1","kind":"HistorySettings","metadata":{"name":"flow","module":"historian","version":0},"spec":{"tagPath":"Plant/Flow","storageProvider":"plant-pg"}}""").status)
            write(gw, "[default]Plant/Flow", 2.0)
            write(gw, "[default]Plant/Flow", 3.0)
            assertProblem(http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}"), 503)
            val before = pending(http, "plant-pg")
            assertTrue(before >= 2, "pending was $before")

            val broken = http.postRaw(DISK_CACHE_LOAD_PATH, """{"kind":"nope","version":1,"records":[]}""".toByteArray(), "application/json")
            assertProblem(broken, 422)
            assertTrue(broken.body.contains("nothing was loaded"), broken.body)
            assertEquals(before, pending(http, "plant-pg"))
            val oversized = http.postRaw(DISK_CACHE_LOAD_PATH, ByteArray(StoreForwardFiles.MAX_BYTES + 1), "application/json")
            assertProblem(oversized, 413)
            assertTrue(oversized.body.contains("nothing was loaded"), oversized.body)
            assertEquals(before, pending(http, "plant-pg"))

            val unknown = http.postRaw(
                DISK_CACHE_LOAD_PATH,
                """
                {"kind":"oip.store-forward.disk-cache","version":1,"provider":"nope","records":1,"created":"2026-06-01T00:00:00Z"}
                {"provider":"nope","sourceId":"s","seq":1,"tagPath":"[default]Plant/Flow","timestamp":"2026-06-01T00:00:01Z","value":9.0,"quality":"Good"}
                """.trim().toByteArray(),
                "application/x-ndjson",
            )
            assertProblem(unknown, 422)
            assertTrue(unknown.body.contains("does not exist"), unknown.body)
            assertEquals(before, pending(http, "plant-pg"))

            val archived = http.postRaw("$DISK_CACHE_ARCHIVE_PATH?provider=plant-pg", ByteArray(0), "application/json")
            assertEquals(200, archived.status, archived.body)
            assertEquals(0, pending(http, "plant-pg"))
            val name = archived.json["name"] as String
            assertEquals("plant-pg", archived.json["provider"])
            assertEquals(before, (archived.json["records"] as Number).toInt())
            assertEquals(1, gw.auditCount(org.oip.security.AuditActions.DISK_CACHE_ARCHIVED, actor = "admin"))
            val listed = http.get(DISK_CACHE_ARCHIVES_PATH)
            assertEquals(200, listed.status, listed.body)
            assertEquals(name, listed.list("archives").single()["name"])

            val download = http.get("$DISK_CACHE_ARCHIVES_PATH/$name")
            assertEquals(200, download.status, download.body)
            assertTrue(download.header("Content-Type")!!.startsWith("application/x-ndjson"), download.header("Content-Type"))
            assertTrue(download.body.contains("\"seq\""), download.body)
            assertTrue(download.body.contains("[default]Plant/Flow"), download.body)

            val loaded = http.post(DISK_CACHE_LOAD_PATH, """{"name":"$name"}""")
            assertEquals(200, loaded.status, loaded.body)
            assertEquals(before, (loaded.json["loaded"] as Number).toInt())
            assertEquals(before, pending(http, "plant-pg"))
            assertEquals(1, gw.auditCount(org.oip.security.AuditActions.DISK_CACHE_LOADED, actor = "admin"))

            remote.down = false
            clock.advance(Duration.ofSeconds(10))
            val data = http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}")
            assertEquals(200, data.status, data.body)
            assertEquals(listOf(1.0, 2.0, 3.0), data.list("samples").map { (it["value"] as Number).toDouble() })
            assertEquals(0, pending(http, "plant-pg"))
            assertEquals(listOf(1.0, 2.0, 3.0), remote.rows.map { it.value })

            val reader = gw.security.apiKeys.create(
                "cache-read", setOf(Permissions.GATEWAY_READ), secureConnectionRequired = false, actor = "test",
            )
            fun client(token: String) = Http(gw.port()).also { it.authorizeBearer(token) }
            val deniedArchive = client(reader.token).postRaw("$DISK_CACHE_ARCHIVE_PATH?provider=plant-pg", ByteArray(0), "application/json")
            assertProblem(deniedArchive, 403)
            assertTrue(deniedArchive.body.contains("gateway.write"), deniedArchive.body)
            val deniedLoad = client(reader.token).post(DISK_CACHE_LOAD_PATH, """{"name":"$name"}""")
            assertProblem(deniedLoad, 403)
            assertTrue(deniedLoad.body.contains("gateway.write"), deniedLoad.body)
            val uploaded = http.postRaw(DISK_CACHE_LOAD_PATH, download.raw, "application/x-ndjson")
            assertEquals(200, uploaded.status, uploaded.body)
            val removed = http.delete("$DISK_CACHE_ARCHIVES_PATH/$name")
            assertEquals(204, removed.status, removed.body)
            assertEquals(0, http.get(DISK_CACHE_ARCHIVES_PATH).list("archives").size)
            assertEquals(1, gw.auditCount(org.oip.security.AuditActions.DISK_CACHE_ARCHIVE_DELETED, actor = "admin"))

            assertEquals("gateway.write", GatewayAuth.explicitRule("POST", DISK_CACHE_ARCHIVE_PATH))
            assertEquals("gateway.write", GatewayAuth.explicitRule("POST", DISK_CACHE_LOAD_PATH))
            assertEquals("gateway.read", GatewayAuth.explicitRule("GET", DISK_CACHE_ARCHIVES_PATH))
            assertEquals("gateway.write", GatewayAuth.explicitRule("DELETE", "$DISK_CACHE_ARCHIVES_PATH/plant-pg_20260601T000000.000Z.json"))
            assertTrue(OpenApi.document.toString().contains(DISK_CACHE_ARCHIVE_PATH))
            assertTrue(OpenApi.document.toString().contains("storeRate"))
        }
    }

    /**
     * Store rate counts datapoints entering the disk cache and drops back to zero once they age out of the window.
     * (doc source: FEATURES HIS-025)
     */
    @Test
    fun storeRateFollowsIngestOnTheProviderStatus() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("flow", "Plant/Flow", 1.0)
            gw.start()
            val remote = Remote("plant-pg")
            remote.down = true
            gw.historyRouter.register(remote)
            val http = Http(gw.port())
            gw.loginAdmin(http)
            assertEquals(201, http.put("/api/v1/config/historian/gateway/HistorySettings/flow",
                """{"apiVersion":"oip/v1","kind":"HistorySettings","metadata":{"name":"flow","module":"historian","version":0},"spec":{"tagPath":"Plant/Flow","storageProvider":"plant-pg"}}""").status)
            val initial = provider(http, "plant-pg")
            val storedBefore = (initial["stored"] as Number).toLong()
            write(gw, "[default]Plant/Flow", 2.0)
            write(gw, "[default]Plant/Flow", 3.0)
            assertProblem(http.get("/api/v1/history/data?tagPath=${enc("Plant/Flow")}&from=0&to=${Long.MAX_VALUE}"), 503)
            val live = provider(http, "plant-pg")
            val stored = (live["stored"] as Number).toLong()
            assertTrue(stored > storedBefore, "stored $stored before $storedBefore")
            assertTrue((live["storeRate"] as Number).toDouble() > 0.0, live.toString())
            clock.advance(Duration.ofMillis(org.oip.historian.HistoryRouter.STORE_RATE_WINDOW_MILLIS + 1))
            val later = provider(http, "plant-pg")
            assertEquals(0.0, (later["storeRate"] as Number).toDouble(), 1e-9)
            assertEquals(stored, (later["stored"] as Number).toLong())
            val local = provider(http, "local")
            assertEquals(0.0, (local["storeRate"] as Number).toDouble(), 1e-9)
        }
    }

    private fun provider(http: Http, name: String) =
        http.get("/api/v1/diagnostics/history/providers").list("providers").single { it["name"] == name }

    private fun pending(http: Http, name: String) = (provider(http, name)["pending"] as Number).toInt()

    private fun quarantined(http: Http, name: String) = (provider(http, name)["quarantined"] as Number).toInt()
}
