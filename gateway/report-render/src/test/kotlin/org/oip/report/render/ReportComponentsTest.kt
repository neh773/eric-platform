package org.oip.report.render

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.report.render.model.Column
import org.oip.report.render.model.Dataset
import org.oip.report.render.model.RenderInput
import org.oip.report.render.model.ReportDefinition
import org.oip.report.render.model.ReportRef
import org.oip.report.render.model.ReportResult
import org.oip.report.render.model.ResolvedParameter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Report components added on top of the first catalog: grouped and auto-sized tables (REP-050, REP-016), pie and
 * XY charts (REP-004, REP-005), data-key bound chart settings (REP-059) and images (REP-023).
 */
class ReportComponentsTest {
    private val nf = JsonNodeFactory.instance

    private fun layout(json: String): JsonNode = RenderInput.mapper.readTree(json)

    private fun result(datasets: List<Dataset>, parameters: List<ResolvedParameter> = emptyList()) =
        ReportResult(1, ReportRef("reporting/gateway/Report/t", "t", "T", 1), parameters, 1761544800000L, datasets, null, null)

    private fun render(layout: JsonNode, datasets: List<Dataset>, parameters: List<ResolvedParameter> = emptyList()) =
        PdfReportRenderer.render(ReportDefinition("t", layout = layout), result(datasets, parameters))

    private fun text(pdf: ByteArray) = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

    private fun str(v: String): JsonNode = nf.textNode(v)
    private fun num(v: Number): JsonNode = if (v is Int || v is Long) nf.numberNode(v.toLong()) else nf.numberNode(v.toDouble())

    private val prod = Dataset(
        "prod", "sql", listOf(Column("line", "string"), Column("shift", "string"), Column("count", "number")),
        listOf(
            listOf(str("L2"), str("A"), num(3)),
            listOf(str("L1"), str("A"), num(5)),
            listOf(str("L2"), str("B"), num(4)),
            listOf(str("L1"), str("B"), num(7)),
            listOf(str("L1"), str("C"), num(1)),
        ),
    )

    // ---- REP-050 ----

    @Test
    fun tableGroupsRowsByKeyInOrderOfFirstAppearance() {
        val groups = TableGrouping.groups(prod.rows, 0)
        assertEquals(listOf("L2", "L1"), groups.map { it.key!!.asText() })
        assertEquals(listOf(2, 3), groups.map { it.rows.size })
        // Rows keep their order inside a group.
        assertEquals(listOf("A", "B", "C"), groups[1].rows.map { it[1]!!.asText() })
    }

    @Test
    fun nullKeysFormOneGroup() {
        val rows = listOf(listOf<JsonNode?>(null, num(1)), listOf(nf.nullNode(), num(2)), listOf(str("x"), num(3)))
        val groups = TableGrouping.groups(rows, 0)
        assertEquals(2, groups.size)
        assertNull(groups[0].key)
        assertEquals(2, groups[0].rows.size)
    }

    @Test
    fun groupedTableShowsHeadingsSubtotalsAndGrandTotal() {
        val t = text(render(layout("""{"components":[{"type":"table","dataset":"prod","groupBy":"line","groupSummary":true,"summary":true}]}"""), listOf(prod)))
        assertTrue(t.contains("line: L2 (2)"), t)
        assertTrue(t.contains("line: L1 (3)"), t)
        assertTrue(t.indexOf("line: L2 (2)") < t.indexOf("line: L1 (3)"), t)
        // L2 sums to 7, L1 to 13, all rows to 20.
        assertTrue(t.lines().any { it.trim().startsWith("Subtotal") && it.trim().endsWith("7") }, t)
        assertTrue(t.lines().any { it.trim().startsWith("Subtotal") && it.trim().endsWith("13") }, t)
        assertTrue(t.lines().any { it.trim().startsWith("Total") && it.trim().endsWith("20") }, t)
    }

    @Test
    fun groupedTableWithoutSubtotalsHasNone() {
        val t = text(render(layout("""{"components":[{"type":"table","dataset":"prod","groupBy":"shift"}]}"""), listOf(prod)))
        assertTrue(t.contains("shift: A (2)"), t)
        assertFalse(t.contains("Subtotal"), t)
        assertFalse(t.lines().any { it.trim().startsWith("Total") }, t)
    }

