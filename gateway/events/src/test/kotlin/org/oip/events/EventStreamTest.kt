package org.oip.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Event-stream pipeline: filter, transform, buffer, handlers, and back-pressure.
 */
class EventStreamTest {
    @TempDir lateinit var dir: Path

    @Test fun filterTransformAndLoggerUseTheEventPayload() {
        val logs = mutableListOf<String>()
        val pipeline = pipeline(
            filter = "{event.data.value} > 10",
            transform = "{event.data.value} * 2",
            handlers = listOf(logger("{event.data}")),
            log = logs,
        )
        assertTrue(pipeline.offer(event(mapOf("value" to 5)), 0))
        assertTrue(pipeline.offer(event(mapOf("value" to 15)), 0))
        pipeline.pump(0)
        assertEquals(listOf("30"), logs)
        assertEquals(1, pipeline.metrics.filteredOut)
        assertEquals(1, pipeline.metrics.emitted)
        assertEquals(1, pipeline.metrics.handled)
        assertTrue(pipeline.metrics.filter.count >= 1)
        assertTrue(pipeline.metrics.filter.lastNanos >= 0)
    }

    @Test fun debounceHoldsABatchUntilTheQuietPeriodAndMaxWaitFlushesEarly() {
        val logs = mutableListOf<String>()
        val debounced = pipeline(
            handlers = listOf(logger("{event.data.n}")),
            debounce = 100,
            maxWait = 1000,
            log = logs,
        )
        debounced.offer(event(mapOf("n" to "a")), 0)
        debounced.offer(event(mapOf("n" to "b")), 50)
        debounced.pump(50)
        assertTrue(logs.isEmpty())
        debounced.pump(150)
        assertEquals(listOf("a", "b"), logs)

        val forced = pipeline(
            handlers = listOf(logger("{event.data.n}")),
            debounce = 5_000,
            maxWait = 200,
            log = logs,
        )
        logs.clear()
        forced.offer(event(mapOf("n" to "c")), 0)
        forced.pump(199)
        assertTrue(logs.isEmpty())
        forced.pump(200)
        assertEquals(listOf("c"), logs)
    }

    @Test fun fullBufferDropsOldestOrNewestAndDoesNotDeliverThem() {
        val oldest = mutableListOf<String>()
        val dropOld = pipeline(
            handlers = listOf(logger("{event.data.n}")),
            debounce = 1_000,
            maxQueue = 2,
            overflow = "dropOldest",
            log = oldest,
        )
        repeat(4) { dropOld.offer(event(mapOf("n" to (it + 1).toString())), 0) }
        assertEquals(2, dropOld.metrics.dropped)
        assertEquals(2, dropOld.queued())
        dropOld.pump(1_000)
        assertEquals(listOf("3", "4"), oldest)

        val newest = mutableListOf<String>()
        val dropNew = pipeline(
            handlers = listOf(logger("{event.data.n}")),
            debounce = 1_000,
            maxQueue = 2,
            overflow = "dropNewest",
            log = newest,
        )
        assertTrue(dropNew.offer(event(mapOf("n" to "1")), 0))
        assertTrue(dropNew.offer(event(mapOf("n" to "2")), 0))
        assertFalse(dropNew.offer(event(mapOf("n" to "3")), 0))
        assertFalse(dropNew.offer(event(mapOf("n" to "4")), 0))
        assertEquals(2, dropNew.metrics.dropped)
        dropNew.pump(1_000)
        assertEquals(listOf("1", "2"), newest)
    }

