package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.security.AuditActions
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import org.oip.security.IdentityProvider
import org.oip.security.SecretReferences
import org.oip.security.Subject
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Browser login through an external identity provider (SEC-013, PLAN P1-32), and provider tokens as REST bearer
 * credentials (SPEC §6).
 *
 * Routes, all reachable by anonymous callers:
 * - `GET /api/v1/auth/providers`: the enabled providers, for the sign-in page buttons.
 * - `GET /api/v1/auth/providers/{name}/login`: starts a login. OIDC: an authorization request with `state`, `nonce` and
 *   a PKCE S256 challenge. SAML: an AuthnRequest over the redirect binding. A short-lived `oip_idp_state` cookie binds
 *   the login to this browser.
 * - `GET /api/v1/auth/providers/{name}/callback`: the OIDC redirect back. The state must be one this gateway issued,
 *   unused, unexpired, for this provider and this browser; the code is exchanged with the PKCE verifier and the ID token
 *   is checked for signature, issuer, audience, expiry, issued-at and nonce.
 * - `POST /api/v1/auth/providers/{name}/acs`: the SAML assertion consumer service.
 *
 * A successful login issues the same session cookie as a password login. The session carries the provider and the
 * roles mapped from the token claims; it ends at logout, at the session timeouts, or when the provider is disabled or
 * deleted. Every login, refused or not, writes one audit record (`login` or `login attempt`, detail `provider=...`).
 * A refused browser login is sent back to the sign-in page with `?error=idp`; the reason is in the audit log only.
 *
 * Doc source: FEATURES SEC-013.
 * Doc source: FEATURES SEC-014.
 * Doc source: FEATURES SEC-018: an administrator starts a test login (and then a test logout) against a provider; the
 * browser flow runs as a login but issues no session, and the result page and `GET .../identity-providers/{name}/test/{id}`
 * show what the provider returned and how it was mapped. A test ticket is single use and expires with the pending login.
 * Doc source: FEATURES SEC-015: the session reports the display name and email mapped from the provider's attributes.
 */
class IdpLogin(private val gw: Gateway) {
    private val security get() = gw.security
    val oidc = OidcClient(gw.clock)
    val saml = SamlServiceProvider(gw.clock)
    private val pending = ConcurrentHashMap<String, Pending>()

    private class Pending(
        val provider: String,
        val type: String,
        val binding: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        val returnTo: String,
        val createdMillis: Long,
        val testId: String? = null,
    )

    /** One administrator-started test of a provider (SEC-018). */
    class TestRun(val provider: String, val startedBy: String, val createdMillis: Long) {
        @Volatile var started = false
        @Volatile var ok: Boolean? = null
        @Volatile var reason: String = ""
        /** SEC-032: what the refused check compared, and the callback address the gateway sent the provider. */
        @Volatile var facts: Map<String, Any?> = emptyMap()
        @Volatile var callback: String = ""
        @Volatile var startedMillis: Long = 0L
        @Volatile var identity: IdpIdentity? = null
        @Volatile var levels: Set<String> = emptySet()
        @Volatile var logoutStarted = false
        @Volatile var loggedOut = false
    }

    private val tests = ConcurrentHashMap<String, TestRun>()

    fun register(app: Javalin) {
        app.get(PROVIDERS) { listEnabled(it) }
        app.get("$PROVIDERS/{name}/login") { start(it) }
        app.get("$PROVIDERS/{name}/callback") { oidcCallback(it) }
        app.post("$PROVIDERS/{name}/acs") { samlAcs(it) }
        app.get("$PROVIDERS/{name}/test-logout") { testLogout(it) }
        app.get("$PROVIDERS/{name}/test-result") { testResult(it) }
    }

    /** A test ticket for [provider], for an administrator ([actor]) to open in a browser (SEC-018). */
    fun createTest(provider: String, actor: String): Map<String, Any?> {
        val p = security.identityProviders.get(provider) ?: throw Problems.notFound("IdentityProvider '$provider' not found")
        val problems = p.problems()
        if (problems.isNotEmpty()) throw Problems.unprocessable("identity provider is not fully configured: ${problems.joinToString("; ")}")
        val now = gw.clock.millis()
        tests.entries.removeIf { now - it.value.createdMillis > TEST_KEEP_MILLIS }
        if (tests.size >= MAX_TESTS) throw Problems.badRequest("too many provider tests are pending; try again later")
        val id = Pkce.token(24)
        tests[id] = TestRun(provider, actor, now)
        return linkedMapOf("testId" to id, "loginPath" to "$PROVIDERS/$provider/login?test=$id", "expiresInSeconds" to PENDING_MILLIS / 1000)
    }

