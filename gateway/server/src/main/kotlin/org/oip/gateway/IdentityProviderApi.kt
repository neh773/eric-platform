package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.security.AuditContext
import org.oip.security.IdentityProvider
import org.oip.security.INTERNAL_SOURCE
import org.oip.security.SecretReferences
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Identity provider registry (SEC-014, PLAN P1-33) and the user source check (SEC-027, PLAN P1-34).
 *
 * `/api/v1/security/identity-providers` creates, reads, replaces and deletes OIDC and SAML provider resources
 * (`org.oip.security/IdentityProvider`). `POST .../import` reads an OIDC discovery document or SAML metadata and returns
 * the fields it fills; invalid metadata is 422. A client secret is accepted as `clientSecret` on create and replace,
 * stored in the secret store as `idp-<name>`, and referenced from the resource as `secret://...`. No response, and no
 * resource, carries it; responses say only `clientSecretSet`. All of it needs `users.admin`: a provider decides which
 * roles its users get.
 *
 * `POST /api/v1/security/user-sources/{source}/check` verifies a user on a user source: whether it exists, its roles,
 * and whether a password matches. A password is always hashed (against a dummy hash for a missing user), so the answer
 * time does not tell a right password from a wrong one or a present user from a missing one. One audit row per check;
 * the password is never recorded or returned. Needs `users.admin`.
 *
 * Doc source: FEATURES SEC-014.
 * Doc source: FEATURES SEC-027.
 */
class IdentityProviderApi(private val gw: Gateway) {
    private val security get() = gw.security
    private val login get() = gw.idp

    fun register(app: Javalin) {
        app.get(BASE) { list(it) }
        app.post(BASE) { create(it) }
        app.post("$BASE/import") { import(it) }
        app.get("$BASE/{name}") { get(it) }
        app.put("$BASE/{name}") { replace(it) }
        app.delete("$BASE/{name}") { delete(it) }
        app.post("$BASE/{name}/test") { startTest(it) }
        app.get("$BASE/{name}/test/{id}") { testResult(it) }
        app.post("/api/v1/security/user-sources/{source}/check") { check(it) }
    }

    private fun list(ctx: Context) {
        val items = security.identityProviders.list().sortedBy { it.name }.map { view(it) }
        json(ctx, 200, mapOf("items" to items))
    }

    /** SEC-018: a test ticket for the browser flow; the result is read from [testResult]. Needs `users.admin`. */
    private fun startTest(ctx: Context) {
        val name = ctx.pathParam("name")
        json(ctx, 201, login.createTest(name, AuditContext.current()?.actor ?: "api").also {
            ctx.header("Location", "$BASE/$name/test/${it["testId"]}")
        })
    }

    private fun testResult(ctx: Context) {
        val view = login.testResult(ctx.pathParam("name"), ctx.pathParam("id")) ?: throw Problems.notFound("test not found")
        json(ctx, 200, view)
    }

    private fun get(ctx: Context) {
        val p = security.identityProviders.get(ctx.pathParam("name")) ?: throw notFound(ctx.pathParam("name"))
        send(ctx, 200, p)
    }

    private fun create(ctx: Context) {
        val body = jsonObject(ctx)
        val name = body["name"] as? String ?: throw Problems.badRequest("name is required")
        if (!NAME.matches(name)) throw Problems.unprocessable("name must be lowercase letters, digits and hyphens")
        if (security.identityProviders.get(name) != null) throw Problems.conflict("identity provider '$name' already exists")
        val saved = save(name, body - "name", current = null)
        ctx.header("Location", "$BASE/$name")
        send(ctx, 201, saved)
    }

    private fun replace(ctx: Context) {
        val name = ctx.pathParam("name")
        val body = jsonObject(ctx)
        if (body.containsKey("name") && body["name"] != name) throw Problems.unprocessable("name does not match path")
        val current = security.identityProviders.get(name) ?: throw notFound(name)
        ifMatch(ctx)?.let { if (it != current.version) throw Problems.conflict("IdentityProvider '$name' version is ${current.version}") }
        send(ctx, 200, save(name, body - "name", current))
    }

    private fun delete(ctx: Context) {
        val name = ctx.pathParam("name")
        val current = security.identityProviders.get(name) ?: throw notFound(name)
        ifMatch(ctx)?.let { if (it != current.version) throw Problems.conflict("IdentityProvider '$name' version is ${current.version}") }
        security.identityProviders.delete(name, actor(), host())
        if (current.clientSecretRef.isNotBlank()) runCatching { security.secrets.delete(SecretReferences.parse(current.clientSecretRef)) }
        login.oidc.forget(current.jwksUri)
        gw.httpAuth.deleteProviderSessions(name)
        ctx.status(204)
    }

