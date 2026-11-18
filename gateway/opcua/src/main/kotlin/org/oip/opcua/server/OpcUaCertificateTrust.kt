package org.oip.opcua.server

import org.eclipse.milo.opcua.stack.core.security.FileBasedCertificateQuarantine
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import javax.naming.ldap.LdapName
import kotlin.io.path.createDirectories

/** Public fields of one client application certificate. No key material. */
data class ClientCertificateInfo(
    /** SHA-1 of the DER encoding, lowercase hex. This is the OPC UA certificate thumbprint. */
    val thumbprint: String,
    val subject: String,
    val commonName: String?,
    val issuer: String,
    val applicationUri: String?,
    val notBefore: Instant,
    val notAfter: Instant,
) {
    fun expired(now: Instant): Boolean = now.isAfter(notAfter) || now.isBefore(notBefore)
}

/**
 * Client-certificate trust for the gateway OPC UA server, kept in the Milo file layout under
 * `<securityDir>/pki`: `trusted/certs` is the trust list and `rejected/certs` holds certificates
 * from clients that tried a secured endpoint before they were trusted (quarantined).
 *
 * An operator trusts a quarantined certificate, deletes it from quarantine, imports a certificate
 * into the trust list, or deletes a trusted one. A deleted trusted certificate is rejected on the
 * client's next secure-channel attempt and lands in quarantine again.
 *
 * The server's certificate validator reads the same [trustList] instance, so a change applies to
 * the next secure channel without a restart. Methods are synchronized; the lists are small.
 */
class OpcUaCertificateTrust private constructor(
    val pkiDir: Path,
    internal val trustList: FileBasedTrustListManager,
    internal val quarantine: FileBasedCertificateQuarantine,
) : AutoCloseable {

    @Synchronized
    fun trusted(): List<ClientCertificateInfo> = trustList.trustedCertificates.map(::describe).sortedBy { it.subject }

    @Synchronized
    fun quarantined(): List<ClientCertificateInfo> =
        quarantine.rejectedCertificates.map(::describe).distinctBy { it.thumbprint }.sortedBy { it.subject }

    /** Move a quarantined certificate into the trust list. Null when no quarantined certificate has [thumbprint]. */
    @Synchronized
    fun trustQuarantined(thumbprint: String): ClientCertificateInfo? {
        val key = normalize(thumbprint)
        val certificate = quarantine.rejectedCertificates.firstOrNull { thumbprintOf(it) == key } ?: return null
        if (trustList.trustedCertificates.none { thumbprintOf(it) == key }) trustList.addTrustedCertificate(certificate)
        quarantine.removeRejectedCertificate(certificate)
        return describe(certificate)
    }

    /** Delete a quarantined certificate without trusting it. False when it is not quarantined. */
    @Synchronized
    fun removeQuarantined(thumbprint: String): Boolean {
        val key = normalize(thumbprint)
        val certificate = quarantine.rejectedCertificates.firstOrNull { thumbprintOf(it) == key } ?: return false
        quarantine.removeRejectedCertificate(certificate)
        return true
    }

    /** Remove a certificate from the trust list. False when it is not trusted. */
    @Synchronized
    fun removeTrusted(thumbprint: String): Boolean {
        val key = normalize(thumbprint)
        if (trustList.trustedCertificates.none { thumbprintOf(it) == key }) return false
        return trustList.removeTrustedCertificate(ByteString.of(HexFormat.of().parseHex(key)))
    }

    /**
     * Add a certificate to the trust list from PEM text, or base64 of the DER bytes. A certificate that is
     * already trusted is returned unchanged. A quarantined copy of the same certificate is removed.
     */
    @Synchronized
    fun importTrusted(text: String): ClientCertificateInfo {
        val certificate = parseCertificate(text)
        val key = thumbprintOf(certificate)
        if (trustList.trustedCertificates.none { thumbprintOf(it) == key }) trustList.addTrustedCertificate(certificate)
        quarantine.rejectedCertificates.filter { thumbprintOf(it) == key }.forEach { quarantine.removeRejectedCertificate(it) }
        return describe(certificate)
    }

    override fun close() {
        runCatching { trustList.close() }
    }

    companion object {
        private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
        /** Upper bound for an uploaded certificate. Application certificates are a few KiB. */
        const val MAX_UPLOAD_CHARS = 64 * 1024

        fun open(securityDir: Path): OpcUaCertificateTrust {
            val pki = securityDir.resolve("pki")
            pki.createDirectories()
            val trust = FileBasedTrustListManager.createAndInitialize(pki)
            val rejected = pki.resolve("rejected").resolve("certs").createDirectories()
            val quarantine = FileBasedCertificateQuarantine.create(rejected)
            return OpcUaCertificateTrust(pki, trust, quarantine)
        }

        fun validThumbprint(text: String): Boolean = HEX40.matches(text)

        fun thumbprintOf(certificate: X509Certificate): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(certificate.encoded))

        fun describe(certificate: X509Certificate): ClientCertificateInfo {
            val subject = certificate.subjectX500Principal.name
            return ClientCertificateInfo(
                thumbprint = thumbprintOf(certificate),
                subject = subject,
                commonName = commonName(subject),
                issuer = certificate.issuerX500Principal.name,
                applicationUri = applicationUri(certificate),
                notBefore = certificate.notBefore.toInstant(),
                notAfter = certificate.notAfter.toInstant(),
            )
        }

        /** PEM (one CERTIFICATE block) or base64 DER. Throws [IllegalArgumentException] for anything else. */
        fun parseCertificate(text: String): X509Certificate {
            require(text.length <= MAX_UPLOAD_CHARS) { "certificate is larger than ${MAX_UPLOAD_CHARS / 1024} KiB" }
            require(!text.contains("PRIVATE KEY")) { "send the certificate only, not a private key" }
            val trimmed = text.trim()
            val body = if (trimmed.startsWith("-----BEGIN CERTIFICATE-----")) {
                val end = trimmed.indexOf("-----END CERTIFICATE-----")
                require(end > 0) { "PEM certificate has no END line" }
                trimmed.substring("-----BEGIN CERTIFICATE-----".length, end)
            } else {
                trimmed
            }
            val der = try {
                Base64.getMimeDecoder().decode(body.filterNot { it.isWhitespace() })
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("certificate must be PEM or base64 DER")
            }
            require(der.isNotEmpty()) { "certificate is empty" }
            return try {
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            } catch (_: CertificateException) {
                throw IllegalArgumentException("not an X.509 certificate")
            } catch (_: ClassCastException) {
                throw IllegalArgumentException("not an X.509 certificate")
            }
        }

        private fun normalize(thumbprint: String): String {
            require(validThumbprint(thumbprint)) { "thumbprint must be 40 hex characters (SHA-1)" }
            return thumbprint.lowercase()
        }

        private fun commonName(subject: String): String? = runCatching {
            LdapName(subject).rdns.lastOrNull { it.type.equals("CN", ignoreCase = true) }?.value?.toString()
        }.getOrNull()

        /** The OPC UA application URI is the URI entry of the subject alternative name (general name type 6). */
        private fun applicationUri(certificate: X509Certificate): String? = runCatching {
            certificate.subjectAlternativeNames?.firstOrNull { (it[0] as? Int) == 6 }?.get(1) as? String
        }.getOrNull()
    }
}
