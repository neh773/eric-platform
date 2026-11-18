package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.report.render.ComponentCatalog
import org.oip.report.render.LayoutException
import org.oip.report.render.render
import org.oip.report.render.validateLayout
import org.oip.reporting.ReportCaller
import org.oip.reporting.ReportDefinition
import org.oip.reporting.ReportDisabled
import org.oip.reporting.ReportInvalid
import org.oip.reporting.ReportNotFound
import org.oip.reporting.ReportParameterException
import org.oip.reporting.ReportResult
import org.oip.reporting.ReportRunException
import org.oip.reporting.ReportService
import org.oip.reporting.RunHistory
import org.oip.reporting.RunTrigger
import org.oip.security.Subject
import java.nio.file.Files

/**
 * Report routes (M5.3). Definitions are `reporting/gateway/Report/<name>` config resources, created and edited
 * through `/api/v1/config` like every other resource. These routes run reports and read their history:
 *
 *  - `POST /api/v1/reports/{name}/run` `{parameters?: {..}}`: run now; 200 with the report result (contract v1,
 *    `docs/ops/report-contract.md`).
 *  - `GET /api/v1/reports/{name}/runs?limit=`: run records, newest first.
 *  - `GET /api/v1/reports/{name}/status`, `GET /api/v1/reports/status`: schedule and last-run status.
 *  - `POST /api/v1/reports/{name}/pdf` `{parameters?: {..}}`: run now like `/run` and answer with the rendered PDF
 *    (C-9b `report-render`); a layout that can't render is refused (422) before the run.
 *  - `GET /api/v1/reports/{name}/files`, `GET /api/v1/reports/{name}/files/{file}`: PDFs of scheduled runs, kept by
 *    [Gateway.reportArchive] (the default scheduler sink). These were run as `system`, so they need `gateway.read`.
 *
 *  - `GET /api/v1/reports/components`: the component catalog (types, properties, defaults) the Designer's report
 *    workspace builds its palette from (doc source: FEATURES REP-013).
 *  - `POST /api/v1/reports/preview` `{spec, parameters?}` and `POST /api/v1/reports/preview/pdf`: run and render a
 *    draft definition that is not saved yet, with the engine and renderer a saved report uses (FEATURES REP-028).
 *    A draft can name any SQL or named query, so these need `gateway.write`, like saving the definition. Not recorded
 *    in run history.
 *
 * The others need `tags.read` (`Auth.kt`). A run is made as the caller: named-query role lists use the caller's roles and
 * every history tag passes [Gateway.reportTagCheck].
 */
class ReportApi(private val gw: Gateway) {
    private val reports: ReportService get() = gw.reports

    fun register(app: Javalin) {
        app.get("/api/v1/reports/components") { ctx -> json(ctx, ComponentCatalog.toMap()) }
        app.post("/api/v1/reports/preview") { ctx -> json(ctx, preview(ctx).second.toMap()) }
        app.post("/api/v1/reports/preview/pdf") { ctx ->
            val (definition, result) = preview(ctx)
            val bytes = layoutChecked { render(definition.copy(layout = result.layout), result) }
            ctx.contentType("application/pdf")
            ctx.header("Content-Disposition", "attachment; filename=\"preview-${result.generatedAt}.pdf\"")
            ctx.result(bytes)
        }
        app.post("/api/v1/reports/{name}/run") { ctx -> json(ctx, run(ctx).toMap()) }
        app.post("/api/v1/reports/{name}/pdf") { ctx -> pdf(ctx) }
        app.get("/api/v1/reports/{name}/files") { ctx ->
            val name = ctx.pathParam("name")
            guard { reports.definition(name) }
            json(ctx, mapOf("items" to gw.reportArchive.files(name).map { it.toMap() }))
        }
        app.get("/api/v1/reports/{name}/files/{file}") { ctx ->
            val name = ctx.pathParam("name")
            val fileName = ctx.pathParam("file")
            val file = gw.reportArchive.file(name, fileName) ?: throw Problems.notFound("no archived file '$fileName' for report '$name'")
            ctx.contentType("application/pdf")
            ctx.header("Content-Disposition", "attachment; filename=\"$name-$fileName\"")
            ctx.result(Files.readAllBytes(file))
        }
        app.get("/api/v1/reports/status") { ctx ->
            json(ctx, mapOf("items" to reports.statuses().map { it.toMap() }))
        }
        app.get("/api/v1/reports/{name}/runs") { ctx ->
            val name = ctx.pathParam("name")
            val limit = ctx.queryParam("limit")?.let { it.toIntOrNull() ?: throw Problems.badRequest("limit must be an integer") } ?: 50
            if (limit !in 1..RunHistory.DEFAULT_KEEP) throw Problems.badRequest("limit must be 1..${RunHistory.DEFAULT_KEEP}")
            guard { reports.definition(name) }
            json(ctx, mapOf("items" to reports.runs(name, limit).map { it.toMap() }))
        }
        app.get("/api/v1/reports/{name}/status") { ctx ->
            json(ctx, guard { reports.status(ctx.pathParam("name")) }.toMap())
        }
    }

