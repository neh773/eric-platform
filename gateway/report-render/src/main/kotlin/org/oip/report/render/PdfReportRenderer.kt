package org.oip.report.render

import com.fasterxml.jackson.databind.JsonNode
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.oip.report.render.model.Dataset
import org.oip.report.render.model.ReportDefinition
import org.oip.report.render.model.ReportResult
import org.oip.report.render.model.ResolvedParameter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a report definition's layout over a run result into PDF bytes.
 *
 * Output is deterministic for a given (definition, result): document dates come from `result.generatedAt`, the
 * trailer /ID is a hash of the report id, version, run id and `generatedAt`, the only fonts are the bundled DejaVu
 * Sans files (embedded as subsets), and nothing reads the clock, the locale, the default timezone or system fonts.
 * Charts are drawn as vector paths in the page content stream; no AWT/Java2D is used for them. Embedded pictures
 * (image components) are decoded by PDFBox, which uses ImageIO in headless mode for PNG.
 */
internal object PdfReportRenderer {
    const val PRODUCER = "ERIC report-render"

    /** More characters than any page width can show at any font size, so [fit] never measures past this. */
    const val FIT_SCAN_CHARS = 4096

    fun render(definition: ReportDefinition, result: ReportResult): ByteArray {
        val layout = ReportLayout.parse(definition.layout ?: result.layout)
        PDDocument().use { doc ->
            val fonts = Fonts(
                PDType0Font.load(doc, resource("fonts/DejaVuSans.ttf"), true),
                PDType0Font.load(doc, resource("fonts/DejaVuSans-Bold.ttf"), true),
            )
            val title = layout.header.title ?: definition.title ?: result.report.title.ifEmpty { definition.name }
            Writer(doc, layout, fonts, result, title).run {
                header(definition)
                val components = layout.components.ifEmpty {
                    result.datasets.map { TableComponent(it.name, it.name, emptyList(), null) }
                }
                components.forEachIndexed { i, c ->
                    if (i > 0) gap(14f)
                    component(c)
                }
                finish()
            }
            stampMetadata(doc, DataKeys.apply(title, DataKeys.values(result, layout.timezone, 1)), definition, result)
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun resource(name: String) =
        PdfReportRenderer::class.java.getResourceAsStream(name) ?: error("missing bundled resource $name")

    private fun stampMetadata(doc: PDDocument, title: String, definition: ReportDefinition, result: ReportResult) {
        val stamp = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = result.generatedAt }
        doc.documentInformation.apply {
            this.title = title
            subject = definition.description?.takeIf { it.isNotBlank() }
            creator = "OIP reporting (${result.report.id})"
            producer = PRODUCER
            creationDate = stamp
            modificationDate = stamp.clone() as Calendar
        }
        val seed = "${result.report.id}|${result.report.version}|${result.run?.runId ?: ""}|${result.generatedAt}"
        val id = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
        doc.document.trailer.setItem(COSName.ID, COSArray(listOf(COSString(id), COSString(id))))
    }

    internal class Fonts(val regular: PDType0Font, val bold: PDType0Font)

    internal val PALETTE = listOf(
        floatArrayOf(0f, 0.447f, 0.698f), // #0072B2
        floatArrayOf(0.902f, 0.624f, 0f), // #E69F00
        floatArrayOf(0f, 0.620f, 0.451f), // #009E73
        floatArrayOf(0.835f, 0.369f, 0f), // #D55E00
        floatArrayOf(0.800f, 0.475f, 0.655f), // #CC79A7
        floatArrayOf(0.337f, 0.706f, 0.914f), // #56B4E9
        floatArrayOf(0.2f, 0.2f, 0.2f),
    )

    private class Writer(
        val doc: PDDocument,
        val layout: ReportLayout,
        val fonts: Fonts,
        val result: ReportResult,
        val title: String,
    ) {
        val zone: ZoneId = layout.timezone
        val margins = layout.margins
        val bottom = margins.bottom + 12f
        val left = margins.left
        val right = layout.pageWidth - margins.right
        val width = right - left
        val top = layout.pageHeight - margins.top
        var cs: PDPageContentStream? = null
        var y = 0f
        val pages = ArrayList<PDPage>()
        private val glyphCache = HashMap<Pair<PDType0Font, Int>, Boolean>()

        init { newPage() }

        fun newPage() {
            cs?.close()
            val page = PDPage(PDRectangle(layout.pageWidth, layout.pageHeight))
            doc.addPage(page)
            pages += page
            cs = PDPageContentStream(doc, page)
            y = top
            if (pages.size > 1) {
                text(shown(title), left, y - 9f, fonts.bold, 9f, GRAY)
                y -= 22f
            }
        }

        fun ensure(height: Float) { if (y - height < bottom) newPage() }
        fun gap(h: Float) { y -= h; if (y < bottom) newPage() }

        // ---------- header ----------

        fun shown(text: String, page: Int = pages.size.coerceAtLeast(1)): String =
            DataKeys.apply(text, DataKeys.values(result, zone, page))

        fun header(definition: ReportDefinition) {
            y -= 18f
            text(shown(title), left, y, fonts.bold, 18f, BLACK, width)
            y -= 8f
            val subtitle = layout.header.subtitle ?: definition.description?.takeIf { it.isNotBlank() }
            if (subtitle != null) wrapped(shown(subtitle), fonts.regular, 10.5f, GRAY, 14f)
            if (layout.header.showParameters && result.parameters.isNotEmpty()) {
                y -= 2f
                wrapped(result.parameters.joinToString("   ") { "${it.name}: ${parameterText(it)}" }, fonts.regular, 9f, BLACK, 12f)
            }
            if (layout.header.showGeneratedAt) {
                val run = result.run
                val runText = if (run == null) "" else "   run ${run.runId.take(8)} (${run.trigger}, ${run.user}, ${run.status})"
                wrapped("Generated ${Formatting.timestamp(result.generatedAt, Formatting.DEFAULT_TIMESTAMP_PATTERN, zone)} ${zone.id}$runText", fonts.regular, 9f, GRAY, 12f)
            }
            result.run?.warnings?.forEach { wrapped("Warning: $it", fonts.regular, 9f, WARN, 12f) }
            y -= 4f
            line(left, y, right, y, 0.75f, GRAY)
            y -= 14f
        }

        fun parameterText(p: ResolvedParameter): String {
            val v = p.value ?: return "(none)"
            return when {
                p.type == "timeRange" && v.isObject ->
                    "${ts(v.path("from"))} to ${ts(v.path("to"))}"
                p.type == "timestamp" -> ts(v)
                v.isValueNode -> v.asText()
                else -> v.toString()
            }
        }

        fun ts(v: JsonNode) = if (v.isIntegralNumber) Formatting.timestamp(v.asLong(), "yyyy-MM-dd HH:mm", zone) else v.asText()

        // ---------- components ----------

        fun component(raw: LayoutComponent) {
            if (raw is TextComponent) {
                ensure(28f)
                raw.title?.let {
                    y -= 12f
                    text(shown(it), left, y, fonts.bold, 12f, BLACK, width)
                    y -= 8f
                }
                wrapped(shown(raw.text), fonts.regular, 10.5f, BLACK, 14f)
                return
            }
            val bound = ChartBinding.bind(raw) { shown(it) }
            val c = bound.component
            if (c is ImageComponent) return image(c)
            val ds = result.dataset(c.dataset)
            ensure(if (c is TableComponent) 50f else min(c.blockHeight() + 20f, top - 22f - bottom))
            c.title?.let {
                y -= 12f
                text(shown(it), left, y, fonts.bold, 12f, BLACK, width)
                y -= 8f
            }
            bound.problems.forEach { note(it) }
            if (ds == null) return note("Dataset '${c.dataset}' is not in this result.")
            when (c) {
                is TableComponent -> table(c, ds)
                is TimeSeriesChartComponent -> timeSeries(c, ds)
                is BarChartComponent -> bar(c, ds)
                is PieChartComponent -> pie(c, ds)
                is XyChartComponent -> xy(c, ds)
                is TextComponent, is ImageComponent -> Unit
            }
        }

        fun LayoutComponent.blockHeight() = when (this) {
            is TimeSeriesChartComponent -> height
            is BarChartComponent -> height
            is PieChartComponent -> height
            is XyChartComponent -> height
            is TableComponent -> 0f
            is TextComponent -> 28f
            is ImageComponent -> 28f
        }

        fun note(msg: String) {
            ensure(14f)
            y -= 10f
            text(msg, left, y, fonts.regular, 9f, GRAY, width)
            y -= 4f
        }

        // ---------- table ----------

        private inner class ResolvedColumn(val index: Int, val header: String, val type: String, val format: CellFormat, val align: Align, val width: Float)

        fun table(c: TableComponent, ds: Dataset) {
            val specs = c.columns.ifEmpty { ds.columns.map { TableColumn(it.name, null, null, CellFormat(), null, 1f) } }
            val missing = (specs.map { it.field } + listOfNotNull(c.groupBy)).filter { ds.columnIndex(it) < 0 }
            if (missing.isNotEmpty()) return note("Table skipped: dataset '${ds.name}' has no column ${missing.joinToString { "'$it'" }}.")
            val rows = c.maxRows?.let { ds.rows.take(it) } ?: ds.rows
            val resolved = specs.map { s ->
                val idx = ds.columnIndex(s.field)
                val col = ds.columns[idx]
                val unit = s.unit ?: col.unit
                val numeric = s.format.kind == FormatKind.NUMBER || (s.format.kind == FormatKind.AUTO && col.type == "number")
                ResolvedColumn(
                    idx,
                    (s.label ?: s.field) + (unit?.let { " ($it)" } ?: ""),
                    col.type, s.format,
                    s.align ?: if (numeric) Align.RIGHT else Align.LEFT,
                    0f,
                )
            }
            val weights = if (c.autoSize) {
                TableGrouping.autoWeights(resolved.map { col ->
                    TableGrouping.textLength(col.header, rows.take(TableGrouping.AUTO_SAMPLE).map { Formatting.cell(it.getOrNull(col.index), col.type, col.format, zone) })
                })
            } else specs.map { it.width }
            val totalWeight = weights.sum()
            val cols = resolved.mapIndexed { i, col -> ResolvedColumn(col.index, col.header, col.type, col.format, col.align, width * weights[i] / totalWeight) }
            val rowH = 15f
            val size = 8.5f
            fun headerRow() {
                fill(left, y - rowH, width, rowH, HEADER_FILL)
                var x = left
                for (col in cols) { cellText(col.header, x, col.width, col.align, fonts.bold, size); x += col.width }
                y -= rowH
                line(left, y, right, y, 0.5f, GRAY)
            }
            fun ensureRow() { if (y - rowH < bottom) { newPage(); if (c.showHeader) headerRow() } }
            fun totalsRow(label: String, of: List<List<JsonNode?>>) {
                ensureRow()
                fill(left, y - rowH, width, rowH, HEADER_FILL)
                var x = left
                var labeled = false
                for (col in cols) {
                    val text = if (col.type == "number") {
                        val sum = of.sumOf {
                            val cell = it.getOrNull(col.index)
                            if (cell != null && cell.isNumber) cell.asDouble() else 0.0
                        }
                        Formatting.number(sum, col.format.decimals, col.format.grouping)
                    } else if (!labeled) {
                        labeled = true
                        label
                    } else ""
                    cellText(text, x, col.width, col.align, fonts.bold, size)
                    x += col.width
                }
                y -= rowH
            }
            fun detail(row: List<JsonNode?>, shaded: Boolean) {
                ensureRow()
                if (shaded) fill(left, y - rowH, width, rowH, ZEBRA)
                var x = left
                for (col in cols) {
                    val s = Formatting.cell(row.getOrNull(col.index), col.type, col.format, zone)
                    cellText(s, x, col.width, col.align, fonts.regular, size)
                    x += col.width
                }
                y -= rowH
            }
            ensure(rowH * 2)
            if (c.showHeader) headerRow()
            if (c.groupBy == null) {
                rows.forEachIndexed { r, row -> detail(row, c.zebra && r % 2 == 1) }
            } else {
                val gi = ds.columnIndex(c.groupBy)
                val gcol = ds.columns[gi]
                val format = specs.firstOrNull { it.field == c.groupBy }?.format ?: CellFormat()
                for (group in TableGrouping.groups(rows, gi)) {
                    val key = group.key?.let { Formatting.cell(it, gcol.type, format, zone) } ?: "(none)"
                    ensureRow()
                    fill(left, y - rowH, width, rowH, GROUP_FILL)
                    cellText("${gcol.name}: $key (${group.rows.size})", left, width, Align.LEFT, fonts.bold, size)
                    y -= rowH
                    group.rows.forEachIndexed { r, row -> detail(row, c.zebra && r % 2 == 1) }
                    if (c.groupSummary) totalsRow("Subtotal", group.rows)
                }
            }
            if (c.summary) totalsRow("Total", rows)
            line(left, y, right, y, 0.5f, GRAY)
            if (rows.isEmpty()) note("No rows.")
            if (rows.size < ds.rows.size) note("Showing the first ${rows.size} of ${ds.rows.size} rows.")
            if (ds.truncated) note("The data source was truncated by the backend row limit.")
        }

        fun cellText(s: String, x: Float, w: Float, align: Align, font: PDType0Font, size: Float) {
            val pad = 4f
            val fitted = fit(s, font, size, w - 2 * pad)
            val tw = width(fitted, font, size)
            val tx = when (align) {
                Align.LEFT -> x + pad
                Align.RIGHT -> x + w - pad - tw
                Align.CENTER -> x + (w - tw) / 2
            }
            text(fitted, tx, y - 10.5f, font, size, BLACK)
        }

        // ---------- charts ----------

        private inner class Line(val label: String, val unit: String?, val points: List<Pair<Long, Double?>>)

        fun timeSeries(c: TimeSeriesChartComponent, ds: Dataset) {
            val lines: List<Line> = if (ds.series.isNotEmpty()) {
                val wanted = c.series.toSet()
                ds.series.filter { wanted.isEmpty() || it.tagPath in wanted || it.label in wanted }
                    .map { Line(it.label, it.unit, it.points) }
            } else {
                val xIdx = c.x?.let { ds.columnIndex(it) } ?: ds.columns.indexOfFirst { it.type == "timestamp" }
                if (xIdx < 0) return note("Chart skipped: dataset '${ds.name}' has no timestamp column (set x).")
                val yNames = c.y.ifEmpty { ds.columns.filter { it.type == "number" }.map { it.name } }
                val missing = yNames.filter { ds.columnIndex(it) < 0 }
                if (missing.isNotEmpty()) return note("Chart skipped: dataset '${ds.name}' has no column ${missing.joinToString { "'$it'" }}.")
                yNames.map { name ->
                    val yi = ds.columnIndex(name)
                    Line(name, ds.columns[yi].unit, ds.rows.mapNotNull { row ->
                        val t = row.getOrNull(xIdx)?.takeIf { it.isIntegralNumber }?.asLong() ?: return@mapNotNull null
                        t to row.getOrNull(yi)?.takeIf { it.isNumber }?.asDouble()
                    })
                }
            }
            val all = lines.flatMap { it.points }
            val values = all.mapNotNull { it.second }
            if (all.isEmpty() || values.isEmpty()) return note("No data to chart.")
            var t0 = all.minOf { it.first }
            var t1 = all.maxOf { it.first }
            if (t0 == t1) { t0 -= 30 * 60_000L; t1 += 30 * 60_000L }
            val axis = Axes.numeric(values.min(), values.max(), c.yAxis.min, c.yAxis.max)
            val decimals = c.yAxis.decimals ?: axis.decimals
            val unit = c.yAxis.label ?: lines.mapNotNull { it.unit }.distinct().singleOrNull()
            val frame = plotFrame(c.height, axis, decimals, unit, lines.map { it.label to it.unit })
            val maxTicks = max(2, (frame.w / 70f).toInt())
            val time = Axes.time(t0, t1, zone, maxTicks, c.timePattern)
            fun px(t: Long) = frame.x + ((t - t0).toDouble() / (t1 - t0) * frame.w).toFloat()
            for (t in time.ticks) {
                val x = px(t)
                line(x, frame.y, x, frame.y - 3f, 0.5f, GRAY)
                val label = Formatting.timestamp(t, time.pattern, zone)
                val lw = width(label, fonts.regular, 7.5f)
                text(label, (x - lw / 2).coerceIn(frame.x - 10f, frame.x + frame.w - lw + 10f), frame.y - 12f, fonts.regular, 7.5f, GRAY)
            }
            clipped(frame) {
                lines.forEachIndexed { i, l ->
                    val s = cs!!
                    val color = PALETTE[i % PALETTE.size]
                    s.setStrokingColor(color[0], color[1], color[2])
                    s.setLineWidth(1.2f)
                    s.setLineJoinStyle(1)
                    var open = false
                    var runLength = 0
                    var lastX = 0f
                    var lastY = 0f
                    fun endRun() {
                        if (runLength == 1) { s.addRect(lastX - 1f, lastY - 1f, 2f, 2f) }
                        if (runLength >= 1) s.stroke()
                        open = false; runLength = 0
                    }
                    for ((t, v) in l.points.sortedBy { it.first }) {
                        if (v == null || !v.isFinite()) { endRun(); continue }
                        val x = px(t)
                        val yy = frame.py(v)
                        if (!open) { s.moveTo(x, yy); open = true } else s.lineTo(x, yy)
                        runLength++; lastX = x; lastY = yy
                    }
                    endRun()
                }
            }
        }

        fun bar(c: BarChartComponent, ds: Dataset) {
            val ci = ds.columnIndex(c.category)
            val missing = (listOf(c.category) + c.values).filter { ds.columnIndex(it) < 0 }
            if (missing.isNotEmpty()) return note("Chart skipped: dataset '${ds.name}' has no column ${missing.joinToString { "'$it'" }}.")
            val vi = c.values.map { ds.columnIndex(it) }
            if (ds.rows.isEmpty()) return note("No data to chart.")
            val cats = ds.rows.map { Formatting.cell(it.getOrNull(ci), ds.columns[ci].type, c.categoryFormat, zone) }
            val values = ds.rows.map { row -> vi.map { row.getOrNull(it)?.takeIf { n -> n.isNumber }?.asDouble() } }
            val finite = values.flatten().filterNotNull().filter { it.isFinite() }
            val lo = min(0.0, finite.minOrNull() ?: 0.0)
            val hi = max(0.0, finite.maxOrNull() ?: 1.0)
            val axis = Axes.numeric(lo, hi, c.yAxis.min, c.yAxis.max)
            val decimals = c.yAxis.decimals ?: axis.decimals
            val unit = c.yAxis.label ?: vi.mapNotNull { ds.columns[it].unit }.distinct().singleOrNull()
            val frame = plotFrame(c.height, axis, decimals, unit, vi.map { ds.columns[it].name to ds.columns[it].unit })
            val group = frame.w / cats.size
            val barW = group * 0.7f / vi.size
            val labelEvery = max(1, kotlin.math.ceil(40.0 / group).toInt())
            cats.forEachIndexed { i, cat ->
                val gx = frame.x + i * group
                if (i % labelEvery == 0) {
                    val label = fit(cat, fonts.regular, 7.5f, group * labelEvery - 4f)
                    val lw = width(label, fonts.regular, 7.5f)
                    text(label, gx + (group - lw) / 2, frame.y - 12f, fonts.regular, 7.5f, GRAY)
                }
            }
            clipped(frame) {
                val zeroY = frame.py(0.0.coerceIn(axis.min, axis.max))
                cats.indices.forEach { i ->
                    values[i].forEachIndexed { j, v ->
                        if (v == null || !v.isFinite()) return@forEachIndexed
                        val bx = frame.x + i * group + group * 0.15f + j * barW
                        val vy = frame.py(v)
                        fill(bx, min(zeroY, vy), barW, kotlin.math.abs(vy - zeroY), PALETTE[j % PALETTE.size])
                    }
                }
            }
            if (axis.min < 0 && axis.max > 0) { val z = frame.py(0.0); line(frame.x, z, frame.x + frame.w, z, 0.5f, GRAY) }
        }

        // ---------- pie, xy, image ----------

        fun pie(c: PieChartComponent, ds: Dataset) {
            val ci = ds.columnIndex(c.category)
            val missing = listOf(c.category, c.value).filter { ds.columnIndex(it) < 0 }
            if (missing.isNotEmpty()) return note("Chart skipped: dataset '${ds.name}' has no column ${missing.joinToString { "'$it'" }}.")
            val vi = ds.columnIndex(c.value)
            val slices = PieSlices.of(ds.rows.map {
                Formatting.cell(it.getOrNull(ci), ds.columns[ci].type, c.categoryFormat, zone) to
                    it.getOrNull(vi)?.takeIf { n -> n.isNumber }?.asDouble()
            })
            if (slices.isEmpty()) return note("No data to chart.")
            val legend = slices.map { s -> (if (c.showPercent) "${s.label} (${PieSlices.percent(s.fraction)})" else s.label) to ds.columns[vi].unit }
            val height = min(c.height, top - 22f - bottom)
            ensure(height)
            val blockTop = y
            val legendH = legendHeight(legend)
            val diameter = max(40f, min(height - legendH - 14f, width))
            val cx = left + width / 2
            val cy = blockTop - 6f - diameter / 2
            val r = diameter / 2
            val s = cs!!
            var from = 0.0
            slices.forEachIndexed { i, slice ->
                val color = PALETTE[i % PALETTE.size]
                val to = from + slice.fraction
                // Clockwise from 12 o'clock; PDF angles run counter-clockwise, so the sweep is negative.
                val a0 = Math.PI / 2 - 2 * Math.PI * from
                val a1 = Math.PI / 2 - 2 * Math.PI * to
                s.setNonStrokingColor(color[0], color[1], color[2])
                s.setStrokingColor(1f, 1f, 1f)
                s.setLineWidth(0.75f)
                if (slice.fraction >= 0.9999) {
                    arcPath(s, cx, cy, r, a0, a0 - 2 * Math.PI, moveToCentre = false)
                } else {
                    arcPath(s, cx, cy, r, a0, a1, moveToCentre = true)
                }
                s.closePath()
                s.fillAndStroke()
                from = to
            }
            drawLegend(legend, blockTop - height + legendH - 9f)
            y = blockTop - height
        }

        /** Adds a circular arc from angle [a0] to [a1] (radians, signed sweep) as Bezier segments of at most 90 degrees. */
        private fun arcPath(s: PDPageContentStream, cx: Float, cy: Float, r: Float, a0: Double, a1: Double, moveToCentre: Boolean) {
            val segments = max(1, kotlin.math.ceil(kotlin.math.abs(a1 - a0) / (Math.PI / 2) - 1e-9).toInt())
            val step = (a1 - a0) / segments
            fun px(a: Double) = (cx + r * kotlin.math.cos(a)).toFloat()
            fun py(a: Double) = (cy + r * kotlin.math.sin(a)).toFloat()
            if (moveToCentre) { s.moveTo(cx, cy); s.lineTo(px(a0), py(a0)) } else s.moveTo(px(a0), py(a0))
            val k = 4.0 / 3.0 * kotlin.math.tan(step / 4)
            for (i in 0 until segments) {
                val a = a0 + i * step
                val b = a + step
                s.curveTo(
                    (px(a) - k * r * kotlin.math.sin(a)).toFloat(), (py(a) + k * r * kotlin.math.cos(a)).toFloat(),
                    (px(b) + k * r * kotlin.math.sin(b)).toFloat(), (py(b) - k * r * kotlin.math.cos(b)).toFloat(),
                    px(b), py(b),
                )
            }
        }

        fun xy(c: XyChartComponent, ds: Dataset) {
            val missing = (listOf(c.x) + c.y).filter { ds.columnIndex(it) < 0 }
            if (missing.isNotEmpty()) return note("Chart skipped: dataset '${ds.name}' has no column ${missing.joinToString { "'$it'" }}.")
            val xi = ds.columnIndex(c.x)
            val yi = c.y.map { ds.columnIndex(it) }
            val series = yi.map { col ->
                ds.rows.mapNotNull { row ->
                    val xv = row.getOrNull(xi)?.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                    val yv = row.getOrNull(col)?.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                    xv to yv
                }
            }
            val all = series.flatten()
            if (all.isEmpty()) return note("No data to chart.")
            val xAxis = Axes.numeric(all.minOf { it.first }, all.maxOf { it.first }, c.xAxis.min, c.xAxis.max)
            val yAxis = Axes.numeric(all.minOf { it.second }, all.maxOf { it.second }, c.yAxis.min, c.yAxis.max)
            val yDecimals = c.yAxis.decimals ?: yAxis.decimals
            val xDecimals = c.xAxis.decimals ?: xAxis.decimals
            val unit = c.yAxis.label ?: yi.mapNotNull { ds.columns[it].unit }.distinct().singleOrNull()
            val frame = plotFrame(c.height, yAxis, yDecimals, unit, yi.map { ds.columns[it].name to ds.columns[it].unit })
            fun px(v: Double) = frame.x + ((v - xAxis.min) / (xAxis.max - xAxis.min) * frame.w).toFloat()
            for (t in xAxis.ticks) {
                val x = px(t)
                line(x, frame.y, x, frame.y - 3f, 0.5f, GRAY)
                val label = Formatting.number(t, xDecimals, false)
                val lw = width(label, fonts.regular, 7.5f)
                text(label, (x - lw / 2).coerceIn(frame.x - 10f, frame.x + frame.w - lw + 10f), frame.y - 12f, fonts.regular, 7.5f, GRAY)
            }
            val xLabel = c.xAxis.label ?: ds.columns[xi].unit
            if (xLabel != null) {
                val lw = width(xLabel, fonts.regular, 7.5f)
                text(xLabel, frame.x + frame.w - lw, frame.y + 3f, fonts.regular, 7.5f, GRAY)
            }
            clipped(frame) {
                val s = cs!!
                series.forEachIndexed { i, points ->
                    val color = PALETTE[i % PALETTE.size]
                    if (c.connect && points.size > 1) {
                        s.setStrokingColor(color[0], color[1], color[2])
                        s.setLineWidth(1.2f)
                        s.setLineJoinStyle(1)
                        points.forEachIndexed { j, (xv, yv) -> if (j == 0) s.moveTo(px(xv), frame.py(yv)) else s.lineTo(px(xv), frame.py(yv)) }
                        s.stroke()
                    }
                    for ((xv, yv) in points) fill(px(xv) - 2f, frame.py(yv) - 2f, 4f, 4f, color)
                }
            }
        }

        fun image(c: ImageComponent) {
            val src = shown(c.src).trim()
            val bytes = ImageData.decode(src) ?: return note("Image skipped: ${if (src == c.src && c.src.startsWith("@")) "data key ${c.src} has no value" else "the source is not a base64 PNG or JPEG data URI"}.")
            val img = try {
                PDImageXObject.createFromByteArray(doc, bytes, "image")
            } catch (e: Exception) {
                return note("Image skipped: the picture cannot be read.")
            }
            val natural = img.width * 0.75f to img.height * 0.75f
            val maxH = top - 22f - bottom - 24f
            var (w, h) = when {
                c.width != null && c.height != null -> c.width to c.height
                c.width != null -> c.width to c.width * img.height / img.width
                c.height != null -> c.height * img.width / img.height to c.height
                else -> natural
            }
            val scale = min(1f, min(width / w, maxH / h))
            w *= scale; h *= scale
            c.title?.let {
                ensure(h + 24f)
                y -= 12f
                text(shown(it), left, y, fonts.bold, 12f, BLACK, width)
                y -= 8f
            }
            ensure(h + 4f)
            val x = when (c.align) { Align.LEFT -> left; Align.CENTER -> left + (width - w) / 2; Align.RIGHT -> right - w }
            cs!!.drawImage(img, x, y - h, w, h)
            y -= h + 4f
        }

        inner class Frame(val x: Float, val y: Float, val w: Float, val h: Float, val axis: Axes.NumericAxis) {
            fun py(v: Double): Float = y + ((v - axis.min) / (axis.max - axis.min) * h).toFloat()
        }

        /**
         * Reserves [height] points for a chart, draws the y axis, grid, axis lines and legend, and returns the plot
         * rectangle. The x-axis labels go in the 16 points under the plot.
         */
        fun plotFrame(requested: Float, axis: Axes.NumericAxis, decimals: Int, unit: String?, legend: List<Pair<String, String?>>): Frame {
            // A chart never spans pages: clamp to what fits under the continuation header of a fresh page.
            val height = min(requested, top - 22f - bottom)
            ensure(height)
            val blockTop = y
            val labels = axis.ticks.map { Formatting.number(it, decimals, false) }
            val labelW = labels.maxOf { width(it, fonts.regular, 7.5f) }
            val legendH = legendHeight(legend)
            val px = left + labelW + 8f
            val pw = right - px - 6f
            val pTop = blockTop - (if (unit != null) 18f else 6f)
            val pBottom = blockTop - height + legendH + 18f
            val frame = Frame(px, pBottom, pw, pTop - pBottom, axis)
            if (unit != null) text(unit, left, blockTop - 7f, fonts.regular, 7.5f, GRAY)
            axis.ticks.forEachIndexed { i, t ->
                val ty = frame.py(t)
                line(px, ty, px + pw, ty, 0.4f, GRID)
                val lw = width(labels[i], fonts.regular, 7.5f)
                text(labels[i], px - 4f - lw, ty - 2.5f, fonts.regular, 7.5f, GRAY)
            }
            line(px, pBottom, px + pw, pBottom, 0.75f, AXIS)
            line(px, pBottom, px, pTop, 0.75f, AXIS)
            drawLegend(legend, blockTop - height + legendH - 9f)
            y = blockTop - height
            return frame
        }

        private fun legendItems(legend: List<Pair<String, String?>>) = legend.map { (l, u) -> if (u != null) "$l ($u)" else l }

        fun legendHeight(legend: List<Pair<String, String?>>): Float = legendRows(legendItems(legend)).size * 12f

        private fun legendRows(items: List<String>): List<List<Pair<Int, String>>> {
            val rows = ArrayList<MutableList<Pair<Int, String>>>()
            var used = Float.MAX_VALUE
            items.forEachIndexed { i, raw ->
                val label = fit(raw, fonts.regular, 7.5f, width - 20f)
                val w = 14f + width(label, fonts.regular, 7.5f) + 12f
                if (used + w > width) { rows += mutableListOf<Pair<Int, String>>(); used = 0f }
                rows.last() += i to label
                used += w
            }
            return rows
        }

        fun drawLegend(legend: List<Pair<String, String?>>, firstBaseline: Float) {
            legendRows(legendItems(legend)).forEachIndexed { r, row ->
                var x = left
                val by = firstBaseline - r * 12f
                for ((i, label) in row) {
                    fill(x, by + 1f, 10f, 3f, PALETTE[i % PALETTE.size])
                    text(label, x + 14f, by, fonts.regular, 7.5f, BLACK)
                    x += 14f + width(label, fonts.regular, 7.5f) + 12f
                }
            }
        }

        fun clipped(f: Frame, body: () -> Unit) {
            val s = cs!!
            s.saveGraphicsState()
            s.addRect(f.x, f.y, f.w, f.h)
            s.clip()
            body()
            s.restoreGraphicsState()
        }

        // ---------- primitives ----------

        fun safe(s: String, font: PDType0Font): String {
            val out = StringBuilder()
            s.codePoints().forEach { cp0 ->
                val cp = if (cp0 < 0x20 || cp0 == 0x7f) ' '.code else cp0
                val ok = glyphCache.getOrPut(font to cp) {
                    runCatching { font.encode(String(Character.toChars(cp))) }.isSuccess
                }
                if (ok) out.appendCodePoint(cp) else out.append('?')
            }
            return out.toString()
        }

        fun width(s: String, font: PDType0Font, size: Float): Float = font.getStringWidth(safe(s, font)) / 1000f * size

        /**
         * [s] cut to fit [max] points with an ellipsis. Width only grows with length, so the cut point is
         * binary-searched over a prefix clamped to [FIT_SCAN_CHARS]: a long cell costs O(n log n), not O(n²).
         */
        fun fit(s: String, font: PDType0Font, size: Float, max: Float): String {
            val head = s.take(FIT_SCAN_CHARS)
            if (head.length == s.length && width(head, font, size) <= max) return s
            fun fits(end: Int) = width(head.substring(0, end) + "…", font, size) <= max
            var lo = 0
            var hi = head.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (fits(mid)) lo = mid else hi = mid - 1
            }
            var end = lo
            if (end > 0 && Character.isHighSurrogate(head[end - 1])) end--
            return if (end == 0) "" else head.substring(0, end) + "…"
        }

        fun text(s: String, x: Float, baseline: Float, font: PDType0Font, size: Float, color: FloatArray, maxWidth: Float? = null) {
            val str = safe(if (maxWidth != null) fit(s, font, size, maxWidth) else s, font)
            if (str.isEmpty()) return
            val c = cs!!
            c.beginText()
            c.setFont(font, size)
            c.setNonStrokingColor(color[0], color[1], color[2])
            c.newLineAtOffset(x, baseline)
            c.showText(str)
            c.endText()
        }

        /** Word-wraps [s] across the content width, advancing y by [leading] per line. */
        fun wrapped(s: String, font: PDType0Font, size: Float, color: FloatArray, leading: Float) {
            val lines = ArrayList<String>()
            var cur = ""
            for (word in s.split(' ')) {
                val next = if (cur.isEmpty()) word else "$cur $word"
                if (cur.isNotEmpty() && width(next, font, size) > width) { lines += cur; cur = word } else cur = next
            }
            if (cur.isNotEmpty()) lines += cur
            for (l in lines) {
                ensure(leading)
                y -= leading
                text(l, left, y + 3f, font, size, color, width)
            }
        }

        fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: FloatArray) {
            val c = cs!!
            c.setStrokingColor(color[0], color[1], color[2])
            c.setLineWidth(w)
            c.moveTo(x0, y0)
            c.lineTo(x1, y1)
            c.stroke()
        }

