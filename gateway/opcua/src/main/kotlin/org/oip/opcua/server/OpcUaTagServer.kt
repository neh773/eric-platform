package org.oip.opcua.server

import org.eclipse.milo.opcua.sdk.server.EndpointConfig
import org.eclipse.milo.opcua.sdk.server.OpcUaServer
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig
import org.eclipse.milo.opcua.sdk.server.ServiceSets
import org.eclipse.milo.opcua.sdk.server.Session
import org.eclipse.milo.opcua.sdk.server.servicesets.AttributeServiceSet
import org.eclipse.milo.opcua.sdk.server.servicesets.impl.DefaultAttributeServiceSet
import org.eclipse.milo.opcua.sdk.server.identity.AbstractUsernameIdentityValidator
import org.eclipse.milo.opcua.sdk.server.identity.DefaultUsernameIdentity
import org.eclipse.milo.opcua.sdk.server.identity.Identity
import org.eclipse.milo.opcua.stack.core.security.DefaultApplicationGroup
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager
import org.eclipse.milo.opcua.stack.core.security.DefaultServerCertificateValidator
import org.eclipse.milo.opcua.stack.core.security.KeyStoreCertificateStore
import org.eclipse.milo.opcua.stack.core.security.RsaSha256CertificateFactory
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig
import org.oip.tags.TagCaller
import org.oip.tags.TagEngine
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * Checks an OPC UA username token. Returns the tag caller the session acts as (the user's roles and the security
 * zone of [client], and [TagCaller.User.tagWrite] from the user's `tags.write` permission), or null to reject the
 * token. [secure] is true on a signed and encrypted endpoint.
 */
fun interface OpcUaUserAuthenticator {
    fun authenticate(username: String, password: CharArray, client: InetAddress?, secure: Boolean): TagCaller.User?
}

/**
 * Gateway OPC UA server. Tag providers are browsable nodes with read, write, and data-change
 * subscriptions. Configured devices and connections are a `Devices` folder beside `Tags`: each one's
 * items are browsable, and a read or a write goes to that driver connection as the session user.
 * Username tokens are checked with the gateway user directory. Anonymous access
 * is off. Each session reads, writes and monitors tags as the user it activated with: a tag's read and write
 * permissions are checked against that user's roles and zone (TAG-013), and every write it attempts is audited. A session without `tags.write` cannot write a device item either. SecurityPolicy.None and Basic256Sha256 are both offered. The self-signed certificate
 * is created on first start and stored under [securityDir].
 *
 * Client certificates for Basic256Sha256 are quarantined until they are trusted
 * (`pki/trusted/certs`), through [OpcUaCertificateTrust]. SecurityPolicy.None does not
 * authenticate a client certificate.
 *
 */
class OpcUaTagServer(
    private val settings: OpcUaServerSettings,
    private val securityDir: Path,
    private val engine: TagEngine,
    private val authenticate: OpcUaUserAuthenticator,
    private val productVersion: String,
    /** Shared with the gateway trust API. Null opens one under [securityDir] that this server closes. */
    private val sharedTrust: OpcUaCertificateTrust? = null,
    /** Live device and connection catalog. Empty leaves the `Devices` folder with no children. */
    private val devices: DeviceCatalog = DeviceCatalog.EMPTY,
) : AutoCloseable {
    val boundPort: Int
    val endpointUrl: String
    val certificate: X509Certificate
    val namespaceUri: String = TagNodeIds.NAMESPACE_URI
    var namespaceIndex: Int = 0
        private set
    /** Delivery workers. More than one, so monitored items are not served by a single thread. */
    var deliveryStripes: Int = 1
        private set

    private val material: ServerMaterial
    private var ownedTrust: OpcUaCertificateTrust? = null
    private var store: KeyStoreCertificateStore? = null
    private var addressSpace: TagAddressSpace? = null
    private var deviceSpace: DeviceAddressSpace? = null
    private var server: OpcUaServer? = null
    @Volatile private var started = false

    /** True between a successful [start] and [close]. */
    val running: Boolean get() = started

    init {
        val port = if (settings.port == 0) ServerSocket(0).use { it.localPort } else settings.port
        boundPort = port
        endpointUrl = "opc.tcp://${settings.hostname}:$port$SERVER_PATH"
        material = ServerCertificates.loadOrCreate(
            securityDir,
            APPLICATION_URI,
            "ERIC OPC UA Server",
            listOf(settings.hostname, settings.bindAddress),
        )
        certificate = material.certificate
    }

    fun start() {
        if (started) return
        securityDir.createDirectories()
        val certificateTrust = sharedTrust ?: OpcUaCertificateTrust.open(securityDir).also { ownedTrust = it }
        val trustList = certificateTrust.trustList
        val quarantine = certificateTrust.quarantine
        val keyStore = KeyStoreCertificateStore.createAndInitialize(KeyStoreCertificateStore.Settings(
            securityDir.resolve("server.pfx"),
            { storePassword() },
            { storePassword() },
        ))
        store = keyStore
        val factory = object : RsaSha256CertificateFactory() {
            override fun createRsaSha256KeyPair(): KeyPair = material.keyPair
            override fun createRsaSha256CertificateChain(keyPair: KeyPair): Array<X509Certificate> =
                arrayOf(material.certificate)
        }
        val validator = DefaultServerCertificateValidator(trustList, quarantine)
        val group = DefaultApplicationGroup.createAndInitialize(trustList, keyStore, factory, validator)
        val certificateManager = DefaultCertificateManager(quarantine, group)
        val username = object : AbstractUsernameIdentityValidator() {
            override fun authenticateUsernamePassword(session: Session, user: String, pass: String): Identity.UsernameIdentity? {
                val password = pass.toCharArray()
                val caller = try {
                    val secure = session.endpoint?.securityMode == MessageSecurityMode.SignAndEncrypt
                    authenticate.authenticate(user, password, session.clientAddress, secure)
                } catch (e: Exception) {
                    log.warn("OPC UA username check failed for {}", user)
                    null
                } finally {
                    password.fill('\u0000')
                }
                return caller?.let { DefaultUsernameIdentity(user).apply { userData = it } }
            }
        }
        val config = OpcUaServerConfig.builder()
            .setApplicationUri(APPLICATION_URI)
            .setApplicationName(LocalizedText.english("ERIC OPC UA Server"))
            .setProductUri("urn:oip:gateway")
            .setEndpoints(endpoints(boundPort, material.certificate))
            .setBuildInfo(BuildInfo(
                APPLICATION_URI,
                "oip",
                "ERIC OPC UA Server",
                OpcUaServer.SDK_VERSION,
                productVersion,
                DateTime.now(),
            ))
            .setCertificateManager(certificateManager)
            .setIdentityValidator(username)
            .build()
        var spaceRef: TagAddressSpace? = null
        val serviceSets = object : ServiceSets {
            override fun createAttributeServiceSet(server: OpcUaServer): AttributeServiceSet =
                RefusedWriteAudit(server, DefaultAttributeServiceSet(server)) { session, writes, results ->
                    spaceRef?.auditRefused(session, writes, results)
                }
        }
        val opc = OpcUaServer(config, { OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build()) }, serviceSets)
        val space = TagAddressSpace(opc, engine)
        spaceRef = space
        addressSpace = space
        namespaceIndex = space.namespaceIndex.toInt()
        deliveryStripes = space.deliveryStripes
        opc.addressSpaceManager.register(space)
        val devicesSpace = DeviceAddressSpace(opc, devices)
        deviceSpace = devicesSpace
        opc.addressSpaceManager.register(devicesSpace)
        server = opc
        opc.startup().get(30, TimeUnit.SECONDS)
        started = true
        log.info("OPC UA server listening on {} (bound {})", endpointUrl, settings.bindAddress)
    }

    override fun close() {
        val space = addressSpace
        addressSpace = null
        runCatching { space?.close() }
        val devices = deviceSpace
        deviceSpace = null
        runCatching { devices?.close() }
        val opc = server
        server = null
        if (opc != null) runCatching { opc.shutdown().get(15, TimeUnit.SECONDS) }
        if (started) {
            started = false
            log.info("OPC UA server stopped")
        }
        runCatching { store?.close() }
        store = null
        runCatching { ownedTrust?.close() }
        ownedTrust = null
    }

    private fun endpoints(port: Int, certificate: X509Certificate): Set<EndpointConfig> {
        fun build(path: String, policy: SecurityPolicy, mode: MessageSecurityMode) =
            EndpointConfig.newBuilder()
                .setBindAddress(settings.bindAddress)
                .setHostname(settings.hostname)
                .setBindPort(port)
                .setPath(path)
                .setCertificate(certificate)
                .setSecurityPolicy(policy)
                .setSecurityMode(mode)
                .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                .addTokenPolicies(OpcUaServerConfig.USER_TOKEN_POLICY_USERNAME)
                .build()
        return linkedSetOf(
            build(SERVER_PATH, SecurityPolicy.None, MessageSecurityMode.None),
            build(SERVER_PATH, SecurityPolicy.Basic256Sha256, MessageSecurityMode.SignAndEncrypt),
            build(DISCOVERY_PATH, SecurityPolicy.None, MessageSecurityMode.None),
        )
    }

    private fun storePassword(): CharArray {
        val file = securityDir.resolve("store.pass")
        if (!file.exists()) {
            val bytes = ByteArray(18)
            SecureRandom().nextBytes(bytes)
            file.writeText(bytes.joinToString("") { "%02x".format(it) })
            runCatching {
                file.setPosixFilePermissions(setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ))
            }
        }
        return file.readText().toCharArray()
    }

    companion object {
        const val APPLICATION_URI = "urn:oip:gateway:opcua"
        const val SERVER_PATH = "/oip"
        const val DISCOVERY_PATH = "/discovery"
        private val log = LoggerFactory.getLogger(OpcUaTagServer::class.java)
    }
}
