package org.oip.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

const val HTTP_SOURCE_KIND = "HttpSource"

/**
 * What an HTTP binding reads (HMP-114, doc source: FEATURES HMP-114). A project's `HttpSource` resource names an
 * upstream: `baseUrl` (http or https), `headers` (sent by the gateway only; a credential header such as
 * `Authorization` or `X-Api-Key` must hold a `secret://` reference, resolved from the secret store at send time),
 * `timeoutMs` and `maxBytes`. A session asks for `{source, path, query}`; the gateway builds the URL from the stored
 * base, so the browser never names a host, never sees a header and cannot reach anything the resource does not name
 * (deny by default: an unknown source is a 404). Only GET, no redirects, JSON responses only, size and time bounded.
 */
internal object HmiHttpFetch {
    const val DEFAULT_TIMEOUT_MS = 5_000L
    const val MAX_TIMEOUT_MS = 15_000L
    const val DEFAULT_MAX_BYTES = 256_000
    const val MAX_BYTES = 1_000_000
    private val mapper = ObjectMapper()

    /** Header names that carry a credential: their value must be a secret reference, never plain text in config. */
    const val CREDENTIAL_HEADER = "(?i)^(authorization|proxy-authorization|cookie|.*(key|token|secret|auth|password|session).*)$"
    private const val SECRET_REF = "^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"
    private val credentialHeader = Regex(CREDENTIAL_HEADER)

    fun schema(): Map<String, Any?> = mapOf(
        "type" to "object",
        "required" to listOf("baseUrl"),
        "additionalProperties" to false,
        "properties" to mapOf(
            "baseUrl" to mapOf("type" to "string", "pattern" to "^https?://[^/?#@\\s]+(?:/[^?#\\s]*)?$", "maxLength" to 500),
            "headers" to mapOf(
                "type" to "object",
                "maxProperties" to 16,
                "propertyNames" to mapOf("pattern" to "^[A-Za-z0-9-]{1,64}$"),
                "patternProperties" to mapOf(CREDENTIAL_HEADER to mapOf("type" to "string", "pattern" to SECRET_REF)),
                "additionalProperties" to mapOf("type" to "string", "maxLength" to 500),
            ),
            "timeoutMs" to mapOf("type" to "integer", "minimum" to 100, "maximum" to MAX_TIMEOUT_MS),
            "maxBytes" to mapOf("type" to "integer", "minimum" to 1, "maximum" to MAX_BYTES),
        ),
    )

    /** The relative [path] after validation, or an [IllegalArgumentException] naming what is wrong. */
    fun checkedPath(path: Any?): String {
        val text = path as? String ?: throw IllegalArgumentException("path must be a string")
        if (text.isEmpty() || text.length > 500) throw IllegalArgumentException("path must be 1 to 500 characters")
        if (!text.startsWith("/") || text.startsWith("//")) throw IllegalArgumentException("path must start with a single /")
        if (text.any { it.code < 0x21 || it == '\\' || it == '#' || it == '?' || it == '@' }) throw IllegalArgumentException("path has a character that is not allowed")
        if (text.split('/').any { it == ".." || it == "." || it.contains("%2e", ignoreCase = true) || it.contains("%2f", ignoreCase = true) }) {
            throw IllegalArgumentException("path must not climb or encode separators")
        }
        return text
    }

    fun checkedQuery(query: Any?): String {
        if (query == null) return ""
        val map = query as? Map<*, *> ?: throw IllegalArgumentException("query must be an object")
        if (map.size > 16) throw IllegalArgumentException("query has too many parameters")
        val parts = map.entries.map { (k, v) ->
            val name = k as? String ?: throw IllegalArgumentException("query names must be text")
            val value = when (v) {
                is String, is Number, is Boolean -> v.toString()
                else -> throw IllegalArgumentException("query values must be text, numbers or booleans")
            }
            if (name.isEmpty() || name.length > 64 || value.length > 500) throw IllegalArgumentException("query parameter is too long")
            URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }

    /** One fetch. Never throws for an upstream failure: the answer has `ok` false and a short fixed `error`. */
    fun fetch(spec: Map<String, Any?>, path: String, query: String, secret: (String) -> String): Map<String, Any?> {
        val base = (spec["baseUrl"] as? String)?.trimEnd('/') ?: return failure(0, "source has no base URL")
        val timeout = ((spec["timeoutMs"] as? Number)?.toLong() ?: DEFAULT_TIMEOUT_MS).coerceIn(100, MAX_TIMEOUT_MS)
        val maxBytes = ((spec["maxBytes"] as? Number)?.toInt() ?: DEFAULT_MAX_BYTES).coerceIn(1, MAX_BYTES)
        val uri = try {
            URI(base + path + query)
        } catch (e: Exception) {
            return failure(0, "request address is invalid")
        }
        // The host is the stored one; a path cannot move it.
        if (uri.host == null || uri.host != URI(base).host || uri.userInfo != null) return failure(0, "request address is invalid")
        return try {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(timeout))
                .sslContext(org.oip.config.OutboundTls.context("hmi-http"))
                .build()
            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout)).header("Accept", "application/json").GET()
            for ((k, v) in (spec["headers"] as? Map<*, *>).orEmpty()) {
                if (k !is String || v !is String) continue
                val value = when {
                    v.startsWith("secret://") -> try { secret(v) } catch (e: Exception) { return failure(0, "source credential could not be read") }
                    credentialHeader.matches(k) -> return failure(0, "source credential must be a secret reference")
                    else -> v
                }
                builder.header(k, value)
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(maxBytes + 1) }
            val status = response.statusCode()
            when {
                bytes.size > maxBytes -> failure(status, "response is too large")
                status !in 200..299 -> failure(status, "upstream answered $status")
                else -> try {
                    linkedMapOf("ok" to true, "status" to status, "body" to mapper.readValue(bytes, Any::class.java))
                } catch (e: Exception) {
                    failure(status, "response is not JSON")
                }
            }
        } catch (e: java.net.http.HttpTimeoutException) {
            failure(0, "request timed out")
        } catch (e: Exception) {
            failure(0, "request failed")
        }
    }

    private fun failure(status: Int, error: String): Map<String, Any?> = linkedMapOf("ok" to false, "status" to status, "error" to error)
}
