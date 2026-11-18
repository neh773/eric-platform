package org.oip.security

/**
 * External user sources (SEC-007, SEC-010, SEC-021, SEC-025): `org.oip.security/UserSource` resources.
 *
 * A user source checks a username and password somewhere other than the gateway's own user list and says which roles
 * the user holds. Four types:
 *
 * - `ldap`: an LDAP or Active Directory server checks the password (a bind as the user); roles come from the user's
 *   group membership through [UserSource.roleMappings].
 * - `database`: tables in an external SQL database, read through a named database connection, hold the users, their
 *   password hashes and their roles. Passwords are verified against the stored hash with [UserSource.hashScheme];
 *   there is no plain-text scheme.
 * - `ldap-internal`: the directory checks the password; roles are kept in the resource itself ([UserSource.users]).
 * - `ldap-database`: the directory checks the password; roles come from the roles table of an external database.
 *
 * The bind password of the directory's service account lives in the secret store; the resource names it as a
 * `secret://` reference ([UserSource.bindPasswordRef]) and has no other credential field.
 *
 * Doc source: FEATURES SEC-007.
 * Doc source: FEATURES SEC-010.
 * Doc source: FEATURES SEC-021.
 * Doc source: FEATURES SEC-025.
 */
object UserSourceSchema {
    private const val NAME = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"
    /** A table or column name, optionally schema-qualified. Checked here and quoted by the driver's quote string. */
    const val IDENTIFIER = "^([A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?)?$"

    fun schema(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "type" to mapOf("type" to "string", "enum" to UserSource.TYPES),
            "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
            "enabled" to mapOf("type" to "boolean", "default" to true),
            // LDAP / Active Directory
            "ldapUrls" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string", "pattern" to "^ldaps?://[^\\s/]+(:[0-9]{1,5})?/?$", "maxLength" to 300),
                "maxItems" to 4,
                "default" to emptyList<String>(),
            ),
            "startTls" to mapOf("type" to "boolean", "default" to false),
            "allowPlaintext" to mapOf("type" to "boolean", "default" to false),
            "bindDn" to text(1000),
            "bindPasswordRef" to mapOf("type" to "string", "maxLength" to 300, "pattern" to "^(secret://[A-Za-z0-9._-]+)?$", "default" to ""),
            "userBaseDn" to text(1000),
            "userFilter" to text(1000, "(uid={username})"),
            "userBindPattern" to text(1000),
            "usernameAttribute" to text(100, "uid"),
            "displayNameAttribute" to text(100, "cn"),
            "emailAttribute" to text(100, "mail"),
            "groupAttribute" to text(100, "memberOf"),
            "groupBaseDn" to text(1000),
            "groupFilter" to text(1000, "(member={dn})"),
            "groupNameAttribute" to text(100, "cn"),
            "nestedGroups" to mapOf("type" to "boolean", "default" to false),
            "nestedGroupDepth" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 10, "default" to 5),
            "connectTimeoutMillis" to mapOf("type" to "integer", "minimum" to 100, "maximum" to 60_000, "default" to 5_000),
            "readTimeoutMillis" to mapOf("type" to "integer", "minimum" to 100, "maximum" to 120_000, "default" to 10_000),
            // External database
            "connection" to mapOf("type" to "string", "maxLength" to 63, "default" to ""),
            "usersTable" to ident(""),
            "usernameColumn" to ident("username"),
            "passwordColumn" to ident("password_hash"),
            "enabledColumn" to ident(""),
            "displayNameColumn" to ident(""),
            "emailColumn" to ident(""),
            "rolesTable" to ident(""),
            "rolesUsernameColumn" to ident("username"),
            "roleColumn" to ident("role"),
            "hashScheme" to mapOf("type" to "string", "enum" to ExternalPasswordHashes.SCHEMES, "default" to ExternalPasswordHashes.ARGON2ID),
            "queryTimeoutSeconds" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 120, "default" to 10),
            // Roles
            "roleMappings" to mapOf(
                "type" to "array",
                "maxItems" to 256,
                "default" to emptyList<Any>(),
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "value" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 1000),
                        "roles" to nameList(),
                    ),
                    "required" to listOf("value", "roles"),
                ),
            ),
            "mapRolesByName" to mapOf("type" to "boolean", "default" to false),
            "defaultRoles" to nameList(),
            "users" to mapOf(
                "type" to "array",
                "maxItems" to 4096,
                "default" to emptyList<Any>(),
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "username" to mapOf("type" to "string", "pattern" to UserSource.USERNAME_PATTERN),
                        "roles" to nameList(),
                    ),
                    "required" to listOf("username", "roles"),
                ),
            ),
            "allowUnlistedUsers" to mapOf("type" to "boolean", "default" to false),
        ),
        "required" to listOf("type"),
    )

    private fun text(max: Int, default: String = "") = mapOf("type" to "string", "maxLength" to max, "default" to default)
    private fun ident(default: String) = mapOf("type" to "string", "pattern" to IDENTIFIER, "maxLength" to 127, "default" to default)
    private fun nameList() = mapOf(
        "type" to "array", "items" to mapOf("type" to "string", "pattern" to NAME),
        "uniqueItems" to true, "maxItems" to 64, "default" to emptyList<String>(),
    )
}

