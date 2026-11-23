package org.oip.tags

import org.oip.config.Canon
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditLog
import org.oip.security.AuditStatus
import org.oip.tags.expr.EvalContext
import org.oip.tags.expr.Expr
import org.oip.tags.expr.ExpressionException
import org.oip.tags.expr.Expressions
import org.oip.tags.expr.Value
import org.oip.tags.expr.stringify
import org.oip.tags.expr.truth
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * One tag namespace (SPEC §5). Providers are named; `default` is file-config backed and `system` is read-only.
 * Module code can [registerProvider] with its own [TagProvider]. The peer network registers remote providers.
 *
 * Listeners run synchronously on the writer thread, in subscription order, so each tag's events stay ordered.
 * Listeners must not block. A memory [TagSample.set] or a device [TagSample.update] does not hold the engine
 * lock while listeners run, and neither does [write]: it stores under the lock and notifies after releasing it.
 * One thread delivers a tag's changes at a time. A change stored while that tag's listeners are still being
 * called (by another thread, or by a listener writing the same tag) is delivered after the current one, by the
 * thread already delivering, so that write can return before its own listeners have run. See [TagChange].
 */
interface TagProvider {
    val name: String
    val readOnly: Boolean
    fun read(path: TagPath): QualifiedValue
    fun write(path: TagPath, value: Any?): WriteResult
    fun subscribe(path: TagPath, listener: (TagChange) -> Unit): AutoCloseable
    fun browse(path: TagPath): List<BrowseNode>
}

/**
 * A provider whose writes need to know who wrote, such as a remote provider that tells the owning gateway (SEC-003).
 * [TagEngine.write] passes a [TagCaller.User] write here, after its own `tags.write` check, and audits the outcome.
 */
interface CallerTagProvider : TagProvider {
    fun write(path: TagPath, value: Any?, caller: TagCaller): WriteResult
}

/**
 * OPC sample target resolved once. Drivers call [update] on every scan instead of handing the
 * engine a path string to parse. [read] returns the current value without taking the config lock.
 */
interface TagSample {
    /** [sourceEpochMillis] is [NO_SOURCE] when the device did not stamp the sample. */
    fun update(value: Any?, quality: Quality = Quality.GOOD, sourceEpochMillis: Long = NO_SOURCE)

    /** Keeps a device timestamp that is finer than a millisecond. */
    fun updateAt(value: Any?, quality: Quality, source: Instant?)

    /**
     * The device is still there but the value did not change (an OPC UA subscription keep-alive).
     * Moves the silence deadline without allocating a sample or notifying subscribers.
     */
    fun touch() {}

    /**
     * Memory-tag value change resolved once. Does not parse a path and does not take the config lock.
     * [at] is the scan timestamp (shared across a scan). Null stamps gateway receive time.
     * OPC and other non-memory tags reject the call; device samples use [update].
     * Subscribers run on this thread after the tag's own monitor is released.
     */
    fun set(value: Any?, at: Instant? = null): WriteResult = MEMORY_WRITE_NOT_WRITABLE

    fun read(): QualifiedValue

    /**
     * Whether [caller] may read this tag's value (TAG-013): its `readPermissions` allow the caller, or it has
     * none. [TagCaller.System] always may. Reads the tag's current configuration; no lock, no path parse.
     */
    fun mayRead(caller: TagCaller): Boolean = true

    /** [read] as [caller]: a caller [mayRead] refuses gets a null value with `Bad_AccessDenied`, as [TagEngine.read] does. */
    fun read(caller: TagCaller): QualifiedValue = read()

    companion object {
        /** No device timestamp; the engine stamps gateway receive time. */
        const val NO_SOURCE = Long.MIN_VALUE
    }
}

/** Shared results so a memory scan does not allocate a [WriteResult] per tag. */
private val MEMORY_WRITE_OK = WriteResult(Quality.GOOD)
private val MEMORY_WRITE_NOT_WRITABLE = WriteResult(Quality.BAD_NOT_WRITABLE)
private val MEMORY_WRITE_NOT_FOUND = WriteResult(Quality.BAD_NOT_FOUND)
private val MEMORY_WRITE_CYCLE = WriteResult(Quality.ERROR_CYCLE_DETECTED)

/** Kind and datatype of one child, without building a [BrowseNode] or listing its siblings. */
data class ChildMeta(val kind: NodeKind, val dataType: DataType?)

fun interface DeviceWrite {
    /**
     * Queues a device write. Called under the engine lock, so it must return without blocking
     * and without calling back into the engine. The device I/O happens on the caller's own thread.
     *
     * Returns the device's answer (review-2 F5): the future completes with the device quality once the
     * device has accepted or refused the write, after the handler has updated the tag ([TagEngine.updateRaw]
     * on success, [TagEngine.deviceWriteFailed] on failure). Writes to one device item reach the device in
     * the order they were queued. Null: nothing was queued.
     */
    fun enqueue(config: TagConfig, rawValue: Any?): java.util.concurrent.CompletableFuture<Quality>?
}

/**
 * Told when a tag is configured or removed. Invoked under the engine write lock: subscribe is
 * allowed, reading or writing a tag value is not.
 */
fun interface TagConfigWatch {
    fun onTag(provider: String, path: String, config: TagConfig?)
}

class TagEngine(val clock: java.time.Clock = java.time.Clock.systemUTC()) {
    /**
     * Config, browse structure, and writes take the exclusive lock. Value updates do not: each tag
     * is published under its own monitor so scans on different cores do not queue on one gate.
     * The read lock is only for structure (browse, child lookup) that walks the folder maps.
     */
    internal val gate = ReentrantReadWriteLock()
    /**
     * Subscriber notifications raised by the path-based write running on this thread. [StandardTagProvider.write]
     * sets it for the outermost write and runs the queue after the exclusive lock is released, so subscribers
     * (expressions, alarms, history, OPC UA and session feeds) never run while a write holds the engine lock
     * (P2-14). Null outside such a write: scans, [TagSample.set] and config changes notify as before.
     */
    internal val deferredNotify = ThreadLocal<ArrayList<() -> Unit>?>()
    private val structureEpoch = AtomicInteger()

    /** Bumped when a tag is added, removed, or reconfigured. Address-space caches key off this. */
    fun structureEpoch(): Int = structureEpoch.get()

    /**
     * Alarm configuration is tag configuration. The watch runs under the write lock and must not
     * read or write tags; [afterMutation] runs after that lock is released.
     */
    @Volatile var configWatch: TagConfigWatch? = null

    /** Runs after the outermost [withGate] releases the write lock. Null on the value-scan path. */
    @Volatile var afterMutation: (() -> Unit)? = null

    fun holdingWriteLock(): Boolean = gate.writeLock().isHeldByCurrentThread

    /**
     * Extra configuration listeners (query tags, TAG-030). [configWatch] stays the single alarm slot.
     * Empty on the value path: [notifyConfig] returns before iterating, and configure is not the sample hot path.
     */
    private val configListeners = CopyOnWriteArrayList<TagConfigWatch>()

    /**
     * Told when any tag is configured ([config] non-null) or removed (null), under the engine write lock.
     * Do not read or write tag values from [listener]. Tags that already exist are not replayed; call
     * [copyTagConfigs] after registering. The engine does not evaluate a [TagType.QUERY] tag: the listener's
     * owner publishes with [publishQuery].
     */
    fun addConfigListener(listener: TagConfigWatch): AutoCloseable {
        configListeners.add(listener)
        return AutoCloseable { configListeners.remove(listener) }
    }

    internal fun notifyConfig(provider: String, path: String, config: TagConfig?) {
        val watch = configWatch
        if (watch != null) {
            try {
                watch.onTag(provider, path, config)
            } catch (_: RuntimeException) {
                // A watcher must not fail tag configuration.
            }
        }
        if (configListeners.isEmpty()) return
        for (listener in configListeners) {
            try {
                listener.onTag(provider, path, config)
            } catch (_: RuntimeException) {
                // A listener must not fail tag configuration.
            }
        }
    }

    /** Configured tags, for a watcher attached after they were created. Does not include definitions. */
    fun copyTagConfigs(): List<Pair<String, TagConfig>> {
        val out = ArrayList<Pair<String, TagConfig>>()
        for (p in providers.values) {
            if (p is StandardTagProvider) p.copyConfigs(out)
        }
        return out
    }

    /** UDT parameters resolved onto a tag, empty for a plain tag. */
    fun parametersOf(path: String): Map<String, Any?> {
        val parsed = try {
            parseUser(path)
        } catch (_: PathException) {
            return emptyMap()
        }
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider ?: return emptyMap()
        return p.parametersOf(parsed)
    }

    /**
     * True when [path] is a member generated by a UDT instance. Such tags have no config resource of their own,
     * so the driver bridge binds them from [addConfigListener] events instead of from the config store.
     */
    fun isGeneratedMember(path: String): Boolean {
        val parsed = try {
            parseUser(path)
        } catch (_: PathException) {
            return false
        }
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider ?: return false
        return p.isGenerated(parsed)
    }

    internal fun bumpStructure() {
        structureEpoch.incrementAndGet()
    }
    /** OPC tag writes. Null keeps the in-memory pending value and does not talk to a driver. */
    @Volatile var deviceWrite: DeviceWrite? = null
    /**
     * How long a [TagCaller.User] write waits for the device to answer (review-2 F5). A device that has not
     * answered by then gives the writer `Bad_Timeout`; the write itself may still land, and the tag then shows
     * what the device reports.
     */
    @Volatile var deviceWriteTimeoutMillis: Long = DEFAULT_DEVICE_WRITE_TIMEOUT_MS
    /** Device writes queued by the [write] call running on this thread; it waits for them after the lock is released. */
    internal val pendingDeviceWrites = ThreadLocal<ArrayList<java.util.concurrent.CompletableFuture<Quality>>?>()
    /**
     * Called on every subscribe and unsubscribe with the tag's [subscriptionKey] and its new subscriber
     * count, while the engine lock is held, so it must not block or call back into the engine.
     * [TagGroupSchedule.observe] uses it for leased tag groups (TAG-025).
     */
    @Volatile var subscriptionCountListener: ((key: String, count: Int) -> Unit)? = null
    val groups = TagGroupRegistry()
    /** Tag-group execution counters for diagnostics (TAG-003). Not touched on the publish path. */
    val groupActivity = TagGroupActivity()
    /** Where [TagCaller.User] writes are recorded (TAG-013). Null: they are still checked, not recorded. */
    @Volatile var audit: AuditLog? = null
    /**
     * Whether [audit] records user writes right now. The gateway ties this to its audit profile's `enabled`
     * flag, the same switch its other audit records follow. Checked once per user write, after the lock.
     */
    @Volatile var auditEnabled: () -> Boolean = { true }
    private val auditFailures = AtomicLong()
    internal val storedChanges = java.util.concurrent.atomic.LongAdder()

    /** Value, quality or timestamp changes stored since start, whether or not anything listened. Dashboards take a rate from it. */
    fun changesStored(): Long = storedChanges.sum()

    /** Audit appends that threw. The write itself had already been applied or rejected. */
    fun auditFailures(): Long = auditFailures.get()

    /** Memory-tag persistence (TAG-016), set by [MemoryTagPersistence.open]. Null: values are not kept. */
    @Volatile internal var memorySink: MemorySink? = null

    /** Current state of the memory tags behind [handles]; removed tags are left out. Shared lock only. */
    internal fun snapshotMemory(handles: Collection<Any>): List<MemorySnapshot> =
        withShared { handles.mapNotNull { defaultProvider.snapshotOf(it) } }
    private val providers = LinkedHashMap<String, TagProvider>()
    val defaultProvider: StandardTagProvider
    val systemProvider: StandardTagProvider

    init {
        defaultProvider = StandardTagProvider(DEFAULT_PROVIDER, this, readOnly = false)
        systemProvider = StandardTagProvider(SYSTEM_PROVIDER, this, readOnly = true)
        providers[DEFAULT_PROVIDER] = defaultProvider
        providers[SYSTEM_PROVIDER] = systemProvider
        systemProvider.publish("Gateway/Name", "open-industrial-platform", DataType.STRING)
        systemProvider.publish("Gateway/Started", clock.instant(), DataType.DATETIME)
    }

    fun registerProvider(provider: TagProvider) {
        val key = provider.name.lowercase()
        require(key !in providers) { "provider '${provider.name}' is already registered" }
        providers[key] = provider
    }

    fun provider(name: String): TagProvider? = providers[name.lowercase()]
    fun providers(): List<TagProvider> = providers.values.toList()

    fun configure(config: TagConfig, origin: String? = null, originKind: String? = null) {
        val p = provider(config.provider) as? StandardTagProvider
            ?: throw TagConfigException("provider '${config.provider}' is not configurable")
        p.configure(config, originKind, origin)
    }

    fun configureUdt(definition: UdtDefinition, origin: String? = null, originKind: String? = null) {
        val p = provider(definition.provider) as? StandardTagProvider
            ?: throw TagConfigException("provider '${definition.provider}' is not configurable")
        p.configureUdt(definition, originKind, origin)
    }

    /** Add or replace a tag group (TAG-006). Tags that were waiting on this name clear their configuration error. */
    fun configureGroup(def: TagGroupDef) {
        groups.put(def)
        standardProviders().forEach { it.regroup(def.name) }
    }

    /**
     * Remove a tag group. Tags still naming it show [QualityCode.ERROR_CONFIGURATION] (the same as a
     * group that never existed) until it is defined again, and [TagGroupRegistry.resolve] schedules
     * their items at the default group's rate meanwhile. [DEFAULT_GROUP] cannot be removed.
     */
    fun removeGroup(name: String) {
        if (name == DEFAULT_GROUP) return
        groups.remove(name)
        standardProviders().forEach { it.regroup(name) }
    }

    private fun standardProviders() = providers.values.filterIsInstance<StandardTagProvider>()

    /**
     * Timed expression tags (TAG-029): re-evaluate every expression tag in [ExecutionMode.FIXED_RATE]
     * or [ExecutionMode.TAG_GROUP] whose period has elapsed, even with no dependency change. Tags in
     * the default [ExecutionMode.EVENT_DRIVEN] are not touched. No sleeping and no per-tag thread: call
     * this on your own timer (a real one, or a fake clock in a test).
     */
    fun tickExpressionGroups(now: Instant = clock.instant()) = standardProviders().forEach { it.tickExpressionGroups(groups, now) }

    fun remove(path: String) {
        val parsed = parseUser(path)
        (provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider)?.remove(parsed)
    }

    /**
     * Values are cached; this does not perform device I/O. Each path takes the shared lock on its own, so a
     * large batch never holds the engine lock for longer than one lookup (P2-14) and does not block writers
     * or other readers for the whole batch.
     */
    fun readAll(paths: List<String>, caller: TagCaller = TagCaller.System): List<QualifiedValue> {
        if (paths.isEmpty()) return emptyList()
        return paths.map { read(it, caller) }
    }

    /**
     * One tag's diagnostics (TAG-003). Null when the path is not a live tag. A [TagCaller.User] who may not
     * read the tag gets a null value, quality `Bad_AccessDenied`, and no references.
     */
    fun diagnostics(path: String, caller: TagCaller = TagCaller.System): TagDiagnostics? {
        val parsed = parseUser(path)
        val provider = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider ?: return null
        val raw = provider.diagnostics(parsed) ?: return null
        if (mayRead(path, caller)) return raw
        return raw.copy(
            value = null,
            quality = QualityCode.BAD_USER_ACCESS_DENIED.label,
            timestamp = clock.instant(),
            sourceTimestamp = null,
            message = null,
            referencesIn = emptyList(),
            referencesOut = emptyList(),
        )
    }

    /**
     * Roles named by the tag's read permissions, or null when the tag does not restrict readers.
     * Remote tag reads use this when the property is present.
     */
    fun readPermissionRoles(path: String): Set<String>? {
        val parsed = try {
            parseUser(path)
        } catch (_: PathException) {
            return null
        }
        val standard = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider ?: return null
        return standard.readPermissionRoles(parsed)
    }

