package org.oip.opcua

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator
import java.security.KeyPair
import java.security.Security
import java.security.cert.X509Certificate

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

    fun generate(applicationUri: String, commonName: String): OpcUaCertificate {
        ensureProvider()
        val keys = SelfSignedCertificateGenerator.generateRsaKeyPair(2048)
        val certificate = SelfSignedCertificateBuilder(keys)
            .setCommonName(commonName)
            .setOrganization("OIP")
            .setApplicationUri(applicationUri)
            .addDnsName("localhost")
            .addIpAddress("127.0.0.1")
            .build()
        return OpcUaCertificate(keys, certificate)
    }
}
