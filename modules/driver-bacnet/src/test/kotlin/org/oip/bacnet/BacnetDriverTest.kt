package org.oip.bacnet

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import org.oip.host.ModuleHost
import org.oip.host.ModuleState
import org.oip.sim.bacnet.BacnetSimulator
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The peer is the in-repo BACnet/IP device simulator (test-infra/sim/bacnet), whose encoder is written
 * separately from the driver's. Everything runs on loopback UDP.
 * doc source: FEATURES DEV-011, DEV-012, DEV-038
 */
@Timeout(60)
class BacnetDriverTest {
    private fun sim(instance: Int = 260001): BacnetSimulator = BacnetSimulator(instance, deviceName = "AHU-1").apply {
        add(BacnetSimulator.ANALOG_INPUT, 0, "Supply Temp", 72.5f).units = 64
        add(BacnetSimulator.ANALOG_OUTPUT, 1, "Damper", 0.0f)
        add(BacnetSimulator.BINARY_INPUT, 2, "Fan Status", 1L)
        add(BacnetSimulator.BINARY_OUTPUT, 3, "Fan Command", 0L)
        add(BacnetSimulator.MULTI_STATE_VALUE, 4, "Mode", 2L)
    }

    private fun local(instance: Int = 1001, timeoutMs: Long = 200, retries: Int = 1, maxOutstanding: Int = 32, maxQueued: Int = 256) =
        BacnetLocalDevice(LocalDeviceConfig(
            "gw", instance, "Gateway $instance", vendorId = 7, bindAddress = "127.0.0.1", port = 0,
            broadcastAddress = "127.0.0.1", apduTimeoutMs = timeoutMs, apduRetries = retries,
            maxOutstanding = maxOutstanding, maxQueued = maxQueued,
        )).also { it.start() }

    private fun remote(sim: BacnetSimulator, local: BacnetLocalDevice, host: String? = "127.0.0.1", maxProps: Int = 20) =
        BacnetRemoteDevice(
            RemoteDeviceConfig("ahu", "gw", sim.deviceInstance, host = host, port = sim.port, reconnectMs = 100, maxPropertiesPerRequest = maxProps),
        ) { if (it == "gw") local else null }

    private fun value(device: BacnetRemoteDevice, address: String) = device.read(listOf(address)).single()

    @Test fun discoversByBroadcastWhoIsAndBrowsesTheObjectList() {
        sim().use { sim ->
            local().use { gw ->
                remote(sim, gw, host = null).use { ahu ->
                    ahu.start()
                    awaitConnected(ahu)
                    assertEquals(sim.port, ahu.address!!.socket.port)
                    assertTrue(sim.whoIsCount.get() >= 1)

                    val root = ahu.browse("")
                    assertEquals(listOf("analog-input:0", "analog-output:1", "binary-input:2", "binary-output:3", "device:260001", "multi-state-value:4"), root.map { it.nodeId })
                    assertEquals("Supply Temp", root.first().displayName)
                    assertEquals("AHU-1", root.single { it.nodeId == "device:260001" }.displayName)

                    val items = ahu.browse("analog-output:1")
                    assertTrue(items.any { it.nodeId == "analog-output:1/PresentValue" && it.writable })
                    assertTrue(items.any { it.nodeId == "analog-output:1/RelinquishDefault" })
                    assertTrue(items.any { it.nodeId == "analog-output:1/StatusFlags" && it.nodeClass == "Object" })
                    // An input's present value is listed writable because it can be written out of service.
                    assertTrue(ahu.browse("analog-input:0").single { it.nodeId.endsWith("/PresentValue") }.writable)
                    assertFalse(ahu.browse("analog-input:0").single { it.nodeId.endsWith("/ObjectName") }.writable)
                    assertFalse(ahu.browse("analog-input:0/StatusFlags").any { it.writable })
                    assertEquals(
                        listOf("InAlarm", "Fault", "Overridden", "OutOfService"),
                        ahu.browse("analog-input:0/StatusFlags").map { it.displayName },
                    )
                    assertTrue(ahu.browse("device:260001").any { it.nodeId == "device:260001/VendorIdentifier" })
                    assertEquals("Damper", ahu.describe("analog-output:1")!!.displayName)
                    assertNull(ahu.describe("bogus"))
                }
            }
        }
    }

