package org.oip.mqtt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The P2-02 simulator already publishes Sparkplug B. PRF-012 refuses a loopback target,
 * so this connects to the address the simulator prints for `--require-networked`.
 */
class HighPointMqttTest {
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun readsThreePointsFromTheBenchSimulator() {
        val root = System.getProperty("oip.repoRoot") ?: error("oip.repoRoot")
        val sim = File(root, "bench/sim")
        val process = ProcessBuilder(
            python(), "server.py",
            "--require-networked",
            "--points", "3",
            "--percent", "0",
            "--metrics-per-device", "3",
            "--modbus-extra-ports", "0",
            "--modbus-port", "0",
            "--opcua-port", "0",
            "--mqtt-port", "0",
            "--http-port", "0",
        ).directory(sim).redirectErrorStream(true).start()
        val lines = CopyOnWriteArrayList<String>()
        val reader = Thread({
            process.inputStream.bufferedReader().useLines { seq -> seq.forEach { lines += it } }
        }, "bench-sim-log")
        reader.isDaemon = true
        reader.start()
        try {
            await("READY ${lines.joinToString()}") { lines.any { it == "READY" } || !process.isAlive }
            if (!process.isAlive) throw AssertionError(lines.joinToString("\n"))
            val bind = lines.first { it.startsWith("BIND=") }.substringAfter("=").trim()
            val port = lines.first { it.startsWith("MQTT_PORT=") }.substringAfter("=").trim().toInt()
            requireNonLoopback(bind)
            val connection = MqttConnection(
                MqttConnectionConfig(
                    name = "bench",
                    host = bind,
                    port = port,
                    clientId = "oip-highpoint",
                    keepAliveSeconds = 10,
                    sparkplugHostId = "oip-host",
                    sparkplugGroup = "OIPBENCH",
                    autoCreateTags = false,
                    reorderTimeoutMs = 2_000,
                    connectTimeoutMs = 3_000,
                ),
                secrets = { null },
            )
            connection.start()
            try {
                await("bench values ${connection.read("spb:OIPBENCH/Bench/D0/P0000000")}") {
                    (0..2).all { index ->
                        val sample = connection.read("spb:OIPBENCH/Bench/D0/P%07d".format(index))
                        sample.quality.statusCode == 0L && sample.value == index
                    }
                }
                assertEquals(0, connection.read("spb:OIPBENCH/Bench/D0/P0000000").value)
                assertEquals(1, connection.read("spb:OIPBENCH/Bench/D0/P0000001").value)
                assertEquals(2, connection.read("spb:OIPBENCH/Bench/D0/P0000002").value)
            } finally {
                connection.close()
            }
        } finally {
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
            reader.join(1_000)
        }
    }

    private fun requireNonLoopback(host: String) {
        val name = host.trim().lowercase()
        if (name.isEmpty() || name == "localhost" || name == "0.0.0.0" || name == "::1" || name.startsWith("127.")) {
            throw IllegalArgumentException("high-point driver target is loopback; PRF-012 requires a non-loopback address")
        }
        val address = InetAddress.getByName(name)
        if (address.isLoopbackAddress) {
            throw IllegalArgumentException("high-point driver target is loopback; PRF-012 requires a non-loopback address")
        }
    }

    private fun python(): String {
        for (name in listOf("python3", "python")) {
            val ok = try {
                val process = ProcessBuilder(name, "--version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                process.waitFor() == 0
            } catch (_: Exception) {
                false
            }
            if (ok) return name
        }
        return "python3"
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(30)
        }
        throw AssertionError(message)
    }
}
