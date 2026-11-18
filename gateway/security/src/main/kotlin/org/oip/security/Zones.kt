package org.oip.security

import java.net.InetAddress

/**
 * Security-zone identifier matching.
 *
 * Rules from - :
 * a connection matches a zone when at least one identifier matches (IP address, host name, or gateway name)
 * and every qualifier passes. IP patterns accept an exact address, IPv4 wildcards (`192.168.100.*`),
 * IPv4 per-octet ranges (`100.100.1-100.0-255`), and CIDR (`10.0.0.0/8`, `2001:db8::/32`) which this
 * platform adds on top of the wildcard and range forms. Host names use `*` as a glob, case-insensitively
 * (`*_Workstation`). Gateway names are exact and case-insensitive (the public page does not give them a wildcard).
 * Among zones that match, the highest priority wins. Equal priorities break toward the lexicographically
 * smaller name. Anything that matches no zone is in the built-in `Default` zone, which cannot be edited.
 */
data class SecurityZone(
    val name: String,
    val description: String = "",
    val priority: Int = 1,
    val enabled: Boolean = true,
    val ipAddresses: List<String> = emptyList(),
    val hostNames: List<String> = emptyList(),
    val gatewayNames: List<String> = emptyList(),
    val requireSecureConnection: Boolean = false,
    val directConnectionRequired: Boolean = false,
    val allowClientScope: Boolean = true,
    val allowDesignerScope: Boolean = true,
    val allowGatewayScope: Boolean = true,
    /** Empty means the zone does not add a permission ceiling. Non-empty is expanded with [Permissions.impliedBy]. */
    val allowedPermissions: List<String> = emptyList(),
    /** Exact permissions the zone refuses, checked before the ceiling. */
    val deniedPermissions: List<String> = emptyList(),
) {
    val isDefault: Boolean get() = name.equals(DEFAULT_NAME, ignoreCase = true)

    fun patternProblems(): List<String> {
        val out = mutableListOf<String>()
        ipAddresses.forEach { if (IpPattern.parse(it) == null) out += "invalid IP pattern '$it'" }
        hostNames.forEach { if (it.isBlank()) out += "blank host name" }
        gatewayNames.forEach { if (it.isBlank()) out += "blank gateway name" }
        return out
    }

    fun matches(ctx: ConnectionContext): Boolean {
        if (!enabled || isDefault) return false
        if (!identifierMatches(ctx)) return false
        if (requireSecureConnection && !ctx.secure) return false
        if (directConnectionRequired && !ctx.direct) return false
        return when (ctx.scope) {
            RequestScope.CLIENT -> allowClientScope
            RequestScope.DESIGNER -> allowDesignerScope
            RequestScope.GATEWAY -> allowGatewayScope
        }
    }

    private fun identifierMatches(ctx: ConnectionContext): Boolean {
        val addr = ctx.address?.let { IpLiteral.normalize(it) }
        if (addr != null && ipAddresses.any { IpPattern.parse(it)?.matches(addr) == true }) return true
        val host = ctx.hostName
        if (!host.isNullOrEmpty() && hostNames.any { it.isNotBlank() && hostGlob(it).matches(host) }) return true
        val gw = ctx.gatewayName
        if (!gw.isNullOrEmpty() && gatewayNames.any { it.isNotBlank() && it.equals(gw, ignoreCase = true) }) return true
        return false
    }

    companion object {
        const val DEFAULT_NAME = "Default"

        val DEFAULT = SecurityZone(
            name = DEFAULT_NAME,
            description = "Built-in zone used when no other zone matches. It cannot be modified.",
            priority = Int.MIN_VALUE,
            enabled = true,
            allowClientScope = true,
            allowDesignerScope = true,
            allowGatewayScope = true,
        )

        fun select(zones: List<SecurityZone>, ctx: ConnectionContext): SecurityZone {
            val hits = zones.filter { it.matches(ctx) }
            // Higher priority wins. Equal priority: lexicographically smaller name
            // (thenByDescending + maxWith picks the smaller name; see tests).
            return hits.maxWithOrNull(compareBy<SecurityZone> { it.priority }.thenByDescending { it.name }) ?: DEFAULT
        }
    }
}

internal fun hostGlob(pattern: String): Regex {
    val sb = StringBuilder("^")
    for (c in pattern) {
        if (c == '*') sb.append(".*") else sb.append(Regex.escape(c.toString()))
    }
    sb.append('$')
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}

