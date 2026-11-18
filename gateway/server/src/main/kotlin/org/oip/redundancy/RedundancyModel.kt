package org.oip.redundancy

/**
 * Two-node redundancy roles and the activity a node reports.
 *
 * Activity is undecided until the node has chosen, then cold, warm, or active.
 * Cold and warm are standby levels. Active is the node currently in control.
 *
 */
enum class NodeRole { INDEPENDENT, MASTER, BACKUP;

    fun wire(): String = when (this) {
        INDEPENDENT -> "Independent"
        MASTER -> "Master"
        BACKUP -> "Backup"
    }

    companion object {
        fun fromWire(raw: String?): NodeRole = when (raw?.trim()?.lowercase()) {
            "master" -> MASTER
            "backup" -> BACKUP
            "independent" -> INDEPENDENT
            else -> INDEPENDENT
        }
    }
}

enum class ActivityLevel { UNDECIDED, COLD, WARM, ACTIVE;

    val active: Boolean get() = this == ACTIVE

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(raw: String?): ActivityLevel = when (raw?.trim()?.lowercase()) {
            "cold" -> COLD
            "warm" -> WARM
            "active" -> ACTIVE
            "undecided" -> UNDECIDED
            else -> UNDECIDED
        }

        fun standby(level: StandbyLevel): ActivityLevel = if (level == StandbyLevel.COLD) COLD else WARM
    }
}

enum class RecoveryMode { AUTOMATIC, MANUAL;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(raw: String?): RecoveryMode = when (raw?.trim()?.lowercase()) {
            "manual" -> MANUAL
            else -> AUTOMATIC
        }
    }
}

enum class StandbyLevel { COLD, WARM;

    companion object {
        fun fromWire(raw: String?): StandbyLevel = when (raw?.trim()?.lowercase()) {
            "cold" -> COLD
            else -> WARM
        }
    }
}

enum class HistoryMode { FULL, PARTIAL;

    companion object {
        fun fromWire(raw: String?): HistoryMode = when (raw?.trim()?.lowercase()) {
            "full" -> FULL
            else -> PARTIAL
        }
    }
}

/**
 * What a standby node is allowed to do.
 * Cold connects but does not subscribe. Warm subscribes and does not write to devices or log history.
 * Active does all three. Undecided matches cold so a node does not touch devices before it has chosen.
 *
 */
data class RuntimeGate(val subscriptions: Boolean, val deviceWrites: Boolean, val history: Boolean) {
    companion object {
        val ACTIVE = RuntimeGate(subscriptions = true, deviceWrites = true, history = true)
        val WARM = RuntimeGate(subscriptions = true, deviceWrites = false, history = false)
        val COLD = RuntimeGate(subscriptions = false, deviceWrites = false, history = false)

        fun forActivity(level: ActivityLevel): RuntimeGate = when (level) {
            ActivityLevel.ACTIVE -> ACTIVE
            ActivityLevel.WARM -> WARM
            ActivityLevel.COLD, ActivityLevel.UNDECIDED -> COLD
        }
    }
}

/**
 * Role, activity, and gate a gateway service can read.
 * [active] is true on an independent gateway and on the node currently in control.
 */
data class RedundancySnapshot(
    val role: NodeRole,
    val activity: ActivityLevel,
    val gate: RuntimeGate,
    val active: Boolean,
)

/**
 * Called when activity changes.
 *
 * The runtime invokes this off the heartbeat thread, after it has released the redundancy
 * lock and without holding the tag-engine lock. Listeners run in registration order, and changes
 * arrive in the order they happened: `previous` is the `current` of the call before.
 * A throw from one listener does not skip the rest. The body may call back into the runtime.
 * A listener added with replay first gets the state at subscribe time, with `previous == current`.
 */
fun interface RedundancyListener {
    fun onChange(previous: RedundancySnapshot, current: RedundancySnapshot)
}

data class PeerSnapshot(
    val role: NodeRole,
    val activity: ActivityLevel,
    val activeUptimeMillis: Long,
    val nodeId: String,
    val configRevision: Long,
    val runtimeRevision: Long,
    val command: String,
    val recovery: RecoveryMode,
    val splitBrain: Boolean,
)