    /** What a test showed so far, for the admin page; null when [id] is not a test of [provider]. */
    fun testResult(provider: String, id: String): Map<String, Any?>? {
        val run = tests[id]?.takeIf { it.provider == provider } ?: return null
        val p = security.identityProviders.get(provider)
        val identity = run.identity
        return linkedMapOf(
            "testId" to id,
            "provider" to provider,
            "status" to when (run.ok) { true -> "success"; false -> "failure"; null -> if (run.started) "started" else "pending" },
            "reason" to run.reason,
            "diagnosis" to diagnosis(run, p)?.view(),
            "username" to identity?.username,
            "roles" to identity?.roles?.sorted(),
            "displayName" to identity?.displayName,
            "email" to identity?.email,
            "levels" to run.levels.sorted(),
            "logout" to linkedMapOf(
                "supported" to (p != null && p.type == IdentityProvider.OIDC && p.endSessionEndpoint.isNotBlank()),
                "started" to run.logoutStarted,
                "completed" to run.loggedOut,
            ),
        )
    }

    /** SEC-032: a failed test, or one whose browser has not come back from the provider, says what is most likely wrong. */
    private fun diagnosis(run: TestRun, p: IdentityProvider?): IdpDiagnostics.Diagnosis? = when {
        run.ok == false -> IdpDiagnostics.forFailure(run.reason, run.facts, p, run.callback)
        run.ok == null && run.started && run.callback.isNotEmpty() && gw.clock.millis() - run.startedMillis > IdpDiagnostics.NO_RETURN_MILLIS ->
            IdpDiagnostics.forNoReturn(p, run.callback, gw.clock.millis() - run.startedMillis)
        else -> null
    }

    private fun ticket(ctx: Context, requireLogin: Boolean): Pair<String, TestRun> {
        val id = ctx.queryParam("test") ?: throw Problems.notFound("test not found")
        val run = tests[id]?.takeIf { it.provider == ctx.pathParam("name") }
        if (run == null || gw.clock.millis() - run.createdMillis > TEST_KEEP_MILLIS || (requireLogin && run.ok != true)) throw Problems.notFound("test not found")
        return id to run
    }

    private fun testLogout(ctx: Context) {
        val (id, run) = ticket(ctx, requireLogin = true)
        val p = security.identityProviders.get(run.provider) ?: throw Problems.notFound("test not found")
        val url = logoutUrl(ctx, p, "$PROVIDERS/${p.name}/test-result?test=$id")
            ?: throw Problems.unprocessable("this provider has no end-session endpoint to test")
        run.logoutStarted = true
        ctx.header("Cache-Control", "no-store")
        ctx.redirect(url, io.javalin.http.HttpStatus.FOUND)
    }

    private fun testResult(ctx: Context) {
        val (id, run) = ticket(ctx, requireLogin = false)
        // The provider sent the browser back to the post-logout address: it ended its own session.
        if (run.logoutStarted && !run.loggedOut) {
            run.loggedOut = true
            audit(AuditActions.IDP_TEST, AuditStatus.SUCCESS, run.startedBy, clientIp(ctx), "provider=${run.provider};step=logout;outcome=returned")
        }
        resultPage(ctx, id, run)
    }

