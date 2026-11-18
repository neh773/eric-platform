package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
/**
 * UDTs: -
 * Parameters, nesting and inheritance are separate public pages under that section.
 * Scaling equations: -
 * Comms loss keeps the last value and raises Bad/Uncertain quality (SPEC §5).
 */
class UdtTest {
    private fun num(v: Any?): Double = (v as Number).toDouble()

    private fun member(spec: Map<String, Any?>) = TagConfig.fromSpec(spec, member = true)

    @Test
    fun parametersNestingInheritanceAndOverrides() {
        val engine = TagEngine()
        engine.configureUdt(UdtDefinition.fromSpec(mapOf(
            "path" to "Types/Equipment",
            "parameters" to listOf(mapOf("name" to "Name", "dataType" to "String", "default" to "equip")),
            "members" to listOf(mapOf("name" to "Running", "type" to "memory", "dataType" to "Boolean", "value" to false)),
        )))
        engine.configureUdt(UdtDefinition.fromSpec(mapOf(
            "path" to "Types/Vfd",
            "parameters" to listOf(mapOf("name" to "Device", "dataType" to "String", "default" to "")),
            "members" to listOf(
                mapOf("name" to "Hz", "type" to "memory", "dataType" to "Double", "value" to 0),
                mapOf("name" to "Item", "type" to "opc", "dataType" to "Double", "opcItemPath" to "ns=1;s={Device}.Hz"),
                mapOf("name" to "Label", "type" to "expression", "dataType" to "String", "expression" to "{Device}"),
            ),
        )))
        engine.configureUdt(UdtDefinition.fromSpec(mapOf(
            "path" to "Types/Motor",
            "parent" to "Types/Equipment",
            "parameters" to listOf(mapOf("name" to "Device", "dataType" to "String", "default" to "PLC")),
            "members" to listOf(
                mapOf("name" to "Running", "type" to "memory", "value" to true),
                mapOf("name" to "Speed", "type" to "memory", "dataType" to "Double", "value" to 0),
                mapOf(
                    "name" to "SpeedText", "type" to "expression", "dataType" to "String",
                    "expression" to """if({[.]Running}, {Name} + " " + toString({[.]Speed}), "stopped")""",
                ),
                mapOf(
                    "name" to "Drive", "type" to "udt", "typeId" to "Types/Vfd",
                    "parameters" to mapOf("Device" to "{Device}"),
                ),
            ),
        )))
        engine.configure(TagConfig.fromSpec(mapOf(
            "path" to "Plant/M1",
            "type" to "udt",
            "typeId" to "types/motor",
            "parameters" to mapOf("Device" to "Motor1", "Name" to "Pump"),
            "overrides" to mapOf(
                "Speed" to mapOf("value" to 5),
                "Drive/Hz" to mapOf("value" to 60),
            ),
        )))
        assertEquals(true, engine.read("Plant/M1/Running").value)
        assertEquals(5.0, num(engine.read("Plant/M1/Speed").value))
        assertEquals("Pump 5", engine.read("Plant/M1/SpeedText").value)
        assertEquals(60.0, num(engine.read("Plant/M1/Drive/Hz").value))
        assertEquals("ns=1;s=Motor1.Hz", engine.read("Plant/M1/Drive/Item.OpcItemPath").value)
        assertEquals("Motor1", engine.read("Plant/M1/Drive/Label").value)
        assertEquals(NodeKind.UDT_INSTANCE, engine.browse("Plant").single { it.name == "M1" }.kind)
        assertEquals(NodeKind.UDT_DEFINITION, engine.browse("Types").single { it.name == "Motor" }.kind)
        engine.write("Plant/M1/Running", false)
        assertEquals("stopped", engine.read("Plant/M1/SpeedText").value)
        engine.write("Plant/M1/Speed", 7)
        engine.write("Plant/M1/Running", true)
        assertEquals("Pump 7", engine.read("Plant/M1/SpeedText").value)
    }

    @Test
    fun definitionChangeUpdatesInstancesAndPreservesRuntimeValues() {
        val engine = TagEngine()
        engine.configureUdt(UdtDefinition.fromSpec(mapOf(
            "path" to "Types/Motor",
            "members" to listOf(
                mapOf("name" to "Speed", "type" to "memory", "dataType" to "Double", "value" to 1),
                mapOf("name" to "Twice", "type" to "expression", "dataType" to "Double", "expression" to "{[.]Speed} * 2"),
            ),
        )))
        engine.configure(TagConfig(path = "A", type = TagType.UDT, typeId = "Types/Motor"))
        engine.write("A/Speed", 4)
        assertEquals(8.0, num(engine.read("A/Twice").value))
        engine.configureUdt(UdtDefinition.fromSpec(mapOf(
            "path" to "Types/Motor",
            "members" to listOf(
                mapOf("name" to "Speed", "type" to "memory", "dataType" to "Double", "value" to 1),
                mapOf("name" to "Twice", "type" to "expression", "dataType" to "Double", "expression" to "{[.]Speed} * 3"),
            ),
        )))
        assertEquals(4.0, num(engine.read("A/Speed").value))
        assertEquals(12.0, num(engine.read("A/Twice").value))
    }

