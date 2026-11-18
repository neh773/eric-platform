package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.opcua.server.OpcUaCertificateTrust
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus

const val OUTBOUND_CERT_IMPORTED_ACTION = "outbound tls certificate imported"
const val OUTBOUND_CERT_TRUSTED_ACTION = "outbound tls certificate trusted"
const val OUTBOUND_CERT_REMOVED_ACTION = "outbound tls certificate removed"
const val OUTBOUND_CERT_REJECTED_DELETED_ACTION = "outbound tls rejected certificate deleted"

/**
 * The outbound TLS trust store over REST (SEC-022), beside the OPC UA client-certificate page.
 *
 * - `GET /api/v1/tls/outbound`: the trusted and rejected server certificates (`gateway.read`).
 * - `POST /api/v1/tls/outbound/trusted` with `{certificate}` (PEM or base64 DER): trust a certificate ahead of the
 *   first connection.
 * - `POST /api/v1/tls/outbound/rejected/{sha256}/trust`: trust a certificate a client was refused.
 * - `DELETE /api/v1/tls/outbound/trusted/{sha256}` and `/rejected/{sha256}`: remove one.
 *
 * Every change needs `gateway.write` and writes one audit record.
 *
 * Doc source: FEATURES SEC-022.
 */
class OutboundTrustApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get(BASE) { ctx -> list(ctx) }
        app.post("$BASE/trusted") { ctx -> import(ctx) }
        app.delete("$BASE/trusted/{thumbprint}") { ctx -> change(ctx, OUTBOUND_CERT_REMOVED_ACTION, 204) { gw.outboundTrust.removeTrusted(it) } }
        app.post("$BASE/rejected/{thumbprint}/trust") { ctx -> change(ctx, OUTBOUND_CERT_TRUSTED_ACTION, 200) { gw.outboundTrust.trustRejected(it) } }
        app.delete("$BASE/rejected/{thumbprint}") { ctx -> change(ctx, OUTBOUND_CERT_REJECTED_DELETED_ACTION, 204) { gw.outboundTrust.removeRejected(it) } }
    }

    private fun list(ctx: Context) {
        val trust = gw.outboundTrust
        json(ctx, 200, linkedMapOf("trusted" to trust.trusted().map(::view), "rejected" to trust.rejected().map(::view)))
    }

    private fun import(ctx: Context) {
        if (ctx.contentLength() > OpcUaCertificateTrust.MAX_UPLOAD_CHARS * 2) throw Problems.unprocessable("certificate upload is too large")
        val body = try {
            Json.parse(ctx.body())
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        val text = body["certificate"] as? String ?: throw Problems.badRequest("certificate is required (PEM or base64 DER)")
        val info = try {
            gw.outboundTrust.importTrusted(text)
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable(e.message ?: "certificate was rejected")
        }
        audit(OUTBOUND_CERT_IMPORTED_ACTION, info)
        json(ctx, 201, view(info))
    }

    private fun change(ctx: Context, action: String, status: Int, apply: (String) -> OutboundCertificate?) {
        val raw = ctx.pathParam("thumbprint").lowercase()
        if (!OutboundTrust.validThumbprint(raw)) throw Problems.badRequest("thumbprint must be 64 hex characters (SHA-256)")
        val info = apply(raw) ?: throw Problems.notFound("no such certificate $raw")
        audit(action, info)
        if (status == 204) ctx.status(204) else json(ctx, status, view(info))
    }

    private fun audit(action: String, info: OutboundCertificate) {
        val context = AuditContext.current()
        val actor = context?.actor ?: "api"
        gw.logs.log(LogLevel.INFO, "org.oip.tls", action, mapOf("thumbprint" to info.thumbprint, "subject" to info.subject, "actor" to actor))
        if (!gw.security.auditProfile.get().enabled) return
        gw.security.audit.append(
            AuditDraft(
                timestampMillis = gw.clock.millis(),
                actor = actor,
                action = action,
                status = AuditStatus.SUCCESS,
                actorHost = context?.host ?: "",
                target = "tls/outbound/${info.thumbprint}",
                detail = "subject=${info.subject}".take(500),
                context = "gateway-http",
            )
        )
    }

    private fun view(info: OutboundCertificate): Map<String, Any?> = linkedMapOf(
        "thumbprint" to info.thumbprint,
        "subject" to info.subject,
        "commonName" to info.commonName,
        "issuer" to info.issuer,
        "notBefore" to info.notBefore.toString(),
        "notAfter" to info.notAfter.toString(),
        "expired" to info.expired(gw.clock.instant()),
        "seenAt" to info.seenAt.ifEmpty { null },
        "client" to info.client.ifEmpty { null },
        "lastSeen" to info.lastSeen?.toString(),
    )

    private fun json(ctx: Context, status: Int, value: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(value))
    }

    companion object {
        const val BASE = "/api/v1/tls/outbound"
    }
}
