package org.oip.tags.query

import org.oip.db.DatabaseService
import org.oip.db.QueryFailed
import org.oip.db.QueryNotFound
import org.oip.db.QueryRejected
import org.oip.db.QueryUnavailable
import org.oip.tags.DataType
import org.oip.tags.PathException
import org.oip.tags.Quality
import org.oip.tags.QualityCode
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagPath
import org.oip.tags.TagType
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Query tags (TAG-030). A scheduler thread marks tags due at their tag group's rate and publishes
 * through [TagEngine.publishQuery]. The engine keeps read permissions, so a reader who may not read
 * the tag never sees the value. The query itself runs as the gateway: referenced tags are read with
 * [TagEngine.read] (system), and the statement runs on the named gateway connection.
 *
 * Each named datasource has one serial lane. The lanes share a pool of [LANE_POOL_SIZE] threads, so
 * one slow or hung connection occupies a single lane until [QUERY_TIMEOUT_SECONDS] (or until [close]
 * cancels it) and does not stall query tags on other connections. A tag is skipped, not queued,
 * while its previous poll is still running or still waiting on that lane, so a slow poll does not
 * pile up a catch-up burst.
 *
 *
 * Quality when the poll does not produce a new value (the last value is kept):
 * - [QualityCode.BAD_NOT_CONNECTED] when the named connection is missing, disabled, or cannot be
 *   borrowed. That is the platform's code for a tag that should receive a value from its source
 *   and cannot reach it.
 *   -
 * - [QualityCode.BAD_DEVICE_FAILURE] when the connection was borrowed and the database rejected the
 *   statement. OPC UA Bad_DeviceFailure (0x808B0000) is the closest existing code for a source that
 *   answered and then failed the read.
 * A bad statement shape (empty text, a write, more than one statement) is [QualityCode.ERROR_CONFIGURATION],
 * the same class of failure as an empty expression. Recovery to a successful poll publishes Good.
 *
 * Driven-group slow rates and leasing are device-scan behaviour. A query tag uses the group's
 * [org.oip.tags.TagGroupDef.rateMillis], as a tag-group expression tag does.
 *
 * [tick] runs every due poll and returns when those polls finish. [start] only hands due polls to
 * the lanes; it does not wait for a slow connection.
 */
