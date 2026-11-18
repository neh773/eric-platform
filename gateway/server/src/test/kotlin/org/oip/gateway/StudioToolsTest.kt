package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class StudioToolsTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private lateinit var viewer: Http

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
        viewer = Http(gw.port()).also { assertEquals(200, it.login("view", TEST_PASSWORD).status) }
        assertEquals(201, admin.post("/api/v1/projects", """{"name":"plant","title":"Plant"}""").status)
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun scriptConsoleReturnsPrintAndRefusesAViewer() {
        // One designer console (DES-008): the old /api/v1/scripts/console duplicate is gone.
        assertEquals("gateway.write+designer.login", GatewayAuth.explicitRule("POST", "/api/v1/designer/console"))
        assertEquals(404, admin.post("/api/v1/scripts/console", """{"source":"print('no')"}""").status)
        val refused = viewer.post("/api/v1/designer/console", """{"source":"print('no')"}""")
        assertEquals(403, refused.status)
        val ran = admin.post("/api/v1/designer/console", """{"source":"print('hello-console')"}""")
        assertEquals(200, ran.status, ran.body)
        assertTrue(ran.json["output"].toString().contains("hello-console"))
        assertEquals(true, ran.json["ok"])
    }

    @Test fun imagesRoundTripAndAScriptInSvgIsRefused() {
        assertEquals("tags.read", GatewayAuth.explicitRule("GET", "/api/v1/hmi/projects/plant/images/mark.png"))
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val saved = admin.post("/api/v1/projects/plant/images", """{"name":"mark.png","data":"$png"}""")
        assertEquals(201, saved.status, saved.body)
        val listed = admin.get("/api/v1/projects/plant/images")
        val items = listed.json["items"] as List<Map<String, Any?>>
        assertEquals("mark.png", items.first()["name"])
        val bytes = viewer.get("/api/v1/hmi/projects/plant/images/mark.png")
        assertEquals(200, bytes.status)
        assertEquals("image/png", bytes.header("content-type"))
        val svg = java.util.Base64.getEncoder().encodeToString("<svg><script>1</script></svg>".toByteArray())
        assertEquals(422, admin.post("/api/v1/projects/plant/images", """{"name":"bad.svg","data":"$svg"}""").status)
        // SEC-003: saving and deleting a project image are project changes, audited.
        assertEquals(1L, gw.auditCount(org.oip.security.AuditActions.PROJECT_IMAGE_SAVED, actor = "admin"))
        assertEquals(204, admin.delete("/api/v1/projects/plant/images/mark.png").status)
        assertEquals(1L, gw.auditCount(org.oip.security.AuditActions.PROJECT_IMAGE_DELETED, actor = "admin"))
    }

    @Test fun aSessionPresenceShowsOnTheLiveList() {
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/hmi/sessions"))
        assertEquals(403, viewer.get("/api/v1/hmi/sessions").status)
        val socket = connect(admin)
        socket.send("type" to "hello", "nonce" to "n1")
        socket.next { it["type"] == "welcome" }
        socket.send("type" to "presence", "project" to "plant", "page" to "/home", "views" to listOf("home", "dock"))
        val deadline = System.nanoTime() + 5_000_000_000L
        var seen = false
        while (System.nanoTime() < deadline) {
            val rows = admin.get("/api/v1/hmi/sessions").json["sessions"] as List<Map<String, Any?>>
            seen = rows.any { it["project"] == "plant" && it["page"] == "/home" && (it["views"] as List<*>).contains("dock") && it["connected"] == true }
            if (seen) break
            Thread.sleep(20)
        }
        assertTrue(seen)
        socket.close()
    }

    private class Ws(val socket: WebSocket, val frames: LinkedBlockingQueue<Map<String, Any?>>) {
        fun send(vararg entries: Pair<String, Any?>) {
            socket.sendText(Json.write(mapOf(*entries)), true).get(5, TimeUnit.SECONDS)
        }
        fun next(match: (Map<String, Any?>) -> Boolean): Map<String, Any?> {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (true) {
                val frame = frames.poll(200, TimeUnit.MILLISECONDS) ?: if (System.nanoTime() > deadline) error("no frame") else continue
                if (match(frame)) return frame
            }
        }
        fun close() {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
        }
    }

    private fun connect(http: Http): Ws {
        val frames = LinkedBlockingQueue<Map<String, Any?>>()
        val builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        http.sessionCookie()?.let { builder.header("Cookie", "${HttpAuth.COOKIE}=$it") }
        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    frames.add(Json.parse(buffer.toString()))
                    buffer.setLength(0)
                }
                webSocket.request(1)
                return null
            }
            override fun onError(webSocket: WebSocket, error: Throwable) {}
        }
        val socket = builder.buildAsync(URI.create("ws://127.0.0.1:${gw.port()}${SessionHost.PATH}"), listener).get(10, TimeUnit.SECONDS)
        socket.request(1)
        return Ws(socket, frames)
    }
}
