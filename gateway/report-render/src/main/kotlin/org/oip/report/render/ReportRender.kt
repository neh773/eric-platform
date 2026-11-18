package org.oip.report.render

import org.oip.report.render.model.RenderInput
import org.oip.reporting.ReportDefinition
import org.oip.reporting.ReportResult

/*
 * Report rendering (M5.3): a report's layout over one run's data, as a paginated PDF with tables and charts.
 * Behaviour is our own design; the docs below were read for the feature scope only.
 */

/**
 * Entry point for M5.3 report rendering: `render(definition, result) -> PDF bytes`.
 *
 * [definition] supplies the layout (`spec.layout`) and title; when it has no layout, the result's verbatim copy of
 * the layout is used, and with neither the report renders as a header plus one table per dataset.
 * Throws [LayoutException] when the layout does not match the layoutVersion 1 schema.
 */
fun render(definition: ReportDefinition, result: ReportResult): ByteArray {
    val (def, res) = RenderInput.from(definition, result)
    return PdfReportRenderer.render(def, res)
}

/** Throws [LayoutException] when [definition]'s layout would not render; lets callers refuse before running. */
fun validateLayout(definition: ReportDefinition) {
    ReportLayout.parse(RenderInput.from(definition).layout)
}
