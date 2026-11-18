package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.tags.expr.EvalContext
import org.oip.tags.expr.Expressions
import org.oip.tags.expr.Value
import org.oip.tags.expr.truth
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Tag groups (TAG-006/008/023/025/029): - */
class TagGroupScheduleTest {
    private fun num(v: Any?): Double = (v as Number).toDouble()

    private class FakeClock(var millis: Long = 0) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }

    private class Harness : AutoCloseable {
        val registry = SchemaRegistry()
        val store = FileConfigStore(Files.createTempDirectory("oip-tag-groups"), registry)
        val engine = TagEngine()
        val binding = TagConfigBinding(engine, store, registry).also { it.attach() }

        fun group(name: String, vararg spec: Pair<String, Any?>) {
            val id = ResourceId(TAGS_MODULE, Scope.GATEWAY, GROUP_KIND, name)
            val version = store.get(id)?.metadata?.version ?: 0L
            store.put(Scope.GATEWAY, Resource(GROUP_KIND, Metadata(name, TAGS_MODULE, version = version), mapOf(*spec)))
        }

        fun tag(name: String, vararg spec: Pair<String, Any?>) {
            store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata(name, TAGS_MODULE), mapOf(*spec)))
        }

        override fun close() {
            binding.close()
            store.close()
        }
    }

    /** Engine-backed driving expression, as a gateway would wire it. */
    private fun drivingFrom(engine: TagEngine): (String) -> Boolean = { expr ->
        truth(Expressions.eval(expr, EvalContext(ref = { raw -> engine.read(raw).let { Value(it.value, it.quality) } })).value)
    }

    @Test
    fun directModeIsDueAtTheConfiguredRate() {
        val clock = FakeClock()
        val groups = TagGroupRegistry().apply { put(TagGroupDef("fast", GroupMode.DIRECT, rateMillis = 100)) }
        val schedule = TagGroupSchedule(groups, clock)
        schedule.assign("item", "fast")

        val hits = (0..1000 step 10).filter { t -> clock.millis = t.toLong(); schedule.due() == listOf("item") }
        assertEquals((0..1000 step 100).toList(), hits)
        assertEquals(mapOf("item" to 100L), schedule.ratesFor(listOf("item", "unknown")))
    }

    @Test
    fun drivenModeSwitchesRateWhenTheConditionCrossesAndBack() {
        val clock = FakeClock()
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Drive", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0.0, valueSet = true))
        val groups = TagGroupRegistry().apply {
            put(TagGroupDef("driven", GroupMode.DRIVEN, rateMillis = 100, slowRateMillis = 1000, drivingExpression = "{Drive} > 5"))
        }
        val schedule = TagGroupSchedule(groups, clock, drivingFrom(engine))
        val changes = ArrayList<Long>()
        schedule.onRateChanged { _, rate -> changes += rate }
        schedule.assign("item", "driven")

        assertEquals(listOf("item"), schedule.due())
        clock.millis = 500
        assertEquals(emptyList<String>(), schedule.due(), "slow: 500 < 1000")
        clock.millis = 1000
        assertEquals(listOf("item"), schedule.due())

        engine.write("Drive", 10.0)
        assertEquals(mapOf("item" to 100L), schedule.ratesFor(listOf("item")))
        clock.millis = 1050
        assertEquals(emptyList<String>(), schedule.due(), "fast: 50 < 100")
        clock.millis = 1100
        assertEquals(listOf("item"), schedule.due())

        engine.write("Drive", 0.0)
        clock.millis = 1200
        assertEquals(emptyList<String>(), schedule.due(), "slow again: 100 < 1000")
        clock.millis = 2100
        assertEquals(listOf("item"), schedule.due())
        assertEquals(listOf(100L, 1000L), changes, "onRateChanged fires on each switch")
    }

    @Test
    fun leasedModeIsFastWhileAnySubscriberRemainsThenOff() {
        val clock = FakeClock()
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Leased/Tag", type = TagType.MEMORY, dataType = DataType.DOUBLE))
        val groups = TagGroupRegistry().apply { put(TagGroupDef("leased", GroupMode.LEASED, rateMillis = 50)) }
        val schedule = TagGroupSchedule(groups, clock)
        schedule.observe(engine)
        val key = engine.subscriptionKey("Leased/Tag")
        schedule.assign(key, "leased")

        assertEquals(emptyList<String>(), schedule.due(), "unleased with no slow rate: off")
        clock.millis = 10_000
        assertEquals(emptyList<String>(), schedule.due())

        val first = engine.subscribe("Leased/Tag") {}
        val second = engine.subscribe("[default]Leased/Tag.Quality") {}
        assertEquals(listOf(key), schedule.due())
        clock.millis = 10_050
        assertEquals(listOf(key), schedule.due())

        first.close()
        clock.millis = 10_100
        assertEquals(listOf(key), schedule.due(), "one subscriber is still leasing")

        second.close()
        clock.millis = 10_150
        assertEquals(emptyList<String>(), schedule.due(), "last unsubscribe: off")
        clock.millis = 20_000
        assertEquals(emptyList<String>(), schedule.due())
    }

    @Test
    fun leasedModeFallsBackToTheSlowRateWhenOneIsSet() {
        val clock = FakeClock()
        val groups = TagGroupRegistry().apply { put(TagGroupDef("leased", GroupMode.LEASED, rateMillis = 50, slowRateMillis = 500)) }
        val schedule = TagGroupSchedule(groups, clock)
        schedule.assign("item", "leased")
        assertEquals(mapOf("item" to 500L), schedule.ratesFor(listOf("item")))
        schedule.lease("item", true)
        assertEquals(mapOf("item" to 50L), schedule.ratesFor(listOf("item")))
    }

    @Test
    fun fixedRateExpressionReevaluatesOnTheTimerWithNoDependencyChange() {
        Harness().use { h ->
            h.group("ticked", "mode" to "direct", "rateMillis" to 100)
            h.tag("counter", "path" to "Counter", "type" to "memory", "dataType" to "Int32", "value" to 0)
            h.tag("ticks", "path" to "Ticks", "type" to "expression", "dataType" to "Int32",
                "expression" to "{Counter}", "tagGroup" to "ticked", "executionMode" to "tagGroup")
            val t0 = h.engine.read("Ticks").timestamp.plusSeconds(10)

            h.engine.tickExpressionGroups(t0)
            assertEquals(t0, h.engine.read("Ticks").timestamp, "first tick re-runs it although nothing changed")
            h.engine.tickExpressionGroups(t0.plusMillis(50))
            assertEquals(t0, h.engine.read("Ticks").timestamp, "not due yet")
            h.engine.tickExpressionGroups(t0.plusMillis(100))
            assertEquals(t0.plusMillis(100), h.engine.read("Ticks").timestamp)
            assertEquals(0.0, num(h.engine.read("Ticks").value))

            // A tag in the default (event-driven) group is not touched by the timer.
            h.tag("plain", "path" to "Plain", "type" to "expression", "dataType" to "Int32", "expression" to "{Counter}")
            val plainStamp = h.engine.read("Plain").timestamp
            h.engine.tickExpressionGroups(t0.plusSeconds(1))
            h.engine.tickExpressionGroups(t0.plusSeconds(2))
            assertEquals(plainStamp, h.engine.read("Plain").timestamp, "event-driven tag only changes on a dependency change")

            h.engine.write("Counter", 7)
            assertEquals(7.0, num(h.engine.read("Ticks").value), "event-driven path still works for a fixed-rate tag")
            assertEquals(7.0, num(h.engine.read("Plain").value))
        }
    }

    @Test
    fun editingAGroupsRateInTheStoreTakesEffectWithoutRestart() {
        Harness().use { h ->
            h.group("g", "mode" to "direct", "rateMillis" to 1000)
            h.tag("ticks", "path" to "Ticks", "type" to "expression", "dataType" to "Int32", "expression" to "1", "tagGroup" to "g", "executionMode" to "tagGroup")
            val t0 = h.engine.read("Ticks").timestamp.plusSeconds(10)
            h.engine.tickExpressionGroups(t0)
            h.engine.tickExpressionGroups(t0.plusMillis(300))
            assertEquals(t0, h.engine.read("Ticks").timestamp, "1000 ms rate: not due at +300")

            h.group("g", "mode" to "direct", "rateMillis" to 100)
            h.engine.tickExpressionGroups(t0.plusMillis(300))
            assertEquals(t0.plusMillis(300), h.engine.read("Ticks").timestamp, "100 ms rate applies to the running engine")
        }
    }

    @Test
    fun unknownTagGroupIsAConfigurationErrorThatClearsWhenTheGroupAppears() {
        Harness().use { h ->
            h.tag("ghost", "path" to "Ghost", "type" to "memory", "dataType" to "Double", "value" to 2.5, "tagGroup" to "later")
            h.tag("calc", "path" to "Calc", "type" to "expression", "dataType" to "Double", "expression" to "{Ghost} * 2", "tagGroup" to "later")
            assertEquals(QualityCode.ERROR_CONFIGURATION, h.engine.read("Ghost").quality.code)
            assertEquals(QualityCode.ERROR_CONFIGURATION, h.engine.read("Calc").quality.code)
            assertTrue(h.binding.problems().isEmpty(), "a quality on the tag, not a load exception: ${h.binding.problems()}")

            h.group("later", "mode" to "direct", "rateMillis" to 500)
            assertEquals(QualityCode.GOOD, h.engine.read("Ghost").quality.code)
            assertEquals(2.5, num(h.engine.read("Ghost").value))
            assertEquals(5.0, num(h.engine.read("Calc").value))
        }
    }

    @Test
    fun deletingAGroupInUseMarksItsTagsAndSchedulesAtTheDefaultRate() {
        Harness().use { h ->
            h.group("temp", "mode" to "direct", "rateMillis" to 5000)
            h.tag("t", "path" to "T", "type" to "memory", "dataType" to "Double", "value" to 1.0, "tagGroup" to "temp")
            assertEquals(QualityCode.GOOD, h.engine.read("T").quality.code)

            val clock = FakeClock()
            val schedule = TagGroupSchedule(h.engine.groups, clock)
            schedule.assign("item", "temp")
            assertEquals(mapOf("item" to 5000L), schedule.ratesFor(listOf("item")))

            h.store.delete(ResourceId(TAGS_MODULE, Scope.GATEWAY, GROUP_KIND, "temp"))
            assertEquals(QualityCode.ERROR_CONFIGURATION, h.engine.read("T").quality.code)
            assertEquals(1.0, num(h.engine.read("T").value), "value is kept")
            assertEquals(mapOf("item" to TagGroupDef.DEFAULT.rateMillis), schedule.ratesFor(listOf("item")), "items keep polling at the default rate")

            h.group("temp", "mode" to "direct", "rateMillis" to 5000)
            assertEquals(QualityCode.GOOD, h.engine.read("T").quality.code)
        }
    }

    @Test
    fun fixedRateExecutionModeUsesItsOwnRateNotTheGroup() {
        Harness().use { h ->
            h.tag("fixed", "path" to "Fixed", "type" to "expression", "dataType" to "Int32", "expression" to "1",
                "executionMode" to "fixedRate", "executionRateMillis" to 250)
            val t0 = h.engine.read("Fixed").timestamp.plusSeconds(10)
            h.engine.tickExpressionGroups(t0)
            h.engine.tickExpressionGroups(t0.plusMillis(200))
            assertEquals(t0, h.engine.read("Fixed").timestamp, "250 ms rate: not due at +200")
            h.engine.tickExpressionGroups(t0.plusMillis(250))
            assertEquals(t0.plusMillis(250), h.engine.read("Fixed").timestamp)
        }
    }

    @Test
    fun theDefaultGroupCannotBeRemoved() {
        val engine = TagEngine()
        engine.removeGroup(DEFAULT_GROUP)
        assertNotNull(engine.groups.get(DEFAULT_GROUP))
    }

    @Test
    fun tagGroupRoundTripsThroughSpecAndDefaultIsNotWritten() {
        val grouped = TagConfig.fromSpec(mapOf("path" to "A", "type" to "memory", "tagGroup" to "fast"))
        assertEquals("fast", grouped.tagGroup)
        assertEquals("fast", grouped.toSpec()["tagGroup"])
        val plain = TagConfig.fromSpec(mapOf("path" to "B", "type" to "opc"))
        assertEquals(DEFAULT_GROUP, plain.tagGroup)
        assertFalse(plain.toSpec().containsKey("tagGroup"))
    }

    /**
     * System time, except that the first reading taken on [blockThread] after [arm] parks until
     * [release]. The timed tick reads the clock while it evaluates an expression, so this holds a
     * tick in the middle of an evaluation, deterministically and without sleeps.
     */
    private class GateClock : Clock() {
        @Volatile var blockThread: Thread? = null
        private val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun arm(thread: Thread) {
            blockThread = thread
            armed.set(true)
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant(): Instant {
            if (Thread.currentThread() === blockThread && armed.compareAndSet(true, false)) {
                entered.countDown()
                release.await()
            }
            return Instant.now()
        }
    }

    private fun timedEngine(clock: Clock, count: Int): TagEngine {
        val engine = TagEngine(clock)
        engine.configure(TagConfig(path = "Counter", type = TagType.MEMORY, dataType = DataType.INT32, value = 1, valueSet = true))
        engine.configure(TagConfig(path = "Dev", type = TagType.OPC, dataType = DataType.DOUBLE))
        for (i in 0 until count) {
            engine.configure(TagConfig(path = "Timed/T$i", type = TagType.EXPRESSION, dataType = DataType.INT32,
                expression = "{Counter} + $i", executionMode = ExecutionMode.FIXED_RATE, executionRateMillis = 100))
        }
        return engine
    }

    @Test
    fun deviceSampleCompletesWhileATimedTickIsMidEvaluation() {
        val clock = GateClock()
        val engine = timedEngine(clock, 500)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tickAt = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            val ticker = pool.submit {
                clock.arm(Thread.currentThread())
                engine.tickExpressionGroups(tickAt)
            }
            assertTrue(clock.entered.await(30, TimeUnit.SECONDS), "tick reached an evaluation")

            // The tick is parked inside an expression evaluation. A path-based device sample takes the
            // shared lock to find its node, and a config-locked write takes the exclusive lock; neither
            // may wait for the tick. (A bounded get() fails the test instead of hanging it.)
            pool.submit { engine.updateRaw("Dev", 42.0) }.get(30, TimeUnit.SECONDS)
            assertEquals(42.0, num(engine.read("Dev").value))
            assertEquals(QualityCode.GOOD, engine.read("Dev").quality.code)
            assertFalse(ticker.isDone, "the tick is still held mid-evaluation")

            clock.release.countDown()
            ticker.get(30, TimeUnit.SECONDS)
            for (i in 0 until 500) assertEquals(tickAt, engine.read("Timed/T$i").timestamp, "T$i re-stamped by the tick")
        } finally {
            clock.release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun timedTickNotifiesSubscribersWithNoEngineLockHeld() {
        val engine = timedEngine(Clock.systemUTC(), 50)
        val inListener = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = AtomicBoolean(false)
        val first = AtomicBoolean(true)
        engine.subscribe("Timed/T0") {
            if (!first.compareAndSet(true, false)) return@subscribe
            held.set(engine.holdingWriteLock() || engine.gate.readHoldCount > 0)
            inListener.countDown()
            release.await()
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val ticker = pool.submit { engine.tickExpressionGroups(Instant.now().plusSeconds(60)) }
            assertTrue(inListener.await(30, TimeUnit.SECONDS), "tick notified the subscriber")
            assertFalse(held.get(), "no engine lock is held while subscribers run")
            pool.submit {
                engine.updateRaw("Dev", 7.0)
                engine.write("Counter", 5)
            }.get(30, TimeUnit.SECONDS)
            assertEquals(7.0, num(engine.read("Dev").value))
            assertEquals(5.0, num(engine.read("Timed/T0").value), "the write's own re-evaluation ran meanwhile")
            assertFalse(ticker.isDone)
            release.countDown()
            ticker.get(30, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun opcTagInAMissingGroupKeepsItsConfigurationErrorOnDeviceSamples() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Dev", type = TagType.OPC, dataType = DataType.DOUBLE, scanPeriodMillis = 100, tagGroup = "nope"))
        assertEquals(QualityCode.ERROR_CONFIGURATION, engine.read("Dev").quality.code)

        engine.updateRaw("Dev", 3.0)
        assertEquals(QualityCode.ERROR_CONFIGURATION, engine.read("Dev").quality.code, "path-based sample")
        val sample = engine.openSample("Dev")
        sample.update(4.0)
        assertEquals(QualityCode.ERROR_CONFIGURATION, sample.read().quality.code, "resolved-sample hot path")
        engine.checkComms(Instant.now().plusSeconds(3600))
        engine.reportCommsLoss("Dev")
        assertEquals(QualityCode.ERROR_CONFIGURATION, engine.read("Dev").quality.code, "comms loss does not mask it either")

        engine.configureGroup(TagGroupDef("nope", rateMillis = 100))
        engine.reportCommsLoss("Dev", lost = false)
        sample.update(5.0)
        assertEquals(QualityCode.GOOD, engine.read("Dev").quality.code)
        assertEquals(5.0, num(engine.read("Dev").value))
    }
}
