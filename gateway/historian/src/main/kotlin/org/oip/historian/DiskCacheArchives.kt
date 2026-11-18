package org.oip.historian

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * Gateway-side disk-cache archives.
 *
 * Archive moves a provider's disk cache to a gateway-side folder and Load picks one of those archives. This
 * gateway writes `<dataDir>/store-forward/archives/<provider>_<UTC timestamp>.json`: under the cache lock the
 * file is synced, renamed into place and read back, and only then are the archived rows (by seq) deleted.
 * A write or read-back that does not hold every row removes the file and deletes no row. A crash during the write
 * leaves the rows and no archive. A crash after the rename and before the delete leaves both, and a later
 * load inserts the same (sourceId, seq) so the provider stores each sample once.
 *
 * A load walks the file in pages. Each page is its own transaction, and the committed record count is synced
 * to a progress file first so a crash resumes without skipping or, together with INSERT OR IGNORE, double-loading.
 */
class DiskCacheArchives(
    val directory: Path,
    private val buffer: ForwardBuffer,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun archive(provider: String, pageSize: Int = PAGE, hooks: DiskCacheArchiveHooks = DiskCacheArchiveHooks()): ArchiveInfo {
        require(PROVIDER_NAME.matches(provider)) { "provider must be a provider name" }
        require(pageSize > 0) { "pageSize must be positive" }
        directory.createDirectories()
        val created = clock.instant()
        val name = freshName(provider, created)
        val tmp = directory.resolve("$name.partial")
        val dest = directory.resolve(name)
        try {
            buffer.withLock {
                val count = buffer.pendingCount(provider)
                var lastSeq = 0L
                var written = 0
                FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
                    writeLine(ch, ArchiveLines.header(provider, count, created))
                    while (written < count) {
                        val page = buffer.pendingPage(provider, lastSeq, minOf(pageSize, count - written))
                        if (page.isEmpty()) break
                        for (record in page) writeLine(ch, ArchiveLines.record(record))
                        written += page.size
                        lastSeq = page.last().seq
                        hooks.duringWrite?.invoke()
                    }
                    ch.force(true)
                }
                if (written != count) throw IllegalStateException("disk cache for $provider changed while it was archived; nothing was removed")
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE)
                syncDir(directory)
                hooks.beforeDelete?.invoke()
                // Read the renamed file back. Only an archive that holds every row written above, in order,
                // lets those rows leave the cache; anything else removes the file and keeps every row.
                try {
                    verify(dest, provider, count, lastSeq)
                } catch (e: Exception) {
                    dest.deleteIfExists()
                    syncDir(directory)
                    throw IllegalStateException("archive ${dest.fileName} did not read back (${e.message}); nothing was removed", e)
                }
                // Only the rows that are in the archive: a row with a later seq stays pending.
                if (count > 0) buffer.deletePendingThrough(provider, lastSeq)
            }
        } catch (e: Exception) {
            tmp.deleteIfExists()
            throw e
        }
        return info(dest)
    }

    /** [file] has the header for [provider] and exactly [records] records of it, seq ascending, ending at [lastSeq]. */
    private fun verify(file: Path, provider: String, records: Int, lastSeq: Long) {
        BufferedReader(InputStreamReader(Files.newInputStream(file), Charsets.UTF_8)).use { reader ->
            val header = ArchiveLines.parseHeader(reader.readLine() ?: throw ArchiveFormatException("archive is empty"))
            if (header.provider != provider || header.records != records) throw ArchiveFormatException("archive header does not match")
            var seen = 0
            var previous = 0L
            while (true) {
                val line = reader.readLine() ?: break
                val record = ArchiveLines.parseRecord(line)
                if (record.provider != provider || record.seq <= previous) throw ArchiveFormatException("archive record is out of order")
                previous = record.seq
                seen++
            }
            if (seen != records) throw ArchiveFormatException("archive has $seen of $records records")
            if (records > 0 && previous != lastSeq) throw ArchiveFormatException("archive ends at the wrong record")
        }
    }

    fun list(): List<ArchiveInfo> {
        if (!directory.exists()) return emptyList()
        return Files.list(directory).use { stream ->
            stream.filter { NAME.matches(it.fileName.toString()) }
                .map { info(it) }
                .toList()
                .sortedByDescending { it.created }
        }
    }

    fun path(name: String): Path {
        val file = resolve(name)
        if (!file.exists()) throw NoSuchElementException("archive $name was not found")
        return file
    }

    fun delete(name: String) {
        val file = resolve(name)
        if (!file.exists()) throw NoSuchElementException("archive $name was not found")
        Files.delete(file)
        progressPath(name).deleteIfExists()
    }

    /**
     * Load [name] in pages. [providerExists] is checked before the first insert. [noteStored] receives the
     * number of rows actually inserted for the archive's provider.
     * A finished progress file is cleared so a deliberate second load runs again; rows still pending are
     * skipped by (sourceId, seq), and rows the provider already stored are skipped there too.
     */
    fun load(
        name: String,
        pageSize: Int = PAGE,
        providerExists: (String) -> Boolean,
        hooks: DiskCacheLoadHooks = DiskCacheLoadHooks(),
        noteStored: (provider: String, inserted: Int) -> Unit = { _, _ -> },
    ): Int {
        require(pageSize > 0) { "pageSize must be positive" }
        val file = path(name)
        BufferedReader(InputStreamReader(Files.newInputStream(file), Charsets.UTF_8)).use { reader ->
            val header = ArchiveLines.parseHeader(reader.readLine() ?: throw ArchiveFormatException("archive $name is empty"))
            if (!providerExists(header.provider)) throw UnknownHistoryProvider(listOf(header.provider))
            var committed = readProgress(name)
            if (header.records == 0) return 0
            if (committed >= header.records) {
                committed = 0
                writeProgress(name, 0)
            }
            var seen = 0
            var inserted = 0
            val page = ArrayList<DiskCacheRecord>(pageSize)
            fun flush() {
                if (page.isEmpty()) return
                val start = seen - page.size
                if (start >= committed) {
                    hooks.beforeEnqueue?.invoke()
                    val n = buffer.enqueueIdentified(page.toList())
                    inserted += n
                    noteStored(header.provider, n)
                    hooks.beforeProgress?.invoke()
                    committed = seen
                    writeProgress(name, committed)
                    hooks.afterPage?.invoke(committed)
                }
                page.clear()
            }
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val record = ArchiveLines.parseRecord(line)
                if (record.provider != header.provider || !providerExists(record.provider)) {
                    throw UnknownHistoryProvider(listOf(record.provider))
                }
                page += record
                seen++
                if (page.size == pageSize) flush()
            }
            flush()
            return inserted
        }
    }

    private fun freshName(provider: String, created: Instant): String {
        val stamp = STAMP.format(created)
        var name = "${provider}_$stamp.json"
        var n = 2
        while (directory.resolve(name).exists()) {
            name = "${provider}_$stamp-$n.json"
            n++
        }
        return name
    }

    private fun resolve(name: String): Path {
        if (!NAME.matches(name)) throw IllegalArgumentException("archive name is not valid")
        val file = directory.resolve(name).normalize()
        if (file.parent?.normalize() != directory.normalize()) throw IllegalArgumentException("archive name is not valid")
        return file
    }

    private fun info(file: Path): ArchiveInfo {
        val header = BufferedReader(InputStreamReader(Files.newInputStream(file), Charsets.UTF_8)).use { reader ->
            ArchiveLines.parseHeader(reader.readLine() ?: throw ArchiveFormatException("${file.fileName} is empty"))
        }
        return ArchiveInfo(
            name = file.fileName.toString(),
            provider = header.provider,
            records = header.records,
            bytes = file.fileSize(),
            created = header.created,
        )
    }

    private fun progressPath(name: String) = directory.resolve("$name.progress")

    private fun readProgress(name: String): Int {
        val path = progressPath(name)
        if (!path.exists()) return 0
        return path.toFile().readText().trim().toIntOrNull() ?: 0
    }

    private fun writeProgress(name: String, committed: Int) {
        val path = progressPath(name)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            ch.write(ByteBuffer.wrap(committed.toString().toByteArray(Charsets.UTF_8)))
            ch.force(true)
        }
    }

    private fun writeLine(ch: FileChannel, line: String) {
        val bytes = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
        while (bytes.hasRemaining()) ch.write(bytes)
    }

    private fun syncDir(dir: Path) {
        // Linux can fsync a directory. Windows refuses to open a directory as a file channel.
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: UnsupportedOperationException) {
        } catch (_: java.nio.file.AccessDeniedException) {
        }
    }

    companion object {
        const val PAGE = 2_000
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC)
        val NAME: Regex = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?_\\d{8}T\\d{6}\\.\\d{3}Z(?:-\\d+)?\\.json$")
    }
}

