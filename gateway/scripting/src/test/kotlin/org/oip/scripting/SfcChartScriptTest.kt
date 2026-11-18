package org.oip.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.sfc.CHART_KIND
import org.oip.sfc.MemoryChartStateStore
import org.oip.sfc.SFC_MODULE
import org.oip.sfc.SfcEngine
import org.oip.tags.TagEngine
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * SFC Python step bodies run on the gateway GraalPy runtime.
 *
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class SfcChartScriptTest {
    @TempDir lateinit var tmp: Path

    @Test fun pythonStepChangesAChartVariable() {
        val view = runStep("chart.partNumber = chart.partNumber + 1")
        assertEquals("Stopped", view["state"])
        assertEquals(111, (view.vars()["partNumber"] as Number).toInt())
    }

    @Test fun pythonStepErrorAbortsTheChart() {
        val view = runStep("raise Exception('step failed')")
        assertEquals("Aborted", view["state"])
        assertTrue(view["abortCause"].toString().contains("step failed"), view["abortCause"].toString())
        assertEquals(110, (view.vars()["partNumber"] as Number).toInt())
    }

    private fun runStep(source: String): Map<String, Any?> {
        val clock = Clock.systemUTC()
        val tags = TagEngine(clock)
        val config = FileConfigStore(tmp.resolve("config-${System.nanoTime()}"), SchemaRegistry(), clock)
        val scripts = ScriptService(tags, null, clock)
        val engine = SfcEngine(
            clock,
            tags,
            config,
            MemoryChartStateStore(),
            inline = true,
            scripts = SfcChartScripts(scripts),
        )
        try {
            engine.attach()
            config.put(
                Scope.GATEWAY,
                Resource(
                    CHART_KIND,
                    Metadata("job", SFC_MODULE),
                    mapOf(
                        "variables" to listOf(mapOf("name" to "partNumber", "initial" to 110)),
                        "elements" to listOf(
                            mapOf("id" to "begin", "type" to "begin"),
                            mapOf(
                                "id" to "step",
                                "type" to "action",
                                "onStart" to listOf(mapOf("type" to "script", "source" to source)),
                            ),
                            mapOf("id" to "end", "type" to "end"),
                        ),
                        "links" to listOf(
                            mapOf("from" to "begin", "to" to "step"),
                            mapOf("from" to "step", "to" to "end"),
                        ),
                    ),
                ),
                "test",
            )
            val id = engine.startChart("job")
            return engine.instance(id)
        } finally {
            engine.close()
            scripts.close()
            config.close()
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.vars(): Map<String, Any?> = this["variables"] as Map<String, Any?>
