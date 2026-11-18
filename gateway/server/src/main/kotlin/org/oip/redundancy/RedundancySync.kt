package org.oip.redundancy

import com.fasterxml.jackson.databind.JsonNode
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmSyncState
import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.gateway.Json
import org.oip.tags.Quality
import org.oip.tags.QualityCode
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TAG_KIND
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.time.Instant

/**
 * Config and runtime sync over the public config-store and tag APIs.
 * Node-local resources carry the label `oip-local: true` and are neither copied nor deleted.
 * Redundancy settings themselves live in gateway.yaml and are not in the config store.
 *
 */

const val LOCAL_LABEL = "oip-local"
private const val CONFIG_LOG = 4096
private const val RUNTIME_LOG = 4096
private const val HISTORY_LIMIT = 20_000

data class ConfigDoc(
    val scope: String,
    val module: String,
    val kind: String,
    val name: String,
    val spec: Map<String, Any?>,
    val labels: Map<String, String>,
    val deleted: Boolean,
) {
    val key: String get() = "$scope/$module/$kind/$name"
}

data class ConfigPull(val full: Boolean, val revision: Long, val documents: List<ConfigDoc>)

class ConfigMirror(private val store: FileConfigStore) {
    private val lock = Any()
    private val log = ArrayDeque<Logged>()
    private var revision = 0L
    private var subscription: AutoCloseable? = null

    fun start() {
        if (subscription != null) return
        subscription = store.subscribe { event -> onEvent(event) }
    }

    fun close() {
        subscription?.close()
        subscription = null
    }

    fun revision(): Long = synchronized(lock) { revision }

    fun pull(since: Long): ConfigPull {
        synchronized(lock) {
            if (since >= 0) {
                val oldest = log.firstOrNull()?.revision
                val gap = (oldest == null && since > 0) || (oldest != null && oldest > since + 1)
                if (!gap) return ConfigPull(false, revision, log.filter { it.revision > since }.map { it.doc })
            }
        }
        return fullSnapshot()
    }

    fun apply(pull: ConfigPull) {
        val live = pull.documents.filter { !it.deleted && it.labels[LOCAL_LABEL] != "true" }
        val grouped = live.groupBy { Scope.parse(it.scope) }
        for ((scope, docs) in grouped) {
            val resources = ArrayList<Resource>()
            for (doc in docs) {
                val id = runCatching { ResourceId(doc.module, scope, doc.kind, doc.name) }.getOrNull() ?: continue
                val existing = store.get(id)
                if (existing != null && same(existing, doc)) continue
                resources += Resource(
                    kind = doc.kind,
                    metadata = Metadata(
                        name = doc.name,
                        module = doc.module,
                        labels = doc.labels,
                        version = existing?.metadata?.version ?: 0L,
                    ),
                    spec = doc.spec,
                )
            }
            if (resources.isNotEmpty()) store.putAll(scope, resources, actor = "redundancy", syncEach = false)
        }
        if (pull.full) {
            val keep = live.map { it.key }.toSet()
            for (stored in store.list()) {
                if (isLocal(stored.resource)) continue
                val key = keyOf(stored.id)
                if (key !in keep) store.delete(stored.id)
            }
        } else {
            for (doc in pull.documents) {
                if (!doc.deleted) continue
                val id = runCatching { ResourceId(doc.module, Scope.parse(doc.scope), doc.kind, doc.name) }.getOrNull() ?: continue
                val existing = store.get(id) ?: continue
                if (isLocal(existing)) continue
                store.delete(id)
            }
        }
    }

    private fun fullSnapshot(): ConfigPull {
        val before = synchronized(lock) { revision }
        val merged = LinkedHashMap<String, ConfigDoc>()
        for (stored in store.list()) {
            if (isLocal(stored.resource)) continue
            val doc = encode(stored.id, stored.resource)
            merged[doc.key] = doc
        }
        val overlay = synchronized(lock) { log.filter { it.revision > before } }
        for (entry in overlay) {
            if (entry.doc.deleted || entry.doc.labels[LOCAL_LABEL] == "true") merged.remove(entry.doc.key)
            else merged[entry.doc.key] = entry.doc
        }
        val rev = synchronized(lock) { revision }
        return ConfigPull(true, rev, merged.values.toList())
    }

