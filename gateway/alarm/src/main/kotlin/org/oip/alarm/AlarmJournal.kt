package org.oip.alarm

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories

/**
 * Alarm journal in the runtime SQLite database (ADR 0008): one file per concern under `db/`.
 * Columns follow the published alarm_events and alarm_event_data tables.
 *
 * By default only events at or above Low are stored, and events that occur while the alarm is
 * shelved are not stored.
 *
 * Writes are asynchronous (P2-14). [append] only checks the storage filters and queues the row; one
 * writer thread, `alarm-journal-writer`, inserts rows in the order they were queued. The alarm engine
 * calls [append] from tag subscriber threads, so a slow disk never holds up a tag update. Readers
 * ([records], [query], [purgeOlderThan]) wait for rows that were already queued, so a caller sees its
 * own writes.
 *
 * Retention: the docs describe pruning as off by default, with a configurable age past which rows are
 * deleted. [maxAge] is that age (null keeps rows forever). The writer thread applies it once at start
 * and then every [purgeInterval]. The age is kept in this database file (`journal_settings`).
 *
 * Shelve and unshelve operator actions are journaled as event types [JournalEventTypes.SHELVED] and
 * [JournalEventTypes.UNSHELVED]. The published event type list has no such codes (DIFFERENCES.md).
 *
 * A failed write is retried (review-3 F5). The writer holds the failed batch and retries it before anything newer,
 * with a backoff from [RETRY_INITIAL_MILLIS] doubling to [RETRY_MAX_MILLIS], for as long as it takes. Rows queued
 * meanwhile wait in the bounded queue; only a row that finds the queue full is lost, and it is counted in
 * [lostRows]. [stats] reports the queue, the retry state and the losses for diagnostics.
 *
 * The same file keeps the engine's live alarm state (`alarm_live_state`, review-3 F4): [saveState] queues an upsert
 * or delete that the writer applies in the same transaction and order as the journal rows queued before it, so the
 * saved state never runs ahead of the rows it describes. [loadStates] reads it back at start-up.
 *
 * Named database connection (DAT-015): [useConnection] moves the journal rows (`alarm_events`, `alarm_event_data`)
 * to a gateway database connection, borrowed from that connection's pool for each write and read, the same pool the
 * history providers, named queries and reports use. SQLite and PostgreSQL connections are supported. The live alarm
 * state and the retention age stay in this file. Within one batch the rows commit on the connection first and the
 * state updates after them, so the saved state still never runs ahead of its rows. A missing, faulted or
 * unsupported connection is not a silent failure: the batch is held and retried as above, and [stats] reports the
 * connection, its status and the error, even before any alarm is written. Reads from such a connection throw
 * [JournalUnavailable].
 */
