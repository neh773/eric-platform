package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * SEC-032: a failed identity provider test login says what is most likely wrong. Clock skew (an expired token or
 * assertion), a redirect mismatch (the provider refused the redirect address, or answered for another ACS address, or
 * never sent the browser back) and bad metadata (a signature from a key the settings do not hold) each get their own
 * diagnosis with the facts the check compared, in the admin API result and on the result page. No secret is a fact.
 */
class IdpDiagnosticsTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private var idp: TestOidcProvider? = null

    private fun start(clock: java.time.Clock = java.time.Clock.systemUTC()) {
        gw = Gateway(tmp, port = 0, clock = clock).start()
        admin = Http(gw.port())
        gw.loginAdmin(admin)
    }

    @AfterEach fun tearDown() {
        idp?.close()
        if (::gw.isInitialized) gw.close()
    }

    private fun createOidc(name: String = "corp", secret: String? = null): TestOidcProvider {
        val p = idp ?: TestOidcProvider().also { idp = it }
        val created = admin.post(IdentityProviderApi.BASE, Json.write(mapOf(
            "name" to name, "type" to "oidc", "issuer" to p.issuer,
            "authorizationEndpoint" to "${p.issuer}/authorize", "tokenEndpoint" to "${p.issuer}/token", "jwksUri" to "${p.issuer}/jwks",
            "clientId" to p.clientId, "clientSecret" to (secret ?: p.clientSecret), "clockSkewSeconds" to 30,
            "rolesClaim" to "groups", "roleMappings" to listOf(mapOf("value" to "plant-ops", "roles" to listOf(DefaultRoles.OPERATOR))),
        )))
        assertEquals(201, created.status, created.body)
        return p
    }

    private class Test1(val id: String, val browser: Http, val location: String)

    private fun startTest(provider: String): Test1 {
        val started = admin.post("${IdentityProviderApi.BASE}/$provider/test", "")
        assertEquals(201, started.status, started.body)
        val browser = Http(gw.port())
        val go = browser.get(started.json["loginPath"] as String)
        assertEquals(302, go.status, go.body)
        return Test1(started.json["testId"] as String, browser, go.header("Location")!!)
    }

    @Suppress("UNCHECKED_CAST")
    private fun diagnosis(provider: String, id: String): Map<String, Any?> {
        val result = admin.get("${IdentityProviderApi.BASE}/$provider/test/$id").json
        assertEquals("failure", result["status"], result.toString())
        return result["diagnosis"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun facts(d: Map<String, Any?>) = d["facts"] as Map<String, Any?>

    @Test fun anExpiredTokenIsDiagnosedAsClockSkewWithBothTimes() {
        start()
        val p = createOidc()
        p.tamper = { it["exp"] = Instant.now().epochSecond - 3600 }
        val t = startTest("corp")
        val page = t.browser.get(pathOf(redirectFrom(t.location)))
        assertEquals(200, page.status, page.body)
        assertTrue(page.body.contains("Diagnosis") && page.body.contains("NTP"), page.body)
        val d = diagnosis("corp", t.id)
        assertEquals("clock-skew", d["code"])
        val f = facts(d)
        assertEquals(30, f["allowedSkewSeconds"])
        val diff = (f["differenceSeconds"] as Number).toLong()
        assertTrue(diff in 3590..3610, "difference $diff")
        assertNotNull(f["providerTime"])
        assertNotNull(f["gatewayTime"])
        assertTrue(f.getValue("gatewayRedirectUri").toString().endsWith("/api/v1/auth/providers/corp/callback"), f.toString())
    }

    @Test fun aRedirectTheProviderRefusedIsDiagnosedWithTheAddressToRegister() {
        start()
        createOidc()
        val t = startTest("corp")
        val q = TestOidcProvider.query(URI.create(t.location).rawQuery)
        val redirectUri = q.getValue("redirect_uri")
        // The provider's answer to an unregistered redirect address, sent back to it.
        val back = "${pathOf(redirectUri)}?error=invalid_request&error_description=redirect_uri+is+not+registered&state=${IdpHttp.enc(q.getValue("state"))}"
        assertEquals(200, t.browser.get(back).status)
        val d = diagnosis("corp", t.id)
        assertEquals("redirect-mismatch", d["code"])
        assertTrue((d["hint"] as String).contains(redirectUri), d["hint"].toString())
        assertEquals(redirectUri, facts(d)["gatewayRedirectUri"])
        assertEquals("invalid_request", facts(d)["providerError"])
        // A provider error code is shown only when it is one: anything else is dropped.
        val t2 = startTest("corp")
        val q2 = TestOidcProvider.query(URI.create(t2.location).rawQuery)
        t2.browser.get("${pathOf(redirectUri)}?error=%3Cscript%3E&state=${IdpHttp.enc(q2.getValue("state"))}")
        val d2 = diagnosis("corp", t2.id)
        assertEquals("provider-error", d2["code"])
        assertFalse(facts(d2).containsKey("providerError"))
    }

    @Test fun aWrongClientSecretIsDiagnosedAndNeverShown() {
        start()
        createOidc("wrong", secret = "not-the-secret-9")
        val t = startTest("wrong")
        t.browser.get(pathOf(redirectFrom(t.location)))
        val result = admin.get("${IdentityProviderApi.BASE}/wrong/test/${t.id}")
        assertFalse(result.body.contains("not-the-secret-9"))
        val d = diagnosis("wrong", t.id)
        assertEquals("client-credentials", d["code"])
        assertEquals(401, facts(d)["httpStatus"])
        assertEquals("invalid_client", facts(d)["providerError"])
    }

    @Test fun aSignatureFromAnotherKeyIsDiagnosedAsMetadata() {
        start()
        val p = createOidc()
        p.signingKeys = rsaKeyPair()
        val t = startTest("corp")
        t.browser.get(pathOf(redirectFrom(t.location)))
        assertEquals("metadata", diagnosis("corp", t.id)["code"])
        // An issuer the settings do not hold is a metadata problem too, with both values shown.
        p.signingKeys = null
        p.tamper = { it["iss"] = "http://127.0.0.1:1/moved" }
        val t2 = startTest("corp")
        t2.browser.get(pathOf(redirectFrom(t2.location)))
        val d = diagnosis("corp", t2.id)
        assertEquals("metadata", d["code"])
        assertEquals(p.issuer, facts(d)["expected"])
        assertEquals("http://127.0.0.1:1/moved", facts(d)["presented"])
    }

    @Test fun aTestWhoseBrowserNeverCameBackSaysSoAfterAMinute() {
        val clock = MutableClock(Instant.now())
        start(clock)
        createOidc()
        val t = startTest("corp")
        val url = "${IdentityProviderApi.BASE}/corp/test/${t.id}"
        assertNull(admin.get(url).json["diagnosis"], "no diagnosis while the provider may still answer")
        clock.advance(Duration.ofSeconds(61))
        @Suppress("UNCHECKED_CAST")
        val d = admin.get(url).json["diagnosis"] as Map<String, Any?>
        assertEquals("redirect-mismatch", d["code"])
        assertEquals(61L, (facts(d)["waitedSeconds"] as Number).toLong())
    }

    // SAML

    private class SamlTest(val id: String, val browser: Http, val requestId: String, val relay: String, val acs: String)

    private fun createSaml(saml: TestSamlIdp) {
        val imported = admin.post("${IdentityProviderApi.BASE}/import", Json.write(mapOf("type" to "saml", "metadataXml" to saml.metadata())))
        assertEquals(200, imported.status, imported.body)
        @Suppress("UNCHECKED_CAST")
        val f = imported.json["fields"] as Map<String, Any?>
        val created = admin.post(IdentityProviderApi.BASE, Json.write(f + mapOf("name" to "plant-saml", "spEntityId" to "urn:oip:gateway")))
        assertEquals(201, created.status, created.body)
    }

    private fun startSaml(saml: TestSamlIdp): SamlTest {
        val t = startTest("plant-saml")
        val q = TestOidcProvider.query(URI.create(t.location).rawQuery)
        return SamlTest(t.id, t.browser, saml.requestId(t.location), q.getValue("RelayState"), "http://127.0.0.1:${gw.port()}/api/v1/auth/providers/plant-saml/acs")
    }

    private fun post(s: SamlTest, response: String) = s.browser.postRaw(
        "/api/v1/auth/providers/plant-saml/acs", TestSamlIdp.form("SAMLResponse" to response, "RelayState" to s.relay), "application/x-www-form-urlencoded",
    )

    @Test fun samlFailuresAreDiagnosedAsClockSkewRedirectMismatchMetadataAndAudience() {
        start()
        val saml = TestSamlIdp()
        createSaml(saml)
        // Clock skew: the assertion's validity ended an hour ago.
        var s = startSaml(saml)
        assertEquals(200, post(s, saml.response(s.requestId, s.acs, "urn:oip:gateway", notOnOrAfter = Instant.now().minusSeconds(3600))).status)
        var d = diagnosis("plant-saml", s.id)
        assertEquals("clock-skew", d["code"])
        assertTrue((facts(d)["differenceSeconds"] as Number).toLong() in 3590..3610)
        // Redirect mismatch: the provider sent the assertion for another ACS address.
        s = startSaml(saml)
        post(s, saml.response(s.requestId, "https://old-host.test.invalid/acs", "urn:oip:gateway"))
        d = diagnosis("plant-saml", s.id)
        assertEquals("redirect-mismatch", d["code"])
        assertEquals(s.acs, facts(d)["expected"])
        assertEquals("https://old-host.test.invalid/acs", facts(d)["presented"])
        assertTrue((d["hint"] as String).contains(s.acs))
        // Bad metadata: the provider signs with a key its imported metadata does not hold.
        s = startSaml(saml)
        post(s, saml.response(s.requestId, s.acs, "urn:oip:gateway", signer = rsaKeyPair()))
        assertEquals("metadata", diagnosis("plant-saml", s.id)["code"])
        // Audience: the assertion is for another SP entity ID.
        s = startSaml(saml)
        post(s, saml.response(s.requestId, s.acs, "urn:someone-else"))
        d = diagnosis("plant-saml", s.id)
        assertEquals("audience", d["code"])
        assertEquals("urn:oip:gateway", facts(d)["expected"])
        assertEquals("urn:someone-else", facts(d)["presented"])
    }

    @Test fun providerValuesAreCutToPrintableTextAndOnlyErrorCodesAreKept() {
        assertEquals("abc", IdpDiagnostics.shown("a\u0000b\nc"))
        assertEquals(200, IdpDiagnostics.shown("x".repeat(5000))!!.length)
        assertNull(IdpDiagnostics.shown(null))
        assertEquals("redirect_uri_mismatch", IdpDiagnostics.errorCode("redirect_uri_mismatch"))
        assertNull(IdpDiagnostics.errorCode("bad code with spaces"))
        assertNull(IdpDiagnostics.errorCode(42))
        val skew = IdpDiagnostics.timeFacts(1_000_000L, 1_120_000L)
        assertEquals(120L, skew["differenceSeconds"])
    }
}
