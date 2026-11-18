package org.oip.gateway

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.xml.crypto.dsig.CanonicalizationMethod
import javax.xml.crypto.dsig.DigestMethod
import javax.xml.crypto.dsig.Transform
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMSignContext
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec
import javax.xml.crypto.dsig.spec.TransformParameterSpec
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun unsigned(b: BigInteger): ByteArray = b.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** RS256 compact JWS over [claims]. */
fun signJwt(claims: Map<String, Any?>, keys: KeyPair, kid: String?, alg: String = "RS256"): String {
    val header = linkedMapOf<String, Any?>("alg" to alg, "typ" to "JWT")
    if (kid != null) header["kid"] = kid
    val input = b64url(Json.write(header).toByteArray()) + "." + b64url(Json.write(claims).toByteArray())
    if (alg == "none") return "$input."
    val sig = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private); update(input.toByteArray()) }.sign()
    return "$input.${b64url(sig)}"
}

/**
 * A small OpenID Connect provider for tests, on 127.0.0.1: discovery, JWK set, an authorization endpoint that signs the
 * next user in at once and redirects back with a code, and a token endpoint that checks the PKCE verifier, the redirect
 * URI, and the client secret before it returns a signed ID token. [tamper] edits the ID token claims before signing.
 */
class TestOidcProvider(
    val clientId: String = "oip-gateway",
    val clientSecret: String? = "client-secret-1",
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val keys: KeyPair = rsaKeyPair()
    val kid = "k1"
    val issuer: String get() = "http://127.0.0.1:${server.address.port}"
    @Volatile var user: Map<String, Any?> = mapOf("sub" to "u-1", "preferred_username" to "alice", "groups" to listOf("plant-ops"))
    @Volatile var tamper: (MutableMap<String, Any?>) -> Unit = {}
    /** Sign the ID token with another key (signature check). */
    @Volatile var signingKeys: KeyPair? = null
    @Volatile var discovery: (Map<String, Any?>) -> Map<String, Any?> = { it }
    val tokenRequests = java.util.concurrent.atomic.AtomicInteger()
    /** Every form body the token endpoint received, so a test can show what the gateway sent (never a secret in a URL). */
    val tokenForms = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
    private val codes = ConcurrentHashMap<String, Map<String, String>>()

    init {
        server.createContext("/.well-known/openid-configuration") { ex ->
            reply(ex, 200, Json.write(discovery(linkedMapOf(
                "issuer" to issuer,
                "authorization_endpoint" to "$issuer/authorize",
                "token_endpoint" to "$issuer/token",
                "jwks_uri" to "$issuer/jwks",
                "end_session_endpoint" to "$issuer/logout",
                "response_types_supported" to listOf("code"),
                "code_challenge_methods_supported" to listOf("S256"),
                "id_token_signing_alg_values_supported" to listOf("RS256"),
            ))))
        }
        server.createContext("/jwks") { ex ->
            val pub = keys.public as RSAPublicKey
            reply(ex, 200, Json.write(mapOf("keys" to listOf(mapOf(
                "kty" to "RSA", "kid" to kid, "use" to "sig", "alg" to "RS256",
                "n" to b64url(unsigned(pub.modulus)), "e" to b64url(unsigned(pub.publicExponent)),
            )))))
        }
        server.createContext("/authorize") { ex ->
            val q = query(ex.requestURI.rawQuery)
            val code = Pkce.token(16)
            codes[code] = q
            val back = q.getValue("redirect_uri") + "?code=" + IdpHttp.enc(code) + "&state=" + IdpHttp.enc(q.getValue("state"))
            ex.responseHeaders.add("Location", back)
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/token") { ex ->
            tokenRequests.incrementAndGet()
            val form = query(ex.requestBody.readAllBytes().toString(Charsets.UTF_8))
            tokenForms += form
            val auth = ex.requestHeaders.getFirst("Authorization")
            val expectedAuth = clientSecret?.let { "Basic " + Base64.getEncoder().encodeToString("$clientId:$it".toByteArray()) }
            val req = codes.remove(form["code"] ?: "")
            val challenge = form["code_verifier"]?.let { b64url(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
            when {
                req == null -> reply(ex, 400, """{"error":"invalid_grant"}""")
                expectedAuth != null && auth != expectedAuth -> reply(ex, 401, """{"error":"invalid_client"}""")
                challenge != req["code_challenge"] || req["code_challenge_method"] != "S256" -> reply(ex, 400, """{"error":"invalid_grant","error_description":"pkce"}""")
                form["redirect_uri"] != req["redirect_uri"] -> reply(ex, 400, """{"error":"invalid_grant"}""")
                else -> {
                    val now = Instant.now().epochSecond
                    val claims = linkedMapOf<String, Any?>("iss" to issuer, "aud" to clientId, "iat" to now, "exp" to now + 300, "nonce" to req["nonce"])
                    claims.putAll(user)
                    tamper(claims)
                    reply(ex, 200, Json.write(mapOf(
                        "token_type" to "Bearer",
                        "id_token" to signJwt(claims, signingKeys ?: keys, kid),
                        "access_token" to accessToken(),
                    )))
                }
            }
        }
        server.createContext("/metadata.xml") { ex -> reply(ex, 200, "<not-metadata/>", "application/xml") }
        server.start()
    }

    /** A provider access token for REST bearer use, audience [audience]. */
    fun accessToken(audience: String = clientId, extra: Map<String, Any?> = emptyMap(), signWith: KeyPair = keys): String {
        val now = Instant.now().epochSecond
        val claims = linkedMapOf<String, Any?>("iss" to issuer, "aud" to audience, "iat" to now, "exp" to now + 300)
        claims.putAll(user)
        claims.putAll(extra)
        return signJwt(claims, signWith, kid)
    }

    override fun close() = server.stop(0)

    private fun reply(ex: HttpExchange, status: Int, body: String, type: String = "application/json") {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
    }
}

/** Follow one redirect hop outside the gateway (to the test provider), without following further. */
fun redirectFrom(url: String): String {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    val r = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
    check(r.statusCode() == 302) { "expected a redirect from $url, got ${r.statusCode()}: ${r.body()}" }
    return r.headers().firstValue("Location").orElseThrow()
}

/** Path and query of an absolute URL on the gateway. */
fun pathOf(url: String): String = URI.create(url).let { it.rawPath + (it.rawQuery?.let { q -> "?$q" } ?: "") }

/**
 * A SAML identity provider for tests: a signing key and self-signed certificate, its metadata, and signed Responses.
 */
class TestSamlIdp(val entityId: String = "urn:test:idp") {
    val keys: KeyPair = rsaKeyPair()
    val certificate: X509Certificate = selfSigned(keys)
    val ssoUrl = "https://idp.test.invalid/sso"

    fun metadata(cert: X509Certificate = certificate): String = """<?xml version="1.0"?>
<md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" entityID="$entityId">
  <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
    <md:KeyDescriptor use="signing"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>${Base64.getMimeEncoder().encodeToString(cert.encoded)}</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>
    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="$ssoUrl"/>
    <md:SingleLogoutService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="https://idp.test.invalid/slo"/>
  </md:IDPSSODescriptor>
</md:EntityDescriptor>"""

    /** The AuthnRequest ID inside a redirect-binding URL. */
    fun requestId(redirectUrl: String): String {
        val q = TestOidcProvider.query(URI.create(redirectUrl).rawQuery)
        val xml = InflaterInputStream(ByteArrayInputStream(Base64.getDecoder().decode(q.getValue("SAMLRequest"))), Inflater(true)).readAllBytes().toString(Charsets.UTF_8)
        return Regex("ID=\"([^\"]+)\"").find(xml)!!.groupValues[1]
    }

    /**
     * A base64 Response. [edit] changes the parsed assertion element after signing (tampering), [signer] signs with
     * another key, [sign] false sends no signature.
     */
    fun response(
        inResponseTo: String,
        acs: String,
        audience: String,
        nameId: String = "bob",
        groups: List<String> = listOf("plant-ops"),
        attributes: Map<String, String> = emptyMap(),
        issuer: String = entityId,
        notOnOrAfter: Instant = Instant.now().plusSeconds(300),
        sign: Boolean = true,
        signer: KeyPair = keys,
        edit: (Element) -> Unit = {},
    ): String {
        val now = Instant.now()
        val extra = attributes.entries.joinToString("") { (k, v) -> """<saml:Attribute Name="$k"><saml:AttributeValue>$v</saml:AttributeValue></saml:Attribute>""" }
        val values = groups.joinToString("") { "<saml:AttributeValue>$it</saml:AttributeValue>" }
        val xml = """<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion" ID="_r${Pkce.token(8)}" Version="2.0" IssueInstant="$now" Destination="$acs" InResponseTo="$inResponseTo"><saml:Issuer>$issuer</saml:Issuer><samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/></samlp:Status><saml:Assertion ID="_a${Pkce.token(8)}" Version="2.0" IssueInstant="$now"><saml:Issuer>$issuer</saml:Issuer><saml:Subject><saml:NameID>$nameId</saml:NameID><saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer"><saml:SubjectConfirmationData InResponseTo="$inResponseTo" Recipient="$acs" NotOnOrAfter="$notOnOrAfter"/></saml:SubjectConfirmation></saml:Subject><saml:Conditions NotBefore="${now.minusSeconds(5)}" NotOnOrAfter="$notOnOrAfter"><saml:AudienceRestriction><saml:Audience>$audience</saml:Audience></saml:AudienceRestriction></saml:Conditions><saml:AttributeStatement><saml:Attribute Name="groups">$values</saml:Attribute>$extra</saml:AttributeStatement></saml:Assertion></samlp:Response>"""
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val assertion = doc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Assertion").item(0) as Element
        if (sign) {
            assertion.setIdAttribute("ID", true)
            val fac = XMLSignatureFactory.getInstance("DOM")
            val ref = fac.newReference(
                "#" + assertion.getAttribute("ID"),
                fac.newDigestMethod(DigestMethod.SHA256, null),
                listOf(
                    fac.newTransform(Transform.ENVELOPED, null as TransformParameterSpec?),
                    fac.newTransform(CanonicalizationMethod.EXCLUSIVE, null as TransformParameterSpec?),
                ),
                null, null,
            )
            val si = fac.newSignedInfo(
                fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, null as C14NMethodParameterSpec?),
                fac.newSignatureMethod("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", null),
                listOf(ref),
            )
            val issuerEl = assertion.firstChild
            fac.newXMLSignature(si, null).sign(DOMSignContext(signer.private, assertion, issuerEl.nextSibling))
        }
        edit(assertion)
        val out = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(out))
        return Base64.getEncoder().encodeToString(out.toString().toByteArray())
    }

    companion object {
        fun selfSigned(keys: KeyPair): X509Certificate {
            val name = X500Name("CN=test idp")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000L), name, keys.public)
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keys.private)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }

        fun form(vararg pairs: Pair<String, String>): ByteArray =
            pairs.joinToString("&") { (k, v) -> "${IdpHttp.enc(k)}=${IdpHttp.enc(v)}" }.toByteArray()
    }
}
