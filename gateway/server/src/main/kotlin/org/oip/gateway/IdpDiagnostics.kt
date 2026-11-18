package org.oip.gateway

import org.oip.security.IdentityProvider
import java.time.Instant

/**
 * What a failed or stalled identity provider test login most likely means (SEC-032), for the administrator who ran
 * it. A refusal reason ([IdpRejected.reason]) and the facts the check recorded ([IdpRejected.facts]: the times it
 * compared, the value it expected and the one it was given, an OAuth error code) become a short diagnosis with the
 * setting to look at. The common cases have their own codes:
 *
 * - `clock-skew`: a token or assertion is outside its validity window by more than the allowed skew. The facts give
 *   the provider's time, the gateway's time and the difference.
 * - `redirect-mismatch`: the provider answered for another callback address (SAML Destination or Recipient), or
 *   refused the redirect address (an OAuth `redirect_uri` or `invalid_grant` answer), or never sent the browser back.
 * - `metadata`: the signature, key, certificate or issuer does not match what the provider's metadata gave; import
 *   the metadata or discovery document again.
 * - `audience`, `client-credentials`, `provider-error`, `browser-state`, `claims`: the audience or entity ID, the client
 *   ID or secret, another error the provider reported, the login's own state, and a missing username claim.
 *
 * Values the provider sent are shown only after they are cut to printable text of bounded length. Nothing here is a
 * secret: no client secret, code or token is ever a fact.
 *
 * Doc source: FEATURES SEC-032.
 */
object IdpDiagnostics {
    data class Diagnosis(val code: String, val summary: String, val hint: String, val facts: Map<String, Any?>) {
        fun view(): Map<String, Any?> = linkedMapOf("code" to code, "summary" to summary, "hint" to hint, "facts" to facts)
    }

    /** How long a started test may wait for the provider before the result says it probably never returned. */
    const val NO_RETURN_MILLIS = 60_000L

    fun forFailure(reason: String, facts: Map<String, Any?>, p: IdentityProvider?, callback: String): Diagnosis {
        val skew = p?.clockSkewSeconds ?: 0
        val saml = p?.type == IdentityProvider.SAML
        val all = LinkedHashMap(facts)
        if (callback.isNotEmpty()) all.putIfAbsent(if (saml) "gatewayAcsUrl" else "gatewayRedirectUri", callback)
        val error = facts["providerError"] as? String
        return when {
            reason in CLOCK -> Diagnosis(
                "clock-skew",
                "The ${if (saml) "assertion" else "token"} is outside its validity time as the gateway's clock reads it.",
                "Check that the gateway and the provider keep the same time (NTP). The allowed difference is ${skew}s (clockSkewSeconds); raise it only " +
                    "if the clocks cannot be brought closer.",
                all + mapOf("allowedSkewSeconds" to skew),
            )
            reason == "destination mismatch" || reason.startsWith("no bearer confirmation") || error == "redirect_uri_mismatch" ||
                (error == "invalid_request" && facts["providerErrorAbout"] == "redirect_uri") || error == "invalid_grant" -> Diagnosis(
                "redirect-mismatch",
                if (saml) "The provider sent the assertion for another assertion consumer address." else "The provider did not accept the gateway's redirect address.",
                "Register exactly ${callback.ifEmpty { "the callback address shown on the provider page" }} at the provider. If the gateway is reached " +
                    "through a proxy or another host name, set redirectBaseUrl to the address users open.",
                all,
            )
            reason in METADATA || reason.startsWith("token algorithm") || reason.startsWith("provider keys") -> Diagnosis(
                "metadata",
                "The provider's signature, key or issuer does not match the provider settings.",
                if (saml) "Import the provider's current metadata again: its signing certificate or entity ID may have changed."
                else "Import the discovery document again and check the issuer and the allowed algorithms; the provider may have rotated its keys.",
                all,
            )
            reason == "audience mismatch" || reason == "azp does not match the client" -> Diagnosis(
                "audience",
                if (saml) "The assertion is for another service provider entity ID." else "The token was issued for another client.",
                if (saml) "Set the gateway entity ID (spEntityId) to the one registered at the provider." else "Set the client ID to the one the provider registered for the gateway.",
                all,
            )
            reason.startsWith("token exchange failed") && (error == "invalid_client" || error == "unauthorized_client" || facts["httpStatus"] == 401) -> Diagnosis(
                "client-credentials",
                "The provider refused the gateway's client ID or secret at the token endpoint.",
                "Check the client ID, enter the client secret again, and check that the client is allowed the authorization code grant.",
                all,
            )
            reason == "provider returned an error" || reason.startsWith("token exchange failed") || reason.startsWith("provider returned status") -> Diagnosis(
                "provider-error",
                "The provider reported an error${error?.let { " ($it)" } ?: ""}.",
                when (error) {
                    "invalid_scope" -> "Remove the scopes the provider does not offer to this client."
                    "access_denied" -> "The user, or the provider's policy, refused the login; check the user's assignment to this application."
                    else -> "Read the provider's own log for this login; the error code says which setting it refused."
                },
                all,
            )
            reason in STATE -> Diagnosis(
                "browser-state",
                "The browser came back without the login it started.",
                "Run the test again in one browser window and finish it within a few minutes; a blocked cookie also causes this.",
                all,
            )
            reason.contains("claim") || reason.contains("attribute") || reason.contains("NameID") || reason == "token has no sub" -> Diagnosis(
                "claims",
                "The provider's answer has no usable user name.",
                "Check the username claim (usernameClaim) against the claims or attributes the provider sends.",
                all,
            )
            else -> Diagnosis("other", "The login was refused: $reason.", "The reason names the check that failed.", all)
        }
    }

