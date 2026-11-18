package org.oip.security

import org.oip.config.ConflictException
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.config.SchemaRegistry
import org.oip.config.ValidationException
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.security.SecureRandom

const val SECURITY_MODULE = "org.oip.security"

object Kinds {
    const val USER = "User"
    const val ROLE = "Role"
    const val PASSWORD_POLICY = "PasswordPolicy"
    const val API_KEY = "ApiKey"
    const val SECURITY_ZONE = "SecurityZone"
    const val GATEWAY_SECURITY = "GatewaySecurity"
    const val AUDIT_PROFILE = "AuditProfile"
    /** An external identity provider (OIDC or SAML), SEC-014. Written only through `/api/v1/security/identity-providers`. */
    const val IDENTITY_PROVIDER = "IdentityProvider"
    /** An OAuth2 client registration for outbound services, SEC-029. Written only through `/api/v1/security/oauth2-clients`. */
    const val OAUTH2_CLIENT = OAuth2Client.KIND
    /** An external user source (LDAP, database, or a hybrid), SEC-007/010/021/025. Written through `/api/v1/security/user-sources`. */
    const val USER_SOURCE = "UserSource"
}

internal object Specs {
    fun str(spec: Map<String, Any?>, key: String): String? = spec[key] as? String
    fun bool(spec: Map<String, Any?>, key: String, default: Boolean = false): Boolean = spec[key] as? Boolean ?: default
    fun int(spec: Map<String, Any?>, key: String, default: Int): Int = when (val v = spec[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        null -> default
        else -> error("bad integer '$key'")
    }
    fun strings(spec: Map<String, Any?>, key: String): List<String> =
        (spec[key] as? List<*>)?.map { it.toString() } ?: emptyList()
    fun instant(spec: Map<String, Any?>, key: String): Instant =
        Instant.parse(str(spec, key) ?: error("missing $key"))
}

/**
 * Optimistic read-modify-write against [FileConfigStore]. The actor is published on [AuditContext]
 * for the duration of the write so the config listener records who did it (delete carries no actor of its own).
 */
internal class ConfigDocs(val store: FileConfigStore, val registry: SchemaRegistry) {
    fun get(kind: String, name: String): Resource? = store.get(ResourceId(SECURITY_MODULE, Scope.GATEWAY, kind, name))

    fun spec(kind: String, name: String): Map<String, Any?>? {
        val r = get(kind, name) ?: return null
        return registry.applyDefaults(SECURITY_MODULE, kind, r.spec)
    }

    fun list(kind: String): List<Resource> =
        store.list(module = SECURITY_MODULE, scope = Scope.GATEWAY, kind = kind).map { it.resource }

    fun modify(kind: String, name: String, actor: String, host: String = "", build: (current: Map<String, Any?>?) -> Map<String, Any?>): Resource {
        return AuditContext.call(actor, host) {
            var last: ConflictException? = null
            repeat(8) {
                val cur = get(kind, name)
                val currentSpec = cur?.let { registry.applyDefaults(SECURITY_MODULE, kind, it.spec) }
                val spec = build(currentSpec)
                try {
                    return@call store.put(
                        Scope.GATEWAY,
                        Resource(kind, Metadata(name, SECURITY_MODULE, version = cur?.metadata?.version ?: 0L), spec),
                        actor,
                    )
                } catch (e: ConflictException) {
                    last = e
                }
            }
            throw last ?: ConflictException("could not write $kind/$name")
        }
    }

    fun remove(kind: String, name: String, actor: String, host: String = "") {
        AuditContext.call(actor, host) {
            val cur = get(kind, name) ?: throw NotFoundException(kind, name)
            store.delete(ResourceId(SECURITY_MODULE, Scope.GATEWAY, kind, name), cur.metadata.version)
        }
    }
}

object SecuritySchemas {
    private const val NAME = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"

    fun register(registry: SchemaRegistry) {
        registry.register(SECURITY_MODULE, Kinds.USER, user())
        registry.register(SECURITY_MODULE, Kinds.ROLE, role())
        registry.register(SECURITY_MODULE, Kinds.PASSWORD_POLICY, policy())
        registry.register(SECURITY_MODULE, Kinds.API_KEY, apiKey())
        registry.register(SECURITY_MODULE, Kinds.SECURITY_ZONE, zone())
        registry.register(SECURITY_MODULE, Kinds.GATEWAY_SECURITY, gateway())
        registry.register(SECURITY_MODULE, Kinds.AUDIT_PROFILE, auditProfile())
        registry.register(SECURITY_MODULE, Kinds.IDENTITY_PROVIDER, IdentityProviderSchema.schema())
        registry.register(SECURITY_MODULE, Kinds.OAUTH2_CLIENT, OAuth2Client.schema())
        registry.register(SECURITY_MODULE, Kinds.USER_SOURCE, UserSourceSchema.schema())
        SecurityLevels.register(registry)
    }

    private fun permArray() = mapOf(
        "type" to "array",
        "items" to mapOf("type" to "string", "enum" to Permissions.ALL.toList()),
        "uniqueItems" to true,
        "maxItems" to 64,
    )

