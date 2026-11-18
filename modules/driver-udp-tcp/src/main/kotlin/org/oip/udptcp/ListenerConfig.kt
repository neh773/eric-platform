package org.oip.udptcp

import org.oip.config.SchemaRegistry
import java.nio.charset.Charset

const val DEVICE_KIND = "Device"

enum class Protocol { TCP, UDP }

/** [LISTEN] binds the ports and waits for devices; [CONNECT] dials out to [ListenerDeviceConfig.address]. */
enum class Mode { LISTEN, CONNECT }

/**
 * How a byte stream is cut into messages. [CHARACTER] ends a message at the delimiter characters,
 * [FIXED] after a set number of bytes, [PACKET] at each datagram (UDP) or each read (TCP).
 */
enum class DelimiterType { CHARACTER, FIXED, PACKET }

/**
 * One simple-device connection: a barcode scanner, a scale, a label printer's status port. The
 * driver receives delimited text, keeps the last message per port, splits it into fields and
 * counts messages.
 *
 * Setting names follow the public UDP and TCP driver page: ports (several per device), message
 * delimiter type (character based, fixed size, packet based), message delimiter, field count,
 * field delimiter, local address, connect and inactivity timeouts. Delimiters accept the escapes
 * that page lists: `\t`, `\b`, `\n`, `\r`, `\f`. doc source: FEATURES DEV-037
 *
 * The page names no buffer limit. [maxMessageBytes] bounds what one message may hold; a longer
 * one is dropped and counted rather than buffered. [maxClients] bounds the devices one TCP
 * listening port accepts; more are refused and counted.
 */
data class ListenerDeviceConfig(
    val name: String,
    val protocol: Protocol = Protocol.TCP,
    val mode: Mode = Mode.LISTEN,
    val address: String = "0.0.0.0",
    val ports: List<Int>,
    val localAddress: String? = null,
    val description: String = "",
    val delimiterType: DelimiterType = DelimiterType.CHARACTER,
    val messageDelimiter: String = "\\r\\n",
    val fixedLength: Int = 0,
    val fieldCount: Int = 0,
    val fieldDelimiter: String = ",",
    val charset: String = "UTF-8",
    val maxMessageBytes: Int = 4096,
    val maxClients: Int = 8,
    val connectTimeoutMs: Long = 5_000,
    val inactivityTimeoutMs: Long = 0,
    val reconnectMs: Long = 1_000,
    val defaultScanPeriodMs: Long = 1_000,
) {
    init {
        require(name.isNotBlank()) { "device name is required" }
        require(ports.isNotEmpty()) { "at least one port is required" }
        require(ports.all { it in 1..65535 }) { "ports must be 1..65535" }
        require(ports.toSet().size == ports.size) { "ports must not repeat" }
        require(!(protocol == Protocol.UDP && mode == Mode.CONNECT)) { "UDP receives only; use mode listen" }
        require(address.isNotBlank()) { "address is required" }
        if (mode == Mode.CONNECT) require(address != "0.0.0.0" && address != "::") { "connect mode needs the device address" }
        require(maxMessageBytes in 1..65536) { "maxMessageBytes $maxMessageBytes is outside 1..65536" }
        require(maxClients in 1..64) { "maxClients $maxClients is outside 1..64" }
        require(connectTimeoutMs >= 0 && inactivityTimeoutMs >= 0) { "timeouts must not be negative" }
        require(reconnectMs >= 10) { "reconnectMs must be at least 10" }
        require(defaultScanPeriodMs >= 10) { "defaultScanPeriodMs must be at least 10" }
        require(fieldCount in 0..256) { "fieldCount $fieldCount is outside 0..256" }
        require(Charset.isSupported(charset)) { "unknown charset '$charset'" }
        when (delimiterType) {
            DelimiterType.CHARACTER -> {
                val d = delimiterBytes()
                require(d.isNotEmpty()) { "a character delimiter needs messageDelimiter" }
                require(d.size <= 16) { "messageDelimiter is longer than 16 bytes" }
            }
            DelimiterType.FIXED -> require(fixedLength in 1..maxMessageBytes) {
                "fixedLength $fixedLength must be 1..maxMessageBytes ($maxMessageBytes)"
            }
            DelimiterType.PACKET -> Unit
        }
        if (fieldCount > 0) require(unescape(fieldDelimiter).isNotEmpty()) { "fields need a fieldDelimiter" }
    }

    fun delimiterBytes(): ByteArray = unescape(messageDelimiter).toByteArray(Charset.forName(charset))
    fun fieldSeparator(): String = unescape(fieldDelimiter)
}

