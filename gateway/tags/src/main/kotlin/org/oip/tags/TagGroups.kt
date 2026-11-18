package org.oip.tags

import org.oip.tags.expr.EvalContext
import org.oip.tags.expr.Expressions
import org.oip.tags.expr.Value
import org.oip.tags.expr.truth
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tag group scan mode (TAG-006), see
 * - [DIRECT] (TAG-008): one fixed rate.
 * - [DRIVEN] (TAG-023): [TagGroupDef.rateMillis] while [TagGroupDef.drivingExpression] is true,
 *   [TagGroupDef.slowRateMillis] (or [TagGroupDef.rateMillis] if unset) otherwise. The comparison is
 *   part of the boolean expression (`{Line/Running} > 0`) rather than a separate operator/value pair.
 * - [LEASED] (TAG-025): [TagGroupDef.rateMillis] while at least one subscriber leases the item,
 *   [TagGroupDef.slowRateMillis] otherwise; no slow rate means the item is not read at all.
 */
enum class GroupMode { DIRECT, DRIVEN, LEASED }

data class TagGroupDef(
    val name: String,
    val mode: GroupMode = GroupMode.DIRECT,
    val rateMillis: Long = 1000,
    val slowRateMillis: Long? = null,
    val drivingExpression: String? = null,
) {
    companion object {
        val DEFAULT = TagGroupDef(DEFAULT_GROUP)

        fun fromSpec(name: String, spec: Map<String, Any?>): TagGroupDef {
            val mode = when ((spec["mode"] as? String)?.lowercase() ?: "direct") {
                "direct" -> GroupMode.DIRECT
                "driven" -> GroupMode.DRIVEN
                "leased" -> GroupMode.LEASED
                else -> throw IllegalArgumentException("unknown tag group mode '${spec["mode"]}'")
            }
            val driving = spec["drivingExpression"] as? String
            if (mode == GroupMode.DRIVEN && driving.isNullOrBlank()) throw IllegalArgumentException("driven tag group '$name' needs a drivingExpression")
            return TagGroupDef(
                name = name,
                mode = mode,
                rateMillis = (spec["rateMillis"] as? Number)?.toLong() ?: 1000L,
                slowRateMillis = (spec["slowRateMillis"] as? Number)?.toLong(),
                drivingExpression = driving,
            )
        }
    }
}

const val DEFAULT_GROUP = "default"

/**
 * Live tag groups by name. [DEFAULT_GROUP] always exists and cannot be removed, so a tag that leaves
 * `tagGroup` unset always resolves. [TagConfigBinding] keeps this in step with `kind: TagGroup`
 * resources through [TagEngine.configureGroup] and [TagEngine.removeGroup].
 */
class TagGroupRegistry {
    private val groups = ConcurrentHashMap<String, TagGroupDef>().apply { this[DEFAULT_GROUP] = TagGroupDef.DEFAULT }

    internal fun put(def: TagGroupDef) {
        groups[def.name] = def
    }

    internal fun remove(name: String) {
        if (name != DEFAULT_GROUP) groups.remove(name)
    }

    /** Null when no group of that name exists right now. */
    fun get(name: String): TagGroupDef? = groups[name]

    /**
     * The group to schedule by. A missing group (never defined, or deleted while tags still use it)
     * schedules at [DEFAULT_GROUP]'s rate so a driver keeps reading the item; the tag itself shows
     * [QualityCode.ERROR_CONFIGURATION] until the group exists again (see [TagEngine.removeGroup]).
     */
    fun resolve(name: String): TagGroupDef = groups[name] ?: groups[DEFAULT_GROUP] ?: TagGroupDef.DEFAULT
}

/**
 * Tells a tag source which items are due, at each item's tag group's current rate (TAG-006). Items
 * are keyed by a caller-chosen string (for engine tags, [TagEngine.subscriptionKey]). There is no
 * thread or sleep per item: the caller ticks [due] on its own timer (a fake [Clock] in tests) and this
 * class does the rate and mode arithmetic. The gateway's tag driver bridge reads [ratesFor] for each OPC tag that
 * names a tag group and re-subscribes the device item when the rate moves (review-2 F3).
 *
 * Locking: [lease] does not take this object's monitor, because [TagEngine] calls it (via [observe])
 * while holding the engine lock, and [due] holds the monitor while [evalDriving] reads tags.
 */
