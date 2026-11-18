package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * GAT-030: SMTP email profiles are configured once through one API and reused by the notification service, scheduled
 * reports and gateway scripts through the same lookup. Passwords, OAuth2 client secrets and refresh tokens go to the
 * secret store and never appear in a response, a log line or a file in the data directory.
 * Doc source: FEATURES GAT-030.
 */
class EmailProfilesTest {
    @TempDir lateinit var tmp: Path
    private val clock = MutableClock(Instant.parse("2026-06-01T00:00:00Z"))
    private lateinit var gw: Gateway
    private lateinit var http: Http
    private lateinit var smtp: CapturingSmtp

    @BeforeEach fun setUp() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        smtp = CapturingSmtp()
        gw = Gateway(tmp, port = 0, clock = clock).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() {
        gw.close()
        smtp.close()
    }

    private fun profileBody(extra: String = "") =
        """{"hostname":"127.0.0.1","port":${smtp.port},"username":"mailer","password":"$PASSWORD","fromAddress":"plant@example.test"$extra}"""

    private fun assertNoSecretOnDisk(vararg secrets: String) {
        Files.walk(tmp).use { paths ->
            paths.filter { it.isRegularFile() && !lockedDatabase(it) }.forEach { file ->
                val text = Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
                for (secret in secrets) assertFalse(text.contains(secret), "$secret found in $file")
            }
        }
    }

    /** A live SQLite file holds a range lock on Windows, so a raw read is not a config scan. */
    private fun lockedDatabase(file: Path): Boolean {
        val name = file.fileName.toString()
        return name.endsWith(".sqlite") || name.endsWith(".sqlite-wal") || name.endsWith(".sqlite-shm") ||
            name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm") || name == "gateway.lock"
    }

    @Test fun aProfileIsSavedOnceAndItsPasswordGoesToTheSecretStore() {
        val created = http.put("/api/v1/email/profiles/plant", profileBody())
        assertEquals(201, created.status, created.body)
        assertEquals(true, created.json["passwordSet"])
        assertEquals("plant@example.test", created.json["fromAddress"])
        assertFalse(created.body.contains(PASSWORD))
        assertFalse(created.json.containsKey("password"))

        val listed = http.get("/api/v1/email/profiles")
        assertEquals(200, listed.status, listed.body)
        assertFalse(listed.body.contains(PASSWORD))
        assertEquals(listOf("plant"), (listed.json["items"] as List<*>).map { (it as Map<*, *>)["name"] })
        val config = http.get("/api/v1/config/alarm/gateway/SmtpProfile/plant")
        assertEquals(200, config.status, config.body)
        assertTrue(config.body.contains("secret://smtp-plant-password"))
        assertFalse(config.body.contains(PASSWORD))
        assertNoSecretOnDisk(PASSWORD)

        // An update without a password keeps the stored one; an empty string clears it.
        val kept = http.put("/api/v1/email/profiles/plant", """{"hostname":"127.0.0.1","port":${smtp.port},"username":"mailer"}""")
        assertEquals(200, kept.status, kept.body)
        assertEquals(true, kept.json["passwordSet"])
        val cleared = http.put("/api/v1/email/profiles/plant", """{"hostname":"127.0.0.1","port":${smtp.port},"password":""}""")
        assertEquals(false, cleared.json["passwordSet"])
        assertEquals(200, http.delete("/api/v1/email/profiles/plant").status)
        assertEquals(404, http.get("/api/v1/email/profiles/plant").status)
    }

