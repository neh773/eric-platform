package org.oip.sfc

import org.oip.config.SchemaRegistry

const val SFC_MODULE = "sfc"
const val CHART_KIND = "Chart"

private const val NAME = "^[A-Za-z_][A-Za-z0-9_]{0,62}$"

/**
 * Chart resources are config documents. The graph rules that a schema cannot express are checked
 * when the chart is compiled.
 */
fun registerSfcSchemas(registry: SchemaRegistry) {
    registry.register(SFC_MODULE, CHART_KIND, chartSchema())
}

private fun chartSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("elements", "links"),
    "properties" to mapOf(
        "executionMode" to mapOf("type" to "string", "enum" to listOf("callable", "runAlways", "disabled"), "default" to "callable"),
        "persistState" to mapOf("type" to "boolean", "default" to false),
        "redundancySync" to mapOf("type" to "boolean", "default" to false),
        "failover" to mapOf("type" to "string", "enum" to listOf("continue", "cancel", "restart", "goto"), "default" to "continue"),
        "failoverStep" to mapOf("type" to "string", "pattern" to NAME),
        "parameters" to mapOf("type" to "array", "maxItems" to 64, "default" to emptyList<Any>(), "items" to namedValue("default")),
        "variables" to mapOf("type" to "array", "maxItems" to 64, "default" to emptyList<Any>(), "items" to namedValue("initial")),
        "onStart" to actionList(),
        "onStop" to actionList(),
        "onCancel" to actionList(),
        "onAbort" to actionList(),
        "onRedundantFailover" to actionList(),
        "elements" to mapOf("type" to "array", "minItems" to 1, "maxItems" to 256, "items" to mapOf("type" to "object")),
        "links" to linkList(),
    ),
)

private fun namedValue(valueKey: String): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("name"),
    "properties" to mapOf(
        "name" to mapOf("type" to "string", "pattern" to NAME),
        valueKey to emptyMap<String, Any>(),
        "key" to mapOf("type" to "boolean", "default" to false),
    ),
)

private fun actionList(): Map<String, Any?> = mapOf(
    "type" to "array",
    "maxItems" to 32,
    "default" to emptyList<Any>(),
    "items" to mapOf(
        "type" to "object",
        "required" to listOf("type"),
        "properties" to mapOf(
            "type" to mapOf("type" to "string", "enum" to listOf("expression", "script")),
            "assign" to mapOf("type" to "string", "pattern" to NAME),
            "expression" to mapOf("type" to "string", "maxLength" to 8000),
            "source" to mapOf("type" to "string", "maxLength" to 100_000),
        ),
    ),
)

private fun linkList(): Map<String, Any?> = mapOf(
    "type" to "array",
    "maxItems" to 512,
    "items" to mapOf(
        "type" to "object",
        "required" to listOf("from", "to"),
        "properties" to mapOf(
            "from" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 63),
            "to" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 63),
        ),
    ),
)
