package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class StructuredLogTest {
    @TempDir lateinit var tmp: Path

    @Test fun levelChangeFiltersTheNextRecordWithoutRestart() {
        val logs = StructuredLog(tmp)
        assertFalse(logs.log(LogLevel.DEBUG, "org.oip.http", "hidden"))
        assertTrue(logs.query(loggerPrefix = "org.oip.http").isEmpty())
        logs.setLevel("org.oip", LogLevel.DEBUG)
        assertTrue(logs.log(LogLevel.DEBUG, "org.oip.http", "visible"))
        assertFalse(logs.log(LogLevel.DEBUG, "other", "no"))
        logs.setLevel("org.oip.http", LogLevel.ERROR)
        assertFalse(logs.log(LogLevel.INFO, "org.oip.http", "dropped"))
        assertTrue(logs.log(LogLevel.DEBUG, "org.oip.other", "still"))
        assertTrue(logs.log(LogLevel.ERROR, "org.oip.http", "kept"))
        val messages = logs.query().map { it.message }
        assertEquals(listOf("visible", "still", "kept"), messages)
        assertEquals(listOf("kept"), logs.query(min = LogLevel.ERROR, loggerPrefix = "org.oip.http").map { it.message })
        val line = logs.download().toString(Charsets.UTF_8)
        assertTrue(line.contains("\"message\":\"visible\""))
        assertFalse(line.contains("hidden"))
        logs.close()
    }

    @Test fun levelsPersistAcrossReopenAndFilesRotate() {
        // One retained generation: keep the ceiling above a single line so the first rotation
        // still holds message-0, then write one more record that does not rotate again.
        val first = StructuredLog(tmp, maxFileBytes = 2_000, ringSize = 2)
        first.setLevel("org.oip.http", LogLevel.WARN)
        var i = 0
        while (!tmp.resolve("gateway.jsonl.1").exists() && i < 40) {
            first.log(LogLevel.INFO, "svc", "message-$i-${"x".repeat(80)}")
            i++
        }
        assertTrue(tmp.resolve("gateway.jsonl.1").exists()) { "expected exactly one rotation" }
        first.log(LogLevel.INFO, "svc", "late-marker")
        assertEquals("late-marker", first.query().last().message)
        assertEquals(2, first.query().size)
        val download = first.download().toString(Charsets.UTF_8)
        assertTrue(download.contains("message-0-")) { download }
        assertTrue(download.contains("late-marker"))
        first.close()
        val second = StructuredLog(tmp)
        assertEquals(LogLevel.WARN, second.level("org.oip.http"))
        assertEquals(LogLevel.INFO, second.level("svc"))
        second.close()
        assertTrue(tmp.resolve("levels.tsv").readText().contains("org.oip.http\tWARN"))
    }
}
