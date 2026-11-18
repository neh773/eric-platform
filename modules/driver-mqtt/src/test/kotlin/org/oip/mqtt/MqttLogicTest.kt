package org.oip.mqtt

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MqttLogicTest {
    @Test fun standbyDoesNotPublishCommands() {
        val connection = MqttConnection(
            MqttConnectionConfig(name = "c", host = "127.0.0.1", clientId = "c"),
            secrets = { null },
        )
        try {
            connection.setPublishing(false)
            val blocked = connection.write("number:plant/level", 1)
            assertEquals(Quality.NOT_WRITABLE.statusCode, blocked.quality.statusCode)
            connection.setPublishing(true)
            val down = connection.write("number:plant/level", 1)
            assertEquals(Quality.NOT_CONNECTED.statusCode, down.quality.statusCode)
        } finally {
            connection.close()
        }
    }

    @Test fun connectPacketMatchesMqtt311() {
        val packet = MqttWire.encodeConnect(
            MqttConnectSpec(MqttProtocol.MQTT_3_1_1, "oip", 60, cleanSession = true, username = null, password = null, will = null),
        )
        assertEquals("100f00044d5154540402003c00036f6970", packet.toHex())
    }

    @Test fun connectCarriesPasswordBytesAndMqtt5SessionExpiry() {
        val password = "s3cret-pass-mqtt-9f3a".encodeToByteArray()
        val v311 = MqttWire.encodeConnect(
            MqttConnectSpec(MqttProtocol.MQTT_3_1_1, "edge", 10, true, "line-user", password, MqttWill("will/t", "bye".encodeToByteArray(), 1, true)),
        )
        val parsed = parseConnect(v311)
        assertEquals(4, parsed.level)
        assertEquals("line-user", parsed.username)
        assertArrayEquals(password, parsed.password)
        assertEquals("will/t", parsed.willTopic)
        assertArrayEquals("bye".encodeToByteArray(), parsed.willPayload)
        assertTrue(parsed.clean)
        assertEquals(1, parsed.willQos)
        assertTrue(parsed.willRetain)

        val v5 = MqttWire.encodeConnect(
            MqttConnectSpec(MqttProtocol.MQTT_5, "edge", 10, true, null, null, null),
        )
        val mqtt5 = parseConnect(v5)
        assertEquals(5, mqtt5.level)
        assertEquals(0, mqtt5.sessionExpiry)
    }

    @Test fun numberStringAndJsonPointerRoundTrip() {
        val number = Addresses.parsePlain("number:plant/flow")!!
        val decoded = PayloadMap.decode(number, "42".encodeToByteArray(), null)
        assertEquals(42, decoded.value)
        assertArrayEquals("42".encodeToByteArray(), PayloadMap.encode(number, 42, null))

        val text = Addresses.parsePlain("string:plant/note")!!
        assertEquals("hello", PayloadMap.decode(text, "hello".encodeToByteArray(), null).value)
        assertArrayEquals("hello".encodeToByteArray(), PayloadMap.encode(text, "hello", null))

        val doc = Addresses.parsePlain("json:plant/line#/temp")!!
        val body = """{"temp":12.5,"name":"a/b"}""".encodeToByteArray()
        val value = PayloadMap.decode(doc, body, null)
        assertEquals(12.5, value.value)
        val merged = PayloadMap.encode(doc, 9, value.document)
        assertEquals(9, PayloadMap.decode(doc, merged, null).value)
        val escaped = Addresses.parsePlain("json:plant/line#/name")!!
        assertEquals("a/b", PayloadMap.decode(escaped, body, null).value)
        val pointer = Addresses.parsePlain("json:plant/line#/a~1b")!!
        val slash = PayloadMap.decode(pointer, """{"a/b":7}""".encodeToByteArray(), null)
        assertEquals(7, slash.value)
    }

    @Test fun sparkplugAddressRoundTripAndTagPath() {
        val node = Addresses.parseSparkplug("spb:G/E//Node Control/Rebirth")!!
        assertEquals("", node.device)
        assertEquals("Node Control/Rebirth", node.metric)
        assertEquals("spBv1.0/G/NCMD/E", node.commandTopic)
        val device = Addresses.parseSparkplug("spb:OIPBENCH/Bench/D0/P0000000")!!
        assertEquals("D0", device.device)
        assertEquals("spBv1.0/OIPBENCH/DCMD/Bench/D0", device.commandTopic)
        assertEquals(
            "mqtt/line/G/E/Pump 1/Speed",
            sparkplugTagPath("line", "G", "E", "Pump 1", "Speed"),
        )
        assertEquals(
            "mqtt/line/G/E/Node Control/Rebirth",
            sparkplugTagPath("line", "G", "E", "", "Node Control/Rebirth"),
        )
        val name = sparkplugResourceName("line", device.address())
        assertTrue(name.matches(Regex("m[0-9a-f]{20}")))
        assertEquals(name, sparkplugResourceName("line", device.address()))
    }

    @Test fun sparkplugIntMetricGoldenBytesRoundTrip() {
        val metric = SparkplugCodec.Metric("V", null, SparkplugCodec.INT32, null, false, 7)
        val payload = SparkplugCodec.Payload(1000, 1, listOf(metric))
        val encoded = SparkplugCodec.encode(payload)
        assertEquals("08e80712070a0156200350071801", encoded.toHex())
        val decoded = SparkplugCodec.decode(encoded)
        assertEquals(1000, decoded.timestamp)
        assertEquals(1, decoded.seq)
        assertEquals(7, decoded.metrics.single().value)
        val withAlias = SparkplugCodec.encode(
            SparkplugCodec.Payload(0, 0, listOf(SparkplugCodec.Metric("V", 10, SparkplugCodec.INT32, null, false, 7))),
        )
        assertEquals(10L, SparkplugCodec.decode(withAlias).metrics.single().alias)
        val floatBits = SparkplugCodec.encode(
            SparkplugCodec.Payload(0, null, listOf(SparkplugCodec.Metric("F", null, SparkplugCodec.FLOAT, null, false, 1.0f))),
        )
        assertEquals(1.0f, SparkplugCodec.decode(floatBits).metrics.single().value)
        val negative = SparkplugCodec.encode(
            SparkplugCodec.Payload(0, 0, listOf(SparkplugCodec.Metric("N", null, SparkplugCodec.INT32, null, false, -1))),
        )
        assertEquals(-1, SparkplugCodec.decode(negative).metrics.single().value)
        val absent = SparkplugCodec.decode(encoded + byteArrayOf(0x20, 0x00))
        assertEquals(7, absent.metrics.single().value)
    }

    @Test fun sequenceAppliesInOrderFillsGapsAndRequestsRebirthOnce() {
        val session = SparkplugSession(reorderTimeoutMs = 1_000)
        val effects = session.onMessage("spBv1.0/G/NBIRTH/E", birth(0, metric("V", 1, alias = 10), bdSeq(4)), 0)
        assertEquals(1, samples(effects).first { it.metric == "V" }.value)
        assertEquals(0, effects.filterIsInstance<SpEffect.Rebirth>().size)

        val first = session.onMessage("spBv1.0/G/NDATA/E", data(1, aliasOnly(10, 2)), 10)
        assertEquals(2, samples(first).single().value)

        val gap = session.onMessage("spBv1.0/G/NDATA/E", data(3, aliasOnly(10, 9)), 20)
        assertTrue(samples(gap).isEmpty())
        assertTrue(gap.filterIsInstance<SpEffect.Rebirth>().isEmpty())

        val filled = session.onMessage("spBv1.0/G/NDATA/E", data(2, aliasOnly(10, 3)), 30)
        assertEquals(listOf(3, 9), samples(filled).map { it.value })
        assertTrue(session.poll(500).isEmpty())

        session.onMessage("spBv1.0/G/NDATA/E", data(5, aliasOnly(10, 8)), 40)
        assertTrue(session.poll(1_039).isEmpty())
        val rebirths = session.poll(1_040)
        assertEquals(1, rebirths.filterIsInstance<SpEffect.Rebirth>().size)
        assertEquals("E", rebirths.filterIsInstance<SpEffect.Rebirth>().single().edge)
        assertTrue(session.poll(5_000).isEmpty())

        val again = session.onMessage("spBv1.0/G/NBIRTH/E", birth(0, metric("V", 1, alias = 10), bdSeq(4)), 6_000)
        assertTrue(again.filterIsInstance<SpEffect.Rebirth>().isEmpty())
        val resumed = session.onMessage("spBv1.0/G/NDATA/E", data(1, aliasOnly(10, 7)), 6_010)
        assertEquals(7, samples(resumed).single().value)
    }

    @Test fun oldSequenceIsIgnoredAndBenchDeviceBirthStillApplies() {
        val session = SparkplugSession(reorderTimeoutMs = 50)
        session.onMessage("spBv1.0/G/NBIRTH/E", birth(0, metric("V", 1), bdSeq(1)), 0)
        val old = session.onMessage("spBv1.0/G/NDATA/E", data(0, metric("V", 99)), 10)
        assertTrue(samples(old).isEmpty())
        assertTrue(old.filterIsInstance<SpEffect.Rebirth>().isEmpty())
        val device = session.onMessage("spBv1.0/G/DBIRTH/E/D0", birth(0, metric("P0000000", 0)), 20)
        assertEquals(0, samples(device).single { it.metric == "P0000000" }.value)
        assertTrue(session.poll(1_000).isEmpty())
    }

    @Test fun nonZeroBirthWrapsAndUnknownAliasRequestsRebirth() {
        val session = SparkplugSession(1_000)
        val badBirth = session.onMessage("spBv1.0/G/NBIRTH/E", birth(254, metric("V", 3, alias = 2)), 0)
        assertEquals(3, samples(badBirth).single { it.metric == "V" }.value)
        assertEquals(1, badBirth.filterIsInstance<SpEffect.Rebirth>().size)
        val around = session.onMessage("spBv1.0/G/NDATA/E", data(255, aliasOnly(2, 6)), 10)
        assertEquals(6, samples(around).single().value)
        val next = session.onMessage("spBv1.0/G/NDATA/E", data(0, aliasOnly(2, 7)), 20)
        assertEquals(7, samples(next).single().value)
        val fresh = SparkplugSession(1_000)
        fresh.onMessage("spBv1.0/G/NBIRTH/E", birth(0, metric("V", 1, alias = 2)), 0)
        val unknown = fresh.onMessage("spBv1.0/G/NDATA/E", data(1, aliasOnly(99, 1)), 30)
        assertTrue(samples(unknown).isEmpty())
        assertEquals(1, unknown.filterIsInstance<SpEffect.Rebirth>().size)
    }

    @Test fun deathUsesBdSeqAndDeviceDeathIsStale() {
        val session = SparkplugSession(1_000)
        session.onMessage(
            "spBv1.0/G/NBIRTH/E",
            birth(0, metric("Node Control/Rebirth", 0).let {
                SparkplugCodec.Metric(it.name, null, SparkplugCodec.BOOLEAN, null, false, false)
            }, bdSeq(9), metric("Speed", 4)),
            100,
        )
        val born = session.onMessage("spBv1.0/G/DBIRTH/E/Pump", birth(1, metric("Flow", 5)), 100)
        assertEquals(0L, born.filterIsInstance<SpEffect.Sample>().first { it.metric == "Flow" }.statusCode)
        val death = session.onMessage("spBv1.0/G/DDEATH/E/Pump", birth(2), 200)
        val stale = samples(death).single { it.metric == "Flow" }
        assertEquals(SparkplugCodec.STALE, stale.statusCode)
        assertEquals(5, stale.value)
        val ignored = session.onMessage("spBv1.0/G/NDEATH/E", birth(0, bdSeq(3)), 300)
        assertTrue(ignored.isEmpty())
        val node = session.onMessage("spBv1.0/G/NDEATH/E", birth(0, bdSeq(9)), 400)
        assertTrue(samples(node).any { it.metric == "Speed" && it.statusCode == SparkplugCodec.STALE })
        assertTrue(samples(node).any { it.metric == "Flow" && it.statusCode == SparkplugCodec.STALE })
        val early = session.onMessage("spBv1.0/G/NDATA/E", data(0, metric("Speed", 1)), 500)
        assertTrue(samples(early).isEmpty())
        assertEquals(1, early.filterIsInstance<SpEffect.Rebirth>().size)
    }

    @Test fun configToStringOmitsSecrets() {
        val config = mqttConnectionConfig(
            "line",
            mapOf(
                "host" to "127.0.0.1",
                "username" to "secret://mqtt-user",
                "password" to "secret://mqtt-pass",
                "sparkplugHostId" to "primary",
                "cleanSession" to false,
            ),
        )
        assertTrue(config.cleanSession)
        assertFalse(config.toString().contains("secret"))
        assertFalse(config.toString().contains("mqtt-pass"))
    }

    private fun samples(effects: List<SpEffect>) = effects.filterIsInstance<SpEffect.Sample>()

    private fun metric(name: String, value: Int, alias: Long? = null) =
        SparkplugCodec.Metric(name, alias, SparkplugCodec.INT32, null, false, value)

    private fun aliasOnly(alias: Long, value: Int) =
        SparkplugCodec.Metric("", alias, SparkplugCodec.INT32, null, false, value)

    private fun bdSeq(value: Long) =
        SparkplugCodec.Metric("bdSeq", null, SparkplugCodec.UINT64, null, false, value)

    private fun birth(seq: Int, vararg metrics: SparkplugCodec.Metric) =
        SparkplugCodec.encode(SparkplugCodec.Payload(1_000, seq, metrics.toList()))

    private fun data(seq: Int, vararg metrics: SparkplugCodec.Metric) = birth(seq, *metrics)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private class ParsedConnect(
        val level: Int,
        val clean: Boolean,
        val username: String?,
        val password: ByteArray?,
        val willTopic: String?,
        val willPayload: ByteArray?,
        val willQos: Int,
        val willRetain: Boolean,
        val sessionExpiry: Int?,
    )

    private fun parseConnect(packet: ByteArray): ParsedConnect {
        assertEquals(0x10, packet[0].toInt() and 0xFF)
        var index = 1
        var multiplier = 1
        var remaining = 0
        while (true) {
            val encoded = packet[index].toInt() and 0xFF
            index += 1
            remaining += (encoded and 0x7F) * multiplier
            if (encoded and 0x80 == 0) break
            multiplier *= 128
        }
        val body = packet.copyOfRange(index, index + remaining)
        var at = 0
        val (protocol, nextName) = MqttWire.readMqttString(body, at)
        at = nextName
        assertEquals("MQTT", protocol)
        val level = body[at].toInt() and 0xFF
        at += 1
        val flags = body[at].toInt() and 0xFF
        at += 1
        at += 2
        var sessionExpiry: Int? = null
        if (level == 5) {
            val (length, next) = readVarint(body, at)
            at = next
            val end = at + length.toInt()
            if (length >= 5 && body[at].toInt() == 0x11) sessionExpiry = 0
            at = end
        }
        val (clientId, afterId) = MqttWire.readMqttString(body, at)
        at = afterId
        assertEquals(clientId.isNotEmpty(), true)
        var willTopic: String? = null
        var willPayload: ByteArray? = null
        if (flags and 0x04 != 0) {
            if (level == 5) {
                val (length, next) = readVarint(body, at)
                at = next + length.toInt()
            }
            val topic = MqttWire.readMqttString(body, at)
            willTopic = topic.first
            at = topic.second
            val payload = MqttWire.readPrefixed(body, at)
            willPayload = payload.first
            at = payload.second
        }
        var username: String? = null
        if (flags and 0x80 != 0) {
            val user = MqttWire.readMqttString(body, at)
            username = user.first
            at = user.second
        }
        var password: ByteArray? = null
        if (flags and 0x40 != 0) {
            val secret = MqttWire.readPrefixed(body, at)
            password = secret.first
        }
        return ParsedConnect(
            level,
            flags and 0x02 != 0,
            username,
            password,
            willTopic,
            willPayload,
            (flags shr 3) and 0x03,
            flags and 0x20 != 0,
            sessionExpiry,
        )
    }

    private fun readVarint(body: ByteArray, start: Int): Pair<Long, Int> {
        var index = start
        var shift = 0
        var value = 0L
        while (true) {
            val byte = body[index].toLong() and 0xFF
            index += 1
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80L == 0L) return value to index
            shift += 7
        }
    }
}
