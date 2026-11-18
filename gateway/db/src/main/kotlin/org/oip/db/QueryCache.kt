package org.oip.db

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * One cache entry per named query, resource version, and parameter values.
 * Update queries are not cached. Saving the query drops that query's entries.
 */
internal class QueryCache(private val clock: Clock) {
    private data class Entry(val expiresAt: Long, val result: QueryResult)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(key: String): QueryResult? {
        val entry = entries[key] ?: return null
        if (clock.millis() >= entry.expiresAt) {
            entries.remove(key, entry)
            return null
        }
        return entry.result.cachedCopy()
    }

    fun put(key: String, ttlMillis: Long, result: QueryResult) {
        if (ttlMillis <= 0) return
        val stored = when (result) {
            is QueryResult.Rows -> result.copy(rows = result.rows.map { it.toList() }, cached = false)
            is QueryResult.Scalar -> result.copy(cached = false, fallback = false)
            is QueryResult.Update, is QueryResult.Procedure -> return
        }
        entries[key] = Entry(clock.millis() + ttlMillis, stored)
    }

    fun invalidate(name: String) {
        val prefix = "$name\u0000"
        entries.keys.removeIf { it.startsWith(prefix) }
    }

    fun clear() = entries.clear()

    companion object {
        fun key(name: String, version: Long, parameters: Map<String, Any?>): String {
            val body = parameters.entries.sortedBy { it.key }.joinToString("\u0001") { (k, v) -> "$k=${encode(v)}" }
            return "$name\u0000$version\u0000$body"
        }

        private fun encode(value: Any?): String = when (value) {
            null -> "null"
            is String -> "s:$value"
            is Boolean -> "b:$value"
            is Int, is Long -> "i:$value"
            is Float, is Double -> "d:${(value as Number).toDouble()}"
            is java.sql.Timestamp -> "t:${value.time}"
            else -> "o:$value"
        }
    }
}
