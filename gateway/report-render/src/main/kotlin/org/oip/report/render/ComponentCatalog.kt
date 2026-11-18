package org.oip.report.render

/*
 * Report component catalog: the component types a layout may hold, with the properties each one reads and the
 * values a new one starts with. The Designer's report workspace builds its palette and inspectors from this list
 * (`GET /api/v1/reports/components`), so a type added to [ReportLayout] shows up there once it is listed here.
 * Behaviour is our own design; doc source: FEATURES REP-013.
 */

/** One property of a component. [kind] tells an editor which control to draw. */
data class CatalogProperty(
    val name: String,
    val label: String,
    /** string, text, dataset, column, columns, number, boolean, enum, tableColumns, yAxis, format */
    val kind: String,
    val default: Any? = null,
    val options: List<String> = emptyList(),
    val help: String? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("name" to name, "label" to label, "kind" to kind).also { m ->
        if (default != null) m["default"] = default
        if (options.isNotEmpty()) m["options"] = options
        if (help != null) m["help"] = help
    }
}

data class CatalogComponent(
    val type: String,
    val label: String,
    val description: String,
    /** True when the component draws one dataset of the run. */
    val needsDataset: Boolean,
    /** A new component's own properties (what the layout stores). */
    val defaults: Map<String, Any?>,
    val properties: List<CatalogProperty>,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "type" to type,
        "label" to label,
        "description" to description,
        "needsDataset" to needsDataset,
        "defaults" to defaults,
        "properties" to properties.map { it.toMap() },
    )
}

object ComponentCatalog {
    private val title = CatalogProperty("title", "Title", "string", help = "Heading above the component. May use @Name@ data keys.")
    private val dataset = CatalogProperty("dataset", "Data source", "dataset", help = "The report data source this component draws.")
    private val height = CatalogProperty("height", "Height (pt)", "number", ReportLayout.DEFAULT_CHART_HEIGHT.toInt(), help = "80 to 600 points.")
    private val yAxis = CatalogProperty("yAxis", "Y axis", "yAxis", help = "min, max, label and decimals of the value axis.")

