package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context

/**
 * Designer script console. The source runs on the gateway as the signed-in designer, with the gateway
 * `system.*` surface and the project script library (SCR-042). The browser receives the printed output and the `_result` value; it does not run the script.
 */
class DesignerConsole(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.post("/api/v1/designer/console") { ctx -> run(ctx) }
    }

    private fun run(ctx: Context) {
        val body = if (ctx.body().isBlank()) throw Problems.badRequest("body must be a JSON object") else try {
            Json.parse(ctx.body())
        } catch (e: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        val unknown = body.keys - setOf("source")
        if (unknown.isNotEmpty()) throw Problems.badRequest("unknown fields: ${unknown.sorted().joinToString()}")
        val source = body["source"] as? String ?: throw Problems.badRequest("source is required")
        if (source.isBlank()) throw Problems.badRequest("source is required")
        if (source.length > 100_000) throw Problems.badRequest("source is too long")
        val result = gw.scripts.run(source, "designer-console", scope = org.oip.scripting.ScriptScope.DESIGNER)
        json(ctx, linkedMapOf(
            "ok" to result.ok,
            "value" to result.value,
            "output" to result.output,
            "error" to result.error,
            "scope" to "designer",
        ))
    }

    private fun json(ctx: Context, body: Any) {
        ctx.contentType("application/json").result(Json.write(body))
    }
}
