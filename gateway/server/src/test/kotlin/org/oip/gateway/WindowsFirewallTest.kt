package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WindowsFirewallTest {
    private val calls = mutableListOf<List<String>>()
    private var windows = WindowsFirewall.isWindows
    private var command = WindowsFirewall.runCommand

    @BeforeEach fun isolate() {
        calls.clear()
        WindowsFirewall.resetForTest()
        WindowsFirewall.isWindows = { true }
        WindowsFirewall.runCommand = { calls.add(it.toList()) }
    }

    @AfterEach fun restore() {
        WindowsFirewall.isWindows = { false }
        WindowsFirewall.resetForTest()
        WindowsFirewall.isWindows = windows
        WindowsFirewall.runCommand = command
    }

    @Test fun serviceStopDeletesTheRuleThisProcessAdded() {
        WindowsFirewall.openForTest(8090, "ERIC Gateway")
        WindowsFirewall.removeOpenedRule()
        WindowsFirewall.removeOpenedRule()
        assertEquals(
            listOf(
                WindowsFirewall.deleteArgs("ERIC Gateway"),
                WindowsFirewall.addArgs(8090, "ERIC Gateway"),
                WindowsFirewall.deleteArgs("ERIC Gateway"),
            ),
            calls,
        )
        assertTrue(WindowsFirewall.addArgs(8090, "ERIC Gateway").contains("localport=8090"))
    }

    @Test fun aLateAllowAfterShutdownDoesNotRecreateTheRule() {
        WindowsFirewall.openForTest(8090, "ERIC Gateway")
        WindowsFirewall.removeOpenedRule()
        calls.clear()
        WindowsFirewall.allow(8090, "ERIC Gateway", ticket = 1)
        assertTrue(calls.isEmpty())
    }

    @Test fun otherOperatingSystemsDoNotTouchTheFirewall() {
        WindowsFirewall.isWindows = { false }
        WindowsFirewall.openForTest(8090, "ERIC Gateway")
        WindowsFirewall.removeOpenedRule()
        assertTrue(calls.isEmpty())
    }
}
