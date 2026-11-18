package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.backup.BackupException
import org.oip.config.ConflictException
import org.oip.config.ResourceFormatException
import org.oip.config.UnknownKindException
import org.oip.config.ValidationException
import org.oip.security.AccessDeniedException
import org.oip.security.AlreadyExistsException
import org.oip.security.NotFoundException
import org.oip.security.PasswordRejectedException

/** RFC 9457 problem details. `type` is `urn:oip:problem:<slug>`. */
class ProblemException(val status: Int, val slug: String, val title: String, detail: String,
                       val errors: List<String> = emptyList(), val extensions: Map<String, Any?> = emptyMap()) : RuntimeException(detail)

object Problems {
    fun notFound(detail: String) = ProblemException(404, "not-found", "Not Found", detail)
    fun badRequest(detail: String) = ProblemException(400, "bad-request", "Bad Request", detail)
    fun unprocessable(detail: String, errors: List<String> = emptyList()) = ProblemException(422, "validation", "Unprocessable Content", detail, errors)
    fun tooLarge(detail: String) = ProblemException(413, "too-large", "Content Too Large", detail)
    fun conflict(detail: String) = ProblemException(409, "conflict", "Conflict", detail)
    fun unauthorized(detail: String, slug: String = "unauthorized") = ProblemException(401, slug, "Unauthorized", detail)
    fun forbidden(detail: String, slug: String = "forbidden") = ProblemException(403, slug, "Forbidden", detail)
    fun locked(retryAfterSeconds: Long) = ProblemException(
        429, "account-locked", "Too Many Requests", "account is temporarily locked",
        extensions = mapOf("retryAfter" to retryAfterSeconds),
    )
    fun rateLimited(retryAfterSeconds: Long) = ProblemException(
        429, "rate-limited", "Too Many Requests", "too many login attempts",
        extensions = mapOf("retryAfter" to retryAfterSeconds),
    )

    fun of(t: Throwable): ProblemException = when (t) {
        is ProblemException -> t
        is NoSuchElementException -> notFound(t.message ?: "not found")
        is NotFoundException -> notFound(t.message!!)
        is ConflictException -> conflict(t.message!!)
        is AlreadyExistsException -> conflict(t.message!!)
        is ValidationException -> unprocessable(t.message!!, t.errors)
        is UnknownKindException -> unprocessable(t.message!!)
        is ResourceFormatException -> unprocessable(t.message!!)
        is PasswordRejectedException -> unprocessable(t.message!!, t.violations.map { it.message })
        is AccessDeniedException -> forbidden(t.message!!)
        is IllegalArgumentException -> unprocessable(t.message ?: "invalid request")
        is BackupException -> unprocessable(t.message ?: "backup failed")
        is IllegalStateException -> conflict(t.message ?: "invalid state")
        else -> ProblemException(500, "internal", "Internal Server Error", t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
    }

    fun write(ctx: Context, p: ProblemException) {
        // Error responses can skip the after-handler. Clear the actor before the Jetty thread is reused.
        GatewayAuth.releaseAudit(ctx)
        val body = linkedMapOf<String, Any?>("type" to "urn:oip:problem:${p.slug}", "title" to p.title, "status" to p.status,
            "detail" to p.message, "instance" to ctx.path())
        if (p.errors.isNotEmpty()) body["errors"] = p.errors
        p.extensions.forEach { (k, v) -> if (v != null) body[k] = v }
        if (p.status == 401) ctx.header("WWW-Authenticate", "Bearer realm=\"oip\"")
        (p.extensions["retryAfter"] as? Number)?.let { ctx.header("Retry-After", it.toLong().toString()) }
        ctx.status(p.status).contentType("application/problem+json").result(Json.write(body))
    }

    fun install(app: Javalin) {
        app.exception(Exception::class.java) { e, ctx -> write(ctx, of(e)) }
        app.error(404) { ctx ->
            // contentType() is the request header. The response type is what an endpoint already wrote.
            val responseType = ctx.res().contentType
            if (ctx.result().isNullOrEmpty() || responseType?.startsWith("application/problem") != true)
                write(ctx, notFound("no route for ${ctx.method()} ${ctx.path()}"))
        }
        app.error(405) { ctx -> write(ctx, ProblemException(405, "method-not-allowed", "Method Not Allowed", "${ctx.method()} not allowed on ${ctx.path()}")) }
    }
}
