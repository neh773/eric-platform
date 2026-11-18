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
 * `POST /api/v1/tags/search` (TAG-015, doc source: FEATURES TAG-015): the tag report searches by path glob, name,
 * data type and quality and returns table rows; a tag the caller may not read is never listed.
 */
class TagSearchApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        gw.start(); http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        memoryTag("plant/tank/level", "Double", 62.4)
        memoryTag("plant/tank/temp", "Double", 20.5)
        memoryTag("plant/pump/running", "Boolean", true)
        memoryTag("other/flag", "Boolean", false)
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

    @Suppress("UNCHECKED_CAST")
    private fun paths(body: String, client: Http = http): List<String> {
        val r = client.post("/api/v1/tags/search", body)
        assertEquals(200, r.status, r.body)
        return (r.json["rows"] as List<Map<String, Any?>>).map { it["path"] as String }
    }

    @Test fun pathGlobNameAndDataTypeFiltersCombine() {
        assertEquals(setOf("[default]plant/tank/level", "[default]plant/tank/temp"), paths("""{"path":"[default]plant/tank/*"}""").toSet())
        assertEquals(listOf("[default]plant/tank/temp"), paths("""{"path":"[default]plant/**","nameContains":"TEM"}"""))
        assertEquals(setOf("[default]plant/pump/running", "[default]other/flag"), paths("""{"dataType":"boolean"}""").toSet())
        assertEquals(listOf("[default]plant/tank/level"), paths("""{"path":"[default]plant/**","dataType":"double","nameContains":"lev"}"""))
    }

    @Test fun qualityFilterAndLimitAndColumns() {
        assertEquals(4, paths("""{"quality":"Good"}""").size)
        assertEquals(emptyList<String>(), paths("""{"quality":"Bad"}"""))
        val r = http.post("/api/v1/tags/search", """{"limit":2}""")
        assertEquals(200, r.status, r.body)
        assertEquals(true, r.json["truncated"])
        assertEquals(listOf("name", "path", "kind", "dataType", "value", "quality", "timestamp"), r.json["columns"])
        assertEquals(422, http.post("/api/v1/tags/search", """{"limit":0}""").status)
        assertEquals(422, http.post("/api/v1/tags/search", """{"path":5}""").status)
    }

    @Test fun requiresAnAuthenticatedCaller() {
        assertEquals(401, Http(gw.port()).post("/api/v1/tags/search", "{}").status)
    }
}
