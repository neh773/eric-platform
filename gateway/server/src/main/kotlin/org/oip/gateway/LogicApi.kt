package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus

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
        app.get("/api/v1/logic/programs/{name}/udts") { ctx -> json(ctx, attempt { gw.logic.udtView(ctx.pathParam("name")) }) }
        app.get("/api/v1/logic/mapping") { ctx -> json(ctx, gw.logic.mapping()) }
        app.put("/api/v1/logic/mapping") { ctx ->
            attempt { gw.logic.saveMapping(body(ctx)) }
            json(ctx, gw.logic.mapping())
        }
        app.post("/api/v1/logic/deploy") { ctx ->
            val released = gw.logic.forcedNames()
            val result = attempt { gw.logic.deploy(text(ctx, "program")) }
            if (released.isNotEmpty()) audit("plc.force.release", released.joinToString(","), "released on deploy")
            json(ctx, result)
        }
        app.post("/api/v1/logic/start") { ctx ->
            attempt { gw.logic.start() }
            json(ctx, gw.logic.status())
        }
        app.post("/api/v1/logic/stop") { ctx ->
            val released = gw.logic.forcedNames()
            gw.logic.stop()
            if (released.isNotEmpty()) audit("plc.force.release", released.joinToString(","), "released on stop")
            json(ctx, gw.logic.status())
        }
        app.get("/api/v1/logic/status") { ctx -> json(ctx, gw.logic.status()) }
        app.get("/api/v1/logic/watch") { ctx -> json(ctx, gw.logic.watch()) }
        app.post("/api/v1/logic/forces") { ctx ->
            val map = body(ctx)
            val name = map["name"] as? String ?: throw Problems.unprocessable("name is required")
            attempt { gw.logic.force(name, map["value"], map["output"] as? Boolean ?: true) }
            audit("plc.force", name, "forced to ${map["value"]}")
            json(ctx, gw.logic.watch())
        }
        app.delete("/api/v1/logic/forces") { ctx ->
            val released = gw.logic.releaseAll()
            if (released.isNotEmpty()) audit("plc.force.release", released.joinToString(","), "release all")
            json(ctx, gw.logic.watch())
        }
        app.delete("/api/v1/logic/forces/{name}") { ctx ->
            val name = ctx.pathParam("name")
            attempt { gw.logic.unforce(name) }
            audit("plc.force.release", name, "released")
            json(ctx, gw.logic.watch())
        }
        app.post("/api/v1/logic/write") { ctx ->
            val map = body(ctx)
            val name = map["name"] as? String ?: throw Problems.unprocessable("name is required")
            attempt { gw.logic.writeOnce(name, map["value"]) }
            audit("plc.write", name, "wrote ${map["value"]} once")
            json(ctx, gw.logic.watch())
        }
    }

    /** Forces, releases and one-off writes go in the audit log with the signed-in user. */
    private fun audit(action: String, name: String, detail: String) {
        if (!gw.security.auditProfile.get().enabled) return
        val context = AuditContext.current()
        gw.security.audit.append(
            AuditDraft(
                timestampMillis = gw.clock.millis(),
                actor = context?.actor ?: "api",
                action = action,
                status = AuditStatus.SUCCESS,
                actorHost = context?.host ?: "",
                target = "plc/$name",
                detail = detail.take(500),
                context = "gateway-http",
            ),
        )
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
