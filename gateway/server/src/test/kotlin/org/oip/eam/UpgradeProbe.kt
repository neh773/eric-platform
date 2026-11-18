package org.oip.eam

import org.oip.gateway.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stands in for a gateway process in [GatewayUpgradeRestartTest]: it does what the real runtime does at boot
 * (GatewayUpgrade.onBoot, markHealthy), then writes what an agent would report and exits.
 * Args: install root, data dir.
 */
object UpgradeProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val data = Path.of(args[1])
        val upgrade = GatewayUpgrade(root, data)
        val boot = upgrade.onBoot()
        if (boot is GatewayUpgrade.Boot.Pending) upgrade.markHealthy()
        val report = linkedMapOf("version" to upgrade.currentVersion(), "upgradeResult" to upgrade.result(), "boot" to boot.toString())
        Files.writeString(data.resolve("report.json"), Json.write(report))
        Files.writeString(data.resolve("boots.log"), (upgrade.currentVersion() ?: "?") + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        if (boot is GatewayUpgrade.Boot.RolledBack) kotlin.system.exitProcess(GatewayUpgrade.EXIT_RESTART_FOR_UPGRADE)
    }
}
