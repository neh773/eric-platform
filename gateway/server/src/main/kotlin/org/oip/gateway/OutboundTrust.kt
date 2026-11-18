package org.oip.gateway

import org.oip.opcua.server.OpcUaCertificateTrust
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import javax.naming.ldap.LdapName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/** One certificate of the outbound trust store, as the admin page lists it. */
data class OutboundCertificate(
    /** SHA-256 of the DER encoding, lowercase hex. */
    val thumbprint: String,
    val subject: String,
    val commonName: String?,
    val issuer: String,
    val notBefore: Instant,
    val notAfter: Instant,
    /** Where a rejected certificate was last presented (`host:port`) and by which client; empty for a trusted one. */
    val seenAt: String = "",
    val client: String = "",
    val lastSeen: Instant? = null,
) {
    fun expired(now: Instant): Boolean = now.isAfter(notAfter) || now.isBefore(notBefore)
}

/**
 * The gateway's trust store for its own outbound TLS clients (SEC-022): database connections, MQTT brokers, HTTP
 * clients (scripts, event streams, identity-provider discovery and token calls) and mail servers.
 *
 * A server certificate is accepted when the JVM's default trust (the public certificate authorities) accepts it, or
 * when its chain ends at a certificate an administrator trusted here: a server's own self-signed certificate or a
 * private certificate authority. Host name checks still apply. A certificate neither accepts is refused, and its
 * leaf is kept in the rejected list with the host it came from and the client that met it, so an administrator can
 * trust it from the admin page. Trusting it moves it to the trusted list; the next connection uses it. Removing a
 * trusted certificate refuses new connections; a connection a client already holds open is not cut.
 *
 * Files: `<dataDir>/pki/outbound/trusted/<sha256>.der` and `<dataDir>/pki/outbound/rejected/<sha256>.der` with a
 * `.seen` file beside each rejected one. At most [MAX_REJECTED] rejected certificates are kept; the oldest goes first.
 *
 * Doc source: FEATURES SEC-022.
 */
class OutboundTrust(dataDir: Path, private val clock: Clock) {
    private val root = dataDir.resolve("pki").resolve("outbound")
    private val trustedDir = root.resolve("trusted")
    private val rejectedDir = root.resolve("rejected")
    private val lock = Any()

    init {
        Files.createDirectories(trustedDir)
        Files.createDirectories(rejectedDir)
    }

    fun trusted(): List<OutboundCertificate> = synchronized(lock) { read(trustedDir).map { describe(it) }.sortedBy { it.subject } }

    fun rejected(): List<OutboundCertificate> = synchronized(lock) {
        read(rejectedDir).map { cert -> describe(cert).withSeen(rejectedDir.resolve("${thumbprintOf(cert)}.seen")) }
            .sortedByDescending { it.lastSeen ?: Instant.EPOCH }
    }

    /** Trust a PEM or base64 DER certificate. Throws [IllegalArgumentException] for anything that is not one. */
    fun importTrusted(text: String): OutboundCertificate {
        val cert = OpcUaCertificateTrust.parseCertificate(text)
        synchronized(lock) {
            write(trustedDir, cert)
            delete(rejectedDir, thumbprintOf(cert))
            changed()
        }
        return describe(cert)
    }

    /** Move a rejected certificate to the trusted list; null when there is no such rejected certificate. */
    fun trustRejected(thumbprint: String): OutboundCertificate? = synchronized(lock) {
        val file = rejectedDir.resolve("$thumbprint.der")
        if (!Files.isRegularFile(file)) return null
        val cert = load(file) ?: return null
        write(trustedDir, cert)
        delete(rejectedDir, thumbprint)
        changed()
        describe(cert)
    }

    fun removeTrusted(thumbprint: String): OutboundCertificate? = synchronized(lock) {
        val cert = load(trustedDir.resolve("$thumbprint.der")) ?: return null
        delete(trustedDir, thumbprint)
        changed()
        describe(cert)
    }

    fun removeRejected(thumbprint: String): OutboundCertificate? = synchronized(lock) {
        val cert = load(rejectedDir.resolve("$thumbprint.der")) ?: return null
        delete(rejectedDir, thumbprint)
        describe(cert)
    }

