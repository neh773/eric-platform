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

    @Test fun ctorMarkerObjectDefinesBothMarkers() {
        assertEquals(listOf(CTORS_BEGIN, CTORS_END), Coff.definedExternals(Coff.ctorMarkers()))
    }

    @Test fun undefinedSymbolsReadAsAMissingLibraryFunction() {
        val text = "lld-link: error: undefined symbol: FOO\n>>> referenced by main.obj\nwarning: something"
        assertEquals("the program calls FOO, which this ERIC install's standard library does not provide", linkErrors(text))
    }

    @Test fun theBundledLinkerSitsInPlcLlvm() {
        val binary = tmp.resolve("plc/rusty/plc.exe").toFile()
        binary.parentFile.mkdirs()
        binary.writeText("")
        assertEquals(null, windowsLinker(binary, path = ""))
        val lld = tmp.resolve("plc/llvm/lld-link.exe").toFile()
        lld.parentFile.mkdirs()
        lld.writeText("")
        assertEquals(lld.absoluteFile, windowsLinker(binary, path = ""))
    }

    @Test fun aCompilerWithNoSiblingLibrariesAddsNothing() {
        val binary = tmp.resolve("bin/plc").toFile()
        binary.parentFile.mkdirs()
        assertTrue(compilerIncludes(binary).isEmpty())
        assertTrue(libraryIncludes(binary).isEmpty())
    }
}
