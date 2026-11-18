package org.oip.tags

import java.time.Instant

/**
 * One other tag, or a view binding, named from tag diagnostics (TAG-003).
 *
 * [kind] is `expression`, `derived`, `reference`, or `binding` for something that reads this tag,
 * and the referenced tag's type for a tag this one reads.
 */
data class TagLink(val path: String, val kind: String)

/**
 * The tag's tag group and that group's execution counters. [lastExecution] and [durationMillis]
 * stay null until a tag-group expression evaluation has run. [overruns] counts missed periods and
 * executions that outlasted the rate. [valid] is false when no group of [name] is configured.
 */
data class TagGroupDiagnostics(
    val name: String,
    val mode: String,
    val rateMillis: Long,
    val slowRateMillis: Long?,
    val drivingExpression: String?,
    val valid: Boolean,
    val lastExecution: Instant?,
    val durationMillis: Double?,
    val overruns: Long,
)

/**
 * Deadband counters. [evaluated] is how many values reached the deadband check. [published] is how
 * many of those were stored. Both move only under the tag's own monitor, with no allocation.
 */
data class DeadbandDiagnostics(
    val mode: String,
    val limit: Double,
    val evaluated: Long,
    val published: Long,
)

/**
 * Read-only diagnostics for one tag (TAG-003).
 *
 * [referencesIn] lists expression, derived and reference tags that read this tag. The gateway route
 * also appends view bindings. [referencesOut] lists the tags this tag reads.
 */
data class TagDiagnostics(
    val path: String,
    val type: String,
    val value: Any?,
    val quality: String,
    val timestamp: Instant,
    val sourceTimestamp: Instant?,
    val message: String?,
    val subscriberCount: Int,
    val referencesIn: List<TagLink>,
    val referencesOut: List<TagLink>,
    val tagGroup: TagGroupDiagnostics,
    val deadband: DeadbandDiagnostics,
)
