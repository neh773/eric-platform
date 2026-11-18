package org.oip.report.render

import com.fasterxml.jackson.databind.JsonNode
import java.time.DateTimeException
import java.time.ZoneId

/*
 * Report `layout` schema, layoutVersion 1. The backend stores `spec.layout` verbatim; this module owns its meaning.
 * The same schema is implemented by the web preview (web/components/src/report/layout.ts); a change must go to both.
 *
 * {
 *   "layoutVersion": 1,                                  // optional, anything else is rejected
 *   "page": { "size": "A4" | "A5" | "Letter" | "Legal", "orientation": "portrait" | "landscape",
 *             "margins": 40 | { "top", "right", "bottom", "left" } },   // points; default 40 each; 24..200
 *                                                      // (doc source: FEATURES REP-010)
 *   "timezone": "UTC",                                   // IANA zone for every timestamp in the report
 *   "header": { "title", "subtitle", "showParameters": true, "showGeneratedAt": true },
 *   "components": [                                      // drawn top to bottom; empty = one table per dataset
 *     { "type": "table", "dataset", "title", "maxRows", "summary": false, "showHeader": true, "zebra": true,
 *       "groupBy": "col", "groupSummary": false,         // heading row per distinct value (REP-050); subtotal per group
 *       "autoSize": false,                               // column widths from the text lengths (REP-016)
 *       "columns": [ "field" | { "field", "label", "unit", "align": "left|right|center", "width": 1.0,
 *                                "format": { "type": "auto|text|number|timestamp|boolean", "decimals": 0..10,
 *                                            "grouping": false, "pattern": "yyyy-MM-dd HH:mm:ss", "nullText": "" } } ] },
 *     { "type": "timeSeries", "dataset", "title", "height": 220,
 *       "series": [tagPath|label],                       // tagHistory datasets; empty = all series
 *       "x": "col", "y": ["col"],                        // other datasets: timestamp column and number columns
 *       "yAxis": { "min", "max", "label", "decimals" }, "timePattern": "HH:mm" },
 *     { "type": "bar", "dataset", "title", "height": 220, "category": "col", "values": ["col"],
 *       "categoryFormat": { ...format }, "yAxis": { ... } },
 *     { "type": "pie", "dataset", "title", "height": 220, "category": "col", "value": "col",
 *       "categoryFormat": { ...format }, "showPercent": true },     // REP-004
 *     { "type": "xy", "dataset", "title", "height": 220, "x": "col", "y": ["col"], "connect": false,
 *       "xAxis": { ... }, "yAxis": { ... } },                       // REP-005
 *     { "type": "image", "src": "data:image/png;base64,..." | "@Key@", "width", "height", "align", "alt" },  // REP-023
 *     { "type": "text", "text", "title" }                 // no dataset; @Name@ is a data key
 *   ]
 *
 * Chart `dataset`, column names (`category`, `value`, `values`, `x`, `y`, `series`), `title` and `yAxis`/`xAxis`
 * `label`, `min` and `max` may hold data keys (`"@Limit@"`), filled in when the report renders (REP-059).
 * }
 *
 * Missing datasets/columns at render time become a one-line note in place of the component; schema violations
 * throw [LayoutException] with the JSON path.
 */

class LayoutException(message: String) : IllegalArgumentException(message)

enum class PageSize(val widthPt: Float, val heightPt: Float, val wire: String) {
    A4(595.2756f, 841.8898f, "A4"),
    A5(419.5276f, 595.2756f, "A5"),
    LETTER(612f, 792f, "Letter"),
    LEGAL(612f, 1008f, "Legal"),
}

/** Page margins in points. The footer sits inside the bottom margin, so the minimum is [MIN]. */
data class Margins(val top: Float, val right: Float, val bottom: Float, val left: Float) {
    companion object {
        const val DEFAULT = 40f
        const val MIN = 24f
        const val MAX = 200f
        val STANDARD = Margins(DEFAULT, DEFAULT, DEFAULT, DEFAULT)
    }
}

enum class FormatKind { AUTO, TEXT, NUMBER, TIMESTAMP, BOOLEAN }

enum class Align { LEFT, RIGHT, CENTER }

data class CellFormat(
    val kind: FormatKind = FormatKind.AUTO,
    val decimals: Int? = null,
    val grouping: Boolean = false,
    val pattern: String? = null,
    val nullText: String = "",
)

data class TableColumn(
    val field: String,
    val label: String?,
    val unit: String?,
    val format: CellFormat,
    val align: Align?,
    val width: Float,
)

