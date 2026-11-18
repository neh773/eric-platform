package org.oip.report.render

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdfwriter.compress.CompressParameters
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.report.render.model.Column
import org.oip.report.render.model.RenderInput
import org.oip.report.render.model.Dataset
import org.oip.report.render.model.ReportDefinition
import org.oip.report.render.model.ReportRef
import org.oip.report.render.model.ReportResult
import java.io.ByteArrayOutputStream
import java.time.ZoneId

class RenderTest {
    private val nf = JsonNodeFactory.instance
    private val utc = ZoneId.of("UTC")

    @Test
    fun numberFormatting() {
        assertEquals("1,234,567.50", Formatting.number(1234567.5, 2, true))
        assertEquals("1234567.5", Formatting.number(1234567.5, null, false))
        assertEquals("0.333", Formatting.number(1.0 / 3, null, false))
        assertEquals("3", Formatting.number(2.5, 0, false)) // HALF_UP
        assertEquals("-3", Formatting.number(-2.5, 0, false))
        assertEquals("0.00", Formatting.number(-0.001, 2, false)) // no negative zero
        assertEquals("-1,000", Formatting.number(-1000.0, 0, true))
        assertEquals("120", Formatting.number(120.0, null, true))
        assertEquals("0.000001", Formatting.number(1e-6, 6, false))
    }

    @Test
    fun timestampFormatting() {
        val t = 1761544800123L // 2025-10-27T06:00:00.123Z
        assertEquals("2025-10-27 06:00:00", Formatting.timestamp(t, Formatting.DEFAULT_TIMESTAMP_PATTERN, utc))
        assertEquals("27/10/2025 07:00:00.123", Formatting.timestamp(t, "dd/MM/yyyy HH:mm:ss.SSS", ZoneId.of("Europe/Berlin")))
        assertEquals("T 06h", Formatting.timestamp(t, "T HHh", utc))
    }

    @Test
    fun cellFormattingFollowsColumnTypeOrFormat() {
        assertEquals("", Formatting.cell(null, "number", CellFormat(), utc))
        assertEquals("n/a", Formatting.cell(nf.nullNode(), "number", CellFormat(nullText = "n/a"), utc))
        assertEquals("2025-10-27 06:00:00", Formatting.cell(nf.numberNode(1761544800000L), "timestamp", CellFormat(), utc))
        assertEquals("1761544800000", Formatting.cell(nf.numberNode(1761544800000L), "number", CellFormat(), utc))
        assertEquals("true", Formatting.cell(nf.booleanNode(true), "boolean", CellFormat(), utc))
        assertEquals("12.0", Formatting.cell(nf.numberNode(12), "number", CellFormat(FormatKind.NUMBER, 1), utc))
        assertEquals("abc", Formatting.cell(nf.textNode("abc"), "number", CellFormat(FormatKind.NUMBER, 1), utc))
    }

    @Test
    fun niceNumericAxis() {
        val a = Axes.numeric(21.3, 188.9)
        assertEquals(listOf(0.0, 50.0, 100.0, 150.0, 200.0), a.ticks)
        assertEquals(0, a.decimals)
        val b = Axes.numeric(0.12, 0.47)
        assertEquals(0.1, b.step, 1e-12)
        assertEquals(1, b.decimals)
        val flat = Axes.numeric(5.0, 5.0)
        assertTrue(flat.min < 5.0 && flat.max > 5.0)
        val fixed = Axes.numeric(10.0, 20.0, fixedMin = 0.0, fixedMax = 100.0)
        assertEquals(0.0, fixed.min); assertEquals(100.0, fixed.max)
    }

    @Test
    fun timeAxisAlignsToRoundSteps() {
        val from = 1761458400000L // 06:00Z
        val axis = Axes.time(from, from + 24 * 3_600_000L, utc, 10)
        assertEquals("HH:mm", axis.pattern)
        assertEquals(9, axis.ticks.size) // every 3 h, both ends included
        val berlin = Axes.time(from + 60_000, from + 3 * 3_600_000L, ZoneId.of("Europe/Berlin"), 6)
        // first tick on a whole local half-hour: 07:30 Berlin = 06:30Z
        assertEquals(from + 30 * 60_000L, berlin.ticks.first())
    }

