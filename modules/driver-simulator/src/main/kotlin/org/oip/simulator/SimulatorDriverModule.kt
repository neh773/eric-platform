package org.oip.simulator

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.SchemaRegistry.Companion.MODULE_CONFIG_KIND
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Programmable device simulator. A `Device` resource opens a [SimulatorDevice]; deleting it
 * closes the device. Creating, updating, or deleting the resource takes effect without a gateway restart.
 *
 */
class SimulatorDriverModule : Module {
    private val log = Logger.getLogger("org.oip.simulator")
    private val devices = ConcurrentHashMap<String, SimulatorDevice>()
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

    fun device(name: String): SimulatorDevice? = devices[name]

    fun removeDevice(name: String) {
        devices.remove(name)?.close()
    }

    private fun prepare(context: ModuleContext) {
        this.context = context
        context.config.registerKind(DEVICE_KIND, simulatorDeviceSchema())
        context.config.revalidate()
        configSub?.close()
        reload(context)
        configSub = context.config.subscribe { event -> onConfig(context, event) }
    }

    private fun reload(context: ModuleContext) {
        context.config.list(DEVICE_KIND).forEach { stored ->
            apply(context, stored.resource.metadata.name, stored.resource.spec)
        }
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
            ChangeType.INVALID -> log.warning("simulator device ${id.name} rejected: ${event.error}")
        }
    }

    private fun apply(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            val spec = context.config.withDefaults(raw, DEVICE_KIND)
            if (spec["enabled"] == false) {
                removeDevice(name)
                return
            }
            // Pass the caller's keys so a preloaded program can supply legacy item paths and is not
            // blocked by schema defaults the user did not set.
            val config = simulatorDeviceConfig(name, spec, raw.keys)
            val device = SimulatorDevice(config)
            devices.remove(name)?.close()
            devices[name] = device
            if (running) device.start()
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "simulator device $name was not started", e)
            removeDevice(name)
        }
    }

    companion object {
        const val CONFIG_NAME = "default"
        const val MODULE_ID = "org.oip.driver-simulator"
        val MANIFEST_YAML = """
            id: org.oip.driver-simulator
            version: 0.1.0
            name: Programmable Device Simulator
            entrypoint: org.oip.simulator.SimulatorDriverModule
            dependencies: []
            permissions:
              - device.connect
            configSchema:
              type: object
              properties:
                description:
                  type: string
                  default: ""
        """.trimIndent() + "\n"
    }
}
