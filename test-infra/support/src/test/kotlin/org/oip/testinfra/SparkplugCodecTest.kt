package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets

class SparkplugCodecTest {
    // timestamp=1000, seq=1, metric Value datatype Double = 1.0. Hand-built from the protobuf encoding.
    private val golden = "08e80712120a0556616c7565200a69000000000000f03f1801"

    @Test fun goldenPayloadRoundTrips() {
        val payload = Sparkplug.Payload(
            timestamp = 1000,
            seq = 1,
            metrics = listOf(Sparkplug.Metric("Value", Sparkplug.TYPE_DOUBLE, doubleValue = 1.0)),
        )
        val encoded = Sparkplug.encode(payload)
        assertEquals(golden, encoded.toHex())
        val decoded = Sparkplug.decode(encoded)
        assertEquals(1000, decoded.timestamp)
        assertEquals(1, decoded.seq)
        assertEquals("Value", decoded.metrics.single().name)
        assertEquals(Sparkplug.TYPE_DOUBLE, decoded.metrics.single().datatype)
        assertEquals(1.0, decoded.metrics.single().doubleValue)
    }

    @Test fun otherMetricTypesRoundTrip() {
        val payload = Sparkplug.Payload(
            timestamp = 5,
            seq = 255,
            metrics = listOf(
                Sparkplug.Metric("i", Sparkplug.TYPE_INT32, intValue = 7),
                Sparkplug.Metric("bdSeq", Sparkplug.TYPE_UINT64, longValue = 42),
                Sparkplug.Metric("f", Sparkplug.TYPE_FLOAT, floatValue = 1.5f),
                Sparkplug.Metric("b", Sparkplug.TYPE_BOOLEAN, boolValue = true),
                Sparkplug.Metric("s", Sparkplug.TYPE_STRING, stringValue = "sine"),
            ),
        )
        val decoded = Sparkplug.decode(Sparkplug.encode(payload))
        assertEquals(payload, decoded)
    }

    @Test fun pythonCodecMatchesGolden() {
        val script = File(repoRoot(), "test-infra/sim/sparkplug/sparkplug.py")
        val encode = ProcessBuilder(Python.command(), script.path, "encode-golden").redirectErrorStream(true).start()
        val hex = encode.inputStream.readAllBytes().toString(StandardCharsets.UTF_8).trim()
        assertEquals(0, encode.waitFor(), hex)
        assertEquals(golden, hex)

        val decode = ProcessBuilder(Python.command(), script.path, "decode-hex").redirectErrorStream(true).start()
        decode.outputStream.use { it.write(golden.toByteArray(StandardCharsets.UTF_8)) }
        val json = decode.inputStream.readAllBytes().toString(StandardCharsets.UTF_8).trim()
        assertEquals(0, decode.waitFor(), json)
        assertEquals(true, json.contains("\"timestamp\": 1000") || json.contains("\"timestamp\":1000"), json)
        assertEquals(true, json.contains("\"name\": \"Value\"") || json.contains("\"name\":\"Value\""), json)
        assertEquals(true, json.contains("1.0"), json)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
