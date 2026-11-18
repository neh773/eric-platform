package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Memory, expression, derived and reference tags:
 * Worst referenced quality is passed upward:
 */
class TagBehaviorTest {
    private fun num(v: Any?): Double = (v as Number).toDouble()

    @Test
    fun memoryTagIsWritableAndCaseInsensitive() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Plant/Speed", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 3.5, valueSet = true))
        val read = engine.read("plant/speed")
        assertEquals(3.5, num(read.value))
        assertEquals(QualityCode.GOOD, read.quality.code)
        assertEquals(Quality.GOOD, engine.write("[default]Plant/Speed", 4).quality)
        assertEquals(4.0, num(engine.read("Plant/Speed").value))
        val nodes = engine.browse("[default]Plant")
        assertEquals(listOf("Speed"), nodes.map { it.name })
        assertEquals(NodeKind.TAG, nodes.single().kind)
        assertEquals(QualityCode.BAD_NOT_FOUND, engine.read("Plant/Missing").quality.code)
        assertEquals(QualityCode.ERROR_INVALID_PATH, engine.read("tag[-1]").quality.code)
    }

    @Test
    fun arrayIndexReadAndWrite() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Arr", type = TagType.MEMORY, dataType = DataType.INT32, value = listOf(10, 20, 30), valueSet = true))
        assertEquals(20, engine.read("Arr[1]").value)
        assertEquals(Quality.GOOD, engine.write("Arr[1]", 5).quality)
        assertEquals(listOf(10, 5, 30), engine.read("Arr").value)
        assertEquals(QualityCode.BAD_OUT_OF_RANGE, engine.read("Arr[9]").quality.code)
    }

    @Test
    fun badExpressionDoesNotThrowAndOneSubscriberCannotBlockTheOthers() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "f", type = TagType.EXPRESSION, dataType = DataType.STRING, expression = "left()"))
        assertEquals(QualityCode.ERROR_EXPRESSION_EVAL, engine.read("f").quality.code)
        engine.configure(TagConfig(path = "cell", type = TagType.EXPRESSION, dataType = DataType.STRING, expression = "null[1, 2]"))
        assertEquals(QualityCode.BAD_NOT_FOUND, engine.read("cell").quality.code)

        engine.configure(TagConfig(path = "Src", type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
        engine.configure(TagConfig(path = "Boom", type = TagType.EXPRESSION, dataType = DataType.STRING, expression = "if({Src} = 0, 'ok', left())"))
        val seen = ArrayList<Any?>()
        engine.subscribe("Src") { throw IllegalStateException("subscriber failed") }
        engine.subscribe("Src") { seen += it.value.value }
        engine.write("Src", 1)
        assertEquals(listOf(1), seen)
        assertEquals(1, engine.read("Src").value)
        assertEquals(QualityCode.ERROR_EXPRESSION_EVAL, engine.read("Boom").quality.code)
    }

    @Test
    fun subscriptionFanOutPreservesPerTagOrder() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "A", type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
        val seen = ArrayList<String>()
        engine.subscribe("[default]A") { seen += "a:${it.value.value}" }
        engine.subscribe("A") { seen += "b:${it.value.value}" }
        assertTrue(seen.isEmpty())
        engine.write("A", 1)
        engine.write("A", 2)
        assertEquals(listOf("a:1", "b:1", "a:2", "b:2"), seen)
    }

    @Test
    fun expressionRecalculatesWhenDependenciesChange() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Late", type = TagType.EXPRESSION, dataType = DataType.DOUBLE, expression = "{Soon} * 3"))
        assertEquals(QualityCode.BAD_REFERENCE_NOT_FOUND, engine.read("Late").quality.code)
        engine.configure(TagConfig(path = "Soon", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 2, valueSet = true))
        assertEquals(6.0, num(engine.read("Late").value))
        assertEquals(QualityCode.GOOD, engine.read("Late").quality.code)
        engine.write("Soon", 4)
        assertEquals(12.0, num(engine.read("Late").value))
        val seen = ArrayList<Double>()
        engine.subscribe("Late") { seen += num(it.value.value) }
        engine.write("Soon", 5)
        assertEquals(listOf(15.0), seen)
    }

    @Test
    fun dynamicTagFunctionRebinds() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Pick", type = TagType.MEMORY, dataType = DataType.STRING, value = "A", valueSet = true))
        engine.configure(TagConfig(path = "A", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1, valueSet = true))
        engine.configure(TagConfig(path = "B", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 8, valueSet = true))
        engine.configure(TagConfig(path = "Out", type = TagType.EXPRESSION, dataType = DataType.DOUBLE, expression = "tag({Pick})"))
        assertEquals(1.0, num(engine.read("Out").value))
        engine.write("Pick", "B")
        assertEquals(8.0, num(engine.read("Out").value))
        engine.write("B", 9)
        assertEquals(9.0, num(engine.read("Out").value))
    }

    @Test
    fun badQualityPropagatesAndCyclesTerminate() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Raw", type = TagType.OPC, dataType = DataType.DOUBLE, opcItemPath = "ns=1;s=Raw"))
        engine.updateRaw("Raw", 10.0, Quality.BAD_NOT_CONNECTED)
        engine.configure(TagConfig(path = "Plus", type = TagType.EXPRESSION, dataType = DataType.DOUBLE, expression = "{Raw} + 1"))
        val plus = engine.read("Plus")
        assertEquals(11.0, num(plus.value))
        assertEquals(QualityCode.BAD_NOT_CONNECTED, plus.quality.code)
        engine.configure(TagConfig(path = "A", type = TagType.EXPRESSION, dataType = DataType.DOUBLE, expression = "{B} + 1"))
        engine.configure(TagConfig(path = "B", type = TagType.EXPRESSION, dataType = DataType.DOUBLE, expression = "{A} + 1"))
        assertEquals(QualityCode.ERROR_CYCLE_DETECTED, engine.read("A").quality.code)
        assertEquals(QualityCode.ERROR_CYCLE_DETECTED, engine.read("B").quality.code)
        assertEquals(Quality.BAD_NOT_WRITABLE, engine.write("Plus", 1).quality)
    }

    @Test
    fun derivedScalesReadsAndWrites() {
        // Fahrenheit source, Celsius derived. -
        val engine = TagEngine()
        engine.configure(TagConfig(path = "TempF", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 212, valueSet = true))
        engine.configure(TagConfig(
            path = "TempC", type = TagType.DERIVED, dataType = DataType.DOUBLE, sourcePath = "TempF",
            readExpression = "({source} - 32) * 5 / 9",
            writeExpression = "{value} * 9 / 5 + 32",
        ))
        assertEquals(100.0, num(engine.read("TempC").value), 1e-9)
        assertEquals(Quality.GOOD, engine.write("TempC", 0).quality)
        assertEquals(32.0, num(engine.read("TempF").value), 1e-9)
        assertEquals(0.0, num(engine.read("TempC").value), 1e-9)
    }

    @Test
    fun referenceSharesValueAndPassesWrites() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Src", type = TagType.MEMORY, dataType = DataType.INT32, value = 3, valueSet = true))
        engine.configure(TagConfig(path = "Alias", type = TagType.REFERENCE, dataType = DataType.INT32, sourcePath = "[.]../Src".let { "Src" }))
        assertEquals(3, engine.read("Alias").value)
        assertEquals(QualityCode.GOOD, engine.read("Alias").quality.code)
        engine.write("Alias", 9)
        assertEquals(9, engine.read("Src").value)
        assertEquals(9, engine.read("Alias").value)
        engine.write("Alias.SourceTagPath", "Missing")
        assertEquals(QualityCode.BAD_REFERENCE_NOT_FOUND, engine.read("Alias").quality.code)
    }

    @Test
    fun systemProviderIsReadOnlyAndCustomProvidersRegister() {
        val engine = TagEngine()
        assertEquals("open-industrial-platform", engine.read("[system]Gateway/Name").value)
        assertEquals(QualityCode.BAD_NOT_WRITABLE, engine.write("[system]Gateway/Name", "x").quality.code)
        engine.publishSystem("Gateway/Uptime", 12.0, DataType.DOUBLE)
        assertEquals(12.0, num(engine.read("[System]Gateway/Uptime").value))
        val demo = object : TagProvider {
            override val name = "demo"
            override val readOnly = true
            override fun read(path: TagPath) = QualifiedValue(path.segments.joinToString("/"), Quality.GOOD, Instant.EPOCH.micros())
            override fun write(path: TagPath, value: Any?) = WriteResult(Quality.BAD_NOT_WRITABLE)
            override fun subscribe(path: TagPath, listener: (TagChange) -> Unit) = AutoCloseable {}
            override fun browse(path: TagPath) = emptyList<BrowseNode>()
        }
        engine.registerProvider(demo)
        assertEquals("Tank/Level", engine.read("[demo]Tank/Level").value)
        assertTrue(engine.providers().map { it.name }.containsAll(listOf("default", "system", "demo")))
    }

    @Test
    fun propertyReads() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Plant/Speed", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1, valueSet = true, engUnit = "rpm", documentation = "line speed"))
        assertEquals("Good", engine.read("Plant/Speed.Quality").value)
        assertEquals("Speed", engine.read("Plant/Speed.Name").value)
        assertEquals("Double", engine.read("Plant/Speed.DataType").value)
        assertEquals("rpm", engine.read("Plant/Speed.EngUnit").value)
        assertTrue(engine.read("Plant/Speed.Timestamp").value is Instant)
    }
}

class ManualClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    var now: Instant = start
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advanceMillis(ms: Long) { now = now.plusMillis(ms) }
}
