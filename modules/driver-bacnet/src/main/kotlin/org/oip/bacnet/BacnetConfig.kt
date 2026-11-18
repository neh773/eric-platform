package org.oip.bacnet

import org.oip.config.SchemaRegistry
import java.net.InetAddress

const val LOCAL_DEVICE_KIND = "LocalDevice"
const val REMOTE_DEVICE_KIND = "RemoteDevice"

/**
 * The gateway's own BACnet device (DEV-012). Every remote device talks through one local device, which owns
 * the UDP socket, the device instance and name other devices see, and the vendor id it announces. A remote
 * device does not communicate until the local device it names is configured and running.
 *
 * [apduTimeoutMs] and [apduRetries] are the local device's APDU timeout and number of retries; every confirmed
 * request is sent at most `1 + apduRetries` times. [maxOutstanding] bounds the confirmed requests in flight
 * at once and [maxQueued] the requests waiting behind them; a request beyond both fails at once.
 * doc source: FEATURES DEV-012
 */
data class LocalDeviceConfig(
    val name: String,
    val deviceInstance: Int,
    val deviceName: String,
    val vendorId: Int = 0,
    val vendorName: String = "ERIC",
    val modelName: String = "Gateway",
    val description: String = "",
    val bindAddress: String = "0.0.0.0",
    val port: Int = BacnetFrames.DEFAULT_PORT,
    val broadcastAddress: String = "255.255.255.255",
    val apduTimeoutMs: Long = 3_000,
    val apduRetries: Int = 3,
    val maxApduLength: Int = 1476,
    val maxOutstanding: Int = 32,
    val maxQueued: Int = 256,
) {
    init {
        require(name.isNotBlank()) { "local device name is required" }
        require(deviceInstance in 0 until ObjectId.MAX_INSTANCE) { "deviceInstance $deviceInstance is outside 0..${ObjectId.MAX_INSTANCE - 1}" }
        require(deviceName.isNotBlank()) { "deviceName is required" }
        require(vendorId in 0..65535) { "vendorId $vendorId is outside 0..65535" }
        require(port in 0..65535) { "port $port is outside 0..65535" }
        require(apduTimeoutMs in 10..120_000) { "apduTimeoutMs $apduTimeoutMs is outside 10..120000" }
        require(apduRetries in 0..10) { "apduRetries $apduRetries is outside 0..10" }
        require(maxApduLength in setOf(50, 128, 206, 480, 1024, 1476)) { "maxApduLength must be 50, 128, 206, 480, 1024 or 1476" }
        require(maxOutstanding in 1..255) { "maxOutstanding $maxOutstanding is outside 1..255" }
        require(maxQueued in 0..10_000) { "maxQueued $maxQueued is outside 0..10000" }
        require(isIpLiteral(bindAddress)) { "bindAddress '$bindAddress' is not an IP address" }
        require(isIpLiteral(broadcastAddress)) { "broadcastAddress '$broadcastAddress' is not an IP address" }
    }
}

/**
 * A remote BACnet/IP device (DEV-011). It is found by its device instance: a Who-Is limited to that
 * instance goes to [host] when it is set, otherwise to the local device's broadcast address, and the
 * I-Am answer gives the address (including a router's network number and MAC) used from then on.
 * With [host] set, a device that does not answer Who-Is is still read directly.
 *
 * Once found, the device object's object list is read (whole, or one array element at a time when the
 * whole list does not fit an unsegmented reply) and each object's name, which is the browse tree.
 * Subscribed properties are read with ReadPropertyMultiple, at most [maxPropertiesPerRequest] per
 * request, or one ReadProperty at a time when the device does not support it.
 * Writes to a commandable present value use [writePriority] unless the address names a priority.
 * doc source: FEATURES DEV-011, DEV-038
 */
data class RemoteDeviceConfig(
    val name: String,
    val localDevice: String,
    val deviceInstance: Int,
    val host: String? = null,
    val port: Int = BacnetFrames.DEFAULT_PORT,
    val description: String = "",
    val writePriority: Int = 16,
    val defaultScanPeriodMs: Long = 1_000,
    val maxPropertiesPerRequest: Int = 20,
    val reconnectMs: Long = 5_000,
    val browseOnConnect: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "remote device name is required" }
        require(localDevice.isNotBlank()) { "localDevice is required: configure a local device first" }
        require(deviceInstance in 0 until ObjectId.MAX_INSTANCE) { "deviceInstance $deviceInstance is outside 0..${ObjectId.MAX_INSTANCE - 1}" }
        require(port in 1..65535) { "port $port is outside 1..65535" }
        require(writePriority in 1..16) { "writePriority $writePriority is outside 1..16" }
        require(defaultScanPeriodMs >= 10) { "defaultScanPeriodMs must be at least 10" }
        require(maxPropertiesPerRequest in 1..200) { "maxPropertiesPerRequest $maxPropertiesPerRequest is outside 1..200" }
        require(reconnectMs >= 10) { "reconnectMs must be at least 10" }
        if (host != null) require(isIpLiteral(host)) { "host '$host' is not an IP address" }
    }
}

private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