    /** Read permission of a standard-provider tag, or null when the tag does not restrict readers. */
    fun readAccessOf(path: String): TagAccess? = accessOf(path) { readAccess(it) }

    /** Write permission of a standard-provider tag, or null when the tag does not restrict writers. */
    fun writeAccessOf(path: String): TagAccess? = accessOf(path) { writeAccess(it) }

    private fun accessOf(path: String, load: StandardTagProvider.(TagPath) -> TagAccess?): TagAccess? {
        val parsed = try {
            parseUser(path)
        } catch (_: PathException) {
            return null
        }
        val standard = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider ?: return null
        return standard.load(parsed)
    }

    /**
     * Whether [caller] may read [path]'s value, history and alarms (TAG-013): the one check behind [read],
     * [browse], the history and alarm queries and the OPC UA server. True for [TagCaller.System], for a tag
     * without read permissions, and for registered providers other than the standard one (remote and system
     * providers), which [read] does not check either. A [TagCaller.User] is refused a path that does not
     * parse, and a path with no configured tag or naming no registered provider (history or journal rows of a
     * deleted or renamed tag) unless it is [TagCaller.User.configAdmin]: fail closed, so removing a restricted
     * tag does not open its history to everyone. Under the engine lock this only looks the tag up and reads
     * its permission field; the decision ([readAdmits]) runs after the lock is released.
     */
    fun mayRead(path: String, caller: TagCaller): Boolean {
        if (caller !is TagCaller.User) return true
        val parsed = try {
            parseUser(path)
        } catch (_: PathException) {
            return false
        }
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER) ?: return caller.configAdmin
        val standard = p as? StandardTagProvider ?: return true
        return standard.mayRead(parsed, caller)
    }

    /**
     * Whether [caller] passes [path]'s write permissions (TAG-013), the check a user write meets after the
     * read-only flag: [TagCaller.User.tagWrite] and the tag's `writePermissions` with the caller's roles and zone.
     * It does not look at the read-only flag, so an alarm on a read-only device tag can still be acknowledged.
     * The same fail-closed rule as [mayRead] applies to a path with no configured tag. Alarm acknowledge and shelve
     * use it with [mayRead] (review-3 F2).
     */
    fun mayWrite(path: String, caller: TagCaller): Boolean {
        if (caller !is TagCaller.User) return true
        if (!caller.tagWrite) return false
        val parsed = try {
            parseUser(path)
        } catch (_: PathException) {
            return false
        }
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER) ?: return caller.configAdmin
        val standard = p as? StandardTagProvider ?: return true
        return standard.mayWrite(parsed, caller)
    }

    /**
     * Whether [path] names a configured tag. Unlike a read that comes back `Bad_NotFound`, this does not
     * confuse a missing tag with a configured one whose source reports that quality.
     */
    fun isConfigured(path: String): Boolean {
        val parsed = try {
            parseUser(path)
        } catch (_: PathException) {
            return false
        }
        val standard = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider ?: return false
        return standard.isConfigured(parsed)
    }

    /**
     * A [TagCaller.User] without read permission on the tag gets a null value with
     * [QualityCode.BAD_USER_ACCESS_DENIED] (`Bad_AccessDenied`) instead of the value (TAG-013).
     */
    fun read(path: String, caller: TagCaller = TagCaller.System): QualifiedValue {
        val parsed = try {
            parseUser(path)
        } catch (e: PathException) {
            return bad(Quality(QualityCode.ERROR_INVALID_PATH, e.message))
        }
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER)
            ?: return bad(Quality(QualityCode.BAD_NOT_FOUND, "provider '${parsed.provider}' not found"))
        if (caller is TagCaller.User && p is StandardTagProvider) return p.read(parsed, caller)
        return p.read(parsed)
    }

    /**
     * Write [value] as [caller] (TAG-013). A read-only tag (`readOnly` / `writable: false`) rejects
     * every caller, administrators and [TagCaller.System] included, with `Bad_NotWritable`. A
     * [TagCaller.User] outside the tag's `writePermissions` gets `Bad_AccessDenied`. Either way the
     * value is unchanged. A reference or derived tag writes through to its source, and the source's
     * read-only flag and write permissions apply to that write as well, so the user needs both. A
     * [TagCaller.User] may not write a tag property such as `sourceTagPath` (`Bad_NotWritable`). A
     * [TagCaller.User] without [TagCaller.User.tagWrite] is refused every write with `Bad_UserAccessDenied`.
     *
     * Each [TagCaller.User] write, accepted or rejected, appends exactly one [audit] record (actor,
     * time, tag path, old and new value or the rejection), after the engine lock is released.
     * [TagCaller.System] writes are not audited.
     */
    fun write(path: String, value: Any?, caller: TagCaller = TagCaller.System): WriteResult {
        val parsed = try {
            parseUser(path)
        } catch (e: PathException) {
            return audited(caller, path, value, null, WriteResult(Quality(QualityCode.ERROR_INVALID_PATH, e.message)))
        }
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER)
            ?: return audited(caller, path, value, null, WriteResult(Quality(QualityCode.BAD_NOT_FOUND, "provider '${parsed.provider}' not found")))
        if (caller !is TagCaller.User) return p.write(parsed, value)
        if (!caller.tagWrite) {
            return audited(caller, parsed.toString(), value, null,
                WriteResult(Quality(QualityCode.BAD_USER_ACCESS_DENIED, "tags.write denied")))
        }
        val before = p.read(parsed)
        val outer = pendingDeviceWrites.get()
        val pending = ArrayList<java.util.concurrent.CompletableFuture<Quality>>(1)
        pendingDeviceWrites.set(pending)
        val queued = try {
            when (p) {
                is StandardTagProvider -> p.write(parsed, value, caller)
                is CallerTagProvider -> p.write(parsed, value, caller)
                else -> p.write(parsed, value)
            }
        } finally {
            if (outer == null) pendingDeviceWrites.remove() else pendingDeviceWrites.set(outer)
        }
        val result = if (queued.ok && pending.isNotEmpty()) awaitDevice(pending, queued) else queued
        return audited(caller, parsed.toString(), value, before, result) { p.read(parsed) }
    }

    /**
     * The device's answer to the writes [write] queued (review-2 F5), so a user is told, and the audit log records,
     * what the device did rather than that the write was queued. Waits at most [deviceWriteTimeoutMillis] in all.
     * Never waits while this thread holds the engine lock (the device handler needs it to publish the outcome); a
     * write made under the lock reports what was queued, as before.
     */
    private fun awaitDevice(pending: List<java.util.concurrent.CompletableFuture<Quality>>, queued: WriteResult): WriteResult {
        if (gate.isWriteLockedByCurrentThread || gate.readHoldCount > 0) return queued
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deviceWriteTimeoutMillis)
        var worst: Quality = Quality.GOOD
        for (future in pending) {
            val quality = try {
                future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                Quality(QualityCode.BAD_TIMEOUT, "device did not answer within $deviceWriteTimeoutMillis ms")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                Quality(QualityCode.BAD_TIMEOUT, "interrupted waiting for the device")
            } catch (e: java.util.concurrent.ExecutionException) {
                Quality(QualityCode.BAD_UNEXPECTED_ERROR, e.cause?.message ?: "device write failed")
            }
            if (quality.level != Level.GOOD && worst.level == Level.GOOD) worst = quality
        }
        return if (worst.level == Level.GOOD) queued else WriteResult(worst)
    }

    fun subscribe(path: String, listener: (TagChange) -> Unit): AutoCloseable = subscribe(path, TagCaller.System, listener)

    /**
     * Like [subscribe], but a [TagCaller.User] without read permission never receives the value: on
     * the first change while it is denied it gets one change carrying `Bad_AccessDenied` and a null
     * value, then nothing until it may read again. Permission is checked per change, so a config edit
     * that grants or revokes read access takes effect on the live subscription.
     */
    fun subscribe(path: String, caller: TagCaller, listener: (TagChange) -> Unit): AutoCloseable {
        val parsed = parseUser(path)
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER)
            ?: throw TagConfigException("provider '${parsed.provider}' not found")
        if (caller is TagCaller.User && p is StandardTagProvider) return p.subscribe(parsed, caller, listener)
        return p.subscribe(parsed, listener)
    }

    private fun audited(
        caller: TagCaller,
        target: String,
        attempted: Any?,
        before: QualifiedValue?,
        result: WriteResult,
        after: () -> QualifiedValue? = { null },
    ): WriteResult {
        if (caller !is TagCaller.User) return result
        val log = audit ?: return result
        if (!runCatching { auditEnabled() }.getOrDefault(true)) return result
        val ok = result.quality.level == Level.GOOD
        val detail = if (ok) "old=${render(before?.value)} new=${render(after()?.value)} zone=${caller.zone}"
            else "rejected ${result.quality.code.label}: value=${render(attempted)} zone=${caller.zone}"
        val origin = AuditContext.current()
        try {
            log.append(AuditDraft(
                timestampMillis = clock.millis(),
                actor = caller.actor,
                action = TAG_WRITE_ACTION,
                status = if (ok) AuditStatus.SUCCESS else AuditStatus.FAILURE,
                actorHost = caller.host.ifEmpty { origin?.host ?: "" },
                target = target,
                detail = detail,
                context = origin?.context ?: "",
            ))
        } catch (e: RuntimeException) {
            auditFailures.incrementAndGet()
        }
        return result
    }

    private fun render(v: Any?): String = v?.toString()?.let { if (it.length > 200) it.take(200) + "..." else it } ?: "null"

    /**
     * Children of [path]. [offset] is the child index in link order and [limit] caps the page,
     * so a folder of hundreds of thousands of tags is not materialized in one call.
     */
    fun browse(path: String = "[$DEFAULT_PROVIDER]", offset: Int = 0, limit: Int = Int.MAX_VALUE): List<BrowseNode> =
        browse(path, offset, limit, TagCaller.System)

    /**
     * [browse] as [caller] (TAG-013). Names, paths and kinds are listed for every caller. A tag whose
     * `readPermissions` deny [caller] ([mayRead]) is listed with [BrowseNode.readable] false, no data type and
     * no resource, and a null value with `Bad_AccessDenied`, the same as [read]. Providers other than
     * [StandardTagProvider] are listed as is.
     */
    fun browse(path: String, offset: Int, limit: Int, caller: TagCaller): List<BrowseNode> {
        val parsed = parseUser(path.ifEmpty { "[$DEFAULT_PROVIDER]" })
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER) ?: return emptyList()
        val standard = p as? StandardTagProvider ?: return if (offset == 0) p.browse(parsed) else emptyList()
        return standard.browse(parsed, offset, limit, caller)
    }

    /** Direct child lookup. Does not list or cache the rest of the parent folder. */
    fun childMeta(providerName: String, parentPath: String, name: String): ChildMeta? =
        (provider(providerName) as? StandardTagProvider)?.childMeta(parentPath, name)

    /**
     * Resolve [path] once. Later samples call [TagSample.update] and do not parse the path or take
     * the config lock.
     */
    fun openSample(path: String): TagSample {
        val parsed = parseUser(path)
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider
            ?: throw TagConfigException("provider '${parsed.provider ?: DEFAULT_PROVIDER}' is not a standard provider")
        return p.openSample(parsed)
    }

    /** The key [subscriptionCountListener] reports for [path] (TAG-025: leased tag groups key off this). */
    fun subscriptionKey(path: String): String {
        val parsed = parseUser(path)
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider
            ?: throw TagConfigException("provider '${parsed.provider ?: DEFAULT_PROVIDER}' is not a standard provider")
        return p.sampleKey(parsed)
    }

    /**
     * A device write to the OPC tag at [path] failed with [quality] (review-2 F5). The tag goes back to the last
     * value read from the device (the value it held before the write, or a newer device sample) with the
     * device's quality, so it never shows a value the device did not take.
     */
    fun deviceWriteFailed(path: String, quality: Quality) {
        val parsed = parseUser(path)
        (provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider)?.deviceWriteFailed(parsed, quality)
    }

    /**
     * The rate a driver now reads the OPC tag at [path] (TAG-006, review-2 F3): its tag group's current rate. The
     * comms-loss deadline uses it instead of `scanPeriodMillis`; 0 means the item is not read (a leased group with no
     * subscriber and no slow rate), so silence is not comms loss. Null goes back to `scanPeriodMillis`. False when
     * no such tag is configured (yet).
     */
    fun setDeviceScanRate(path: String, rateMillis: Long?): Boolean {
        val parsed = parseUser(path)
        val p = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider ?: return false
        return p.setDeviceScanRate(parsed, rateMillis)
    }

    /**
     * Store a value computed outside the engine for the query tag at [path] (TAG-030). Not a user write:
     * query tags stay read-only. A [quality] that is not Good keeps the last value. No-op when [path] is
     * missing or is not a query tag. Subscribers run after the tag monitor is released, without the config lock.
     */
    fun publishQuery(path: String, value: Any?, quality: Quality) {
        val parsed = parseUser(path)
        val provider = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider ?: return
        provider.publishQuery(parsed, value, quality)
    }

    /** Device sample for an OPC tag. Scaling and deadband run here. No protocol driver is included. */
    fun updateRaw(path: String, value: Any?, quality: Quality = Quality.GOOD, sourceTimestamp: Instant? = null) {
        val parsed = parseUser(path)
        val provider = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider ?: return
        val epoch = sourceTimestamp?.toEpochMilli() ?: TagSample.NO_SOURCE
        provider.updateByKey(provider.sampleKey(parsed), value, quality, epoch, sourceTimestamp != null, sourceTimestamp)
    }

    /**
     * Store a value and quality on a memory tag. This is not a user write: the PLC bridge uses it
     * so a lost engine can leave the safe value with bad quality. A good publish still refuses a
     * tag that is not writable. A bad publish is the safe-state override and is stored anyway.
     */
    fun publishMemory(path: String, value: Any?, quality: Quality): WriteResult {
        val parsed = parseUser(path)
        val provider = provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider
            ?: return WriteResult(Quality.BAD_NOT_FOUND)
        return provider.publishMemory(parsed, value, quality)
    }

    fun reportCommsLoss(path: String, lost: Boolean = true) {
        val parsed = parseUser(path)
        (provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider)?.reportCommsLoss(parsed, lost)
    }

    /** Apply comms-loss when a device tag has been silent for scanPeriod + timeout (SPEC §5). */
    fun checkComms(now: Instant = clock.instant()) {
        providers.values.filterIsInstance<StandardTagProvider>().forEach { it.checkComms(now) }
    }

    /**
     * Earliest engine-clock millis at which an OPC tag that still has a sample becomes comms loss.
     * [Long.MAX_VALUE] when nothing is waiting. The scan reads primitives only.
     */
    fun earliestCommsDeadlineMillis(): Long {
        var earliest = Long.MAX_VALUE
        for (provider in providers.values) {
            val due = (provider as? StandardTagProvider)?.earliestCommsDeadlineMillis() ?: continue
            if (due < earliest) earliest = due
        }
        return earliest
    }

    /**
     * Configure a gateway-owned tag in the read-only `[system]` provider, for example a reference tag that carries an
     * alarm on a published system value (EAM-017). Users still cannot write it or configure tags there.
     */
    fun configureSystem(config: TagConfig, origin: String, originKind: String) {
        require(config.provider.equals(SYSTEM_PROVIDER, ignoreCase = true)) { "configureSystem takes [system] tags only" }
        require(config.type != TagType.UDT) { "configureSystem does not take UDT instances" }
        systemProvider.configureOwned(config, originKind, origin)
    }

    /** Alarm configuration of a `[system]` tag (ALA-040). Null or empty removes it. */
    fun setSystemAlarms(path: String, alarms: List<Map<String, Any?>>?) = systemProvider.setAlarms(path, alarms)

    fun systemAlarms(path: String): List<Map<String, Any?>> = systemProvider.alarmsOf(path)

    fun publishSystem(path: String, value: Any?, dataType: DataType = DataType.DOUBLE) {
        systemProvider.publish(path, value, dataType)
    }

    fun lastRawWrite(path: String): Any? {
        val parsed = parseUser(path)
        return (provider(parsed.provider ?: DEFAULT_PROVIDER) as? StandardTagProvider)?.lastRawWrite(parsed)
    }

    internal fun <T> withGate(body: () -> T): T {
        val lock = gate.writeLock()
        val outer = lock.holdCount == 0
        lock.lock()
        try {
            return body()
        } finally {
            lock.unlock()
            if (outer) afterMutation?.invoke()
        }
    }

    internal fun <T> withShared(body: () -> T): T {
        val lock = gate.readLock()
        lock.lock()
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }

    private fun bad(q: Quality) = QualifiedValue(null, q, clock.nowMicros())

    internal fun parseUser(text: String): TagPath {
        val p = TagPath.parse(text, DEFAULT_PROVIDER)
        if (p.relative != null || p.segments.firstOrNull() == "." || p.segments.firstOrNull() == "..")
            throw PathException("relative path '$text' needs a base tag")
        return p.resolve(null)
    }
}