class AlarmJournal(
    val file: Path,
    val settings: AlarmSettings = AlarmSettings(),
    val clock: Clock = Clock.systemUTC(),
    private val purgeInterval: Duration = Duration.ofHours(1),
) : AutoCloseable {
    private val lock = Any()
    private var conn: Connection = open()
    private val pending = LinkedBlockingQueue<Item>(QUEUE_LIMIT)
    private val accepted = AtomicLong()
    private val processed = AtomicLong()
    private val failed = AtomicLong()
    private val dropped = AtomicLong()
    private val droppedStates = AtomicLong()
    private val failedAttempts = AtomicLong()
    @Volatile private var heldRows = 0
    @Volatile private var lastError: String? = null
    @Volatile private var lastErrorMillis: Long? = null
    private val progress = Object()
    @Volatile private var closed = false
    @Volatile private var maxAgeValue: Duration? = loadMaxAge()
    /** Where the rows go when they do not go to [file] (DAT-015). Null: this file. */
    @Volatile private var remote: Remote? = null

    /** Test hook. Runs on the writer thread before each batch, outside every lock. */
    @Volatile internal var beforeWrite: (() -> Unit)? = null

    private val writer = Thread(::writeLoop, "alarm-journal-writer").apply {
        isDaemon = true
        start()
    }

    init {
        file.parent?.createDirectories()
    }

    /** Rows older than this are deleted by retention. Null keeps rows forever (the documented default). */
    val maxAge: Duration? get() = maxAgeValue

    /**
     * Rows that will never be stored: the queue was full when they were appended, or the journal closed while their
     * batch was still failing. A failed insert alone is not a loss; the batch is retried (review-3 F5).
     */
    val lostRows: Long get() = dropped.get() + failed.get()

    /** Journal health for diagnostics (review-3 F5), and where the rows are stored (DAT-015). */
    fun stats(): Map<String, Any?> {
        val target = remote
        return linkedMapOf(
            "queued" to pending.size + heldRows,
            "retrying" to (heldRows > 0),
            "failedWriteAttempts" to failedAttempts.get(),
            "lostRows" to lostRows,
            "lostStateUpdates" to droppedStates.get(),
            "lastWriteError" to lastError,
            "lastWriteErrorAt" to lastErrorMillis?.let { Instant.ofEpochMilli(it).toString() },
            "storage" to if (target == null) STORAGE_BUILT_IN else STORAGE_CONNECTION,
            "connection" to target?.name,
            "connectionStatus" to (target?.status ?: STATUS_VALID),
            "connectionError" to target?.error,
            "database" to (target?.dialect?.wire ?: if (target == null) JournalDialect.SQLITE.wire else null),
        )
    }

    /** The named database connection the rows go to, or null for this journal's own file. */
    val connection: String? get() = remote?.name

    /**
     * Store the journal rows on database connection [name], borrowing from [source] (DAT-015), or in this journal's
     * own file when [name] is null or empty. Rows still queued go to the new place. Rows already written stay where
     * they are; they are not copied. The connection is checked and its tables created on the writer thread, so this
     * call does not wait on the database. Until that check passes, [stats] says `Pending`, then `Valid` or `Faulted`.
     */
    fun useConnection(name: String?, source: JournalConnectionSource?) {
        val target = if (name.isNullOrEmpty()) null else Remote(name, requireNotNull(source) { "a connection source is required" })
        val current = remote
        if (current?.name == target?.name && current?.source === target?.source) return
        remote = target
        if (target != null) wake()
    }

    /**
     * Queue [entry]. Returns false when the storage filters skip it, the journal is closed, or the queue
     * is full (the row is counted in [lostRows]). Never blocks on disk.
     */
    fun append(entry: JournalEntry): Boolean {
        if (entry.priority < settings.minimumPriority) return false
        if (entry.shelved && !settings.storeShelvedEvents) return false
        if (closed) return false
        if (!pending.offer(Row(entry))) {
            dropped.incrementAndGet()
            log.log(System.Logger.Level.WARNING, "alarm journal queue is full; dropped ${JournalEventTypes.name(entry.eventType)} event for ${entry.source}")
            return false
        }
        accepted.incrementAndGet()
        return true
    }

    /**
     * Queue the live state of the alarm [source] (an opaque document from the engine), or its removal when [state]
     * is null. Applied in queue order with the journal rows. Never blocks on disk. False when the queue is full.
     */
    fun saveState(source: String, state: String?): Boolean {
        if (closed) return false
        if (!pending.offer(StateUpdate(source, state))) {
            droppedStates.incrementAndGet()
            log.log(System.Logger.Level.WARNING, "alarm journal queue is full; live state of $source was not saved")
            return false
        }
        accepted.incrementAndGet()
        return true
    }

    /** Saved live alarm state by source, as written by [saveState]. Read once at start-up. */
    fun loadStates(): Map<String, String> = synchronized(lock) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT source, state FROM alarm_live_state").use { rs ->
                val out = LinkedHashMap<String, String>()
                while (rs.next()) out[rs.getString(1)] = rs.getString(2)
                out
            }
        }
    }

    /**
     * Events whose journal is not closed: an active row with no clear row, or with no acknowledgement (an ack row,
     * or a row flagged Acknowledged). Waits for queued rows first.
     */
    fun openEvents(): List<OpenJournalEvent> {
        flush()
        return rows { conn, _ ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT event_id, MIN(source), MIN(display_path), MAX(priority), " +
                        "MAX(CASE WHEN event_type = ${AlarmEventType.CLEAR} THEN 1 ELSE 0 END), " +
                        "MAX(CASE WHEN event_type = ${AlarmEventType.ACKNOWLEDGED} OR (event_flags & ${AlarmEventFlags.ACKNOWLEDGED}) != 0 THEN 1 ELSE 0 END) " +
                        "FROM alarm_events WHERE event_type IN (${AlarmEventType.ACTIVE}, ${AlarmEventType.CLEAR}, ${AlarmEventType.ACKNOWLEDGED}) " +
                        "GROUP BY event_id " +
                        "HAVING MAX(CASE WHEN event_type = ${AlarmEventType.ACTIVE} THEN 1 ELSE 0 END) = 1 " +
                        "AND (MAX(CASE WHEN event_type = ${AlarmEventType.CLEAR} THEN 1 ELSE 0 END) = 0 " +
                        "OR MAX(CASE WHEN event_type = ${AlarmEventType.ACKNOWLEDGED} OR (event_flags & ${AlarmEventFlags.ACKNOWLEDGED}) != 0 THEN 1 ELSE 0 END) = 0)",
                ).use { rs ->
                    buildList {
                        while (rs.next()) add(OpenJournalEvent(
                            eventId = rs.getString(1),
                            source = rs.getString(2),
                            displayPath = rs.getString(3),
                            priority = rs.getInt(4),
                            cleared = rs.getInt(5) == 1,
                            acknowledged = rs.getInt(6) == 1,
                        ))
                    }
                }
            }
        }
    }

    /** Wait until every row queued before this call has been written or has failed. */
    fun flush(timeout: Duration = Duration.ofSeconds(10)): Boolean {
        if (Thread.currentThread() === writer) return true
        val target = accepted.get()
        val deadline = System.nanoTime() + timeout.toNanos()
        synchronized(progress) {
            while (processed.get() < target) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(progress, left)
            }
        }
        return true
    }

    /**
     * Committed rows with id greater than [afterId], oldest first (edge sync reads these). Rows still in
     * the write queue are not included; they appear on a later call. Does not wait for the writer.
     */
    fun recordsAfter(afterId: Long, limit: Int): List<JournalRecord> {
        require(limit in 1..MAX_PAGE) { "limit must be 1..$MAX_PAGE" }
        return rows { conn, _ ->
            attachData(conn, select(conn, "SELECT $COLUMNS FROM alarm_events WHERE id > ? ORDER BY id ASC LIMIT ?", listOf(afterId, limit)))
        }
    }

    /** Largest committed row id, or 0 when the journal is empty. */
    fun maxId(): Long = rows { conn, _ ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM alarm_events").use { rs -> if (!rs.next()) 0L else rs.getLong(1) }
        }
    }

    /** Every row, oldest first, with its data. */
    fun records(): List<JournalRecord> {
        flush()
        return rows { conn, _ ->
            val events = select(conn, "SELECT $COLUMNS FROM alarm_events ORDER BY id", emptyList())
            attachData(conn, events)
        }
    }

    /**
     * One page of rows matching [q]. Time range is start inclusive, end exclusive. Source and display path
     * use the same comma-separated `*` patterns as the live alarm filter.
     */
    fun query(q: JournalQuery = JournalQuery()): JournalPage {
        require(q.offset >= 0) { "offset must not be negative" }
        require(q.limit in 1..MAX_PAGE) { "limit must be between 1 and $MAX_PAGE" }
        flush()
        val dialect = dialect()
        val where = ArrayList<String>()
        val args = ArrayList<Any>()
        q.start?.let { where += "event_time >= ?"; args += it.toEpochMilli() }
        q.end?.let { where += "event_time < ?"; args += it.toEpochMilli() }
        q.minPriority?.let { where += "priority >= ?"; args += it.code }
        q.maxPriority?.let { where += "priority <= ?"; args += it.code }
        q.eventTypes?.let { types ->
            if (types.isEmpty()) return JournalPage(emptyList(), 0, q.offset, q.limit)
            where += "event_type IN (${types.joinToString(",") { "?" }})"
            args.addAll(types.sorted())
        }
        globClause("source", q.source, dialect)?.let { (sql, values) -> where += sql; args.addAll(values) }
        globClause("display_path", q.displayPath, dialect)?.let { (sql, values) -> where += sql; args.addAll(values) }
        q.sourceAllowed?.let { admits ->
            // The admitted sources are listed, not the refused ones, so a source first written after this
            // check is left out of this page rather than shown unchecked.
            val base = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
            val sources = rows { conn, _ ->
                conn.prepareStatement("SELECT DISTINCT source FROM alarm_events$base").use { ps ->
                    bind(ps, args)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            }
            val allowed = sources.filter(admits)
            if (allowed.isEmpty()) return JournalPage(emptyList(), 0, q.offset, q.limit)
            where += dialect.inJsonArray("source")
            args += jsonStrings(allowed)
        }
        val filter = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val order = if (q.newestFirst) "DESC" else "ASC"
        return rows { conn, _ ->
            val total = conn.prepareStatement("SELECT COUNT(*) FROM alarm_events$filter").use { ps ->
                bind(ps, args)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
            val rows = select(
                conn,
                "SELECT $COLUMNS FROM alarm_events$filter ORDER BY event_time $order, id $order LIMIT ? OFFSET ?",
                args + listOf(q.limit, q.offset),
            )
            JournalPage(attachData(conn, rows), total, q.offset, q.limit)
        }
    }

    /** Delete rows whose event time is before [cutoff]. Returns the number of events deleted. */
    fun purgeOlderThan(cutoff: Instant): Int {
        flush()
        return purgeNow(cutoff)
    }

    /** Apply [maxAge] now. Returns rows deleted, or 0 when retention is off. */
    fun applyRetention(): Int {
        val age = maxAgeValue ?: return 0
        return purgeOlderThan(clock.instant().minus(age))
    }

    /** Set and persist the retention age. Null or zero turns pruning off. */
    fun setMaxAge(age: Duration?) {
        require(age == null || !age.isNegative) { "retention age must not be negative" }
        val value = age?.takeUnless { it.isZero }
        synchronized(lock) {
            if (value == null) {
                conn.prepareStatement("DELETE FROM journal_settings WHERE name = 'maxAgeMillis'").use { it.executeUpdate() }
            } else {
                conn.prepareStatement(
                    "INSERT INTO journal_settings(name, value) VALUES ('maxAgeMillis', ?) " +
                        "ON CONFLICT(name) DO UPDATE SET value = excluded.value",
                ).use { ps -> ps.setString(1, value.toMillis().toString()); ps.executeUpdate() }
            }
            maxAgeValue = value
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        writer.interrupt()
        writer.join(TimeUnit.SECONDS.toMillis(10))
        synchronized(lock) { runCatching { conn.close() } }
    }

    private fun writeLoop() {
        var lastPurge = 0L
        var purged = false
        var held: List<Item>? = null
        var backoff = RETRY_INITIAL_MILLIS
        while (true) {
            remote?.let { target -> if (target.dialect == null) prepareIfDue(target) }
            val batch = held ?: try {
                pending.poll(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                null
            }?.let { first ->
                val drained = ArrayList<Item>(64)
                drained += first
                pending.drainTo(drained, BATCH - 1)
                drained
            }
            if (batch != null) {
                val left = writeBatch(batch)
                if (left == null) {
                    held = null
                    heldRows = 0
                    backoff = RETRY_INITIAL_MILLIS
                } else if (closed) {
                    // Closing with the store still failing: what is left is lost, and counted.
                    giveUp(left)
                    held = null
                    heldRows = 0
                    val rest = ArrayList<Item>()
                    pending.drainTo(rest)
                    if (rest.isNotEmpty()) giveUp(rest)
                } else {
                    // Review-3 F5: hold the batch and retry it before anything newer, with a bounded backoff.
                    held = left
                    heldRows = left.count { it is Row }
                    try {
                        Thread.sleep(backoff)
                    } catch (_: InterruptedException) {
                        // close() interrupts; the next pass makes one more attempt, then gives up.
                    }
                    backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MILLIS)
                    continue
                }
            }
            if (closed && pending.isEmpty()) break
            val now = System.nanoTime()
            if (!closed && (!purged || now - lastPurge >= purgeInterval.toNanos())) {
                purged = true
                lastPurge = now
                val age = maxAgeValue
                if (age != null) runCatching { purgeNow(clock.instant().minus(age)) }
                    .onFailure { log.log(System.Logger.Level.WARNING, "alarm journal retention failed: ${it.message}") }
            }
        }
    }

    /**
     * One attempt. Null when the batch committed; otherwise the items still to write, for the caller to retry. On a
     * named connection (DAT-015) the rows commit there first and the state updates in this file after them, so when
     * only the second step fails, only the state updates are retried and no row is written twice.
     */
    private fun writeBatch(batch: List<Item>): List<Item>? {
        var left = batch
        try {
            beforeWrite?.invoke()
            val target = remote
            if (target == null) {
                synchronized(lock) { insert(conn, JournalDialect.SQLITE, batch) }
            } else {
                val rows = batch.filterIsInstance<Row>()
                if (rows.isNotEmpty()) {
                    onConnection(target) { c, dialect -> insert(c, dialect, rows) }
                    finished(rows.size)
                    left = batch.filter { it !is Row }
                }
                if (left.isNotEmpty()) synchronized(lock) { insert(conn, JournalDialect.SQLITE, left) }
            }
        } catch (t: Throwable) {
            failedAttempts.incrementAndGet()
            lastError = errorText(t)
            lastErrorMillis = System.currentTimeMillis()
            log.log(System.Logger.Level.WARNING, "alarm journal write of ${left.size} items failed; retrying: ${t.message}")
            return left
        }
        finished(left.size)
        return null
    }

    private fun giveUp(batch: List<Item>) {
        val rows = batch.count { it is Row }
        failed.addAndGet(rows.toLong())
        droppedStates.addAndGet(batch.count { it is StateUpdate }.toLong())
        log.log(System.Logger.Level.WARNING, "alarm journal closed while a write was failing; $rows rows were not stored")
        finished(batch.size)
    }

    private fun finished(count: Int) {
        processed.addAndGet(count.toLong())
        synchronized(progress) { progress.notifyAll() }
    }

    private fun insert(conn: Connection, dialect: JournalDialect, batch: List<Item>) {
        conn.autoCommit = false
        try {
            val events = conn.prepareStatement(
                "INSERT INTO alarm_events(event_id, source, display_path, priority, event_type, event_flags, event_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)" + dialect.returningId,
            )
            val data = conn.prepareStatement(
                "INSERT INTO alarm_event_data(id, propname, dtype, intvalue, floatvalue, strvalue) VALUES (?, ?, ?, ?, ?, ?)",
            )
            // The live state is only in this journal's own file; a named connection gets rows only (DAT-015).
            val states = batch.any { it is StateUpdate }
            val upsert = if (!states) null else conn.prepareStatement(
                "INSERT INTO alarm_live_state(source, state) VALUES (?, ?) ON CONFLICT(source) DO UPDATE SET state = excluded.state",
            )
            val remove = if (!states) null else conn.prepareStatement("DELETE FROM alarm_live_state WHERE source = ?")
            upsert.use { _ -> remove.use { _ ->
            events.use { _ ->
                data.use { _ ->
                    for (item in batch) {
                        if (item === Wake) continue
                        if (item is StateUpdate) {
                            if (item.state == null) {
                                remove!!.setString(1, item.source)
                                remove.executeUpdate()
                            } else {
                                upsert!!.setString(1, item.source)
                                upsert.setString(2, item.state)
                                upsert.executeUpdate()
                            }
                            continue
                        }
                        val entry = (item as Row).entry
                        events.setString(1, entry.eventId)
                        events.setString(2, entry.source)
                        events.setString(3, entry.displayPath)
                        events.setInt(4, entry.priority.code)
                        events.setInt(5, entry.eventType)
                        events.setInt(6, entry.eventFlags)
                        events.setLong(7, entry.eventTimeMillis)
                        val id = if (dialect.returningId.isNotEmpty()) {
                            events.executeQuery().use { rs ->
                                if (!rs.next()) throw IllegalStateException("alarm journal did not return an id")
                                rs.getLong(1)
                            }
                        } else {
                            events.executeUpdate()
                            conn.createStatement().use { st ->
                                st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                                    if (!rs.next()) throw IllegalStateException("alarm journal did not return an id")
                                    rs.getLong(1)
                                }
                            }
                        }
                        var rows = 0
                        for ((name, value) in entry.data) {
                            if (value == null) continue
                            bindData(data, id, name, value)
                            data.addBatch()
                            rows++
                        }
                        if (rows > 0) data.executeBatch()
                    }
                }
            }
            } }
            conn.commit()
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    /** Run [block] where the rows are: this file under the journal lock, or a connection borrowed from the pool. */
    private fun <T> rows(block: (Connection, JournalDialect) -> T): T {
        val target = remote ?: return synchronized(lock) { block(conn, JournalDialect.SQLITE) }
        return onConnection(target, block)
    }

    /** The SQL dialect of where the rows are now. Checks a named connection first, so it can throw [JournalUnavailable]. */
    private fun dialect(): JournalDialect {
        val target = remote ?: return JournalDialect.SQLITE
        return target.dialect ?: rows { _, dialect -> dialect }
    }

    private fun <T> onConnection(target: Remote, block: (Connection, JournalDialect) -> T): T {
        val c = try {
            target.source.borrow()
        } catch (e: Exception) {
            target.fault(errorText(e))
            throw JournalUnavailable("alarm journal connection '${target.name}' is unavailable: ${errorText(e)}")
        }
        var broken = false
        try {
            val dialect = target.dialect ?: prepare(target, c)
            val out = block(c, dialect)
            target.valid()
            return out
        } catch (e: SQLException) {
            broken = !runCatching { c.isValid(1) }.getOrDefault(false)
            target.fault(errorText(e))
            throw JournalUnavailable("alarm journal connection '${target.name}' failed: ${errorText(e)}")
        } catch (e: JournalUnavailable) {
            throw e
        } finally {
            target.source.release(c, broken)
        }
    }

    /** Check the database behind [target] and create the journal tables there. Returns its dialect. */
    private fun prepare(target: Remote, c: Connection): JournalDialect {
        val product = runCatching { c.metaData.databaseProductName }.getOrNull().orEmpty()
        val dialect = JournalDialect.of(product) ?: run {
            val message = "alarm journal storage supports SQLite and PostgreSQL connections; '${target.name}' is ${product.ifBlank { "an unknown database" }}"
            target.fault(message)
            throw JournalUnavailable(message)
        }
        val auto = c.autoCommit
        c.autoCommit = true
        try {
            c.createStatement().use { st -> for (sql in dialect.rowTables()) st.execute(sql) }
        } finally {
            c.autoCommit = auto
        }
        target.dialect = dialect
        target.valid()
        return dialect
    }

    /** Writer thread: check a named connection that is not ready yet, at most once per backoff step. */
    private fun prepareIfDue(target: Remote) {
        val now = System.nanoTime()
        if (now < target.nextCheckNanos) return
        try {
            onConnection(target) { _, _ -> Unit }
            target.checkBackoff = RETRY_INITIAL_MILLIS
        } catch (e: Exception) {
            target.fault(errorText(e))
            target.nextCheckNanos = now + TimeUnit.MILLISECONDS.toNanos(target.checkBackoff)
            target.checkBackoff = (target.checkBackoff * 2).coerceAtMost(RETRY_MAX_MILLIS)
        }
    }

    /** Wake the writer so a new connection is checked now rather than on the next poll. */
    private fun wake() {
        if (pending.offer(Wake)) accepted.incrementAndGet()
    }

    private fun errorText(t: Throwable): String = when (t) {
        is JournalUnavailable -> t.message ?: "alarm journal connection is unavailable"
        else -> "${t.javaClass.simpleName}: ${t.message}"
    }

    private fun bindData(ps: java.sql.PreparedStatement, id: Long, name: String, value: Any) {
        ps.setLong(1, id)
        ps.setString(2, name)
        when (value) {
            is Boolean -> {
                ps.setInt(3, 0); ps.setLong(4, if (value) 1L else 0L); ps.setNull(5, java.sql.Types.REAL); ps.setNull(6, java.sql.Types.VARCHAR)
            }
            is Byte, is Short, is Int, is Long -> {
                ps.setInt(3, 0); ps.setLong(4, (value as Number).toLong()); ps.setNull(5, java.sql.Types.REAL); ps.setNull(6, java.sql.Types.VARCHAR)
            }
            is Number -> {
                ps.setInt(3, 1); ps.setNull(4, java.sql.Types.INTEGER); ps.setDouble(5, value.toDouble()); ps.setNull(6, java.sql.Types.VARCHAR)
            }
            else -> {
                ps.setInt(3, 2); ps.setNull(4, java.sql.Types.INTEGER); ps.setNull(5, java.sql.Types.REAL); ps.setString(6, value.toString())
            }
        }
    }

    private fun purgeNow(cutoff: Instant): Int = rows { conn, _ ->
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "DELETE FROM alarm_event_data WHERE id IN (SELECT id FROM alarm_events WHERE event_time < ?)",
            ).use { ps -> ps.setLong(1, cutoff.toEpochMilli()); ps.executeUpdate() }
            val deleted = conn.prepareStatement("DELETE FROM alarm_events WHERE event_time < ?").use { ps ->
                ps.setLong(1, cutoff.toEpochMilli())
                ps.executeUpdate()
            }
            conn.commit()
            deleted
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    private fun select(conn: Connection, sql: String, args: List<Any>): List<JournalRecord> {
        val events = ArrayList<JournalRecord>()
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    events += JournalRecord(
                        id = rs.getLong(1),
                        eventId = rs.getString(2),
                        source = rs.getString(3),
                        displayPath = rs.getString(4),
                        priority = rs.getInt(5),
                        eventType = rs.getInt(6),
                        eventFlags = rs.getInt(7),
                        eventTimeMillis = rs.getLong(8),
                        data = emptyMap(),
                    )
                }
            }
        }
        return events
    }

    private fun attachData(conn: Connection, events: List<JournalRecord>): List<JournalRecord> {
        if (events.isEmpty()) return events
        val data = HashMap<Long, LinkedHashMap<String, Any?>>()
        for (chunk in events.chunked(500)) {
            conn.prepareStatement(
                "SELECT id, propname, dtype, intvalue, floatvalue, strvalue FROM alarm_event_data " +
                    "WHERE id IN (${chunk.joinToString(",") { "?" }}) ORDER BY id, propname",
            ).use { ps ->
                chunk.forEachIndexed { i, e -> ps.setLong(i + 1, e.id) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val value: Any? = when (rs.getInt(3)) {
                            0 -> rs.getLong(4)
                            1 -> rs.getDouble(5)
                            else -> rs.getString(6)
                        }
                        data.getOrPut(rs.getLong(1)) { LinkedHashMap() }[rs.getString(2)] = value
                    }
                }
            }
        }
        return events.map { it.copy(data = data[it.id] ?: emptyMap()) }
    }

    /** A JSON array of [values], for `json_each(?)`. */
    private fun jsonStrings(values: List<String>): String = buildString {
        append('[')
        values.forEachIndexed { i, v ->
            if (i > 0) append(',')
            append('"')
            for (ch in v) {
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch < ' ' -> append(String.format("\\u%04x", ch.code))
                    else -> append(ch)
                }
            }
            append('"')
        }
        append(']')
    }

    private fun bind(ps: java.sql.PreparedStatement, args: List<Any>) {
        args.forEachIndexed { i, v ->
            when (v) {
                is Int -> ps.setInt(i + 1, v)
                is Long -> ps.setLong(i + 1, v)
                else -> ps.setString(i + 1, v.toString())
            }
        }
    }

    private fun loadMaxAge(): Duration? = synchronized(lock) {
        conn.prepareStatement("SELECT value FROM journal_settings WHERE name = 'maxAgeMillis'").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1).toLongOrNull()?.let { Duration.ofMillis(it) } else null }
        }
    }

    private fun open(): Connection {
        file.parent?.createDirectories()
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        c.createStatement().use { st ->
            for (sql in listOf(
                "PRAGMA journal_mode=WAL",
                "PRAGMA synchronous=NORMAL",
                "PRAGMA busy_timeout=5000",
                "CREATE TABLE IF NOT EXISTS alarm_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL, source TEXT NOT NULL, " +
                    "display_path TEXT NOT NULL, priority INTEGER NOT NULL, event_type INTEGER NOT NULL, " +
                    "event_flags INTEGER NOT NULL, event_time INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_alarm_events_source ON alarm_events(source)",
                "CREATE INDEX IF NOT EXISTS idx_alarm_events_time ON alarm_events(event_time)",
                "CREATE TABLE IF NOT EXISTS alarm_event_data (" +
                    "id INTEGER NOT NULL, propname TEXT NOT NULL, dtype INTEGER NOT NULL, " +
                    "intvalue INTEGER, floatvalue REAL, strvalue TEXT)",
                "CREATE INDEX IF NOT EXISTS idx_alarm_event_data_id ON alarm_event_data(id)",
                "CREATE TABLE IF NOT EXISTS journal_settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_alarm_events_event_id ON alarm_events(event_id)",
                "CREATE TABLE IF NOT EXISTS alarm_live_state (source TEXT PRIMARY KEY, state TEXT NOT NULL)",
            )) {
                if (st.execute(sql)) st.resultSet?.close()
            }
        }
        return c
    }

    companion object {
        /** Largest page [query] returns. */
        const val MAX_PAGE = 1000
        private const val QUEUE_LIMIT = 100_000
        private const val BATCH = 256
        const val RETRY_INITIAL_MILLIS = 50L
        const val RETRY_MAX_MILLIS = 5_000L
        /** [stats] `storage`: rows in this journal's own file, or on a named database connection (DAT-015). */
        const val STORAGE_BUILT_IN = "built-in"
        const val STORAGE_CONNECTION = "connection"
        const val STATUS_VALID = "Valid"
        const val STATUS_PENDING = "Pending"
        const val STATUS_FAULTED = "Faulted"
        private const val COLUMNS = "id, event_id, source, display_path, priority, event_type, event_flags, event_time"
        private val log: System.Logger = System.getLogger(AlarmJournal::class.java.name)

        /**
         * `a*b,c` to an SQL clause using GLOB, which is case-sensitive like [wildcardMatch]. `?` and `[`
         * are GLOB syntax here but literal characters in our patterns, so they are bracketed.
         */
        internal fun globClause(column: String, patterns: String?, dialect: JournalDialect = JournalDialect.SQLITE): Pair<String, List<String>>? {
            if (patterns.isNullOrBlank()) return null
            val parts = patterns.split(',').map { it.trim() }
            if (dialect == JournalDialect.POSTGRESQL) {
                // LIKE is case-sensitive in PostgreSQL, like GLOB. `*` is the only wildcard in our patterns.
                val likes = parts.map { p ->
                    buildString {
                        for (ch in p) {
                            when (ch) {
                                '*' -> append('%')
                                '%', '_', '\\' -> append('\\').append(ch)
                                else -> append(ch)
                            }
                        }
                    }
                }
                return "(" + likes.joinToString(" OR ") { "$column LIKE ? ESCAPE '\\'" } + ")" to likes
            }
            val globs = parts.map { p ->
                buildString {
                    for (ch in p) {
                        when (ch) {
                            '?' -> append("[?]")
                            '[' -> append("[[]")
                            else -> append(ch)
                        }
                    }
                }
            }
            return "(" + globs.joinToString(" OR ") { "$column GLOB ?" } + ")" to globs
        }
    }
}

