package org.oip.scripting

import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.tags.QualifiedValue
import org.oip.tags.TagChange
import org.oip.tags.TagEngine
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Date

/**
 * Tag event scripts (`scripting/TagEvent`): value change, quality change, and alarm transitions.
 * The script runs off the tag thread. The sample already present when the script is armed is not an event.
 */
class TagEventScripts(
    private val tags: TagEngine,
    private val run: (source: String, name: String, eventJson: String) -> ScriptResult,
) {
    private val json = ObjectMapper()
    private val scripts = ConcurrentHashMap<String, TagEventScript>()
    private val arms = ConcurrentHashMap<String, Arm>()
    @Volatile var running = false

    fun replace(script: TagEventScript) {
        scripts[script.name] = script
        if (running) arm(script)
    }

    fun remove(name: String) {
        scripts.remove(name)
        arms.remove(name)?.close()
    }

    fun start() {
        running = true
        for (script in scripts.values) arm(script)
    }

    fun stop() {
        running = false
        for (arm in arms.values) arm.close()
        arms.clear()
    }

    /** [alarmEvent] is the alarm event object scripts read as `event.alarm` (ALA-034): state, value, times and config. */
    fun onAlarm(source: String, alarm: String, trigger: String, value: Any?, alarmEvent: Map<String, Any?>? = null) {
        if (!running) return
        for (script in scripts.values) {
            if (!script.enabled || script.event != "alarm") continue
            if (!script.tagPath.equals(source, ignoreCase = true)) continue
            if (script.alarmName.isNotEmpty() && !script.alarmName.equals(alarm, ignoreCase = true)) continue
            val arm = arms[script.name] ?: continue
            val body = json.writeValueAsString(
                mapOf(
                    "initialChange" to false,
                    "executionCount" to arm.count.getAndIncrement(),
                    "newValue" to mapOf("value" to jsonValue(value), "quality" to "Good", "timestamp" to System.currentTimeMillis()),
                    "previousValue" to null,
                    "tagPath" to source,
                    "changes" to listOf("Alarm"),
                    "alarmName" to alarm,
                    "trigger" to trigger,
                    "alarm" to alarmEvent,
                ),
            )
            arm.offer(script, body)
        }
    }

    private fun arm(script: TagEventScript) {
        arms.remove(script.name)?.close()
        if (!script.enabled) return
        val arm = Arm(script.name)
        arms[script.name] = arm
        if (script.event == "alarm") return
        arm.sub = if (org.oip.tags.TagPathPattern.isPattern(script.tagPath)) {
            org.oip.tags.PatternSubscription(tags, script.tagPath) { change -> onChange(script, arm, change) }
        } else {
            tags.subscribe(script.tagPath) { change -> onChange(script, arm, change) }
        }
    }

    private fun onChange(script: TagEventScript, arm: Arm, change: TagChange) {
        val previous = change.previous ?: return
        val next = change.value
        val hit = when (script.event) {
            "value" -> next.value != previous.value
            "quality" -> next.quality != previous.quality
            else -> false
        }
        if (!hit) return
        val changes = ArrayList<String>(2)
        if (next.value != previous.value) changes += "ValueChange"
        if (next.quality != previous.quality) changes += "QualityChange"
        val body = json.writeValueAsString(
            mapOf(
                "initialChange" to false,
                "executionCount" to arm.count.getAndIncrement(),
                "newValue" to qv(next),
                "previousValue" to qv(previous),
                "tagPath" to change.path.toString(),
                "changes" to changes,
                "alarmName" to "",
                "trigger" to "",
            ),
        )
        arm.offer(script, body)
    }

    private fun qv(value: QualifiedValue): Map<String, Any?> = mapOf(
        "value" to jsonValue(value.value),
        "quality" to value.quality.code.label,
        "timestamp" to value.timestamp.toEpochMilli(),
    )

    private fun jsonValue(value: Any?): Any? = when (value) {
        is Instant -> value.toEpochMilli()
        is Date -> value.time
        else -> value
    }

    private inner class Arm(val name: String) : AutoCloseable {
        val count = AtomicInteger()
        private val queue = ArrayBlockingQueue<String>(64)
        private val busy = AtomicBoolean(false)
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "oip-tag-event-$name").apply { isDaemon = true }
        }
        @Volatile var sub: AutoCloseable? = null

        fun offer(script: TagEventScript, eventJson: String) {
            if (!queue.offer(eventJson)) return
            worker.execute { drain(script) }
        }

        private fun drain(script: TagEventScript) {
            if (!busy.compareAndSet(false, true)) return
            try {
                while (true) {
                    val eventJson = queue.poll() ?: break
                    if (!running) break
                    runCatching { run(script.source, script.name, eventJson) }
                }
            } finally {
                busy.set(false)
                if (running && queue.isNotEmpty()) worker.execute { drain(script) }
            }
        }

        override fun close() {
            sub?.let { runCatching { it.close() } }
            worker.shutdownNow()
        }
    }
}

data class TagEventScript(
    val name: String,
    val tagPath: String,
    val event: String,
    val alarmName: String,
    val source: String,
    val enabled: Boolean,
)
