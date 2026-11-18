package org.oip.events

/**
 * Filter, transform, script-handler, and error-handler bodies. The gateway supplies the GraalPy host
 * (`org.oip.scripting.EventStreamScripts`). This module does not embed a scripting engine.
 *
 *
 * A null filter or transform result means the host did not run the script, and the engine uses the
 * expression on that stage when one is configured. [handle] returns false when the host skips the body.
 * A host that runs a script which fails throws a [RuntimeException]: a failing filter or transform drops
 * the event and counts an error, and a failing handler goes through that handler's failure setting.
 */
interface EventScriptHost {
    fun filter(source: String, event: StreamEvent, state: MutableMap<String, Any?>): Boolean? = null
    fun transform(source: String, event: StreamEvent, state: MutableMap<String, Any?>): Any? = null
    fun handle(source: String, events: List<StreamEvent>): Boolean = false

    /** Script handler with its own [state], kept across calls for the life of the stream definition. */
    fun handle(source: String, events: List<StreamEvent>, state: MutableMap<String, Any?>): Boolean = handle(source, events)

    /** Error handler, run when a handler set to abort fails. [message] describes the failure. */
    fun error(source: String, event: StreamEvent, message: String, state: MutableMap<String, Any?>): Boolean =
        handle(source, listOf(event))

    companion object {
        val NOOP: EventScriptHost = object : EventScriptHost {}

        /** A transform script ran and returned nothing: the event payload becomes null. */
        val NULL_RESULT: Any = object {
            override fun toString() = "null"
        }
    }
}

/**
 * One source event after encoding. [data] is the payload; [metadata] describes the source.
 * [done] runs once the stream has finished with the event: every handler ran (or the failure setting
 * moved on), or the filter dropped it. It does not run for an event that is discarded or dropped by the
 * buffer. The Kafka source uses it to commit offsets only for handled records.
 */
data class StreamEvent(val data: Any?, val metadata: Map<String, Any?>, val done: (() -> Unit)? = null)

/**
 * Gateway-side named update. The server wires this to [org.oip.db.DatabaseService.execute].
 * Event streams do not open JDBC connections themselves.
 */
fun interface NamedUpdate {
    fun execute(name: String, parameters: Map<String, Any?>, roles: Set<String>): Int
}

/** Posts one payload to a gateway message handler. The handler name is the chart's `path`. */
fun interface GatewayEvents {
    fun post(handler: String, payloadJson: String)
}

/** Outbound HTTP used by the HTTP handler. Tests substitute a fake. */
fun interface HttpSender {
    /** @return HTTP status, or throws when the call fails. */
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray, timeoutMillis: Long): Int
}
