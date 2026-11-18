package org.oip.scripting

import com.fasterxml.jackson.databind.ObjectMapper
import org.graalvm.polyglot.HostAccess
import org.oip.sfc.ChartScriptHost
import org.oip.sfc.ChartScriptScope

/**
 * SFC action and chart scripts on the gateway GraalPy runtime.
 *
 * `chart.name` reads and writes the chart scope. Assigning a name that was not defined creates it.
 * A script that fails is thrown back to the chart, which aborts when the step has no error handler.
 *
 */
class SfcChartScripts(private val scripts: ScriptService) : ChartScriptHost {
    override fun execute(source: String, scope: ChartScriptScope): Boolean {
        val result = scripts.runChart(source, scope.variables, scope.chartPath)
        if (!result.ok) throw RuntimeException(result.error ?: "script failed")
        return true
    }
}

/** JSON in and out so the guest never holds a host collection after the context closes. */
internal class ChartVars(private val variables: MutableMap<String, Any?>) {
    @HostAccess.Export
    fun has(name: String): Boolean = variables.containsKey(name)

    @HostAccess.Export
    fun get(name: String): String = json.writeValueAsString(variables[name])

    @HostAccess.Export
    fun set(name: String, encoded: String) {
        variables[name] = json.readValue(encoded, Any::class.java)
    }

    private companion object {
        val json = ObjectMapper()
    }
}