    @Test
    fun groupColumnMustExist() {
        val t = text(render(layout("""{"components":[{"type":"table","dataset":"prod","groupBy":"nope"}]}"""), listOf(prod)))
        assertTrue(t.contains("has no column 'nope'"), t)
    }

    @Test
    fun groupedTablePaginatesWithRepeatedHeader() {
        val rows = (0 until 120).map { listOf<JsonNode?>(str("g${it / 10}"), num(it)) }
        val ds = Dataset("big", "sql", listOf(Column("g", "string"), Column("n", "number")), rows)
        Loader.loadPDF(render(layout("""{"components":[{"type":"table","dataset":"big","groupBy":"g","groupSummary":true}]}"""), listOf(ds))).use { doc ->
            assertTrue(doc.numberOfPages >= 3)
            for (i in 1..doc.numberOfPages) {
                val page = PDFTextStripper().apply { startPage = i; endPage = i }.getText(doc)
                assertTrue(page.lines().any { it.trim() == "g n" }, "header row on page $i: $page")
            }
        }
    }

    // ---- REP-016 ----

    @Test
    fun autoWeightsFollowTextLengthWithinLimits() {
        assertEquals(listOf(4f, 12f, 40f), TableGrouping.autoWeights(listOf(1, 12, 300)))
        assertEquals(9, TableGrouping.textLength("id", listOf("a", "123456789")))
        assertEquals(5, TableGrouping.textLength("hello", emptyList()))
    }

    @Test
    fun autoSizedTableGivesLongTextTheRoomItNeeds() {
        val long = "z".repeat(38)
        val cols = listOf("a", "b", "c", "d", "e").map { Column(it, "string") }
        val ds = Dataset("w", "sql", cols, listOf(listOf(str("1"), str("2"), str("3"), str(long), str(long))))
        val equal = text(render(layout("""{"components":[{"type":"table","dataset":"w"}]}"""), listOf(ds)))
        val auto = text(render(layout("""{"components":[{"type":"table","dataset":"w","autoSize":true}]}"""), listOf(ds)))
        assertFalse(equal.contains(long), "equal columns cut the long text")
        assertTrue(auto.contains(long), auto)
    }

    // ---- REP-004 ----

    @Test
    fun pieSlicesIgnoreNonPositiveAndSumOverflowIntoOther() {
        assertTrue(PieSlices.of(listOf("a" to 0.0, "b" to -1.0, "c" to null, "d" to Double.NaN)).isEmpty())
        val s = PieSlices.of(listOf("a" to 1.0, "b" to 3.0, "c" to 0.0))
        assertEquals(listOf("a", "b"), s.map { it.label })
        assertEquals(0.25, s[0].fraction, 1e-12)
        assertEquals("25.0%", PieSlices.percent(s[0].fraction))
        assertEquals("33.3%", PieSlices.percent(1.0 / 3))

        val many = PieSlices.of((1..15).map { "c$it" to it.toDouble() })
        assertEquals(PieSlices.MAX_SLICES, many.size)
        assertEquals("Other", many.last().label)
        // The three smallest (1, 2, 3) and the next smallest (4) are merged: 11 kept, 1 + 2 + 3 + 4 = 10 in Other.
        assertEquals(10.0, many.last().value, 1e-12)
        assertEquals(1.0, many.sumOf { it.fraction }, 1e-9)
        assertEquals(listOf("c5", "c6"), many.map { it.label }.take(2))
    }

