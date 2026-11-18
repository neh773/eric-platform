package org.oip.gateway

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.eclipse.milo.opcua.sdk.client.DiscoveryClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.modbus.ModbusDeviceConfig
import org.oip.modbus.ModbusDriverModule
import org.oip.opcua.server.OpcUaServerSettings
import org.oip.opcua.server.OpcUaTagServer
import org.oip.opcua.server.TagNodeIds
import org.oip.tags.QualityCode
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TagConfigBinding
import org.oip.tags.TagEngine
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import com.sun.management.ThreadMXBean

/**
 * Retained heap on the path the 800k histogram was taken from: config batch, file store,
 * tag engine, Modbus device, and the OPC UA server. The earlier tags-module check configured
 * OPC tags with no device and no server and reported ~704 B/tag; the histogram was ~2.3 KB.
 */
class TagScaleTest {
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    fun hundredThousandRealPathTagsStayWithinOneKilobyte() {
        val tags = 100_000
        val dir = Files.createTempDirectory("oip-tag-scale")
        val registry = SchemaRegistry()
        val store = FileConfigStore(dir.resolve("config"), registry)
        val engine = TagEngine()
        val binding = TagConfigBinding(engine, store, registry)
        binding.attach()
        val modbus = ModbusDriverModule()
        val device = modbus.addDevice(ModbusDeviceConfig(name = "bench-mb-0", host = "127.0.0.1", port = 1))
        val bridge = TagDriverBridge(engine, store, modbus, null)
        bridge.start()
        val opc = OpcUaTagServer(
            OpcUaServerSettings(port = 0, hostname = "127.0.0.1"),
            dir.resolve("opcua"),
            engine,
            { user, _, _, _ -> org.oip.tags.TagCaller.User(user, emptySet(), "Default") },
            "test",
        )
        try {
            opc.start()
            val before = liveBytes()
            val resources = ArrayList<Resource>(tags)
            for (i in 0 until tags) {
                val name = "p%07d".format(i)
                resources += Resource(
                    TAG_KIND,
                    Metadata(name, TAGS_MODULE, mapOf("bench" to "load")),
                    mapOf(
                        "provider" to "default",
                        "path" to "bench/$name",
                        "type" to "opc",
                        "dataType" to "Int32",
                        "opcServer" to "bench-mb-0",
                        "opcItemPath" to "1.HR${i + 1}",
                        // The comms watchdog treats silence past the scan period as loss. This
                        // measurement injects once and then reads the value back; it is not a poll.
                        "scanPeriodMillis" to 3_600_000,
                    ),
                )
            }
            store.putAll(Scope.GATEWAY, resources, actor = "scale", syncEach = false)
            resources.clear()
            assertTrue(binding.problems().isEmpty(), binding.problems().take(5).toString())
            for (i in 0 until tags) device.inject("1.HR${i + 1}", i % 1000, epochMillis = 1_700_000_000_000L)
            assertEquals(999, engine.read("[default]bench/p0000999").value)
            assertEquals(QualityCode.GOOD, engine.read("[default]bench/p0000999").quality.code)
            assertEquals(1, engine.browse("[default]bench", tags - 1, 1).size)
            val after = liveBytes()
            val delta = (after - before).coerceAtLeast(0)
            val perTag = delta / tags.toDouble()
            println(
                "SCALE real-path $tags tags heap delta=$delta bytes (${"%.1f".format(perTag)} bytes/tag) " +
                    "live=$after used=${usedHeap()}",
            )
            if (perTag > 1024.0) println(classHistogram().lineSequence().take(45).joinToString("\n"))
            assertTrue(perTag <= 1024.0, "footprint ${"%.1f".format(perTag)} bytes/tag is above 1 KB")

            val nodeId = NodeId(opc.namespaceIndex, TagNodeIds.tag("default", "bench/p0000999"))
            val client = connect(opc)
            try {
                val read = client.readValues(0.0, TimestampsToReturn.Both, listOf(nodeId)).single()
                assertTrue(read.statusCode.isGood, "opc read ${read.statusCode}")
                assertEquals(999, (read.value.value as Number).toInt())
            } finally {
                runCatching { client.disconnect() }
            }

            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            assertTrue(bean.isThreadAllocatedMemorySupported)
            if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
            val sampleTags = 2_000
            val addresses = Array(sampleTags) { "1.HR${it + 1}" }
            val good = org.oip.modbus.Quality.of(org.oip.modbus.QualityCode.Good)
            repeat(10) { pass ->
                for (address in addresses) device.inject(address, pass, good, 1_700_000_000_000L)
            }
            val allocBefore = bean.currentThreadAllocatedBytes
            val cpuBefore = bean.currentThreadCpuTime
            val passes = 50
            repeat(passes) { pass ->
                for (address in addresses) device.inject(address, pass + 3, good, 1_700_000_001_000L)
            }
            val updates = sampleTags * passes
            val allocated = bean.currentThreadAllocatedBytes - allocBefore
            val cpuNs = bean.currentThreadCpuTime - cpuBefore
            val recording = Recording()
            recording.enable("jdk.ObjectAllocationInNewTLAB")
            recording.enable("jdk.ObjectAllocationOutsideTLAB")
            recording.start()
            repeat(2) { pass ->
                for (address in addresses) device.inject(address, pass + 20, good, 1_700_000_002_000L)
            }
            recording.stop()
            val jfr = Files.createTempFile("oip-scale", ".jfr")
            recording.dump(jfr)
            var tlabBytes = 0L
            var tlabEvents = 0
            RecordingFile(jfr).use { file ->
                while (file.hasMoreEvents()) {
                    val event = file.readEvent()
                    val type = event.eventType.name
                    if (type == "jdk.ObjectAllocationInNewTLAB" || type == "jdk.ObjectAllocationOutsideTLAB") {
                        tlabEvents++
                        tlabBytes += event.getLong("allocationSize")
                    }
                }
            }
            recording.close()
            val perUpdate = allocated.toDouble() / updates
            val cpuPer100kMs = cpuNs / 1_000_000.0 * (100_000.0 / updates)
            println(
                "SCALE alloc ${"%.1f".format(perUpdate)} bytes/update over $updates samples; " +
                    "CPU ${"%.2f".format(cpuPer100kMs)} ms/100k updates; " +
                    "JFR tlabEvents=$tlabEvents tlabBytes=$tlabBytes",
            )
            assertEquals(21, engine.read("[default]bench/p0000000").value)
            assertTrue(perUpdate < 64.0, "allocation ${"%.1f".format(perUpdate)} bytes/update is above 64")
        } finally {
            runCatching { opc.close() }
            runCatching { bridge.close() }
            runCatching { binding.close() }
            runCatching { device.close() }
            runCatching { store.close() }
        }
    }

