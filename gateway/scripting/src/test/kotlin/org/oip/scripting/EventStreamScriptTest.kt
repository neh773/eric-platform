package org.oip.scripting

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.events.EVENTS_MODULE
import org.oip.events.EventStreamEngine
import org.oip.events.STREAM_KIND
import org.oip.events.StreamEvent
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Event-stream filter, transform, handler, and error scripts on the gateway GraalPy runtime.
 *
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class EventStreamScriptTest {
    @TempDir lateinit var tmp: Path

    private val clock = Clock.systemUTC()
    private val tags = TagEngine(clock).also {
        it.configure(TagConfig(path = "Total", type = TagType.MEMORY, dataType = DataType.INT64, value = 0L, valueSet = true))
    }
    private val scripts = ScriptService(tags, null, clock)
    private val logs = mutableListOf<String>()
    private lateinit var config: FileConfigStore
    private lateinit var engine: EventStreamEngine

    @AfterEach fun close() {
        if (::engine.isInitialized) engine.close()
        if (::config.isInitialized) config.close()
        scripts.close()
    }

    @Test fun filterTransformAndHandlerScriptsShapeTheStream() {
        start()
        stream(mapOf(
            "filter" to mapOf("source" to """
                last = state.get('lastEmittedEvent')
                state['seen'] = state.get('seen', 0) + 1
                return event['data']['qty'] > 0 and event.metadata['source'] == 'http'
            """.trimIndent()),
            "transform" to mapOf("source" to """
                def onEventReceived(event, state):
                    state['n'] = state.get('n', 0) + 1
                    return {'qty': event.data['qty'] * 10, 'n': state['n']}
            """.trimIndent()),
            "handlers" to listOf(
                mapOf("type" to "logger", "message" to "{event.data.qty}"),
                mapOf("type" to "script", "source" to """
                    state['total'] = state.get('total', 0) + sum(e['data']['qty'] for e in events)
                    system.tag.writeBlocking(['[default]Total'], [state['total']])
                """.trimIndent()),
            ),
        ))
        for (qty in listOf(1, 0, 2)) engine.publishHttp("s", mapOf("qty" to qty), mapOf("source" to "http"))
        engine.pump()
        assertEquals(listOf("10", "20"), logs)
        assertEquals(30L, (tags.read("[default]Total").value as Number).toLong())
        val view = engine.stream("s")
        assertEquals(1L, view["eventsFiltered"])
        assertEquals(4L, view["handlerExecutions"])
    }

    @Test fun failingScriptsDropTheEventOrRunTheErrorHandler() {
        start()
        stream(mapOf(
            "filter" to mapOf("source" to "return 1 / event['data']"),
            "handlers" to listOf(mapOf("type" to "script", "failure" to "abort", "source" to "raise ValueError('no room')")),
            "errorHandler" to mapOf("source" to "system.tag.writeBlocking(['[default]Total'], [len(error)])"),
        ))
        engine.publishHttp("s", 0, emptyMap())
        engine.publishHttp("s", 4, emptyMap())
        engine.pump()
        assertTrue(logs.any { it.startsWith("filter failed") && it.contains("ZeroDivisionError") }, logs.toString())
        val written = (tags.read("[default]Total").value as Number).toInt()
        assertTrue(written > "no room".length, "error handler wrote $written")
        assertEquals(2L, engine.stream("s")["handlerErrors"])
    }

    @Test fun bytesRoundTripThroughATransform() {
        val host = EventStreamScripts(scripts)
        val state = LinkedHashMap<String, Any?>()
        val out = host.transform("return event['data'][::-1]", StreamEvent(byteArrayOf(1, 2, -1), mapOf("k" to "v")), state)
        assertArrayEquals(byteArrayOf(-1, 2, 1), out as ByteArray)
        val kept = host.filter("state['last'] = event['data']\nreturn True", StreamEvent(mapOf("a" to listOf(1, 2)), emptyMap()), state)
        assertEquals(true, kept)
        assertEquals(mapOf("a" to listOf(1L, 2L)), state["last"])
    }

    private fun start() {
        config = FileConfigStore(tmp.resolve("config"), SchemaRegistry(), clock)
        engine = EventStreamEngine(clock, tags, config, inline = true, scripts = EventStreamScripts(scripts),
            log = { _, _, message -> logs += message })
        engine.attach()
    }

    private fun stream(spec: Map<String, Any?>) {
        val full = LinkedHashMap(spec)
        full["source"] = mapOf("type" to "http")
        full["buffer"] = mapOf("debounceMillis" to 0, "maxWaitMillis" to 0)
        config.put(Scope.GATEWAY, Resource(STREAM_KIND, Metadata("s", EVENTS_MODULE), full), "test")
    }
}
