package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.security.DefaultRoles
import org.oip.security.Kinds
import org.oip.security.Permissions
import org.oip.security.SECURITY_MODULE
import java.nio.file.Path

/**
 * REST users, roles, and API keys (P1-25a).
 * Users and roles: -
 * API keys: -
 * Route permissions: -
 */
class SecurityApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun usersAndRolesRoundTripHideTheHashAndAuditEachWrite() {
        val roles = "/api/v1/security/roles"
        val users = "/api/v1/security/users"
        val rejected = http.post(roles, """{"name":"shift","permissions":["not.a.permission"]}""")
        assertProblem(rejected, 422, "validation")
        assertFalse(rejected.body.contains("passwordHash"))

        http.sendCsrf = false
        assertProblem(http.post(roles, roleBody("shift", listOf(Permissions.TAGS_READ))), 403, "csrf")
        assertEquals(0, auditTotal(AuditActions.CONFIG_ADDED, "Role/shift"))
        http.sendCsrf = true

        val createdRole = http.post(roles, roleBody("shift", listOf(Permissions.TAGS_READ), "Shift lead"))
        assertEquals(201, createdRole.status, createdRole.body)
        assertEquals("/api/v1/security/roles/shift", createdRole.header("Location"))
        assertEquals("\"1\"", createdRole.header("ETag"))
        assertEquals("shift", createdRole.json["name"])
        assertEquals("Shift lead", createdRole.json["description"])
        assertEquals(listOf(Permissions.TAGS_READ), createdRole.json["permissions"])
        assertOne(AuditActions.CONFIG_ADDED, "Role/shift")