data class RedundancyState(
    val role: NodeRole,
    val activity: ActivityLevel,
    val standby: StandbyLevel,
    val recovery: RecoveryMode,
    val connected: Boolean = false,
    val peer: PeerSnapshot? = null,
    val missed: Int = 0,
    val pingMaxMissed: Int = 30,
    val splitBrain: Boolean = false,
    val becameActiveWithoutPeer: Boolean = false,
    val command: String = "",
    val yielding: Boolean = false,
) {
    /** True when this node is the one in control. An independent gateway is active. */
    val isActive: Boolean get() = role == NodeRole.INDEPENDENT || activity == ActivityLevel.ACTIVE

    /**
     * True only when this node is the master and it is the active node.
     * False while a backup is in control.
     */
    val isMaster: Boolean get() = role == NodeRole.MASTER && activity == ActivityLevel.ACTIVE

    fun standbyActivity(): ActivityLevel = ActivityLevel.standby(standby)
}

sealed class RedundancyEvent {
    data object Started : RedundancyEvent()
    data object AllowanceElapsed : RedundancyEvent()
    data class HeartbeatOk(val peer: PeerSnapshot) : RedundancyEvent()
    data object HeartbeatMissed : RedundancyEvent()
    data object LinkLost : RedundancyEvent()
    data object AssumeControl : RedundancyEvent()
    data object RequestFailover : RedundancyEvent()
}

/**
 * One master-active interval. [endMillis] of 0 means the master is still active.
 * History collected inside an interval was not "while the master was down".
 */
data class ActiveWindow(val startMillis: Long, val endMillis: Long) {
    fun covers(timestampMillis: Long): Boolean =
        timestampMillis >= startMillis && (endMillis == 0L || timestampMillis < endMillis)
}

/**
 * Pure redundancy decisions. The runtime feeds events. It does not decide them itself.
 *
 */
fun reduce(state: RedundancyState, event: RedundancyEvent): RedundancyState {
    if (state.role == NodeRole.INDEPENDENT) {
        return state.copy(activity = ActivityLevel.ACTIVE, connected = false, peer = null, command = "", splitBrain = false)
    }
    return when (event) {
        RedundancyEvent.Started -> state
        RedundancyEvent.AllowanceElapsed -> onAllowance(state)
        is RedundancyEvent.HeartbeatOk -> onHeartbeat(state, event.peer)
        RedundancyEvent.HeartbeatMissed -> {
            val missed = state.missed + 1
            if (missed >= state.pingMaxMissed) onLinkLost(state.copy(missed = missed)) else state.copy(missed = missed)
        }
        RedundancyEvent.LinkLost -> onLinkLost(state)
        RedundancyEvent.AssumeControl -> if (state.role != NodeRole.MASTER) state else state.copy(
            activity = ActivityLevel.ACTIVE,
            command = CMD_DEACTIVATE,
            splitBrain = false,
            yielding = false,
        )
        RedundancyEvent.RequestFailover -> onFailover(state)
    }
}

/**
 * Keep a sample when it was not collected while the master was active.
 * Partial history forwards only the samples taken while the master was truly down.
 */
fun historyWhileMasterDown(timestampMillis: Long, windows: List<ActiveWindow>): Boolean =
    windows.none { it.covers(timestampMillis) }

/**
 * Default is master-wins. With active-uptime enabled, the node that has been
 * active longer keeps its runtime values.
 *
 */
fun backupWinsConflict(masterUptimeMillis: Long, backupUptimeMillis: Long, useActiveUptime: Boolean): Boolean =
    useActiveUptime && backupUptimeMillis > masterUptimeMillis

private const val CMD_ACTIVATE = "activate"
private const val CMD_DEACTIVATE = "deactivate"

