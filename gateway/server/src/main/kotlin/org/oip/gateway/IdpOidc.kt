package org.oip.gateway

import org.oip.security.IdentityProvider
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PublicKey
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Outbound HTTP to identity providers: bounded time and size, no redirects followed. */
class IdpHttp(private val timeout: Duration = Duration.ofSeconds(10)) {
    // The outbound trust store (SEC-022) decides which provider certificates are accepted; it reads the trusted list
    // at each handshake, so one client serves every login.
    private val client by lazy {
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslContext(org.oip.config.OutboundTls.context("identity-provider"))
            .build()
    }

    data class Reply(val status: Int, val body: String)

    fun get(url: String): Reply = send(HttpRequest.newBuilder(URI.create(url)).GET().header("Accept", "application/json, application/xml"))

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): Reply {
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val b = HttpRequest.newBuilder(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return send(b)
    }

    private fun send(b: HttpRequest.Builder): Reply {
        val r = client.send(b.timeout(timeout).build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = r.body().use { it.readNBytes(MAX_BODY + 1) }
        if (bytes.size > MAX_BODY) throw IdpRejected("provider response too large")
        return Reply(r.statusCode(), String(bytes, Charsets.UTF_8))
    }

    companion object {
        const val MAX_BODY = 1024 * 1024
        fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
    }
}

/** Provider endpoint URLs must be HTTPS. Plain HTTP is accepted only for a loopback host (a provider on this machine). */
object IdpUrls {
    fun check(field: String, value: String, required: Boolean): String? {
        if (value.isBlank()) return if (required) "$field is required" else null
        val uri = runCatching { URI(value) }.getOrNull() ?: return "$field is not a URL"
        val host = uri.host ?: return "$field must be an absolute URL"
        if (uri.rawUserInfo != null) return "$field must not carry credentials"
        if (uri.rawFragment != null) return "$field must not have a fragment"
        return when (uri.scheme?.lowercase()) {
            "https" -> null
            "http" -> if (loopback(host)) null else "$field must use https"
            else -> "$field must use https"
        }
    }

    fun loopback(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h.equals("localhost", ignoreCase = true)) return true
        if (!h.matches(Regex("^[0-9.]+$")) && ':' !in h) return false
        return runCatching { InetAddress.getByName(h).isLoopbackAddress }.getOrDefault(false)
    }
}

/** Claims of a validated token, and the identity they map to. */
data class IdpIdentity(
    val provider: String,
    val username: String,
    val roles: Set<String>,
    val subject: String,
    /** The provider's claims or assertion attributes, for the security level rules (SEC-016). */
    val attributes: Map<String, List<String>> = emptyMap(),
    /** The user's display name and email mapped from the provider's attributes (SEC-015); empty when absent or unusable. */
    val displayName: String = "",
    val email: String = "",
) {
    /** Principal for sessions and audit: `provider:username`. Internal user names cannot contain ':'. */
    val principal: String get() = "$provider:$username"
}

/**
 * OpenID Connect relying party (SEC-013): discovery, the authorization-code exchange with PKCE, and ID token and
 * access token validation against the provider's JWK set.
 *
 * Doc source: FEATURES SEC-013.
 * Doc source: FEATURES SEC-014.
 * Protocol: OpenID Connect Core 1.0 §3.1.3.7 (ID token validation), RFC 7636 (PKCE), RFC 8414 / OIDC Discovery 1.0.
 */
class OidcClient(private val clock: Clock, private val http: IdpHttp = IdpHttp()) {
    private class KeySet(val fetchedMillis: Long, val keys: List<Jwk>)
    private val jwks = ConcurrentHashMap<String, KeySet>()

    /**
     * Read `<issuer>/.well-known/openid-configuration` and return the provider fields it fills. The document must be a
     * JSON object for the same issuer, support the code flow and S256 PKCE when it lists them, and name HTTPS endpoints.
     */
    fun discover(issuer: String): Map<String, Any?> {
        IdpUrls.check("issuer", issuer, required = true)?.let { throw IdpMetadataInvalid(it) }
        val url = issuer.trimEnd('/') + "/.well-known/openid-configuration"
        val reply = try {
            http.get(url)
        } catch (e: IdpRejected) {
            throw IdpMetadataInvalid(e.reason)
        } catch (e: Exception) {
            throw IdpMetadataInvalid("discovery document could not be read (${e.javaClass.simpleName})")
        }
        if (reply.status != 200) throw IdpMetadataInvalid("discovery document returned HTTP ${reply.status}")
        val doc = runCatching { Json.parse(reply.body) }.getOrNull() ?: throw IdpMetadataInvalid("discovery document is not a JSON object")
        return fromDiscovery(issuer, doc)
    }