    @Test fun readsPropertiesAndStatusFlagsWithReadPropertyMultiple() {
        sim().use { sim ->
            local().use { gw ->
                remote(sim, gw).use { ahu ->
                    ahu.start(); awaitConnected(ahu)
                    val rpmBefore = sim.readPropertyMultipleCount.get()
                    val values = ahu.read(listOf(
                        "analog-input:0/PresentValue", "analog-input:0/Units", "binary-input:2/PresentValue",
                        "multi-state-value:4/PresentValue", "analog-input:0/StatusFlags/Fault", "device:260001/VendorIdentifier",
                        "analog-input:0/ObjectName", "analog-input:9/PresentValue", "nonsense",
                    ))
                    assertEquals(sim.readPropertyMultipleCount.get(), rpmBefore + 1)
                    assertEquals(72.5, values[0].value)
                    assertEquals(64L, values[1].value)
                    assertEquals(true, values[2].value)
                    assertEquals(2L, values[3].value)
                    assertEquals(false, values[4].value)
                    assertEquals(999L, values[5].value)
                    assertEquals("Supply Temp", values[6].value)
                    for (i in 0..6) assertEquals(QualityCode.Good, values[i].quality.code, values[i].toString())
                    assertEquals(QualityCode.Bad_NotFound, values[7].quality.code)
                    assertEquals(QualityCode.Bad_NotFound, values[8].quality.code)

                    sim.obj(BacnetSimulator.ANALOG_INPUT, 0).apply { fault = true; outOfService = true }
                    val flags = ahu.read(BacnetItem.STATUS_BITS.map { "analog-input:0/${it.path}" }).map { it.value }
                    assertEquals(listOf(false, true, false, true), flags)
                    assertEquals(true, value(ahu, "analog-input:0/OutOfService").value)
                    assertTrue(ahu.usesReadPropertyMultiple)
                }
            }
        }
    }

    @Test fun fallsBackToReadPropertyWhenReadPropertyMultipleIsNotSupported() {
        sim().use { sim ->
            sim.supportsReadPropertyMultiple = false
            local().use { gw ->
                remote(sim, gw).use { ahu ->
                    ahu.start(); awaitConnected(ahu)
                    assertEquals(6, ahu.objects().size)
                    assertEquals("Mode", ahu.objects()[ObjectId(BacnetObjectType.MULTI_STATE_VALUE, 4)])
                    assertFalse(ahu.usesReadPropertyMultiple)
                    val before = sim.readPropertyCount.get()
                    val values = ahu.read(listOf("analog-input:0/PresentValue", "binary-output:3/PresentValue"))
                    assertEquals(listOf<Any?>(72.5, false), values.map { it.value })
                    assertEquals(before + 2, sim.readPropertyCount.get())
                }
            }
        }
    }

    @Test fun readsTheObjectListElementByElementWhenItDoesNotFitOneReply() {
        sim().use { sim ->
            for (i in 10 until 60) sim.add(BacnetSimulator.ANALOG_VALUE, i, "AV $i", i.toFloat())
            sim.maxApdu = 206
            local().use { gw ->
                remote(sim, gw, maxProps = 50).use { ahu ->
                    ahu.start(); awaitConnected(ahu)
                    val objects = ahu.objects()
                    assertEquals(56, objects.size)
                    assertEquals("AV 59", objects[ObjectId(BacnetObjectType.ANALOG_VALUE, 59)])
                    // 50 present values do not fit 206 bytes either: the request is split until they do.
                    val values = ahu.read((10 until 60).map { "analog-value:$it/PresentValue" })
                    assertEquals((10 until 60).map { it.toDouble() }, values.map { it.value })
                }
            }
        }
    }

