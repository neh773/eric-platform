package org.oip.gateway

import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.math.BigInteger
import java.io.StringReader

/**
 * Gateway web-server certificate material.
 *
 * A self-signed certificate is created when HTTPS is on and no certificate is configured.
 * An administrator can replace it with a PEM chain and unencrypted key, or with PKCS#12.
 * The private key is never part of [view].
 *
 * Ports, the public address, and force-HTTPS redirect:
 * Self-signed, CA-signed, and uploaded certificates:
 * (FEATURES GAT-031, GAT-032).
 */
class TlsMaterial(val chain: List<X509Certificate>, val privateKey: PrivateKey) {
    val leaf: X509Certificate get() = chain.first()

    fun fingerprint(): String = TlsCertificates.fingerprint(leaf)

    fun toKeyStore(password: CharArray): KeyStore {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, password)
        store.setKeyEntry("gateway", privateKey, password, chain.toTypedArray())
        return store
    }

    override fun toString(): String = "TlsMaterial(fingerprint=${fingerprint()})"
}

object TlsCertificates {
    const val MAX_BYTES = 256 * 1024
    val PROTOCOLS: List<String> = listOf("TLSv1.2", "TLSv1.3")
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6_CHARS = Regex("""[0-9A-Fa-f:.]+""")
    private val DNS_NAME = Regex("""(\*\.)?[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*""")
    private val lock = Any()

    fun generate(names: Collection<String>): TlsMaterial =
        generate(names, "OIP Gateway", listOf(KeyPurposeId.id_kp_serverAuth))