    @Test fun retryWaitsForTheClockThenAbortSkipsLaterHandlers() {
        var calls = 0
        val retried = pipeline(
            handlers = listOf(script("once", "retry", 100)),
            scripts = object : EventScriptHost {
                override fun handle(source: String, events: List<StreamEvent>): Boolean {
                    calls++
                    if (calls == 1) throw IllegalStateException("not yet")
                    return true
                }
            },
        )
        retried.offer(event(mapOf("n" to 1)), 0)
        retried.pump(0)
        assertEquals(1, calls)
        assertEquals(0, retried.metrics.handled)
        retried.pump(99)
        assertEquals(1, calls)
        retried.pump(100)
        assertEquals(2, calls)
        assertEquals(1, retried.metrics.handled)

        val logs = mutableListOf<String>()
        val aborted = pipeline(
            handlers = listOf(script("boom", "abort", 0), logger("later")),
            scripts = object : EventScriptHost {
                override fun handle(source: String, events: List<StreamEvent>): Boolean {
                    throw IllegalStateException("abort")
                }
            },
            log = logs,
        )
        aborted.offer(event(mapOf("n" to 1)), 0)
        aborted.pump(0)
        assertFalse(logs.contains("later"))
        assertEquals(1, aborted.metrics.errors)
    }

    @Test fun databaseAndHttpHandlersBindExpressions() {
        val updates = mutableListOf<Map<String, Any?>>()
        val sent = mutableListOf<String>()
        val pipeline = pipeline(
            handlers = listOf(
                mapOf(
                    "type" to "database",
                    "name" to "insert",
                    "query" to "add-row",
                    "parameters" to mapOf("n" to "{event.data.n}"),
                    "roles" to listOf("administrator"),
                ),
                mapOf(
                    "type" to "http",
                    "name" to "post",
                    "url" to "http://example.test/hook",
                    "method" to "POST",
                    "body" to "{event.data.n}",
                ),
            ),
            query = { _, params, roles ->
                assertEquals(setOf("administrator"), roles)
                updates += params
                1
            },
            http = { _, _, _, body, _ ->
                sent += body.toString(Charsets.UTF_8)
                204
            },
        )
        pipeline.offer(event(mapOf("n" to 4)), 0)
        pipeline.pump(0)
        assertEquals(listOf(mapOf("n" to 4)), updates)
        assertEquals(listOf("4"), sent)
        assertEquals(2, pipeline.metrics.handled)
    }