    private fun onEvent(event: ConfigEvent) {
        val id = event.id ?: return
        if (event.type == ChangeType.INVALID) return
        val nowLocal = isLocal(event.resource)
        val wasLocal = isLocal(event.previous)
        // Node-local before and after (or created or deleted as node-local): the backup never had it.
        if (event.type == ChangeType.DELETED && wasLocal) return
        if (nowLocal && (wasLocal || event.previous == null)) return
        // A shared resource relabeled node-local leaves the backup, as a full pull would remove it there.
        // A node-local one relabeled shared reaches the backup like any other change.
        val doc = if (event.type == ChangeType.DELETED || nowLocal) {
            ConfigDoc(id.scope.path, id.module, id.kind, id.name, emptyMap(), emptyMap(), deleted = true)
        } else {
            val resource = event.resource ?: return
            encode(id, resource)
        }
        synchronized(lock) {
            revision += 1
            log.addLast(Logged(revision, doc))
            while (log.size > CONFIG_LOG) log.removeFirst()
        }
    }

    private fun encode(id: ResourceId, resource: Resource) = ConfigDoc(
        scope = id.scope.path,
        module = id.module,
        kind = id.kind,
        name = id.name,
        spec = resource.spec,
        labels = resource.metadata.labels,
        deleted = false,
    )

    private fun same(existing: Resource, doc: ConfigDoc): Boolean =
        existing.metadata.labels == doc.labels && jsonEqual(existing.spec, doc.spec)

    private data class Logged(val revision: Long, val doc: ConfigDoc)
}

/**
 * Tag paths whose Tag resource is labeled [LOCAL_LABEL] on this node, followed from the config store's events.
 * A node-local tag keeps its value on its node as well as its configuration, so [RuntimeMirror] neither offers
 * nor applies it. A local UDT instance makes every member under its path local.
 */
class LocalTags(private val store: FileConfigStore) {
    private val lock = Any()
    private val byResource = HashMap<ResourceId, String>()
    @Volatile private var locations: Set<String> = emptySet()
    private var subscription: AutoCloseable? = null

    fun start() {
        if (subscription != null) return
        subscription = store.subscribe { event -> onEvent(event) }
        synchronized(lock) {
            for (stored in store.list(TAGS_MODULE, kind = TAG_KIND)) note(stored.id, stored.resource)
            publishLocked()
        }
    }

    fun close() {
        subscription?.close()
        subscription = null
    }

    /** Whether [path] on [provider], or a folder or UDT instance above it, is a node-local tag. */
    fun isLocal(provider: String, path: String): Boolean {
        val local = locations
        if (local.isEmpty()) return false
        var key = locationKey(provider, path)
        while (true) {
            if (key in local) return true
            val cut = key.lastIndexOf('/')
            if (cut <= key.indexOf('\u0000')) return false
            key = key.substring(0, cut)
        }
    }

    private fun onEvent(event: ConfigEvent) {
        val id = event.id ?: return
        if (id.module != TAGS_MODULE || id.kind != TAG_KIND || event.type == ChangeType.INVALID) return
        synchronized(lock) {
            note(id, if (event.type == ChangeType.DELETED) null else event.resource)
            publishLocked()
        }
    }

    /** Caller holds [lock]. */
    private fun note(id: ResourceId, resource: Resource?) {
        if (resource == null || !isLocal(resource)) {
            byResource.remove(id)
            return
        }
        val provider = resource.spec["provider"] as? String ?: "default"
        val path = resource.spec["path"] as? String ?: return
        byResource[id] = locationKey(provider, path)
    }

    private fun publishLocked() {
        locations = if (byResource.isEmpty()) emptySet() else HashSet(byResource.values)
    }

    private fun locationKey(provider: String, path: String): String =
        provider.lowercase() + "\u0000" + path.trim('/').lowercase()
}

data class TagSnap(
    val provider: String,
    val path: String,
    val valueJson: String,
    val quality: String,
    val timestampMillis: Long,
    val type: String,
) {
    val key: String get() = "$provider\u0000$path"
}

data class RuntimeBundle(
    val full: Boolean,
    val revision: Long,
    val tags: List<TagSnap>,
    val alarms: List<AlarmSyncState>,
)

