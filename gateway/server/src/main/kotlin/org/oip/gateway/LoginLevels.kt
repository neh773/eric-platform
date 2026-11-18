package org.oip.gateway

import io.javalin.http.Context
import org.oip.security.ConnectionContext
import org.oip.security.LevelGrant
import org.oip.security.LoginFacts
import org.oip.security.SecurityLevels
import org.oip.security.SecurityService
import java.time.Clock

/**
 * Security level rules at login (SEC-016): every login path of the gateway (a password login, an OIDC or SAML login,
 * an identity-provider bearer token, an API key) builds its [LoginFacts] here and gets the levels the rules grant.
 *
 * A session stores the levels granted at its login; a bearer credential is evaluated at every request, since every
 * request is its login. A rule that cannot be read grants nothing, and an error while evaluating grants only `Public`
 * and `Authenticated` (fail closed: no administrator-defined level).
 *
 * `POST /api/v1/security/levels/evaluate` runs the rules over facts an administrator enters, without signing anyone
 * in, so the admin page can show which levels a login would get. It needs `users.admin`.
 *
 * Doc source: FEATURES SEC-016.
 */
class LoginLevels(private val security: () -> SecurityService, private val clock: Clock) {
    /** The levels [facts] earn, with ancestors. */
    fun grant(facts: LoginFacts): LevelGrant = try {
        security().levels.config().evaluate(facts)
    } catch (_: Exception) {
        LevelGrant(SecurityLevels.withAncestors(setOf(SecurityLevels.AUTHENTICATED)), emptyList())
    }

    /** Facts for a login from [source] over [connection]. */
    fun facts(
        source: String,
        username: String,
        roles: Set<String>,
        attributes: Map<String, List<String>>,
        connection: ConnectionContext,
    ): LoginFacts = LoginFacts(
        source = source,
        username = username,
        roles = roles,
        attributes = attributes,
        address = connection.address,
        zone = runCatching { security().zones.match(connection).name }.getOrDefault(""),
        secure = connection.secure,
        at = clock.instant(),
    )

    fun levels(source: String, username: String, roles: Set<String>, attributes: Map<String, List<String>>, connection: ConnectionContext): Set<String> =
        grant(facts(source, username, roles, attributes, connection)).levels

    /** The attributes a password login exposes to the rules: the account's own fields. */
    fun internalAttributes(username: String): Map<String, List<String>> {
        val account = runCatching { security().users.get(username) }.getOrNull() ?: return mapOf("username" to listOf(username))
        return buildMap {
            put("username", listOf(account.username))
            put("roles", account.roles.sorted())
            if (account.displayName.isNotEmpty()) put("displayName", listOf(account.displayName))
            if (account.email.isNotEmpty()) put("email", listOf(account.email))
        }
    }

    fun register(app: io.javalin.Javalin) {
        app.post(EVALUATE) { ctx -> evaluate(ctx) }
    }

    private fun evaluate(ctx: Context) {
        val body = try {
            Json.parse(ctx.body())
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        val source = (body["source"] as? String)?.ifBlank { null } ?: SecurityLevels.SOURCE_INTERNAL
        val username = body["username"] as? String ?: ""
        val roles = (body["roles"] as? List<*>).orEmpty().filterIsInstance<String>().toSet()
        val attributes = (body["attributes"] as? Map<*, *>).orEmpty().entries.associate { (k, v) ->
            k.toString() to when (v) {
                is List<*> -> v.mapNotNull { it?.toString() }
                null -> emptyList()
                else -> listOf(v.toString())
            }
        }
        val address = (body["address"] as? String)?.takeIf { it.isNotBlank() }?.let {
            // Only an address literal: no name is resolved.
            if (!IP_LITERAL.matches(it)) throw Problems.unprocessable("address must be an IP address")
            runCatching { java.net.InetAddress.getByName(it) }.getOrNull() ?: throw Problems.unprocessable("address must be an IP address")
        }
        val at = (body["at"] as? String)?.takeIf { it.isNotBlank() }?.let {
            runCatching { java.time.Instant.parse(it) }.getOrNull() ?: throw Problems.unprocessable("at must be an ISO-8601 instant")
        } ?: clock.instant()
        val secure = body["secure"] as? Boolean ?: false
        val connection = ConnectionContext(address, null, "local", secure, true, org.oip.security.RequestScope.GATEWAY)
        val facts = facts(source, username, roles, attributes, connection).copy(at = at)
        val result = grant(facts)
        ctx.status(200).contentType("application/json").result(
            Json.write(linkedMapOf("levels" to result.levels.sorted(), "rules" to result.rules, "zone" to facts.zone)),
        )
    }

    companion object {
        const val EVALUATE = "/api/v1/security/levels/evaluate"
        private val IP_LITERAL = Regex("^([0-9]{1,3}(\\.[0-9]{1,3}){3}|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*)$")

        /** OIDC claims as rule attributes: text, numbers and booleans as text; lists of those flattened; objects left out. */
        fun claimAttributes(claims: Map<String, Any?>): Map<String, List<String>> = buildMap {
            for ((k, v) in claims) {
                val values = when (v) {
                    is String -> listOf(v)
                    is Number, is Boolean -> listOf(v.toString())
                    is List<*> -> v.mapNotNull { e -> if (e is String || e is Number || e is Boolean) e.toString() else null }
                    else -> null
                } ?: continue
                put(k, values.take(256).map { it.take(500) })
            }
        }
    }
}

/** One audit detail field listing [levels] (paths only; nothing secret). */
internal fun levelsDetail(levels: Set<String>): String = "levels=" + levels.sorted().joinToString(",")