    fun fromDiscovery(issuer: String, doc: Map<String, Any?>): Map<String, Any?> {
        val docIssuer = doc["issuer"] as? String ?: throw IdpMetadataInvalid("discovery document has no issuer")
        if (docIssuer != issuer) throw IdpMetadataInvalid("discovery issuer does not match the requested issuer")
        val fields = linkedMapOf<String, Any?>("type" to IdentityProvider.OIDC, "issuer" to docIssuer)
        for ((key, field, required) in listOf(
            Triple("authorization_endpoint", "authorizationEndpoint", true),
            Triple("token_endpoint", "tokenEndpoint", true),
            Triple("jwks_uri", "jwksUri", true),
            Triple("end_session_endpoint", "endSessionEndpoint", false),
        )) {
            val value = doc[key]
            if (value != null && value !is String) throw IdpMetadataInvalid("$key must be a string")
            val text = (value as? String) ?: ""
            IdpUrls.check(key, text, required)?.let { throw IdpMetadataInvalid(it) }
            if (text.isNotEmpty()) fields[field] = text
        }
        (doc["response_types_supported"] as? List<*>)?.let { types ->
            if ("code" !in types) throw IdpMetadataInvalid("provider does not support the authorization code flow")
        }
        (doc["code_challenge_methods_supported"] as? List<*>)?.let { methods ->
            if ("S256" !in methods) throw IdpMetadataInvalid("provider does not support S256 PKCE")
        }
        (doc["id_token_signing_alg_values_supported"] as? List<*>)?.let { algs ->
            val usable = IdentityProvider.SIGNING_ALGORITHMS.filter { it in algs }
            if (usable.isEmpty()) throw IdpMetadataInvalid("provider signs ID tokens with no supported algorithm")
            fields["allowedAlgorithms"] = usable
        }
        return fields
    }

    /** The authorization request URL (code flow, PKCE S256). */
    fun authorizationUrl(p: IdentityProvider, redirectUri: String, state: String, nonce: String, verifier: String): String {
        val query = linkedMapOf(
            "response_type" to "code",
            "client_id" to p.clientId,
            "redirect_uri" to redirectUri,
            "scope" to p.scopes.joinToString(" "),
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to Pkce.challenge(verifier),
            "code_challenge_method" to "S256",
        ).entries.joinToString("&") { (k, v) -> "${IdpHttp.enc(k)}=${IdpHttp.enc(v)}" }
        return p.authorizationEndpoint + (if ('?' in p.authorizationEndpoint) "&" else "?") + query
    }

