package org.oip.alarm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.tags.Level
import org.oip.tags.QualifiedValue
import org.oip.tags.TagConfig
import org.oip.tags.TagConfigWatch
import org.oip.tags.TagEngine
import org.oip.tags.TagPath
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Evaluates tag alarms: active/clear crossed with acked/unacked, on/off delays, deadband,
 * priorities, and shelving with an expiry. Notification pipelines are not part of this engine.
 *
 *
 * Shelving hides the alarm and holds new events back until the shelf expires, then the current
 * tag value is evaluated and may open a new event.
 *
 * Time comes from [clock]. Call [poll] after advancing a fake clock so delays and shelf expiries run.
 *
 * [onNotice] runs after the alarm lock is released, once per active, clear, or acknowledge transition.
 *
 * Journal rows are only queued here ([AlarmJournal.append]). The SQLite insert runs on the journal's
 * writer thread, so a tag update that trips an alarm never waits on disk and never holds the tag-engine
 * lock during journal I/O (P2-14).
 *
 * Config changes (A-1). An alarm is identified by its source: provider, tag path and alarm name. When a
 * tag's config is saved again, each alarm whose source is unchanged keeps its live state (active event,
 * acknowledgement, shelf, delay timers) and takes the new definition; the next evaluation decides whether
 * it is still true. A changed setpoint that makes the condition false is an ordinary clear, after the off
 * delay. An alarm that becomes disabled (by a save or by its bound enable) clears at once, as a system
 * clear. An alarm that goes away (removed from the tag, renamed, its tag deleted or moved, or its config
 * no longer valid) is retired: an active event gets a system clear, and every event still unacknowledged
 * gets a system acknowledgement, so nothing is left live that no one can acknowledge. Retiring writes
 * journal rows, so it runs from [TagEngine.afterMutation] once the tag-engine lock is released, never
 * from the config watch itself (P2-14). A tag removed and added again inside one mutation (a UDT
 * instance being re-expanded) keeps its alarms.
 *
 * Restart (review-3 F4). Each alarm's live state (its live events with their ids, acknowledgements and ack users,
 * the latched condition, and the shelf with its expiry and actor) is saved through [AlarmJournal.saveState] on every
 * transition, in order with the journal rows. A new engine on the same journal file restores it when the alarm's
 * tag is configured, before the first evaluation, so the same event stays live with the same id, still acknowledged
 * or shelved; an event whose condition is no longer true then gets an ordinary clear. On the first [poll] that may
 * write the journal, events the journal left open (an active row without a clear or an acknowledgement) that are not
 * live any more, for example of an alarm removed while the gateway was down, get a system clear and a system
 * acknowledgement, and saved state that no configured alarm claimed is dropped.
 */
