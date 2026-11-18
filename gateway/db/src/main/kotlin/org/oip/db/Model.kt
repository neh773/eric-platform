package org.oip.db

/**
 * Gateway database connections and named queries.
 *
 * Connections: -
 * Adding one (URL, credentials, pool, validation, failover):
 * PostgreSQL: -
 * SQLite: -
 * Named queries: -
 * Parameters: -
 * Caching: -
 * Query types and role security: -
 *
 * Stored procedures: -
 * (see [StoredProcedures]; a named query of type `procedure` calls one).
 *
 * A parameter with `queryString: true` is substituted as an identifier (`{name}` in the SQL). Every other
 * parameter is a JDBC placeholder.
 */
const val DB_MODULE = "db"
const val CONNECTION_KIND = "DatabaseConnection"
const val QUERY_KIND = "NamedQuery"

/** Per-driver defaults (DAT-027) and SQL dialect translators (DAT-016). */
const val DRIVER_SETTINGS_KIND = "DriverSettings"
const val TRANSLATOR_KIND = "SqlTranslator"

/** Faulted connections are checked on this period. The public status page uses the same 10 seconds. */
const val RETEST_INTERVAL_MILLIS = 10_000L

const val CONNECTION_DEFAULT = "<default>"
const val CONNECTION_PARAMETER = "<parameter>"

class QueryNotFound(message: String) : RuntimeException(message)
class QueryForbidden(message: String) : RuntimeException(message)
class QueryRejected(message: String) : RuntimeException(message)
class QueryUnavailable(message: String) : RuntimeException(message)
class QueryFailed(message: String) : RuntimeException(message)

/** How a create or update supplies the database password. The config file stores a secret reference, never the text. */
sealed class PasswordUpdate {
    data object Unchanged : PasswordUpdate()
    data object Clear : PasswordUpdate()
    data class Set(val plaintext: String) : PasswordUpdate()
    data class Reference(val reference: String) : PasswordUpdate()
}

sealed class QueryResult {
    data class Rows(
        val columns: List<String>,
        val rows: List<List<Any?>>,
        val cached: Boolean,
        val truncated: Boolean,
    ) : QueryResult()

    data class Scalar(val value: Any?, val cached: Boolean, val fallback: Boolean) : QueryResult()
    data class Update(val rowsAffected: Int) : QueryResult()

    /** A stored-procedure call: return value, out and in/out values by name, and any result set. */
    data class Procedure(
        val returnValue: Any?,
        val outputs: Map<String, Any?>,
        val columns: List<String>,
        val rows: List<List<Any?>>,
        val truncated: Boolean,
    ) : QueryResult() {
        /**
         * One table for callers that only take rows (scripts, reports): the result set when there is one,
         * otherwise a single row of the outputs plus `return_value` when the routine returned one.
         */
        fun table(): Pair<List<String>, List<List<Any?>>> {
            if (columns.isNotEmpty()) return columns to rows
            val names = outputs.keys.toMutableList()
            val values = outputs.values.toMutableList()
            if (returnValue != null || names.isEmpty()) {
                names += StoredProcedures.RETURN_COLUMN
                values += returnValue
            }
            return names to listOf(values)
        }
    }
}

/** Result of an ad hoc statement from the query browser: rows for a read, a count for an allowed write. */
sealed class BrowseResult {
    data class Rows(val columns: List<String>, val rows: List<List<Any?>>, val truncated: Boolean) : BrowseResult()
    data class Update(val rowsAffected: Int) : BrowseResult()
}

internal fun QueryResult.cachedCopy(): QueryResult = when (this) {
    is QueryResult.Rows -> copy(rows = rows.map { it.toList() }, cached = true)
    is QueryResult.Scalar -> copy(cached = true)
    is QueryResult.Update -> this
    is QueryResult.Procedure -> this
}

internal data class ConnectionSpec(
    val driver: String,
    val url: String,
    val resolvedUrl: String,
    val username: String,
    val passwordRef: String?,
    val description: String,
    val enabled: Boolean,
    val defaultConnection: Boolean,
    val translator: String,
    /** Id of the uploaded JDBC driver (DAT-016); empty for a bundled driver. */
    val driverId: String,
    val extra: Map<String, String>,
    val failoverConnection: String,
    val failoverMode: String,
    val slowQueryMillis: Long,
    val validationTimeoutMillis: Long,
    val validationQuery: String,
    val initialSize: Int,
    val maxActive: Int,
    val maxIdle: Int,
    val minIdle: Int,
    val maxWaitMillis: Long,
    val allowBrowserWrites: Boolean = false,
)

internal data class QuerySpec(
    val description: String,
    val enabled: Boolean,
    val connection: String,
    val queryType: String,
    val sql: String,
    val parameters: List<QueryParam>,
    val roles: List<String>,
    val cacheEnabled: Boolean,
    val cacheTtlMillis: Long,
    val fallbackSet: Boolean,
    val fallback: Any?,
    val maxRows: Int,
    /** Set for queryType `procedure`; [sql] is empty then. */
    val procedure: ProcedureCall? = null,
)

internal data class QueryParam(
    val name: String,
    val dataType: String,
    val mode: ParamMode = ParamMode.IN,
    /** When true, `{name}` in the SQL is replaced with an identifier. Otherwise `:name` is bound. */
    val queryString: Boolean = false,
)

internal data class PreparedSql(val sql: String, val names: List<String>)
