package org.oip.gateway

import org.oip.historian.DiskCacheRecord
import org.oip.historian.HistorySample
import org.oip.historian.PROVIDER_NAME
import org.oip.historian.normalizeTagPath
import org.oip.tags.Quality
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Quarantine export/import and disk-cache archive files.
 *
 * Quarantine import reads the file produced by [HistoryApi]'s export and puts those samples back
 * through store-and-forward (the reference product's Import Quarantine File). A disk-cache archive is the pending
 * cache for one provider, or for every provider when the archive was taken that way. Load puts those
 * samples back on the same providers' queues.
 *
 * An import that is empty, malformed, or over the size limit throws before any record is written.
 *
 * The 8.3 manual page that used to live at
 * `platform/database-connections/store-and-forward` is published here:
 * (doc source: FEATURES HIS-025)
 */
internal object StoreForwardFiles {
    /** Large enough for a full quarantine export (100_000 records) of ordinary tag values. */
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_RECORDS = 100_000

    const val DISK_CACHE_KIND = "oip.store-forward.disk-cache"
    const val DISK_CACHE_VERSION = 1

    val QUARANTINE_COLUMNS = listOf(
        "id", "provider", "tagPath", "timestamp", "value", "quality", "error", "quarantinedAt",
    )

    fun parseQuarantine(bytes: ByteArray, contentType: String?, maxRecords: Int = MAX_RECORDS): List<DiskCacheRecord> {
        rejectOversized(bytes, "quarantine file", "nothing was imported")
        val text = decode(bytes, "quarantine file", "nothing was imported")
        if (text.isEmpty()) throw Problems.badRequest("quarantine file is empty; nothing was imported")
        val type = mediaType(contentType)
        return when {
            type == "application/json" || type == "text/json" -> parseQuarantineJson(text, maxRecords)
            type == "text/csv" || type == "application/csv" -> parseQuarantineCsv(text, maxRecords)
            type == null || type == "application/octet-stream" -> when {
                text.startsWith("{") -> parseQuarantineJson(text, maxRecords)
                text.startsWith("id,") -> parseQuarantineCsv(text, maxRecords)
                else -> throw Problems.unprocessable("quarantine file must be a JSON object or a CSV export; nothing was imported")
            }
            else -> throw Problems.unprocessable("quarantine file must be JSON or CSV; nothing was imported")
        }
    }

    fun writeDiskCache(records: List<DiskCacheRecord>): ByteArray {
        val body = linkedMapOf(
            "kind" to DISK_CACHE_KIND,
            "version" to DISK_CACHE_VERSION,
            "records" to records.map { record ->
                linkedMapOf<String, Any?>(
                    "provider" to record.provider,
                    "tagPath" to record.sample.tagPath,
                    "timestamp" to Instant.ofEpochMilli(record.sample.timestampMillis).toString(),
                    "value" to record.sample.value,
                    "quality" to record.sample.quality.code.label,
                )
            },
        )
        return Json.write(body).toByteArray(Charsets.UTF_8)
    }

    fun parseDiskCache(bytes: ByteArray, maxRecords: Int = MAX_RECORDS): List<DiskCacheRecord> {
        rejectOversized(bytes, "disk cache file", "nothing was loaded")
        val text = decode(bytes, "disk cache file", "nothing was loaded")
        if (text.isEmpty()) throw Problems.badRequest("disk cache file is empty; nothing was loaded")
        if (text.contains('\n')) return parseNdjson(text, maxRecords)
        val root = try {
            Json.mapper.readValue(text, Any::class.java)
        } catch (e: Exception) {
            throw Problems.badRequest("disk cache file is not valid JSON; nothing was loaded")
        }
        val obj = root as? Map<*, *> ?: throw Problems.unprocessable("disk cache file must be a JSON object; nothing was loaded")
        unknownFields(obj, setOf("kind", "version", "records"), "nothing was loaded")
        if (obj["kind"] != DISK_CACHE_KIND) {
            throw Problems.unprocessable("disk cache file is not a store-and-forward archive; nothing was loaded")
        }
        val version = (obj["version"] as? Number)?.toInt()
        if (version != DISK_CACHE_VERSION) {
            throw Problems.unprocessable("disk cache archive version is not supported; nothing was loaded")
        }
        return records(obj["records"], maxRecords, "disk cache file", "nothing was loaded", quarantineColumns = false)
    }

    private fun parseQuarantineJson(text: String, maxRecords: Int): List<DiskCacheRecord> {
        val root = try {
            Json.mapper.readValue(text, Any::class.java)
        } catch (e: Exception) {
            throw Problems.badRequest("quarantine file is not valid JSON; nothing was imported")
        }
        val obj = root as? Map<*, *> ?: throw Problems.unprocessable("quarantine file must be a JSON object; nothing was imported")
        unknownFields(obj, setOf("records"), "nothing was imported")
        if ("records" !in obj) throw Problems.unprocessable("quarantine file requires records; nothing was imported")
        return records(obj["records"], maxRecords, "quarantine file", "nothing was imported", quarantineColumns = true)
    }

    private fun parseQuarantineCsv(text: String, maxRecords: Int): List<DiskCacheRecord> {
        val rows = try {
            csvRows(text)
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable("quarantine file is not valid CSV: ${e.message}; nothing was imported")
        }
        if (rows.isEmpty() || rows.first() != QUARANTINE_COLUMNS) {
            throw Problems.unprocessable("quarantine CSV must start with the export header; nothing was imported")
        }
        val data = rows.drop(1).filter { it.size != 1 || it.single().isNotEmpty() }
        if (data.size > maxRecords) {
            throw Problems.tooLarge(
                "quarantine file has ${data.size} records, which is above the limit of $maxRecords; nothing was imported",
            )
        }
        return data.mapIndexed { index, cells ->
            if (cells.size != QUARANTINE_COLUMNS.size) {
                throw Problems.unprocessable("record ${index + 1}: expected ${QUARANTINE_COLUMNS.size} columns; nothing was imported")
            }
            val fields = QUARANTINE_COLUMNS.zip(cells).toMap()
            recordFromFields(
                index + 1,
                provider = fields.getValue("provider"),
                tagPath = fields.getValue("tagPath"),
                timestamp = fields.getValue("timestamp"),
                value = parseCsvValue(fields.getValue("value")),
                valuePresent = true,
                quality = fields.getValue("quality"),
                nothing = "nothing was imported",
            )
        }
    }

    private fun records(
        raw: Any?,
        maxRecords: Int,
        what: String,
        nothing: String,
        quarantineColumns: Boolean,
    ): List<DiskCacheRecord> {
        val list = raw as? List<*> ?: throw Problems.unprocessable("$what records must be an array; $nothing")
        if (list.size > maxRecords) {
            throw Problems.tooLarge("$what has ${list.size} records, which is above the limit of $maxRecords; $nothing")
        }
        val allowed = if (quarantineColumns) {
            setOf("id", "provider", "tagPath", "timestamp", "value", "quality", "error", "quarantinedAt")
        } else {
            setOf("provider", "sourceId", "seq", "tagPath", "timestamp", "value", "quality")
        }
        return list.mapIndexed { index, item ->
            val row = item as? Map<*, *> ?: throw Problems.unprocessable("record ${index + 1} must be an object; $nothing")
            unknownFields(row, allowed, nothing, "record ${index + 1}")
            recordFromFields(
                index + 1,
                provider = row["provider"] as? String,
                tagPath = row["tagPath"] as? String,
                timestamp = row["timestamp"] as? String,
                value = jsonValue(index + 1, row["value"], "value" in row, nothing),
                valuePresent = "value" in row,
                quality = row["quality"] as? String,
                nothing = nothing,
                sourceId = if (quarantineColumns) "" else row["sourceId"] as? String,
                seq = if (quarantineColumns) 0L else (row["seq"] as? Number)?.toLong() ?: 0L,
                identityRequired = !quarantineColumns,
            )
        }
    }

    private fun parseNdjson(text: String, maxRecords: Int): List<DiskCacheRecord> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) throw Problems.badRequest("disk cache file is empty; nothing was loaded")
        try {
            org.oip.historian.ArchiveLines.parseHeader(lines.first())
        } catch (e: IllegalArgumentException) {
            throw Problems.unprocessable("${e.message}; nothing was loaded")
        }
        val data = lines.drop(1)
        if (data.size > maxRecords) {
            throw Problems.tooLarge("disk cache file has ${data.size} records, which is above the limit of $maxRecords; nothing was loaded")
        }
        return data.map { line ->
            try {
                org.oip.historian.ArchiveLines.parseRecord(line)
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable("${e.message}; nothing was loaded")
            }
        }
    }

    private fun recordFromFields(
        index: Int,
        provider: String?,
        tagPath: String?,
        timestamp: String?,
        value: Any?,
        valuePresent: Boolean,
        quality: String?,
        nothing: String,
        sourceId: String? = "",
        seq: Long = 0,
        identityRequired: Boolean = false,
    ): DiskCacheRecord {
        fun bad(detail: String): Nothing = throw Problems.unprocessable("record $index: $detail; $nothing")
        if (provider == null || !PROVIDER_NAME.matches(provider)) bad("provider must be a provider name")
        if (tagPath.isNullOrBlank()) bad("tagPath is required")
        val path = try {
            normalizeTagPath(tagPath)
        } catch (e: RuntimeException) {
            bad(e.message ?: "tagPath is invalid")
        }
        if (timestamp.isNullOrBlank()) bad("timestamp is required")
        val millis = try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (e: DateTimeParseException) {
            bad("timestamp must be an ISO-8601 instant")
        } catch (e: ArithmeticException) {
            bad("timestamp is out of range")
        }
        if (!valuePresent) bad("value is required")
        if (quality.isNullOrBlank()) bad("quality is required")
        val parsedQuality = Quality.fromName(quality) ?: bad("unknown quality '$quality'")
        if (identityRequired && (sourceId.isNullOrBlank() || seq <= 0)) bad("sourceId and seq are required")
        return DiskCacheRecord(provider, HistorySample(path, millis, value, parsedQuality), sourceId ?: "", seq)
    }

    /** [present] is false when the key was omitted. A JSON null is present and stays null. */
    private fun jsonValue(index: Int, value: Any?, present: Boolean, nothing: String): Any? {
        if (!present) return null
        return when (value) {
            null -> null
            is Boolean, is String -> value
            is Int, is Double -> value
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
            is Float -> value.toDouble()
            is Short, is Byte -> value.toInt()
            is Number -> {
                val number = value.toDouble()
                if (!number.isFinite()) throw Problems.unprocessable("record $index: value is not a finite number; $nothing")
                number
            }
            else -> throw Problems.unprocessable("record $index: value must be a string, number, boolean, or null; $nothing")
        }
    }

    private fun parseCsvValue(text: String): Any? {
        if (text.isEmpty()) return null
        val raw = if (text.length > 1 && text[0] == '\'' && text[1] in CSV_FORMULA) text.substring(1) else text
        if (raw == "true") return true
        if (raw == "false") return false
        if (raw.any { it == '.' || it == 'e' || it == 'E' }) {
            raw.toDoubleOrNull()?.let { return it }
        } else {
            raw.toLongOrNull()?.let { return if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
        }
        return raw
    }

    private fun unknownFields(obj: Map<*, *>, allowed: Set<String>, nothing: String, where: String = "") {
        val unknown = obj.keys.map { it.toString() }.filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            val prefix = if (where.isEmpty()) "" else "$where: "
            throw Problems.unprocessable("${prefix}unknown fields: ${unknown.joinToString()}; $nothing")
        }
    }

    private fun rejectOversized(bytes: ByteArray, what: String, nothing: String) {
        if (bytes.size > MAX_BYTES) {
            throw Problems.tooLarge("$what is ${bytes.size} bytes, which is above the limit of $MAX_BYTES; $nothing")
        }
    }

    private fun decode(bytes: ByteArray, what: String, nothing: String): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .trimStart('\uFEFF')
            .trim()
    } catch (e: CharacterCodingException) {
        throw Problems.badRequest("$what is not valid UTF-8; $nothing")
    }

    private fun mediaType(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** RFC 4180. A row that ends inside quotes is rejected. */
    private fun csvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val cell = StringBuilder()
        var row = ArrayList<String>()
        var quoted = false
        var i = 0
        fun finishRow() {
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
                    i++
                    continue
                }
                cell.append(c)
                i++
                continue
            }
            when (c) {
                '"' -> {
                    quoted = true
                    i++
                }
                ',' -> {
                    row.add(cell.toString())
                    cell.clear()
                    i++
                }
                '\n' -> {
                    finishRow()
                    i++
                }
                '\r' -> {
                    finishRow()
                    i++
                    if (i < text.length && text[i] == '\n') i++
                }
                else -> {
                    cell.append(c)
                    i++
                }
            }
        }
        if (quoted) throw IllegalArgumentException("a quoted value is not closed")
        if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    private val CSV_FORMULA = "=+-@\t\r"
}
