package org.oip.eam

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.ValidationException
import org.oip.gateway.Gateway
import org.oip.gateway.GatewayAuth
import org.oip.gateway.Json
import org.oip.gateway.Problems
import org.oip.security.AuditContext
import org.oip.security.Permissions

/**
 * `/api/v1/eam`: enterprise administration. Reads need gateway.read and changes gateway.write ([GatewayAuth]).
 * Backup and restore tasks, and agent recovery, also need backups.admin, as local backups do.
 *
 * - `GET /api/v1/eam`, `PUT /api/v1/eam/settings` (role, controller connection, thresholds: EAM-014, EAM-017)
 * - `POST /api/v1/eam/check-in` (an agent checks in now)
 * - `GET /api/v1/eam/agents`, `PUT|DELETE /api/v1/eam/agents/{name}`, `POST .../approve`, `POST .../recover`,
 *   `GET .../backups` (EAM-001, EAM-005, EAM-006, EAM-007)
 * - `GET /api/v1/eam/groups`, `GET /api/v1/eam/inventory` (`?format=csv`, EAM-010), `GET /api/v1/eam/events`
 * - `GET|POST /api/v1/eam/tasks`, `DELETE /api/v1/eam/tasks/{id}`, `POST /api/v1/eam/tasks/{id}/run` (EAM-003, EAM-009)
 * - `GET /api/v1/eam/runs`, `POST /api/v1/eam/runs/{id}/retry`, `POST /api/v1/eam/runs/{id}/cancel`
 * - `GET|POST /api/v1/eam/payloads` (module and upgrade packages, EAM-004, EAM-011)
 * Doc source: FEATURES EAM rows (EAM-001 to EAM-017).
 */
class EamApi(private val gw: Gateway) {
    private val eam get() = gw.eam
    private val controller get() = gw.eam.controller

    fun register(app: Javalin) {
        app.get(BASE) { ctx -> json(ctx, eam.statusDocument()) }
        app.put("$BASE/settings") { ctx ->
            val body = body(ctx)
            val current = eam.settings()
            val thresholds = (body["thresholds"] as? Map<*, *>)?.let { EamThresholds.fromSpec(it) } ?: current.thresholds
            val next = EamSettings(
                role = runCatching { EamRole.parse(body["role"] as? String ?: current.role.label) }.getOrElse { throw Problems.unprocessable(it.message!!) },
                controller = body["controller"] as? String ?: current.controller,
                backupController = body["backupController"] as? String ?: current.backupController,
                group = body["group"] as? String ?: current.group,
                checkInMillis = (body["checkInMillis"] as? Number)?.toLong() ?: current.checkInMillis,
                autoApprove = body["autoApprove"] as? Boolean ?: current.autoApprove,
                keepBackups = (body["keepBackups"] as? Number)?.toInt() ?: current.keepBackups,
                thresholds = thresholds,
            )
            validated { eam.updateSettings(next, actor()) }
            json(ctx, eam.statusDocument())
        }
        app.post("$BASE/check-in") { ctx ->
            if (eam.settings().role != EamRole.AGENT) throw Problems.conflict("this gateway is not an agent")
            eam.agent.checkInOnce()
            json(ctx, eam.statusDocument())
        }
        app.get("$BASE/agents") { ctx ->
            json(ctx, mapOf("agents" to controller.agentViews(), "pendingCertificates" to eam.pendingCertificates()))
        }
        app.put("$BASE/agents/{name}") { ctx ->
            val name = ctx.pathParam("name")
            val record = controller.agent(name) ?: throw Problems.notFound("agent '$name' not found")
            val body = body(ctx)
            val next = record.copy(
                group = body["group"] as? String ?: record.group,
                maintenance = body["maintenance"] as? Boolean ?: record.maintenance,
                description = body["description"] as? String ?: record.description,
            )
            validated { controller.putAgent(next, actor()) }
            if (next.maintenance != record.maintenance) controller.event(name, if (next.maintenance) "maintenance-on" else "maintenance-off", "by ${actor()}")
            json(ctx, agentView(name))
        }
        app.delete("$BASE/agents/{name}") { ctx ->
            mapped { controller.removeAgent(ctx.pathParam("name"), actor()) }
            json(ctx, mapOf("removed" to ctx.pathParam("name")))
        }
        app.post("$BASE/agents/{name}/approve") { ctx ->
            val name = ctx.pathParam("name")
            mapped { controller.approve(name, actor()) }
            json(ctx, agentView(name))
        }
        app.post("$BASE/agents/{name}/recover") { ctx ->
            GatewayAuth.requirePermission(ctx, gw.security.authorization, Permissions.BACKUPS_ADMIN)
            val body = if (ctx.body().isBlank()) emptyMap() else body(ctx)
            json(ctx, mapped { controller.recover(ctx.pathParam("name"), actor(), body["backup"] as? String) })
        }
        app.get("$BASE/agents/{name}/backups") { ctx ->
            json(ctx, mapOf("backups" to controller.backups(ctx.pathParam("name"))))
        }
        app.get("$BASE/groups") { ctx -> json(ctx, mapOf("groups" to controller.groups())) }
        app.get("$BASE/inventory") { ctx ->
            if (ctx.queryParam("format") == "csv") {
                ctx.contentType("text/csv; charset=utf-8")
                ctx.header("Content-Disposition", "attachment; filename=\"agent-inventory.csv\"")
                ctx.result(controller.inventoryCsv())
            } else {
                json(ctx, mapOf("agents" to controller.inventory()))
            }
        }
        app.get("$BASE/events") { ctx ->
            json(ctx, mapOf("events" to controller.events(ctx.queryParam("agent"), ctx.queryParam("limit")?.toIntOrNull() ?: 200)))
        }
        app.get("$BASE/tasks") { ctx -> json(ctx, mapOf("tasks" to controller.tasks().map { mapOf("id" to it.id) + it.toSpec() })) }
        app.post("$BASE/tasks") { ctx ->
            val body = body(ctx)
            val id = body["id"] as? String ?: throw Problems.unprocessable("id is required")
            val spec = body - "id" - "runNow"
            val def = try {
                EamTaskDef.fromSpec(id, spec)
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable(e.message ?: "invalid task")
            }
            requireFor(ctx, def.kind)
            val runNow = body["runNow"] as? Boolean ?: def.schedule.isBlank()
            val runs = validated { controller.putTask(def, actor(), runNow) }
            ctx.status(201)
            json(ctx, mapOf("task" to mapOf("id" to def.id) + def.toSpec(), "runs" to runs.map { it.view() }), status = 201)
        }
        app.delete("$BASE/tasks/{id}") { ctx ->
            mapped { controller.removeTask(ctx.pathParam("id")) }
            json(ctx, mapOf("removed" to ctx.pathParam("id")))
        }
        app.post("$BASE/tasks/{id}/run") { ctx ->
            val def = controller.task(ctx.pathParam("id")) ?: throw Problems.notFound("task '${ctx.pathParam("id")}' not found")
            requireFor(ctx, def.kind)
            json(ctx, mapOf("runs" to controller.fire(def).map { it.view() }))
        }
        app.get("$BASE/runs") { ctx ->
            json(ctx, mapOf("runs" to controller.runs(ctx.queryParam("agent"), ctx.queryParam("task"), ctx.queryParam("limit")?.toIntOrNull() ?: 200)))
        }
        app.post("$BASE/runs/{id}/retry") { ctx -> json(ctx, mapped { controller.retry(ctx.pathParam("id")) }) }
        app.post("$BASE/runs/{id}/cancel") { ctx -> json(ctx, mapped { controller.cancel(ctx.pathParam("id")) }) }
        app.get("$BASE/payloads") { ctx -> json(ctx, mapOf("payloads" to controller.payloads())) }
        app.post("$BASE/payloads") { ctx ->
            GatewayAuth.requirePermission(ctx, gw.security.authorization, Permissions.MODULES_ADMIN)
            val bytes = ctx.bodyAsBytes()
            json(ctx, mapped { controller.storePayload(bytes, ctx.queryParam("label") ?: "package") }, status = 201)
        }
    }

