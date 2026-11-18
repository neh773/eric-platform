package org.oip.host

import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.sdk.ManifestException
import org.oip.sdk.ManifestParser
import org.oip.sdk.ModuleConfig
import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import org.oip.sdk.ModuleManifest
import org.oip.sdk.ModuleServices
import org.oip.sdk.ServiceRegistry
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

class DependencyException(message: String) : RuntimeException(message)

enum class ModuleState { INSTALLED, RUNNING, STOPPED,
    /** Module.uninstall() threw: module stays installed, config untouched; uninstall may be retried. */
    UNINSTALL_FAILED }

/**
 * Loads modules from [modulesDir] (one sub-directory per module containing module.yaml and optional *.jar),
 * manages lifecycle, and stores each module's config under [configRoot]/<id>.
 * The installed-module registry is persisted to [registryFile] so a restart restores install state
 * without calling Module.install() again (existing config is never overwritten).
 */
class ModuleHost(private val modulesDir: Path, private val configRoot: Path,
                 private val parent: ClassLoader = ModuleHost::class.java.classLoader,
                 private val registryFile: Path = configRoot.resolveSibling("installed-modules.tsv"),
                 /** Config store over [configRoot]; every module's resources live under `<configRoot>/<moduleId>/`. */
                 val store: FileConfigStore = FileConfigStore(configRoot, SchemaRegistry()),
                 /** Services offered to modules; each is granted only if its permission is declared in module.yaml. */
                 val services: ServiceRegistry = ServiceRegistry.defaults()) : AutoCloseable {
    private class Entry(val manifest: ModuleManifest, val dir: Path, var instance: Module? = null,
                        var state: ModuleState? = null, var error: String? = null, var context: ModuleContext? = null,
                        var loader: URLClassLoader? = null)
    private val entries = linkedMapOf<String, Entry>()
    /** Install state read from [registryFile] at the first discover. An uninstall in this run drops its entry. */
    private val persisted: MutableMap<String, Pair<ModuleState, String?>> by lazy { readRegistry().toMutableMap() }

    /** Scan the modules directory and parse all manifests. */
    fun discover(): List<ModuleManifest> {
        if (!modulesDir.isDirectory()) return emptyList()
        modulesDir.listDirectoryEntries().filter { it.isDirectory() && it.resolve("module.yaml").exists() }.sorted().forEach { d ->
            val m = ManifestParser.parse(d.resolve("module.yaml").readText())
            validateSchema(m)
            val existing = entries[m.id]
            check(existing == null || existing.dir == d) { "duplicate module id '${m.id}' in ${existing?.dir} and $d" }
            if (existing == null) {
                val e = Entry(m, d); entries[m.id] = e
                persisted[m.id]?.let { (st, err) ->
                    registerSchema(m)
                    val inst = instantiate(e)
                    val ctx = context(m); e.context = ctx
                    inst.restore(ctx)
                    e.instance = inst; e.error = err
                    e.state = if (st == ModuleState.UNINSTALL_FAILED) st else ModuleState.INSTALLED
                }
            }
        }
        return entries.values.map { it.manifest }
    }

    /** Discover and install every not-yet-installed module in dependency order. */
    fun installAll() {
        discover()
        for (id in installOrder()) if (entries.getValue(id).state == null) install(id)
    }

    fun install(id: String) {
        val e = entry(id)
        check(e.state == null) { "'$id' already installed" }
        for (d in e.manifest.dependencies) {
            val dep = entries[d.id]?.takeIf { it.state != null }
                ?: throw DependencyException("'$id' requires '${d.id}' which is not installed")
            if (dep.manifest.version < d.minVersion)
                throw DependencyException("'$id' requires '${d.id}' >= ${d.minVersion}, found ${dep.manifest.version}")
        }
        val instance = instantiate(e)
        registerSchema(e.manifest)
        val ctx = context(e.manifest)
        try { instance.install(ctx) } catch (t: Throwable) { removeConfig(id); throw t }
        e.instance = instance; e.context = ctx; e.state = ModuleState.INSTALLED
        writeRegistry()
    }

    /** Modules with jars get their own [ModuleClassLoader]; directory modules without jars use [parent] (tests). */
    private fun instantiate(e: Entry): Module {
        val jars = e.dir.listDirectoryEntries("*.jar").sorted().map { it.toUri().toURL() }.toTypedArray()
        e.loader?.close(); e.loader = null
        val loader = if (jars.isEmpty()) parent else ModuleClassLoader(jars, parent, e.manifest.id).also { e.loader = it }
        val cls = Class.forName(e.manifest.entrypoint, true, loader)
        return cls.getDeclaredConstructor().newInstance() as? Module
            ?: throw IllegalStateException("${e.manifest.entrypoint} does not implement Module")
    }

    fun start(id: String) {
        val e = entry(id)
        check(e.state == ModuleState.INSTALLED || e.state == ModuleState.STOPPED) { "'$id' cannot start from ${e.state}" }
        for (d in e.manifest.dependencies) if (entries[d.id]?.state != ModuleState.RUNNING) start(d.id)
        e.instance!!.start(); e.state = ModuleState.RUNNING
    }

    fun stop(id: String) {
        val e = entry(id)
        if (e.state != ModuleState.RUNNING) return
        dependents(id).forEach { stop(it) }
        e.instance!!.stop(); e.state = ModuleState.STOPPED
    }

    /**
     * Install a module package (zip, see [ModulePackage]) into `<modulesDir>/<id>/`. Refuses if a module with the
     * same id is already known. On any failure the extracted files and config are removed.
     */
    fun installPackage(bytes: ByteArray): ModuleManifest {
        val pkg = ModulePackage.read(bytes); val m = pkg.manifest
        discover()
        check(entries[m.id] == null) { "module '${m.id}' is already installed (use upgrade)" }
        validateSchema(m)
        val dir = packageDir(m.id)
        check(!dir.exists()) { "module directory $dir already exists" }
        pkg.extractTo(dir)
        val e = Entry(m, dir); entries[m.id] = e
        try { install(m.id) } catch (t: Throwable) {
            e.loader?.close(); entries.remove(m.id); deleteTree(dir); throw t
        }
        return m
    }

    /**
     * Replace an installed module with a newer version from [bytes]. Downgrades and same-version installs are
     * refused ([UpgradeException]). Config resources are kept; the new instance gets [Module.upgrade].
     * A running module is stopped and started again. On failure the previous version is restored.
     */
    fun upgradePackage(bytes: ByteArray): ModuleManifest {
        val pkg = ModulePackage.read(bytes); val m = pkg.manifest
        discover()
        val e = entries[m.id]?.takeIf { it.state != null } ?: throw NoSuchElementException("module '${m.id}' is not installed")
        val old = e.manifest
        if (m.version <= old.version) throw UpgradeException("refusing ${if (m.version == old.version) "reinstall of same version" else "downgrade"} of '${m.id}' from ${old.version} to ${m.version}")
        for (d in m.dependencies) {
            val dep = entries[d.id]?.takeIf { it.state != null } ?: throw DependencyException("'${m.id}' requires '${d.id}' which is not installed")
            if (dep.manifest.version < d.minVersion) throw DependencyException("'${m.id}' requires '${d.id}' >= ${d.minVersion}, found ${dep.manifest.version}")
        }
        validateSchema(m)
        val wasRunning = e.state == ModuleState.RUNNING
        if (wasRunning) stop(m.id)
        val dir = e.dir
        val staged = dir.resolveSibling(".${dir.name}.new"); deleteTree(staged); pkg.extractTo(staged)
        val backup = dir.resolveSibling(".${dir.name}.old"); deleteTree(backup)
        // Close the loader before the directory is renamed. Windows will not move a jar that is still open.
        e.loader?.close()
        e.loader = null
        e.instance = null
        Files.move(dir, backup); Files.move(staged, dir)
        try {
            val ne = Entry(m, dir)
            registerSchema(m)
            val inst = instantiate(ne)
            val ctx = context(m)
            inst.upgrade(ctx, old.version.toString())
            entries[m.id] = ne.also { it.instance = inst; it.context = ctx; it.state = ModuleState.INSTALLED }
            deleteTree(backup)
            writeRegistry()
            if (wasRunning) start(m.id)
            return m
        } catch (t: Throwable) {
            deleteTree(dir); Files.move(backup, dir)
            registerSchema(old)
            val restored = instantiate(e)
            e.instance = restored
            e.context = context(old)
            e.state = ModuleState.INSTALLED
            if (wasRunning) runCatching { start(m.id) }
            throw t
        }
    }

    fun manifest(id: String): ModuleManifest? = entries[id]?.manifest

    /** Uninstall a module, refusing if any installed module depends on it. Removes its config dir. */
    fun uninstall(id: String) = uninstall(id, removeFiles = false)

    /** As [uninstall]; with [removeFiles] the module's package directory under modulesDir is deleted too (no residue). */
    fun uninstall(id: String, removeFiles: Boolean) {
        val e = entry(id)
        checkNotNull(e.state) { "'$id' is not installed" }
        val deps = dependents(id)
        if (deps.isNotEmpty()) throw DependencyException("cannot uninstall '$id': required by ${deps.joinToString()}")
        stop(id)
        try { e.instance!!.uninstall() } catch (t: Throwable) {
            e.state = ModuleState.UNINSTALL_FAILED; e.error = "${t.javaClass.name}: ${t.message}"
            writeRegistry(); throw t
        }
        removeConfig(id)
        e.loader?.close(); e.loader = null
        entries.remove(id)
        persisted.remove(id)
        if (removeFiles) deleteTree(e.dir)
        writeRegistry()
    }

    fun startAll() = installOrder().filter { entries.getValue(it).state != null }.forEach { if (state(it) != ModuleState.RUNNING) start(it) }
    fun stopAll() = installOrder().reversed().forEach { stop(it) }

    /** Stop modules and close their class loaders so Windows can delete or replace the jars. */
    override fun close() {
        runCatching { stopAll() }
        entries.values.forEach { entry ->
            runCatching { entry.loader?.close() }
            entry.loader = null
        }
    }

    fun state(id: String): ModuleState? = entries[id]?.state
    fun instance(id: String): Module? = entries[id]?.instance
    /** The context handed to the module at install/restore (null if not installed). */
    fun context(id: String): ModuleContext? = entries[id]?.context
    fun lastError(id: String): String? = entries[id]?.error
    fun installed(): List<ModuleManifest> = entries.values.filter { it.state != null }.map { it.manifest }

    private fun dependents(id: String) = entries.values
        .filter { it.state != null && it.manifest.dependencies.any { d -> d.id == id } }.map { it.manifest.id }

    private fun installOrder(): List<String> {
        val out = mutableListOf<String>(); val visiting = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in out) return
            if (!visiting.add(id)) throw DependencyException("dependency cycle involving '$id'")
            val e = entries[id] ?: throw DependencyException("missing module '$id'")
            e.manifest.dependencies.forEach { d ->
                if (d.id !in entries) throw DependencyException("'$id' requires '${d.id}' which is not present")
                visit(d.id)
            }
            visiting.remove(id); out += id
        }
        entries.keys.toList().forEach { visit(it) }
        return out
    }

    private fun readRegistry(): Map<String, Pair<ModuleState, String?>> =
        if (!registryFile.exists()) emptyMap() else registryFile.readLines().filter { it.isNotBlank() }.associate { line ->
            val p = line.split('\t', limit = 3)
            p[0] to (ModuleState.valueOf(p[1]) to p.getOrNull(2)?.takeIf { it.isNotEmpty() }?.replace("\\n", "\n"))
        }

    /** Atomically rewrite the registry (id, state, last error) of every installed module. */
    private fun writeRegistry() {
        registryFile.parent?.createDirectories()
        val text = entries.values.filter { it.state != null }.joinToString("") {
            "${it.manifest.id}\t${it.state}\t${(it.error ?: "").replace("\n", "\\n").replace("\t", " ")}\n" }
        val tmp = registryFile.resolveSibling(registryFile.name + ".tmp")
        tmp.writeText(text)
        Files.move(tmp, registryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun context(m: ModuleManifest) = ModuleContext(m, configDir(m.id).createDirectories(), ModuleConfig(m.id, store), ModuleServices(m, services))

    /** Manifest `configSchema` -> schema of kind ModuleConfig; previously stored resources are re-validated against it. */
    private fun registerSchema(m: ModuleManifest) {
        store.registry.registerModuleConfig(m.id, m.configSchema)
        store.revalidate(m.id)
    }
    private fun validateSchema(m: ModuleManifest) {
        if (m.configSchema.isEmpty()) return
        try { SchemaRegistry().registerModuleConfig(m.id, m.configSchema) }
        catch (e: IllegalArgumentException) { throw ManifestException("invalid configSchema in '${m.id}': ${e.message}", e) }
    }
    /** Remove every resource of the module, its schemas, and `<configRoot>/<id>/`. */
    private fun removeConfig(id: String) {
        store.deleteModule(id); store.registry.unregisterModule(id); deleteTree(configDir(id))
    }

    private fun packageDir(id: String): Path = modulesDir.resolve(id).normalize().also {
        require(it.parent == modulesDir.normalize()) { "bad module id '$id'" }
    }
    private fun entry(id: String) = entries[id] ?: throw NoSuchElementException("unknown module '$id'")
    private fun configDir(id: String): Path = configRoot.resolve(id).normalize().also {
        require(it.parent == configRoot.normalize()) { "bad module id '$id'" }
    }
    @OptIn(ExperimentalPathApi::class)
    private fun deleteTree(p: Path) { if (Files.exists(p)) p.deleteRecursively() }
}
