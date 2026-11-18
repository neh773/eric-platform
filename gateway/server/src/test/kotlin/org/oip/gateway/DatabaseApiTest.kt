package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * REST for database connections and named queries, behind the existing session and role checks.
 */
class DatabaseApiTest {
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

    @Test fun passwordIsStoredAsASecretAndQueriesStayParameterized() {
        val secret = "Unique-db-pass-9f3a"
        http.sendCsrf = false
        assertEquals(403, http.post("/api/v1/db/connections", connectionBody(secret)).status)
        http.sendCsrf = true

        val created = http.post("/api/v1/db/connections", connectionBody(secret))
        assertEquals(201, created.status, created.body)
        assertEquals(true, created.json["passwordSet"])
        assertEquals("Valid", created.json["status"])
        assertFalse(created.body.contains(secret))
        assertFalse(created.json.containsKey("password"))
        val yaml = tmp.resolve("config/db/gateway/DatabaseConnection/plant.yaml").readText()
        assertTrue(yaml.contains("secret://db-plant"))
        assertFalse(yaml.contains(secret))

        val listed = http.get("/api/v1/db/connections")
        assertEquals(200, listed.status, listed.body)
        assertFalse(listed.body.contains(secret))
        val config = http.get("/api/v1/config/db/gateway/DatabaseConnection/plant")
        assertEquals(200, config.status, config.body)
        assertTrue(config.body.contains("secret://db-plant"))
        assertFalse(config.body.contains(secret))

        val planted = http.post(
            "/api/v1/config/db/gateway/DatabaseConnection",
            """{"apiVersion":"oip/v1","kind":"DatabaseConnection","metadata":{"name":"bad","module":"db","version":0},"spec":{"driver":"sqlite","url":"jdbc:sqlite::memory:","password":"$secret"}}""",
        )
        assertEquals(422, planted.status, planted.body)

        assertEquals(201, http.post("/api/v1/db/queries", """
            {"name":"create","connection":"plant","queryType":"update","sql":"CREATE TABLE users (name TEXT PRIMARY KEY, secret TEXT NOT NULL)"}
        """.trimIndent()).status)
        assertEquals(200, http.post("/api/v1/db/queries/create/execute", """{"parameters":{}}""").status)
        assertEquals(201, http.post("/api/v1/db/queries", """
            {"name":"add","connection":"plant","queryType":"update","sql":"INSERT INTO users(name, secret) VALUES (:name, :secret)","parameters":[{"name":"name","dataType":"string"},{"name":"secret","dataType":"string"}]}
        """.trimIndent()).status)
        assertEquals(200, http.post("/api/v1/db/queries/add/execute", """{"parameters":{"name":"ada","secret":"ok"}}""").status)
        assertEquals(201, http.post("/api/v1/db/queries", """
            {"name":"find","connection":"plant","queryType":"scalar","sql":"SELECT secret FROM users WHERE name = :name","parameters":[{"name":"name","dataType":"string"}],"roles":["operator","administrator"]}
        """.trimIndent()).status)

        val found = http.post("/api/v1/db/queries/find/execute", """{"parameters":{"name":"ada"}}""")
        assertEquals(200, found.status, found.body)
        assertEquals("ok", found.json["value"])
        val injected = http.post("/api/v1/db/queries/find/execute", """{"parameters":{"name":"ada' OR '1'='1"}}""")
        assertEquals(200, injected.status, injected.body)
        assertEquals(null, injected.json["value"])
        val count = http.post("/api/v1/db/queries", """
            {"name":"count","connection":"plant","queryType":"scalar","sql":"SELECT COUNT(*) FROM users","roles":["operator","administrator"]}
        """.trimIndent())
        assertEquals(201, count.status, count.body)
        val left = http.post("/api/v1/db/queries/count/execute", """{"parameters":{}}""")
        assertEquals(1, (left.json["value"] as Number).toInt())

        assertPasswordAbsent(secret)
    }

