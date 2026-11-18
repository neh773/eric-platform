package org.oip.events

import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.tags.Level
import org.oip.tags.Quality
import org.oip.tags.QualityCode
import org.oip.tags.TagEngine
import org.oip.tags.expr.EvalContext
import org.oip.tags.expr.Expressions
import org.oip.tags.expr.Value
import java.nio.charset.StandardCharsets

/** Producers for the Kafka handler, keyed by connection name and producer properties. */
fun interface KafkaProducers {
    fun producer(connection: String, properties: Map<String, String>): KafkaProducerClient
}

/**
 * One event stream: encode, filter, transform, buffer, then handlers.
 *
 * Buffer debounce, max wait, and overflow:
 * the queue drops events once it reaches max queue size (0 means unlimited). Dropped events are not forwarded.
 * Handlers run only from [pump], never from [offer], so a tag callback can enqueue and return.
 */
class EventPipeline(
    val name: String,
    private val definition: StreamDefinition,
    var scripts: EventScriptHost = EventScriptHost.NOOP,
    var tags: TagEngine? = null,
    var query: NamedUpdate = NamedUpdate { _, _, _ -> throw IllegalStateException("no database service") },
    var http: HttpSender = HttpSender { _, _, _, _, _ -> throw IllegalStateException("no http sender") },
    var kafka: KafkaProducers = KafkaProducers { _, _ -> throw IllegalStateException("no kafka connection") },
    var gateway: GatewayEvents = GatewayEvents { _, _ -> throw IllegalStateException("no gateway event bus") },
    var log: (level: String, logger: String, message: String) -> Unit = { _, _, _ -> },
) {
    val metrics = StreamMetrics()
    private val queue = ArrayDeque<StreamEvent>()
    private var firstAt: Long? = null
    private var lastAt: Long? = null
    private val filterState = LinkedHashMap<String, Any?>()
    private val transformState = LinkedHashMap<String, Any?>()
    private val handlerStates = HashMap<Int, MutableMap<String, Any?>>()
    private val errorState = LinkedHashMap<String, Any?>()
    private var held: List<StreamEvent?>? = null
    private var heldDone: List<(() -> Unit)?> = emptyList()

    /** Events this stream still holds (queued, plus the batch in progress). Readable from any thread. */
    @Volatile var backlog: Int = 0
        private set
    private var resumeAt: Int = 0
    private var retry: Retry? = null

    /** @return false when the event was dropped or the stream is disabled. */
    fun offer(event: StreamEvent, now: Long): Boolean = try {
        accept(event, now)
    } finally {
        updateBacklog()
    }

    private fun accept(event: StreamEvent, now: Long): Boolean {
        metrics.received++
        if (!definition.enabled) {
            metrics.dropped++
            return false
        }
        val max = definition.buffer.maxQueueSize
        if (max > 0 && queue.size >= max) {
            metrics.dropped++
            if (definition.buffer.overflow == Overflow.DROP_NEWEST) return false
            if (queue.isNotEmpty()) queue.removeFirst()
            if (queue.isEmpty()) firstAt = now
        }
        queue.addLast(event)
        if (firstAt == null) firstAt = now
        lastAt = now
        return true
    }

    fun pump(now: Long) {
        try {
            process(now)
        } finally {
            updateBacklog()
        }
    }

    private fun updateBacklog() {
        backlog = queue.size + (held?.let { it.size - resumeAt } ?: 0)
    }

    private fun process(now: Long) {
        val waiting = retry
        if (waiting != null && now < waiting.notBefore) return
        if (held == null) {
            val batch = take(now) ?: return
            heldDone = batch.map { it.done }
            held = batch.map { prepare(it) }
            resumeAt = 0
        }
        val batch = held ?: return
        var index = resumeAt
        while (index < batch.size) {
            val prepared = batch[index]
            if (prepared != null && !dispatch(prepared, index, now)) return
            heldDone.getOrNull(index)?.let { done -> runCatching { done() } }
            index++
            resumeAt = index
            retry = null
        }
        held = null
        heldDone = emptyList()
        resumeAt = 0
        retry = null
    }

    fun queued(): Int = queue.size

    /** Drop queued and held events without running handlers. */
    fun discard() {
        queue.clear()
        held = null
        heldDone = emptyList()
        resumeAt = 0
        retry = null
        firstAt = null
        lastAt = null
        updateBacklog()
    }

    private fun take(now: Long): List<StreamEvent>? {
        val first = firstAt ?: return null
        val last = lastAt ?: return null
        val buffer = definition.buffer
        val quiet = now - last >= buffer.debounceMillis
        val maxWait = buffer.maxWaitMillis > 0 && now - first >= buffer.maxWaitMillis
        if (!quiet && !maxWait) return null
        if (queue.isEmpty()) return null
        val batch = queue.toList()
        queue.clear()
        firstAt = null
        lastAt = null
        return batch
    }

    private fun prepare(incoming: StreamEvent): StreamEvent? {
        val encoded = incoming.copy(data = encode(definition.encoder, incoming.data))
        val filterStarted = System.nanoTime()
        val keep = try {
            keep(encoded)
        } catch (e: RuntimeException) {
            metrics.filter.record(System.nanoTime() - filterStarted)
            stageFailed("filter", e)
            return null
        }
        metrics.filter.record(System.nanoTime() - filterStarted)
        filterState["lastEvent"] = mapOf("data" to encoded.data, "metadata" to encoded.metadata)
        if (!keep) {
            metrics.filteredOut++
            return null
        }
        filterState["lastEmittedEvent"] = filterState["lastEvent"]
        metrics.emitted++
        val transformStarted = System.nanoTime()
        val transformed = try {
            transform(encoded)
        } catch (e: RuntimeException) {
            metrics.transform.record(System.nanoTime() - transformStarted)
            stageFailed("transform", e)
            return null
        }
        metrics.transform.record(System.nanoTime() - transformStarted)
        transformState["lastEvent"] = mapOf("data" to transformed.data, "metadata" to transformed.metadata)
        return transformed
    }

    /** A filter or transform script that fails drops the event; the stream keeps running. */
    private fun stageFailed(stage: String, error: RuntimeException) {
        metrics.errors++
        log("warn", "oip.events.$name", "$stage failed, event dropped: ${error.message}")
    }

    private fun keep(event: StreamEvent): Boolean {
        val source = definition.filterScript
        if (!source.isNullOrBlank()) {
            scripts.filter(source, event, filterState)?.let { return it }
        }
        val expression = definition.filterExpression ?: return true
        return isTrue(eval(expression, event, filterState))
    }

    private fun transform(event: StreamEvent): StreamEvent {
        val source = definition.transformScript
        if (!source.isNullOrBlank()) {
            val replaced = scripts.transform(source, event, transformState)
            if (replaced === EventScriptHost.NULL_RESULT) return event.copy(data = null)
            if (replaced != null) return event.copy(data = replaced)
        }
        val expression = definition.transformExpression ?: return event
        val value = eval(expression, event, transformState)
        if (value.quality.level == Level.ERROR) return event
        return event.copy(data = value.value)
    }

    /** @return false when processing paused for a retry. */
    private fun dispatch(event: StreamEvent, eventIndex: Int, now: Long): Boolean {
        val handlers = definition.handlers
        var handlerIndex = if (retry?.eventIndex == eventIndex) retry!!.handlerIndex else 0
        while (handlerIndex < handlers.size) {
            val handler = handlers[handlerIndex]
            if (!handler.enabled) { handlerIndex++; continue }
            val timing = metrics.handler(handler.name)
            val started = System.nanoTime()
            try {
                runHandler(handler, handlerIndex, event)
                timing.record(System.nanoTime() - started)
                metrics.handled++
                handlerIndex++
            } catch (e: RuntimeException) {
                timing.record(System.nanoTime() - started)
                when (fail(handler, handlerIndex, eventIndex, event, e, now)) {
                    AfterFailure.CONTINUE -> handlerIndex++
                    AfterFailure.STOP_EVENT -> return true
                    AfterFailure.PAUSE -> return false
                }
            }
        }
        return true
    }

    /**
     * Ignore moves on to the next handler. Abort skips the rest of this event.
     * Retry pauses the batch until the delay elapses, then runs the failed handler again.
     */
    private fun fail(handler: HandlerDef, handlerIndex: Int, eventIndex: Int, event: StreamEvent, error: RuntimeException, now: Long): AfterFailure {
        log("warn", "oip.events.$name", "${handler.name} failed: ${error.message}")
        return when (handler.failure) {
            Failure.IGNORE -> AfterFailure.CONTINUE
            Failure.ABORT -> {
                runError(event, error)
                AfterFailure.STOP_EVENT
            }
            Failure.RETRY -> {
                val attempt = (retry?.attempt ?: 0) + 1
                if (attempt <= handler.retryCount) {
                    val delay = delayMillis(handler, attempt)
                    retry = Retry(handlerIndex, eventIndex, attempt, now + delay)
                    resumeAt = eventIndex
                    AfterFailure.PAUSE
                } else {
                    retry = null
                    if (handler.retryFailure == Failure.ABORT) {
                        runError(event, error)
                        AfterFailure.STOP_EVENT
                    } else AfterFailure.CONTINUE
                }
            }
        }
    }

    private fun delayMillis(handler: HandlerDef, attempt: Int): Long {
        if (handler.retryStrategy == RetryStrategy.FIXED) return handler.retryDelayMillis
        var delay = handler.retryDelayMillis.toDouble()
        repeat(attempt - 1) { delay *= handler.multiplier }
        return delay.toLong().coerceAtMost(handler.maxDelayMillis)
    }

    private fun runError(event: StreamEvent, error: RuntimeException) {
        val source = definition.errorScript
        if (!source.isNullOrBlank()) {
            runCatching { scripts.error(source, event, error.message ?: error.javaClass.simpleName, errorState) }
        }
        metrics.errors++
        log("error", "oip.events.$name", error.message ?: "handler aborted")
    }

    private fun runHandler(handler: HandlerDef, index: Int, event: StreamEvent) {
        when (handler.type) {
            "logger" -> {
                val message = text(handler.message, event)
                log(handler.level, handler.loggerName.ifBlank { "oip.events.$name" }, message)
            }
            "tag" -> {
                val engine = tags ?: throw IllegalStateException("no tag engine")
                val path = text(handler.path, event)
                val value = valueOf(handler.value, event)
                val result = engine.write(path, value)
                if (result.quality.level.ordinal >= Level.BAD.ordinal) {
                    throw IllegalStateException("tag write to $path failed: ${result.quality}")
                }
            }
            "database" -> {
                val params = LinkedHashMap<String, Any?>()
                for ((key, expression) in handler.parameters) params[key] = valueOf(expression, event)
                query.execute(handler.query, params, handler.roles)
            }
            "http" -> {
                val url = text(handler.url, event)
                val body = text(handler.body, event).toByteArray(StandardCharsets.UTF_8)
                val headers = handler.headers.mapValues { text(it.value, event) }
                val status = http.send(handler.method, url, headers, body, handler.timeoutMillis)
                if (status >= 400) throw IllegalStateException("HTTP $status from $url")
            }
            "gateway" -> {
                val handlerName = text(handler.path, event)
                if (handlerName.isBlank()) throw IllegalStateException("gateway handler ${handler.name} has no handler")
                gateway.post(handlerName, text(if (handler.body.isBlank()) "{event.data}" else handler.body, event))
            }
            "script" -> {
                val state = handlerStates.getOrPut(index) { LinkedHashMap() }
                if (!scripts.handle(handler.source, listOf(event), state)) {
                    // Host declined. The event is accepted so a stream can be saved before GraalPy is installed.
                }
            }
            "kafka" -> {
                val topic = text(handler.topic, event)
                if (topic.isBlank()) throw IllegalStateException("kafka handler ${handler.name} has no topic")
                val record = KafkaOutgoing(
                    topic = topic,
                    partition = optionalLong(handler.partition, event, "partition")?.toInt(),
                    timestamp = optionalLong(handler.timestamp, event, "timestamp"),
                    key = if (handler.key.isBlank()) null else bytes(valueOf(handler.key, event)),
                    value = bytes(valueOf(handler.value, event)),
                    headers = handler.headers.mapValues { bytes(valueOf(it.value, event)) },
                )
                kafka.producer(handler.connection, handler.properties).send(record, handler.timeoutMillis)
            }
            else -> throw IllegalStateException("unknown handler ${handler.type}")
        }
    }

    private fun text(expression: String, event: StreamEvent): String = stringify(valueOf(expression, event))

    private fun bytes(value: Any?): ByteArray = value as? ByteArray ?: stringify(value).toByteArray(StandardCharsets.UTF_8)

    private fun optionalLong(expression: String, event: StreamEvent, what: String): Long? {
        if (expression.isBlank()) return null
        return when (val value = valueOf(expression, event)) {
            null -> null
            is Number -> value.toLong()
            else -> value.toString().trim().ifEmpty { return null }.toLongOrNull()
                ?: throw IllegalStateException("kafka $what '$value' is not a number")
        }
    }

    private fun valueOf(expression: String, event: StreamEvent): Any? {
        if ('{' !in expression) return expression
        val value = eval(expression, event, emptyMap())
        if (value.quality.level == Level.ERROR) throw IllegalStateException(value.quality.toString())
        return value.value
    }

    private fun eval(expression: String, event: StreamEvent, state: Map<String, Any?>): Value {
        val root = hashMapOf<String, Any?>(
            "event" to mapOf("data" to event.data, "metadata" to event.metadata),
            "state" to state,
        )
        return Expressions.eval(Expressions.parse(expression), EvalContext(ref = { token ->
            val found = lookup(root, token)
            if (found === MISSING) Value(null, Quality(QualityCode.BAD_NOT_FOUND, token)) else Value(found)
        }))
    }

    companion object {
        private val MAPPER = ObjectMapper()

        fun encode(encoder: String, data: Any?): Any? = when (encoder) {
            "string" -> stringify(data)
            "bytes" -> data as? ByteArray ?: stringify(data).toByteArray(StandardCharsets.UTF_8)
            else -> when (data) {
                is String -> parseJsonOrRaw(data)
                is ByteArray -> parseJsonOrRaw(data.toString(StandardCharsets.UTF_8))
                else -> data
            }
        }

        private fun parseJsonOrRaw(text: String): Any? {
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return runCatching { MAPPER.readValue(trimmed, Any::class.java) }.getOrDefault(text)
            }
            return text
        }
    }
}

