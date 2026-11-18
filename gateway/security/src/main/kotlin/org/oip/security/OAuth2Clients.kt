package org.oip.security

import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth2 client registrations (SEC-029): how the gateway's outbound services (the SMTP sender first) obtain access
 * tokens from an authorization server. A registration is the resource `org.oip.security/gateway/OAuth2Client/<name>`
 * and is written only through `/api/v1/security/oauth2-clients`; its client secret and refresh token live in the
 * gateway secret store and the resource holds only their `secret://` references.
 *
 * Fields: `description`, `enabled`, `clientId`, `clientSecret` (reference), `authorizationUrl` (needed for the
 * authorization code flow), `tokenUrl`, `scope` (space-separated, the default for a token request), `grantType`
 * (`client_credentials`, or `refresh_token` with a refresh token from the authorization code flow or entered),
 * `refreshToken` (reference), `redirectBaseUrl`, `httpVersion` (`HTTP_2` or `HTTP_1_1`), `connectTimeoutMillis` and
 * `requestTimeoutMillis` (zero or less: no timeout).
 * Doc source: FEATURES SEC-029.
 */
data class OAuth2Client(
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val clientId: String = "",
    val clientSecretRef: String = "",
    val authorizationUrl: String = "",
    val tokenUrl: String = "",
    val scope: String = "",
    val grantType: String = CLIENT_CREDENTIALS,
    val refreshTokenRef: String = "",
    val redirectBaseUrl: String = "",
    val httpVersion: String = HTTP_2,
    val connectTimeoutMillis: Long = 30_000,
    val requestTimeoutMillis: Long = 120_000,
) {
    fun toSpec(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "description" to description,
        "enabled" to enabled,
        "clientId" to clientId,
        "authorizationUrl" to authorizationUrl,
        "tokenUrl" to tokenUrl,
        "scope" to scope,
        "grantType" to grantType,
        "redirectBaseUrl" to redirectBaseUrl,
        "httpVersion" to httpVersion,
        "connectTimeoutMillis" to connectTimeoutMillis,
        "requestTimeoutMillis" to requestTimeoutMillis,
    ).also {
        if (clientSecretRef.isNotEmpty()) it["clientSecret"] = clientSecretRef
        if (refreshTokenRef.isNotEmpty()) it["refreshToken"] = refreshTokenRef
    }

    /** Problems that keep this registration from fetching a token, or empty when it can. */
    fun problems(): List<String> = buildList {
        if (clientId.isBlank()) add("clientId is required")
        endpointProblem("tokenUrl", tokenUrl, required = true)?.let(::add)
        endpointProblem("authorizationUrl", authorizationUrl, required = false)?.let(::add)
        if (redirectBaseUrl.isNotEmpty()) endpointProblem("redirectBaseUrl", redirectBaseUrl, required = false)?.let(::add)
        if (grantType !in RUNTIME_GRANTS) add("grantType must be one of ${RUNTIME_GRANTS.joinToString()}")
        if (httpVersion !in setOf(HTTP_2, HTTP_1_1)) add("httpVersion must be $HTTP_2 or $HTTP_1_1")
        if (scope.any { it.code < 0x20 || it.code == 0x7F }) add("scope must be printable text")
    }

    companion object {
        const val KIND = "OAuth2Client"
        const val CLIENT_CREDENTIALS = "client_credentials"
        const val REFRESH_TOKEN = "refresh_token"
        const val AUTHORIZATION_CODE = "authorization_code"
        const val HTTP_2 = "HTTP_2"
        const val HTTP_1_1 = "HTTP_1_1"
        /** Grants a registration may use to obtain its tokens unattended. */
        val RUNTIME_GRANTS = listOf(CLIENT_CREDENTIALS, REFRESH_TOKEN)
        /** Grants Verify Token may try. */
        val VERIFY_GRANTS = listOf(AUTHORIZATION_CODE, CLIENT_CREDENTIALS, REFRESH_TOKEN)
        /** Registration names are kept short, so the secret names derived from them stay valid secret names. */
        const val MAX_NAME = 40

        fun id(name: String) = ResourceId(SECURITY_MODULE, Scope.GATEWAY, KIND, name)

        fun validName(name: String) = name.length <= MAX_NAME && NAME.matches(name)

        private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        fun secretName(name: String, what: String) = "oauth2-$name-$what"

        fun fromSpec(name: String, spec: Map<String, Any?>): OAuth2Client = OAuth2Client(
            name = name,
            description = spec["description"] as? String ?: "",
            enabled = spec["enabled"] as? Boolean ?: true,
            clientId = spec["clientId"] as? String ?: "",
            clientSecretRef = spec["clientSecret"] as? String ?: "",
            authorizationUrl = spec["authorizationUrl"] as? String ?: "",
            tokenUrl = spec["tokenUrl"] as? String ?: "",
            scope = spec["scope"] as? String ?: "",
            grantType = spec["grantType"] as? String ?: CLIENT_CREDENTIALS,
            refreshTokenRef = spec["refreshToken"] as? String ?: "",
            redirectBaseUrl = spec["redirectBaseUrl"] as? String ?: "",
            httpVersion = spec["httpVersion"] as? String ?: HTTP_2,
            connectTimeoutMillis = (spec["connectTimeoutMillis"] as? Number)?.toLong() ?: 30_000,
            requestTimeoutMillis = (spec["requestTimeoutMillis"] as? Number)?.toLong() ?: 120_000,
        )

        /** An https URL, or http to a loopback host so a local server can stand in. */
        fun endpointProblem(field: String, value: String, required: Boolean): String? {
            if (value.isEmpty()) return if (required) "$field is required" else null
            val uri = runCatching { URI(value) }.getOrNull() ?: return "$field is not a URL"
            if (uri.host.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null) return "$field is not a usable URL"
            val loopback = uri.host == "localhost" || uri.host == "127.0.0.1" || uri.host == "[::1]"
            if (uri.scheme != "https" && !(uri.scheme == "http" && loopback)) return "$field must be https"
            return null
        }

        private const val SECRET = "^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"

        fun schema(): Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
                "enabled" to mapOf("type" to "boolean", "default" to true),
                "clientId" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
                "clientSecret" to mapOf("type" to "string", "pattern" to SECRET, "description" to "secret:// reference; the secret is never stored here."),
                "authorizationUrl" to mapOf("type" to "string", "maxLength" to 1000, "default" to ""),
                "tokenUrl" to mapOf("type" to "string", "maxLength" to 1000, "default" to ""),
                "scope" to mapOf("type" to "string", "maxLength" to 1000, "default" to ""),
                "grantType" to mapOf("type" to "string", "enum" to RUNTIME_GRANTS, "default" to CLIENT_CREDENTIALS),
                "refreshToken" to mapOf("type" to "string", "pattern" to SECRET, "description" to "secret:// reference to the refresh token."),
                "redirectBaseUrl" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
                "httpVersion" to mapOf("type" to "string", "enum" to listOf(HTTP_2, HTTP_1_1), "default" to HTTP_2),
                "connectTimeoutMillis" to mapOf("type" to "integer", "minimum" to -1, "maximum" to 600_000, "default" to 30_000),
                "requestTimeoutMillis" to mapOf("type" to "integer", "minimum" to -1, "maximum" to 600_000, "default" to 120_000),
            ),
        )
    }
}

