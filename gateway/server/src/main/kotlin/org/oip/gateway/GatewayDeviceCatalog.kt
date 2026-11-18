package org.oip.gateway

import org.oip.bacnet.BacnetDriverModule
import org.oip.bacnet.BacnetRemoteDevice
import org.oip.bacnet.REMOTE_DEVICE_KIND as BACNET_DEVICE
import org.oip.config.Scope
import org.oip.dnp3.Dnp3DriverModule
import org.oip.dnp3.Dnp3Outstation
import org.oip.dnp3.OUTSTATION_KIND as DNP3_DEVICE
import org.oip.logix.LogixDevice
import org.oip.logix.LogixDriverModule
import org.oip.logix.DEVICE_KIND as LOGIX_DEVICE
import org.oip.modbus.AddressException
import org.oip.modbus.DEVICE_KIND
import org.oip.modbus.ModbusAddress
import org.oip.modbus.ModbusDevice
import org.oip.modbus.ModbusDriverModule
import org.oip.mqtt.CONNECTION_KIND as MQTT_CONNECTION
import org.oip.mqtt.MqttConnection
import org.oip.mqtt.MqttDriverModule
import org.oip.opcua.CONNECTION_KIND as OPC_CONNECTION
import org.oip.opcua.OpcUaClientModule
import org.oip.opcua.OpcUaConnection
import org.oip.opcua.server.DeviceCatalog
import org.oip.opcua.server.DeviceEndpoint
import org.oip.opcua.server.DeviceItem
import org.oip.opcua.server.DeviceNodeIds
import org.oip.opcua.server.DeviceReading
import org.oip.opcua.server.revisedDriverInterval
import org.oip.s7.AddressException as S7AddressException
import org.oip.s7.DEVICE_KIND as S7_DEVICE
import org.oip.s7.S7Address
import org.oip.s7.S7Device
import org.oip.s7.S7DriverModule
import org.oip.simulator.DEVICE_KIND as SIM_DEVICE
import org.oip.simulator.SimulatorDevice
import org.oip.simulator.SimulatorDriverModule
import org.oip.udptcp.DEVICE_KIND as UDPTCP_DEVICE
import org.oip.udptcp.ListenerDevice
import org.oip.udptcp.UdpTcpDriverModule
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Live view of the built-in drivers for the OPC UA `Devices` folder.
 *
 * Names come from the config store; the endpoint delegates to the driver instance that is running
 * now, so a device created or deleted through config appears or disappears without a server restart.
 * Two drivers given the same name are listed as `<driver>:<name>` (browse name `<name> (<driver>)`).
 *
 * Modbus has no device browse. Each area lists its first [MODBUS_BROWSE_SPAN] addresses, and any
 * address the driver already polls is listed on the device. Any other valid address is still readable
 * and writable by its node id.
 *
 * The programmable simulator exposes the program's folder tree, including `[Controls]` and, for a
 * preloaded program, the `_Meta:` item paths. S7 lists the addresses the driver is polling. Any
 * other valid S7 address is still readable and writable by its node id. Sampling stays on the
 * driver's own thread, the same way Modbus and Logix do it.
 *
 * doc source: FEATURES DEV-028
 * doc source: FEATURES DEV-004
 */
class GatewayDeviceCatalog(private val gw: Gateway) : DeviceCatalog {
    private val modbusEndpoints = ConcurrentHashMap<String, ModbusEndpoint>()
    private val logixEndpoints = ConcurrentHashMap<String, LogixEndpoint>()
    private val simulatorEndpoints = ConcurrentHashMap<String, SimulatorEndpoint>()
    private val s7Endpoints = ConcurrentHashMap<String, S7Endpoint>()
    private val opcEndpoints = ConcurrentHashMap<String, OpcUaEndpoint>()
    private val mqttEndpoints = ConcurrentHashMap<String, MqttEndpoint>()
    private val udpTcpEndpoints = ConcurrentHashMap<String, UdpTcpEndpoint>()
    private val bacnetEndpoints = ConcurrentHashMap<String, BacnetEndpoint>()
    private val dnp3Endpoints = ConcurrentHashMap<String, Dnp3Endpoint>()

