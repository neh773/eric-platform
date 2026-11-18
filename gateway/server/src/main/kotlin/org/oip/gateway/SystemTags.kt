package org.oip.gateway

import org.oip.config.Scope
import org.oip.logix.DEVICE_KIND as LOGIX_DEVICE
import org.oip.logix.LogixDriverModule
import org.oip.modbus.DEVICE_KIND
import org.oip.modbus.ModbusDriverModule
import org.oip.opcua.CONNECTION_KIND
import org.oip.opcua.OpcUaClientModule
import org.oip.bacnet.BacnetDriverModule
import org.oip.bacnet.REMOTE_DEVICE_KIND as BACNET_DEVICE
import org.oip.dnp3.Dnp3DriverModule
import org.oip.dnp3.OUTSTATION_KIND as DNP3_DEVICE
import org.oip.s7.DEVICE_KIND as S7_DEVICE
import org.oip.s7.S7DriverModule
import org.oip.simulator.DEVICE_KIND as SIM_DEVICE
import org.oip.simulator.SimulatorDriverModule
import org.oip.tags.DataType
import org.oip.tags.TagEngine
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Gateway system tags in the read-only `[system]` provider, beyond `Gateway/Name` and `Gateway/Started`.
 * Names and types follow the public system-tags page where this gateway has the same data:
 *
 * Published here (provider `[system]`):
 * - `Gateway/CurrentDateTime` (DateTime), `Gateway/UptimeSeconds` (Int64): every second.
 * - `Gateway/SystemName`, `Gateway/Timezone`, `Gateway/DeploymentMode` (null when no mode is active),
 *   `Gateway/Version` (String; not on the public page, which carries a version only for EAM agents).
 * - `Gateway/Performance/CPU Usage` (Double, 0..1 system load seen by the JVM), `Max Memory`, `Memory Usage`
 *   (Int64 bytes, JVM heap), `Memory Utilization` (Double 0..1), `Available Disk Space MB` (Int64, the volume
 *   holding the data directory) and `Disk Utilization` (Double 0..1). Tag paths cannot hold parentheses,
 *   so the page's "Available Disk Space (MB)" is spelled without them.
 * - `Gateway/Sessions/SessionCount` (Int32): browser sessions on the gateway web API inside both timeouts.
 * - `Gateway/Alarming/Active and Acked`, `Active and Unacked`, `Clear and Unacked` (Int32), from the live
 *   alarm table. Shelved alarms are not counted, as in the alarm status API.
 * - `Gateway/Database/<connection>/Available` (Boolean), `ActiveConnections` (Int32), `QueriesPerSecond`
 *   (Int32), `ConnectionSaturation` (Double 0..1).
 * - `Gateway/Device/<device>/Name`, `Description`, `Status` (String), `Enabled` (Boolean) for Modbus, Logix, programmable simulator,
 *   and S7 devices (S7: doc source FEATURES DEV-004), BACnet/IP remote devices (DEV-011) and DNP3 outstations (DEV-014).
 *   BACnet and DNP3 diagnostics carry the request, error and timeout counts; their timing values stay 0.
 * - `Gateway/Device/<device>/Diagnostics/` and `Gateway/OPC/<connection>/Diagnostics/`: `Connected` (Boolean),
 *   `RequestCount`, `ErrorCount`, `TimeoutCount` (Int64), `AverageResponseTime`, `SamplingInterval`,
 *   `PublishInterval` (Double, milliseconds). OPC connections also publish `MonitoredItemCount` (Int32),
 *   counting only monitored items the server created. Tag paths reserve `[]` for the provider and
 *   for array indexes, so the folder segment is `Diagnostics` (DIFFERENCES 311).
 * - `Gateway/OPC/<connection>/Connected`, `Enabled` (Boolean), `State` (String) for OPC UA client connections.
 * - `Gateway/OPC UA Server/Enabled`, `Running` (Boolean), `State` (String), `QuarantinedCertificates`,
 *   `TrustedCertificates` (Int32) for this gateway's own OPC UA server (an OIP addition).
 *
 * One daemon thread runs everything. Values are computed off the tag-engine lock; the engine lock is
 * taken only by [TagEngine.publishSystem], and only when a value changed since the last publish.
 * Folders for removed connections and devices are removed.
 */
