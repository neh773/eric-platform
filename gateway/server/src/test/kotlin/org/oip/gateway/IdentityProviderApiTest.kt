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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * P1-33 (SEC-014): the identity provider registry. OIDC discovery and SAML metadata import fill the resource; invalid
 * metadata is 422; the client secret is only in the secret store, never in the config tree or a response; SAML login
 * against a test IdP sets a session.
 *
 * P1-34 (SEC-027): the user source check. Existing and missing user; right and wrong password; roles listed; needs
 * users.admin; audited; the password is never recorded.
 */
class IdentityProviderApiTest {
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

    @Test fun discoveryImportFillsTheResourceAndTheSecretStaysInTheSecretStore() {
        val idp = TestOidcProvider().also { oidc = it }
        val imported = admin.post("${IdentityProviderApi.BASE}/import", Json.write(mapOf("type" to "oidc", "issuer" to idp.issuer)))
        assertEquals(200, imported.status, imported.body)
        val f = fields(imported)
        assertEquals(idp.issuer, f["issuer"])
        assertEquals("${idp.issuer}/authorize", f["authorizationEndpoint"])
        assertEquals("${idp.issuer}/token", f["tokenEndpoint"])
        assertEquals("${idp.issuer}/jwks", f["jwksUri"])
        assertEquals("${idp.issuer}/logout", f["endSessionEndpoint"])
        assertEquals(listOf("RS256"), f["allowedAlgorithms"])

        val secret = "s3cr3t-value-never-shown"
        val created = admin.post(IdentityProviderApi.BASE, Json.write(f + mapOf("name" to "corp", "clientId" to "oip", "clientSecret" to secret)))
        assertEquals(201, created.status, created.body)
        assertEquals(true, created.json["clientSecretSet"])
        assertEquals("/api/v1/auth/providers/corp/callback", created.json["callbackPath"])
        assertFalse(created.body.contains(secret))
        assertFalse(created.json.containsKey("clientSecretRef"))

        val read = admin.get("${IdentityProviderApi.BASE}/corp")
        assertEquals(200, read.status)
        assertEquals("${idp.issuer}/token", read.json["tokenEndpoint"])
        assertFalse(read.body.contains(secret))
        val list = admin.get(IdentityProviderApi.BASE)
        assertFalse(list.body.contains(secret))
        // The generic config API shows the resource without the secret reference.
        val generic = admin.get("/api/v1/config/org.oip.security/gateway/IdentityProvider/corp")
        assertEquals(200, generic.status, generic.body)
        assertFalse(generic.body.contains(secret))
        assertFalse(generic.body.contains("clientSecretRef"))
        // Nothing under the config tree holds the plaintext; the secret store does.
        Files.walk(tmp.resolve("config")).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { assertFalse(it.readText().contains(secret), "$it holds the secret") }
        }
        assertEquals(secret, gw.security.secrets.getText("idp-corp"))
        // A secret written straight into the resource is refused by its schema.
        val spec = Json.write(mapOf("apiVersion" to "oip/v1", "kind" to "IdentityProvider",
            "metadata" to mapOf("name" to "raw", "module" to "org.oip.security", "version" to 0),
            "spec" to mapOf("type" to "oidc", "clientSecret" to secret)))
        assertEquals(403, admin.put("/api/v1/config/org.oip.security/gateway/IdentityProvider/raw", spec).status)
        assertEquals(422, admin.post(IdentityProviderApi.BASE, Json.write(f + mapOf("name" to "ref", "clientId" to "x", "clientSecretRef" to "secret://idp-corp"))).status)

