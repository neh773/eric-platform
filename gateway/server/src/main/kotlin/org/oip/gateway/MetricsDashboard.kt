package org.oip.gateway

import org.oip.config.GATEWAY_MODULE
import org.oip.config.SchemaRegistry

/**
 * User-built metrics dashboards (GAT-024): a config resource `org.oip.gateway/gateway/MetricsDashboard/<name>`
 * that lists gauge and time-series tiles over the metrics `GET /api/v1/diagnostics/metrics` serves. The admin
 * Metrics page reads and writes it through the config API, so a save is validated here and audited like any other
 * config change.
 *
 * - `title`: the dashboard's heading.
 * - `tiles`: up to [MAX_TILES], each `{id, type: gauge | series, metric, title, max?}`. `metric` is a metric id such
 *   as `cpu.system`; an id the gateway does not publish (a module that is not installed) shows as unavailable, it
 *   does not fail the save. `max` fixes a gauge's top of scale; without it the metric's own maximum is used.
 *
 * Doc source: FEATURES GAT-024.
 */
object MetricsDashboard {
    const val KIND = "MetricsDashboard"
    const val MAX_TILES = 24

    fun schema(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "maxLength" to 80, "default" to ""),
            "tiles" to mapOf(
                "type" to "array",
                "maxItems" to MAX_TILES,
                "default" to emptyList<Any?>(),
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("id", "type", "metric"),
                    "additionalProperties" to false,
                    "properties" to mapOf(
                        "id" to mapOf("type" to "string", "pattern" to "^[a-z0-9][a-z0-9-]{0,40}$"),
                        "type" to mapOf("type" to "string", "enum" to listOf("gauge", "series")),
                        "metric" to mapOf("type" to "string", "pattern" to "^[A-Za-z][A-Za-z0-9._-]{0,63}$"),
                        "title" to mapOf("type" to "string", "maxLength" to 80, "default" to ""),
                        "max" to mapOf("type" to "number", "exclusiveMinimum" to 0),
                    ),
                ),
            ),
        ),
    )

    fun semantic(spec: Map<String, Any?>): List<String> {
        val ids = (spec["tiles"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String } ?: return emptyList()
        val dupes = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return if (dupes.isEmpty()) emptyList() else listOf("tile ids must be unique: ${dupes.sorted().joinToString()}")
    }

    fun register(registry: SchemaRegistry) {
        registry.register(GATEWAY_MODULE, KIND, schema())
        registry.registerSemantic(GATEWAY_MODULE, KIND, ::semantic)
    }
}