/** Replace the escapes the public page lists (`\t \b \n \r \f`) and `\\`. Anything else stays as typed. */
fun unescape(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            val mapped = when (text[i + 1]) {
                't' -> '\t'
                'b' -> '\b'
                'n' -> '\n'
                'r' -> '\r'
                'f' -> '\u000C'
                '\\' -> '\\'
                else -> null
            }
            if (mapped != null) {
                out.append(mapped)
                i += 2
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/** Config kind `Device` for module `org.oip.driver-udp-tcp`. doc source: FEATURES DEV-037 */
fun listenerDeviceSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("ports"),
    "properties" to linkedMapOf(
        "name" to mapOf("type" to "string", "minLength" to 1),
        "description" to mapOf("type" to "string", "default" to ""),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "protocol" to mapOf("type" to "string", "enum" to listOf("tcp", "udp"), "default" to "tcp"),
        "mode" to mapOf("type" to "string", "enum" to listOf("listen", "connect"), "default" to "listen"),
        "address" to mapOf("type" to "string", "minLength" to 1, "default" to "0.0.0.0"),
        "ports" to mapOf(
            "type" to "array",
            "minItems" to 1,
            "uniqueItems" to true,
            "items" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535),
        ),
        "localAddress" to mapOf("type" to "string"),
        "delimiterType" to mapOf("type" to "string", "enum" to listOf("character", "fixed", "packet"), "default" to "character"),
        "messageDelimiter" to mapOf("type" to "string", "default" to "\\r\\n"),
        "fixedLength" to mapOf("type" to "integer", "minimum" to 0, "default" to 0),
        "fieldCount" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 256, "default" to 0),
        "fieldDelimiter" to mapOf("type" to "string", "default" to ","),
        "charset" to mapOf("type" to "string", "enum" to listOf("UTF-8", "US-ASCII", "ISO-8859-1"), "default" to "UTF-8"),
        "maxMessageBytes" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65536, "default" to 4096),
        "maxClients" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 64, "default" to 8),
        "connectTimeoutMs" to mapOf("type" to "integer", "minimum" to 0, "default" to 5000),
        "inactivityTimeoutMs" to mapOf("type" to "integer", "minimum" to 0, "default" to 0),
        "reconnectMs" to mapOf("type" to "integer", "minimum" to 10, "default" to 1000),
        "defaultScanPeriodMs" to mapOf("type" to "integer", "minimum" to 10, "default" to 1000),
    ),
)

fun listenerDeviceConfig(resourceName: String, spec: Map<String, Any?>): ListenerDeviceConfig = ListenerDeviceConfig(
    name = resourceName,
    protocol = Protocol.valueOf((spec["protocol"] as? String ?: "tcp").uppercase()),
    mode = Mode.valueOf((spec["mode"] as? String ?: "listen").uppercase()),
    address = spec["address"] as? String ?: "0.0.0.0",
    ports = (spec["ports"] as? List<*>)?.map { (it as? Number)?.toInt() ?: throw IllegalArgumentException("port '$it'") }
        ?: throw IllegalArgumentException("ports are required"),
    localAddress = (spec["localAddress"] as? String)?.takeIf { it.isNotBlank() },
    description = spec["description"] as? String ?: "",
    delimiterType = DelimiterType.valueOf((spec["delimiterType"] as? String ?: "character").uppercase()),
    messageDelimiter = spec["messageDelimiter"] as? String ?: "\\r\\n",
    fixedLength = (spec["fixedLength"] as? Number)?.toInt() ?: 0,
    fieldCount = (spec["fieldCount"] as? Number)?.toInt() ?: 0,
    fieldDelimiter = spec["fieldDelimiter"] as? String ?: ",",
    charset = spec["charset"] as? String ?: "UTF-8",
    maxMessageBytes = (spec["maxMessageBytes"] as? Number)?.toInt() ?: 4096,
    maxClients = (spec["maxClients"] as? Number)?.toInt() ?: 8,
    connectTimeoutMs = (spec["connectTimeoutMs"] as? Number)?.toLong() ?: 5_000,
    inactivityTimeoutMs = (spec["inactivityTimeoutMs"] as? Number)?.toLong() ?: 0,
    reconnectMs = (spec["reconnectMs"] as? Number)?.toLong() ?: 1_000,
    defaultScanPeriodMs = (spec["defaultScanPeriodMs"] as? Number)?.toLong() ?: 1_000,
)

/** Save-time checks the schema cannot express (UDP has no connect mode, fixed length within the buffer, ...). */
fun listenerDeviceErrors(spec: Map<String, Any?>): List<String> = try {
    listenerDeviceConfig("check", spec)
    emptyList()
} catch (e: IllegalArgumentException) {
    listOf(e.message ?: "invalid UDP/TCP device")
}

internal fun Map<String, Any?>.enabled(): Boolean = this["enabled"] as? Boolean ?: true