    @Test fun writesPresentValuesAtAPriorityAndRelinquishes() {
        sim().use { sim ->
            local().use { gw ->
                remote(sim, gw).use { ahu ->
                    ahu.start(); awaitConnected(ahu)
                    val damper = sim.obj(BacnetSimulator.ANALOG_OUTPUT, 1)

                    assertEquals(QualityCode.Good, ahu.write("analog-output:1/PresentValue", 40).quality.code)
                    assertEquals(40f, damper.priority[15])
                    assertEquals(QualityCode.Good, ahu.write("analog-output:1/PresentValue;priority=8", 55.5).quality.code)
                    assertEquals(55.5f, damper.priority[7])
                    assertEquals(55.5, value(ahu, "analog-output:1/PresentValue").value)
                    assertEquals(8, sim.writes.last().priority)
                    // Null relinquishes the command at that priority; priority 16 still holds 40.
                    assertEquals(QualityCode.Good, ahu.write("analog-output:1/PresentValue;priority=8", null).quality.code)
                    assertNull(damper.priority[7])
                    assertEquals(40.0, value(ahu, "analog-output:1/PresentValue").value)

                    assertEquals(QualityCode.Good, ahu.write("binary-output:3/PresentValue", true).quality.code)
                    assertEquals(1L, sim.obj(BacnetSimulator.BINARY_OUTPUT, 3).priority[15])
                    assertEquals(true, value(ahu, "binary-output:3/PresentValue").value)
                    assertEquals(QualityCode.Good, ahu.write("multi-state-value:4/PresentValue", 3).quality.code)
                    assertEquals(3L, value(ahu, "multi-state-value:4/PresentValue").value)
                    assertEquals(QualityCode.Good, ahu.write("analog-output:1/Description", "outside air").quality.code)
                    assertEquals("outside air", damper.description)

                    // An input's present value is writable only out of service; the device says write-access-denied.
                    assertEquals(QualityCode.Bad_NotWritable, ahu.write("analog-input:0/PresentValue", 1).quality.code)
                    assertEquals(QualityCode.Good, ahu.write("analog-input:0/OutOfService", true).quality.code)
                    assertEquals(QualityCode.Good, ahu.write("analog-input:0/PresentValue", 12).quality.code)
                    assertEquals(12.0, value(ahu, "analog-input:0/PresentValue").value)
                    // Priority 6 is reserved for minimum on/off: the device refuses it as out of range.
                    assertEquals(QualityCode.Bad_OutOfRange, ahu.write("analog-output:1/PresentValue;priority=6", 1).quality.code)

                    assertEquals(QualityCode.Bad_NotFound, ahu.write("analog-output:77/PresentValue", 1).quality.code)
                    assertEquals(QualityCode.Bad_NotWritable, ahu.write("analog-input:0/StatusFlags/Fault", true).quality.code)
                    assertEquals(QualityCode.Bad_TypeMismatch, ahu.write("analog-output:1/PresentValue", "much").quality.code)
                    assertEquals(QualityCode.Bad_NotFound, ahu.write("nope", 1).quality.code)
                }
            }
        }
    }

    @Test fun rawReadsWritesAndTimeSynchronizationForScripts() {
        sim().use { sim ->
            local().use { gw ->
                remote(sim, gw).use { ahu ->
                    assertThrows(NotConnectedException::class.java) { ahu.readRaw(ObjectId(BacnetObjectType.ANALOG_INPUT, 0), BacnetProperty.PRESENT_VALUE) }
                    ahu.start(); awaitConnected(ahu)
                    val ai = ObjectId(BacnetObjectType.ANALOG_INPUT, 0)
                    val ao = ObjectId(BacnetObjectType.ANALOG_OUTPUT, 1)
                    assertEquals(72.5, ahu.readRaw(ai, BacnetProperty.PRESENT_VALUE))
                    assertEquals(64L, ahu.readRaw(ai, BacnetProperty.UNITS))
                    assertEquals(listOf(false, false, false, false), ahu.readRaw(ai, BacnetProperty.STATUS_FLAGS))
                    val device = ObjectId(BacnetObjectType.DEVICE, sim.deviceInstance)
                    assertEquals(6L, ahu.readRaw(device, BacnetProperty.OBJECT_LIST, 0))
                    assertEquals("device:${sim.deviceInstance}", ahu.readRaw(device, BacnetProperty.OBJECT_LIST, 1))
                    val refused = assertThrows(IllegalStateException::class.java) { ahu.readRaw(ObjectId(BacnetObjectType.ANALOG_INPUT, 99), BacnetProperty.PRESENT_VALUE) }
                    assertTrue(refused.message!!.isNotBlank())

                    // A whole number written to an analog present value is still a REAL, at the priority given.
                    assertEquals(QualityCode.Good, ahu.writeRaw(ao, BacnetProperty.PRESENT_VALUE, 33, priority = 9).code)
                    assertEquals(33f, sim.obj(BacnetSimulator.ANALOG_OUTPUT, 1).priority[8])
                    assertEquals(9, sim.writes.last().priority)
                    assertEquals(QualityCode.Good, ahu.writeRaw(ao, BacnetProperty.PRESENT_VALUE, null, priority = 9).code)
                    assertNull(sim.obj(BacnetSimulator.ANALOG_OUTPUT, 1).priority[8])
                    assertEquals(QualityCode.Bad_OutOfRange, ahu.writeRaw(ao, BacnetProperty.PRESENT_VALUE, 1, priority = 17).code)

                    val at = java.time.Instant.parse("2026-11-10T13:45:30Z")
                    assertTrue(ahu.synchronizeTime(utc = true, at = at))
                    assertTrue(ahu.synchronizeTime(utc = false, at = at, zone = java.time.ZoneOffset.ofHours(2)))
                    await("time syncs") { sim.timeSyncs.size == 2 }
                    assertTrue(sim.timeSyncs[0].utc)
                    assertArrayEquals(hex("A4 7E 0B 0A 02 B4 0D 2D 1E 00"), sim.timeSyncs[0].data)
                    assertFalse(sim.timeSyncs[1].utc)
                    assertArrayEquals(hex("A4 7E 0B 0A 02 B4 0F 2D 1E 00"), sim.timeSyncs[1].data)
                }
            }
        }
    }

