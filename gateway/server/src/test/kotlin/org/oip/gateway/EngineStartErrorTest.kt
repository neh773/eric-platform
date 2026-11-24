package org.oip.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class EngineStartErrorTest {
    @Test
    fun aMappedAddressTheProgramDoesNotDeclareSaysWhatToChange() {
        val text = engineStartError("2026-11-23 eric-plc: hwmap has no %QD0")
        assertTrue(text.contains("Tag mapping uses %QD0"), text)
        assertTrue(text.contains("AT %QD0"), text)
    }

    @Test
    fun otherFailuresKeepTheEngineLog() {
        assertEquals("the scan engine did not start: boom", engineStartError("boom"))
        assertEquals("the scan engine did not start", engineStartError(""))
    }
}