    private fun import(ctx: Context) {
        val body = jsonObject(ctx)
        val fields = try {
            when (body["type"]) {
                IdentityProvider.OIDC -> {
                    val issuer = body["issuer"] as? String ?: throw Problems.badRequest("issuer is required")
                    login.oidc.discover(issuer)
                }
                IdentityProvider.SAML -> {
                    val xml = body["metadataXml"] as? String ?: (body["metadataUrl"] as? String)?.let { fetchMetadata(it) }
                        ?: throw Problems.badRequest("metadataXml or metadataUrl is required")
                    login.saml.importMetadata(xml)
                }
                else -> throw Problems.badRequest("type must be oidc or saml")
            }
        } catch (e: IdpMetadataInvalid) {
            throw Problems.unprocessable("metadata is not valid: ${e.message}")
        }
        json(ctx, 200, mapOf("fields" to fields))
    }

    private fun fetchMetadata(url: String): String {
        IdpUrls.check("metadataUrl", url, required = true)?.let { throw IdpMetadataInvalid(it) }
        val reply = try {
            IdpHttp().get(url)
        } catch (e: Exception) {
            throw IdpMetadataInvalid("metadata could not be read")
        }
        if (reply.status != 200) throw IdpMetadataInvalid("metadata returned HTTP ${reply.status}")
        return reply.body
    }

    private fun check(ctx: Context) {
        val source = ctx.pathParam("source")
        if (source != INTERNAL_SOURCE && security.userSources.get(source) == null) throw Problems.notFound("user source '$source' not found")
        val body = jsonObject(ctx)
        val username = body["username"] as? String ?: throw Problems.badRequest("username is required")
        if (username.isEmpty() || username.length > 253) throw Problems.unprocessable("username must be 1 to 253 characters")
        val raw = body["password"]
        if (raw != null && raw !is String) throw Problems.unprocessable("password must be a string")
        val password = (raw as? String)?.also {
            if (it.length > org.oip.security.PasswordRules.MAX_CODE_POINTS) throw Problems.badRequest("password is too long")
        }?.toCharArray()
        if (source != INTERNAL_SOURCE) {
            // SEC-007, SEC-010, SEC-021, SEC-025: an external source also shows what it read, so a role mapping can be
            // checked against the groups or role values the directory or the tables gave.
            val external = gw.userSourceLogin.check(source, username, password, actor(), host()) ?: throw Problems.notFound("user source '$source' not found")
            val c = external.check
            json(ctx, 200, linkedMapOf(
                "source" to c.source,
                "username" to c.username,
                "exists" to c.exists,
                "enabled" to c.enabled,
                "roles" to c.roles,
                "passwordChecked" to c.passwordChecked,
                "passwordValid" to c.passwordValid,
                "displayName" to external.user?.displayName,
                "email" to external.user?.email,
                "groups" to external.user?.groups.orEmpty(),
                "dn" to external.user?.dn?.takeIf { it.isNotEmpty() },
                "unavailable" to external.reason,
            ))
            return
        }
        val result = security.users.check(username, password, actor(), host())
        json(ctx, 200, linkedMapOf(
            "source" to result.source,
            "username" to result.username,
            "exists" to result.exists,
            "enabled" to result.enabled,
            "roles" to result.roles,
            "passwordChecked" to result.passwordChecked,
            "passwordValid" to result.passwordValid,
        ))
    }