private fun onAllowance(state: RedundancyState): RedundancyState {
    if (state.activity != ActivityLevel.UNDECIDED) return state
    return when (state.role) {
        NodeRole.MASTER -> state.copy(
            activity = ActivityLevel.ACTIVE,
            becameActiveWithoutPeer = state.peer == null,
        )
        NodeRole.BACKUP -> state.copy(activity = ActivityLevel.ACTIVE)
        NodeRole.INDEPENDENT -> state
    }
}

private fun onHeartbeat(state: RedundancyState, peer: PeerSnapshot): RedundancyState {
    val base = state.copy(connected = true, peer = peer, missed = 0)
    return if (state.role == NodeRole.MASTER) masterSees(base, peer) else backupSees(base, peer)
}

private fun masterSees(state: RedundancyState, peer: PeerSnapshot): RedundancyState {
    if (peer.command == CMD_ACTIVATE) {
        return state.copy(activity = ActivityLevel.ACTIVE, splitBrain = false, command = CMD_DEACTIVATE, yielding = false)
    }
    if (state.yielding && peer.activity != ActivityLevel.ACTIVE) {
        return state.copy(activity = state.standbyActivity(), command = CMD_ACTIVATE, splitBrain = false)
    }
    if (state.yielding && peer.activity == ActivityLevel.ACTIVE) {
        return state.copy(yielding = false, command = "")
            .let { masterSees(it, peer) }
    }
    val peerActive = peer.activity == ActivityLevel.ACTIVE
    if (!state.activity.active && !state.becameActiveWithoutPeer && state.recovery == RecoveryMode.MANUAL && peerActive) {
        return state.copy(activity = state.standbyActivity(), command = "", splitBrain = false)
    }
    val take = state.activity.active || state.becameActiveWithoutPeer || state.recovery == RecoveryMode.AUTOMATIC || !peerActive
    if (!take) return state.copy(activity = state.standbyActivity(), command = "", splitBrain = false)
    return state.copy(
        activity = ActivityLevel.ACTIVE,
        splitBrain = false,
        command = if (peerActive) CMD_DEACTIVATE else "",
        yielding = false,
    )
}

private fun backupSees(state: RedundancyState, peer: PeerSnapshot): RedundancyState {
    if (peer.command == CMD_DEACTIVATE) {
        return state.copy(activity = state.standbyActivity(), splitBrain = false, command = "", yielding = false)
    }
    if (peer.command == CMD_ACTIVATE || state.yielding) {
        return state.copy(activity = ActivityLevel.ACTIVE, splitBrain = false, command = CMD_DEACTIVATE, yielding = false)
    }
    val masterActive = peer.activity == ActivityLevel.ACTIVE
    if (masterActive && peer.recovery == RecoveryMode.AUTOMATIC) {
        return state.copy(activity = state.standbyActivity(), splitBrain = false, command = "")
    }
    if (state.activity.active) {
        return state.copy(activity = ActivityLevel.ACTIVE, splitBrain = masterActive, command = "")
    }
    return state.copy(activity = state.standbyActivity(), splitBrain = false, command = "")
}

private fun onLinkLost(state: RedundancyState): RedundancyState {
    val peerWasActive = state.peer?.activity == ActivityLevel.ACTIVE
    val base = state.copy(connected = false, yielding = false)
    return when (state.role) {
        NodeRole.BACKUP -> base.copy(activity = ActivityLevel.ACTIVE, splitBrain = peerWasActive || state.splitBrain)
        NodeRole.MASTER -> if (state.activity.active) {
            base.copy(splitBrain = state.splitBrain || peerWasActive)
        } else {
            base.copy(
                activity = ActivityLevel.ACTIVE,
                splitBrain = peerWasActive,
                becameActiveWithoutPeer = state.becameActiveWithoutPeer || state.peer == null,
            )
        }
        NodeRole.INDEPENDENT -> state
    }
}

private fun onFailover(state: RedundancyState): RedundancyState {
    if (state.activity.active) {
        return state.copy(activity = state.standbyActivity(), command = CMD_ACTIVATE, yielding = true, splitBrain = false)
    }
    return state.copy(activity = ActivityLevel.ACTIVE, command = CMD_DEACTIVATE, yielding = false, splitBrain = false)
}
