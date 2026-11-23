package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TAG_KIND
import org.oip.tags.UDT_KIND
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * 0.1.1 end to end: FB_CentralInhibit (a STRUCT passed VAR_IN_OUT) called twice for eight stations. Each station
 * structure is bound to a gateway UDT instance, compiled with the pinned compiler, and scanned by the managed
 * `eric-plc` engine. Skipped when the compiler or the engine has not been built on this machine.
 */
class StUdtEngineTest {
    @TempDir lateinit var tmp: Path

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val compiler = File(root, "plc/eric-plc/.cache/rusty-1.0.8/plc")
    private val engine = File(root, "plc/eric-plc/target/release/eric-plc")
    private val stations = listOf("CLS-1", "OS-1A", "OS-1B", "OS-1C", "CLS-2", "OS-2A", "OS-2B", "OS-2C")
    private val variables = listOf("Cls1", "Os1A", "Os1B", "Os1C", "Cls2", "Os2A", "Os2B", "Os2C")
    private val bools = listOf("CommOk", "PumpFault", "InhibitCmd", "OverrideActive", "AlmHH", "AlmCommLoss", "AlmPumpFault",
        "AlmCannotRelieve", "AlmCannotEvacuate", "AlmPumpIntoOverflow")

    @Test fun twoFunctionBlockCallsDriveEightUdtInstancesAndSurviveARestart() {
        assumeTrue(compiler.isFile && engine.isFile, "RuSTy (scripts/fetch-rusty.sh) and eric-plc (cargo build --release) are needed")
        val dir = tmp.resolve("gw").createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  port: 0\n")
        val env = System.getenv() + mapOf("ERIC_PLC_ENGINE" to engine.absolutePath, "ERIC_PLC_COMPILER" to compiler.absolutePath)
        val source = javaClass.getResource("/plc/FB_CentralInhibit.st")!!.readText()
        Gateway(dir, port = 0, env = env).use { gw ->
            gw.start()
            gw.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("lift-station", TAGS_MODULE), mapOf(
                "path" to "Types/LiftStation",
                "members" to listOf(mapOf("name" to "Level", "type" to "memory", "dataType" to "Double", "value" to 10.0)) +
                    bools.map { mapOf("name" to it, "type" to "memory", "dataType" to "Boolean", "value" to (it == "CommOk")) },
            )), "test")
            for (s in stations) {
                gw.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("st-" + s.lowercase(), TAGS_MODULE), mapOf(
                    "provider" to "default", "path" to "MiamiDade/$s", "type" to "udt", "typeId" to "Types/LiftStation",
                )), "test")
            }
            await("instances") { gw.tags.read("[default]MiamiDade/OS-2C/CommOk").value == true }
            gw.logic.create("CollectionInterlock")
            gw.logic.save("CollectionInterlock", source)
            gw.logic.saveMapping(mapOf("inputs" to emptyList<Any>(), "outputs" to emptyList<Any>(),
                "udts" to variables.zip(stations).map { (v, s) -> mapOf("variable" to v, "instance" to "[default]MiamiDade/$s") }))
            val view = gw.logic.udtView("CollectionInterlock")
            @Suppress("UNCHECKED_CAST")
            val vars = view["variables"] as List<Map<String, Any?>>
            assertEquals(variables, vars.map { it["variable"] })
            val result = gw.logic.deploy("CollectionInterlock")
            assertEquals(true, result["ok"], result.toString())
            assertEquals("running", gw.logic.status()["state"])

            fun write(station: String, member: String, value: Any) = assertTrue(gw.tags.write("[default]MiamiDade/$station/$member", value).ok)
            fun read(station: String, member: String) = gw.tags.read("[default]MiamiDade/$station/$member").value

            // Central 1 at high-high for longer than the 2 s confirm: its outstations get the stop command.
            write("CLS-1", "Level", 96.0)
            await("OS-1A inhibited") { read("OS-1A", "InhibitCmd") == true }
            assertEquals(true, read("CLS-1", "AlmHH"))
            assertEquals(true, read("OS-1C", "InhibitCmd"))
            assertEquals(false, read("OS-2A", "InhibitCmd"), "the second call is independent")
            // An outstation at its own high-high runs anyway: pumping into overflow.
            write("OS-1A", "Level", 96.0)
            await("override") { read("OS-1A", "OverrideActive") == true && read("OS-1A", "InhibitCmd") == false }
            await("overflow alarm") { read("OS-1A", "AlmPumpIntoOverflow") == true && read("CLS-1", "AlmPumpIntoOverflow") == true }
            // Online mode shows the member values.
            @Suppress("UNCHECKED_CAST")
            val watched = gw.logic.watch()["variables"] as List<Map<String, Any?>>
            assertEquals(true, watched.first { it["name"] == "Os1A.OverrideActive" }["value"])
            assertEquals("Os1A", watched.first { it["name"] == "Os1A.OverrideActive" }["udt"])
            assertEquals(96.0, watched.first { it["name"] == "Cls1.Level" }["value"])
        }
        // The deployed program starts again with the gateway.
        Gateway(dir, port = 0, env = env).use { gw ->
            gw.start()
            await("engine back") { gw.logic.status()["state"] == "running" }
            gw.tags.write("[default]MiamiDade/CLS-2/Level", 97.0)
            await("OS-2B inhibited after restart") { gw.tags.read("[default]MiamiDade/OS-2B/InhibitCmd").value == true }
        }
    }

    private fun await(what: String, check: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < end) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(100)
        }
        org.junit.jupiter.api.Assertions.fail<Unit>("timed out waiting for $what")
    }
}
