package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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

    /** 0.1.1: the packaged UIs are found with no variables, with ERIC_ or OIP_ names, and despite a stale path. */
    @Test fun `bundled web apps are found with either env name or none`(@TempDir dir: Path) {
        val bundledAdmin = Files.createDirectories(dir.resolve("app/admin"))
        val bundledWeb = Files.createDirectories(dir.resolve("app/web"))
        val custom = Files.createDirectories(dir.resolve("custom-admin"))
        assertEquals(bundledAdmin, AdminUi.locate(emptyMap(), null, bundledAdmin))
        assertEquals(bundledWeb, WebUi.locate(emptyMap(), null, bundledWeb))
        assertEquals(custom, AdminUi.locate(LegacyEnv.normalize(mapOf("ERIC_ADMIN_DIST" to custom.toString()), warn = false), null, bundledAdmin))
        assertEquals(custom, AdminUi.locate(LegacyEnv.normalize(mapOf("OIP_ADMIN_DIST" to custom.toString()), warn = false), null, bundledAdmin))
        assertEquals(bundledAdmin, AdminUi.locate(mapOf("OIP_ADMIN_DIST" to dir.resolve("gone").toString()), null, bundledAdmin))
        assertNull(AdminUi.locate(emptyMap(), null, null))
    }

    @Test fun `the desktop data folder follows ERIC_DATA_DIR then XDG`() {
        assertEquals(Path.of("/srv/eric"), desktopDataDir(LegacyEnv.normalize(mapOf("ERIC_DATA_DIR" to "/srv/eric"), warn = false)))
        assertEquals(Path.of("/x/eric/data"), desktopDataDir(mapOf("XDG_DATA_HOME" to "/x")))
        assertEquals(Path.of("/home/t/.local/share/eric/data"), desktopDataDir(mapOf("HOME" to "/home/t")))
    }
}
