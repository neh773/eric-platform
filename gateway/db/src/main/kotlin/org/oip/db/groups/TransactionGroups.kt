package org.oip.db.groups

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs `TransactionGroup` resources. Each enabled group gets its own single-thread scheduler that ticks
 * every `execution.rateMillis`; a trigger, when set, decides whether a tick executes.
 *
 * Nothing here is called from a tag-engine callback: groups poll tag values on their own thread, so a
 * slow or failing database only delays that group (P2-14). A failed run marks the group in error and the
 * next tick tries again.
 *
 * Each tick first asks [activity] whether this node is active. On a standby node of a redundant pair the
 * tick does nothing and the group reports state `Standby`; it runs again as soon as the node is active.
 * [setNodeActive] is the push side of the same rule: going standby stops every group's scheduler, and going
 * active starts them again. It only records the change and hands the work to the `txgroup-control` thread,
 * so a redundancy listener can call it without doing any group or database work itself (P2-14).
 *
 * Overview: -
 */
class TransactionGroups(
    private val store: FileConfigStore,
    private val tags: GroupTags,
    private val database: GroupDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val activity: GroupActivity = GroupActivity.ALWAYS,
    /** Where hour and event meter totals are kept (DAT-007). */
    private val meters: MeterStore = MeterStore.memory(),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(TransactionGroups::class.java)
    private val groups = ConcurrentHashMap<String, LiveGroup>()
    private var subscription: AutoCloseable? = null
    @Volatile private var running = false
    /** Set by [setNodeActive]. While true no group has a scheduler. */
    @Volatile private var standby = false
    private val control = Executors.newSingleThreadExecutor { r ->
        Thread(r, "txgroup-control").apply { isDaemon = true }
    }

    /** Register the schema, load saved groups, and follow config changes. Does not start any thread. */
    fun attach() {
        if (!store.registry.isRegistered(GROUP_MODULE, GROUP_KIND)) GroupSpecs.register(store.registry)
        store.revalidate(GROUP_MODULE).forEach { (id, reason) -> log.warn("transaction group {} is invalid: {}", id.name, reason) }
        subscription = store.subscribe { event -> onEvent(event) }
        for (stored in store.list(GROUP_MODULE, Scope.GATEWAY, GROUP_KIND)) apply(stored.id.name, stored.resource)
    }

    /** Start the per-group schedulers. Groups configured later start as they arrive. */
    fun start() {
        running = true
        for (group in groups.values) schedule(group)
    }

    /**
     * Follow this node's redundancy activity. Inactive stops every group's scheduler (a run already in
     * progress finishes; it is not interrupted mid-transaction) and reports `Standby`. Active starts them again.
     * Returns at once: the start or stop runs on `txgroup-control`, and changes are applied in call order.
     */
    fun setNodeActive(active: Boolean) {
        standby = !active
        try {
            control.execute { reconcile() }
        } catch (_: RejectedExecutionException) {
            // Closed: nothing to start or stop.
        }
    }

    private fun reconcile() {
        if (!running) return
        for (group in groups.values) if (standby) pause(group) else schedule(group)
    }

    override fun close() {
        running = false
        control.shutdownNow()
        subscription?.close()
        subscription = null
        for (group in groups.values) { group.stop(); group.runner?.flushMeters() }
    }

    fun list(): List<Map<String, Any?>> = groups.values.map { status(it) }.sortedBy { it["name"] as String }

    fun status(name: String): Map<String, Any?> = status(live(name))

    /**
     * Enable or disable by writing the resource, so the change is audited like any config write.
     * Returns false when the group was already in that state and nothing was written.
     */
    fun setEnabled(name: String, enabled: Boolean, actor: String = "api"): Boolean {
        val id = ResourceId(GROUP_MODULE, Scope.GATEWAY, GROUP_KIND, name)
        val current = store.get(id) ?: throw GroupNotFound("transaction group '$name' not found")
        if ((current.spec["enabled"] as? Boolean ?: true) == enabled) return false
        val spec = LinkedHashMap(current.spec)
        spec["enabled"] = enabled
        store.put(Scope.GATEWAY, Resource(GROUP_KIND, Metadata(name, GROUP_MODULE, version = current.metadata.version), spec), actor)
        return true
    }

    /**
     * Run one tick now on the caller's thread, serialised with the scheduler. Used by tests and by an
     * operator who wants to check a group without waiting for its rate.
     */
    fun runOnce(name: String): RunOutcome = tick(live(name))

    /** Test hook: called on the group's thread after tags are read and before the database is used. */
    fun beforeDatabase(name: String, hook: (() -> Unit)?) {
        live(name).runner?.beforeDatabase = hook
    }

    private fun live(name: String): LiveGroup = groups[name] ?: throw GroupNotFound("transaction group '$name' not found")

    private fun onEvent(event: ConfigEvent) {
        val id = event.id ?: return
        if (id.module != GROUP_MODULE || id.scope != Scope.GATEWAY || id.kind != GROUP_KIND) return
        when (event.type) {
            ChangeType.DELETED, ChangeType.INVALID -> groups.remove(id.name)?.also { it.stop(); it.runner?.flushMeters() }
            ChangeType.CREATED, ChangeType.UPDATED -> event.resource?.let { apply(id.name, it) }
        }
    }

    private fun apply(name: String, resource: Resource) {
        groups[name]?.runner?.flushMeters()
        val fresh = LiveGroup(name, resource.metadata.version)
        try {
            val spec = GroupSpecs.parse(store.registry.applyDefaults(GROUP_MODULE, GROUP_KIND, resource.spec))
            fresh.spec = spec
            fresh.state = if (spec.enabled) "Stopped" else "Disabled"
            fresh.runner = GroupRunner(name, spec, tags, database, clock, meters)
        } catch (e: GroupRejected) {
            fresh.state = "Invalid"
            fresh.error = e.message
        } catch (e: RuntimeException) {
            fresh.state = "Invalid"
            fresh.error = "group could not be read"
            log.warn("transaction group {} could not be read ({})", name, e.javaClass.simpleName)
        }
        groups.put(name, fresh)?.stop()
        if (running) schedule(fresh)
    }

    private fun schedule(group: LiveGroup): Unit = synchronized(group) {
        val spec = group.spec ?: return
        if (group.stopped) return
        if (!spec.enabled) {
            group.state = "Disabled"
            return
        }
        if (standby) {
            group.state = STANDBY
            return
        }
        if (group.executor != null) return
        group.state = "Running"
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "txgroup-${group.name}").apply { isDaemon = true }
        }
        group.executor = executor
        executor.scheduleWithFixedDelay({ runCatching { tick(group) } }, 0, spec.rateMillis, TimeUnit.MILLISECONDS)
    }

    /** Stop the scheduler without retiring the group, so [schedule] can start it again. */
    private fun pause(group: LiveGroup): Unit = synchronized(group) {
        group.executor?.shutdown()
        group.executor = null
        if (group.spec?.enabled == true) group.state = STANDBY
    }

    private fun tick(group: LiveGroup): RunOutcome {
        val runner = group.runner ?: throw GroupRejected(group.error ?: "transaction group is invalid")
        return group.lock.withLock {
            if (group.stopped) return RunOutcome(executed = false)
            if (!nodeActive()) {
                if (group.spec?.enabled == true) group.state = STANDBY
                return RunOutcome(executed = false)
            }
            if (group.state == STANDBY) group.state = idleState(group)
            val started = System.nanoTime()
            try {
                val outcome = runner.tick()
                if (outcome.executed) {
                    group.lastRunMillis = clock.millis()
                    group.lastDurationMillis = (System.nanoTime() - started) / 1_000_000L
                    group.rowsWritten = outcome.rowsWritten
                    group.totalRowsWritten += outcome.rowsWritten
                    group.executions++
                    group.warning = outcome.warning
                    group.error = null
                    group.errorMillis = null
                    group.state = idleState(group)
                }
                outcome
            } catch (e: Exception) {
                group.lastRunMillis = clock.millis()
                group.lastDurationMillis = (System.nanoTime() - started) / 1_000_000L
                group.rowsWritten = 0
                group.errors++
                group.error = (e as? GroupRunFailed)?.message ?: (e.message ?: e.javaClass.simpleName).take(300)
                group.errorMillis = clock.millis()
                group.state = "Error"
                log.warn("transaction group {} failed: {}", group.name, group.error)
                RunOutcome(executed = true, warning = group.error)
            }
        }
    }

    private fun idleState(group: LiveGroup): String = when {
        standby && group.spec?.enabled == true -> STANDBY
        group.executor != null -> "Running"
        group.spec?.enabled == true -> "Stopped"
        else -> "Disabled"
    }

    private fun nodeActive(): Boolean = try {
        !standby && activity.isActive()
    } catch (e: RuntimeException) {
        log.warn("transaction groups could not read node activity ({}); skipping this run", e.javaClass.simpleName)
        false
    }

    private fun status(group: LiveGroup): Map<String, Any?> {
        val spec = group.spec
        return linkedMapOf(
            "name" to group.name,
            "version" to group.version,
            "enabled" to (spec?.enabled ?: false),
            "state" to group.state,
            "groupType" to spec?.groupType?.wire,
            "updateMode" to spec?.updateMode?.wire,
            "tableAction" to spec?.tableAction?.wire,
            "connection" to spec?.connection,
            "table" to spec?.table,
            "rateMillis" to spec?.rateMillis,
            "trigger" to spec?.trigger?.tag,
            "lastRunMillis" to group.lastRunMillis,
            "lastDurationMillis" to group.lastDurationMillis,
            "rowsWritten" to group.rowsWritten,
            "totalRowsWritten" to group.totalRowsWritten,
            "executions" to group.executions,
            "errors" to group.errors,
            "error" to group.error,
            "errorMillis" to group.errorMillis,
            "warning" to group.warning,
        )
    }

    private companion object {
        const val STANDBY = "Standby"
    }

    private class LiveGroup(val name: String, val version: Long) {
        val lock = ReentrantLock()
        @Volatile var spec: GroupSpec? = null
        @Volatile var runner: GroupRunner? = null
        @Volatile var executor: ScheduledExecutorService? = null
        @Volatile var stopped = false
        @Volatile var state: String = "Stopped"
        @Volatile var lastRunMillis: Long? = null
        @Volatile var lastDurationMillis: Long? = null
        @Volatile var rowsWritten: Int = 0
        @Volatile var totalRowsWritten: Long = 0
        @Volatile var executions: Long = 0
        @Volatile var errors: Long = 0
        @Volatile var error: String? = null
        @Volatile var errorMillis: Long? = null
        @Volatile var warning: String? = null

        /** Stop scheduling. A run already in progress finishes on its own; it is not interrupted mid-transaction. */
        @Synchronized fun stop() {
            stopped = true
            executor?.shutdown()
            executor = null
        }
    }
}