    private fun requireFor(ctx: Context, kind: EamTaskKind) {
        when (kind) {
            EamTaskKind.BACKUP, EamTaskKind.RESTORE -> GatewayAuth.requirePermission(ctx, gw.security.authorization, Permissions.BACKUPS_ADMIN)
            EamTaskKind.MODULE_INSTALL, EamTaskKind.UPGRADE -> GatewayAuth.requirePermission(ctx, gw.security.authorization, Permissions.MODULES_ADMIN)
            else -> Unit
        }
    }

    private fun agentView(name: String): Map<String, Any?> =
        controller.agentViews().firstOrNull { it["name"] == name } ?: throw Problems.notFound("agent '$name' not found")

    private fun actor(): String = AuditContext.current()?.actor ?: "api"

    private fun <T> validated(body: () -> T): T = try {
        body()
    } catch (e: ValidationException) {
        throw Problems.unprocessable(e.message ?: "invalid enterprise administration resource")
    } catch (e: IllegalArgumentException) {
        throw Problems.unprocessable(e.message ?: "invalid request")
    }

    private fun <T> mapped(body: () -> T): T = try {
        body()
    } catch (e: NoSuchElementException) {
        throw Problems.notFound(e.message ?: "not found")
    } catch (e: IllegalStateException) {
        throw Problems.conflict(e.message ?: "conflict")
    } catch (e: IllegalArgumentException) {
        throw Problems.unprocessable(e.message ?: "invalid request")
    }

    private fun body(ctx: Context): Map<String, Any?> = try {
        Json.parse(ctx.body())
    } catch (_: Exception) {
        throw Problems.badRequest("body must be a JSON object")
    }

    private fun json(ctx: Context, value: Any?, status: Int = 200) {
        ctx.status(status)
        ctx.contentType("application/json").result(Json.write(value))
    }

    companion object {
        const val BASE = "/api/v1/eam"
    }
}
