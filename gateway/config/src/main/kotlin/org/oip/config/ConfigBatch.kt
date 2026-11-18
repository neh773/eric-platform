package org.oip.config

import java.util.concurrent.CopyOnWriteArrayList

/**
 * One logical config write that spans many resources (POST /api/v1/config/batch).
 * Listeners still see one [ConfigEvent] per resource. Audit and other batch-aware
 * subscribers record the whole write once, when [ConfigBatches.run] returns.
 */
data class ConfigBatch(
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val actor: String,
    val scope: String,
    /** True when the audit profile was enabled for any event in the batch, including the write that turns it off. */
    val record: Boolean,
    /** Audit action for the whole write, or null for the generic config batch action. */
    val action: String? = null,
    /** Audit target for the whole write, or null for `config/batch`. */
    val target: String? = null,
    /** Audit detail for the whole write, or null for the change counts. */
    val detail: String? = null,
) {
    val size: Int get() = created + updated + deleted
}

/**
 * Thread-local batch scope. [FileConfigStore.putAll] opens it around the per-resource events
 * so a subscriber can fold them. Nested batches roll their counts into the outer one.
 */
object ConfigBatches {
    private val current = ThreadLocal<Open?>()
    private val finishers = CopyOnWriteArrayList<(ConfigBatch) -> Unit>()

    class Open {
        var created: Int = 0
        var updated: Int = 0
        var deleted: Int = 0
        var actor: String = "system"
        var scope: String = ""
        var record: Boolean = false
        var action: String? = null
        var target: String? = null
        var detail: String? = null

        fun note(type: ChangeType) {
            when (type) {
                ChangeType.CREATED -> created++
                ChangeType.UPDATED -> updated++
                ChangeType.DELETED -> deleted++
                ChangeType.INVALID -> {}
            }
        }
    }

    fun current(): Open? = current.get()

    /**
     * Run [block] as one logical write. [action], [target] and [detail] name the write in its single audit record
     * (a project rename, copy or delete); null keeps the generic batch record.
     */
    fun <T> run(
        scope: String,
        actor: String,
        action: String? = null,
        target: String? = null,
        detail: String? = null,
        block: () -> T,
    ): T {
        val open = Open().also { it.actor = actor; it.scope = scope; it.action = action; it.target = target; it.detail = detail }
        val prev = current.get()
        current.set(open)
        try {
            return block()
        } finally {
            current.set(prev)
            if (prev != null) {
                prev.created += open.created
                prev.updated += open.updated
                prev.deleted += open.deleted
                prev.record = prev.record || open.record
            } else if (open.created + open.updated + open.deleted > 0) {
                val batch = ConfigBatch(
                    open.created, open.updated, open.deleted, open.actor, open.scope, open.record,
                    open.action, open.target, open.detail,
                )
                for (finisher in finishers) runCatching { finisher(batch) }
            }
        }
    }

    fun onFinished(listener: (ConfigBatch) -> Unit): AutoCloseable {
        finishers += listener
        return AutoCloseable { finishers -= listener }
    }
}