class QueryTagRuntime(
    private val tags: TagEngine,
    private val databases: DatabaseService,
    private val clock: Clock = tags.clock,
) : AutoCloseable {
    private class Item(val provider: String, val path: String, val config: TagConfig) {
        @Volatile var lastRun: Long = Long.MIN_VALUE
    }

    private val items = ConcurrentHashMap<String, Item>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val registration = AtomicReference<AutoCloseable?>()
    private val scheduler = AtomicReference<java.util.concurrent.ScheduledExecutorService?>()
    private val lanes = Lanes()
    private val gate = Any()
    @Volatile private var closed = false

    /** Register for tag configuration and pick up query tags that already exist. Idempotent. */
    fun attach() {
        if (closed || registration.get() != null) return
        val sub = tags.addConfigListener { provider, path, config -> onTag(provider, path, config) }
        if (!registration.compareAndSet(null, sub)) {
            sub.close()
            return
        }
        for ((provider, config) in tags.copyTagConfigs()) {
            if (config.type == TagType.QUERY) {
                items.putIfAbsent(key(provider, config.path), Item(provider, config.path, config))
            }
        }
    }

    /**
     * Poll from one daemon scheduler. The scheduler does not run the SQL; each datasource lane does.
     * Does nothing until [attach], and does nothing after [close].
     */
    fun start() {
        if (closed) return
        attach()
        if (scheduler.get() != null) return
        val created = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, THREAD).apply { isDaemon = true }
        }
        if (!scheduler.compareAndSet(null, created)) {
            created.shutdownNow()
            return
        }
        created.scheduleWithFixedDelay(
            { runCatching { dispatch(clock.instant(), null) } },
            TICK_MILLIS,
            TICK_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Run every query tag whose group rate has elapsed as of [now] and return when those polls finish.
     * The first call for a tag runs it. A missing tag group is left to the engine (`Error_Configuration`);
     * this does not poll it. Tags on different datasources run together; tags on one datasource run one
     * at a time. A tag that is already polling is left for a later call.
     */
    fun tick(now: Instant = clock.instant()) {
        val latches = ArrayList<CountDownLatch>()
        dispatch(now, latches)
        for (latch in latches) latch.await()
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
        }
        val exec = scheduler.getAndSet(null)
        exec?.shutdownNow()
        lanes.close()
        if (exec != null) runCatching { exec.awaitTermination(2, TimeUnit.SECONDS) }
        registration.getAndSet(null)?.close()
        items.clear()
        running.clear()
    }

    /**
     * Hand each due tag to its datasource lane. [join] receives a latch for every poll this call
     * actually started; null means the caller does not wait (the scheduler).
     */
    private fun dispatch(now: Instant, join: MutableList<CountDownLatch>?) {
        val millis = now.toEpochMilli()
        synchronized(gate) {
            if (closed) return
            for (item in items.values) {
                if (!rateElapsed(item, millis)) continue
                val id = key(item.provider, item.path)
                if (!running.add(id)) continue
                item.lastRun = millis
                val latch = if (join == null) null else CountDownLatch(1)
                val task = Runnable {
                    try {
                        execute(item)
                    } catch (e: RuntimeException) {
                        publish(item, Quality(QualityCode.BAD_UNEXPECTED_ERROR, clip(e.message)))
                    } finally {
                        running.remove(id)
                        latch?.countDown()
                    }
                }
                if (!lanes.submit(item.config.datasource ?: "", task)) {
                    running.remove(id)
                    latch?.countDown()
                } else if (latch != null && join != null) {
                    join.add(latch)
                }
            }
        }
    }

    private fun onTag(provider: String, path: String, config: TagConfig?) {
        val id = key(provider, path)
        if (config != null && config.type == TagType.QUERY) items[id] = Item(provider, path, config)
        else items.remove(id)
    }

    /** Group rate elapsed, without reserving the slot. The caller sets [Item.lastRun] once it accepts the poll. */
    private fun rateElapsed(item: Item, millis: Long): Boolean {
        val group = item.config.tagGroup
        if (tags.groups.get(group) == null) return false
        val rate = tags.groups.resolve(group).rateMillis
        if (rate <= 0) return false
        if (item.lastRun != Long.MIN_VALUE && millis - item.lastRun < rate) return false
        return true
    }

    private fun execute(item: Item) {
        if (closed || !same(item)) return
        val text = item.config.query
        val source = item.config.datasource
        if (text.isNullOrBlank()) {
            publish(item, Quality(QualityCode.ERROR_CONFIGURATION, "query is empty"))
            return
        }
        if (source.isNullOrBlank()) {
            publish(item, Quality(QualityCode.ERROR_CONFIGURATION, "datasource is empty"))
            return
        }
        if (item.config.queryDataset && item.config.dataType != DataType.DOCUMENT) {
            publish(item, Quality(QualityCode.ERROR_CONFIGURATION, "queryDataset requires data type Document"))
            return
        }
        val prepared = try {
            QuerySql.prepare(text)
        } catch (e: QueryTagException) {
            publish(item, Quality(QualityCode.ERROR_CONFIGURATION, clip(e.message)))
            return
        }
        val values = resolve(item, prepared.references) ?: return
        if (closed || !same(item)) return
        try {
            val value = databases.withConnection(source) { jdbc ->
                runRead(jdbc, prepared, values, item.config.queryDataset)
            }
            if (closed || !same(item)) return
            tags.publishQuery(pathOf(item), value, Quality.GOOD)
        } catch (_: QueryCancelled) {
            return
        } catch (e: QueryNotFound) {
            publish(item, Quality(QualityCode.BAD_NOT_CONNECTED, clip(e.message)))
        } catch (e: QueryUnavailable) {
            publish(item, Quality(QualityCode.BAD_NOT_CONNECTED, clip(e.message)))
        } catch (e: QueryFailed) {
            publish(item, Quality(QualityCode.BAD_DEVICE_FAILURE, clip(e.message)))
        } catch (e: QueryRejected) {
            publish(item, Quality(QualityCode.ERROR_CONFIGURATION, clip(e.message)))
        }
    }

    /** Referenced tag values, read as the gateway. Null when a path does not parse (already published). */
    private fun resolve(item: Item, refs: List<String>): List<Any?>? {
        if (refs.isEmpty()) return emptyList()
        val base = try {
            TagPath.parse(item.path, item.provider).resolve(null)
        } catch (e: PathException) {
            publish(item, Quality(QualityCode.ERROR_INVALID_PATH, clip(e.message)))
            return null
        }
        val values = ArrayList<Any?>(refs.size)
        for (raw in refs) {
            val path = try {
                TagPath.parse(raw, base.provider ?: item.provider).resolve(base)
            } catch (e: PathException) {
                publish(item, Quality(QualityCode.ERROR_INVALID_PATH, clip(e.message)))
                return null
            }
            values += tags.read(path.toString()).value
        }
        return values
    }

    private fun publish(item: Item, quality: Quality) {
        if (closed || !same(item)) return
        tags.publishQuery(pathOf(item), null, quality)
    }

    private fun same(item: Item) = items[key(item.provider, item.path)] === item

    private fun runRead(jdbc: Connection, prepared: QuerySql.Prepared, values: List<Any?>, dataset: Boolean): Any? {
        val product = jdbc.metaData.databaseProductName
        val sqlite = product.contains("SQLite", ignoreCase = true)
        val mysql = product.contains("MySQL", ignoreCase = true) || product.contains("MariaDB", ignoreCase = true)
        var readOnly = false
        try {
            if (sqlite) jdbc.createStatement().use { it.execute("PRAGMA query_only = 1") }
            jdbc.autoCommit = false
            // The bundled MySQL and MariaDB client does not map isReadOnly to the server (DAT-019, DAT-022).
            if (mysql) jdbc.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            if (!sqlite) {
                jdbc.isReadOnly = true
                readOnly = true
            }
            return jdbc.prepareStatement(prepared.sql).use { statement ->
                statement.setEscapeProcessing(false)
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statement.maxRows = if (dataset) DATASET_MAX_ROWS + 1 else 1
                if (!lanes.track(statement)) throw QueryCancelled()
                try {
                    values.forEachIndexed { index, value -> bind(statement, index + 1, value) }
                    if (!statement.execute()) throw QueryFailed("query did not return a result set")
                    val rs = statement.resultSet ?: throw QueryFailed("query did not return a result set")
                    rs.use { readRows(it, dataset) }
                } finally {
                    lanes.untrack(statement)
                }
            }
        } finally {
            runCatching { jdbc.rollback() }
            runCatching { jdbc.autoCommit = true }
            if (sqlite) runCatching { jdbc.createStatement().use { it.execute("PRAGMA query_only = 0") } }
            else if (readOnly) runCatching { jdbc.isReadOnly = false }
        }
    }

    private fun readRows(rs: ResultSet, dataset: Boolean): Any? {
        val meta = rs.metaData
        val columns = meta.columnCount
        if (!dataset) {
            if (columns == 0 || !rs.next()) return null
            return cell(rs.getObject(1))
        }
        val names = (1..columns).map { meta.getColumnLabel(it) }
        val rows = ArrayList<List<Any?>>()
        var truncated = false
        while (rs.next()) {
            if (rows.size == DATASET_MAX_ROWS) {
                truncated = true
                break
            }
            rows += (1..columns).map { cell(rs.getObject(it)) }
        }
        return linkedMapOf("columns" to names, "rows" to rows, "truncated" to truncated)
    }

    private fun bind(statement: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.setObject(index, null)
            is Int -> statement.setInt(index, value)
            is Long -> statement.setLong(index, value)
            is Float -> statement.setFloat(index, value)
            is Double -> statement.setDouble(index, value)
            is Boolean -> statement.setBoolean(index, value)
            is String -> statement.setString(index, value)
            is Instant -> statement.setTimestamp(index, Timestamp.from(value))
            is ByteArray -> statement.setBytes(index, value)
            else -> statement.setObject(index, value)
        }
    }

    /** JDBC values in the shapes tags already store. A BigDecimal stays text so it is not rounded here. */
    private fun cell(value: Any?): Any? = when (value) {
        null -> null
        is Boolean, is Int, is Long, is String -> value
        is Float, is Double -> (value as Number).toDouble()
        is java.math.BigDecimal -> value.toPlainString()
        is Timestamp -> value.toInstant().toString()
        is java.sql.Date -> value.toLocalDate().toString()
        is java.sql.Time -> value.toLocalTime().toString()
        is ByteArray -> java.util.Base64.getEncoder().encodeToString(value)
        is Number -> value.toString()
        else -> value.toString()
    }

    private fun clip(message: String?) = (message ?: "query failed").take(300)

    private fun pathOf(item: Item) = "[${item.provider}]${item.path}"

    private fun key(provider: String, path: String) = provider + "\u0000" + path

    /** [close] won the race with a statement that had not started yet. Not a tag quality. */
    private class QueryCancelled : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * One serial queue per datasource name, executed on a fixed pool. A blocked poll holds one pool
     * thread; other datasource names keep the rest. [close] drops queued work and cancels the
     * statement that is inside the database.
     */
    private class Lanes : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val pool = ThreadPoolExecutor(
            LANE_POOL_SIZE,
            LANE_POOL_SIZE,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            { runnable -> Thread(runnable, LANE).apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val lanes = ConcurrentHashMap<String, Lane>()
        private val statements = ConcurrentHashMap.newKeySet<Statement>()

        fun submit(datasource: String, task: Runnable): Boolean {
            if (closed.get()) return false
            val lane = lanes.computeIfAbsent(datasource) { Lane() }
            return lane.submit(task)
        }

        /** False when [close] has already started; the statement is cancelled and must not run. */
        fun track(statement: Statement): Boolean {
            statements.add(statement)
            if (closed.get()) {
                statements.remove(statement)
                runCatching { statement.cancel() }
                return false
            }
            return true
        }

        fun untrack(statement: Statement) {
            statements.remove(statement)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            for (lane in lanes.values) lane.abandon()
            cancelStatements()
            pool.shutdown()
            if (Thread.currentThread().name == LANE) {
                pool.shutdownNow()
                return
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                cancelStatements()
                if (pool.awaitTermination(100, TimeUnit.MILLISECONDS)) return
            }
            pool.shutdownNow()
            cancelStatements()
            runCatching { pool.awaitTermination(1, TimeUnit.SECONDS) }
        }

        private fun cancelStatements() {
            for (statement in statements) runCatching { statement.cancel() }
        }

        private inner class Lane {
            private val queue = ArrayDeque<Runnable>()
            private var draining = false

            fun submit(task: Runnable): Boolean {
                val start = synchronized(queue) {
                    if (closed.get()) return false
                    queue.addLast(task)
                    if (draining) {
                        false
                    } else {
                        draining = true
                        true
                    }
                }
                if (!start) return true
                return try {
                    pool.execute { drain() }
                    true
                } catch (_: RejectedExecutionException) {
                    synchronized(queue) {
                        queue.remove(task)
                        if (queue.isEmpty()) draining = false
                    }
                    false
                }
            }

            private fun drain() {
                while (true) {
                    val task = synchronized(queue) {
                        val next = queue.pollFirst()
                        if (next == null) {
                            draining = false
                            return
                        }
                        next
                    }
                    runCatching { task.run() }
                    if (closed.get() && Thread.currentThread().isInterrupted) {
                        abandon()
                        synchronized(queue) { draining = false }
                        return
                    }
                }
            }

            fun abandon() {
                val dropped = ArrayList<Runnable>()
                synchronized(queue) {
                    dropped.addAll(queue)
                    queue.clear()
                }
                for (task in dropped) runCatching { task.run() }
            }
        }
    }

    companion object {
        const val THREAD = "tag-query-ticker"
        /** Worker threads for per-connection lanes. Not the ticker. */
        const val LANE = "tag-query-lane"
        /** Shared by every datasource lane. One blocked connection takes one slot, not the whole pool. */
        const val LANE_POOL_SIZE = 4
        const val TICK_MILLIS = 100L
        const val QUERY_TIMEOUT_SECONDS = 10
        /** Full-result cap. [truncated] on the document is true when the statement had more rows. */
        const val DATASET_MAX_ROWS = 1_000
    }
}
