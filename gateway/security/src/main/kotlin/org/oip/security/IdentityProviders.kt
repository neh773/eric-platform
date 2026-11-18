package org.oip.security

/**
 * External identity providers (SEC-013, SEC-014): OIDC and SAML provider resources, `org.oip.security/IdentityProvider`.
 *
 * A provider resource holds the endpoints, the client id, role mappings and per-provider settings. It never holds a
 * client secret: the secret lives in the secret store and the resource names it as a `secret://` reference
 * ([IdentityProvider.clientSecretRef]). The schema has no other secret field, and unknown fields are rejected, so a
 * plaintext secret cannot be written into the resource.
 *
 * Doc source: FEATURES SEC-013.
 * Doc source: FEATURES SEC-014.
 * Doc source: FEATURES SEC-015.
 */
object IdentityProviderSchema {
    private const val NAME = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"

    fun schema(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "type" to mapOf("type" to "string", "enum" to listOf(IdentityProvider.OIDC, IdentityProvider.SAML)),
            "displayName" to mapOf("type" to "string", "maxLength" to 200, "default" to ""),
            "enabled" to mapOf("type" to "boolean", "default" to true),
            // Where the browser comes back to. Blank: the scheme and host of the request that started the login.
            "redirectBaseUrl" to url(),
            "usernameClaim" to mapOf("type" to "string", "maxLength" to 200, "default" to ""),
            // SEC-015: the claim or attribute holding the user's groups (mapped to roles by roleMappings), the user's
            // display name and the user's email. A blank name or email claim reads `name` and `email` from an OIDC
            // token and maps nothing from a SAML assertion.
            "rolesClaim" to mapOf("type" to "string", "maxLength" to 200, "default" to ""),
            "nameClaim" to mapOf("type" to "string", "maxLength" to 200, "default" to ""),
            "emailClaim" to mapOf("type" to "string", "maxLength" to 200, "default" to ""),
            "roleMappings" to mapOf(
                "type" to "array",
                "maxItems" to 256,
                "default" to emptyList<Any>(),
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "value" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 500),
                        "roles" to nameList(),
                    ),
                    "required" to listOf("value", "roles"),
                ),
            ),
            "defaultRoles" to nameList(),
            "clockSkewSeconds" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 600, "default" to 60),
            // OIDC
            "issuer" to url(),
            "authorizationEndpoint" to url(),
            "tokenEndpoint" to url(),
            "jwksUri" to url(),
            "endSessionEndpoint" to url(),
            "clientId" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
            "clientSecretRef" to mapOf("type" to "string", "maxLength" to 300, "pattern" to "^(secret://[A-Za-z0-9._-]+)?$", "default" to ""),
            "scopes" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string", "pattern" to "^[\\x21\\x23-\\x5B\\x5D-\\x7E]+$", "maxLength" to 200),
                "uniqueItems" to true,
                "maxItems" to 32,
                "default" to listOf("openid", "profile", "email"),
            ),
            "apiAudience" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
            "allowedAlgorithms" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string", "enum" to IdentityProvider.SIGNING_ALGORITHMS),
                "uniqueItems" to true,
                "minItems" to 1,
                "default" to listOf("RS256", "ES256"),
            ),
            // SAML
            "idpEntityId" to mapOf("type" to "string", "maxLength" to 1000, "default" to ""),
            "ssoUrl" to url(),
            "sloUrl" to url(),
            "spEntityId" to mapOf("type" to "string", "maxLength" to 1000, "default" to ""),
            "signingCertificates" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 16000),
                "maxItems" to 8,
                "default" to emptyList<String>(),
            ),
        ),
        "required" to listOf("type"),
    )

    private fun url() = mapOf("type" to "string", "maxLength" to 2000, "default" to "")
    private fun nameList() = mapOf(
        "type" to "array", "items" to mapOf("type" to "string", "pattern" to NAME),
        "uniqueItems" to true, "maxItems" to 64, "default" to emptyList<String>(),
    )
}

/** One role mapping: a user whose roles claim (or attribute) holds [value] gets [roles]. */
data class RoleMapping(val value: String, val roles: Set<String>)

