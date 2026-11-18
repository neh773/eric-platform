package org.oip.tags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * The `[system]` provider is read-only to users. The gateway can still own a tag there, such as the reference tag that
 * carries an enterprise-administration threshold alarm (EAM-017). Doc source: FEATURES EAM-017.
 */
class SystemProviderConfigureTest {
    private val engine = TagEngine(Clock.systemUTC())

    private fun reference() = TagConfig.fromSpec(linkedMapOf(
        "provider" to "system",
        "path" to "Gateway/EAM/Alarms/a/Cpu",
        "type" to "reference",
        "dataType" to "Boolean",
        "sourcePath" to "[system]Gateway/EAM/Agents/a/Thresholds/Cpu",
    ))

    @Test fun gatewayOwnedReferenceTagFollowsAPublishedSystemValue() {
        engine.publishSystem("Gateway/EAM/Agents/a/Thresholds/Cpu", false, DataType.BOOLEAN)
        engine.configureSystem(reference(), "eam", "EamThreshold")
        assertEquals(false, engine.read("[system]Gateway/EAM/Alarms/a/Cpu").value)
        engine.publishSystem("Gateway/EAM/Agents/a/Thresholds/Cpu", true, DataType.BOOLEAN)
        assertEquals(true, engine.read("[system]Gateway/EAM/Alarms/a/Cpu").value)
        assertEquals(Quality.GOOD.code, engine.read("[system]Gateway/EAM/Alarms/a/Cpu").quality.code)
    }

    @Test fun usersStillCannotConfigureOrWriteSystemTags() {
        assertThrows(TagConfigException::class.java) { engine.configure(reference()) }
        engine.configureSystem(reference(), "eam", "EamThreshold")
        assertEquals(QualityCode.BAD_NOT_WRITABLE, engine.write("[system]Gateway/EAM/Alarms/a/Cpu", false).quality.code)
        assertThrows(IllegalArgumentException::class.java) {
            engine.configureSystem(TagConfig.fromSpec(linkedMapOf("path" to "x", "type" to "memory")), "eam", "EamThreshold")
        }
    }
}