    /**
     * Self-signed material for a listener that is both a TLS server and a TLS client.
     * Gateway-network links use this so one certificate is presented in both directions.
     * The web listener keeps [generate] (server authentication only).
     */
    fun generateMutual(commonName: String, names: Collection<String>): TlsMaterial =
        generate(names, commonName, listOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth))

    fun generate(names: Collection<String>, commonName: String, purposes: List<KeyPurposeId>): TlsMaterial {
        ensureProvider()
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keys = generator.generateKeyPair()
        val subject = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName).build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial(),
            Date(now - 5 * 60_000),
            Date(now + 3650L * 24 * 60 * 60 * 1000),
            subject,
            keys.public,
        )
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(sanNames(names).toTypedArray()))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(purposes.toTypedArray()))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(BouncyCastleProvider.PROVIDER_NAME).build(keys.private)
        val certificate = JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
        return TlsMaterial(listOf(certificate), keys.private)
    }

    fun parsePem(certificatePem: String, privateKeyPem: String): TlsMaterial {
        ensureProvider()
        if (certificatePem.length > MAX_BYTES || privateKeyPem.length > MAX_BYTES) {
            throw IllegalArgumentException("certificate PEM is larger than 256 KiB")
        }
        if (certificatePem.contains("PRIVATE KEY")) {
            throw IllegalArgumentException("certificate PEM must not contain a private key")
        }
        try {
            val chain = readCertificates(certificatePem)
            val key = readPrivateKey(privateKeyPem)
            checkChain(chain, key)
            return TlsMaterial(chain, key)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("certificate PEM could not be parsed")
        }
    }

    fun parsePkcs12(bytes: ByteArray, password: CharArray): TlsMaterial {
        ensureProvider()
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException("PKCS#12 is larger than 256 KiB")
        val store = KeyStore.getInstance("PKCS12")
        try {
            store.load(ByteArrayInputStream(bytes), password)
        } catch (_: Exception) {
            throw IllegalArgumentException("PKCS#12 could not be opened")
        }
        val alias = store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
            ?: throw IllegalArgumentException("PKCS#12 has no private key")
        val key = try {
            store.getKey(alias, password) as? PrivateKey
        } catch (_: Exception) {
            null
        } ?: throw IllegalArgumentException("PKCS#12 private key could not be read")
        val chain = store.getCertificateChain(alias)?.mapNotNull { it as? X509Certificate }.orEmpty()
        if (chain.isEmpty()) throw IllegalArgumentException("PKCS#12 has no certificate")
        checkChain(chain, key)
        return TlsMaterial(chain, key)
    }

    fun fingerprint(certificate: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return java.util.HexFormat.of().formatHex(digest)
    }

    fun certificatePem(chain: List<X509Certificate>): String = chain.joinToString("") { pem("CERTIFICATE", it.encoded) }

    fun privateKeyPem(key: PrivateKey): String = pem("PRIVATE KEY", key.encoded)

    /** Parse an unencrypted PEM private key (PKCS#8 or PKCS#1). Used for the pending CSR key on disk. */
    fun parsePrivateKey(pem: String): PrivateKey {
        ensureProvider()
        return readPrivateKey(pem)
    }

    /**
     * Make a new key pair and a PKCS#10 request for it. The subject alternative names go in the
     * extension-request attribute. The caller keeps the private key on the gateway.
     */
    fun createCsr(request: CsrRequest): Pair<String, java.security.KeyPair> {
        ensureProvider()
        val ec = request.keyType == "EC"
        val generator = java.security.KeyPairGenerator.getInstance(if (ec) "EC" else "RSA")
        if (ec) generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1")) else generator.initialize(request.keySize)
        val keys = generator.generateKeyPair()
        val subject = X500NameBuilder(BCStyle.INSTANCE).apply {
            request.country?.let { addRDN(BCStyle.C, it) }
            request.state?.let { addRDN(BCStyle.ST, it) }
            request.locality?.let { addRDN(BCStyle.L, it) }
            request.organization?.let { addRDN(BCStyle.O, it) }
            request.organizationalUnit?.let { addRDN(BCStyle.OU, it) }
            addRDN(BCStyle.CN, request.commonName)
        }.build()
        val extensions = ExtensionsGenerator()
        extensions.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(request.sans.map { generalName(it) }.toTypedArray()),
        )
        extensions.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        extensions.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        val builder = JcaPKCS10CertificationRequestBuilder(subject, keys.public)
            .addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
        val algorithm = if (ec) "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(algorithm).setProvider(BouncyCastleProvider.PROVIDER_NAME).build(keys.private)
        return pem("CERTIFICATE REQUEST", builder.build(signer).encoded) to keys
    }

    /** Public summary of a stored request: subject, names, a hash of its public key, and the PEM itself. */
    fun csrView(csrPem: String): Map<String, Any?> {
        ensureProvider()
        val request = PEMParser(StringReader(csrPem)).use { it.readObject() as? PKCS10CertificationRequest }
            ?: throw IllegalStateException("stored certificate request could not be read")
        val sans = ArrayList<String>()
        for (attribute in request.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest)) {
            val ext = Extensions.getInstance(attribute.attrValues.getObjectAt(0))
            val names = GeneralNames.fromExtensions(ext, Extension.subjectAlternativeName) ?: continue
            for (n in names.names) {
                when (n.tagNo) {
                    GeneralName.dNSName -> sans.add("DNS:${n.name}")
                    GeneralName.iPAddress -> sans.add("IP:" + InetAddress.getByAddress(DEROctetString.getInstance(n.name).octets).hostAddress)
                }
            }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(request.subjectPublicKeyInfo.encoded)
        return linkedMapOf(
            "subject" to request.subject.toString(),
            "sans" to sans,
            "publicKeySha256" to java.util.HexFormat.of().formatHex(digest),
            "csrPem" to csrPem,
        )
    }

    /**
     * Put a CA reply (any order, root optional) into leaf-first order for [key]. The leaf is the
     * certificate whose public key pairs with [key]. Every other certificate must sit on its issuer path.
     */
    fun chainForKey(certificatePem: String, key: PrivateKey): TlsMaterial {
        ensureProvider()
        if (certificatePem.length > MAX_BYTES) throw IllegalArgumentException("certificate PEM is larger than 256 KiB")
        if (certificatePem.contains("PRIVATE KEY")) throw IllegalArgumentException("certificate PEM must not contain a private key")
        val certificates = try {
            readCertificates(certificatePem)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("certificate PEM could not be parsed")
        }
        val leaf = certificates.firstOrNull { matches(it, key) }
            ?: throw IllegalArgumentException("no certificate in the reply matches the pending request's key")
        val remaining = certificates.filter { it !== leaf }.toMutableList()
        val chain = arrayListOf(leaf)
        while (remaining.isNotEmpty()) {
            val tail = chain.last()
            if (tail.subjectX500Principal == tail.issuerX500Principal) break
            val issuer = remaining.firstOrNull { it.subjectX500Principal == tail.issuerX500Principal } ?: break
            remaining.remove(issuer)
            chain.add(issuer)
        }
        if (remaining.isNotEmpty()) throw IllegalArgumentException("certificate chain does not link")
        checkChain(chain, key)
        return TlsMaterial(chain, key)
    }

    /**
     * Check one subject alternative name for a CSR. The `DNS:` and `IP:` prefixes are optional.
     * Returns the normalized `DNS:name` or `IP:address` label.
     */
    fun csrSan(raw: String): String {
        val text = raw.trim()
        val (kind, value) = when {
            text.startsWith("DNS:", ignoreCase = true) -> "DNS" to text.substring(4).trim()
            text.startsWith("IP:", ignoreCase = true) -> "IP" to text.substring(3).trim()
            IPV4.matches(text) || text.contains(':') -> "IP" to text
            else -> "DNS" to text
        }
        if (kind == "IP") {
            val ok = if (IPV4.matches(value)) value.split('.').all { it.toIntOrNull() in 0..255 }
            else value.contains(':') && value.matches(IPV6_CHARS)
            if (!ok) throw IllegalArgumentException("subject alternative name '$raw' is not an IP address")
            // The character check above keeps getByName from doing a DNS lookup; it still rejects a
            // malformed literal such as "1:2:3", which must be a validation error and not a 500.
            val address = try {
                InetAddress.getByName(value)
            } catch (_: java.net.UnknownHostException) {
                throw IllegalArgumentException("subject alternative name '$raw' is not an IP address")
            }
            return "IP:" + address.hostAddress
        }
        if (value.length > 253 || !value.matches(DNS_NAME)) {
            throw IllegalArgumentException("subject alternative name '$raw' is not a DNS name")
        }
        return "DNS:$value"
    }

    private fun generalName(label: String): GeneralName =
        if (label.startsWith("IP:")) GeneralName(GeneralName.iPAddress, label.removePrefix("IP:"))
        else GeneralName(GeneralName.dNSName, label.removePrefix("DNS:"))

    fun sanLabels(certificate: X509Certificate): List<String> {
        val names = certificate.subjectAlternativeNames ?: return emptyList()
        val out = ArrayList<String>()
        for (name in names) {
            if (name.size < 2) continue
            when ((name[0] as Number).toInt()) {
                2 -> out.add("DNS:${name[1]}")
                7 -> out.add("IP:${ipText(name[1])}")
            }
        }
        return out
    }

    fun view(material: TlsMaterial, source: String): Map<String, Any?> {
        val leaf = material.leaf
        return linkedMapOf(
            "subject" to leaf.subjectX500Principal.name,
            "issuer" to leaf.issuerX500Principal.name,
            "notBefore" to leaf.notBefore.toInstant().toString(),
            "notAfter" to leaf.notAfter.toInstant().toString(),
            "sha256Fingerprint" to material.fingerprint(),
            "sans" to sanLabels(leaf),
            "source" to source,
        )
    }

    private fun checkChain(chain: List<X509Certificate>, key: PrivateKey) {
        if (chain.isEmpty()) throw IllegalArgumentException("certificate chain is empty")
        if (!matches(chain[0], key)) throw IllegalArgumentException("private key does not match the certificate")
        for (i in 0 until chain.size - 1) {
            try {
                chain[i].verify(chain[i + 1].publicKey)
            } catch (_: Exception) {
                throw IllegalArgumentException("certificate chain does not link")
            }
        }
    }

    private fun matches(certificate: X509Certificate, key: PrivateKey): Boolean {
        val algorithm = when (key.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> return false
        }
        return try {
            val data = "oip-https".toByteArray()
            val signature = Signature.getInstance(algorithm)
            signature.initSign(key)
            signature.update(data)
            val signed = signature.sign()
            signature.initVerify(certificate.publicKey)
            signature.update(data)
            signature.verify(signed)
        } catch (_: Exception) {
            false
        }
    }

    private fun readCertificates(pem: String): List<X509Certificate> {
        val out = ArrayList<X509Certificate>()
        PEMParser(StringReader(pem)).use { parser ->
            while (true) {
                when (val obj = parser.readObject() ?: break) {
                    is X509CertificateHolder -> out.add(
                        JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(obj),
                    )
                    else -> throw IllegalArgumentException("certificate PEM must contain X.509 certificates")
                }
            }
        }
        if (out.isEmpty()) {
            val fromJdk = CertificateFactory.getInstance("X.509").generateCertificates(pem.byteInputStream())
            fromJdk.filterIsInstance<X509Certificate>().forEach { out.add(it) }
        }
        if (out.isEmpty()) throw IllegalArgumentException("certificate chain is empty")
        return out
    }

    private fun readPrivateKey(pem: String): PrivateKey {
        if (pem.contains("ENCRYPTED")) {
            throw IllegalArgumentException("encrypted private keys are not accepted; upload PKCS#12 or an unencrypted key")
        }
        PEMParser(StringReader(pem)).use { parser ->
            val converter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
            return when (val obj = parser.readObject() ?: throw IllegalArgumentException("private key PEM is empty")) {
                is PEMKeyPair -> converter.getKeyPair(obj).private
                is PrivateKeyInfo -> converter.getPrivateKey(obj)
                is PKCS8EncryptedPrivateKeyInfo ->
                    throw IllegalArgumentException("encrypted private keys are not accepted; upload PKCS#12 or an unencrypted key")
                else -> throw IllegalArgumentException("private key PEM must be PKCS#8 or PKCS#1")
            }
        }
    }

    private fun sanNames(names: Collection<String>): List<GeneralName> {
        val out = LinkedHashSet<String>()
        out.add("dns:localhost")
        out.add("ip:127.0.0.1")
        for (name in names) addSan(out, name)
        return out.map { token ->
            if (token.startsWith("ip:")) GeneralName(GeneralName.iPAddress, token.removePrefix("ip:"))
            else GeneralName(GeneralName.dNSName, token.removePrefix("dns:"))
        }
    }

    private fun addSan(out: MutableSet<String>, raw: String) {
        val name = raw.trim().removePrefix("[").removeSuffix("]")
        if (name.isEmpty() || name == "0.0.0.0" || name == "::" || name == "localhost" || name == "127.0.0.1") return
        if (IPV4.matches(name) && name.split('.').all { octet -> octet.toIntOrNull() in 0..255 }) {
            out.add("ip:$name")
        } else if (name.contains(':') && runCatching { InetAddress.getByName(name) }.isSuccess) {
            out.add("ip:$name")
        } else if (name.matches(Regex("""[A-Za-z0-9._-]{1,253}"""))) {
            out.add("dns:$name")
        }
    }

    private fun pem(type: String, der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der)
        return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
    }

    private fun serial(): BigInteger {
        val value = BigInteger(128, SecureRandom())
        return if (value == BigInteger.ZERO) BigInteger.ONE else value
    }

    private fun ipText(value: Any?): String = when (value) {
        is String -> value
        is ByteArray -> InetAddress.getByAddress(value).hostAddress
        else -> ""
    }

    private fun ensureProvider() = synchronized(lock) {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}