    /**
     * Validate and store [body] as the provider [name]. `clientSecret`: absent keeps the stored secret, a string replaces
     * it, null or "" removes it. The resource only ever holds the `secret://` reference.
     */
    private fun save(name: String, body: Map<String, Any?>, current: IdentityProvider?): IdentityProvider {
        if (body.containsKey("clientSecretRef")) throw Problems.unprocessable("clientSecretRef is set by the gateway; send clientSecret")
        for (key in READ_ONLY) if (body.containsKey(key)) throw Problems.unprocessable("$key is read-only")
        val secretGiven = body.containsKey("clientSecret")
        val secret = body["clientSecret"]
        if (secret != null && secret !is String) throw Problems.unprocessable("clientSecret must be a string")
        if (secret is String && secret.length > 4096) throw Problems.unprocessable("clientSecret is too long")
        val spec = LinkedHashMap(body - "clientSecret")
        val type = spec["type"] as? String ?: current?.type ?: throw Problems.badRequest("type is required")
        if (current != null && type != current.type) throw Problems.unprocessable("type cannot change; delete and create the provider")
        spec["type"] = type
        val secretName = secretName(name)
        val keepRef = current?.clientSecretRef.orEmpty()
        val newSecret = (secret as? String)?.takeIf { it.isNotEmpty() }
        spec["clientSecretRef"] = when {
            newSecret != null -> SecretReferences.format(secretName)
            secretGiven -> ""
            else -> keepRef
        }
        // Checks on the would-be resource before anything is written.
        val draft = IdentityProvider.of(name, gw.store.registry.applyDefaults(org.oip.security.SECURITY_MODULE, org.oip.security.Kinds.IDENTITY_PROVIDER, spec), current?.version ?: 0L)
        val problems = draft.problems().toMutableList()
        for ((field, value, required) in urlFields(draft)) IdpUrls.check(field, value, required)?.let { problems += it }
        draft.signingCertificates.forEach { cert -> SamlServiceProvider.checkCertificate(cert)?.let { problems += it } }
        val known = security.roles.list().mapTo(HashSet()) { it.name }
        (draft.defaultRoles + draft.roleMappings.flatMap { it.roles }).filter { it !in known }.distinct().forEach { problems += "role '$it' does not exist" }
        if (problems.isNotEmpty()) throw Problems.unprocessable("identity provider is not valid", problems.distinct())
        if (newSecret != null) security.secrets.putText(secretName, newSecret, "identity provider $name client secret")
        val saved = try {
            security.identityProviders.save(name, spec, actor(), host())
        } catch (e: Exception) {
            if (newSecret != null && keepRef.isBlank()) runCatching { security.secrets.delete(secretName) }
            throw e
        }
        if (secretGiven && newSecret == null && keepRef.isNotBlank()) runCatching { security.secrets.delete(SecretReferences.parse(keepRef)) }
        login.oidc.forget(saved.jwksUri)
        current?.let { login.oidc.forget(it.jwksUri) }
        // Disabling a provider ends the sessions it signed in.
        if (!saved.enabled) gw.httpAuth.deleteProviderSessions(name)
        return saved
    }

    private fun urlFields(p: IdentityProvider): List<Triple<String, String, Boolean>> = when (p.type) {
        IdentityProvider.OIDC -> listOf(
            Triple("issuer", p.issuer, true),
            Triple("authorizationEndpoint", p.authorizationEndpoint, true),
            Triple("tokenEndpoint", p.tokenEndpoint, true),
            Triple("jwksUri", p.jwksUri, true),
            Triple("endSessionEndpoint", p.endSessionEndpoint, false),
            Triple("redirectBaseUrl", p.redirectBaseUrl, false),
        )
        else -> listOf(
            Triple("ssoUrl", p.ssoUrl, true),
            Triple("sloUrl", p.sloUrl, false),
            Triple("redirectBaseUrl", p.redirectBaseUrl, false),
        )
    }

    private fun send(ctx: Context, status: Int, p: IdentityProvider) {
        ctx.header("ETag", "\"${p.version}\"")
        json(ctx, status, view(p))
    }

    /** The resource as the API shows it: no secret, no secret reference, plus where the provider sends users back. */
    private fun view(p: IdentityProvider): Map<String, Any?> {
        val spec = security.identityProviders.spec(p.name).orEmpty().filterKeys { it !in HIDDEN }
        val out = linkedMapOf<String, Any?>("name" to p.name)
        out.putAll(spec)
        out["clientSecretSet"] = p.clientSecretRef.isNotBlank()
        out["callbackPath"] = if (p.type == IdentityProvider.SAML) "${IdpLogin.PROVIDERS}/${p.name}/acs" else "${IdpLogin.PROVIDERS}/${p.name}/callback"
        out["version"] = p.version
        return out
    }

    private fun secretName(provider: String): String {
        val candidate = "idp-$provider"
        if (candidate.length <= 63) return candidate
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(provider.toByteArray())).take(8)
        return "idp-${provider.take(50).trimEnd('-')}-$hash"
    }

    private fun notFound(name: String) = Problems.notFound("IdentityProvider '$name' not found")

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
        const val BASE = "/api/v1/security/identity-providers"
        private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        /** Never shown: the secret reference (and a secret, should a file carry one). */
        private val HIDDEN = setOf("clientSecretRef", "clientSecret")
        private val READ_ONLY = setOf("clientSecretSet", "callbackPath", "version")
    }
}