    /** Exchange [code] at the token endpoint and return the ID token. [secret] null is a public client. */
    fun exchange(p: IdentityProvider, code: String, redirectUri: String, verifier: String, secret: String?): String {
        val form = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to verifier,
        )
        val headers = linkedMapOf<String, String>()
        if (secret != null) {
            // client_secret_basic (RFC 6749 §2.3.1): both parts form-encoded before base64.
            val basic = Base64.getEncoder().encodeToString("${IdpHttp.enc(p.clientId)}:${IdpHttp.enc(secret)}".toByteArray(Charsets.UTF_8))
            headers["Authorization"] = "Basic $basic"
        } else {
            form["client_id"] = p.clientId
        }
        val reply = try {
            http.postForm(p.tokenEndpoint, form, headers)
        } catch (e: IdpRejected) {
            throw e
        } catch (e: Exception) {
            throw IdpRejected("token endpoint unreachable")
        }
        if (reply.status != 200) {
            val error = runCatching { Json.parse(reply.body)["error"] }.getOrNull()
            throw IdpRejected("token exchange failed (HTTP ${reply.status})", buildMap {
                put("httpStatus", reply.status)
                IdpDiagnostics.errorCode(error)?.let { put("providerError", it) }
            })
        }
        val body = runCatching { Json.parse(reply.body) }.getOrNull() ?: throw IdpRejected("token response is not JSON")
        return body["id_token"] as? String ?: throw IdpRejected("token response has no id_token")
    }

    /** OIDC Core §3.1.3.7: signature, issuer, audience (and azp), expiry, issued-at, nonce. */
    fun validateIdToken(p: IdentityProvider, token: String, nonce: String): Map<String, Any?> {
        val claims = verified(p, token, p.clientId)
        val aud = audiences(claims)
        if (aud.size > 1 && claims["azp"] != p.clientId) throw IdpRejected("azp does not match the client")
        if (claims["azp"] != null && claims["azp"] != p.clientId) throw IdpRejected("azp does not match the client")
        val iat = number(claims["iat"]) ?: throw IdpRejected("token has no iat")
        if (iat > nowSeconds() + p.clockSkewSeconds) throw IdpRejected("token issued in the future", IdpDiagnostics.timeFacts(iat * 1000, nowSeconds() * 1000))
        val presented = claims["nonce"] as? String ?: throw IdpRejected("token has no nonce")
        if (!org.oip.security.ConstantTime.equal(presented, nonce)) throw IdpRejected("nonce mismatch")
        return claims
    }

    /** A provider token presented as `Authorization: Bearer` (SPEC §6): signature, issuer, audience, expiry. */
    fun validateAccessToken(p: IdentityProvider, token: String): Map<String, Any?> =
        verified(p, token, p.apiAudience.ifBlank { p.clientId })

    /** The identity [claims] map to under [p]'s username claim, roles claim, and role mappings. */
    fun identity(p: IdentityProvider, claims: Map<String, Any?>, knownRoles: Set<String>): IdpIdentity {
        val sub = claims["sub"] as? String ?: throw IdpRejected("token has no sub")
        val username = if (p.usernameClaim.isNotBlank()) {
            IdpClaims.text(claims, p.usernameClaim) ?: throw IdpRejected("token has no ${p.usernameClaim} claim")
        } else {
            IdpClaims.text(claims, "preferred_username") ?: sub
        }
        val clean = IdpClaims.username(username) ?: throw IdpRejected("username claim is not usable")
        val values = if (p.rolesClaim.isBlank()) emptyList() else IdpClaims.values(claims, p.rolesClaim)
        val nameClaim = p.nameClaim.ifBlank { "name" }
        val emailClaim = p.emailClaim.ifBlank { "email" }
        return IdpIdentity(
            p.name, clean, p.mapRoles(values).filterTo(sortedSetOf()) { it in knownRoles }, sub, LoginLevels.claimAttributes(claims),
            IdpClaims.displayName(IdpClaims.text(claims, nameClaim)), IdpClaims.email(IdpClaims.text(claims, emailClaim)),
        )
    }

    private fun verified(p: IdentityProvider, token: String, audience: String): Map<String, Any?> {
        val parsed = Jws.parse(token) ?: throw IdpRejected("token is not a signed JWT")
        val alg = parsed.alg ?: throw IdpRejected("token has no alg")
        if (alg !in p.allowedAlgorithms) throw IdpRejected("token algorithm $alg is not allowed")
        if (parsed.header.containsKey("crit")) throw IdpRejected("token has critical headers")
        val key = keyFor(p, parsed, alg) ?: throw IdpRejected("no provider key matches the token")
        if (!Jws.verify(parsed, alg, key)) throw IdpRejected("token signature is invalid")
        val claims = parsed.claims
        if (claims["iss"] != p.issuer) throw IdpRejected("issuer mismatch", mapOf("expected" to p.issuer, "presented" to IdpDiagnostics.shown(claims["iss"])))
        if (audience !in audiences(claims)) {
            throw IdpRejected("audience mismatch", mapOf("expected" to audience, "presented" to IdpDiagnostics.shown(audiences(claims).joinToString(", "))))
        }
        val now = nowSeconds()
        val exp = number(claims["exp"]) ?: throw IdpRejected("token has no exp")
        if (now >= exp + p.clockSkewSeconds) throw IdpRejected("token expired", IdpDiagnostics.timeFacts(exp * 1000, now * 1000))
        number(claims["nbf"])?.let { if (now + p.clockSkewSeconds < it) throw IdpRejected("token not yet valid", IdpDiagnostics.timeFacts(it * 1000, now * 1000)) }
        return claims
    }

    private fun keyFor(p: IdentityProvider, parsed: Jws.Parsed, alg: String): PublicKey? {
        fun pick(keys: List<Jwk>): PublicKey? {
            val kty = if (alg.startsWith("ES")) "EC" else "RSA"
            val candidates = keys.filter { it.kty == kty && (it.alg == null || it.alg == alg) }
            val kid = parsed.kid
            return if (kid != null) candidates.firstOrNull { it.kid == kid }?.key
            else candidates.singleOrNull()?.key
        }
        val now = clock.millis()
        val cached = jwks[p.jwksUri]
        if (cached != null && now - cached.fetchedMillis < JWKS_TTL_MILLIS) {
            pick(cached.keys)?.let { return it }
            // An unknown kid may be a key rotation; refetch, but not more often than every REFETCH_MILLIS.
            if (now - cached.fetchedMillis < REFETCH_MILLIS) return null
        }
        val fresh = fetchKeys(p.jwksUri)
        jwks[p.jwksUri] = KeySet(now, fresh)
        return pick(fresh)
    }

    private fun fetchKeys(uri: String): List<Jwk> {
        val reply = try {
            http.get(uri)
        } catch (e: IdpRejected) {
            throw e
        } catch (e: Exception) {
            throw IdpRejected("provider keys unreachable")
        }
        if (reply.status != 200) throw IdpRejected("provider keys returned HTTP ${reply.status}")
        val doc = runCatching { Json.parse(reply.body) }.getOrNull() ?: throw IdpRejected("provider keys are not JSON")
        return try {
            Jwks.parse(doc)
        } catch (e: IdpMetadataInvalid) {
            throw IdpRejected(e.message ?: "provider keys are invalid")
        }
    }

    /** Forget cached keys (a provider was edited or removed). */
    fun forget(jwksUri: String) {
        jwks.remove(jwksUri)
    }

    private fun audiences(claims: Map<String, Any?>): List<String> = when (val aud = claims["aud"]) {
        is String -> listOf(aud)
        is List<*> -> aud.filterIsInstance<String>()
        else -> emptyList()
    }

    private fun number(v: Any?): Long? = (v as? Number)?.toLong()

    private fun nowSeconds(): Long = clock.millis() / 1000L

    companion object {
        const val JWKS_TTL_MILLIS = 10L * 60L * 1000L
        const val REFETCH_MILLIS = 30L * 1000L
    }
}