/** BACnet/IP (Annex J) addresses are IPv4. Names are refused so a save never waits on DNS. */
internal fun isIpLiteral(text: String): Boolean {
    val m = IPV4.matchEntire(text) ?: return false
    return m.groupValues.drop(1).all { it.toInt() in 0..255 }
}

internal fun ipv4(text: String): InetAddress = InetAddress.getByAddress(text.split('.').map { it.toInt().toByte() }.toByteArray())

/** Config kind `LocalDevice` for module `org.oip.driver-bacnet`. doc source: FEATURES DEV-012 */
fun localDeviceSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("deviceInstance", "deviceName"),
    "properties" to linkedMapOf(
        "description" to mapOf("type" to "string", "default" to ""),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "deviceInstance" to mapOf("type" to "integer", "minimum" to 0, "maximum" to ObjectId.MAX_INSTANCE - 1),
        "deviceName" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 150),
        "vendorId" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 65535, "default" to 0),
        "vendorName" to mapOf("type" to "string", "default" to "ERIC"),
        "modelName" to mapOf("type" to "string", "default" to "Gateway"),
        "bindAddress" to mapOf("type" to "string", "default" to "0.0.0.0", "pattern" to IPV4.pattern),
        "port" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 65535, "default" to BacnetFrames.DEFAULT_PORT),
        "broadcastAddress" to mapOf("type" to "string", "default" to "255.255.255.255", "pattern" to IPV4.pattern),
        "apduTimeoutMs" to mapOf("type" to "integer", "minimum" to 10, "maximum" to 120000, "default" to 3000),
        "apduRetries" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 10, "default" to 3),
        "maxApduLength" to mapOf("type" to "integer", "enum" to listOf(50, 128, 206, 480, 1024, 1476), "default" to 1476),
        "maxOutstanding" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 255, "default" to 32),
        "maxQueued" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 10000, "default" to 256),
    ),
)

/** Config kind `RemoteDevice` for module `org.oip.driver-bacnet`. doc source: FEATURES DEV-011 */
fun remoteDeviceSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("localDevice", "deviceInstance"),
    "properties" to linkedMapOf(
        "description" to mapOf("type" to "string", "default" to ""),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "localDevice" to mapOf("type" to "string", "minLength" to 1),
        "deviceInstance" to mapOf("type" to "integer", "minimum" to 0, "maximum" to ObjectId.MAX_INSTANCE - 1),
        "host" to mapOf("type" to "string", "pattern" to IPV4.pattern),
        "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535, "default" to BacnetFrames.DEFAULT_PORT),
        "writePriority" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 16, "default" to 16),
        "defaultScanPeriodMs" to mapOf("type" to "integer", "minimum" to 10, "default" to 1000),
        "maxPropertiesPerRequest" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 200, "default" to 20),
        "reconnectMs" to mapOf("type" to "integer", "minimum" to 10, "default" to 5000),
        "browseOnConnect" to mapOf("type" to "boolean", "default" to true),
    ),
)

fun localDeviceConfig(resourceName: String, spec: Map<String, Any?>): LocalDeviceConfig = LocalDeviceConfig(
    name = resourceName,
    deviceInstance = spec.int("deviceInstance") ?: throw IllegalArgumentException("deviceInstance is required"),
    deviceName = spec.str("deviceName") ?: throw IllegalArgumentException("deviceName is required"),
    vendorId = spec.int("vendorId") ?: 0,
    vendorName = spec.str("vendorName") ?: "ERIC",
    modelName = spec.str("modelName") ?: "Gateway",
    description = spec.str("description") ?: "",
    bindAddress = spec.str("bindAddress") ?: "0.0.0.0",
    port = spec.int("port") ?: BacnetFrames.DEFAULT_PORT,
    broadcastAddress = spec.str("broadcastAddress") ?: "255.255.255.255",
    apduTimeoutMs = spec.long("apduTimeoutMs") ?: 3_000,
    apduRetries = spec.int("apduRetries") ?: 3,
    maxApduLength = spec.int("maxApduLength") ?: 1476,
    maxOutstanding = spec.int("maxOutstanding") ?: 32,
    maxQueued = spec.int("maxQueued") ?: 256,
)

fun remoteDeviceConfig(resourceName: String, spec: Map<String, Any?>): RemoteDeviceConfig = RemoteDeviceConfig(
    name = resourceName,
    localDevice = spec.str("localDevice") ?: throw IllegalArgumentException("localDevice is required"),
    deviceInstance = spec.int("deviceInstance") ?: throw IllegalArgumentException("deviceInstance is required"),
    host = spec.str("host")?.takeIf { it.isNotBlank() },
    port = spec.int("port") ?: BacnetFrames.DEFAULT_PORT,
    description = spec.str("description") ?: "",
    writePriority = spec.int("writePriority") ?: 16,
    defaultScanPeriodMs = spec.long("defaultScanPeriodMs") ?: 1_000,
    maxPropertiesPerRequest = spec.int("maxPropertiesPerRequest") ?: 20,
    reconnectMs = spec.long("reconnectMs") ?: 5_000,
    browseOnConnect = spec.bool("browseOnConnect") ?: true,
)

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
internal fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
internal fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
internal fun Map<String, Any?>.enabled(): Boolean = this["enabled"] as? Boolean ?: true
