package org.oip.sfc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Chart execution against a fake clock: flow, selection, parallel, enclosing, cancel, pause, and redundancy.
 */
class SfcEngineTest {
    @TempDir lateinit var dir: Path

    @Test fun linearChartReachesEndAndRecordsTheKeyParam() {
        val fx = fixture()
        fx.chart("fill", spec(
            parameters = listOf(mapOf("name" to "target", "default" to 1, "key" to true)),
            variables = listOf(mapOf("name" to "count", "initial" to 0)),
            elements = listOf(
                el("begin", "begin"),
                el("fill", "action", "onStart" to listOf(expr("count", "{count} + 1"))),
                el("done", "transition", "expression" to "{count} >= {target}"),
                el("end", "end"),
            ),
            links = listOf(link("begin", "fill"), link("fill", "done"), link("done", "end")),
        ))
        val id = fx.engine.startChart("fill", mapOf("target" to 1))
        val view = fx.engine.instance(id)
        assertEquals("Stopped", view["state"])
        assertEquals(11, view["stateCode"])
        assertEquals(1L, (view["variables"] as Map<*, *>)["count"])
        assertEquals("1", view["key"])
        assertEquals(emptyList<String>(), view["activeSteps"])
        fx.close()
    }

    @Test fun falseTransitionBlocksUntilTheClockLetsTheTimerOpenIt() {
        val fx = fixture()
        fx.chart("wait", spec(
            variables = listOf(mapOf("name" to "ready", "initial" to false), mapOf("name" to "n", "initial" to 0)),
            elements = listOf(
                el("begin", "begin"),
                el("step", "action", "timerPeriodMillis" to 1000, "timer" to listOf(expr("ready", "true"), expr("n", "{n} + 1"))),
                el("go", "transition", "expression" to "{ready}"),
                el("end", "end"),
            ),
            links = listOf(link("begin", "step"), link("step", "go"), link("go", "end")),
        ))
        val id = fx.engine.startChart("wait")
        assertEquals("Running", fx.engine.instance(id)["state"])
        assertEquals(listOf("step"), fx.engine.instance(id)["activeSteps"])
        assertEquals(0, fx.engine.instance(id).vars()["n"])
        fx.clock.advance(999)
        fx.engine.tick()
        assertEquals("Running", fx.engine.instance(id)["state"])
        fx.clock.advance(1)
        fx.engine.tick()
        assertEquals("Stopped", fx.engine.instance(id)["state"])
        assertEquals(1L, fx.engine.instance(id).vars()["n"])
        fx.close()
    }

    @Test fun selectionTakesTheLeftmostTrueTransition() {
        val fx = fixture()
        fx.chart("pick", spec(
            variables = listOf(mapOf("name" to "side", "initial" to "")),
            elements = listOf(
                el("begin", "begin"),
                el("s1", "action"),
                el("left", "transition", "expression" to "true"),
                el("right", "transition", "expression" to "true"),
                el("takeLeft", "action", "onStart" to listOf(expr("side", "\"left\""))),
                el("takeRight", "action", "onStart" to listOf(expr("side", "\"right\""))),
                el("end", "end"),
            ),
            links = listOf(
                link("begin", "s1"),
                link("s1", "left"),
                link("s1", "right"),
                link("left", "takeLeft"),
                link("right", "takeRight"),
                link("takeLeft", "end"),
                link("takeRight", "end"),
            ),
        ))
        val id = fx.engine.startChart("pick")
        assertEquals("Stopped", fx.engine.instance(id)["state"])
        assertEquals("left", fx.engine.instance(id).vars()["side"])
        fx.close()
    }

    @Test fun selectionWaitsAndThenTakesTheRightBranch() {
        val fx = fixture()
        fx.chart("pick", spec(
            variables = listOf(mapOf("name" to "choice", "initial" to 0), mapOf("name" to "side", "initial" to "")),
            elements = listOf(
                el("begin", "begin"),
                el("s1", "action", "timerPeriodMillis" to 500, "timer" to listOf(expr("choice", "2"))),
                el("toLeft", "transition", "expression" to "{choice} = 1"),
                el("toRight", "transition", "expression" to "{choice} = 2"),
                el("takeLeft", "action", "onStart" to listOf(expr("side", "\"left\""))),
                el("takeRight", "action", "onStart" to listOf(expr("side", "\"right\""))),
                el("end", "end"),
            ),
            links = listOf(
                link("begin", "s1"), link("s1", "toLeft"), link("s1", "toRight"),
                link("toLeft", "takeLeft"), link("toRight", "takeRight"),
                link("takeLeft", "end"), link("takeRight", "end"),
            ),
        ))
        val id = fx.engine.startChart("pick")
        assertEquals("Running", fx.engine.instance(id)["state"])
        assertEquals("", fx.engine.instance(id).vars()["side"])
        fx.clock.advance(500)
        fx.engine.tick()
        assertEquals("Stopped", fx.engine.instance(id)["state"])
        assertEquals("right", fx.engine.instance(id).vars()["side"])
        fx.close()
    }

