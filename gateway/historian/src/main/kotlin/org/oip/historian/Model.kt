package org.oip.historian

import org.oip.tags.Deadband
import org.oip.tags.DeadbandMode
import org.oip.tags.Quality
import org.oip.tags.TagPath

/**
 * Tag history sample modes. Reference:
 *
 *  - [ON_CHANGE]: a sample is considered whenever the tag reports a change; the row keeps the
 *    timestamp the value arrived with.
 *  - [PERIODIC]: a sample is considered on a fixed timer, once every [HistorySettings.sampleRateMillis];
 *    the row is stamped with the time the timer fired.
 *
 * Sampling driven by a shared scan group is not in this slice.
 */
enum class HistorySampleMode(val wire: String) {
    ON_CHANGE("onChange"),
    PERIODIC("periodic");

    companion object {
        fun fromWire(text: String): HistorySampleMode =
            entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
                ?: throw IllegalArgumentException("sampleMode must be one of ${entries.joinToString { it.wire }}")
    }
}

/**
 * Per-tag history collection settings, stored as config resource kind [HISTORY_SETTINGS_KIND] in module
 * [HISTORIAN_MODULE] (see [HistoryConfigBinding]).
 *
 * Store rule ([HistoryCollector.shouldStore]), applied each time a sample is evaluated:
 *  1. The first sample for a tag is stored.
 *  2. A change of quality is stored.
 *  3. Deadband off: any sample with a newer timestamp than the last row is kept (reference: the
 *     configuring-tag-history page above). An on-change event carries a new value timestamp and a periodic
 *     sample is stamped with the sample time, so on-change stores every change and periodic stores one row
 *     per period whether or not the value moved. A value change that shares the previous row's
 *     millisecond is stored too.
 *  4. Deadband absolute or percent (compared against the raw value, with no smoothing or slope logic):
 *     stored only when the value differs from the last *stored* value by at least the deadband.
 *     This applies to periodic mode as well, so a periodic evaluation whose value has not moved past the
 *     deadband stores nothing. Percent uses the previous value as the span in this slice (see
 *     [Deadband.suppresses]); the tag's engineering span is not looked up yet.
 *  5. [maxTimeBetweenSamplesMillis] > 0: once that long has passed since the last stored row, the next
 *     sample is kept even when the deadband would have filtered it out. Reference:
 *     -
 *     That reference measures the limit in scan executions; this slice measures milliseconds.
 *
 * [storageProvider] names where this tag's rows are written (see the configuring-tag-history page above).
 * [LOCAL_PROVIDER] is the built-in SQLite store, written directly. Any other name is a
 * `historian/HistoryProvider` resource (for example Postgres/Timescale) and its rows go through
 * store-and-forward ([HistoryRouter]).
 */
data class HistorySettings(
    val tagPath: String,
    val sampleMode: HistorySampleMode = HistorySampleMode.ON_CHANGE,
    val deadband: Deadband = Deadband.OFF,
    val sampleRateMillis: Long = 1000,
    val maxTimeBetweenSamplesMillis: Long = 0,
    val storageProvider: String = LOCAL_PROVIDER,
) {
    init {
        require(PROVIDER_NAME.matches(storageProvider)) { "storageProvider must be a provider name (lowercase alphanumeric and '-')" }
        require(tagPath.isNotBlank()) { "tagPath must not be blank" }
        require(sampleRateMillis > 0) { "sampleRateMillis must be > 0" }
        require(maxTimeBetweenSamplesMillis >= 0) { "maxTimeBetweenSamplesMillis must be >= 0" }
        require(deadband.value >= 0) { "deadband.value must be >= 0" }
    }

    /** [tagPath] with its provider filled in: the form rows are stored and queried under. */
    val normalizedPath: String = normalizeTagPath(tagPath)

    companion object {
        /** Build from a [HISTORY_SETTINGS_KIND] spec that already passed schema validation. */
        fun fromSpec(spec: Map<String, Any?>): HistorySettings {
            val db = spec["deadband"] as? Map<*, *>
            val mode = when ((db?.get("mode") as? String)?.lowercase()) {
                null, "off" -> DeadbandMode.OFF
                "absolute" -> DeadbandMode.ABSOLUTE
                "percent" -> DeadbandMode.PERCENT
                else -> throw IllegalArgumentException("deadband.mode must be off, absolute, or percent")
            }
            val dbValue = (db?.get("value") as? Number)?.toDouble() ?: 0.0
            return HistorySettings(
                tagPath = spec["tagPath"] as? String ?: throw IllegalArgumentException("tagPath is required"),
                sampleMode = HistorySampleMode.fromWire(spec["sampleMode"] as? String ?: HistorySampleMode.ON_CHANGE.wire),
                deadband = if (mode == DeadbandMode.OFF) Deadband.OFF else Deadband(mode, dbValue),
                sampleRateMillis = (spec["sampleRateMillis"] as? Number)?.toLong() ?: 1000,
                maxTimeBetweenSamplesMillis = (spec["maxTimeBetweenSamplesMillis"] as? Number)?.toLong() ?: 0,
                storageProvider = spec["storageProvider"] as? String ?: LOCAL_PROVIDER,
            )
        }
    }
}

private const val DEFAULT_TAG_PROVIDER = "default"

/** The built-in history provider: SQLite at `<dataDir>/db/history.sqlite`, written without store-and-forward. */
const val LOCAL_PROVIDER = "local"

/** History provider names follow config resource names (DNS-label style). */
val PROVIDER_NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/** `Plant/Speed` and `[default]Plant/Speed` are the same tag; rows use the provider-qualified form. */
fun normalizeTagPath(text: String): String {
    val parsed = TagPath.parse(text, DEFAULT_TAG_PROVIDER)
    require(parsed.relative == null && parsed.segments.isNotEmpty()) { "tagPath must be an absolute tag path: '$text'" }
    return parsed.toString()
}

/** One stored history row: a tag value at a point in time, with its quality at capture. */
data class HistorySample(
    val tagPath: String,
    val timestampMillis: Long,
    val value: Any?,
    val quality: Quality,
)

/** Inclusive time range query against stored history for one tag, oldest first. */
data class HistoryQuery(
    val tagPath: String,
    val fromMillis: Long,
    val toMillis: Long,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(fromMillis <= toMillis) { "from must not be after to" }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT = 10_000
        const val MAX_LIMIT = 100_000
    }
}