data class ArchiveInfo(
    val name: String,
    val provider: String,
    val records: Int,
    val bytes: Long,
    val created: String,
)

class DiskCacheArchiveHooks(
    val duringWrite: (() -> Unit)? = null,
    val beforeDelete: (() -> Unit)? = null,
)

class DiskCacheLoadHooks(
    val beforeEnqueue: (() -> Unit)? = null,
    val beforeProgress: (() -> Unit)? = null,
    val afterPage: ((committed: Int) -> Unit)? = null,
)

class ArchiveFormatException(message: String) : IllegalArgumentException(message)

/** One JSON object per line. The first line is the header; each later line is one pending record. */
object ArchiveLines {
    fun header(provider: String, records: Int, created: Instant): String = json(
        listOf(
            "kind" to DiskCacheArchivesKind.KIND,
            "version" to DiskCacheArchivesKind.VERSION,
            "provider" to provider,
            "records" to records,
            "created" to created.toString(),
        ),
    )

    fun record(record: DiskCacheRecord): String = json(
        listOf(
            "provider" to record.provider,
            "sourceId" to record.sourceId,
            "seq" to record.seq,
            "tagPath" to record.sample.tagPath,
            "timestamp" to Instant.ofEpochMilli(record.sample.timestampMillis).toString(),
            "value" to record.sample.value,
            "quality" to record.sample.quality.code.label,
        ),
    )

