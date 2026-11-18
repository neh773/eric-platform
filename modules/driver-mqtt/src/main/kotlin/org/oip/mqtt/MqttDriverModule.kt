package org.oip.mqtt

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.SchemaRegistry.Companion.MODULE_CONFIG_KIND
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * MQTT driver. A `Connection` config resource opens an [MqttConnection].
 * Creating, updating, or deleting the resource connects or drops the session without a gateway restart.
 * A broker that is down does not fail the config write: the session retries in the background.
 *
 * [secretSource] is set by the gateway after install and before start. It resolves `secret://`
 * references. The module does not log the resolved text.
 */
class MqttDriverModule : Module {
    private val log = Logger.getLogger("org.oip.mqtt")
    private val connections = ConcurrentHashMap<String, MqttConnection>()
    private val edges = ConcurrentHashMap<String, SparkplugEdgeNode>()
    /** bdSeq survives an `EdgeNode` update in this process, so each new session still increments it. */
    private val bdSeqs = ConcurrentHashMap<String, AtomicLong>()
    private val discovered = ConcurrentHashMap<String, DiscoveredMetric>()
    private val discoveryListeners = CopyOnWriteArrayList<(DiscoveredMetric) -> Unit>()
    private val discoveryLock = Any()
    private var configSub: AutoCloseable? = null
    @Volatile private var running = false
    @Volatile private var publishing = true
    @Volatile private var edgeActive = true
    @Volatile var secretSource: SecretSource? = null
    /** Set by the gateway before start. Edge nodes wait for it before connecting. */
    @Volatile var tagFeed: EdgeTagFeed? = null

    override fun install(context: ModuleContext) {
        prepare(context)
        context.config.put(MODULE_CONFIG_KIND, CONFIG_NAME, context.config.defaults())
    }

    override fun restore(context: ModuleContext) {
        prepare(context)
    }

    override fun start() {
        running = true
        connections.values.forEach { it.start() }
        edges.values.forEach { it.start() }
    }

    override fun stop() {
        running = false
        connections.values.forEach { it.stop() }
        // Signal every edge node first so their NDEATH and disconnect run in parallel.
        edges.values.forEach { it.requestStop() }
        edges.values.forEach { it.awaitStop() }
    }

    override fun uninstall() {
        configSub?.close()
        configSub = null
        stop()
        connections.values.forEach { it.close() }
        connections.clear()
        edges.values.forEach { it.close() }
        edges.clear()
        synchronized(discoveryLock) {
            discovered.clear()
            discoveryListeners.clear()
        }
    }

    fun connect(config: MqttConnectionConfig): MqttConnection {
        connections.remove(config.name)?.close()
        val connection = MqttConnection(config, { secretSource }, ::announce)
        connection.setPublishing(publishing)
        connections[config.name] = connection
        if (running) connection.start()
        return connection
    }

    /**
     * Host state and command publishes. False on a standby node: subscriptions stay up,
     * and this session does not claim the primary host or send commands.
     */
    fun setPublishing(enabled: Boolean) {
        publishing = enabled
        connections.values.forEach { it.setPublishing(enabled) }
    }

    /**
     * Redundancy state for edge nodes. False on a backup (inactive) node: edge nodes publish NDEATH,
     * disconnect, and stay disconnected until this is true again. Only stores a flag (P2-14).
     */
    fun setEdgeActive(active: Boolean) {
        edgeActive = active
        edges.values.forEach { it.setActive(active) }
    }

    fun connection(name: String): MqttConnection? = connections[name]

    fun connectionNames(): List<String> = connections.keys.sorted()

    fun edgeNodeNames(): List<String> = edges.keys.sorted()

    fun edgeNode(config: SparkplugEdgeConfig): SparkplugEdgeNode {
        edges.remove(config.name)?.close()
        val counter = bdSeqs.computeIfAbsent(config.name) { AtomicLong(0) }
        val node = SparkplugEdgeNode(config, { tagFeed }, { secretSource }, counter)
        node.setActive(edgeActive)
        edges[config.name] = node
        if (running) node.start()
        return node
    }

