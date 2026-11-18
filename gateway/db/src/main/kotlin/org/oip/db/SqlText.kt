package org.oip.db

/**
 * The quoting and comment rules of one database, as far as [SqlText] needs them to find where a statement
 * ends. Getting these wrong in the permissive direction would let a second statement through, so each
 * dialect follows its server's lexer:
 * - PostgreSQL: `'…'` (backslash escapes only in `E'…'`, or in every string when [standardConformingStrings]
 *   is off), `"…"` identifiers, `$$…$$` and `$tag$…$tag$` dollar quotes, and nested block comments.
 *   https://www.postgresql.org/docs/16/sql-syntax-lexical.html
 * - SQLite: `'…'`, `"…"`, backticks and `[…]` identifiers, no backslash escapes, block comments do not nest.
 *   https://www.sqlite.org/lang_keywords.html
 * - SQL Server: `'…'` and `"…"` (doubled quote escapes), `[…]` identifiers, no backticks, no dollar quotes,
 *   no backslash escapes, block comments do not nest.
 * - MySQL and MariaDB: `'…'` and `"…"` strings with backslash escapes (the default `sql_mode`), backtick
 *   identifiers, `#` and `-- ` line comments (the two dashes need a following blank), no nested block
 *   comments. A version comment (`/*! … */`, `/*M! … */`) is executed by the server, so it is refused.
 *   https://dev.mysql.com/doc/refman/8.0/en/comments.html
 */
internal data class SqlDialect(
    val postgres: Boolean,
    val standardConformingStrings: Boolean = true,
    val mssql: Boolean = false,
    val mysql: Boolean = false,
) {
    init {
        require(!postgres || !mssql) { "a dialect is not both PostgreSQL and SQL Server" }
        require(!mysql || (!postgres && !mssql)) { "a dialect is not MySQL and another database" }
    }

    companion object {
        val POSTGRES = SqlDialect(postgres = true)
        val SQLITE = SqlDialect(postgres = false)
        val MSSQL = SqlDialect(postgres = false, mssql = true)
        val MYSQL = SqlDialect(postgres = false, mysql = true)

        /** The dialect of a connection's `driver` field. */
        fun of(driver: String?): SqlDialect = when (driver) {
            "sqlite" -> SQLITE
            "sqlserver", "azuresql" -> MSSQL
            "mysql", "mariadb" -> MYSQL
            // Oracle, DB2, Snowflake and uploaded drivers: ANSI quotes. Oracle `q'[…]'` and Snowflake `$$…$$` strings are not
            // recognised, which can only make the scanner stricter (DIFFERENCES 770).
            "oracle", "db2", "snowflake", "custom" -> SQLITE
            else -> POSTGRES
        }
    }
}

/**
 * Turns `:name` value parameters into JDBC `?` placeholders.
 *
 * Text inside quotes, identifiers, and comments is left alone. A second statement is rejected.
 * `{name}` is a query-string parameter: [substitute] replaces it with an identifier. `:name` stays a placeholder.
 */
internal object SqlText {
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_-]{0,62}")

    fun prepare(sql: String, known: Set<String>, dialect: SqlDialect): PreparedSql =
        scan(sql, known, placeholders = true, dialect)

    private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")

    /**
     * Replace `{name}` outside quotes and comments with the identifier in [queryStrings].
     * A brace name that is a value parameter is refused: those use `:name`.
     */
    fun substitute(sql: String, queryStrings: Map<String, String>, valueNames: Set<String>, dialect: SqlDialect): String {
        if (queryStrings.isEmpty() && valueNames.isEmpty()) return sql
        for ((name, value) in queryStrings) {
            if (!IDENTIFIER.matches(value)) throw QueryRejected("query-string parameter $name must be an identifier")
        }
        val out = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val skip = commentEnd(sql, i, dialect, strict = true) ?: quoteEnd(sql, i, dialect)
            if (skip != null) {
                out.append(sql, i, skip)
                i = skip
                continue
            }
            if (sql[i] == '{') {
                val end = sql.indexOf('}', i + 1)
                if (end > i + 1) {
                    val raw = sql.substring(i + 1, end)
                    if (IDENTIFIER.matches(raw)) {
                        val key = raw.lowercase()
                        val value = queryStrings[key]
                        if (value != null) {
                            out.append(value)
                            i = end + 1
                            continue
                        }
                        if (key in valueNames) throw QueryRejected("parameter $raw is a value parameter; use :$raw")
                    }
                }
            }
            out.append(sql[i])
            i++
        }
        return out.toString()
    }

    /**
     * One ad hoc statement for the query browser: the same single-statement, quote, and comment rules, but
     * `?` and `:name` are left as written (nothing is bound). A trailing `;` is dropped.
     */
    fun single(sql: String, dialect: SqlDialect): String = scan(sql, emptySet(), placeholders = false, dialect).sql

    private val READ_KEYWORDS = setOf("select", "with", "values", "table", "show", "explain")
    private val TRANSACTION_KEYWORDS = setOf("begin", "start", "commit", "end", "rollback", "abort", "savepoint", "release")

    /**
     * True when [statement] starts with a read keyword, after leading comments, whitespace, and parentheses.
     * This is the first gate only: the browser also runs reads in a read-only transaction.
     */
    fun isRead(statement: String, dialect: SqlDialect): Boolean = words(statement, dialect, 1).firstOrNull() in READ_KEYWORDS

    /**
     * True when [statement] starts or ends a transaction, sets a savepoint, or changes transaction
     * characteristics. The query browser manages the transaction itself, so these are refused.
     */
    fun isTransactionControl(statement: String, dialect: SqlDialect): Boolean {
        val w = words(statement, dialect, 3)
        val first = w.firstOrNull() ?: return false
        if (first in TRANSACTION_KEYWORDS) return true
        if (first == "prepare" && w.getOrNull(1) == "transaction") return true
        if (first == "set" && w.getOrNull(1) == "transaction") return true
        return first == "set" && w.getOrNull(1) == "session" && w.getOrNull(2) == "characteristics"
    }

    /**
     * The first [count] words of [statement], lower case, skipping comments, whitespace, and parentheses
     * before the first one. A word is a whole identifier, so `selectx` is not `select`.
     */
    private fun words(statement: String, dialect: SqlDialect, count: Int): List<String> {
        val out = ArrayList<String>(count)
        var i = 0
        while (i < statement.length && out.size < count) {
            val c = statement[i]
            val comment = commentEnd(statement, i, dialect, strict = false)
            when {
                comment != null -> i = comment
                c.isWhitespace() || (c == '(' && out.isEmpty()) -> i++
                c.isLetter() -> {
                    val start = i
                    while (i < statement.length && identifierChar(statement[i])) i++
                    out += statement.substring(start, i).lowercase()
                }
                else -> return out
            }
        }
        return out
    }

    /**
     * End (exclusive) of the comment that starts at [i], or null when no comment starts there. An unterminated
     * block comment is rejected when [strict], and otherwise runs to the end of the text.
     */
    private fun commentEnd(sql: String, i: Int, dialect: SqlDialect, strict: Boolean): Int? {
        if (dialect.mysql) {
            if (sql.startsWith("/*!", i) || sql.startsWith("/*M!", i)) {
                throw QueryRejected("version comments are not accepted")
            }
            val dashes = sql.startsWith("--", i) && (i + 2 >= sql.length || sql[i + 2].isWhitespace() || sql[i + 2].isISOControl())
            if (dashes || sql[i] == '#') return sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
        } else if (sql.startsWith("--", i)) {
            return sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
        }
        if (!sql.startsWith("/*", i)) return null
        var depth = 1
        var j = i + 2
        while (j < sql.length) {
            when {
                dialect.postgres && sql.startsWith("/*", j) -> {
                    depth++
                    j += 2
                }
                sql.startsWith("*/", j) -> {
                    depth--
                    j += 2
                    if (depth == 0) return j
                }
                else -> j++
            }
        }
        if (strict) throw QueryRejected("unterminated comment")
        return sql.length
    }

    private fun scan(sql: String, known: Set<String>, placeholders: Boolean, dialect: SqlDialect): PreparedSql {
        if (sql.length > 100_000) throw QueryRejected("query is too long")
        if (sql.indexOf('\u0000') >= 0) throw QueryRejected("query contains a NUL")
        val out = StringBuilder(sql.length)
        val names = ArrayList<String>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val skip = commentEnd(sql, i, dialect, strict = true) ?: quoteEnd(sql, i, dialect)
            if (skip != null) {
                out.append(sql, i, skip)
                i = skip
                continue
            }
            if (c == ';') {
                if (sql.substring(i + 1).isNotBlank()) throw QueryRejected("only one SQL statement is allowed")
                break
            }
            if (!placeholders) {
                out.append(c)
                i++
                continue
            }
            if (c == '?') throw QueryRejected("use a named parameter (:name); '?' is not accepted")
            if (c == ':' && i + 1 < sql.length && sql[i + 1] == ':') {
                out.append("::")
                i += 2
                continue
            }
            if (c == ':' && i + 1 < sql.length) {
                val match = NAME.matchAt(sql, i + 1)
                if (match != null) {
                    val raw = match.value
                    val key = raw.lowercase()
                    if (key !in known) throw QueryRejected("unknown parameter :$raw")
                    names += key
                    out.append('?')
                    i = match.range.last + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        val statement = out.toString().trim()
        if (statement.isEmpty()) throw QueryRejected("query is empty")
        return PreparedSql(statement, names)
    }

    /** End (exclusive) of the quoted string or identifier that starts at [i], or null when none starts there. */
    private fun quoteEnd(sql: String, i: Int, dialect: SqlDialect): Int? {
        val c = sql[i]
        if (dialect.postgres) {
            return when (c) {
                '\'' -> scanQuoted(sql, i, '\'', backslash = !dialect.standardConformingStrings || escapeString(sql, i))
                '"' -> scanQuoted(sql, i, '"', backslash = false)
                '$' -> dollarQuoteEnd(sql, i)
                else -> null
            }
        }
        if (dialect.mysql) {
            return when (c) {
                '\'', '"', '`' -> scanQuoted(sql, i, c, backslash = c != '`')
                else -> null
            }
        }
        if (dialect.mssql) {
            return when (c) {
                '\'', '"' -> scanQuoted(sql, i, c, backslash = false)
                '[' -> sql.indexOf(']', i + 1).let { if (it < 0) throw QueryRejected("unterminated quote") else it + 1 }
                else -> null
            }
        }
        return when (c) {
            '\'', '"', '`' -> scanQuoted(sql, i, c, backslash = false)
            '[' -> sql.indexOf(']', i + 1).let { if (it < 0) throw QueryRejected("unterminated quote") else it + 1 }
            else -> null
        }
    }

    /** Identifier characters; in PostgreSQL `$` continues an identifier, so `a$b$` is not a dollar quote. */
    private fun identifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$' || c.code >= 0x80

    /** True when the `'` at [quote] opens a PostgreSQL escape string: it follows a lone `E` or `e`. */
    private fun escapeString(sql: String, quote: Int): Boolean {
        if (quote < 1 || (sql[quote - 1] != 'E' && sql[quote - 1] != 'e')) return false
        return quote < 2 || !identifierChar(sql[quote - 2])
    }

    /** A dollar quote `$tag$…$tag$` (tag optional) starting at [start], or null when this `$` does not open one. */
    private fun dollarQuoteEnd(sql: String, start: Int): Int? {
        if (start > 0 && identifierChar(sql[start - 1])) return null
        var j = start + 1
        if (j < sql.length && sql[j] != '$') {
            val first = sql[j]
            if (!(first.isLetter() || first == '_' || first.code >= 0x80)) return null
            while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_' || sql[j].code >= 0x80)) j++
        }
        if (j >= sql.length || sql[j] != '$') return null
        val tag = sql.substring(start, j + 1)
        val close = sql.indexOf(tag, j + 1)
        if (close < 0) throw QueryRejected("unterminated quote")
        return close + tag.length
    }

    private fun scanQuoted(sql: String, start: Int, quote: Char, backslash: Boolean): Int {
        var i = start + 1
        while (i < sql.length) {
            val c = sql[i]
            if (backslash && c == '\\') {
                i += 2
                continue
            }
            if (c == quote) {
                if (i + 1 < sql.length && sql[i + 1] == quote) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        throw QueryRejected("unterminated quote")
    }
}

/**
 * A second opinion from the PostgreSQL driver: the statement list its own parser splits [statement] into,
 * which is what it would send to the server. The browser refuses anything that is not exactly one statement.
 */
internal object PgStatements {
    fun requireSingle(statement: String, standardConformingStrings: Boolean) {
        val parsed = try {
            org.postgresql.core.Parser.parseJdbcSql(statement, standardConformingStrings, false, true, false, false)
        } catch (e: java.sql.SQLException) {
            throw QueryRejected("the statement could not be parsed")
        }
        if (parsed.size != 1) throw QueryRejected("only one SQL statement is allowed")
        val sql = parsed[0].nativeSql
        if (SqlText.isTransactionControl(sql, SqlDialect(postgres = true, standardConformingStrings))) {
            throw QueryRejected("transaction statements are not accepted; each statement runs on its own")
        }
    }
}
