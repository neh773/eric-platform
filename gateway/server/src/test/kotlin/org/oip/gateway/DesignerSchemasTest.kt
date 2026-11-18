package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The Designer persists a view as a `View` config resource (module `designer`, [registerDesignerSchemas]),
 * `project/{project}` scope, through the generic [ConfigApi] CRUD -- the same mechanism
 * `gateway/tags` uses for `Tag`/`Udt`. This is the server side of the Designer's save/reload flow
 * (web/designer e2e `persistence.spec.ts` exercises the same resource through the browser).
 */
class DesignerSchemasTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http
    private val base = "/api/v1/config/designer/project/demo/View"

    @BeforeEach fun setUp() {
        // OPC UA defaults to a fixed port (4840); this test does not exercise it, so turn it off
        // to avoid colliding with another test's gateway on the same port in the same JVM.
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.start(); http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() = gw.close()

    private fun doc(name: String, path: String, version: Long = 0) =
        """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"$name","module":"designer","version":$version},
            "spec":{"path":"$path","document":{"params":{},"root":{"id":"root","kind":"container","layout":"coordinate","children":[]}}}}"""

    @Test fun createThenGetReturnsTheView() {
        val r = http.post(base, doc("overview", "Overview"))
        assertEquals(201, r.status, r.body)
        val g = http.get("$base/overview")
        assertEquals(200, g.status)
        @Suppress("UNCHECKED_CAST") val spec = g.json["spec"] as Map<String, Any?>
        assertEquals("Overview", spec["path"])
    }

    @Test fun updateSurvivesAFreshGatewayOpenOnTheSameDataDir() {
        http.post(base, doc("overview", "Overview"))
        gw.close()

        gw = Gateway(tmp, port = 0)
        gw.start(); http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        val g = http.get("$base/overview")
        assertEquals(200, g.status, g.body)
        @Suppress("UNCHECKED_CAST") val spec = g.json["spec"] as Map<String, Any?>
        assertEquals("Overview", spec["path"])
    }

    @Test fun listsAllViewsInAProject() {
        http.post(base, doc("overview", "Overview"))
        http.post(base, doc("pumps", "Pumps"))
        val r = http.get(base)
        assertEquals(200, r.status, r.body)
        @Suppress("UNCHECKED_CAST") val items = r.json["items"] as List<Map<String, Any?>>
        assertEquals(setOf("overview", "pumps"), items.map { (it["metadata"] as Map<*, *>)["name"] }.toSet())
    }

    /** Review-4 F3: a url navigation action that is not a page path or an http(s) URL is refused at save. */
    @Test fun aScriptOrDataNavigationUrlIsRefusedOnSave() {
        fun view(name: String, url: String, version: Long = 0) =
            """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"$name","module":"designer","version":$version},
                "spec":{"path":"$name","document":{"params":{},"root":{"id":"root","kind":"container","layout":"coordinate","children":[
                {"id":"go","kind":"component","type":"button","events":[{"event":"onClick","actions":[{"kind":"navigation","mode":"url","url":${Json.write(url)}}]}]}]}}}}"""
        for (url in listOf("javascript:alert(1)", " JavaScript:alert(document.cookie)", "data:text/html,<script>x</script>", "vbscript:msgbox(1)", "//evil.example/", "/\\evil.example", "java\tscript:alert(1)")) {
            val r = http.post(base, view("bad", url))
            assertEquals(422, r.status, "$url: ${r.body}")
            assertTrue(r.body.contains("page path"), r.body)
        }
        assertEquals(404, http.get("$base/bad").status)
        assertEquals(201, http.post(base, view("good", "https://example.com/manual")).status)
        assertEquals(200, http.put("$base/good", view("good", "/pumps?line=3", 1)).status)
        // An existing view cannot be changed to carry one either.
        assertEquals(422, http.put("$base/good", view("good", "javascript:alert(1)", 2)).status)
    }

    /** P4-07 (HMP-093): a component or container style outside the CSS subset is refused on save. */
    @Test fun aStyleOutsideTheSubsetIsRefusedOnSave() {
        fun view(name: String, style: String, version: Long = 0) =
            """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"$name","module":"designer","version":$version},
                "spec":{"path":"$name","document":{"params":{},"root":{"id":"root","kind":"container","layout":"coordinate","style":{"padding":4},"children":[
                {"id":"t","kind":"component","type":"label","style":$style,
                 "bindings":{"text":{"kind":"tag","mode":"direct","path":"a","transforms":[{"kind":"format","type":"datetime","mode":"date","style":"short"}]}}}]}}}}"""
        val good = """{"color":"#c0392b","background":"var(--oip-color-surface)","borderRadius":6,"borderStyle":"dashed","opacity":0.5,"fontFamily":"'Plex Sans', sans-serif"}"""
        assertEquals(201, http.post(base, view("styled", good)).status)
        for (bad in listOf(
            """{"position":"fixed"}""",
            """{"background":"url(https://example.test/a.png)"}""",
            """{"color":"red; position: fixed"}""",
            """{"color":"expression(alert(1))"}""",
            """{"fontSize":"12px"}""",
            """{"opacity":3}""",
            """{"textAlign":"middle"}""",
            """{"fontFamily":"x</style>"}""",
        )) {
            val r = http.post(base, view("bad", bad))
            assertEquals(422, r.status, "$bad: ${r.body}")
            assertTrue(r.body.contains("spec.document.root.children[0].style"), r.body)
        }
        assertEquals(404, http.get("$base/bad").status)
        assertEquals(422, http.put("$base/styled", view("styled", """{"zIndex":9}""", 1)).status)
    }

    @Test fun styleColorsFollowTheWebRule() {
        for (color in listOf("#fff", "#ffffff80", "red", "transparent", "currentColor", "rgba(0,0,0,0.5)", "hsl(120deg 50% 40%)", "var(--oip-color-accent)")) {
            assertTrue(HmiStyleRules.isColor(color), color)
        }
        for (color in listOf("#ff", "url(x)", "red!important", "var(--a); color: red", "rgb(0,0,0) url(x)", "linear-gradient(red, blue)", "@import")) {
            assertFalse(HmiStyleRules.isColor(color), color)
        }
    }

    @Test fun missingRequiredFieldIs422() {
        val bad = """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"x","module":"designer","version":0},"spec":{}}"""
        val r = http.post(base, bad)
        assertEquals(422, r.status, r.body)
    }

    @Test fun staleVersionIs409AndLeavesTheStoredViewAlone() {
        http.put("$base/overview", doc("overview", "Overview"))
        assertEquals(200, http.put("$base/overview", doc("overview", "Edited by A", version = 1)).status)
        val stale = http.put("$base/overview", doc("overview", "Edited by B", version = 1))
        assertEquals(409, stale.status, stale.body)
        @Suppress("UNCHECKED_CAST") val spec = http.get("$base/overview").json["spec"] as Map<String, Any?>
        assertEquals("Edited by A", spec["path"])
    }

    @Test fun unknownSpecFieldIs422() {
        val bad = """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"x","module":"designer","version":0},
            "spec":{"path":"X","document":{},"style":"big"}}"""
        assertEquals(422, http.post(base, bad).status)
    }

    @Test fun putUpdatesWithCurrentVersion() {
        http.put("$base/overview", doc("overview", "Overview"))
        val u = http.put("$base/overview", doc("overview", "Overview Renamed", version = 1))
        assertEquals(200, u.status, u.body)
        @Suppress("UNCHECKED_CAST") val spec = u.json["spec"] as Map<String, Any?>
        assertEquals("Overview Renamed", spec["path"])
    }

    // --- StyleClasses: one resource per project, same version-conflict rules as views ---

    private val styleBase = "/api/v1/config/designer/project/demo/StyleClasses"

    private fun styles(classes: String, version: Long = 0) =
        """{"apiVersion":"oip/v1","kind":"StyleClasses","metadata":{"name":"style-classes","module":"designer","version":$version},
            "spec":{"classes":$classes}}"""

    @Test fun styleClassesRoundTripAndSurviveARestart() {
        val created = http.put("$styleBase/style-classes",
            styles("""{"heading":{"style":{"color":"var(--oip-color-accent)","fontSize":20,"fontWeight":700}},"alert":{}}"""))
        assertTrue(created.status == 200 || created.status == 201, created.body)
        gw.close()

        gw = Gateway(tmp, port = 0)
        gw.start(); http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        val g = http.get("$styleBase/style-classes")
        assertEquals(200, g.status, g.body)
        @Suppress("UNCHECKED_CAST") val classes = (g.json["spec"] as Map<String, Any?>)["classes"] as Map<String, Map<String, Any?>>
        assertEquals(setOf("heading", "alert"), classes.keys)
        @Suppress("UNCHECKED_CAST") val heading = classes.getValue("heading")["style"] as Map<String, Any?>
        assertEquals("var(--oip-color-accent)", heading["color"])
        assertEquals(1L, (g.json["metadata"] as Map<*, *>)["version"].toString().toLong())
    }

    @Test fun staleStyleClassesVersionIs409AndKeepsTheStoredSet() {
        http.put("$styleBase/style-classes", styles("""{"a":{"style":{"color":"red"}}}"""))
        assertEquals(200, http.put("$styleBase/style-classes", styles("""{"a":{"style":{"color":"blue"}}}""", version = 1)).status)
        val stale = http.put("$styleBase/style-classes", styles("""{"a":{"style":{"color":"green"}}}""", version = 1))
        assertEquals(409, stale.status, stale.body)
        @Suppress("UNCHECKED_CAST") val classes = (http.get("$styleBase/style-classes").json["spec"] as Map<String, Any?>)["classes"] as Map<String, Map<String, Map<String, Any?>>>
        assertEquals("blue", classes.getValue("a").getValue("style")["color"])
    }

    /** P4-07: style classes take the same CSS subset as a component style. */
    @Test fun styleClassesTakeTheStyleSubset() {
        val ok = http.put("$styleBase/style-classes", styles("""{"card":{"style":{"borderRadius":8,"borderStyle":"solid","borderColor":"#ccc","textAlign":"center"}}}"""))
        assertTrue(ok.status == 200 || ok.status == 201, ok.body)
        val bad = http.put("$styleBase/style-classes", styles("""{"card":{"style":{"background":"url(x)"}}}""", version = 1))
        assertEquals(422, bad.status, bad.body)
        assertTrue(bad.body.contains("spec.classes.card.style.background"), bad.body)
    }

    @Test fun styleStatesAndProjectThemesAreChecked() {
        fun doc(spec: String, version: Long) =
            """{"apiVersion":"oip/v1","kind":"StyleClasses","metadata":{"name":"style-classes","module":"designer","version":$version},"spec":$spec}"""
        val ok = http.put("$styleBase/style-classes", doc(
            """{"classes":{"card":{"style":{"color":"#111111"},"states":{"hover":{"color":"#aa0000"},"disabled":{"opacity":0.4}}}},"themes":{"plant":{"extends":"daylight","tokens":{"color-alarm":"#cc0000","radius":"8px"}}}}""",
            0,
        ))
        assertTrue(ok.status == 200 || ok.status == 201, ok.body)
        val badState = http.put("$styleBase/style-classes", doc("""{"classes":{"card":{"style":{},"states":{"visited":{"color":"red"}}}}}""", 1))
        assertEquals(422, badState.status, badState.body)
        val badTheme = http.put("$styleBase/style-classes", doc("""{"classes":{"card":{"style":{}}},"themes":{"plant":{"extends":"other","tokens":{}}}}""", 1))
        assertEquals(422, badTheme.status, badTheme.body)
        val branded = http.put("$styleBase/style-classes", doc(
            """{"classes":{"card":{"style":{}}},"themes":{"acme":{"extends":"daylight","tokens":{},"brand":{"name":"Acme Water","logo":"/img/logo.svg","logoAlt":"Acme logo","graphic":"data:image/png;base64,AAAA"}}}}""",
            1,
        ))
        assertTrue(branded.status == 200, branded.body)
        val scriptLogo = http.put("$styleBase/style-classes", doc("""{"classes":{"card":{"style":{}}},"themes":{"acme":{"extends":"daylight","tokens":{},"brand":{"logo":"javascript:alert(1)"}}}}""", 2))
        assertEquals(422, scriptLogo.status, scriptLogo.body)
        val otherHost = http.put("$styleBase/style-classes", doc("""{"classes":{"card":{"style":{}}},"themes":{"acme":{"extends":"daylight","tokens":{},"brand":{"graphic":"//evil.example/x.png"}}}}""", 2))
        assertEquals(422, otherHost.status, otherHost.body)
        val unknownField = http.put("$styleBase/style-classes", doc("""{"classes":{"card":{"style":{}}},"themes":{"acme":{"extends":"daylight","tokens":{},"brand":{"motto":"x"}}}}""", 2))
        assertEquals(422, unknownField.status, unknownField.body)
    }

    @Test fun invalidStyleClassesAre422() {
        // Class name must start with a letter.
        assertEquals(422, http.put("$styleBase/style-classes", styles("""{"1bad":{}}""")).status)
        // Unsupported style property.
        assertEquals(422, http.put("$styleBase/style-classes", styles("""{"a":{"style":{"border":"1px"}}}""")).status)
        // Wrong value type.
        assertEquals(422, http.put("$styleBase/style-classes", styles("""{"a":{"style":{"fontSize":"big"}}}""")).status)
        // Unknown field on a definition.
        assertEquals(422, http.put("$styleBase/style-classes", styles("""{"a":{"style":{},"extra":1}}""")).status)
        // classes is required.
        val missing = """{"apiVersion":"oip/v1","kind":"StyleClasses","metadata":{"name":"style-classes","module":"designer","version":0},"spec":{}}"""
        assertEquals(422, http.put("$styleBase/style-classes", missing).status)
        assertEquals(404, http.get("$styleBase/style-classes").status)
    }

    /**
     * C-3: running sessions read the saved style classes the way they would read the project's views, so
     * both need the same permission (gateway.read) and saving either needs gateway.write.
     */
    @Test fun readingStyleClassesNeedsTheSamePermissionAsReadingViews() {
        assertEquals(201, http.post(base, doc("overview", "Overview")).status)
        val put = http.put("$styleBase/style-classes", styles("""{"heading":{"style":{"color":"red"}}}"""))
        assertTrue(put.status == 200 || put.status == 201, put.body)
        val reads = listOf("$base/overview", base, "$styleBase/style-classes", styleBase)

        val anonymous = Http(gw.port())
        for (path in reads) assertEquals(401, anonymous.get(path).status, path)

        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        val operator = Http(gw.port())
        assertEquals(200, operator.login("op", TEST_PASSWORD).status)
        for (path in reads) assertEquals(200, operator.get(path).status, path)
        assertEquals(403, operator.put("$styleBase/style-classes", styles("""{"heading":{}}""", version = 1)).status)

        gw.createUser("watcher", setOf(DefaultRoles.VIEWER))
        val viewer = Http(gw.port())
        assertEquals(200, viewer.login("watcher", TEST_PASSWORD).status)
        for (path in reads) assertEquals(403, viewer.get(path).status, path)
    }

    // --- PageConfig (H-1): one resource per project, read by /hmi sessions ---

    private val pageBase = "/api/v1/config/designer/project/demo/PageConfig"

    private fun pages(spec: String, version: Long = 0) =
        """{"apiVersion":"oip/v1","kind":"PageConfig","metadata":{"name":"page-config","module":"designer","version":$version},
            "spec":$spec}"""

    @Test fun pageConfigRoundTripsWithDocksAndVersions() {
        val spec = """{"pages":[{"url":"/","title":"Home","primaryView":"home","docks":[]},
            {"url":"/line/:id","primaryView":"line","cornerPriority":"left-right"}],
            "sharedDocks":[{"id":"nav","edge":"left","view":"nav","size":180,"display":"visible","content":"push","handle":"hide"}],
            "cornerPriority":"top-bottom"}"""
        val created = http.put("$pageBase/page-config", pages(spec))
        assertTrue(created.status == 200 || created.status == 201, created.body)
        val g = http.get("$pageBase/page-config")
        assertEquals(200, g.status, g.body)
        @Suppress("UNCHECKED_CAST") val stored = g.json["spec"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val list = stored["pages"] as List<Map<String, Any?>>
        assertEquals(listOf("/", "/line/:id"), list.map { it["url"] })
        @Suppress("UNCHECKED_CAST") val docks = stored["sharedDocks"] as List<Map<String, Any?>>
        assertEquals("nav", docks.single()["id"])
        val stale = http.put("$pageBase/page-config", pages("""{"pages":[]}""", version = 0))
        assertEquals(409, stale.status, stale.body)
        assertEquals(200, http.put("$pageBase/page-config", pages("""{"pages":[]}""", version = 1)).status)
    }

    @Test fun invalidPageConfigIs422() {
        // pages is required.
        assertEquals(422, http.put("$pageBase/page-config", pages("""{}""")).status)
        // A page needs a URL starting with / and a primary view.
        assertEquals(422, http.put("$pageBase/page-config", pages("""{"pages":[{"url":"home","primaryView":"home"}]}""")).status)
        assertEquals(422, http.put("$pageBase/page-config", pages("""{"pages":[{"url":"/"}]}""")).status)
        // Unknown page field, unknown corner rule.
        assertEquals(422, http.put("$pageBase/page-config", pages("""{"pages":[{"url":"/","primaryView":"a","color":"red"}]}""")).status)
        assertEquals(422, http.put("$pageBase/page-config", pages("""{"pages":[],"cornerPriority":"diagonal"}""")).status)
        assertEquals(404, http.get("$pageBase/page-config").status)
    }

    /** A session reads the page config with the same permission as the project's views and style classes. */
    @Test fun readingPageConfigNeedsGatewayRead() {
        assertTrue(http.put("$pageBase/page-config", pages("""{"pages":[{"url":"/","primaryView":"home"}]}""")).status in 200..201)
        assertEquals(401, Http(gw.port()).get("$pageBase/page-config").status)
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        val operator = Http(gw.port())
        assertEquals(200, operator.login("op", TEST_PASSWORD).status)
        assertEquals(200, operator.get("$pageBase/page-config").status)
        assertEquals(403, operator.put("$pageBase/page-config", pages("""{"pages":[]}""", version = 1)).status)
    }
}
