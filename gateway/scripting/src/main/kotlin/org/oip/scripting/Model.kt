package org.oip.scripting

import java.time.Duration
import java.util.logging.Logger

/**
 * Where a script is allowed to run.
 *
 *
 * Every scope runs on the gateway runtime with the same project library snapshot (SCR-042): gateway events, tag
 * event scripts, SFC steps, event streams and web resources run as [GATEWAY]; HMI script hooks (transforms and event
 * scripts) run as [SESSION] for the session user; the designer script console runs as [DESIGNER]. No scope runs in
 * the browser.
 */
enum class ScriptScope { GATEWAY, SESSION, DESIGNER }

enum class EventType {
    STARTUP, SHUTDOWN, TIMER, TAG_CHANGE, SCHEDULED, MESSAGE;

    companion object {
        fun fromName(raw: String): EventType? = when (raw.trim()) {
            "startup" -> STARTUP
            "shutdown" -> SHUTDOWN
            "timer" -> TIMER
            "tagChange" -> TAG_CHANGE
            "scheduled" -> SCHEDULED
            "message" -> MESSAGE
            else -> null
        }
    }

    fun wire(): String = when (this) {
        STARTUP -> "startup"
        SHUTDOWN -> "shutdown"
        TIMER -> "timer"
        TAG_CHANGE -> "tagChange"
        SCHEDULED -> "scheduled"
        MESSAGE -> "message"
    }
}

/** [CANCELLED]: the run's owner withdrew it (an HMI session ended or its call was given up). */
enum class ScriptFailure { TIMEOUT, MEMORY, STATEMENTS, OUTPUT, SANDBOX, ERROR, CANCELLED }

/**
 * Per-run ceilings. A script that crosses one is cancelled so it cannot hold the tag engine.
 *
 * [maxStatements] is the Graal statement budget (CPU). [timeout] is wall-clock time.
 * [maxAllocatedBytes] is bytes allocated on the script thread after the prelude, measured
 * from the HotSpot thread allocation counter.
 */
data class ScriptLimits(
    val timeout: Duration = Duration.ofSeconds(10),
    val maxStatements: Long = 1_000_000,
    val maxAllocatedBytes: Long = 64L * 1024 * 1024,
    val maxOutputBytes: Int = 64 * 1024,
) {
    init {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        require(maxStatements > 0) { "maxStatements must be positive" }
        require(maxAllocatedBytes > 0) { "maxAllocatedBytes must be positive" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
    }
}

data class ScriptResult(
    val ok: Boolean,
    val value: Any?,
    val output: String,
    val error: String?,
    val failure: ScriptFailure?,
    /** Thread that entered the user script. Tag-change runs are `oip-script-tag-*`. */
    val thread: String,
    /**
     * True when the user script began while this thread held the tag engine write lock.
     * A tag-change script must observe false: the listener only enqueues (P2-14).
     */
    val heldEngineWriteLock: Boolean,
    val millis: Long,
)

/** Project-library or gateway-event script after config defaults are applied. */
data class EventScript(
    val name: String,
    val type: EventType,
    val source: String,
    val enabled: Boolean = true,
    val delayMillis: Long = 1_000,
    val delayType: String = "fixedDelay",
    val threading: String = "shared",
    val tagPaths: List<String> = emptyList(),
    val triggers: Set<String> = setOf("value", "quality", "timestamp"),
    val cron: String = "",
    val limits: ScriptLimits = ScriptLimits(),
)

fun interface ScriptLogger {
    fun log(level: String, logger: String, message: String)

    companion object {
        val JUL = ScriptLogger { level, name, message ->
            val log = Logger.getLogger("oip.script.$name")
            when (level) {
                "TRACE" -> log.finest(message)
                "DEBUG" -> log.fine(message)
                "INFO" -> log.info(message)
                "WARN" -> log.warning(message)
                "ERROR", "FATAL" -> log.severe(message)
                else -> log.info(message)
            }
        }
    }
}

/** Logger thresholds. A message is kept when its rank is at least the effective threshold. */
class LogLevels {
    private val levels = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun set(name: String, level: String) {
        require(name.isNotBlank()) { "logger name is required" }
        levels[name] = rank(level)
    }

    fun threshold(name: String): Int {
        var cursor = name
        while (true) {
            levels[cursor]?.let { return it }
            val dot = cursor.lastIndexOf('.')
            if (dot < 0) return rank("INFO")
            cursor = cursor.substring(0, dot)
        }
    }

    /** True when a record at [level] is stored. */
    fun allows(name: String, level: String): Boolean = rank(level) >= threshold(name)

    /** True when the logger will emit [level] (threshold is that verbose or more). */
    fun emits(name: String, level: String): Boolean = threshold(name) <= rank(level)

    companion object {
        private val ORDER = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")

        fun rank(level: String): Int {
            val idx = ORDER.indexOf(level.trim().uppercase())
            require(idx >= 0) { "unknown log level '$level'" }
            return idx
        }
    }
}
