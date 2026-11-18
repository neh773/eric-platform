package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import java.net.URLEncoder
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * M3.1 first slice over a live [Gateway]: history settings as a config resource, the raw-query REST
 * route (auth, validation, time range, limit), and history plus collection surviving a restart.
 * Docs: -
 *
 * Time comes from a [MutableClock]; queries flush the collector's queue, so there are no sleeps.
 * OPC UA is off so the tests do not need port 4840.
 */
class HistoryApiTest {
    @TempDir lateinit var tmp: Path

    private val clock = MutableClock(Instant.parse("2026-06-01T00:00:00Z"))
    private val t0 = clock.millis()

    private fun gateway() = Gateway(tmp, port = 0, clock = clock)

    private fun prepare() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
    }

    private fun Gateway.memoryTag(name: String, path: String, value: Any?, dataType: String = "Double") {
        store.put(Scope.GATEWAY, Resource("Tag", Metadata(name, "tags"),
            mapOf("path" to path, "type" to "memory", "dataType" to dataType, "value" to value)))
    }

    private fun settingsDoc(name: String, spec: String, version: Long = 0) =
        """{"apiVersion":"oip/v1","kind":"HistorySettings","metadata":{"name":"$name","module":"historian","version":$version},"spec":$spec}"""

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun Http.history(tagPath: String, from: Any, to: Any, limit: Int? = null) =
        get("/api/v1/history/data?tagPath=${enc(tagPath)}&from=${enc(from.toString())}&to=${enc(to.toString())}" +
            (limit?.let { "&limit=$it" } ?: ""))

    @Suppress("UNCHECKED_CAST")
    private fun Http.Res.samples() = json["samples"] as List<Map<String, Any?>>

    private fun assertProblem(r: Http.Res, status: Int) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
    }

    private fun write(gw: Gateway, path: String, value: Any?) {
        clock.advance(Duration.ofSeconds(1))
        gw.tags.write(path, value)
    }

    @Test
    fun settingsResourceDrivesCollectionAndRawQueryReturnsTheRange() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("plant-speed", "Plant/Speed", 1.0)
            gw.start()
            val http = Http(gw.port())
            gw.loginAdmin(http)
            assertEquals(201, http.put("/api/v1/config/historian/gateway/HistorySettings/speed",
                settingsDoc("speed", """{"tagPath":"Plant/Speed","sampleMode":"onChange"}""")).status)

            for (v in listOf(2.0, 3.0, 4.0)) write(gw, "[default]Plant/Speed", v)

            val all = http.history("[default]Plant/Speed", 0, Long.MAX_VALUE)
            assertEquals(200, all.status, all.body)
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), all.samples().map { (it["value"] as Number).toDouble() })
            assertEquals("[default]Plant/Speed", all.json["tagPath"])
            assertEquals(false, all.json["truncated"])
            assertTrue(all.samples().all { it["quality"] == "Good" })

            // Inclusive range by epoch millis, and the same range as ISO-8601 instants.
            val mid = http.history("Plant/Speed", t0 + 1000, t0 + 2000)
            assertEquals(listOf(2.0, 3.0), mid.samples().map { (it["value"] as Number).toDouble() })
            assertEquals(listOf("2026-06-01T00:00:01Z", "2026-06-01T00:00:02Z"), mid.samples().map { it["timestamp"] })
            val iso = http.history("Plant/Speed", "2026-06-01T00:00:01Z", "2026-06-01T00:00:02Z")
            assertEquals(mid.samples(), iso.samples())

            val limited = http.history("Plant/Speed", 0, Long.MAX_VALUE, limit = 2)
            assertEquals(listOf(1.0, 2.0), limited.samples().map { (it["value"] as Number).toDouble() })
            assertEquals(true, limited.json["truncated"])

            assertTrue(gw.dataDir.resolve("db/history.sqlite").exists())
        }
    }

    @Test
    fun rawQueryRequiresAuthenticationAndValidatesItsParameters() {
        prepare()
        gateway().use { gw ->
            gw.start()
            val http = Http(gw.port())
            assertProblem(http.history("Plant/Speed", 0, 10), 401)
            gw.loginAdmin(http)
            assertEquals(200, http.history("Plant/Speed", 0, 10).status)
            assertProblem(http.get("/api/v1/history/data?from=0&to=10"), 400)
            assertProblem(http.get("/api/v1/history/data?tagPath=A&to=10"), 400)
            assertProblem(http.get("/api/v1/history/data?tagPath=A&from=0"), 400)
            assertProblem(http.history("A", "yesterday", 10), 400)
            assertProblem(http.get("/api/v1/history/data?tagPath=A&from=0&to=10&limit=x"), 400)
            assertProblem(http.history("A", 11, 10), 422)
            assertProblem(http.history("A", 0, 10, limit = 0), 422)
            assertProblem(http.history("A", 0, 10, limit = 100_001), 422)
            assertProblem(http.history("[.]A", 0, 10), 422)
        }
    }

    @Test
    fun historyAndCollectionSurviveAGatewayRestart() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("plant-level", "Plant/Level", 10.0)
            gw.store.put(Scope.GATEWAY, Resource("HistorySettings", Metadata("level", "historian"),
                mapOf("tagPath" to "[default]Plant/Level", "deadband" to mapOf("mode" to "absolute", "value" to 1.0))))
            gw.start()
            write(gw, "[default]Plant/Level", 20.0)
            write(gw, "[default]Plant/Level", 20.5) // inside the deadband
        }
        gateway().use { gw ->
            gw.start()
            // The memory tag comes back at its last value, 20.5 (TAG-016), not its configured 10.0.
            assertEquals(20.5, (gw.tags.read("[default]Plant/Level").value as Number).toDouble())
            // No code configures collection here: it comes back from the HistorySettings resource.
            write(gw, "[default]Plant/Level", 30.0)
            val http = Http(gw.port())
            gw.loginAdmin(http)
            val r = http.history("[default]Plant/Level", 0, Long.MAX_VALUE)
            assertEquals(200, r.status)
            // 20.5 is inside the deadband of the last stored 20.0, before and after the restart, so it is never a
            // row. The deadband state survives the restart: 30.0 is measured against the stored 20.0.
            assertEquals(listOf(10.0, 20.0, 30.0), r.samples().map { (it["value"] as Number).toDouble() })
        }
    }

    @Test
    fun openApiDocumentsTheHistoryRoute() {
        prepare()
        gateway().use { gw ->
            gw.start()
            val doc = Http(gw.port()).get("/api/docs").json
            val route = (doc["paths"] as Map<*, *>)["/api/v1/history/data"] as Map<*, *>
            val get = route["get"] as Map<*, *>
            assertTrue((get["security"] as List<*>).isNotEmpty())
            assertEquals(setOf("tagPath", "from", "to", "limit", "provider"), (get["parameters"] as List<*>).map { (it as Map<*, *>)["name"] }.toSet())
            val agg = ((doc["paths"] as Map<*, *>)["/api/v1/history/aggregate"] as Map<*, *>)["get"] as Map<*, *>
            assertEquals(setOf("tagPath", "from", "to", "provider", "interval", "aggregate", "interpolation"), (agg["parameters"] as List<*>).map { (it as Map<*, *>)["name"] }.toSet())
            for (path in listOf("/api/v1/diagnostics/history/providers", "/api/v1/diagnostics/history/quarantine",
                "/api/v1/diagnostics/history/quarantine/retry", "/api/v1/diagnostics/history/quarantine/delete")) {
                assertTrue((doc["paths"] as Map<*, *>).containsKey(path), path)
            }
        }
    }

    /** S-1: history answers per tag only to a caller the tag's read permissions admit (TAG-013). */
    @Test
    fun historyOfATagTheCallerMayNotReadIsRefusedPerTag() {
        prepare()
        gateway().use { gw ->
            gw.memoryTag("plant-speed", "Plant/Speed", 1.0)
            gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("plant-secret", "tags"), mapOf(
                "path" to "Plant/Secret", "type" to "memory", "dataType" to "Double", "value" to 5.0,
                "readPermissions" to listOf(DefaultRoles.ADMINISTRATOR))))
            for ((name, path) in listOf("speed" to "[default]Plant/Speed", "secret" to "[default]Plant/Secret")) {
                gw.store.put(Scope.GATEWAY, Resource("HistorySettings", Metadata(name, "historian"),
                    mapOf("tagPath" to path, "sampleMode" to "onChange")))
            }
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.start()
            write(gw, "[default]Plant/Speed", 2.0)
            write(gw, "[default]Plant/Secret", 6.0)

            val admin = Http(gw.port()).also { gw.loginAdmin(it) }
            val operator = Http(gw.port()).also { assertEquals(200, it.login("ada", TEST_PASSWORD).status) }
            fun aggregate(http: Http, tag: String) = http.get("/api/v1/history/aggregate?tagPath=${enc(tag)}&from=0&to=${t0 + 10_000}" +
                "&interval=${t0 + 10_001}&aggregate=Maximum")

            // The administrator role is listed on the tag: raw and aggregated history come back.
            val shown = admin.history("[default]Plant/Secret", 0, Long.MAX_VALUE)
            assertEquals(200, shown.status, shown.body)
            assertEquals(listOf(5.0, 6.0), shown.samples().map { (it["value"] as Number).toDouble() })
            assertFalse(shown.json.containsKey("quality"), shown.body)
            val shownAgg = aggregate(admin, "[default]Plant/Secret")
            assertEquals(200, shownAgg.status, shownAgg.body)
            assertEquals(6.0, ((shownAgg.json["windows"] as List<*>).single() as Map<*, *>)["value"].let { (it as Number).toDouble() })

            // An operator is refused that tag with Bad_AccessDenied and no rows, as a tag read reports it ...
            val refused = operator.history("[default]Plant/Secret", 0, Long.MAX_VALUE)
            assertEquals(200, refused.status, refused.body)
            assertEquals("Bad_AccessDenied", refused.json["quality"])
            assertEquals(emptyList<Any>(), refused.samples())
            assertEquals("[default]Plant/Secret", refused.json["tagPath"])
            val refusedAgg = aggregate(operator, "Plant/Secret")
            assertEquals(200, refusedAgg.status, refusedAgg.body)
            assertEquals("Bad_AccessDenied", refusedAgg.json["quality"])
            assertEquals(emptyList<Any>(), refusedAgg.json["windows"])

            // ... while a tag without read permissions still answers in full.
            val open = operator.history("[default]Plant/Speed", 0, Long.MAX_VALUE)
            assertEquals(listOf(1.0, 2.0), open.samples().map { (it["value"] as Number).toDouble() })
            assertFalse(open.json.containsKey("quality"), open.body)

            // Deleting the restricted tag does not open its history: with no tag left to check, only a caller that
            // may edit tag configuration (the administrator, gateway.write) still reads it.
            gw.store.delete(org.oip.config.ResourceId("tags", Scope.GATEWAY, "Tag", "plant-secret"))
            assertTrue(!gw.tags.isConfigured("[default]Plant/Secret"))
            val gone = operator.history("[default]Plant/Secret", 0, Long.MAX_VALUE)
            assertEquals(200, gone.status, gone.body)
            assertEquals("Bad_AccessDenied", gone.json["quality"])
            assertEquals(emptyList<Any>(), gone.samples())
            assertEquals("Bad_AccessDenied", aggregate(operator, "[default]Plant/Secret").json["quality"])
            val kept = admin.history("[default]Plant/Secret", 0, Long.MAX_VALUE)
            // The removal itself may be recorded as a last row with no value; the stored values come first.
            assertEquals(listOf(5.0, 6.0), kept.samples().take(2).map { (it["value"] as Number).toDouble() })
            assertFalse(kept.json.containsKey("quality"), kept.body)
        }
    }
}
