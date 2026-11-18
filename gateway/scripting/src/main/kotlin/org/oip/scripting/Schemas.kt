package org.oip.scripting

import org.oip.config.SchemaRegistry

const val SCRIPT_MODULE = "scripting"
const val LIBRARY_KIND = "ScriptModule"
const val EVENT_KIND = "GatewayEvent"
const val TAG_EVENT_KIND = "TagEvent"

private const val MODULE_NAME = "^$|^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$"
private const val CRON = "^[A-Za-z0-9*,/\\- ]{0,200}$"

/**
 * Project library and gateway event scripts are ordinary config resources.
 *
 */
fun registerScriptSchemas(registry: SchemaRegistry) {
    registry.register(SCRIPT_MODULE, LIBRARY_KIND, librarySchema())
    registry.register(SCRIPT_MODULE, EVENT_KIND, eventSchema())
    registry.register(SCRIPT_MODULE, TAG_EVENT_KIND, tagEventSchema())
}

private fun librarySchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("source"),
    "properties" to mapOf(
        "module" to mapOf(
            "type" to "string",
            "pattern" to MODULE_NAME,
            "maxLength" to 200,
            "default" to "",
            "description" to "Python module name. Empty derives it from the resource name (hyphens become underscores).",
        ),
        "source" to mapOf("type" to "string", "minLength" to 0, "maxLength" to 100_000),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
    ),
)

private fun tagEventSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("tagPath", "event", "source"),
    "properties" to mapOf(
        "tagPath" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 512),
        "event" to mapOf("type" to "string", "enum" to listOf("value", "quality", "alarm")),
        "alarmName" to mapOf("type" to "string", "maxLength" to 200, "default" to ""),
        "source" to mapOf("type" to "string", "minLength" to 0, "maxLength" to 100_000),
        "enabled" to mapOf("type" to "boolean", "default" to true),
    ),
)

private fun eventSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("type", "source"),
    "properties" to mapOf(
        "type" to mapOf(
            "type" to "string",
            "enum" to listOf("startup", "shutdown", "timer", "tagChange", "scheduled", "message"),
        ),
        "source" to mapOf("type" to "string", "minLength" to 0, "maxLength" to 100_000),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
        "delayMillis" to mapOf("type" to "integer", "minimum" to 10, "maximum" to 86_400_000, "default" to 1_000),
        "delayType" to mapOf("type" to "string", "enum" to listOf("fixedDelay", "fixedRate"), "default" to "fixedDelay"),
        "threading" to mapOf("type" to "string", "enum" to listOf("shared", "dedicated"), "default" to "shared"),
        "tagPaths" to mapOf(
            "type" to "array",
            "maxItems" to 256,
            "default" to emptyList<Any>(),
            "items" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 512),
        ),
        "triggers" to mapOf(
            "type" to "array",
            "maxItems" to 3,
            "default" to listOf("value", "quality", "timestamp"),
            "items" to mapOf("type" to "string", "enum" to listOf("value", "quality", "timestamp")),
        ),
        "cron" to mapOf("type" to "string", "pattern" to CRON, "default" to ""),
        "timeoutMillis" to mapOf("type" to "integer", "minimum" to 50, "maximum" to 600_000, "default" to 10_000),
        "maxStatements" to mapOf("type" to "integer", "minimum" to 1_000, "maximum" to 1_000_000_000, "default" to 1_000_000),
        "maxAllocatedBytes" to mapOf(
            "type" to "integer",
            "minimum" to 1_000_000,
            "maximum" to 1_073_741_824,
            "default" to 67_108_864,
        ),
    ),
)
