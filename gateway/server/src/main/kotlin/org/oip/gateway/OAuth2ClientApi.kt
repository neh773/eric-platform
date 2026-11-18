package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.alarm.notify.NOTIFY_MODULE
import org.oip.alarm.notify.NotifyKinds
import org.oip.config.Scope
import org.oip.security.AuditActions
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import org.oip.security.OAuth2Client
import org.oip.security.OAuth2Exception
import org.oip.security.OAuth2Token
import org.oip.security.OAuth2TokenService
import org.oip.security.SecretReferences
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth2 client registrations (SEC-029) under `/api/v1/security/oauth2-clients`:
 *
 * - `GET` (gateway.read): the registrations, each with `clientSecretSet`, `refreshTokenSet`, the `redirectUri` to give
 *   the identity provider, the SMTP profiles that use it, and when its cached token expires. Never a secret or token.
 * - `PUT /{name}` (gateway.write): create or replace. `clientSecret` and `refreshToken` are plain text that goes to the
 *   secret store (`oauth2-<name>-client-secret`, `-refresh-token`); `clientSecretRef` names a secret already in the
 *   store instead. A missing secret field keeps the stored one and an empty string clears it.
 * - `DELETE /{name}` (gateway.write): refused with 409 while an SMTP profile names it; removes its own secrets.
 * - `POST /{name}/verify-token` (gateway.write): one token request with `grantType` (`authorization_code` with
 *   `code` and `redirectUri`, `client_credentials`, or `refresh_token` with `refreshToken` or the stored one), `scope`
 *   and `parameters`. The answer reports the token type, lifetime, scope and whether a refresh token came back, never
 *   a token.
 * - `POST /{name}/authorize` (gateway.write): starts the authorization code flow (with PKCE) and answers the
 *   `authorizationUrl` the browser opens. Needs `authorizationUrl` on the registration.
 * - `GET /{name}/authorize/callback` (public, checked by its single-use `state`): the identity provider's redirect.
 *   The code is exchanged at once; a refresh token in the answer is stored, so the registration then gets tokens with
 *   nobody present. The page says what happened and links back to the admin page.
 * Every change is one audit record (`oauth2 client saved`, `deleted`, `authorized`).
 * Doc source: FEATURES SEC-029.
 */
class OAuth2ClientApi(private val gw: Gateway) {
    private val service: OAuth2TokenService get() = gw.oauth2Clients
    private val pending = PendingAuthorizations(gw.clock)

    fun register(app: Javalin) {
        app.get(BASE) { ctx -> ProjectsApi.json(ctx, 200, mapOf("items" to service.list().map { view(ctx, it.first, it.second) })) }
        app.get("$BASE/{name}") { ctx ->
            val name = name(ctx)
            val stored = gw.store.get(OAuth2Client.id(name)) ?: throw Problems.notFound("no OAuth2 client '$name'")
            ProjectsApi.json(ctx, 200, view(ctx, OAuth2Client.fromSpec(name, stored.spec), stored.metadata.version))
        }
        app.put("$BASE/{name}") { ctx -> put(ctx) }
        app.delete("$BASE/{name}") { ctx -> delete(ctx) }
        app.post("$BASE/{name}/verify-token") { ctx -> verify(ctx) }
        app.post("$BASE/{name}/authorize") { ctx -> authorize(ctx) }
        app.get("$BASE/{name}/authorize/callback") { ctx -> callback(ctx) }
    }

    private fun name(ctx: Context): String {
        val name = ctx.pathParam("name")
        if (!OAuth2Client.validName(name)) {
            throw Problems.unprocessable("'$name' is not a client name: lowercase letters, digits and '-', 1 to ${OAuth2Client.MAX_NAME} characters")
        }
        return name
    }

    private fun existing(ctx: Context): OAuth2Client {
        val name = name(ctx)
        return service.client(name) ?: throw Problems.notFound("no OAuth2 client '$name'")
    }

    private fun usedBy(name: String): List<String> =
        gw.store.list(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.SMTP)
            .filter { it.resource.spec["oauthClient"] == name }
            .map { it.id.name }
            .sorted()

