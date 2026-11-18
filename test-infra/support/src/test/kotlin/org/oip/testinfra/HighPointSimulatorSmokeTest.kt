package org.oip.testinfra

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * CI-sized PRF-012 smoke. The Simulator stack smoke job runs this with the
 * compose stack. Full size is `fullPoints` in test-infra/perf/prf-012.json
 * (`python3 -m bench prf012`, or OIP_PRF012_POINTS).
 */
@Tag("smoke")
@Timeout(180)
class HighPointSimulatorSmokeTest {
    @Test fun ciSizeServesEachProtocolOnANonLoopbackAddress() {
        val config = ObjectMapper().readTree(File(repoRoot(), "test-infra/perf/prf-012.json"))
        val override = System.getenv("OIP_PRF012_POINTS")?.trim().orEmpty()
        val expected = if (override.isNotEmpty()) override.toInt() else config.get("ciPoints").intValue()
        assertTrue(expected >= 1)
        if (override.isEmpty()) {
            assertTrue(expected < config.get("fullPoints").intValue(), "ci size must stay under the full address space")
        }
        val command = mutableListOf(Python.command(), "-m", "bench", "prf012")
        if (override.isNotEmpty()) {
            command += listOf("--points", override)
        } else {
            command += "--ci"
        }
        val process = ProcessBuilder(command)
            .directory(File(repoRoot(), "bench"))
            .redirectErrorStream(true)
            .apply { environment()["PYTHONUNBUFFERED"] = "1" }
            .start()
        val output = StringBuilder()
        val pump = Thread {
            process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(1024)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    output.append(buf, 0, n)
                }
            }
        }
        pump.isDaemon = true
        pump.start()
        val finished = process.waitFor(170, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        pump.join(5_000)
        val text = output.toString()
        assertTrue(finished, text)
        assertEquals(0, process.exitValue(), text)
        assertTrue(text.contains("PRF012 OK"), text)
        assertTrue(text.contains("points=$expected"), text)
        assertTrue(text.contains("modbus=ok"), text)
        assertTrue(text.contains("opcua=ok"), text)
        assertTrue(text.contains("mqtt=ok"), text)
        val host = Regex("""host=(\S+)""").find(text)?.groupValues?.get(1)
            ?: error("no host in $text")
        assertFalse(host.startsWith("127."), host)
        assertEquals(host, HighPointDriver.require(host))
    }
}
