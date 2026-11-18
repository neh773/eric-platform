package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class P5BatchTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun aPythonResourceAnswersGetAndPost() {
        val source = """
            def doGet(request):
                return request['path'] + '-get'
            def doPost(request):
                return {'status': 201, 'contentType': 'text/plain; charset=utf-8', 'body': request['body']}
        """.trimIndent()
        val saved = admin.post("/api/v1/config/webdev/gateway/WebResource", resource("webdev", "WebResource", "echo", mapOf(
            "path" to "echo",
            "type" to "python",
            "source" to source,
        )))
        assertEquals(201, saved.status, saved.body)
        val anon = Http(gw.port())
        val get = anon.get("/system/webdev/gateway/echo")
        assertEquals(200, get.status, get.body)
        assertEquals("echo-get", get.body)
        val post = anon.post("/system/webdev/gateway/echo", "\"posted\"")
        assertEquals(201, post.status, post.body)
        assertEquals("\"posted\"", post.body)
        assertEquals(405, anon.send("PUT", "/system/webdev/gateway/echo", "{}".toByteArray()).status)
    }

    @Test fun aListenerStreamPostsAGatewayMessageHandler() {
        val tag = admin.post("/api/v1/config/tags/gateway/Tag", resource("tags", "Tag", "heard", mapOf(
            "path" to "Heard",
            "type" to "memory",
            "dataType" to "Int32",
            "value" to 0,
        )))
        assertEquals(201, tag.status, tag.body)
        val handler = admin.post("/api/v1/config/scripting/gateway/GatewayEvent", resource("scripting", "GatewayEvent", "heard", mapOf(
            "type" to "message",
            "source" to "system.tag.writeBlocking('[default]Heard', payload['n'])\n",
        )))
        assertEquals(201, handler.status, handler.body)
        val stream = admin.post("/api/v1/config/events/gateway/EventStream", resource("events", "EventStream", "ear", mapOf(
            "source" to mapOf("type" to "listener"),
            "handlers" to listOf(mapOf("type" to "gateway", "path" to "heard", "body" to "{event.data}")),
        )))
        assertEquals(201, stream.status, stream.body)
        val posted = admin.post("/api/v1/event-streams/ear/listen", """{"n":9}""")
        assertEquals(202, posted.status, posted.body)
        val value = admin.post("/api/v1/tags/read", """{"paths":["[default]Heard"]}""")
        assertEquals(200, value.status, value.body)
        val rows = value.json["values"] as List<Map<*, *>>
        assertEquals(9, (rows.first()["value"] as Number).toInt())
        assertTrue(admin.get("/api/v1/event-streams/ear").json["sourceConnected"] == true)
    }

    private fun resource(module: String, kind: String, name: String, spec: Map<String, Any?>): String = Json.write(mapOf(
        "apiVersion" to "oip/v1",
        "kind" to kind,
        "metadata" to mapOf("name" to name, "module" to module),
        "spec" to spec,
    ))
}