class AlarmEngine(
    val clock: Clock,
    val journal: AlarmJournal,
    val settings: AlarmSettings = journal.settings,
) : AutoCloseable {
    private val mapLock = Any()
    private val byTag = HashMap<String, WatchedTag>()
    private val byRead = HashMap<String, WatchedTag>()
    private val dirty = ConcurrentLinkedQueue<String>()
    private val draining = AtomicBoolean(false)
    private var tags: TagEngine? = null
    private var closed = false
    /** Saved live state by source, not yet claimed by a configured alarm (F4). */
    private val restored = ConcurrentHashMap<String, String>(
        runCatching { journal.loadStates() }.onFailure {
            log.log(System.Logger.Level.WARNING, "saved alarm state could not be read; starting without it: ${it.message}")
        }.getOrDefault(emptyMap()),
    )
    @Volatile private var reconciled = false

    /** Notification pipelines subscribe here. The engine does not send email itself. */
    var onNotice: ((AlarmNotice) -> Unit)? = null

    /**
     * When false, live state still updates and [onNotice] still runs, but the journal does not append.
     * A standby node sets this false so it does not log the same events as the active node.
     */
    var recordsJournal: () -> Boolean = { true }

    /**
     * Alarms that left the config during the current tag mutation on this thread, by tag key. They are
     * retired after the tag lock is released, unless the same tag and alarm come back first.
     */
    private val parked = ThreadLocal.withInitial { LinkedHashMap<String, MutableList<AlarmRuntime>>() }

    private val liveWatchers = CopyOnWriteArrayList<() -> Unit>()
    private val wakePending = AtomicBoolean(false)

    /**
     * Called after alarm locks are released when the live set may have changed.
     * The listener may run on a tag thread and must not do network I/O or take the tag lock.
     * If the tag write lock is held, delivery waits until that lock is released.
     */
    fun onLiveChange(listener: () -> Unit): AutoCloseable {
        liveWatchers.add(listener)
        return AutoCloseable { liveWatchers.remove(listener) }
    }

    fun attach(engine: TagEngine) {
        tags = engine
        engine.configWatch = TagConfigWatch { provider, path, config -> onTag(provider, path, config) }
        engine.afterMutation = {
            retireParked()
            drain()
            flushDeferredWatchers()
        }
        val existing = engine.copyTagConfigs()
        for ((provider, config) in existing) onTag(provider, config.path, config)
        drain()
    }

    /** Advance delay timers and shelf expiries to [clock]. The first call that may write the journal also reconciles it. */
    fun poll() {
        if (!reconciled && recordsJournal()) reconcileJournal()
        val now = clock.instant()
        val due = ArrayList<String>()
        synchronized(mapLock) {
            for (tag in byTag.values) {
                if (tag.alarms.any { it.memory.needsPoll(now) }) due += tag.readPath
            }
        }
        for (path in due) enqueue(path)
        drain()
        flushDeferredWatchers()
    }

    fun query(filter: AlarmFilter = AlarmFilter()): List<AlarmEventView> {
        val now = clock.instant()
        val engine = tags
        val out = ArrayList<AlarmEventView>()
        val snapshot = synchronized(mapLock) { byTag.values.toList() }
        for (tag in snapshot) {
            if (!tag.alive) continue
            val params = engine?.parametersOf(tag.readPath) ?: emptyMap()
            for (alarm in tag.alarms) {
                val resolved = if (engine == null) null else try {
                    alarm.definition.resolve(engine, tag.provider, tag.path, params)
                } catch (_: RuntimeException) {
                    null
                }
                synchronized(alarm.memory) {
                    if (alarm.memory.isShelved(now)) return@synchronized
                    for (event in alarm.memory.live) {
                        if (filter.active != null && event.active != filter.active) continue
                        if (filter.minPriority != null && event.priority < filter.minPriority) continue
                        if (!wildcardMatch(filter.source, event.source)) continue
                        if (!wildcardMatch(filter.displayPath, event.displayPath)) continue
                        out += event.view(tag.readPath, resolved, alarm.definition.notes)
                    }
                }
            }
        }
        return out.sortedWith(compareBy({ it.source }, { it.eventId }))
    }

    /**
     * Check an acknowledgement without applying it: every id names a live event, and notes are given where the
     * alarm requires them. Throws what [acknowledge] would throw, so a caller that splits ids between this gateway
     * and a peer can refuse the whole request before acting on any part of it (review-3 F7).
     */
    fun checkAcknowledge(eventIds: List<String>, notes: String?) {
        if (eventIds.isEmpty()) throw IllegalArgumentException("eventIds is required")
        val found = eventIds.map { id -> locate(id) ?: throw NoSuchElementException("alarm event $id not found") }
        for ((alarm, event) in found) {
            val acked = synchronized(alarm.memory) { event.acked }
            if (alarm.definition.ackNotesRequired && notes.isNullOrBlank() && !acked) {
                throw IllegalArgumentException("acknowledgement notes are required for ${event.source}")
            }
        }
    }

    /** The alarm source of a live event, or null when no live event has that id. */
    fun sourceOfEvent(eventId: String): String? = locate(eventId)?.first?.source

    /** Whether an alarm with this source is configured on this gateway. */
    fun hasSource(source: String): Boolean = findSource(source) != null

    fun acknowledge(eventIds: List<String>, user: String, notes: String?) {
        checkAcknowledge(eventIds, notes)
        val found = eventIds.map { id -> locate(id) ?: throw NoSuchElementException("alarm event $id not found") }
        val now = clock.instant()
        val notices = ArrayList<AlarmNotice>()
        for ((alarm, event) in found) {
            synchronized(alarm.memory) {
                alarm.memory.acknowledge(alarm, event, user, notes, system = false, now, notices)
                alarm.memory.persist()
            }
        }
        deliver(notices)
        wakeWatchers()
    }

    /**
     * Shelve each source until now + [duration]. [user] is kept for [shelved] and written to the journal
     * as one [JournalEventTypes.SHELVED] row per source. The journal call only queues the row (P2-14).
     */
    fun shelve(sources: List<String>, duration: Duration, user: String? = null) {
        val found = checkShelveFound(sources, duration)
        val now = clock.instant()
        val until = now.plus(duration)
        for ((alarm, memory) in found) {
            val fallback = displayPathOf(alarm)
            val row = synchronized(memory) {
                memory.shelvedUntil = until
                memory.shelvedAt = now
                memory.shelvedBy = user
                memory.dirty = true
                memory.persist()
                memory.shelfEntry(JournalEventTypes.SHELVED, now, linkedMapOf(
                    "actor" to user,
                    "shelveExpiry" to until.toEpochMilli(),
                ), fallback)
            }
            journal.append(row)
        }
        wakeWatchers()
    }

    /** Check a shelve request without applying it; throws what [shelve] would throw (review-3 F7). */
    fun checkShelve(sources: List<String>, duration: Duration) {
        checkShelveFound(sources, duration)
    }

    private fun checkShelveFound(sources: List<String>, duration: Duration): List<Pair<AlarmRuntime, AlarmMemory>> {
        if (sources.isEmpty()) throw IllegalArgumentException("sources is required")
        if (duration.isZero || duration.isNegative) throw IllegalArgumentException("shelve duration must be positive")
        if (duration > settings.maxShelveDuration) {
            throw IllegalArgumentException("shelve duration must not exceed ${settings.maxShelveDuration.toSeconds()} seconds")
        }
        val found = sources.map { source -> findSource(source) ?: throw NoSuchElementException("alarm $source not found") }
        for ((alarm, _) in found) {
            if (!alarm.definition.shelvingAllowed) throw IllegalArgumentException("shelving is not allowed for ${alarm.source}")
        }
        return found
    }

    /** End the shelf now and re-evaluate. Writes one [JournalEventTypes.UNSHELVED] row per source that was shelved. */
    fun unshelve(sources: List<String>, user: String? = null) {
        if (sources.isEmpty()) throw IllegalArgumentException("sources is required")
        val found = sources.map { source -> findSource(source) ?: throw NoSuchElementException("alarm $source not found") }
        val now = clock.instant()
        for ((alarm, memory) in found) {
            val fallback = displayPathOf(alarm)
            val row = synchronized(memory) {
                val wasShelved = memory.isShelved(now)
                if (memory.shelvedUntil != null) memory.shelvedUntil = now
                memory.shelvedAt = null
                memory.shelvedBy = null
                memory.dirty = true
                memory.persist()
                if (wasShelved) memory.shelfEntry(JournalEventTypes.UNSHELVED, now, linkedMapOf("actor" to user), fallback) else null
            }
            if (row != null) journal.append(row)
        }
        for ((alarm, _) in found) enqueue(alarm.readPath)
        drain()
        wakeWatchers()
    }

    fun isShelved(source: String, now: Instant = clock.instant()): Boolean {
        val found = findSource(source) ?: return false
        return synchronized(found.second) { found.second.isShelved(now) }
    }

    /**
     * Alarms that are shelved now, one entry per alarm, with the expiry, who shelved it, and the live
     * event if it has one. An alarm whose condition started while shelved has no live event unless
     * Store Shelved Events is on; [ShelvedAlarmView.conditionActive] still reports it.
     */
    fun shelved(filter: AlarmFilter = AlarmFilter()): List<ShelvedAlarmView> {
        val now = clock.instant()
        val engine = tags
        val out = ArrayList<ShelvedAlarmView>()
        val snapshot = synchronized(mapLock) { byTag.values.toList() }
        for (tag in snapshot) {
            if (!tag.alive) continue
            val params = engine?.parametersOf(tag.readPath) ?: emptyMap()
            for (alarm in tag.alarms) {
                val resolved = if (engine == null) null else try {
                    alarm.definition.resolve(engine, tag.provider, tag.path, params)
                } catch (_: RuntimeException) {
                    null
                }
                val view = synchronized(alarm.memory) {
                    val memory = alarm.memory
                    if (!memory.isShelved(now)) return@synchronized null
                    val event = memory.activeEvent ?: memory.live.lastOrNull()
                    val displayPath = event?.displayPath ?: resolved?.displayPath ?: "${tag.path}/${alarm.definition.name}"
                    ShelvedAlarmView(
                        source = alarm.source,
                        name = alarm.definition.name,
                        displayPath = displayPath,
                        tagPath = tag.readPath,
                        priority = alarm.definition.priority,
                        shelvedAt = memory.shelvedAt,
                        shelvedUntil = memory.shelvedUntil!!,
                        shelvedBy = memory.shelvedBy,
                        conditionActive = memory.latched,
                        event = event?.view(tag.readPath, resolved, alarm.definition.notes),
                    )
                } ?: continue
                if (filter.active != null && (view.event?.active ?: view.conditionActive) != filter.active) continue
                if (filter.minPriority != null && view.priority < filter.minPriority) continue
                if (!wildcardMatch(filter.source, view.source)) continue
                if (!wildcardMatch(filter.displayPath, view.displayPath)) continue
                out += view
            }
        }
        return out.sortedBy { it.source }
    }

    /** Live alarm state for redundancy runtime sync. Shelved alarms are included. */
    fun exportRuntime(): List<AlarmSyncState> {
        val out = ArrayList<AlarmSyncState>()
        val snapshot = synchronized(mapLock) { byTag.values.toList() }
        for (tag in snapshot) {
            if (!tag.alive) continue
            for (alarm in tag.alarms) {
                synchronized(alarm.memory) {
                    val event = alarm.memory.activeEvent ?: alarm.memory.live.lastOrNull()
                    out += AlarmSyncState(
                        source = alarm.source,
                        tagPath = alarm.readPath,
                        active = event?.active ?: alarm.memory.latched,
                        acknowledged = event?.acked ?: false,
                        ackUser = event?.ackUser,
                        shelvedUntilMillis = alarm.memory.shelvedUntil?.toEpochMilli(),
                        eventId = event?.id,
                    )
                }
            }
        }
        return out
    }

    /**
     * Apply a peer's live alarm state. Shelving is copied. An event that is already
     * live and should be acknowledged is system-acknowledged. The caller applies tag
     * values first so a still-active condition has an event to acknowledge.
     */
    fun importRuntime(states: List<AlarmSyncState>) {
        if (states.isEmpty()) return
        val now = clock.instant()
        val notices = ArrayList<AlarmNotice>()
        for (state in states) {
            val found = findSource(state.source) ?: continue
            val (alarm, memory) = found
            synchronized(memory) {
                memory.shelvedUntil = state.shelvedUntilMillis?.let { Instant.ofEpochMilli(it) }
                val event = memory.activeEvent
                    ?: state.eventId?.let { id -> memory.live.lastOrNull { it.id == id } }
                    ?: memory.live.lastOrNull()
                if (event != null && state.acknowledged && !event.acked) {
                    memory.acknowledge(alarm, event, state.ackUser, null, system = true, now, notices)
                }
                memory.dirty = true
                memory.persist()
            }
        }
        deliver(notices)
        wakeWatchers()
    }

    /** Current live event, including one that is shelved. Null once the event has left the live set. */
    fun liveState(eventId: String): AlarmLiveState? {
        val tags = synchronized(mapLock) { byTag.values.toList() }
        for (tag in tags) {
            for (alarm in tag.alarms) {
                val snap = synchronized(alarm.memory) { alarm.memory.liveState(eventId) }
                if (snap != null) return snap
            }
        }
        return null
    }

    private fun appendJournal(entry: JournalEntry): Boolean {
        if (!recordsJournal()) return false
        return journal.append(entry)
    }

    private val noticeListeners = java.util.concurrent.CopyOnWriteArrayList<(AlarmNotice) -> Unit>()

    /** Extra listeners beside [onNotice]. The notification profile keeps [onNotice]; tag event scripts use this. */
    fun listen(listener: (AlarmNotice) -> Unit): AutoCloseable {
        noticeListeners.add(listener)
        return AutoCloseable { noticeListeners.remove(listener) }
    }

    private fun deliver(notices: List<AlarmNotice>) {
        if (notices.isEmpty()) return
        val listener = onNotice
        for (notice in notices) {
            if (listener != null) runCatching { listener(notice) }
            for (extra in noticeListeners) runCatching { extra(notice) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val engine = tags
        if (engine != null) {
            if (engine.configWatch != null) engine.configWatch = null
            if (engine.afterMutation != null) engine.afterMutation = null
        }
        val watched = synchronized(mapLock) {
            val all = byTag.values.toList()
            byTag.clear()
            byRead.clear()
            all
        }
        watched.forEach { it.close() }
        parked.remove()
        liveWatchers.clear()
        journal.close()
    }

    private fun wakeWatchers() {
        if (tags?.holdingWriteLock() == true) {
            wakePending.set(true)
            return
        }
        dispatchWatchers()
    }

    private fun flushDeferredWatchers() {
        if (tags?.holdingWriteLock() == true) return
        if (wakePending.compareAndSet(true, false)) dispatchWatchers()
    }

    private fun dispatchWatchers() {
        wakePending.set(false)
        if (liveWatchers.isEmpty()) return
        for (listener in liveWatchers) {
            try {
                listener()
            } catch (_: RuntimeException) {
                // A watcher must not break evaluation. Network delivery is queued elsewhere.
            }
        }
    }

    /**
     * Runs under the tag-engine write lock (from the config watch), so it only swaps maps, matches alarms
     * by name, and subscribes. Alarms that leave are parked and retired by [retireParked] after the lock.
     */
    private fun onTag(provider: String, path: String, config: TagConfig?) {
        val key = tagKey(provider, path)
        val previous = synchronized(mapLock) { byTag.remove(key)?.also { byRead.remove(it.readPath) } }
        previous?.close()
        val pool = ArrayList<AlarmRuntime>()
        parked.get().remove(key)?.let { pool += it }
        previous?.let { pool += it.alarms }
        val definitions = if (config == null) emptyList() else try {
            AlarmDefinition.parseAll(config)
        } catch (_: RuntimeException) {
            emptyList()
        }
        val engine = tags
        if (config == null || definitions.isEmpty() || engine == null) {
            park(key, pool)
            if (previous != null) wakeWatchers()
            return
        }
        val readPath = "[$provider]$path"
        val kept = ArrayList<AlarmRuntime>()
        val runtimes = definitions.map { definition ->
            val match = pool.firstOrNull { it.definition.name == definition.name }
            if (match != null) {
                pool.remove(match)
                kept += match
            }
            AlarmRuntime(definition, provider, path, match?.memory)
        }
        val watched = WatchedTag(provider, path, readPath, config, runtimes)
        try {
            val deps = LinkedHashSet<String>()
            deps += readPath
            for (alarm in watched.alarms) deps += alarm.definition.dependencyPaths(provider, path)
            for (dep in deps) {
                val sub = engine.subscribe(dep) { enqueue(readPath) }
                watched.subs += sub
            }
        } catch (_: RuntimeException) {
            // A dependency that cannot be subscribed (an unknown provider, a bad path): the tag's alarms cannot
            // be evaluated, so they are treated like alarms whose config no longer parses and are retired.
            watched.close()
            park(key, pool + kept)
            if (previous != null) wakeWatchers()
            return
        }
        park(key, pool)
        synchronized(mapLock) {
            byTag[key] = watched
            byRead[readPath] = watched
        }
        enqueue(readPath)
    }

    /** Tag paths match case-insensitively, so a save that only changes case finds the same entry. */
    private fun tagKey(provider: String, path: String): String = try {
        TagPath.parse(path, provider).resolve(null).canonicalKey()
    } catch (_: RuntimeException) {
        provider.lowercase() + "\u0000" + path.lowercase()
    }

    private fun park(key: String, alarms: List<AlarmRuntime>) {
        if (alarms.isEmpty()) return
        parked.get().getOrPut(key) { ArrayList() } += alarms
        if (tags?.holdingWriteLock() != true) retireParked()
    }

    /** Retire the alarms this thread parked. Called with the tag lock released. */
    private fun retireParked() {
        val map = parked.get()
        if (map.isEmpty()) return
        val gone = map.values.flatten()
        map.clear()
        parked.remove()
        if (closed) return
        val notices = ArrayList<AlarmNotice>()
        val now = clock.instant()
        for (alarm in gone) {
            synchronized(alarm.memory) {
                alarm.memory.retire(alarm, now, notices)
                journal.saveState(alarm.source, null)
            }
        }
        deliver(notices)
        wakeWatchers()
    }

    private fun enqueue(readPath: String) {
        dirty.add(readPath)
        val engine = tags ?: return
        if (!engine.holdingWriteLock()) drain()
    }

    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        var changed = false
        try {
            val engine = tags ?: return
            while (true) {
                val path = dirty.poll() ?: break
                val tag = synchronized(mapLock) { byRead[path] } ?: continue
                if (!tag.alive) continue
                val qv = engine.read(tag.readPath)
                val params = engine.parametersOf(tag.readPath)
                val now = clock.instant()
                for (alarm in tag.alarms) {
                    if (!tag.alive) break
                    val resolved = try {
                        alarm.definition.resolve(engine, tag.provider, tag.path, params)
                    } catch (_: RuntimeException) {
                        continue
                    }
                    val notices = ArrayList<AlarmNotice>()
                    var shelfChanged = false
                    synchronized(alarm.memory) {
                        if (!tag.alive) return@synchronized
                        val shelf = alarm.memory.shelvedUntil
                        alarm.memory.step(alarm, tag.config, resolved, qv, now, notices)
                        alarm.memory.persist()
                        shelfChanged = shelf != alarm.memory.shelvedUntil
                    }
                    if (notices.isNotEmpty() || shelfChanged) changed = true
                    deliver(notices)
                }
            }
        } finally {
            draining.set(false)
            if (changed) wakeWatchers()
            val engine = tags
            if (dirty.isNotEmpty() && (engine == null || !engine.holdingWriteLock())) drain()
        }
    }

    /**
     * F4: close what the journal left open and nobody can close any more. Runs once, from the first [poll] on a node
     * that writes the journal, after start-up has configured the tags (and so restored their alarms).
     */
    private fun reconcileJournal() {
        reconciled = true
        for (source in restored.keys.toList()) {
            if (restored.remove(source) != null) journal.saveState(source, null)
        }
        val snapshot = synchronized(mapLock) { byTag.values.toList() }
        // Hold every alarm's memory across the live-id snapshot and the journal read. A config
        // save retires under that same lock and queues its system ack before it drops the event
        // from the live set. Without the lock held here, the first poll can see the event gone
        // and not yet acknowledged, and write a second ack (AlarmHttpTest on a loaded runner).
        val memories = snapshot.flatMap { it.alarms }.map { it.memory }.sortedBy { System.identityHashCode(it) }
        fun <T> holding(index: Int, body: () -> T): T =
            if (index == memories.size) body() else synchronized(memories[index]) { holding(index + 1, body) }
        val open = try {
            holding(0) {
                val liveIds = HashSet<String>()
                for (tag in snapshot) for (alarm in tag.alarms) alarm.memory.live.forEach { liveIds += it.id }
                journal.openEvents().filter { it.eventId !in liveIds }
            }
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "alarm journal could not be reconciled at start-up: ${e.message}")
            return
        } catch (e: java.sql.SQLException) {
            log.log(System.Logger.Level.WARNING, "alarm journal could not be reconciled at start-up: ${e.message}")
            return
        }
        val now = clock.instant().toEpochMilli()
        for (event in open) {
            val priority = AlarmPriority.entries.getOrNull(event.priority) ?: continue
            fun row(type: Int, flags: Int) = JournalEntry(
                eventId = event.eventId,
                source = event.source,
                displayPath = event.displayPath,
                priority = priority,
                eventType = type,
                eventFlags = flags,
                eventTimeMillis = now,
                shelved = false,
                data = emptyMap(),
            )
            if (!event.cleared) {
                journal.append(row(AlarmEventType.CLEAR, AlarmEventFlags.SYSTEM or (if (event.acknowledged) AlarmEventFlags.ACKNOWLEDGED else 0)))
            }
            if (!event.acknowledged) journal.append(row(AlarmEventType.ACKNOWLEDGED, AlarmEventFlags.SYSTEM_ACK or AlarmEventFlags.CLEARED))
        }
    }

    /** F4: the saved state for [source], if a previous run left one, as a memory for a newly configured alarm. */
    private fun restoreMemory(definition: AlarmDefinition, source: String, tagPath: String): AlarmMemory? {
        val saved = restored.remove(source) ?: return null
        return try {
            AlarmMemory(definition, source, tagPath).also { it.load(mapper.readTree(saved)) }
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "saved state of alarm $source could not be restored: ${e.message}")
            null
        }
    }

    private fun locate(eventId: String): Pair<AlarmRuntime, LiveEvent>? {
        val tags = synchronized(mapLock) { byTag.values.toList() }
        for (tag in tags) {
            for (alarm in tag.alarms) {
                val event = synchronized(alarm.memory) { alarm.memory.live.firstOrNull { it.id == eventId } }
                if (event != null) return alarm to event
            }
        }
        return null
    }

    private fun findSource(source: String): Pair<AlarmRuntime, AlarmMemory>? {
        val tags = synchronized(mapLock) { byTag.values.toList() }
        for (tag in tags) {
            for (alarm in tag.alarms) {
                if (alarm.source == source) return alarm to alarm.memory
            }
        }
        return null
    }

    /** Resolved display path for a row with no live event. Called outside the alarm lock because it reads tags. */
    private fun displayPathOf(alarm: AlarmRuntime): String {
        val engine = tags
        val tag = synchronized(mapLock) { byRead[alarm.readPath] }
        if (engine != null && tag != null) {
            try {
                return alarm.definition.resolve(engine, tag.provider, tag.path, engine.parametersOf(tag.readPath)).displayPath
            } catch (_: RuntimeException) {
                // Fall through to the default display path.
            }
        }
        return "${alarm.tagPath}/${alarm.definition.name}"
    }

    private class WatchedTag(
        val provider: String,
        val path: String,
        val readPath: String,
        val config: TagConfig,
        val alarms: List<AlarmRuntime>,
    ) {
        val subs = ArrayList<AutoCloseable>()
        @Volatile var alive: Boolean = true

        fun close() {
            alive = false
            subs.forEach { runCatching { it.close() } }
            subs.clear()
        }
    }

    /**
     * [kept] is the live state of the same alarm from before a config save. A kept alarm keeps the source
     * (and so the tag path spelling) it was opened under, even when the save changed only the case.
     */
    private inner class AlarmRuntime(
        val definition: AlarmDefinition,
        provider: String,
        tagPath: String,
        kept: AlarmMemory? = null,
    ) {
        val source = kept?.source ?: "prov:$provider:/tag:$tagPath:/alm:${definition.name}"
        val readPath = "[$provider]$tagPath"
        val tagPath = kept?.tagPath ?: tagPath
        val memory = kept ?: restoreMemory(definition, source, this.tagPath) ?: AlarmMemory(definition, source, this.tagPath)
    }

    private inner class AlarmMemory(
        /**
         * The definition of the runtime last evaluated. Written only by [step], under this memory's lock, so
         * one evaluation never mixes the definition from before a config save with the one after it.
         */
        @Volatile var definition: AlarmDefinition,
        val source: String,
        val tagPath: String,
    ) {
        var shelvedUntil: Instant? = null
        var shelvedAt: Instant? = null
        var shelvedBy: String? = null
        /** Most recent event, kept after it leaves the live set so a shelve row can name it. */
        var lastEvent: LiveEvent? = null
        var conditionSince: Instant? = null
        var clearSince: Instant? = null
        var deadline: Instant? = null
        var latched: Boolean = false
        var suppressedActive: Boolean = false
        var activeEvent: LiveEvent? = null
        val live = ArrayDeque<LiveEvent>()
        /** Set by every transition; [persist] saves the state once and clears it (F4). */
        var dirty = false

        /** Queue this alarm's live state for saving if it changed. Called under this memory's lock. */
        fun persist() {
            if (!dirty) return
            dirty = false
            journal.saveState(source, snapshot())
        }

        /** The live state as a JSON document, or null when there is nothing to keep (no events, not latched, not shelved). */
        fun snapshot(): String? {
            if (!latched && !suppressedActive && live.isEmpty() && shelvedUntil == null) return null
            val root = mapper.createObjectNode()
            root.put("version", 1)
            root.put("latched", latched)
            root.put("suppressedActive", suppressedActive)
            shelvedUntil?.let { root.put("shelvedUntil", it.toString()) }
            shelvedAt?.let { root.put("shelvedAt", it.toString()) }
            shelvedBy?.let { root.put("shelvedBy", it) }
            activeEvent?.let { root.put("activeEventId", it.id) }
            val events = root.putArray("events")
            for (event in live) {
                val node = events.addObject()
                node.put("id", event.id)
                node.put("name", event.name)
                node.put("displayPath", event.displayPath)
                node.put("priority", event.priority.code)
                node.put("active", event.active)
                node.put("acked", event.acked)
                node.put("activeTime", event.activeTime.toString())
                event.clearTime?.let { node.put("clearTime", it.toString()) }
                event.ackTime?.let { node.put("ackTime", it.toString()) }
                event.ackUser?.let { node.put("ackUser", it) }
                event.ackNotes?.let { node.put("ackNotes", it) }
                node.set<JsonNode>("eventValue", valueNode(event.eventValue))
                node.put("eventTime", event.eventTime.toString())
                node.put("journaled", event.journaled)
            }
            return mapper.writeValueAsString(root)
        }

        /** Restore from [snapshot]'s document. */
        fun load(root: JsonNode) {
            fun text(node: JsonNode, name: String): String? = node.get(name)?.takeUnless { it.isNull }?.asText()
            fun instant(node: JsonNode, name: String): Instant? = text(node, name)?.let { Instant.parse(it) }
            latched = root.path("latched").asBoolean(false)
            suppressedActive = root.path("suppressedActive").asBoolean(false)
            shelvedUntil = instant(root, "shelvedUntil")
            shelvedAt = instant(root, "shelvedAt")
            shelvedBy = text(root, "shelvedBy")
            val activeId = text(root, "activeEventId")
            for (node in root.path("events")) {
                val event = LiveEvent(
                    id = text(node, "id") ?: continue,
                    source = source,
                    name = text(node, "name") ?: definition.name,
                    displayPath = text(node, "displayPath") ?: "$tagPath/${definition.name}",
                    priority = AlarmPriority.entries.getOrNull(node.path("priority").asInt(-1)) ?: definition.priority,
                    active = node.path("active").asBoolean(false),
                    acked = node.path("acked").asBoolean(false),
                    activeTime = instant(node, "activeTime") ?: clock.instant(),
                    clearTime = instant(node, "clearTime"),
                    ackTime = instant(node, "ackTime"),
                    ackUser = text(node, "ackUser"),
                    ackNotes = text(node, "ackNotes"),
                    eventValue = plainValue(node.get("eventValue")),
                    eventTime = instant(node, "eventTime") ?: clock.instant(),
                )
                event.journaled = node.path("journaled").asBoolean(true)
                live.addLast(event)
                if (event.id == activeId) activeEvent = event
            }
            lastEvent = activeEvent ?: live.lastOrNull()
            // A latched alarm whose active event already left the live set (acknowledged while active and then
            // trimmed) has none; the next evaluation clears or keeps it as usual.
        }

        fun isShelved(now: Instant): Boolean = shelvedUntil != null && now.isBefore(shelvedUntil)

        fun needsPoll(now: Instant): Boolean {
            val shelf = shelvedUntil != null && !now.isBefore(shelvedUntil)
            val delay = deadline != null && !now.isBefore(deadline)
            return shelf || delay
        }

        fun liveState(eventId: String): AlarmLiveState? {
            val event = live.firstOrNull { it.id == eventId } ?: return null
            return AlarmLiveState(
                active = event.active,
                acknowledged = event.acked,
                shelved = isShelved(clock.instant()),
                ackUser = event.ackUser,
                ackTime = event.ackTime,
                clearTime = event.clearTime,
                value = event.eventValue,
                displayPath = event.displayPath,
                priority = event.priority,
            )
        }

        fun step(alarm: AlarmRuntime, config: TagConfig, resolved: ResolvedAlarm, qv: QualifiedValue, now: Instant, notices: MutableList<AlarmNotice>) {
            if (alarm.memory !== this) return
            // [resolved] came from alarm.definition, so evaluate with exactly that one. The caller checked
            // under this lock that the runtime is still installed, so it is the newest definition.
            if (definition !== alarm.definition) definition = alarm.definition
            val shelved = isShelved(now)
            if (!resolved.enabled) {
                // Disabling clears at once, without the off delay, as a system clear.
                if (latched) clear(alarm, resolved, qv, now, shelved, notices, system = true)
                conditionSince = null
                clearSince = null
                deadline = null
                if (shelvedUntil != null && !now.isBefore(shelvedUntil)) {
                    shelvedUntil = null
                    dirty = true
                }
                return
            }
            // Review-3 F3: a value or bound setpoint that is not Good says nothing about the process, so the alarm
            // holds its state (no activation, no clear) and a pending on or off delay starts again once data is Good.
            val trusted = qv.quality.level == Level.GOOD && resolved.setpointsGood
            val raw = trusted && definition.inCondition(qv.value, resolved, config.engLow, config.engHigh, latched = false)
            val held = if (latched) definition.inCondition(qv.value, resolved, config.engLow, config.engHigh, latched = true) else raw
            if (!trusted) {
                conditionSince = null
                clearSince = null
                deadline = null
            } else if (!latched) {
                clearSince = null
                if (raw) {
                    if (conditionSince == null) conditionSince = now
                    val due = conditionSince!!.plus(definition.onDelay)
                    deadline = due
                    if (!now.isBefore(due)) activate(alarm, resolved, qv, now, shelved, notices)
                } else {
                    conditionSince = null
                    deadline = null
                }
            } else if (held) {
                clearSince = null
                deadline = null
            } else {
                if (clearSince == null) clearSince = now
                val due = clearSince!!.plus(definition.offDelay)
                deadline = due
                if (!now.isBefore(due)) clear(alarm, resolved, qv, now, shelved, notices)
            }
            if (shelvedUntil != null && !now.isBefore(shelvedUntil)) {
                dirty = true
                shelvedUntil = null
                shelvedAt = null
                shelvedBy = null
                if (suppressedActive && latched && activeEvent == null) {
                    materialize(alarm, resolved, qv, now, shelved = false, notices)
                    suppressedActive = false
                }
            }
        }

        fun acknowledge(
            alarm: AlarmRuntime,
            event: LiveEvent,
            user: String?,
            notes: String?,
            system: Boolean,
            now: Instant,
            notices: MutableList<AlarmNotice>,
            closing: Boolean = false,
        ) {
            if (event.acked) return
            dirty = true
            event.acked = true
            event.ackTime = now
            event.ackUser = user
            event.ackNotes = notes
            event.eventTime = now
            var flags = if (system) AlarmEventFlags.SYSTEM_ACK else 0
            if (!event.active) flags = flags or AlarmEventFlags.CLEARED
            val shelved = isShelved(now)
            // An event whose active row is in the journal is closed there even while shelved: the row is flagged,
            // not filtered (DIFFERENCES 165; review-3 F7). [closing] is kept for the retire path's callers.
            val forced = shelved && event.journaled
            if (forced) flags = flags or AlarmEventFlags.SHELVED
            appendJournal(entry(event, AlarmEventType.ACKNOWLEDGED, flags, now, shelved = shelved && !forced, extra = ackData(user, notes)))
            notices += notice("ack", event, shelved)
            if (!event.active) {
                live.remove(event)
                if (activeEvent === event) activeEvent = null
            }
        }

        private fun activate(alarm: AlarmRuntime, resolved: ResolvedAlarm, qv: QualifiedValue, now: Instant, shelved: Boolean, notices: MutableList<AlarmNotice>) {
            dirty = true
            latched = true
            clearSince = null
            deadline = null
            if (shelved && !settings.storeShelvedEvents) {
                suppressedActive = true
                return
            }
            materialize(alarm, resolved, qv, now, shelved, notices)
            suppressedActive = false
        }

        private fun materialize(alarm: AlarmRuntime, resolved: ResolvedAlarm, qv: QualifiedValue, now: Instant, shelved: Boolean, notices: MutableList<AlarmNotice>) {
            val whenHappened = eventTime(now, qv)
            val acked = definition.ackMode == AckMode.UNUSED
            dirty = true
            val event = LiveEvent(
                id = UUID.randomUUID().toString(),
                source = source,
                name = definition.name,
                displayPath = resolved.displayPath,
                priority = definition.priority,
                active = true,
                acked = acked,
                activeTime = whenHappened,
                clearTime = null,
                ackTime = if (acked) whenHappened else null,
                ackUser = null,
                ackNotes = null,
                eventValue = qv.value,
                eventTime = whenHappened,
            )
            event.setpointA = resolved.setpointARaw
            event.setpointB = resolved.setpointBRaw
            event.associated = resolved.associated
            live.addLast(event)
            activeEvent = event
            lastEvent = event
            var flags = if (shelved) AlarmEventFlags.SHELVED else 0
            if (acked) flags = flags or AlarmEventFlags.ACKNOWLEDGED
            event.journaled = appendJournal(entry(event, AlarmEventType.ACTIVE, flags, whenHappened, shelved, valueData(qv.value, resolved)))
            notices += notice("active", event, shelved)
            trim(alarm, now, notices)
        }

        private fun clear(
            alarm: AlarmRuntime,
            resolved: ResolvedAlarm,
            qv: QualifiedValue,
            now: Instant,
            shelved: Boolean,
            notices: MutableList<AlarmNotice>,
            system: Boolean = false,
        ) {
            dirty = true
            latched = false
            suppressedActive = false
            conditionSince = null
            clearSince = null
            deadline = null
            val event = activeEvent ?: return
            activeEvent = null
            if (shelved && !settings.storeShelvedEvents && !event.journaled) {
                live.remove(event)
                return
            }
            // Review-3 F7: as in [retire], an event whose active row was journaled before the shelf gets its clear
            // row (flagged Shelved) even with Store Shelved Events off, so the journal closes what it opened.
            val filtered = shelved && !event.journaled
            val whenHappened = eventTime(now, qv)
            event.active = false
            event.clearTime = whenHappened
            event.eventTime = whenHappened
            event.eventValue = qv.value
            var flags = if (shelved) AlarmEventFlags.SHELVED else 0
            if (system) flags = flags or AlarmEventFlags.SYSTEM
            if (event.acked) flags = flags or AlarmEventFlags.ACKNOWLEDGED
            appendJournal(entry(event, AlarmEventType.CLEAR, flags, whenHappened, filtered, valueData(qv.value, resolved)))
            notices += notice("clear", event, shelved)
            if (definition.ackMode == AckMode.AUTO && !event.acked) {
                acknowledge(alarm, event, user = null, notes = null, system = false, now = whenHappened, notices)
            } else if (!event.acked) {
                // Cleared and still unacknowledged: it stays live.
            } else {
                live.remove(event)
            }
        }

        /**
         * The alarm left the config. An active event gets a system clear carrying the last value it saw
         * (the tag may be gone, so nothing is read), then every unacknowledged event gets a system
         * acknowledgement. The live set ends empty. Shelved rows follow Store Shelved Events as usual.
         */
        fun retire(alarm: AlarmRuntime, now: Instant, notices: MutableList<AlarmNotice>) {
            val shelved = isShelved(now)
            latched = false
            suppressedActive = false
            conditionSince = null
            clearSince = null
            deadline = null
            val event = activeEvent
            activeEvent = null
            if (event != null) {
                if (shelved && !settings.storeShelvedEvents && !event.journaled) {
                    live.remove(event)
                } else {
                    event.active = false
                    event.clearTime = now
                    event.eventTime = now
                    var flags = AlarmEventFlags.SYSTEM
                    if (shelved) flags = flags or AlarmEventFlags.SHELVED
                    if (event.acked) flags = flags or AlarmEventFlags.ACKNOWLEDGED
                    // An event whose active row is in the journal is closed there, even while shelved with
                    // Store Shelved Events off (the ordinary shelved clear still writes nothing).
                    val filtered = shelved && !event.journaled
                    appendJournal(entry(event, AlarmEventType.CLEAR, flags, now, filtered, linkedMapOf("eventValue" to event.eventValue)))
                    notices += notice("clear", event, shelved)
                }
            }
            for (pending in live.toList()) {
                if (!pending.acked) acknowledge(alarm, pending, user = null, notes = null, system = true, now = now, notices, closing = true)
            }
            live.clear()
        }

        private fun trim(alarm: AlarmRuntime, now: Instant, notices: MutableList<AlarmNotice>) {
            val limit = settings.liveEventLimit.coerceAtLeast(1)
            while (live.size > limit) {
                val victim = live.firstOrNull { !it.acked } ?: break
                val before = live.size
                acknowledge(alarm, victim, user = null, notes = null, system = true, now = now, notices)
                if (live.size >= before) break
            }
        }

        /**
         * Shelve or unshelve row. Not a shelved event itself, so Store Shelved Events does not apply.
         * It carries the alarm's latest event id, or a new id when the alarm has never been active.
         */
        fun shelfEntry(type: Int, now: Instant, data: Map<String, Any?>, fallbackDisplayPath: String): JournalEntry {
            val event = activeEvent ?: live.lastOrNull() ?: lastEvent
            return JournalEntry(
                eventId = event?.id ?: UUID.randomUUID().toString(),
                source = source,
                displayPath = event?.displayPath ?: fallbackDisplayPath,
                priority = definition.priority,
                eventType = type,
                eventFlags = 0,
                eventTimeMillis = now.toEpochMilli(),
                shelved = false,
                data = data,
            )
        }

        private fun notice(trigger: String, event: LiveEvent, shelved: Boolean): AlarmNotice {
            val state = runtimeState(event.active, event.acked)
            val pipeline = when (trigger) {
                "active" -> definition.activePipeline
                "clear" -> definition.clearPipeline
                else -> definition.ackPipeline
            }
            return AlarmNotice(
                trigger = trigger,
                eventId = event.id,
                source = event.source,
                name = event.name,
                displayPath = event.displayPath,
                priority = event.priority,
                active = event.active,
                acknowledged = event.acked,
                shelved = shelved,
                value = event.eventValue,
                activeTime = event.activeTime,
                clearTime = event.clearTime,
                ackTime = event.ackTime,
                ackUser = event.ackUser,
                pipeline = pipeline,
                emailSubject = definition.emailSubject,
                emailMessage = definition.emailMessage,
                associatedData = event.associated,
                notes = definition.notes,
                notesRequired = definition.ackNotesRequired,
                label = definition.label,
                stateLabel = state.label,
                config = configView(event),
            )
        }

        /** The alarm's configuration for a script's event object: mode, setpoints as the event saw them, delays and routing. */
        private fun configView(event: LiveEvent): Map<String, Any?> = linkedMapOf(
            "name" to definition.name,
            "mode" to definition.mode.name,
            "priority" to definition.priority.label(),
            "priorityCode" to definition.priority.code,
            "ackMode" to definition.ackMode.name,
            "setpointA" to event.setpointA,
            "setpointB" to event.setpointB,
            "deadbandMode" to definition.deadbandMode.name,
            "timeOnDelaySeconds" to definition.onDelay.toMillis() / 1000.0,
            "timeOffDelaySeconds" to definition.offDelay.toMillis() / 1000.0,
            "ackNotesRequired" to definition.ackNotesRequired,
            "shelvingAllowed" to definition.shelvingAllowed,
            "timestampSource" to definition.timestampSource.name,
            "label" to definition.label,
            "notes" to definition.notes,
            "activePipeline" to definition.activePipeline,
            "clearPipeline" to definition.clearPipeline,
            "ackPipeline" to definition.ackPipeline,
        )

        private fun eventTime(now: Instant, qv: QualifiedValue): Instant =
            if (definition.timestampSource == TimestampSource.VALUE) qv.timestamp else now

        private fun entry(
            event: LiveEvent,
            type: Int,
            flags: Int,
            time: Instant,
            shelved: Boolean,
            extra: Map<String, Any?> = emptyMap(),
        ) = JournalEntry(
            eventId = event.id,
            source = event.source,
            displayPath = event.displayPath,
            priority = event.priority,
            eventType = type,
            eventFlags = flags,
            eventTimeMillis = time.toEpochMilli(),
            shelved = shelved,
            data = extra,
        )

        private fun valueData(value: Any?, resolved: ResolvedAlarm): Map<String, Any?> {
            val data = linkedMapOf<String, Any?>(
                "eventValue" to value,
                "setpointA" to resolved.setpointARaw,
                "setpointB" to resolved.setpointBRaw,
            )
            // Associated data rides on the active row as event data, never replacing a built-in key (ALA-039).
            for ((key, v) in resolved.associated) {
                if (key !in data && key != "ackUser" && key != "ackNotes") data[key] = if (v is Number || v is Boolean) v else v.toString()
            }
            return data
        }

        private fun ackData(user: String?, notes: String?): Map<String, Any?> = linkedMapOf(
            "ackUser" to user,
            "ackNotes" to notes,
        )
    }
}

