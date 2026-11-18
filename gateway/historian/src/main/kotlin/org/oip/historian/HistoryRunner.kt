package org.oip.historian

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Gateway-wide store-and-forward engine settings: the `historian/StoreAndForward/engine` resource
 * (doc source: FEATURES HIS-024). Names follow the reference product's store settings:
 *  - [memoryBufferSize]: samples the in-memory buffer holds before new ones are dropped (and counted);
 *  - [timeThresholdMillis]: longest a sample waits in memory before it is written (Time Threshold);
 *  - [dataThreshold]: samples in memory that trigger a write before the time threshold (Data Threshold);
 *  - [batchSize]: samples per write from memory to disk (Batch Size).
 * Per-provider forward rate, batch size, retry delay and disk cache cap are [ForwardPolicy].
 */
data class StoreAndForwardSettings(
    val memoryBufferSize: Int = HistoryCollector.DEFAULT_QUEUE_CAPACITY,
    val timeThresholdMillis: Long = DEFAULT_TIME_THRESHOLD_MILLIS,
    val dataThreshold: Int = DEFAULT_DATA_THRESHOLD,
    val batchSize: Int = HistoryCollector.MAX_BATCH,
) {
    init {
        require(memoryBufferSize > 0) { "memoryBufferSize must be > 0" }
        require(timeThresholdMillis in MIN_TIME_THRESHOLD_MILLIS..MAX_TIME_THRESHOLD_MILLIS) {
            "timeThresholdMillis must be between $MIN_TIME_THRESHOLD_MILLIS and $MAX_TIME_THRESHOLD_MILLIS"
        }
        require(dataThreshold > 0) { "dataThreshold must be > 0" }
        require(batchSize in 1..ForwardPolicy.MAX_BATCH) { "batchSize must be between 1 and ${ForwardPolicy.MAX_BATCH}" }
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "memoryBufferSize" to memoryBufferSize,
        "timeThresholdMillis" to timeThresholdMillis,
        "dataThreshold" to dataThreshold,
        "batchSize" to batchSize,
    )

    companion object {
        /** The writer's pace before these settings existed. */
        const val DEFAULT_TIME_THRESHOLD_MILLIS = 250L
        const val DEFAULT_DATA_THRESHOLD = 10_000
        const val MIN_TIME_THRESHOLD_MILLIS = 10L
        const val MAX_TIME_THRESHOLD_MILLIS = 3_600_000L
    }
}

/**
 * Production timers for a [HistoryCollector]: one thread calls [HistoryCollector.tick] every [tickMillis] (the
 * granularity of periodic sampling); a writer thread checks every [WRITER_POLL_MILLIS] and calls
 * [HistoryCollector.flush] once [StoreAndForwardSettings.timeThresholdMillis] has passed since the last write or
 * [StoreAndForwardSettings.dataThreshold] samples are waiting, so writes happen in batches off both the tag engine
 * thread and the tick thread. With a [router], a third thread calls [HistoryRouter.forwardDue] every [scanMillis]
 * (each provider then forwards at its own [ForwardPolicy.forwardRateMillis]), so a slow or unreachable database
 * never delays sampling or the local buffer. [maintenance] (for example [EmbeddedHistorian.maintain]) runs on the
 * writer thread every [maintenanceMillis]. [settings] is read on every check, so a change applies without a restart.
 * All sampling and forwarding logic lives in the collector and router, which tests drive directly.
 */
class HistoryRunner(
    private val collector: HistoryCollector,
    private val router: HistoryRouter? = null,
    private val tickMillis: Long = 50,
    private val scanMillis: Long = DEFAULT_SCAN_MILLIS,
    private val settings: () -> StoreAndForwardSettings = { StoreAndForwardSettings() },
    private val maintenance: (() -> Unit)? = null,
    private val maintenanceMillis: Long = DEFAULT_MAINTENANCE_MILLIS,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val log: System.Logger = System.getLogger(HistoryRunner::class.java.name)
    private var ticker: ScheduledExecutorService? = null
    private var writer: ScheduledExecutorService? = null
    private var forwarder: ScheduledExecutorService? = null
    @Volatile private var lastFlushNanos = nanoTime()
    @Volatile private var lastMaintenanceNanos = lastFlushNanos
    @Volatile private var flushes = 0L

    /** Writes the writer thread has started, for tests and diagnostics. */
    val flushCount: Long get() = flushes

    @Synchronized
    fun start(): HistoryRunner {
        if (ticker != null) return this
        lastFlushNanos = nanoTime()
        lastMaintenanceNanos = lastFlushNanos
        ticker = daemon("history-tick").also {
            it.scheduleAtFixedRate({ guarded("tick") { collector.tick() } }, 0, tickMillis, TimeUnit.MILLISECONDS)
        }
        writer = daemon("history-writer").also {
            it.scheduleWithFixedDelay({ guarded("flush") { writerPass() } }, WRITER_POLL_MILLIS, WRITER_POLL_MILLIS, TimeUnit.MILLISECONDS)
        }
        if (router != null) forwarder = daemon("history-forward").also {
            it.scheduleWithFixedDelay({ guarded("forward") { router.forwardDue() } }, scanMillis, scanMillis, TimeUnit.MILLISECONDS)
        }
        return this
    }

    /**
     * One check of the writer thread: apply the buffer settings, flush when the time or data threshold is
     * reached, then run maintenance when it is due. Public so tests can drive it with a fake [nanoTime].
     */
    fun writerPass() {
        val s = settings()
        if (collector.queueCapacity != s.memoryBufferSize || collector.batchSize != s.batchSize) collector.resize(s.memoryBufferSize, s.batchSize)
        val now = nanoTime()
        try {
            if (now - lastFlushNanos >= TimeUnit.MILLISECONDS.toNanos(s.timeThresholdMillis) || collector.pending >= s.dataThreshold) {
                lastFlushNanos = now
                flushes++
                collector.flush()
            }
        } finally {
            if (maintenance != null && now - lastMaintenanceNanos >= TimeUnit.MILLISECONDS.toNanos(maintenanceMillis)) {
                lastMaintenanceNanos = now
                guarded("maintenance") { maintenance.invoke() }
            }
        }
    }

    /** Stop the timers and write what is still queued. */
    @Synchronized
    override fun close() {
        listOfNotNull(ticker, writer, forwarder).forEach { ex ->
            ex.shutdown()
            if (!ex.awaitTermination(5, TimeUnit.SECONDS)) ex.shutdownNow()
        }
        ticker = null
        writer = null
        forwarder = null
        guarded("final flush") { collector.flush() }
    }

    private fun guarded(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "history $what failed: $e")
        }
    }

    private fun daemon(name: String): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }

    companion object {
        /** How often the writer checks the thresholds. */
        const val WRITER_POLL_MILLIS = 10L
        /** How often the forwarder looks for providers whose forward rate has passed (the reference's Engine Scan Rate). */
        const val DEFAULT_SCAN_MILLIS = 100L
        const val DEFAULT_MAINTENANCE_MILLIS = 60_000L
    }
}
