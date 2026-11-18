package org.oip.gateway

import io.javalin.http.Context

/**
 * How the browser reached the gateway when a reverse proxy sits in front of it (HMP-088).
 *
 * `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-Prefix` are honored only when the socket peer is listed in
 * `http.trustedProxies`, the same setting that gates `X-Forwarded-For` ([ClientAddresses]). From any other peer the
 * headers are ignored, so a caller cannot make the gateway build links, cookies or redirects for an address of their
 * choosing. Every value is validated; a malformed one falls back to what the socket itself saw. Only the first hop of
 * a comma separated list is read (the one the outermost proxy set).
 */
object ForwardedHeaders {
    private val HOST = Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$|^\\[[0-9A-Fa-f:.]+](:[0-9]{1,5})?$")
    private val SEGMENT = Regex("^[A-Za-z0-9._~-]{1,64}$")

    /** `https` or `http` from [proto] when [direct] is trusted; otherwise [socketScheme]. */
    fun scheme(direct: String, socketScheme: String, proto: String?, trusted: List<String>): String {
        if (!isTrusted(direct, trusted)) return socketScheme
        return when (first(proto)?.lowercase()) {
            "https" -> "https"
            "http" -> "http"
            else -> socketScheme
        }
    }

    /** Host (with optional port) from [forwardedHost] when [direct] is trusted and it is valid; otherwise [hostHeader]. */
    fun host(direct: String, hostHeader: String?, forwardedHost: String?, trusted: List<String>): String? {
        if (!isTrusted(direct, trusted)) return hostHeader
        val candidate = first(forwardedHost) ?: return hostHeader
        return if (HOST.matches(candidate)) candidate else hostHeader
    }

    /**
     * Path prefix such as `/plant` from [prefix] when [direct] is trusted, else "". Always starts with `/` and has no
     * trailing slash. A value with an empty, `.` or `..` segment, or any character outside the safe set, is dropped.
     */
    fun prefix(direct: String, prefix: String?, trusted: List<String>): String {
        if (!isTrusted(direct, trusted)) return ""
        val given = first(prefix) ?: return ""
        if (given.contains("//")) return ""
        val raw = given.trim('/')
        if (raw.isEmpty()) return ""
        val parts = raw.split('/')
        if (parts.any { it == "." || it == ".." || !SEGMENT.matches(it) }) return ""
        return "/" + parts.joinToString("/")
    }

    fun isTrusted(direct: String, trusted: List<String>): Boolean = trusted.any { ClientAddresses.matches(direct, it) }

    private fun first(value: String?): String? = value?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/** The scheme the browser used: `X-Forwarded-Proto` from a trusted proxy, else the connection's own. */
fun Context.viaScheme(gw: Gateway): String =
    ForwardedHeaders.scheme(ip(), scheme(), header("X-Forwarded-Proto"), gw.settings.trustedProxies)

/** The host the browser used: `X-Forwarded-Host` from a trusted proxy, else `Host`. */
fun Context.viaHost(gw: Gateway): String? =
    ForwardedHeaders.host(ip(), header("Host"), header("X-Forwarded-Host"), gw.settings.trustedProxies)

/** The path prefix the proxy publishes the gateway under (`X-Forwarded-Prefix` from a trusted proxy), or "". */
fun Context.viaPrefix(gw: Gateway): String =
    ForwardedHeaders.prefix(ip(), header("X-Forwarded-Prefix"), gw.settings.trustedProxies)
