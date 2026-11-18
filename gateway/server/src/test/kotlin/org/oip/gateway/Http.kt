package org.oip.gateway

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Minimal JDK HTTP client for API tests. Keeps the session cookie and sends the CSRF token on unsafe methods. */
class Http(port: Int) {
    data class Res(val status: Int, val raw: ByteArray, val headers: Map<String, List<String>>) {
        val body: String get() = raw.toString(Charsets.UTF_8)
        fun header(n: String) = headers.entries.firstOrNull { it.key.equals(n, true) }?.value?.firstOrNull()
        fun headers(n: String) = headers.entries.firstOrNull { it.key.equals(n, true) }?.value ?: emptyList()
        val json: Map<String, Any?> get() = Json.parse(body)
    }
    private val base = "http://127.0.0.1:$port"
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val cookies = linkedMapOf<String, String>()
    private var bearer: String? = null
    var csrf: String? = null
    /** When false, unsafe requests do not send [csrf]. */
    var sendCsrf: Boolean = true
    /** Sent as X-Forwarded-For. The gateway uses it only when the socket peer is a trusted proxy. */
    var forwardedFor: String? = null

    fun sessionCookie(): String? = cookies[HttpAuth.COOKIE]

    fun authorizeBearer(token: String) {
        bearer = token
        cookies.clear()
        csrf = null
    }

    fun login(username: String, password: String): Res {
        val r = post("/api/v1/auth/login", Json.write(mapOf("username" to username, "password" to password)))
        if (r.status == 200) csrf = r.json["csrfToken"] as String
        return r
    }

    fun send(method: String, path: String, body: ByteArray? = null, type: String = "application/json", headers: Map<String, String> = emptyMap()): Res {
        val h = linkedMapOf<String, String>()
        if (bearer != null) h["Authorization"] = "Bearer $bearer"
        else if (cookies.isNotEmpty()) h["Cookie"] = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val unsafe = method.uppercase() in setOf("POST", "PUT", "PATCH", "DELETE")
        if (unsafe && sendCsrf && bearer == null && csrf != null) h[HttpAuth.CSRF_HEADER] = csrf!!
        forwardedFor?.let { h["X-Forwarded-For"] = it }
        h.putAll(headers)
        val b = HttpRequest.newBuilder(URI.create(base + path))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        if (body != null) b.header("Content-Type", type)
        h.forEach { (k, v) -> b.header(k, v) }
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
        val res = Res(r.statusCode(), r.body(), r.headers().map())
        absorb(res)
        return res
    }
    fun get(p: String) = send("GET", p)
    fun delete(p: String, headers: Map<String, String> = emptyMap()) = send("DELETE", p, headers = headers)
    fun put(p: String, json: String) = send("PUT", p, json.toByteArray())
    fun post(p: String, json: String) = send("POST", p, json.toByteArray())
    fun postRaw(p: String, bytes: ByteArray, type: String) = send("POST", p, bytes, type)

    private fun absorb(res: Res) {
        for (line in res.headers("set-cookie")) {
            val part = line.substringBefore(';').trim()
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            val maxAge = Regex("(?i)\\bmax-age=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
            if (value.isEmpty() || maxAge == 0L) cookies.remove(name) else cookies[name] = value
        }
    }
}