    private fun resultPage(ctx: Context, id: String, run: TestRun) {
        val view = testResult(run.provider, id)!!
        val logout = view["logout"] as Map<*, *>
        fun row(k: String, v: Any?) = "<tr><th scope=\"row\">${esc(k)}</th><td>${esc(v?.toString().orEmpty())}</td></tr>"
        val body = buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>Identity provider test</title></head><body><main><h1>Identity provider test: ${esc(run.provider)}</h1>")
            append("<p>No session was created. Close this window to finish.</p><table>")
            append(row("Login", view["status"]))
            if (view["status"] == "failure") append(row("Reason", view["reason"]))
            (view["diagnosis"] as? Map<*, *>)?.let { d ->
                append(row("Diagnosis", d["summary"]))
                append(row("What to check", d["hint"]))
                (d["facts"] as? Map<*, *>)?.forEach { (k, v) -> append(row(k.toString(), v)) }
            }
            if (view["status"] == "success") {
                append(row("User", view["username"]))
                append(row("Display name", view["displayName"]))
                append(row("Email", view["email"]))
                append(row("Roles", (view["roles"] as? List<*>)?.joinToString(", ")))
                append(row("Security levels", (view["levels"] as? List<*>)?.joinToString(", ")))
                append(row("Logout", when {
                    logout["completed"] == true -> "completed: the provider returned the browser after ending its session"
                    logout["started"] == true -> "started: waiting for the provider to return"
                    logout["supported"] == true -> "not tested yet"
                    else -> "not supported: the provider has no end-session endpoint"
                }))
            }
            append("</table>")
            if (view["status"] == "success" && logout["supported"] == true && logout["completed"] != true) {
                append("<p><a href=\"$PROVIDERS/${esc(run.provider)}/test-logout?test=${esc(id)}\">Test logout</a></p>")
            }
            append("</main></body></html>")
        }
        ctx.status(200).header("Cache-Control", "no-store").contentType("text/html; charset=utf-8").result(body)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun listEnabled(ctx: Context) {
        val items = security.identityProviders.list().filter { it.enabled && it.problems().isEmpty() }.sortedBy { it.name }.map {
            linkedMapOf("name" to it.name, "displayName" to it.label, "type" to it.type, "loginPath" to "$PROVIDERS/${it.name}/login")
        }
        ctx.status(200).contentType("application/json").result(Json.write(mapOf("items" to items)))
    }

    private fun start(ctx: Context) {
        val testId = ctx.queryParam("test")?.let { id ->
            val run = tests[id]?.takeIf { it.provider == ctx.pathParam("name") }
            if (run == null || run.started || gw.clock.millis() - run.createdMillis > PENDING_MILLIS) throw Problems.notFound("test not found")
            run.started = true
            run.startedMillis = gw.clock.millis()
            id
        }
        val p = if (testId != null) testableProvider(ctx.pathParam("name")) else enabledProvider(ctx.pathParam("name"))
        val base = baseUrl(ctx, p)
        val returnTo = safeReturn(ctx.queryParam("returnTo"))
        val state = Pkce.token(32)
        val binding = Pkce.token(32)
        prune()
        val now = gw.clock.millis()
        val secure = ctx.viaScheme(gw).equals("https", ignoreCase = true)
        val location = when (p.type) {
            IdentityProvider.OIDC -> {
                val redirectUri = "$base$PROVIDERS/${p.name}/callback"
                val nonce = Pkce.token(32)
                val verifier = Pkce.verifier()
                pending[state] = Pending(p.name, p.type, sha(binding), nonce, verifier, redirectUri, returnTo, now, testId)
                testId?.let { tests[it]?.callback = redirectUri }
                oidc.authorizationUrl(p, redirectUri, state, nonce, verifier)
            }
            else -> {
                val acs = "$base$PROVIDERS/${p.name}/acs"
                // The AuthnRequest ID: an NCName, so it starts with a letter or underscore.
                val requestId = "_" + Pkce.token(24)
                pending[state] = Pending(p.name, p.type, sha(binding), requestId, "", acs, returnTo, now, testId)
                testId?.let { tests[it]?.callback = acs }
                saml.authnRequestUrl(p, requestId, acs, state)
            }
        }
        // The SAML response comes back as a cross-site POST, which only a SameSite=None cookie survives (HTTPS only).
        val sameSite = if (p.type == IdentityProvider.SAML && secure) "None" else "Lax"
        ctx.header("Set-Cookie", "$STATE_COOKIE=$binding; Path=$PROVIDERS; Max-Age=${PENDING_MILLIS / 1000}; HttpOnly; SameSite=$sameSite" + if (secure) "; Secure" else "")
        ctx.header("Cache-Control", "no-store")
        ctx.redirect(location, io.javalin.http.HttpStatus.FOUND)
    }

