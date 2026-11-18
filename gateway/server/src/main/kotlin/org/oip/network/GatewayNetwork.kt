package org.oip.network

import io.grpc.BindableService
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Grpc
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmEventView
import org.oip.alarm.AlarmFilter
import org.oip.alarm.AlarmRemote
import org.oip.alarm.RemoteCaller
import org.oip.alarm.ShelvedAlarmView
import org.oip.gateway.Json
import org.oip.gateway.PrivateFiles
import org.oip.gateway.TlsCertificates
import org.oip.security.AccessDeniedException
import org.oip.network.v1.GatewayLinkGrpc
import org.oip.network.v1.HeartbeatRequest
import org.oip.network.v1.HeartbeatResponse
import org.oip.network.v1.HelloRequest
import org.oip.network.v1.HelloResponse
import org.oip.network.v1.RemoteAlarmsGrpc
import org.oip.network.v1.RemoteTagsGrpc
import org.oip.network.v1.TagBrowseNode
import org.oip.network.v1.TagBrowseRequest
import org.oip.network.v1.TagBrowseResponse
import org.oip.network.v1.TagReadRequest
import org.oip.network.v1.TagReadResponse
import org.oip.network.v1.TagSample
import org.oip.network.v1.TagSubscribeRequest
import org.oip.network.v1.TagWriteRequest
import org.oip.network.v1.TagWriteResponse
import org.oip.tags.BrowseNode
import org.oip.tags.DataType
import org.oip.tags.NodeKind
import org.oip.tags.QualifiedValue
import org.oip.tags.Quality
import org.oip.tags.QualityCode
import org.oip.tags.TagChange
import org.oip.tags.TagEngine
import org.oip.tags.TagPath
import org.oip.tags.TagProvider
import org.oip.tags.CallerTagProvider
import org.oip.tags.TagCaller
import org.oip.tags.WriteResult
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * gRPC peer network over mutual TLS.
 *
 * Outgoing connections dial a peer. Incoming handshakes stay rejected until an administrator
 * approves the presented certificate. A service policy can deny a service to a peer.
 * Remote services are realtime tags and live alarms. History is reserved and not served.
 *
 * (FEATURES GAN-001, GAN-002, GAN-003, SEC-030, TAG-018).
 *
 * Wire choices are ADR 0012. This is not the reference platform's peer network protocol.
 */
