package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.DefaultRoles
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * PRO-004 project CRUD, PRO-002 inheritance through REST, and GAT-023 home page data over HTTP.
 */
class ProjectCrudApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        val dist = tmp.resolve("dist").createDirectories()
        dist.resolve("index.html").writeText("<!doctype html><div id=\"root\" data-oip-web=\"1\"></div>")
        val dir = tmp.resolve("gw").createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(dir, port = 0, adminDist = null, webDist = dist).start()
        http = Http(gw.port())
        gw.loginAdmin(http)
    }

    @AfterEach fun tearDown() = gw.close()

    private fun view(project: String, name: String, title: String, version: Long = 0) =
        http.put(
            "/api/v1/config/designer/project/$project/View/$name",
            """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"$name","module":"designer","version":$version},""" +
                """"spec":{"path":"$name","title":"$title","document":{"params":{},"root":{"id":"root","kind":"container","layout":"coordinate","children":[]}}}}""",
        )

    private fun create(name: String, extra: String = "") =
        http.post("/api/v1/projects", """{"name":"$name"${if (extra.isEmpty()) "" else ",$extra"}}""")

    private fun patch(name: String, body: String) = http.send("PATCH", "/api/v1/projects/$name", body.toByteArray())

    @Test fun createReadUpdateRenameCopyDeleteEachAuditedOnce() {
        val created = create("plant", "\"title\":\"Plant\",\"description\":\"Main line\"")
        assertEquals(201, created.status, created.body)
        assertEquals("Plant", created.json["title"])
        assertEquals(true, created.json["enabled"])
        assertEquals(409, create("plant").status)
        assertEquals(422, create("Bad Name").status)
        assertEquals(422, create("x", "\"colour\":\"red\"").status)
        assertEquals(1, gw.auditCount(AuditActions.PROJECT_CREATED))
        assertEquals(201, view("plant", "home", "Home").status)
        assertEquals(201, view("plant", "pumps", "Pumps").status)

        val listed = http.get("/api/v1/projects")
        assertEquals(listOf("plant"), listed.json["projects"])
        val item = (listed.json["items"] as List<*>).single() as Map<*, *>
        assertEquals("Main line", item["description"])
        assertEquals(3, (item["resources"] as Number).toInt())

        val disabled = patch("plant", """{"enabled":false,"title":"Plant A"}""")
        assertEquals(200, disabled.status, disabled.body)
        assertEquals(false, disabled.json["enabled"])
        assertEquals(false, disabled.json["launchable"])
        assertEquals(1, gw.auditCount(AuditActions.PROJECT_UPDATED))
        // A stale settings version is refused.
        val stale = http.send("PATCH", "/api/v1/projects/plant", """{"enabled":true}""".toByteArray(), headers = mapOf("If-Match" to "1"))
        assertEquals(409, stale.status, stale.body)
        assertEquals(200, patch("plant", """{"enabled":true}""").status)

        val copied = http.post("/api/v1/projects/plant/copy", """{"name":"plant-b","title":"Plant B"}""")
        assertEquals(201, copied.status, copied.body)
        assertEquals("Plant B", copied.json["title"])
        assertEquals(3, (copied.json["resources"] as Number).toInt())
        assertEquals(409, http.post("/api/v1/projects/plant/copy", """{"name":"plant-b"}""").status)
        assertEquals(1, gw.auditCount(AuditActions.PROJECT_COPIED))

        val renamed = http.post("/api/v1/projects/plant-b/rename", """{"name":"line-two"}""")
        assertEquals(200, renamed.status, renamed.body)
        assertEquals(404, http.get("/api/v1/config/designer/project/plant-b/View/home").status)
        assertEquals(200, http.get("/api/v1/config/designer/project/line-two/View/pumps").status)
        assertEquals(1, gw.auditCount(AuditActions.PROJECT_RENAMED))
        assertEquals(404, http.post("/api/v1/projects/missing/rename", """{"name":"other"}""").status)

        assertEquals(204, http.delete("/api/v1/projects/line-two").status)
        assertEquals(404, http.get("/api/v1/projects/line-two").status)
        assertEquals(200, http.get("/api/v1/config/designer/project/plant/View/home").status)
        assertEquals(listOf("plant"), http.get("/api/v1/projects").json["projects"])
        assertEquals(1, gw.auditCount(AuditActions.PROJECT_DELETED))
        assertEquals(404, http.delete("/api/v1/projects/line-two").status)
        // Copy and rename moved several resources each, and still wrote no per-resource config record.
        assertEquals(0, gw.auditCount(AuditActions.CONFIG_BATCH))
    }

    @Test fun inheritanceResolvesThroughTheConfigApiAndRefusesCyclesAndParentDeletion() {
        assertEquals(201, create("base", "\"inheritable\":true,\"runnable\":false").status)
        assertEquals(201, create("plant", "\"parent\":\"base\"").status)
        assertEquals(422, create("orphan", "\"parent\":\"nowhere\"").status)
        assertEquals(422, create("child2", "\"parent\":\"plant\"").status) // plant is not inheritable
        assertEquals(201, view("base", "home", "Base home").status)
        assertEquals(201, view("base", "alarms", "Alarms").status)

        val own = http.get("/api/v1/config/designer/project/plant/View")
        assertEquals(emptyList<Any>(), own.json["items"])
        val seen = http.get("/api/v1/config/designer/project/plant/View?inherited=true")
        val items = (seen.json["items"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("alarms", "home"), items.map { (it["metadata"] as Map<*, *>)["name"] })
        assertTrue(items.all { it["inheritedFrom"] == "base" && it["scope"] == "project/base" })
        val one = http.get("/api/v1/config/designer/project/plant/View/home?inherited=true")
        assertEquals(200, one.status)
        assertEquals("base", one.json["inheritedFrom"])
        assertEquals(404, http.get("/api/v1/config/designer/project/plant/View/home").status)
        assertEquals(422, http.get("/api/v1/config/designer/gateway/View?inherited=true").status)

        // Override in the child, then delete the override.
        assertEquals(201, view("plant", "home", "Plant home").status)
        val overridden = http.get("/api/v1/config/designer/project/plant/View/home?inherited=true")
        assertNull(overridden.json["inheritedFrom"])
        assertEquals("Plant home", (overridden.json["spec"] as Map<*, *>)["title"])
        assertEquals(204, http.delete("/api/v1/config/designer/project/plant/View/home").status)
        assertEquals("base", http.get("/api/v1/config/designer/project/plant/View/home?inherited=true").json["inheritedFrom"])

        // Cycle: base cannot inherit from its own child.
        assertEquals(200, patch("plant", """{"inheritable":true}""").status)
        val cycle = patch("base", """{"parent":"plant"}""")
        assertEquals(422, cycle.status, cycle.body)
        assertTrue(cycle.body.contains("cycle"), cycle.body)
        assertEquals(null, http.get("/api/v1/projects/base").json["parent"])

        // A parent in use is not deleted.
        val inUse = http.delete("/api/v1/projects/base")
        assertEquals(409, inUse.status, inUse.body)
        assertTrue(inUse.body.contains("plant"))
        assertEquals(200, http.get("/api/v1/config/designer/project/base/View/home").status)
        assertEquals(0, gw.auditCount(AuditActions.PROJECT_DELETED))

        val info = http.get("/api/v1/projects/base").json
        assertEquals(listOf("plant"), info["children"])
        assertEquals(listOf("plant", "base"), http.get("/api/v1/projects/plant").json["inheritanceChain"])

        // Renaming the parent repoints the child.
        assertEquals(200, http.post("/api/v1/projects/base/rename", """{"name":"core"}""").status)
        assertEquals("core", http.get("/api/v1/projects/plant").json["parent"])
        assertEquals("core", http.get("/api/v1/config/designer/project/plant/View/alarms?inherited=true").json["inheritedFrom"])
    }

    @Test fun exportCarriesTheParentAndImportChecksIt() {
        assertEquals(201, create("base", "\"inheritable\":true").status)
        assertEquals(201, create("plant", "\"parent\":\"base\",\"title\":\"Plant\"").status)
        val zip = http.get("/api/v1/projects/plant/export").raw
        val copy = http.postRaw("/api/v1/projects/plant-c/import?policy=skip", zip, "application/zip")
        assertEquals(200, copy.status, copy.body)
        assertEquals("base", http.get("/api/v1/projects/plant-c").json["parent"])

        // Into a gateway without the parent, the import is refused and writes nothing.
        val otherDir = tmp.resolve("other").createDirectories()
        otherDir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  enabled: false\n")
        Gateway(otherDir, port = 0).start().use { other ->
            val h = Http(other.port())
            other.loginAdmin(h)
            val refused = h.postRaw("/api/v1/projects/plant/import?policy=skip", zip, "application/zip")
            assertEquals(422, refused.status, refused.body)
            assertTrue(refused.body.contains("does not exist"))
            assertEquals(emptyList<Any>(), h.get("/api/v1/projects").json["projects"])
        }
    }

    @Test fun aDisabledOrNonRunnableProjectIsNotServedAtHmi() {
        assertEquals(201, create("plant").status)
        assertEquals(200, http.get("/hmi/plant/").status)
        assertEquals(200, http.get("/hmi/plant/pumps").status)
        assertEquals(200, patch("plant", """{"enabled":false}""").status)
        assertEquals(404, http.get("/hmi/plant/").status)
        assertEquals(404, http.get("/hmi/plant/pumps").status)
        assertEquals(200, patch("plant", """{"enabled":true,"runnable":false}""").status)
        assertEquals(404, http.get("/hmi/plant/").status)
        assertEquals(200, patch("plant", """{"runnable":true}""").status)
        assertEquals(200, http.get("/hmi/plant/").status)
        // A name with no project still gets the shell, which reports it missing.
        assertEquals(200, http.get("/hmi/unknown/").status)
    }

    @Test fun readersSeeProjectsButCannotChangeThem() {
        assertEquals(201, create("plant").status)
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        val op = Http(gw.port()).also { assertEquals(200, it.login("op", TEST_PASSWORD).status) }
        assertEquals(200, op.get("/api/v1/projects/plant").status)
        assertEquals(403, op.post("/api/v1/projects", """{"name":"x"}""").status)
        assertEquals(403, op.send("PATCH", "/api/v1/projects/plant", """{"enabled":false}""".toByteArray()).status)
        assertEquals(403, op.post("/api/v1/projects/plant/copy", """{"name":"y"}""").status)
        assertEquals(403, op.post("/api/v1/projects/plant/rename", """{"name":"y"}""").status)
        assertEquals(403, op.delete("/api/v1/projects/plant").status)
        assertEquals(listOf("plant"), http.get("/api/v1/projects").json["projects"])
        assertEquals(0, gw.auditCount(AuditActions.PROJECT_DELETED))
    }

    @Test fun homeListsLaunchableProjectsAndRespectsPermissions() {
        assertEquals(401, Http(gw.port()).get("/api/v1/home").status)
        val fresh = http.get("/api/v1/home")
        assertEquals(200, fresh.status, fresh.body)
        val setup = fresh.json["setup"] as Map<*, *>
        assertEquals(false, setup["complete"])
        val steps = (setup["steps"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("system-name", "users", "plc-resources", "project"), steps.map { it["id"] })
        assertTrue(steps.none { it["done"] == true })
        assertEquals(listOf("admin", "designer", "hmi"), (fresh.json["links"] as List<*>).map { (it as Map<*, *>)["id"] })

        assertEquals(201, create("plant", "\"title\":\"Plant floor\"").status)
        assertEquals(201, create("base", "\"inheritable\":true,\"runnable\":false").status)
        assertEquals(201, create("off", "\"enabled\":false").status)
        val after = http.get("/api/v1/home")
        val projects = (after.json["projects"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("plant"), projects.map { it["name"] })
        assertEquals("Plant floor", projects.single()["title"])
        assertEquals("/hmi/plant/", projects.single()["hmi"])
        assertEquals("/studio?project=plant", projects.single()["designer"])
        val projectStep = ((after.json["setup"] as Map<*, *>)["steps"] as List<*>).map { it as Map<*, *> }.single { it["id"] == "project" }
        assertEquals(true, projectStep["done"])

        val done = http.put("/api/v1/home/setup", """{"complete":true}""")
        assertEquals(200, done.status, done.body)
        assertEquals(true, (done.json["setup"] as Map<*, *>)["complete"])
        assertEquals(422, http.put("/api/v1/home/setup", """{"complete":"yes"}""").status)

        // An operator (gateway.read) sees the projects and HMI link but no designer link and no setup.
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        val op = Http(gw.port()).also { assertEquals(200, it.login("op", TEST_PASSWORD).status) }
        val opHome = op.get("/api/v1/home").json
        assertNull(opHome["setup"])
        assertEquals(listOf("admin", "hmi"), (opHome["links"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertNull(((opHome["projects"] as List<*>).single() as Map<*, *>)["designer"])
        assertEquals(403, op.put("/api/v1/home/setup", """{"complete":false}""").status)

        // A viewer without gateway.read gets no project list.
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        val viewer = Http(gw.port()).also { assertEquals(200, it.login("view", TEST_PASSWORD).status) }
        val viewHome = viewer.get("/api/v1/home").json
        assertEquals(emptyList<Any>(), viewHome["projects"])
        assertEquals(true, viewHome["projectsHidden"])
    }

    @Test fun rootRedirectsToTheHomePage() {
        val res = Http(gw.port()).get("/")
        assertEquals(302, res.status)
        assertEquals("/admin/home", res.header("Location"))
    }

    @Test fun newRoutesHaveExplicitRulesAndAreDocumented() {
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/projects/plant"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/projects"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("PATCH", "/api/v1/projects/plant"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("DELETE", "/api/v1/projects/plant"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/projects/plant/rename"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/projects/plant/copy"))
        assertEquals("authenticated", GatewayAuth.explicitRule("GET", HomeApi.PATH))
        assertEquals("gateway.write", GatewayAuth.explicitRule("PUT", HomeApi.SETUP_PATH))
        assertNull(GatewayAuth.explicitRule("POST", HomeApi.PATH))
        val doc = OpenApi.document.toString()
        for (path in listOf("/api/v1/projects/{name}/rename", "/api/v1/projects/{name}/copy", HomeApi.PATH, HomeApi.SETUP_PATH)) {
            assertTrue(doc.contains(path), path)
        }
    }
}