    @Test fun parallelJoinsAfterTheSlowerBranch() {
        val fx = fixture()
        fx.chart("fork", spec(
            variables = listOf(mapOf("name" to "a", "initial" to 0), mapOf("name" to "b", "initial" to 0)),
            elements = listOf(
                el("begin", "begin"),
                el("fork", "parallel", "branches" to listOf(
                    mapOf(
                        "elements" to listOf(el("fast", "action", "onStart" to listOf(expr("a", "1")))),
                        "links" to emptyList<Any>(),
                    ),
                    mapOf(
                        "elements" to listOf(
                            el("slow", "action", "timerPeriodMillis" to 500, "timer" to listOf(expr("b", "1"))),
                            el("gate", "transition", "expression" to "{b} = 1"),
                            el("done", "action"),
                        ),
                        "links" to listOf(link("slow", "gate"), link("gate", "done")),
                    ),
                )),
                el("end", "end"),
            ),
            links = listOf(link("begin", "fork"), link("fork", "end")),
        ))
        val id = fx.engine.startChart("fork")
        assertEquals("Running", fx.engine.instance(id)["state"])
        assertEquals(1L, fx.engine.instance(id).vars()["a"])
        assertEquals(0, fx.engine.instance(id).vars()["b"])
        assertTrue((fx.engine.instance(id)["activeSteps"] as List<*>).contains("slow"))
        fx.clock.advance(500)
        fx.engine.tick()
        assertEquals("Stopped", fx.engine.instance(id)["state"])
        assertEquals(1L, fx.engine.instance(id).vars()["b"])
        fx.close()
    }

    @Test fun enclosingStepBlocksUntilTheChildStopsAndCopiesReturns() {
        val fx = fixture()
        fx.chart("child", spec(
            variables = listOf(mapOf("name" to "result", "initial" to 0)),
            elements = listOf(
                el("begin", "begin"),
                el("work", "action", "onStart" to listOf(expr("result", "{n} + 7"))),
                el("end", "end"),
            ),
            links = listOf(link("begin", "work"), link("work", "end")),
            parameters = listOf(mapOf("name" to "n", "default" to 0)),
        ))
        fx.chart("parent", spec(
            variables = listOf(mapOf("name" to "out", "initial" to 0)),
            elements = listOf(
                el("begin", "begin"),
                el("call", "enclosing", "chart" to "child", "execution" to "block",
                    "parameters" to listOf(mapOf("name" to "n", "expression" to "3")),
                    "returns" to listOf(mapOf("from" to "result", "to" to "out"))),
                el("end", "end"),
            ),
            links = listOf(link("begin", "call"), link("call", "end")),
        ))
        val id = fx.engine.startChart("parent")
        assertEquals("Stopped", fx.engine.instance(id)["state"])
        assertEquals(10L, fx.engine.instance(id).vars()["out"])
        val child = fx.engine.instances().single { it["chart"] == "child" }
        assertEquals("Stopped", child["state"])
        assertEquals(id, child["parentId"])
        fx.close()
    }

    @Test fun cancelFromABlockedChartRunsOnCancelAndDoesNotReachEnd() {
        val fx = fixture()
        fx.chart("held", spec(
            variables = listOf(mapOf("name" to "hold", "initial" to false), mapOf("name" to "flag", "initial" to false)),
            onCancel = listOf(expr("flag", "true")),
            elements = listOf(
                el("begin", "begin"),
                el("step", "action"),
                el("go", "transition", "expression" to "{hold}"),
                el("end", "end"),
            ),
            links = listOf(link("begin", "step"), link("step", "go"), link("go", "end")),
        ))
        val id = fx.engine.startChart("held")
        assertEquals("Running", fx.engine.instance(id)["state"])
        fx.engine.cancel(id)
        val view = fx.engine.instance(id)
        assertEquals("Canceled", view["state"])
        assertEquals(2, view["stateCode"])
        assertEquals(true, view.vars()["flag"])
        assertEquals(emptyList<String>(), view["activeSteps"])
        fx.close()
    }

