package org.oip.opcua

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free request counters for an OPC UA client connection.
 *
 * Service calls use [noteRequest], [noteSample], [noteError], and [noteTimeout].
 * A data notification uses [noteNotification]: one atomic add, no allocation, so a
 * subscription callback does not wait on a lock or build a diagnostic object.
 * [averageMillis] is read when something asks for diagnostics, not on that callback.
 */
internal class ResponseStats {
    val requests = AtomicLong()
    val errors = AtomicLong()
    val timeouts = AtomicLong()
    val notifications = AtomicLong()
    private val responseNanos = AtomicLong()
    private val samples = AtomicLong()

    fun noteRequest() {
        requests.incrementAndGet()
    }

    fun noteSample(elapsedNanos: Long) {
        responseNanos.addAndGet(elapsedNanos)
        samples.incrementAndGet()
    }

    fun noteError() {
        errors.incrementAndGet()
    }

    fun noteTimeout() {
        timeouts.incrementAndGet()
    }

    /** One received data change. Not included in [averageMillis]. */
    fun noteNotification() {
        notifications.incrementAndGet()
    }

    fun requestCount(): Long = requests.get()

    /** Service requests plus data notifications, so the figure moves with subscription traffic. */
    fun trafficCount(): Long = requests.get() + notifications.get()

    fun errorCount(): Long = errors.get()

    fun timeoutCount(): Long = timeouts.get()

    /** Mean completed service-call time in milliseconds, or null before the first response. */
    fun averageMillis(): Double? {
        val n = samples.get()
        if (n <= 0L) return null
        return responseNanos.get().toDouble() / n / 1_000_000.0
    }
}
