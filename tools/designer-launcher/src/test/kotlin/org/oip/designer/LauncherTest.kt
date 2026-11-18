package org.oip.designer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LauncherTest {
    @Test fun defaultGatewayOpensTheDesignerPath() {
        val request = parseLauncherArgs(emptyArray(), emptyMap())
        assertEquals("http://127.0.0.1:8090/designer", request.url)
        assertFalse(request.printOnly)
    }

    @Test fun gatewayFlagAndTrailingSlash() {
        assertEquals(
            "https://plant.example:8043/designer",
            parseLauncherArgs(arrayOf("--gateway", "https://plant.example:8043/"), emptyMap()).url,
        )
    }

    @Test fun anExistingDesignerPathIsNotDoubled() {
        assertEquals(
            "http://127.0.0.1:8090/designer",
            designerUrl("http://127.0.0.1:8090/designer"),
        )
    }

    @Test fun printOnlyAndEnv() {
        assertTrue(parseLauncherArgs(arrayOf("--print-only"), emptyMap()).printOnly)
        assertEquals(
            "http://gw.example/designer",
            parseLauncherArgs(emptyArray(), mapOf("OIP_GATEWAY_URL" to "http://gw.example")).url,
        )
    }

    @Test fun rejectsANonHttpGateway() {
        assertThrows<IllegalArgumentException> { designerUrl("file:///tmp") }
        assertThrows<IllegalArgumentException> { parseLauncherArgs(arrayOf("--nope"), emptyMap()) }
    }
}