    @Test fun pauseStopsTheTimerUntilResume() {
        val fx = fixture()
        fx.chart("beat", spec(
            variables = listOf(mapOf("name" to "n", "initial" to 0)),
            elements = listOf(
                el("begin", "begin"),
                el("step", "action", "timerPeriodMillis" to 1000, "timer" to listOf(expr("n", "{n} + 1"))),
                el("done", "transition", "expression" to "{n} >= 2"),
                el("end", "end"),
            ),
            links = listOf(link("begin", "step"), link("step", "done"), link("done", "end")),
        ))
        val id = fx.engine.startChart("beat")
        fx.clock.advance(1000)
        fx.engine.tick()
        assertEquals(1L, fx.engine.instance(id).vars()["n"])
        fx.engine.pause(id)
        assertEquals("Paused", fx.engine.instance(id)["state"])
        assertEquals(6, fx.engine.instance(id)["stateCode"])
        fx.clock.advance(5000)
        fx.engine.tick()
        assertEquals(1L, fx.engine.instance(id).vars()["n"])
        fx.engine.resume(id)
        assertEquals("Running", fx.engine.instance(id)["state"])
        fx.clock.advance(1000)
        fx.engine.tick()
        assertEquals("Stopped", fx.engine.instance(id)["state"])
        assertEquals(2L, fx.engine.instance(id).vars()["n"])
        fx.close()
    }

    @Test fun jumpLoopsUntilTheCounterOpensTheEndTransition() {
        val fx = fixture()
        fx.chart("loop", spec(
            variables = listOf(mapOf("name" to "count", "initial" to 0)),
            elements = listOf(
                el("begin", "begin"),
                el("again", "anchor"),
                el("step", "action", "onStart" to listOf(expr("count", "{count} + 1"))),
                el("finish", "transition", "expression" to "{count} >= 2"),
                el("repeat", "transition", "expression" to "{count} < 2"),
                el("end", "end"),
            ),
            links = listOf(
                link("begin", "again"), link("again", "step"),
                link("step", "finish"), link("step", "repeat"),
                link("finish", "end"), link("repeat", "again"),
            ),
        ))
        val id = fx.engine.startChart("loop")
        assertEquals("Stopped", fx.engine.instance(id)["state"])
        assertEquals(2L, fx.engine.instance(id).vars()["count"])
        fx.close()
    }

    @Test fun assertionAbortSetsThePublicAbortedCode() {
        val fx = fixture()
        fx.chart("check", spec(
            parameters = listOf(mapOf("name" to "target", "default" to 0)),
            onAbort = listOf(expr("seen", "true")),
            variables = listOf(mapOf("name" to "seen", "initial" to false)),
            elements = listOf(
                el("begin", "begin"),
                el("gate", "assertion", "onFail" to "abort", "assertions" to listOf(
                    mapOf("parameter" to "target", "operator" to ">=", "value" to 0),
                )),
                el("end", "end"),
            ),
            links = listOf(link("begin", "gate"), link("gate", "end")),
        ))
        val id = fx.engine.startChart("check", mapOf("target" to -1))
        val view = fx.engine.instance(id)
        assertEquals("Aborted", view["state"])
        assertEquals(0, view["stateCode"])
        assertEquals(true, view.vars()["seen"])
        assertTrue((view["abortCause"] as String).contains("assertion"))
        fx.close()
    }

    @Test fun redundancySnapshotResumesOnThePeerAfterTheTagOpens() {
        val state = MemoryChartStateStore()
        val clock = ManualClock(Instant.parse("2026-01-01T00:00:00Z"))
        val tags = TagEngine(clock)
        tags.configure(TagConfig(path = "Go", type = TagType.MEMORY, dataType = DataType.BOOLEAN, value = false, valueSet = true))
        val config = FileConfigStore(dir.resolve("config"), SchemaRegistry(), clock)
        val first = engine(clock, tags, config, state)
        putChart(config, "wait", spec(
            redundancySync = true,
            persistState = true,
            elements = listOf(
                el("begin", "begin"),
                el("step", "action"),
                el("go", "transition", "expression" to "{[default]Go}"),
                el("end", "end"),
            ),
            links = listOf(link("begin", "step"), link("step", "go"), link("go", "end")),
        ))
        val id = first.startChart("wait")
        assertEquals("Running", first.instance(id)["state"])
        first.park()
        assertEquals("RedundantInactive", first.instance(id)["state"])
        assertEquals(14, first.instance(id)["stateCode"])
        first.close()

        val second = engine(clock, tags, config, state)
        second.start()
        assertEquals("RedundantInactive", second.instance(id)["state"])
        second.activate()
        assertEquals("Running", second.instance(id)["state"])
        tags.write("[default]Go", true)
        second.tick()
        assertEquals("Stopped", second.instance(id)["state"])
        second.close()
        config.close()
    }

