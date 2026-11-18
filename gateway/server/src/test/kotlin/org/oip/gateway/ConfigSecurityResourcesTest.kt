package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.backup.ZipIo
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import org.oip.security.Kinds
import org.oip.security.Permissions
import org.oip.security.SECURITY_MODULE
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Review-1 F1: the generic config API must not expose or accept the security module's credential resources.
 * Users, roles, and API keys are managed through the `/api/v1/security` routes only (password policy, hashing, audit);
 * the other security settings need the security administration permissions. No response carries a password
 * hash, password history, or API-key hash.
 */
class ConfigSecurityResourcesTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http

    private val base = "/api/v1/config/$SECURITY_MODULE/gateway"

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.security.roles.create("configwriter", setOf(Permissions.GATEWAY_WRITE, Permissions.GATEWAY_ACCESS), actor = "test")
        gw.createUser("writer", setOf("configwriter"))
        gw.security.apiKeys.create("reader", setOf(Permissions.GATEWAY_READ), actor = "test", secureConnectionRequired = false)
        admin = client("admin")
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun operatorCannotReadHashesThroughTheConfigApi() {
        val op = client("op")
        val user = op.get("$base/${Kinds.USER}/admin")
        assertEquals(403, user.status, user.body)
        assertNoSecrets(user)
        assertEquals(403, op.get("$base/${Kinds.USER}").status)
        assertEquals(403, op.get("$base/${Kinds.API_KEY}/reader").status)

        for (path in listOf("/api/v1/config?module=$SECURITY_MODULE", "/api/v1/config", "/api/v1/config?kind=${Kinds.USER}")) {
            val listed = op.get(path)
            assertEquals(200, listed.status, listed.body)
            assertNoSecrets(listed)
            val kinds = items(listed).filter { meta(it)["module"] == SECURITY_MODULE }.map { it["kind"] }
            assertFalse(Kinds.USER in kinds, "$path lists users to an operator: $kinds")
            assertFalse(Kinds.API_KEY in kinds, "$path lists API keys to an operator: $kinds")
        }
        // Settings that hold no credential stay readable to gateway.read.
        assertEquals(200, op.get("$base/${Kinds.AUDIT_PROFILE}/internal").status)
    }

    @Test fun adminReadsAreRedacted() {
        val user = admin.get("$base/${Kinds.USER}/admin")
        assertEquals(200, user.status, user.body)
        assertNoSecrets(user)
        assertEquals(listOf(DefaultRoles.ADMINISTRATOR), spec(user.json)["roles"])
        val key = admin.get("$base/${Kinds.API_KEY}/reader")
        assertEquals(200, key.status, key.body)
        assertNoSecrets(key)
        val listed = admin.get("/api/v1/config?module=$SECURITY_MODULE")
        assertEquals(200, listed.status)
        assertNoSecrets(listed)
        assertTrue(items(listed).any { it["kind"] == Kinds.USER && meta(it)["name"] == "admin" })
        assertNoSecrets(admin.get("$base/${Kinds.USER}"))
    }

    @Test fun gatewayWriteCannotCreateOrChangeUsersRolesOrKeys() {
        val writer = client("writer")
        val userDoc = userJson("eve", gw.store.get(org.oip.config.ResourceId(SECURITY_MODULE, Scope.GATEWAY, Kinds.USER, "admin"))!!.spec["passwordHash"] as String)
        assertEquals(403, writer.post("$base/${Kinds.USER}", userDoc).status)
        assertEquals(403, writer.put("$base/${Kinds.USER}/eve", userDoc).status)
        assertEquals(403, writer.send("PATCH", "$base/${Kinds.USER}/op", """{"spec":{"roles":["administrator"]}}""".toByteArray()).status)
        assertEquals(403, writer.send("PATCH", "$base/${Kinds.USER}/op", """{"roles":["administrator"]}""".toByteArray()).status)
        assertEquals(403, writer.delete("$base/${Kinds.USER}/op").status)
        assertEquals(403, writer.post("$base/${Kinds.ROLE}", roleJson("owner", Permissions.ALL)).status)
        assertEquals(403, writer.send("PATCH", "$base/${Kinds.ROLE}/configwriter", """{"permissions":["users.admin","gateway.write"]}""".toByteArray()).status)
        assertEquals(403, writer.post("$base/${Kinds.API_KEY}", apiKeyJson("backdoor")).status)
        assertEquals(403, writer.delete("$base/${Kinds.API_KEY}/reader").status)
        assertEquals(403, writer.put("/api/v1/config/$SECURITY_MODULE/mode/dev/${Kinds.USER}/eve", userDoc).status)
        val batch = """{"items":[$userDoc]}"""
        assertEquals(403, writer.post("/api/v1/config/batch", batch).status)
        // Security settings also need the security administration permissions.
        assertEquals(403, writer.send("PATCH", "$base/${Kinds.PASSWORD_POLICY}/default", """{"minLength":1}""".toByteArray()).status)
        assertEquals(403, writer.send("PATCH", "$base/${Kinds.AUDIT_PROFILE}/internal", """{"enabled":false}""".toByteArray()).status)

        assertNull(gw.security.users.get("eve"))
        assertEquals(setOf(DefaultRoles.OPERATOR), gw.security.users.get("op")!!.roles)
        assertEquals(setOf(Permissions.GATEWAY_WRITE, Permissions.GATEWAY_ACCESS), gw.security.roles.get("configwriter")!!.permissions)
        assertNull(gw.security.roles.get("owner"))
        assertNull(gw.security.apiKeys.get("backdoor"))
        assertNotNull(gw.security.apiKeys.get("reader"))
        assertTrue(gw.security.auditProfile.get().enabled)
    }

    @Test fun credentialKindsAreNotWritableThroughTheConfigApiEvenForAdmin() {
        val hash = gw.store.get(org.oip.config.ResourceId(SECURITY_MODULE, Scope.GATEWAY, Kinds.USER, "op"))!!.spec["passwordHash"] as String
        val created = admin.post("$base/${Kinds.USER}", userJson("eve", hash))
        assertEquals(403, created.status, created.body)
        assertTrue(created.body.contains("/api/v1/security/users"), created.body)
        assertEquals(403, admin.post("$base/${Kinds.ROLE}", roleJson("owner", setOf(Permissions.GATEWAY_READ))).status)
        assertEquals(403, admin.post("/api/v1/config/batch", """{"items":[${userJson("eve", hash)}]}""").status)
        assertNull(gw.security.users.get("eve"))
        // Admins still edit the other security settings here (the admin UI has no dedicated page for them).
        val patched = admin.send("PATCH", "$base/${Kinds.AUDIT_PROFILE}/internal", """{"retentionDays":45}""".toByteArray())
        assertEquals(200, patched.status, patched.body)
    }

    /** The escalation reproduced in review-1: copy the admin hash into a new administrator account. */
    @Test fun malloryEscalationFails() {
        val writer = client("writer")
        assertEquals(403, writer.get("/api/v1/security/users").status)
        val leaked = writer.get("$base/${Kinds.USER}/admin")
        assertNoSecrets(leaked)
        val adminHash = gw.store.get(org.oip.config.ResourceId(SECURITY_MODULE, Scope.GATEWAY, Kinds.USER, "admin"))!!.spec["passwordHash"] as String
        val created = writer.post("$base/${Kinds.USER}", userJson("mallory", adminHash))
        assertEquals(403, created.status, created.body)
        assertNull(gw.security.users.get("mallory"))
        val mallory = Http(gw.port())
        assertEquals(401, mallory.login("mallory", TEST_PASSWORD).status)
        assertEquals(401, mallory.get("/api/v1/security/users").status)
    }

    @Test fun projectExportAndImportDoNotCarryCredentials() {
        // A credential resource in a project scope (placed on disk, not through the API) is never exported.
        val hash = gw.store.get(org.oip.config.ResourceId(SECURITY_MODULE, Scope.GATEWAY, Kinds.USER, "admin"))!!.spec["passwordHash"] as String
        gw.store.put(Scope.project("plant"), Resource(Kinds.USER, Metadata("ghost", SECURITY_MODULE), userSpec(hash)))
        gw.store.put(Scope.project("plant"), Resource(Kinds.AUDIT_PROFILE, Metadata("main", SECURITY_MODULE), mapOf("retentionDays" to 30)))
        val op = client("op")
        val exported = op.get("/api/v1/projects/plant/export")
        assertEquals(200, exported.status, exported.body)
        val entries = ZipIo.read(exported.raw)
        assertFalse(entries.keys.any { it.contains("/${Kinds.USER}/") }, entries.keys.toString())
        assertFalse(entries.values.any { String(it).contains("\$argon2") })

        // Import of a credential kind is refused for every caller; security settings need security administration.
        val userZip = zip("$SECURITY_MODULE/project/plant/${Kinds.USER}/mallory.yaml" to userYaml("mallory", hash))
        assertEquals(403, client("writer").postRaw("/api/v1/projects/plant/import?policy=overwrite", userZip, "application/zip").status)
        assertEquals(403, admin.postRaw("/api/v1/projects/plant/import?policy=overwrite", userZip, "application/zip").status)
        assertNull(gw.store.get(org.oip.config.ResourceId(SECURITY_MODULE, Scope.project("plant"), Kinds.USER, "mallory")))
        val settingsZip = zip("$SECURITY_MODULE/project/plant/${Kinds.AUDIT_PROFILE}/other.yaml" to
            "apiVersion: oip/v1\nkind: ${Kinds.AUDIT_PROFILE}\nmetadata:\n  name: other\n  module: $SECURITY_MODULE\nspec:\n  retentionDays: 10\n")
        assertEquals(403, client("writer").postRaw("/api/v1/projects/plant/import?policy=overwrite", settingsZip, "application/zip").status)
        assertEquals(200, admin.postRaw("/api/v1/projects/plant/import?policy=overwrite", settingsZip, "application/zip").status)
    }

    private fun client(user: String): Http {
        val c = Http(gw.port())
        assertEquals(200, c.login(user, TEST_PASSWORD).status)
        return c
    }

    private fun assertNoSecrets(r: Http.Res) {
        for (s in listOf("passwordHash", "passwordHistory", "keyHash", "\$argon2")) assertFalse(r.body.contains(s), "response exposes $s: ${r.body.take(400)}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(r: Http.Res) = r.json["items"] as List<Map<String, Any?>>
    private fun meta(item: Map<String, Any?>) = item["metadata"] as Map<*, *>
    private fun spec(json: Map<String, Any?>) = json["spec"] as Map<*, *>

    private fun userSpec(hash: String) = mapOf(
        "enabled" to true, "passwordHash" to hash, "passwordChangedAt" to "2026-01-01T00:00:00Z",
        "passwordHistory" to emptyList<String>(), "roles" to listOf(DefaultRoles.ADMINISTRATOR),
    )

    private fun userJson(name: String, hash: String) =
        """{"apiVersion":"oip/v1","kind":"${Kinds.USER}","metadata":{"name":"$name","module":"$SECURITY_MODULE","version":0},""" +
            """"spec":{"enabled":true,"passwordHash":"$hash","passwordChangedAt":"2026-01-01T00:00:00Z","passwordHistory":[],"roles":["administrator"]}}"""

    private fun userYaml(name: String, hash: String) =
        "apiVersion: oip/v1\nkind: ${Kinds.USER}\nmetadata:\n  name: $name\n  module: $SECURITY_MODULE\nspec:\n  enabled: true\n" +
            "  passwordHash: '$hash'\n  passwordChangedAt: '2026-01-01T00:00:00Z'\n  passwordHistory: []\n  roles: [administrator]\n"

    private fun roleJson(name: String, perms: Set<String>) =
        """{"apiVersion":"oip/v1","kind":"${Kinds.ROLE}","metadata":{"name":"$name","module":"$SECURITY_MODULE","version":0},""" +
            """"spec":{"permissions":[${perms.joinToString(",") { "\"$it\"" }}]}}"""

    private fun apiKeyJson(name: String) =
        """{"apiVersion":"oip/v1","kind":"${Kinds.API_KEY}","metadata":{"name":"$name","module":"$SECURITY_MODULE","version":0},""" +
            """"spec":{"enabled":true,"keyId":"0123456789abcdef","keyHash":"${"a".repeat(64)}","scopes":["gateway.write"],""" +
            """"secureConnectionRequired":false,"createdAt":"2026-01-01T00:00:00Z"}}"""

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("oip-project.json"))
            z.write("""{"format":"oip-project","formatVersion":1,"project":"plant","resources":[]}""".toByteArray())
            z.closeEntry()
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry("files/$name"))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
