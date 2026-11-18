package org.oip.mqtt

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.inputStream

class MqttClientTest {
    @TempDir
    lateinit var tmp: Path

    @Test fun passwordIsOnTheWireAndNotInLogs() {
        val password = "s3cret-pass-mqtt-9f3a"
        val records = CopyOnWriteArrayList<String>()
        val handler = capture(records)
        val logger = Logger.getLogger("org.oip.mqtt")
        logger.addHandler(handler)
        logger.level = Level.ALL
        ScriptedBroker().use { broker ->
            val client = MqttClient(
                host = "127.0.0.1",
                port = broker.port,
                clientId = "edge",
                keepAliveSeconds = 10,
                username = "line-user",
                password = password.encodeToByteArray(),
                will = MqttWill("spBv1.0/STATE/primary", stateDocument(false, 50).encodeToByteArray(), 1, true),
            )
            try {
                client.connect()
                client.subscribe("plant/#")
                client.publish("plant/flow", "42".encodeToByteArray(), retain = true)
                client.ping()
                assertEquals("line-user", broker.connects.single().username)
                assertArrayEquals(password.encodeToByteArray(), broker.connects.single().password)
                val seen = awaitPublish(broker)
                assertEquals("plant/flow", seen.topic)
                assertArrayEquals("42".encodeToByteArray(), seen.payload)
                client.drop()
                await("will") { broker.wills.isNotEmpty() }
                assertEquals("spBv1.0/STATE/primary", broker.wills.single().topic)
                assertTrue(broker.disconnects.get() == 0)
            } finally {
                client.drop()
                logger.removeHandler(handler)
            }
        }
        assertTrue(records.none { it.contains(password) }, "password appeared in mqtt logs")
    }

    @Test fun cleanDisconnectSuppressesTheWill() {
        ScriptedBroker().use { broker ->
            val client = MqttClient(
                "127.0.0.1",
                broker.port,
                "edge",
                will = MqttWill("status", "down".encodeToByteArray(), 0, false),
            )
            client.connect()
            client.disconnect()
            Thread.sleep(100)
            assertEquals(1, broker.disconnects.get())
            assertTrue(broker.wills.isEmpty())
        }
    }

