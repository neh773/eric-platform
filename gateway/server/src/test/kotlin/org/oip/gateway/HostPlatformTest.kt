package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostPlatformTest {
    @Test fun windowsIsTheOnlyHostThatShowsThePlcBanner() {
        assertEquals("windows", HostPlatform.osName("Windows 11"))
        assertEquals("linux", HostPlatform.osName("Linux"))
        assertTrue(HostPlatform.body("Windows Server 2022")["windowsPlc"] as Boolean)
        assertFalse(HostPlatform.body("Linux")["windowsPlc"] as Boolean)
        val banner = HostPlatform.body("Windows 10")["banner"] as String
        assertTrue(banner.contains("real-time kernel"))
        assertTrue(banner.contains("Scan times under 100 mS are difficult to execute. For higher performance, use on Linux."))
    }
}