    override fun devices(): List<DeviceEndpoint> {
        val found = ArrayList<DeviceEndpoint>()
        val modbus = modbus()
        if (modbus != null) {
            for (stored in gw.store.list(ModbusDriverModule.MODULE_ID, Scope.GATEWAY, DEVICE_KIND)) {
                liveModbus(stored.id.name)?.let { found += it }
            }
        }
        val logix = logix()
        if (logix != null) {
            for (stored in gw.store.list(LogixDriverModule.MODULE_ID, Scope.GATEWAY, LOGIX_DEVICE)) {
                liveLogix(stored.id.name)?.let { found += it }
            }
        }
        val simulator = simulator()
        if (simulator != null) {
            for (stored in gw.store.list(SimulatorDriverModule.MODULE_ID, Scope.GATEWAY, SIM_DEVICE)) {
                liveSimulator(stored.id.name)?.let { found += it }
            }
        }
        val s7 = s7()
        if (s7 != null) {
            for (stored in gw.store.list(S7DriverModule.MODULE_ID, Scope.GATEWAY, S7_DEVICE)) {
                liveS7(stored.id.name)?.let { found += it }
            }
        }
        val opc = opc()
        if (opc != null) {
            for (stored in gw.store.list(OpcUaClientModule.MODULE_ID, Scope.GATEWAY, OPC_CONNECTION)) {
                liveOpc(stored.id.name)?.let { found += it }
            }
        }
        val mqtt = mqtt()
        if (mqtt != null) {
            for (stored in gw.store.list(MqttDriverModule.MODULE_ID, Scope.GATEWAY, MQTT_CONNECTION)) {
                liveMqtt(stored.id.name)?.let { found += it }
            }
        }
        val udpTcp = udpTcp()
        if (udpTcp != null) {
            for (stored in gw.store.list(UdpTcpDriverModule.MODULE_ID, Scope.GATEWAY, UDPTCP_DEVICE)) {
                liveUdpTcp(stored.id.name)?.let { found += it }
            }
        }
        val bacnet = bacnet()
        if (bacnet != null) {
            for (stored in gw.store.list(BacnetDriverModule.MODULE_ID, Scope.GATEWAY, BACNET_DEVICE)) {
                liveBacnet(stored.id.name)?.let { found += it }
            }
        }
        val dnp3 = dnp3()
        if (dnp3 != null) {
            for (stored in gw.store.list(Dnp3DriverModule.MODULE_ID, Scope.GATEWAY, DNP3_DEVICE)) {
                liveDnp3(stored.id.name)?.let { found += it }
            }
        }
        return disambiguate(found).sortedBy { it.browseName }
    }

    override fun device(name: String): DeviceEndpoint? {
        val split = name.indexOf(QUALIFIER)
        if (split > 0) {
            val driver = name.substring(0, split)
            val raw = name.substring(split + QUALIFIER.length)
            val endpoint = when (driver) {
                DRIVER_MODBUS -> liveModbus(raw)
                DRIVER_LOGIX -> liveLogix(raw)
                DRIVER_SIMULATOR -> liveSimulator(raw)
                DRIVER_S7 -> liveS7(raw)
                DRIVER_OPCUA -> liveOpc(raw)
                DRIVER_MQTT -> liveMqtt(raw)
                DRIVER_UDPTCP -> liveUdpTcp(raw)
                DRIVER_BACNET -> liveBacnet(raw)
                DRIVER_DNP3 -> liveDnp3(raw)
                else -> null
            } ?: return null
            return Qualified(endpoint, name, "${endpoint.browseName} ($driver)")
        }
        val hits = listOfNotNull(
            liveModbus(name), liveLogix(name), liveSimulator(name), liveS7(name), liveOpc(name), liveMqtt(name),
            liveUdpTcp(name), liveBacnet(name), liveDnp3(name),
        )
        return hits.singleOrNull()
    }

    private fun disambiguate(found: List<DeviceEndpoint>): List<DeviceEndpoint> {
        val counts = HashMap<String, Int>()
        for (endpoint in found) counts[endpoint.name] = (counts[endpoint.name] ?: 0) + 1
        if (counts.values.all { it == 1 }) return found
        return found.map { endpoint ->
            if ((counts[endpoint.name] ?: 0) == 1) endpoint
            else Qualified(endpoint, "${endpoint.driver}$QUALIFIER${endpoint.name}", "${endpoint.browseName} (${endpoint.driver})")
        }
    }