private class LiveEvent(
    val id: String,
    val source: String,
    val name: String,
    val displayPath: String,
    val priority: AlarmPriority,
    var active: Boolean,
    var acked: Boolean,
    val activeTime: Instant,
    var clearTime: Instant?,
    var ackTime: Instant?,
    var ackUser: String?,
    var ackNotes: String?,
    var eventValue: Any?,
    var eventTime: Instant,
) {
    /** True once its active row was queued for the journal. Retiring closes such an event even while shelved. */
    var journaled: Boolean = false

    /** Setpoints and associated data as they read when the event became active. */
    var setpointA: Any? = null
    var setpointB: Any? = null
    var associated: Map<String, Any?> = emptyMap()

    fun view(tagPath: String, resolved: ResolvedAlarm?, notes: String?): AlarmEventView {
        val state = runtimeState(active, acked)
        return AlarmEventView(
            eventId = id,
            source = source,
            name = name,
            displayPath = displayPath,
            tagPath = tagPath,
            priority = priority.label(),
            priorityCode = priority.code,
            state = state.label,
            stateCode = state.code,
            active = active,
            acknowledged = acked,
            value = eventValue,
            activeTime = activeTime,
            clearTime = clearTime,
            ackTime = ackTime,
            ackUser = ackUser,
            ackNotes = ackNotes,
            setpointA = resolved?.setpointARaw,
            setpointB = resolved?.setpointBRaw,
            notes = notes,
            associatedData = associated,
        )
    }
}

