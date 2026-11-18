package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.notify.NOTIFY_MODULE
import org.oip.alarm.notify.NotifyKinds
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.security.CreateUser
import org.oip.security.DefaultRoles
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.readText

/**
 * Notification config is gateway configuration: operators may read it, only administrators may change it.
 * SMTP passwords are `secret://` references and do not appear in the resource or the log.
 * The link in the email acknowledges the alarm without a gateway session.
 */
class NotificationHttpTest {
    @TempDir lateinit var tmp: Path

    @Test fun rolesAndSecretReference() {
        val gw = Gateway(tmp.resolve("roles"), port = 0).start()
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            gw.createUser("op", setOf(DefaultRoles.OPERATOR))
            gw.createUser("view", setOf(DefaultRoles.VIEWER))
            val anon = Http(gw.port())
            assertProblem(anon.get("/api/v1/notifications/schedules/always"), 401, "unauthorized")
            val view = client(gw, "view")
            assertProblem(view.get("/api/v1/notifications/schedules/always"), 403, "forbidden")
            val op = client(gw, "op")
            val schedule = op.get("/api/v1/notifications/schedules/always")
            assertEquals(200, schedule.status, schedule.body)
            assertEquals(true, schedule.json["active"])
            val pipelines = op.get("/api/v1/notifications/pipelines")
            assertEquals(200, pipelines.status)
            assertEquals(emptyList<Any?>(), pipelines.json["events"])
            assertProblem(op.put(smtpPath("plant"), doc(NotifyKinds.SMTP, "plant", """{"hostname":"127.0.0.1"}""")), 403, "forbidden")
            val admin = client(gw, "admin")
            assertProblem(
                admin.put(smtpPath("plant"), doc(NotifyKinds.SMTP, "plant", """{"hostname":"127.0.0.1","password":"plain-text-secret"}""")),
                422, "validation",
            )
            gw.security.secrets.putText("smtp-pass", SECRET)
            val created = admin.put(
                smtpPath("plant"),
                doc(NotifyKinds.SMTP, "plant", """{"hostname":"127.0.0.1","port":2525,"username":"mailer","password":"secret://smtp-pass","debug":true}"""),
            )
            assertEquals(201, created.status, created.body)
            val spec = created.json["spec"] as Map<*, *>
            assertEquals("secret://smtp-pass", spec["password"])
            assertFalse(created.body.contains(SECRET))
            val yaml = Files.walk(tmp.resolve("roles").resolve("config")).filter { it.toString().endsWith(".yaml") }.toList()
                .joinToString("\n") { it.readText() }
            assertTrue(yaml.contains("secret://smtp-pass"), yaml)
            assertFalse(yaml.contains(SECRET), yaml)
            val onCall = op.get("/api/v1/notifications/rosters/operators/on-call")
            assertEquals(200, onCall.status, onCall.body)
            assertTrue((onCall.json["users"] as List<*>).isEmpty())
        } finally {
            gw.close()
        }
    }

    @Test fun emailLinkAcknowledgesAndNotesAreRequired() {
        val body = AtomicReference<String>()
        ServerSocket(0).use { server ->
            val accepted = Thread { serve(server.accept(), body) }
            accepted.start()
            val gw = Gateway(tmp.resolve("ack"), port = 0).start()
            try {
                gw.security.secrets.putText("smtp-pass", SECRET)
                gw.security.users.create(
                    CreateUser("ada", TEST_PASSWORD.toCharArray(), setOf(DefaultRoles.OPERATOR), email = "directory@example.com"),
                    actor = "test",
                )
                gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
                put(gw, NotifyKinds.SMTP, "plant", mapOf(
                    "hostname" to "127.0.0.1", "port" to server.localPort, "username" to "mailer",
                    "password" to "secret://smtp-pass", "debug" to true,
                ))
                put(gw, NotifyKinds.EMAIL, "mail", mapOf(
                    "useSmtpProfile" to true, "smtpProfile" to "plant", "twoWayEnabled" to true,
                    "gatewayAddress" to "127.0.0.1:${gw.port()}", "sendHttpsLink" to false,
                    "fromAddress" to "alarms@example.com",
                ))
                put(gw, NotifyKinds.ROSTER, "operators", mapOf("users" to listOf("ada")))
                put(gw, NotifyKinds.CONTACT, "ada", mapOf(
                    "contacts" to listOf(mapOf("type" to "email", "value" to "ada@example.com")),
                ))
                put(gw, NotifyKinds.PIPELINE, "active", mapOf(
                    "blocks" to listOf(
                        mapOf("id" to "start", "type" to "start", "next" to "send"),
                        mapOf("id" to "send", "type" to "notification", "profile" to "mail", "roster" to "operators", "next" to "end"),
                        mapOf("id" to "end", "type" to "end"),
                    ),
                ))
                gw.store.put(Scope.GATEWAY, tag("tank/level", notes = true), actor = "test")
                gw.tags.write("[default]tank/level", 150)
                accepted.join(5_000)
                val text = body.get() ?: error(
                    "smtp server captured nothing alarms=${gw.alarms.query()} log=${tmp.resolve("ack").resolve("logs").resolve("gateway.jsonl").readText()}",
                )
                assertTrue(text.contains("To: <ada@example.com>"), text)
                assertFalse(text.contains("directory@example.com"), text)
                assertFalse(text.contains(SECRET), text)
                val token = Regex("/api/v1/alarms/email-ack/([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)
                    ?: error("no acknowledgement link in $text")
                val anon = Http(gw.port())
                val page = anon.send("GET", "/api/v1/alarms/email-ack/$token", headers = mapOf("Accept" to "text/html"))
                assertEquals(200, page.status, page.body)
                assertTrue(page.body.contains("High"))
                assertTrue(page.body.contains("Tank &lt;script&gt;"), page.body)
                val missing = anon.post("/api/v1/alarms/email-ack/$token", """{"notes":""}""")
                assertProblem(missing, 422, "validation")
                val acked = anon.post("/api/v1/alarms/email-ack/$token", """{"notes":"from the email"}""")
                assertEquals(200, acked.status, acked.body)
                assertEquals(true, acked.json["acknowledged"])
                val op = client(gw, "ada")
                val event = (op.get("/api/v1/alarms").json["alarms"] as List<*>).single() as Map<*, *>
                assertEquals("Active and Acknowledged", event["state"])
                assertEquals("ada", event["ackUser"])
                val log = tmp.resolve("ack").resolve("logs").resolve("gateway.jsonl").readText()
                assertFalse(log.contains(SECRET), log)
                assertFalse(log.contains(token), log)
                assertFalse(log.contains(Base64.getEncoder().encodeToString(SECRET.toByteArray(StandardCharsets.UTF_8))), log)
            } finally {
                gw.close()
            }
        }
    }

    private fun put(gw: Gateway, kind: String, name: String, spec: Map<String, Any?>) {
        gw.store.put(Scope.GATEWAY, Resource(kind, Metadata(name, NOTIFY_MODULE), spec), actor = "test")
    }

    private fun tag(path: String, notes: Boolean) = Resource(
        TAG_KIND,
        Metadata(path.substringAfterLast('/'), TAGS_MODULE),
        mapOf(
            "path" to path,
            "type" to "memory",
            "dataType" to "Double",
            "value" to 0,
            "alarms" to listOf(mapOf(
                "name" to "High",
                "mode" to "AboveValue",
                "priority" to "High",
                "setpointA" to 100,
                "ackNotesReqd" to notes,
                "displayPath" to "Tank <script>",
                "activePipeline" to "active",
            )),
        ),
    )

    private fun client(gw: Gateway, user: String): Http {
        val http = Http(gw.port())
        assertEquals(200, http.login(user, TEST_PASSWORD).status)
        return http
    }

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
        assertEquals("urn:oip:problem:$slug", r.json["type"])
    }

    private fun smtpPath(name: String) = "/api/v1/config/$NOTIFY_MODULE/gateway/${NotifyKinds.SMTP}/$name"

    private fun doc(kind: String, name: String, spec: String) =
        """{"apiVersion":"oip/v1","kind":"$kind","metadata":{"name":"$name","module":"$NOTIFY_MODULE","version":0},"spec":$spec}"""

    private fun serve(socket: Socket, body: AtomicReference<String>) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
            fun say(line: String) {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
            }
            say("220 ready")
            reader.readLine()
            say("250 AUTH LOGIN")
            reader.readLine()
            say("334 VXNlcm5hbWU6")
            reader.readLine()
            say("334 UGFzc3dvcmQ6")
            reader.readLine()
            say("235 ok")
            reader.readLine()
            say("250 ok")
            reader.readLine()
            say("250 ok")
            reader.readLine()
            say("354 go")
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line == ".") break
                data.append(line).append('\n')
            }
            body.set(data.toString())
            say("250 queued")
            reader.readLine()
            say("221 bye")
        }
    }

    companion object {
        private const val SECRET = "s3cret-mail-1"
    }
}
