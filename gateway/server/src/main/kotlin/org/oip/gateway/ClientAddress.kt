package org.oip.gateway

/**
 * Client address for login throttling and audit.
 * `X-Forwarded-For` is honored only when the socket peer is listed in `http.trustedProxies`.
 * Otherwise the header is ignored so a caller cannot pick their own throttle bucket.
 */
object ClientAddresses {
    private val TOKEN = Regex("^[A-Fa-f0-9:.]+$")

    fun validRule(rule: String): Boolean {
        if ('/' !in rule) return rule.matches(TOKEN) && (ipv4(rule) != null || rule.contains(':'))
        val parts = rule.split('/', limit = 2)
        val bits = parts[1].toIntOrNull() ?: return false
        return bits in 0..32 && ipv4(parts[0]) != null
    }

    /** Left-most forwarded hop when [direct] is a trusted proxy; otherwise [direct]. */
    fun clientIp(direct: String, forwardedFor: String?, trusted: List<String>): String {
        if (forwardedFor.isNullOrBlank() || trusted.none { matches(direct, it) }) return direct
        val first = forwardedFor.split(',').firstOrNull()?.trim().orEmpty()
        if (first.length !in 1..64 || !first.matches(TOKEN)) return direct
        return first
    }

    fun matches(ip: String, rule: String): Boolean {
        if ('/' !in rule) return ip.equals(rule, ignoreCase = true)
        val addr = ipv4(ip) ?: return false
        val parts = rule.split('/', limit = 2)
        val network = ipv4(parts[0]) ?: return false
        val bits = parts[1].toIntOrNull() ?: return false
        if (bits !in 0..32) return false
        if (bits == 0) return true
        val mask = (0xffffffffL shl (32 - bits)) and 0xffffffffL
        return (addr and mask) == (network and mask)
    }

    private fun ipv4(text: String): Long? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var acc = 0L
        for (part in parts) {
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255 || (part.length > 1 && part.startsWith('0'))) return null
            acc = (acc shl 8) or v.toLong()
        }
        return acc
    }
}