    @Test fun scriptHostCanCancelOnFailoverAndANoopHostSkipsPython() {
        val seen = mutableListOf<String>()
        val fx = fixture(scripts = ChartScriptHost { source, scope ->
            seen += source
            if (source == "cancel-me") scope.restart.cancel = true
            source == "cancel-me"
        })
        fx.chart("plain", spec(
            variables = listOf(mapOf("name" to "y", "initial" to 0)),
            elements = listOf(
                el("begin", "begin"),
                el("step", "action", "onStart" to listOf(
                    mapOf("type" to "script", "source" to "chart.x = 1"),
                    expr("y", "2"),
                )),
                el("end", "end"),
            ),
            links = listOf(link("begin", "step"), link("step", "end")),
        ))
        val plain = fx.engine.startChart("plain")
        assertEquals("Stopped", fx.engine.instance(plain)["state"])
        assertEquals(2L, fx.engine.instance(plain).vars()["y"])
        assertNull(fx.engine.instance(plain).vars()["x"])
        assertTrue(seen.contains("chart.x = 1"))

        fx.chart("peer", spec(
            redundancySync = true,
            failover = "continue",
            onRedundantFailover = listOf(mapOf("type" to "script", "source" to "cancel-me")),
            elements = listOf(
                el("begin", "begin"),
                el("step", "action"),
                el("go", "transition", "expression" to "false"),
                el("end", "end"),
            ),
            links = listOf(link("begin", "step"), link("step", "go"), link("go", "end")),
        ))
        val id = fx.engine.startChart("peer")
        fx.engine.park()
        fx.engine.activate()
        assertEquals("Canceled", fx.engine.instance(id)["state"])
        fx.close()
    }

    @Test fun chartWorkQueuedOnATagThreadDoesNotRunThere() {
        val seen = mutableListOf<String>()
        val fx = fixture(
            scripts = ChartScriptHost { _, _ -> seen += Thread.currentThread().name; false },
            deferWhen = { Thread.currentThread().name == "tag-writer" },
        )
        fx.chart("job", spec(
            elements = listOf(
                el("begin", "begin"),
                el("step", "action", "onStart" to listOf(mapOf("type" to "script", "source" to "run"))),
                el("end", "end"),
            ),
            links = listOf(link("begin", "step"), link("step", "end")),
        ))
        val worker = Thread({ fx.engine.startChart("job") }, "tag-writer")
        worker.start()
        worker.join()
        assertTrue(seen.isEmpty())
        assertTrue(fx.engine.instances().isEmpty())
        fx.engine.drain()
        assertEquals(1, seen.size)
        assertNotEquals("tag-writer", seen.single())
        assertEquals("Stopped", fx.engine.instances().single()["state"])
        fx.close()
    }

    @Test fun runAlwaysStartsOnceAndDisabledRejectsStart() {
        val fx = fixture()
        fx.chart("always", spec(
            executionMode = "runAlways",
            elements = listOf(el("begin", "begin"), el("end", "end")),
            links = listOf(link("begin", "end")),
        ))
        fx.chart("off", spec(
            executionMode = "disabled",
            elements = listOf(el("begin", "begin"), el("end", "end")),
            links = listOf(link("begin", "end")),
        ))
        fx.engine.start()
        assertEquals(1, fx.engine.instances().count { it["chart"] == "always" })
        assertEquals("Stopped", fx.engine.instances().single { it["chart"] == "always" }["state"])
        fx.engine.tick()
        assertEquals(1, fx.engine.instances().count { it["chart"] == "always" })
        assertThrows(ChartRejected::class.java) { fx.engine.startChart("always") }
        assertThrows(ChartRejected::class.java) { fx.engine.startChart("off") }
        val first = fx.engine.startChart("fill-gap".also {
            fx.chart(it, spec(
                elements = listOf(el("begin", "begin"), el("end", "end")),
                links = listOf(link("begin", "end")),
            ))
        })
        val second = fx.engine.startChart("fill-gap")
        assertNotEquals(first, second)
        fx.close()
    }