class TagGroupSchedule(
    private val groups: TagGroupRegistry,
    private val clock: Clock = Clock.systemUTC(),
    /** Truth value of a DRIVEN group's [TagGroupDef.drivingExpression]. Defaults to false (slow rate). */
    private val evalDriving: (expression: String) -> Boolean = { false },
) {
    private class ItemState(@Volatile var group: String) {
        var lastRun: Long = Long.MIN_VALUE
        @Volatile var leased: Boolean = false
    }

    private val items = ConcurrentHashMap<String, ItemState>()
    private val lastRate = HashMap<String, Long>()
    private val rateListeners = CopyOnWriteArrayList<(String, Long) -> Unit>()

    /** Register [key] under [group], or move it to another group. */
    @Synchronized
    fun assign(key: String, group: String) {
        items.getOrPut(key) { ItemState(group) }.group = group
    }

    @Synchronized
    fun unassign(key: String) {
        items.remove(key)
        lastRate.remove(key)
    }

    /** LEASED (TAG-025): whether at least one subscriber currently leases [key]. */
    fun lease(key: String, leased: Boolean) {
        items[key]?.leased = leased
    }

    /**
     * Drive [lease] from [engine]'s subscription counts, keyed by [TagEngine.subscriptionKey].
     * The engine has one listener slot, so one schedule observes a given engine.
     */
    fun observe(engine: TagEngine) {
        engine.subscriptionCountListener = { key, count -> lease(key, count > 0) }
    }

    /** Current rate per key; [OFF_RATE] means "do not read". Unknown keys are omitted. */
    @Synchronized
    fun ratesFor(keys: Collection<String>): Map<String, Long> {
        val driven = HashMap<String, Boolean>()
        val out = LinkedHashMap<String, Long>()
        for (key in keys) {
            val state = items[key] ?: continue
            out[key] = rateFor(groups.resolve(state.group), state, driven)
        }
        return out
    }

    /**
     * Keys whose rate has elapsed as of [now], in no particular order; marks them run. The first call
     * for a key always returns it. Fires [onRateChanged] for each key whose rate moved since the
     * previous call. Each driven group's expression is evaluated once per call, not once per item.
     */
    @Synchronized
    fun due(now: Instant = clock.instant()): List<String> {
        val nowMillis = now.toEpochMilli()
        val driven = HashMap<String, Boolean>()
        val out = ArrayList<String>()
        for ((key, state) in items) {
            val rate = rateFor(groups.resolve(state.group), state, driven)
            val previous = lastRate.put(key, rate)
            if (previous != null && previous != rate) rateListeners.forEach { it(key, rate) }
            if (rate <= OFF_RATE) continue
            if (state.lastRun == Long.MIN_VALUE || nowMillis - state.lastRun >= rate) {
                state.lastRun = nowMillis
                out += key
            }
        }
        return out
    }

    fun onRateChanged(listener: (key: String, rateMillis: Long) -> Unit): AutoCloseable {
        rateListeners += listener
        return AutoCloseable { rateListeners -= listener }
    }

    private fun rateFor(def: TagGroupDef, state: ItemState, driven: MutableMap<String, Boolean>): Long = when (def.mode) {
        GroupMode.DIRECT -> def.rateMillis
        GroupMode.DRIVEN -> {
            val active = driven.getOrPut(def.name) { def.drivingExpression?.let { runCatching { evalDriving(it) }.getOrDefault(false) } ?: false }
            if (active) def.rateMillis else (def.slowRateMillis ?: def.rateMillis)
        }
        GroupMode.LEASED -> if (state.leased) def.rateMillis else (def.slowRateMillis ?: OFF_RATE)
    }

    companion object {
        const val OFF_RATE = 0L

        /**
         * A driven group's condition evaluated against [engine]'s tags: true when the expression's result is Good
         * and truthy (`{Line/Running} > 0`). Any error or non-Good result is false (the slow rate).
         */
        fun drivingCondition(engine: TagEngine): (String) -> Boolean = { text ->
            val result = Expressions.eval(text, EvalContext(ref = { raw -> engine.read(raw).let { Value(it.value, it.quality) } }))
            result.quality.level == Level.GOOD && truth(result.value)
        }
    }
}

/**
 * Per-group execution counters for tag diagnostics (TAG-003).
 *
 * [record] runs when a tag-group expression evaluation finishes ([TagEngine.tickExpressionGroups]).
 * It is not on the value publish path. An overrun is a later execution that arrived more than one
 * period after the previous one (the missed slots) or an execution that lasted longer than the rate.
 * Device scans are counted by the drivers, which this class does not see.
 */
class TagGroupActivity {
    class Counters {
        var lastExecutionMillis: Long = Long.MIN_VALUE
        var durationNanos: Long = -1L
        var overruns: Long = 0L
    }

    private val byName = ConcurrentHashMap<String, Counters>()

    fun record(name: String, atMillis: Long, durationNanos: Long, rateMillis: Long) {
        val counters = byName.computeIfAbsent(name) { Counters() }
        synchronized(counters) {
            val previous = counters.lastExecutionMillis
            var extra = 0L
            if (previous != Long.MIN_VALUE && rateMillis > 0) {
                val gap = atMillis - previous
                if (gap > rateMillis) {
                    val missed = gap / rateMillis - 1
                    if (missed > 0) extra += missed
                }
            }
            if (rateMillis > 0 && durationNanos > rateMillis * 1_000_000L) extra += 1
            if (extra > 0) counters.overruns += extra
            counters.durationNanos = durationNanos
            counters.lastExecutionMillis = atMillis
        }
    }

    /** A copy, or null when [name] has not executed. Safe to read off the ticker thread. */
    fun snapshot(name: String): Counters? {
        val counters = byName[name] ?: return null
        return synchronized(counters) {
            Counters().also {
                it.lastExecutionMillis = counters.lastExecutionMillis
                it.durationNanos = counters.durationNanos
                it.overruns = counters.overruns
            }
        }
    }
}
