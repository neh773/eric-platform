package org.oip.dnp3

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
import org.oip.sim.dnp3.Dnp3OutstationSimulator
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The peer is the in-repo DNP3 outstation simulator (test-infra/sim/dnp3), whose encoder is written
 * separately from the driver's. Everything runs on loopback TCP.
 * doc source: FEATURES DEV-013, DEV-014
 */
@Timeout(60)
class Dnp3DriverTest {
    private fun sim() = Dnp3OutstationSimulator().apply {
        binaryInput(0, true, event = false)
        binaryInput(1, false, event = false)
        binaryOutput(0, false)
        binaryOutput(1, false)
        counter(0, 1000, event = false)
        analogInput(0, 12.5, event = false)
        analogInput(1, 42L, event = false)
        analogOutput(0, 0.0)
    }

    private fun config(sim: Dnp3OutstationSimulator, change: (OutstationConfig) -> OutstationConfig = { it }): OutstationConfig =
        change(OutstationConfig("rtu", "127.0.0.1", sim.port, masterAddress = sim.masterAddress, outstationAddress = sim.address,
            responseTimeoutMs = 1_000, reconnectMs = 100, integrityPeriodMs = 0, eventPeriodMs = 0, unsolicited = false))

    private fun value(o: Dnp3Outstation, id: String) = o.read(listOf(id)).single()

    @Test fun integrityPollReadsEveryPointTypeAndClearsTheRestart() {
        sim().use { sim ->
            Dnp3Outstation(config(sim) { it.copy(unsolicited = true) }).use { rtu ->
                rtu.start(); awaitConnected(rtu)
                assertEquals(true, value(rtu, "bi:0").value)
                assertEquals(false, value(rtu, "bi:1").value)
                assertEquals(false, value(rtu, "bo:1").value)
                assertEquals(1000L, value(rtu, "ci:0").value)
                assertEquals(12.5, value(rtu, "ai:0").value)
                assertEquals(42L, value(rtu, "ai:1").value)
                assertEquals(0.0, value(rtu, "ao:0").value)
                assertEquals(QualityCode.Good, value(rtu, "ai:0").quality.code)
                assertEquals(1L, value(rtu, "ai:0/Flags").value)
                assertEquals(true, value(rtu, "ai:0/Online").value)
                assertEquals(QualityCode.Bad_NotFound, value(rtu, "ai:9").quality.code)
                assertEquals(QualityCode.Bad_NotFound, value(rtu, "zz:1").quality.code)
                // Startup: unsolicited disabled first, the restart indication cleared, then enabled for classes 1 to 3.
                assertTrue(sim.requestCount(Dnp3App.DISABLE_UNSOLICITED) >= 1)
                await("restart cleared") { !sim.restart }
                await("unsolicited enabled") { sim.enabledClasses == setOf(1, 2, 3) }
                // The null unsolicited response the outstation sends on connect is confirmed.
                await("null unsolicited confirmed") { sim.unsolicitedConfirmed.get() >= 1 }
                assertEquals(1L, rtu.integrityPolls.get())

                val root = rtu.browse("").map { it.nodeId }
                assertEquals(listOf("bi", "bo", "ci", "ai", "ao", "iin"), root)
                val bo = rtu.browse("bo")
                assertTrue(bo.single { it.nodeId == "bo:1" }.writable)
                assertTrue(bo.any { it.nodeId == "bo:1/Timestamp" && !it.writable })
                assertFalse(rtu.browse("bi").single { it.nodeId == "bi:0" }.writable)
                assertNotNull(rtu.describe("ai:77"))
                assertNull(rtu.describe("nonsense"))

                // Point flags: forced is Good_Overridden, offline is bad.
                sim.analogInput(0, 13.0, flags = Dnp3OutstationSimulator.ONLINE or Dnp3OutstationSimulator.LOCAL_FORCED, event = false)
                sim.binaryInput(1, false, flags = 0, event = false)
                rtu.integrityPoll()
                assertEquals(QualityCode.Good_Overridden, value(rtu, "ai:0").quality.code)
                assertEquals(QualityLevel.BAD, value(rtu, "bi:1").quality.level)
            }
        }
    }

