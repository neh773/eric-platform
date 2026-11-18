package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Chart instances, event-stream HTTP ingress, and the small web-dev mount.
 */
class M54ApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun chartStartPauseAndCancelStayOffTheEndStep() {
        val created = http.post("/api/v1/config/sfc/gateway/Chart", chart("held"))
        assertEquals(201, created.status, created.body)
        val op = Http(gw.port())
        val view = Http(gw.port())
        val anon = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(200, view.login("view", TEST_PASSWORD).status)

        assertEquals(401, anon.post("/api/v1/sfc/charts/held/start", """{"parameters":{}}""").status)
        assertEquals(403, view.post("/api/v1/sfc/charts/held/start", """{"parameters":{}}""").status)
        assertEquals(200, view.get("/api/v1/sfc/charts").status)
        val started = op.post("/api/v1/sfc/charts/held/start", """{"parameters":{}}""")
        assertEquals(201, started.status, started.body)
        assertEquals("Running", started.json["state"])
        assertEquals(listOf("step"), started.json["activeSteps"])
        val id = started.json["id"] as String

        assertEquals(403, view.post("/api/v1/sfc/instances/$id/pause", "{}").status)
        val paused = op.post("/api/v1/sfc/instances/$id/pause", "{}")
        assertEquals(200, paused.status, paused.body)
        assertEquals("Paused", paused.json["state"])
        val canceled = op.post("/api/v1/sfc/instances/$id/cancel", "{}")
        assertEquals(200, canceled.status, canceled.body)
        assertEquals("Canceled", canceled.json["state"])
        assertEquals(2, canceled.json["stateCode"])
        val variables = canceled.json["variables"] as Map<*, *>
        assertEquals(0, (variables["finished"] as Number).toInt())
        assertEquals(1, (variables["canceled"] as Number).toInt())
        assertEquals(200, view.get("/api/v1/sfc/instances/$id").status)

