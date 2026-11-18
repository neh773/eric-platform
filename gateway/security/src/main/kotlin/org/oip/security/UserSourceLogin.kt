package org.oip.security

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock

/**
 * The gateway's named database connections, as a database user source reads them (SEC-010, SEC-025). The gateway
 * implements it over its connection pools; the security module only sees a JDBC connection and how that connection
 * quotes an identifier.
 */
interface UserSourceConnections {
    /** Run [block] on a pooled connection of [connection]. [quote] quotes one identifier part for its database. */
    fun <T> use(connection: String, block: (Connection, quote: (String) -> String) -> T): T
}

/** A user an external source signed in or found. Never carries a password or its hash. */
data class ExternalUser(
    val source: String,
    val username: String,
    val displayName: String,
    val email: String,
    val roles: Set<String>,
    /** Directory group names (LDAP types) or role column values (database types), before mapping. */
    val groups: List<String>,
    /** The directory entry, for LDAP types; empty otherwise. */
    val dn: String = "",
)

sealed interface SourceLogin {
    data class Success(val user: ExternalUser) : SourceLogin
    data object BadCredentials : SourceLogin
    data object Disabled : SourceLogin
    data class Unavailable(val reason: String) : SourceLogin
}

/** What a user source check (SEC-027) found on an external source. [reason] is set when the source could not be asked. */
data class SourceCheck(val check: UserCheck, val user: ExternalUser?, val reason: String?)

/** The rows of a user in the tables of a database user source. */
data class DatabaseUserRow(val username: String, val passwordHash: String, val enabled: Boolean, val displayName: String, val email: String)

/**
 * Reads the user and role tables of a database user source (SEC-010, SEC-025). Table and column names come from the
 * resource, where [UserSourceSchema.IDENTIFIER] limits them to plain identifiers, and are quoted for the connection's
 * database; the user name is always a bound parameter. A name that matches more than one user row matches none.
 *
 * Doc source: FEATURES SEC-010.
 */
class DatabaseUserTables(private val source: UserSource, private val connections: UserSourceConnections) {
    fun user(username: String): DatabaseUserRow? = connections.use(source.connection) { conn, quote ->
        val columns = buildList {
            add(source.usernameColumn)
            add(source.passwordColumn)
            add(source.enabledColumn.ifBlank { null })
            add(source.displayNameColumn.ifBlank { null })
            add(source.emailColumn.ifBlank { null })
        }
        val select = columns.joinToString(", ") { it?.let { c -> name(c, quote) } ?: "NULL" }
        val sql = "SELECT $select FROM ${name(source.usersTable, quote)} WHERE ${name(source.usernameColumn, quote)} = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.queryTimeout = source.queryTimeoutSeconds
            ps.maxRows = 2
            ps.setString(1, username)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                val row = DatabaseUserRow(
                    username = rs.getString(1) ?: username,
                    passwordHash = rs.getString(2) ?: "",
                    enabled = if (source.enabledColumn.isBlank()) true else truthy(rs, 3),
                    displayName = if (source.displayNameColumn.isBlank()) "" else rs.getString(4).orEmpty(),
                    email = if (source.emailColumn.isBlank()) "" else rs.getString(5).orEmpty(),
                )
                if (rs.next()) null else row
            }
        }
    }

    /** The role column values of [username] in the roles table; empty when the resource names no roles table. */
    fun roles(username: String): List<String> {
        if (source.rolesTable.isBlank()) return emptyList()
        return connections.use(source.connection) { conn, quote ->
            val sql = "SELECT ${name(source.roleColumn, quote)} FROM ${name(source.rolesTable, quote)} WHERE ${name(source.rolesUsernameColumn, quote)} = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.queryTimeout = source.queryTimeoutSeconds
                ps.maxRows = MAX_ROLE_ROWS
                ps.setString(1, username)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<String>()
                    while (rs.next()) rs.getString(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                    out.distinct()
                }
            }
        }
    }

    companion object {
        const val MAX_ROLE_ROWS = 256

        /** [identifier] (`table` or `schema.table`), each part quoted. */
        fun name(identifier: String, quote: (String) -> String): String {
            require(Regex(UserSourceSchema.IDENTIFIER).matches(identifier) && identifier.isNotEmpty()) { "not an identifier" }
            return identifier.split('.').joinToString(".") { quote(it) }
        }

        private fun truthy(rs: ResultSet, column: Int): Boolean {
            val v = rs.getObject(column) ?: return false
            return when (v) {
                is Boolean -> v
                is Number -> v.toLong() != 0L
                else -> v.toString().trim().lowercase() in setOf("true", "t", "y", "yes", "1", "on")
            }
        }
    }
}

