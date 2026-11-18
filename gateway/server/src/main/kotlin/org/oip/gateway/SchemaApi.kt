package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.UnknownKindException

/**
 * Read-only view of the JSON Schema registry (SPEC §4, draft 2020-12).
 * `GET /api/v1/schemas/{module}/{kind}` requires `gateway.read`.
 * The admin editor builds its form from this document and writes the resource through the config API.
 */
class SchemaApi(private val registry: SchemaRegistry) {
    fun register(app: Javalin) {
        app.get("/api/v1/schemas/{module}/{kind}") { ctx -> get(ctx) }
    }

    private fun get(ctx: Context) {
        val module = ctx.pathParam("module")
        val kind = ctx.pathParam("kind")
        try {
            ResourceId(module, Scope.GATEWAY, kind, "x")
        } catch (e: IllegalArgumentException) {
            throw Problems.notFound(e.message ?: "bad schema path")
        }
        val schema = try {
            registry.schema(module, kind)
        } catch (e: UnknownKindException) {
            throw Problems.notFound(e.message ?: "schema not found")
        }
        ctx.contentType("application/json").result(Json.write(mapOf(
            "module" to module,
            "kind" to kind,
            "schema" to schema,
        )))
    }
}
