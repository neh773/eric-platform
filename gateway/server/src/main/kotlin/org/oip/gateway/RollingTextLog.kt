package org.oip.gateway

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * `logging` in gateway.yaml (GAT-025): the plain-text rolling log files under `logs/`.
 *
 *  - `files`: write the files at all. On by default.
 *  - `maxFileKb`: size at which the current file rolls over. Default 5120 (5 MiB).
 *  - `maxFiles`: files kept in total, the live one included. Default 5, so disk use stays under
 *    `maxFiles * maxFileKb`.
 *
 * Doc source: FEATURES GAT-025.
 */
data class LogFileSettings(val enabled: Boolean = true, val maxFileKb: Int = 5_120, val maxFiles: Int = 5) {
    init {
        require(maxFileKb in 1..1_048_576) { "logging.maxFileKb out of range: $maxFileKb" }
        require(maxFiles in 1..100) { "logging.maxFiles out of range: $maxFiles" }
    }

    companion object {
        private val KEYS = setOf("files", "maxFileKb", "maxFiles")

        fun parse(map: Map<String, Any?>): LogFileSettings {
            val unknown = map.keys - KEYS
            require(unknown.isEmpty()) { "unknown logging keys: $unknown" }
            fun whole(key: String, default: Int): Int = when (val v = map[key]) {
                null -> default
                is Int -> v
                else -> throw IllegalArgumentException("logging.$key must be a whole number")
            }
            val files = when (val v = map["files"]) {
                null -> true
                is Boolean -> v
                else -> throw IllegalArgumentException("logging.files must be true or false")
            }
            return LogFileSettings(files, whole("maxFileKb", 5_120), whole("maxFiles", 5))
        }
    }
}

/**
 * Plain-text log file (`gateway.log`) that rolls by size: when the live file reaches [maxFileBytes] it becomes
 * `gateway.log.1`, older generations shift up, and anything past [maxFiles] files in total is deleted.
 * It is independent of the web log viewer's ring and of the structured `gateway.jsonl`.
 */
class RollingTextLog(
    private val dir: Path,
    private val maxFileBytes: Long,
    private val maxFiles: Int,
    private val baseName: String = "gateway.log",
) : AutoCloseable {
    private val lock = Any()
    private val live = dir.resolve(baseName)
    private var writer: BufferedWriter
    private var size = 0L
    private var open = true

    init {
        require(maxFileBytes > 0 && maxFiles >= 1) { "rolling log needs a positive size and file count" }
        dir.createDirectories()
        writer = openWriter()
    }

    fun write(line: String) = synchronized(lock) {
        if (!open) return
        val bytes = line.toByteArray(StandardCharsets.UTF_8).size + 1L
        if (size > 0 && size + bytes > maxFileBytes) roll()
        writer.write(line)
        writer.newLine()
        writer.flush()
        size += bytes
    }

    override fun close() = synchronized(lock) {
        if (!open) return
        open = false
        runCatching { writer.flush(); writer.close() }
        Unit
    }

    private fun openWriter(): BufferedWriter {
        size = if (live.exists()) live.fileSize() else 0L
        return Files.newBufferedWriter(live, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun roll() {
        writer.flush()
        writer.close()
        Files.deleteIfExists(dir.resolve("$baseName.${maxFiles - 1}"))
        for (i in maxFiles - 2 downTo 1) {
            val from = dir.resolve("$baseName.$i")
            if (from.exists()) Files.move(from, dir.resolve("$baseName.${i + 1}"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        if (maxFiles > 1) Files.move(live, dir.resolve("$baseName.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        else Files.deleteIfExists(live)
        writer = openWriter()
    }

    companion object {
        fun of(dir: Path, settings: LogFileSettings): RollingTextLog? =
            if (settings.enabled) RollingTextLog(dir, settings.maxFileKb * 1024L, settings.maxFiles) else null

        /** One line: `2026-11-10T12:00:00.000Z INFO  logger message key=value ...`, newlines in the message escaped. */
        fun format(record: LogRecord): String = buildString {
            append(record.ts).append(' ').append(record.level.name.padEnd(5)).append(' ').append(record.logger).append(' ')
            append(flat(record.message))
            for ((k, v) in record.fields) append(' ').append(k).append('=').append(flat(v?.toString() ?: "null"))
        }

        private fun flat(s: String) = s.replace("\r", "\\r").replace("\n", "\\n")
    }
}
