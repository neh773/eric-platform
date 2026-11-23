package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TAG_KIND
import org.oip.tags.UDT_KIND
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * 0.1.1 fix: a UDT member of type opc, bound straight to a device address through a UDT parameter, reads the
 * device. Before the fix only top-level OPC tags were subscribed, and members stayed Bad_WaitingForInitialData.
 * The binding also comes back after a gateway restart, and a definition edit moves it to the new address. The
 * member's history block starts collection for the instance member.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class UdtDeviceMemberTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun udtMemberBoundToADeviceAddressReadsTheDevice() {
        TinyModbusSlave().use { slave ->
            slave.holding[0] = 1234
            slave.holding[1] = 77
            val dir = tmp.resolve("gw").createDirectories()
            dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  port: 0\n")
            Gateway(dir, port = 0).use { gw ->
                gw.start()
                gw.store.put(Scope.GATEWAY, Resource("Device", Metadata("ls014", "org.oip.driver-modbus"), deviceSpec(slave.port)), "test")
                gw.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("lift", TAGS_MODULE), udtSpec("1.HR1")), "test")
                gw.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("ls014-station", TAGS_MODULE), mapOf(
                    "provider" to "default",
                    "path" to "Stations/LS014",
                    "type" to "udt",
                    "typeId" to "Types/Lift",
                    "parameters" to mapOf("Device" to "ls014"),
                )), "test")
                awaitValue(gw, "[default]Stations/LS014/Level", 1234)
                awaitCollecting(gw, "[default]Stations/LS014/Level")
                // A definition edit re-expands the instance; the member follows the new address.
                gw.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("lift", TAGS_MODULE, version = gw.store.get(
                    org.oip.config.ResourceId(TAGS_MODULE, Scope.GATEWAY, UDT_KIND, "lift"))!!.metadata.version), udtSpec("1.HR2")), "test")
                awaitValue(gw, "[default]Stations/LS014/Level", 77)
            }
            slave.holding[1] = 78
            Gateway(dir, port = 0).use { gw ->
                gw.start()
                awaitValue(gw, "[default]Stations/LS014/Level", 78)
                awaitCollecting(gw, "[default]Stations/LS014/Level")
            }
        }
    }

    private fun deviceSpec(port: Int) = mapOf(
        "host" to "127.0.0.1", "port" to port, "mode" to "TCP", "unitId" to 1, "zeroBasedAddressing" to false,
        "timeoutMs" to 400, "backoffInitialMs" to 40, "backoffMaxMs" to 200,
    )

    private fun udtSpec(item: String) = mapOf(
        "path" to "Types/Lift",
        "parameters" to listOf(mapOf("name" to "Device", "dataType" to "String")),
        "members" to listOf(
            mapOf("name" to "Level", "type" to "opc", "dataType" to "Int32", "opcServer" to "{Device}", "opcItemPath" to item, "scanPeriodMillis" to 100,
                "history" to mapOf("enabled" to true, "sampleMode" to "onChange", "deadband" to mapOf("mode" to "absolute", "value" to 0.5))),
        ),
    )

    /** The member's own history block (UDT editor, History section) starts collection for each instance member. */
    private fun awaitCollecting(gw: Gateway, path: String) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            if (gw.historyCollector.isCollecting(path)) return
            Thread.sleep(50)
        }
        org.junit.jupiter.api.Assertions.fail<Unit>("history is not collecting $path")
    }

    private fun awaitValue(gw: Gateway, path: String, expected: Int) {
        val end = System.nanoTime() + 15_000_000_000L
        var last: Any? = null
        while (System.nanoTime() < end) {
            val qv = gw.tags.read(path)
            last = "${qv.value} ${qv.quality}"
            if (qv.quality.level == org.oip.tags.Level.GOOD && (qv.value as? Number)?.toInt() == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected.toString(), last.toString(), "member $path never read the device")
    }
}
