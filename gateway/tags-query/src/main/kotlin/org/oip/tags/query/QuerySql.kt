package org.oip.tags.query

/**
 * Turns `{tag path}` references in a query tag's SQL into JDBC `?` placeholders.
 *
 * The tag value is never written into the statement text. Quotes, identifiers, and comments are
 * copied through, so a reference inside a string literal stays a literal.
 *
 * Brace syntax is the public tag reference used by expressions and query tags:
 *
 * One statement, and it must read. A query tag does not get a second statement or a write.
 */
internal object QuerySql {
    const val MAX_CHARS = 100_000
    const val MAX_REFERENCES = 64

    data class Prepared(val sql: String, val references: List<String>)

    fun prepare(text: String): Prepared {
        if (text.isBlank()) throw QueryTagException("query is empty")
        if (text.length > MAX_CHARS) throw QueryTagException("query is too long")
        if (text.indexOf('\u0000') >= 0) throw QueryTagException("query contains a NUL")
        val out = StringBuilder(text.length)
        val refs = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val skip = commentEnd(text, i) ?: quoteEnd(text, i)
            if (skip != null) {
                out.append(text, i, skip)
                i = skip
                continue
            }
            val c = text[i]
            when {
                c == ';' -> {
                    if (text.substring(i + 1).isNotBlank()) throw QueryTagException("only one SQL statement is allowed")
                    i = text.length
                }
                c == '?' -> throw QueryTagException("use a {tag path} reference; '?' is not accepted")
                c == '{' -> {
                    val end = text.indexOf('}', i + 1)
                    if (end < 0 || '{' in text.substring(i + 1, end)) throw QueryTagException("unclosed tag reference")
                    val ref = text.substring(i + 1, end).trim()
                    if (ref.isEmpty()) throw QueryTagException("empty tag reference")
                    if (refs.size == MAX_REFERENCES) throw QueryTagException("too many tag references")
                    refs += ref
                    out.append('?')
                    i = end + 1
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        val sql = out.toString().trim()
        if (sql.isEmpty()) throw QueryTagException("query is empty")
        if (!isRead(sql)) throw QueryTagException("a query tag runs one read statement")
        return Prepared(sql, refs)
    }

    private val READ = setOf("select", "with", "values", "table", "show", "explain")
    private val DOLLAR = Regex("\\$[A-Za-z0-9_]*\\$")

    /** First word, after comments, whitespace, and parentheses. */
    private fun isRead(sql: String): Boolean {
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isWhitespace() || c == '(' -> i++
                sql.startsWith("--", i) -> {
                    val nl = sql.indexOf('\n', i)
                    i = if (nl < 0) sql.length else nl + 1
                }
                sql.startsWith("/*", i) -> {
                    val end = sql.indexOf("*/", i + 2)
                    if (end < 0) return false
                    i = end + 2
                }
                c.isLetter() -> {
                    val start = i
                    while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
                    return sql.substring(start, i).lowercase() in READ
                }
                else -> return false
            }
        }
        return false
    }

    /** End (exclusive) of a comment starting at [i], or null when none starts there. Block comments do not nest. */
    private fun commentEnd(sql: String, i: Int): Int? {
        if (sql.startsWith("--", i)) {
            val nl = sql.indexOf('\n', i)
            return if (nl < 0) sql.length else nl
        }
        if (!sql.startsWith("/*", i)) return null
        val end = sql.indexOf("*/", i + 2)
        if (end < 0) throw QueryTagException("unterminated comment")
        return end + 2
    }

    /**
     * End (exclusive) of a quoted string or identifier starting at [i], or null.
     * PostgreSQL dollar quotes and `E'...'` escapes are recognized so a `{tag}` inside them is not a parameter.
     * https://www.postgresql.org/docs/16/sql-syntax-lexical.html
     */
    private fun quoteEnd(sql: String, i: Int): Int? {
        val c = sql[i]
        if ((c == 'E' || c == 'e') && i + 1 < sql.length && sql[i + 1] == '\'') {
            return scanQuoted(sql, i + 1, '\'', backslash = true)
        }
        return when (c) {
            '\'' -> scanQuoted(sql, i, '\'', backslash = false)
            '"', '`' -> scanQuoted(sql, i, c, backslash = false)
            '[' -> {
                val end = sql.indexOf(']', i + 1)
                if (end < 0) throw QueryTagException("unterminated quote")
                end + 1
            }
            '$' -> dollarQuoteEnd(sql, i)
            else -> null
        }
    }

    private fun dollarQuoteEnd(sql: String, i: Int): Int? {
        val match = DOLLAR.matchAt(sql, i) ?: return null
        val tag = match.value
        val from = i + tag.length
        val end = sql.indexOf(tag, from)
        if (end < 0) throw QueryTagException("unterminated quote")
        return end + tag.length
    }

    private fun scanQuoted(sql: String, quoteAt: Int, quote: Char, backslash: Boolean): Int {
        var j = quoteAt + 1
        while (j < sql.length) {
            val c = sql[j]
            if (backslash && c == '\\' && j + 1 < sql.length) {
                j += 2
                continue
            }
            if (c == quote) {
                if (j + 1 < sql.length && sql[j + 1] == quote) {
                    j += 2
                    continue
                }
                return j + 1
            }
            j++
        }
        throw QueryTagException("unterminated quote")
    }
}

internal class QueryTagException(message: String) : IllegalArgumentException(message)
