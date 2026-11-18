package org.oip.events

import org.oip.config.SchemaRegistry

const val EVENTS_MODULE = "events"
const val STREAM_KIND = "EventStream"

/**
 * Event stream resources.
 */
fun registerEventSchemas(registry: SchemaRegistry) {
    registry.register(EVENTS_MODULE, STREAM_KIND, streamSchema())
    registry.register(KAFKA_MODULE, KAFKA_CONNECTION_KIND, kafkaConnectionSchema())
}

private fun streamSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("source", "handlers"),
    "properties" to mapOf(
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "source" to mapOf(
            "type" to "object",
            "properties" to mapOf("properties" to clientPropertiesSchema()),
            "additionalProperties" to true,
        ),
        "encoder" to mapOf("type" to "string", "enum" to listOf("json", "string", "bytes"), "default" to "json"),
        "filter" to mapOf("type" to "object"),
        "transform" to mapOf("type" to "object"),
        "buffer" to mapOf("type" to "object"),
        "handlers" to mapOf(
            "type" to "array",
            "maxItems" to 32,
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf("properties" to clientPropertiesSchema()),
                "additionalProperties" to true,
            ),
        ),
        "errorHandler" to mapOf("type" to "object"),
    ),
)
