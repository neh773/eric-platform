package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.db.DatabaseService
import org.oip.security.AuditContext
import org.oip.security.INTERNAL_SOURCE
import org.oip.security.Kinds
import org.oip.security.SECURITY_MODULE
import org.oip.security.SecretReferences
import org.oip.security.UserSource
import org.oip.security.UserSourceConnections
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat

/**
 * User sources (SEC-007, SEC-010, SEC-021, SEC-025): `org.oip.security/UserSource` resources for an LDAP or Active
 * Directory server, tables in an external database, or a hybrid of the two.
 *
 * `/api/v1/security/user-sources` creates, reads, replaces and deletes them. The directory service account's password
 * is accepted as `bindPassword` on create and replace, stored in the secret store as `usersource-<name>` and named from
 * the resource as `secret://...`; no response carries it, only `bindPasswordSet`. Each save checks the type's required
 * fields, that every named role exists, and that a named database connection exists. The source that
 * GatewaySecurity.systemUserSource names is the one gateway logins use (`inUse` in the view); it cannot be deleted
 * while it is. Disabling or deleting a source ends the sessions it signed in. All of it needs `users.admin`.
 *
 * The user source check (`POST .../{source}/check`, SEC-027) is served by [IdentityProviderApi].
 *
 * Doc source: FEATURES SEC-007.
 * Doc source: FEATURES SEC-010.
 * Doc source: FEATURES SEC-021.
 * Doc source: FEATURES SEC-025.
 */
class UserSourceApi(private val gw: Gateway) {
    private val security get() = gw.security

    fun register(app: Javalin) {
        app.get(BASE) { list(it) }
        app.post(BASE) { create(it) }
        app.get("$BASE/{name}") { get(it) }
        app.put("$BASE/{name}") { replace(it) }
        app.delete("$BASE/{name}") { delete(it) }
    }

    private fun list(ctx: Context) {
        val items = security.userSources.list().sortedBy { it.name }.map { view(it) }
        json(ctx, 200, mapOf("items" to items, "systemUserSource" to activeName()))
    }

    private fun get(ctx: Context) {
        val s = security.userSources.get(ctx.pathParam("name")) ?: throw notFound(ctx.pathParam("name"))
        send(ctx, 200, s)
    }

    private fun create(ctx: Context) {
        val body = jsonObject(ctx)
        val name = body["name"] as? String ?: throw Problems.badRequest("name is required")
        if (!NAME.matches(name)) throw Problems.unprocessable("name must be lowercase letters, digits and hyphens")
        if (name == INTERNAL_SOURCE) throw Problems.unprocessable("'$INTERNAL_SOURCE' is the gateway's own user source")
        if (security.userSources.get(name) != null) throw Problems.conflict("user source '$name' already exists")
        val saved = save(name, body - "name", current = null)
        ctx.header("Location", "$BASE/$name")
        send(ctx, 201, saved)
    }

    private fun replace(ctx: Context) {
        val name = ctx.pathParam("name")
        val body = jsonObject(ctx)
        if (body.containsKey("name") && body["name"] != name) throw Problems.unprocessable("name does not match path")
        val current = security.userSources.get(name) ?: throw notFound(name)
        ifMatch(ctx)?.let { if (it != current.version) throw Problems.conflict("UserSource '$name' version is ${current.version}") }
        send(ctx, 200, save(name, body - "name", current))
    }

    private fun delete(ctx: Context) {
        val name = ctx.pathParam("name")
        val current = security.userSources.get(name) ?: throw notFound(name)
        ifMatch(ctx)?.let { if (it != current.version) throw Problems.conflict("UserSource '$name' version is ${current.version}") }
        if (security.gatewaySecurity.get().systemUserSource == name) {
            throw Problems.conflict("user source '$name' is the gateway's system user source; choose another in gateway security first")
        }
        security.userSources.delete(name, actor(), host())
        if (current.bindPasswordRef.isNotBlank()) runCatching { security.secrets.delete(SecretReferences.parse(current.bindPasswordRef)) }
        gw.httpAuth.deleteProviderSessions(sessionMarker(name))
        ctx.status(204)
    }

