package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PlcResourcesTest {
    @TempDir lateinit var tmp: Path

    @Test fun `topology and memory come from the fixture files`() {
        val machine = PlcResources.readMachine(fixture(), tmp.resolve("meminfo"))
        assertEquals(8, machine.threads.size)
        assertEquals(4, machine.cores.size)
        assertEquals(setOf(0, 4), machine.threads.first { it.id == 0 }.siblings)
        assertEquals(16L * 1024 * 1024 * 1024, machine.memoryBytes)
        val defaults = PlcResources.recommend(machine)
        assertEquals(1, defaults.plcCores)
        assertEquals(2, defaults.plcRamGb)
        assertTrue(defaults.disableSmt)
    }

    @Test fun `eight physical cores default to two PLC cores`() {
        val root = tmp.resolve("wide")
        writeCpu(root, cores = 8, smt = false)
        tmp.resolve("mem-wide").writeText("MemTotal: ${32L * 1024 * 1024} kB\n")
        val machine = PlcResources.readMachine(root, tmp.resolve("mem-wide"))
        assertEquals(2, PlcResources.recommend(machine).plcCores)
    }

    @Test fun `the plan isolates the PLC cores and leaves one for ERIC`() {
        val machine = PlcResources.readMachine(fixture(), tmp.resolve("meminfo"))
        val plan = PlcResources.plan(machine, PlcResources.Request(1, 2, disableSmt = true))
        assertEquals(listOf(3), plan.plcCpus)
        assertEquals(listOf(3, 7), plan.isolatedCpus)
        assertTrue(0 in plan.ericCpus)
        assertFalse(7 in plan.ericCpus)
        assertTrue(plan.cmdline.startsWith("isolcpus=managed_irq,domain,3,7 "))
        assertTrue(plan.cmdline.contains("nohz_full=3,7"))
        assertTrue(plan.cmdline.contains("rcu_nocbs=3,7"))
        assertTrue(plan.cmdline.contains("irqaffinity="))
        assertTrue(PlcResources.validate(machine, PlcResources.Request(4, 2, true)).any { it.contains("at least one core") })
        val one = tmp.resolve("one-core")
        writeCpu(one, cores = 1, smt = true)
        val tiny = PlcResources.readMachine(one, tmp.resolve("meminfo"))
        assertEquals(1, tiny.cores.size)
        assertTrue(PlcResources.validate(tiny, PlcResources.Request(1, 1, true)).any { it.contains("at least one core") })
        assertTrue(PlcResources.validate(machine, PlcResources.Request(1, 15, true)).any { it.contains("2 GB") })
    }

    @Test fun `cpu lists collapse ranges and the boot line is recognized`() {
        assertEquals("0-2,4,6-7", PlcResources.cpuList(listOf(7, 0, 1, 2, 4, 6)))
        assertEquals(listOf(2, 3, 6), PlcResources.isolcpus("quiet isolcpus=managed_irq,domain,2-3,6 rcu_nocbs=2-3,6"))
        assertEquals("applied", PlcResources.bootState("isolcpus=managed_irq,domain,3,7", listOf(7, 3)))
        assertEquals("reboot required", PlcResources.bootState("quiet", listOf(3, 7)))
    }

    @Test fun `apply is idempotent and revert restores the previous file`() {
        val machine = PlcResources.readMachine(fixture(), tmp.resolve("meminfo"))
        val files = PlcResources.files(PlcResources.plan(machine, PlcResources.Request(1, 2, true)))
        val stage = tmp.resolve("stage")
        PlcResources.writeStage(stage, files)
        val root = tmp.resolve("root")
        val grub = root.resolve("etc/default/grub.d/eric-plc.cfg")
        grub.parent.createDirectories()
        grub.writeText("OLD\n")
        val script = helper()
        run(script, "apply", "--stage", stage.toString(), "--root", root.toString())
        assertTrue(grub.readText().contains("isolcpus=managed_irq,domain,3,7"))
        val backup = root.resolve("var/lib/eric/plc-resources/backup/etc/default/grub.d/eric-plc.cfg")
        assertEquals("OLD\n", backup.readText())
        run(script, "apply", "--stage", stage.toString(), "--root", root.toString())
        assertEquals("OLD\n", backup.readText())
        assertTrue(root.resolve("etc/eric/plc-resources.json").readText().contains("\"plcCpus\": [3]"))
        assertTrue(root.resolve("etc/systemd/system/eric-plc.service").readText().contains("LimitMEMLOCK=infinity"))
        assertTrue(root.resolve("etc/systemd/system/eric-plc.slice").readText().contains("AllowedCPUs=3"))
        run(script, "revert", "--root", root.toString())
        assertEquals("OLD\n", grub.readText())
        assertFalse(root.resolve("etc/eric/plc-resources.json").exists())
    }

    private fun fixture(): Path {
        val root = tmp.resolve("cpu")
        writeCpu(root, cores = 4, smt = true)
        tmp.resolve("meminfo").writeText("MemTotal: ${16L * 1024 * 1024} kB\n")
        return root
    }

    private fun writeCpu(root: Path, cores: Int, smt: Boolean) {
        val siblings = if (smt) 2 else 1
        for (core in 0 until cores) {
            for (thread in 0 until siblings) {
                val id = core + thread * cores
                val dir = root.resolve("cpu$id/topology")
                dir.createDirectories()
                dir.resolve("core_id").writeText("$core\n")
                dir.resolve("physical_package_id").writeText("0\n")
                val others = (0 until siblings).joinToString(",") { (core + it * cores).toString() }
                dir.resolve("thread_siblings_list").writeText(others)
            }
        }
    }

    private fun helper(): Path {
        var dir = Path.of("").toAbsolutePath()
        repeat(6) {
            val candidate = dir.resolve("scripts/eric-plc-resources.sh")
            if (candidate.exists()) return candidate
            dir = dir.parent ?: return candidate
        }
        error("eric-plc-resources.sh was not found")
    }

    private fun run(script: Path, vararg args: String) {
        val bash = Path.of(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git", "bin", "bash.exe")
        val shell = if (bash.exists()) bash.toString() else "bash"
        val process = ProcessBuilder(listOf(shell, script.toString().replace('\\', '/')) + args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        assertEquals(0, code, text)
    }
}