    @Test fun eventPollsReadClassesAndEventsCarryTheirTime() {
        sim().use { sim ->
            Dnp3Outstation(config(sim) { it.copy(eventPeriodMs = 100, eventClasses = setOf(1, 2)) }).use { rtu ->
                rtu.start(); awaitConnected(rtu)
                val at = System.currentTimeMillis() - 60_000
                sim.binaryInput(1, true, dnpClass = 1, timeMs = at)
                sim.analogInput(0, 99.5, dnpClass = 2)
                sim.counter(0, 1001, dnpClass = 3)
                await("events polled") { value(rtu, "bi:1").value == true && value(rtu, "ai:0").value == 99.5 }
                assertEquals(at, value(rtu, "bi:1/Timestamp").value)
                assertEquals(at, value(rtu, "bi:1").sourceTimestamp?.toEpochMilli())
                // Class 3 is not polled; the counter event stays buffered until an integrity poll.
                assertEquals(1000L, value(rtu, "ci:0").value)
                await("confirmed events leave the buffer") { sim.pendingEvents() == 1 }
                assertTrue(rtu.eventPolls.get() >= 1)
                rtu.integrityPoll()
                assertEquals(1001L, value(rtu, "ci:0").value)
                await("all confirmed") { sim.pendingEvents() == 0 }
            }
        }
    }

    @Test fun unsolicitedResponsesAreAppliedAndConfirmed() {
        sim().use { sim ->
            Dnp3Outstation(config(sim) { it.copy(unsolicited = true) }).use { rtu ->
                val seen = CopyOnWriteArrayList<Any?>()
                rtu.attachRaw(20, "ai:1") { v, q, _ -> if (q.code == QualityCode.Good) seen += v }
                rtu.start(); awaitConnected(rtu)
                await("enabled") { sim.enabledClasses.isNotEmpty() && sim.unsolicitedConfirmed.get() >= 1 }
                // The null unsolicited response of the connect may still carry the restart bit, which the master
                // follows up with its own clear and integrity poll; let that settle before counting reads.
                Thread.sleep(200)
                val before = sim.unsolicitedConfirmed.get()
                val polls = sim.requestCount(Dnp3App.READ)
                sim.analogInput(1, 77L)
                await("unsolicited value") { value(rtu, "ai:1").value == 77L }
                await("confirmed") { sim.unsolicitedConfirmed.get() > before && sim.pendingEvents() == 0 }
                assertEquals(polls, sim.requestCount(Dnp3App.READ), "no poll was needed")
                assertTrue(rtu.unsolicitedCount.get() >= 1)
                await("subscriber saw it") { seen.contains(77L) }
                assertEquals(42L, seen.first())
            }
        }
    }

    @Test fun responsesSpanningFragmentsAndSegmentsAreConfirmedAndReassembled() {
        sim().use { sim ->
            for (i in 0 until 300) sim.analogInput(10 + i, i.toLong(), event = false)
            Dnp3Outstation(config(sim)).use { rtu ->
                rtu.start(); awaitConnected(rtu)
                // One fragment of about 1.5 kB: several transport segments.
                assertEquals(299L, value(rtu, "ai:309").value)
                sim.maxFragment = 64
                for (i in 0 until 300) sim.analogInput(10 + i, i.toLong() + 1, event = false)
                val confirms = sim.confirms.get()
                rtu.integrityPoll()
                assertEquals((1L..300L).toList(), (0 until 300).map { value(rtu, "ai:${10 + it}").value })
                assertTrue(sim.confirms.get() - confirms > 20, "each fragment but the last is confirmed")
            }
        }
    }