    @Test fun flowIntoTwoActionsIsRejected() {
        val fx = fixture()
        fx.chart("bad", spec(
            elements = listOf(
                el("begin", "begin"),
                el("s1", "action"),
                el("a", "action"),
                el("b", "action"),
                el("end", "end"),
            ),
            links = listOf(link("begin", "s1"), link("s1", "a"), link("s1", "b"), link("a", "end"), link("b", "end")),
        ))
        val rejected = assertThrows(ChartRejected::class.java) { fx.engine.startChart("bad") }
        assertTrue(rejected.message!!.contains("multiple actions"))
        fx.close()
    }

    @Test fun persistedChartSurvivesANewEngine() {
        val clock = ManualClock(Instant.parse("2026-01-01T00:00:00Z"))
        val config = FileConfigStore(dir.resolve("config"), SchemaRegistry(), clock)
        val state = FileChartStateStore(dir.resolve("sfc"))
        val first = engine(clock, TagEngine(clock), config, state)
        putChart(config, "held", spec(
            persistState = true,
            variables = listOf(mapOf("name" to "n", "initial" to 4)),
            elements = listOf(
                el("begin", "begin"),
                el("step", "action"),
                el("go", "transition", "expression" to "false"),
                el("end", "end"),
            ),
            links = listOf(link("begin", "step"), link("step", "go"), link("go", "end")),
        ))
        val id = first.startChart("held")
        first.close()
        val second = engine(clock, TagEngine(clock), config, state)
        second.start()
        val view = second.instance(id)
        assertEquals("Running", view["state"])
        assertEquals(4, view.vars()["n"])
        assertEquals(listOf("step"), view["activeSteps"])
        second.close()
        config.close()
    }

    private fun fixture(scripts: ChartScriptHost = ChartScriptHost.NOOP, deferWhen: () -> Boolean = { false }): Fixture {
        val clock = ManualClock(Instant.parse("2026-01-01T00:00:00Z"))
        val tags = TagEngine(clock)
        val config = FileConfigStore(dir.resolve("config-${System.nanoTime()}"), SchemaRegistry(), clock)
        val engine = engine(clock, tags, config, MemoryChartStateStore(), scripts, deferWhen)
        return Fixture(clock, config, engine)
    }

    private fun engine(
        clock: Clock,
        tags: TagEngine,
        config: FileConfigStore,
        state: ChartStateStore,
        scripts: ChartScriptHost = ChartScriptHost.NOOP,
        deferWhen: () -> Boolean = { false },
    ): SfcEngine = SfcEngine(clock, tags, config, state, inline = true, scripts = scripts, deferWhen = deferWhen).also { it.attach() }

    private class Fixture(val clock: ManualClock, val config: FileConfigStore, val engine: SfcEngine) {
        fun chart(name: String, spec: Map<String, Any?>) {
            config.put(Scope.GATEWAY, Resource(CHART_KIND, Metadata(name, SFC_MODULE), spec), "test")
        }
        fun close() {
            engine.close()
            config.close()
        }
    }
}

private fun putChart(config: FileConfigStore, name: String, spec: Map<String, Any?>) {
    config.put(Scope.GATEWAY, Resource(CHART_KIND, Metadata(name, SFC_MODULE), spec), "test")
}

private fun spec(
    elements: List<Map<String, Any?>>,
    links: List<Map<String, String>>,
    executionMode: String = "callable",
    parameters: List<Map<String, Any?>> = emptyList(),
    variables: List<Map<String, Any?>> = emptyList(),
    onCancel: List<Map<String, Any?>> = emptyList(),
    onAbort: List<Map<String, Any?>> = emptyList(),
    onRedundantFailover: List<Map<String, Any?>> = emptyList(),
    redundancySync: Boolean = false,
    persistState: Boolean = false,
    failover: String = "continue",
): Map<String, Any?> = linkedMapOf(
    "executionMode" to executionMode,
    "persistState" to persistState,
    "redundancySync" to redundancySync,
    "failover" to failover,
    "parameters" to parameters,
    "variables" to variables,
    "onCancel" to onCancel,
    "onAbort" to onAbort,
    "onRedundantFailover" to onRedundantFailover,
    "elements" to elements,
    "links" to links,
)

private fun el(id: String, type: String, vararg extra: Pair<String, Any?>): Map<String, Any?> =
    linkedMapOf<String, Any?>("id" to id, "type" to type).apply { putAll(extra) }

private fun link(from: String, to: String) = mapOf("from" to from, "to" to to)

private fun expr(assign: String, expression: String) = mapOf("type" to "expression", "assign" to assign, "expression" to expression)

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.vars(): Map<String, Any?> = this["variables"] as Map<String, Any?>

private class ManualClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    override fun millis(): Long = now.toEpochMilli()
    fun advance(millis: Long) { now = now.plusMillis(millis) }
}
