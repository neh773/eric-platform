package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.ApiKeyInfo
import org.oip.security.AuditContext
import org.oip.security.CreateUser
import org.oip.security.Kinds
import org.oip.security.PasswordRules
import org.oip.security.RoleInfo
import org.oip.security.SECURITY_MODULE
import org.oip.security.UpdateUser
import org.oip.security.UserAccount

/**
 * REST administration of the internal user source.
 *
 * Create, edit, and delete users and roles, and assign roles to users, as described for an internal
 * user source: -
 * API keys are created, rotated, and revoked (disabled) here:
 * Each route is gated by a permission only the administrator role holds by default (`users.admin`,
 * `roles.admin`, `apikeys.admin`), the same matrix as the gateway security settings:
 *
 * A response never includes a password, password hash, password history, or API-key hash.
 * The API-key secret is returned only from create and rotate. Writes use the caller's audit actor.
 * `ETag` is the config resource version; `If-Match` conflicts when it does not match.
 */
class SecurityApi(private val gw: Gateway) {
    private val security get() = gw.security

    fun register(app: Javalin) {
        app.get("/api/v1/security/users") { ctx -> listUsers(ctx) }
        app.post("/api/v1/security/users") { ctx -> createUser(ctx) }
        app.get("/api/v1/security/users/{name}") { ctx -> getUser(ctx) }
        app.put("/api/v1/security/users/{name}") { ctx -> updateUser(ctx) }
        app.delete("/api/v1/security/users/{name}") { ctx -> deleteUser(ctx) }
        app.post("/api/v1/security/users/{name}/password") { ctx -> setPassword(ctx) }

        app.get("/api/v1/security/roles") { ctx -> listRoles(ctx) }
        app.post("/api/v1/security/roles") { ctx -> createRole(ctx) }
        app.get("/api/v1/security/roles/{name}") { ctx -> getRole(ctx) }
        app.put("/api/v1/security/roles/{name}") { ctx -> updateRole(ctx) }
        app.delete("/api/v1/security/roles/{name}") { ctx -> deleteRole(ctx) }

        app.get("/api/v1/security/api-keys") { ctx -> listKeys(ctx) }
        app.post("/api/v1/security/api-keys") { ctx -> createKey(ctx) }
        app.get("/api/v1/security/api-keys/{id}") { ctx -> getKey(ctx) }
        app.post("/api/v1/security/api-keys/{id}/rotate") { ctx -> rotateKey(ctx) }
        app.delete("/api/v1/security/api-keys/{id}") { ctx -> revokeKey(ctx) }
    }

    private fun listUsers(ctx: Context) {
        val items = security.users.list().sortedBy { it.username }.map { annotated(userJson(it), Kinds.USER, it.username) }
        json(ctx, 200, mapOf("items" to items))
    }

    private fun createUser(ctx: Context) {
        val body = jsonObject(ctx)
        rejectSecrets(body, "password")
        val username = requiredString(body, "username")
        val password = requiredString(body, "password")
        if (password.length > PasswordRules.MAX_CODE_POINTS) throw Problems.badRequest("password is too long")
        val chars = password.toCharArray()
        val account = try {
            security.users.create(
                CreateUser(
                    username = username,
                    password = chars,
                    roles = stringSet(body, "roles") ?: emptySet(),
                    displayName = optionalString(body, "displayName") ?: "",
                    email = optionalString(body, "email") ?: "",
                    phone = optionalString(body, "phone") ?: "",
                    enabled = optionalBool(body, "enabled") ?: true,
                ),
                actor(),
                host(),
            )
        } finally {
            chars.fill('\u0000')
        }
        ctx.header("Location", "/api/v1/security/users/$username")
        send(ctx, 201, userJson(account), Kinds.USER, username)
    }

    private fun getUser(ctx: Context) {
        val name = ctx.pathParam("name")
        val account = security.users.get(name) ?: throw Problems.notFound("User '$name' not found")
        send(ctx, 200, userJson(account), Kinds.USER, name)
    }

    private fun updateUser(ctx: Context) {
        val name = ctx.pathParam("name")
        val body = jsonObject(ctx)
        rejectSecrets(body)
        if (body.containsKey("username") && body["username"] != name) throw Problems.unprocessable("username does not match path")
        checkMatch(ctx, Kinds.USER, name)
        val account = security.users.update(
            UpdateUser(
                username = name,
                displayName = optionalString(body, "displayName"),
                email = optionalString(body, "email"),
                phone = optionalString(body, "phone"),
                enabled = optionalBool(body, "enabled"),
                roles = stringSet(body, "roles"),
            ),
            actor(),
            host(),
        )
        send(ctx, 200, userJson(account), Kinds.USER, name)
    }