    /** A test whose browser went to the provider and has not come back after [NO_RETURN_MILLIS]. */
    fun forNoReturn(p: IdentityProvider?, callback: String, waitedMillis: Long): Diagnosis = Diagnosis(
        "redirect-mismatch",
        "The provider has not sent the browser back to the gateway.",
        "If the provider showed an error page, the redirect address registered there probably differs from $callback, or the client is not " +
            "allowed to use it. Register that address exactly, or set redirectBaseUrl to the address users open.",
        linkedMapOf(
            (if (p?.type == IdentityProvider.SAML) "gatewayAcsUrl" else "gatewayRedirectUri") to callback,
            "waitedSeconds" to waitedMillis / 1000,
        ),
    )

    /** Facts for a time check: the provider's time, the gateway's, and how far apart they are (positive: gateway ahead). */
    fun timeFacts(providerMillis: Long, gatewayMillis: Long): Map<String, Any?> = linkedMapOf(
        "providerTime" to Instant.ofEpochMilli(providerMillis).toString(),
        "gatewayTime" to Instant.ofEpochMilli(gatewayMillis).toString(),
        "differenceSeconds" to (gatewayMillis - providerMillis) / 1000,
    )

    /** A value the provider sent, cut to printable ASCII of at most [max] characters. */
    fun shown(value: Any?, max: Int = 200): String? {
        val text = value?.toString() ?: return null
        return text.filter { it in ' '..'~' }.take(max).ifEmpty { null }
    }

    /** An OAuth error code ([RFC 6749] grammar), or null. */
    fun errorCode(value: Any?): String? = (value as? String)?.takeIf { ERROR_CODE.matches(it) }

    private val ERROR_CODE = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val CLOCK = setOf("token expired", "token not yet valid", "token issued in the future", "assertion not yet valid", "assertion expired")
    private val METADATA = setOf(
        "SAML signature is missing or invalid", "provider has no usable signing certificate", "issuer mismatch",
        "no provider key matches the token", "token signature is invalid",
    )
    private val STATE = setOf(
        "missing state", "unknown or used state", "state is for another provider", "login expired", "login was started in another browser",
        "missing RelayState", "InResponseTo mismatch",
    )
}
