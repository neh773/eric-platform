@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package org.oip.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Structured gateway log (one JSON object per line) with a per-logger level that is read on every call,
 * so a change applies to the next record without a restart.
 *
 */
enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR;

    companion object {
        fun parse(raw: String): LogLevel = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown log level '$raw'")
    }
}

data class LogRecord(
    val ts: String,
    val level: LogLevel,
    val logger: String,
    val message: String,
    val fields: Map<String, Any?> = emptyMap(),
)

class StructuredLog(
    private val dir: Path,
    private val maxFileBytes: Long = 5_000_000,
    private val ringSize: Int = 2_000,
    val defaultLevel: LogLevel = LogLevel.INFO,
    /** Plain-text rolling files beside the structured log (GAT-025); null when `logging.files` is off. */
    private val text: RollingTextLog? = null,
) : AutoCloseable {
    private val lock = Any()
    private val json = ObjectMapper()
    private val file = dir.resolve("gateway.jsonl")
    private val levelsFile = dir.resolve("levels.tsv")
    private val levels = linkedMapOf<String, LogLevel>()
    private val ring = ArrayDeque<LogRecord>()
    private var writer: BufferedWriter
    private var size = 0L
    private var open = true

    init {
        dir.createDirectories()
        loadLevels()
        writer = openWriter()
    }

    fun setLevel(logger: String, level: LogLevel) = synchronized(lock) {
        require(logger.isNotBlank() && logger.length <= 200) { "invalid logger name" }
        levels[logger] = level
        saveLevels()
    }

    fun level(logger: String): LogLevel = synchronized(lock) { effective(logger) }

    fun configuredLevels(): Map<String, String> = synchronized(lock) { levels.mapValues { it.value.name } }

    /** Returns true when the record was kept. A level below the effective logger level is dropped immediately. */
    fun log(level: LogLevel, logger: String, message: String, fields: Map<String, Any?> = emptyMap()): Boolean = synchronized(lock) {
        if (!open) return false
        if (level.ordinal < effective(logger).ordinal) return false
        val record = LogRecord(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(), level, logger, message, fields)
        ring.addLast(record)
        while (ring.size > ringSize) ring.removeFirst()
        val line = json.writeValueAsString(record)
        if (size >= maxFileBytes) rotate()
        writer.write(line)
        writer.newLine()
        size += line.length + 1L
        runCatching { text?.write(RollingTextLog.format(record)) }
        true
    }

    fun query(min: LogLevel = LogLevel.TRACE, loggerPrefix: String? = null, text: String? = null, limit: Int = 200): List<LogRecord> {
        val copy = synchronized(lock) { ring.toList() }
        return copy.asSequence()
            .filter { it.level.ordinal >= min.ordinal }
            .filter { loggerPrefix == null || it.logger == loggerPrefix || it.logger.startsWith("$loggerPrefix.") }
            .filter { text == null || it.message.contains(text) || it.fields.values.any { v -> v?.toString()?.contains(text) == true } }
            .toList()
            .takeLast(limit)
    }

    /** Both the live file and the previous rotation, oldest first. */
    fun download(): ByteArray {
        synchronized(lock) { if (open) writer.flush() }
        val out = java.io.ByteArrayOutputStream()
        val previous = dir.resolve("gateway.jsonl.1")
        if (previous.exists()) out.write(Files.readAllBytes(previous))
        if (file.exists()) out.write(Files.readAllBytes(file))
        return out.toByteArray()
    }

    override fun close() = synchronized(lock) {
        if (!open) return
        open = false
        runCatching { writer.flush(); writer.close() }
        runCatching { text?.close() }
        Unit
    }

    private fun effective(name: String): LogLevel {
        var bestLen = -1
        var best = defaultLevel
        for ((prefix, level) in levels) {
            if ((name == prefix || name.startsWith("$prefix.")) && prefix.length > bestLen) {
                best = level
                bestLen = prefix.length
            }
        }
        return best
    }

    private fun loadLevels() {
        if (!levelsFile.exists()) return
        for (line in levelsFile.readLines()) {
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2) runCatching { levels[parts[0]] = LogLevel.parse(parts[1]) }
        }
    }

    private fun saveLevels() {
        val text = levels.entries.joinToString("") { "${it.key}\t${it.value.name}\n" }
        levelsFile.writeText(text)
    }

    private fun openWriter(): BufferedWriter {
        size = if (file.exists()) file.fileSize() else 0L
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun rotate() {
        writer.flush()
        writer.close()
        val bak = dir.resolve("gateway.jsonl.1")
        Files.deleteIfExists(bak)
        if (file.exists()) Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING)
        size = 0
        writer = openWriter()
    }
}