    @Test
    fun layoutValidationErrors() {
        fun parse(json: String) = ReportLayout.parse(RenderInput.mapper.readTree(json))
        assertThrows(LayoutException::class.java) { parse("""{"layoutVersion":2}""") }
        assertThrows(LayoutException::class.java) { parse("""{"page":{"size":"A3"}}""") }
        assertThrows(LayoutException::class.java) { parse("""{"timezone":"Mars/Olympus"}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"radar","dataset":"d"}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"table"}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"bar","dataset":"d","values":["v"]}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"timeSeries","dataset":"d","height":10}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"table","dataset":"d","columns":[{"field":"a","format":{"decimals":11}}]}]}""") }
        val ok = parse("""{"page":{"size":"Letter","orientation":"landscape"},"components":[{"type":"table","dataset":"d","columns":["a",{"field":"b","align":"center"}]}]}""")
        assertEquals(792f, ok.pageWidth)
        assertEquals(Align.CENTER, (ok.components.single() as TableComponent).columns[1].align)
    }

    @Test
    fun emptyLayoutRendersOneTablePerDatasetAndNotesProblems() {
        val ds = Dataset("prod", "namedQuery", listOf(Column("line", "string"), Column("count", "number", "pcs")),
            listOf(listOf(nf.textNode("L1"), nf.numberNode(5)), listOf(nf.textNode("L2"), null)), truncated = true)
        val text = text(render(ReportDefinition("d"), result(listOf(ds), null)))
        assertTrue(text.contains("prod"), text)
        assertTrue(text.contains("count (pcs)"), text)
        assertTrue(text.contains("truncated"), text)

        val layout = RenderInput.mapper.readTree(
            """{"components":[{"type":"table","dataset":"missing"},{"type":"table","dataset":"prod","columns":["nope"]},
               {"type":"timeSeries","dataset":"prod"},{"type":"bar","dataset":"prod","category":"line","values":["count"]},
               {"type":"table","dataset":"prod","maxRows":1}]}""",
        )
        val t2 = text(render(ReportDefinition("d", layout = layout), result(listOf(ds), null)))
        assertTrue(t2.contains("Dataset 'missing' is not in this result."), t2)
        assertTrue(t2.contains("has no column 'nope'"), t2)
        assertTrue(t2.contains("has no timestamp column"), t2)
        assertTrue(t2.contains("Showing the first 1 of 2 rows."), t2)
    }

    @Test
    fun dataKeysFillHeaderTextAndBuiltIns() {
        val ds = Dataset("prod", "sql", listOf(Column("line", "string"), Column("count", "number")), listOf(listOf(nf.textNode("L1"), nf.numberNode(5))))
        val layout = RenderInput.mapper.readTree(
            """{"header":{"title":"Line @line@","subtitle":"Report @Report@ page @Page@"},"components":[{"type":"text","text":"Shift @Shift@ on @Date@"}]}""",
        )
        val parameters = listOf(org.oip.report.render.model.ResolvedParameter("Shift", "string", nf.textNode("A")))
        val rendered = text(
            render(
                ReportDefinition("daily", layout = layout),
                ReportResult(1, ReportRef("reporting/gateway/Report/daily", "daily", "Daily", 1), parameters, 1761544800000L, listOf(ds), null, layout),
            ),
        )
        assertTrue(rendered.contains("Line L1"), rendered)
        assertTrue(rendered.contains("Report daily page 1"), rendered)
        assertTrue(rendered.contains("Shift A on "), rendered)
        assertTrue(rendered.contains("@Missing@") || !rendered.contains("@line@"), rendered)
        val unknown = text(
            render(
                ReportDefinition("daily", layout = RenderInput.mapper.readTree("""{"components":[{"type":"text","text":"keep @Missing@"}]}""")),
                result(emptyList(), null),
            ),
        )
        assertTrue(unknown.contains("@Missing@"), unknown)
    }

