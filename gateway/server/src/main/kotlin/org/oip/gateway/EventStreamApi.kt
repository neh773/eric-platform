package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.events.NodeNotActive

/**
 * Event-stream metrics and the HTTP source.
 *
 * Metrics require gateway.read. `/system/eventstream/{name}` is public unless the source sets
 * requireAuthentication. A full buffer responds 429 and the event is not delivered.
 * `POST /api/v1/event-streams/{name}/test` injects one event into any enabled stream (gateway.write).
 */
class EventStreamApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get("/api/v1/event-streams") { ctx -> json(ctx, 200, mapOf("items" to gw.events.streams())) }
        app.get("/api/v1/event-streams/{name}") { ctx -> json(ctx, 200, gw.events.stream(ctx.pathParam("name"))) }
        app.post("/api/v1/event-streams/{name}/listen") { ctx -> listen(ctx) }
        app.post("/api/v1/event-streams/{name}/test") { ctx -> test(ctx) }
        app.post("/system/eventstream/{name}") { ctx -> publish(ctx) }
        app.put("/system/eventstream/{name}") { ctx -> publish(ctx) }
    }

    private fun test(ctx: Context) {
        val name = ctx.pathParam("name")
        val view = gw.events.stream(name)
        if (view["valid"] != true) throw Problems.unprocessable(view["error"]?.toString() ?: "invalid event stream")
        if (view["enabled"] != true) throw Problems.unprocessable("event stream '$name' is disabled")
        if (ctx.body().length > MAX_BODY) throw Problems.badRequest("body is too large")
        val accepted = try {
            gw.events.publishTest(name, payload(ctx))
        } catch (e: NodeNotActive) {
            throw Problems.conflict(e.message ?: "node is not active")
        }
        if (!accepted) throw ProblemException(429, "back-pressure", "Too Many Requests", "event stream buffer is full")
        gw.events.pump()
        json(ctx, 202, mapOf("accepted" to true))
    }

    private fun listen(ctx: Context) {
        val name = ctx.pathParam("name")
        val view = gw.events.stream(name)
        if (view["valid"] != true) throw Problems.unprocessable(view["error"]?.toString() ?: "invalid event stream")
        if (view["source"] != "listener") throw Problems.unprocessable("event stream '$name' is not an event listener")
        if (ctx.body().length > MAX_BODY) throw Problems.badRequest("body is too large")
        val accepted = try {
            gw.events.publishListener(name, payload(ctx))
        } catch (e: NodeNotActive) {
            throw Problems.conflict(e.message ?: "node is not active")
        }
        if (!accepted) throw ProblemException(429, "back-pressure", "Too Many Requests", "event stream buffer is full")
        gw.events.pump()
        json(ctx, 202, mapOf("accepted" to true))
    }

    private fun publish(ctx: Context) {
        val name = ctx.pathParam("name")
        val view = gw.events.stream(name)
        if (view["valid"] != true) throw Problems.unprocessable(view["error"]?.toString() ?: "invalid event stream")
        if (view["source"] != "http") throw Problems.unprocessable("event stream '$name' does not accept HTTP")
        if (view["enabled"] != true) throw Problems.unprocessable("event stream '$name' is disabled")
        if (gw.events.requireHttps(name) && !ctx.scheme().equals("https", ignoreCase = true)) {
            throw Problems.forbidden("https required", "https-required")
        }
        val allowed = gw.events.roles(name)
        if (gw.events.requiresAuthentication(name) && allowed.isNotEmpty()) {
            val roles = GatewayAuth.principal(ctx).roles
            if (allowed.none { it in roles }) throw Problems.forbidden("event stream '$name' is not permitted for this role")
        }
        if (ctx.body().length > MAX_BODY) throw Problems.badRequest("body is too large")
        val accepted = try {
            gw.events.publishHttp(name, payload(ctx), linkedMapOf(
                "source" to "http",
                "method" to ctx.method().name,
                "path" to ctx.path(),
            ))
        } catch (e: NodeNotActive) {
            throw Problems.conflict(e.message ?: "node is not active")
        }
        if (!accepted) {
            throw ProblemException(429, "back-pressure", "Too Many Requests", "event stream buffer is full")
        }
        json(ctx, 202, mapOf("accepted" to true))
    }

    private fun payload(ctx: Context): Any? {
        val raw = ctx.body()
        if (raw.isBlank()) return null
        val type = ctx.contentType()?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type != "application/json") return raw
        return try {
            Json.mapper.readValue(raw, Any::class.java)
        } catch (_: Exception) {
            raw
        }
    }

    private fun json(ctx: Context, status: Int, body: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(body))
    }

    companion object {
        private const val MAX_BODY = 256 * 1024
    }
}