/**
 * Logins and user checks on external user sources (SEC-007, SEC-010, SEC-021, SEC-025).
 *
 * - `ldap`: [LdapUserSource] checks the password; roles are [UserSource.mapRoles] of the user's group names and DNs.
 * - `ldap-internal`: the directory checks the password; roles are the ones the resource keeps for the user
 *   ([UserSource.users]). A user it does not list cannot log in, unless [UserSource.allowUnlistedUsers] gives such a
 *   user [UserSource.defaultRoles].
 * - `database`: the users table holds the password hash, checked with [ExternalPasswordHashes] under
 *   [UserSource.hashScheme]; roles come from the roles table. A missing user still costs one hash verification.
 * - `ldap-database`: the directory checks the password; roles come from the roles table of the external database.
 *
 * Role values from a database name roles directly; mappings and default roles add to them. Only roles that exist on
 * this gateway are given. Each login records one audit row (success or failure) with the source's name; the password
 * is never recorded, and is wiped before a call returns.
 */
class UserSourceAuthenticator(
    private val sources: UserSourceDirectory,
    private val secrets: SecretStore,
    private val knownRoles: () -> Set<String>,
    private val connections: () -> UserSourceConnections?,
    private val audit: AuditLog,
    private val auditEnabled: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun authenticate(sourceName: String, username: String, password: CharArray, host: String = ""): SourceLogin {
        try {
            val source = sources.get(sourceName)?.takeIf { it.enabled }
                ?: return record(username, host, sourceName, SourceLogin.Unavailable("user source '$sourceName' is not available"))
            return record(username, host, sourceName, login(source, username, password))
        } finally {
            password.fill('\u0000')
        }
    }

    /** Verify a user on [sourceName] without logging in (SEC-027). [password] null looks the user up only. */
    fun check(sourceName: String, username: String, password: CharArray?, actor: String, host: String = ""): SourceCheck? {
        try {
            val source = sources.get(sourceName) ?: return null
            val result = if (password != null) {
                when (val login = login(source, username, password)) {
                    is SourceLogin.Success -> SourceCheck(userCheck(source, username, true, true, login.user, true), login.user, null)
                    SourceLogin.Disabled -> SourceCheck(userCheck(source, username, true, false, null, true), null, null)
                    SourceLogin.BadCredentials -> SourceCheck(userCheck(source, username, null, null, null, false), null, null)
                    is SourceLogin.Unavailable -> SourceCheck(userCheck(source, username, null, null, null, null), null, login.reason)
                }
            } else {
                lookup(source, username)
            }
            if (auditEnabled()) {
                val c = result.check
                val detail = buildString {
                    append("source=").append(sourceName)
                    append(";exists=").append(c.exists)
                    append(";password=").append(if (password == null) "not checked" else if (c.passwordValid == true) "valid" else "invalid")
                    if (result.reason != null) append(";unavailable")
                }
                audit.append(AuditDraft(clock.millis(), actor, AuditActions.USER_SOURCE_CHECK, AuditStatus.SUCCESS, actorHost = host, target = username.take(200), detail = detail))
            }
            return result
        } finally {
            password?.fill('\u0000')
        }
    }

    private fun userCheck(source: UserSource, username: String, exists: Boolean?, enabled: Boolean?, user: ExternalUser?, valid: Boolean?) = UserCheck(
        source = source.name,
        username = user?.username ?: username,
        // A wrong password does not say whether the name exists: the directory gives one answer for both.
        exists = exists ?: false,
        enabled = enabled,
        roles = user?.roles?.sorted() ?: emptyList(),
        passwordChecked = true,
        passwordValid = valid,
    )

    private fun lookup(source: UserSource, username: String): SourceCheck {
        fun found(user: ExternalUser?, enabled: Boolean?) = SourceCheck(
            UserCheck(source.name, user?.username ?: username, user != null, enabled, user?.roles?.sorted() ?: emptyList(), false, null), user, null,
        )
        fun unavailable(reason: String) = SourceCheck(UserCheck(source.name, username, false, null, emptyList(), false, null), null, reason)
        if (!UserSource.USERNAME.matches(username)) return found(null, null)
        return try {
            if (source.type == UserSource.DATABASE) {
                val row = tables(source).user(username) ?: return found(null, null)
                val values = tables(source).roles(row.username)
                found(ExternalUser(source.name, row.username, row.displayName, row.email, databaseRoles(source, values), values), row.enabled)
            } else {
                when (val outcome = ldap(source).lookup(username)) {
                    is DirectoryOutcome.Found -> {
                        val user = directoryUser(source, outcome.user) ?: return found(null, null)
                        found(user, true)
                    }
                    DirectoryOutcome.NotFound, DirectoryOutcome.BadCredentials -> found(null, null)
                    is DirectoryOutcome.Unavailable -> unavailable(outcome.reason)
                }
            }
        } catch (e: SourceUnavailable) {
            unavailable(e.message ?: "unavailable")
        }
    }

    private fun login(source: UserSource, username: String, password: CharArray): SourceLogin {
        if (!UserSource.USERNAME.matches(username) || password.isEmpty()) {
            if (source.type == UserSource.DATABASE) ExternalPasswordHashes.verifyMissing(source.hashScheme, password)
            return SourceLogin.BadCredentials
        }
        return try {
            if (source.type == UserSource.DATABASE) databaseLogin(source, username, password)
            else when (val outcome = ldap(source).authenticate(username, password)) {
                is DirectoryOutcome.Found -> directoryUser(source, outcome.user)?.let { SourceLogin.Success(it) } ?: SourceLogin.BadCredentials
                DirectoryOutcome.BadCredentials, DirectoryOutcome.NotFound -> SourceLogin.BadCredentials
                is DirectoryOutcome.Unavailable -> SourceLogin.Unavailable(outcome.reason)
            }
        } catch (e: SourceUnavailable) {
            SourceLogin.Unavailable(e.message ?: "unavailable")
        }
    }

    private fun databaseLogin(source: UserSource, username: String, password: CharArray): SourceLogin {
        val tables = tables(source)
        val row = tables.user(username)
        if (row == null || row.passwordHash.isBlank()) {
            ExternalPasswordHashes.verifyMissing(source.hashScheme, password)
            return SourceLogin.BadCredentials
        }
        if (!ExternalPasswordHashes.verify(source.hashScheme, row.passwordHash, password)) return SourceLogin.BadCredentials
        if (!row.enabled) return SourceLogin.Disabled
        val values = tables.roles(row.username)
        return SourceLogin.Success(ExternalUser(source.name, row.username, row.displayName, row.email, databaseRoles(source, values), values))
    }

    /** The roles of a directory user under [source]'s type, or null when an `ldap-internal` source does not list the user. */
    private fun directoryUser(source: UserSource, dir: DirectoryUser): ExternalUser? {
        val known = knownRoles()
        val values = dir.groupNames + dir.groupDns
        val (roles, groups) = when (source.type) {
            UserSource.LDAP_INTERNAL -> {
                val listed = source.listedRoles(dir.username)
                val roles = when {
                    listed != null -> listed + source.defaultRoles
                    source.allowUnlistedUsers -> source.defaultRoles
                    else -> return null
                }
                roles.filterTo(sortedSetOf()) { it in known } to dir.groupNames
            }
            UserSource.LDAP_DATABASE -> {
                val dbValues = tables(source).roles(dir.username)
                databaseRoles(source, dbValues) to dbValues
            }
            else -> source.mapRoles(values, known) to dir.groupNames
        }
        return ExternalUser(source.name, dir.username, dir.displayName, dir.email, roles, groups, dir.dn)
    }

    private fun databaseRoles(source: UserSource, values: List<String>): Set<String> {
        val known = knownRoles()
        return (source.mapRoles(values, known) + values.filter { it in known }).toSortedSet()
    }

    private class SourceUnavailable(message: String) : RuntimeException(message)

    private fun tables(source: UserSource): DatabaseUserTables {
        val c = connections() ?: throw SourceUnavailable("database connections are not available")
        return DatabaseUserTables(source, object : UserSourceConnections {
            override fun <T> use(connection: String, block: (Connection, (String) -> String) -> T): T = try {
                c.use(connection, block)
            } catch (e: SQLException) {
                throw SourceUnavailable("the user tables could not be read (SQL state ${e.sqlState ?: "unknown"})")
            } catch (e: SourceUnavailable) {
                throw e
            } catch (e: RuntimeException) {
                throw SourceUnavailable("database connection '$connection' is not available: ${e.message?.take(200) ?: e.javaClass.simpleName}")
            }
        })
    }

    private fun ldap(source: UserSource) = LdapUserSource(source) {
        if (source.bindPasswordRef.isBlank()) null
        else runCatching { secrets.getReference(source.bindPasswordRef) }.getOrNull()?.let { bytes ->
            try {
                Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes)).let { buf -> CharArray(buf.remaining()).also { buf.get(it) } }
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun record(username: String, host: String, sourceName: String, result: SourceLogin): SourceLogin {
        if (!auditEnabled()) return result
        val actor = (if (result is SourceLogin.Success) result.user.username else username).ifBlank { "unknown" }.take(200)
        val (action, status, detail) = when (result) {
            is SourceLogin.Success -> Triple(AuditActions.LOGIN, AuditStatus.SUCCESS, "source=$sourceName")
            SourceLogin.BadCredentials -> Triple(AuditActions.LOGIN_ATTEMPT, AuditStatus.FAILURE, "source=$sourceName;bad credentials")
            SourceLogin.Disabled -> Triple(AuditActions.LOGIN_ATTEMPT, AuditStatus.FAILURE, "source=$sourceName;disabled")
            is SourceLogin.Unavailable -> Triple(AuditActions.LOGIN_ATTEMPT, AuditStatus.FAILURE, "source=$sourceName;source unavailable")
        }
        audit.append(AuditDraft(clock.millis(), actor, action, status, actorHost = host, target = actor, detail = detail))
        return result
    }
}
