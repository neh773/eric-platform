package org.oip.gateway

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Plans how many cores and how much memory the scan engine gets, and how many stay with ERIC.
 * Applying the plan is a root helper. This class only reads hardware, checks the request, and writes a stage directory.
 */
object PlcResources {
    const val MARKER = "Managed by ERIC resource split."
    private const val GIB = 1024L * 1024L * 1024L

    data class ThreadInfo(val id: Int, val coreId: Int, val packageId: Int, val siblings: Set<Int>)
    data class Core(val packageId: Int, val coreId: Int, val threads: List<Int>) {
        val primary: Int get() = threads.min()
    }
    data class Machine(val threads: List<ThreadInfo>, val memoryBytes: Long) {
        val cores: List<Core>
            get() = threads.groupBy { it.packageId to it.coreId }
                .map { (key, group) -> Core(key.first, key.second, group.map { it.id }.sorted()) }
                .sortedWith(compareBy({ it.packageId }, { it.primary }))
    }
    data class Request(val plcCores: Int, val plcRamGb: Int, val disableSmt: Boolean)
    data class Plan(
        val plcCpus: List<Int>,
        val isolatedCpus: List<Int>,
        val ericCpus: List<Int>,
        val plcMemoryBytes: Long,
        val ericMemoryBytes: Long,
        val osReserveBytes: Long,
        val disableSmt: Boolean,
        val cmdline: String,
    )

    fun readMachine(cpuRoot: Path, meminfo: Path): Machine {
        val threads = mutableListOf<ThreadInfo>()
        if (cpuRoot.exists()) {
            Files.list(cpuRoot).use { stream ->
                stream.filter { it.fileName.toString().matches(Regex("cpu[0-9]+")) }.forEach { cpu ->
                    val id = cpu.fileName.toString().removePrefix("cpu").toInt()
                    val topology = cpu.resolve("topology")
                    val coreId = readInt(topology.resolve("core_id"), id)
                    val packageId = readInt(topology.resolve("physical_package_id"), 0)
                    val siblings = parseCpuList(readText(topology.resolve("thread_siblings_list")).ifBlank { id.toString() }).toSet()
                    threads += ThreadInfo(id, coreId, packageId, if (siblings.isEmpty()) setOf(id) else siblings)
                }
            }
        }
        return Machine(threads.sortedBy { it.id }, readMemTotal(meminfo))
    }

    fun recommend(machine: Machine): Request {
        val physical = machine.cores.size
        val cores = if (physical >= 8) 2 else 1
        val gib = machine.memoryBytes / GIB
        val ram = if (gib >= 16) 2 else 1
        val smt = machine.cores.any { it.threads.size > 1 }
        return Request(cores, ram, disableSmt = smt)
    }

    /** Empty when the request can be applied. */
    fun validate(machine: Machine, request: Request): List<String> {
        val errors = mutableListOf<String>()
        val physical = machine.cores.size
        if (machine.threads.isEmpty()) errors += "No processors were found."
        if (request.plcCores < 1) errors += "Choose at least one PLC core."
        if (physical > 0 && request.plcCores > physical - 1) {
            errors += "Leave at least one core for ERIC and the operating system."
        }
        if (request.plcRamGb < 1) errors += "Choose at least 1 GB for the PLC."
        val remain = machine.memoryBytes - request.plcRamGb.toLong() * GIB
        if (machine.memoryBytes > 0 && remain < 2 * GIB) {
            errors += "Leave at least 2 GB for ERIC and the operating system."
        }
        return errors
    }

    fun plan(machine: Machine, request: Request): Plan {
        val errors = validate(machine, request)
        if (errors.isNotEmpty()) throw IllegalArgumentException(errors.joinToString(" "))
        val ordered = machine.cores
        val chosen = ordered.takeLast(request.plcCores)
        val plcThreads = chosen.flatMap { it.threads }.sorted()
        val affinity = if (request.disableSmt) chosen.map { it.primary }.sorted() else plcThreads
        val isolated = plcThreads
        val eric = machine.threads.map { it.id }.filter { it !in isolated.toSet() }.sorted()
        val plcBytes = request.plcRamGb.toLong() * GIB
        val remain = machine.memoryBytes - plcBytes
        val osReserve = GIB
        val ericBytes = (remain - osReserve).coerceAtLeast(0)
        val cmdline = "isolcpus=managed_irq,domain,${cpuList(isolated)} nohz_full=${cpuList(isolated)} rcu_nocbs=${cpuList(isolated)} irqaffinity=${cpuList(eric)}"
        return Plan(affinity, isolated, eric, plcBytes, ericBytes, osReserve, request.disableSmt, cmdline)
    }

