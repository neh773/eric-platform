package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.scripting.ScriptLimits
import org.oip.security.DefaultRoles
import org.oip.security.SecurityZone
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * D-5a: the `script` frame on `/api/v1/ws` runs a project's HMI script hooks (script transforms and event scripts) as
 * the session user, with the gateway script limits, per-session and per-user caps, and the login checked at each
 * write. Failures are per-call results.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class HmiScriptHostTest {
    @TempDir lateinit var tmp: Path

    private fun gateway(name: String): Gateway {
        val dir = tmp.resolve(name).createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        return Gateway(dir, port = 0, adminDist = null, webDist = null)
    }

    private fun Gateway.tag(name: String, vararg spec: Pair<String, Any?>) {
        store.put(Scope.GATEWAY, Resource("Tag", Metadata(name, "tags"), mapOf(*spec)))
    }

    private fun Gateway.hooks(project: String, vararg hooks: Pair<String, String>) {
        val id = ResourceId(DESIGNER_MODULE, Scope.project(project), SCRIPT_HOOKS_KIND, SCRIPT_HOOKS_RESOURCE)
        val version = store.get(id)?.metadata?.version ?: 0L
        val spec = mapOf("hooks" to hooks.associate { (name, source) -> name to mapOf("source" to source) })
        store.put(Scope.project(project), Resource(SCRIPT_HOOKS_KIND, Metadata(SCRIPT_HOOKS_RESOURCE, DESIGNER_MODULE, version = version), spec))
    }

    private fun login(gw: Gateway, user: String): Http = Http(gw.port()).also {
        val r = it.login(user, TEST_PASSWORD)
        check(r.status == 200) { "login $user: ${r.status} ${r.body}" }
    }

    private class Ws(val socket: WebSocket, val frames: LinkedBlockingQueue<Map<String, Any?>>, val closed: CompletableFuture<Int>) {
        fun send(vararg entries: Pair<String, Any?>) {
            socket.sendText(Json.write(mapOf(*entries)), true).get(5, TimeUnit.SECONDS)
        }

        fun next(timeoutMillis: Long = 30_000, match: (Map<String, Any?>) -> Boolean): Map<String, Any?> {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (true) {
                val left = deadline - System.nanoTime()
                val frame = frames.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("no matching frame within $timeoutMillis ms")
                if (match(frame)) return frame
            }
        }

        /** Script results seen while waiting for another one; results may arrive in any order. */
        private val results = HashMap<Any?, Map<String, Any?>>()

        fun result(requestId: String, timeoutMillis: Long = 30_000): Map<String, Any?> {
            results.remove(requestId)?.let { return it }
            return next(timeoutMillis) { frame ->
                val mine = frame["type"] == "scriptResult" && frame["requestId"] == requestId
                if (!mine && frame["type"] == "scriptResult") results[frame["requestId"]] = frame
                mine
            }
        }

        fun hello() {
            val nonce = java.util.UUID.randomUUID().toString()
            send("type" to "hello", "nonce" to nonce)
            next { it["type"] == "welcome" && it["nonce"] == nonce }
        }

        fun transform(requestId: String, project: String, hook: String, value: Any?, view: String? = null) = send(
            "type" to "script", "requestId" to requestId, "call" to "transform", "project" to project, "view" to view,
            "hook" to hook, "value" to value, "quality" to "good", "timestamp" to 1_000, "nodeId" to "label", "prop" to "text",
        )

        fun event(requestId: String, project: String, hook: String, payload: Any? = null) = send(
            "type" to "script", "requestId" to requestId, "call" to "event", "project" to project, "view" to "Main",
            "hook" to hook, "event" to "onClick", "payload" to payload, "nodeId" to "button",
            "params" to mapOf("line" to 3), "custom" to emptyMap<String, Any?>(),
        )

        fun close() {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
        }
    }

    private fun connect(gw: Gateway, http: Http?, headers: Map<String, String> = emptyMap()): Ws {
        val frames = LinkedBlockingQueue<Map<String, Any?>>()
        val closed = CompletableFuture<Int>()
        val builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        http?.sessionCookie()?.let { builder.header("Cookie", "${HttpAuth.COOKIE}=$it") }
        for ((name, value) in headers) builder.header(name, value)
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

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                closed.complete(statusCode)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                closed.complete(-1)
            }
        }
        val socket = builder.buildAsync(URI.create("ws://127.0.0.1:${gw.port()}${SessionHost.PATH}"), listener).get(10, TimeUnit.SECONDS)
        return Ws(socket, frames, closed).also { it.hello() }
    }

    private fun num(v: Any?): Double = (v as Number).toDouble()

    @Test fun aScriptTransformAndAnEventScriptRoundTrip() {
        gateway("round").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.hooks(
                "plant",
                "double" to "return value * 2",
                "describe" to "def transform(value, context):\n    return '%s %s %s %s' % (context.nodeId, context.prop, context.quality, context.user.name)\n",
                "goPumps" to "actions.navigate(page='/pumps', params={'line': component.params.line, 'from': event.name})\nreturn 'ignored'",
            )
            // A script transform that carries its own source in the saved view is found there first.
            val document = mapOf(
                "root" to mapOf(
                    "kind" to "component", "id" to "label",
                    "bindings" to mapOf("text" to mapOf("kind" to "tag", "transforms" to listOf(
                        mapOf("kind" to "script", "hook" to "double", "source" to "return value * 10"),
                    ))),
                ),
            )
            gw.store.put(Scope.project("plant"), Resource(VIEW_KIND, Metadata("main", DESIGNER_MODULE), mapOf("path" to "Main", "document" to document)))
            gw.start()
            val ws = connect(gw, login(gw, "ada"))

            ws.transform("t1", "plant", "double", 21)
            val doubled = ws.result("t1")
            assertEquals(true, doubled["ok"], doubled.toString())
            assertEquals(42.0, num(doubled["value"]))

            ws.transform("t2", "plant", "double", 21, view = "Main")
            assertEquals(210.0, num(ws.result("t2")["value"]))

            ws.transform("t3", "plant", "describe", "x")
            assertEquals("label text good ada", ws.result("t3")["value"])

            ws.event("e1", "plant", "goPumps")
            val event = ws.result("e1")
            assertEquals(true, event["ok"], event.toString())
            @Suppress("UNCHECKED_CAST") val actions = event["actions"] as List<Map<String, Any?>>
            assertEquals(1, actions.size)
            assertEquals("navigation", actions[0]["kind"])
            assertEquals("page", actions[0]["mode"])
            assertEquals("/pumps", actions[0]["page"])
            assertEquals(mapOf("line" to 3, "from" to "onClick"), actions[0]["params"])

            // An unknown hook is a per-call error; the socket stays usable.
            ws.transform("t4", "plant", "missing", 1)
            val unknown = ws.result("t4")
            assertEquals(false, unknown["ok"])
            assertTrue((unknown["error"] as String).contains("unknown script hook"), unknown.toString())
            assertEquals("unknown_hook", unknown["code"])
            ws.transform("t5", "plant", "double", 2)
            assertEquals(4.0, num(ws.result("t5")["value"]))
            ws.close()
        }
    }

    private fun Gateway.library(scope: Scope, name: String, source: String, module: String = "") {
        val id = ResourceId(org.oip.scripting.SCRIPT_MODULE, scope, org.oip.scripting.LIBRARY_KIND, name)
        val version = store.get(id)?.metadata?.version ?: 0L
        store.put(scope, Resource(org.oip.scripting.LIBRARY_KIND, Metadata(name, org.oip.scripting.SCRIPT_MODULE, version = version), mapOf("source" to source, "module" to module)))
    }

    /** PRO-001: a project's script library modules are its own. Two projects with a module of the same name run their own code. */
    @Test fun twoProjectsWithTheSameLibraryModuleRunTheirOwnCode() {
        gateway("libraries").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            val actor = "test"
            val base = org.oip.config.ProjectService.Settings(inheritable = true, runnable = false)
            gw.projectService.create("base", base, actor)
            gw.projectService.create("plant-a", org.oip.config.ProjectService.Settings(parent = "base"), actor)
            gw.projectService.create("plant-b", org.oip.config.ProjectService.Settings(), actor)
            gw.library(Scope.GATEWAY, "gateway-wide", "def where():\n    return 'gateway'\n", module = "wide")
            gw.library(Scope.project("base"), "calc", "def answer():\n    return 'base'\n")
            gw.library(Scope.project("base"), "shared", "def answer():\n    return 'shared'\n")
            gw.library(Scope.project("plant-a"), "calc", "def answer():\n    return 'a'\n")
            gw.library(Scope.project("plant-b"), "calc", "def answer():\n    return 'b'\n")
            val hook = "import calc\nimport wide\nreturn calc.answer() + '/' + wide.where()"
            gw.hooks("plant-a", "which" to hook, "inherited" to "import shared\nreturn shared.answer()")
            gw.hooks("plant-b", "which" to hook, "inherited" to "import shared\nreturn shared.answer()")
            gw.start()
            val ws = connect(gw, login(gw, "ada"))

            ws.transform("a", "plant-a", "which", 0)
            assertEquals("a/gateway", ws.result("a")["value"])
            ws.transform("b", "plant-b", "which", 0)
            assertEquals("b/gateway", ws.result("b")["value"])
            // plant-a inherits base's "shared" module; plant-b has no parent and does not see it.
            ws.transform("ia", "plant-a", "inherited", 0)
            assertEquals("shared", ws.result("ia")["value"])
            ws.transform("ib", "plant-b", "inherited", 0)
            val missing = ws.result("ib")
            assertEquals(false, missing["ok"], missing.toString())

            // Saving a project's module changes what that project's next run imports; the other project is unchanged.
            gw.library(Scope.project("plant-a"), "calc", "def answer():\n    return 'a2'\n")
            ws.transform("a2", "plant-a", "which", 0)
            assertEquals("a2/gateway", ws.result("a2")["value"])
            ws.transform("b2", "plant-b", "which", 0)
            assertEquals("b/gateway", ws.result("b2")["value"])
            ws.close()
        }
    }

    /** Review-4 F6 (PRO-004): the scripts of a disabled or inheritance-only project are refused over the socket. */
    @Test fun aDisabledOrNotRunnableProjectRunsNoScripts() {
        gateway("disabled").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.hooks("plant", "double" to "return value * 2")
            gw.start()
            val ws = connect(gw, login(gw, "ada"))
            ws.transform("t1", "plant", "double", 2)
            assertEquals(4.0, num(ws.result("t1")["value"]))

            gw.projectService.update("plant", mapOf("enabled" to false), null, "test")
            ws.transform("t2", "plant", "double", 2)
            val refused = ws.result("t2")
            assertEquals(false, refused["ok"], refused.toString())
            assertEquals(HmiScriptRunner.PROJECT_NOT_RUNNING, refused["code"])
            ws.event("e1", "plant", "double")
            assertEquals(HmiScriptRunner.PROJECT_NOT_RUNNING, ws.result("e1")["code"])

            gw.projectService.update("plant", mapOf("enabled" to true, "runnable" to false, "inheritable" to true), null, "test")
            ws.transform("t3", "plant", "double", 2)
            assertEquals(HmiScriptRunner.PROJECT_NOT_RUNNING, ws.result("t3")["code"])

            gw.projectService.update("plant", mapOf("runnable" to true), null, "test")
            ws.transform("t4", "plant", "double", 3)
            assertEquals(6.0, num(ws.result("t4")["value"]))
            ws.close()
        }
    }

    /** P4-04 (HMP-092): session roles and a view's security roles hold for script frames as they do for the project read. */
    @Test fun sessionAndViewSecurityRefuseScriptsToo() {
        gateway("secured").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("vic", setOf(DefaultRoles.VIEWER))
            gw.hooks("plant", "double" to "return value * 2")
            val view = mapOf(
                "path" to "reactor",
                "document" to mapOf(
                    "params" to emptyMap<String, Any?>(),
                    "security" to mapOf("roles" to listOf(DefaultRoles.OPERATOR)),
                    "root" to mapOf("id" to "root", "kind" to "container", "layout" to "coordinate", "children" to emptyList<Any?>()),
                ),
            )
            gw.store.put(Scope.project("plant"), Resource(VIEW_KIND, Metadata("reactor", DESIGNER_MODULE), view))
            gw.start()
            val operator = connect(gw, login(gw, "ada"))
            val viewer = connect(gw, login(gw, "vic"))
            operator.transform("o1", "plant", "double", 2, view = "reactor")
            assertEquals(4.0, num(operator.result("o1")["value"]))
            viewer.transform("v1", "plant", "double", 2, view = "reactor")
            val refusedView = viewer.result("v1")
            assertEquals(false, refusedView["ok"], refusedView.toString())
            assertEquals(HmiProjectApi.SESSION_REFUSED, refusedView["code"])
            // The project's hooks outside that view still run for the viewer.
            viewer.transform("v2", "plant", "double", 2)
            assertEquals(4.0, num(viewer.result("v2")["value"]))

            gw.projectService.update("plant", mapOf("sessionRoles" to listOf(DefaultRoles.OPERATOR)), null, "test")
            viewer.transform("v3", "plant", "double", 2)
            assertEquals(HmiProjectApi.SESSION_REFUSED, viewer.result("v3")["code"])
            operator.transform("o2", "plant", "double", 3)
            assertEquals(6.0, num(operator.result("o2")["value"]))

            gw.projectService.update("plant", mapOf("sessionRoles" to emptyList<String>(), "sessionIdentityProvider" to "corp"), null, "test")
            operator.transform("o3", "plant", "double", 3)
            assertEquals(HmiProjectApi.SESSION_REFUSED, operator.result("o3")["code"])
            operator.close()
            viewer.close()
        }
    }

    /** SEC-017: a hook inside a component the viewer's levels refuse, or a project hook with its own security, is refused. */
    @Test fun securityLevelsRefuseHooksOfDeniedComponentsAndGuardedHooks() {
        gateway("levels").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("vic", setOf(DefaultRoles.VIEWER))
            gw.store.put(Scope.GATEWAY, Resource(org.oip.security.SecurityLevels.KIND,
                Metadata(org.oip.security.SecurityLevels.RESOURCE, org.oip.security.SECURITY_MODULE),
                mapOf("levels" to listOf(mapOf("path" to "Authenticated/Roles/operator")))))
            val id = ResourceId(DESIGNER_MODULE, Scope.project("plant"), SCRIPT_HOOKS_KIND, SCRIPT_HOOKS_RESOURCE)
            gw.store.put(Scope.project("plant"), Resource(SCRIPT_HOOKS_KIND, Metadata(SCRIPT_HOOKS_RESOURCE, DESIGNER_MODULE, version = gw.store.get(id)?.metadata?.version ?: 0L),
                mapOf("hooks" to mapOf(
                    "open" to mapOf("source" to "return value"),
                    "guarded" to mapOf("source" to "return value + 1", "security" to mapOf("levels" to listOf("Authenticated/Roles/operator"))),
                ))))
            val button = mapOf("id" to "b", "kind" to "component", "type" to "button",
                "security" to mapOf("levels" to listOf("Authenticated/Roles/operator")),
                "events" to listOf(mapOf("event" to "onClick", "actions" to listOf(mapOf("kind" to "script", "hook" to "bypass", "source" to "return value * 10")))))
            val view = mapOf("path" to "reactor", "document" to mapOf(
                "params" to emptyMap<String, Any?>(),
                "root" to mapOf("id" to "root", "kind" to "container", "layout" to "coordinate", "children" to listOf(button)),
            ))
            gw.store.put(Scope.project("plant"), Resource(VIEW_KIND, Metadata("reactor", DESIGNER_MODULE), view))
            gw.start()
            val operator = connect(gw, login(gw, "ada"))
            val viewer = connect(gw, login(gw, "vic"))
            operator.transform("o1", "plant", "bypass", 2, view = "reactor")
            assertEquals(20.0, num(operator.result("o1")["value"]))
            operator.transform("o2", "plant", "guarded", 2)
            assertEquals(3.0, num(operator.result("o2")["value"]))
            viewer.transform("v1", "plant", "bypass", 2, view = "reactor")
            val refused = viewer.result("v1")
            assertEquals(HmiProjectApi.SESSION_REFUSED, refused["code"], refused.toString())
            viewer.transform("v2", "plant", "guarded", 2)
            assertEquals(HmiProjectApi.SESSION_REFUSED, viewer.result("v2")["code"])
            viewer.transform("v3", "plant", "open", 2)
            assertEquals(2.0, num(viewer.result("v3")["value"]))
            operator.close()
            viewer.close()
        }
    }

    @Test fun aScriptErrorIsAPerCallResultWithItsMessage() {
        gateway("errors").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.hooks("plant", "boom" to "raise ValueError('bad input %s' % value)", "syntax" to "return (")
            gw.start()
            val ws = connect(gw, login(gw, "ada"))
            ws.transform("b1", "plant", "boom", 7)
            val boom = ws.result("b1")
            assertEquals(false, boom["ok"])
            assertTrue((boom["error"] as String).contains("bad input 7"), boom.toString())
            ws.transform("b2", "plant", "syntax", 1)
            val syntax = ws.result("b2")
            assertEquals(false, syntax["ok"])
            assertTrue((syntax["error"] as String).contains("SyntaxError"), syntax.toString())
            // Malformed calls get a result too, not a dropped socket.
            ws.send("type" to "script", "requestId" to "m1", "call" to "nope", "project" to "plant", "hook" to "boom")
            assertEquals(false, ws.result("m1")["ok"])
            ws.send("type" to "script", "requestId" to "m2", "call" to "transform", "project" to "Not A Project", "hook" to "boom")
            assertEquals(false, ws.result("m2")["ok"])
            ws.send("type" to "script", "call" to "transform", "project" to "plant", "hook" to "boom")
            assertEquals("script needs a requestId", ws.next { it["type"] == "error" }["message"])
            assertFalse(ws.closed.isDone)
            ws.close()
        }
    }

    @Test fun scriptTagReadsAndWritesRunAsTheUserWithPerTagPermissions() {
        gateway("perms").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("vic", setOf(DefaultRoles.VIEWER))
            gw.security.zones.create(SecurityZone("floor", ipAddresses = listOf("127.0.0.1"), priority = 10), actor = "test")
            gw.tag("secret", "path" to "Line/Secret", "type" to "memory", "dataType" to "String", "value" to "hidden",
                "readPermissions" to listOf("administrator"))
            gw.tag("recipe", "path" to "Line/Recipe", "type" to "memory", "dataType" to "String", "value" to "mix",
                "readPermissions" to listOf("operator"))
            gw.tag("setpoint", "path" to "Line/Setpoint", "type" to "memory", "dataType" to "Double", "value" to 10.0,
                "writePermissions" to mapOf("roles" to listOf("operator"), "zones" to listOf("floor")))
            gw.tag("remote", "path" to "Line/RemoteOnly", "type" to "memory", "dataType" to "Double", "value" to 1.0,
                "writePermissions" to mapOf("roles" to listOf("operator"), "zones" to listOf("remote")))
            gw.hooks(
                "plant",
                "read" to "qv = system.tag.readBlocking([value])[0]\nreturn [qv.value, str(qv.quality)]",
                "write" to "return actions.writeTag(value['path'], value['value'])",
                "browse" to "return sorted([[r.name, r.dataType] for r in system.tag.browse(value).getResults()])",
            )
            gw.start()
            val ada = connect(gw, login(gw, "ada"))
            val vic = connect(gw, login(gw, "vic"))

            ada.transform("r1", "plant", "read", "[default]Line/Recipe")
            assertEquals(listOf("mix", "Good"), ada.result("r1")["value"])
            ada.transform("r2", "plant", "read", "[default]Line/Secret")
            val secret = ada.result("r2")
            assertEquals(true, secret["ok"], secret.toString())
            assertEquals(listOf(null, "Bad_AccessDenied"), secret["value"])

            ada.transform("w1", "plant", "write", mapOf("path" to "[default]Line/Setpoint", "value" to 55.5))
            assertEquals("Good", ada.result("w1")["value"])
            assertEquals(55.5, num(gw.tags.read("[default]Line/Setpoint").value))
            ada.transform("w2", "plant", "write", mapOf("path" to "[default]Line/RemoteOnly", "value" to 2.0))
            assertEquals("Bad_AccessDenied", ada.result("w2")["value"])
            assertEquals(1.0, num(gw.tags.read("[default]Line/RemoteOnly").value))

            // Browse runs as the user too: a tag it may not read is listed by name only, without its data type.
            ada.transform("br", "plant", "browse", "[default]Line")
            val browsed = ada.result("br")
            assertEquals(true, browsed["ok"], browsed.toString())
            @Suppress("UNCHECKED_CAST") val rows = (browsed["value"] as List<List<Any?>>).associate { it[0] to it[1] }
            assertEquals("String", rows["Recipe"], rows.toString())
            assertTrue(rows.containsKey("Secret"), rows.toString())
            assertEquals(null, rows["Secret"], rows.toString())

            // A viewer has no tags.write: a script it runs cannot write, even a tag with no write permission set.
            vic.transform("v1", "plant", "write", mapOf("path" to "[default]Line/Setpoint", "value" to 99.0))
            assertEquals("Bad_AccessDenied", vic.result("v1")["value"])
            assertEquals(55.5, num(gw.tags.read("[default]Line/Setpoint").value))
            ada.close()
            vic.close()
        }
    }

    @Test fun aSlowScriptTimesOutAndASessionRunsAtMostFourAtOnce() {
        gateway("limits").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.hooks("plant", "spin" to "while True:\n    pass", "quick" to "return value")
            gw.hmiScripts.limits = { ScriptLimits(timeout = Duration.ofMillis(1_500), maxStatements = Long.MAX_VALUE / 2) }
            gw.start()
            val ws = connect(gw, login(gw, "ada"))
            val other = connect(gw, login(gw, "ada"))
            val third = connect(gw, login(gw, "ada"))
            val cap = SessionHost.MAX_SCRIPT_RUNS_PER_SESSION
            for (i in 1..cap) ws.transform("s$i", "plant", "spin", i)
            ws.transform("over", "plant", "quick", 1)
            val busy = ws.result("over", 10_000)
            assertEquals(false, busy["ok"])
            assertTrue((busy["error"] as String).startsWith("busy"), busy.toString())
            assertEquals("busy", busy["code"])
            // The cap is per session: another session still runs.
            other.transform("o1", "plant", "quick", 5)
            assertEquals(5.0, num(other.result("o1")["value"]))
            // ... up to the per-user cap across sessions.
            assertEquals(2 * cap, SessionHost.MAX_SCRIPT_RUNS_PER_PRINCIPAL)
            for (i in 1..cap) other.transform("p$i", "plant", "spin", i)
            // Frames of different sockets are handled in parallel: wait until all eight runs hold their slots.
            waitUntil("eight running scripts") { gw.hmiScripts.running() == 2 * cap }
            third.transform("t1", "plant", "quick", 1)
            val userBusy = third.result("t1", 10_000)
            assertEquals("busy", userBusy["code"], userBusy.toString())
            assertTrue((userBusy["error"] as String).contains("for this user"), userBusy.toString())
            for (i in 1..cap) {
                val timedOut = ws.result("s$i")
                assertEquals(false, timedOut["ok"])
                assertEquals("script timed out", timedOut["error"])
                assertEquals("script timed out", other.result("p$i")["error"])
            }
            // Slots are free again.
            ws.transform("after", "plant", "quick", 9)
            assertEquals(9.0, num(ws.result("after")["value"]))
            third.transform("t2", "plant", "quick", 2)
            assertEquals(2.0, num(third.result("t2")["value"]))
            ws.close()
            other.close()
            third.close()
        }
    }
    /** Spins until `[default]Line/Gate` is true, writing Level before and after; returns both write qualities. */
    private val gated = """
first = actions.writeTag('[default]Line/Level', 1.0)
while not system.tag.readBlocking(['[default]Line/Gate'])[0].value:
    pass
second = actions.writeTag('[default]Line/Level', 2.0)
return [first, second]
"""

    private fun Gateway.gatedTags() {
        tag("level", "path" to "Line/Level", "type" to "memory", "dataType" to "Double", "value" to 0.0)
        tag("gate", "path" to "Line/Gate", "type" to "memory", "dataType" to "Boolean", "value" to false)
        hooks("plant", "gated" to gated)
        hmiScripts.limits = { ScriptLimits(timeout = Duration.ofSeconds(20), maxStatements = Long.MAX_VALUE / 2) }
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!check()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test fun aRunningScriptLosesItsWritesWhenItsKeyIsRevoked() {
        gateway("revoke").use { gw ->
            gw.gatedTags()
            gw.start()
            val key = gw.security.apiKeys.create("panel", setOf("tags.read", "tags.write"), secureConnectionRequired = false, actor = "test")
            val ws = connect(gw, null, mapOf("Authorization" to "Bearer ${key.token}"))
            ws.event("g1", "plant", "gated")
            waitUntil("first write") { num(gw.tags.read("[default]Line/Level").value) == 1.0 }
            // Revoked while the script runs: its next write is refused, though the call was allowed when it arrived.
            gw.security.apiKeys.revoke("panel", actor = "test")
            gw.tags.write("[default]Line/Gate", true)
            // The periodic re-check may close the socket before the answer arrives, so wait for the run itself.
            waitUntil("the script to finish") { gw.hmiScripts.running() == 0 }
            assertEquals(1.0, num(gw.tags.read("[default]Line/Level").value))
            ws.close()
        }
    }

    @Test fun loggingOutCancelsTheSessionsRunningScripts() {
        gateway("logout").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.gatedTags()
            gw.start()
            val http = login(gw, "ada")
            val ws = connect(gw, http)
            ws.event("g1", "plant", "gated")
            waitUntil("first write") { num(gw.tags.read("[default]Line/Level").value) == 1.0 }
            assertEquals(1, gw.hmiScripts.running())
            assertEquals(204, http.post("/api/v1/auth/logout", "{}").status)
            gw.sessions.sweepNow()
            assertEquals(1008, ws.closed.get(5, TimeUnit.SECONDS))
            // Cancelled well before its 20 s timeout, with the gate still shut.
            waitUntil("the script to stop") { gw.hmiScripts.running() == 0 }
            gw.tags.write("[default]Line/Gate", true)
            Thread.sleep(200)
            assertEquals(1.0, num(gw.tags.read("[default]Line/Level").value))
        }
    }
}
