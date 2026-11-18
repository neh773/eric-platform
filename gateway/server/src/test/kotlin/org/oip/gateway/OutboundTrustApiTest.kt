package org.oip.gateway

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Clock
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.io.path.writeText

/**
 * SEC-022: the gateway's outbound TLS clients trust the JVM's public authorities plus the outbound trust store. A
 * server certificate neither accepts is refused and listed as rejected with the client and host that met it; trusting
 * it from the API lets the next connection through; removing it refuses again. Every change is audited and needs
 * `gateway.write`. Exercised through a real outbound client: the identity-provider discovery import.
 *
 * Doc source: FEATURES SEC-022.
 */
class OutboundTrustApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private lateinit var server: HttpsServer
    private lateinit var cert: X509Certificate
    private lateinit var keys: KeyPair
    private val base = OutboundTrustApi.BASE

    @BeforeEach fun setUp() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0, adminDist = null, webDist = null).start()
        admin = Http(gw.port())
        gw.loginAdmin(admin)
        keys = rsaKeyPair()
        cert = selfSigned(keys, "CN=plant idp")
        server = httpsServer(keys, cert)
    }

    @AfterEach fun tearDown() {
        server.stop(0)
        gw.close()
    }

    private val issuer get() = "https://127.0.0.1:${server.address.port}"

    private fun importDiscovery(): Http.Res =
        admin.post("${IdentityProviderApi.BASE}/import", Json.write(mapOf("type" to "oidc", "issuer" to issuer)))

    @Suppress("UNCHECKED_CAST")
    private fun list(kind: String, http: Http = admin): List<Map<String, Any?>> = http.get(base).json[kind] as List<Map<String, Any?>>

    @Test fun aRefusedServerIsListedAndTrustingItLetsTheNextConnectionThrough() {
        val refused = importDiscovery()
        assertEquals(422, refused.status, refused.body)
        val thumb = OutboundTrust.thumbprintOf(cert)
        val rejected = list("rejected")
        assertEquals(1, rejected.size, rejected.toString())
        assertEquals(thumb, rejected[0]["thumbprint"])
        assertEquals("plant idp", rejected[0]["commonName"])
        assertEquals("identity-provider", rejected[0]["client"])
        assertEquals("127.0.0.1:${server.address.port}", rejected[0]["seenAt"])
        assertEquals(emptyList<Any?>(), list("trusted"))

        // An operator (gateway.read) may read the store but not change it (gateway.write); a viewer may not read it.
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.createUser("vic", setOf(DefaultRoles.VIEWER))
        val operator = Http(gw.port()).also { assertEquals(200, it.login("ada", TEST_PASSWORD).status) }
        assertEquals(1, list("rejected", operator).size)
        assertEquals(403, operator.post("$base/rejected/$thumb/trust", "{}").status)
        assertEquals(403, Http(gw.port()).also { assertEquals(200, it.login("vic", TEST_PASSWORD).status) }.get(base).status)
        assertEquals(401, Http(gw.port()).get(base).status)

        val trusted = admin.post("$base/rejected/$thumb/trust", "{}")
        assertEquals(200, trusted.status, trusted.body)
        assertEquals(1L, gw.auditCount(OUTBOUND_CERT_TRUSTED_ACTION, actor = "admin"))
        assertEquals(listOf(thumb), list("trusted").map { it["thumbprint"] })
        assertEquals(emptyList<Any?>(), list("rejected"))
        val imported = importDiscovery()
        assertEquals(200, imported.status, imported.body)
        assertEquals(issuer, (imported.json["fields"] as Map<*, *>)["issuer"])

        // Removing it refuses the server's next new connection (an open pooled connection may finish its work; a fresh
        // port stands for a new connection here).
        assertEquals(204, admin.delete("$base/trusted/$thumb").status)
        server.stop(0)
        server = httpsServer(keys, cert)
        assertEquals(1L, gw.auditCount(OUTBOUND_CERT_REMOVED_ACTION, actor = "admin"))
        assertEquals(422, importDiscovery().status)
        assertEquals(204, admin.delete("$base/rejected/$thumb").status)
        assertEquals(1L, gw.auditCount(OUTBOUND_CERT_REJECTED_DELETED_ACTION, actor = "admin"))
        assertEquals(404, admin.delete("$base/rejected/$thumb").status)
        assertEquals(400, admin.delete("$base/rejected/not-a-thumbprint").status)
    }

    @Test fun aCertificateCanBeTrustedAheadOfTheFirstConnection() {
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"
        val created = admin.post("$base/trusted", Json.write(mapOf("certificate" to pem)))
        assertEquals(201, created.status, created.body)
        assertEquals(1L, gw.auditCount(OUTBOUND_CERT_IMPORTED_ACTION, actor = "admin"))
        assertEquals(200, importDiscovery().status)
        assertEquals(emptyList<Any?>(), list("rejected"))
        assertEquals(422, admin.post("$base/trusted", Json.write(mapOf("certificate" to "not a certificate"))).status)
        assertEquals(400, admin.post("$base/trusted", "{}").status)
    }

    @Test fun theStoreNeverAcceptsClientCertificatesAndKeepsABoundedRejectedList() {
        val store = OutboundTrust(tmp.resolve("store"), Clock.systemUTC())
        val tm = store.trustManager("test")
        assertThrows(CertificateException::class.java) { tm.checkClientTrusted(arrayOf(cert), "RSA") }
        store.importTrusted(Base64.getEncoder().encodeToString(cert.encoded))
        assertThrows(CertificateException::class.java) { tm.checkClientTrusted(arrayOf(cert), "RSA") }
        // A trusted self-signed server certificate passes; an unknown one is refused and recorded.
        tm.checkServerTrusted(arrayOf(cert), "RSA")
        val otherKeys = rsaKeyPair()
        repeat(OutboundTrust.MAX_REJECTED + 3) { i ->
            val other = selfSigned(otherKeys, "CN=other $i")
            assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(other), "RSA") }
        }
        assertEquals(OutboundTrust.MAX_REJECTED, store.rejected().size)
        assertEquals(1, store.trusted().size)
    }

    /** The database client (the PostgreSQL driver's socket factory) refuses an untrusted server until it is trusted. */
    @Test fun theDatabaseSocketFactoryUsesTheStore() {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("db", keys.private, CharArray(0), arrayOf(cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        val listener = ssl.serverSocketFactory.createServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress()) as javax.net.ssl.SSLServerSocket
        val accept = Thread {
            while (!listener.isClosed) {
                runCatching { listener.accept().use { s -> runCatching { (s as javax.net.ssl.SSLSocket).startHandshake(); s.getOutputStream().write(1) } } }
            }
        }.apply { isDaemon = true; start() }
        try {
            fun handshake() = org.oip.db.OutboundSslSocketFactory().createSocket("127.0.0.1", listener.localPort).use { (it as javax.net.ssl.SSLSocket).startHandshake() }
            assertThrows(javax.net.ssl.SSLHandshakeException::class.java) { handshake() }
            val rejected = list("rejected").single()
            assertEquals("database", rejected["client"])
            assertEquals(200, admin.post("$base/rejected/${rejected["thumbprint"]}/trust", "{}").status)
            handshake()
        } finally {
            listener.close()
            accept.join(2_000)
        }
    }

    private fun selfSigned(keys: KeyPair, subject: String): X509Certificate {
        val name = X500Name(subject)
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now).add(BigInteger.valueOf(SecureRandom().nextInt(1_000_000).toLong())),
            Date(now - 60_000), Date(now + 86_400_000L), name, keys.public)
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.iPAddress, "127.0.0.1")))
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
    }

    private fun httpsServer(keys: KeyPair, cert: X509Certificate): HttpsServer {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("server", keys.private, CharArray(0), arrayOf(cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        val s = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.httpsConfigurator = HttpsConfigurator(ssl)
        s.createContext("/.well-known/openid-configuration") { ex ->
            val body = Json.write(mapOf(
                "issuer" to issuer,
                "authorization_endpoint" to "$issuer/authorize",
                "token_endpoint" to "$issuer/token",
                "jwks_uri" to "$issuer/jwks",
                "response_types_supported" to listOf("code"),
                "code_challenge_methods_supported" to listOf("S256"),
            )).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        return s
    }
}