    @Test
    fun tableSummarySumsNumericColumns() {
        val ds = Dataset(
            "prod", "sql",
            listOf(Column("line", "string"), Column("count", "number")),
            listOf(listOf(nf.textNode("L1"), nf.numberNode(5)), listOf(nf.textNode("L2"), nf.numberNode(7))),
        )
        val layout = RenderInput.mapper.readTree("""{"components":[{"type":"table","dataset":"prod","summary":true}]}""")
        val rendered = text(render(ReportDefinition("d", layout = layout), result(listOf(ds), layout)))
        assertTrue(rendered.contains("Total"), rendered)
        assertTrue(rendered.contains("12"), rendered)
    }

    @Test
    fun longTablesPaginateWithRepeatedHeader() {
        val rows = (0 until 200).map { listOf<JsonNode?>(nf.numberNode(it)) }
        val ds = Dataset("big", "namedQuery", listOf(Column("n", "number")), rows)
        Loader.loadPDF(render(ReportDefinition("big"), result(listOf(ds), null))).use { doc ->
            assertTrue(doc.numberOfPages >= 4)
            for (i in 1..doc.numberOfPages) {
                val page = PDFTextStripper().apply { startPage = i; endPage = i }.getText(doc)
                assertTrue(page.lines().any { it.trim() == "n" }, "header row on page $i")
                assertTrue(page.contains("Page $i of ${doc.numberOfPages}"))
            }
        }
    }

    @Test
    fun oversizedChartsAreClampedToOnePage() {
        val series = org.oip.report.render.model.Series("a", "A", null, (0 until 50).map { 1761458400000L + it * 60_000L to it.toDouble() })
        val ds = Dataset("h", "tagHistory", emptyList(), emptyList(), series = listOf(series))
        val layout = RenderInput.mapper.readTree(
            """{"page":{"orientation":"landscape"},"components":[{"type":"timeSeries","dataset":"h","height":600},{"type":"timeSeries","dataset":"h","height":600}]}""",
        )
        Loader.loadPDF(render(ReportDefinition("h", layout = layout), result(listOf(ds), null))).use { doc ->
            assertEquals(3, doc.numberOfPages) // header page, then one clamped chart per page
        }
    }

    @Test
    fun aVeryLongCellIsCutQuickly() {
        // fit() used to shorten one character at a time and re-measure each time: O(n²), minutes for 100k chars.
        val long = "x".repeat(100_000)
        val ds = Dataset("t", "namedQuery", listOf(Column("s", "string")), listOf(listOf(nf.textNode(long))))
        val started = System.nanoTime()
        val t = text(render(ReportDefinition("t"), result(listOf(ds), null)))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 10_000, "render took $ms ms")
        assertTrue(t.contains("…"), t)
        assertFalse(t.contains("x".repeat(5_000)))
    }

    @Test
    fun unsupportedGlyphsAreReplacedNotFatal() {
        val ds = Dataset("x", "namedQuery", listOf(Column("s", "string")), listOf(listOf(nf.textNode("ok " + String(Character.toChars(0x4E2D)) + " \u0007 done"))))
        val t = text(render(ReportDefinition("x"), result(listOf(ds), null)))
        assertTrue(t.contains("ok ? "), t)
    }

    @Test
    fun canonicalFormIgnoresCompressionButNotContent() {
        val definition = RenderInput.parseDefinition(fixture("table-report.definition.json"))
        val res = RenderInput.parseResult(fixture("table-report.result.json"))
        val pdf = render(definition, res)
        val recompressed = Loader.loadPDF(pdf).use { doc ->
            ByteArrayOutputStream().also { doc.save(it, CompressParameters.NO_COMPRESSION) }.toByteArray()
        }
        assertFalse(pdf.contentEquals(recompressed))
        assertEquals(GoldenPdfTest.canonical(pdf), GoldenPdfTest.canonical(recompressed))
        val other = render(definition.copy(title = "Other title"), res)
        assertNotEquals(GoldenPdfTest.canonical(pdf), GoldenPdfTest.canonical(other))
    }

    /** The internal renderer over the internal model (the public [render] takes the C-9a classes). */
    private fun render(definition: ReportDefinition, result: ReportResult) = PdfReportRenderer.render(definition, result)

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun result(datasets: List<Dataset>, layout: JsonNode?) =
        ReportResult(1, ReportRef("reporting/gateway/Report/t", "t", "T", 1), emptyList(), 1761544800000L, datasets, null, layout)

    private fun text(pdf: ByteArray) = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
}