/**
 * Journal event type codes. 0–2 and 4–5 are the published `eventtype` values; 6 and 7 are this
 * gateway's operator shelve and unshelve rows (DIFFERENCES.md).
 */
object JournalEventTypes {
    const val ACTIVE = AlarmEventType.ACTIVE
    const val CLEAR = AlarmEventType.CLEAR
    const val ACKNOWLEDGED = AlarmEventType.ACKNOWLEDGED
    const val ENABLED = 4
    const val DISABLED = 5
    const val SHELVED = 6
    const val UNSHELVED = 7

    private val names = linkedMapOf(
        ACTIVE to "active",
        CLEAR to "clear",
        ACKNOWLEDGED to "ack",
        ENABLED to "enabled",
        DISABLED to "disabled",
        SHELVED to "shelve",
        UNSHELVED to "unshelve",
    )

    fun name(code: Int): String = names[code] ?: code.toString()

    /** `active`, `clear`, `ack` (or `acknowledged`), `shelve`, `unshelve`, `enabled`, `disabled`. */
    fun fromName(raw: String): Int? = when (raw.trim().lowercase()) {
        "acknowledged" -> ACKNOWLEDGED
        "shelved" -> SHELVED
        "unshelved" -> UNSHELVED
        else -> names.entries.firstOrNull { it.value == raw.trim().lowercase() }?.key
    }
}