class StandardTagProvider(
    rawName: String,
    private val engine: TagEngine,
    override val readOnly: Boolean,
) : TagProvider {
    override val name: String = Canon.share(rawName)

    /** Folder trie. A leaf's [Dir.kids] stays null, so a tag does not allocate its own child map. */
    private val root = Dir(name, null)
    private val live = ArrayList<Node>()
    private val definitions = HashMap<Dir, UdtDefinition>()
    private val definitionOrigin = HashMap<Dir, Pair<String, String>>()
    /** Subscribers are rare. Keyed by the folder node so a scan does not build a path string. */
    private val watchers = HashMap<Dir, CopyOnWriteArrayList<Sub>>()
    /**
     * Expression, derived and reference tags that read a tag, keyed by the read tag's base canonical
     * key. Mutated only under the engine write lock (rebind and remove). Not the publish path.
     */
    private val readersOf = HashMap<String, ArrayList<Node>>()
    /** Same-thread memory writes. Listeners run inline, so a write that re-enters the same tag is a cycle. */
    private val memoryWriteStack = ThreadLocal.withInitial { ArrayList<Node>(4) }
    /** Subscribe-before-create, keyed by canonical path. Empty on the device hot path. */
    private val earlyWatchers = HashMap<String, CopyOnWriteArrayList<Sub>>()
    /**
     * Expressions evaluate on whichever thread delivered the dependency change (a scan thread, a
     * writer, or the [TagEngine.tickExpressionGroups] ticker), so the evaluation chain used for cycle
     * detection belongs to that thread, not to the provider.
     */
    private class EvalState {
        val chain = ArrayDeque<Node>()
        var cycleLatch = false
    }
    private val evalState = ThreadLocal.withInitial { EvalState() }
    private val evalChain: ArrayDeque<Node> get() = evalState.get().chain
    private val writeStack = ArrayDeque<String>()
    /**
     * Tags naming a tag group that does not exist (quality ERROR_CONFIGURATION), and the last timed
     * run of FIXED_RATE / TAG_GROUP expression tags. Both are rare, so they live here rather than as
     * fields on every [Node]. [groupMissing] is written under the exclusive lock and read without
     * it by the device sample path (only when it is non-empty); [timedLastRun] is written by the
     * ticker under the shared lock. Both are concurrent sets/maps for that reason.
     */
    private val groupMissing: MutableSet<Node> = ConcurrentHashMap.newKeySet()
    private val timedLastRun = ConcurrentHashMap<Node, Long>()

    /**
     * One tick at a time per provider. The gateway ticker and an explicit caller can tick together; a
     * tick claims due tags through [timedLastRun] before it records group activity, so without this a
     * tick that found nothing due could return while another tick still held the executions it claimed.
     */
    private val tickLock = Any()

    /** Cheap guard for the hot path: normal tags never look the node up. */
    private fun inMissingGroup(node: Node): Boolean = !groupMissing.isEmpty() && groupMissing.contains(node)

    fun configure(config: TagConfig, originKind: String?, originName: String?) {
        if (readOnly) throw TagConfigException("provider '$name' is read-only")
        configureOwned(config, originKind, originName)
    }

    /** [configure] for a tag the gateway itself owns in a read-only provider. Writes to it stay refused. */
    internal fun configureOwned(config: TagConfig, originKind: String?, originName: String?) = engine.withGate {
        val prepared = normalize(config)
        if (prepared.type == TagType.UDT) configureInstance(prepared, originKind, originName)
        else configureLeaf(prepared, originKind, originName, generated = false, params = emptyMap(), owner = null)
        engine.bumpStructure()
    }

    fun configureUdt(definition: UdtDefinition, originKind: String?, originName: String?) = engine.withGate {
        if (readOnly) throw TagConfigException("provider '$name' is read-only")
        val path = TagPath.parse(definition.path, name).resolve(null)
        val dir = locate(path, create = true) ?: throw TagConfigException("tag path is empty")
        val stored = definition.copy(provider = name, path = display(path))
        definitions[dir] = stored
        if (originName != null) definitionOrigin[dir] = Canon.share(originKind ?: "") to Canon.share(originName)
        reexpandDependents(stored.path)
        engine.bumpStructure()
    }

    fun remove(path: TagPath) = engine.withGate {
        val node = nodeAt(path) ?: return@withGate
        removeNode(node, notify = true, forget = true)
        engine.bumpStructure()
    }

    fun removeOrigin(kind: String, resourceName: String) = engine.withGate {
        val kindS = Canon.share(kind)
        val nameS = Canon.share(resourceName)
        live.filter { it.originKind == kindS && it.originName == nameS }.toList().forEach { removeNode(it, notify = true, forget = true) }
        definitionOrigin.filterValues { it.first == kindS && it.second == nameS }.keys.toList().forEach { dir ->
            val def = definitions.remove(dir)
            definitionOrigin.remove(dir)
            unlink(dir)
            reexpandDependents(def?.path ?: return@forEach)
        }
        engine.bumpStructure()
    }

    override fun read(path: TagPath): QualifiedValue = engine.withShared { readLocked(path) }

    /** See [TagEngine.read]. */
    fun read(path: TagPath, caller: TagCaller): QualifiedValue {
        // Under the lock: the lookup, the permission field and the cached value. The decision runs after it.
        var configured = false
        var access: TagAccess? = null
        val qv = engine.withShared {
            val node = nodeAt(path)
            configured = node != null
            access = node?.config?.readAccess
            readLocked(path)
        }
        return if (configured && !readAdmits(access, caller)) accessDenied() else qv
    }

    /** See [TagEngine.subscribe] with a caller. */
    fun subscribe(path: TagPath, caller: TagCaller, listener: (TagChange) -> Unit): AutoCloseable {
        val hidden = AtomicBoolean(false)
        return subscribe(path) { change ->
            if (subscriptionMayRead(path, caller)) {
                hidden.set(false)
                listener(change)
            } else if (hidden.compareAndSet(false, true)) {
                listener(TagChange(change.path, accessDenied(), null))
            }
        }
    }

    /**
     * A live subscription's per-change check. Unlike [mayRead], a path with no tag passes, so a subscriber sees
     * the tag's own `Bad_NotFound` change when it is removed rather than an access denial.
     */
    private fun subscriptionMayRead(path: TagPath, caller: TagCaller): Boolean {
        if (caller !is TagCaller.User) return true
        return readAdmits(engine.withShared { nodeAt(path)?.config?.readAccess }, caller)
    }

    /**
     * See [TagEngine.mayRead]: a path with no live tag is refused unless the caller is a config admin. Only the
     * lookup and the permission field read hold the shared lock.
     */
    fun mayRead(path: TagPath, caller: TagCaller): Boolean {
        if (caller !is TagCaller.User) return true
        var configured = false
        val access = engine.withShared {
            val node = nodeAt(path)?.takeIf { it.alive }
            configured = node != null
            node?.config?.readAccess
        }
        return if (configured) readAdmits(access, caller) else caller.configAdmin
    }

    fun mayWrite(path: TagPath, caller: TagCaller): Boolean {
        if (caller !is TagCaller.User) return true
        var configured = false
        val access = engine.withShared {
            val node = nodeAt(path)?.takeIf { it.alive }
            configured = node != null
            node?.config?.writeAccess
        }
        return if (configured) access?.allows(caller) != false else caller.configAdmin
    }

    /**
     * Diagnostics snapshot (TAG-003). Null when [path] is not a live tag. Holds the shared lock only
     * long enough to copy fields; the deadband counters are read under the tag's own monitor.
     */
    fun diagnostics(path: TagPath): TagDiagnostics? = engine.withShared {
        val node = nodeAt(basePath(path))?.takeIf { it.alive } ?: return@withShared null
        val subscribers = watchers[node.dir]?.size ?: earlyWatchers[node.path.canonicalKey()]?.size ?: 0
        val evaluated: Long
        val published: Long
        val qv: QualifiedValue
        synchronized(node) {
            qv = node.qv
            evaluated = node.deadbandEvaluated
            published = node.deadbandPublished
        }
        val groupName = node.config.tagGroup
        val defined = engine.groups.get(groupName)
        val resolved = defined ?: engine.groups.resolve(groupName)
        val activity = engine.groupActivity.snapshot(groupName)
        val self = node.path.canonicalKey()
        val inbound = ArrayList<TagLink>()
        for (reader in readersOf[self] ?: emptyList()) {
            if (!reader.alive || reader === node) continue
            inbound.add(TagLink(reader.path.toString(), linkKind(reader.config.type)))
        }
        inbound.sortBy { it.path }
        val outbound = ArrayList<TagLink>(node.reads.size)
        for (ref in node.reads) {
            val target = nodeAt(basePath(ref))
            val kind = if (target != null && target.alive) linkKind(target.config.type) else "tag"
            outbound.add(TagLink(ref.toString(), kind))
        }
        outbound.sortBy { it.path }
        val executed = activity != null && activity.lastExecutionMillis != Long.MIN_VALUE
        TagDiagnostics(
            path = node.path.toString(),
            type = linkKind(node.config.type),
            value = qv.value,
            quality = qv.quality.code.label,
            timestamp = qv.timestamp,
            sourceTimestamp = qv.sourceTimestamp,
            message = qv.quality.diagnostic,
            subscriberCount = subscribers,
            referencesIn = inbound,
            referencesOut = outbound,
            tagGroup = TagGroupDiagnostics(
                name = groupName,
                mode = resolved.mode.name.lowercase(),
                rateMillis = resolved.rateMillis,
                slowRateMillis = resolved.slowRateMillis,
                drivingExpression = resolved.drivingExpression,
                valid = defined != null,
                lastExecution = if (executed) java.time.Instant.ofEpochMilli(activity!!.lastExecutionMillis) else null,
                durationMillis = if (activity != null && activity.durationNanos >= 0) activity.durationNanos / 1_000_000.0 else null,
                overruns = activity?.overruns ?: 0L,
            ),
            deadband = DeadbandDiagnostics(
                mode = node.config.deadband.mode.name.lowercase(),
                limit = node.config.deadband.value,
                evaluated = evaluated,
                published = published,
            ),
        )
    }

    private fun linkKind(type: TagType): String = when (type) {
        TagType.MEMORY -> "memory"
        TagType.EXPRESSION -> "expression"
        TagType.DERIVED -> "derived"
        TagType.REFERENCE -> "reference"
        TagType.OPC -> "opc"
        TagType.QUERY -> "query"
        TagType.UDT -> "udt"
        TagType.FOLDER -> "folder"
    }

    private fun accessDenied() = QualifiedValue(null, Quality(QualityCode.BAD_USER_ACCESS_DENIED), engine.clock.nowMicros())

    override fun write(path: TagPath, value: Any?): WriteResult = write(path, value, TagCaller.System)

    /**
     * See [TagEngine.write]. Does not audit; [TagEngine.write] does, once per top-level write.
     *
     * The checks and the stored value happen under the exclusive lock; the subscriber notifications they raise
     * are queued and run after the outermost write releases it (P2-14), still on this thread and in order, before
     * this returns, unless that tag's listeners are already being called (see [TagEngine]). A write that a
     * subscriber starts is a new outermost write.
     */
    fun write(path: TagPath, value: Any?, caller: TagCaller): WriteResult {
        if (engine.deferredNotify.get() != null) return writeGated(path, value, caller)
        val queue = ArrayList<() -> Unit>(2)
        engine.deferredNotify.set(queue)
        val result = try {
            writeGated(path, value, caller)
        } finally {
            engine.deferredNotify.remove()
        }
        for (notification in queue) notification()
        return result
    }

    private fun writeGated(path: TagPath, value: Any?, caller: TagCaller): WriteResult = engine.withGate {
        if (readOnly || pathIsReadOnly(path)) return@withGate WriteResult(Quality.BAD_NOT_WRITABLE)
        val stackKey = baseKey(path)
        if (stackKey in writeStack) return@withGate WriteResult(Quality.ERROR_CYCLE_DETECTED)
        writeStack.addLast(stackKey)
        try {
            writeBody(path, value, caller)
        } finally {
            writeStack.removeLast()
        }
    }

    private fun writeBody(path: TagPath, value: Any?, caller: TagCaller): WriteResult {
        val node = nodeAt(path) ?: return WriteResult(Quality(QualityCode.BAD_NOT_FOUND, path.toString()))
        val propertyWrite = path.property != null && !path.property.equals("value", true)
        if (!propertyWrite && !node.config.writable) return WriteResult(Quality.BAD_NOT_WRITABLE)
        // A property write changes the tag's configuration (a reference's source), not its value. Users go through
        // the config API for that; otherwise re-pointing an open reference would read a source they may not read.
        if (propertyWrite && caller is TagCaller.User) {
            return WriteResult(Quality(QualityCode.BAD_NOT_WRITABLE, "properties of ${node.path} are configuration"))
        }
        if (node.config.writeAccess?.allows(caller) == false) {
            return WriteResult(Quality(QualityCode.BAD_USER_ACCESS_DENIED, "no write permission on ${node.path}"))
        }
        if (propertyWrite) return writeProperty(node, path.property!!, value)
        return when (node.config.type) {
            // Query tags are produced by the gateway query, so a write has nowhere to go.
            TagType.EXPRESSION, TagType.FOLDER, TagType.UDT, TagType.QUERY -> WriteResult(Quality.BAD_NOT_WRITABLE)
            TagType.REFERENCE -> writeThrough(node, value, identity = true, caller)
            TagType.DERIVED -> writeThrough(node, value, identity = false, caller)
            TagType.OPC -> writeOpc(node, value)
            TagType.MEMORY -> writeMemory(node, path, value)
        }
    }

    /** Roles required to read [path], or null when this tag does not list read permissions. */
    fun readPermissionRoles(path: TagPath): Set<String>? = engine.withShared {
        nodeAt(basePath(path))?.config?.readPermissions
    }

    fun readAccess(path: TagPath): TagAccess? = engine.withShared {
        nodeAt(basePath(path))?.config?.readAccess
    }

    fun writeAccess(path: TagPath): TagAccess? = engine.withShared {
        nodeAt(basePath(path))?.config?.writeAccess
    }

    /** Whether [path] names a configured tag here, whatever quality its value has. */
    fun isConfigured(path: TagPath): Boolean = engine.withShared { nodeAt(basePath(path))?.alive == true }

    override fun subscribe(path: TagPath, listener: (TagChange) -> Unit): AutoCloseable = engine.withGate {
        val base = basePath(path)
        val dir = locate(base, create = false)
        val list = if (dir != null) {
            watchers.getOrPut(dir) { CopyOnWriteArrayList() }
        } else {
            earlyWatchers.getOrPut(base.canonicalKey()) { CopyOnWriteArrayList() }
        }
        val sub = Sub(path, listener)
        list.add(sub)
        if (dir != null) refreshListeners(dir)
        engine.subscriptionCountListener?.invoke(sampleKey(path), list.size)
        AutoCloseable {
            engine.withGate {
                list.remove(sub)
                val bound = locate(base, create = false)
                if (bound != null) refreshListeners(bound)
                engine.subscriptionCountListener?.invoke(sampleKey(path), list.size)
            }
        }
    }

    override fun browse(path: TagPath): List<BrowseNode> = browse(path, 0, Int.MAX_VALUE)

    fun browse(path: TagPath, offset: Int, limit: Int): List<BrowseNode> = browse(path, offset, limit, TagCaller.System)

    /** See [TagEngine.browse] with a caller. */
    fun browse(path: TagPath, offset: Int, limit: Int, caller: TagCaller): List<BrowseNode> {
        if (limit <= 0) return emptyList()
        // Under the lock each row only copies fields (its read permission included); the per-caller
        // decision and redaction run after the lock is released.
        val access = if (caller is TagCaller.User) ArrayList<TagAccess?>() else null
        val page = engine.withShared {
            val kids = locate(path, create = false)?.kids ?: return@withShared emptyList()
            val out = ArrayList<BrowseNode>(minOf(limit, (kids.size - offset).coerceAtLeast(0)))
            var index = 0
            var child = kids.first
            while (child != null) {
                if (index++ >= offset) {
                    if (out.size >= limit) break
                    out += browseNode(path, child)
                    access?.add(child.node?.config?.readAccess)
                }
                child = child.next
            }
            out
        }
        if (access == null) return page
        return page.mapIndexed { i, node ->
            if (readAdmits(access[i], caller)) node
            else node.copy(dataType = null, value = accessDenied(), resource = null, readable = false)
        }
    }

    fun openSample(path: TagPath): TagSample {
        val node = nodeAt(path) ?: return MissingSample
        return DirectSample(node)
    }

    fun sampleKey(path: TagPath): String = basePath(path).canonicalKey()

    /**
     * One child, by name. The folder listing is not built and is not cached.
     */
    fun childMeta(parentPath: String, childName: String): ChildMeta? = engine.withShared {
        val parent = if (parentPath.isEmpty()) TagPath(name, emptyList()) else TagPath.parse(parentPath, name).resolve(null)
        val dir = locate(parent, create = false)?.child(childName, create = false) ?: return@withShared null
        val node = dir.node
        val def = definitions[dir]
        val kind = when {
            node?.config?.type == TagType.UDT -> NodeKind.UDT_INSTANCE
            def != null && node == null -> NodeKind.UDT_DEFINITION
            node == null || node.config.type == TagType.FOLDER -> NodeKind.FOLDER
            else -> NodeKind.TAG
        }
        ChildMeta(kind, node?.config?.dataType)
    }

    fun deviceWriteFailed(path: TagPath, quality: Quality) {
        val node = engine.withShared { nodeByKey(baseKey(path)) } ?: return
        val previous = synchronized(node) {
            if (!node.alive || node.config.type != TagType.OPC) return@synchronized null
            val current = node.qv
            val restore = if (current.quality.code == QualityCode.GOOD_WRITE_PENDING) node.beforeWrite ?: current else current
            assign(node, QualifiedValue(restore.value, quality, engine.clock.nowMicros(), restore.sourceTimestamp))
        }
        if (previous != null) deliver(node)
    }

    /** The device read period the comms deadline uses: the driver's current rate (TAG-006), else `scanPeriodMillis`. */
    private fun scanPeriodOf(node: Node): Long = node.scanOverride ?: node.config.scanPeriodMillis

    fun setDeviceScanRate(path: TagPath, rateMillis: Long?): Boolean {
        val node = engine.withShared { nodeByKey(baseKey(path)) } ?: return false
        synchronized(node) {
            node.scanOverride = rateMillis
            // Not read at all (a leased group with no subscriber): the silence timer stays off until a sample arrives.
            if (rateMillis != null && rateMillis <= 0) node.lastSampleMillis = Long.MIN_VALUE
        }
        return true
    }

    /**
     * See [TagEngine.publishQuery]. A non-Good [quality] keeps [Node.qv]'s value. A Good value is coerced
     * to the tag's data type; a conversion failure keeps the last value with `Error_TypeConversion`.
     */
    fun publishQuery(path: TagPath, value: Any?, quality: Quality) {
        val node = engine.withShared { nodeByKey(baseKey(path)) } ?: return
        val previous = synchronized(node) {
            if (!node.alive || node.config.type != TagType.QUERY) return@synchronized null
            if (inMissingGroup(node)) return@synchronized null
            val now = engine.clock.nowMicros()
            if (quality.level != Level.GOOD) {
                return@synchronized assign(node, QualifiedValue(node.qv.value, quality, now, node.qv.sourceTimestamp))
            }
            val stored = try {
                sharedInt(coerce(value, node.config.dataType))
            } catch (e: TypeFail) {
                return@synchronized assign(
                    node,
                    QualifiedValue(node.qv.value, Quality(QualityCode.ERROR_TYPE_CONVERSION, e.message), now, node.qv.sourceTimestamp),
                )
            }
            assign(node, QualifiedValue(stored, quality, now))
        }
        if (previous != null) deliver(node)
    }

    fun updateRaw(path: TagPath, value: Any?, quality: Quality, sourceTimestamp: Instant?) {
        val epoch = sourceTimestamp?.toEpochMilli() ?: TagSample.NO_SOURCE
        updateByKey(baseKey(path), value, quality, epoch, sourceTimestamp != null, sourceTimestamp)
    }

    /**
     * Hot path. No config lock and no path parse. The node monitor keeps one tag's samples ordered.
     * Listeners run after that monitor is released so a subscriber can take the config lock.
     */
    fun updateByKey(
        key: String,
        value: Any?,
        quality: Quality,
        sourceEpochMillis: Long,
        hasSource: Boolean,
        sourceInstant: Instant?,
    ) {
        val node = engine.withShared { nodeByKey(key) } ?: return
        updateNode(node, value, quality, sourceEpochMillis, hasSource, sourceInstant)
    }

    /**
     * Hot path. The caller already holds the [Node], so a sample does not hash a path or take the config lock.
     * The node monitor keeps one tag's samples ordered. Listeners run after it is released.
     */
    private fun updateNode(
        node: Node,
        value: Any?,
        quality: Quality,
        sourceEpochMillis: Long,
        hasSource: Boolean,
        sourceInstant: Instant?,
    ) {
        val previous = synchronized(node) {
            if (!node.alive || node.config.type != TagType.OPC) return@synchronized null
            // A tag whose tag group is missing keeps ERROR_CONFIGURATION until the group exists
            // again (bind() restores it). The set is empty for a correctly configured gateway.
            if (inMissingGroup(node)) return@synchronized null
            // Waiting is not a sample. The silence timer stays disarmed until a real value
            // arrives, so a slow first connect is not treated as comms loss.
            if (quality.code == QualityCode.BAD_WAITING_FOR_INITIAL_DATA) {
                val now = ScanTime.instant(engine.clock.millis())
                return@synchronized assign(node, QualifiedValue(node.qv.value, quality, now, node.qv.sourceTimestamp))
            }
            val loss = isCommsLoss(quality.code)
            if (loss) {
                if (node.commsLost) return@synchronized null
                node.commsLost = true
                if (node.lastSampleMillis != Long.MIN_VALUE) {
                    // SPEC §5: keep the last value and the device timestamp. The gateway time
                    // of this quality change is the detection instant. The tag's commsLoss
                    // setting chooses the code, not the driver's.
                    val now = ScanTime.instant(engine.clock.millis())
                    return@synchronized assign(
                        node,
                        QualifiedValue(node.qv.value, commsQuality(node), now, node.qv.sourceTimestamp),
                    )
                }
            } else {
                // Good path: one primitive write. ScanTime shares the Instant across the poll.
                node.lastSampleMillis = engine.clock.millis()
                node.commsLost = false
            }
            val source = when {
                sourceInstant != null -> sourceInstant.micros()
                hasSource -> ScanTime.instant(sourceEpochMillis)
                else -> null
            }
            val now = if (loss) source ?: ScanTime.instant(engine.clock.millis()) else source ?: ScanTime.instant(node.lastSampleMillis)
            val applied = if (loss) commsQuality(node) else quality
            if (node.config.scaling.mode == ScaleMode.EXPONENTIAL_FILTER && applied.level != Level.GOOD) {
                return@synchronized assign(node, QualifiedValue(node.qv.value, applied, source ?: now, source))
            }
            val scaled = if (node.config.scaling.mode != ScaleMode.NONE) {
                val numeric = asDouble(value)
                if (numeric != null) node.config.scaling.forward(numeric, node.filterState) else null
            } else null
            if (scaled?.filterState != null) node.filterState = scaled.filterState
            val engineering = scaled?.value ?: value
            var q = applied
            if (scaled != null && scaled.clamped && q.level == Level.GOOD) q = Quality.UNCERTAIN_ENGINEERING_UNITS_EXCEEDED
            if (scaled != null && scaled.value.isNaN()) q = q.worse(Quality(QualityCode.ERROR_EXPRESSION_EVAL, "scaling produced NaN"))
            val coerced = try {
                coerce(engineering, node.config.dataType)
            } catch (e: TypeFail) {
                return@synchronized assign(node, QualifiedValue(node.qv.value, Quality(QualityCode.ERROR_TYPE_CONVERSION, e.message), now, source))
            }
            // A suppressed sample never reaches [assign]. Count the evaluation here. The node monitor
            // is already held: one field increment, no allocation and no extra lock.
            if (node.qv.quality == q && node.config.deadband.suppresses(node.qv.value, coerced, node.config.engLow, node.config.engHigh)) {
                node.deadbandEvaluated++
                return@synchronized null
            }
            assign(node, QualifiedValue(sharedInt(coerced), q, source ?: now, source))
        }
        if (previous != null) deliver(node)
    }

    private fun browseNode(path: TagPath, childDir: Dir): BrowseNode {
        val displayName = childDir.segment
        val child = if (path.segments.isEmpty()) TagPath(name, listOf(displayName)) else path.child(displayName)
        val node = childDir.node
        val def = definitions[childDir]
        val kind = when {
            node?.config?.type == TagType.UDT -> NodeKind.UDT_INSTANCE
            def != null && node == null -> NodeKind.UDT_DEFINITION
            node == null || node.config.type == TagType.FOLDER -> NodeKind.FOLDER
            else -> NodeKind.TAG
        }
        val resource = node?.originName?.takeIf { node.originKind == TAG_KIND }
        return BrowseNode(displayName, child, kind, node?.config?.dataType, node?.qv, resource)
    }

    fun reportCommsLoss(path: TagPath, lost: Boolean) = engine.withGate {
        val node = nodeAt(path) ?: return@withGate
        node.commsLost = lost
        if (lost && !inMissingGroup(node)) applyLoss(node, engine.clock.nowMicros())
    }

    /**
     * Silence longer than the tag's scan period plus comms timeout is comms loss (SPEC §5). An unset timeout is
     * [effectiveCommsTimeoutMillis]: max(3 × scan period, 5000 ms) (review-3 F6).
     * The steady tick holds the read lock only long enough to read primitive deadlines, so a
     * scan can still publish, and it allocates nothing per tag. A tag that is actually due is
     * published after that lock drops, so a listener can take the config lock.
     */
    fun checkComms(now: Instant) {
        val nowMillis = now.toEpochMilli()
        var due: ArrayList<Node>? = null
        engine.withShared {
            var i = 0
            val size = live.size
            while (i < size) {
                val node = live[i++]
                if (node.config.type != TagType.OPC || node.commsLost) continue
                val last = node.lastSampleMillis
                if (last == Long.MIN_VALUE) continue
                val period = scanPeriodOf(node)
                if (period <= 0) continue
                if (nowMillis >= last + period + effectiveCommsTimeoutMillis(node.config.commsTimeoutMillis, period)) {
                    val list = due ?: ArrayList<Node>(4).also { due = it }
                    list.add(node)
                }
            }
        }
        val lost = due ?: return
        val stamp = ScanTime.instant(nowMillis)
        for (node in lost) {
            val previous = synchronized(node) {
                if (!node.alive || node.commsLost) return@synchronized null
                val last = node.lastSampleMillis
                if (last == Long.MIN_VALUE) return@synchronized null
                val period = scanPeriodOf(node)
                if (period <= 0 || nowMillis < last + period + effectiveCommsTimeoutMillis(node.config.commsTimeoutMillis, period)) return@synchronized null
                if (inMissingGroup(node)) return@synchronized null
                node.commsLost = true
                assign(node, QualifiedValue(node.qv.value, commsQuality(node), stamp, node.qv.sourceTimestamp))
            }
            if (previous != null) deliver(node)
        }
    }

    /**
     * See [TagEngine.tickExpressionGroups]. A [ExecutionMode.TAG_GROUP] tag runs at its group's
     * [TagGroupDef.rateMillis]; the driven slow rate and leasing are OPC scan concepts (TAG-023/025)
     * that [TagGroupSchedule] applies to device items, not to expression evaluation.
     *
     * [assign] dedups an identical (value, quality) pair (TagMemoryScanTest), so a fixed-rate
     * re-evaluation whose result happens to be unchanged would otherwise be silent -- "re-evaluated
     * ... at a rate" (TAG-029) means the engine actually re-ran it on schedule, not only when a
     * dependency happened to change, so a no-op result still re-stamps the timestamp.
     */
    fun tickExpressionGroups(groups: TagGroupRegistry, now: Instant) = synchronized(tickLock) { tickExpressionGroupsLocked(groups, now) }

    private fun tickExpressionGroupsLocked(groups: TagGroupRegistry, now: Instant) {
        val nowMillis = now.toEpochMilli()
        // Find due tags under the shared lock, like checkComms, so a tick over a large provider does
        // not hold the exclusive lock while it walks every live tag.
        val due = engine.withShared {
            var out: ArrayList<Node>? = null
            var i = 0
            val size = live.size
            while (i < size) {
                val node = live[i++]
                if (node.config.type != TagType.EXPRESSION) continue
                val rate = when (node.config.executionMode) {
                    ExecutionMode.EVENT_DRIVEN -> continue
                    ExecutionMode.FIXED_RATE -> node.config.executionRateMillis ?: 1000L
                    ExecutionMode.TAG_GROUP -> groups.resolve(node.config.tagGroup).rateMillis
                }
                if (rate <= 0 || inMissingGroup(node)) continue
                val last = timedLastRun[node]
                if (last != null && nowMillis - last < rate) continue
                (out ?: ArrayList<Node>(4).also { out = it }).add(node)
            }
            out
        } ?: return
        val stamp = now.micros()
        // Time spent evaluating tag-group expressions, per group. Fixed-rate tags are not the group's scan.
        // The map exists only when this tick actually ran one, and it is not the value publish path.
        var spent: HashMap<String, Long>? = null
        for (node in due) {
            val group = if (node.config.executionMode == ExecutionMode.TAG_GROUP) node.config.tagGroup else null
            val started = if (group != null) System.nanoTime() else 0L
            runTimed(node, nowMillis, stamp)
            if (group != null) {
                val map = spent ?: HashMap<String, Long>(2).also { spent = it }
                val elapsed = System.nanoTime() - started
                val previous = map[group]
                map[group] = if (previous == null) elapsed else previous + elapsed
            }
        }
        val finished = spent ?: return
        for ((group, nanos) in finished) {
            engine.groupActivity.record(group, nowMillis, nanos, groups.resolve(group).rateMillis)
        }
    }

    /**
     * One timed re-evaluation. Nothing here holds the exclusive lock across evaluation or
     * notification (P2-14): the expression runs under the shared lock, which is all [readLocked]
     * needs to walk the folder trie and which device samples also take; a dependency-set change is
     * applied afterwards under a short exclusive section (subscribe needs it, and a read lock cannot
     * be upgraded); the value is stored under the node monitor; subscribers run after every lock is
     * released, the same as a device sample in [updateNode].
     */
    private fun runTimed(node: Node, nowMillis: Long, stamp: Instant) {
        val deferred = arrayOfNulls<Set<TagPath>>(1)
        val next = engine.withShared {
            if (!node.alive || inMissingGroup(node) || node.config.type != TagType.EXPRESSION) return@withShared null
            timedLastRun[node] = nowMillis
            evaluate(node, deferred)
        } ?: return
        val deps = deferred[0]
        if (deps != null) engine.withGate { if (node.alive && !inMissingGroup(node)) rebind(node, deps) }
        val previous = synchronized(node) {
            if (!node.alive) return@synchronized null
            // assign() dedups an unchanged result; a timed run still re-stamps (see above).
            assign(node, next) ?: assignForce(node, node.qv.copy(timestamp = stamp))
        }
        if (previous != null) deliver(node)
    }

    /** Re-check every tag in [groupName] after that group was added or removed. */
    internal fun regroup(groupName: String) = engine.withGate {
        val exists = engine.groups.get(groupName) != null
        for (node in live.toList()) {
            if (node.config.tagGroup != groupName || (node in groupMissing) == !exists) continue
            bind(node)
        }
    }

    /**
     * Unconditional store for [tickExpressionGroups]'s re-stamp only; everything else goes through
     * [assign]'s dedup. Caller holds the node monitor. Null when the value is already identical.
     */
    private fun assignForce(node: Node, next: QualifiedValue): QualifiedValue? {
        val prev = node.qv
        if (prev == next) return null
        node.qv = next
        queueChange(node, prev, next)
        return prev
    }

    /** Next silence deadline, or [Long.MAX_VALUE] when no OPC tag is waiting on a sample. */
    fun earliestCommsDeadlineMillis(): Long {
        var earliest = Long.MAX_VALUE
        engine.withShared {
            var i = 0
            val size = live.size
            while (i < size) {
                val node = live[i++]
                if (node.config.type != TagType.OPC || node.commsLost) continue
                val last = node.lastSampleMillis
                if (last == Long.MIN_VALUE) continue
                val period = scanPeriodOf(node)
                if (period <= 0) continue
                val due = last + period + effectiveCommsTimeoutMillis(node.config.commsTimeoutMillis, period)
                if (due < earliest) earliest = due
            }
        }
        return earliest
    }

    private fun isCommsLoss(code: QualityCode) = when (code) {
        QualityCode.BAD_NOT_CONNECTED,
        QualityCode.BAD_NO_COMMUNICATION,
        QualityCode.BAD_TIMEOUT,
        QualityCode.UNCERTAIN_LAST_KNOWN_VALUE,
        QualityCode.UNCERTAIN_NO_COMM_LAST_USABLE,
        -> true
        else -> false
    }

    private fun commsQuality(node: Node): Quality = when (node.config.commsLoss) {
        CommsLossMode.UNCERTAIN_LAST_KNOWN -> Quality.UNCERTAIN_LAST_KNOWN_VALUE
        CommsLossMode.BAD_NOT_CONNECTED -> Quality.BAD_NOT_CONNECTED
    }

    /**
     * Alarm configuration for tags of a read-only provider (the `[system]` tags), by lowercase path. Users cannot
     * configure those tags, but the alarm list on them is configuration that is exported and imported (ALA-040).
     * Doc source: FEATURES ALA-040.
     */
    private val tagAlarms = ConcurrentHashMap<String, List<Map<String, Any?>>>()

    /** Set (or with null or empty, clear) the alarms configured on the tag at [path], and apply them to a published tag. */
    fun setAlarms(path: String, alarms: List<Map<String, Any?>>?) = engine.withGate {
        val key = path.trim('/').lowercase()
        if (alarms.isNullOrEmpty()) tagAlarms.remove(key) else tagAlarms[key] = alarms
        val node = nodeAt(TagPath.parse(path, name).resolve(null)) ?: return@withGate
        if (node.config.type != TagType.MEMORY) return@withGate
        publish(path, node.qv.value, node.config.dataType)
    }

    /** The alarm configuration held for [path], empty when none. */
    fun alarmsOf(path: String): List<Map<String, Any?>> = tagAlarms[path.trim('/').lowercase()] ?: emptyList()

    fun publish(path: String, value: Any?, dataType: DataType) = engine.withGate {
        val alarms = tagAlarms[path.trim('/').lowercase()]
        val cfg = if (alarms == null) {
            TagConfig(provider = name, path = path, type = TagType.MEMORY, dataType = dataType, value = value, valueSet = true, writable = !readOnly)
        } else {
            TagConfig.fromSpec(linkedMapOf(
                "provider" to name, "path" to path, "type" to "memory", "dataType" to dataType.label(),
                "value" to value, "writable" to false, "alarms" to alarms,
            ))
        }
        val held = if (alarms == null) null else nodeAt(TagPath.parse(path, name).resolve(null))
        if (held != null && held.config.raw["alarms"] == alarms && held.config.dataType == dataType) {
            // Same alarm list already applied: only the value moves, so the alarms keep their state.
            publish(held, QualifiedValue(runCatching { coerce(value, dataType) }.getOrNull(), Quality.GOOD, engine.clock.nowMicros()))
            return@withGate
        }
        val leaf = configureLeaf(normalize(cfg), null, null, generated = false, params = emptyMap(), owner = null)
        val coerced = runCatching { coerce(value, dataType) }.getOrNull()
        publish(leaf, QualifiedValue(coerced, Quality.GOOD, engine.clock.nowMicros()))
    }

    fun lastRawWrite(path: TagPath): Any? = engine.withGate { nodeAt(path)?.lastRawWrite }

    private fun configureInstance(config: TagConfig, originKind: String?, originName: String?) {
        val path = TagPath.parse(config.path, name).resolve(null)
        val key = path.canonicalKey()
        val existing = nodeAt(path)
        if (existing?.originName != null && originName != null && existing.originName != Canon.share(originName))
            throw TagConfigException("path '${config.path}' is already claimed by ${existing.originName}")
        val previous = HashMap<String, Snapshot>()
        for (node in live) if (node.owner == key) previous[node.path.canonicalKey()] = Snapshot(node)
        removeOwned(key)
        val flatError = if (config.typeId.isNullOrBlank()) "UDT instance requires typeId" else null
        if (flatError != null) {
            val node = configureLeaf(config.copy(type = TagType.UDT), originKind, originName, generated = false, params = emptyMap(), owner = key)
            publish(node, QualifiedValue(null, Quality(QualityCode.ERROR_CONFIGURATION, flatError), engine.clock.nowMicros()))
            return
        }
        val pieces = expand(config, emptyMap(), key, emptySet())
        var root: Node? = null
        val created = ArrayList<Node>()
        for (piece in pieces) {
            val node = configureLeaf(
                piece.config,
                if (piece.root) originKind else null,
                if (piece.root) originName else null,
                generated = !piece.root,
                params = piece.params,
                owner = key,
                bindNow = false,
                restore = previous[TagPath.parse(piece.config.path, name).resolve(null).canonicalKey()],
            )
            if (piece.error != null) publish(node, QualifiedValue(null, Quality(QualityCode.ERROR_CONFIGURATION, piece.error), engine.clock.nowMicros()))
            if (piece.root) root = node
            created += node
        }
        for (node in created) bind(node)
        if (root != null && pieces.any { it.error != null }) {
            val message = pieces.first { it.error != null }.error
            publish(root, QualifiedValue(null, Quality(QualityCode.ERROR_CONFIGURATION, message), engine.clock.nowMicros()))
        }
    }

    fun copyConfigs(out: MutableList<Pair<String, TagConfig>>) = engine.withShared {
        for (node in live) out += name to node.config
    }

    fun parametersOf(path: TagPath): Map<String, Any?> = engine.withShared {
        nodeAt(path)?.params ?: emptyMap()
    }

    /** True when the tag at [path] was generated by a UDT instance (a member), not configured on its own. */
    fun isGenerated(path: TagPath): Boolean = engine.withShared {
        nodeAt(path)?.generated == true
    }

    /**
     * The UDT definition at [typeId], with parameters resolved through `parent` inheritance, or null when
     * no definition is there. [UdtTypeView.error] is an inheritance error (cycle or missing parent).
     */
    fun udtType(typeId: String): UdtTypeView? = engine.withShared {
        val dir = try {
            locate(TagPath.parse(typeId, name).resolve(null), create = false)
        } catch (_: PathException) {
            null
        }
        val def = dir?.let { definitions[it] } ?: return@withShared null
        typeView(def)
    }

    /** Every UDT definition in this provider, ordered by path. */
    fun udtTypes(): List<UdtTypeView> = engine.withShared {
        definitions.values.map { typeView(it) }.sortedBy { it.path.lowercase() }
    }

    private fun typeView(def: UdtDefinition): UdtTypeView {
        val flat = flatten(def.path, emptySet())
        return UdtTypeView(def.path, flat.parameters, flat.error)
    }

    /**
     * Why a new tag cannot be created at [path], or null when the path is free: something already
     * exists there (tag, UDT instance, UDT definition, or folder), or a parent segment is a tag.
     */
    fun creationConflict(path: TagPath): String? = engine.withShared {
        var dir = root
        val segs = path.segments
        for (i in segs.indices) {
            val next = dir.child(segs[i], create = false) ?: return@withShared null
            if (i < segs.lastIndex) {
                val type = next.node?.config?.type
                if (type != null && type != TagType.FOLDER) {
                    return@withShared "parent '${segs.subList(0, i + 1).joinToString("/")}' is a tag, not a folder"
                }
            }
            dir = next
        }
        when {
            dir.node?.config?.type == TagType.UDT -> "a UDT instance already exists at this path"
            dir.node != null && dir.node?.config?.type != TagType.FOLDER -> "a tag already exists at this path"
            dir in definitions -> "a UDT definition already exists at this path"
            else -> FOLDER_EXISTS
        }
    }

    private fun configureLeaf(
        config: TagConfig,
        originKind: String?,
        originName: String?,
        generated: Boolean,
        params: Map<String, Any?>,
        owner: String?,
        bindNow: Boolean = true,
        restore: Snapshot? = null,
    ): Node {
        val kindS = originKind?.let { Canon.share(it) }
        val nameS = originName?.let { Canon.share(it) }
        val path = TagPath.parse(config.path, name).resolve(null)
        if (path.segments.isEmpty()) throw TagConfigException("tag path is empty")
        val dir = locate(path, create = true, leafAlias = nameS) ?: throw TagConfigException("tag path is empty")
        val existing = dir.node
        if (!generated && existing?.originName != null && nameS != null && existing.owner == null &&
            (existing.originName != nameS || (kindS != null && existing.originKind != null && existing.originKind != kindS))
        ) throw TagConfigException("path '${config.path}' is already claimed by ${existing.originName}")
        val node = existing ?: Node(dir, config)
        node.dir = dir
        node.alive = true
        val before = node.qv
        val kept = restore ?: existing?.let { Snapshot(it) }
        val keepRuntime = kept != null && config.type == TagType.MEMORY && kept.type == TagType.MEMORY &&
            kept.seed == config.value && kept.valueSet == config.valueSet && kept.dataType == config.dataType
        val shownText = display(TagPath(name, path.segments))
        val stored = if (config.provider == name && config.path == shownText) config else config.copy(provider = name, path = shownText)
        if (nameS != null) stored.aliasLeaf(nameS)
        node.config = stored
        if (generated) {
            node.originKind = null
            node.originName = null
        } else if (nameS != null) {
            node.originKind = kindS
            node.originName = nameS
        }
        node.generated = generated
        node.owner = owner
        node.params = params
        node.seed = config.value
        node.qv = if (keepRuntime) kept!!.qv else initialValue(node)
        // A reconfigured memory tag that dropped its runtime value: rewrite its persisted row too.
        if (existing != null && !keepRuntime && config.type == TagType.MEMORY && !readOnly) engine.memorySink?.dirty(node)
        dir.node = node
        track(node)
        if (earlyWatchers.isNotEmpty()) adoptEarly(dir, path.canonicalKey())
        engine.notifyConfig(name, stored.path, stored)
        if (bindNow && config.type != TagType.UDT) bind(node)
        // Expression, derived, and reference tags publish from bind(). Memory and device tags
        // must still announce their initial value so anything already subscribed (a tag that
        // referenced this path before it existed) recalculates.
        if (config.type != TagType.EXPRESSION && config.type != TagType.DERIVED && config.type != TagType.REFERENCE && before != node.qv) {
            val announced = node.qv
            node.qv = before
            publish(node, announced)
        }
        return node
    }

    private fun bind(node: Node) {
        node.deps?.forEach { it.close() }
        node.deps?.clear()
        node.depKeys = emptySet()
        forgetReads(node)
        node.expr = null
        val groupName = node.config.tagGroup
        if (engine.groups.get(groupName) == null) {
            groupMissing.add(node)
            // Disarm the silence timer: device samples are ignored while the group is missing.
            synchronized(node) { node.lastSampleMillis = Long.MIN_VALUE }
            publish(node, QualifiedValue(node.qv.value, Quality(QualityCode.ERROR_CONFIGURATION, "unknown tag group '$groupName'"), engine.clock.nowMicros(), node.qv.sourceTimestamp))
            return
        }
        if (groupMissing.remove(node)) {
            // The group exists again. Computed tags republish below; the rest get their quality back.
        when (node.config.type) {
            // Query tags keep the last value until their runner publishes again (TAG-030).
            TagType.EXPRESSION, TagType.DERIVED, TagType.REFERENCE, TagType.QUERY -> {}
            TagType.MEMORY -> publish(node, QualifiedValue(node.qv.value, Quality.GOOD, engine.clock.nowMicros()))
            else -> publish(node, initialValue(node))
        }
        }
        when (node.config.type) {
            TagType.EXPRESSION -> {
                val text = node.config.expression
                if (text.isNullOrBlank()) {
                    publish(node, QualifiedValue(null, Quality(QualityCode.ERROR_CONFIGURATION, "expression is empty"), engine.clock.nowMicros()))
                    return
                }
                try {
                    node.expr = Expressions.parse(text)
                } catch (e: ExpressionException) {
                    publish(node, QualifiedValue(null, Quality(e.quality, e.message), engine.clock.nowMicros()))
                    return
                }
                reevaluate(node)
            }
            TagType.REFERENCE -> {
                val sourcePath = node.config.sourcePath
                if (sourcePath.isNullOrBlank()) {
                    publish(node, QualifiedValue(null, Quality(QualityCode.BAD_REFERENCE_NOT_FOUND, "sourcePath is empty"), engine.clock.nowMicros()))
                    return
                }
                val src = try {
                    resolveAgainst(sourcePath, node.path)
                } catch (e: PathException) {
                    publish(node, QualifiedValue(null, Quality(QualityCode.ERROR_INVALID_PATH, e.message), engine.clock.nowMicros()))
                    return
                }
                rebind(node, setOf(src))
                pullReference(node)
            }
            TagType.DERIVED -> {
                val sourcePath = node.config.sourcePath
                if (sourcePath.isNullOrBlank()) {
                    publish(node, QualifiedValue(null, Quality(QualityCode.BAD_REFERENCE_NOT_FOUND, "sourcePath is empty"), engine.clock.nowMicros()))
                    return
                }
                val text = node.config.readExpression ?: "{source}"
                try {
                    node.expr = Expressions.parse(text)
                } catch (e: ExpressionException) {
                    publish(node, QualifiedValue(null, Quality(e.quality, e.message), engine.clock.nowMicros()))
                    return
                }
                reevaluate(node)
            }
            else -> {}
        }
    }

    private fun reevaluate(node: Node) {
        evaluate(node, null)
    }

    /**
     * Runs [node]'s expression. With [deferRebind] null (the event-driven path) it rebinds and
     * publishes while [node] is still on the evaluation chain, so a dependency loop is caught as a
     * cycle, and returns null. With [deferRebind] given (the timed path, which holds only the shared
     * lock) it neither rebinds nor publishes: a changed dependency set is left in the array's first
     * slot and the value to publish is returned.
     */
    private fun evaluate(node: Node, deferRebind: Array<Set<TagPath>?>?): QualifiedValue? {
        val evalChain = evalChain
        if (evalChain.any { it === node }) {
            latchCycle()
            return null
        }
        if (evalChain.size > 64) {
            val deep = QualifiedValue(node.qv.value, Quality.ERROR_CYCLE_DETECTED, engine.clock.nowMicros(), node.qv.sourceTimestamp)
            if (deferRebind != null) return deep
            publish(node, deep)
            return null
        }
        val expr = node.expr ?: return null
        evalChain.addLast(node)
        try {
            val reads = LinkedHashSet<TagPath>()
            val locals = HashMap<String, Value>()
            if (node.config.type == TagType.DERIVED) {
                val src = readSource(node)
                locals["source"] = Value(src.value, src.quality)
                src.path?.let { reads += it }
            }
            val result = Expressions.eval(expr, EvalContext(
                ref = { raw -> readRef(raw, node, reads) },
                params = node.params,
                locals = locals,
            ))
            val selfKey = node.path.canonicalKey()
            val deps = reads.filterNot { it.property == null && it.indexes.isEmpty() && it.canonicalKey() == selfKey }.toSet()
            if (deferRebind == null) rebind(node, deps)
            else if (deps.map { it.watchKey() }.toSet() != node.depKeys) deferRebind[0] = deps
            val out = try {
                QualifiedValue(coerce(result.value, node.config.dataType), result.quality, engine.clock.nowMicros())
            } catch (e: TypeFail) {
                QualifiedValue(null, Quality(QualityCode.ERROR_TYPE_CONVERSION, e.message), engine.clock.nowMicros())
            }
            if (deferRebind != null) return out
            publish(node, out)
            return null
        } finally {
            evalChain.removeLast()
        }
    }

    private fun latchCycle() {
        val state = evalState.get()
        if (state.cycleLatch) return
        state.cycleLatch = true
        try {
            val now = engine.clock.nowMicros()
            for (n in state.chain) {
                if (n.qv.quality.code == QualityCode.ERROR_CYCLE_DETECTED) continue
                synchronized(n) {
                    val prev = n.qv
                    val next = QualifiedValue(prev.value, Quality.ERROR_CYCLE_DETECTED, now, prev.sourceTimestamp)
                    n.qv = next
                    queueChange(n, prev, next)
                }
                deliver(n)
            }
        } finally {
            state.cycleLatch = false
        }
    }

    private fun readRef(raw: String, node: Node, reads: MutableSet<TagPath>): Value {
        val path = try {
            resolveAgainst(raw, node.path)
        } catch (e: PathException) {
            return Value(null, Quality(QualityCode.ERROR_INVALID_PATH, e.message))
        }
        if (evalChain.any { it.dir === locate(path, false) && path.property == null && path.indexes.isEmpty() })
            return Value(locate(path, false)?.node?.qv?.value, Quality.ERROR_CYCLE_DETECTED)
        reads += path
        val qv = readLocked(path)
        val quality = if (qv.quality.code == QualityCode.BAD_NOT_FOUND) Quality(QualityCode.BAD_REFERENCE_NOT_FOUND, raw) else qv.quality
        return Value(qv.value, quality)
    }

    private fun readSource(node: Node): Sourced {
        val raw = node.config.sourcePath ?: return Sourced(null, Quality.BAD_REFERENCE_NOT_FOUND, null)
        val path = try {
            resolveAgainst(raw, node.path)
        } catch (e: PathException) {
            return Sourced(null, Quality(QualityCode.ERROR_INVALID_PATH, e.message), null)
        }
        val qv = readLocked(path)
        val quality = if (qv.quality.code == QualityCode.BAD_NOT_FOUND) Quality(QualityCode.BAD_REFERENCE_NOT_FOUND, raw) else qv.quality
        return Sourced(qv.value, quality, path)
    }

    private fun pullReference(node: Node) {
        val src = readSource(node)
        val now = engine.clock.nowMicros()
        if (src.path == null) {
            publish(node, QualifiedValue(null, src.quality, now))
            return
        }
        val coerced = try {
            coerce(src.value, node.config.dataType)
        } catch (e: TypeFail) {
            publish(node, QualifiedValue(src.value, Quality(QualityCode.ERROR_TYPE_CONVERSION, e.message), now))
            return
        }
        val qv = readLocked(src.path)
        val stamp = if (src.quality.code == QualityCode.BAD_REFERENCE_NOT_FOUND) now else qv.timestamp
        publish(node, QualifiedValue(coerced, src.quality, stamp, qv.sourceTimestamp))
    }

    private fun rebind(node: Node, paths: Set<TagPath>) {
        val keys = paths.map { it.watchKey() }.toSet()
        if (keys == node.depKeys) return
        // The dependency callback does not hold the engine lock. The index is only mutated under it.
        if (engine.holdingWriteLock()) rebindLocked(node, paths, keys)
        else engine.withGate { if (node.alive) rebindLocked(node, paths, keys) }
    }

    private fun rebindLocked(node: Node, paths: Set<TagPath>, keys: Set<String>) {
        node.deps?.forEach { it.close() }
        node.deps = null
        node.depKeys = keys
        indexReads(node, paths)
        for (p in paths) {
            val sub = subscribe(p) {
                if (evalState.get().cycleLatch) return@subscribe
                when (node.config.type) {
                    TagType.REFERENCE -> pullReference(node)
                    else -> reevaluate(node)
                }
            }
            val list = node.deps ?: ArrayList<AutoCloseable>(1).also { node.deps = it }
            list.add(sub)
        }
    }

    /** Caller holds the engine write lock. */
    private fun indexReads(node: Node, paths: Collection<TagPath>) {
        forgetReads(node)
        if (paths.isEmpty()) return
        val stored = ArrayList<TagPath>(paths.size)
        for (p in paths) {
            val key = basePath(p).canonicalKey()
            val list = readersOf.getOrPut(key) { ArrayList(1) }
            if (list.none { it === node }) list.add(node)
            stored.add(p)
        }
        node.reads = stored
    }

    /** Caller holds the engine write lock. A tag that reads nothing does not touch the index. */
    private fun forgetReads(node: Node) {
        val old = node.reads
        if (old.isEmpty()) return
        for (p in old) {
            val list = readersOf[basePath(p).canonicalKey()] ?: continue
            var i = list.size
            while (i > 0) {
                i--
                if (list[i] === node) list.removeAt(i)
            }
            if (list.isEmpty()) readersOf.remove(basePath(p).canonicalKey())
        }
        node.reads = emptyList()
    }

    private fun writeMemory(node: Node, path: TagPath, value: Any?): WriteResult {
        if (path.indexes.isEmpty()) return setMemory(node, value, null)
        val list = (node.qv.value as? List<*>)?.toMutableList()
            ?: return WriteResult(Quality(QualityCode.ERROR_TYPE_CONVERSION, "tag value is not an array"))
        var cursor: MutableList<Any?> = list
        for ((depth, index) in path.indexes.withIndex()) {
            if (index !in cursor.indices) return WriteResult(Quality.BAD_OUT_OF_RANGE)
            if (depth == path.indexes.lastIndex) {
                val coerced = try {
                    coerce(value, node.config.dataType)
                } catch (e: TypeFail) {
                    return WriteResult(Quality(QualityCode.ERROR_TYPE_CONVERSION, e.message))
                }
                cursor[index] = coerced
            } else {
                val nested = cursor[index] as? MutableList<Any?> ?: (cursor[index] as? List<*>)?.toMutableList()
                    ?: return WriteResult(Quality(QualityCode.ERROR_TYPE_CONVERSION, "tag value is not an array"))
                cursor[index] = nested
                cursor = nested
            }
        }
        publish(node, QualifiedValue(list, Quality.GOOD, engine.clock.nowMicros()))
        if (!readOnly) engine.memorySink?.dirty(node)
        return WriteResult(Quality.GOOD)
    }

    private fun writeOpc(node: Node, value: Any?): WriteResult {
        val numeric = asDouble(value)
        val raw = if (numeric != null && node.config.scaling.mode != ScaleMode.NONE && node.config.scaling.mode != ScaleMode.EXPONENTIAL_FILTER)
            node.config.scaling.inverse(numeric) else value
        node.lastRawWrite = raw
        val echoed = if (raw is Double && node.config.scaling.mode != ScaleMode.NONE && node.config.scaling.mode != ScaleMode.EXPONENTIAL_FILTER)
            node.config.scaling.forward(raw, null).value else value
        val coerced = try {
            coerce(echoed, node.config.dataType)
        } catch (e: TypeFail) {
            return WriteResult(Quality(QualityCode.ERROR_TYPE_CONVERSION, e.message))
        }
        // The value to go back to if the device refuses (review-2 F5): the last one read, not an earlier pending write.
        val current = node.qv
        if (current.quality.code != QualityCode.GOOD_WRITE_PENDING) node.beforeWrite = current
        publish(node, QualifiedValue(coerced, Quality.GOOD_WRITE_PENDING, engine.clock.nowMicros()))
        val handler = engine.deviceWrite
        if (handler != null) handler.enqueue(node.config, raw)?.let { engine.pendingDeviceWrites.get()?.add(it) }
        return WriteResult(Quality.GOOD)
    }

    private fun writeThrough(node: Node, value: Any?, identity: Boolean, caller: TagCaller): WriteResult {
        val source = node.config.sourcePath ?: return WriteResult(Quality.BAD_REFERENCE_NOT_FOUND)
        val target = try {
            resolveAgainst(source, node.path)
        } catch (e: PathException) {
            return WriteResult(Quality(QualityCode.ERROR_INVALID_PATH, e.message))
        }
        val outgoing = if (identity) value else {
            val text = node.config.writeExpression ?: "{value}"
            val expr = try {
                Expressions.parse(text)
            } catch (e: ExpressionException) {
                return WriteResult(Quality(e.quality, e.message))
            }
            val src = readSource(node)
            val result = Expressions.eval(expr, EvalContext(
                ref = { raw -> readRef(raw, node, LinkedHashSet()) },
                params = node.params,
                locals = mapOf("value" to Value(value, Quality.GOOD), "source" to Value(src.value, src.quality)),
            ))
            if (result.quality.level == Level.ERROR && result.value == null) return WriteResult(result.quality)
            result.value
        }
        val providerName = target.provider ?: name
        val provider = engine.provider(providerName) ?: return WriteResult(Quality(QualityCode.BAD_NOT_FOUND, providerName))
        // The source's own read-only flag and write permissions apply to the same caller.
        return if (provider is StandardTagProvider) provider.write(target, outgoing, caller) else provider.write(target, outgoing)
    }

    private fun writeProperty(node: Node, property: String, value: Any?): WriteResult {
        if (property.equals("sourceTagPath", true) && (node.config.type == TagType.DERIVED || node.config.type == TagType.REFERENCE)) {
            node.config = node.config.copy(sourcePath = value?.toString())
            bind(node)
            return WriteResult(Quality.GOOD)
        }
        return WriteResult(Quality.BAD_NOT_WRITABLE)
    }

    private fun readLocked(path: TagPath): QualifiedValue {
        val dir = locate(basePath(path), create = false)
        val node = dir?.node
        if (node == null) {
            if (dir != null && ((dir.kids?.size ?: 0) > 0 || dir in definitions))
                return QualifiedValue(null, Quality.GOOD, engine.clock.nowMicros())
            return QualifiedValue(null, Quality(QualityCode.BAD_NOT_FOUND, path.toString()), engine.clock.nowMicros())
        }
        if (path.property != null && !path.property.equals("value", true)) return propertyValue(node, path.property)
        var current = node.qv.value
        for (index in path.indexes) {
            val list = current as? List<*> ?: return node.qv.copy(value = null, quality = Quality.BAD_OUT_OF_RANGE)
            if (index !in list.indices) return node.qv.copy(value = null, quality = Quality.BAD_OUT_OF_RANGE)
            current = list[index]
        }
        return if (path.indexes.isEmpty()) node.qv else node.qv.copy(value = current)
    }

    private fun propertyValue(node: Node, property: String): QualifiedValue {
        val qv = node.qv
        val value = when (property.lowercase()) {
            "quality" -> qv.quality.code.label
            "timestamp" -> qv.timestamp
            "sourcetimestamp" -> qv.sourceTimestamp
            "name" -> node.path.segments.last()
            "datatype" -> node.config.dataType.label()
            "documentation" -> node.config.documentation
            "engunit" -> node.config.engUnit
            "sourcetagpath" -> node.config.sourcePath
            "opcitempath" -> node.config.opcItemPath
            "englow" -> node.config.engLow
            "enghigh" -> node.config.engHigh
            else -> return qv.copy(value = null, quality = Quality(QualityCode.BAD_NOT_FOUND, "unknown property $property"))
        }
        return QualifiedValue(value, Quality.GOOD, qv.timestamp, qv.sourceTimestamp)
    }

    private fun publish(node: Node, next: QualifiedValue) {
        synchronized(node) { assign(node, next) } ?: return
        notifyOrDefer(node)
    }

    /** [deliver] now, or after the enclosing path-based write releases the engine lock (see [write]). */
    private fun notifyOrDefer(node: Node) {
        val queue = engine.deferredNotify.get()
        if (queue != null && engine.holdingWriteLock()) queue.add { deliver(node) } else deliver(node)
    }

    /** Caller holds the node monitor. Returns the previous value when subscribers must be told. */
    private fun assign(node: Node, next: QualifiedValue): QualifiedValue? {
        if (!node.alive && next.quality.code != QualityCode.BAD_NOT_FOUND) return null
        // Deadband counters. The caller already holds this node's monitor, so the increments allocate
        // nothing and take no extra lock (TAG-003). A suppressed value is evaluated and not published.
        node.deadbandEvaluated++
        val prev = node.qv
        if (prev.quality == next.quality &&
            node.config.deadband.suppresses(prev.value, next.value, node.config.engLow, node.config.engHigh)
        ) return null
        if (prev == next) return null
        node.deadbandPublished++
        node.qv = next
        queueChange(node, prev, next)
        return prev
    }

    /**
     * Caller holds the node monitor and has just stored [next] over [prev]. Records the change, with the
     * listeners subscribed at this moment, in store order. [deliver] hands it out later, outside every lock,
     * so a change stored while this tag's listeners are still being called waits behind the one in progress
     * instead of overtaking it, and each change keeps the exact value it replaced (see [TagChange]).
     * No listeners: nothing is recorded and nothing allocated.
     */
    private fun queueChange(node: Node, prev: QualifiedValue, next: QualifiedValue) {
        engine.storedChanges.increment()
        val subs = node.listeners
        if (subs.isEmpty()) return
        val queue = node.changes ?: java.util.ArrayDeque<Any>(6).also { node.changes = it }
        queue.addLast(subs)
        queue.addLast(prev)
        queue.addLast(next)
    }

    /**
     * Calls listeners for every change [queueChange] recorded on [node], oldest first. One thread delivers a
     * tag's changes at a time: if another thread (or an outer frame of this one, when a listener writes the
     * same tag) is already delivering, this returns at once and that delivery picks the new change up after
     * the current one. No lock is held while a listener runs. Hot path for a plain memory or device value:
     * the queue is an [java.util.ArrayDeque] reused per tag, so a steady scan does not allocate here.
     */
    @Suppress("UNCHECKED_CAST")
    private fun deliver(node: Node) {
        var subs: Array<Sub>
        var previous: QualifiedValue
        var next: QualifiedValue
        synchronized(node) {
            val queue = node.changes
            if (node.delivering || queue == null || queue.isEmpty()) return
            node.delivering = true
            subs = queue.pollFirst() as Array<Sub>
            previous = queue.pollFirst() as QualifiedValue
            next = queue.pollFirst() as QualifiedValue
        }
        var released = false
        try {
            while (true) {
                fanOut(subs, previous, next, node)
                synchronized(node) {
                    val queue = node.changes
                    if (queue == null || queue.isEmpty()) {
                        node.delivering = false
                        released = true
                        return
                    }
                    subs = queue.pollFirst() as Array<Sub>
                    previous = queue.pollFirst() as QualifiedValue
                    next = queue.pollFirst() as QualifiedValue
                }
            }
        } finally {
            // Only an Error from a listener gets here. Later changes stay queued for the next delivery.
            if (!released) synchronized(node) { node.delivering = false }
        }
    }

    /**
     * Calls each of [subs] with one change. The listener array is a snapshot taken when the subscription
     * set changes, so a scan does not walk a map or allocate an iterator.
     */
    private fun fanOut(subs: Array<Sub>, previous: QualifiedValue, next: QualifiedValue, node: Node) {
        val n = subs.size
        var i = 0
        while (i < n) {
            val sub = subs[i]
            i++
            try {
                val path = sub.path
                if (path.property == null && path.indexes.isEmpty()) {
                    if (next.value != previous.value || next.quality != previous.quality || next.timestamp != previous.timestamp)
                        sub.listener(TagChange(path, next, previous))
                } else {
                    val projectedNext = project(next, node, path)
                    val projectedPrev = project(previous, node, path)
                    if (projectedNext.value != projectedPrev.value || projectedNext.quality != projectedPrev.quality ||
                        projectedNext.timestamp != projectedPrev.timestamp
                    ) sub.listener(TagChange(path, projectedNext, projectedPrev))
                }
            } catch (_: Exception) {
                // One listener, including an expression that failed closed, must not skip the rest.
            }
        }
    }

    /**
     * Scalar memory write used by [write] and by [TagSample.set]. No config lock. The node monitor
     * orders this tag's values. Subscribers run after it is released.
     * A [Double] stored on a double tag is kept as that instance so a scan can share one boxed value.
     */
    private fun setMemory(node: Node, value: Any?, at: Instant?): WriteResult {
        if (node.config.type != TagType.MEMORY) return MEMORY_WRITE_NOT_WRITABLE
        val stack = memoryWriteStack.get()
        var depth = stack.size
        while (depth > 0) {
            if (stack[--depth] === node) return MEMORY_WRITE_CYCLE
        }
        val stored = if (value is Double && node.config.dataType == DataType.DOUBLE) value else {
            val coerced = try {
                coerce(value, node.config.dataType)
            } catch (e: TypeFail) {
                return WriteResult(Quality(QualityCode.ERROR_TYPE_CONVERSION, e.message))
            }
            sharedInt(coerced)
        }
        val stamp = if (at != null) at.micros() else engine.clock.nowMicros()
        val next = QualifiedValue(stored, Quality.GOOD, stamp)
        stack.add(node)
        try {
            var stop = 0
            val previous = synchronized(node) {
                if (!node.alive) {
                    stop = 1
                    null
                } else if (!node.config.writable) {
                    stop = 2
                    null
                } else {
                    assign(node, next)
                }
            }
            if (stop == 1) return MEMORY_WRITE_NOT_FOUND
            if (stop == 2) return MEMORY_WRITE_NOT_WRITABLE
            if (previous != null) {
                notifyOrDefer(node)
                // Write-behind: one volatile read and a set add. No path, no encoding, no disk I/O here.
                if (!readOnly) engine.memorySink?.dirty(node)
            }
            return MEMORY_WRITE_OK
        } finally {
            stack.removeAt(stack.lastIndex)
        }
    }

    fun publishMemory(path: TagPath, value: Any?, quality: Quality): WriteResult {
        val node = engine.withShared { nodeByKey(baseKey(path)) } ?: return WriteResult(Quality.BAD_NOT_FOUND)
        if (node.config.type != TagType.MEMORY) return WriteResult(Quality.BAD_NOT_WRITABLE)
        val stored = try {
            sharedInt(coerce(value, node.config.dataType))
        } catch (e: TypeFail) {
            return WriteResult(Quality(QualityCode.ERROR_TYPE_CONVERSION, e.message))
        }
        val now = engine.clock.nowMicros()
        val outcome = synchronized(node) {
            if (!node.alive) 0
            else if (quality.level == Level.GOOD && !node.config.writable) 1
            else if (assign(node, QualifiedValue(stored, quality, now)) != null) 2
            else 3
        }
        return when (outcome) {
            0 -> WriteResult(Quality.BAD_NOT_FOUND)
            1 -> WriteResult(Quality.BAD_NOT_WRITABLE)
            2 -> {
                deliver(node)
                if (!readOnly) engine.memorySink?.dirty(node)
                WriteResult(quality)
            }
            else -> WriteResult(quality)
        }
    }

    /** Point the node's scan list at the current watcher snapshot. Subscribe time, not scan time. */
    private fun refreshListeners(dir: Dir) {
        val node = dir.node ?: return
        val list = watchers[dir]
        node.listeners = if (list == null || list.isEmpty()) Node.NO_SUBS else list.toTypedArray()
    }

    private fun project(qv: QualifiedValue, node: Node, path: TagPath): QualifiedValue {
        if (path.property != null && !path.property.equals("value", true)) {
            val synthetic = node.copyFor(qv)
            return propertyValue(synthetic, path.property)
        }
        var current = qv.value
        for (index in path.indexes) {
            val list = current as? List<*> ?: return qv.copy(value = null, quality = Quality.BAD_OUT_OF_RANGE)
            if (index !in list.indices) return qv.copy(value = null, quality = Quality.BAD_OUT_OF_RANGE)
            current = list[index]
        }
        return if (path.indexes.isEmpty()) qv else qv.copy(value = current)
    }

    private fun applyLoss(node: Node, now: Instant) {
        val quality = when (node.config.commsLoss) {
            CommsLossMode.UNCERTAIN_LAST_KNOWN -> Quality.UNCERTAIN_LAST_KNOWN_VALUE
            CommsLossMode.BAD_NOT_CONNECTED -> Quality.BAD_NOT_CONNECTED
        }
        publish(node, QualifiedValue(node.qv.value, quality, now, node.qv.sourceTimestamp))
    }

    private fun initialValue(node: Node): QualifiedValue {
        val now = engine.clock.nowMicros()
        return when (node.config.type) {
            TagType.OPC -> QualifiedValue(null, Quality.BAD_WAITING_FOR_INITIAL_DATA, now)
            TagType.EXPRESSION, TagType.DERIVED, TagType.REFERENCE, TagType.QUERY -> QualifiedValue(null, Quality.UNCERTAIN_INITIAL_VALUE, now)
            TagType.FOLDER, TagType.UDT -> QualifiedValue(null, Quality.GOOD, now)
            TagType.MEMORY -> {
                restoredValue(node)?.let { return it }
                if (!node.config.valueSet) QualifiedValue(zero(node.config.dataType), Quality.GOOD, now)
                else try {
                    QualifiedValue(coerce(node.config.value, node.config.dataType), Quality.GOOD, now)
                } catch (e: TypeFail) {
                    QualifiedValue(null, Quality(QualityCode.ERROR_TYPE_CONVERSION, e.message), now)
                }
            }
        }
    }

    /** A persisted value for a memory tag whose data type and seed have not changed since it was written (TAG-016). */
    private fun restoredValue(node: Node): QualifiedValue? {
        val sink = engine.memorySink ?: return null
        if (readOnly) return null
        val saved = sink.restore(node.path.canonicalKey(), node.config) ?: return null
        val value = try {
            coerce(saved.value, node.config.dataType)
        } catch (e: TypeFail) {
            return null
        }
        return QualifiedValue(sharedInt(value), saved.quality, saved.timestamp)
    }

    internal fun snapshotOf(handle: Any): MemorySnapshot? {
        val node = handle as? Node ?: return null
        if (!node.alive || node.config.type != TagType.MEMORY) return null
        val path = node.path
        return MemorySnapshot(path.canonicalKey(), path.toString(), node.config, node.qv)
    }

    private fun expand(instance: TagConfig, inherited: Map<String, Any?>, owner: String?, stack: Set<String>): List<Piece> {
        val typeId = instance.typeId ?: return listOf(Piece(instance, inherited, root = true, error = "UDT instance requires typeId"))
        if (typeId.lowercase() in stack) return listOf(Piece(instance.copy(type = TagType.UDT), inherited, root = true, error = "nested UDT cycle at $typeId"))
        val flat = flatten(typeId, emptySet())
        if (flat.error != null) return listOf(Piece(instance.copy(type = TagType.UDT), inherited, root = true, error = flat.error))
        val params = LinkedHashMap<String, Any?>()
        params.putAll(inherited)
        flat.parameters.forEach { params.putIfAbsent(it.name, it.defaultValue) }
        instance.parameters.forEach { (k, v) -> params[k] = v }
        repeat(4) {
            val keys = params.keys.toList()
            for (k in keys) {
                val v = params[k]
                if (v is String) params[k] = subst(v, params)
            }
        }
        val out = ArrayList<Piece>()
        out += Piece(instance.copy(type = TagType.UDT), params, root = true, error = null)
        for (member in flat.members) {
            val name = member.memberName()
            val spec = LinkedHashMap(member.raw.ifEmpty { member.toSpec() })
            instance.overrides[name]?.forEach { (k, v) -> if (v == null) spec.remove(k) else spec[k] = v }
            substSpec(spec, params)
            spec["name"] = name
            spec["provider"] = name.let { instance.provider }
            spec["path"] = instance.path.trimEnd('/') + "/" + name
            val cfg = try {
                TagConfig.fromSpec(spec, member = true)
            } catch (e: RuntimeException) {
                out += Piece(instance, params, root = false, error = e.message)
                continue
            }
            if (cfg.type == TagType.UDT) {
                val nested = LinkedHashMap(cfg.overrides)
                instance.overrides.forEach { (k, v) ->
                    val prefix = "$name/"
                    if (k.startsWith(prefix)) nested[k.removePrefix(prefix)] = v
                }
                val parentPath = instance.path
                val nestedCfg = cfg.copy(overrides = nested, path = parentPath.trimEnd('/') + "/" + name, provider = instance.provider)
                val childrenPieces = expand(nestedCfg, params, owner, stack + typeId.lowercase())
                // The nested expand includes its own root; that root is the member, not a second top-level instance.
                out += childrenPieces.map { if (it.root) it.copy(root = false) else it }
            } else {
                out += Piece(cfg.copy(provider = instance.provider), params, root = false, error = null)
            }
        }
        return out
    }

    private data class Flat(val parameters: List<UdtParameter>, val members: List<TagConfig>, val error: String?)

    private fun flatten(typePath: String, stack: Set<String>): Flat {
        val dir = try {
            locate(TagPath.parse(typePath, name).resolve(null), create = false)
        } catch (e: PathException) {
            return Flat(emptyList(), emptyList(), e.message)
        } ?: return Flat(emptyList(), emptyList(), "UDT '$typePath' not found")
        val def = definitions[dir] ?: return Flat(emptyList(), emptyList(), "UDT '$typePath' not found")
        val id = def.path.lowercase()
        if (id in stack) return Flat(emptyList(), emptyList(), "UDT inheritance cycle at ${def.path}")
        val parent = if (def.parent.isNullOrBlank()) null else flatten(def.parent, stack + id)
        if (parent?.error != null) return parent
        val params = LinkedHashMap<String, UdtParameter>()
        parent?.parameters?.forEach { params[it.name] = it }
        def.parameters.forEach { params[it.name] = it }
        val members = LinkedHashMap<String, TagConfig>()
        parent?.members?.forEach { members[it.memberName()] = it }
        def.members.forEach { member ->
            val n = member.memberName()
            val prev = members[n]
            members[n] = if (prev == null) member else prev.overriddenBy(member.raw.ifEmpty { member.toSpec() })
        }
        return Flat(params.values.toList(), members.values.toList(), null)
    }

    private fun reexpandDependents(changedPath: String) {
        val instances = live.filter { it.config.type == TagType.UDT && it.owner == it.path.canonicalKey() && !it.generated }.toList()
        for (instance in instances) {
            val typeId = instance.config.typeId ?: continue
            if (!dependsOn(typeId, changedPath, emptySet())) continue
            configureInstance(instance.config, instance.originKind, instance.originName)
        }
    }

    private fun dependsOn(typePath: String, changed: String, stack: Set<String>): Boolean {
        val def = try {
            val dir = locate(TagPath.parse(typePath, name).resolve(null), create = false) ?: return false
            definitions[dir]
        } catch (e: PathException) {
            null
        } ?: return false
        if (def.path.equals(changed, true)) return true
        if (def.path.lowercase() in stack) return false
        if (def.parent != null && dependsOn(def.parent, changed, stack + def.path.lowercase())) return true
        return def.members.any { member ->
            val nested = member.typeId
            member.type == TagType.UDT && nested != null && dependsOn(nested, changed, stack + def.path.lowercase())
        }
    }

    private fun removeOwned(owner: String) {
        live.filter { it.owner == owner }.toList().forEach { removeNode(it, notify = false, cascade = false) }
    }

    /** [forget]: the tag is deleted, not re-expanded, so its persisted memory value goes too (TAG-016). */
    private fun removeNode(node: Node, notify: Boolean, cascade: Boolean = true, forget: Boolean = false) {
        engine.notifyConfig(name, node.config.path, null)
        val key = node.owner
        if (cascade && node.config.type == TagType.UDT && key != null && node.owner == node.path.canonicalKey()) {
            live.filter { it.owner == key && it !== node }.toList().forEach { removeNode(it, notify = false, cascade = false, forget = forget) }
        }
        node.deps?.forEach { it.close() }
        forgetReads(node)
        groupMissing.remove(node)
        timedLastRun.remove(node)
        if (forget && !readOnly && node.config.type == TagType.MEMORY) engine.memorySink?.removed(node.path.canonicalKey())
        synchronized(node) {
            node.alive = false
            val before = node.qv
            node.qv = QualifiedValue(null, Quality(QualityCode.BAD_NOT_FOUND, node.path.toString()), engine.clock.nowMicros())
            if (notify) queueChange(node, before, node.qv)
        }
        if (node.dir.node === node) node.dir.node = null
        untrack(node)
        if (notify) deliver(node)
        node.listeners = Node.NO_SUBS
        watchers.remove(node.dir)
        unlink(node.dir)
    }

    private fun unlink(dir: Dir) {
        var current: Dir? = dir
        while (current != null && current !== root) {
            val here = current
            if (here.node != null || here in definitions || (here.kids?.size ?: 0) > 0) break
            val parent = here.parent ?: break
            parent.kids?.remove(here.segment)
            if (parent.kids?.size == 0) parent.kids = null
            watchers.remove(here)
            current = parent
        }
    }

    private fun normalize(config: TagConfig): TagConfig {
        val path = TagPath.parse(config.path, name).resolve(null)
        if (path.segments.isEmpty() && config.type != TagType.FOLDER) throw TagConfigException("tag path is empty")
        val shown = display(TagPath(name, path.segments))
        if (config.provider == name && config.path == shown) return config
        return config.copy(provider = name, path = shown)
    }

    private fun locate(path: TagPath, create: Boolean, leafAlias: String? = null): Dir? {
        var dir = root
        val segs = path.segments
        val last = segs.lastIndex
        for (i in segs.indices) {
            val seg = if (i == last && leafAlias != null && segs[i] == leafAlias) leafAlias else segs[i]
            dir = dir.child(seg, create) ?: return null
        }
        return dir
    }

    private fun basePath(path: TagPath): TagPath =
        if (path.indexes.isEmpty() && path.property == null) path else path.copy(indexes = emptyList(), property = null)

    private fun nodeAt(path: TagPath): Node? = locate(basePath(path), create = false)?.node

    private fun nodeByKey(key: String): Node? {
        val parts = key.split('\u0000')
        if (parts.size < 2 || !parts[0].equals(name, true)) return null
        var dir = root
        for (i in 1 until parts.size) {
            if (parts[i].isEmpty()) continue
            dir = dir.child(parts[i], create = false) ?: return null
        }
        return dir.node
    }

    private fun track(node: Node) {
        if (node.liveIndex >= 0) return
        node.liveIndex = live.size
        live.add(node)
    }

    private fun untrack(node: Node) {
        val index = node.liveIndex
        if (index < 0) return
        val last = live.removeAt(live.lastIndex)
        if (index < live.size) {
            live[index] = last
            last.liveIndex = index
        }
        node.liveIndex = -1
    }

    private fun adoptEarly(dir: Dir, key: String) {
        val early = earlyWatchers.remove(key) ?: return
        val list = watchers[dir]
        if (list == null) watchers[dir] = early else list.addAll(early)
        refreshListeners(dir)
    }

    private object MissingSample : TagSample {
        override fun update(value: Any?, quality: Quality, sourceEpochMillis: Long) {}
        override fun updateAt(value: Any?, quality: Quality, source: Instant?) {}
        override fun set(value: Any?, at: Instant?) = MEMORY_WRITE_NOT_FOUND
        override fun read() = QualifiedValue(null, Quality.BAD_NOT_FOUND, Instant.EPOCH)
    }

    private inner class DirectSample(private val node: Node) : TagSample {
        override fun set(value: Any?, at: Instant?): WriteResult = setMemory(node, value, at)
        override fun update(value: Any?, quality: Quality, sourceEpochMillis: Long) {
            updateNode(node, value, quality, sourceEpochMillis, sourceEpochMillis != TagSample.NO_SOURCE, null)
        }
        override fun updateAt(value: Any?, quality: Quality, source: Instant?) {
            updateNode(node, value, quality, source?.toEpochMilli() ?: TagSample.NO_SOURCE, source != null, source)
        }
        override fun touch() {
            synchronized(node) {
                if (!node.alive || node.commsLost || node.lastSampleMillis == Long.MIN_VALUE) return
                if (node.qv.quality.level != Level.GOOD) return
                node.lastSampleMillis = engine.clock.millis()
            }
        }
        override fun read(): QualifiedValue {
            if (!node.alive) return QualifiedValue(null, Quality.BAD_NOT_FOUND, engine.clock.nowMicros())
            return node.qv
        }
        override fun mayRead(caller: TagCaller): Boolean = readAdmits(node.config.readAccess, caller)
        override fun read(caller: TagCaller): QualifiedValue {
            if (node.alive && !mayRead(caller)) return accessDenied()
            return read()
        }
    }

    private class Dir(var segment: String, val parent: Dir?) {
        var node: Node? = null
        var kids: Kids? = null
        /** Next child of [parent], in insertion order. */
        var next: Dir? = null

        fun child(segment: String, create: Boolean): Dir? {
            kids?.find(segment)?.let { return it }
            if (!create) return null
            val map = kids ?: Kids().also { kids = it }
            val created = Dir(segment, this)
            map.add(segment, created)
            return created
        }

        fun toPath(provider: String): TagPath {
            if (parent == null) return TagPath(provider, emptyList())
            val segs = ArrayList<String>()
            var cursor: Dir? = this
            while (cursor != null && cursor.parent != null) {
                segs.add(cursor.segment)
                cursor = cursor.parent
            }
            segs.reverse()
            return TagPath(provider, segs)
        }
    }

    private fun resolveAgainst(raw: String, base: TagPath): TagPath {
        val parsed = TagPath.parse(raw, base.provider ?: name)
        return parsed.resolve(base)
    }

    private fun baseKey(path: TagPath) = path.copy(indexes = emptyList(), property = null).canonicalKey()

    private fun pathIsReadOnly(path: TagPath) = readOnly

    private fun display(path: TagPath): String {
        val segs = path.segments
        if (segs.size == 1) return segs[0]
        return segs.joinToString("/")
    }

    private class Snapshot(node: Node) {
        val qv = node.qv
        val seed = node.seed
        val valueSet = node.config.valueSet
        val dataType = node.config.dataType
        val type = node.config.type
    }

    /**
     * Expression, UDT, and scaling state. Null on a plain device tag so those fields are not
     * reserved on every node.
     */
    private class Cold {
        var lastRawWrite: Any? = null
        var beforeWrite: QualifiedValue? = null
        @Volatile var scanOverride: Long? = null
        var filterState: Double? = null
        var expr: Expr? = null
        var params: Map<String, Any?> = emptyMap()
        var generated: Boolean = false
        var owner: String? = null
        var seed: Any? = null
        var depKeys: Set<String> = emptySet()
        var deps: MutableList<AutoCloseable>? = null
        /** Tags this node reads, after [indexReads]. Empty on a plain memory or device tag. */
        var reads: List<TagPath> = emptyList()
    }

    private class Node(
        var dir: Dir,
        @Volatile var config: TagConfig,
        @Volatile var qv: QualifiedValue = QualifiedValue(null, Quality.UNCERTAIN_INITIAL_VALUE, Instant.EPOCH),
        /** Epoch millis of the last real sample. [Long.MIN_VALUE] means the silence timer is disarmed. */
        @Volatile var lastSampleMillis: Long = Long.MIN_VALUE,
        @Volatile var commsLost: Boolean = false,
        var originKind: String? = null,
        var originName: String? = null,
        @Volatile var alive: Boolean = true,
        var liveIndex: Int = -1,
    ) {
        private var cold: Cold? = null
        private fun edit(): Cold = cold ?: Cold().also { cold = it }

        var lastRawWrite: Any?
            get() = cold?.lastRawWrite
            set(value) { if (value == null) cold?.lastRawWrite = null else edit().lastRawWrite = value }
        /** The tag's value before its outstanding device writes; restored when one fails. */
        var beforeWrite: QualifiedValue?
            get() = cold?.beforeWrite
            set(value) { if (value == null) cold?.beforeWrite = null else edit().beforeWrite = value }
        /** The driver's current read period for this OPC tag; see [StandardTagProvider.setDeviceScanRate]. */
        var scanOverride: Long?
            get() = cold?.scanOverride
            set(value) { if (value == null) cold?.scanOverride = null else edit().scanOverride = value }
        var filterState: Double?
            get() = cold?.filterState
            set(value) { if (value == null) cold?.filterState = null else edit().filterState = value }
        var expr: Expr?
            get() = cold?.expr
            set(value) { if (value == null) cold?.expr = null else edit().expr = value }
        var params: Map<String, Any?>
            get() = cold?.params ?: emptyMap()
            set(value) { if (value.isEmpty()) cold?.params = emptyMap() else edit().params = value }
        var generated: Boolean
            get() = cold?.generated ?: false
            set(value) { if (!value) cold?.generated = false else edit().generated = true }
        var owner: String?
            get() = cold?.owner
            set(value) { if (value == null) cold?.owner = null else edit().owner = value }
        var seed: Any?
            get() = cold?.seed
            set(value) { if (value == null) cold?.seed = null else edit().seed = value }
        var depKeys: Set<String>
            get() = cold?.depKeys ?: emptySet()
            set(value) { if (value.isEmpty()) cold?.depKeys = emptySet() else edit().depKeys = value }
        var deps: MutableList<AutoCloseable>?
            get() = cold?.deps
            set(value) { if (value == null) cold?.deps = null else edit().deps = value }
        var reads: List<TagPath>
            get() = cold?.reads ?: emptyList()
            set(value) { if (value.isEmpty()) cold?.reads = emptyList() else edit().reads = value }

        /** Snapshot of [watchers] for this folder. Empty until something subscribes. */
        @Volatile var listeners: Array<Sub> = NO_SUBS

        /**
         * Changes not yet handed to listeners, three entries each: listeners, previous, value. Guarded by
         * this node's monitor, like [delivering]. Null until the tag first changes with a subscriber.
         */
        var changes: java.util.ArrayDeque<Any>? = null

        /** A thread is in [deliver] for this tag; a new change joins [changes] and that thread hands it out. */
        var delivering: Boolean = false

        /**
         * Deadband counters (TAG-003). Written only while this node's monitor is held, so a diagnostics
         * read under the same monitor sees a consistent pair. Not volatile and not atomic: the monitor
         * is the publish path's existing lock, and these increments allocate nothing.
         */
        var deadbandEvaluated: Long = 0
        var deadbandPublished: Long = 0

        val path: TagPath get() = dir.toPath(config.provider)
        fun copyFor(value: QualifiedValue): Node {
            val copy = Node(dir, config, value)
            val extra = cold
            if (extra != null && extra.params.isNotEmpty()) copy.params = extra.params
            return copy
        }

        companion object {
            val NO_SUBS = emptyArray<Sub>()
        }
    }

    /**
     * Children of one folder. Open addressing instead of a [LinkedHashMap] entry per child.
     * [Dir.next] keeps insertion order for browse.
     */
    private class Kids {
        private var keys = arrayOfNulls<String>(8)
        private var vals = arrayOfNulls<Dir>(8)
        private var mask = 7
        private var tomb = 0
        var size = 0
        var first: Dir? = null
        private var last: Dir? = null

        fun find(segment: String): Dir? {
            val key = fold(segment)
            var slot = key.hashCode() and mask
            val start = slot
            while (true) {
                val found = keys[slot] ?: return null
                if (found !== TOMB && found.equals(key, ignoreCase = true)) return vals[slot]
                slot = (slot + 1) and mask
                if (slot == start) return null
            }
        }

        fun add(segment: String, dir: Dir) {
            if ((size + tomb) * 2 >= keys.size) rehash()
            val key = fold(segment)
            var slot = key.hashCode() and mask
            var tombSlot = -1
            while (true) {
                val found = keys[slot]
                when {
                    found == null -> {
                        val dest = if (tombSlot >= 0) tombSlot else slot
                        if (tombSlot >= 0) tomb--
                        keys[dest] = key
                        vals[dest] = dir
                        break
                    }
                    found === TOMB && tombSlot < 0 -> tombSlot = slot
                    found !== TOMB && found.equals(key, ignoreCase = true) -> {
                        vals[slot] = dir
                        return
                    }
                }
                slot = (slot + 1) and mask
            }
            size++
            dir.next = null
            if (last == null) first = dir else last!!.next = dir
            last = dir
        }

        fun remove(segment: String) {
            val key = fold(segment)
            var slot = key.hashCode() and mask
            val start = slot
            while (true) {
                val found = keys[slot] ?: return
                if (found !== TOMB && found.equals(key, ignoreCase = true)) {
                    val dir = vals[slot]
                    keys[slot] = TOMB
                    vals[slot] = null
                    size--
                    tomb++
                    if (dir != null) unlink(dir)
                    if (size == 0) {
                        keys = arrayOfNulls(8)
                        vals = arrayOfNulls(8)
                        mask = 7
                        tomb = 0
                        first = null
                        last = null
                    }
                    return
                }
                slot = (slot + 1) and mask
                if (slot == start) return
            }
        }

        private fun unlink(dir: Dir) {
            if (first === dir) {
                first = dir.next
                if (last === dir) last = null
                dir.next = null
                return
            }
            var prev = first
            while (prev != null && prev.next !== dir) prev = prev.next
            if (prev != null) {
                prev.next = dir.next
                if (last === dir) last = prev
            }
            dir.next = null
        }

        private fun rehash() {
            val oldKeys = keys
            val oldVals = vals
            val cap = oldKeys.size * 2
            keys = arrayOfNulls(cap)
            vals = arrayOfNulls(cap)
            mask = cap - 1
            tomb = 0
            for (i in oldKeys.indices) {
                val key = oldKeys[i] ?: continue
                if (key === TOMB) continue
                var slot = key.hashCode() and mask
                while (keys[slot] != null) slot = (slot + 1) and mask
                keys[slot] = key
                vals[slot] = oldVals[i]
            }
        }

        companion object {
            private const val TOMB = "\u0000"
            private fun fold(segment: String): String {
                for (i in segment.indices) {
                    val c = segment[i]
                    if (c in 'A'..'Z') return segment.lowercase()
                }
                return segment
            }
        }
    }

    private data class Sub(val path: TagPath, val listener: (TagChange) -> Unit)
    private data class Piece(val config: TagConfig, val params: Map<String, Any?>, val root: Boolean, val error: String?)
    private data class Sourced(val value: Any?, val quality: Quality, val path: TagPath?)
}

