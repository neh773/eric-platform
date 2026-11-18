package org.oip.sdk

import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.StoredResource
import java.nio.file.Path

/** Lifecycle contract every module implements. Order: install -> start -> stop -> uninstall. */
interface Module {
    /** Called once; the module writes its initial config resources via [ModuleContext.config]. */
    fun install(context: ModuleContext)
    /** Called instead of [install] when the host restarts and the module is already installed; config must be kept. */
    fun restore(context: ModuleContext) {}
    /** Called on the new version's instance after a package upgrade from [fromVersion]; config is kept. Default: [restore]. */
    fun upgrade(context: ModuleContext, fromVersion: String) = restore(context)
    fun start()
    fun stop()
    /** Release external resources. The host deletes all of the module's resources and its config dir afterwards. */
    fun uninstall()
}

/**
 * Per-module context supplied by the host. [configDir] = `<configRoot>/<moduleId>/`; resources go through [config]
 * (always allowed, own module only); other host services only via [services], gated by manifest permissions.
 */
data class ModuleContext(val manifest: ModuleManifest, val configDir: Path, val config: ModuleConfig, val services: ModuleServices)

/**
 * A module's view of the ConfigStore, restricted to resources whose `metadata.module` is the module's own id
 * (stored under `<configRoot>/<moduleId>/`).
 */
class ModuleConfig(val moduleId: String, private val store: FileConfigStore) {
    private fun own(module: String?) { require(module == null || module == moduleId) { "module '$moduleId' may not access config of '$module'" } }
    fun id(kind: String, name: String, scope: Scope = Scope.GATEWAY) = ResourceId(moduleId, scope, kind, name)
    fun get(kind: String, name: String, scope: Scope = Scope.GATEWAY): Resource? = store.get(id(kind, name, scope))
    /** Effective resource with the active deployment-mode overlay applied. */
    fun resolve(kind: String, name: String): Resource? = store.resolve(id(kind, name))
    fun list(kind: String? = null, scope: Scope? = null): List<StoredResource> = store.list(moduleId, scope, kind)
    /** Create (version = 0) or update (version = currently stored version). */
    fun put(kind: String, name: String, spec: Map<String, Any?>, version: Long = 0, scope: Scope = Scope.GATEWAY,
            actor: String = moduleId, module: String? = null): Resource {
        own(module)
        return store.put(scope, Resource(kind, Metadata(name, moduleId, version = version), spec), actor)
    }
    fun delete(kind: String, name: String, scope: Scope = Scope.GATEWAY) = store.delete(id(kind, name, scope))
    /** Defaults from the schema of [kind] (default: the manifest `configSchema`). */
    fun defaults(kind: String = SchemaRegistry.MODULE_CONFIG_KIND): Map<String, Any?> = store.registry.defaults(moduleId, kind)
    fun withDefaults(spec: Map<String, Any?>, kind: String = SchemaRegistry.MODULE_CONFIG_KIND) = store.registry.applyDefaults(moduleId, kind, spec)
    /** Register an additional resource kind owned by this module (for example `Device` or `Connection`). */
    fun registerKind(kind: String, schema: Map<String, Any?>) = store.registry.register(moduleId, kind, schema)
    /**
     * Checks for [kind] that JSON Schema cannot express (a connection path that must parse, fields that depend on each
     * other). They run on every save after the schema passes, so a bad resource is refused at save time.
     */
    fun registerSemantic(kind: String, check: (Map<String, Any?>) -> List<String>) = store.registry.registerSemantic(moduleId, kind, check)
    /** Re-check this module's indexed resources after a kind was registered. */
    fun revalidate(): Map<ResourceId, String> = store.revalidate(moduleId)
    fun subscribe(listener: (ConfigEvent) -> Unit): AutoCloseable = store.subscribe { e -> if (e.id?.module == moduleId) listener(e) }
}

class PermissionDeniedException(message: String) : SecurityException(message)

/** Module-scoped logging; requires permission `log.write`. */
interface ModuleLog {
    fun info(message: String)
    fun warn(message: String)
    companion object { const val PERMISSION = "log.write" }
}

/**
 * Host-side registry of services offered to modules. Each service type is guarded by one permission string;
 * a factory creates the instance bound to the calling module's manifest.
 */
class ServiceRegistry {
    private class Reg(val permission: String, val factory: (ModuleManifest) -> Any)
    private val regs = java.util.concurrent.ConcurrentHashMap<Class<*>, Reg>()
    fun <T : Any> register(type: Class<T>, permission: String, factory: (ModuleManifest) -> T) { regs[type] = Reg(permission, factory) }
    fun permission(type: Class<*>): String? = regs[type]?.permission
    internal fun create(type: Class<*>, m: ModuleManifest): Any = regs[type]?.factory?.invoke(m)
        ?: throw NoSuchElementException("no service ${type.name} registered")

    companion object {
        /** Default services: [ModuleLog] backed by java.util.logging logger `oip.module.<id>`. */
        fun defaults() = ServiceRegistry().apply {
            register(ModuleLog::class.java, ModuleLog.PERMISSION) { m ->
                val l = java.util.logging.Logger.getLogger("oip.module.${m.id}")
                object : ModuleLog {
                    override fun info(message: String) = l.info(message)
                    override fun warn(message: String) = l.warning(message)
                }
            }
        }
    }
}

/** A module's gate to [ServiceRegistry]: only services whose permission is declared in the manifest `permissions`. */
class ModuleServices(val manifest: ModuleManifest, private val registry: ServiceRegistry) {
    private val cache = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    fun isGranted(type: Class<*>): Boolean = registry.permission(type)?.let { it in manifest.permissions } ?: false
    fun <T : Any> get(type: Class<T>): T {
        val perm = registry.permission(type) ?: throw NoSuchElementException("no service ${type.name} registered")
        if (perm !in manifest.permissions)
            throw PermissionDeniedException("module '${manifest.id}' lacks permission '$perm' required for ${type.name}")
        return type.cast(cache.computeIfAbsent(type) { registry.create(type, manifest) })
    }
}
