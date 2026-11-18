package org.oip.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Library-level coverage for the internal user source, role matrix, API keys, zones and audit.
 * Password rules: -
 * API keys: -
 * Gateway areas: -
 */
class SecurityServiceTest {
    @TempDir lateinit var tmp: Path
    private val good = "Abcdefg1!"

    private fun open(clock: Clock = Clock.systemUTC(), hasher: PasswordHasher = Argon2idHasher.fast(), registry: SchemaRegistry = SchemaRegistry()) =
        openSecurity(tmp.resolve("run"), clock, hasher, registry = registry)

    @Test fun restartKeepsUsersKeysSecretsAndTheAuditChain() {
        val dir = tmp.resolve("restart")
        val (store, svc) = openSecurity(dir)
        val token = try {
            svc.users.create(CreateUser("alice", good.toCharArray(), setOf("operator")), "boss")
            val issued = svc.apiKeys.create("ci", setOf(Permissions.TAGS_READ), actor = "boss")
            svc.secrets.putText("db-password", "unique-plaintext-secret")
            assertTrue(svc.audit.verify().ok)
            issued.token
        } finally {
            svc.close()
            store.close()
        }
        val (store2, svc2) = openSecurity(dir, registry = SchemaRegistry())
        try {
            assertTrue(svc2.users.authenticate("alice", good.toCharArray()) is AuthenticationResult.Success)
            assertTrue(svc2.apiKeys.authenticate(token) is AuthenticationResult.Success)
            assertEquals("unique-plaintext-secret", svc2.secrets.getText("db-password"))
            assertTrue(svc2.audit.verify().ok)
            assertEquals(1, svc2.audit.query(AuditQuery(targetContains = "User/alice", action = AuditActions.CONFIG_ADDED)).total)
            val yaml = store2.file(org.oip.config.ResourceId(SECURITY_MODULE, Scope.GATEWAY, Kinds.USER, "alice")).readText()
            assertFalse(yaml.contains(good))
            assertFalse(yaml.contains(token))
        } finally {
            svc2.close()
            store2.close()
        }
    }

    @Test fun seedsRolesPolicyAndGatewaySettings() {
        val (store, svc) = open()
        try {
            assertEquals(setOf("administrator", "operator", "viewer"), svc.roles.list().map { it.name }.toSet())
            assertEquals(Permissions.ALL, svc.roles.get("administrator")!!.permissions)
            assertEquals(8, svc.passwordPolicy.get().minLength)
            assertEquals(90, svc.auditProfile.get().retentionDays)
            assertTrue(svc.auditProfile.get().enabled)
            assertFalse(svc.gatewaySecurity.get().allowUserAdministration)
            assertEquals(setOf("administrator"), svc.gatewaySecurity.get().designerRoles)
            assertEquals(0, svc.gatewaySecurity.get().inactivityTimeoutMinutes)
            assertTrue(store.file(org.oip.config.ResourceId(SECURITY_MODULE, Scope.GATEWAY, Kinds.ROLE, "administrator")).exists())
        } finally { svc.close(); store.close() }
    }

    @Test fun userLifecycleHidesTheHashAndPersists() {
        val clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC)
        val (store, svc) = open(clock)
        val password = good.toCharArray()
        try {
            val created = svc.users.create(CreateUser("alice", password, setOf("operator"), "Alice", "alice@plant.local", "555"), "boss", "10.1.1.1")
            assertEquals(0, password.count { it != '\u0000' })
            assertEquals(setOf("operator"), created.roles)
            assertFalse(created.passwordExpired)
            val fields = UserAccount::class.java.declaredFields.map { it.name }
            assertFalse(fields.any { it.contains("hash", ignoreCase = true) || it.contains("password", ignoreCase = true) && it != "passwordChangedAt" && it != "passwordExpired" })
            assertFalse(created.toString().contains(good))
            assertFalse(created.toString().contains("argon2"))
            val yaml = store.file(org.oip.config.ResourceId(SECURITY_MODULE, Scope.GATEWAY, Kinds.USER, "alice")).readText()
            assertTrue(yaml.contains("\$argon2id\$"))
            assertFalse(yaml.contains(good))
            val auth = svc.users.authenticate("alice", good.toCharArray(), "10.1.1.1")
            assertTrue(auth is AuthenticationResult.Success)
            assertEquals(setOf("operator"), svc.users.rolesOf("alice"))
            assertTrue(svc.users.authenticate("alice", "wrong-pass".toCharArray()) is AuthenticationResult.Failure)
            assertTrue(svc.users.authenticate("nobody", good.toCharArray()) is AuthenticationResult.Failure)
            assertEquals(
                AuthFailure.BAD_CREDENTIALS,
                (svc.users.authenticate("nobody", "xxxxxxxx".toCharArray()) as AuthenticationResult.Failure).reason,
            )
            svc.users.update(UpdateUser("alice", roles = setOf("viewer"), enabled = false), "boss")
            assertEquals(setOf("viewer"), svc.users.get("alice")!!.roles)
            val disabled = svc.users.authenticate("alice", good.toCharArray())
            assertEquals(AuthFailure.DISABLED, (disabled as AuthenticationResult.Failure).reason)
            svc.users.delete("alice", "boss")
            assertNull(svc.users.get("alice"))
        } finally { svc.close(); store.close() }

