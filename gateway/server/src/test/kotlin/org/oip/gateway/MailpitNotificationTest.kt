package org.oip.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * A pinned Mailpit container receives the notification and the link in that message acknowledges the alarm.
 * Image pin matches test-infra: axllent/mailpit v1.32.0.
 */
class MailpitNotificationTest {
    @TempDir lateinit var tmp: Path

    @Test fun mailpitDeliversTheAckLink() {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(), "Docker is not available; this test needs a container")
        val image = DockerImageName.parse(IMAGE)
        val mailpit = GenericContainer(image)
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/livez").forPort(8025).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))
        mailpit.start()
        try {
                val smtp = mailpit.getMappedPort(1025)
                val http = "http://${mailpit.host}:${mailpit.getMappedPort(8025)}"
                val gw = Gateway(tmp.resolve("gw"), port = 0).start()
                try {
                    gw.security.users.create(
                        CreateUser("ada", TEST_PASSWORD.toCharArray(), setOf(DefaultRoles.OPERATOR), email = "ada@example.com"),
                        actor = "test",
                    )
                    gw.store.put(Scope.GATEWAY, Resource(NotifyKinds.SMTP, Metadata("plant", NOTIFY_MODULE), mapOf(
                        "hostname" to mailpit.host, "port" to smtp,
                    )), actor = "test")
                    gw.store.put(Scope.GATEWAY, Resource(NotifyKinds.EMAIL, Metadata("mail", NOTIFY_MODULE), mapOf(
                        "useSmtpProfile" to true,
                        "smtpProfile" to "plant",
                        "twoWayEnabled" to true,
                        "gatewayAddress" to "127.0.0.1:${gw.port()}",
                        "sendHttpsLink" to false,
                        "fromAddress" to "alarms@example.com",
                    )), actor = "test")
                    gw.store.put(Scope.GATEWAY, Resource(NotifyKinds.ROSTER, Metadata("operators", NOTIFY_MODULE), mapOf(
                        "users" to listOf("ada"),
                    )), actor = "test")
                    gw.store.put(Scope.GATEWAY, Resource(NotifyKinds.PIPELINE, Metadata("active", NOTIFY_MODULE), mapOf(
                        "blocks" to listOf(
                            mapOf("id" to "start", "type" to "start", "next" to "send"),
                            mapOf("id" to "send", "type" to "notification", "profile" to "mail", "roster" to "operators"),
                            mapOf("id" to "end", "type" to "end"),
                        ),
                    )), actor = "test")
                    gw.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("level", TAGS_MODULE), mapOf(
                        "path" to "tank/level",
                        "type" to "memory",
                        "dataType" to "Double",
                        "value" to 0,
                        "alarms" to listOf(mapOf(
                            "name" to "High",
                            "mode" to "AboveValue",
                            "priority" to "High",
                            "setpointA" to 100,
                            "activePipeline" to "active",
                        )),
                    )), actor = "test")
                    gw.tags.write("[default]tank/level", 150)
                    val (text, to) = awaitBody(http)
                    assertEquals(listOf("ada@example.com"), to)
                    val token = Regex("/api/v1/alarms/email-ack/([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)
                        ?: error("no acknowledgement link in $text")
                    val anon = Http(gw.port())
                    val acked = anon.post("/api/v1/alarms/email-ack/$token", """{"notes":"mailpit"}""")
                    assertEquals(200, acked.status, acked.body)
                    val session = Http(gw.port())
                    assertEquals(200, session.login("ada", TEST_PASSWORD).status)
                    val event = (session.get("/api/v1/alarms").json["alarms"] as List<*>).single() as Map<*, *>
                    assertEquals("Active and Acknowledged", event["state"])
                    assertEquals("ada", event["ackUser"])
                } finally {
                    gw.close()
                }
        } finally {
            mailpit.stop()
        }
    }

    private fun awaitBody(httpBase: String): Pair<String, List<String>> {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val mapper = ObjectMapper()
        val deadline = System.nanoTime() + 10_000_000_000L
        var last = ""
        while (System.nanoTime() < deadline) {
            val listed = client.send(
                HttpRequest.newBuilder(URI.create("$httpBase/api/v1/messages")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            last = listed.body()
            val messages = mapper.readTree(last).path("messages")
            val id = if (messages.isArray && messages.size() > 0) messages[0].path("ID").asText() else ""
            if (id.isNotEmpty()) {
                val one = client.send(
                    HttpRequest.newBuilder(URI.create("$httpBase/api/v1/message/$id")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                val node = mapper.readTree(one.body())
                val text = node.path("Text").asText()
                val to = node.path("To").map { it.path("Address").asText() }
                if (text.isNotBlank()) return text to to
            }
            Thread.sleep(100)
        }
        error("mailpit had no message body: $last")
    }

    companion object {
        private const val IMAGE =
            "axllent/mailpit:v1.32.0@sha256:0790692be935ea4420d085694f7985b1f692ca6a8771ab0e0bd8ba75ac180498"
    }
}