/** One [Instant] per millisecond per scan thread, so a poll does not allocate a timestamp per tag. */
internal object ScanTime {
    private class Slot(var epochMillis: Long = Long.MIN_VALUE, var instant: Instant? = null)
    private val slots = ThreadLocal.withInitial { Slot() }

    fun instant(epochMillis: Long): Instant {
        val slot = slots.get()
        val cached = slot.instant
        if (slot.epochMillis == epochMillis && cached != null) return cached
        val created = Instant.ofEpochMilli(epochMillis)
        slot.epochMillis = epochMillis
        slot.instant = created
        return created
    }
}

private val SHARED_INTS = Array(4096) { Integer.valueOf(it) }

/** One boxed int for the common register range, so each tag does not keep its own Integer. */
private fun sharedInt(value: Any?): Any? = when (value) {
    is Int -> if (value in SHARED_INTS.indices) SHARED_INTS[value] else value
    is Short -> {
        val n = value.toInt()
        if (n in SHARED_INTS.indices) SHARED_INTS[n] else value
    }
    is Byte -> {
        val n = value.toInt()
        if (n in SHARED_INTS.indices) SHARED_INTS[n] else value
    }
    else -> value
}

internal class TypeFail(message: String) : RuntimeException(message)

internal fun coerce(value: Any?, type: DataType): Any? {
    if (value == null) return null
    if (type != DataType.DATASET && value is List<*>) return value.map { coerce(it, type) }
    return when (type) {
        DataType.INT32 -> when (value) {
            is Int -> value
            is Short -> value.toInt()
            is Byte -> value.toInt()
            else -> {
                val n = asDouble(value) ?: throw TypeFail("cannot convert '$value' to Int32")
                if (n < Int.MIN_VALUE || n > Int.MAX_VALUE) throw TypeFail("'$value' is outside Int32")
                n.toInt()
            }
        }
        DataType.INT64 -> asDouble(value)?.toLong() ?: throw TypeFail("cannot convert '$value' to Int64")
        DataType.FLOAT -> asDouble(value)?.toFloat() ?: throw TypeFail("cannot convert '$value' to Float")
        DataType.DOUBLE -> asDouble(value) ?: throw TypeFail("cannot convert '$value' to Double")
        DataType.BOOLEAN -> truth(value)
        DataType.STRING -> stringify(value)
        DataType.DATETIME -> when (value) {
            is Instant -> value.micros()
            is Number -> Instant.ofEpochMilli(value.toLong()).micros()
            is String -> try {
                Instant.parse(value).micros()
            } catch (e: Exception) {
                throw TypeFail("cannot convert '$value' to DateTime")
            }
            else -> throw TypeFail("cannot convert '$value' to DateTime")
        }
        DataType.DOCUMENT -> when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            else -> throw TypeFail("cannot convert '$value' to Document")
        }
        DataType.DATASET -> Dataset.parse(value)
    }
}