/** A token request failed. The message names the registration and the step, never a secret or a response body. */
class OAuth2Exception(message: String) : RuntimeException(message)

/** One POST to a token endpoint: the status and body of the answer. */
fun interface OAuth2Transport {
    fun post(client: OAuth2Client, uri: URI, form: String): Pair<Int, String>
}

/**
 * The token endpoint answer, as the gateway keeps it. [accessToken] and [refreshToken] are secrets: they go to the
 * outbound service that asked, never to a log, an API response or an error.
 */
data class OAuth2Token(
    val accessToken: String,
    val tokenType: String,
    val expiresAtMillis: Long,
    val expiresInSeconds: Long,
    val scope: String,
    val refreshToken: String?,
    val receivedAtMillis: Long,
)

/**
 * Token fetch, cache and refresh for [OAuth2Client] registrations (SEC-029). [accessToken] answers from the cache
 * until 30 seconds before the token expires, then refreshes it: with the refresh token when the registration has one
 * (or the last answer gave one), otherwise with the client-credentials grant. A rotated refresh token replaces the
 * stored one in the secret store. Tokens are cached per registration and scope, and a change to the registration
 * (its resource version) drops its cached tokens. Requests use the gateway's outbound TLS trust (SEC-022) through
 * [transport].
 * Doc source: FEATURES SEC-029.
 */