    private fun user(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "enabled" to mapOf("type" to "boolean"),
            "displayName" to mapOf("type" to "string", "maxLength" to 200),
            "email" to mapOf("type" to "string", "maxLength" to 254),
            "phone" to mapOf("type" to "string", "maxLength" to 32),
            "passwordHash" to mapOf("type" to "string", "minLength" to 20, "maxLength" to 400),
            "passwordChangedAt" to mapOf("type" to "string", "format" to "date-time"),
            "passwordHistory" to mapOf("type" to "array", "items" to mapOf("type" to "string", "minLength" to 20, "maxLength" to 400), "maxItems" to 24),
            "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string", "pattern" to NAME), "uniqueItems" to true, "maxItems" to 64),
        ),
        "required" to listOf("enabled", "passwordHash", "passwordChangedAt", "passwordHistory", "roles"),
    )

    private fun role(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
            "permissions" to permArray(),
        ),
        "required" to listOf("permissions"),
    )

    private fun policy(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "maxAgeDays" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 36500, "default" to 0),
            "minLength" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 256, "default" to 8),
            "complexity" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 4, "default" to 3),
            "history" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 24, "default" to 0),
            "prohibitPassword" to mapOf("type" to "boolean", "default" to true),
            "prohibitUsername" to mapOf("type" to "boolean", "default" to true),
            "maxConsecutiveRepeated" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 128, "default" to 3),
        ),
    )

    private fun apiKey(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
            "enabled" to mapOf("type" to "boolean"),
            "keyId" to mapOf("type" to "string", "pattern" to "^[0-9a-f]{16}$"),
            "keyHash" to mapOf("type" to "string", "pattern" to "^[0-9a-f]{64}$"),
            "scopes" to permArray(),
            "secureConnectionRequired" to mapOf("type" to "boolean", "default" to true),
            "createdAt" to mapOf("type" to "string", "format" to "date-time"),
        ),
        "required" to listOf("enabled", "keyId", "keyHash", "scopes", "secureConnectionRequired", "createdAt"),
    )

    private fun zone(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
            "priority" to mapOf("type" to "integer"),
            "enabled" to mapOf("type" to "boolean", "default" to true),
            "ipAddresses" to stringList(),
            "hostNames" to stringList(),
            "gatewayNames" to stringList(),
            "requireSecureConnection" to mapOf("type" to "boolean", "default" to false),
            "directConnectionRequired" to mapOf("type" to "boolean", "default" to false),
            "allowClientScope" to mapOf("type" to "boolean", "default" to true),
            "allowDesignerScope" to mapOf("type" to "boolean", "default" to true),
            "allowGatewayScope" to mapOf("type" to "boolean", "default" to true),
            "allowedPermissions" to permArray(),
            "deniedPermissions" to permArray(),
        ),
        "required" to listOf("priority", "ipAddresses", "hostNames", "gatewayNames"),
    )

    private fun gateway(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "allowUserAdministration" to mapOf("type" to "boolean", "default" to false),
            "inactivityTimeoutMinutes" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 100000, "default" to 0),
            "designerAuthStrategy" to mapOf("type" to "string", "enum" to listOf("classic", "identityProvider"), "default" to "classic"),
            "systemUserSource" to mapOf("type" to "string", "maxLength" to 63, "default" to "internal"),
            "designerRoles" to nameList(),
            "writeRoles" to nameList(),
            "writeMatch" to matchMode(),
            "writePublic" to mapOf("type" to "boolean", "default" to false),
            "readRoles" to nameList(),
            "readMatch" to matchMode(),
            "readPublic" to mapOf("type" to "boolean", "default" to false),
            "accessRoles" to nameList(),
            "accessMatch" to matchMode(),
            "accessPublic" to mapOf("type" to "boolean", "default" to false),
        ),
    )

    private fun auditProfile(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
            "retentionDays" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 36500, "default" to 90),
            "enabled" to mapOf("type" to "boolean", "default" to true),
        ),
    )

    private fun stringList() = mapOf("type" to "array", "items" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 253), "maxItems" to 64, "default" to emptyList<String>())
    private fun nameList() = mapOf("type" to "array", "items" to mapOf("type" to "string", "pattern" to NAME), "uniqueItems" to true, "maxItems" to 64, "default" to emptyList<String>())
    private fun matchMode() = mapOf("type" to "string", "enum" to listOf("any", "all"), "default" to "any")
}

data class UserAccount(
    val username: String,
    val displayName: String,
    val email: String,
    val phone: String,
    val enabled: Boolean,
    val roles: Set<String>,
    val passwordChangedAt: Instant,
    val passwordExpired: Boolean,
)

class CreateUser(
    val username: String,
    val password: CharArray,
    val roles: Set<String> = emptySet(),
    val displayName: String = "",
    val email: String = "",
    val phone: String = "",
    val enabled: Boolean = true,
) {
    override fun toString() = "CreateUser(username=$username)"
}

data class UpdateUser(
    val username: String,
    val displayName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val enabled: Boolean? = null,
    val roles: Set<String>? = null,
)

/**
 * Result of [UserDirectory.check]. [passwordValid] is null when no password was checked. It never carries the password
 * or its hash.
 */
data class UserCheck(
    val source: String,
    val username: String,
    val exists: Boolean,
    val enabled: Boolean?,
    val roles: List<String>,
    val passwordChecked: Boolean,
    val passwordValid: Boolean?,
)

/** The gateway's own user source (GatewaySecurity.systemUserSource defaults to it); external ones are [UserSource] resources. */
const val INTERNAL_SOURCE = "internal"

data class RoleInfo(val name: String, val description: String, val permissions: Set<String>)

data class PasswordPolicyRecord(val policy: PasswordPolicy, val version: Long)

data class GatewaySecurity(
    val allowUserAdministration: Boolean,
    val inactivityTimeoutMinutes: Int,
    val designerAuthStrategy: DesignerAuthStrategy,
    val systemUserSource: String,
    val designerRoles: Set<String>,
    val writeRoles: Set<String>,
    val writeMatch: MatchMode,
    val writePublic: Boolean,
    val readRoles: Set<String>,
    val readMatch: MatchMode,
    val readPublic: Boolean,
    val accessRoles: Set<String>,
    val accessMatch: MatchMode,
    val accessPublic: Boolean,
)

data class AuditProfile(val description: String, val retentionDays: Int, val enabled: Boolean)

interface UserDirectory {
    fun create(cmd: CreateUser, actor: String, host: String = "", caller: CallerScope = CallerScope.GATEWAY): UserAccount
    fun update(cmd: UpdateUser, actor: String, host: String = "", caller: CallerScope = CallerScope.GATEWAY): UserAccount
    fun delete(username: String, actor: String, host: String = "", caller: CallerScope = CallerScope.GATEWAY)
    fun get(username: String): UserAccount?
    fun list(): List<UserAccount>
    fun setPassword(username: String, newPassword: CharArray, actor: String, host: String = "", caller: CallerScope = CallerScope.GATEWAY)
    fun changePassword(username: String, current: CharArray, newPassword: CharArray, actor: String, host: String = "", caller: CallerScope = CallerScope.GATEWAY)
    fun authenticate(username: String, password: CharArray, host: String = ""): AuthenticationResult
    fun rolesOf(username: String): Set<String>
    /**
     * Verify a user on this user source (SEC-027) without logging in: whether [username] exists, its roles, and, when
     * [password] is given, whether it matches. A password is always hashed, for a missing user too, so a right and a
     * wrong password, and a present and a missing user, take the same time class. Records one `user source check`
     * audit row as [actor]; the password is never recorded. [password] is wiped before this returns.
     */
    fun check(username: String, password: CharArray?, actor: String, host: String = ""): UserCheck
}

interface RoleDirectory {
    fun create(name: String, permissions: Set<String>, description: String = "", actor: String, host: String = "", caller: CallerScope = CallerScope.GATEWAY): RoleInfo
    fun update(name: String, permissions: Set<String>? = null, description: String? = null, actor: String, host: String = "", caller: CallerScope = CallerScope.GATEWAY): RoleInfo
    fun delete(name: String, actor: String, host: String = "", caller: CallerScope = CallerScope.GATEWAY)
    fun get(name: String): RoleInfo?
    fun list(): List<RoleInfo>
}

/**
 * Permissions added to [Permissions.ALL] after gateways were already seeded. A role that holds every older
 * permission (the seeded administrator, or a copy of it) gains them on the next start or restore, so an upgrade
 * does not take an action away from an administrator. Such a role already holds `roles.admin`, so this grants
 * nothing it could not grant itself. Other roles are left alone.
 */
object AddedPermissions {
    val ADDED: Set<String> = setOf(Permissions.BACKUPS_ADMIN)

