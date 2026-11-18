package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class PlcResourceWindowsTest {
    @TempDir lateinit var tmp: Path

    private fun service(processors: Int = 4) = PlcResourceService(
        dir = tmp.resolve("stage"),
        osWindows = true,
        processors = processors,
        memoryBytes = 8L * 1024 * 1024 * 1024,
        windowsResources = tmp.resolve("plc-resources.json"),
    )

    @Test fun windowsRefusesTheOnlyCoreAndAppliesAffinityFiles() {
        val only = service(1)
        val refused = only.preview(1, 1, false)
        assertEquals(false, refused["ok"])
        assertTrue((refused["errors"] as List<*>).isNotEmpty())

        val split = service()
        val applied = split.apply(1, 2, true)
        assertEquals(true, applied["ok"])
        assertEquals(false, applied["rebootRequired"])
        assertEquals(true, applied["applied"])
        val text = tmp.resolve("plc-resources.json").readText()
        assertTrue(text.contains("\"plcCpus\":[3]"))
        assertTrue(text.contains("cannot fully isolate"))
        assertTrue(tmp.resolve("gateway-jvm.txt").readText().contains("-Xmx2g"))
        assertEquals("windows", split.status()["platform"])

        val reverted = split.revert()
        assertEquals(true, reverted["applied"])
        assertFalse(tmp.resolve("plc-resources.json").exists())
        assertFalse(tmp.resolve("gateway-jvm.txt").exists())
    }
}
