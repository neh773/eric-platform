package org.oip.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.oip.config.ACTIVE_MODE_ID
import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.config.ValidationException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Named JDBC connections and the named queries that run on them.
 *
 * The password is a `secret://` reference in the config resource. The pool holds the plaintext only
 * in memory. Status, logs, and thrown messages are passed through [redact].
 *
 * SQLite is one connection, matching the public driver note. An in-memory URL is rewritten to a
 * shared cache so the pooled connection and later checkouts see the same database.
 *
 * Transaction groups borrow pooled connections through [withConnection]. Stored procedures are called
 * through [callProcedure] or a named query of type `procedure` (see [StoredProcedures]). The admin query
 * browser runs one ad hoc statement through [browse].
 */
class DatabaseService(
    private val store: FileConfigStore,
    private val dataDir: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val readSecret: (reference: String) -> String,
    private val writeSecret: (name: String, plaintext: String, description: String) -> String,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(DatabaseService::class.java)
    private val connections = ConcurrentHashMap<String, ManagedConnection>()
    private val queries = ConcurrentHashMap<String, LiveQuery>()
    /** Named queries a project stores itself (PRO-001), keyed by `<project>\u0000<name>`. A project's query hides a parent's or the gateway's of the same name. */
    private val projectQueries = ConcurrentHashMap<String, LiveQuery>()
    /** A project and the projects it inherits from, child first. The gateway sets this to its project service. */
    @Volatile var projectChain: (String) -> List<String> = { listOf(it) }
    private val cache = QueryCache(clock)

    /** Uploaded JDBC drivers (DAT-016), stored under the data directory. */
    val drivers = JdbcDriverStore(dataDir.resolve("jdbc-drivers"), clock)
    private val translators = ConcurrentHashMap<String, SqlTranslator>()
    private val driverSettings = ConcurrentHashMap<String, DriverSettings>()
    private var subscription: AutoCloseable? = null
    private var scheduler: java.util.concurrent.ScheduledExecutorService? = null
    var slowQuery: (name: String, millis: Long) -> Unit = { _, _ -> }
    /**
     * What uses a connection, by connection name (DAT-015): `kind` and `name` of each user, such as the alarm journal,
     * a history provider or a report. The gateway sets it; [connections] and [connection] report it as `usedBy`.
     */
    var usage: (connection: String) -> List<Map<String, Any?>> = { emptyList() }

    fun attach() {
        registerDatabaseSchemas(store.registry)
        store.revalidate(DB_MODULE)
        subscription = store.subscribe { event -> onEvent(event) }
        // Translators and driver settings first: a connection reads them when it is applied.
        val order = listOf(TRANSLATOR_KIND, DRIVER_SETTINGS_KIND, CONNECTION_KIND, QUERY_KIND)
        for (stored in store.list(DB_MODULE, Scope.GATEWAY).sortedBy { order.indexOf(it.id.kind) }) apply(stored.id, stored.resource)
        for (stored in store.list(DB_MODULE, kind = QUERY_KIND)) {
            if (stored.id.scope.type == "project") applyProjectQuery(stored.id.scope.qualifier!!, stored.id.name, stored.resource)
        }
    }

    /** Re-test enabled connections. Faulted ones are the case the status page calls out. */
    fun start() {
        if (scheduler != null) return
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "db-health").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(
                { runCatching { retest() } },
                RETEST_INTERVAL_MILLIS,
                RETEST_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    override fun close() {
        for (id in transactions.keys.toList()) runCatching { finishScriptTransaction(id, commit = false) }
        subscription?.close()
        subscription = null
        scheduler?.shutdownNow()
        scheduler = null
        for (name in connections.keys.toList()) removeConnection(name)
        queries.clear()
        cache.clear()
        drivers.close()
    }

    fun connections(): List<Map<String, Any?>> =
        connections.values.map { view(it) }.sortedBy { it["name"] as String }

    fun connection(name: String): Map<String, Any?> = view(managed(name))

    fun refreshConnection(name: String): Map<String, Any?> {
        val conn = managed(name)
        probe(conn)
        return view(conn)
    }

    fun saveConnection(name: String, fields: Map<String, Any?>, version: Long, password: PasswordUpdate): Resource {
        requireName(name)
        val unknown = fields.keys - CONNECTION_FIELDS
        if (unknown.isNotEmpty()) throw QueryRejected("unknown fields: ${unknown.sorted().joinToString(",")}")
        val current = connections[name]
        val passwordRef = resolvePassword(name, current?.spec?.passwordRef, password)
        val spec = connectionSpec(name, fields, passwordRef)
        if (spec.defaultConnection) {
            val other = connections.values.firstOrNull { it.name != name && it.spec?.defaultConnection == true }
            if (other != null) throw QueryRejected("connection '${other.name}' is already the default")
        }
        if (spec.failoverConnection == name) throw QueryRejected("a connection cannot fail over to itself")
        if (spec.driverId.isNotEmpty() && drivers.get(spec.driverId) == null) {
            throw QueryRejected("driver '${spec.driverId}' is not uploaded")
        }
        return put(CONNECTION_KIND, name, version, connectionDocument(spec))
    }

    fun deleteConnection(name: String, version: Long?) {
        store.delete(id(CONNECTION_KIND, name), version)
    }

    fun queries(): List<Map<String, Any?>> =
        queries.map { (name, live) -> queryView(name, live) }.sortedBy { it["name"] as String }

    fun query(name: String): Map<String, Any?> = queryView(name, liveQuery(name))

    fun saveQuery(name: String, fields: Map<String, Any?>, version: Long): Resource {
        requireName(name)
        val unknown = fields.keys - QUERY_FIELDS
        if (unknown.isNotEmpty()) throw QueryRejected("unknown fields: ${unknown.sorted().joinToString(",")}")
        val spec = querySpec(fields)
        if (spec.procedure == null) {
            // The connection (and so the dialect) can change before the query runs, where it is checked
            // again; here the text only has to be valid for one of them.
            val errors = listOf(SqlDialect.POSTGRES, SqlDialect.SQLITE, SqlDialect.MYSQL).map { dialect ->
                runCatching { prepareNamed(spec, emptyMap(), dialect) }
            }
            if (errors.all { it.isFailure }) throw errors.first().exceptionOrNull()!!
        }
        return put(QUERY_KIND, name, version, queryDocument(spec))
    }

    fun deleteQuery(name: String, version: Long?) {
        store.delete(id(QUERY_KIND, name), version)
    }

    fun clearCache(name: String?) {
        if (name == null) cache.clear() else {
            if (!queries.containsKey(name)) throw QueryNotFound("named query '$name' not found")
            cache.invalidate(name)
        }
    }

    /**
     * Run a named query. [roles] are the caller's roles. An empty role list on the query allows any caller;
     * the HTTP layer has already required authentication. A security rejection does not return a scalar fallback.
     */
    fun execute(
        name: String,
        parameters: Map<String, Any?>,
        roles: Set<String>,
        connection: String? = null,
        tx: String? = null,
        /** Run as this project's script or session (PRO-001): its own query of this name, else a parent's, else the gateway's. */
        project: String? = null,
    ): QueryResult {
        val (live, layer) = resolveQuery(name, project)
        // A project's own query keeps its cache entries apart from the gateway's query of the same name.
        val cacheName = if (layer == null) name else "$layer/$name"
        val spec = live.spec
        if (!spec.enabled) throw QueryForbidden("named query '$name' is disabled")
        if (spec.roles.isNotEmpty() && spec.roles.none { it in roles }) {
            throw QueryForbidden("named query '$name' is not permitted for this role")
        }
        spec.procedure?.let { call ->
            val values = StoredProcedures.coerce(call, parameters)
            val outcome = callProcedure(resolveConnection(spec, connection), call, values, coerced = true)
            return QueryResult.Procedure(outcome.returnValue, outcome.outputs, outcome.columns, outcome.rows, outcome.truncated)
        }
        val coerced = coerceParameters(spec, parameters)
        val cacheKey = QueryCache.key(cacheName, live.version, coerced)
        if (tx == null && spec.cacheEnabled && spec.queryType != "update") {
            cache.get(cacheKey)?.let { return it }
        }
        return try {
            val result = runQuery(name, spec, coerced, connection, tx)
            if (tx == null && spec.cacheEnabled && spec.queryType != "update") cache.put(cacheKey, spec.cacheTtlMillis, result)
            result
        } catch (e: QueryUnavailable) {
            scalarFallback(spec) ?: throw e
        } catch (e: QueryFailed) {
            scalarFallback(spec) ?: throw e
        }
    }

    /**
     * Borrow a pooled JDBC connection for [block] and return it to the pool when [block] finishes.
     *
     * [name] is a connection name or [CONNECTION_DEFAULT]. The connection is chosen the same way a named
     * query chooses one: disabled or faulted connections are refused, and the failover connection is used
     * when the primary is down. Throws [QueryNotFound] for an unknown name, [QueryRejected] when there is no
     * default connection, [QueryUnavailable] when no connection can be borrowed, and [QueryFailed] when
     * [block] throws a [SQLException]. Every message has the connection password removed, and the original
     * exception is not attached as a cause.
     *
     * A failing statement only marks the connection faulted when the connection itself is no longer valid,
     * so a bad column name in one caller does not take the connection away from everyone else.
     */
    fun <T> withConnection(name: String, block: (Connection) -> T): T {
        val resolved = if (name == CONNECTION_DEFAULT) defaultConnectionName() else name
        val (jdbc, used) = borrow(resolved)
        used.inflight.incrementAndGet()
        try {
            return block(jdbc)
        } catch (e: SQLException) {
            if (!runCatching { jdbc.isValid(1) }.getOrDefault(false)) markFault(used, e)
            throw QueryFailed(faultText(used, e))
        } finally {
            used.inflight.decrementAndGet()
            runCatching { jdbc.close() }
        }
    }

    /**
     * Call a stored procedure or function on connection [name] (or [CONNECTION_DEFAULT]). [values] are keyed by
     * parameter name; they are coerced to the declared types unless [coerced] says that was already done.
     * Errors are the same as for [withConnection], plus [QueryRejected] for a bad value or an SQLite connection.
     */
    fun callProcedure(name: String, call: ProcedureCall, values: Map<String, Any?>, coerced: Boolean = false): ProcedureOutcome {
        val bound = if (coerced) values else StoredProcedures.coerce(call, values)
        val started = System.nanoTime()
        val resolved = if (name == CONNECTION_DEFAULT) defaultConnectionName() else name
        val timeout = ((connections[resolved]?.spec?.validationTimeoutMillis ?: 10_000L) / 1000L).toInt().coerceAtLeast(1)
        val outcome = withConnection(resolved) { jdbc -> StoredProcedures.execute(jdbc, call, bound, timeout) }
        val millis = (System.nanoTime() - started) / 1_000_000L
        connections[resolved]?.let { noteComplete(it, millis) }
        return outcome
    }

    /**
     * Run one ad hoc statement from the admin query browser on connection [name].
     *
     * A statement that does not start with a read keyword (SELECT, WITH, VALUES, TABLE, SHOW, EXPLAIN) is a
     * write, and is refused with [QueryForbidden] unless the connection actually used (the failover one, when
     * the primary is down) has `allowBrowserWrites`. Only one statement is accepted, split by the rules of
     * that connection's database; on PostgreSQL the driver's own parser must also see exactly one statement.
     * Statements that begin, end, or change a transaction are refused ([QueryRejected]).
     *
     * Reads run in a read-only transaction that is rolled back (PostgreSQL) or with `query_only` set
     * (SQLite), so a data-modifying CTE or `EXPLAIN ANALYZE` cannot change table data. This is not a
     * sandbox on PostgreSQL: a read-only transaction still allows functions with effects outside the
     * transaction (ending or cancelling other sessions, session advisory locks, remote-database calls from
     * an extension) when the login role may call them. Session advisory locks are released before the
     * connection goes back to the pool; for a browse-only connection use a login role that can only read.
     */
    fun browse(name: String, sql: String, maxRows: Int): BrowseResult {
        if (maxRows !in 1..MAX_BROWSE_ROWS) throw QueryRejected("maxRows must be 1 to $MAX_BROWSE_ROWS")
        val (jdbc, used) = borrow(name)
        val driverName = used.spec?.driver
        val sqlite = driverName == "sqlite"
        val postgres = driverName == "postgres" || driverName == null
        val mysql = driverName == "mysql" || driverName == "mariadb"
        var broken = false
        used.inflight.incrementAndGet()
        try {
            val dialect = dialectOf(used, jdbc, sql)
            val statement = SqlText.single(sql, dialect)
            if (SqlText.isTransactionControl(statement, dialect)) {
                throw QueryRejected("transaction statements are not accepted; each statement runs on its own")
            }
            if (postgres) PgStatements.requireSingle(statement, dialect.standardConformingStrings)
            val read = SqlText.isRead(statement, dialect)
            if (!read && used.spec?.allowBrowserWrites != true) {
                val via = if (used.name == name) "" else " (failover '${used.name}')"
                throw QueryForbidden("connection '$name'$via does not allow write statements from the query browser")
            }
            val started = System.nanoTime()
            if (read) {
                if (sqlite) jdbc.createStatement().use { it.execute("PRAGMA query_only = 1") }
                jdbc.autoCommit = false
                if (postgres) jdbc.isReadOnly = true
                // DAT-019/DAT-022: the next transaction is read-only on MySQL and MariaDB.
                if (mysql) jdbc.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            }
            try {
                jdbc.createStatement().use { st ->
                    // What runs must be exactly the text checked above, so no JDBC escape rewriting.
                    st.setEscapeProcessing(false)
                    st.queryTimeout = ((used.spec?.validationTimeoutMillis ?: 10_000L) / 1000L).toInt().coerceAtLeast(1)
                    st.maxRows = maxRows + 1
                    val hasRows = st.execute(statement)
                    val result = if (hasRows) {
                        st.resultSet.use { rs ->
                            val meta = rs.metaData
                            val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                            val rows = ArrayList<List<Any?>>()
                            var truncated = false
                            while (rs.next()) {
                                if (rows.size == maxRows) {
                                    truncated = true
                                    break
                                }
                                rows += (1..meta.columnCount).map { jsonValue(rs.getObject(it)) }
                            }
                            BrowseResult.Rows(columns, rows, truncated)
                        }
                    } else {
                        BrowseResult.Update(st.updateCount.coerceAtLeast(0))
                    }
                    noteComplete(used, (System.nanoTime() - started) / 1_000_000L)
                    return result
                }
            } finally {
                if (read) {
                    runCatching { jdbc.rollback() }.onFailure { broken = true }
                    runCatching { jdbc.autoCommit = true }.onFailure { broken = true }
                    if (sqlite) runCatching { jdbc.createStatement().use { it.execute("PRAGMA query_only = 0") } }.onFailure { broken = true }
                    else if (postgres) runCatching { jdbc.isReadOnly = false }.onFailure { broken = true }
                }
                // Session advisory locks outlive the rollback; do not hand them to the next borrower.
                if (postgres && !broken) {
                    runCatching { jdbc.createStatement().use { it.execute("SELECT pg_advisory_unlock_all()") } }.onFailure { broken = true }
                }
            }
        } catch (e: SQLException) {
            if (!runCatching { jdbc.isValid(1) }.getOrDefault(false)) {
                broken = true
                markFault(used, e)
            }
            throw QueryFailed(faultText(used, e))
        } finally {
            used.inflight.decrementAndGet()
            used.pool?.let { pool -> if (broken) runCatching { pool.evictConnection(jdbc) } }
            runCatching { jdbc.close() }
        }
    }

    /**
     * How [sql] is split on the borrowed connection [jdbc] of [used]. PostgreSQL's string rules depend on the
     * server's `standard_conforming_strings`, as the driver tracks it; if that cannot be read, [sql] must be
     * one statement under both settings.
     */
    private fun dialectOf(used: ManagedConnection, jdbc: Connection, sql: String): SqlDialect {
        when (used.spec?.driver) {
            "sqlite" -> return SqlDialect.SQLITE
            "sqlserver", "azuresql" -> return SqlDialect.MSSQL
            "mysql", "mariadb" -> return SqlDialect.MYSQL
            "oracle", "db2", "snowflake", "custom" -> return SqlDialect.of(used.spec?.driver)
        }
        val scs = runCatching { jdbc.unwrap(org.postgresql.core.BaseConnection::class.java).standardConformingStrings }.getOrNull()
        if (scs != null) return SqlDialect(postgres = true, standardConformingStrings = scs)
        SqlText.single(sql, SqlDialect(postgres = true, standardConformingStrings = false))
        return SqlDialect.POSTGRES
    }

    private fun defaultConnectionName(): String = connections.values
        .filter { it.spec?.defaultConnection == true && it.spec?.enabled == true }
        .minByOrNull { it.name }?.name
        ?: throw QueryRejected("no default database connection")

    private fun scalarFallback(spec: QuerySpec): QueryResult? {
        if (spec.queryType != "scalar" || !spec.fallbackSet) return null
        log.debug("named query returned its scalar fallback")
        return QueryResult.Scalar(spec.fallback, cached = false, fallback = true)
    }

    private fun runQuery(
        name: String,
        spec: QuerySpec,
        parameters: Map<String, Any?>,
        connectionOverride: String?,
        tx: String? = null,
    ): QueryResult {
        val held = tx?.let { openTransaction(it) }
        val connectionName = resolveConnection(spec, connectionOverride)
        if (held != null && held.connection != connectionName) throw QueryRejected("transaction is open on '${held.connection}'")
        val started = System.nanoTime()
        val jdbc: java.sql.Connection
        val used: ManagedConnection
        val borrowed = held == null
        if (held != null) {
            jdbc = held.jdbc
            used = held.managed
        } else {
            val pair = borrow(connectionName)
            jdbc = pair.first
            used = pair.second
            used.inflight.incrementAndGet()
        }
        try {
            val prepared = prepareNamed(spec, parameters, dialectOf(used, jdbc, spec.sql))
            jdbc.prepareStatement(prepared.sql).use { statement ->
                val seconds = ((used.spec?.validationTimeoutMillis ?: 10_000L) / 1000L).toInt().coerceAtLeast(1)
                statement.queryTimeout = seconds
                if (spec.queryType == "query") statement.maxRows = spec.maxRows + 1
                prepared.names.forEachIndexed { index, param -> bind(statement, index + 1, parameters[param]) }
                val hasRows = statement.execute()
                val result = readResult(spec, statement, hasRows)
                val millis = (System.nanoTime() - started) / 1_000_000L
                noteComplete(used, millis)
                val threshold = connections[connectionName]?.spec?.slowQueryMillis ?: 0L
                if (threshold > 0 && millis >= threshold) slowQuery(name, millis)
                return result
            }
        } catch (e: SQLException) {
            if (borrowed) markFault(used, e)
            throw QueryFailed(faultText(used, e))
        } finally {
            if (borrowed) {
                used.inflight.decrementAndGet()
                jdbc.close()
            }
        }
    }

    /** `{name}` query-string parameters are substituted; `:name` value parameters are bound. An empty [parameters] map uses a stand-in identifier so a definition can be checked before a call. */
    private fun prepareNamed(spec: QuerySpec, parameters: Map<String, Any?>, dialect: SqlDialect): PreparedSql {
        val queryStrings = linkedMapOf<String, String>()
        val valueNames = HashSet<String>()
        for (param in spec.parameters) {
            val key = param.name.lowercase()
            if (param.queryString) {
                val raw = parameters[key]
                val text = when {
                    parameters.isEmpty() -> "x"
                    raw is String -> raw
                    else -> throw QueryRejected("query-string parameter ${param.name} must be text")
                }
                queryStrings[key] = text
            } else {
                valueNames += key
            }
        }
        val sql = SqlText.substitute(spec.sql, queryStrings, valueNames, dialect)
        return SqlText.prepare(sql, valueNames, dialect)
    }

    private class OpenTx(val connection: String, val jdbc: java.sql.Connection, val managed: ManagedConnection)

    private val transactions = java.util.concurrent.ConcurrentHashMap<String, OpenTx>()

    /** Hold one pooled connection with auto-commit off. The id is passed back to [scriptQuery] and [execute]. */
    fun beginScriptTransaction(connection: String): String {
        if (transactions.size >= 32) throw QueryRejected("too many open transactions")
        val resolved = if (connection.isBlank() || connection == CONNECTION_DEFAULT) defaultConnectionName() else connection
        val (jdbc, managed) = borrow(resolved)
        managed.inflight.incrementAndGet()
        try {
            jdbc.autoCommit = false
        } catch (e: java.sql.SQLException) {
            managed.inflight.decrementAndGet()
            runCatching { jdbc.close() }
            throw QueryFailed(faultText(managed, e))
        }
        val id = java.util.UUID.randomUUID().toString()
        transactions[id] = OpenTx(resolved, jdbc, managed)
        return id
    }

    fun finishScriptTransaction(id: String, commit: Boolean) {
        val tx = transactions.remove(id) ?: throw QueryRejected("transactions: unknown id")
        try {
            if (commit) tx.jdbc.commit() else tx.jdbc.rollback()
            runCatching { tx.jdbc.autoCommit = true }
        } catch (e: java.sql.SQLException) {
            throw QueryFailed(faultText(tx.managed, e))
        } finally {
            tx.managed.inflight.decrementAndGet()
            runCatching { tx.jdbc.close() }
        }
    }

    private fun openTransaction(id: String): OpenTx =
        transactions[id] ?: throw QueryRejected("transactions: unknown id")

    /**
     * One ad hoc statement from a gateway script. [prepared] binds [parameters] as `:name` placeholders.
     * A standard statement ([prepared] false) is sent as written and takes no parameters.
     * [mode] is `rows`, `update`, or `scalar`. Transaction control SQL is refused; use [beginScriptTransaction].
     */
    fun scriptQuery(
        connection: String,
        sql: String,
        parameters: Map<String, Any?>,
        prepared: Boolean,
        mode: String,
        tx: String?,
        allowWrite: Boolean = true,
    ): QueryResult {
        if (mode !in setOf("rows", "update", "scalar")) throw QueryRejected("query mode must be rows, update, or scalar")
        val lowered = linkedMapOf<String, Any?>()
        for ((key, value) in parameters) {
            val name = key.lowercase()
            if (lowered.containsKey(name)) throw QueryRejected("duplicate parameter $key")
            lowered[name] = value
        }
        if (!prepared && lowered.isNotEmpty()) throw QueryRejected("a standard query has no parameters")
        val held = tx?.takeIf { it.isNotBlank() }?.let { openTransaction(it) }
        val resolved = held?.connection ?: if (connection.isBlank() || connection == CONNECTION_DEFAULT) defaultConnectionName() else connection
        if (held != null && connection.isNotBlank() && connection != CONNECTION_DEFAULT && connection != held.connection) {
            throw QueryRejected("transaction is open on '${held.connection}'")
        }
        val jdbc: java.sql.Connection
        val used: ManagedConnection
        val borrowed = held == null
        if (held != null) {
            jdbc = held.jdbc
            used = held.managed
        } else {
            val pair = borrow(resolved)
            jdbc = pair.first
            used = pair.second
            used.inflight.incrementAndGet()
        }
        try {
            val dialect = dialectOf(used, jdbc, sql)
            val seconds = ((used.spec?.validationTimeoutMillis ?: 10_000L) / 1000L).toInt().coerceAtLeast(1)
            if (prepared) {
                val preparedSql = SqlText.prepare(sql, lowered.keys, dialect)
                if (SqlText.isTransactionControl(preparedSql.sql, dialect)) {
                    throw QueryRejected("transaction statements are not accepted; use beginTransaction")
                }
                if (!allowWrite && !SqlText.isRead(preparedSql.sql, dialect)) {
                    throw QueryForbidden("a report SQL source can only read")
                }
                jdbc.prepareStatement(preparedSql.sql).use { statement ->
                    statement.queryTimeout = seconds
                    if (mode == "rows") statement.maxRows = 10_001
                    preparedSql.names.forEachIndexed { index, param -> bind(statement, index + 1, lowered[param]) }
                    return scriptResult(mode, statement, statement.execute())
                }
            }
            val single = SqlText.single(sql, dialect)
            if (SqlText.isTransactionControl(single, dialect)) {
                throw QueryRejected("transaction statements are not accepted; use beginTransaction")
            }
            if (!allowWrite && !SqlText.isRead(single, dialect)) {
                throw QueryForbidden("a report SQL source can only read")
            }
            jdbc.createStatement().use { statement ->
                statement.setEscapeProcessing(false)
                statement.queryTimeout = seconds
                if (mode == "rows") statement.maxRows = 10_001
                return scriptResult(mode, statement, statement.execute(single))
            }
        } catch (e: java.sql.SQLException) {
            if (borrowed && !runCatching { jdbc.isValid(1) }.getOrDefault(false)) markFault(used, e)
            throw QueryFailed(faultText(used, e))
        } finally {
            if (borrowed) {
                used.inflight.decrementAndGet()
                runCatching { jdbc.close() }
            }
        }
    }

    private fun scriptResult(mode: String, statement: java.sql.Statement, hasRows: Boolean): QueryResult = when (mode) {
        "update" -> {
            if (hasRows) throw QueryFailed("update query returned a result set")
            QueryResult.Update(statement.updateCount.coerceAtLeast(0))
        }
        "scalar" -> {
            if (!hasRows) throw QueryFailed("scalar query did not return a value")
            statement.resultSet.use { rs ->
                if (!rs.next()) QueryResult.Scalar(null, cached = false, fallback = false)
                else QueryResult.Scalar(jsonValue(rs.getObject(1)), cached = false, fallback = false)
            }
        }
        else -> {
            if (!hasRows) throw QueryFailed("query did not return a result set")
            statement.resultSet.use { rs ->
                val meta = rs.metaData
                val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                val rows = ArrayList<List<Any?>>()
                var truncated = false
                while (rs.next()) {
                    if (rows.size == 10_000) {
                        truncated = true
                        break
                    }
                    rows += (1..meta.columnCount).map { jsonValue(rs.getObject(it)) }
                }
                QueryResult.Rows(columns, rows, cached = false, truncated = truncated)
            }
        }
    }

    private fun readResult(spec: QuerySpec, statement: java.sql.PreparedStatement, hasRows: Boolean): QueryResult {
        return when (spec.queryType) {
            "update" -> {
                if (hasRows) throw QueryFailed("update query returned a result set")
                QueryResult.Update(statement.updateCount.coerceAtLeast(0))
            }
            "scalar" -> {
                if (!hasRows) throw QueryFailed("scalar query did not return a value")
                statement.resultSet.use { rs ->
                    if (!rs.next()) QueryResult.Scalar(null, cached = false, fallback = false)
                    else QueryResult.Scalar(jsonValue(rs.getObject(1)), cached = false, fallback = false)
                }
            }
            else -> {
                if (!hasRows) throw QueryFailed("query did not return a result set")
                statement.resultSet.use { rs ->
                    val meta = rs.metaData
                    val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                    val rows = ArrayList<List<Any?>>()
                    var truncated = false
                    while (rs.next()) {
                        if (rows.size == spec.maxRows) {
                            truncated = true
                            break
                        }
                        rows += (1..meta.columnCount).map { jsonValue(rs.getObject(it)) }
                    }
                    QueryResult.Rows(columns, rows, cached = false, truncated = truncated)
                }
            }
        }
    }

    private fun bind(statement: java.sql.PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.setObject(index, null)
            is Int -> statement.setInt(index, value)
            is Long -> statement.setLong(index, value)
            is Float -> statement.setFloat(index, value)
            is Double -> statement.setDouble(index, value)
            is Boolean -> statement.setBoolean(index, value)
            is String -> statement.setString(index, value)
            is java.sql.Timestamp -> statement.setTimestamp(index, value)
            else -> statement.setObject(index, value)
        }
    }

    private fun coerceParameters(spec: QuerySpec, raw: Map<String, Any?>): Map<String, Any?> {
        val incoming = linkedMapOf<String, Any?>()
        for ((key, value) in raw) {
            val name = key.lowercase()
            if (incoming.containsKey(name)) throw QueryRejected("duplicate parameter $key")
            incoming[name] = value
        }
        val known = spec.parameters.associateBy { it.name.lowercase() }
        val unknown = incoming.keys - known.keys
        if (unknown.isNotEmpty()) throw QueryRejected("unknown parameter ${unknown.sorted().joinToString(",")}")
        val out = linkedMapOf<String, Any?>()
        for (param in spec.parameters) {
            val key = param.name.lowercase()
            if (!incoming.containsKey(key)) throw QueryRejected("missing parameter ${param.name}")
            out[key] = Coercion.coerce(param.dataType, incoming[key])
        }
        return out
    }

    private fun resolveConnection(spec: QuerySpec, override: String?): String = when (spec.connection) {
        CONNECTION_DEFAULT -> defaultConnectionName()
        CONNECTION_PARAMETER -> {
            val name = override?.trim().orEmpty()
            if (!NAME.matches(name)) throw QueryRejected("connection parameter is required")
            name
        }
        else -> spec.connection
    }

    private val lent = ConcurrentHashMap<Connection, HikariDataSource>()

    /**
     * A pooled JDBC connection from database connection [name] (with failover, as for queries), for another
     * gateway service such as the SQL history provider. Return it with [releaseConnection].
     * Throws [QueryNotFound] or [QueryUnavailable] when the connection is unknown, disabled, or faulted.
     */
    fun borrowConnection(name: String): Connection {
        val (connection, managed) = borrow(name)
        managed.pool?.let { lent[connection] = it }
        return connection
    }

    /** Return a connection from [borrowConnection]. A [broken] one is evicted from its pool instead of reused. */
    fun releaseConnection(connection: Connection, broken: Boolean) {
        val pool = lent.remove(connection)
        if (broken && pool != null) runCatching { pool.evictConnection(connection) }
        runCatching { connection.close() }
    }

    private fun borrow(name: String): Pair<Connection, ManagedConnection> {
        val first = choose(name)
        try {
            return first.pool!!.connection to first
        } catch (e: SQLException) {
            markFault(first, e)
            val second = choose(name)
            if (second.name == first.name) throw QueryUnavailable(faultText(first, e))
            try {
                return second.pool!!.connection to second
            } catch (again: SQLException) {
                markFault(second, again)
                throw QueryUnavailable(faultText(second, again))
            }
        }
    }

    private fun choose(requested: String): ManagedConnection {
        val primary = connections[requested] ?: throw QueryNotFound("database connection '$requested' not found")
        val spec = primary.spec ?: throw QueryUnavailable("database connection '$requested' is not ready")
        if (!spec.enabled) throw QueryUnavailable("database connection '$requested' is disabled")
        val failover = failoverOf(primary)
        if (primary.sticky && spec.failoverMode == "sticky" && failover != null && usable(failover)) return failover
        if (primary.sticky && !usable(failover)) primary.sticky = false
        if (usable(primary)) {
            if (spec.failoverMode == "standard") primary.sticky = false
            return primary
        }
        if (failover != null && usable(failover)) {
            if (spec.failoverMode == "sticky") primary.sticky = true
            return failover
        }
        throw QueryUnavailable(primary.error ?: "database connection '$requested' is ${primary.status}")
    }

    private fun usable(conn: ManagedConnection?): Boolean =
        conn != null && conn.spec?.enabled == true && conn.status == "Valid" && conn.pool != null

    private fun failoverOf(conn: ManagedConnection): ManagedConnection? {
        val name = conn.spec?.failoverConnection.orEmpty()
        if (name.isEmpty() || name == conn.name) return null
        return connections[name]
    }

    private fun retest() {
        for (name in connections.keys.toList()) runCatching { probe(connections[name] ?: return@runCatching) }
    }

    private fun probe(conn: ManagedConnection) {
        val spec = conn.spec ?: return
        if (!spec.enabled) {
            closePool(conn)
            conn.status = "Disabled"
            conn.error = null
            return
        }
        val pool = conn.pool
        if (pool == null) {
            conn.status = "Faulted"
            conn.error = conn.error ?: "connection is not open"
            return
        }
        try {
            pool.connection.use { jdbc ->
                jdbc.createStatement().use { statement ->
                    statement.queryTimeout = (spec.validationTimeoutMillis / 1000L).toInt().coerceAtLeast(1)
                    statement.executeQuery(spec.validationQuery).use { rs ->
                        if (!rs.next()) throw SQLException("validation query returned no row")
                    }
                }
            }
            conn.status = "Valid"
            conn.error = null
        } catch (e: SQLException) {
            markFault(conn, e)
        }
    }

    private fun markFault(conn: ManagedConnection, error: SQLException) {
        conn.status = "Faulted"
        conn.error = faultText(conn, error)
        log.warn("database connection {} faulted ({})", conn.name, error.sqlState ?: "no-sqlstate")
    }

    private fun faultText(conn: ManagedConnection, error: SQLException): String {
        val secrets = listOfNotNull(conn.password)
        val message = error.message ?: "database error"
        return redact(message, secrets)
    }

    private fun noteComplete(conn: ManagedConnection, millis: Long) {
        val now = System.nanoTime()
        synchronized(conn.recent) {
            conn.recent.addLast(now)
            val cutoff = now - 1_000_000_000L
            while (conn.recent.isNotEmpty() && conn.recent.first() < cutoff) conn.recent.removeFirst()
        }
        if (millis < 0) return
    }

    private fun onEvent(event: ConfigEvent) {
        val id = event.id ?: return
        // GAT-037: switching the deployment mode, or editing a connection's mode overlay, re-applies the connections
        // whose effective spec changed (a different URL per environment).
        if (id == ACTIVE_MODE_ID) {
            for (stored in store.list(DB_MODULE, Scope.GATEWAY, CONNECTION_KIND)) reapplyIfChanged(stored.id.name, stored.resource)
            return
        }
        if (id.scope.isOverlay && id.module == DB_MODULE && id.kind == CONNECTION_KIND) {
            store.get(id(CONNECTION_KIND, id.name))?.let { reapplyIfChanged(id.name, it) }
            return
        }
        if (id.module != DB_MODULE) return
        if (id.scope.type == "project") {
            if (id.kind == QUERY_KIND) onProjectQuery(id.scope.qualifier!!, id.name, event)
            return
        }
        if (id.scope != Scope.GATEWAY) return
        try {
            when (id.kind) {
                CONNECTION_KIND -> when (event.type) {
                    ChangeType.DELETED -> removeConnection(id.name)
                    ChangeType.CREATED, ChangeType.UPDATED -> event.resource?.let { effectiveConnection(id.name, it)?.let { merged -> applyConnection(id.name, merged) } }
                    ChangeType.INVALID -> faultConnection(id.name, "connection resource is invalid")
                }
                QUERY_KIND -> when (event.type) {
                    ChangeType.DELETED -> removeQuery(id.name)
                    ChangeType.CREATED, ChangeType.UPDATED -> event.resource?.let { applyQuery(id.name, it) }
                    ChangeType.INVALID -> removeQuery(id.name)
                }
                TRANSLATOR_KIND, DRIVER_SETTINGS_KIND -> when (event.type) {
                    ChangeType.DELETED, ChangeType.INVALID -> removeSupport(id.kind, id.name)
                    ChangeType.CREATED, ChangeType.UPDATED -> event.resource?.let { applySupport(id.kind, id.name, it) }
                }
            }
        } catch (e: Exception) {
            log.warn("database resource {} was not applied ({})", id, e.javaClass.simpleName)
            if (id.kind == CONNECTION_KIND) faultConnection(id.name, "connection could not be opened")
        }
    }

    private fun apply(id: ResourceId, resource: Resource) {
        if (id.scope != Scope.GATEWAY) return
        when (id.kind) {
            CONNECTION_KIND -> effectiveConnection(id.name, resource)?.let { applyConnection(id.name, it) }
            QUERY_KIND -> applyQuery(id.name, resource)
            TRANSLATOR_KIND, DRIVER_SETTINGS_KIND -> applySupport(id.kind, id.name, resource)
        }
    }

    /** The stored connection with the active deployment mode's overlay merged in; faults it when the merge is invalid. */
    private fun effectiveConnection(name: String, stored: Resource): Resource? = try {
        store.resolve(id(CONNECTION_KIND, name)) ?: stored
    } catch (e: ValidationException) {
        faultConnection(name, "deployment mode override is invalid")
        null
    }

    private fun reapplyIfChanged(name: String, stored: Resource) {
        val effective = effectiveConnection(name, stored) ?: return
        if (connections[name]?.resource?.spec != effective.spec) applyConnection(name, effective)
    }

    private fun applySupport(kind: String, name: String, resource: Resource) {
        try {
            if (kind == TRANSLATOR_KIND) translators[name] = SqlTranslator.fromFields(name, resource.spec)
            else driverSettings[name] = DriverSettings.fromFields(resource.spec).also { checkSettings(name, it) }
        } catch (e: QueryRejected) {
            removeSupport(kind, name)
            log.warn("{} {} was rejected ({})", kind, name, e.message)
        }
    }

    private fun removeSupport(kind: String, name: String) {
        if (kind == TRANSLATOR_KIND) translators.remove(name) else driverSettings.remove(name)
    }

    private fun applyConnection(name: String, resource: Resource) {
        val conn = connections.computeIfAbsent(name) { ManagedConnection(name) }
        val spec = try {
            connectionSpec(name, resource.spec, resource.spec["password"] as? String)
        } catch (e: QueryRejected) {
            faultConnection(name, e.message ?: "connection resource is invalid")
            return
        }
        conn.spec = spec
        conn.resource = resource
        conn.version = resource.metadata.version
        if (!spec.enabled) {
            closePool(conn)
            conn.password = null
            conn.status = "Disabled"
            conn.error = null
            return
        }
        val password = readPassword(spec)
        if (spec.passwordRef != null && password == null) {
            closePool(conn)
            conn.password = null
            conn.status = "Faulted"
            conn.error = "password secret could not be read"
            return
        }
        val identity = poolIdentity(spec, password)
        if (identity == conn.identity && conn.pool != null) return
        val opened = try {
            openPool(name, spec, password)
        } catch (e: Exception) {
            closePool(conn)
            conn.password = null
            conn.status = "Faulted"
            conn.error = redact(e.message ?: "connection could not be opened", listOfNotNull(password))
            return
        }
        val previous = conn.pool
        conn.pool = opened
        conn.password = password
        conn.identity = identity
        if (spec.failoverMode != "sticky") conn.sticky = false
        previous?.let { runCatching { it.close() } }
        probe(conn)
    }

    private fun readPassword(spec: ConnectionSpec): String? {
        val ref = spec.passwordRef ?: return null
        return try {
            readSecret(ref)
        } catch (_: Exception) {
            null
        }
    }

    private fun applyQuery(name: String, resource: Resource) {
        val spec = try {
            querySpec(withDefaults(QUERY_KIND, resource.spec))
        } catch (e: QueryRejected) {
            removeQuery(name)
            log.warn("named query {} was rejected ({})", name, e.javaClass.simpleName)
            return
        }
        queries[name] = LiveQuery(resource.metadata.version, spec)
        cache.invalidate(name)
    }

    private fun onProjectQuery(project: String, name: String, event: ConfigEvent) {
        try {
            when (event.type) {
                ChangeType.CREATED, ChangeType.UPDATED -> event.resource?.let { applyProjectQuery(project, name, it) }
                ChangeType.DELETED, ChangeType.INVALID -> removeProjectQuery(project, name)
            }
        } catch (e: Exception) {
            log.warn("project named query {} was not applied ({})", name, e.javaClass.simpleName)
        }
    }

    private fun applyProjectQuery(project: String, name: String, resource: Resource) {
        val spec = try {
            querySpec(withDefaults(QUERY_KIND, resource.spec))
        } catch (e: QueryRejected) {
            removeProjectQuery(project, name)
            log.warn("project named query {} was rejected ({})", name, e.javaClass.simpleName)
            return
        }
        projectQueries["$project\u0000$name"] = LiveQuery(resource.metadata.version, spec)
        cache.invalidate("$project/$name")
    }

    private fun removeProjectQuery(project: String, name: String) {
        projectQueries.remove("$project\u0000$name")
        cache.invalidate("$project/$name")
    }

    /** The query [name] means for a session or script of [project]: the nearest project in its chain that stores one, else the gateway's. */
    private fun resolveQuery(name: String, project: String?): Pair<LiveQuery, String?> {
        if (project != null) {
            val chain = runCatching { projectChain(project) }.getOrDefault(listOf(project))
            for (layer in chain) projectQueries["$layer\u0000$name"]?.let { return it to layer }
        }
        return liveQuery(name) to null
    }

    private fun removeConnection(name: String) {
        connections.remove(name)?.let { closePool(it); it.password = null }
    }

    private fun removeQuery(name: String) {
        queries.remove(name)
        cache.invalidate(name)
    }

    private fun faultConnection(name: String, message: String) {
        val conn = connections.computeIfAbsent(name) { ManagedConnection(name) }
        closePool(conn)
        conn.password = null
        conn.status = "Faulted"
        conn.error = message
    }

    private fun closePool(conn: ManagedConnection) {
        val pool = conn.pool
        conn.pool = null
        conn.identity = null
        pool?.let { runCatching { it.close() } }
    }

    private fun openPool(name: String, spec: ConnectionSpec, password: String?): HikariDataSource {
        val config = HikariConfig()
        config.poolName = "oip-db-$name"
        val external = spec.driver in DriverSettings.EXTERNAL_KINDS
        if (external) {
            config.dataSource = drivers.dataSource(spec.driverId, spec.resolvedUrl, spec.username.ifEmpty { null }, password, spec.extra)
        } else if (spec.driver == "mysql" || spec.driver == "mariadb") {
            config.dataSource = MySqlDataSource(spec.resolvedUrl, spec.username.ifEmpty { null }, password)
        } else {
            config.driverClassName = when (spec.driver) {
                "sqlite" -> "org.sqlite.JDBC"
                "sqlserver", "azuresql" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver"
                else -> "org.postgresql.Driver"
            }
            config.jdbcUrl = spec.resolvedUrl
        }
        if (spec.username.isNotEmpty()) config.username = spec.username
        if (!password.isNullOrEmpty()) config.password = password
        val max = if (spec.driver == "sqlite") 1 else spec.maxActive
        config.maximumPoolSize = max
        val warm = maxOf(spec.minIdle, spec.initialSize).coerceAtMost(max)
        config.minimumIdle = if (spec.driver == "sqlite" && spec.resolvedUrl.contains("mode=memory")) 1 else warm
        val validation = spec.validationTimeoutMillis.coerceIn(250, 60_000)
        var wait = spec.maxWaitMillis.coerceIn(250, 120_000)
        if (wait <= validation) wait = (validation + 250).coerceAtMost(120_000)
        config.connectionTimeout = wait
        config.validationTimeout = validation.coerceAtMost(wait - 1)
        config.connectionTestQuery = spec.validationQuery
        config.initializationFailTimeout = -1
        config.isAutoCommit = true
        // An uploaded driver gets the extra properties as connect properties, inside its own data source.
        if (!external) for ((key, value) in spec.extra) config.addDataSourceProperty(key, value)
        // SEC-022: a PostgreSQL connection that asks for verified TLS uses the gateway's outbound trust store.
        if (spec.driver == "postgres") {
            OutboundSslSocketFactory.forPostgres(spec.resolvedUrl, spec.extra)?.let { config.addDataSourceProperty("sslfactory", it) }
        }
        return HikariDataSource(config)
    }

    private fun poolIdentity(spec: ConnectionSpec, password: String?): String = listOf(
        spec.driver,
        spec.driverId,
        if (spec.driverId.isEmpty()) "" else drivers.get(spec.driverId)?.sha256 ?: "missing",
        spec.resolvedUrl,
        spec.username,
        password ?: "",
        spec.maxActive.toString(),
        spec.minIdle.toString(),
        spec.initialSize.toString(),
        spec.maxWaitMillis.toString(),
        spec.validationQuery,
        spec.validationTimeoutMillis.toString(),
        spec.extra.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" },
    ).joinToString("|")

    private fun resolvePassword(name: String, current: String?, update: PasswordUpdate): String? = when (update) {
        PasswordUpdate.Unchanged -> current
        PasswordUpdate.Clear -> null
        is PasswordUpdate.Reference -> {
            if (!SECRET.matches(update.reference)) throw QueryRejected("password must be a secret:// reference")
            update.reference
        }
        is PasswordUpdate.Set -> {
            if (update.plaintext.length > 1024) throw QueryRejected("password is too long")
            try {
                writeSecret("db-$name", update.plaintext, "database connection $name")
            } catch (e: Exception) {
                throw QueryRejected("could not store password")
            }
        }
    }

    private fun connectionSpec(name: String, rawFields: Map<String, Any?>, passwordRef: String?): ConnectionSpec {
        val driver = requiredString(rawFields, "driver")
        if (driver !in DriverSettings.KINDS) {
            throw QueryRejected("driver must be postgres, sqlite, sqlserver, azuresql, mariadb, mysql, oracle, db2, snowflake, or custom")
        }
        val fields = withDriverDefaults(driver, rawFields)
        val external = driver in DriverSettings.EXTERNAL_KINDS
        val driverId = optionalString(fields, "driverId", "")
        if (external) {
            if (driverId.isEmpty()) throw QueryRejected("$driver needs driverId, the id of an uploaded JDBC driver")
            if (!JdbcDriverStore.ID.matches(driverId)) throw QueryRejected("driverId must be a lowercase resource name")
        } else if (driverId.isNotEmpty()) {
            throw QueryRejected("driverId is only for oracle, db2, snowflake, and custom connections")
        }
        val expectedTranslator = DriverSettings.BUILT_IN[driver]?.translator?.takeIf { !external }
        val url = requiredString(fields, "url")
        checkUrl(driver, url)
        val username = optionalString(fields, "username", "")
        if (username.length > 128 || username.any { it == '\n' || it == '\r' }) throw QueryRejected("username is invalid")
        val translator = optionalString(fields, "translator", expectedTranslator ?: "")
        if (expectedTranslator != null && translator != expectedTranslator) throw QueryRejected("translator does not match the driver")
        if (expectedTranslator == null && translator.isEmpty()) throw QueryRejected("translator is required")
        val resolvedTranslator = translator(translator) ?: throw QueryRejected("translator '$translator' is not defined")
        val validationQuery = optionalString(fields, "validationQuery", resolvedTranslator.validationQuery).trim()
        if (!validationQuery.startsWith("SELECT", ignoreCase = true) || validationQuery.contains(';')) {
            throw QueryRejected("validation query must be one SELECT")
        }
        val allowBrowserWrites = optionalBool(fields, "allowBrowserWrites", false)
        val failover = optionalString(fields, "failoverConnection", "")
        if (failover.isNotEmpty() && !NAME.matches(failover)) throw QueryRejected("failover connection name is invalid")
        val mode = optionalString(fields, "failoverMode", "standard")
        if (mode != "standard" && mode != "sticky") throw QueryRejected("failover mode must be standard or sticky")
        return ConnectionSpec(
            driver = driver,
            url = url,
            resolvedUrl = resolveUrl(name, driver, url),
            username = username,
            passwordRef = passwordRef,
            description = optionalString(fields, "description", ""),
            enabled = optionalBool(fields, "enabled", true),
            defaultConnection = optionalBool(fields, "defaultConnection", false),
            translator = translator,
            driverId = driverId,
            extra = extraProperties(fields["extraProperties"]),
            failoverConnection = failover,
            failoverMode = mode,
            slowQueryMillis = optionalLong(fields, "slowQueryMillis", 60_000, 0, 3_600_000),
            validationTimeoutMillis = optionalLong(fields, "validationTimeoutMillis", 10_000, 250, 60_000),
            validationQuery = validationQuery,
            initialSize = optionalInt(fields, "initialSize", 0, 0, 32),
            maxActive = optionalInt(fields, "maxActive", 8, 1, 32),
            maxIdle = optionalInt(fields, "maxIdle", 8, 0, 32),
            minIdle = optionalInt(fields, "minIdle", 0, 0, 32),
            maxWaitMillis = optionalLong(fields, "maxWaitMillis", 5_000, 250, 120_000),
            allowBrowserWrites = allowBrowserWrites,
        )
    }

    private fun extraProperties(raw: Any?): Map<String, String> = ConnectionProperties.check(raw)

    /** Built-in values, then `DriverSettings` overrides for the kind, then for the uploaded JAR (DAT-027). */
    private fun effectiveSettings(kind: String, driverId: String?): DriverSettings {
        var out = DriverSettings.BUILT_IN[kind] ?: DriverSettings()
        driverSettings[kind]?.let { out = out.overlay(it) }
        if (!driverId.isNullOrEmpty() && driverId != kind) driverSettings[driverId]?.let { out = out.overlay(it) }
        return out
    }

    /**
     * Fill what a connection leaves out from the driver's settings: the url (built from host, port and database),
     * translator, validation query, uploaded driver, and extra connect properties. Fields the connection sets win.
     */
    private fun withDriverDefaults(kind: String, fields: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(fields)
        val explicitId = (fields["driverId"] as? String)?.takeIf { it.isNotEmpty() }
        val driverId = if (kind in DriverSettings.EXTERNAL_KINDS) explicitId ?: effectiveSettings(kind, null).driverId else explicitId
        val eff = effectiveSettings(kind, driverId)
        if (kind in DriverSettings.EXTERNAL_KINDS && explicitId == null && driverId != null) out["driverId"] = driverId
        val host = out.remove("host")
        val port = out.remove("port")
        val database = out.remove("database")
        if (out["url"] == null && eff.urlTemplate != null) {
            if (host != null && host !is String) throw QueryRejected("host must be a string")
            if (database != null && database !is String) throw QueryRejected("database must be a string")
            if (port != null && port !is Int && port !is Long) throw QueryRejected("port must be an integer")
            out["url"] = DriverSettings.buildUrl(eff.urlTemplate, eff.defaultPort, host as String?, (port as Number?)?.toInt(), database as String?)
        } else if (host != null || port != null || database != null) {
            if (out["url"] != null) throw QueryRejected("give a url or host, port and database, not both")
            throw QueryRejected("this driver has no url template; give a url")
        }
        if (out["translator"] == null && eff.translator != null) out["translator"] = eff.translator
        if (out["validationQuery"] == null && eff.validationQuery != null) out["validationQuery"] = eff.validationQuery
        if (eff.extraProperties.isNotEmpty() || out["extraProperties"] != null) {
            out["extraProperties"] = eff.extraProperties + extraProperties(out["extraProperties"])
        }
        return out
    }

    /** A built-in translator, or one defined by a `SqlTranslator` resource. */
    fun translator(name: String): SqlTranslator? = SqlTranslator.BUILT_IN[name] ?: translators[name]

    /** The translator of connection [name]. */
    fun connectionTranslator(name: String): SqlTranslator {
        val spec = managed(name).spec ?: throw QueryNotFound("database connection '$name' not found")
        return translator(spec.translator) ?: throw QueryRejected("translator '${spec.translator}' is not defined")
    }

    fun translators(): List<Map<String, Any?>> =
        (SqlTranslator.BUILT_IN.values + translators.values.sortedBy { it.name }).map { it.view() }

    /** [version] null replaces the stored translator, or creates it. */
    fun saveTranslator(name: String, fields: Map<String, Any?>, version: Long?): Resource {
        requireName(name)
        val parsed = SqlTranslator.fromFields(name, fields)
        return put(TRANSLATOR_KIND, name, version ?: currentVersion(TRANSLATOR_KIND, name), parsed.view().filterKeys { it != "name" && it != "builtIn" })
    }

    fun deleteTranslator(name: String, version: Long?) {
        val user = connections.values.firstOrNull { it.spec?.translator == name }
        if (user != null) throw QueryRejected("translator '$name' is used by connection '${user.name}'")
        store.delete(id(TRANSLATOR_KIND, name), version)
    }

    /** Every connection kind and every override, with the values a connection of that kind would get. */
    fun driverSettings(): List<Map<String, Any?>> {
        val names = (DriverSettings.KINDS + driverSettings.keys).distinct()
        return names.map { n ->
            val builtIn = n in DriverSettings.KINDS
            (if (builtIn) effectiveSettings(n, null) else driverSettings[n]!!).view(n, builtIn, driverSettings.containsKey(n))
        }
    }

    /** Store the override for [name], a connection kind or an uploaded driver id. Only the fields given are overridden. */
    fun saveDriverSettings(name: String, fields: Map<String, Any?>, version: Long?): Resource {
        requireName(name)
        if (name !in DriverSettings.KINDS && drivers.get(name) == null) {
            throw QueryRejected("'$name' is neither a driver kind nor an uploaded driver")
        }
        val parsed = DriverSettings.fromFields(fields)
        checkSettings(name, parsed)
        parsed.translator?.let { if (translator(it) == null) throw QueryRejected("translator '$it' is not defined") }
        parsed.driverId?.let { if (drivers.get(it) == null) throw QueryRejected("driver '$it' is not uploaded") }
        val doc = linkedMapOf<String, Any?>()
        parsed.urlTemplate?.let { doc["urlTemplate"] = it }
        parsed.defaultPort?.let { doc["defaultPort"] = it }
        parsed.translator?.let { doc["translator"] = it }
        parsed.validationQuery?.let { doc["validationQuery"] = it }
        parsed.driverId?.let { doc["driverId"] = it }
        if (parsed.extraProperties.isNotEmpty()) doc["extraProperties"] = parsed.extraProperties
        return put(DRIVER_SETTINGS_KIND, name, version ?: currentVersion(DRIVER_SETTINGS_KIND, name), doc)
    }

    /** A bundled driver keeps its own translator, as a connection of that kind must (see [connectionSpec]). */
    private fun checkSettings(name: String, parsed: DriverSettings) {
        val kind = DriverSettings.BUILT_IN[name]
        if (kind != null && name !in DriverSettings.EXTERNAL_KINDS && parsed.translator != null && parsed.translator != kind.translator) {
            throw QueryRejected("the translator of $name cannot be changed")
        }
        if (name !in DriverSettings.EXTERNAL_KINDS && name in DriverSettings.KINDS && parsed.driverId != null) {
            throw QueryRejected("$name uses a bundled driver, so it has no driverId")
        }
    }

    fun deleteDriverSettings(name: String, version: Long?) {
        store.delete(id(DRIVER_SETTINGS_KIND, name), version)
    }

    fun installDriver(id: String, bytes: ByteArray, driverClass: String?, actor: String): JdbcDriverStore.Info {
        val info = drivers.install(id, bytes, driverClass, actor)
        for (conn in connections.values.toList()) {
            if (conn.spec?.driverId == id) conn.resource?.let { runCatching { applyConnection(conn.name, it) } }
        }
        return info
    }

    fun removeDriver(id: String) {
        val user = connections.values.firstOrNull { it.spec?.driverId == id }
        if (user != null) throw QueryRejected("driver '$id' is used by connection '${user.name}'")
        drivers.remove(id)
    }

    fun driverViews(): List<Map<String, Any?>> = drivers.list().map { info ->
        info.view() + ("usedBy" to connections.values.filter { it.spec?.driverId == info.id }.map { it.name }.sorted())
    }

    private fun querySpec(fields: Map<String, Any?>): QuerySpec {
        val queryType = requiredString(fields, "queryType")
        if (queryType !in QUERY_TYPES) throw QueryRejected("queryType must be query, update, scalar, or procedure")
        val isProcedure = queryType == "procedure"
        if (isProcedure && fields["sql"] != null) throw QueryRejected("a procedure query names its procedure instead of sql")
        if (!isProcedure) {
            for (key in listOf("procedure", "routine", "returnType")) {
                if (fields[key] != null) throw QueryRejected("$key is only for queryType procedure")
            }
        }
        val cacheEnabled = optionalBool(fields, "cacheEnabled", false)
        val ttl = optionalLong(fields, "cacheTtlMillis", 0, 0, 86_400_000)
        if ((queryType == "update" || isProcedure) && cacheEnabled) throw QueryRejected("$queryType queries cannot be cached")
        if (cacheEnabled && ttl < 1) throw QueryRejected("cacheTtlMillis is required when caching is enabled")
        if (queryType != "scalar" && fields.containsKey("fallback")) throw QueryRejected("only a scalar query has a fallback")
        val parameters = parameters(fields["parameters"], isProcedure)
        val roles = stringList(fields["roles"], "roles")
        if (roles.size != roles.map { it.lowercase() }.toSet().size) throw QueryRejected("roles must be unique")
        val maxRows = optionalInt(fields, "maxRows", 10_000, 1, 100_000)
        val procedure = if (!isProcedure) null else ProcedureCall(
            routine = requiredString(fields, "procedure"),
            kind = RoutineKind.of(optionalString(fields, "routine", RoutineKind.PROCEDURE.wire)),
            parameters = parameters.map { ProcedureParam(it.name, it.mode, it.dataType) },
            returnType = fields["returnType"]?.let { it as? String ?: throw QueryRejected("returnType must be a string") },
            maxRows = maxRows,
        )
        return QuerySpec(
            description = optionalString(fields, "description", ""),
            enabled = optionalBool(fields, "enabled", true),
            connection = requiredString(fields, "connection"),
            queryType = queryType,
            sql = if (isProcedure) "" else requiredString(fields, "sql"),
            parameters = parameters,
            roles = roles,
            cacheEnabled = cacheEnabled,
            cacheTtlMillis = ttl,
            fallbackSet = fields.containsKey("fallback"),
            fallback = fields["fallback"],
            maxRows = maxRows,
            procedure = procedure,
        )
    }

    private fun parameters(raw: Any?, procedure: Boolean): List<QueryParam> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: throw QueryRejected("parameters must be a list")
        if (list.size > 64) throw QueryRejected("too many parameters")
        val out = ArrayList<QueryParam>()
        val seen = HashSet<String>()
        for (item in list) {
            val map = item as? Map<*, *> ?: throw QueryRejected("parameter must be an object")
            val name = map["name"] as? String ?: throw QueryRejected("parameter name is required")
            if (!PARAM.matches(name)) throw QueryRejected("parameter name '$name' is invalid")
            val type = map["dataType"] as? String ?: throw QueryRejected("parameter type is required")
            if (type !in TYPES) throw QueryRejected("parameter type '$type' is invalid")
            if (!seen.add(name.lowercase())) throw QueryRejected("duplicate parameter $name")
            val mode = when (val m = map["mode"]) {
                null -> ParamMode.IN
                is String -> ParamMode.of(m)
                else -> throw QueryRejected("parameter mode must be a string")
            }
            if (!procedure && mode != ParamMode.IN) throw QueryRejected("only a procedure query has out parameters")
            val queryString = when (val flag = map["queryString"]) {
                null -> false
                is Boolean -> flag
                else -> throw QueryRejected("queryString must be true or false")
            }
            if (queryString && procedure) throw QueryRejected("a procedure parameter cannot be a query string")
            if (queryString && type != "string") throw QueryRejected("a query-string parameter is text")
            out += QueryParam(name, type, mode, queryString)
        }
        return out
    }

    private fun stringList(raw: Any?, key: String): List<String> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: throw QueryRejected("$key must be a list")
        return list.map {
            val text = it as? String ?: throw QueryRejected("$key entries must be strings")
            if (!NAME.matches(text)) throw QueryRejected("$key entry '$text' is invalid")
            text
        }
    }

    private fun connectionDocument(spec: ConnectionSpec): Map<String, Any?> {
        val doc = linkedMapOf<String, Any?>(
            "driver" to spec.driver,
            "url" to spec.url,
            "username" to spec.username,
            "description" to spec.description,
            "enabled" to spec.enabled,
            "defaultConnection" to spec.defaultConnection,
            "translator" to spec.translator,
            "extraProperties" to spec.extra,
            "failoverConnection" to spec.failoverConnection,
            "failoverMode" to spec.failoverMode,
            "slowQueryMillis" to spec.slowQueryMillis,
            "validationTimeoutMillis" to spec.validationTimeoutMillis,
            "validationQuery" to spec.validationQuery,
            "initialSize" to spec.initialSize,
            "maxActive" to spec.maxActive,
            "maxIdle" to spec.maxIdle,
            "minIdle" to spec.minIdle,
            "maxWaitMillis" to spec.maxWaitMillis,
            "allowBrowserWrites" to spec.allowBrowserWrites,
        )
        if (spec.driverId.isNotEmpty()) doc["driverId"] = spec.driverId
        if (spec.passwordRef != null) doc["password"] = spec.passwordRef
        return doc
    }

    private fun queryDocument(spec: QuerySpec): Map<String, Any?> {
        val doc = linkedMapOf<String, Any?>(
            "description" to spec.description,
            "enabled" to spec.enabled,
            "connection" to spec.connection,
            "queryType" to spec.queryType,
            "sql" to spec.sql,
            "parameters" to parameterDocs(spec),
            "roles" to spec.roles,
            "cacheEnabled" to spec.cacheEnabled,
            "cacheTtlMillis" to spec.cacheTtlMillis,
            "maxRows" to spec.maxRows,
        )
        if (spec.fallbackSet) doc["fallback"] = spec.fallback
        procedureFields(spec, doc)
        return doc
    }

    private fun parameterDocs(spec: QuerySpec): List<Map<String, Any?>> = spec.parameters.map {
        val doc = linkedMapOf<String, Any?>("name" to it.name, "dataType" to it.dataType)
        if (it.queryString) doc["queryString"] = true
        if (spec.procedure != null) doc["mode"] = it.mode.wire
        doc
    }

    private fun procedureFields(spec: QuerySpec, doc: MutableMap<String, Any?>) {
        val call = spec.procedure ?: return
        doc.remove("sql")
        doc["procedure"] = call.routine
        doc["routine"] = call.kind.wire
        call.returnType?.let { doc["returnType"] = it }
    }

    private fun view(conn: ManagedConnection): Map<String, Any?> {
        val spec = conn.spec
        val pool = conn.pool
        val mx = pool?.hikariPoolMXBean
        val body = linkedMapOf<String, Any?>(
            "name" to conn.name,
            "driver" to spec?.driver,
            "translator" to spec?.translator,
            "driverId" to spec?.driverId,
            "url" to spec?.url,
            "username" to spec?.username,
            "description" to spec?.description,
            "enabled" to (spec?.enabled ?: false),
            "defaultConnection" to (spec?.defaultConnection ?: false),
            "passwordSet" to (spec?.passwordRef != null),
            "status" to conn.status,
            "error" to conn.error,
            "activeConnections" to (mx?.activeConnections ?: 0),
            "idleConnections" to (mx?.idleConnections ?: 0),
            "maxConnections" to (pool?.maximumPoolSize ?: 0),
            "queriesPerSecond" to queriesPerSecond(conn),
            "activeQueries" to conn.inflight.get(),
            "failoverConnection" to spec?.failoverConnection,
            "failoverMode" to spec?.failoverMode,
            "validationQuery" to spec?.validationQuery,
            "allowBrowserWrites" to (spec?.allowBrowserWrites ?: false),
            "version" to conn.version,
            "usedBy" to runCatching { usage(conn.name) }.getOrDefault(emptyList()),
        )
        return body
    }

    private fun queriesPerSecond(conn: ManagedConnection): Int = synchronized(conn.recent) { conn.recent.size }

    private fun queryView(name: String, live: LiveQuery): Map<String, Any?> {
        val spec = live.spec
        val body = linkedMapOf<String, Any?>(
            "name" to name,
            "description" to spec.description,
            "enabled" to spec.enabled,
            "connection" to spec.connection,
            "queryType" to spec.queryType,
            "sql" to spec.sql,
            "parameters" to parameterDocs(spec),
            "roles" to spec.roles,
            "cacheEnabled" to spec.cacheEnabled,
            "cacheTtlMillis" to spec.cacheTtlMillis,
            "maxRows" to spec.maxRows,
            "version" to live.version,
        )
        if (spec.fallbackSet) body["fallback"] = spec.fallback
        procedureFields(spec, body)
        return body
    }

    private fun liveQuery(name: String): LiveQuery =
        queries[name] ?: throw QueryNotFound("named query '$name' not found")

    private fun managed(name: String): ManagedConnection =
        connections[name] ?: throw QueryNotFound("database connection '$name' not found")

    private fun put(kind: String, name: String, version: Long, spec: Map<String, Any?>): Resource =
        store.put(Scope.GATEWAY, Resource(kind, Metadata(name, DB_MODULE, version = version), spec), "api")

    private fun currentVersion(kind: String, name: String): Long = store.get(id(kind, name))?.metadata?.version ?: 0L

    private fun withDefaults(kind: String, spec: Map<String, Any?>): Map<String, Any?> =
        store.registry.applyDefaults(DB_MODULE, kind, spec)

    private fun id(kind: String, name: String) = ResourceId(DB_MODULE, Scope.GATEWAY, kind, name)

    private fun resolveUrl(name: String, driver: String, url: String): String {
        if (driver == "postgres" || driver == "sqlserver" || driver in DriverSettings.EXTERNAL_KINDS) return url
        if (driver == "azuresql") return AzureSqlUrl.resolve(url)
        if (driver == "mysql" || driver == "mariadb") return mysqlThinUrl(url)
        val raw = url.removePrefix("jdbc:sqlite:")
        if (raw == ":memory:" || raw == ":memory" || raw.isEmpty()) {
            return "jdbc:sqlite:file:oip-$name?mode=memory&cache=shared"
        }
        if (raw.startsWith("file:") && raw.contains("mode=memory")) return url
        val split = raw.indexOf('?')
        val pathPart = if (split < 0) raw else raw.substring(0, split)
        val query = if (split < 0) "" else raw.substring(split)
        val expanded = pathPart.replace("\${data}", dataDir.toAbsolutePath().toString())
        val path = Path.of(expanded)
        val absolute = if (path.isAbsolute) path.normalize() else dataDir.resolve(path).normalize()
        absolute.parent?.let { Files.createDirectories(it) }
        val suffix = when {
            query.contains("busy_timeout") -> query
            query.isEmpty() -> "?busy_timeout=5000"
            else -> "$query&busy_timeout=5000"
        }
        return "jdbc:sqlite:$absolute$suffix"
    }

    /** The bundled BSD client reads `jdbc:mysql:thin://`; the URL as written (`jdbc:mysql:` or `jdbc:mariadb:`) is what is stored. */
    private fun mysqlThinUrl(url: String): String =
        "jdbc:mysql:thin:" + url.removePrefix("jdbc:mysql:").removePrefix("jdbc:mariadb:")

    private fun checkUrl(driver: String, url: String) {
        if (url.any { it.isISOControl() || it == ' ' }) throw QueryRejected("url must not contain spaces or control characters")
        if (SECRET_IN_URL.containsMatchIn(url)) throw QueryRejected("put the password in the password field, not the url")
        when (driver) {
            "postgres" -> {
                if (!url.startsWith("jdbc:postgresql:")) throw QueryRejected("postgres url must start with jdbc:postgresql:")
                val rest = url.removePrefix("jdbc:postgresql:")
                if (rest.startsWith("//") && rest.removePrefix("//").substringBefore('/').contains('@')) {
                    throw QueryRejected("put the user and password in their fields, not the url")
                }
            }
            "mysql", "mariadb" -> {
                if (!url.startsWith("jdbc:mysql://") && !url.startsWith("jdbc:mariadb://")) {
                    throw QueryRejected("$driver url must start with jdbc:mysql:// or jdbc:mariadb://")
                }
                if (url.substringAfter("//").substringBefore('/').contains('@') || Regex("(?i)[?&;]user(name)?=").containsMatchIn(url)) {
                    throw QueryRejected("put the user and password in their fields, not the url")
                }
            }
            "azuresql" -> {
                AzureSqlUrl.check(url)
                if (Regex("(?i)(^|;)user(name)?\\s*=").containsMatchIn(url)) {
                    throw QueryRejected("put the user and password in their fields, not the url")
                }
            }
            "sqlite" -> if (!url.startsWith("jdbc:sqlite:")) throw QueryRejected("sqlite url must start with jdbc:sqlite:")
            "oracle", "db2", "snowflake", "custom" -> {
                val prefix = if (driver == "custom") "jdbc:" else "jdbc:$driver:"
                if (!url.startsWith(prefix)) throw QueryRejected("$driver url must start with $prefix")
                // Thin-style `user/password@host`, and a user name in the url, are credentials: they go in their fields.
                if (Regex("(?i)[?&;:](user|username)\\s*=").containsMatchIn(url) || Regex("^jdbc:oracle:[a-z]+:[^@/]*/[^@]*@").containsMatchIn(url)) {
                    throw QueryRejected("put the user and password in their fields, not the url")
                }
            }
            "sqlserver" -> {
                if (!url.startsWith("jdbc:sqlserver:")) throw QueryRejected("sqlserver url must start with jdbc:sqlserver:")
                if (Regex("(?i)(^|;)user(name)?\\s*=").containsMatchIn(url)) {
                    throw QueryRejected("put the user and password in their fields, not the url")
                }
            }
        }
    }

    private fun requireName(name: String) {
        if (!NAME.matches(name)) throw QueryRejected("name must be a lowercase resource name")
    }

    private fun requiredString(fields: Map<String, Any?>, key: String): String {
        val value = fields[key] ?: throw QueryRejected("$key is required")
        return value as? String ?: throw QueryRejected("$key must be a string")
    }

    private fun optionalString(fields: Map<String, Any?>, key: String, default: String): String {
        if (!fields.containsKey(key) || fields[key] == null) return default
        return fields[key] as? String ?: throw QueryRejected("$key must be a string")
    }

    private fun optionalBool(fields: Map<String, Any?>, key: String, default: Boolean): Boolean {
        if (!fields.containsKey(key) || fields[key] == null) return default
        return fields[key] as? Boolean ?: throw QueryRejected("$key must be true or false")
    }

    private fun optionalInt(fields: Map<String, Any?>, key: String, default: Int, min: Int, max: Int): Int {
        val n = optionalLong(fields, key, default.toLong(), min.toLong(), max.toLong())
        return n.toInt()
    }

    private fun optionalLong(fields: Map<String, Any?>, key: String, default: Long, min: Long, max: Long): Long {
        if (!fields.containsKey(key) || fields[key] == null) return default
        val raw = fields[key]
        val n = when (raw) {
            is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
            else -> throw QueryRejected("$key must be an integer")
        }
        if (n !in min..max) throw QueryRejected("$key is out of range")
        return n
    }

    companion object {
        private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        private val SECRET = Regex("^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        private val PARAM = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,62}$")
        private val SECRET_IN_URL = Regex("(?i)(password|pwd)\\s*=")
        private val TYPES = setOf("string", "integer", "long", "float", "double", "boolean", "datetime")
        private val CONNECTION_FIELDS = setOf(
            "driver", "url", "username", "description", "enabled", "defaultConnection", "translator",
            "extraProperties", "failoverConnection", "failoverMode", "slowQueryMillis", "validationTimeoutMillis",
            "validationQuery", "initialSize", "maxActive", "maxIdle", "minIdle", "maxWaitMillis", "allowBrowserWrites",
            "driverId", "host", "port", "database",
        )
        private val QUERY_FIELDS = setOf(
            "description", "enabled", "connection", "queryType", "sql", "parameters", "roles",
            "cacheEnabled", "cacheTtlMillis", "fallback", "maxRows", "procedure", "routine", "returnType",
        )
        private val QUERY_TYPES = setOf("query", "update", "scalar", "procedure")
        const val MAX_BROWSE_ROWS = 10_000
    }
}

private class ManagedConnection(val name: String) {
    @Volatile var spec: ConnectionSpec? = null
    @Volatile var resource: Resource? = null
    @Volatile var pool: HikariDataSource? = null
    @Volatile var password: String? = null
    @Volatile var identity: String? = null
    @Volatile var status: String = "Faulted"
    @Volatile var error: String? = null
    @Volatile var version: Long = 0
    @Volatile var sticky: Boolean = false
    val inflight = AtomicInteger()
    val recent: ArrayDeque<Long> = ArrayDeque()
    override fun toString(): String = "connection $name $status"
}

private data class LiveQuery(val version: Long, val spec: QuerySpec)