    /**
     * Validate and store [body] as the source [name]. `bindPassword`: absent keeps the stored one, a string replaces
     * it, null or "" removes it. The resource only ever holds the `secret://` reference.
     */
    private fun save(name: String, body: Map<String, Any?>, current: UserSource?): UserSource {
        if (body.containsKey("bindPasswordRef")) throw Problems.unprocessable("bindPasswordRef is set by the gateway; send bindPassword")
        for (key in READ_ONLY) if (body.containsKey(key)) throw Problems.unprocessable("$key is read-only")
        val given = body.containsKey("bindPassword")
        val secret = body["bindPassword"]
        if (secret != null && secret !is String) throw Problems.unprocessable("bindPassword must be a string")
        if (secret is String && secret.length > 1024) throw Problems.unprocessable("bindPassword is too long")
        val spec = LinkedHashMap(body - "bindPassword")
        val type = spec["type"] as? String ?: current?.type ?: throw Problems.badRequest("type is required")
        spec["type"] = type
        val secretName = secretName(name)
        val keepRef = current?.bindPasswordRef.orEmpty()
        val newSecret = (secret as? String)?.takeIf { it.isNotEmpty() }
        spec["bindPasswordRef"] = when {
            newSecret != null -> SecretReferences.format(secretName)
            given -> ""
            else -> keepRef
        }
        val draft = UserSource.of(name, gw.store.registry.applyDefaults(SECURITY_MODULE, Kinds.USER_SOURCE, spec), current?.version ?: 0L)
        val problems = draft.problems().toMutableList()
        if (draft.usesLdap && draft.bindDn.isNotBlank() && draft.bindPasswordRef.isBlank()) problems += "bindDn needs bindPassword"
        val known = security.roles.list().mapTo(HashSet()) { it.name }
        draft.namedRoles().filter { it !in known }.distinct().sorted().forEach { problems += "role '$it' does not exist" }
        if (draft.usesDatabase && draft.connection.isNotBlank() && runCatching { gw.databases.connection(draft.connection) }.isFailure) {
            problems += "database connection '${draft.connection}' does not exist"
        }
        if (problems.isNotEmpty()) throw Problems.unprocessable("user source is not valid", problems.distinct())
        if (newSecret != null) security.secrets.putText(secretName, newSecret, "user source $name bind password")
        val saved = try {
            security.userSources.save(name, spec, actor(), host())
        } catch (e: Exception) {
            if (newSecret != null && keepRef.isBlank()) runCatching { security.secrets.delete(secretName) }
            throw e
        }
        if (given && newSecret == null && keepRef.isNotBlank()) runCatching { security.secrets.delete(SecretReferences.parse(keepRef)) }
        // A source that is turned off, or whose type or role rules changed, ends the sessions it signed in; the next login
        // maps the roles again.
        if (current != null && (!saved.enabled || rolesChanged(current, saved))) gw.httpAuth.deleteProviderSessions(sessionMarker(name))
        return saved
    }

    private fun rolesChanged(a: UserSource, b: UserSource): Boolean =
        a.type != b.type || a.roleMappings != b.roleMappings || a.defaultRoles != b.defaultRoles || a.users != b.users ||
            a.mapRolesByName != b.mapRolesByName || a.allowUnlistedUsers != b.allowUnlistedUsers

    private fun activeName(): String = security.gatewaySecurity.get().systemUserSource

    private fun send(ctx: Context, status: Int, s: UserSource) {
        ctx.header("ETag", "\"${s.version}\"")
        json(ctx, status, view(s))
    }

    /** The resource as the API shows it: no bind password and no reference to it. */
    private fun view(s: UserSource): Map<String, Any?> {
        val spec = security.userSources.spec(s.name).orEmpty().filterKeys { it !in HIDDEN }
        val out = linkedMapOf<String, Any?>("name" to s.name)
        out.putAll(spec)
        out["bindPasswordSet"] = s.bindPasswordRef.isNotBlank()
        out["inUse"] = activeName() == s.name
        out["version"] = s.version
        return out
    }

    private fun secretName(source: String): String {
        val candidate = "usersource-$source"
        if (candidate.length <= 63) return candidate
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray())).take(8)
        return "usersource-${source.take(42).trimEnd('-')}-$hash"
    }

    private fun notFound(name: String) = Problems.notFound("UserSource '$name' not found")

    private fun actor(): String = AuditContext.current()?.actor ?: "api"

    private fun host(): String = AuditContext.current()?.host ?: ""

    private fun ifMatch(ctx: Context): Long? = ctx.header("If-Match")?.trim()?.removePrefix("W/")?.trim('"')?.let {
        it.toLongOrNull() ?: throw Problems.badRequest("If-Match must be a resource version")
    }

    private fun json(ctx: Context, status: Int, body: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(body))
    }

    private fun jsonObject(ctx: Context): Map<String, Any?> {
        val parsed = try {
            Json.mapper.readValue(ctx.body(), Any::class.java)
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            throw Problems.badRequest("malformed JSON: ${e.originalMessage}")
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?> ?: throw Problems.badRequest("JSON body must be an object")
    }

    companion object {
        const val BASE = "/api/v1/security/user-sources"
        private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        private val HIDDEN = setOf("bindPasswordRef", "bindPassword")
        private val READ_ONLY = setOf("bindPasswordSet", "inUse", "version")
        /** Marks a session that an external user source signed in. Identity provider names cannot contain `:`. */
        private const val SESSION_PREFIX = "source:"

        fun sessionMarker(source: String): String = SESSION_PREFIX + source

        /** The user source of a session's provider column, or null when it is not a user source session. */
        fun sourceOf(provider: String?): String? = provider?.takeIf { it.startsWith(SESSION_PREFIX) }?.removePrefix(SESSION_PREFIX)
    }
}

/**
 * The gateway's named database connections for database user sources (SEC-010): a pooled connection from
 * [DatabaseService] and the quote characters of the connection's SQL translator.
 */
class UserSourceDatabases(private val databases: DatabaseService) : UserSourceConnections {
    override fun <T> use(connection: String, block: (Connection, (String) -> String) -> T): T {
        val translator = databases.connectionTranslator(connection)
        val open = translator.quoteOpen
        val close = translator.quoteClose
        val conn = databases.borrowConnection(connection)
        var broken = false
        try {
            return block(conn) { part -> open + part + close }
        } catch (e: java.sql.SQLException) {
            broken = conn.isClosed || !runCatching { conn.isValid(1) }.getOrDefault(false)
            throw e
        } finally {
            databases.releaseConnection(conn, broken)
        }
    }
}
