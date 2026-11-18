package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** GAT-025: plain-text rolling log files under logs/, bounded by size and count, independent of the web viewer. */
class RollingTextLogTest {
    @TempDir lateinit var tmp: Path

    @Test fun linesLandInTheFileAndItRollsWithinTheCountBound() {
        val logs = StructuredLog(tmp, text = RollingTextLog(tmp, maxFileBytes = 1_024, maxFiles = 3))
        repeat(60) { logs.log(LogLevel.INFO, "svc", "message-$it-${"x".repeat(60)}", mapOf("n" to it)) }
        logs.close()
        val live = tmp.resolve("gateway.log")
        assertTrue(live.exists())
        assertTrue(live.readText().contains(" INFO  svc message-59-"))
        assertTrue(live.readText().contains("n=59"))
        assertTrue(tmp.resolve("gateway.log.1").exists())
        assertTrue(tmp.resolve("gateway.log.2").exists())
        assertFalse(tmp.resolve("gateway.log.3").exists()) { "maxFiles 3 keeps the live file plus two generations" }
        listOf("gateway.log", "gateway.log.1", "gateway.log.2").forEach {
            assertTrue(tmp.resolve(it).fileSize() <= 1_024) { "$it over the size bound" }
        }
        // Older lines were dropped with the oldest generation.
        assertFalse(listOf("gateway.log", "gateway.log.1", "gateway.log.2").any { tmp.resolve(it).readText().contains("message-0-") })
    }

    @Test fun aGatewayWritesTheFileByDefaultAndYamlTurnsItOff() {
        val on = tmp.resolve("on").also { java.nio.file.Files.createDirectories(it) }
        Gateway(on, port = 0).use { g -> g.start() }
        assertTrue(on.resolve("logs/gateway.log").readText().contains("gateway started"))

        val off = tmp.resolve("off").also { java.nio.file.Files.createDirectories(it) }
        off.resolve("gateway.yaml").writeText("profile: standard\nlogging:\n  files: false\n")
        Gateway(off, port = 0).use { g -> g.start() }
        assertFalse(off.resolve("logs/gateway.log").exists())
        assertTrue(off.resolve("logs/gateway.jsonl").exists())
    }

    @Test fun settingsParseDefaultsAndRejectBadValues() {
        assertEquals(LogFileSettings(), LogFileSettings.parse(emptyMap()))
        assertEquals(LogFileSettings(true, 64, 2), LogFileSettings.parse(mapOf("maxFileKb" to 64, "maxFiles" to 2)))
        assertThrows(IllegalArgumentException::class.java) { LogFileSettings.parse(mapOf("maxFiles" to 0)) }
        assertThrows(IllegalArgumentException::class.java) { LogFileSettings.parse(mapOf("bogus" to 1)) }
    }
}
