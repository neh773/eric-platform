package org.oip.gateway

import org.eclipse.jetty.http.HttpVersion
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.util.ssl.SslContextFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

const val HTTPS_CERTIFICATE_ACTION = "https certificate replaced"
const val HTTPS_CSR_CREATED_ACTION = "https csr created"
const val HTTPS_CSR_INSTALLED_ACTION = "https csr reply installed"
const val HTTPS_CSR_DISCARDED_ACTION = "https csr discarded"
const val HTTPS_TLS_ACTION = "https tls policy changed"
const val HTTPS_SETTINGS_ACTION = "https web server settings saved"

/**
 * TLS listener for the gateway web server.
 *
 * Active material is `<dataDir>/https/server.crt` and `server.key` (mode 0600).
 * A drop of `certificate.pem` plus `private-key.pem`, or `server.p12`, in `https/incoming`
 * replaces the live certificate without a process restart when the listener is up.
 * The private key is not logged.
 *
 * A certificate signing request keeps its new key in `pending.key` (mode 0600) beside `pending.csr` until
 * the CA reply is installed or the request is discarded; the key never leaves the gateway. The protocol
 * and cipher-suite choice lives in `tls.json` and applies to the running listener ([TlsPolicy]).
 * Port, public address, and redirect edits are written to `gateway.yaml` and apply on the next start.
 */
