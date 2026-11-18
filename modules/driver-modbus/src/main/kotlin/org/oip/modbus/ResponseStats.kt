package org.oip.modbus

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free request counters for a device connection.
 *
 * The poll path calls [noteRequest], [noteSample], [noteError], and [noteTimeout] only.
 * Each is one atomic add and allocates nothing, so the counters do not stall a scan.
 * [averageMillis] is read from the diagnostics thread, not from the poll.
 * The figures describe issued requests and arrived responses. Reading them is not part of the request.
 */
internal class ResponseStats {
    val requests = AtomicLong()
    val errors = AtomicLong()
    val timeouts = AtomicLong()
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

    fun requestCount(): Long = requests.get()

    fun errorCount(): Long = errors.get()

    fun timeoutCount(): Long = timeouts.get()

    /** Mean completed-response time in milliseconds, or null before the first response. */
    fun averageMillis(): Double? {
        val n = samples.get()
        if (n <= 0L) return null
        return responseNanos.get().toDouble() / n / 1_000_000.0
    }
}
