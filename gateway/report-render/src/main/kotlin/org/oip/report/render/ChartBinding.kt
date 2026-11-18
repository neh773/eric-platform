package org.oip.report.render

/**
 * Data-key binding of chart settings (FEATURES REP-059). A chart's dataset, column names, series names, title and
 * axis label may contain `@Name@` keys, and an axis `min` or `max` may be one (`"@Limit@"`). They are filled from
 * the same values as report text (parameters, first-row columns, built-ins), so one layout can draw a different
 * data source or column per run. Mirrored by web/components/src/report/chartBinding.ts.
 */
internal object ChartBinding {
    class Result(val component: LayoutComponent, val problems: List<String>)

    fun bind(c: LayoutComponent, fill: (String) -> String): Result {
        val problems = ArrayList<String>()
        fun axis(a: YAxis, name: String): YAxis {
            fun number(key: String?, fixed: Double?, which: String): Double? {
                if (key == null) return fixed
                val text = fill(key).trim()
                return text.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: null.also { problems += "$name.$which '$key' is not a number (${if (text == key) "unknown key" else "got '$text'"}); using automatic" }
            }
            return a.copy(
                label = a.label?.let(fill),
                min = number(a.minKey, a.min, "min"),
                max = number(a.maxKey, a.max, "max"),
            )
        }
        val bound: LayoutComponent = when (c) {
            is TableComponent, is TextComponent, is ImageComponent -> c
            is TimeSeriesChartComponent -> c.copy(
                title = c.title?.let(fill), dataset = fill(c.dataset), series = c.series.map(fill), x = c.x?.let(fill),
                y = c.y.map(fill), yAxis = axis(c.yAxis, "yAxis"),
            )
            is BarChartComponent -> c.copy(
                title = c.title?.let(fill), dataset = fill(c.dataset), category = fill(c.category), values = c.values.map(fill),
                yAxis = axis(c.yAxis, "yAxis"),
            )
            is PieChartComponent -> c.copy(title = c.title?.let(fill), dataset = fill(c.dataset), category = fill(c.category), value = fill(c.value))
            is XyChartComponent -> c.copy(
                title = c.title?.let(fill), dataset = fill(c.dataset), x = fill(c.x), y = c.y.map(fill),
                xAxis = axis(c.xAxis, "xAxis"), yAxis = axis(c.yAxis, "yAxis"),
            )
        }
        return Result(bound, problems)
    }
}
