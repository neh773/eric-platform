package org.oip.gateway

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The admin page and the first-run step talk to this service.
 * A saved plan is staged under the data directory. Copying it onto the machine
 * is [helper], which must be allowed to run as root. The gateway process stays unprivileged.
 */
class PlcResourceService(
    private val dir: Path,
    private val helper: String? = System.getenv("ERIC_PLC_RESOURCE_HELPER")?.takeIf { it.isNotEmpty() },
    private val cpuRoot: Path = Path.of("/sys/devices/system/cpu"),
    private val meminfo: Path = Path.of("/proc/meminfo"),
    private val cmdlinePath: Path = Path.of("/proc/cmdline"),
    private val osWindows: Boolean = System.getProperty("os.name").lowercase().contains("win"),
    private val processors: Int = Runtime.getRuntime().availableProcessors(),
    private val memoryBytes: Long = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0L,
    private val windowsResources: Path = Path.of(System.getenv("PROGRAMDATA") ?: "C:\\ProgramData", "ERIC", "plc-resources.json"),
    private val serviceName: String = "ERIC Gateway",
) {
    private val stage = dir.resolve("stage")
    private val pending = dir.resolve("pending.json")

    fun configured(): Boolean = pending.exists() || Path.of("/etc/eric/plc-resources.json").exists()

    fun status(): Map<String, Any?> {
        if (osWindows) return windowsStatus()
        val machine = PlcResources.readMachine(cpuRoot, meminfo)
        val defaults = PlcResources.recommend(machine)
        val saved = readPending()
        val isolated = (saved?.get("isolatedCpus") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        return mapOf(
            "threads" to machine.threads.map {
                mapOf("id" to it.id, "coreId" to it.coreId, "packageId" to it.packageId, "siblings" to it.siblings.sorted())
            },
            "physicalCores" to machine.cores.size,
            "memoryBytes" to machine.memoryBytes,
            "defaults" to mapOf("plcCores" to defaults.plcCores, "plcRamGb" to defaults.plcRamGb, "disableSmt" to defaults.disableSmt),
            "cmdline" to readCmdline(),
            "state" to PlcResources.bootState(readCmdline(), isolated),
            "pending" to saved,
            "helperConfigured" to (helper != null),
            "platform" to "linux",
        )
    }

    fun preview(plcCores: Int, plcRamGb: Int, disableSmt: Boolean): Map<String, Any?> {
        if (osWindows) return windowsPreview(plcCores, plcRamGb, disableSmt)
        val request = PlcResources.Request(plcCores, plcRamGb, disableSmt)
        val machine = PlcResources.readMachine(cpuRoot, meminfo)
        val errors = PlcResources.validate(machine, request)
        if (errors.isNotEmpty()) return mapOf("ok" to false, "errors" to errors, "rebootRequired" to false)
        val plan = PlcResources.plan(machine, request)
        val files = PlcResources.files(plan)
        dir.createDirectories()
        val body = mapOf(
            "plcCores" to plcCores,
            "plcRamGb" to plcRamGb,
            "disableSmt" to disableSmt,
            "plcCpus" to plan.plcCpus,
            "isolatedCpus" to plan.isolatedCpus,
            "ericCpus" to plan.ericCpus,
            "cmdline" to plan.cmdline,
            "plcMemoryBytes" to plan.plcMemoryBytes,
            "ericMemoryBytes" to plan.ericMemoryBytes,
        )
        pending.writeText(Json.write(body))
        PlcResources.writeStage(stage, files)
        return body + mapOf(
            "ok" to true,
            "errors" to emptyList<String>(),
            "files" to files,
            "state" to PlcResources.bootState(readCmdline(), plan.isolatedCpus),
            "rebootRequired" to true,
        )
    }

    fun apply(plcCores: Int, plcRamGb: Int, disableSmt: Boolean): Map<String, Any?> {
        if (osWindows) return windowsApply(plcCores, plcRamGb, disableSmt)
        val shown = preview(plcCores, plcRamGb, disableSmt)
        if (shown["ok"] != true) return shown
        val command = "sudo ${helper ?: "eric-plc-resources"} apply --stage ${stage.toAbsolutePath()} --root /"
        val ran = runHelper("apply")
        return shown + mapOf("applied" to ran.ok, "command" to command, "helperOutput" to ran.output, "rebootRequired" to true)
    }

    fun revert(): Map<String, Any?> {
        if (osWindows) return windowsRevert()
        val command = "sudo ${helper ?: "eric-plc-resources"} revert --root /"
        val ran = runHelper("revert")
        if (ran.ok) java.nio.file.Files.deleteIfExists(pending)
        return mapOf("applied" to ran.ok, "command" to command, "helperOutput" to ran.output, "rebootRequired" to true, "state" to "reboot required")
    }

    private fun runHelper(action: String): HelperResult {
        val program = helper ?: return HelperResult(false, "The root helper is not configured. Run the command as root.")
        val args = when (action) {
            "apply" -> listOf(program, "apply", "--stage", stage.toAbsolutePath().toString(), "--root", "/")
            else -> listOf(program, "revert", "--root", "/")
        }
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        return HelperResult(code == 0, text.ifBlank { if (code == 0) "applied" else "the helper exited $code" })
    }

    @Suppress("UNCHECKED_CAST")
    private fun readPending(): Map<String, Any?>? {
        if (!pending.exists()) return null
        return Json.mapper.readValue(pending.readText(), Map::class.java) as? Map<String, Any?>
    }

    private fun readCmdline(): String = if (cmdlinePath.exists()) cmdlinePath.readText().trim() else ""

    private data class HelperResult(val ok: Boolean, val output: String)

    private fun windowsStatus(): Map<String, Any?> {
        val saved = readPending()
        val threads = (0 until processors).map { id -> mapOf("id" to id, "coreId" to id, "packageId" to 0, "siblings" to listOf(id)) }
        return mapOf(
            "threads" to threads,
            "physicalCores" to processors,
            "memoryBytes" to memoryBytes,
            "defaults" to mapOf("plcCores" to if (processors > 1) 1 else 0, "plcRamGb" to 1, "disableSmt" to false),
            "cmdline" to "Windows cannot fully isolate a core. Affinity and a locked working set are the split.",
            "state" to if (saved == null) "not applied" else "applied in this process and on the next scan start",
            "pending" to saved,
            "helperConfigured" to true,
            "platform" to "windows",
            "windowsNote" to "Windows cannot fully isolate a core. The choice is affinity, a locked working set, and the gateway -Xmx.",
        )
    }

    private fun windowsPreview(plcCores: Int, plcRamGb: Int, disableSmt: Boolean): Map<String, Any?> {
        val errors = mutableListOf<String>()
        if (processors < 2) errors += "This computer has one processor. Windows cannot give the only core to the PLC."
        if (plcCores < 1 || plcCores >= processors) errors += "Leave at least one core for ERIC and Windows."
        val ramBytes = plcRamGb.toLong() * 1024L * 1024L * 1024L
        if (plcRamGb < 1 || (memoryBytes > 0 && ramBytes >= memoryBytes)) errors += "Leave memory for ERIC and Windows."
        if (errors.isNotEmpty()) return mapOf("ok" to false, "errors" to errors, "rebootRequired" to false, "platform" to "windows")
        val plcCpus = (processors - plcCores until processors).toList()
        val ericCpus = (0 until processors).filter { it !in plcCpus }
        val note = "Windows cannot fully isolate a core. Affinity, a locked working set, and the gateway -Xmx are applied. They are not a real-time partition."
        val body = mapOf(
            "generator" to "eric-plc-resources",
            "plcCores" to plcCores,
            "plcRamGb" to plcRamGb,
            "disableSmt" to disableSmt,
            "plcCpus" to plcCpus,
            "ericCpus" to ericCpus,
            "plcMemoryBytes" to ramBytes,
            "note" to note,
            "platform" to "windows",
        )
        dir.createDirectories()
        pending.writeText(Json.write(body))
        return body + mapOf(
            "ok" to true,
            "errors" to emptyList<String>(),
            "rebootRequired" to false,
            "cmdline" to note,
            "files" to mapOf(windowsResources.toString() to Json.write(body)),
            "smtApplied" to false,
        )
    }

    private fun windowsApply(plcCores: Int, plcRamGb: Int, disableSmt: Boolean): Map<String, Any?> {
        val shown = windowsPreview(plcCores, plcRamGb, disableSmt)
        if (shown["ok"] != true) return shown
        val text = pending.readText()
        return try {
            windowsResources.parent?.createDirectories()
            windowsResources.writeText(text)
            val jvm = windowsResources.parent.resolve("gateway-jvm.txt")
            jvm.writeText("-Xmx${plcRamGb}g\n")
            val plcCpus = (shown["plcCpus"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
            val eric = (0 until processors).filter { it !in plcCpus }
            val affinity = runPowerShell("(Get-Process -Id ${ProcessHandle.current().pid()}).ProcessorAffinity = [IntPtr]${mask(eric)}")
            val xmx = setServiceXmx(plcRamGb)
            shown + mapOf(
                "applied" to true,
                "command" to "affinity and $windowsResources",
                "helperOutput" to listOf(affinity, xmx).filter { it.isNotBlank() }.joinToString("\n"),
                "rebootRequired" to false,
            )
        } catch (ex: Exception) {
            shown + mapOf("applied" to false, "helperOutput" to (ex.message ?: "could not write the resource file"), "rebootRequired" to false)
        }
    }

    private fun windowsRevert(): Map<String, Any?> {
        val removed = runCatching {
            java.nio.file.Files.deleteIfExists(windowsResources)
            java.nio.file.Files.deleteIfExists(windowsResources.parent.resolve("gateway-jvm.txt"))
            java.nio.file.Files.deleteIfExists(pending)
        }
        val affinity = runPowerShell("(Get-Process -Id ${ProcessHandle.current().pid()}).ProcessorAffinity = [IntPtr]${mask((0 until processors).toList())}")
        val xmx = clearServiceXmx()
        return mapOf(
            "applied" to removed.isSuccess,
            "command" to "delete $windowsResources",
            "helperOutput" to listOf(affinity, xmx, removed.exceptionOrNull()?.message).filter { !it.isNullOrBlank() }.joinToString("\n"),
            "rebootRequired" to false,
            "state" to "reverted",
            "platform" to "windows",
        )
    }

    private fun mask(cpus: List<Int>): Long = cpus.filter { it in 0..62 }.fold(0L) { bits, cpu -> bits or (1L shl cpu) }.let { if (it == 0L) 1L else it }

    private fun setServiceXmx(gb: Int): String = runPowerShell(
        """
        ${'$'}key = 'HKLM:\SYSTEM\CurrentControlSet\Services\$serviceName\Parameters'
        if (-not (Test-Path ${'$'}key)) { exit 0 }
        ${'$'}cur = @(Get-ItemProperty ${'$'}key).AppEnvironmentExtra
        ${'$'}lines = @(${'$'}cur | Where-Object { ${'$'}_ -and ${'$'}_ -notlike 'JAVA_TOOL_OPTIONS=*' })
        ${'$'}lines += 'JAVA_TOOL_OPTIONS=-Xmx${gb}g'
        New-ItemProperty -Path ${'$'}key -Name AppEnvironmentExtra -PropertyType MultiString -Value ${'$'}lines -Force | Out-Null
        """.trimIndent(),
    )

    private fun clearServiceXmx(): String = runPowerShell(
        """
        ${'$'}key = 'HKLM:\SYSTEM\CurrentControlSet\Services\$serviceName\Parameters'
        if (-not (Test-Path ${'$'}key)) { exit 0 }
        ${'$'}cur = @(Get-ItemProperty ${'$'}key).AppEnvironmentExtra
        ${'$'}lines = @(${'$'}cur | Where-Object { ${'$'}_ -and ${'$'}_ -notlike 'JAVA_TOOL_OPTIONS=*' })
        if (${'$'}lines.Count -eq 0) { exit 0 }
        New-ItemProperty -Path ${'$'}key -Name AppEnvironmentExtra -PropertyType MultiString -Value ${'$'}lines -Force | Out-Null
        """.trimIndent(),
    )

    private fun runPowerShell(script: String): String {
        if (!osWindows) return ""
        return try {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            text
        } catch (ex: Exception) {
            ex.message ?: "powershell failed"
        }
    }
}
