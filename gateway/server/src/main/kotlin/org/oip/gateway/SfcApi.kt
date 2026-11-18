package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context

/**
 * Chart instances. Chart documents stay config resources.
 *
 * Reading instances requires tags.read. Starting, pausing, resuming, and canceling require tags.write.
 */
class SfcApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get("/api/v1/sfc/charts") { ctx -> json(ctx, 200, mapOf("items" to gw.charts.charts())) }
        app.post("/api/v1/sfc/charts/{name}/start") { ctx -> start(ctx) }
        app.get("/api/v1/sfc/instances") { ctx -> json(ctx, 200, mapOf("items" to gw.charts.instances())) }
        app.get("/api/v1/sfc/instances/{id}") { ctx -> json(ctx, 200, gw.charts.instance(ctx.pathParam("id"))) }
        app.post("/api/v1/sfc/instances/{id}/pause") { ctx ->
            val id = ctx.pathParam("id")
            gw.charts.pause(id)
            json(ctx, 200, gw.charts.instance(id))
        }
        app.post("/api/v1/sfc/instances/{id}/resume") { ctx ->
            val id = ctx.pathParam("id")
            gw.charts.resume(id)
            json(ctx, 200, gw.charts.instance(id))
        }
        app.post("/api/v1/sfc/instances/{id}/cancel") { ctx ->
            val id = ctx.pathParam("id")
            gw.charts.cancel(id)
            json(ctx, 200, gw.charts.instance(id))
        }
    }

    private fun start(ctx: Context) {
        val name = ctx.pathParam("name")
        val id = gw.charts.startChart(name, parameters(ctx))
        ctx.header("Location", "/api/v1/sfc/instances/$id")
        json(ctx, 201, gw.charts.instance(id))
    }

    private fun parameters(ctx: Context): Map<String, Any?> {
        if (ctx.body().isBlank()) return emptyMap()
        val body = jsonObject(ctx)
        return when (val raw = body["parameters"]) {
            null -> emptyMap()
            is Map<*, *> -> {
                val out = linkedMapOf<String, Any?>()
                for ((key, value) in raw) {
                    val name = key as? String ?: throw Problems.badRequest("parameter names must be strings")
                    out[name] = value
                }
                out
            }
            else -> throw Problems.badRequest("parameters must be an object")
        }
    }

    private fun json(ctx: Context, status: Int, body: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(body))
    }

    private fun jsonObject(ctx: Context): Map<String, Any?> {
        if (ctx.body().length > MAX_BODY) throw Problems.badRequest("body is too large")
        val parsed = try {
            Json.mapper.readValue(ctx.body(), Any::class.java)
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?> ?: throw Problems.badRequest("body must be a JSON object")
    }

    companion object {
        private const val MAX_BODY = 256 * 1024
    }
}
