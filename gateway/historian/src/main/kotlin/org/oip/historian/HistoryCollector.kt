package org.oip.historian

import org.oip.tags.DeadbandMode
import org.oip.tags.Quality
import org.oip.tags.QualifiedValue
import org.oip.tags.TagEngine
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Collects tag history per [HistorySettings] into a [HistorySink]. Sample modes and the store rule are
 * documented on [HistorySampleMode] and [HistorySettings].
 *
 * Threading. Nothing here owns a thread; callers drive it:
 *  - The on-change listener runs on the tag engine's writer thread, which must not block
 *    ([TagEngine] KDoc). It only compares against the last stored value held in memory and offers the
 *    sample to a bounded in-memory queue. No SQLite I/O happens on that thread.
 *  - [tick] evaluates periodic tags and the max-time-between-samples rule as of a given instant. It reads
 *    tag values and enqueues; it does no I/O. Tests call it with explicit instants.
 *  - [flush] drains the queue and writes it to the sink as one batch. A batch the sink fails to write
 *    (for example SQLITE_BUSY) is held, not dropped, and written first on the next flush, so order is kept.
 * [HistoryRunner] calls [tick] and [flush] from real timer threads in production.
 *
 * When the queue is full a sample is dropped and counted in [dropped]. A dropped sample does not move the
 * deadband baseline, so the next evaluation compares against the last sample that was actually queued.
 */
