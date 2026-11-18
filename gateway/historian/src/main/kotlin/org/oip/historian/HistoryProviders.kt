package org.oip.historian

import org.oip.tags.Quality
import org.oip.tags.TagPath
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A provider that only answers reads. A write is refused as permanent, so records routed to it go to the
 * store-and-forward quarantine with the reason instead of being silently discarded.
 */
abstract class ReadOnlyHistoryProvider(final override val name: String, private val kind: String) : RemoteHistoryProvider {
    override fun write(records: List<ForwardRecord>) {
        throw HistoryWriteException("history provider '$name' is a read-only $kind provider and stores nothing", permanent = true)
    }

    override fun query(query: HistoryQuery): List<HistorySample> {
        val out = ArrayList<HistorySample>()
        scanUntil(query.tagPath, query.fromMillis, query.toMillis) { out += it; out.size < query.limit }
        return out
    }

    override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) =
        scanUntil(tagPath, fromMillis, toMillis) { each(it); true }

    /** Rows of [tagPath] in `[from, to]`, oldest first, until [each] returns false. */
    protected abstract fun scanUntil(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Boolean)
}

/**
 * CSV history provider: read-only history imported from one CSV file (doc source: FEATURES HIS-006).
 *
 * File layout (RFC 4180, UTF-8): the header row names the timestamp column first and one tag path per
 * remaining column; each later row is a timestamp followed by that row's value for each tag. An empty cell
 * means the tag has no sample at that time. A tag path without a provider means `[default]`, and paths compare
 * case-insensitively.
 *
 * Timestamps: an ISO-8601 instant (`2026-11-02T08:00:00Z`), a date and time without a zone, read as UTC
 * (`2026-11-02 08:00:00`, optional fraction), or epoch milliseconds. With [relativeTimestamps], only the first
 * row's timestamp is a point in time; every later row holds milliseconds after it.
 *
 * Values: `true` / `false` are booleans, numbers are numbers (whole numbers stay integers), anything else is
 * text. Every sample has Good quality.
 *
 * The file is read on first use and again whenever its size or modification time changes, so a replaced file is
 * picked up without restarting. A file over [maxBytes], or one that does not parse, fails the read with the line
 * number and stores nothing in memory.
 */
