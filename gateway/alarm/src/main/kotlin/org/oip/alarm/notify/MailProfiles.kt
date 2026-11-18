package org.oip.alarm.notify

import org.oip.config.FileConfigStore
import org.oip.config.Scope
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The one lookup of SMTP email profiles. An `SmtpProfile` config resource is configured once and reused by alarm
 * notification, scheduled reports and gateway scripts: each asks [send] (or [endpoint]) for the profile by name, so
 * the host, the login, the OAuth2 token and the secret references are handled in one place.
 *
 * Secrets (the password, the OAuth2 client secret and refresh token) are `secret://` references resolved by [secrets]
 * at send time. No value from the secret store is logged or returned; failures name the profile and the step only.
 * Doc source: FEATURES GAT-030.
 */
class MailProfiles(
    private val store: FileConfigStore,
    private val secrets: (String) -> String,
    private val clock: Clock = Clock.systemUTC(),
    private val transport: MailTransport = SmtpClient,
    private val tokens: OAuthTokenSource = HttpOAuthTokens(secrets, clock),
    private val log: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    /**
     * SEC-029: an access token from the named OAuth2 client registration, for the profile's scope (empty: the
     * registration's default). Profiles that name `oauthClient` use it; null leaves only the inline settings.
     */
    private val clientTokens: ((client: String, scope: String) -> String)? = null,
) {
    /** The enabled profile named [name], or null when it is missing, disabled or not a valid profile. */
    fun profile(name: String): SmtpProfile? {
        val stored = store.get(org.oip.config.ResourceId(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.SMTP, name)) ?: return null
        val parsed = try {
            NotifyCodec.smtp(name, stored.spec)
        } catch (_: RuntimeException) {
            return null
        }
        return parsed.takeIf { it.enabled }
    }

    /** Names of the enabled profiles, for pickers. */
    fun names(): List<String> =
        store.list(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.SMTP).map { it.id.name }.filter { profile(it) != null }.sorted()

    private fun registeredToken(profile: SmtpProfile): String {
        val source = clientTokens ?: throw SmtpException("oauth2 client registrations are not available for smtp profile '${profile.name}'")
        return try {
            source(profile.oauthClient, profile.oauthScope)
        } catch (e: SmtpException) {
            throw e
        } catch (e: RuntimeException) {
            // The token service's messages name the registration and the step, never a secret.
            throw SmtpException(e.message ?: "oauth2 token for smtp profile '${profile.name}' could not be obtained")
        }
    }

    /** Connection settings for [profile], with the password or OAuth2 token read now. The caller wipes the endpoint. */
    fun endpoint(profile: SmtpProfile, extraDebug: Boolean = false): SmtpEndpoint {
        val oauth = profile.oauth2
        val secret: CharArray = when {
            oauth && profile.oauthClient.isNotEmpty() -> registeredToken(profile).toCharArray()
            oauth -> tokens.accessToken(profile).toCharArray()
            profile.username.isBlank() || profile.passwordRef.isBlank() -> CharArray(0)
            else -> try {
                secrets(profile.passwordRef).toCharArray()
            } catch (_: RuntimeException) {
                throw SmtpException("smtp password could not be read")
            }
        }
        return SmtpEndpoint(
            host = profile.hostname,
            port = profile.port,
            useSsl = profile.useSsl,
            startTls = profile.startTls && !profile.useSsl,
            username = profile.username,
            password = secret,
            timeoutMillis = profile.timeoutMillis,
            protocols = profile.sslProtocols.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            debug = profile.debug || extraDebug,
            xoauth2 = oauth,
        )
    }

    /**
     * Send [mail] through the profile [name]. An empty `from` falls back to the profile's sender, then its username.
     * Throws [SmtpException] when the profile is missing or disabled or the send fails; the message never holds a secret.
     */
    fun send(name: String, mail: OutboundMail) {
        val profile = profile(name) ?: throw SmtpException("smtp profile '$name' is missing or disabled")
        val endpoint = endpoint(profile)
        val secret = String(endpoint.password)
        try {
            val from = mail.from.ifBlank { profile.fromAddress }.ifBlank { profile.username }.ifBlank { "alarms@localhost" }
            val lines = ArrayList<String>()
            try {
                transport.send(endpoint, mail.copy(from = from, smtpProfile = name)) { lines += it }
            } catch (e: RuntimeException) {
                throw SmtpException(scrubSecret(e.message ?: e.javaClass.simpleName, secret))
            }
            for (line in lines) log("smtp", mapOf("profile" to name, "detail" to scrubSecret(line, secret)))
        } finally {
            endpoint.wipe()
        }
    }
}