/**
 * Tag values and alarm state since a revision. [isLocal] names the tags that stay on this node
 * (see [LocalTags]): they are left out of [snapshot] and skipped by [apply].
 */
class RuntimeMirror(
    private val tags: TagEngine,
    private val alarms: AlarmEngine,
    private val isLocal: (provider: String, path: String) -> Boolean = { _, _ -> false },
) {
    private val lock = Any()
    private val log = ArrayDeque<LoggedTag>()
    private var lastTags: Map<String, TagSnap> = emptyMap()
    private var lastAlarms: List<AlarmSyncState> = emptyList()
    private var revision = 0L

    fun revision(): Long = synchronized(lock) { revision }

    fun capture() {
        val current = snapshot()
        val alarmStates = alarms.exportRuntime()
        synchronized(lock) { diffUnlocked(current, alarmStates) }
    }

    fun pull(since: Long): RuntimeBundle {
        val current = snapshot()
        val alarmStates = alarms.exportRuntime()
        return synchronized(lock) {
            diffUnlocked(current, alarmStates)
            val full = since < 0 || log.isEmpty() || log.first().revision > since + 1
            val rows = if (full) current else log.filter { it.revision > since }.map { it.tag }
            RuntimeBundle(full, revision, rows, alarmStates)
        }
    }

    /** Apply [snaps], then [alarmStates]. Returns the rows whose tag is not configured on this node. */
    fun apply(snaps: List<TagSnap>, alarmStates: List<AlarmSyncState>): List<TagSnap> {
        val missed = snaps.filter { !applyOne(it) }
        if (alarmStates.isNotEmpty()) alarms.importRuntime(alarmStates)
        return missed
    }

    /**
     * Master-wins unless active uptime says the backup has been in control longer.
     * The returned list is what the backup should keep.
     */
    fun resolvePush(remote: List<TagSnap>, remoteUptime: Long, localUptime: Long, useActiveUptime: Boolean): List<TagSnap> {
        val local = snapshot().associateBy { it.key }
        val adopt = ArrayList<TagSnap>()
        val result = ArrayList<TagSnap>(remote.size)
        for (item in remote) {
            val mine = local[item.key]
            if (mine != null && backupWinsConflict(localUptime, remoteUptime, useActiveUptime) && mine.valueJson != item.valueJson) {
                adopt += item
                result += item
            } else if (mine != null) {
                result += mine
            } else {
                result += item
            }
        }
        if (adopt.isNotEmpty()) apply(adopt, emptyList())
        return result
    }

    fun snapshot(): List<TagSnap> {
        val out = ArrayList<TagSnap>()
        for ((provider, config) in tags.copyTagConfigs()) {
            if (provider == "system") continue
            if (config.type == TagType.FOLDER || config.type == TagType.UDT) continue
            if (isLocal(provider, config.path)) continue
            val user = if (config.path.startsWith("[")) config.path else "[$provider]${config.path}"
            val qv = tags.read(user)
            out += TagSnap(
                provider = provider,
                path = config.path,
                valueJson = Json.write(qv.value),
                quality = qv.quality.code.label,
                timestampMillis = qv.timestamp.toEpochMilli(),
                type = config.type.name.lowercase(),
            )
        }
        return out
    }

    private fun diffUnlocked(current: List<TagSnap>, alarmStates: List<AlarmSyncState>) {
        val next = LinkedHashMap<String, TagSnap>(current.size)
        for (tag in current) next[tag.key] = tag
        val tagChanged = next != lastTags
        val alarmChanged = alarmStates != lastAlarms
        if (!tagChanged && !alarmChanged) return
        revision += 1
        if (tagChanged) {
            for (tag in current) {
                if (lastTags[tag.key] != tag) log.addLast(LoggedTag(revision, tag))
            }
        }
        lastTags = next
        lastAlarms = alarmStates
        while (log.size > RUNTIME_LOG) log.removeFirst()
    }

    /** False only when the tag does not exist here, so the value had nowhere to go. */
    private fun applyOne(snap: TagSnap): Boolean {
        val type = TagType.fromName(snap.type) ?: return true
        if (isLocal(snap.provider, snap.path)) return true
        // Query tags are evaluated on this gateway against its own database connections (TAG-030).
        if (type == TagType.EXPRESSION || type == TagType.DERIVED || type == TagType.REFERENCE ||
            type == TagType.FOLDER || type == TagType.UDT || type == TagType.QUERY
        ) return true
        val user = if (snap.path.startsWith("[")) snap.path else "[${snap.provider}]${snap.path}"
        val value = runCatching { Json.mapper.readValue(snap.valueJson, Any::class.java) }.getOrNull()
        val quality = Quality.fromName(snap.quality) ?: Quality.GOOD
        if (!tags.isConfigured(user)) return false
        val current = tags.read(user)
        if (sameValue(current.value, value) && current.quality.code == quality.code) return true
        val memoryGood = type == TagType.MEMORY && quality.code == QualityCode.GOOD
        if (memoryGood) {
            val written = tags.write(user, value)
            if (!written.ok) tags.updateRaw(user, value, quality, Instant.ofEpochMilli(snap.timestampMillis))
        } else {
            tags.updateRaw(user, value, quality, Instant.ofEpochMilli(snap.timestampMillis))
        }
        return true
    }

    private data class LoggedTag(val revision: Long, val tag: TagSnap)
}

