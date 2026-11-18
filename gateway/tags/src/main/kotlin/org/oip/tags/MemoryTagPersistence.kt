package org.oip.tags

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Engine side of memory-tag persistence. Called on the tag write path, so it must not block. */
internal interface MemorySink {
    /** A memory tag's value changed. [handle] is the engine's node; it is read again at flush time. */
    fun dirty(handle: Any)

    /** The tag at [key] was deleted (not re-expanded). */
    fun removed(key: String)

    /** The persisted value for [key], if it was written under the same data type and seed as [config]. */
    fun restore(key: String, config: TagConfig): RestoredValue?
}

internal class RestoredValue(val value: Any?, val quality: Quality, val timestamp: Instant)

internal class MemorySnapshot(val key: String, val display: String, val config: TagConfig, val value: QualifiedValue)

/**
 * Memory-tag values kept across restarts (TAG-016, "memory tags ... persisted across restarts":
 * -) in the
 * embedded runtime database of ADR 0008: `<dataDir>/db/memory-tags.sqlite`, WAL journal.
 *
 * **Write path.** A memory value change adds the tag's node to a coalescing set and nothing else, so
 * the engine's write and scan path does no encoding and no disk I/O. The set holds at most one entry
 * per memory tag however fast the tags change. Every [flushMillis] a single background thread reads
 * the current value of each dirty tag under the engine's shared lock and writes them in one
 * transaction; [close] flushes once more.
 *
 * **Crash window.** A process that dies without [close] loses at most the changes since the last
 * completed flush: up to [flushMillis] (1 s by default). WAL with `synchronous=NORMAL` makes each
 * flush atomic, so a crash leaves the previous flush intact and never a partly written database.
 * (After a power cut, as opposed to a process crash, NORMAL may also drop the last few commits; it
 * still does not corrupt the file.)
 *
 * **Restore.** Rows are read once at [open]. When the engine creates a memory tag it takes that
 * tag's row only if the row was written under the tag's current data type and seed `value`; otherwise
 * the configured seed wins and the stale row is deleted. This is the rule a UDT re-expansion already
 * uses to keep a memory member's runtime value. Value, quality, and timestamp are all restored.
 *
 * **Delete and move.** Deleting a tag deletes its row. Changing a tag resource's `path` is a move
 * ([TagConfigBinding]): a plain memory tag keeps its value at the new path under the same rule, and
 * the old path's row is deleted. A moved UDT instance's members start from their seeds.
 *
 * Open this before [TagConfigBinding.attach] so configured tags can restore; system-provider tags
 * are never persisted. Rows still unclaimed after the tags are loaded belong to tags deleted while
 * the gateway was down; [pruneUnclaimed] removes them.
 */
