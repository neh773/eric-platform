package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.AuditProfile
import org.oip.security.AuditQuery
import org.oip.security.DefaultRoles
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * GAT-035: the gateway-wide settings resource (system name, script message encoding, client launch defaults) is read
 * and written through `/api/v1/gateway/settings` and the config API, validated, audited, and applied without a restart.
 * SEC-001 (gateway level): the running gateway applies the audit profile's retention against its clock.
 * Doc source: FEATURES GAT-035.
 * Doc source: FEATURES SEC-001.
 */
class GatewaySettingsApiTest {
    @TempDir lateinit var tmp: Path
    private var gw: Gateway? = null

    @AfterEach fun tearDown() { gw?.close() }

    private fun start(clock: MutableClock = MutableClock(), webDist: Path? = null): Gateway =
        Gateway(tmp.resolve("gw"), port = 0, clock = clock, webDist = webDist).start().also { gw = it }

    private fun auditTotal(g: Gateway) = g.security.audit.query(AuditQuery(limit = 1)).total

    @Test fun defaultsThenUpdateIsValidatedAuditedAndVisibleThroughTheConfigApi() {
        val g = start()
        val http = Http(g.port())
        g.loginAdmin(http)

        val initial = http.get(PlatformSettingsService.PATH)
        assertEquals(200, initial.status, initial.body)
        assertEquals("", initial.json["systemName"])
        assertTrue((initial.json["effectiveSystemName"] as String).isNotBlank())
        assertEquals("json", initial.json["scriptMessageEncoding"])
        assertEquals(listOf("json"), initial.json["scriptMessageEncodings"])
        assertEquals(mapOf("defaultProject" to ""), initial.json["launch"])

        val before = auditTotal(g)
        val put = http.put(PlatformSettingsService.PATH, """{"systemName":"Plant North","scriptMessageEncoding":"json","launch":{"defaultProject":"line-1"}}""")
        assertEquals(200, put.status, put.body)
        assertEquals("Plant North", put.json["systemName"])
        assertEquals("Plant North", put.json["effectiveSystemName"])
        assertEquals(mapOf("defaultProject" to "line-1"), put.json["launch"])
        assertEquals(before + 1, auditTotal(g), "one config-change audit record")
        val row = g.security.audit.query(AuditQuery(limit = 1)).records.single()
        assertEquals("admin", row.actor)
        assertTrue(row.target.contains("GatewaySettings/gateway"), row.target)

        // The same resource through the config API.
        val viaConfig = http.get("/api/v1/config/org.oip.gateway/gateway/GatewaySettings/gateway")
        assertEquals(200, viaConfig.status, viaConfig.body)
        @Suppress("UNCHECKED_CAST") val spec = viaConfig.json["spec"] as Map<String, Any?>
        assertEquals("Plant North", spec["systemName"])

        // A config-API patch is the same resource and shows up on the settings route.
        val patched = http.send("PATCH", "/api/v1/config/org.oip.gateway/gateway/GatewaySettings/gateway", """{"systemName":"Plant South"}""".toByteArray())
        assertEquals(200, patched.status, patched.body)
        assertEquals("Plant South", http.get(PlatformSettingsService.PATH).json["systemName"])

        // Blank goes back to the host name.
        val blank = http.put(PlatformSettingsService.PATH, """{"systemName":""}""")
        assertEquals(200, blank.status, blank.body)
        assertEquals(PlatformSettingsService.defaultHostName(), blank.json["effectiveSystemName"])
        assertEquals(mapOf("defaultProject" to ""), blank.json["launch"], "a missing field takes its default")
    }

    @Test fun invalidValuesAreRefusedAndChangeNothing() {
        val g = start()
        val http = Http(g.port())
        g.loginAdmin(http)
        assertEquals(200, http.put(PlatformSettingsService.PATH, """{"systemName":"keep"}""").status)
        val before = auditTotal(g)
        for (body in listOf(
            """{"systemName":"a|b"}""",
            """{"systemName":"${"x".repeat(64)}"}""",
            """{"scriptMessageEncoding":"java-serialization"}""",
            """{"launch":{"defaultProject":"Not A Project"}}""",
            """{"launch":{"memoryMb":512}}""",
            """{"homepage":"/x"}""",
            """{"systemName":5}""",
        )) {
            val r = http.put(PlatformSettingsService.PATH, body)
            assertEquals(422, r.status, "$body -> ${r.body}")
        }
        assertEquals(400, http.put(PlatformSettingsService.PATH, "{not json").status)
        assertEquals("keep", http.get(PlatformSettingsService.PATH).json["systemName"])
        assertEquals(before, auditTotal(g), "a refused write is not a config change")
        // The config API applies the same schema.
        val bad = http.send("PATCH", "/api/v1/config/org.oip.gateway/gateway/GatewaySettings/gateway", """{"scriptMessageEncoding":"binary"}""".toByteArray())
        assertEquals(422, bad.status, bad.body)
    }

