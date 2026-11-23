package org.oip.gateway

import org.oip.bacnet.BacnetDriverModule
import org.oip.config.Canon
import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.dnp3.Dnp3DriverModule
import org.oip.host.ModuleHost
import org.oip.logix.LogixDriverModule
import org.oip.modbus.ModbusDriverModule
import org.oip.modbus.RawSample
import org.oip.mqtt.DiscoveredMetric
import org.oip.mqtt.MqttDriverModule
import org.oip.opcua.OpcUaClientModule
import org.oip.s7.S7DriverModule
import org.oip.udptcp.UdpTcpDriverModule
import org.oip.simulator.SimulatorDriverModule
import org.oip.tags.DeviceWrite
import org.oip.tags.Quality
import org.oip.tags.QualityCode
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagGroupSchedule
import org.oip.tags.TagSample
import org.oip.redundancy.RuntimeGate
import org.oip.tags.TagType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Subscribes OPC tags to the Modbus device, S7 device, Logix device, OPC UA connection, MQTT
 * connection, programmable simulator, UDP/TCP listener (DEV-037), BACnet/IP remote device (DEV-038) or
 * DNP3 outstation (DEV-014) named by `opcServer`.
 * Device I/O stays on the driver threads. The tag engine lock is taken only to publish a sample.
 * Addresses that share a device and a scan period are one coalesced subscription. A tag that names a tag group is
 * read at that group's current rate: direct, driven (fast while its condition holds) or leased (fast while the tag
 * has a subscriber, otherwise the slow rate or not at all), and re-subscribed when that rate changes (TAG-006).
 */
