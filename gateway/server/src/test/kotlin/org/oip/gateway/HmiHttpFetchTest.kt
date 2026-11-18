package org.oip.gateway

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles

/**
 * HMP-114 (doc source: FEATURES HMP-114): an HTTP binding reads a REST endpoint through the gateway. The upstream
 * address and credential headers stay in the project's `HttpSource`; a session sends only a relative path.
 */
class HmiHttpFetchTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private lateinit var viewer: Http
    private lateinit var upstream: HttpServer
    private val seenAuth = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val seenPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val route = "/api/v1/hmi/projects/plant/http/weather"

    @BeforeEach fun setUp() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun reply(ex: com.sun.net.httpserver.HttpExchange, status: Int, type: String, text: String) {
            seenAuth += ex.requestHeaders.getFirst("X-Api-Key") ?: ""
            seenPaths += ex.requestURI.toString()
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        upstream.createContext("/v1/temp") { reply(it, 200, "application/json", """{"temp":21.5,"city":"Oslo"}""") }
        upstream.createContext("/v1/text") { reply(it, 200, "text/plain", "not json") }
        upstream.createContext("/v1/broken") { reply(it, 503, "application/json", """{"detail":"secret upstream detail"}""") }
        upstream.createContext("/v1/big") { reply(it, 200, "application/json", "[" + "1,".repeat(200) + "1]") }
        upstream.start()
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
        viewer = Http(gw.port()).also { assertEquals(200, it.login("view", TEST_PASSWORD).status) }
        val view = """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"home","module":"designer","version":0},
            "spec":{"path":"home","title":"Home","document":{"params":{},"root":{"id":"root","kind":"container","layout":"coordinate","children":[]}}}}"""
        assertEquals(201, admin.post("/api/v1/config/designer/project/plant/View", view).status)
        gw.security.secrets.putText("weather-key", "s3cret-token", "weather API key")
        putSource("weather", """{"baseUrl":"http://127.0.0.1:${upstream.address.port}/v1","headers":{"X-Api-Key":"secret://weather-key","Accept-Language":"en"},"maxBytes":100}""")
    }

    @AfterEach fun tearDown() { gw.close(); upstream.stop(0) }

    private fun putSource(name: String, spec: String) {
        val doc = """{"apiVersion":"oip/v1","kind":"HttpSource","metadata":{"name":"$name","module":"designer","version":0},"spec":$spec}"""
        val r = admin.put("/api/v1/config/designer/project/plant/HttpSource/$name", doc)
        assertTrue(r.status == 200 || r.status == 201, r.body)
    }

    @Test fun aViewerGetsParsedJsonAndNeverTheCredentialOrAddress() {
        val r = viewer.post(route, """{"path":"/temp","query":{"unit":"c"}}""")
        assertEquals(200, r.status, r.body)
        assertEquals(true, r.json["ok"])
        assertEquals(mapOf("temp" to 21.5, "city" to "Oslo"), r.json["body"])
        assertEquals(listOf("s3cret-token"), seenAuth)
        assertEquals(listOf("/v1/temp?unit=c"), seenPaths)
        assertFalse(r.body.contains("s3cret-token"))
        assertFalse(r.body.contains("127.0.0.1"))
    }

    @Test fun upstreamFailuresBecomeAShortErrorWithoutUpstreamDetail() {
        val notJson = viewer.post(route, """{"path":"/text"}""")
        assertEquals(false, notJson.json["ok"]); assertEquals("response is not JSON", notJson.json["error"])
        val broken = viewer.post(route, """{"path":"/broken"}""")
        assertEquals(false, broken.json["ok"]); assertEquals(503, broken.json["status"])
        assertFalse(broken.body.contains("secret upstream detail"))
        val big = viewer.post(route, """{"path":"/big"}""")
        assertEquals(false, big.json["ok"]); assertEquals("response is too large", big.json["error"])
    }

    @Test fun deniesByDefault() {
        assertEquals(404, viewer.post("/api/v1/hmi/projects/plant/http/missing", """{"path":"/temp"}""").status)
        assertEquals(404, viewer.post("/api/v1/hmi/projects/nope/http/weather", """{"path":"/temp"}""").status)
        assertEquals(401, Http(gw.port()).post(route, """{"path":"/temp"}""").status)
        for (bad in listOf("/../etc", "//evil.example/x", "temp", "/a?b=1", "/a@b", "/%2e%2e/x", "/a\\b", "")) {
            val r = viewer.post(route, Json.write(mapOf("path" to bad)))
            assertEquals(422, r.status, "$bad: ${r.body}")
        }
        assertEquals(422, viewer.post(route, """{"path":"/temp","query":{"a":[1]}}""").status)
        assertTrue(seenPaths.isEmpty(), "a refused request must not reach the upstream: $seenPaths")
        // A source that is not an http(s) address is refused when it is saved.
        val doc = """{"apiVersion":"oip/v1","kind":"HttpSource","metadata":{"name":"bad","module":"designer","version":0},"spec":{"baseUrl":"file:///etc"}}"""
        assertEquals(422, admin.put("/api/v1/config/designer/project/plant/HttpSource/bad", doc).status)
    }

    @Test fun aCredentialHeaderMustBeASecretReference() {
        for (header in listOf("Authorization", "X-Api-Key", "Cookie", "X-Auth-Token")) {
            val doc = """{"apiVersion":"oip/v1","kind":"HttpSource","metadata":{"name":"plain","module":"designer","version":0},
                "spec":{"baseUrl":"http://127.0.0.1:1/v1","headers":{"$header":"plain-text-credential"}}}"""
            assertEquals(422, admin.put("/api/v1/config/designer/project/plant/HttpSource/plain", doc).status, header)
        }
        // The stored source holds the reference, not the credential.
        val stored = admin.get("/api/v1/config/designer/project/plant/HttpSource/weather")
        assertTrue(stored.body.contains("secret://weather-key"), stored.body)
        assertFalse(stored.body.contains("s3cret-token"))
        // A reference to a secret that does not exist fails without reaching the upstream.
        putSource("nokey", """{"baseUrl":"http://127.0.0.1:${upstream.address.port}/v1","headers":{"X-Api-Key":"secret://missing-key"}}""")
        val r = viewer.post("/api/v1/hmi/projects/plant/http/nokey", """{"path":"/temp"}""")
        assertEquals(false, r.json["ok"]); assertEquals("source credential could not be read", r.json["error"])
        assertTrue(seenPaths.isEmpty(), seenPaths.toString())
    }
}