    private fun deleteUser(ctx: Context) {
        val name = ctx.pathParam("name")
        checkMatch(ctx, Kinds.USER, name)
        security.users.delete(name, actor(), host())
        ctx.status(204)
    }

    private fun setPassword(ctx: Context) {
        val name = ctx.pathParam("name")
        val body = jsonObject(ctx)
        rejectSecrets(body, "password")
        val password = requiredString(body, "password")
        if (password.length > PasswordRules.MAX_CODE_POINTS) throw Problems.badRequest("password is too long")
        checkMatch(ctx, Kinds.USER, name)
        val chars = password.toCharArray()
        try {
            security.users.setPassword(name, chars, actor(), host())
        } finally {
            chars.fill('\u0000')
        }
        val account = security.users.get(name) ?: throw Problems.notFound("User '$name' not found")
        send(ctx, 200, userJson(account), Kinds.USER, name)
    }

    private fun listRoles(ctx: Context) {
        val items = security.roles.list().sortedBy { it.name }.map { annotated(roleJson(it), Kinds.ROLE, it.name) }
        json(ctx, 200, mapOf("items" to items))
    }

    private fun createRole(ctx: Context) {
        val body = jsonObject(ctx)
        rejectSecrets(body)
        val name = requiredString(body, "name")
        val permissions = stringSet(body, "permissions") ?: throw Problems.badRequest("permissions is required")
        val role = security.roles.create(name, permissions, optionalString(body, "description") ?: "", actor(), host())
        ctx.header("Location", "/api/v1/security/roles/$name")
        send(ctx, 201, roleJson(role), Kinds.ROLE, name)
    }

    private fun getRole(ctx: Context) {
        val name = ctx.pathParam("name")
        val role = security.roles.get(name) ?: throw Problems.notFound("Role '$name' not found")
        send(ctx, 200, roleJson(role), Kinds.ROLE, name)
    }

    private fun updateRole(ctx: Context) {
        val name = ctx.pathParam("name")
        val body = jsonObject(ctx)
        rejectSecrets(body)
        if (body.containsKey("name") && body["name"] != name) throw Problems.unprocessable("name does not match path")
        checkMatch(ctx, Kinds.ROLE, name)
        val role = security.roles.update(
            name,
            permissions = stringSet(body, "permissions"),
            description = optionalString(body, "description"),
            actor = actor(),
            host = host(),
        )
        send(ctx, 200, roleJson(role), Kinds.ROLE, name)
    }

    private fun deleteRole(ctx: Context) {
        val name = ctx.pathParam("name")
        checkMatch(ctx, Kinds.ROLE, name)
        security.roles.delete(name, actor(), host())
        ctx.status(204)
    }

    private fun listKeys(ctx: Context) {
        val items = security.apiKeys.list().sortedBy { it.name }.map { annotated(keyJson(it), Kinds.API_KEY, it.name) }
        json(ctx, 200, mapOf("items" to items))
    }

    private fun createKey(ctx: Context) {
        val body = jsonObject(ctx)
        rejectSecrets(body)
        val name = requiredString(body, "name")
        val issued = security.apiKeys.create(
            name = name,
            scopes = stringSet(body, "scopes") ?: emptySet(),
            description = optionalString(body, "description") ?: "",
            secureConnectionRequired = optionalBool(body, "secureConnectionRequired") ?: true,
            actor = actor(),
            host = host(),
        )
        ctx.header("Location", "/api/v1/security/api-keys/$name")
        send(ctx, 201, keyJson(issued.info, issued.token), Kinds.API_KEY, name)
    }

    private fun getKey(ctx: Context) {
        val name = ctx.pathParam("id")
        val info = security.apiKeys.get(name) ?: throw Problems.notFound("ApiKey '$name' not found")
        send(ctx, 200, keyJson(info), Kinds.API_KEY, name)
    }

    private fun rotateKey(ctx: Context) {
        val name = ctx.pathParam("id")
        checkMatch(ctx, Kinds.API_KEY, name)
        val issued = security.apiKeys.rotate(name, actor(), host())
        send(ctx, 200, keyJson(issued.info, issued.token), Kinds.API_KEY, name)
    }

