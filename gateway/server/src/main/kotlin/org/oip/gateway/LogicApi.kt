package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context

/** Programs, mapping, compile, deploy, and forces for the Logic editor. */
class LogicApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get("/api/v1/logic/programs") { ctx -> json(ctx, mapOf("items" to gw.logic.list())) }
        app.post("/api/v1/logic/programs") { ctx ->
            val name = text(ctx, "name")
            attempt { gw.logic.create(name) }
            json(ctx, mapOf("name" to name, "source" to gw.logic.read(name)))
        }
        app.get("/api/v1/logic/programs/{name}") { ctx ->
            val name = ctx.pathParam("name")
            json(ctx, mapOf("name" to name, "source" to attempt { gw.logic.read(name) }))
        }
        app.put("/api/v1/logic/programs/{name}") { ctx ->
            attempt { gw.logic.save(ctx.pathParam("name"), text(ctx, "source")) }
            ctx.status(204)
        }
        app.delete("/api/v1/logic/programs/{name}") { ctx ->
            attempt { gw.logic.delete(ctx.pathParam("name")) }
            ctx.status(204)
        }
        app.post("/api/v1/logic/programs/{name}/rename") { ctx ->
            attempt { gw.logic.rename(ctx.pathParam("name"), text(ctx, "name")) }
            ctx.status(204)
        }
        app.post("/api/v1/logic/programs/{name}/check") { ctx ->
            val diagnostics = attempt { gw.logic.check(ctx.pathParam("name")) }
            json(ctx, mapOf("diagnostics" to diagnostics.map { it.toMap() }))
        }
        app.get("/api/v1/logic/mapping") { ctx -> json(ctx, gw.logic.mapping()) }
        app.put("/api/v1/logic/mapping") { ctx ->
            attempt { gw.logic.saveMapping(body(ctx)) }
            json(ctx, gw.logic.mapping())
        }
        app.post("/api/v1/logic/deploy") { ctx -> json(ctx, attempt { gw.logic.deploy(text(ctx, "program")) }) }
        app.post("/api/v1/logic/start") { ctx ->
            attempt { gw.logic.start() }
            json(ctx, gw.logic.status())
        }
        app.post("/api/v1/logic/stop") { ctx ->
            gw.logic.stop()
            json(ctx, gw.logic.status())
        }
        app.get("/api/v1/logic/status") { ctx -> json(ctx, gw.logic.status()) }
        app.get("/api/v1/logic/watch") { ctx -> json(ctx, gw.logic.watch()) }
        app.post("/api/v1/logic/forces") { ctx ->
            val map = body(ctx)
            val name = map["name"] as? String ?: throw Problems.unprocessable("name is required")
            gw.logic.force(name, map["value"], map["output"] as? Boolean ?: true)
            json(ctx, gw.logic.watch())
        }
        app.delete("/api/v1/logic/forces/{name}") { ctx ->
            gw.logic.unforce(ctx.pathParam("name"))
            json(ctx, gw.logic.watch())
        }
    }

    private fun <T> attempt(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw Problems.unprocessable(e.message ?: "rejected")
    } catch (e: java.nio.file.NoSuchFileException) {
        throw Problems.notFound(e.message ?: "not found")
    }

    private fun text(ctx: Context, key: String): String =
        body(ctx)[key] as? String ?: throw Problems.unprocessable("$key is required")

    private fun body(ctx: Context): Map<String, Any?> {
        if (ctx.body().length > 200_000) throw Problems.badRequest("body is too large")
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