data class StreamMetrics(
    var received: Long = 0,
    var dropped: Long = 0,
    var filteredOut: Long = 0,
    var emitted: Long = 0,
    var handled: Long = 0,
    var errors: Long = 0,
    val filter: Timing = Timing(),
    val transform: Timing = Timing(),
) {
    private val handlers = LinkedHashMap<String, Timing>()
    fun handler(name: String): Timing = handlers.getOrPut(name) { Timing() }
    fun handlerView(): Map<String, Map<String, Any?>> = handlers.mapValues { it.value.view() }
}

class Timing {
    var count: Long = 0
    var totalNanos: Long = 0
    var maxNanos: Long = 0
    var lastNanos: Long = 0

    fun record(nanos: Long) {
        count++
        totalNanos += nanos
        if (nanos > maxNanos) maxNanos = nanos
        lastNanos = nanos
    }

    fun view(): Map<String, Any?> = mapOf(
        "count" to count,
        "avgMillis" to if (count == 0L) 0.0 else totalNanos.toDouble() / count / 1_000_000.0,
        "maxMillis" to maxNanos.toDouble() / 1_000_000.0,
        "lastMillis" to lastNanos.toDouble() / 1_000_000.0,
    )
}

enum class Overflow { DROP_OLDEST, DROP_NEWEST }
enum class Failure { IGNORE, ABORT, RETRY }
enum class RetryStrategy { FIXED, EXPONENTIAL }

