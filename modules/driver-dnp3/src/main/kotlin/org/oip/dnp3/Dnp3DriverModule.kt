package org.oip.dnp3

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.SchemaRegistry.Companion.MODULE_CONFIG_KIND
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * DNP3 driver module: the gateway is a DNP3 master over TCP. Each `Outstation` resource is one outstation
 * the master polls (DEV-014) and commands (DEV-013). Changes apply without a restart.
 * doc source: FEATURES DEV-013, DEV-014
 */
class Dnp3DriverModule : Module {
    private val log = Logger.getLogger("org.oip.dnp3")
    private val outstations = ConcurrentHashMap<String, Dnp3Outstation>()
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
        outstations.values.forEach { it.start() }
    }

    override fun stop() {
        outstations.values.forEach { it.stop() }
        running = false
    }

    override fun uninstall() {
        configSub?.close()
        configSub = null
        stop()
        outstations.values.forEach { it.close() }
        outstations.clear()
    }

    fun addOutstation(config: OutstationConfig): Dnp3Outstation {
        outstations.remove(config.name)?.close()
        val device = Dnp3Outstation(config)
        outstations[config.name] = device
        if (running) device.start()
        return device
    }

    /** The outstation [name]; the tag bridge, device catalog and scripts look outstations up here. */
    fun device(name: String): Dnp3Outstation? = outstations[name]

    fun deviceNames(): Set<String> = outstations.keys.toSet()

    fun removeOutstation(name: String) {
        outstations.remove(name)?.close()
    }

    private fun prepare(context: ModuleContext) {
        context.config.registerKind(OUTSTATION_KIND, outstationSchema())
        context.config.revalidate()
        configSub?.close()
        context.config.list(OUTSTATION_KIND).forEach { apply(context, it.resource.metadata.name, it.resource.spec) }
        configSub = context.config.subscribe { event -> onConfig(context, event) }
    }

    private fun onConfig(context: ModuleContext, event: ConfigEvent) {
        val id = event.id ?: return
        if (id.kind != OUTSTATION_KIND) return
        when (event.type) {
            ChangeType.DELETED -> removeOutstation(id.name)
            ChangeType.CREATED, ChangeType.UPDATED -> apply(context, id.name, event.resource?.spec ?: return)
            ChangeType.INVALID -> log.warning("dnp3 outstation ${id.name} rejected: ${event.error}")
        }
    }

    private fun apply(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            val spec = context.config.withDefaults(raw, OUTSTATION_KIND)
            if (!spec.enabled()) { removeOutstation(name); return }
            addOutstation(outstationConfig(name, spec))
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "dnp3 outstation $name was not started", e)
            removeOutstation(name)
        }
    }

    companion object {
        const val CONFIG_NAME = "default"
        const val MODULE_ID = "org.oip.driver-dnp3"
        val MANIFEST_YAML = """
            id: org.oip.driver-dnp3
            version: 0.1.0
            name: DNP3 Driver
            entrypoint: org.oip.dnp3.Dnp3DriverModule
            dependencies: []
            permissions:
              - device.connect
            configSchema:
              type: object
              properties: {}
        """.trimIndent() + "\n"
    }
}
