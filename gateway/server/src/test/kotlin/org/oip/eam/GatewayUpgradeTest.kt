package org.oip.eam

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.gateway.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** EAM-011: marker, pointer, and rollback logic of [GatewayUpgrade]. The real restart is in [GatewayUpgradeRestartTest]. */
class GatewayUpgradeTest {
    @TempDir lateinit var tmp: Path
    private var now = 1_000L

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bo ->
        ZipOutputStream(bo).use { z -> for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private fun install(): GatewayUpgrade {
        tmp.resolve("install").createDirectories().resolve("current").let { Files.writeString(it, "1.0.0\n") }
        tmp.resolve("data").createDirectories()
        return GatewayUpgrade(tmp.resolve("install"), tmp.resolve("data")) { now }
    }

    fun stage(bytes: ByteArray, version: String) {
        val dir = tmp.resolve("data/upgrade").createDirectories()
        Files.write(dir.resolve("staged.bin"), bytes)
        Files.writeString(dir.resolve("staged.json"), Json.write(mapOf("version" to version, "sha256" to EamController.sha256(bytes))))
    }

    @Test fun applyUnpacksMarksPendingAndSwitchesThePointer() {
        val u = install()
        stage(zip("bin/oip-gateway" to "#!/bin/sh\n", "lib/a.txt" to "x"), "1.1.0")
        val marker = u.apply(5_000)
        assertEquals("1.0.0", marker["from"]); assertEquals("1.1.0", marker["to"]); assertEquals(6_000L, marker["deadlineMillis"])
        assertEquals("1.1.0", u.currentVersion())
        assertTrue(tmp.resolve("install/versions/1.1.0/lib/a.txt").exists())
        assertTrue(Files.isExecutable(tmp.resolve("install/versions/1.1.0/bin/oip-gateway")))
        assertEquals("1.1.0", u.pending()!!["to"])
    }

    @Test fun healthyBootClearsTheMarkerAndRecordsSuccess() {
        val u = install(); stage(zip("bin/oip-gateway" to "x"), "1.1.0"); u.apply(5_000)
        now = 2_000
        val boot = u.onBoot()
        assertEquals(GatewayUpgrade.Boot.Pending("1.1.0", 6_000), boot)
        u.markHealthy()
        assertNull(u.pending())
        assertEquals("applied", u.result()!!["state"]); assertEquals("1.1.0", u.result()!!["version"])
        assertEquals("1.1.0", u.currentVersion())
        assertEquals(GatewayUpgrade.Boot.Nothing, u.onBoot())
    }

    @Test fun bootAfterTheDeadlineRollsBackWithAReason() {
        val u = install(); stage(zip("bin/oip-gateway" to "x"), "1.1.0"); u.apply(5_000)
        now = 6_001
        val boot = u.onBoot()
        assertTrue(boot is GatewayUpgrade.Boot.RolledBack)
        assertEquals("1.0.0", u.currentVersion())
        assertNull(u.pending())
        val r = u.result()!!
        assertEquals("rolledBack", r["state"]); assertEquals("1.1.0", r["version"]); assertEquals("1.0.0", r["restoredVersion"])
        assertTrue((r["reason"] as String).contains("deadline"))
    }

    @Test fun bootAfterTheLauncherAlreadyRolledBackJustDropsTheStaleMarker() {
        val u = install(); stage(zip("bin/oip-gateway" to "x"), "1.1.0"); u.apply(5_000)
        Files.writeString(tmp.resolve("install/current"), "1.0.0\n")
        assertEquals(GatewayUpgrade.Boot.Nothing, u.onBoot())
        assertNull(u.pending())
    }

    @Test fun badPackagesAreRefusedAndLeaveTheInstallUntouched() {
        val u = install()
        stage(zip("../evil" to "x", "bin/oip-gateway" to "x"), "1.1.0")
        assertThrows(IllegalArgumentException::class.java) { u.apply() }
        stage(zip("lib/a" to "x"), "1.1.0")
        assertTrue(assertThrows(IllegalArgumentException::class.java) { u.apply() }.message!!.contains("bin/oip-gateway"))
        stage(ByteArray(100) { 7 }, "1.1.0")
        assertTrue(assertThrows(IllegalArgumentException::class.java) { u.apply() }.message!!.contains("not a zip"))
        stage(zip("bin/oip-gateway" to "x"), "1.0.0")
        assertThrows(IllegalArgumentException::class.java) { u.apply() }
        assertEquals("1.0.0", u.currentVersion()); assertNull(u.pending())
        assertFalse(tmp.resolve("install/versions/1.1.0").exists()); assertFalse(tmp.resolve("install/versions/1.1.0.partial").exists())
        assertFalse(tmp.resolve("install/evil").exists())
    }

    @Test fun staleStagedPackageIsRefusedAndTwoAppliesDoNotStack() {
        val u = install(); stage(zip("bin/oip-gateway" to "x"), "1.1.0")
        Files.write(tmp.resolve("data/upgrade/staged.bin"), byteArrayOf(1, 2, 3))
        assertTrue(assertThrows(IllegalStateException::class.java) { u.apply() }.message!!.contains("checksum"))
        stage(zip("bin/oip-gateway" to "x"), "1.1.0"); u.apply()
        assertThrows(IllegalStateException::class.java) { u.apply() }
        assertTrue(assertThrows(IllegalStateException::class.java) { GatewayUpgrade(null, tmp.resolve("data")).apply() }.message!!.contains("launcher"))
    }

    @Test fun versionThatIsNotAPlainDirectoryNameIsRefusedAndNothingIsDeleted() {
        val u = install()
        val keep = tmp.resolve("install/versions/1.0.0").createDirectories().resolve("keep.txt")
        Files.writeString(keep, "x")
        val pkg = zip("bin/oip-gateway" to "#!/bin/sh\n")
        for (bad in listOf("..", ".", "../1.0.0", "1..0", ".hidden", "1.0/2")) {
            stage(pkg, bad)
            assertThrows(IllegalArgumentException::class.java) { u.apply() }
            assertTrue(keep.exists(), "install files survive a staged version of '$bad'")
            assertEquals("1.0.0", u.currentVersion())
        }
        assertTrue(GatewayUpgrade.validVersion("2.1.0-rc.1+b7"))
    }
}