/** A shelved alarm for `GET /api/v1/alarms?state=shelved`. [event] is null when the alarm has no live event. */
data class ShelvedAlarmView(
    val source: String,
    val name: String,
    val displayPath: String,
    val tagPath: String,
    val priority: AlarmPriority,
    val shelvedAt: Instant?,
    val shelvedUntil: Instant,
    val shelvedBy: String?,
    val conditionActive: Boolean,
    val event: AlarmEventView?,
)

private val mapper = ObjectMapper()
private val log: System.Logger = System.getLogger(AlarmEngine::class.java.name)

/** An event value as JSON: numbers, text and booleans as themselves, anything else as its text. */
private fun valueNode(value: Any?): JsonNode = try {
    when (value) {
        null, is Number, is String, is Boolean -> mapper.valueToTree(value)
        else -> mapper.valueToTree(value.toString())
    }
} catch (_: IllegalArgumentException) {
    mapper.valueToTree(value.toString())
}

private fun plainValue(node: JsonNode?): Any? = when {
    node == null || node.isNull || node.isMissingNode -> null
    node.isIntegralNumber -> if (node.canConvertToInt()) node.intValue() else node.longValue()
    node.isNumber -> node.doubleValue()
    node.isBoolean -> node.booleanValue()
    else -> node.asText()
}