/** Location of a force-HTTPS redirect. The public address wins over the request Host header. */
object HttpsRedirect {
    fun url(
        publicAddress: String?,
        publicHttpsPort: Int?,
        listenPort: Int?,
        requestHost: String?,
        fallbackHost: String,
        path: String,
        query: String?,
    ): String? {
        val port = publicHttpsPort ?: listenPort ?: return null
        val host = publicAddress ?: requestHostName(requestHost) ?: fallbackHost
        val rendered = if (host.startsWith("[")) host else if (host.contains(':')) "[$host]" else host
        val authority = if (port == 443) rendered else "$rendered:$port"
        val p = if (path.isBlank()) "/" else path
        val q = if (query.isNullOrEmpty()) "" else "?$query"
        return "https://$authority$p$q"
    }

    /** A hostname or bracketed IPv6 address. Anything else is ignored so Host cannot become an open redirect. */
    fun requestHostName(header: String?): String? {
        val raw = header?.trim()?.substringBefore(',')?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 253 || raw.contains('/') || raw.contains('@')) return null
        val host = if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            if (end < 2) return null
            raw.substring(0, end + 1)
        } else {
            raw.substringBefore(':')
        }
        if (host.startsWith("[")) return host
        if (!host.matches(Regex("""[A-Za-z0-9._-]+"""))) return null
        return host
    }
}

