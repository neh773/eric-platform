package org.oip.edgesync

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Edge sync settings from `gateway.yaml`. Each stream is off until enabled.
 * The remote target is an outgoing gateway-network connection name.
 *
 */
data class EdgeSyncSettings(
    val remoteGateway: String = "",
    val history: HistorySyncSettings = HistorySyncSettings(),
    val audit: StreamSyncSettings = StreamSyncSettings(retentionDays = AUDIT_RETENTION_DAYS),
    val alarmJournal: StreamSyncSettings = StreamSyncSettings(retentionDays = AUDIT_RETENTION_DAYS),
) {
    companion object {
        val KEYS = setOf("remoteGateway", "history", "audit", "alarmJournal")
        private val CONNECTION = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?""")

        fun parse(raw: Map<String, Any?>): EdgeSyncSettings {
            val unknown = raw.keys - KEYS
            require(unknown.isEmpty()) { "unknown gateway.yaml edgeSync keys: $unknown" }
            val remote = string(raw["remoteGateway"], "edgeSync.remoteGateway") ?: ""
            require(remote.isEmpty() || CONNECTION.matches(remote)) {
                "edgeSync.remoteGateway must be an outgoing connection name"
            }
            val settings = EdgeSyncSettings(
                remoteGateway = remote,
                history = HistorySyncSettings.parse(mapping(raw["history"], "edgeSync.history")),
                audit = StreamSyncSettings.parse(
                    mapping(raw["audit"], "edgeSync.audit"),
                    "edgeSync.audit",
                    "remoteProfile",
                    AUDIT_RETENTION_DAYS,
                ),
                alarmJournal = StreamSyncSettings.parse(
                    mapping(raw["alarmJournal"], "edgeSync.alarmJournal"),
                    "edgeSync.alarmJournal",
                    "remoteJournal",
                    AUDIT_RETENTION_DAYS,
                ),
            )
            val any = settings.history.enabled || settings.audit.enabled || settings.alarmJournal.enabled
            require(!any || settings.remoteGateway.isNotEmpty()) {
                "edgeSync.remoteGateway is required when a sync stream is enabled"
            }
            return settings
        }
    }
}

data class HistorySyncSettings(
    val enabled: Boolean = false,
    val remoteProvider: String = "",
    val maxBatchSize: Int = DEFAULT_HISTORY_BATCH,
    val syncFrequency: Long = DEFAULT_FREQUENCY,
    val syncFrequencyUnits: SyncUnit = SyncUnit.SECONDS,
    val scheduleEnabled: Boolean = false,
    val schedulePattern: String = "",
    val schedule: SyncSchedule = SyncSchedule.NONE,
    val tagPaths: List<String> = emptyList(),
    val captureAll: Boolean = false,
    val retentionDays: Int = HISTORY_RETENTION_DAYS,
    val maxPoints: Long = HISTORY_MAX_POINTS,
) {
    fun open(nowMillis: Long): Boolean = !scheduleEnabled || schedule.allows(nowMillis)

    fun frequencyMillis(): Long = syncFrequencyUnits.toMillis(syncFrequency)

    companion object {
        val KEYS = setOf(
            "enabled", "remoteProvider", "maxBatchSize", "syncFrequency", "syncFrequencyUnits",
            "scheduleEnabled", "schedulePattern", "tagPaths", "captureAll", "retentionDays", "maxPoints",
        )

        fun parse(raw: Map<String, Any?>): HistorySyncSettings {
            if (raw.isEmpty()) return HistorySyncSettings()
            val unknown = raw.keys - KEYS
            require(unknown.isEmpty()) { "unknown gateway.yaml edgeSync.history keys: $unknown" }
            val enabled = bool(raw["enabled"], "edgeSync.history.enabled") ?: false
            val provider = remoteName(raw["remoteProvider"], "edgeSync.history.remoteProvider")
            require(!enabled || provider.isNotEmpty()) {
                "edgeSync.history.remoteProvider is required when history sync is enabled"
            }
            val pattern = string(raw["schedulePattern"], "edgeSync.history.schedulePattern") ?: ""
            val scheduleEnabled = bool(raw["scheduleEnabled"], "edgeSync.history.scheduleEnabled") ?: false
            return HistorySyncSettings(
                enabled = enabled,
                remoteProvider = provider,
                maxBatchSize = number(raw["maxBatchSize"], "edgeSync.history.maxBatchSize", DEFAULT_HISTORY_BATCH, 1, MAX_HISTORY_BATCH),
                syncFrequency = long(raw["syncFrequency"], "edgeSync.history.syncFrequency", DEFAULT_FREQUENCY, 1, 1_000_000),
                syncFrequencyUnits = SyncUnit.parse(string(raw["syncFrequencyUnits"], "edgeSync.history.syncFrequencyUnits") ?: "seconds"),
                scheduleEnabled = scheduleEnabled,
                schedulePattern = pattern,
                schedule = if (scheduleEnabled || pattern.isNotEmpty()) SyncSchedule.parse(pattern) else SyncSchedule.NONE,
                tagPaths = paths(raw["tagPaths"]),
                captureAll = bool(raw["captureAll"], "edgeSync.history.captureAll") ?: false,
                retentionDays = number(raw["retentionDays"], "edgeSync.history.retentionDays", HISTORY_RETENTION_DAYS, 1, 3_650),
                maxPoints = long(raw["maxPoints"], "edgeSync.history.maxPoints", HISTORY_MAX_POINTS, 1, HISTORY_MAX_POINTS),
            )
        }

        private fun paths(value: Any?): List<String> = when (value) {
            null -> emptyList()
            is List<*> -> {
                require(value.size <= 10_000) { "edgeSync.history.tagPaths is too long" }
                value.map {
                    val path = it as? String ?: throw IllegalArgumentException("edgeSync.history.tagPaths entries must be strings")
                    require(path.isNotBlank() && path.length <= 512) { "edgeSync.history.tagPaths entry is invalid" }
                    path
                }
            }
            else -> throw IllegalArgumentException("edgeSync.history.tagPaths must be a list")
        }
    }
}

data class StreamSyncSettings(
    val enabled: Boolean = false,
    val remoteName: String = "",
    val syncFrequency: Long = DEFAULT_FREQUENCY,
    val syncFrequencyUnits: SyncUnit = SyncUnit.SECONDS,
    val scheduleEnabled: Boolean = false,
    val schedulePattern: String = "",
    val schedule: SyncSchedule = SyncSchedule.NONE,
    val retentionDays: Int = AUDIT_RETENTION_DAYS,
) {
    fun open(nowMillis: Long): Boolean = !scheduleEnabled || schedule.allows(nowMillis)

    fun frequencyMillis(): Long = syncFrequencyUnits.toMillis(syncFrequency)

    companion object {
        fun parse(raw: Map<String, Any?>, label: String, nameKey: String, defaultRetention: Int): StreamSyncSettings {
            if (raw.isEmpty()) return StreamSyncSettings(retentionDays = defaultRetention)
            val keys = setOf(
                "enabled", nameKey, "syncFrequency", "syncFrequencyUnits",
                "scheduleEnabled", "schedulePattern", "retentionDays",
            )
            val unknown = raw.keys - keys
            require(unknown.isEmpty()) { "unknown gateway.yaml $label keys: $unknown" }
            val enabled = bool(raw["enabled"], "$label.enabled") ?: false
            val remote = remoteName(raw[nameKey], "$label.$nameKey")
            require(!enabled || remote.isNotEmpty()) { "$label.$nameKey is required when sync is enabled" }
            val pattern = string(raw["schedulePattern"], "$label.schedulePattern") ?: ""
            val scheduleEnabled = bool(raw["scheduleEnabled"], "$label.scheduleEnabled") ?: false
            return StreamSyncSettings(
                enabled = enabled,
                remoteName = remote,
                syncFrequency = long(raw["syncFrequency"], "$label.syncFrequency", DEFAULT_FREQUENCY, 1, 1_000_000),
                syncFrequencyUnits = SyncUnit.parse(string(raw["syncFrequencyUnits"], "$label.syncFrequencyUnits") ?: "seconds"),
                scheduleEnabled = scheduleEnabled,
                schedulePattern = pattern,
                schedule = if (scheduleEnabled || pattern.isNotEmpty()) SyncSchedule.parse(pattern) else SyncSchedule.NONE,
                retentionDays = number(raw["retentionDays"], "$label.retentionDays", defaultRetention, 1, 3_650),
            )
        }
    }
}

enum class SyncUnit {
    MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS;

    /** A month is 30 days and a year is 365 days. The interval does not follow the calendar. */
    fun toMillis(amount: Long): Long {
        val factor = when (this) {
            MILLISECONDS -> 1L
            SECONDS -> 1_000L
            MINUTES -> 60_000L
            HOURS -> 3_600_000L
            DAYS -> 86_400_000L
            WEEKS -> 7L * 86_400_000L
            MONTHS -> 30L * 86_400_000L
            YEARS -> 365L * 86_400_000L
        }
        return Math.multiplyExact(amount, factor)
    }

    companion object {
        fun parse(raw: String): SyncUnit = when (raw.trim().lowercase()) {
            "millisecond", "milliseconds" -> MILLISECONDS
            "second", "seconds" -> SECONDS
            "minute", "minutes" -> MINUTES
            "hour", "hours" -> HOURS
            "day", "days" -> DAYS
            "week", "weeks" -> WEEKS
            "month", "months" -> MONTHS
            "year", "years" -> YEARS
            else -> throw IllegalArgumentException(
                "sync frequency units must be milliseconds, seconds, minutes, hours, days, weeks, months, or years",
            )
        }
    }
}

data class Window(val startMinute: Int, val endMinute: Int) {
    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}

/**
 * Comma-separated ranges. The end minute is outside the window.
 * `6pm-1am` crosses midnight. Minutes may be omitted. `.` and `:` both split hours from minutes.
 */
data class SyncSchedule(val windows: List<Window>) {
    fun allows(nowMillis: Long, zone: ZoneId = ZoneOffset.UTC): Boolean {
        if (windows.isEmpty()) return false
        val local = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val minute = local.hour * 60 + local.minute
        return windows.any { it.contains(minute) }
    }

    companion object {
        val NONE = SyncSchedule(emptyList())

        fun parse(pattern: String): SyncSchedule {
            require(pattern.length <= 500) { "edgeSync schedule pattern is too long" }
            val windows = ArrayList<Window>()
            for (part in pattern.split(',')) {
                val token = part.trim()
                if (token.isEmpty()) continue
                val dash = token.indexOf('-')
                require(dash > 0 && token.indexOf('-', dash + 1) < 0) { "schedule range '$token' must be start-end" }
                val start = parseTime(token.substring(0, dash).trim())
                val end = parseTime(token.substring(dash + 1).trim())
                windows += Window(start, end)
            }
            return SyncSchedule(windows)
        }

        private fun parseTime(raw: String): Int {
            var text = raw.trim().lowercase()
            require(text.isNotEmpty()) { "schedule time is empty" }
            val ampm = when {
                text.endsWith("am") -> 0
                text.endsWith("pm") -> 1
                else -> null
            }
            if (ampm != null) text = text.dropLast(2).trim()
            text = text.replace('.', ':')
            val parts = text.split(':')
            require(parts.size in 1..2) { "schedule time '$raw' is invalid" }
            val hour = parts[0].toIntOrNull() ?: throw IllegalArgumentException("schedule time '$raw' is invalid")
            val minute = if (parts.size == 1) 0 else parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("schedule time '$raw' is invalid")
            require(minute in 0..59) { "schedule time '$raw' is invalid" }
            if (ampm == null) {
                require(hour in 0..24 && (hour < 24 || minute == 0)) { "schedule time '$raw' is invalid" }
                return hour * 60 + minute
            }
            require(hour in 1..12) { "schedule time '$raw' is invalid" }
            val normalized = when {
                ampm == 0 && hour == 12 -> 0
                ampm == 1 && hour != 12 -> hour + 12
                else -> hour
            }
            return normalized * 60 + minute
        }
    }
}

internal const val HISTORY_RETENTION_DAYS = 35
internal const val HISTORY_MAX_POINTS = 10_000_000L
internal const val AUDIT_RETENTION_DAYS = 7
internal const val DEFAULT_HISTORY_BATCH = 1000
internal const val MAX_HISTORY_BATCH = 2000
internal const val DEFAULT_FREQUENCY = 10L
internal const val RECORD_BATCH = 200

private val REMOTE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}""")

internal fun validRemoteName(text: String): Boolean = text.isNotEmpty() && REMOTE_NAME.matches(text)

private fun remoteName(value: Any?, what: String): String {
    val text = string(value, what) ?: ""
    require(text.isEmpty() || REMOTE_NAME.matches(text)) { "$what is invalid" }
    return text
}

private fun mapping(value: Any?, what: String): Map<String, Any?> = when (value) {
    null -> emptyMap()
    is Map<*, *> -> {
        val out = LinkedHashMap<String, Any?>()
        value.forEach { (key, item) ->
            val name = key as? String ?: throw IllegalArgumentException("$what keys must be strings")
            out[name] = item
        }
        out
    }
    else -> throw IllegalArgumentException("$what must be a mapping")
}

private fun bool(value: Any?, what: String): Boolean? = when (value) {
    null -> null
    is Boolean -> value
    else -> throw IllegalArgumentException("$what must be true or false")
}

private fun number(value: Any?, what: String, default: Int, min: Int, max: Int): Int {
    val n = when (value) {
        null -> return default
        is Number -> value.toInt()
        else -> throw IllegalArgumentException("$what must be a number")
    }
    require(n in min..max) { "$what out of range: $n" }
    return n
}

private fun long(value: Any?, what: String, default: Long, min: Long, max: Long): Long {
    val n = when (value) {
        null -> return default
        is Number -> value.toLong()
        else -> throw IllegalArgumentException("$what must be a number")
    }
    require(n in min..max) { "$what out of range: $n" }
    return n
}

private fun string(value: Any?, what: String): String? = when (value) {
    null -> null
    is String -> value
    else -> throw IllegalArgumentException("$what must be a string")
}
