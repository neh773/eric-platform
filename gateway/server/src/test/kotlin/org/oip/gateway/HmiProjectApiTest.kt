package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Review-4 F5 and F12: an HMI session reads its project through `/api/v1/hmi/projects/{name}` with `tags.read`. A
 * viewer runs the HMI without `gateway.read`, and the route answers a saved change by notification, not by a poll.
 */
class HmiProjectApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private lateinit var viewer: Http
    private val project = "/api/v1/hmi/projects/plant"

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
        viewer = Http(gw.port()).also { assertEquals(200, it.login("view", TEST_PASSWORD).status) }
        assertEquals(201, admin.post("/api/v1/config/designer/project/plant/View", view("home", "Home")).status)
        val hooks = """{"apiVersion":"oip/v1","kind":"ScriptHooks","metadata":{"name":"script-hooks","module":"designer","version":0},
            "spec":{"hooks":{"secret":{"source":"return 'hook source'"}}}}"""
        assertEquals(201, admin.put("/api/v1/config/designer/project/plant/ScriptHooks/script-hooks", hooks).status)
    }

    @Test fun projectDefaultsRoundTripAndReachTheSessionBundle() {
        val bad = admin.send("PATCH", "/api/v1/projects/plant", """{"sessionLocale":"english"}""".toByteArray())
        assertEquals(422, bad.status, bad.body)
        val patched = admin.send(
            "PATCH",
            "/api/v1/projects/plant",
            """{"defaultTagProvider":"edge","defaultDatabase":"plant","sessionTheme":"nightwatch","sessionLocale":"en-GB","touchscreenMode":true}""".toByteArray(),
        )
        assertEquals(200, patched.status, patched.body)
        assertEquals("edge", patched.json["defaultTagProvider"])
        assertEquals("plant", patched.json["defaultDatabase"])
        assertEquals("nightwatch", patched.json["sessionTheme"])
        assertEquals("en-GB", patched.json["sessionLocale"])
        assertEquals(true, patched.json["touchscreenMode"])
        val bundle = viewer.get(project)
        assertEquals(200, bundle.status, bundle.body)
        assertEquals("nightwatch", bundle.json["sessionTheme"])
        assertEquals("en-GB", bundle.json["sessionLocale"])
        assertEquals(true, bundle.json["touchscreenMode"])
        assertEquals("edge", bundle.json["defaultTagProvider"])
    }

    @AfterEach fun tearDown() = gw.close()

    private val translation = """{"apiVersion":"oip/v1","kind":"Translation","metadata":{"name":"messages","module":"designer","version":0},
        "spec":{"locale":"en-US","messages":{"Start":"Start","Stop":"Stop"},
        "locales":{"es":{"Start":"Iniciar","Stop":"Parar"},"es-MX":{"Stop":"Detener"},"de":{"Start":"Starten"}}}}"""

    /** doc source: FEATURES HMI-005: the session bundle carries the terms of the session locale, regional over language. */
    @Test fun theSessionBundleCarriesTheTermsOfTheSessionLocale() {
        assertEquals(201, admin.put("/api/v1/config/designer/project/plant/Translation/messages", translation).status)
        var bundle = viewer.get(project)
        @Suppress("UNCHECKED_CAST")
        var terms = bundle.json["translations"] as Map<String, Any?>
        assertEquals("en-US", terms["locale"])
        assertEquals(mapOf("Start" to "Start", "Stop" to "Stop"), terms["messages"])
        assertEquals(listOf("de", "en-US", "es", "es-MX"), terms["locales"])
        assertEquals(200, admin.send("PATCH", "/api/v1/projects/plant", """{"sessionLocale":"es-MX"}""".toByteArray()).status)
        bundle = viewer.get(project)
        @Suppress("UNCHECKED_CAST")
        terms = bundle.json["translations"] as Map<String, Any?>
        assertEquals(mapOf("Start" to "Iniciar", "Stop" to "Detener"), terms["messages"])
        // A locale with no terms sends none; the HMI shows each key.
        assertEquals(200, admin.send("PATCH", "/api/v1/projects/plant", """{"sessionLocale":"fr-FR"}""".toByteArray()).status)
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyMap<String, Any?>(), (viewer.get(project).json["translations"] as Map<String, Any?>)["messages"])
    }

    /** doc source: FEATURES HMP-125 and HMP-101: every locale's terms and the gateway's default zone reach the session. */
    @Test fun theSessionBundleCarriesEveryLocaleAndTheGatewayTimeZone() {
        assertEquals(201, admin.put("/api/v1/config/designer/project/plant/Translation/messages", translation).status)
        val bundle = viewer.get(project)
        @Suppress("UNCHECKED_CAST")
        val catalog = (bundle.json["translations"] as Map<String, Any?>)["catalog"] as Map<String, Map<String, String>>
        assertEquals(setOf("de", "en-US", "es", "es-MX"), catalog.keys)
        assertEquals("Iniciar", catalog["es"]!!["Start"])
        assertEquals(mapOf("Start" to "Iniciar", "Stop" to "Detener"), catalog["es-MX"])
        assertEquals(java.time.ZoneId.systemDefault().id, bundle.json["gatewayTimeZone"])
    }

    private fun securedView(name: String, title: String, roles: List<String>) =
        """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"$name","module":"designer","version":0},
            "spec":{"path":"$name","title":"$title","document":{"params":{},"security":{"roles":${Json.write(roles)}},
            "root":{"id":"root","kind":"container","layout":"coordinate","children":[{"id":"secret-label","kind":"component","type":"label",
            "props":{"text":"Reactor bypass"},"bindings":{"text":{"kind":"tag","mode":"direct","path":"[default]reactor/bypass"}}}]}}}}"""

    private fun view(name: String, title: String, version: Long = 0) =
        """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"$name","module":"designer","version":$version},
            "spec":{"path":"$name","title":"$title","document":{"params":{},"root":{"id":"root","kind":"container","layout":"coordinate","children":[]}}}}"""

    @Test fun aViewerRunsAProjectButCannotReadGatewayConfiguration() {
        val r = viewer.get(project)
        assertEquals(200, r.status, r.body)
        assertEquals(true, r.json["launchable"])
        val views = r.json["views"] as List<*>
        assertEquals(listOf("home"), views.map { ((it as Map<*, *>)["metadata"] as Map<*, *>)["name"] })
        assertNotNull(r.json["signature"])
        // Script source stays on the gateway, and nothing but the project's design resources is in the answer.
        assertFalse(r.body.contains("hook source"), r.body)
        assertEquals(listOf("plant"), viewer.get("/api/v1/hmi/projects").json["projects"])

        assertEquals(403, viewer.get("/api/v1/config/designer/project/plant/View").status)
        assertEquals(403, viewer.get("/api/v1/projects/plant").status)
        assertEquals(403, viewer.get("/api/v1/config").status)
        assertEquals(404, viewer.get("/api/v1/hmi/projects/nothing-here").status)
        assertEquals(404, viewer.get("/api/v1/hmi/projects/Not_A_Name").status)
        assertEquals(401, Http(gw.port()).get(project).status)
    }

    @Test fun aDisabledProjectAnswersWithoutItsResources() {
        assertEquals(201, admin.post("/api/v1/projects", """{"name":"other","enabled":false}""").status)
        assertEquals(201, admin.post("/api/v1/config/designer/project/other/View", view("home", "Hidden")).status)
        val r = viewer.get("/api/v1/hmi/projects/other")
        assertEquals(200, r.status, r.body)
        assertEquals(false, r.json["launchable"])
        assertEquals(false, r.json["enabled"])
        assertNull(r.json["views"])
        assertFalse(r.body.contains("Hidden"))
        assertEquals(listOf("plant"), viewer.get("/api/v1/hmi/projects").json["projects"])
    }

    @Test fun aWaitingReadAnswersASavedChangeAndOtherwiseNotModified() {
        val first = viewer.get(project)
        val signature = first.json["signature"] as String
        // Nothing changes: 304 once the wait is over.
        val started = System.nanoTime()
        val idle = viewer.get("$project?after=$signature&waitMs=400")
        assertEquals(304, idle.status, idle.body)
        assertTrue(System.nanoTime() - started >= 350_000_000L, "answered before the wait")
        // A different signature answers at once.
        assertEquals(200, viewer.get("$project?after=stale&waitMs=20000").status)

        // A save while the read waits answers it with the new project, well before the wait is over.
        val waiting = CompletableFuture.supplyAsync { viewer.get("$project?after=$signature&waitMs=20000") }
        Thread.sleep(300)
        assertFalse(waiting.isDone)
        val saved = System.nanoTime()
        assertEquals(200, admin.put("/api/v1/config/designer/project/plant/View/home", view("home", "Edited", 1)).status)
        val changed = waiting.get()
        assertEquals(200, changed.status, changed.body)
        assertTrue(System.nanoTime() - saved < 5_000_000_000L)
        assertTrue(changed.body.contains("Edited"))
        assertNotEquals(signature, changed.json["signature"])

        // A change to another project, or outside the design resources, does not answer it.
        val next = changed.json["signature"] as String
        val other = CompletableFuture.supplyAsync { viewer.get("$project?after=$next&waitMs=1500") }
        assertEquals(201, admin.post("/api/v1/config/designer/project/elsewhere/View", view("home", "Else")).status)
        assertEquals(304, other.get().status)
    }

    /** P4-04 (HMP-092): a view whose security names roles the caller lacks is listed as denied and sent without content. */
    @Test fun aViewTheCallerMayNotSeeIsRefusedAndSendsNothingOfIt() {
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        val operator = Http(gw.port()).also { assertEquals(200, it.login("op", TEST_PASSWORD).status) }
        assertEquals(201, admin.post("/api/v1/config/designer/project/plant/View", securedView("reactor", "Reactor controls", listOf("operator"))).status)

        val refused = viewer.get(project)
        assertEquals(200, refused.status, refused.body)
        assertEquals(listOf("reactor"), refused.json["deniedViews"])
        assertEquals(listOf("home"), (refused.json["views"] as List<*>).map { ((it as Map<*, *>)["metadata"] as Map<*, *>)["name"] })
        // Nothing of the view goes out: no title, no components, no tag paths.
        for (secret in listOf("Reactor controls", "Reactor bypass", "reactor/bypass", "secret-label")) assertFalse(refused.body.contains(secret), secret)

        val allowed = operator.get(project)
        assertEquals(200, allowed.status, allowed.body)
        assertEquals(emptyList<String>(), allowed.json["deniedViews"])
        assertEquals(listOf("home", "reactor"), (allowed.json["views"] as List<*>).map { ((it as Map<*, *>)["metadata"] as Map<*, *>)["name"] }.sortedBy { it as String })
        assertTrue(allowed.body.contains("Reactor bypass"))
        // Each caller's signature covers what that caller is sent.
        assertNotEquals(refused.json["signature"], allowed.json["signature"])
        assertEquals(304, viewer.get("$project?after=${refused.json["signature"]}&waitMs=50").status)
    }

    /** P4-04 (HMP-092): a project's session roles refuse the whole project to a caller without one of them. */
    @Test fun aSessionTheCallerMayNotOpenIsRefusedWithNothingOfTheProject() {
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        val operator = Http(gw.port()).also { assertEquals(200, it.login("op", TEST_PASSWORD).status) }
        val patched = admin.send("PATCH", "/api/v1/projects/plant", """{"sessionRoles":["operator"," operator ",""]}""".toByteArray())
        assertEquals(200, patched.status, patched.body)
        assertEquals(listOf("operator"), patched.json["sessionRoles"])
        assertEquals(422, admin.send("PATCH", "/api/v1/projects/plant", """{"sessionRoles":"operator"}""".toByteArray()).status)

        val refused = viewer.get(project)
        assertEquals(403, refused.status, refused.body)
        assertTrue(refused.body.contains("session_refused"), refused.body)
        assertTrue(refused.body.contains("operator"), refused.body)
        assertFalse(refused.body.contains("\"views\""), refused.body)
        assertFalse(refused.body.contains("Home"), refused.body)
        // Also a waiting read, and the list of launchable projects.
        assertEquals(403, viewer.get("$project?after=anything&waitMs=50").status)
        assertEquals(emptyList<String>(), viewer.get("/api/v1/hmi/projects").json["projects"])

        assertEquals(200, operator.get(project).status)
        assertEquals(listOf("plant"), operator.get("/api/v1/hmi/projects").json["projects"])
        // An administrator without the named role is refused too: the roles are the rule, not the permissions.
        assertEquals(403, admin.get(project).status)
    }

    /** P4-04 (HMP-092): a project that needs an identity provider login refuses a password login. */
    @Test fun aSessionThatNeedsAnIdentityProviderRefusesAPasswordLogin() {
        val patched = admin.send("PATCH", "/api/v1/projects/plant", """{"sessionIdentityProvider":"corp"}""".toByteArray())
        assertEquals(200, patched.status, patched.body)
        val refused = viewer.get(project)
        assertEquals(403, refused.status, refused.body)
        assertTrue(refused.body.contains("identity provider 'corp'"), refused.body)
    }

    /** P4-04 (HMP-092): the rule for session and view refusal, as the route and the script frames apply it. */
    @Test fun sessionAndViewRulesFollowRolesAndProvider() {
        val settings = org.oip.config.ProjectService.Settings(sessionRoles = listOf("operator", "supervisor"), sessionIdentityProvider = "corp")
        assertNull(HmiProjectApi.sessionRefusal(settings, HmiProjectApi.Viewer(setOf("supervisor"), "corp")))
        assertNotNull(HmiProjectApi.sessionRefusal(settings, HmiProjectApi.Viewer(setOf("supervisor"), null)))
        assertNotNull(HmiProjectApi.sessionRefusal(settings, HmiProjectApi.Viewer(setOf("viewer"), "corp")))
        assertNull(HmiProjectApi.sessionRefusal(org.oip.config.ProjectService.Settings(), HmiProjectApi.Viewer(emptySet(), null)))
        val spec = mapOf("document" to mapOf("security" to mapOf("roles" to listOf("operator"))))
        assertTrue(HmiProjectApi.viewAllowed(spec, HmiProjectApi.Viewer(setOf("operator"), null)))
        assertFalse(HmiProjectApi.viewAllowed(spec, HmiProjectApi.Viewer(setOf("viewer"), null)))
        assertTrue(HmiProjectApi.viewAllowed(mapOf("document" to mapOf("security" to mapOf("roles" to emptyList<String>()))), HmiProjectApi.Viewer(emptySet(), null)))
        assertTrue(HmiProjectApi.viewAllowed(mapOf("document" to emptyMap<String, Any?>()), HmiProjectApi.Viewer(emptySet(), null)))
    }
}