/**
 * A certificate signing request as the admin enters it. [sans] are normalized `DNS:`/`IP:` labels.
 * The secure-communication page covers making a request on the gateway and then installing the CA's reply
 * (-).
 */
data class CsrRequest(
    val commonName: String,
    val sans: List<String>,
    val organization: String? = null,
    val organizationalUnit: String? = null,
    val locality: String? = null,
    val state: String? = null,
    val country: String? = null,
    val keyType: String = "RSA",
    val keySize: Int = 2048,
) {
    init {
        require(printable(commonName)) { "commonName must be 1 to 64 printable characters" }
        for ((field, value) in listOf(
            "organization" to organization, "organizationalUnit" to organizationalUnit,
            "locality" to locality, "state" to state,
        )) {
            if (value != null) require(printable(value)) { "$field must be 1 to 64 printable characters" }
        }
        if (country != null) require(country.matches(Regex("[A-Z]{2}"))) { "country must be a two-letter code" }
        require(keyType == "RSA" || keyType == "EC") { "keyType must be RSA or EC" }
        if (keyType == "RSA") require(keySize in setOf(2048, 3072, 4096)) { "keySize must be 2048, 3072, or 4096" }
        require(sans.isNotEmpty() && sans.size <= 50) { "between 1 and 50 subject alternative names are allowed" }
    }

    companion object {
        private val COUNTRY = Regex("[A-Za-z]{2}")

        private fun printable(value: String) = value.isNotBlank() && value.length <= 64 && value.none { it.isISOControl() }

        /** Read and check a JSON body. Blank optional fields count as absent. */
        fun parse(body: Map<String, Any?>): CsrRequest {
            fun text(key: String): String? {
                val v = body[key] ?: return null
                if (v !is String) throw IllegalArgumentException("$key must be a string")
                return v.trim().ifEmpty { null }
            }
            val commonName = text("commonName") ?: throw IllegalArgumentException("commonName is required")
            val rawSans = when (val v = body["sans"]) {
                null -> emptyList()
                is List<*> -> v.map { it as? String ?: throw IllegalArgumentException("sans must be strings") }
                else -> throw IllegalArgumentException("sans must be a list")
            }.filter { it.isNotBlank() }
            val sans = rawSans.map { TlsCertificates.csrSan(it) }.distinct()
                .ifEmpty { listOf(TlsCertificates.csrSan(commonName)) }
            val keyType = (text("keyType") ?: "RSA").uppercase()
            val keySize = when (val v = body["keySize"]) {
                null -> 2048
                is Number -> v.toInt()
                else -> throw IllegalArgumentException("keySize must be a number")
            }
            // Check the raw text before normalising case, so a non-ASCII letter that upper-cases to A-Z
            // (for example "ß" to "SS") is refused rather than accepted.
            val country = text("country")?.also {
                require(COUNTRY.matches(it)) { "country must be a two-letter code" }
            }?.uppercase(java.util.Locale.ROOT)
            return CsrRequest(
                commonName, sans, text("organization"), text("organizationalUnit"), text("locality"), text("state"),
                country, keyType, keySize,
            )
        }
    }
}