class GatewayNetwork(
    directory: java.nio.file.Path,
    private val tags: TagEngine,
    val settings: LinkSettings = LinkSettings(),
    private val version: String = "0.1.0-SNAPSHOT",
    private val bindHost: String = "0.0.0.0",
) : AlarmRemote, AutoCloseable {
    private val directory = directory
    private val trust = PeerTrust(directory.resolve("trust"))
    private val policies = PolicyTable(directory.resolve("policies.json"))
    private val outgoing = LinkedHashMap<String, OutgoingRuntime>()
    private val providers = LinkedHashMap<String, RemoteProviderSpec>()
    private val liveIncoming = ConcurrentHashMap<String, LivePeer>()
    private val peerNames = ConcurrentHashMap<String, String>()
    private val lock = Any()
    @Volatile private var identity: LinkIdentity? = null
    @Volatile private var server: Server? = null
    @Volatile private var boundPort: Int = -1
    @Volatile private var closed = false
    /** Test seam. While true, accepted calls fail so the peer reconnects. */
    @Volatile internal var dropCalls: Boolean = false
    /** Extra services on the same mutual-TLS listener. Redundancy registers here before start. */
    val extraServices = CopyOnWriteArrayList<BindableService>()
    /** Told when an outgoing connection's channel comes up or goes down. The name is the connection name. */
    @Volatile var linkListener: ((String, Boolean) -> Unit)? = null
    private val alarmThread = ThreadLocal<Boolean>()
    private val alarmIo = Executors.newSingleThreadExecutor { worker ->
        Thread({
            alarmThread.set(true)
            worker.run()
        }, "gan-alarms").apply { isDaemon = true }
    }
    @Volatile private var alarmEngine: AlarmEngine? = null
    @Volatile private var alarmAuditor: ((RemoteAlarmAudit) -> Unit)? = null
    /** Protocol advertised on Hello. Tests set 1 to keep the original alarm query. */
    @Volatile var advertisedProtocol: Int = PROTOCOL_VERSION
    private val peerProtocol = ConcurrentHashMap<String, Int>()
    private val taskBoard = TaskBoard()
    @Volatile var notificationSink: ((RemoteNotification) -> Boolean)? = null

    /** Live alarm engine served to approved peers, and the audit sink for remote acknowledgements. */
    internal fun attachAlarms(engine: AlarmEngine, auditor: (RemoteAlarmAudit) -> Unit) {
        alarmEngine = engine
        alarmAuditor = auditor
    }

    /**
     * Remote tag read, subscribe, and browse run only while this returns true.
     * Hello, heartbeat, and the redundancy session stay up either way.
     * An independent gateway leaves this true. This node still dials its own remote providers.
     */
    @Volatile var servesRemoteData: () -> Boolean = { true }

    init {
        directory.createDirectories()
        loadOutgoing()
        loadProviders()
    }

    fun start() {
        if (closed || !settings.enabled) return
        synchronized(lock) {
            if (server != null) return
            val id = ensureIdentity()
            val built = startServer(id, settings.port)
            server = built
            boundPort = built.port
        }
        synchronized(outgoing) {
            outgoing.values.forEach { runtime ->
                if (runtime.ssl == null) runtime.ssl = clientSsl(runtime.spec.name)
            }
        }
        restoreProviders()
        synchronized(outgoing) { outgoing.values.filter { it.spec.enabled }.forEach { launch(it) } }
    }

    fun port(): Int = boundPort

    fun fingerprint(): String = ensureIdentity().fingerprint()

    fun addOutgoing(name: String, host: String, port: Int, enabled: Boolean = true, description: String = "") {
        require(NAME.matches(name)) { "outgoing connection name must be a lowercase token" }
        require(HOST.matches(host)) { "outgoing host must be a hostname or address" }
        require(port in 1..65535) { "outgoing port out of range" }
        val spec = OutgoingSpec(name, host, port, enabled, description.take(200))
        val runtime = synchronized(outgoing) {
            check(name !in outgoing) { "outgoing connection '$name' already exists" }
            val created = OutgoingRuntime(spec, clientSsl(name))
            outgoing[name] = created
            saveOutgoing()
            created
        }
        if (server != null && enabled) launch(runtime)
    }

    fun addRemoteProvider(name: String, connection: String, provider: String = "default", enabled: Boolean = true) {
        require(NAME.matches(name)) { "remote provider name must be a lowercase token" }
        require(name != "default" && name != "system") { "remote provider name is reserved" }
        require(NAME.matches(provider) || provider == "default" || provider == "system") { "remote provider id is invalid" }
        val runtime = synchronized(outgoing) { outgoing[connection] }
            ?: throw NoSuchElementException("outgoing connection '$connection' not found")
        val spec = RemoteProviderSpec(name, connection, provider, enabled)
        synchronized(providers) {
            check(name !in providers) { "remote provider '$name' already exists" }
            providers[name] = spec
            saveProviders()
        }
        if (enabled) {
            val remote = RemoteTagProvider(name, provider, runtime, settings.pingTimeoutMillis.coerceAtLeast(1_000))
            runtime.providers += remote
            tags.registerProvider(remote)
            runtime.asyncStub?.let { remote.attach(it) }
            bindAlarm(runtime, spec)
        }
    }

    fun approveOutgoing(name: String) {
        val record = trust.latestOutgoing(name) ?: throw NoSuchElementException("no certificate for outgoing connection '$name'")
        trust.approve(DIR_OUTGOING, record.fingerprint)
        poke(name)
    }

    fun denyOutgoing(name: String) {
        val record = trust.latestOutgoing(name) ?: throw NoSuchElementException("no certificate for outgoing connection '$name'")
        trust.deny(DIR_OUTGOING, record.fingerprint)
        poke(name)
    }

    /**
     * Trust the peer certificate [fingerprint] on outgoing connections before the first handshake.
     * Used by the enterprise-administration first-boot files (EAM-008) to name the controller's certificate.
     */
    fun pinOutgoing(fingerprint: String) {
        trust.pin(DIR_OUTGOING, normalizeFingerprint(fingerprint))
    }

    /** Whether an outgoing connection named [name] exists. */
    fun hasOutgoing(name: String): Boolean = synchronized(outgoing) { name in outgoing }

    fun approveIncoming(fingerprint: String): Map<String, Any?> {
        trust.approve(DIR_INCOMING, normalizeFingerprint(fingerprint))
        return statusDocument()
    }

    fun denyIncoming(fingerprint: String): Map<String, Any?> {
        trust.deny(DIR_INCOMING, normalizeFingerprint(fingerprint))
        return statusDocument()
    }

    fun putPolicy(policy: ServicePolicy) {
        require(policy.peer == "*" || FINGERPRINT.matches(policy.peer) || NAME.matches(policy.peer) || GATEWAY_NAME.matches(policy.peer)) {
            "policy peer must be a fingerprint, a gateway name, or *"
        }
        require(SERVICE_NAME.matches(policy.service)) { "policy service name is invalid" }
        require(policy.access == "allow" || policy.access == "deny" || policy.access == "read") {
            "policy access must be allow, read, or deny"
        }
        policies.put(policy)
    }

    fun removePolicy(peer: String, service: String) {
        if (!policies.remove(peer, service)) throw NoSuchElementException("no policy for $peer $service")
    }

    fun outgoingStatus(name: String): String = synchronized(outgoing) { outgoing[name] }?.status
        ?: throw NoSuchElementException("outgoing connection '$name' not found")

    fun outgoingConnects(name: String): Int = synchronized(outgoing) { outgoing[name] }?.connectCount
        ?: throw NoSuchElementException("outgoing connection '$name' not found")

    internal fun faultSessions(drop: Boolean) {
        dropCalls = drop
    }

    /**
     * Called from an edge-sync RPC on this listener. Returns the authenticated peer gateway name.
     * `allow` is required. `read` and a missing policy are permission denied.
     */
    fun requireStorage(service: String): String {
        if (dropCalls) throw Status.UNAVAILABLE.withDescription("connection dropped").asRuntimeException()
        val fingerprint = PEER_FP.get() ?: ""
        val name = peerNames[fingerprint]?.takeIf { it.isNotBlank() }
            ?: throw Status.UNAUTHENTICATED.withDescription("peer name unknown").asRuntimeException()
        val access = policies.decide(fingerprint, name, service)
        if (!access.storage) throw Status.PERMISSION_DENIED.withDescription("service denied").asRuntimeException()
        return name
    }

    /**
     * Called from an enterprise-administration RPC on this listener. Returns the authenticated peer's
     * certificate fingerprint and gateway name. The certificate is already approved (the interceptor checks it).
     * A missing policy admits the peer, because the controller approves each agent separately.
     * An explicit `deny` for [service] refuses it.
     */
    fun requirePeer(service: String): Pair<String, String> {
        if (dropCalls) throw Status.UNAVAILABLE.withDescription("connection dropped").asRuntimeException()
        val fingerprint = PEER_FP.get() ?: ""
        val name = peerNames[fingerprint]?.takeIf { it.isNotBlank() }
            ?: throw Status.UNAUTHENTICATED.withDescription("peer name unknown").asRuntimeException()
        val explicit = policies.list().firstOrNull {
            it.service == service && (it.peer.equals(fingerprint, true) || it.peer.equals(name, true) || it.peer == "*")
        }
        if (explicit != null && explicit.access.equals("deny", true)) {
            throw Status.PERMISSION_DENIED.withDescription("service denied").asRuntimeException()
        }
        return fingerprint to name
    }

    /** The live channel for an outgoing connection, or null when it is down. */
    fun outgoingChannel(name: String): ManagedChannel? = synchronized(outgoing) { outgoing[name]?.channel }

    fun retargetOutgoing(name: String, host: String, port: Int) {
        require(HOST.matches(host)) { "outgoing host must be a hostname or address" }
        require(port in 1..65535) { "outgoing port out of range" }
        val runtime = synchronized(outgoing) { outgoing[name] } ?: throw NoSuchElementException("outgoing connection '$name' not found")
        runtime.spec = runtime.spec.copy(host = host, port = port)
        synchronized(outgoing) { saveOutgoing() }
        runtime.thread?.interrupt()
    }

    fun regenerateCertificate(): Map<String, Any?> {
        ensureIdentity().regenerate()
        val threads = synchronized(outgoing) {
            outgoing.values.forEach { it.ssl = null }
            outgoing.values.mapNotNull { it.thread }
        }
        restartListener()
        threads.forEach { it.interrupt() }
        return statusDocument()
    }

    fun statusDocument(): Map<String, Any?> {
        val id = identity
        val certificate = id?.let { TlsCertificates.view(it.material, "generated") }
        val incoming = trust.list(DIR_INCOMING).map { record ->
            val live = liveIncoming[record.fingerprint]
            record.publicView() + linkedMapOf(
                "direction" to DIR_INCOMING,
                "status" to incomingStatus(record, live),
                "remoteVersion" to live?.version,
                "protocolVersion" to (live?.protocol ?: peerProtocol[record.fingerprint]),
                "lastCommMillis" to live?.lastCommMillis,
                "pingMillis" to live?.pingMillis,
            )
        }
        val outgoingViews = synchronized(outgoing) { outgoing.values.toList() }.map { it.publicView(trust) }
        return linkedMapOf(
            "enabled" to settings.enabled,
            "listening" to (server?.isShutdown == false),
            "port" to if (boundPort >= 0) boundPort else null,
            "gatewayName" to settings.gatewayName,
            "protocolVersion" to PROTOCOL_VERSION,
            "protocols" to TlsCertificates.PROTOCOLS,
            "tasks" to taskBoard.publicList(),
            "certificate" to certificate,
            "incoming" to incoming,
            "outgoing" to outgoingViews,
            "policies" to policies.list().map {
                linkedMapOf("peer" to it.peer, "service" to it.service, "access" to it.access, "roles" to it.roles)
            },
            "providers" to synchronized(providers) { providers.values.toList() }.map {
                linkedMapOf("name" to it.name, "connection" to it.connection, "provider" to it.provider, "enabled" to it.enabled)
            },
        )
    }

    override fun close() {
        closed = true
        alarmIo.shutdownNow()
        val threads = synchronized(outgoing) { outgoing.values.mapNotNull { it.thread } }
        threads.forEach { it.interrupt() }
        synchronized(outgoing) { outgoing.values.forEach { it.onDown() } }
        server?.shutdownNow()
        runCatching { server?.awaitTermination(2, TimeUnit.SECONDS) }
        server = null
        threads.forEach { runCatching { it.join(1_000) } }
    }

    private fun ensureIdentity(): LinkIdentity {
        identity?.let { return it }
        return synchronized(lock) {
            identity ?: LinkIdentity(directory.resolve("identity"), settings.gatewayName).also { identity = it }
        }
    }

    private fun restartListener() {
        synchronized(lock) {
            val previous = server ?: return
            val port = boundPort
            previous.shutdownNow()
            previous.awaitTermination(2, TimeUnit.SECONDS)
            val id = identity ?: return
            val built = startServer(id, port)
            server = built
            boundPort = built.port
        }
    }

    private fun startServer(id: LinkIdentity, port: Int): Server {
        val builder = NettyServerBuilder.forAddress(java.net.InetSocketAddress(bindHost, port))
            .sslContext(serverSsl(id))
            .maxInboundMessageSize(MAX_MESSAGE)
            .addService(LinkService())
            .addService(TagService())
            .addService(RemoteAlarmService(AlarmHostAdapter()))
            .addService(RemoteTaskService(TaskHostAdapter()))
            .addService(RemoteNotificationService(NotificationHostAdapter()))
        extraServices.forEach { builder.addService(it) }
        return builder.intercept(ApprovalInterceptor()).build().start()
    }

    private fun serverSsl(id: LinkIdentity): SslContext {
        val builder = GrpcSslContexts.configure(
            SslContextBuilder.forServer(id.material.privateKey, *id.material.chain.toTypedArray())
                .trustManager(FingerprintTrustManager(DIR_INCOMING, trust, null))
                .clientAuth(ClientAuth.REQUIRE),
        )
        builder.protocols(*TlsCertificates.PROTOCOLS.toTypedArray())
        return builder.build()
    }

    private fun clientSsl(connection: String): SslContext {
        val id = ensureIdentity()
        val builder = GrpcSslContexts.configure(
            SslContextBuilder.forClient()
                .keyManager(id.material.privateKey, *id.material.chain.toTypedArray())
                .trustManager(FingerprintTrustManager(DIR_OUTGOING, trust, connection)),
        )
        builder.protocols(*TlsCertificates.PROTOCOLS.toTypedArray())
        // Trust is the certificate fingerprint. This Netty builder does not set an endpoint
        // identification algorithm, so the handshake does not check the subject or SAN.
        return builder.build()
    }

    private fun launch(runtime: OutgoingRuntime) {
        val current = runtime.thread
        if (current != null && current.isAlive) {
            current.interrupt()
            return
        }
        val thread = Thread({ runOutgoing(runtime) }, "gan-out-${runtime.spec.name}")
        thread.isDaemon = true
        runtime.thread = thread
        thread.start()
    }

    private fun poke(name: String) {
        synchronized(outgoing) { outgoing[name] }?.thread?.interrupt()
    }

    private fun runOutgoing(runtime: OutgoingRuntime) {
        var attempt = 0
        while (!closed && runtime.spec.enabled) {
            Thread.interrupted()
            var channel: ManagedChannel? = null
            try {
                runtime.status = STATUS_CONNECTING
                val ssl = runtime.ssl ?: clientSsl(runtime.spec.name).also { runtime.ssl = it }
                val target = runtime.spec
                channel = NettyChannelBuilder.forAddress(target.host, target.port)
                    .sslContext(ssl)
                    .maxInboundMessageSize(MAX_MESSAGE)
                    .build()
                val link = GatewayLinkGrpc.newBlockingStub(channel)
                val deadline = settings.pingTimeoutMillis
                val hello = link.withDeadlineAfter(deadline, TimeUnit.MILLISECONDS).hello(
                    HelloRequest.newBuilder()
                        .setGatewayName(settings.gatewayName)
                        .setVersion(version)
                        .setCertificateFingerprint(identity?.fingerprint() ?: "")
                        .setProtocolVersion(advertisedProtocol)
                        .build(),
                )
                runtime.peerProtocol = hello.protocolVersion
                // Publish the channel first: a caller that sees "running" must also get the channel.
                runtime.onUp(channel)
                runtime.connectCount += 1
                runtime.status = STATUS_RUNNING
                runtime.lastError = null
                runtime.lastCommMillis = System.currentTimeMillis()
                attempt = 0
                linkListener?.invoke(runtime.spec.name, true)
                var missed = 0
                while (!closed && runtime.spec.enabled && runtime.spec.host == target.host && runtime.spec.port == target.port && runtime.ssl != null) {
                    try {
                        val sent = System.currentTimeMillis()
                        val started = System.nanoTime()
                        link.withDeadlineAfter(deadline, TimeUnit.MILLISECONDS)
                            .heartbeat(HeartbeatRequest.newBuilder().setSentUnixMillis(sent).build())
                        runtime.pingMillis = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
                        runtime.lastCommMillis = System.currentTimeMillis()
                        missed = 0
                    } catch (error: Exception) {
                        if (closed) break
                        missed += 1
                        runtime.lastError = safeError(error)
                        val kind = failureKind(error)
                        if (kind != null || missed >= settings.missedPings) {
                            if (kind != null) runtime.status = kind
                            break
                        }
                    }
                    if (!pause(settings.pingRateMillis)) break
                }
            } catch (error: Exception) {
                runtime.lastError = safeError(error)
                runtime.status = failureKind(error) ?: STATUS_FAULTED
                if (runtime.status != STATE_PENDING) runtime.faultCount += 1
            } finally {
                // Leave "running" before the channel goes, for the same reason as on the way up.
                if (!closed && runtime.spec.enabled && runtime.status == STATUS_RUNNING) {
                    runtime.status = STATUS_FAULTED
                    runtime.faultCount += 1
                }
                runtime.onDown()
                linkListener?.invoke(runtime.spec.name, false)
                channel?.shutdownNow()
            }
            if (closed || !runtime.spec.enabled) break
            attempt += 1
            if (!pause(backoffMillis(attempt))) break
        }
        if (!closed && !runtime.spec.enabled) runtime.status = STATUS_DISABLED
    }

    private fun pause(millis: Long): Boolean {
        if (closed) return false
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            // A trust change interrupts the sleep so the next handshake is not stuck in backoff.
        }
        return !closed
    }

    private fun backoffMillis(attempt: Int): Long {
        var delay = settings.backoffInitialMillis
        repeat((attempt - 1).coerceAtLeast(0).coerceAtMost(8)) {
            if (delay >= settings.backoffCapMillis / 2) return settings.backoffCapMillis
            delay = delay.coerceAtMost(settings.backoffCapMillis) * 2
        }
        return delay.coerceIn(settings.backoffInitialMillis, settings.backoffCapMillis)
    }

    private fun incomingStatus(record: PeerRecord, live: LivePeer?): String = when (record.state) {
        STATE_DENIED -> STATE_DENIED
        STATE_PENDING -> STATE_PENDING
        else -> if (live?.running == true) STATUS_RUNNING else STATUS_CONNECTING
    }

    private fun peerFingerprint(call: ServerCall<*, *>): String {
        val session = call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION) ?: return ""
        val certificate = try {
            session.peerCertificates?.firstOrNull() as? X509Certificate
        } catch (_: Exception) {
            null
        } ?: return ""
        return TlsCertificates.fingerprint(certificate)
    }

    private fun requireTags(fingerprint: String): ServiceAccess {
        if (!servesRemoteData()) throw Status.FAILED_PRECONDITION.withDescription("node is not active").asRuntimeException()
        if (dropCalls) throw Status.UNAVAILABLE.withDescription("connection dropped").asRuntimeException()
        val access = policies.decide(fingerprint, peerNames[fingerprint], SERVICE_TAGS)
        if (!access.allowed) throw Status.PERMISSION_DENIED.withDescription("service denied").asRuntimeException()
        return access
    }

    private fun tagPermitted(provider: String, path: String, roles: Set<String>): Boolean {
        val required = tags.readPermissionRoles("[$provider]$path") ?: return true
        if (required.isEmpty()) return false
        return roles.any { role -> required.any { it.equals(role, ignoreCase = true) } }
    }

    private inner class ApprovalInterceptor : ServerInterceptor {
        override fun <Req, Resp> interceptCall(
            call: ServerCall<Req, Resp>,
            headers: Metadata,
            next: ServerCallHandler<Req, Resp>,
        ): ServerCall.Listener<Req> {
            if (dropCalls) {
                call.close(Status.UNAVAILABLE.withDescription("connection dropped"), Metadata())
                return object : ServerCall.Listener<Req>() {}
            }
            val fingerprint = peerFingerprint(call)
            val state = if (fingerprint.isEmpty()) null else trust.state(DIR_INCOMING, fingerprint)
            if (state != STATE_APPROVED) {
                val description = if (state == STATE_DENIED) "certificate denied" else "certificate pending approval"
                call.close(Status.UNAUTHENTICATED.withDescription(description), Metadata())
                return object : ServerCall.Listener<Req>() {}
            }
            val context = Context.current().withValue(PEER_FP, fingerprint)
            return Contexts.interceptCall(context, call, headers, next)
        }
    }

    private inner class LinkService : GatewayLinkGrpc.GatewayLinkImplBase() {
        override fun hello(request: HelloRequest, responseObserver: StreamObserver<HelloResponse>) {
            val fingerprint = PEER_FP.get() ?: ""
            if (dropCalls) {
                responseObserver.onError(Status.UNAVAILABLE.withDescription("connection dropped").asRuntimeException())
                return
            }
            if (request.certificateFingerprint.isNotEmpty() && !request.certificateFingerprint.equals(fingerprint, true)) {
                responseObserver.onError(Status.UNAUTHENTICATED.withDescription("certificate fingerprint mismatch").asRuntimeException())
                return
            }
            val remoteName = request.gatewayName.take(64)
            if (remoteName.isNotEmpty()) {
                peerNames[fingerprint] = remoteName
                trust.noteGatewayName(DIR_INCOMING, fingerprint, remoteName)
            }
            val protocol = if (request.protocolVersion <= 0) 1 else request.protocolVersion
            peerProtocol[fingerprint] = protocol
            liveIncoming[fingerprint] = LivePeer(remoteName, request.version.take(40), System.currentTimeMillis(), 0, true, protocol)
            val id = identity
            responseObserver.onNext(
                HelloResponse.newBuilder()
                    .setGatewayName(settings.gatewayName)
                    .setVersion(version)
                    .setCertificateFingerprint(id?.fingerprint() ?: "")
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun heartbeat(request: HeartbeatRequest, responseObserver: StreamObserver<HeartbeatResponse>) {
            if (dropCalls) {
                responseObserver.onError(Status.UNAVAILABLE.withDescription("connection dropped").asRuntimeException())
                return
            }
            val fingerprint = PEER_FP.get() ?: ""
            val now = System.currentTimeMillis()
            val live = liveIncoming[fingerprint]
            if (live != null) {
                live.lastCommMillis = now
                live.pingMillis = (now - request.sentUnixMillis).coerceAtLeast(0)
                live.running = true
            }
            responseObserver.onNext(
                HeartbeatResponse.newBuilder().setSentUnixMillis(request.sentUnixMillis).setReceivedUnixMillis(now).build(),
            )
            responseObserver.onCompleted()
        }
    }

    private inner class TagService : RemoteTagsGrpc.RemoteTagsImplBase() {
        /**
         * A peer's remote-provider write (SEC-003). The peer's tags policy decides: `allow` writes; `read` (and no
         * policy, which is read-only for tags) is refused with `Bad_UserAccessDenied`, and `deny` with PERMISSION_DENIED;
         * every refusal is audited. The write runs here as a user named after the peer and its user, with the policy's roles, so the
         * tag's own write permissions apply, and the tag engine audits it (accepted or refused) on this gateway with
         * context `gateway-network`. The requesting gateway audits the same write for its own user.
         */
        override fun write(request: TagWriteRequest, responseObserver: StreamObserver<TagWriteResponse>) {
            val fingerprint = PEER_FP.get() ?: ""
            val peer = (peerNames[fingerprint] ?: fingerprint.take(16)).take(64)
            val actor = request.actor.take(200).filter { !it.isISOControl() }.ifEmpty { "unknown" }
            val provider = request.provider.ifEmpty { "default" }
            val access = try {
                requireTags(fingerprint)
            } catch (error: StatusRuntimeException) {
                // An approved peer whose policy denies the tags service: the refused write is audited here too.
                if (error.status.code == Status.Code.PERMISSION_DENIED) {
                    val refused = TagCaller.User(actor = "$peer/$actor", roles = emptySet(), zone = REMOTE_WRITE_ZONE, host = peer, tagWrite = false)
                    runCatching {
                        org.oip.security.AuditContext.call("$peer/$actor", peer, REMOTE_WRITE_CONTEXT) {
                            tags.write("[$provider]${request.path}", null, refused)
                        }
                    }
                }
                responseObserver.onError(error)
                return
            }
            val value = if (!request.valuePresent || request.valueJson.isEmpty()) null else try {
                Json.mapper.readValue(request.valueJson, Any::class.java)
            } catch (_: Exception) {
                responseObserver.onNext(TagWriteResponse.newBuilder().setQuality(QualityCode.BAD_UNEXPECTED_ERROR.label).build())
                responseObserver.onCompleted()
                return
            }
            val caller = TagCaller.User(
                actor = "$peer/$actor",
                roles = access.roles.mapTo(HashSet()) { it.lowercase() },
                zone = REMOTE_WRITE_ZONE,
                host = peer,
                tagWrite = access.canWrite,
            )
            val result = org.oip.security.AuditContext.call("$peer/$actor", peer, REMOTE_WRITE_CONTEXT) {
                tags.write("[$provider]${request.path}", value, caller)
            }
            responseObserver.onNext(TagWriteResponse.newBuilder().setQuality(result.quality.code.label).build())
            responseObserver.onCompleted()
        }

        override fun read(request: TagReadRequest, responseObserver: StreamObserver<TagReadResponse>) {
            val fingerprint = PEER_FP.get() ?: ""
            val access = try {
                requireTags(fingerprint)
            } catch (error: StatusRuntimeException) {
                responseObserver.onError(error)
                return
            }
            val provider = request.provider.ifEmpty { "default" }
            val samples = request.pathsList.map { path ->
                if (!tagPermitted(provider, path, access.roles)) deniedSample(path)
                else encodeSample(path, tags.read("[$provider]$path"))
            }
            responseObserver.onNext(TagReadResponse.newBuilder().addAllValues(samples).build())
            responseObserver.onCompleted()
        }

        override fun subscribe(request: TagSubscribeRequest, responseObserver: StreamObserver<TagSample>) {
            val fingerprint = PEER_FP.get() ?: ""
            val access = try {
                requireTags(fingerprint)
            } catch (error: StatusRuntimeException) {
                responseObserver.onError(error)
                return
            }
            val provider = request.provider.ifEmpty { "default" }
            val path = request.path
            if (!tagPermitted(provider, path, access.roles)) {
                responseObserver.onError(Status.PERMISSION_DENIED.withDescription("tag read denied").asRuntimeException())
                return
            }
            val full = "[$provider]$path"
            val open = AtomicBoolean(true)
            val sendLock = Any()
            val subscription = java.util.concurrent.atomic.AtomicReference<AutoCloseable>()
            fun fail(status: Status) {
                synchronized(sendLock) {
                    if (!open.compareAndSet(true, false)) return
                    runCatching { subscription.get()?.close() }
                    responseObserver.onError(status.asRuntimeException())
                }
            }
            fun emit(sample: TagSample) {
                synchronized(sendLock) {
                    if (!open.get()) return
                    responseObserver.onNext(sample)
                }
            }
            try {
                subscription.set(tags.subscribe(full) { change ->
                    if (!open.get()) return@subscribe
                    if (!servesRemoteData()) {
                        fail(Status.FAILED_PRECONDITION.withDescription("node is not active"))
                        return@subscribe
                    }
                    if (dropCalls || !policies.decide(fingerprint, peerNames[fingerprint], SERVICE_TAGS).allowed) {
                        fail(Status.PERMISSION_DENIED.withDescription("service denied"))
                        return@subscribe
                    }
                    if (!tagPermitted(provider, path, access.roles)) {
                        fail(Status.PERMISSION_DENIED.withDescription("tag read denied"))
                        return@subscribe
                    }
                    try {
                        emit(encodeSample(path, change.value))
                    } catch (_: Exception) {
                        open.set(false)
                    }
                })
            } catch (_: Exception) {
                responseObserver.onError(Status.INTERNAL.withDescription("subscribe failed").asRuntimeException())
                return
            }
            val closeSub = {
                synchronized(sendLock) {
                    if (open.compareAndSet(true, false)) runCatching { subscription.get()?.close() }
                }
            }
            if (responseObserver is ServerCallStreamObserver<*>) {
                @Suppress("UNCHECKED_CAST")
                (responseObserver as ServerCallStreamObserver<TagSample>).setOnCancelHandler { closeSub() }
            }
            try {
                emit(encodeSample(path, tags.read(full)))
            } catch (_: Exception) {
                closeSub()
            }
        }

        override fun browse(request: TagBrowseRequest, responseObserver: StreamObserver<TagBrowseResponse>) {
            val fingerprint = PEER_FP.get() ?: ""
            try {
                requireTags(fingerprint)
            } catch (error: StatusRuntimeException) {
                responseObserver.onError(error)
                return
            }
            val provider = request.provider.ifEmpty { "default" }
            val root = if (request.path.isEmpty()) "[$provider]" else "[$provider]${request.path}"
            val nodes = tags.browse(root).map { node -> browseNode(node) }
            responseObserver.onNext(TagBrowseResponse.newBuilder().addAllNodes(nodes).build())
            responseObserver.onCompleted()
        }
    }

    private fun restoreProviders() {
        val specs = synchronized(providers) { providers.values.filter { it.enabled } }
        for (spec in specs) {
            val runtime = synchronized(outgoing) { outgoing[spec.connection] } ?: continue
            if (runtime.providers.none { it.name == spec.name } && tags.provider(spec.name) == null) {
                val remote = RemoteTagProvider(spec.name, spec.provider, runtime, settings.pingTimeoutMillis.coerceAtLeast(1_000))
                runtime.providers += remote
                tags.registerProvider(remote)
            }
            bindAlarm(runtime, spec)
        }
    }

    override fun query(filter: AlarmFilter): List<AlarmEventView> = query(filter, RemoteCaller())

    override fun query(filter: AlarmFilter, caller: RemoteCaller): List<AlarmEventView> {
        val rows = ArrayList<AlarmEventView>()
        for (session in alarmSessions()) {
            val got = if (session.connected()) session.pull(caller) else session.snapshot()
            for (event in got) if (event.matches(filter)) rows += event
        }
        return rows.sortedWith(compareBy({ it.source }, { it.eventId }))
    }

    override fun shelved(filter: AlarmFilter): List<ShelvedAlarmView> = shelved(filter, RemoteCaller())

    override fun shelved(filter: AlarmFilter, caller: RemoteCaller): List<ShelvedAlarmView> {
        val rows = ArrayList<ShelvedAlarmView>()
        for (session in alarmSessions()) {
            if (!session.connected()) continue
            for (shelf in session.pullShelved(caller)) if (shelf.matchesShelf(filter)) rows += shelf
        }
        return rows.sortedBy { it.source }
    }

    override fun ownsEvent(eventId: String): Boolean = parseEvent(eventId) != null

    override fun ownsSource(source: String): Boolean = parseSource(source) != null

    override fun acknowledge(eventIds: List<String>, user: String, notes: String?) {
        acknowledge(eventIds, user, notes, RemoteCaller(user = user))
    }

    override fun acknowledge(eventIds: List<String>, user: String, notes: String?, caller: RemoteCaller) {
        val grouped = LinkedHashMap<RemoteAlarmSession, MutableList<String>>()
        for (id in eventIds) {
            val parsed = parseEvent(id) ?: throw NoSuchElementException("alarm event $id not found")
            grouped.getOrPut(parsed.first) { mutableListOf() }.add(parsed.second)
        }
        val sent = if (caller.user.isBlank()) caller.copy(user = user) else caller
        for ((session, ids) in grouped) session.acknowledge(ids, sent.user, notes, sent)
    }

    override fun shelve(sources: List<String>, duration: Duration) {
        shelve(sources, duration, RemoteCaller())
    }

    override fun shelve(sources: List<String>, duration: Duration, caller: RemoteCaller) {
        val grouped = LinkedHashMap<RemoteAlarmSession, MutableList<String>>()
        for (source in sources) {
            val parsed = parseSource(source) ?: throw NoSuchElementException("alarm $source not found")
            grouped.getOrPut(parsed.first) { mutableListOf() }.add(parsed.second)
        }
        for ((session, remote) in grouped) session.shelve(remote, duration, caller)
    }

    override fun unshelve(sources: List<String>) {
        unshelve(sources, RemoteCaller())
    }

    override fun unshelve(sources: List<String>, caller: RemoteCaller) {
        val grouped = LinkedHashMap<RemoteAlarmSession, MutableList<String>>()
        for (source in sources) {
            val parsed = parseSource(source) ?: throw NoSuchElementException("alarm $source not found")
            grouped.getOrPut(parsed.first) { mutableListOf() }.add(parsed.second)
        }
        for ((session, remote) in grouped) session.unshelve(remote, caller)
    }

    /** `idle`, `live`, `denied`, `down`, or `absent` when the provider is unknown. */
    fun remoteAlarmPhase(provider: String): String = alarmSession(provider)?.phase ?: "absent"

    fun watchRemoteAlarms(provider: String, listener: (List<AlarmEventView>) -> Unit): AutoCloseable {
        val found = alarmSession(provider) ?: throw NoSuchElementException("remote provider '$provider' not found")
        return found.watch(listener)
    }

    fun pullRemoteAlarms(provider: String): List<AlarmEventView> {
        val found = alarmSession(provider) ?: throw NoSuchElementException("remote provider '$provider' not found")
        return found.pull()
    }

    fun submitTask(connection: String, name: String, argument: String): Map<String, Any?> {
        val runtime = outgoingRuntime(connection)
        val stub = runtime.taskBlocking ?: throw IllegalStateException("remote task link is down")
        val state = try {
            stub.withDeadlineAfter(settings.pingTimeoutMillis.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
                .submit(org.oip.network.v1.TaskSubmit.newBuilder().setName(name).setArgument(argument).build())
        } catch (error: StatusRuntimeException) {
            throw taskFailure(error)
        }
        val view = taskBoard.save(state.toView("outgoing", connection))
        return view.toMap()
    }

    fun taskStatus(connection: String, id: String): Map<String, Any?> {
        val runtime = outgoingRuntime(connection)
        val stub = runtime.taskBlocking ?: throw IllegalStateException("remote task link is down")
        val state = try {
            stub.withDeadlineAfter(settings.pingTimeoutMillis.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
                .get(org.oip.network.v1.TaskRef.newBuilder().setId(id).build())
        } catch (error: StatusRuntimeException) {
            throw taskFailure(error)
        }
        return taskBoard.save(state.toView("outgoing", connection)).toMap()
    }

    fun cancelTask(connection: String, id: String): Map<String, Any?> {
        val runtime = outgoingRuntime(connection)
        val stub = runtime.taskBlocking ?: throw IllegalStateException("remote task link is down")
        val state = try {
            stub.withDeadlineAfter(settings.pingTimeoutMillis.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
                .cancel(org.oip.network.v1.TaskRef.newBuilder().setId(id).build())
        } catch (error: StatusRuntimeException) {
            throw taskFailure(error)
        }
        return taskBoard.save(state.toView("outgoing", connection)).toMap()
    }

    fun deliverNotification(connection: String, notice: RemoteNotification): Boolean {
        val runtime = outgoingRuntime(connection)
        val stub = runtime.notifyBlocking ?: throw IllegalStateException("remote notification link is down")
        return try {
            val ack = stub.withDeadlineAfter(settings.pingTimeoutMillis.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
                .deliver(notice.toProto(settings.gatewayName))
            ack.accepted
        } catch (error: StatusRuntimeException) {
            throw taskFailure(error)
        }
    }

    private fun outgoingRuntime(name: String): OutgoingRuntime =
        synchronized(outgoing) { outgoing[name] } ?: throw NoSuchElementException("outgoing connection '$name' not found")

    private fun onAlarmIo(task: () -> Unit) {
        if (closed) return
        if (alarmThread.get() == true) {
            if (!closed) task()
            return
        }
        alarmIo.execute {
            if (!closed) task()
        }
    }

    private fun alarmSessions(): List<RemoteAlarmSession> {
        val runtimes = synchronized(outgoing) { outgoing.values.toList() }
        return runtimes.flatMap { it.alarmSessions.toList() }
    }

    private fun alarmSession(name: String): RemoteAlarmSession? =
        alarmSessions().firstOrNull { it.localName == name }

    private fun parseEvent(eventId: String): Pair<RemoteAlarmSession, String>? {
        val index = eventId.indexOf(':')
        if (index <= 0) return null
        val name = eventId.substring(0, index)
        val remoteId = eventId.substring(index + 1)
        if (remoteId.isEmpty()) return null
        val session = alarmSession(name) ?: return null
        return session to remoteId
    }

    private fun parseSource(source: String): Pair<RemoteAlarmSession, String>? {
        if (!source.startsWith("prov:")) return null
        val body = source.removePrefix("prov:")
        val name = body.substringBefore(':')
        if (name.isEmpty() || !body.startsWith("$name:")) return null
        val session = alarmSession(name) ?: return null
        return session to session.toRemoteSource(source)
    }

    private fun bindAlarm(runtime: OutgoingRuntime, spec: RemoteProviderSpec) {
        if (runtime.alarmSessions.any { it.localName == spec.name }) return
        val session = RemoteAlarmSession(spec.name, spec.provider, settings.pingTimeoutMillis.coerceAtLeast(1_000))
        runtime.alarmSessions += session
        val async = runtime.alarmAsync
        val blocking = runtime.alarmBlocking
        if (async != null && blocking != null) session.attach(async, blocking)
    }

    private inner class AlarmHostAdapter : AlarmServerHost {
        override fun dropping(): Boolean = dropCalls
        override fun fingerprint(): String = PEER_FP.get() ?: ""
        override fun access(fingerprint: String): ServiceAccess =
            policies.decide(fingerprint, peerNames[fingerprint], SERVICE_ALARMS)
        override fun peerName(fingerprint: String): String = peerNames[fingerprint] ?: ""
        override fun engine(): AlarmEngine? = alarmEngine
        override fun schedule(task: () -> Unit) = onAlarmIo(task)
        override fun audit(event: RemoteAlarmAudit) {
            alarmAuditor?.invoke(event)
        }
        override fun protocol(fingerprint: String): Int = peerProtocol[fingerprint] ?: 1
        override fun readAccess(path: String) = tags.readAccessOf(path)
        override fun writeAccess(path: String) = tags.writeAccessOf(path)
    }

    private inner class TaskHostAdapter : TaskServerHost {
        override fun dropping(): Boolean = dropCalls
        override fun fingerprint(): String = PEER_FP.get() ?: ""
        override fun access(fingerprint: String): ServiceAccess =
            policies.decide(fingerprint, peerNames[fingerprint], SERVICE_TASKS)
        override fun gatewayName(): String = settings.gatewayName
        override fun tagCount(provider: String): Int {
            val root = if (provider.isEmpty()) "[default]" else "[$provider]"
            return runCatching { tags.browse(root).size }.getOrDefault(0)
        }
        override fun board(): TaskBoard = taskBoard
    }

    private inner class NotificationHostAdapter : NotificationServerHost {
        override fun dropping(): Boolean = dropCalls
        override fun fingerprint(): String = PEER_FP.get() ?: ""
        override fun access(fingerprint: String): ServiceAccess =
            policies.decide(fingerprint, peerNames[fingerprint], SERVICE_NOTIFICATIONS)
        override fun accept(notice: RemoteNotification): Boolean = notificationSink?.invoke(notice) == true
    }

    private fun taskFailure(error: StatusRuntimeException): RuntimeException = when (error.status.code) {
        Status.Code.PERMISSION_DENIED -> AccessDeniedException(error.status.description ?: "service denied")
        Status.Code.NOT_FOUND -> NoSuchElementException(error.status.description ?: "task not found")
        Status.Code.INVALID_ARGUMENT -> IllegalArgumentException(error.status.description ?: "task rejected")
        Status.Code.UNAVAILABLE, Status.Code.UNAUTHENTICATED -> IllegalStateException("remote link is down")
        else -> IllegalStateException(error.status.description ?: "remote request failed")
    }

    private fun loadOutgoing() {
        val file = directory.resolve("outgoing.json")
        if (!file.exists()) return
        try {
            @Suppress("UNCHECKED_CAST")
            val list = Json.mapper.readValue(file.readText(), List::class.java) as List<Map<String, Any?>>
            list.forEach { map ->
                val name = map["name"] as? String ?: return@forEach
                val host = map["host"] as? String ?: return@forEach
                val port = (map["port"] as? Number)?.toInt() ?: return@forEach
                val enabled = map["enabled"] as? Boolean ?: true
                val description = map["description"] as? String ?: ""
                outgoing[name] = OutgoingRuntime(OutgoingSpec(name, host, port, enabled, description), null)
            }
        } catch (_: Exception) {
            outgoing.clear()
        }
    }

    private fun saveOutgoing() {
        val body = outgoing.values.map {
            linkedMapOf<String, Any?>(
                "name" to it.spec.name,
                "host" to it.spec.host,
                "port" to it.spec.port,
                "enabled" to it.spec.enabled,
                "description" to it.spec.description,
            )
        }
        PrivateFiles.writeReadable(directory.resolve("outgoing.json"), Json.mapper.writeValueAsBytes(body))
    }

    private fun loadProviders() {
        val file = directory.resolve("providers.json")
        if (!file.exists()) return
        try {
            @Suppress("UNCHECKED_CAST")
            val list = Json.mapper.readValue(file.readText(), List::class.java) as List<Map<String, Any?>>
            list.forEach { map ->
                val name = map["name"] as? String ?: return@forEach
                val connection = map["connection"] as? String ?: return@forEach
                val provider = map["provider"] as? String ?: "default"
                val enabled = map["enabled"] as? Boolean ?: true
                providers[name] = RemoteProviderSpec(name, connection, provider, enabled)
            }
        } catch (_: Exception) {
            providers.clear()
        }
    }

    private fun saveProviders() {
        val body = providers.values.map {
            linkedMapOf<String, Any?>(
                "name" to it.name,
                "connection" to it.connection,
                "provider" to it.provider,
                "enabled" to it.enabled,
            )
        }
        PrivateFiles.writeReadable(directory.resolve("providers.json"), Json.mapper.writeValueAsBytes(body))
    }

    private companion object {
        val PEER_FP: Context.Key<String> = Context.key("oip-peer-fingerprint")
        val NAME = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?""")
        val HOST = Regex("""[A-Za-z0-9._-]{1,253}""")
        val FINGERPRINT = Regex("""[0-9a-fA-F]{64}""")
        val GATEWAY_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,62}""")
        val SERVICE_NAME = Regex("""[a-z][a-z0-9-]{0,31}""")
        const val MAX_MESSAGE = 8 * 1024 * 1024

        fun normalizeFingerprint(fingerprint: String): String {
            val trimmed = fingerprint.trim().lowercase()
            if (!FINGERPRINT.matches(trimmed)) throw IllegalArgumentException("certificate fingerprint must be 64 hex characters")
            return trimmed
        }
    }
}

private class LivePeer(
    val gatewayName: String,
    val version: String,
    @Volatile var lastCommMillis: Long,
    @Volatile var pingMillis: Long,
    @Volatile var running: Boolean,
    @Volatile var protocol: Int = 1,
)

private class OutgoingRuntime(var spec: OutgoingSpec, var ssl: SslContext?) {
    @Volatile var status: String = if (spec.enabled) STATUS_CONNECTING else STATUS_DISABLED
    @Volatile var faultCount: Int = 0
    @Volatile var connectCount: Int = 0
    @Volatile var lastError: String? = null
    @Volatile var lastCommMillis: Long? = null
    @Volatile var pingMillis: Long? = null
    @Volatile var thread: Thread? = null
    @Volatile var asyncStub: RemoteTagsGrpc.RemoteTagsStub? = null
    @Volatile var blockingStub: RemoteTagsGrpc.RemoteTagsBlockingStub? = null
    @Volatile var channel: ManagedChannel? = null
    @Volatile var alarmAsync: RemoteAlarmsGrpc.RemoteAlarmsStub? = null
    @Volatile var alarmBlocking: RemoteAlarmsGrpc.RemoteAlarmsBlockingStub? = null
    @Volatile var taskBlocking: org.oip.network.v1.RemoteTasksGrpc.RemoteTasksBlockingStub? = null
    @Volatile var notifyBlocking: org.oip.network.v1.RemoteNotificationsGrpc.RemoteNotificationsBlockingStub? = null
    @Volatile var peerProtocol: Int = 1
    val providers = CopyOnWriteArrayList<RemoteTagProvider>()
    val alarmSessions = CopyOnWriteArrayList<RemoteAlarmSession>()

    fun onUp(channel: ManagedChannel) {
        this.channel = channel
        asyncStub = RemoteTagsGrpc.newStub(channel)
        blockingStub = RemoteTagsGrpc.newBlockingStub(channel)
        alarmAsync = RemoteAlarmsGrpc.newStub(channel)
        alarmBlocking = RemoteAlarmsGrpc.newBlockingStub(channel)
        taskBlocking = org.oip.network.v1.RemoteTasksGrpc.newBlockingStub(channel)
        notifyBlocking = org.oip.network.v1.RemoteNotificationsGrpc.newBlockingStub(channel)
        val stub = asyncStub
        if (stub != null) providers.forEach { it.attach(stub) }
        val alarms = alarmAsync
        val alarmCalls = alarmBlocking
        if (alarms != null && alarmCalls != null) alarmSessions.forEach { it.attach(alarms, alarmCalls) }
    }

    fun onDown() {
        channel = null
        asyncStub = null
        blockingStub = null
        alarmAsync = null
        alarmBlocking = null
        taskBlocking = null
        notifyBlocking = null
        providers.forEach { it.detach() }
        alarmSessions.forEach { it.detach() }
    }

    fun publicView(trust: PeerTrust): Map<String, Any?> {
        val cert = trust.latestOutgoing(spec.name)
        return linkedMapOf(
            "name" to spec.name,
            "host" to spec.host,
            "port" to spec.port,
            "enabled" to spec.enabled,
            "description" to spec.description,
            "direction" to DIR_OUTGOING,
            "status" to status,
            "gatewayName" to cert?.gatewayName,
            "certificateState" to (cert?.state ?: "none"),
            "fingerprint" to cert?.fingerprint,
            "subject" to cert?.subject,
            "faultCount" to faultCount,
            "connectCount" to connectCount,
            "lastCommMillis" to lastCommMillis,
            "pingMillis" to pingMillis,
            "protocolVersion" to peerProtocol,
            "lastError" to lastError,
        )
    }
}

/**
 * Local provider that reads and subscribes through an approved gateway-network connection.
 * Execution stays on the remote gateway. Writes are rejected; the default tag service is read only.
 */
private class RemoteTagProvider(
    override val name: String,
    private val remoteProvider: String,
    private val link: OutgoingRuntime,
    private val timeoutMillis: Long,
) : CallerTagProvider {
    // Writes go to the owning gateway, whose tags policy and tag permissions decide (SEC-003).
    override val readOnly: Boolean = false
    private val groups = ConcurrentHashMap<String, Group>()
    @Volatile private var stub: RemoteTagsGrpc.RemoteTagsStub? = null

    fun attach(next: RemoteTagsGrpc.RemoteTagsStub) {
        stub = next
        groups.values.forEach { open(it) }
    }

    fun detach() {
        stub = null
        groups.values.forEach { it.cancel() }
    }

    override fun read(path: TagPath): QualifiedValue {
        val blocking = link.blockingStub ?: return disconnected()
        return try {
            val response = blocking.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS).read(
                TagReadRequest.newBuilder().setProvider(remoteProvider).addPaths(relative(path)).build(),
            )
            if (response.valuesCount == 0) disconnected() else decodeSample(response.getValues(0))
        } catch (error: StatusRuntimeException) {
            failedSample(error)
        } catch (_: Exception) {
            disconnected()
        }
    }

    override fun write(path: TagPath, value: Any?): WriteResult = send(path, value, "system")

    override fun write(path: TagPath, value: Any?, caller: TagCaller): WriteResult =
        send(path, value, (caller as? TagCaller.User)?.actor ?: "system")

    private fun send(path: TagPath, value: Any?, actor: String): WriteResult {
        val blocking = link.blockingStub ?: return WriteResult(Quality.BAD_NOT_CONNECTED)
        val request = TagWriteRequest.newBuilder().setProvider(remoteProvider).setPath(relative(path)).setActor(actor.take(200))
        if (value != null) request.setValuePresent(true).setValueJson(Json.mapper.writeValueAsString(value))
        return try {
            val response = blocking.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS).write(request.build())
            WriteResult(Quality.fromName(response.quality) ?: Quality(QualityCode.BAD))
        } catch (error: StatusRuntimeException) {
            // A peer without the write call answers UNIMPLEMENTED: its tags stay read-only from here.
            if (error.status.code == Status.Code.UNIMPLEMENTED) WriteResult(Quality.BAD_NOT_WRITABLE)
            else WriteResult(failedSample(error).quality)
        } catch (_: Exception) {
            WriteResult(Quality.BAD_NOT_CONNECTED)
        }
    }

    override fun subscribe(path: TagPath, listener: (TagChange) -> Unit): AutoCloseable {
        val key = relative(path)
        val group = groups.computeIfAbsent(key) { Group(key, path) }
        group.listeners.add(listener)
        group.last?.let { listener(TagChange(path, it, null)) }
        open(group)
        return AutoCloseable {
            group.listeners.remove(listener)
            if (group.listeners.isEmpty()) {
                group.cancel()
                groups.remove(key, group)
            }
        }
    }

    override fun browse(path: TagPath): List<BrowseNode> {
        val blocking = link.blockingStub ?: return emptyList()
        return try {
            val response = blocking.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS).browse(
                TagBrowseRequest.newBuilder().setProvider(remoteProvider).setPath(relative(path)).build(),
            )
            response.nodesList.map { node ->
                val child = if (path.segments.isEmpty()) TagPath(name, listOf(node.name))
                else TagPath(name, path.segments + node.name)
                BrowseNode(
                    name = node.name,
                    path = child,
                    kind = runCatching { NodeKind.valueOf(node.kind) }.getOrDefault(NodeKind.TAG),
                    dataType = node.dataType.takeIf { it.isNotEmpty() }?.let { DataType.fromName(it) },
                    value = null,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun open(group: Group) {
        val current = stub ?: return
        synchronized(group) {
            group.cancel()
            val context = Context.ROOT.withCancellation()
            group.context = context
            val request = TagSubscribeRequest.newBuilder().setProvider(remoteProvider).setPath(group.key).build()
            context.run {
                current.subscribe(request, object : StreamObserver<TagSample> {
                    override fun onNext(value: TagSample) = deliver(group, decodeSample(value))
                    override fun onError(t: Throwable) = deliver(group, failedSample(t))
                    override fun onCompleted() = deliver(group, disconnected())
                })
            }
        }
    }

    private fun deliver(group: Group, value: QualifiedValue) {
        val previous = group.last
        group.last = value
        val change = TagChange(group.path, value, previous)
        group.listeners.toList().forEach { listener ->
            try {
                listener(change)
            } catch (_: Exception) {
                // A subscriber must not break the stream.
            }
        }
    }

    private fun relative(path: TagPath): String = path.segments.joinToString("/")

    private class Group(val key: String, val path: TagPath) {
        val listeners = CopyOnWriteArrayList<(TagChange) -> Unit>()
        @Volatile var last: QualifiedValue? = null
        @Volatile var context: Context.CancellableContext? = null
        fun cancel() {
            context?.cancel(null)
            context = null
        }
    }
}

private class FingerprintTrustManager(
    private val direction: String,
    private val trust: PeerTrust,
    private val connection: String?,
) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = decide(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = decide(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) = decide(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) = decide(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = decide(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = decide(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun decide(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("certificate missing")
        when (trust.observe(direction, leaf, connection)) {
            STATE_APPROVED -> return
            STATE_DENIED -> throw CertificateException("certificate denied")
            else -> throw CertificateException("certificate pending approval")
        }
    }
}

private fun failureKind(error: Throwable): String? {
    val text = safeError(error)
    return when {
        text.contains("certificate denied") || text.contains("UNAUTHENTICATED") && text.contains("denied") -> STATE_DENIED
        text.contains("pending approval") -> STATE_PENDING
        else -> null
    }
}

private fun safeError(error: Throwable): String {
    val parts = ArrayList<String>(4)
    var current: Throwable? = error
    var guard = 0
    while (current != null && guard++ < 8) {
        current.message?.let { parts += it }
        current = current.cause
    }
    return parts.joinToString(" ").replace(Regex("-----BEGIN [^-]+-----"), "").take(300)
}

private fun encodeSample(path: String, value: QualifiedValue): TagSample {
    val builder = TagSample.newBuilder()
        .setPath(path)
        .setQuality(value.quality.code.label)
        .setTimestampUnixMicros(value.timestamp.epochSecond * 1_000_000 + value.timestamp.nano / 1_000)
    if (value.value != null) {
        builder.setValuePresent(true)
        builder.setValueJson(Json.mapper.writeValueAsString(value.value))
    }
    return builder.build()
}

private fun deniedSample(path: String): TagSample = TagSample.newBuilder()
    .setPath(path)
    .setQuality(QualityCode.BAD_USER_ACCESS_DENIED.label)
    .setTimestampUnixMicros(System.currentTimeMillis() * 1_000)
    .build()

private fun decodeSample(sample: TagSample): QualifiedValue {
    val quality = Quality.fromName(sample.quality) ?: Quality(QualityCode.BAD)
    val timestamp = if (sample.timestampUnixMicros <= 0) Instant.EPOCH else {
        val seconds = Math.floorDiv(sample.timestampUnixMicros, 1_000_000L)
        val micros = Math.floorMod(sample.timestampUnixMicros, 1_000_000L)
        Instant.ofEpochSecond(seconds, micros * 1_000)
    }
    val raw = if (!sample.valuePresent || sample.valueJson.isEmpty()) null
    else try {
        Json.mapper.readValue(sample.valueJson, Any::class.java)
    } catch (_: Exception) {
        null
    }
    return QualifiedValue(raw, quality, timestamp)
}

private fun disconnected(): QualifiedValue =
    QualifiedValue(null, Quality.BAD_NOT_CONNECTED, Instant.EPOCH)

private fun failedSample(error: Throwable): QualifiedValue {
    val code = when (statusCode(error)) {
        Status.Code.PERMISSION_DENIED -> QualityCode.BAD_USER_ACCESS_DENIED
        Status.Code.UNAUTHENTICATED -> QualityCode.BAD_NOT_CONNECTED
        else -> if (failureKind(error) == STATE_PENDING || failureKind(error) == STATE_DENIED) {
            QualityCode.BAD_NOT_CONNECTED
        } else QualityCode.BAD_NOT_CONNECTED
    }
    return QualifiedValue(null, Quality(code), Instant.EPOCH)
}

private fun statusCode(error: Throwable): Status.Code? {
    var current: Throwable? = error
    var guard = 0
    while (current != null && guard++ < 8) {
        if (current is StatusRuntimeException) return current.status.code
        current = current.cause
    }
    return null
}

/** The zone a peer's remote write runs in; a tag whose write permissions name zones refuses it (fail closed). */
internal const val REMOTE_WRITE_ZONE = "gateway-network"
/** Audit context of a write a peer made through its remote tag provider (SEC-003). */
internal const val REMOTE_WRITE_CONTEXT = "gateway-network"

private fun browseNode(node: BrowseNode): TagBrowseNode {
    val builder = TagBrowseNode.newBuilder()
        .setName(node.name)
        .setPath(node.path.segments.joinToString("/"))
        .setKind(node.kind.name)
    node.dataType?.let { builder.setDataType(it.label()) }
    return builder.build()
}
