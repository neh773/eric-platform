package org.oip.mqtt

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * One MQTT connection. The socket loop runs on `mqtt-io-<name>`.
 * Tag listeners run on that thread only long enough to hand a sample to the tag engine.
 * Sparkplug births are announced on `mqtt-tags-<name>` so a config write cannot wait on the socket thread.
 *
 * Comms loss matches the Modbus and OPC UA drivers: while the session is up, inbound packets
 * (including a ping faster than the scan) refresh subscribed tags. A stall stops that refresh
 * and the tag engine applies the tag's comms-loss quality after one scan period plus the tag
 * timeout. The TCP session stays up across a short stall so the same session recovers.
 * Keepalive is 1.5 times the configured seconds, which is longer than that budget.
 *
 * Sparkplug B host behaviour (STATE, births, aliases, sequence, rebirth) is in [SparkplugSession].
 * https://sparkplug.eclipse.org/specification/version/3.0/documents/sparkplug-specification-3.0.0.pdf
 */
class MqttConnection(
    val config: MqttConnectionConfig,
    private val secrets: () -> SecretSource?,
    private val onDiscover: (DiscoveredMetric) -> Unit = {},
) : AutoCloseable {
    private val log = Logger.getLogger("org.oip.mqtt")
    val sourceId: String = config.name
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Listener>>()
    private val periods = ConcurrentHashMap<String, Long>()
    private val cache = ConcurrentHashMap<String, Cached>()
    private val datatypes = ConcurrentHashMap<String, Int>()
    private val documents = ConcurrentHashMap<String, Any?>()
    private val plainTopics = ConcurrentHashMap.newKeySet<String>()
    private val clientLock = Any()
    private val session = SparkplugSession(config.reorderTimeoutMs)
    private val tags = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mqtt-tags-${config.name}").apply { isDaemon = true }
    }
    private val connects = AtomicInteger()
    @Volatile private var client: MqttClient? = null
    @Volatile private var running = false
    @Volatile private var sessionUp = false
    @Volatile private var reportedDown = false
    @Volatile private var closing = false
    @Volatile private var sparkplugAttached = false
    @Volatile private var lastInbound = 0L
    @Volatile private var birthTimestamp = 0L
    /** False while this node must not publish host state or commands. Subscriptions still run. */
    @Volatile private var commandsEnabled = true
    @Volatile private var warnedTrust = false
    private var thread: Thread? = null

    fun connectCount(): Int = connects.get()

    fun connected(): Boolean = sessionUp

    /** True when an inbound packet arrived within the fastest subscribed scan period. */
    fun linkFresh(now: Long = System.currentTimeMillis()): Boolean {
        if (!sessionUp) return false
        return now - lastInbound <= aliveWindow()
    }

    fun read(address: String): Sample {
        val cached = cache[address]
        if (!sessionUp && reportedDown) {
            return Sample(cached?.value, Quality.NOT_CONNECTED, cached?.epoch ?: 0L)
        }
        if (cached == null) return Sample(null, Quality.WAITING, 0L)
        return Sample(cached.value, cached.quality, cached.epoch)
    }

    fun write(address: String, value: Any?): WriteResult {
        val now = Instant.now()
        val parsed = try {
            Addresses.parse(address)
        } catch (_: IllegalArgumentException) {
            return WriteResult(address, Quality.CONFIG, now)
        } ?: return WriteResult(address, Quality.CONFIG, now)
        if (!commandsEnabled) return WriteResult(address, Quality.NOT_WRITABLE, now)
        val current = client
        if (current == null || !sessionUp || !current.open) return WriteResult(address, Quality.NOT_CONNECTED, now)
        return try {
            when (parsed) {
                is PlainAddress -> {
                    val bytes = PayloadMap.encode(parsed, value, documents[parsed.topic])
                    current.publish(parsed.topic, bytes, qos = 0, retain = false)
                    if (parsed.encoding == PayloadEncoding.JSON && !parsed.pointer.isNullOrEmpty()) {
                        documents[parsed.topic] = JsonPointer.set(documents[parsed.topic], parsed.pointer, value)
                    }
                }
                is SparkplugAddress -> {
                    if (parsed.metric == "bdSeq") return WriteResult(address, Quality.NOT_WRITABLE, now)
                    val datatype = datatypes[parsed.address()] ?: inferDatatype(value)
                    val bytes = SparkplugSession.commandPayload(parsed.metric, datatype, value, now.toEpochMilli())
                    current.publish(parsed.commandTopic, bytes, qos = 0, retain = false)
                }
            }
            WriteResult(address, Quality.GOOD, Instant.now())
        } catch (_: IllegalArgumentException) {
            WriteResult(address, Quality.CONFIG, Instant.now())
        } catch (_: Exception) {
            WriteResult(address, Quality.NOT_CONNECTED, Instant.now())
        }
    }

    /** Period already attached for [address], or null. A second attach joins those listeners. */
    fun activeSamplingMs(address: String): Long? = periods[address]

    fun attach(
        address: String,
        periodMs: Long,
        onSample: (Any?, Quality, Long) -> Unit,
        onAlive: (Any?, Quality, Long) -> Unit = { _, _, _ -> },
    ): AutoCloseable {
        val parsed = try {
            Addresses.parse(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (parsed == null) {
            onSample(null, Quality.CONFIG, System.currentTimeMillis())
            return AutoCloseable {}
        }
        val period = periodMs.coerceAtLeast(10)
        val listener = Listener(onSample, onAlive)
        val list = listeners.computeIfAbsent(address) { CopyOnWriteArrayList() }
        list.add(listener)
        val previous = periods[address]
        periods[address] = if (previous == null) period else minOf(previous, period)
        when (parsed) {
            is PlainAddress -> synchronized(clientLock) {
                plainTopics.add(parsed.topic)
                client?.subscribe(parsed.topic)
            }
            is SparkplugAddress -> {
                sparkplugAttached = true
                synchronized(clientLock) {
                    val current = client
                    if (current != null) sparkplugFilters().forEach { current.subscribe(it) }
                }
            }
        }
        val cached = cache[address]
        if (!sessionUp && reportedDown) onSample(cached?.value, Quality.NOT_CONNECTED, cached?.epoch ?: System.currentTimeMillis())
        else if (cached != null) onSample(cached.value, cached.quality, cached.epoch)
        else onSample(null, Quality.WAITING, System.currentTimeMillis())
        return AutoCloseable {
            list.remove(listener)
            if (list.isEmpty()) {
                listeners.remove(address, list)
                periods.remove(address)
            }
        }
    }

    /**
     * Allow or stop host-state and command publishes. Subscriptions are unchanged.
     * Turning this off publishes an offline host state when a session is already up.
     */
    fun setPublishing(enabled: Boolean) {
        val was = commandsEnabled
        commandsEnabled = enabled
        if (was == enabled) return
        val opened = client ?: return
        if (!sessionUp || !opened.open) return
        val hostId = config.sparkplugHostId ?: return
        val stamp = if (enabled) birthTimestamp else System.currentTimeMillis()
        runCatching {
            opened.publish(stateTopic(hostId), stateDocument(enabled, stamp).encodeToByteArray(), qos = 1, retain = true)
        }
    }

    fun start() {
        if (running) return
        running = true
        closing = false
        val worker = Thread(this::loop, "mqtt-io-${config.name}")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    fun stop() {
        running = false
        thread?.join(5_000)
        thread = null
    }

    override fun close() {
        stop()
        tags.shutdownNow()
        listeners.clear()
        periods.clear()
    }

    private fun loop() {
        var attempt = 0
        while (running) {
            var opened: MqttClient? = null
            try {
                opened = openSession()
                attempt = 0
                pump(opened)
                if (!running && opened.open) cleanClose(opened)
            } catch (e: Exception) {
                if (running) log.fine("mqtt ${config.name} session ended (${e.javaClass.simpleName})")
            } finally {
                val current = opened
                if (current != null && current.open) current.drop()
                synchronized(clientLock) { if (client === current) client = null }
                sessionUp = false
                noteDown()
            }
            if (running) sleepBackoff(attempt++)
        }
    }

    private fun openSession(): MqttClient {
        val username = resolve(config.usernameRef)
        val password = resolve(config.passwordRef)?.encodeToByteArray()
        val trustPassword = resolve(config.tlsTrustStorePasswordRef)?.toCharArray()
        if (config.insecureTrustAllCertificates && !warnedTrust) {
            warnedTrust = true
            log.warning("mqtt ${config.name} tls certificate checks are disabled")
        }
        val birth = System.currentTimeMillis()
        birthTimestamp = birth
        val hostId = config.sparkplugHostId
        val will = if (!hostId.isNullOrBlank()) {
            MqttWill(stateTopic(hostId), stateDocument(false, birth).encodeToByteArray(), qos = 1, retain = true)
        } else {
            val topic = config.willTopic
            if (topic.isNullOrBlank()) null
            else MqttWill(topic, config.willPayload.encodeToByteArray(), config.willQos, config.willRetain)
        }
        val tls = MqttTls(
            enabled = config.tls,
            trustAll = config.insecureTrustAllCertificates,
            trustStore = config.tlsTrustStore?.let { Path.of(it) },
            trustStorePassword = trustPassword,
        )
        val opened = MqttClient(
            host = config.host,
            port = config.port,
            clientId = config.clientId,
            protocol = config.protocol,
            keepAliveSeconds = config.keepAliveSeconds,
            cleanSession = config.cleanSession,
            username = username,
            password = password,
            will = will,
            tls = tls,
            connectTimeoutMs = config.connectTimeoutMs,
        )
        opened.connect()
        synchronized(clientLock) {
            client = opened
            sparkplugFilters().forEach { opened.subscribe(it) }
            plainTopics.forEach { opened.subscribe(it) }
        }
        if (!hostId.isNullOrBlank() && commandsEnabled) {
            opened.publish(stateTopic(hostId), stateDocument(true, birth).encodeToByteArray(), qos = 1, retain = true)
        }
        noteUp(System.currentTimeMillis())
        connects.incrementAndGet()
        return opened
    }

    private fun pump(opened: MqttClient) {
        var lastPing = 0L
        while (running && opened.open) {
            val now = System.currentTimeMillis()
            val keepaliveMs = config.keepAliveSeconds * 1_500L
            if (config.keepAliveSeconds > 0 && lastInbound != 0L && now - lastInbound > keepaliveMs) {
                throw IllegalStateException("keepalive")
            }
            val pingEvery = (aliveWindow() / 2).coerceAtLeast(20)
            if (now - lastPing >= pingEvery) {
                opened.ping()
                lastPing = now
            }
            when (val polled = opened.poll(20)) {
                null -> Unit
                else -> {
                    lastInbound = System.currentTimeMillis()
                    if (polled is MqttPoll.Published) handle(opened, polled.message)
                    if (linkFresh()) fanoutAlive()
                }
            }
            session.poll(System.currentTimeMillis()).forEach { apply(opened, it) }
        }
    }

    private fun handle(opened: MqttClient, message: MqttIncoming) {
        val now = lastInbound
        if (message.topic.startsWith("spBv1.0/STATE/")) {
            handleState(opened, message.topic, message.payload)
            return
        }
        if (message.topic.startsWith("spBv1.0/")) {
            session.onMessage(message.topic, message.payload, now).forEach { apply(opened, it) }
        }
        deliverPlain(message.topic, message.payload, now)
    }

    private fun handleState(opened: MqttClient, topic: String, payload: ByteArray) {
        if (closing) return
        val hostId = config.sparkplugHostId ?: return
        if (topic != stateTopic(hostId)) return
        val parsed = parseState(payload) ?: return
        if (!parsed.first && sessionUp && commandsEnabled) {
            runCatching {
                opened.publish(topic, stateDocument(true, birthTimestamp).encodeToByteArray(), qos = 1, retain = true)
            }
        }
    }

    private fun apply(opened: MqttClient, effect: SpEffect) {
        when (effect) {
            is SpEffect.Sample -> {
                val quality = if (effect.statusCode == 0L) Quality.GOOD else Quality(
                    effect.statusCode,
                    if (effect.statusCode == SparkplugCodec.STALE) "stale" else null,
                )
                datatypes[effect.address] = effect.datatype
                val list = listeners[effect.address]
                if (list != null) deliver(effect.address, effect.value, quality, effect.timestamp, list)
                else cache[effect.address] = Cached(effect.value, quality, effect.timestamp)
                if (effect.birth && config.autoCreateTags && effect.tagDataType != null && effect.metric.isNotEmpty()) {
                    val metric = DiscoveredMetric(
                        connection = config.name,
                        address = effect.address,
                        path = sparkplugTagPath(config.name, effect.group, effect.edge, effect.device, effect.metric),
                        dataType = effect.tagDataType,
                        scanPeriodMillis = config.defaultScanPeriodMs,
                        writable = effect.writable,
                    )
                    try {
                        tags.execute { onDiscover(metric) }
                    } catch (_: RejectedExecutionException) {
                    }
                }
            }
            is SpEffect.Rebirth -> {
                if (!commandsEnabled) return
                val topic = "spBv1.0/${effect.group}/NCMD/${effect.edge}"
                runCatching { opened.publish(topic, SparkplugSession.rebirthPayload(System.currentTimeMillis())) }
            }
            SpEffect.None -> Unit
        }
    }

    private fun deliverPlain(topic: String, payload: ByteArray, now: Long) {
        for ((address, list) in listeners) {
            val plain = try {
                Addresses.parsePlain(address)
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue
            if (plain.topic != topic) continue
            val decoded = PayloadMap.decode(plain, payload, documents[topic])
            if (decoded.document != null) documents[topic] = decoded.document
            if (decoded.error == null) deliver(address, decoded.value, Quality.GOOD, now, list)
            else deliver(address, cache[address]?.value, Quality(0x80890000L, decoded.error), now, list)
        }
    }

    private fun deliver(address: String, value: Any?, quality: Quality, epoch: Long, list: List<Listener>) {
        cache[address] = Cached(value, quality, epoch)
        for (listener in list) {
            try {
                listener.onSample(value, quality, epoch)
            } catch (t: Throwable) {
                log.log(Level.FINE, "mqtt ${config.name} listener", t)
            }
        }
    }

    private fun fanoutAlive() {
        for ((address, list) in listeners) {
            val cached = cache[address] ?: continue
            if (cached.quality.statusCode == Quality.WAITING.statusCode) continue
            for (listener in list) {
                try {
                    listener.onAlive(cached.value, cached.quality, cached.epoch)
                } catch (t: Throwable) {
                    log.log(Level.FINE, "mqtt ${config.name} listener", t)
                }
            }
        }
    }

    private fun noteUp(now: Long) {
        sessionUp = true
        reportedDown = false
        lastInbound = now
    }

    private fun noteDown() {
        sessionUp = false
        if (reportedDown) return
        reportedDown = true
        val now = System.currentTimeMillis()
        for ((address, list) in listeners) {
            val cached = cache[address]
            for (listener in list) {
                try {
                    listener.onSample(cached?.value, Quality.NOT_CONNECTED, cached?.epoch ?: now)
                } catch (t: Throwable) {
                    log.log(Level.FINE, "mqtt ${config.name} listener", t)
                }
            }
        }
    }

    private fun cleanClose(opened: MqttClient) {
        closing = true
        val hostId = config.sparkplugHostId
        if (!hostId.isNullOrBlank()) {
            runCatching {
                opened.publish(stateTopic(hostId), stateDocument(false, System.currentTimeMillis()).encodeToByteArray(), qos = 1, retain = true)
            }
        }
        opened.disconnect()
    }

    private fun resolve(reference: String?): String? {
        if (reference.isNullOrBlank()) return null
        val name = secretName(reference) ?: throw IllegalArgumentException("mqtt ${config.name} secret reference rejected")
        val source = secrets() ?: run {
            log.warning("mqtt ${config.name} secret $name is not available")
            throw IllegalStateException("mqtt ${config.name} secret $name is not available")
        }
        return try {
            source.resolve(reference)
        } catch (_: Exception) {
            log.warning("mqtt ${config.name} secret $name is not available")
            throw IllegalStateException("mqtt ${config.name} secret $name is not available")
        }
    }

    private fun sparkplugFilters(): List<String> {
        val host = !config.sparkplugHostId.isNullOrBlank()
        if (!host && !config.autoCreateTags && !sparkplugAttached) return emptyList()
        val group = config.sparkplugGroup?.takeIf { it.isNotBlank() }
        if (group == null) return listOf("spBv1.0/#")
        val filters = mutableListOf("spBv1.0/$group/#")
        if (host) filters += stateTopic(config.sparkplugHostId!!)
        return filters
    }

    private fun aliveWindow(): Long {
        var min = Long.MAX_VALUE
        for (period in periods.values) if (period < min) min = period
        if (min == Long.MAX_VALUE) min = config.defaultScanPeriodMs
        return min.coerceAtLeast(40)
    }

    private fun sleepBackoff(attempt: Int) {
        var delay = config.reconnectInitialMs
        repeat(attempt.coerceAtMost(16)) {
            if (delay >= config.reconnectMaxMs / 2) {
                delay = config.reconnectMaxMs
                return@repeat
            }
            delay *= 2
        }
        delay = delay.coerceAtMost(config.reconnectMaxMs)
        val end = System.nanoTime() + delay * 1_000_000L
        while (running && System.nanoTime() < end) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private class Listener(
        val onSample: (Any?, Quality, Long) -> Unit,
        val onAlive: (Any?, Quality, Long) -> Unit,
    )

    private class Cached(val value: Any?, val quality: Quality, val epoch: Long)
}

class Quality(val statusCode: Long, val diagnostic: String? = null) {
    companion object {
        val GOOD = Quality(0)
        val NOT_CONNECTED = Quality(0x808A0000, "not connected")
        val WAITING = Quality(0x80320000)
        val CONFIG = Quality(0x80890000, "configuration")
        val NOT_WRITABLE = Quality(0x803B0000, "not writable")
    }
}

class Sample(val value: Any?, val quality: Quality, val epochMillis: Long)

class WriteResult(val address: String, val quality: Quality, val timestamp: Instant)

class DiscoveredMetric(
    val connection: String,
    val address: String,
    val path: String,
    val dataType: String,
    val scanPeriodMillis: Long,
    val writable: Boolean,
) {
    fun resourceName(): String = sparkplugResourceName(connection, address)
}

fun interface SecretSource {
    fun resolve(reference: String): String
}

fun sparkplugTagPath(connection: String, group: String, edge: String, device: String, metric: String): String {
    val parts = ArrayList<String>(8)
    parts += "mqtt"
    parts += sanitizeSegment(connection)
    parts += sanitizeSegment(group)
    parts += sanitizeSegment(edge)
    if (device.isNotEmpty()) parts += sanitizeSegment(device)
    metric.split('/').forEach { parts += sanitizeSegment(it) }
    return parts.joinToString("/")
}

fun sparkplugResourceName(connection: String, address: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest((connection + "\u0000" + address).toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(20)
    for (i in 0 until 10) hex.append("%02x".format(digest[i]))
    return "m$hex"
}

internal fun stateTopic(hostId: String) = "spBv1.0/STATE/$hostId"

internal fun stateDocument(online: Boolean, timestamp: Long): String =
    """{"online":${if (online) "true" else "false"},"timestamp":$timestamp}"""

internal fun parseState(payload: ByteArray): Pair<Boolean, Long>? {
    val parsed = try {
        JsonCodec.parse(String(payload, Charsets.UTF_8))
    } catch (_: IllegalArgumentException) {
        return null
    } as? Map<*, *> ?: return null
    val online = parsed["online"] as? Boolean ?: return null
    val timestamp = (parsed["timestamp"] as? Number)?.toLong() ?: return null
    return online to timestamp
}

internal fun secretName(reference: String): String? {
    if (!reference.startsWith("secret://")) return null
    val name = reference.removePrefix("secret://")
    if (!Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$").matches(name)) return null
    return name
}

private fun sanitizeSegment(segment: String): String {
    if (segment.isEmpty()) return "_"
    val cleaned = segment.map { ch ->
        if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == ' ') ch else '_'
    }.joinToString("")
    return cleaned.ifEmpty { "_" }
}

private fun inferDatatype(value: Any?): Int = when (value) {
    is Boolean -> SparkplugCodec.BOOLEAN
    is Float -> SparkplugCodec.FLOAT
    is Double -> SparkplugCodec.DOUBLE
    is Long -> SparkplugCodec.INT64
    is Byte, is Short, is Int -> SparkplugCodec.INT32
    is String -> SparkplugCodec.STRING
    null -> SparkplugCodec.STRING
    else -> throw IllegalArgumentException("value")
}