class SystemTags(private val gw: Gateway) : AutoCloseable {
    private val engine: TagEngine get() = gw.tags
    private val last = HashMap<String, Any?>()
    private val dynamic = HashMap<String, MutableSet<String>>()
    private var scheduler: ScheduledExecutorService? = null
    private var startedAt: Instant = gw.clock.instant()
    private var ticks = 0L
    private val hostName: String by lazy {
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?: System.getenv("HOSTNAME") ?: "unknown"
    }

    /** Publish every tag once on the caller's thread, then refresh on [THREAD]. */
    @Synchronized
    fun start() {
        if (scheduler != null) return
        startedAt = gw.clock.instant()
        refresh(full = true)
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, THREAD).apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { tick() } }, FAST_MS, FAST_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    override fun close() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    private fun tick() {
        ticks++
        refresh(full = ticks % SLOW_EVERY == 0L)
    }

    /** Recompute and publish. [full] includes the slower groups (performance, counts, connections). */
    @Synchronized
    fun refresh(full: Boolean) {
        val now = gw.clock.instant()
        publish("Gateway/CurrentDateTime", now, DataType.DATETIME, force = true)
        publish("Gateway/UptimeSeconds", maxOf(0L, (now.toEpochMilli() - startedAt.toEpochMilli()) / 1000L), DataType.INT64)
        // Device diagnostics are atomics. Publishing them every tick does not touch the poll path.
        guarded("devices") { devices() }
        guarded("opc") { opcConnections() }
        if (!full) return
        guarded("identity") { identity() }
        guarded("performance") { performance() }
        guarded("sessions") { sessions() }
        guarded("alarming") { alarming() }
        guarded("databases") { databases() }
        guarded("opcua-server") { opcUaServer() }
    }

    private fun identity() {
        // The configured system name (GAT-035), or the host name when it is blank.
        publish("Gateway/SystemName", gw.platformSettings.get().systemName.trim().ifEmpty { hostName }, DataType.STRING)
        publish("Gateway/Timezone", ZoneId.systemDefault().id, DataType.STRING)
        publish("Gateway/DeploymentMode", gw.store.activeMode(), DataType.STRING)
        publish("Gateway/Version", Gateway.VERSION, DataType.STRING)
    }

    private fun performance() {
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val cpu = os?.cpuLoad?.takeIf { it.isFinite() && it >= 0.0 }
        publish("Gateway/Performance/CPU Usage", cpu, DataType.DOUBLE)
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val max = rt.maxMemory()
        publish("Gateway/Performance/Max Memory", max, DataType.INT64)
        publish("Gateway/Performance/Memory Usage", used, DataType.INT64)
        publish("Gateway/Performance/Memory Utilization", if (max > 0) used.toDouble() / max else null, DataType.DOUBLE)
        val store = runCatching { Files.getFileStore(gw.dataDir) }.getOrNull()
        val total = store?.totalSpace ?: 0L
        val free = store?.usableSpace ?: 0L
        publish("Gateway/Performance/Available Disk Space MB", if (store != null) free / (1024L * 1024L) else null, DataType.INT64)
        publish("Gateway/Performance/Disk Utilization", if (total > 0) (total - free).toDouble() / total else null, DataType.DOUBLE)
    }

    private fun sessions() {
        val minutes = gw.security.gatewaySecurity.get().inactivityTimeoutMinutes
        val idle = if (minutes <= 0) null else minutes.toLong() * 60_000L
        publish("Gateway/Sessions/SessionCount", gw.httpAuth.activeSessionCount(idle), DataType.INT32)
    }

    private fun alarming() {
        var activeAcked = 0
        var activeUnacked = 0
        var clearUnacked = 0
        for (event in gw.alarms.query()) {
            when {
                event.active && event.acknowledged -> activeAcked++
                event.active -> activeUnacked++
                !event.acknowledged -> clearUnacked++
            }
        }
        publish("Gateway/Alarming/Active and Acked", activeAcked, DataType.INT32)
        publish("Gateway/Alarming/Active and Unacked", activeUnacked, DataType.INT32)
        publish("Gateway/Alarming/Clear and Unacked", clearUnacked, DataType.INT32)
    }

    private fun databases() {
        val seen = HashSet<String>()
        for (conn in gw.databases.connections()) {
            val name = conn["name"] as? String ?: continue
            if (!segmentOk(name)) continue
            seen += name
            val folder = "Gateway/Database/$name"
            val active = (conn["activeConnections"] as? Number)?.toInt() ?: 0
            val max = (conn["maxConnections"] as? Number)?.toInt() ?: 0
            publish("$folder/Available", conn["enabled"] == true && conn["status"] == "Valid", DataType.BOOLEAN)
            publish("$folder/ActiveConnections", active, DataType.INT32)
            publish("$folder/QueriesPerSecond", (conn["queriesPerSecond"] as? Number)?.toInt() ?: 0, DataType.INT32)
            publish("$folder/ConnectionSaturation", if (max > 0) active.toDouble() / max else 0.0, DataType.DOUBLE)
        }
        prune("Gateway/Database", seen, listOf("Available", "ActiveConnections", "QueriesPerSecond", "ConnectionSaturation"))
    }

    private fun devices() {
        val module = gw.host.instance(ModbusDriverModule.MODULE_ID) as? ModbusDriverModule
        val seen = HashSet<String>()
        for (stored in gw.store.list(ModbusDriverModule.MODULE_ID, Scope.GATEWAY, DEVICE_KIND)) {
            val name = stored.id.name
            if (!segmentOk(name)) continue
            seen += name
            val spec = stored.resource.spec
            val enabled = spec["enabled"] as? Boolean ?: true
            val status = when {
                !enabled -> "Disabled"
                else -> module?.device(name)?.connectionStatus()?.state?.let { title(it.name) } ?: "Not Started"
            }
            val folder = "Gateway/Device/$name"
            publish("$folder/Name", name, DataType.STRING)
            publish("$folder/Description", spec["description"] as? String ?: "", DataType.STRING)
            publish("$folder/Enabled", enabled, DataType.BOOLEAN)
            publish("$folder/Status", status, DataType.STRING)
            val diag = if (enabled) module?.device(name)?.diagnostics() else null
            publishDiagnostics("$folder/Diagnostics", diag?.connected == true, diag?.requestCount ?: 0L,
                diag?.errorCount ?: 0L, diag?.timeoutCount ?: 0L, diag?.averageResponseTimeMs,
                diag?.samplingIntervalMs, diag?.publishIntervalMs)
        }
        val logix = gw.host.instance(LogixDriverModule.MODULE_ID) as? LogixDriverModule
        for (stored in gw.store.list(LogixDriverModule.MODULE_ID, Scope.GATEWAY, LOGIX_DEVICE)) {
            val name = stored.id.name
            if (!segmentOk(name) || name in seen) continue
            seen += name
            val spec = stored.resource.spec
            val enabled = spec["enabled"] as? Boolean ?: true
            val status = when {
                !enabled -> "Disabled"
                else -> logix?.device(name)?.connectionStatus()?.state?.let { title(it.name) } ?: "Not Started"
            }
            val folder = "Gateway/Device/$name"
            publish("$folder/Name", name, DataType.STRING)
            publish("$folder/Description", spec["description"] as? String ?: "", DataType.STRING)
            publish("$folder/Enabled", enabled, DataType.BOOLEAN)
            publish("$folder/Status", status, DataType.STRING)
            val diag = if (enabled) logix?.device(name)?.diagnostics() else null
            publishDiagnostics("$folder/Diagnostics", diag?.connected == true, diag?.requestCount ?: 0L,
                diag?.errorCount ?: 0L, diag?.timeoutCount ?: 0L, diag?.averageResponseTimeMs,
                diag?.samplingIntervalMs, diag?.publishIntervalMs)
        }
        val simulator = gw.host.instance(SimulatorDriverModule.MODULE_ID) as? SimulatorDriverModule
        for (stored in gw.store.list(SimulatorDriverModule.MODULE_ID, Scope.GATEWAY, SIM_DEVICE)) {
            val name = stored.id.name
            if (!segmentOk(name) || name in seen) continue
            seen += name
            val spec = stored.resource.spec
            val enabled = spec["enabled"] as? Boolean ?: true
            val device = if (enabled) simulator?.device(name) else null
            val diag = device?.diagnostics()
            // The simulator has no connection state. "Connected" means the device is started.
            val status = when {
                !enabled -> "Disabled"
                diag?.connected == true -> "Connected"
                else -> "Not Started"
            }
            val folder = "Gateway/Device/$name"
            publish("$folder/Name", name, DataType.STRING)
            publish("$folder/Description", spec["description"] as? String ?: "", DataType.STRING)
            publish("$folder/Enabled", enabled, DataType.BOOLEAN)
            publish("$folder/Status", status, DataType.STRING)
            publishDiagnostics("$folder/Diagnostics", diag?.connected == true, diag?.requestCount ?: 0L,
                diag?.errorCount ?: 0L, diag?.timeoutCount ?: 0L, diag?.averageResponseTimeMs,
                diag?.samplingIntervalMs, diag?.publishIntervalMs)
        }
        val s7 = gw.host.instance(S7DriverModule.MODULE_ID) as? S7DriverModule
        for (stored in gw.store.list(S7DriverModule.MODULE_ID, Scope.GATEWAY, S7_DEVICE)) {
            val name = stored.id.name
            if (!segmentOk(name) || name in seen) continue
            seen += name
            val spec = stored.resource.spec
            val enabled = spec["enabled"] as? Boolean ?: true
            val device = if (enabled) s7?.device(name) else null
            val status = when {
                !enabled -> "Disabled"
                else -> device?.connectionStatus()?.state?.let { title(it.name) } ?: "Not Started"
            }
            val folder = "Gateway/Device/$name"
            publish("$folder/Name", name, DataType.STRING)
            publish("$folder/Description", spec["description"] as? String ?: "", DataType.STRING)
            publish("$folder/Enabled", enabled, DataType.BOOLEAN)
            publish("$folder/Status", status, DataType.STRING)
            val diag = device?.diagnostics()
            publishDiagnostics("$folder/Diagnostics", diag?.connected == true, diag?.requestCount ?: 0L,
                diag?.errorCount ?: 0L, diag?.timeoutCount ?: 0L, diag?.averageResponseTimeMs,
                diag?.samplingIntervalMs, diag?.publishIntervalMs)
        }
        val bacnet = gw.host.instance(BacnetDriverModule.MODULE_ID) as? BacnetDriverModule
        driverDevices(BacnetDriverModule.MODULE_ID, BACNET_DEVICE, seen) { name ->
            bacnet?.device(name)?.let { d ->
                val st = d.connectionStatus()
                DriverSummary(st.state.name, st.connected, d.requestCount.get(), d.failureCount.get(), d.timeoutCount.get())
            }
        }
        val dnp3 = gw.host.instance(Dnp3DriverModule.MODULE_ID) as? Dnp3DriverModule
        driverDevices(Dnp3DriverModule.MODULE_ID, DNP3_DEVICE, seen) { name ->
            dnp3?.device(name)?.let { d ->
                val st = d.connectionStatus()
                DriverSummary(st.state.name, st.connected, d.requestCount.get(), d.failureCount.get(), d.timeoutCount.get())
            }
        }
        prune("Gateway/Device", seen, DEVICE_LEAVES)
    }

    private class DriverSummary(val state: String, val connected: Boolean, val requests: Long, val errors: Long, val timeouts: Long)

    /** Device folders for a driver whose devices report a connection status and request counters. */
    private fun driverDevices(module: String, kind: String, seen: MutableSet<String>, summary: (String) -> DriverSummary?) {
        for (stored in gw.store.list(module, Scope.GATEWAY, kind)) {
            val name = stored.id.name
            if (!segmentOk(name) || name in seen) continue
            seen += name
            val spec = stored.resource.spec
            val enabled = spec["enabled"] as? Boolean ?: true
            val s = if (enabled) summary(name) else null
            val status = if (!enabled) "Disabled" else s?.state?.let { title(it) } ?: "Not Started"
            val folder = "Gateway/Device/$name"
            publish("$folder/Name", name, DataType.STRING)
            publish("$folder/Description", spec["description"] as? String ?: "", DataType.STRING)
            publish("$folder/Enabled", enabled, DataType.BOOLEAN)
            publish("$folder/Status", status, DataType.STRING)
            publishDiagnostics("$folder/Diagnostics", s?.connected == true, s?.requests ?: 0L, s?.errors ?: 0L, s?.timeouts ?: 0L, null, null, null)
        }
    }

    private fun opcConnections() {
        val module = gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule
        val seen = HashSet<String>()
        for (stored in gw.store.list(OpcUaClientModule.MODULE_ID, Scope.GATEWAY, CONNECTION_KIND)) {
            val name = stored.id.name
            if (!segmentOk(name)) continue
            seen += name
            val enabled = stored.resource.spec["enabled"] as? Boolean ?: true
            val status = if (enabled) module?.connection(name)?.connectionStatus() else null
            val folder = "Gateway/OPC/$name"
            publish("$folder/Enabled", enabled, DataType.BOOLEAN)
            publish("$folder/Connected", status?.connected == true, DataType.BOOLEAN)
            publish("$folder/State", if (!enabled) "Disabled" else status?.state?.let { title(it.name) } ?: "Not Started", DataType.STRING)
            val diag = if (enabled) module?.connection(name)?.diagnostics() else null
            publishDiagnostics("$folder/Diagnostics", diag?.connected == true, diag?.requestCount ?: 0L,
                diag?.errorCount ?: 0L, diag?.timeoutCount ?: 0L, diag?.averageResponseTimeMs,
                diag?.samplingIntervalMs, diag?.publishIntervalMs)
            publish("$folder/Diagnostics/MonitoredItemCount", diag?.monitoredItemCount ?: 0, DataType.INT32)
        }
        prune("Gateway/OPC", seen, OPC_LEAVES)
    }

    /**
     * Per-device diagnostic tags. Values come from the driver's atomics; this method does no I/O.
     * Doc source: FEATURES DEV-007.
     */
    private fun publishDiagnostics(
        folder: String, connected: Boolean, requests: Long, errors: Long, timeouts: Long,
        averageMs: Double?, samplingMs: Double?, publishMs: Double?,
    ) {
        publish("$folder/Connected", connected, DataType.BOOLEAN)
        publish("$folder/RequestCount", requests, DataType.INT64)
        publish("$folder/ErrorCount", errors, DataType.INT64)
        publish("$folder/TimeoutCount", timeouts, DataType.INT64)
        publish("$folder/AverageResponseTime", averageMs, DataType.DOUBLE)
        publish("$folder/SamplingInterval", samplingMs, DataType.DOUBLE)
        publish("$folder/PublishInterval", publishMs, DataType.DOUBLE)
    }

    private fun opcUaServer() {
        val enabled = gw.settings.opcua.enabled
        val running = gw.opcUa?.running == true
        publish("Gateway/OPC UA Server/Enabled", enabled, DataType.BOOLEAN)
        publish("Gateway/OPC UA Server/Running", running, DataType.BOOLEAN)
        publish("Gateway/OPC UA Server/State", when {
            !enabled -> "Disabled"
            running -> "Running"
            else -> "Stopped"
        }, DataType.STRING)
        // Only touch the trust store when the server uses it, so a disabled server leaves no pki directory.
        if (enabled) {
            val trust = gw.opcUaTrust
            publish("Gateway/OPC UA Server/TrustedCertificates", trust.trusted().size, DataType.INT32)
            publish("Gateway/OPC UA Server/QuarantinedCertificates", trust.quarantined().size, DataType.INT32)
        }
    }

    /** Publish when the value moved. [force] always publishes (the clock tag). */
    private fun publish(path: String, value: Any?, type: DataType, force: Boolean = false) {
        if (!force && last.containsKey(path) && last[path] == value) return
        engine.publishSystem(path, value, type)
        last[path] = value
    }

    private fun prune(root: String, seen: Set<String>, leaves: List<String>) {
        val known = dynamic.getOrPut(root) { HashSet() }
        for (gone in known - seen) {
            for (leaf in leaves) {
                val path = "$root/$gone/$leaf"
                runCatching { engine.remove("[system]$path") }
                last.remove(path)
            }
        }
        known.clear()
        known += seen
    }

    private fun guarded(group: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            log.debug("system tags: {} refresh failed: {}", group, e.toString())
        }
    }

    companion object {
        const val THREAD = "system-tags"
        const val FAST_MS = 1_000L
        /** Slower groups run every fifth tick (5 s). Device diagnostics run on every tick. */
        const val SLOW_EVERY = 5L
        private val DIAGNOSTIC_LEAVES = listOf(
            "Diagnostics/Connected", "Diagnostics/RequestCount", "Diagnostics/ErrorCount", "Diagnostics/TimeoutCount",
            "Diagnostics/AverageResponseTime", "Diagnostics/SamplingInterval", "Diagnostics/PublishInterval",
        )
        private val DEVICE_LEAVES = listOf("Name", "Description", "Enabled", "Status") + DIAGNOSTIC_LEAVES
        private val OPC_LEAVES = listOf("Enabled", "Connected", "State") + DIAGNOSTIC_LEAVES + "Diagnostics/MonitoredItemCount"
        private val log = LoggerFactory.getLogger(SystemTags::class.java)
        private val SEGMENT = Regex("^[A-Za-z0-9_ -]+$")

        private fun segmentOk(name: String) = SEGMENT.matches(name)

        private fun title(enumName: String): String =
            enumName.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}