    /** PRO-001: a project's named queries are its own; a session names its project, and the nearest project that stores the query wins. */
    @Test fun aProjectsNamedQueriesAreItsOwnAndInheritThroughItsParent() {
        assertEquals(201, http.post("/api/v1/db/connections", connectionBody(null)).status)
        assertEquals(201, http.post("/api/v1/db/queries", """{"name":"who","connection":"plant","queryType":"scalar","sql":"SELECT 'gateway'"}""").status)
        val actor = "test"
        gw.projectService.create("base", org.oip.config.ProjectService.Settings(inheritable = true, runnable = false), actor)
        gw.projectService.create("plant-a", org.oip.config.ProjectService.Settings(parent = "base"), actor)
        gw.projectService.create("plant-b", org.oip.config.ProjectService.Settings(), actor)
        fun query(project: String, name: String, answer: String) {
            val id = org.oip.config.ResourceId("db", org.oip.config.Scope.project(project), "NamedQuery", name)
            val version = gw.store.get(id)?.metadata?.version ?: 0L
            gw.store.put(
                org.oip.config.Scope.project(project),
                org.oip.config.Resource("NamedQuery", org.oip.config.Metadata(name, "db", version = version), mapOf("connection" to "plant", "queryType" to "scalar", "sql" to "SELECT '$answer'")),
            )
        }
        query("base", "who", "base")
        query("base", "only-base", "inherited")
        query("plant-a", "who", "a")
        query("plant-b", "who", "b")
        fun run(name: String, project: String?): String? {
            val extra = if (project == null) "" else ""","project":"$project""""
            val res = http.post("/api/v1/db/queries/$name/execute", """{"parameters":{}$extra}""")
            assertEquals(200, res.status, res.body)
            return res.json["value"] as String?
        }
        assertEquals("gateway", run("who", null))
        assertEquals("a", run("who", "plant-a"))
        assertEquals("b", run("who", "plant-b"))
        assertEquals("inherited", run("only-base", "plant-a"))
        assertEquals(404, http.post("/api/v1/db/queries/only-base/execute", """{"parameters":{},"project":"plant-b"}""").status)
        assertEquals(400, http.post("/api/v1/db/queries/who/execute", """{"parameters":{},"project":"Not A Project"}""").status)
        // Saving a project's query takes effect on its next run; the other project is unchanged.
        query("plant-a", "who", "a2")
        assertEquals("a2", run("who", "plant-a"))
        assertEquals("b", run("who", "plant-b"))
    }

    /** DAT-035: an ad hoc script call converts to a named query; only gateway.write may convert. */
    @Test fun convertsAnAdHocScriptCallToANamedQuery() {
        assertEquals(201, http.post("/api/v1/db/connections", connectionBody(null)).status)
        val script = "rows = system.db.runPrepQuery(\"SELECT 1 AS n WHERE CAST(? AS INTEGER) < 5\", [limit], \"plant\")"
        val body = """{"name":"above","script":${Json.write(script)}}"""
        val preview = http.post("/api/v1/db/queries/convert", body)
        assertEquals(200, preview.status, preview.body)
        assertEquals("rows = system.db.runNamedQuery(\"above\", {\"limit\": limit})", preview.json["script"])
        assertFalse(http.get("/api/v1/db/queries").body.contains("\"above\""), "a preview stores nothing")
        val op = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(403, op.post("/api/v1/db/queries/convert", body).status)
        assertEquals(400, http.post("/api/v1/db/queries/convert", """{"name":"above"}""").status)
        val made = http.post("/api/v1/db/queries/convert", """{"name":"above","script":${Json.write(script)},"create":true}""")
        assertEquals(201, made.status, made.body)
        val ran = http.post("/api/v1/db/queries/above/execute", """{"parameters":{"limit":"3"}}""")
        assertEquals(200, ran.status, ran.body)
        assertEquals(listOf(listOf(1)), (ran.json["rows"] as List<*>).map { row -> (row as List<*>).map { (it as Number).toInt() } })
    }

    @Test fun rolesGateExecutionAndManagement() {
        val op = Http(gw.port())
        val view = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(200, view.login("view", TEST_PASSWORD).status)
        val anon = Http(gw.port())

        assertEquals(401, anon.get("/api/v1/db/connections").status)
        assertEquals(403, view.get("/api/v1/db/connections").status)
        assertEquals(200, op.get("/api/v1/db/connections").status)
        assertEquals(403, op.post("/api/v1/db/connections", connectionBody("ignored-secret-value")).status)

        assertEquals(201, http.post("/api/v1/db/connections", connectionBody(null)).status)
        assertEquals(201, http.post("/api/v1/db/queries", """
            {"name":"one","connection":"plant","queryType":"scalar","sql":"SELECT 1","roles":["operator"]}
        """.trimIndent()).status)
        assertEquals(401, anon.post("/api/v1/db/queries/one/execute", """{"parameters":{}}""").status)
        assertEquals(403, view.post("/api/v1/db/queries/one/execute", """{"parameters":{}}""").status)
        val ran = op.post("/api/v1/db/queries/one/execute", """{"parameters":{}}""")
        assertEquals(200, ran.status, ran.body)
        assertEquals(1, (ran.json["value"] as Number).toInt())
        assertEquals(403, http.post("/api/v1/db/queries/one/execute", """{"parameters":{}}""").status)
    }

