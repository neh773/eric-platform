package org.oip.gateway

import org.oip.bacnet.BacnetDriverModule
import org.oip.dnp3.Dnp3DriverModule
import org.oip.logix.LogixDriverModule
import org.oip.modbus.ModbusDriverModule
import org.oip.mqtt.MqttDriverModule
import org.oip.opcua.OpcUaClientModule
import org.oip.s7.S7DriverModule
import org.oip.simulator.SimulatorDriverModule
import org.oip.udptcp.UdpTcpDriverModule
import org.oip.sdk.ManifestParser
import org.oip.sdk.ModuleManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Drivers that ship inside the gateway process. Their classes stay on the gateway class loader
 * (no nested jar), so the gateway can call them when a config resource changes.
 *
 * [seed] writes `module.yaml` for each built-in driver at start, so a fresh data dir installs all of them.
 * A directory that already has `module.yaml` is left alone. A built-in the operator uninstalled is listed in
 * [removedFile] (one id per line) and is not seeded again, so it stays uninstalled across restarts (review-1 F5)
 * until [restore] installs it again on purpose.
 */
class BuiltInModules(private val modulesDir: Path, private val removedFile: Path) {
    fun isBuiltIn(id: String): Boolean = id in MANIFESTS

    /** Built-in modules the operator removed, in declaration order. */
    fun removed(): List<ModuleManifest> {
        val ids = readRemoved()
        return MANIFESTS.filterKeys { it in ids }.values.map { ManifestParser.parse(it) }
    }

    /**
     * Write the manifest of every built-in that was not removed. A removed one that [registered] lists as installed
     * (a restored registry, for example) is no longer removed: the registry is the newer statement.
     */
    fun seed(registered: Set<String> = emptySet()) {
        val removed = readRemoved()
        val kept = removed - registered
        if (kept != removed) writeRemoved(kept)
        for ((id, manifest) in MANIFESTS) if (id !in kept) write(id, manifest)
    }

    /** Record that the operator uninstalled [id]; the next start does not seed it. */
    fun markRemoved(id: String) {
        require(isBuiltIn(id)) { "'$id' is not a built-in module" }
        writeRemoved(readRemoved() + id)
    }

    /** Clear the removal of [id] and write its `module.yaml` again, ready for the module host to install. */
    fun restore(id: String) {
        val manifest = MANIFESTS[id] ?: throw NoSuchElementException("'$id' is not a built-in module")
        writeRemoved(readRemoved() - id)
        write(id, manifest)
    }

    private fun write(id: String, manifest: String) {
        val dir = modulesDir.resolve(id)
        val file = dir.resolve("module.yaml")
        if (file.exists()) return
        dir.createDirectories()
        file.writeText(manifest)
    }

    private fun readRemoved(): Set<String> =
        if (!removedFile.exists()) emptySet() else removedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun writeRemoved(ids: Set<String>) {
        removedFile.parent?.createDirectories()
        val tmp = removedFile.resolveSibling(removedFile.name + ".tmp")
        tmp.writeText(MANIFESTS.keys.filter { it in ids }.joinToString("") { "$it\n" })
        Files.move(tmp, removedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** Data-dir file that lists the built-in modules the operator uninstalled. */
        const val REMOVED_FILE = "removed-built-in-modules.txt"

        private val MANIFESTS: Map<String, String> = linkedMapOf(
            ModbusDriverModule.MODULE_ID to ModbusDriverModule.MANIFEST_YAML,
            OpcUaClientModule.MODULE_ID to OpcUaClientModule.MANIFEST_YAML,
            MqttDriverModule.MODULE_ID to MqttDriverModule.MANIFEST_YAML,
            S7DriverModule.MODULE_ID to S7DriverModule.MANIFEST_YAML,
            LogixDriverModule.MODULE_ID to LogixDriverModule.MANIFEST_YAML,
            SimulatorDriverModule.MODULE_ID to SimulatorDriverModule.MANIFEST_YAML,
            UdpTcpDriverModule.MODULE_ID to UdpTcpDriverModule.MANIFEST_YAML,
            BacnetDriverModule.MODULE_ID to BacnetDriverModule.MANIFEST_YAML,
            Dnp3DriverModule.MODULE_ID to Dnp3DriverModule.MANIFEST_YAML,
        )
    }
}