    fun edgeNode(name: String): SparkplugEdgeNode? = edges[name]

    fun removeEdgeNode(name: String) {
        edges.remove(name)?.close()
    }

    fun remove(name: String) {
        connections.remove(name)?.close()
    }

    fun listen(listener: (DiscoveredMetric) -> Unit): AutoCloseable {
        val snapshot = synchronized(discoveryLock) {
            discoveryListeners.add(listener)
            discovered.values.toList()
        }
        snapshot.forEach { deliver(listener, it) }
        return AutoCloseable {
            synchronized(discoveryLock) { discoveryListeners.remove(listener) }
        }
    }

    private fun announce(metric: DiscoveredMetric) {
        val key = metric.connection + "\u0000" + metric.address
        val listeners = synchronized(discoveryLock) {
            if (discovered.putIfAbsent(key, metric) != null) return
            discoveryListeners.toList()
        }
        listeners.forEach { deliver(it, metric) }
    }

    private fun deliver(listener: (DiscoveredMetric) -> Unit, metric: DiscoveredMetric) {
        try {
            listener(metric)
        } catch (e: RuntimeException) {
            log.log(Level.FINE, "mqtt discovery ${metric.address}", e)
        }
    }

    private fun prepare(context: ModuleContext) {
        context.config.registerKind(CONNECTION_KIND, mqttConnectionSchema())
        context.config.registerKind(EDGE_NODE_KIND, sparkplugEdgeSchema())
        context.config.revalidate()
        configSub?.close()
        context.config.list(CONNECTION_KIND).forEach { stored ->
            apply(context, stored.resource.metadata.name, stored.resource.spec)
        }
        context.config.list(EDGE_NODE_KIND).forEach { stored ->
            applyEdge(context, stored.resource.metadata.name, stored.resource.spec)
        }
        configSub = context.config.subscribe { event -> onConfig(context, event) }
    }

    private fun onConfig(context: ModuleContext, event: ConfigEvent) {
        val id = event.id ?: return
        if (id.kind == EDGE_NODE_KIND) {
            when (event.type) {
                ChangeType.DELETED -> removeEdgeNode(id.name)
                ChangeType.CREATED, ChangeType.UPDATED -> applyEdge(context, id.name, event.resource?.spec ?: return)
                ChangeType.INVALID -> log.warning("sparkplug edge node ${id.name} rejected")
            }
            return
        }
        if (id.kind != CONNECTION_KIND) return
        when (event.type) {
            ChangeType.DELETED -> remove(id.name)
            ChangeType.CREATED, ChangeType.UPDATED -> apply(context, id.name, event.resource?.spec ?: return)
            ChangeType.INVALID -> log.warning("mqtt connection ${id.name} rejected")
        }
    }

    private fun apply(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            val spec = context.config.withDefaults(raw, CONNECTION_KIND)
            if (!spec.enabled()) {
                remove(name)
                return
            }
            connect(mqttConnectionConfig(name, spec))
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "mqtt connection $name was not started", e)
            remove(name)
        }
    }

    private fun applyEdge(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            val spec = context.config.withDefaults(raw, EDGE_NODE_KIND)
            if (!spec.enabled()) {
                removeEdgeNode(name)
                return
            }
            edgeNode(sparkplugEdgeConfig(name, spec))
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "sparkplug edge node $name was not started", e)
            removeEdgeNode(name)
        }
    }

    companion object {
        const val CONFIG_NAME = "default"
        const val MODULE_ID = "org.oip.driver-mqtt"
        val MANIFEST_YAML = """
            id: org.oip.driver-mqtt
            version: 0.1.0
            name: MQTT Driver
            entrypoint: org.oip.mqtt.MqttDriverModule
            dependencies: []
            permissions:
              - device.connect
            configSchema:
              type: object
              properties:
                reconnectInitialMs:
                  type: integer
                  minimum: 1
                  default: 500
                reconnectMaxMs:
                  type: integer
                  minimum: 1
                  default: 30000
        """.trimIndent() + "\n"
    }
}