private fun zero(type: DataType): Any? = when (type) {
    DataType.INT32 -> 0
    DataType.INT64 -> 0L
    DataType.FLOAT -> 0f
    DataType.DOUBLE -> 0.0
    DataType.BOOLEAN -> false
    DataType.STRING -> ""
    DataType.DATETIME -> null
    DataType.DOCUMENT -> emptyMap<String, Any?>()
    DataType.DATASET -> Dataset(emptyList(), emptyList())
}

private val PARAM_REF = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")

private fun subst(text: String, params: Map<String, Any?>): String =
    PARAM_REF.replace(text) { m ->
        val key = m.groupValues[1]
        if (params.containsKey(key)) stringify(params[key]) else m.value
    }

private fun substSpec(spec: MutableMap<String, Any?>, params: Map<String, Any?>) {
    // Query text is bound later. Substituting a UDT parameter here would splice its value into the SQL.
    val skip = setOf("expression", "readExpression", "writeExpression", "query")
    for ((k, v) in spec.toMap()) {
        if (k in skip) continue
        spec[k] = substValue(v, params, skip)
    }
}

private fun substValue(value: Any?, params: Map<String, Any?>, skip: Set<String>): Any? = when (value) {
    is String -> subst(value, params)
    is Map<*, *> -> value.entries.associate { it.key.toString() to substValue(it.value, params, skip) }
    is List<*> -> value.map { substValue(it, params, skip) }
    else -> value
}