/** A user of an `ldap-internal` source and the roles kept for it in the gateway. */
data class SourceUserRoles(val username: String, val roles: Set<String>)

/** A user source resource as read, with defaults applied. */
data class UserSource(
    val name: String,
    val type: String,
    val description: String,
    val enabled: Boolean,
    val ldapUrls: List<String>,
    val startTls: Boolean,
    val allowPlaintext: Boolean,
    val bindDn: String,
    val bindPasswordRef: String,
    val userBaseDn: String,
    val userFilter: String,
    val userBindPattern: String,
    val usernameAttribute: String,
    val displayNameAttribute: String,
    val emailAttribute: String,
    val groupAttribute: String,
    val groupBaseDn: String,
    val groupFilter: String,
    val groupNameAttribute: String,
    val nestedGroups: Boolean,
    val nestedGroupDepth: Int,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val connection: String,
    val usersTable: String,
    val usernameColumn: String,
    val passwordColumn: String,
    val enabledColumn: String,
    val displayNameColumn: String,
    val emailColumn: String,
    val rolesTable: String,
    val rolesUsernameColumn: String,
    val roleColumn: String,
    val hashScheme: String,
    val queryTimeoutSeconds: Int,
    val roleMappings: List<RoleMapping>,
    val mapRolesByName: Boolean,
    val defaultRoles: Set<String>,
    val users: List<SourceUserRoles>,
    val allowUnlistedUsers: Boolean,
    val version: Long,
) {
    /** The directory checks passwords. */
    val usesLdap: Boolean get() = type == LDAP || type == LDAP_INTERNAL || type == LDAP_DATABASE
    /** An external database is read (for users and roles, or for roles only). */
    val usesDatabase: Boolean get() = type == DATABASE || type == LDAP_DATABASE

    /**
     * Roles for the values a user carries ([values]: group names and DNs, or role column values): [defaultRoles], the
     * roles of every mapping whose value matches one of them (case-insensitive), and with [mapRolesByName] every value
     * that is itself the name of an existing role. Roles that do not exist ([known]) are dropped.
     */
    fun mapRoles(values: Collection<String>, known: Set<String>): Set<String> {
        val lowered = values.mapTo(HashSet()) { it.lowercase() }
        val out = sortedSetOf<String>()
        out += defaultRoles
        for (m in roleMappings) if (m.value.lowercase() in lowered) out += m.roles
        if (mapRolesByName) for (v in values) if (v in known) out += v
        return out.filterTo(sortedSetOf()) { it in known }
    }

    /** The roles kept for [username] by an `ldap-internal` source, or null when it is not listed. */
    fun listedRoles(username: String): Set<String>? = users.firstOrNull { it.username.equals(username, ignoreCase = true) }?.roles

    /** What a source of this type needs before it can be used, as messages; empty when complete. */
    fun problems(): List<String> = buildList {
        if (type !in TYPES) {
            add("type must be one of ${TYPES.joinToString()}")
            return@buildList
        }
        if (usesLdap) {
            if (ldapUrls.isEmpty()) add("ldapUrls needs at least one ldap:// or ldaps:// URL")
            val schemes = ldapUrls.map { it.substringBefore("://") }.toSet()
            if (schemes.size > 1) add("ldapUrls must all use the same scheme")
            if (startTls && "ldaps" in schemes) add("startTls cannot be used with ldaps:// URLs")
            if (schemes == setOf("ldap") && !startTls && !allowPlaintext) {
                add("ldap:// without startTls sends passwords in clear text; use ldaps://, turn on startTls, or set allowPlaintext")
            }
            if (bindPasswordRef.isNotBlank() && bindDn.isBlank()) add("bindPasswordRef needs bindDn")
            if (userBindPattern.isBlank()) {
                if (userBaseDn.isBlank()) add("userBaseDn is required (or set userBindPattern)")
                if (!userFilter.contains("{username}")) add("userFilter must contain {username}")
            } else if (!userBindPattern.contains("{username}")) {
                add("userBindPattern must contain {username}")
            }
            if (userBindPattern.isNotBlank() && userBaseDn.isNotBlank() && !userFilter.contains("{username}")) add("userFilter must contain {username}")
            if (groupBaseDn.isNotBlank() && !groupFilter.contains("{dn}") && !groupFilter.contains("{username}")) {
                add("groupFilter must contain {dn} or {username}")
            }
            for ((field, value) in listOf("userFilter" to userFilter, "groupFilter" to groupFilter)) {
                if (value.isNotBlank() && !balancedFilter(value)) add("$field is not a parenthesised LDAP filter")
            }
        }
        if (usesDatabase) {
            if (connection.isBlank()) add("connection is required")
        }
        if (type == DATABASE) {
            if (usersTable.isBlank()) add("usersTable is required")
            if (usernameColumn.isBlank()) add("usernameColumn is required")
            if (passwordColumn.isBlank()) add("passwordColumn is required")
            if (rolesTable.isNotBlank() && (rolesUsernameColumn.isBlank() || roleColumn.isBlank())) {
                add("rolesTable needs rolesUsernameColumn and roleColumn")
            }
        }
        if (type == LDAP_DATABASE) {
            if (rolesTable.isBlank()) add("rolesTable is required")
            if (rolesUsernameColumn.isBlank()) add("rolesUsernameColumn is required")
            if (roleColumn.isBlank()) add("roleColumn is required")
        }
        if (type != LDAP_INTERNAL && users.isNotEmpty()) add("users is only used by ldap-internal sources")
        val dupes = users.groupBy { it.username.lowercase() }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) add("users lists ${dupes.sorted().joinToString()} more than once")
    }

    /** Every role this resource names. */
    fun namedRoles(): Set<String> = defaultRoles + roleMappings.flatMap { it.roles } + users.flatMap { it.roles }

    companion object {
        const val LDAP = "ldap"
        const val DATABASE = "database"
        const val LDAP_INTERNAL = "ldap-internal"
        const val LDAP_DATABASE = "ldap-database"
        val TYPES = listOf(LDAP, DATABASE, LDAP_INTERNAL, LDAP_DATABASE)

        /**
         * Usernames an external source accepts at login. Wider than internal names (directories use dots, `@` and
         * capitals), but no filter, DN or SQL metacharacter can appear in one.
         */
        const val USERNAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._@-]{0,252}$"
        val USERNAME = Regex(USERNAME_PATTERN)

        private fun balancedFilter(f: String): Boolean {
            val t = f.trim()
            if (!t.startsWith("(") || !t.endsWith(")")) return false
            var depth = 0
            for (c in t) {
                if (c == '(') depth++
                if (c == ')') depth--
                if (depth < 0) return false
            }
            return depth == 0
        }

        @Suppress("UNCHECKED_CAST")
        fun of(name: String, spec: Map<String, Any?>, version: Long): UserSource = UserSource(
            name = name,
            type = Specs.str(spec, "type") ?: "",
            description = Specs.str(spec, "description") ?: "",
            enabled = Specs.bool(spec, "enabled", true),
            ldapUrls = Specs.strings(spec, "ldapUrls"),
            startTls = Specs.bool(spec, "startTls"),
            allowPlaintext = Specs.bool(spec, "allowPlaintext"),
            bindDn = Specs.str(spec, "bindDn") ?: "",
            bindPasswordRef = Specs.str(spec, "bindPasswordRef") ?: "",
            userBaseDn = Specs.str(spec, "userBaseDn") ?: "",
            userFilter = Specs.str(spec, "userFilter") ?: "(uid={username})",
            userBindPattern = Specs.str(spec, "userBindPattern") ?: "",
            usernameAttribute = Specs.str(spec, "usernameAttribute") ?: "uid",
            displayNameAttribute = Specs.str(spec, "displayNameAttribute") ?: "cn",
            emailAttribute = Specs.str(spec, "emailAttribute") ?: "mail",
            groupAttribute = Specs.str(spec, "groupAttribute") ?: "memberOf",
            groupBaseDn = Specs.str(spec, "groupBaseDn") ?: "",
            groupFilter = Specs.str(spec, "groupFilter") ?: "(member={dn})",
            groupNameAttribute = Specs.str(spec, "groupNameAttribute") ?: "cn",
            nestedGroups = Specs.bool(spec, "nestedGroups"),
            nestedGroupDepth = Specs.int(spec, "nestedGroupDepth", 5),
            connectTimeoutMillis = Specs.int(spec, "connectTimeoutMillis", 5_000),
            readTimeoutMillis = Specs.int(spec, "readTimeoutMillis", 10_000),
            connection = Specs.str(spec, "connection") ?: "",
            usersTable = Specs.str(spec, "usersTable") ?: "",
            usernameColumn = Specs.str(spec, "usernameColumn") ?: "username",
            passwordColumn = Specs.str(spec, "passwordColumn") ?: "password_hash",
            enabledColumn = Specs.str(spec, "enabledColumn") ?: "",
            displayNameColumn = Specs.str(spec, "displayNameColumn") ?: "",
            emailColumn = Specs.str(spec, "emailColumn") ?: "",
            rolesTable = Specs.str(spec, "rolesTable") ?: "",
            rolesUsernameColumn = Specs.str(spec, "rolesUsernameColumn") ?: "username",
            roleColumn = Specs.str(spec, "roleColumn") ?: "role",
            hashScheme = Specs.str(spec, "hashScheme") ?: ExternalPasswordHashes.ARGON2ID,
            queryTimeoutSeconds = Specs.int(spec, "queryTimeoutSeconds", 10),
            roleMappings = (spec["roleMappings"] as? List<*>).orEmpty().mapNotNull { raw ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                RoleMapping(Specs.str(m, "value") ?: return@mapNotNull null, Specs.strings(m, "roles").toSet())
            },
            mapRolesByName = Specs.bool(spec, "mapRolesByName"),
            defaultRoles = Specs.strings(spec, "defaultRoles").toSet(),
            users = (spec["users"] as? List<*>).orEmpty().mapNotNull { raw ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                SourceUserRoles(Specs.str(m, "username") ?: return@mapNotNull null, Specs.strings(m, "roles").toSet())
            },
            allowUnlistedUsers = Specs.bool(spec, "allowUnlistedUsers"),
            version = version,
        )
    }
}