    /** Disable the key. The same token stays invalid until something enables it again; this API does not. */
    private fun revokeKey(ctx: Context) {
        val name = ctx.pathParam("id")
        checkMatch(ctx, Kinds.API_KEY, name)
        val info = security.apiKeys.revoke(name, actor(), host())
        send(ctx, 200, keyJson(info), Kinds.API_KEY, name)
    }

    private fun send(ctx: Context, status: Int, body: Map<String, Any?>, kind: String, name: String) {
        val out = annotated(body, kind, name)
        out["version"]?.let { ctx.header("ETag", "\"$it\"") }
        json(ctx, status, out)
    }

    private fun annotated(body: Map<String, Any?>, kind: String, name: String): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap(body)
        version(kind, name)?.let { out["version"] = it }
        return out
    }

    private fun json(ctx: Context, status: Int, body: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(body))
    }

    private fun version(kind: String, name: String): Long? =
        gw.store.get(ResourceId(SECURITY_MODULE, Scope.GATEWAY, kind, name))?.metadata?.version

    private fun checkMatch(ctx: Context, kind: String, name: String) {
        val expected = ifMatch(ctx) ?: return
        val current = version(kind, name) ?: throw Problems.notFound("$kind '$name' not found")
        if (expected != current) throw Problems.conflict("$kind '$name' version is $current")
    }

    private fun ifMatch(ctx: Context): Long? = ctx.header("If-Match")?.trim()?.removePrefix("W/")?.trim('"')?.let {
        it.toLongOrNull() ?: throw Problems.badRequest("If-Match must be a resource version")
    }

    private fun actor(): String = AuditContext.current()?.actor ?: "api"

    private fun host(): String = AuditContext.current()?.host ?: ""

    private fun rejectSecrets(body: Map<String, Any?>, vararg allowed: String) {
        val allow = allowed.toSet()
        for (key in SECRET_FIELDS) {
            if (key in allow) continue
            if (body.containsKey(key)) throw Problems.unprocessable("$key is not accepted")
        }
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

    private fun requiredString(body: Map<String, Any?>, key: String): String {
        val value = body[key] ?: throw Problems.badRequest("$key is required")
        return value as? String ?: throw Problems.unprocessable("$key must be a string")
    }

    /** Absent key is null (leave unchanged). JSON null clears a string field. */
    private fun optionalString(body: Map<String, Any?>, key: String): String? {
        if (!body.containsKey(key)) return null
        val value = body[key] ?: return ""
        return value as? String ?: throw Problems.unprocessable("$key must be a string")
    }

    private fun optionalBool(body: Map<String, Any?>, key: String): Boolean? {
        if (!body.containsKey(key) || body[key] == null) return null
        return body[key] as? Boolean ?: throw Problems.unprocessable("$key must be a boolean")
    }

    /** Absent key is null (leave unchanged). A present value must be an array of strings. */
    private fun stringSet(body: Map<String, Any?>, key: String): Set<String>? {
        if (!body.containsKey(key)) return null
        val raw = body[key] ?: throw Problems.unprocessable("$key must be an array of strings")
        if (raw !is List<*>) throw Problems.unprocessable("$key must be an array of strings")
        return raw.map { it as? String ?: throw Problems.unprocessable("$key must be an array of strings") }.toSet()
    }

    private fun userJson(account: UserAccount): LinkedHashMap<String, Any?> = linkedMapOf(
        "username" to account.username,
        "displayName" to account.displayName,
        "email" to account.email,
        "phone" to account.phone,
        "enabled" to account.enabled,
        "roles" to account.roles.sorted(),
        "passwordChangedAt" to account.passwordChangedAt.toString(),
        "passwordExpired" to account.passwordExpired,
    )

    private fun roleJson(role: RoleInfo): LinkedHashMap<String, Any?> = linkedMapOf(
        "name" to role.name,
        "description" to role.description,
        "permissions" to role.permissions.sorted(),
    )

    private fun keyJson(info: ApiKeyInfo, secret: String? = null): LinkedHashMap<String, Any?> {
        val body = linkedMapOf<String, Any?>(
            "name" to info.name,
            "description" to info.description,
            "enabled" to info.enabled,
            "keyId" to info.keyId,
            "scopes" to info.scopes.sorted(),
            "secureConnectionRequired" to info.secureConnectionRequired,
            "createdAt" to info.createdAt.toString(),
        )
        if (secret != null) body["secret"] = secret
        return body
    }

    companion object {
        private val SECRET_FIELDS = listOf("password", "passwordHash", "passwordHistory", "keyHash", "secret", "token")
    }
}
