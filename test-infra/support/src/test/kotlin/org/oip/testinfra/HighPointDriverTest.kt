package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * The high-point driver refuses 127.0.0.1. This is the PRF-012 target rule.
 * The compose-stack drivers are a different path and still use loopback in unit tests.
 */
class HighPointDriverTest {
    @Test fun driverRefusesLoopbackBeforeConnecting() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 300
            val refused = assertThrows<IllegalArgumentException> {
                HighPointDriver.connect("127.0.0.1", server.localPort)
            }
            assertTrue(refused.message!!.contains("loopback"), refused.message)
            assertThrows<SocketTimeoutException> { server.accept() }
        }
    }

    @Test fun namedLoopbackTargetsAreRefused() {
        for (host in listOf("localhost", "::1", "127.0.0.2", "0.0.0.0", "::ffff:127.0.0.1")) {
            val refused = assertThrows<IllegalArgumentException> { HighPointDriver.require(host) }
            assertTrue(refused.message!!.contains("loopback"), "$host ${refused.message}")
        }
    }

    @Test fun aUnicastAddressIsAccepted() {
        assertEquals("172.30.0.2", HighPointDriver.require("172.30.0.2"))
    }
}
