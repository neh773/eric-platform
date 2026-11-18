package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.AuditStatus
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.nio.file.Path

/**
 * Session cookies, CSRF, API tokens, and role checks on `/api/v1`.
 * Cookie flags: https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
 * API tokens are bearer credentials (SPEC), checked with the security library.
 */
class AuthHttpTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        http = Http(gw.port())
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun healthIsPublicAndOtherRoutesRequireAuth() {
        val anon = Http(gw.port())
        assertEquals(200, anon.get("/api/v1/health").status)
        val denied = anon.get("/api/v1/config")
        assertProblem(denied, 401, "unauthorized")
        assertEquals("Bearer realm=\"oip\"", denied.header("WWW-Authenticate"))
    }

    @Test fun loginSetsHostOnlyCookieAndAuditsSuccessAndFailure() {
        val bad = http.login("admin", "Wrong-pass-1")
        assertProblem(bad, 401, "unauthorized")
        assertNull(bad.header("Set-Cookie"))
        assertEquals(1, gw.auditCount(AuditActions.LOGIN_ATTEMPT, "admin", AuditStatus.FAILURE))
        assertFalse(bad.body.contains("Wrong-pass-1"))

        val ok = http.login("admin", TEST_PASSWORD)
        assertEquals(200, ok.status, ok.body)
        val cookie = ok.header("Set-Cookie")!!
        val attrs = cookie.substringAfter(';')
        assertTrue(cookie.startsWith("${HttpAuth.COOKIE}="), cookie)
        assertTrue(attrs.contains("Path=/"), cookie)
        assertTrue(attrs.contains("HttpOnly"), cookie)
        assertFalse(Regex("(?i)(^|;)\\s*Secure\\s*(;|$)").containsMatchIn(";$attrs"), cookie)
        assertTrue(gw.insecureCookieWarning!!.contains("plain HTTP"))
        assertTrue(attrs.contains("SameSite=Strict"), cookie)
        assertFalse(attrs.contains("Domain="), cookie)
        assertFalse(ok.body.contains(http.sessionCookie()!!))
        assertEquals(listOf("administrator"), ok.json["roles"])
        assertEquals(ok.json["csrfToken"], http.csrf)
        val maxAge = Regex("Max-Age=(\\d+)").find(cookie)!!.groupValues[1].toLong()
        assertTrue(maxAge in 43_000..43_200, "max-age=$maxAge")
        assertEquals(1, gw.auditCount(AuditActions.LOGIN, "admin", AuditStatus.SUCCESS))

        val session = http.get("/api/v1/auth/session")
        assertEquals(200, session.status, session.body)
        assertEquals("user", session.json["kind"])
        assertEquals("admin", session.json["principal"])
        assertEquals(http.csrf, session.json["csrfToken"])
        assertTrue((session.json["permissions"] as List<*>).contains(Permissions.GATEWAY_WRITE))

        val page = http.get("/api/v1/audit?action=login&actor=admin").json
        assertEquals(1, (page["total"] as Number).toInt())
        val row = (page["items"] as List<*>).first() as Map<*, *>
        assertEquals("SUCCESS", row["status"])
        assertFalse(row["detail"].toString().contains(TEST_PASSWORD))
    }

    /** SEC-002. Doc source: FEATURES SEC-002. */
    @Test fun auditQueryFiltersByTimeRangeActorActionAndTarget() {
        fun add(at: Long, actor: String, action: String, target: String) = gw.security.audit.append(
            org.oip.security.AuditDraft(timestampMillis = at, actor = actor, action = action, status = AuditStatus.SUCCESS, target = target)
        )
        val day = 86_400_000L
        val t0 = 1_800_000_000_000L
        add(t0, "sec002-ann", "sec002 opened", "sec002/door-1")
        add(t0 + day, "sec002-ann", "sec002 closed", "sec002/door-2")
        add(t0 + 2 * day, "sec002-bob", "sec002 opened", "sec002/door-2")
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        fun total(query: String) = (http.get("/api/v1/audit?$query").json["total"] as Number).toInt()
        assertEquals(3, total("target=sec002%2F"))
        assertEquals(2, total("actor=sec002-ann"))
        assertEquals(2, total("action=sec002%20opened"))
        assertEquals(2, total("target=door-2"))
        assertEquals(1, total("from=${t0 + day}&to=${t0 + day}&target=sec002"))
        assertEquals(2, total("from=${t0 + day}&target=sec002"))
        assertEquals(2, total("to=${t0 + day}&target=sec002"))
        assertEquals(1, total("actor=sec002-ann&action=sec002%20closed&from=${t0}&to=${t0 + 2 * day}"))
        assertEquals(0, total("actor=sec002-bob&to=${t0 + day}"))
        // The newest rows come first, and bad parameters are refused.
        val page = http.get("/api/v1/audit?target=sec002").json["items"] as List<*>
        assertEquals("sec002-bob", (page.first() as Map<*, *>)["actor"])
        assertEquals(400, http.get("/api/v1/audit?from=yesterday").status)
        assertEquals(400, http.get("/api/v1/audit?status=MAYBE").status)
        assertEquals(401, Http(gw.port()).get("/api/v1/audit?actor=sec002-ann").status)
    }

    @Test fun logoutRequiresCsrfAndAuditsOneEntry() {
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        http.sendCsrf = false
        val rejected = http.post("/api/v1/auth/logout", "{}")
        assertProblem(rejected, 403, "csrf")
        assertEquals(200, http.get("/api/v1/auth/session").status)
        http.sendCsrf = true
        val done = http.post("/api/v1/auth/logout", "{}")
        assertEquals(204, done.status, done.body)
        assertTrue(done.header("Set-Cookie")!!.contains("Max-Age=0"))
        assertEquals(401, http.get("/api/v1/auth/session").status)
        assertEquals(1, gw.auditCount(AuditActions.LOGOUT, "admin", AuditStatus.SUCCESS))
    }

    @Test fun loginRotatesTheSessionCookie() {
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        val first = http.sessionCookie()!!
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        val second = http.sessionCookie()!!
        assertNotEquals(first, second)
        val stale = Http(gw.port())
        val r = stale.send("GET", "/api/v1/auth/session", headers = mapOf("Cookie" to "${HttpAuth.COOKIE}=$first"))
        assertProblem(r, 401, "unauthorized")
        assertEquals(200, http.get("/api/v1/auth/session").status)
    }

    @Test fun stateChangingRequestWithoutCsrfIsRejected() {
        gw.allowWidgets()
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        val base = "/api/v1/config/org.oip.test/gateway/Widget"
        http.sendCsrf = false
        assertEquals(200, http.get("/api/v1/config").status)
        val missing = http.post(base, widgetDoc("a"))
        assertProblem(missing, 403, "csrf")
        assertEquals(0, (http.get(base).json["items"] as List<*>).size)
        http.csrf = "not-the-token"
        http.sendCsrf = true
        assertProblem(http.post(base, widgetDoc("a")), 403, "csrf")
        http.login("admin", TEST_PASSWORD)
        assertEquals(201, http.post(base, widgetDoc("a")).status)
    }

    @Test fun bearerTokenSkipsCsrfAndHonoursScopeAndRevocation() {
        gw.allowWidgets()
        val read = gw.security.apiKeys.create(
            "reader", setOf(Permissions.GATEWAY_READ), secureConnectionRequired = false, actor = "test",
        )
        val write = gw.security.apiKeys.create(
            "writer", setOf(Permissions.GATEWAY_WRITE), secureConnectionRequired = false, actor = "test",
        )
        val secureOnly = gw.security.apiKeys.create("tls-only", setOf(Permissions.GATEWAY_READ), actor = "test")
        val base = "/api/v1/config/org.oip.test/gateway/Widget"

        val reader = Http(gw.port())
        reader.authorizeBearer(read.token)
        assertEquals(200, reader.get(base).status)
        assertProblem(reader.post(base, widgetDoc("a")), 403, "forbidden")
        val who = reader.get("/api/v1/auth/session")
        assertEquals(200, who.status, who.body)
        assertEquals("apiKey", who.json["kind"])
        assertEquals("reader", who.json["principal"])
        assertNull(who.json["csrfToken"])

        val writer = Http(gw.port())
        writer.authorizeBearer(write.token)
        writer.sendCsrf = false
        assertEquals(201, writer.post(base, widgetDoc("a")).status)

        val tls = Http(gw.port())
        tls.authorizeBearer(secureOnly.token)
        assertProblem(tls.get(base), 403, "insecure-channel")

        gw.security.apiKeys.revoke("reader", actor = "test")
        assertProblem(reader.get(base), 401, "unauthorized")

        val bad = Http(gw.port())
        bad.authorizeBearer("not-a-token")
        assertProblem(bad.get(base), 401, "unauthorized")
        assertTrue(gw.auditCount(AuditActions.TOKEN_LOGIN, status = AuditStatus.FAILURE) >= 1)
        assertTrue(gw.auditCount(AuditActions.TOKEN_LOGIN, "reader", AuditStatus.SUCCESS) >= 1)
    }

    @Test fun rolesGateConfigModulesAndAudit() {
        gw.allowWidgets()
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        val base = "/api/v1/config/org.oip.test/gateway/Widget"
        val op = client("op")
        val view = client("view")
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)

        assertEquals(200, view.get("/api/v1/audit").status)
        assertProblem(view.get(base), 403, "forbidden")
        assertProblem(view.get("/api/v1/modules"), 403, "forbidden")
        assertEquals(200, op.get(base).status)
        assertEquals(200, op.get("/api/v1/modules").status)
        assertProblem(op.post(base, widgetDoc("a")), 403, "forbidden")
        assertProblem(op.post("/api/v1/modules", "{}"), 403, "forbidden")
        assertEquals(201, http.post(base, widgetDoc("a")).status)
        assertEquals("admin", ((http.get("$base/a").json["metadata"] as Map<*, *>)["modifiedBy"]))
    }

    @Test fun rolesGateBackupRestoreProjectsLogsAndDiagnostics() {
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        val op = client("op")
        val view = client("view")
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        val anon = Http(gw.port())

        assertProblem(anon.get("/api/v1/backups"), 401, "unauthorized")
        assertProblem(anon.post("/api/v1/backups/restore", ""), 401, "unauthorized")
        assertProblem(anon.get("/api/v1/projects"), 401, "unauthorized")
        assertProblem(anon.get("/api/v1/logs"), 401, "unauthorized")
        assertProblem(anon.get("/api/v1/diagnostics/memory"), 401, "unauthorized")
        assertProblem(view.get("/api/v1/backups"), 403, "forbidden")
        assertProblem(view.get("/api/v1/projects"), 403, "forbidden")
        assertProblem(view.get("/api/v1/logs"), 403, "forbidden")
        assertProblem(view.get("/api/v1/diagnostics/memory"), 403, "forbidden")
        assertProblem(op.send("POST", "/api/v1/backups"), 403, "forbidden")
        assertProblem(op.post("/api/v1/backups/restore", ""), 403, "forbidden")
        assertProblem(op.postRaw("/api/v1/projects/plant/import?policy=skip", byteArrayOf(1), "application/zip"), 403, "forbidden")
        assertProblem(op.put("/api/v1/logs/levels", """{"logger":"org.oip.http","level":"INFO"}"""), 403, "forbidden")
        assertEquals(200, op.get("/api/v1/backups").status)
        assertEquals(200, op.get("/api/v1/projects").status)
        assertEquals(200, op.get("/api/v1/logs").status)
        assertEquals(200, op.get("/api/v1/diagnostics/memory").status)

        val created = http.createBackup()
        assertEquals(200, created.status, created.body)
        assertTrue(org.oip.backup.BackupCipher.isEncrypted(created.raw), "REST backups are sealed (review-1 F3)")
    }

    /**
     * Tag value reads, browse, and history need tags.read. Viewer and operator hold it by default.
     * Browse and history routes arrive with PRs #54 and #52; until then an allowed caller gets 404.
     */
    @Test fun rolesGateTagReadsBrowseAndHistory() {
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        gw.security.roles.create("auditor", setOf(Permissions.GATEWAY_ACCESS, Permissions.AUDIT_READ), actor = "test")
        gw.createUser("aud", setOf("auditor"))
        val readBody = """{"paths":["[default]missing"]}"""
        val browseBody = """{"path":"[default]"}"""
        val history = "/api/v1/history/data?tagPath=x&from=0&to=10"
        val anon = Http(gw.port())
        val op = client("op")
        val view = client("view")
        val aud = client("aud")

        assertProblem(anon.post("/api/v1/tags/read", readBody), 401, "unauthorized")
        assertProblem(anon.post("/api/v1/tags/browse", browseBody), 401, "unauthorized")
        assertProblem(anon.get(history), 401, "unauthorized")
        for (c in listOf(view, op)) {
            val read = c.post("/api/v1/tags/read", readBody)
            assertEquals(200, read.status, read.body)
            assertEquals("[default]missing", ((read.json["values"] as List<*>).single() as Map<*, *>)["path"])
            assertFalse(c.post("/api/v1/tags/browse", browseBody).status in setOf(401, 403))
            assertFalse(c.get(history).status in setOf(401, 403))
        }
        assertEquals(200, aud.get("/api/v1/audit").status)
        assertProblem(aud.post("/api/v1/tags/read", readBody), 403, "forbidden")
        assertProblem(aud.post("/api/v1/tags/browse", browseBody), 403, "forbidden")
        assertProblem(aud.get(history), 403, "forbidden")
        assertProblem(aud.get("/api/v1/history"), 403, "forbidden")

        val withRead = Http(gw.port())
        withRead.authorizeBearer(
            gw.security.apiKeys.create("tag-reader", setOf(Permissions.TAGS_READ), secureConnectionRequired = false, actor = "test").token,
        )
        val without = Http(gw.port())
        without.authorizeBearer(
            gw.security.apiKeys.create("gw-reader", setOf(Permissions.GATEWAY_READ), secureConnectionRequired = false, actor = "test").token,
        )
        assertEquals(200, withRead.post("/api/v1/tags/read", readBody).status)
        assertFalse(withRead.get(history).status in setOf(401, 403))
        assertProblem(without.post("/api/v1/tags/read", readBody), 403, "forbidden")
        assertProblem(without.post("/api/v1/tags/browse", browseBody), 403, "forbidden")
        assertProblem(without.get(history), 403, "forbidden")
    }

    @Test fun openApiDescribesSessionBearerAndCsrf() {
        val doc = http.get("/api/docs")
        assertEquals(200, doc.status, doc.body)
        val paths = doc.json["paths"] as Map<*, *>
        assertTrue(paths.containsKey("/api/v1/auth/login"))
        assertTrue(paths.containsKey("/api/v1/auth/logout"))
        assertTrue(paths.containsKey("/api/v1/auth/session"))
        assertTrue(paths.containsKey("/api/v1/audit"))
        val schemes = ((doc.json["components"] as Map<*, *>)["securitySchemes"] as Map<*, *>).keys
        assertTrue("sessionCookie" in schemes && "bearerAuth" in schemes)
        val health = ((paths["/api/v1/health"] as Map<*, *>)["get"] as Map<*, *>)["security"] as List<*>
        assertTrue(health.isEmpty())
        assertTrue((doc.json["security"] as List<*>).size == 2)
    }

    private fun client(user: String): Http {
        val c = Http(gw.port())
        assertEquals(200, c.login(user, TEST_PASSWORD).status)
        return c
    }

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
        assertEquals("urn:oip:problem:$slug", r.json["type"])
        assertEquals(status, (r.json["status"] as Number).toInt())
        assertNotNull(r.json["title"])
        assertNotNull(r.json["detail"])
    }
}