    @Test
    fun inheritanceCycleAndMissingTypeAreErrors() {
        val engine = TagEngine()
        engine.configureUdt(UdtDefinition(path = "Types/A", parent = "Types/B"))
        engine.configureUdt(UdtDefinition(path = "Types/B", parent = "Types/A"))
        engine.configure(TagConfig(path = "X", type = TagType.UDT, typeId = "Types/A"))
        assertEquals(QualityCode.ERROR_CONFIGURATION, engine.read("X").quality.code)
        engine.configure(TagConfig(path = "Y", type = TagType.UDT, typeId = "Types/Missing"))
        assertEquals(QualityCode.ERROR_CONFIGURATION, engine.read("Y").quality.code)
    }

    @Test
    fun nestedTypeCycleIsReported() {
        val engine = TagEngine()
        engine.configureUdt(UdtDefinition.fromSpec(mapOf(
            "path" to "Types/Loop",
            "members" to listOf(mapOf("name" to "Inner", "type" to "udt", "typeId" to "Types/Loop")),
        )))
        engine.configure(TagConfig(path = "L", type = TagType.UDT, typeId = "Types/Loop"))
        assertEquals(QualityCode.ERROR_CONFIGURATION, engine.read("L").quality.code)
        assertTrue(engine.read("L").quality.diagnostic!!.contains("cycle"))
    }
}

class ScalingDeadbandCommsTest {
    private fun num(v: Any?): Double = (v as Number).toDouble()

    @Test
    fun absoluteAndPercentDeadband() {
        val engine = TagEngine()
        engine.configure(TagConfig(
            path = "Abs", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0, valueSet = true,
            deadband = Deadband.absolute(1.0),
        ))
        val abs = ArrayList<Double>()
        engine.subscribe("Abs") { abs += num(it.value.value) }
        engine.write("Abs", 0.4)
        engine.write("Abs", 1.0)
        engine.write("Abs", 1.9)
        engine.write("Abs", 2.0)
        assertEquals(listOf(1.0, 2.0), abs)
        assertEquals(2.0, num(engine.read("Abs").value))

        engine.configure(TagConfig(
            path = "Pct", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0, valueSet = true,
            deadband = Deadband.percent(10.0), engLow = 0.0, engHigh = 100.0,
        ))
        val pct = ArrayList<Double>()
        engine.subscribe("Pct") { pct += num(it.value.value) }
        engine.write("Pct", 9)
        engine.write("Pct", 10)
        assertEquals(listOf(10.0), pct)
    }

    @Test
    fun linearSquareRootAndExponentialScaling() {
        val clock = ManualClock()
        val engine = TagEngine(clock)
        engine.configure(TagConfig(
            path = "Lin", type = TagType.OPC, dataType = DataType.DOUBLE,
            scaling = Scaling(ScaleMode.LINEAR, rawLow = 4.0, rawHigh = 20.0, scaledLow = 0.0, scaledHigh = 100.0, clamp = true),
        ))
        engine.updateRaw("Lin", 4.0)
        assertEquals(0.0, num(engine.read("Lin").value), 1e-9)
        assertEquals(QualityCode.GOOD, engine.read("Lin").quality.code)
        engine.updateRaw("Lin", 12.0)
        assertEquals(50.0, num(engine.read("Lin").value), 1e-9)
        engine.updateRaw("Lin", 0.0)
        assertEquals(0.0, num(engine.read("Lin").value), 1e-9)
        assertEquals(QualityCode.UNCERTAIN_ENGINEERING_UNITS_EXCEEDED, engine.read("Lin").quality.code)
        assertEquals(Quality.GOOD, engine.write("Lin", 50).quality)
        assertEquals(12.0, num(engine.lastRawWrite("Lin")!!), 1e-9)
        assertEquals(QualityCode.GOOD_WRITE_PENDING, engine.read("Lin").quality.code)

        engine.configure(TagConfig(
            path = "Open", type = TagType.OPC, dataType = DataType.DOUBLE,
            scaling = Scaling(ScaleMode.LINEAR, 4.0, 20.0, 0.0, 100.0, clamp = false),
        ))
        engine.updateRaw("Open", 0.0)
        assertEquals(-25.0, num(engine.read("Open").value), 1e-9)
        assertEquals(QualityCode.GOOD, engine.read("Open").quality.code)

        engine.configure(TagConfig(
            path = "Sqrt", type = TagType.OPC, dataType = DataType.DOUBLE,
            scaling = Scaling(ScaleMode.SQUARE_ROOT, 0.0, 100.0, 0.0, 10.0, clamp = true),
        ))
        engine.updateRaw("Sqrt", 25.0)
        assertEquals(5.0, num(engine.read("Sqrt").value), 1e-9)
        engine.updateRaw("Sqrt", 100.0)
        assertEquals(10.0, num(engine.read("Sqrt").value), 1e-9)

        engine.configure(TagConfig(
            path = "Filt", type = TagType.OPC, dataType = DataType.DOUBLE,
            scaling = Scaling(mode = ScaleMode.EXPONENTIAL_FILTER, factor = 0.5),
        ))
        engine.updateRaw("Filt", 0.0)
        engine.updateRaw("Filt", 10.0)
        engine.updateRaw("Filt", 10.0)
        assertEquals(7.5, num(engine.read("Filt").value), 1e-9)
        engine.updateRaw("Filt", 999.0, Quality(QualityCode.BAD_SENSOR_FAILURE))
        assertEquals(7.5, num(engine.read("Filt").value), 1e-9)
        assertEquals(QualityCode.BAD_SENSOR_FAILURE, engine.read("Filt").quality.code)
        engine.updateRaw("Filt", 0.0)
        assertEquals(3.75, num(engine.read("Filt").value), 1e-9)
    }

