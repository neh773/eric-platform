package org.oip.opcua.server

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readBytes
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeBytes

/** Application instance certificate generated on first start and reused after that. */
internal data class ServerMaterial(val keyPair: KeyPair, val certificate: X509Certificate)

internal object ServerCertificates {
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun loadOrCreate(
        dir: Path,
        applicationUri: String,
        commonName: String,
        names: Collection<String>,
    ): ServerMaterial {
        ensureProvider()
        dir.createDirectories()
        val certFile = dir.resolve("server.crt")
        val keyFile = dir.resolve("server.key")
        if (certFile.exists() && keyFile.exists()) return load(certFile, keyFile)
        if (certFile.exists() || keyFile.exists()) {
            throw IllegalStateException("OPC UA server certificate in $dir is incomplete; expected both server.crt and server.key")
        }
        val created = generate(applicationUri, commonName, names)
        certFile.writeBytes(created.certificate.encoded)
        keyFile.writeBytes(created.keyPair.private.encoded)
        restrict(keyFile)
        restrict(certFile)
        return created
    }

    private fun generate(applicationUri: String, commonName: String, names: Collection<String>): ServerMaterial {
        val keys = SelfSignedCertificateGenerator.generateRsaKeyPair(2048)
        val builder = SelfSignedCertificateBuilder(keys)
            .setCommonName(commonName)
            .setOrganization("OIP")
            .setApplicationUri(applicationUri)
            .addDnsName("localhost")
            .addIpAddress("127.0.0.1")
        for (name in names) {
            if (name.isBlank() || name == "0.0.0.0" || name == "localhost" || name == "127.0.0.1") continue
            if (IPV4.matches(name)) builder.addIpAddress(name) else builder.addDnsName(name)
        }
        val certificate = builder.build()
        return ServerMaterial(keys, certificate)
    }

    private fun load(certFile: Path, keyFile: Path): ServerMaterial {
        val certificate = certFile.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        return ServerMaterial(KeyPair(certificate.publicKey, key), certificate)
    }

    private fun ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun restrict(path: Path) {
        runCatching {
            path.setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }
}