    /**
     * An SSL context whose trust is the JVM default plus this store, for a client named [client] (shown beside a
     * certificate it rejects). The context reads the trusted list as it stands at each handshake, so a certificate
     * trusted later applies to the next connection of a context made earlier.
     */
    fun sslContext(client: String): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager(client)), SecureRandom())
        return context
    }

    /** The trust manager [sslContext] uses. */
    fun trustManager(client: String): X509ExtendedTrustManager = Composite(client, system)

    /** Bumped on every trusted-list change, so clients that cache a context can tell it is stale. */
    @Volatile var generation: Long = 0L
        private set

    private fun changed() {
        generation++
    }

    /** The JVM's default trust (its configured trust store), read once. */
    private val system: X509ExtendedTrustManager by lazy { extended(null) }

    /** A trust manager over the trusted list, kept until the next change. */
    @Volatile private var store: Pair<Long, X509ExtendedTrustManager?>? = null

    private fun currentStore(): X509ExtendedTrustManager? {
        val seen = generation
        store?.let { (g, tm) -> if (g == seen) return tm }
        val tm = buildStore()
        store = seen to tm
        return tm
    }

    private fun buildStore(): X509ExtendedTrustManager? {
        val certs = synchronized(lock) { read(trustedDir) }
        if (certs.isEmpty()) return null
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        certs.forEach { ks.setCertificateEntry(thumbprintOf(it), it) }
        return extended(ks)
    }

    private fun extended(ks: KeyStore?): X509ExtendedTrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
    }

    /** Record [chain]'s leaf as rejected, seen at [where] by [client]. Never throws. */
    internal fun recordRejected(chain: Array<X509Certificate>, where: String, client: String) {
        val leaf = chain.firstOrNull() ?: return
        runCatching {
            synchronized(lock) {
                val thumb = thumbprintOf(leaf)
                if (Files.isRegularFile(trustedDir.resolve("$thumb.der"))) return@synchronized
                write(rejectedDir, leaf)
                Files.writeString(rejectedDir.resolve("$thumb.seen"), "${clock.instant()}\n${where.take(300)}\n${client.take(100)}\n")
                val all = read(rejectedDir).map { thumbprintOf(it) }
                if (all.size > MAX_REJECTED) {
                    all.sortedBy { seenAt(rejectedDir.resolve("$it.seen")) ?: Instant.EPOCH }
                        .take(all.size - MAX_REJECTED).forEach { delete(rejectedDir, it) }
                }
            }
        }
    }

    private inner class Composite(
        private val client: String,
        private val system: X509ExtendedTrustManager,
    ) : X509ExtendedTrustManager() {
        private fun server(chain: Array<X509Certificate>, where: String, check: (X509ExtendedTrustManager) -> Unit) {
            val store = currentStore()
            val first = try {
                check(system)
                return
            } catch (e: CertificateException) {
                e
            }
            if (store != null) {
                try {
                    check(store)
                    return
                } catch (_: CertificateException) {
                    // Falls through to the rejection below.
                }
            }
            recordRejected(chain, where, client)
            throw first
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            server(chain, socket?.let { "${it.inetAddress?.hostAddress}:${it.port}" } ?: "") { it.checkServerTrusted(chain, authType, socket) }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            server(chain, engine?.let { "${it.peerHost}:${it.peerPort}" } ?: "") { it.checkServerTrusted(chain, authType, engine) }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            server(chain, "") { it.checkServerTrusted(chain, authType) }

        // This store is for the gateway's outbound clients; it never accepts client certificates.
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            throw CertificateException("outbound trust does not accept client certificates")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            throw CertificateException("outbound trust does not accept client certificates")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("outbound trust does not accept client certificates")

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + (currentStore()?.acceptedIssuers ?: emptyArray())
    }

    private fun read(dir: Path): List<X509Certificate> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".der") }.toList() }.mapNotNull { load(it) }
    }

    private fun load(file: Path): X509Certificate? = runCatching {
        if (!Files.isRegularFile(file)) return null
        Files.newInputStream(file).use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    }.getOrNull()

    private fun write(dir: Path, cert: X509Certificate) {
        val target = dir.resolve("${thumbprintOf(cert)}.der")
        val tmp = dir.resolve("${thumbprintOf(cert)}.tmp")
        Files.write(tmp, cert.encoded)
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun delete(dir: Path, thumbprint: String) {
        Files.deleteIfExists(dir.resolve("$thumbprint.der"))
        Files.deleteIfExists(dir.resolve("$thumbprint.seen"))
    }

    private fun seenAt(file: Path): Instant? = runCatching { Instant.parse(Files.readAllLines(file).first()) }.getOrNull()

    private fun OutboundCertificate.withSeen(file: Path): OutboundCertificate {
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return this
        return copy(
            lastSeen = lines.getOrNull(0)?.let { runCatching { Instant.parse(it) }.getOrNull() },
            seenAt = lines.getOrNull(1).orEmpty(),
            client = lines.getOrNull(2).orEmpty(),
        )
    }

    companion object {
        const val MAX_REJECTED = 64
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun validThumbprint(text: String): Boolean = HEX64.matches(text)

        fun thumbprintOf(cert: X509Certificate): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

        fun describe(cert: X509Certificate): OutboundCertificate {
            val subject = cert.subjectX500Principal.name
            return OutboundCertificate(
                thumbprint = thumbprintOf(cert),
                subject = subject,
                commonName = runCatching { LdapName(subject).rdns.lastOrNull { it.type.equals("CN", true) }?.value?.toString() }.getOrNull(),
                issuer = cert.issuerX500Principal.name,
                notBefore = cert.notBefore.toInstant(),
                notAfter = cert.notAfter.toInstant(),
            )
        }
    }
}
