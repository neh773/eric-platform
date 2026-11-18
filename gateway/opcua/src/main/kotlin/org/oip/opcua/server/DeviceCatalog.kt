package org.oip.opcua.server

import java.time.Instant

/**
 * Configured devices and connections the gateway OPC UA server exposes under `Devices`.
 *
 * the reference platform's OPC UA server exposes each connected device so a third-party client can browse, read,
 * and write it. A connection (an outbound OPC UA session, for example) is listed the same way: it is
 * one more configured driver endpoint.
 *
 * [devices] and [device] are the live configuration. A device added or removed at runtime is visible
 * on the next browse without restarting the server.
 */
interface DeviceCatalog {
    /** Currently running devices and connections. Names are unique. */
    fun devices(): List<DeviceEndpoint>

    /** The endpoint [name] names now, or null when it is not configured. */
    fun device(name: String): DeviceEndpoint?

    companion object {
        val EMPTY: DeviceCatalog = object : DeviceCatalog {
            override fun devices(): List<DeviceEndpoint> = emptyList()
            override fun device(name: String): DeviceEndpoint? = null
        }
    }
}

/**
 * One device or connection. [name] is the node-id segment under `Devices`. [browseName] is what a
 * client sees. They differ only when two drivers were given the same name.
 */
interface DeviceEndpoint {
    val name: String
    val browseName: String get() = name
    /** Short driver id (`modbus`, `opcua`, `mqtt`). */
    val driver: String

    /** Children of [nodeId]. An empty id is the device root. */
    fun browse(nodeId: String): List<DeviceItem>

    /**
     * The item [nodeId], including an address a client names directly that [browse] does not list
     * (a Modbus address outside the browsed window). Null when [nodeId] is not an item of this device.
     */
    fun describe(nodeId: String): DeviceItem?

    /** One sample per id, in order. Failures are bad status codes, not thrown. */
    fun read(nodeIds: List<String>): List<DeviceReading>

    /**
     * Write one item through the driver connection. The result is an OPC UA status code (upper 16 bits).
     * A code other than Good means the device value was not changed by this call.
     */
    fun write(nodeId: String, value: Any?): Long

    /**
     * Rate, in milliseconds, this driver will sample [nodeId] at.
     *
     * The result is never faster than the driver can poll or subscribe. When the address is
     * already being polled — an OPC tag, or another monitored item — the result is that poll's
     * rate, so the device is not asked a second time. A request of zero or less means the
     * driver's fastest rate.
     *
     * The gateway OPC UA server returns this as the monitored item's revised sampling interval.
     * Clients subscribe; they do not poll.
     */
    fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double

    /**
     * Sample [nodeId] through the driver's existing poll or subscription.
     *
     * The listener runs on the driver thread and must return quickly. Closing the handle drops
     * this listener. The device poll stops when no tag and no monitored item still wants the
     * address. Comms loss arrives as a sample with a bad status code; a later good sample is
     * recovery.
     *
     * [samplingIntervalMs] is a value [revisedSamplingIntervalMs] would return. An existing poll
     * of this address is joined even when the number differs.
     */
    fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable
}

/**
 * Sampling interval a driver will actually run.
 *
 * An existing poll wins, so a second subscriber does not open a second device request.
 * Otherwise the request is raised to [minimumMs]. Zero or a negative request (OPC UA's
 * "fastest practical rate") is [minimumMs].
 */
fun revisedDriverInterval(requestedMs: Double, existingMs: Double? = null, minimumMs: Double = 10.0): Double {
    if (existingMs != null && existingMs > 0.0 && !existingMs.isNaN()) return existingMs
    if (requestedMs.isNaN() || requestedMs <= 0.0) return minimumMs
    return requestedMs.coerceAtLeast(minimumMs)
}

data class DeviceItem(
    val nodeId: String,
    val browseName: String,
    /** False for a folder or object the client can browse into. */
    val variable: Boolean,
    val readable: Boolean,
    val writable: Boolean,
)

data class DeviceReading(
    val value: Any?,
    /** OPC UA status code, upper 16 bits. */
    val statusCode: Long,
    val sourceTimestamp: Instant?,
    val serverTimestamp: Instant,
)