    private fun oidcCallback(ctx: Context) {
        val name = ctx.pathParam("name")
        val ip = clientIp(ctx)
        var testId: String? = null
        val outcome = runCatching {
            gw.httpAuth.takeLoginPermit(ip)?.let { throw IdpRejected("rate limited") }
            val state = ctx.queryParam("state") ?: throw IdpRejected("missing state")
            val login = pending.remove(state) ?: throw IdpRejected("unknown or used state")
            testId = login.testId
            if (login.provider != name || login.type != IdentityProvider.OIDC) throw IdpRejected("state is for another provider")
            if (gw.clock.millis() - login.createdMillis > PENDING_MILLIS) throw IdpRejected("login expired")
            val cookie = ctx.cookie(STATE_COOKIE) ?: throw IdpRejected("login was started in another browser")
            if (!org.oip.security.ConstantTime.equal(sha(cookie), login.binding)) throw IdpRejected("login was started in another browser")
            ctx.queryParam("error")?.let { error ->
                val about = ctx.queryParam("error_description")?.lowercase()?.takeIf { it.contains("redirect") }?.let { "redirect_uri" }
                throw IdpRejected("provider returned an error", buildMap {
                    IdpDiagnostics.errorCode(error)?.let { put("providerError", it) }
                    about?.let { put("providerErrorAbout", it) }
                })
            }
            val code = ctx.queryParam("code") ?: throw IdpRejected("missing code")
            val p = if (testId != null) testableProvider(name) else enabledProvider(name)
            val token = oidc.exchange(p, code, login.redirectUri, login.verifier, clientSecret(p))
            val claims = oidc.validateIdToken(p, token, login.nonce)
            oidc.identity(p, claims, knownRoles()) to login.returnTo
        }
        finish(ctx, name, ip, outcome, testId)
    }

    private fun samlAcs(ctx: Context) {
        val name = ctx.pathParam("name")
        val ip = clientIp(ctx)
        var testId: String? = null
        val outcome = runCatching {
            gw.httpAuth.takeLoginPermit(ip)?.let { throw IdpRejected("rate limited") }
            val state = ctx.formParam("RelayState") ?: throw IdpRejected("missing RelayState")
            val login = pending.remove(state) ?: throw IdpRejected("unknown or used state")
            testId = login.testId
            if (login.provider != name || login.type != IdentityProvider.SAML) throw IdpRejected("state is for another provider")
            if (gw.clock.millis() - login.createdMillis > PENDING_MILLIS) throw IdpRejected("login expired")
            val cookie = ctx.cookie(STATE_COOKIE)
            // Over HTTPS the binding cookie is SameSite=None and must come back. Over plain HTTP a browser drops a
            // cross-site POST's cookies, so the single-use request ID and RelayState are the binding (DIFFERENCES 258).
            if (cookie != null || ctx.viaScheme(gw).equals("https", ignoreCase = true)) {
                if (cookie == null || !org.oip.security.ConstantTime.equal(sha(cookie), login.binding)) {
                    throw IdpRejected("login was started in another browser")
                }
            }
            val response = ctx.formParam("SAMLResponse") ?: throw IdpRejected("missing SAMLResponse")
            val p = if (testId != null) testableProvider(name) else enabledProvider(name)
            val assertion = saml.validate(p, response, login.redirectUri, login.nonce)
            saml.identity(p, assertion, knownRoles()) to login.returnTo
        }
        finish(ctx, name, ip, outcome, testId)
    }

