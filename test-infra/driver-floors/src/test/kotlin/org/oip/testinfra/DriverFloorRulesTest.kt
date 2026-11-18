package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.modbus.ModbusAddress
import org.oip.modbus.ModbusDeviceConfig
import org.oip.modbus.RequestOptimizer
import java.net.InetAddress

/**
 * Checks that do not open a socket. The rate floors run in [DriverFloorTest] on a container bridge.
 */
class DriverFloorRulesTest {
    @Test fun loopbackIsNotADriverFloorPath() {
        assertTrue(InetAddress.getByName("127.0.0.1").isLoopbackAddress)
        assertTrue(InetAddress.getByName("localhost").isLoopbackAddress)
        assertFalse(InetAddress.getByName("172.18.0.2").isLoopbackAddress)
    }

    @Test fun fifteenPercentWorseDoesNotFailAndSixteenDoes() {
        assertFalse(regressionFails(10_000.0, 8_500.0, lowerIsBetter = false))
        assertTrue(regressionFails(10_000.0, 8_499.0, lowerIsBetter = false))
        assertFalse(regressionFails(50_000.0, 50_000.0, lowerIsBetter = false))
        assertFalse(regressionFails(0.0103, 0.011845, lowerIsBetter = true))
        assertTrue(regressionFails(0.0103, 0.011846, lowerIsBetter = true))
    }

    @Test fun tenThousandHoldingRegistersCoalesceIntoFullPdus() {
        val config = ModbusDeviceConfig(
            name = "plan", host = "172.18.0.2", port = 5020,
            spanGaps = true, maxHoldingRegistersPerRead = 125, concurrentRequests = 8,
        )
        val items = (1..10_000).map { ModbusAddress.parse("HR$it", config) }
        val plan = RequestOptimizer.plan(items, config)
        assertTrue(plan.rejected.isEmpty())
        assertEquals(80, plan.blocks.size)
        assertEquals(10_000, plan.blocks.sumOf { it.items.size })
        assertTrue(plan.blocks.all { it.quantity == 125 && it.items.size == 125 })
    }
}

/** Positive when [measured] is worse. Throughput falling is worse. A CPU fraction rising is worse. */
internal fun regressionFails(baseline: Double, measured: Double, lowerIsBetter: Boolean): Boolean {
    val change = (measured - baseline) / kotlin.math.abs(baseline) * 100.0
    val worse = if (lowerIsBetter) change else -change
    return worse > 15.0
}
