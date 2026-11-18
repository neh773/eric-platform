package org.oip.historian

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What happens to new samples for a provider whose disk cache holds [ForwardPolicy.maxPending] records. */
enum class WhenFull(val wire: String) {
    /** Keep what is queued and refuse the newest samples that do not fit (counted as rejected). */
    PREVENT_NEW_DATA("preventNewData"),
    /** Store the new samples and delete the provider's oldest queued records (counted as evicted). */
    EVICT_OLDEST_DATA("evictOldestData");

    companion object {
        fun fromWire(text: String): WhenFull = entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("whenFull must be one of ${entries.joinToString { it.wire }}")
    }
}

/**
 * Store-and-forward settings of one provider, from the `storeAndForward` block of its `historian/HistoryProvider`
 * resource (doc source: FEATURES HIS-024):
 *  - [forwardRateMillis]: how often the provider's queue is eligible to forward (the reference product's Forward Rate);
 *  - [batchSize]: records per write to the provider (Batch Size);
 *  - [retryMillis]: wait after the provider was unreachable before trying again;
 *  - [maxPending]: most records its disk cache may hold, 0 for no limit (Maintenance Value, COUNT);
 *  - [whenFull]: what a full cache does with new samples (Maintenance Action).
 */
data class ForwardPolicy(
    val forwardRateMillis: Long = DEFAULT_FORWARD_RATE_MILLIS,
    val batchSize: Int = HistoryRouter.DEFAULT_BATCH,
    val retryMillis: Long = HistoryRouter.DEFAULT_RETRY_MILLIS,
    val maxPending: Long = 0,
    val whenFull: WhenFull = WhenFull.PREVENT_NEW_DATA,
) {
    init {
        require(forwardRateMillis >= 0) { "forwardRateMillis must be >= 0" }
        require(batchSize in 1..MAX_BATCH) { "batchSize must be between 1 and $MAX_BATCH" }
        require(retryMillis >= 0) { "retryMillis must be >= 0" }
        require(maxPending >= 0) { "maxPending must be >= 0" }
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "forwardRateMillis" to forwardRateMillis,
        "batchSize" to batchSize,
        "retryMillis" to retryMillis,
        "maxPending" to maxPending,
        "whenFull" to whenFull.wire,
    )

    companion object {
        /** The forwarder's pace before this setting existed: one pass every 500 ms. */
        const val DEFAULT_FORWARD_RATE_MILLIS = 500L
        const val MAX_BATCH = 100_000
    }
}

/**
 * Routes collected samples to history providers and forwards buffered ones.
 *
 * The collector's sink. [appendAll] runs on the collector's writer thread (never a tag listener):
 *  - tags whose [HistorySettings.storageProvider] is [LOCAL_PROVIDER] are written straight to the local SQLite
 *    store (the reference platform's Core Historian likewise bypasses store-and-forward);
 *  - every other tag's samples are appended to the durable [ForwardBuffer] and later written to the provider
 *    by [forward]; while the database is unreachable they stay queued and are sent once it is back
 *    (-).
 *
 * Forwarding rules ([forward]):
 *  1. Per provider, records go in buffer order (seq), oldest first, in batches of [batchSize]. A tag's samples
 *     stay in that order. A provider may commit different tags of one batch on different connections; it does
 *     not return until every tag in the batch has committed, and only then does the batch leave the buffer.
 *  2. A record leaves the buffer only after the provider committed it. Each record carries (sourceId, seq) and
 *     the provider skips one it already has, so a batch resent after a lost acknowledgement lands once.
 *  3. A transient failure (unreachable database) stops forwarding to that provider; everything stays queued and
 *     is retried after [retryMillis]. New samples keep accumulating behind it.
 *  4. A permanent failure (the provider rejects the data) retries that batch one record at a time, as the same
 *     page describes for its quarantine. Records that fail alone go to the quarantine table; the rest are written, so a bad record never blocks the queue.
 *  5. Records for a provider that is not configured stay queued until it is.
 *
 * Per-provider settings ([ForwardPolicy], [setPolicy]) set the batch size, retry delay, forward rate ([forwardDue])
 * and a cap on that provider's queued records; a capped queue refuses or evicts as its policy says, in the same
 * transaction as the enqueue.
 *
 * A [SplitterHistoryProvider]'s queue is not written to a database: [forward] moves each batch onto the queues of
 * its two targets in one transaction ([ForwardBuffer.fanOut]), and each target then forwards on its own.
 *
 * Store rate is datapoints per second written into that provider's disk cache over the last
 * [STORE_RATE_WINDOW_MILLIS], the status page's "Store Rate". Local history bypasses the cache and stays at zero.
 * (doc source: FEATURES HIS-025)
 */