    @Test
    fun pieChartRendersLegendWithPercentages() {
        val pdf = render(layout("""{"components":[{"type":"pie","dataset":"prod","title":"Share","category":"line","value":"count"}]}"""),
            listOf(Dataset("prod", "sql", listOf(Column("line", "string"), Column("count", "number", "pcs")),
                listOf(listOf(str("L1"), num(1)), listOf(str("L2"), num(3))))))
        val t = text(pdf)
        assertTrue(t.contains("Share"), t)
        assertTrue(t.contains("L1 (25.0%) (pcs)"), t)
        assertTrue(t.contains("L2 (75.0%) (pcs)"), t)
        Loader.loadPDF(pdf).use { assertEquals(1, it.numberOfPages) }
        // Slices are 25% and 75% of a disc: the first two palette colours both appear, the larger slice with about 3x the area.
        val blue = pixelsNear(pdf, 0x0072B2)
        val orange = pixelsNear(pdf, 0xE69F00)
        assertTrue(blue > 500 && orange > 1500, "blue $blue orange $orange")
        assertTrue(orange > blue * 2 && orange < blue * 4, "blue $blue orange $orange")
    }

    @Test
    fun pieChartWithOneSliceAndWithoutPercent() {
        val ds = Dataset("p", "sql", listOf(Column("k", "string"), Column("v", "number")), listOf(listOf(str("only"), num(9))))
        val t = text(render(layout("""{"components":[{"type":"pie","dataset":"p","category":"k","value":"v","showPercent":false}]}"""), listOf(ds)))
        assertTrue(t.lines().any { it.trim() == "only" }, t)
        assertFalse(t.contains("%"), t)
    }

    @Test
    fun pieChartNotesMissingColumnsAndNoData() {
        val ds = Dataset("p", "sql", listOf(Column("k", "string"), Column("v", "number")), listOf(listOf(str("a"), num(0))))
        val t = text(render(layout("""{"components":[{"type":"pie","dataset":"p","category":"k","value":"v"},{"type":"pie","dataset":"p","category":"k","value":"zz"}]}"""), listOf(ds)))
        assertTrue(t.contains("No data to chart."), t)
        assertTrue(t.contains("has no column 'zz'"), t)
    }

    // ---- REP-005 ----

    private val points = Dataset(
        "pts", "sql", listOf(Column("speed", "number", "rpm"), Column("temp", "number", "C"), Column("load", "number")),
        listOf(
            listOf(num(100), num(20.5), num(1)),
            listOf(num(200), num(35), num(2)),
            listOf(num(300), num(51), null),
            listOf(str("n/a").takeIf { false }, num(1), num(1)),
        ),
    )

    @Test
    fun xyChartDrawsAxesAndLegend() {
        val t = text(render(layout("""{"components":[{"type":"xy","dataset":"pts","title":"Curve","x":"speed","y":["temp","load"],"connect":true}]}"""), listOf(points)))
        assertTrue(t.contains("Curve"), t)
        assertTrue(t.contains("temp (C)"), t)
        assertTrue(t.contains("temp (C) load"), t)
        assertTrue(t.contains("rpm"), t)
        assertTrue(t.contains("300"), t)
        val pdf = render(layout("""{"components":[{"type":"xy","dataset":"pts","x":"speed","y":["temp"]}]}"""), listOf(points))
        assertTrue(pixelsNear(pdf, 0x0072B2) >= 3 * 12, "three 4x4 markers")
    }

    @Test
    fun xyChartNotesMissingColumnsAndNoNumericPoints() {
        val t = text(render(layout("""{"components":[{"type":"xy","dataset":"pts","x":"nope","y":["temp"]},{"type":"xy","dataset":"pts","x":"speed","y":["nope"]}]}"""), listOf(points)))
        assertEquals(2, Regex("has no column").findAll(t).count(), t)
        val words = Dataset("w", "sql", listOf(Column("a", "string"), Column("b", "string")), listOf(listOf(str("x"), str("y"))))
        val t2 = text(render(layout("""{"components":[{"type":"xy","dataset":"w","x":"a","y":["b"]}]}"""), listOf(words)))
        assertTrue(t2.contains("No data to chart."), t2)
    }

    @Test
    fun xyAxisBoundsAreHonoured() {
        val t = text(render(layout("""{"components":[{"type":"xy","dataset":"pts","x":"speed","y":["temp"],"xAxis":{"min":0,"max":1000},"yAxis":{"min":0,"max":100}}]}"""), listOf(points)))
        assertTrue(t.lines().any { it.trim() == "100" }, t)
        assertTrue(t.contains("1000"), t)
    }

