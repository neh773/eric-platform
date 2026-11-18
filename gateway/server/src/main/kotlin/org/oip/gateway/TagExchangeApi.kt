package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.FileConfigStore
import org.oip.tags.TagEngine
import org.oip.tags.TagExchange

/**
 * Tag configuration export and import as JSON (TAG-005, review-2 F4), over [TagExchange].
 *
 * `GET /api/v1/tags/export?provider=default&folder=Line1` returns the export document for a provider, or for one
 * folder subtree with the UDT definitions its tags need. Two exports of the same configuration are byte-identical.
 * It is a gateway configuration read (`gateway.read`).
 *
 * `POST /api/v1/tags/import?policy=overwrite|merge|ignore&provider=default` applies a document. The policy decides
 * what happens to a tag or UDT that already exists: replace it, merge the imported keys into it, or keep it (new
 * resources are added under every policy). The import is all or nothing: one invalid entry is 422 with every
 * problem listed and nothing written. It is a gateway configuration write (`gateway.write`), recorded as a config
 * change by the importing user.
 *
 * `provider=system` exports and imports the alarm configuration of the gateway's `[system]` tags (ALA-040): each entry
 * names a system tag path and its `alarms`, nothing else. Doc source: FEATURES ALA-040.
 */
class TagExchangeApi(private val engine: TagEngine, private val store: FileConfigStore) {
    fun register(app: Javalin) {
        app.get(EXPORT) { ctx -> export(ctx) }
        app.post(IMPORT) { ctx -> import(ctx) }
    }

    private fun export(ctx: Context) {
        val provider = provider(ctx)
        val folder = ctx.queryParam("folder")?.trim()?.trim('/')?.ifEmpty { null }
        val body = TagExchange.export(store, provider, folder)
        ctx.header("Content-Disposition", "attachment; filename=\"tags-$provider.json\"")
        ctx.contentType("application/json").result(body)
    }

    private fun import(ctx: Context) {
        val policy = when (ctx.queryParam("policy")?.trim()?.lowercase()) {
            null, "", "overwrite" -> TagExchange.CollisionPolicy.OVERWRITE
            "merge" -> TagExchange.CollisionPolicy.MERGE
            "ignore" -> TagExchange.CollisionPolicy.IGNORE
            else -> throw Problems.badRequest("policy must be overwrite, merge or ignore")
        }
        val provider = ctx.queryParam("provider")?.trim()?.ifEmpty { null }
        if (provider != null) provider(ctx)
        val actor = GatewayAuth.subject(ctx).first.principal
        val report = try {
            TagExchange.import(store, ctx.body(), policy, provider, actor = actor)
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable(e.message ?: "invalid export document")
        }
        val body = linkedMapOf<String, Any?>(
            "policy" to policy.name.lowercase(),
            "created" to report.created,
            "updated" to report.updated,
            "skipped" to report.skipped,
            "failed" to report.failed.map { linkedMapOf("resource" to it.resource, "message" to it.message) },
        )
        ctx.status(if (report.failed.isEmpty()) 200 else 422)
        ctx.contentType("application/json").result(Json.write(body))
    }

    private fun provider(ctx: Context): String {
        val name = ctx.queryParam("provider")?.trim()?.ifEmpty { null } ?: "default"
        val provider = engine.provider(name) ?: throw Problems.notFound("tag provider '$name' not found")
        // The read-only [system] provider exports and imports its alarm configuration (ALA-040).
        if (provider.readOnly && !provider.name.equals("system", ignoreCase = true)) {
            throw Problems.unprocessable("tag provider '$name' is read-only")
        }
        return provider.name
    }

    companion object {
        const val EXPORT = "/api/v1/tags/export"
        const val IMPORT = "/api/v1/tags/import"
    }
}
