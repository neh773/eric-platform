package org.oip.gateway

import org.oip.security.IdentityProvider
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.xml.XMLConstants
import javax.xml.crypto.dsig.XMLSignature
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.KeySelector
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SAML 2.0 service provider for browser login (SEC-013/SEC-014): metadata import, an AuthnRequest over the
 * HTTP-Redirect binding, and validation of the signed Response posted back to the assertion consumer service.
 *
 * Only the configured signing certificates verify a signature; any key carried in the document is ignored. Exactly
 * one assertion is accepted, and the element read is the element whose signature verified (signature wrapping).
 * DTDs are refused. Encrypted assertions and single logout at the provider are not supported (DIFFERENCES 258).
 *
 * Doc source: FEATURES SEC-013.
 * Doc source: FEATURES SEC-014.
 * Protocol: SAML 2.0 Core, Bindings (HTTP-Redirect, HTTP-POST), Profiles §4.1.4 (Web Browser SSO response checks).
 */
class SamlServiceProvider(private val clock: Clock) {
    data class Assertion(val nameId: String, val attributes: Map<String, List<String>>)

    /** Provider fields from an IdP metadata document (EntityDescriptor, or the first IdP entity in EntitiesDescriptor). */
    fun importMetadata(xml: String): Map<String, Any?> {
        if (xml.isBlank()) throw IdpMetadataInvalid("metadata is empty")
        if (xml.length > MAX_XML) throw IdpMetadataInvalid("metadata is too large")
        val doc = try {
            parse(xml.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IdpMetadataInvalid("metadata is not well-formed XML")
        }
        val root = doc.documentElement
        val entity = when {
            root.namespaceURI == MD && root.localName == "EntityDescriptor" -> root
            root.namespaceURI == MD && root.localName == "EntitiesDescriptor" ->
                children(root, MD, "EntityDescriptor").firstOrNull { children(it, MD, "IDPSSODescriptor").isNotEmpty() }
            else -> null
        } ?: throw IdpMetadataInvalid("metadata has no identity provider EntityDescriptor")
        val entityId = entity.getAttribute("entityID").takeIf { it.isNotBlank() } ?: throw IdpMetadataInvalid("EntityDescriptor has no entityID")
        val idp = children(entity, MD, "IDPSSODescriptor").firstOrNull() ?: throw IdpMetadataInvalid("metadata has no IDPSSODescriptor")
        val sso = children(idp, MD, "SingleSignOnService").firstOrNull { it.getAttribute("Binding") == REDIRECT }
            ?.getAttribute("Location")?.takeIf { it.isNotBlank() }
            ?: throw IdpMetadataInvalid("metadata has no HTTP-Redirect SingleSignOnService")
        IdpUrls.check("SingleSignOnService", sso, required = true)?.let { throw IdpMetadataInvalid(it) }
        val slo = children(idp, MD, "SingleLogoutService").firstOrNull { it.getAttribute("Binding") == REDIRECT }
            ?.getAttribute("Location") ?: ""
        if (slo.isNotEmpty()) IdpUrls.check("SingleLogoutService", slo, required = false)?.let { throw IdpMetadataInvalid(it) }
        val certs = children(idp, MD, "KeyDescriptor")
            .filter { it.getAttribute("use").let { use -> use.isEmpty() || use == "signing" } }
            .flatMap { kd -> kd.getElementsByTagNameNS(DS, "X509Certificate").let { nl -> (0 until nl.length).map { nl.item(it).textContent } } }
            .map { pem(certificate(it.filterNot(Char::isWhitespace))) }
            .distinct()
        if (certs.isEmpty()) throw IdpMetadataInvalid("metadata has no signing certificate")
        val fields = linkedMapOf<String, Any?>(
            "type" to IdentityProvider.SAML,
            "idpEntityId" to entityId,
            "ssoUrl" to sso,
            "signingCertificates" to certs,
        )
        if (slo.isNotEmpty()) fields["sloUrl"] = slo
        return fields
    }

    /** The redirect URL that carries a deflated AuthnRequest [id] and [relayState] to the provider. */
    fun authnRequestUrl(p: IdentityProvider, id: String, acsUrl: String, relayState: String): String {
        val xml = """<samlp:AuthnRequest xmlns:samlp="$P" xmlns:saml="$A" ID="${esc(id)}" Version="2.0" """ +
            """IssueInstant="${Instant.ofEpochMilli(clock.millis())}" Destination="${esc(p.ssoUrl)}" """ +
            """AssertionConsumerServiceURL="${esc(acsUrl)}" ProtocolBinding="$POST">""" +
            """<saml:Issuer>${esc(p.spEntityId)}</saml:Issuer><samlp:NameIDPolicy AllowCreate="true"/></samlp:AuthnRequest>"""
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        val request = Base64.getEncoder().encodeToString(out.toByteArray())
        val q = "SAMLRequest=${IdpHttp.enc(request)}&RelayState=${IdpHttp.enc(relayState)}"
        return p.ssoUrl + (if ('?' in p.ssoUrl) "&" else "?") + q
    }

    /** SAML Profiles §4.1.4.2/3: a signed, successful Response for this SP, request, and time. */
    fun validate(p: IdentityProvider, samlResponse: String, acsUrl: String, requestId: String): Assertion {
        if (samlResponse.length > MAX_XML * 2) throw IdpRejected("SAML response too large")
        val bytes = runCatching { Base64.getMimeDecoder().decode(samlResponse) }.getOrNull() ?: throw IdpRejected("SAML response is not base64")
        val doc = runCatching { parse(bytes) }.getOrNull() ?: throw IdpRejected("SAML response is not well-formed XML")
        val response = doc.documentElement
        if (response.namespaceURI != P || response.localName != "Response") throw IdpRejected("not a SAML Response")
        if (response.getElementsByTagNameNS(A, "EncryptedAssertion").length > 0) throw IdpRejected("encrypted assertions are not supported")
        val all = response.getElementsByTagNameNS(A, "Assertion")
        if (all.length != 1) throw IdpRejected("response must hold exactly one assertion")
        val assertion = all.item(0) as Element
        if (assertion.parentNode !== response) throw IdpRejected("assertion is not a child of the response")
        val status = child(response, P, "Status")?.let { child(it, P, "StatusCode") }?.getAttribute("Value")
        if (status != SUCCESS) throw IdpRejected("provider returned status ${status?.substringAfterLast(':') ?: "none"}")

        val responseId = response.getAttribute("ID")
        val assertionId = assertion.getAttribute("ID")
        if (responseId.isEmpty() || assertionId.isEmpty() || responseId == assertionId) throw IdpRejected("response and assertion need distinct IDs")
        response.setIdAttribute("ID", true)
        assertion.setIdAttribute("ID", true)
        val keys = p.signingCertificates.mapNotNull { runCatching { certificate(stripPem(it)).publicKey }.getOrNull() }
        if (keys.isEmpty()) throw IdpRejected("provider has no usable signing certificate")
        val responseSigned = child(response, DS, "Signature")?.let { signed(it, responseId, keys) } == true
        val assertionSigned = child(assertion, DS, "Signature")?.let { signed(it, assertionId, keys) } == true
        if (!responseSigned && !assertionSigned) throw IdpRejected("SAML signature is missing or invalid")

        val skew = p.clockSkewSeconds.toLong() * 1000L
        val now = clock.millis()
        fun issuerMismatch(presented: String?) = IdpRejected("issuer mismatch", mapOf("expected" to p.idpEntityId, "presented" to IdpDiagnostics.shown(presented)))
        child(response, A, "Issuer")?.textContent?.trim()?.let { if (it != p.idpEntityId) throw issuerMismatch(it) }
        child(assertion, A, "Issuer")?.textContent?.trim().let { if (it != p.idpEntityId) throw issuerMismatch(it) }
        response.getAttribute("Destination").takeIf { it.isNotEmpty() }?.let {
            if (it != acsUrl) throw IdpRejected("destination mismatch", mapOf("expected" to acsUrl, "presented" to IdpDiagnostics.shown(it)))
        }
        response.getAttribute("InResponseTo").takeIf { it.isNotEmpty() }?.let { if (it != requestId) throw IdpRejected("InResponseTo mismatch") }

        val subject = child(assertion, A, "Subject") ?: throw IdpRejected("assertion has no subject")
        val nameId = child(subject, A, "NameID")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: throw IdpRejected("assertion has no NameID")
        val bearer = children(subject, A, "SubjectConfirmation").filter { it.getAttribute("Method") == BEARER }
            .mapNotNull { child(it, A, "SubjectConfirmationData") }
            .firstOrNull { data ->
                data.getAttribute("Recipient") == acsUrl &&
                    data.getAttribute("InResponseTo") == requestId &&
                    time(data.getAttribute("NotOnOrAfter"))?.let { now < it + skew } == true
            }
        if (bearer == null) {
            val recipients = children(subject, A, "SubjectConfirmation").mapNotNull { child(it, A, "SubjectConfirmationData") }.map { it.getAttribute("Recipient") }
            if (recipients.isNotEmpty() && acsUrl !in recipients) {
                throw IdpRejected("no bearer confirmation for this request, recipient, and time", mapOf("expected" to acsUrl, "presented" to IdpDiagnostics.shown(recipients.joinToString(", "))))
            }
            val ends = children(subject, A, "SubjectConfirmation").mapNotNull { child(it, A, "SubjectConfirmationData") }.mapNotNull { time(it.getAttribute("NotOnOrAfter")) }
            // The recipient is right but the confirmation's time has passed: the clocks disagree.
            if (ends.isNotEmpty() && ends.all { now >= it + skew }) throw IdpRejected("assertion expired", IdpDiagnostics.timeFacts(ends.max(), now))
            throw IdpRejected("no bearer confirmation for this request, recipient, and time")
        }
        val conditions = child(assertion, A, "Conditions") ?: throw IdpRejected("assertion has no conditions")
        time(conditions.getAttribute("NotBefore"))?.let { if (now + skew < it) throw IdpRejected("assertion not yet valid", IdpDiagnostics.timeFacts(it, now)) }
        time(conditions.getAttribute("NotOnOrAfter"))?.let { if (now >= it + skew) throw IdpRejected("assertion expired", IdpDiagnostics.timeFacts(it, now)) }
        val audiences = children(conditions, A, "AudienceRestriction").flatMap { children(it, A, "Audience") }.map { it.textContent.trim() }
        if (p.spEntityId !in audiences) throw IdpRejected("audience mismatch", mapOf("expected" to p.spEntityId, "presented" to IdpDiagnostics.shown(audiences.joinToString(", "))))

        val attributes = linkedMapOf<String, MutableList<String>>()
        for (statement in children(assertion, A, "AttributeStatement")) {
            for (attr in children(statement, A, "Attribute")) {
                val values = children(attr, A, "AttributeValue").map { it.textContent.trim() }.filter { it.isNotEmpty() }
                for (name in listOf(attr.getAttribute("Name"), attr.getAttribute("FriendlyName")).filter { it.isNotEmpty() }) {
                    attributes.getOrPut(name) { mutableListOf() } += values
                }
            }
        }
        return Assertion(nameId, attributes)
    }

    /** The identity an [assertion] maps to: NameID or the username attribute, and roles from the roles attribute. */
    fun identity(p: IdentityProvider, assertion: Assertion, knownRoles: Set<String>): IdpIdentity {
        val raw = if (p.usernameClaim.isBlank()) assertion.nameId
        else assertion.attributes[p.usernameClaim]?.firstOrNull() ?: throw IdpRejected("assertion has no ${p.usernameClaim} attribute")
        val username = IdpClaims.username(raw) ?: throw IdpRejected("username attribute is not usable")
        val values = if (p.rolesClaim.isBlank()) emptyList() else assertion.attributes[p.rolesClaim].orEmpty()
        val attributes = assertion.attributes.mapValues { (_, v) -> v.take(256).map { it.take(500) } } + ("NameID" to listOf(assertion.nameId))
        val name = if (p.nameClaim.isBlank()) null else assertion.attributes[p.nameClaim]?.firstOrNull()
        val email = if (p.emailClaim.isBlank()) null else assertion.attributes[p.emailClaim]?.firstOrNull()
        return IdpIdentity(
            p.name, username, p.mapRoles(values).filterTo(sortedSetOf()) { it in knownRoles }, assertion.nameId, attributes,
            IdpClaims.displayName(name), IdpClaims.email(email),
        )
    }

    /** One enveloped signature over the element with [id], verified against one of [keys] only. */
    private fun signed(signature: Element, id: String, keys: List<PublicKey>): Boolean {
        val factory = XMLSignatureFactory.getInstance("DOM")
        for (key in keys) {
            val ctx = DOMValidateContext(KeySelector.singletonKeySelector(key), signature)
            ctx.setProperty("org.jcp.xml.dsig.secureValidation", true)
            val ok = runCatching {
                val sig: XMLSignature = factory.unmarshalXMLSignature(ctx)
                val refs = sig.signedInfo.references
                if (refs.size != 1 || refs[0].uri != "#$id") return@runCatching false
                sig.validate(ctx)
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun time(v: String): Long? = v.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun parse(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature("http://xml.org/sax/features/external-general-entities", false)
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        f.isXIncludeAware = false
        f.isExpandEntityReferences = false
        val builder = f.newDocumentBuilder()
        builder.setErrorHandler(null)
        return builder.parse(ByteArrayInputStream(bytes))
    }

    private fun children(parent: Element, ns: String, local: String): List<Element> {
        val out = mutableListOf<Element>()
        var n = parent.firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == ns && n.localName == local) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun child(parent: Element, ns: String, local: String): Element? = children(parent, ns, local).firstOrNull()

    private fun certificate(base64: String): X509Certificate = try {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64))) as X509Certificate
    } catch (e: Exception) {
        throw IdpMetadataInvalid("signing certificate is not a valid X.509 certificate")
    }

    companion object {
        const val MAX_XML = 512 * 1024
        const val MD = "urn:oasis:names:tc:SAML:2.0:metadata"
        const val DS = "http://www.w3.org/2000/09/xmldsig#"
        const val P = "urn:oasis:names:tc:SAML:2.0:protocol"
        const val A = "urn:oasis:names:tc:SAML:2.0:assertion"
        const val REDIRECT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"
        const val POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
        const val BEARER = "urn:oasis:names:tc:SAML:2.0:cm:bearer"
        const val SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success"

        fun pem(cert: X509Certificate): String =
            "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"

        fun stripPem(pem: String): String = pem.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "").filterNot(Char::isWhitespace)

        /** A configured certificate (PEM or bare base64) that parses, or an error message. */
        fun checkCertificate(pem: String): String? = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(stripPem(pem))))
        }.fold({ null }, { "signingCertificates holds an entry that is not an X.509 certificate" })

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