    /**
     * C-17 query browser: gateway.write only, reads are read-only, writes need allowBrowserWrites.
     */
    @Test fun queryBrowserIsReadOnlyUnlessTheConnectionAllowsWrites() {
        val secret = "Browse-pass-7c1e"
        assertEquals(201, http.post("/api/v1/db/connections", connectionBody(secret)).status)
        val browse = "/api/v1/db/connections/plant/browse"
        val created = http.post(browse, """{"sql":"CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)"}""")
        assertEquals(403, created.status, created.body)
        assertTrue(created.body.contains("does not allow write statements"), created.body)

        val allow = http.put("/api/v1/db/connections/plant", """{"driver":"sqlite","url":"jdbc:sqlite::memory:","allowBrowserWrites":true}""")
        assertEquals(200, allow.status, allow.body)
        assertEquals(true, allow.json["allowBrowserWrites"])
        assertEquals(true, allow.json["passwordSet"], "omitting password keeps the stored secret")
        assertEquals(200, http.post(browse, """{"sql":"CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)"}""").status)
        val inserted = http.post(browse, """{"sql":"INSERT INTO t(name) VALUES ('a'), ('b'), ('c')"}""")
        assertEquals(200, inserted.status, inserted.body)
        assertEquals("update", inserted.json["type"])
        assertEquals(3, (inserted.json["rowsAffected"] as Number).toInt())

        val rows = http.post(browse, """{"sql":"SELECT id, name FROM t ORDER BY id","maxRows":2}""")
        assertEquals(200, rows.status, rows.body)
        assertEquals("query", rows.json["type"])
        assertEquals(listOf("id", "name"), rows.json["columns"])
        assertEquals(listOf(listOf(1, "a"), listOf(2, "b")), rows.json["rows"])
        assertEquals(true, rows.json["truncated"])

        assertEquals(200, http.put("/api/v1/db/connections/plant", """{"driver":"sqlite","url":"jdbc:sqlite::memory:","allowBrowserWrites":false}""").status)
        assertEquals(403, http.post(browse, """{"sql":"DELETE FROM t"}""").status)
        assertEquals(422, http.post(browse, """{"sql":"WITH x AS (SELECT 1) DELETE FROM t"}""").status)
        assertEquals(422, http.post(browse, """{"sql":"SELECT 1; DELETE FROM t"}""").status)
        assertEquals(400, http.post(browse, """{"sql":"SELECT 1","extra":1}""").status)
        assertEquals(400, http.post(browse, """{"maxRows":1}""").status)
        assertEquals(404, http.post("/api/v1/db/connections/nope/browse", """{"sql":"SELECT 1"}""").status)
        assertEquals(3, ((http.post(browse, """{"sql":"SELECT COUNT(*) FROM t"}""").json["rows"] as List<*>)[0] as List<*>)[0])

        val op = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(403, op.post(browse, """{"sql":"SELECT 1"}""").status)
        assertEquals(401, Http(gw.port()).post(browse, """{"sql":"SELECT 1"}""").status)
        http.sendCsrf = false
        assertEquals(403, http.post(browse, """{"sql":"SELECT 1"}""").status)
        http.sendCsrf = true

        assertFalse(http.get("/api/v1/db/connections").body.contains(secret))
        assertPasswordAbsent(secret)
        val docs = http.get("/api/docs")
        assertTrue(docs.body.contains("/api/v1/db/connections/{name}/browse"))
        assertTrue(docs.body.contains("QueryProcedure"))
    }

    /** C-17: a named query of type procedure is stored and listed; SQLite refuses the call with a clear 422. */
    @Test fun procedureNamedQueryOverRest() {
        assertEquals(201, http.post("/api/v1/db/connections", connectionBody(null)).status)
        val created = http.post("/api/v1/db/queries", """
            {"name":"adjust","connection":"plant","queryType":"procedure","procedure":"plant.adjust","parameters":[{"name":"line","dataType":"string"},{"name":"total","dataType":"integer","mode":"inout"}]}
        """.trimIndent())
        assertEquals(201, created.status, created.body)
        assertEquals("plant.adjust", created.json["procedure"])
        assertEquals("procedure", created.json["routine"])
        assertFalse(created.json.containsKey("sql"))
        val ran = http.post("/api/v1/db/queries/adjust/execute", """{"parameters":{"line":"L1","total":1}}""")
        assertEquals(422, ran.status, ran.body)
        assertTrue(ran.body.contains("SQLite has no stored procedures"), ran.body)
        val bad = http.post("/api/v1/db/queries", """{"name":"bad","connection":"plant","queryType":"procedure","procedure":"x; DROP TABLE y"}""")
        assertEquals(422, bad.status, bad.body)
    }

    private fun connectionBody(password: String?): String {
        val passwordField = if (password == null) "" else ""","password":"$password""""
        return """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite::memory:","username":"","description":"plant"$passwordField}"""
    }

    private fun assertPasswordAbsent(secret: String) {
        Files.walk(tmp).use { paths ->
            paths.filter { it.isRegularFile() && !lockedDatabase(it) }.forEach { file ->
                val text = Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
                assertFalse(text.contains(secret), file.toString())
            }
        }
    }

    /** A live SQLite file holds a range lock on Windows, so a raw read is not a config scan. */
    private fun lockedDatabase(file: Path): Boolean {
        val name = file.fileName.toString()
        return name.endsWith(".sqlite") || name.endsWith(".sqlite-wal") || name.endsWith(".sqlite-shm") ||
            name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm") || name == "gateway.lock"
    }
}
