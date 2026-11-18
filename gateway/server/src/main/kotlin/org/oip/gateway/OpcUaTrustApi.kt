package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.opcua.server.ClientCertificateInfo
import org.oip.opcua.server.OpcUaCertificateTrust
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus

const val OPCUA_CERT_TRUSTED_ACTION = "opcua certificate trusted"
const val OPCUA_CERT_IMPORTED_ACTION = "opcua certificate imported"
const val OPCUA_CERT_REMOVED_ACTION = "opcua certificate removed"
const val OPCUA_CERT_QUARANTINE_DELETED_ACTION = "opcua quarantined certificate deleted"

/**
 * Trust list for client certificates of the gateway OPC UA server.
 *
 * A client that opens a secured endpoint (Basic256Sha256) with a certificate the server does not trust is
 * refused and its certificate is quarantined. An administrator trusts or deletes quarantined
 * certificates, imports a certificate ahead of the first connection, and deletes trusted ones.
 *
 * Reading needs `gateway.read`; every change needs `gateway.write` (see [GatewayAuth]) and is audited.
 * The lists work while the OPC UA server is off, so a client can be trusted before the server starts.
 */
class OpcUaTrustApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get(BASE) { ctx -> list(ctx) }
        app.post("$BASE/trusted") { ctx -> import(ctx) }
        app.delete("$BASE/trusted/{thumbprint}") { ctx -> removeTrusted(ctx) }
        app.post("$BASE/quarantined/{thumbprint}/trust") { ctx -> trust(ctx) }
        app.delete("$BASE/quarantined/{thumbprint}") { ctx -> removeQuarantined(ctx) }
    }

    private fun list(ctx: Context) {
        val trust = gw.opcUaTrust
        val server = gw.opcUa
        json(ctx, 200, linkedMapOf(
            "serverEnabled" to gw.settings.opcua.enabled,
            "serverRunning" to (server?.running == true),
            "endpointUrl" to server?.endpointUrl,
            "trusted" to trust.trusted().map(::view),
            "quarantined" to trust.quarantined().map(::view),
        ))
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
            gw.opcUaTrust.importTrusted(text)
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable(e.message ?: "certificate was rejected")
        }
        audit(OPCUA_CERT_IMPORTED_ACTION, info)
        json(ctx, 201, view(info))
    }

    private fun trust(ctx: Context) {
        val thumbprint = thumbprint(ctx)
        val info = gw.opcUaTrust.trustQuarantined(thumbprint)
            ?: throw Problems.notFound("no quarantined certificate $thumbprint")
        audit(OPCUA_CERT_TRUSTED_ACTION, info)
        json(ctx, 200, view(info))
    }

    private fun removeTrusted(ctx: Context) {
        val thumbprint = thumbprint(ctx)
        val info = gw.opcUaTrust.trusted().firstOrNull { it.thumbprint == thumbprint }
        if (info == null || !gw.opcUaTrust.removeTrusted(thumbprint)) throw Problems.notFound("no trusted certificate $thumbprint")
        audit(OPCUA_CERT_REMOVED_ACTION, info)
        ctx.status(204)
    }

    private fun removeQuarantined(ctx: Context) {
        val thumbprint = thumbprint(ctx)
        val info = gw.opcUaTrust.quarantined().firstOrNull { it.thumbprint == thumbprint }
        if (info == null || !gw.opcUaTrust.removeQuarantined(thumbprint)) throw Problems.notFound("no quarantined certificate $thumbprint")
        audit(OPCUA_CERT_QUARANTINE_DELETED_ACTION, info)
        ctx.status(204)
    }

    private fun thumbprint(ctx: Context): String {
        val raw = ctx.pathParam("thumbprint")
        if (!OpcUaCertificateTrust.validThumbprint(raw)) throw Problems.badRequest("thumbprint must be 40 hex characters (SHA-1)")
        return raw.lowercase()
    }

    private fun audit(action: String, info: ClientCertificateInfo) {
        val context = AuditContext.current()
        val actor = context?.actor ?: "api"
        gw.logs.log(LogLevel.INFO, "org.oip.opcua", action, mapOf("thumbprint" to info.thumbprint, "subject" to info.subject, "actor" to actor))
        if (!gw.security.auditProfile.get().enabled) return
        gw.security.audit.append(
            AuditDraft(
                timestampMillis = gw.clock.millis(),
                actor = actor,
                action = action,
                status = AuditStatus.SUCCESS,
                actorHost = context?.host ?: "",
                target = "opcua/pki/${info.thumbprint}",
                detail = "subject=${info.subject}",
                context = "gateway-http",
            )
        )
    }

    private fun view(info: ClientCertificateInfo): Map<String, Any?> = linkedMapOf(
        "thumbprint" to info.thumbprint,
        "subject" to info.subject,
        "commonName" to info.commonName,
        "issuer" to info.issuer,
        "applicationUri" to info.applicationUri,
        "notBefore" to info.notBefore.toString(),
        "notAfter" to info.notAfter.toString(),
        "expired" to info.expired(gw.clock.instant()),
    )

    private fun json(ctx: Context, status: Int, value: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(value))
    }

    companion object {
        const val BASE = "/api/v1/opcua/certificates"
    }
}