/**
 * User source resources. Writes go through the config store, so they are audited as config changes and validated
 * against [UserSourceSchema]; the REST layer adds the secret handling and the per-type checks.
 */
interface UserSourceDirectory {
    fun list(): List<UserSource>
    fun get(name: String): UserSource?
    /** Raw spec with defaults, for the REST view. */
    fun spec(name: String): Map<String, Any?>?
    fun save(name: String, spec: Map<String, Any?>, actor: String, host: String = ""): UserSource
    fun delete(name: String, actor: String, host: String = "")
}

internal class UserSourceService(private val docs: ConfigDocs) : UserSourceDirectory {
    override fun list(): List<UserSource> = docs.list(Kinds.USER_SOURCE).mapNotNull { get(it.metadata.name) }

    override fun get(name: String): UserSource? {
        val r = runCatching { docs.get(Kinds.USER_SOURCE, name) }.getOrNull() ?: return null
        val spec = docs.spec(Kinds.USER_SOURCE, name) ?: return null
        return UserSource.of(name, spec, r.metadata.version)
    }

    override fun spec(name: String): Map<String, Any?>? = runCatching { docs.spec(Kinds.USER_SOURCE, name) }.getOrNull()

    override fun save(name: String, spec: Map<String, Any?>, actor: String, host: String): UserSource {
        docs.modify(Kinds.USER_SOURCE, name, actor, host) { spec }
        return get(name)!!
    }

    override fun delete(name: String, actor: String, host: String) = docs.remove(Kinds.USER_SOURCE, name, actor, host)
}