data class Header(
    val title: String?,
    val subtitle: String?,
    val showParameters: Boolean,
    val showGeneratedAt: Boolean,
)

/**
 * Numeric axis settings. [min] and [max] may instead be a data key such as `"@Limit@"` ([minKey], [maxKey]),
 * resolved when the report renders (FEATURES REP-059).
 */
data class YAxis(
    val min: Double?,
    val max: Double?,
    val label: String?,
    val decimals: Int?,
    val minKey: String? = null,
    val maxKey: String? = null,
)

sealed interface LayoutComponent {
    val title: String?
    val dataset: String
}

data class TableComponent(
    override val title: String?,
    override val dataset: String,
    /** Empty = every dataset column in order, with auto formats. */
    val columns: List<TableColumn>,
    val maxRows: Int?,
    /** A totals row after the detail rows. Numeric columns are summed; the first text column reads "Total". */
    val summary: Boolean = false,
    /** The header row (column labels), repeated on every page. Default true. */
    val showHeader: Boolean = true,
    /** Shade every other detail row. Default true. */
    val zebra: Boolean = true,
    /** Column whose value groups the rows (REP-050): a heading row per distinct value, groups in order of first appearance. */
    val groupBy: String? = null,
    /** With [groupBy]: a subtotal row after each group (numeric columns summed). */
    val groupSummary: Boolean = false,
    /** Column widths follow the longest text in each column instead of the `width` weights (REP-016). */
    val autoSize: Boolean = false,
) : LayoutComponent

data class TimeSeriesChartComponent(
    override val title: String?,
    override val dataset: String,
    /** Restrict to these series (tagPath or label). Empty = all series of the dataset. */
    val series: List<String>,
    /** For datasets without `series` (named queries): timestamp column and numeric columns to plot. */
    val x: String?,
    val y: List<String>,
    val height: Float,
    val yAxis: YAxis,
    val timePattern: String?,
) : LayoutComponent

data class TextComponent(
    override val title: String?,
    val text: String,
) : LayoutComponent {
    override val dataset: String get() = ""
}

data class BarChartComponent(
    override val title: String?,
    override val dataset: String,
    val category: String,
    val values: List<String>,
    val height: Float,
    val yAxis: YAxis,
    val categoryFormat: CellFormat,
) : LayoutComponent

/** Share of a total per category (FEATURES REP-004). Rows with a missing, zero or negative value draw no slice. */
data class PieChartComponent(
    override val title: String?,
    override val dataset: String,
    val category: String,
    val value: String,
    val height: Float,
    val categoryFormat: CellFormat,
    /** Legend entries read "label (12.5%)". Default true. */
    val showPercent: Boolean = true,
) : LayoutComponent

/** Numeric x against one or more numeric y columns as markers, optionally joined by lines (FEATURES REP-005). */
data class XyChartComponent(
    override val title: String?,
    override val dataset: String,
    val x: String,
    val y: List<String>,
    val height: Float,
    val xAxis: YAxis,
    val yAxis: YAxis,
    /** Join the points of each series in row order. Default false (scatter). */
    val connect: Boolean = false,
) : LayoutComponent

/** A picture from a `data:image/png|jpeg;base64,` URI, literal or through a data key (FEATURES REP-023). */
data class ImageComponent(
    override val title: String?,
    val src: String,
    /** Points; when only one is set the other follows the picture's aspect ratio. Neither: natural size at 96 dpi. */
    val width: Float?,
    val height: Float?,
    val align: Align,
    val alt: String?,
) : LayoutComponent {
    override val dataset: String get() = ""
}