    private fun modbus(): ModbusDriverModule? = gw.host.instance(ModbusDriverModule.MODULE_ID) as? ModbusDriverModule
    private fun logix(): LogixDriverModule? = gw.host.instance(LogixDriverModule.MODULE_ID) as? LogixDriverModule
    private fun simulator(): SimulatorDriverModule? = gw.host.instance(SimulatorDriverModule.MODULE_ID) as? SimulatorDriverModule
    private fun s7(): S7DriverModule? = gw.host.instance(S7DriverModule.MODULE_ID) as? S7DriverModule
    private fun opc(): OpcUaClientModule? = gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule
    private fun mqtt(): MqttDriverModule? = gw.host.instance(MqttDriverModule.MODULE_ID) as? MqttDriverModule
    private fun udpTcp(): UdpTcpDriverModule? = gw.host.instance(UdpTcpDriverModule.MODULE_ID) as? UdpTcpDriverModule

    private fun bacnet(): BacnetDriverModule? = gw.host.instance(BacnetDriverModule.MODULE_ID) as? BacnetDriverModule

    private fun dnp3(): Dnp3DriverModule? = gw.host.instance(Dnp3DriverModule.MODULE_ID) as? Dnp3DriverModule

    private fun liveDnp3(name: String): DeviceEndpoint? {
        val module = dnp3() ?: return null
        if (module.device(name) == null) {
            dnp3Endpoints.remove(name)
            return null
        }
        return dnp3Endpoints.computeIfAbsent(name) { Dnp3Endpoint(module, name) }
    }

    private fun liveBacnet(name: String): DeviceEndpoint? {
        val module = bacnet() ?: return null
        if (module.device(name) == null) {
            bacnetEndpoints.remove(name)
            return null
        }
        return bacnetEndpoints.computeIfAbsent(name) { BacnetEndpoint(module, name) }
    }

    private fun liveUdpTcp(name: String): DeviceEndpoint? {
        val module = udpTcp() ?: return null
        if (module.device(name) == null) {
            udpTcpEndpoints.remove(name)
            return null
        }
        return udpTcpEndpoints.computeIfAbsent(name) { UdpTcpEndpoint(module, name) }
    }

    private fun liveModbus(name: String): DeviceEndpoint? {
        val module = modbus() ?: return null
        if (module.device(name) == null) {
            modbusEndpoints.remove(name)
            return null
        }
        return modbusEndpoints.computeIfAbsent(name) { ModbusEndpoint(module, name) }
    }

    private fun liveLogix(name: String): DeviceEndpoint? {
        val module = logix() ?: return null
        if (module.device(name) == null) {
            logixEndpoints.remove(name)
            return null
        }
        return logixEndpoints.computeIfAbsent(name) { LogixEndpoint(module, name) }
    }

    private fun liveSimulator(name: String): DeviceEndpoint? {
        val module = simulator() ?: return null
        if (module.device(name) == null) {
            simulatorEndpoints.remove(name)
            return null
        }
        return simulatorEndpoints.computeIfAbsent(name) { SimulatorEndpoint(module, name) }
    }

    private fun liveS7(name: String): DeviceEndpoint? {
        val module = s7() ?: return null
        if (module.device(name) == null) {
            s7Endpoints.remove(name)
            return null
        }
        return s7Endpoints.computeIfAbsent(name) { S7Endpoint(module, name) }
    }

    private fun liveOpc(name: String): DeviceEndpoint? {
        val module = opc() ?: return null
        if (module.connection(name) == null) {
            opcEndpoints.remove(name)
            return null
        }
        return opcEndpoints.computeIfAbsent(name) { OpcUaEndpoint(module, name) }
    }

    private fun liveMqtt(name: String): DeviceEndpoint? {
        val module = mqtt() ?: return null
        if (module.connection(name) == null) {
            mqttEndpoints.remove(name)
            return null
        }
        return mqttEndpoints.computeIfAbsent(name) { MqttEndpoint(module, name) }
    }