class CsvHistoryProvider(
    name: String,
    val file: Path,
    val relativeTimestamps: Boolean = false,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : ReadOnlyHistoryProvider(name, "csv") {
    private class Series(val path: String, val times: LongArray, val values: Array<Any?>)
    private class Loaded(val size: Long, val modified: Long, val series: Map<String, Series>)

    @Volatile private var loaded: Loaded? = null

    override fun scanUntil(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Boolean) {
        val s = data()[key(tagPath)] ?: return
        var i = lowerBound(s.times, fromMillis)
        while (i < s.times.size && s.times[i] <= toMillis) {
            if (!each(HistorySample(s.path, s.times[i], s.values[i], Quality.GOOD))) return
            i++
        }
    }

    override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? {
        val s = data()[key(tagPath)] ?: return null
        val i = lowerBound(s.times, beforeMillis) - 1
        return if (i < 0) null else HistorySample(s.path, s.times[i], s.values[i], Quality.GOOD)
    }

    /** Tag paths the file holds, in column order. */
    fun tagPaths(): List<String> = data().values.map { it.path }

    override fun status(): Map<String, Any?> = linkedMapOf(
        "type" to "csv",
        "file" to file.fileName.toString(),
        "relativeTimestamps" to relativeTimestamps,
        "tags" to (runCatching { data().size }.getOrNull()),
    )

    private fun data(): Map<String, Series> {
        val (size, modified) = try {
            Files.size(file) to Files.getLastModifiedTime(file).toMillis()
        } catch (e: NoSuchFileException) {
            throw HistoryReadException("csv file '${file.fileName}' of history provider '$name' does not exist")
        }
        loaded?.let { if (it.size == size && it.modified == modified) return it.series }
        synchronized(this) {
            loaded?.let { if (it.size == size && it.modified == modified) return it.series }
            if (size > maxBytes) throw HistoryReadException("csv file '${file.fileName}' is $size bytes; at most $maxBytes are read")
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString().trimStart('﻿')
            } catch (e: CharacterCodingException) {
                throw HistoryReadException("csv file '${file.fileName}' is not valid UTF-8")
            }
            val series = try {
                parse(text, relativeTimestamps)
            } catch (e: IllegalArgumentException) {
                throw HistoryReadException("csv file '${file.fileName}': ${e.message}")
            }
            loaded = Loaded(size, modified, series)
            return series
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024

        private fun key(tagPath: String): String = runCatching { normalizeTagPath(tagPath) }.getOrDefault(tagPath).lowercase()

        private fun lowerBound(times: LongArray, t: Long): Int {
            var lo = 0
            var hi = times.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] < t) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Parse the whole file into one sorted series per tag column. */
        private fun parse(text: String, relative: Boolean): Map<String, Series> {
            val rows = csvRows(text)
            if (rows.isEmpty()) throw IllegalArgumentException("the file is empty")
            val header = rows.first()
            if (header.size < 2) throw IllegalArgumentException("line 1: the header needs a timestamp column and at least one tag column")
            val paths = header.drop(1).mapIndexed { i, raw ->
                val name = raw.trim()
                if (name.isEmpty()) throw IllegalArgumentException("line 1: column ${i + 2} has no tag path")
                try {
                    normalizeTagPath(name)
                } catch (e: RuntimeException) {
                    throw IllegalArgumentException("line 1: column ${i + 2} is not a tag path: ${e.message}")
                }
            }
            paths.groupBy { it.lowercase() }.values.firstOrNull { it.size > 1 }?.let {
                throw IllegalArgumentException("line 1: tag path '${it.first()}' appears in more than one column")
            }
            val times = paths.map { ArrayList<Long>() }
            val values = paths.map { ArrayList<Any?>() }
            var origin: Long? = null
            for ((index, cells) in rows.withIndex().drop(1)) {
                val line = index + 1
                if (cells.size == 1 && cells.single().isBlank()) continue
                if (cells.size > header.size) throw IllegalArgumentException("line $line has ${cells.size} cells; the header has ${header.size}")
                val stamp = cells[0].trim()
                val ts = if (relative && origin != null) {
                    val offset = stamp.toLongOrNull() ?: throw IllegalArgumentException("line $line: '$stamp' is not an offset in milliseconds")
                    try {
                        Math.addExact(origin, offset)
                    } catch (e: ArithmeticException) {
                        throw IllegalArgumentException("line $line: offset is out of range")
                    }
                } else {
                    parseTime(stamp) ?: throw IllegalArgumentException("line $line: '$stamp' is not a timestamp")
                }
                if (origin == null) origin = ts
                for (c in 1 until cells.size) {
                    val cell = cells[c]
                    if (cell.isEmpty()) continue
                    times[c - 1] += ts
                    values[c - 1] += parseValue(cell)
                }
            }
            val out = LinkedHashMap<String, Series>()
            for ((i, path) in paths.withIndex()) {
                val order = times[i].indices.sortedBy { times[i][it] }
                out[path.lowercase()] = Series(
                    path,
                    LongArray(order.size) { times[i][order[it]] },
                    Array(order.size) { values[i][order[it]] },
                )
            }
            return out
        }

        private fun parseTime(text: String): Long? {
            if (text.isEmpty()) return null
            text.toLongOrNull()?.let { return it }
            try {
                return Instant.parse(text).toEpochMilli()
            } catch (e: DateTimeParseException) {
                // try a local date-time next
            }
            return try {
                LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }
        }

        private fun parseValue(text: String): Any? {
            val t = text.trim()
            if (t.equals("true", ignoreCase = true)) return true
            if (t.equals("false", ignoreCase = true)) return false
            if (t.any { it == '.' || it == 'e' || it == 'E' }) {
                t.toDoubleOrNull()?.let { if (it.isFinite()) return it }
            } else {
                t.toLongOrNull()?.let { return if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            }
            return text
        }

        /** RFC 4180 rows; a quoted value may hold commas, quotes ("") and line breaks. */
        private fun csvRows(text: String): List<List<String>> {
            val rows = ArrayList<List<String>>()
            var row = ArrayList<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            fun endRow() {
                row.add(cell.toString())
                cell.clear()
                rows.add(row)
                row = ArrayList()
            }
            while (i < text.length) {
                val c = text[i]
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            cell.append('"')
                            i += 2
                            continue
                        }
                        quoted = false
                    } else {
                        cell.append(c)
                    }
                    i++
                    continue
                }
                when (c) {
                    '"' -> quoted = true
                    ',' -> { row.add(cell.toString()); cell.clear() }
                    '\n' -> endRow()
                    '\r' -> { endRow(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                    else -> cell.append(c)
                }
                i++
            }
            if (quoted) throw IllegalArgumentException("line ${rows.size + 1}: a quoted value is not closed")
            if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
            return rows
        }
    }
}

