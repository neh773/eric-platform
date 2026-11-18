package org.oip.simulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.host.ModuleHost
import org.oip.host.ModuleState
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class SimulatorModuleTest {
    @TempDir lateinit var tmp: Path

    @Test fun moduleInstallStartStopUninstallLeavesNoResources() {
        val id = SimulatorDriverModule.MODULE_ID
        val manifest = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n")
        assertEquals(manifest.trim(), SimulatorDriverModule.MANIFEST_YAML.trim())
        val modules = tmp.resolve("modules")
        modules.resolve("driver-simulator").createDirectories().resolve("module.yaml").writeText(manifest)
        val config = tmp.resolve("config")
        val store = FileConfigStore(config, SchemaRegistry())
        val host = ModuleHost(modules, config, store = store)
        host.installAll()
        assertEquals(ModuleState.INSTALLED, host.state(id))
        host.start(id)
        val module = host.instance(id) as SimulatorDriverModule
        assertEquals(ModuleState.RUNNING, host.state(id))

        store.put(
            Scope.GATEWAY,
            Resource(DEVICE_KIND, Metadata("gensim", id), mapOf(
                "repeatProgram" to false,
                "baseRateMs" to 1000,
                "instructions" to listOf(mapOf(
                    "timeInterval" to 0,
                    "browsePath" to "Tank/Level",
                    "valueSource" to "42",
                    "dataType" to "Int32",
                )),
            )),
            "test",
        )
        val device = module.device("gensim")
        assertEquals(42, device!!.read("Tank/Level").value)
        assertEquals(0L, device.write("Tank/Level", 7).quality.statusCode)
        assertEquals(7, device.read("Tank/Level").value)

        store.delete(org.oip.config.ResourceId(id, Scope.GATEWAY, DEVICE_KIND, "gensim"))
        assertNull(module.device("gensim"))

        store.put(
            Scope.GATEWAY,
            Resource(DEVICE_KIND, Metadata("bad", id), mapOf(
                "instructions" to listOf(mapOf(
                    "timeInterval" to 0,
                    "browsePath" to "A",
                    "valueSource" to "nope()",
                    "dataType" to "Double",
                )),
            )),
            "test",
        )
        assertNull(module.device("bad"))

        store.put(
            Scope.GATEWAY,
            Resource(DEVICE_KIND, Metadata("dairy", id), mapOf("program" to "dairy")),
            "test",
        )
        val dairy = module.device("dairy")
        assertEquals(0, dairy!!.read("_Meta:Writeable/Agitator1Mode").value)
        assertEquals(false, dairy.read("[Controls]/Pause").value)
        assertTrue(dairy.browse("").any { it.browseName == "Overview" && it.nodeId == "_Meta:Overview" })

        host.stop(id)
        host.uninstall(id)
        assertTrue(host.installed().isEmpty())
        assertEquals(0, store.list(module = id).size)
        assertFalse(config.resolve(id).exists())
    }
}
