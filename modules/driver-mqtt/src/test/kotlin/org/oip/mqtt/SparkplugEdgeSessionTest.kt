package org.oip.mqtt

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Edge-node rules from the Sparkplug B 3.0 specification, without a broker.
 * https://sparkplug.eclipse.org/specification/version/3.0/documents/sparkplug-specification-3.0.0.pdf
 */
class SparkplugEdgeSessionTest {
    private val config = SparkplugEdgeConfig(
        name = "edge",
        host = "127.0.0.1",
        clientId = "edge",
        groupId = "Plant",
        edgeNodeId = "Line3",
        nodeMetrics = listOf(
            EdgeMetricConfig("Speed", "[default]Line3/Speed"),
            EdgeMetricConfig("Setpoint", "[default]Line3/Setpoint", writable = true),
        ),
        devices = listOf(
            EdgeDeviceConfig("Filler", listOf(EdgeMetricConfig("Count", "[default]Line3/Filler/Count", dataType = "Int64", writable = true))),
            EdgeDeviceConfig("Capper", listOf(EdgeMetricConfig("Torque/Max", "[default]Line3/Capper/Torque"))),
        ),
    )

    private val values = mutableMapOf(
        "[default]Line3/Speed" to EdgeTagValue(12.5, true, 1_000, "Double"),
        "[default]Line3/Setpoint" to EdgeTagValue(40, true, 1_001, "Int32"),
        "[default]Line3/Filler/Count" to EdgeTagValue(7, true, 1_002, "Int32"),
        "[default]Line3/Capper/Torque" to EdgeTagValue(null, false, 0, "Double"),
    )

    private fun session(cfg: SparkplugEdgeConfig = config, first: Long = 0) = SparkplugEdgeSession(cfg, first)

    private fun SparkplugEdgeSession.born(now: Long = 5_000): List<EdgePublish> =
        births(now) { values[it.config.tag] ?: EdgeTagValue.MISSING }

    private fun metric(session: SparkplugEdgeSession, name: String) = session.metrics().first { it.config.name == name }

    @Test fun willIsNdeathWithBdSeqAndBdSeqIncrementsPerConnectAndWraps() {
        val s = session(first = 254)
        val first = s.beginConnect(1)
        assertEquals("spBv1.0/Plant/NDEATH/Line3", first.topic)
        assertEquals(1, first.qos)
        assertFalse(first.retain)
        val death = SparkplugCodec.decode(first.payload)
        assertNull(death.seq, "NDEATH carries no seq")
        val bd = death.metrics.single()
        assertEquals("bdSeq", bd.name)
        assertEquals(SparkplugCodec.UINT64, bd.datatype)
        assertEquals(254L, bd.value)
        assertEquals(254L, s.bdSeq)
        s.beginConnect(2)
        assertEquals(255L, s.bdSeq)
        s.beginConnect(3)
        assertEquals(0L, s.bdSeq, "bdSeq wraps after 255")
        s.beginConnect(4)
        assertEquals(1L, s.bdSeq)
    }

