package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.alarm.notify.NOTIFY_MODULE
import org.oip.alarm.notify.NotifyCodec
import org.oip.alarm.notify.NotifyKinds
import org.oip.alarm.notify.OutboundMail
import org.oip.alarm.notify.SmtpException
import org.oip.alarm.notify.SmtpProfile
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope

/**
 * SMTP email profiles, configured once and reused by alarm notification, scheduled reports and gateway scripts
 * (`org.oip.alarm.notify.MailProfiles` is the one shared lookup). Doc source: FEATURES GAT-030.
 *
 * `GET /api/v1/email/profiles[/{name}]` needs gateway.read; `PUT`, `DELETE` and `POST .../{name}/test` need gateway.write.
 * A write takes the password, OAuth2 client secret and refresh token as plain text fields that are stored in the secret
 * store (`smtp-<name>-password`, `-client-secret`, `-refresh-token`) and kept in the profile only as `secret://`
 * references. No response, log line or error carries a secret: a profile reports `passwordSet`, `clientSecretSet` and
 * `refreshTokenSet`. On update a missing secret field keeps the stored one and an empty string clears it.
 * An OAuth2 profile may instead name `oauthClient`, an OAuth2 client registration (SEC-029) that supplies the token for
 * the profile's `oauthScope` through the shared token cache; the registration must exist when the profile is saved.
 */
class EmailProfileApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get(PATH) { ctx -> ProjectsApi.json(ctx, 200, mapOf("items" to all().map(::view))) }
        app.get("$PATH/{name}") { ctx -> ProjectsApi.json(ctx, 200, view(one(ctx.pathParam("name")))) }
        app.put("$PATH/{name}") { ctx -> put(ctx) }
        app.delete("$PATH/{name}") { ctx -> delete(ctx) }
        app.post("$PATH/{name}/test") { ctx -> test(ctx) }
    }

    private fun id(name: String): ResourceId = try {
        ResourceId(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.SMTP, name)
    } catch (e: IllegalArgumentException) {
        throw Problems.unprocessable("'$name' is not a profile name: lowercase letters, digits and '-', 1 to 63 characters")
    }

    private fun all(): List<Pair<SmtpProfile, Long>> =
        gw.store.list(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.SMTP).mapNotNull { stored ->
            runCatching { NotifyCodec.smtp(stored.id.name, stored.resource.spec) to stored.resource.metadata.version }.getOrNull()
        }.sortedBy { it.first.name }

    private fun one(name: String): Pair<SmtpProfile, Long> {
        val stored = gw.store.get(id(name)) ?: throw Problems.notFound("no email profile '$name'")
        val profile = try {
            NotifyCodec.smtp(name, stored.spec)
        } catch (e: RuntimeException) {
            throw Problems.unprocessable("email profile '$name' is not valid")
        }
        return profile to stored.metadata.version
    }

    private fun view(entry: Pair<SmtpProfile, Long>): Map<String, Any?> {
        val p = entry.first
        return linkedMapOf(
            "name" to p.name,
            "enabled" to p.enabled,
            "hostname" to p.hostname,
            "port" to p.port,
            "useSsl" to p.useSsl,
            "startTls" to p.startTls,
            "username" to p.username,
            "passwordSet" to p.passwordRef.isNotEmpty(),
            "authType" to p.authType,
            "fromAddress" to p.fromAddress,
            "timeoutMillis" to p.timeoutMillis,
            "oauthTokenUrl" to p.oauthTokenUrl,
            "oauthClientId" to p.oauthClientId,
            "clientSecretSet" to p.oauthClientSecretRef.isNotEmpty(),
            "refreshTokenSet" to p.oauthRefreshTokenRef.isNotEmpty(),
            "oauthScope" to p.oauthScope,
            "oauthClient" to p.oauthClient,
            "version" to entry.second,
        )
    }

    private fun put(ctx: Context) {
        val name = ctx.pathParam("name")
        val resourceId = id(name)
        val body = ProjectsApi.body(ctx)
        val unknown = body.keys - FIELDS - SECRET_FIELDS
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown fields: ${unknown.sorted().joinToString()}")
        val existing = gw.store.get(resourceId)
        val spec = LinkedHashMap<String, Any?>()
        for (key in FIELDS) if (body.containsKey(key)) spec[key] = body[key]
        // Secrets: absent keeps the stored reference, empty clears it, text is stored and referenced.
        val written = ArrayList<String>()
        val refs = mapOf(
            "password" to ("password" to "password"),
            "oauthClientSecret" to ("oauthClientSecret" to "client-secret"),
            "oauthRefreshToken" to ("oauthRefreshToken" to "refresh-token"),
        )
        for ((field, pair) in refs) {
            val (specKey, suffix) = pair
            if (!body.containsKey(field)) {
                existing?.spec?.get(specKey)?.let { spec[specKey] = it }
                continue
            }
            when (val value = body[field]) {
                null, "" -> spec.remove(specKey)
                is String -> {
                    if (value.length > 4096) throw Problems.unprocessable("$field is too long")
                    val secretName = "smtp-$name-$suffix"
                    try {
                        spec[specKey] = gw.security.secrets.putText(secretName, value, "email profile $name")
                        written += secretName
                    } catch (e: Exception) {
                        throw Problems.unprocessable("could not store $field")
                    }
                }
                else -> throw Problems.unprocessable("$field must be a string")
            }
        }
        // Validate through the same parser the senders use, so a profile that is saved is one that can be used.
        val parsed = try {
            NotifyCodec.smtp(name, spec)
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable(e.message ?: "invalid email profile")
        }
        // SEC-029: an OAuth2 profile names a client registration, or carries its own token URL and client id.
        if (parsed.oauthClient.isNotEmpty() && gw.oauth2Clients.client(parsed.oauthClient) == null) {
            throw Problems.unprocessable("oauthClient '${parsed.oauthClient}' is not an OAuth2 client registration")
        }
        if (parsed.oauth2 && parsed.oauthClient.isEmpty() && (parsed.oauthTokenUrl.isBlank() || parsed.oauthClientId.isBlank())) {
            throw Problems.unprocessable("oauth2 needs oauthClient, or oauthTokenUrl and oauthClientId")
        }
        val version = existing?.metadata?.version ?: 0L
        val saved = gw.store.put(Scope.GATEWAY, Resource(NotifyKinds.SMTP, Metadata(name, NOTIFY_MODULE, version = version), spec), ProjectsApi.actor())
        ProjectsApi.json(ctx, if (existing == null) 201 else 200, view(parsed to saved.metadata.version))
    }

    private fun delete(ctx: Context) {
        val name = ctx.pathParam("name")
        val existing = one(name)
        gw.store.delete(id(name))
        // The secrets this API stored for the profile go with it.
        for (suffix in listOf("password", "client-secret", "refresh-token")) runCatching { gw.security.secrets.delete("smtp-$name-$suffix") }
        ProjectsApi.json(ctx, 200, mapOf("deleted" to existing.first.name))
    }

    private fun test(ctx: Context) {
        val name = ctx.pathParam("name")
        one(name)
        val body = ProjectsApi.body(ctx)
        val unknown = body.keys - setOf("to")
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown fields: ${unknown.sorted().joinToString()}")
        val to = body["to"] as? String ?: throw Problems.unprocessable("to is required")
        if (!to.contains('@') || to.length > 254 || to.any { it.code < 0x20 || it.code == 0x7F }) {
            throw Problems.unprocessable("to must be an email address")
        }
        try {
            gw.notifications.mailProfiles.send(
                name,
                OutboundMail(
                    from = "", to = to, subject = "ERIC test message",
                    body = "This message confirms that the email profile '$name' can send.", testMode = false,
                    eventId = "", username = "", profile = name, ackLink = false, emailProfile = null, smtpProfile = name,
                ),
            )
        } catch (e: SmtpException) {
            throw ProblemException(502, "mail-failed", "Bad Gateway", e.message ?: "the mail was not sent")
        }
        ProjectsApi.json(ctx, 200, mapOf("sent" to true, "profile" to name, "to" to to))
    }

    companion object {
        const val PATH = "/api/v1/email/profiles"
        private val FIELDS = setOf(
            "description", "enabled", "hostname", "port", "useSsl", "startTls", "username", "timeoutMillis",
            "sslProtocols", "debug", "fromAddress", "authType", "oauthTokenUrl", "oauthClientId", "oauthScope", "oauthClient",
        )
        private val SECRET_FIELDS = setOf("password", "oauthClientSecret", "oauthRefreshToken")
    }
}