/** Fetches OAuth2 access tokens for SMTP profiles that use `authType: oauth2`. */
fun interface OAuthTokenSource {
    fun accessToken(profile: SmtpProfile): String
}

/**
 * Token endpoint client: the refresh-token grant when the profile has a refresh token, otherwise the client-credentials
 * grant. A token is cached until 30 seconds before it expires. The endpoint must be https (http is accepted for a
 * loopback host so a local test server can stand in). The response body is never logged.
 */
class HttpOAuthTokens(
    private val secrets: (String) -> String,
    private val clock: Clock,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : OAuthTokenSource {
    private data class Cached(val token: String, val expiresAtMillis: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun accessToken(profile: SmtpProfile): String {
        val key = "${profile.name}|${profile.oauthTokenUrl}|${profile.oauthClientId}|${profile.oauthRefreshTokenRef}"
        cache[key]?.takeIf { it.expiresAtMillis > clock.millis() + 30_000 }?.let { return it.token }
        val fresh = fetch(profile)
        cache[key] = fresh
        return fresh.token
    }

    private fun fetch(profile: SmtpProfile): Cached {
        val uri = tokenUri(profile)
        val form = linkedMapOf<String, String>()
        try {
            val clientSecret = if (profile.oauthClientSecretRef.isBlank()) "" else secrets(profile.oauthClientSecretRef)
            if (profile.oauthRefreshTokenRef.isNotBlank()) {
                form["grant_type"] = "refresh_token"
                form["refresh_token"] = secrets(profile.oauthRefreshTokenRef)
            } else {
                form["grant_type"] = "client_credentials"
            }
            form["client_id"] = profile.oauthClientId
            if (clientSecret.isNotEmpty()) form["client_secret"] = clientSecret
        } catch (_: RuntimeException) {
            throw SmtpException("oauth2 secret for smtp profile '${profile.name}' could not be read")
        }
        if (profile.oauthScope.isNotBlank()) form["scope"] = profile.oauthScope
        val body = form.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, StandardCharsets.UTF_8) }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw SmtpException("oauth2 token request for smtp profile '${profile.name}' failed: ${e.javaClass.simpleName}")
        }
        if (response.statusCode() !in 200..299) {
            throw SmtpException("oauth2 token request for smtp profile '${profile.name}' was refused (HTTP ${response.statusCode()})")
        }
        val parsed = try {
            @Suppress("UNCHECKED_CAST")
            MAPPER.readValue(response.body(), Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            throw SmtpException("oauth2 token response for smtp profile '${profile.name}' was not JSON")
        }
        val token = parsed["access_token"] as? String
        if (token.isNullOrEmpty() || token.any { it.code < 0x21 || it.code > 0x7E }) {
            throw SmtpException("oauth2 token response for smtp profile '${profile.name}' had no usable access_token")
        }
        val seconds = (parsed["expires_in"] as? Number)?.toLong()?.coerceIn(1L, 86_400L) ?: 300L
        return Cached(token, clock.millis() + seconds * 1000)
    }

    private fun tokenUri(profile: SmtpProfile): URI {
        val uri = try {
            URI(profile.oauthTokenUrl)
        } catch (_: Exception) {
            throw SmtpException("oauth2 token url for smtp profile '${profile.name}' is not a URL")
        }
        val loopback = uri.host == "localhost" || uri.host == "127.0.0.1" || uri.host == "[::1]" || uri.host == "::1"
        if (uri.scheme != "https" && !(uri.scheme == "http" && loopback)) {
            throw SmtpException("oauth2 token url for smtp profile '${profile.name}' must be https")
        }
        if (profile.oauthClientId.isBlank()) throw SmtpException("oauth2 client id for smtp profile '${profile.name}' is missing")
        return uri
    }

    private companion object {
        val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
    }
}