/** A provider resource as read, with defaults applied. */
data class IdentityProvider(
    val name: String,
    val type: String,
    val displayName: String,
    val enabled: Boolean,
    val redirectBaseUrl: String,
    val usernameClaim: String,
    val rolesClaim: String,
    val nameClaim: String,
    val emailClaim: String,
    val roleMappings: List<RoleMapping>,
    val defaultRoles: Set<String>,
    val clockSkewSeconds: Int,
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val jwksUri: String,
    val endSessionEndpoint: String,
    val clientId: String,
    val clientSecretRef: String,
    val scopes: List<String>,
    val apiAudience: String,
    val allowedAlgorithms: Set<String>,
    val idpEntityId: String,
    val ssoUrl: String,
    val sloUrl: String,
    val spEntityId: String,
    val signingCertificates: List<String>,
    val version: Long,
) {
    val label: String get() = displayName.ifBlank { name }

    /**
     * The roles a user gets from the values of the roles claim: [defaultRoles], plus the roles of every mapping whose
     * value is among [claimValues]. Values match exactly (case-sensitive).
     */
    fun mapRoles(claimValues: Collection<String>): Set<String> {
        val values = claimValues.toSet()
        val out = sortedSetOf<String>()
        out += defaultRoles
        for (m in roleMappings) if (m.value in values) out += m.roles
        return out
    }

    /** Fields an OIDC or SAML provider needs before a login can start, as messages; empty when complete. */
    fun problems(): List<String> = buildList {
        when (type) {
            OIDC -> {
                if (issuer.isBlank()) add("issuer is required")
                if (authorizationEndpoint.isBlank()) add("authorizationEndpoint is required")
                if (tokenEndpoint.isBlank()) add("tokenEndpoint is required")
                if (jwksUri.isBlank()) add("jwksUri is required")
                if (clientId.isBlank()) add("clientId is required")
                if ("openid" !in scopes) add("scopes must include openid")
            }
            SAML -> {
                if (idpEntityId.isBlank()) add("idpEntityId is required")
                if (ssoUrl.isBlank()) add("ssoUrl is required")
                if (spEntityId.isBlank()) add("spEntityId is required")
                if (signingCertificates.isEmpty()) add("signingCertificates needs at least one certificate")
            }
            else -> add("type must be oidc or saml")
        }
    }

    companion object {
        const val OIDC = "oidc"
        const val SAML = "saml"
        /** Asymmetric JWS algorithms only: no `none` and no shared-secret HMAC. */
        val SIGNING_ALGORITHMS = listOf("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512")

        @Suppress("UNCHECKED_CAST")
        fun of(name: String, spec: Map<String, Any?>, version: Long): IdentityProvider = IdentityProvider(
            name = name,
            type = Specs.str(spec, "type") ?: "",
            displayName = Specs.str(spec, "displayName") ?: "",
            enabled = Specs.bool(spec, "enabled", true),
            redirectBaseUrl = Specs.str(spec, "redirectBaseUrl") ?: "",
            usernameClaim = Specs.str(spec, "usernameClaim") ?: "",
            rolesClaim = Specs.str(spec, "rolesClaim") ?: "",
            nameClaim = Specs.str(spec, "nameClaim") ?: "",
            emailClaim = Specs.str(spec, "emailClaim") ?: "",
            roleMappings = (spec["roleMappings"] as? List<*>).orEmpty().mapNotNull { raw ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                RoleMapping(Specs.str(m, "value") ?: return@mapNotNull null, Specs.strings(m, "roles").toSet())
            },
            defaultRoles = Specs.strings(spec, "defaultRoles").toSet(),
            clockSkewSeconds = Specs.int(spec, "clockSkewSeconds", 60),
            issuer = Specs.str(spec, "issuer") ?: "",
            authorizationEndpoint = Specs.str(spec, "authorizationEndpoint") ?: "",
            tokenEndpoint = Specs.str(spec, "tokenEndpoint") ?: "",
            jwksUri = Specs.str(spec, "jwksUri") ?: "",
            endSessionEndpoint = Specs.str(spec, "endSessionEndpoint") ?: "",
            clientId = Specs.str(spec, "clientId") ?: "",
            clientSecretRef = Specs.str(spec, "clientSecretRef") ?: "",
            scopes = if (spec.containsKey("scopes")) Specs.strings(spec, "scopes") else listOf("openid", "profile", "email"),
            apiAudience = Specs.str(spec, "apiAudience") ?: "",
            allowedAlgorithms = (if (spec.containsKey("allowedAlgorithms")) Specs.strings(spec, "allowedAlgorithms") else listOf("RS256", "ES256")).toSet(),
            idpEntityId = Specs.str(spec, "idpEntityId") ?: "",
            ssoUrl = Specs.str(spec, "ssoUrl") ?: "",
            sloUrl = Specs.str(spec, "sloUrl") ?: "",
            spEntityId = Specs.str(spec, "spEntityId") ?: "",
            signingCertificates = Specs.strings(spec, "signingCertificates"),
            version = version,
        )
    }
}

/**
 * Provider resources. Writes go through the config store (so they are audited as config changes and validated against
 * [IdentityProviderSchema]); the REST layer adds the secret handling and the per-type checks.
 */
interface IdentityProviderDirectory {
    fun list(): List<IdentityProvider>
    fun get(name: String): IdentityProvider?
    /** Raw spec with defaults, for the REST view. */
    fun spec(name: String): Map<String, Any?>?
    fun save(name: String, spec: Map<String, Any?>, actor: String, host: String = ""): IdentityProvider
    fun delete(name: String, actor: String, host: String = "")
}

internal class IdentityProviderService(private val docs: ConfigDocs) : IdentityProviderDirectory {
    override fun list(): List<IdentityProvider> = docs.list(Kinds.IDENTITY_PROVIDER).mapNotNull { get(it.metadata.name) }

    override fun get(name: String): IdentityProvider? {
        val r = runCatching { docs.get(Kinds.IDENTITY_PROVIDER, name) }.getOrNull() ?: return null
        val spec = docs.spec(Kinds.IDENTITY_PROVIDER, name) ?: return null
        return IdentityProvider.of(name, spec, r.metadata.version)
    }

    override fun spec(name: String): Map<String, Any?>? = runCatching { docs.spec(Kinds.IDENTITY_PROVIDER, name) }.getOrNull()

    override fun save(name: String, spec: Map<String, Any?>, actor: String, host: String): IdentityProvider {
        docs.modify(Kinds.IDENTITY_PROVIDER, name, actor, host) { spec }
        return get(name)!!
    }

    override fun delete(name: String, actor: String, host: String) = docs.remove(Kinds.IDENTITY_PROVIDER, name, actor, host)
}
