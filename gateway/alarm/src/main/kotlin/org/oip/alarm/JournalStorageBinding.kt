package org.oip.alarm

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope

/** Config kind for where the alarm journal stores its rows (DAT-015). One resource, named [JOURNAL_STORAGE_NAME]. */
const val JOURNAL_STORAGE_KIND = "AlarmJournal"
const val JOURNAL_STORAGE_NAME = "journal"
private const val JOURNAL_MODULE = "alarm"
private const val CONNECTION_NAME = "^$|^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"

/**
 * Binds the `alarm/AlarmJournal/journal` resource to [journal] (DAT-015). Its `connection` names a gateway database
 * connection; the journal rows are then written and read through that connection's pool, which [connectionSource]
 * lends. An empty connection, or no resource, keeps the rows in the journal's own file.
 *
 * The resource may name a connection that does not exist yet, as a history provider can: the journal then reports
 * the connection as faulted (see [AlarmJournal.stats]) and holds its rows until the connection appears.
 */
class JournalStorageBinding(
    private val journal: AlarmJournal,
    private val store: FileConfigStore,
    private val connectionSource: (connection: String) -> JournalConnectionSource,
) : AutoCloseable {
    private var subscription: AutoCloseable? = null
    private val problems = ArrayList<String>()

    @Synchronized
    fun problems(): List<String> = problems.toList()

    fun attach() {
        registerJournalStorageSchema(store.registry)
        store.revalidate(JOURNAL_MODULE)
        subscription = store.subscribe { event -> onEvent(event) }
        apply(store.get(id()))
    }

    /** The connection the resource names, or null for the journal's own file. */
    fun connection(): String? = (store.get(id())?.spec?.get("connection") as? String)?.ifEmpty { null }

    /** Save the resource: [connection] null or empty keeps the rows in the journal's own file. */
    fun save(connection: String?, actor: String): Resource {
        val current = store.get(id())
        val spec = linkedMapOf<String, Any?>("connection" to connection.orEmpty())
        val resource = Resource(
            JOURNAL_STORAGE_KIND,
            Metadata(JOURNAL_STORAGE_NAME, JOURNAL_MODULE, version = current?.metadata?.version ?: 0),
            store.registry.applyDefaults(JOURNAL_MODULE, JOURNAL_STORAGE_KIND, spec),
        )
        return store.put(Scope.GATEWAY, resource, actor)
    }

    override fun close() {
        subscription?.close()
        subscription = null
    }

    private fun onEvent(event: ConfigEvent) {
        val id = event.id ?: return
        if (id.module != JOURNAL_MODULE || id.kind != JOURNAL_STORAGE_KIND) return
        when (event.type) {
            ChangeType.DELETED -> if (id.name == JOURNAL_STORAGE_NAME) apply(null)
            ChangeType.CREATED, ChangeType.UPDATED -> if (id.name == JOURNAL_STORAGE_NAME) apply(event.resource)
            ChangeType.INVALID -> synchronized(this) { problems += "$id: ${event.error}" }
        }
    }

    @Synchronized
    private fun apply(resource: Resource?) {
        val name = (resource?.spec?.get("connection") as? String).orEmpty()
        journal.useConnection(name.ifEmpty { null }, if (name.isEmpty()) null else connectionSource(name))
    }

    private fun id() = ResourceId(JOURNAL_MODULE, Scope.GATEWAY, JOURNAL_STORAGE_KIND, JOURNAL_STORAGE_NAME)
}

fun registerJournalStorageSchema(registry: SchemaRegistry) {
    if (registry.isRegistered(JOURNAL_MODULE, JOURNAL_STORAGE_KIND)) return
    registry.register(JOURNAL_MODULE, JOURNAL_STORAGE_KIND, linkedMapOf(
        "\$schema" to SchemaRegistry.DRAFT,
        "type" to "object",
        "additionalProperties" to false,
        "properties" to linkedMapOf(
            "connection" to mapOf(
                "type" to "string",
                "pattern" to CONNECTION_NAME,
                "default" to "",
                "description" to "Database connection for the journal rows. Empty keeps them in the gateway's own journal file.",
            ),
            "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
        ),
    ))
}
