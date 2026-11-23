package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Linux deploy with the bundled `plc/llvm/ld.lld`: no C compiler is involved. Needs the staged Linux bundle. */
class PlcLinkBundledLldTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val bundle = System.getenv("ERIC_TEST_LINUX_PLC_DIR")?.let(::File) ?: File(root, "packaging/build/plc-bundle-linux")

    @Test fun linksWithTheBundledLinker(@TempDir dir: Path) {
        val plc = File(bundle, "rusty/plc")
        assumeTrue(!System.getProperty("os.name").contains("win", true) && plc.canExecute() && bundledLinuxLinker(plc) != null)
        val source = Files.createDirectories(dir.resolve("programs")).resolve("Pump.st")
        Files.writeString(
            source,
            """
            PROGRAM Pump
            VAR_OUTPUT answer AT %QD0 : DINT; END_VAR
            VAR x : DINT := 42; p : REF_TO DINT := REF(x); delay : TON; END_VAR
            delay(IN := TRUE, PT := T#20ms);
            answer := p^;
            END_PROGRAM
            """.trimIndent(),
        )
        val so = PlcCompiler(plc.absolutePath).link(source)!!.toFile()
        assertTrue(so.isFile)
        assertTrue(File(so.parentFile, "libiec61131std.so").isFile)
        val head = so.readBytes().copyOf(4)
        assertTrue(head.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
        System.getenv("ERIC_TEST_KEEP")?.takeIf { it.isNotBlank() }?.let { so.parentFile.copyRecursively(File(it), overwrite = true) }
    }
}