        val gotRole = http.get("$roles/shift")
        assertEquals(200, gotRole.status, gotRole.body)
        assertEquals("\"1\"", gotRole.header("ETag"))
        val roleNames = (http.get(roles).json["items"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertTrue(roleNames.containsAll(listOf("administrator", "operator", "shift", "viewer")))

        val updatedRole = send("PUT", "$roles/shift", """{"description":"Floor lead"}""", "\"1\"")
        assertEquals(200, updatedRole.status, updatedRole.body)
        assertEquals("\"2\"", updatedRole.header("ETag"))
        assertEquals("Floor lead", updatedRole.json["description"])
        assertEquals(listOf(Permissions.TAGS_READ), updatedRole.json["permissions"])
        assertProblem(send("PUT", "$roles/shift", """{"description":"nope"}""", "\"1\""), 409, "conflict")
        assertOne(AuditActions.CONFIG_UPDATED, "Role/shift")

        val ada = """{"username":"ada","password":"$TEST_PASSWORD","roles":["shift"],"displayName":"Ada Lovelace","email":"ada@example.com","phone":"555-0100","enabled":true}"""
        val planted = http.post(users, """{"username":"ada","password":"$TEST_PASSWORD","passwordHash":"client-supplied-hash-value"}""")
        assertProblem(planted, 422, "validation")
        assertFalse(planted.body.contains("client-supplied-hash-value"))
        assertEquals(0, auditTotal(AuditActions.CONFIG_ADDED, "User/ada"))

        http.sendCsrf = false
        assertProblem(http.post(users, ada), 403, "csrf")
        http.sendCsrf = true
        val created = http.post(users, ada)
        assertEquals(201, created.status, created.body)
        assertEquals("/api/v1/security/users/ada", created.header("Location"))
        assertEquals("\"1\"", created.header("ETag"))
        assertEquals("ada", created.json["username"])
        assertEquals("Ada Lovelace", created.json["displayName"])
        assertEquals("ada@example.com", created.json["email"])
        assertEquals(listOf("shift"), created.json["roles"])
        assertEquals(false, created.json["passwordExpired"])
        val hash = stored(Kinds.USER, "ada", "passwordHash")
        assertTrue(hash.startsWith("\$argon2id\$"), hash)
        assertNoSecret(created.body, TEST_PASSWORD, hash)
        assertOne(AuditActions.CONFIG_ADDED, "User/ada")

        val fetched = http.get("$users/ada")
        assertEquals(200, fetched.status, fetched.body)
        assertEquals("Ada Lovelace", fetched.json["displayName"])
        assertNoSecret(fetched.body, TEST_PASSWORD, hash)
        val listed = http.get(users)
        assertTrue((listed.json["items"] as List<*>).any { (it as Map<*, *>)["username"] == "ada" })
        assertNoSecret(listed.body, TEST_PASSWORD, hash)

        assertProblem(http.post(users, ada), 409, "conflict")
        assertEquals(1, auditTotal(AuditActions.CONFIG_ADDED, "User/ada"))

        val updated = send("PUT", "$users/ada", """{"displayName":"Ada L","roles":["viewer"]}""", "\"1\"")
        assertEquals(200, updated.status, updated.body)
        assertEquals("\"2\"", updated.header("ETag"))
        assertEquals("Ada L", updated.json["displayName"])
        assertEquals(listOf("viewer"), updated.json["roles"])
        assertEquals("ada@example.com", updated.json["email"])
        assertNoSecret(updated.body, TEST_PASSWORD, hash)
        assertProblem(send("PUT", "$users/ada", """{"displayName":"stale"}""", "\"1\""), 409, "conflict")
        assertEquals(1, auditTotal(AuditActions.CONFIG_UPDATED, "User/ada"))

        val password = send("POST", "$users/ada/password", """{"password":"$NEW_PASSWORD"}""")
        assertEquals(200, password.status, password.body)
        assertEquals("\"3\"", password.header("ETag"))
        assertNoSecret(password.body, TEST_PASSWORD, hash)
        assertNoSecret(password.body, NEW_PASSWORD, hash)
        assertEquals(2, auditTotal(AuditActions.CONFIG_UPDATED, "User/ada"))
        val fresh = Http(gw.port())
        assertProblem(fresh.login("ada", TEST_PASSWORD), 401, "unauthorized")
        assertEquals(200, fresh.login("ada", NEW_PASSWORD).status)

        assertProblem(http.delete("$users/ada", mapOf("If-Match" to "\"2\"")), 409, "conflict")
        assertEquals(204, http.delete("$users/ada", mapOf("If-Match" to "\"3\"")).status)
        assertProblem(http.get("$users/ada"), 404, "not-found")
        assertOne(AuditActions.CONFIG_DELETED, "User/ada")

        assertEquals(204, http.delete("$roles/shift", mapOf("If-Match" to "\"2\"")).status)
        assertProblem(http.get("$roles/shift"), 404, "not-found")
        assertOne(AuditActions.CONFIG_DELETED, "Role/shift")

        val audit = http.get("/api/v1/audit?limit=1000")
        assertNoSecret(audit.body, TEST_PASSWORD, hash)
        assertFalse(audit.body.contains(NEW_PASSWORD))
    }

    @Test fun apiKeySecretIsReturnedOnceThenRotateRevokeAndScope() {
        val keys = "/api/v1/security/api-keys"
        http.sendCsrf = false
        assertProblem(http.post(keys, keyBody("reader", listOf(Permissions.GATEWAY_READ))), 403, "csrf")
        assertEquals(0, auditTotal(AuditActions.CONFIG_ADDED, "ApiKey/reader"))
        http.sendCsrf = true

        val created = http.post(keys, keyBody("reader", listOf(Permissions.GATEWAY_READ)))
        assertEquals(201, created.status, created.body)
        val secret = created.json["secret"] as String
        assertTrue(secret.startsWith("oipk_"))
        assertEquals(1, created.body.split(secret).size - 1)
        val keyHash = stored(Kinds.API_KEY, "reader", "keyHash")
        assertNoSecret(created.body, secret = null, hash = keyHash)
        assertFalse(created.body.contains("keyHash"))
        assertOne(AuditActions.CONFIG_ADDED, "ApiKey/reader")

        val listed = http.get(keys)
        val fetched = http.get("$keys/reader")
        assertEquals(200, fetched.status, fetched.body)
        assertEquals(false, fetched.json.containsKey("secret"))
        assertNoSecret(listed.body, hash = keyHash)
        assertNoSecret(fetched.body, hash = keyHash)
        assertFalse(listed.body.contains(secret))
        assertFalse(fetched.body.contains(secret))
        assertFalse(listed.body.contains("oipk_"))

        val reader = Http(gw.port())
        reader.authorizeBearer(secret)
        reader.sendCsrf = false
        assertEquals(200, reader.get("/api/v1/logs").status)
        assertProblem(reader.get("/api/v1/security/users"), 403, "forbidden")
        assertTrue((reader.get("/api/v1/security/users").json["detail"] as String).contains(Permissions.USERS_ADMIN))
        assertProblem(reader.post("/api/v1/security/users", """{"username":"x","password":"$TEST_PASSWORD"}"""), 403, "forbidden")

        val rotated = http.post("$keys/reader/rotate", "{}")
        assertEquals(200, rotated.status, rotated.body)
        val next = rotated.json["secret"] as String
        assertTrue(next.startsWith("oipk_"))
        assertTrue(next != secret)
        assertFalse(rotated.body.contains(secret))
        assertNoSecret(http.get("$keys/reader").body, hash = keyHash)
        assertFalse(http.get("$keys/reader").body.contains(next))
        assertFalse(http.get(keys).body.contains(next))
        assertEquals(1, auditTotal(AuditActions.CONFIG_UPDATED, "ApiKey/reader"))
        assertProblem(reader.get("/api/v1/logs"), 401, "unauthorized")
        val rotatedClient = Http(gw.port())
        rotatedClient.authorizeBearer(next)
        assertEquals(200, rotatedClient.get("/api/v1/logs").status)

        val accounts = http.post(keys, keyBody("accounts", listOf(Permissions.USERS_ADMIN)))
        assertEquals(201, accounts.status, accounts.body)
        val accountSecret = accounts.json["secret"] as String
        val machine = Http(gw.port())
        machine.authorizeBearer(accountSecret)
        machine.sendCsrf = false
        val ada = machine.post("/api/v1/security/users", """{"username":"ada","password":"$TEST_PASSWORD","roles":["viewer"]}""")
        assertEquals(201, ada.status, ada.body)
        assertNoSecret(ada.body, TEST_PASSWORD, stored(Kinds.USER, "ada", "passwordHash"))
        assertProblem(machine.post("/api/v1/security/roles", roleBody("shift", listOf(Permissions.TAGS_READ))), 403, "forbidden")
        assertTrue((machine.post(keys, keyBody("other", emptyList())).json["detail"] as String).contains(Permissions.APIKEYS_ADMIN))
        val byKey = gw.security.audit.query(AuditQuery(action = AuditActions.CONFIG_ADDED, actor = "accounts", targetContains = "User/ada"))
        assertEquals(1, byKey.total)
        assertTrue(byKey.records.single().timestampMillis > 0)
        assertEquals("127.0.0.1", byKey.records.single().actorHost)

        val revoked = http.delete("$keys/reader")
        assertEquals(200, revoked.status, revoked.body)
        assertEquals(false, revoked.json["enabled"])
        assertFalse(revoked.body.contains(next))
        assertFalse(revoked.body.contains("secret"))
        assertEquals(2, auditTotal(AuditActions.CONFIG_UPDATED, "ApiKey/reader"))
        assertProblem(rotatedClient.get("/api/v1/logs"), 401, "unauthorized")
        assertEquals(false, http.get("$keys/reader").json["enabled"])

        val page = http.get("/api/v1/audit?limit=1000").body
        assertFalse(page.contains(secret))
        assertFalse(page.contains(next))
        assertFalse(page.contains(accountSecret))
        assertFalse(page.contains(keyHash))
    }

    @Test fun viewerAndOperatorAreForbiddenOnEverySecurityWrite() {
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        val op = client("op")
        val view = client("view")
        val writes = listOf(
            { h: Http -> h.post("/api/v1/security/users", """{"username":"ada","password":"$TEST_PASSWORD"}""") },
            { h: Http -> h.put("/api/v1/security/users/admin", """{"displayName":"nope"}""") },
            { h: Http -> h.delete("/api/v1/security/users/admin") },
            { h: Http -> h.post("/api/v1/security/users/admin/password", """{"password":"$NEW_PASSWORD"}""") },
            { h: Http -> h.post("/api/v1/security/roles", roleBody("shift", listOf(Permissions.TAGS_READ))) },
            { h: Http -> h.put("/api/v1/security/roles/viewer", """{"description":"nope"}""") },
            { h: Http -> h.delete("/api/v1/security/roles/viewer") },
            { h: Http -> h.post("/api/v1/security/api-keys", keyBody("reader", listOf(Permissions.GATEWAY_READ))) },
            { h: Http -> h.post("/api/v1/security/api-keys/reader/rotate", "{}") },
            { h: Http -> h.delete("/api/v1/security/api-keys/reader") },
        )
        val reads = listOf(
            "/api/v1/security/users",
            "/api/v1/security/roles",
            "/api/v1/security/api-keys",
        )
        for (caller in listOf(op, view)) {
            for (read in reads) {
                val denied = caller.get(read)
                assertProblem(denied, 403, "forbidden")
                assertTrue((denied.json["detail"] as String).contains(".admin"), denied.body)
            }
            for (write in writes) {
                assertProblem(write(caller), 403, "forbidden")
            }
        }
        assertEquals(200, http.get("/api/v1/security/users").status)
        assertEquals(0, auditTotal(AuditActions.CONFIG_ADDED, "User/ada"))
        assertEquals(0, auditTotal(AuditActions.CONFIG_UPDATED, "Role/viewer"))
    }

    @Test fun openApiDocumentsSecurityRoutes() {
        val doc = http.get("/api/docs")
        assertEquals(200, doc.status, doc.body)
        val paths = doc.json["paths"] as Map<*, *>
        listOf(
            "/api/v1/security/users",
            "/api/v1/security/users/{name}",
            "/api/v1/security/users/{name}/password",
            "/api/v1/security/roles",
            "/api/v1/security/roles/{name}",
            "/api/v1/security/api-keys",
            "/api/v1/security/api-keys/{id}",
            "/api/v1/security/api-keys/{id}/rotate",
        ).forEach { assertTrue(paths.containsKey(it), it) }
    }

    private fun client(user: String): Http {
        val c = Http(gw.port())
        assertEquals(200, c.login(user, TEST_PASSWORD).status)
        return c
    }

    private fun send(method: String, path: String, json: String, ifMatch: String? = null): Http.Res {
        val headers = if (ifMatch == null) emptyMap() else mapOf("If-Match" to ifMatch)
        return http.send(method, path, json.toByteArray(), headers = headers)
    }

    private fun roleBody(name: String, permissions: List<String>, description: String = ""): String =
        Json.write(mapOf("name" to name, "permissions" to permissions, "description" to description))

    private fun keyBody(name: String, scopes: List<String>): String =
        Json.write(mapOf("name" to name, "scopes" to scopes, "secureConnectionRequired" to false, "description" to "plant"))

    private fun stored(kind: String, name: String, field: String): String {
        val resource = gw.store.get(ResourceId(SECURITY_MODULE, Scope.GATEWAY, kind, name))
        assertNotNull(resource)
        return resource!!.spec[field] as String
    }

    private fun auditTotal(action: String, target: String): Long =
        gw.security.audit.query(AuditQuery(action = action, targetContains = target, limit = 20)).total

    private fun assertOne(action: String, target: String) {
        val page = gw.security.audit.query(
            AuditQuery(action = action, actor = "admin", targetContains = target, status = AuditStatus.SUCCESS, limit = 20),
        )
        assertEquals(1, page.total, page.records.joinToString { "${it.action} ${it.target} ${it.detail}" })
        val row = page.records.single()
        assertEquals("admin", row.actor)
        assertTrue(row.timestampMillis > 0)
        assertTrue(row.target.contains(target), row.target)
        assertEquals("127.0.0.1", row.actorHost)
        assertFalse(row.detail.contains(TEST_PASSWORD))
        assertFalse(row.detail.contains(NEW_PASSWORD))
        assertFalse(row.detail.contains("passwordHash"))
        assertFalse(row.detail.contains("argon2"))
    }

    private fun assertNoSecret(body: String, password: String? = null, hash: String? = null, secret: String? = null) {
        assertFalse(body.contains("passwordHash"), body)
        assertFalse(body.contains("passwordHistory"), body)
        assertFalse(body.contains("keyHash"), body)
        assertFalse(body.contains("\$argon2"), body)
        if (password != null) assertFalse(body.contains(password), body)
        if (hash != null) assertFalse(body.contains(hash), body)
        if (secret != null) assertFalse(body.contains(secret), body)
    }

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
        assertEquals("urn:oip:problem:$slug", r.json["type"])
        assertEquals(status, (r.json["status"] as Number).toInt())
    }

    companion object {
        private const val NEW_PASSWORD = "Correct-horse-2"
    }
}
