package org.oip.opcua.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeviceNodeIdsTest {
    @Test fun rootDeviceAndItemRoundTrip() {
        assertEquals(DeviceLoc.Root, DeviceNodeIds.parse("Devices"))
        assertEquals(DeviceLoc.Device("mb"), DeviceNodeIds.parse(DeviceNodeIds.device("mb")))
        val item = DeviceNodeIds.item("opc", "ns=2;s=Sim.Offset")
        assertEquals(DeviceLoc.Item("opc", "ns=2;s=Sim.Offset"), DeviceNodeIds.parse(item))
        val nested = DeviceNodeIds.item("opc", "ns=2;s=Sim/Offset")
        assertEquals(DeviceLoc.Item("opc", "ns=2;s=Sim/Offset"), DeviceNodeIds.parse(nested))
    }

    @Test fun aSlashInTheDeviceNameStaysInTheDeviceSegment() {
        val id = DeviceNodeIds.device("line/a")
        assertEquals(DeviceLoc.Device("line/a"), DeviceNodeIds.parse(id))
        val item = DeviceNodeIds.item("line/a", "HR1")
        assertEquals(DeviceLoc.Item("line/a", "HR1"), DeviceNodeIds.parse(item))
        assertEquals("100%", DeviceNodeIds.decode(DeviceNodeIds.encode("100%")))
    }

    @Test fun malformedIdsAreNotNodes() {
        assertNull(DeviceNodeIds.parse("Tags"))
        assertNull(DeviceNodeIds.parse("Devices/"))
        assertNull(DeviceNodeIds.parse("Devices/mb/"))
        assertNull(DeviceNodeIds.parse(""))
    }
}