/**
 * Simulator history provider: synthetic, repeatable history for testing, training and demos; it stores nothing
 * (doc source: FEATURES HIS-009).
 *
 * The last segment of the tag path says what to generate: `function_period_amplitude_resolution`, for example
 * `sine_10s_20_500ms` (a sine with a 10 s period and amplitude 20, one point every 500 ms), or `realistic_period`.
 * Durations take `ms`, `s`, `m`, `h` or `d`. Functions:
 *  - `ramp`: rises from 0 to the amplitude over each period, then starts again at 0;
 *  - `sine` / `cos`: a wave whose peak distance from 0 is the amplitude;
 *  - `square`: 0 for the first half of each period and the amplitude for the second half;
 *  - `realistic`: 400 points per period of a smooth random walk between 0 and 100 that repeats every period.
 *
 * Points sit at whole multiples of the resolution since the epoch, so the same request always gives the same
 * points, aligned to the period. Every point is a Good double. A path that does not follow the pattern has no
 * history. One read may generate at most [maxPoints] points (a raw query also stops at its row limit).
 */
class SimulatorHistoryProvider(name: String, private val maxPoints: Long = DEFAULT_MAX_POINTS) : ReadOnlyHistoryProvider(name, "simulator") {
    /** One parsed simulator tag. */
    data class Signal(val function: String, val periodMillis: Long, val amplitude: Double, val resolutionMillis: Long) {
        fun valueAt(t: Long): Double {
            val phase = Math.floorMod(t, periodMillis).toDouble() / periodMillis
            return when (function) {
                "ramp" -> amplitude * phase
                "sine" -> amplitude * sin(2 * PI * phase)
                "cos" -> amplitude * cos(2 * PI * phase)
                "square" -> if (phase < 0.5) 0.0 else amplitude
                "realistic" -> realistic(periodMillis)[(Math.floorMod(t, periodMillis) / resolutionMillis).toInt().coerceIn(0, REALISTIC_POINTS - 1)]
                else -> error("unknown function $function")
            }
        }
    }

    override fun scanUntil(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Boolean) {
        val signal = signal(tagPath) ?: return
        val r = signal.resolutionMillis
        val first = ceilTo(fromMillis, r) ?: return
        if (first > toMillis) return
        val count = (toMillis - first) / r + 1
        var emitted = 0L
        var t = first
        val path = pathOf(tagPath)
        while (true) {
            if (emitted >= maxPoints) {
                throw IllegalArgumentException("the range holds $count simulated points at ${r}ms resolution; at most $maxPoints are generated per read")
            }
            if (!each(HistorySample(path, t, signal.valueAt(t), Quality.GOOD))) return
            emitted++
            if (toMillis - t < r) return
            t += r
        }
    }