    @Test
    fun commsLossKeepsLastValue() {
        val clock = ManualClock()
        val engine = TagEngine(clock)
        engine.configure(TagConfig(
            path = "Dev", type = TagType.OPC, dataType = DataType.DOUBLE,
            scanPeriodMillis = 1000, commsTimeoutMillis = 500,
        ))
        engine.updateRaw("Dev", 42.0, sourceTimestamp = clock.now.plusMillis(5))
        assertEquals(42.0, num(engine.read("Dev").value))
        val sourceTs = engine.read("Dev").sourceTimestamp
        clock.advanceMillis(1499)
        engine.checkComms()
        assertEquals(QualityCode.GOOD, engine.read("Dev").quality.code)
        clock.advanceMillis(1)
        engine.checkComms()
        val lost = engine.read("Dev")
        assertEquals(QualityCode.BAD_NOT_CONNECTED, lost.quality.code)
        assertEquals(42.0, num(lost.value))
        assertEquals(sourceTs, lost.sourceTimestamp)
        engine.updateRaw("Dev", 43.0)
        assertEquals(43.0, num(engine.read("Dev").value))
        assertEquals(QualityCode.GOOD, engine.read("Dev").quality.code)

        engine.configure(TagConfig(
            path = "Hold", type = TagType.OPC, dataType = DataType.DOUBLE,
            commsLoss = CommsLossMode.UNCERTAIN_LAST_KNOWN,
        ))
        engine.updateRaw("Hold", 7.0)
        engine.reportCommsLoss("Hold", true)
        val uncertain = engine.read("Hold")
        assertEquals(QualityCode.UNCERTAIN_LAST_KNOWN_VALUE, uncertain.quality.code)
        assertEquals(7.0, num(uncertain.value))
        assertEquals(0x40900000, uncertain.quality.opcUaStatus)
    }

    @Test
    fun sampleInsideDeadbandStillResetsCommsTimer() {
        val clock = ManualClock()
        val engine = TagEngine(clock)
        engine.configure(TagConfig(
            path = "Dev", type = TagType.OPC, dataType = DataType.DOUBLE,
            deadband = Deadband.absolute(1.0), scanPeriodMillis = 1000, commsTimeoutMillis = 0,
        ))
        engine.updateRaw("Dev", 0.0)
        clock.advanceMillis(900)
        engine.updateRaw("Dev", 0.2)
        assertEquals(0.0, num(engine.read("Dev").value))
        clock.advanceMillis(900)
        engine.checkComms()
        assertEquals(QualityCode.GOOD, engine.read("Dev").quality.code)
        clock.advanceMillis(100)
        engine.checkComms()
        assertEquals(QualityCode.BAD_NOT_CONNECTED, engine.read("Dev").quality.code)
        assertEquals(0.0, num(engine.read("Dev").value))
    }

    @Test
    fun driverTimeoutUsesTheTagCodeAndKeepsValueAndSourceTime() {
        val clock = ManualClock()
        val engine = TagEngine(clock)
        engine.configure(TagConfig(
            path = "Hold", type = TagType.OPC, dataType = DataType.DOUBLE,
            commsLoss = CommsLossMode.UNCERTAIN_LAST_KNOWN,
        ))
        engine.updateRaw("Hold", 7.0, sourceTimestamp = clock.now.plusMillis(5))
        val good = engine.read("Hold")
        engine.updateRaw("Hold", 99.0, Quality(QualityCode.BAD_TIMEOUT))
        val lost = engine.read("Hold")
        assertEquals(QualityCode.UNCERTAIN_LAST_KNOWN_VALUE, lost.quality.code)
        assertEquals(7.0, num(lost.value))
        assertEquals(good.sourceTimestamp, lost.sourceTimestamp)
        clock.advanceMillis(40)
        engine.updateRaw("Hold", 1.0, Quality(QualityCode.BAD_NOT_CONNECTED))
        val again = engine.read("Hold")
        assertEquals(lost.timestamp, again.timestamp)
        assertEquals(7.0, num(again.value))
        assertEquals(good.sourceTimestamp, again.sourceTimestamp)
        engine.updateRaw("Hold", 8.0)
        assertEquals(QualityCode.GOOD, engine.read("Hold").quality.code)
        assertEquals(8.0, num(engine.read("Hold").value))
    }
}