    private fun view(ctx: Context, client: OAuth2Client, version: Long): Map<String, Any?> = linkedMapOf(
        "name" to client.name,
        "description" to client.description,
        "enabled" to client.enabled,
        "clientId" to client.clientId,
        "clientSecretSet" to client.clientSecretRef.isNotEmpty(),
        // A secret this API stored is its own; another name is a reference the admin chose.
        "clientSecretRef" to client.clientSecretRef.takeIf {
            it.isNotEmpty() && it != SecretReferences.format(OAuth2Client.secretName(client.name, "client-secret"))
        },
        "authorizationUrl" to client.authorizationUrl,
        "tokenUrl" to client.tokenUrl,
        "scope" to client.scope,
        "grantType" to client.grantType,
        "refreshTokenSet" to client.refreshTokenRef.isNotEmpty(),
        "redirectBaseUrl" to client.redirectBaseUrl,
        "redirectUri" to redirectUri(ctx, client),
        "httpVersion" to client.httpVersion,
        "connectTimeoutMillis" to client.connectTimeoutMillis,
        "requestTimeoutMillis" to client.requestTimeoutMillis,
        "usedBy" to usedBy(client.name),
        "tokenCachedUntil" to service.cachedUntil(client.name)?.let { Instant.ofEpochMilli(it).toString() },
        "problems" to client.problems(),
        "version" to version,
    )

    /** Where the identity provider sends the browser back: `redirectBaseUrl`, or the address this request used. */
    private fun redirectUri(ctx: Context, client: OAuth2Client): String? {
        val base = client.redirectBaseUrl.trimEnd('/').ifEmpty {
            val host = ctx.header("Host")?.takeIf { HOST.matches(it) } ?: return null
            (if (ctx.scheme() == "https") "https" else "http") + "://" + host
        }
        return "$base$BASE/${client.name}/authorize/callback"
    }

    private fun put(ctx: Context) {
        val name = name(ctx)
        val body = ProjectsApi.body(ctx)
        val unknown = body.keys - FIELDS - SECRET_FIELDS
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown fields: ${unknown.sorted().joinToString()}")
        val stored = gw.store.get(OAuth2Client.id(name))
        val before = stored?.let { OAuth2Client.fromSpec(name, it.spec) } ?: OAuth2Client(name)
        fun text(key: String, current: String, max: Int = 1000): String = when (val v = body[key]) {
            null -> if (body.containsKey(key)) "" else current
            is String -> v.trim().also { if (it.length > max) throw Problems.unprocessable("$key is too long") }
            else -> throw Problems.unprocessable("$key must be a string")
        }
        fun millis(key: String, current: Long): Long = when (val v = body[key]) {
            null -> current
            is Number -> v.toLong().also { if (it > 600_000) throw Problems.unprocessable("$key is at most 600000") }.coerceAtLeast(-1)
            else -> throw Problems.unprocessable("$key must be a number of milliseconds")
        }
        var client = before.copy(
            description = text("description", before.description, 500),
            enabled = when (val v = body["enabled"]) {
                null -> before.enabled
                is Boolean -> v
                else -> throw Problems.unprocessable("enabled must be a boolean")
            },
            clientId = text("clientId", before.clientId, 500),
            authorizationUrl = text("authorizationUrl", before.authorizationUrl),
            tokenUrl = text("tokenUrl", before.tokenUrl),
            scope = text("scope", before.scope),
            grantType = text("grantType", before.grantType, 40),
            redirectBaseUrl = text("redirectBaseUrl", before.redirectBaseUrl, 500),
            httpVersion = text("httpVersion", before.httpVersion, 20),
            connectTimeoutMillis = millis("connectTimeoutMillis", before.connectTimeoutMillis),
            requestTimeoutMillis = millis("requestTimeoutMillis", before.requestTimeoutMillis),
        )
        // The registration is checked before any secret is written, so a refused request stores nothing.
        val problems = client.problems().filter { !it.startsWith("clientId") && !it.startsWith("tokenUrl is required") }
        if (problems.isNotEmpty()) throw Problems.unprocessable(problems.joinToString("; "))
        if (body.containsKey("clientSecretRef")) {
            when (val v = body["clientSecretRef"]) {
                null, "" -> client = client.copy(clientSecretRef = "")
                is String -> {
                    val secret = try {
                        SecretReferences.parse(v)
                    } catch (_: RuntimeException) {
                        throw Problems.unprocessable("clientSecretRef must be a secret:// reference")
                    }
                    if (gw.security.secrets.list().none { it.name == secret }) throw Problems.unprocessable("clientSecretRef names no stored secret")
                    client = client.copy(clientSecretRef = v)
                }
                else -> throw Problems.unprocessable("clientSecretRef must be a string")
            }
        }
        if (body.containsKey("clientSecret") && body.containsKey("clientSecretRef")) {
            throw Problems.unprocessable("give clientSecret or clientSecretRef, not both")
        }
        val written = ArrayList<String>()
        fun secret(field: String, suffix: String, current: String): String {
            if (!body.containsKey(field)) return current
            return when (val v = body[field]) {
                null, "" -> ""
                is String -> {
                    if (v.length > 8192) throw Problems.unprocessable("$field is too long")
                    val secretName = OAuth2Client.secretName(name, suffix)
                    try {
                        gw.security.secrets.putText(secretName, v, "oauth2 client $name").also { written += secretName }
                    } catch (_: Exception) {
                        throw Problems.unprocessable("could not store $field")
                    }
                }
                else -> throw Problems.unprocessable("$field must be a string")
            }
        }
        client = client.copy(
            clientSecretRef = secret("clientSecret", "client-secret", client.clientSecretRef),
            refreshTokenRef = secret("refreshToken", "refresh-token", client.refreshTokenRef),
        )
        val expected = ctx.header("If-Match")?.trim()?.removePrefix("W/")?.trim('"')?.let {
            it.toLongOrNull() ?: throw Problems.badRequest("If-Match must be a version")
        }
        val version = service.save(client, expected ?: stored?.metadata?.version, ProjectsApi.actor())
        // A secret this API stored that the registration no longer names is removed.
        for (suffix in listOf("client-secret", "refresh-token")) {
            val own = SecretReferences.format(OAuth2Client.secretName(name, suffix))
            if (client.clientSecretRef != own && client.refreshTokenRef != own) runCatching { gw.security.secrets.delete(OAuth2Client.secretName(name, suffix)) }
        }
        audit(AuditActions.OAUTH2_CLIENT_SAVED, name, "grantType=${client.grantType}")
        ProjectsApi.json(ctx, if (stored == null) 201 else 200, view(ctx, client, version))
    }