class HttpsEndpoint(
    private val settings: GatewaySettings,
    private val dir: Path,
    private val gatewayYaml: Path = dir.resolveSibling("gateway.yaml"),
    private val httpPort: () -> Int,
) {
    private val incoming = dir.resolve("incoming")
    private val certFile = dir.resolve("server.crt")
    private val keyFile = dir.resolve("server.key")
    private val sourceFile = dir.resolve("server.source")
    private val tlsFile = dir.resolve("tls.json")
    private val pendingKeyFile = dir.resolve("pending.key")
    private val pendingCsrFile = dir.resolve("pending.csr")
    @Volatile private var policy: TlsPolicy = TlsPolicy.DEFAULT
    @Volatile private var savedSettings: Map<String, Any?>? = null
    private val storePassword = charArrayOf('o', 'i', 'p', '-', 'm', 'e', 'm', 'o', 'r', 'y')
    private val lock = Any()
    private val rejectSeq = AtomicInteger()
    @Volatile private var material: TlsMaterial? = null
    @Volatile private var source: String = "stored"
    private var ssl: SslContextFactory.Server? = null
    /** The listener's factory while [releaseFiles] has it stopped for a restore; [reloadStored] starts it again. */
    private var released: SslContextFactory.Server? = null
    private var wasWatching = false
    private var tls: ServerConnector? = null
    private var watcher: java.util.concurrent.ScheduledExecutorService? = null
    private var pending: Map<String, Long>? = null
    private var failed: Map<String, Long>? = null
    var replaced: ((actor: String, fingerprint: String, source: String) -> Unit)? = null

    fun prepare() {
        synchronized(lock) {
            policy = readPolicy()
            if (settings.httpsEnabled) {
                val resolved = resolveForListener()
                material = resolved.first
                source = resolved.second
            } else if (certFile.exists() && keyFile.exists()) {
                val loaded = readActive()
                material = loaded.first
                source = loaded.second
            }
        }
    }

    fun httpConnector(server: Server, httpConfig: HttpConfiguration): ServerConnector {
        val connector = ServerConnector(server, HttpConnectionFactory(httpConfig))
        connector.host = settings.host
        connector.port = settings.port
        connector.reuseAddress = true
        return connector
    }

    fun tlsConnector(server: Server, httpConfig: HttpConfiguration): ServerConnector {
        val current = material ?: throw IllegalStateException("HTTPS is enabled and no certificate is loaded")
        val httpsConfig = HttpConfiguration(httpConfig)
        httpsConfig.addCustomizer(SecureRequestCustomizer())
        val factory = newFactory(current)
        ssl = factory
        val connector = ServerConnector(
            server,
            SslConnectionFactory(factory, HttpVersion.HTTP_1_1.asString()),
            HttpConnectionFactory(httpsConfig),
        )
        connector.host = settings.host
        connector.port = settings.httpsPort
        connector.reuseAddress = true
        tls = connector
        return connector
    }

    fun listenPort(): Int? = tls?.localPort?.takeIf { it > 0 }

    fun redirectTarget(scheme: String, hostHeader: String?, path: String, query: String?): String? {
        if (!settings.httpsEnabled || !settings.forceHttpsRedirect) return null
        if (scheme.equals("https", ignoreCase = true)) return null
        return HttpsRedirect.url(
            settings.publicAddress,
            settings.publicHttpsPort,
            listenPort(),
            hostHeader,
            fallbackHost(),
            path,
            query,
        )
    }

    fun status(): Map<String, Any?> {
        val current = material
        return linkedMapOf(
            "enabled" to settings.httpsEnabled,
            "listening" to (listenPort() != null),
            "httpPort" to httpPort(),
            "httpsPort" to settings.httpsPort,
            "httpsListenPort" to listenPort(),
            "publicAddress" to settings.publicAddress,
            "publicHttpsPort" to settings.publicHttpsPort,
            "forceHttpsRedirect" to settings.forceHttpsRedirect,
            "protocols" to (ssl?.selectedProtocols?.toList()?.takeIf { listenPort() != null } ?: policy.protocols),
            "configuredProtocols" to policy.protocols,
            "cipherSuites" to ssl?.selectedCipherSuites?.toList()?.takeIf { listenPort() != null },
            "configuredCipherSuites" to policy.cipherSuites,
            "availableCipherSuites" to TlsPolicy.available(),
            "certificate" to current?.let { TlsCertificates.view(it, source) },
            "csr" to runCatching { pendingCsr() }.getOrNull()?.let { it - "csrPem" },
            "savedSettings" to savedSettings,
        )
    }

    /** The pending request with its PEM, or null. The pending private key is never part of it. */
    fun pendingCsr(): Map<String, Any?>? = synchronized(lock) {
        if (!pendingCsrFile.exists() || !pendingKeyFile.exists()) null
        else TlsCertificates.csrView(pendingCsrFile.readText())
    }

    /** Make a new key pair and request. A request that was already pending is replaced. */
    fun createCsr(body: Map<String, Any?>): Map<String, Any?> {
        val request = CsrRequest.parse(body)
        val (csrPem, keys) = TlsCertificates.createCsr(request)
        synchronized(lock) {
            PrivateFiles.directory(dir)
            PrivateFiles.writeKey(pendingKeyFile, TlsCertificates.privateKeyPem(keys.private).toByteArray())
            PrivateFiles.restrictKey(pendingKeyFile)
            PrivateFiles.writeReadable(pendingCsrFile, csrPem.toByteArray())
        }
        return TlsCertificates.csrView(csrPem)
    }

    /** Install the CA reply for the pending request. The chain may arrive in any order. */
    fun installSignedChain(body: Map<String, Any?>): Map<String, Any?> {
        val pem = body["certificatePem"] as? String ?: throw IllegalArgumentException("certificatePem is required")
        val actor = org.oip.security.AuditContext.current()?.actor ?: "api"
        // One critical section from reading the pending key to deleting it, so a POST /csr cannot land in
        // between and have its new key and request deleted. install() takes the same (reentrant) lock.
        synchronized(lock) {
            if (!pendingKeyFile.exists()) throw IllegalArgumentException("no certificate request is pending")
            PrivateFiles.restrictKey(pendingKeyFile)
            val key = try {
                TlsCertificates.parsePrivateKey(pendingKeyFile.readText())
            } catch (_: Exception) {
                throw IllegalStateException("the pending request key could not be read")
            }
            val material = TlsCertificates.chainForKey(pem, key)
            install(material, "csr", actor)
            Files.deleteIfExists(pendingKeyFile)
            Files.deleteIfExists(pendingCsrFile)
        }
        return status()
    }

    /** Drop the pending request and its key. Returns false when nothing was pending. */
    fun discardCsr(): Boolean = synchronized(lock) {
        val had = pendingKeyFile.exists() || pendingCsrFile.exists()
        Files.deleteIfExists(pendingKeyFile)
        Files.deleteIfExists(pendingCsrFile)
        had
    }

    /** Store a new protocol and cipher-suite choice and apply it to the running listener. */
    fun updateTls(body: Map<String, Any?>): Map<String, Any?> {
        val next = TlsPolicy.parse(body)
        synchronized(lock) {
            val previous = policy
            val previousBytes = if (tlsFile.exists()) tlsFile.readBytes() else null
            PrivateFiles.directory(dir)
            PrivateFiles.writeReadable(tlsFile, Json.write(next.toMap()).toByteArray())
            try {
                applyLive(material, next)
                policy = next
            } catch (_: Exception) {
                runCatching {
                    if (previousBytes != null) PrivateFiles.writeReadable(tlsFile, previousBytes) else Files.deleteIfExists(tlsFile)
                    applyLive(material, previous)
                }
                throw IllegalStateException("TLS settings could not be applied")
            }
        }
        return status()
    }

    /**
     * Save ports, public address, and redirect to `gateway.yaml`. The file is checked with the same
     * parser the gateway uses at start, so a bad value is refused and the file is left alone.
     * The listener keeps its current values until the next start.
     */
    fun updateSettings(body: Map<String, Any?>, env: Map<String, String> = System.getenv()): Map<String, Any?> {
        val unknown = body.keys - SETTINGS_KEYS.keys
        if (unknown.isNotEmpty()) throw IllegalArgumentException("unknown settings: ${unknown.sorted()}")
        synchronized(lock) {
            val yaml = ObjectMapper(YAMLFactory())
            @Suppress("UNCHECKED_CAST")
            val root = (if (gatewayYaml.exists()) yaml.readValue(gatewayYaml.readText(), Any::class.java) else null)
                as? Map<String, Any?> ?: emptyMap()
            val doc = LinkedHashMap(root)
            @Suppress("UNCHECKED_CAST")
            val http = LinkedHashMap((doc["http"] as? Map<String, Any?>) ?: emptyMap())
            for ((field, value) in body) {
                val key = SETTINGS_KEYS.getValue(field)
                if (value == null || (value is String && value.isBlank())) http.remove(key) else http[key] = value
            }
            doc["http"] = http
            val text = "# Saved from the admin Network page. Port and redirect changes apply on the next start.\n" +
                yaml.writeValueAsString(doc)
            val tmp = gatewayYaml.resolveSibling("${gatewayYaml.fileName}.tmp-${UUID.randomUUID()}")
            try {
                Files.writeString(tmp, text)
                try {
                    GatewaySettings.load(tmp, emptyMap())
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(e.message ?: "settings were rejected")
                } catch (_: Exception) {
                    throw IllegalArgumentException("settings were rejected")
                }
                Files.move(tmp, gatewayYaml, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
            val saved = linkedMapOf<String, Any?>()
            for ((field, key) in SETTINGS_KEYS) saved[field] = http[key]
            saved["overriddenByEnvironment"] = SETTINGS_KEYS.keys.filter { !env[SETTINGS_ENV.getValue(it)].isNullOrBlank() }
            saved["restartRequired"] = true
            savedSettings = saved
        }
        return status()
    }

    /**
     * Load the certificate and TLS choice from disk again, after a backup restore replaced `https/`.
     * The running listener switches to them on the next handshake.
     */
    fun reloadStored() {
        synchronized(lock) {
            // A restore stopped the listener's factory to free `https/` (Windows). Start that same factory again,
            // so the running HTTPS connector keeps answering without a gateway restart.
            released?.let { factory ->
                released = null
                ssl = factory
                runCatching { factory.start() }
                if (wasWatching) runCatching { watchIncoming() }
            }
            policy = readPolicy()
            val loaded = if (certFile.exists() && keyFile.exists()) readActive() else null
            if (loaded != null) {
                applyLive(loaded.first, policy)
                material = loaded.first
                source = loaded.second
            } else {
                applyLive(material, policy)
            }
        }
    }

    fun fingerprint(): String? = material?.fingerprint()

    fun activeSource(): String = source

    fun replaceUpload(body: Map<String, Any?>): Map<String, Any?> {
        val format = body["format"] as? String ?: throw IllegalArgumentException("format must be pem or pkcs12")
        val parsed = when (format) {
            "pem" -> {
                val certificate = body["certificatePem"] as? String
                    ?: throw IllegalArgumentException("certificatePem is required")
                val key = body["privateKeyPem"] as? String
                    ?: throw IllegalArgumentException("privateKeyPem is required")
                TlsCertificates.parsePem(certificate, key)
            }
            "pkcs12" -> {
                val encoded = body["pkcs12Base64"] as? String
                    ?: throw IllegalArgumentException("pkcs12Base64 is required")
                val password = (body["password"] as? String ?: "").toCharArray()
                try {
                    val decoded = try {
                        java.util.Base64.getMimeDecoder().decode(encoded)
                    } catch (_: IllegalArgumentException) {
                        throw IllegalArgumentException("pkcs12Base64 is not base64")
                    }
                    TlsCertificates.parsePkcs12(decoded, password)
                } finally {
                    password.fill('\u0000')
                }
            }
            else -> throw IllegalArgumentException("format must be pem or pkcs12")
        }
        val actor = org.oip.security.AuditContext.current()?.actor ?: "api"
        install(parsed, "uploaded", actor)
        return status()
    }

    fun watchIncoming() {
        if (watcher != null) return
        PrivateFiles.directory(incoming)
        watcher = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "https-incoming").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({ runCatching { pollIncoming() } }, 500, 500, TimeUnit.MILLISECONDS)
        }
    }

    fun close() {
        releaseFiles()
    }

    /** Stop the drop watcher and the TLS factory so Windows can rename or delete `https/`. */
    fun releaseFiles() {
        wasWatching = watcher != null
        watcher?.shutdownNow()
        watcher = null
        val factory = ssl
        ssl = null
        if (factory != null) {
            released = factory
            runCatching { factory.stop() }
        }
    }

    private fun resolveForListener(): Pair<TlsMaterial, String> {
        val configured = loadConfigured()
        if (configured != null) {
            writeActive(configured.first, configured.second)
            return configured
        }
        if (certFile.exists() || keyFile.exists()) {
            if (!certFile.exists() || !keyFile.exists()) {
                throw IllegalStateException("HTTPS certificate in $dir is incomplete; expected both server.crt and server.key")
            }
            return readActive()
        }
        val created = TlsCertificates.generate(sanInputs())
        writeActive(created, "generated")
        return created to "generated"
    }

    private fun loadConfigured(): Pair<TlsMaterial, String>? {
        val pemCert = settings.certificatePath
        val pemKey = settings.privateKeyPath
        val pkcs12 = settings.pkcs12Path
        if (pemCert == null && pemKey == null && pkcs12 == null) return null
        if (pemCert != null && pemKey != null) {
            val certPath = Path.of(pemCert)
            val keyPath = Path.of(pemKey)
            if (!certPath.exists() || !keyPath.exists()) {
                throw IllegalArgumentException("http.certificate or http.privateKey does not exist")
            }
            return try {
                TlsCertificates.parsePem(certPath.readText(), keyPath.readText()) to "configured"
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("configured HTTPS certificate was rejected: ${safe(e)}")
            }
        }
        val p12 = Path.of(pkcs12!!)
        if (!p12.exists()) throw IllegalArgumentException("http.pkcs12 does not exist")
        val password = pkcs12Password()
        try {
            return TlsCertificates.parsePkcs12(p12.readBytes(), password) to "configured"
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("configured HTTPS certificate was rejected: ${safe(e)}")
        } finally {
            password.fill('\u0000')
        }
    }

    private fun readActive(): Pair<TlsMaterial, String> {
        PrivateFiles.restrictKey(keyFile)
        val parsed = try {
            TlsCertificates.parsePem(certFile.readText(), keyFile.readText())
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("stored HTTPS certificate was rejected: ${safe(e)}")
        }
        return parsed to readSource()
    }

    private fun writeActive(material: TlsMaterial, nextSource: String) {
        PrivateFiles.directory(dir)
        PrivateFiles.writeReadable(certFile, TlsCertificates.certificatePem(material.chain).toByteArray())
        PrivateFiles.writeKey(keyFile, TlsCertificates.privateKeyPem(material.privateKey).toByteArray())
        PrivateFiles.restrictKey(keyFile)
        PrivateFiles.writeReadable(sourceFile, nextSource.toByteArray())
    }

    private fun install(next: TlsMaterial, nextSource: String, actor: String) {
        synchronized(lock) {
            val previous = snapshot()
            try {
                writeActive(next, nextSource)
                reload(next)
                material = next
                source = nextSource
            } catch (e: Exception) {
                if (previous != null) runCatching { restore(previous) }
                if (e is IllegalArgumentException || e is IllegalStateException) throw e
                throw IllegalStateException("HTTPS certificate could not be applied")
            }
        }
        try {
            replaced?.invoke(actor, next.fingerprint(), nextSource)
        } catch (_: Exception) {
            // A failed audit record does not roll the certificate back.
        }
    }

    private fun reload(next: TlsMaterial) = applyLive(next, policy)

    /** Swap key material and TLS policy on the running listener. No-op while the listener is off. */
    private fun applyLive(next: TlsMaterial?, nextPolicy: TlsPolicy) {
        val factory = ssl ?: return
        val store = next?.toKeyStore(storePassword)
        factory.reload { updated ->
            if (store != null) {
                updated.keyStore = store
                updated.setKeyStorePassword(String(storePassword))
                updated.setKeyManagerPassword(String(storePassword))
                updated.certAlias = "gateway"
            }
            applyPolicy(updated, nextPolicy)
        }
    }

    private fun newFactory(current: TlsMaterial): SslContextFactory.Server {
        val factory = SslContextFactory.Server()
        factory.setKeyStorePassword(String(storePassword))
        factory.setKeyManagerPassword(String(storePassword))
        factory.keyStore = current.toKeyStore(storePassword)
        applyPolicy(factory, policy)
        factory.certAlias = "gateway"
        return factory
    }

    /** TLS 1.1 and older and the weak suites stay excluded whatever the include lists say. */
    private fun applyPolicy(factory: SslContextFactory, next: TlsPolicy) {
        factory.setIncludeProtocols(*next.protocols.toTypedArray())
        factory.setExcludeProtocols(*TlsPolicy.REFUSED_PROTOCOLS.toTypedArray())
        factory.setIncludeCipherSuites(*(next.cipherSuites ?: emptyList()).toTypedArray())
        factory.setExcludeCipherSuites(*TlsPolicy.WEAK.toTypedArray())
    }

    /** The stored choice, or the default when there is none or it no longer validates. */
    private fun readPolicy(): TlsPolicy {
        if (!tlsFile.exists()) return TlsPolicy.DEFAULT
        return runCatching { TlsPolicy.parse(Json.parse(tlsFile.readText())) }.getOrDefault(TlsPolicy.DEFAULT)
    }

    private fun pollIncoming() {
        val sizes = incomingSizes()
        val pemReady = "certificate.pem" in sizes && "private-key.pem" in sizes
        val p12Ready = "server.p12" in sizes
        if (!pemReady && !p12Ready) {
            pending = null
            return
        }
        if (pemReady && p12Ready) {
            if (sizes == failed) return
            failed = sizes
            quarantine("incoming has both PEM and PKCS#12")
            failed = null
            return
        }
        if (sizes == failed) return
        if (sizes != pending) {
            pending = sizes
            return
        }
        pending = null
        try {
            if (pemReady) {
                val parsed = TlsCertificates.parsePem(
                    incoming.resolve("certificate.pem").readText(),
                    incoming.resolve("private-key.pem").readText(),
                )
                install(parsed, "file", "file")
                Files.deleteIfExists(incoming.resolve("certificate.pem"))
                Files.deleteIfExists(incoming.resolve("private-key.pem"))
            } else {
                val password = dropPassword()
                try {
                    val parsed = TlsCertificates.parsePkcs12(incoming.resolve("server.p12").readBytes(), password)
                    install(parsed, "file", "file")
                    Files.deleteIfExists(incoming.resolve("server.p12"))
                    Files.deleteIfExists(incoming.resolve("password"))
                } finally {
                    password.fill('\u0000')
                }
            }
            failed = null
        } catch (e: Exception) {
            failed = sizes
            try {
                quarantine(safe(e))
                failed = null
            } catch (_: Exception) {
                // The drop stays in place. The same sizes are not retried every poll.
            }
        }
    }

    private fun incomingSizes(): Map<String, Long> {
        if (!incoming.exists()) return emptyMap()
        val names = listOf("certificate.pem", "private-key.pem", "server.p12")
        val out = linkedMapOf<String, Long>()
        for (name in names) {
            val path = incoming.resolve(name)
            if (path.exists() && Files.size(path) > 0L) out[name] = Files.size(path)
        }
        return out
    }

    private fun quarantine(message: String) {
        val dest = dir.resolve("rejected").resolve(Instant.now().toEpochMilli().toString() + "-" + failedCounter())
        PrivateFiles.directory(dest)
        for (name in listOf("certificate.pem", "private-key.pem", "server.p12", "password")) {
            val src = incoming.resolve(name)
            if (!src.exists()) continue
            val target = dest.resolve(name)
            Files.move(src, target)
            if (name != "certificate.pem") runCatching { PrivateFiles.restrictKey(target) }
        }
        val text = if (message.contains("PRIVATE") || message.contains("-----") || message.length > 200) {
            "certificate was rejected"
        } else {
            message
        }
        Files.writeString(dest.resolve("error.txt"), text + "\n")
        pending = null
    }

    private fun snapshot(): Triple<ByteArray, ByteArray, ByteArray?>? {
        if (!certFile.exists() || !keyFile.exists()) return null
        val sourceBytes = if (sourceFile.exists()) sourceFile.readBytes() else null
        return Triple(certFile.readBytes(), keyFile.readBytes(), sourceBytes)
    }

    private fun restore(previous: Triple<ByteArray, ByteArray, ByteArray?>) {
        PrivateFiles.writeReadable(certFile, previous.first)
        PrivateFiles.writeKey(keyFile, previous.second)
        val sourceBytes = previous.third
        if (sourceBytes != null) PrivateFiles.writeReadable(sourceFile, sourceBytes) else Files.deleteIfExists(sourceFile)
        val restored = readActive()
        reload(restored.first)
        material = restored.first
        source = restored.second
    }

    private fun readSource(): String {
        if (!sourceFile.exists()) return "stored"
        val text = sourceFile.readText().trim()
        return if (text.matches(Regex("[a-z]+"))) text else "stored"
    }

    private fun sanInputs(): List<String> {
        val names = ArrayList<String>()
        if (settings.host != "0.0.0.0" && settings.host != "::" && settings.host != "[::]") names.add(settings.host)
        settings.publicAddress?.let { names.add(it) }
        return names
    }

    private fun fallbackHost(): String = when (settings.host) {
        "0.0.0.0", "::", "[::]" -> "127.0.0.1"
        else -> settings.host
    }

    private fun dropPassword(): CharArray {
        val file = incoming.resolve("password")
        if (file.exists()) {
            runCatching { PrivateFiles.restrictKey(file) }
            val text = file.readText()
            val stripped = if (text.endsWith("\n")) text.dropLast(1).removeSuffix("\r") else text
            return stripped.toCharArray()
        }
        return pkcs12Password()
    }

    private fun pkcs12Password(): CharArray {
        val raw = System.getenv("OIP_HTTPS_PKCS12_PASSWORD") ?: return CharArray(0)
        if (raw.isEmpty()) return CharArray(0)
        return raw.toCharArray()
    }

    private fun safe(error: Exception): String {
        val message = error.message ?: "certificate was rejected"
        if (message.contains("PRIVATE") || message.contains("-----")) return "certificate was rejected"
        return message
    }

    private fun failedCounter(): Int = rejectSeq.incrementAndGet()

    companion object {
        /** Request field to `gateway.yaml` `http` key. */
        val SETTINGS_KEYS: Map<String, String> = linkedMapOf(
            "httpPort" to "port",
            "httpsEnabled" to "https",
            "httpsPort" to "httpsPort",
            "publicAddress" to "publicAddress",
            "publicHttpsPort" to "publicHttpsPort",
            "forceHttpsRedirect" to "forceHttpsRedirect",
        )
        private val SETTINGS_ENV: Map<String, String> = mapOf(
            "httpPort" to "OIP_HTTP_PORT",
            "httpsEnabled" to "OIP_HTTP_HTTPS",
            "httpsPort" to "OIP_HTTP_HTTPS_PORT",
            "publicAddress" to "OIP_HTTP_PUBLIC_ADDRESS",
            "publicHttpsPort" to "OIP_HTTP_PUBLIC_HTTPS_PORT",
            "forceHttpsRedirect" to "OIP_HTTP_FORCE_REDIRECT",
        )

        /** Audit action and target of each mutating `/api/v1/https` route, keyed by "VERB path". */
        private val WRITE_ROUTES: Map<String, Pair<String, String>> = mapOf(
            "PUT /api/v1/https/certificate" to (HTTPS_CERTIFICATE_ACTION to "https/server.crt"),
            "POST /api/v1/https/csr" to (HTTPS_CSR_CREATED_ACTION to "https/pending.csr"),
            "DELETE /api/v1/https/csr" to (HTTPS_CSR_DISCARDED_ACTION to "https/pending.csr"),
            "POST /api/v1/https/csr/install" to (HTTPS_CSR_INSTALLED_ACTION to "https/server.crt"),
            "PUT /api/v1/https/tls" to (HTTPS_TLS_ACTION to "https/tls.json"),
            "PUT /api/v1/https/settings" to (HTTPS_SETTINGS_ACTION to "gateway.yaml"),
        )

        /** The audit action and target for a mutating HTTPS route, or null when [path] is not one. */
        fun writeRoute(method: String, path: String): Pair<String, String>? {
            val trimmed = if (path.length > 1 && path.endsWith('/')) path.trimEnd('/') else path
            return WRITE_ROUTES["${method.uppercase()} $trimmed"]
        }
    }
}
