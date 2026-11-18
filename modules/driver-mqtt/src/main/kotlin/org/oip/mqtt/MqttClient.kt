package org.oip.mqtt

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * One MQTT 3.1.1 or MQTT 5 client session over TCP (or TLS).
 *
 * Socket writes are serialized. [poll] is the only reader. A QoS 1 publish from another thread
 * completes when [poll] sees PUBACK, so the reader thread must not wait on that future.
 * Payloads and credentials are not logged.
 *
 * Spec: https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html
 * Spec: https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html
 */
class MqttClient(
    private val host: String,
    private val port: Int,
    clientId: String,
    protocol: MqttProtocol = MqttProtocol.MQTT_3_1_1,
    keepAliveSeconds: Int = 30,
    cleanSession: Boolean = true,
    username: String? = null,
    password: ByteArray? = null,
    will: MqttWill? = null,
    private val tls: MqttTls = MqttTls(),
    private val connectTimeoutMs: Int = 5_000,
) : AutoCloseable {
    private val spec = MqttConnectSpec(protocol, clientId, keepAliveSeconds, cleanSession, username, password, will)
    private val outLock = Any()
    private val ackLock = Any()
    private val subscriptions = ConcurrentHashMap.newKeySet<String>()
    private val pendingAcks = HashMap<Int, CompletableFuture<Boolean>>()
    private var nextPacketId = 1
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    /** The socket of a [connect] in progress, so [drop] from another thread can abort it. */
    @Volatile private var connecting: Socket? = null
    @Volatile var open: Boolean = false
        private set

    fun connect() {
        val tcp = if (tls.enabled) openTls() else Socket()
        tcp.tcpNoDelay = true
        connecting = tcp
        try {
            if (!tls.enabled) tcp.connect(InetSocketAddress(host, port), connectTimeoutMs)
            tcp.soTimeout = connectTimeoutMs
            val inn = tcp.getInputStream()
            val out = tcp.getOutputStream()
            synchronized(outLock) {
                out.write(MqttWire.encodeConnect(spec))
                out.flush()
            }
            val packet = MqttWire.readPacket(inn) ?: throw EOFException("mqtt broker closed before CONNACK")
            if ((packet.first and 0xF0) != 0x20) throw IllegalStateException("mqtt CONNACK missing")
            val reason = connAckReason(packet.second)
            if (reason != 0) throw IllegalStateException("mqtt connect rejected ($reason)")
            socket = tcp
            input = inn
            output = out
            open = true
        } catch (e: Exception) {
            runCatching { tcp.close() }
            throw e
        } finally {
            connecting = null
        }
    }

    /**
     * Next inbound PUBLISH, or null when [idleTimeoutMs] elapses with no packet.
     * PUBACK, PINGRESP, SUBACK and other session packets are consumed here. Any non-null
     * result, including [Activity], means the broker sent bytes.
     */
    internal fun poll(idleTimeoutMs: Int): MqttPoll? {
        val tcp = socket ?: return null
        val inn = input ?: return null
        tcp.soTimeout = idleTimeoutMs.coerceAtLeast(1)
        val first = try {
            inn.read()
        } catch (_: SocketTimeoutException) {
            return null
        }
        if (first < 0) {
            open = false
            throw EOFException("mqtt connection closed")
        }
        tcp.soTimeout = connectTimeoutMs.coerceAtLeast(1_000)
        val remaining = readRemaining(inn)
        if (remaining > MqttWire.MAX_PACKET) throw IllegalStateException("mqtt packet is larger than ${MqttWire.MAX_PACKET} bytes")
        val body = readFully(inn, remaining)
        return when (first and 0xF0) {
            0x30 -> {
                val qos = (first shr 1) and 0x03
                if (qos == 1) {
                    val id = MqttWire.publishPacketId(first, body)
                    if (id != null) send(MqttWire.encodePubAck(id))
                }
                MqttPoll.Published(MqttWire.decodePublish(spec.protocol, first, body))
            }
            0x40 -> {
                if (body.size >= 2) {
                    val id = MqttWire.readShort(body, 0)
                    val reason = if (body.size >= 3) body[2].toInt() and 0xFF else 0
                    completeAck(id, reason < 0x80)
                }
                MqttPoll.Activity
            }
            0xD0, 0x90, 0xB0 -> MqttPoll.Activity
            else -> MqttPoll.Activity
        }
    }

    fun subscribe(filter: String, qos: Int = 1) {
        if (!subscriptions.add(filter)) return
        val id = synchronized(outLock) { takePacketId() }
        send(MqttWire.encodeSubscribe(spec.protocol, id, filter, qos))
    }

    fun publish(topic: String, payload: ByteArray, qos: Int = 0, retain: Boolean = false, awaitAck: Boolean = false, ackTimeoutMs: Long = 2_000): Boolean {
        val id = if (qos > 0) synchronized(outLock) { takePacketId() } else 0
        val future = if (qos > 0 && awaitAck) CompletableFuture<Boolean>() else null
        if (future != null) synchronized(ackLock) { pendingAcks[id] = future }
        return try {
            send(MqttWire.encodePublish(spec.protocol, topic, payload, qos, retain, id))
            if (future == null) true else future.get(ackTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (future != null) synchronized(ackLock) { pendingAcks.remove(id) }
            throw e
        }
    }

    /**
     * QoS 1 publish for the reader thread, which cannot wait on [publish]'s ack future: send, then [poll] until
     * this packet's PUBACK or [timeoutMs]. Inbound publishes read meanwhile are dropped, so use it only to end
     * a session. Returns whether the broker acknowledged the packet in time.
     */
    fun publishPollingAck(topic: String, payload: ByteArray, retain: Boolean = false, timeoutMs: Long = 1_000): Boolean {
        val id = synchronized(outLock) { takePacketId() }
        val future = CompletableFuture<Boolean>()
        synchronized(ackLock) { pendingAcks[id] = future }
        try {
            send(MqttWire.encodePublish(spec.protocol, topic, payload, 1, retain, id))
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!future.isDone) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) break
                poll(left.toInt())
            }
            return future.getNow(false)
        } finally {
            synchronized(ackLock) { pendingAcks.remove(id) }
        }
    }

    fun ping() {
        send(MqttWire.encodePing())
    }

    /**
     * Send DISCONNECT, half-close, and read what the broker still sends (bounded by [DRAIN_MS]) before closing.
     * Closing with unread bytes in the receive buffer resets the connection; a broker that is still
     * acknowledging an earlier packet then sees a lost client and publishes the will.
     */
    fun disconnect() {
        if (!open) return
        runCatching { send(MqttWire.encodeDisconnect()) }
        runCatching { drain() }
        closeSocket()
    }

    private fun drain() {
        val tcp = socket ?: return
        val inn = input ?: return
        // An SSLSocket cannot half-close; it still drains until the broker closes or the deadline passes.
        runCatching { tcp.shutdownOutput() }
        val buffer = ByteArray(512)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_MS)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) return
            tcp.soTimeout = left.toInt().coerceAtLeast(1)
            if (inn.read(buffer) < 0) return
        }
    }

    /** Close the TCP session without DISCONNECT, so the broker delivers the will. */
    fun drop() {
        closeSocket()
    }

    override fun close() {
        disconnect()
    }

    private fun send(packet: ByteArray) {
        val out = output ?: throw IllegalStateException("mqtt client is not connected")
        synchronized(outLock) {
            out.write(packet)
            out.flush()
        }
    }

    private fun takePacketId(): Int {
        val id = nextPacketId
        nextPacketId = if (nextPacketId == 65535) 1 else nextPacketId + 1
        return id
    }

    private fun completeAck(id: Int, ok: Boolean) {
        val future = synchronized(ackLock) { pendingAcks.remove(id) } ?: return
        future.complete(ok)
    }

    private fun closeSocket() {
        open = false
        connecting?.let { runCatching { it.close() } }
        val tcp = socket
        socket = null
        input = null
        output = null
        runCatching { tcp?.close() }
        synchronized(ackLock) {
            pendingAcks.values.forEach { it.complete(false) }
            pendingAcks.clear()
        }
    }

    private fun openTls(): Socket {
        val context = SSLContext.getInstance("TLS")
        if (tls.trustAll) {
            context.init(null, arrayOf<TrustManager>(trustAll()), SecureRandom())
        } else if (tls.trustStore != null) {
            val keyStore = KeyStore.getInstance(tls.trustStoreType)
            val password = tls.trustStorePassword ?: CharArray(0)
            java.nio.file.Files.newInputStream(tls.trustStore).use { keyStore.load(it, password) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore)
            context.init(null, factory.trustManagers, SecureRandom())
        }
        // SEC-022: without a trust store of its own, the gateway's outbound trust store decides.
        val factory = if (tls.trustAll || tls.trustStore != null) context.socketFactory else org.oip.config.OutboundTls.context("mqtt").socketFactory
        val ssl = factory.createSocket() as SSLSocket
        ssl.tcpNoDelay = true
        if (!tls.trustAll) {
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
        }
        ssl.connect(InetSocketAddress(host, port), connectTimeoutMs)
        ssl.startHandshake()
        return ssl
    }

    private fun connAckReason(body: ByteArray): Int {
        if (body.size < 2) throw IllegalStateException("mqtt CONNACK is truncated")
        return body[1].toInt() and 0xFF
    }

    private fun readRemaining(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        repeat(4) {
            val encoded = input.read()
            if (encoded < 0) throw EOFException("mqtt remaining length is truncated")
            value += (encoded and 0x7F) * multiplier
            if (encoded and 0x80 == 0) return value
            multiplier *= 128
        }
        throw IllegalStateException("mqtt remaining length is malformed")
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(buf, off, length - off)
            if (n < 0) throw EOFException("mqtt packet is truncated")
            off += n
        }
        return buf
    }

    private fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        /** Upper bound on how long [disconnect] reads after DISCONNECT. A broker closes at once, so this is rarely reached. */
        const val DRAIN_MS = 250L
    }
}

internal sealed class MqttPoll {
    data class Published(val message: MqttIncoming) : MqttPoll()
    data object Activity : MqttPoll()
}

/** TLS material for one client. [trustAll] skips certificate and hostname checks. It is for tests. */
class MqttTls(
    val enabled: Boolean = false,
    val trustAll: Boolean = false,
    val trustStore: java.nio.file.Path? = null,
    val trustStorePassword: CharArray? = null,
    val trustStoreType: String = "PKCS12",
)
