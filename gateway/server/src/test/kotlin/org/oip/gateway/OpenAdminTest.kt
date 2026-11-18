package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAdminTest {
    @Test fun noArgsStillStartsTheGateway() {
        val launch = classifyLaunch(arrayOf("--data-dir", "data", "--port", "0"), emptyMap())
        assertFalse(launch.openAdmin)
        assertArrayEquals(arrayOf("--data-dir", "data", "--port", "0"), launch.gatewayArgs)
    }

    @Test fun openAdminIsTheShortcutPath() {
        val launch = classifyLaunch(arrayOf("open-admin"), emptyMap())
        assertTrue(launch.openAdmin)
        assertEquals(DEFAULT_ADMIN_URL, launch.adminUrl)
        assertEquals(0, launch.gatewayArgs.size)
    }

    @Test fun dataDirWinsOverTheShortcutArgument() {
        val launch = classifyLaunch(arrayOf("--data-dir", "C:\\data", "open-admin"), emptyMap())
        assertFalse(launch.openAdmin)
        assertArrayEquals(arrayOf("--data-dir", "C:\\data"), launch.gatewayArgs)
    }

    @Test fun printOnlyDoesNotChangeGatewayArguments() {
        assertThrows<IllegalArgumentException> { parseArgs(arrayOf("--print-only")) }
    }

    @Test fun adminUrlOverrideMustBeHttp() {
        assertEquals("https://gw.example/admin", classifyLaunch(arrayOf("open-admin"), mapOf("OIP_ADMIN_URL" to "https://gw.example/admin")).adminUrl)
        assertThrows<IllegalArgumentException> {
            classifyLaunch(arrayOf("open-admin"), mapOf("OIP_ADMIN_URL" to "file:///tmp"))
        }
    }
}