    private class Qualified(
        private val inner: DeviceEndpoint,
        override val name: String,
        override val browseName: String,
    ) : DeviceEndpoint {
        override val driver: String get() = inner.driver
        override fun browse(nodeId: String) = inner.browse(nodeId)
        override fun describe(nodeId: String) = inner.describe(nodeId)
        override fun read(nodeIds: List<String>) = inner.read(nodeIds)
        override fun write(nodeId: String, value: Any?) = inner.write(nodeId, value)
        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double) =
            inner.revisedSamplingIntervalMs(nodeId, requestedMs)
        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit) =
            inner.watch(nodeId, samplingIntervalMs, listener)
    }

    private class ModbusEndpoint(
        private val module: ModbusDriverModule,
        override val name: String,
    ) : DeviceEndpoint {
        override val driver: String = DRIVER_MODBUS

        private fun live(): ModbusDevice? = module.device(name)

        override fun browse(nodeId: String): List<DeviceItem> {
            val device = live() ?: return emptyList()
            if (nodeId.isEmpty()) {
                val areas = AREAS.map { (code, label) ->
                    DeviceItem("$AREA_PREFIX$code", label, variable = false, readable = false, writable = false)
                }
                val polled = device.browse("").map { node ->
                    DeviceItem(node.nodeId, node.displayName, variable = true, readable = node.readable, writable = node.writable)
                }
                return areas + polled
            }
            val code = nodeId.removePrefix(AREA_PREFIX)
            if (!nodeId.startsWith(AREA_PREFIX) || AREAS.none { it.first == code }) return emptyList()
            val first = if (device.config.zeroBasedAddressing) 0 else 1
            val writable = code == "HR" || code == "C"
            return (0 until MODBUS_BROWSE_SPAN).map { offset ->
                val address = "$code${first + offset}"
                DeviceItem(address, address, variable = true, readable = true, writable = writable)
            }
        }

        override fun describe(nodeId: String): DeviceItem? {
            val device = live() ?: return null
            if (nodeId.startsWith(AREA_PREFIX)) {
                val code = nodeId.removePrefix(AREA_PREFIX)
                val label = AREAS.find { it.first == code }?.second ?: return null
                return DeviceItem(nodeId, label, variable = false, readable = false, writable = false)
            }
            val parsed = try {
                ModbusAddress.parse(nodeId, device.config)
            } catch (_: AddressException) {
                null
            } ?: return null
            return DeviceItem(nodeId, nodeId, variable = true, readable = true, writable = parsed.writable)
        }

        override fun read(nodeIds: List<String>): List<DeviceReading> {
            val device = live() ?: return nodeIds.map { gone() }
            return device.read(nodeIds).map { sample ->
                DeviceReading(sample.value, sample.quality.statusCode, sample.sourceTimestamp, sample.timestamp)
            }
        }

        override fun write(nodeId: String, value: Any?): Long {
            val device = live() ?: return GONE
            return device.write(nodeId, value).quality.statusCode
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double {
            val device = live() ?: return revisedDriverInterval(requestedMs)
            // Join the scan that already reads this point (an OPC tag spells it `1.HR1`, the
            // device node spells it `HR1`) so both listeners share one PDU.
            return revisedDriverInterval(requestedMs, device.activeScanPeriodMs(nodeId)?.toDouble())
        }

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            val device = live() ?: return gone(listener)
            val period = revisedSamplingIntervalMs(nodeId, samplingIntervalMs).toLong()
            return device.attach(period, nodeId) { sample ->
                listener(DeviceReading(sample.value, sample.quality.statusCode, sample.sourceTimestamp, sample.timestamp))
            }
        }
    }

    private class LogixEndpoint(
        private val module: LogixDriverModule,
        override val name: String,
    ) : DeviceEndpoint {
        override val driver: String = DRIVER_LOGIX

        private fun live(): LogixDevice? = module.device(name)

        override fun browse(nodeId: String): List<DeviceItem> {
            val device = live() ?: return emptyList()
            return device.browse(nodeId).map { node ->
                val variable = node.nodeClass == "Variable"
                DeviceItem(node.nodeId, node.displayName, variable = variable, readable = node.readable, writable = node.writable)
            }
        }

        override fun describe(nodeId: String): DeviceItem? {
            val device = live() ?: return null
            val node = device.describe(nodeId) ?: return null
            val variable = node.nodeClass == "Variable"
            return DeviceItem(node.nodeId, node.displayName, variable = variable, readable = node.readable, writable = node.writable)
        }

        override fun read(nodeIds: List<String>): List<DeviceReading> {
            val device = live() ?: return nodeIds.map { gone() }
            return device.read(nodeIds).map { sample ->
                DeviceReading(sample.value, sample.quality.statusCode, sample.sourceTimestamp, sample.timestamp)
            }
        }

        override fun write(nodeId: String, value: Any?): Long {
            val device = live() ?: return GONE
            return device.write(nodeId, value).quality.statusCode
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double {
            val device = live() ?: return revisedDriverInterval(requestedMs)
            return revisedDriverInterval(requestedMs, device.activeScanPeriodMs(nodeId)?.toDouble())
        }

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            val device = live() ?: return gone(listener)
            val period = revisedSamplingIntervalMs(nodeId, samplingIntervalMs).toLong()
            return device.attachRaw(period, nodeId) { value, quality, epoch ->
                val stamp = Instant.ofEpochMilli(epoch)
                listener(DeviceReading(value, quality.statusCode, null, stamp))
            }
        }
    }

    private class OpcUaEndpoint(
        private val module: OpcUaClientModule,
        override val name: String,
    ) : DeviceEndpoint {
        override val driver: String = DRIVER_OPCUA
        private val seen = ConcurrentHashMap<String, DeviceItem>()

        private fun live(): OpcUaConnection? = module.connection(name)

        override fun browse(nodeId: String): List<DeviceItem> {
            val connection = live() ?: return emptyList()
            val nodes = try {
                connection.browse(nodeId)
            } catch (_: RuntimeException) {
                emptyList()
            }
            val items = ArrayList<DeviceItem>(nodes.size)
            for (node in nodes) {
                if (node.nodeId.isEmpty()) continue
                val variable = node.nodeClass == "Variable"
                val objectNode = node.nodeClass == "Object"
                if (!variable && !objectNode) continue
                val item = DeviceItem(
                    node.nodeId,
                    node.displayName.ifBlank { node.nodeId },
                    variable = variable,
                    readable = variable && node.readable,
                    writable = variable && node.writable,
                )
                seen[item.nodeId] = item
                items += item
            }
            return items
        }

        override fun describe(nodeId: String): DeviceItem? {
            if (nodeId.isEmpty()) return null
            seen[nodeId]?.let { return it }
            if (live() == null) return null
            // A client that already knows the remote node id can read it without browsing first.
            if (!DeviceNodeIds.isRemoteNodeId(nodeId)) return null
            return DeviceItem(nodeId, nodeId.substringAfter("s=", nodeId), variable = true, readable = true, writable = true)
        }

        override fun read(nodeIds: List<String>): List<DeviceReading> {
            val connection = live() ?: return nodeIds.map { gone() }
            return connection.read(nodeIds).map { sample ->
                DeviceReading(sample.value, sample.quality.statusCode, sample.sourceTimestamp, sample.timestamp)
            }
        }

        override fun write(nodeId: String, value: Any?): Long {
            val connection = live() ?: return GONE
            return connection.write(nodeId, value).quality.statusCode
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double {
            val connection = live() ?: return revisedDriverInterval(requestedMs)
            return revisedDriverInterval(requestedMs, connection.activeSamplingMs(nodeId))
        }

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            val connection = live() ?: return gone(listener)
            val period = revisedSamplingIntervalMs(nodeId, samplingIntervalMs)
            // Joins a node the tag path is already sampling, so the remote server sees one item.
            return connection.watch(nodeId, period) { sample ->
                listener(DeviceReading(sample.value, sample.quality.statusCode, sample.sourceTimestamp, sample.timestamp))
            }
        }
    }

    private class MqttEndpoint(
        private val module: MqttDriverModule,
        override val name: String,
    ) : DeviceEndpoint {
        override val driver: String = DRIVER_MQTT
        private val seen = ConcurrentHashMap<String, DeviceItem>()

        private fun live(): MqttConnection? = module.connection(name)

        override fun browse(nodeId: String): List<DeviceItem> {
            if (nodeId.isNotEmpty() || live() == null) return emptyList()
            val items = ArrayList<DeviceItem>()
            module.listen { metric ->
                if (metric.connection != name || metric.address.isEmpty()) return@listen
                val item = DeviceItem(
                    metric.address,
                    metric.path.ifBlank { metric.address },
                    variable = true,
                    readable = true,
                    writable = metric.writable,
                )
                seen[item.nodeId] = item
                items += item
            }.close()
            return items
        }

        override fun describe(nodeId: String): DeviceItem? {
            if (nodeId.isEmpty() || live() == null) return null
            seen[nodeId]?.let { return it }
            browse("")
            return seen[nodeId]
        }

        override fun read(nodeIds: List<String>): List<DeviceReading> {
            val connection = live() ?: return nodeIds.map { gone() }
            return nodeIds.map { id ->
                val sample = connection.read(id)
                val stamp = if (sample.epochMillis > 0) Instant.ofEpochMilli(sample.epochMillis) else Instant.now()
                DeviceReading(sample.value, sample.quality.statusCode, null, stamp)
            }
        }

        override fun write(nodeId: String, value: Any?): Long {
            val connection = live() ?: return GONE
            return connection.write(nodeId, value).quality.statusCode
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double {
            val connection = live() ?: return revisedDriverInterval(requestedMs)
            return revisedDriverInterval(requestedMs, connection.activeSamplingMs(nodeId)?.toDouble())
        }

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            val connection = live() ?: return gone(listener)
            val period = revisedSamplingIntervalMs(nodeId, samplingIntervalMs).toLong()
            return connection.attach(nodeId, period, onSample = { value, quality, epoch ->
                val stamp = if (epoch > 0) Instant.ofEpochMilli(epoch) else Instant.now()
                listener(DeviceReading(value, quality.statusCode, null, stamp))
            })
        }
    }

    /**
     * Programmable simulator. Values live in the device, so a read does not open a socket.
     * [watch] joins the device scheduler; the listener runs there, not on an OPC UA stack thread.
     * doc source: FEATURES DEV-028
     */
    private class SimulatorEndpoint(
        private val module: SimulatorDriverModule,
        override val name: String,
    ) : DeviceEndpoint {
        override val driver: String = DRIVER_SIMULATOR

        private fun live(): SimulatorDevice? = module.device(name)

        override fun browse(nodeId: String): List<DeviceItem> {
            val device = live() ?: return emptyList()
            return device.browse(nodeId).map { node ->
                val variable = node.nodeClass == "Variable"
                DeviceItem(node.nodeId, node.browseName, variable = variable, readable = node.readable, writable = node.writable)
            }
        }

        override fun describe(nodeId: String): DeviceItem? {
            val device = live() ?: return null
            val node = device.describe(nodeId) ?: return null
            val variable = node.nodeClass == "Variable"
            return DeviceItem(node.nodeId, node.browseName, variable = variable, readable = node.readable, writable = node.writable)
        }

        override fun read(nodeIds: List<String>): List<DeviceReading> {
            val device = live() ?: return nodeIds.map { gone() }
            return nodeIds.map { id ->
                val sample = device.read(id)
                val stamp = Instant.ofEpochMilli(sample.epochMillis)
                DeviceReading(sample.value, sample.quality.statusCode, null, stamp)
            }
        }

        override fun write(nodeId: String, value: Any?): Long {
            val device = live() ?: return GONE
            return device.write(nodeId, value).quality.statusCode
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double {
            val device = live() ?: return revisedDriverInterval(requestedMs)
            return revisedDriverInterval(requestedMs, device.activeScanPeriodMs(nodeId)?.toDouble())
        }

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            val device = live() ?: return gone(listener)
            val period = revisedSamplingIntervalMs(nodeId, samplingIntervalMs).toLong()
            return device.attach(period, nodeId) { value, quality, epoch ->
                val stamp = Instant.ofEpochMilli(epoch)
                listener(DeviceReading(value, quality.statusCode, null, stamp))
            }
        }
    }

    /**
     * Siemens S7. Browse lists addresses the driver is already polling. Any other address the
     * model accepts is still a node. The ISO session is the driver's; [watch] uses that scan
     * thread, the same split as Logix.
     * doc source: FEATURES DEV-004
     */
    private class S7Endpoint(
        private val module: S7DriverModule,
        override val name: String,
    ) : DeviceEndpoint {
        override val driver: String = DRIVER_S7

        private fun live(): S7Device? = module.device(name)

        override fun browse(nodeId: String): List<DeviceItem> {
            val device = live() ?: return emptyList()
            return device.browse(nodeId).map { node ->
                val variable = node.nodeClass == "Variable"
                DeviceItem(node.nodeId, node.displayName, variable = variable, readable = node.readable, writable = node.writable)
            }
        }

        override fun describe(nodeId: String): DeviceItem? {
            val device = live() ?: return null
            if (nodeId.isEmpty()) return null
            try {
                S7Address.parse(nodeId, device.config.model)
            } catch (_: S7AddressException) {
                return null
            }
            return DeviceItem(nodeId, nodeId, variable = true, readable = true, writable = true)
        }

        override fun read(nodeIds: List<String>): List<DeviceReading> {
            val device = live() ?: return nodeIds.map { gone() }
            return device.read(nodeIds).map { sample ->
                DeviceReading(sample.value, sample.quality.statusCode, sample.sourceTimestamp, sample.timestamp)
            }
        }

        override fun write(nodeId: String, value: Any?): Long {
            val device = live() ?: return GONE
            return device.write(nodeId, value).quality.statusCode
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double {
            val device = live() ?: return revisedDriverInterval(requestedMs)
            return revisedDriverInterval(requestedMs, device.activeScanPeriodMs(nodeId)?.toDouble())
        }

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            val device = live() ?: return gone(listener)
            val period = revisedSamplingIntervalMs(nodeId, samplingIntervalMs).toLong()
            return device.attachRaw(period, nodeId) { value, quality, epoch ->
                val stamp = Instant.ofEpochMilli(epoch)
                listener(DeviceReading(value, quality.statusCode, null, stamp))
            }
        }
    }

    /**
     * UDP/TCP listener: a folder per port holding the last message, its fields and the counters.
     * The values come from the device's own receive thread; [watch] samples them on its scan thread.
     * doc source: FEATURES DEV-037
     */
    private class UdpTcpEndpoint(
        private val module: UdpTcpDriverModule,
        override val name: String,
    ) : DeviceEndpoint {
        override val driver: String = DRIVER_UDPTCP

        private fun live(): ListenerDevice? = module.device(name)

        override fun browse(nodeId: String): List<DeviceItem> {
            val device = live() ?: return emptyList()
            return device.browse(nodeId).map { node ->
                DeviceItem(node.nodeId, node.displayName, variable = node.nodeClass == "Variable", readable = node.readable, writable = node.writable)
            }
        }

        override fun describe(nodeId: String): DeviceItem? {
            val node = live()?.describe(nodeId) ?: return null
            return DeviceItem(node.nodeId, node.displayName, variable = node.nodeClass == "Variable", readable = node.readable, writable = node.writable)
        }

        override fun read(nodeIds: List<String>): List<DeviceReading> {
            val device = live() ?: return nodeIds.map { gone() }
            return device.read(nodeIds).map { sample ->
                DeviceReading(sample.value, sample.quality.statusCode, sample.sourceTimestamp, sample.timestamp)
            }
        }

        override fun write(nodeId: String, value: Any?): Long {
            val device = live() ?: return GONE
            return device.write(nodeId, value).quality.statusCode
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double {
            val device = live() ?: return revisedDriverInterval(requestedMs)
            return revisedDriverInterval(requestedMs, device.activeScanPeriodMs(nodeId)?.toDouble())
        }

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            val device = live() ?: return gone(listener)
            val period = revisedSamplingIntervalMs(nodeId, samplingIntervalMs).toLong()
            return device.attachRaw(period, nodeId) { value, quality, epoch ->
                listener(DeviceReading(value, quality.statusCode, null, Instant.ofEpochMilli(epoch)))
            }
        }
    }

    /**
     * BACnet/IP remote device: the browse tree is the device's object list, each object's properties and its status flags. Reads and [watch] use the device's scan thread.
     * doc source: FEATURES DEV-011, DEV-038
     */
    private class BacnetEndpoint(
        private val module: BacnetDriverModule,
        override val name: String,
    ) : DeviceEndpoint {
        override val driver: String = DRIVER_BACNET

        private fun live(): BacnetRemoteDevice? = module.device(name)

        override fun browse(nodeId: String): List<DeviceItem> {
            val device = live() ?: return emptyList()
            return device.browse(nodeId).map { node ->
                DeviceItem(node.nodeId, node.displayName, variable = node.nodeClass == "Variable", readable = node.readable, writable = node.writable)
            }
        }

        override fun describe(nodeId: String): DeviceItem? {
            val node = live()?.describe(nodeId) ?: return null
            return DeviceItem(node.nodeId, node.displayName, variable = node.nodeClass == "Variable", readable = node.readable, writable = node.writable)
        }

        override fun read(nodeIds: List<String>): List<DeviceReading> {
            val device = live() ?: return nodeIds.map { gone() }
            return device.read(nodeIds).map { sample ->
                DeviceReading(sample.value, sample.quality.statusCode, sample.sourceTimestamp, sample.timestamp)
            }
        }

        override fun write(nodeId: String, value: Any?): Long {
            val device = live() ?: return GONE
            return device.write(nodeId, value).quality.statusCode
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double {
            val device = live() ?: return revisedDriverInterval(requestedMs)
            return revisedDriverInterval(requestedMs, device.activeScanPeriodMs(nodeId)?.toDouble())
        }

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            val device = live() ?: return gone(listener)
            val period = revisedSamplingIntervalMs(nodeId, samplingIntervalMs).toLong()
            return device.attachRaw(period, nodeId) { value, quality, epoch ->
                listener(DeviceReading(value, quality.statusCode, null, Instant.ofEpochMilli(epoch)))
            }
        }
    }

    /**
     * DNP3 outstation: a folder per point type holding the points polled or reported so far, plus the IIN. Values come
     * from the master's integrity, event and unsolicited data; a write to a bo or ao point is a command.
     * doc source: FEATURES DEV-013, DEV-014
     */
    private class Dnp3Endpoint(
        private val module: Dnp3DriverModule,
        override val name: String,
    ) : DeviceEndpoint {
        override val driver: String = DRIVER_DNP3

        private fun live(): Dnp3Outstation? = module.device(name)

        override fun browse(nodeId: String): List<DeviceItem> {
            val device = live() ?: return emptyList()
            return device.browse(nodeId).map { node ->
                DeviceItem(node.nodeId, node.displayName, variable = node.nodeClass == "Variable", readable = node.readable, writable = node.writable)
            }
        }

        override fun describe(nodeId: String): DeviceItem? {
            val node = live()?.describe(nodeId) ?: return null
            return DeviceItem(node.nodeId, node.displayName, variable = node.nodeClass == "Variable", readable = node.readable, writable = node.writable)
        }

        override fun read(nodeIds: List<String>): List<DeviceReading> {
            val device = live() ?: return nodeIds.map { gone() }
            return device.read(nodeIds).map { sample ->
                DeviceReading(sample.value, sample.quality.statusCode, sample.sourceTimestamp, sample.timestamp)
            }
        }

        override fun write(nodeId: String, value: Any?): Long {
            val device = live() ?: return GONE
            return device.write(nodeId, value).quality.statusCode
        }

        override fun revisedSamplingIntervalMs(nodeId: String, requestedMs: Double): Double {
            val device = live() ?: return revisedDriverInterval(requestedMs)
            return revisedDriverInterval(requestedMs, device.activeScanPeriodMs(nodeId)?.toDouble())
        }

        override fun watch(nodeId: String, samplingIntervalMs: Double, listener: (DeviceReading) -> Unit): AutoCloseable {
            val device = live() ?: return gone(listener)
            val period = revisedSamplingIntervalMs(nodeId, samplingIntervalMs).toLong()
            return device.attachRaw(period, nodeId) { value, quality, epoch ->
                listener(DeviceReading(value, quality.statusCode, null, Instant.ofEpochMilli(epoch)))
            }
        }
    }

    private companion object {
        const val QUALIFIER = "\u001f"
        const val DRIVER_MODBUS = "modbus"
        const val DRIVER_LOGIX = "logix"
        const val DRIVER_SIMULATOR = "simulator"
        const val DRIVER_S7 = "s7"
        const val DRIVER_OPCUA = "opcua"
        const val DRIVER_MQTT = "mqtt"
        const val DRIVER_UDPTCP = "udp-tcp"
        const val DRIVER_BACNET = "bacnet"
        const val DRIVER_DNP3 = "dnp3"
        const val AREA_PREFIX = "area:"
        /** First addresses of each Modbus area. The map is not browsable; any later address is still a node. */
        const val MODBUS_BROWSE_SPAN = 16
        /** OPC UA Bad_NotConnected, the quality a removed device reports. */
        const val GONE = 0x808A0000L
        val AREAS = listOf(
            "HR" to "Holding registers",
            "IR" to "Input registers",
            "C" to "Coils",
            "DI" to "Discrete inputs",
        )

        fun gone() = DeviceReading(null, GONE, null, Instant.now())

        fun gone(listener: (DeviceReading) -> Unit): AutoCloseable {
            listener(gone())
            return AutoCloseable {}
        }
    }
}
