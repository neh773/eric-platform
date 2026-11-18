package org.oip.report.render

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.report.render.model.Column
import org.oip.report.render.model.Dataset
import org.oip.report.render.model.RenderInput
import org.oip.report.render.model.ReportDefinition
import org.oip.report.render.model.ReportRef
import org.oip.report.render.model.ReportResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory

/** Report object page size and margins (FEATURES REP-010), table options (REP-051), component catalog (REP-013). */
class PageSetupTest {
    private val nf = JsonNodeFactory.instance

    private fun render(definition: ReportDefinition, result: ReportResult) = PdfReportRenderer.render(definition, result)

    private fun parse(json: String) = ReportLayout.parse(RenderInput.mapper.readTree(json))

    private fun result(ds: List<Dataset>, layout: JsonNode?) =
        ReportResult(1, ReportRef("id", "daily", "Daily", 1), emptyList(), 1761544800000L, ds, null, layout)

    private fun pdf(layoutJson: String, rows: Int = 3): ByteArray {
        val layout = RenderInput.mapper.readTree(layoutJson)
        val ds = Dataset("t", "namedQuery", listOf(Column("name", "string")), (0 until rows).map { listOf<JsonNode?>(nf.textNode("row$it")) })
        return render(ReportDefinition("daily", layout = layout), result(listOf(ds), layout))
    }

    private fun minX(bytes: ByteArray, pageIndex: Int = 0): Float {
        var min = Float.MAX_VALUE
        Loader.loadPDF(bytes).use { doc ->
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    if (text.unicode.isNotBlank()) min = minOf(min, text.xDirAdj)
                    super.processTextPosition(text)
                }
            }
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(doc)
        }
        return min
    }

    @Test
    fun marginsAndSizesParse() {
        assertEquals(Margins.STANDARD, parse("{}").margins)
        assertEquals(Margins(60f, 60f, 60f, 60f), parse("""{"page":{"margins":60}}""").margins)
        assertEquals(Margins(30f, 50f, 40f, 70f), parse("""{"page":{"margins":{"top":30,"right":50,"left":70}}}""").margins)
        assertEquals(PageSize.LEGAL, parse("""{"page":{"size":"Legal"}}""").pageSize)
        assertEquals(PageSize.A5, parse("""{"page":{"size":"a5"}}""").pageSize)
        for (bad in listOf(
            """{"page":{"margins":5}}""", """{"page":{"margins":900}}""", """{"page":{"margins":"wide"}}""",
            """{"page":{"margins":{"top":30,"middle":3}}}""", """{"page":{"size":"A5","margins":{"left":200,"right":200}}}""",
        )) assertThrows(LayoutException::class.java, { parse(bad) }, bad)
    }

    @Test
    fun legalPageHasItsOwnMediaBox() {
        Loader.loadPDF(pdf("""{"page":{"size":"Legal"}}""")).use { doc ->
            assertEquals(612f, doc.getPage(0).mediaBox.width)
            assertEquals(1008f, doc.getPage(0).mediaBox.height)
        }
    }

    @Test
    fun leftMarginMovesTheText() {
        val narrow = minX(pdf("""{"page":{"margins":24}}"""))
        val wide = minX(pdf("""{"page":{"margins":{"left":120}}}"""))
        assertTrue(narrow < 40f, "narrow=$narrow")
        assertTrue(wide >= 118f, "wide=$wide")
    }

    @Test
    fun largerTopAndBottomMarginsGivePagesFewerRows() {
        fun pages(margin: Int) = Loader.loadPDF(pdf("""{"page":{"margins":$margin}}""", rows = 120)).use { it.numberOfPages }
        assertTrue(pages(150) > pages(24), "more margin, more pages")
    }

    @Test
    fun tableHeaderRowCanBeTurnedOff() {
        fun text(layoutJson: String) = Loader.loadPDF(pdf(layoutJson)).use { PDFTextStripper().getText(it) }
        val with = text("""{"components":[{"type":"table","dataset":"t","columns":[{"field":"name","label":"Machine name"}]}]}""")
        val without = text("""{"components":[{"type":"table","dataset":"t","showHeader":false,"zebra":false,"columns":[{"field":"name","label":"Machine name"}]}]}""")
        assertTrue(with.contains("Machine name"), with)
        assertFalse(without.contains("Machine name"), without)
        assertTrue(without.contains("row2"), without)
        val table = parse("""{"components":[{"type":"table","dataset":"t","showHeader":false}]}""").components.single() as TableComponent
        assertFalse(table.showHeader)
        assertTrue(table.zebra)
    }

    @Test
    fun catalogListsExactlyTheComponentsTheLayoutAccepts() {
        val types = ComponentCatalog.components.map { it.type }
        assertEquals(listOf("text", "table", "bar", "timeSeries", "pie", "xy", "image"), types)
        for (c in ComponentCatalog.components) {
            // A new component starts valid once an editor fills the data source and required columns.
            val defaults = LinkedHashMap(c.defaults)
            if (c.needsDataset) defaults["dataset"] = "d"
            if (c.type == "bar") { defaults["category"] = "a"; defaults["values"] = listOf("b") }
            if (c.type == "pie") { defaults["category"] = "a"; defaults["value"] = "b" }
            if (c.type == "xy") { defaults["x"] = "a"; defaults["y"] = listOf("b") }
            if (c.type == "image") defaults["src"] = "@Logo@"
            val json = RenderInput.mapper.writeValueAsString(mapOf("components" to listOf(defaults)))
            assertEquals(1, parse(json).components.size, c.type)
            // every property an editor may draw is named in the schema doc of the type
            assertTrue(c.properties.isNotEmpty())
            assertEquals(c.needsDataset, c.properties.any { it.kind == "dataset" }, c.type)
        }
        assertTrue(ComponentCatalog.report.any { it.name == "page.margins" })
        assertEquals(PageSize.entries.map { it.wire }, ComponentCatalog.report.first { it.name == "page.size" }.options)
    }
}
