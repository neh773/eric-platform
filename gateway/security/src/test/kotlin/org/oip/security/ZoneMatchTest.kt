package org.oip.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Identifier and qualifier rules from
 */
class ZoneMatchTest {
    private fun addr(text: String): InetAddress = InetAddress.getByName(text)
    private fun ctx(
        ip: String? = null,
        host: String? = null,
        gateway: String? = null,
        secure: Boolean = true,
        direct: Boolean = true,
        scope: RequestScope = RequestScope.GATEWAY,
    ) = ConnectionContext(ip?.let { addr(it) }, host, gateway, secure, direct, scope)

    private fun zone(
        name: String,
        priority: Int = 10,
        ips: List<String> = emptyList(),
        hosts: List<String> = emptyList(),
        gateways: List<String> = emptyList(),
        secure: Boolean = false,
        direct: Boolean = false,
        client: Boolean = true,
        designer: Boolean = true,
        gatewayScope: Boolean = true,
        enabled: Boolean = true,
    ) = SecurityZone(
        name, priority = priority, enabled = enabled,
        ipAddresses = ips, hostNames = hosts, gatewayNames = gateways,
        requireSecureConnection = secure, directConnectionRequired = direct,
        allowClientScope = client, allowDesignerScope = designer, allowGatewayScope = gatewayScope,
    )

