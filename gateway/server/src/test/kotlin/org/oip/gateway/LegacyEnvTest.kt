package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LegacyEnvTest {
    @Test fun `an ERIC name fills the older key`() {
        val out = LegacyEnv.normalize(mapOf("ERIC_PROFILE" to "edge"))
        assertEquals("edge", out["OIP_PROFILE"])
    }

    @Test fun `an ERIC name wins when both are set`() {
        val out = LegacyEnv.normalize(mapOf("ERIC_PROFILE" to "hub", "OIP_PROFILE" to "edge"))
        assertEquals("hub", out["OIP_PROFILE"])
        assertEquals("hub", out["ERIC_PROFILE"])
    }
}
