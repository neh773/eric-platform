package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context

/** Read the resource split, preview it, or ask the root helper to apply or revert it. */
class PlcResourcesApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get("/api/v1/plc/resources") { ctx -> json(ctx, gw.plcResources.status()) }
        app.post("/api/v1/plc/resources/preview") { ctx -> json(ctx, gw.plcResources.preview(cores(ctx), ram(ctx), smt(ctx))) }
        app.post("/api/v1/plc/resources/apply") { ctx -> json(ctx, gw.plcResources.apply(cores(ctx), ram(ctx), smt(ctx))) }
        app.post("/api/v1/plc/resources/revert") { ctx -> json(ctx, gw.plcResources.revert()) }
    }

    private fun cores(ctx: Context) = number(ctx, "plcCores")
    private fun ram(ctx: Context) = number(ctx, "plcRamGb")
    private fun smt(ctx: Context): Boolean = body(ctx)["disableSmt"] as? Boolean ?: true

    private fun number(ctx: Context, key: String): Int {
        val value = body(ctx)[key] ?: throw Problems.unprocessable("$key is required")
        val number = (value as? Number)?.toInt() ?: throw Problems.unprocessable("$key must be a number")
        return number
    }

    private fun body(ctx: Context): Map<String, Any?> {
        if (ctx.body().length > 20_000) throw Problems.badRequest("body is too large")
        val parsed = try {
            Json.mapper.readValue(ctx.body().ifBlank { "{}" }, Any::class.java)
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?> ?: throw Problems.badRequest("body must be a JSON object")
    }

    private fun json(ctx: Context, body: Any?) {
        ctx.status(200).contentType("application/json").result(Json.write(body))
    }
}