    override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? {
        val signal = signal(tagPath) ?: return null
        if (beforeMillis == Long.MIN_VALUE) return null
        val t = Math.floorDiv(beforeMillis - 1, signal.resolutionMillis) * signal.resolutionMillis
        return HistorySample(pathOf(tagPath), t, signal.valueAt(t), Quality.GOOD)
    }

    override fun status(): Map<String, Any?> = linkedMapOf("type" to "simulator", "functions" to FUNCTIONS)

    companion object {
        const val DEFAULT_MAX_POINTS = 5_000_000L
        const val REALISTIC_POINTS = 400
        val FUNCTIONS = listOf("ramp", "sine", "cos", "square", "realistic")
        private val DURATION = Regex("^(\\d{1,12})(ms|s|m|h|d)$")
        private val realisticCache = java.util.concurrent.ConcurrentHashMap<Long, DoubleArray>()

        private fun pathOf(tagPath: String) = runCatching { normalizeTagPath(tagPath) }.getOrDefault(tagPath)

        /** The signal a tag path names, or null when the last segment does not follow the pattern. */
        fun signal(tagPath: String): Signal? {
            val last = runCatching { TagPath.parse(tagPath, "default").segments.lastOrNull() }.getOrNull() ?: return null
            val parts = last.lowercase().split('_')
            val function = parts.firstOrNull() ?: return null
            if (function !in FUNCTIONS) return null
            if (function == "realistic") {
                if (parts.size != 2) return null
                val period = duration(parts[1]) ?: return null
                return Signal(function, period, 100.0, maxOf(1L, period / REALISTIC_POINTS))
            }
            if (parts.size != 4) return null
            val period = duration(parts[1]) ?: return null
            val amplitude = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            val resolution = duration(parts[3]) ?: return null
            return Signal(function, period, amplitude, resolution)
        }

        private fun duration(text: String): Long? {
            val m = DURATION.matchEntire(text) ?: return null
            val n = m.groupValues[1].toLong()
            val unit = when (m.groupValues[2]) {
                "ms" -> 1L
                "s" -> 1_000L
                "m" -> 60_000L
                "h" -> 3_600_000L
                else -> 86_400_000L
            }
            val millis = try {
                Math.multiplyExact(n, unit)
            } catch (e: ArithmeticException) {
                return null
            }
            return millis.takeIf { it > 0 }
        }

        private fun ceilTo(t: Long, step: Long): Long? {
            val floor = Math.floorDiv(t, step) * step
            if (floor == t) return t
            return try {
                Math.addExact(floor, step)
            } catch (e: ArithmeticException) {
                null
            }
        }

        /** 400 points of a smooth random walk in [0, 100], the same for every period of this length. */
        private fun realistic(period: Long): DoubleArray = realisticCache.computeIfAbsent(period) {
            val rnd = java.util.Random(period * 31 + 7)
            val raw = DoubleArray(REALISTIC_POINTS)
            var v = 50.0
            var drift = 0.0
            for (i in raw.indices) {
                drift = (drift + rnd.nextGaussian() * 0.6) * 0.9
                v = (v + drift).coerceIn(5.0, 95.0)
                raw[i] = v
            }
            // Ease the last points back toward the first, so a period joins the next without a step.
            val blend = 40
            for (k in 0 until blend) {
                val i = REALISTIC_POINTS - blend + k
                val w = (k + 1).toDouble() / (blend + 1)
                raw[i] = raw[i] * (1 - w) + raw[0] * w
            }
            DoubleArray(REALISTIC_POINTS) { Math.round(raw[it] * 100) / 100.0 }
        }
    }
}