    private fun preview(ctx: Context): Pair<ReportDefinition, ReportResult> {
        val body = try {
            Json.parse(ctx.body())
        } catch (e: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        val unknown = body.keys - setOf("spec", "parameters")
        if (unknown.isNotEmpty()) throw Problems.badRequest("unknown fields: ${unknown.sorted().joinToString()}")
        @Suppress("UNCHECKED_CAST")
        val spec = body["spec"] as? Map<String, Any?> ?: throw Problems.badRequest("spec must be a report definition object")
        @Suppress("UNCHECKED_CAST")
        val parameters = when (val raw = body["parameters"]) {
            null -> emptyMap()
            is Map<*, *> -> raw as Map<String, Any?>
            else -> throw Problems.badRequest("parameters must be an object")
        }
        val definition = try {
            reports.draft(spec)
        } catch (e: ReportInvalid) {
            throw Problems.unprocessable("invalid report: ${e.errors.joinToString("; ")}")
        }
        layoutChecked { validateLayout(definition) }
        val result = guard { reports.preview(definition, parameters, caller(ctx)) }
        return definition to result
    }

    private fun pdf(ctx: Context) {
        val name = ctx.pathParam("name")
        val definition = guard { reports.definition(name) }
        layoutChecked { validateLayout(definition) }
        val result = run(ctx)
        // Render with the layout the run used (copied into the result), even if the definition was saved meanwhile.
        val bytes = layoutChecked { render(definition.copy(layout = result.layout), result) }
        ctx.contentType("application/pdf")
        ctx.header("Content-Disposition", "attachment; filename=\"$name-${result.generatedAt}.pdf\"")
        ctx.result(bytes)
    }

    private fun <T> layoutChecked(body: () -> T): T = try {
        body()
    } catch (e: LayoutException) {
        throw Problems.unprocessable("report layout: ${e.message}")
    }

    private fun run(ctx: Context): ReportResult {
        val name = ctx.pathParam("name")
        val body = if (ctx.body().isBlank()) emptyMap() else try {
            Json.parse(ctx.body())
        } catch (e: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        val unknown = body.keys - setOf("parameters")
        if (unknown.isNotEmpty()) throw Problems.badRequest("unknown fields: ${unknown.sorted().joinToString()}")
        @Suppress("UNCHECKED_CAST")
        val parameters = when (val raw = body["parameters"]) {
            null -> emptyMap()
            is Map<*, *> -> raw as Map<String, Any?>
            else -> throw Problems.badRequest("parameters must be an object")
        }
        return guard { reports.run(name, parameters, caller(ctx), RunTrigger.MANUAL) }
    }

    private fun caller(ctx: Context): ReportCaller {
        val (subject, connection) = GatewayAuth.subject(ctx)
        val permissions = gw.security.authorization.effectivePermissions(subject, connection)
        val roles = when (subject) {
            is Subject.User -> subject.roles
            is Subject.ApiKey -> emptySet()
        }
        return ReportCaller(subject.principal, roles, permissions, zone = gw.security.zones.match(connection).name)
    }

    private fun <T> guard(body: () -> T): T = try {
        body()
    } catch (e: ReportNotFound) {
        throw Problems.notFound(e.message!!)
    } catch (e: ReportDisabled) {
        throw Problems.conflict(e.message!!)
    } catch (e: ReportParameterException) {
        throw Problems.badRequest(e.message!!)
    } catch (e: ReportRunException) {
        throw when (e.status) {
            400 -> Problems.badRequest(e.message!!)
            403 -> Problems.forbidden(e.message!!)
            422 -> Problems.unprocessable(e.message!!)
            502 -> ProblemException(502, "report-source-failed", "Bad Gateway", e.message!!)
            503 -> ProblemException(503, "report-source-unavailable", "Service Unavailable", e.message!!)
            else -> ProblemException(500, "report-failed", "Internal Server Error", e.message!!)
        }
    }

    private fun json(ctx: Context, body: Any) {
        ctx.contentType("application/json").result(Json.write(body))
    }
}