    private fun delete(ctx: Context) {
        val client = existing(ctx)
        val users = usedBy(client.name)
        if (users.isNotEmpty()) throw Problems.conflict("OAuth2 client '${client.name}' is used by the email profiles ${users.joinToString(", ")}")
        service.delete(client.name)
        for (suffix in listOf("client-secret", "refresh-token")) runCatching { gw.security.secrets.delete(OAuth2Client.secretName(client.name, suffix)) }
        audit(AuditActions.OAUTH2_CLIENT_DELETED, client.name, "")
        ctx.status(204)
    }

    private fun verify(ctx: Context) {
        val client = existing(ctx)
        val body = ProjectsApi.body(ctx)
        val unknown = body.keys - setOf("grantType", "scope", "code", "redirectUri", "refreshToken", "parameters")
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown fields: ${unknown.sorted().joinToString()}")
        val grant = body["grantType"] as? String ?: client.grantType
        if (grant !in OAuth2Client.VERIFY_GRANTS) throw Problems.unprocessable("grantType must be one of ${OAuth2Client.VERIFY_GRANTS.joinToString()}")
        val scope = (body["scope"] as? String)?.trim() ?: client.scope
        val params = LinkedHashMap(parameters(body["parameters"]))
        when (grant) {
            OAuth2Client.AUTHORIZATION_CODE -> {
                params["code"] = body["code"] as? String ?: throw Problems.unprocessable("code is required for authorization_code")
                params["redirect_uri"] = body["redirectUri"] as? String ?: redirectUri(ctx, client)
                    ?: throw Problems.unprocessable("redirectUri is required")
            }
            OAuth2Client.REFRESH_TOKEN -> {
                params["refresh_token"] = (body["refreshToken"] as? String)?.takeIf { it.isNotEmpty() }
                    ?: client.refreshTokenRef.takeIf { it.isNotEmpty() }?.let { readSecret(it) }
                    ?: throw Problems.unprocessable("refreshToken is required: enter one or run the authorization")
            }
        }
        val token = request(client, grant, params, scope)
        ProjectsApi.json(ctx, 200, tokenView(grant, token))
    }