/** Queued work for the writer thread: a journal row, or a live-state upsert (null [StateUpdate.state] deletes). */
private sealed interface Item
private class Row(val entry: JournalEntry) : Item
private class StateUpdate(val source: String, val state: String?) : Item
/** Wakes the writer to check a newly named connection (DAT-015). Writes nothing. */
private object Wake : Item

/**
 * Pooled JDBC connections from a named gateway database connection (DAT-015). The gateway lends them from the same
 * pool as history providers, named queries and reports. [borrow] throws when the connection is missing, disabled
 * or faulted; its message says which.
 */
interface JournalConnectionSource {
    fun borrow(): Connection
    fun release(connection: Connection, broken: Boolean)
}

/** The journal's database connection is missing, faulted or unsupported, so rows cannot be read now. */
class JournalUnavailable(message: String) : IllegalStateException(message)

/** SQL that differs between the databases the journal rows can live in. */
enum class JournalDialect(val wire: String) {
    SQLITE("sqlite") {
        override val returningId = ""
        override fun inJsonArray(column: String) = "$column IN (SELECT value FROM json_each(?))"
        override fun rowTables() = listOf(
            "CREATE TABLE IF NOT EXISTS alarm_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL, source TEXT NOT NULL, " +
                "display_path TEXT NOT NULL, priority INTEGER NOT NULL, event_type INTEGER NOT NULL, " +
                "event_flags INTEGER NOT NULL, event_time INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS alarm_event_data (" +
                "id INTEGER NOT NULL, propname TEXT NOT NULL, dtype INTEGER NOT NULL, " +
                "intvalue INTEGER, floatvalue REAL, strvalue TEXT)",
        ) + INDEXES
    },
    POSTGRESQL("postgresql") {
        override val returningId = " RETURNING id"
        override fun inJsonArray(column: String) = "$column IN (SELECT jsonb_array_elements_text(CAST(? AS jsonb)))"
        override fun rowTables() = listOf(
            "CREATE TABLE IF NOT EXISTS alarm_events (" +
                "id BIGSERIAL PRIMARY KEY, event_id TEXT NOT NULL, source TEXT NOT NULL, " +
                "display_path TEXT NOT NULL, priority INTEGER NOT NULL, event_type INTEGER NOT NULL, " +
                "event_flags INTEGER NOT NULL, event_time BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS alarm_event_data (" +
                "id BIGINT NOT NULL, propname TEXT NOT NULL, dtype INTEGER NOT NULL, " +
                "intvalue BIGINT, floatvalue DOUBLE PRECISION, strvalue TEXT)",
        ) + INDEXES
    };