    val components: List<CatalogComponent> = listOf(
        CatalogComponent(
            "text", "Text block",
            "Free text. @Name@ is replaced by a report parameter, a data column of the first row, or a built-in (Date, Report, Page).",
            needsDataset = false,
            defaults = mapOf("type" to "text", "title" to "Note", "text" to "Report @Report@, page @Page@"),
            properties = listOf(
                title,
                CatalogProperty("text", "Text", "text", help = "Up to 8000 characters. Unknown @keys@ are left as written."),
            ),
        ),
        CatalogComponent(
            "table", "Table",
            "A data source as a header row, repeated detail rows and an optional totals row.",
            needsDataset = true,
            defaults = mapOf("type" to "table", "title" to "Table", "dataset" to "", "columns" to emptyList<Any>(), "summary" to false, "showHeader" to true, "zebra" to true),
            properties = listOf(
                title, dataset,
                CatalogProperty("columns", "Columns", "tableColumns", help = "Empty shows every column. Each column has a label, unit, alignment, relative width and format."),
                CatalogProperty("maxRows", "Maximum rows", "number", help = "Show only the first rows. Empty shows all."),
                CatalogProperty("showHeader", "Header row", "boolean", true),
                CatalogProperty("zebra", "Shaded detail rows", "boolean", true),
                CatalogProperty("summary", "Summary row", "boolean", false, help = "Sums numeric columns; the first text column reads Total."),
                CatalogProperty("groupBy", "Group by column", "column", help = "A heading row per distinct value, groups in order of first appearance."),
                CatalogProperty("groupSummary", "Subtotal per group", "boolean", false, help = "With a group column: sums numeric columns after each group."),
                CatalogProperty("autoSize", "Auto-size columns", "boolean", false, help = "Column widths follow the longest text instead of the width weights."),
            ),
        ),
        CatalogComponent(
            "bar", "Bar chart",
            "Values per category from one data source.",
            needsDataset = true,
            defaults = mapOf("type" to "bar", "title" to "Bar chart", "dataset" to "", "category" to "", "values" to emptyList<Any>(), "height" to ReportLayout.DEFAULT_CHART_HEIGHT.toInt()),
            properties = listOf(
                title, dataset,
                CatalogProperty("category", "Category column", "column"),
                CatalogProperty("values", "Value columns", "columns", help = "At least one numeric column."),
                height, yAxis,
            ),
        ),
        CatalogComponent(
            "timeSeries", "Time series chart",
            "Tag history series, or timestamp and number columns of another data source, over time.",
            needsDataset = true,
            defaults = mapOf("type" to "timeSeries", "title" to "Trend", "dataset" to "", "height" to ReportLayout.DEFAULT_CHART_HEIGHT.toInt()),
            properties = listOf(
                title, dataset,
                CatalogProperty("series", "Series", "columns", help = "Tag paths or labels of a tag history source. Empty draws all."),
                CatalogProperty("x", "Time column", "column", help = "For data sources that are not tag history."),
                CatalogProperty("y", "Value columns", "columns", help = "For data sources that are not tag history."),
                height, yAxis,
                CatalogProperty("timePattern", "Time pattern", "string", help = "For example HH:mm."),
            ),
        ),
        CatalogComponent(
            "pie", "Pie chart",
            "Share of a total per category from one data source. Rows with no positive value draw no slice.",
            needsDataset = true,
            defaults = mapOf("type" to "pie", "title" to "Pie chart", "dataset" to "", "category" to "", "value" to "", "height" to ReportLayout.DEFAULT_CHART_HEIGHT.toInt(), "showPercent" to true),
            properties = listOf(
                title, dataset,
                CatalogProperty("category", "Category column", "column"),
                CatalogProperty("value", "Value column", "column", help = "A numeric column."),
                CatalogProperty("showPercent", "Percent in legend", "boolean", true),
                height,
            ),
        ),
        CatalogComponent(
            "xy", "XY chart",
            "Numeric x against one or more numeric y columns, as markers or joined lines.",
            needsDataset = true,
            defaults = mapOf("type" to "xy", "title" to "XY chart", "dataset" to "", "x" to "", "y" to emptyList<Any>(), "connect" to false, "height" to ReportLayout.DEFAULT_CHART_HEIGHT.toInt()),
            properties = listOf(
                title, dataset,
                CatalogProperty("x", "X column", "column", help = "A numeric column."),
                CatalogProperty("y", "Y columns", "columns", help = "At least one numeric column."),
                CatalogProperty("connect", "Join points with lines", "boolean", false),
                height, yAxis,
                CatalogProperty("xAxis", "X axis", "yAxis", help = "min, max, label and decimals of the x axis."),
            ),
        ),
        CatalogComponent(
            "image", "Image",
            "A PNG or JPEG picture, embedded as a data URI or taken from a data key such as @Logo@.",
            needsDataset = false,
            defaults = mapOf("type" to "image", "src" to "", "align" to "left"),
            properties = listOf(
                title,
                CatalogProperty("src", "Picture", "text", help = "data:image/png;base64,... or a data key such as @Logo@ that holds one. At most 4 MB of text."),
                CatalogProperty("width", "Width (pt)", "number", help = "8 to 2000 points. With only one of width and height the other keeps the aspect ratio."),
                CatalogProperty("height", "Height (pt)", "number"),
                CatalogProperty("align", "Alignment", "enum", "left", listOf("left", "center", "right")),
                CatalogProperty("alt", "Alternative text", "string", help = "Read by screen readers in the preview."),
            ),
        ),
    )

    /** Page and header settings of the report object itself (REP-010). */
    val report: List<CatalogProperty> = listOf(
        CatalogProperty("page.size", "Page size", "enum", "A4", PageSize.entries.map { it.wire }),
        CatalogProperty("page.orientation", "Orientation", "enum", "portrait", listOf("portrait", "landscape")),
        CatalogProperty("page.margins", "Margins (pt)", "number", Margins.DEFAULT.toInt(), help = "${Margins.MIN.toInt()} to ${Margins.MAX.toInt()} points, per side."),
        CatalogProperty("timezone", "Time zone", "string", "UTC"),
        CatalogProperty("header.title", "Header title", "string"),
        CatalogProperty("header.subtitle", "Header subtitle", "string"),
        CatalogProperty("header.showParameters", "Show parameters", "boolean", true),
        CatalogProperty("header.showGeneratedAt", "Show generation time", "boolean", true),
    )

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "layoutVersion" to ReportLayout.LAYOUT_VERSION,
        "components" to components.map { it.toMap() },
        "report" to report.map { it.toMap() },
    )
}