    @Test fun tagListenerDoesNotRunHandlersUntilPump() {
        val clock = ManualClock(Instant.parse("2026-01-01T00:00:00Z"))
        val tags = TagEngine(clock)
        tags.configure(TagConfig(path = "Go", type = TagType.MEMORY, dataType = DataType.BOOLEAN, value = false, valueSet = true))
        val config = FileConfigStore(dir.resolve("config"), SchemaRegistry(), clock)
        val logs = mutableListOf<String>()
        var ranOnWrite = false
        val engine = EventStreamEngine(clock, tags, config, inline = true, log = { _, _, message ->
            ranOnWrite = true
            logs += message
        })
        engine.attach()
        config.put(Scope.GATEWAY, Resource(STREAM_KIND, Metadata("go", EVENTS_MODULE), mapOf(
            "source" to mapOf("type" to "tag", "paths" to listOf("[default]Go"), "qualifiedValue" to true),
            "buffer" to mapOf("debounceMillis" to 0, "maxWaitMillis" to 0),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "{event.data.value}")),
        )), "test")
        ranOnWrite = false
        tags.write("[default]Go", true)
        assertFalse(ranOnWrite)
        assertEquals(0L, engine.stream("go")["handlerExecutions"])
        engine.pump()
        assertEquals(listOf("true"), logs)
        assertEquals(1L, engine.stream("go")["eventsReceived"])
        assertEquals(1L, engine.stream("go")["handlerExecutions"])
        val rejected = config.put(Scope.GATEWAY, Resource(STREAM_KIND, Metadata("wild", EVENTS_MODULE), mapOf(
            "source" to mapOf("type" to "tag", "paths" to listOf("[default]Tank/*")),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "x")),
        )), "test")
        assertEquals(rejected.metadata.name, "wild")
        assertEquals(false, engine.stream("wild")["valid"])
        engine.close()
        config.close()
    }

    @Test fun httpSourceReportsBackPressure() {
        val clock = ManualClock(Instant.parse("2026-01-01T00:00:00Z"))
        val config = FileConfigStore(dir.resolve("http"), SchemaRegistry(), clock)
        val logs = mutableListOf<String>()
        val engine = EventStreamEngine(clock, null, config, inline = true, log = { _, _, message -> logs += message })
        engine.attach()
        config.put(Scope.GATEWAY, Resource(STREAM_KIND, Metadata("in", EVENTS_MODULE), mapOf(
            "source" to mapOf("type" to "http"),
            "buffer" to mapOf("debounceMillis" to 10_000, "maxWaitMillis" to 10_000, "maxQueueSize" to 1, "overflow" to "dropNewest"),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "{event.data}")),
        )), "test")
        assertTrue(engine.publishHttp("in", "one", mapOf("source" to "http")))
        assertFalse(engine.publishHttp("in", "two", mapOf("source" to "http")))
        assertEquals(1L, engine.stream("in")["eventsDropped"])
        clock.advance(10_000)
        engine.pump()
        assertEquals(listOf("one"), logs)
        engine.close()
        config.close()
    }

    @Test fun aListenerSourceReachesAGatewayEventHandler() {
        val posted = mutableListOf<Pair<String, String>>()
        val pipeline = pipeline(
            handlers = listOf(mapOf("type" to "gateway", "path" to "heard", "body" to "{event.data}")),
        )
        pipeline.gateway = GatewayEvents { handler, payload -> posted += handler to payload }
        assertTrue(pipeline.offer(event(mapOf("n" to 4)), 0))
        pipeline.pump(0)
        assertEquals(listOf("heard" to "{\"n\":4}"), posted)
    }

    @Test fun listenerSourceRejectsHttpAndReportsConnected() {
        val clock = ManualClock(Instant.parse("2026-01-01T00:00:00Z"))
        val config = FileConfigStore(dir.resolve("listen"), SchemaRegistry(), clock)
        val logs = mutableListOf<String>()
        val engine = EventStreamEngine(clock, null, config, inline = true, log = { _, _, message -> logs += message })
        engine.attach()
        config.put(Scope.GATEWAY, Resource(STREAM_KIND, Metadata("ear", EVENTS_MODULE), mapOf(
            "source" to mapOf("type" to "listener"),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "{event.data}")),
        )), "test")
        assertEquals(true, engine.stream("ear")["sourceConnected"])
        assertTrue(engine.publishListener("ear", "ping"))
        engine.pump()
        assertEquals(listOf("ping"), logs)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            engine.publishHttp("ear", "no", emptyMap())
        }
        engine.close()
        config.close()
    }

    private fun pipeline(
        handlers: List<Map<String, Any?>>,
        filter: String? = null,
        transform: String? = null,
        debounce: Long = 0,
        maxWait: Long = 0,
        maxQueue: Int = 0,
        overflow: String = "dropOldest",
        log: MutableList<String> = mutableListOf(),
        scripts: EventScriptHost = EventScriptHost.NOOP,
        query: NamedUpdate = NamedUpdate { _, _, _ -> 0 },
        http: HttpSender = HttpSender { _, _, _, _, _ -> 200 },
    ): EventPipeline {
        val spec = linkedMapOf<String, Any?>(
            "source" to mapOf("type" to "http"),
            "buffer" to mapOf(
                "debounceMillis" to debounce,
                "maxWaitMillis" to maxWait,
                "maxQueueSize" to maxQueue,
                "overflow" to overflow,
            ),
            "handlers" to handlers,
        )
        if (filter != null) spec["filter"] = mapOf("expression" to filter)
        if (transform != null) spec["transform"] = mapOf("expression" to transform)
        val definition = compile("test", spec)
        return EventPipeline("test", definition, scripts, null, query, http) { _, _, message -> log += message }
    }

    private fun event(data: Any?) = StreamEvent(data, mapOf("source" to "test"))

    private fun logger(message: String) = mapOf("type" to "logger", "name" to "log", "message" to message)

    private fun script(name: String, failure: String, delay: Long) = mapOf(
        "type" to "script",
        "name" to name,
        "source" to name,
        "failure" to failure,
        "retryCount" to 1,
        "retryDelayMillis" to delay,
    )
}

private class ManualClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    override fun millis(): Long = now.toEpochMilli()
    fun advance(millis: Long) { now = now.plusMillis(millis) }
}
