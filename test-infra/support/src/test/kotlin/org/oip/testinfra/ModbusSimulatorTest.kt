package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ModbusSimulatorTest {
    @Test fun scriptedRegistersFollowTheFrozenWaveform() {
        withSimulator { modbusPort, httpPort ->
            val control = SimControl("http://127.0.0.1:$httpPort")
            assertTrue(control.healthy())
            ModbusTcpClient("127.0.0.1", modbusPort).use { client ->
                val sine = control.set(
                    mode = Waveform.Mode.SINE,
                    amplitude = 100.0,
                    offset = 0.0,
                    periodMs = 1000,
                    epochMs = 0,
                    frozen = true,
                    nowMs = 250,
                )
                assertEquals(100.0, sine.value, 1e-6)
                val sineRegs = client.readHolding(0, ModbusSimMap.COUNT)
                assertEquals(Waveform.Mode.SINE.ordinal, sineRegs[ModbusSimMap.MODE])
                assertEquals(Waveform.toRegister(sine.value), sineRegs[ModbusSimMap.VALUE_X100])

                val ramp = control.set(mode = Waveform.Mode.RAMP, nowMs = 250)
                assertEquals(25.0, ramp.value, 0.0)
                assertEquals(2500, client.readHolding(ModbusSimMap.VALUE_X100, 1)[0])

                val stepLow = control.set(mode = Waveform.Mode.STEP, amplitude = 80.0, offset = 5.0, nowMs = 499)
                assertEquals(5.0, stepLow.value, 0.0)
                val stepHigh = control.set(nowMs = 500)
                assertEquals(85.0, stepHigh.value, 0.0)
                assertEquals(Waveform.toRegister(85.0), client.readHolding(ModbusSimMap.VALUE_X100, 1)[0])

                control.set(mode = Waveform.Mode.CONSTANT, offset = 3.25, amplitude = 0.0)
                assertEquals(3.25, control.get().value, 0.0)

                client.writeSingle(ModbusSimMap.MODE, Waveform.Mode.RAMP.ordinal)
                client.writeMultiple(ModbusSimMap.AMPLITUDE_X100, intArrayOf(Waveform.toRegister(50.0), Waveform.toRegister(1.5)))
                val afterWrite = control.get()
                assertEquals(Waveform.Mode.RAMP, afterWrite.mode)
                assertEquals(50.0, afterWrite.amplitude, 0.0)
                assertEquals(1.5, afterWrite.offset, 0.0)

                val first = client.readHolding(ModbusSimMap.VALUE_X100, 1)[0]
                val second = client.readHolding(ModbusSimMap.VALUE_X100, 1)[0]
                assertEquals(first, second, "frozen value must not move between reads")

                val ex = assertThrows<ModbusException> { client.readHolding(ModbusSimMap.COUNT, 1) }
                assertEquals(2, ex.code)
                val illegal = assertThrows<ModbusException> { client.transact(byteArrayOf(1, 0, 0, 0, 1)) }
                assertEquals(1, illegal.code)
                assertArrayEquals(intArrayOf(Waveform.Mode.RAMP.ordinal), client.readHolding(ModbusSimMap.MODE, 1))
            }
        }
    }

    private fun withSimulator(body: (modbusPort: Int, httpPort: Int) -> Unit) {
        val sim = File(repoRoot(), "test-infra/sim")
        val process = ProcessBuilder(
            Python.command(), "-u", File(sim, "modbus/server.py").path,
            "--bind", "127.0.0.1", "--modbus-port", "0", "--http-port", "0",
        ).apply { environment()["PYTHONPATH"] = sim.path }.redirectErrorStream(true).start()
        val lines = LinkedBlockingQueue<String>()
        val pump = Thread {
            process.inputReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lines.offer(line)
                }
            }
        }
        pump.isDaemon = true
        pump.start()
        try {
            val modbus = awaitPort(process, lines, "MODBUS_PORT=")
            val http = awaitPort(process, lines, "HTTP_PORT=")
            awaitReady(process, lines)
            body(modbus, http)
        } finally {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            process.destroyForcibly()
        }
    }

    private fun awaitPort(process: Process, lines: LinkedBlockingQueue<String>, prefix: String): Int {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) error("simulator exited ${process.exitValue()}: $lines")
            val line = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (line.startsWith(prefix)) return line.substring(prefix.length).toInt()
            if (line == "READY") lines.offer(line)
        }
        error("timed out waiting for $prefix; saw $lines")
    }

    private fun awaitReady(process: Process, lines: LinkedBlockingQueue<String>) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) error("simulator exited ${process.exitValue()}: $lines")
            val line = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (line == "READY") return
        }
        error("simulator never became READY: $lines")
    }
}