    /** Appended to the row insert so it returns the new id; empty where `last_insert_rowid()` is used. */
    abstract val returningId: String
    /** `column IN (...)` over one bound JSON array of strings. */
    abstract fun inJsonArray(column: String): String
    /** DDL for the two row tables and their indexes. */
    abstract fun rowTables(): List<String>

    companion object {
        private val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_alarm_events_source ON alarm_events(source)",
            "CREATE INDEX IF NOT EXISTS idx_alarm_events_time ON alarm_events(event_time)",
            "CREATE INDEX IF NOT EXISTS idx_alarm_events_event_id ON alarm_events(event_id)",
            "CREATE INDEX IF NOT EXISTS idx_alarm_event_data_id ON alarm_event_data(id)",
        )

        /** From JDBC `databaseProductName`; null for a database the journal does not support. */
        fun of(product: String): JournalDialect? = when {
            product.contains("sqlite", ignoreCase = true) -> SQLITE
            product.contains("postgres", ignoreCase = true) -> POSTGRESQL
            else -> null
        }
    }
}

/** A named connection the journal rows go to, with what the writer last saw of it. */
private class Remote(val name: String, val source: JournalConnectionSource) {
    @Volatile var dialect: JournalDialect? = null
    @Volatile var status: String = AlarmJournal.STATUS_PENDING
    @Volatile var error: String? = null
    @Volatile var nextCheckNanos: Long = 0
    @Volatile var checkBackoff: Long = AlarmJournal.RETRY_INITIAL_MILLIS