/** PKCE (RFC 7636) verifier and S256 challenge. */
object Pkce {
    private val random = java.security.SecureRandom()

    fun verifier(): String = token(32)

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun token(bytes: Int): String {
        val raw = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}

/** Claim lookups shared by OIDC claims and SAML attributes. */
object IdpClaims {
    /** A claim by name, or a dotted path into nested objects (`realm.roles`) when no claim has the full name. */
    fun lookup(claims: Map<String, Any?>, path: String): Any? {
        if (claims.containsKey(path)) return claims[path]
        var cur: Any? = claims
        for (part in path.split('.')) cur = (cur as? Map<*, *>)?.get(part) ?: return null
        return cur
    }

    fun text(claims: Map<String, Any?>, path: String): String? = (lookup(claims, path) as? String)?.takeIf { it.isNotBlank() }

    fun values(claims: Map<String, Any?>, path: String): List<String> = when (val v = lookup(claims, path)) {
        is String -> listOf(v)
        is List<*> -> v.filterIsInstance<String>()
        else -> emptyList()
    }

    /** A display name fit for a profile: trimmed, at most 200 characters, no control characters; empty otherwise. */
    fun displayName(raw: String?): String {
        val t = raw?.trim().orEmpty()
        return if (t.length > 200 || t.any { it.isISOControl() }) "" else t
    }

    /** An email address of the plain `local@domain` form, at most 254 characters; empty otherwise. */
    fun email(raw: String?): String {
        val t = raw?.trim().orEmpty()
        return if (t.length <= 254 && EMAIL.matches(t)) t else ""
    }

    private val EMAIL = Regex("^[^@\\s<>\"',;:]+@[^@\\s<>\"',;:]+\\.[^@\\s<>\"',;:]+$")

    /** A username fit for a principal and an audit row: trimmed, at most 200 characters, no control characters. */
    fun username(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 200 || t.any { it.isISOControl() }) return null
        return t
    }
}
