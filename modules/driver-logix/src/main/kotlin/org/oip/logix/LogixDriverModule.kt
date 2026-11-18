package org.oip.logix

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.SchemaRegistry.Companion.MODULE_CONFIG_KIND
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Logix driver module. A `Device` config resource opens a [LogixDevice]; deleting it closes the device.
 * Creating, updating, or deleting the resource takes effect without a gateway restart.
 * Doc source: FEATURES DEV-008, DEV-010.
 */
class LogixDriverModule : Module {
    private val log = Logger.getLogger("org.oip.logix")
    private val devices = ConcurrentHashMap<String, LogixDevice>()
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

    fun addDevice(config: LogixDeviceConfig): LogixDevice {
        devices.remove(config.name)?.close()
        val device = LogixDevice(config)
        devices[config.name] = device
        if (running) device.start()
        return device
    }

    fun device(name: String): LogixDevice? = devices[name]

    fun removeDevice(name: String) {
        devices.remove(name)?.close()
    }

    private fun prepare(context: ModuleContext) {
        this.context = context
        context.config.registerKind(DEVICE_KIND, logixDeviceSchema())
        context.config.registerSemantic(DEVICE_KIND, ::logixDeviceErrors)
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
            ChangeType.INVALID -> log.warning("logix device ${id.name} rejected: ${event.error}")
        }
    }

    private fun apply(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            val spec = context.config.withDefaults(raw, DEVICE_KIND)
            if (!spec.enabled()) {
                removeDevice(name)
                return
            }
            addDevice(logixDeviceConfig(name, spec))
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "logix device $name was not started", e)
            removeDevice(name)
        }
    }

    companion object {
        const val CONFIG_NAME = "default"
        const val MODULE_ID = "org.oip.driver-logix"
        val MANIFEST_YAML = """
            id: org.oip.driver-logix
            version: 0.1.0
            name: Logix Driver
            entrypoint: org.oip.logix.LogixDriverModule
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
                defaultSlot:
                  type: integer
                  minimum: 0
                  maximum: 31
                  default: 0
                defaultCipConnectionSize:
                  type: integer
                  minimum: 64
                  maximum: 4000
                  default: 500
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