    fun valid() {
        status = AlarmJournal.STATUS_VALID
        error = null
    }

    /** A fault also forgets the checked dialect, so the next use checks the database and its tables again. */
    fun fault(message: String) {
        status = AlarmJournal.STATUS_FAULTED
        error = message
        dialect = null
    }
}

/** An event whose active row has no clear row, or no acknowledgement, in the journal (see [AlarmJournal.openEvents]). */
data class OpenJournalEvent(
    val eventId: String,
    val source: String,
    val displayPath: String,
    val priority: Int,
    val cleared: Boolean,
    val acknowledged: Boolean,
)

data class JournalEntry(
    val eventId: String,
    val source: String,
    val displayPath: String,
    val priority: AlarmPriority,
    val eventType: Int,
    val eventFlags: Int,
    val eventTimeMillis: Long,
    val shelved: Boolean,
    val data: Map<String, Any?>,
)

data class JournalRecord(
    val id: Long,
    val eventId: String,
    val source: String,
    val displayPath: String,
    val priority: Int,
    val eventType: Int,
    val eventFlags: Int,
    val eventTimeMillis: Long,
    val data: Map<String, Any?>,
) {
    /** Who caused the row: the acknowledging user, or the operator who shelved or unshelved. Null for system rows. */
    val actor: String? get() = (data["ackUser"] ?: data["actor"]) as? String
}

/** Filters for [AlarmJournal.query]. Null means no filter. */
data class JournalQuery(
    val start: Instant? = null,
    val end: Instant? = null,
    val source: String? = null,
    val displayPath: String? = null,
    val minPriority: AlarmPriority? = null,
    val maxPriority: AlarmPriority? = null,
    val eventTypes: Set<Int>? = null,
    val offset: Int = 0,
    val limit: Int = 100,
    val newestFirst: Boolean = true,
    /**
     * Only rows whose source this admits (the caller's per-tag read check, TAG-013). Null: every source.
     * Applied before paging, so `total`, `offset` and `limit` count only admitted rows. It is called once per
     * distinct matching source, outside the journal lock, and must not call back into the journal.
     */
    val sourceAllowed: ((String) -> Boolean)? = null,
)

data class JournalPage(
    val records: List<JournalRecord>,
    val total: Long,
    val offset: Int,
    val limit: Int,
)