    private fun authorize(ctx: Context) {
        val client = existing(ctx)
        if (client.authorizationUrl.isEmpty()) throw Problems.unprocessable("OAuth2 client '${client.name}' has no authorizationUrl")
        client.problems().firstOrNull()?.let { throw Problems.unprocessable(it) }
        val body = ProjectsApi.body(ctx)
        val unknown = body.keys - setOf("scope", "parameters")
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown fields: ${unknown.sorted().joinToString()}")
        val scope = (body["scope"] as? String)?.trim() ?: client.scope
        val redirect = redirectUri(ctx, client) ?: throw Problems.badRequest("no usable Host header; set redirectBaseUrl")
        val started = pending.start(client.name, redirect, GatewayAuth.subject(ctx).first.principal)
        val url = OAuth2TokenService.authorizationRequest(
            client, redirect, started.state, OAuth2TokenService.challenge(started.verifier), scope, parameters(body["parameters"]),
        )
        ProjectsApi.json(ctx, 200, mapOf("authorizationUrl" to url, "redirectUri" to redirect, "expiresInSeconds" to PendingAuthorizations.TTL_SECONDS))
    }

    private fun callback(ctx: Context) {
        val name = name(ctx)
        val state = ctx.queryParam("state").orEmpty()
        val flow = pending.take(state, name)
        if (flow == null) {
            page(ctx, 400, "This authorization link is not valid or has expired. Start the authorization again from the OAuth2 clients page.")
            return
        }
        val error = ctx.queryParam("error")
        if (error != null) {
            val code = error.takeIf { Regex("^[a-z_]{1,64}$").matches(it) } ?: "error"
            page(ctx, 400, "The identity provider did not authorize OAuth2 client $name ($code).")
            return
        }
        val code = ctx.queryParam("code")?.takeIf { it.isNotEmpty() && it.length <= 4096 }
        if (code == null) {
            page(ctx, 400, "The identity provider sent no authorization code.")
            return
        }
        val client = service.client(name) ?: run {
            page(ctx, 404, "OAuth2 client $name no longer exists.")
            return
        }
        val token = try {
            service.request(client, OAuth2Client.AUTHORIZATION_CODE, mapOf("code" to code, "redirect_uri" to flow.redirectUri, "code_verifier" to flow.verifier), client.scope)
        } catch (e: OAuth2Exception) {
            log("oauth2 authorization failed", name, e.message.orEmpty())
            page(ctx, 502, e.message ?: "The token request failed.")
            return
        }
        val refresh = token.refreshToken
        if (refresh != null) service.keepRefreshToken(client, refresh, flow.user)
        audit(AuditActions.OAUTH2_CLIENT_AUTHORIZED, name, "refreshToken=${refresh != null}", actor = flow.user)
        page(
            ctx, 200,
            if (refresh != null) "OAuth2 client $name is authorized. Its refresh token is stored, so it can get tokens with nobody present."
            else "OAuth2 client $name received an access token but no refresh token, so it cannot get tokens later on its own. " +
                "Check the provider's parameters for offline access and authorize again.",
        )
    }

    private fun request(client: OAuth2Client, grant: String, params: Map<String, String>, scope: String): OAuth2Token = try {
        service.request(client, grant, params, scope)
    } catch (e: OAuth2Exception) {
        log("oauth2 token request failed", client.name, e.message.orEmpty())
        throw ProblemException(502, "oauth2-token-failed", "Bad Gateway", e.message ?: "the token request failed")
    }

    private fun readSecret(ref: String): String = try {
        gw.security.secrets.getReference(ref).toString(Charsets.UTF_8)
    } catch (_: RuntimeException) {
        throw Problems.unprocessable("the stored refresh token could not be read")
    }

    private fun parameters(raw: Any?): Map<String, String> {
        if (raw == null) return emptyMap()
        val map = raw as? Map<*, *> ?: throw Problems.unprocessable("parameters must be an object of strings")
        if (map.size > 20) throw Problems.unprocessable("at most 20 parameters")
        return map.entries.associate { (k, v) ->
            val key = k as? String ?: throw Problems.unprocessable("parameters must be an object of strings")
            val value = v as? String ?: throw Problems.unprocessable("parameter $key must be a string")
            if (!PARAM.matches(key) || value.length > 1000) throw Problems.unprocessable("parameter $key is not usable")
            if (key in RESERVED) throw Problems.unprocessable("parameter $key is set by the gateway")
            key to value
        }
    }

