package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.security.NotFoundException
import org.oip.security.SecretException
import org.oip.security.SecretInfo

/**
 * Create, list, and delete encrypted secrets. The response is the name, description, and `secret://` reference.
 * The stored value is never written back to the client.
 */
class SecretsApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get("/api/v1/secrets") { ctx -> json(ctx, 200, mapOf("items" to gw.security.secrets.list().map(::info))) }
        app.put("/api/v1/secrets/{name}") { ctx -> save(ctx) }
        app.delete("/api/v1/secrets/{name}") { ctx -> remove(ctx) }
    }

    private fun save(ctx: Context) {
        if (ctx.body().length > MAX_BODY) throw Problems.badRequest("body is too large")
        val body = jsonObject(ctx)
        val name = ctx.pathParam("name")
        val description = body["description"] as? String ?: ""
        if (description.length > 200) throw Problems.unprocessable("description is too long")
        val value = body["value"] as? String
        if (value.isNullOrEmpty()) throw Problems.unprocessable("value is required")
        try {
            gw.security.secrets.putText(name, value, description)
        } catch (e: SecretException) {
            throw Problems.unprocessable(e.message ?: "secret was rejected")
        }
        val saved = gw.security.secrets.list().firstOrNull { it.name == name }
            ?: throw Problems.notFound("secret '$name' not found")
        json(ctx, 200, info(saved))
    }

    private fun remove(ctx: Context) {
        val name = ctx.pathParam("name")
        try {
            gw.security.secrets.delete(name)
        } catch (_: NotFoundException) {
            throw Problems.notFound("secret '$name' not found")
        }
        ctx.status(204)
    }

    private fun info(item: SecretInfo): Map<String, Any?> = mapOf(
        "name" to item.name,
        "description" to item.description,
        "createdAt" to item.createdAt.toString(),
        "updatedAt" to item.updatedAt.toString(),
        "reference" to item.reference,
    )

    private fun json(ctx: Context, status: Int, body: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(body))
    }

    private fun jsonObject(ctx: Context): Map<String, Any?> {
        val parsed = try {
            Json.mapper.readValue(ctx.body(), Any::class.java)
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?> ?: throw Problems.badRequest("body must be a JSON object")
    }

    companion object {
        private const val MAX_BODY = 64 * 1024
    }
}