    @Test fun nbirthHasSeqZeroBdSeqRebirthAndTypedTimestampedMetricsThenDbirths() {
        val s = session(first = 9)
        val will = SparkplugCodec.decode(s.beginConnect(1).payload)
        assertTrue(s.readyToBirth(), "no primary host configured")
        val out = s.born()
        assertEquals(listOf("spBv1.0/Plant/NBIRTH/Line3", "spBv1.0/Plant/DBIRTH/Line3/Filler", "spBv1.0/Plant/DBIRTH/Line3/Capper"), out.map { it.topic })
        assertTrue(out.all { it.qos == 0 && !it.retain })
        val nbirth = SparkplugCodec.decode(out[0].payload)
        assertEquals(0, nbirth.seq)
        assertEquals(5_000, nbirth.timestamp)
        val byName = nbirth.metrics.associateBy { it.name }
        assertEquals(listOf("bdSeq", "Node Control/Rebirth", "Speed", "Setpoint"), nbirth.metrics.map { it.name })
        assertEquals(will.metrics.single().value, byName.getValue("bdSeq").value, "NBIRTH bdSeq matches the will")
        assertEquals(SparkplugCodec.UINT64, byName.getValue("bdSeq").datatype)
        assertEquals(false, byName.getValue("Node Control/Rebirth").value)
        assertEquals(SparkplugCodec.DOUBLE, byName.getValue("Speed").datatype)
        assertEquals(12.5, byName.getValue("Speed").value)
        assertEquals(1_000L, byName.getValue("Speed").timestamp)
        assertEquals(SparkplugCodec.INT32, byName.getValue("Setpoint").datatype)
        assertEquals(40, byName.getValue("Setpoint").value)
        assertTrue(nbirth.metrics.all { it.timestamp != null })

        val filler = SparkplugCodec.decode(out[1].payload)
        assertEquals(1, filler.seq)
        assertEquals(SparkplugCodec.INT64, filler.metrics.single().datatype, "config dataType wins over the tag type")
        assertEquals(7L, filler.metrics.single().value)
        val capper = SparkplugCodec.decode(out[2].payload)
        assertEquals(2, capper.seq)
        val torque = capper.metrics.single()
        assertEquals("Torque/Max", torque.name)
        assertTrue(torque.isNull, "bad quality is published as is_null")
        assertEquals(SparkplugCodec.DOUBLE, torque.datatype)
    }

    @Test fun reportByExceptionSendsOnlyChangesAndSeqWraps() {
        val s = session()
        s.beginConnect(1)
        assertTrue(s.data(1, listOf(metric(s, "Speed") to EdgeTagValue(1.0, true, 1))).isEmpty(), "no data before NBIRTH")
        s.born()
        val speed = metric(s, "Speed")
        val count = metric(s, "Count")
        assertTrue(s.data(6_000, listOf(speed to EdgeTagValue(12.5, true, 6_000))).isEmpty(), "unchanged value is not sent")
        val out = s.data(6_000, listOf(speed to EdgeTagValue(13.0, true, 6_000), count to EdgeTagValue(8, true, 6_001)))
        assertEquals(listOf("spBv1.0/Plant/NDATA/Line3", "spBv1.0/Plant/DDATA/Line3/Filler"), out.map { it.topic })
        val ndata = SparkplugCodec.decode(out[0].payload)
        assertEquals(3, ndata.seq)
        assertEquals(listOf("Speed"), ndata.metrics.map { it.name })
        assertEquals(13.0, ndata.metrics.single().value)
        assertEquals(6_000L, ndata.metrics.single().timestamp)
        val ddata = SparkplugCodec.decode(out[1].payload)
        assertEquals(4, ddata.seq)
        assertEquals(8L, ddata.metrics.single().value)

        val bad = s.data(7_000, listOf(speed to EdgeTagValue(13.0, false, 7_000)))
        assertTrue(SparkplugCodec.decode(bad.single().payload).metrics.single().isNull, "quality loss is sent as is_null")
        val back = s.data(7_100, listOf(speed to EdgeTagValue(13.0, true, 7_100)))
        assertEquals(13.0, SparkplugCodec.decode(back.single().payload).metrics.single().value)

        var last = 6
        repeat(300) { i ->
            val seq = SparkplugCodec.decode(s.data(8_000, listOf(speed to EdgeTagValue(100.0 + i, true, 8_000))).single().payload).seq!!
            assertEquals((last + 1) and 0xFF, seq)
            last = seq
        }
        val rebirth = s.born()
        assertEquals(0, SparkplugCodec.decode(rebirth[0].payload).seq, "NBIRTH resets seq")
        assertEquals(1, SparkplugCodec.decode(rebirth[1].payload).seq)
    }

    @Test fun twoChangesOfOneMetricInOneFlushAreBothSent() {
        val s = session()
        s.beginConnect(1)
        s.born()
        val speed = metric(s, "Speed")
        val out = s.data(1, listOf(speed to EdgeTagValue(1.0, true, 1), speed to EdgeTagValue(2.0, true, 2)))
        assertEquals(listOf(1.0, 2.0), SparkplugCodec.decode(out.single().payload).metrics.map { it.value })
    }

