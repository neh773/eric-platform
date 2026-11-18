package org.oip.s7

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.SchemaRegistry.Companion.MODULE_CONFIG_KIND
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * S7 driver module. A `Device` config resource opens an [S7Device]; deleting it closes the device.
 * Creating, updating, or deleting the resource takes effect without a gateway restart.
 * Doc source: FEATURES DEV-025, DEV-026, DEV-036.
 */
class S7DriverModule : Module {
    private val log = Logger.getLogger("org.oip.s7")
    private val devices = ConcurrentHashMap<String, S7Device>()
    private var configSub: AutoCloseable? = null
    @Volatile private var running = false
    @Volatile private var context: ModuleContext? = null

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
        context = null
    }

    fun addDevice(config: S7DeviceConfig): S7Device {
        devices.remove(config.name)?.close()
        val device = S7Device(config)
        devices[config.name] = device
        if (running) device.start()
        return device
    }

    fun device(name: String): S7Device? = devices[name]

    fun removeDevice(name: String) {
        devices.remove(name)?.close()
    }

    private fun prepare(context: ModuleContext) {
        this.context = context
        context.config.registerKind(DEVICE_KIND, s7DeviceSchema())
        context.config.revalidate()
        configSub?.close()
        reload(context)
        configSub = context.config.subscribe { event -> onConfig(context, event) }
    }

    private fun reload(context: ModuleContext) {
        context.config.list(DEVICE_KIND).forEach { stored -> apply(context, stored.resource.metadata.name, stored.resource.spec) }
    }

    private fun onConfig(context: ModuleContext, event: ConfigEvent) {
        val id = event.id ?: return
        if (id.kind != DEVICE_KIND) return
        when (event.type) {
            ChangeType.DELETED -> removeDevice(id.name)
            ChangeType.CREATED, ChangeType.UPDATED -> {
                val spec = event.resource?.spec ?: return
                apply(context, id.name, spec)
            }
            ChangeType.INVALID -> log.warning("s7 device ${id.name} rejected: ${event.error}")
        }
    }

    private fun apply(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            val spec = context.config.withDefaults(raw, DEVICE_KIND)
            if (!spec.enabled()) {
                removeDevice(name)
                return
            }
            addDevice(s7DeviceConfig(name, spec))
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "s7 device $name was not started", e)
            removeDevice(name)
        }
    }

    companion object {
        const val CONFIG_NAME = "default"
        const val MODULE_ID = "org.oip.driver-s7"
        val MANIFEST_YAML = """
            id: org.oip.driver-s7
            version: 0.1.0
            name: S7 Driver
            entrypoint: org.oip.s7.S7DriverModule
            dependencies: []
            permissions:
              - device.connect
            configSchema:
              type: object
              properties:
                defaultTimeoutMs:
                  type: integer
                  minimum: 1
                  maximum: 120000
                  default: 2000
                defaultPduSize:
                  type: integer
                  minimum: 240
                  maximum: 960
                  default: 240
                defaultRack:
                  type: integer
                  minimum: 0
                  maximum: 7
                  default: 0
                defaultSlot:
                  type: integer
                  minimum: 0
                  maximum: 31
                  default: 2
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