    /** Role name to its new permission set, for each role that needs the added permissions. */
    fun upgrades(roles: List<RoleInfo>): List<Pair<String, Set<String>>> {
        val older = Permissions.ALL - ADDED
        return roles.filter { it.permissions.containsAll(older) && !it.permissions.containsAll(ADDED) }
            .map { it.name to (it.permissions + ADDED) }
    }

    /** Apply [upgrades] through [roles] (after a backup restore brings back an older role set). */
    fun apply(roles: RoleDirectory, actor: String = "system") {
        for ((name, permissions) in upgrades(roles.list())) roles.update(name, permissions, actor = actor)
    }
}

interface PasswordPolicyStore {
    fun get(): PasswordPolicy
    fun update(policy: PasswordPolicy, actor: String): PasswordPolicy
}

interface GatewaySecurityStore {
    fun get(): GatewaySecurity
    fun update(settings: GatewaySecurity, actor: String): GatewaySecurity
}

interface AuditProfileStore {
    fun get(): AuditProfile
    fun update(profile: AuditProfile, actor: String): AuditProfile
    /** Rows older than the configured retention. 0 when retention is 0 (keep forever). */
    fun expiredCount(now: Instant = Instant.now()): Long
    /**
     * Remove rows older than the configured retention (SEC-001) and append one [AuditActions.AUDIT_PRUNED] row when
     * any were removed. Retention 0 keeps every row. The kept chain still verifies ([AuditBase]). Returns rows removed.
     */
    fun enforceRetention(now: Instant = Instant.now()): Long
}