    fun parseHeader(line: String): ArchiveHeader {
        val obj = parse(line)
        if (obj["kind"] != DiskCacheArchivesKind.KIND) throw ArchiveFormatException("not a disk-cache archive")
        if ((obj["version"] as? Number)?.toInt() != DiskCacheArchivesKind.VERSION) {
            throw ArchiveFormatException("disk cache archive version is not supported")
        }
        val provider = obj["provider"] as? String ?: throw ArchiveFormatException("archive provider is missing")
        if (!PROVIDER_NAME.matches(provider)) throw ArchiveFormatException("archive provider is not a provider name")
        val records = (obj["records"] as? Number)?.toInt() ?: throw ArchiveFormatException("archive record count is missing")
        if (records < 0) throw ArchiveFormatException("archive record count is negative")
        val created = obj["created"] as? String ?: throw ArchiveFormatException("archive timestamp is missing")
        return ArchiveHeader(provider, records, created)
    }

    fun parseRecord(line: String): DiskCacheRecord {
        val obj = parse(line)
        val provider = obj["provider"] as? String ?: throw ArchiveFormatException("record provider is missing")
        if (!PROVIDER_NAME.matches(provider)) throw ArchiveFormatException("record provider is not a provider name")
        val sourceId = obj["sourceId"] as? String ?: throw ArchiveFormatException("record sourceId is missing")
        if (sourceId.isBlank()) throw ArchiveFormatException("record sourceId is missing")
        val seq = (obj["seq"] as? Number)?.toLong() ?: throw ArchiveFormatException("record seq is missing")
        if (seq <= 0) throw ArchiveFormatException("record seq is missing")
        val tagPath = obj["tagPath"] as? String ?: throw ArchiveFormatException("record tagPath is missing")
        val path = try {
            normalizeTagPath(tagPath)
        } catch (e: RuntimeException) {
            throw ArchiveFormatException(e.message ?: "record tagPath is invalid")
        }
        val timestamp = obj["timestamp"] as? String ?: throw ArchiveFormatException("record timestamp is missing")
        val millis = try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (e: RuntimeException) {
            throw ArchiveFormatException("record timestamp is invalid")
        }
        if ("value" !in obj) throw ArchiveFormatException("record value is missing")
        val quality = obj["quality"] as? String ?: throw ArchiveFormatException("record quality is missing")
        val parsed = org.oip.tags.Quality.fromName(quality) ?: throw ArchiveFormatException("record quality is unknown")
        return DiskCacheRecord(provider, HistorySample(path, millis, obj["value"], parsed), sourceId, seq)
    }

