package org.oip.gateway

import io.javalin.http.Context
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.Authorizer
import org.oip.security.Kinds
import org.oip.security.Permissions
import org.oip.security.SECURITY_MODULE

/**
 * Rules for security-module resources (`org.oip.security`) reached through a generic route: the config API,
 * the config batch, and project export/import (review-1 F1).
 *
 * - Users, roles, and API keys are written only through the `/api/v1/security` routes, which apply the password policy,
 *   hashes passwords, issues key secrets, and audits the change as a user, role, or key action. A generic write is
 *   refused for every caller. A generic read needs the same permission as the security API (`users.admin`,
 *   `roles.admin`, `apikeys.admin`), and the response never carries a password hash, password history, or key hash.
 * - Designer resources (module `designer`) need `designer.login` too (review-4 F4).
 * - The other security settings (zones, gateway security, password policy, audit profile) loosen or tighten the
 *   checks on every account, so a write needs all three security administration permissions as well as the route's
 *   `gateway.write`. They hold no credential, so `gateway.read` reads them.
 * - Resources in a project scope also follow the project's Designer permissions, protected resources and resource
 *   locks (SEC-020, [DesignerProjectGuard]): see [checkProjectWrite], [checkProjectRead] and [mayListResource].
 */
class SecurityResourceGuard(private val authorizer: Authorizer, private val design: DesignerProjectGuard? = null) {
    /** Refuse a write of [module]/[kind] by the caller of [ctx]. */
    fun checkWrite(ctx: Context, module: String, kind: String) {
        // Review-4 F4 (SEC-020): views, page configuration, style classes and HMI script hooks are Designer edits.
        // The route rule covers `/api/v1/config/designer/...`; this covers the config batch, project import, and
        // project rename, copy and delete, which reach the same resources.
        if (module == DESIGNER_MODULE) GatewayAuth.requirePermission(ctx, authorizer, Permissions.DESIGNER_LOGIN)
        if (module != SECURITY_MODULE) return
        CREDENTIAL_ROUTES[kind]?.let { route ->
            throw Problems.forbidden("$kind resources are managed through $route, not the config API")
        }
        for (p in SETTINGS_WRITE) GatewayAuth.requirePermission(ctx, authorizer, p)
    }

    /**
     * SEC-020: refuse writing [id] from [before] to [after] (null deletes it) when the project's Designer permissions,
     * a protected resource, or another Designer's lock forbid it. Call after [checkWrite]; gateway scope passes.
     */
    fun checkProjectWrite(ctx: Context, id: ResourceId, before: Map<String, Any?>?, after: Map<String, Any?>?) {
        design?.checkWrite(ctx, id, before, after)
    }

    /** SEC-020: after a successful write of [id], the writer's own lock on it is released. */
    fun projectWritten(ctx: Context, id: ResourceId) {
        design?.written(ctx, id)
    }

    /** SEC-020: refuse a read of [module] resources in a project [scope] the caller may not view in the Designer. */
    fun checkProjectRead(ctx: Context, module: String, scope: Scope) {
        design?.checkRead(ctx, module, scope)
    }

    /** Whether a listing may show [id]: [mayList] and, for project Designer resources, the project's view roles. */
    fun mayListResource(ctx: Context, id: ResourceId): Boolean =
        mayList(ctx, id.module, id.kind) && (design?.mayList(ctx, id) ?: true)

    /** Refuse a read of one [module]/[kind] resource, or of a [kind] collection, when the caller lacks its permission. */
    fun checkRead(ctx: Context, module: String, kind: String) {
        if (module != SECURITY_MODULE) return
        CREDENTIAL_READ[kind]?.let { GatewayAuth.requirePermission(ctx, authorizer, it) }
    }

    /** Whether the caller may see [module]/[kind] in a listing. Listings drop what [checkRead] would refuse. */
    fun mayList(ctx: Context, module: String, kind: String): Boolean {
        if (module != SECURITY_MODULE) return true
        val permission = CREDENTIAL_READ[kind] ?: return true
        val (subject, connection) = GatewayAuth.subject(ctx)
        return authorizer.authorize(subject, permission, connection).allowed
    }

    companion object {
        /** Secret fields removed from every security resource returned by a generic route. */
        val SECRET_FIELDS: Set<String> = setOf("passwordHash", "passwordHistory", "keyHash", "clientSecret", "clientSecretRef", "refreshToken", "bindPassword", "bindPasswordRef")

        private val CREDENTIAL_ROUTES = mapOf(
            Kinds.USER to "/api/v1/security/users",
            Kinds.ROLE to "/api/v1/security/roles",
            Kinds.API_KEY to "/api/v1/security/api-keys",
            Kinds.IDENTITY_PROVIDER to IdentityProviderApi.BASE,
            Kinds.OAUTH2_CLIENT to OAuth2ClientApi.BASE,
            Kinds.USER_SOURCE to UserSourceApi.BASE,
        )
        private val CREDENTIAL_READ = mapOf(
            Kinds.USER to Permissions.USERS_ADMIN,
            Kinds.ROLE to Permissions.ROLES_ADMIN,
            Kinds.API_KEY to Permissions.APIKEYS_ADMIN,
            Kinds.IDENTITY_PROVIDER to Permissions.USERS_ADMIN,
            Kinds.USER_SOURCE to Permissions.USERS_ADMIN,
        )
        private val SETTINGS_WRITE = listOf(Permissions.USERS_ADMIN, Permissions.ROLES_ADMIN, Permissions.APIKEYS_ADMIN)

        /** Kinds that never leave the gateway in a project archive and are never imported from one. */
        fun isCredential(module: String, kind: String) = module == SECURITY_MODULE && kind in CREDENTIAL_ROUTES

        /** [map] (a resource as a JSON map) without [SECRET_FIELDS] in its spec, when it is a security resource. */
        fun redact(module: String, map: Map<String, Any?>): Map<String, Any?> {
            if (module != SECURITY_MODULE) return map
            val spec = map["spec"] as? Map<*, *> ?: return map
            if (SECRET_FIELDS.none { it in spec }) return map
            return map + ("spec" to spec.filterKeys { it !in SECRET_FIELDS })
        }
    }
}
