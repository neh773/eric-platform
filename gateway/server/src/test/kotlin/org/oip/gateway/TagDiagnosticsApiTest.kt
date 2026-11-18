package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import org.oip.tags.DataType
import org.oip.tags.Deadband
import org.oip.tags.ExecutionMode
import org.oip.tags.GroupMode
import org.oip.tags.TagConfig
import org.oip.tags.TagGroupDef
import org.oip.tags.TagType
import java.net.URLEncoder
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * `GET /api/v1/tags/diagnostics` (TAG-003).
 *
 * Memory, OPC and expression tags each return the value, quality, timestamp, subscriber count,
 * references, tag group execution counters and deadband counters. Subscriber and deadband counts
 * move when a subscription opens or a value changes. The route requires tags.read.
 */
class TagDiagnosticsApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        gw.start()
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun memoryOpcAndExpressionTagsReturnEveryFieldAndCountsMove() {
        gw.tags.configure(TagConfig(
            path = "Level", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0.0, valueSet = true,
            deadband = Deadband.absolute(1.0),
        ))
        gw.tags.configure(TagConfig(
            path = "Dev", type = TagType.OPC, dataType = DataType.DOUBLE,
            opcServer = "sim", opcItemPath = "HR1", deadband = Deadband.absolute(1.0),
        ))
        gw.tags.configure(TagConfig(
            path = "High", type = TagType.EXPRESSION, dataType = DataType.DOUBLE, expression = "{Level} + 1",
        ))
        gw.tags.configureGroup(TagGroupDef("fast", GroupMode.DIRECT, rateMillis = 100))
        gw.tags.configure(TagConfig(
            path = "Calc", type = TagType.EXPRESSION, dataType = DataType.DOUBLE, expression = "{Level}",
            tagGroup = "fast", executionMode = ExecutionMode.TAG_GROUP,
        ))

        val memory = diag(http, "[default]Level")
        assertEquals(200, memory.status, memory.body)
        assertShape(memory.json, "memory")
        assertEquals(0.0, memory.json["value"])
        assertEquals("Good", memory.json["quality"])
        assertNotNull(memory.json["timestamp"])
        val memDb = deadband(memory.json)
        assertEquals("absolute", memDb["mode"])
        assertEquals(1.0, memDb["limit"])
        assertTrue(http.post("/api/v1/tags/write", """{"writes":[{"path":"[default]Level","value":0.4}]}""").status == 200)
        val inside = diag(http, "[default]Level")
        assertEquals(0.0, inside.json["value"])
        assertEquals(long(memDb["evaluated"]) + 1, long(deadband(inside.json)["evaluated"]))
        assertEquals(long(memDb["published"]), long(deadband(inside.json)["published"]))
        assertEquals(200, http.post("/api/v1/tags/write", """{"writes":[{"path":"[default]Level","value":2}]}""").status)
        val moved = diag(http, "[default]Level")
        assertEquals(2.0, moved.json["value"])
        assertEquals(long(deadband(inside.json)["evaluated"]) + 1, long(deadband(moved.json)["evaluated"]))
        assertEquals(long(deadband(inside.json)["published"]) + 1, long(deadband(moved.json)["published"]))

        val beforeSubs = int(moved.json["subscriberCount"])
        val sub = gw.tags.subscribe("[default]Level") { }
        val subscribed = diag(http, "[default]Level")
        assertEquals(beforeSubs + 1, int(subscribed.json["subscriberCount"]))
        sub.close()
        assertEquals(beforeSubs, int(diag(http, "[default]Level").json["subscriberCount"]))

        val kinds = links(subscribed.json, "referencesIn").map { it["kind"] }.toSet()
        assertEquals(setOf("expression"), kinds)
        assertTrue(links(subscribed.json, "referencesIn").all { (it["path"] as String).startsWith("[default]") })

        val opcBase = diag(http, "[default]Dev")
        assertShape(opcBase.json, "opc")
        gw.tags.updateRaw("[default]Dev", 5.0)
        gw.tags.updateRaw("[default]Dev", 5.4)
        gw.tags.updateRaw("[default]Dev", 8.0)
        val opc = diag(http, "[default]Dev")
        assertEquals(8.0, opc.json["value"])
        assertEquals("Good", opc.json["quality"])
        assertEquals(long(deadband(opcBase.json)["evaluated"]) + 3, long(deadband(opc.json)["evaluated"]))
        assertEquals(long(deadband(opcBase.json)["published"]) + 2, long(deadband(opc.json)["published"]))

        val expr = diag(http, "[default]High")
        assertShape(expr.json, "expression")
        assertEquals(listOf("[default]Level"), links(expr.json, "referencesOut").map { it["path"] })
        assertEquals("memory", links(expr.json, "referencesOut").single()["kind"])

        gw.tags.tickExpressionGroups()
        val group = group(diag(http, "[default]Calc").json)
        assertEquals("fast", group["name"])
        assertEquals("direct", group["mode"])
        assertEquals(100, int(group["rateMillis"]))
        assertEquals(true, group["valid"])
        assertNotNull(group["lastExecution"])
        assertTrue(double(group["durationMillis"]) >= 0.0)
        assertTrue(long(group["overruns"]) >= 0)
        assertEquals("tags.read", GatewayAuth.explicitRule("GET", "/api/v1/tags/diagnostics"))
    }

    @Test fun aViewBindingIsAReferenceIn() {
        gw.tags.configure(TagConfig(path = "Src", type = TagType.MEMORY, dataType = DataType.INT32, value = 4, valueSet = true))
        val view = """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"home","module":"designer","version":0},
            "spec":{"path":"home","title":"Home","document":{"root":{"id":"root","kind":"container","children":[
            {"id":"meter","kind":"component","type":"label","props":{"text":""},
            "bindings":{"text":{"kind":"tag","mode":"direct","path":"[default]Src"},
            "caption":{"kind":"expression","expression":"{[default]Src} + 1"}}}]}}}}"""
        assertEquals(201, http.post("/api/v1/config/designer/project/plant/View", view).status)
        val refs = links(diag(http, "[default]Src").json, "referencesIn")
        val bindings = refs.filter { it["kind"] == "binding" }.map { it["path"] as String }
        assertTrue(bindings.any { it.contains("meter.text") }, bindings.toString())
        assertTrue(bindings.any { it.contains("meter.caption") }, bindings.toString())
    }

    @Test fun diagnosticsRequireTagReadAndHideATagTheCallerMayNotRead() {
        gw.tags.configure(TagConfig(
            path = "Secret", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 9.5, valueSet = true,
            readPermissions = setOf(DefaultRoles.OPERATOR),
        ))
        assertEquals(401, Http(gw.port()).get(url("[default]Secret")).status)

        gw.security.roles.create("limited", setOf(Permissions.GATEWAY_ACCESS), actor = "test")
        gw.createUser("lim", setOf("limited"))
        val limited = Http(gw.port())
        assertEquals(200, limited.login("lim", TEST_PASSWORD).status)
        assertEquals(403, limited.get(url("[default]Secret")).status)

        val viewer = Http(gw.port())
        assertEquals(200, viewer.login("view", TEST_PASSWORD).status)
        val hidden = diag(viewer, "[default]Secret")
        assertEquals(200, hidden.status, hidden.body)
        assertNull(hidden.json["value"])
        assertEquals("Bad_AccessDenied", hidden.json["quality"])
        // The server clock stamp can itself contain "9.5" (e.g. 06:10:09.56Z); every other byte must not.
        val withoutStamp = hidden.body.replace("\"timestamp\":\"${hidden.json["timestamp"]}\"", "")
        assertFalse(withoutStamp.contains("9.5"), hidden.body)
        assertEquals(emptyList<Any?>(), hidden.json["referencesIn"])

        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        val op = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        val shown = diag(op, "[default]Secret")
        assertEquals(9.5, shown.json["value"])
        assertEquals("Good", shown.json["quality"])

        assertEquals(422, http.get("/api/v1/tags/diagnostics").status)
        assertEquals(404, diag(http, "[default]Missing").status)
        assertEquals(400, diag(http, "[.]Relative").status)
    }

    private fun diag(client: Http, path: String): Http.Res = client.get(url(path))

    private fun url(path: String) = "/api/v1/tags/diagnostics?path=${URLEncoder.encode(path, Charsets.UTF_8)}"

    @Suppress("UNCHECKED_CAST")
    private fun assertShape(doc: Map<String, Any?>, type: String) {
        assertEquals(type, doc["type"])
        assertTrue(doc.containsKey("value"))
        assertNotNull(doc["quality"])
        assertNotNull(doc["timestamp"])
        assertTrue(doc.containsKey("message"))
        assertTrue(doc.containsKey("subscriberCount"))
        assertTrue(doc["referencesIn"] is List<*>)
        assertTrue(doc["referencesOut"] is List<*>)
        val group = doc["tagGroup"] as Map<String, Any?>
        for (key in listOf("name", "mode", "rateMillis", "valid", "lastExecution", "durationMillis", "overruns")) {
            assertTrue(group.containsKey(key), "tagGroup.$key missing: $group")
        }
        val deadband = doc["deadband"] as Map<String, Any?>
        for (key in listOf("mode", "limit", "evaluated", "published")) {
            assertTrue(deadband.containsKey(key), "deadband.$key missing: $deadband")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun links(doc: Map<String, Any?>, name: String) = doc[name] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun group(doc: Map<String, Any?>) = doc["tagGroup"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun deadband(doc: Map<String, Any?>) = doc["deadband"] as Map<String, Any?>

    private fun long(v: Any?) = (v as Number).toLong()
    private fun int(v: Any?) = (v as Number).toInt()
    private fun double(v: Any?) = (v as Number).toDouble()
}