    fun files(plan: Plan): Map<String, String> {
        val affinity = plan.plcCpus.joinToString(" ")
        val allowedPlc = cpuList(plan.plcCpus)
        val allowedEric = cpuList(plan.ericCpus)
        val plcMax = gib(plan.plcMemoryBytes)
        val ericMax = gib(plan.ericMemoryBytes)
        val json = buildString {
            append("{\n")
            append("  \"generator\": \"eric-plc-resources\",\n")
            append("  \"note\": \"$MARKER\",\n")
            append("  \"plcCpus\": [${plan.plcCpus.joinToString()}],\n")
            append("  \"isolatedCpus\": [${plan.isolatedCpus.joinToString()}],\n")
            append("  \"ericCpus\": [${plan.ericCpus.joinToString()}],\n")
            append("  \"plcMemoryBytes\": ${plan.plcMemoryBytes},\n")
            append("  \"disableSmt\": ${plan.disableSmt}\n")
            append("}\n")
        }
        return linkedMapOf(
            "etc/default/grub.d/eric-plc.cfg" to "# $MARKER\nGRUB_CMDLINE_LINUX_DEFAULT=\"\$GRUB_CMDLINE_LINUX_DEFAULT ${plan.cmdline}\"\n",
            "etc/kernel/eric-plc.cmdline" to "# $MARKER\n${plan.cmdline}\n",
            "etc/eric/plc-resources.json" to json,
            "etc/systemd/system/eric-plc.slice" to slice("eric-plc", allowedPlc, plcMax, low = true),
            "etc/systemd/system/eric.slice" to slice("eric", allowedEric, ericMax, low = false),
            "etc/systemd/system/eric-plc.service" to unit(affinity),
            "etc/systemd/system/eric-plc.service.d/10-resources.conf" to dropIn("eric-plc.slice", affinity),
            "etc/systemd/system/oip-gateway.service.d/10-resources.conf" to dropIn("eric.slice", plan.ericCpus.joinToString(" ")),
        )
    }

    fun bootState(cmdline: String, isolated: List<Int>): String {
        val booted = isolcpus(cmdline)
        if (isolated.isEmpty()) return if (booted.isEmpty()) "not configured" else "reboot required"
        return if (booted.toSet() == isolated.toSet()) "applied" else "reboot required"
    }

    fun isolcpus(cmdline: String): List<Int> {
        val match = Regex("""(?:^|\s)isolcpus=(?:[A-Za-z_,]*?)([0-9][0-9,-]*)""").find(cmdline) ?: return emptyList()
        return parseCpuList(match.groupValues[1])
    }

    fun cpuList(ids: List<Int>): String {
        val sorted = ids.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = sorted[0]
        for (id in sorted.drop(1)) {
            if (id == prev + 1) {
                prev = id
            } else {
                parts += if (start == prev) "$start" else "$start-$prev"
                start = id
                prev = id
            }
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        return parts.joinToString(",")
    }

    fun parseCpuList(text: String): List<Int> {
        val out = linkedSetOf<Int>()
        for (part in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val range = part.split('-')
            if (range.size == 1) out += range[0].toInt()
            else {
                val from = range[0].toInt()
                val to = range[1].toInt()
                for (id in from..to) out += id
            }
        }
        return out.toList()
    }

    fun writeStage(stage: Path, files: Map<String, String>) {
        val root = stage.resolve("files")
        for ((rel, content) in files) {
            require(!rel.contains("..") && !rel.startsWith("/")) { "refusing path $rel" }
            val dest = root.resolve(rel)
            dest.parent.createDirectories()
            dest.writeText(content)
        }
        stage.resolve("manifest").writeText(files.keys.joinToString("\n") + "\n")
    }

    private fun slice(name: String, cpus: String, memory: String, low: Boolean): String = buildString {
        append("# $MARKER\n[Slice]\nAllowedCPUs=$cpus\nMemoryMax=$memory\n")
        if (low) append("MemoryLow=$memory\n")
        if (name.isEmpty()) append("")
    }

    private fun unit(affinity: String): String = """
        # $MARKER
        [Unit]
        Description=ERIC scan engine
        After=network-online.target

        [Service]
        ExecStart=/usr/bin/eric-plc run --config /etc/eric/plc.toml
        Slice=eric-plc.slice
        CPUAffinity=$affinity
        LimitMEMLOCK=infinity
        LimitRTPRIO=99
        Restart=on-failure

        [Install]
        WantedBy=multi-user.target
    """.trimIndent() + "\n"

    private fun dropIn(sliceName: String, affinity: String): String = """
        # $MARKER
        [Service]
        Slice=$sliceName
        CPUAffinity=$affinity
        LimitMEMLOCK=infinity
        LimitRTPRIO=99
    """.trimIndent() + "\n"

    private fun gib(bytes: Long): String = "${bytes / GIB}G"

    private fun readInt(path: Path, fallback: Int): Int =
        readText(path).trim().toIntOrNull() ?: fallback

    private fun readText(path: Path): String = if (path.exists()) path.readText() else ""

    private fun readMemTotal(meminfo: Path): Long {
        if (!meminfo.exists()) return 0
        val line = meminfo.readText().lineSequence().firstOrNull { it.startsWith("MemTotal:") } ?: return 0
        val kb = Regex("""(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return 0
        return kb * 1024L
    }
}
