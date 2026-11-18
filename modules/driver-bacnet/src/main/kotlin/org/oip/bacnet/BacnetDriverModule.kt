package org.oip.bacnet

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.SchemaRegistry.Companion.MODULE_CONFIG_KIND
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * BACnet/IP driver module. A `LocalDevice` resource opens the gateway's own BACnet device and its UDP
 * socket (DEV-012); a `RemoteDevice` resource names the local device it talks through and the remote
 * device instance to find, browse and poll (DEV-011, DEV-038). A remote device whose local device is
 * missing or not running reports that and does not communicate. Changes apply without a restart.
 * doc source: FEATURES DEV-011, DEV-012, DEV-038
 */
class BacnetDriverModule : Module {
    private val log = Logger.getLogger("org.oip.bacnet")
    private val locals = ConcurrentHashMap<String, BacnetLocalDevice>()
    private val remotes = ConcurrentHashMap<String, BacnetRemoteDevice>()
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
        locals.values.forEach { it.start() }
        remotes.values.forEach { it.start() }
    }

    override fun stop() {
        remotes.values.forEach { it.stop() }
        locals.values.forEach { it.stop() }
        running = false
    }

    override fun uninstall() {
        configSub?.close()
        configSub = null
        stop()
        remotes.values.forEach { it.close() }
        remotes.clear()
        locals.values.forEach { it.close() }
        locals.clear()
    }

    fun addLocalDevice(config: LocalDeviceConfig): BacnetLocalDevice {
        locals.remove(config.name)?.close()
        val device = BacnetLocalDevice(config)
        locals[config.name] = device
        if (running) device.start()
        return device
    }

    fun addRemoteDevice(config: RemoteDeviceConfig): BacnetRemoteDevice {
        remotes.remove(config.name)?.close()
        val device = BacnetRemoteDevice(config) { name -> locals[name] }
        remotes[config.name] = device
        if (running) device.start()
        return device
    }

    fun localDevice(name: String): BacnetLocalDevice? = locals[name]

    /** The remote device [name]; the tag bridge and device catalog look devices up here. */
    fun device(name: String): BacnetRemoteDevice? = remotes[name]

    fun deviceNames(): Set<String> = remotes.keys.toSet()

    fun removeLocalDevice(name: String) {
        locals.remove(name)?.close()
    }

    fun removeRemoteDevice(name: String) {
        remotes.remove(name)?.close()
    }

    private fun prepare(context: ModuleContext) {
        context.config.registerKind(LOCAL_DEVICE_KIND, localDeviceSchema())
        context.config.registerKind(REMOTE_DEVICE_KIND, remoteDeviceSchema())
        context.config.revalidate()
        configSub?.close()
        context.config.list(LOCAL_DEVICE_KIND).forEach { applyLocal(context, it.resource.metadata.name, it.resource.spec) }
        context.config.list(REMOTE_DEVICE_KIND).forEach { applyRemote(context, it.resource.metadata.name, it.resource.spec) }
        configSub = context.config.subscribe { event -> onConfig(context, event) }
    }

    private fun onConfig(context: ModuleContext, event: ConfigEvent) {
        val id = event.id ?: return
        when (id.kind) {
            LOCAL_DEVICE_KIND -> when (event.type) {
                ChangeType.DELETED -> removeLocalDevice(id.name)
                ChangeType.CREATED, ChangeType.UPDATED -> applyLocal(context, id.name, event.resource?.spec ?: return)
                ChangeType.INVALID -> log.warning("bacnet local device ${id.name} rejected: ${event.error}")
            }
            REMOTE_DEVICE_KIND -> when (event.type) {
                ChangeType.DELETED -> removeRemoteDevice(id.name)
                ChangeType.CREATED, ChangeType.UPDATED -> applyRemote(context, id.name, event.resource?.spec ?: return)
                ChangeType.INVALID -> log.warning("bacnet remote device ${id.name} rejected: ${event.error}")
            }
        }
    }

    private fun applyLocal(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            val spec = context.config.withDefaults(raw, LOCAL_DEVICE_KIND)
            if (!spec.enabled()) { removeLocalDevice(name); return }
            addLocalDevice(localDeviceConfig(name, spec))
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "bacnet local device $name was not started", e)
            removeLocalDevice(name)
        }
    }

    private fun applyRemote(context: ModuleContext, name: String, raw: Map<String, Any?>) {
        try {
            val spec = context.config.withDefaults(raw, REMOTE_DEVICE_KIND)
            if (!spec.enabled()) { removeRemoteDevice(name); return }
            addRemoteDevice(remoteDeviceConfig(name, spec))
        } catch (e: RuntimeException) {
            log.log(Level.WARNING, "bacnet remote device $name was not started", e)
            removeRemoteDevice(name)
        }
    }

    companion object {
        const val CONFIG_NAME = "default"
        const val MODULE_ID = "org.oip.driver-bacnet"
        val MANIFEST_YAML = """
            id: org.oip.driver-bacnet
            version: 0.1.0
            name: BACnet/IP Driver
            entrypoint: org.oip.bacnet.BacnetDriverModule
            dependencies: []
            permissions:
              - device.connect
            configSchema:
              type: object
              properties: {}
        """.trimIndent() + "\n"
    }
}