data class BufferDef(
    val debounceMillis: Long = 0,
    val maxWaitMillis: Long = 0,
    val maxQueueSize: Int = 0,
    val overflow: Overflow = Overflow.DROP_OLDEST,
)

data class HandlerDef(
    val name: String,
    val type: String,
    val enabled: Boolean = true,
    val failure: Failure = Failure.IGNORE,
    val retryStrategy: RetryStrategy = RetryStrategy.FIXED,
    val retryCount: Int = 1,
    val retryDelayMillis: Long = 1000,
    val multiplier: Double = 2.0,
    val maxDelayMillis: Long = 60_000,
    val retryFailure: Failure = Failure.IGNORE,
    val loggerName: String = "",
    val level: String = "info",
    val message: String = "",
    val path: String = "",
    val value: String = "",
    val query: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val roles: Set<String> = setOf("administrator"),
    val url: String = "",
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val timeoutMillis: Long = 30_000,
    val source: String = "",
    val connection: String = "",
    val topic: String = "",
    val partition: String = "",
    val timestamp: String = "",
    val key: String = "",
    val properties: Map<String, String> = emptyMap(),
)

data class StreamDefinition(
    val enabled: Boolean,
    val sourceType: String,
    val tagPaths: List<String>,
    val onValue: Boolean,
    val onQuality: Boolean,
    val onTimestamp: Boolean,
    val qualifiedValue: Boolean,
    val skipInitial: Boolean,
    val requireAuthentication: Boolean,
    val requireHttps: Boolean,
    val roles: Set<String>,
    val encoder: String,
    val filterExpression: String?,
    val filterScript: String?,
    val transformExpression: String?,
    val transformScript: String?,
    val buffer: BufferDef,
    val handlers: List<HandlerDef>,
    val errorScript: String?,
    val kafka: KafkaSourceDef? = null,
)

