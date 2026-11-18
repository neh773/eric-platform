package org.oip.tags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tag diagnostics counters and references (TAG-003).
 *
 * Deadband counters move when a value is checked and when it is stored. Subscriber counts move when
 * a subscription opens and closes. Tag-group execution counters move when the expression ticker runs,
 * including an overrun when a later tick arrives more than one period late.
 */
class TagDiagnosticsTest {
    @Test
    fun memoryAndOpcDeadbandCountersMoveWithValueChanges() {
        val engine = TagEngine()
        engine.configure(TagConfig(
            path = "Level", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0.0, valueSet = true,
            deadband = Deadband.absolute(1.0),
        ))
        val base = engine.diagnostics("[default]Level")!!
        assertEquals("memory", base.type)
        assertEquals("absolute", base.deadband.mode)
        assertEquals(1.0, base.deadband.limit)
        assertEquals("Good", base.quality)
        assertTrue(base.deadband.evaluated >= 1)
        assertEquals(base.deadband.evaluated, base.deadband.published)

        assertTrue(engine.write("[default]Level", 0.4).ok)
        val inside = engine.diagnostics("[default]Level")!!
        assertEquals(0.0, inside.value)
        assertEquals(base.deadband.evaluated + 1, inside.deadband.evaluated)
        assertEquals(base.deadband.published, inside.deadband.published)

        assertTrue(engine.write("[default]Level", 2.0).ok)
        val published = engine.diagnostics("[default]Level")!!
        assertEquals(2.0, published.value)
        assertEquals(inside.deadband.evaluated + 1, published.deadband.evaluated)
        assertEquals(inside.deadband.published + 1, published.deadband.published)

        engine.configure(TagConfig(
            path = "Dev", type = TagType.OPC, dataType = DataType.DOUBLE,
            opcServer = "sim", opcItemPath = "HR1", deadband = Deadband.absolute(1.0),
        ))
        val opc = engine.diagnostics("[default]Dev")!!
        assertEquals("opc", opc.type)
        assertEquals("default", opc.tagGroup.name)
        assertEquals(true, opc.tagGroup.valid)
        assertEquals(0L, opc.tagGroup.overruns)
        engine.updateRaw("[default]Dev", 5.0)
        engine.updateRaw("[default]Dev", 5.4)
        engine.updateRaw("[default]Dev", 8.0)
        val after = engine.diagnostics("[default]Dev")!!
        assertEquals(8.0, after.value)
        assertEquals("Good", after.quality)
        assertEquals(opc.deadband.evaluated + 3, after.deadband.evaluated)
        assertEquals(opc.deadband.published + 2, after.deadband.published)
    }