    @Test fun commandsUseSelectBeforeOperateOrDirectOperate() {
        sim().use { sim ->
            Dnp3Outstation(config(sim)).use { rtu ->
                rtu.start(); awaitConnected(rtu)
                val latched = rtu.operateBinary(0, Crob(Crob.LATCH_ON))
                assertTrue(latched.ok, latched.toString())
                assertEquals(1, sim.requestCount(Dnp3App.SELECT))
                assertEquals(1, sim.requestCount(Dnp3App.OPERATE))
                assertEquals(Dnp3App.OPERATE, sim.commands.last().function)
                assertEquals(true, sim.binaryOutputs[0]!!.value)

                val pulse = rtu.operateBinary(1, Crob(Crob.CLOSE or Crob.PULSE_ON, onTimeMs = 250), CommandMode.DIRECT_OPERATE)
                assertTrue(pulse.ok)
                assertEquals(Dnp3App.DIRECT_OPERATE, sim.commands.last().function)
                assertEquals(250L, sim.commands.last().onMs)
                assertEquals(1, sim.requestCount(Dnp3App.SELECT))

                assertTrue(rtu.operateAnalog(0, 21.75, mode = CommandMode.DIRECT_OPERATE).ok)
                assertEquals(21.75, sim.analogOutputs[0]!!.value)
                assertTrue(rtu.operateAnalog(0, 300).ok)
                assertEquals(300L, sim.analogOutputs[0]!!.value)
                assertThrows(IllegalArgumentException::class.java) { rtu.operateAnalog(0, 1.5, variation = 2) }

                // Status the outstation echoes: local control, an unknown point, an operate whose select expired.
                sim.commandStatus[1] = 7
                val local = rtu.operateBinary(1, Crob(Crob.LATCH_OFF))
                assertFalse(local.ok); assertEquals("LOCAL", local.statusName)
                assertEquals("NOT_SUPPORTED", rtu.operateBinary(9, Crob(Crob.LATCH_ON)).statusName)
                sim.commandStatus.clear()
                sim.selectTimeoutMs = -1
                assertEquals("NO_SELECT", rtu.operateBinary(1, Crob(Crob.LATCH_ON)).statusName)
                sim.selectTimeoutMs = 2_000

                // Tag writes: a boolean latches, options pick the operation and the mode; the status comes back as quality.
                assertEquals(QualityCode.Good, rtu.write("bo:1", true).quality.code)
                assertEquals(Crob.LATCH_ON, sim.commands.last().code)
                assertEquals(QualityCode.Good, rtu.write("bo:1;op=pulse;onMs=100;mode=direct", true).quality.code)
                assertEquals(Crob.PULSE_ON, sim.commands.last().code)
                assertEquals(QualityCode.Good, rtu.write("bo:1;op=tripclose", false).quality.code)
                assertEquals(Crob.TRIP or Crob.PULSE_ON, sim.commands.last().code)
                assertEquals(QualityCode.Good, rtu.write("ao:0;variation=3", "4.5").quality.code)
                assertEquals(4.5, sim.analogOutputs[0]!!.value)
                sim.commandStatus[0] = 4
                val refused = rtu.write("ao:0", 1)
                assertEquals(QualityCode.Bad_DeviceFailure, refused.quality.code)
                assertTrue(refused.quality.diagnostic!!.contains("NOT_SUPPORTED"), refused.quality.diagnostic)
                assertEquals(QualityCode.Bad_NotWritable, rtu.write("bi:0", true).quality.code)
                assertEquals(QualityCode.Bad_TypeMismatch, rtu.write("ao:0", "lots").quality.code)
                // A successful command asks for the change: the next event poll reads the output status.
                await("output status") { rtu.eventPolls.get() > 0 && value(rtu, "bo:1").value == false }
            }
        }
    }

    @Test fun timeIsSetWhenTheOutstationAsksLanAndNonLan() {
        for (mode in listOf(TimeSync.LAN, TimeSync.NON_LAN)) {
            sim().use { sim ->
                sim.needTime = true
                Dnp3Outstation(config(sim) { it.copy(timeSync = mode) }).use { rtu ->
                    rtu.start(); awaitConnected(rtu)
                    // The simulator records the write before it answers; the master counts the sync once the answer arrives.
                    await("time written ($mode)") { sim.timeWrites.isNotEmpty() && !sim.needTime && rtu.timeSyncs.get() > 0 }
                    assertTrue(kotlin.math.abs(sim.timeWrites.single() - System.currentTimeMillis()) < 5_000, "$mode ${sim.timeWrites}")
                    val asked = if (mode == TimeSync.LAN) Dnp3App.RECORD_CURRENT_TIME else Dnp3App.DELAY_MEASURE
                    assertEquals(1, sim.requestCount(asked))
                    assertEquals(1L, rtu.timeSyncs.get())
                }
            }
        }
    }

    @Test fun aSilentOutstationTimesOutKeepsTheLastValueAndReconnects() {
        sim().use { sim ->
            Dnp3Outstation(config(sim) { it.copy(responseTimeoutMs = 200, eventPeriodMs = 50) }).use { rtu ->
                rtu.start(); awaitConnected(rtu)
                assertEquals(12.5, value(rtu, "ai:0").value)
                sim.silent = true
                await("lost") { !rtu.connectionStatus().connected }
                assertTrue(rtu.timeoutCount.get() >= 1)
                val held = value(rtu, "ai:0")
                assertEquals(12.5, held.value)
                assertEquals(QualityCode.Bad_NotConnected, held.quality.code)
                assertTrue(rtu.connectionStatus().lastError!!.contains("no response"), rtu.connectionStatus().lastError)
                assertEquals(QualityCode.Bad_NotConnected, rtu.write("bo:0", true).quality.code)
                sim.silent = false
                awaitConnected(rtu)
                assertEquals(QualityCode.Good, value(rtu, "ai:0").quality.code)
                val connections = sim.connections.get()
                sim.dropConnection()
                await("reconnected after a drop") { sim.connections.get() > connections && rtu.connectionStatus().connected }
            }
        }
    }

