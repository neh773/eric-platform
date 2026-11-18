package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.security.DefaultRoles
import java.nio.file.Path
import java.time.Instant

/**
 * P1-32 (SEC-013): browser login through an OIDC provider, against an in-process test provider ([TestOidcProvider]).
 * Authorization code with PKCE; the session carries the roles mapped from the token claims; a bad state, nonce,
 * issuer, audience, expiry, or signature is refused; logout ends the session; every login is audited. Provider tokens
 * also work as REST bearer credentials (SPEC §6).
 */
class OidcLoginTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var idp: TestOidcProvider
    private lateinit var admin: Http

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        idp = TestOidcProvider()
        admin = Http(gw.port())
        gw.loginAdmin(admin)
        val created = admin.post(IdentityProviderApi.BASE, Json.write(mapOf(
            "name" to "corp",
            "type" to "oidc",
            "displayName" to "Corporate sign-in",
            "issuer" to idp.issuer,
            "authorizationEndpoint" to "${idp.issuer}/authorize",
            "tokenEndpoint" to "${idp.issuer}/token",
            "jwksUri" to "${idp.issuer}/jwks",
            "endSessionEndpoint" to "${idp.issuer}/logout",
            "clientId" to idp.clientId,
            "clientSecret" to idp.clientSecret,
            "rolesClaim" to "groups",
            "roleMappings" to listOf(mapOf("value" to "plant-ops", "roles" to listOf(DefaultRoles.OPERATOR))),
            "defaultRoles" to listOf(DefaultRoles.VIEWER),
        )))
        assertEquals(201, created.status, created.body)
    }

    @AfterEach fun tearDown() {
        idp.close()
        gw.close()
    }

    /** Run the browser flow; returns the gateway's final response (the callback). */
    private fun browserLogin(browser: Http, callbackEdit: (String) -> String = { it }): Http.Res {
        val start = browser.get("/api/v1/auth/providers/corp/login?returnTo=/admin/users")
        assertEquals(302, start.status, start.body)
        val authorize = start.header("Location")!!
        assertTrue(authorize.startsWith("${idp.issuer}/authorize?"), authorize)
        val q = TestOidcProvider.query(java.net.URI.create(authorize).rawQuery)
        assertEquals("code", q["response_type"])
        assertEquals("S256", q["code_challenge_method"])
        assertFalse(q["code_challenge"].isNullOrEmpty())
        assertFalse(q["nonce"].isNullOrEmpty())
        assertFalse(q["state"].isNullOrEmpty())
        assertTrue(q.getValue("scope").split(' ').contains("openid"))
        return browser.get(callbackEdit(pathOf(redirectFrom(authorize))))
    }

    private fun refusedWith(res: Http.Res, browser: Http, reason: String) {
        assertEquals(302, res.status, res.body)
        assertEquals("/admin/login?error=idp", res.header("Location"))
        assertNull(browser.sessionCookie(), "no session for a refused login")
        assertEquals(401, browser.get("/api/v1/auth/session").status)
        val rows = gw.security.audit.query(AuditQuery(action = AuditActions.LOGIN_ATTEMPT, status = AuditStatus.FAILURE, limit = 50)).records
        assertTrue(rows.any { it.detail.contains("provider=corp") && it.detail.contains(reason) }, "audit rows: ${rows.map { it.detail }}")
    }

    @Test fun loginSetsASessionWithMappedRolesAndIsAudited() {
        val browser = Http(gw.port())
        val done = browserLogin(browser)
        assertEquals(302, done.status, done.body)
        assertEquals("/admin/users", done.header("Location"))
        assertNotNull(browser.sessionCookie())
        val session = browser.get("/api/v1/auth/session")
        assertEquals(200, session.status, session.body)
        assertEquals("corp:alice", session.json["principal"])
        assertEquals("corp", session.json["provider"])
        assertEquals(listOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), session.json["roles"])
        @Suppress("UNCHECKED_CAST")
        val permissions = session.json["permissions"] as List<String>
        assertTrue("tags.write" in permissions, "$permissions")
        assertFalse("users.admin" in permissions)
        // The mapped roles are what the session can do: a tag read passes, user administration does not.
        assertEquals(403, browser.get("/api/v1/security/users").status)
        browser.csrf = session.json["csrfToken"] as String
        assertEquals(200, browser.post("/api/v1/tags/read", """{"paths":["[default]nope"]}""").status)
        // The secret went in the Basic header, never in the form or a URL.
        assertTrue(idp.tokenForms.none { form -> form.values.any { it.contains(idp.clientSecret!!) } })
        val rows = gw.security.audit.query(AuditQuery(action = AuditActions.LOGIN, actor = "corp:alice", status = AuditStatus.SUCCESS, limit = 10)).records
        assertEquals(1, rows.size)
        assertTrue(rows[0].detail.contains("provider=corp"), rows[0].detail)
    }

    /** SEC-018. Doc source: FEATURES SEC-018. */
    @Test fun anAdminTestLoginAndLogoutShowTheResultAndIssueNoSession() {
        val started = admin.post("${IdentityProviderApi.BASE}/corp/test", "")
        assertEquals(201, started.status, started.body)
        val id = started.json["testId"] as String
        val browser = Http(gw.port())
        val start = browser.get(started.json["loginPath"] as String)
        assertEquals(302, start.status, start.body)
        val pending = admin.get("${IdentityProviderApi.BASE}/corp/test/$id")
        assertEquals("started", pending.json["status"])
        val done = browser.get(pathOf(redirectFrom(start.header("Location")!!)))
        assertEquals(200, done.status, done.body)
        assertTrue(done.body.contains("alice") && done.body.contains("Identity provider test"), done.body)
        // The browser flow ran, but no session exists for it.
        assertNull(browser.sessionCookie())
        assertEquals(401, browser.get("/api/v1/auth/session").status)
        val result = admin.get("${IdentityProviderApi.BASE}/corp/test/$id").json
        assertEquals("success", result["status"])
        assertEquals("alice", result["username"])
        assertEquals(listOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), result["roles"])
        assertEquals(false, (result["logout"] as Map<*, *>)["completed"])
        // A ticket is single use.
        assertEquals(404, Http(gw.port()).get("/api/v1/auth/providers/corp/login?test=$id").status)
        // Logout: the browser is sent to the provider's end-session endpoint and returns.
        val out = browser.get("/api/v1/auth/providers/corp/test-logout?test=$id")
        assertEquals(302, out.status, out.body)
        val target = out.header("Location")!!
        assertTrue(target.startsWith("${idp.issuer}/logout?"), target)
        val back = TestOidcProvider.query(java.net.URI.create(target).rawQuery).getValue("post_logout_redirect_uri")
        val returned = browser.get(pathOf(back))
        assertEquals(200, returned.status, returned.body)
        assertTrue(returned.body.contains("completed"), returned.body)
        assertEquals(true, ((admin.get("${IdentityProviderApi.BASE}/corp/test/$id").json)["logout"] as Map<*, *>)["completed"])
        // Both steps are audited, with no session login row.
        val rows = gw.security.audit.query(AuditQuery(action = AuditActions.IDP_TEST, limit = 10)).records
        assertEquals(2, rows.size, rows.toString())
        assertTrue(rows.all { it.actor == "admin" })
        assertEquals(0, gw.security.audit.query(AuditQuery(action = AuditActions.LOGIN, actor = "corp:alice", limit = 10)).records.size)
    }

    @Test fun aFailedTestLoginShowsTheReasonAndLogoutNeedsASuccessfulLogin() {
        val id = admin.post("${IdentityProviderApi.BASE}/corp/test", "").json["testId"] as String
        idp.user = mapOf("preferred_username" to "alice")
        val browser = Http(gw.port())
        val start = browser.get("/api/v1/auth/providers/corp/login?test=$id")
        val done = browser.get(pathOf(redirectFrom(start.header("Location")!!)))
        assertEquals(200, done.status, done.body)
        val result = admin.get("${IdentityProviderApi.BASE}/corp/test/$id").json
        assertEquals("failure", result["status"])
        assertEquals("token has no sub", result["reason"])
        assertEquals(404, browser.get("/api/v1/auth/providers/corp/test-logout?test=$id").status)
        // Without a ticket the public routes show nothing; the admin routes need users.admin.
        assertEquals(404, Http(gw.port()).get("/api/v1/auth/providers/corp/test-result?test=nope").status)
        assertEquals(404, Http(gw.port()).get("/api/v1/auth/providers/corp/test-result").status)
        assertEquals(401, Http(gw.port()).post("${IdentityProviderApi.BASE}/corp/test", "").status)
        assertEquals(404, admin.post("${IdentityProviderApi.BASE}/missing/test", "").status)
        // A test ticket for another provider's id does not work here.
        assertEquals(404, admin.get("${IdentityProviderApi.BASE}/other/test/$id").status)
    }

    /** SEC-015. Doc source: FEATURES SEC-015. */
    @Test fun nameEmailAndGroupsAreMappedFromTheTokenOntoTheSession() {
        idp.user = mapOf(
            "sub" to "u-9", "preferred_username" to "dana", "name" to "Dana Ng", "email" to "dana@example.test",
            "groups" to listOf("plant-ops"),
        )
        val browser = Http(gw.port())
        assertEquals(302, browserLogin(browser).status)
        val session = browser.get("/api/v1/auth/session").json
        assertEquals("Dana Ng", session["displayName"])
        assertEquals("dana@example.test", session["email"])
        assertEquals(listOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), session["roles"])

        // Per-provider claim names: this provider keeps the name and email under other claims.
        val current = admin.get("${IdentityProviderApi.BASE}/corp")
        val spec = (current.json - "clientSecretSet" - "callbackPath" - "name" - "version")
        val put = admin.put("${IdentityProviderApi.BASE}/corp", Json.write(spec + mapOf("nameClaim" to "full_name", "emailClaim" to "mail")))
        assertEquals(200, put.status, put.body)
        idp.user = mapOf(
            "sub" to "u-9", "preferred_username" to "dana", "name" to "Wrong", "email" to "wrong@example.test",
            "full_name" to "Dana N.", "mail" to "dana2@example.test", "groups" to listOf("plant-ops"),
        )
        val again = Http(gw.port())
        assertEquals(302, browserLogin(again).status)
        val second = again.get("/api/v1/auth/session").json
        assertEquals("Dana N.", second["displayName"])
        assertEquals("dana2@example.test", second["email"])

        // An unusable email is dropped, not stored; a missing name leaves the field out.
        idp.user = mapOf("sub" to "u-9", "preferred_username" to "dana", "mail" to "not an address", "groups" to listOf("plant-ops"))
        val third = Http(gw.port())
        assertEquals(302, browserLogin(third).status)
        val bare = third.get("/api/v1/auth/session").json
        assertFalse(bare.containsKey("email"))
        assertFalse(bare.containsKey("displayName"))
    }

    /** P4-04 (HMP-092): a project whose sessions need the `corp` provider opens for a `corp` login, not a password login. */
    @Test fun aProjectThatNeedsTheProviderOpensForAProviderLogin() {
        val view = """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"home","module":"designer","version":0},
            "spec":{"path":"home","title":"Home","document":{"params":{},"root":{"id":"root","kind":"container","layout":"coordinate","children":[]}}}}"""
        assertEquals(201, admin.post("/api/v1/config/designer/project/plant/View", view).status)
        val patched = admin.send("PATCH", "/api/v1/projects/plant", """{"sessionIdentityProvider":"corp"}""".toByteArray())
        assertEquals(200, patched.status, patched.body)
        val browser = Http(gw.port())
        assertEquals(302, browserLogin(browser).status)
        val opened = browser.get("/api/v1/hmi/projects/plant")
        assertEquals(200, opened.status, opened.body)
        assertEquals(listOf("plant"), browser.get("/api/v1/hmi/projects").json["projects"])
        val refused = admin.get("/api/v1/hmi/projects/plant")
        assertEquals(403, refused.status, refused.body)
        assertTrue(refused.body.contains("identity provider 'corp'"), refused.body)
    }

    @Test fun theLoginPageListsEnabledProviders() {
        val anon = Http(gw.port())
        val list = anon.get(IdpLogin.PROVIDERS)
        assertEquals(200, list.status)
        @Suppress("UNCHECKED_CAST")
        val items = list.json["items"] as List<Map<String, Any?>>
        assertEquals(listOf("corp"), items.map { it["name"] })
        assertEquals("Corporate sign-in", items[0]["displayName"])
        assertFalse(list.body.contains("client-secret-1"))
    }

    @Test fun badStateIsRefused() {
        val browser = Http(gw.port())
        val res = browserLogin(browser) { it.replace(Regex("state=[^&]+"), "state=forged") }
        refusedWith(res, browser, "unknown or used state")
        assertEquals(0, idp.tokenRequests.get(), "a forged state never reaches the token endpoint")
    }

    @Test fun aReplayedCallbackIsRefused() {
        val browser = Http(gw.port())
        var callback = ""
        assertEquals("/admin/users", browserLogin(browser) { it.also { p -> callback = p } }.header("Location"))
        val again = Http(gw.port())
        refusedWith(again.get(callback), again, "unknown or used state")
    }

    @Test fun aCallbackInAnotherBrowserIsRefused() {
        val browser = Http(gw.port())
        val start = browser.get("/api/v1/auth/providers/corp/login")
        val callback = pathOf(redirectFrom(start.header("Location")!!))
        val other = Http(gw.port())
        refusedWith(other.get(callback), other, "another browser")
    }

    @Test fun badNonceIsRefused() {
        idp.tamper = { it["nonce"] = "not-the-nonce" }
        val browser = Http(gw.port())
        refusedWith(browserLogin(browser), browser, "nonce mismatch")
    }

    @Test fun missingNonceIsRefused() {
        idp.tamper = { it.remove("nonce") }
        val browser = Http(gw.port())
        refusedWith(browserLogin(browser), browser, "no nonce")
    }

    @Test fun badIssuerIsRefused() {
        idp.tamper = { it["iss"] = "http://127.0.0.1:1/other" }
        val browser = Http(gw.port())
        refusedWith(browserLogin(browser), browser, "issuer mismatch")
    }

    @Test fun badAudienceIsRefused() {
        idp.tamper = { it["aud"] = "someone-else" }
        val browser = Http(gw.port())
        refusedWith(browserLogin(browser), browser, "audience mismatch")
    }

    @Test fun expiredTokenIsRefused() {
        idp.tamper = { it["exp"] = Instant.now().epochSecond - 3600 }
        val browser = Http(gw.port())
        refusedWith(browserLogin(browser), browser, "token expired")
    }

    @Test fun aTokenSignedByAnotherKeyIsRefused() {
        idp.signingKeys = rsaKeyPair()
        val browser = Http(gw.port())
        refusedWith(browserLogin(browser), browser, "signature is invalid")
    }

    @Test fun logoutEndsTheSessionAndPointsAtTheProvider() {
        val browser = Http(gw.port())
        browserLogin(browser)
        val session = browser.get("/api/v1/auth/session")
        browser.csrf = session.json["csrfToken"] as String
        val out = browser.post("/api/v1/auth/logout", "")
        assertEquals(200, out.status, out.body)
        val end = out.json["endSessionUrl"] as String
        assertTrue(end.startsWith("${idp.issuer}/logout?client_id=oip-gateway"), end)
        assertEquals(401, browser.get("/api/v1/auth/session").status)
        assertEquals(1L, gw.auditCount(AuditActions.LOGOUT, actor = "corp:alice"))
    }

    @Test fun disablingTheProviderEndsItsSessions() {
        val browser = Http(gw.port())
        browserLogin(browser)
        assertEquals(200, browser.get("/api/v1/auth/session").status)
        val current = admin.get("${IdentityProviderApi.BASE}/corp")
        val body = current.json.filterKeys { it !in setOf("name", "clientSecretSet", "callbackPath", "version") } + ("enabled" to false)
        assertEquals(200, admin.put("${IdentityProviderApi.BASE}/corp", Json.write(body)).status)
        assertEquals(401, browser.get("/api/v1/auth/session").status)
        assertEquals(404, Http(gw.port()).get("/api/v1/auth/providers/corp/login").status)
    }

    @Test fun providerTokensAreBearerCredentials() {
        val api = Http(gw.port())
        api.authorizeBearer(idp.accessToken())
        val session = api.get("/api/v1/auth/session")
        assertEquals(200, session.status, session.body)
        assertEquals("corp:alice", session.json["principal"])
        assertEquals(listOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), session.json["roles"])
        // A bearer write needs no CSRF token.
        assertEquals(200, api.post("/api/v1/tags/read", """{"paths":["[default]nope"]}""").status)

        for ((token, why) in listOf(
            idp.accessToken(audience = "other") to "audience",
            idp.accessToken(extra = mapOf("exp" to Instant.now().epochSecond - 3600)) to "expiry",
            idp.accessToken(signWith = rsaKeyPair()) to "signature",
            signJwt(mapOf("iss" to idp.issuer, "aud" to idp.clientId, "sub" to "x", "exp" to Instant.now().epochSecond + 60), idp.keys, idp.kid, alg = "none") to "alg none",
        )) {
            val c = Http(gw.port())
            c.authorizeBearer(token)
            assertEquals(401, c.get("/api/v1/auth/session").status, why)
        }
        assertTrue(gw.auditCount(AuditActions.TOKEN_LOGIN, status = AuditStatus.FAILURE) >= 4)
    }

    @Test fun returnToMustStayOnTheGateway() {
        assertEquals("/admin/overview", IdpLogin.safeReturn("https://elsewhere.example/"))
        assertEquals("/admin/overview", IdpLogin.safeReturn("//elsewhere.example/"))
        assertEquals("/admin/overview", IdpLogin.safeReturn("/\\elsewhere"))
        assertEquals("/admin/projects", IdpLogin.safeReturn("/admin/projects"))
    }
}
