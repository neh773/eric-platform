package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.historian.HistoryAggregate
import org.oip.historian.HistoryAggregator
import org.oip.historian.HistoryCollector
import org.oip.historian.HistoryInterpolation
import org.oip.historian.HistoryQuery
import org.oip.historian.HistoryReadException
import org.oip.historian.HistoryReader
import org.oip.historian.HistoryRouter
import org.oip.historian.HistoryWriteException
import org.oip.historian.LOCAL_PROVIDER
import org.oip.historian.PROVIDER_NAME
import org.oip.historian.normalizeTagPath
import org.oip.security.Authorizer
import org.oip.security.Permissions
import org.oip.security.ZoneDirectory
import org.oip.tags.QualityCode
import org.oip.tags.TagEngine
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Tag history REST routes.
 *
 * Queries (any authenticated caller, like tag reads; `Auth.kt` owns the rule):
 *  - `GET /api/v1/history/data?tagPath=&from=&to=&limit=&provider=`: raw rows, oldest first, exactly as stored,
 *    with `truncated` set when more rows matched than `limit`.
 *  - `GET /api/v1/history/aggregate?tagPath=&from=&to=&interval=&aggregate=&interpolation=&provider=`: one value
 *    per fixed interval ([HistoryAggregator] rules; mode names from the reference platform 8.3's Aggregation Mode list).
 *    `interpolation` is None (default, unchanged results), Step, or Linear; with Step or Linear each window
 *    also carries `startValue` and `endValue`.
 * `from` and `to` are inclusive; each is epoch milliseconds or an ISO-8601 instant. `provider` defaults to the
 * tag's configured storage provider (`local` when the tag has none). Samples still queued in the collector are
 * flushed first, and for a store-and-forward provider a forward is attempted (never waiting on one already
 * running), so a reader sees what has reached the provider. A provider that cannot be read returns 503.
 *
 * Both queries apply the tag's read permissions to the caller ([TagEngine.mayRead], TAG-013), as `POST
 * /api/v1/tags/read` does. A tag the caller may not read is answered 200 with `quality: "Bad_AccessDenied"` and
 * no `samples` or `windows` rows, and its history is not read. A readable tag's answer has no `quality` field.
 * History of a tag that is no longer configured (deleted, renamed, or on a removed provider) has no permission
 * left to check, so it is refused the same way unless the caller holds `gateway.write`, the permission that edits
 * tag configuration.
 *
 * Store-and-forward status and quarantine live under `/api/v1/diagnostics/history/` (gateway.read to view,
 * gateway.write to retry, delete, or export), the counterpart of the store-and-forward status page:
 *  - `GET  .../providers`: collector counters (pending, dropped, consecutive sink failures, last error, samples
 *    diverted to quarantine) and, per provider, pending, quarantined, connection state, last error.
 *    -
 *  - `GET  .../quarantine?provider=&limit=`: quarantined records with the rejection error. The records carry
 *    tag values, so this route also needs `tags.read` (403 without it), and records of tags the caller may not
 *    read are left out;
 *  - `GET  .../quarantine/export?format=csv|json&provider=&limit=&ids=`: those same records as a file download.
 *    gateway.write, and tags.read. `format` defaults to json. `ids` narrows the file to that subset of the list.
 *    -
 *  - `POST .../quarantine/retry` `{ids?: [..], provider?: ".."}`: put them back on the queue;
 *  - `POST .../quarantine/delete` `{ids?: [..], provider?: ".."}`: discard them.
 *  - `POST .../quarantine/import`: body is a quarantine export (JSON or CSV). The records are put back on
 *    their providers' queues and a forward runs now. gateway.write. A malformed or oversized file is a 4xx
 *    and writes nothing.
 *  - `POST .../cache/archive?provider=`: download that provider's disk cache (or every provider's when
 *    `provider` is omitted) and remove those pending records from the live cache. gateway.write.
 *  - `POST .../cache/load`: body is a disk-cache archive. The records go back on the same providers' queues.
 *    gateway.write. A malformed or oversized file is a 4xx and writes nothing.
 *  - provider status includes `stored` (datapoints written into the disk cache) and `storeRate` (those
 *    datapoints per second over the last 10 seconds).
 * (doc source: FEATURES HIS-025)
 * (retry, delete, export, import; the 8.3 page keeps the same quarantine behaviour).
 */

/** Quarantine file download. gateway.write at the route; the handler also requires tags.read. */
const val QUARANTINE_EXPORT_PATH = "/api/v1/diagnostics/history/quarantine/export"

/** Quarantine export uploaded back onto the queue. gateway.write, same as retry and delete. */
const val QUARANTINE_IMPORT_PATH = "/api/v1/diagnostics/history/quarantine/import"

/** Write a provider's pending disk cache to a gateway-side archive, then remove those rows. gateway.write. */
const val DISK_CACHE_ARCHIVE_PATH = "/api/v1/diagnostics/history/cache/archive"

/** List gateway-side disk-cache archives. gateway.read. */
const val DISK_CACHE_ARCHIVES_PATH = "/api/v1/diagnostics/history/cache/archives"

/** Load a gateway-side archive by name, or upload a downloaded archive. gateway.write. */
const val DISK_CACHE_LOAD_PATH = "/api/v1/diagnostics/history/cache/load"
class HistoryApi(
    private val collector: HistoryCollector,
    private val router: HistoryRouter,
    private val authorizer: Authorizer,
    /** Per-tag read checks (TAG-013). Null (unit tests without a tag engine): no per-tag check. */
    private val tags: TagEngine? = null,
    private val zones: ZoneDirectory? = null,
    private val archives: org.oip.historian.DiskCacheArchives,
    private val audit: (action: String, target: String, detail: String) -> Unit = { _, _, _ -> },
) {
    fun register(app: Javalin) {
        app.get("/api/v1/history/data") { ctx ->
            val q = common(ctx)
            val limit = ctx.queryParam("limit")?.let { it.toIntOrNull() ?: throw Problems.badRequest("limit must be an integer") }
                ?: HistoryQuery.DEFAULT_LIMIT
            if (limit !in 1..HistoryQuery.MAX_LIMIT) throw Problems.unprocessable("limit must be between 1 and ${HistoryQuery.MAX_LIMIT}")
            if (q.denied) {
                json(ctx, refused(q, "limit" to limit, "truncated" to false, "samples" to emptyList<Any>()))
                return@get
            }
            // One extra row tells the caller whether the range held more than `limit`.
            val fetched = read(q.provider) { q.reader.query(HistoryQuery(q.tagPath, q.from, q.to, minOf(limit + 1, HistoryQuery.MAX_LIMIT))) }
            val truncated = fetched.size > limit
            val rows = fetched.take(limit).map { s ->
                linkedMapOf<String, Any?>(
                    "timestamp" to Instant.ofEpochMilli(s.timestampMillis).toString(),
                    "value" to s.value,
                    "quality" to s.quality.code.label,
                )
            }
            json(ctx, linkedMapOf(
                "tagPath" to q.tagPath,
                "provider" to q.provider,
                "from" to Instant.ofEpochMilli(q.from).toString(),
                "to" to Instant.ofEpochMilli(q.to).toString(),
                "limit" to limit,
                "truncated" to truncated,
                "samples" to rows,
            ))
        }

        app.get("/api/v1/history/aggregate") { ctx ->
            val q = common(ctx)
            val interval = durationParam(ctx, "interval")
            val mode = ctx.queryParam("aggregate")?.takeIf { it.isNotBlank() }?.let {
                try {
                    HistoryAggregate.fromWire(it)
                } catch (e: IllegalArgumentException) {
                    throw Problems.unprocessable(e.message!!)
                }
            } ?: HistoryAggregate.AVERAGE
            val interpolation = ctx.queryParam("interpolation")?.takeIf { it.isNotBlank() }?.let {
                try {
                    HistoryInterpolation.fromWire(it)
                } catch (e: IllegalArgumentException) {
                    throw Problems.unprocessable(e.message!!)
                }
            } ?: HistoryInterpolation.NONE
            try {
                HistoryAggregator.windowCount(q.from, q.to, interval)
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable(e.message!!)
            }
            if (q.denied) {
                json(ctx, refused(q, "intervalMillis" to interval, "aggregate" to mode.wire, "interpolation" to interpolation.wire,
                    "windows" to emptyList<Any>()))
                return@get
            }
            val windows = read(q.provider) { HistoryAggregator.aggregate(q.reader, q.tagPath, q.from, q.to, interval, mode, interpolation) }
            json(ctx, linkedMapOf(
                "tagPath" to q.tagPath,
                "provider" to q.provider,
                "from" to Instant.ofEpochMilli(q.from).toString(),
                "to" to Instant.ofEpochMilli(q.to).toString(),
                "intervalMillis" to interval,
                "aggregate" to mode.wire,
                "interpolation" to interpolation.wire,
                "windows" to windows.map { w ->
                    linkedMapOf<String, Any?>(
                        "start" to Instant.ofEpochMilli(w.startMillis).toString(),
                        "end" to Instant.ofEpochMilli(w.endMillis).toString(),
                        "value" to w.value,
                        "rows" to w.rows,
                    ).also {
                        if (interpolation != HistoryInterpolation.NONE) {
                            it["startValue"] = w.startValue
                            it["endValue"] = w.endValue
                        }
                    }
                },
            ))
        }

        app.get("/api/v1/diagnostics/history/providers") { ctx ->
            json(ctx, linkedMapOf("collector" to collector.stats(), "providers" to router.status()))
        }

        app.get("/api/v1/diagnostics/history/quarantine") { ctx ->
            json(ctx, mapOf("records" to quarantineRecords(ctx, null)))
        }

        // A file download is a GET so the browser can save it. The route rule is gateway.write, not the
        // diagnostics read used by the list. The body is the list the same query would return.
        app.get(QUARANTINE_EXPORT_PATH) { ctx ->
            val format = exportFormat(ctx)
            val records = quarantineRecords(ctx, exportIds(ctx))
            val filename = exportFilename(providerParam(ctx), format)
            val body = if (format == "csv") quarantineCsv(records) else Json.write(mapOf("records" to records))
            val type = if (format == "csv") "text/csv; charset=utf-8" else "application/json"
            ctx.header("Content-Disposition", "attachment; filename=\"$filename\"")
            ctx.header("Cache-Control", "no-store")
            ctx.contentType(type).result(body)
        }

        app.post("/api/v1/diagnostics/history/quarantine/retry") { ctx ->
            val (ids, provider) = selection(ctx)
            json(ctx, mapOf("retried" to router.buffer.retryQuarantined(ids, provider)))
        }

        app.post("/api/v1/diagnostics/history/quarantine/delete") { ctx ->
            val (ids, provider) = selection(ctx)
            json(ctx, mapOf("deleted" to router.buffer.deleteQuarantined(ids, provider)))
        }

        // The body is a file the export route produced. Parsing finishes, and only then are the records
        // queued, so a 4xx leaves the cache and the quarantine untouched.
        app.post(QUARANTINE_IMPORT_PATH) { ctx ->
            val records = StoreForwardFiles.parseQuarantine(ctx.bodyAsBytes(), ctx.contentType())
            val retried = try {
                router.importQuarantine(records)
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable(e.message ?: "nothing was imported")
            }
            val target = records.map { it.provider }.distinct().singleOrNull() ?: "quarantine"
            audit(org.oip.security.AuditActions.QUARANTINE_IMPORTED, target, "records=$retried")
            json(ctx, mapOf("retried" to retried))
        }

        // The file is synced into store-forward/archives before the pending rows are deleted. A crash
        // before that delete leaves the rows and the file; load inserts the same (sourceId, seq).
        // The reference product also opens a duplicated database connection suffixed _archive_backfill. This
        // gateway does not: load refuses a provider that is not already registered.
        app.get(DISK_CACHE_ARCHIVES_PATH) { ctx ->
            json(ctx, mapOf("archives" to archives.list().map { archiveJson(it) }))
        }

        app.get("$DISK_CACHE_ARCHIVES_PATH/{name}") { ctx ->
            GatewayAuth.requirePermission(ctx, authorizer, Permissions.TAGS_READ)
            val file = archiveFile(ctx.pathParam("name"))
            ctx.header("Content-Disposition", "attachment; filename=\"${file.fileName}\"")
            ctx.header("Cache-Control", "no-store")
            ctx.contentType("application/x-ndjson").result(java.nio.file.Files.newInputStream(file))
        }

        app.delete("$DISK_CACHE_ARCHIVES_PATH/{name}") { ctx ->
            val name = ctx.pathParam("name")
            try {
                archives.delete(name)
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable(e.message ?: "archive name is not valid")
            } catch (e: NoSuchElementException) {
                throw Problems.notFound(e.message ?: "archive was not found")
            }
            audit(org.oip.security.AuditActions.DISK_CACHE_ARCHIVE_DELETED, name, "")
            ctx.status(204)
        }

        app.post(DISK_CACHE_ARCHIVE_PATH) { ctx ->
            val provider = providerParam(ctx) ?: throw Problems.unprocessable("provider is required")
            val info = try {
                archives.archive(provider)
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable(e.message ?: "provider is not valid")
            }
            audit(org.oip.security.AuditActions.DISK_CACHE_ARCHIVED, info.name, "provider=$provider;records=${info.records}")
            json(ctx, archiveJson(info))
        }

        app.post(DISK_CACHE_LOAD_PATH) { ctx ->
            val bytes = ctx.bodyAsBytes()
            val named = archiveName(bytes, ctx.contentType())
            val loaded = if (named != null) {
                try {
                    archives.load(named, providerExists = ::providerExists, noteStored = router::noteStored)
                } catch (e: IllegalArgumentException) {
                    throw Problems.unprocessable(e.message ?: "nothing was loaded")
                } catch (e: NoSuchElementException) {
                    throw Problems.notFound(e.message ?: "archive was not found")
                }
            } else {
                val records = StoreForwardFiles.parseDiskCache(bytes)
                try {
                    router.loadDiskCache(records)
                } catch (e: IllegalArgumentException) {
                    throw Problems.unprocessable(e.message ?: "nothing was loaded")
                }
            }
            audit(org.oip.security.AuditActions.DISK_CACHE_LOADED, named ?: "upload", "records=$loaded")
            json(ctx, mapOf("loaded" to loaded))
        }
    }

    private fun providerExists(name: String) = name == org.oip.historian.LOCAL_PROVIDER || router.provider(name) != null

    private fun archiveFile(name: String): java.nio.file.Path = try {
        archives.path(name)
    } catch (e: IllegalArgumentException) {
        throw Problems.unprocessable(e.message ?: "archive name is not valid")
    } catch (e: NoSuchElementException) {
        throw Problems.notFound(e.message ?: "archive was not found")
    }

    private fun archiveJson(info: org.oip.historian.ArchiveInfo) = linkedMapOf(
        "name" to info.name,
        "provider" to info.provider,
        "records" to info.records,
        "bytes" to info.bytes,
        "created" to info.created,
    )

    /** `{"name":"..."}` loads a gateway-side archive. Any other body is an uploaded file. */
    private fun archiveName(bytes: ByteArray, contentType: String?): String? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type != "application/json") return null
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isEmpty() || !text.startsWith("{") || text.contains('\n')) return null
        val obj = try {
            Json.mapper.readValue(text, Map::class.java) as Map<*, *>
        } catch (e: Exception) {
            return null
        }
        if ("name" !in obj || "kind" in obj || "records" in obj) return null
        val unknown = obj.keys.map { it.toString() } - "name"
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown fields: ${unknown.joinToString()}; nothing was loaded")
        return obj["name"] as? String ?: throw Problems.unprocessable("name must be an archive name; nothing was loaded")
    }

    /** [denied]: the caller may not read [tagPath] (TAG-013); nothing was flushed or read for it. */
    private class Common(val tagPath: String, val from: Long, val to: Long, val provider: String, val reader: HistoryReader, val denied: Boolean)

    /** The answer for a tag the caller may not read: the request echoed back, `Bad_AccessDenied`, no rows. */
    private fun refused(q: Common, vararg rest: Pair<String, Any?>): Map<String, Any?> = linkedMapOf<String, Any?>(
        "tagPath" to q.tagPath,
        "provider" to q.provider,
        "from" to Instant.ofEpochMilli(q.from).toString(),
        "to" to Instant.ofEpochMilli(q.to).toString(),
        "quality" to QualityCode.BAD_USER_ACCESS_DENIED.label,
    ).also { it.putAll(rest) }

    /** tagPath, from, to, provider: validated, then flush and (best effort) forward before reading. */
    private fun common(ctx: Context): Common {
        val rawPath = ctx.queryParam("tagPath")?.takeIf { it.isNotBlank() } ?: throw Problems.badRequest("tagPath is required")
        val tagPath = try {
            normalizeTagPath(rawPath)
        } catch (e: RuntimeException) {
            throw Problems.unprocessable("invalid tagPath: ${e.message}")
        }
        val from = instantParam(ctx, "from")
        val to = instantParam(ctx, "to")
        if (from > to) throw Problems.unprocessable("from must not be after to")
        val provider = providerParam(ctx) ?: router.providerFor(tagPath)
        val reader = router.reader(provider) ?: throw Problems.unprocessable("unknown history provider '$provider'")
        if (tags != null && zones != null && !tags.mayRead(tagPath, GatewayAuth.tagCaller(ctx, zones, authorizer))) {
            return Common(tagPath, from, to, provider, reader, denied = true)
        }
        // A reader's flush does not count toward the collector's divert threshold (review-3 F8).
        runCatching { collector.flushForRead() }
        if (provider != LOCAL_PROVIDER) runCatching { router.tryForward(provider) }
        return Common(tagPath, from, to, provider, reader, denied = false)
    }

    private fun <T> read(provider: String, body: () -> T): T = try {
        body()
    } catch (e: HistoryReadException) {
        throw unavailable(provider, e.message)
    } catch (e: HistoryWriteException) {
        throw unavailable(provider, e.message)
    }

    private fun unavailable(provider: String, message: String?) =
        ProblemException(503, "history-provider-unavailable", "Service Unavailable", "history provider '$provider' cannot be read: $message")

    private fun providerParam(ctx: Context): String? = ctx.queryParam("provider")?.takeIf { it.isNotBlank() }?.also {
        if (!PROVIDER_NAME.matches(it)) throw Problems.unprocessable("provider must be a provider name")
    }

    @Suppress("UNCHECKED_CAST")
    private fun selection(ctx: Context): Pair<List<Long>?, String?> {
        val body = if (ctx.body().isBlank()) emptyMap() else try {
            Json.mapper.readValue(ctx.body(), Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        val unknown = body.keys - setOf("ids", "provider")
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown fields: ${unknown.joinToString()}")
        val ids = body["ids"]?.let { raw ->
            val list = raw as? List<*> ?: throw Problems.unprocessable("ids must be an array of integers")
            list.map { (it as? Number)?.toLong() ?: throw Problems.unprocessable("ids must be an array of integers") }
        }
        val provider = body["provider"]?.let { p ->
            (p as? String)?.takeIf { PROVIDER_NAME.matches(it) } ?: throw Problems.unprocessable("provider must be a provider name")
        }
        if (ids == null && provider == null) throw Problems.unprocessable("give ids, provider, or both")
        return ids to provider
    }

    /**
     * Quarantine rows the caller may see, oldest first, in the same shape as the list route.
     * [ids] narrows that list; null keeps every listed row. Id 0 matches nothing (row ids start at 1),
     * which is how an empty selection downloads an empty file instead of the whole list.
     */
    private fun quarantineRecords(ctx: Context, ids: Set<Long>?): List<Map<String, Any?>> {
        // gateway.read (the list's route rule) covers the diagnostics; the records are tag values, so they
        // also need tags.read, the same permission as a tag or history read. Export's route rule is
        // gateway.write, and this check still applies.
        GatewayAuth.requirePermission(ctx, authorizer, Permissions.TAGS_READ)
        val provider = providerParam(ctx)
        val limit = ctx.queryParam("limit")?.let { it.toIntOrNull() ?: throw Problems.badRequest("limit must be an integer") } ?: 1000
        if (limit !in 1..HistoryQuery.MAX_LIMIT) throw Problems.unprocessable("limit must be between 1 and ${HistoryQuery.MAX_LIMIT}")
        val caller = if (tags != null && zones != null) GatewayAuth.tagCaller(ctx, zones, authorizer) else null
        val records = router.buffer.quarantined(provider, limit).filter { r ->
            caller == null || tags!!.mayRead(r.sample.tagPath, caller)
        }.map { r ->
            linkedMapOf<String, Any?>(
                "id" to r.id,
                "provider" to r.provider,
                "tagPath" to r.sample.tagPath,
                "timestamp" to Instant.ofEpochMilli(r.sample.timestampMillis).toString(),
                "value" to r.sample.value,
                "quality" to r.sample.quality.code.label,
                "error" to r.error,
                "quarantinedAt" to Instant.ofEpochMilli(r.quarantinedMillis).toString(),
            )
        }
        if (ids == null) return records
        return records.filter { (it["id"] as Number).toLong() in ids }
    }

    /** `json` (default) or `csv`. */
    private fun exportFormat(ctx: Context): String {
        val raw = ctx.queryParam("format")?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: "json"
        if (raw != "json" && raw != "csv") throw Problems.unprocessable("format must be csv or json")
        return raw
    }

    /** Comma-separated ids, or null when the parameter is absent. */
    private fun exportIds(ctx: Context): Set<Long>? {
        val raw = ctx.queryParam("ids")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raw.split(',').map { part ->
            val text = part.trim()
            text.toLongOrNull()?.takeIf { it >= 0 && text.matches(DIGITS) }
                ?: throw Problems.unprocessable("ids must be a comma-separated list of integers")
        }.toSet()
    }

    private fun exportFilename(provider: String?, format: String): String =
        if (provider == null) "quarantine.$format" else "quarantine-$provider.$format"

    /** RFC 4180, with a leading quote on cells that a spreadsheet would treat as a formula. */
    private fun quarantineCsv(records: List<Map<String, Any?>>): String {
        val columns = StoreForwardFiles.QUARANTINE_COLUMNS
        val sb = StringBuilder()
        sb.append(columns.joinToString(",")).append("\r\n")
        for (record in records) {
            sb.append(columns.joinToString(",") { csvCell(record[it]) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun csvCell(value: Any?): String {
        var text = when (value) {
            null -> ""
            is String -> value
            else -> Json.write(value)
        }
        if (text.isNotEmpty() && text[0] in CSV_FORMULA) text = "'$text"
        val escaped = text.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }

    private fun json(ctx: Context, body: Any) {
        ctx.contentType("application/json").result(Json.write(body))
    }

    /** Milliseconds, or an ISO-8601 duration such as PT1M. */
    private fun durationParam(ctx: Context, name: String): Long {
        val raw = ctx.queryParam(name)?.takeIf { it.isNotBlank() } ?: throw Problems.badRequest("$name is required")
        val millis = raw.toLongOrNull() ?: try {
            Duration.parse(raw).toMillis()
        } catch (e: DateTimeParseException) {
            throw Problems.badRequest("$name must be milliseconds or an ISO-8601 duration")
        } catch (e: ArithmeticException) {
            throw Problems.badRequest("$name is out of range")
        }
        if (millis <= 0) throw Problems.unprocessable("$name must be greater than zero")
        return millis
    }

    private fun instantParam(ctx: Context, name: String): Long {
        val raw = ctx.queryParam(name)?.takeIf { it.isNotBlank() } ?: throw Problems.badRequest("$name is required")
        raw.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw Problems.badRequest("$name must be epoch milliseconds or an ISO-8601 instant")
        } catch (e: ArithmeticException) {
            throw Problems.badRequest("$name is out of range")
        }
    }

    private val DIGITS = Regex("\\d+")
    /** Spreadsheet formula triggers. A cell that starts with one is prefixed so it stays text. */
    private val CSV_FORMULA = "=+-@\t\r"
}
