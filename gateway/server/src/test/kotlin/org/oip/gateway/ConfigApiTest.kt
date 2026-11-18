package org.oip.gateway

import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path

class ConfigApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http
    private val m = "org.oip.test"
    private val base = "/api/v1/config/$m/gateway/Widget"

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0)
        gw.store.registry.register(m, "Widget", mapOf("type" to "object", "required" to listOf("size"),
            "properties" to mapOf("size" to mapOf("type" to "integer", "minimum" to 1), "color" to mapOf("type" to "string"))))
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.start(); http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }
    @AfterEach fun tearDown() = gw.close()

    private fun doc(name: String, spec: String, version: Long = 0) =
        """{"apiVersion":"oip/v1","kind":"Widget","metadata":{"name":"$name","module":"$m","version":$version},"spec":$spec}"""

    private fun assertProblem(r: Http.Res, status: Int) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
        val p = r.json
        assertEquals(status, p["status"]); assertNotNull(p["title"]); assertNotNull(p["type"]); assertNotNull(p["detail"])
    }

    @Test fun postCreatesThenGetReturnsIt() {
        val r = http.post(base, doc("a", """{"size":2}"""))
        assertEquals(201, r.status, r.body)
        assertEquals("$base/a", r.header("Location"))
        assertEquals("\"1\"", r.header("ETag"))
        val g = http.get("$base/a")
        assertEquals(200, g.status)
        @Suppress("UNCHECKED_CAST") val md = g.json["metadata"] as Map<String, Any?>
        assertEquals(1, md["version"]); assertEquals("admin", md["modifiedBy"])
        assertEquals(mapOf("size" to 2), g.json["spec"])
    }

    @Test fun postExistingIs409() {
        http.post(base, doc("a", """{"size":2}"""))
        assertProblem(http.post(base, doc("a", """{"size":3}""")), 409)
    }

    @Test fun putCreatesWithVersion0AndUpdatesWithCurrentVersion() {
        assertEquals(201, http.put("$base/b", doc("b", """{"size":1}""")).status)
        val u = http.put("$base/b", doc("b", """{"size":5}""", version = 1))
        assertEquals(200, u.status, u.body)
        assertEquals(2, (u.json["metadata"] as Map<*, *>)["version"])
        assertEquals(5, (gw.store.list(m).single().resource.spec["size"]))
    }

    @Test fun putStaleVersionIs409() {
        http.put("$base/b", doc("b", """{"size":1}"""))
        http.put("$base/b", doc("b", """{"size":2}""", 1))
        assertProblem(http.put("$base/b", doc("b", """{"size":3}""", 1)), 409)
    }

    @Test fun putNameMismatchIs422() = assertProblem(http.put("$base/b", doc("c", """{"size":1}""")), 422)

    @Test fun invalidSpecIs422WithErrors() {
        val r = http.post(base, doc("a", """{"size":0}"""))
        assertProblem(r, 422)
        assertTrue((r.json["errors"] as List<*>).isNotEmpty())
        assertProblem(http.post(base, doc("a", """{"size":1,"extra":true}""")), 422)
    }

    @Test fun unknownKindIs422() =
        assertProblem(http.post("/api/v1/config/$m/gateway/Gadget", doc("a", "{}").replace("Widget", "Gadget")), 422)

    @Test fun malformedJsonIs400() = assertProblem(http.post(base, "{not json"), 400)

    @Test fun getMissingIs404() = assertProblem(http.get("$base/nope"), 404)
    @Test fun deleteMissingIs404() = assertProblem(http.delete("$base/nope"), 404)
    @Test fun badPathIs404() = assertProblem(http.get("/api/v1/config/$m/galaxy/Widget/a"), 404)

    @Test fun patchMergesSpec() {
        http.post(base, doc("a", """{"size":2,"color":"red"}"""))
        val r = http.send("PATCH", "$base/a", """{"color":null,"size":4}""".toByteArray(), "application/merge-patch+json")
        assertEquals(200, r.status, r.body)
        assertEquals(mapOf("size" to 4), r.json["spec"])
        assertProblem(http.send("PATCH", "$base/a", """{"size":-1}""".toByteArray(), "application/merge-patch+json"), 422)
        assertProblem(http.send("PATCH", "$base/a", """{"size":3}""".toByteArray(), "application/merge-patch+json", mapOf("If-Match" to "\"1\"")), 409)
        assertProblem(http.send("PATCH", "$base/zz", "{}".toByteArray(), "application/merge-patch+json"), 404)
    }

    @Test fun deleteRemovesAndHonoursIfMatch() {
        http.post(base, doc("a", """{"size":2}"""))
        assertProblem(http.delete("$base/a", mapOf("If-Match" to "\"7\"")), 409)
        assertEquals(204, http.delete("$base/a", mapOf("If-Match" to "\"1\"")).status)
        assertProblem(http.get("$base/a"), 404)
        assertTrue(gw.store.list(m).isEmpty())
    }

    @Test fun listFiltersByModuleAndKind() {
        http.post(base, doc("a", """{"size":2}""")); http.post(base, doc("b", """{"size":3}"""))
        val all = http.get("/api/v1/config?module=$m&kind=Widget")
        assertEquals(200, all.status)
        val items = all.json["items"] as List<*>
        assertEquals(listOf("a", "b"), items.map { ((it as Map<*, *>)["metadata"] as Map<*, *>)["name"] }.sortedBy { it.toString() })
        assertEquals(2, (http.get(base).json["items"] as List<*>).size)
        assertEquals(0, (http.get("/api/v1/config?kind=Nope").json["items"] as List<*>).size)
    }

    @Test fun projectScopeRoundTrip() {
        val p = "/api/v1/config/$m/project/demo/Widget"
        assertEquals(201, http.post(p, doc("w", """{"size":9}""")).status)
        assertEquals(200, http.get("$p/w").status)
        assertProblem(http.get("$base/w"), 404)
    }

    @Test fun openApiServedAndValidAgainstOas31Schema() {
        val r = http.get("/api/docs")
        assertEquals(200, r.status)
        val doc = r.json
        assertTrue((doc["openapi"] as String).startsWith("3.1"))
        val paths = (doc["paths"] as Map<*, *>).keys
        assertTrue("/api/v1/health" in paths); assertTrue("/api/v1/config/{module}/gateway/{kind}/{name}" in paths); assertTrue("/api/v1/config/{module}/project/{project}/{kind}/{name}" in paths)
        // Review-1 F5 and F11 routes.
        assertTrue("/api/v1/modules/{id}/install" in paths); assertTrue("/api/v1/deployment-mode" in paths)
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { b ->
            b.schemaMappers { it.mapPrefix("https://spec.openapis.org/oas/3.1/schema/2022-10-07", "classpath:oas-3.1-schema.json") } }
        val schema = factory.getSchema(SchemaLocation.of("https://spec.openapis.org/oas/3.1/schema/2022-10-07"))
        val errors = schema.validate(Json.mapper.readTree(r.body))
        assertTrue(errors.isEmpty(), errors.toString())
        // a deliberately broken doc must fail, so the validation is real
        assertFalse(schema.validate(Json.mapper.readTree("""{"openapi":"3.1.0","paths":{"x":1}}""")).isEmpty())
    }

    /** Clean-room rule: the served API document carries no vendor doc links or product names. */
    @Test fun openApiHasNoVendorReferences() {
        val r = http.get("/api/docs")
        assertEquals(200, r.status)
        val vendor = Regex(
            listOf(
                "ign" + "ition",
                "induct" + "iveautomation",
                "induct" + "ive automation",
                "\\bperspec" + "tive\\b",
                "\\bvis" + "ion\\b",
                "sql bridge",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )
        val hits = vendor.findAll(r.body).map { r.body.substring(maxOf(0, it.range.first - 60), minOf(r.body.length, it.range.last + 60)) }.toList()
        assertTrue(hits.isEmpty(), hits.joinToString("\n"))
    }
}