private enum class AfterFailure { CONTINUE, STOP_EVENT, PAUSE }

private data class Retry(val handlerIndex: Int, val eventIndex: Int, val attempt: Int, val notBefore: Long)

private val MISSING = Any()

private fun lookup(root: Map<String, Any?>, path: String): Any? {
    var current: Any? = root
    for (part in path.split('.')) {
        current = when (val node = current) {
            is Map<*, *> -> if (part in node) node[part] else return MISSING
            else -> return MISSING
        }
    }
    return current
}

private fun isTrue(value: Value): Boolean {
    if (value.quality.level.ordinal > Level.UNCERTAIN.ordinal) return false
    return when (val raw = value.value) {
        null -> false
        is Boolean -> raw
        is Number -> raw.toDouble() != 0.0 && !raw.toDouble().isNaN()
        is String -> raw.isNotEmpty() && !raw.equals("false", true) && raw != "0"
        else -> true
    }
}

internal fun stringify(value: Any?): String = when (value) {
    null -> ""
    is ByteArray -> value.toString(StandardCharsets.UTF_8)
    is Double -> if (value % 1.0 == 0.0 && !value.isInfinite() && !value.isNaN()) value.toLong().toString() else value.toString()
    is Float -> if (value % 1.0f == 0.0f && !value.isInfinite() && !value.isNaN()) value.toLong().toString() else value.toString()
    is Map<*, *>, is List<*> -> ObjectMapper().writeValueAsString(value)
    else -> value.toString()
}