        val disabled = http.post("/api/v1/config/sfc/gateway/Chart", chart("off", "disabled"))
        assertEquals(201, disabled.status, disabled.body)
        val rejected = op.post("/api/v1/sfc/charts/off/start", """{"parameters":{}}""")
        assertEquals(422, rejected.status, rejected.body)
    }

    @Test fun httpEventReachesANamedUpdateAndAFullBufferReturns429() {
        assertEquals(201, http.post("/api/v1/db/connections", """
            {"name":"plant","driver":"sqlite","url":"jdbc:sqlite::memory:"}
        """.trimIndent()).status)
        assertEquals(201, http.post("/api/v1/db/queries", """
            {"name":"create","connection":"plant","queryType":"update","sql":"CREATE TABLE events (n INTEGER)"}
        """.trimIndent()).status)
        assertEquals(200, http.post("/api/v1/db/queries/create/execute", """{"parameters":{}}""").status)
        assertEquals(201, http.post("/api/v1/db/queries", """
            {"name":"add","connection":"plant","queryType":"update","sql":"INSERT INTO events(n) VALUES (:n)","parameters":[{"name":"n","dataType":"integer"}],"roles":["administrator"]}
        """.trimIndent()).status)
        assertEquals(201, http.post("/api/v1/db/queries", """
            {"name":"find","connection":"plant","queryType":"scalar","sql":"SELECT n FROM events","roles":["administrator"]}
        """.trimIndent()).status)
        val stream = http.post("/api/v1/config/events/gateway/EventStream", """
            {"apiVersion":"oip/v1","kind":"EventStream","metadata":{"name":"ingest","module":"events"},"spec":{
              "source":{"type":"http"},
              "buffer":{"debounceMillis":0,"maxWaitMillis":0},
              "handlers":[{"type":"database","query":"add","parameters":{"n":"{event.data.n}"},"roles":["administrator"]}]
            }}
        """.trimIndent())
        assertEquals(201, stream.status, stream.body)

        val anon = Http(gw.port())
        val published = anon.post("/system/eventstream/ingest", """{"n":7}""")
        assertEquals(202, published.status, published.body)
        val metrics = waitFor("ingest") { (it["handlerExecutions"] as Number).toLong() >= 1L }
        assertEquals(0, (metrics["handlerErrors"] as Number).toInt(), metrics.toString())
        val found = http.post("/api/v1/db/queries/find/execute", """{"parameters":{}}""")
        assertEquals(200, found.status, found.body)
        assertEquals(7, (found.json["value"] as Number).toInt())

        val blocked = http.post("/api/v1/config/events/gateway/EventStream", """
            {"apiVersion":"oip/v1","kind":"EventStream","metadata":{"name":"full","module":"events"},"spec":{
              "source":{"type":"http","requireAuthentication":true,"roles":["operator"]},
              "buffer":{"debounceMillis":60000,"maxWaitMillis":0,"maxQueueSize":1,"overflow":"dropNewest"},
              "handlers":[{"type":"logger","message":"{event.data}"}]
            }}
        """.trimIndent())
        assertEquals(201, blocked.status, blocked.body)
        assertEquals(401, anon.post("/system/eventstream/full", """{"n":1}""").status)
        val op = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(202, op.post("/system/eventstream/full", """{"n":1}""").status)
        val dropped = op.post("/system/eventstream/full", """{"n":2}""")
        assertEquals(429, dropped.status, dropped.body)
        assertEquals(1, (http.get("/api/v1/event-streams/full").json["eventsDropped"] as Number).toInt())
        val viewer = Http(gw.port())
        assertEquals(200, viewer.login("view", TEST_PASSWORD).status)
        assertEquals(403, viewer.get("/api/v1/event-streams").status)
    }

    @Test fun webDevServesTextFallsBackForPythonAndRejectsEscape() {
        val text = http.post("/api/v1/config/webdev/gateway/WebResource", resource("hello", """
            {"path":"hello","type":"text","body":"hello-web","contentType":"text/plain; charset=utf-8"}
        """.trimIndent()))
        assertEquals(201, text.status, text.body)
        val anon = Http(gw.port())
        val page = anon.get("/system/webdev/gateway/hello")
        assertEquals(200, page.status, page.body)
        assertEquals("hello-web", page.body)

        val script = http.post("/api/v1/config/webdev/gateway/WebResource", resource("page", """
            {"path":"page","type":"python","source":"doGet","body":"fallback","contentType":"text/plain; charset=utf-8"}
        """.trimIndent()))
        assertEquals(201, script.status, script.body)
        assertEquals("fallback", anon.get("/system/webdev/gateway/page").body)
        val missing = http.post("/api/v1/config/webdev/gateway/WebResource", resource("scripted", """
            {"path":"scripted","type":"python","source":"doGet"}
        """.trimIndent()))
        assertEquals(201, missing.status, missing.body)
        assertEquals(501, anon.get("/system/webdev/gateway/scripted").status)

        val locked = http.post("/api/v1/config/webdev/gateway/WebResource", resource("private", """
            {"path":"private","type":"text","body":"secret-page","requireAuthentication":true}
        """.trimIndent()))
        assertEquals(201, locked.status, locked.body)
        assertEquals(401, anon.get("/system/webdev/gateway/private").status)
        assertEquals("secret-page", http.get("/system/webdev/gateway/private").body)

        val tls = http.post("/api/v1/config/webdev/gateway/WebResource", resource("secure", """
            {"path":"secure","type":"text","body":"tls-only","requireHttps":true}
        """.trimIndent()))
        assertEquals(201, tls.status, tls.body)
        assertEquals(403, anon.get("/system/webdev/gateway/secure").status)

        tmp.resolve("webdev/site").createDirectories()
        tmp.resolve("webdev/site/hello.txt").writeText("from-folder")
        val canary = "webdev-canary-secret"
        tmp.resolve("secret.txt").writeText(canary)
        val mounted = http.post("/api/v1/config/webdev/gateway/WebResource", resource("files", """
            {"path":"files","type":"mounted","folder":"site"}
        """.trimIndent()))
        assertEquals(201, mounted.status, mounted.body)
        assertEquals("from-folder", anon.get("/system/webdev/gateway/files/hello.txt").body)
        val escaped = anon.get("/system/webdev/gateway/files/../../secret.txt")
        assertFalse(escaped.body.contains(canary), escaped.body)
        assertTrue(escaped.status == 404 || escaped.status == 400, escaped.body)
        val badFolder = http.post("/api/v1/config/webdev/gateway/WebResource", resource("bad", """
            {"path":"bad","type":"mounted","folder":"../secrets"}
        """.trimIndent()))
        assertEquals(422, badFolder.status, badFolder.body)
    }

    @Test fun aTestEventReachesAnySavedStream() {
        val created = http.post("/api/v1/config/events/gateway/EventStream", """
            {"apiVersion":"oip/v1","kind":"EventStream","metadata":{"name":"probe","module":"events"},"spec":{
              "enabled":true,
              "source":{"type":"tag","paths":["[default]Probe"]},
              "handlers":[{"type":"logger","message":"{event.data}"}]
            }}
        """.trimIndent())
        assertEquals(201, created.status, created.body)
        val sent = http.post("/api/v1/event-streams/probe/test", """{"hello":"stream"}""")
        assertEquals(202, sent.status, sent.body)
        val view = http.get("/api/v1/event-streams/probe").json
        assertEquals(1, (view["eventsReceived"] as Number).toInt())
        assertEquals(1, (view["handlerExecutions"] as Number).toInt())

        val off = http.post("/api/v1/config/events/gateway/EventStream", """
            {"apiVersion":"oip/v1","kind":"EventStream","metadata":{"name":"quiet","module":"events"},"spec":{
              "enabled":false,
              "source":{"type":"listener"},
              "handlers":[{"type":"logger","message":"{event.data}"}]
            }}
        """.trimIndent())
        assertEquals(201, off.status, off.body)
        assertEquals(422, http.post("/api/v1/event-streams/quiet/test", """{"n":1}""").status)

        val viewer = Http(gw.port())
        assertEquals(200, viewer.login("view", TEST_PASSWORD).status)
        assertEquals(403, viewer.post("/api/v1/event-streams/probe/test", """{"n":1}""").status)
    }

    private fun waitFor(name: String, ready: (Map<String, Any?>) -> Boolean): Map<String, Any?> {
        val deadline = System.nanoTime() + 3_000_000_000L
        var last = http.get("/api/v1/event-streams/$name").json
        while (System.nanoTime() < deadline) {
            if (ready(last)) return last
            Thread.sleep(25)
            val next = http.get("/api/v1/event-streams/$name")
            assertEquals(200, next.status, next.body)
            last = next.json
        }
        return last
    }

    private fun chart(name: String, mode: String = "callable") = """
        {"apiVersion":"oip/v1","kind":"Chart","metadata":{"name":"$name","module":"sfc"},"spec":{
          "executionMode":"$mode",
          "variables":[{"name":"ready","initial":false},{"name":"finished","initial":0},{"name":"canceled","initial":0}],
          "onCancel":[{"type":"expression","assign":"canceled","expression":"1"}],
          "elements":[
            {"id":"begin","type":"begin"},
            {"id":"step","type":"action"},
            {"id":"go","type":"transition","expression":"{ready}"},
            {"id":"done","type":"action","onStart":[{"type":"expression","assign":"finished","expression":"1"}]},
            {"id":"end","type":"end"}
          ],
          "links":[
            {"from":"begin","to":"step"},
            {"from":"step","to":"go"},
            {"from":"go","to":"done"},
            {"from":"done","to":"end"}
          ]
        }}
    """.trimIndent()

    private fun resource(name: String, spec: String) = """
        {"apiVersion":"oip/v1","kind":"WebResource","metadata":{"name":"$name","module":"webdev"},"spec":$spec}
    """.trimIndent()
}
