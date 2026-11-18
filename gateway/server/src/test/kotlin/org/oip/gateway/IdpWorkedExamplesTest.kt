package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.net.URI
import java.nio.file.Path

/**
 * SEC-026 and SEC-028: the worked setups in docs/ops/identity-providers.md, run end to end against in-process test
 * providers, step by step as an administrator follows them: import the provider's discovery document or metadata,
 * create the provider with its scopes, claims and role mappings, run a test login and read its result, then log in for
 * real and read the session.
 */
class IdpWorkedExamplesTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private var oidc: TestOidcProvider? = null

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        admin = Http(gw.port())
        gw.loginAdmin(admin)
    }

    @AfterEach fun tearDown() {
        oidc?.close()
        gw.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun fields(res: Http.Res) = res.json["fields"] as Map<String, Any?>

    @Test fun oidcWorkedExampleImportsDiscoveryAsksForTheScopesAndLogsIn() {
        val idp = TestOidcProvider().also { oidc = it }
        idp.user = mapOf("sub" to "u-7", "preferred_username" to "alice", "name" to "Alice Rowe", "email" to "alice@example.test", "groups" to listOf("plant-ops"))

        // Step 1: import the discovery document from the issuer.
        val imported = admin.post("${IdentityProviderApi.BASE}/import", Json.write(mapOf("type" to "oidc", "issuer" to idp.issuer)))
        assertEquals(200, imported.status, imported.body)
        // Step 2: create the provider with the client registered at the provider, the scopes, and the claim mappings.
        val created = admin.post(IdentityProviderApi.BASE, Json.write(fields(imported) + mapOf(
            "name" to "corp",
            "displayName" to "Corporate sign-in",
            "clientId" to idp.clientId,
            "clientSecret" to idp.clientSecret,
            "scopes" to listOf("openid", "profile", "email", "groups"),
            "nameClaim" to "name",
            "emailClaim" to "email",
            "rolesClaim" to "groups",
            "roleMappings" to listOf(mapOf("value" to "plant-ops", "roles" to listOf(DefaultRoles.OPERATOR))),
            "defaultRoles" to listOf(DefaultRoles.VIEWER),
        )))
        assertEquals(201, created.status, created.body)
        // Step 3: register this callback address at the provider.
        assertEquals("/api/v1/auth/providers/corp/callback", created.json["callbackPath"])
        assertEquals(true, created.json["clientSecretSet"])
        assertFalse(created.body.contains(idp.clientSecret!!))

        // Step 4: the test login. The authorization request asks for exactly the configured scopes.
        val started = admin.post("${IdentityProviderApi.BASE}/corp/test", "")
        assertEquals(201, started.status, started.body)
        val id = started.json["testId"] as String
        val browser = Http(gw.port())
        val go = browser.get(started.json["loginPath"] as String)
        assertEquals(302, go.status, go.body)
        val authorize = go.header("Location")!!
        val q = TestOidcProvider.query(URI.create(authorize).rawQuery)
        assertEquals(listOf("openid", "profile", "email", "groups"), q.getValue("scope").split(' '))
        assertTrue(q.getValue("redirect_uri").endsWith("/api/v1/auth/providers/corp/callback"))
        val page = browser.get(pathOf(redirectFrom(authorize)))
        assertEquals(200, page.status, page.body)
        assertTrue(page.body.contains("alice"), page.body)
        val result = admin.get("${IdentityProviderApi.BASE}/corp/test/$id").json
        assertEquals("success", result["status"], result.toString())
        assertEquals("alice", result["username"])
        assertEquals(listOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), result["roles"])
        assertNull(result["diagnosis"])
        assertNull(browser.sessionCookie(), "a test login issues no session")
        // The code went to the token endpoint with the same redirect address, in the form body.
        assertEquals(q["redirect_uri"], idp.tokenForms.last()["redirect_uri"])

        // Step 5: a real login.
        val user = Http(gw.port())
        val start = user.get("/api/v1/auth/providers/corp/login?returnTo=/admin/overview")
        val done = user.get(pathOf(redirectFrom(start.header("Location")!!)))
        assertEquals(302, done.status, done.body)
        assertEquals("/admin/overview", done.header("Location"))
        val session = user.get("/api/v1/auth/session").json
        assertEquals("corp:alice", session["principal"])
        assertEquals(listOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), session["roles"])
        assertEquals("Alice Rowe", session["displayName"])
        assertEquals("alice@example.test", session["email"])
        // The login page offers the provider by its display name.
        assertTrue(Http(gw.port()).get("/api/v1/auth/providers").body.contains("Corporate sign-in"))
    }

    @Test fun samlWorkedExampleImportsMetadataTestsTheAcsAndLogsIn() {
        val saml = TestSamlIdp()

        // Step 1: import the provider's metadata.
        val imported = admin.post("${IdentityProviderApi.BASE}/import", Json.write(mapOf("type" to "saml", "metadataXml" to saml.metadata())))
        assertEquals(200, imported.status, imported.body)
        val f = fields(imported)
        assertEquals(saml.entityId, f["idpEntityId"])
        assertEquals(saml.ssoUrl, f["ssoUrl"])
        // Step 2: create the provider with the gateway's entity ID and the attribute mappings.
        val created = admin.post(IdentityProviderApi.BASE, Json.write(f + mapOf(
            "name" to "plant-saml",
            "displayName" to "Plant sign-in",
            "spEntityId" to "urn:oip:gateway",
            "nameClaim" to "displayName",
            "emailClaim" to "mail",
            "rolesClaim" to "groups",
            "roleMappings" to listOf(mapOf("value" to "plant-ops", "roles" to listOf(DefaultRoles.OPERATOR))),
        )))
        assertEquals(201, created.status, created.body)
        // Step 3: register the gateway at the provider: entity ID and this assertion consumer address.
        assertEquals("/api/v1/auth/providers/plant-saml/acs", created.json["callbackPath"])
        val acs = "http://127.0.0.1:${gw.port()}/api/v1/auth/providers/plant-saml/acs"
        val attrs = mapOf("displayName" to "Bob Stone", "mail" to "bob@example.test")

        // Step 4: the test login, answered at the assertion consumer address.
        val started = admin.post("${IdentityProviderApi.BASE}/plant-saml/test", "")
        assertEquals(201, started.status, started.body)
        val id = started.json["testId"] as String
        val browser = Http(gw.port())
        val go = browser.get(started.json["loginPath"] as String)
        assertEquals(302, go.status, go.body)
        val location = go.header("Location")!!
        assertTrue(location.startsWith(saml.ssoUrl + "?SAMLRequest="), location)
        val relay = TestOidcProvider.query(URI.create(location).rawQuery).getValue("RelayState")
        val page = browser.postRaw("/api/v1/auth/providers/plant-saml/acs",
            TestSamlIdp.form("SAMLResponse" to saml.response(saml.requestId(location), acs, "urn:oip:gateway", attributes = attrs), "RelayState" to relay),
            "application/x-www-form-urlencoded")
        assertEquals(200, page.status, page.body)
        val result = admin.get("${IdentityProviderApi.BASE}/plant-saml/test/$id").json
        assertEquals("success", result["status"], result.toString())
        assertEquals("bob", result["username"])
        assertEquals(listOf(DefaultRoles.OPERATOR), result["roles"])
        assertNull(browser.sessionCookie(), "a test login issues no session")

        // Step 5: a real login.
        val user = Http(gw.port())
        val start = user.get("/api/v1/auth/providers/plant-saml/login")
        val loc = start.header("Location")!!
        val done = user.postRaw("/api/v1/auth/providers/plant-saml/acs",
            TestSamlIdp.form("SAMLResponse" to saml.response(saml.requestId(loc), acs, "urn:oip:gateway", attributes = attrs),
                "RelayState" to TestOidcProvider.query(URI.create(loc).rawQuery).getValue("RelayState")),
            "application/x-www-form-urlencoded")
        assertEquals(302, done.status, done.body)
        val session = user.get("/api/v1/auth/session").json
        assertEquals("plant-saml:bob", session["principal"])
        assertEquals(listOf(DefaultRoles.OPERATOR), session["roles"])
        assertEquals("Bob Stone", session["displayName"])
        assertEquals("bob@example.test", session["email"])
    }
}
