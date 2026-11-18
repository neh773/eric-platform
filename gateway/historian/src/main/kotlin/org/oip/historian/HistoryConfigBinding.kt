package org.oip.historian

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry

const val HISTORIAN_MODULE = "historian"
const val HISTORY_SETTINGS_KIND = "HistorySettings"

/**
 * Which tags are historized, and how, is a typed config resource (SPEC §4): kind [HISTORY_SETTINGS_KIND]
 * in module [HISTORIAN_MODULE], one resource per tag, at `<configRoot>/historian/<scope>/HistorySettings/<name>.yaml`.
 * The same resource is edited through `/api/v1/config`, files, or the CLI. Because collection is driven
 * only by these resources, it resumes after a restart.
 *
 * Example spec: `{ tagPath: "[default]Plant/Speed", sampleMode: periodic, sampleRateMillis: 1000,
 * deadband: { mode: absolute, value: 0.5 }, maxTimeBetweenSamplesMillis: 60000 }`.
 */
fun registerHistorySchemas(registry: SchemaRegistry) {
    registry.register(HISTORIAN_MODULE, HISTORY_SETTINGS_KIND, historySettingsSchema())
    registry.register(HISTORIAN_MODULE, HISTORY_PROVIDER_KIND, historyProviderSchema())
    registry.registerSemantic(HISTORIAN_MODULE, HISTORY_PROVIDER_KIND, ::historyProviderSemantics)
    registry.register(HISTORIAN_MODULE, STORE_AND_FORWARD_KIND, storeAndForwardSchema())
}

/**
 * Applies [HISTORY_SETTINGS_KIND] resources to a [HistoryCollector] and follows [FileConfigStore] events
 * (API writes and file edits). Follows the pattern of `org.oip.tags.TagConfigBinding`.
 *
 * Two resources naming the same tag: the last one applied wins; deleting a resource stops collection
 * only when that resource is the one currently applied for its tag.
 */
class HistoryConfigBinding(
    val collector: HistoryCollector,
    val store: FileConfigStore,
    val registry: SchemaRegistry,
    /** Where each tag's rows go ([HistorySettings.storageProvider]); null routes everything to the sink as-is. */
    val router: HistoryRouter? = null,
) : AutoCloseable {
    private val problems = ArrayList<String>()
    /** Resource id -> normalized tag path it configured. */
    private val owners = HashMap<String, String>()
    /** Normalized lowercase tag path -> resource id currently applied. */
    private val applied = HashMap<String, String>()
    private var subscription: AutoCloseable? = null

    @Synchronized
    fun problems(): List<String> = problems.toList()

    fun attach() {
        registerHistorySchemas(registry)
        store.revalidate(HISTORIAN_MODULE)
        reload()
        subscription = store.subscribe { event -> onEvent(event) }
    }

    fun reload() {
        store.list(HISTORIAN_MODULE, kind = HISTORY_SETTINGS_KIND).forEach { apply(it.id, it.resource) }
    }

    override fun close() {
        subscription?.close()
        subscription = null
    }

    private fun onEvent(event: ConfigEvent) {
        val id = event.id ?: return
        if (id.module != HISTORIAN_MODULE || id.kind != HISTORY_SETTINGS_KIND) return
        when (event.type) {
            ChangeType.DELETED -> remove(id)
            ChangeType.CREATED, ChangeType.UPDATED -> event.resource?.let { apply(id, it) }
            ChangeType.INVALID -> synchronized(this) { problems += "$id: ${event.error}" }
        }
    }

    @Synchronized
    private fun apply(id: ResourceId, resource: Resource) {
        try {
            val settings = HistorySettings.fromSpec(resource.spec)
            val path = settings.normalizedPath
            // A resource that moved to another tag stops collecting its old tag.
            owners[id.toString()]?.let { old -> if (!old.equals(path, ignoreCase = true)) stopIfOwned(id.toString(), old) }
            router?.route(path, settings.storageProvider)
            collector.configure(settings)
            owners[id.toString()] = path
            applied[path.lowercase()] = id.toString()
        } catch (e: RuntimeException) {
            problems += "$id: ${e.message}"
        }
    }

    @Synchronized
    private fun remove(id: ResourceId) {
        val path = owners.remove(id.toString()) ?: return
        stopIfOwned(id.toString(), path)
    }

    private fun stopIfOwned(name: String, path: String) {
        if (applied[path.lowercase()] != name) return
        applied.remove(path.lowercase())
        collector.stop(path)
    }
}

private fun historySettingsSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("tagPath"),
    "properties" to linkedMapOf(
        "tagPath" to mapOf("type" to "string", "minLength" to 1,
            "description" to "Tag to historize, e.g. [default]Plant/Speed. A path without a provider uses [default]."),
        "sampleMode" to mapOf("type" to "string", "enum" to HistorySampleMode.entries.map { it.wire },
            "default" to HistorySampleMode.ON_CHANGE.wire),
        "sampleRateMillis" to mapOf("type" to "integer", "minimum" to 1, "default" to 1000,
            "description" to "Periodic sample interval. Ignored for onChange."),
        "deadband" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "mode" to mapOf("type" to "string", "enum" to listOf("off", "absolute", "percent"), "default" to "off"),
                "value" to mapOf("type" to "number", "minimum" to 0, "default" to 0),
            ),
        ),
        "maxTimeBetweenSamplesMillis" to mapOf("type" to "integer", "minimum" to 0, "default" to 0,
            "description" to "Store a sample at least this often even when the deadband is not exceeded. 0 disables."),
        "storageProvider" to mapOf("type" to "string", "pattern" to PROVIDER_NAME.pattern, "default" to LOCAL_PROVIDER,
            "description" to "History provider for this tag: 'local' (the built-in embedded historian) or the name of a historian/HistoryProvider resource."),
    ),
)
