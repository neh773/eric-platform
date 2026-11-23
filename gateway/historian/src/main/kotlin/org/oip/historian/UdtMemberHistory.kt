package org.oip.historian

import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * History settings carried on a UDT member (0.1.1): the member's `history` block, edited in the admin UDT editor,
 * applies to the member in every instance of the type. Each instance member is collected as if it had its own
 * [HISTORY_SETTINGS_KIND] resource. A `HistorySettings` resource naming the same tag wins ([HistoryConfigBinding]).
 *
 * The engine tells this class about tag changes under its write lock, so the collector work runs on its own thread.
 */
class UdtMemberHistory(
    private val engine: TagEngine,
    private val collector: HistoryCollector,
    private val router: HistoryRouter?,
    private val explicit: HistoryConfigBinding?,
) : AutoCloseable {
    private val log = Logger.getLogger("org.oip.historian.udt")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "history-udt-members").apply { isDaemon = true } }
    /** Lowercase normalized paths this class started. */
    private val owned = ConcurrentHashMap.newKeySet<String>()
    private var watch: AutoCloseable? = null

    fun attach() {
        watch = engine.addConfigListener { provider, path, config -> onTag(provider, path, config) }
        for ((provider, config) in engine.copyTagConfigs()) onTag(provider, config.path, config)
    }

    override fun close() {
        watch?.close()
        watch = null
        worker.shutdownNow()
    }

    private fun onTag(provider: String, path: String, config: TagConfig?) {
        val full = if (path.startsWith("[")) path else "[$provider]$path"
        val settings = config?.let { settingsFor(full, it) }
        if (settings == null && !owned.contains(normalizeTagPath(full).lowercase())) return
        try {
            worker.execute {
                runCatching { apply(full, settings) }.onFailure { e -> log.log(Level.FINE, "udt member history $full", e) }
            }
        } catch (_: RejectedExecutionException) {
            // Closing.
        }
    }

    private fun apply(full: String, settings: HistorySettings?) {
        val key = normalizeTagPath(full).lowercase()
        if (explicit?.owns(full) == true) {
            owned.remove(key)
            return
        }
        if (settings == null || !engine.isGeneratedMember(full)) {
            if (owned.remove(key)) collector.stop(full)
            return
        }
        router?.route(settings.normalizedPath, settings.storageProvider)
        collector.configure(settings)
        owned += key
    }

    companion object {
        /** The member's `history` block as [HistorySettings], or null when history is off or the block is absent. */
        fun settingsFor(tagPath: String, config: TagConfig): HistorySettings? {
            val block = config.raw["history"] as? Map<*, *> ?: return null
            if (block["enabled"] == false) return null
            val spec = LinkedHashMap<String, Any?>()
            for ((k, v) in block) if (k != "enabled" && v != null) spec[k.toString()] = v
            spec["tagPath"] = tagPath
            return HistorySettings.fromSpec(spec)
        }
    }
}
