package org.oip.tags

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Tag path globs. A single star matches inside one path segment, a question mark matches one character
 * there, and a double star matches across segments. The provider is included, as in [default]Line/star.
 */
object TagPathPattern {
    fun isPattern(path: String): Boolean = path.any { it == '*' || it == '?' }

    fun matches(pattern: String, path: String): Boolean {
        val (patternProvider, patternPath) = split(pattern)
        val (pathProvider, relative) = split(path)
        return glob(patternProvider).matches(pathProvider) && glob(patternPath).matches(relative)
    }

    /** Tag paths that currently match [pattern]. Folders are walked; the pattern's literal prefix limits the walk. */
    fun collect(tags: TagEngine, pattern: String): List<String> {
        val (provider, relative) = split(pattern)
        val literal = relative.substringBefore('*').substringBefore('?')
        val prefix = literal.substringBeforeLast('/', "").trim('/')
        val start = if (prefix.isEmpty()) "[$provider]" else "[$provider]$prefix"
        val out = ArrayList<String>()
        fun walk(path: String, depth: Int) {
            if (depth > 32 || out.size >= 5_000) return
            val nodes = runCatching { tags.browse(path, 0, 5_000) }.getOrDefault(emptyList())
            for (node in nodes) {
                val full = node.path.toString()
                if (node.kind == NodeKind.TAG || node.kind == NodeKind.UDT_INSTANCE) {
                    if (matches(pattern, full)) out += full
                }
                if (node.kind == NodeKind.FOLDER || node.kind == NodeKind.UDT_INSTANCE) walk(full, depth + 1)
            }
        }
        walk(start, 0)
        return out
    }

    private fun split(path: String): Pair<String, String> {
        val text = path.trim()
        if (text.startsWith("[") && "]" in text) {
            val end = text.indexOf(']')
            return text.substring(1, end).ifBlank { "default" } to text.substring(end + 1).trim('/')
        }
        return "default" to text.trim('/')
    }

    private fun glob(pattern: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            if (pattern.startsWith("**", i)) {
                out.append(".*")
                i += 2
                continue
            }
            when (val c = pattern[i]) {
                '*' -> out.append("[^/]*")
                '?' -> out.append("[^/]")
                else -> {
                    if (c in REGEX_META) out.append('\\')
                    out.append(c)
                }
            }
            i++
        }
        return Regex("^$out$", RegexOption.IGNORE_CASE)
    }

    private const val REGEX_META = "\\.[]{}()+^$|"
}

/**
 * Subscribes to every tag that matches a glob, including tags configured after the subscription starts.
 * The config listener only schedules work; browse and subscribe run off the engine write lock.
 */
class PatternSubscription(
    private val tags: TagEngine,
    private val pattern: String,
    private val listener: (TagChange) -> Unit,
) : AutoCloseable {
    private val subs = ConcurrentHashMap<String, AutoCloseable>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "oip-tag-pattern").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private val config = tags.addConfigListener { _, _, _ ->
        if (!closed) worker.execute { sync() }
    }

    init {
        sync()
    }

    private fun sync() {
        if (closed) return
        val wanted = TagPathPattern.collect(tags, pattern).toSet()
        for (path in subs.keys) {
            if (path !in wanted) subs.remove(path)?.let { runCatching { it.close() } }
        }
        for (path in wanted) {
            if (subs.containsKey(path)) continue
            val sub = runCatching { tags.subscribe(path, listener) }.getOrNull() ?: continue
            if (subs.putIfAbsent(path, sub) != null) runCatching { sub.close() }
        }
    }

    override fun close() {
        closed = true
        runCatching { config.close() }
        subs.values.forEach { runCatching { it.close() } }
        subs.clear()
        worker.shutdownNow()
    }
}
