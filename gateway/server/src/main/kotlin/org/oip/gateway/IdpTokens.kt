package org.oip.gateway

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** A login or token refused by an identity-provider check. [reason] is short, safe to audit, and has no token in it. */
/** [facts] are what the check compared, for the test-login diagnosis (SEC-032); never a secret, code or token. */
class IdpRejected(val reason: String, val facts: Map<String, Any?> = emptyMap()) : RuntimeException(reason)

/** Provider metadata (discovery document or SAML metadata) that cannot be used. The API answers 422. */
class IdpMetadataInvalid(message: String) : RuntimeException(message)

/**
 * Compact JWS (RFC 7515) parsing and signature checks over JDK crypto, for OIDC ID tokens and provider access tokens.
 * Only asymmetric algorithms are accepted ([org.oip.security.IdentityProvider.SIGNING_ALGORITHMS]); `none` and the
 * HMAC family never verify. Keys come from the provider's JWK set (RFC 7517/7518).
 */
object Jws {
    /** Longest token accepted, so a huge header cannot cost parse time. */
    const val MAX_LENGTH = 16 * 1024

    class Parsed(
        val header: Map<String, Any?>,
        val claims: Map<String, Any?>,
        val signingInput: ByteArray,
        val signature: ByteArray,
    ) {
        val alg: String? get() = header["alg"] as? String
        val kid: String? get() = header["kid"] as? String
    }

    /** Three base64url parts with JSON-object header and payload, or null. Nothing is verified here. */
    fun parse(token: String): Parsed? {
        if (token.length > MAX_LENGTH) return null
        val parts = token.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        return try {
            val dec = Base64.getUrlDecoder()
            val header = Json.parse(String(dec.decode(parts[0]), Charsets.UTF_8))
            val claims = Json.parse(String(dec.decode(parts[1]), Charsets.UTF_8))
            Parsed(header, claims, "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII), dec.decode(parts[2]))
        } catch (_: Exception) {
            null
        }
    }

    /** Whether [token] has the shape of a JWS (so a bearer value is tried as a provider token, not an API key). */
    fun looksLikeJws(token: String): Boolean = token.length <= MAX_LENGTH && token.count { it == '.' } == 2 && !token.startsWith("oipk_")

    fun verify(parsed: Parsed, alg: String, key: PublicKey): Boolean = try {
        val sig = signer(alg, key) ?: return false
        sig.initVerify(key)
        sig.update(parsed.signingInput)
        sig.verify(parsed.signature)
    } catch (_: Exception) {
        false
    }

    private fun signer(alg: String, key: PublicKey): Signature? {
        val rsa = key.algorithm == "RSA"
        val ec = key.algorithm == "EC"
        return when (alg) {
            "RS256" -> if (rsa) Signature.getInstance("SHA256withRSA") else null
            "RS384" -> if (rsa) Signature.getInstance("SHA384withRSA") else null
            "RS512" -> if (rsa) Signature.getInstance("SHA512withRSA") else null
            "PS256" -> if (rsa) pss("SHA-256", MGF1ParameterSpec.SHA256, 32) else null
            "PS384" -> if (rsa) pss("SHA-384", MGF1ParameterSpec.SHA384, 48) else null
            "PS512" -> if (rsa) pss("SHA-512", MGF1ParameterSpec.SHA512, 64) else null
            // JOSE carries R || S; the JDK reads that layout as P1363.
            "ES256" -> if (ec && curve(key) == 256) Signature.getInstance("SHA256withECDSAinP1363Format") else null
            "ES384" -> if (ec && curve(key) == 384) Signature.getInstance("SHA384withECDSAinP1363Format") else null
            "ES512" -> if (ec && curve(key) == 521) Signature.getInstance("SHA512withECDSAinP1363Format") else null
            else -> null
        }
    }

    private fun pss(hash: String, mgf: MGF1ParameterSpec, salt: Int): Signature =
        Signature.getInstance("RSASSA-PSS").also { it.setParameter(PSSParameterSpec(hash, "MGF1", mgf, salt, 1)) }

    private fun curve(key: PublicKey): Int = (key as? java.security.interfaces.ECPublicKey)?.params?.curve?.field?.fieldSize ?: 0
}

/** One verification key from a JWK set. */
data class Jwk(val kid: String?, val kty: String, val alg: String?, val key: PublicKey)

object Jwks {
    /** The usable signature keys of a JWK set document. Encryption keys and unknown key types are skipped. */
    fun parse(doc: Map<String, Any?>): List<Jwk> {
        val keys = doc["keys"] as? List<*> ?: throw IdpMetadataInvalid("JWK set has no keys array")
        return keys.mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            val k = raw as? Map<String, Any?> ?: return@mapNotNull null
            if ((k["use"] as? String)?.let { it != "sig" } == true) return@mapNotNull null
            val kty = k["kty"] as? String ?: return@mapNotNull null
            val key = runCatching {
                when (kty) {
                    "RSA" -> rsa(k)
                    "EC" -> ec(k)
                    else -> null
                }
            }.getOrNull() ?: return@mapNotNull null
            Jwk(k["kid"] as? String, kty, k["alg"] as? String, key)
        }
    }

    private fun b64(v: Any?): BigInteger = BigInteger(1, Base64.getUrlDecoder().decode(v as String))

    private fun rsa(k: Map<String, Any?>): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(b64(k["n"]), b64(k["e"])))

    private fun ec(k: Map<String, Any?>): PublicKey {
        val name = when (k["crv"]) {
            "P-256" -> "secp256r1"
            "P-384" -> "secp384r1"
            "P-521" -> "secp521r1"
            else -> error("unsupported curve")
        }
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(name)) }
            .getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(b64(k["x"]), b64(k["y"])), params))
    }
}