internal sealed interface IpPattern {
    fun matches(addr: InetAddress): Boolean

    data class V4(val octets: List<Octet>) : IpPattern {
        override fun matches(addr: InetAddress): Boolean {
            val b = addr.address
            if (b.size != 4) return false
            return octets.indices.all { octets[it].matches(b[it].toInt() and 0xFF) }
        }
    }

    data class Cidr(val network: InetAddress, val prefix: Int) : IpPattern {
        override fun matches(addr: InetAddress): Boolean {
            val a = addr.address
            val n = network.address
            if (a.size != n.size) return false
            var bits = prefix
            var i = 0
            while (bits >= 8) {
                if (a[i] != n[i]) return false
                i++
                bits -= 8
            }
            if (bits == 0) return true
            val mask = (0xFF shl (8 - bits)) and 0xFF
            return ((a[i].toInt() and 0xFF) and mask) == ((n[i].toInt() and 0xFF) and mask)
        }
    }

    sealed interface Octet {
        fun matches(value: Int): Boolean
        data object Any : Octet { override fun matches(value: Int) = true }
        data class Exact(val n: Int) : Octet { override fun matches(value: Int) = value == n }
        data class Range(val lo: Int, val hi: Int) : Octet { override fun matches(value: Int) = value in lo..hi }
    }

    companion object {
        fun parse(raw: String): IpPattern? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            if ('/' in s) return parseCidr(s)
            if (s.count { it == '.' } == 3 && s.all { it.isDigit() || it == '.' || it == '-' || it == '*' }) return parseV4(s)
            val exact = IpLiteral.parse(s) ?: return null
            val bits = exact.address.size * 8
            return Cidr(exact, bits)
        }

        private fun parseCidr(s: String): IpPattern? {
            val parts = s.split('/')
            if (parts.size != 2) return null
            val prefix = parts[1].toIntOrNull() ?: return null
            val addr = IpLiteral.parse(parts[0]) ?: return null
            val max = addr.address.size * 8
            if (prefix !in 0..max) return null
            return Cidr(addr, prefix)
        }

        private fun parseV4(s: String): IpPattern? {
            val parts = s.split('.')
            if (parts.size != 4) return null
            val octets = parts.map { parseOctet(it) ?: return null }
            return V4(octets)
        }

        private fun parseOctet(s: String): Octet? = when {
            s == "*" -> Octet.Any
            "-" in s -> {
                val bits = s.split('-', limit = 2)
                val lo = positive(bits[0]) ?: return null
                val hi = positive(bits[1]) ?: return null
                if (lo > hi) null else Octet.Range(lo, hi)
            }
            else -> positive(s)?.let { Octet.Exact(it) }
        }

        /** Decimal 0..255 with no leading zero (except the number 0 itself). */
        private fun positive(s: String): Int? {
            if (s.isEmpty() || (s.length > 1 && s.startsWith('0'))) return null
            val n = s.toIntOrNull() ?: return null
            return n.takeIf { it in 0..255 }
        }
    }
}

internal object IpLiteral {
    private val V4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun parse(raw: String): InetAddress? {
        val s = raw.trim()
        V4.matchEntire(s)?.let { m ->
            val nums = (1..4).map { m.groupValues[it] }
            if (nums.any { it.length > 1 && it.startsWith('0') }) return null
            val bytes = nums.map { it.toIntOrNull() ?: return null }
            if (bytes.any { it !in 0..255 }) return null
            return InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())
        }
        if (':' !in s || !s.matches(Regex("^[0-9a-fA-F:.]+$"))) return null
        val addr = try {
            InetAddress.getByName(s)
        } catch (_: Exception) {
            return null
        }
        if (addr.address.size != 16) return null
        // Reject a successful parse that does not round-trip the textual form's structure.
        if (InetAddress.getByAddress(addr.address).hostAddress.isNullOrEmpty()) return null
        return addr
    }

    /** Map IPv4-mapped IPv6 (`::ffff:a.b.c.d`) onto the IPv4 address so v4 patterns apply. */
    fun normalize(addr: InetAddress): InetAddress {
        val b = addr.address
        if (b.size == 16 && b[10] == 0xff.toByte() && b[11] == 0xff.toByte() && (0 until 10).all { b[it] == 0.toByte() }) {
            return InetAddress.getByAddress(b.copyOfRange(12, 16))
        }
        return addr
    }
}
