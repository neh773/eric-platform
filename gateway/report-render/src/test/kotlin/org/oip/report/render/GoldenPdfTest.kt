package org.oip.report.render

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.oip.report.render.model.RenderInput
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import javax.imageio.ImageIO

/**
 * Golden-file acceptance tests: one table report and one chart report.
 *
 * Comparison: the rendered PDF must first be byte-identical across two renders in this JVM (determinism). It is
 * then compared byte-for-byte with `src/test/resources/golden/<name>.pdf`. If the bytes differ, the test falls back
 * to a canonical structural dump of both files (every object reachable from the trailer, dictionary keys sorted,
 * streams replaced by the SHA-256 of their *decoded* data; `/Length`, `/Filter`, `/DecodeParms` and the xref keys
 * `/W /Index /Prev /Size /XRefStm`, which follow from compressed sizes and offsets, are ignored), which
 * must match exactly. The fallback only absorbs differences in Flate compression output, which depends on the
 * zlib build inside the JDK; any change to drawing operators, text, fonts, metadata or the document ID still fails.
 * The extracted text must also equal `<name>.txt`, which keeps reviews of golden updates readable.
 *
 * Regenerate after an intended rendering change:
 *   ./gradlew :gateway:report-render:test -Poip.updateGoldens=true
 * then review `git diff gateway/report-render/src/test/resources/golden/ (the .txt files)` and the page PNGs written to
 * gateway/report-render/build/golden-actual/.
 */
class GoldenPdfTest {
    private val update = System.getProperty("oip.updateGoldens") == "true"
    private val goldenDir = File(System.getProperty("oip.goldenDir") ?: "src/test/resources/golden")
    private val outDir = File(System.getProperty("oip.goldenOutDir") ?: "build/golden-actual")

    @ParameterizedTest
    @ValueSource(strings = ["table-report", "chart-report"])
    fun matchesGolden(name: String) {
        // Through the public entry point with the real C-9a classes, as the REST route and the scheduler sink call it.
        val definition = ContractFixtures.definition(fixture("$name.definition.json"))
        val result = ContractFixtures.result(fixture("$name.result.json"))
        val first = render(definition, result)
        val second = render(definition, result)
        assertArrayEquals(first, second, "rendering $name twice must give identical bytes")
        val text = extractText(first)

        outDir.mkdirs()
        File(outDir, "$name.pdf").writeBytes(first)
        File(outDir, "$name.txt").writeText(text)
        writePngs(first, name)

        val goldenPdf = File(goldenDir, "$name.pdf")
        val goldenTxt = File(goldenDir, "$name.txt")
        if (update) {
            goldenDir.mkdirs()
            goldenPdf.writeBytes(first)
            goldenTxt.writeText(text)
            println("updated golden ${goldenPdf.path}")
            return
        }
        assertTrue(goldenPdf.isFile, "missing golden ${goldenPdf.path}; run with -Poip.updateGoldens=true")
        assertEquals(goldenTxt.readText().replace("\r\n", "\n"), text.replace("\r\n", "\n"), "extracted text of $name differs from ${goldenTxt.path}")
        val expected = goldenPdf.readBytes()
        if (!expected.contentEquals(first)) {
            assertEquals(canonical(expected), canonical(first),
                "$name differs from ${goldenPdf.path} beyond stream compression; actual written to ${outDir.path}")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["table-report", "chart-report"])
    fun embedsOnlyBundledFontsAndFixedMetadata(name: String) {
        val result = ContractFixtures.result(fixture("$name.result.json"))
        val pdf = render(ContractFixtures.definition(fixture("$name.definition.json")), result)
        Loader.loadPDF(pdf).use { doc ->
            val fonts = doc.pages.flatMap { p -> p.resources.fontNames.map { p.resources.getFont(it) } }
            assertTrue(fonts.isNotEmpty())
            for (f in fonts) {
                assertTrue(f is PDType0Font && f.isEmbedded, "font ${f.name} must be an embedded Type0 font")
                assertTrue(f.name.contains("DejaVuSans"), "unexpected font ${f.name}")
            }
            // PDF dates have one-second resolution.
            assertEquals(result.generatedAt / 1000 * 1000, doc.documentInformation.creationDate.timeInMillis)
            assertEquals(result.generatedAt / 1000 * 1000, doc.documentInformation.modificationDate.timeInMillis)
            assertEquals(PdfReportRenderer.PRODUCER, doc.documentInformation.producer)
            val id = doc.document.trailer.getCOSArray(COSName.ID)
            assertEquals(2, id.size())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["table-report", "chart-report"])
    fun realClassesRenderLikeTheirContractJson(name: String) {
        // The C-9a classes, converted through their toMap() JSON, must render exactly like the contract JSON itself.
        val fromClasses = render(ContractFixtures.definition(fixture("$name.definition.json")), ContractFixtures.result(fixture("$name.result.json")))
        val fromJson = PdfReportRenderer.render(
            RenderInput.parseDefinition(fixture("$name.definition.json")),
            RenderInput.parseResult(fixture("$name.result.json")),
        )
        assertArrayEquals(fromJson, fromClasses)
    }

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")?.readText() ?: error("missing fixture $name")

    private fun extractText(pdf: ByteArray): String = Loader.loadPDF(pdf).use { doc ->
        PDFTextStripper().apply { lineSeparator = "\n"; sortByPosition = true }.getText(doc)
    }

    private fun writePngs(pdf: ByteArray, name: String) {
        Loader.loadPDF(pdf).use { doc ->
            val renderer = PDFRenderer(doc)
            for (i in 0 until doc.numberOfPages) {
                ImageIO.write(renderer.renderImageWithDPI(i, 72f), "png", File(outDir, "$name-p${i + 1}.png"))
            }
        }
    }

    companion object {
        private val IGNORED = setOf(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.W, COSName.INDEX, COSName.PREV, COSName.SIZE, COSName.XREF_STM)

        /** Canonical dump of the object graph reachable from the trailer, with decoded stream contents hashed. */
        fun canonical(pdf: ByteArray): String = Loader.loadPDF(pdf).use { doc: PDDocument ->
            val out = StringBuilder()
            val seen = IdentityHashMap<COSBase, Int>()
            val queue = ArrayDeque<COSBase>()
            fun ref(o: COSBase?): String {
                val target = (o as? COSObject)?.`object` ?: o ?: return "null"
                if (target is COSDictionary || target is COSArray) {
                    val id = seen.getOrPut(target) { queue.addLast(target); seen.size }
                    return "@$id"
                }
                return target.toString()
            }
            ref(doc.document.trailer)
            while (queue.isNotEmpty()) {
                val o = queue.removeFirst()
                out.append('@').append(seen[o]).append(' ')
                when (o) {
                    is COSArray -> out.append(o.toList().joinToString(" ", "[", "]") { ref(it) })
                    is COSDictionary -> {
                        val keys = o.keySet().filter { it !in IGNORED }.sortedBy { it.name }
                        out.append(keys.joinToString(" ", "<<", ">>") { "/${it.name} ${ref(o.getItem(it))}" })
                        if (o is COSStream) {
                            val data = o.createInputStream().use { it.readAllBytes() }
                            out.append(" stream sha256=").append(MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
                        }
                    }
                }
                out.append('\n')
            }
            out.toString()
        }
    }
}
