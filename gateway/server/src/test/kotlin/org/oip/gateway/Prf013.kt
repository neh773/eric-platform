package org.oip.gateway

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * PRF-013 (ADR 0010): a tracked result more than 15% worse than its accepted baseline fails; exactly
 * 15% worse does not. A baseline applies only on the hardware class it was measured on. Each
 * scenario's `result` in test-infra/perf/perf-requirements.json holds the primary baseline
 * (`result.hardware`, `result.baseline`) and optional `result.runnerBaselines` for other classes.
 *
 * The class of this run is `OIP_PERF_HARDWARE` when set (for example S8, or ci for the agent
 * container that measured PRF-007's primary baseline), otherwise `github-runner` under GitHub
 * Actions, otherwise none. With no baseline for that class the comparison is printed, not asserted.
 * GitHub's hosted runners are not one class: PRF-007 measured 0.0120 to 0.0733 on them, so none of
 * them has a baseline until CI runs on a pinned machine.
 */
internal object Prf013 {
    const val REGRESSION_LIMIT_PERCENT = 15.0

    data class Baseline(val hardware: String, val value: Double, val lowerIsBetter: Boolean)

    fun hardwareClass(): String? =
        System.getenv("OIP_PERF_HARDWARE")?.takeIf { it.isNotBlank() }
            ?: if (System.getenv("GITHUB_ACTIONS") == "true") "github-runner" else null

    /** Every accepted baseline of [scenario], primary first. */
    fun baselines(scenario: String): List<Baseline> {
        val result = requirements()["scenarios"].firstOrNull { it["id"]?.asText() == scenario }?.get("result")
            ?: error("$scenario has no result in perf-requirements.json")
        val lower = result["lowerIsBetter"].asBoolean()
        val out = mutableListOf(Baseline(result["hardware"].asText(), result["baseline"].asDouble(), lower))
        result["runnerBaselines"]?.forEach { entry ->
            out += Baseline(entry["hardware"].asText(), entry["baseline"].asDouble(), lower)
        }
        return out
    }

    /** The baseline for this run's hardware class, or null when none was measured on it. */
    fun applicable(scenario: String, hardware: String? = hardwareClass()): Baseline? =
        hardware?.let { h -> baselines(scenario).firstOrNull { it.hardware == h } }

    /** Positive when [measured] is worse than [baseline]. */
    fun worsePercent(baseline: Double, measured: Double, lowerIsBetter: Boolean): Double {
        val change = (measured - baseline) / kotlin.math.abs(baseline) * 100.0
        return if (lowerIsBetter) change else -change
    }

    fun regressionFails(baseline: Double, measured: Double, lowerIsBetter: Boolean): Boolean =
        worsePercent(baseline, measured, lowerIsBetter) > REGRESSION_LIMIT_PERCENT

    private fun requirements(): JsonNode {
        val given = System.getProperty("oip.perfRequirements")
        val file = if (!given.isNullOrBlank()) Path.of(given) else {
            var dir: Path? = Path.of("").toAbsolutePath()
            var found: Path? = null
            while (dir != null) {
                val candidate = dir.resolve("test-infra/perf/perf-requirements.json")
                if (Files.exists(candidate)) {
                    found = candidate
                    break
                }
                dir = dir.parent
            }
            found ?: error("perf-requirements.json not found")
        }
        return ObjectMapper().readTree(file.toFile())
    }
}