    /**
     * The gateway ticks tag groups every 100 ms on its own thread while callers also tick. A tick that
     * returns has recorded every group it found due, so a group that executed is never left without a
     * last execution because another tick claimed its tags and had not recorded them yet.
     */
    @Test
    fun aTickThatReturnsSeesTheGroupExecutionEvenWhileAnotherTickRuns() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Src", type = TagType.MEMORY, dataType = DataType.INT32, value = 1, valueSet = true))
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val background = Thread {
            while (!stop.get()) engine.tickExpressionGroups()
        }.apply { isDaemon = true; start() }
        try {
            repeat(300) { i ->
                engine.configureGroup(TagGroupDef("g$i", GroupMode.DIRECT, rateMillis = 100))
                engine.configure(TagConfig(
                    path = "Calc$i", type = TagType.EXPRESSION, dataType = DataType.INT32, expression = "{Src}",
                    tagGroup = "g$i", executionMode = ExecutionMode.TAG_GROUP,
                ))
                engine.tickExpressionGroups()
                val group = engine.diagnostics("[default]Calc$i")!!.tagGroup
                assertTrue(group.lastExecution != null, "group g$i executed but has no last execution (iteration $i)")
                assertTrue(group.durationMillis!! >= 0.0)
            }
        } finally {
            stop.set(true)
            background.join(5_000)
        }
    }

    @Test
    fun referencesAndSubscriberCountFollowReadersAndSubscriptions() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Src", type = TagType.MEMORY, dataType = DataType.INT32, value = 1, valueSet = true))
        engine.configure(TagConfig(
            path = "High", type = TagType.EXPRESSION, dataType = DataType.INT32, expression = "{Src} + 1",
        ))
        engine.configure(TagConfig(
            path = "Scaled", type = TagType.DERIVED, dataType = DataType.INT32, sourcePath = "Src", readExpression = "{source}",
        ))
        engine.configure(TagConfig(
            path = "Copy", type = TagType.REFERENCE, dataType = DataType.INT32, sourcePath = "Src",
        ))

        val src = engine.diagnostics("[default]Src")!!
        assertEquals(setOf("expression", "derived", "reference"), src.referencesIn.map { it.kind }.toSet())
        assertTrue(src.referencesIn.all { it.path.startsWith("[default]") })
        assertEquals(3, src.subscriberCount)
        assertEquals(2, (engine.diagnostics("[default]High")!!.value as Number).toInt())
        val high = engine.diagnostics("[default]High")!!
        assertEquals(listOf("[default]Src"), high.referencesOut.map { it.path })
        assertEquals("memory", high.referencesOut.single().kind)
        assertEquals(listOf("[default]Src"), engine.diagnostics("[default]Scaled")!!.referencesOut.map { it.path })
        assertEquals(listOf("[default]Src"), engine.diagnostics("[default]Copy")!!.referencesOut.map { it.path })

        val sub = engine.subscribe("[default]Src") { }
        assertEquals(4, engine.diagnostics("[default]Src")!!.subscriberCount)
        sub.close()
        assertEquals(3, engine.diagnostics("[default]Src")!!.subscriberCount)

        engine.remove("[default]High")
        val left = engine.diagnostics("[default]Src")!!
        assertEquals(setOf("derived", "reference"), left.referencesIn.map { it.kind }.toSet())
        assertEquals(2, left.subscriberCount)
        assertNull(engine.diagnostics("[default]Missing"))
    }

    @Test
    fun tagGroupExecutionCountersRecordTheTickAndALateOverrun() {
        val engine = TagEngine()
        engine.configureGroup(TagGroupDef("fast", GroupMode.DIRECT, rateMillis = 100))
        engine.configure(TagConfig(path = "Src", type = TagType.MEMORY, dataType = DataType.INT32, value = 1, valueSet = true, tagGroup = "fast"))
        engine.configure(TagConfig(
            path = "Calc", type = TagType.EXPRESSION, dataType = DataType.INT32, expression = "{Src}",
            tagGroup = "fast", executionMode = ExecutionMode.TAG_GROUP,
        ))
        val before = engine.diagnostics("[default]Calc")!!
        assertEquals("fast", before.tagGroup.name)
        assertEquals("direct", before.tagGroup.mode)
        assertEquals(100L, before.tagGroup.rateMillis)
        assertEquals(true, before.tagGroup.valid)
        assertNull(before.tagGroup.lastExecution)
        assertNull(before.tagGroup.durationMillis)
        assertEquals(0L, before.tagGroup.overruns)

        val t0 = Instant.parse("2026-06-01T00:00:00Z")
        engine.tickExpressionGroups(t0)
        val once = engine.diagnostics("[default]Src")!!
        assertEquals(t0, once.tagGroup.lastExecution)
        assertTrue(once.tagGroup.durationMillis!! >= 0.0)
        assertEquals(0L, once.tagGroup.overruns)
        assertEquals(once.tagGroup.lastExecution, engine.diagnostics("[default]Calc")!!.tagGroup.lastExecution)

        engine.tickExpressionGroups(t0.plusMillis(500))
        val late = engine.diagnostics("[default]Calc")!!
        assertEquals(t0.plusMillis(500), late.tagGroup.lastExecution)
        assertEquals(4L, late.tagGroup.overruns, "500 ms gap at a 100 ms rate misses four slots")

        engine.groupActivity.record("fast", t0.plusMillis(500).toEpochMilli(), 200_000_000L, 100)
        assertEquals(5L, engine.diagnostics("[default]Calc")!!.tagGroup.overruns, "an execution longer than the rate is an overrun")
    }

    @Test
    fun aCallerWhoMayNotReadTheTagDoesNotSeeItsValueOrReferences() {
        val engine = TagEngine()
        engine.configure(TagConfig(
            path = "Secret", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 9.5, valueSet = true,
            readPermissions = setOf("operator"),
        ))
        engine.configure(TagConfig(
            path = "Echo", type = TagType.EXPRESSION, dataType = DataType.DOUBLE, expression = "{Secret}",
        ))
        val denied = engine.diagnostics("[default]Secret", TagCaller.User("ada", setOf("viewer"), "plant"))!!
        assertNull(denied.value)
        assertEquals("Bad_AccessDenied", denied.quality)
        assertTrue(denied.referencesIn.isEmpty())
        assertTrue(denied.referencesOut.isEmpty())
        val allowed = engine.diagnostics("[default]Secret", TagCaller.User("op", setOf("operator"), "plant"))!!
        assertEquals(9.5, allowed.value)
        assertEquals("Good", allowed.quality)
        assertEquals(listOf("expression"), allowed.referencesIn.map { it.kind })
    }

    @Test
    fun queryTagDiagnosticsUseTheQueryLinkKind() {
        val engine = TagEngine()
        engine.configure(TagConfig(
            path = "Src", type = TagType.MEMORY, dataType = DataType.INT32, value = 4, valueSet = true,
        ))
        engine.configure(TagConfig(
            path = "Lookup", type = TagType.QUERY, dataType = DataType.INT32,
            query = "SELECT id FROM line WHERE n = {Src}", datasource = "plant",
        ))
        engine.configure(TagConfig(
            path = "Echo", type = TagType.EXPRESSION, dataType = DataType.INT32, expression = "{Lookup}",
        ))

        val lookup = engine.diagnostics("[default]Lookup")!!
        assertEquals("query", lookup.type)
        assertEquals("Uncertain_InitialValue", lookup.quality)
        assertNull(lookup.value)
        assertEquals(listOf("[default]Echo"), lookup.referencesIn.map { it.path })
        assertEquals(listOf("expression"), lookup.referencesIn.map { it.kind })

        val echo = engine.diagnostics("[default]Echo")!!
        assertEquals(listOf("[default]Lookup"), echo.referencesOut.map { it.path })
        assertEquals("query", echo.referencesOut.single().kind)
    }
}