    @Test fun linkLayerConfirmedDataAndLinkStatusAreAnswered() {
        sim().use { sim ->
            sim.confirmedUserData = true
            Dnp3Outstation(config(sim)).use { rtu ->
                rtu.start(); awaitConnected(rtu)
                assertTrue(sim.linkAcks.get() >= 1, "confirmed user data is acknowledged")
                sim.requestLinkStatus()
                await("link status answered") { sim.linkStatusReplies.get() == 1 }
                assertEquals(12.5, value(rtu, "ai:0").value)
            }
        }
        // A wrong outstation address is never answered: no response, no connection.
        sim().use { sim ->
            Dnp3Outstation(config(sim) { it.copy(outstationAddress = 7, responseTimeoutMs = 150) }).use { rtu ->
                rtu.start()
                await("refused") { rtu.connectionStatus().lastError != null }
                assertFalse(rtu.connectionStatus().connected)
            }
        }
    }

    @Test fun moduleAppliesConfigAndShutsDownWithoutThreadsOrSockets(@TempDir tmp: Path) {
        val registry = SchemaRegistry()
        registry.register(Dnp3DriverModule.MODULE_ID, OUTSTATION_KIND, outstationSchema())
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(OUTSTATION_KIND, Metadata("r", Dnp3DriverModule.MODULE_ID), mapOf("host" to "h", "outstationAddress" to 70000)))
        }
        assertThrows(ValidationException::class.java) {
            registry.check(Resource(OUTSTATION_KIND, Metadata("r", Dnp3DriverModule.MODULE_ID), mapOf("host" to "h", "commandMode" to "maybe")))
        }
        assertThrows(IllegalArgumentException::class.java) { outstationConfig("r", mapOf("host" to "h", "masterAddress" to 5, "outstationAddress" to 5)) }
        assertEquals(javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n").trim(), Dnp3DriverModule.MANIFEST_YAML.trim())

        sim().use { sim ->
            val id = Dnp3DriverModule.MODULE_ID
            val modules = tmp.resolve("modules")
            modules.resolve("driver-dnp3").createDirectories().resolve("module.yaml").writeText(Dnp3DriverModule.MANIFEST_YAML)
            val configDir = tmp.resolve("config")
            val store = FileConfigStore(configDir, SchemaRegistry())
            val host = ModuleHost(modules, configDir, store = store)
            host.installAll()
            host.start(id)
            assertEquals(ModuleState.RUNNING, host.state(id))
            val module = host.instance(id) as Dnp3DriverModule
            store.put(Scope.GATEWAY, Resource(OUTSTATION_KIND, Metadata("rtu", id), mapOf(
                "host" to "127.0.0.1", "port" to sim.port, "reconnectMs" to 100, "responseTimeoutMs" to 1000, "commandMode" to "directOperate",
            )))
            val rtu = module.device("rtu")!!
            awaitConnected(rtu)
            assertEquals(CommandMode.DIRECT_OPERATE, rtu.config.commandMode)
            assertEquals(12.5, value(rtu, "ai:0").value)
            assertEquals(setOf("rtu"), module.deviceNames())
            store.put(Scope.GATEWAY, Resource(OUTSTATION_KIND, Metadata("rtu", id, version = 1), mapOf("host" to "127.0.0.1", "port" to sim.port, "enabled" to false)))
            await("disabled") { module.device("rtu") == null }
            store.put(Scope.GATEWAY, Resource(OUTSTATION_KIND, Metadata("rtu", id, version = 2), mapOf("host" to "127.0.0.1", "port" to sim.port, "reconnectMs" to 100)))
            awaitConnected(module.device("rtu")!!)

            host.stop(id)
            host.uninstall(id)
            assertNull(module.device("rtu"))
            assertNoDriverThreads()
            // The outstation sees the master's socket close.
            await("socket closed") { sim.requestCount(Dnp3App.READ) > 0 && !sim.hasClient }
        }
    }

    private fun assertNoDriverThreads() {
        await("driver threads end") {
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("dnp3-") && !it.name.startsWith("dnp3-sim") }
        }
    }

    private fun awaitConnected(o: Dnp3Outstation) = await("connected ${o.connectionStatus()}") { o.connectionStatus().connected }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting: $message")
    }
}