    @Test fun tlsTrustStoreConnects() {
        val store = tmp.resolve("mqtt.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool")
        val gen = ProcessBuilder(
            keytool.toString(), "-genkeypair", "-alias", "mqtt", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1", "-noprompt",
        ).redirectErrorStream(true).start()
        val output = gen.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, gen.waitFor(), output)
        val context = SSLContext.getInstance("TLS")
        val keys = KeyStore.getInstance("PKCS12")
        store.inputStream().use { keys.load(it, "changeit".toCharArray()) }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        managers.init(keys, "changeit".toCharArray())
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(keys)
        context.init(managers.keyManagers, trust.trustManagers, null)
        val server = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val accepted = AtomicBoolean(false)
        val thread = Thread({
            try {
                val socket = server.accept()
                socket.soTimeout = 3_000
                val input = socket.getInputStream()
                val packet = readPacket(input)
                socket.getOutputStream().write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                socket.getOutputStream().flush()
                accepted.set(packet.isNotEmpty())
                Thread.sleep(200)
                socket.close()
            } catch (_: Exception) {
            }
        }, "mqtt-tls")
        thread.isDaemon = true
        thread.start()
        val client = MqttClient(
            host = "localhost",
            port = server.localPort,
            clientId = "tls",
            tls = MqttTls(enabled = true, trustStore = store, trustStorePassword = "changeit".toCharArray()),
        )
        try {
            client.connect()
            assertTrue(client.open)
            await("tls handshake") { accepted.get() }
        } finally {
            client.drop()
            server.close()
            thread.join(1_000)
        }
    }

    private fun capture(records: MutableList<String>) = object : Handler() {
        override fun publish(record: LogRecord) {
            records += record.message.orEmpty() + record.thrown?.message.orEmpty()
        }
        override fun flush() {}
        override fun close() {}
    }

    private fun awaitPublish(broker: ScriptedBroker): ScriptedBroker.Pub {
        await("publish") { broker.publishes.isNotEmpty() }
        return broker.publishes.single()
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError(message)
    }

    private fun readPacket(input: InputStream): ByteArray {
        val first = input.read()
        if (first < 0) throw EOFException()
        var multiplier = 1
        var remaining = 0
        while (true) {
            val encoded = input.read()
            if (encoded < 0) throw EOFException()
            remaining += (encoded and 0x7F) * multiplier
            if (encoded and 0x80 == 0) break
            multiplier *= 128
        }
        val body = ByteArray(remaining)
        var off = 0
        while (off < remaining) {
            val n = input.read(body, off, remaining - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return byteArrayOf(first.toByte()) + body
    }
}

/** Enough of an MQTT 3.1.1 broker to record CONNECT, PUBLISH, and the will. */
internal class ScriptedBroker : AutoCloseable {
    class ConnectView(val username: String?, val password: ByteArray?, val clean: Boolean, val level: Int)
    class Pub(val topic: String, val payload: ByteArray)

    private val server = ServerSocket()
    val connects = CopyOnWriteArrayList<ConnectView>()
    val publishes = CopyOnWriteArrayList<Pub>()
    val wills = CopyOnWriteArrayList<Pub>()
    val disconnects = java.util.concurrent.atomic.AtomicInteger()
    val port: Int
    private val thread: Thread

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", 0))
        port = server.localPort
        thread = Thread({ acceptLoop() }, "scripted-mqtt").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            Thread({ serve(socket) }, "scripted-mqtt-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 3_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        var will: Pub? = null
        var cleanDisconnect = false
        try {
            while (true) {
                val header = input.read()
                if (header < 0) break
                val body = readRemaining(input)
                when (header and 0xF0) {
                    0x10 -> {
                        val view = parseConnect(body)
                        connects += view.connect
                        will = view.will
                        output.write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                        output.flush()
                    }
                    0x30 -> {
                        publishes += parsePublish(header, body)
                        if ((header shr 1) and 0x03 == 1) {
                            val id = publishId(body)
                            output.write(byteArrayOf(0x40, 0x02, (id shr 8).toByte(), id.toByte()))
                            output.flush()
                        }
                    }
                    0x82 -> {
                        val id0 = body[0]
                        val id1 = body[1]
                        output.write(byteArrayOf(0x90.toByte(), 0x03, id0, id1, 0x01))
                        output.flush()
                    }
                    0xC0 -> {
                        output.write(byteArrayOf(0xD0.toByte(), 0x00))
                        output.flush()
                    }
                    0xE0 -> {
                        cleanDisconnect = true
                        disconnects.incrementAndGet()
                        break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            if (!cleanDisconnect && will != null) wills += will
            runCatching { socket.close() }
        }
    }

    private class ConnectParsed(val connect: ConnectView, val will: Pub?)

    private fun parseConnect(body: ByteArray): ConnectParsed {
        var at = 0
        val protocol = MqttWire.readMqttString(body, at)
        at = protocol.second
        val level = body[at].toInt() and 0xFF
        at += 1
        val flags = body[at].toInt() and 0xFF
        at += 3
        if (level == 5) {
            val (length, next) = varint(body, at)
            at = next + length.toInt()
        }
        val client = MqttWire.readMqttString(body, at)
        at = client.second
        var will: Pub? = null
        if (flags and 0x04 != 0) {
            if (level == 5) {
                val (length, next) = varint(body, at)
                at = next + length.toInt()
            }
            val topic = MqttWire.readMqttString(body, at)
            at = topic.second
            val payload = MqttWire.readPrefixed(body, at)
            at = payload.second
            will = Pub(topic.first, payload.first)
        }
        var username: String? = null
        if (flags and 0x80 != 0) {
            val user = MqttWire.readMqttString(body, at)
            username = user.first
            at = user.second
        }
        var password: ByteArray? = null
        if (flags and 0x40 != 0) {
            password = MqttWire.readPrefixed(body, at).first
        }
        return ConnectParsed(ConnectView(username, password, flags and 0x02 != 0, level), will)
    }

    private fun parsePublish(header: Int, body: ByteArray): Pub {
        val topic = MqttWire.readMqttString(body, 0)
        var at = topic.second
        if ((header shr 1) and 0x03 > 0) at += 2
        return Pub(topic.first, body.copyOfRange(at, body.size))
    }

    private fun publishId(body: ByteArray): Int {
        val topic = MqttWire.readMqttString(body, 0)
        return MqttWire.readShort(body, topic.second)
    }

    private fun readRemaining(input: InputStream): ByteArray {
        var multiplier = 1
        var remaining = 0
        while (true) {
            val encoded = input.read()
            if (encoded < 0) throw EOFException()
            remaining += (encoded and 0x7F) * multiplier
            if (encoded and 0x80 == 0) break
            multiplier *= 128
        }
        val body = ByteArray(remaining)
        var off = 0
        while (off < remaining) {
            val n = input.read(body, off, remaining - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return body
    }

    private fun varint(body: ByteArray, start: Int): Pair<Long, Int> {
        var index = start
        var shift = 0
        var value = 0L
        while (true) {
            val byte = body[index].toLong() and 0xFF
            index += 1
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80L == 0L) return value to index
            shift += 7
        }
    }

    override fun close() {
        server.close()
        thread.join(1_000)
    }
}
