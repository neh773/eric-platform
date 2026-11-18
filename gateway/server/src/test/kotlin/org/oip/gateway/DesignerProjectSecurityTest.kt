package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText

/**
 * SEC-020: a project's required Designer roles restrict viewing, saving, deleting and protecting resources, a protected
 * resource is changed only by holders of the protect roles, and a resource a Designer is editing is locked against
 * every other Designer until it is saved or released, its session ends, or an administrator breaks the lock. Every
 * check is on the gateway API, so a client that skips the Designer UI meets the same rules.
 * Doc source: FEATURES SEC-020.
 */
class DesignerProjectSecurityTest {
    @TempDir lateinit var tmp: Path
    private val clock = MutableClock()
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private lateinit var alice: Http
    private lateinit var bob: Http
    private val views = "/api/v1/config/designer/project/demo/View"
    private val base = "/api/v1/designer/projects/demo"

    @BeforeEach fun setUp() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0, clock = clock).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        for (role in listOf("engineer", "lead")) {
            gw.security.roles.create(role, setOf(Permissions.GATEWAY_WRITE, Permissions.TAGS_READ), "", "test", "127.0.0.1")
        }
        gw.createUser("alice", setOf("engineer", "lead"))
        gw.createUser("bob", setOf("engineer"))
        admin = Http(gw.port()).also { assertEquals(200, it.login("admin", TEST_PASSWORD).status) }
        // Both engineers may use the Designer (gateway designer roles); the project decides what each may do in it.
        val current = admin.get("/api/v1/config/org.oip.security/gateway/GatewaySecurity/gateway")
        @Suppress("UNCHECKED_CAST") val spec = (current.json["spec"] as Map<String, Any?>).toMutableMap()
        spec["designerRoles"] = listOf(DefaultRoles.ADMINISTRATOR, "engineer")
        val body = Json.write(mapOf("apiVersion" to "oip/v1", "kind" to "GatewaySecurity", "metadata" to current.json["metadata"], "spec" to spec))
        assertEquals(200, admin.put("/api/v1/config/org.oip.security/gateway/GatewaySecurity/gateway", body).status)
        alice = Http(gw.port()).also { assertEquals(200, it.login("alice", TEST_PASSWORD).status) }
        bob = Http(gw.port()).also { assertEquals(200, it.login("bob", TEST_PASSWORD).status) }
        assertEquals(201, admin.post("/api/v1/projects", """{"name":"demo"}""").status)
        assertEquals(201, admin.post(views, view("main")).status)
    }

    @AfterEach fun tearDown() = gw.close()

    private fun view(name: String, version: Long = 0) =
        """{"apiVersion":"oip/v1","kind":"View","metadata":{"name":"$name","module":"designer","version":$version},
            "spec":{"path":"$name","document":{"params":{},"root":{"id":"root","kind":"container","layout":"coordinate","children":[]}}}}"""

    private fun version(name: String): Long = ((admin.get("$views/$name").json["metadata"] as Map<*, *>)["version"] as Number).toLong()

    private fun save(http: Http, name: String = "main", session: String? = null): Http.Res =
        http.send("PUT", "$views/$name", view(name, version(name)).toByteArray(),
            headers = session?.let { mapOf(DesignerLocks.SESSION_HEADER to it) } ?: emptyMap())

    private fun require(roles: Map<String, List<String>>) {
        val r = admin.send("PATCH", "/api/v1/projects/demo", Json.write(roles).toByteArray())
        assertEquals(200, r.status, r.body)
    }

    private fun session(http: Http): String {
        val r = http.post("/api/v1/designer/sessions", """{"project":"demo"}""")
        assertEquals(201, r.status, r.body)
        return r.json["id"] as String
    }

    private fun lock(http: Http, session: String, name: String = "main"): Http.Res =
        http.post("$base/locks", """{"module":"designer","kind":"View","name":"$name","session":"$session"}""")

    @Suppress("UNCHECKED_CAST")
    private fun access(http: Http): Map<String, Any?> = http.get("$base/access").also { assertEquals(200, it.status, it.body) }.json

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertEquals("urn:oip:problem:$slug", r.json["type"])
    }

    @Test fun withoutRequiredRolesEveryDesignerUserMaySave() {
        assertEquals(200, save(bob).status)
        val allowed = access(bob)["allowed"] as Map<*, *>
        assertEquals(mapOf("view" to true, "save" to true, "delete" to true, "protect" to true), allowed)
        assertEquals(false, access(bob)["breakLocks"])
        assertEquals(true, access(admin)["breakLocks"])
    }

    @Test fun saveRolesRestrictEveryWriteOfTheProject() {
        require(mapOf("designerSaveRoles" to listOf("lead")))
        assertProblem(save(bob), 403, DesignerProjectGuard.SLUG)
        assertTrue(save(bob).body.contains("lead"))
        assertProblem(bob.post(views, view("other")), 403, DesignerProjectGuard.SLUG)
        assertProblem(bob.send("DELETE", "$views/main"), 403, DesignerProjectGuard.SLUG)
        assertProblem(bob.post("/api/v1/config/batch", """{"scope":"project/demo","items":[${view("batched")}]}"""), 403, DesignerProjectGuard.SLUG)
        // Changing the project settings is a save too; users.admin may still change them, so a lockout can be undone.
        assertProblem(bob.send("PATCH", "/api/v1/projects/demo", """{"title":"x"}""".toByteArray()), 403, DesignerProjectGuard.SLUG)
        assertEquals(200, save(alice).status)
        assertEquals(201, alice.post(views, view("other")).status)
        val doc = access(bob)
        assertEquals(false, (doc["allowed"] as Map<*, *>)["save"])
        assertEquals(listOf("lead"), (doc["requiredRoles"] as Map<*, *>)["save"])
        // The administrator holds no "lead" role: a project save is refused, but the settings stay open to fix.
        assertProblem(save(admin), 403, DesignerProjectGuard.SLUG)
        require(mapOf("designerSaveRoles" to emptyList()))
        assertEquals(200, save(bob).status)
    }

    @Test fun viewRolesHideTheProjectFromTheDesigner() {
        require(mapOf("designerViewRoles" to listOf("lead")))
        assertProblem(bob.get(views), 403, DesignerProjectGuard.SLUG)
        assertProblem(bob.get("$views/main"), 403, DesignerProjectGuard.SLUG)
        assertProblem(bob.get("$views?inherited=true"), 403, DesignerProjectGuard.SLUG)
        assertProblem(bob.post("/api/v1/designer/sessions", """{"project":"demo"}"""), 403, DesignerProjectGuard.SLUG)
        val listed = bob.get("/api/v1/config?module=designer").json["items"] as List<*>
        assertTrue(listed.isEmpty(), "a listing leaves the project's Designer resources out: $listed")
        assertEquals(200, alice.get("$views/main").status)
        assertEquals(1, (alice.get("/api/v1/config?module=designer").json["items"] as List<*>).size)
        assertEquals(201, alice.post("/api/v1/designer/sessions", """{"project":"demo"}""").status)
    }

    @Test fun deleteRolesRestrictDeletingAndRenamingTheProject() {
        require(mapOf("designerDeleteRoles" to listOf("lead")))
        assertProblem(bob.delete("/api/v1/projects/demo"), 403, DesignerProjectGuard.SLUG)
        assertProblem(bob.post("/api/v1/projects/demo/rename", """{"name":"renamed"}"""), 403, DesignerProjectGuard.SLUG)
        assertEquals(200, save(bob).status, "saving is not deleting")
        assertEquals(204, alice.delete("/api/v1/projects/demo").status)
    }

    @Test fun aProtectedResourceIsChangedOnlyWithTheProtectRoles() {
        require(mapOf("designerProtectRoles" to listOf("lead")))
        assertProblem(bob.put("$base/protected/designer/View/main", "{}"), 403, DesignerProjectGuard.SLUG)
        assertEquals(404, alice.put("$base/protected/designer/View/missing", "{}").status)
        val protected = alice.put("$base/protected/designer/View/main", "{}")
        assertEquals(200, protected.status, protected.body)
        assertEquals(listOf("designer/View/main"), access(bob)["protected"])
        assertEquals(listOf("designer/View/main"), admin.get("/api/v1/projects/demo").json["protectedResources"])

        assertProblem(save(bob), 403, DesignerProjectGuard.PROTECTED_SLUG)
        assertProblem(bob.send("DELETE", "$views/main"), 403, DesignerProjectGuard.PROTECTED_SLUG)
        assertProblem(lock(bob, session(bob)), 403, DesignerProjectGuard.SLUG)
        // Other resources of the project stay open to bob.
        assertEquals(201, bob.post(views, view("other")).status)
        // Removing protection through the settings needs the protect roles too.
        assertProblem(bob.send("PATCH", "/api/v1/projects/demo", """{"protectedResources":[]}""".toByteArray()), 403, DesignerProjectGuard.SLUG)
        assertEquals(200, save(alice).status)
        assertEquals(1, gw.security.audit.query(AuditQuery(action = AuditActions.PROJECT_RESOURCE_PROTECTED)).records.size)

        assertEquals(200, alice.send("DELETE", "$base/protected/designer/View/main").status)
        assertEquals(emptyList<String>(), access(bob)["protected"])
        assertEquals(200, save(bob).status)
    }

    @Test fun aLockedResourceIsSavedOnlyByItsHolderAndASaveReleasesTheLock() {
        val s1 = session(alice)
        val taken = lock(alice, s1)
        assertEquals(201, taken.status, taken.body)
        assertEquals(200, lock(alice, s1).status, "the same session holds it already")
        val bobs = session(bob)
        val held = lock(bob, bobs)
        assertProblem(held, 423, DesignerProjectGuard.LOCKED_SLUG)
        assertEquals("alice", (held.json["lock"] as Map<*, *>)["user"])
        assertFalse((held.json["lock"] as Map<*, *>).containsKey("session"), "another user's session id is not shown")

        val seen = (access(bob)["locks"] as List<*>).single() as Map<*, *>
        assertEquals("alice", seen["user"])
        assertEquals("designer/View/main", seen["path"])
        assertEquals(false, seen["mine"])

        assertProblem(save(bob), 423, DesignerProjectGuard.LOCKED_SLUG)
        assertProblem(bob.send("DELETE", "$views/main"), 423, DesignerProjectGuard.LOCKED_SLUG)
        assertProblem(bob.post("/api/v1/config/batch", """{"scope":"project/demo","items":[${view("main", version("main"))}]}"""), 423, DesignerProjectGuard.LOCKED_SLUG)
        // Alice's other Designer session is another Designer: it may not save over her open editor either.
        val s2 = session(alice)
        assertProblem(save(alice, session = s2), 423, DesignerProjectGuard.LOCKED_SLUG)

        assertEquals(200, save(alice, session = s1).status)
        assertTrue((access(bob)["locks"] as List<*>).isEmpty(), "a save by the holder releases the lock")
        assertEquals(200, save(bob).status)
    }

    @Test fun locksEndWithReleaseCloseAndSessionExpiry() {
        val s1 = session(alice)
        assertEquals(201, lock(alice, s1).status)
        assertProblem(bob.delete("$base/locks/designer/View/main"), 423, DesignerProjectGuard.LOCKED_SLUG)
        assertEquals(204, alice.delete("$base/locks/designer/View/main").status)
        assertEquals(404, alice.delete("$base/locks/designer/View/main").status)

        assertEquals(201, lock(alice, s1).status)
        assertEquals(204, alice.delete("/api/v1/designer/sessions/$s1").status)
        assertEquals(200, save(bob).status, "closing the Designer releases its locks")

        val s2 = session(alice)
        assertEquals(201, lock(alice, s2).status)
        clock.advance(Duration.ofSeconds(DesignerSessions.TTL_SECONDS + 1))
        assertEquals(200, save(bob).status, "a session that stopped its heartbeat no longer holds locks")
        assertProblem(lock(alice, s2), 422, "validation")

        // A session of another user cannot be used to take a lock.
        val bobs = session(bob)
        assertProblem(lock(alice, bobs), 422, "validation")
    }

    @Test fun anAdministratorBreaksALockAndTheBreakIsAudited() {
        val s1 = session(alice)
        assertEquals(201, lock(alice, s1).status)
        assertProblem(bob.delete("$base/locks/designer/View/main?break=true"), 403, "forbidden")
        assertEquals(204, admin.delete("$base/locks/designer/View/main?break=true").status)
        assertTrue((access(alice)["locks"] as List<*>).isEmpty())
        assertEquals(200, save(bob).status)
        val record = gw.security.audit.query(AuditQuery(action = AuditActions.DESIGNER_LOCK_BROKEN)).records.single()
        assertEquals("admin", record.actor)
        assertEquals("project/demo/designer/View/main", record.target)
        assertTrue(record.detail.contains("holder=alice"))
    }

    @Test fun anUnknownProjectOrResourcePathIsRefused() {
        assertEquals(404, admin.get("/api/v1/designer/projects/nope/access").status)
        assertEquals(422, admin.put("$base/protected/designer/view/main", "{}").status)
        assertEquals(422, admin.post("$base/locks", """{"module":"designer","kind":"View","name":"main"}""").status)
    }
}
