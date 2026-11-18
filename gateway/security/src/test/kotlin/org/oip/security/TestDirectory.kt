package org.oip.security

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor
import com.unboundid.ldif.LDIFReader
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.oip.config.OutboundTls
import java.io.StringReader
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Collections
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import java.net.Socket

/**
 * An in-memory LDAP directory for the user source tests (SEC-007): people, a service account and groups, over plain
 * LDAP, LDAPS or StartTLS on a loopback port. It records which connections did StartTLS and which binds were sent, so
 * a test can tell that a bind ran on the upgraded connection.
 */
class TestDirectory private constructor(val server: InMemoryDirectoryServer, val mode: Mode) : AutoCloseable {
    enum class Mode { PLAIN, LDAPS, STARTTLS }

    val port: Int get() = server.getListenPort(mode.name)
    val url: String get() = (if (mode == Mode.LDAPS) "ldaps" else "ldap") + "://localhost:$port"

    /**
     * Bind DNs in the order they were sent, with the connection they came on. The server numbers connections from 0 in
     * the order it accepts them.
     */
    val binds: MutableList<Pair<Long, String>> = Collections.synchronizedList(ArrayList())
    /** Client ports of the connections that StartTLS upgraded. */
    val startTlsConnections: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    override fun close() = server.shutDown(true)

    companion object {
        const val BASE = "dc=example,dc=test"
        const val SERVICE_DN = "cn=gateway,ou=system,$BASE"
        const val SERVICE_PASSWORD = "svc-Bind-Secret-1"

        private val LDIF = """
            dn: $BASE
            objectClass: domain
            dc: example

            dn: ou=people,$BASE
            objectClass: organizationalUnit
            ou: people

            dn: ou=groups,$BASE
            objectClass: organizationalUnit
            ou: groups

            dn: ou=system,$BASE
            objectClass: organizationalUnit
            ou: system

            dn: $SERVICE_DN
            objectClass: person
            cn: gateway
            sn: gateway
            userPassword: $SERVICE_PASSWORD

            dn: uid=alice,ou=people,$BASE
            objectClass: inetOrgPerson
            uid: alice
            cn: Alice Example
            sn: Example
            mail: alice@example.test
            userPassword: alice-Pass-1
            memberOf: cn=admins,ou=groups,$BASE

            dn: uid=bob,ou=people,$BASE
            objectClass: inetOrgPerson
            uid: bob
            cn: Bob Example
            sn: Example
            mail: bob@example.test
            userPassword: bob-Pass-1

            dn: uid=carol,ou=people,$BASE
            objectClass: inetOrgPerson
            uid: carol
            cn: Carol Example
            sn: Example
            userPassword: carol-Pass-1

            dn: cn=admins,ou=groups,$BASE
            objectClass: groupOfNames
            cn: admins
            member: uid=alice,ou=people,$BASE

            dn: cn=operators,ou=groups,$BASE
            objectClass: groupOfNames
            cn: operators
            member: uid=bob,ou=people,$BASE

            dn: cn=shift-a,ou=groups,$BASE
            objectClass: groupOfNames
            cn: shift-a
            member: uid=carol,ou=people,$BASE

            dn: cn=plant-staff,ou=groups,$BASE
            objectClass: groupOfNames
            cn: plant-staff
            member: cn=shift-a,ou=groups,$BASE

            dn: cn=site-staff,ou=groups,$BASE
            objectClass: groupOfNames
            cn: site-staff
            member: cn=plant-staff,ou=groups,$BASE
        """.trimIndent()

        fun start(mode: Mode = Mode.PLAIN, tls: TestTls? = null): TestDirectory {
            val holder = arrayOfNulls<TestDirectory>(1)
            val config = InMemoryDirectoryServerConfig(BASE)
            // The default schema has no memberOf; the tests set it by hand, as a directory that computes it would.
            config.setSchema(null)
            val loopback = InetAddress.getLoopbackAddress()
            val listener = when (mode) {
                Mode.PLAIN -> InMemoryListenerConfig.createLDAPConfig(mode.name, loopback, 0, null)
                Mode.STARTTLS -> InMemoryListenerConfig.createLDAPConfig(mode.name, loopback, 0, Upgrades(tls!!.server.socketFactory) { port -> holder[0]?.startTlsConnections?.add(port) })
                Mode.LDAPS -> InMemoryListenerConfig.createLDAPSConfig(mode.name, loopback, 0, tls!!.server.serverSocketFactory, null)
            }
            config.setListenerConfigs(listener)
            config.addInMemoryOperationInterceptor(object : InMemoryOperationInterceptor() {
                override fun processSimpleBindRequest(request: InMemoryInterceptedSimpleBindRequest) {
                    holder[0]?.binds?.add(request.connectionID to request.request.bindDN)
                }
            })
            val server = InMemoryDirectoryServer(config)
            LDIFReader(java.io.BufferedReader(StringReader(LDIF))).use { reader ->
                while (true) server.add(reader.readEntry() ?: break)
            }
            server.startListening()
            return TestDirectory(server, mode).also { holder[0] = it }
        }
    }
}

/** A self-signed server certificate for [host] and a client trust that holds it (or not). */
class TestTls(host: String = "localhost") {
    val certificate: X509Certificate
    val server: SSLContext

    init {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Instant.now()
        val names = GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, host)))
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$host"), BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minusSeconds(3600)),
            Date.from(now.plusSeconds(86_400)), X500Name("CN=$host"), keys.public,
        ).addExtension(Extension.subjectAlternativeName, false, names)
        certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", keys.private, CharArray(0), arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        server = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    /** Install an outbound trust that holds this certificate, as the gateway's trust store would once it is trusted. */
    fun trust(): AutoCloseable {
        val ts = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", certificate)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ts) }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        return OutboundTls.install { ctx }
    }
}

/** The server side of StartTLS: records the client port of each connection it upgrades. */
private class Upgrades(private val delegate: SSLSocketFactory, private val upgraded: (Int) -> Unit) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
        upgraded(s.port)
        return delegate.createSocket(s, host, port, autoClose)
    }
    override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = delegate.createSocket(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort)
}
