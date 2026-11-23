package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.security.AuditActions
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.security.AuthFailure
import org.oip.security.AuthenticationResult
import org.oip.security.ChainedAuditLog
import org.oip.security.ConnectionContext
import org.oip.security.Permissions
import org.oip.security.RequestScope
import org.oip.security.SecurityLevels
import org.oip.security.SecurityService
import org.oip.security.Subject
import org.oip.security.ZoneDirectory
import org.oip.tags.TagCaller
import java.net.InetAddress
import java.security.MessageDigest
import java.util.HexFormat

/** Caller identity for routes that apply their own role check after authentication. */
data class HttpPrincipal(val name: String, val roles: Set<String>)

/**
 * HTTP authentication for `/api/v1`.
 *
 * Browser clients get an `oip_session` cookie (Secure, HttpOnly, SameSite=Strict) and must send
 * `X-CSRF-Token` on POST, PUT, PATCH, and DELETE. Machine clients send `Authorization: Bearer` with an
 * API token (SPEC); those requests are not cookie-authenticated, so they are not subject to CSRF.
 * The secure flag is the request scheme only. `X-Forwarded-For` is used only when the peer is a trusted proxy.
 *
 * Idle timeout: gateway security `inactivityTimeoutMinutes` (<= 0 disables it).
 * Absolute timeout: [SessionPolicy.absolute] (default 12 h; zero disables it).
 */
class GatewayAuth(private val gw: Gateway) {
    private val security: SecurityService get() = gw.security
    private val store: HttpAuthStore get() = gw.httpAuth

    fun register(app: Javalin) {
        app.before { guard(it) }
        app.after { releaseAudit(it) }
        // WebSocket upgrades skip the HTTP before/after handlers; they get the same guard (`/api/v1/ws`, M4.1).
        app.wsBeforeUpgrade { ctx ->
            guard(ctx)
            releaseAudit(ctx)
        }
        app.post("/api/v1/auth/login") { login(it) }
        app.post("/api/v1/auth/logout") { logout(it) }
        app.get("/api/v1/auth/session") { session(it) }
        app.get("/api/v1/audit") { audit(it) }
        app.get(SETUP_PATH) { setupStatus(it) }
        app.post(SETUP_ADMIN_PATH) { createFirstAdmin(it) }
    }

    /**
     * First-run setup (0.1.1): a gateway with no users and no ERIC_ADMIN_PASSWORD shows a "Create the administrator"
     * form at /admin instead of a sign-in that nobody can pass. Only a browser on this machine can use it: the
     * request must come straight from a loopback address (no proxy header), as JSON, from this gateway's own origin.
     * Once any user exists the route answers 409 for good.
     */
    private fun setupStatus(ctx: Context) {
        val needs = security.users.list().isEmpty() && externalSource() == null
        ctx.status(200).contentType("application/json").result(Json.write(mapOf("needsAdmin" to needs, "fromThisMachine" to firstRunAllowed(ctx))))
    }

    private fun firstRunAllowed(ctx: Context): Boolean {
        val remote = runCatching { InetAddress.getByName(ctx.req().remoteAddr) }.getOrNull() ?: return false
        if (!remote.isLoopbackAddress) return false
        if (ctx.header("X-Forwarded-For") != null || ctx.header("Forwarded") != null) return false
        val origin = ctx.header("Origin") ?: return true
        val host = ctx.header("Host") ?: return false
        return origin == "http://$host" || origin == "https://$host"
    }

