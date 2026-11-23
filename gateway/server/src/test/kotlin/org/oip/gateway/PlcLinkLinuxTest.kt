package org.oip.gateway

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** The Linux deploy link in the installed layout (`plc/rusty/plc`, `plc/st`): process-library blocks are linked in. */
class PlcLinkLinuxTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val rusty = File(root, "plc/eric-plc/.cache/rusty-1.0.8")

    @Test fun linksTheProcessLibraryBodiesIntoTheProgram(@TempDir dir: Path) {
        assumeTrue(!System.getProperty("os.name").contains("win", true) && File(rusty, "plc").isFile && linuxLinker() != null)
        val plc = dir.resolve("plc").toFile()
        rusty.copyRecursively(File(plc, "rusty"))
        File(root, "plc/eric-plc/st").copyRecursively(File(plc, "st"))
        File(plc, "rusty/plc").setExecutable(true)
        val programs = Files.createDirectories(dir.resolve("programs"))
        val source = programs.resolve("LevelLoop.st")
        File(root, "plc/eric-plc/examples/level/level.st").copyTo(source.toFile())
        val so = PlcCompiler(File(plc, "rusty/plc").absolutePath).link(source)!!
        val undefined = ProcessBuilder("nm", "-D", "--undefined-only", so.toString()).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()
        assertFalse(undefined.contains("ERIC_PID")) { undefined }
        assertTrue(processLibraryUnits(File(plc, "rusty/plc")).map { it.name }.contains("eric_pid.st"))
    }

    @Test fun findsALinkerOnPathOrNone(@TempDir dir: Path) {
        assertNull(linuxLinker(dir.toString()))
        val cc = dir.resolve("gcc").toFile().apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        assertEquals("gcc", linuxLinker(cc.parent))
    }
}
