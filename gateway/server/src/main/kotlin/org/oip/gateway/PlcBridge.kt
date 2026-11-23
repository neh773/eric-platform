package org.oip.gateway

import org.oip.tags.Level
import org.oip.tags.Quality
import org.oip.tags.TagEngine
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * Copies mapped gateway tags into the PLC input image and writes PLC outputs
 * back onto those tags. The scan loop never calls into the JVM. When the
 * engine's heartbeat is late, or the engine is not running, output tags go to
 * their safe values with bad quality.
 *
 * Retain is not part of this preview. TODO: retain.
 */
internal class PlcBridge(
    private val tags: TagEngine,
    private val settings: PlcSettings,
    private val mapping: PlcMapping,
    internal val image: PlcImage,
    private val log: (String) -> Unit = { message -> System.err.println("eric-plc: $message") },
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val thread = Thread(::loop, "eric-plc-bridge")
    private var lossReported = false

    fun start() {
        thread.start()
    }

    override fun close() {
        running.set(false)
        thread.interrupt()
        thread.join(2_000)
        publishSafe()
    }

    private fun loop() {
        val period = settings.publishMillis * 1_000_000L
        val inputs = Array(mapping.inputs.size) { PlcSlot(0, 1, 0, 0) }
        val outputs = Array(mapping.outputs.size) { PlcSlot(0, 1, 0, 0) }
        while (running.get()) {
            val started = System.nanoTime()
            try {
                publishInputs(inputs)
                applyOutputs(outputs)
            } catch (e: Exception) {
                log("bridge cycle failed: ${e.message ?: e.javaClass.simpleName}")
            }
            val rest = period - (System.nanoTime() - started)
            if (rest > 0 && running.get()) {
                try {
                    Thread.sleep(rest / 1_000_000L, (rest % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    if (!running.get()) break
                }
            }
        }
    }

    private fun publishInputs(slots: Array<PlcSlot>) {
        val now = realtimeNs()
        for ((index, point) in mapping.inputs.withIndex()) {
            val sample = tags.read(point.tag)
            val good = sample.quality.level == Level.GOOD
            val stamp = sample.sourceTimestamp ?: sample.timestamp
            slots[index] = PlcSlot(
                typeCode = typeCode(point.type),
                quality = if (good) 0 else 1,
                value = if (good) bitsOf(point.type, sample.value) else 0L,
                sourceNs = if (good) epochNs(stamp) else 0L,
            )
        }
        image.writeInputs(slots.toList())
        image.setGatewayHeartbeat(now)
    }

    private fun applyOutputs(slots: Array<PlcSlot>) {
        val now = realtimeNs()
        val heartbeat = image.plcHeartbeat()
        val fresh = heartbeat != 0L && now - heartbeat in 0..settings.bridgeTimeoutMillis * 1_000_000L
        val runningEngine = image.state() == PlcImage.STATE_RUNNING
        val lost = !fresh || !runningEngine
        if (lost) {
            if (!lossReported) {
                log("plc bridge lost; outputs set to safe values")
                lossReported = true
            }
            publishSafe()
            return
        }
        if (lossReported) {
            log("plc bridge restored")
            lossReported = false
        }
        if (!image.readOutputs(slots)) return
        for ((index, point) in mapping.outputs.withIndex()) {
            val slot = slots[index]
            if (slot.quality == 0) {
                publish(point, valueOf(point.type, slot.value), Quality.GOOD)
            } else {
                publish(point, safeValue(point), Quality.BAD_NOT_CONNECTED)
            }
        }
    }

    /** Values last written to a device (non-memory) output tag, so a device sees a write only when the value changes. */
    private val deviceWrites = HashMap<String, Any?>()

    /**
     * A memory tag takes the scan value directly. Any other tag (a device address, for example an outstation stop
     * coil) gets a normal tag write, and only when the value changes.
     */
    private fun publish(point: PlcPoint, value: Any?, quality: Quality) {
        val result = tags.publishMemory(point.tag, value, quality)
        if (result.quality != Quality.BAD_NOT_WRITABLE || quality.level != Level.GOOD) return
        if (deviceWrites.containsKey(point.tag) && deviceWrites[point.tag] == value) return
        deviceWrites[point.tag] = value
        runCatching { tags.write(point.tag, value) }
            .onFailure { log("write to ${point.tag} failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun publishSafe() {
        for (point in mapping.outputs) {
            val result = tags.publishMemory(point.tag, safeValue(point), Quality.BAD_NOT_CONNECTED)
            // A device output goes to its safe value too, once.
            if (result.quality == Quality.BAD_NOT_WRITABLE && (!deviceWrites.containsKey(point.tag) || deviceWrites[point.tag] != safeValue(point))) {
                deviceWrites[point.tag] = safeValue(point)
                runCatching { tags.write(point.tag, safeValue(point)) }
            }
        }
    }

    companion object {
        fun open(tags: TagEngine, settings: PlcSettings, log: (String) -> Unit = { message -> System.err.println("eric-plc: $message") }): PlcBridge {
            val mapping = PlcMapping.load(Path(settings.mapping))
            val image = PlcImage.open(Path(settings.image), mapping.inputs.size, mapping.outputs.size, settings.publishMillis * 1_000_000L)
            return PlcBridge(tags, settings, mapping, image, log)
        }
    }
}

data class PlcSettings(
    val enabled: Boolean = false,
    val image: String = "",
    val mapping: String = "",
    val publishMillis: Long = 10,
    val bridgeTimeoutMillis: Long = 100,
) {
    init {
        if (enabled) {
            require(image.isNotBlank()) { "plc.image is required" }
            require(mapping.isNotBlank()) { "plc.mapping is required" }
            require(publishMillis in 1..60_000) { "plc.publishMillis out of range: $publishMillis" }
            require(bridgeTimeoutMillis in 1..60_000) { "plc.bridgeTimeoutMillis out of range: $bridgeTimeoutMillis" }
        }
    }

    companion object {
        private val KEYS = setOf("enabled", "image", "mapping", "publishMillis", "bridgeTimeoutMillis")

        fun parse(raw: Map<String, Any?>): PlcSettings {
            val unknown = raw.keys - KEYS
            require(unknown.isEmpty()) { "unknown gateway.yaml plc keys: $unknown" }
            return PlcSettings(
                enabled = when (val value = raw["enabled"]) {
                    null -> false
                    is Boolean -> value
                    else -> throw IllegalArgumentException("plc.enabled must be true or false")
                },
                image = raw["image"] as? String ?: "",
                mapping = raw["mapping"] as? String ?: "",
                publishMillis = number(raw, "publishMillis", 10),
                bridgeTimeoutMillis = number(raw, "bridgeTimeoutMillis", 100),
            )
        }

        private fun number(raw: Map<String, Any?>, key: String, default: Long): Long {
            val value = raw[key] ?: return default
            return (value as? Number)?.toLong() ?: throw IllegalArgumentException("plc.$key must be a number")
        }
    }
}

internal data class PlcMapping(val inputs: List<PlcPoint>, val outputs: List<PlcPoint>) {
    companion object {
        fun load(path: Path): PlcMapping {
            val root = com.fasterxml.jackson.databind.ObjectMapper().readValue(path.readText(), Map::class.java)
            val inputs = points(root, "inputs")
            val outputs = points(root, "outputs")
            require(inputs.isNotEmpty() || outputs.isNotEmpty()) { "mapping has no points" }
            return PlcMapping(inputs, outputs)
        }

        private fun points(root: Map<*, *>, key: String): List<PlcPoint> {
            val rows = root[key] as? List<*> ?: emptyList<Any>()
            return rows.map { row ->
                val map = row as? Map<*, *> ?: throw IllegalArgumentException("mapping $key entry must be an object")
                val type = map["type"] as? String ?: throw IllegalArgumentException("mapping point is missing type")
                typeCode(type)
                PlcPoint(
                    address = map["address"] as? String ?: throw IllegalArgumentException("mapping point is missing address"),
                    tag = map["tag"] as? String ?: throw IllegalArgumentException("mapping point is missing tag"),
                    type = type,
                    safe = map["safe"],
                )
            }
        }
    }
}

internal data class PlcPoint(val address: String, val tag: String, val type: String, val safe: Any?)

internal fun typeCode(type: String): Int = when (type) {
    "BOOL" -> 1
    "INT" -> 2
    "DINT" -> 3
    "REAL" -> 4
    "LREAL" -> 5
    else -> throw IllegalArgumentException("unsupported PLC type $type")
}

internal fun bitsOf(type: String, value: Any?): Long = when (type) {
    "BOOL" -> if (value == true) 1L else 0L
    "INT" -> ((value as? Number)?.toInt() ?: 0).toShort().toLong() and 0xffff
    "DINT" -> ((value as? Number)?.toLong() ?: 0L) and 0xffffffffL
    "REAL" -> java.lang.Float.floatToIntBits((value as? Number)?.toFloat() ?: 0f).toLong() and 0xffffffffL
    "LREAL" -> java.lang.Double.doubleToRawLongBits((value as? Number)?.toDouble() ?: 0.0)
    else -> 0L
}

internal fun valueOf(type: String, bits: Long): Any = when (type) {
    "BOOL" -> bits != 0L
    "INT" -> bits.toShort().toInt()
    "DINT" -> bits.toInt()
    "REAL" -> java.lang.Float.intBitsToFloat(bits.toInt())
    "LREAL" -> java.lang.Double.longBitsToDouble(bits)
    else -> throw IllegalArgumentException("unsupported PLC type $type")
}

internal fun safeValue(point: PlcPoint): Any = when (point.type) {
    "BOOL" -> point.safe == true
    "INT", "DINT" -> (point.safe as? Number)?.toInt() ?: 0
    "REAL" -> (point.safe as? Number)?.toFloat() ?: 0f
    "LREAL" -> (point.safe as? Number)?.toDouble() ?: 0.0
    else -> throw IllegalArgumentException("unsupported PLC type ${point.type}")
}

private fun realtimeNs(): Long = epochNs(Instant.now())

private fun epochNs(instant: Instant): Long = instant.epochSecond * 1_000_000_000L + instant.nano
