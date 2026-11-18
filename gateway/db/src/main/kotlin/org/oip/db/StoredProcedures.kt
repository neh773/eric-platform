package org.oip.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Stored-procedure and function calls with in, out, and in/out parameters and a return value.
 *
 * Behaviour follows the public page on calling stored procedures (wording and structure here are our own):
 *
 * The call is written as plain SQL for the connection's database instead of the JDBC `{call}` escape,
 * because the escape's meaning depends on a driver setting and the two kinds of routine behave differently:
 *
 * - A procedure is `CALL name(...)`. Every parameter has a position: in and in/out parameters are bound
 *   placeholders and out parameters are `NULL`. The database returns one row holding the out and in/out
 *   values.
 * - A function with out or in/out parameters is `SELECT * FROM name(...)` with only the in and in/out
 *   arguments; its single row holds the outputs.
 * - A function with a return type and no outputs is `SELECT name(...)`; the one value is the return value.
 * - Any other function is `SELECT * FROM name(...)`, and its rows are the call's result set.
 *
 * Output values are matched to parameters by column name, ignoring case. When no column names any output,
 * they are matched by position; when only some are named, the call fails rather than mix the two.
 * Only PostgreSQL connections can call routines; SQLite has no stored procedures and is refused.
 * Every value is bound; the routine name is checked against a strict identifier pattern.
 */
enum class ParamMode(val wire: String) {
    IN("in"), OUT("out"), INOUT("inout");

    val sends: Boolean get() = this != OUT
    val receives: Boolean get() = this != IN

    companion object {
        fun of(raw: String): ParamMode = entries.firstOrNull { it.wire == raw }
            ?: throw QueryRejected("parameter mode must be in, out, or inout")
    }
}

enum class RoutineKind(val wire: String) {
    PROCEDURE("procedure"), FUNCTION("function");

    companion object {
        fun of(raw: String): RoutineKind = entries.firstOrNull { it.wire == raw }
            ?: throw QueryRejected("routine must be procedure or function")
    }
}

data class ProcedureParam(val name: String, val mode: ParamMode, val dataType: String)

data class ProcedureCall(
    val routine: String,
    val kind: RoutineKind,
    val parameters: List<ProcedureParam>,
    val returnType: String? = null,
    val maxRows: Int = 10_000,
) {
    init {
        StoredProcedures.check(this)
    }
}

/**
 * What a call produced. [outputs] has one entry per out and in/out parameter, keyed by the declared name.
 * [columns] and [rows] are the result set of a set-returning function; empty otherwise.
 */
data class ProcedureOutcome(
    val returnValue: Any?,
    val outputs: Map<String, Any?>,
    val columns: List<String>,
    val rows: List<List<Any?>>,
    val truncated: Boolean,
)