class HistoryRouter(
    val local: HistorySink,
    val buffer: ForwardBuffer,
    private val clock: Clock = Clock.systemUTC(),
    val retryMillis: Long = DEFAULT_RETRY_MILLIS,
    val batchSize: Int = DEFAULT_BATCH,
) : HistorySink, NativeAggregation {
    private val routes = ConcurrentHashMap<String, String>()
    private val policies = ConcurrentHashMap<String, ForwardPolicy>()
    private val providers = ConcurrentHashMap<String, RemoteHistoryProvider>()
    private val states = ConcurrentHashMap<String, ProviderState>()
    private val forwardLock = ReentrantLock()
    /** A batch whose buffered part committed but whose local part failed; see [appendAll]. */
    private var bufferedFor: List<HistorySample>? = null

    private class ProviderState {
        @Volatile var retryAtMillis: Long = Long.MIN_VALUE
        @Volatile var lastError: String? = null
        @Volatile var lastErrorMillis: Long? = null
        @Volatile var lastForwardMillis: Long? = null
        @Volatile var forwarded: Long = 0
        @Volatile var quarantined: Long = 0
        @Volatile var connected: Boolean? = null
        @Volatile var lastAttemptMillis: Long = Long.MIN_VALUE
        @Volatile var rejected: Long = 0
        @Volatile var evicted: Long = 0
        val stores = StoreWindow()
    }

    /** Datapoints written into one provider's disk cache, and the rate over [STORE_RATE_WINDOW_MILLIS]. */
    private class StoreWindow {
        private val events = ArrayDeque<LongArray>()
        var stored: Long = 0
            private set

        fun add(nowMillis: Long, count: Int) {
            if (count <= 0) return
            synchronized(this) {
                stored += count
                events.addLast(longArrayOf(nowMillis, count.toLong()))
                prune(nowMillis)
            }
        }

        /** Cumulative stored count, and datapoints per second inside the window ending at [nowMillis]. */
        fun snapshot(nowMillis: Long): Pair<Long, Double> = synchronized(this) {
            prune(nowMillis)
            val sum = events.sumOf { it[1] }
            stored to sum * 1000.0 / STORE_RATE_WINDOW_MILLIS
        }

        private fun prune(nowMillis: Long) {
            val cutoff = nowMillis - STORE_RATE_WINDOW_MILLIS
            while (events.isNotEmpty() && events.first()[0] <= cutoff) events.removeFirst()
        }
    }

    /** Send [tagPath]'s future samples to [provider]. */
    fun route(tagPath: String, provider: String) {
        routes[normalizeTagPath(tagPath).lowercase()] = provider
    }

    /** The provider [tagPath] is routed to ([LOCAL_PROVIDER] when none was set). */
    fun providerFor(tagPath: String): String = routes[normalizeTagPath(tagPath).lowercase()] ?: LOCAL_PROVIDER

    /** Add or replace the provider named [RemoteHistoryProvider.name]; a replaced one is closed. */
    fun register(provider: RemoteHistoryProvider) {
        require(provider.name != LOCAL_PROVIDER) { "'$LOCAL_PROVIDER' is the built-in provider" }
        providers.put(provider.name, provider)?.let { if (it !== provider) runCatching { it.close() } }
        state(provider.name).retryAtMillis = Long.MIN_VALUE
    }

    fun unregister(name: String) {
        providers.remove(name)?.let { runCatching { it.close() } }
    }

    fun provider(name: String): RemoteHistoryProvider? = providers[name]

    /** Set (or with null, clear) [name]'s store-and-forward settings. Applies to the next enqueue and forward. */
    fun setPolicy(name: String, policy: ForwardPolicy?) {
        if (policy == null) policies.remove(name) else policies[name] = policy
    }

    /** The settings in effect for [name]: its own, or the router defaults. */
    fun policyFor(name: String): ForwardPolicy = policies[name] ?: ForwardPolicy(batchSize = batchSize, retryMillis = retryMillis)

    /**
     * Put a batch the collector could not write after repeated failures into the quarantine, durably, each
     * sample under the provider its tag routes to, with [error]. Retrying them puts them back on the queue:
     * a `local` record is then written to the local store by [forward]. If the remote part of this very batch
     * was already buffered (see [appendAll]), only the local part is quarantined.
     */
    @Synchronized
    fun divert(samples: List<HistorySample>, error: Exception) {
        val alreadyBuffered = bufferedFor === samples
        val byProvider = LinkedHashMap<String, MutableList<HistorySample>>()
        for (s in samples) {
            val p = routes[s.tagPath.lowercase()] ?: LOCAL_PROVIDER
            if (alreadyBuffered && p != LOCAL_PROVIDER) continue
            byProvider.getOrPut(p) { ArrayList() } += s
        }
        val message = "history write failed repeatedly: ${error.javaClass.simpleName}: ${error.message}"
        val now = clock.millis()
        for ((p, list) in byProvider) buffer.quarantineSamples(p, list, message, now)
        bufferedFor = null
    }

    /** Reader for [name]: the local store, a configured provider, or null. */
    fun reader(name: String): HistoryReader? = if (name == LOCAL_PROVIDER) local else providers[name]

    @Synchronized
    override fun appendAll(samples: List<HistorySample>) {
        val remote = LinkedHashMap<String, MutableList<HistorySample>>()
        val locals = ArrayList<HistorySample>()
        for (s in samples) {
            val p = routes[s.tagPath.lowercase()] ?: LOCAL_PROVIDER
            if (p == LOCAL_PROVIDER) locals += s else remote.getOrPut(p) { ArrayList() } += s
        }
        // The collector retries the same batch object after a failure. If the buffered part already
        // committed, do not buffer it again (it would get new seqs and arrive twice).
        if (bufferedFor !== samples) {
            val nowMillis = clock.millis()
            for ((p, list) in remote) {
                val policy = policies[p]
                val st = state(p)
                if (policy == null || policy.maxPending <= 0) {
                    buffer.enqueue(p, list)
                    st.stores.add(nowMillis, list.size)
                } else {
                    val r = buffer.enqueueCapped(p, list, policy.maxPending, policy.whenFull == WhenFull.EVICT_OLDEST_DATA)
                    st.stores.add(nowMillis, r.stored)
                    if (r.rejected > 0) {
                        st.rejected += r.rejected
                        st.lastError = "disk cache for '$p' is full (${policy.maxPending} records); ${r.rejected} new samples were refused"
                        st.lastErrorMillis = nowMillis
                    }
                    st.evicted += r.evicted
                }
            }
            bufferedFor = samples
        }
        local.appendAll(locals)
        bufferedFor = null
    }

    override fun query(query: HistoryQuery): List<HistorySample> = local.query(query)
    override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) = local.scan(tagPath, fromMillis, toMillis, each)
    override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? = local.latestBefore(tagPath, beforeMillis)

    override fun aggregateNative(
        tagPath: String,
        fromMillis: Long,
        toMillis: Long,
        intervalMillis: Long,
        mode: HistoryAggregate,
        interpolation: HistoryInterpolation,
    ): List<AggregateWindow>? = (local as? NativeAggregation)?.aggregateNative(tagPath, fromMillis, toMillis, intervalMillis, mode, interpolation)

    /** Last sample per tag across the local store and the forward buffer (later timestamp wins). */
    override fun latestAll(): Map<String, HistorySample> {
        val out = HashMap<String, HistorySample>()
        for (s in local.latestAll().values + buffer.latestAll().values) {
            val k = s.tagPath.lowercase()
            val prev = out[k]
            if (prev == null || s.timestampMillis >= prev.timestampMillis) out[k] = s
        }
        return out.values.associateBy { it.tagPath }
    }

    /**
     * Forward buffered records to every configured provider that is not waiting out a retry delay, as of
     * [now]. Returns the number of records written. Serialized; see the class KDoc for the rules.
     */
    fun forward(now: Instant = clock.instant()): Int = forwardLock.withLock { forwardAll(now.toEpochMilli(), due = false) }

    /**
     * [forward] for the providers whose [ForwardPolicy.forwardRateMillis] has passed since their last attempt.
     * The production timer calls this; [forward] ignores the rate.
     */
    fun forwardDue(now: Instant = clock.instant()): Int = forwardLock.withLock { forwardAll(now.toEpochMilli(), due = true) }

    /** [forward] for [provider] only if no forward is running; never waits. Used before a read. */
    fun tryForward(provider: String, now: Instant = clock.instant()): Int {
        if (!forwardLock.tryLock()) return 0
        try {
            val p = providers[provider] ?: return 0
            val st = state(provider)
            if (st.retryAtMillis > now.toEpochMilli()) return 0
            return forwardOne(p, st, now.toEpochMilli())
        } finally {
            forwardLock.unlock()
        }
    }

    /** Status per provider: configured ones plus any name with buffered or quarantined records. */
    fun status(): List<Map<String, Any?>> {
        val pending = buffer.pendingCounts()
        val quarantined = buffer.quarantineCounts()
        val names = (listOf(LOCAL_PROVIDER) + providers.keys + pending.keys + quarantined.keys).distinct().sorted()
        return names.map { n ->
            val st = states[n]
            val store = st?.stores?.snapshot(clock.millis())
            linkedMapOf<String, Any?>(
                "name" to n,
                "configured" to (n == LOCAL_PROVIDER || providers.containsKey(n)),
                "storeAndForward" to (n != LOCAL_PROVIDER),
                "pending" to (pending[n] ?: 0L),
                "quarantined" to (quarantined[n] ?: 0L),
                "stored" to (store?.first ?: 0L),
                "storeRate" to (store?.second ?: 0.0),
                "connected" to if (n == LOCAL_PROVIDER) true else st?.connected,
                "rejected" to (st?.rejected ?: 0L),
                "evicted" to (st?.evicted ?: 0L),
                "forwarded" to (st?.forwarded ?: 0L),
                "lastForward" to st?.lastForwardMillis?.let { Instant.ofEpochMilli(it).toString() },
                "lastError" to st?.lastError,
                "lastErrorAt" to st?.lastErrorMillis?.let { Instant.ofEpochMilli(it).toString() },
                "retryAt" to st?.retryAtMillis?.takeIf { it != Long.MIN_VALUE }?.let { Instant.ofEpochMilli(it).toString() },
            ).also { m ->
                if (n != LOCAL_PROVIDER) m["policy"] = policyFor(n).toMap()
                if (n == LOCAL_PROVIDER) (local as? EmbeddedHistorian)?.let { h -> runCatching { m["provider"] = h.status() } }
                providers[n]?.let { p -> runCatching { m["provider"] = p.status() } }
            }
        }
    }

    /**
     * Put [records] back on their providers' disk caches with the archive's (sourceId, seq), in one transaction.
     * A provider that is not registered is refused before anything is written. Rows already pending under that
     * seq are skipped. Counted toward store rate.
     * (doc source: FEATURES HIS-025)
     */
    fun loadDiskCache(records: List<DiskCacheRecord>): Int {
        requireKnown(records.map { it.provider })
        if (records.isEmpty()) return 0
        if (records.any { it.seq <= 0 || it.sourceId.isBlank() }) {
            throw IllegalArgumentException("disk cache record is missing sourceId or seq; nothing was loaded")
        }
        val inserted = buffer.enqueueIdentified(records)
        noteStored(records, inserted)
        return inserted
    }

    /** Count [inserted] rows against the providers in [records]. One provider per gateway-side archive page. */
    fun noteStored(provider: String, inserted: Int) {
        if (inserted > 0) state(provider).stores.add(clock.millis(), inserted)
    }

    /**
     * Put records from a quarantine export back through store-and-forward and forward them now.
     * The count is how many were placed on the queue. A provider that still rejects a record quarantines it again;
     * one that is unreachable keeps it pending. A provider that is not registered is refused and nothing is written.
     * (doc source: FEATURES HIS-025)
     */
    fun importQuarantine(records: List<DiskCacheRecord>): Int {
        requireKnown(records.map { it.provider })
        val stored = storeIncoming(records)
        if (stored > 0) forward()
        return stored
    }

    private fun requireKnown(providers: Collection<String>) {
        val missing = providers.distinct().filter { it != LOCAL_PROVIDER && !this.providers.containsKey(it) }
        if (missing.isNotEmpty()) throw UnknownHistoryProvider(missing)
    }

    private fun noteStored(records: List<DiskCacheRecord>, inserted: Int) {
        if (inserted <= 0) return
        val providers = records.map { it.provider }.distinct()
        if (providers.size == 1) {
            state(providers.single()).stores.add(clock.millis(), inserted)
        } else {
            val now = clock.millis()
            for ((provider, rows) in records.groupBy { it.provider }) state(provider).stores.add(now, rows.size)
        }
    }

    /** Close every provider and the buffer. The local store is closed by its owner. */
    override fun close() {
        forwardLock.withLock {
            providers.values.forEach { runCatching { it.close() } }
            providers.clear()
        }
        buffer.close()
    }

    private fun state(name: String) = states.getOrPut(name) { ProviderState() }

    /** Enqueue [records] in one transaction and count them as stored. Empty input writes nothing. */
    private fun storeIncoming(records: List<DiskCacheRecord>): Int {
        if (records.isEmpty()) return 0
        val grouped = LinkedHashMap<String, MutableList<HistorySample>>()
        for (record in records) grouped.getOrPut(record.provider) { ArrayList() }.add(record.sample)
        buffer.enqueueAll(grouped.map { (provider, samples) -> provider to samples.toList() })
        val nowMillis = clock.millis()
        for ((provider, samples) in grouped) state(provider).stores.add(nowMillis, samples.size)
        return records.size
    }

    /** Retried `local` records (from [divert]) are written straight to the local store. */
    private val localTarget = object : RemoteHistoryProvider {
        override val name = LOCAL_PROVIDER
        override fun write(records: List<ForwardRecord>) = try {
            local.appendAll(records.map { it.sample })
        } catch (e: Exception) {
            throw HistoryWriteException("${e.javaClass.simpleName}: ${e.message}", permanent = false, e)
        }
        override fun query(query: HistoryQuery) = local.query(query)
        override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) = local.scan(tagPath, fromMillis, toMillis, each)
        override fun latestBefore(tagPath: String, beforeMillis: Long) = local.latestBefore(tagPath, beforeMillis)
    }

    private fun forwardAll(nowMillis: Long, due: Boolean): Int {
        var total = 0
        // A splitter's fan-out can fill a target queue listed earlier; a second look forwards it in this pass.
        var names = buffer.pendingCounts().keys
        for (round in 0 until 2) {
            var fannedOut = false
            for (name in names) {
                val st = state(name)
                val p = if (name == LOCAL_PROVIDER) localTarget else providers[name]
                if (p == null) {
                    st.lastError = "history provider '$name' is not configured; records stay queued"
                    continue
                }
                if (st.retryAtMillis > nowMillis) continue
                if (due && round == 0) {
                    val rate = policyFor(name).forwardRateMillis
                    if (st.lastAttemptMillis != Long.MIN_VALUE && nowMillis - st.lastAttemptMillis < rate) continue
                }
                if (round == 0) st.lastAttemptMillis = nowMillis
                val n = forwardOne(p, st, nowMillis)
                if (p is SplitterHistoryProvider && n > 0) fannedOut = true
                total += n
            }
            if (!fannedOut) break
            names = buffer.pendingCounts().keys.filter { n -> providers[n] !is SplitterHistoryProvider }.toSet()
        }
        return total
    }

    /** Move a splitter's queued records onto both targets' queues. A target that is itself a splitter is refused. */
    private fun fanOut(p: SplitterHistoryProvider, st: ProviderState, nowMillis: Long): Int {
        val nested = p.targets.filter { providers[it] is SplitterHistoryProvider }
        if (nested.isNotEmpty()) {
            st.fail(HistoryWriteException("splitter '${p.name}' cannot write to splitter ${nested.joinToString()}", permanent = false), nowMillis, policyFor(p.name).retryMillis)
            return 0
        }
        var moved = 0
        val size = policyFor(p.name).batchSize
        while (moved < MAX_PER_PASS) {
            val batch = buffer.head(p.name, size)
            if (batch.isEmpty()) break
            buffer.fanOut(p.name, batch, p.targets)
            moved += batch.size
            st.ok(nowMillis, batch.size)
            val now = clock.millis()
            for (t in p.targets) state(t).stores.add(now, batch.size)
        }
        return moved
    }

    private fun forwardOne(p: RemoteHistoryProvider, st: ProviderState, nowMillis: Long): Int {
        if (p is SplitterHistoryProvider) return fanOut(p, st, nowMillis)
        val policy = policyFor(p.name)
        val retryMillis = policy.retryMillis
        var written = 0
        while (written < MAX_PER_PASS) {
            val batch = buffer.head(p.name, policy.batchSize)
            if (batch.isEmpty()) break
            try {
                p.write(batch)
                buffer.remove(p.name, batch)
                written += batch.size
                st.ok(nowMillis, batch.size)
            } catch (e: HistoryWriteException) {
                if (!e.permanent) {
                    st.fail(e, nowMillis, retryMillis)
                    return written
                }
                // One at a time: write what the provider accepts, quarantine what it rejects.
                for (record in batch) {
                    try {
                        p.write(listOf(record))
                        buffer.remove(p.name, listOf(record))
                        written++
                        st.ok(nowMillis, 1)
                    } catch (single: HistoryWriteException) {
                        if (!single.permanent) {
                            st.fail(single, nowMillis, retryMillis)
                            return written
                        }
                        buffer.quarantine(p.name, record, single.message ?: "rejected", nowMillis)
                        st.quarantined++
                        st.lastError = single.message
                        st.lastErrorMillis = nowMillis
                    }
                }
            }
        }
        return written
    }

    private fun ProviderState.ok(nowMillis: Long, count: Int) {
        connected = true
        forwarded += count
        lastForwardMillis = nowMillis
        retryAtMillis = Long.MIN_VALUE
    }

    private fun ProviderState.fail(e: HistoryWriteException, nowMillis: Long, delay: Long) {
        connected = false
        lastError = e.message
        lastErrorMillis = nowMillis
        retryAtMillis = nowMillis + delay
    }

    companion object {
        const val DEFAULT_RETRY_MILLIS = 5_000L
        /**
         * Rows per database round trip. A COPY of this many rows is what keeps a provider ahead of the
         * PRF-008 stretch ingest (30,000 changes/s); 1,000-row inserts did not.
         */
        const val DEFAULT_BATCH = 10_000
        /**
         * Trailing window for [status]'s per-provider store rate (datapoints per second entering the disk cache).
         * (doc source: FEATURES HIS-025)
         */
        const val STORE_RATE_WINDOW_MILLIS = 10_000L
        /** Records per provider per [forward] call, so one busy provider does not starve the others. */
        const val MAX_PER_PASS = 100_000
    }
}