    @Test fun rebirthCommandAndWritesMapToActions() {
        val s = session()
        s.beginConnect(1)
        s.born()
        val bdSeq = s.bdSeq
        val rebirth = s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric(NODE_REBIRTH, null, SparkplugCodec.BOOLEAN, null, false, true)))
        assertEquals(listOf(EdgeAction.Rebirth), rebirth)
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric(NODE_REBIRTH, null, SparkplugCodec.BOOLEAN, null, false, false))).isEmpty())
        val reborn = SparkplugCodec.decode(s.born()[0].payload)
        assertEquals(bdSeq, reborn.metrics.first { it.name == "bdSeq" }.value, "rebirth keeps the session bdSeq")

        val write = s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("Setpoint", null, SparkplugCodec.INT32, null, false, 55)))
            .single() as EdgeAction.Write
        assertEquals("[default]Line3/Setpoint", write.metric.config.tag)
        assertEquals(55, write.value)
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("Speed", null, SparkplugCodec.DOUBLE, null, false, 1.0))).rejected(), "not writable")
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("bdSeq", null, SparkplugCodec.UINT64, null, false, 3L))).rejected())
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("", 4, SparkplugCodec.INT32, null, false, 1))).rejected(), "alias-only")

        val device = s.onMessage("spBv1.0/Plant/DCMD/Line3/Filler", command(SparkplugCodec.Metric("Count", null, SparkplugCodec.INT64, null, false, 99L)))
            .single() as EdgeAction.Write
        assertEquals("[default]Line3/Filler/Count", device.metric.config.tag)
        assertEquals(99L, device.value)
        assertTrue(s.onMessage("spBv1.0/Plant/DCMD/Line3/Capper", command(SparkplugCodec.Metric("Torque/Max", null, SparkplugCodec.DOUBLE, null, false, 1.0))).rejected())
        assertTrue(s.onMessage("spBv1.0/Plant/DCMD/Line3/Nope", command(SparkplugCodec.Metric("Count", null, SparkplugCodec.INT64, null, false, 1L))).rejected())
        assertTrue(s.onMessage("spBv1.0/Plant/DCMD/Line3/Filler", command(SparkplugCodec.Metric(NODE_REBIRTH, null, SparkplugCodec.BOOLEAN, null, false, true))).rejected())
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Other", command(SparkplugCodec.Metric("Setpoint", null, SparkplugCodec.INT32, null, false, 1))).isEmpty())
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Line3", byteArrayOf(0x0A, 0x7F)).isEmpty(), "truncated payload")
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("Setpoint", null, SparkplugCodec.INT32, null, true, null))).rejected(), "is_null command")
        val wrongType = s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("Setpoint", null, SparkplugCodec.STRING, null, false, "DROP TABLE")))
        assertEquals("Setpoint", (wrongType.single() as EdgeAction.Rejected).target)
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("Setpoint", null, SparkplugCodec.INT64, null, false, 5_000_000_000L))).rejected(), "out of Int32 range")
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("Setpoint", null, SparkplugCodec.DOUBLE, null, false, 2.5))).rejected(), "fraction to an integer")
        assertEquals(2, (s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("Setpoint", null, SparkplugCodec.DOUBLE, null, false, 2.0))).single() as EdgeAction.Write).value)
    }

    @Test fun commandsBeforeBirthAreRejected() {
        val s = session(config.copy(primaryHostId = "scada"))
        s.beginConnect(1)
        val actions = s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric("Setpoint", null, SparkplugCodec.INT32, null, false, 1)))
        assertEquals("not born", (actions.single() as EdgeAction.Rejected).reason)
        assertTrue(s.onMessage("spBv1.0/Plant/NCMD/Line3", command(SparkplugCodec.Metric(NODE_REBIRTH, null, SparkplugCodec.BOOLEAN, null, false, true))) == listOf(EdgeAction.Rebirth), "the caller ignores a rebirth before births")
    }

    @Test fun commandValuesAreCheckedAgainstTheBirthDatatype() {
        fun cmd(type: Int, value: Any?) = SparkplugCodec.Metric("m", null, type, null, false, value)
        fun v(birth: Int, type: Int, value: Any?) = SparkplugEdgeSession.commandValue(birth, cmd(type, value))
        assertNull(v(SparkplugCodec.INT8, SparkplugCodec.INT32, 300))
        assertEquals(-128, v(SparkplugCodec.INT8, SparkplugCodec.INT8, -128))
        assertNull(v(SparkplugCodec.UINT8, SparkplugCodec.INT32, -5))
        assertNull(v(SparkplugCodec.INT32, SparkplugCodec.DOUBLE, 1e12))
        assertNull(v(SparkplugCodec.INT64, SparkplugCodec.DOUBLE, 1e30))
        assertNull(v(SparkplugCodec.INT32, SparkplugCodec.BOOLEAN, true))
        assertNull(v(SparkplugCodec.INT32, SparkplugCodec.STRING, "12"))
        assertEquals(12L, v(SparkplugCodec.INT64, SparkplugCodec.INT32, 12))
        assertEquals(1.5f, v(SparkplugCodec.FLOAT, SparkplugCodec.DOUBLE, 1.5))
        assertEquals(3.0, v(SparkplugCodec.DOUBLE, SparkplugCodec.INT32, 3))
        assertNull(v(SparkplugCodec.DOUBLE, SparkplugCodec.STRING, "3"))
        assertEquals(true, v(SparkplugCodec.BOOLEAN, SparkplugCodec.BOOLEAN, true))
        assertNull(v(SparkplugCodec.BOOLEAN, SparkplugCodec.INT32, 1))
        assertEquals("hi", v(SparkplugCodec.STRING, SparkplugCodec.TEXT, "hi"))
        assertNull(v(SparkplugCodec.STRING, SparkplugCodec.INT32, 1))
        assertEquals(42L, v(SparkplugCodec.DATETIME, SparkplugCodec.DATETIME, 42L))
        assertNull(v(SparkplugCodec.INT32, SparkplugCodec.INT32, null))
    }

    @Test fun narrowIntegersOutOfRangeArePublishedAsNull() {
        fun enc(type: Int, v: Any?) = SparkplugEdgeSession.encodable(type, EdgeTagValue(v, true, 0))
        assertNull(enc(SparkplugCodec.INT8, 300))
        assertEquals(127L, enc(SparkplugCodec.INT8, 127))
        assertNull(enc(SparkplugCodec.UINT8, -5))
        assertNull(enc(SparkplugCodec.INT16, 40_000))
        assertNull(enc(SparkplugCodec.UINT32, -1L))
        assertEquals(4_294_967_295L, enc(SparkplugCodec.UINT32, 4_294_967_295L))
        assertNull(enc(SparkplugCodec.INT32, 1e12))
        assertNull(enc(SparkplugCodec.INT64, 1e30))
        assertNull(enc(SparkplugCodec.UINT64, -1))
        assertNull(enc(SparkplugCodec.INT8, "300"))
        val payload = SparkplugCodec.encode(SparkplugCodec.Payload(1, 0, listOf(SparkplugCodec.Metric("m", null, SparkplugCodec.INT8, 1, true, null))))
        assertTrue(SparkplugCodec.decode(payload).metrics.single().isNull)
    }

    @Test fun documentTagsArePublishedAsJson() {
        val json = SparkplugEdgeSession.encodable(SparkplugCodec.STRING, EdgeTagValue(linkedMapOf("a" to 1, "b" to listOf(true, "x")), true, 0))
        assertEquals("""{"a":1,"b":[true,"x"]}""", json)
    }

    @Test fun aMetricWithoutADatatypeIsLeftOutOfTheBirthUntilItResolves() {
        values["[default]Line3/Speed"] = EdgeTagValue.MISSING
        val s = session()
        s.beginConnect(1)
        val nbirth = SparkplugCodec.decode(s.born()[0].payload)
        assertEquals(listOf("bdSeq", "Node Control/Rebirth", "Setpoint"), nbirth.metrics.map { it.name })
        val speed = metric(s, "Speed")
        assertTrue(s.data(1, listOf(speed to EdgeTagValue(null, false, 1))).isEmpty())
        assertFalse(s.rebirthNeeded)
        assertTrue(s.data(2, listOf(speed to EdgeTagValue(3.5, true, 2))).isEmpty(), "not sent as data before it is born")
        assertTrue(s.rebirthNeeded)
        values["[default]Line3/Speed"] = EdgeTagValue(3.5, true, 2, "Double")
        val reborn = SparkplugCodec.decode(s.born()[0].payload)
        assertEquals(3.5, reborn.metrics.first { it.name == "Speed" }.value)
        assertFalse(s.rebirthNeeded)
    }

    private fun List<EdgeAction>.rejected() = size == 1 && single() is EdgeAction.Rejected

    @Test fun primaryHostStateGatesBirthAndOfflineEndsTheSession() {
        val s = session(config.copy(primaryHostId = "scada"))
        assertTrue("spBv1.0/STATE/scada" in s.subscriptions())
        assertTrue("spBv1.0/Plant/NCMD/Line3" in s.subscriptions())
        assertTrue("spBv1.0/Plant/DCMD/Line3/+" in s.subscriptions())
        s.beginConnect(1)
        assertFalse(s.readyToBirth())
        assertTrue(s.onMessage("spBv1.0/STATE/scada", stateDocument(false, 100).encodeToByteArray()).isEmpty())
        assertTrue(s.onMessage("spBv1.0/STATE/other", stateDocument(true, 200).encodeToByteArray()).isEmpty())
        assertTrue(s.onMessage("spBv1.0/STATE/scada", "not json".encodeToByteArray()).isEmpty())
        assertFalse(s.readyToBirth())
        assertEquals(listOf(EdgeAction.Birth), s.onMessage("spBv1.0/STATE/scada", stateDocument(true, 200).encodeToByteArray()))
        assertTrue(s.readyToBirth())
        s.born()
        assertTrue(s.onMessage("spBv1.0/STATE/scada", stateDocument(true, 200).encodeToByteArray()).isEmpty(), "already born")
        assertTrue(s.onMessage("spBv1.0/STATE/scada", stateDocument(false, 150).encodeToByteArray()).isEmpty(), "older STATE is ignored")
        assertEquals(listOf(EdgeAction.HostOffline), s.onMessage("spBv1.0/STATE/scada", stateDocument(false, 300).encodeToByteArray()))
        s.disconnected()
        s.beginConnect(2)
        assertFalse(s.readyToBirth(), "a new session waits for STATE again")
        assertEquals(listOf(EdgeAction.Birth), s.onMessage("spBv1.0/STATE/scada", stateDocument(true, 400).encodeToByteArray()))
    }

    @Test fun payloadBytesMatchTheProtobufWireFormat() {
        val s = session()
        s.beginConnect(1)
        val nbirth = s.born()[0].payload
        // Decode with the protobuf runtime instead of our codec: Payload.timestamp=1, metrics=2, seq=3.
        val input = CodedInputStream.newInstance(nbirth)
        var timestamp = -1L
        var seq = -1L
        val names = mutableListOf<String>()
        var speed: Double? = null
        while (!input.isAtEnd) {
            val tag = input.readTag()
            when (WireFormat.getTagFieldNumber(tag)) {
                1 -> timestamp = input.readUInt64()
                3 -> seq = input.readUInt64()
                2 -> {
                    val metric = CodedInputStream.newInstance(input.readBytes().toByteArray())
                    var name = ""
                    var datatype = -1
                    var double: Double? = null
                    var long: Long? = null
                    while (!metric.isAtEnd) {
                        val mt = metric.readTag()
                        when (WireFormat.getTagFieldNumber(mt)) {
                            1 -> name = metric.readString()
                            4 -> datatype = metric.readUInt32()
                            11 -> long = metric.readUInt64()
                            13 -> double = metric.readDouble()
                            else -> metric.skipField(mt)
                        }
                    }
                    names += name
                    if (name == "Speed") {
                        assertEquals(SparkplugCodec.DOUBLE, datatype)
                        speed = double
                    }
                    if (name == "bdSeq") assertEquals(0L, long)
                }
                else -> input.skipField(tag)
            }
        }
        assertEquals(5_000L, timestamp)
        assertEquals(0L, seq)
        assertEquals(listOf("bdSeq", "Node Control/Rebirth", "Speed", "Setpoint"), names)
        assertEquals(12.5, speed)
    }

    @Test fun codecRoundTripsEveryScalarType() {
        val metrics = listOf(
            SparkplugCodec.Metric("i8", null, SparkplugCodec.INT8, 1, false, -5L),
            SparkplugCodec.Metric("i16", null, SparkplugCodec.INT16, 1, false, -300L),
            SparkplugCodec.Metric("i32", null, SparkplugCodec.INT32, 1, false, -70_000L),
            SparkplugCodec.Metric("i64", null, SparkplugCodec.INT64, 1, false, -5_000_000_000L),
            SparkplugCodec.Metric("u32", null, SparkplugCodec.UINT32, 1, false, 4_000_000_000L),
            SparkplugCodec.Metric("f", null, SparkplugCodec.FLOAT, 1, false, 1.5f),
            SparkplugCodec.Metric("d", null, SparkplugCodec.DOUBLE, 1, false, 2.25),
            SparkplugCodec.Metric("b", null, SparkplugCodec.BOOLEAN, 1, false, true),
            SparkplugCodec.Metric("s", null, SparkplugCodec.STRING, 1, false, "héllo"),
            SparkplugCodec.Metric("t", null, SparkplugCodec.DATETIME, 1, false, 1_700_000_000_000L),
            SparkplugCodec.Metric("n", null, SparkplugCodec.DOUBLE, 1, true, null),
        )
        val decoded = SparkplugCodec.decode(SparkplugCodec.encode(SparkplugCodec.Payload(9, 200, metrics)))
        assertEquals(200, decoded.seq)
        assertEquals(listOf<Any?>(-5, -300, -70_000, -5_000_000_000L, 4_000_000_000L, 1.5f, 2.25, true, "héllo", 1_700_000_000_000L, null), decoded.metrics.map { it.value })
        assertTrue(decoded.metrics.last().isNull)
    }

    @Test fun datatypeResolutionAndCoercion() {
        val plain = EdgeMetricConfig("m", "t")
        assertEquals(SparkplugCodec.FLOAT, SparkplugEdgeSession.resolveDatatype(plain, EdgeTagValue(1, true, 0, "Float")))
        assertEquals(SparkplugCodec.STRING, SparkplugEdgeSession.resolveDatatype(plain, EdgeTagValue("{}", true, 0, "DOCUMENT")))
        assertEquals(SparkplugCodec.DATETIME, SparkplugEdgeSession.resolveDatatype(plain, EdgeTagValue(null, true, 0, "DATETIME")))
        assertEquals(SparkplugCodec.BOOLEAN, SparkplugEdgeSession.resolveDatatype(plain, EdgeTagValue(true, true, 0)))
        assertEquals(SparkplugCodec.INT64, SparkplugEdgeSession.resolveDatatype(plain, EdgeTagValue(3L, true, 0)))
        assertEquals(SparkplugCodec.DOUBLE, SparkplugEdgeSession.resolveDatatype(plain, EdgeTagValue(3.0, true, 0)))
        assertNull(SparkplugEdgeSession.resolveDatatype(plain, EdgeTagValue.MISSING), "unknown until the tag has a type or a value")
        assertEquals(SparkplugCodec.STRING, SparkplugEdgeSession.resolveDatatype(plain, EdgeTagValue("x", true, 0)))
        assertEquals(SparkplugCodec.UINT16, SparkplugEdgeSession.resolveDatatype(plain.copy(dataType = "UInt16"), EdgeTagValue(1.0, true, 0, "Double")))

        fun enc(type: Int, v: Any?, good: Boolean = true) = SparkplugEdgeSession.encodable(type, EdgeTagValue(v, good, 0))
        assertEquals(3L, enc(SparkplugCodec.INT32, 2.6))
        assertEquals(1L, enc(SparkplugCodec.INT32, true))
        assertEquals(12L, enc(SparkplugCodec.INT64, " 12 "))
        assertNull(enc(SparkplugCodec.INT32, "x"))
        assertNull(enc(SparkplugCodec.INT32, Double.NaN))
        assertEquals(true, enc(SparkplugCodec.BOOLEAN, 2))
        assertEquals(false, enc(SparkplugCodec.BOOLEAN, "false"))
        assertEquals(1.5f, enc(SparkplugCodec.FLOAT, 1.5))
        assertEquals(42L, enc(SparkplugCodec.DATETIME, Instant.ofEpochMilli(42)))
        assertEquals("7", enc(SparkplugCodec.STRING, 7))
        assertNull(enc(SparkplugCodec.STRING, 7, good = false))
    }

    @Test fun configParsingRejectsReservedNamesWildcardsAndDuplicates() {
        val base = mapOf<String, Any?>("host" to "broker", "groupId" to "G", "edgeNodeId" to "E")
        val parsed = sparkplugEdgeConfig(
            "n1",
            base + mapOf(
                "primaryHostId" to "scada",
                "nodeMetrics" to listOf(mapOf("name" to "Speed", "tag" to "[default]Speed", "writable" to true)),
                "devices" to listOf(mapOf("id" to "D", "metrics" to listOf(mapOf("name" to "a/b", "tag" to "x", "dataType" to "Float")))),
            ),
        )
        assertEquals("oip-edge-n1", parsed.clientId)
        assertEquals("scada", parsed.primaryHostId)
        assertTrue(parsed.nodeMetrics.single().writable)
        assertEquals("Float", parsed.devices.single().metrics.single().dataType)
        assertFalse(parsed.toString().contains("secret"))

        fun bad(spec: Map<String, Any?>) = assertThrows(IllegalArgumentException::class.java) { sparkplugEdgeConfig("n", base + spec) }
        bad(mapOf("groupId" to "a/b"))
        bad(mapOf("edgeNodeId" to "e+"))
        bad(mapOf("primaryHostId" to "#"))
        bad(mapOf("nodeMetrics" to listOf(mapOf("name" to "bdSeq", "tag" to "t"))))
        bad(mapOf("nodeMetrics" to listOf(mapOf("name" to "Node Control/Rebirth", "tag" to "t"))))
        bad(mapOf("nodeMetrics" to listOf(mapOf("name" to "a", "tag" to "t"), mapOf("name" to "a", "tag" to "u"))))
        bad(mapOf("nodeMetrics" to listOf(mapOf("name" to "a", "tag" to "t", "dataType" to "Quaternion"))))
        bad(mapOf("devices" to listOf(mapOf("id" to "D"), mapOf("id" to "D"))))
        bad(mapOf("devices" to listOf(mapOf("id" to "a/b"))))
        bad(mapOf("password" to "secret://p"))
        bad(mapOf("username" to "plain", "password" to "secret://p"))
    }

    @Test fun topicsUseTheSparkplugNamespace() {
        val s = session()
        assertEquals("spBv1.0/Plant/DDEATH/Line3/Filler", s.topic("DDEATH", "Filler"))
        assertEquals("spBv1.0/Plant/NBIRTH/Line3", s.topic("NBIRTH"))
    }

    private fun command(vararg metrics: SparkplugCodec.Metric) =
        SparkplugCodec.encode(SparkplugCodec.Payload(System.currentTimeMillis(), null, metrics.toList()))
}