    private fun finish(ctx: Context, provider: String, ip: String, outcome: Result<Pair<IdpIdentity, String>>, testId: String? = null) {
        val secure = ctx.viaScheme(gw).equals("https", ignoreCase = true)
        val clearState = "$STATE_COOKIE=; Path=$PROVIDERS; Max-Age=0; HttpOnly; SameSite=Lax" + if (secure) "; Secure" else ""
        ctx.header("Cache-Control", "no-store")
        val run = testId?.let { tests[it] }
        if (testId != null && run != null) {
            // SEC-018: a test login runs the whole flow and shows the outcome, but never issues a session.
            ctx.res().addHeader("Set-Cookie", clearState)
            outcome.onSuccess { (identity, _) ->
                run.identity = identity
                run.levels = gw.loginLevels.levels(identity.provider, identity.username, identity.roles, identity.attributes, GatewayAuth.connection(ctx, gw))
                run.ok = true
                audit(AuditActions.IDP_TEST, AuditStatus.SUCCESS, run.startedBy, ip, "provider=$provider;step=login;outcome=success;user=${identity.username.take(100)}")
            }.onFailure { e ->
                run.reason = when (e) {
                    is IdpRejected -> e.reason
                    is ProblemException -> e.message ?: "refused"
                    else -> "login failed (${e.javaClass.simpleName})"
                }
                run.facts = (e as? IdpRejected)?.facts.orEmpty()
                run.ok = false
                audit(AuditActions.IDP_TEST, AuditStatus.FAILURE, run.startedBy, ip, "provider=$provider;step=login;reason=${run.reason}")
            }
            resultPage(ctx, testId, run)
            return
        }
        val result = outcome.getOrElse { e ->
            val reason = when (e) {
                is IdpRejected -> e.reason
                is ProblemException -> e.message ?: "refused"
                else -> "login failed (${e.javaClass.simpleName})"
            }
            audit(AuditActions.LOGIN_ATTEMPT, AuditStatus.FAILURE, "$provider:unknown", ip, "provider=$provider;reason=$reason")
            ctx.res().addHeader("Set-Cookie", clearState)
            ctx.redirect("/admin/login?error=idp", io.javalin.http.HttpStatus.FOUND)
            return
        }
        val (identity, returnTo) = result
        ctx.cookie(HttpAuth.COOKIE)?.let { gw.httpAuth.delete(it) }
        // SEC-016: the provider's rules place the user into security levels at this login.
        val levels = gw.loginLevels.levels(identity.provider, identity.username, identity.roles, identity.attributes, GatewayAuth.connection(ctx, gw))
        val issued = gw.httpAuth.issue(identity.principal, gw.sessionPolicy.absolute, identity.provider, identity.roles, levels)
        gw.httpAuth.saveProfile(identity.principal, identity.displayName, identity.email)
        val now = gw.clock.millis()
        val maxAge = if (issued.absoluteExpiresMillis <= 0L) null
        else ((issued.absoluteExpiresMillis - now) / 1000L).coerceIn(1L, Int.MAX_VALUE.toLong())
        audit(
            AuditActions.LOGIN, AuditStatus.SUCCESS, identity.principal, ip,
            "provider=$provider;roles=${identity.roles.joinToString(",")};${levelsDetail(levels)}",
        )
        ctx.res().addHeader("Set-Cookie", HttpAuth.sessionCookie(issued.token, maxAge, gw.settings.secureCookiesEffective))
        ctx.res().addHeader("Set-Cookie", clearState)
        ctx.redirect(returnTo, io.javalin.http.HttpStatus.FOUND)
    }

    /**
     * A provider access token (or ID token) presented as `Authorization: Bearer` (SPEC §6). The token's issuer picks
     * the enabled OIDC provider; its signature, issuer, audience (`apiAudience`, else the client id) and expiry must
     * hold. Null when no provider claims the issuer, so the caller falls back to the API-key check.
     */
    fun bearerSubject(token: String, ip: String): Subject.User? = bearerIdentity(token, ip)?.let { Subject.User(it.principal, it.roles, true) }

    /** [bearerSubject] with the provider and its claims, for the security level rules (SEC-016). */
    fun bearerIdentity(token: String, ip: String): IdpIdentity? {
        if (!Jws.looksLikeJws(token)) return null
        val iss = Jws.parse(token)?.claims?.get("iss") as? String ?: return null
        val providers = security.identityProviders.list().filter { it.enabled && it.type == IdentityProvider.OIDC && it.issuer == iss }
        if (providers.isEmpty()) return null
        var reason = "rejected"
        for (p in providers) {
            try {
                val claims = oidc.validateAccessToken(p, token)
                return oidc.identity(p, claims, knownRoles())
            } catch (e: IdpRejected) {
                reason = e.reason
            }
        }
        audit(AuditActions.TOKEN_LOGIN, AuditStatus.FAILURE, "${providers.first().name}:unknown", ip, "provider=${providers.first().name};reason=$reason")
        throw Problems.unauthorized("invalid credentials")
    }

    /** RP-initiated logout URL for a session [provider] signed in, or null when the provider names no end-session endpoint. */
    fun endSessionUrl(ctx: Context, provider: String): String? {
        val p = security.identityProviders.get(provider) ?: return null
        if (p.type != IdentityProvider.OIDC || p.endSessionEndpoint.isBlank()) return null
        return logoutUrl(ctx, p, "/admin/login")
    }

