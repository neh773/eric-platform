package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.DataType
import org.oip.tags.QualityCode
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

class PlcBridgeTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `header matches the scan engine prefix`() {
        val path = dir.resolve("image")
        val image = PlcImage.open(path, 1, 1, 10_000_000)
        val prefix = path.readBytes().copyOf(16)
        assertArrayEquals(
            byteArrayOf(0x45, 0x52, 0x50, 0x43, 1, 0, 0, 0, 128.toByte(), 0, 0, 0, 32, 0, 0, 0),
            prefix,
        )
        image.writeInputs(listOf(PlcSlot(2, 0, 42, 42)))
        val back = arrayOf(PlcSlot(0, 0, 0, 0))
        assertTrue(image.readInputs(back))
        assertEquals(42, back[0].value)
        assertEquals(42, back[0].sourceNs)
        image.close()
    }

    @Test
    fun `outputs follow the engine and go safe when the heartbeat stops`() {
        val mapping = dir.resolve("mapping.json")
        mapping.writeText(
            """
            {"inputs":[{"address":"%IX0.0","tag":"plc/start","type":"BOOL"}],
             "outputs":[{"address":"%QX1.0","tag":"plc/motor","type":"BOOL","safe":false}]}
            """.trimIndent(),
        )
        val imagePath = dir.resolve("image")
        val tags = TagEngine()
        tags.configure(TagConfig(path = "plc/start", type = TagType.MEMORY, dataType = DataType.BOOLEAN, value = false, valueSet = true))
        tags.configure(TagConfig(path = "plc/motor", type = TagType.MEMORY, dataType = DataType.BOOLEAN, value = false, valueSet = true))
        val bridge = PlcBridge.open(
            tags,
            PlcSettings(enabled = true, image = imagePath.toString(), mapping = mapping.toString(), publishMillis = 5, bridgeTimeoutMillis = 40),
        )
        bridge.start()
        try {
            tags.write("plc/start", true)
            val input = arrayOf(PlcSlot(0, 0, 0, 0))
            waitUntil(500) { bridge.image.readInputs(input) && input[0].value == 1L }
            bridge.image.setState(PlcImage.STATE_RUNNING)
            bridge.image.setPlcHeartbeat(System.currentTimeMillis() * 1_000_000L)
            bridge.image.writeOutputs(listOf(PlcSlot(1, 0, 1, 0)))
            waitUntil(500) {
                val sample = tags.read("plc/motor")
                sample.value == true && sample.quality.code == QualityCode.GOOD
            }
            bridge.image.setPlcHeartbeat(1)
            waitUntil(500) {
                val sample = tags.read("plc/motor")
                sample.value == false && sample.quality.code == QualityCode.BAD_NOT_CONNECTED
            }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun `settings accept a plc block and refuse an unknown key`() {
        val file = dir.resolve("gateway.yaml")
        file.writeText("profile: standard\n")
        assertFalse(GatewaySettings.load(file).plc.enabled)
        file.writeText(
            """
            profile: standard
            plc:
              enabled: true
              image: /dev/shm/eric-plc-test
              mapping: mapping.json
              publishMillis: 10
              bridgeTimeoutMillis: 100
            """.trimIndent(),
        )
        val loaded = GatewaySettings.load(file)
        assertTrue(loaded.plc.enabled)
        assertEquals("/dev/shm/eric-plc-test", loaded.plc.image)
        file.writeText("profile: standard\nplc:\n  nope: 1\n")
        assertThrows(IllegalArgumentException::class.java) { GatewaySettings.load(file) }
    }

    private fun waitUntil(timeoutMs: Long, pred: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < end) {
            if (pred()) return
            Thread.sleep(5)
        }
        throw AssertionError("condition was not met within ${timeoutMs}ms")
    }
}