    private fun connect(server: OpcUaTagServer): OpcUaClient {
        val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(20, TimeUnit.SECONDS)
        val selected = endpoints.first { ep ->
            ep.securityPolicyUri == SecurityPolicy.None.uri &&
                ep.endpointUrl.contains("/oip") &&
                !ep.endpointUrl.contains("discovery")
        }
        val config = OpcUaClientConfig.builder()
            .setEndpoint(EndpointUtil.updateUrl(selected, "127.0.0.1", server.boundPort))
            .setApplicationName(LocalizedText.english("oip-scale"))
            .setApplicationUri("urn:oip:test:tag-scale")
            .setProductUri("urn:oip:test")
            .setRequestTimeout(uint(30_000))
            .setSessionTimeout(uint(60_000))
            .setIdentityProvider(UsernameProvider("ada", "pw"))
            .build()
        val client = OpcUaClient(config, OpcTcpClientTransport(OpcTcpClientTransportConfig.newBuilder().build()))
        try {
            client.connect()
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
        return client
    }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /**
     * Live bytes after a full GC, matching the class-histogram number from the 800k run.
     * GC filler arrays are not tag state and move by more than the 1 KB ceiling between runs, so they are left out.
     * The ceiling itself is unchanged.
     */
    private fun liveBytes(): Long {
        val histogram = classHistogram()
        var total = -1L
        var filler = 0L
        for (line in histogram.lineSequence()) {
            val trimmed = line.trim()
            val cols = trimmed.split(Regex("\\s+"))
            if (trimmed.startsWith("Total")) total = cols[2].toLong()
            else if ("jdk.internal.vm.Filler" in trimmed) filler += cols[2].toLong()
        }
        check(total >= 0) { "class histogram had no total" }
        return total - filler
    }

    private fun classHistogram(): String =
        ManagementFactory.getPlatformMBeanServer().invoke(
            javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram",
            arrayOf(emptyArray<String>()),
            arrayOf("[Ljava.lang.String;"),
        ) as String
}