object StoredProcedures {
    /** Optionally schema-qualified. Written into the SQL unquoted, so the pattern is the only gate. */
    private val ROUTINE = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?$")
    private val PARAM = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,62}$")
    val TYPES = setOf("string", "integer", "long", "float", "double", "boolean", "datetime")
    const val MAX_PARAMETERS = 64
    const val RETURN_COLUMN = "return_value"

    internal fun check(call: ProcedureCall) {
        if (!ROUTINE.matches(call.routine)) throw QueryRejected("procedure name must be a plain or schema-qualified identifier")
        if (call.parameters.size > MAX_PARAMETERS) throw QueryRejected("too many parameters")
        if (call.maxRows !in 1..100_000) throw QueryRejected("maxRows is out of range")
        val seen = HashSet<String>()
        for (p in call.parameters) {
            if (!PARAM.matches(p.name)) throw QueryRejected("parameter name '${p.name}' is invalid")
            if (p.dataType !in TYPES) throw QueryRejected("parameter type '${p.dataType}' is invalid")
            if (!seen.add(p.name.lowercase())) throw QueryRejected("duplicate parameter ${p.name}")
        }
        val returnType = call.returnType
        if (returnType != null) {
            if (returnType !in TYPES) throw QueryRejected("return type '$returnType' is invalid")
            if (call.kind == RoutineKind.PROCEDURE) throw QueryRejected("a procedure has no return value; use an out parameter")
            if (call.parameters.any { it.mode.receives }) {
                throw QueryRejected("a function with out parameters returns them instead of a return value")
            }
        }
    }

    /** SQL text and the parameter names bound to its placeholders, in order. */
    data class Plan(val sql: String, val binds: List<String>)

    fun plan(call: ProcedureCall): Plan {
        val outputs = call.parameters.any { it.mode.receives }
        return when (call.kind) {
            RoutineKind.PROCEDURE -> {
                val args = call.parameters.map { if (it.mode.sends) "?" else "NULL" }
                Plan("CALL ${call.routine}(${args.joinToString(", ")})", call.parameters.filter { it.mode.sends }.map { it.name })
            }
            RoutineKind.FUNCTION -> {
                val sent = call.parameters.filter { it.mode.sends }
                val args = sent.joinToString(", ") { "?" }
                val sql = when {
                    outputs -> "SELECT * FROM ${call.routine}($args)"
                    call.returnType != null -> "SELECT ${call.routine}($args) AS $RETURN_COLUMN"
                    else -> "SELECT * FROM ${call.routine}($args)"
                }
                Plan(sql, sent.map { it.name })
            }
        }
    }

    /**
     * Run [call] on [conn]. [values] are already coerced and keyed by lower-case parameter name; a missing
     * in or in/out value is bound as SQL NULL. [read] turns one column of the current row into a value.
     * A [java.sql.SQLException] is left to the caller, which owns redaction.
     */
    fun execute(
        conn: Connection,
        call: ProcedureCall,
        values: Map<String, Any?>,
        timeoutSeconds: Int = 0,
        read: (ResultSet, Int) -> Any? = { rs, i -> jsonValue(rs.getObject(i)) },
    ): ProcedureOutcome {
        requireSupported(conn)
        val plan = plan(call)
        conn.prepareStatement(plan.sql).use { st ->
            if (timeoutSeconds > 0) st.queryTimeout = timeoutSeconds
            plan.binds.forEachIndexed { i, name -> bind(st, i + 1, values[name.lowercase()]) }
            val hasRows = st.execute()
            val receiving = call.parameters.filter { it.mode.receives }
            if (receiving.isNotEmpty()) {
                if (!hasRows) throw QueryFailed("${call.routine} did not return its output parameters")
                return st.resultSet.use { rs -> outputs(call, receiving, rs, read) }
            }
            if (call.kind == RoutineKind.FUNCTION && call.returnType != null) {
                if (!hasRows) throw QueryFailed("${call.routine} did not return a value")
                val value = st.resultSet.use { rs -> if (rs.next()) read(rs, 1) else null }
                return ProcedureOutcome(value, emptyMap(), emptyList(), emptyList(), truncated = false)
            }
            if (call.kind == RoutineKind.FUNCTION && hasRows) {
                return st.resultSet.use { rs -> rows(rs, call.maxRows, read) }
            }
            return ProcedureOutcome(null, emptyMap(), emptyList(), emptyList(), truncated = false)
        }
    }

    private fun outputs(call: ProcedureCall, receiving: List<ProcedureParam>, rs: ResultSet, read: (ResultSet, Int) -> Any?): ProcedureOutcome {
        if (!rs.next()) throw QueryFailed("${call.routine} returned no row for its output parameters")
        val meta = rs.metaData
        val labels = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
        val out = LinkedHashMap<String, Any?>()
        // Every output by name, or (when the routine names none of them) every output by position. Mixing
        // the two could hand one output's column to another, so a partial name match is an error.
        val byName = receiving.map { labels.indexOf(it.name.lowercase()) }
        val missing = receiving.filterIndexed { i, _ -> byName[i] < 0 }.map { it.name }
        if (missing.isNotEmpty() && missing.size < receiving.size) {
            throw QueryFailed(
                "${call.routine} returned columns ${labels.joinToString(", ")}, which name some outputs but not " +
                    missing.joinToString(", ") + "; name every output as the routine returns it",
            )
        }
        receiving.forEachIndexed { position, param ->
            val column = when {
                byName[position] >= 0 -> byName[position] + 1
                position < labels.size -> position + 1
                else -> throw QueryFailed("${call.routine} did not return output ${param.name}")
            }
            out[param.name] = read(rs, column)
        }
        return ProcedureOutcome(null, out, emptyList(), emptyList(), truncated = false)
    }

    private fun rows(rs: ResultSet, maxRows: Int, read: (ResultSet, Int) -> Any?): ProcedureOutcome {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
        val rows = ArrayList<List<Any?>>()
        var truncated = false
        while (rs.next()) {
            if (rows.size == maxRows) {
                truncated = true
                break
            }
            rows += (1..meta.columnCount).map { read(rs, it) }
        }
        return ProcedureOutcome(null, emptyMap(), columns, rows, truncated)
    }

    private fun requireSupported(conn: Connection) {
        val product = runCatching { conn.metaData.databaseProductName }.getOrNull().orEmpty()
        if (!product.equals("PostgreSQL", ignoreCase = true)) {
            throw QueryRejected(
                if (product.equals("SQLite", ignoreCase = true)) "SQLite has no stored procedures"
                else "stored procedures are not supported on this database",
            )
        }
    }

    /** Coerce the in and in/out values of [raw] (any case) to their declared types. Unknown names are refused. */
    fun coerce(call: ProcedureCall, raw: Map<String, Any?>): Map<String, Any?> {
        val incoming = LinkedHashMap<String, Any?>()
        for ((key, value) in raw) {
            val name = key.lowercase()
            if (incoming.containsKey(name)) throw QueryRejected("duplicate parameter $key")
            incoming[name] = value
        }
        val known = call.parameters.associateBy { it.name.lowercase() }
        val unknown = incoming.keys - known.keys
        if (unknown.isNotEmpty()) throw QueryRejected("unknown parameter ${unknown.sorted().joinToString(",")}")
        val out = LinkedHashMap<String, Any?>()
        for (param in call.parameters) {
            val key = param.name.lowercase()
            if (!param.mode.sends) {
                if (incoming.containsKey(key)) throw QueryRejected("${param.name} is an out parameter and takes no value")
                continue
            }
            if (!incoming.containsKey(key)) throw QueryRejected("missing parameter ${param.name}")
            out[key] = Coercion.coerce(param.dataType, incoming[key])
        }
        return out
    }

    private fun bind(st: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> st.setObject(index, null)
            is Int -> st.setInt(index, value)
            is Long -> st.setLong(index, value)
            is Float -> st.setFloat(index, value)
            is Double -> st.setDouble(index, value)
            is Boolean -> st.setBoolean(index, value)
            is String -> st.setString(index, value)
            is java.sql.Timestamp -> st.setTimestamp(index, value)
            else -> st.setObject(index, value)
        }
    }
}
