package org.oip.scripting

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-script execution counters for the Diagnostics scripts table (GAT-029).
 *
 * One row per script name: how many runs finished, the running count right now, last/average/max wall
 * time, the last failure, and the last few log lines the script (or a logger carrying its name) wrote.
 * Counters are lock-free; the table is capped so a script that makes up names cannot grow it without bound.
 */
class ScriptStats(private val maxScripts: Int = 512, private val recentLines: Int = 5) {
    private class Row {
        val running = AtomicInteger()
        val runs = AtomicLong()
        val failures = AtomicLong()
        val totalMillis = AtomicLong()
        val maxMillis = AtomicLong()
        @Volatile var lastMillis = 0L
        @Volatile var lastError: String? = null
        @Volatile var lastFinishedAt = 0L
        val lines = ArrayDeque<String>()
    }

    private val rows = ConcurrentHashMap<String, Row>()

    private fun row(name: String): Row? {
        rows[name]?.let { return it }
        if (rows.size >= maxScripts) return null
        return rows.computeIfAbsent(name) { Row() }
    }

    fun begin(name: String) {
        row(name)?.running?.incrementAndGet()
    }

    fun end(name: String, millis: Long, error: String?, nowMillis: Long = System.currentTimeMillis()) {
        val r = row(name) ?: return
        r.running.updateAndGet { if (it > 0) it - 1 else 0 }
        r.runs.incrementAndGet()
        if (error != null) r.failures.incrementAndGet()
        r.totalMillis.addAndGet(millis)
        r.maxMillis.accumulateAndGet(millis) { a, b -> maxOf(a, b) }
        r.lastMillis = millis
        r.lastError = error
        r.lastFinishedAt = nowMillis
    }

    fun line(name: String, level: String, message: String) {
        val r = rows[name] ?: return
        val text = "$level ${if (message.length > 300) message.substring(0, 300) else message}"
        synchronized(r.lines) {
            r.lines.addLast(text)
            while (r.lines.size > recentLines) r.lines.removeFirst()
        }
    }

    /** Wraps [delegate] so every log line is also kept against the logger name. */
    fun wrap(delegate: ScriptLogger): ScriptLogger = ScriptLogger { level, name, message ->
        line(name, level, message)
        delegate.log(level, name, message)
    }

    /** Rows with at least one run or a run in flight, busiest first. [typeOf] names the script's event type. */
    fun snapshot(typeOf: (String) -> String? = { null }): List<Map<String, Any?>> =
        rows.entries.filter { it.value.runs.get() > 0 || it.value.running.get() > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Row>> { it.value.running.get() }.thenBy { it.key })
            .map { (name, r) ->
                val runs = r.runs.get()
                linkedMapOf(
                    "name" to name,
                    "type" to (typeOf(name) ?: "run"),
                    "running" to r.running.get(),
                    "executions" to runs,
                    "failures" to r.failures.get(),
                    "lastMillis" to r.lastMillis,
                    "avgMillis" to if (runs == 0L) 0.0 else r.totalMillis.get().toDouble() / runs,
                    "maxMillis" to r.maxMillis.get(),
                    "lastError" to r.lastError,
                    "lastFinishedAt" to r.lastFinishedAt,
                    "recentLog" to synchronized(r.lines) { r.lines.toList() },
                )
            }
}