    @Test fun cidrWildcardRangeAndExactIpv4() {
        val z = zone("plant", ips = listOf("10.0.0.0/8", "192.168.100.*", "100.100.1-100.0-255", "172.16.5.5"))
        assertEquals("plant", SecurityZone.select(listOf(z), ctx(ip = "10.255.255.255")).name)
        assertEquals("plant", SecurityZone.select(listOf(z), ctx(ip = "10.0.0.1")).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(ip = "11.0.0.1")).name)
        assertEquals("plant", SecurityZone.select(listOf(z), ctx(ip = "192.168.100.50")).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(ip = "192.168.101.50")).name)
        assertEquals("plant", SecurityZone.select(listOf(z), ctx(ip = "100.100.50.10")).name)
        assertEquals("plant", SecurityZone.select(listOf(z), ctx(ip = "100.100.1.0")).name)
        assertEquals("plant", SecurityZone.select(listOf(z), ctx(ip = "100.100.100.255")).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(ip = "100.100.101.10")).name)
        assertEquals("plant", SecurityZone.select(listOf(z), ctx(ip = "172.16.5.5")).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(ip = "172.16.5.6")).name)
        assertEquals("slash32", SecurityZone.select(listOf(zone("slash32", ips = listOf("10.1.2.3/32"))), ctx(ip = "10.1.2.3")).name)
        assertEquals("Default", SecurityZone.select(listOf(zone("slash32", ips = listOf("10.1.2.3/32"))), ctx(ip = "10.1.2.4")).name)
    }

    @Test fun ipv6CidrAndMappedIpv4() {
        val z = zone("v6", ips = listOf("2001:db8::/32"))
        assertEquals("v6", SecurityZone.select(listOf(z), ctx(ip = "2001:db8::1")).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(ip = "2001:db9::1")).name)
        val v4 = zone("lan", ips = listOf("10.0.0.0/8"))
        assertEquals("lan", SecurityZone.select(listOf(v4), ctx(ip = "::ffff:10.1.2.3")).name)
    }

    @Test fun invalidPatternsAreRejectedAndDoNotMatch() {
        assertNull(IpPattern.parse("10.0.0.256"))
        assertNull(IpPattern.parse("10.0.0.0/33"))
        assertNull(IpPattern.parse("192.168.001.1"))
        assertNull(IpPattern.parse("*"))
        assertNull(IpPattern.parse("not-an-ip"))
        assertNull(IpPattern.parse("1::2::3"))
        val broken = zone("broken", ips = listOf("not-an-ip"))
        assertTrue(broken.patternProblems().isNotEmpty())
        assertEquals("Default", SecurityZone.select(listOf(broken), ctx(ip = "10.0.0.1")).name)
    }

    @Test fun hostnameGlobAndGatewayName() {
        val z = zone("hmi", hosts = listOf("*_Workstation", "*.hmi.plant.local"), gateways = listOf("Edge-1"))
        assertEquals("hmi", SecurityZone.select(listOf(z), ctx(host = "Joe_Workstation")).name)
        assertEquals("hmi", SecurityZone.select(listOf(z), ctx(host = "joe_workstation")).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(host = "Joe_Workstations")).name)
        assertEquals("hmi", SecurityZone.select(listOf(z), ctx(host = "panel.hmi.plant.local")).name)
        assertEquals("hmi", SecurityZone.select(listOf(z), ctx(gateway = "edge-1")).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(gateway = "edge-10")).name)
        assertEquals("Default", SecurityZone.select(listOf(zone("star", gateways = listOf("Edge*"))), ctx(gateway = "Edge1")).name)
    }

    @Test fun identifiersAreOrAndQualifiersAreAnd() {
        val z = zone("mixed", ips = listOf("10.0.0.0/8"), hosts = listOf("hmi-1"), gateways = listOf("Edge-1"), secure = true, direct = true, client = false)
        assertEquals("mixed", SecurityZone.select(listOf(z), ctx(ip = "10.1.1.1", secure = true, direct = true)).name)
        assertEquals("mixed", SecurityZone.select(listOf(z), ctx(host = "hmi-1", secure = true, direct = true)).name)
        assertEquals("mixed", SecurityZone.select(listOf(z), ctx(gateway = "Edge-1", secure = true, direct = true)).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(ip = "10.1.1.1", secure = false, direct = true)).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(ip = "10.1.1.1", secure = true, direct = false)).name)
        assertEquals("Default", SecurityZone.select(listOf(z), ctx(ip = "10.1.1.1", secure = true, direct = true, scope = RequestScope.CLIENT)).name)
        assertEquals("mixed", SecurityZone.select(listOf(z), ctx(ip = "10.1.1.1", secure = true, direct = true, scope = RequestScope.DESIGNER)).name)
        val noDesigner = zone("ops", ips = listOf("10.0.0.1"), designer = false)
        assertEquals("Default", SecurityZone.select(listOf(noDesigner), ctx(ip = "10.0.0.1", scope = RequestScope.DESIGNER)).name)
        val noGateway = zone("ops2", ips = listOf("10.0.0.1"), gatewayScope = false)
        assertEquals("Default", SecurityZone.select(listOf(noGateway), ctx(ip = "10.0.0.1", scope = RequestScope.GATEWAY)).name)
    }

    @Test fun priorityTieBreakDisabledAndDefault() {
        val low = zone("low", priority = 1, ips = listOf("10.0.0.0/8"))
        val high = zone("high", priority = 5, ips = listOf("10.1.0.0/16"))
        assertEquals("high", SecurityZone.select(listOf(low, high), ctx(ip = "10.1.2.3")).name)
        assertEquals("low", SecurityZone.select(listOf(low, high), ctx(ip = "10.2.2.3")).name)
        val alpha = zone("alpha", priority = 3, ips = listOf("10.9.0.0/16"))
        val beta = zone("beta", priority = 3, ips = listOf("10.9.0.0/16"))
        assertEquals("alpha", SecurityZone.select(listOf(beta, alpha), ctx(ip = "10.9.1.1")).name)
        val off = zone("off", priority = 100, ips = listOf("10.0.0.0/8"), enabled = false)
        assertEquals("low", SecurityZone.select(listOf(off, low), ctx(ip = "10.1.1.1")).name)
        assertEquals("Default", SecurityZone.select(emptyList(), ctx(ip = "8.8.8.8")).name)
        assertEquals(Int.MIN_VALUE, SecurityZone.DEFAULT.priority)
    }

    @Test fun qualifierFailureFallsThroughToTheNextZone() {
        val strict = zone("strict", priority = 50, ips = listOf("10.0.0.0/8"), secure = true)
        val open = zone("open", priority = 10, ips = listOf("10.0.0.0/8"), secure = false)
        assertEquals("open", SecurityZone.select(listOf(strict, open), ctx(ip = "10.0.0.9", secure = false)).name)
        assertEquals("strict", SecurityZone.select(listOf(strict, open), ctx(ip = "10.0.0.9", secure = true)).name)
    }
}
