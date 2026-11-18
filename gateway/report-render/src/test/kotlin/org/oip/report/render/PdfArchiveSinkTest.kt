package org.oip.report.render

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PdfArchiveSinkTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @Test
    fun deliverRendersAndKeepsNewest(@TempDir dir: Path) {
        val definition = ContractFixtures.definition(fixture("table-report.definition.json"))
        val base = ContractFixtures.result(fixture("table-report.result.json"))
        val sink = PdfArchiveSink(dir, keep = 2)
        for (i in 1..3) {
            sink.deliver(definition, base.copy(generatedAt = base.generatedAt + i * 1000, run = base.run.copy(runId = "00000000-0000-0000-0000-00000000000$i")))
        }
        val files = sink.files(definition.name)
        assertEquals(listOf(base.generatedAt + 3000, base.generatedAt + 2000), files.map { it.generatedAt })
        assertEquals("${base.generatedAt + 3000}-00000000-0000-0000-0000-000000000003.pdf", files[0].file)
        val path = checkNotNull(sink.file(definition.name, files[0].file))
        assertEquals(files[0].size, Files.size(path))
        Loader.loadPDF(Files.readAllBytes(path)).use { assertTrue(it.numberOfPages >= 1) }
        // No temporary files are left behind.
        assertEquals(2, Files.list(dir.resolve(definition.name)).use { it.count() })
    }

    @Test
    fun symlinksInTheArchiveAreNotServed(@TempDir dir: Path) {
        val sink = PdfArchiveSink(dir.resolve("archive"))
        Files.createDirectories(dir.resolve("archive/r"))
        val outside = Files.write(dir.resolve("secret.pdf"), byteArrayOf(1))
        Files.createSymbolicLink(dir.resolve("archive/r/1-x.pdf"), outside)
        assertNull(sink.file("r", "1-x.pdf"))
        assertTrue(sink.files("r").isEmpty())
    }

    @Test
    fun namesCannotEscapeTheArchive(@TempDir dir: Path) {
        val sink = PdfArchiveSink(dir.resolve("archive"))
        Files.createDirectories(dir.resolve("archive"))
        Files.write(dir.resolve("1-x.pdf"), byteArrayOf(1))
        assertNull(sink.file("..", "1-x.pdf"))
        assertNull(sink.file("r", "../1-x.pdf"))
        assertNull(sink.file("r", "missing.pdf"))
        assertEquals(emptyList<ArchivedReport>(), sink.files(".."))
        assertEquals(emptyList<ArchivedReport>(), sink.files("unknown"))
        assertThrows(IllegalArgumentException::class.java) { sink.store("../r", 1, "x", byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { sink.store("r", 1, "../x", byteArrayOf()) }
    }
}
