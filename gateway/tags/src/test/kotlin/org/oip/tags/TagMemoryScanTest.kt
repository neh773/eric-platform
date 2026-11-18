package org.oip.tags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Scalar memory writes through [TagSample.set]: the scan handle PRF-007 drives.
 */
class TagMemoryScanTest {
    private val stamp: Instant = Instant.EPOCH

    @Test
    fun setNotifiesEverySubscriberAndSkipsAnUnchangedValue() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "A", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true))
        val seen = Array(2) { ArrayList<Double>() }
        repeat(2) { s -> engine.subscribe("A") { seen[s] += (it.value.value as Number).toDouble() } }
        val sample = engine.openSample("A")
        assertEquals(Quality.GOOD, sample.set(1.0, stamp).quality)
        assertEquals(Quality.GOOD, sample.set(4.0, stamp).quality)
        assertEquals(listOf(4.0), seen[0])
        assertEquals(listOf(4.0), seen[1])
        assertEquals(4.0, (sample.read().value as Number).toDouble())
        assertEquals(stamp, sample.read().timestamp)
    }

    @Test
    fun setUpdatesAnExpressionThatSubscribedBeforeTheWrite() {
        val engine = TagEngine()
        val seen = ArrayList<Double>()
        engine.subscribe("Late") { seen += (it.value.value as Number).toDouble() }
        engine.configure(TagConfig(path = "Late", type = TagType.EXPRESSION, dataType = DataType.DOUBLE, expression = "{Soon} * 3"))
        engine.configure(TagConfig(path = "Soon", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 2.0, valueSet = true))
        assertEquals(6.0, (engine.read("Late").value as Number).toDouble())
        assertEquals(Quality.GOOD, engine.openSample("Soon").set(5.0, stamp).quality)
        assertEquals(15.0, (engine.read("Late").value as Number).toDouble())
        assertEquals(listOf(6.0, 15.0), seen)
    }

    @Test
    fun absoluteDeadbandSuppressesASmallSet() {
        val engine = TagEngine()
        engine.configure(TagConfig(
            path = "A", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0.0, valueSet = true,
            deadband = Deadband.absolute(1.0),
        ))
        val seen = ArrayList<Double>()
        engine.subscribe("A") { seen += (it.value.value as Number).toDouble() }
        val sample = engine.openSample("A")
        assertEquals(Quality.GOOD, sample.set(0.4, stamp).quality)
        assertEquals(Quality.GOOD, sample.set(2.0, stamp).quality)
        assertEquals(listOf(2.0), seen)
        assertEquals(2.0, (engine.read("A").value as Number).toDouble())
    }

    @Test
    fun reentrantSetOnTheSameTagIsACycle() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "A", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0.0, valueSet = true))
        val sample = engine.openSample("A")
        val nested = AtomicReference<WriteResult>()
        engine.subscribe("A") { nested.set(sample.set(9.0, stamp)) }
        assertEquals(Quality.GOOD, sample.set(1.0, stamp).quality)
        assertEquals(QualityCode.ERROR_CYCLE_DETECTED, nested.get().quality.code)
        assertEquals(1.0, (engine.read("A").value as Number).toDouble())
    }

    @Test
    fun rejectsOpcReadOnlyAndMissingAndABadConversion() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Opc", type = TagType.OPC, dataType = DataType.DOUBLE, opcItemPath = "ns=1;s=Opc"))
        engine.configure(TagConfig(path = "Locked", type = TagType.MEMORY, dataType = DataType.INT32, value = 1, valueSet = true, writable = false))
        engine.configure(TagConfig(path = "Num", type = TagType.MEMORY, dataType = DataType.INT32, value = 1, valueSet = true))
        assertEquals(QualityCode.BAD_NOT_WRITABLE, engine.openSample("Opc").set(1.0, stamp).quality.code)
        assertEquals(QualityCode.BAD_NOT_WRITABLE, engine.openSample("Locked").set(2, stamp).quality.code)
        assertEquals(1, engine.read("Locked").value)
        assertEquals(QualityCode.BAD_NOT_FOUND, engine.openSample("Missing").set(1.0, stamp).quality.code)
        assertEquals(QualityCode.ERROR_TYPE_CONVERSION, engine.openSample("Num").set("nope", stamp).quality.code)
        assertEquals(1, engine.read("Num").value)
    }

    @Test
    fun propertySubscriberStillFires() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "A", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true))
        val qualities = ArrayList<Any?>()
        engine.subscribe("A.Quality") { qualities += it.value.value }
        engine.openSample("A").set(2.0, stamp)
        assertEquals(listOf("Good"), qualities)
    }

    @Test
    fun setDoesNotNeedTheConfigLock() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "A", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0.0, valueSet = true))
        val sample = engine.openSample("A")
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            repeat(2_000) { sample.set(it.toDouble(), stamp) }
            finished.countDown()
        }
        engine.withGate {
            worker.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(finished.await(2, TimeUnit.SECONDS), "memory set blocked on the config lock")
        }
        worker.join(2_000)
        assertEquals(1999.0, (sample.read().value as Number).toDouble())
    }
}
