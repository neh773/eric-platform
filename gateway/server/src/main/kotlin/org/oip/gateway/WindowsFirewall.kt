package org.oip.gateway

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Opens the gateway TCP port in the Windows firewall after the process is up.
 * Writing that rule from the installer stalled the silent install, so the service adds it.
 * A failure must not stop the gateway.
 */
object WindowsFirewall {
    fun allowGatewayPortLater(port: Int, name: String = "ERIC Gateway") {
        val os = System.getProperty("os.name").orEmpty()
        if (!os.contains("win", ignoreCase = true)) return
        thread(name = "windows-firewall", isDaemon = true) {
            allow(port, name)
        }
    }

    private fun allow(port: Int, name: String) {
        runCatching {
            // A restart otherwise adds another rule with the same name.
            netsh("advfirewall", "firewall", "delete", "rule", "name=$name")
            netsh(
                "advfirewall", "firewall", "add", "rule",
                "name=$name", "dir=in", "action=allow", "protocol=TCP", "localport=$port",
            )
        }
    }

    private fun netsh(vararg args: String) {
        val process = ProcessBuilder(listOf("netsh") + args).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}
