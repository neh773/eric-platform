package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * GAT-004: the edge profile is a reduced gateway: loopback by default, local drivers, capped device count and local
 * storage, and optional upstream sync. Caps come from the profile and gateway.yaml overrides them.
 * Doc source: FEATURES GAT-004.
 */
class EdgeProfileTest {
    @TempDir lateinit var tmp: Path

    private fun device(name: String, version: Int = 0) = """
        {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"$name","module":"org.oip.driver-modbus","version":$version},
         "spec":{"name":"$name","host":"127.0.0.1","port":1,"mode":"TCP","unitId":1,"enabled":false}}
    """.trimIndent()

    private fun <T> run(yaml: String, block: (Gateway, Http) -> T): T {
        tmp.resolve("gateway.yaml").writeText(yaml)
        Gateway(tmp, port = 0).start().use { gw ->
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val http = Http(gw.port())
            assertEquals(200, http.login("admin", TEST_PASSWORD).status)
            return block(gw, http)
        }
    }

    @Test fun edgeProfileCarriesDefaultCapsAndOtherProfilesHaveNone() {
        tmp.resolve("gateway.yaml").writeText("profile: edge\n")
        Gateway(tmp, port = 0).use {
            assertEquals(50, it.settings.limits.maxDevices)
            assertEquals(2048, it.settings.limits.maxStorageMb)
        }
        tmp.resolve("gateway.yaml").writeText("profile: standard\n")
        Gateway(tmp, port = 0).use { assertTrue(!it.settings.limits.any) }
    }

    @Test fun gatewayYamlOverridesOneCapAndRejectsUnknownKeys() {
        tmp.resolve("gateway.yaml").writeText("profile: edge\nlimits:\n  maxDevices: 3\n")
        Gateway(tmp, port = 0).use {
            assertEquals(3, it.settings.limits.maxDevices)
            assertEquals(2048, it.settings.limits.maxStorageMb)
        }
        tmp.resolve("gateway.yaml").writeText("profile: edge\nlimits:\n  maxTags: 3\n")
        assertThrows<IllegalArgumentException> { Gateway(tmp, port = 0) }
    }

    @Test fun theDeviceCapRefusesTheNextDeviceButAllowsEditsAndDeletes() {
        run("profile: edge\nopcua:\n  enabled: false\nlimits:\n  maxDevices: 2\n") { _, http ->
            assertEquals(201, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/a", device("a")).status)
            assertEquals(201, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/b", device("b")).status)
            val over = http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/c", device("c"))
            assertEquals(409, over.status, over.body)
            assertTrue(over.json["type"].toString().endsWith("limit-reached"), over.body)
            assertEquals(200, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/a", device("a", 1)).status)
            val batch = http.post("/api/v1/config/batch", """{"items":[${device("d")}]}""")
            assertEquals(409, batch.status, batch.body)
            assertEquals(204, http.delete("/api/v1/config/org.oip.driver-modbus/gateway/Device/b").status)
            assertEquals(201, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/c", device("c")).status)
            val env = http.get("/api/v1/diagnostics/environment").json["limits"] as Map<*, *>
            assertEquals(2, env["maxDevices"]); assertEquals(2, env["devices"])
        }
    }

    @Test fun theStorageCapRefusesNewResourcesWithInsufficientStorage() {
        run("profile: edge\nopcua:\n  enabled: false\nlimits:\n  maxStorageMb: 20\n  maxDevices: 0\n") { gw, http ->
            assertTrue(gw.limitGuard.storageBytes() < 20L * 1024 * 1024, "baseline data dir is under the cap")
            assertEquals(201, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/a", device("a")).status)
            java.nio.file.Files.write(tmp.resolve("filler.bin"), ByteArray(21 * 1024 * 1024))
            Thread.sleep(2_100)
            val full = http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/b", device("b"))
            assertEquals(507, full.status, full.body)
            assertEquals(200, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/a", device("a", 1)).status)
            assertEquals(204, http.delete("/api/v1/config/org.oip.driver-modbus/gateway/Device/a").status)
            assertTrue(gw.limitGuard.storageBytes() > 0)
        }
    }

    @Test fun anUncappedProfileAcceptsDevicesFreely() {
        run("profile: standard\nopcua:\n  enabled: false\n") { _, http ->
            for (n in listOf("a", "b", "c", "d")) {
                assertEquals(201, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/$n", device(n)).status)
            }
        }
    }
}