data class HistorySample(
    val provider: String,
    val path: String,
    val valueJson: String,
    val quality: String,
    val timestampMillis: Long,
)

/**
 * In-memory store-and-forward for redundancy. This is not a historian.
 * Partial mode on the backup keeps samples taken while that node is active and
 * forwards them when the master is reachable again. Full mode logs on the node that has them.
 *
 */
class HistoryBuffer(private val mode: HistoryMode, private val role: NodeRole) {
    private val lock = Any()
    private val pending = ArrayDeque<HistorySample>()
    private val logged = ArrayDeque<HistorySample>()

    fun note(sample: HistorySample, active: Boolean) {
        synchronized(lock) {
            if (role == NodeRole.BACKUP && mode == HistoryMode.PARTIAL) {
                if (!active) return
                pending.addLast(sample)
                trim(pending)
            } else if (active || role == NodeRole.MASTER) {
                logged.addLast(sample)
                trim(logged)
            }
        }
    }

    fun peekPending(): List<HistorySample> = synchronized(lock) { pending.toList() }

    /** Drop the samples a successful forward already delivered. Later samples stay queued. */
    fun ackPending(sent: Int) {
        if (sent <= 0) return
        synchronized(lock) {
            repeat(sent.coerceAtMost(pending.size)) { pending.removeFirst() }
        }
    }

    fun log(sample: HistorySample) {
        synchronized(lock) {
            logged.addLast(sample)
            trim(logged)
        }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }
    fun logged(): List<HistorySample> = synchronized(lock) { logged.toList() }

    private fun trim(buffer: ArrayDeque<HistorySample>) {
        while (buffer.size > HISTORY_LIMIT) buffer.removeFirst()
    }
}

class ActiveWindows {
    private val lock = Any()
    private val closed = ArrayDeque<ActiveWindow>()
    private var openStart: Long? = null

    fun note(active: Boolean, now: Long) {
        synchronized(lock) {
            if (active) {
                if (openStart == null) openStart = now
            } else if (openStart != null) {
                closed.addLast(ActiveWindow(openStart!!, now))
                openStart = null
            }
        }
    }

    fun covers(timestampMillis: Long): Boolean = synchronized(lock) {
        val open = openStart
        if (open != null && timestampMillis >= open) return true
        closed.any { it.covers(timestampMillis) }
    }

    fun snapshot(): List<ActiveWindow> = synchronized(lock) {
        val open = openStart
        if (open == null) closed.toList() else closed.toList() + ActiveWindow(open, 0)
    }
}

private fun isLocal(resource: Resource?): Boolean = resource?.metadata?.labels?.get(LOCAL_LABEL) == "true"

private fun keyOf(id: ResourceId): String = "${id.scope.path}/${id.module}/${id.kind}/${id.name}"

private fun jsonEqual(left: Any?, right: Any?): Boolean {
    val a = Json.mapper.valueToTree<JsonNode>(left)
    val b = Json.mapper.valueToTree<JsonNode>(right)
    return a == b
}

private fun sameValue(left: Any?, right: Any?): Boolean {
    if (left == right) return true
    if (left is Number && right is Number) return left.toDouble() == right.toDouble()
    return false
}