    private fun logoutUrl(ctx: Context, p: IdentityProvider, returnPath: String): String? {
        if (p.type != IdentityProvider.OIDC || p.endSessionEndpoint.isBlank()) return null
        val back = runCatching { baseUrl(ctx, p) }.getOrNull() ?: return null
        val q = "client_id=${IdpHttp.enc(p.clientId)}&post_logout_redirect_uri=${IdpHttp.enc("$back$returnPath")}"
        return p.endSessionEndpoint + (if ('?' in p.endSessionEndpoint) "&" else "?") + q
    }

    /** A provider a test may run against: configured completely, enabled or not. */
    private fun testableProvider(name: String): IdentityProvider {
        val p = security.identityProviders.get(name) ?: throw Problems.notFound("identity provider '$name' not found")
        if (p.problems().isNotEmpty()) throw Problems.notFound("identity provider '$name' is not configured")
        return p
    }

    /** The plaintext client secret from the secret store, or null for a public client. */
    private fun clientSecret(p: IdentityProvider): String? {
        if (p.clientSecretRef.isBlank()) return null
        return try {
            security.secrets.getText(SecretReferences.parse(p.clientSecretRef))
        } catch (e: Exception) {
            throw IdpRejected("client secret could not be read")
        }
    }

    private fun enabledProvider(name: String): IdentityProvider {
        val p = security.identityProviders.get(name) ?: throw Problems.notFound("identity provider '$name' not found")
        if (!p.enabled) throw Problems.notFound("identity provider '$name' not found")
        if (p.problems().isNotEmpty()) throw Problems.notFound("identity provider '$name' is not configured")
        return p
    }

    private fun knownRoles(): Set<String> = security.roles.list().mapTo(HashSet()) { it.name }

    private fun audit(action: String, status: AuditStatus, actor: String, host: String, detail: String) {
        if (!security.auditProfile.get().enabled) return
        runCatching {
            security.audit.append(
                AuditDraft(
                    timestampMillis = gw.clock.millis(),
                    actor = actor.take(250),
                    action = action,
                    status = status,
                    actorHost = host,
                    target = actor.take(250),
                    detail = detail.take(500),
                    context = "gateway-http",
                )
            )
        }
    }

    private fun prune() {
        val now = gw.clock.millis()
        pending.entries.removeIf { now - it.value.createdMillis > PENDING_MILLIS }
        if (pending.size >= MAX_PENDING) {
            pending.entries.sortedBy { it.value.createdMillis }.take(pending.size - MAX_PENDING + 1).forEach { pending.remove(it.key) }
        }
    }

    private fun baseUrl(ctx: Context, p: IdentityProvider): String {
        if (p.redirectBaseUrl.isNotBlank()) return p.redirectBaseUrl.trimEnd('/')
        val host = ctx.viaHost(gw) ?: throw Problems.badRequest("Host header is required")
        if (!HOST.matches(host)) throw Problems.badRequest("Host header is not valid")
        return "${ctx.viaScheme(gw)}://$host${ctx.viaPrefix(gw)}"
    }

    private fun clientIp(ctx: Context): String =
        ClientAddresses.clientIp(ctx.ip(), ctx.header("X-Forwarded-For"), gw.settings.trustedProxies)

    private fun sha(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    companion object {
        const val PROVIDERS = "/api/v1/auth/providers"
        const val STATE_COOKIE = "oip_idp_state"
        const val PENDING_MILLIS = 10L * 60L * 1000L
        const val MAX_PENDING = 10_000
        const val MAX_TESTS = 1_000
        const val TEST_KEEP_MILLIS = 30L * 60L * 1000L
        private val HOST = Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$|^\\[[0-9A-Fa-f:.]+](:[0-9]{1,5})?$")

        /** A same-origin path to land on after login; anything else becomes the admin overview. */
        fun safeReturn(raw: String?): String {
            val fallback = "/admin/overview"
            if (raw.isNullOrEmpty() || raw.length > 500) return fallback
            if (!raw.startsWith("/") || raw.startsWith("//") || raw.contains('\\') || raw.any { it.isISOControl() }) return fallback
            return raw
        }
    }
}
