package org.oip.gateway

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.DefaultRoles
import org.oip.security.OAuth2Exception
import org.oip.security.OAuth2TokenService
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * SEC-029: OAuth2 client registrations hold what an outbound service needs to obtain tokens (client id, a client
 * secret kept in the secret store, token and authorization endpoints, scopes, grant type). The gateway fetches,
 * caches and refreshes the tokens, a rotated refresh token replaces the stored one, the authorization code flow
 * stores a refresh token so the client works unattended, and an SMTP profile that names a registration sends with
 * its token over XOAUTH2. No secret or token appears in a response, a log or a file in the data directory.
 * Doc source: FEATURES SEC-029.
 */
class OAuth2ClientsTest {
    @TempDir lateinit var tmp: Path
    private val clock = MutableClock(Instant.parse("2026-06-01T00:00:00Z"))
    private lateinit var gw: Gateway
    private lateinit var http: Http
    private lateinit var idp: FakeAuthorizationServer
    private val base = OAuth2ClientApi.BASE

    @BeforeEach fun setUp() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0, clock = clock).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        http = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
        idp = FakeAuthorizationServer()
    }

    @AfterEach fun tearDown() {
        gw.close()
        idp.close()
    }

    private fun register(name: String = "mail", extra: String = ""): Http.Res {
        val r = http.put("$base/$name", """{"clientId":"client-1","clientSecret":"$CLIENT_SECRET","tokenUrl":"${idp.tokenUrl}",
            "authorizationUrl":"${idp.authorizeUrl}","scope":"mail.send"$extra}""")
        assertTrue(r.status == 201 || r.status == 200, r.body)
        return r
    }

    private fun assertNoSecretOnDisk(vararg secrets: String) {
        Files.walk(tmp).use { paths ->
            paths.filter { it.isRegularFile() && !lockedDatabase(it) }.forEach { file ->
                val text = Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
                for (secret in secrets) assertFalse(text.contains(secret), "secret found in $file")
            }
        }
    }

    @Test fun aRegistrationKeepsItsSecretInTheSecretStoreAndIsWrittenOnlyThroughItsApi() {
        val saved = register(extra = ""","refreshToken":"$REFRESH"""")
        assertEquals(201, saved.status)
        assertEquals(true, saved.json["clientSecretSet"])
        assertEquals(true, saved.json["refreshTokenSet"])
        assertEquals("client_credentials", saved.json["grantType"])
        assertEquals("HTTP_2", saved.json["httpVersion"])
        assertEquals(30_000, (saved.json["connectTimeoutMillis"] as Number).toInt())
        assertEquals(120_000, (saved.json["requestTimeoutMillis"] as Number).toInt())
        assertTrue((saved.json["redirectUri"] as String).endsWith("$base/mail/authorize/callback"))
        assertFalse(saved.body.contains(CLIENT_SECRET) || saved.body.contains(REFRESH))
        assertFalse(http.get(base).body.contains(CLIENT_SECRET))
        assertEquals(CLIENT_SECRET, gw.security.secrets.getText("oauth2-mail-client-secret"))
        assertNoSecretOnDisk(CLIENT_SECRET, REFRESH)

        // The generic config API cannot write it and does not return its secret references.
        val generic = http.get("/api/v1/config/org.oip.security/gateway/OAuth2Client/mail")
        assertEquals(200, generic.status, generic.body)
        assertFalse((generic.json["spec"] as Map<*, *>).containsKey("clientSecret"))
        val write = http.put("/api/v1/config/org.oip.security/gateway/OAuth2Client/mail", generic.body)
        assertEquals(403, write.status, write.body)

        // A missing secret field keeps the stored one; an empty one clears it.
        val updated = http.put("$base/mail", """{"description":"mail sender","refreshToken":""}""")
        assertEquals(200, updated.status, updated.body)
        assertEquals(true, updated.json["clientSecretSet"])
        assertEquals(false, updated.json["refreshTokenSet"])
        assertEquals("mail sender", updated.json["description"])
        assertTrue(gw.security.secrets.list().none { it.name == "oauth2-mail-refresh-token" })

        assertEquals(422, http.put("$base/mail", """{"grantType":"password"}""").status)
        assertEquals(422, http.put("$base/mail", """{"tokenUrl":"http://tokens.example.test/token"}""").status)
        assertEquals(422, http.put("$base/mail", """{"unknown":1}""").status)
        assertEquals(422, http.put("$base/mail", """{"clientSecretRef":"secret://does-not-exist"}""").status)

        assertEquals(204, http.delete("$base/mail").status)
        assertTrue(gw.security.secrets.list().none { it.name.startsWith("oauth2-mail-") })
        assertEquals(404, http.get("$base/mail").status)
        assertEquals(1, gw.security.audit.query(AuditQuery(action = AuditActions.OAUTH2_CLIENT_DELETED)).records.size)
    }

    @Test fun clientCredentialsTokensAreCachedUntilShortlyBeforeTheyExpire() {
        register()
        val first = gw.oauth2Clients.accessToken("mail")
        assertEquals(first, gw.oauth2Clients.accessToken("mail"))
        assertEquals(1, idp.requests.size)
        val request = idp.requests.single()
        assertEquals("client_credentials", request["grant_type"])
        assertEquals("client-1", request["client_id"])
        assertEquals(CLIENT_SECRET, request["client_secret"])
        assertEquals("mail.send", request["scope"])
        assertNotNull(http.get("$base/mail").json["tokenCachedUntil"])

        // Another scope is its own token.
        gw.oauth2Clients.accessToken("mail", "other.scope")
        assertEquals("other.scope", idp.requests.last()["scope"])

        clock.advance(Duration.ofSeconds(3_600 - 20))
        val second = gw.oauth2Clients.accessToken("mail")
        assertTrue(first != second, "a token 20 s from expiry is fetched again")
        // A change to the registration drops its cached tokens.
        val count = idp.requests.size
        assertEquals(200, http.put("$base/mail", """{"scope":"mail.send offline"}""").status)
        gw.oauth2Clients.accessToken("mail")
        assertEquals(count + 1, idp.requests.size)
    }

    @Test fun aRefreshTokenIsUsedAndARotatedOneReplacesTheStoredOne() {
        idp.rotate = true
        register(extra = ""","grantType":"refresh_token","refreshToken":"$REFRESH"""")
        gw.oauth2Clients.accessToken("mail")
        assertEquals("refresh_token", idp.requests.last()["grant_type"])
        assertEquals(REFRESH, idp.requests.last()["refresh_token"])
        val rotated = gw.security.secrets.getText("oauth2-mail-refresh-token")
        assertTrue(rotated.startsWith("rotated-"), "the rotated refresh token is stored")

        clock.advance(Duration.ofHours(2))
        gw.oauth2Clients.accessToken("mail")
        assertEquals(rotated, idp.requests.last()["refresh_token"])
        assertNoSecretOnDisk(CLIENT_SECRET, REFRESH, rotated)

        // Without a refresh token a refresh_token registration cannot get a token.
        assertEquals(200, http.put("$base/mail", """{"refreshToken":""}""").status)
        val failure = assertThrows<OAuth2Exception> { gw.oauth2Clients.accessToken("mail") }
        assertTrue(failure.message!!.contains("no refresh token"))
    }

    @Test fun anSmtpProfileThatNamesARegistrationSendsItsTokenWithXoauth2() {
        CapturingSmtp().use { smtp ->
            register()
            val missing = http.put("/api/v1/email/profiles/plant", """{"hostname":"127.0.0.1","port":${smtp.port},"authType":"oauth2","oauthClient":"nope"}""")
            assertEquals(422, missing.status, missing.body)
            val saved = http.put("/api/v1/email/profiles/plant", """{"hostname":"127.0.0.1","port":${smtp.port},"username":"mailer@example.test",
                "authType":"oauth2","oauthClient":"mail","fromAddress":"plant@example.test"}""")
            assertEquals(201, saved.status, saved.body)
            assertEquals("mail", saved.json["oauthClient"])
            for (i in 1..2) assertEquals(200, http.post("/api/v1/email/profiles/plant/test", """{"to":"ops@example.test"}""").status)
            val mails = smtp.await(2)
            assertTrue(mails.all { it.auth == "xoauth2" })
            assertEquals("user=mailer@example.test\u0001auth=Bearer ${idp.lastToken}\u0001\u0001", mails.first().login.single())
            assertEquals(1, idp.requests.size, "both mails use the cached token")
            assertEquals(listOf("plant"), http.get("$base/mail").json["usedBy"])
            val inUse = http.delete("$base/mail")
            assertEquals(409, inUse.status, inUse.body)
            assertNoSecretOnDisk(CLIENT_SECRET, idp.lastToken)
        }
    }

    @Test fun verifyTokenReportsTheAnswerWithoutTheToken() {
        register()
        val ok = http.post("$base/mail/verify-token", """{"grantType":"client_credentials","scope":"a b"}""")
        assertEquals(200, ok.status, ok.body)
        assertEquals("Bearer", ok.json["tokenType"])
        assertEquals(3600, (ok.json["expiresInSeconds"] as Number).toInt())
        assertEquals(false, ok.json["refreshTokenReceived"])
        assertFalse(ok.body.contains(idp.lastToken))
        assertEquals("a b", idp.requests.last()["scope"])

        val code = http.post("$base/mail/verify-token", """{"grantType":"authorization_code","code":"c-1","redirectUri":"https://gw.example.test/cb"}""")
        assertEquals(200, code.status, code.body)
        assertEquals(true, code.json["refreshTokenReceived"])
        assertEquals("c-1", idp.requests.last()["code"])
        assertEquals("https://gw.example.test/cb", idp.requests.last()["redirect_uri"])
        assertFalse(code.body.contains("refresh-from-code"))

        idp.refuse = true
        val refused = http.post("$base/mail/verify-token", """{"grantType":"client_credentials"}""")
        assertEquals(502, refused.status, refused.body)
        assertTrue(refused.body.contains("invalid_client"))
        assertFalse(refused.body.contains(CLIENT_SECRET))
        assertEquals(422, http.post("$base/mail/verify-token", """{"grantType":"refresh_token"}""").status)
        assertEquals(422, http.post("$base/mail/verify-token", """{"grantType":"client_credentials","parameters":{"client_secret":"x"}}""").status)
    }

    @Test fun theAuthorizationCodeFlowStoresARefreshTokenForUnattendedUse() {
        register(extra = ""","grantType":"refresh_token"""")
        val started = http.post("$base/mail/authorize", """{"parameters":{"access_type":"offline","prompt":"consent"}}""")
        assertEquals(200, started.status, started.body)
        val url = URI(started.json["authorizationUrl"] as String)
        assertTrue(url.toString().startsWith(idp.authorizeUrl))
        val query = url.rawQuery.split('&').associate { val (k, v) = it.split('=', limit = 2); k to URLDecoder.decode(v, StandardCharsets.UTF_8) }
        assertEquals("code", query["response_type"])
        assertEquals("client-1", query["client_id"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("offline", query["access_type"])
        assertEquals("mail.send", query["scope"])
        assertEquals(started.json["redirectUri"], query["redirect_uri"])
        val state = query["state"]!!

        // The provider redirects the browser back; the session cookie does not come along (SameSite=Strict).
        val browser = Http(gw.port())
        assertEquals(400, browser.get("$base/mail/authorize/callback?state=forged&code=x").status)
        val back = browser.get("$base/mail/authorize/callback?state=$state&code=the-code")
        assertEquals(200, back.status, back.body)
        assertTrue(back.body.contains("is authorized"))
        val exchange = idp.requests.last()
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("the-code", exchange["code"])
        assertEquals(query["redirect_uri"], exchange["redirect_uri"])
        assertEquals(query["code_challenge"], OAuth2TokenService.challenge(exchange["code_verifier"]!!), "PKCE verifier matches the challenge")
        assertEquals(400, browser.get("$base/mail/authorize/callback?state=$state&code=the-code").status, "a state is used once")

        assertEquals(true, http.get("$base/mail").json["refreshTokenSet"])
        gw.oauth2Clients.accessToken("mail")
        assertEquals("refresh_token", idp.requests.last()["grant_type"])
        assertEquals("refresh-from-code", idp.requests.last()["refresh_token"])
        val audit = gw.security.audit.query(AuditQuery(action = AuditActions.OAUTH2_CLIENT_AUTHORIZED)).records.single()
        assertEquals("admin", audit.actor)
        assertNoSecretOnDisk(CLIENT_SECRET, "refresh-from-code")

        // A registration without an authorization URL has no authorization flow.
        assertEquals(201, http.put("$base/svc", """{"clientId":"c","tokenUrl":"${idp.tokenUrl}"}""").status)
        assertEquals(422, http.post("$base/svc/authorize", "{}").status)
        assertNull(idp.requests.find { it["client_id"] == "c" })
    }

    @Test fun readsNeedGatewayReadAndChangesNeedGatewayWrite() {
        register()
        // An operator holds gateway.read but not gateway.write.
        val viewer = Http(gw.port()).also { assertEquals(200, it.login("op", TEST_PASSWORD).status) }
        assertEquals(200, viewer.get(base).status)
        assertEquals(403, viewer.put("$base/mail", "{}").status)
        assertEquals(403, viewer.delete("$base/mail").status)
        assertEquals(403, viewer.post("$base/mail/verify-token", "{}").status)
        assertEquals(403, viewer.post("$base/mail/authorize", "{}").status)
        assertEquals(401, Http(gw.port()).get(base).status)
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "$base/mail"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("PUT", "$base/mail"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "$base/mail/verify-token"))
        assertEquals("public", GatewayAuth.explicitRule("GET", "$base/mail/authorize/callback"))
    }

    /** A live SQLite file holds a range lock on Windows, so a raw read is not a config scan. */
    private fun lockedDatabase(file: Path): Boolean {
        val name = file.fileName.toString()
        return name.endsWith(".sqlite") || name.endsWith(".sqlite-wal") || name.endsWith(".sqlite-shm") ||
            name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm") || name == "gateway.lock"
    }

    companion object {
        const val CLIENT_SECRET = "oauth-client-secret-7781-qq"
        const val REFRESH = "oauth-refresh-6612-ww"
    }
}

/** A token and authorization endpoint for tests: answers each grant, numbers its tokens, and can rotate or refuse. */
class FakeAuthorizationServer : AutoCloseable {
    private val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    private val issued = AtomicInteger()
    val requests = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile var rotate = false
    @Volatile var refuse = false
    @Volatile var lastToken = ""
    val tokenUrl: String get() = "http://127.0.0.1:${http.address.port}/token"
    val authorizeUrl: String get() = "http://127.0.0.1:${http.address.port}/authorize"

    init {
        http.createContext("/token") { exchange ->
            val form = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8).split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
            requests += form
            val n = issued.incrementAndGet()
            val token = "issued-token-$n"
            val refresh = when {
                form["grant_type"] == "authorization_code" -> "refresh-from-code"
                form["grant_type"] == "refresh_token" && rotate -> "rotated-$n"
                else -> null
            }
            val reply = if (refuse) {
                """{"error":"invalid_client","error_description":"bad client ${form["client_secret"]}"}"""
            } else {
                lastToken = token
                """{"access_token":"$token","token_type":"Bearer","expires_in":3600${refresh?.let { ",\"refresh_token\":\"$it\"" } ?: ""}}"""
            }
            val bytes = reply.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (refuse) 401 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
    }

    override fun close() = http.stop(0)
}
