package org.oip.report.render.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.reporting.CONTRACT_VERSION

/*
 * The renderer's read-only view of a report run (C-9 contract, contractVersion 1; field names as in
 * `org.oip.reporting.Contract.kt`). The public entry points (`render`, `PdfArchiveSink`) take the real
 * `org.oip.reporting` classes and convert them here via their contract JSON (`toMap()`), so the renderer reads
 * exactly what the REST API returns, with cell values as JSON nodes. Tests also build this model straight from
 * contract JSON fixtures. Only the fields rendering needs are modelled; unknown fields are ignored.
 */

/** The parts of a Report definition `spec` that rendering reads. `layout` is opaque to the backend. */
internal data class ReportDefinition(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val layout: JsonNode? = null,
)

internal data class ReportRef(val id: String, val name: String, val title: String, val version: Long)

/** A resolved parameter value; `value` is the raw JSON (timeRange is `{from, to}` in epoch millis). */
internal data class ResolvedParameter(val name: String, val type: String, val value: JsonNode?)

internal data class Column(val name: String, val type: String, val unit: String? = null)

/** `points` are `[timestamp, value]`; value is null for an empty aggregate window or a non-numeric sample. */
internal data class Series(val tagPath: String, val label: String, val unit: String?, val points: List<Pair<Long, Double?>>)

internal data class Dataset(
    val name: String,
    val sourceType: String,
    val columns: List<Column>,
    val rows: List<List<JsonNode?>>,
    val truncated: Boolean = false,
    val series: List<Series> = emptyList(),
    val excludedTags: List<String> = emptyList(),
) {
    fun columnIndex(name: String): Int = columns.indexOfFirst { it.name == name }
}

internal data class RunInfo(
    val runId: String,
    val trigger: String,
    val user: String,
    val startedAt: Long,
    val durationMillis: Long,
    val status: String,
    val warnings: List<String> = emptyList(),
)

internal data class ReportResult(
    val contractVersion: Int,
    val report: ReportRef,
    val parameters: List<ResolvedParameter>,
    val generatedAt: Long,
    val datasets: List<Dataset>,
    val run: RunInfo?,
    val layout: JsonNode?,
) {
    fun dataset(name: String): Dataset? = datasets.firstOrNull { it.name == name }
}

/** Builds the model from the real contract classes or from contract JSON, tolerant of unknown fields. */
internal object RenderInput {
    val mapper: ObjectMapper = ObjectMapper()

    /** The renderer's view of a real definition and result. */
    fun from(definition: org.oip.reporting.ReportDefinition, result: org.oip.reporting.ReportResult): Pair<ReportDefinition, ReportResult> =
        from(definition) to parseResult(mapper.valueToTree<JsonNode>(result.toMap()))

    fun from(definition: org.oip.reporting.ReportDefinition): ReportDefinition = ReportDefinition(
        name = definition.name,
        title = definition.title,
        description = definition.description,
        layout = definition.layout?.let { mapper.valueToTree<JsonNode>(it) }?.takeIf { it.isObject },
    )

    fun parseResult(json: String): ReportResult = parseResult(mapper.readTree(json))

    fun parseResult(n: JsonNode): ReportResult {
        val version = n.path("contractVersion").asInt(0)
        require(version == CONTRACT_VERSION) { "unsupported contractVersion $version (renderer supports $CONTRACT_VERSION)" }
        val r = n.path("report")
        return ReportResult(
            contractVersion = version,
            report = ReportRef(r.path("id").asText(""), r.path("name").asText(""), r.path("title").asText(r.path("name").asText("")), r.path("version").asLong(0)),
            parameters = n.path("parameters").map { p ->
                ResolvedParameter(p.path("name").asText(), p.path("type").asText("string"), p.get("value")?.takeUnless { it.isNull })
            },
            generatedAt = n.path("generatedAt").asLong(0),
            datasets = n.path("datasets").map(::parseDataset),
            run = n.get("run")?.takeIf { it.isObject }?.let { run ->
                RunInfo(
                    run.path("runId").asText(""), run.path("trigger").asText(""), run.path("user").asText(""),
                    run.path("startedAt").asLong(0), run.path("durationMillis").asLong(0), run.path("status").asText(""),
                    run.path("warnings").map { it.asText() },
                )
            },
            layout = n.get("layout")?.takeIf { it.isObject },
        )
    }

    /** Accepts either a full config resource envelope (`{kind, metadata, spec}`) or a bare `spec` with a `name`. */
    fun parseDefinition(json: String): ReportDefinition = parseDefinition(mapper.readTree(json))

    fun parseDefinition(n: JsonNode): ReportDefinition {
        val spec = if (n.has("spec")) n.path("spec") else n
        val name = n.path("metadata").path("name").asText(null) ?: spec.path("name").asText("")
        return ReportDefinition(
            name = name,
            title = spec.path("title").asText(null),
            description = spec.path("description").asText(null),
            layout = spec.get("layout")?.takeIf { it.isObject },
        )
    }

    private fun parseDataset(d: JsonNode): Dataset {
        val columns = d.path("columns").map { Column(it.path("name").asText(), it.path("type").asText("string"), it.path("unit").asText(null)) }
        return Dataset(
            name = d.path("name").asText(),
            sourceType = d.path("sourceType").asText("namedQuery"),
            columns = columns,
            rows = d.path("rows").map { row -> row.map { v -> v.takeUnless { it.isNull } } },
            truncated = d.path("truncated").asBoolean(false),
            series = d.path("series").map { s ->
                val path = s.path("tagPath").asText()
                Series(
                    tagPath = path,
                    label = s.path("label").asText(path),
                    unit = s.path("unit").asText(null),
                    points = s.path("points").map { p -> p.path(0).asLong() to p.path(1).takeIf { it.isNumber }?.asDouble() },
                )
            },
            excludedTags = d.path("excludedTags").map { it.asText() },
        )
    }
}