    @Test fun pollsSubscriptionsKeepsTheLastValueWhenSilentAndReconnects() {
        sim().use { sim ->
            local(timeoutMs = 100, retries = 1).use { gw ->
                remote(sim, gw).use { ahu ->
                    val samples = CopyOnWriteArrayList<Pair<Any?, QualityCode>>()
                    val handle = ahu.attachRaw(50, "analog-input:0/PresentValue") { v, q, _ -> samples += v to q.code }
                    val listened = CopyOnWriteArrayList<QualifiedValue>()
                    val sub = ahu.subscribe(listOf("binary-input:2/PresentValue"), 50.0) { listened += it }
                    ahu.start(); awaitConnected(ahu)
                    await("first sample") { samples.any { it == (72.5 to QualityCode.Good) } }
                    sim.obj(BacnetSimulator.ANALOG_INPUT, 0).presentValue = 80f
                    await("changed sample") { samples.any { it == (80.0 to QualityCode.Good) } }
                    await("listener sample") { listened.any { it.value == true && it.quality.code == QualityCode.Good } }
                    assertEquals(50L, ahu.activeScanPeriodMs("analog-input:0/PresentValue"))

                    sim.silent = true
                    await("lost") { samples.lastOrNull()?.second == QualityCode.Bad_NotConnected }
                    assertEquals(80.0, samples.last().first, "the last good value is kept")
                    assertEquals(ConnectionState.RECONNECTING, ahu.connectionStatus().state)
                    assertTrue(gw.timeoutCount.get() > 0)
                    assertTrue(gw.retryCount.get() > 0)
                    assertEquals(QualityCode.Bad_NotConnected, ahu.write("analog-output:1/PresentValue", 3).quality.code)

                    sim.obj(BacnetSimulator.ANALOG_INPUT, 0).presentValue = 81f
                    sim.silent = false
                    awaitConnected(ahu)
                    await("back") { samples.lastOrNull() == (81.0 to QualityCode.Good) }
                    handle.close(); sub.close()
                    assertNull(ahu.activeScanPeriodMs("analog-input:0/PresentValue"))
                }
            }
        }
    }

    @Test fun aDeviceThatDoesNotAnswerWhoIsIsStillReadAtItsConfiguredHost() {
        sim().use { sim ->
            sim.answerWhoIs = false
            local(timeoutMs = 50, retries = 0).use { gw ->
                remote(sim, gw).use { ahu ->
                    ahu.start(); awaitConnected(ahu)
                    assertEquals(72.5, value(ahu, "analog-input:0/PresentValue").value)
                }
            }
        }
    }

