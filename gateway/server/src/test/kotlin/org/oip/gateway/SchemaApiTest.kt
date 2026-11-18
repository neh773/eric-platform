package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path

class SchemaApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http
    private val module = "org.oip.test"

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0)
        gw.store.registry.register(module, "Widget", mapOf(
            "type" to "object",
            "properties" to mapOf(
                "size" to mapOf("type" to "integer", "minimum" to 1),
                "label" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 20),
            ),
        ))
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        gw.start()
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun returnsTheRegisteredSchema() {
        val r = http.get("/api/v1/schemas/$module/Widget")
        assertEquals(200, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/json"))
        assertEquals(module, r.json["module"])
        assertEquals("Widget", r.json["kind"])
        @Suppress("UNCHECKED_CAST") val schema = r.json["schema"] as Map<String, Any?>
        assertEquals("object", schema["type"])
        assertEquals(false, schema["additionalProperties"])
        @Suppress("UNCHECKED_CAST") val props = schema["properties"] as Map<String, Any?>
        assertTrue(props.containsKey("size"))
        assertTrue(props.containsKey("label"))
    }

    @Test fun unknownKindAndBadIdAre404() {
        assertProblem(http.get("/api/v1/schemas/$module/Missing"), 404)
        assertProblem(http.get("/api/v1/schemas/BAD/Widget"), 404)
    }

    @Test fun anonymousIs401AndViewerIs403() {
        val anon = Http(gw.port())
        assertProblem(anon.get("/api/v1/schemas/$module/Widget"), 401)
        val view = Http(gw.port())
        assertEquals(200, view.login("view", TEST_PASSWORD).status)
        assertProblem(view.get("/api/v1/schemas/$module/Widget"), 403)
    }

    @Test fun invalidSpecNamesTheField() {
        val body = """{"apiVersion":"oip/v1","kind":"Widget","metadata":{"name":"a","module":"$module","version":0},"spec":{"size":1,"label":""}}"""
        val r = http.post("/api/v1/config/$module/gateway/Widget", body)
        assertEquals(422, r.status, r.body)
        val errors = r.json["errors"] as List<*>
        assertEquals(listOf("$.label: must be at least 1 characters long"), errors.map { it.toString() })
    }

    @Test fun openApiListsTheSchemaPath() {
        val doc = http.get("/api/docs")
        assertEquals(200, doc.status, doc.body)
        val paths = doc.json["paths"] as Map<*, *>
        assertTrue(paths.containsKey("/api/v1/schemas/{module}/{kind}"))
        val schemas = ((doc.json["components"] as Map<*, *>)["schemas"] as Map<*, *>).keys
        assertTrue("KindSchema" in schemas)
    }

    private fun assertProblem(r: Http.Res, status: Int) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
    }
}
