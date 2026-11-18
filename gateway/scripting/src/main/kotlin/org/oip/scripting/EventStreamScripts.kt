package org.oip.scripting

import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.events.EventScriptHost
import org.oip.events.StreamEvent

/**
 * Event-stream filter, transform, handler, and error scripts on the gateway GraalPy runtime.
 *
 * A stage's `source` is either the body of its function or a module that defines the function:
 *  - filter: `onEventReceived(event, state)`; a truthy return keeps the event.
 *  - transform: `onEventReceived(event, state)`; the return value becomes the event payload.
 *  - script handler: `onEventsReceived(events, state)`.
 *  - error handler: `onError(event, error, state)`, where `error` is the failure message.
 *
 * `event` is a dict with `data` and `metadata` (also readable as attributes). `state` is a dict
 * kept between calls of the same stage: the filter's holds `lastEvent` and `lastEmittedEvent`,
 * the transform's holds `lastEvent`, and the script can add its own keys. Values cross the
 * boundary as JSON, so a stored value must be JSON-like; byte payloads arrive as Python `bytes`,
 * and `bytes` returned from a transform become the byte payload.
 *
 * A script that raises, times out, or exceeds its limits throws, so the stream applies its
 * filter/transform drop or the handler's failure setting. Scripts run on the event-stream
 * thread, never under the tag-engine lock (P2-14).
 *
 */
class EventStreamScripts(private val scripts: ScriptService) : EventScriptHost {
    override fun filter(source: String, event: StreamEvent, state: MutableMap<String, Any?>): Boolean {
        val value = run("filter", source, Call.FILTER, mapOf("event" to eventMap(event)), state)
        return value == true
    }

    override fun transform(source: String, event: StreamEvent, state: MutableMap<String, Any?>): Any {
        return run("transform", source, Call.TRANSFORM, mapOf("event" to eventMap(event)), state) ?: EventScriptHost.NULL_RESULT
    }

    override fun handle(source: String, events: List<StreamEvent>): Boolean = handle(source, events, LinkedHashMap())

    override fun handle(source: String, events: List<StreamEvent>, state: MutableMap<String, Any?>): Boolean {
        run("handler", source, Call.HANDLER, mapOf("events" to events.map { eventMap(it) }), state)
        return true
    }

    override fun error(source: String, event: StreamEvent, message: String, state: MutableMap<String, Any?>): Boolean {
        run("error", source, Call.ERROR, mapOf("event" to eventMap(event), "error" to message), state)
        return true
    }

    private fun run(stage: String, source: String, call: Call, args: Map<String, Any?>, state: MutableMap<String, Any?>): Any? {
        val input = LinkedHashMap<String, Any?>(args)
        input["state"] = state
        val result = scripts.runStream(wrap(source, call), json.writeValueAsString(toJson(input)), "event-stream-$stage")
        if (!result.ok) throw IllegalStateException("$stage script failed: ${result.error ?: result.failure ?: "error"}")
        val out = result.value as? Map<*, *> ?: return null
        val newState = fromJson(out["state"]) as? Map<*, *>
        if (newState != null) {
            state.clear()
            for ((key, value) in newState) state[key.toString()] = value
        }
        return fromJson(out["value"])
    }

    private fun eventMap(event: StreamEvent): Map<String, Any?> = mapOf("data" to event.data, "metadata" to event.metadata)

    internal enum class Call(val function: String, val parameters: String, val invoke: String) {
        FILTER("onEventReceived", "event, state", "bool(onEventReceived(_oip_args['event'], _oip_state))"),
        TRANSFORM("onEventReceived", "event, state", "onEventReceived(_oip_args['event'], _oip_state)"),
        HANDLER("onEventsReceived", "events, state", "onEventsReceived(_oip_args['events'], _oip_state)"),
        ERROR("onError", "event, error, state", "onError(_oip_args['event'], _oip_args['error'], _oip_state)"),
    }

    internal companion object {
        private val json = ObjectMapper()
        private const val BYTES = "\$bytes"

        /** The user's function (wrapped when only a body was given), then the decode, call, and encode. */
        fun wrap(source: String, call: Call): String {
            val defines = Regex("^def\\s+${call.function}\\s*\\(", RegexOption.MULTILINE).containsMatchIn(source)
            val user = if (defines) source else {
                val body = source.trimEnd().ifBlank { "pass" }.lines().joinToString("\n") { "    $it" }
                "def ${call.function}(${call.parameters}):\n$body"
            }
            return user + "\n" + """
                |import json as _oip_json
                |class _OipDict(dict):
                |    def __getattr__(self, name):
                |        try:
                |            return self[name]
                |        except KeyError:
                |            raise AttributeError(name)
                |def _oip_in(v):
                |    if isinstance(v, dict):
                |        if len(v) == 1 and "$BYTES" in v:
                |            return bytes(v["$BYTES"])
                |        return _OipDict({k: _oip_in(x) for k, x in v.items()})
                |    if isinstance(v, list):
                |        return [_oip_in(x) for x in v]
                |    return v
                |def _oip_out(v):
                |    if isinstance(v, (bytes, bytearray)):
                |        return {"$BYTES": list(v)}
                |    if isinstance(v, dict):
                |        return {str(k): _oip_out(x) for k, x in v.items()}
                |    if isinstance(v, (list, tuple)):
                |        return [_oip_out(x) for x in v]
                |    return v
                |_oip_args = _oip_in(_oip_json.loads(_oip_stream_json))
                |_oip_state = _oip_args['state']
                |_oip_value = ${call.invoke}
                |_result = {"value": _oip_out(_oip_value), "state": _oip_out(_oip_state)}
            """.trimMargin()
        }

        /** Host values to JSON-safe values. Bytes travel as `{"$bytes": [0..255]}`. */
        fun toJson(value: Any?): Any? = when (value) {
            null, is String, is Boolean, is Number -> value
            is ByteArray -> mapOf(BYTES to value.map { it.toInt() and 0xff })
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to toJson(v) }
            is Iterable<*> -> value.map { toJson(it) }
            is Array<*> -> value.map { toJson(it) }
            else -> value.toString()
        }

        fun fromJson(value: Any?): Any? = when (value) {
            is Map<*, *> -> {
                val raw = value[BYTES]
                if (value.size == 1 && raw is List<*>) ByteArray(raw.size) { (raw[it] as Number).toInt().toByte() }
                else value.entries.associateTo(LinkedHashMap()) { (k, v) -> k.toString() to fromJson(v) }
            }
            is List<*> -> value.map { fromJson(it) }
            else -> value
        }
    }
}