class OAuth2TokenService(
    private val store: FileConfigStore,
    private val secrets: SecretStore,
    private val clock: Clock,
    private val transport: OAuth2Transport = HttpOAuth2Transport(),
) {
    private data class Cached(val version: Long, val token: OAuth2Token)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun client(name: String): OAuth2Client? {
        if (!OAuth2Client.validName(name)) return null
        val stored = store.get(OAuth2Client.id(name)) ?: return null
        return OAuth2Client.fromSpec(name, stored.spec)
    }

    fun list(): List<Pair<OAuth2Client, Long>> =
        store.list(SECURITY_MODULE, Scope.GATEWAY, OAuth2Client.KIND)
            .map { OAuth2Client.fromSpec(it.id.name, it.resource.spec) to it.resource.metadata.version }
            .sortedBy { it.first.name }

    fun save(client: OAuth2Client, expectedVersion: Long?, actor: String): Long {
        val version = expectedVersion ?: store.get(OAuth2Client.id(client.name))?.metadata?.version ?: 0L
        val stored = store.put(Scope.GATEWAY, Resource(OAuth2Client.KIND, Metadata(client.name, SECURITY_MODULE, version = version), client.toSpec()), actor)
        forget(client.name)
        return stored.metadata.version
    }

    fun delete(name: String) {
        store.delete(OAuth2Client.id(name))
        forget(name)
    }

    fun forget(name: String) {
        cache.keys.removeIf { it.startsWith("$name|") }
    }

    /** When the cached token of [name] for [scope] expires, or null when none is cached. */
    fun cachedUntil(name: String, scope: String? = null): Long? = cache[key(name, scope)]?.token?.expiresAtMillis

    /** An access token for [name] (with [scope], or the registration's default scope), from the cache or fetched now. */
    fun accessToken(name: String, scope: String? = null): String {
        val stored = store.get(OAuth2Client.id(name)) ?: throw OAuth2Exception("oauth2 client '$name' does not exist")
        val client = OAuth2Client.fromSpec(name, stored.spec)
        if (!client.enabled) throw OAuth2Exception("oauth2 client '$name' is disabled")
        val version = stored.metadata.version
        val key = key(name, scope)
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            val cached = cache[key]?.takeIf { it.version == version }
            if (cached != null && cached.token.expiresAtMillis > clock.millis() + EARLY_MILLIS) return cached.token.accessToken
            val requested = scope?.takeIf { it.isNotBlank() } ?: client.scope
            val refresh = cached?.token?.refreshToken ?: client.refreshTokenRef.takeIf { it.isNotEmpty() }?.let { read(client, it, "refresh token") }
            val token = when {
                refresh != null -> request(client, OAuth2Client.REFRESH_TOKEN, mapOf("refresh_token" to refresh), requested)
                client.grantType == OAuth2Client.REFRESH_TOKEN ->
                    throw OAuth2Exception("oauth2 client '$name' has no refresh token: run its authorization or enter one")
                else -> request(client, OAuth2Client.CLIENT_CREDENTIALS, emptyMap(), requested)
            }
            val kept = if (token.refreshToken != null && token.refreshToken != refresh) keepRefreshToken(client, token.refreshToken) else version
            cache[key] = Cached(kept, token)
            return token.accessToken
        }
    }

    /**
     * Ask the token endpoint for a token with [grant] and [parameters] (Verify Token, and the authorization code
     * exchange). Nothing is cached; the caller decides what to keep.
     */
    fun request(client: OAuth2Client, grant: String, parameters: Map<String, String>, scope: String): OAuth2Token {
        client.problems().firstOrNull()?.let { throw OAuth2Exception("oauth2 client '${client.name}': $it") }
        val uri = URI(client.tokenUrl)
        val form = linkedMapOf("grant_type" to grant)
        form.putAll(parameters)
        if (scope.isNotBlank() && grant != OAuth2Client.AUTHORIZATION_CODE) form["scope"] = scope
        form["client_id"] = client.clientId
        if (client.clientSecretRef.isNotEmpty()) form["client_secret"] = read(client, client.clientSecretRef, "client secret")
        val body = form.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8) }
        val (status, text) = try {
            transport.post(client, uri, body)
        } catch (e: java.io.IOException) {
            throw OAuth2Exception("oauth2 token request for client '${client.name}' failed: ${e.javaClass.simpleName}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OAuth2Exception("oauth2 token request for client '${client.name}' was interrupted")
        }
        val parsed = runCatching {
            @Suppress("UNCHECKED_CAST")
            MAPPER.readValue(text, Map::class.java) as Map<String, Any?>
        }.getOrNull()
        if (status !in 200..299) {
            // The standard error code is safe to show; the description and the rest of the body are not repeated.
            val code = (parsed?.get("error") as? String)?.takeIf { ERROR_CODE.matches(it) }
            throw OAuth2Exception("oauth2 token request for client '${client.name}' was refused (HTTP $status${code?.let { ", $it" } ?: ""})")
        }
        parsed ?: throw OAuth2Exception("oauth2 token response for client '${client.name}' was not JSON")
        val token = parsed["access_token"] as? String
        if (token.isNullOrEmpty() || token.any { it.code < 0x21 || it.code > 0x7E }) {
            throw OAuth2Exception("oauth2 token response for client '${client.name}' had no usable access_token")
        }
        val seconds = (parsed["expires_in"] as? Number)?.toLong()?.coerceIn(1L, 31_536_000L)
            ?: (parsed["expires_in"] as? String)?.toLongOrNull()?.coerceIn(1L, 31_536_000L) ?: DEFAULT_EXPIRY_SECONDS
        val now = clock.millis()
        return OAuth2Token(
            accessToken = token,
            tokenType = (parsed["token_type"] as? String)?.take(40) ?: "",
            expiresAtMillis = now + seconds * 1000,
            expiresInSeconds = seconds,
            scope = (parsed["scope"] as? String)?.take(1000) ?: scope,
            refreshToken = (parsed["refresh_token"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 8192 },
            receivedAtMillis = now,
        )
    }

    /** Store [refreshToken] as [client]'s refresh token and point the registration at it. Returns the new version. */
    fun keepRefreshToken(client: OAuth2Client, refreshToken: String, actor: String = "oauth2"): Long {
        val secret = OAuth2Client.secretName(client.name, "refresh-token")
        val ref = secrets.putText(secret, refreshToken, "oauth2 client ${client.name} refresh token")
        val stored = store.get(OAuth2Client.id(client.name))
        val current = stored?.let { OAuth2Client.fromSpec(client.name, it.spec) } ?: return 0
        if (current.refreshTokenRef == ref) return stored.metadata.version
        forget(client.name)
        return store.put(
            Scope.GATEWAY,
            Resource(OAuth2Client.KIND, Metadata(client.name, SECURITY_MODULE, version = stored.metadata.version), current.copy(refreshTokenRef = ref).toSpec()),
            actor,
        ).metadata.version
    }

    private fun read(client: OAuth2Client, ref: String, what: String): String = try {
        secrets.getReference(ref).toString(Charsets.UTF_8)
    } catch (_: RuntimeException) {
        throw OAuth2Exception("oauth2 $what for client '${client.name}' could not be read")
    }

    private fun key(name: String, scope: String?) = "$name|${scope.orEmpty()}"

    companion object {
        const val EARLY_MILLIS = 30_000L
        const val DEFAULT_EXPIRY_SECONDS = 300L
        private val ERROR_CODE = Regex("^[a-z_]{1,64}$")
        private val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
        private val RANDOM = SecureRandom()

        /** A random URL-safe value: an authorization `state` or a PKCE code verifier. */
        fun randomToken(bytes: Int = 32): String {
            val raw = ByteArray(bytes).also(RANDOM::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        }

        /** The PKCE S256 challenge of [verifier]. */
        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

        /** The authorization endpoint URL for the authorization code flow, with PKCE and [extra] parameters. */
        fun authorizationRequest(client: OAuth2Client, redirectUri: String, state: String, challenge: String, scope: String, extra: Map<String, String>): String {
            val params = linkedMapOf(
                "response_type" to "code",
                "client_id" to client.clientId,
                "redirect_uri" to redirectUri,
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
            )
            if (scope.isNotBlank()) params["scope"] = scope
            for ((k, v) in extra) if (k !in params) params[k] = v
            val query = params.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8) }
            return client.authorizationUrl + (if (client.authorizationUrl.contains('?')) "&" else "?") + query
        }
    }
}

/** The JDK HTTP client with the registration's HTTP version and timeouts, over the gateway's outbound TLS trust. */
class HttpOAuth2Transport : OAuth2Transport {
    override fun post(client: OAuth2Client, uri: URI, form: String): Pair<Int, String> {
        val builder = HttpClient.newBuilder()
            .version(if (client.httpVersion == OAuth2Client.HTTP_1_1) HttpClient.Version.HTTP_1_1 else HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslContext(org.oip.config.OutboundTls.context("oauth2-client"))
        if (client.connectTimeoutMillis > 0) builder.connectTimeout(Duration.ofMillis(client.connectTimeoutMillis))
        val request = HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
        if (client.requestTimeoutMillis > 0) request.timeout(Duration.ofMillis(client.requestTimeoutMillis))
        val http = builder.build()
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body().take(MAX_BODY)
    }

    private companion object {
        const val MAX_BODY = 256 * 1024
    }
}