data class ReportLayout(
    val pageSize: PageSize,
    val landscape: Boolean,
    val timezone: ZoneId,
    val header: Header,
    val components: List<LayoutComponent>,
    val margins: Margins = Margins.STANDARD,
) {
    val pageWidth: Float get() = if (landscape) pageSize.heightPt else pageSize.widthPt
    val pageHeight: Float get() = if (landscape) pageSize.widthPt else pageSize.heightPt

    companion object {
        const val LAYOUT_VERSION = 1
        const val DEFAULT_CHART_HEIGHT = 220f

        /**
         * Parses a layout object. A null or empty layout yields the default: a header and one table per dataset
         * (the caller fills that in from the result, see [PdfReportRenderer]).
         */
        fun parse(node: JsonNode?): ReportLayout {
            val n = node?.takeIf { it.isObject }
            val version = n?.get("layoutVersion")?.asInt(LAYOUT_VERSION) ?: LAYOUT_VERSION
            if (version != LAYOUT_VERSION) throw LayoutException("layoutVersion $version is not supported (expected $LAYOUT_VERSION)")
            val page = n?.path("page")
            val sizeText = page?.path("size")?.asText("A4") ?: "A4"
            val size = PageSize.entries.firstOrNull { it.name == sizeText.uppercase() }
                ?: throw LayoutException("page.size must be ${PageSize.entries.joinToString(", ") { it.wire }}, not ${sizeText.uppercase()}")
            val orientation = page?.path("orientation")?.asText("portrait") ?: "portrait"
            if (orientation != "portrait" && orientation != "landscape") throw LayoutException("page.orientation must be portrait or landscape")
            val zone = try {
                ZoneId.of(n?.path("timezone")?.asText("UTC") ?: "UTC")
            } catch (e: DateTimeException) {
                throw LayoutException("timezone: ${e.message}")
            }
            val h = n?.path("header")
            val header = Header(
                title = h?.text("title"),
                subtitle = h?.text("subtitle"),
                showParameters = h?.path("showParameters")?.asBoolean(true) ?: true,
                showGeneratedAt = h?.path("showGeneratedAt")?.asBoolean(true) ?: true,
            )
            val components = n?.path("components")?.mapIndexed { i, c -> component(c, "components[$i]") } ?: emptyList()
            val margins = margins(page?.get("margins"))
            val landscape = orientation == "landscape"
            val width = if (landscape) size.heightPt else size.widthPt
            if (width - margins.left - margins.right < MIN_CONTENT) throw LayoutException("page.margins leave less than ${MIN_CONTENT.toInt()} points of width")
            val height = if (landscape) size.widthPt else size.heightPt
            if (height - margins.top - margins.bottom < MIN_CONTENT) throw LayoutException("page.margins leave less than ${MIN_CONTENT.toInt()} points of height")
            return ReportLayout(size, landscape, zone, header, components, margins)
        }

        private const val MIN_CONTENT = 100f

        private fun margins(node: JsonNode?): Margins {
            if (node == null || node.isNull) return Margins.STANDARD
            fun one(value: JsonNode?, at: String): Float {
                if (value == null || !value.isNumber) throw LayoutException("$at must be a number of points")
                val v = value.asDouble().toFloat()
                if (v < Margins.MIN || v > Margins.MAX) throw LayoutException("$at must be ${Margins.MIN.toInt()}..${Margins.MAX.toInt()} points")
                return v
            }
            if (node.isNumber) return one(node, "page.margins").let { Margins(it, it, it, it) }
            if (!node.isObject) throw LayoutException("page.margins must be a number or an object")
            val extra = node.fieldNames().asSequence().filter { it !in setOf("top", "right", "bottom", "left") }.toList()
            if (extra.isNotEmpty()) throw LayoutException("page.margins: unknown fields ${extra.sorted().joinToString()}")
            fun side(name: String) = if (node.has(name)) one(node.get(name), "page.margins.$name") else Margins.DEFAULT
            return Margins(side("top"), side("right"), side("bottom"), side("left"))
        }

        private fun component(c: JsonNode, at: String): LayoutComponent {
            if (!c.isObject) throw LayoutException("$at must be an object")
            val title = c.text("title")
            val type = c.path("type").asText("")
            if (type == "text") {
                val text = c.text("text") ?: throw LayoutException("$at.text is required")
                if (text.length > 8_000) throw LayoutException("$at.text is too long")
                return TextComponent(title, text)
            }
            if (type == "image") return image(c, title, at)
            val dataset = c.text("dataset") ?: throw LayoutException("$at.dataset is required")
            return when (type) {
                "table" -> TableComponent(
                    title, dataset,
                    c.path("columns").mapIndexed { i, col -> column(col, "$at.columns[$i]") },
                    c.get("maxRows")?.asInt()?.also { if (it < 1) throw LayoutException("$at.maxRows must be >= 1") },
                    c.path("summary").asBoolean(false),
                    c.path("showHeader").asBoolean(true),
                    c.path("zebra").asBoolean(true),
                    groupBy = c.text("groupBy"),
                    groupSummary = c.path("groupSummary").asBoolean(false),
                    autoSize = c.path("autoSize").asBoolean(false),
                )
                "timeSeries" -> TimeSeriesChartComponent(
                    title, dataset,
                    series = c.path("series").map { it.asText() },
                    x = c.text("x"),
                    y = c.path("y").map { it.asText() },
                    height = height(c, at),
                    yAxis = yAxis(c.path("yAxis")),
                    timePattern = c.text("timePattern"),
                )
                "bar" -> BarChartComponent(
                    title, dataset,
                    category = c.text("category") ?: throw LayoutException("$at.category is required for a bar chart"),
                    values = c.path("values").map { it.asText() }.ifEmpty { throw LayoutException("$at.values needs at least one column") },
                    height = height(c, at),
                    yAxis = yAxis(c.path("yAxis")),
                    categoryFormat = format(c.path("categoryFormat"), "$at.categoryFormat"),
                )
                "pie" -> PieChartComponent(
                    title, dataset,
                    category = c.text("category") ?: throw LayoutException("$at.category is required for a pie chart"),
                    value = c.text("value") ?: throw LayoutException("$at.value is required for a pie chart"),
                    height = height(c, at),
                    categoryFormat = format(c.path("categoryFormat"), "$at.categoryFormat"),
                    showPercent = c.path("showPercent").asBoolean(true),
                )
                "xy" -> XyChartComponent(
                    title, dataset,
                    x = c.text("x") ?: throw LayoutException("$at.x is required for an xy chart"),
                    y = c.path("y").map { it.asText() }.ifEmpty { throw LayoutException("$at.y needs at least one column") },
                    height = height(c, at),
                    xAxis = yAxis(c.path("xAxis")),
                    yAxis = yAxis(c.path("yAxis")),
                    connect = c.path("connect").asBoolean(false),
                )
                else -> throw LayoutException("$at.type must be table, timeSeries, bar, pie, xy, image, or text, not '$type'")
            }
        }

        private const val MAX_IMAGE_SRC = 4_000_000

        private fun image(c: JsonNode, title: String?, at: String): ImageComponent {
            val src = c.text("src") ?: throw LayoutException("$at.src is required")
            if (src.length > MAX_IMAGE_SRC) throw LayoutException("$at.src is too long")
            val key = Regex("^@[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?@$").matches(src)
            if (!key && !src.startsWith("data:image/")) throw LayoutException("$at.src must be a data:image/png or data:image/jpeg URI or a data key such as @Logo@")
            fun size(name: String): Float? = c.get(name)?.takeIf { !it.isNull }?.let {
                if (!it.isNumber || it.asDouble() < 8.0 || it.asDouble() > 2000.0) throw LayoutException("$at.$name must be 8..2000 points")
                it.asDouble().toFloat()
            }
            val align = c.text("align")?.let {
                runCatching { Align.valueOf(it.uppercase()) }.getOrElse { throw LayoutException("$at.align must be left, right or center") }
            } ?: Align.LEFT
            return ImageComponent(title, src, size("width"), size("height"), align, c.text("alt"))
        }

        private fun column(c: JsonNode, at: String): TableColumn {
            val field = (if (c.isTextual) c.asText() else c.text("field")) ?: throw LayoutException("$at.field is required")
            if (c.isTextual) return TableColumn(field, null, null, CellFormat(), null, 1f)
            val width = c.path("width").asDouble(1.0).toFloat()
            if (width <= 0f) throw LayoutException("$at.width must be > 0")
            return TableColumn(
                field = field,
                label = c.text("label"),
                unit = c.text("unit"),
                format = format(c.path("format"), "$at.format"),
                align = c.text("align")?.let {
                    runCatching { Align.valueOf(it.uppercase()) }.getOrElse { throw LayoutException("$at.align must be left, right or center") }
                },
                width = width,
            )
        }

        private fun format(f: JsonNode, at: String): CellFormat {
            if (!f.isObject) return CellFormat()
            val kind = f.text("type")?.let {
                runCatching { FormatKind.valueOf(it.uppercase()) }.getOrElse { throw LayoutException("$at.type must be auto, text, number, timestamp or boolean") }
            } ?: FormatKind.AUTO
            val decimals = f.get("decimals")?.asInt()?.also { if (it !in 0..10) throw LayoutException("$at.decimals must be 0..10") }
            return CellFormat(kind, decimals, f.path("grouping").asBoolean(false), f.text("pattern"), f.text("nullText") ?: "")
        }

        private fun height(c: JsonNode, at: String): Float {
            val h = c.path("height").asDouble(DEFAULT_CHART_HEIGHT.toDouble()).toFloat()
            if (h < 80f || h > 600f) throw LayoutException("$at.height must be 80..600 points")
            return h
        }

        private fun yAxis(y: JsonNode) = YAxis(
            min = y.get("min")?.takeIf { it.isNumber }?.asDouble(),
            max = y.get("max")?.takeIf { it.isNumber }?.asDouble(),
            label = y.text("label"),
            decimals = y.get("decimals")?.asInt(),
            minKey = y.text("min"),
            maxKey = y.text("max"),
        )

        private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()
    }
}
