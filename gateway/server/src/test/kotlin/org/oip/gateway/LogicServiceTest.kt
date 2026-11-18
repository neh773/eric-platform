package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LogicServiceTest {
    @TempDir lateinit var tmp: Path

    @Test fun `rusty diagnostics keep the line and column`() {
        val text = """
            error[E048]: Could not resolve reference to not_a_keyword
               ┌─ program.st:4:5
        """.trimIndent()
        val found = PlcDiagnostics.parse(text)
        assertEquals(1, found.size)
        assertEquals(4, found[0].line)
        assertEquals(5, found[0].column)
        assertEquals("E048", found[0].code)
        assertTrue(found[0].message.contains("not_a_keyword"))
    }

    @Test fun `a force does not change a stopped output`() {
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()))
        logic.create("Pump")
        logic.saveMapping(mapOf(
            "outputs" to listOf(mapOf("name" to "Motor", "address" to "%QX1.0", "tag" to "pid/motor", "type" to "BOOL", "safe" to false)),
        ))
        logic.deploy("Pump")
        logic.force("Motor", true, output = true)
        val forced = logic.watch()
        assertEquals(true, forced["forcesActive"])
        assertEquals(true, (forced["variables"] as List<*>).filterIsInstance<Map<*, *>>()[0]["value"])
        logic.fault()
        val held = logic.watch()
        assertEquals("faulted", held["state"])
        assertEquals(false, held["forcesActive"])
        assertEquals(false, (held["variables"] as List<*>).filterIsInstance<Map<*, *>>()[0]["value"])
        logic.unforce("Motor")
        assertFalse(logic.watch()["forcesActive"] as Boolean)
    }

    @Test fun `a failed compile does not mark the program running`() {
        val logic = LogicService(tmp, PlcCompiler(null, listOf(PlcDiagnostic(2, 1, "bad token", "E048"))))
        logic.create("Pump")
        val result = logic.deploy("Pump")
        assertEquals(false, result["ok"])
        assertEquals("stopped", logic.status()["state"])
    }

    @Test fun `a failed compile does not hand a library to the engine`() {
        val engine = RecordingEngine()
        val logic = LogicService(tmp, PlcCompiler(null, listOf(PlcDiagnostic(2, 1, "bad token", "E048"))), engine)
        logic.create("Pump")
        val result = logic.deploy("Pump")
        assertEquals(false, result["ok"])
        assertEquals("stopped", result["state"])
        assertTrue(engine.deploys.isEmpty())
    }

    @Test fun `a clean deploy hands the library to the engine`() {
        val engine = RecordingEngine()
        val library = tmp.resolve("libPump.so")
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()) { library }, engine)
        logic.create("Pump")
        val result = logic.deploy("Pump")
        assertEquals(true, result["ok"])
        assertEquals(library.toString(), engine.deploys.single())
        assertEquals("running", result["state"])
        assertEquals(10, result["periodMs"])
    }

    @Test fun `a force on a faulted engine is sent but not published`() {
        val engine = RecordingEngine()
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()) { tmp.resolve("libPump.so") }, engine)
        logic.create("Pump")
        logic.saveMapping(mapOf(
            "outputs" to listOf(mapOf("name" to "Motor", "address" to "%QX1.0", "tag" to "pid/motor", "type" to "BOOL", "safe" to false)),
        ))
        logic.deploy("Pump")
        engine.state = "faulted"
        logic.force("Motor", true, output = true)
        assertEquals("pid/motor", engine.forces.single())
        val held = logic.watch()
        assertEquals("faulted", held["state"])
        assertEquals(false, held["forcesActive"])
        assertEquals(false, (held["variables"] as List<*>).filterIsInstance<Map<*, *>>()[0]["value"])
        assertEquals(false, (held["variables"] as List<*>).filterIsInstance<Map<*, *>>()[0]["forced"])
    }

    @Test fun `a real value is shown as a decimal and a decimal force keeps its bits`() {
        val engine = RecordingEngine()
        val fifty = java.lang.Double.doubleToRawLongBits(50.0)
        engine.analogBits = fifty
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()) { tmp.resolve("libPump.so") }, engine)
        logic.create("Pump")
        logic.saveMapping(mapOf(
            "inputs" to listOf(mapOf("name" to "SP", "address" to "%IL0", "tag" to "pid/sp", "type" to "LREAL", "safe" to 0.0)),
        ))
        logic.deploy("Pump")
        val shown = (logic.watch()["variables"] as List<*>).filterIsInstance<Map<*, *>>()[0]["value"] as Double
        assertEquals(50.0, shown, 1.0e-9)
        logic.force("SP", 12.5, output = false)
        assertEquals(java.lang.Double.doubleToRawLongBits(12.5), engine.sent.last())
    }
}

private class RecordingEngine : PlcControl {
    val deploys = mutableListOf<String>()
    val forces = mutableListOf<String>()
    val sent = mutableListOf<Any?>()
    var analogBits: Long? = null
    var state: String = "running"

    override fun connected(): Boolean = true

    override fun deploy(program: String, entry: String, instance: String, hwmap: String?): String? {
        deploys += program
        return null
    }

    override fun force(name: String, value: Any?, output: Boolean): String? {
        forces += name
        sent += value
        return null
    }

    override fun status(): Map<String, Any?> = mapOf(
        "state" to state,
        "forcesActive" to (state == "running"),
        "periodMs" to 10,
        "jitterP99Ns" to 100,
        "execP99Ns" to 50,
        "overruns" to 2,
    )

    override fun watch(): Map<String, Any?> {
        val row = if (analogBits != null) {
            mapOf("name" to "pid/sp", "address" to "%IL0", "bits" to analogBits, "forced" to false, "output" to false)
        } else {
            mapOf("name" to "pid/motor", "address" to "%QX1.0", "bits" to 1, "forced" to true, "output" to true)
        }
        return mapOf("state" to state, "forcesActive" to true, "variables" to listOf(row))
    }
}
