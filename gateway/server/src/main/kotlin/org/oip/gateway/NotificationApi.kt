package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.alarm.notify.NotificationService

/**
 * On-call resolution, schedule state, pipelines in flight, and email acknowledgement links.
 *
 * Reads use `gateway.read` (operator and administrator). Writes of the config kinds use the existing
 * config routes and `gateway.write` (administrator). The email link is the credential for that one event:
 * the recipient opens it without a gateway role.
 */
class NotificationApi(private val notifications: NotificationService) {
    fun register(app: Javalin) {
        app.get("/api/v1/notifications/rosters/{name}/on-call") { ctx ->
            json(ctx, mapOf("roster" to ctx.pathParam("name"), "users" to notifications.onCall(ctx.pathParam("name"))))
        }
        app.get("/api/v1/notifications/schedules/{name}") { ctx ->
            val name = ctx.pathParam("name")
            json(ctx, mapOf("name" to name, "active" to notifications.scheduleActive(name)))
        }
        app.get("/api/v1/notifications/pipelines") { ctx ->
            json(ctx, linkedMapOf(
                "pipelines" to notifications.pipelineCounts().map { mapOf("name" to it.first, "inFlight" to it.second) },
                "events" to notifications.inFlightEvents().map { event ->
                    linkedMapOf(
                        "eventId" to event.eventId,
                        "pipeline" to event.pipeline,
                        "block" to event.block,
                        "alarm" to event.alarm,
                        "displayPath" to event.displayPath,
                        "state" to event.state,
                        "waitingUntil" to event.waitingUntil?.toString(),
                    )
                },
            ))
        }
        app.get("/api/v1/notifications/delivered") { ctx ->
            json(ctx, mapOf("delivered" to notifications.delivered()))
        }
        app.get("/api/v1/alarms/email-ack/{token}") { ctx ->
            val view = notifications.emailAck(ctx.pathParam("token"))
            if (wantsHtml(ctx)) {
                ctx.contentType("text/html; charset=utf-8").result(page(view))
            } else {
                json(ctx, viewJson(view))
            }
        }
        app.post("/api/v1/alarms/email-ack/{token}") { ctx ->
            val notes = notes(ctx)
            notifications.acknowledgeEmail(ctx.pathParam("token"), notes)
            val view = notifications.emailAck(ctx.pathParam("token"))
            if (wantsHtml(ctx)) ctx.contentType("text/html; charset=utf-8").result(page(view))
            else json(ctx, viewJson(view))
        }
    }

    private fun json(ctx: Context, value: Any?) {
        ctx.contentType("application/json").result(Json.write(value))
    }

    private fun wantsHtml(ctx: Context): Boolean {
        if (ctx.queryParam("format") == "json") return false
        val accept = ctx.header("Accept").orEmpty()
        return accept.contains("text/html") && !accept.contains("application/json")
    }

    private fun notes(ctx: Context): String? {
        val type = ctx.header("Content-Type").orEmpty()
        if (type.startsWith("application/x-www-form-urlencoded")) {
            return ctx.formParam("notes")
        }
        if (ctx.body().isBlank()) return null
        val parsed = try {
            Json.parse(ctx.body())
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        return parsed["notes"] as? String
    }

    private fun viewJson(view: org.oip.alarm.notify.EmailAckView): Map<String, Any?> = linkedMapOf(
        "eventId" to view.eventId,
        "username" to view.username,
        "source" to view.source,
        "name" to view.name,
        "displayPath" to view.displayPath,
        "state" to view.state,
        "active" to view.active,
        "acknowledged" to view.acknowledged,
        "notesRequired" to view.notesRequired,
    )

    private fun page(view: org.oip.alarm.notify.EmailAckView): String {
        val notes = if (view.notesRequired) "<p>Notes are required.</p>" else ""
        val form = if (view.acknowledged) {
            "<p>This alarm is acknowledged.</p>"
        } else {
            """<form method="post"><label>Notes <input name="notes"></label><button type="submit">Acknowledge</button></form>"""
        }
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><title>Acknowledge alarm</title></head><body>
            <h1>${esc(view.name)}</h1>
            <p>${esc(view.displayPath)}</p>
            <p>${esc(view.state)}</p>
            $notes
            $form
            </body></html>"""
    }

    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