    // ---- REP-059 ----

    @Test
    fun chartDatasetColumnsAndAxisLimitsComeFromDataKeys() {
        val params = listOf(
            ResolvedParameter("Source", "string", nf.textNode("pts")),
            ResolvedParameter("Col", "string", nf.textNode("temp")),
            ResolvedParameter("Limit", "number", nf.numberNode(500)),
            ResolvedParameter("Plant", "string", nf.textNode("North")),
        )
        val t = text(render(layout("""{"components":[{"type":"xy","dataset":"@Source@","title":"Temp at @Plant@","x":"speed","y":["@Col@"],"yAxis":{"min":0,"max":"@Limit@","label":"@Plant@ degrees"}}]}"""), listOf(points), params))
        assertTrue(t.contains("Temp at North"), t)
        assertTrue(t.contains("North degrees"), t)
        assertTrue(t.lines().any { it.trim() == "500" }, t)
        assertTrue(t.contains("temp (C)"), t)
    }

    @Test
    fun axisLimitFromFirstRowColumn() {
        val ds = Dataset("cfg", "sql", listOf(Column("hi", "number")), listOf(listOf(num(250))))
        val bars = Dataset("b", "sql", listOf(Column("k", "string"), Column("v", "number")), listOf(listOf(str("a"), num(10)), listOf(str("b"), num(20))))
        val t = text(render(layout("""{"components":[{"type":"bar","dataset":"b","category":"k","values":["v"],"yAxis":{"min":0,"max":"@hi@"}}]}"""), listOf(ds, bars)))
        // Without the bound limit the axis would stop at 20; with 250 the ticks run 0, 100, 200.
        assertTrue(t.lines().any { it.trim() == "200" }, t)
    }

    @Test
    fun badAxisKeyFallsBackToAutomaticWithANote() {
        val bars = Dataset("b", "sql", listOf(Column("k", "string"), Column("v", "number")), listOf(listOf(str("a"), num(10))))
        val t = text(render(layout("""{"components":[{"type":"bar","dataset":"b","category":"k","values":["v"],"yAxis":{"max":"@Nope@"}}]}"""), listOf(bars)))
        assertTrue(t.contains("yAxis.max '@Nope@' is not a number (unknown key)"), t)
        assertTrue(t.contains("a"), t)
    }

    @Test
    fun axisKeysParseNextToNumbers() {
        val l = ReportLayout.parse(layout("""{"components":[{"type":"bar","dataset":"d","category":"k","values":["v"],"yAxis":{"min":1,"max":"@M@"}}]}"""))
        val axis = (l.components.single() as BarChartComponent).yAxis
        assertEquals(1.0, axis.min)
        assertNull(axis.max)
        assertEquals("@M@", axis.maxKey)
    }

    // ---- REP-023 ----