    private fun tokenView(grant: String, token: OAuth2Token): Map<String, Any?> = linkedMapOf(
        "ok" to true,
        "grantType" to grant,
        "tokenType" to token.tokenType,
        "expiresInSeconds" to token.expiresInSeconds,
        "scope" to token.scope,
        "refreshTokenReceived" to (token.refreshToken != null),
        "receivedAt" to Instant.ofEpochMilli(token.receivedAtMillis).toString(),
        "expiresAt" to Instant.ofEpochMilli(token.expiresAtMillis).toString(),
    )

    private fun page(ctx: Context, status: Int, message: String) {
        val text = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        ctx.status(status).contentType("text/html; charset=utf-8").header("Cache-Control", "no-store").result(
            "<!doctype html><html><head><meta charset=\"utf-8\"><title>OAuth2 authorization</title></head>" +
                "<body><p>$text</p><p><a href=\"/admin/oauth2-clients\">Back to OAuth2 clients</a></p></body></html>",
        )
    }

    private fun log(message: String, client: String, detail: String) {
        gw.logs.log(LogLevel.WARN, "org.oip.oauth2", message, mapOf("client" to client, "detail" to detail.take(300)))
    }

    private fun audit(action: String, name: String, detail: String, actor: String? = null) {
        if (!gw.security.auditProfile.get().enabled) return
        val context = AuditContext.current()
        gw.security.audit.append(
            AuditDraft(
                timestampMillis = gw.clock.millis(),
                actor = actor ?: context?.actor ?: "api",
                action = action,
                status = AuditStatus.SUCCESS,
                actorHost = context?.host ?: "",
                target = "oauth2-client/$name",
                detail = detail.take(500),
                context = "gateway-http",
            ),
        )
    }

    companion object {
        const val BASE = "/api/v1/security/oauth2-clients"
        val CALLBACK = Regex("^/api/v1/security/oauth2-clients/[^/]+/authorize/callback$")
        private val FIELDS = setOf(
            "description", "enabled", "clientId", "authorizationUrl", "tokenUrl", "scope", "grantType", "redirectBaseUrl",
            "httpVersion", "connectTimeoutMillis", "requestTimeoutMillis", "clientSecretRef",
        )
        private val SECRET_FIELDS = setOf("clientSecret", "refreshToken")
        private val HOST = Regex("^[A-Za-z0-9.\\-\\[\\]:]{1,255}$")
        private val PARAM = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val RESERVED = setOf(
            "grant_type", "client_id", "client_secret", "code", "redirect_uri", "refresh_token", "scope", "state",
            "code_verifier", "code_challenge", "code_challenge_method", "response_type",
        )
    }
}

/** Authorization code flows started and not yet finished: single-use `state` values that expire after [TTL_SECONDS]. */
class PendingAuthorizations(private val clock: Clock) {
    data class Flow(val state: String, val client: String, val verifier: String, val redirectUri: String, val user: String, val startedAt: Long)

    private val flows = ConcurrentHashMap<String, Flow>()

    fun start(client: String, redirectUri: String, user: String): Flow {
        prune()
        if (flows.size >= MAX) flows.values.minByOrNull { it.startedAt }?.let { flows.remove(it.state) }
        val flow = Flow(OAuth2TokenService.randomToken(), client, OAuth2TokenService.randomToken(48), redirectUri, user, clock.millis())
        flows[flow.state] = flow
        return flow
    }

    /** The flow [state] names for [client], removed so it cannot be used twice; null when unknown or expired. */
    fun take(state: String, client: String): Flow? {
        if (state.isEmpty()) return null
        val flow = flows.remove(state) ?: return null
        if (flow.client != client || clock.millis() - flow.startedAt > TTL_SECONDS * 1000) return null
        return flow
    }

    private fun prune() {
        val now = clock.millis()
        flows.values.removeIf { now - it.startedAt > TTL_SECONDS * 1000 }
    }

    companion object {
        const val TTL_SECONDS = 600L
        const val MAX = 100
    }
}
