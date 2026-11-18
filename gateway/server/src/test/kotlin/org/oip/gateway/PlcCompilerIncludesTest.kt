package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PlcCompilerIncludesTest {
    @TempDir lateinit var tmp: Path

    @Test fun includesTheStandardLibraryAndTheProcessLibraryWhenBothExist() {
        val binary = tmp.resolve("plc/rusty/plc.exe").toFile()
        binary.parentFile.mkdirs()
        binary.writeText("")
        File(binary.parentFile, "stdlib/include").mkdirs()
        File(binary.parentFile.parentFile, "st").mkdirs()
        val includes = compilerIncludes(binary)
        assertEquals(4, includes.size)
        assertEquals("-i", includes[0])
        assertTrue(includes[1].endsWith("stdlib${File.separator}include${File.separator}*.st"))
        assertEquals("-i", includes[2])
        assertTrue(includes[3].endsWith("st${File.separator}*.st"))
        assertEquals(listOf("-i", includes[3]), libraryIncludes(binary))
    }

    @Test fun libraryStemStripsThePrefixAndEitherSuffix() {
        assertEquals("Pump", libraryStem("libPump.so"))
        assertEquals("Pump", libraryStem("Pump.dll"))
        assertEquals("LevelLoop", libraryStem("LevelLoop.dll"))
    }

    @Test fun dumpbinExportsSkipUndefinedSymbols() {
        val dump = """
            00E 00000000 UNDEF  notype ()    External     | TON
            013 00000000 SECT1  notype ()    External     | SealIn
            024 00000010 SECT3  notype       External     | SealIn_instance
        """.trimIndent()
        assertEquals(listOf("SealIn", "SealIn_instance"), exportedSymbols(dump))
    }

    @Test fun aCompilerWithNoSiblingLibrariesAddsNothing() {
        val binary = tmp.resolve("bin/plc").toFile()
        binary.parentFile.mkdirs()
        assertTrue(compilerIncludes(binary).isEmpty())
        assertTrue(libraryIncludes(binary).isEmpty())
    }
}
