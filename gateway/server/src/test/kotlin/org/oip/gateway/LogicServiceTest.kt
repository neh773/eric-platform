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

    @Test fun `online mode - forces are released on stop, on redeploy and by release all`() {
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()))
        logic.create("Pump")
        logic.saveMapping(mapOf(
            "inputs" to listOf(mapOf("name" to "Level", "address" to "%ID0", "tag" to "lvl")),
            "outputs" to listOf(mapOf("name" to "Motor", "address" to "%QX1.0", "tag" to "pid/motor", "safe" to false)),
        ))
        logic.deploy("Pump")
        val rows = (logic.watch()["variables"] as List<*>).filterIsInstance<Map<*, *>>()
        assertEquals("REAL", rows.first { it["name"] == "Level" }["type"], "the type follows the address size when none is given")
        logic.force("Motor", true, output = true)
        logic.force("Level", 42.5, output = false)
        assertEquals(listOf("Level", "Motor"), logic.forcedNames())
        logic.stop()
        assertEquals(emptyList<String>(), logic.forcedNames())
        logic.start()
        logic.force("Motor", true, output = true)
        logic.deploy("Pump")
        assertEquals(emptyList<String>(), logic.forcedNames())
        logic.force("Motor", true, output = true)
        assertEquals(listOf("Motor"), logic.releaseAll())
        assertFalse(logic.watch()["forcesActive"] as Boolean)
    }

    @Test fun `online mode - write once writes the mapped tag`() {
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()))
        logic.saveMapping(mapOf("inputs" to listOf(mapOf("name" to "Run", "address" to "%IX0.0", "tag" to "mem/run"))))
        val written = mutableListOf<Pair<String, Any?>>()
        logic.writeTag = { tag, value -> written += tag to value }
        logic.writeOnce("Run", true)
        assertEquals(listOf("mem/run" to true), written)
        assertTrue(runCatching { logic.writeOnce("Nope", 1) }.isFailure)
    }

    @Test fun `a resumed program can be stopped and started without a redeploy`() {
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()))
        logic.create("Station")
        logic.resumed(ManagedPlc.Deployed(tmp.resolve("build/libStation.so").toString(), "Station", "Station_instance", null, true))
        assertEquals("Station", logic.program)
        logic.stop()
        logic.start()
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


    @Test fun `a string safe value is stored as a typed number`() {
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()))
        logic.saveMapping(mapOf(
            "outputs" to listOf(mapOf("name" to "Count", "address" to "%QW0", "tag" to "pid/count", "type" to "INT", "safe" to "0")),
            "periodMs" to 250,
            "maxConsecutiveOverruns" to 12,
        ))
        val saved = logic.mapping()
        val row = (saved["outputs"] as List<*>).filterIsInstance<Map<*, *>>().single()
        assertEquals(0L, (row["safe"] as Number).toLong())
        assertEquals(250, saved["periodMs"])
        assertEquals(12, saved["maxConsecutiveOverruns"])
    }

    @Test fun `status shows a watchdog latch instead of running`() {
        val engine = RecordingEngine()
        engine.state = "running"
        engine.watchdog = true
        engine.fault = "watchdog"
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()) { tmp.resolve("libPump.so") }, engine)
        logic.create("Pump")
        logic.deploy("Pump")
        val status = logic.status()
        assertEquals("faulted", status["state"])
        assertEquals("watchdog", status["message"])
        assertEquals("watchdog", status["fault"])
    }

    @Test fun `an unset scan period defaults to 100 ms`() {
        val logic = LogicService(tmp, PlcCompiler(null, emptyList()))
        assertEquals(100, logic.status()["periodMs"])
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
    var watchdog: Boolean = false
    var fault: String? = null

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

    override fun status(): Map<String, Any?> = buildMap {
        put("state", state)
        put("forcesActive", state == "running")
        put("periodMs", 10)
        put("jitterP99Ns", 100)
        put("execP99Ns", 50)
        put("overruns", 2)
        if (fault != null) put("fault", fault)
        put("watchdog", watchdog)
    }

    override fun watch(): Map<String, Any?> {
        val row = if (analogBits != null) {
            mapOf("name" to "pid/sp", "address" to "%IL0", "bits" to analogBits, "forced" to false, "output" to false)
        } else {
            mapOf("name" to "pid/motor", "address" to "%QX1.0", "bits" to 1, "forced" to true, "output" to true)
        }
        return mapOf("state" to state, "forcesActive" to true, "variables" to listOf(row))
    }
}

class LogicHealthTest {
    @org.junit.jupiter.api.Test fun `a dead engine is stopped with its error`() {
        val presented = presentLogicHealth("running", "", null, false, "the scan engine exited (code 1)", false)
        assertEquals("stopped", presented.state)
        assertEquals("the scan engine exited (code 1)", presented.message)
    }

    @org.junit.jupiter.api.Test fun `a latched watchdog is faulted even if the control socket still says running`() {
        val presented = presentLogicHealth(
            "running",
            "",
            mapOf("state" to "running", "periodMs" to 10, "execP99Ns" to 0),
            true,
            null,
            true,
        )
        assertEquals("faulted", presented.state)
        assertEquals("watchdog", presented.message)
        assertEquals("watchdog", presented.fault)
    }

    @org.junit.jupiter.api.Test fun `typedSafe turns a string zero into an integer`() {
        assertEquals(0L, typedSafe("INT", "0"))
        assertEquals(false, typedSafe("BOOL", "false"))
        assertEquals(1.5, typedSafe("REAL", "1.5"))
    }
}