    private fun json(fields: List<Pair<String, Any?>>): String {
        val sb = StringBuilder()
        sb.append('{')
        fields.forEachIndexed { i, (key, value) ->
            if (i > 0) sb.append(',')
            sb.append(quote(key)).append(':').append(literal(value))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun literal(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> if (value.isFinite()) value.toString() else throw ArchiveFormatException("value is not finite")
        is Float -> literal(value.toDouble())
        is String -> quote(value)
        else -> quote(value.toString())
    }

    private fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun parse(line: String): Map<String, Any?> = Parser(line).parseObject()

    private class Parser(val text: String) {
        var i = 0
        fun parseObject(): Map<String, Any?> {
            skip()
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                val key = parseString()
                skip()
                expect(':')
                skip()
                val value = parseValue()
                out[key] = value
                skip()
                when (peek()) {
                    ',' -> {
                        i++
                        skip()
                    }
                    '}' -> {
                        i++
                        return out
                    }
                    else -> throw ArchiveFormatException("archive line is not valid JSON")
                }
            }
        }

        private fun parseValue(): Any? {
            skip()
            return when (val c = peek()) {
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-' -> parseNumber()
                else -> if (c.isDigit()) parseNumber() else throw ArchiveFormatException("archive line is not valid JSON")
            }
        }

        private fun literal(token: String, value: Any?): Any? {
            if (!text.startsWith(token, i)) throw ArchiveFormatException("archive line is not valid JSON")
            i += token.length
            return value
        }

        private fun parseNumber(): Number {
            val start = i
            if (peek() == '-') i++
            while (i < text.length && text[i].isDigit()) i++
            var real = false
            if (i < text.length && text[i] == '.') {
                real = true
                i++
                while (i < text.length && text[i].isDigit()) i++
            }
            if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                real = true
                i++
                if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                while (i < text.length && text[i].isDigit()) i++
            }
            val token = text.substring(start, i)
            return if (real) token.toDouble() else token.toLong()
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < text.length) {
                val c = text[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= text.length) throw ArchiveFormatException("archive line is not valid JSON")
                        when (val e = text[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > text.length) throw ArchiveFormatException("archive line is not valid JSON")
                                sb.append(text.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw ArchiveFormatException("archive line is not valid JSON")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw ArchiveFormatException("archive line is not valid JSON")
        }

        private fun skip() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        private fun peek(): Char = if (i < text.length) text[i] else throw ArchiveFormatException("archive line is not valid JSON")

        private fun expect(c: Char) {
            if (peek() != c) throw ArchiveFormatException("archive line is not valid JSON")
            i++
        }
    }
}

data class ArchiveHeader(val provider: String, val records: Int, val created: String)

object DiskCacheArchivesKind {
    const val KIND = "oip.store-forward.disk-cache"
    const val VERSION = 1
}