/**
 * Splitter history provider: stores every sample in two providers and reads from one of them
 * (doc source: FEATURES HIS-006).
 *
 * Writing: [HistoryRouter] moves each record routed here into the store-and-forward queues of [first] and
 * [second] in one transaction, so each backend then forwards on its own: an outage of one does not hold back the
 * other, and each stores every record exactly once. Either may be `local`.
 *
 * Reading: everything comes from [first]. With [limitFirstMillis] > 0, [first] serves only the most recent
 * [limitFirstMillis] (as of the read), and the older part of a range comes from [second]. When [first] cannot be
 * read ([HistoryReadException]), the whole range comes from [second].
 */
class SplitterHistoryProvider(
    override val name: String,
    val first: String,
    val second: String,
    val limitFirstMillis: Long = 0,
    private val clock: Clock = Clock.systemUTC(),
    /** Reader for a provider name (the router's [HistoryRouter.reader]); null when it is not configured. */
    private val readers: (String) -> HistoryReader?,
) : RemoteHistoryProvider {
    init {
        require(PROVIDER_NAME.matches(first) && PROVIDER_NAME.matches(second)) { "splitter targets must be provider names" }
        require(first != second) { "a splitter needs two different providers" }
        require(first != name && second != name) { "a splitter cannot write to itself" }
        require(limitFirstMillis >= 0) { "limitFirstMillis must be >= 0" }
    }

    val targets: List<String> get() = listOf(first, second)

    override fun write(records: List<ForwardRecord>) {
        throw HistoryWriteException("splitter '$name' is fanned out by the router, not written directly", permanent = false)
    }

    override fun query(query: HistoryQuery): List<HistorySample> {
        val out = ArrayList<HistorySample>()
        scan(query.tagPath, query.fromMillis, query.toMillis) { if (out.size < query.limit) out += it }
        return out
    }

    override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) {
        val cutoff = cutoff()
        var start = fromMillis
        if (cutoff != null && fromMillis < cutoff) {
            // The older part comes from the second provider.
            reader(second).scan(tagPath, fromMillis, minOf(toMillis, cutoff - 1), each)
            if (toMillis < cutoff) return
            start = cutoff
        }
        var last: Long? = null
        try {
            reader(first).scan(tagPath, start, toMillis) { last = it.timestampMillis; each(it) }
        } catch (e: HistoryReadException) {
            // Continue after the last row the first provider delivered, so no row is repeated.
            val delivered = last
            if (delivered == Long.MAX_VALUE) return
            val resume = if (delivered == null) start else delivered + 1
            if (resume <= toMillis) reader(second).scan(tagPath, resume, toMillis, each)
        }
    }

    override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? {
        val cutoff = cutoff()
        if (cutoff == null) {
            return withFallback({ reader(first).latestBefore(tagPath, beforeMillis) }) { reader(second).latestBefore(tagPath, beforeMillis) }
        }
        if (beforeMillis > cutoff) {
            val recent = runCatching { reader(first).latestBefore(tagPath, beforeMillis) }
            val hit = recent.getOrNull()
            if (recent.isFailure && recent.exceptionOrNull() !is HistoryReadException) throw recent.exceptionOrNull()!!
            if (recent.isFailure) return reader(second).latestBefore(tagPath, beforeMillis)
            if (hit != null && hit.timestampMillis >= cutoff) return hit
        }
        return reader(second).latestBefore(tagPath, minOf(beforeMillis, cutoff))
    }

    override fun status(): Map<String, Any?> = linkedMapOf(
        "type" to "splitter",
        "first" to first,
        "second" to second,
        "limitFirstMillis" to limitFirstMillis,
    )

    private fun cutoff(): Long? = if (limitFirstMillis <= 0) null else clock.millis() - limitFirstMillis

    private fun reader(provider: String): HistoryReader =
        readers(provider) ?: throw HistoryReadException("splitter '$name': history provider '$provider' is not configured")

    private inline fun <T> withFallback(primary: () -> T, fallback: () -> T): T = try {
        primary()
    } catch (e: HistoryReadException) {
        fallback()
    }
}