internal class SecurityDirectory(
    private val docs: ConfigDocs,
    private val hasher: PasswordHasher,
    private val clock: Clock,
    private val audit: AuditLog,
    private val apiKeyRandom: SecureRandom = SecureRandom(),
) {
    private val dummyHash: String = CharArray(24) { 'd' }.let { pw ->
        try { hasher.hash(pw) } finally { pw.fill('\u0000') }
    }

    // --- password policy ---

    fun passwordPolicy(): PasswordPolicy = readPolicy()

    fun savePasswordPolicy(policy: PasswordPolicy, actor: String): PasswordPolicy {
        docs.modify(Kinds.PASSWORD_POLICY, POLICY_NAME, actor) { _ -> policySpec(policy) }
        return policy
    }

    private fun readPolicy(): PasswordPolicy {
        val spec = docs.spec(Kinds.PASSWORD_POLICY, POLICY_NAME) ?: return PasswordPolicy()
        return PasswordPolicy(
            maxAgeDays = Specs.int(spec, "maxAgeDays", 0),
            minLength = Specs.int(spec, "minLength", 8),
            complexity = Specs.int(spec, "complexity", 3),
            history = Specs.int(spec, "history", 0),
            prohibitPassword = Specs.bool(spec, "prohibitPassword", true),
            prohibitUsername = Specs.bool(spec, "prohibitUsername", true),
            maxConsecutiveRepeated = Specs.int(spec, "maxConsecutiveRepeated", 3),
        )
    }

    // --- gateway security ---

    fun gatewaySecurity(): GatewaySecurity = readGateway()

    fun saveGatewaySecurity(settings: GatewaySecurity, actor: String): GatewaySecurity {
        docs.modify(Kinds.GATEWAY_SECURITY, GATEWAY_NAME, actor) { _ -> gatewaySpec(settings) }
        return settings
    }

    private fun readGateway(): GatewaySecurity {
        val spec = docs.spec(Kinds.GATEWAY_SECURITY, GATEWAY_NAME) ?: return defaultGateway()
        return GatewaySecurity(
            allowUserAdministration = Specs.bool(spec, "allowUserAdministration"),
            inactivityTimeoutMinutes = Specs.int(spec, "inactivityTimeoutMinutes", 0),
            designerAuthStrategy = if (Specs.str(spec, "designerAuthStrategy") == "identityProvider") DesignerAuthStrategy.IDENTITY_PROVIDER else DesignerAuthStrategy.CLASSIC,
            systemUserSource = Specs.str(spec, "systemUserSource") ?: "internal",
            designerRoles = Specs.strings(spec, "designerRoles").toSet(),
            writeRoles = Specs.strings(spec, "writeRoles").toSet(),
            writeMatch = match(spec, "writeMatch"),
            writePublic = Specs.bool(spec, "writePublic"),
            readRoles = Specs.strings(spec, "readRoles").toSet(),
            readMatch = match(spec, "readMatch"),
            readPublic = Specs.bool(spec, "readPublic"),
            accessRoles = Specs.strings(spec, "accessRoles").toSet(),
            accessMatch = match(spec, "accessMatch"),
            accessPublic = Specs.bool(spec, "accessPublic"),
        )
    }

    // --- audit profile ---

    fun auditProfile(): AuditProfile = readProfile()

    fun saveAuditProfile(profile: AuditProfile, actor: String): AuditProfile {
        docs.modify(Kinds.AUDIT_PROFILE, PROFILE_NAME, actor) { _ -> profileSpec(profile) }
        return profile
    }

    fun expiredCount(now: Instant): Long {
        val cutoff = retentionCutoff(now) ?: return 0
        return audit.countOlderThan(cutoff)
    }

    fun enforceRetention(now: Instant): Long {
        val cutoff = retentionCutoff(now) ?: return 0
        val result = audit.prune(cutoff, now.toEpochMilli())
        val base = result.base ?: return 0
        // The prune is itself recorded, even with the profile disabled: it removes audit history.
        audit.append(
            AuditDraft(
                timestampMillis = now.toEpochMilli(),
                actor = "system",
                action = AuditActions.AUDIT_PRUNED,
                status = AuditStatus.SUCCESS,
                target = "audit",
                detail = "removed=${result.removed},throughSeq=${base.seq},retentionDays=${readProfile().retentionDays}",
            )
        )
        return result.removed
    }

    private fun retentionCutoff(now: Instant): Long? {
        val days = readProfile().retentionDays
        if (days <= 0) return null
        return now.minusSeconds(days.toLong() * 86_400L).toEpochMilli()
    }

    private fun readProfile(): AuditProfile {
        val spec = docs.spec(Kinds.AUDIT_PROFILE, PROFILE_NAME) ?: return AuditProfile("", 90, true)
        return AuditProfile(
            Specs.str(spec, "description") ?: "",
            Specs.int(spec, "retentionDays", 90),
            Specs.bool(spec, "enabled", true),
        )
    }

    fun auditEnabled(): Boolean = readProfile().enabled

    /** Seed built-in roles, password policy, gateway security and the internal audit profile when absent. */
    fun seed() {
        if (roles().isEmpty()) {
            createRole(DefaultRoles.ADMINISTRATOR, Permissions.ALL, "Full gateway administration", "system", "", CallerScope.GATEWAY)
            createRole(DefaultRoles.OPERATOR, DefaultRoles.operator, "Operate tags and read gateway configuration", "system", "", CallerScope.GATEWAY)
            createRole(DefaultRoles.VIEWER, DefaultRoles.viewer, "Read-only access", "system", "", CallerScope.GATEWAY)
        }
        for ((name, permissions) in AddedPermissions.upgrades(roles())) {
            updateRole(name, permissions, null, "system", "", CallerScope.GATEWAY)
        }
        if (docs.get(Kinds.PASSWORD_POLICY, POLICY_NAME) == null) savePasswordPolicy(PasswordPolicy(), "system")
        if (docs.get(Kinds.GATEWAY_SECURITY, GATEWAY_NAME) == null) saveGatewaySecurity(defaultGateway(), "system")
        if (docs.get(Kinds.AUDIT_PROFILE, PROFILE_NAME) == null) {
            saveAuditProfile(AuditProfile("Internal audit profile", 90, true), "system")
        }
    }

    // --- roles ---

    fun createRole(name: String, permissions: Set<String>, description: String, actor: String, host: String, caller: CallerScope): RoleInfo {
        guardAdmin(caller)
        checkName(name, "role")
        return try {
            docs.modify(Kinds.ROLE, name, actor, host) { current ->
                if (current != null) throw AlreadyExistsException(Kinds.ROLE, name)
                roleSpec(description, permissions)
            }
            RoleInfo(name, description, permissions)
        } catch (e: ValidationException) {
            throw e
        }
    }

    fun updateRole(name: String, permissions: Set<String>?, description: String?, actor: String, host: String, caller: CallerScope): RoleInfo {
        guardAdmin(caller)
        val info = docs.modify(Kinds.ROLE, name, actor, host) { current ->
            if (current == null) throw NotFoundException(Kinds.ROLE, name)
            roleSpec(description ?: (Specs.str(current, "description") ?: ""), permissions ?: Specs.strings(current, "permissions").toSet())
        }
        return roleOf(name, info.spec)
    }

    fun deleteRole(name: String, actor: String, host: String, caller: CallerScope) {
        guardAdmin(caller)
        docs.remove(Kinds.ROLE, name, actor, host)
    }

    fun role(name: String): RoleInfo? {
        val spec = docs.spec(Kinds.ROLE, name) ?: return null
        return roleOf(name, spec)
    }

    fun roles(): List<RoleInfo> = docs.list(Kinds.ROLE).map { roleOf(it.metadata.name, docs.spec(Kinds.ROLE, it.metadata.name)!!) }

    fun permissionsOf(roleNames: Collection<String>): Set<String> {
        val held = roleNames.flatMap { role(it)?.permissions ?: emptySet() }
        return Permissions.expand(held)
    }

    // --- users ---

    fun createUser(cmd: CreateUser, actor: String, host: String, caller: CallerScope): UserAccount {
        try {
            guardAdmin(caller)
            checkName(cmd.username, "user")
            checkProfile(cmd.displayName, cmd.email, cmd.phone)
            ensureRoles(cmd.roles)
            PasswordRules.reject(cmd.password, cmd.username, readPolicy(), hasher)
            val hash = hasher.hash(cmd.password)
            val now = clock.instant()
            docs.modify(Kinds.USER, cmd.username, actor, host) { current ->
                if (current != null) throw AlreadyExistsException(Kinds.USER, cmd.username)
                userSpec(cmd.enabled, cmd.displayName, cmd.email, cmd.phone, hash, now, emptyList(), cmd.roles)
            }
            return account(cmd.username)!!
        } finally {
            cmd.password.fill('\u0000')
        }
    }

    fun updateUser(cmd: UpdateUser, actor: String, host: String, caller: CallerScope): UserAccount {
        guardAdmin(caller)
        cmd.roles?.let { ensureRoles(it) }
        docs.modify(Kinds.USER, cmd.username, actor, host) { current ->
            if (current == null) throw NotFoundException(Kinds.USER, cmd.username)
            val display = cmd.displayName ?: (Specs.str(current, "displayName") ?: "")
            val email = cmd.email ?: (Specs.str(current, "email") ?: "")
            val phone = cmd.phone ?: (Specs.str(current, "phone") ?: "")
            checkProfile(display, email, phone)
            userSpec(
                cmd.enabled ?: Specs.bool(current, "enabled", true),
                display, email, phone,
                Specs.str(current, "passwordHash")!!,
                Specs.instant(current, "passwordChangedAt"),
                Specs.strings(current, "passwordHistory"),
                cmd.roles ?: Specs.strings(current, "roles").toSet(),
            )
        }
        return account(cmd.username)!!
    }

    fun deleteUser(username: String, actor: String, host: String, caller: CallerScope) {
        guardAdmin(caller)
        docs.remove(Kinds.USER, username, actor, host)
    }

    fun user(username: String): UserAccount? = account(username)

    fun users(): List<UserAccount> = docs.list(Kinds.USER).map { account(it.metadata.name)!! }

    fun setPassword(username: String, newPassword: CharArray, actor: String, host: String, caller: CallerScope) {
        try {
            guardAdmin(caller)
            writePassword(username, newPassword, actor, host)
        } finally {
            newPassword.fill('\u0000')
        }
    }

    fun changePassword(username: String, current: CharArray, newPassword: CharArray, actor: String, host: String, caller: CallerScope) {
        try {
            val spec = docs.spec(Kinds.USER, username) ?: throw NotFoundException(Kinds.USER, username)
            val hash = Specs.str(spec, "passwordHash")!!
            if (!hasher.verify(hash, current)) throw AccessDeniedException("current password is incorrect")
            writePassword(username, newPassword, actor, host)
        } finally {
            current.fill('\u0000')
            newPassword.fill('\u0000')
        }
    }

    private fun writePassword(username: String, newPassword: CharArray, actor: String, host: String) {
        val policy = readPolicy()
        docs.modify(Kinds.USER, username, actor, host) { current ->
            if (current == null) throw NotFoundException(Kinds.USER, username)
            val hash = Specs.str(current, "passwordHash")!!
            val history = Specs.strings(current, "passwordHistory")
            PasswordRules.reject(newPassword, username, policy, hasher, hash, history)
            val storedHistory = if (policy.history > 0) (listOf(hash) + history).take(policy.history) else history
            userSpec(
                Specs.bool(current, "enabled", true),
                Specs.str(current, "displayName") ?: "",
                Specs.str(current, "email") ?: "",
                Specs.str(current, "phone") ?: "",
                hasher.hash(newPassword),
                clock.instant(),
                storedHistory,
                Specs.strings(current, "roles").toSet(),
            )
        }
    }

    fun authenticate(username: String, password: CharArray, host: String): AuthenticationResult {
        try {
            val spec = docs.spec(Kinds.USER, username)
            val hash = spec?.let { Specs.str(it, "passwordHash") } ?: dummyHash
            val ok = hasher.verify(hash, password)
            if (spec == null || !ok) {
                recordLogin(username.ifBlank { "unknown" }, host, AuditActions.LOGIN_ATTEMPT, AuditStatus.FAILURE, "bad credentials")
                return AuthenticationResult.Failure(AuthFailure.BAD_CREDENTIALS)
            }
            val account = account(username)!!
            val subject = Subject.User(username, account.roles, account.enabled)
            if (!account.enabled) {
                recordLogin(username, host, AuditActions.LOGIN_ATTEMPT, AuditStatus.FAILURE, "disabled")
                return AuthenticationResult.Failure(AuthFailure.DISABLED)
            }
            if (account.passwordExpired) {
                recordLogin(username, host, AuditActions.LOGIN, AuditStatus.FAILURE, "password expired")
                return AuthenticationResult.PasswordExpired(subject)
            }
            recordLogin(username, host, AuditActions.LOGIN, AuditStatus.SUCCESS, "")
            return AuthenticationResult.Success(subject)
        } finally {
            password.fill('\u0000')
        }
    }

    fun rolesOf(username: String): Set<String> = account(username)?.roles ?: emptySet()

    fun check(username: String, password: CharArray?, actor: String, host: String): UserCheck {
        try {
            val spec = runCatching { docs.spec(Kinds.USER, username) }.getOrNull()
            // Hash whenever a password is given, against a dummy hash for a missing user (same hasher, same cost).
            val matches = password?.let { hasher.verify(spec?.let { s -> Specs.str(s, "passwordHash") } ?: dummyHash, it) }
            val exists = spec != null
            val account = if (exists) account(username) else null
            val passwordValid = if (password == null) null else exists && matches == true
            if (auditEnabled()) {
                val detail = buildString {
                    append("source=").append(INTERNAL_SOURCE)
                    append(";exists=").append(exists)
                    append(";password=").append(if (password == null) "not checked" else if (passwordValid == true) "valid" else "invalid")
                }
                audit.append(
                    AuditDraft(
                        clock.millis(), actor, AuditActions.USER_SOURCE_CHECK, AuditStatus.SUCCESS,
                        actorHost = host, target = username.take(200), detail = detail,
                    )
                )
            }
            return UserCheck(
                source = INTERNAL_SOURCE,
                username = username,
                exists = exists,
                enabled = account?.enabled,
                roles = account?.roles?.sorted() ?: emptyList(),
                passwordChecked = password != null,
                passwordValid = passwordValid,
            )
        } finally {
            password?.fill('\u0000')
        }
    }

    private fun recordLogin(actor: String, host: String, action: String, status: AuditStatus, detail: String) {
        if (!auditEnabled()) return
        audit.append(AuditDraft(clock.millis(), actor, action, status, actorHost = host, target = actor, detail = detail))
    }

    private fun account(username: String): UserAccount? {
        val spec = docs.spec(Kinds.USER, username) ?: return null
        val changed = Specs.instant(spec, "passwordChangedAt")
        return UserAccount(
            username = username,
            displayName = Specs.str(spec, "displayName") ?: "",
            email = Specs.str(spec, "email") ?: "",
            phone = Specs.str(spec, "phone") ?: "",
            enabled = Specs.bool(spec, "enabled", true),
            roles = Specs.strings(spec, "roles").toSet(),
            passwordChangedAt = changed,
            passwordExpired = readPolicy().expired(changed, clock.instant()),
        )
    }

    // --- api keys (methods used by ApiKeyDirectory impl in this class) ---

    fun createApiKey(name: String, scopes: Set<String>, description: String, secureConnectionRequired: Boolean, actor: String, host: String): IssuedApiKey {
        checkName(name, "api key")
        val issued = issueToken()
        val now = clock.instant()
        docs.modify(Kinds.API_KEY, name, actor, host) { current ->
            if (current != null) throw AlreadyExistsException(Kinds.API_KEY, name)
            apiKeySpec(description, true, issued.id, issued.hash, scopes, secureConnectionRequired, now)
        }
        return IssuedApiKey(name, issued.token, apiKeyInfo(name)!!)
    }

    fun rotateApiKey(name: String, actor: String, host: String): IssuedApiKey {
        val issued = issueToken()
        docs.modify(Kinds.API_KEY, name, actor, host) { current ->
            if (current == null) throw NotFoundException(Kinds.API_KEY, name)
            apiKeySpec(
                Specs.str(current, "description") ?: "",
                Specs.bool(current, "enabled", true),
                issued.id, issued.hash,
                Specs.strings(current, "scopes").toSet(),
                Specs.bool(current, "secureConnectionRequired", true),
                Specs.instant(current, "createdAt"),
            )
        }
        return IssuedApiKey(name, issued.token, apiKeyInfo(name)!!)
    }

    fun updateApiKey(name: String, scopes: Set<String>?, description: String?, enabled: Boolean?, secure: Boolean?, actor: String, host: String): ApiKeyInfo {
        docs.modify(Kinds.API_KEY, name, actor, host) { current ->
            if (current == null) throw NotFoundException(Kinds.API_KEY, name)
            apiKeySpec(
                description ?: (Specs.str(current, "description") ?: ""),
                enabled ?: Specs.bool(current, "enabled", true),
                Specs.str(current, "keyId")!!,
                Specs.str(current, "keyHash")!!,
                scopes ?: Specs.strings(current, "scopes").toSet(),
                secure ?: Specs.bool(current, "secureConnectionRequired", true),
                Specs.instant(current, "createdAt"),
            )
        }
        return apiKeyInfo(name)!!
    }

    fun deleteApiKey(name: String, actor: String, host: String) = docs.remove(Kinds.API_KEY, name, actor, host)

    fun apiKeyInfo(name: String): ApiKeyInfo? {
        val spec = docs.spec(Kinds.API_KEY, name) ?: return null
        return ApiKeyInfo(
            name = name,
            description = Specs.str(spec, "description") ?: "",
            enabled = Specs.bool(spec, "enabled", true),
            keyId = Specs.str(spec, "keyId")!!,
            scopes = Specs.strings(spec, "scopes").toSet(),
            secureConnectionRequired = Specs.bool(spec, "secureConnectionRequired", true),
            createdAt = Specs.instant(spec, "createdAt"),
        )
    }

    fun listApiKeys(): List<ApiKeyInfo> = docs.list(Kinds.API_KEY).map { apiKeyInfo(it.metadata.name)!! }

    fun authenticateApiKey(token: String, host: String, secure: Boolean): AuthenticationResult {
        val parsed = parseToken(token)
        val secret = parsed?.secret ?: DUMMY_SECRET
        val actual = sha256(secret)
        val spec = parsed?.let { findKeyById(it.id) }
        val expected = spec?.let { Hex.decode(Specs.str(it.second, "keyHash")!!) } ?: DUMMY_HASH
        val macOk = ConstantTime.equal(actual, expected)
        if (parsed == null || spec == null || !macOk) {
            recordToken(parsed?.id ?: "unknown", host, AuditStatus.FAILURE, "bad credentials")
            return AuthenticationResult.Failure(AuthFailure.BAD_CREDENTIALS)
        }
        val (resource, _) = spec
        val info = apiKeyInfo(resource.metadata.name)!!
        val subject = Subject.ApiKey(info.name, info.scopes, info.secureConnectionRequired, info.enabled)
        if (!info.enabled) {
            recordToken(info.name, host, AuditStatus.FAILURE, "revoked")
            return AuthenticationResult.Failure(AuthFailure.DISABLED)
        }
        if (info.secureConnectionRequired && !secure) {
            recordToken(info.name, host, AuditStatus.FAILURE, "insecure channel")
            return AuthenticationResult.Failure(AuthFailure.INSECURE_CHANNEL)
        }
        recordToken(info.name, host, AuditStatus.SUCCESS, "")
        return AuthenticationResult.Success(subject)
    }

    private fun recordToken(actor: String, host: String, status: AuditStatus, detail: String) {
        if (!auditEnabled()) return
        audit.append(AuditDraft(clock.millis(), actor, AuditActions.TOKEN_LOGIN, status, actorHost = host, target = actor, detail = detail))
    }

    private fun findKeyById(id: String): Pair<Resource, Map<String, Any?>>? {
        for (r in docs.list(Kinds.API_KEY)) {
            val spec = docs.spec(Kinds.API_KEY, r.metadata.name) ?: continue
            if (Specs.str(spec, "keyId") == id) return r to spec
        }
        return null
    }

    private fun issueToken(): Issued {
        val idBytes = ByteArray(8).also { apiKeyRandom.nextBytes(it) }
        val secret = ByteArray(32).also { apiKeyRandom.nextBytes(it) }
        val id = Hex.encode(idBytes)
        val secretText = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        val token = "oipk_${id}_$secretText"
        val hash = Hex.encode(sha256(secret))
        secret.fill(0)
        return Issued(id, hash, token)
    }

    private data class Issued(val id: String, val hash: String, val token: String)
    private data class ParsedToken(val id: String, val secret: ByteArray)

    private fun parseToken(token: String): ParsedToken? {
        val m = TOKEN.matchEntire(token) ?: return null
        val secret = try {
            Base64.getUrlDecoder().decode(m.groupValues[2])
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (secret.size != 32) return null
        return ParsedToken(m.groupValues[1], secret)
    }

    // --- zones ---

    fun createZone(zone: SecurityZone, actor: String, host: String = ""): SecurityZone {
        checkName(zone.name, "zone")
        if (zone.isDefault) throw AccessDeniedException("the Default security zone cannot be modified")
        val problems = zone.patternProblems()
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        if (zone.ipAddresses.isEmpty() && zone.hostNames.isEmpty() && zone.gatewayNames.isEmpty()) {
            throw IllegalArgumentException("a security zone needs at least one identifier")
        }
        docs.modify(Kinds.SECURITY_ZONE, zone.name, actor, host) { current ->
            if (current != null) throw AlreadyExistsException(Kinds.SECURITY_ZONE, zone.name)
            zoneSpec(zone)
        }
        return zone
    }

    fun updateZone(zone: SecurityZone, actor: String, host: String = ""): SecurityZone {
        if (zone.isDefault) throw AccessDeniedException("the Default security zone cannot be modified")
        val problems = zone.patternProblems()
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        if (zone.ipAddresses.isEmpty() && zone.hostNames.isEmpty() && zone.gatewayNames.isEmpty()) {
            throw IllegalArgumentException("a security zone needs at least one identifier")
        }
        docs.modify(Kinds.SECURITY_ZONE, zone.name, actor, host) { current ->
            if (current == null) throw NotFoundException(Kinds.SECURITY_ZONE, zone.name)
            zoneSpec(zone)
        }
        return zone
    }

    fun deleteZone(name: String, actor: String, host: String = "") {
        if (name.equals(SecurityZone.DEFAULT_NAME, ignoreCase = true)) throw AccessDeniedException("the Default security zone cannot be modified")
        docs.remove(Kinds.SECURITY_ZONE, name, actor, host)
    }

    fun zones(): List<SecurityZone> = docs.list(Kinds.SECURITY_ZONE).map { zoneOf(it.metadata.name, docs.spec(Kinds.SECURITY_ZONE, it.metadata.name)!!) }

    fun zoneProblems(): List<String> = zones().flatMap { z -> z.patternProblems().map { "${z.name}: $it" } }

    fun matchZone(ctx: ConnectionContext): SecurityZone = SecurityZone.select(zones(), ctx)

    private fun guardAdmin(caller: CallerScope) {
        if (caller == CallerScope.GATEWAY) return
        if (!readGateway().allowUserAdministration) {
            throw AccessDeniedException("user administration from ${caller.name.lowercase()} is disabled")
        }
    }

    private fun ensureRoles(roles: Set<String>) {
        roles.forEach { if (role(it) == null) throw NotFoundException(Kinds.ROLE, it) }
    }

    private fun checkName(name: String, what: String) {
        if (!NAME.matches(name)) throw IllegalArgumentException("invalid $what name '$name'")
    }

    private fun checkProfile(displayName: String, email: String, phone: String) {
        if (displayName.any { it == '\n' || it == '\r' }) throw IllegalArgumentException("display name must be a single line")
        if (email.isNotEmpty() && !EMAIL.matches(email)) throw IllegalArgumentException("invalid email")
        if (phone.any { it == '\n' || it == '\r' }) throw IllegalArgumentException("phone must be a single line")
    }

    companion object {
        const val POLICY_NAME = "default"
        const val GATEWAY_NAME = "gateway"
        const val PROFILE_NAME = "internal"
        private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val TOKEN = Regex("^oipk_([0-9a-f]{16})_([A-Za-z0-9_-]+)$")
        private val DUMMY_SECRET = ByteArray(32) { 0xA5.toByte() }
        private val DUMMY_HASH = sha256(DUMMY_SECRET)

        fun defaultGateway() = GatewaySecurity(
            allowUserAdministration = false,
            inactivityTimeoutMinutes = 0,
            designerAuthStrategy = DesignerAuthStrategy.CLASSIC,
            systemUserSource = "internal",
            designerRoles = setOf(DefaultRoles.ADMINISTRATOR),
            writeRoles = setOf(DefaultRoles.ADMINISTRATOR),
            writeMatch = MatchMode.ANY,
            writePublic = false,
            readRoles = setOf(DefaultRoles.ADMINISTRATOR, DefaultRoles.OPERATOR),
            readMatch = MatchMode.ANY,
            readPublic = false,
            accessRoles = setOf(DefaultRoles.ADMINISTRATOR, DefaultRoles.OPERATOR, DefaultRoles.VIEWER),
            accessMatch = MatchMode.ANY,
            accessPublic = false,
        )

        private fun match(spec: Map<String, Any?>, key: String) =
            if (Specs.str(spec, key) == "all") MatchMode.ALL else MatchMode.ANY

        private fun roleOf(name: String, spec: Map<String, Any?>) =
            RoleInfo(name, Specs.str(spec, "description") ?: "", Specs.strings(spec, "permissions").toSet())

        private fun zoneOf(name: String, spec: Map<String, Any?>) = SecurityZone(
            name = name,
            description = Specs.str(spec, "description") ?: "",
            priority = Specs.int(spec, "priority", 0),
            enabled = Specs.bool(spec, "enabled", true),
            ipAddresses = Specs.strings(spec, "ipAddresses"),
            hostNames = Specs.strings(spec, "hostNames"),
            gatewayNames = Specs.strings(spec, "gatewayNames"),
            requireSecureConnection = Specs.bool(spec, "requireSecureConnection"),
            directConnectionRequired = Specs.bool(spec, "directConnectionRequired"),
            allowClientScope = Specs.bool(spec, "allowClientScope", true),
            allowDesignerScope = Specs.bool(spec, "allowDesignerScope", true),
            allowGatewayScope = Specs.bool(spec, "allowGatewayScope", true),
            allowedPermissions = Specs.strings(spec, "allowedPermissions"),
            deniedPermissions = Specs.strings(spec, "deniedPermissions"),
        )
    }
}

private fun policySpec(p: PasswordPolicy): Map<String, Any?> = linkedMapOf(
    "maxAgeDays" to p.maxAgeDays,
    "minLength" to p.minLength,
    "complexity" to p.complexity,
    "history" to p.history,
    "prohibitPassword" to p.prohibitPassword,
    "prohibitUsername" to p.prohibitUsername,
    "maxConsecutiveRepeated" to p.maxConsecutiveRepeated,
)

private fun gatewaySpec(g: GatewaySecurity): Map<String, Any?> = linkedMapOf(
    "allowUserAdministration" to g.allowUserAdministration,
    "inactivityTimeoutMinutes" to g.inactivityTimeoutMinutes,
    "designerAuthStrategy" to if (g.designerAuthStrategy == DesignerAuthStrategy.IDENTITY_PROVIDER) "identityProvider" else "classic",
    "systemUserSource" to g.systemUserSource,
    "designerRoles" to g.designerRoles.sorted(),
    "writeRoles" to g.writeRoles.sorted(),
    "writeMatch" to g.writeMatch.name.lowercase(),
    "writePublic" to g.writePublic,
    "readRoles" to g.readRoles.sorted(),
    "readMatch" to g.readMatch.name.lowercase(),
    "readPublic" to g.readPublic,
    "accessRoles" to g.accessRoles.sorted(),
    "accessMatch" to g.accessMatch.name.lowercase(),
    "accessPublic" to g.accessPublic,
)

private fun profileSpec(p: AuditProfile): Map<String, Any?> = linkedMapOf(
    "description" to p.description,
    "retentionDays" to p.retentionDays,
    "enabled" to p.enabled,
)

private fun roleSpec(description: String, permissions: Set<String>): Map<String, Any?> = linkedMapOf(
    "description" to description,
    "permissions" to permissions.sorted(),
)

private fun userSpec(
    enabled: Boolean, displayName: String, email: String, phone: String, hash: String,
    changedAt: Instant, history: List<String>, roles: Set<String>,
): Map<String, Any?> = linkedMapOf(
    "enabled" to enabled,
    "displayName" to displayName,
    "email" to email,
    "phone" to phone,
    "passwordHash" to hash,
    "passwordChangedAt" to changedAt.toString(),
    "passwordHistory" to history,
    "roles" to roles.sorted(),
)

private fun apiKeySpec(
    description: String, enabled: Boolean, keyId: String, keyHash: String, scopes: Set<String>,
    secure: Boolean, createdAt: Instant,
): Map<String, Any?> = linkedMapOf(
    "description" to description,
    "enabled" to enabled,
    "keyId" to keyId,
    "keyHash" to keyHash,
    "scopes" to scopes.sorted(),
    "secureConnectionRequired" to secure,
    "createdAt" to createdAt.toString(),
)

private fun zoneSpec(z: SecurityZone): Map<String, Any?> = linkedMapOf(
    "description" to z.description,
    "priority" to z.priority,
    "enabled" to z.enabled,
    "ipAddresses" to z.ipAddresses,
    "hostNames" to z.hostNames,
    "gatewayNames" to z.gatewayNames,
    "requireSecureConnection" to z.requireSecureConnection,
    "directConnectionRequired" to z.directConnectionRequired,
    "allowClientScope" to z.allowClientScope,
    "allowDesignerScope" to z.allowDesignerScope,
    "allowGatewayScope" to z.allowGatewayScope,
    "allowedPermissions" to z.allowedPermissions.sorted(),
    "deniedPermissions" to z.deniedPermissions.sorted(),
)

data class ApiKeyInfo(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val keyId: String,
    val scopes: Set<String>,
    val secureConnectionRequired: Boolean,
    val createdAt: Instant,
)

class IssuedApiKey(val name: String, val token: String, val info: ApiKeyInfo) {
    override fun toString() = "IssuedApiKey(name=$name, token=***)"
}

interface ApiKeyDirectory {
    fun create(name: String, scopes: Set<String>, description: String = "", secureConnectionRequired: Boolean = true, actor: String, host: String = ""): IssuedApiKey
    fun rotate(name: String, actor: String, host: String = ""): IssuedApiKey
    fun update(name: String, scopes: Set<String>? = null, description: String? = null, enabled: Boolean? = null, secureConnectionRequired: Boolean? = null, actor: String, host: String = ""): ApiKeyInfo
    /** Disable the key. The same token works again after [enable]. */
    fun revoke(name: String, actor: String, host: String = ""): ApiKeyInfo
    fun enable(name: String, actor: String, host: String = ""): ApiKeyInfo
    fun delete(name: String, actor: String, host: String = "")
    fun get(name: String): ApiKeyInfo?
    fun list(): List<ApiKeyInfo>
    fun authenticate(token: String, host: String = "", secure: Boolean = true): AuthenticationResult
}

interface ZoneDirectory {
    fun create(zone: SecurityZone, actor: String, host: String = ""): SecurityZone
    fun update(zone: SecurityZone, actor: String, host: String = ""): SecurityZone
    fun delete(name: String, actor: String, host: String = "")
    fun list(): List<SecurityZone>
    fun problems(): List<String>
    fun match(ctx: ConnectionContext): SecurityZone
}

interface Authorizer {
    fun authorize(subject: Subject?, permission: String, ctx: ConnectionContext): AuthorizationDecision
    fun effectivePermissions(subject: Subject?, ctx: ConnectionContext): Set<String>
}

internal class UserService(private val directory: SecurityDirectory) : UserDirectory {
    override fun create(cmd: CreateUser, actor: String, host: String, caller: CallerScope) = directory.createUser(cmd, actor, host, caller)
    override fun update(cmd: UpdateUser, actor: String, host: String, caller: CallerScope) = directory.updateUser(cmd, actor, host, caller)
    override fun delete(username: String, actor: String, host: String, caller: CallerScope) = directory.deleteUser(username, actor, host, caller)
    override fun get(username: String) = directory.user(username)
    override fun list() = directory.users()
    override fun setPassword(username: String, newPassword: CharArray, actor: String, host: String, caller: CallerScope) =
        directory.setPassword(username, newPassword, actor, host, caller)
    override fun changePassword(username: String, current: CharArray, newPassword: CharArray, actor: String, host: String, caller: CallerScope) =
        directory.changePassword(username, current, newPassword, actor, host, caller)
    override fun authenticate(username: String, password: CharArray, host: String) = directory.authenticate(username, password, host)
    override fun rolesOf(username: String) = directory.rolesOf(username)
    override fun check(username: String, password: CharArray?, actor: String, host: String) = directory.check(username, password, actor, host)
}

internal class RoleService(private val directory: SecurityDirectory) : RoleDirectory {
    override fun create(name: String, permissions: Set<String>, description: String, actor: String, host: String, caller: CallerScope) =
        directory.createRole(name, permissions, description, actor, host, caller)
    override fun update(name: String, permissions: Set<String>?, description: String?, actor: String, host: String, caller: CallerScope) =
        directory.updateRole(name, permissions, description, actor, host, caller)
    override fun delete(name: String, actor: String, host: String, caller: CallerScope) = directory.deleteRole(name, actor, host, caller)
    override fun get(name: String) = directory.role(name)
    override fun list() = directory.roles()
}

internal class PasswordPolicyService(private val directory: SecurityDirectory) : PasswordPolicyStore {
    override fun get() = directory.passwordPolicy()
    override fun update(policy: PasswordPolicy, actor: String) = directory.savePasswordPolicy(policy, actor)
}

internal class GatewaySecurityService(private val directory: SecurityDirectory) : GatewaySecurityStore {
    override fun get() = directory.gatewaySecurity()
    override fun update(settings: GatewaySecurity, actor: String) = directory.saveGatewaySecurity(settings, actor)
}

internal class AuditProfileService(private val directory: SecurityDirectory) : AuditProfileStore {
    override fun get() = directory.auditProfile()
    override fun update(profile: AuditProfile, actor: String) = directory.saveAuditProfile(profile, actor)
    override fun expiredCount(now: Instant) = directory.expiredCount(now)
    override fun enforceRetention(now: Instant) = directory.enforceRetention(now)
}

internal class ApiKeyService(private val directory: SecurityDirectory) : ApiKeyDirectory {
    override fun create(name: String, scopes: Set<String>, description: String, secureConnectionRequired: Boolean, actor: String, host: String) =
        directory.createApiKey(name, scopes, description, secureConnectionRequired, actor, host)
    override fun rotate(name: String, actor: String, host: String) = directory.rotateApiKey(name, actor, host)
    override fun update(name: String, scopes: Set<String>?, description: String?, enabled: Boolean?, secureConnectionRequired: Boolean?, actor: String, host: String) =
        directory.updateApiKey(name, scopes, description, enabled, secureConnectionRequired, actor, host)
    override fun revoke(name: String, actor: String, host: String) = update(name, enabled = false, actor = actor, host = host)
    override fun enable(name: String, actor: String, host: String) = update(name, enabled = true, actor = actor, host = host)
    override fun delete(name: String, actor: String, host: String) = directory.deleteApiKey(name, actor, host)
    override fun get(name: String) = directory.apiKeyInfo(name)
    override fun list() = directory.listApiKeys()
    override fun authenticate(token: String, host: String, secure: Boolean) = directory.authenticateApiKey(token, host, secure)
}

internal class ZoneService(private val directory: SecurityDirectory) : ZoneDirectory {
    override fun create(zone: SecurityZone, actor: String, host: String) = directory.createZone(zone, actor, host)
    override fun update(zone: SecurityZone, actor: String, host: String) = directory.updateZone(zone, actor, host)
    override fun delete(name: String, actor: String, host: String) = directory.deleteZone(name, actor, host)
    override fun list() = directory.zones() + SecurityZone.DEFAULT
    override fun problems() = directory.zoneProblems()
    override fun match(ctx: ConnectionContext) = directory.matchZone(ctx)
}

internal class PermissionAuthorizer(private val directory: SecurityDirectory, private val zones: ZoneDirectory) : Authorizer {
    override fun authorize(subject: Subject?, permission: String, ctx: ConnectionContext): AuthorizationDecision {
        val zone = zones.match(ctx)
        if (!Permissions.known(permission)) return AuthorizationDecision(false, permission, zone.name, DecisionReason.UNKNOWN_PERMISSION)
        if (subject == null) return AuthorizationDecision(false, permission, zone.name, DecisionReason.UNAUTHENTICATED)
        if (!subject.enabled) return AuthorizationDecision(false, permission, zone.name, DecisionReason.DISABLED)
        if (!zoneAllows(zone, permission)) return AuthorizationDecision(false, permission, zone.name, DecisionReason.ZONE_DENY)
        val granted = granted(subject)
        if (permission !in granted) return AuthorizationDecision(false, permission, zone.name, DecisionReason.PERMISSION_DENIED)
        if (subject is Subject.ApiKey && subject.secureConnectionRequired && !ctx.secure) {
            return AuthorizationDecision(false, permission, zone.name, DecisionReason.INSECURE_CHANNEL)
        }
        return AuthorizationDecision(true, permission, zone.name, DecisionReason.ALLOW)
    }

    override fun effectivePermissions(subject: Subject?, ctx: ConnectionContext): Set<String> {
        if (subject == null || !subject.enabled) return emptySet()
        if (subject is Subject.ApiKey && subject.secureConnectionRequired && !ctx.secure) return emptySet()
        val zone = zones.match(ctx)
        return granted(subject).filter { zoneAllows(zone, it) }.toSet()
    }

    private fun granted(subject: Subject): Set<String> = when (subject) {
        is Subject.User -> {
            val fromRoles = directory.permissionsOf(subject.roles)
            val fromSettings = settingsGrants(subject.roles, directory.gatewaySecurity())
            fromRoles + fromSettings
        }
        is Subject.ApiKey -> Permissions.expand(subject.scopes)
    }

    private fun zoneAllows(zone: SecurityZone, permission: String): Boolean {
        if (permission in zone.deniedPermissions) return false
        if (zone.allowedPermissions.isEmpty()) return true
        return permission in Permissions.expand(zone.allowedPermissions)
    }

    internal fun settingsGrants(roles: Set<String>, settings: GatewaySecurity): Set<String> {
        val out = linkedSetOf<String>()
        if (area(settings.writePublic, settings.writeMatch, settings.writeRoles, roles)) out += Permissions.impliedBy(Permissions.GATEWAY_WRITE)
        if (area(settings.readPublic, settings.readMatch, settings.readRoles, roles)) out += Permissions.impliedBy(Permissions.GATEWAY_READ)
        if (area(settings.accessPublic, settings.accessMatch, settings.accessRoles, roles)) out += Permissions.GATEWAY_ACCESS
        if (settings.designerRoles.isNotEmpty() && roles.any { it in settings.designerRoles }) out += Permissions.DESIGNER_LOGIN
        return out
    }

    private fun area(public: Boolean, match: MatchMode, required: Set<String>, held: Set<String>): Boolean {
        if (public) return true
        if (required.isEmpty()) return false
        return when (match) {
            MatchMode.ANY -> held.any { it in required }
            MatchMode.ALL -> required.all { it in held }
        }
    }
}
