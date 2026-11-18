package org.oip.gateway

import io.javalin.http.HandlerType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path

/**
 * Review-1 F6: the route matrix denies by default. A registered `/api/` route with no explicit rule is refused for every
 * caller, so a route added without a matrix entry is closed until someone adds one.
 */
class RouteMatrixTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun aRegisteredRouteWithoutARuleIsDeniedForEveryCaller() {
        // A route added without a matrix entry, as a future change might.
        gw.app.get("/api/v1/unruled-probe") { it.result("open") }
        gw.app.post("/api/v1/unruled-probe") { it.result("open") }
        val anon = Http(gw.port())
        assertEquals(401, anon.get("/api/v1/unruled-probe").status)
        for (user in listOf("admin", "view")) {
            val c = client(user)
            for (method in listOf("GET", "POST")) {
                val r = c.send(method, "/api/v1/unruled-probe", if (method == "POST") "{}".toByteArray() else null)
                assertEquals(403, r.status, "$user $method: ${r.body}")
                assertEquals("urn:oip:problem:forbidden", r.json["type"])
                assertFalse(r.body.contains("open"))
            }
            // A path no handler serves is still a 404 once the caller is authenticated.
            assertEquals(404, c.get("/api/v1/not-a-route").status)
        }
        assertEquals(401, anon.get("/api/v1/not-a-route").status)
    }

    /**
     * Every registered `/api/` route has an explicit rule. A route registered without one is denied to every caller
     * at runtime; this test names it so the gap is found before it ships.
     */
    @Test fun everyRegisteredApiRouteHasAnExplicitRule() {
        val verbs = setOf(HandlerType.GET, HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE, HandlerType.HEAD)
        val routes = gw.app.unsafeConfig().pvt.internalRouter.allHttpHandlers()
            .map { it.endpoint }
            .filter { it.method in verbs && it.path.startsWith("/api/") }
            .map { it.method.name to it.path }
            .distinct()
        assertTrue(routes.size > 100, "route enumeration found only ${routes.size} routes")
        val missing = routes.filter { (method, path) -> GatewayAuth.explicitRule(method, sample(path)) == null }
        assertEquals(emptyList<Pair<String, String>>(), missing, "routes without an access rule")
        // The HMI session socket is registered as a WebSocket route, not an HTTP one.
        assertEquals("tags.read", GatewayAuth.explicitRule("GET", "/api/v1/ws"))
    }

    /** Spot checks of intended access on the routes review-1 named. */
    @Test fun sensitiveRoutesKeepTheirIntendedRules() {
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/backups"))
        assertEquals("backups.admin", GatewayAuth.explicitRule("GET", "/api/v1/backups/20260101-000000-000.zip"))
        assertEquals("backups.admin", GatewayAuth.explicitRule("POST", "/api/v1/backups"))
        assertEquals("backups.admin", GatewayAuth.explicitRule("POST", "/api/v1/backups/restore"))
        assertEquals("backups.admin", GatewayAuth.explicitRule("POST", "/api/v1/backups/20260101-000000-000.zip/restore"))
        // Review-1 F3: sealed backups are stored as .oipbk under the same rules.
        assertEquals("backups.admin", GatewayAuth.explicitRule("GET", "/api/v1/backups/20260101-000000-000.oipbk"))
        assertEquals("backups.admin", GatewayAuth.explicitRule("POST", "/api/v1/backups/20260101-000000-000.oipbk/restore"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("PUT", "/api/v1/logs/levels"))
        assertEquals("users.admin", GatewayAuth.explicitRule("GET", "/api/v1/security/users"))
        assertEquals("authenticated", GatewayAuth.explicitRule("POST", "/api/v1/db/queries/q/execute"))
        assertEquals("public", GatewayAuth.explicitRule("GET", "/api/v1/health"))
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", JOURNAL_DIAGNOSTICS))
        assertEquals("tags.write", GatewayAuth.explicitRule("POST", "/api/v1/alarms/shelve"))
        assertNull(GatewayAuth.explicitRule("GET", "/api/v1/not-a-route"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", QUARANTINE_IMPORT_PATH))
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", DISK_CACHE_ARCHIVE_PATH))
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", DISK_CACHE_LOAD_PATH))
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", DISK_CACHE_ARCHIVES_PATH))
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "$DISK_CACHE_ARCHIVES_PATH/sample"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("DELETE", "$DISK_CACHE_ARCHIVES_PATH/sample"))
    }

    /**
     * SEC-020: project access and the lock list are reads; taking a lock and protecting a resource are Designer
     * actions; releasing a lock is gateway.write (the handler adds designer.login for the holder, users.admin to break).
     */
    @Test fun designerProjectSecurityRoutesKeepTheirRules() {
        val base = DesignerProjectApi.BASE + "/demo"
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "$base/access"))
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "$base/locks"))
        assertEquals("gateway.write+designer.login", GatewayAuth.explicitRule("POST", "$base/locks"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("DELETE", "$base/locks/designer/View/main"))
        assertEquals("gateway.write+designer.login", GatewayAuth.explicitRule("PUT", "$base/protected/designer/View/main"))
        assertEquals("gateway.write+designer.login", GatewayAuth.explicitRule("DELETE", "$base/protected/designer/View/main"))
        val view = client("view")
        assertEquals(403, view.post("$base/locks", "{}").status)
        assertEquals(403, view.delete("$base/locks/designer/View/main").status)
    }

    /**
     * DAT-016, DAT-027: an uploaded JDBC driver is code that runs in the gateway, so upload and removal need the same
     * permission as installing a module; reading and the per-driver settings and translators are gateway configuration.
     */
    @Test fun jdbcDriverRoutesNeedModuleAdministrationToChange() {
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/db/drivers"))
        assertEquals("modules.admin", GatewayAuth.explicitRule("POST", "/api/v1/db/drivers"))
        assertEquals("modules.admin", GatewayAuth.explicitRule("DELETE", "/api/v1/db/drivers/vendor"))
        assertEquals("modules.admin", GatewayAuth.explicitRule("PUT", "/api/v1/db/drivers/vendor"))
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/db/driver-settings"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("PUT", "/api/v1/db/driver-settings/oracle"))
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/db/translators"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("DELETE", "/api/v1/db/translators/mine"))
        // The existing database rules are unchanged.
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/db/connections"))
        assertEquals("authenticated", GatewayAuth.explicitRule("POST", "/api/v1/db/queries/q/execute"))
    }

    /** Store-and-forward writes are gateway.write. A viewer and an operator are refused. */
    @Test
    fun storeAndForwardWritesNeedGatewayWrite() {
        gw.createUser("oper", setOf(DefaultRoles.OPERATOR))
        val calls = listOf(
            "POST" to QUARANTINE_IMPORT_PATH,
            "POST" to "$DISK_CACHE_ARCHIVE_PATH?provider=plant",
            "POST" to DISK_CACHE_LOAD_PATH,
            "DELETE" to "$DISK_CACHE_ARCHIVES_PATH/plant_20260601T000000.000Z.json",
        )
        for (user in listOf("view", "oper")) {
            val c = client(user)
            for ((method, path) in calls) {
                val body = if (method == "DELETE") null else "{}".toByteArray()
                val response = c.send(method, path, body)
                assertEquals(403, response.status, "$user $method $path ${response.body}")
                assertTrue(response.body.contains("gateway.write"), response.body)
            }
        }
    }

    /** Review-4 F4 and F5: design writes need designer.login too; the HMI reads its project with tags.read. */
    @Test fun designerAndHmiRoutesHaveTheirIntendedRules() {
        for (verb in listOf("POST", "PUT", "PATCH", "DELETE")) {
            assertEquals("gateway.write+designer.login", GatewayAuth.explicitRule(verb, "/api/v1/config/designer/project/demo/View/overview"))
        }
        assertEquals("gateway.write+designer.login", GatewayAuth.explicitRule("PUT", "/api/v1/config/designer/project/demo/ScriptHooks/script-hooks"))
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/config/designer/project/demo/View/overview"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("PUT", "/api/v1/config/tags/gateway/Tag/t1"))
        assertEquals("tags.read", GatewayAuth.explicitRule("GET", "/api/v1/hmi/projects/demo"))
        assertEquals("tags.read", GatewayAuth.explicitRule("GET", "/api/v1/hmi/projects"))
        assertNull(GatewayAuth.explicitRule("PUT", "/api/v1/hmi/projects/demo"))
    }

    /** P1-32/P1-33/P1-34: provider login is reachable before a session exists; administration is users.admin. */
    @Test fun identityProviderRoutesHaveTheirIntendedRules() {
        assertEquals("public", GatewayAuth.explicitRule("GET", "/api/v1/auth/providers"))
        assertEquals("public", GatewayAuth.explicitRule("GET", "/api/v1/auth/providers/corp/login"))
        assertEquals("public", GatewayAuth.explicitRule("GET", "/api/v1/auth/providers/corp/callback"))
        assertEquals("public", GatewayAuth.explicitRule("POST", "/api/v1/auth/providers/corp/acs"))
        assertEquals("public", GatewayAuth.explicitRule("GET", "/api/v1/auth/providers/corp/test-logout"))
        assertEquals("public", GatewayAuth.explicitRule("GET", "/api/v1/auth/providers/corp/test-result"))
        assertNull(GatewayAuth.explicitRule("POST", "/api/v1/auth/providers/corp/test-result"))
        assertEquals("users.admin", GatewayAuth.explicitRule("POST", "/api/v1/security/identity-providers/corp/test"))
        assertEquals("users.admin", GatewayAuth.explicitRule("GET", "/api/v1/security/identity-providers/corp/test/abc"))
        // Nothing else under the login prefix is open.
        assertNull(GatewayAuth.explicitRule("POST", "/api/v1/auth/providers/corp/login"))
        assertNull(GatewayAuth.explicitRule("GET", "/api/v1/auth/providers/corp/acs"))
        assertNull(GatewayAuth.explicitRule("GET", "/api/v1/auth/providers/corp/other"))
        for (verb in listOf("GET", "POST", "PUT", "DELETE")) {
            assertEquals("users.admin", GatewayAuth.explicitRule(verb, "/api/v1/security/identity-providers/corp"))
        }
        assertEquals("users.admin", GatewayAuth.explicitRule("POST", "/api/v1/security/identity-providers/import"))
        assertEquals("users.admin", GatewayAuth.explicitRule("POST", "/api/v1/security/user-sources/internal/check"))
        assertNull(GatewayAuth.explicitRule("GET", "/api/v1/security/user-sources/internal/check"))
    }

    /** SEC-007, SEC-010, SEC-021, SEC-025: user source administration is users.admin, and only the routes it serves. */
    @Test fun userSourceRoutesNeedUsersAdmin() {
        assertEquals("users.admin", GatewayAuth.explicitRule("GET", "/api/v1/security/user-sources"))
        assertEquals("users.admin", GatewayAuth.explicitRule("POST", "/api/v1/security/user-sources"))
        for (verb in listOf("GET", "PUT", "DELETE")) {
            assertEquals("users.admin", GatewayAuth.explicitRule(verb, "/api/v1/security/user-sources/corp"))
        }
        assertNull(GatewayAuth.explicitRule("POST", "/api/v1/security/user-sources/corp"))
        assertNull(GatewayAuth.explicitRule("PUT", "/api/v1/security/user-sources"))
        assertNull(GatewayAuth.explicitRule("GET", "/api/v1/security/user-sources/corp/other"))
    }

    /** A concrete path for a route template: `{param}` becomes one segment, `<path>` a config resource path. */
    private fun sample(template: String): String = template
        .replace(Regex("<[^>]+>"), "m/gateway/Thing/n")
        .replace(Regex("\\{[^}]+}"), "sample")

    private fun client(user: String): Http {
        val c = Http(gw.port())
        assertEquals(200, c.login(user, TEST_PASSWORD).status)
        return c
    }
}
