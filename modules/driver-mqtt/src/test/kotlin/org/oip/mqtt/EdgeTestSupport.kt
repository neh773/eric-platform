package org.oip.mqtt

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process MQTT 3.1.1 broker for tests that cannot start a container. It routes PUBLISH to matching
 * subscriptions (delivered at QoS 0), keeps retained messages, answers QoS 1 with PUBACK, and sends the
 * will when a client goes away without DISCONNECT. Every routed publish is recorded in [log].
 */
internal class RoutingBroker : AutoCloseable {
    class Routed(val topic: String, val payload: ByteArray, val qos: Int, val retain: Boolean, val fromWill: Boolean)

    private class Client(val socket: Socket, val out: OutputStream) {
        val filters = CopyOnWriteArrayList<String>()
        fun send(packet: ByteArray) = synchronized(out) {
            out.write(packet)
            out.flush()
        }
    }

    private val server = ServerSocket()
    private val clients = CopyOnWriteArrayList<Client>()
    private val retained = ConcurrentHashMap<String, ByteArray>()
    val log = CopyOnWriteArrayList<Routed>()
    val port: Int

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", 0))
        port = server.localPort
        Thread({ accept() }, "routing-mqtt").apply { isDaemon = true; start() }
    }

    fun published(topic: String): List<Routed> = log.filter { it.topic == topic }

    /** Drops every client socket without DISCONNECT, so their wills are sent. */
    fun dropClients() {
        clients.forEach { runCatching { it.socket.close() } }
    }

    private fun accept() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            Thread({ serve(socket) }, "routing-mqtt-client").apply { isDaemon = true; start() }
        }
    }

    private fun serve(socket: Socket) {
        val client = Client(socket, socket.getOutputStream())
        val input = socket.getInputStream()
        var will: Routed? = null
        var clean = false
        clients += client
        try {
            while (true) {
                val (header, body) = MqttWire.readPacket(input) ?: break
                when (header and 0xF0) {
                    0x10 -> {
                        will = parseWill(body)
                        client.send(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                    }
                    0x30 -> {
                        val qos = (header shr 1) and 0x03
                        val message = MqttWire.decodePublish(MqttProtocol.MQTT_3_1_1, header, body)
                        if (qos == 1) MqttWire.publishPacketId(header, body)?.let { client.send(MqttWire.encodePubAck(it)) }
                        route(Routed(message.topic, message.payload, qos, message.retain, fromWill = false))
                    }
                    0x80 -> {
                        val id = MqttWire.readShort(body, 0)
                        var at = 2
                        val granted = ArrayList<Byte>()
                        while (at < body.size) {
                            val (filter, next) = MqttWire.readMqttString(body, at)
                            at = next + 1
                            client.filters += filter
                            granted += 0
                        }
                        client.send(MqttWire.packet(0x90, byteArrayOf((id shr 8).toByte(), id.toByte()) + granted.toByteArray()))
                        for ((topic, payload) in retained) {
                            if (client.filters.any { matches(it, topic) }) {
                                client.send(MqttWire.encodePublish(MqttProtocol.MQTT_3_1_1, topic, payload, 0, true, 0))
                            }
                        }
                    }
                    0xC0 -> client.send(byteArrayOf(0xD0.toByte(), 0x00))
                    0xE0 -> {
                        clean = true
                        break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            clients -= client
            runCatching { socket.close() }
            val pending = will
            if (!clean && pending != null) route(pending)
        }
    }

    private fun route(message: Routed) {
        log += message
        if (message.retain) {
            if (message.payload.isEmpty()) retained.remove(message.topic) else retained[message.topic] = message.payload
        }
        val packet = MqttWire.encodePublish(MqttProtocol.MQTT_3_1_1, message.topic, message.payload, 0, false, 0)
        for (client in clients) {
            if (client.filters.none { matches(it, message.topic) }) continue
            runCatching { client.send(packet) }
        }
    }

    private fun parseWill(body: ByteArray): Routed? {
        var at = MqttWire.readMqttString(body, 0).second
        at += 1
        val flags = body[at].toInt() and 0xFF
        at += 3
        at = MqttWire.readMqttString(body, at).second
        if (flags and 0x04 == 0) return null
        val (topic, next) = MqttWire.readMqttString(body, at)
        val payload = MqttWire.readPrefixed(body, next).first
        return Routed(topic, payload, (flags shr 3) and 0x03, flags and 0x20 != 0, fromWill = true)
    }

    override fun close() {
        runCatching { server.close() }
        clients.forEach { runCatching { it.socket.close() } }
    }

    companion object {
        fun matches(filter: String, topic: String): Boolean {
            val f = filter.split('/')
            val t = topic.split('/')
            for (i in f.indices) {
                if (f[i] == "#") return true
                if (i >= t.size) return false
                if (f[i] != "+" && f[i] != t[i]) return false
            }
            return f.size == t.size
        }
    }
}

/** Tag feed backed by a map. [set] notifies listeners on the calling thread, like a tag-engine writer. */
internal class FakeFeed : EdgeTagFeed {
    private val values = ConcurrentHashMap<String, EdgeTagValue>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(EdgeTagValue) -> Unit>>()
    val writes = CopyOnWriteArrayList<Pair<String, Any?>>()
    val listenerThreads = CopyOnWriteArrayList<String>()
    @Volatile var acceptWrites = true
    /** When set, [write] blocks until it opens (to fill the edge node's write queue). */
    @Volatile var writeGate: java.util.concurrent.CountDownLatch? = null

    fun set(path: String, value: Any?, good: Boolean = true, dataType: String? = null) {
        val next = EdgeTagValue(value, good, System.currentTimeMillis(), dataType ?: values[path]?.dataType)
        values[path] = next
        listeners[path]?.forEach { it(next) }
    }

    override fun read(path: String): EdgeTagValue = values[path] ?: EdgeTagValue.MISSING

    override fun subscribe(path: String, listener: (EdgeTagValue) -> Unit): AutoCloseable {
        val list = listeners.computeIfAbsent(path) { CopyOnWriteArrayList() }
        val wrapped: (EdgeTagValue) -> Unit = { value ->
            listenerThreads += Thread.currentThread().name
            listener(value)
        }
        list += wrapped
        return AutoCloseable { list -= wrapped }
    }

    override fun write(path: String, value: Any?): Boolean {
        writeGate?.await()
        writes += path to value
        if (!acceptWrites) return false
        set(path, value)
        return true
    }

    fun listenerCount(): Int = listeners.values.sumOf { it.size }
}

internal fun awaitTrue(message: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
    val end = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < end) {
        if (cond()) return
        Thread.sleep(10)
    }
    throw AssertionError(message)
}