    private fun createFirstAdmin(ctx: Context) {
        if (!firstRunAllowed(ctx)) throw Problems.forbidden("the first administrator can only be created from a browser on the gateway machine")
        val type = ctx.contentType()?.substringBefore(';')?.trim()?.lowercase()
        if (type != "application/json") throw Problems.badRequest("send JSON")
        val body = jsonObject(ctx)
        val username = (body["username"] as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: "admin"
        val password = body["password"] as? String ?: throw Problems.badRequest("password is required")
        if (password.length > org.oip.security.PasswordRules.MAX_CODE_POINTS) throw Problems.badRequest("password is too long")
        val chars = password.toCharArray()
        synchronized(FIRST_RUN_LOCK) {
            if (security.users.list().isNotEmpty() || externalSource() != null) {
                throw ProblemException(409, "already-set-up", "Conflict", "this gateway already has users; sign in instead")
            }
            try {
                security.users.create(
                    org.oip.security.CreateUser(username, chars, setOf(org.oip.security.DefaultRoles.ADMINISTRATOR)),
                    actor = "first-run",
                    host = clientIp(ctx),
                )
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable(e.message ?: "the user was not created")
            } finally {
                chars.fill('\u0000')
            }
        }
        finishLogin(ctx, username, setOf(org.oip.security.DefaultRoles.ADMINISTRATOR), connectionOf(ctx))
        ctx.status(201)
    }

    private fun guard(ctx: Context) {
        var access = accessFor(ctx.req().method, ctx.path())
        if (access == Access.Public && gw.systemRequiresAuth(ctx.path())) access = Access.Authenticated
        when (access) {
            Access.Public -> return
            Access.Deny -> {
                // No rule names this path (review-1 F6). The caller is still resolved, so anonymous gets 401.
                ctx.attribute(CALLER, resolve(ctx))
                // A path no handler serves falls through to 404; nothing runs for it. A registered route without
                // a rule is refused for every caller until the matrix names it.
                if (servesRoute(ctx)) throw Problems.forbidden("no access rule for ${ctx.req().method} ${ctx.path()}")
            }
            Access.Authenticated -> {
                val caller = resolve(ctx)
                ctx.attribute(CALLER, caller)
                restrictPublicHmi(ctx, caller)
                if (caller.sessionToken != null && unsafe(ctx.req().method)) checkCsrf(ctx, caller)
                ctx.attribute(SCOPE, AuditContext.open(caller.subject.principal, clientIp(ctx), "gateway-http"))
            }
            is Access.Permission -> {
                val caller = resolve(ctx)
                ctx.attribute(CALLER, caller)
                restrictPublicHmi(ctx, caller)
                if (caller.sessionToken != null && unsafe(ctx.req().method)) checkCsrf(ctx, caller)
                for (permission in listOf(access.permission) + access.also) {
                    val decision = security.authorization.authorize(caller.subject, permission, caller.connection)
                    if (!decision.allowed) {
                        auditDenial(ctx, permission)
                        throw Problems.forbidden("$permission denied (${decision.reason})")
                    }
                }
                ctx.attribute(SCOPE, AuditContext.open(caller.subject.principal, clientIp(ctx), "gateway-http"))
            }
        }
    }

    private fun servesRoute(ctx: Context): Boolean {
        val type = runCatching { io.javalin.http.HandlerType.valueOf(ctx.req().method.uppercase()) }.getOrNull() ?: return false
        return gw.app.unsafeConfig().pvt.internalRouter.findHttpHandlerEntries(type, ctx.path()).findAny().isPresent
    }

    private fun login(ctx: Context) {
        val ip = clientIp(ctx)
        // Every login POST spends a permit, including a body this gateway will reject, so spraying garbage
        // cannot skip the bucket.
        store.takeLoginPermit(ip)?.let { throw Problems.rateLimited(it) }
        val body = jsonObject(ctx)
        val username = body["username"] as? String ?: throw Problems.badRequest("username is required")
        val password = body["password"] as? String ?: throw Problems.badRequest("password is required")
        if (password.length > org.oip.security.PasswordRules.MAX_CODE_POINTS) throw Problems.badRequest("password is too long")
        // SEC-007, SEC-010, SEC-021, SEC-025: the gateway's system user source can be an LDAP, database or hybrid source.
        externalSource()?.let { return externalLogin(ctx, it, username, password, ip) }
        // A name the user directory would reject (wrong case, spaces, controls) must look like any other
        // failed login. It still spends the IP permit above and a lockout slot under a bounded key.
        val canonical = username.takeIf { LOGIN_NAME.matches(it) }
        val bucket = canonical ?: invalidBucket(username)
        store.lockRemaining(bucket, ip)?.let { throw Problems.locked(it) }
        if (canonical == null) rejectCredentials(bucket, ip)
        val chars = password.toCharArray()
        val result = try {
            security.users.authenticate(canonical, chars, ip)
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            chars.fill('\u0000')
        }
        if (result == null) rejectCredentials(bucket, ip)
        when (result) {
            is AuthenticationResult.Success -> {
                store.clearFailures(canonical, ip)
                val user = result.subject as Subject.User
                finishLogin(ctx, user.username, user.roles, connectionOf(ctx))
            }
            is AuthenticationResult.PasswordExpired -> {
                store.clearFailures(canonical, ip)
                throw Problems.forbidden("password expired", "password-expired")
            }
            is AuthenticationResult.Failure -> when (result.reason) {
                AuthFailure.BAD_CREDENTIALS -> rejectCredentials(bucket, ip)
                AuthFailure.DISABLED -> {
                    store.clearFailures(canonical, ip)
                    throw Problems.forbidden("account disabled")
                }
                AuthFailure.INSECURE_CHANNEL -> throw Problems.forbidden("secure connection required", "insecure-channel")
            }
        }
    }

    /**
     * The external user source gateway logins use: GatewaySecurity.systemUserSource when it names a user source
     * resource. The internal source otherwise, also when the name matches no resource, so a mistyped or removed name
     * never locks every account out; a source in use cannot be deleted (UserSourceApi).
     */
    private fun externalSource(): String? {
        val name = security.gatewaySecurity.get().systemUserSource
        if (name == org.oip.security.INTERNAL_SOURCE) return null
        return name.takeIf { security.userSources.get(it) != null }
    }

    private fun externalLogin(ctx: Context, source: String, username: String, password: String, ip: String) {
        // Directory names are case-insensitive, so the lockout bucket is too. A name no source accepts spends a bounded slot.
        val valid = org.oip.security.UserSource.USERNAME.matches(username)
        val bucket = if (valid) "$source:${username.lowercase()}" else invalidBucket(username)
        store.lockRemaining(bucket, ip)?.let { throw Problems.locked(it) }
        val chars = password.toCharArray()
        val result = try {
            gw.userSourceLogin.authenticate(source, username, chars, ip)
        } finally {
            chars.fill('\u0000')
        }
        when (result) {
            is org.oip.security.SourceLogin.Success -> {
                store.clearFailures(bucket, ip)
                val user = result.user
                gw.httpAuth.saveProfile(user.username, user.displayName, user.email)
                finishLogin(ctx, user.username, user.roles, connectionOf(ctx), source)
            }
            org.oip.security.SourceLogin.BadCredentials -> rejectCredentials(bucket, ip)
            org.oip.security.SourceLogin.Disabled -> {
                store.clearFailures(bucket, ip)
                throw Problems.forbidden("account disabled")
            }
            // Not a failed password: no lockout slot. The reason is for the administrator (audit, user source check).
            is org.oip.security.SourceLogin.Unavailable -> throw ProblemException(503, "user-source-unavailable", "Service Unavailable", "the user source is not available")
        }
    }

    private fun rejectCredentials(bucket: String, ip: String): Nothing {
        val view = store.recordFailure(bucket, ip)
        if (view.justLocked) recordLockout(if (bucket.startsWith("invalid-")) "unknown" else bucket, ip, view)
        if (view.locked) throw Problems.locked(view.retryAfterSeconds)
        throw Problems.unauthorized("invalid credentials")
    }

    /** [source] is the external user source of the login, null for the internal one. */
    private fun finishLogin(ctx: Context, username: String, roles: Set<String>, connection: ConnectionContext, source: String? = null) {
        // A signed-in user is about to use the Designer or an HMI session, whose event scripts
        // would otherwise pay the script engine's cold start on the first click (review-2 F2).
        gw.scripts.warmUp()
        ctx.cookie(HttpAuth.COOKIE)?.let { store.delete(it) }
        // SEC-016: the rules for password logins place the user into security levels now, for this session.
        val levels = if (source == null) gw.loginLevels.levels(SecurityLevels.SOURCE_INTERNAL, username, roles, gw.loginLevels.internalAttributes(username), connection)
        else gw.loginLevels.levels(source, username, roles, emptyMap(), connection)
        // An external source's session carries the roles mapped at login, like an identity provider's (SEC-007).
        val issued = if (source == null) store.issue(username, gw.sessionPolicy.absolute, roles = roles, levels = levels)
        else store.issue(username, gw.sessionPolicy.absolute, provider = UserSourceApi.sessionMarker(source), roles = roles, levels = levels)
        val now = gw.clock.millis()
        val maxAge = if (issued.absoluteExpiresMillis <= 0L) null
        else ((issued.absoluteExpiresMillis - now) / 1000L).coerceIn(1L, Int.MAX_VALUE.toLong())
        ctx.header("Set-Cookie", HttpAuth.sessionCookie(issued.token, maxAge, gw.settings.secureCookiesEffective))
        val body = linkedMapOf<String, Any?>(
            "username" to username,
            "roles" to roles.sorted(),
            "securityLevels" to levels.sorted(),
            "csrfToken" to issued.csrf,
        )
        if (issued.absoluteExpiresMillis > 0L) body["expiresAt"] = java.time.Instant.ofEpochMilli(issued.absoluteExpiresMillis).toString()
        idleSeconds()?.let { body["idleTimeoutSeconds"] = it }
        ctx.status(200).contentType("application/json").result(Json.write(body))
    }

    /**
     * A project with `publicHmi` opens with no sign-in. The request gets a session for [PUBLIC_USER], which can read
     * and write tags and use that project's HMI. Every other API call, including `/admin`, is refused.
     * The account is created the first time a public project is opened. It is not created at install.
     */
    internal fun ensurePublicHmi(ctx: Context) {
        val existing = ctx.cookie(HttpAuth.COOKIE)
        if (existing != null && store.resume(existing, idleMillis(), touch = false) is SessionState.Active) return
        val account = ensurePublicAccount() ?: return
        val connection = connectionOf(ctx)
        val levels = gw.loginLevels.levels(
            SecurityLevels.SOURCE_INTERNAL,
            account.username,
            account.roles,
            gw.loginLevels.internalAttributes(account.username),
            connection,
        )
        val issued = store.issue(account.username, gw.sessionPolicy.absolute, roles = account.roles, levels = levels)
        val now = gw.clock.millis()
        val maxAge = if (issued.absoluteExpiresMillis <= 0L) null
        else ((issued.absoluteExpiresMillis - now) / 1000L).coerceIn(1L, Int.MAX_VALUE.toLong())
        ctx.header("Set-Cookie", HttpAuth.sessionCookie(issued.token, maxAge, gw.settings.secureCookiesEffective))
    }

    private fun ensurePublicAccount(): org.oip.security.UserAccount? {
        synchronized(GatewayAuth::class.java) {
            if (security.roles.get(PUBLIC_ROLE) == null) {
                security.roles.create(
                    PUBLIC_ROLE,
                    setOf(org.oip.security.Permissions.TAGS_READ, org.oip.security.Permissions.TAGS_WRITE),
                    "Read and write tags for a project that allows a public HMI. No admin access.",
                    actor = "system",
                )
            }
            val existing = security.users.get(PUBLIC_USER)
            if (existing != null) return existing.takeIf { it.enabled && PUBLIC_ROLE in it.roles }
            val password = "Anon-Session-Key-9kLm!".toCharArray()
            return try {
                security.users.create(
                    org.oip.security.CreateUser(PUBLIC_USER, password, setOf(PUBLIC_ROLE), displayName = "Public HMI"),
                    actor = "system",
                )
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun restrictPublicHmi(ctx: Context, caller: Caller) {
        val name = (caller.subject as? Subject.User)?.username ?: return
        if (name != PUBLIC_USER) return
        if (!publicHmiAllowed(ctx.req().method, ctx.path())) throw Problems.unauthorized("authentication required")
    }

    private fun publicHmiAllowed(method: String, path: String): Boolean {
        val verb = method.uppercase()
        if (path == "/api/v1/auth/session" && verb == "GET") return true
        if (path == "/api/v1/auth/logout" && verb == "POST") return true
        if (path == "/api/v1/hmi/projects" && (verb == "GET" || verb == "HEAD")) return true
        val project = HMI_PROJECT.matchEntire(path)?.groupValues?.get(1)
        if (project != null && projectIsPublic(project) && (verb == "GET" || verb == "HEAD" || verb == "POST")) return true
        if (path == "/api/v1/ws" && verb == "GET") return true
        if ((path == "/api/v1/tags/read" || path == "/api/v1/tags/write") && verb == "POST") return true
        if ((path == "/api/v1/history" || path.startsWith("/api/v1/history/")) && (verb == "GET" || verb == "HEAD")) return true
        if (path == "/api/v1/alarms" && (verb == "GET" || verb == "HEAD")) return true
        if ((path == "/api/v1/alarms/ack" || path == "/api/v1/alarms/shelve" || path == "/api/v1/alarms/unshelve") && verb == "POST") return true
        return false
    }

    private fun projectIsPublic(name: String): Boolean =
        runCatching { gw.projectService.info(name)?.settings?.publicHmi == true }.getOrDefault(false)

    private fun logout(ctx: Context) {
        val caller = ctx.attribute<Caller>(CALLER) ?: throw Problems.unauthorized("authentication required")
        val token = caller.sessionToken ?: throw Problems.badRequest("logout requires a session cookie")
        // An identity-provider session can also end at the provider (RP-initiated logout), when it names an endpoint.
        val endSession = caller.provider?.let { gw.idp.endSessionUrl(ctx, it) }
        if (security.auditProfile.get().enabled) {
            security.audit.append(
                AuditDraft(
                    timestampMillis = gw.clock.millis(),
                    actor = caller.subject.principal,
                    action = AuditActions.LOGOUT,
                    status = AuditStatus.SUCCESS,
                    actorHost = clientIp(ctx),
                    target = caller.subject.principal,
                    context = "gateway-http",
                )
            )
        }
        store.delete(token)
        ctx.header("Set-Cookie", HttpAuth.clearCookie(gw.settings.secureCookiesEffective))
        if (endSession != null) ctx.status(200).contentType("application/json").result(Json.write(mapOf("endSessionUrl" to endSession)))
        else ctx.status(204)
    }

    private fun session(ctx: Context) {
        val caller = ctx.attribute<Caller>(CALLER) ?: throw Problems.unauthorized("authentication required")
        val permissions = security.authorization.effectivePermissions(caller.subject, caller.connection).sorted()
        val body = linkedMapOf<String, Any?>(
            "kind" to if (caller.subject is Subject.User) "user" else "apiKey",
            "principal" to caller.subject.principal,
            "permissions" to permissions,
            "zone" to security.zones.match(caller.connection).name,
            "securityLevels" to caller.levels.sorted(),
        )
        when (val subject = caller.subject) {
            is Subject.User -> {
                body["roles"] = subject.roles.sorted()
                caller.userSource?.let {
                    body["userSource"] = it
                    gw.httpAuth.profile(subject.principal)?.let { (name, email) ->
                        if (name.isNotEmpty()) body["displayName"] = name
                        if (email.isNotEmpty()) body["email"] = email
                    }
                }
                caller.provider?.let {
                    body["provider"] = it
                    // SEC-015: the profile fields the provider's attributes mapped at login.
                    gw.httpAuth.profile(subject.principal)?.let { (name, email) ->
                        if (name.isNotEmpty()) body["displayName"] = name
                        if (email.isNotEmpty()) body["email"] = email
                    }
                }
                caller.csrf?.let { body["csrfToken"] = it }
                caller.absoluteExpiresMillis?.takeIf { it > 0L }?.let {
                    body["expiresAt"] = java.time.Instant.ofEpochMilli(it).toString()
                }
            }
            is Subject.ApiKey -> body["scopes"] = subject.scopes.sorted()
        }
        ctx.status(200).contentType("application/json").result(Json.write(body))
    }

    private fun audit(ctx: Context) {
        val limit = intParam(ctx, "limit", 100)
        val offset = intParam(ctx, "offset", 0)
        if (limit !in 1..ChainedAuditLog.MAX_LIMIT) throw Problems.badRequest("limit must be 1..${ChainedAuditLog.MAX_LIMIT}")
        if (offset < 0) throw Problems.badRequest("offset must be >= 0")
        val status = ctx.queryParam("status")?.let {
            runCatching { AuditStatus.valueOf(it) }.getOrNull()
                ?: throw Problems.badRequest("status must be SUCCESS or FAILURE")
        }
        val page = security.audit.query(
            AuditQuery(
                fromMillis = longParam(ctx, "from"),
                toMillis = longParam(ctx, "to"),
                actor = ctx.queryParam("actor"),
                action = ctx.queryParam("action"),
                targetContains = ctx.queryParam("target"),
                status = status,
                limit = limit,
                offset = offset,
            )
        )
        val items = page.records.map { r ->
            linkedMapOf(
                "seq" to r.seq,
                "timestamp" to r.timestamp.toString(),
                "actor" to r.actor,
                "actorHost" to r.actorHost,
                "action" to r.action,
                "target" to r.target,
                "detail" to r.detail,
                "status" to r.status.name,
                "origin" to r.origin,
                "context" to r.context,
            )
        }
        ctx.status(200).contentType("application/json").result(
            Json.write(mapOf("items" to items, "total" to page.total, "limit" to page.limit, "offset" to page.offset))
        )
    }

    private fun resolve(ctx: Context): Caller {
        val connection = connectionOf(ctx)
        val authorization = ctx.header("Authorization")
        if (authorization != null) {
            val token = bearer(authorization) ?: throw Problems.unauthorized("invalid credentials")
            // A provider token (P1-32, SPEC §6) when an enabled OIDC provider claims its issuer; otherwise an API key.
            gw.idp.bearerIdentity(token, clientIp(ctx))?.let { identity ->
                // A bearer token is a login at every request, so its rules are evaluated at every request (SEC-016).
                val levels = gw.loginLevels.levels(identity.provider, identity.username, identity.roles, identity.attributes, connection)
                val user = Subject.User(identity.principal, identity.roles, true)
                return Caller(user, connection, sessionToken = null, csrf = null, absoluteExpiresMillis = null, levels = levels)
            }
            return bearerCaller(token, connection)
        }
        val cookie = ctx.cookie(HttpAuth.COOKIE) ?: throw Problems.unauthorized("authentication required")
        return sessionCaller(ctx, cookie, connection)
    }

    private fun bearerCaller(token: String, connection: ConnectionContext): Caller {
        when (val result = security.apiKeys.authenticate(token, connection.address?.hostAddress ?: "", connection.secure)) {
            is AuthenticationResult.Success -> {
                val levels = gw.loginLevels.levels(SecurityLevels.SOURCE_API_KEY, result.subject.principal, emptySet(), emptyMap(), connection)
                return Caller(result.subject, connection, sessionToken = null, csrf = null, absoluteExpiresMillis = null, levels = levels)
            }
            is AuthenticationResult.PasswordExpired -> throw Problems.unauthorized("invalid credentials")
            is AuthenticationResult.Failure -> when (result.reason) {
                AuthFailure.INSECURE_CHANNEL -> throw Problems.forbidden("secure connection required", "insecure-channel")
                AuthFailure.DISABLED -> throw Problems.unauthorized("credential is disabled")
                AuthFailure.BAD_CREDENTIALS -> throw Problems.unauthorized("invalid credentials")
            }
        }
    }

    private fun sessionCaller(ctx: Context, token: String, connection: ConnectionContext): Caller {
        // A background poll is checked and expired like any request; it only does not count as activity.
        val touch = ctx.header(HttpAuth.BACKGROUND_HEADER) != "1"
        when (val state = store.resume(token, idleMillis(), touch)) {
            SessionState.Unknown -> {
                ctx.header("Set-Cookie", HttpAuth.clearCookie(gw.settings.secureCookiesEffective))
                throw Problems.unauthorized("authentication required")
            }
            SessionState.Expired -> {
                ctx.header("Set-Cookie", HttpAuth.clearCookie(gw.settings.secureCookiesEffective))
                throw Problems.unauthorized("session expired", "session-expired")
            }
            is SessionState.Active -> {
                UserSourceApi.sourceOf(state.provider)?.let { sourceName ->
                    // External user source login (SEC-007): the roles mapped at login, while the source stays enabled.
                    val source = security.userSources.get(sourceName)
                    if (source == null || !source.enabled) {
                        store.delete(token)
                        ctx.header("Set-Cookie", HttpAuth.clearCookie(gw.settings.secureCookiesEffective))
                        throw Problems.unauthorized("authentication required")
                    }
                    val levels = security.levels.config().current(state.levels ?: emptySet())
                    return Caller(Subject.User(state.username, state.roles, true), connection, token, state.csrf, state.absoluteExpiresMillis, levels = levels, userSource = sourceName)
                }
                if (state.provider != null) {
                    // Identity-provider login (P1-32): the roles mapped at login, while the provider stays enabled.
                    val provider = security.identityProviders.get(state.provider)
                    if (provider == null || !provider.enabled) {
                        store.delete(token)
                        ctx.header("Set-Cookie", HttpAuth.clearCookie(gw.settings.secureCookiesEffective))
                        throw Problems.unauthorized("authentication required")
                    }
                    val levels = security.levels.config().current(state.levels ?: emptySet())
                    return Caller(Subject.User(state.username, state.roles, true), connection, token, state.csrf, state.absoluteExpiresMillis, state.provider, levels)
                }
                val account = security.users.get(state.username)
                if (account == null || !account.enabled) {
                    store.delete(token)
                    ctx.header("Set-Cookie", HttpAuth.clearCookie(gw.settings.secureCookiesEffective))
                    throw Problems.unauthorized("authentication required")
                }
                if (account.passwordExpired) {
                    store.delete(token)
                    ctx.header("Set-Cookie", HttpAuth.clearCookie(gw.settings.secureCookiesEffective))
                    throw Problems.forbidden("password expired", "password-expired")
                }
                // The levels granted at login (SEC-016). A session whose roles have changed since (or one issued before
                // levels were recorded) is evaluated again, so a removed role never keeps the level it granted.
                val stored = state.levels
                val levels = if (stored != null && state.loginRoles == account.roles) security.levels.config().current(stored)
                else gw.loginLevels.levels(SecurityLevels.SOURCE_INTERNAL, account.username, account.roles, gw.loginLevels.internalAttributes(account.username), connection)
                return Caller(
                    Subject.User(account.username, account.roles, true),
                    connection,
                    token,
                    state.csrf,
                    state.absoluteExpiresMillis,
                    levels = levels,
                )
            }
        }
    }

    private fun checkCsrf(ctx: Context, caller: Caller) {
        val expected = caller.csrf ?: ""
        val presented = ctx.header(HttpAuth.CSRF_HEADER) ?: ""
        if (!org.oip.security.ConstantTime.equal(presented, expected) || presented.isEmpty()) {
            throw Problems.forbidden("CSRF token missing or rejected", "csrf")
        }
    }

    private fun recordLockout(username: String, host: String, view: LockoutView) {
        if (!security.auditProfile.get().enabled) return
        security.audit.append(
            AuditDraft(
                timestampMillis = gw.clock.millis(),
                actor = username,
                action = AuditActions.LOCKOUT,
                status = AuditStatus.FAILURE,
                actorHost = host,
                target = username,
                detail = "failures=${view.failures};retryAfterSeconds=${view.retryAfterSeconds}",
                context = "gateway-http",
            )
        )
    }

    private fun idleMillis(): Long? {
        val minutes = security.gatewaySecurity.get().inactivityTimeoutMinutes
        if (minutes <= 0) return null
        return minutes.toLong() * 60_000L
    }

    private fun idleSeconds(): Int? {
        val minutes = security.gatewaySecurity.get().inactivityTimeoutMinutes
        if (minutes <= 0) return null
        return minutes * 60
    }

    private fun connectionOf(ctx: Context): ConnectionContext {
        val ip = clientIp(ctx)
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull()
        return ConnectionContext(addr, hostName = null, gatewayName = "local", secure = secure(ctx), direct = true, scope = RequestScope.GATEWAY)
    }

    private fun secure(ctx: Context) = ctx.viaScheme(gw).equals("https", ignoreCase = true)

    /** Socket address, unless the peer is a trusted proxy and sent X-Forwarded-For. */
    private fun clientIp(ctx: Context): String =
        ClientAddresses.clientIp(ctx.ip(), ctx.header("X-Forwarded-For"), gw.settings.trustedProxies)

    /**
     * Audit a route-table denial for a mutating HTTPS route, as the [CALLER] that [guard] already resolved.
     * Other routes keep their existing behavior (the 403 only).
     */
    private fun auditDenial(ctx: Context, permission: String) {
        val (action, target) = HttpsEndpoint.writeRoute(ctx.req().method, ctx.path()) ?: return
        val caller = ctx.attribute<Caller>(CALLER) ?: return
        runCatching {
            if (!security.auditProfile.get().enabled) return@runCatching
            security.audit.append(
                AuditDraft(
                    timestampMillis = gw.clock.millis(),
                    actor = caller.subject.principal,
                    action = action,
                    status = AuditStatus.FAILURE,
                    actorHost = clientIp(ctx),
                    target = target,
                    detail = "denied: $permission",
                    context = "gateway-https",
                )
            )
        }
    }

    private fun bearer(header: String): String? {
        val prefix = "Bearer "
        if (!header.startsWith(prefix, ignoreCase = true)) return null
        return header.substring(prefix.length).trim().ifEmpty { null }
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

    private fun intParam(ctx: Context, name: String, default: Int): Int {
        val raw = ctx.queryParam(name) ?: return default
        return raw.toIntOrNull() ?: throw Problems.badRequest("$name must be an integer")
    }

    private fun longParam(ctx: Context, name: String): Long? {
        val raw = ctx.queryParam(name) ?: return null
        return raw.toLongOrNull() ?: throw Problems.badRequest("$name must be epoch milliseconds")
    }

    private sealed interface Access {
        data object Public : Access
        /** An `/api/` path the matrix does not name. Refused for every caller. */
        data object Deny : Access
        data object Authenticated : Access
        /** Needs [permission] and every one of [also]. */
        data class Permission(val permission: String, val also: List<String> = emptyList()) : Access
    }

    private data class Caller(
        val subject: Subject,
        val connection: ConnectionContext,
        val sessionToken: String?,
        val csrf: String?,
        val absoluteExpiresMillis: Long?,
        /** The identity provider of a provider login (P1-32); null for a password login or a bearer credential. */
        val provider: String? = null,
        /** Security levels this caller holds, with ancestors (SEC-016, SEC-017). Never more than `Public` by default. */
        val levels: Set<String> = setOf(SecurityLevels.PUBLIC),
        /** The external user source of the login (SEC-007); null for the internal source, a provider or a bearer credential. */
        val userSource: String? = null,
    )

    companion object {
        private const val CALLER = "oip.caller"
        /** Session user for a project with public HMI. Created on first use, not at install. */
        const val SETUP_PATH = "/api/v1/setup"
        const val SETUP_ADMIN_PATH = "/api/v1/setup/admin"
        private val FIRST_RUN_LOCK = Any()
        const val PUBLIC_USER = "public-hmi"
        const val PUBLIC_ROLE = "public-hmi"
        private val HMI_PROJECT = Regex("^/api/v1/hmi/projects/([^/]+)(?:/.*)?$")
        private const val SCOPE = "oip.auditScope"

        /** Drop the request audit actor. A second call is a no-op. */
        fun releaseAudit(ctx: Context) {
            val scope = ctx.attribute<AutoCloseable>(SCOPE) ?: return
            ctx.req().setAttribute(SCOPE, null)
            scope.close()
        }
        private val UNSAFE = setOf("POST", "PUT", "PATCH", "DELETE")

        private fun unsafe(method: String) = method.uppercase() in UNSAFE

        private val LOGIN_NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        private val EXECUTE = Regex("^/api/v1/db/queries/[^/]+/execute$")
        private val IDP_LOGIN = Regex("^/api/v1/auth/providers/[^/]+/(login|callback|test-logout|test-result)$")
        private val IDP_ACS = Regex("^/api/v1/auth/providers/[^/]+/acs$")
        private val USER_SOURCE_CHECK = Regex("^/api/v1/security/user-sources/[^/]+/check$")
        private val DESIGNER_LOCK_ITEM = Regex("^/api/v1/designer/projects/[^/]+/locks/[^/]+/[^/]+/[^/]+$")
        private val USER_SOURCE = Regex("^/api/v1/security/user-sources/[^/]+$")
        private val REPORT_FILES = Regex("^/api/v1/reports/[^/]+/files(/[^/]+)?$")
        private val REPORT_PREVIEW = Regex("^/api/v1/reports/preview(/pdf)?$")

        /** The authenticated caller. Named-query execution uses [HttpPrincipal.roles]. */
        fun principal(ctx: Context): HttpPrincipal {
            val caller = ctx.attribute<Caller>(CALLER) ?: throw Problems.unauthorized("authentication required")
            return when (val subject = caller.subject) {
                is Subject.User -> HttpPrincipal(subject.username, subject.roles)
                is Subject.ApiKey -> HttpPrincipal(subject.keyName, emptySet())
            }
        }

        /**
         * A second permission check inside a handler, for routes whose response mixes two kinds of data.
         * The route rule in [accessFor] has already passed, so the caller is resolved.
         */
        fun requirePermission(ctx: Context, authorizer: org.oip.security.Authorizer, permission: String) {
            val caller = ctx.attribute<Caller>(CALLER) ?: throw Problems.unauthorized("authentication required")
            val decision = authorizer.authorize(caller.subject, permission, caller.connection)
            if (!decision.allowed) throw Problems.forbidden("$permission denied (${decision.reason})")
        }

        /**
         * The authenticated caller as a tag-engine user (TAG-013): the user's roles (none for an API key, see
         * [TagCaller.of]) and the security zone its connection matches, for the per-tag permission checks.
         */
        fun tagCaller(ctx: Context, zones: ZoneDirectory): TagCaller.User = tagCaller(ctx, zones, null)

        /**
         * [tagCaller], with [TagCaller.User.configAdmin] set when [authorizer] grants the caller `gateway.write`
         * (the permission that edits tag configuration) in its zone. History and alarm journal queries use it, so
         * rows of a deleted tag stay readable to those who manage tags (S-1).
         */
        fun tagCaller(ctx: Context, zones: ZoneDirectory, authorizer: org.oip.security.Authorizer?): TagCaller.User {
            val caller = ctx.attribute<Caller>(CALLER) ?: throw Problems.unauthorized("authentication required")
            val configAdmin = authorizer?.authorize(caller.subject, Permissions.GATEWAY_WRITE, caller.connection)?.allowed == true
            return TagCaller.of(caller.subject, zones.match(caller.connection), caller.connection.address?.hostAddress ?: "", configAdmin)
        }

        /** The authenticated subject and its connection, for routes that need effective permissions (reports). */
        fun subject(ctx: Context): Pair<Subject, ConnectionContext> {
            val caller = ctx.attribute<Caller>(CALLER) ?: throw Problems.unauthorized("authentication required")
            return caller.subject to caller.connection
        }

        /**
         * The identity provider the caller logged in through (P1-32), or null for a password login or a bearer
         * credential. HMI session security (HMP-092) checks it against a project's `sessionIdentityProvider`.
         */
        fun provider(ctx: Context): String? = ctx.attribute<Caller>(CALLER)?.provider

        /** The security levels the caller holds (SEC-017), with ancestors; only `Public` when the caller is unknown. */
        fun levels(ctx: Context): Set<String> = ctx.attribute<Caller>(CALLER)?.levels ?: setOf(SecurityLevels.PUBLIC)

        /** Where the request of [ctx] comes from, as the auth filter sees it: client address, security, scope. */
        fun connection(ctx: Context, gw: Gateway): ConnectionContext {
            val ip = ClientAddresses.clientIp(ctx.ip(), ctx.header("X-Forwarded-For"), gw.settings.trustedProxies)
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull()
            return ConnectionContext(addr, hostName = null, gatewayName = "local", secure = ctx.viaScheme(gw).equals("https", ignoreCase = true), direct = true, scope = RequestScope.GATEWAY)
        }

        /** Stable short key so a huge or illegal username cannot be stored raw in the lockout table. */
        private fun invalidBucket(username: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(username.toByteArray(Charsets.UTF_8))
            return "invalid-" + HexFormat.of().formatHex(digest).take(32)
        }

        private fun accessFor(method: String, rawPath: String): Access {
            val path = if (rawPath.length > 1 && rawPath.endsWith('/')) rawPath.trimEnd('/') else rawPath
            val verb = method.uppercase()
            if (path == "/api/docs" || path == "/api/v1/health") return Access.Public
            if (path == "/api/v1/https" && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_READ)
            if (path.startsWith("/api/v1/https/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            if (path == "/api/v1/https") return Access.Permission(Permissions.GATEWAY_WRITE)
            if (path == "/api/v1/gateway-network" || path.startsWith("/api/v1/gateway-network/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Redundancy status is readable. Failover, assume, and resync change which node is active.
            // The reference-architecture catalog is a read of this gateway's live settings.
            if (path == "/api/v1/architectures" && (verb == "GET" || verb == "HEAD")) {
                return Access.Permission(Permissions.GATEWAY_READ)
            }
            // The Connections list (GAT-022) reads live connection status: a gateway read.
            if (path == ConnectionsApi.PATH && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_READ)
            // Email profiles (GAT-030): reads are gateway.read; saving, deleting and the test send need gateway.write.
            if (path == EmailProfileApi.PATH || path.startsWith("${EmailProfileApi.PATH}/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            if (path == "/api/v1/redundancy" || path.startsWith("/api/v1/redundancy/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Host OS for the PLC banner. A read of this gateway, not of the browser.
            if (path == "/api/v1/platform" && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_READ)
            // PLC / ERIC resource split. Reads are gateway.read. Apply and revert are gateway.write; the root helper does the write.
            if (path == "/api/v1/plc/resources" || path.startsWith("/api/v1/plc/resources/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Logic programs, mapping, deploy, and forces. Reads are gateway.read; changes are gateway.write.
            if (path == "/api/v1/logic" || path.startsWith("/api/v1/logic/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Secrets (SEC-023): the list is names and references. The value is never returned.
            if (path == "/api/v1/secrets" || path.startsWith("/api/v1/secrets/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Edge sync status and the forwarded rows. A manual pass still respects the schedule and the link.
            if (path == "/api/v1/edge-sync" || path.startsWith("/api/v1/edge-sync/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Enterprise administration (EAM-001..EAM-017): status, agents, tasks and runs are readable with gateway.read;
            // every change needs gateway.write. Backup, restore and recovery also check backups.admin, and module or
            // upgrade packages modules.admin, inside the handler (EamApi).
            if (path == "/api/v1/eam" || path.startsWith("/api/v1/eam/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // The active deployment mode is gateway configuration: reading needs gateway.read, switching gateway.write.
            if (path == "/api/v1/deployment-mode") {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Gateway-wide settings (GAT-035): a gateway configuration read and write, like the config API it fronts.
            // Doc source: FEATURES GAT-035.
            if (path == PlatformSettingsService.PATH) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            if (path == "/api/v1/auth/login" && verb == "POST") return Access.Public
            // First-run setup checks its own conditions (no users yet, loopback, same origin).
            if (path == SETUP_PATH && (verb == "GET" || verb == "HEAD")) return Access.Public
            if (path == SETUP_ADMIN_PATH && verb == "POST") return Access.Public
            // Identity-provider login (SEC-013): the provider list, the login start, the OIDC redirect back and the SAML
            // assertion consumer are reached before any session exists. Each checks its own state (IdpLogin).
            // Doc source: FEATURES SEC-013.
            if (path == IdpLogin.PROVIDERS && (verb == "GET" || verb == "HEAD")) return Access.Public
            if (IDP_LOGIN.matches(path) && (verb == "GET" || verb == "HEAD")) return Access.Public
            if (IDP_ACS.matches(path) && verb == "POST") return Access.Public
            if (path == "/api/v1/auth/logout" && verb == "POST") return Access.Authenticated
            if (path == "/api/v1/auth/session" && verb == "GET") return Access.Authenticated
            if (path == "/api/v1/audit" && verb == "GET") return Access.Permission(Permissions.AUDIT_READ)
            // Alarm status is a tag read. Ack and shelve are tag writes, which the operator role holds
            // and the viewer role does not.
            if (path == "/api/v1/alarms" && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.TAGS_READ)
            if ((path == "/api/v1/alarms/ack" || path == "/api/v1/alarms/shelve" || path == "/api/v1/alarms/unshelve") && verb == "POST") {
                return Access.Permission(Permissions.TAGS_WRITE)
            }
            // The alarm journal is a read of alarm history, like the live list. Retention changes and purges
            // delete history, so they are gateway configuration writes (and are audited by AlarmApi).
            if ((path == "/api/v1/alarms/journal" || path == "/api/v1/alarms/journal/settings") && (verb == "GET" || verb == "HEAD")) {
                return Access.Permission(Permissions.TAGS_READ)
            }
            if (path == "/api/v1/alarms/journal/settings" && verb == "PUT") return Access.Permission(Permissions.GATEWAY_WRITE)
            if (path == "/api/v1/alarms/journal/purge" && verb == "POST") return Access.Permission(Permissions.GATEWAY_WRITE)
            // Journal write health (review-3 F5) is a diagnostics read: queue, retries and lost rows, no alarm data.
            if (path == JOURNAL_DIAGNOSTICS && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_READ)
            // Tag value reads, tag browse, and tag history queries need tags.read. Browse (PR #54) and
            // history (PR #52) are matched here before their routes land. Tag value writes need tags.write;
            // each tag's own read and write permissions are then checked for the caller (TagApi).
            if ((path == "/api/v1/tags/read" || path == "/api/v1/tags/browse" || path == "/api/v1/tags/search") && verb == "POST") return Access.Permission(Permissions.TAGS_READ)
            // Tag diagnostics (TAG-003) is a tag read: value, subscribers, references, group and deadband counters.
            if (path == TagApi.DIAGNOSTICS && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.TAGS_READ)
            if (path == "/api/v1/tags/write" && verb == "POST") return Access.Permission(Permissions.TAGS_WRITE)
            // Tag configuration export and import (TAG-005) read and write Tag and Udt config resources, like the config
            // API: gateway.read to export, gateway.write to import.
            if (path == TagExchangeApi.EXPORT && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_READ)
            if (path == TagExchangeApi.IMPORT && verb == "POST") return Access.Permission(Permissions.GATEWAY_WRITE)
            if ((path == "/api/v1/history" || path.startsWith("/api/v1/history/")) && (verb == "GET" || verb == "HEAD")) {
                return Access.Permission(Permissions.TAGS_READ)
            }
            // The link in a two-way notification email is the credential. It does not require a gateway role.
            if (path.startsWith("/api/v1/alarms/email-ack/") && (verb == "GET" || verb == "HEAD" || verb == "POST")) {
                return Access.Public
            }
            // Roster and schedule resolution are gateway configuration reads. Writes stay on the config API.
            if (path == "/api/v1/notifications" || path.startsWith("/api/v1/notifications/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Database connections are gateway configuration. Running a named query is authenticated,
            // then the query's own role list is checked.
            // Chart instance control follows tag security. Stream metrics are gateway configuration.
            // UDT wizard (TAG-024). Listing definitions is a tag read. Creating instances is a tag mutation and
            // takes tags.write, like alarm ack/shelve and chart control. A dry run is held to the same permission
            // because it reports which tag paths and config resources exist.
            if (path == "/api/v1/tags/udts" && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.TAGS_READ)
            // What a session reads to run a project (review-4 F5, HmiProjectApi): the same tags.read that opens the
            // session socket, not gateway.read. It returns only the launchable project's views, page configuration
            // and style classes.
            if ((path == HmiProjectApi.LIST || (path.startsWith(HmiProjectApi.LIST + "/") && path.count { it == '/' } == 5)) &&
                (verb == "GET" || verb == "HEAD")
            ) {
                return Access.Permission(Permissions.TAGS_READ)
            }
            // HTTP binding fetch (HMP-114): a session reads through a stored HttpSource; the gateway checks the project.
            if (path.startsWith(HmiProjectApi.LIST + "/") && path.contains("/http/") && verb == "POST") return Access.Permission(Permissions.TAGS_READ)
            // File upload to a script handler (HMP-047): a session sends a file; the handler's own `upload` limits, the project and hook security, and a signed-in user are checked in HmiUploadApi.
            if (path.startsWith(HmiProjectApi.LIST + "/") && path.contains("/upload/") && verb == "POST") return Access.Permission(Permissions.TAGS_READ)
            // A session image is the same read as opening the project. Management stays on /api/v1/projects.
            if (path.startsWith("/api/v1/hmi/projects/") && path.contains("/images/") && (verb == "GET" || verb == "HEAD")) {
                return Access.Permission(Permissions.TAGS_READ)
            }
            // The live session list is gateway configuration, not a session's own tag read.
            if (path == "/api/v1/hmi/sessions" && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_READ)
            // HMI session socket (M4.1): tag values; each write on it also needs tags.write (SessionHost).
            if (path == "/api/v1/ws" && verb == "GET") return Access.Permission(Permissions.TAGS_READ)
            if (path == "/api/v1/tags/udt-instances" && verb == "POST") return Access.Permission(Permissions.TAGS_WRITE)
            if (path == "/api/v1/sfc/charts" && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.TAGS_READ)
            if (path.startsWith("/api/v1/sfc/charts/") && path.endsWith("/start") && verb == "POST") {
                return Access.Permission(Permissions.TAGS_WRITE)
            }
            if (path == "/api/v1/sfc/instances" && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.TAGS_READ)
            if (path.startsWith("/api/v1/sfc/instances/") && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.TAGS_READ)
            if (path.startsWith("/api/v1/sfc/instances/") && verb == "POST") return Access.Permission(Permissions.TAGS_WRITE)
            if (path == "/api/v1/event-streams" || path.startsWith("/api/v1/event-streams/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Reports read plant data (tag history, named queries): running one and reading its run history need
            // tags.read. Each tag of a history source is then checked again for the caller (ReportEngine), and each
            // named query applies its own role list. Definitions are config resources (gateway.read / gateway.write).
            // POST .../{name}/pdf is a run too. Archived scheduled PDFs (.../{name}/files[/{file}]) were run as
            // system, unfiltered for the reader, so they need gateway.read.
            // Previews of a draft definition (POST /api/v1/reports/preview[/pdf]) run whatever SQL or named query the draft
            // names, so they need gateway.write, like saving the definition.
            if (REPORT_PREVIEW.matches(path)) return Access.Permission(Permissions.GATEWAY_WRITE)
            if (REPORT_FILES.matches(path)) return Access.Permission(Permissions.GATEWAY_READ)
            if (path == "/api/v1/reports" || path.startsWith("/api/v1/reports/")) return Access.Permission(Permissions.TAGS_READ)
            // DAT-016: an uploaded JDBC driver is code that runs inside the gateway once a connection opens it, so upload and
            // removal need the same permission as installing a module. Listing the drivers is a gateway read.
            if (path == "/api/v1/db/drivers" || path.startsWith("/api/v1/db/drivers/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.MODULES_ADMIN)
            }
            // Per-driver defaults and SQL translators are configuration (DAT-027, DAT-016).
            if (path == "/api/v1/db/driver-settings" || path.startsWith("/api/v1/db/driver-settings/") ||
                path == "/api/v1/db/translators" || path.startsWith("/api/v1/db/translators/")
            ) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            if (path == "/api/v1/db/connections" || path.startsWith("/api/v1/db/connections/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Transaction group status is gateway configuration, like connections. Enable and disable are writes.
            if (path == "/api/v1/db/groups" || path.startsWith("/api/v1/db/groups/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            if (path == "/api/v1/db/queries" || path.startsWith("/api/v1/db/queries/")) {
                if (verb == "POST" && EXECUTE.matches(path)) return Access.Authenticated
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // C-7: OPC UA client-certificate trust and Modbus address-range tag generation. Changing trust
            // decides which clients may open a secure channel, so every change needs gateway.write. A mapping
            // preview writes nothing and needs gateway.read; generating tags is a config write.
            if (path == "/api/v1/opcua/certificates" || path.startsWith("/api/v1/opcua/certificates/") ||
                path == "/api/v1/modbus/address-ranges" || path.startsWith("/api/v1/modbus/address-ranges/")
            ) {
                val read = verb == "GET" || verb == "HEAD" || (verb == "POST" && path == "/api/v1/modbus/address-ranges/preview")
                return Access.Permission(if (read) Permissions.GATEWAY_READ else Permissions.GATEWAY_WRITE)
            }
            // C-16 tag browser. The provider list is a tag read. The device list is gateway configuration. A device
            // browse talks to the device and returns raw values no tag permission covers, so it needs gateway.write,
            // like creating the tags it is for.
            if (path == TagDeviceApi.PROVIDERS && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.TAGS_READ)
            if (path == TagDeviceApi.DEVICES && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_READ)
            if (path == TagDeviceApi.BROWSE && verb == "POST") return Access.Permission(Permissions.GATEWAY_WRITE)
            // Review-4 F4 (SEC-020): project design resources (views, page configuration, style classes, script
            // hooks) are Designer edits, so a write needs designer.login (the gateway's designer roles) as well as
            // gateway.write. The config batch and project import apply the same rule per resource
            // (SecurityResourceGuard.checkWrite). Reads stay gateway.read.
            // The designer script console runs gateway Python as the signed-in designer.
            // Designer sessions (GAT-028): the list is a gateway read; opening, keeping alive and closing a session is a
            // Designer action (the handler also limits heartbeat and close to the session's own user). The launcher
            // download is the Designer's address, so it needs gateway.write like the Designer link on the home page.
            if (path == DesignerSessionApi.PATH && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_READ)
            if (path == DesignerSessionApi.PATH || path.startsWith("${DesignerSessionApi.PATH}/")) {
                return Access.Permission(Permissions.GATEWAY_WRITE, listOf(Permissions.DESIGNER_LOGIN))
            }
            if (path == DesignerSessionApi.LAUNCHER && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_WRITE)
            // SEC-020: the caller's project access and the lock list are reads; taking a lock and protecting a
            // resource are Designer actions. Releasing a lock is gateway.write: the holder also needs designer.login
            // and breaking another user's lock needs users.admin (both checked in DesignerProjectApi), so an
            // administrator without designer roles can still free a resource.
            // Doc source: FEATURES SEC-020.
            if (path.startsWith(DesignerProjectApi.BASE + "/")) {
                if (verb == "GET" || verb == "HEAD") return Access.Permission(Permissions.GATEWAY_READ)
                if (verb == "DELETE" && DESIGNER_LOCK_ITEM.matches(path)) return Access.Permission(Permissions.GATEWAY_WRITE)
                return Access.Permission(Permissions.GATEWAY_WRITE, listOf(Permissions.DESIGNER_LOGIN))
            }
            if (path == "/api/v1/designer/console" && verb == "POST") {
                return Access.Permission(Permissions.GATEWAY_WRITE, listOf(Permissions.DESIGNER_LOGIN))
            }
            if (path.startsWith("/api/v1/config/$DESIGNER_MODULE/") && verb != "GET" && verb != "HEAD") {
                return Access.Permission(Permissions.GATEWAY_WRITE, listOf(Permissions.DESIGNER_LOGIN))
            }
            if (path == "/api/v1/config" || path.startsWith("/api/v1/config/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            if (path == "/api/v1/schemas" || path.startsWith("/api/v1/schemas/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            if (path == "/api/v1/modules" || path.startsWith("/api/v1/modules/")) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.MODULES_ADMIN)
            }
            // User, role, and API-key administration. Only the administrator role holds these permissions.
            if (path == "/api/v1/security/users" || path.startsWith("/api/v1/security/users/")) {
                return Access.Permission(Permissions.USERS_ADMIN)
            }
            // Identity providers decide which roles their users get, and the user source check answers whether an
            // account exists and a password matches, so both are user administration (SEC-014, SEC-027).
            // Doc source: FEATURES SEC-014.
            // Doc source: FEATURES SEC-027.
            if (path == IdentityProviderApi.BASE || path.startsWith(IdentityProviderApi.BASE + "/")) {
                return Access.Permission(Permissions.USERS_ADMIN)
            }
            if (USER_SOURCE_CHECK.matches(path) && verb == "POST") return Access.Permission(Permissions.USERS_ADMIN)
            // SEC-007, SEC-010, SEC-021, SEC-025: user sources decide who can log in and with which roles, and hold a
            // directory service account, so reading and changing them is user administration too.
            // Doc source: FEATURES SEC-007.
            if (path == UserSourceApi.BASE && (verb == "GET" || verb == "HEAD" || verb == "POST")) return Access.Permission(Permissions.USERS_ADMIN)
            if (USER_SOURCE.matches(path) && verb in setOf("GET", "HEAD", "PUT", "DELETE")) return Access.Permission(Permissions.USERS_ADMIN)
            // SEC-016: trying the security level rules on entered login facts shows which levels a login would get,
            // which is user administration like the identity providers that feed the rules.
            // Doc source: FEATURES SEC-016.
            if (path == LoginLevels.EVALUATE && verb == "POST") return Access.Permission(Permissions.USERS_ADMIN)
            // SEC-022: the outbound TLS trust store decides which servers the gateway's own TLS clients (database,
            // MQTT, HTTP, mail, identity providers) accept, so every change needs gateway.write; reading is gateway.read.
            // Doc source: FEATURES SEC-022.
            // SEC-029: OAuth2 client registrations are outbound-service configuration like the email profiles that
            // use them: reading is gateway.read, every change, token check and authorization start is gateway.write.
            // The identity provider's redirect back carries no session cookie (SameSite=Strict), so the callback is
            // public and is accepted only with the single-use state its authorization started.
            // Doc source: FEATURES SEC-029.
            if (OAuth2ClientApi.CALLBACK.matches(path) && (verb == "GET" || verb == "HEAD")) return Access.Public
            if (path == OAuth2ClientApi.BASE || path.startsWith(OAuth2ClientApi.BASE + "/")) {
                return Access.Permission(if (verb == "GET" || verb == "HEAD") Permissions.GATEWAY_READ else Permissions.GATEWAY_WRITE)
            }
            if (path == OutboundTrustApi.BASE || path.startsWith(OutboundTrustApi.BASE + "/")) {
                return Access.Permission(if (verb == "GET" || verb == "HEAD") Permissions.GATEWAY_READ else Permissions.GATEWAY_WRITE)
            }
            if (path == "/api/v1/security/roles" || path.startsWith("/api/v1/security/roles/")) {
                return Access.Permission(Permissions.ROLES_ADMIN)
            }
            if (path == "/api/v1/security/api-keys" || path.startsWith("/api/v1/security/api-keys/")) {
                return Access.Permission(Permissions.APIKEYS_ADMIN)
            }
            // A backup archive holds the secrets key, the secret store, and every user and role, and a restore
            // replaces them, so create, download, and restore need backups.admin (review-1 F2). The listing (names
            // and sizes) is a gateway read.
            if (path == "/api/v1/backups" && (verb == "GET" || verb == "HEAD")) return Access.Permission(Permissions.GATEWAY_READ)
            if (path == "/api/v1/backups" || path.startsWith("/api/v1/backups/")) return Access.Permission(Permissions.BACKUPS_ADMIN)
            // Gateway home page (GAT-023): any signed-in caller reads it; the document is filtered by the caller's
            // permissions (HomeApi). Marking first-run setup finished is a gateway write.
            if (path == HomeApi.PATH && (verb == "GET" || verb == "HEAD")) return Access.Authenticated
            if (path == HomeApi.SETUP_PATH && verb == "PUT") return Access.Permission(Permissions.GATEWAY_WRITE)
            // Quarantine export is a GET so the browser can save the file, and it needs gateway.write.
            // The diagnostics prefix below would otherwise treat every GET as gateway.read.
            // The handler also requires tags.read (the file contains tag values).
            if (path == QUARANTINE_EXPORT_PATH) return Access.Permission(Permissions.GATEWAY_WRITE)
            // OPC UA client connection status (DEV-016): session, subscription, and monitored-item counts.
            // A read is gateway.read, the same rule as the other diagnostics GETs.
            if (path == "/api/v1/diagnostics/opcua/connections") {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Projects (PRO-003, PRO-004): listing and settings reads need gateway.read; create, settings changes,
            // rename, copy, delete, and import need gateway.write.
            if (path == "/api/v1/examples" || path.startsWith("/api/v1/examples/") ||
                path == "/api/v1/projects" || path.startsWith("/api/v1/projects/") ||
                path == "/api/v1/logs" || path.startsWith("/api/v1/logs/") ||
                path == "/api/v1/diagnostics" || path.startsWith("/api/v1/diagnostics/")
            ) {
                return if (verb == "GET" || verb == "HEAD") Access.Permission(Permissions.GATEWAY_READ)
                else Access.Permission(Permissions.GATEWAY_WRITE)
            }
            // Deny by default (review-1 F6): a new route is closed until it gets a rule above.
            if (path.startsWith("/api/")) return Access.Deny
            return Access.Public
        }

        /**
         * The rule [accessFor] applies to [method] [path], as text, or null when the path falls through to the
         * default deny. RouteMatrixTest checks every registered route against this.
         */
        internal fun explicitRule(method: String, path: String): String? = when (val access = accessFor(method, path)) {
            Access.Deny -> null
            Access.Public -> if (path.startsWith("/api/")) "public" else null
            Access.Authenticated -> "authenticated"
            is Access.Permission -> (listOf(access.permission) + access.also).joinToString("+")
        }
    }
}
