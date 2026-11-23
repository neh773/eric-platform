package org.oip.opcua

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.SchemaRegistry.Companion.MODULE_CONFIG_KIND
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * OPC UA client module. A `Connection` config resource opens an [OpcUaConnection].
 * Creating, updating, or deleting the resource connects or drops the session without a gateway restart.
 * A server that is down does not fail the config write: the session retries in the background.
 */
class OpcUaClientModule : Module {
    private val log = Logger.getLogger("org.oip.opcua")
    private val connections = ConcurrentHashMap<String, OpcUaConnection>()
    private val openers = ConcurrentHashMap<String, Thread>()
    private var configSub: AutoCloseable? = null
    @Volatile private var running = false

    /**
     * Set by the gateway before start. Resolves a `secret://` password reference when a session opens,
     * so the plaintext is never in the config resource.
     */
    @Volatile var secretSource: ((String) -> String)? = null

    override fun install(context: ModuleContext) {
        prepare(context)
        context.config.put(MODULE_CONFIG_KIND, CONFIG_NAME, context.config.defaults())
    }

    override fun restore(context: ModuleContext) {
        prepare(context)
    }

    override fun start() {
        running = true
        connections.values.forEach { launch(it) }
    }

    override fun stop() {
        running = false
        openers.values.forEach { it.interrupt() }
        openers.clear()
        connections.values.forEach { it.disconnect() }
    }

    override fun uninstall() {
        configSub?.close()
        configSub = null
        stop()
        connections.values.forEach { it.close() }
        connections.clear()
    }

    /** Open a connection. If the module is already started, it connects in the background. */
    fun connect(config: OpcUaConnectionConfig): OpcUaConnection {
        openers.remove(config.name)?.interrupt()
        connections.remove(config.name)?.close()
        val connection = OpcUaConnection(config) { value ->
            if (!value.startsWith("secret://")) value
            else secretSource?.invoke(value) ?: throw IllegalStateException("the secret store is not ready")
        }
        connections[config.name] = connection
        if (running) launch(connection)
        return connection
    }

    fun connection(name: String): OpcUaConnection? = connections[name]

    fun remove(name: String) {
        openers.remove(name)?.interrupt()
        connections.remove(name)?.close()
    }

    private fun launch(connection: OpcUaConnection) {
        val thread = Thread({
            var attempt = 0
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    if (!connection.connectionStatus().connected) connection.connect()
                    if (!running) {
                        connection.disconnect()
                        return@Thread
                    }
                    connection.flushWatches()
                    return@Thread
                } catch (e: Exception) {
                    if (!running || Thread.currentThread().isInterrupted) return@Thread
                    val delay = (500L * (1L shl attempt.coerceAtMost(6))).coerceAtMost(30_000L)
                    attempt++
                    log.fine("opc connect ${connection.sourceId} failed (${e.message}); retry in ${delay}ms")
                    try {
                        Thread.sleep(delay)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }
        }, "opcua-open-${connection.sourceId}")
        thread.isDaemon = true
        openers[connection.sourceId] = thread
        thread.start()
    }

    private fun prepare(context: ModuleContext) {
        context.config.registerKind(CONNECTION_KIND, opcUaConnectionSchema())
        context.config.revalidate()
        configSub?.close()
        context.config.list(CONNECTION_KIND).forEach { stored ->
            apply(context, stored.resource.metadata.name, stored.resource.spec)
        }
        configSub = context.config.subscribe { event -> onConfig(context, event) }
    }

    private fun onConfig(context: ModuleContext, event: ConfigEvent) {
        val id = event.id ?: return
        if (id.kind != CONNECTION_KIND) return
        when (event.type) {
            ChangeType.DELETED -> remove(id.name)
            ChangeType.CREATED, ChangeType.UPDATED -> apply(context, id.name, event.resource?.spec ?: return)
            ChangeType.INVALID -> log.warning("opc connection ${id.name} rejected: ${event.error}")
        }
    }

    private fun apply(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            val spec = context.config.withDefaults(raw, CONNECTION_KIND)
            if (!spec.enabled()) {
                remove(name)
                return
            }
            connect(opcUaConnectionConfig(name, spec))
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "opc connection $name was not started", e)
            remove(name)
        }
    }

    companion object {
        const val CONFIG_NAME = "default"
        const val MODULE_ID = "org.oip.driver-opcua-client"
        val MANIFEST_YAML = """
            id: org.oip.driver-opcua-client
            version: 0.1.0
            name: OPC UA Client
            entrypoint: org.oip.opcua.OpcUaClientModule
            dependencies: []
            permissions:
              - device.connect
            configSchema:
              type: object
              properties:
                requestTimeoutMs:
                  type: integer
                  minimum: 1
                  maximum: 120000
                  default: 5000
                keepAliveIntervalMs:
                  type: integer
                  minimum: 50
                  default: 5000
                sessionTimeoutMs:
                  type: integer
                  minimum: 1000
                  default: 60000
                reconnectInitialMs:
                  type: integer
                  minimum: 1
                  default: 500
        """.trimIndent() + "\n"
    }
}