        // Replace without clientSecret keeps it; an empty clientSecret removes it from the store.
        val body = read.json.filterKeys { it !in setOf("name", "clientSecretSet", "callbackPath", "version") }
        val kept = admin.put("${IdentityProviderApi.BASE}/corp", Json.write(body + ("displayName" to "Corp")))
        assertEquals(200, kept.status, kept.body)
        assertEquals(true, kept.json["clientSecretSet"])
        val cleared = admin.put("${IdentityProviderApi.BASE}/corp", Json.write(body + ("clientSecret" to "")))
        assertEquals(false, cleared.json["clientSecretSet"])
        assertFalse(gw.security.secrets.list().any { it.name == "idp-corp" })
        // Delete.
        assertEquals(204, admin.delete("${IdentityProviderApi.BASE}/corp").status)
        assertEquals(404, admin.get("${IdentityProviderApi.BASE}/corp").status)
    }

    @Test fun invalidMetadataIs422() {
        val idp = TestOidcProvider().also { oidc = it }
        fun import(body: Map<String, Any?>) = admin.post("${IdentityProviderApi.BASE}/import", Json.write(body))
        // Issuer mismatch, a missing endpoint, an http endpoint off loopback, no S256: all 422.
        idp.discovery = { it + ("issuer" to "http://127.0.0.1:1/other") }
        assertEquals(422, import(mapOf("type" to "oidc", "issuer" to idp.issuer)).status)
        idp.discovery = { it - "jwks_uri" }
        assertEquals(422, import(mapOf("type" to "oidc", "issuer" to idp.issuer)).status)
        idp.discovery = { it + ("token_endpoint" to "http://idp.example.invalid/token") }
        assertEquals(422, import(mapOf("type" to "oidc", "issuer" to idp.issuer)).status)
        idp.discovery = { it + ("code_challenge_methods_supported" to listOf("plain")) }
        assertEquals(422, import(mapOf("type" to "oidc", "issuer" to idp.issuer)).status)
        // A path that is not a discovery document.
        assertEquals(422, import(mapOf("type" to "oidc", "issuer" to "${idp.issuer}/nowhere")).status)
        // SAML: not XML, a DTD (refused before parsing entities), no IdP descriptor, no certificate, a broken certificate.
        assertEquals(422, import(mapOf("type" to "saml", "metadataXml" to "not xml")).status)
        val dtd = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="&e;"/>"""
        assertEquals(422, import(mapOf("type" to "saml", "metadataXml" to dtd)).status)
        assertEquals(422, import(mapOf("type" to "saml", "metadataXml" to """<md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="x"/>""")).status)
        val saml = TestSamlIdp()
        assertEquals(422, import(mapOf("type" to "saml", "metadataXml" to saml.metadata().replace(Regex("<md:KeyDescriptor.*?</md:KeyDescriptor>", RegexOption.DOT_MATCHES_ALL), ""))).status)
        assertEquals(422, import(mapOf("type" to "saml", "metadataXml" to saml.metadata().replace(Regex("<ds:X509Certificate>[^<]+"), "<ds:X509Certificate>AAAA"))).status)
        assertEquals(422, import(mapOf("type" to "saml", "metadataUrl" to "${idp.issuer}/metadata.xml")).status)
        // An incomplete or unsafe provider resource is 422 too.
        assertEquals(422, admin.post(IdentityProviderApi.BASE, Json.write(mapOf("name" to "half", "type" to "oidc", "issuer" to idp.issuer))).status)
        val unsafe = admin.post(IdentityProviderApi.BASE, Json.write(mapOf("name" to "plain", "type" to "oidc", "issuer" to "http://idp.example.invalid",
            "authorizationEndpoint" to "http://idp.example.invalid/a", "tokenEndpoint" to "http://idp.example.invalid/t",
            "jwksUri" to "http://idp.example.invalid/k", "clientId" to "c")))
        assertEquals(422, unsafe.status, unsafe.body)
        val badRole = admin.post(IdentityProviderApi.BASE, Json.write(mapOf("name" to "roles", "type" to "oidc", "issuer" to idp.issuer,
            "authorizationEndpoint" to "${idp.issuer}/a", "tokenEndpoint" to "${idp.issuer}/t", "jwksUri" to "${idp.issuer}/k",
            "clientId" to "c", "defaultRoles" to listOf("no-such-role"))))
        assertEquals(422, badRole.status)
        assertEquals(0, gw.security.identityProviders.list().size)
    }

    private fun createSaml(saml: TestSamlIdp, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val imported = admin.post("${IdentityProviderApi.BASE}/import", Json.write(mapOf("type" to "saml", "metadataXml" to saml.metadata())))
        assertEquals(200, imported.status, imported.body)
        val f = fields(imported)
        assertEquals(saml.entityId, f["idpEntityId"])
        assertEquals(saml.ssoUrl, f["ssoUrl"])
        assertEquals("https://idp.test.invalid/slo", f["sloUrl"])
        @Suppress("UNCHECKED_CAST")
        assertTrue((f["signingCertificates"] as List<String>).single().startsWith("-----BEGIN CERTIFICATE-----"))
        val created = admin.post(IdentityProviderApi.BASE, Json.write(f + mapOf(
            "name" to "plant-saml",
            "spEntityId" to "urn:oip:gateway",
            "rolesClaim" to "groups",
            "roleMappings" to listOf(mapOf("value" to "plant-ops", "roles" to listOf(DefaultRoles.OPERATOR))),
        ) + extra))
        assertEquals(201, created.status, created.body)
        assertEquals("/api/v1/auth/providers/plant-saml/acs", created.json["callbackPath"])
        return created.json
    }

    private class SamlStart(val browser: Http, val requestId: String, val relayState: String, val acs: String)

    private fun startSaml(saml: TestSamlIdp): SamlStart {
        val browser = Http(gw.port())
        val start = browser.get("/api/v1/auth/providers/plant-saml/login")
        assertEquals(302, start.status, start.body)
        val location = start.header("Location")!!
        assertTrue(location.startsWith(saml.ssoUrl + "?SAMLRequest="), location)
        val q = TestOidcProvider.query(URI.create(location).rawQuery)
        return SamlStart(browser, saml.requestId(location), q.getValue("RelayState"), "http://127.0.0.1:${gw.port()}/api/v1/auth/providers/plant-saml/acs")
    }

    private fun post(s: SamlStart, response: String, relay: String = s.relayState) =
        s.browser.postRaw("/api/v1/auth/providers/plant-saml/acs", TestSamlIdp.form("SAMLResponse" to response, "RelayState" to relay), "application/x-www-form-urlencoded")

    @Test fun samlLoginAgainstATestIdpSetsASession() {
        val saml = TestSamlIdp()
        createSaml(saml)
        val s = startSaml(saml)
        val done = post(s, saml.response(s.requestId, s.acs, "urn:oip:gateway"))
        assertEquals(302, done.status, done.body)
        assertEquals("/admin/overview", done.header("Location"))
        val session = s.browser.get("/api/v1/auth/session")
        assertEquals(200, session.status, session.body)
        assertEquals("plant-saml:bob", session.json["principal"])
        assertEquals(listOf(DefaultRoles.OPERATOR), session.json["roles"])
        val rows = gw.security.audit.query(AuditQuery(action = AuditActions.LOGIN, actor = "plant-saml:bob", status = AuditStatus.SUCCESS, limit = 5)).records
        assertEquals(1, rows.size)
        assertTrue(rows[0].detail.contains("provider=plant-saml"))
        // The same response cannot be replayed: the request it answers is used up.
        val replay = startSaml(saml)
        val again = post(replay, saml.response(s.requestId, s.acs, "urn:oip:gateway"))
        assertEquals("/admin/login?error=idp", again.header("Location"))
    }

    /** SEC-015. Doc source: FEATURES SEC-015. */
    @Test fun samlAttributesMapOntoNameEmailAndRolesPerProvider() {
        val saml = TestSamlIdp()
        createSaml(saml, mapOf("nameClaim" to "displayName", "emailClaim" to "mail"))
        val s = startSaml(saml)
        val attrs = mapOf("displayName" to "Bob Stone", "mail" to "bob@example.test", "email" to "other@example.test")
        assertEquals(302, post(s, saml.response(s.requestId, s.acs, "urn:oip:gateway", attributes = attrs)).status)
        val session = s.browser.get("/api/v1/auth/session").json
        assertEquals("Bob Stone", session["displayName"])
        assertEquals("bob@example.test", session["email"])
        assertEquals(listOf(DefaultRoles.OPERATOR), session["roles"])
    }

    @Test fun samlWithNoMappedNameOrEmailMapsNothing() {
        val saml = TestSamlIdp()
        createSaml(saml)
        val s = startSaml(saml)
        val attrs = mapOf("displayName" to "Bob Stone", "email" to "bob@example.test")
        assertEquals(302, post(s, saml.response(s.requestId, s.acs, "urn:oip:gateway", attributes = attrs)).status)
        val session = s.browser.get("/api/v1/auth/session").json
        assertFalse(session.containsKey("displayName"))
        assertFalse(session.containsKey("email"))
    }

    @Test fun samlResponsesThatFailACheckAreRefused() {
        val saml = TestSamlIdp()
        createSaml(saml)
        val cases = listOf<Pair<String, (SamlStart) -> String>>(
            "unsigned" to { s -> saml.response(s.requestId, s.acs, "urn:oip:gateway", sign = false) },
            "other key" to { s -> saml.response(s.requestId, s.acs, "urn:oip:gateway", signer = rsaKeyPair()) },
            "tampered NameID" to { s ->
                saml.response(s.requestId, s.acs, "urn:oip:gateway") { a ->
                    a.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "NameID").item(0).textContent = "admin"
                }
            },
            "audience" to { s -> saml.response(s.requestId, s.acs, "urn:someone-else") },
            "issuer" to { s -> saml.response(s.requestId, s.acs, "urn:oip:gateway", issuer = "urn:other") },
            "expired" to { s -> saml.response(s.requestId, s.acs, "urn:oip:gateway", notOnOrAfter = Instant.now().minusSeconds(3600)) },
            "other request" to { s -> saml.response("_other", s.acs, "urn:oip:gateway") },
            "recipient" to { s -> saml.response(s.requestId, "http://127.0.0.1:1/acs", "urn:oip:gateway") },
        )
        for ((why, build) in cases) {
            val s = startSaml(saml)
            val res = post(s, build(s))
            assertEquals(302, res.status, why)
            assertEquals("/admin/login?error=idp", res.header("Location"), why)
            assertEquals(401, s.browser.get("/api/v1/auth/session").status, why)
        }
        val failures = gw.security.audit.query(AuditQuery(action = AuditActions.LOGIN_ATTEMPT, status = AuditStatus.FAILURE, limit = 50)).records
        assertEquals(cases.size, failures.count { it.detail.contains("provider=plant-saml") })
        // A forged RelayState never matches a pending request.
        val s = startSaml(saml)
        assertEquals("/admin/login?error=idp", post(s, saml.response(s.requestId, s.acs, "urn:oip:gateway"), relay = "forged").header("Location"))
    }

    @Test fun providerAdministrationNeedsUsersAdmin() {
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        val op = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(403, op.get(IdentityProviderApi.BASE).status)
        assertEquals(403, op.post(IdentityProviderApi.BASE, """{"name":"x","type":"oidc"}""").status)
        assertEquals(403, op.post("${IdentityProviderApi.BASE}/import", """{"type":"saml","metadataXml":"x"}""").status)
        assertEquals(403, op.get("/api/v1/config/org.oip.security/gateway/IdentityProvider/x").status)
        assertEquals(401, Http(gw.port()).get(IdentityProviderApi.BASE).status)
    }

    @Test fun userSourceCheckReportsExistenceRolesAndPassword() {
        gw.createUser("carol", setOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER))
        fun check(body: Map<String, Any?>, client: Http = admin) =
            client.post("/api/v1/security/user-sources/internal/check", Json.write(body))
        val exists = check(mapOf("username" to "carol"))
        assertEquals(200, exists.status, exists.body)
        assertEquals(true, exists.json["exists"])
        assertEquals(listOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), exists.json["roles"])
        assertEquals(false, exists.json["passwordChecked"])
        assertNull(exists.json["passwordValid"])

        val right = check(mapOf("username" to "carol", "password" to TEST_PASSWORD))
        assertEquals(true, right.json["passwordValid"])
        val wrong = check(mapOf("username" to "carol", "password" to "Wrong-horse-9"))
        assertEquals(false, wrong.json["passwordValid"])
        val missing = check(mapOf("username" to "nobody", "password" to TEST_PASSWORD))
        assertEquals(200, missing.status)
        assertEquals(false, missing.json["exists"])
        assertEquals(emptyList<String>(), missing.json["roles"])
        assertEquals(false, missing.json["passwordValid"])
        assertEquals(404, admin.post("/api/v1/security/user-sources/ldap/check", """{"username":"carol"}""").status)

        // Audited, never the password; a check is not a login.
        val rows = gw.security.audit.query(AuditQuery(action = AuditActions.USER_SOURCE_CHECK, limit = 20)).records
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.actor == "admin" })
        assertTrue(rows.any { it.target == "nobody" && it.detail.contains("exists=false") })
        assertTrue(rows.any { it.target == "carol" && it.detail.contains("password=valid") })
        assertTrue(rows.any { it.target == "carol" && it.detail.contains("password=invalid") })
        val everything = gw.security.audit.query(AuditQuery(limit = 500)).records
        assertTrue(everything.none { it.detail.contains(TEST_PASSWORD) || it.detail.contains("Wrong-horse-9") })
        assertEquals(0L, gw.auditCount(AuditActions.LOGIN, actor = "carol"))
        assertEquals(0L, gw.auditCount(AuditActions.LOGIN_ATTEMPT, actor = "carol"))
        Files.walk(tmp.resolve("logs")).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { assertFalse(it.readText().contains("Wrong-horse-9"), "$it logs the password") }
        }

        // users.admin only.
        val op = Http(gw.port())
        assertEquals(200, op.login("carol", TEST_PASSWORD).status)
        assertEquals(403, check(mapOf("username" to "carol"), op).status)
        assertEquals(401, check(mapOf("username" to "carol"), Http(gw.port())).status)
    }
}