    private fun png(width: Int = 40, height: Int = 20): String {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) img.setRGB(x, y, if ((x / 4 + y / 4) % 2 == 0) 0x0072B2 else 0xE69F00)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Pixels of page 1, rasterised at 72 dpi, within a small distance of [rgb]. */
    private fun pixelsNear(pdf: ByteArray, rgb: Int): Int = Loader.loadPDF(pdf).use { doc ->
        val img = org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 72f)
        var n = 0
        for (x in 0 until img.width) for (y in 0 until img.height) {
            val p = img.getRGB(x, y)
            val d = kotlin.math.abs(((p shr 16) and 255) - ((rgb shr 16) and 255)) + kotlin.math.abs(((p shr 8) and 255) - ((rgb shr 8) and 255)) + kotlin.math.abs((p and 255) - (rgb and 255))
            if (d < 24) n++
        }
        n
    }

    private fun imageCount(pdf: ByteArray) = Loader.loadPDF(pdf).use { doc ->
        doc.pages.sumOf { page -> page.resources.xObjectNames.count { page.resources.isImageXObject(it) } }
    }

    @Test
    fun imageFromDataUriIsEmbedded() {
        val pdf = render(layout("""{"components":[{"type":"image","title":"Logo","src":"${png()}","width":120,"align":"center"}]}"""), emptyList())
        assertEquals(1, imageCount(pdf))
        assertTrue(text(pdf).contains("Logo"))
    }

    @Test
    fun imageComesFromADataKey() {
        val params = listOf(ResolvedParameter("Logo", "string", nf.textNode(png())))
        assertEquals(1, imageCount(render(layout("""{"components":[{"type":"image","src":"@Logo@","height":50}]}"""), emptyList(), params)))
        val t = text(render(layout("""{"components":[{"type":"image","src":"@Missing@"}]}"""), emptyList(), params))
        assertTrue(t.contains("Image skipped: data key @Missing@ has no value."), t)
    }

    @Test
    fun imageFromAColumnCell() {
        val ds = Dataset("brand", "sql", listOf(Column("logo", "string")), listOf(listOf(str(png()))))
        assertEquals(1, imageCount(render(layout("""{"components":[{"type":"image","src":"@logo@"}]}"""), listOf(ds))))
    }

    @Test
    fun badPicturesAreNotesNotFailures() {
        val params = listOf(
            ResolvedParameter("Text", "string", nf.textNode("hello")),
            ResolvedParameter("Broken", "string", nf.textNode("data:image/png;base64," + Base64.getEncoder().encodeToString("not a png".toByteArray()))),
        )
        val t = text(render(layout("""{"components":[{"type":"image","src":"@Text@"},{"type":"image","src":"@Broken@"}]}"""), emptyList(), params))
        assertTrue(t.contains("not a base64 PNG or JPEG data URI"), t)
        assertTrue(t.contains("the picture cannot be read"), t)
    }

    @Test
    fun largePicturesShrinkToTheContentWidth() {
        val pdf = render(layout("""{"components":[{"type":"image","src":"${png(2000, 1000)}"},{"type":"text","text":"after"}]}"""), emptyList())
        Loader.loadPDF(pdf).use { assertEquals(1, it.numberOfPages) }
        assertTrue(text(pdf).contains("after"))
    }

    @Test
    fun imageDataDecoding() {
        assertNotNull(ImageData.decode(png()))
        assertNull(ImageData.decode("data:image/gif;base64,R0lGODlh"))
        assertNull(ImageData.decode("data:image/png;base64,***"))
        assertNull(ImageData.decode("https://example.invalid/logo.png"))
        assertNull(ImageData.decode("data:image/png;base64,"))
    }

    // ---- layout validation ----

    @Test
    fun newComponentsValidateTheirSettings() {
        fun parse(json: String) = ReportLayout.parse(layout(json))
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"pie","dataset":"d","category":"k"}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"pie","dataset":"d","value":"v"}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"xy","dataset":"d","y":["v"]}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"xy","dataset":"d","x":"x"}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"image"}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"image","src":"https://example.invalid/a.png"}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"image","src":"@K@","width":2}]}""") }
        assertThrows(LayoutException::class.java) { parse("""{"components":[{"type":"image","src":"@K@","align":"middle"}]}""") }
        val ok = parse("""{"components":[{"type":"pie","dataset":"d","category":"k","value":"v"},{"type":"xy","dataset":"d","x":"x","y":["a","b"],"connect":true},{"type":"image","src":"@K@"}]}""")
        assertEquals(3, ok.components.size)
        assertTrue((ok.components[1] as XyChartComponent).connect)
        val table = parse("""{"components":[{"type":"table","dataset":"d","groupBy":"g","groupSummary":true,"autoSize":true}]}""").components.single() as TableComponent
        assertEquals("g", table.groupBy)
        assertTrue(table.groupSummary && table.autoSize)
    }

    @Test
    fun catalogListsTheNewComponents() {
        val types = ComponentCatalog.components.map { it.type }
        assertTrue(types.containsAll(listOf("pie", "xy", "image")), types.toString())
        val table = ComponentCatalog.components.first { it.type == "table" }.properties.map { it.name }
        assertTrue(table.containsAll(listOf("groupBy", "groupSummary", "autoSize")), table.toString())
    }
}
