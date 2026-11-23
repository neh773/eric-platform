package org.oip.opcua

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/** Application instance certificate used for a secure channel or an X.509 user token. */
data class OpcUaCertificate(val keyPair: KeyPair, val certificate: X509Certificate)

/**
 * Self-signed RSA certificate. The application URI is written into the subject alternative name;
 * OPC UA requires that URI to match the client's ApplicationUri.
 */
object OpcUaCertificates {
    fun ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val cache = ConcurrentHashMap<String, OpcUaCertificate>()
    // Local file protection is the folder's permissions; the store password only satisfies the format.
    private val STORE_PASSWORD = "eric-opcua-client".toCharArray()

    /**
     * The client's application instance certificate, made once and kept. With a PKI folder it is stored in
     * `own/client-<uri hash>.p12` and survives restarts, so a server that trusted it keeps trusting it;
     * without one it is kept for the life of the gateway process.
     */
    fun loadOrCreate(pkiDir: Path?, applicationUri: String, commonName: String): OpcUaCertificate {
        val key = "${pkiDir?.toAbsolutePath()}|$applicationUri"
        cache[key]?.let { return it }
        synchronized(this) {
            cache[key]?.let { return it }
            val file = pkiDir?.resolve("own")?.resolve("client-${hash(applicationUri)}.p12")
            val loaded = file?.takeIf { Files.isRegularFile(it) }?.let { runCatching { read(it) }.getOrNull() }
            val material = loaded ?: generate(applicationUri, commonName).also { made -> if (file != null) runCatching { write(file, made) } }
            cache[key] = material
            return material
        }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun read(file: Path): OpcUaCertificate {
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(file).use { store.load(it, STORE_PASSWORD) }
        val certificate = store.getCertificate("client") as X509Certificate
        val privateKey = store.getKey("client", STORE_PASSWORD) as PrivateKey
        return OpcUaCertificate(KeyPair(certificate.publicKey, privateKey), certificate)
    }

    private fun write(file: Path, material: OpcUaCertificate) {
        Files.createDirectories(file.parent)
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("client", material.keyPair.private, STORE_PASSWORD, arrayOf(material.certificate))
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.newOutputStream(temp).use { store.store(it, STORE_PASSWORD) }
        runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    fun generate(applicationUri: String, commonName: String): OpcUaCertificate {
        ensureProvider()
        val keys = SelfSignedCertificateGenerator.generateRsaKeyPair(2048)
        val certificate = SelfSignedCertificateBuilder(keys)
            .setCommonName(commonName)
            .setOrganization("ERIC")
            .setApplicationUri(applicationUri)
            .addDnsName("localhost")
            .addIpAddress("127.0.0.1")
            .build()
        return OpcUaCertificate(keys, certificate)
    }
}
