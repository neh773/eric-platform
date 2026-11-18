package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.scripting.EVENT_KIND
import org.oip.scripting.SCRIPT_MODULE
import org.oip.scripting.TAG_EVENT_KIND
import org.oip.security.DefaultRoles
import org.oip.sfc.CHART_KIND
import org.oip.sfc.SFC_MODULE
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TAG_KIND
import org.oip.tags.TagCaller
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * SCR-042 on a running gateway: one project library module, `shared`, is importable and callable from every script
 * scope this gateway has (gateway timer, tag-change and message events, tag event scripts, HMI script hooks as the
 * session user, SFC steps, event-stream handlers, web resources, and the designer script console), and a save of the
 * module is what the next run in each scope sees.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ScriptLibraryScopesTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        // The peer network listener is off so this test does not need its fixed port.
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
    }

    @AfterEach fun tearDown() = gw.close()

    private fun put(module: String, kind: String, name: String, spec: Map<String, Any?>, scope: Scope = Scope.GATEWAY) {
        gw.store.put(scope, Resource(kind, Metadata(name, module, version = gw.store.get(org.oip.config.ResourceId(module, scope, kind, name))?.metadata?.version ?: 0), spec), "test")
    }

    private fun tag(path: String) = put(TAGS_MODULE, TAG_KIND, path.lowercase().replace('/', '-'), mapOf(
        "path" to path, "type" to "memory", "dataType" to "String", "value" to "",
    ))

    private fun value(path: String): Any? = gw.tags.read("[default]$path").value

    private fun until(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!runCatching(check).getOrDefault(false)) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    /** Each scope's own way in, as the gateway runs it, returning what `shared.mark` said there. */
    private fun everyScope(version: String): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        // Timer gateway event: runs on its own schedule; wait for this version.
        until("the timer event to see $version") { (value("Lib/timer") as String).startsWith(version) }
        out["timer"] = value("Lib/timer")
        // Tag-change gateway event and the tag event script, both on Lib/trigger.
        gw.tags.write("[default]Lib/trigger", "go-$version")
        until("the tag-change event to see $version") { (value("Lib/tagChange") as String).startsWith(version) }
        until("the tag event script to see $version") { (value("Lib/tagEvent") as String).startsWith(version) }
        out["tagChange"] = value("Lib/tagChange")
        out["tagEvent"] = value("Lib/tagEvent")
        // Message handler, called and waited for.
        gw.scripts.postMessage("lib-message", "{}")
        out["message"] = value("Lib/message")
        // HMI script hook, run for a session user through the gateway's HMI runner.
        val reply = gw.hmiScripts.run(
            TagCaller.User("op", setOf(DefaultRoles.OPERATOR), "default"),
            SessionHost.ScriptCall(SessionHost.ScriptKind.TRANSFORM, "plant", null, "mark", value = "session"),
        )
        assertEquals(true, reply.ok, reply.toString())
        out["session"] = reply.value
        // SFC step.
        val id = gw.charts.startChart("libchart")
        until("the chart to stop") { gw.charts.instance(id)["state"] == "Stopped" }
        @Suppress("UNCHECKED_CAST")
        out["sfc"] = (gw.charts.instance(id)["variables"] as Map<String, Any?>)["out"]
        // Event-stream script handler.
        assertEquals(202, admin.post("/api/v1/event-streams/libstream/listen", """{"n":1}""").status)
        until("the event stream to see $version") { (value("Lib/stream") as String).startsWith(version) }
        out["stream"] = value("Lib/stream")
        // Web resource.
        val web = Http(gw.port()).get("/system/webdev/gateway/lib")
        assertEquals(200, web.status, web.body)
        out["webdev"] = web.body
        // Designer script console.
        val console = admin.post("/api/v1/designer/console", """{"source":"import shared\n_result = shared.mark('designer')\n"}""")
        assertEquals(200, console.status, console.body)
        assertEquals(true, console.json["ok"], console.body)
        out["designer"] = console.json["value"]
        return out
    }

    @Test fun oneLibraryModuleIsCallableFromEveryScopeAndASaveReachesEachNextRun() {
        for (path in listOf("Lib/timer", "Lib/trigger", "Lib/tagChange", "Lib/tagEvent", "Lib/message", "Lib/stream")) tag(path)
        val saved = gw.scripts.saveModule("shared", "def mark(where):\n    return 'v1:' + where\n")
        fun event(name: String, spec: Map<String, Any?>) = put(SCRIPT_MODULE, EVENT_KIND, name, spec)
        event("lib-timer", mapOf("type" to "timer", "delayMillis" to 100,
            "source" to "system.tag.writeBlocking(['[default]Lib/timer'], [shared.mark('timer')])\n"))
        event("lib-change", mapOf("type" to "tagChange", "tagPaths" to listOf("[default]Lib/trigger"), "triggers" to listOf("value"),
            "source" to "if not initialChange:\n    system.tag.writeBlocking(['[default]Lib/tagChange'], [shared.mark('tagChange')])\n"))
        event("lib-message", mapOf("type" to "message",
            "source" to "system.tag.writeBlocking(['[default]Lib/message'], [shared.mark('message')])\n"))
        put(SCRIPT_MODULE, TAG_EVENT_KIND, "lib-tag-event", mapOf("tagPath" to "[default]Lib/trigger", "event" to "value",
            "source" to "system.tag.writeBlocking(['[default]Lib/tagEvent'], [shared.mark('tagEvent')])\n"))
        put(DESIGNER_MODULE, SCRIPT_HOOKS_KIND, SCRIPT_HOOKS_RESOURCE,
            mapOf("hooks" to mapOf("mark" to mapOf("source" to "return shared.mark(value)"))), Scope.project("plant"))
        put(SFC_MODULE, CHART_KIND, "libchart", mapOf(
            "variables" to listOf(mapOf("name" to "out", "initial" to "")),
            "elements" to listOf(
                mapOf("id" to "begin", "type" to "begin"),
                mapOf("id" to "step", "type" to "action", "onStart" to listOf(mapOf("type" to "script", "source" to "chart.out = shared.mark('sfc')"))),
                mapOf("id" to "end", "type" to "end"),
            ),
            "links" to listOf(mapOf("from" to "begin", "to" to "step"), mapOf("from" to "step", "to" to "end")),
        ))
        put("events", "EventStream", "libstream", mapOf(
            "source" to mapOf("type" to "listener"),
            "handlers" to listOf(mapOf("type" to "script",
                "source" to "system.tag.writeBlocking(['[default]Lib/stream'], [shared.mark('stream')])\n")),
        ))
        put("webdev", "WebResource", "lib", mapOf("path" to "lib", "type" to "python",
            "source" to "def doGet(request):\n    return shared.mark('webdev')\n"))

        val scopes = listOf("timer", "tagChange", "tagEvent", "message", "session", "sfc", "stream", "webdev", "designer")
        assertEquals(scopes.associateWith { "v1:$it" }, everyScope("v1"))

        gw.scripts.saveModule("shared", "def mark(where):\n    return 'v2:' + where\n", version = saved.metadata.version)
        assertEquals(scopes.associateWith { "v2:$it" }, everyScope("v2"))
    }
}
