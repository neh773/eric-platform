package org.oip.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * How the pipeline treats script stages, independent of the scripting engine.
 */
class ScriptStageTest {
    @Test fun failingFilterOrTransformDropsOnlyThatEvent() {
        val logs = mutableListOf<String>()
        val host = object : EventScriptHost {
            override fun filter(source: String, event: StreamEvent, state: MutableMap<String, Any?>): Boolean {
                if (event.data == "bad-filter") throw IllegalStateException("filter boom")
                return true
            }

            override fun transform(source: String, event: StreamEvent, state: MutableMap<String, Any?>): Any? {
                if (event.data == "bad-transform") throw IllegalStateException("transform boom")
                return "t:${event.data}"
            }
        }
        val pipeline = pipeline(host, logs, filter = "f", transform = "t")
        listOf("a", "bad-filter", "bad-transform", "b").forEach { pipeline.offer(StreamEvent(it, emptyMap()), 0) }
        pipeline.pump(0)
        assertEquals(listOf("filter failed, event dropped: filter boom", "transform failed, event dropped: transform boom", "t:a", "t:b"), logs)
        assertEquals(2L, pipeline.metrics.errors)
        assertEquals(2L, pipeline.metrics.handled)
    }

    @Test fun transformReturningNothingClearsThePayload() {
        val seen = mutableListOf<Any?>()
        val host = object : EventScriptHost {
            override fun transform(source: String, event: StreamEvent, state: MutableMap<String, Any?>): Any = EventScriptHost.NULL_RESULT
            override fun handle(source: String, events: List<StreamEvent>, state: MutableMap<String, Any?>): Boolean {
                seen += events.single().data
                return true
            }
        }
        val pipeline = pipeline(host, mutableListOf(), transform = "t", handlers = listOf(mapOf("type" to "script", "source" to "h")))
        pipeline.offer(StreamEvent("x", emptyMap()), 0)
        pipeline.pump(0)
        assertEquals(1, seen.size)
        assertNull(seen[0])
    }

    @Test fun scriptHandlerStateIsKeptAndErrorScriptGetsTheMessage() {
        val counts = mutableListOf<Any?>()
        val errors = mutableListOf<String>()
        val host = object : EventScriptHost {
            override fun handle(source: String, events: List<StreamEvent>, state: MutableMap<String, Any?>): Boolean {
                if (source == "fail") throw IllegalStateException("handler boom")
                state["n"] = ((state["n"] as? Int) ?: 0) + 1
                counts += state["n"]
                return true
            }

            override fun error(source: String, event: StreamEvent, message: String, state: MutableMap<String, Any?>): Boolean {
                errors += "$source:${event.data}:$message"
                return true
            }
        }
        val pipeline = pipeline(
            host,
            mutableListOf(),
            handlers = listOf(
                mapOf("type" to "script", "source" to "count"),
                mapOf("type" to "script", "source" to "fail", "failure" to "abort"),
            ),
            error = "onError",
        )
        pipeline.offer(StreamEvent(1, emptyMap()), 0)
        pipeline.offer(StreamEvent(2, emptyMap()), 0)
        pipeline.pump(0)
        assertEquals(listOf<Any?>(1, 2), counts)
        assertEquals(listOf("onError:1:handler boom", "onError:2:handler boom"), errors)
    }

    @Test fun stringEncoderDecodesBytePayloads() {
        assertEquals("héllo", EventPipeline.encode("string", "héllo".toByteArray()))
        assertEquals(mapOf("a" to 1), EventPipeline.encode("json", """{"a":1}""".toByteArray()))
    }

    private fun pipeline(
        host: EventScriptHost,
        logs: MutableList<String>,
        filter: String? = null,
        transform: String? = null,
        handlers: List<Map<String, Any?>> = listOf(mapOf("type" to "logger", "message" to "{event.data}")),
        error: String? = null,
    ): EventPipeline {
        val spec = linkedMapOf<String, Any?>("source" to mapOf("type" to "http"), "handlers" to handlers)
        if (filter != null) spec["filter"] = mapOf("source" to filter)
        if (transform != null) spec["transform"] = mapOf("source" to transform)
        if (error != null) spec["errorHandler"] = mapOf("source" to error)
        return EventPipeline("test", compile("test", spec), host) { _, _, message -> logs += message }
    }
}
