package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * SEC-016: security level rules place a user into levels on every login path (password, OIDC browser login,
 * provider bearer token, API key). SEC-017: the levels gate the HMI project route on the gateway (project sessions,
 * views, and components), and a level held grants its parents.
 *
 * Doc source: FEATURES SEC-016.
 * Doc source: FEATURES SEC-017.
 * SEC-019: a user grant puts one named user in a level at login, enforced on the session.
 * Doc source: FEATURES SEC-019.
 */
class SecurityLevelsGatewayTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private val levelsUrl = "/api/v1/config/org.oip.security/gateway/SecurityLevels/levels"

    @BeforeEach fun setUp() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0).start()
        admin = Http(gw.port())
        gw.loginAdmin(admin)
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.createUser("vic", setOf(DefaultRoles.VIEWER))
    }

    @AfterEach fun tearDown() = gw.close()

    private fun saveLevels(levels: List<String>, rules: List<Map<String, Any?>>): Http.Res {
        val current = admin.get(levelsUrl)
        val version = if (current.status == 200) ((current.json["metadata"] as Map<*, *>)["version"] as Number).toLong() else 0L
        val body = Json.write(mapOf(
            "apiVersion" to "oip/v1", "kind" to "SecurityLevels",
            "metadata" to mapOf("name" to "levels", "module" to "org.oip.security", "version" to version),
            "spec" to mapOf("levels" to levels.map { mapOf("path" to it) }, "rules" to rules),
        ))
        val res = admin.put(levelsUrl, body)
        // A first save creates the resource (201); later ones replace it (200). Callers check for success as 200.
        return if (res.status == 201) res.copy(status = 200) else res
    }

    private fun login(user: String): Http = Http(gw.port()).also { assertEquals(200, it.login(user, TEST_PASSWORD).status) }

    @Suppress("UNCHECKED_CAST")
    private fun levelsOf(http: Http): List<String> = http.get("/api/v1/auth/session").json["securityLevels"] as List<String>

    @Test fun aPasswordLoginGetsItsRoleLevelsAndTheRulesThatMatchIt() {
        val saved = saveLevels(
            listOf("Authenticated/Roles/operator", "Authenticated/Roles/viewer", "Local"),
            listOf(
                mapOf("name" to "local", "sources" to listOf("internal"), "grant" to listOf("Local"),
                    "conditions" to listOf(mapOf("type" to "address", "value" to "127.0.0.0/8"))),
                mapOf("name" to "not-here", "grant" to listOf("Authenticated/Roles/operator"),
                    "conditions" to listOf(mapOf("type" to "address", "value" to "192.0.2.0/24"))),
            ),
        )
        assertEquals(200, saved.status, saved.body)
        val ada = Http(gw.port())
        val loggedIn = ada.login("ada", TEST_PASSWORD)
        assertEquals(200, loggedIn.status, loggedIn.body)
        val expected = listOf("Authenticated", "Authenticated/Roles", "Authenticated/Roles/operator", "Local", "Public")
        assertEquals(expected, loggedIn.json["securityLevels"])
        assertEquals(expected, levelsOf(ada))
        assertEquals(listOf("Authenticated", "Authenticated/Roles", "Authenticated/Roles/viewer", "Local", "Public"), levelsOf(login("vic")))
        // The login's audit record is unchanged; the levels ride on the session.
        assertTrue(gw.auditCount(AuditActions.LOGIN, actor = "ada") >= 1)

        // A role taken away re-evaluates the session: the level that role granted is gone at the next request.
        gw.security.users.update(org.oip.security.UpdateUser("ada", roles = setOf(DefaultRoles.VIEWER)), actor = "test")
        assertEquals(listOf("Authenticated", "Authenticated/Roles", "Authenticated/Roles/viewer", "Local", "Public"), levelsOf(ada))
        // A level deleted from the tree is dropped from sessions that hold it.
        assertEquals(200, saveLevels(listOf("Authenticated/Roles/viewer"), emptyList()).status)
        assertEquals(listOf("Authenticated", "Authenticated/Roles", "Authenticated/Roles/viewer", "Public"), levelsOf(ada))
    }

    @Test fun aUserGrantPutsOneUserInALevelAtLoginWithoutAnyRule() {
        val body = Json.write(mapOf(
            "apiVersion" to "oip/v1", "kind" to "SecurityLevels",
            "metadata" to mapOf("name" to "levels", "module" to "org.oip.security", "version" to 0),
            "spec" to mapOf(
                "levels" to listOf(mapOf("path" to "Maintenance")),
                "userGrants" to listOf(mapOf("source" to "internal", "username" to "vic", "grant" to listOf("Maintenance"))),
            ),
        ))
        val saved = admin.put(levelsUrl, body)
        assertTrue(saved.status == 200 || saved.status == 201, saved.body)
        // vic is a viewer with no rule behind the level; ada has no grant.
        assertEquals(listOf("Authenticated", "Maintenance", "Public"), levelsOf(login("vic")))
        assertEquals(listOf("Authenticated", "Public"), levelsOf(login("ada")))
        // The admin trial endpoint shows the grant.
        val tried = admin.post(LoginLevels.EVALUATE, Json.write(mapOf("source" to "internal", "username" to "vic")))
        assertEquals(listOf("Authenticated", "Maintenance", "Public"), tried.json["levels"])
        // A grant naming an undefined level is refused.
        val bad = admin.put(levelsUrl, body.replace("\"grant\":[\"Maintenance\"]", "\"grant\":[\"Nowhere\"]").replace("\"version\":0", "\"version\":1"))
        assertEquals(422, bad.status, bad.body)
    }

    @Test fun anApiKeyIsEvaluatedAsItsOwnSourceAtEveryRequest() {
        assertEquals(200, saveLevels(listOf("Machines"), listOf(
            mapOf("name" to "keys", "sources" to listOf("api-key"), "grant" to listOf("Machines")),
        )).status)
        val key = gw.security.apiKeys.create("reader", setOf(Permissions.TAGS_READ), secureConnectionRequired = false, actor = "test")
        val machine = Http(gw.port()).also { it.authorizeBearer(key.token) }
        assertEquals(listOf("Authenticated", "Machines", "Public"), levelsOf(machine))
        // A password login is not an API key.
        assertEquals(listOf("Authenticated", "Public"), levelsOf(login("vic")))
    }

    @Test fun badRulesAreRefusedAndTryingRulesNeedsUserAdministration() {
        val refused = saveLevels(listOf("Ops"), listOf(mapOf("name" to "x", "grant" to listOf("Nowhere"))))
        assertEquals(422, refused.status, refused.body)
        assertTrue(refused.body.contains("Nowhere"), refused.body)
        // An operator cannot change the rules (security settings need the three administration permissions).
        assertEquals(403, login("ada").put(levelsUrl, "{}").status)

        assertEquals(200, saveLevels(listOf("Night"), listOf(mapOf("name" to "night", "grant" to listOf("Night"),
            "conditions" to listOf(mapOf("type" to "time", "from" to "22:00", "to" to "06:00", "timeZone" to "UTC"))))).status)
        val tried = admin.post(LoginLevels.EVALUATE, Json.write(mapOf("source" to "internal", "username" to "x", "at" to "2026-11-09T23:30:00Z")))
        assertEquals(200, tried.status, tried.body)
        assertEquals(listOf("Authenticated", "Night", "Public"), tried.json["levels"])
        assertEquals(listOf("night"), tried.json["rules"])
        val day = admin.post(LoginLevels.EVALUATE, Json.write(mapOf("at" to "2026-11-09T12:00:00Z")))
        assertEquals(listOf("Authenticated", "Public"), day.json["levels"])
        assertEquals(422, admin.post(LoginLevels.EVALUATE, Json.write(mapOf("address" to "gateway.example"))).status)
        assertEquals(403, login("ada").post(LoginLevels.EVALUATE, "{}").status)
        assertEquals(401, Http(gw.port()).post(LoginLevels.EVALUATE, "{}").status)
    }

    @Test fun anOidcLoginAndItsBearerTokenAreEvaluatedOverTheProviderClaims() {
        TestOidcProvider().use { idp ->
            val created = admin.post(IdentityProviderApi.BASE, Json.write(mapOf(
                "name" to "corp", "type" to "oidc", "issuer" to idp.issuer,
                "authorizationEndpoint" to "${idp.issuer}/authorize", "tokenEndpoint" to "${idp.issuer}/token",
                "jwksUri" to "${idp.issuer}/jwks", "clientId" to idp.clientId, "clientSecret" to idp.clientSecret,
                "defaultRoles" to listOf(DefaultRoles.VIEWER),
            )))
            assertEquals(201, created.status, created.body)
            assertEquals(200, saveLevels(listOf("Plant/Operators", "Plant/Engineers"), listOf(
                mapOf("name" to "ops", "sources" to listOf("corp"), "grant" to listOf("Plant/Operators"),
                    "conditions" to listOf(mapOf("type" to "attribute", "name" to "groups", "value" to "plant-ops"))),
                mapOf("name" to "eng-password-only", "sources" to listOf("internal"), "grant" to listOf("Plant/Engineers")),
            )).status)
            idp.user = mapOf("sub" to "u-1", "preferred_username" to "alice", "groups" to listOf("plant-ops"))
            val browser = Http(gw.port())
            val start = browser.get("/api/v1/auth/providers/corp/login")
            assertEquals(302, start.status, start.body)
            val done = browser.get(pathOf(redirectFrom(start.header("Location")!!)))
            assertEquals(302, done.status, done.body)
            assertEquals(listOf("Plant", "Plant/Operators", "Authenticated", "Public").sorted(), levelsOf(browser))
            val record = gw.security.audit.query(AuditQuery(action = AuditActions.LOGIN, actor = "corp:alice", limit = 5)).records.first()
            assertTrue(record.detail.contains("levels=") && record.detail.contains("Plant/Operators"), record.detail)

            // The same provider's access token as a bearer credential is evaluated at the request.
            idp.user = mapOf("sub" to "u-2", "preferred_username" to "bob", "groups" to listOf("visitors"))
            val bearer = Http(gw.port()).also { it.authorizeBearer(idp.accessToken()) }
            assertEquals(listOf("Authenticated", "Public"), levelsOf(bearer))
        }
    }

    private fun view(path: String, security: Map<String, Any?>?, children: List<Map<String, Any?>> = emptyList(), rootSecurity: Map<String, Any?>? = null) =
        Json.write(mapOf(
            "apiVersion" to "oip/v1", "kind" to "View",
            "metadata" to mapOf("name" to path, "module" to "designer", "version" to 0),
            "spec" to mapOf("path" to path, "title" to path, "document" to buildMap {
                put("params", emptyMap<String, Any?>())
                if (security != null) put("security", security)
                put("root", buildMap {
                    put("id", "root"); put("kind", "container"); put("layout", "coordinate"); put("children", children)
                    if (rootSecurity != null) put("security", rootSecurity)
                })
            }),
        ))

    @Test fun levelsGateHmiSessionsViewsAndComponentsOnTheGateway() {
        assertEquals(200, saveLevels(listOf("Authenticated/Roles/operator", "Authenticated/Roles/viewer"), emptyList()).status)
        val bypass = mapOf(
            "id" to "bypass", "kind" to "component", "type" to "button", "name" to "Bypass",
            "security" to mapOf("levels" to listOf("Authenticated/Roles/operator")),
            "props" to mapOf("text" to "Reactor bypass"),
            "bindings" to mapOf("text" to mapOf("kind" to "tag", "mode" to "direct", "path" to "[default]reactor/bypass")),
            "events" to listOf(mapOf("event" to "onClick", "actions" to listOf(mapOf("kind" to "script", "hook" to "bypass", "source" to "pass")))),
        )
        val label = mapOf("id" to "status", "kind" to "component", "type" to "label", "name" to "Status", "props" to mapOf("text" to "Reactor status"))
        // Parent levels: any role level meets a requirement of Authenticated/Roles.
        val panel = mapOf("id" to "panel", "kind" to "container", "layout" to "flex", "name" to "Panel",
            "security" to mapOf("levels" to listOf("Authenticated/Roles")), "children" to listOf(mapOf(
                "id" to "inner", "kind" to "component", "type" to "label", "name" to "Inner", "props" to mapOf("text" to "Shift notes"))))
        assertEquals(201, admin.post("/api/v1/config/designer/project/plant/View", view("reactor", null, listOf(label, bypass, panel))).status)
        assertEquals(201, admin.post("/api/v1/config/designer/project/plant/View",
            view("ops", mapOf("levels" to listOf("Authenticated/Roles/operator", "Authenticated/Roles/viewer"), "requireAll" to true))).status)
        assertEquals(201, admin.post("/api/v1/config/designer/project/plant/View",
            view("root-guarded", null, rootSecurity = mapOf("levels" to listOf("Authenticated/Roles/operator")))).status)

        val viewer = login("vic").get("/api/v1/hmi/projects/plant")
        assertEquals(200, viewer.status, viewer.body)
        // The component the viewer may not see is not sent at all: no props, binding, tag path or script.
        for (secret in listOf("Reactor bypass", "reactor/bypass", "\"bypass\"")) assertFalse(viewer.body.contains(secret), secret)
        assertTrue(viewer.body.contains("Reactor status"))
        assertTrue(viewer.body.contains("Shift notes"), "a role level meets the parent level requirement")
        assertEquals(listOf("ops", "root-guarded"), viewer.json["deniedViews"])

        val operator = login("ada").get("/api/v1/hmi/projects/plant")
        assertTrue(operator.body.contains("Reactor bypass"))
        // requireAll: the operator lacks the viewer level.
        assertEquals(listOf("ops"), operator.json["deniedViews"])

        // A project's session levels refuse the whole project.
        assertEquals(200, admin.send("PATCH", "/api/v1/projects/plant",
            """{"sessionLevels":["Authenticated/Roles/operator"]}""".toByteArray()).status)
        val refused = login("vic").get("/api/v1/hmi/projects/plant")
        assertEquals(403, refused.status, refused.body)
        assertTrue(refused.body.contains(HmiProjectApi.SESSION_REFUSED) && refused.body.contains("Authenticated/Roles/operator"), refused.body)
        assertEquals(emptyList<String>(), login("vic").get("/api/v1/hmi/projects").json["projects"])
        assertEquals(200, login("ada").get("/api/v1/hmi/projects/plant").status)
        assertEquals(422, admin.send("PATCH", "/api/v1/projects/plant", """{"sessionLevels":["/bad"]}""".toByteArray()).status)
    }

    @Test fun anUnreadableRequirementIsUnmet() {
        val viewer = HmiProjectApi.Viewer(emptySet(), null, setOf("Public", "Authenticated"))
        assertFalse(HmiSecurity.requirement(mapOf("levels" to listOf(42)))!!.allows(viewer))
        assertFalse(HmiSecurity.requirement(mapOf("levels" to "Authenticated"))!!.allows(viewer))
        assertTrue(HmiSecurity.requirement(mapOf("levels" to listOf("Authenticated")))!!.allows(viewer))
        assertFalse(HmiSecurity.requirement(mapOf("levels" to listOf("Authenticated", "Other"), "requireAll" to "yes"))!!.allows(viewer))
        assertNull(HmiSecurity.requirement(mapOf("levels" to emptyList<String>())))
    }
}
