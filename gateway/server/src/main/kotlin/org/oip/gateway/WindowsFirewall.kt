package org.oip.gateway

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Opens the gateway TCP port in the Windows firewall after the process is up.
 * Writing that rule from the installer stalled the silent install, so the service adds it.
 * A failure must not stop the gateway.
 *
 * The same process deletes the rule on shutdown. Add/Remove Programs does not run
 * `packaging/windows/uninstall.ps1`, and a rule left behind still allows the port after the
 * service is gone. The service stop wait (`AppStopMethodConsole`) is long enough for this
 * delete to finish before the process is killed.
 */
object WindowsFirewall {
    internal var isWindows: () -> Boolean = {
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
    }
    internal var runCommand: (List<String>) -> Unit = { args -> netsh(args) }

    private val lock = Any()
    private var generation = 0
    private var closed = false
    private var openedName: String? = null

    fun allowGatewayPortLater(port: Int, name: String = "ERIC Gateway") {
        if (!isWindows()) return
        val ticket = synchronized(lock) {
            closed = false
            ++generation
        }
        thread(name = "windows-firewall", isDaemon = true) {
            allow(port, name, ticket)
        }
    }

    /** Delete the rule this process added. A second call, or a call that lost the race with [allow], does nothing. */
    fun removeOpenedRule() {
        val name = synchronized(lock) {
            closed = true
            val current = openedName
            openedName = null
            current
        } ?: return
        if (!isWindows()) return
        runCatching { runCommand(deleteArgs(name)) }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            generation = 0
            closed = false
            openedName = null
        }
    }

    internal fun openForTest(port: Int, name: String) {
        val ticket = synchronized(lock) {
            closed = false
            ++generation
        }
        allow(port, name, ticket)
    }

    internal fun allow(port: Int, name: String, ticket: Int) {
        synchronized(lock) {
            if (!isWindows() || closed || generation != ticket) return
            runCatching {
                runCommand(deleteArgs(name))
                runCommand(addArgs(port, name))
                openedName = name
            }
        }
    }

    internal fun deleteArgs(name: String): List<String> =
        listOf("advfirewall", "firewall", "delete", "rule", "name=$name")

    internal fun addArgs(port: Int, name: String): List<String> = listOf(
        "advfirewall", "firewall", "add", "rule",
        "name=$name", "dir=in", "action=allow", "protocol=TCP", "localport=$port",
        // Windows Home usually puts a network in the Public profile; the rule must cover it, not only Private/Domain.
        "profile=any",
    )

    private fun netsh(args: List<String>) {
        val process = ProcessBuilder(listOf("netsh") + args).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}
