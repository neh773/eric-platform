package org.oip.udptcp

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.SchemaRegistry.Companion.MODULE_CONFIG_KIND
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * UDP and TCP driver module. A `Device` config resource opens a [ListenerDevice]; deleting it
 * closes the device and its sockets. Changes take effect without a gateway restart, and a bad
 * device is refused when it is saved. doc source: FEATURES DEV-037
 */
class UdpTcpDriverModule : Module {
    private val log = Logger.getLogger("org.oip.udptcp")
    private val devices = ConcurrentHashMap<String, ListenerDevice>()
    private var configSub: AutoCloseable? = null
    @Volatile private var running = false

    override fun install(context: ModuleContext) {
        prepare(context)
        context.config.put(MODULE_CONFIG_KIND, CONFIG_NAME, context.config.defaults())
    }

    override fun restore(context: ModuleContext) {
        prepare(context)
    }

    override fun start() {
        running = true
        devices.values.forEach { it.start() }
    }

    override fun stop() {
        devices.values.forEach { it.stop() }
        running = false
    }

    override fun uninstall() {
        configSub?.close()
        configSub = null
        stop()
        devices.values.forEach { it.close() }
        devices.clear()
    }

    fun addDevice(config: ListenerDeviceConfig): ListenerDevice {
        devices.remove(config.name)?.close()
        val device = ListenerDevice(config)
        devices[config.name] = device
        if (running) device.start()
        return device
    }

    fun device(name: String): ListenerDevice? = devices[name]

    fun removeDevice(name: String) {
        devices.remove(name)?.close()
    }

    private fun prepare(context: ModuleContext) {
        context.config.registerKind(DEVICE_KIND, listenerDeviceSchema())
        context.config.registerSemantic(DEVICE_KIND, ::listenerDeviceErrors)
        context.config.revalidate()
        configSub?.close()
        context.config.list(DEVICE_KIND).forEach { apply(context, it.resource.metadata.name, it.resource.spec) }
        configSub = context.config.subscribe { event -> onConfig(context, event) }
    }

    private fun onConfig(context: ModuleContext, event: ConfigEvent) {
        val id = event.id ?: return
        if (id.kind != DEVICE_KIND) return
        when (event.type) {
            ChangeType.DELETED -> removeDevice(id.name)
            ChangeType.CREATED, ChangeType.UPDATED -> apply(context, id.name, event.resource?.spec ?: return)
            ChangeType.INVALID -> log.warning("udp/tcp device ${id.name} rejected: ${event.error}")
        }
    }

    private fun apply(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            // A device that does not set maxMessageBytes takes the module's defaultMaxMessageBytes.
            val moduleDefault = context.config.get(MODULE_CONFIG_KIND, CONFIG_NAME)?.spec?.get("defaultMaxMessageBytes")
            val withModule = if (raw.containsKey("maxMessageBytes") || moduleDefault == null) raw else raw + ("maxMessageBytes" to moduleDefault)
            val spec = context.config.withDefaults(withModule, DEVICE_KIND)
            if (!spec.enabled()) {
                removeDevice(name)
                return
            }
            addDevice(listenerDeviceConfig(name, spec))
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "udp/tcp device $name was not started", e)
            removeDevice(name)
        }
    }

    companion object {
        const val CONFIG_NAME = "default"
        const val MODULE_ID = "org.oip.driver-udp-tcp"
        val MANIFEST_YAML = """
            id: org.oip.driver-udp-tcp
            version: 0.1.0
            name: UDP and TCP Driver
            entrypoint: org.oip.udptcp.UdpTcpDriverModule
            dependencies: []
            permissions:
              - device.connect
            configSchema:
              type: object
              properties:
                defaultMaxMessageBytes:
                  type: integer
                  minimum: 1
                  maximum: 65536
                  default: 4096
        """.trimIndent() + "\n"
    }
}
