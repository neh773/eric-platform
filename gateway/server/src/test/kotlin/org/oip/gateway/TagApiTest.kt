package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagType
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * `POST /api/v1/tags/browse` (TagApi): the Designer tag browser panel pages through the tag tree
 * one folder at a time, reusing `TagEngine.browse` (gateway/tags).
 */
class TagApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        // OPC UA defaults to a fixed port (4840); this test does not exercise it, so turn it off
        // to avoid colliding with another test's gateway on the same port in the same JVM.
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.start(); http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        memoryTag("plant/tank/level", "Double", 62.4)
        memoryTag("plant/pump/running", "Boolean", true)
    }

    @AfterEach fun tearDown() = gw.close()

    private fun memoryTag(path: String, dataType: String, value: Any) {
        val name = path.replace('/', '-')
        val spec = """{"path":"$path","type":"memory","dataType":"$dataType","value":${Json.write(value)}}"""
        val r = http.put(
            "/api/v1/config/tags/gateway/Tag/$name",
            """{"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":0},"spec":$spec}""",
        )
        check(r.status == 200 || r.status == 201) { "seed tag failed: ${r.status} ${r.body}" }
    }

    @Test fun browseRootListsFolder() {
        val r = http.post("/api/v1/tags/browse", """{"path":"[default]"}""")
        assertEquals(200, r.status, r.body)
        @Suppress("UNCHECKED_CAST") val nodes = r.json["nodes"] as List<Map<String, Any?>>
        val plant = nodes.find { it["name"] == "plant" }
        assertNotNull(plant, "expected a plant folder: $nodes")
        assertEquals("folder", plant!!["kind"])
        assertEquals(true, plant["hasChildren"])
    }

    @Test fun browseDescendsIntoFolderAndReturnsLeafTagWithValue() {
        val r = http.post("/api/v1/tags/browse", """{"path":"[default]plant/tank"}""")
        assertEquals(200, r.status, r.body)
        @Suppress("UNCHECKED_CAST") val nodes = r.json["nodes"] as List<Map<String, Any?>>
        val level = nodes.find { it["name"] == "level" }
        assertNotNull(level, "expected a level tag: $nodes")
        assertEquals("tag", level!!["kind"])
        assertEquals(false, level["hasChildren"])
        assertEquals("double", level["dataType"])
        assertEquals(62.4, level["value"])
        assertEquals("[default]plant/tank/level", level["path"])
        assertNotNull(level["quality"])
        assertNotNull(level["timestamp"])
    }

    @Test fun browseUnknownFolderReturnsEmptyList() {
        val r = http.post("/api/v1/tags/browse", """{"path":"[default]nope"}""")
        assertEquals(200, r.status, r.body)
        assertEquals(emptyList<Any?>(), r.json["nodes"])
    }

    @Test fun browsePagesThroughAFolderWithMoreFlag() {
        memoryTag("plant/tank/temp", "Double", 21.0)
        memoryTag("plant/tank/note", "String", "north")
        val seen = mutableListOf<String>()
        var offset = 0
        var pages = 0
        while (true) {
            val r = http.post("/api/v1/tags/browse", """{"path":"[default]plant/tank","offset":$offset,"limit":2}""")
            assertEquals(200, r.status, r.body)
            @Suppress("UNCHECKED_CAST") val nodes = r.json["nodes"] as List<Map<String, Any?>>
            assertTrue(nodes.size <= 2)
            seen += nodes.map { it["name"] as String }
            offset += nodes.size
            pages += 1
            if (r.json["more"] != true) break
            check(pages < 10) { "paging did not terminate" }
        }
        assertEquals(2, pages)
        assertEquals(setOf("level", "temp", "note"), seen.toSet())
        assertEquals(3, seen.size, "no row is repeated across pages: $seen")
    }

    @Test fun browseInvalidLimitIs422() {
        for (body in listOf(
            """{"path":"[default]","limit":0}""",
            """{"path":"[default]","limit":10001}""",
            """{"path":"[default]","offset":-1}""",
            """{"path":"[default]","offset":1.5}""",
            """{"path":"[default]","limit":"10"}""",
            """{"path":"[default]","offset":99999999999}""",
            """{"path":42}""",
            """[]""",
        )) {
            val r = http.post("/api/v1/tags/browse", body)
            assertEquals(422, r.status, "$body -> ${r.body}")
        }
    }

    @Test fun browseShowsATagsValueOnlyToACallerItsReadPermissionsAdmit() {
        gw.tags.configure(TagConfig(
            path = "plant/tank/secret",
            type = TagType.MEMORY,
            dataType = DataType.DOUBLE,
            value = 7.0,
            valueSet = true,
            readPermissions = setOf(DefaultRoles.OPERATOR),
        ))
        @Suppress("UNCHECKED_CAST")
        fun browseAs(client: Http): List<Map<String, Any?>> {
            val r = client.post("/api/v1/tags/browse", """{"path":"[default]plant/tank"}""")
            assertEquals(200, r.status, r.body)
            return r.json["nodes"] as List<Map<String, Any?>>
        }

        // The administrator does not hold the tag's operator role: still listed by name and kind, but without
        // its data type (S-1), value, quality and timestamp.
        val denied = browseAs(http)
        val hidden = denied.single { it["name"] == "secret" }
        assertEquals("[default]plant/tank/secret", hidden["path"])
        assertEquals("tag", hidden["kind"])
        assertEquals(false, hidden["readable"])
        assertFalse(hidden.containsKey("dataType"), "$hidden")
        assertFalse(hidden.containsKey("value"), "$hidden")
        assertFalse(hidden.containsKey("quality"), "$hidden")
        assertFalse(hidden.containsKey("timestamp"), "$hidden")
        assertEquals(62.4, denied.single { it["name"] == "level" }["value"])

        // An operator is admitted and gets the row with its value.
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        val op = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        val shown = browseAs(op).single { it["name"] == "secret" }
        assertEquals("double", shown["dataType"])
        assertFalse(shown.containsKey("readable"), "only an unreadable row is flagged")
        assertEquals(7.0, shown["value"])
        assertEquals("Good", shown["quality"])
        assertNotNull(shown["timestamp"])
    }

    @Test fun aTagPathAnotherTagResourceHoldsIsRefusedWith409() {
        fun doc(name: String, path: String, version: Long) =
            """{"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"$name","module":"tags","version":$version},""" +
                """"spec":{"path":"$path","type":"memory","dataType":"Double","value":1.0}}"""
        fun read(path: String): Map<String, Any?> {
            val r = http.post("/api/v1/tags/read", """{"paths":["$path"]}""")
            assertEquals(200, r.status, r.body)
            @Suppress("UNCHECKED_CAST")
            return (r.json["values"] as List<Map<String, Any?>>).single()
        }

        // An update that moves a tag onto a path another resource holds (any case) is refused; both tags stay.
        val moved = http.put("/api/v1/config/tags/gateway/Tag/plant-pump-running", doc("plant-pump-running", "Plant/Tank/Level", 1))
        assertEquals(409, moved.status, moved.body)
        assertTrue(moved.body.contains("already used by tag 'plant-tank-level'"), moved.body)
        assertEquals(true, read("[default]plant/pump/running")["value"])
        assertEquals(62.4, read("[default]plant/tank/level")["value"])

        // A create on a taken path is refused too, and stores nothing.
        val created = http.post("/api/v1/config/tags/gateway/Tag", doc("other-level", "plant/tank/level", 0))
        assertEquals(409, created.status, created.body)
        assertTrue(created.body.contains("already used"), created.body)
        assertEquals(404, http.get("/api/v1/config/tags/gateway/Tag/other-level").status)

        // A resource name that is taken is still the plain name conflict.
        val sameName = http.post("/api/v1/config/tags/gateway/Tag", doc("plant-tank-level", "plant/tank/other", 0))
        assertEquals(409, sameName.status, sameName.body)
        assertTrue(sameName.body.contains("already exists"), sameName.body)

        // Saving a tag at its own path, and moving it to a free path, still work; the old path is then free.
        assertEquals(200, http.put("/api/v1/config/tags/gateway/Tag/plant-tank-level", doc("plant-tank-level", "plant/tank/level", 1)).status)
        assertEquals(200, http.put("/api/v1/config/tags/gateway/Tag/plant-tank-level", doc("plant-tank-level", "plant/tank/level2", 2)).status)
        assertEquals(201, http.post("/api/v1/config/tags/gateway/Tag", doc("other-level", "plant/tank/level", 0)).status)
        assertEquals("Good", read("[default]plant/tank/level")["quality"])
    }

    @Test fun browseLeavesOutTheResourceOfATagTheCallerMayNotRead() {
        val spec = """{"path":"plant/tank/hidden","type":"memory","dataType":"Double","value":1.0,"readPermissions":["operator"]}"""
        val r = http.put(
            "/api/v1/config/tags/gateway/Tag/hidden",
            """{"apiVersion":"oip/v1","kind":"Tag","metadata":{"name":"hidden","module":"tags","version":0},"spec":$spec}""",
        )
        assertEquals(201, r.status, r.body)
        val page = http.post("/api/v1/tags/browse", """{"path":"[default]plant/tank"}""")
        assertEquals(200, page.status, page.body)
        @Suppress("UNCHECKED_CAST")
        val nodes = page.json["nodes"] as List<Map<String, Any?>>
        assertFalse(nodes.single { it["name"] == "hidden" }.containsKey("resource"))
        assertEquals("plant-tank-level", nodes.single { it["name"] == "level" }["resource"])
    }

    @Test fun browseRequiresAuthentication() {
        val anonymous = Http(gw.port())
        assertEquals(401, anonymous.post("/api/v1/tags/browse", """{"path":"[default]"}""").status)
    }

    @Test fun browseIsInTheOpenApiDocument() {
        @Suppress("UNCHECKED_CAST") val paths = http.get("/api/docs").json["paths"] as Map<String, Any?>
        assertTrue("/api/v1/tags/browse" in paths)
    }

    @Test fun browseMalformedJsonIs400() {
        val r = http.post("/api/v1/tags/browse", "{not json")
        assertEquals(400, r.status)
    }
}