/**
 * Which TLS protocols and cipher suites the web listener offers.
 *
 * The web-server settings page lets an administrator choose protocols and cipher suites
 * (-).
 * TLS 1.2 is the floor here. TLS 1.1 and older cannot be chosen, and the listener excludes them even if the
 * JDK would allow them. Suites that match [WEAK] cannot be chosen and are also dropped from the default
 * list: NULL, anonymous, export, RC4, DES/3DES, MD5, SHA-1 MAC, static RSA/DH/ECDH key exchange, and CBC.
 * No explicit list means the JDK defaults minus [WEAK] (DIFFERENCES).
 */
data class TlsPolicy(val protocols: List<String>, val cipherSuites: List<String>?) {
    fun toMap(): Map<String, Any?> = linkedMapOf("protocols" to protocols, "cipherSuites" to cipherSuites)

    companion object {
        val ALLOWED_PROTOCOLS = listOf("TLSv1.2", "TLSv1.3")
        val DEFAULT = TlsPolicy(ALLOWED_PROTOCOLS, null)
        val REFUSED_PROTOCOLS = listOf("SSL", "SSLv2", "SSLv2Hello", "SSLv3", "TLSv1", "TLSv1.1")
        val WEAK: List<String> = listOf(
            "^.*_NULL_.*$", "^.*_anon_.*$", "^.*_EXPORT.*$", "^.*_RC4_.*$", "^.*_DES40_.*$", "^.*_DES_.*$",
            "^.*3DES.*$", "^.*_MD5$", "^.*_SHA$", "^.*_SHA1$", "^SSL_.*$", "^TLS_RSA_.*$", "^TLS_DH_.*$", "^TLS_ECDH_.*$",
            "^.*_CBC_.*$", "^TLS_EMPTY_RENEGOTIATION_INFO_SCSV$", "^TLS_FALLBACK_SCSV$",
        )
        private val weakRegex = WEAK.map { Regex(it) }

        fun weak(suite: String): Boolean = weakRegex.any { it.matches(suite) }

        /** TLS 1.3 suite names carry no key-exchange part. */
        fun tls13(suite: String): Boolean = suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_")

        /** Suites the JDK supports and the policy allows, in the JDK's order. */
        fun available(): List<String> =
            javax.net.ssl.SSLContext.getDefault().supportedSSLParameters.cipherSuites.filter { !weak(it) }

        /** Check an admin's choice. Throws [IllegalArgumentException] with a message that is safe to show. */
        fun parse(body: Map<String, Any?>): TlsPolicy {
            val protocols = when (val v = body["protocols"]) {
                is List<*> -> v.map { it as? String ?: throw IllegalArgumentException("protocols must be strings") }.distinct()
                else -> throw IllegalArgumentException("protocols is required")
            }
            if (protocols.isEmpty()) throw IllegalArgumentException("choose at least one protocol")
            for (p in protocols) {
                if (p in REFUSED_PROTOCOLS) throw IllegalArgumentException("$p is below the TLS 1.2 floor")
                if (p !in ALLOWED_PROTOCOLS) throw IllegalArgumentException("unknown protocol $p")
            }
            val suites = when (val v = body["cipherSuites"]) {
                null -> null
                is List<*> -> v.map { it as? String ?: throw IllegalArgumentException("cipherSuites must be strings") }.distinct()
                else -> throw IllegalArgumentException("cipherSuites must be a list")
            }?.ifEmpty { null }
            if (suites != null) {
                if (suites.size > 64) throw IllegalArgumentException("at most 64 cipher suites")
                val supported = javax.net.ssl.SSLContext.getDefault().supportedSSLParameters.cipherSuites.toSet()
                for (s in suites) {
                    if (weak(s)) throw IllegalArgumentException("cipher suite $s is on the weak list")
                    if (s !in supported) throw IllegalArgumentException("cipher suite $s is not supported by this JVM")
                }
                for (p in protocols) {
                    val usable = suites.any { if (p == "TLSv1.3") tls13(it) else !tls13(it) }
                    if (!usable) throw IllegalArgumentException("$p is enabled but none of the chosen cipher suites work with it")
                }
            }
            return TlsPolicy(ALLOWED_PROTOCOLS.filter { it in protocols }, suites)
        }
    }
}
