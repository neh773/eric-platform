package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.modbus.ModbusDriverModule
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 0.1.1 fix: a Modbus device saved in the admin UI is still there, and running, after the gateway restarts. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DeviceRestartTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun modbusDeviceSurvivesARestart() {
        val dir = tmp.resolve("gw")
        val id = ResourceId(ModbusDriverModule.MODULE_ID, Scope.GATEWAY, "Device", "ls014")
        Gateway(dir, port = 0).use { gw ->
            gw.start()
            gw.store.put(
                Scope.GATEWAY,
                Resource("Device", Metadata("ls014", ModbusDriverModule.MODULE_ID), mapOf("host" to "127.0.0.1", "port" to 1502, "unitId" to 1)),
                "test",
            )
            assertNotNull(gw.store.get(id))
        }
        Gateway(dir, port = 0).use { gw ->
            gw.start()
            assertNotNull(gw.store.get(id), "device config is still in the store after a restart")
            val modbus = gw.host.instance(ModbusDriverModule.MODULE_ID) as ModbusDriverModule
            assertNotNull(modbus.device("ls014"), "the driver opened the device again")
            assertEquals("127.0.0.1", gw.store.get(id)!!.spec["host"])
        }
    }
}