    @Test fun readNeedsGatewayReadAndWriteNeedsGatewayWrite() {
        val g = start()
        g.createUser("view", setOf(DefaultRoles.VIEWER))
        g.createUser("op", setOf(DefaultRoles.OPERATOR))
        assertEquals(401, Http(g.port()).get(PlatformSettingsService.PATH).status)
        // The viewer role holds no gateway.read; the operator reads gateway configuration but does not write it.
        val viewer = Http(g.port())
        assertEquals(200, viewer.login("view", TEST_PASSWORD).status)
        assertEquals(403, viewer.get(PlatformSettingsService.PATH).status)
        assertEquals(403, viewer.put(PlatformSettingsService.PATH, """{"systemName":"x"}""").status)
        val op = Http(g.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(200, op.get(PlatformSettingsService.PATH).status)
        assertEquals(403, op.put(PlatformSettingsService.PATH, """{"systemName":"x"}""").status)
        assertEquals("", op.get(PlatformSettingsService.PATH).json["systemName"], "a refused write changes nothing")
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", PlatformSettingsService.PATH))
        assertEquals("gateway.write", GatewayAuth.explicitRule("PUT", PlatformSettingsService.PATH))
        assertTrue(OpenApi.document.toString().contains(PlatformSettingsService.PATH))
    }

    @Test fun systemNameReachesTheSystemTagWithoutARestart() {
        val g = start()
        val http = Http(g.port())
        g.loginAdmin(http)
        assertEquals(200, http.put(PlatformSettingsService.PATH, """{"systemName":"Plant North"}""").status)
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (g.tags.read("[system]Gateway/SystemName").value != "Plant North" && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals("Plant North", g.tags.read("[system]Gateway/SystemName").value)
    }

    @Test fun hmiLaunchWithoutAProjectOpensTheDefaultProject() {
        val dist = tmp.resolve("dist").also { Files.createDirectories(it) }
        Files.writeString(dist.resolve("index.html"), "<!doctype html><title>hmi</title>")
        val g = start(webDist = dist)
        val http = Http(g.port())
        g.loginAdmin(http)
        // No default: the shell, as before.
        assertEquals(200, http.get("/hmi").status)
        assertEquals(200, http.put(PlatformSettingsService.PATH, """{"launch":{"defaultProject":"line-1"}}""").status)
        val launched = http.get("/hmi?theme=dark")
        assertEquals(302, launched.status)
        assertEquals("/hmi/line-1/?theme=dark", launched.header("Location"))
        assertEquals("/hmi/line-1/", http.get("/hmi/").header("Location"))
        // A named project and the studio are unaffected.
        assertEquals(200, http.get("/hmi/other").status)
        assertEquals(200, http.get("/studio").status)
    }

    @Test fun theRunningGatewayEnforcesAuditRetentionOnItsClock() {
        val clock = MutableClock()
        val g = start(clock)
        // The startup pass runs on the scheduler thread; let it finish before the clock moves.
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        fun runs() = (g.diagnostics.tasks.snapshot().firstOrNull { it["name"] == "audit-retention" }?.get("runs") as? Number)?.toLong() ?: 0L
        while (runs() < 1 && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(runs() >= 1, "the startup retention pass ran")
        g.security.auditProfile.update(AuditProfile("Internal audit profile", 30, true), actor = "test")
        val old = g.security.audit.query(AuditQuery(limit = 1000)).total
        assertTrue(old > 1)
        assertEquals(0, g.enforceAuditRetention(), "nothing has expired yet")

        clock.advance(Duration.ofDays(31))
        g.security.audit.append(org.oip.security.AuditDraft(clock.millis(), "alice", "login", org.oip.security.AuditStatus.SUCCESS))
        val removed = g.enforceAuditRetention()
        assertEquals(old, removed, "every row older than 30 days")
        val kept = g.security.audit.query(AuditQuery(limit = 1000)).records
        assertEquals(listOf(AuditActions.AUDIT_PRUNED, "login"), kept.map { it.action })
        assertTrue(g.security.audit.verify().ok)
        assertNotNull(g.security.audit.base())
        assertFalse(kept.any { clock.millis() - it.timestampMillis > Duration.ofDays(30).toMillis() })
    }
}
