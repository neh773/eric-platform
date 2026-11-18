package org.oip.gateway

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Clock drift for the performance reading.
 *
 * The Server Sizing Guide describes drift as a task that was scheduled every 1 s and did not wake
 * on that interval: the host is not delivering CPU. ADR 0010 records the ratio
 * `(actualIntervalMs - 1000) / 1000`, and +/-10% is the head-to-head cutoff.
 *
 * A daemon thread named `clock-drift` sleeps for [intervalMs] and compares the expected wake time
 * with the actual wake time on a monotonic clock. Positive [drift] means the sleep returned late.
 */
class ClockDriftDetector(
    val intervalMs: Long = 1_000,
    private val clock: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    init {
        require(intervalMs in 1..60_000) { "clock drift interval out of range: $intervalMs" }
    }

    private val intervalNanos = intervalMs * 1_000_000L
    private val samples = AtomicLong()
    @Volatile private var drift: Double = 0.0
    @Volatile private var lateMillis: Double = 0.0
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Latest ratio. Zero until the first wakeup. */
    fun drift(): Double = drift

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread({
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    measureOnce()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "clock-drift").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        val t = thread ?: return
        t.interrupt()
        runCatching { t.join(2_000) }
        thread = null
    }

    /**
     * One sleep. Drift is `(actualWake - expectedWake) / interval`.
     * [InterruptedException] from the sleeper is not recorded.
     */
    fun measureOnce() {
        val start = clock()
        val expectedWake = start + intervalNanos
        sleeper(intervalMs)
        val actualWake = clock()
        val lateNanos = actualWake - expectedWake
        lateMillis = lateNanos.toDouble() / 1_000_000.0
        drift = lateNanos.toDouble() / intervalNanos.toDouble()
        samples.incrementAndGet()
    }

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "intervalMs" to intervalMs,
        "drift" to finite(drift),
        "lateMillis" to finite(lateMillis),
        "samples" to samples.get(),
    )

    private fun finite(d: Double) = if (d.isFinite()) d else -1.0

    companion object {
        /** True when the ratio is outside the ADR 0010 +/-10% cutoff. */
        fun outsideCutoff(drift: Double): Boolean = !drift.isFinite() || abs(drift) > 0.10
    }
}
