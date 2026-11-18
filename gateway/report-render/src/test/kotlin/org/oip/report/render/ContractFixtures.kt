package org.oip.report.render

import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.reporting.Column
import org.oip.reporting.ColumnType
import org.oip.reporting.Dataset
import org.oip.reporting.ParameterType
import org.oip.reporting.ReportDefinition
import org.oip.reporting.ReportDefinitions
import org.oip.reporting.ReportRef
import org.oip.reporting.ReportResult
import org.oip.reporting.ResolvedParameter
import org.oip.reporting.RunInfo
import org.oip.reporting.RunStatus
import org.oip.reporting.RunTrigger
import org.oip.reporting.Series
import org.oip.reporting.SourceType

/** Builds the real C-9a classes from the contract JSON fixtures, the way the backend would produce them. */
@Suppress("UNCHECKED_CAST")
object ContractFixtures {
    private val mapper = ObjectMapper()

    private fun map(json: String): Map<String, Any?> = mapper.readValue(json, Map::class.java) as Map<String, Any?>

    /** A `reporting/Report` resource envelope, parsed and checked by the backend's own definition parser. */
    fun definition(json: String): ReportDefinition {
        val envelope = map(json)
        val name = (envelope["metadata"] as Map<String, Any?>)["name"] as String
        return ReportDefinitions.parse(name, 1, envelope["spec"] as Map<String, Any?>)
    }

    fun result(json: String): ReportResult {
        val m = map(json)
        val r = m["report"] as Map<String, Any?>
        val run = m["run"] as Map<String, Any?>
        return ReportResult(
            report = ReportRef(r["id"] as String, r["name"] as String, r["title"] as String, (r["version"] as Number).toLong()),
            parameters = (m["parameters"] as List<Map<String, Any?>>).map {
                ResolvedParameter(it["name"] as String, ParameterType.fromWire(it["type"] as String), it["value"])
            },
            generatedAt = (m["generatedAt"] as Number).toLong(),
            datasets = (m["datasets"] as List<Map<String, Any?>>).map { d ->
                Dataset(
                    name = d["name"] as String,
                    sourceType = SourceType.entries.first { it.wire == d["sourceType"] },
                    columns = (d["columns"] as List<Map<String, Any?>>).map {
                        Column(it["name"] as String, ColumnType.fromWire(it["type"] as String), it["unit"] as String?)
                    },
                    rows = d["rows"] as List<List<Any?>>,
                    truncated = d["truncated"] as Boolean? ?: false,
                    series = (d["series"] as List<Map<String, Any?>>? ?: emptyList()).map {
                        Series(it["tagPath"] as String, it["label"] as String, it["unit"] as String?, it["points"] as List<List<Any?>>)
                    },
                    excludedTags = d["excludedTags"] as List<String>? ?: emptyList(),
                )
            },
            run = RunInfo(
                runId = run["runId"] as String,
                trigger = RunTrigger.entries.first { it.wire == run["trigger"] },
                user = run["user"] as String,
                startedAt = (run["startedAt"] as Number).toLong(),
                durationMillis = (run["durationMillis"] as Number).toLong(),
                status = RunStatus.fromWire(run["status"] as String),
                warnings = run["warnings"] as List<String>? ?: emptyList(),
            ),
            layout = m["layout"] as Map<String, Any?>?,
            contractVersion = (m["contractVersion"] as Number).toInt(),
        )
    }
}