class MemoryTagPersistence private constructor(
    private val engine: TagEngine,
    private val connection: Connection,
    val flushMillis: Long,
    private val loaded: ConcurrentHashMap<String, Row>,
) : AutoCloseable {
    private class Row(val dataType: String, val seed: String, val value: String?, val quality: String, val timestampMicros: Long)

    private val dirty: MutableSet<Any> = ConcurrentHashMap.newKeySet()
    private val tombstones: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val flushLock = Any()
    private val flushes = AtomicLong()
    private val failures = AtomicLong()
    @Volatile private var closed = false
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "memory-tag-flush").apply { isDaemon = true }
    }

    init {
        timer.scheduleWithFixedDelay({ runCatching { flush() } }, flushMillis, flushMillis, TimeUnit.MILLISECONDS)
    }

    /** Completed flushes, and flushes that failed (their changes stay queued for the next one). */
    fun flushCount(): Long = flushes.get()
    fun failureCount(): Long = failures.get()

    internal val sink = object : MemorySink {
        override fun dirty(handle: Any) {
            dirty.add(handle)
        }

        override fun removed(key: String) {
            loaded.remove(key)
            tombstones.add(key)
        }

        override fun restore(key: String, config: TagConfig): RestoredValue? {
            val row = loaded.remove(key) ?: return null
            if (row.dataType != config.dataType.label() || row.seed != seedOf(config)) {
                tombstones.add(key)
                return null
            }
            val quality = runCatching { QualityCode.valueOf(row.quality) }.getOrNull() ?: return null
            val value = row.value?.let { decode(it) }
            return RestoredValue(value, Quality(quality), micros(row.timestampMicros))
        }
    }

    /** Write every pending change now. Returns how many rows were written or deleted. */
    fun flush(): Int = synchronized(flushLock) {
        if (closed && dirty.isEmpty() && tombstones.isEmpty()) return 0
        val gone = tombstones.toList().also { tombstones.removeAll(it.toSet()) }
        val handles = dirty.toList().also { dirty.removeAll(it.toSet()) }
        if (gone.isEmpty() && handles.isEmpty()) return 0
        val snapshots = engine.snapshotMemory(handles)
        try {
            connection.autoCommit = false
            connection.prepareStatement("DELETE FROM memory_tag WHERE tag_key = ?").use { st ->
                for (key in gone) {
                    st.setString(1, key)
                    st.addBatch()
                }
                st.executeBatch()
            }
            connection.prepareStatement(
                """INSERT INTO memory_tag(tag_key, path, data_type, seed, value, quality, ts_micros) VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(tag_key) DO UPDATE SET path = excluded.path, data_type = excluded.data_type, seed = excluded.seed,
                   value = excluded.value, quality = excluded.quality, ts_micros = excluded.ts_micros""",
            ).use { st ->
                for (s in snapshots) {
                    st.setString(1, s.key)
                    st.setString(2, s.display)
                    st.setString(3, s.config.dataType.label())
                    st.setString(4, seedOf(s.config))
                    st.setString(5, s.value.value?.let { encode(it) })
                    st.setString(6, s.value.quality.code.name)
                    st.setLong(7, micros(s.value.timestamp))
                    st.addBatch()
                }
                st.executeBatch()
            }
            connection.commit()
            flushes.incrementAndGet()
            gone.size + snapshots.size
        } catch (e: SQLException) {
            runCatching { connection.rollback() }
            failures.incrementAndGet()
            // Keep the changes queued; the node handles still point at the latest values.
            tombstones.addAll(gone)
            dirty.addAll(handles)
            throw e
        } finally {
            runCatching { connection.autoCommit = true }
        }
    }

    /** Delete rows that no configured tag claimed. Call once the tag configuration has loaded. */
    fun pruneUnclaimed(): Int {
        val keys = loaded.keys.toList()
        keys.forEach { loaded.remove(it) }
        tombstones.addAll(keys)
        return keys.size
    }

    /** Stop the timer, write what is pending, and detach from the engine. */
    override fun close() {
        if (closed) return
        timer.shutdown()
        runCatching { timer.awaitTermination(5, TimeUnit.SECONDS) }
        try {
            flush()
        } finally {
            closed = true
            if (engine.memorySink === sink) engine.memorySink = null
            runCatching { connection.close() }
        }
    }

    companion object {
        const val DEFAULT_FLUSH_MILLIS = 1000L
        /** File name under `<dataDir>/db/` (ADR 0008: one database file per concern). */
        const val FILE_NAME = "memory-tags.sqlite"

        private val mapper = ObjectMapper()
        private const val UNSET = "{\"unset\":true}"

        /** Open (or create) [file], load its rows, and attach to [engine]. */
        fun open(engine: TagEngine, file: Path, flushMillis: Long = DEFAULT_FLUSH_MILLIS): MemoryTagPersistence {
            require(flushMillis > 0) { "flushMillis must be > 0" }
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
            try {
                connection.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute("PRAGMA synchronous=NORMAL")
                    st.execute("PRAGMA busy_timeout=5000")
                    st.execute(
                        """CREATE TABLE IF NOT EXISTS memory_tag(
                             tag_key TEXT PRIMARY KEY, path TEXT NOT NULL, data_type TEXT NOT NULL, seed TEXT NOT NULL,
                             value TEXT, quality TEXT NOT NULL, ts_micros INTEGER NOT NULL)""",
                    )
                }
                val rows = ConcurrentHashMap<String, Row>()
                connection.createStatement().use { st ->
                    st.executeQuery("SELECT tag_key, data_type, seed, value, quality, ts_micros FROM memory_tag").use { rs ->
                        while (rs.next()) rows[rs.getString(1)] = Row(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6))
                    }
                }
                val persistence = MemoryTagPersistence(engine, connection, flushMillis, rows)
                engine.memorySink = persistence.sink
                return persistence
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        }

        private fun seedOf(config: TagConfig): String = if (!config.valueSet) UNSET else encode(config.value)

        private fun encode(value: Any?): String = mapper.writeValueAsString(plain(value))

        /** Instants as ISO strings; [coerce] turns them back into the tag's data type on restore. */
        private fun plain(value: Any?): Any? = when (value) {
            is Instant -> value.toString()
            is Dataset -> plain(value.toWire())
            is List<*> -> value.map { plain(it) }
            is Map<*, *> -> value.entries.associate { it.key.toString() to plain(it.value) }
            else -> value
        }

        private fun decode(text: String): Any? = mapper.readValue(text, Any::class.java)

        private fun micros(t: Instant): Long = Math.addExact(Math.multiplyExact(t.epochSecond, 1_000_000L), (t.nano / 1000).toLong())
        private fun micros(v: Long): Instant = Instant.ofEpochSecond(Math.floorDiv(v, 1_000_000L), Math.floorMod(v, 1_000_000L) * 1000L)
    }
}