    @Test fun routesAreCheckedAndInputIsRejected() {
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/email/profiles"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("PUT", "/api/v1/email/profiles/plant"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/email/profiles/plant/test"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("DELETE", "/api/v1/email/profiles/plant"))
        assertEquals(401, Http(gw.port()).get("/api/v1/email/profiles").status)
        assertEquals(201, http.put("/api/v1/email/profiles/plant", profileBody()).status)
        val op = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(200, op.get("/api/v1/email/profiles").status)
        assertFalse(op.get("/api/v1/email/profiles").body.contains(PASSWORD))
        assertEquals(403, op.put("/api/v1/email/profiles/other", profileBody()).status)
        assertEquals(403, op.post("/api/v1/email/profiles/plant/test", """{"to":"a@b.test"}""").status)
        assertEquals(403, op.delete("/api/v1/email/profiles/plant").status)
        val view = Http(gw.port())
        assertEquals(200, view.login("view", TEST_PASSWORD).status)
        assertEquals(403, view.get("/api/v1/email/profiles").status)
        assertEquals(422, http.put("/api/v1/email/profiles/Bad_Name", profileBody()).status)
        assertEquals(422, http.put("/api/v1/email/profiles/x", """{"hostname":"h","surprise":1}""").status)
        assertEquals(422, http.put("/api/v1/email/profiles/x", """{"hostname":"h","authType":"oauth2"}""").status)
        assertEquals(422, http.put("/api/v1/email/profiles/x", """{"port":25}""").status)
        assertEquals(422, http.post("/api/v1/email/profiles/plant/test", """{"to":"not-an-address"}""").status)
    }

    @Test fun theTestSendUsesPlainAuthAndTheProfileSender() {
        assertEquals(201, http.put("/api/v1/email/profiles/plant", profileBody()).status)
        val sent = http.post("/api/v1/email/profiles/plant/test", """{"to":"ops@example.test"}""")
        assertEquals(200, sent.status, sent.body)
        val mail = smtp.await().single()
        assertEquals("login", mail.auth)
        assertEquals(listOf("mailer", PASSWORD), mail.login)
        assertEquals("plant@example.test", mail.from)
        assertEquals("ops@example.test", mail.to)
        assertFalse(sent.body.contains(PASSWORD))
    }

    @Test fun aDisabledOrMissingProfileFailsWithoutLeakingAnything() {
        assertEquals(201, http.put("/api/v1/email/profiles/plant", profileBody(""","enabled":false""")).status)
        val refused = http.post("/api/v1/email/profiles/plant/test", """{"to":"ops@example.test"}""")
        assertEquals(502, refused.status, refused.body)
        assertFalse(refused.body.contains(PASSWORD))
        assertEquals(404, http.post("/api/v1/email/profiles/none/test", """{"to":"ops@example.test"}""").status)
    }

    @Test fun oauth2FetchesATokenOnceAndSendsItWithXoauth2() {
        TokenServer(token = "tok-abc-123").use { tokens ->
            val body = """{"hostname":"127.0.0.1","port":${smtp.port},"username":"mailer@example.test","authType":"oauth2",
                "oauthTokenUrl":"${tokens.url}","oauthClientId":"client-1","oauthClientSecret":"$CLIENT_SECRET",
                "oauthRefreshToken":"$REFRESH","oauthScope":"mail.send","fromAddress":"plant@example.test"}"""
            val saved = http.put("/api/v1/email/profiles/oauth", body)
            assertEquals(201, saved.status, saved.body)
            assertEquals("oauth2", saved.json["authType"])
            assertEquals(true, saved.json["clientSecretSet"])
            assertEquals(true, saved.json["refreshTokenSet"])
            assertFalse(saved.body.contains(CLIENT_SECRET) || saved.body.contains(REFRESH))

            for (i in 1..2) assertEquals(200, http.post("/api/v1/email/profiles/oauth/test", """{"to":"ops@example.test"}""").status)
            val mails = smtp.await(2)
            assertEquals(2, mails.size)
            assertTrue(mails.all { it.auth == "xoauth2" })
            assertEquals("user=mailer@example.test\u0001auth=Bearer tok-abc-123\u0001\u0001", mails.first().login.single())
            assertEquals(1, tokens.requests.size, "the token is cached until shortly before it expires")
            val request = tokens.requests.single()
            assertEquals("refresh_token", request["grant_type"])
            assertEquals(REFRESH, request["refresh_token"])
            assertEquals("client-1", request["client_id"])
            assertEquals(CLIENT_SECRET, request["client_secret"])
            assertEquals("mail.send", request["scope"])
            assertNoSecretOnDisk(CLIENT_SECRET, REFRESH, "tok-abc-123")
            assertFalse(http.get("/api/v1/email/profiles").body.let { it.contains(CLIENT_SECRET) || it.contains("tok-abc-123") })

            // A token past its lifetime is fetched again.
            clock.advance(Duration.ofSeconds(3_700))
            assertEquals(200, http.post("/api/v1/email/profiles/oauth/test", """{"to":"ops@example.test"}""").status)
            assertEquals(2, tokens.requests.size)
        }
    }

    @Test fun oauth2UsesClientCredentialsWithoutARefreshTokenAndRefusesAPlainHttpEndpoint() {
        TokenServer(token = "cc-token").use { tokens ->
            val body = """{"hostname":"127.0.0.1","port":${smtp.port},"username":"mailer@example.test","authType":"oauth2",
                "oauthTokenUrl":"${tokens.url}","oauthClientId":"client-1","oauthClientSecret":"$CLIENT_SECRET"}"""
            assertEquals(201, http.put("/api/v1/email/profiles/cc", body).status)
            assertEquals(200, http.post("/api/v1/email/profiles/cc/test", """{"to":"ops@example.test"}""").status)
            assertEquals("client_credentials", tokens.requests.single()["grant_type"])
        }
        val remote = """{"hostname":"127.0.0.1","port":${smtp.port},"username":"m@example.test","authType":"oauth2",
            "oauthTokenUrl":"http://tokens.example.test/token","oauthClientId":"c","oauthClientSecret":"$CLIENT_SECRET"}"""
        assertEquals(201, http.put("/api/v1/email/profiles/remote", remote).status)
        val refused = http.post("/api/v1/email/profiles/remote/test", """{"to":"ops@example.test"}""")
        assertEquals(502, refused.status, refused.body)
        assertTrue(refused.body.contains("must be https"), refused.body)
        assertFalse(refused.body.contains(CLIENT_SECRET))
    }

    @Test fun aRefusedTokenRequestNamesTheProfileAndNoSecret() {
        TokenServer(status = 401).use { tokens ->
            val body = """{"hostname":"127.0.0.1","port":${smtp.port},"username":"m@example.test","authType":"oauth2",
                "oauthTokenUrl":"${tokens.url}","oauthClientId":"c","oauthClientSecret":"$CLIENT_SECRET","oauthRefreshToken":"$REFRESH"}"""
            assertEquals(201, http.put("/api/v1/email/profiles/bad", body).status)
            val refused = http.post("/api/v1/email/profiles/bad/test", """{"to":"ops@example.test"}""")
            assertEquals(502, refused.status)
            assertTrue(refused.body.contains("refused (HTTP 401)"), refused.body)
            assertFalse(refused.body.contains(CLIENT_SECRET) || refused.body.contains(REFRESH))
            assertEquals(0, smtp.mails.size)
        }
    }

    @Test fun gatewayScriptsSendThroughTheSameProfile() {
        assertEquals(201, http.put("/api/v1/email/profiles/plant", profileBody()).status)
        val run = http.post("/api/v1/designer/console", Json.write(mapOf(
            "source" to "system.net.sendEmail('plant', 'ops@example.test', 'Pump alert', 'Pump 3 tripped')",
        )))
        assertEquals(200, run.status, run.body)
        assertEquals(true, run.json["ok"], run.body)
        val mail = smtp.await().single()
        assertEquals("login", mail.auth)
        assertEquals("plant@example.test", mail.from)
        assertEquals("ops@example.test", mail.to)
        assertTrue(mail.data.contains("Subject: Pump alert"))
        assertTrue(mail.data.contains("Pump 3 tripped"))

        val missing = http.post("/api/v1/designer/console", Json.write(mapOf("source" to "system.net.sendEmail('nope', 'ops@example.test', 's', 'b')")))
        assertEquals(false, missing.json["ok"], missing.body)
        assertFalse(missing.body.contains(PASSWORD))
    }

    @Test fun scheduledReportsEmailThePdfThroughTheSameProfile() {
        assertEquals(201, http.put("/api/v1/email/profiles/plant", profileBody()).status)
        gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("speed", "tags"),
            mapOf("path" to "Plant/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0)))
        val spec = """
            {"title": "Speed", "parameters": [{"name": "range", "type": "timeRange", "default": {"last": "1h"}}],
             "dataSources": [{"name": "speed", "type": "tagHistory", "mode": "raw", "tags": [{"path": "Plant/Speed", "label": "Speed"}], "range": {"param": "range"}}],
             "schedule": {"cron": "* * * * *", "actions": [{"type": "email", "to": "boss@example.test", "subject": "Speed report", "smtpProfile": "plant"}]},
             "layout": {"layoutVersion": 1, "components": [{"type": "table", "dataset": "speed"}]}}
        """.trimIndent()
        val put = http.put("/api/v1/config/reporting/gateway/Report/speed",
            """{"apiVersion":"oip/v1","kind":"Report","metadata":{"name":"speed","module":"reporting","version":0},"spec":$spec}""")
        assertEquals(201, put.status, put.body)
        val deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos()
        while (smtp.mails.isEmpty() && System.nanoTime() < deadline) {
            clock.advance(Duration.ofSeconds(30))
            Thread.sleep(250)
        }
        val mail = smtp.mails.firstOrNull() ?: error("no report mail arrived; runs: " + http.get("/api/v1/reports/speed/runs").body)
        assertEquals("login", mail.auth)
        assertEquals("plant@example.test", mail.from)
        assertEquals("boss@example.test", mail.to)
        assertTrue(mail.data.contains("Subject: Speed report"), mail.data)
        assertTrue(mail.data.contains("application/pdf"), mail.data)
        val encoded = mail.data.substringAfter("filename=\"speed.pdf\"").trim().lines().takeWhile { !it.startsWith("--") }.joinToString("")
        assertTrue(String(Base64.getMimeDecoder().decode(encoded)).startsWith("%PDF"))
    }

    @Test fun aReportThatNamesAProfileCannotAlsoCarryItsOwnServerOrLogin() {
        val spec = """
            {"title": "Speed", "dataSources": [], "schedule": {"cron": "* * * * *",
             "actions": [{"type": "email", "to": "boss@example.test", "smtpProfile": "plant", "host": "smtp.example.test"}]},
             "layout": {"layoutVersion": 1, "components": []}}
        """.trimIndent()
        val put = http.put("/api/v1/config/reporting/gateway/Report/speed",
            """{"apiVersion":"oip/v1","kind":"Report","metadata":{"name":"speed","module":"reporting","version":0},"spec":$spec}""")
        assertEquals(422, put.status, put.body)
    }

    private companion object {
        const val PASSWORD = "Mail-pass-4471-xq"
        const val CLIENT_SECRET = "client-secret-9921-zz"
        const val REFRESH = "refresh-token-5532-yy"
    }
}
