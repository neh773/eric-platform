package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Links a Windows scan library with LLVM's lld-link and no Microsoft tools.
 *
 * Needs `ERIC_TEST_PLC_DIR`: a `plc` folder laid out like the install (`rusty/plc[.exe]`, `rusty/stdlib/include`,
 * `rusty/iec61131std.dll`, `st/`), and lld-link either in `llvm/` there or on PATH. On Linux the RuSTy compiler
 * cross-compiles with `--target x86_64-pc-windows-msvc`. Skipped when the folder is not given.
 */
class WindowsPlcLinkTest {
    @TempDir lateinit var tmp: Path

    private fun plcDir(): File {
        val dir = System.getenv("ERIC_TEST_PLC_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(dir != null && dir.isDirectory, "ERIC_TEST_PLC_DIR is not set")
        return dir!!
    }

    private fun compiler(dir: File): File =
        listOf("rusty/plc.exe", "rusty/plc").map { dir.resolve(it) }.first { it.isFile }

    private val windows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    @Test fun linksAProgramWithTimersTheProcessLibraryAndStandardConstants() {
        val dir = plcDir()
        val plc = compiler(dir)
        val linker = windowsLinker(plc)
        assumeTrue(linker != null, "no lld-link")
        val source = tmp.resolve("Pump.st")
        Files.writeString(
            source,
            """
            PROGRAM Pump
            VAR_INPUT start AT %IX0.0 : BOOL; END_VAR
            VAR_OUTPUT answer AT %QD0 : DINT; run AT %QX4.0 : BOOL; END_VAR
            VAR x : DINT := 42; p : REF_TO DINT := REF(x); delay : TON; scaled : LREAL; buffer : ARRAY[0..4095] OF BYTE; END_VAR
            delay(IN := start, PT := T#20ms);
            run := delay.Q;
            answer := p^;
            scaled := SQRT(LREAL#2.0) * PI_LREAL + INT_TO_LREAL(3);
            buffer[1] := 7;
            END_PROGRAM
            """.trimIndent(),
        )
        val dll = linkWindows(plc, source, linker, target = if (windows) null else "x86_64-pc-windows-msvc").toFile()
        assertTrue(dll.isFile)
        System.getenv("ERIC_TEST_KEEP")?.takeIf { it.isNotBlank() }?.let { dll.parentFile.copyRecursively(File(it), overwrite = true) }
        val exports = PeExports.read(dll.readBytes()).map { it.name }
        for (name in listOf("Pump", "Pump_instance", CTORS_BEGIN, CTORS_END)) assertTrue(name in exports, "$name not exported: $exports")
        assertTrue(dll.parentFile.resolve("iec61131std.dll").isFile)
        assertTrue(dll.parentFile.resolve(libraryStem(dll.name) + "-hwmap.json").isFile)
    }

    @Test fun theStandardLibraryDllExportsTimersAsCodeAndConstantsAsData() {
        val dll = plcDir().resolve("rusty/iec61131std.dll")
        assumeTrue(dll.isFile)
        val exports = PeExports.read(dll.readBytes()).associate { it.name to it.code }
        assertEquals(true, exports["TON"])
        assertEquals(false, exports["PI_LREAL"])
    }
}
