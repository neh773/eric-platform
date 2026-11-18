package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path

/**
 * DAT-037: the worked example in docs/named-query-security-example.md. A query restricted to operator and
 * administrator returns rows to an operator and 403 to a viewer.
 */
class NamedQuerySecurityExampleTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        admin = Http(gw.port())
        assertEquals(200, admin.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun allowedCallReturnsRowsAndDeniedCallReturns403() {
        assertEquals(201, admin.post("/api/v1/db/connections", """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite::memory:","username":""}""").status)
        fun update(name: String, sql: String, params: String = "[]", body: String = "{}") {
            assertEquals(201, admin.post("/api/v1/db/queries", """{"name":"$name","connection":"plant","queryType":"update","sql":"$sql","parameters":$params}""").status)
            assertEquals(200, admin.post("/api/v1/db/queries/$name/execute", """{"parameters":$body}""").status)
        }
        update("mk", "CREATE TABLE output (line TEXT, units INTEGER)")
        update("add", "INSERT INTO output(line, units) VALUES ('A', 120)")
        val created = admin.post("/api/v1/db/queries", """
            {"name":"line-output","connection":"plant","queryType":"query","sql":"SELECT line, units FROM output WHERE line = :line",
             "parameters":[{"name":"line","dataType":"string"}],"roles":["operator","administrator"]}
        """.trimIndent())
        assertEquals(201, created.status, created.body)

        val op = Http(gw.port())
        val view = Http(gw.port())
        val anon = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(200, view.login("view", TEST_PASSWORD).status)
        val run = """{"parameters":{"line":"A"}}"""

        val allowed = op.post("/api/v1/db/queries/line-output/execute", run)
        assertEquals(200, allowed.status, allowed.body)
        assertEquals(listOf("line", "units"), allowed.json["columns"])
        val rows = allowed.json["rows"] as List<*>
        assertEquals(1, rows.size)
        assertEquals("A", (rows[0] as List<*>)[0])
        assertEquals(120, ((rows[0] as List<*>)[1] as Number).toInt())

        val denied = view.post("/api/v1/db/queries/line-output/execute", run)
        assertEquals(403, denied.status, denied.body)
        assertFalse(denied.body.contains("120"), "a denied call returns no rows")
        assertEquals(401, anon.post("/api/v1/db/queries/line-output/execute", run).status)
    }
}