        fun fill(x: Float, y0: Float, w: Float, h: Float, color: FloatArray) {
            val c = cs!!
            c.setNonStrokingColor(color[0], color[1], color[2])
            c.addRect(x, y0, w, h)
            c.fill()
        }

        fun finish() {
            cs?.close()
            cs = null
            val generated = Formatting.timestamp(result.generatedAt, Formatting.DEFAULT_TIMESTAMP_PATTERN, zone)
            pages.forEachIndexed { i, page ->
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { s ->
                    cs = s
                    line(left, margins.bottom, right, margins.bottom, 0.5f, GRID)
                    text("${shown(title, i + 1)}   $generated ${zone.id}", left, margins.bottom - 12f, fonts.regular, 7.5f, GRAY, width * 0.75f)
                    val p = "Page ${i + 1} of ${pages.size}"
                    text(p, right - width(p, fonts.regular, 7.5f), margins.bottom - 12f, fonts.regular, 7.5f, GRAY)
                }
            }
            cs = null
        }
    }

    private val BLACK = floatArrayOf(0.1f, 0.1f, 0.1f)
    private val GRAY = floatArrayOf(0.4f, 0.4f, 0.4f)
    private val WARN = floatArrayOf(0.6f, 0.3f, 0f)
    private val GRID = floatArrayOf(0.86f, 0.86f, 0.86f)
    private val AXIS = floatArrayOf(0.45f, 0.45f, 0.45f)
    private val HEADER_FILL = floatArrayOf(0.9f, 0.92f, 0.95f)
    private val ZEBRA = floatArrayOf(0.965f, 0.965f, 0.965f)
    private val GROUP_FILL = floatArrayOf(0.95f, 0.96f, 0.98f)
}
