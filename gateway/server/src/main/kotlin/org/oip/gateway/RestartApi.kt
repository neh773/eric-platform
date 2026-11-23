package org.oip.gateway

import io.javalin.Javalin
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import java.io.File

/**
 * Admin > Overview > Restart gateway. Under systemd the gateway exits 75 and the unit starts it again
 * (`RestartForceExitStatus=75`). Otherwise (the Windows launcher, a terminal) a small waiter process starts the
 * same command again once this process has exited, so the new one can bind the same ports.
 */
class RestartApi(private val gw: Gateway, private val exit: (Int) -> Unit = { kotlin.system.exitProcess(it) }) {
    fun register(app: Javalin) {
        app.post("/api/v1/gateway/restart") { ctx ->
            val plan = plan()
            audit()
            ctx.status(202).json(mapOf("restarting" to true, "how" to plan.how))
            Thread({
                Thread.sleep(700)
                runCatching { plan.relaunch?.let { ProcessBuilder(it).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() } }
                runCatching { gw.close() }
                exit(plan.exitCode)
            }, "gateway-restart").apply { isDaemon = false }.start()
        }
    }

    internal data class Plan(val how: String, val exitCode: Int, val relaunch: List<String>?)

    internal fun plan(env: Map<String, String> = System.getenv(), self: ProcessHandle = ProcessHandle.current()): Plan {
        if (!env["INVOCATION_ID"].isNullOrBlank()) return Plan("service", 75, null)
        val info = self.info()
        val command = info.command().orElse(null) ?: return Plan("stop-only", 0, null)
        val args = info.arguments().orElse(emptyArray()).toList()
        val pid = self.pid()
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val relaunch = if (windows) {
            val quoted = args.joinToString(",") { "'" + it.replace("'", "''") + "'" }
            val start = if (args.isEmpty()) "Start-Process -FilePath '${command.replace("'", "''")}'"
            else "Start-Process -FilePath '${command.replace("'", "''")}' -ArgumentList @($quoted)"
            listOf("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "Wait-Process -Id $pid -ErrorAction SilentlyContinue; $start")
        } else {
            val line = (listOf(command) + args).joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            listOf("/bin/sh", "-c", "while kill -0 $pid 2>/dev/null; do sleep 0.2; done; cd '${File(".").absolutePath.replace("'", "'\\''")}' && exec $line </dev/null >/dev/null 2>&1")
        }
        return Plan("relaunch", 0, relaunch)
    }

    private fun audit() {
        if (!gw.security.auditProfile.get().enabled) return
        val context = AuditContext.current()
        gw.security.audit.append(
            AuditDraft(
                timestampMillis = gw.clock.millis(), actor = context?.actor ?: "api", action = "gateway.restart",
                status = AuditStatus.SUCCESS, actorHost = context?.host ?: "", target = "gateway",
                detail = "restart requested from the admin page", context = "gateway-http",
            ),
        )
    }
}