        val (store2, svc2) = openSecurity(tmp.resolve("run"), clock, registry = SchemaRegistry())
        try {
            assertNull(svc2.users.get("alice"))
            assertEquals(3, svc2.roles.list().size) // deleted user, roles remain; reopen does not resurrect alice
        } finally { svc2.close(); store2.close() }
    }

    @Test fun missingUserPaysForAHashComparison() {
        val counting = CountingHasher(Argon2idHasher.fast())
        val (store, svc) = open(hasher = counting)
        try {
            assertTrue(counting.hashes >= 1) // dummy hash so an unknown user is not cheaper
            counting.verifies = 0
            svc.users.authenticate("ghost", "Abcdefg1!".toCharArray())
            assertEquals(1, counting.verifies)
            svc.users.create(CreateUser("alice", good.toCharArray(), setOf("viewer")), "boss")
            counting.verifies = 0
            svc.users.authenticate("alice", "zzzzzzzz".toCharArray())
            assertEquals(1, counting.verifies)
        } finally { svc.close(); store.close() }
    }

    @Test fun passwordPolicyIsEnforcedIncludingHistoryAndAge() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val clock = MutableClock(start)
        val (store, svc) = open(clock)
        try {
            svc.passwordPolicy.update(PasswordPolicy(history = 0, maxAgeDays = 0), "boss")
            svc.users.create(CreateUser("alice", "First111!".toCharArray(), setOf("administrator")), "boss")
            // History is off, so replacing First does not store it.
            svc.users.setPassword("alice", "Second22!".toCharArray(), "boss")
            svc.passwordPolicy.update(PasswordPolicy(history = 2, maxAgeDays = 0, minLength = 8, complexity = 3), "boss")
            svc.users.setPassword("alice", "First111!".toCharArray(), "boss")
            val reused = assertThrows(PasswordRejectedException::class.java) {
                svc.users.setPassword("alice", "First111!".toCharArray(), "boss")
            }
            assertTrue(reused.violations.any { it.rule == PasswordRule.HISTORY })
            svc.users.setPassword("alice", "Third333!".toCharArray(), "boss")
            assertThrows(PasswordRejectedException::class.java) { svc.users.setPassword("alice", "Second22!".toCharArray(), "boss") }
        } finally { svc.close(); store.close() }
    }

    @Test fun wrongCurrentPasswordDoesNotChangeIt() {
        val (store, svc) = open()
        try {
            svc.users.create(CreateUser("alice", good.toCharArray(), setOf("administrator")), "boss")
            assertThrows(AccessDeniedException::class.java) {
                svc.users.changePassword("alice", "not-the-password".toCharArray(), "Newpass1!".toCharArray(), "alice")
            }
            assertTrue(svc.users.authenticate("alice", good.toCharArray()) is AuthenticationResult.Success)
            svc.users.changePassword("alice", good.toCharArray(), "Newpass1!".toCharArray(), "alice")
            assertTrue(svc.users.authenticate("alice", "Newpass1!".toCharArray()) is AuthenticationResult.Success)
        } finally { svc.close(); store.close() }
    }

    @Test fun expiredPasswordAuthenticatesAsExpired() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val (store, svc) = open(clock)
        try {
            svc.passwordPolicy.update(PasswordPolicy(maxAgeDays = 10), "boss")
            svc.users.create(CreateUser("alice", good.toCharArray(), setOf("viewer")), "boss")
            clock.instant = clock.instant.plusSeconds(10L * 86_400L - 1)
            assertTrue(svc.users.authenticate("alice", good.toCharArray()) is AuthenticationResult.Success)
            clock.instant = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(10L * 86_400L)
            val expired = svc.users.authenticate("alice", good.toCharArray())
            assertTrue(expired is AuthenticationResult.PasswordExpired)
            assertTrue(svc.users.get("alice")!!.passwordExpired)
            // wrong password still looks like bad credentials, not expiry
            assertEquals(AuthFailure.BAD_CREDENTIALS, (svc.users.authenticate("alice", "xxxxxxxx".toCharArray()) as AuthenticationResult.Failure).reason)
        } finally { svc.close(); store.close() }
    }

    @Test fun clientUserAdminFlag() {
        val (store, svc) = open()
        try {
            assertThrows(AccessDeniedException::class.java) {
                svc.users.create(CreateUser("alice", good.toCharArray()), "boss", caller = CallerScope.CLIENT)
            }
            assertThrows(AccessDeniedException::class.java) {
                svc.roles.create("night", setOf(Permissions.TAGS_READ), actor = "boss", caller = CallerScope.DESIGNER)
            }
            svc.users.create(CreateUser("alice", good.toCharArray(), setOf("administrator")), "boss", caller = CallerScope.GATEWAY)
            svc.users.changePassword("alice", good.toCharArray(), "Newpass1!".toCharArray(), "alice", caller = CallerScope.CLIENT)
            val settings = svc.gatewaySecurity.get()
            svc.gatewaySecurity.update(settings.copy(allowUserAdministration = true), "boss")
            svc.users.create(CreateUser("bob", good.toCharArray(), setOf("viewer")), "boss", caller = CallerScope.CLIENT)
            assertNotNull(svc.users.get("bob"))
            assertThrows(ValidationException::class.java) {
                svc.passwordPolicy.update(PasswordPolicy(minLength = 0), "boss")
            }
            assertThrows(IllegalArgumentException::class.java) { svc.users.create(CreateUser("Bad", good.toCharArray()), "boss") }
            assertThrows(NotFoundException::class.java) { svc.users.create(CreateUser("carol", good.toCharArray(), setOf("missing")), "boss") }
        } finally { svc.close(); store.close() }
    }

    @Test fun permissionMatrixAndZoneCeiling() {
        val (store, svc) = open()
        try {
            svc.users.create(CreateUser("ada", good.toCharArray(), setOf("administrator")), "boss")
            svc.users.create(CreateUser("oli", good.toCharArray(), setOf("operator")), "boss")
            svc.users.create(CreateUser("vera", good.toCharArray(), setOf("viewer")), "boss")
            val local = ConnectionContext.local()
            fun allow(user: String, permission: String) = svc.authorization.authorize(subject(svc, user), permission, local).allowed
            assertTrue(allow("ada", Permissions.GATEWAY_WRITE))
            assertTrue(allow("ada", Permissions.GATEWAY_READ)) // write implies read
            assertTrue(allow("ada", Permissions.DESIGNER_LOGIN))
            assertTrue(allow("ada", Permissions.USERS_ADMIN))
            assertTrue(allow("oli", Permissions.TAGS_WRITE))
            assertTrue(allow("oli", Permissions.GATEWAY_READ))
            assertFalse(allow("oli", Permissions.GATEWAY_WRITE))
            assertFalse(allow("oli", Permissions.DESIGNER_LOGIN))
            assertTrue(allow("vera", Permissions.TAGS_READ))
            assertTrue(allow("vera", Permissions.GATEWAY_ACCESS))
            assertFalse(allow("vera", Permissions.TAGS_WRITE))
            assertFalse(allow("vera", Permissions.GATEWAY_READ))

            val decision = svc.authorization.authorize(null, Permissions.TAGS_READ, local)
            assertEquals(DecisionReason.UNAUTHENTICATED, decision.reason)
            assertEquals("Default", decision.zone)
            assertEquals(DecisionReason.UNKNOWN_PERMISSION, svc.authorization.authorize(subject(svc, "ada"), "nope", local).reason)

            // settings can grant an area the role resource does not list
            val onlyCustom = svc.roles.create("custom", emptySet(), actor = "boss")
            svc.users.create(CreateUser("cam", good.toCharArray(), setOf(onlyCustom.name)), "boss")
            assertFalse(allow("cam", Permissions.GATEWAY_WRITE))
            val settings = svc.gatewaySecurity.get()
            svc.gatewaySecurity.update(settings.copy(writePublic = true), "boss")
            assertTrue(allow("cam", Permissions.GATEWAY_WRITE))
            assertTrue(allow("cam", Permissions.GATEWAY_ACCESS))
            // Match-all is visible only on a role that does not already list gateway.write.
            svc.gatewaySecurity.update(settings.copy(writePublic = false, writeRoles = setOf("custom", "operator"), writeMatch = MatchMode.ALL), "boss")
            assertFalse(allow("cam", Permissions.GATEWAY_WRITE))
            svc.users.update(UpdateUser("cam", roles = setOf("custom", "operator")), "boss")
            assertTrue(allow("cam", Permissions.GATEWAY_WRITE))

            svc.zones.create(SecurityZone("remote", priority = 10, ipAddresses = listOf("10.0.0.0/8"), allowedPermissions = listOf(Permissions.TAGS_READ)), "boss")
            val remote = ConnectionContext(java.net.InetAddress.getByName("10.1.2.3"), "hmi", null, true, true, RequestScope.GATEWAY)
            val denied = svc.authorization.authorize(subject(svc, "ada"), Permissions.TAGS_WRITE, remote)
            assertEquals(DecisionReason.ZONE_DENY, denied.reason)
            assertEquals("remote", denied.zone)
            assertTrue(svc.authorization.authorize(subject(svc, "ada"), Permissions.TAGS_READ, remote).allowed)
            svc.zones.update(SecurityZone("remote", priority = 10, ipAddresses = listOf("10.0.0.0/8"), deniedPermissions = listOf(Permissions.GATEWAY_READ), allowedPermissions = listOf(Permissions.GATEWAY_WRITE)), "boss")
            assertEquals(DecisionReason.ZONE_DENY, svc.authorization.authorize(subject(svc, "ada"), Permissions.GATEWAY_READ, remote).reason)
            assertTrue(svc.authorization.authorize(subject(svc, "ada"), Permissions.GATEWAY_WRITE, remote).allowed)

            svc.roles.delete("viewer", "boss")
            svc.close()
        } finally { store.close() }

        val (store2, svc2) = openSecurity(tmp.resolve("run"), registry = SchemaRegistry())
        try {
            assertNull(svc2.roles.get("viewer"))
        } finally { svc2.close(); store2.close() }
    }

    @Test fun apiKeysAreHashedScopedRotatableAndRevocable() {
        val (store, svc) = open()
        try {
            val issued = svc.apiKeys.create("ci", setOf(Permissions.TAGS_READ), "pipeline", secureConnectionRequired = true, actor = "boss", host = "10.0.0.8")
            assertTrue(issued.token.startsWith("oipk_"))
            assertFalse(issued.toString().contains(issued.token.substringAfterLast('_')))
            val yaml = store.file(org.oip.config.ResourceId(SECURITY_MODULE, Scope.GATEWAY, Kinds.API_KEY, "ci")).readText()
            assertFalse(yaml.contains(issued.token))
            assertTrue(yaml.contains(issued.info.keyId))
            val keyFields = ApiKeyInfo::class.java.declaredFields.map { it.name }
            assertFalse(keyFields.any { it.contains("hash", ignoreCase = true) || it.contains("token", ignoreCase = true) })
            val ok = svc.apiKeys.authenticate(issued.token, "10.0.0.8", secure = true)
            assertTrue(ok is AuthenticationResult.Success)
            val insecure = svc.apiKeys.authenticate(issued.token, secure = false)
            assertEquals(AuthFailure.INSECURE_CHANNEL, (insecure as AuthenticationResult.Failure).reason)
            assertEquals(AuthFailure.BAD_CREDENTIALS, (svc.apiKeys.authenticate("oipk_0123456789abcdef_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") as AuthenticationResult.Failure).reason)
            assertEquals(AuthFailure.BAD_CREDENTIALS, (svc.apiKeys.authenticate("not-a-token") as AuthenticationResult.Failure).reason)
            val subject = (svc.apiKeys.authenticate(issued.token) as AuthenticationResult.Success).subject
            val local = ConnectionContext.local()
            assertTrue(svc.authorization.authorize(subject, Permissions.TAGS_READ, local).allowed)
            assertEquals(DecisionReason.PERMISSION_DENIED, svc.authorization.authorize(subject, Permissions.TAGS_WRITE, local).reason)
            // public gateway write does not widen an API key
            svc.gatewaySecurity.update(svc.gatewaySecurity.get().copy(writePublic = true), "boss")
            assertEquals(DecisionReason.PERMISSION_DENIED, svc.authorization.authorize(subject, Permissions.GATEWAY_WRITE, local).reason)
            val rotated = svc.apiKeys.rotate("ci", "boss")
            assertEquals(AuthFailure.BAD_CREDENTIALS, (svc.apiKeys.authenticate(issued.token) as AuthenticationResult.Failure).reason)
            assertTrue(svc.apiKeys.authenticate(rotated.token) is AuthenticationResult.Success)
            svc.apiKeys.revoke("ci", "boss")
            assertEquals(AuthFailure.DISABLED, (svc.apiKeys.authenticate(rotated.token) as AuthenticationResult.Failure).reason)
            svc.apiKeys.enable("ci", "boss")
            assertTrue(svc.apiKeys.authenticate(rotated.token) is AuthenticationResult.Success)
            svc.apiKeys.delete("ci", "boss")
            assertEquals(AuthFailure.BAD_CREDENTIALS, (svc.apiKeys.authenticate(rotated.token) as AuthenticationResult.Failure).reason)
            assertThrows(AlreadyExistsException::class.java) {
                svc.apiKeys.create("ci", emptySet(), actor = "boss")
                svc.apiKeys.create("ci", emptySet(), actor = "boss")
            }
        } finally { svc.close(); store.close() }
    }

    @Test fun zonesPersistAndRejectABadOrDefaultZone() {
        val (store, svc) = open()
        try {
            assertThrows(AccessDeniedException::class.java) { svc.zones.create(SecurityZone("default", priority = 1, ipAddresses = listOf("10.0.0.1")), "boss") }
            assertThrows(IllegalArgumentException::class.java) { svc.zones.create(SecurityZone("empty", priority = 1), "boss") }
            assertThrows(IllegalArgumentException::class.java) { svc.zones.create(SecurityZone("bad", priority = 1, ipAddresses = listOf("nope")), "boss") }
            svc.zones.create(SecurityZone("plant", priority = 4, hostNames = listOf("*_Workstation"), description = "hmis"), "boss")
            val hit = svc.zones.match(ConnectionContext(null, "Joe_Workstation", null, true, true, RequestScope.CLIENT))
            assertEquals("plant", hit.name)
            assertTrue(svc.zones.list().any { it.name == "Default" })
            svc.zones.delete("plant", "boss")
            assertEquals("Default", svc.zones.match(ConnectionContext.local()).name)
        } finally { svc.close(); store.close() }
    }

    @Test fun eachConfigWriteLoginAndSecretProducesOneAuditRecord() {
        val (store, svc) = open()
        try {
            val registry = store.registry
            registry.register("sample.mod", "Widget", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
            store.put(Scope.GATEWAY, Resource("Widget", Metadata("primary", "sample.mod"), mapOf("x" to 1)), "carol")
            val added = svc.audit.query(AuditQuery(action = "GATEWAY CONFIG ADDED", targetContains = "Widget/primary"))
            assertEquals(1, added.total)
            assertEquals("carol", added.records.single().actor)
            assertTrue(added.records.single().timestampMillis > 0)
            assertEquals(AuditStatus.SUCCESS, added.records.single().status)

            svc.users.create(CreateUser("alice", good.toCharArray(), setOf("administrator")), "boss", "10.0.0.5")
            val userAdded = svc.audit.query(AuditQuery(targetContains = "User/alice", action = AuditActions.CONFIG_ADDED))
            assertEquals(1, userAdded.total)
            val entry = userAdded.records.single()
            assertEquals("boss", entry.actor)
            assertEquals("10.0.0.5", entry.actorHost)
            assertFalse(entry.detail.contains(good))
            assertFalse(entry.detail.contains("argon2"))
            assertFalse(entry.detail.contains("passwordHash"))

            svc.users.authenticate("alice", good.toCharArray(), "10.0.0.5")
            val login = svc.audit.query(AuditQuery(action = AuditActions.LOGIN, actor = "alice"))
            assertEquals(1, login.total)
            svc.users.authenticate("alice", "bad-password".toCharArray(), "10.0.0.5")
            assertEquals(1, svc.audit.query(AuditQuery(action = AuditActions.LOGIN_ATTEMPT, actor = "alice")).total)
            val dumped = svc.audit.query(AuditQuery(actor = "alice", limit = 50)).records.joinToString { it.detail }
            assertFalse(dumped.contains(good))
            assertFalse(dumped.contains("bad-password"))

            val key = svc.apiKeys.create("ci", setOf(Permissions.AUDIT_READ), actor = "boss")
            svc.apiKeys.authenticate(key.token, secure = true)
            assertEquals(1, svc.audit.query(AuditQuery(action = AuditActions.TOKEN_LOGIN, status = AuditStatus.SUCCESS, targetContains = "ci")).total)

            AuditContext.call("boss", "10.9.9.9") { svc.secrets.putText("db-password", "unique-plaintext-secret") }
            val secretAudit = svc.audit.query(AuditQuery(action = AuditActions.SECRET_WRITTEN))
            assertEquals(1, secretAudit.total)
            assertEquals("boss", secretAudit.records.single().actor)
            assertEquals("10.9.9.9", secretAudit.records.single().actorHost)
            assertFalse(secretAudit.records.single().detail.contains("unique-plaintext-secret"))
            val resolved = svc.resolveSecretReferences(mapOf("password" to "secret://db-password", "plain" to "keep"))
            assertEquals("unique-plaintext-secret", resolved["password"])
            assertEquals("keep", resolved["plain"])

            svc.auditProfile.update(svc.auditProfile.get().copy(enabled = false), "boss")
            val before = svc.audit.query(AuditQuery(limit = 1)).total
            svc.users.update(UpdateUser("alice", displayName = "A"), "boss")
            assertEquals(before, svc.audit.query(AuditQuery(limit = 1)).total)
            assertTrue(svc.audit.verify().ok)
        } finally { svc.close(); store.close() }
    }

    @Test fun configBatchWritesOneAuditRecord() {
        val (store, svc) = open()
        try {
            store.registry.register(
                "sample.mod", "Widget",
                mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))),
            )
            val resources = (1..25).map { i ->
                Resource("Widget", Metadata("w$i", "sample.mod"), mapOf("x" to i))
            }
            store.putAll(Scope.GATEWAY, resources, actor = "carol", syncEach = false)
            val batch = svc.audit.query(AuditQuery(action = AuditActions.CONFIG_BATCH))
            assertEquals(1, batch.total)
            val record = batch.records.single()
            assertEquals("carol", record.actor)
            assertEquals("config/batch", record.target)
            assertTrue(record.detail.contains("created=25"), record.detail)
            assertTrue(record.detail.contains("updated=0"), record.detail)
            assertEquals(0, svc.audit.query(AuditQuery(action = AuditActions.CONFIG_ADDED, targetContains = "Widget/w1")).total)
            store.put(Scope.GATEWAY, Resource("Widget", Metadata("solo", "sample.mod"), mapOf("x" to 1)), "carol")
            assertEquals(1, svc.audit.query(AuditQuery(action = AuditActions.CONFIG_ADDED, targetContains = "Widget/solo")).total)
            assertEquals(1, svc.audit.query(AuditQuery(action = AuditActions.CONFIG_BATCH)).total)
        } finally { svc.close(); store.close() }
    }

    @Test fun roleDeletionRemovesGrantsButLeavesTheUser() {
        val (store, svc) = open()
        try {
            svc.users.create(CreateUser("oli", good.toCharArray(), setOf("operator")), "boss")
            svc.roles.delete("operator", "boss")
            val decision = svc.authorization.authorize(subject(svc, "oli"), Permissions.TAGS_WRITE, ConnectionContext.local())
            assertEquals(DecisionReason.PERMISSION_DENIED, decision.reason)
            assertEquals(setOf("operator"), svc.users.get("oli")!!.roles)
        } finally { svc.close(); store.close() }
    }

    @Test fun auditFailureDoesNotRollBackTheConfigWrite() {
        val sink = MemoryAuditSink()
        var fail = false
        val flaky = object : AuditSink by sink {
            override fun append(record: AuditRecord) {
                if (fail) error("disk full")
                sink.append(record)
            }
        }
        val (store, svc) = openSecurity(tmp.resolve("flaky"), runtime = RuntimeStores(flaky, MemorySecretBlobStore()))
        try {
            fail = true
            svc.users.create(CreateUser("alice", good.toCharArray(), setOf("viewer")), "boss")
            assertEquals("alice", svc.users.get("alice")!!.username)
            assertTrue(svc.auditErrors() >= 1)
        } finally { svc.close(); store.close() }
    }
}

private fun subject(svc: SecurityService, username: String): Subject.User {
    val user = svc.users.get(username)!!
    return Subject.User(username, user.roles, user.enabled)
}

private class MutableClock(var instant: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant() = instant
}