class TagDriverBridge(
    private val engine: TagEngine,
    private val store: FileConfigStore,
    private val modbus: ModbusDriverModule?,
    private val opc: OpcUaClientModule?,
    private val mqtt: MqttDriverModule? = null,
    private val s7: S7DriverModule? = null,
    private val simulator: SimulatorDriverModule? = null,
    private val logix: LogixDriverModule? = null,
    private val udpTcp: UdpTcpDriverModule? = null,
    private val bacnet: BacnetDriverModule? = null,
    private val dnp3: Dnp3DriverModule? = null,
) : AutoCloseable {
    private val log = Logger.getLogger("org.oip.gateway.tags")
    private val binds = ConcurrentHashMap<String, Bind>()
    private val writes = Executors.newFixedThreadPool(4) { r -> Thread(r, "tag-device-write").apply { isDaemon = true } }
    /**
     * The last write queued per device item (source and address). A new write to the same item runs after it, so
     * writes reach the device in the order the engine queued them; different items still write in parallel.
     */
    private val writeTails = ConcurrentHashMap<String, CompletableFuture<Quality>>()
    /**
     * Cold and undecided do not subscribe or write devices. Warm subscribes and still does not write.
     * Active does both. An independent gateway leaves this at active.
     */
    @Volatile private var gate: RuntimeGate = RuntimeGate.ACTIVE

    fun setGate(next: RuntimeGate) {
        val previous = gate
        gate = next
        mqtt?.setPublishing(next.deviceWrites)
        if (previous.subscriptions == next.subscriptions) return
        if (!next.subscriptions) {
            binds.values.forEach { bind ->
                bind.handle?.close()
                bind.handle = null
            }
        } else {
            binds.values.forEach { bind -> bind.handle = subscribe(bind) }
        }
    }
    private var commsWatch: Thread? = null
    private val schedule = TagGroupSchedule(engine.groups, engine.clock, TagGroupSchedule.drivingCondition(engine))
    /** Subscriber count per [TagEngine.subscriptionKey], for leased groups. */
    private val subscribers = ConcurrentHashMap<String, Int>()
    /** Group details of the binds whose tag names a tag group; kept off [Bind] so an ungrouped tag costs nothing more. */
    private val grouping = ConcurrentHashMap<Bind, Grouped>()
    private var retuner: java.util.concurrent.ScheduledExecutorService? = null
    private var subscription: AutoCloseable? = null
    private var discovery: AutoCloseable? = null
    /** UDT members bound straight to a device address have no config resource; they arrive as engine config events. */
    private var memberWatch: AutoCloseable? = null
    private val members = Executors.newSingleThreadExecutor { r -> Thread(r, "tag-udt-members").apply { isDaemon = true } }

    fun start() {
        engine.deviceWrite = DeviceWrite { config, raw -> enqueueWrite(config, raw) }
        // Leased groups (TAG-025): called under the engine lock, so it only records the count.
        engine.subscriptionCountListener = { key, count ->
            if (count > 0) subscribers[key] = count else subscribers.remove(key)
            schedule.lease(key, count > 0)
        }
        subscription = store.subscribe { event -> onConfig(event) }
        for (stored in store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND)) bindResource(stored.id.name, stored.resource.spec)
        // UDT members with type opc (0.1.1 fix): the listener runs under the engine lock, so the bind runs on its own thread.
        memberWatch = engine.addConfigListener { provider, path, config -> onEngineTag(provider, path, config) }
        for ((provider, config) in engine.copyTagConfigs()) {
            if (config.type == TagType.OPC) onEngineTag(provider, config.path, config)
        }
        // A stalled socket may not report until its own timeout. The tag deadline is the
        // scan period plus the tag's comms timeout (SPEC §5), which can be sooner.
        // Sleep until that deadline. A fixed poll would add up to one period of lateness.
        commsWatch = Thread({ watchComms() }, "tag-comms-watch").apply { isDaemon = true; start() }
        discovery = mqtt?.listen { metric -> ensureSparkplugTag(metric) }
        retuner = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tag-group-rates").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { retune() }.onFailure { e -> log.log(Level.FINE, "tag group rates", e) } }, RETUNE_MS, RETUNE_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    override fun close() {
        retuner?.shutdownNow()
        retuner = null
        engine.subscriptionCountListener = null
        engine.deviceWrite = null
        discovery?.close()
        discovery = null
        subscription?.close()
        subscription = null
        memberWatch?.close()
        memberWatch = null
        members.shutdownNow()
        commsWatch?.interrupt()
        commsWatch?.join(1_000)
        commsWatch = null
        binds.values.forEach { it.handle?.close() }
        binds.clear()
        writes.shutdownNow()
    }

    /**
     * Wake at the earliest tag deadline. Recompute at least every [COMMS_WATCH_MS] so a tag
     * added during the wait is not invisible until a longer sleep ends. [TagEngine.checkComms]
     * re-reads each tag, so a sample that landed during the sleep is not marked lost.
     */
    private fun watchComms() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val now = engine.clock.millis()
                val next = engine.earliestCommsDeadlineMillis()
                if (next == Long.MAX_VALUE) {
                    Thread.sleep(COMMS_WATCH_MS)
                    continue
                }
                val delay = next - now
                if (delay <= 0) {
                    runCatching { engine.checkComms() }
                    // A clock that does not move would otherwise spin once the deadline is past.
                    if (engine.clock.millis() >= next) Thread.sleep(1)
                    continue
                }
                Thread.sleep(minOf(delay, COMMS_WATCH_MS))
                if (engine.clock.millis() >= next) runCatching { engine.checkComms() }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun onConfig(event: ConfigEvent) {
        val id = event.id ?: return
        when {
            id.module == TAGS_MODULE && id.kind == TAG_KIND -> onTag(id.name, event)
            id.kind == "Device" || id.kind == "Connection" -> onSource(id.name, event.type)
        }
    }

    private fun onTag(resourceId: String, event: ConfigEvent) {
        when (event.type) {
            ChangeType.DELETED -> unbind(resourceId)
            ChangeType.CREATED, ChangeType.UPDATED -> bindResource(resourceId, event.resource?.spec ?: return)
            ChangeType.INVALID -> log.fine("tag $resourceId invalid: ${event.error}")
        }
    }

    private fun onSource(name: String, type: ChangeType) {
        val server = Canon.share(name)
        val matched = binds.values.filter { it.server == server }
        if (type == ChangeType.DELETED) {
            for (bind in matched) {
                bind.handle?.close()
                bind.handle = null
                bind.sample.update(null, Quality(QualityCode.BAD_NOT_CONNECTED, "source '$name' removed"))
            }
            return
        }
        for (bind in matched) {
            bind.handle?.close()
            bind.handle = subscribe(bind)
        }
    }

    private fun bindResource(resourceId: String, raw: Map<String, Any?>) {
        val config = try {
            TagConfig.fromSpec(raw)
        } catch (e: RuntimeException) {
            log.log(Level.FINE, "tag $resourceId", e)
            return
        }
        unbind(resourceId)
        if (config.type != TagType.OPC) return
        val server = config.opcServer
        val item = config.opcItemPath
        if (server.isNullOrBlank() || item.isNullOrBlank()) return
        val configured = config.scanPeriodMillis.let { if (it < 10) 1000 else it }
        val path = userPath(config)
        // A tag that names a tag group is read at that group's current rate (TAG-006, review-2 F3); one that does not
        // keeps its own scanPeriodMillis.
        val group = if (raw.containsKey("tagGroup")) config.tagGroup else null
        val key = if (group != null) runCatching { engine.subscriptionKey(path) }.getOrNull() else null
        val bind = if (group != null && key != null) {
            schedule.assign(key, group)
            schedule.lease(key, (subscribers[key] ?: 0) > 0)
            val rate = schedule.ratesFor(listOf(key))[key] ?: configured
            Bind(engine.openSample(path), Canon.share(server), item, rate)
                .also { grouping[it] = Grouped(Canon.share(resourceId), group, key, path) }
        } else {
            Bind(engine.openSample(path), Canon.share(server), item, configured)
        }
        binds[Canon.share(resourceId)] = bind
        synchronized(bind) {
            bind.handle = subscribe(bind)
            applyEngineRate(bind)
        }
    }

    private fun memberKey(provider: String, path: String) = "\u0001udt:" + provider.lowercase() + "\u0000" + path.lowercase()

    /** Called under the engine write lock: only queues the work. */
    private fun onEngineTag(provider: String, path: String, config: TagConfig?) {
        val key = memberKey(provider, path)
        if (config == null || config.type != TagType.OPC) {
            if (binds.containsKey(key)) members.execute { unbind(key) }
            return
        }
        try {
            members.execute {
                runCatching { bindMember(key, config) }.onFailure { e -> log.log(Level.FINE, "udt member $path", e) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closing.
        }
    }

    private fun bindMember(key: String, config: TagConfig) {
        val path = userPath(config)
        // A tag with its own config resource is bound by bindResource.
        if (!engine.isGeneratedMember(path)) return
        unbind(key)
        val server = config.opcServer
        val item = config.opcItemPath
        if (server.isNullOrBlank() || item.isNullOrBlank()) return
        val configured = config.scanPeriodMillis.let { if (it < 10) 1000 else it }
        val bind = Bind(engine.openSample(path), Canon.share(server), item, configured)
        binds[key] = bind
        synchronized(bind) { bind.handle = subscribe(bind) }
    }

    /**
     * Tag groups (TAG-006/008/023/025): every [RETUNE_MS] the rate of each grouped tag is recomputed (a group edit, a
     * driven group's condition, a leased group's subscriber count), and an item whose rate moved is subscribed again
     * at the new rate, or closed while its leased group is off.
     */
    private fun retune() {
        if (grouping.isEmpty()) return
        val grouped = grouping.entries.toList()
        val rates = schedule.ratesFor(grouped.map { it.value.key })
        for ((bind, info) in grouped) {
            val rate = rates[info.key] ?: continue
            synchronized(bind) {
                if (binds[info.resourceId] !== bind) return@synchronized
                if (rate != bind.periodMs) {
                    bind.periodMs = rate
                    bind.handle?.close()
                    bind.handle = subscribe(bind)
                }
                applyEngineRate(bind)
            }
        }
    }

    /** The comms deadline follows the group rate; a tag configured before the engine node exists is retried. */
    private fun applyEngineRate(bind: Bind) {
        val info = grouping[bind] ?: return
        if (info.engineRate == bind.periodMs) return
        if (engine.setDeviceScanRate(info.path, bind.periodMs)) info.engineRate = bind.periodMs
    }

    private fun unbind(resourceId: String) {
        val bind = binds.remove(Canon.share(resourceId)) ?: binds.remove(resourceId) ?: return
        synchronized(bind) {
            bind.handle?.close()
            bind.handle = null
        }
        val info = grouping.remove(bind) ?: return
        if (grouping.values.none { it.key == info.key }) schedule.unassign(info.key)
    }

    private fun subscribe(bind: Bind): AutoCloseable? {
        if (!gate.subscriptions) return null
        if (bind.periodMs <= TagGroupSchedule.OFF_RATE) return null
        val modbusDevice = modbus?.device(bind.server)
        if (modbusDevice != null) {
            return modbusDevice.attachRaw(bind.periodMs, bind.item, bind)
        }
        val s7Device = s7?.device(bind.server)
        if (s7Device != null) {
            return s7Device.attachRaw(bind.periodMs, bind.item) { value, quality, epoch ->
                bind.sample.update(value, driverQuality(quality), epoch)
            }
        }
        val logixDevice = logix?.device(bind.server)
        if (logixDevice != null) {
            return logixDevice.attachRaw(bind.periodMs, bind.item) { value, quality, epoch ->
                bind.sample.update(value, driverQuality(quality), epoch)
            }
        }
        val listener = udpTcp?.device(bind.server)
        if (listener != null) {
            return listener.attachRaw(bind.periodMs, bind.item) { value, quality, epoch ->
                bind.sample.update(value, driverQuality(quality), epoch)
            }
        }
        val bacnetDevice = bacnet?.device(bind.server)
        if (bacnetDevice != null) {
            return bacnetDevice.attachRaw(bind.periodMs, bind.item) { value, quality, epoch ->
                bind.sample.update(value, driverQuality(quality), epoch)
            }
        }
        val outstation = dnp3?.device(bind.server)
        if (outstation != null) {
            return outstation.attachRaw(bind.periodMs, bind.item) { value, quality, epoch ->
                bind.sample.update(value, driverQuality(quality), epoch)
            }
        }
        val connection = opc?.connection(bind.server)
        if (connection != null) {
            return connection.watch(
                bind.item,
                bind.periodMs.toDouble(),
                // A keep-alive is not a new sample. Bump the silence timer unless the tag is
                // already bad, in which case the cached good value is what brings it back.
                onAlive = { sample ->
                    val current = bind.sample.read()
                    if (current.quality.level == org.oip.tags.Level.GOOD) bind.sample.touch()
                    else bind.sample.updateAt(sample.value, driverQuality(sample.quality), sample.sourceTimestamp)
                },
            ) { sample ->
                bind.sample.updateAt(sample.value, driverQuality(sample.quality), sample.sourceTimestamp)
            }
        }
        val mqttConnection = mqtt?.connection(bind.server)
        if (mqttConnection != null) {
            return mqttConnection.attach(
                bind.item,
                bind.periodMs,
                onSample = { value, quality, epoch ->
                    bind.sample.update(value, driverQuality(quality), epoch)
                },
                // A ping is not a new sample. Touch a good tag. Republish the cached sample when
                // the tag is already bad so an unchanged value comes back after comms loss.
                onAlive = { value, quality, epoch ->
                    val current = bind.sample.read()
                    if (current.quality.level == org.oip.tags.Level.GOOD) bind.sample.touch()
                    else bind.sample.update(value, driverQuality(quality), epoch)
                },
            )
        }
        val simulated = simulator?.device(bind.server)
        if (simulated != null) {
            return simulated.attach(bind.periodMs, bind.item) { value, quality, epoch ->
                bind.sample.update(value, driverQuality(quality), epoch)
            }
        }
        bind.sample.update(null, Quality(QualityCode.BAD_NOT_CONNECTED, "source '${bind.server}' is not configured"))
        return null
    }

    /** Sparkplug births become OPC tags. The config write runs off the MQTT socket thread. */
    private fun ensureSparkplugTag(metric: DiscoveredMetric) {
        val name = metric.resourceName()
        val id = ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, name)
        if (store.get(id) != null) return
        try {
            store.put(
                Scope.GATEWAY,
                Resource(
                    TAG_KIND,
                    Metadata(name, TAGS_MODULE),
                    mapOf(
                        "provider" to "default",
                        "path" to metric.path,
                        "type" to "opc",
                        "dataType" to metric.dataType,
                        "opcServer" to metric.connection,
                        "opcItemPath" to metric.address,
                        "scanPeriodMillis" to metric.scanPeriodMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        "writable" to metric.writable,
                    ),
                ),
                "mqtt",
            )
        } catch (e: RuntimeException) {
            log.log(Level.FINE, "sparkplug tag ${metric.address}", e)
        }
    }

    /**
     * Queues one device write behind the earlier writes to the same item and returns the device's answer
     * (review-2 F5). On success the tag takes the written value; on failure it goes back to the last value read
     * from the device, with the device's quality ([TagEngine.deviceWriteFailed]), and an OPC UA item is read
     * again so a subscription that reports only changes does not leave that quality in place. A backup gateway
     * (device writes gated off) refuses the write the same way. Called under the engine lock: nothing here blocks.
     */
    private fun enqueueWrite(config: TagConfig, raw: Any?): CompletableFuture<Quality>? {
        val server = config.opcServer ?: return null
        val item = config.opcItemPath ?: return null
        val path = userPath(config)
        val key = Canon.share(server) + "\u0000" + item
        return serial(key) {
            if (!gate.deviceWrites) {
                val refused = Quality(QualityCode.BAD_NOT_CONNECTED, "device writes are off on this gateway (redundancy backup)")
                engine.deviceWriteFailed(path, refused)
                return@serial refused
            }
            val quality = writeDevice(server, item, raw)
            if (quality.level == org.oip.tags.Level.GOOD) {
                engine.updateRaw(path, raw, quality, null)
            } else {
                engine.deviceWriteFailed(path, quality)
                refresh(server, item, path)
            }
            quality
        }
    }

    private fun serial(key: String, job: () -> Quality): CompletableFuture<Quality> {
        val done = CompletableFuture<Quality>()
        val previous = writeTails.put(key, done)
        val run = Runnable {
            val quality = try {
                job()
            } catch (e: RuntimeException) {
                log.log(Level.FINE, "device write $key", e)
                Quality(QualityCode.BAD_UNEXPECTED_ERROR, e.message)
            }
            writeTails.remove(key, done)
            done.complete(quality)
        }
        val submit = {
            try {
                writes.execute(run)
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                writeTails.remove(key, done)
                done.complete(Quality(QualityCode.BAD_NOT_CONNECTED, "gateway is stopping"))
            }
        }
        if (previous == null || previous.isDone) submit() else previous.whenComplete { _, _ -> submit() }
        return done
    }

    /** After a refused write: the item's current device value, for an OPC UA source (a Modbus poll does this itself). */
    private fun refresh(server: String, item: String, path: String) {
        val connection = opc?.connection(server) ?: return
        val current = runCatching { connection.read(listOf(item)).single() }.getOrNull() ?: return
        if (current.quality.statusCode != 0L) return
        engine.updateRaw(path, current.value, driverQuality(current.quality), current.sourceTimestamp)
    }

    private fun writeDevice(server: String, item: String, raw: Any?): Quality {
        val modbusDevice = modbus?.device(server)
        if (modbusDevice != null) {
            val result = modbusDevice.write(item, raw)
            return driverQuality(result.quality)
        }
        val s7Device = s7?.device(server)
        if (s7Device != null) {
            val result = s7Device.write(item, raw)
            return driverQuality(result.quality)
        }
        val logixDevice = logix?.device(server)
        if (logixDevice != null) {
            val result = logixDevice.write(item, raw)
            return driverQuality(result.quality)
        }
        val listener = udpTcp?.device(server)
        if (listener != null) {
            val result = listener.write(item, raw)
            return driverQuality(result.quality)
        }
        val bacnetDevice = bacnet?.device(server)
        if (bacnetDevice != null) {
            val result = bacnetDevice.write(item, raw)
            return driverQuality(result.quality)
        }
        val outstation = dnp3?.device(server)
        if (outstation != null) {
            val result = outstation.write(item, raw)
            return driverQuality(result.quality)
        }
        val connection = opc?.connection(server)
        if (connection != null) {
            val result = connection.write(item, raw)
            return driverQuality(result.quality)
        }
        val mqttConnection = mqtt?.connection(server)
        if (mqttConnection != null) {
            val result = mqttConnection.write(item, raw)
            return driverQuality(result.quality)
        }
        val simulated = simulator?.device(server)
        if (simulated != null) {
            val result = simulated.write(item, raw)
            return driverQuality(result.quality)
        }
        return Quality(QualityCode.BAD_NOT_CONNECTED, "source '$server' is not configured")
    }

    /**
     * [group]: the tag group the tag names (`tagGroup` set in its config). [key]: its [TagEngine.subscriptionKey] (the
     * schedule's item key). [engineRate]: the rate last given to [TagEngine.setDeviceScanRate].
     */
    private class Grouped(val resourceId: String, val group: String, val key: String, val path: String) {
        @Volatile var engineRate: Long? = null
    }

    private class Bind(
        val sample: TagSample,
        val server: String,
        val item: String,
        /** The rate the device item is subscribed at now; [TagGroupSchedule.OFF_RATE] when it is not read. */
        periodMs: Long,
        var handle: AutoCloseable? = null,
    ) : RawSample {
        /** Milliseconds, as an Int so a bind stays at 32 bytes (TagScaleTest footprint). */
        @Volatile private var period: Int = periodMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        var periodMs: Long
            get() = period.toLong()
            set(value) { period = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
        override fun onSample(value: Any?, quality: org.oip.modbus.Quality, epochMillis: Long) {
            val mapped = if (quality.statusCode == 0L && quality.diagnostic.isNullOrEmpty()) Quality.GOOD else driverQuality(quality)
            sample.update(value, mapped, epochMillis)
        }
    }

    companion object {
        /**
         * Upper bound on how long the comms watch sleeps before it recomputes the next deadline.
         * The last sleep before a deadline is only the remaining time, so a fixed tick is not added.
         */
        const val COMMS_WATCH_MS = 40L

        /** How often tag group rates are recomputed (TAG-006). */
        const val RETUNE_MS = 100L

        fun attach(engine: TagEngine, store: FileConfigStore, host: ModuleHost): TagDriverBridge {
            val modbus = host.instance(ModbusDriverModule.MODULE_ID) as? ModbusDriverModule
            val opc = host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule
            val mqtt = host.instance(MqttDriverModule.MODULE_ID) as? MqttDriverModule
            val s7 = host.instance(S7DriverModule.MODULE_ID) as? S7DriverModule
            val simulator = host.instance(SimulatorDriverModule.MODULE_ID) as? SimulatorDriverModule
            val logix = host.instance(LogixDriverModule.MODULE_ID) as? LogixDriverModule
            val udpTcp = host.instance(UdpTcpDriverModule.MODULE_ID) as? UdpTcpDriverModule
            val bacnet = host.instance(BacnetDriverModule.MODULE_ID) as? BacnetDriverModule
            val dnp3 = host.instance(Dnp3DriverModule.MODULE_ID) as? Dnp3DriverModule
            return TagDriverBridge(engine, store, modbus, opc, mqtt, s7, simulator, logix, udpTcp, bacnet, dnp3)
        }
    }
}

private fun userPath(config: TagConfig): String {
    val path = config.path
    if (path.startsWith("[")) return path
    return if (config.provider.equals("default", true)) path else "[${config.provider}]$path"
}

private fun driverQuality(quality: org.oip.modbus.Quality): Quality {
    if (quality.statusCode == 0L && quality.diagnostic.isNullOrEmpty()) return Quality.GOOD
    val mapped = Quality.fromOpcUa(quality.statusCode.toInt())
    return if (quality.diagnostic.isNullOrEmpty()) mapped else mapped.withDiagnostic(quality.diagnostic)
}

private fun driverQuality(quality: org.oip.opcua.Quality): Quality {
    val mapped = Quality.fromOpcUa(quality.statusCode.toInt())
    return if (quality.diagnostic.isNullOrEmpty()) mapped else mapped.withDiagnostic(quality.diagnostic)
}

private fun driverQuality(quality: org.oip.logix.Quality): Quality {
    if (quality.statusCode == 0L && quality.diagnostic.isNullOrEmpty()) return Quality.GOOD
    val mapped = Quality.fromOpcUa(quality.statusCode.toInt())
    return if (quality.diagnostic.isNullOrEmpty()) mapped else mapped.withDiagnostic(quality.diagnostic)
}

private fun driverQuality(quality: org.oip.udptcp.Quality): Quality {
    if (quality.statusCode == 0L && quality.diagnostic.isNullOrEmpty()) return Quality.GOOD
    val mapped = Quality.fromOpcUa(quality.statusCode.toInt())
    return if (quality.diagnostic.isNullOrEmpty()) mapped else mapped.withDiagnostic(quality.diagnostic)
}

private fun driverQuality(quality: org.oip.bacnet.Quality): Quality {
    if (quality.statusCode == 0L && quality.diagnostic.isNullOrEmpty()) return Quality.GOOD
    val mapped = Quality.fromOpcUa(quality.statusCode.toInt())
    return if (quality.diagnostic.isNullOrEmpty()) mapped else mapped.withDiagnostic(quality.diagnostic)
}

private fun driverQuality(quality: org.oip.dnp3.Quality): Quality {
    if (quality.statusCode == 0L && quality.diagnostic.isNullOrEmpty()) return Quality.GOOD
    val mapped = Quality.fromOpcUa(quality.statusCode.toInt())
    return if (quality.diagnostic.isNullOrEmpty()) mapped else mapped.withDiagnostic(quality.diagnostic)
}

private fun driverQuality(quality: org.oip.s7.Quality): Quality {
    if (quality.statusCode == 0L && quality.diagnostic.isNullOrEmpty()) return Quality.GOOD
    val mapped = Quality.fromOpcUa(quality.statusCode.toInt())
    return if (quality.diagnostic.isNullOrEmpty()) mapped else mapped.withDiagnostic(quality.diagnostic)
}

private fun driverQuality(quality: org.oip.mqtt.Quality): Quality {
    if (quality.statusCode == 0L && quality.diagnostic.isNullOrEmpty()) return Quality.GOOD
    val mapped = Quality.fromOpcUa(quality.statusCode.toInt())
    return if (quality.diagnostic.isNullOrEmpty()) mapped else mapped.withDiagnostic(quality.diagnostic)
}

private fun driverQuality(quality: org.oip.simulator.Quality): Quality {
    if (quality.statusCode == 0L && quality.diagnostic.isNullOrEmpty()) return Quality.GOOD
    val mapped = Quality.fromOpcUa(quality.statusCode.toInt())
    return if (quality.diagnostic.isNullOrEmpty()) mapped else mapped.withDiagnostic(quality.diagnostic)
}