class HistoryCollector(
    private val engine: TagEngine,
    private val sink: HistorySink,
    private val clock: Clock = Clock.systemUTC(),
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    /**
     * Where a batch goes once the sink has failed [divertAfterFailures] flushes in a row (for example the
     * store-and-forward quarantine, [HistoryRouter.divert]). Null keeps holding the batch.
     */
    private val divert: ((List<HistorySample>, Exception) -> Unit)? = null,
    private val divertAfterFailures: Int = DEFAULT_DIVERT_AFTER_FAILURES,
) : AutoCloseable {
    private class Stored(val value: Any?, val quality: Quality, val timestampMillis: Long)

    private inner class TagState(val settings: HistorySettings, @Volatile var lastStored: Stored?) {
        val path: String = settings.normalizedPath
        var subscription: AutoCloseable? = null
        /** Periodic: next sample time in epoch millis; [Long.MIN_VALUE] means due on the next tick. */
        var nextDueMillis: Long = Long.MIN_VALUE
    }

    private val states = ConcurrentHashMap<String, TagState>()
    /** Last row already in the sink for each tag (lowercase path), from before this collector started. */
    private val persisted = ConcurrentHashMap<String, Stored>()
    /** Unbounded queue whose size [queued] keeps at or below [queueCapacity]; the cap can change at runtime. */
    private val queue = LinkedBlockingQueue<HistorySample>()
    private val queued = AtomicInteger()
    @Volatile private var capacity = queueCapacity.also { require(it > 0) { "queueCapacity must be > 0" } }
    @Volatile private var batchLimit = MAX_BATCH
    private val writeLock = ReentrantLock()
    private val droppedCount = AtomicLong()
    private val writtenCount = AtomicLong()
    /** A batch the sink failed to write; retried before anything newer. Guarded by [writeLock]. */
    private var held: List<HistorySample> = emptyList()
    @Volatile private var heldSize = 0
    @Volatile private var failures = 0
    @Volatile private var lastError: String? = null
    @Volatile private var lastErrorMillis: Long? = null
    private val divertedCount = AtomicLong()
    @Volatile private var closed = false

    init {
        // One read at startup so deadband comparison after a restart is against the last stored row,
        // without any query on the listener path.
        for ((path, s) in sink.latestAll()) persisted[path.lowercase()] = Stored(s.value, s.quality, s.timestampMillis)
    }

    /**
     * Change the in-memory buffer at runtime (doc source: FEATURES HIS-024): [queueCapacity] samples may wait for
     * the writer (samples already queued above a lowered cap are kept and written), and each sink write takes
     * at most [batchSize] samples.
     */
    fun resize(queueCapacity: Int, batchSize: Int = batchLimit) {
        require(queueCapacity > 0) { "queueCapacity must be > 0" }
        require(batchSize > 0) { "batchSize must be > 0" }
        capacity = queueCapacity
        batchLimit = batchSize
    }

    /** The in-memory buffer's current cap, in samples. */
    val queueCapacity: Int get() = capacity

    /** Samples a single sink write takes at most. */
    val batchSize: Int get() = batchLimit

    /** Samples dropped because the queue was full. */
    val dropped: Long get() = droppedCount.get()
    /** Samples written to the sink so far. */
    val written: Long get() = writtenCount.get()
    /** Samples waiting for [flush], including a held batch the sink failed to write. */
    val pending: Int get() = queue.size + heldSize
    /** Flushes in a row whose sink write failed; 0 after a successful write. */
    val consecutiveFlushFailures: Int get() = failures
    /** Message of the most recent sink failure (kept after recovery, with [lastFlushErrorAt]). */
    val lastFlushError: String? get() = lastError
    val lastFlushErrorAt: Instant? get() = lastErrorMillis?.let { Instant.ofEpochMilli(it) }
    /** Samples handed to [divert] because the sink kept failing. */
    val diverted: Long get() = divertedCount.get()

    /** The counters above as one map, for diagnostics. */
    fun stats(): Map<String, Any?> = linkedMapOf(
        "pending" to pending,
        "written" to written,
        "dropped" to dropped,
        "queueCapacity" to capacity,
        "batchSize" to batchLimit,
        "diverted" to diverted,
        "consecutiveFlushFailures" to failures,
        "lastFlushError" to lastError,
        "lastFlushErrorAt" to lastFlushErrorAt?.toString(),
    )

    fun settings(): List<HistorySettings> = states.values.map { it.settings }

    fun isCollecting(tagPath: String): Boolean = states.containsKey(key(normalizeTagPath(tagPath)))

    /** Start or replace collection for [settings].tagPath. Keeps the last stored value across a replace. */
    fun configure(settings: HistorySettings) {
        check(!closed) { "collector is closed" }
        val k = key(settings.normalizedPath)
        val previous = states.remove(k)
        previous?.subscription?.let { runCatching { it.close() } }
        val state = TagState(settings, previous?.lastStored ?: persisted[k])
        states[k] = state
        if (settings.sampleMode == HistorySampleMode.ON_CHANGE) {
            state.subscription = engine.subscribe(state.path) { change -> onChange(state, change.value) }
            // Evaluate the current value once so a reader does not wait for the next change.
            onChange(state, engine.read(state.path))
        }
    }

    /** Stop collecting [tagPath]. Samples already queued are still written. */
    fun stop(tagPath: String) {
        states.remove(key(normalizeTagPath(tagPath)))?.subscription?.let { runCatching { it.close() } }
    }

    /** Tag engine listener: in-memory comparison plus a non-blocking queue offer. */
    private fun onChange(state: TagState, qv: QualifiedValue) {
        if (states[key(state.path)] !== state) return
        evaluate(state, qv.value, qv.quality, qv.timestamp.toEpochMilli(), forced = false)
    }

    /**
     * Evaluate periodic tags whose sample time has come, and the max-time rule for on-change tags, as of
     * [now]. A periodic row is stamped [now].
     */
    fun tick(now: Instant = clock.instant()) {
        val nowMillis = now.toEpochMilli()
        for (state in states.values) {
            val s = state.settings
            when (s.sampleMode) {
                HistorySampleMode.PERIODIC -> {
                    val due = synchronized(state) {
                        if (state.nextDueMillis != Long.MIN_VALUE && nowMillis < state.nextDueMillis) false
                        else {
                            val next = if (state.nextDueMillis == Long.MIN_VALUE) nowMillis + s.sampleRateMillis
                            else state.nextDueMillis + s.sampleRateMillis
                            // After a stall, skip missed periods rather than storing a burst.
                            state.nextDueMillis = if (next <= nowMillis) nowMillis + s.sampleRateMillis else next
                            true
                        }
                    }
                    if (due) {
                        val qv = engine.read(state.path)
                        evaluate(state, qv.value, qv.quality, nowMillis, forced = false)
                    }
                }
                HistorySampleMode.ON_CHANGE -> {
                    val max = s.maxTimeBetweenSamplesMillis
                    val last = state.lastStored ?: continue
                    if (max > 0 && nowMillis - last.timestampMillis >= max) {
                        val qv = engine.read(state.path)
                        evaluate(state, qv.value, qv.quality, nowMillis, forced = true)
                    }
                }
            }
        }
    }

    private fun evaluate(state: TagState, value: Any?, quality: Quality, timestampMillis: Long, forced: Boolean) {
        synchronized(state) {
            if (!forced && !shouldStore(state.settings, state.lastStored, value, quality, timestampMillis)) return
            // offer() never blocks. The baseline moves only when the sample is really queued.
            if (queued.incrementAndGet() <= capacity) {
                queue.offer(HistorySample(state.path, timestampMillis, value, quality))
                state.lastStored = Stored(value, quality, timestampMillis)
            } else {
                queued.decrementAndGet()
                droppedCount.incrementAndGet()
            }
        }
    }

    /**
     * Write every queued sample to the sink in batches. Returns the number written. Safe to call from
     * any thread; concurrent calls are serialized so batches commit in queue order.
     *
     * If the sink throws, the failed batch is held (not dropped) and the exception is rethrown. The next
     * call writes the held batch before draining newer samples, so nothing is lost or reordered.
     *
     * A sink that keeps failing is bounded: from the [divertAfterFailures]th failed flush in a row, each
     * failing batch is handed to [divert] (durable quarantine) instead of being held, so memory holds at most
     * one batch plus the bounded queue. [consecutiveFlushFailures] and [lastFlushError] show the state.
     */
    fun flush(): Int = flush(writer = true)

    /**
     * [flush] for a reader that wants its own writes visible (a history query on an HTTP thread). A failure here
     * holds the batch as usual but does not count toward [divertAfterFailures] and never diverts, so quick reads
     * while the store is failing cannot push the writer's batch into quarantine early (review-3 F8).
     */
    fun flushForRead(): Int = flush(writer = false)

    private fun flush(writer: Boolean): Int = writeLock.withLock {
        var total = 0
        while (true) {
            val batch: List<HistorySample> = held.ifEmpty {
                val drained = ArrayList<HistorySample>()
                queue.drainTo(drained, batchLimit)
                queued.addAndGet(-drained.size)
                drained
            }
            if (batch.isEmpty()) break
            try {
                sink.appendAll(batch)
            } catch (e: Exception) {
                if (writer) failures++
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                lastErrorMillis = clock.millis()
                held = batch
                heldSize = batch.size
                if (writer && divert != null && failures >= divertAfterFailures) {
                    try {
                        divert.invoke(batch, e)
                        divertedCount.addAndGet(batch.size.toLong())
                        held = emptyList()
                        heldSize = 0
                    } catch (d: Exception) {
                        e.addSuppressed(d)
                    }
                }
                throw e
            }
            failures = 0
            held = emptyList()
            heldSize = 0
            writtenCount.addAndGet(batch.size.toLong())
            total += batch.size
        }
        total
    }

    /** Stop all collection and write what is queued. Does not close the sink. */
    override fun close() {
        closed = true
        states.values.forEach { s -> s.subscription?.let { runCatching { it.close() } } }
        states.clear()
        flush()
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 1_000_000
        const val MAX_BATCH = 10_000
        const val DEFAULT_DIVERT_AFTER_FAILURES = 5

        private fun key(normalizedPath: String) = normalizedPath.lowercase()

        /** The store rule documented on [HistorySettings]. */
        private fun shouldStore(settings: HistorySettings, last: Stored?, value: Any?, quality: Quality, timestampMillis: Long): Boolean {
            if (last == null) return true
            if (last.quality.code != quality.code) return true
            val max = settings.maxTimeBetweenSamplesMillis
            if (max > 0 && timestampMillis - last.timestampMillis >= max) return true
            val deadband = settings.deadband
            if (deadband.mode == DeadbandMode.OFF) return timestampMillis != last.timestampMillis || last.value != value
            if (last.value == value) return false
            return !deadband.suppresses(last.value, value, null, null)
        }
    }
}