    @Test fun confirmedRequestsTimeOutAfterTheirRetriesAndTheQueueIsBounded() {
        sim().use { sim ->
            sim.silent = true
            local(timeoutMs = 80, retries = 2, maxOutstanding = 1, maxQueued = 1).use { gw ->
                val dest = BacnetAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), sim.port))
                val data = BacnetFrames.readProperty(ObjectId(8, sim.deviceInstance), BacnetProperty.OBJECT_NAME)
                val started = System.nanoTime()
                val first = gw.request(dest, BacnetService.READ_PROPERTY, data)
                val second = gw.request(dest, BacnetService.READ_PROPERTY, data)
                val third = gw.request(dest, BacnetService.READ_PROPERTY, data)
                val queueFull = assertThrows(ExecutionException::class.java) { third.get(1, TimeUnit.SECONDS) }
                assertTrue(queueFull.cause is IllegalStateException)
                val e = assertThrows(ExecutionException::class.java) { first.get(5, TimeUnit.SECONDS) }
                assertTrue(e.cause is TimeoutException, e.cause.toString())
                assertTrue(System.nanoTime() - started >= 3 * 80 * 1_000_000L)
                assertTrue(gw.retryCount.get() >= 2)
                // The queued request is sent once the first ends, and times out the same way.
                assertTrue(assertThrows(ExecutionException::class.java) { second.get(5, TimeUnit.SECONDS) }.cause is TimeoutException)
                assertEquals(4L, gw.retryCount.get())
                assertEquals(2L, gw.timeoutCount.get())
                assertEquals(0, gw.pendingCount)
                // Each try went to the device.
                assertEquals(6, sim.droppedCount.get())
            }
        }
    }

    @Test fun theLocalDeviceAnswersWhoIsAndReadPropertyWithItsConfiguredIdentity() {
        local(instance = 1001).use { gw ->
            DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { peer ->
                peer.soTimeout = 2_000
                val to = InetSocketAddress(InetAddress.getLoopbackAddress(), gw.boundPort)
                fun exchange(frame: ByteArray): ByteArray {
                    peer.send(DatagramPacket(frame, frame.size, to))
                    val buf = ByteArray(1500)
                    val p = DatagramPacket(buf, buf.size)
                    peer.receive(p)
                    return buf.copyOf(p.length)
                }
                // Who-Is 1001..1001 (0x03E9) -> I-Am device 1001, 1476, no segmentation, vendor 7.
                assertArrayEquals(
                    hex("81 0A 00 14 01 00 10 00 C4 02 00 03 E9 22 05 C4 91 03 21 07"),
                    exchange(hex("81 0A 00 0E 01 00 10 08 0A 03 E9 1A 03 E9")),
                )
                // ReadProperty object-name of the wildcard device 4194303, invoke id 5.
                val name = exchange(hex("81 0A 00 11 01 04 00 05 05 0C 0C 02 3F FF FF 19 4D"))
                assertArrayEquals(hex("81 0A 00 21 01 00 30 05 0C 0C 02 00 03 E9 19 4D 3E 75 0D 00") + "Gateway 1001".toByteArray() + hex("3F"), name)
                // vendor-identifier of device 1001 -> 7; an unknown object -> error class object (1), unknown-object (31).
                assertArrayEquals(hex("81 0A 00 14 01 00 30 06 0C 0C 02 00 03 E9 19 78 3E 21 07 3F"), exchange(hex("81 0A 00 11 01 04 00 05 06 0C 0C 02 00 03 E9 19 78")))
                assertArrayEquals(hex("81 0A 00 0D 01 00 50 07 0C 91 01 91 1F"), exchange(hex("81 0A 00 11 01 04 00 05 07 0C 0C 00 00 00 01 19 55")))
                // An unsupported service is rejected (unrecognized-service, 9).
                assertArrayEquals(hex("81 0A 00 09 01 00 60 08 09"), exchange(hex("81 0A 00 0A 01 04 00 05 08 1F")))
            }

            // Another BACnet device sees it through the same driver: a second local device reads the gateway's device object.
            val other = BacnetLocalDevice(LocalDeviceConfig("other", 1002, "Other", bindAddress = "127.0.0.1", port = 0, broadcastAddress = "127.0.0.1", apduTimeoutMs = 200, apduRetries = 1))
            other.start()
            try {
                val viewer = BacnetRemoteDevice(RemoteDeviceConfig("gw-view", "other", 1001, host = "127.0.0.1", port = gw.boundPort, reconnectMs = 100)) { if (it == "other") other else null }
                viewer.use {
                    viewer.start(); awaitConnected(viewer)
                    assertEquals(mapOf(ObjectId(8, 1001) to "Gateway 1001"), viewer.objects())
                    val v = viewer.read(listOf("device:1001/VendorIdentifier", "device:1001/ModelName", "device:1001/VendorName"))
                    assertEquals(listOf<Any?>(7L, "Gateway", "ERIC"), v.map { it.value })
                }
            } finally {
                other.close()
            }
        }
    }

    @Test fun moduleAppliesConfigAndShutsDownWithoutThreadsOrSockets(@TempDir tmp: Path) {
        val registry = SchemaRegistry()
        registry.register(BacnetDriverModule.MODULE_ID, LOCAL_DEVICE_KIND, localDeviceSchema())
        registry.register(BacnetDriverModule.MODULE_ID, REMOTE_DEVICE_KIND, remoteDeviceSchema())
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(LOCAL_DEVICE_KIND, Metadata("gw", BacnetDriverModule.MODULE_ID), mapOf("deviceInstance" to 4194303, "deviceName" to "x")))
        }
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(REMOTE_DEVICE_KIND, Metadata("r", BacnetDriverModule.MODULE_ID), mapOf("localDevice" to "gw", "deviceInstance" to 1, "host" to "plant.local")))
        }
        assertThrows(IllegalArgumentException::class.java) { remoteDeviceConfig("r", mapOf("localDevice" to "gw", "deviceInstance" to 1, "writePriority" to 0)) }
        assertEquals(javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n").trim(), BacnetDriverModule.MANIFEST_YAML.trim())

        sim().use { sim ->
            val id = BacnetDriverModule.MODULE_ID
            val modules = tmp.resolve("modules")
            modules.resolve("driver-bacnet").createDirectories().resolve("module.yaml").writeText(BacnetDriverModule.MANIFEST_YAML)
            val configDir = tmp.resolve("config")
            val store = FileConfigStore(configDir, SchemaRegistry())
            val host = ModuleHost(modules, configDir, store = store)
            host.installAll()
            host.start(id)
            assertEquals(ModuleState.RUNNING, host.state(id))
            val module = host.instance(id) as BacnetDriverModule

            store.put(Scope.GATEWAY, Resource(REMOTE_DEVICE_KIND, Metadata("ahu", id), mapOf(
                "localDevice" to "gw", "deviceInstance" to sim.deviceInstance, "host" to "127.0.0.1", "port" to sim.port, "reconnectMs" to 100,
            )))
            val ahu = module.device("ahu")!!
            await("waits for its local device") { ahu.connectionStatus().lastError?.contains("local device 'gw' is not configured") == true }
            store.put(Scope.GATEWAY, Resource(LOCAL_DEVICE_KIND, Metadata("gw", id), mapOf(
                "deviceInstance" to 1001, "deviceName" to "Gateway", "bindAddress" to "127.0.0.1", "port" to 0,
                "broadcastAddress" to "127.0.0.1", "apduTimeoutMs" to 200, "apduRetries" to 1,
            )))
            awaitConnected(ahu)
            assertEquals(72.5, ahu.read(listOf("analog-input:0/PresentValue")).single().value)
            assertEquals(setOf("ahu"), module.deviceNames())
            val port = module.localDevice("gw")!!.boundPort

            host.stop(id)
            host.uninstall(id)
            assertNull(module.device("ahu"))
            assertNoDriverThreads()
            // The UDP port is free again.
            DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), port)).close()
        }
    }

    @Test fun stoppingADeviceEndsItsThreads() {
        sim().use { sim ->
            val gw = local()
            val ahu = remote(sim, gw)
            ahu.attachRaw(20, "analog-input:0/PresentValue") { _, _, _ -> }
            ahu.start(); awaitConnected(ahu)
            ahu.close(); gw.close()
            assertFalse(gw.isBound)
            assertNoDriverThreads()
            val after = gw.request(BacnetAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), sim.port)), BacnetService.READ_PROPERTY, ByteArray(0))
            assertTrue(assertThrows(ExecutionException::class.java) { after.get(1, TimeUnit.SECONDS) }.cause is NotConnectedException)
        }
    }

    private fun assertNoDriverThreads() {
        await("driver threads end") {
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("bacnet-") && !it.name.startsWith("bacnet-sim") }
        }
    }

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun awaitConnected(device: BacnetRemoteDevice) = await("connected ${device.connectionStatus()}") { device.connectionStatus().connected }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        assertNotNull(null, "timed out waiting: $message")
    }
}
