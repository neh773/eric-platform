package org.oip.eam

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.gateway.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

/**
 * EAM-011 with real processes: the launcher script runs under a tiny supervisor loop that restarts it on exit code 75
 * (what the systemd unit, a service wrapper or a container restart policy does). The probe process stands in for the
 * gateway and runs the real boot-time upgrade code; it is not the full gateway (that would need the whole install).
 */
class GatewayUpgradeRestartTest {
    @TempDir lateinit var tmp: Path

    private val launcher: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("packaging/launcher/oip-gateway.sh") }.first { Files.exists(it) }

    private fun script(body: String) = "#!/bin/sh\n$body\n"

    private fun probeScript(): String {
        val javaBin = Path.of(System.getProperty("java.home"), "bin")
        val java = listOf(javaBin.resolve("java.exe"), javaBin.resolve("java")).first { Files.exists(it) }
        return script("exec '${java.toString().replace('\\', '/')}' -cp '${System.getProperty("java.class.path")}' org.oip.eam.UpgradeProbe \"\$OIP_INSTALL_DIR\" \"\$OIP_DATA_DIR\"")
    }

    private fun pack(binScript: String): ByteArray = ByteArrayOutputStream().also { bo ->
        ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("bin/oip-gateway")); z.write(binScript.toByteArray()); z.closeEntry() }
    }.toByteArray()

    private fun exe(path: Path, text: String) { path.parent.createDirectories(); Files.writeString(path, text); path.toFile().setExecutable(true) }

    private fun installOld(): Pair<Path, Path> {
        val root = tmp.resolve("install"); val data = tmp.resolve("data").createDirectories()
        exe(root.resolve("versions/1.0.0/bin/oip-gateway"), probeScript())
        Files.writeString(root.resolve("current"), "1.0.0\n")
        return root to data
    }

    private fun stageAndApply(root: Path, data: Path, version: String, bin: String) {
        val bytes = pack(bin)
        val dir = data.resolve("upgrade").createDirectories()
        Files.write(dir.resolve("staged.bin"), bytes)
        Files.writeString(dir.resolve("staged.json"), Json.write(mapOf("version" to version, "sha256" to EamController.sha256(bytes))))
        GatewayUpgrade(root, data).apply(60_000)
    }

    /** The supervisor: start the launcher; restart on 75; stop on any other code or after [limit] starts. Returns the exit codes. */
    private fun supervise(root: Path, data: Path, limit: Int = 4): List<Int> {
        val codes = mutableListOf<Int>()
        while (codes.size < limit) {
            val bash = Path.of(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git", "bin", "bash.exe")
            val shell = if (Files.exists(bash)) bash.toString() else "sh"
            val p = ProcessBuilder(shell, launcher.toString().replace('\\', '/'))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment()["OIP_INSTALL_DIR"] = root.toString(); it.environment()["OIP_DATA_DIR"] = data.toString() }
                .start()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "launcher did not finish")
            codes += p.exitValue()
            if (p.exitValue() != GatewayUpgrade.EXIT_RESTART_FOR_UPGRADE) break
        }
        return codes
    }

    @Test fun appliedUpgradeRestartsAndComesBackOnTheNewVersion() {
        val (root, data) = installOld()
        stageAndApply(root, data, "1.1.0", probeScript())
        assertEquals(listOf(0), supervise(root, data))
        val report = Json.parse(data.resolve("report.json").readText())
        assertEquals("1.1.0", report["version"])
        val result = report["upgradeResult"] as Map<*, *>
        assertEquals("applied", result["state"]); assertEquals("1.1.0", result["version"])
        assertTrue(!Files.exists(data.resolve("upgrade/pending.json")))
        assertEquals("1.1.0\n", data.resolve("boots.log").readText())
    }

    @Test fun brokenNewVersionIsRolledBackAndTheOldOneComesBackWithTheReason() {
        val (root, data) = installOld()
        stageAndApply(root, data, "1.1.0", script("exit 3"))
        // First start: the new version dies (launcher rolls back, exits 75). Second start: the old version runs.
        assertEquals(listOf(75, 0), supervise(root, data))
        assertEquals("1.0.0", root.resolve("current").readText().trim())
        val report = Json.parse(data.resolve("report.json").readText())
        assertEquals("1.0.0", report["version"])
        val result = report["upgradeResult"] as Map<*, *>
        assertEquals("rolledBack", result["state"]); assertEquals("1.1.0", result["version"]); assertEquals("1.0.0", result["restoredVersion"])
        assertTrue((result["reason"] as String).contains("code 3"), result.toString())
        assertTrue(!Files.exists(data.resolve("upgrade/pending.json")))
    }

    @Test fun anOrdinaryCrashWithNoUpgradePendingIsNotRewrittenAsARollback() {
        val (root, data) = installOld()
        exe(root.resolve("versions/1.0.0/bin/oip-gateway"), script("exit 9"))
        assertEquals(listOf(9), supervise(root, data))
        assertEquals("1.0.0", root.resolve("current").readText().trim())
    }
}
