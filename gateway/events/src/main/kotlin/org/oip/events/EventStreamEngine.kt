package org.oip.events

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Scope
import org.oip.tags.TagChange
import org.oip.tags.TagEngine
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Event streams stored as config resources and executed off the tag-engine thread.
 *
 * A tag listener only enqueues. [pump] runs the filter, transform, buffer, and handlers.
 */
class EventStreamEngine(
    private val clock: Clock,
    private val tags: TagEngine?,
    private val store: FileConfigStore,
    private val inline: Boolean = false,
    var scripts: EventScriptHost = EventScriptHost.NOOP,
    var query: NamedUpdate = NamedUpdate { _, _, _ -> throw IllegalStateException("no database service") },
    var http: HttpSender = javaHttp(),
    var log: (level: String, logger: String, message: String) -> Unit = { level, logger, message ->
        val jul = java.util.logging.Logger.getLogger(logger)
        when (level.lowercase()) {
            "error" -> jul.severe(message)
            "warn" -> jul.warning(message)
            "debug", "trace" -> jul.fine(message)
            else -> jul.info(message)
        }
    },
    var deferWhen: () -> Boolean = { Thread.currentThread().name.startsWith("tag-") },
    /** When false, handlers do not run and queued events are dropped. An independent gateway leaves this true. */
    var actsWhen: () -> Boolean = { true },
    /** Posts a handler payload to a gateway message handler. The gateway installs this. */
    var gatewayEvents: GatewayEvents = GatewayEvents { _, _ -> throw IllegalStateException("no gateway event bus") },

    /** Kafka source and handler clients. The gateway installs [ApacheKafkaClients]. */
    var kafka: KafkaClients = KafkaClients.NONE,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val streams = LinkedHashMap<String, LiveStream>()
    private val faults = LinkedHashMap<String, String>()
    private val seenInitial = HashSet<String>()
    private val ingress = ArrayDeque<Pair<String, StreamEvent>>()
    private val ingressLock = ReentrantLock()
    private val pendingByStream = HashMap<String, Int>()
    private val connections = LinkedHashMap<String, KafkaConnection>()
    private val connectionFaults = LinkedHashMap<String, String>()
    private val producers = LinkedHashMap<Pair<String, Map<String, String>>, Pair<KafkaConnection, KafkaProducerClient>>()
    private val sources = LinkedHashMap<String, KafkaSource>()
    private var subscription: AutoCloseable? = null
    private var tagSubs: List<AutoCloseable> = emptyList()
    private var executor: ScheduledExecutorService? = null
    private var scheduled = false
    @Volatile private var closed = false

    fun attach() {
        registerEventSchemas(store.registry)
        store.revalidate(EVENTS_MODULE)
        store.revalidate(KAFKA_MODULE)
        subscription = store.subscribe { event -> onConfig(event) }
        teardown(lock.withLock { reload() })
    }

    fun start() {
        if (inline || scheduled || closed) return
        val exec = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "event-stream").apply { isDaemon = true }
        }
        executor = exec
        scheduled = true
        exec.scheduleWithFixedDelay({ runCatching { pump() } }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS)
        lock.withLock { sources.values.forEach { it.start() } }
    }

    /**
     * One poll of every Kafka source on the calling thread. Only for an engine built with `inline = true`
     * (tests); otherwise each source polls on its own thread.
     */
    fun pollSources(timeoutMillis: Long = 0) {
        check(inline) { "pollSources is only for inline engines" }
        val current = lock.withLock { sources.values.toList() }
        current.forEach { it.step(timeoutMillis) }
    }

    fun streams(): List<Map<String, Any?>> = lock.withLock {
        (streams.keys + faults.keys).sorted().map { view(it) }
    }

    fun stream(name: String): Map<String, Any?> = lock.withLock {
        if (name !in streams && name !in faults) throw NoSuchElementException("event stream '$name' not found")
        view(name)
    }

    /**
     * HTTP source. Returns false when the buffer drops the event (back-pressure).
     * Mounted by the gateway at `/system/eventstream/{name}`.
     */
    fun publishHttp(name: String, data: Any?, metadata: Map<String, Any?>): Boolean = lock.withLock {
        if (!actsWhen()) throw NodeNotActive()
        val live = streams[name] ?: throw NoSuchElementException("event stream '$name' not found")
        if (live.definition.sourceType != "http") throw IllegalArgumentException("event stream '$name' does not accept HTTP")
        if (!live.definition.enabled) return false
        bind(live)
        live.pipeline.offer(StreamEvent(data, metadata), clock.millis())
    }

    /**
     * Event-listener source. Returns false when the buffer drops the event.
     * A listener stream does not poll; something on the gateway calls this with the event.
     */
    fun publishListener(name: String, data: Any?, metadata: Map<String, Any?> = mapOf("source" to "listener")): Boolean = lock.withLock {
        if (!actsWhen()) throw NodeNotActive()
        val live = streams[name] ?: throw NoSuchElementException("event stream '$name' not found")
        if (live.definition.sourceType != "listener") throw IllegalArgumentException("event stream '$name' does not accept listener events")
        if (!live.definition.enabled) return false
        bind(live)
        live.pipeline.offer(StreamEvent(data, metadata), clock.millis())
    }

    /**
     * One event from the admin screen, so a person can try the handlers of a saved stream.
     * The source type does not matter. Returns false when the stream is disabled or the buffer drops the event.
     */
    fun publishTest(name: String, data: Any?): Boolean = lock.withLock {
        if (!actsWhen()) throw NodeNotActive()
        val live = streams[name] ?: throw NoSuchElementException("event stream '$name' not found")
        if (!live.definition.enabled) return false
        bind(live)
        live.pipeline.offer(StreamEvent(data, mapOf("source" to "test")), clock.millis())
    }

    fun requiresAuthentication(name: String): Boolean = lock.withLock {
        streams[name]?.definition?.requireAuthentication == true
    }

    fun requireHttps(name: String): Boolean = lock.withLock {
        streams[name]?.definition?.requireHttps == true
    }

    fun roles(name: String): Set<String> = lock.withLock {
        streams[name]?.definition?.roles ?: emptySet()
    }

    fun pump() {
        if (!actsWhen()) {
            ingressLock.withLock {
                ingress.clear()
                pendingByStream.clear()
            }
            lock.withLock { streams.values.forEach { it.pipeline.discard() } }
            return
        }
        if (deferWhen()) return
        val pending = ingressLock.withLock {
            val copy = ingress.toList()
            ingress.clear()
            copy
        }
        lock.withLock {
            val now = clock.millis()
            for ((stream, event) in pending) {
                val live = streams[stream] ?: continue
                bind(live)
                live.pipeline.offer(event, now)
            }
            // Uncount only after the pipeline holds them, so a source never sees these events in neither place.
            if (pending.isNotEmpty()) {
                val drained = pending.groupingBy { it.first }.eachCount()
                ingressLock.withLock {
                    for ((stream, count) in drained) {
                        val left = (pendingByStream[stream] ?: 0) - count
                        if (left > 0) pendingByStream[stream] = left else pendingByStream.remove(stream)
                    }
                }
            }
            for (live in streams.values) {
                bind(live)
                live.pipeline.pump(now)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        subscription?.close()
        tagSubs.forEach { runCatching { it.close() } }
        tagSubs = emptyList()
        executor?.shutdownNow()
        executor = null
        val (stopping, closing) = lock.withLock {
            val s = sources.values.toList()
            s.forEach { it.halt() }
            sources.clear()
            val p = producers.values.map { it.second }
            producers.clear()
            s to p
        }
        stopping.forEach { it.stop() }
        closing.forEach { runCatching { it.close() } }
    }

    private fun onConfig(event: ConfigEvent) {
        val id = event.id ?: return
        val ours = (id.module == EVENTS_MODULE && id.kind == STREAM_KIND) || (id.module == KAFKA_MODULE && id.kind == KAFKA_CONNECTION_KIND)
        if (!ours || id.scope != Scope.GATEWAY) return
        if (event.type == ChangeType.DELETED || event.type == ChangeType.UPDATED || event.type == ChangeType.CREATED || event.type == ChangeType.INVALID) {
            teardown(lock.withLock { reload() })
        }
    }

    /** Stops sources and closes producers that [reload] retired. Runs without [lock]: this is client IO. */
    private fun teardown(work: List<() -> Unit>) {
        for (step in work) runCatching { step() }
    }

    /** Called under [lock]. Returns the client shutdowns to run after the lock is released. */
    private fun reload(): List<() -> Unit> {
        reloadConnections()
        val next = LinkedHashMap<String, LiveStream>()
        val nextFaults = LinkedHashMap<String, String>()
        for (stored in store.list(EVENTS_MODULE, Scope.GATEWAY, STREAM_KIND)) {
            val name = stored.resource.metadata.name
            try {
                val definition = compile(name, stored.resource.spec)
                definition.kafka?.let { source ->
                    if (source.connection !in connections) throw missingConnection(name, source.connection)
                }
                for (handler in definition.handlers) {
                    if (handler.type == "kafka" && handler.connection !in connections) throw missingConnection(name, handler.connection)
                }
                val existing = streams[name]
                val pipeline = if (existing != null && existing.definition == definition) existing.pipeline else EventPipeline(name, definition)
                pipeline.scripts = scripts
                pipeline.tags = tags
                pipeline.query = query
                pipeline.http = http
                pipeline.kafka = producerSource
                pipeline.gateway = gatewayEvents
                pipeline.log = log
                next[name] = LiveStream(definition, pipeline)
            } catch (e: IllegalArgumentException) {
                nextFaults[name] = e.message ?: "invalid event stream"
            }
        }
        streams.clear()
        streams.putAll(next)
        faults.clear()
        faults.putAll(nextFaults)
        resubscribe()
        return restartSources() + retireProducers()
    }

    private fun missingConnection(stream: String, connection: String): IllegalArgumentException {
        val fault = connectionFaults[connection]
        return IllegalArgumentException(
            if (fault != null) "event stream '$stream': $fault" else "event stream '$stream' kafka connection '$connection' not found",
        )
    }

    private fun reloadConnections() {
        val next = LinkedHashMap<String, KafkaConnection>()
        val nextFaults = LinkedHashMap<String, String>()
        for (stored in store.list(KAFKA_MODULE, Scope.GATEWAY, KAFKA_CONNECTION_KIND)) {
            val name = stored.resource.metadata.name
            try {
                next[name] = compileKafkaConnection(name, stored.resource.spec)
            } catch (e: IllegalArgumentException) {
                nextFaults[name] = e.message ?: "invalid kafka connection"
            }
        }
        connections.clear()
        connections.putAll(next)
        connectionFaults.clear()
        connectionFaults.putAll(nextFaults)
    }

    /** Drops producers whose connection changed or that no current Kafka handler uses. */
    private fun retireProducers(): List<() -> Unit> {
        val used = HashSet<Pair<String, Map<String, String>>>()
        for (live in streams.values) {
            for (handler in live.definition.handlers) {
                if (handler.type == "kafka") used += handler.connection to handler.properties
            }
        }
        val stale = producers.filter { (key, value) -> key !in used || connections[key.first] != value.first }.keys.toList()
        return stale.mapNotNull { key ->
            val closing = producers.remove(key)?.second ?: return@mapNotNull null
            { closing.close() }
        }
    }

    /** Called on the pump thread, under [lock]. Producers are cached per connection and property set. */
    private val producerSource = KafkaProducers { name, properties ->
        val connection = connections[name] ?: throw IllegalStateException("kafka connection '$name' not found")
        if (!connection.enabled) throw IllegalStateException("kafka connection '$name' is disabled")
        val key = name to properties
        producers[key]?.second ?: kafka.producer(connection, properties).also { producers[key] = connection to it }
    }

    /**
     * Keep a source whose whole stream definition and connection are unchanged; retire and replace the
     * rest. A replaced pipeline loses its queued events, so its source restarts too and the new consumer
     * resumes from the last committed offset. Called under [lock]; returns the stops to run after it.
     */
    private fun restartSources(): List<() -> Unit> {
        val wanted = LinkedHashMap<String, Triple<StreamDefinition, KafkaConnection, EventPipeline>>()
        for ((name, live) in streams) {
            val source = live.definition.kafka ?: continue
            if (!live.definition.enabled) continue
            val connection = connections[source.connection] ?: continue
            wanted[name] = Triple(live.definition, connection, live.pipeline)
        }
        val stops = ArrayList<() -> Unit>()
        val iterator = sources.entries.iterator()
        while (iterator.hasNext()) {
            val (name, runner) = iterator.next()
            val want = wanted[name]
            if (want == null || want.first != runner.stream || want.second != runner.connection || want.third !== runner.pipeline) {
                runner.halt()
                stops += { runner.stop() }
                iterator.remove()
            }
        }
        for ((name, want) in wanted) {
            if (name in sources) continue
            val runner = KafkaSource(name, want.first, want.second, want.third)
            sources[name] = runner
            if (scheduled && !inline) runner.start()
        }
        return stops
    }

    /** Enqueues unless [from] was retired: a source being replaced never feeds its successor's stream. */
    private fun enqueueFromSource(from: KafkaSource, event: StreamEvent): Boolean = ingressLock.withLock {
        if (!from.running) return false
        ingress.add(from.name to event)
        pendingByStream[from.name] = (pendingByStream[from.name] ?: 0) + 1
        true
    }

    private fun pendingFor(stream: String): Int = ingressLock.withLock { pendingByStream[stream] ?: 0 }

    /**
     * One Kafka source. Polls on its own thread (or from [pollSources] when inline) and only enqueues;
     * the pump runs the stream. The client is only touched from the polling thread, except
     * [KafkaConsumerClient.wakeup].
     *
     * Back-pressure: the source polls only while everything its stream holds (events waiting for the
     * pump, queued in the buffer, or in the batch being handled, including one paused for a retry) plus
     * one more poll fits in [limit]. A slow or stuck stream therefore holds the consumer back and the
     * buffer never has to drop a record.
     *
     * Delivery is at least once in group mode: an offset is committed (by this thread, before the next
     * poll and when the consumer closes) only after the stream has finished with the record. Records that
     * were polled but not finished when the consumer closes (node demoted, stream changed, gateway stopped)
     * are read again by the next consumer of the group. Partition mode commits nothing.
     */
    private inner class KafkaSource(
        val name: String,
        val stream: StreamDefinition,
        val connection: KafkaConnection,
        val pipeline: EventPipeline,
    ) {
        val definition: KafkaSourceDef = stream.kafka!!
        private val limit: Int = stream.buffer.maxQueueSize.let { if (it > 0) minOf(it, MAX_PENDING) else MAX_PENDING }
        private val batch: Int = minOf(definition.maxRecords, limit)
        private val finished = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, Long>()
        @Volatile var running = true
            private set
        @Volatile var lastError: String? = null
        @Volatile var connected = false
        @Volatile var polls = 0L
            private set
        private var thread: Thread? = null
        private var consumer: KafkaConsumerClient? = null
        private var retryAt = 0L

        fun start() {
            if (thread != null || !running) return
            thread = Thread({ loop() }, "event-stream-kafka-$name").apply {
                isDaemon = true
                start()
            }
        }

        /** Stop feeding the stream. Cheap and IO-free, so it is safe under [lock]. */
        fun halt() {
            ingressLock.withLock { running = false }
        }

        /** [halt], then wait for the poll thread and close the consumer. Never under [lock]. */
        fun stop() {
            halt()
            val polling = thread
            if (polling == null) {
                release()
                return
            }
            runCatching { consumer?.wakeup() }
            polling.interrupt()
            polling.join(10_000)
        }

        private fun loop() {
            try {
                while (running && !closed) {
                    if (!step(definition.pollTimeoutMillis)) {
                        try {
                            Thread.sleep(IDLE_MILLIS)
                        } catch (_: InterruptedException) {
                            if (!running) break
                        }
                    }
                }
            } finally {
                release()
            }
        }

        /** @return false when nothing was polled because the source is idle, paused, or backing off. */
        fun step(timeoutMillis: Long): Boolean {
            if (!running) return false
            if (!actsWhen() || !connection.enabled) {
                release()
                return false
            }
            val now = System.currentTimeMillis()
            if (now < retryAt) return false
            val client = try {
                consumer ?: open()
            } catch (e: RuntimeException) {
                fail("connect", e, now)
                return false
            }
            commit(client)
            if (pendingFor(name) + pipeline.backlog + batch > limit) return false
            val records = try {
                polls++
                client.poll(timeoutMillis)
            } catch (e: RuntimeException) {
                fail("poll", e, now)
                release()
                return false
            }
            lastError = null
            for (record in records) {
                if (!enqueueFromSource(this, event(record))) break
            }
            return true
        }

        /** Commits the offsets of records the stream has finished with. Group mode only. */
        private fun commit(client: KafkaConsumerClient) {
            if (definition.partition != null || finished.isEmpty()) return
            val snapshot = HashMap(finished)
            for ((key, offset) in snapshot) finished.remove(key, offset)
            try {
                client.commit(snapshot)
            } catch (e: RuntimeException) {
                // A revoked partition or a closed group: the next owner re-reads from the last commit.
                log("warn", "oip.events.$name", "kafka commit failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        private fun open(): KafkaConsumerClient {
            val properties = LinkedHashMap(definition.properties)
            properties["max.poll.records"] = batch.toString()
            properties.putIfAbsent("auto.offset.reset", definition.offsetReset)
            if (definition.partition == null) properties["group.id"] = definition.groupId
            val client = kafka.consumer(connection, properties)
            try {
                val partition = definition.partition
                if (partition == null) client.subscribe(definition.topics)
                else client.assign(definition.topics.first(), partition, definition.offset)
            } catch (e: RuntimeException) {
                runCatching { client.close() }
                throw e
            }
            consumer = client
            connected = true
            return client
        }

        private fun fail(what: String, error: RuntimeException, now: Long) {
            val message = "kafka $what failed: ${error.message ?: error.javaClass.simpleName}"
            if (message != lastError) log("warn", "oip.events.$name", message)
            lastError = message
            retryAt = now + RETRY_MILLIS
        }

        private fun release() {
            val client = consumer ?: return
            commit(client)
            consumer = null
            connected = false
            runCatching { client.close() }
        }

        private fun finished(record: KafkaRecord) {
            finished.merge(record.topic to record.partition, record.offset + 1) { a, b -> maxOf(a, b) }
        }

        private fun event(record: KafkaRecord): StreamEvent = StreamEvent(
            record.value,
            linkedMapOf(
                "source" to "kafka",
                "topic" to record.topic,
                "partition" to record.partition,
                "offset" to record.offset,
                "timestamp" to record.timestamp,
                "key" to record.key?.toString(Charsets.UTF_8),
                "headers" to record.headers,
            ),
            done = { finished(record) },
        )
    }

    private fun resubscribe() {
        tagSubs.forEach { runCatching { it.close() } }
        val engine = tags
        if (engine == null) {
            tagSubs = emptyList()
            return
        }
        val subs = ArrayList<AutoCloseable>()
        for ((name, live) in streams) {
            if (!live.definition.enabled || live.definition.sourceType != "tag") continue
            for (path in live.definition.tagPaths) {
                subs += engine.subscribe(path) { change -> onTag(name, live.definition, change) }
            }
        }
        tagSubs = subs
    }

    private fun bind(live: LiveStream) {
        live.pipeline.scripts = scripts
        live.pipeline.tags = tags
        live.pipeline.query = query
        live.pipeline.http = http
        live.pipeline.kafka = producerSource
        live.pipeline.log = log
    }

    /**
     * Tag callbacks run on the writer thread and may hold the tag engine lock.
     * Only enqueue here. [pump] is the only place handlers run.
     */
    private fun onTag(stream: String, definition: StreamDefinition, change: TagChange) {
        if (!actsWhen()) return
        val previous = change.previous
        val valueChanged = previous == null || change.value.value != previous.value
        val qualityChanged = previous == null || change.value.quality != previous.quality
        val timeChanged = previous == null || change.value.timestamp != previous.timestamp
        val want = (definition.onValue && valueChanged) ||
            (definition.onQuality && qualityChanged) ||
            (definition.onTimestamp && timeChanged)
        if (!want) return
        val path = change.path.toString()
        if (definition.skipInitial) {
            val key = "$stream:$path"
            val first = ingressLock.withLock { seenInitial.add(key) }
            if (first) return
        }
        val data: Any? = if (definition.qualifiedValue) linkedMapOf(
            "path" to path,
            "value" to change.value.value,
            "quality" to change.value.quality.toString(),
            "timestamp" to change.value.timestamp.toString(),
        ) else change.value.value
        val metadata = linkedMapOf(
            "source" to "tag",
            "path" to path,
            "quality" to change.value.quality.toString(),
            "timestamp" to change.value.timestamp.toString(),
        )
        ingressLock.withLock { ingress.add(stream to StreamEvent(data, metadata)) }
    }

    private fun view(name: String): Map<String, Any?> {
        val live = streams[name]
        val metrics = live?.pipeline?.metrics
        return linkedMapOf(
            "name" to name,
            "enabled" to (live?.definition?.enabled ?: false),
            "source" to (live?.definition?.sourceType ?: "invalid"),
            "valid" to (live != null),
            "error" to faults[name],
            "queued" to (live?.pipeline?.queued() ?: 0),
            "eventsReceived" to (metrics?.received ?: 0L),
            "eventsDropped" to (metrics?.dropped ?: 0L),
            "eventsFiltered" to (metrics?.filteredOut ?: 0L),
            "eventsEmitted" to (metrics?.emitted ?: 0L),
            "handlerExecutions" to (metrics?.handled ?: 0L),
            "handlerErrors" to (metrics?.errors ?: 0L),
            "filter" to (metrics?.filter?.view() ?: Timing().view()),
            "transform" to (metrics?.transform?.view() ?: Timing().view()),
            "handlers" to (metrics?.handlerView() ?: emptyMap<String, Any>()),
            "sourceConnected" to (sources[name]?.connected ?: if (live?.definition?.sourceType == "listener") true else null),
            "sourceError" to sources[name]?.lastError,
        )
    }

    private class LiveStream(val definition: StreamDefinition, val pipeline: EventPipeline)

    companion object {
        private const val TICK_MILLIS = 50L
        private const val IDLE_MILLIS = 100L
        private const val RETRY_MILLIS = 5_000L
        const val MAX_PENDING = 10_000
    }
}

internal fun compile(name: String, spec: Map<String, Any?>): StreamDefinition {
    val source = asMap(spec["source"]) ?: throw IllegalArgumentException("event stream '$name' needs a source")
    val type = source["type"] as? String ?: throw IllegalArgumentException("event stream '$name' source needs a type")
    if (type != "tag" && type != "http" && type != "kafka" && type != "listener") {
        throw IllegalArgumentException("event stream '$name' source '$type' is not supported")
    }
    val paths = asList(source["paths"]).map { it.toString() }
    if (paths.any { '*' in it || '?' in it }) throw IllegalArgumentException("tag wildcards are not supported on '$name'")
    if (type == "tag" && paths.isEmpty()) throw IllegalArgumentException("event stream '$name' needs a tag path")
    val filter = asMap(spec["filter"])
    val transform = asMap(spec["transform"])
    val buffer = asMap(spec["buffer"]) ?: emptyMap()
    val overflow = when (buffer["overflow"] as? String) {
        "dropNewest" -> Overflow.DROP_NEWEST
        else -> Overflow.DROP_OLDEST
    }
    val handlers = asList(spec["handlers"]).mapIndexed { index, raw ->
        val handler = asMap(raw) ?: throw IllegalArgumentException("handler must be an object")
        handlerDef(name, index, handler)
    }
    val error = asMap(spec["errorHandler"])
    return StreamDefinition(
        enabled = spec["enabled"] as? Boolean ?: true,
        sourceType = type,
        tagPaths = paths,
        onValue = source["value"] as? Boolean ?: true,
        onQuality = source["quality"] as? Boolean ?: true,
        onTimestamp = source["timestamp"] as? Boolean ?: true,
        qualifiedValue = source["qualifiedValue"] as? Boolean ?: true,
        skipInitial = source["skipInitial"] as? Boolean ?: false,
        requireAuthentication = source["requireAuthentication"] as? Boolean ?: false,
        requireHttps = source["requireHttps"] as? Boolean ?: false,
        roles = asList(source["roles"]).map { it.toString() }.toSet(),
        encoder = spec["encoder"] as? String ?: "json",
        filterExpression = filter?.get("expression") as? String,
        filterScript = filter?.get("source") as? String,
        transformExpression = transform?.get("expression") as? String,
        transformScript = transform?.get("source") as? String,
        buffer = BufferDef(
            debounceMillis = long(buffer["debounceMillis"], 0),
            maxWaitMillis = long(buffer["maxWaitMillis"], 0),
            maxQueueSize = (buffer["maxQueueSize"] as? Number)?.toInt() ?: 0,
            overflow = overflow,
        ),
        handlers = handlers,
        errorScript = error?.get("source") as? String,
        kafka = if (type == "kafka") kafkaSource(name, source) else null,
    )
}

/**
 * Kafka source settings. With `partition` set the source reads that one partition from `offset`
 * (or the reset policy when no offset is given) and commits nothing. Without it the source joins
 * consumer group `groupId` and the group's committed offsets decide where it resumes.
 */
private fun kafkaSource(stream: String, source: Map<String, Any?>): KafkaSourceDef {
    val connection = source["connection"] as? String
    if (connection.isNullOrBlank()) throw IllegalArgumentException("event stream '$stream' kafka source needs a connection")
    val topics = (asList(source["topics"]).map { it.toString() } + listOfNotNull(source["topic"] as? String))
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (topics.isEmpty()) throw IllegalArgumentException("event stream '$stream' kafka source needs a topic")
    val partition = (source["partition"] as? Number)?.toInt()
    if (partition != null && (partition < 0 || topics.size != 1)) {
        throw IllegalArgumentException("event stream '$stream' partition mode needs one topic and a partition of 0 or more")
    }
    val reset = (source["offsetReset"] as? String ?: "latest").lowercase()
    if (reset !in listOf("latest", "earliest", "none")) throw IllegalArgumentException("event stream '$stream' offset reset '$reset' is not supported")
    val maxRecords = (source["maxRecords"] as? Number)?.toInt() ?: 500
    if (maxRecords !in 1..MAX_KAFKA_RECORDS) throw IllegalArgumentException("event stream '$stream' maxRecords must be 1 to $MAX_KAFKA_RECORDS")
    val pollTimeout = long(source["pollTimeoutMillis"], 100)
    if (pollTimeout !in 1..60_000) throw IllegalArgumentException("event stream '$stream' pollTimeoutMillis must be 1 to 60000")
    return KafkaSourceDef(
        connection = connection,
        topics = topics,
        groupId = (source["groupId"] as? String)?.takeIf { it.isNotBlank() } ?: "oip-$stream",
        partition = partition,
        offset = (source["offset"] as? Number)?.toLong(),
        offsetReset = reset,
        maxRecords = maxRecords,
        pollTimeoutMillis = pollTimeout,
        properties = stringMap(source["properties"]).also {
            checkClientProperties("event stream '$stream' kafka source", it, setOf("group.id", "enable.auto.commit"))
        },
    )
}

private const val MAX_KAFKA_RECORDS = 10_000

data class KafkaSourceDef(
    val connection: String,
    val topics: List<String>,
    val groupId: String,
    val partition: Int?,
    val offset: Long?,
    val offsetReset: String,
    val maxRecords: Int,
    val pollTimeoutMillis: Long,
    val properties: Map<String, String>,
)

private fun handlerDef(stream: String, index: Int, spec: Map<String, Any?>): HandlerDef {
    val type = spec["type"] as? String ?: throw IllegalArgumentException("handler on '$stream' needs a type")
    val failure = when (spec["failure"] as? String) {
        "abort" -> Failure.ABORT
        "retry" -> Failure.RETRY
        else -> Failure.IGNORE
    }
    val retryFailure = if (spec["retryFailure"] == "abort") Failure.ABORT else Failure.IGNORE
    val strategy = if (spec["retryStrategy"] == "exponential") RetryStrategy.EXPONENTIAL else RetryStrategy.FIXED
    val parameters = LinkedHashMap<String, String>()
    val rawParams = spec["parameters"]
    if (rawParams is Map<*, *>) {
        for ((key, value) in rawParams) parameters[key.toString()] = value?.toString() ?: ""
    }
    val headers = LinkedHashMap<String, String>()
    val rawHeaders = spec["headers"]
    if (rawHeaders is Map<*, *>) {
        for ((key, value) in rawHeaders) headers[key.toString()] = value?.toString() ?: ""
    }
    val roles = asList(spec["roles"]).map { it.toString() }.ifEmpty { listOf("administrator") }.toSet()
    if (type == "kafka") {
        if ((spec["connection"] as? String).isNullOrBlank()) throw IllegalArgumentException("kafka handler on '$stream' needs a connection")
        if ((spec["topic"] as? String).isNullOrBlank()) throw IllegalArgumentException("kafka handler on '$stream' needs a topic")
        checkClientProperties("kafka handler on '$stream'", stringMap(spec["properties"]))
    }
    return HandlerDef(
        name = spec["name"] as? String ?: "$type-$index",
        type = type,
        enabled = spec["enabled"] as? Boolean ?: true,
        failure = failure,
        retryStrategy = strategy,
        retryCount = (spec["retryCount"] as? Number)?.toInt() ?: 1,
        retryDelayMillis = long(spec["retryDelayMillis"], 1000),
        multiplier = (spec["multiplier"] as? Number)?.toDouble() ?: 2.0,
        maxDelayMillis = long(spec["maxDelayMillis"], 60_000),
        retryFailure = retryFailure,
        loggerName = spec["logger"] as? String ?: "",
        level = spec["level"] as? String ?: "info",
        message = spec["message"] as? String ?: "",
        path = spec["path"] as? String ?: "",
        value = spec["value"] as? String ?: if (type == "kafka") "{event.data}" else "",
        query = spec["query"] as? String ?: "",
        parameters = parameters,
        roles = roles,
        url = spec["url"] as? String ?: "",
        method = (spec["method"] as? String ?: "POST").uppercase(),
        headers = headers,
        body = spec["body"] as? String ?: "{event.data}",
        timeoutMillis = long(spec["timeoutMillis"], 30_000),
        source = spec["source"] as? String ?: "",
        connection = spec["connection"] as? String ?: "",
        topic = spec["topic"] as? String ?: "",
        partition = spec["partition"]?.toString() ?: "",
        timestamp = spec["timestamp"]?.toString() ?: "",
        key = spec["key"] as? String ?: "",
        properties = stringMap(spec["properties"]),
    )
}

private fun long(value: Any?, default: Long): Long = (value as? Number)?.toLong() ?: default

internal fun asList(value: Any?): List<*> = value as? List<*> ?: emptyList<Any>()

@Suppress("UNCHECKED_CAST")
internal fun asMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val out = LinkedHashMap<String, Any?>()
    for ((key, item) in map) out[key.toString()] = item
    return out
}

private fun javaHttp(): HttpSender = HttpSender { method, url, headers, body, timeoutMillis ->
    val client = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(timeoutMillis))
        // SEC-022: the gateway's outbound trust store.
        .sslContext(org.oip.config.OutboundTls.context("event-stream-http"))
        .build()
    val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
        .timeout(java.time.Duration.ofMillis(timeoutMillis))
        .method(method, java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
    headers.forEach { (key, value) -> builder.header(key, value) }
    val response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
    response.statusCode()
}

/** This gateway is a redundancy standby, so it does not accept work that only the active node does. */
class NodeNotActive(message: String = "node is not active") : RuntimeException(message)
